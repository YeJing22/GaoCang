package com.yejing.artvault.browse

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yejing.artvault.Space
import com.yejing.artvault.Accent
import com.yejing.artvault.AccentC
import com.yejing.artvault.Amber
import com.yejing.artvault.Art
import com.yejing.artvault.ArtKind
import com.yejing.artvault.CHigh
import com.yejing.artvault.CardBg
import com.yejing.artvault.Dates
import com.yejing.artvault.KindFilter
import com.yejing.artvault.OutlineSoft
import com.yejing.artvault.Muted
import com.yejing.artvault.Filter
import com.yejing.artvault.Gap
import com.yejing.artvault.gapCount
import com.yejing.artvault.Ink
import com.yejing.artvault.Library
import com.yejing.artvault.Lilac
import com.yejing.artvault.Mint
import com.yejing.artvault.OnAccent
import com.yejing.artvault.OnMid
import com.yejing.artvault.Paper
import com.yejing.artvault.Sort
import com.yejing.artvault.SortDir
import com.yejing.artvault.SortKey
import com.yejing.artvault.T
import com.yejing.artvault.Taxonomy
import com.yejing.artvault.WarnC
import com.yejing.artvault.toast
import com.yejing.artvault.ui.component.ArtCard
import com.yejing.artvault.ui.component.ChipPill
import com.yejing.artvault.ui.component.EmptyState
import com.yejing.artvault.ui.component.ExportConfirmDialog
import com.yejing.artvault.ui.component.GI
import com.yejing.artvault.ui.component.GIcon
import com.yejing.artvault.ui.component.GaoActionBar
import com.yejing.artvault.ui.component.ListScrollBar
import com.yejing.artvault.ui.component.RoundIconButton
import com.yejing.artvault.ui.component.ScrollToTopButton
import kotlinx.coroutines.launch

