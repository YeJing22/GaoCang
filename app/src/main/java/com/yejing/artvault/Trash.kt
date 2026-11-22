package com.yejing.artvault

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant

/** 回收站里的一条：可能解析得出 [Art]，也可能是个坏文件（那时只有文件名可用） */
data class TrashedArt(
    /** 恢复时要用的文件名主干：能解析就用 JSON 里的 id，否则退回文件名 */
    val id: String,
    val art: Art?,
    val file: File,
    /** 删除时间（文件 mtime），展示成「3 天前」 */
    val deletedAt: Long,
    /** true = 这条不是用户删的，是合并时被并掉的槽：内容已经全在合并目标里 */
    val viaMerge: Boolean = false,
    /** 合并目标的稿件 id（仅 viaMerge 时有） */
    val mergedInto: String? = null,
    /** 合并目标的标题（列表时顺手解析；目标已不在库里则为 null） */
    val mergedIntoTitle: String? = null,
)

/** 回收站里的一个被删版本：从某个槽的版本数组里摘掉的那一条 */
data class TrashedVersion(
    val artId: String,
    /** 删除那一刻槽的标题（快照，槽可能后来改了名甚至被删） */
    val artTitle: String,
    val ver: Ver,
    /** 原始版本对象原样保存，恢复时直接塞回版本数组 */
    val versionJson: JSONObject,
    val file: File,
    val deletedAt: Long,
    /** 所属槽现在还在不在库里（不在就恢复不了） */
    val artExists: Boolean,
    /** 所属槽现在的标题（列表时顺手解析） */
    val artTitleNow: String?,
)

/** 回收站条目：整槽的、或槽里单个版本的 */
sealed interface TrashEntry {
    val file: File
    val deletedAt: Long

    data class Slot(val t: TrashedArt) : TrashEntry {
        override val file: File get() = t.file
        override val deletedAt: Long get() = t.deletedAt
    }

    data class Version(val v: TrashedVersion) : TrashEntry {
        override val file: File get() = v.file
        override val deletedAt: Long get() = v.deletedAt
    }
}

/**
 * 回收站：`meta/artworks/.trash/`（整槽）+ `.trash/versions/`（槽里被删的单个版本）
 *
 * 收录两类东西：
 * - **删除**：整槽 JSON 移进来；删单个版本时把那一条版本对象存进 `versions/`
 * - **合并**：被并掉的槽也进这里（带 `deleted_via:"merge"` 标记）
 *
 * **图片 blob 永远不会被删**（内容寻址，一个 blob 可能被多个槽共享）。
 * 恢复只有两种：整槽搬回 `meta/artworks/`，或把版本对象塞回所属槽的数组。
 * 不做自动清理：一条 JSON 才几 KB，而删掉就再也拼不回「这张图是哪次约稿、多少钱、谁画的」。
 */
class Trash(private val root: File) {

    private val trashDir: File get() = File(root, "meta/artworks/.trash")
    private val versionsDir: File get() = File(trashDir, "versions")
    private val liveDir: File get() = File(root, "meta/artworks")

