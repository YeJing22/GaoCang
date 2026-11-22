package com.yejing.artvault

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.max
import kotlin.math.roundToInt

data class ImportForm(
    val worldId: String? = null,
    /** 归档文件夹（导出成一级目录）；和世界观无关，可空 */
    val folderId: String? = null,
    val roleIds: List<String> = emptyList(),
    val title: String = "",
    /** 画师**名字**（表单输入什么就是什么）；落盘时经 ensure() 换成 artist_id */
    val artist: String = "",
    /** 自己画的：不建画师实体，只落一个 self_made 标记 */
    val selfMade: Boolean = false,
    val platform: String = "",
    /** 自由备注，进搜索 */
    val note: String = "",
    /** 本批总价；多张时按张均摊，保证统计不翻倍 */
    val priceTotal: Long? = null,
    val receivedAt: String = Dates.now(),
    /** receivedAt 是从图片文件时间猜的（用户没改过的话就是 true） */
    val dateGuessed: Boolean = false,
    /** 标签**名字**；落盘时经 ensure() 换成 tag_ids */
    val tags: List<String> = emptyList(),
    /** 稿件类型：普通稿不写 `kind` 键 */
    val kind: ArtKind = ArtKind.Artwork,
    /** 二次创作关联的原稿 id */
    val sourceArtId: String? = null,
)

data class ImportResult(
    val done: Int,
    val failed: Int,
    val firstError: String? = null,
    /** 已跳过：内容已在库（或被本批前面的那张顶掉） */
    val skipped: Int = 0,
    /**
     * 建成/追加的**版本数**。只有「多版到一件」这条路会填（那时 `done` 是 1），
     * 别的路径留 0 —— 别拿它当"张数"用。
     */
    val versions: Int = 0,
)

private fun extOf(mime: String, name: String): String {
    val byMime = when (mime.lowercase()) {
        "image/png" -> "png"
        "image/jpeg" -> "jpg"
        "image/webp" -> "webp"
        "image/gif" -> "gif"
        "image/bmp" -> "bmp"
        "image/heic", "image/heif" -> "heic"
        else -> null
    }
    if (byMime != null) return byMime
    val n = name.substringAfterLast('.', "").lowercase()
    return if (n in setOf("png", "jpg", "jpeg", "webp", "gif", "bmp", "heic")) {
        if (n == "jpeg") "jpg" else n
    } else "png"
}

private fun decodeBounded(cr: ContentResolver, item: MediaItem): Bitmap? = runCatching {
    val src = ImageDecoder.createSource(cr, item.uri)
    ImageDecoder.decodeBitmap(src) { dec, info, _ ->
        val m = max(info.size.width, info.size.height)
        if (m > 1400) dec.setTargetSampleSize(((m + 1399) / 1400))
        dec.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        dec.isMutableRequired = false
    }
}.getOrNull()

/** 采样检查是否真有透明像素（很多 PNG 带 alpha 通道但全不透明） */
private fun reallyHasAlpha(bmp: Bitmap): Boolean {
    if (!bmp.hasAlpha()) return false
    val stepX = max(1, bmp.width / 24)
    val stepY = max(1, bmp.height / 24)
    var y = 0
    while (y < bmp.height) {
        var x = 0
        while (x < bmp.width) {
            if ((bmp.getPixel(x, y) ushr 24) < 250) return true
            x += stepX
        }
        y += stepY
    }
    return false
}

private fun writeThumb(bmp: Bitmap, dst: File) {
    val m = max(bmp.width, bmp.height)
    val scale = if (m > 640) 640f / m else 1f
    val w = (bmp.width * scale).roundToInt().coerceAtLeast(1)
    val h = (bmp.height * scale).roundToInt().coerceAtLeast(1)
    val out = if (scale < 1f) Bitmap.createScaledBitmap(bmp, w, h, true) else bmp
    dst.parentFile?.mkdirs()
    val tmp = File(dst.parentFile, dst.name + ".tmp")
    FileOutputStream(tmp).use { out.compress(Bitmap.CompressFormat.WEBP, 82, it) }
    if (!tmp.renameTo(dst)) {
        dst.delete()
        tmp.renameTo(dst)
    }
    if (out !== bmp) out.recycle()
}

