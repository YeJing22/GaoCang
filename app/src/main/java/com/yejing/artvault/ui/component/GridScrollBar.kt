package com.yejing.artvault.ui.component

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.yejing.artvault.Accent
import com.yejing.artvault.CHigh
import com.yejing.artvault.Paper
import com.yejing.artvault.T
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/** 视觉条宽：闲置 4dp（只是「这里有根条」的提示），滚动中 8dp，按住拖动 11dp */
private val BAR_W = 4.dp
private val BAR_W_ACTIVE = 8.dp
private val BAR_W_DRAG = 11.dp

/** 触摸热区：26dp。比视觉条宽得多，手指不用精确瞄准 */
private val TOUCH_W = 26.dp

/** 条最短 36dp：1780 件的库按比例算出来只有几像素，短到抓不住也看不见 */
private val MIN_THUMB = 36.dp

/** 抓取宽容：thumb 上下各多给 22dp，按在条边缘附近也能拖 */
private val HOT = 22.dp

/**
 * 当前滚到哪儿（0..1）：首项下标 + 首项被推出视口的比例。
 *
 * 为什么不用「已滚像素」：那要先知道总内容高，lazy 容器不给；拿首末可见项反推又会被
 * contentPadding 和不等高卡片带偏。下标 + 首项推出比例，滚到底正好是 1，
 * 和拖拽映射严格互逆，不会越拖越偏。
 */
private fun progressOf(
    total: Int,
    visible: Int,
    firstIndex: Int,
    firstOffsetY: Int,
    firstSize: Int,
    viewportStart: Int,
): Float {
    val maxFirst = (total - visible).coerceAtLeast(1)
    val frac = if (firstSize > 0) (viewportStart - firstOffsetY).toFloat() / firstSize else 0f
    return ((firstIndex + frac) / maxFirst).coerceIn(0f, 1f)
}

private fun LazyGridState.progressNow(): Float {
    val info = layoutInfo
    val f = info.visibleItemsInfo.firstOrNull()
    return progressOf(
        total = info.totalItemsCount,
        visible = info.visibleItemsInfo.size,
        firstIndex = f?.index ?: 0,
        firstOffsetY = f?.offset?.y ?: 0,
        firstSize = f?.size?.height ?: 0,
        viewportStart = info.viewportStartOffset,
    )
}

private fun LazyListState.progressNow(): Float {
    val info = layoutInfo
    val f = info.visibleItemsInfo.firstOrNull()
    return progressOf(
        total = info.totalItemsCount,
        visible = info.visibleItemsInfo.size,
        firstIndex = f?.index ?: 0,
        firstOffsetY = f?.offset ?: 0,
        firstSize = f?.size ?: 0,
        viewportStart = info.viewportStartOffset,
    )
}

private fun LazyGridState.maxFirstNow(): Int {
    val info = layoutInfo
    return (info.totalItemsCount - info.visibleItemsInfo.size).coerceAtLeast(0)
}

private fun LazyListState.maxFirstNow(): Int {
    val info = layoutInfo
    return (info.totalItemsCount - info.visibleItemsInfo.size).coerceAtLeast(0)
}

/**
 * 网格右侧的细滚动条（`LazyVerticalGrid` 版）。
 *
 * 容器高度**由调用方决定**：稿库那根要扣掉底部导航条的高度，否则拖到底时 thumb
 * 会沉到导航面板后面看不见 —— 这是用户实际报的问题。
 *
 * 实际绘制与手势在 [ScrollBarCore]，网格与列表只是把状态翻译成同一组数字。
 */
@Composable
fun GridScrollBar(
    state: LazyGridState,
    modifier: Modifier = Modifier,
    /** 计数单位：稿件是「件」，别的列表可以换成「个」 */
    unit: String = "件",
) {
    ScrollBarCore(
        total = state.layoutInfo.totalItemsCount,
        visible = state.layoutInfo.visibleItemsInfo.size,
        scrolling = state.isScrollInProgress,
        progress = { state.progressNow() },
        maxFirst = { state.maxFirstNow() },
        scrollTo = { state.requestScrollToItem(it) },
        modifier = modifier,
        unit = unit,
    )
}

