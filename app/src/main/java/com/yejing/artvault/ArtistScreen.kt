package com.yejing.artvault

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import java.io.File
import kotlinx.coroutines.launch
import com.yejing.artvault.ui.component.ArtCard
import com.yejing.artvault.ui.component.EmptyState
import com.yejing.artvault.ui.component.GI
import com.yejing.artvault.ui.component.GIcon
import com.yejing.artvault.ui.component.RoundIconButton

/**
 * 单个画师的作品页（画师反查）。
 *
 * 从「统计 → 画师」点进来，只回答一个问题：**这个画师画过哪些稿**。
 * v2-B 实体化之后这一页变简单了：稿件上只有 `artist_id`，本页按 id 过滤；
 * 名字、主页都从实体来，**改名只改实体**（旧名自动进别名），一件稿都不用重写。
 */
@Composable
fun ArtistScreen(
    artistId: String,
    root: File?,
    folders: FolderBook,
    arts: List<Art>,
    lib: Library,
    tax: Taxonomy,
    columns: Int,
    artists: ArtistBook,
    names: VaultNames,
    /**
     * 正在看哪一件（null = 只看网格）。**按 id 记**、状态放 Root：
     * 点「编辑 / 管理版本」时本页会整个离开组合，本地 remember 会被丢掉。
     */
    openId: String?,
    onOpenId: (String?) -> Unit,
    onClose: () -> Unit,
    onChanged: () -> Unit,
    onAddVersion: (String) -> Unit,
    onManageVersions: (String) -> Unit,
    onEdit: (Art) -> Unit,
    onDelete: (List<Art>) -> Unit,
    onOpenArt: (String) -> Unit = {},
    onAddDerived: (String) -> Unit = {},
    onUnlink: (String) -> Unit = {},
    onPickSource: (String) -> Unit = {},
    onOpenRole: (String) -> Unit = {},
    onOpenArtist: (String) -> Unit = {},
    onFilterTag: (String) -> Unit = {},
    onFilterWorld: (String) -> Unit = {},
    savedScrolls: MutableMap<String, Int> = remember { HashMap() },
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val entity = artists.byId(artistId)

    val counted = remember(arts) { arts.countedOnly() }
    val mine = remember(counted, artistId) {
        counted.filter { it.artistId == artistId }
            .sortedByDescending { Dates.sortKey(it.receivedAt) }
    }
    val artistCounts = remember(counted) {
        val m = HashMap<String, Int>()
        counted.forEach { a -> a.artistId?.let { m[it] = (m[it] ?: 0) + 1 } }
        m
    }
    val mergeTargets = remember(artists.all, artistCounts, artistId) {
        artists.all
            .filter { it.id != artistId }
            .sortedByDescending { artistCounts[it.id] ?: 0 }
            .map { EntityMergeChoice(it.id, it.name, "${artistCounts[it.id] ?: 0} 件", artistCounts[it.id] ?: 0) }
    }
    // 0 也算「记了价格」（免费稿），只排除 null
    val priced = remember(mine) { mine.mapNotNull { it.priceTotal } }
    val latest = remember(mine) {
        mine.filter { Dates.parse(it.receivedAt) != null }
            .maxByOrNull { Dates.sortKey(it.receivedAt) }?.receivedAt
    }

    var editing by remember(artistId) { mutableStateOf(false) }
    var renaming by remember(artistId) { mutableStateOf(false) }
    var menuOpen by remember(artistId) { mutableStateOf(false) }
    var merging by remember(artistId) { mutableStateOf(false) }
    var confirmDelete by remember(artistId) { mutableStateOf(false) }
    var demoLinkTip by remember(artistId) { mutableStateOf(false) }

    val open = openId?.let { id -> mine.indexOfFirst { it.id == id } }?.takeIf { it >= 0 }
    if (open != null && mine.isNotEmpty()) {
        DetailScreen(
            arts = mine,
            lib = lib,
            tax = tax,
            names = names,
            root = root,
            folders = folders,
            rangeLabel = "画师: ${entity?.name ?: "未知"}",
            startIndex = open.coerceIn(0, mine.lastIndex),
            onAddVersion = onAddVersion,
            onEdit = onEdit,
            onDelete = { a -> onDelete(listOf(a)) },
            // 版本管理已经内嵌进详情页的 tab，这里只需在写完盘后重载
            onChanged = onChanged,
            onManageVersions = onManageVersions,
            onPageChanged = onOpenId,
            onClose = { onOpenId(null) },
            allArts = arts,
            onOpenArt = onOpenArt,
            onAddDerived = onAddDerived,
            onUnlink = onUnlink,
            onPickSource = onPickSource,
            onOpenRole = onOpenRole,
            onOpenArtist = onOpenArtist,
            onFilterTag = onFilterTag,
            onFilterWorld = onFilterWorld,
            savedScrolls = savedScrolls,
        )
        return
    }

    BackHandler {
        android.util.Log.d("GaoCangBack", "artistList")
        onClose()
    }

    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    Column(Modifier.fillMaxSize().background(Ink)) {
        Row(
            Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RoundIconButton(GI.Back, onClick = onClose, background = Color.Transparent)
            Spacer(Modifier.width(Space.xs))
            Text("画师", style = T.label, color = Muted)
            Spacer(Modifier.weight(1f))
            if (entity != null && artists.writable) {
                Box {
                    RoundIconButton(
                        GI.More,
                        onClick = { menuOpen = true },
                        background = Color.Transparent,
                    )
                    DropdownMenu(
                        expanded = menuOpen,
                        onDismissRequest = { menuOpen = false },
                        containerColor = CHigh,
                    ) {
                        DropdownMenuItem(
                            text = { Text("重命名", style = T.bodySm, color = Paper) },
                            onClick = {
                                menuOpen = false
                                renaming = true
                            },
                        )
                        DropdownMenuItem(
                            text = {
                                Text(
                                    "合并其他",
                                    style = T.bodySm,
                                    color = if (mergeTargets.isNotEmpty()) Paper else Muted,
                                )
                            },
                            enabled = mergeTargets.isNotEmpty(),
                            onClick = {
                                menuOpen = false
                                merging = true
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("删除", style = T.bodySm, color = Warn) },
                            onClick = {
                                menuOpen = false
                                confirmDelete = true
                            },
                        )
                    }
                }
            }
        }

        Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 6.dp)) {
            Text(
                // 实体没了（被删 / 数据坏了）也照常显示这页：作品还在，别让人进不来
                entity?.name ?: VaultNames.MISSING,
                style = T.display,
                color = if (entity == null) Warn else Paper,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(Space.s))
            Text(
                buildList {
                    add("${mine.size} 件")
                    if (priced.isNotEmpty()) add("合计 ¥${priced.sum()}")
                    latest?.let { add("最近 ${Dates.datePart(it)}") }
                }.joinToString("  ·  "),
                style = T.label,
                color = Muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            Spacer(Modifier.height(14.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Space.s),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val link = entity?.homepage
                if (link == null) {
                    LinkPill("添加主页链接", GI.Plus, accent = true) { editing = true }
                } else {
                    val hostLabel = ArtistLink.label(link)
                    val btnLabel = if (hostLabel == "主页") "主页" else "${hostLabel}主页"
                    LinkPill(btnLabel, GI.External) {
                        if (root == null) {
                            demoLinkTip = true
                        } else if (!ArtistLink.open(ctx, link)) {
                            toast(ctx, "打不开这个链接，检查一下地址")
                        }
                    }
                    LinkPill("改", null) { editing = true }
                }
            }
            if (entity != null && entity.aliases.isNotEmpty()) {
                Spacer(Modifier.height(Space.s))
                Text(
                    "也叫：" + entity.aliases.joinToString(" / "),
                    style = T.micro,
                    color = Muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        if (mine.isEmpty()) {
            Column(Modifier.fillMaxWidth().weight(1f)) {
                EmptyState(
                    title = "还没有这个画师的稿件",
                    sub = "在稿件的「画师」字段里填上 ${entity?.name ?: "这个名字"}，就会出现在这里",
                )
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(columns),
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = 18.dp,
                    bottom = 24.dp + navBottom,
                ),
                horizontalArrangement = Arrangement.spacedBy(Space.s),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier.fillMaxWidth().weight(1f),
            ) {
                itemsIndexed(mine, key = { _, a -> a.id }) { _, art ->
                    ArtCard(
                        art = art,
                        lib = lib,
                        onClick = { onOpenId(art.id) },
                    )
                }
            }
        }
    }

    if (demoLinkTip) {
        AlertDialog(
            onDismissRequest = { demoLinkTip = false },
            containerColor = CardBg,
            shape = RoundedCornerShape(24.dp),
            title = { Text("演示功能说明", style = T.titleSm, color = Paper) },
            text = {
                Text(
                    "此为演示功能，在真实图库中可以填入画师主页并按下此按钮直接跳转",
                    style = T.bodySm,
                    color = OnMid,
                )
            },
            confirmButton = {
                TextButton(onClick = { demoLinkTip = false }) {
                    Text("知道了", style = T.body, color = Accent)
                }
            },
        )
    }

    if (editing) {
        LinkDialog(
            initial = entity?.homepage.orEmpty(),
            onDismiss = { editing = false },
            onSave = { url ->
                editing = false
                when {
                    !artists.writable -> toast(ctx, "演示库不能改")
                    !artists.setHomepage(artistId, url) -> toast(ctx, "写入失败，检查库目录是否可写")
                    else -> onChanged()
                }
            },
        )
    }

    if (renaming) {
        TextPromptDialog(
            title = "重命名画师",
            hint = "画师名",
            initial = entity?.name.orEmpty(),
            onDismiss = { renaming = false },
            onConfirm = { input ->
                renaming = false
                val name = input.trim()
                if (name.isEmpty() || name == entity?.name) return@TextPromptDialog
                // 只改实体：旧名进别名，稿件上的 id 不动，主页链接当然也不动
                val ok = artists.rename(artistId, name)
                toast(
                    ctx,
                    if (ok) "已改成「$name」" else "改名失败：新名字已被另一个画师占用",
                )
                if (ok) onChanged()
            },
        )
    }

    if (merging && entity != null) {
        EntityMergeDialog(
            kindLabel = "画师",
            intoName = entity.name,
            works = mine.size,
            targets = mergeTargets,
            onDismiss = { merging = false },
            onConfirm = { from ->
                merging = false
                val r = root
                if (r == null) {
                    toast(ctx, "演示库不能改")
                    return@EntityMergeDialog
                }
                scope.launch {
                    try {
                        val n = OperationJournal.run(r, "entity", "合并画师") {
                            EntityCommands.mergeArtist(r, artists, from.id, entity.id)
                        }
                        toast(ctx, "已把「${from.name}」合并进来（$n 件）")
                        onChanged()
                    } catch (t: Throwable) {
                        toast(ctx, "合并失败：${t.message ?: t.javaClass.simpleName}")
                    }
                }
            },
        )
    }

    if (confirmDelete && entity != null) {
        EntityDeleteDialog(
            title = "删除画师「${entity.name}」？",
            detail = "${mine.size} 件稿的画师会变成未填",
            onDismiss = { confirmDelete = false },
            onConfirm = {
                confirmDelete = false
                val r = root
                if (r == null) {
                    toast(ctx, "演示库不能改")
                    return@EntityDeleteDialog
                }
                scope.launch {
                    try {
                        val n = OperationJournal.run(r, "entity", "删除画师「${entity.name}」") {
                            EntityCommands.deleteArtist(r, artists, entity.id)
                        }
                        if (artists.byId(entity.id) != null) {
                            toast(ctx, "删除失败，检查库目录是否可写")
                            return@launch
                        }
                        toast(ctx, if (n > 0) "已删除，$n 件稿的画师已变成未填" else "已删除")
                        onChanged()
                        onClose()
                    } catch (t: Throwable) {
                        toast(ctx, "删除失败：${t.message ?: t.javaClass.simpleName}")
                    }
                }
            },
        )
    }
}

internal data class EntityMergeChoice(
    val id: String,
    val name: String,
    val sub: String,
    /** 件数的数值：流向条要着色 / 排版，不能只靠 [sub] 的字符串 */
    val count: Int,
)

@Composable
internal fun EntityMergeDialog(
    kindLabel: String,
    /** 当前这一个：合并后保留的主身份。列表里选的是「并进来的」那一个 */
    intoName: String,
    /** 当前这一个的件数 */
    works: Int,
    targets: List<EntityMergeChoice>,
    onDismiss: () -> Unit,
    onConfirm: (EntityMergeChoice) -> Unit,
) {
    var selectedId by remember(targets) { mutableStateOf(targets.firstOrNull()?.id) }
    val selected = targets.firstOrNull { it.id == selectedId }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = CardBg,
        shape = RoundedCornerShape(24.dp),
        title = {
            Text(
                "将哪个${kindLabel}合并到「$intoName」",
                style = T.titleSm,
                color = Paper,
            )
        },
        text = {
            Column {
                if (targets.isEmpty()) {
                    Text("没有其他可合并的目标", style = T.bodySm, color = Muted)
                } else {
                    LazyColumn(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(max = 260.dp),
                        verticalArrangement = Arrangement.spacedBy(Space.xs),
                    ) {
                        items(targets, key = { it.id }) { t ->
                            val on = t.id == selectedId
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(if (on) AccentC else CHigh)
                                    .clickable { selectedId = t.id }
                                    .padding(horizontal = 13.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    t.name,
                                    style = T.bodySm,
                                    color = if (on) Accent else Paper,
                                    fontWeight = if (on) FontWeight.Medium else FontWeight.Normal,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                                Spacer(Modifier.width(Space.s))
                                Text(
                                    t.sub,
                                    style = T.micro,
                                    color = if (on) Accent else Muted,
                                )
                                if (on) {
                                    Spacer(Modifier.width(Space.xs))
                                    GIcon(GI.Check, Accent, size = 14.dp, strokeWidth = 2f)
                                }
                            }
                        }
                    }
                    if (selected != null) {
                        Spacer(Modifier.height(Space.m))
                        // 流向条：给人看方向；下面那条琥珀提示说清后果，两条都要
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(CardBg)
                                .border(1.dp, OutlineSoft, RoundedCornerShape(14.dp))
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("原身份（将移除）", style = T.micro, color = Muted, maxLines = 1)
                                Text(
                                    "${selected.name} · ${selected.count} 件",
                                    style = T.bodySm,
                                    color = Warn,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Spacer(Modifier.width(Space.s))
                            Text("→", style = T.bodySm, color = Muted)
                            Spacer(Modifier.width(Space.s))
                            Column(Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                                Text("目标主身份（保留）", style = T.micro, color = Muted, maxLines = 1)
                                Text(
                                    "$intoName · $works 件",
                                    style = T.bodySm,
                                    color = Mint,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        Spacer(Modifier.height(Space.s))
                        Text(
                            "会把 ${selected.count} 件稿的『$kindLabel』改成 $intoName，「${selected.name}」实体删除",
                            style = T.label,
                            color = Amber,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(AmberC)
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (selected != null) onConfirm(selected) },
                enabled = selected != null,
            ) {
                Text(
                    "合并",
                    style = T.label,
                    color = if (selected != null) Accent else Muted,
                    fontWeight = FontWeight.Medium,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消", style = T.label, color = Muted)
            }
        },
    )
}

@Composable
internal fun EntityDeleteDialog(
    title: String,
    detail: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = CardBg,
        shape = RoundedCornerShape(24.dp),
        title = { Text(title, style = T.titleSm, color = Paper) },
        text = { Text(detail, style = T.bodySm, color = Muted) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("删除", style = T.label, color = Warn, fontWeight = FontWeight.Medium)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消", style = T.label, color = Muted)
            }
        },
    )
}

@Composable
private fun LinkPill(text: String, icon: GI?, accent: Boolean = false, onClick: () -> Unit) {
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(if (accent) AccentC else CardBg)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        if (icon != null) {
            GIcon(icon, if (accent) Accent else OnMid, size = 13.dp, strokeWidth = 1.8f)
        }
        Text(
            text,
            style = T.label,
            color = if (accent) Accent else OnMid,
            fontWeight = FontWeight.Medium,
        )
    }
}

/**
 * 主页链接输入。
 * 直接粘「分享自米画师 …… https://…」这种整段也能存——[ArtistLink.normalize] 会把链接挑出来。
 */
@Composable
private fun LinkDialog(initial: String, onDismiss: () -> Unit, onSave: (String?) -> Unit) {
    var v by remember(initial) { mutableStateOf(initial) }
    val url = remember(v) { ArtistLink.normalize(v) }
    val bad = v.isNotBlank() && url == null

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = CardBg,
        shape = RoundedCornerShape(24.dp),
        title = {
            Text(
                if (initial.isBlank()) "添加主页链接" else "修改主页链接",
                style = T.titleSm,
                color = Paper,
            )
        },
        text = {
            Column {
                BasicTextField(
                    value = v,
                    onValueChange = { v = it },
                    singleLine = true,
                    textStyle = T.bodySm.copy(color = Paper),
                    cursorBrush = SolidColor(Accent),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(CardBg)
                        .padding(horizontal = 13.dp, vertical = 12.dp),
                    decorationBox = { inner ->
                        Box {
                            if (v.isBlank()) Text("粘贴画师主页链接", style = T.bodySm, color = Muted)
                            inner()
                        }
                    },
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    when {
                        v.isBlank() -> "米画师的地址形如 www.mihuashi.com/profiles/257345\n要数字 ID 那种，换显示名也不会失效"
                        bad -> "这看起来不是链接"
                        else -> "${ArtistLink.label(url!!)} · 点开用浏览器打开"
                    },
                    style = T.micro,
                    color = if (bad) Warn else Muted,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(url) },
                enabled = v.isBlank() || url != null,
            ) {
                Text(
                    "保存",
                    style = T.label,
                    color = if (v.isBlank() || url != null) Accent else Muted,
                    fontWeight = FontWeight.Medium,
                )
            }
        },
        dismissButton = {
            Row {
                if (initial.isNotBlank()) {
                    TextButton(onClick = { onSave(null) }) {
                        Text("移除", style = T.label, color = Warn)
                    }
                }
                TextButton(onClick = onDismiss) {
                    Text("取消", style = T.label, color = Muted)
                }
            }
        },
    )
}