/**
 * 稿库首屏：大标题 + 筛选条固定，网格独立滚动。
 * 网格固定 3 列（2 列那档已删）。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun BrowseScreen(
    arts: List<Art>,
    shown: List<Art>,
    lib: Library,
    tax: Taxonomy,
    demo: Boolean,
    hasPerm: Boolean,
    filter: Filter,
    columns: Int,
    onFilter: (Filter) -> Unit,
    onOpenFilter: () -> Unit,
    /** 点搜索框：直接进全屏搜索态（输入法立刻弹），不再借道筛选面板 */
    onOpenSearch: () -> Unit,
    onGrant: () -> Unit,
    onPickRoot: () -> Unit,
    onOpen: (Int) -> Unit,
    onAssign: (List<Art>) -> Unit,
    onMerge: (List<Art>) -> Unit,
    onDelete: (List<Art>) -> Unit,
    /** 多选导出：只往「相册」目录复制副本，不动库数据 */
    onExport: (List<Art>) -> Unit,
    onSelectingChange: (Boolean) -> Unit,
    /**
     * 选稿模式（取字套用 / 关联原稿 / 转为制品）：网格里点哪张就是选哪张。
     *
     * 提示条画在**流式布局里**（标题下方），不做悬浮层 —— 以前是 `align(TopCenter)` 的浮层，
     * 直接盖在「稿库」大标题上，两个文字叠在一起（2026-11-22 用户报的）。
     * 选稿是短暂状态，把内容整体下移一条横幅的代价可以接受。
     */
    pickHint: String? = null,
    onCancelPick: () -> Unit = {},
    /** 最近一次整理完毕（不再命中待整理缺口）的稿件 id；用于快速定位 */
    lastCleanedId: String? = null,
) {
    // 滑动多选的状态归库管：网格必须用它的 gridState，否则库算命中/自动滚时用的是另一份滚动位置。
    // compareSelector 用 id：Art 是 data class，但每次重载都是新实例，靠 equals 也能过；
    // 用 id 是为了「刷新后仍认得同一件」这件事与字段变化解耦。
    val ctx = LocalContext.current
    // 导出要复制真实文件，先确认一次（多选一次可能几百件）
    var confirmExport by remember { mutableStateOf(false) }
    // 稿库用 LazyColumn：月标头要冻结，而官方 stickyHeader 只支持列表不支持网格。
    // 选择态见下方 Selection（拖动连选由 listDragSelect 提供）。
    val sel = remember { Selection() }
    val listState = rememberLazyListState()
    // 手指还按着在拖选时为 true：这段时间滚动条要被禁掉。
    // 否则第二根手指把列表滚走、拖选那根手指的位置没变，选区就停在原处，看起来是「滚了但没多选」。
    // 用 State 对象而不是 Boolean：拖选时主线程忙，重组会推迟，Boolean 传下去会慢一拍
    val dragActive = remember { mutableStateOf(false) }
    // 滚动条的「可用」是「不在拖选」——之前直接把 dragActive 传下去，语义整个反了：
    // 拖选时滚动条反而能用、松手后反而锁死。这里显式取反，别再靠脑子记。
    val scrollBarEnabled = remember { androidx.compose.runtime.derivedStateOf { !dragActive.value } }
    val scope = rememberCoroutineScope()
    val selecting = sel.active

    // 头部/胶囊/版本数是「当前列表范围」的口径，必须跟着类型筛选走
    val scopeArts = remember(arts, filter.kind) {
        when (filter.kind) {
            KindFilter.Artwork -> arts.filter { it.kind == ArtKind.Artwork }
            KindFilter.Derived -> arts.filter { it.kind == ArtKind.Derived }
            KindFilter.Fill -> arts.filter { it.kind == ArtKind.Fill }
            KindFilter.All -> arts
        }
    }
    // 「待整理」胶囊上的数字：当前类型范围的口径（制品/制图天然为 0）
    val gapTotal = remember(scopeArts) { scopeArts.gapCount() }
    // 版本总数与世界观数：筛选开启时实时反映筛选结果（shown），未筛选时保持全库口径
    val versionTotal = remember(shown) { shown.sumOf { it.versions.size } }
    val worldCount = remember(shown, filter.isEmpty, tax.worlds) {
        if (filter.isEmpty) tax.worlds.size else shown.mapNotNull { it.worldId }.toSet().size
    }

    LaunchedEffect(selecting) { onSelectingChange(selecting) }
    // 退出多选时一定要把「正在拖」的标记放掉，否则滚动条会一直是禁用态
    LaunchedEffect(selecting) { if (!selecting) dragActive.value = false }
    // 页面被销毁时一定要把「多选」状态报回去，否则底栏和 FAB 会一直藏着
    DisposableEffect(Unit) { onDispose { onSelectingChange(false) } }
    BackHandler(enabled = selecting) { sel.clear() }

    // 写了盘回来（合并/赋值/删除）后，选择集里可能已经有不存在的条目。
    // 库自带 reconcile：按 compareSelector 对上，并顺手换成新实例（否则详情页拿到的是旧数据）。
    LaunchedEffect(shown) { sel.reconcile(shown) }

    // P1-4 量测：每次列表内容变化都记一下当前锚点，用来判断「写入后跳回顶部」是哪一种成因
    LaunchedEffect(shown) {
        android.util.Log.d(
            "GaoCangPerf",
            "shown size=${shown.size} first=${listState.firstVisibleItemIndex} off=${listState.firstVisibleItemScrollOffset} selecting=${selecting}",
        )
    }

    // 排序/筛选一变，LazyGrid 会锚定旧 key 导致视图飞到末尾，这里强制回顶。
    // 同时清空选择，否则会选中一堆看不见的稿子。
    var searchBarExpanded by remember { mutableStateOf(false) }
    var pendingJumpId by remember { mutableStateOf<String?>(null) }
    val months = remember(shown) { buildMonths(shown) }
    val allRows = remember(months) { months.flatMap { m -> m.arts.chunked(3) } }
    val hasCleaned = remember(lastCleanedId, arts) { lastCleanedId != null && arts.any { it.id == lastCleanedId } }
    val cleanedRowIdx = remember(lastCleanedId, allRows) {
        lastCleanedId?.let { id -> allRows.indexOfFirst { row -> row.any { it.id == id } } }?.takeIf { it >= 0 }
    }
    LaunchedEffect(filter) {
        if (pendingJumpId == null) listState.scrollToItem(0)
        sel.clear()
    }
    LaunchedEffect(pendingJumpId, allRows) {
        val target = pendingJumpId ?: return@LaunchedEffect
        val idx = allRows.indexOfFirst { row -> row.any { it.id == target } }
        if (idx >= 0) {
            listState.scrollToItem(idx)
            pendingJumpId = null
        }
    }

    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    // 动作条浮在网格上方（而不是占 Column 的一行）：
    // 占位会让网格在进出多选时高度变化 30dp 上下，加上底栏同时出现/消失，看起来就是「闪一下」。
    Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize()) {
        Header(
            total = shown.size,
            versionTotal = versionTotal,
            worldCount = worldCount,
            demo = demo,
            hasPerm = hasPerm,
            onGrant = onGrant,
            onPickRoot = onPickRoot,
            selecting = selecting,
            selectedCount = sel.count,
            onExitSelection = { sel.clear() },
            onSelectAll = { sel.setAll(shown) },
            filterCount = filter.condCount,
            searchBarExpanded = searchBarExpanded,
            onToggleSearchBar = { searchBarExpanded = !searchBarExpanded },
        )
        if (pickHint != null && !selecting) {
            PickBanner(pickHint, onCancelPick)
        }
        // 搜索与筛选行默认收起，减轻顶部视觉负担；点击顶栏右侧胶囊即可展开/收起
        AnimatedVisibility(visible = searchBarExpanded) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(Space.s),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .weight(1f)
                        .height(40.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(CardBg)
                        .clickable(enabled = !selecting, onClick = onOpenSearch),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Row(
                        Modifier
                            .fillMaxSize()
                            .padding(start = 12.dp, end = if (filter.condCount > 0) 6.dp else 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "搜索 标题 / 画师 / 角色",
                            style = T.bodySm,
                            color = Muted,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (filter.condCount > 0) {
                            Text(
                                "筛 ${filter.condCount} 项 ×",
                                style = T.micro,
                                color = Accent,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(50))
                                    .background(AccentC)
                                    .clickable(enabled = !selecting) {
                                        onFilter(Filter(sort = filter.sort))
                                    }
                                    .padding(horizontal = 8.dp, vertical = 4.dp),
                            )
                        }
                    }
                }
                // 带文字的筛选按钮：图标 + 「筛选」。只有图标时，用户得先猜它是干嘛的。
                Row(
                    Modifier
                        .height(40.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (filter.condCount > 0) AccentC else CardBg)
                        .clickable(enabled = !selecting, onClick = onOpenFilter)
                        .padding(horizontal = 11.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Space.xs),
                ) {
                    GIcon(GI.Tune, if (filter.condCount > 0) Accent else OnMid, size = 16.dp)
                    Text(
                        "筛选",
                        style = T.label,
                        color = if (filter.condCount > 0) Accent else OnMid,
                        maxLines = 1,
                    )
                }
            }
        }

        // 多选态锁死筛选/排序：改条件会让「已选的」和「看得见的」脱节
        FilterRow(
            f = filter,
            onOpen = onOpenFilter,
            onChange = onFilter,
            enabled = !selecting,
            // 筛选入口已经移到上面搜索框右侧，这一行只留状态胶囊 + 排序
            showTune = false,
            showStatusChips = true,
            totalCount = scopeArts.size,
            gapCount = gapTotal,
            onJumpLastCleaned = if (hasCleaned) {
                {
                    val idx = cleanedRowIdx
                    if (idx != null) {
                        scope.launch { listState.scrollToItem(idx) }
                    } else {
                        pendingJumpId = lastCleanedId
                        onFilter(Filter(sort = filter.sort))
                    }
                }
            } else null,
        )

        if (shown.isEmpty()) {
            EmptyState(
                title = if (filter.isEmpty) "这个库还是空的" else "没有符合条件的稿件",
                sub = if (filter.isEmpty) "从相册选几张图，建档后就会出现在这里" else "放宽筛选条件试试",
                action = if (filter.isEmpty) null else "清空筛选",
                onAction = { onFilter(Filter(sort = filter.sort)) },
            )
        } else {
            val indexById = remember(shown) { shown.withIndex().associate { it.value.id to it.index } }

            // 拖选的索引空间：所有行拉平，flat = row * 3 + col（跨月份连续）
            val rowKeyToIndex = remember(allRows) {
                allRows.withIndex().associate { "r:${it.value.first().id}" to it.index }
            }
            val idToFlat = remember(allRows) {
                val m = HashMap<String, Int>(allRows.size * 3)
                allRows.forEachIndexed { r, row -> row.forEachIndexed { c, a -> m[a.id] = r * 3 + c } }
                m
            }
            val artAtFlat: (Int) -> Art? = { flat -> allRows.getOrNull(flat / 3)?.getOrNull(flat % 3) }

            // 每行属于哪个月。列表里**不再有标头 item**（冻结标头改成浮层原地替换），
            // 所以列表下标 = 行号，这张表的长度就等于行数，气泡与冻结标头都查它。
            val rowMonth = remember(months) {
                val out = ArrayList<MonthBlock>(months.size * 4)
                months.forEach { m -> repeat((m.arts.size + 2) / 3) { out += m } }
                out
            }
            val rowMonthIdx by remember(rowMonth) {
                androidx.compose.runtime.derivedStateOf {
                    val last = rowMonth.size - 1
                    if (last < 0) -1 else listState.firstVisibleItemIndex.coerceIn(0, last)
                }
            }

            // 命中换算要的两个像素值（LazyListItemInfo 只给高度，宽度得自己算）
            val density = androidx.compose.ui.platform.LocalDensity.current
            val screenW = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp.dp
            val padLeftPx = with(density) { 16.dp.toPx() }
            val cellWidthPx = with(density) { ((screenW - 32.dp - 24.dp) / 3f).toPx() }

            Box(Modifier.fillMaxWidth().weight(1f)) {
                LazyColumn(
                    state = listState,
                    // 顶部不再留白：每个月的标题是真实 item，第一个月那份就压在浮层底下
                    // （两者位置、字号完全一致，滚起来是无缝的），多余留白反而让首屏少一行卡
                    contentPadding = PaddingValues(
                        start = 16.dp,
                        end = 16.dp,
                        top = 0.dp,
                        bottom = 118.dp + navBottom,
                    ),
                    modifier = Modifier
                        .fillMaxSize()
                        .listDragSelect(
                            state = listState,
                            cellCount = 3,
                            isRowKey = { k -> (k as? String)?.startsWith("r:") == true },
                            rowOfKey = { k -> rowKeyToIndex[k as? String] },
                            rowCountOf = { allRows.size },
                            padLeftPx = padLeftPx,
                            cellWidthPx = cellWidthPx,
                            onStart = { flat ->
                                dragActive.value = true
                                android.util.Log.d("GaoCangGesture", "dragStart")
                                sel.beginDrag(flat) { artAtFlat(it) }
                            },
                            onUpdate = { flat -> sel.dragTo(flat, { artAtFlat(it) }, { id -> idToFlat[id] }) },
                            onStop = {
                                dragActive.value = false
                                android.util.Log.d("GaoCangGesture", "dragStop")
                                sel.endDrag()
                            },
                        ),
                ) {
                    months.forEach { m ->
                        itemsIndexed(
                            m.arts.chunked(3),
                            key = { _, row -> "r:${row.first().id}" },
                        ) { ci, row ->
                          Column {
                            // 每月开头一条真实标题（自带分割线）。
                            // 之前只有一条细线、月份文字全靠顶部浮层，滚到月中就完全看不到自己在哪个月。
                            // 现在标题跟着内容一起滚，滚进顶部 44dp 区时被浮层整块盖住——
                            // 位置与字号一致，所以看上去仍是「文字原地替换」，不会出现两个月份。
                            if (ci == 0) MonthTitleSlot(m.label, m.count, m.spend)
                            Row(
                                Modifier.fillMaxWidth().padding(bottom = 16.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                row.forEach { a ->
                                    Box(Modifier.weight(1f)) {
                                        ArtCard(
                                            art = a,
                                            lib = lib,
                                            selecting = selecting,
                                            selected = sel.selectedFlag(a.id).value,
                                            modifier = Modifier.fillMaxWidth(),
                                            // 长按交给 listDragSelect（它用 detectDragGesturesAfterLongPress 独占），
                                            // 这里必须 null，否则两边抢 500ms 超时。
                                            onLongClick = null,
                                            onClick = {
                                                if (selecting) sel.toggle(a) else indexById[a.id]?.let(onOpen)
                                            },
                                        )
                                        if (a.id == lastCleanedId && !selecting) {
                                            Text(
                                                "上次整理",
                                                style = T.micro,
                                                color = OnAccent,
                                                fontWeight = FontWeight.Medium,
                                                modifier = Modifier
                                                    .align(Alignment.TopStart)
                                                    .padding(5.dp)
                                                    .clip(RoundedCornerShape(50))
                                                    .background(Accent.copy(alpha = 0.92f))
                                                    .padding(horizontal = 6.dp, vertical = 2.dp),
                                            )
                                        }
                                    }
                                }
                                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                            }
                          }
                        }
                    }
                }

                // 冻结月标头：不推、不位移，跨月时文字原地淡入替换（华为相册的做法）。
                // 内容滚到 0..44 这一段会被它整块盖住，所以不需要再算「顶出位移」。
                rowMonth.getOrNull(rowMonthIdx)?.let { MonthHeaderOverlay(it.label, it.count, it.spend) }

                // 右缘滚动条：扣掉底栏高度，否则拖到底时 thumb 会沉到导航面板后面。
                // 先声明 = 画在回顶按钮下面（不会被那颗圆钮挡住）。
                ListScrollBar(
                    state = listState,
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(bottom = 96.dp + navBottom),
                    bubbleText = { idx -> rowMonth.getOrNull(idx)?.label.orEmpty() },
                    enabledState = scrollBarEnabled,
                )
                ScrollToTopButton(
                    visible = listState.firstVisibleItemIndex > 3,
                    // 直接跳，不是模拟滑回去：两千件 animate 会滚好几秒、一路丢帧。
                    // 原生相册也是瞬时归位，不做缓动。
                    onClick = { scope.launch { listState.scrollToItem(0) } },
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 20.dp, bottom = 108.dp + navBottom),
                )
            }
        }

    }

    if (confirmExport) {
        ExportConfirmDialog(
            title = "导出这 ${sel.count} 件？",
            onCancel = { confirmExport = false },
            onConfirm = {
                confirmExport = false
                onExport(sel.items)
            },
        )
    }

    // 这里曾经放过一个「覆盖全列表、拖选时吞掉新手指」的拦截层。已删除：
    // Compose 的命中测试里，同层兄弟只有最上面的那个能进入 hit path，
    // 这个 matchParentSize 的 Box 虽然不消费事件，却把整条路径抢走了 ——
    // 列表、按钮、滚动条全部收不到事件，整页失灵。
    // 结论：不能用覆盖层做「只观察不拦截」，跨节点的手势仲裁只能靠状态传递。

    // 多选时底部换成动作条，替代被隐藏的底部导航 + FAB（浮层，不参与列布局）
    if (selecting) {
        SelectionActions(
            count = sel.count,
            onAssign = { onAssign(sel.items) },
            onMerge = { onMerge(sel.items) },
            onDelete = { onDelete(sel.items) },
            onExport = { if (demo) toast(ctx, "演示库不能导出") else confirmExport = true },
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
    }
}

