package com.yejing.artvault

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun DirPickerDialog(
    start: File,
    onDismiss: () -> Unit,
    onPick: (File) -> Unit,
) {
    var cur by remember { mutableStateOf(start) }
    var dirs by remember { mutableStateOf(emptyList<File>()) }
    var creating by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("ArtVault") }
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    LaunchedEffect(cur) {
        dirs = withContext(Dispatchers.IO) {
            (cur.listFiles { f -> f.isDirectory && !f.name.startsWith(".") } ?: emptyArray())
                .sortedBy { it.name.lowercase() }
        }
    }

    val ok = VaultLocator.isVault(cur)

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = CardBg,
        shape = RoundedCornerShape(24.dp),
        title = { Text("选择库目录", style = T.titleSm, color = Paper) },
        text = {
            Column {
                Text(cur.absolutePath, style = T.micro, color = Muted, maxLines = 3)
                Spacer(Modifier.height(10.dp))
                LazyColumn(Modifier.heightIn(max = 300.dp)) {
                    val parent = cur.parentFile
                    if (parent != null) {
                        item {
                            Row(
                                Modifier.fillMaxWidth().clickable { cur = parent }
                                    .padding(vertical = 9.dp)
                            ) { Text("↑ 上一级", style = T.bodySm, color = Muted) }
                        }
                    }
                    items(dirs) { d ->
                        val isV = VaultLocator.isVault(d)
                        Row(
                            modifier = Modifier.fillMaxWidth()
                                .clickable { cur = d }
                                .padding(vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                d.name,
                                style = T.bodySm,
                                color = if (isV) Mint else Paper,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                            )
                            if (isV) {
                                Text(
                                    "库",
                                    style = T.micro,
                                    color = Mint,
                                    modifier = Modifier
                                        .background(MintC, RoundedCornerShape(6.dp))
                                        .padding(horizontal = 5.dp, vertical = 1.dp),
                                )
                            }
                        }
                    }
                    if (dirs.isEmpty()) {
                        item {
                            Text(
                                "没有子文件夹（若刚授权，退回再进一次）",
                                style = T.label,
                                color = Muted,
                                modifier = Modifier.padding(vertical = 12.dp),
                            )
                        }
                    }
                }
                if (!ok) {
                    Spacer(Modifier.height(Space.s))
                    Text(
                        "当前目录不是已有库；可点下方「新建稿仓」在此创建，或进入包含 meta/artworks 的目录。",
                        style = T.micro,
                        color = Muted,
                    )
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
                if (!ok) {
                    TextButton(onClick = {
                        newName = "ArtVault"
                        creating = true
                    }) {
                        Text("新建稿仓", style = T.label, color = Accent)
                    }
                }
                TextButton(onClick = { onPick(cur) }, enabled = ok) {
                    Text("使用此目录", style = T.label, color = if (ok) Mint else Muted)
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消", style = T.label, color = Muted)
            }
        },
    )

    if (creating) {
        AlertDialog(
            onDismissRequest = { creating = false },
            containerColor = CardBg,
            shape = RoundedCornerShape(24.dp),
            title = { Text("在此新建稿仓", style = T.titleSm, color = Paper) },
            text = {
                Column {
                    Text(
                        "将在 ${cur.absolutePath} 下创建目录并初始化稿仓结构。",
                        style = T.micro,
                        color = Muted,
                    )
                    Spacer(Modifier.height(10.dp))
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(CHigh)
                            .padding(horizontal = 12.dp, vertical = 11.dp),
                    ) {
                        if (newName.isBlank()) {
                            Text("文件夹名（如 ArtVault）", style = T.bodySm, color = Muted)
                        }
                        BasicTextField(
                            value = newName,
                            onValueChange = { newName = it },
                            singleLine = true,
                            textStyle = T.bodySm.copy(color = Paper),
                            cursorBrush = SolidColor(Accent),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = newName.trim().isNotEmpty(),
                    onClick = {
                        val safe = newName.trim().replace(Regex("[/\\\\:*?\"<>|]"), "")
                        if (safe.isEmpty()) {
                            toast(ctx, "文件夹名不合法")
                            return@TextButton
                        }
                        val target = File(cur, safe)
                        creating = false
                        scope.launch {
                            val okInit = withContext(Dispatchers.IO) { VaultLocator.initVault(target) }
                            if (okInit) {
                                toast(ctx, "已创建稿仓：${target.name}")
                                onPick(target)
                            } else {
                                toast(ctx, "创建失败，请检查存储权限与目录可写性")
                            }
                        }
                    },
                ) {
                    Text("创建并使用", style = T.label, color = Accent)
                }
            },
            dismissButton = {
                TextButton(onClick = { creating = false }) {
                    Text("取消", style = T.label, color = Muted)
                }
            },
        )
    }
}
