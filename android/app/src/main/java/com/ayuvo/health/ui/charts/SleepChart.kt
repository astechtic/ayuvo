package com.ayuvo.health.ui.charts

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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

/** Day hypnogram palette (docs/charts.md › Sleep D): soft lane colours, lighter in dark mode. */
object SleepHypnogramColors {
    private val AwakeLight = Color(0xFFF2668F)
    private val AwakeDark = Color(0xFFFF9AB8)
    private val RemLight = Color(0xFF6EC3F2)
    private val RemDark = Color(0xFFBDE6FF)
    private val LightLight = Color(0xFF6F9BF2)
    private val LightDark = Color(0xFF92B8FF)
    private val DeepLight = Color(0xFF7A4FC0)
    private val DeepDark = Color(0xFF8E5FCB)

    fun of(stage: Int, dark: Boolean): Color = when (stage) {
        HealthSleepCodes.AWAKE -> if (dark) AwakeDark else AwakeLight
        HealthSleepCodes.REM -> if (dark) RemDark else RemLight
        HealthSleepCodes.LIGHT, HealthSleepCodes.ASLEEP_UNSPECIFIED -> if (dark) LightDark else LightLight
        HealthSleepCodes.DEEP -> if (dark) DeepDark else DeepLight
        else -> SleepColors.InBed
    }
}

@Composable
private fun rememberHypnogramColor(): (Int) -> Color {
    val dark = isSystemInDarkTheme()
    return remember(dark) { { stage: Int -> SleepHypnogramColors.of(stage, dark) } }
}

private val LaneTitleHeight = 26.dp
private val LaneTrackHeight = 34.dp
private val LaneGap = 8.dp
private val HourRowHeight = 18.dp

/** Horizontal scale of the D hypnogram; a night wider than the screen scrolls sideways. */
private const val HYPNOGRAM_DP_PER_MINUTE = 1.8f

/**
 * D: one night from bedtime to the time out of bed as four stage lanes (Awake, REM, Core, Deep),
 * each a grey pill track under a "<stage> · <total>" title. Stage segments fill their track, the
 * corner facing a stage change is squared off, and a thin gradient stem joins the two lanes.
 * The plot keeps a fixed scale per minute so short stages stay readable; a long night scrolls
 * left and right while the lane titles stay pinned. Tap a segment (or long-press and drag) to read it.
 */