    fun list(): List<TrashEntry> {
        val slots = (trashDir.listFiles { f -> f.isFile && f.extension == "json" } ?: emptyArray())
            .map { f ->
                val art = runCatching { parseArt(f.readText()) }.getOrNull()
                // 合并来源的条目要带标记：回收站把「删除」和「合并」混在一起还不说话的话，
                // 用户会以为一次删除产生了多件垃圾
                val raw = runCatching { JSONObject(f.readText()) }.getOrNull()
                val viaMerge = raw?.optString("deleted_via") == "merge"
                val mergedInto = raw?.strOrNull("merged_into")
                val mergedIntoTitle = mergedInto?.let { id ->
                    runCatching {
                        val o = JSONObject(File(liveDir, "$id.json").readText())
                        o.strOrNull("title") ?: "未命名"
                    }.getOrNull()
                }
                TrashedArt(
                    id = art?.id?.takeIf { it.isNotBlank() } ?: f.nameWithoutExtension,
                    art = art,
                    file = f,
                    deletedAt = f.lastModified(),
                    viaMerge = viaMerge,
                    mergedInto = mergedInto,
                    mergedIntoTitle = mergedIntoTitle,
                )
            }
            .map { TrashEntry.Slot(it) }

        val versions = (versionsDir.listFiles { f -> f.isFile && f.extension == "json" } ?: emptyArray())
            .mapNotNull { f ->
                runCatching {
                    val o = JSONObject(f.readText())
                    if (o.optString("type") != "version") return@runCatching null
                    val v = o.optJSONObject("version") ?: return@runCatching null
                    val artId = o.optString("art_id").takeIf { it.isNotBlank() } ?: return@runCatching null
                    val artFile = File(liveDir, "$artId.json")
                    val artExists = artFile.isFile
                    val artTitleNow = if (artExists) {
                        runCatching {
                            JSONObject(artFile.readText()).strOrNull("title") ?: "未命名"
                        }.getOrNull()
                    } else null
                    TrashEntry.Version(
                        TrashedVersion(
                            artId = artId,
                            artTitle = o.strOrNull("art_title") ?: "未命名",
                            ver = Ver(
                                id = v.optString("id"),
                                blob = v.optString("blob"),
                                label = v.optString("label"),
                                ext = v.optString("ext"),
                                w = v.optInt("w"),
                                h = v.optInt("h"),
                                hasAlpha = v.optBoolean("has_alpha"),
                                isPrimary = v.optBoolean("is_primary"),
                            ),
                            versionJson = v,
                            file = f,
                            deletedAt = f.lastModified(),
                            artExists = artExists,
                            artTitleNow = artTitleNow,
                        )
                    )
                }.getOrNull()
            }

        return (slots + versions).sortedByDescending { it.deletedAt }
    }

    fun count(): Int = list().size

    /**
     * 恢复整槽：把 JSON 搬回 `meta/artworks/<id>.json`。
     * 如果那个 id 已经在库里（删了之后又从别处同步回来），不覆盖，返回 false 让界面说话。
     */
    fun restore(t: TrashedArt): Boolean {
        val target = File(liveDir, "${t.id}.json")
        if (target.exists()) return false
        // 恢复不进操作历史：它和删除是一对，都归回收站管（想反悔就再删一次）
        // 读原文清掉合并/删除标记再走 commit（bumpRev = false，不把恢复算成编辑）
        return runCatching {
            val o = JSONObject(t.file.readText())
            o.remove("deleted_via")
            o.remove("merged_into")
            o.remove("deleted_at")
            if (!ArtworkCommands.commit(root, t.id, o, bumpRev = false)) return@runCatching false
            t.file.delete()
            true
        }.getOrDefault(false)
    }

    /**
     * 恢复单个版本：塞回所属槽的版本数组末尾。
     * 槽里已经有同 id 的版本（比如恢复过一次）就不再加，直接把这条记录清掉——内容已经在了。
     */
    fun restore(v: TrashedVersion): Boolean {
        val f = File(liveDir, "${v.artId}.json")
        if (!f.isFile) return false
        return runCatching {
            val o = JSONObject(f.readText())
            val vs = o.optJSONArray("versions") ?: JSONArray()
            val id = v.ver.id
            val already = id.isNotBlank() &&
                (0 until vs.length()).any { vs.optJSONObject(it)?.optString("id") == id }
            if (already) {
                v.file.delete()
                return@runCatching true
            }
            vs.put(v.versionJson)
            o.put("versions", vs)
            // 走统一写盘点：归一（主图/版本 id）与 rev 都由它负责
            if (!ArtworkCommands.commit(root, v.artId, o)) return@runCatching false
            v.file.delete()
            true
        }.getOrDefault(false)
    }

    /** 彻底删除：只删这条 JSON，图片文件不动 */
    fun purge(e: TrashEntry): Boolean = runCatching { e.file.delete() }.getOrDefault(false)

    fun purgeAll(): Int = list().count { purge(it) }
}
