package com.yejing.artvault

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant

/**
 * 稿件写入命令层（v2-C1）。
 *
 * **`meta/artworks` 下的稿件 JSON 只从这个 object 写。** 之前编辑、批量赋值、合并、版本管理、
 * 恢复各写各的，规则靠“每个地方都记得”维持——漏一处就是脏数据，而且没人会发现。
 *
 * 这里只有一条主干：读 → 改 → [normalize] 归一 → `commit` 原子写（补 rev / updated_at）。
 * 归一做的事都是**可自动修且不丢信息**的：
 * - 版本 id 缺失/重复 → 重新分配（排序也一并归一）
 * - 主图不是恰好一个 → 留第一个命中项，一个都没有就把第一张设为主图
 * - id 列表里的空项/重复项 → 清掉
 * - 价格为负 → 清空
 * 真正会丢信息的（没有任何版本、版本没有 blob）不在这里修，交给调用方当失败处理。
 *
 * 引用完整性：删实体 / 合并实体对稿件的批量改动也走这里（[stripRef]、[remapRef]）；
 * 把「改实体 + 清引用」包成一次操作的是 [EntityCommands]。
 * C3 会用 OperationJournal 记录 before/after，让合并这类多文件操作可崩溃恢复。
 */
object ArtworkCommands {

    fun dir(root: File): File = File(root, "meta/artworks")

    /**
     * 把稿件 JSON 移进回收站。
     *
     * **不进操作历史**：槽删除 / 合并并掉槽的可逆性由回收站负责（它本来就是干这个的）。
     * 历史只管"改内容"，两边都管会逼出「撤销恢复要挪回回收站」这种自相矛盾的实现。
     */
    private fun moveToTrash(src: File, dst: File): Boolean {
        val ok = src.renameTo(dst) || runCatching {
            src.copyTo(dst, overwrite = true)
            src.delete()
        }.getOrDefault(false)
        // 挪进回收站 = 这条稿在库里消失了，刷新时要把它从列表里摘掉
        if (ok) VaultChanges.mark(src.nameWithoutExtension)
        return ok
    }

    fun read(root: File, id: String): JSONObject? {
        val f = File(dir(root), "$id.json")
        if (!f.isFile) return null
        return runCatching { JSONObject(f.readText()) }.getOrNull()
    }

    /**
     * 归一化（原地修改并返回改了什么）。
     * 返回的说明目前只用于排查；界面上不弹——这些都是「本来就不该出现」的状态。
     */
    fun normalize(o: JSONObject, tax: Taxonomy? = null): List<String> {
        val notes = ArrayList<String>()

        // 版本：id 必填且唯一，sort 按当前顺序重排
        val arr = o.optJSONArray("versions") ?: JSONArray()
        val seen = HashSet<String>()
        var primary: JSONObject? = null
        for (i in 0 until arr.length()) {
            val v = arr.optJSONObject(i) ?: continue
            var id = v.strOrNull("id").orEmpty()
            if (id.isBlank() || id in seen) {
                id = newId()
                v.put("id", id)
                notes += "版本 ${i + 1} 的 id 缺失或重复，已重新分配"
            }
            seen += id
            v.put("sort", i)
            if (v.optBoolean("is_primary") && primary == null) primary = v
        }
        if (arr.length() > 0) {
            if (primary == null) {
                primary = arr.optJSONObject(0)
                notes += "没有主图，已把第一张设为主图"
            }
            for (i in 0 until arr.length()) {
                val v = arr.optJSONObject(i) ?: continue
                v.put("is_primary", v === primary)
            }
        }

        // 注意：**不校验「角色必须属于 world_id」**。
        // 角色是内容，world_id 是归档文件夹（相册导出只认一级子目录，必须单值）。
        // 一张跨作品联动的稿可以有 A 世界的角色、归到 B 世界的文件夹里——
        // 以前这里会把它们当脏数据删掉，等于替用户否决了合法排版。

        // id 列表：去空、去重（顺序保持）
        listOf("character_ids", "tag_ids").forEach { key ->
            val list = o.strList(key)
            val clean = list.distinct()
            if (clean.size != list.size) {
                o.put(key, JSONArray().also { a -> clean.forEach { a.put(it) } })
                notes += "$key 里有空项或重复项，已清理"
            }
        }

        o.strOrNull("artist_id") ?: o.put("artist_id", JSONObject.NULL)

        // 价格为负没有意义（元为单位，0 与 null 都表示「没记」）
        if (!o.isNull("price_total") && o.optLong("price_total", 0) < 0) {
            o.put("price_total", JSONObject.NULL)
            notes += "价格为负，已清空"
        }

        return notes
    }

