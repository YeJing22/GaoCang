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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import sh.calvin.reorderable.ReorderableItem
import coil.compose.AsyncImage
import kotlinx.coroutines.launch
import java.io.File
import com.yejing.artvault.ui.component.EmptyState
import com.yejing.artvault.ui.component.GI
import com.yejing.artvault.ui.component.SheetGrip
import com.yejing.artvault.ui.component.RoundIconButton

/**
 * 单个世界观的独立页面（世界观展柜卡点进来的二级页）。
 *
 * 为什么要有这一页：世界观板块一级只放展柜卡，如果把「＋角色 / 改名」塞在卡片右上角，
 * 标题会被那行按钮挤下一截，一屏能放的世界观也变少。角色库的定位是「浏览」，
 * 管理动作收进这里，和画师反查（统计 → 点画师 → 画师页）是同一套分层。
 *
 * [worldId] 为 null = 「未归入世界观」这个虚拟分组：只能加角色，没有改名/删除。
 */
@Composable
fun WorldScreen(
    worldId: String?,
    root: File?,
    arts: List<Art>,
    tax: Taxonomy,
    onOpenRole: (String) -> Unit,
    onClose: () -> Unit,
    onChanged: () -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val world = worldId?.let { id -> tax.worlds.firstOrNull { it.id == id } }
    // 别处删掉了这个世界观（或同步过来时没了）→ 不能停在一个空页面
    LaunchedEffect(worldId) { if (worldId != null && world == null) onClose() }

    var menuOpen by remember(worldId) { mutableStateOf(false) }
    var adding by remember(worldId) { mutableStateOf(false) }
    var renaming by remember(worldId) { mutableStateOf(false) }
    var confirmDelete by remember(worldId) { mutableStateOf(false) }
    /** 角色排序弹窗：只在角色 > 1 时给入口 */
    var ordering by remember(worldId) { mutableStateOf(false) }

    val roles = remember(tax.roles, worldId) { tax.rolesOf(worldId) }
    val counted = remember(arts) { arts.countedOnly() }
    val works = remember(counted, worldId) { counted.count { it.worldId == worldId } }
    // 每个角色名下的稿件数（只算普通稿）。
    // 世界观这一层仍然只数件数；角色页才展示人均分摊后的金额合计（见 RoleScreen.shareFor）。
    val counts = remember(counted) {
        val m = HashMap<String, Int>()
        counted.forEach { a -> a.roleIds.forEach { id -> m[id] = (m[id] ?: 0) + 1 } }
        m
    }

    BackHandler { onClose() }

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
            Text("世界观", style = T.label, color = Muted)
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
                        text = { Text("新建角色", style = T.bodySm, color = Paper) },
                        onClick = {
                            menuOpen = false
                            adding = true
                        },
                    )
                    if (roles.size > 1) {
                        DropdownMenuItem(
                            text = { Text("排序角色", style = T.bodySm, color = Paper) },
                            onClick = {
                                menuOpen = false
                                ordering = true
                            },
                        )
                    }
                    if (worldId != null) {
                        DropdownMenuItem(
                            text = { Text("重命名", style = T.bodySm, color = Paper) },
                            onClick = {
                                menuOpen = false
                                renaming = true
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("删除世界观", style = T.bodySm, color = Warn) },
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
                world?.name ?: "未归入世界观",
                style = T.display,
                color = if (worldId != null && world == null) Warn else Paper,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(Space.s))
            Text(
                "${roles.size} 个角色 · $works 件",
                style = T.label,
                color = Muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        if (roles.isEmpty()) {
            Column(Modifier.fillMaxWidth().weight(1f)) {
                EmptyState(
                    title = if (worldId == null) "没有未归入世界观的角色" else "这个世界观还没有角色",
                    sub = "点右上角菜单里的「新建角色」加一个，之后在稿件的「角色」里就能选到",
                )
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 78.dp),
                contentPadding = PaddingValues(
                    start = 20.dp,
                    end = 20.dp,
                    top = 18.dp,
                    bottom = 24.dp + navBottom,
                ),
                horizontalArrangement = Arrangement.spacedBy(Space.m),
                verticalArrangement = Arrangement.spacedBy(Space.l),
                modifier = Modifier.fillMaxWidth().weight(1f),
            ) {
                items(roles, key = { it.id }) { r ->
                    RoleTile(
                        role = r,
                        avatar = tax.avatarFile(r),
                        count = counts[r.id] ?: 0,
                        onClick = { onOpenRole(r.id) },
                    )
                }
            }
        }
    }

    if (adding) {
        TextPromptDialog(
            title = if (worldId == null) "新建角色（未归入世界观）" else "在「${world?.name}」下新建角色",
            hint = "角色名",
            onDismiss = { adding = false },
            onConfirm = { name ->
                tax.addRole(name, worldId)
                adding = false
                onChanged()
            },
        )
    }

    if (renaming) {
        TextPromptDialog(
            title = "重命名世界观",
            hint = "世界观名",
            initial = world?.name.orEmpty(),
            onDismiss = { renaming = false },
            onConfirm = { name ->
                if (name.isNotBlank() && worldId != null) {
                    tax.renameWorld(worldId, name)
                    onChanged()
                }
                renaming = false
            },
        )
    }

    if (confirmDelete && world != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            containerColor = CardBg,
            shape = RoundedCornerShape(24.dp),
            title = { Text("删除世界观「${world.name}」？", style = T.titleSm, color = Paper) },
            text = {
                Text(
                    if (roles.isEmpty()) {
                        "稿件不会被删除；它们的「世界观」字段会被清空（变成未归类）。"
                    } else {
                        "下面 ${roles.size} 个角色会变成「未归入世界观」，稿件本身不删，" +
                            "但这些稿件的「世界观」字段会被清空（变成未归类）。"
                    },
                    style = T.bodySm,
                    color = Muted,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    val r = root
                    android.util.Log.d(
                        "GaoCangTax",
                        "deleteWorld click id=${world.id} name=${world.name} root=${r?.absolutePath ?: "NULL"}",
                    )
                    scope.launch {
                        // 实体 + 稿件引用一次做完：只删实体的话，稿件上的 world_id 会悬空
                        try {
                            val n = if (r != null) {
                                OperationJournal.run(r, "entity", "删除世界观「${world.name}」") {
                                    EntityCommands.deleteWorld(r, tax, world.id)
                                }
                            } else {
                                tax.deleteWorldEntity(world.id)
                                0
                            }
                            android.util.Log.d("GaoCangTax", "deleteWorld done n=$n")
                            toast(ctx, if (n > 0) "已删除，$n 件稿件变成未归类" else "已删除")
                            onChanged()
                            onClose()
                        } catch (t: Throwable) {
                            // 事务失败会回滚文件 —— 那正是「删了又回来」的样子，必须让人看见
                            android.util.Log.e("GaoCangTax", "deleteWorld failed, rolled back", t)
                            toast(ctx, "删除失败：${t.message ?: t.javaClass.simpleName}")
                        }
                    }
                }) { Text("删除", style = T.label, color = Warn, fontWeight = FontWeight.Medium) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) {
                    Text("取消", style = T.label, color = Muted)
                }
            },
        )
    }

    if (ordering) {
        RoleOrderSheet(
            title = world?.name ?: "未归入世界观",
            roles = roles,
            counts = counts,
            tax = tax,
            onDismiss = { ordering = false },
            onSave = { ids ->
                tax.reorderRolesWithin(worldId, ids)
                ordering = false
                onChanged()
            },
        )
    }
}

