package com.yejing.artvault

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yejing.artvault.ui.component.EmptyState
import com.yejing.artvault.ui.component.GI
import com.yejing.artvault.ui.component.RoundIconButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 操作历史（撤销 / 重做）。
 *
 * 为什么是个整页而不是弹窗：一条记录要同时说清「做了什么、什么时候、还能不能撤」，
 * 弹窗里三列信息会挤成一团；而且它和回收站是同一类东西（都是"能找回"），
 * 用同一个整页形态，用户学一次就够。
 *
 * **只支持撤销最近一步**（标准栈语义）：跳着撤会让重做失去意义，也容易把两次操作的
 * 中间态当成可回退的状态。所以列表只标注状态，操作靠顶部两个按钮。
 */
@Composable
fun HistoryScreen(
    root: File,
    onClose: () -> Unit,
    onChanged: () -> Unit,
    onUndo: () -> Unit = onChanged,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var list by remember { mutableStateOf<List<OpRecord>>(emptyList()) }
    var refresh by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }

    LaunchedEffect(root, refresh) {
        list = withContext(Dispatchers.IO) { OperationJournal.history(root) }
    }

    BackHandler { onClose() }

    val latestDone = list.firstOrNull { it.done }
    val redoTarget = list.firstOrNull { !it.done && (latestDone == null || it.at > latestDone.at) }
    val canUndo = latestDone != null
    val canRedo = redoTarget != null

    fun doUndo() {
        if (busy || !canUndo) return
        busy = true
        scope.launch {
            val r = withContext(Dispatchers.IO) { OperationJournal.undoLatest(root) }
            busy = false
            toast(ctx, if (r == null) "没有可撤销的操作" else "已撤销：${r.label}")
            if (r != null) {
                refresh++
                onUndo()
            }
        }
    }

    fun doRedo() {
        if (busy || !canRedo) return
        busy = true
        scope.launch {
            val r = withContext(Dispatchers.IO) { OperationJournal.redoLatest(root) }
            busy = false
            toast(ctx, if (r == null) "没有可重做的操作" else "已重做：${r.label}")
            if (r != null) {
                refresh++
                onChanged()
            }
        }
    }

    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    Column(Modifier.fillMaxSize().background(Ink)) {
        Row(
            Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(start = 12.dp, end = 16.dp, top = 8.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RoundIconButton(GI.Back, onClick = onClose, background = Color.Transparent)
            Spacer(Modifier.width(Space.xs))
            Text("操作历史", style = T.label, color = Muted)
        }

        Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 6.dp)) {
            Text("操作历史", style = T.display, color = Paper)
            Spacer(Modifier.height(Space.s))
            Text(
                "只记内容改动 · 最近 ${OperationJournal.KEEP} 步 · 一次批量操作算一步",
                style = T.label,
                color = Muted,
            )
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                MiniButton(
                    text = if (latestDone == null) "撤销" else "撤销「${latestDone.label.substringAfterLast(" · ")}」",
                    enabled = canUndo && !busy,
                    accent = true,
                    onClick = { doUndo() },
                )
                MiniButton(
                    text = "重做",
                    enabled = canRedo && !busy,
                    accent = false,
                    onClick = { doRedo() },
                )
            }
            Spacer(Modifier.height(Space.xs))
            Text(
                "只能撤最近一步；撤销后再做新操作，重做就没了。\n" +
                    "删除和合并不在这里——它们进回收站，去「我的 → 回收站」找回",
                style = T.micro,
                color = Muted,
            )
        }

        if (list.isEmpty()) {
            Column(Modifier.fillMaxWidth().weight(1f)) {
                EmptyState(
                    title = "还没有操作记录",
                    sub = "批量赋值、改信息、删世界观 / 角色 / 文件夹都会记一步。\n" +
                        "删掉的稿件和合并掉的槽在回收站里，不在这里",
                )
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = 16.dp,
                    bottom = 24.dp + navBottom,
                ),
                verticalArrangement = Arrangement.spacedBy(Space.s),
                modifier = Modifier.fillMaxWidth().weight(1f),
            ) {
                items(list, key = { it.id }) { r ->
                    HistoryRow(
                        rec = r,
                        status = when {
                            !r.done && r.id == redoTarget?.id -> "已撤销"
                            !r.done -> "已撤销"
                            r.id == latestDone?.id -> "可撤销"
                            else -> ""
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun HistoryRow(rec: OpRecord, status: String) {
    val color = when (rec.kind) {
        "assign" -> Accent
        "edit" -> Accent
        "versions", "version" -> Mint
        "entity" -> Lilac
        else -> Muted
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(CardBg)
            .padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 左侧细色条：一眼分出「赋值 / 合并 / 删除 / 实体」
        Box(
            Modifier
                .width(3.dp)
                .height(30.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(if (rec.done) color else Muted),
        )
        Spacer(Modifier.width(11.dp))
        Column(Modifier.weight(1f)) {
            Text(
                rec.label.ifBlank { rec.kind },
                style = T.bodySm,
                color = if (rec.done) Paper else Muted,
                // 两行：摘要里带了「哪张稿 + 做了什么」，一行放不下就看不到动作了
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                buildList {
                    add(agoShort(rec.at))
                    add("${rec.changes.size} 个文件")
                    if (status.isNotEmpty()) add(status)
                }.joinToString("  ·  "),
                style = T.micro,
                color = if (status == "可撤销") Accent else Muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun MiniButton(
    text: String,
    enabled: Boolean,
    accent: Boolean,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .height(34.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (!enabled) CardBg else if (accent) AccentC else CHigh)
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            style = T.label,
            color = if (!enabled) Muted else if (accent) Accent else OnMid,
            fontWeight = if (accent && enabled) FontWeight.Medium else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 操作历史要「刚刚 / 3 分钟前」，回收站那个是按天的 */
private fun agoShort(ms: Long): String {
    val diff = System.currentTimeMillis() - ms
    val min = diff / 60_000L
    return when {
        min < 1 -> "刚刚"
        min < 60 -> "$min 分钟前"
        min < 60 * 24 -> "${min / 60} 小时前"
        else -> "${min / (60 * 24)} 天前"
    }
}
