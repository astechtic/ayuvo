package com.ayuvo.health.ui.charts

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ayuvo.health.R
import com.ayuvo.health.data.health.HealthSleepCodes
import com.ayuvo.health.data.metrics.MetricsReference
import com.ayuvo.health.data.metrics.SleepRangeSeries
import com.ayuvo.health.data.metrics.SleepRow
import com.ayuvo.health.data.metrics.SleepWindow
import com.ayuvo.health.ui.health.HealthValueFormatter
import com.ayuvo.health.ui.theme.AppColors
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import kotlin.math.roundToInt

/** Sleep stage colours (docs/charts.md). */
object SleepColors {
    val Awake = Color(0xFFFF6250)
    val Rem = Color(0xFF3ACBFF)
    val Core = Color(0xFF0A84FF)
    val Deep = Color(0xFF3634A3)
    /** Deep is lifted in dark mode so it stays visible on black (docs/charts.md). */
    val DeepDark = Color(0xFF5856D6)
    val Asleep = Color(0xFF5E5CE6)
    val InBed = Color(0xFF5E5CE6).copy(alpha = 0.25f)

    fun of(stage: Int, deep: Color = Deep): Color = when (stage) {
        HealthSleepCodes.AWAKE -> Awake
        HealthSleepCodes.REM -> Rem
        HealthSleepCodes.LIGHT -> Core
        HealthSleepCodes.DEEP -> deep
        HealthSleepCodes.ASLEEP_UNSPECIFIED -> Asleep
        else -> InBed
    }
}

/** Stage colour lookup for the current theme. */
@Composable
private fun rememberStageColor(): (Int) -> Color {
    val deep = if (isSystemInDarkTheme()) SleepColors.DeepDark else SleepColors.Deep
    return remember(deep) { { stage: Int -> SleepColors.of(stage, deep) } }
}

/** Hypnogram lane of a stage, top to bottom Awake, REM, Core, Deep; asleep without stages sits in Core. */
private fun laneOf(stage: Int): Int? = when (stage) {
    HealthSleepCodes.AWAKE -> 0
    HealthSleepCodes.REM -> 1
    HealthSleepCodes.LIGHT, HealthSleepCodes.ASLEEP_UNSPECIFIED -> 2
    HealthSleepCodes.DEEP -> 3
    else -> null
}

@Composable
private fun stageName(stage: Int): String = stringResource(
    when (stage) {
        HealthSleepCodes.AWAKE -> R.string.sleep_stage_awake
        HealthSleepCodes.REM -> R.string.sleep_stage_rem
        HealthSleepCodes.LIGHT -> R.string.sleep_stage_core
        HealthSleepCodes.DEEP -> R.string.sleep_stage_deep
        HealthSleepCodes.ASLEEP_UNSPECIFIED -> R.string.sleep_stage_asleep
        else -> R.string.sleep_in_bed
    }
)

/**
 * D: one night as a hypnogram over the fitted window of `sleep_night_window` (never midnight to
 * midnight), drawn like Apple Health's Sleep › D chart (docs/charts.md): four equal lanes on a
 * transparent plot, lane names inside on the leading side, stage capsules joined by gradient
 * "waterfall" stems, dashed hour gridlines with the labels leading-aligned underneath.
 * Tap or drag shows the stage under the finger.
 */
