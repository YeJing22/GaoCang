package com.yejing.artvault

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/* ────────────────────────────────────────────────────────────────
 * 设计 token。文件名沿用 Ui.kt，因为旧页面全部直接引用这些顶层常量。
 * 视觉规范见 design/index.html（评审稿）。改色只改这里。
 * ──────────────────────────────────────────────────────────────── */

/*
 * 表面层级：只有三级（R1b，2026-11-19 从六级收敛而来）。
 *   Ink   = 页面底
 *   CardBg= 唯一的一块「面」：卡片、胶囊、输入框、底栏、备注块、弹层
 *   CHigh = 抬一档：选中胶囊、次要按钮、当前 tab
 * 规则：一屏最多同时出现两级面，第三级只出现在浮层与选中态。
 * 退役的别名：Surface / CLow / CHighest（并入上面三级）、Faint（并入 Muted）。
 * 为什么合并：六个近黑相邻只差 1–3 个 L* 点，肉眼分不出，最后又靠描边补救。
 */
internal val Ink = Color(0xFF090B10)          // 页面背景
internal val CardBg = Color(0xFF11151E)       // 容器（输入框、卡片底、chip 底）
internal val CHigh = Color(0xFF191F2C)        // 高容器
internal val Outline = Color(0xFF232A38)
internal val OutlineSoft = Color(0xFF161B26)

// ── 文字 ──
internal val Paper = Color(0xFFEEF0F6)        // 主文字
internal val OnMid = Color(0xFF9BA3B5)        // 次文字
internal val Muted = Color(0xFF5E6678)        // 辅助文字

// ── 强调与语义 ──
internal val Accent = Color(0xFFF2B45E)       // 主色：暖琥珀（2026-11-19 换风格，原紫 #B0A6FF）
internal val AccentC = Color(0xFF2B2217)      // 主色容器（低饱和暖灰，不要用高饱和棕：深底上会显脏）
internal val AccentC2 = Color(0xFF4A3822)     // 主色容器描边
internal val OnAccent = Color(0xFF1A1206)     // 主色上的文字
internal val Lilac = Color(0xFFB0A6FF)        // 只表示「演示数据」这一状态（见下方说明）
internal val Mint = Color(0xFF5BC8A8)         // 透明底 / 成功
internal val MintC = Color(0x1A5BC8A8)
internal val Warn = Color(0xFFF08C8C)         // 警示
internal val Amber = Color(0xFFF2B45E)        // 价格

/*
 * 语义色的「低透明度容器」与「压在画面上的墨」。
 * 规则：容器一律 `XxxC`（跟 MintC 一套），压图遮罩一律 `Ink<百分比>`。
 * B1 先把散落各页面的硬编码收成 token；2026-11-19 换风格时按新底色重算了一遍数值。
 */
internal val WarnC = Color(0x1FF09A9A)        // Warn 12% 容器（危险动作底）
internal val AmberC = Color(0x1AF2B45E)       // Amber 10% 容器（价格 / 合并提示底）
internal val Ink60 = Color(0x99090B10)        // Ink 60%：压在缩略图上的描边、角标底
internal val Ink50 = Color(0x80090B10)        // Ink 50%：未选中的遮罩
internal val Grip = Color(0xFF2A3242)         // 底部弹层的拖拽手柄
internal val Scrim = Color(0xCC040506)        // 底部弹层遮罩（比纯黑略偏冷）
internal val PanelVeil = Color(0x4D090B10)    // 详情页信息面板底（30% 墨）
internal val Paper70 = Color(0xB3EEF0F6)      // 图上的次级文字

/**
 * 卡片顶光描边（R6，2026-11-21）。
 *
 * 三级表面色的底色 `Ink / CardBg / CHigh` 只差 1–3 个 L* 点，纯色块叠在一起时
 * 「哪一块在上面」只能靠描边读出来；而统一 1dp 的实心描边又会让整屏像贴了一圈钢丝。
 * 换成沿高度衰减的极淡白光之后，卡片上沿有一条「顶光」，往下自己化开 ——
 * 观感上是从「纯平色块」变成「一块能反光的面」，和 Apple Music / Arc 的暗色层叠同一套逻辑。
 *
 * 三条约束：
 * 1. **必须是顶层 val**。网格里同时有几十张卡，每帧 new 一个 Brush 是白扔对象。
 * 2. 上沿 8%（真机 OLED 下 10% 偏亮，2026-11-21 定 8%），中段 3.5%，下沿回落到 5.5%。
 *    下沿**不能落到 0**：落到 0 卡片底边完全消失，在 Ink 上看着像没封口。
 * 3. 只给「面」（卡片 / 底栏 / 浮钮）用，**不要给 `ChipPill` 用** ——
 *    胶囊只有 28dp 高，1dp 描边占掉 1/28，一颗颗都像镶了银边。
 */