    /**
     * 唯一的写盘点：归一 → **内容没变就什么都不做** → 补时间戳/rev → 原子写。
     *
     * 「内容没变」的判断放在补时间戳之前：`updated_at` / `rev` 每次都会变，
     * 早先无条件先补再写，于是「打开编辑、原样保存」也会 rev+1 并白记一条历史
     * （用户 2026-11-18 报的现象）。归一之后和盘上的内容比一次，只差这两个字段就不写。
     *
     * 返回 true 表示「盘上已经是这个内容」（可能是刚写的，也可能本来就没变）；
     * false 才是写失败。
     */
    fun commit(
        root: File,
        id: String,
        o: JSONObject,
        tax: Taxonomy? = null,
        bumpRev: Boolean = true,
    ): Boolean {
        normalize(o, tax)
        val file = File(dir(root), "$id.json")
        if (file.isFile && sameIgnoringStamps(file, o)) return true
        if (bumpRev) {
            o.put("updated_at", Instant.now().toString())
            o.put("rev", o.optInt("rev", 1) + 1)
        }
        val ok = writeJsonAtomic(file, o)
        if (ok) VaultChanges.mark(id)
        return ok
    }

    /**
     * 盘上那份和待写的这份，去掉 `updated_at` / `rev` 后是否完全相同。
     *
     * 偏保守：键顺序不同、或者一边有 `null` 一边没有这个键，都会判成「有变化」，
     * 结果只是多写一次（等于旧行为）。真正要避免的误判是「有变化却说没变」，那不可能发生。
     */
    private fun sameIgnoringStamps(file: File, next: JSONObject): Boolean = runCatching {
        val a = JSONObject(file.readText()).apply {
            remove("updated_at")
            remove("rev")
        }
        val b = JSONObject(next.toString()).apply {
            remove("updated_at")
            remove("rev")
        }
        a.toString() == b.toString()
    }.getOrDefault(false)

    // ── 编辑 ───────────────────────────────────────────────────────────

    /**
     * 只改星标一个键。不碰任何其他字段，也不重建 versions。
     * false 时直接 remove —— 让「没标」与「老文件本来就没这个键」在盘上长得一样。
     */
    suspend fun setStarred(root: File, artId: String, on: Boolean): Boolean =
        withContext(Dispatchers.IO) {
            val o = read(root, artId) ?: return@withContext false
            runCatching {
                if (on) o.put("starred", true) else o.remove("starred")
                commit(root, artId, o)
            }.getOrDefault(false)
        }

    suspend fun edit(
        root: File,
        artId: String,
        form: ImportForm,
        artists: ArtistBook,
        tags: TagBook,
        tax: Taxonomy? = null,
    ): Boolean = withContext(Dispatchers.IO) {
        val o = read(root, artId) ?: return@withContext false
        runCatching {
            o.put("title", form.title.trim())
            o.put("note", form.note.trim())
            o.put("world_id", form.worldId ?: JSONObject.NULL)
            o.put("folder_id", form.folderId ?: JSONObject.NULL)
            o.put("character_ids", JSONArray().also { a -> form.roleIds.forEach { a.put(it) } })
            o.put("tag_ids", JSONArray().also { a ->
                form.tags.map { it.trim() }.filter { it.isNotEmpty() }
                    .mapNotNull { tags.ensure(it) }.forEach { a.put(it) }
            })
            val artistId = form.artist.trim().takeIf { it.isNotEmpty() }?.let { artists.ensure(it) }
            if (artistId != null) o.put("artist_id", artistId) else o.put("artist_id", JSONObject.NULL)
            // 自制与「没填」必须分开存：没有这个键 = 没填，有且为 true = 自制
            if (form.selfMade) o.put("self_made", true) else o.remove("self_made")

            // 「推断」标记**完全信任表单**（EditScreen 没碰过日期时传 art.dateGuessed，碰过就传 false）。
            //
            // 以前这里还有一层「日期串没变就把旧标记 OR 回来」，本意是"没改就别丢标记"。但它让
            // **推断是对的、只想确认一下** 这种情况永远清不掉：用户点开日期、点确定，值没变，
            // 标记被 OR 回 true —— 待整理里的「待矫时」就永远消不掉（2026-11-22 用户报的）。
            // 现在「确认」由界面显式表达（`dateTouched = true`），数据层不再猜。
            // 旧键 received_inferred 必须删掉：留着会和 received_at_guessed 自相矛盾。
            o.put("received_at", form.receivedAt)
            o.put("received_at_guessed", form.dateGuessed)
            o.remove("received_inferred")
            if (form.priceTotal != null) o.put("price_total", form.priceTotal)
            else o.put("price_total", JSONObject.NULL)
            o.put("platform", form.platform.trim())
            commit(root, artId, o, tax)
        }.getOrDefault(false)
    }

    // ── 批量赋值 ───────────────────────────────────────────────────────