@Composable
fun SleepHypnogram(window: SleepWindow, rows: List<SleepRow>, zone: ZoneId, is24: Boolean, modifier: Modifier = Modifier) {
    val locale = Locale.getDefault()
    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current
    val grid = chartGridColor()
    val axis = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.16f)
    val surface = MaterialTheme.colorScheme.surface
    val secondary = chartSecondaryLabel()
    val stageColor = rememberStageColor()
    val reveal = rememberReveal(window.bedtimeMs)
    val span = (window.domainEndMs - window.domainStartMs).coerceAtLeast(1L).toFloat()
    val stages = remember(rows) { rows.filter { laneOf(it.stage) != null && it.endMs > it.startMs }.sortedBy { it.startMs } }
    val inBed = remember(rows) { rows.filter { it.stage == HealthSleepCodes.IN_BED && it.endMs > it.startMs } }
    var picked by remember(rows) { mutableStateOf<SleepRow?>(null) }
    val laneNames = listOf(HealthSleepCodes.AWAKE, HealthSleepCodes.REM, HealthSleepCodes.LIGHT, HealthSleepCodes.DEEP).map { stageName(it) }
    val minCapsulePx = with(density) { 2.dp.toPx() }
    val stemPx = with(density) { 3.dp.toPx() }
    val summary = stringResource(R.string.sleep_night_summary, stringResource(R.string.sleep_time_span, ChartClock.time(window.bedtimeMs, zone, is24, locale), ChartClock.time(window.wakeMs, zone, is24, locale)), HealthValueFormatter.duration(window.asleepS.toDouble()))

    fun rowAt(fraction: Float): SleepRow? {
        val t = window.domainStartMs + (fraction * span).toLong()
        return stages.lastOrNull { it.startMs <= t && t < it.endMs } ?: inBed.firstOrNull { it.startMs <= t && t < it.endMs }
    }

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Column(modifier.semantics { contentDescription = summary }) {
            BoxWithConstraints(Modifier.fillMaxWidth().height(ChartSpec.PlotHeight)) {
                val widthPx = with(density) { maxWidth.toPx() }
                Canvas(
                    Modifier.fillMaxSize().bucketGestures(
                        key = rows,
                        count = 1000,
                        onTap = { i -> picked = rowAt(i / 1000f) },
                        onScrub = { i ->
                            val r = rowAt(i / 1000f)
                            if (r != picked) {
                                picked = r
                                if (r != null) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            }
                        }
                    )
                ) {
                    val w = size.width
                    val h = size.height
                    val laneH = h / 4f
                    val capsuleH = laneH * 0.40f
                    fun xOf(t: Long) = ((t - window.domainStartMs) / span) * w
                    fun laneCentre(stage: Int) = laneH * laneOf(stage)!! + laneH / 2f
                    // Lane separators, left axis and baseline; the hour ticks are dashed.
                    for (i in 1 until 4) drawLine(grid, Offset(0f, laneH * i), Offset(w, laneH * i), strokeWidth = 1f)
                    drawLine(axis, Offset(0f, 0f), Offset(0f, h), strokeWidth = 1f)
                    drawLine(axis, Offset(0f, h), Offset(w, h), strokeWidth = 1f)
                    val dash = PathEffect.dashPathEffect(floatArrayOf(4f, 6f), 0f)
                    window.ticks.forEach { t ->
                        val x = xOf(t)
                        if (x > 1f && x < w - 1f) drawLine(grid, Offset(x, 0f), Offset(x, h), strokeWidth = 1f, pathEffect = dash)
                    }
                    val revealX = w * reveal
                    // Apple's "waterfall": a gradient stem between the lanes at every stage change.
                    for (i in 0 until stages.size - 1) {
                        val a = stages[i]
                        val b = stages[i + 1]
                        val x = xOf(b.startMs)
                        if (b.startMs - a.endMs > 60_000L || x > revealX) continue
                        val ya = laneCentre(a.stage)
                        val yb = laneCentre(b.stage)
                        val top = minOf(ya, yb)
                        val bottom = maxOf(ya, yb)
                        val topColor = (if (ya <= yb) stageColor(a.stage) else stageColor(b.stage)).copy(alpha = 0.55f)
                        val bottomColor = (if (ya <= yb) stageColor(b.stage) else stageColor(a.stage)).copy(alpha = 0.55f)
                        drawRoundRect(
                            brush = Brush.verticalGradient(listOf(topColor, bottomColor), startY = top, endY = bottom),
                            topLeft = Offset(x - stemPx / 2f, top),
                            size = Size(stemPx, (bottom - top).coerceAtLeast(stemPx)),
                            cornerRadius = CornerRadius(stemPx / 2f, stemPx / 2f)
                        )
                    }
                    stages.forEach { r ->
                        val x0 = xOf(r.startMs)
                        if (x0 > revealX) return@forEach
                        val x1 = minOf(xOf(r.endMs), revealX)
                        val top = laneCentre(r.stage) - capsuleH / 2f
                        val alpha = if (picked != null && picked != r) ChartSpec.FADE_ALPHA else 1f
                        drawRoundRect(
                            stageColor(r.stage).copy(alpha = alpha), topLeft = Offset(x0, top),
                            size = Size((x1 - x0).coerceAtLeast(minCapsulePx), capsuleH),
                            cornerRadius = CornerRadius(capsuleH / 2f, capsuleH / 2f)
                        )
                    }
                }
                // Lane names inside the plot, at the top of their lane (never clipped).
                Column(Modifier.fillMaxSize()) {
                    laneNames.forEach { name ->
                        Box(Modifier.weight(1f).fillMaxWidth()) {
                            // 10 sp at the very top of the lane, so it clears the centred capsule band.
                            Text(name, fontSize = 10.sp, color = secondary, maxLines = 1, modifier = Modifier.padding(start = 5.dp, top = 1.dp))
                        }
                    }
                }
                picked?.let { r ->
                    val calloutW = with(density) { 168.dp.toPx() }
                    val cx = ((r.startMs + r.endMs) / 2 - window.domainStartMs) / span * widthPx
                    val left = (cx - calloutW / 2f).coerceIn(0f, (widthPx - calloutW).coerceAtLeast(0f))
                    Column(
                        Modifier
                            .offset { IntOffset(left.roundToInt(), 0) }
                            .width(168.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(surface)
                            .border(0.75.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(10.dp))
                            .padding(horizontal = 8.dp, vertical = 5.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            stringResource(R.string.sleep_stage_callout, stageName(r.stage), HealthValueFormatter.duration((r.endMs - r.startMs) / 1000.0)),
                            fontSize = 12.sp, fontWeight = FontWeight.Bold, color = stageColor(r.stage), maxLines = 1
                        )
                        Text(
                            stringResource(R.string.sleep_time_span, ChartClock.time(r.startMs, zone, is24, locale), ChartClock.time(r.endMs, zone, is24, locale)),
                            fontSize = 11.sp, color = secondary, maxLines = 1
                        )
                    }
                }
            }
            XAxisLabels(
                fractions = window.ticks.map { ((it - window.domainStartMs) / span) },
                labels = window.ticks.map { ChartClock.time(it, zone, is24, locale, short = true) },
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                leadingAligned = true
            )
        }
    }
}

