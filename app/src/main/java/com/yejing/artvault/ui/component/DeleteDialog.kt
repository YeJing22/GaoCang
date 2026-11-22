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
import com.yejing.artvault.Muted
import com.yejing.artvault.OnMid
import com.yejing.artvault.Paper
import com.yejing.artvault.CardBg
import com.yejing.artvault.T
import com.yejing.artvault.Warn

/**
 * 删除确认。
 *
 * 删除只会把 JSON 移进 `meta/artworks/.trash/`，图片文件不动（blob 是内容寻址、
 * 可能被别的槽共享），所以文案说的是「记录」，别让用户以为图被抹了。
 */
@Composable
fun DeleteConfirmDialog(
    count: Int,
    sample: List<String>,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onCancel,
        containerColor = CardBg,
        shape = RoundedCornerShape(24.dp),
        title = { Text("删除 $count 件稿子？", style = T.titleSm, color = Paper) },
        text = {
            Column {
                Text(
                    "只会把稿件记录移进 meta/artworks/.trash/，图片文件保留 —— 需要时可以手动找回。",
                    style = T.micro,
                    color = Muted,
                )
                if (sample.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        sample.joinToString("、"),
                        style = T.label,
                        color = OnMid,
                        maxLines = 3,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("删除", style = T.body, color = Warn)
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) {
                Text("取消", style = T.body, color = Muted)
            }
        },
    )
}
