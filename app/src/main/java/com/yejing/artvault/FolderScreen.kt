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
import androidx.compose.foundation.layout.size
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dragselectcompose.core.gridDragSelect
import com.dragselectcompose.core.rememberDragSelectState
import com.yejing.artvault.browse.FilterRow
import com.yejing.artvault.browse.SelectionActions
import com.yejing.artvault.ui.component.ArtCard
import com.yejing.artvault.ui.component.EmptyState
import com.yejing.artvault.ui.component.ExportConfirmDialog
import com.yejing.artvault.ui.component.ChipPill
import com.yejing.artvault.ui.component.GI
import com.yejing.artvault.ui.component.GridScrollBar
import com.yejing.artvault.ui.component.RoundIconButton
import com.yejing.artvault.ui.component.ScrollToTopButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 单个归档文件夹的独立页面（角色库 → 文件夹展柜点进来的二级页）。
 *
 * 这页就是「一个文件夹里的稿库」：筛选、排序、2/3 列切换、长按多选（合并 / 赋值 / 删除）
 * 全都和稿库首屏用同一套控件。唯一的区别是**归档这个筛选维度锁死**——这页的稿件本来就
 * 都属于这个文件夹，再让人改归档条件等于把前提改了，筛出来只会是空的。
 *
 * [folderId] 为 null = 「未分类」（`folder_id` 为空），只能浏览，没有改名/删除。
 */
