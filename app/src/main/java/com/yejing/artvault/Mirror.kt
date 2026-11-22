package com.yejing.artvault

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * 相册镜像：把稿件按「世界观」摊成普通文件夹，让系统图库能当相册翻。
 *
 * 为什么必须复制而不是改名/链接：原图在 blobs/<2位hex>/<sha256> 下，
 * 是内容寻址的，多个槽共享同一份；改它的名字会连带毁掉去重和引用。
 *
 * 时间为的是「在图库里按时间找」：收稿日期只有日期没有时刻，
 * 统一设成当天 12:00，避免时区换算把日期挤到前后一天。
 *
 * **一个版本导出一张**（不是只导主图）：这套导出本来就是为「同一个槽的
 * 带背景 / 透明底 / 线稿在图库里紧挨着对比」而做的。所以同槽各版本的
 * mtime 按版本顺序逐秒递增——图库按时间排序时它们必然相邻且顺序稳定。
 */
object Mirror {

    /** 镜像根目录名，放在库根下，PC 经 Syncthing 也能直接翻 */
    const val DIR = "相册"
    private const val MANIFEST = "meta/mirror.json"

    /**
     * 文件名里可以带哪些字段。顺序固定（日期在前），用户的开关只决定「要不要」，
     * 不决定「排在哪」——否则不同人的文件名排序习惯会把整个相册搞乱。
     */
    enum class NameField(val label: String, val sample: String) {
        DATE("日期", "2026-11-15"),
        ROLE("角色", "忍"),
        TITLE("标题", "半身"),
        VERSION("版本", "透明底"),
    }

    val ALL_FIELDS: Set<NameField> = NameField.values().toSet()

    /**
     * 库内「App 自己产出」的目录：镜像、原图、缩略图。
     * 媒体选择器要把它们排除掉，否则同步完的镜像会整屏堆在最前面
     * （它们刚被扫描，DATE_ADDED 最新）。
     */
    /**
     * 选择器要排除的「库内自产目录」——**只列内部缓存**。
     *
     * 以前这里还列了 `相册`，理由是「刚导出的文件 DATE_ADDED 最新，会在选择器里堆满第一屏」。
     * 但那个名单里 blobs / thumbs 本来就有 `.nomedia`、MediaStore 里根本没有它们的记录，
     * **于是唯一真正被排除掉的就是用户自己导出的镜像** —— 表现为「选择器看不到自己导出的图」。
     * 宁可让它们排在前面，也不能让成品图从此找不回来。
     */
    fun generatedDirs(root: File): List<File> =
        listOf(File(root, "blobs"), File(root, "thumbs"))

    fun fieldsOf(keys: Set<String>): Set<NameField> =
        keys.mapNotNull { k -> runCatching { NameField.valueOf(k) }.getOrNull() }
            .toSet()
            .ifEmpty { ALL_FIELDS }

    /**
     * 一条导出计划 = 一个**版本**。
     * [key] 用 `artId:verId`（manifest 的键），同槽多版本才有各自的记录。
     * [seq] 是版本在槽内的序号，用来给 mtime 加偏移，保证图库里紧挨着。
     */
    data class Item(
        val key: String,
        val src: File,
        val rel: String,
        val date: String?,
        val seq: Int = 0,
        /** 源文件的内容 sha256。**只有 src 真的是 blob 时才有值** —— 回落缩略图时必须为 null */
        val sha: String? = null,
    )

    data class VerifyReport(
        val total: Int,
        val ok: Int,
        /** 长度或内容对不上，需要重新导出 */
        val mismatch: List<String>,
        /** 目标文件不在 */
        val missing: List<String>,
        /** 没有 sha 可比（缩略图回落），只比了长度；不算失败，但要报数 */
        val unchecked: Int,
    )

    data class Result(
        val written: Int = 0,
        val skipped: Int = 0,
        val removed: Int = 0,
        val failed: Int = 0,
        val error: String? = null,
        /** 被用户取消（不是失败）。取消时 manifest **不写**，下次会重新覆盖。 */
        val cancelled: Boolean = false,
        /** 取消前已经写出去的文件（绝对路径），供调用方决定保留还是删掉 */
        val writtenPaths: List<String> = emptyList(),
        /** 写入失败的目标相对路径（`相册/xxx/yyy.png`）。空列表 = 全部成功 */
        val failedPaths: List<String> = emptyList(),
    ) {
        val ok: Boolean get() = error == null
    }

