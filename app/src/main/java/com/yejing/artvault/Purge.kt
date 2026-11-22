package com.yejing.artvault

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 孤儿图片扫描报告。
 * - `orphan*`：`blobs/`、`thumbs/` 里不再被任何在库稿件或回收站条目引用的文件
 * - `isolated*`：已移入 `blobs/.trash/`、`thumbs/.trash/` 的待彻底删除文件
 */
data class PurgeReport(
    val orphanBlobs: List<File>,
    val orphanBytes: Long,
    val orphanThumbs: List<File>,
    val orphanThumbBytes: Long,
    val isolatedBlobs: List<File>,
    val isolatedBytes: Long,
    val isolatedThumbs: List<File>,
    val isolatedThumbBytes: Long,
    val referencedCount: Int,
) {
    val orphanTotalCount: Int get() = orphanBlobs.size + orphanThumbs.size
    val isolatedTotalCount: Int get() = isolatedBlobs.size + isolatedThumbs.size
}

data class PurgeActionResult(
    val succeeded: Int,
    val failed: Int,
)

/**
 * 孤儿图片两步清理：
 * 1. [isolate]：仅使用 `renameTo` 原子移入 `blobs/.trash/` 与 `thumbs/.trash/`（可手动移回）
 * 2. [emptyTrash]：仅删除两个 `.trash/` 目录下的文件，绝不直接对 `blobs/` 或 `thumbs/` 执行 `delete()`
 */
object Purge {

    private const val TRASH_DIR = ".trash"
    private const val STAGING_DIR = ".staging"
    private const val NOMEDIA = ".nomedia"

    /**
     * 从磁盘重读在库稿件与回收站条目（不信任内存快照），计算孤儿原图/缩略图与隔离区文件。
     *
     * 引用集合必须包含：
     * 1. 在库稿件（`lib.load()`）的每个版本 `v.blob`
     * 2. 回收站整槽（`TrashEntry.Slot`）的每个版本 `v.blob`
     * 3. 回收站单版本（`TrashEntry.Version`）的 `v.ver.blob`
     * 隔离区文件不参与引用集合。
     */
    suspend fun scan(root: File, lib: Library): PurgeReport = withContext(Dispatchers.IO) {
        val referenced = HashSet<String>()

        lib.load().forEach { art ->
            art.versions.forEach { v ->
                if (v.blob.isNotBlank()) referenced += v.blob
            }
        }

        Trash(root).list().forEach { entry ->
            when (entry) {
                is TrashEntry.Slot -> entry.t.art?.versions?.forEach { v ->
                    if (v.blob.isNotBlank()) referenced += v.blob
                }
                is TrashEntry.Version -> {
                    val b = entry.v.ver.blob
                    if (b.isNotBlank()) referenced += b
                }
            }
        }

        val orphanBlobs = ArrayList<File>()
        var orphanBytes = 0L
        val blobsDir = File(root, "blobs")
        if (blobsDir.isDirectory) {
            blobsDir.listFiles()?.forEach { child ->
                val name = child.name
                if (name == STAGING_DIR || name == TRASH_DIR || name == NOMEDIA) return@forEach
                if (child.isDirectory) {
                    child.listFiles()?.forEach { f ->
                        if (f.isFile && f.name != NOMEDIA) {
                            val sha = f.name.substringBeforeLast('.')
                            if (sha !in referenced) {
                                orphanBlobs += f
                                orphanBytes += f.length()
                            }
                        }
                    }
                } else if (child.isFile) {
                    val sha = child.name.substringBeforeLast('.')
                    if (sha !in referenced) {
                        orphanBlobs += child
                        orphanBytes += child.length()
                    }
                }
            }
        }

        val orphanThumbs = ArrayList<File>()
        var orphanThumbBytes = 0L
        val thumbsDir = File(root, "thumbs")
        if (thumbsDir.isDirectory) {
            thumbsDir.listFiles()?.forEach { f ->
                if (f.isFile && f.name != NOMEDIA) {
                    val sha = f.name.substringBefore(".w640.")
                    if (sha !in referenced) {
                        orphanThumbs += f
                        orphanThumbBytes += f.length()
                    }
                }
            }
        }

        val isolatedBlobs = ArrayList<File>()
        var isolatedBytes = 0L
        collectFiles(File(blobsDir, TRASH_DIR), isolatedBlobs)
        isolatedBlobs.forEach { isolatedBytes += it.length() }

        val isolatedThumbs = ArrayList<File>()
        var isolatedThumbBytes = 0L
        collectFiles(File(thumbsDir, TRASH_DIR), isolatedThumbs)
        isolatedThumbs.forEach { isolatedThumbBytes += it.length() }

        PurgeReport(
            orphanBlobs = orphanBlobs,
            orphanBytes = orphanBytes,
            orphanThumbs = orphanThumbs,
            orphanThumbBytes = orphanThumbBytes,
            isolatedBlobs = isolatedBlobs,
            isolatedBytes = isolatedBytes,
            isolatedThumbs = isolatedThumbs,
            isolatedThumbBytes = isolatedThumbBytes,
            referencedCount = referenced.size,
        )
    }

