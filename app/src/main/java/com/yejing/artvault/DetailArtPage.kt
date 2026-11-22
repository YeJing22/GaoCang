package com.yejing.artvault

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun ArtPage(
    art: Art,
    lib: Library,
    tax: Taxonomy,
    names: VaultNames,
    root: File?,
    folders: FolderBook,
    onEdit: (Art) -> Unit,
    onAddVersion: (String) -> Unit,
    onDelete: (Art) -> Unit,
    onChanged: () -> Unit,
    bg: BgMode,
    onBgChange: (BgMode) -> Unit,
    onOpenViewer: (Int) -> Unit,
    /** 把「正在看第几版」报给 DetailScreen：顶栏的全屏钮要用它 */
    onVersionChanged: (Int) -> Unit = {},
    exportTick: Int = 0,
    /** 版本模式：信息区换成版本列表（图与版本 chip 保留）。状态在 DetailScreen，翻页不退出 */
    versionMode: Boolean = false,
    /** 版本模式下底部操作条的「导出版本 / 删除版本」：DetailScreen 发令，当前页的 VersionEditor 执行 */
    versionCmd: VersionCmd? = null,
    onVersionCmdHandled: () -> Unit = {},
    sourceArt: Art? = null,
    derived: List<Art> = emptyList(),
    onOpenArt: (String) -> Unit = {},
    onAddDerived: (String) -> Unit = {},
    onUnlink: (String) -> Unit = {},
    onPickSource: (String) -> Unit = {},
    onOpenRole: (String) -> Unit = {},
    onOpenArtist: (String) -> Unit = {},
    onFilterTag: (String) -> Unit = {},
    onFilterWorld: (String) -> Unit = {},
    roleCounts: Map<String, Int> = emptyMap(),
    savedScroll: Int = 0,
    onSaveScroll: (Int) -> Unit = {},
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val versions = art.versions
    val vPager = rememberPagerState(0) { versions.size.coerceAtLeast(1) }

    LaunchedEffect(vPager.currentPage, art.id) { onVersionChanged(vPager.currentPage) }

    var localExportTick by remember(art.id) { mutableIntStateOf(0) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, ev ->
            if (ev == Lifecycle.Event.ON_RESUME) localExportTick++
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    val scroll = rememberScrollState(initial = savedScroll)
    DisposableEffect(art.id) {
        onDispose { onSaveScroll(scroll.value) }
    }

    // 页面整体是一条可滚动的长页：大图 → 版本条 → 信息区（或版本模式下的版本列表）。
    // 版本模式不另起布局：整页照样滚，VersionEditor 的 LazyColumn 自带固定高度（不是无限高），
    // 嵌进 verticalScroll 不会塌。
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(scroll),
    ) {
        ImageBlock(
            versions = versions,
            lib = lib,
            vPager = vPager,
            bg = bg,
            onBgChange = onBgChange,
            onOpenViewer = { onOpenViewer(vPager.currentPage) },
        )

        // 版本条（R4）：一排带名字的 chip + 「＋」。
        VersionStrip(
            versions = versions,
            current = vPager.currentPage,
            // 点 chip 切版本 = **原地替换，不要动画**（2026-11-21 用户拍板）。
            // 理由：点版本名的人是在「对比两版的差异」（带背景 vs 透明底），
            // 一滑过去，眼睛会跟着位移走，反而看不出线条/配色的差别；
            // 硬切让同一块像素位置上的图直接换掉，差异一眼可见。
            // 手动拖动松手才要滑（那是"我划到了下一页"的确认），见 ImageBlock 的 AlwaysAnimate。
            // scrollToPage 是瞬时定位，本来就与动画时长无关，不需要 AlwaysAnimate。
            onSelect = { i -> scope.launch { vPager.scrollToPage(i) } },
            onAdd = { onAddVersion(art.id) },
        )

        if (versionMode) {
            // 当前版本按 **id** 交给列表：pager 用的是 art.versions 的原始顺序，
            // VersionEditor 内部是「主图置顶」后的顺序，两边的下标不一定对得上。
            // 传下标的话，一旦错位就是「高亮/删除/导出的是另一版」，而且不报错。
            val curVerId = versions.getOrNull(vPager.currentPage)?.id
            VersionEditor(
                art = art,
                lib = lib,
                root = root,
                tax = tax,
                folders = folders,
                onChanged = onChanged,
                currentId = curVerId,
                onSelect = { id ->
                    val i = versions.indexOfFirst { it.id == id }
                    if (i >= 0) scope.launch { vPager.scrollToPage(i) }
                },
                command = versionCmd,
                onCommandHandled = onVersionCmdHandled,
                // 制品导出时要在文件名里带**原稿**的角色，所以把原稿一起传下去
                allArts = listOfNotNull(sourceArt, art),
            )
        } else {
            val curIdx = vPager.currentPage.coerceIn(0, (versions.size - 1).coerceAtLeast(0))
            val curVerId = versions.getOrNull(curIdx)?.id?.ifBlank { curIdx.toString() } ?: curIdx.toString()
            InfoTab(
                art = art,
                tax = tax,
                names = names,
                lib = lib,
                folders = folders,
                root = root,
                verId = curVerId,
                refreshTick = exportTick + localExportTick,
                sourceArt = sourceArt,
                derived = derived,
                onOpenArt = onOpenArt,
                onAddDerived = onAddDerived,
                onUnlink = onUnlink,
                onPickSource = onPickSource,
                onOpenRole = onOpenRole,
                onOpenArtist = onOpenArtist,
                onFilterTag = onFilterTag,
                onFilterWorld = onFilterWorld,
                roleCounts = roleCounts,
            )
        }

        // 留白 = 操作条高度 + 真正的呼吸空间。
        // 之前只留 28dp，扣掉被操作条占住的 68dp 之后只剩 28dp 空白，
        // 滚到底时最后一行几乎贴着按钮（2026-11-21 用户反馈截图）。
        // 版本模式多给 24dp：列表底部的说明小字与多选条不会被操作条压住。
        Spacer(
            Modifier
                .height(DetailBottomBarH + DetailContentBottomGap + if (versionMode) 24.dp else 0.dp)
                .navigationBarsPadding(),
        )
    }
}

