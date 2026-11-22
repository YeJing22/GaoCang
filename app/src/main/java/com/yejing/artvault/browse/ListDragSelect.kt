package com.yejing.artvault.browse

import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.abs

/**
 * 拖动连选，给 `LazyColumn` 用。
 *
 * 移植自 `jordond/drag-select-compose` 的 `gridDragSelect`（它只支持 LazyGrid）。
 * 五段逻辑逐条对应踩过的坑，**别自己重新设计**：
 *
 * 1. 用 `detectDragGesturesAfterLongPress`，不自己计时 —— 它内部已处理「长按超时前移动超 touchSlop 就取消」，
 *    当年自研失败就是「起手立即生效」。
 * 2. 命中靠**行矩形 + 列宽除**，不是只比 y —— 只比 y 会导致整行误选（漏 x 轴）。
 * 3. 行号用 `key → row` 映射取，**不能用 lazy index**：月标头也占一个 index，一插进去就整体错位。
 * 4. 拖出末行继续选到最后一张，不是断掉。
 * 5. 自动滚按越界距离线性加速，每 10ms `scrollBy`（不能 animate，要被手指持续覆盖）。
 *
 * 回调给的是**扁平下标** `flat = row * cellCount + col`，选中集合的算法留在页面里
 * （见 `Selection.dragTo`），这样这个 Modifier 不关心你的数据结构。
 */
fun Modifier.listDragSelect(
    state: LazyListState,
    cellCount: Int,
    isRowKey: (Any?) -> Boolean,
    rowOfKey: (Any?) -> Int?,
    rowCountOf: () -> Int,
    /** 内容左内边距（px）与单格宽度（px）：LazyListItemInfo 只给高度，宽度得调用方算。 */
    padLeftPx: Float,
    cellWidthPx: Float,
    onStart: (flat: Int) -> Unit,
    onUpdate: (flat: Int) -> Unit,
    onStop: () -> Unit,
    enableAutoScroll: Boolean = true,
    scrollThresholdPx: Float = 140f,
): Modifier = composed {
    val haptics = LocalHapticFeedback.current
    val currentOnStart by rememberUpdatedState(onStart)
    val currentOnUpdate by rememberUpdatedState(onUpdate)
    val currentOnStop by rememberUpdatedState(onStop)
    val currentRowOfKey by rememberUpdatedState(rowOfKey)
    val currentIsRowKey by rememberUpdatedState(isRowKey)
    val currentRowCount by rememberUpdatedState(rowCountOf)

    var autoScroll by remember { mutableFloatStateOf(0f) }

    // ⑤ 自动滚：速度由手指到边缘的距离算，手指不动也持续滚
    if (enableAutoScroll) {
        LaunchedEffect(autoScroll) {
            if (autoScroll == 0f) return@LaunchedEffect
            while (isActive) {
                state.scrollBy(autoScroll)
                delay(10)
            }
        }
    }

    /** 命中：先按「行」的矩形找行，再用列宽算第几列。标头不是 r: 开头的 key，直接跳过。 */
    fun hit(point: Offset): Int? {
        // 行只按 y 判定（行铺满整宽）；列用「扣掉左内边距 ÷ 单格宽」算。
        // 只比 y 不看 x 就是当年「整行误选」的原因，别退回去。
        val info = state.layoutInfo.visibleItemsInfo.firstOrNull { item ->
            currentIsRowKey(item.key) &&
                point.y >= item.offset && point.y <= item.offset + item.size
        }
        if (info != null) {
            val row = currentRowOfKey(info.key) ?: return null
            val col = ((point.x - padLeftPx) / cellWidthPx).toInt().coerceIn(0, cellCount - 1)
            return row * cellCount + col
        }
        // 拖过最后一行：继续算到最后一张，而不是断掉
        val last = state.layoutInfo.visibleItemsInfo.lastOrNull() ?: return null
        if (currentIsRowKey(last.key) && point.y > last.offset + last.size) {
            val row = currentRowOfKey(last.key) ?: return null
            return row * cellCount + cellCount - 1
        }
        return null
    }

    fun speedFor(point: Offset): Float {
        val h = state.layoutInfo.viewportSize.height.toFloat()
        val fromTop = point.y
        val fromBottom = h - point.y
        return when {
            fromBottom < scrollThresholdPx -> scrollThresholdPx - fromBottom
            fromTop < scrollThresholdPx -> -(scrollThresholdPx - fromTop)
            else -> 0f
        }
    }

    pointerInput(Unit) {
        // ── 为什么不再用 detectDragGesturesAfterLongPress ──
        //
        // 它的拖动阶段走通用 `drag(pointerId)`，只认两件事：这一帧能不能拿到该 pointer、
        // 以及它的 change 有没有被标记 consumed。任一成立就返回 false → onDragCancel。
        // 实测症状：拖选进行中，第二根手指按下（落在另一个节点上）约 178ms 后，拖选被静默取消，
        // 而第一根手指根本没抬 —— 日志 `barDown blocked(dragging)` → 178ms → `dragCancel`。
        // 排查了六轮都修不掉，因为它把「谁消费了事件」当成「手势被抢」，
        // 而这个页面里能碰到事件的东西太多（scrollable、combinedClickable、卡片上的长按保护）。
        //
        // 现在只借它的**长按判定**：awaitLongPressOrCancellation 是官方公开 API，
        // 超时前滑出 touchSlop 会返回 null，手势自然让给列表滚动 —— 这正是当年自研没做好的部分。
        // 拖动循环自己写：只认第一根手指的 pointerId，只看它有没有抬起，**不看谁消费了事件**。
        awaitPointerEventScope {
            while (true) {
                val down = awaitFirstDown(requireUnconsumed = false)
                val primaryId = down.id

                // 长按没成立（提前滑走）就什么都不做，让列表去滚
                val longPress = awaitLongPressOrCancellation(primaryId) ?: continue

                val startFlat = hit(longPress.position) ?: continue
                // 起点落在末行补齐用的空格子上时没有对应稿件，不进入拖选
                if (startFlat / cellCount >= currentRowCount()) continue

                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                currentOnStart(startFlat)
                // 记下被锁定的那根手指：之后所有事件只认它，别的 id 一律作废
                android.util.Log.d("GaoCangGesture", "dragPrimary id=${primaryId.value}")
                autoScroll = 0f

                try {
                    while (true) {
                        // Initial pass：从外到内，先于 LazyColumn 的 scrollable 和卡片的
                        // combinedClickable。它们根本看不到这根手指，既不会触发点击，也抢不走滚动。
                        val ev = awaitPointerEvent(PointerEventPass.Initial)

                        // 其他手指一律作废：拖选期间第二根手指不产生任何效果
                        ev.changes.forEach { ch ->
                            if (ch.id != primaryId && ch.pressed) ch.consume()
                        }

                        val pc = ev.changes.firstOrNull { it.id == primaryId } ?: break

                        // **唯一的结束条件**：第一根手指抬起。
                        // 刻意不看 pc.isConsumed —— 那正是旧实现会自杀的地方。
                        if (!pc.pressed) {
                            pc.consume() // 吃掉抬手，避免卡片收到 onClick
                            break
                        }

                        hit(pc.position)?.let { currentOnUpdate(it) }
                        autoScroll = if (enableAutoScroll) speedFor(pc.position) else 0f
                        pc.consume()
                    }
                } finally {
                    autoScroll = 0f
                    android.util.Log.d("GaoCangGesture", "dragLoopExit")
                    currentOnStop()
                }
            }
        }
    }
}
