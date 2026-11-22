package com.yejing.artvault

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animate
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.clickable
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.yejing.artvault.ui.component.GI
import com.yejing.artvault.ui.component.ExportConfirmDialog
import com.yejing.artvault.ui.component.RoundIconButton
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class BgMode { Checker, Black, White }

/**
 * 单版本/整槽导出，返回要 toast 的文案。文案与 ArtPage 原来的逐字一致。
 * 任务卡 D §2.3：文案阶梯只留这一份，ArtPage 与看图浮层共用，否则以后会漂。
 */
internal suspend fun exportVersionMessage(
    ctx: Context,
    root: File,
    lib: Library,
    art: Art,
    tax: Taxonomy,
    folders: FolderBook,
    verId: String?,
    allArts: List<Art> = listOf(art),
): String {
    val res = withContext(Dispatchers.IO) {
        Mirror.export(ctx, root, lib, listOf(art), tax, folders, verId = verId, allArts = allArts)
    }
    val base = when {
        res.error != null -> "导出失败：${res.error}"
        res.written == 0 && res.failed == 0 -> "已是最新，无需重复导出"
        res.written == 0 -> "已导出 0 个"
        res.skipped == 0 -> "已导出 ${res.written} 个"
        else -> "导出 ${res.written} 个，${res.skipped} 个已是最新"
    }
    return if (res.error == null && res.failed > 0) "$base，${res.failed} 个写入失败" else base
}

internal fun bgName(b: BgMode) = when (b) {
    BgMode.Checker -> "棋盘"
    BgMode.Black -> "黑底"
    BgMode.White -> "白底"
}

@Composable
internal fun CheckerBoard(cell: Dp = 12.dp) {
    val dark = ThemeState.palette.isDark
    val c1 = if (dark) Color(0xFF161C26) else Color(0xFFEBE7E0)
    val c2 = if (dark) Color(0xFF11161E) else Color(0xFFF5F2EC)
    Canvas(Modifier.fillMaxSize()) {
        val s = cell.toPx()
        val cols = (size.width / s).toInt() + 1
        val rows = (size.height / s).toInt() + 1
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                val color = if ((r + c) % 2 == 0) c1 else c2
                drawRect(color, Offset(c * s, r * s), Size(s, s))
            }
        }
    }
}

/**
 * 详情页：一条可纵向滚动的长页（大图 → 文件信息 → 详细信息 / 版本管理）。
 *
 * 手势分工（2026-11-18 重做）：
 * - **图片区左右滑 = 切这个槽的版本**；只有一个版本时滑动什么都不发生（横向事件被吞掉）。
 * - **图片区以外左右滑 = 切上/下一张稿**（外层 Pager）。内层版本 Pager 先命中，所以不冲突。
 * - **单击图片 = 进看图浮层**，那里才有缩放/平移。详情页本身不再缩放。
 */
/**
 * 详情页：一条可纵向滚动的长页（大图 → 文件信息 → 详细信息 / 版本管理）。
 *
 * **横向的一切都是自绘的**（2026-11-18）。不再用 `HorizontalPager` 叠 `HorizontalPager`：
 * Compose 的 nested-scroll 在 pre 阶段是子→父派发、父级可以先消费，内层永远抢不过外层，
 * 官方没有开关能反过来。现在外层（切稿件）和内层（切版本）是同一套机制：
 * 手势直接推位移 → `graphicsLayer` 平移（只重绘不重排）→ 松手把速度交给 `animate`（惯性）。
 *
 * 手势分工：
 * - 图片区横向 → 切版本（内层），事件被 consume，外层收不到；
 * - 图片区以外横向 → 切上/下一张稿（外层）；
 * - 竖向 → 各自的纵向滚动；单击图片 → 进看图浮层。
 */
/** 底部操作条实际占的高度：46dp 按钮 + 10dp 上间距 + 12dp 下间距。
 *  它是浮层（不占布局），所以滚动内容的末尾必须自己留出这么多，否则最后一行会被压住。 */
internal val DetailBottomBarH = 68.dp

/** 内容末尾真正的呼吸空间：扣掉被操作条占住的那 68dp 之后，还剩这么多空白。 */
internal val DetailContentBottomGap = 48.dp