internal val RimBrush = Brush.verticalGradient(
    0f to Color.White.copy(alpha = 0.08f),
    0.5f to Color.White.copy(alpha = 0.028f),
    1f to Color.White.copy(alpha = 0.045f),
)

/*
 * `Lilac` 曾经与 `Accent` 同值（都是 #B0A6FF），2026-11-19 换风格后两者分开了：
 * `Accent` 变成暖琥珀，`Lilac` 保留紫，**专门表示「演示数据」这个状态**（浏览页标题下的小圆点、
 * 演示横幅的文字与底色）。这是刻意的：演示态要一眼和真库区分开，用一个「不在主色体系里」的颜色最省事。
 * 约束：除演示态之外不要再用 `Lilac`；新代码一律 `Accent`。
 */

/**
 * 仅账本可视化使用。**刻意避开 Lilac** —— 那个紫是「演示数据」的专属语义，
 * 一旦出现在图表里，"紫色 = 演示"这条约定就废了。
 */
internal val ChartColors = listOf(
    Accent,                     // 琥珀
    Mint,                       // 薄荷
    Color(0xFF6E8FE0),          // 冷蓝
    Color(0xFF8A93A8),          // 灰蓝
    Color(0xFFC98F6A),          // 陶土
    Color(0xFF6FB3C0),          // 灰青
)

/*
 * 字号阶梯（2026-11-17 抛光：对齐 Material 3 的档位，拉大层级差）
 *
 * 改的是三件事，都是为了「看起来像成熟 App」而不是「信息密度高的工具」：
 * 1. 正文字号 14 → 15：主流阅读型界面正文在 15–17 之间，14 偏小、显拥挤
 * 2. 行高统一到 1.4–1.5：中文笔画密，1.2 会显得贴在一起（原来 micro 只有 1.33）
 * 3. 最小字号 10.5 → 11：中文在 10.5sp 下笔画会糊，11 是多数系统的下限
 *
 * 相邻档位的差至少 1sp，且**大字号之间差得更多**（30/22 差 8、22/16 差 6），
 * 小字号之间只差 1–2（15/13/12/11）——层级感靠这个非线性的比例撑起来。
 */
internal object T {
    val display = TextStyle(fontSize = 30.sp, lineHeight = 37.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.4).sp)
    val title = TextStyle(fontSize = 22.sp, lineHeight = 29.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp)
    val titleSm = TextStyle(fontSize = 16.sp, lineHeight = 23.sp, fontWeight = FontWeight.Medium)
    val body = TextStyle(fontSize = 15.sp, lineHeight = 22.sp)
    val bodySm = TextStyle(fontSize = 13.sp, lineHeight = 19.sp)
    val label = TextStyle(fontSize = 12.sp, lineHeight = 17.sp)
    val micro = TextStyle(fontSize = 11.sp, lineHeight = 15.sp)
    val overline = TextStyle(fontSize = 11.sp, lineHeight = 15.sp, letterSpacing = 1.6.sp, fontWeight = FontWeight.Medium)
    /** 日期 / 价格 / 计数：等宽数字，列表滚动时不会左右抖 */
    val num = TextStyle(fontSize = 11.sp, lineHeight = 15.sp, fontFeatureSettings = "tnum")
    val numLg = TextStyle(fontSize = 14.sp, lineHeight = 19.sp, fontFeatureSettings = "tnum")
}

/**
 * 圆角只有四档，且**同类元素必须用同一档**：之前卡片有 14 和 16 两种、
 * 缩略图有 9 和 10 两种，混在一起就是"没设计过"的观感。
 *   8  → 小标签、输入框内衬
 *   12 → chip 容器、缩略图
 *   14 → 卡片、面板
 *   28 → 抽屉、对话框
 */
internal val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/**
 * 间距阶梯（B1 新增）。**只用于 padding / spacedBy / Spacer**，
 * 不要用在 `size` / `height` / `width` 这类「元素尺寸」上——尺寸是组件规格，不是间距。
 *
 * 4/6/8 是「紧贴 → 一行内 → 元素间」三级，12/16/20/24 是「组内 → 块间 → 页边 → 大段落」。
 * 现有代码里这七档占 `.dp` 出现次数的六成多；剩下的 5 / 7 / 10 / 14 是历史手写值，
 * **B1 不动它们**（改值就破坏「像素零变化」），由 B2–B6 各自重排那个页面时顺手收敛到本表。
 */
