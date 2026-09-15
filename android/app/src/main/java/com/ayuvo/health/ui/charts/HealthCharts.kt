package com.ayuvo.health.ui.charts

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ayuvo.health.data.health.HealthChartPoint
import com.ayuvo.health.data.health.HealthSleepCodes
import com.ayuvo.health.data.health.SleepNight
import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.roundToInt

/** How one chart reads a bucket. */
enum class HealthChartStyle { BAR, LINE, RANGE }

/**
 * Bucketed chart for the Health Data detail screen: bars (SUM/DURATION/COUNT), line + area
 * (AVERAGE/LATEST) or min–max range bars with an average dot (MIN_MAX, blood pressure as two
 * series). Scrubbing selects the nearest bucket and shows a tooltip. Always drawn LTR.
 */
@Composable
fun HealthBucketChart(
    points: List<HealthChartPoint>,
    style: HealthChartStyle,
    color: Color,
    xLabels: List<String>,
    formatValue: (Double) -> String,
    tooltipLabel: (Int) -> String,
    modifier: Modifier = Modifier,
    secondaryColor: Color? = null,
    summary: String = ""
) {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        val valuesOf: (HealthChartPoint) -> Double? = when (style) {
            HealthChartStyle.BAR -> { p -> p.sum }
            HealthChartStyle.LINE -> { p -> p.avg }
            HealthChartStyle.RANGE -> { p -> p.avg }
        }
        val allValues = points.flatMap { p ->
            when (style) {
                HealthChartStyle.BAR -> listOfNotNull(p.sum)
                HealthChartStyle.LINE -> listOfNotNull(p.avg)
                HealthChartStyle.RANGE -> listOfNotNull(p.min, p.max, p.v2Min, p.v2Max)
            }
        }
        val yMinRaw = if (style == HealthChartStyle.BAR) 0.0 else (allValues.minOrNull() ?: 0.0)
        val yMaxRaw = allValues.maxOrNull() ?: 1.0
        val pad = if (style == HealthChartStyle.BAR) 0.0 else maxOf((yMaxRaw - yMinRaw) * 0.15, 1.0)
        val yMin = yMinRaw - pad
        val yMax = maxOf(yMaxRaw + pad, yMin + 1.0)
        val ticks = remember(yMin, yMax) { niceAxisTicks(yMin, yMax, 4) }
        val gridColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.09f)
        val secondary = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
        val surface = MaterialTheme.colorScheme.surface
        val haptic = LocalHapticFeedback.current
        val density = LocalDensity.current
        var selected by remember(points) { mutableStateOf<Int?>(null) }

        Column(modifier.semantics { contentDescription = summary }) {
            Row(Modifier.fillMaxWidth().height(190.dp)) {
                BoxWithConstraints(
                    Modifier
                        .weight(1f)
                        .fillMaxSize()
                        .clip(RoundedCornerShape(10.dp))
                        .background(color.copy(alpha = 0.04f))
                ) {
                    val widthPx = with(density) { maxWidth.toPx() }
                    val n = points.size.coerceAtLeast(1)
                    val slot = widthPx / n
                    fun yOf(v: Double, h: Float): Float = h - (((v - yMin) / (yMax - yMin)).toFloat() * h)
                    fun selectNearest(x: Float) {
                        val idx = (x / slot).toInt().coerceIn(0, n - 1)
                        if (idx != selected) {
                            selected = idx
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        }
                    }
                    Canvas(
                        Modifier
                            .fillMaxSize()
                            .pointerInput(points) {
                                var gestureX = 0f
                                detectHorizontalDragGestures(
                                    onDragStart = { offset -> gestureX = offset.x; selectNearest(gestureX) },
                                    onDragEnd = { selected = null },
                                    onDragCancel = { selected = null },
                                    onHorizontalDrag = { change, amount ->
                                        gestureX = (gestureX + amount).coerceIn(0f, size.width.toFloat())
                                        selectNearest(gestureX)
                                        change.consume()
                                    }
                                )
                            }
                    ) {
                        val w = size.width
                        val h = size.height
                        ticks.forEach { tick ->
                            drawLine(gridColor, Offset(0f, yOf(tick, h)), Offset(w, yOf(tick, h)), strokeWidth = 1f)
                        }
                        when (style) {
                            HealthChartStyle.BAR -> {
                                val barW = (slot * 0.62f).coerceAtLeast(2f)
                                points.forEachIndexed { i, p ->
                                    val v = p.sum ?: return@forEachIndexed
                                    if (v <= 0.0) return@forEachIndexed
                                    val x = i * slot + (slot - barW) / 2f
                                    val top = yOf(v, h)
                                    drawRoundRect(
                                        brush = Brush.verticalGradient(listOf(color, color.copy(alpha = 0.7f)), startY = top, endY = h),
                                        topLeft = Offset(x, top),
                                        size = Size(barW, h - top),
                                        cornerRadius = CornerRadius(5f, 5f)
                                    )
                                }
                            }
                            HealthChartStyle.LINE -> {
                                val offsets = points.mapIndexedNotNull { i, p -> p.avg?.let { Offset(i * slot + slot / 2f, yOf(it, h)) } }
                                clipRect {
                                    if (offsets.size >= 2) {
                                        drawPath(smoothTrendAreaPath(offsets, h), brush = Brush.verticalGradient(listOf(color.copy(alpha = 0.14f), color.copy(alpha = 0.01f)), startY = 0f, endY = h))
                                        drawPath(smoothTrendPath(offsets), color, style = Stroke(width = 5f))
                                    }
                                    if (offsets.size <= 40) offsets.forEach { drawCircle(color, radius = 5f, center = it) }
                                }
                            }
                            HealthChartStyle.RANGE -> {
                                val barW = (slot * 0.34f).coerceAtLeast(3f)
                                points.forEachIndexed { i, p ->
                                    val lo = p.min ?: return@forEachIndexed
                                    val hi = p.max ?: lo
                                    val cx = i * slot + slot / 2f
                                    val top = yOf(hi, h)
                                    val bottom = yOf(lo, h)
                                    drawRoundRect(color.copy(alpha = 0.55f), topLeft = Offset(cx - barW / 2f, top), size = Size(barW, (bottom - top).coerceAtLeast(barW)), cornerRadius = CornerRadius(barW / 2f, barW / 2f))
                                    p.avg?.let { drawCircle(color, radius = barW / 2f + 1f, center = Offset(cx, yOf(it, h))) }
                                    if (secondaryColor != null && p.v2Min != null && p.v2Max != null) {
                                        val t2 = yOf(p.v2Max, h)
                                        val b2 = yOf(p.v2Min, h)
                                        drawRoundRect(secondaryColor.copy(alpha = 0.55f), topLeft = Offset(cx - barW / 2f, t2), size = Size(barW, (b2 - t2).coerceAtLeast(barW)), cornerRadius = CornerRadius(barW / 2f, barW / 2f))
                                        p.v2Avg?.let { drawCircle(secondaryColor, radius = barW / 2f + 1f, center = Offset(cx, yOf(it, h))) }
                                    }
                                }
                            }
                        }
                        selected?.let { idx ->
                            val x = idx * slot + slot / 2f
                            drawLine(color.copy(alpha = 0.6f), Offset(x, 0f), Offset(x, h), strokeWidth = 2.5f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(7f, 7f)))
                            valuesOf(points[idx])?.let { drawCircle(surface, radius = 10f, center = Offset(x, yOf(it, h))); drawCircle(color, radius = 6f, center = Offset(x, yOf(it, h))) }
                        }
                    }
                    selected?.let { idx ->
                        val p = points[idx]
                        val tooltipWidth = 124.dp
                        val tooltipWidthPx = with(density) { tooltipWidth.toPx() }
                        val left = (idx * slot + slot / 2f - tooltipWidthPx / 2f).coerceIn(0f, (widthPx - tooltipWidthPx).coerceAtLeast(0f))
                        Column(
                            Modifier
                                .offset { IntOffset(left.roundToInt(), with(density) { 6.dp.roundToPx() }) }
                                .width(tooltipWidth)
                                .shadow(8.dp, RoundedCornerShape(10.dp))
                                .clip(RoundedCornerShape(10.dp))
                                .background(surface.copy(alpha = 0.96f))
                                .border(0.75.dp, color.copy(alpha = 0.24f), RoundedCornerShape(10.dp))
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(tooltipLabel(idx), fontSize = 10.sp, fontWeight = FontWeight.Medium, color = secondary, maxLines = 1)
                            val text = when (style) {
                                HealthChartStyle.BAR -> p.sum?.let(formatValue) ?: "—"
                                HealthChartStyle.LINE -> p.avg?.let(formatValue) ?: "—"
                                HealthChartStyle.RANGE -> if (p.min != null && p.max != null) "${formatValue(p.min)} – ${formatValue(p.max)}" else "—"
                            }
                            Text(text, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                            if (secondaryColor != null && p.v2Min != null && p.v2Max != null) {
                                Text("${formatValue(p.v2Min)} – ${formatValue(p.v2Max)}", fontSize = 11.sp, color = secondaryColor, maxLines = 1)
                            }
                        }
                    }
                }
                Column(Modifier.width(44.dp).fillMaxSize().padding(start = 4.dp), verticalArrangement = Arrangement.SpaceBetween) {
                    ticks.reversed().forEach { Text(formatValue(it), fontSize = 10.sp, color = secondary, maxLines = 1) }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 4.dp, end = 44.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                xLabels.forEach { Text(it, fontSize = 11.sp, color = secondary) }
            }
        }
    }
}