/**
 * Apple's sleep header: TIME IN BED and TIME ASLEEP side by side with small unit words, the date
 * underneath. A night with no asleep data shows TIME IN BED only.
 */
@Composable
fun SleepDayHeader(window: SleepWindow, dateText: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
            SleepDurationStat(stringResource(R.string.sleep_time_in_bed), window.inBedS)
            if (window.asleepS > 0) SleepDurationStat(stringResource(R.string.sleep_time_asleep), window.asleepS)
        }
        if (dateText.isNotBlank()) Text(dateText, fontSize = 14.sp, color = chartSecondaryLabel())
    }
}

@Composable
private fun SleepDurationStat(label: String, seconds: Long) {
    val total = seconds.coerceAtLeast(0L)
    val hours = total / 3600
    val minutes = (total % 3600) / 60
    Column {
        Text(
            label.uppercase(Locale.getDefault()),
            fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = chartSecondaryLabel(), letterSpacing = 0.3.sp
        )
        Row(verticalAlignment = Alignment.Bottom) {
            if (hours > 0) {
                Text("$hours", fontSize = 30.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                Spacer(Modifier.width(3.dp))
                Text(
                    stringResource(R.string.sleep_unit_hour),
                    fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = chartSecondaryLabel(),
                    modifier = Modifier.padding(bottom = 5.dp, end = 6.dp)
                )
            }
            // A whole number of hours reads "7 hr", like Apple, not "7 hr 0 min".
            if (minutes > 0 || hours == 0L) {
                Text("$minutes", fontSize = 30.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                Spacer(Modifier.width(3.dp))
                Text(
                    stringResource(R.string.sleep_unit_minute),
                    fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = chartSecondaryLabel(),
                    modifier = Modifier.padding(bottom = 5.dp)
                )
            }
        }
    }
}

/** Stage list under the D hypnogram: duration and share of time asleep. */
@Composable
fun SleepStageList(window: SleepWindow, modifier: Modifier = Modifier) {
    val entries = listOf(
        HealthSleepCodes.AWAKE to (window.stages.awakeS to null),
        HealthSleepCodes.REM to (window.stages.remS to window.pct.rem),
        HealthSleepCodes.LIGHT to (window.stages.coreS to window.pct.core),
        HealthSleepCodes.DEEP to (window.stages.deepS to window.pct.deep),
        HealthSleepCodes.ASLEEP_UNSPECIFIED to (window.stages.unspecifiedS to window.pct.unspecified)
    ).filter { (code, v) -> v.first > 0 || code != HealthSleepCodes.ASLEEP_UNSPECIFIED }
    val stageColor = rememberStageColor()
    if (window.asleepS == 0L && window.stages.awakeS == 0L) return
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        entries.forEach { (code, v) ->
            val (seconds, pct) = v
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).clip(RoundedCornerShape(3.dp)).background(stageColor(code)))
                Spacer(Modifier.width(8.dp))
                Text(stageName(code), fontSize = 14.sp, modifier = Modifier.weight(1f))
                val duration = HealthValueFormatter.duration(seconds.toDouble())
                Text(
                    if (pct != null) stringResource(R.string.sleep_stage_share, duration, pct) else duration,
                    fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)
                )
            }
        }
    }
}

/**
 * W / M / 6M / Y: one floating bar per bucket on the sleep clock axis (`sleep_range_series`),
 * evening at the top and morning at the bottom. W/M draw each night's stages at their real times;
 * 6M/Y draw the mean bedtime → mean wake. Tap selects or drills through [onBucketTap].
 */