/**
 * 同一根滚动条的 `LazyColumn` 版（2026-11-19 稿库换容器后需要）。
 *
 * [bubbleText] 决定拖动时气泡里显示什么：给 null 就是「第 N / M 件」；
 * 稿库传「下标 → 月份」，于是拖到哪个月就显示哪个月 —— 这是用户明确要的交互，
 * 比「第 613 件」有用得多。
 */
@Composable
fun ListScrollBar(
    state: LazyListState,
    modifier: Modifier = Modifier,
    unit: String = "件",
    /** 从「若滚到这里」的首项下标换一句人话；null = 用默认的序号文案 */
    bubbleText: ((Int) -> String)? = null,
    /**
     * 拖选进行中传 false。
     *
     * 这里要的是**状态对象**而不是 Boolean：Boolean 得靠重组传进来，而拖选时主线程正忙着写选择集，
     * 重组会被推后——手指第二根落下的那一瞬间读到的还是旧值 true。直接持有 `MutableState`
     * 就没有这个传播延迟，手势协程里 `.value` 立即能看到最新值。
     */
    enabledState: State<Boolean>? = null,
) {
    ScrollBarCore(
        total = state.layoutInfo.totalItemsCount,
        visible = state.layoutInfo.visibleItemsInfo.size,
        scrolling = state.isScrollInProgress,
        progress = { state.progressNow() },
        maxFirst = { state.maxFirstNow() },
        scrollTo = { state.requestScrollToItem(it) },
        bubbleText = bubbleText,
        enabledState = enabledState,
        modifier = modifier,
        unit = unit,
    )
}

/**
 * 滚动条本体：位置指示 + 可拖拽跳转 + 拖动气泡。两种 lazy 容器共用。
 *
 * 几处刻意的选择：
 *
 * 1. **位置比例按「项下标」算，不按「已滚像素」**：见 [progressOf] 的注释。
 * 2. **拖拽直接 `scrollToItem(index)`，不用 `scrollBy` 累积**：`scrollBy` 到列表尽头
 *    `scrolled == 0` 仍空转，且拖动中重排会让 dy 和手指脱节（11-17 踩过，写进了 AGENTS）。
 *    拖动中 thumb 位置用 `dragProgress` 跟手，不等 state 回报，所以跳转有半帧延迟也不脱节。
 * 3. **只有按在条附近才接管手势**：热区通高 26dp，若整条都消费，右边缘那列卡片就点不动了。
 *    不在附近就 return 不消费，事件正常落到下面的列表。
 * 4. **不足一屏直接不画**：筛选后只剩三件还杵一根满高的条，会让人以为列表很长。
 * 5. **手势协程的 key 不含 total / visible**：拖动中可见项数会变，key 一变
 *    `pointerInput` 就重启，手势会被当场取消（拖到一半松手的错觉）。
 * 6. **「手柄」靠宽度变化表达，不靠图标**：闲置 4dp（只当作"这里有根条"的提示，alpha 0.22），
 *    滚动时胀到 8dp 并画三条抓握纹，按住再胀到 11dp。
 */