    private fun applyPatch(o: JSONObject, patch: AssignPatch, artists: ArtistBook, tags: TagBook) {
        val rawKind = o.strOrNull("kind")
        val isDerived = rawKind == ArtKind.Derived.wire
        val isFill = rawKind == ArtKind.Fill.wire
        val isArtwork = !isDerived && !isFill

        // 制品/制图不记金额、无画师/平台，且归档钉死在系统文件夹；
        // 制品的世界观与角色也继承自原稿（制图允许选世界观与角色）。
        if (!isDerived) {
            patch.worldId?.let { w ->
                o.put("world_id", if (w.isNotBlank()) w else JSONObject.NULL)
            }
        }
        if (isArtwork) {
            patch.folderId?.let { f ->
                o.put("folder_id", if (f.isNotBlank()) f else JSONObject.NULL)
            }
        }

        if (!isDerived && patch.roleMode != FieldMode.Skip) {
            val old = o.strList("character_ids")
            val next = if (patch.roleMode == FieldMode.Add) (old + patch.roleIds).distinct() else patch.roleIds
            o.put("character_ids", JSONArray().also { a -> next.forEach { a.put(it) } })
        }

        if (patch.tagMode != FieldMode.Skip) {
            // patch.tags 是名字：先 ensure 成 id 再并/替换。名字拼错在这里就被合并掉了
            val old = o.strList("tag_ids")
            val fresh = patch.tags.map { it.trim() }.filter { it.isNotEmpty() }
                .mapNotNull { tags.ensure(it) }
            val next = if (patch.tagMode == FieldMode.Add) (old + fresh).distinct() else fresh
            o.put("tag_ids", JSONArray().also { a -> next.forEach { a.put(it) } })
            // 老键顺手清掉，防止同一文件里两套标签并存
            o.remove("tags")
        }

        if (isArtwork) {
            patch.artist?.let { name ->
                if (name.isBlank()) {
                    o.put("artist_id", JSONObject.NULL)
                } else {
                    artists.ensure(name)?.let { o.put("artist_id", it) }
                }
                o.remove("artist")
            }
            // 自制与「没填」必须分开存：没有这个键 = 没填，有且为 true = 自制。
            // 批量赋值里点「自制」= artist_id 置空 + self_made=true（见 AssignPatch.selfMade）；
            // 赋成具体画师时要显式清掉它，否则会留下「有画师又自称自制」的矛盾状态。
            patch.selfMade?.let { sm ->
                if (sm) o.put("self_made", true) else o.remove("self_made")
            }
            patch.platform?.let { o.put("platform", it.trim()) }
        }

        patch.receivedAt?.let {
            o.put("received_at", it)
            o.put("received_at_guessed", false)
            o.remove("received_inferred")
        }

        if (isArtwork) {
            patch.priceTotal?.let { o.put("price_total", it) }
        }
    }

    suspend fun assign(
        root: File,
        ids: List<String>,
        patch: AssignPatch,
        artists: ArtistBook,
        tags: TagBook,
        tax: Taxonomy? = null,
        /** 每写完一件报一次（含读不到的那几件）。默认空实现，老调用点不用改 */
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): Int = withContext(Dispatchers.IO) {
        if (patch.isEmpty) return@withContext 0
        var n = 0
        ids.forEachIndexed { i, id ->
            val o = read(root, id)
            if (o != null) {
                runCatching {
                    applyPatch(o, patch, artists, tags)
                    // 写失败时盘上内容没变，这一件就不算改过
                    if (commit(root, id, o, tax)) n++
                }
            }
            onProgress(i + 1, ids.size)
        }
        n
    }

    // ── 版本管理（改名 / 排序 / 主图 / 删除） ──────────────────────────

    suspend fun setVersions(
        root: File,
        artId: String,
        edits: List<VerEdit>,
        primaryIndex: Int,
        tax: Taxonomy? = null,
    ): Boolean = withContext(Dispatchers.IO) {
        if (edits.isEmpty()) return@withContext false
        val o = read(root, artId) ?: return@withContext false

        runCatching {
            val old = o.optJSONArray("versions") ?: JSONArray()
            // 按 blob 建池：同一个 blob 出现两次（同图两个标签）也能各取各的
            val pool = HashMap<String, ArrayDeque<JSONObject>>()
            for (i in 0 until old.length()) {
                old.optJSONObject(i)?.let { obj ->
                    pool.getOrPut(obj.optString("blob")) { ArrayDeque() }
                        .addLast(JSONObject(obj.toString()))
                }
            }

            val arr = JSONArray()
            edits.forEachIndexed { i, e ->
                val obj = pool[e.blob]?.removeFirstOrNull() ?: JSONObject().put("blob", e.blob)
                if (obj.optString("id").isBlank()) obj.put("id", newId())
                obj.put("label", e.label)
                obj.put("is_primary", i == primaryIndex)
                obj.put("sort", i)
                arr.put(obj)
            }

            o.put("versions", arr)
            // 先落盘，成功才写回收站快照。
            // 反过来写的话，写盘失败会留下一条「其实还在槽里」的回收站记录
            // （恢复时靠去重能兜住，但回收站计数会虚高，而且人眼看不出来）。
            if (!commit(root, artId, o, tax)) return@runCatching false

            // 被删掉的版本 = 池里没被 edits 消耗的条目，整条进回收站：
            // 「删除」在这个 App 里一律要有兜底，哪怕只是几行 JSON。
            val removed = pool.values.flatten()
            if (removed.isNotEmpty()) {
                val vTrash = File(root, "meta/artworks/.trash/versions").apply { mkdirs() }
                removed.forEach { leftover ->
                    runCatching {
                        if (leftover.optString("id").isBlank()) leftover.put("id", newId())
                        val vid = leftover.optString("id")
                        // 同一个版本可能被删过多次（删 → 回收站恢复 → 再删）。
                        // 只留**最新一份**快照：否则回收站里会堆一串同 id 的记录，
                        // 恢复其中一条之后另外几条还在，计数虚高且都指向同一张图。
                        vTrash.listFiles { f -> f.name.startsWith("$artId.$vid") }?.forEach { it.delete() }
                        writeJsonAtomic(
                            File(vTrash, "$artId.$vid.json"),
                            JSONObject()
                                .put("type", "version")
                                .put("art_id", artId)
                                .put("art_title", o.strOrNull("title").orEmpty())
                                .put("version", leftover)
                        )
                    }
                }
            }
            true
        }.getOrDefault(false)
    }