@Composable
internal fun SelectionActions(
    count: Int,
    onAssign: () -> Unit,
    onMerge: () -> Unit,
    onDelete: () -> Unit,
    /** 为空则不显示「导出」（文件夹页没有导出这一项） */
    onExport: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    // 和底部导航共用同一块底板（BottomNav.kt 的 BarShell），所以进出多选时网格不跳。
    // 以前这里自己画渐变 + fixedHeight 补偿，是为了对齐另一条导航条；现在两边同高，补偿可以删了。
    GaoActionBar(
        count = count,
        onAssign = onAssign,
        onMerge = onMerge,
        onExport = onExport,
        onDelete = onDelete,
        modifier = modifier,
    )
}

@Composable
internal fun ActionButton(
    text: String,
    enabled: Boolean,
    primary: Boolean,
    danger: Boolean = false,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier
            .height(46.dp)
            .clip(RoundedCornerShape(15.dp))
            .background(
                when {
                    !enabled -> CardBg
                    primary -> Accent
                    danger -> WarnC
                    else -> CHigh
                }
            )
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            style = T.body,
            color = when {
                !enabled -> Muted
                primary -> OnAccent
                danger -> com.yejing.artvault.Warn
                else -> Paper
            },
            fontWeight = if (primary && enabled) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}