/**
 * 图片区：宽度铺满，高度**按主图**算（不跟当前版本，避免每切一次跳一下）。
 *
 * 横向切版本用 `HorizontalPager` + **手动推进位移**（`dispatchRawDelta`）：
 * 内层 Pager 自己滚时，外层「切稿件」的 Pager 会在 nested-scroll 的 pre 阶段先把横向吃掉。
 * 手动推进的同时 consume，外层就收不到 —— 这是这一版唯一稳定过的做法，别再改回「锁外层」。
 */
@Composable
private fun ImageBlock(
    versions: List<Ver>,
    lib: Library,
    vPager: PagerState,
    bg: BgMode,
    onBgChange: (BgMode) -> Unit,
    onOpenViewer: () -> Unit,
) {
    if (versions.isEmpty()) return
    val screenH = LocalConfiguration.current.screenHeightDp.dp
    // 图高上限 440dp（R4）：整页滚动的方案下，图再高就会把下面的字段压成一条缝。
    // 横图按比例算出来可能只有两三百，竖图最多到 440，两种都不撑满整屏。
    val maxH = minOf(440.dp, screenH * 0.58f)
    // 图高下限（2026-11-20 用户拍板）：11213×3653 这种超宽图按比例只有 130dp，
    // 文件信息条 + 底衬切换条几乎把它整条盖住。
    // 试过低于下限就裁左右（Crop）——能看到画中间，但两边的构图直接没了，用户否掉。
    // 现在的做法：容器给到 200dp，图仍整张 Fit 居中，多出来的上下是衬底。
    // 图本身没变大（宽度是瓶颈），换来的是两条浮层不再压在画上。
    val minH = 200.dp
    val scope = rememberCoroutineScope()

    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(top = 46.dp),
    ) {
        // 高度按主图算：一个槽位可能混横竖图
        val ref = versions.firstOrNull { it.isPrimary } ?: versions.firstOrNull()
        val ratio = if (ref != null && ref.w > 0 && ref.h > 0) ref.w.toFloat() / ref.h else 0.72f
        val h = minOf(maxWidth / ratio, maxH).coerceAtLeast(minOf(minH, maxH))

        Box(
            Modifier
                .fillMaxWidth()
                .height(h)
                .clipToBounds(),
        ) {
            // userScrollEnabled = false：滚动由手势层推，避免和外层 Pager 抢
            HorizontalPager(
                state = vPager,
                userScrollEnabled = false,
                // 预组合前后各一页：翻页时新图已经解好，不会翻到一半还是空白
                beyondViewportPageCount = 1,
                modifier = Modifier.fillMaxSize(),
            ) { i ->
                // 必须显式包一层 Box：Pager 的 page 里放两个子节点时，LazyLayout 只保证第一个占满，
                // 第二个会被摆到看不见的地方（透明底「只有棋盘格没有图」就是踩的这个）
                Box(Modifier.fillMaxSize()) {
                    val ver = versions.getOrNull(i)
                    BgLayer(bg)
                    if (bg == BgMode.Black) AmbientGlow()
                    ProgressiveArtImage(
                        ver = ver,
                        lib = lib,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }

            // 手势层：横向 = 推 Pager 位移；竖向不消费；无位移 = 单击进看图
            Box(
                Modifier
                    .matchParentSize()
                    .pointerInput(versions.size) {
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false)
                            val startPage = vPager.currentPage
                            var dx = 0f
                            var dy = 0f
                            var horizontal = false
                            do {
                                val ev = awaitPointerEvent()
                                val ch = ev.changes.firstOrNull() ?: continue
                                val pdx = ch.position.x - ch.previousPosition.x
                                val pdy = ch.position.y - ch.previousPosition.y
                                dx += pdx
                                dy += pdy
                                if (!horizontal &&
                                    kotlin.math.abs(dx) > viewConfiguration.touchSlop &&
                                    kotlin.math.abs(dx) > kotlin.math.abs(dy)
                                ) {
                                    horizontal = true
                                }
                                if (horizontal) {
                                    // consume：外层「切稿件」的 Pager 收不到，不会翻错
                                    ch.consume()
                                    if (versions.size > 1) vPager.dispatchRawDelta(-pdx)
                                }
                            } while (ev.changes.any { it.pressed })

                            val slop = viewConfiguration.touchSlop
                            when {
                                horizontal && versions.size > 1 -> {
                                    // 只要认定横向就按方向翻（不做"拖够 1/3 才翻"的门槛，回弹是负反馈）
                                    val towardNext = if (dx < 0) 1 else -1
                                    val target = (startPage + towardNext)
                                        .coerceIn(0, versions.lastIndex)
                                    // AlwaysAnimate：松手后「滑到目标页」这一下必须真的滑过去。
                                    // 系统关掉动画时这里会瞬间跳页，看起来像"一松手图就换了"，
                                    // 分不清是自己划过头还是翻了页（2026-11-21 用户真机确认）。
                                    scope.launch(AlwaysAnimate) {
                                        vPager.animateScrollToPage(
                                            target,
                                            animationSpec = tween(
                                                durationMillis = 280,
                                                easing = FastOutSlowInEasing,
                                            ),
                                        )
                                    }
                                }
                                kotlin.math.abs(dx) < slop && kotlin.math.abs(dy) < slop ->
                                    onOpenViewer()
                            }
                        }
                    },
            )

            // 下面两个浮层必须声明在手势层**之后**：同层兄弟里后声明的在上层，才收得到点击。
            // 早先试过把它们放在手势层之前，结果点不动（事件被手势层整块接走了）。
            // 它们各自只占一小块，不 matchParentSize —— 尺寸小就不会挡住图区的点击和翻页。
            FileChip(
                v = versions.getOrNull(vPager.currentPage),
                lib = lib,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 12.dp, bottom = 12.dp),
            )
            BgSwitchCompact(
                bg = bg,
                onChange = onBgChange,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 12.dp, bottom = 12.dp),
            )
        }
    }
}