@Composable
private fun ScrollBarCore(
    total: Int,
    visible: Int,
    scrolling: Boolean,
    progress: () -> Float,
    maxFirst: () -> Int,
    /** 非 suspend：用 `requestScrollToItem`，它不走滚动 mutex，所以不会被用户手势顶掉 */
    scrollTo: (Int) -> Unit,
    modifier: Modifier = Modifier,
    unit: String = "件",
    bubbleText: ((Int) -> String)? = null,
    /** 拖选进行中传 false：此时滑动应该由拖选的自动滚动负责，滚动条介入会让「滚了但选区不跟」 */
    enabledState: State<Boolean>? = null,
) {
    // 不足一屏 = 没有可滚的内容
    val usable = total > visible && visible > 0

        // 没有外部状态时恒可用。用 remember 挂一个常量 state，避免在手势里做分支判断。
    val alwaysEnabled = remember { mutableStateOf(true) }
    val enabled: State<Boolean> = enabledState ?: alwaysEnabled
    var hPx by remember { mutableIntStateOf(0) }
    var dragProgress by remember { mutableStateOf<Float?>(null) }
    var dragging by remember { mutableStateOf(false) }
    var lit by remember { mutableStateOf(false) }

    // 「亮」= 正在滚（含 fling）/ 正在拖；停手 1.5s 后淡回贴身提示。
    // 用 isScrollInProgress 而不是自己记「最后一次滚动时间」：后者要么每帧重组，要么得自己防抖。
    LaunchedEffect(scrolling, dragging) {
        if (scrolling || dragging) {
            lit = true
        } else {
            delay(1500)
            lit = false
        }
    }
    // 禁用发生时手势不会重启（key 里没有 enabled），得自己把拖动态收掉，
    // 否则 thumb 会卡在抓握态一直显示那三道纹。
    LaunchedEffect(enabled.value) {
        if (!enabled.value) {
            dragging = false
            dragProgress = null
        }
    }
    val alpha by animateFloatAsState(if (lit) 1f else 0.22f, tween(240))
    val barWd by animateFloatAsState(
        when {
            dragging -> BAR_W_DRAG.value
            lit -> BAR_W_ACTIVE.value
            else -> BAR_W.value
        },
        tween(180),
    )

    val ratio = if (total > 0) visible.toFloat() / total else 1f
    val curProgress = dragProgress ?: progress()
    val maxFirstIdx = (total - visible).coerceAtLeast(1)

    if (!usable) return

    val density = LocalDensity.current
    val minThumb = with(density) { MIN_THUMB.toPx() }
    val hot = with(density) { HOT.toPx() }

    /** 容器高 + 位置 → thumb 的 top 与长度（px）。绘制和拖拽共用这一份 */
    fun geom(h: Float, p: Float): Pair<Float, Float> {
        val len = (h * ratio).coerceIn(minThumb.coerceAtMost(h * 0.9f), h)
        return (h - len) * p.coerceIn(0f, 1f) to len
    }

    Box(modifier.fillMaxHeight(), contentAlignment = Alignment.CenterEnd) {
        Box(
            Modifier
                .fillMaxHeight()
                .width(TOUCH_W)
                .onSizeChanged { hPx = it.height }
                // key 只用 usable：total/visible/hPx 都不能进 key，否则拖动中重启手势
                .pointerInput(usable) {
                    if (!usable) return@pointerInput
                    // 不用 awaitEachGesture：它在 block 结束（包括「按下点不在条上」这种早退）之后会
                    // awaitAllPointersUp()，也就是等**所有**手指抬起才接受下一次手势。
                    // 拖选时第一根手指正按在列表上，滚动条就一直卡在那儿，第二根手指按上来毫无反应
                    // —— 这就是「同时拖选又拖滚动条，条不动」的真正原因，跟多点触摸本身无关。
                    // 这里自己循环：每个新落下的手指独立判断，不在条上就不消费、继续等下一个。
                    try {
                    awaitPointerEventScope {
                        while (true) {
                            val ev0 = awaitPointerEvent()
                            // 注意：这里的 changes 只有**命中本节点**的指针。落在列表上的那根手指是看不见的，
                            // 所以不能用「数手指数」判断多点 —— 那是上一版失效的原因。
                            // 多点的拦截放在页面级（BrowseScreen 的 matchParentSize 层）。
                            val down = ev0.changes.firstOrNull { !it.previousPressed && it.pressed }
                                ?: continue
                            val h = size.height.toFloat()
                            if (h <= 0f) continue

                            val len0 = run {
                                val r = if (total > 0) visible.toFloat() / total else 1f
                                (h * r).coerceIn(minThumb.coerceAtMost(h * 0.9f), h)
                            }
                            val top0 = (h - len0) * progress()

                            // 不在条附近：不消费，事件留给下面的列表
                            if (down.position.y < top0 - hot || down.position.y > top0 + len0 + hot) {
                                continue
                            }
                            // 禁用时**绝不能 consume**。
                            // detectDragGesturesAfterLongPress 在拖动阶段只要看到任何一个 change 被消费，
                            // 就判定手势被抢走 → 立刻 onDragCancel。吃掉这根手指的 down，
                            // 等于把正在进行的拖选当场终止（日志里 dragStop 紧跟 barDown 十几毫秒，就是它）。
                            // 不消费也不会漏给下面的列表：滚动条与列表是同层兄弟、它在更上层，
                            // 这块 26dp 区域只有滚动条进命中路径。
                            if (!enabled.value) {
                                android.util.Log.d("GaoCangGesture", "barDown blocked(dragging)")
                                continue
                            }
                            down.consume()
                            android.util.Log.d("GaoCangGesture", "barDown take")
                            dragging = true
                            // 按在 thumb 上就保持相对位置，按在边缘就把 thumb 拽到手指处
                            val grab = (down.position.y - top0).coerceIn(0f, len0)
                            val room = (h - len0).coerceAtLeast(1f)

                            fun apply(y: Float): Int {
                                val p = ((y - grab) / room).coerceIn(0f, 1f)
                                dragProgress = p
                                return (p * maxFirst()).roundToInt()
                                    .coerceIn(0, (total - 1).coerceAtLeast(0))
                            }

                            try {
                                var lastIdx = apply(down.position.y)
                                // 第一下也要真的滚过去（点条跳一大段）
                                scrollTo(lastIdx)

                                while (true) {
                                    val ev2 = awaitPointerEvent()
                                    val ch = ev2.changes.firstOrNull { it.id == down.id } ?: continue
                                    ch.consume()
                                    if (!ch.pressed) break
                                    val idx = apply(ch.position.y)
                                    if (idx != lastIdx) {
                                        lastIdx = idx
                                        scrollTo(idx)
                                    }
                                }
                            } finally {
                                // 手势被取消（比如拖选开始导致 enabled 变 false）时也要复位，
                                // 否则 thumb 会停在拖到的位置、一直显示抓握纹。
                                dragging = false
                                dragProgress = null
                            }
                        }
                    }
                    } finally {
                        // 循环如果退出，之后这根条就再也不响应了 —— 这条日志是用来抓这种情况的
                        android.util.Log.d("GaoCangGesture", "barGestureLoopExit")
                    }
                },
        ) {
            Canvas(Modifier.fillMaxSize()) {
                if (hPx <= 0) return@Canvas
                val w = barWd.dp.toPx()
                val (top, len) = geom(size.height, curProgress)
                drawRoundRect(
                    color = (if (lit) Accent else Paper)
                        .copy(alpha = alpha * if (lit) 0.92f else 0.6f),
                    topLeft = Offset(size.width - w, top),
                    size = Size(w, len),
                    cornerRadius = CornerRadius(w / 2, w / 2),
                )
                // 抓握纹：条够宽够长时才画。窄条上画三条线会糊成一坨，反而更脏
                if (w > 7.dp.toPx() && len > 34.dp.toPx()) {
                    val ink = Color.White.copy(alpha = 0.5f * alpha)
                    val gw = 3.5.dp.toPx()
                    val gh = 1.5.dp.toPx()
                    val gap = 2.6.dp.toPx()
                    val cx = size.width - w / 2
                    val cy = top + len / 2
                    for (i in -1..1) {
                        val y = cy + i * (gh + gap)
                        drawRoundRect(
                            color = ink,
                            topLeft = Offset(cx - gw / 2, y - gh / 2),
                            size = Size(gw, gh),
                            cornerRadius = CornerRadius(gh / 2, gh / 2),
                        )
                    }
                }
            }
        }

        // 拖动时才出现的气泡。默认报序号；稿库传了 bubbleText 就报月份。
        val p = dragProgress
        if (p != null && hPx > 0) {
            val (top, len) = geom(hPx.toFloat(), p)
            val center = top + len / 2
            val idx = (p * maxFirstIdx).roundToInt().coerceIn(0, (total - 1).coerceAtLeast(0))
            val bubbleH = 38.dp
            val y = with(density) {
                (center - bubbleH.toPx() / 2).coerceIn(0f, (hPx - bubbleH.toPx()).coerceAtLeast(0f)).toDp()
            }
            // 映射查不到（越界）时退回序号文案，别弹一个空气泡
            val label = bubbleText?.invoke(idx)
            Text(
                if (label.isNullOrBlank()) "第 ${idx + 1} / $total $unit" else label,
                style = T.numLg,
                color = Paper,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(y = y)
                    .padding(end = TOUCH_W + 10.dp)
                    .background(CHigh, RoundedCornerShape(12.dp))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
    }
}