/** 一张图落盘后的结果，建新槽和往老槽加版本共用 */
private data class Stored(
    val sha: String,
    val ext: String,
    val w: Int,
    val h: Int,
    val alpha: Boolean,
    val srcName: String,
)

/**
 * 把选中项写进 blobs/ 和 thumbs/，返回可写进 JSON 的版本字段。
 * 内容相同的图不会重复占空间。
 */
private fun storeOne(cr: ContentResolver, root: File, item: MediaItem): Stored {
    val ext = extOf(item.mime, item.name)
    val md = MessageDigest.getInstance("SHA-256")

    // 流式：边读边算哈希边写临时文件。原来是 readBytes() 整文件进内存，
    // 一张几十 MB 的 PNG 就是一份瞬时堆，批量导入时直接把内存打穿。
    val staging = File(File(root, "blobs"), ".staging").apply { mkdirs() }
    val tmp = File(staging, "in.${System.nanoTime()}.part")
    val ins = cr.openInputStream(item.uri)
    if (ins == null) {
        tmp.delete()
        error("读不到文件流")
    }
    runCatching {
        ins.use { stream ->
            tmp.outputStream().use { out ->
                val buf = ByteArray(64 * 1024)
                var r = stream.read(buf)
                while (r >= 0) {
                    md.update(buf, 0, r)
                    out.write(buf, 0, r)
                    r = stream.read(buf)
                }
            }
        }
    }.getOrElse {
        // 读一半断掉也要把 staging 清掉，否则碎片会一直躺在 blobs/.staging/ 里
        tmp.delete()
        throw it
    }

    val sha = md.digest().joinToString("") { "%02x".format(it) }
    val blobFile = File(File(File(root, "blobs"), sha.take(2)), "$sha.$ext")
    if (blobFile.isFile) {
        // 内容已存在（内容寻址天然去重），staging 直接丢
        tmp.delete()
    } else {
        blobFile.parentFile?.mkdirs()
        if (!tmp.renameTo(blobFile)) {
            tmp.delete()
            error("写不进 blobs/")
        }
    }

    val bmp = decodeBounded(cr, item)
    val w = bmp?.width ?: item.w
    val h = bmp?.height ?: item.h
    val alpha = bmp?.let { reallyHasAlpha(it) } ?: (ext == "png")
    if (bmp != null) {
        val thumbFile = File(File(root, "thumbs"), "$sha.w640.webp")
        if (!thumbFile.isFile) writeThumb(bmp, thumbFile)
        bmp.recycle()
    }
    return Stored(sha, ext, w, h, alpha, item.name)
}

private fun Stored.toJson(primary: Boolean, label: String? = null): JSONObject = JSONObject()
    .put("id", newId())
    .put("blob", sha)
    .put("label", label ?: if (alpha) "透明底" else "带背景")
    .put("ext", ext)
    .put("w", w)
    .put("h", h)
    .put("has_alpha", alpha)
    .put("is_primary", primary)
    .put("src_name", srcName)

/**
 * 先写 .tmp 再 rename，避免 Syncthing 读到半截 JSON。
 *
 * 失败时**绝不先删原文件**——那是盘上唯一的好副本，删掉再 rename 失败就什么都不剩了。
 * Linux 上同目录 rename 原子替换目标，失败本来就极罕见；真失败了就清掉 .tmp 返回 false，
 * 让调用方决定要不要报错。tmp 名带纳秒后缀，防并发写同一文件时两个 .tmp 互相覆盖。
 * 返回 false 的语义是「盘上内容没变」，调用方按这个处理就行。
 */
internal fun writeJsonAtomic(f: File, o: JSONObject): Boolean {
    val parent = f.parentFile ?: return false
    parent.mkdirs()
    val text = o.toString(2)
    // before 只在有活动操作时才读（平时多一次无谓 IO 没必要）
    val before = if (OperationJournal.isRecording()) {
        runCatching { if (f.isFile) f.readText() else null }.getOrNull()
    } else {
        null
    }
    val tmp = File(parent, "${f.name}.${System.nanoTime()}.tmp")
    val ok = runCatching {
        tmp.writeText(text)
        val renamed = tmp.renameTo(f)
        if (!renamed) tmp.delete()
        renamed
    }.onFailure {
        // 以前这里只 getOrDefault(false)，写失败连原因都看不到——
        // 「删了又回来」「改完没保存」这类问题全被这一行吞掉。
        android.util.Log.e("GaoCangIO", "writeJsonAtomic failed: ${f.absolutePath}", it)
    }.getOrDefault(false)
    if (ok) OperationJournal.record(f, before, text)
    return ok
}