    /** 只导主图；没有原图时退回缩略图（演示库就是这种情况） */
    fun plan(
        root: File,
        lib: Library,
        arts: List<Art>,
        tax: Taxonomy,
        folders: FolderBook,
        fields: Set<NameField> = ALL_FIELDS,
        /** 全库稿件（用于解析制品原稿的角色名；单件导出时 arts 只有一条） */
        allArts: List<Art> = arts,
    ): List<Item> {
        val used = HashSet<String>()
        val byId = allArts.associateBy { it.id }
        return arts.flatMap { art ->
            // 导出目录用**归档分类**，不是世界观：世界观是设定归属，角色常跨世界观联动，
            // 拿它推文件夹一定推错。没分归档时按 kind 兜底。
            val world = art.folderId?.let { folders.byId(it)?.name }
                ?: when (art.kind) {
                    ArtKind.Derived -> "制品"
                    ArtKind.Fill -> "制图"
                    else -> "未分类"
                }
            val roleIdsForName = if (art.kind == ArtKind.Derived) {
                byId[art.sourceArtId]?.roleIds ?: emptyList()
            } else {
                art.roleIds
            }
            val roles = roleIdsForName.mapNotNull { tax.roleName(it) }.joinToString("+")
            // 多版本时**强制**带上版本名：否则同槽几个文件只能靠 (2)(3) 区分，
            // 就等于把「在相册里对比不同版本」这件事毁了。
            val use = if (art.versions.size > 1) fields + NameField.VERSION else fields

            art.versions.mapIndexedNotNull { vi, v ->
                val blobFile = lib.blob(v)
                val src = blobFile ?: lib.thumb(v)
                if (!src.isFile) return@mapIndexedNotNull null

                val parts = buildList {
                    // 文件名只要日期：带时分秒既长又难看，时间进的是文件 mtime
                    if (NameField.DATE in use) add(Dates.datePart(art.receivedAt))
                    if (NameField.ROLE in use) add(roles.ifBlank { "未归属" })
                    if (NameField.TITLE in use) add(art.title.ifBlank { "未命名" })
                    if (NameField.VERSION in use) add(v.label.ifBlank { "版本${vi + 1}" })
                }.map { sanitize(it) }.filter { it.isNotBlank() }

                val base = (if (parts.isEmpty()) "未命名" else parts.joinToString("_")).take(110)
                val ext = v.ext.ifBlank { src.extension.ifBlank { "png" } }
                var name = "$base.$ext"
                var n = 1
                while (!used.add("${sanitize(world)}/$name")) {
                    n++
                    name = "$base ($n).$ext"
                }
                Item(
                    key = "${art.id}:${v.id.ifBlank { vi.toString() }}",
                    src = src,
                    rel = "$DIR/${sanitize(world)}/$name",
                    date = art.receivedAt,
                    seq = vi,
                    sha = if (blobFile != null && v.blob.isNotBlank()) v.blob else null,
                )
            }
        }
    }

