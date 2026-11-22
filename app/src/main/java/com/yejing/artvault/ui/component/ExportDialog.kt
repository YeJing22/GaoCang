package com.yejing.artvault.ui.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.yejing.artvault.Space
import com.yejing.artvault.Accent
import com.yejing.artvault.Muted
import com.yejing.artvault.OnMid
import com.yejing.artvault.Paper
import com.yejing.artvault.CardBg
import com.yejing.artvault.T
import com.yejing.artvault.Warn

/**
 * 导出前的二级确认。
 *
 * 为什么需要：导出会在 `相册/` 下生成真实文件，几百件的批次要复制几百个原图（几分钟、几 GB）。
 * 之前点一下就走，误触的代价是磁盘和等待时间，而且中途没有明显的"正在做"信号。
 * 加一层确认，顺便把「落在哪、会不会重复、动不动原图」讲清楚 —— 这几件事用户问过不止一次。
 */
@Composable
fun ExportConfirmDialog(
    title: String,
    /** 补充说明（可选）：比如会跳过多少个已是最新的 */
    detail: String? = null,
    note: String? = null,
    extra: (@Composable () -> Unit)? = null,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onCancel,
        containerColor = CardBg,
        shape = RoundedCornerShape(24.dp),
        title = { Text(title, style = T.titleSm, color = Paper) },
        text = {
            Column {
                Text(
                    detail ?: "会按归档分类复制到「相册」目录下，已经在相册里且内容没变的会自动跳过。",
                    style = T.micro,
                    color = OnMid,
                )
                if (extra != null) {
                    Spacer(Modifier.height(12.dp))
                    extra()
                }
                if (note != null) {
                    Spacer(Modifier.height(10.dp))
                    Text(note, style = T.micro, color = Muted)
                }
                Spacer(Modifier.height(10.dp))
                Text("不动库数据，原图也不动。", style = T.micro, color = Muted)
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("导出", style = T.body, color = Accent)
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) {
                Text("取消", style = T.body, color = Muted)
            }
        },
    )
}

/**
 * 全量同步被取消后的收尾：已经复制出去的文件是留还是删。
 *
 * 两个选择都成立 —— 留着的话下次同步会跳过（内容没变）；删掉则回到干净状态。
 * 所以必须问，不能替用户决定。
 */
@Composable
fun MirrorCancelDialog(
    count: Int,
    onKeep: () -> Unit,
    onDelete: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onKeep,
        containerColor = CardBg,
        shape = RoundedCornerShape(24.dp),
        title = { Text("同步已取消", style = T.titleSm, color = Paper) },
        text = {
            Column {
                Text("已经导出 $count 个文件。", style = T.micro, color = OnMid)
                Spacer(Modifier.height(10.dp))
                Text("保留：下次同步会跳过它们（内容没变的不会重导）。", style = T.micro, color = Muted)
                Spacer(Modifier.height(Space.xxs))
                Text("删除：把它们从「相册」里删掉，下次重新导出。", style = T.micro, color = Muted)
            }
        },
        confirmButton = {
            TextButton(onClick = onDelete) {
                Text("删除这些文件", style = T.body, color = Warn)
            }
        },
        dismissButton = {
            TextButton(onClick = onKeep) {
                Text("保留", style = T.body, color = Muted)
            }
        },
    )
}
