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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.dragselectcompose.core.gridDragSelect
import com.dragselectcompose.core.rememberDragSelectState
import com.yejing.artvault.browse.SelectionActions
import kotlinx.coroutines.launch
import java.io.File
import com.yejing.artvault.ui.component.ArtCard
import com.yejing.artvault.ui.component.AvatarCropScreen
import com.yejing.artvault.ui.component.AvatarSinglePicker
import com.yejing.artvault.ui.component.ChipPill
import com.yejing.artvault.ui.component.EmptyState
import com.yejing.artvault.ui.component.GI
import com.yejing.artvault.ui.component.GIcon
import com.yejing.artvault.ui.component.RoundIconButton

/**
 * 单个角色的独立页面。
 *
 * 角色库只负责「按世界观浏览角色」，所有管理动作（改名、换头像、删除）
 * 以及这个角色的稿件浏览都在这里，不再从角色库弹轻量对话框。
 */
@Composable
fun RoleScreen(
    roleId: String,
    root: File?,
    folders: FolderBook,
    arts: List<Art>,
    lib: Library,
    tax: Taxonomy,
    columns: Int,
    /** id → 名字的解析器：本页要显示稿件上的标签名 */
    names: VaultNames,
    /**
     * 正在看哪一件（null = 只看网格）。
     * **按 id 记、不按下标**，而且状态放在 Root：点「编辑 / 管理版本」时 Root 换分支，
     * 本页整个离开组合，本地 remember（包括标签筛选）会被丢掉——用下标会指到别的稿。
     */
    openId: String?,
    onOpenId: (String?) -> Unit,
    onClose: () -> Unit,
    onChanged: () -> Unit,
    onAddVersion: (String) -> Unit,
    onManageVersions: (String) -> Unit,
    onEdit: (Art) -> Unit,
    onAssign: (List<Art>) -> Unit = {},
    onMerge: (List<Art>) -> Unit = {},
    onDelete: (List<Art>) -> Unit,
    onExport: (List<Art>) -> Unit = {},
    onOpenArt: (String) -> Unit = {},
    onAddDerived: (String) -> Unit = {},
    onUnlink: (String) -> Unit = {},
    onPickSource: (String) -> Unit = {},
    onOpenRole: (String) -> Unit = {},
    onOpenArtist: (String) -> Unit = {},
    onFilterTag: (String) -> Unit = {},
    onFilterWorld: (String) -> Unit = {},
    savedScrolls: MutableMap<String, Int> = remember { HashMap() },
    petLabel: String = "附属",
) {
    val role = tax.roles.firstOrNull { it.id == roleId }
    if (role == null) {
        // 角色被删掉/合并后不能留在一个空页面
        LaunchedEffect(roleId) { onClose() }
        return
    }

    var selectedTag by remember(roleId) { mutableStateOf<String?>(null) }
    var menuOpen by remember(roleId) { mutableStateOf(false) }
    var renaming by remember(roleId) { mutableStateOf(false) }
    var confirmDelete by remember(roleId) { mutableStateOf(false) }
    var moving by remember(roleId) { mutableStateOf(false) }
    var picking by remember(roleId) { mutableStateOf(false) }
    var cropUri by remember(roleId) { mutableStateOf<android.net.Uri?>(null) }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val drag = rememberDragSelectState<Art>(compareSelector = { it.id })
    val gridState = drag.gridState
    val selection = drag.selected
    val selecting = drag.inSelectionMode

    var showExtra by remember(roleId) { mutableStateOf(false) }
    val srcById = remember(arts) { arts.associateBy { it.id } }
    fun hasRole(a: Art): Boolean =
        roleId in a.roleIds ||
            (a.kind == ArtKind.Derived && srcById[a.sourceArtId]?.let { roleId in it.roleIds } == true)
    val counted = remember(arts, roleId) { arts.filter { hasRole(it) && it.counted } }
    val extra = remember(arts, roleId) { arts.filter { hasRole(it) && !it.counted } }
    val petIds = remember(tax.roles) { tax.roles.filter { it.pet }.mapTo(HashSet()) { it.id } }
    val spend = remember(counted, roleId, petIds) { counted.sumOf { it.shareFor(roleId, petIds) } }
    // **合并后必须重排**：直接 `counted + extra` 会把二次创作全堆在列表末尾，
    // 稿件一多新卡片就落在屏幕外，点完开关看起来就是"没反应"（2026-11-22 用户报的）。
    val roleArts = remember(counted, extra, showExtra) {
        (if (showExtra) counted + extra else counted)
            .sortedByDescending { it.receivedAt.orEmpty() }
    }
    // 换角色 / 切换筛选项时回顶并退出多选
    LaunchedEffect(roleId) { drag.disableSelectionMode() }
    LaunchedEffect(showExtra, selectedTag) {
        gridState.scrollToItem(0)
        drag.disableSelectionMode()
    }
    val tags = remember(roleArts) {
        roleArts.flatMap { names.tagsOf(it.tagIds) }
            .groupingBy { it }
            .eachCount()
            .entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .map { it.key }
    }
    val shown = remember(roleArts, selectedTag) {
        if (selectedTag == null) roleArts else roleArts.filter { selectedTag in names.tagsOf(it.tagIds) }
    }
    LaunchedEffect(shown) { drag.reconcile(shown) }

    val target = tax.avatarTarget(role.id)
    val exclude = remember(tax) {
        tax.rootOf()?.let { Mirror.generatedDirs(it) } ?: emptyList()
    }

    // 头像流程也是本页的一部分：返回键先退回角色页，不能直接关掉整个角色
    BackHandler(enabled = picking || cropUri != null) {
        if (cropUri != null) cropUri = null else picking = false
    }

    if (cropUri != null && target != null) {
        AvatarCropScreen(
            uri = cropUri!!,
            target = target,
            onCancel = { cropUri = null },
            onSaved = {
                tax.setAvatar(role.id, "avatars/${it.name}")
                onChanged()
                cropUri = null
            },
        )
        return
    }
    if (picking) {
        AvatarSinglePicker(
            excludeDirs = exclude,
            onCancel = { picking = false },
            onPick = { uri ->
                picking = false
                cropUri = uri
            },
        )
        return
    }

    val open = openId?.let { id -> shown.indexOfFirst { it.id == id } }?.takeIf { it >= 0 }
    if (open != null && shown.isNotEmpty()) {
        DetailScreen(
            arts = shown,
            lib = lib,
            tax = tax,
            names = names,
            root = root,
            folders = folders,
            rangeLabel = "角色: ${role?.name ?: "未知"}",
            startIndex = open.coerceIn(0, shown.lastIndex),
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

    BackHandler(enabled = selecting) { drag.disableSelectionMode() }
    BackHandler(enabled = !selecting) {
        android.util.Log.d("GaoCangBack", "roleList")
        onClose()
    }

    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val canMove = role.worldId != null || tax.worlds.any { it.id != role.worldId }

    Box(Modifier.fillMaxSize().background(Ink)) {
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (selecting) {
                RoundIconButton(GI.Close, onClick = { drag.disableSelectionMode() }, background = Color.Transparent)
                Spacer(Modifier.width(Space.s))
                Text("已选 ${selection.size} 件", style = T.titleSm, color = Paper, maxLines = 1)
                Spacer(Modifier.weight(1f))
                ChipPill("全选", onClick = { drag.updateSelected(shown) })
            } else {
                RoundIconButton(GI.Back, onClick = onClose, background = Color.Transparent)
                Spacer(Modifier.width(Space.xs))
                Text("角色", style = T.label, color = Muted)
                Spacer(Modifier.weight(1f))
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
                            text = { Text("更改头像", style = T.bodySm, color = if (target != null) Paper else Muted) },
                            enabled = target != null,
                            onClick = {
                                menuOpen = false
                                picking = true
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("移动到世界观", style = T.bodySm, color = if (canMove) Paper else Muted) },
                            enabled = canMove,
                            onClick = {
                                menuOpen = false
                                moving = true
                            },
                        )
                        DropdownMenuItem(
                            text = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        "附属（不计分摊）",
                                        style = T.bodySm,
                                        color = if (role.pet) Accent else Paper,
                                    )
                                    if (role.pet) {
                                        Spacer(Modifier.width(8.dp))
                                        GIcon(GI.Check, Accent, size = 14.dp, strokeWidth = 2f)
                                    }
                                }
                            },
                            onClick = {
                                menuOpen = false
                                if (!tax.setPet(role.id, !role.pet)) toast(ctx, "保存失败，检查库目录是否可写")
                                onChanged()
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

        Row(
            Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(AccentC)
                    .clickable(enabled = !selecting && target != null) { picking = true },
                contentAlignment = Alignment.Center,
            ) {
                val avatar = tax.avatarFile(role)
                if (avatar != null) {
                    AsyncImage(
                        model = avatar,
                        contentDescription = role.name,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Text(role.name.take(1), style = T.title, color = Accent)
                }
            }
            Spacer(Modifier.width(Space.m))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        role.name,
                        style = T.title,
                        color = Paper,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (role.pet) {
                        Spacer(Modifier.width(Space.xs))
                        Text(
                            petLabel,
                            style = T.micro,
                            color = Accent,
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .background(AccentC)
                                .padding(horizontal = 7.dp, vertical = 2.dp),
                        )
                    }
                }
                Spacer(Modifier.height(2.dp))
                // 这里显示的是人均分摊值（口径见文件末尾 shareFor，宝可梦不计入分母）。
                // 注意：没挂角色的稿不摊给任何人，所以全库角色合计之和 ≠ 账本总支出。
                Text(
                    listOfNotNull(
                        tax.worldName(role.worldId) ?: "未归入世界观",
                        "${roleArts.size} 件" + if (showExtra && extra.isNotEmpty()) "（含 ${extra.size} 件二次创作）" else "",
                        if (spend > 0) "合计 ¥$spend" else null,
                    ).joinToString("  ·  "),
                    style = T.label,
                    color = Muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        if (tags.isNotEmpty() || extra.isNotEmpty()) {
            LazyRow(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp, bottom = 10.dp)
                    .heightIn(min = 38.dp),
                contentPadding = PaddingValues(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (extra.isNotEmpty()) {
                    item {
                        ChipPill("含二次创作（${extra.size}）", selected = showExtra) {
                            if (selecting) return@ChipPill
                            showExtra = !showExtra
                            // 光靠高亮和数字变化太容易被忽略，明说一句现在有几件
                            toast(
                                ctx,
                                if (showExtra) "含二次创作，共 ${counted.size + extra.size} 件"
                                else "只看普通稿，共 ${counted.size} 件",
                            )
                        }
                    }
                }
                if (tags.isNotEmpty()) {
                    item {
                        ChipPill("全部 ${roleArts.size}", selected = selectedTag == null) {
                            if (!selecting) selectedTag = null
                        }
                    }
                    items(tags.size) { i ->
                        val tag = tags[i]
                        ChipPill(tag, selected = selectedTag == tag) {
                            if (!selecting) selectedTag = if (selectedTag == tag) null else tag
                        }
                    }
                }
            }
        } else {
            Spacer(Modifier.height(10.dp))
        }

        if (shown.isEmpty()) {
            EmptyState(
                title = if (roleArts.isEmpty()) "还没有这个角色的稿件" else "没有带这个标签的稿件",
                sub = if (roleArts.isEmpty()) "在稿件的「角色」字段里选上 ${role.name}，就会出现在这里"
                else "换一个标签，或点「全部」",
                action = if (selectedTag == null) null else "看全部",
                onAction = { selectedTag = null },
            )
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(columns),
                state = gridState,
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = 4.dp,
                    bottom = (if (selecting) 118.dp else 24.dp) + navBottom,
                ),
                horizontalArrangement = Arrangement.spacedBy(Space.s),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .gridDragSelect(items = shown, state = drag),
            ) {
                itemsIndexed(shown, key = { _, a -> a.id }) { _, art ->
                    ArtCard(
                        art = art,
                        lib = lib,
                        selecting = selecting,
                        selected = drag.isSelected(art),
                        onLongClick = null,
                        onClick = {
                            if (selecting) {
                                if (drag.isSelected(art)) drag.removeSelected(art)
                                else drag.addSelected(art)
                            } else {
                                onOpenId(art.id)
                            }
                        },
                    )
                }
            }
        }
    }

    if (selecting) {
        SelectionActions(
            count = selection.size,
            onAssign = { onAssign(selection) },
            onMerge = { onMerge(selection) },
            onDelete = { onDelete(selection) },
            onExport = { onExport(selection) },
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
    }

    if (moving) {
        AlertDialog(
            onDismissRequest = { moving = false },
            containerColor = CardBg,
            shape = RoundedCornerShape(24.dp),
            title = { Text("移动「${role.name}」到", style = T.titleSm, color = Paper) },
            text = {
                Column(
                    Modifier
                        .heightIn(max = 360.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(Space.s),
                ) {
                    tax.worlds.forEach { w ->
                        MoveTargetRow(
                            label = w.name,
                            selected = role.worldId == w.id,
                            onClick = {
                                if (role.worldId != w.id) {
                                    tax.moveRole(role.id, w.id)
                                    onChanged()
                                }
                                moving = false
                            },
                        )
                    }
                    MoveTargetRow(
                        label = "未归入世界观",
                        selected = role.worldId == null,
                        onClick = {
                            if (role.worldId != null) {
                                tax.moveRole(role.id, null)
                                onChanged()
                            }
                            moving = false
                        },
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { moving = false }) {
                    Text("取消", style = T.label, color = Muted)
                }
            },
        )
    }

    if (renaming) {
        TextPromptDialog(
            title = "重命名角色",
            hint = "角色名",
            initial = role.name,
            onDismiss = { renaming = false },
            onConfirm = { name ->
                if (name.isNotBlank()) {
                    tax.renameRole(role.id, name)
                    onChanged()
                }
                renaming = false
            },
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            containerColor = CardBg,
            shape = RoundedCornerShape(24.dp),
            title = { Text("删除角色「${role.name}」？", style = T.titleSm, color = Paper) },
            text = {
                Text(
                    "角色会从所有稿件上移除，头像文件一并删除；稿件本身不会被删。",
                    style = T.bodySm,
                    color = Muted,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    val r = root
                    scope.launch {
                        // 实体 + 稿件引用一次做完：只删实体的话，稿件上的 character_ids 会悬空
                        val n = if (r != null) {
                            OperationJournal.run(r, "entity", "删除角色「${role.name}」") {
                                EntityCommands.deleteRole(r, tax, role.id)
                            }
                        } else {
                            tax.deleteRoleEntity(role.id)
                            0
                        }
                        if (tax.roles.any { it.id == role.id }) {
                            toast(ctx, "删除失败，检查库目录是否可写")
                            return@launch
                        }
                        toast(ctx, if (n > 0) "已删除，$n 件稿件上的角色已移除" else "已删除")
                        onChanged()
                        onClose()
                    }
                }) { Text("删除", style = T.label, color = Warn) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) {
                    Text("取消", style = T.label, color = Muted)
                }
            },
        )
    }
}

@Composable
private fun MoveTargetRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) AccentC else CardBg)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = T.body,
            color = if (selected) Accent else Paper,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (selected) {
            GIcon(GI.Check, Accent, size = 16.dp, strokeWidth = 2f)
        }
    }
}

/** 圆角操作胶囊（角色页、世界观页共用） */
@Composable
internal fun ActionPill(
    text: String,
    icon: GI,
    tint: Color,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(CardBg)
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        GIcon(icon, if (enabled) tint else Muted, size = 14.dp, strokeWidth = 1.7f)
        Text(
            text,
            style = T.label,
            color = if (enabled) OnMid else Muted,
            fontWeight = FontWeight.Medium,
        )
    }
}

internal fun Art.shareFor(roleId: String, petIds: Set<String>): Long {
    val price = priceTotal ?: return 0
    if (roleId !in roleIds) return 0
    val participants = roleIds.filter { it !in petIds }.ifEmpty { roleIds }
    if (participants.isEmpty() || roleId !in participants) return 0
    val per = price / participants.size
    return if (roleId == participants.first()) price - per * (participants.size - 1) else per
}
