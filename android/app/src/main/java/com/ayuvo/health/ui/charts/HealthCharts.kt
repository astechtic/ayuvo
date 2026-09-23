package com.ayuvo.health.ui.charts

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ayuvo.health.data.health.HealthChartPoint
import com.ayuvo.health.R
import com.ayuvo.health.data.metrics.MetricsReference
import com.ayuvo.health.ui.theme.AppColors
import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.roundToInt

/** How one chart reads a bucket. */
enum class HealthChartStyle { BAR, LINE, RANGE }

/**
 * Bucketed metric chart (docs/charts.md): bars (SUM/DURATION/COUNT), monotone line + area
 * (AVERAGE/LATEST) or min–max capsules with an average dot (MIN_MAX, blood pressure as two series).
 * Y gridlines come from `nice_ticks` with labels on the lines; x labels sit under their bucket.
 * A tap selects a bucket (or drills through [onBucketTap]); a drag scrubs. The selection is hoisted
 * so the headline above the chart can show it. Always drawn LTR.
 */
@Composable
fun HealthBucketChart(
    points: List<HealthChartPoint>,
    style: HealthChartStyle,
    color: Color,
    xLabels: List<Pair<Int, String>>,
    formatValue: (Double) -> String,
    selected: Int?,
    onSelect: (Int?) -> Unit,
    modifier: Modifier = Modifier,
    secondaryColor: Color? = null,
    summary: String = "",
    /** Overrides the plotted value for BAR/LINE (e.g. a daily average on week buckets). */
    valueSelector: ((HealthChartPoint) -> Double?)? = null,
    /** Draws a dashed goal rule labelled "Goal" and keeps it inside the y range. */
    goalValue: Double? = null,
    /** Called for a tap on a bucket with data; true when the tap was handled (drill-down). */
    onBucketTap: ((Int) -> Boolean)? = null,
    /** Selection rule / marker colour; the theme accent by default. */
    scrubColor: Color = AppColors.Calorie
) {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        val valuesOf: (HealthChartPoint) -> Double? = chartValueOf(style, valueSelector)
        fun hasValue(p: HealthChartPoint): Boolean = if (style == HealthChartStyle.RANGE) p.min != null else valuesOf(p) != null
        val allValues = points.flatMap { p ->
            when (style) {
                HealthChartStyle.BAR, HealthChartStyle.LINE -> listOfNotNull(valuesOf(p))
                HealthChartStyle.RANGE -> listOfNotNull(p.min, p.max, p.v2Min, p.v2Max)
            }
        } + listOfNotNull(goalValue)
        val ticks = remember(allValues, style) {
            MetricsReference.niceTicks(allValues.minOrNull() ?: 0.0, allValues.maxOrNull() ?: 0.0, 4, style == HealthChartStyle.BAR)
        }
        val gridColor = chartGridColor()
        val baseline = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.22f)
        val secondary = chartSecondaryLabel()
        val surface = MaterialTheme.colorScheme.surface
        val haptic = LocalHapticFeedback.current
        val density = LocalDensity.current
        val reveal = rememberReveal(points.map { it.bucketStartMs to it.bucketEndMs })
        val n = points.size.coerceAtLeast(1)
        val topInsetPx = with(density) { ChartSpec.PlotTopInset.toPx() }
        val cornerPx = with(density) { ChartSpec.BarCorner.toPx() }
        val maxCapsulePx = with(density) { 10.dp.toPx() }
        val lineWidthPx = with(density) { 2.dp.toPx() }
        val dotPx = with(density) { 3.dp.toPx() }
        val goalLabel = stringResource(R.string.chart_goal)

        fun yFraction(v: Double, heightPx: Float): Float {
            val span = (ticks.max - ticks.min).takeIf { it > 0 } ?: 1.0
            val frac = ((v - ticks.min) / span).toFloat()
            return (topInsetPx + (1f - frac) * (heightPx - topInsetPx)) / heightPx
        }

        Column(modifier.semantics { contentDescription = summary }) {
            Row(Modifier.fillMaxWidth().height(ChartSpec.PlotHeight)) {
                BoxWithConstraints(Modifier.weight(1f).fillMaxSize()) {
                    val heightPx = with(density) { maxHeight.toPx() }
                    val widthPx = with(density) { maxWidth.toPx() }
                    val slot = widthPx / n
                    Canvas(
                        Modifier
                            .fillMaxSize()
                            .bucketGestures(
                                key = points,
                                count = points.size,
                                onTap = { idx ->
                                    val p = points[idx]
                                    when {
                                        !hasValue(p) -> onSelect(null)
                                        onBucketTap?.invoke(idx) == true -> Unit
                                        else -> onSelect(idx)
                                    }
                                },
                                onScrub = { idx ->
                                    if (idx != selected && hasValue(points[idx])) {
                                        onSelect(idx)
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    }
                                }
                            )
                    ) {
                        val w = size.width
                        val h = size.height
                        fun yOf(v: Double) = yFraction(v, h) * h
                        ticks.ticks.forEach { t -> drawLine(gridColor, Offset(0f, yOf(t)), Offset(w, yOf(t)), strokeWidth = 1f) }
                        drawLine(baseline, Offset(0f, h - 0.5f), Offset(w, h - 0.5f), strokeWidth = 1f)
                        fun alphaFor(i: Int) = if (selected != null && selected != i) ChartSpec.FADE_ALPHA else 1f
                        when (style) {
                            HealthChartStyle.BAR -> {
                                val barW = (slot * ChartSpec.BAR_WIDTH).coerceAtLeast(2f)
                                val zeroY = yOf(0.0)
                                points.forEachIndexed { i, p ->
                                    val v = valuesOf(p) ?: return@forEachIndexed
                                    if (v <= 0.0) return@forEachIndexed
                                    val top = zeroY - (zeroY - yOf(v)) * reveal
                                    drawPath(topRoundedBar(i * slot + (slot - barW) / 2f, top, barW, zeroY, cornerPx), color.copy(alpha = alphaFor(i)))
                                }
                            }
                            HealthChartStyle.LINE -> {
                                val offsets = points.mapIndexedNotNull { i, p -> valuesOf(p)?.let { Offset(i * slot + slot / 2f, yOf(it)) } }
                                clipRect(right = w * reveal) {
                                    if (offsets.size >= 2) {
                                        drawPath(monotoneAreaPath(offsets, h), brush = Brush.verticalGradient(listOf(color.copy(alpha = 0.18f), color.copy(alpha = 0.0f)), startY = 0f, endY = h))
                                        drawPath(monotonePath(offsets), color, style = Stroke(width = lineWidthPx))
                                    }
                                    if (offsets.size <= ChartSpec.MAX_POINTS || offsets.size == 1) offsets.forEach { drawCircle(color, radius = dotPx, center = it) }
                                }
                            }
                            HealthChartStyle.RANGE -> {
                                val barW = (slot * ChartSpec.BAR_WIDTH).coerceIn(3f, maxCapsulePx)
                                points.forEachIndexed { i, p ->
                                    val lo = p.min ?: return@forEachIndexed
                                    val hi = p.max ?: lo
                                    val cx = i * slot + slot / 2f
                                    val a = alphaFor(i)
                                    val top = yOf(hi)
                                    val bottom = yOf(lo)
                                    drawRoundRect(color.copy(alpha = 0.55f * a * reveal), topLeft = Offset(cx - barW / 2f, top), size = Size(barW, (bottom - top).coerceAtLeast(barW)), cornerRadius = CornerRadius(barW / 2f, barW / 2f))
                                    p.avg?.let { drawCircle(color.copy(alpha = a), radius = barW / 2f + 1f, center = Offset(cx, yOf(it))) }
                                    if (secondaryColor != null && p.v2Min != null && p.v2Max != null) {
                                        val t2 = yOf(p.v2Max)
                                        val b2 = yOf(p.v2Min)
                                        drawRoundRect(secondaryColor.copy(alpha = 0.55f * a * reveal), topLeft = Offset(cx - barW / 2f, t2), size = Size(barW, (b2 - t2).coerceAtLeast(barW)), cornerRadius = CornerRadius(barW / 2f, barW / 2f))
                                        p.v2Avg?.let { drawCircle(secondaryColor.copy(alpha = a), radius = barW / 2f + 1f, center = Offset(cx, yOf(it))) }
                                    }
                                }
                            }
                        }
                        goalValue?.let { goal ->
                            val gy = yOf(goal)
                            drawLine(secondary, Offset(0f, gy), Offset(w, gy), strokeWidth = 1.5f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 6f)))
                        }
                        selected?.takeIf { it in points.indices }?.let { idx ->
                            val x = idx * slot + slot / 2f
                            drawLine(scrubColor.copy(alpha = 0.6f), Offset(x, 0f), Offset(x, h), strokeWidth = 1.5f)
                            if (style == HealthChartStyle.LINE) valuesOf(points[idx])?.let {
                                drawCircle(surface, radius = dotPx * 2.4f, center = Offset(x, yOf(it)))
                                drawCircle(scrubColor, radius = dotPx * 1.6f, center = Offset(x, yOf(it)))
                            }
                        }
                    }
                    goalValue?.let { goal ->
                        val gy = yFraction(goal, heightPx) * heightPx
                        Text(
                            goalLabel,
                            fontSize = 10.sp,
                            color = secondary,
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .offset { IntOffset(0, (gy - with(density) { 14.dp.toPx() }).roundToInt().coerceAtLeast(0)) }
                                .background(surface.copy(alpha = 0.85f), RoundedCornerShape(4.dp))
                                .padding(horizontal = 4.dp)
                        )
                    }
                }
                YAxisLabels(
                    fractions = with(density) { ticks.ticks.map { yFraction(it, ChartSpec.PlotHeight.toPx()) } },
                    labels = tickLabels(ticks.ticks, formatValue),
                    modifier = Modifier.width(ChartSpec.YLabelWidth).fillMaxSize()
                )
            }
            XAxisLabels(
                fractions = xLabels.map { (i, _) -> (i + 0.5f) / n },
                labels = xLabels.map { it.second },
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp, end = ChartSpec.YLabelWidth)
            )
        }
    }
}