/**
 * 本进程内「这张原图已经成功显示过一次」的 blob 集合。
 *
 * 为什么需要它：Root 的 `when` **只渲染一个分支**，进编辑页 / 版本管理时详情页整个离开组合，
 * 回来时 `ProgressiveArtImage` 重建、`fullReady` 归零 —— 于是又走一遍
 * 「缩略图打底 → 原图淡入 160ms」，视觉上就是「保存完出来闪一下」（先看到一张模糊的小图）。
 *
 * 而这时 Coil 的内存缓存里明明已经有这张原图了，再垫一次缩略图纯粹是自己给自己造一帧不同的画面。
 *
 * 只记 blob（不含 ext）：同一张图不同版本共用同一 blob 的情形本来就少，
 * 而且这个集合只用来决定「要不要垫缩略图」，判断错了最多多闪一次，不会出错图。
 * 用并发 set：图片加载回调可能在 IO 线程。
 */
private object LoadedOnce {
    private val seen: MutableSet<String> = java.util.Collections.newSetFromMap(
        java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    )

    fun mark(blob: String?) {
        if (!blob.isNullOrBlank()) seen.add(blob)
    }

    fun has(blob: String?): Boolean = !blob.isNullOrBlank() && seen.contains(blob)
}

/**
 * 渐进加载图：缩略图打底 → 屏幕级原图 → （放大时）高清整图，两层淡入叠加。
 *
 * 为什么是「叠一张更大的整图」而不是「解一块区域」：
 * `ImageDecoder.setCrop` 裁的是**解码输出**（官方文档原话："crops the output"），
 * 拿源坐标去填会越界抛 `IllegalStateException`；真想只解一块得用 `BitmapRegionDecoder`。
 * 而我们其实不需要区域 —— 手机一次只看一屏，按倍率把整图解大一点，
 * 几何与原来完全一致（同样 Fit、同一个 Box），没有坐标系、没有接缝、没有偏移对齐。
 *
 * [hdScale] 由看图浮层按缩放档位给（详情页固定 1f，不参与）。
 * 请求尺寸**永不超过源图分辨率**：超过就是插值出来的假清晰，只会白占内存。
 */