@Composable
private fun Header(
    total: Int,
    /** 全库版本总数。印在副标题里，取代原来那句「N 个世界观」独占一行的信息量 */
    versionTotal: Int,
    worldCount: Int,
    demo: Boolean,
    hasPerm: Boolean,
    onGrant: () -> Unit,
    onPickRoot: () -> Unit,
    /**
     * 多选态只换标题那一块的内容，不动整页结构。
     * 普通态是单行 36dp、多选态是两行 ≈59dp，所以进多选时网格会下移约 23dp——
     * 这是有意的：多选时列表本来就会重排，不必为 23dp 保留一段空白。
     */
    selecting: Boolean = false,
    selectedCount: Int = 0,
    onExitSelection: () -> Unit = {},
    onSelectAll: () -> Unit = {},
    filterCount: Int = 0,
    searchBarExpanded: Boolean = false,
    onToggleSearchBar: () -> Unit = {},
) {
    Column(Modifier.fillMaxWidth().statusBarsPadding().padding(top = 10.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                if (selecting) {
                    Text("已选 $selectedCount 件", style = T.display, color = Paper)
                    Spacer(Modifier.height(Space.xs))
                    Text("多选模式 · 长按后滑动可连续多选", style = T.label, color = Muted)
                } else {
                // V3（2026-11-20）：标题和件数合成一行。
                // 以前件数在标题下面单独占一行（display 36 + 6 + label 17 ≈ 59dp），
                // 省下这 23dp，等于把手机屏最上沿多让出 1/8 屏给卡片。
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("稿库", style = T.display, color = Paper)
                    Spacer(Modifier.width(Space.m))
                    Box(
                        Modifier
                            .size(5.dp)
                            .clip(CircleShape)
                            .background(if (demo) Lilac else Mint)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        if (demo) "演示数据 · $total 件"
                        else "$total 件 · $versionTotal 版本 · $worldCount 世界观",
                        style = T.label,
                        color = if (demo) Lilac else Muted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                }
            }
            if (selecting) {
                RoundIconButton(GI.Close, onClick = onExitSelection, background = Color.Transparent)
                Spacer(Modifier.width(Space.xs))
                ChipPill("全选", onClick = onSelectAll)
            } else {
                Spacer(Modifier.width(Space.xs))
                val active = filterCount > 0 || searchBarExpanded
                Row(
                    Modifier
                        .height(32.dp)
                        .clip(RoundedCornerShape(50))
                        .background(if (active) AccentC else CardBg)
                        .clickable(onClick = onToggleSearchBar)
                        .padding(horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    GIcon(GI.Tune, if (active) Accent else OnMid, size = 14.dp)
                    if (filterCount > 0) {
                        Text(
                            "$filterCount",
                            style = T.micro,
                            color = Accent,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                        )
                    }
                    GIcon(
                        GI.ChevronDown,
                        if (active) Accent else Muted,
                        size = 13.dp,
                        modifier = Modifier.graphicsLayer { rotationZ = if (searchBarExpanded) 180f else 0f },
                    )
                }
            }
        }

        if (demo) {
            DemoBanner(hasPerm = hasPerm, onGrant = onGrant, onPick = onPickRoot)
        }
    }
}

@Composable
private fun PickBanner(text: String, onCancel: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 8.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(AccentC)
            .padding(start = 14.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, style = T.label, color = Accent, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(Space.s))
        Box(
            Modifier
                .clip(RoundedCornerShape(50))
                .clickable(onClick = onCancel)
                .padding(horizontal = 12.dp, vertical = 6.dp),
        ) {
            Text("取消", style = T.micro, color = Muted)
        }
    }
}

