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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.yejing.artvault.ui.component.EmptyState
import com.yejing.artvault.ui.component.GI
import com.yejing.artvault.ui.component.RoundIconButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 回收站。
 *
 * 收三种东西，用**顶部 tab 分类型、行内小标签分来源**，不再靠一坨小字解释：
 * - 槽位 tab：整槽删除（用户删的）、合并时并掉的槽（带「合并」标签，恢复前确认）
 * - 版本 tab：从槽里删掉的单个版本，一键加回原槽
 *
 * 图片文件任何时候都不删；「彻底删除」只删元数据记录。
 * 不做自动清理——一条 JSON 只有几 KB，删掉却再也拼不回「哪次约稿、多少钱、谁画的」。
 */
@Composable
fun TrashScreen(
    root: File,
    lib: Library,
    names: VaultNames,
    onClose: () -> Unit,
    onChanged: () -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var items by remember { mutableStateOf<List<TrashEntry>>(emptyList()) }
    var tab by remember { mutableIntStateOf(0) }
    var refresh by remember { mutableIntStateOf(0) }
    var confirmOne by remember { mutableStateOf<TrashEntry?>(null) }
    var confirmEmpty by remember { mutableStateOf(false) }
    /** 合并来源的槽恢复前要确认：恢复 = 撤销那次合并的一半，会多出一个内容重复的旧槽 */
    var confirmRestore by remember { mutableStateOf<TrashedArt?>(null) }
    /** 加回版本前也确认一下：它进的是版本列表末尾，用户可能以为主图会变 */
    var confirmVer by remember { mutableStateOf<TrashedVersion?>(null) }
    var busy by remember { mutableStateOf(false) }

    LaunchedEffect(root, refresh) {
        items = withContext(Dispatchers.IO) { Trash(root).list() }
    }

    BackHandler { onClose() }

    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val slots = items.filterIsInstance<TrashEntry.Slot>()
    val versions = items.filterIsInstance<TrashEntry.Version>()

    fun restoreSlot(t: TrashedArt) {
        if (busy) return
        busy = true
        scope.launch {
            val ok = withContext(Dispatchers.IO) { Trash(root).restore(t) }
            busy = false
            toast(
                ctx,
                if (ok) "已恢复" else "恢复失败：库里已有同 id 的稿件（可能刚从别的设备同步回来）",
            )
            if (ok) {
                refresh++
                onChanged()
            }
        }
    }

    fun restoreVersion(v: TrashedVersion) {
        if (busy) return
        busy = true
        scope.launch {
            val ok = withContext(Dispatchers.IO) { Trash(root).restore(v) }
            busy = false
            toast(
                ctx,
                if (ok) "已加回「${v.artTitleNow ?: v.artTitle}」" else "恢复失败：所属槽已不在库里",
            )
            if (ok) {
                refresh++
                onChanged()
            }
        }
    }

    Column(Modifier.fillMaxSize().background(Ink)) {
        Row(
            Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(start = 12.dp, end = 16.dp, top = 8.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RoundIconButton(GI.Back, onClick = onClose, background = Color.Transparent)
            Spacer(Modifier.width(Space.xs))
            Column(Modifier.weight(1f)) {
                Text("回收站", style = T.titleSm, color = Paper)
                Text(
                    if (items.isEmpty()) "空的" else "${items.size} 条记录 · 图片文件都还在",
                    style = T.micro,
                    color = Muted,
                )
            }
            if (items.isNotEmpty()) {
                Box(
                    Modifier
                        .clip(RoundedCornerShape(50))
                        .background(WarnC)
                        .clickable(enabled = !busy) { confirmEmpty = true }
                        .padding(horizontal = 12.dp, vertical = 7.dp),
                ) {
                    Text("清空", style = T.label, color = Warn, fontWeight = FontWeight.Medium)
                }
            }
        }

        // 与「角色库 / 统计」同一套分段控件：tab 管类型，行内标签管来源
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .clip(RoundedCornerShape(50))
                .background(CardBg)
                .padding(3.dp),
        ) {
            listOf("槽位 ${slots.size}", "版本 ${versions.size}").forEachIndexed { i, label ->
                Box(
                    Modifier
                        .weight(1f)
                        .height(36.dp)
                        .clip(RoundedCornerShape(50))
                        .background(if (tab == i) AccentC else Color.Transparent)
                        .clickable { tab = i },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        label,
                        style = T.bodySm,
                        color = if (tab == i) Accent else Muted,
                        fontWeight = if (tab == i) FontWeight.Medium else FontWeight.Normal,
                    )
                }
            }
        }

        val list = if (tab == 0) slots else versions
        if (list.isEmpty()) {
            Column(Modifier.fillMaxWidth().weight(1f)) {
                EmptyState(
                    title = if (tab == 0) "没有删除过槽位" else "没有删除过版本",
                    sub = if (tab == 0) {
                        "整槽删除和合并时并掉的旧槽会出现在这里，可恢复。"
                    } else {
                        "在详情页「管理」里删掉的单个版本会出现在这里，可加回原槽。"
                    },
                )
            }
        } else {
            LazyColumn(
                Modifier.fillMaxWidth().weight(1f),
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = 12.dp,
                    bottom = 24.dp + navBottom,
                ),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(list, key = { it.file.absolutePath }) { e ->
                    when (e) {
                        is TrashEntry.Slot -> TrashRow(
                            t = e.t,
                            lib = lib,
                            names = names,
                            onRestore = {
                                if (e.t.viaMerge) confirmRestore = e.t else restoreSlot(e.t)
                            },
                            onPurge = { confirmOne = e },
                        )

                        is TrashEntry.Version -> VersionRow(
                            v = e.v,
                            lib = lib,
                            onRestore = { confirmVer = e.v },
                            onPurge = { confirmOne = e },
                        )
                    }
                }
                item {
                    Spacer(Modifier.height(Space.xs))
                    Text(
                        "「彻底删除」只删这条记录；图片文件保留。合并来源的槽恢复前会先确认。",
                        style = T.micro,
                        color = Muted,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                }
            }
        }
    }

    confirmVer?.let { v ->
        AlertDialog(
            onDismissRequest = { confirmVer = null },
            containerColor = CardBg,
            shape = RoundedCornerShape(24.dp),
            title = { Text("加回「${v.ver.label}」？", style = T.titleSm, color = Paper) },
            text = {
                Text(
                    "会加到「${v.artTitleNow ?: v.artTitle}」版本列表的末尾，主图不变。\n" +
                        "想调整顺序或换主图，去详情页的「管理」。",
                    style = T.bodySm,
                    color = Muted,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmVer = null
                    restoreVersion(v)
                }) { Text("加回", style = T.label, color = Accent, fontWeight = FontWeight.Medium) }
            },
            dismissButton = {
                TextButton(onClick = { confirmVer = null }) {
                    Text("取消", style = T.label, color = Muted)
                }
            },
        )
    }

    confirmRestore?.let { t ->
        val target = t.mergedIntoTitle
        AlertDialog(
            onDismissRequest = { confirmRestore = null },
            containerColor = CardBg,
            shape = RoundedCornerShape(24.dp),
            title = { Text("恢复这个被合并的槽？", style = T.titleSm, color = Paper) },
            text = {
                Text(
                    if (target != null) {
                        "它的图片版本都已经在「$target」里了。恢复会多出一个内容重复的旧槽，" +
                            "和合并后的那件各存一份。\n如果你其实是想找回那几张图，直接去稿库看「$target」就好。"
                    } else {
                        "它原本在合并时被并进了另一个槽，但那个槽现在也已经不在库里了。" +
                            "恢复会得到一个独立的旧槽。"
                    },
                    style = T.bodySm,
                    color = Muted,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmRestore = null
                    restoreSlot(t)
                }) { Text("仍然恢复", style = T.label, color = Accent, fontWeight = FontWeight.Medium) }
            },
            dismissButton = {
                TextButton(onClick = { confirmRestore = null }) {
                    Text("算了", style = T.label, color = Muted)
                }
            },
        )
    }

    confirmOne?.let { e ->
        val name = when (e) {
            is TrashEntry.Slot ->
                e.t.art?.title?.takeIf { it.isNotBlank() }
                    ?: e.t.art?.artistId?.let { names.artist(it) }
                        ?.takeIf { it.isNotBlank() && it != VaultNames.MISSING }
                    ?: "未命名"
            is TrashEntry.Version -> "「${e.v.artTitleNow ?: e.v.artTitle}」的版本「${e.v.ver.label}」"
        }
        AlertDialog(
            onDismissRequest = { confirmOne = null },
            containerColor = CardBg,
            shape = RoundedCornerShape(24.dp),
            title = { Text("彻底删除$name？", style = T.titleSm, color = Paper) },
            text = {
                Text(
                    "只会删掉这条记录，图片文件保留在 blobs/ 里。删除后无法撤销。",
                    style = T.bodySm,
                    color = Muted,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmOne = null
                    scope.launch {
                        val ok = withContext(Dispatchers.IO) { Trash(root).purge(e) }
                        toast(ctx, if (ok) "已彻底删除" else "删除失败")
                        refresh++
                        // 让「我的」里的条数跟着变（库内容没动，只是刷新计数）
                        onChanged()
                    }
                }) { Text("彻底删除", style = T.label, color = Warn, fontWeight = FontWeight.Medium) }
            },
            dismissButton = {
                TextButton(onClick = { confirmOne = null }) {
                    Text("取消", style = T.label, color = Muted)
                }
            },
        )
    }

    if (confirmEmpty) {
        AlertDialog(
            onDismissRequest = { confirmEmpty = false },
            containerColor = CardBg,
            shape = RoundedCornerShape(24.dp),
            title = { Text("清空回收站？", style = T.titleSm, color = Paper) },
            text = {
                Text(
                    "会删掉这 ${items.size} 条记录（槽位 + 版本），图片文件保留。清空后无法撤销。",
                    style = T.bodySm,
                    color = Muted,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmEmpty = false
                    scope.launch {
                        val n = withContext(Dispatchers.IO) { Trash(root).purgeAll() }
                        toast(ctx, "已清空 $n 条记录")
                        refresh++
                        onChanged()
                    }
                }) { Text("清空", style = T.label, color = Warn, fontWeight = FontWeight.Medium) }
            },
            dismissButton = {
                TextButton(onClick = { confirmEmpty = false }) {
                    Text("取消", style = T.label, color = Muted)
                }
            },
        )
    }
}