@Composable
fun DetailScreen(
    arts: List<Art>,
    lib: Library,
    tax: Taxonomy,
    /** id → 名字的解析器：画师 / 标签的显示名从这里来 */
    names: VaultNames,
    root: File?,
    /** 导出要用归档名（`folder_id` → 目录名），所以这一层也得传下来 */
    folders: FolderBook,
    /** 顶栏那颗只读胶囊要显示「筛选: 3 项」——告诉用户现在看的是筛过的结果，不是全库 */
    filter: Filter = Filter(),
    /**
     * 二级页（画师 / 角色 / 归档）用一句话说清「这串图是什么范围」。
     * 它们筛的维度（画师、单角色）在 [Filter] 里没有对应字段，硬套只会显示成「筛选: 1 项」。
     */
    rangeLabel: String? = null,
    startIndex: Int,
    onAddVersion: (String) -> Unit,
    onEdit: (Art) -> Unit,
    /** 删除：走 Root 的 deleteTargets → 二次确认 → 进回收站，不在这里直接删 */
    onDelete: (Art) -> Unit,
    /** 内嵌的版本管理写完盘后要让外层重载 */
    onChanged: () -> Unit,
    /**
     * 独立版本管理页（`VersionScreen`）的入口。
     *
     * **详情页自己已经不用它了**：版本管理改成页内「版本管理」模式（卡 13），
     * 不需要整个离开组合。参数暂时留着，是因为 Root 的 `versionOf` 分支还留着当回退
     * （卡 13：验过一轮再单独清理）。清理时这条链路可以整条删：本参数 + Artist/Folder/Role
     * 三个页的转发 + Root 的 `versionOf` 分支 + `VersionScreen` 外壳。
     */
    onManageVersions: (String) -> Unit,
    /**
     * 滑到其他稿件时把当前 id 报上去。
     *
     * 必须由上层记住：去版本管理 / 编辑信息时本页会**整个离开组合**（Root 的 when 只渲染一个分支），
     * `rememberPagerState` 随之丢失，回来时按 `startIndex` 重建 —— 不报上去就会跳回「当初打开的那张」。
     */
    onPageChanged: (String) -> Unit,
    onClose: () -> Unit,
    /** 全库稿件（反查制品列表与解析制品原稿用） */
    allArts: List<Art> = arts,
    onOpenArt: (String) -> Unit = {},
    onAddDerived: (String) -> Unit = {},
    onUnlink: (String) -> Unit = {},
    onPickSource: (String) -> Unit = {},
    onOpenRole: (String) -> Unit = {},
    onOpenArtist: (String) -> Unit = {},
    onFilterTag: (String) -> Unit = {},
    onFilterWorld: (String) -> Unit = {},
    /** 跨覆盖层跳转返回时恢复各稿件的滚动位置（按 art.id 记，由 Root 持有） */
    savedScrolls: MutableMap<String, Int> = remember { HashMap() },
    onMoveVersionsToArt: ((String, List<String>) -> Unit)? = null,
) {
    val pager = rememberPagerState(
        initialPage = startIndex.coerceIn(0, (arts.size - 1).coerceAtLeast(0))
    ) { arts.size }

    // 翻页就把当前 id 交给上层：本页可能随时因覆盖层而离开组合，只有上层记着才不会跳回去。
    // 按 id 而不是下标 —— 上层重载后列表顺序可能变。
    LaunchedEffect(pager.currentPage) {
        arts.getOrNull(pager.currentPage)?.let { onPageChanged(it.id) }
    }

    // 列表重排后，把 pager 挪到这张稿的新位置。
    //
    // `rememberPagerState` 的 initialPage 只在**首次组合**生效：arts 因为「改收稿时间」或
    // 「改排序」而整体重排之后，currentPage 仍是旧索引，而旧索引在新列表里指的是另一张稿。
    // 表现：把 10 号的稿改成 8 号，详情页跳到了「原来那个位置的下一个」（正好是相邻的 10 号）。
    //
    // startIndex 由 Root 用 `indexOfFirst { it.id == detailId }` 算出，按 id 走，重排后一定正确；
    // 用户正常滑动时 onPageChanged 会回写 detailId → startIndex 变成新索引、与 currentPage 相等，
    // 所以这里不会跟手指抢位置。
    // 用 scrollToPage（瞬时）而不是 animateScrollToPage：修正位置不该让画面滑一下。
    LaunchedEffect(startIndex) {
        if (startIndex in 0 until arts.size && startIndex != pager.currentPage) {
            pager.scrollToPage(startIndex)
        }
    }

    // 看图浮层：存「哪张稿 + 哪个版本」，关掉时置空
    var viewer by remember { mutableStateOf<Pair<String, Int>?>(null) }
    // 每张稿「现在看的是第几个版本」。顶栏全屏钮要用它：点全屏应该放大你正看的那一版，
    // 而不是每次从第一版开始。Pager 每个 page 自己一份 vPager，所以在这里按 artId 收一份索引。
    val versionIdx = remember { androidx.compose.runtime.mutableStateMapOf<String, Int>() }
    // 底衬：棋盘 / 黑 / 白。跨稿件保留一份，切来切去不用每次重选
    var bg by remember { mutableStateOf(BgMode.Checker) }
    val viewing = viewer
    // 任务卡 D §2.3：DetailScreen 自己的 scope，供浮层导出用；不动 ArtPage 里已有的那个
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var exportTick by remember { mutableIntStateOf(0) }
    // 整槽导出前的确认（从 ArtPage 上移：按钮已经不在每页里了）
    var askExport by remember { mutableStateOf<Art?>(null) }
    // 版本模式：详情页就地把信息区换成版本列表。翻到别的稿时不退出（用户可能在连续整理）
    var versionMode by remember { mutableStateOf(false) }
    // 版本模式下底部「导出版本 / 删除版本」发给当前页 VersionEditor 的指令；处理完由对方清掉
    var versionCmd by remember { mutableStateOf<VersionCmd?>(null) }
    var starring by remember { mutableStateOf(false) }

    // 浮层开着时返回键先关浮层，再退页面（单一入口）
    BackHandler {
        android.util.Log.d("GaoCangBack", "detail viewing=${viewing != null}")
        if (viewing != null) viewer = null else onClose()
    }

    Box(Modifier.fillMaxSize().background(Ink)) {
        val allById = remember(allArts) { allArts.associateBy { it.id } }
        val roleCounts = remember(allArts) {
            val cnt = HashMap<String, Int>()
            allArts.forEach { a -> if (a.counted) a.roleIds.forEach { cnt[it] = (cnt[it] ?: 0) + 1 } }
            cnt
        }
        HorizontalPager(
            state = pager,
            // 浮层打开时不许翻页，否则浮层里滑动会把底下的稿换掉
            userScrollEnabled = viewing == null,
            modifier = Modifier.fillMaxSize(),
        ) { page ->
            val curArt = arts[page]
            val srcArt = curArt.sourceArtId?.let { allById[it] }
            val derivedList = remember(allArts, curArt.id, curArt.kind) {
                if (curArt.kind == ArtKind.Artwork) {
                    allArts.filter { it.kind == ArtKind.Derived && it.sourceArtId == curArt.id }
                } else emptyList()
            }
            ArtPage(
                art = curArt,
                lib = lib,
                tax = tax,
                names = names,
                root = root,
                folders = folders,
                onEdit = onEdit,
                onAddVersion = onAddVersion,
                onDelete = onDelete,
                onChanged = onChanged,
                bg = bg,
                onBgChange = { bg = it },
                onOpenViewer = { vi -> viewer = curArt.id to vi },
                onVersionChanged = { vi -> versionIdx[curArt.id] = vi },
                exportTick = exportTick,
                versionMode = versionMode,
                versionCmd = versionCmd,
                onVersionCmdHandled = { versionCmd = null },
                sourceArt = srcArt,
                derived = derivedList,
                onOpenArt = onOpenArt,
                onAddDerived = onAddDerived,
                onUnlink = onUnlink,
                onPickSource = onPickSource,
                onOpenRole = onOpenRole,
                onOpenArtist = onOpenArtist,
                onFilterTag = onFilterTag,
                onFilterWorld = onFilterWorld,
                roleCounts = roleCounts,
                savedScroll = savedScrolls[curArt.id] ?: 0,
                onSaveScroll = { savedScrolls[curArt.id] = it },
                onMoveVersionsToArt = onMoveVersionsToArt,
            )
        }

        // 顶栏磨砂浮层：图片延伸到状态栏下面，靠一层渐变保证图标始终看得清
        Box(
            Modifier
                .fillMaxWidth()
                .height(150.dp)
                .align(Alignment.TopCenter)
                .background(
                    Brush.verticalGradient(
                        listOf(Color(0xCC060608), Color(0x73060608), Color.Transparent)
                    )
                )
        )
        // 顶栏压在深色渐变遮罩上，文字/图标恒定浅色，避免浅色主题下变暗看不清
        val barInk = Color(0xFFEEF0F6)
        val barInk70 = Color(0xB3EEF0F6)
        val barVeil = Color(0x4D090B10)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(start = 12.dp, end = 16.dp, top = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RoundIconButton(
                icon = GI.Back,
                onClick = onClose,
                background = barVeil,
                tint = barInk,
                size = 36.dp,
                iconSize = 18.dp,
            )
            Spacer(Modifier.width(10.dp))
            Text(
                "${pager.currentPage + 1} / ${arts.size}",
                style = T.numLg,
                color = barInk70,
            )
            Spacer(Modifier.width(10.dp))
            // 只读的条件提示：现在看的是「筛过的 1765 件」还是「全库」。
            // 刻意不给点击——点开去改筛选，会让当前翻页位置失去意义。
            // 不给 weight：占满会变成一条长横条，胶囊要贴着文字收口，右边用 Spacer 把全屏钮顶到边上。
            Box(
                Modifier
                    .widthIn(max = 168.dp)
                    .clip(RoundedCornerShape(50))
                    .background(barVeil)
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            ) {
                Text(
                    rangeLabel ?: if (filter.isEmpty) "筛选: 全部稿件" else "筛选: ${filter.condCount} 项",
                    style = T.micro,
                    color = barInk70,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.weight(1f))
            val cur = arts.getOrNull(pager.currentPage)
            RoundIconButton(
                icon = GI.Star,
                onClick = {
                    if (starring) return@RoundIconButton
                    val a = arts.getOrNull(pager.currentPage) ?: return@RoundIconButton
                    val r = root
                    if (r == null) {
                        toast(ctx, "演示库不能星标")
                    } else {
                        starring = true
                        scope.launch {
                            val ok = try {
                                withContext(Dispatchers.IO) {
                                    // 用 getOrDefault(false) 取回 Boolean 本身；
                                    // 别写 .isSuccess（setStarred 写盘失败时正常返回 false）。
                                    runCatching {
                                        OperationJournal.run(r, "art", "星标：${a.title.ifBlank { "未命名" }}") {
                                            ArtworkCommands.setStarred(r, a.id, !a.starred)
                                        }
                                    }.getOrDefault(false)
                                }
                            } finally {
                                starring = false
                            }
                            if (ok) onChanged() else toast(ctx, "写入失败")
                        }
                    }
                },
                background = if (cur?.starred == true) Amber.copy(alpha = 0.22f) else barVeil,
                tint = if (cur?.starred == true) Amber else barInk,
                size = 36.dp,
                iconSize = 17.dp,
                filled = cur?.starred == true,
            )
            Spacer(Modifier.width(8.dp))
            RoundIconButton(
                icon = GI.Fullscreen,
                onClick = {
                    val a = arts.getOrNull(pager.currentPage)
                    if (a != null) {
                        val i = (versionIdx[a.id] ?: 0).coerceIn(0, (a.versions.size - 1).coerceAtLeast(0))
                        viewer = a.id to i
                    }
                },
                background = barVeil,
                tint = barInk,
                size = 36.dp,
                iconSize = 17.dp,
            )
        }

        if (viewing != null) {
            val art = arts.firstOrNull { it.id == viewing.first }
            if (art == null) {
                LaunchedEffect(viewing) { viewer = null }
            } else {
                ImageViewer(
                    art = art,
                    lib = lib,
                    startVersion = viewing.second,
                    bg = bg,
                    onClose = { viewer = null },
                    onExportVersion = { vi ->
                        val r = root
                        if (r == null) {
                            toast(ctx, "演示库不能导出")
                        } else {
                            val v = art.versions.getOrNull(vi)
                            if (v == null) toast(ctx, "这个版本已经不存在了")
                            else scope.launch {
                                toast(ctx, exportVersionMessage(ctx, r, lib, art, tax, folders, v.id, allArts))
                                exportTick++
                            }
                        }
                    },
                )
            }
        }
        // 底部常驻操作条（2026-11-21）
        //
        // 这几个按钮原先在 InfoTab 末尾 = 整页最底部，看图为主的页面要滚到底才看得见，
        // 而「编辑 / 导出 / 删除」是详情页唯一的出口。改成浮在底部常驻。
        // 卡 13 起变四等分：多一颗「版本管理」开关，前三颗在版本模式下改指「当前版本」。
        //
        // 用浮层而不是占布局的底栏：详情页的骨架就是「图铺满」，图区高度不能因为按钮变小。
        // 底衬是 Ink 渐变 —— 压在浅色作品图上也能读；滚动内容底部多留的空间见 DetailArtPage。
        // 全屏看图时整条隐藏：那时用户在看图，不该有按钮。
        if (viewing == null) {
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            0f to Color.Transparent,
                            0.32f to Ink.copy(alpha = 0.90f),
                            1f to Ink,
                        )
                    )
                    .navigationBarsPadding()
                    .padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 12.dp),
            ) {
                val cur = arts.getOrNull(pager.currentPage)
                // 顺序：编辑信息 → 导出（整槽/当前版）→ 删除（整槽/当前版）→ 版本管理。
                // 「编辑信息」是日常最常按的一颗，放最左；「版本管理」是模式开关，放最右贴着边缘，
                // 既不容易误触，也符合「开关在末尾」的直觉。
                Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                    Box(
                        Modifier
                            .weight(1f)
                            .height(46.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(Accent)
                            .clickable(enabled = cur != null) { cur?.let(onEdit) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "编辑信息",
                            style = T.bodySm,
                            color = OnAccent,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    Box(
                        Modifier
                            .weight(1f)
                            .height(46.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(CHigh)
                            .clickable(enabled = cur != null) {
                                // 版本模式：接管为「导出当前版本」；否则整槽导出
                                if (versionMode) cur?.let { versionCmd = VersionCmd(it.id, VersionCmdKind.Export) }
                                else askExport = cur
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(if (versionMode) "导出版本" else "导出", style = T.bodySm, color = Paper)
                    }
                    // 破坏性动作：低饱和容器 + 红字，与批量删除、删文件夹同一套，不做实心红
                    Box(
                        Modifier
                            .weight(1f)
                            .height(46.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(WarnC)
                            .clickable(enabled = cur != null) {
                                // 版本模式：接管为「删除当前版本」（进回收站，有确认框）；否则删整槽
                                if (versionMode) cur?.let { versionCmd = VersionCmd(it.id, VersionCmdKind.Delete) }
                                else cur?.let(onDelete)
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(if (versionMode) "删除版本" else "删除", style = T.bodySm, color = Warn)
                    }
                    Box(
                        Modifier
                            .weight(1f)
                            .height(46.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(if (versionMode) AccentC else CHigh)
                            .clickable {
                                versionMode = !versionMode
                                // 退出/进入时把没被消费的指令清掉：否则它会在下次进入版本模式时复活，
                                // 对着一个早就不是「刚才那一版」的东西弹确认框
                                versionCmd = null
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("版本管理", style = T.bodySm, color = if (versionMode) Accent else Paper)
                    }
                }
            }
        }

    }

    // 整槽导出：确认框跟着按钮走（都在 DetailScreen 这一层），
    // 避免「点上面那页的按钮、弹下面那页的框」。
    askExport?.let { a ->
        ExportConfirmDialog(
            title = "导出这一件的 ${a.versions.size} 个版本？",
            onCancel = { askExport = null },
            onConfirm = {
                askExport = null
                val r = root
                if (r == null) {
                    toast(ctx, "演示库不能导出")
                } else {
                    scope.launch {
                        toast(ctx, exportVersionMessage(ctx, r, lib, a, tax, folders, null, allArts))
                        exportTick++
                    }
                }
            },
        )
    }

}
