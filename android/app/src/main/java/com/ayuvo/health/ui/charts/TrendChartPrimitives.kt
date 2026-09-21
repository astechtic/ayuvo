package com.ayuvo.health.ui.charts

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ayuvo.health.ui.components.GlassSurface
import com.ayuvo.health.ui.theme.AppColors
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale

/*
 * Trend-chart building blocks shared by Progress and the Health Data hub. Promoted verbatim
 * from ProgressScreen.kt (segmented control, card section, stat badges, x-axis labels,
 * downsampling, Catmull-Rom smoothing, axis ticks) so both screens draw the same way.
 */

/** Plain iOS-style segmented control used for time range and progress metrics. */
@Composable
internal fun <T> IosStyleSegmentedControl(
    options: List<T>,
    selected: T,
    label: @Composable (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier
) {
    // Apple-flat segmented control: translucent grey track, raised selected segment.
    val shape = RoundedCornerShape(9.dp)
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val trackFill = Color(0x1F767680)
    val selectedFill = if (isDark) Color(0xFF636366) else Color.White
    Row(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(trackFill)
            .padding(2.dp)
            .selectableGroup()
    ) {
        options.forEach { option ->
            val isSel = option == selected
            Box(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(7.dp))
                    .then(
                        if (isSel) {
                            Modifier.background(selectedFill)
                        } else {
                            Modifier.background(Color.Transparent)
                        }
                    )
                    .selectable(
                        selected = isSel,
                        role = Role.Tab,
                        onClick = { onSelect(option) }
                    )
                    .padding(vertical = 6.dp, horizontal = 4.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    label(option),
                    fontSize = 13.sp,
                    fontWeight = if (isSel) FontWeight.SemiBold else FontWeight.Medium,
                    maxLines = 1,
                    color = if (isSel) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f)
                    }
                )
            }
        }
    }
}

@Composable
internal fun CardSection(content: @Composable () -> Unit) {
    GlassSurface(
        modifier = Modifier.fillMaxWidth(),
        cornerRadius = 16.dp,
        padding = 16.dp
    ) { content() }
}

@Composable
internal fun StatBadgeRow(items: List<Pair<String, String>>) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items.forEach { (label, value) ->
            StatBadge(
                label = label,
                value = value,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
internal fun StatBadge(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f))
            .padding(horizontal = 4.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(
            value,
            modifier = Modifier.fillMaxWidth(),
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            autoSize = TextAutoSize.StepBased(minFontSize = 10.sp, maxFontSize = 15.sp, stepSize = 0.5.sp)
        )
        Text(
            label,
            modifier = Modifier.fillMaxWidth(),
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            autoSize = TextAutoSize.StepBased(minFontSize = 7.sp, maxFontSize = 11.sp, stepSize = 0.5.sp)
        )
    }
}

/** X-axis labels under a trend chart, matching the label density of the iOS
 *  charts: five dates aligned with the canvas' quarter gridlines, or
 *  first/middle/last with the year on multi-year spans (wider "MMM yyyy"
 *  labels need the extra room). */
@Composable
internal fun TrendXAxisLabels(
    tStart: Long,
    tEnd: Long,
    showsYear: Boolean,
    singleEntry: Boolean,
    fmt: DateTimeFormatter,
    color: Color,
    endPadding: Dp
) {
    val labels = when {
        singleEntry -> listOf(fmt.format(Instant.ofEpochMilli(tStart)))
        showsYear -> listOf(tStart, (tStart + tEnd) / 2, tEnd)
            .map { fmt.format(Instant.ofEpochMilli(it)) }
        else -> (0..4)
            .map { i -> fmt.format(Instant.ofEpochMilli(tStart + (tEnd - tStart) * i / 4)) }
            // Spans of a couple days format to repeating dates — drop the dupes.
            .let { all -> all.filterIndexed { i, label -> i == 0 || label != all[i - 1] } }
    }
    Row(
        Modifier.fillMaxWidth().padding(top = 4.dp, end = endPadding),
        horizontalArrangement = if (labels.size == 1) Arrangement.Center else Arrangement.SpaceBetween
    ) {
        labels.forEach { Text(it, fontSize = 11.sp, color = color) }
    }
}

/** One plotted point on a trend chart — either a raw entry or the average of
 *  a date bucket when the range is too dense to draw every reading. Mirrors
 *  the iOS TrendPoint/downsampled helpers in ProgressComponents.swift. */
internal data class TrendPoint(val timeMs: Long, val value: Double)

/** Averages a date-sorted series into equal date buckets once it outgrows
 *  [maxPoints]. Hundreds of raw readings drew every dot on top of its
 *  neighbours and turned the line into a solid band — ~60 bucket averages
 *  keep the trend shape readable. Sparse series pass through untouched. */