/**
 * 一张图一个槽，写一个 JSON。
 *
 * 价格分两段算：**先把图全部落盘，再按实际成功的张数均摊**。
 * 原来按原始张数摊，中途失败一张，已入库的合计就不等于输入的总价了——
 * 统计页的「总支出」会悄悄错，而这种错没有任何地方能发现。
 */
suspend fun importMedia(
    ctx: Context,
    root: File,
    items: List<MediaItem>,
    form: ImportForm,
    artists: ArtistBook,
    tags: TagBook,
    /** 全库所有 version.blob 的集合（由 arts 算出，不是 blobs/ 目录） */
    knownBlobs: Set<String>,
    tax: Taxonomy? = null,
): ImportResult {
    val cr = ctx.contentResolver
    val artworks = File(root, "meta/artworks")
    artworks.mkdirs()

    val now = Instant.now().toString()
    var failed = 0
    var firstErr: String? = null

    // 表单里的画师 / 标签是名字，先换成 id（没有的当场建实体——
    // 「随手打个新名字」不该要求用户先去别处建档再回来选）
    val artistId = form.artist.trim().takeIf { it.isNotEmpty() }
        ?.let { artists.ensure(it) }
    val tagIds = form.tags.map { it.trim() }.filter { it.isNotEmpty() }
        .mapNotNull { tags.ensure(it) }

    // 第一段：图全部落 blobs/ + thumbs/，失败的先记下
    val stored = ArrayList<Stored>()
    items.forEach { item ->
        runCatching { stored += storeOne(cr, root, item) }
            .onFailure {
                failed++
                if (firstErr == null) firstErr = it.message ?: it::class.simpleName
            }
    }
    if (stored.isEmpty()) return ImportResult(0, failed, firstErr)

    // 判重（保护：任务卡C 2.2 / 2.3 + AGENTS「删除永不动 blob」）：
    // 查「全库有没有 version 引用这个 sha」，**不查 blobs/ 文件是否存在**——
    // 删稿件不删 blob，磁盘上必有孤儿 blob，按文件存在判断会把「删了再导入」误判成重复。
    // seen 防本批内自己重复：两张同内容的图此刻都还不在 knownBlobs 里。
    val uniq = ArrayList<Stored>(stored.size)
    val seen = HashSet<String>()
    var skipped = 0
    stored.forEach { s ->
        if (s.sha in knownBlobs || !seen.add(s.sha)) skipped++ else uniq += s
    }
    if (uniq.isEmpty()) return ImportResult(0, failed, firstErr, skipped)

    // 第二段：按**实际建成的条数** m = uniq.size 均摊（不是 stored.size），余数记在第一张，保证合计 == 输入值。
    // 保护：统计页总支出。跳过的图还按原张数摊，合计会悄悄少一笔且无处报错。
    val m = uniq.size
    val total = form.priceTotal
    val per = if (total != null) total / m else null
    var done = 0

    uniq.forEachIndexed { idx, s ->
        val price = when {
            total == null -> null
            per == null -> total
            idx == 0 -> total - per * (m - 1)
            else -> per
        }
        runCatching {
            val id = newId()
            val o = newArtJson(
                id = id,
                form = form,
                artistId = artistId,
                tagIds = tagIds,
                now = now,
                price = price,
                versions = JSONArray().put(s.toJson(primary = true)),
            )
            // 唯一写盘点：归一（一主图 / 版本 id / 角色属于世界观）后再落盘
            if (!ArtworkCommands.commit(root, id, o, tax, bumpRev = false)) error("写盘失败")
            done++
        }.onFailure {
            failed++
            if (firstErr == null) firstErr = it.message ?: it::class.simpleName
        }
    }
    return ImportResult(done, failed, firstErr, skipped)
}

