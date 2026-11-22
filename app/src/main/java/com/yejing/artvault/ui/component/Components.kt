package com.yejing.artvault.ui.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.yejing.artvault.Space
import com.yejing.artvault.Amber
import com.yejing.artvault.Accent
import com.yejing.artvault.AccentC
import com.yejing.artvault.AccentC2
import com.yejing.artvault.Art
import com.yejing.artvault.Dates
import com.yejing.artvault.CHigh
import com.yejing.artvault.CardBg
import com.yejing.artvault.CheckerBoard
import com.yejing.artvault.Muted
import com.yejing.artvault.Grip
import com.yejing.artvault.Ink
import com.yejing.artvault.Ink60
import com.yejing.artvault.Library
import com.yejing.artvault.Mint
import com.yejing.artvault.MintC
import com.yejing.artvault.OnAccent
import com.yejing.artvault.OnMid
import com.yejing.artvault.Paper70
import com.yejing.artvault.Outline
import com.yejing.artvault.Paper
import com.yejing.artvault.RimBrush
import com.yejing.artvault.T
import com.yejing.artvault.ThemeState

/** 胶囊 chip。选中 = 主色文字 + 主色容器，不再用实心亮块压眼。 */
@Composable
fun ChipPill(
    text: String,
    selected: Boolean = false,
    modifier: Modifier = Modifier,
    /** 右侧的次要数字，例如世界观胶囊上的件数。比正文小一档、弱一档 */
    badge: String? = null,
    leading: (@Composable RowScope.() -> Unit)? = null,
    outlined: Boolean = false,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    val clickMod = if (onLongClick == null) {
        Modifier.clickable(onClick = onClick)
    } else {
        Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick)
    }
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            // 选中 = 实心强调色 + 反色字。以前是「主色容器底 + 主色字」，必须靠一圈描边才看得出边界；
            // 实心块是成熟产品表达「选中」的通用做法（Spotify / Apple Music / 小红书都这么干）。
            .background(if (selected) Accent else CardBg)
            .border(
                1.dp,
                when {
                    selected -> Accent
                    outlined -> Outline
                    else -> Color.Transparent
                },
                RoundedCornerShape(50),
            )
            .then(clickMod)
            .padding(horizontal = 13.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        if (leading != null) leading()
        Text(
            text,
            style = T.label,
            color = if (selected) OnAccent else OnMid,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (badge != null) {
            Text(
                badge,
                style = T.micro,
                color = if (selected) OnAccent.copy(alpha = 0.62f) else Muted,
                maxLines = 1,
            )
        }
    }
}

/** 分区小标：10.5sp + 1.6 字距 */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier, trailing: @Composable (() -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(top = 18.dp, bottom = 9.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = T.overline, color = Muted)
        Spacer(Modifier.weight(1f))
        trailing?.invoke()
    }
}

/**
 * 表单分区小标（11sp + 0.5 字距 + 上下留白）。
 * 建档 / 编辑 / 批量赋值三个表单共用这一份，替换掉原先在 CaptureScreen 里的同名实现——
 * 实现逐字照搬，**上下分别是 16dp / 7dp 留白，属于组件自带的间距**，调用点不要再自己加。
 */
@Composable
fun Label(t: String) {
    Spacer(Modifier.height(Space.l))
    Text(
        t,
        fontSize = 11.sp,
        letterSpacing = 0.5.sp,
        color = Muted,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
    Spacer(Modifier.height(7.dp))
}

/**
 * 底部弹层的拖拽手柄。三个弹层（筛选 / 批量赋值 / 合并）原先各写了一份一模一样的
 * 34×4 圆角条 + 上下 10/6dp 留白，这里收成一份，视觉零变化。
 */
@Composable
fun SheetGrip() {
    Box(
        Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .width(34.dp)
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(Grip)
        )
    }
}

/** 圆形图标按钮（磨砂底，用在顶栏） */
@Composable
fun RoundIconButton(
    icon: GI,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = OnMid,
    background: Color = CardBg,
    size: Dp = 40.dp,
    iconSize: Dp = 19.dp,
    filled: Boolean = false,
) {
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .background(background)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { GIcon(icon, tint, size = iconSize, filled = filled) }
}

