package com.ayuvo.health.ui.charts

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Shared chart geometry (docs/charts.md "Visual spec"). */
object ChartSpec {
    val PlotHeight = 200.dp
    val YLabelWidth = 44.dp
    val XLabelHeight = 18.dp
    val PlotTopInset = 10.dp
    val BarCorner = 4.dp
    const val BAR_WIDTH = 0.6f
    const val GRID_ALPHA = 0.08f
    const val FADE_ALPHA = 0.4f
    const val REVEAL_MS = 250
    const val MAX_POINTS = 40
}

@Composable
internal fun chartSecondaryLabel(): Color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)

@Composable
internal fun chartGridColor(): Color = MaterialTheme.colorScheme.onSurface.copy(alpha = ChartSpec.GRID_ALPHA)

/** 0 → 1 over [ChartSpec.REVEAL_MS] every time [key] changes (range or anchor). */
@Composable
internal fun rememberReveal(key: Any?): Float {
    val anim = remember { Animatable(0f) }
    LaunchedEffect(key) {
        anim.snapTo(0f)
        anim.animateTo(1f, tween(ChartSpec.REVEAL_MS))
    }
    return anim.value
}

/**
 * Labels placed at vertical positions ([fractions] of the height, 0 = top), vertically centred
 * on their gridline and clamped inside the column.
 */
@Composable
internal fun YAxisLabels(fractions: List<Float>, labels: List<String>, modifier: Modifier = Modifier) {
    val color = chartSecondaryLabel()
    Layout(content = { labels.forEach { Text(it, fontSize = 10.sp, color = color, maxLines = 1) } }, modifier = modifier) { measurables, constraints ->
        val placeables = measurables.map { it.measure(constraints.copy(minWidth = 0, minHeight = 0)) }
        val h = constraints.maxHeight
        layout(constraints.maxWidth, h) {
            placeables.forEachIndexed { i, p ->
                val y = (fractions[i] * h - p.height / 2f).roundToInt().coerceIn(0, (h - p.height).coerceAtLeast(0))
                p.place(4.dp.roundToPx(), y)
            }
        }
    }
}

/**
 * Labels at horizontal positions ([fractions] of the width), clamped inside the row: centred on
 * their gridline, or with their leading edge on it when [leadingAligned] (Apple's time axis).
 */
@Composable
internal fun XAxisLabels(
    fractions: List<Float>,
    labels: List<String>,
    modifier: Modifier = Modifier,
    leadingAligned: Boolean = false
) {
    val color = chartSecondaryLabel()
    Layout(
        content = { labels.forEach { Text(it, fontSize = 11.sp, color = color, maxLines = 1, textAlign = TextAlign.Center) } },
        modifier = modifier
    ) { measurables, constraints ->
        val placeables = measurables.map { it.measure(constraints.copy(minWidth = 0, minHeight = 0)) }
        val w = constraints.maxWidth
        val height = placeables.maxOfOrNull { it.height } ?: 0
        layout(w, height) {
            placeables.forEachIndexed { i, p ->
                val anchor = fractions[i] * w
                val raw = if (leadingAligned) anchor + 3.dp.toPx() else anchor - p.width / 2f
                p.place(raw.roundToInt().coerceIn(0, (w - p.width).coerceAtLeast(0)), 0)
            }
        }
    }
}

/** Tick labels from [format]; falls back to plain numbers when the formatter would repeat a label. */
internal fun tickLabels(ticks: List<Double>, format: (Double) -> String): List<String> {
    val formatted = ticks.map(format)
    return if (formatted.distinct().size == formatted.size) formatted else ticks.map(::formatTick)
}