    suspend fun sync(
        ctx: android.content.Context,
        root: File,
        lib: Library,
        arts: List<Art>,
        tax: Taxonomy,
        folders: FolderBook,
        fields: Set<NameField> = ALL_FIELDS,
        /** 全量同步默认不导制品/制图；取消勾选时绝不删已导出文件，也不抹掉 manifest 记录 */
        includeDerived: Boolean = false,
        includeFill: Boolean = false,
        /** 进度回调（IO 线程）：2230 个版本要复制几分钟，没有进度用户会以为卡死 */
        onProgress: ((done: Int, total: Int) -> Unit)? = null,
        /**
         * 外部取消标志（每复制一个文件前查一次）。
         *
         * **不用协程取消实现**：`job.cancel()` 会让 `withContext` 在返回时抛 CancellationException，
         * 那样调用方就拿不到"已经写了哪些文件"，也就没法问用户"保留还是删掉"。用标志位则正常返回。
         */
        shouldStop: () -> Boolean = { false },
    ): Result =
        withContext(Dispatchers.IO) {
            // 先把 blobs/thumbs/meta 藏起来并清掉历史索引，
            // 否则图库里会多出几十个哈希名碎片相册（用户实际踩过）
            runCatching { Gallery.tidy(ctx, root) }

            val included = arts.filter {
                when (it.kind) {
                    ArtKind.Artwork -> true
                    ArtKind.Derived -> includeDerived
                    ArtKind.Fill -> includeFill
                }
            }
            val excludedIds = arts.filter {
                (it.kind == ArtKind.Derived && !includeDerived) || (it.kind == ArtKind.Fill && !includeFill)
            }.mapTo(HashSet()) { it.id }

            val items = runCatching { plan(root, lib, included, tax, folders, fields, allArts = arts) }
                .getOrElse { return@withContext Result(error = it.message ?: "规划失败") }
            if (items.isEmpty()) return@withContext Result(error = "没有可导出的稿件")

            val manifestFile = File(root, MANIFEST)
            val old = readManifest(manifestFile)
            val next = LinkedHashMap<String, String>()
            // 新写出的文件要主动告诉媒体库，否则图库相册页根本看不到「相册/」——
            // .nomedia 挡住的是隐私目录，不是自动索引；新建的文件没人扫就不会出现。
            val fresh = ArrayList<String>()
            var written = 0
            var skipped = 0
            var failed = 0
            val failedPaths = ArrayList<String>()

            // 取消点：全量要复制几千个文件、几分钟，用户中途取消必须能在**下一个文件之前**停下
            var cancelled = false
            for ((idx, it2) in items.withIndex()) {
                if (shouldStop()) {
                    cancelled = true
                    break
                }
                runCatching {
                    if (place(root, it2, fresh)) written++ else skipped++
                    next[it2.key] = it2.rel
                }.onFailure {
                    failed++
                    failedPaths += it2.rel
                }
                // 每 20 个报一次，太频繁反而拖慢
                if (idx % 20 == 0 || idx == items.lastIndex) onProgress?.invoke(idx + 1, items.size)
            }
            if (cancelled) {
                // 取消时必须**把已经处理过的条目写进 manifest**，哪怕用户之后选了"删除"。
                //
                // 不写会长出"幽灵副本"：文件已经躺在「相册」里，但账本上没有它；
                // 用户随后改了标题 / 版本名再导出，新名字落一份新文件，而旧名字那份
                // 谁都认不出来（清理逻辑只删「账本上有、这次不需要」的文件），永远留在相册里。
                //
                // 写进去之后：下次导出 `prev != rel` → 正常删掉旧文件，不会产生副本。
                // 「删除」分支也一样 —— 文件被删了但账本还记着，下次 place 发现目标不存在会重导，自愈。
                runCatching {
                    val merged = LinkedHashMap(old)
                    merged.putAll(next)
                    writeManifest(manifestFile, merged)
                }
                return@withContext Result(
                    written = written,
                    skipped = skipped,
                    failed = failed,
                    cancelled = true,
                    writtenPaths = fresh.toList(),
                    failedPaths = failedPaths.toList(),
                )
            }

            // 未勾选导出的制品/制图：把它们在旧账本里的条目原样拷进 next，
            // 这样下面的清理段（keep = next.values）和末尾 writeManifest(next) 同时保住文件与账本。
            old.forEach { (k, v) ->
                if (k.substringBefore(':') in excludedIds) next[k] = v
            }

            // 全量镜像要清理：只删上一次记录过、这次不再需要的文件，
            // 绝不碰用户自己往相册目录里丢的东西。
            var removed = 0
            val keep = next.values.toSet()
            old.values.filter { it !in keep }.forEach { rel ->
                val f = File(root, rel)
                if (f.isFile && f.delete()) removed++
            }
            // 清掉空目录（世界观改名 / 稿件清空后留下的壳）
            File(root, DIR).walkBottomUp().forEach { d ->
                if (d.isDirectory && d.name != DIR && d.list()?.isEmpty() == true) d.delete()
            }

            // 写过的文件必扫；另外把「文件在、但媒体库还没有」的也补扫一次
            // —— 首次同步后用户可能没重启图库，或者文件是上次遗留的。
            val indexed = runCatching { Gallery.indexedPaths(ctx, File(root, DIR)) }
                .getOrDefault(emptySet())
            val missing = next.values
                .map { File(root, it).absolutePath }
                .filter { it !in indexed }
            val toScan = (fresh + missing).distinct()
            if (toScan.isNotEmpty()) {
                runCatching {
                    android.media.MediaScannerConnection.scanFile(ctx, toScan.toTypedArray(), null, null)
                }
            }

            writeManifest(manifestFile, next)
            Result(written, skipped, removed, failed, failedPaths = failedPaths.toList())
        }