@Composable
private fun DemoBanner(hasPerm: Boolean, onGrant: () -> Unit, onPick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xFF1E1B3A))
            .padding(start = 14.dp, end = 8.dp, top = 11.dp, bottom = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            if (hasPerm) "现在看的是演示数据，选一个库目录就能接管" else "先授权「所有文件访问」，再选库目录",
            style = T.label,
            color = Lilac,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(Space.s))
        Box(
            Modifier
                .clip(RoundedCornerShape(50))
                .background(Color(0xFF322B66))
                .clickable(onClick = if (hasPerm) onPick else onGrant)
                .padding(horizontal = 13.dp, vertical = 7.dp),
        ) {
            Text(
                if (hasPerm) "选择库" else "去授权",
                style = T.label,
                color = Color(0xFFD6D1FF),
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

@Composable
internal fun FilterRow(
    f: Filter,
    onOpen: () -> Unit,
    onChange: (Filter) -> Unit,
    /**
     * 首屏那排状态胶囊（全部 / 待整理 N）。
     *
     * V3（2026-11-20）把世界观胶囊撤了：库里就两个世界观，占着一整行横向空间不值当，
     * 选世界观走筛选面板；这一行只留「是不是只看待整理」这个高频开关。
     * 文件夹二级页要放改名/删除，不显示胶囊。
     */
    showStatusChips: Boolean = false,
    /** 「全部」胶囊右侧的全库件数 */
    totalCount: Int = 0,
    /** 「待整理」胶囊右侧的件数（全库口径，不随当前筛选跳动） */
    gapCount: Int = 0,
    /** 快速跳到上次整理完毕的稿件位置（null = 暂无记录，不显示） */
    onJumpLastCleaned: (() -> Unit)? = null,
    /** 关掉胶囊后追加到行尾的动作（文件夹页放 改名 / 删除） */
    trailing: (@Composable () -> Unit)? = null,
    /** 稿库把「筛选」入口提到常驻搜索框里了，这里就不再画那颗圆钮，避免两个入口打同一个面板 */
    showTune: Boolean = true,
    /**
     * 多选态下整行锁死。
     *
     * 多选时改筛选会把选择集和当前列表拆开（选了 20 件，一点世界观只剩 3 件可见，动作用到谁就不清了），
     * 所以这里不响应任何触摸；但**保留占位不整块隐藏**——高度一变下面的网格就会跟着跳。
     * 表达方式照 Material 的 disabled：降对比 + 吞事件，而不是把按钮搬走。
     */
    enabled: Boolean = true,
) {
    Box(Modifier.fillMaxWidth()) {
    Row(
        Modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else 0.38f)
            // 右边距 16 与网格对齐：之前是 12，排序胶囊的右沿比缩略图多出 4dp，看着像没对齐
            .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 「筛选」是开面板，跳世界观胶囊是即时切换 —— 两种语义不该长成同一种胶囊。
        // 收成圆形按钮之后，有筛选条件时整颗变主色底，一眼能看出「有东西被筛掉了」。
        if (showTune) {
            // 和右边的排序钮同形（12dp 圆角矩形）。两枚钮一圆一方并排，看着像版本没对齐。
            Box(
                Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (f.condCount > 0) AccentC else CardBg)
                    .clickable(onClick = onOpen),
                contentAlignment = Alignment.Center,
            ) {
                GIcon(GI.Tune, if (f.condCount > 0) Accent else OnMid, size = 17.dp)
            }
        }

        if (showStatusChips) {
            Row(
                // 只有两枚胶囊，不需要横向滚动；给 weight 是为了让排序钮始终贴右边
                modifier = Modifier
                    .weight(1f)
                    .padding(start = if (showTune) Space.s else 0.dp, end = Space.s),
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                ChipPill(
                    "全部",
                    selected = f.worldId == null && f.gaps.isEmpty(),
                    badge = totalCount.takeIf { it > 0 }?.toString(),
                ) {
                    // 「全部」= 把这一排能表达的条件都放掉（世界观是面板里的，也一并清）
                    onChange(f.copy(worldId = null, roleIds = emptySet(), gaps = emptySet()))
                }
                ChipPill(
                    "待整理",
                    selected = f.gaps.isNotEmpty(),
                    badge = gapCount.takeIf { it > 0 }?.toString(),
                ) {
                    // 点第二次就是取消：同一个位置进出，不用去别处找「取消」
                    onChange(
                        if (f.gaps.isNotEmpty()) f.copy(gaps = emptySet())
                        else f.copy(gaps = Gap.entries.toSet())
                    )
                }
                if (onJumpLastCleaned != null) {
                    ChipPill("上次整理", selected = false, onClick = onJumpLastCleaned)
                }
            }
        } else {
            Spacer(Modifier.weight(1f))
        }

        SortPill(f = f, onChange = onChange)
        trailing?.invoke()
    }
    if (!enabled) {
        // 后声明的兄弟节点盖在上面 → 命中测试先轮到它 → 点按和横向滚动都被吃掉。
        // 里面 awaitPointerEvent 是挂起点，不会像 scrollBy 那样把主线程占死。
        Box(
            Modifier.matchParentSize().pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent().changes.forEach { it.consume() }
                    }
                }
            },
        )
    }
    }
}