    /**
     * 把 [artId] 里的若干个版本拆成一个新槽。
     *
     * - 源槽：摘掉这些版本后写回（主图若被摘走，`normalize` 会自动把第一张补为主图）
     * - 新槽：新 ULID，除价格外复制源槽；价格置 null —— 拆出来的是同一笔约稿的另一个形态，钱只记在源槽上，
     *   版本 = 被拆走的那几条（保持原顺序与原 id，不重建）
     * - 不动 blob：版本只是引用，内容寻址，两边共享同一批文件
     *
     * @return 新槽 id；参数非法（没有要拆的版本 / 全选）返回 null
     */
    suspend fun splitVersions(
        root: File,
        artId: String,
        verIds: Collection<String>,
        tax: Taxonomy? = null,
    ): String? = withContext(Dispatchers.IO) {
        if (verIds.isEmpty()) return@withContext null
        val src = read(root, artId) ?: return@withContext null
        val arr = src.optJSONArray("versions") ?: return@withContext null

        val moved = ArrayList<JSONObject>()
        val kept = ArrayList<JSONObject>()
        for (i in 0 until arr.length()) {
            val v = arr.optJSONObject(i) ?: continue
            val id = v.strOrNull("id").orEmpty()
            // 深拷贝：不能和源槽共享同一个 JSONObject 引用，否则先写源槽会污染新槽
            if (id.isNotEmpty() && id in verIds) moved += JSONObject(v.toString())
            else kept += v
        }
        // 全选 = 等于"搬空这个槽"，那是删除的语义，不是拆分
        if (moved.isEmpty() || kept.isEmpty()) return@withContext null

        val newSlotId = newId()
        val neu = JSONObject(src.toString())
        neu.put("id", newSlotId)
        val baseTitle = src.strOrNull("title").orEmpty().ifBlank { "未命名" }
        neu.put("title", "$baseTitle · 拆出")
        // 价格**不复制**：拆出来的是同一笔约稿的另一个形态，钱只记在源槽上。
        // 统计是逐槽累加 price_total 的（StatsScreen / ArtistScreen / TaxonomyScreen），
        // 复制过去等于让这一笔钱算两遍 —— 与「总价按张拆」那条口径直接冲突。
        neu.put("price_total", JSONObject.NULL)
        // 新槽是「现在」建的文件：不继承源槽的入仓时间，rev 也从 1 起
        val now = Instant.now().toString()
        neu.put("created_at", now)
        neu.put("updated_at", now)
        neu.put("rev", 1)
        moved.forEachIndexed { i, v -> v.put("is_primary", i == 0) }
        neu.put("versions", JSONArray().also { a -> moved.forEach { a.put(it) } })

        src.put("versions", JSONArray().also { a -> kept.forEach { a.put(it) } })

        // **先建新槽、后改源槽**。反过来的话，第二步失败 = 源槽已经少了几个版本、新槽又没建起来，
        // 而函数此时只能返回 null（不抛异常）→ journal 把这次操作当成成功，界面上只弹一句「拆分失败」。
        // 现在这个顺序：第一步失败什么都没发生（before/after 都没变，journal 自动丢弃这条记录）；
        // 第二步失败就把刚建的新槽删掉再抛，让 journal 用 before 把源槽也还原。
        if (!commit(root, newSlotId, neu, tax, bumpRev = false)) return@withContext null
        if (!commit(root, artId, src, tax)) {
            runCatching { File(dir(root), "$newSlotId.json").delete() }
            error("源稿写盘失败，已撤销这次拆分")
        }
        newSlotId
    }

