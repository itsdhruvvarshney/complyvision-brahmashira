package com.example.complyvision

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Ring chart whose segments sweep in when the data changes */
@Composable
internal fun DonutChart(
    values: List<Float>,
    colors: List<Color>,
    modifier: Modifier = Modifier,
    thickness: Dp = 12.dp,
    content: @Composable BoxScope.() -> Unit = {}
) {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(values) {
        progress.snapTo(0f)
        progress.animateTo(1f, tween(1000, easing = FastOutSlowInEasing))
    }
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = thickness.toPx()
            val topLeft = Offset(stroke / 2, stroke / 2)
            val arcSize = Size(size.width - stroke, size.height - stroke)
            drawArc(Color(0xFFE6EEF8), 0f, 360f, false, topLeft, arcSize, style = Stroke(stroke))

            val total = values.sum()
            if (total > 0f) {
                val gap = if (values.count { it > 0f } > 1) 3f else 0f
                var start = -90f
                values.forEachIndexed { i, v ->
                    if (v > 0f) {
                        val sweep = v / total * 360f
                        val drawn = ((sweep - gap) * progress.value).coerceAtLeast(0f)
                        drawArc(colors[i], start + gap / 2, drawn, false, topLeft, arcSize, style = Stroke(stroke))
                        start += sweep
                    }
                }
            }
        }
        content()
    }
}

/** Smooth area line; null entries are skipped, so sparse periods still draw cleanly */
@Composable
internal fun Sparkline(values: List<Double?>, color: Color, modifier: Modifier = Modifier) {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(values) {
        progress.snapTo(0f)
        progress.animateTo(1f, tween(1100, easing = FastOutSlowInEasing))
    }
    Canvas(modifier) {
        val pts = values.mapIndexedNotNull { i, v -> v?.let { i to it } }
        if (pts.size < 2) return@Canvas

        val lo = pts.minOf { it.second }
        val hi = pts.maxOf { it.second }
        val pad = size.height * 0.14f
        val span = (hi - lo).takeIf { it > 0.0001 } ?: 1.0
        val lastIndex = (values.size - 1).coerceAtLeast(1)

        val xy = pts.map { (i, v) ->
            val x = size.width * i / lastIndex
            val y = if (hi - lo < 0.0001) size.height / 2 else (size.height - pad - ((v - lo) / span).toFloat() * (size.height - pad * 2))
            Offset(x, y)
        }

        val line = Path().apply {
            moveTo(xy.first().x, xy.first().y)
            for (i in 1 until xy.size) {
                val a = xy[i - 1]
                val b = xy[i]
                val mid = (a.x + b.x) / 2
                cubicTo(mid, a.y, mid, b.y, b.x, b.y)
            }
        }
        val area = Path().apply {
            addPath(line)
            lineTo(xy.last().x, size.height)
            lineTo(xy.first().x, size.height)
            close()
        }

        clipRect(right = size.width * progress.value) {
            drawPath(area, Brush.verticalGradient(listOf(color.copy(alpha = 0.32f), color.copy(alpha = 0.02f))))
            drawPath(line, color, style = Stroke(width = 2.6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}

/** Weekly stacked bars: passed (bottom), failed, under review (top) */
@Composable
internal fun StackedBars(data: List<WeekPoint>, modifier: Modifier = Modifier) {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(data) {
        progress.snapTo(0f)
        progress.animateTo(1f, tween(900, easing = FastOutSlowInEasing))
    }
    Canvas(modifier) {
        if (data.isEmpty()) return@Canvas
        val maxTotal = data.maxOf { it.passed + it.failed + it.review }.coerceAtLeast(1)
        val slot = size.width / data.size
        val barW = slot * 0.56f
        val base = size.height
        val grid = Color(0xFFE3ECF8)
        for (g in 1..3) {
            val y = base - base * g / 4f
            drawLine(grid, Offset(0f, y), Offset(size.width, y), strokeWidth = 1.dp.toPx())
        }
        data.forEachIndexed { i, w ->
            val x = slot * i + (slot - barW) / 2
            var top = base
            val corner = (barW * 0.22f).coerceAtMost(6.dp.toPx())
            val segments = listOf(w.passed to AC.Green, w.failed to AC.Red, w.review to AC.Amber).filter { it.first > 0 }
            segments.forEachIndexed { index, (count, color) ->
                val hgt = base * count / maxTotal * progress.value
                val isTop = index == segments.lastIndex
                if (isTop && hgt > corner * 2) {
                    // Round only the top of the stack: overdraw a little into the segment below
                    drawRoundRect(color, Offset(x, top - hgt), Size(barW, hgt + corner), CornerRadius(corner))
                } else {
                    drawRect(color, Offset(x, top - hgt), Size(barW, hgt))
                }
                top -= hgt
            }
        }
    }
}

/** Month-by-month bars of an inspector's compliance rate (0-100) */
@Composable
internal fun RateBars(points: List<MonthPoint>, modifier: Modifier = Modifier) {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(points) {
        progress.snapTo(0f)
        progress.animateTo(1f, tween(900, easing = FastOutSlowInEasing))
    }
    Canvas(modifier) {
        if (points.isEmpty()) return@Canvas
        val slot = size.width / points.size
        val barW = slot * 0.5f
        for (g in 1..3) {
            val y = size.height - size.height * g / 4f
            drawLine(Color(0xFFE3ECF8), Offset(0f, y), Offset(size.width, y), strokeWidth = 1.dp.toPx())
        }
        points.forEachIndexed { i, p ->
            val x = slot * i + (slot - barW) / 2
            val hgt = size.height * (p.rate / 100.0).toFloat() * progress.value
            val c = if (p.total == 0) Color(0xFFD5DFEE) else rateColor(p.rate)
            drawRoundRect(
                c.copy(alpha = if (p.total == 0) 0.6f else 1f),
                Offset(x, size.height - hgt.coerceAtLeast(3.dp.toPx())),
                Size(barW, hgt.coerceAtLeast(3.dp.toPx())),
                CornerRadius(barW * 0.3f)
            )
        }
    }
}