@Composable
private fun SortPill(f: Filter, onChange: (Filter) -> Unit) {
    var open by remember { mutableStateOf(false) }
    // 排序不是筛选条件，所以不做「选中」高亮；只有偏离默认（收稿时间新→旧）时才点亮，
    // 让「我现在看到的顺序被我改过」这件事有痕迹。方向由图标旋转表达（升序 = 整枚转 180°）。
    val custom = f.sort != Sort()
    val asc = f.sort.dir == SortDir.Asc
    Box {
        // V1（2026-11-20）：从圆形改成 12dp 圆角矩形，和搜索框、「筛选」按钮同一套形状语言。
        // 圆形钮在这个头部里只有它一个，看着像从别的页面漏过来的。
        Box(
            Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(if (custom) AccentC else CardBg)
                .clickable { open = true }
                .graphicsLayer { rotationZ = if (asc) 180f else 0f },
            contentAlignment = Alignment.Center,
        ) {
            GIcon(GI.Sort, if (custom) Accent else OnMid, size = 17.dp)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            SortKey.entries.forEach { k ->
                val active = f.sort.key == k
                DropdownMenuItem(
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                // 当前字段直接显示「新→旧 / 旧→新」，再点一次就是翻转
                                if (active) f.sort.label else k.label,
                                style = T.bodySm,
                                color = if (active) Accent else OnMid,
                            )
                            if (active) {
                                Spacer(Modifier.width(Space.s))
                                GIcon(GI.Check, Accent, size = 14.dp)
                            }
                        }
                    },
                    onClick = {
                        onChange(f.withSort(if (active) f.sort.flip() else Sort(k)))
                        open = false
                    },
                )
            }
        }
    }
}

