package com.yejing.artvault

import android.app.Activity
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import java.io.File

/**
 * 在系统原生图库中打开「已导出到相册」的图片。
 *
 * 只发裸 Intent + `FLAG_GRANT_READ_URI_PERMISSION`，不指定包名、不加包可见性声明、
 * 不做首选应用探测（见 AGENTS.md「刻意不做的事」唯一例外）。
 */
object GalleryOpen {

    /** 查 MediaStore 里这条文件对应的 content://。查不到（还没被索引）→ null */
    @Suppress("DEPRECATION")
    fun uriOf(ctx: Context, f: File): Uri? {
        val path = f.absolutePath
        val proj = arrayOf(MediaStore.Images.Media._ID)
        val sel = "${MediaStore.Images.Media.DATA} = ?"
        return runCatching {
            ctx.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                proj,
                sel,
                arrayOf(path),
                null,
            )?.use { c ->
                if (c.moveToFirst()) {
                    val id = c.getLong(0)
                    ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
                } else {
                    null
                }
            }
        }.getOrNull()
    }

    /**
     * 发一个裸 ACTION_VIEW 给系统图库。
     * 返回 false = 没有任何 App 能接（调用方 toast 提示）。
     */
    fun open(ctx: Context, uri: Uri): Boolean {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "image/*")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if (ctx !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return runCatching {
            ctx.startActivity(intent)
            true
        }.getOrDefault(false)
    }
}