/** D view: one night as a horizontal stage strip (awake / light / deep / REM / in bed). */
@Composable
fun SleepStageStrip(night: SleepNight, stages: List<Triple<Int, Long, Long>>, modifier: Modifier = Modifier) {
    val total = (night.endMs - night.startMs).coerceAtLeast(1L).toFloat()
    val colors = sleepStageColors()
    Column(modifier) {
        Canvas(Modifier.fillMaxWidth().height(56.dp).clip(RoundedCornerShape(8.dp))) {
            drawRect(colors.getValue(HealthSleepCodes.IN_BED).copy(alpha = 0.25f))
            for ((code, s, e) in stages) {
                if (code == HealthSleepCodes.OUT_OF_BED) continue
                val x0 = ((s - night.startMs) / total) * size.width
                val x1 = ((e - night.startMs) / total) * size.width
                val laneTop = when (code) {
                    HealthSleepCodes.AWAKE -> 0f
                    HealthSleepCodes.REM -> size.height * 0.25f
                    HealthSleepCodes.LIGHT, HealthSleepCodes.ASLEEP_UNSPECIFIED -> size.height * 0.5f
                    HealthSleepCodes.DEEP -> size.height * 0.75f
                    else -> 0f
                }
                drawRect(colors.getValue(code), topLeft = Offset(x0, laneTop), size = Size((x1 - x0).coerceAtLeast(1f), size.height * 0.25f))
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            listOf(HealthSleepCodes.AWAKE to "Awake", HealthSleepCodes.REM to "REM", HealthSleepCodes.LIGHT to "Light", HealthSleepCodes.DEEP to "Deep").forEach { (code, label) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.width(10.dp).height(10.dp).clip(RoundedCornerShape(3.dp)).background(colors.getValue(code)))
                    Spacer(Modifier.width(4.dp))
                    Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                }
            }
        }
    }
}