    /**
     * 把源槽勾选的若干版本移入另一件已有稿件（不建新槽、不动原价格、不丢目标主图）。
     * 先写目标槽再写源槽：源槽写盘失败时抛异常，由 OperationJournal 用 before 全量回滚目标槽。
     * @return 实际移入的版本数；参数非法返回 0
     */
    suspend fun moveVersionsToArt(
        root: File,
        srcArtId: String,
        dstArtId: String,
        verIds: Collection<String>,
        tax: Taxonomy? = null,
    ): Int = withContext(Dispatchers.IO) {
        if (verIds.isEmpty() || srcArtId == dstArtId) return@withContext 0
        val src = read(root, srcArtId) ?: return@withContext 0
        val dst = read(root, dstArtId) ?: return@withContext 0
        val srcArr = src.optJSONArray("versions") ?: return@withContext 0
        val dstArr = dst.optJSONArray("versions") ?: JSONArray()

        val moved = ArrayList<JSONObject>()
        val kept = ArrayList<JSONObject>()
        for (i in 0 until srcArr.length()) {
            val v = srcArr.optJSONObject(i) ?: continue
            val id = v.strOrNull("id").orEmpty()
            if (id.isNotEmpty() && id in verIds) {
                moved += JSONObject(v.toString()).apply { put("is_primary", false) }
            } else {
                kept += v
            }
        }
        if (moved.isEmpty() || kept.isEmpty()) return@withContext 0

        val nextDst = JSONArray()
        for (i in 0 until dstArr.length()) {
            dstArr.optJSONObject(i)?.let { nextDst.put(it) }
        }
        moved.forEach { nextDst.put(it) }
        dst.put("versions", nextDst)
        src.put("versions", JSONArray().also { a -> kept.forEach { a.put(it) } })

        if (!commit(root, dstArtId, dst, tax)) return@withContext 0
        if (!commit(root, srcArtId, src, tax)) {
            error("源稿写盘失败，已撤销本次移动")
        }
        moved.size
    }

    // ── 合并 ───────────────────────────────────────────────────────────

    /**
     * 把多个槽合并成一个：图片全部变成目标槽的版本，其余 JSON 进回收站。
     * 价格一律清空——两个槽的单价相加没有意义；「原本各是多少」确认页里给用户看过。
     *
     * 失败要么全不动，要么把主槽还原：绝不能留下「主槽已并 + 原槽还在」的重复态。
     */
    suspend fun merge(
        root: File,
        ids: List<String>,
        primaryId: String,
        tax: Taxonomy? = null,
    ): MergeResult = withContext(Dispatchers.IO) {
        if (ids.size < 2) return@withContext MergeResult(false, error = "至少选两件")
        val files = ids.associateWith { File(dir(root), "$it.json") }
        if (files.values.any { !it.isFile }) return@withContext MergeResult(false, error = "有稿件找不到")

        runCatching {
            val objs = ids.associateWith { id -> JSONObject(files.getValue(id).readText()) }
            val kinds = objs.values.map { it.strOrNull("kind") ?: ArtKind.Artwork.wire }.toSet()
            if (kinds.size > 1) return@withContext MergeResult(false, error = "不能把制品/制图与普通稿合并")
            val primary = if (primaryId in ids) primaryId else ids.first()
            val target = objs.getValue(primary)
            val targetFile = files.getValue(primary)
            // 合并前的主槽原样留一份：后面任何一步失败都用它写回去
            val targetBackup = JSONObject(target.toString())

            val merged = ArrayList<JSONObject>()
            (listOf(primary) + ids.filter { it != primary }).forEach { id ->
                val arr = objs.getValue(id).optJSONArray("versions") ?: JSONArray()
                for (k in 0 until arr.length()) {
                    arr.optJSONObject(k)?.let { merged += JSONObject(it.toString()) }
                }
            }
            if (merged.isEmpty()) return@withContext MergeResult(false, error = "没有可合并的版本")

            dedupeLabels(merged)

            var hasPrimary = false
            merged.forEach { v ->
                val keep = !hasPrimary && v.optBoolean("is_primary")
                v.put("is_primary", keep)
                if (keep) hasPrimary = true
            }
            if (!hasPrimary) merged.first().put("is_primary", true)

            target.put("versions", JSONArray().also { a -> merged.forEach { a.put(it) } })
            // 价格取合计，不再清空：统计是逐槽累加的，清空等于让花过的钱凭空消失。
            // 如果这次合并其实是「同一笔付款被误拆成两个槽」，合计会翻倍——在详情里改一下即可，
            // 这比清空后可恢复（清空的信息是找不回来的）。
            val priceValues = ids.mapNotNull { id ->
                objs.getValue(id).let { if (it.isNull("price_total")) null else it.optLong("price_total", 0L) }
            }
            // 全都没记价格才算空；只要有值就写合计（0 元稿参与合计但不影响总数）
            target.put(
                "price_total",
                if (priceValues.isEmpty()) JSONObject.NULL else priceValues.sumOf { it.coerceAtLeast(0L) },
            )
            if (!commit(root, primary, target, tax)) {
                return@withContext MergeResult(false, error = "主槽写盘失败，其他槽都没动")
            }

            val trash = File(dir(root), ".trash").apply { mkdirs() }
            val others = ids.filter { it != primary }
            val done = ArrayList<Pair<File, File>>()
            var moved = 0
            var moveFailed = 0
            others.forEach { id ->
                val src = File(dir(root), "$id.json")
                // 回收站里已有同名（之前删/合并过一次）就加时间戳，不覆盖上一份
                var dst = File(trash, "$id.json")
                if (dst.exists()) dst = File(trash, "$id.${System.currentTimeMillis()}.json")
                // 走 moveToTrash：移走这件事要记进操作日志，否则撤销合并时原槽回不来
                val ok = moveToTrash(src, dst)
                if (ok) {
                    done += src to dst
                    moved++
                    // 打上「来自合并」标记：回收站里既有用户删的稿、也有合并时并掉的槽
                    runCatching {
                        val o = JSONObject(dst.readText())
                        o.put("deleted_via", "merge")
                        o.put("merged_into", primary)
                        writeJsonAtomic(dst, o)
                    }
                } else moveFailed++
            }
            if (moveFailed > 0) {
                // 1) 把已经移走的按内存里的原文写回活库，再删掉回收站里的副本。
                //    不要 rename 回去：那份已经被打上 deleted_via / merged_into 标记了。
                done.forEach { (src, dst) ->
                    runCatching {
                        writeJsonAtomic(src, objs.getValue(src.nameWithoutExtension))
                        dst.delete()
                    }
                }
                // 2) 还原主槽：宁可让用户重试，也不留下半合并
                writeJsonAtomic(targetFile, targetBackup)
                return@withContext MergeResult(
                    false,
                    error = "有 $moveFailed 件移不进回收站，已全部还原，请重试合并",
                )
            }
            // 合并已彻底落盘且不再回滚：把指向被并掉槽的二次创作改挂到主槽
            others.forEach { fromId ->
                remapRef(root, "source_art_id", fromId, primary)
            }
            MergeResult(true, merged.size, moved)
        }.getOrElse { MergeResult(false, error = it.message ?: "合并失败") }
    }