@Composable
internal fun ProgressiveArtImage(
    ver: Ver?,
    lib: Library,
    modifier: Modifier = Modifier,
    hdScale: Float = 1f,
) {
    val ctx = LocalContext.current
    val thumbFile = remember(ver?.blob) { ver?.let { lib.thumb(it) } }
    val fullFile = remember(ver?.blob, ver?.ext) { ver?.let { lib.image(it) } }
    val hasDistinctThumb = thumbFile != null && fullFile != null && thumbFile != fullFile && thumbFile.isFile
    // 本进程内已经显示过这张原图 → 直接从 alpha=1 起，不垫缩略图。
    // 这一行专门治「保存完从编辑页出来闪一下」（见 LoadedOnce 的注释）。
    var fullReady by remember(ver?.blob, ver?.ext) { mutableStateOf(LoadedOnce.has(ver?.blob)) }
    val fullAlpha by animateFloatAsState(
        targetValue = if (!hasDistinctThumb || fullReady) 1f else 0f,
        animationSpec = tween(durationMillis = 160),
        label = "fullImageFade",
    )

    var box by remember { mutableStateOf(IntSize.Zero) }

    // 高清层的请求尺寸。三个约束：
    // 1. 底图已经是「box 尺寸」，所以只有当高清请求比 box 明显大（>1.1 倍）才值得解一次；
    // 2. 长边封顶 4096（再大就不是手机的像素了，纯烧内存）；
    // 3. 不超过源图本身的分辨率（超过等于让 Coil 帮我插值）。
    val hdSize = remember(ver?.w, ver?.h, box, hdScale) {
        val srcW = ver?.w ?: 0
        val srcH = ver?.h ?: 0
        if (hdScale <= 1.05f || box.width <= 0 || box.height <= 0 || srcW <= 0 || srcH <= 0) {
            null
        } else {
            val fit = min(box.width.toFloat() / srcW, box.height.toFloat() / srcH)
            val need = fit * hdScale
            var w = min((srcW * need).roundToInt(), srcW).coerceAtLeast(1)
            var h = min((srcH * need).roundToInt(), srcH).coerceAtLeast(1)
            val edge = max(w, h)
            if (edge > HD_MAX_EDGE) {
                val k = HD_MAX_EDGE.toFloat() / edge
                w = (w * k).roundToInt().coerceAtLeast(1)
                h = (h * k).roundToInt().coerceAtLeast(1)
            }
            // 字节兜底：3000×4000 的源图按 1:1 解出来是 48MB，一张就够呛，
            // 而它上面还压着底图与缩略图。超了就按面积等比缩，宁可少一点清晰度也不冒 OOM。
            val bytes = w.toLong() * h.toLong() * 4L
            if (bytes > HD_MAX_BYTES) {
                val k = kotlin.math.sqrt(HD_MAX_BYTES.toDouble() / bytes).toFloat()
                w = (w * k).roundToInt().coerceAtLeast(1)
                h = (h * k).roundToInt().coerceAtLeast(1)
            }
            val gain = max(w.toFloat() / box.width, h.toFloat() / box.height)
            if (gain <= 1.1f) null else w to h
        }
    }
    val hdReq = remember(hdSize, fullFile) {
        val (w, h) = hdSize ?: return@remember null
        ImageRequest.Builder(ctx).data(fullFile).size(w, h).build()
    }
    // 换档位时旧的高清层会被换掉，会有一瞬回到屏幕级 —— 档位是离散的（一次缩放最多换 2–3 次），
    // 且叠在下面的始终是同一张原图，不会白屏。
    var hdReady by remember(hdReq) { mutableStateOf(false) }
    val hdAlpha by animateFloatAsState(
        targetValue = if (hdReady) 1f else 0f,
        animationSpec = tween(durationMillis = 120),
        label = "hdImageFade",
    )

    Box(modifier.onSizeChanged { box = it }) {
        if (hasDistinctThumb && fullAlpha < 0.99f) {
            AsyncImage(
                model = thumbFile,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        }
        AsyncImage(
            model = fullFile,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize().alpha(fullAlpha),
            onSuccess = {
                fullReady = true
                LoadedOnce.mark(ver?.blob)
            },
            onError = {
                android.util.Log.w(
                    "GaoCangPerf",
                    "img fail blob=${ver?.blob?.take(10)} ext=${ver?.ext} err=${it.result.throwable}",
                )
            },
        )
        if (hdReq != null) {
            AsyncImage(
                model = hdReq,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().alpha(hdAlpha),
                onSuccess = { hdReady = true },
                // 高清层解不出来就静默退回屏幕级 —— 但**要留一行日志**，
                // 上一版就是因为整条链路全是静默失败，才拖了半天才发现（见 2026-11-21 记录）。
                onError = {
                    android.util.Log.w(
                        "GaoCangPerf",
                        "hd fail blob=${ver?.blob?.take(8)} size=$hdSize err=${it.result.throwable}",
                    )
                },
            )
        }
    }
}

/** 高清层长边上限。4096 已经覆盖 1224 宽视口放到 3.3x，再大纯烧内存。 */
private const val HD_MAX_EDGE = 4096

/** 单张高清层的字节上限。底图 + 缩略图还叠在上面，留够余量。 */
private const val HD_MAX_BYTES = 24L * 1024L * 1024L

/**
 * 图内左下角的文件信息胶囊：`PNG · 1250×900 · 2.33 MB`。
 *
 * 以前是图片下方独立一行（三个带标题的小格），占 56dp 高度但信息就三个值。
 * 压成一颗胶囊贴在图上，省掉整行，同时也让「这张图什么格式」和图片本身挨着。
 */
@Composable
private fun FileChip(v: Ver?, lib: Library, modifier: Modifier = Modifier) {
    val sizeText by produceState("", v?.blob) {
        value = withContext(Dispatchers.IO) {
            val n = v?.let { lib.blob(it)?.length() } ?: 0L
            when {
                n <= 0 -> ""
                n >= 1024 * 1024 -> String.format("%.2f MB", n / 1048576.0)
                else -> "${n / 1024} KB"
            }
        }
    }
    val parts = buildList {
        v?.ext?.uppercase()?.takeIf { it.isNotBlank() }?.let { add(it) }
        if (v != null && v.w > 0) add("${v.w}×${v.h}")
        if (sizeText.isNotBlank()) add(sizeText)
    }
    if (parts.isEmpty()) return
    Text(
        parts.joinToString(" · "),
        style = T.num,
        color = Paper,
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 5.dp),
    )
}