/** W/M/6M/Y: stacked asleep (light/deep/REM) + awake per night. */
@Composable
fun SleepStackedBars(days: List<LocalDate>, nights: Map<String, SleepNight>, xLabels: List<String>, modifier: Modifier = Modifier) {
    val colors = sleepStageColors()
    val secondary = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
    val maxS = nights.values.maxOfOrNull { it.asleepS + it.awakeS }?.coerceAtLeast(3600.0) ?: 8 * 3600.0
    val top = ((maxS / 3600.0).toInt() + 1) * 3600.0
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Column(modifier) {
            Row(Modifier.fillMaxWidth().height(190.dp)) {
                Canvas(Modifier.weight(1f).fillMaxSize().clip(RoundedCornerShape(10.dp)).background(colors.getValue(HealthSleepCodes.DEEP).copy(alpha = 0.04f))) {
                    val n = days.size.coerceAtLeast(1)
                    val slot = size.width / n
                    val barW = (slot * 0.6f).coerceAtLeast(2f)
                    days.forEachIndexed { i, day ->
                        val night = nights[day.toString()] ?: return@forEachIndexed
                        val x = i * slot + (slot - barW) / 2f
                        var y = size.height
                        val unspecified = (night.asleepS - night.lightS - night.deepS - night.remS).coerceAtLeast(0.0)
                        for ((code, seconds) in listOf(HealthSleepCodes.DEEP to night.deepS, HealthSleepCodes.LIGHT to night.lightS + unspecified, HealthSleepCodes.REM to night.remS, HealthSleepCodes.AWAKE to night.awakeS)) {
                            if (seconds <= 0.0) continue
                            val hPx = (seconds / top * size.height).toFloat()
                            drawRect(colors.getValue(code), topLeft = Offset(x, y - hPx), size = Size(barW, hPx))
                            y -= hPx
                        }
                    }
                }
                Column(Modifier.width(44.dp).fillMaxSize().padding(start = 4.dp), verticalArrangement = Arrangement.SpaceBetween) {
                    listOf(top, top / 2, 0.0).forEach { Text("${(it / 3600).toInt()}h", fontSize = 10.sp, color = secondary) }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 4.dp, end = 44.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                xLabels.forEach { Text(it, fontSize = 11.sp, color = secondary) }
            }
        }
    }
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

@Composable
private fun sleepStageColors(): Map<Int, Color> = mapOf(
    HealthSleepCodes.IN_BED to Color(0xFF5E5CE6),
    HealthSleepCodes.ASLEEP_UNSPECIFIED to Color(0xFF64D2FF),
    HealthSleepCodes.AWAKE to Color(0xFFFF9F0A),
    HealthSleepCodes.LIGHT to Color(0xFF64D2FF),
    HealthSleepCodes.DEEP to Color(0xFF0A84FF),
    HealthSleepCodes.REM to Color(0xFFBF5AF2),
    HealthSleepCodes.OUT_OF_BED to Color.Transparent
)

/** Picks up to [max] evenly spread labels from [all]. */
fun spreadLabels(all: List<String>, max: Int = 5): List<String> {
    if (all.size <= max) return all
    return pickXLabelIndices(all.size, max).map { all[it] }
}

@Suppress("unused")
private fun nearlyEqual(a: Double, b: Double) = abs(a - b) < 1e-9