internal fun downsampleTrend(points: List<TrendPoint>, maxPoints: Int = 60): List<TrendPoint> {
    if (points.size < 2) return points
    val dayMs = 86_400_000L
    val first = points.first().timeMs
    val spanDays = maxOf(1L, (points.last().timeMs - first) / dayMs)
    // Long ranges need fewer interaction targets than short ranges. This keeps
    // the visual trend intact while avoiding dozens of redraws + haptics during
    // a single scrub across a two-year series.
    val adaptiveLimit = minOf(maxPoints, when {
        spanDays <= 45 -> 60
        spanDays <= 100 -> 48
        spanDays <= 200 -> 36
        spanDays <= 400 -> 30
        else -> 24
    })
    if (points.size <= adaptiveLimit) return points
    val bucketMs = Math.ceil(spanDays.toDouble() / adaptiveLimit).toLong().coerceAtLeast(1L) * dayMs
    return points
        .groupBy { (it.timeMs - first) / bucketMs }
        .toSortedMap()
        .values
        .map { bucket ->
            TrendPoint(
                timeMs = bucket.map { it.timeMs }.average().toLong(),
                value = bucket.map { it.value }.average()
            )
        }
}

/** Catmull-Rom smoothed path through [points] — same curve the iOS charts
 *  get from interpolationMethod(.catmullRom). */
internal fun smoothTrendPath(points: List<Offset>): Path {
    val path = Path()
    if (points.isEmpty()) return path
    path.moveTo(points.first().x, points.first().y)
    for (i in 1 until points.size) {
        val p0 = points[maxOf(i - 2, 0)]
        val p1 = points[i - 1]
        val p2 = points[i]
        val p3 = points[minOf(i + 1, points.size - 1)]
        path.cubicTo(
            p1.x + (p2.x - p0.x) / 6f, p1.y + (p2.y - p0.y) / 6f,
            p2.x - (p3.x - p1.x) / 6f, p2.y - (p3.y - p1.y) / 6f,
            p2.x, p2.y
        )
    }
    return path
}

/** The same smoothed trend closed against the plot floor, used for the quiet
 *  accent wash beneath weight and body-fat lines. */
internal fun smoothTrendAreaPath(points: List<Offset>, bottomY: Float): Path {
    val path = Path()
    if (points.isEmpty()) return path
    path.moveTo(points.first().x, bottomY)
    path.lineTo(points.first().x, points.first().y)
    for (i in 1 until points.size) {
        val p0 = points[maxOf(i - 2, 0)]
        val p1 = points[i - 1]
        val p2 = points[i]
        val p3 = points[minOf(i + 1, points.size - 1)]
        path.cubicTo(
            p1.x + (p2.x - p0.x) / 6f, p1.y + (p2.y - p0.y) / 6f,
            p2.x - (p3.x - p1.x) / 6f, p2.y - (p3.y - p1.y) / 6f,
            p2.x, p2.y
        )
    }
    path.lineTo(points.last().x, bottomY)
    path.close()
    return path
}

/** Compute "nice" axis tick values across [min, max] with approx [count] divisions. */
internal fun niceAxisTicks(min: Double, max: Double, count: Int): List<Double> {
    val range = max - min
    if (range <= 0) return listOf(min)
    val rawStep = range / (count - 1)
    val mag = Math.pow(10.0, Math.floor(Math.log10(rawStep)))
    val normalized = rawStep / mag
    val niceStep = when {
        normalized < 1.5 -> 1.0
        normalized < 3.0 -> 2.0
        normalized < 7.0 -> 5.0
        else -> 10.0
    } * mag
    val firstTick = Math.ceil(min / niceStep) * niceStep
    val out = mutableListOf<Double>()
    var v = firstTick
    while (v <= max + 1e-9) {
        out.add(v)
        v += niceStep
    }
    return out
}

internal fun formatTick(value: Double): String =
    if (value >= 1000) String.format(Locale.US, "%,d", value.toInt())
    else if (value == value.toInt().toDouble()) value.toInt().toString()
    else String.format(Locale.US, "%.1f", value)

/** Pick at most [maxLabels] evenly-spaced bar indices for x-axis labelling. */
internal fun pickXLabelIndices(n: Int, maxLabels: Int = 7): List<Int> {
    if (n <= 0) return emptyList()
    if (n <= maxLabels) return (0 until n).toList()
    val step = (n - 1).toFloat() / (maxLabels - 1)
    return (0 until maxLabels).map { i -> (i * step).toInt().coerceIn(0, n - 1) }.distinct()
}