/**
 * 选择集。唯一真源是一个 `SnapshotStateMap<id, Art>`。
 *
 * 为什么不用 `mutableStateListOf`：卡片的 `selected` 每帧都要问一次「我在不在里面」，
 * 列表版的 `any { it.id == a.id }` 是 O(n)。选中几百件、屏幕上几十张卡时，每帧就是几万次比较，
 * 拖选跨月滚动直接掉帧（勾号追不上滚动的根因）。
 * 换成 map 之后查找 O(1)，且 `dragTo` 只增删差异项，不再 `clear + addAll` 整表重建。
 */
private class Selection {
    private val map = androidx.compose.runtime.mutableStateMapOf<String, Art>()
    var active by androidx.compose.runtime.mutableStateOf(false)

    /** 选中项快照。读它会订阅整张表，所以只在需要的地方取。 */
    val items: List<Art> get() = map.values.toList()

    val count: Int get() = map.size

    /**
     * 每张卡自己的「选中与否」。
     *
     * 为什么不能直接读 `map.containsKey(id)`：那读的是整张表，任何一项变化都会让**所有**卡片
     * 的 items lambda 失效并重组 —— 拖选划过几百张时，每帧都在重排整屏，勾号自然追不上手指。
     * `derivedStateOf` 会把它折成单个 Boolean 的通知：只有这一张的选中态真的翻了，它才重组。
     */
    private val flags = HashMap<String, androidx.compose.runtime.State<Boolean>>()

    fun selectedFlag(id: String): androidx.compose.runtime.State<Boolean> =
        flags.getOrPut(id) { androidx.compose.runtime.derivedStateOf { map.containsKey(id) } }

    fun has(a: Art) = map.containsKey(a.id)

    fun toggle(a: Art) {
        if (map.remove(a.id) == null) map[a.id] = a
    }

    fun setAll(list: List<Art>) {
        map.clear()
        list.forEach { map[it.id] = it }
    }

    fun clear() {
        map.clear()
        active = false
        anchor = -1
        base = emptyMap()
        lastFlat = -1
        rangeArts.clear()
        curLo = -1
        curHi = -1
    }

    // ── 拖选：起点、基线、当前区间 ──
    private var anchor = -1
    private var base: Map<String, Art> = emptyMap()
    private var lastFlat = -1

    /** 当前区间 [curLo, curHi] 里被选中的项，按 flat 记 — 收缩时靠它反查 id，不必再算 artAt */
    private val rangeArts = HashMap<Int, Art>()
    private var curLo = -1
    private var curHi = -1

    fun beginDrag(flat: Int, artAt: (Int) -> Art?) {
        val a = artAt(flat) ?: return
        anchor = flat
        lastFlat = flat
        base = HashMap(map)
        rangeArts.clear()
        rangeArts[flat] = a
        curLo = flat
        curHi = flat
        map.putIfAbsent(a.id, a)
        active = true
    }

    /**
     * 每次移动**重算区间语义**，但只改差异 —— 往回拖会取消刚划过的那些。
     *
     * 之前是每帧把整个 [lo..hi] 重新收集成一个 Map 再和全表做 diff：跨月快速拖动时区间能有上千格，
     * 等于每帧新建一个上千项的 Map 再做一次全表比对，主线程直接被 GC 和遍历吃掉，勾号就追不上手。
     * 现在只处理「边界移动过的那几格」：扩展补几项、收缩删几项，开销与拖动速度无关。
     */
    fun dragTo(flat: Int, artAt: (Int) -> Art?, flatOf: (String) -> Int?) {
        if (anchor < 0 || flat == lastFlat) return
        lastFlat = flat
        val newLo = minOf(anchor, flat)
        val newHi = maxOf(anchor, flat)

        // 收缩：离开区间的项。拖选开始前就选中的（base）不动。
        if (curLo < newLo) {
            for (i in curLo until newLo) {
                val gone = rangeArts.remove(i) ?: continue
                if (!base.containsKey(gone.id)) map.remove(gone.id)
            }
        }
        if (newHi < curHi) {
            for (i in (newHi + 1)..curHi) {
                val gone = rangeArts.remove(i) ?: continue
                if (!base.containsKey(gone.id)) map.remove(gone.id)
            }
        }
        // 扩展：新进入区间的项
        if (newLo < curLo) {
            for (i in newLo until curLo) {
                val a = artAt(i) ?: continue
                rangeArts[i] = a
                map[a.id] = a
            }
        }
        if (newHi > curHi) {
            for (i in (curHi + 1)..newHi) {
                val a = artAt(i) ?: continue
                rangeArts[i] = a
                map[a.id] = a
            }
        }
        curLo = newLo
        curHi = newHi
    }

    fun endDrag() {
        anchor = -1
        base = emptyMap()
        lastFlat = -1
        rangeArts.clear()
        curLo = -1
        curHi = -1
    }