    /** 同名版本自动加序号：透明底 / 透明底 2 / 透明底 3。只改 label，一条都不丢 */
    private fun dedupeLabels(versions: List<JSONObject>) {
        val used = HashMap<String, Int>()
        versions.forEachIndexed { i, v ->
            val base = v.optString("label").trim().ifBlank { "版本 ${i + 1}" }
            val n = (used[base] ?: 0) + 1
            used[base] = n
            v.put("label", if (n == 1) base else "$base $n")
        }
    }

    // ── 删除 ───────────────────────────────────────────────────────────

    /**
     * 删除稿件：JSON 移进 `.trash/`，图片文件一律不动（内容寻址，可能多槽共享）。
     *
     * **刻意不清子件的 `source_art_id`**：删稿进回收站、可恢复；清掉后从回收站恢复原稿，
     * 制品与原稿的关联就永远找不回来了（画师/标签/世界观删除不可恢复，那才必须立刻清）。
     * 原稿在回收站期间由详情页「原稿已删除」与 Doctor「原稿缺失」兜住。
     */
    suspend fun delete(
        root: File,
        ids: List<String>,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): DeleteResult = withContext(Dispatchers.IO) {
        if (ids.isEmpty()) return@withContext DeleteResult(false, error = "没有选中任何稿件")
        val live = dir(root)
        val trash = File(live, ".trash").apply { mkdirs() }
        var moved = 0
        var fail = 0

        ids.forEachIndexed { i, id ->
            val src = File(live, "$id.json")
            if (!src.isFile) {
                fail++
                onProgress(i + 1, ids.size)
                return@forEachIndexed
            }
            var dst = File(trash, "$id.json")
            if (dst.exists()) dst = File(trash, "$id.${System.currentTimeMillis()}.json")
            val ok = moveToTrash(src, dst)
            if (ok) moved++ else fail++
            onProgress(i + 1, ids.size)
        }

        DeleteResult(
            ok = fail == 0,
            moved = moved,
            error = if (fail == 0) null else "有 $fail 件没删掉",
        )
    }

    // ── 二次创作（类型与原稿关联）────────────────────────────────────────

    private const val MAX_SOURCE_DEPTH = 32

    /**
     * 校验 `artId` 能否关联到 `sourceId`：禁止空、自引用、不存在的目标、已有环或超过 32 层的链。
     */
    private fun isValidSourceLink(root: File, artId: String, sourceId: String): Boolean {
        if (sourceId.isBlank() || sourceId == artId) return false
        var cur: String? = sourceId
        val visited = HashSet<String>()
        var depth = 0
        while (cur != null) {
            if (cur == artId) return false
            if (!visited.add(cur)) return false
            if (depth >= MAX_SOURCE_DEPTH) return false
            val obj = read(root, cur) ?: return false
            cur = obj.strOrNull("source_art_id")
            depth++
        }
        return true
    }

