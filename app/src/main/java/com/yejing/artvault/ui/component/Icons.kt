package com.yejing.artvault.ui.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 手绘矢量图标。不引 material-icons 依赖：
 * BOM 里那套图标要么补依赖（本机 Gradle 缓存不一定有），要么风格对不上，这里统一按 24 网格画。
 */
enum class GI {
    Grid, Tag, Stats, Person,
    Tune, Columns2, Columns3, Sort,
    Back, Close, Plus, Heart, More, ChevronDown, Check, Image, External, Fullscreen, Star,
}

@Composable
fun GIcon(
    icon: GI,
    tint: Color,
    modifier: Modifier = Modifier,
    size: Dp = 22.dp,
    strokeWidth: Float = 1.7f,
    filled: Boolean = false,
) {
    Canvas(modifier.size(size)) {
        val s = this.size.minDimension / 24f
        val sw = strokeWidth * s
        val st = Stroke(width = sw, cap = StrokeCap.Round, join = StrokeJoin.Round)
        fun line(x1: Float, y1: Float, x2: Float, y2: Float) =
            drawLine(tint, Offset(x1 * s, y1 * s), Offset(x2 * s, y2 * s), sw, StrokeCap.Round)

        fun rrect(x: Float, y: Float, w: Float, h: Float, r: Float, fill: Boolean = true) {
            drawRoundRect(
                color = tint,
                topLeft = Offset(x * s, y * s),
                size = Size(w * s, h * s),
                cornerRadius = CornerRadius(r * s, r * s),
                style = if (fill) androidx.compose.ui.graphics.drawscope.Fill else st,
            )
        }

        fun circle(cx: Float, cy: Float, r: Float, fill: Boolean = true) {
            drawCircle(
                color = tint,
                radius = r * s,
                center = Offset(cx * s, cy * s),
                style = if (fill) androidx.compose.ui.graphics.drawscope.Fill else st,
            )
        }

        when (icon) {
            GI.Grid -> {
                rrect(3f, 3f, 8f, 8f, 2.4f)
                rrect(13f, 3f, 8f, 8f, 2.4f)
                rrect(3f, 13f, 8f, 8f, 2.4f)
                rrect(13f, 13f, 8f, 8f, 2.4f)
            }

            GI.Tag -> {
                val p = Path().apply {
                    moveTo(11.8f * s, 3.2f * s)
                    lineTo(19.2f * s, 3.2f * s)
                    cubicTo(20.1f * s, 3.2f * s, 20.8f * s, 3.9f * s, 20.8f * s, 4.8f * s)
                    lineTo(20.8f * s, 12.2f * s)
                    cubicTo(20.8f * s, 12.7f * s, 20.6f * s, 13.2f * s, 20.2f * s, 13.6f * s)
                    lineTo(13.6f * s, 20.2f * s)
                    cubicTo(13.0f * s, 20.8f * s, 12.0f * s, 20.8f * s, 11.4f * s, 20.2f * s)
                    lineTo(3.8f * s, 12.6f * s)
                    cubicTo(3.2f * s, 12.0f * s, 3.2f * s, 11.0f * s, 3.8f * s, 10.4f * s)
                    lineTo(10.4f * s, 3.8f * s)
                    cubicTo(10.8f * s, 3.4f * s, 11.3f * s, 3.2f * s, 11.8f * s, 3.2f * s)
                    close()
                }
                drawPath(p, tint, style = st)
                circle(16.4f, 7.6f, 1.5f)
            }

            GI.Stats -> {
                rrect(3.4f, 13.4f, 3f, 6.4f, 1.5f)
                rrect(8.6f, 9.2f, 3f, 10.6f, 1.5f)
                rrect(13.8f, 11.6f, 3f, 8.2f, 1.5f)
                rrect(19f, 4.4f, 3f, 15.4f, 1.5f)
            }

            GI.Person -> {
                circle(12f, 8.4f, 3.5f, fill = false)
                val p = Path().apply {
                    moveTo(4.8f * s, 19.6f * s)
                    cubicTo(5.6f * s, 16.0f * s, 8.4f * s, 14.0f * s, 12f * s, 14.0f * s)
                    cubicTo(15.6f * s, 14.0f * s, 18.4f * s, 16.0f * s, 19.2f * s, 19.6f * s)
                }
                drawPath(p, tint, style = st)
            }

            GI.Tune -> {
                line(3.4f, 6.6f, 6.4f, 6.6f)
                line(10.6f, 6.6f, 20.6f, 6.6f)
                circle(8.5f, 6.6f, 2.1f, fill = false)

                line(3.4f, 12f, 13.4f, 12f)
                line(17.6f, 12f, 20.6f, 12f)
                circle(15.5f, 12f, 2.1f, fill = false)

                line(3.4f, 17.4f, 5.0f, 17.4f)
                line(9.2f, 17.4f, 20.6f, 17.4f)
                circle(7.1f, 17.4f, 2.1f, fill = false)
            }

            GI.Columns2 -> {
                rrect(3.4f, 5f, 7.6f, 14f, 2.4f)
                rrect(13f, 5f, 7.6f, 14f, 2.4f)
            }

            GI.Columns3 -> {
                rrect(3f, 5f, 5f, 14f, 2.2f)
                rrect(9.5f, 5f, 5f, 14f, 2.2f)
                rrect(16f, 5f, 5f, 14f, 2.2f)
            }

            // 「排序」：左侧三条递减的线 + 右侧一个朝下的箭头。
            // 升序时由调用方整枚旋转 180°，就变成「递增 + 朝上」，一个图形两种方向。
            GI.Sort -> {
                line(3.4f, 6.4f, 12.4f, 6.4f)
                line(3.4f, 12f, 10f, 12f)
                line(3.4f, 17.6f, 7.6f, 17.6f)
                line(17.6f, 5.6f, 17.6f, 18f)
                line(14.7f, 15.2f, 17.6f, 18f)
                line(20.5f, 15.2f, 17.6f, 18f)
            }

            GI.Back -> {
                val p = Path().apply {
                    moveTo(14.4f * s, 4.8f * s)
                    lineTo(7.2f * s, 12f * s)
                    lineTo(14.4f * s, 19.2f * s)
                }
                drawPath(p, tint, style = st)
            }

            GI.Close -> {
                line(6.2f, 6.2f, 17.8f, 17.8f)
                line(17.8f, 6.2f, 6.2f, 17.8f)
            }

            GI.Plus -> {
                line(12f, 5.4f, 12f, 18.6f)
                line(5.4f, 12f, 18.6f, 12f)
            }

            GI.Heart -> {
                val p = Path().apply {
                    moveTo(12f * s, 20.2f * s)
                    cubicTo(12f * s, 20.2f * s, 3.6f * s, 15.2f * s, 3.6f * s, 9.4f * s)
                    cubicTo(3.6f * s, 6.4f * s, 6.0f * s, 4.4f * s, 8.4f * s, 4.4f * s)
                    cubicTo(10.2f * s, 4.4f * s, 11.4f * s, 5.4f * s, 12f * s, 6.5f * s)
                    cubicTo(12.6f * s, 5.4f * s, 13.8f * s, 4.4f * s, 15.6f * s, 4.4f * s)
                    cubicTo(18.0f * s, 4.4f * s, 20.4f * s, 6.4f * s, 20.4f * s, 9.4f * s)
                    cubicTo(20.4f * s, 15.2f * s, 12f * s, 20.2f * s, 12f * s, 20.2f * s)
                    close()
                }
                drawPath(p, tint, style = st)
            }

            GI.Star -> {
                val p = Path().apply {
                    moveTo(12f * s, 3.2f * s)
                    lineTo(14.7f * s, 9.0f * s)
                    lineTo(21.0f * s, 9.7f * s)
                    lineTo(16.4f * s, 14.0f * s)
                    lineTo(17.7f * s, 20.2f * s)
                    lineTo(12f * s, 17.1f * s)
                    lineTo(6.3f * s, 20.2f * s)
                    lineTo(7.6f * s, 14.0f * s)
                    lineTo(3.0f * s, 9.7f * s)
                    lineTo(9.3f * s, 9.0f * s)
                    close()
                }
                drawPath(p, tint, style = if (filled) androidx.compose.ui.graphics.drawscope.Fill else st)
            }

            GI.More -> {
                circle(12f, 5.2f, 1.5f)
                circle(12f, 12f, 1.5f)
                circle(12f, 18.8f, 1.5f)
            }

            GI.ChevronDown -> {
                val p = Path().apply {
                    moveTo(5.6f * s, 9.2f * s)
                    lineTo(12f * s, 15.4f * s)
                    lineTo(18.4f * s, 9.2f * s)
                }
                drawPath(p, tint, style = st)
            }

            GI.Check -> {
                val p = Path().apply {
                    moveTo(5f * s, 12.6f * s)
                    lineTo(10f * s, 17.6f * s)
                    lineTo(19f * s, 6.8f * s)
                }
                drawPath(p, tint, style = st)
            }

            GI.Image -> {
                rrect(3.2f, 4.6f, 17.6f, 14.8f, 3f, fill = false)
                circle(8.8f, 9.8f, 1.6f, fill = false)
                val p = Path().apply {
                    moveTo(4.4f * s, 18.0f * s)
                    lineTo(9.8f * s, 12.6f * s)
                    lineTo(13.4f * s, 16.2f * s)
                    lineTo(16.2f * s, 13.4f * s)
                    lineTo(19.8f * s, 17.0f * s)
                }
                drawPath(p, tint, style = st)
            }

            /** 外链：方框缺右上角 + 一支朝外的箭头 */
            GI.External -> {
                line(11.4f, 5.2f, 5.6f, 5.2f)
                line(5.6f, 5.2f, 5.6f, 18.4f)
                line(5.6f, 18.4f, 18.8f, 18.4f)
                line(18.8f, 18.4f, 18.8f, 12.6f)
                line(13.0f, 11.0f, 19.2f, 4.8f)
                line(19.2f, 4.8f, 14.2f, 4.8f)
                line(19.2f, 4.8f, 19.2f, 9.8f)
            }
            /** 全屏：四角外扩的折线。刻意不画成「四个角的扇面」——那更像扫描，不像放大 */
            GI.Fullscreen -> {
                line(4f, 9f, 4f, 4.6f)
                line(4f, 4.6f, 8.4f, 4.6f)
                line(15.6f, 4.6f, 20f, 4.6f)
                line(20f, 4.6f, 20f, 9f)
                line(20f, 15f, 20f, 19.4f)
                line(20f, 19.4f, 15.6f, 19.4f)
                line(8.4f, 19.4f, 4f, 19.4f)
                line(4f, 19.4f, 4f, 15f)
            }
        }
    }
}