    /**
     * 放一个条目：复制（目标长度相同就跳过）+ 设 mtime + 记入待扫描。
     *
     * **全量镜像和单导出共用这一个函数**。路径与命名一律由 [plan] 算好，
     * 这里不拼任何字符串 —— 两套算路径是最容易长出"两份副本"的地方。
     *
     * 返回 true 表示真的复制了；false 表示目标已经是最新。
     */
    private fun place(root: File, item: Item, fresh: MutableList<String>?): Boolean {
        val dst = File(root, item.rel)
        dst.parentFile?.mkdirs()
        val needCopy = !dst.isFile || dst.length() != item.src.length()
        if (needCopy) {
            item.src.copyTo(dst, overwrite = true)
            fresh?.add(dst.absolutePath)
        }
        // 时间：有精确时刻就用真实时刻，只有日期就取当天 12:00（躲时区）。
        // 同槽各版本按序号逐秒递增 → 图库按时间排序时它们紧挨着、顺序也固定。
        Dates.epochMillis(item.date)?.let { ms ->
            val target = ms + item.seq * 1000L
            if (needCopy || dst.lastModified() != target) dst.setLastModified(target)
        }
        return needCopy
    }

    /**
     * 单槽 / 单版本导出：和全量镜像**落在同一个「相册」目录、同一套命名**，只是范围更小。
     *
     * - 目标已是最新（长度一致）→ 计入 skipped，不重复产生副本；
     * - 改了内容 → 覆盖原来那份；
     * - 目标被手动删了 → 重新生成；
     * - 改了标题/版本名导致文件名变化 → 把旧文件删掉（确认没有别的条目还指着它）。
     *
     * 不跑全量的清理，也不调 `Gallery.tidy`：那两步是"整库重来"的语义，单导出不该动别人的文件。
     */
    suspend fun export(
        ctx: android.content.Context,
        root: File,
        lib: Library,
        arts: List<Art>,
        tax: Taxonomy,
        folders: FolderBook,
        fields: Set<NameField> = ALL_FIELDS,
        /** 非空 = 只导这一个版本（ver.id），null = 整个槽 */
        verId: String? = null,
        /** 全库稿件（用于解析制品原稿的角色名） */
        allArts: List<Art> = arts,
        /** 进度回调（IO 线程）：多选一次导几百个版本时要让用户看到在动 */
        onProgress: ((done: Int, total: Int) -> Unit)? = null,
    ): Result = withContext(Dispatchers.IO) {
        val items = plan(root, lib, arts, tax, folders, fields, allArts = allArts)
            .let { all -> if (verId == null) all else all.filter { it.key.endsWith(":$verId") } }
        if (items.isEmpty()) return@withContext Result(error = "没有可导出的版本")

        val manifestFile = File(root, MANIFEST)
        val manifest = LinkedHashMap(readManifest(manifestFile))
        val fresh = ArrayList<String>()
        var written = 0
        var skipped = 0
        var failed = 0
        val failedPaths = ArrayList<String>()

        items.forEachIndexed { idx, it2 ->
            runCatching {
                if (place(root, it2, fresh)) written++ else skipped++
                // 改了标题/版本名 → 文件名变了，旧的那份要删掉，否则会一直留着
                val prev = manifest[it2.key]
                if (prev != null && prev != it2.rel) {
                    val stillUsed = manifest.any { (k, v) -> k != it2.key && v == prev }
                    if (!stillUsed) File(root, prev).takeIf { it.isFile }?.delete()
                }
                manifest[it2.key] = it2.rel
            }.onFailure {
                failed++
                failedPaths += it2.rel
            }
            if (idx % 5 == 0 || idx == items.lastIndex) onProgress?.invoke(idx + 1, items.size)
        }

        runCatching {
            if (fresh.isNotEmpty()) {
                android.media.MediaScannerConnection.scanFile(
                    ctx,
                    fresh.toTypedArray(),
                    null,
                    null,
                )
            }
        }
        writeManifest(manifestFile, manifest)
        Result(written = written, skipped = skipped, failed = failed, failedPaths = failedPaths.toList())
    }