@Composable
fun SleepHypnogram(window: SleepWindow, rows: List<SleepRow>, zone: ZoneId, is24: Boolean, modifier: Modifier = Modifier) {
    val locale = Locale.getDefault()
    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current
    val resources = androidx.compose.ui.platform.LocalResources.current
    val trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f)
    val secondary = chartSecondaryLabel()
    val stageColor = rememberHypnogramColor()
    val reveal = rememberReveal(window.bedtimeMs)
    val stages = remember(rows) { rows.filter { laneOf(it.stage) != null && it.endMs > it.startMs }.sortedBy { it.startMs } }
    val inBed = remember(rows) { rows.filter { it.stage == HealthSleepCodes.IN_BED && it.endMs > it.startMs } }
    val startMs = minOf(window.bedtimeMs, stages.firstOrNull()?.startMs ?: window.bedtimeMs)
    val endMs = maxOf(window.wakeMs, stages.maxOfOrNull { it.endMs } ?: window.wakeMs).coerceAtLeast(startMs + 60_000L)
    val span = (endMs - startMs).toFloat()
    var picked by remember(rows) { mutableStateOf<SleepRow?>(null) }
    val lanes = listOf(
        HealthSleepCodes.AWAKE to window.stages.awakeS,
        HealthSleepCodes.REM to window.stages.remS,
        HealthSleepCodes.LIGHT to window.stages.coreS + window.stages.unspecifiedS,
        HealthSleepCodes.DEEP to window.stages.deepS
    )
    val minSegmentPx = with(density) { 4.dp.toPx() }
    val stemPx = with(density) { 2.dp.toPx() }
    val scroll = rememberScrollState()
    val summary = stringResource(R.string.sleep_night_summary, stringResource(R.string.sleep_time_span, ChartClock.time(window.bedtimeMs, zone, is24, locale), ChartClock.time(window.wakeMs, zone, is24, locale)), HealthValueFormatter.duration(window.asleepS.toDouble()))

    fun rowAt(fraction: Float): SleepRow? {
        val t = startMs + (fraction * span).toLong()
        return stages.lastOrNull { it.startMs <= t && t < it.endMs } ?: inBed.firstOrNull { it.startMs <= t && t < it.endMs }
    }

    /** True when [b] follows [a] without a gap and sits in another lane, i.e. a stem joins them. */
    fun joins(a: SleepRow?, b: SleepRow?): Boolean =
        a != null && b != null && b.startMs - a.endMs <= 60_000L && laneOf(a.stage) != laneOf(b.stage)

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Column(modifier.semantics { contentDescription = summary }) {
            picked?.let { r ->
                Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.sleep_stage_callout, stageName(r.stage), HealthValueFormatter.duration((r.endMs - r.startMs) / 1000.0, resources)),
                        fontSize = 13.sp, fontWeight = FontWeight.Bold, color = stageColor(r.stage), maxLines = 1
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        stringResource(R.string.sleep_time_span, ChartClock.time(r.startMs, zone, is24, locale), ChartClock.time(r.endMs, zone, is24, locale)),
                        fontSize = 12.sp, color = secondary, maxLines = 1
                    )
                }
            }
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val contentWidth = maxOf(maxWidth, (span / 60_000f * HYPNOGRAM_DP_PER_MINUTE).dp)
                val plotHeight = (LaneTitleHeight + LaneTrackHeight) * 4 + LaneGap * 3
                val contentPx = with(density) { contentWidth.toPx() }
                Column(Modifier.fillMaxWidth().horizontalScroll(scroll)) {
                    Canvas(
                        Modifier
                            .width(contentWidth)
                            .height(plotHeight)
                            .pointerInput(rows) {
                                detectTapGestures { o ->
                                    val r = rowAt(o.x / size.width)
                                    picked = if (r == picked) null else r
                                }
                            }
                            .pointerInput(rows) {
                                fun scrub(x: Float) {
                                    val r = rowAt((x / size.width).coerceIn(0f, 1f))
                                    if (r != picked) {
                                        picked = r
                                        if (r != null) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    }
                                }
                                detectDragGesturesAfterLongPress(onDragStart = { scrub(it.x) }) { change, _ -> scrub(change.position.x) }
                            }
                    ) {
                        val w = size.width
                        val titlePx = LaneTitleHeight.toPx()
                        val trackPx = LaneTrackHeight.toPx()
                        val blockPx = titlePx + trackPx + LaneGap.toPx()
                        val radius = trackPx / 2f
                        fun xOf(t: Long) = ((t - startMs) / span) * w
                        fun trackTop(lane: Int) = blockPx * lane + titlePx
                        for (lane in 0 until 4) {
                            drawRoundRect(trackColor, topLeft = Offset(0f, trackTop(lane)), size = Size(w, trackPx), cornerRadius = CornerRadius(radius, radius))
                        }
                        val revealX = w * reveal
                        // Stems: centre of one track to the centre of the other, under the segments.
                        for (i in 0 until stages.size - 1) {
                            val a = stages[i]
                            val b = stages[i + 1]
                            if (!joins(a, b)) continue
                            val x = xOf(b.startMs)
                            if (x > revealX) continue
                            val ya = trackTop(laneOf(a.stage)!!) + radius
                            val yb = trackTop(laneOf(b.stage)!!) + radius
                            val top = minOf(ya, yb)
                            val bottom = maxOf(ya, yb)
                            val upper = (if (ya <= yb) stageColor(a.stage) else stageColor(b.stage)).copy(alpha = 0.5f)
                            val lower = (if (ya <= yb) stageColor(b.stage) else stageColor(a.stage)).copy(alpha = 0.5f)
                            drawRect(
                                brush = Brush.verticalGradient(listOf(upper, lower), startY = top, endY = bottom),
                                topLeft = Offset(x - stemPx / 2f, top),
                                size = Size(stemPx, bottom - top)
                            )
                        }
                        stages.forEachIndexed { i, r ->
                            val x0 = xOf(r.startMs)
                            if (x0 > revealX) return@forEachIndexed
                            val x1 = minOf(maxOf(xOf(r.endMs), x0 + minSegmentPx), maxOf(revealX, x0 + minSegmentPx))
                            val lane = laneOf(r.stage)!!
                            val top = trackTop(lane)
                            val corner = minOf(radius, (x1 - x0) / 2f).let { CornerRadius(it, it) }
                            val square = CornerRadius.Zero
                            val prev = stages.getOrNull(i - 1)?.takeIf { joins(it, r) }?.let { laneOf(it.stage)!! }
                            val next = stages.getOrNull(i + 1)?.takeIf { joins(r, it) }?.let { laneOf(it.stage)!! }
                            val path = Path().apply {
                                addRoundRect(
                                    RoundRect(
                                        left = x0, top = top, right = x1, bottom = top + trackPx,
                                        topLeftCornerRadius = if (prev != null && prev < lane) square else corner,
                                        topRightCornerRadius = if (next != null && next < lane) square else corner,
                                        bottomRightCornerRadius = if (next != null && next > lane) square else corner,
                                        bottomLeftCornerRadius = if (prev != null && prev > lane) square else corner
                                    )
                                )
                            }
                            val alpha = if (picked != null && picked != r) ChartSpec.FADE_ALPHA else 1f
                            drawPath(path, stageColor(r.stage).copy(alpha = alpha))
                        }
                        picked?.let { r ->
                            val x = xOf((r.startMs + r.endMs) / 2)
                            drawLine(secondary.copy(alpha = 0.6f), Offset(x, 0f), Offset(x, size.height), strokeWidth = 1f)
                        }
                    }
                    // Hour marks under the lanes, scrolling with the plot.
                    Box(Modifier.width(contentWidth).height(HourRowHeight)) {
                        val labelRoom = with(density) { 40.dp.toPx() }
                        window.ticks.filter { it in startMs until endMs }.forEach { t ->
                            val x = (t - startMs) / span * contentPx
                            if (x < contentPx - labelRoom) {
                                Text(
                                    ChartClock.time(t, zone, is24, locale, short = true), fontSize = 10.sp, color = secondary, maxLines = 1,
                                    modifier = Modifier.offset { IntOffset(x.roundToInt(), 0) }.padding(top = 2.dp)
                                )
                            }
                        }
                    }
                }
                // Lane titles stay pinned while the night scrolls underneath.
                Column(Modifier.fillMaxWidth()) {
                    lanes.forEachIndexed { index, (code, seconds) ->
                        Box(Modifier.height(LaneTitleHeight), contentAlignment = Alignment.CenterStart) {
                            Text(
                                stringResource(R.string.sleep_stage_callout, stageName(code), HealthValueFormatter.duration(seconds.toDouble(), resources)),
                                fontSize = 15.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f), maxLines = 1
                            )
                        }
                        Spacer(Modifier.height(LaneTrackHeight + if (index < lanes.size - 1) LaneGap else 0.dp))
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Text(ChartClock.time(startMs, zone, is24, locale), fontSize = 14.sp, color = secondary, maxLines = 1)
                Spacer(Modifier.weight(1f))
                Text(ChartClock.time((startMs + endMs) / 2, zone, is24, locale), fontSize = 14.sp, color = secondary, maxLines = 1)
                Spacer(Modifier.weight(1f))
                Text(ChartClock.time(endMs, zone, is24, locale), fontSize = 14.sp, color = secondary, maxLines = 1)
            }
            Row(Modifier.fillMaxWidth().padding(top = 2.dp)) {
                Text(stringResource(R.string.sleep_went_to_bed), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = secondary, maxLines = 2, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(12.dp))
                Text(stringResource(R.string.sleep_out_of_bed), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = secondary, maxLines = 2, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
            }
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
    val stageColor = rememberHypnogramColor()
    if (window.asleepS == 0L && window.stages.awakeS == 0L) return
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        entries.forEach { (code, v) ->
            val (seconds, pct) = v
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).clip(RoundedCornerShape(3.dp)).background(stageColor(code)))
                Spacer(Modifier.width(8.dp))
                Text(stageName(code), fontSize = 14.sp, modifier = Modifier.weight(1f))
                val duration = HealthValueFormatter.duration(seconds.toDouble(), androidx.compose.ui.platform.LocalResources.current)
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
    val summary = stringResource(R.string.sleep_chart_summary, series.headline.nights, series.headline.asleepS?.let { HealthValueFormatter.duration(it, androidx.compose.ui.platform.LocalResources.current) } ?: "—")

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
