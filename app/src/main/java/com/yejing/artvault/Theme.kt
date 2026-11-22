package com.yejing.artvault

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/**
 * 完整主题色板契约。
 * 所有顶层色值 token（`Ui.kt`）均由此派生，切换 [ThemePreset] 时全应用自动重组。
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
 * 4 套预设主题（2 套深色 + 2 套浅色）。
 */
enum class ThemePreset(
    val id: String,
    val label: String,
    val sub: String,
    val palette: ThemePalette,
) {
    AmberNight(
        id = "amber_night",
        label = "夜色琥珀",
        sub = "深色 · 深蓝墨底 + 暖琥珀",
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
                Color(0xFFF2B45E),
                Color(0xFF5BC8A8),
                Color(0xFF6E8FE0),
                Color(0xFF8A93A8),
                Color(0xFFC98F6A),
                Color(0xFF6FB3C0),
            ),
        ),
    ),

    SlateBlue(
        id = "slate_blue",
        label = "暮岚灰蓝",
        sub = "深色 · 柔和灰蓝底 + 雾灰蓝",
        palette = ThemePalette(
            isDark = true,
            ink = Color(0xFF0B0E14),
            cardBg = Color(0xFF131822),
            cHigh = Color(0xFF1B2230),
            outline = Color(0xFF273142),
            outlineSoft = Color(0xFF1A212E),
            paper = Color(0xFFEAEFF6),
            onMid = Color(0xFF9AA6BA),
            muted = Color(0xFF636F84),
            accent = Color(0xFF7FA3D4),
            accentC = Color(0xFF1A2434),
            accentC2 = Color(0xFF2D3D56),
            onAccent = Color(0xFF0D1522),
            lilac = Color(0xFFB0A6FF),
            mint = Color(0xFF63BFA4),
            mintC = Color(0x1F63BFA4),
            warn = Color(0xFFE38E8E),
            warnC = Color(0x1FE38E8E),
            amber = Color(0xFFDFB374),
            amberC = Color(0x1ADFB374),
            ink60 = Color(0x990B0E14),
            ink50 = Color(0x800B0E14),
            grip = Color(0xFF2F3A4D),
            scrim = Color(0xCC05070B),
            panelVeil = Color(0x4D0B0E14),
            paper70 = Color(0xB3EAEFF6),
            rimBrush = Brush.verticalGradient(
                0f to Color.White.copy(alpha = 0.08f),
                0.5f to Color.White.copy(alpha = 0.028f),
                1f to Color.White.copy(alpha = 0.045f),
            ),
            chartColors = listOf(
                Color(0xFF7FA3D4),
                Color(0xFF63BFA4),
                Color(0xFFDFB374),
                Color(0xFF8B98B0),
                Color(0xFFC28E7A),
                Color(0xFF6EA8B8),
            ),
        ),
    ),

    WarmPaper(
        id = "warm_paper",
        label = "宣纸暖砂",
        sub = "浅色 · 宣纸米白底 + 焦糖琥珀",
        palette = ThemePalette(
            isDark = false,
            ink = Color(0xFFF6F3EE),
            cardBg = Color(0xFFFFFFFF),
            cHigh = Color(0xFFEDE8DF),
            outline = Color(0xFFDFD8CC),
            outlineSoft = Color(0xFFEAE4DA),
            paper = Color(0xFF1F1C18),
            onMid = Color(0xFF5B544B),
            muted = Color(0xFF8E8579),
            accent = Color(0xFFC97A24),
            accentC = Color(0xFFF9ECDC),
            accentC2 = Color(0xFFEBD0AE),
            onAccent = Color(0xFFFFFFFF),
            lilac = Color(0xFF7466D8),
            mint = Color(0xFF258F70),
            mintC = Color(0x1F258F70),
            warn = Color(0xFFD14D4D),
            warnC = Color(0x1CD14D4D),
            amber = Color(0xFFB86B1A),
            amberC = Color(0x1AB86B1A),
            ink60 = Color(0x99191612),
            ink50 = Color(0x80191612),
            grip = Color(0xFFD2C9BC),
            scrim = Color(0x73181410),
            panelVeil = Color(0x66191612),
            paper70 = Color(0xB31F1C18),
            rimBrush = Brush.verticalGradient(
                0f to Color.Black.copy(alpha = 0.05f),
                1f to Color.Black.copy(alpha = 0.08f),
            ),
            chartColors = listOf(
                Color(0xFFC97A24),
                Color(0xFF258F70),
                Color(0xFF5A7BC8),
                Color(0xFF8A8075),
                Color(0xFFB86F52),
                Color(0xFF4F93A0),
            ),
        ),
    ),

    MistBlue(
        id = "mist_blue",
        label = "晴岚雾蓝",
        sub = "浅色 · 雾灰白底 + 晴岚靛蓝",
        palette = ThemePalette(
            isDark = false,
            ink = Color(0xFFF2F5FA),
            cardBg = Color(0xFFFFFFFF),
            cHigh = Color(0xFFE7EDF6),
            outline = Color(0xFFD6DFEC),
            outlineSoft = Color(0xFFE5ECF5),
            paper = Color(0xFF172030),
            onMid = Color(0xFF526076),
            muted = Color(0xFF8391A6),
            accent = Color(0xFF4466E8),
            accentC = Color(0xFFE7EDFE),
            accentC2 = Color(0xFFC5D2FA),
            onAccent = Color(0xFFFFFFFF),
            lilac = Color(0xFF7466D8),
            mint = Color(0xFF1C9674),
            mintC = Color(0x1F1C9674),
            warn = Color(0xFFD44B56),
            warnC = Color(0x1CD44B56),
            amber = Color(0xFFC47A1E),
            amberC = Color(0x1AC47A1E),
            ink60 = Color(0x94141C2A),
            ink50 = Color(0x80141C2A),
            grip = Color(0xFFC5D0E0),
            scrim = Color(0x73101824),
            panelVeil = Color(0x66141C2A),
            paper70 = Color(0xB3172030),
            rimBrush = Brush.verticalGradient(
                0f to Color.Black.copy(alpha = 0.05f),
                1f to Color.Black.copy(alpha = 0.08f),
            ),
            chartColors = listOf(
                Color(0xFF4466E8),
                Color(0xFF1C9674),
                Color(0xFFC47A1E),
                Color(0xFF74849C),
                Color(0xFFBE7268),
                Color(0xFF4A98B0),
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