    /**
     * 只读校验相册镜像：按 [plan] 逐个核对目标文件是否存在、长度及内容 sha256 是否一致。
     * 不写任何文件、不动 manifest；取消通过 [shouldStop] 标志位提前返回已核对结果。
     */
    suspend fun verify(
        root: File,
        lib: Library,
        arts: List<Art>,
        tax: Taxonomy,
        folders: FolderBook,
        fields: Set<NameField> = ALL_FIELDS,
        shouldStop: () -> Boolean = { false },
    ): VerifyReport = withContext(Dispatchers.IO) {
        // 只校验普通稿 + mirror.json 里确实有记录的制品/制图，不把"按策略未导出"的制品报成缺失
        val manifestArtIds = readManifest(File(root, MANIFEST)).keys
            .mapTo(HashSet()) { it.substringBefore(':') }
        val targetArts = arts.filter { it.counted || it.id in manifestArtIds }
        val items = plan(root, lib, targetArts, tax, folders, fields, allArts = arts)
        var ok = 0
        val mismatch = ArrayList<String>()
        val missing = ArrayList<String>()
        var unchecked = 0
        for (item in items) {
            if (shouldStop()) break
            val dst = File(root, item.rel)
            if (!dst.isFile) {
                missing += item.rel
                continue
            }
            if (dst.length() != item.src.length()) {
                mismatch += item.rel
                continue
            }
            val expected = item.sha
            if (expected == null) {
                unchecked++
                continue
            }
            val actual = runCatching { sha256Hex(dst) }.getOrNull()
            if (actual != null && actual.equals(expected, ignoreCase = true)) {
                ok++
            } else {
                mismatch += item.rel
            }
        }
        VerifyReport(
            total = items.size,
            ok = ok,
            mismatch = mismatch,
            missing = missing,
            unchecked = unchecked,
        )
    }

    private fun sha256Hex(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(64 * 1024)
        file.inputStream().use { ins ->
            while (true) {
                val n = ins.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    fun lastCount(root: File): Int = readManifest(File(root, MANIFEST)).size

    /** `artId:verId` → 相册里的绝对文件。没导出过 / 记账了但文件被删了 → null */
    fun exportedFile(root: File, artId: String, verId: String): File? =
        readManifest(File(root, MANIFEST))["$artId:$verId"]
            ?.let { File(root, it) }
            ?.takeIf { it.isFile }

    /** 整槽的任意一个版本导出过没有（用来决定入口显不显示） */
    fun anyExported(root: File, artId: String, verIds: List<String>): Boolean =
        verIds.any { exportedFile(root, artId, it) != null }

    /**
     * 把指定的若干「相册里的文件路径」从 manifest 里摘掉。
     * 用于"取消后删掉已导出文件"：文件没了，账本也不该再记着它，否则 `lastCount` 虚高。
     */
    fun forget(root: File, paths: Collection<String>) {
        if (paths.isEmpty()) return
        val f = File(root, MANIFEST)
        val m = LinkedHashMap(readManifest(f))
        val drop = m.filterValues { it in paths }.keys.toList()
        if (drop.isEmpty()) return
        drop.forEach { m.remove(it) }
        writeManifest(f, m)
    }

    private fun readManifest(f: File): Map<String, String> {
        if (!f.isFile) return emptyMap()
        return runCatching {
            val o = JSONObject(f.readText())
            val out = LinkedHashMap<String, String>()
            o.keys().forEach { k -> o.optString(k).takeIf { it.isNotBlank() }?.let { out[k] = it } }
            out
        }.getOrDefault(emptyMap())
    }

    private fun writeManifest(f: File, map: Map<String, String>) {
        val o = JSONObject()
        map.forEach { (k, v) -> o.put(k, v) }
        writeJsonAtomic(f, o)
    }

    /** 去掉文件名里的非法字符，压掉多余空格 */
    private fun sanitize(s: String): String =
        s.replace(Regex("""[\\/:*?"<>|\u0000-\u001F]"""), "")
            .replace(Regex("""\s+"""), " ")
            .trim()
            .trim('.')
}
