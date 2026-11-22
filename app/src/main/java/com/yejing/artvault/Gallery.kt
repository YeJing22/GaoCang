package com.yejing.artvault

import android.content.Context
import android.media.MediaScannerConnection
import android.provider.MediaStore
import java.io.File

/**
 * 让系统图库只看得到「相册/」，看不到 blobs / thumbs / meta。
 *
 * 图库唯一认的隐藏方式是 `.nomedia`：目录里有它，整棵子树都不进 MediaStore。
 * 但它只管「以后」的扫描——已经被索引的条目不会自己消失，得把那批路径重新扫一遍：
 * 扫描器发现文件落在 no-media 目录里，会把索引记录删掉（只删记录，原文件不动）。
 *
 * 注意：`.nomedia` 绝不能写到库根，否则连「相册/」一起被藏掉。
 */
object Gallery {

    /** 需要藏起来的库内目录。meta 一起藏，将来 meta/avatars 放角色头像也不会漏进图库 */
    private val HIDDEN = listOf("blobs", "thumbs", "meta")

    /** 补齐 .nomedia，返回新建了几个 */
    fun ensureHidden(root: File): Int {
        var n = 0
        HIDDEN.forEach { name ->
            val dir = File(root, name)
            if (!dir.isDirectory) return@forEach
            val mark = File(dir, ".nomedia")
            if (!mark.exists() && runCatching { mark.createNewFile() }.getOrDefault(false)) n++
        }
        return n
    }

    /**
     * 清掉 MediaStore 里指向这些目录的图片记录。
     * 返回处理条数（0 表示本来就没被索引，或者没权限读 MediaStore）。
     *
     * 用逐个 scanFile 而不是 ContentResolver.delete：delete 会把真实文件一起删掉，
     * 那是库里的原图，绝对不能碰。
     */
    fun purgeIndexed(ctx: Context, root: File): Int {
        val paths = runCatching {
            val out = ArrayList<String>()
            val cols = arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DATA)
            HIDDEN.forEach { name ->
                val prefix = File(root, name).absolutePath + "/"
                ctx.contentResolver.query(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    cols,
                    "${MediaStore.Images.Media.DATA} LIKE ?",
                    arrayOf("$prefix%"),
                    null,
                )?.use { c ->
                    val idx = c.getColumnIndex(MediaStore.Images.Media.DATA)
                    while (c.moveToNext() && idx >= 0) {
                        c.getString(idx)?.takeIf { it.isNotBlank() }?.let { out.add(it) }
                    }
                }
            }
            out
        }.getOrDefault(emptyList())

        if (paths.isEmpty()) return 0
        runCatching {
            MediaScannerConnection.scanFile(ctx, paths.toTypedArray(), null, null)
        }
        return paths.size
    }

    /** 某个目录下「已经被媒体库收录」的文件绝对路径，用来避免每次都重扫 */
    fun indexedPaths(ctx: Context, dir: File): Set<String> = runCatching {
        val out = HashSet<String>()
        val cols = arrayOf(MediaStore.Images.Media.DATA)
        ctx.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            cols,
            "${MediaStore.Images.Media.DATA} LIKE ?",
            arrayOf(dir.absolutePath + "/%"),
            null,
        )?.use { c ->
            val idx = c.getColumnIndex(MediaStore.Images.Media.DATA)
            while (c.moveToNext() && idx >= 0) {
                c.getString(idx)?.takeIf { it.isNotBlank() }?.let { out.add(it) }
            }
        }
        out
    }.getOrDefault(emptySet())

    /** 建库/同步时统一调用：先补 .nomedia，再把历史索引清一遍 */
    fun tidy(ctx: Context, root: File): Int {
        ensureHidden(root)
        return purgeIndexed(ctx, root)
    }
}