/** 世界观页里的角色格：圆形头像 + 名字 + 件数，点进去是这个角色的独立页 */
@Composable
private fun RoleTile(
    role: Role,
    avatar: java.io.File?,
    count: Int,
    onClick: () -> Unit,
) {
    Column(
        Modifier.clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(CircleShape)
                .background(AccentC),
            contentAlignment = Alignment.Center,
        ) {
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
        Spacer(Modifier.height(Space.xs))
        Text(
            role.name,
            style = T.bodySm,
            color = Paper,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(2.dp))
        Text(
            if (count > 0) "$count 件" else "暂无稿件",
            style = T.micro,
            color = Muted,
            maxLines = 1,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * 角色排序弹窗（R5）。
 *
 * ## 为什么是弹窗，而不是「在网格里长按拖」
 *
 * 一级页的角色格只有 78dp 宽，长按拖会和「点进角色页」抢同一个手势；
 * 而排序是低频动作，进弹窗、拖、点完成，三步收尾干净，浏览态也不必多背一种手势。
 *
 * ## 为什么拖完不立刻落盘
 *
 * 顺序一旦写进 taxonomy.json 就会影响每一处按世界观分组的视图，
 * 误拖一次就得重新拖回来。所以沿用角色段原来的规矩：**点「完成」才写，关掉等于放弃**。
 *
 * ## 拖动用的是同一套库
 *
 * `rememberListReorderState`（见 Reorder.kt），和版本管理页共用，
 * 自动滚动的速度与频率都在那边统一调过，这里不要另调一套。
 */
internal data class OrderSheetItem(
    val id: String,
    val name: String,
    val sub: String,
    val avatar: File? = null,
)

@Composable
private fun RoleOrderSheet(
    title: String,
    roles: List<Role>,
    counts: Map<String, Int>,
    tax: Taxonomy,
    onDismiss: () -> Unit,
    /** 传回这一组的新顺序（只含本世界的角色） */
    onSave: (List<String>) -> Unit,
) {
    val items = remember(roles, counts, tax) {
        roles.map { r -> OrderSheetItem(r.id, r.name, "${counts[r.id] ?: 0} 件", tax.avatarFile(r)) }
    }
    EntityOrderSheet(
        title = "排序 · $title",
        subtitle = "按住任意一行拖动。这个顺序只影响本世界观内的角色先后。",
        items = items,
        showAvatar = true,
        onDismiss = onDismiss,
        onSave = onSave,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun EntityOrderSheet(
    title: String,
    subtitle: String,
    items: List<OrderSheetItem>,
    showAvatar: Boolean = true,
    onDismiss: () -> Unit,
    onSave: (List<String>) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val listState = rememberLazyListState()
    val original = remember(items) { items.map { it.id } }
    var order by remember(items) { mutableStateOf(original) }
    val byId = remember(items) { items.associateBy { it.id } }
    val ordered = order.mapNotNull { byId[it] }
    val reorderState = rememberListReorderState(listState, order) { order = it }
    val dirty = order != original

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = CardBg,
        dragHandle = { SheetGrip() },
    ) {
        Column(Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
            Text(
                title,
                style = T.titleSm,
                color = Paper,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
            Spacer(Modifier.height(3.dp))
            Text(
                subtitle,
                style = T.micro,
                color = Muted,
                modifier = Modifier.padding(horizontal = 20.dp),
            )

            Spacer(Modifier.height(10.dp))

            LazyColumn(
                state = listState,
                // 弹窗里给它一个上限、而不是 weight：条目少时不该撑出一大块空白
                modifier = Modifier.fillMaxWidth().heightIn(max = 430.dp),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
            ) {
                itemsIndexed(ordered, key = { _, it -> it.id }) { _, item ->
                    ReorderableItem(reorderState, key = item.id) { isDragging ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .height(64.dp)
                                // 被拖的那行跟着手指走（库自己 translate），这里只补抬起来的观感
                                .graphicsLayer {
                                    shadowElevation = if (isDragging) 14f else 0f
                                    scaleX = if (isDragging) 1.02f else 1f
                                    scaleY = if (isDragging) 1.02f else 1f
                                }
                                .clip(RoundedCornerShape(13.dp))
                                .background(if (isDragging) CHigh else Color.Transparent)
                                // 整行都能起拖：弹窗里没有「点进去」的需求，不必只留一个小手柄
                                .longPressDraggableHandle()
                                .padding(horizontal = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (showAvatar) {
                                Box(
                                    Modifier
                                        .size(40.dp)
                                        .clip(CircleShape)
                                        .background(AccentC),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    if (item.avatar != null) {
                                        AsyncImage(
                                            model = item.avatar,
                                            contentDescription = item.name,
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier.fillMaxSize(),
                                        )
                                    } else {
                                        Text(item.name.take(1), style = T.label, color = Accent)
                                    }
                                }
                                Spacer(Modifier.width(Space.m))
                            } else {
                                Box(
                                    Modifier
                                        .width(3.dp)
                                        .height(16.dp)
                                        .clip(RoundedCornerShape(1.5.dp))
                                        .background(Accent),
                                )
                                Spacer(Modifier.width(Space.s))
                            }
                            Text(
                                item.name,
                                style = T.body,
                                color = Paper,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            Text(item.sub, style = T.micro, color = Muted)
                            Spacer(Modifier.width(Space.m))
                            DragHandle()
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(Space.s),
            ) {
                Box(
                    Modifier
                        .weight(1f)
                        .height(46.dp)
                        .clip(RoundedCornerShape(13.dp))
                        .background(CHigh)
                        .clickable(onClick = onDismiss),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("取消", style = T.bodySm, color = OnMid)
                }
                Box(
                    Modifier
                        .weight(1f)
                        .height(46.dp)
                        .clip(RoundedCornerShape(13.dp))
                        .background(if (dirty) Accent else CHigh)
                        .clickable(enabled = dirty) { onSave(order) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "完成",
                        style = T.bodySm,
                        color = if (dirty) OnAccent else Muted,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }
}

/** 拖动手柄：三条短横线。只是提示「这里能拖」，手势挂在整行上。 */
@Composable
private fun DragHandle() {
    Column(
        Modifier.width(16.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        repeat(3) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(1.5.dp)
                    .clip(RoundedCornerShape(1.dp))
                    .background(Muted),
            )
        }
    }
}