@Composable
fun FolderScreen(
    folderId: String?,
    root: File?,
    folders: FolderBook,
    arts: List<Art>,
    lib: Library,
    tax: Taxonomy,
    names: VaultNames,
    columns: Int,
    /** 这页自己的筛选条件；归档维度由本页强制锁成 [folderId] */
    filter: Filter,
    onFilter: (Filter) -> Unit,
    onOpenFilter: () -> Unit,
    openId: String?,
    onOpenId: (String?) -> Unit,
    onClose: () -> Unit,
    onChanged: () -> Unit,
    onAssign: (List<Art>) -> Unit,
    onMerge: (List<Art>) -> Unit,
    onDelete: (List<Art>) -> Unit,
    onExport: (List<Art>) -> Unit,
    onAddVersion: (String) -> Unit,
    onManageVersions: (String) -> Unit,
    onEdit: (Art) -> Unit,
    onOpenArt: (String) -> Unit = {},
    onAddDerived: (String) -> Unit = {},
    onUnlink: (String) -> Unit = {},
    onPickSource: (String) -> Unit = {},
    onOpenRole: (String) -> Unit = {},
    onOpenArtist: (String) -> Unit = {},
    onFilterTag: (String) -> Unit = {},
    onFilterWorld: (String) -> Unit = {},
    savedScrolls: MutableMap<String, Int> = remember { HashMap() },
    onMoveVersionsToArt: ((String, List<String>) -> Unit)? = null,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    // 和稿库同一套：滑动多选的状态归库管，网格必须用它的 gridState
    // 导出要复制真实文件，先确认一次（和稿库、详情页同一套）
    var confirmExport by remember { mutableStateOf(false) }
    val drag = rememberDragSelectState<Art>(compareSelector = { it.id })
    val gridState = drag.gridState
    val selection = drag.selected
    val selecting = drag.inSelectionMode

    val folder = folderId?.let { folders.byId(it) }
    // 别处删掉了这个文件夹 → 不能停在空页面；换文件夹要清掉上一份选择
    LaunchedEffect(folderId) {
        drag.disableSelectionMode()
        if (folderId != null && folder == null) onClose()
    }

    var menuOpen by remember(folderId) { mutableStateOf(false) }
    var renaming by remember(folderId) { mutableStateOf(false) }
    var confirmDelete by remember(folderId) { mutableStateOf(false) }

    val locked = folderId ?: UNSORTED_FOLDER
    // 系统归档锁定 kind = All（制品/制图住在这里，且万一误塞进普通稿也能看见）
    val lockedKind = if (folder?.system != null) KindFilter.All else null

    val mine = remember(arts, folderId) { arts.filter { it.folderId == folderId } }
    // 筛选 + 排序：和稿库同一条路径（Filter.apply）
    val shown = remember(mine, filter) { filter.apply(mine, names, tax) }
    val priced = remember(shown) { shown.mapNotNull { it.priceTotal } }

    // 排序/筛选一变，LazyGrid 会锚定旧 key 飞到末尾——和稿库一样强制回顶，顺带清掉选择
    LaunchedEffect(filter) {
        gridState.scrollToItem(0)
        drag.disableSelectionMode()
    }
    // 赋值/删除之后列表变了：按 id 对一遍，顺带换成新实例（同稿库）
    LaunchedEffect(shown) { drag.reconcile(shown) }

    val openPos = openId?.let { id -> shown.indexOfFirst { it.id == id } } ?: -1
    if (openPos >= 0 && shown.isNotEmpty()) {
        DetailScreen(
            arts = shown,
            lib = lib,
            tax = tax,
            names = names,
            root = root,
            folders = folders,
            rangeLabel = "文件夹: ${folder?.name ?: "未分类"}",
            startIndex = openPos.coerceIn(0, shown.lastIndex),
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
            onMoveVersionsToArt = onMoveVersionsToArt,
        )
        return
    }

    // 多选时返回键先退选择，再退页面（两个 BackHandler 互斥，避免抢彼此的返回）
    BackHandler(enabled = selecting) { drag.disableSelectionMode() }
    BackHandler(enabled = !selecting) {
        android.util.Log.d("GaoCangBack", "folderList")
        onClose()
    }

    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    // 动作条浮在网格上（不占 Column 一行）：占位会让进出多选时网格高度变化，看起来就是闪一下
    Box(Modifier.fillMaxSize().background(Ink)) {
    Column(Modifier.fillMaxSize()) {
        run {
            // 三层结构，各管一件事：
            // ① 返回 + 标题（标题独占一行，长名省略不换行）
            // ② 统计 + 列切换（两端对齐）
            // ③ 筛选 + 排序 + 改名 + 删除文件夹（一行放完；世界观维度不在这页，走筛选抽屉）
            Row(
                Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 多选态只换这一行的内容，整行高度不变（换掉整个头部会让网格上移）
                if (selecting) {
                    RoundIconButton(GI.Close, onClick = { drag.disableSelectionMode() }, background = Color.Transparent)
                    Spacer(Modifier.width(Space.s))
                    Text("已选 ${selection.size} 件", style = T.title, color = Paper, maxLines = 1)
                    Spacer(Modifier.weight(1f))
                    ChipPill("全选", onClick = { drag.updateSelected(shown) })
                } else {
                    RoundIconButton(GI.Back, onClick = onClose, background = Color.Transparent)
                    Spacer(Modifier.width(Space.s))
                    Text(
                        folder?.name ?: "未分类",
                        style = T.title,
                        color = if (folderId != null && folder == null) Warn else Paper,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (folderId != null && folder?.system == null) {
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
                                    text = { Text("删除文件夹", style = T.bodySm, color = Warn) },
                                    onClick = {
                                        menuOpen = false
                                        confirmDelete = true
                                    },
                                )
                            }
                        }
                    }
                }
            }

            Row(
                // 列切换按钮删掉后没人撑高度了，这里显式给 40dp：
                // 多选态里那个 Box 也是 40dp，两态一样高，进出多选网格才不会上下跳一格。
                Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 16.dp, top = 6.dp)
                    .heightIn(min = 40.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (selecting) {
                    // 多选态把这一行让给模式说明：和稿库同一句话。
                    // 高度锁 40dp（= 非选态那个列切换按钮），进出多选时网格不会上下跳一格。
                    Box(Modifier.height(40.dp), contentAlignment = Alignment.CenterStart) {
                        Text(
                            "多选模式 · 长按后滑动可连续多选",
                            style = T.label,
                            color = Muted,
                            maxLines = 1,
                        )
                    }
                } else {
                    Text(
                        buildList {
                            // 有筛选时先说「筛出几件」，否则容易以为文件夹里只剩这么点
                            if (shown.size != mine.size) add("筛出 ${shown.size} / ${mine.size} 件")
                            else add("${mine.size} 件")
                            if (priced.isNotEmpty()) add("合计 ¥${priced.sum()}")
                        }.joinToString("  ·  "),
                        style = T.label,
                        color = Muted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            // 筛选（抽屉入口）与排序：和稿库同一条 FilterRow，只是不画状态胶囊。
            // 任何改动都强制把归档维度（与系统归档的 kind）按回去，抽屉里也是锁死的。
            FilterRow(
                f = filter.copy(folderId = null, kind = KindFilter.Artwork),
                onOpen = onOpenFilter,
                onChange = { onFilter(it.copy(folderId = locked, kind = lockedKind ?: it.kind)) },
                enabled = !selecting,
            )
        }

        if (shown.isEmpty()) {
            EmptyState(
                title = if (mine.isEmpty()) {
                    if (folderId == null) "没有未分类的稿件" else "这个文件夹还是空的"
                } else "没有符合条件的稿件",
                sub = if (mine.isEmpty()) {
                    if (folderId == null) "所有稿件都分好归档了"
                    else "长按稿库里的稿件 → 赋值 → 归档，选这个文件夹"
                } else "放宽筛选条件试试",
                action = if (mine.isNotEmpty()) "清空筛选" else null,
                onAction = {
                    // 清筛选但保住归档锁、系统归档的 kind 锁和排序
                    onFilter(Filter(folderId = locked, kind = lockedKind ?: KindFilter.Artwork, sort = filter.sort))
                },
            )
        } else {
            Box(Modifier.fillMaxWidth().weight(1f)) {
                LazyVerticalGrid(
                    state = gridState,
                    columns = GridCells.Fixed(columns),
                    contentPadding = PaddingValues(
                        start = 16.dp,
                        end = 16.dp,
                        top = 6.dp,
                        bottom = 24.dp + navBottom,
                    ),
                    horizontalArrangement = Arrangement.spacedBy(Space.s),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    // 长按拖动划选 + 贴边自动滚；items 必须与 itemsIndexed 同序（库按下标映射）
                    modifier = Modifier
                        .fillMaxSize()
                        .gridDragSelect(items = shown, state = drag),
                ) {
                    itemsIndexed(shown, key = { _, a -> a.id }) { _, art ->
                        ArtCard(
                            art = art,
                            lib = lib,
                            selecting = selecting,
                            selected = drag.isSelected(art),
                            // 长按留给库（传 {} 会和库抢超时，拖选失效）；
                            // 长按抬手被当成点击的问题由 ArtCard 内部吞 up 解决
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
                // 和稿库同一根条：两处不一致会让人以为是 bug。声明在回顶按钮之前（压在它下面）。
                // 这页是全屏覆盖层、没有底部导航面板，只需躲开系统导航栏。
                GridScrollBar(
                    state = gridState,
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(bottom = navBottom + 8.dp),
                )
                ScrollToTopButton(
                    visible = gridState.firstVisibleItemIndex > columns * 4,
                    onClick = { scope.launch { gridState.animateScrollToItem(0) } },
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 20.dp, bottom = 24.dp + navBottom),
                )
            }
        }

    }

    // 多选时的动作条：和稿库同一套（合并 / 赋值 / 导出 / 删除），浮层不占位
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

    if (renaming && folderId != null) {
        TextPromptDialog(
            title = "重命名文件夹",
            hint = "分类名（就是导出时的文件夹名）",
            initial = folder?.name.orEmpty(),
            onDismiss = { renaming = false },
            onConfirm = { name ->
                renaming = false
                if (!folders.rename(folderId, name)) toast(ctx, "改名失败：名字重复或写盘失败")
                onChanged()
            },
        )
    }

    if (confirmDelete && folder != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            containerColor = CardBg,
            shape = RoundedCornerShape(24.dp),
            title = { Text("删除文件夹「${folder.name}」？", style = T.titleSm, color = Paper) },
            text = {
                Text(
                    "稿件不会被删除，只是变成「未分类」，导出时会落到未分类目录里。",
                    style = T.bodySm,
                    color = Muted,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    scope.launch {
                        // 实体 + 稿件引用一次做完（见 EntityCommands）
                        val n = if (root != null) {
                            OperationJournal.run(root, "entity", "删除文件夹「${folder.name}」") {
                                EntityCommands.deleteFolder(root, folders, folder.id)
                            }
                        } else {
                            folders.delete(folder.id)
                            0
                        }
                        toast(ctx, if (root == null) "已删除" else "已删除，$n 件回到未分类")
                        onChanged()
                        onClose()
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
}