/** Tap selects (or drills through [onTap]); a horizontal drag scrubs bucket by bucket. */
internal fun Modifier.bucketGestures(key: Any?, count: Int, onTap: (Int) -> Unit, onScrub: (Int) -> Unit): Modifier {
    fun index(x: Float, width: Int): Int = ((x / width.coerceAtLeast(1)) * count).toInt().coerceIn(0, (count - 1).coerceAtLeast(0))
    return this
        .pointerInput(key, count) { detectTapGestures { o -> if (count > 0) onTap(index(o.x, size.width)) } }
        .pointerInput(key, count) {
            var x = 0f
            detectHorizontalDragGestures(
                onDragStart = { o -> x = o.x; if (count > 0) onScrub(index(x, size.width)) },
                onHorizontalDrag = { change, amount ->
                    x = (x + amount).coerceIn(0f, size.width.toFloat())
                    if (count > 0) onScrub(index(x, size.width))
                    change.consume()
                }
            )
        }
}

/** Rectangle with only the top corners rounded. */
internal fun topRoundedBar(left: Float, top: Float, width: Float, bottom: Float, radius: Float): Path {
    val r = radius.coerceAtMost(width / 2f).coerceAtMost((bottom - top).coerceAtLeast(0f))
    return Path().apply {
        addRoundRect(RoundRect(left, top, left + width, bottom, CornerRadius(r, r), CornerRadius(r, r), CornerRadius.Zero, CornerRadius.Zero))
    }
}

/** Monotone cubic (Fritsch–Carlson) through [points]: no overshoot above or below the data. */
internal fun monotonePath(points: List<Offset>): Path {
    val path = Path()
    if (points.isEmpty()) return path
    path.moveTo(points[0].x, points[0].y)
    if (points.size == 1) return path
    val tangents = monotoneTangents(points)
    for (i in 0 until points.size - 1) {
        val p0 = points[i]
        val p1 = points[i + 1]
        val h = p1.x - p0.x
        path.cubicTo(p0.x + h / 3f, p0.y + tangents[i] * h / 3f, p1.x - h / 3f, p1.y - tangents[i + 1] * h / 3f, p1.x, p1.y)
    }
    return path
}

internal fun monotoneAreaPath(points: List<Offset>, bottom: Float): Path {
    val path = monotonePath(points)
    if (points.isEmpty()) return path
    path.lineTo(points.last().x, bottom)
    path.lineTo(points.first().x, bottom)
    path.close()
    return path
}

internal fun monotoneTangents(points: List<Offset>): FloatArray {
    val n = points.size
    val d = FloatArray(n - 1) { i ->
        val dx = points[i + 1].x - points[i].x
        if (dx == 0f) 0f else (points[i + 1].y - points[i].y) / dx
    }
    val m = FloatArray(n)
    m[0] = d[0]
    m[n - 1] = d[n - 2]
    for (i in 1 until n - 1) m[i] = if (d[i - 1] * d[i] <= 0f) 0f else (d[i - 1] + d[i]) / 2f
    for (i in 0 until n - 1) {
        if (d[i] == 0f) {
            m[i] = 0f
            m[i + 1] = 0f
        } else {
            val a = m[i] / d[i]
            val b = m[i + 1] / d[i]
            val s = a * a + b * b
            if (s > 9f) {
                val t = 3f / sqrt(s)
                m[i] = t * a * d[i]
                m[i + 1] = t * b * d[i]
            }
        }
    }
    return m
}

/** Clock text following the system 12/24-hour setting. */
object ChartClock {
    private fun pattern(is24: Boolean, short: Boolean) = when {
        is24 -> "HH:mm"
        short -> "h a"
        else -> "h:mm a"
    }

    fun time(ms: Long, zone: ZoneId, is24: Boolean, locale: Locale = Locale.getDefault(), short: Boolean = false): String =
        DateTimeFormatter.ofPattern(pattern(is24, short), locale).format(Instant.ofEpochMilli(ms).atZone(zone))

    /** Wall-clock text of a sleep clock offset (minutes from 12:00 the day before the wake day). */
    fun offset(offsetMin: Double, is24: Boolean, locale: Locale = Locale.getDefault(), short: Boolean = false): String {
        val m = ((offsetMin.roundToInt() + 720) % 1440 + 1440) % 1440
        return DateTimeFormatter.ofPattern(pattern(is24, short), locale).format(LocalTime.of(m / 60, m % 60))
    }
}