@Composable
fun SleepRangeBars(
    series: SleepRangeSeries,
    nightRows: Map<LocalDate, List<SleepRow>>,
    xLabels: List<Pair<Int, String>>,
    zone: ZoneId,
    is24: Boolean,
    selected: Int?,
    onSelect: (Int?) -> Unit,
    modifier: Modifier = Modifier,
    onBucketTap: ((Int) -> Boolean)? = null
) {
    val domain = series.domain ?: return
    val locale = Locale.getDefault()
    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current
    val grid = chartGridColor()
    val stageColor = rememberStageColor()
    val reveal = rememberReveal(series.buckets.firstOrNull()?.startMs)
    val buckets = series.buckets
    val n = buckets.size.coerceAtLeast(1)
    val cornerPx = with(density) { ChartSpec.BarCorner.toPx() }
    val spanMin = (domain.max - domain.min).coerceAtLeast(1).toFloat()
    val insetPx = with(density) { ChartSpec.PlotTopInset.toPx() }
    val plotPx = with(density) { ChartSpec.PlotHeight.toPx() }
    val summary = stringResource(R.string.sleep_chart_summary, series.headline.nights, series.headline.asleepS?.let { HealthValueFormatter.duration(it) } ?: "—")

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Column(modifier.semantics { contentDescription = summary }) {
            Row(Modifier.fillMaxWidth().height(ChartSpec.PlotHeight)) {
                Canvas(
                    Modifier.weight(1f).fillMaxSize().bucketGestures(
                        key = buckets,
                        count = buckets.size,
                        onTap = { i ->
                            when {
                                buckets[i].count == 0 -> onSelect(null)
                                onBucketTap?.invoke(i) == true -> Unit
                                else -> onSelect(i)
                            }
                        },
                        onScrub = { i ->
                            if (i != selected && buckets[i].count > 0) {
                                onSelect(i)
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            }
                        }
                    )
                ) {
                    val w = size.width
                    val h = size.height
                    val slot = w / n
                    val barW = (slot * ChartSpec.BAR_WIDTH).coerceAtLeast(2f)
                    val inset = insetPx
                    fun yOf(offsetMin: Double) = inset + ((offsetMin - domain.min) / spanMin).toFloat() * (h - 2 * inset)
                    domain.ticks.forEach { t -> drawLine(grid, Offset(0f, yOf(t.toDouble())), Offset(w, yOf(t.toDouble())), strokeWidth = 1f) }
                    buckets.forEachIndexed { i, b ->
                        if (b.count == 0 || b.bedOffsetMin == null || b.wakeOffsetMin == null) return@forEachIndexed
                        val alpha = if (selected != null && selected != i) ChartSpec.FADE_ALPHA else 1f
                        val x = i * slot + (slot - barW) / 2f
                        val top = yOf(b.bedOffsetMin)
                        val bottom = top + (yOf(b.wakeOffsetMin) - top) * reveal
                        val day = MetricsReference.localDateOf(b.startMs, zone)
                        val rows = nightRows[day].orEmpty()
                        val staged = rows.filter { laneOf(it.stage) != null }
                        if (staged.isEmpty()) {
                            drawRoundRect(SleepColors.Asleep.copy(alpha = alpha), topLeft = Offset(x, top), size = Size(barW, (bottom - top).coerceAtLeast(2f)), cornerRadius = CornerRadius(cornerPx, cornerPx))
                        } else {
                            drawRoundRect(SleepColors.InBed.copy(alpha = 0.25f * alpha), topLeft = Offset(x, top), size = Size(barW, (bottom - top).coerceAtLeast(2f)), cornerRadius = CornerRadius(cornerPx, cornerPx))
                            staged.forEach { r ->
                                val y0 = yOf(MetricsReference.sleepClockOffset(r.startMs, day, zone).toDouble())
                                val y1 = minOf(yOf(MetricsReference.sleepClockOffset(r.endMs, day, zone).toDouble()), bottom)
                                if (y0 >= bottom) return@forEach
                                drawRect(stageColor(r.stage).copy(alpha = alpha), topLeft = Offset(x, y0), size = Size(barW, (y1 - y0).coerceAtLeast(1f)))
                            }
                        }
                    }
                    selected?.takeIf { it in buckets.indices }?.let { idx ->
                        val x = idx * slot + slot / 2f
                        drawLine(AppColors.Calorie.copy(alpha = 0.6f), Offset(x, 0f), Offset(x, h), strokeWidth = 1.5f)
                    }
                }
                YAxisLabels(
                    fractions = domain.ticks.map { (insetPx + (it - domain.min) / spanMin * (plotPx - 2 * insetPx)) / plotPx },
                    labels = domain.ticks.map { ChartClock.offset(it.toDouble(), is24, locale, short = true) },
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
