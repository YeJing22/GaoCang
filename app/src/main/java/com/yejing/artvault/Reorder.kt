package com.yejing.artvault

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import sh.calvin.reorderable.ReorderableLazyGridState
import sh.calvin.reorderable.ReorderableLazyListState
import sh.calvin.reorderable.rememberReorderableLazyGridState
import sh.calvin.reorderable.rememberReorderableLazyListState
import sh.calvin.reorderable.rememberScroller

/**
 * 拖动排序的统一入口：**角色网格**（2 列）和**版本列表**（单列）共用一套。
 *
 * 底层是 `sh.calvin.reorderable`，不是自研。这套手势自己写踩过两个坑，记在这里别再回去：
 *
 * 1. **自动滚动必须每帧只跑一次**。`ScrollScope.scrollBy` 是普通函数（不是挂起函数），
 *    `scroll { }` 也不等帧；`while (true)` 里再没有别的挂起点，就成了死循环把主线程吃干净
 *    —— 真 ANR 过一次（系统弹「应用无响应」）。
 * 2. **速度不能太大**。库默认每 100ms 滚「视口 × 0.05」，再乘一个 1~10 的「离边越近越快」
 *    倍率，贴边时能到 5 屏/秒，拖一下就飞出去。这里固定成视口 × 0.01（封顶约 1 屏/秒），
 *    实测手感正常，**不给页面暴露速度参数**，免得每个页面各调一套。
 *
 * 约定：onOrderChange 只负责**改内存顺序**，什么时候落盘由调用方决定
 * （角色是点「完成」、版本是松手即写）。所以这里是纯顺序回调，不碰文件。
 */
private const val SCROLL_RATE = 0.01f

/** 把 [from] 处搬到 [to]（中间元素整体让位） */
internal fun List<String>.moved(from: Int, to: Int): List<String> =
    if (from == to || from !in indices || to !in indices) this
    else toMutableList().apply { add(to, removeAt(from)) }

/** 角色网格用：2 列（或任意列数），纵向拖动 */
@Composable
fun rememberGridReorderState(
    gridState: LazyGridState,
    order: List<String>,
    onOrderChange: (List<String>) -> Unit,
): ReorderableLazyGridState {
    val scroller = rememberScroller(
        scrollableState = gridState,
        pixelAmountProvider = { gridState.layoutInfo.viewportSize.height * SCROLL_RATE },
    )
    // onMove 在拖动中被反复调用，必须读到「最新」的顺序和回调，不能用组合期捕获的旧值
    val latestOrder = rememberUpdatedState(order)
    val latestChange = rememberUpdatedState(onOrderChange)
    return rememberReorderableLazyGridState(gridState, scroller = scroller) { from, to ->
        latestChange.value(latestOrder.value.moved(from.index, to.index))
    }
}

/** 版本列表用：单列，纵向拖动 */
@Composable
fun rememberListReorderState(
    listState: LazyListState,
    order: List<String>,
    onOrderChange: (List<String>) -> Unit,
): ReorderableLazyListState {
    val scroller = rememberScroller(
        scrollableState = listState,
        pixelAmountProvider = { listState.layoutInfo.viewportSize.height * SCROLL_RATE },
    )
    val latestOrder = rememberUpdatedState(order)
    val latestChange = rememberUpdatedState(onOrderChange)
    return rememberReorderableLazyListState(listState, scroller = scroller) { from, to ->
        latestChange.value(latestOrder.value.moved(from.index, to.index))
    }
}
