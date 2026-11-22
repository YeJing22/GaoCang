package com.yejing.artvault

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.yejing.artvault.ui.component.GI
import com.yejing.artvault.ui.component.RoundIconButton
import java.io.File
import kotlin.math.abs
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 放大判定阈值。低于这个值的缩放直接吸附回 1f，
 * 否则会卡在 1.02 这种"看着没放大、但翻页已经被禁"的坏状态。
 */
private const val ZOOM_ON = 1.05f

/**
 * 看图浮层：全屏黑底，双指缩放 / 放大后单指平移 / 双击切换 / 单击退出。
 *
 * 遮罩铺满并吞掉所有触摸 —— 不做这一步的话点空白会穿到下面的长页上（那里有「编辑」「删除」）。
 * 缩放那套写法原样搬自旧详情页（累积量不吸附、centroid 锚点、`seenMulti` 防 Pager 抽搐），
 * 别改成 `transformable`，也别加阈值吸附。
 */
@Composable
internal fun ImageViewer(
    art: Art,
    lib: Library,
    startVersion: Int,
    bg: BgMode,
    onClose: () -> Unit,
    onExportVersion: (Int) -> Unit,
) {
    val versions = art.versions
    val scope = rememberCoroutineScope()
    val vPager = rememberPagerState(
        startVersion.coerceIn(0, (versions.size - 1).coerceAtLeast(0))
    ) { versions.size.coerceAtLeast(1) }

    val rawScale = remember(art.id) { mutableFloatStateOf(1f) }
    val offset = remember(art.id) { mutableStateOf(Offset.Zero) }
    val zoomed = rawScale.floatValue >= ZOOM_ON
    val scale = if (zoomed) rawScale.floatValue else 1f
    var pinching by remember(art.id) { mutableStateOf(false) }
    var viewSize by remember { mutableStateOf(IntSize.Zero) }
    // 高清档位（方案 B）：不解码区域，只把「当前倍率」换算成一个档位，
    // 让 Coil 按这个档位解一张更大的整图叠在原图上面。1f = 不叠。
    var hdTier by remember(art.id) { mutableFloatStateOf(1f) }

    fun clamp(o: Offset, s: Float): Offset {
        if (s < ZOOM_ON) return Offset.Zero
        if (viewSize.width == 0 || viewSize.height == 0) return o
        val maxX = viewSize.width * (s - 1f) / 2f
        val maxY = viewSize.height * (s - 1f) / 2f
        return Offset(o.x.coerceIn(-maxX, maxX), o.y.coerceIn(-maxY, maxY))
    }

    fun toggleZoom() {
        if (rawScale.floatValue >= ZOOM_ON) {
            rawScale.floatValue = 1f
            offset.value = Offset.Zero
        } else {
            rawScale.floatValue = 2.6f
            offset.value = Offset.Zero
        }
    }

    // 切版本时回到 1x，档位也归零（否则新版本一进来就顶着上一版的高清请求）
    LaunchedEffect(vPager.currentPage) {
        rawScale.floatValue = 1f
        offset.value = Offset.Zero
        hdTier = 1f
    }

    // 放大后按倍率解一张更大的整图（方案 B）。三条设计取舍：
    //
    // 1. **只升不降**：同一次缩放里降档，会把已经在显示的高清层撤掉换成小的，
    //    观感是"越放大越糊"。只有缩回 1x 才归零。
    // 2. **档位离散**：双指缩放时 scale 每帧都在变，连续换档等于连续解码。
    //    取四个档（2 / 3 / 4.5 / 6），一次缩放最多换 3 次。
    // 3. **手势停下来才解**：pinching 期间不动，防止边缩边抢 IO。
    LaunchedEffect(scale, pinching) {
        if (pinching) return@LaunchedEffect
        if (scale < HD_MIN_SCALE) {
            hdTier = 1f
            return@LaunchedEffect
        }
        delay(HD_DEBOUNCE_MS)
        val wanted = when {
            scale < 2.2f -> 2f
            scale < 3.2f -> 3f
            scale < 5f -> 4.5f
            else -> 6f
        }
        if (wanted > hdTier) hdTier = wanted
    }

    Box(Modifier.fillMaxSize().background(Color(0xFF040406))) {
        HorizontalPager(
            state = vPager,
            // 任务卡 D §1.1：手势 Box 在上层，已把整条 hit path 抢走，pager 本来就收不到手指，
            // 原参数是死参数。false = 明确由手势层 dispatchRawDelta 推位移（不要"修好"它）。
            userScrollEnabled = false,
            modifier = Modifier.fillMaxSize(),
        ) { i ->
            Box(
                Modifier
                    .fillMaxSize()
                    .onSizeChanged { viewSize = it },
            ) {
                val ver = versions.getOrNull(i)
                BgLayer(bg)
                ArtView(
                    ver = ver,
                    lib = lib,
                    scale = scale,
                    offset = offset.value,
                    hdScale = if (i == vPager.currentPage) hdTier else 1f,
                )
            }
        }

        // 手势层：缩放/平移。单指未放大时不消费，把点击让给下面那层
        Box(
            Modifier
                .matchParentSize()
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        // 任务卡 D §2.1：松手按"起手页 ± 1"算目标。用松手瞬间的 currentPage 会一次翻两版
                        // （dispatchRawDelta 拖过一半时 currentPage 已经变了）。
                        val startPage = vPager.currentPage
                        var dxAcc = 0f
                        var dyAcc = 0f
                        var horizontal = false
                        var seenMulti = false
                        do {
                            val ev = awaitPointerEvent()
                            val n = ev.changes.count { it.pressed }
                            if (n >= 2) {
                                if (!seenMulti) {
                                    seenMulti = true
                                    pinching = true
                                }
                                val zoom = ev.calculateZoom()
                                val pan = ev.calculatePan()
                                val centroid = ev.calculateCentroid()
                                if (zoom != 1f || pan != Offset.Zero) {
                                    val old = rawScale.floatValue
                                    val next = (old * zoom).coerceIn(1f, 8f)
                                    rawScale.floatValue = next
                                    if (next >= ZOOM_ON) {
                                        val c = centroid - Offset(
                                            viewSize.width / 2f,
                                            viewSize.height / 2f,
                                        )
                                        val k = next / old
                                        offset.value = clamp(c - (c - offset.value) * k + pan, next)
                                    }
                                }
                                ev.changes.forEach { it.consume() }
                            } else if (rawScale.floatValue >= ZOOM_ON) {
                                val pan = ev.calculatePan()
                                if (pan != Offset.Zero) {
                                    offset.value = clamp(offset.value + pan, rawScale.floatValue)
                                    ev.changes.forEach { it.consume() }
                                }
                            } else if (!seenMulti && versions.size > 1) {
                                // 任务卡 D §2.1：1x 单指，认横向就推 pager；放在缩放/放大平移两条之后，
                                // 放大态必须优先走平移；!seenMulti = 双指缩放抬成单指后不能接着翻版本。
                                // 纵向不动、点击不动——点击交给下面的 detectTapGestures。
                                val ch = ev.changes.firstOrNull { it.pressed } ?: ev.changes.firstOrNull()
                                if (ch != null) {
                                    val pdx = ch.position.x - ch.previousPosition.x
                                    val pdy = ch.position.y - ch.previousPosition.y
                                    dxAcc += pdx
                                    dyAcc += pdy
                                    if (!horizontal && abs(dxAcc) > viewConfiguration.touchSlop && abs(dxAcc) > abs(dyAcc)) {
                                        horizontal = true
                                    }
                                    if (horizontal) {
                                        ch.consume()
                                        vPager.dispatchRawDelta(-pdx)
                                    }
                                }
                            }
                        } while (ev.changes.any { it.pressed })
                        pinching = false
                        // 任务卡 D §2.1 边界 1/3/4：!seenMulti；不做"拖够 1/3"门槛（会回弹，是负反馈）；
                        // coerceIn 不越界（第一版右滑 / 最后一版左滑原地不动，且 consume 已让 tap 作废，不会 onClose）。
                        // AlwaysAnimate：用户手机关了系统动画，280ms 的位移必须挂它才看得见（§1.3）。
                        if (horizontal && !seenMulti && versions.size > 1 && rawScale.floatValue < ZOOM_ON) {
                            val towardNext = if (dxAcc < 0) 1 else -1
                            val target = (startPage + towardNext).coerceIn(0, versions.lastIndex)
                            scope.launch(AlwaysAnimate) {
                                vPager.animateScrollToPage(
                                    target,
                                    animationSpec = tween(durationMillis = 280, easing = FastOutSlowInEasing),
                                )
                            }
                        }
                        if (rawScale.floatValue < ZOOM_ON) {
                            rawScale.floatValue = 1f
                            offset.value = Offset.Zero
                        }
                    }
                }
                // 单击退出 / 双击缩放
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { onClose() },
                        onDoubleTap = { toggleZoom() },
                    )
                },
        )

        // 顶栏：关闭 + 版本页码
        Row(
            Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(start = 12.dp, end = 16.dp, top = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RoundIconButton(
                icon = GI.Close,
                onClick = onClose,
                background = PanelVeil,
                tint = Paper,
                size = 36.dp,
                iconSize = 16.dp,
            )
            Spacer(Modifier.width(10.dp))
            if (versions.size > 1) {
                Text(
                    "${vPager.currentPage + 1} / ${versions.size}",
                    style = T.numLg,
                    color = Paper70,
                )
            }
            Spacer(Modifier.weight(1f))
            // 任务卡 D §2.3：复用 GI.External，不新增图标；导出的是当前页这一个版本
            RoundIconButton(
                icon = GI.External,
                onClick = { onExportVersion(vPager.currentPage) },
                background = PanelVeil,
                tint = Paper,
                size = 36.dp,
                iconSize = 17.dp,
            )
        }

        // 底部版本提示（任务卡 D §2.2）。声明在手势层之后 = 在上层才不被挡；
        // 都不加 clickable：背景不拦截指针，点了仍是"单击退出浮层"。
        if (versions.size >= 2) {
            // settledPage 做 key，不用 currentPage：拖动穿过中线时它会在两页间反复变，胶囊会闪。
            // delay 而非动画：系统关掉动画也能按时收起。
            var hintOn by remember { mutableStateOf(true) }
            LaunchedEffect(vPager.settledPage) {
                hintOn = true
                delay(1600)
                hintOn = false
            }
            val cur = vPager.currentPage.coerceIn(0, versions.lastIndex)
            AnimatedVisibility(
                visible = hintOn,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .navigationBarsPadding()
                    .padding(start = 16.dp, bottom = 20.dp),
            ) {
                val v = versions[cur]
                // ★ = 主图，与 VersionStrip 的标记一致；样式照 FileChip
                val name = v.label.ifBlank { "版本 ${cur + 1}" }
                Text(
                    (if (v.isPrimary) "★ " else "") + "$name · ${cur + 1}/${versions.size}",
                    style = T.label,
                    color = Paper,
                    modifier = Modifier
                        .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(50))
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
            // 点阵：2..8 个版本才画，更多时胶囊里已有 n/N，几十个点只会糊成一条
            if (versions.size <= 8) {
                Row(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                        .padding(bottom = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(Space.xs),
                ) {
                    versions.indices.forEach { k ->
                        Box(
                            Modifier
                                .size(5.dp)
                                .background(
                                    if (k == cur) Accent else Paper.copy(alpha = 0.28f),
                                    CircleShape,
                                )
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ArtView(
    ver: Ver?,
    lib: Library,
    scale: Float,
    offset: Offset,
    /** 1f = 只用屏幕级原图；>1f = 叠加一张按倍率解的更大整图 */
    hdScale: Float,
) {
    Box(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 8.dp, vertical = 6.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationX = offset.x
                translationY = offset.y
            },
    ) {
        ProgressiveArtImage(
            ver = ver,
            lib = lib,
            modifier = Modifier.fillMaxSize(),
            hdScale = hdScale,
        )
    }
}

private const val HD_MIN_SCALE = 1.5f
private const val HD_DEBOUNCE_MS = 160L