/**
 * 多张图建成**同一件**的多个版本（一槽多版本）。
 *
 * 与 [importMedia] 的差别只有两条：
 * - 只写一个 JSON、一次 commit；版本顺序 = 选中顺序，第一张当主图。
 * - **价格不摊**：整笔记在这一件上 —— 这是"同一次约稿的不同形态"（带背景 / 透明底 / 线稿），
 *   不是几张稿。摊了就与「总价按张拆」那条口径冲突。
 *
 * 版本名优先用**原文件名**（去扩展名）：从设计软件 / 米画师导出的图，文件名本来就是
 * 「带背景 / 透明底 / 线稿」，比默认的按 alpha 猜准得多。纯数字/英文文件名不采用，
 * 退回默认（见 [versionLabel]）。
 */
suspend fun importVersions(
    ctx: Context,
    root: File,
    items: List<MediaItem>,
    form: ImportForm,
    artists: ArtistBook,
    tags: TagBook,
    /** 全库所有 version.blob 的集合（由 arts 算出，不是 blobs/ 目录） */
    knownBlobs: Set<String>,
    tax: Taxonomy? = null,
): ImportResult {
    val cr = ctx.contentResolver
    File(root, "meta/artworks").mkdirs()

    val now = Instant.now().toString()
    var failed = 0
    var firstErr: String? = null

    val artistId = form.artist.trim().takeIf { it.isNotEmpty() }?.let { artists.ensure(it) }
    val tagIds = form.tags.map { it.trim() }.filter { it.isNotEmpty() }.mapNotNull { tags.ensure(it) }

    val stored = ArrayList<Stored>()
    items.forEach { item ->
        runCatching { stored += storeOne(cr, root, item) }
            .onFailure {
                failed++
                if (firstErr == null) firstErr = it.message ?: it::class.simpleName
            }
    }
    if (stored.isEmpty()) return ImportResult(0, failed, firstErr)

    // 判重口径与 importMedia 一致：查「全库是否有 version 引用这个 sha」，不查 blob 文件
    val uniq = ArrayList<Stored>(stored.size)
    val seen = HashSet<String>()
    var skipped = 0
    stored.forEach { s ->
        if (s.sha in knownBlobs || !seen.add(s.sha)) skipped++ else uniq += s
    }
    if (uniq.isEmpty()) return ImportResult(0, failed, firstErr, skipped)

    val id = newId()
    val versions = JSONArray()
    uniq.forEachIndexed { i, s ->
        versions.put(s.toJson(primary = i == 0, label = versionLabel(s.srcName)))
    }

    val fail = runCatching {
        val o = newArtJson(
            id = id,
            form = form,
            artistId = artistId,
            tagIds = tagIds,
            now = now,
            // 整笔记一件：不做均摊
            price = form.priceTotal,
            versions = versions,
        )
        if (!ArtworkCommands.commit(root, id, o, tax, bumpRev = false)) error("写盘失败")
    }.exceptionOrNull()
    if (fail != null) return ImportResult(0, failed, "写入失败：${fail.message}", skipped)

    return ImportResult(done = 1, failed = failed, firstError = firstErr, skipped = skipped, versions = uniq.size)
}

/** 版本名：文件名含中文才用（`带背景.png` → `带背景`），否则交给默认的「透明底 / 带背景」 */
private fun versionLabel(srcName: String): String? {
    val base = srcName.substringBeforeLast('.', srcName).trim()
    if (base.isEmpty() || base.length > 24) return null
    return base.takeIf { Regex("[\\u3400-\\u4dbf\\u4e00-\\u9fff]").containsMatchIn(it) }
}

/**
 * 稿件 JSON 的公共骨架（**唯一一份**）。`importMedia` 与 `importVersions` 都从这里出，
 * 免得以后加字段时改一处漏一处 —— 那正是"写盘契约靠每处都记得"的老毛病。
 */
