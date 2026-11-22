package com.yejing.artvault

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

// 一次性警示色：项目色板没有单独的 Danger token，仅用于彻底删除隔离区的确认按钮
private val PurgeDanger = Color(0xFFE05A4F)

private fun formatPurgeBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024L * 1024L -> String.format(Locale.US, "%.1f GB", bytes / (1024.0 * 1024.0 * 1024.0))
    bytes >= 1024L * 1024L -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
    bytes >= 1024L -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
    else -> "$bytes B"
}

/** 「清理孤儿图片」对话框：打开时扫描，确认后原子移动到 .trash 隔离区 */
@Composable
fun PurgeIsolateDialog(
    root: File,
    lib: Library,
    onDismiss: () -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var report by remember { mutableStateOf<PurgeReport?>(null) }
    var running by remember { mutableStateOf(false) }
    var done by remember { mutableIntStateOf(0) }
    var total by remember { mutableIntStateOf(0) }

    LaunchedEffect(root) {
        report = Purge.scan(root, lib)
    }

    val r = report
    AlertDialog(
        onDismissRequest = { if (!running) onDismiss() },
        containerColor = CardBg,
        shape = RoundedCornerShape(24.dp),
        title = { Text("清理孤儿图片", style = T.titleSm, color = Paper) },
        text = {
            when {
                running -> Text("已处理 $done / $total", style = T.bodySm, color = OnMid)
                r == null -> Text("正在统计…", style = T.bodySm, color = Muted)
                r.orphanTotalCount == 0 -> Text(
                    "没有可清理的文件。\n\n" +
                        "库里被引用：${r.referencedCount} 张\n" +
                        "隔离区里已有：${r.isolatedBlobs.size} 个原图（${formatPurgeBytes(r.isolatedBytes)}）",
                    style = T.bodySm,
                    color = OnMid,
                )
                else -> Text(
                    "可清理：${r.orphanBlobs.size} 个原图（${formatPurgeBytes(r.orphanBytes)}）" +
                        "+ ${r.orphanThumbs.size} 个缩略图（${formatPurgeBytes(r.orphanThumbBytes)}）\n" +
                        "库里被引用：${r.referencedCount} 张\n" +
                        "隔离区里已有：${r.isolatedBlobs.size} 个（${formatPurgeBytes(r.isolatedBytes)}）\n\n" +
                        "移到隔离区后，详情页不会再有可见变化；文件放在 blobs/.trash/，需要时可手动移回。",
                    style = T.bodySm,
                    color = OnMid,
                )
            }
        },
        confirmButton = {
            if (!running && r != null && r.orphanTotalCount > 0) {
                val btnLabel = if (r.orphanBlobs.isNotEmpty()) {
                    "隔离这 ${r.orphanBlobs.size} 个"
                } else {
                    "隔离这 ${r.orphanTotalCount} 个"
                }
                TextButton(onClick = {
                    running = true
                    done = 0
                    total = r.orphanTotalCount
                    scope.launch {
                        val res = Purge.isolate(root, r) { d, t ->
                            scope.launch(Dispatchers.Main) {
                                done = d
                                total = t
                            }
                        }
                        running = false
                        val msg = if (res.failed == 0) {
                            "已隔离 ${r.orphanBlobs.size} 个原图，可清理 ${formatPurgeBytes(r.orphanBytes)}"
                        } else {
                            "已隔离 ${res.succeeded} 个，失败 ${res.failed} 个"
                        }
                        toast(ctx, msg)
                        onDismiss()
                    }
                }) { Text(btnLabel, style = T.body, color = Accent) }
            }
        },
        dismissButton = {
            if (!running) {
                TextButton(onClick = onDismiss) {
                    Text(if (r != null && r.orphanTotalCount == 0) "关闭" else "取消", style = T.body, color = Muted)
                }
            }
        },
    )
}

/** 「清空隔离区」对话框：仅彻底删除 blobs/.trash/ 与 thumbs/.trash/ 中的文件 */
@Composable
fun PurgeDeleteDialog(
    root: File,
    lib: Library,
    onDismiss: () -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var report by remember { mutableStateOf<PurgeReport?>(null) }
    var running by remember { mutableStateOf(false) }
    var done by remember { mutableIntStateOf(0) }
    var total by remember { mutableIntStateOf(0) }

    LaunchedEffect(root) {
        report = Purge.scan(root, lib)
    }

    val r = report
    AlertDialog(
        onDismissRequest = { if (!running) onDismiss() },
        containerColor = CardBg,
        shape = RoundedCornerShape(24.dp),
        title = { Text("清空隔离区", style = T.titleSm, color = Paper) },
        text = {
            when {
                running -> Text("正在删除 $done / $total", style = T.bodySm, color = OnMid)
                r == null -> Text("正在统计…", style = T.bodySm, color = Muted)
                r.isolatedTotalCount == 0 -> Text("隔离区是空的", style = T.bodySm, color = OnMid)
                else -> Text(
                    "隔离区现有：${r.isolatedBlobs.size} 个原图（${formatPurgeBytes(r.isolatedBytes)}）" +
                        "+ ${r.isolatedThumbs.size} 个缩略图（${formatPurgeBytes(r.isolatedThumbBytes)}）\n\n" +
                        "彻底删除后无法恢复。若不确定，先关掉这个对话框，用文件管理器确认。",
                    style = T.bodySm,
                    color = OnMid,
                )
            }
        },
        confirmButton = {
            if (!running && r != null && r.isolatedTotalCount > 0) {
                TextButton(onClick = {
                    running = true
                    done = 0
                    total = r.isolatedTotalCount
                    scope.launch {
                        val res = Purge.emptyTrash(root, r) { d, t ->
                            scope.launch(Dispatchers.Main) {
                                done = d
                                total = t
                            }
                        }
                        running = false
                        val msg = if (res.failed == 0) {
                            "已彻底删除 ${res.succeeded} 个文件"
                        } else {
                            "已删除 ${res.succeeded} 个，失败 ${res.failed} 个"
                        }
                        toast(ctx, msg)
                        onDismiss()
                    }
                }) { Text("彻底删除", style = T.body, color = PurgeDanger) }
            }
        },
        dismissButton = {
            if (!running) {
                TextButton(onClick = onDismiss) {
                    Text(if (r != null && r.isolatedTotalCount == 0) "关闭" else "取消", style = T.body, color = Muted)
                }
            }
        },
    )
}
