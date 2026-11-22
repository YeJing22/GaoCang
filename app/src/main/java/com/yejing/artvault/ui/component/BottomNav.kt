package com.yejing.artvault.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yejing.artvault.Accent
import com.yejing.artvault.CHigh
import com.yejing.artvault.CardBg
import com.yejing.artvault.Ink
import com.yejing.artvault.Muted
import com.yejing.artvault.OnAccent
import com.yejing.artvault.OnMid
import com.yejing.artvault.Paper
import com.yejing.artvault.RimBrush
import com.yejing.artvault.T
import com.yejing.artvault.Warn

data class NavDest(val icon: GI, val label: String)

/**
 * 四个目的地。分类 tab 装的是「世界观 / 角色 / 画师 / 标签 / 归档」这些实体，
 * 所以名字从「角色库」改成「分类」——原来那个名字和内容对不上（R2，2026-11-19）。
 */
val NavDests = listOf(
    NavDest(GI.Grid, "稿库"),
    NavDest(GI.Tag, "分类"),
    NavDest(GI.Stats, "账本"),
    NavDest(GI.Person, "我的"),
)

/** 底栏总高：22 上渐变 + 64 条 + 10 下。动作条必须用同一个数，否则进出多选会跳。 */
private val BarTop = 22.dp
private val BarHeight = 64.dp
private val BarBottom = 10.dp

/**
 * 底栏底板。**导航态和动作态共用这一块**（同位置、同高、同圆角、同渐变），
 * 切换时只是里面的内容换掉——所以进出多选时网格一像素不动。
 *
 * 以前是「导航条消失 + 另起一条动作条」，两条各自带渐变和 fixedHeight 补偿，
 * 多选进出整屏会跳一下（R2 之前）。
 */
@Composable
private fun BarShell(
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    Column(
        modifier
            .fillMaxWidth()
            .background(
                // R6：渐变提前收口（0.35 → 0.22），让「卡片在底栏上方淡出」这件事先发生，
                // 再让胶囊自己半透明 —— 毛玻璃观感靠的是这两层配合，而不是真模糊
                // （Compose 无法模糊身后内容：Modifier.blur 在 API 31 以下是空实现，minSdk 28）。
                Brush.verticalGradient(
                    0f to Color.Transparent,
                    0.22f to Ink.copy(alpha = 0.90f),
                    0.6f to Ink.copy(alpha = 0.98f),
                    1f to Ink,
                )
            )
            .navigationBarsPadding()
            .padding(start = 12.dp, end = 12.dp, top = BarTop, bottom = BarBottom),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(BarHeight)
                .clip(RoundedCornerShape(22.dp))
                // 0.97 → 0.93：只有顶上那一小条能透出网格，多了会读不清图标
                .background(CardBg.copy(alpha = 0.93f))
                .border(1.dp, RimBrush, RoundedCornerShape(22.dp)),
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
    }
}

/**
 * 底部导航：4 个 tab + 中间的录入键。
 * 录入从右下角的 FAB 挪到正中间——FAB 一直盖着第三列卡片，回顶按钮也被迫抬到 164dp 去躲它。
 */
@Composable
fun GaoBottomBar(
    selected: Int,
    onSelect: (Int) -> Unit,
    onCapture: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BarShell(modifier) {
        NavItem(NavDests[0], selected == 0) { onSelect(0) }
        NavItem(NavDests[1], selected == 1) { onSelect(1) }
        CaptureItem(onCapture)
        NavItem(NavDests[2], selected == 2) { onSelect(2) }
        NavItem(NavDests[3], selected == 3) { onSelect(3) }
    }
}

@Composable
private fun RowScope.NavItem(dest: NavDest, selected: Boolean, onClick: () -> Unit) {
    // 选中用纯白而不是强调色：底栏常驻，它一亮，整屏就只剩「价格」一种被点亮的颜色
    val tint = if (selected) Paper else Muted
    Box(
        Modifier
            .weight(1f)
            .height(BarHeight)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .clip(RoundedCornerShape(16.dp))
                .background(if (selected) CHigh else Color.Transparent)
                .padding(horizontal = 12.dp, vertical = 5.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            GIcon(dest.icon, tint, size = 20.dp)
            Spacer(Modifier.height(3.dp))
            Text(dest.label, fontSize = 10.sp, color = tint)
        }
    }
}

/** 中间那颗：实心主色、48×40、圆角 14。没有文字，图标自己说话。 */
@Composable
private fun RowScope.CaptureItem(onClick: () -> Unit) {
    Box(Modifier.weight(1f).height(BarHeight), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .size(width = 48.dp, height = 40.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(Accent)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            GIcon(GI.Plus, OnAccent, size = 21.dp, strokeWidth = 2.1f)
        }
    }
}

/**
 * 多选动作条：和导航栏同一块底板，只是内容换成四个动作。
 * 顺序按使用频率：赋值 > 合并 > 导出 > 删除；删除是破坏性动作，只用文字色，不铺实心红。
 */
@Composable
fun GaoActionBar(
    count: Int,
    onAssign: () -> Unit,
    onMerge: () -> Unit,
    onExport: (() -> Unit)?,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BarShell(modifier) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BarAction("赋值", count >= 1, primary = true, Modifier.weight(1f), onAssign)
            Spacer(Modifier.width(6.dp))
            BarAction("合并", count >= 2, primary = false, Modifier.weight(1f), onMerge)
            if (onExport != null) {
                Spacer(Modifier.width(6.dp))
                BarAction("导出", count >= 1, primary = false, Modifier.weight(1f), onExport)
            }
            Spacer(Modifier.width(6.dp))
            BarAction("删除", count >= 1, primary = false, Modifier.weight(1f), onDelete, danger = true)
        }
    }
}

@Composable
private fun BarAction(
    text: String,
    enabled: Boolean,
    primary: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    danger: Boolean = false,
) {
    Box(
        modifier
            .height(44.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(if (primary && enabled) Accent else Color.Transparent)
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            style = T.bodySm,
            color = when {
                !enabled -> Muted.copy(alpha = 0.5f)
                primary -> OnAccent
                danger -> Warn
                else -> OnMid
            },
            fontWeight = if (primary && enabled) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}
