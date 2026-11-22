package com.yejing.artvault

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/**
 * 完整主题色板契约。
 * 所有顶层色值 token（`Ui.kt`）均由此派生，后续新增预设主题只需在 [ThemePreset] 中追加条目，
 * 无需改动业务页面的调用点。
 */
data class ThemePalette(
    val isDark: Boolean,
    val ink: Color,
    val cardBg: Color,
    val cHigh: Color,
    val outline: Color,
    val outlineSoft: Color,
    val paper: Color,
    val onMid: Color,
    val muted: Color,
    val accent: Color,
    val accentC: Color,
    val accentC2: Color,
    val onAccent: Color,
    val lilac: Color,
    val mint: Color,
    val mintC: Color,
    val warn: Color,
    val warnC: Color,
    val amber: Color,
    val amberC: Color,
    val ink60: Color,
    val ink50: Color,
    val grip: Color,
    val scrim: Color,
    val panelVeil: Color,
    val paper70: Color,
    val rimBrush: Brush,
    val chartColors: List<Color>,
)

/**
 * 预设主题枚举。
 * 下个大版本开放切换入口时，直接在这里扩展更多预设并在「我的 → 偏好与工具」提供选择器即可。
 */
enum class ThemePreset(
    val id: String,
    val label: String,
    val palette: ThemePalette,
) {
    AmberNight(
        id = "amber_night",
        label = "夜色琥珀",
        palette = ThemePalette(
            isDark = true,
            ink = Color(0xFF090B10),
            cardBg = Color(0xFF11151E),
            cHigh = Color(0xFF191F2C),
            outline = Color(0xFF232A38),
            outlineSoft = Color(0xFF161B26),
            paper = Color(0xFFEEF0F6),
            onMid = Color(0xFF9BA3B5),
            muted = Color(0xFF5E6678),
            accent = Color(0xFFF2B45E),
            accentC = Color(0xFF2B2217),
            accentC2 = Color(0xFF4A3822),
            onAccent = Color(0xFF1A1206),
            lilac = Color(0xFFB0A6FF),
            mint = Color(0xFF5BC8A8),
            mintC = Color(0x1A5BC8A8),
            warn = Color(0xFFF08C8C),
            warnC = Color(0x1FF09A9A),
            amber = Color(0xFFF2B45E),
            amberC = Color(0x1AF2B45E),
            ink60 = Color(0x99090B10),
            ink50 = Color(0x80090B10),
            grip = Color(0xFF2A3242),
            scrim = Color(0xCC040506),
            panelVeil = Color(0x4D090B10),
            paper70 = Color(0xB3EEF0F6),
            rimBrush = Brush.verticalGradient(
                0f to Color.White.copy(alpha = 0.08f),
                0.5f to Color.White.copy(alpha = 0.028f),
                1f to Color.White.copy(alpha = 0.045f),
            ),
            chartColors = listOf(
                Color(0xFFF2B45E), // 琥珀
                Color(0xFF5BC8A8), // 薄荷
                Color(0xFF6E8FE0), // 冷蓝
                Color(0xFF8A93A8), // 灰蓝
                Color(0xFFC98F6A), // 陶土
                Color(0xFF6FB3C0), // 灰青
            ),
        ),
    ),
    ;

    companion object {
        fun fromId(id: String?): ThemePreset =
            entries.firstOrNull { it.id == id } ?: AmberNight
    }
}

/** 全局可观察主题状态：切换 [preset] 后 Compose 会自动重组所有读取 token 的界面。 */
object ThemeState {
    var preset by mutableStateOf(ThemePreset.AmberNight)

    val palette: ThemePalette get() = preset.palette

    fun apply(id: String?) {
        preset = ThemePreset.fromId(id)
    }
}