    /** 写盘之后：把已经不存在的剔除，并把存活项换成新实例。 */
    fun reconcile(all: List<Art>) {
        if (map.isEmpty()) return
        val byId = all.associateBy { it.id }
        map.keys.toList().forEach { if (it !in byId) map.remove(it) }
        map.keys.toList().forEach { id -> byId[id]?.let { if (map[id] !== it) map[id] = it } }
        if (map.isEmpty()) active = false
    }
}

/**
 * 列表里的月标题槽。
 *
 * 高度 = 浮层高度 + 10dp 渐变带：标题文字贴 `MonthHeaderH` 的底，和浮层里的文字逐像素对齐；
 * 多出来的 10dp 正好让第一行卡片从渐变结束处开始，不会被浮层压得发虚。
 *
 * 首月那份是故意放在这儿的——它整块被浮层盖住，滚起来两个「11 月」不会同时出现。
 */
@Composable
private fun MonthTitleSlot(label: String, count: Int, spend: Long?) {
    Box(Modifier.fillMaxWidth().height(MonthHeaderH + 10.dp)) {
        MonthHeader(label, count, spend)
    }
}

/**
 * 冻结月标头（浮层）。
 *
 * 和系统 `stickyHeader` 的差别就在这：`stickyHeader` 是「下一个月把上一个**推**出去」，
 * 视觉上是一条实体被另一条顶走；这里是**文字原地替换** —— 位置一像素不动，
 * 跨月时只有文字换掉，接近华为相册的手感。
 *
 * 下面接 10dp 渐变而不是硬切，卡片是「从标头下面淡出」而不是被切断。
 */
@Composable
private fun MonthHeaderOverlay(label: String, count: Int, spend: Long?) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(MonthHeaderH + 10.dp)
            .background(
                androidx.compose.ui.graphics.Brush.verticalGradient(
                    0f to Ink,
                    (MonthHeaderH.value / (MonthHeaderH + 10.dp).value) to Ink,
                    1f to Ink.copy(alpha = 0f),
                ),
            ),
    ) {
        // 用 Crossfade，不用 key 重建。
        // key 换掉时旧子树是**瞬间移除**的，新子树再从 alpha 0 淡入 —— 中间有几十毫秒是空的，
        // 那就是「替换时闪一下、像消失了一下」。Crossfade 让旧的淡出和新的淡入重叠，每一帧都有字。
        androidx.compose.animation.Crossfade(
            targetState = Triple(label, count, spend),
            animationSpec = androidx.compose.animation.core.tween(110),
            label = "monthLabel",
        ) { (l, c, s) ->
            // 背景通宽（盖住滚上来的卡片），文字仍守 16dp 页边 —— 和列表里的卡片左沿对齐
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(MonthHeaderH)
                    .padding(horizontal = 16.dp),
            ) {
                MonthHeader(l, c, s)
            }
        }
    }
}

/** 月标头高度。冻结浮层与列表顶部留白都用它，必须一致。 */
private val MonthHeaderH = 34.dp

@Composable
private fun MonthHeader(label: String, count: Int, spend: Long?) {
    Column(
        Modifier.fillMaxWidth().height(MonthHeaderH),
        verticalArrangement = Arrangement.Bottom,
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            Text(
                label,
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                color = Paper,
                letterSpacing = (-0.2).sp,
            )
            Spacer(Modifier.weight(1f))
            // 月度支出（R6）：和底栏「账本」同一套心智 —— 翻到哪个月就知道那个月花了多少。
            // 该月一件都没记价时 spend 为 null，只留件数，不印 ¥0。
            Text(
                if (spend != null) "$count 件 · ¥$spend" else "$count 件",
                style = T.micro,
                color = if (spend != null) Amber else Muted,
                maxLines = 1,
                modifier = Modifier.padding(bottom = 2.dp),
            )
        }
        Spacer(Modifier.height(6.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(OutlineSoft))
    }
}

/** 一个月份的卡片块：切行、气泡文案、冻结浮层都从这里取。 */
private data class MonthBlock(
    val key: String,
    val label: String,
    val count: Int,
    /** 该月已记价格合计。**全月都没填价时为 null**（不显示），不要退化成 ¥0 ——
     * 0 是有效价格（免费稿），拿它表示「没数据」会让账读错 */
    val spend: Long?,
    val arts: List<Art>,
)

private fun monthKeyOf(a: Art): String =
    Dates.parse(a.receivedAt)?.let { "%04d-%02d".format(it.year, it.monthValue) } ?: "unknown"

private fun monthLabelOf(a: Art): String =
    Dates.parse(a.receivedAt)?.let { "${it.year} 年 ${it.monthValue} 月" } ?: "日期未知"

/** 依赖 `shown` 已按收稿时间排序：同一月的稿必然相邻。 */
private fun buildMonths(arts: List<Art>): List<MonthBlock> {
    val out = ArrayList<MonthBlock>(12)
    var i = 0
    while (i < arts.size) {
        val key = monthKeyOf(arts[i])
        var j = i
        var sum = 0L
        var priced = 0
        while (j < arts.size && monthKeyOf(arts[j]) == key) {
            // 顺手把月度支出算掉：这里本来就在遍历，等于零额外成本（R6）
            arts[j].priceTotal?.let { sum += it; priced++ }
            j++
        }
        out += MonthBlock(key, monthLabelOf(arts[i]), j - i, if (priced > 0) sum else null, arts.subList(i, j))
        i = j
    }
    return out
}