internal object Space {
    val xxs = 4.dp    // 图标与文字的紧贴
    val xs = 6.dp     // 一行里的两个小片
    val s = 8.dp      // 默认元素间距（列表卡间距也是它）
    val m = 12.dp     // 分组内部
    val l = 16.dp     // 页内块间距
    val xl = 20.dp    // 页边距
    val xxl = 24.dp   // 大段落
}

/**
 * 动效（B1 新增）。之前全项目只有 GridScrollBar 里两个裸 `tween(240)` / `tween(180)`。
 *
 * 三档按「用户感知得到多快」分：120 是手指下的即时反馈（按下、选中翻转），
 * 180 是元素自己的淡入淡出，240 是整块面板进出。
 * 曲线用 M3 的两条：`standard` 用于同屏内的位移，`decelerate` 用于「进来」的动作
 * （出门用 reflection 的 accelerate，目前没有场景，先不加）。
 */
internal object Motion {
    const val fast = 120      // 按压 / 选中反馈
    const val base = 180      // 淡入淡出、单元素变化
    const val slow = 240      // 面板 / 抽屉 / 大块进出
    val standard = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    val decelerate = CubicBezierEasing(0f, 0f, 0f, 1f)
}

/**
 * 「这一段必须真的动」的动画时钟（2026-11-21）。
 *
 * 系统设置里把「动画时长缩放」关掉（开发者选项 / 部分机型的流畅模式）时，`animator_duration_scale = 0`，
 * Compose 的 `Animatable.animateTo` 会走「scale 为 0 就不动画、直接到终值」的分支。
 * 对 `AnimatedVisibility` 这种纯装饰没影响，但对**承载信息**的动画是致命的：
 * 详情页切版本的 `animateScrollToPage` 会变成「一松手就换图」，用户看不出图是滑过去的还是跳过去的。
 *
 * `MotionDurationScale` 是 CoroutineContext 的元素，动画通过 `coroutineContext.durationScale` 读它。
 * 所以不用改动画本身、也不用自己算帧，只要在发起这次动画的协程里塞一个 `scaleFactor = 1f` 的元素：
 * `scope.launch(AlwaysAnimate) { pager.animateScrollToPage(...) }`
 * —— 缓动、边界吸附、预加载全部照旧，只是不再听系统那个 0。
 *
 * 用法约束：**只给「看完要能判断发生了什么」的动画用**，别拿去包全局。
 * 用户关掉动画本来就是为了快，把所有淡入淡出都强行打开是跟他对抗；
 * 我们只是不让「翻页」这种位移被静音。
 */
internal val AlwaysAnimate = object : androidx.compose.ui.MotionDurationScale {
    override val scaleFactor: Float get() = 1f
}

private val AppTypography = Typography(
    displaySmall = T.display,
    titleLarge = T.title,
    titleMedium = T.titleSm,
    bodyLarge = T.body,
    bodyMedium = T.bodySm,
    labelLarge = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium),
    labelMedium = T.label,
    labelSmall = T.micro,
)

/** 统一入口。浅色主题与主题色切换留到 UI 定稿后再接。 */@Composable
fun GaoCangTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Accent,
            onPrimary = OnAccent,
            primaryContainer = AccentC,
            onPrimaryContainer = Accent,
            secondary = Mint,
            onSecondary = Ink,
            secondaryContainer = MintC,
            onSecondaryContainer = Mint,
            tertiary = Amber,
            background = Ink,
            onBackground = Paper,
            surface = CardBg,
            onSurface = Paper,
            surfaceVariant = CardBg,
            onSurfaceVariant = OnMid,
            surfaceDim = Ink,
            surfaceBright = CHigh,
            surfaceContainerLowest = Ink,
            surfaceContainerLow = CardBg,
            surfaceContainer = CardBg,
            surfaceContainerHigh = CHigh,
            surfaceContainerHighest = CHigh,
            outline = Outline,
            outlineVariant = OutlineSoft,
            error = Warn,
            onError = Ink,
            scrim = Color.Black,
        ),
        typography = AppTypography,
        shapes = AppShapes,
        content = content,
    )
}

/**
 * 一句轻提示。全 App 只有这一份——之前 MainActivity / ArtistScreen / EditScreen 各写了一个，
 * 行为一致但改文案时要找三个地方。内部固定投到主线程，调用点不必关心自己在哪个线程。
 */
internal fun toast(ctx: Context, msg: String) {
    Handler(Looper.getMainLooper()).post {
        Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
    }
}