    private fun collectFiles(dir: File, out: MutableList<File>) {
        if (!dir.isDirectory) return
        dir.listFiles()?.forEach { f ->
            if (f.isDirectory) collectFiles(f, out)
            else if (f.isFile && f.name != NOMEDIA) out += f
        }
    }

    /**
     * 将孤儿原图/缩略图原子移动到 `blobs/.trash/<sha前2位>/` 与 `thumbs/.trash/`。
     * 只使用 `renameTo`，不执行 copy+delete；单个失败不中断整批。
     */
    suspend fun isolate(
        root: File,
        report: PurgeReport,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): PurgeActionResult = withContext(Dispatchers.IO) {
        val blobsTrash = File(root, "blobs/$TRASH_DIR")
        val thumbsTrash = File(root, "thumbs/$TRASH_DIR")
        val total = report.orphanTotalCount
        var done = 0
        var ok = 0
        var failed = 0

        report.orphanBlobs.forEach { f ->
            val bucket = f.parentFile?.name?.takeIf { it != "blobs" && it.isNotBlank() }
            val dstDir = if (bucket != null) File(blobsTrash, bucket) else blobsTrash
            val dst = File(dstDir, f.name)
            val moved = if (dst.exists()) {
                true
            } else {
                dstDir.mkdirs()
                f.renameTo(dst)
            }
            if (moved) ok++ else failed++
            done++
            onProgress(done, total)
        }

        report.orphanThumbs.forEach { f ->
            val dst = File(thumbsTrash, f.name)
            val moved = if (dst.exists()) {
                true
            } else {
                thumbsTrash.mkdirs()
                f.renameTo(dst)
            }
            if (moved) ok++ else failed++
            done++
            onProgress(done, total)
        }

        PurgeActionResult(succeeded = ok, failed = failed)
    }

    /**
     * 彻底删除隔离区里的文件。
     * 红线：只允许删除路径严格位于 `blobs/.trash/` 或 `thumbs/.trash/` 内的文件。
     */
    suspend fun emptyTrash(
        root: File,
        report: PurgeReport,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): PurgeActionResult = withContext(Dispatchers.IO) {
        val blobsTrash = File(root, "blobs/$TRASH_DIR").canonicalFile
        val thumbsTrash = File(root, "thumbs/$TRASH_DIR").canonicalFile
        val targets = report.isolatedBlobs + report.isolatedThumbs
        val total = targets.size
        var done = 0
        var ok = 0
        var failed = 0

        targets.forEach { f ->
            val canonical = runCatching { f.canonicalFile }.getOrNull()
            val insideTrash = canonical != null && (
                canonical.path.startsWith(blobsTrash.path + File.separator) ||
                    canonical.path.startsWith(thumbsTrash.path + File.separator)
                )
            val deleted = insideTrash && (!f.exists() || f.delete())
            if (deleted) ok++ else failed++
            done++
            onProgress(done, total)
        }

        pruneEmptySubdirs(blobsTrash)
        pruneEmptySubdirs(thumbsTrash)

        PurgeActionResult(succeeded = ok, failed = failed)
    }

    /** 清理 `.trash/` 下删空的两级子目录，保留 `.trash/` 自身。 */
    private fun pruneEmptySubdirs(trashRoot: File) {
        if (!trashRoot.isDirectory) return
        trashRoot.listFiles()?.forEach { child ->
            if (child.isDirectory) {
                pruneEmptySubdirs(child)
                if (child.listFiles().isNullOrEmpty()) child.delete()
            }
        }
    }
}