/** The value a chart plots for [style] (BAR sum, LINE/RANGE average), unless [valueSelector] overrides it. */
fun chartValueOf(style: HealthChartStyle, valueSelector: ((HealthChartPoint) -> Double?)? = null): (HealthChartPoint) -> Double? =
    valueSelector?.takeIf { style != HealthChartStyle.RANGE } ?: when (style) {
        HealthChartStyle.BAR -> { p -> p.sum }
        HealthChartStyle.LINE, HealthChartStyle.RANGE -> { p -> p.avg }
    }

/** Menstruation period band: one cell per day in the range. */
@Composable
fun PeriodBandChart(days: List<LocalDate>, marked: Set<LocalDate>, color: Color, xLabels: List<String>, modifier: Modifier = Modifier) {
    val secondary = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Column(modifier) {
            Canvas(Modifier.fillMaxWidth().height(40.dp).clip(RoundedCornerShape(8.dp)).background(color.copy(alpha = 0.06f))) {
                val n = days.size.coerceAtLeast(1)
                val slot = size.width / n
                days.forEachIndexed { i, day ->
                    if (day in marked) drawRoundRect(color, topLeft = Offset(i * slot + 1f, 4f), size = Size((slot - 2f).coerceAtLeast(1f), size.height - 8f), cornerRadius = CornerRadius(3f, 3f))
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                xLabels.forEach { Text(it, fontSize = 11.sp, color = secondary) }
            }
        }
    }
}

/** Picks up to [max] evenly spread labels from [all]. */
fun spreadLabels(all: List<String>, max: Int = 5): List<String> {
    if (all.size <= max) return all
    return pickXLabelIndices(all.size, max).map { all[it] }
}

@Suppress("unused")
private fun nearlyEqual(a: Double, b: Double) = abs(a - b) < 1e-9