/** 主按钮。loading 时按钮内转圈，文案保留宽度避免高度跳动。 */
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
    container: Color = Accent,
    content: Color = OnAccent,
) {
    Box(
        modifier
            .fillMaxWidth()
            .height(50.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(if (enabled) container else CHigh)
            .clickable(enabled = enabled && !loading, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (loading) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(15.dp), color = content, strokeWidth = 2.dp)
                Spacer(Modifier.width(Space.s))
                Text("正在写入…", style = T.body, color = content)
            }
        } else {
            Text(
                text,
                style = T.body,
                color = if (enabled) content else Muted,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** 空状态：一句话 + 可选动作，不要只留一行灰字 */
@Composable
fun EmptyState(
    title: String,
    modifier: Modifier = Modifier,
    sub: String? = null,
    action: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        modifier.fillMaxSize().padding(horizontal = 40.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(64.dp)
                .clip(CircleShape)
                .background(CardBg),
            contentAlignment = Alignment.Center,
        ) { GIcon(GI.Image, Muted, size = 28.dp) }
        Spacer(Modifier.height(Space.l))
        Text(title, style = T.body, color = OnMid, textAlign = TextAlign.Center)
        if (sub != null) {
            Spacer(Modifier.height(Space.xs))
            Text(sub, style = T.label, color = Muted, textAlign = TextAlign.Center)
        }
        if (action != null && onAction != null) {
            Spacer(Modifier.height(18.dp))
            Box(
                Modifier
                    .clip(RoundedCornerShape(50))
                    .background(AccentC)
                    .clickable(onClick = onAction)
                    .padding(horizontal = 18.dp, vertical = 9.dp),
            ) { Text(action, style = T.label, color = Accent, fontWeight = FontWeight.Medium) }
        }
    }
}

/**
 * 网格卡片。3:4 固定格 + 图片 contain，卡面用暗渐变兜住缺边，
 * 竖图横图都不会出现突兀的白块或拉伸。
 */
@Composable
fun ArtCard(
    art: Art,
    lib: Library,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    /** 多选模式：显示选择圈，点击变成选中/取消 */
    selecting: Boolean = false,
    selected: Boolean = false,
    onLongClick: (() -> Unit)? = null,
) {
    val v = art.primary
    val shape = RoundedCornerShape(12.dp)
    Column(
        modifier
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
            )
            // 长按之后的那次抬手**不能算点击**。
            //
            // combinedClickable 在 onLongClick == null 时没有「这次长按已处理」的概念，
            // 抬手照样触发 onClick —— 表现就是「长按进入多选，一松手那张又被自己取消」。
            //
            // 这里只做一件事：长按成立（超过 longPressTimeout）时，把这次的 **up 事件**消费掉。
            // 只消费 up、绝不碰 down 和 move，所以网格那边的拖选（长按起手 + 划动）完全不受影响：
            // 库的 detectDragGesturesAfterLongPress 在 up 上本来就会结束，被消费只会走 onDragCancel，
            // 而 stopDrag 只重置拖动区间，不清已选中的项。
            .pointerInput(Unit) {
                awaitEachGesture {
                    // Initial pass 只「看」不「动」，免得把 DOWN 消费掉让上层收不到
                    var down: PointerInputChange? = null
                    while (down == null) {
                        val ev = awaitPointerEvent(PointerEventPass.Initial)
                        down = ev.changes.firstOrNull { it.pressed && !it.previousPressed }
                    }
                    val t0 = android.os.SystemClock.uptimeMillis()
                    while (true) {
                        val ev = awaitPointerEvent(PointerEventPass.Initial)
                        val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                        if (!ch.pressed) {
                            if (android.os.SystemClock.uptimeMillis() - t0 >=
                                viewConfiguration.longPressTimeoutMillis
                            ) {
                                ch.consume()
                            }
                            break
                        }
                    }
                }
            },
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(3f / 4f)
                .clip(shape)
                .background(
                    Brush.linearGradient(
                        listOf(CHigh, CardBg),
                    )
                )
                .border(
                    width = if (selected) 2.dp else 1.dp,
                    // 选中是「状态」，必须是实心主色；未选中是「面」，用顶光描边（R6）
                    brush = if (selected) androidx.compose.ui.graphics.SolidColor(Accent) else RimBrush,
                    shape = shape,
                ),
        ) {
            // 透明底稿给棋盘衬底：图片是 contain，四周留白直接露出「这块是空的」，
            // 比铺一层暗渐变诚实得多（素材类产品的通用处理）
            if (v?.hasAlpha == true) CheckerBoard(cell = 10.dp)
            if (v != null) {
                AsyncImage(
                    model = lib.thumb(v),
                    contentDescription = art.title.ifBlank { null },
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            val isDark = ThemeState.palette.isDark
            val badgeBg = if (isDark) Ink60 else CardBg.copy(alpha = 0.88f)
            val badgeBorder = if (isDark) Color.White.copy(alpha = 0.12f) else Outline
            if (v?.hasAlpha == true && !selecting) {
                Box(
                    Modifier
                        .align(Alignment.TopStart)
                        .padding(7.dp)
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(Mint)
                        .border(2.dp, if (isDark) Ink60 else CardBg.copy(alpha = 0.9f), CircleShape),
                )
            }
            if (art.versions.size > 1) {
                Text(
                    "${art.versions.size}",
                    style = T.micro,
                    fontWeight = FontWeight.SemiBold,
                    color = if (isDark) Color.White else Paper,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(badgeBg)
                        .border(if (isDark) 0.5.dp else 0.8.dp, badgeBorder, RoundedCornerShape(6.dp))
                        .padding(horizontal = 6.dp, vertical = 1.dp),
                )
            }
            if (art.starred && !selecting) {
                Box(
                    Modifier
                        .align(Alignment.BottomStart)
                        .padding(7.dp)
                        .size(14.dp)
                        .clip(CircleShape)
                        .background(badgeBg)
                        .border(if (isDark) 0.5.dp else 0.8.dp, badgeBorder, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    GIcon(GI.Star, Amber, size = 9.dp, filled = true)
                }
            }
            if (selecting) {
                Box(
                    Modifier
                        .align(Alignment.TopStart)
                        .padding(7.dp)
                        .size(20.dp)
                        .clip(CircleShape)
                        // 未选中圈要压在「浅色作品图」上也看得见：纯白 55% 描边在白底上几乎消失。
                        // Ink60 底 + Paper70 描边，深底浅底都能分出来（V1，2026-11-20）。
                        .background(if (selected) Accent else Ink60)
                        .border(
                            if (selected) 1.2.dp else 1.5.dp,
                            if (selected) Accent else Paper70,
                            CircleShape,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    if (selected) GIcon(GI.Check, OnAccent, size = 12.dp, strokeWidth = 2.6f)
                }
            }
        }
        Spacer(Modifier.height(Space.s))
        // 一行搞定：标题占满剩余宽度，价格靠右。
        // 日期不印了 —— 网格按收稿月份分组，月份在标头上；逐张印日期既扫不出来也不能用来定位。
        // 标题就是标题。以前这里回退到世界观名（`worldName ?: "未命名"`），
        // 后果是「给未命名稿填了世界观，卡片看起来像被改名了」，而详情页仍显示「未命名」。
        // 世界观是归属关系，不是名称 —— 拿它填标题等于编了一个不存在的数据。
        val title = art.title.ifBlank { "未命名" }
        val price = art.priceTotal // 0 是有效价格（免费稿），null 才是「没填」
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                title,
                style = T.label,
                fontWeight = FontWeight.Medium,
                color = OnMid,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (price != null) {
                Spacer(Modifier.width(Space.xs))
                // 0 元是有效价格（免费稿），但「¥0」用琥珀印出来会被读成「标价 0」这种异常值。
                // 换成薄荷微标「无偿」：形状与价格不同，一眼能分出「这张没花钱」和「这张忘了填价」。
                // 判断仍是 `!= null` —— 0 依然是有效价格，只是换个渲染方式。
                if (price == 0L) {
                    Text(
                        "无偿",
                        style = T.micro,
                        color = Mint,
                        maxLines = 1,
                        softWrap = false,
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(MintC)
                            .padding(horizontal = 4.dp, vertical = 1.dp),
                    )
                } else {
                    Text(
                        "¥$price",
                        style = T.num,
                        color = Amber,
                        maxLines = 1,
                        softWrap = false,
                    )
                }
            }
        }
    }
}

/** 顶部固定的分隔细线，用在列表与面板交界处 */
@Composable
fun HairLine(modifier: Modifier = Modifier, color: Color = Outline) {
    Box(modifier.fillMaxWidth().height(0.8.dp).background(color))
}

internal val GridPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp)
internal val CardGap = 8.dp

/**
 * 长列表「回到顶部」。
 *
 * 只在滚过几屏后出现（调用方按 `firstVisibleItemIndex > 列数 * 4` 判断），淡入淡出而不硬切；
 * 点击走 `animateScrollToItem` —— 用户要的是「正在往回走」的方向感，瞬跳会让人失去位置感。
 */
@Composable
fun ScrollToTopButton(visible: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    AnimatedVisibility(visible = visible, enter = fadeIn(), exit = fadeOut(), modifier = modifier) {
        Box(
            Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(CardBg)
                .border(1.dp, RimBrush, CircleShape)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            // 没有 ArrowUp 图标，把 ChevronDown 转 180° 就是向上，省一个图标
            GIcon(
                GI.ChevronDown,
                OnMid,
                modifier = Modifier.graphicsLayer { rotationZ = 180f },
                size = 18.dp,
            )
        }
    }
}

/**
 * 「推断值」提示 + 一键确认。
 *
 * 收稿日期是从文件时间猜出来的时候，用户常常**本来就不想改**——但以前只有"改一下日期"才算确认，
 * 于是猜对了也永远清不掉「待矫时」（2026-11-22 用户报的）。这里给一个显式的「就是这个」。
 */
@Composable
internal fun GuessDateHint(text: String, onConfirm: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = T.micro, color = Muted, maxLines = 1)
        Spacer(Modifier.width(Space.s))
        Box(
            Modifier
                .clip(RoundedCornerShape(50))
                .background(AccentC)
                .clickable(onClick = onConfirm)
                .padding(horizontal = 9.dp, vertical = 3.dp),
        ) {
            Text("就是这个", style = T.micro, color = Accent)
        }
    }
}