    /**
     * 修改稿件类型与原稿指向（不动其它字段，交由界面层按 kind 隐藏/展示）。
     * - 切回 [ArtKind.Artwork] 时必须 `remove("kind")`，保持「普通稿不写 kind 键」契约；
     * - 切到 [ArtKind.Derived] 时 `sourceArtId` 必填且需通过环检测。
     */
    suspend fun setKind(
        root: File,
        artId: String,
        kind: ArtKind,
        sourceArtId: String?,
        tax: Taxonomy? = null,
    ): Boolean = withContext(Dispatchers.IO) {
        val src = sourceArtId?.trim()?.takeIf { it.isNotEmpty() }
        if (kind == ArtKind.Derived && src == null) return@withContext false
        if (src != null && !isValidSourceLink(root, artId, src)) return@withContext false
        val o = read(root, artId) ?: return@withContext false
        runCatching {
            if (kind == ArtKind.Artwork) o.remove("kind") else o.put("kind", kind.wire)
            if (src != null) o.put("source_art_id", src) else o.put("source_art_id", JSONObject.NULL)
            commit(root, artId, o, tax)
        }.getOrDefault(false)
    }

    /** 关联原稿（含自引用、目标存在性与 32 层环检测）。 */
    suspend fun linkSource(
        root: File,
        artId: String,
        sourceId: String,
        tax: Taxonomy? = null,
    ): Boolean = withContext(Dispatchers.IO) {
        val src = sourceId.trim()
        if (!isValidSourceLink(root, artId, src)) return@withContext false
        val o = read(root, artId) ?: return@withContext false
        runCatching {
            o.put("source_art_id", src)
            commit(root, artId, o, tax)
        }.getOrDefault(false)
    }

    /** 解除原稿关联。 */
    suspend fun unlinkSource(
        root: File,
        artId: String,
        tax: Taxonomy? = null,
    ): Boolean = withContext(Dispatchers.IO) {
        val o = read(root, artId) ?: return@withContext false
        runCatching {
            o.put("source_art_id", JSONObject.NULL)
            commit(root, artId, o, tax)
        }.getOrDefault(false)
    }

    /** 库里是否存在任意稿件把 [artId] 当原稿（防「制品套制品」或把已有制品的原稿转成制图） */
    private fun hasDerivedChildren(root: File, artId: String): Boolean {
        val files = dir(root).listFiles { f -> f.isFile && f.extension == "json" } ?: return false
        return files.any { f ->
            runCatching { JSONObject(f.readText()).strOrNull("source_art_id") == artId }.getOrDefault(false)
        }
    }

    /**
     * 把库内已有普通稿转为某张原稿的「制品」：
     * 单次原子写入 `kind = "derived"`、`source_art_id`、`folder_id = 系统归档「制品」`、`price_total = null`。
     * 走 `commit()` + `OperationJournal.run` 可一键撤销。
     */
    suspend fun convertToDerived(
        root: File,
        artId: String,
        sourceId: String,
        derivedFolderId: String,
        tax: Taxonomy? = null,
    ): Boolean = withContext(Dispatchers.IO) {
        val src = sourceId.trim()
        if (derivedFolderId.isBlank() || !isValidSourceLink(root, artId, src)) return@withContext false
        val o = read(root, artId) ?: return@withContext false
        if (o.strOrNull("kind") != null) return@withContext false
        if (hasDerivedChildren(root, artId)) return@withContext false
        runCatching {
            o.put("kind", ArtKind.Derived.wire)
            o.put("source_art_id", src)
            o.put("folder_id", derivedFolderId)
            o.put("price_total", JSONObject.NULL)
            // 世界观 / 角色 / 画师 / 平台 / 自制 一并清掉：制品的这几项是**继承原稿**（读取时解析），
            // 不落盘。不清的话角色页会按残留的旧 roleIds 把它归到旧角色下，
            // 而详情页显示的是原稿的角色 —— 同一件稿在两个页面属于不同角色。
            o.put("world_id", JSONObject.NULL)
            o.put("character_ids", JSONArray())
            o.put("artist_id", JSONObject.NULL)
            o.remove("platform")
            o.remove("self_made")
            commit(root, artId, o, tax)
        }.getOrDefault(false)
    }