/**
 * 图内右下角的底衬切换。三个选项挤在一颗胶囊里，选中项用实心 Paper。
 *
 * 为什么能浮在图上：它在手势层之后声明（上层），且尺寸只有内容那么大，
 * 所以既收得到点击，也不会把整块图区的点击/翻页吃掉。
 */
@Composable
private fun BgSwitchCompact(bg: BgMode, onChange: (BgMode) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(50))
            .padding(2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        BgMode.entries.forEach { m ->
            val on = m == bg
            Text(
                bgName(m),
                style = T.micro,
                color = if (on) Ink else Paper.copy(alpha = 0.8f),
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(if (on) Paper else Color.Transparent)
                    .clickable { onChange(m) }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }
    }
}

/** 底衬：棋盘 / 黑 / 白。透明图才需要棋盘，非透明图三者在视觉上差别不大，但保持一致更好 */
@Composable
internal fun BgLayer(bg: BgMode) {
    when (bg) {
        BgMode.Checker -> CheckerBoard(cell = 12.dp)
        BgMode.Black -> Box(Modifier.fillMaxSize().background(Color(0xFF000000)))
        BgMode.White -> Box(Modifier.fillMaxSize().background(Color(0xFFFFFFFF)))
    }
}

/**
 * 黑底环境光晕：固定双色、透明度 ≤ 8%，不取色。
 * - 只画在 BgMode.Black：棋盘是判读透明底的尺子，白底同理，压一层琥珀就偏色。
 * - 只在图片 Fit 之后的留白里可见（图片矩形不透明会盖住它）：竖图看两侧、横图看上下。
 *   别为了「图上看不到光晕」去把图弄半透明，那是另一个设计。
 */