/** 来源小标签：一眼分清这条是「你删的」还是「合并时并掉的」 */
@Composable
private fun SourceTag(merge: Boolean) {
    Box(
        Modifier
            .clip(RoundedCornerShape(5.dp))
            .background(if (merge) AmberC else CHigh)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(
            if (merge) "合并" else "删除",
            style = T.micro,
            color = if (merge) Amber else Muted,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun TrashRow(
    t: TrashedArt,
    lib: Library,
    names: VaultNames,
    onRestore: () -> Unit,
    onPurge: () -> Unit,
) {
    val art = t.art
    val title = art?.title?.takeIf { it.isNotBlank() }
        ?: art?.artistId?.let { names.artist(it) }?.takeIf { it.isNotBlank() && it != VaultNames.MISSING }
        ?: "未命名"
    val sub = buildList {
        art?.artistId?.let { names.artist(it) }
            ?.takeIf { it.isNotBlank() && it != VaultNames.MISSING }?.let { add(it) }
        art?.receivedAt?.let { add(Dates.datePart(it)) }
        art?.versions?.size?.let { add("$it 个版本") }
        if (art == null) add("文件读不出来")
    }.joinToString("  ·  ")

    // 大卡：图 + 信息一行，操作按钮整行沉底——原来按钮挤右侧把文字压得只剩省略号
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(CardBg)
            .padding(horizontal = 12.dp, vertical = 11.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .width(60.dp)
                    .height(80.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(CardBg),
            ) {
                art?.primary?.let { v ->
                    AsyncImage(
                        model = lib.thumb(v),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            Spacer(Modifier.width(Space.m))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
                    Text(
                        title,
                        style = T.titleSm,
                        color = Paper,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    SourceTag(merge = t.viaMerge)
                }
                Spacer(Modifier.height(5.dp))
                Text(
                    sub,
                    style = T.micro,
                    color = Muted,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(3.dp))
                Text("删除于 ${ago(t.deletedAt)}", style = T.micro, color = Muted, maxLines = 1)
            }
        }
        Spacer(Modifier.height(10.dp))
        ActionPair(onRestore = onRestore, onPurge = onPurge)
    }
}

@Composable
private fun VersionRow(
    v: TrashedVersion,
    lib: Library,
    onRestore: () -> Unit,
    onPurge: () -> Unit,
) {
    val slotTitle = v.artTitleNow ?: v.artTitle
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(CardBg)
            .padding(horizontal = 12.dp, vertical = 11.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .width(60.dp)
                    .height(80.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(CardBg),
            ) {
                if (v.ver.blob.isNotBlank()) {
                    AsyncImage(
                        model = lib.thumb(v.ver),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            Spacer(Modifier.width(Space.m))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
                    Text(
                        v.ver.label,
                        style = T.titleSm,
                        color = Paper,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (!v.artExists) {
                        Box(
                            Modifier
                                .clip(RoundedCornerShape(5.dp))
                                .background(WarnC)
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                        ) {
                            Text("槽已删除", style = T.micro, color = Warn, fontWeight = FontWeight.Medium)
                        }
                    }
                }
                Spacer(Modifier.height(5.dp))
                Text(
                    "属于「$slotTitle」",
                    style = T.micro,
                    color = Muted,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(3.dp))
                Text("删除于 ${ago(v.deletedAt)}", style = T.micro, color = Muted, maxLines = 1)
            }
        }
        Spacer(Modifier.height(10.dp))
        ActionPair(onRestore = onRestore, onPurge = onPurge, label = "加回", enabled = v.artExists)
    }
}

/** 底部操作行：两枚等宽大按钮，替代原来挤在右侧的小胶囊 */
@Composable
private fun ActionPair(
    onRestore: () -> Unit,
    onPurge: () -> Unit,
    label: String = "恢复",
    enabled: Boolean = true,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
        Box(
            Modifier
                .weight(1f)
                .height(34.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(if (enabled) AccentC else CHigh)
                .clickable(enabled = enabled, onClick = onRestore),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                label,
                style = T.label,
                color = if (enabled) Accent else Muted,
                fontWeight = FontWeight.Medium,
            )
        }
        Box(
            Modifier
                .weight(1f)
                .height(34.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(WarnC)
                .clickable(onClick = onPurge),
            contentAlignment = Alignment.Center,
        ) {
            Text("彻底删除", style = T.label, color = Warn)
        }
    }
}

/** 「今天 / 昨天 / N 天前」——回收站里只需要粗粒度，别把日期格式搞复杂 */
private fun ago(ms: Long): String {
    val days = (System.currentTimeMillis() - ms) / 86_400_000L
    return when {
        days <= 0 -> "今天"
        days == 1L -> "昨天"
        days < 30 -> "$days 天前"
        days < 365 -> "${days / 30} 个月前"
        else -> "${days / 365} 年前"
    }
}
