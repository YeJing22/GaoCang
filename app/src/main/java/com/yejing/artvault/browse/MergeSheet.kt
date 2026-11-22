package com.yejing.artvault.browse

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.yejing.artvault.Space
import com.yejing.artvault.Accent
import com.yejing.artvault.Amber
import com.yejing.artvault.Art
import com.yejing.artvault.CHigh
import com.yejing.artvault.CardBg
import com.yejing.artvault.Muted
import com.yejing.artvault.Library
import com.yejing.artvault.Paper
import com.yejing.artvault.Scrim
import com.yejing.artvault.T
import com.yejing.artvault.ui.component.PrimaryButton
import com.yejing.artvault.ui.component.SectionLabel
import com.yejing.artvault.ui.component.SheetGrip

/**
 * 合并确认。合并会减少条目数、并把其余槽移进回收站，
 * 所以这里先把「会发生什么」摆出来：主条目是谁、合并后几个版本、原本的价格是多少。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MergeSheet(
    targets: List<Art>,
    lib: Library,
    onConfirm: (primaryId: String) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var primaryId by remember(targets) { mutableStateOf(targets.firstOrNull()?.id ?: "") }

    val totalVersions = targets.sumOf { it.versions.size }
    val prices = targets.mapNotNull { it.priceTotal }
    val priceSum = prices.sum()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = CardBg,
        scrimColor = Scrim,
        dragHandle = { SheetGrip() },
    ) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 12.dp)) {
            Column(Modifier.padding(horizontal = 20.dp)) {
                Text("合并到一个槽", style = T.title, color = Paper)
                Spacer(Modifier.height(Space.xxs))
                Text(
                    "${targets.size} 个槽 → 1 个槽 · 合并后 $totalVersions 个版本",
                    style = T.label,
                    color = Muted,
                )
            }

            SectionLabel("主条目", modifier = Modifier.padding(horizontal = 20.dp))
            Text(
                "标题 / 世界观 / 角色 / 标签 / 日期都取它的",
                style = T.micro,
                color = Muted,
                modifier = Modifier.padding(horizontal = 20.dp)
            )
            Spacer(Modifier.height(9.dp))
            LazyRow(
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                itemsIndexed(targets) { _, a ->
                    val on = a.id == primaryId
                    val v = a.primary
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.clickable { primaryId = a.id },
                    ) {
                        Box(
                            Modifier
                                .size(64.dp)
                                .clip(RoundedCornerShape(13.dp))
                                .background(CHigh)
                                .border(
                                    width = if (on) 2.dp else 0.8.dp,
                                    color = if (on) Accent else Color.White.copy(alpha = 0.06f),
                                    shape = RoundedCornerShape(13.dp),
                                ),
                        ) {
                            if (v != null) {
                                AsyncImage(
                                    model = lib.thumb(v),
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }
                        }
                        Text(
                            if (on) "主" else "${a.versions.size} 版",
                            style = T.micro,
                            color = if (on) Accent else Muted,
                            maxLines = 1,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.widthIn(max = 68.dp).padding(top = 5.dp),
                        )
                    }
                }
            }

            SectionLabel("会发生什么", modifier = Modifier.padding(horizontal = 20.dp))
            Column(Modifier.padding(horizontal = 20.dp)) {
                Note(
                    "· 图片不搬动，全部并成目标槽的版本；同名版本自动加序号（透明底 / 透明底 2）",
                )
                Spacer(Modifier.height(7.dp))
                Note(
                    if (prices.isNotEmpty()) "· 价格取合计 ¥$priceSum，合并后可在详情里改"
                    else "· 这些槽都没记价格，合并后仍是空",
                    accent = true,
                )
                if (prices.isNotEmpty()) {
                    Spacer(Modifier.height(Space.xxs))
                    Text(
                        "原本：" + targets.mapNotNull { a ->
                            a.priceTotal?.let { "¥$it" }
                        }.joinToString(" + "),
                        style = T.micro,
                        color = Amber,
                        modifier = Modifier.padding(start = 9.dp),
                    )
                }
                Spacer(Modifier.height(7.dp))
                Note("· 其余 ${targets.size - 1} 个槽移进 meta/artworks/.trash/，可以手动恢复")
            }

            Spacer(Modifier.height(Space.l))
            Box(Modifier.padding(horizontal = 20.dp)) {
                PrimaryButton(
                    text = "合并 ${targets.size} 个槽",
                    enabled = primaryId.isNotBlank() && targets.size >= 2,
                    onClick = { onConfirm(primaryId) },
                )
            }
        }
    }
}

@Composable
private fun Note(text: String, accent: Boolean = false) {
    Row {
        Text(
            text,
            style = T.label,
            color = if (accent) Amber else Muted,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