@Composable
private fun AmbientGlow() {
    Box(
        Modifier.fillMaxSize().drawBehind {
            drawRect(
                Brush.radialGradient(
                    colors = listOf(Accent.copy(alpha = 0.07f), Color.Transparent),
                    center = Offset(size.width * 0.5f, size.height * 0.22f),
                    radius = size.height * 0.7f,
                ),
            )
            drawRect(
                Brush.radialGradient(
                    colors = listOf(Color(0xFF6E8FE0).copy(alpha = 0.06f), Color.Transparent),
                    center = Offset(size.width * 0.18f, size.height * 0.55f),
                    radius = size.height * 0.5f,
                ),
            )
        },
    )
}

/**
 * 版本条（R4）：一排带名字的 chip + 「＋」。版本管理入口在底部操作条的「版本」。
 *
 * 为什么用名字而不是缩略图：版本本来就叫「带背景 / 透明底 / 线稿」，
 * 名字好扫、只占一行；缩略图条要 48dp 高，且小图看不清差别。
 */
@Composable
private fun VersionStrip(
    versions: List<Ver>,
    current: Int,
    onSelect: (Int) -> Unit,
    onAdd: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier
                .weight(1f)
                // 容器外观：只在链头加背景，nestedScroll / horizontalScroll 原样在后面
                .clip(RoundedCornerShape(12.dp))
                .background(CardBg)
                .padding(4.dp)
                // nestedScroll 放在 horizontalScroll **外层**，才能收到它在边界之后剩下的位移。
                // 不加这层：chip 滑到头，剩余的横向位移会冒泡给外层 HorizontalPager，
                // 于是变成「切上一张/下一张稿件」——版本条滑到底就翻页。
                .nestedScroll(
                    remember {
                        object : NestedScrollConnection {
                            override fun onPostScroll(
                                consumed: Offset,
                                available: Offset,
                                source: NestedScrollSource,
                            ): Offset =
                                // 横向剩多少就吃多少；纵向一律放行，别影响整页上下滚
                                if (available.x != 0f) Offset(available.x, 0f) else Offset.Zero
                        }
                    },
                )
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            versions.forEachIndexed { i, v ->
                val on = i == current
                Row(
                    Modifier
                        .clip(RoundedCornerShape(50))
                        .background(if (on) AccentC else CardBg)
                        .then(
                            if (on) Modifier.border(1.dp, Accent.copy(alpha = 0.3f), RoundedCornerShape(50))
                            else Modifier,
                        )
                        .clickable { onSelect(i) }
                        // 27dp 高的 chip 太窄：横滑版本时手指很容易点到相邻那颗（2026-11-21 用户反馈）。
                        // 字号升一档、内边距加大到 35dp 高，间距也拉开到 8dp。
                        .padding(horizontal = 14.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    // ★ = 主图。主图是卡片上显示的那张，值得标出来
                    if (v.isPrimary) {
                        Text("★", style = T.label, color = if (on) Accent else Muted)
                    }
                    Text(
                        v.label.ifBlank { "版本 ${i + 1}" },
                        style = T.label,
                        color = if (on) Accent else OnMid,
                        maxLines = 1,
                    )
                }
            }
        }
        Spacer(Modifier.width(Space.s))
        // ＋：加版本。放在固定位置而不是 chip 末尾的 `+`——
        // 版本多了 chip 要横向滚，藏在末尾就得先滚到最右才找得到。
        Box(
            Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(CardBg)
                .clickable(onClick = onAdd),
            contentAlignment = Alignment.Center,
        ) {
            Text("＋", style = T.titleSm, color = Accent)
        }
    }
}