private fun newArtJson(
    id: String,
    form: ImportForm,
    artistId: String?,
    tagIds: List<String>,
    now: String,
    price: Long?,
    versions: JSONArray,
): JSONObject = JSONObject().apply {
    put("schema", 2)
    put("id", id)
    put("title", form.title.trim())
    put("note", form.note.trim())
    put("world_id", form.worldId ?: JSONObject.NULL)
    put("folder_id", form.folderId ?: JSONObject.NULL)
    put("character_ids", JSONArray().also { a -> form.roleIds.forEach { a.put(it) } })
    put("tag_ids", JSONArray().also { a -> tagIds.forEach { a.put(it) } })
    put("artist_id", artistId ?: JSONObject.NULL)
    if (form.selfMade) put("self_made", true) else remove("self_made")
    put("received_at", form.receivedAt)
    put("received_at_guessed", form.dateGuessed)
    put("price_total", price ?: JSONObject.NULL)
    put("currency", "CNY")
    put("platform", form.platform.trim())
    put("created_at", now)
    put("updated_at", now)
    put("rev", 1)
    // 普通稿不写 kind 键；二次创作按需写 kind 与 source_art_id
    if (form.kind != ArtKind.Artwork) put("kind", form.kind.wire)
    form.sourceArtId?.trim()?.takeIf { it.isNotEmpty() }?.let { put("source_art_id", it) }
    put("versions", versions)
}

/**
 * 给已有槽追加版本（带背景 + 透明底放同一个槽）。
 * 只改 versions / updated_at / rev，其它字段原样保留，不改写不认识的键。
 */
suspend fun appendVersions(
    ctx: Context,
    root: File,
    artId: String,
    items: List<MediaItem>,
    /** 全库 version.blob 集合（含本槽自己的）；判重口径同 importMedia */
    knownBlobs: Set<String>,
): ImportResult {
    val cr = ctx.contentResolver
    val f = File(File(root, "meta/artworks"), "$artId.json")
    if (!f.isFile) return ImportResult(0, items.size, "找不到这一个槽的记录")

    var done = 0
    var failed = 0
    var skipped = 0
    var firstErr: String? = null
    val now = Instant.now().toString()

    val added = ArrayList<JSONObject>()
    // 保护：本批内重复（seen）+ 库内已有（knownBlobs，查引用而非 blob 文件）
    val seen = HashSet<String>()
    items.forEach { item ->
        runCatching {
            val s = storeOne(cr, root, item)
            if (s.sha in knownBlobs || !seen.add(s.sha)) {
                skipped++
            } else {
                added += s.toJson(primary = false)
                done++
            }
        }.onFailure {
            failed++
            if (firstErr == null) firstErr = it.message ?: it::class.simpleName
        }
    }
    if (added.isEmpty()) return ImportResult(0, failed, firstErr, skipped)

    runCatching {
        val o = JSONObject(f.readText())
        val vs = o.optJSONArray("versions") ?: JSONArray()
        // 原来没有主图就把第一个设成主图
        if ((0 until vs.length()).none { vs.optJSONObject(it)?.optBoolean("is_primary") == true }) {
            added.firstOrNull()?.put("is_primary", true)
        }
        added.forEach { vs.put(it) }
        o.put("versions", vs)
        o.put("updated_at", now)
        // rev 自己加：commit 走 bumpRev=false，避免同一份文件被 bump 两次
        o.put("rev", o.optInt("rev", 1) + 1)
        if (!ArtworkCommands.commit(root, artId, o, tax = null, bumpRev = false)) error("写盘失败")
    }.onFailure {
        // 带上 skipped：这张提示里「N 张已在库」和「写盘失败」是两件事，别把前者吃掉
        return ImportResult(0, items.size, "写入失败：${it.message}", skipped)
    }

    return ImportResult(done, failed, firstErr, skipped)
}

/**
 * 修改已有槽的元数据。只动这几个字段，versions / created_at 等原样保留，
 * 不改写不认识的键，避免把别的工具写的字段冲掉。
 */
suspend fun updateArtwork(
    root: File,
    artId: String,
    form: ImportForm,
    artists: ArtistBook,
    tags: TagBook,
    tax: Taxonomy? = null,
): Boolean = ArtworkCommands.edit(root, artId, form, artists, tags, tax)

/** 取一批图里最早的保存日期，用来预填收稿日期 */
fun guessDate(items: List<MediaItem>): String? {
    val d = items.firstOrNull()?.dateMs?.takeIf { it > 0 } ?: return null
    return runCatching {
        Instant.ofEpochMilli(d).atZone(ZoneId.systemDefault()).toLocalDate().toString()
    }.getOrNull()
}