    /**
     * 临时迁移命令：把库内已有普通稿转为「制图」：
     * 单次原子写入 `kind = "fill"`、`folder_id = 系统归档「制图」`、`price_total = null`，保留角色/标签/备注/时间。
     */
    suspend fun convertToFill(
        root: File,
        artId: String,
        fillFolderId: String,
        tax: Taxonomy? = null,
    ): Boolean = withContext(Dispatchers.IO) {
        if (fillFolderId.isBlank()) return@withContext false
        val o = read(root, artId) ?: return@withContext false
        if (o.strOrNull("kind") != null) return@withContext false
        if (hasDerivedChildren(root, artId)) return@withContext false
        runCatching {
            o.put("kind", ArtKind.Fill.wire)
            o.put("folder_id", fillFolderId)
            o.put("price_total", JSONObject.NULL)
            // 制图没有画师与「自制」的概念（界面已隐藏），平台也不显示 —— 一并清掉，
            // 免得残留的 artist_id 日后被统计或体检捡起来。世界观与角色保留（制图自带）。
            o.put("artist_id", JSONObject.NULL)
            o.remove("self_made")
            o.remove("platform")
            commit(root, artId, o, tax)
        }.getOrDefault(false)
    }

    // ── 引用完整性（C2）──────────────────────────────────────────

    /** 单值引用字段：删实体时置 null，合并时直接替换。其余 ref 都是数组 */
    private val SINGLE_REF_KEYS = setOf("world_id", "artist_id", "folder_id", "source_art_id")

    /**
     * 摘掉稿件上指向 [id] 的所有引用（**删实体时用**）。
     *
     * 为什么扫全库而不是用调用方内存里的 arts：那份快照可能是修改前的，而悬空引用是
     * 「删完就看不见」的东西——界面上只是少显示一个标签，谁都发现不了。宁可多读几百个
     * 几 KB 的 JSON。只有真正命中的文件才会重写（rev +1）。
     *
     * [key] 必须跟 schema 走：world_id / folder_id / artist_id 是单值，
     * character_ids / tag_ids 是数组。传错（比如当年的 world_ids）会一个都清不掉。
     */
    suspend fun stripRef(root: File, key: String, id: String): Int =
        if (id.isBlank()) 0 else stripRefs(root, key, setOf(id))

    /**
     * 批量版：一次扫库摘掉一批 id。
     *
     * 删十个角色调十次 [stripRef] 会把全库 JSON 读十遍——两千件时是两万次读盘。
     * 这里只扫一遍，命中的文件逐个重写。
     */
    suspend fun stripRefs(root: File, key: String, ids: Set<String>): Int =
        withContext(Dispatchers.IO) {
        if (ids.isEmpty()) return@withContext 0
        val live = dir(root)
        val files = live.listFiles { f -> f.isFile && f.extension == "json" } ?: return@withContext 0
        var n = 0
        files.forEach { f ->
            runCatching {
                val o = JSONObject(f.readText())
                var changed = false
                if (key in SINGLE_REF_KEYS) {
                    if (o.strOrNull(key) in ids) {
                        o.put(key, JSONObject.NULL)
                        changed = true
                    }
                } else {
                    val arr = o.optJSONArray(key) ?: return@runCatching
                    val next = (0 until arr.length())
                        .mapNotNull { arr.optString(it).takeIf { s -> s.isNotBlank() } }
                        .filter { it !in ids }
                    if (next.size != arr.length()) {
                        o.put(key, JSONArray().also { a -> next.forEach { a.put(it) } })
                        changed = true
                    }
                }
                if (changed && commit(root, o.strOrNull("id") ?: f.nameWithoutExtension, o)) n++
            }
        }
        n
    }

    /**
     * 把引用 [fromId] 改指向 [toId]（**合并两个实体时用**）。
     *
     * 数组字段会去重：合并标签后「A B」和「B」两件稿都应该只剩一个「B」。
     * 调用方负责删掉 [fromId] 那个实体本身（见 [EntityCommands]）。
     */
    suspend fun remapRef(root: File, key: String, fromId: String, toId: String): Int =
        withContext(Dispatchers.IO) {
            if (fromId.isBlank() || toId.isBlank() || fromId == toId) return@withContext 0
            val live = dir(root)
            val files = live.listFiles { f -> f.isFile && f.extension == "json" } ?: return@withContext 0
            var n = 0
            files.forEach { f ->
                runCatching {
                    val o = JSONObject(f.readText())
                    var changed = false
                    if (key in SINGLE_REF_KEYS) {
                        val selfId = o.strOrNull("id") ?: f.nameWithoutExtension
                        // 自引用防护：合并原稿与它自己的制品时，不能把主槽改成自己指自己
                        if (selfId != toId && o.strOrNull(key) == fromId) {
                            o.put(key, toId)
                            changed = true
                        }
                    } else {
                        val arr = o.optJSONArray(key) ?: return@runCatching
                        val cur = (0 until arr.length())
                            .mapNotNull { arr.optString(it).takeIf { s -> s.isNotBlank() } }
                        val next = cur.map { if (it == fromId) toId else it }.distinct()
                        if (next != cur) {
                            o.put(key, JSONArray().also { a -> next.forEach { a.put(it) } })
                            changed = true
                        }
                    }
                    if (changed && commit(root, o.strOrNull("id") ?: f.nameWithoutExtension, o)) n++
                }
            }
            n
        }
}
