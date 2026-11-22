package com.yejing.artvault

import android.content.Context
import android.os.Build
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 纯本地崩溃日志记录：App 无联网权限，捕获未处理异常写入私有目录，方便用户在「我的 → 数据」查看或复制提 Issue。
 */
object CrashLog {
    private val installed = AtomicBoolean(false)
    private fun file(ctx: Context): File = File(ctx.applicationContext.filesDir, "crash_last.log")

    fun install(ctx: Context) {
        if (!installed.compareAndSet(false, true)) return
        val appCtx = ctx.applicationContext
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                val ver = runCatching {
                    appCtx.packageManager.getPackageInfo(appCtx.packageName, 0).versionName
                }.getOrNull() ?: "unknown"
                val ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                val body = buildString {
                    appendLine("Time: $ts")
                    appendLine("App: ${appCtx.packageName} v$ver")
                    appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL} (API ${Build.VERSION.SDK_INT})")
                    appendLine("Thread: ${thread.name}")
                    appendLine()
                    append(throwable.stackTraceToString())
                }
                file(appCtx).writeText(body, Charsets.UTF_8)
            }
            prev?.uncaughtException(thread, throwable)
        }
    }

    fun read(ctx: Context): String? = runCatching {
        file(ctx).takeIf { it.isFile }?.readText(Charsets.UTF_8)?.takeIf { it.isNotBlank() }
    }.getOrNull()

    fun clear(ctx: Context) {
        runCatching { file(ctx).delete() }
    }
}

/**
 * 账单明细 CSV 导出（UTF-8 BOM + RFC 4180 转义，Excel 直接打开不乱码，走 .tmp 原子落盘）。
 */
object CsvExport {
    private val tsFmt = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")

    fun exportLedger(
        root: File,
        arts: List<Art>,
        tax: Taxonomy,
        names: VaultNames,
        folders: FolderBook,
    ): File {
        val out = File(root, "稿仓账单_${LocalDateTime.now().format(tsFmt)}.csv")
        val tmp = File(root, "${out.name}.tmp")
        val sorted = arts.sortedByDescending { Dates.sortKey(it.receivedAt) }
        val sb = StringBuilder(sorted.size * 96 + 128)
        sb.append('\uFEFF')
        sb.appendLine("收稿日期,标题,类型,价格(元),画师,自制,平台,世界观,角色,标签,文件夹,版本数,备注")
        for (a in sorted) {
            val kindStr = when (a.kind) {
                ArtKind.Artwork -> "普通稿"
                ArtKind.Derived -> "制品"
                ArtKind.Fill -> "制图"
            }
            val priceStr = a.priceTotal?.toString() ?: ""
            val artistStr = when {
                a.selfMade -> "自制"
                a.artistId == null -> ""
                else -> names.artist(a.artistId)
            }
            val worldStr = a.worldId?.let { tax.worldName(it) }.orEmpty()
            val rolesStr = a.roleIds.mapNotNull { tax.roleName(it) }.joinToString(" / ")
            val tagsStr = names.tagsOf(a.tagIds).joinToString(" / ")
            val folderStr = folders.byId(a.folderId)?.name ?: "未分类"
            val cols = listOf(
                a.receivedAt.orEmpty(),
                a.title,
                kindStr,
                priceStr,
                artistStr,
                if (a.selfMade) "是" else "否",
                a.platform,
                worldStr,
                rolesStr,
                tagsStr,
                folderStr,
                a.versions.size.toString(),
                a.note,
            )
            sb.appendLine(cols.joinToString(",") { escape(it) })
        }
        tmp.writeText(sb.toString(), Charsets.UTF_8)
        if (!tmp.renameTo(out)) {
            out.writeText(sb.toString(), Charsets.UTF_8)
            tmp.delete()
        }
        return out
    }

    internal fun escape(field: String): String {
        val needQuote = field.any { it == ',' || it == '"' || it == '\n' || it == '\r' }
        return if (!needQuote) field else "\"" + field.replace("\"", "\"\"") + "\""
    }
}
