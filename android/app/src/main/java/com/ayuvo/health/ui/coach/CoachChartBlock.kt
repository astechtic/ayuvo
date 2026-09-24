package com.ayuvo.health.ui.coach

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ayuvo.health.coach.logic.CoachReference
import com.ayuvo.health.R
import com.ayuvo.health.medications.logic.MedicationJson
import com.ayuvo.health.medications.logic.MedicationJson.arr
import com.ayuvo.health.medications.logic.MedicationJson.double
import com.ayuvo.health.medications.logic.MedicationJson.int
import com.ayuvo.health.medications.logic.MedicationJson.str
import com.ayuvo.health.ui.charts.ChartSpec
import com.ayuvo.health.ui.theme.AppColors
import kotlinx.serialization.json.JsonObject
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** One point of a chart Coach drew. */
data class CoachChartPoint(val x: String, val y: Double, val y2: Double?)

/** One series of a chart Coach drew. */
data class CoachChartSeries(val label: String, val points: List<CoachChartPoint>)

/**
 * A chart Coach drew (docs/coach.md §5). The spec has already been parsed and validated by
 * `CoachReference.parseChartSpec`, so this only lays out numbers it was handed — it never computes,
 * estimates or fills one in.
 *
 * Visual rules come from `docs/charts.md` via [ChartSpec], so a chart in chat is indistinguishable
 * from a chart in the rest of the app.
 */
@Composable
fun CoachChartBlock(spec: JsonObject, modifier: Modifier = Modifier) {
    val type = spec.str("type") ?: "bar"
    val series = remember(spec) { seriesOf(spec) }
    val unit = spec.str("unit")
    val values = remember(series) {
        series.flatMap { s -> s.points.flatMap { listOfNotNull(it.y, it.y2) } }
    }
    val description = remember(spec) { CoachReference.chartAccessibilityText(spec) }

    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(0.7.dp, AppColors.Calorie.copy(alpha = 0.12f), RoundedCornerShape(14.dp))
            .padding(12.dp)
            .semantics { contentDescription = description }
    ) {
        spec.str("title")?.let {
            Text(it, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
        }
        when (type) {
            "pie" -> PieChart(series.firstOrNull()?.points.orEmpty())
            "progress" -> ProgressChart(series.firstOrNull()?.points.orEmpty(), spec.double("max"), unit)
            else -> CartesianChart(type, series, values)
        }
        if (series.size > 1) {
            Spacer(Modifier.height(8.dp))
            Legend(series)
        }
        spec.str("note")?.let {
            Spacer(Modifier.height(6.dp))
            Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
        }
        // A reading that could not be read is left out of the plot, so the chart says so rather than
        // quietly showing fewer bars than the text talks about (docs/coach.md §5).
        val dropped = spec.int("dropped") ?: 0
        if (dropped > 0) {
            Spacer(Modifier.height(6.dp))
            Text(
                pluralStringResource(R.plurals.coach_chart_dropped_readings, dropped, dropped),
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                modifier = Modifier.testTag("coach.chart.dropped")
            )
        }
    }
}

@Composable
private fun CartesianChart(type: String, series: List<CoachChartSeries>, values: List<Double>) {
    val includeZero = type.endsWith("bar") || type == "area"
    val low = min(values.minOrNull() ?: 0.0, if (includeZero) 0.0 else values.minOrNull() ?: 0.0)
    val high = max(values.maxOrNull() ?: 1.0, if (includeZero) 0.0 else values.maxOrNull() ?: 1.0)
    val span = if (high - low <= 0.0) 1.0 else high - low
    val labels = series.firstOrNull()?.points?.map { it.x }.orEmpty()
    val colors = palette()
    val grid = MaterialTheme.colorScheme.onSurface.copy(alpha = ChartSpec.GRID_ALPHA)

    Row(Modifier.fillMaxWidth().height(ChartSpec.PlotHeight)) {
        Column(
            Modifier.width(ChartSpec.YLabelWidth).fillMaxHeight(),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            for (fraction in listOf(1.0, 0.5, 0.0)) {
                Text(
                    CoachReference.numText(MedicationJson.num(low + span * fraction)),
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                )
            }
        }
        Canvas(Modifier.weight(1f).fillMaxHeight()) {
            for (fraction in listOf(0f, 0.5f, 1f)) {
                val y = size.height * fraction
                drawLine(grid, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
            }
            fun yOf(value: Double): Float =
                (size.height * (1.0 - (value - low) / span)).toFloat().coerceIn(0f, size.height)

            when (type) {
                "line", "area", "scatter" -> series.forEachIndexed { index, entry ->
                    drawSeriesPath(entry, colors[index % colors.size], type, ::yOf)
                }
                "range" -> series.forEachIndexed { index, entry ->
                    drawRangeBars(entry, colors[index % colors.size], ::yOf)
                }
                "stacked_bar" -> drawStackedBars(series, colors, low, span)
                "grouped_bar" -> drawGroupedBars(series, colors, ::yOf, yOf(max(0.0, low)))
                else -> series.firstOrNull()?.let {
                    drawBars(it, colors[0], ::yOf, yOf(max(0.0, low)))
                }
            }
        }
    }
    if (labels.isNotEmpty()) {
        Spacer(Modifier.height(4.dp))
        Row(
            Modifier.fillMaxWidth().padding(start = ChartSpec.YLabelWidth),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // At most six labels so a 60-point series does not become a smear.
            for (label in labels.thinnedTo(6)) {
                Text(
                    label,
                    fontSize = 11.sp,
                    maxLines = 1,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                )
            }
        }
    }
}

private fun DrawScope.drawBars(
    entry: CoachChartSeries,
    color: Color,
    yOf: (Double) -> Float,
    baseline: Float
) {
    val slot = size.width / entry.points.size.coerceAtLeast(1)
    val width = slot * ChartSpec.BAR_WIDTH
    entry.points.forEachIndexed { index, point ->
        val centre = slot * index + slot / 2f
        val top = yOf(point.y)
        drawRoundRect(
            color = color,
            topLeft = Offset(centre - width / 2f, min(top, baseline)),
            size = Size(width, abs(baseline - top).coerceAtLeast(1f)),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(ChartSpec.BarCorner.toPx(), ChartSpec.BarCorner.toPx())
        )
    }
}

private fun DrawScope.drawGroupedBars(
    series: List<CoachChartSeries>,
    colors: List<Color>,
    yOf: (Double) -> Float,
    baseline: Float
) {
    val count = series.firstOrNull()?.points?.size ?: return
    val slot = size.width / count.coerceAtLeast(1)
    val barWidth = (slot * ChartSpec.BAR_WIDTH) / series.size.coerceAtLeast(1)
    series.forEachIndexed { seriesIndex, entry ->
        entry.points.forEachIndexed { index, point ->
            val groupStart = slot * index + slot * (1f - ChartSpec.BAR_WIDTH) / 2f
            val left = groupStart + barWidth * seriesIndex
            val top = yOf(point.y)
            drawRoundRect(
                color = colors[seriesIndex % colors.size],
                topLeft = Offset(left, min(top, baseline)),
                size = Size(barWidth * 0.9f, abs(baseline - top).coerceAtLeast(1f)),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(ChartSpec.BarCorner.toPx(), ChartSpec.BarCorner.toPx())
            )
        }
    }
}

private fun DrawScope.drawStackedBars(
    series: List<CoachChartSeries>,
    colors: List<Color>,
    low: Double,
    span: Double
) {
    val count = series.firstOrNull()?.points?.size ?: return
    val slot = size.width / count.coerceAtLeast(1)
    val width = slot * ChartSpec.BAR_WIDTH
    for (index in 0 until count) {
        var runningTop = size.height * (1.0 - (0.0 - low) / span).toFloat()
        series.forEachIndexed { seriesIndex, entry ->
            val point = entry.points.getOrNull(index) ?: return@forEachIndexed
            val height = (size.height * (point.y / span)).toFloat()
            val top = runningTop - height
            drawRect(
                color = colors[seriesIndex % colors.size],
                topLeft = Offset(slot * index + (slot - width) / 2f, top),
                size = Size(width, height.coerceAtLeast(1f))
            )
            runningTop = top
        }
    }
}

private fun DrawScope.drawRangeBars(entry: CoachChartSeries, color: Color, yOf: (Double) -> Float) {
    val slot = size.width / entry.points.size.coerceAtLeast(1)
    val width = slot * ChartSpec.BAR_WIDTH
    for ((index, point) in entry.points.withIndex()) {
        val a = yOf(point.y)
        val b = yOf(point.y2 ?: point.y)
        drawRoundRect(
            color = color,
            topLeft = Offset(slot * index + (slot - width) / 2f, min(a, b)),
            size = Size(width, abs(a - b).coerceAtLeast(2f)),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(width / 2f, width / 2f)
        )
    }
}

private fun DrawScope.drawSeriesPath(
    entry: CoachChartSeries,
    color: Color,
    type: String,
    yOf: (Double) -> Float
) {
    if (entry.points.isEmpty()) return
    val step = if (entry.points.size == 1) 0f else size.width / (entry.points.size - 1)
    val offsets = entry.points.mapIndexed { index, point -> Offset(step * index, yOf(point.y)) }
    if (type != "scatter") {
        val path = Path().apply {
            moveTo(offsets.first().x, offsets.first().y)
            offsets.drop(1).forEach { lineTo(it.x, it.y) }
        }
        if (type == "area") {
            val filled = Path().apply {
                addPath(path)
                lineTo(offsets.last().x, size.height)
                lineTo(offsets.first().x, size.height)
                close()
            }
            drawPath(filled, color.copy(alpha = 0.25f))
        }
        drawPath(path, color, style = Stroke(width = 2.dp.toPx()))
    }
    if (type != "line" || entry.points.size <= ChartSpec.MAX_POINTS) {
        offsets.forEach { drawCircle(color, radius = if (type == "scatter") 4.dp.toPx() else 3.dp.toPx(), center = it) }
    }
}

@Composable
private fun PieChart(slices: List<CoachChartPoint>) {
    val colors = palette()
    val total = slices.sumOf { abs(it.y) }.takeIf { it > 0 } ?: 1.0
    Row(verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.size(ChartSpec.PlotHeight * 0.8f)) {
            var start = -90f
            slices.forEachIndexed { index, slice ->
                val sweep = (abs(slice.y) / total * 360.0).toFloat()
                drawArc(
                    color = colors[index % colors.size],
                    startAngle = start,
                    sweepAngle = sweep - 1.5f,
                    useCenter = false,
                    style = Stroke(width = size.minDimension * 0.22f)
                )
                start += sweep
            }
        }
        Spacer(Modifier.width(16.dp))
        Column {
            slices.forEachIndexed { index, slice ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).background(colors[index % colors.size], CircleShape))
                    Spacer(Modifier.width(6.dp))
                    Text(slice.x, fontSize = 12.sp, maxLines = 1, modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        CoachReference.numText(MedicationJson.num(slice.y)),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
            }
        }
    }
}

@Composable
private fun ProgressChart(points: List<CoachChartPoint>, ceiling: Double?, unit: String?) {
    val top = ceiling ?: points.maxOfOrNull { it.y } ?: 1.0
    Column {
        for (point in points) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(point.x, fontSize = 12.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                Text(
                    CoachReference.numText(MedicationJson.num(point.y)) + (unit?.let { " $it" } ?: ""),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }
            Spacer(Modifier.height(4.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(10.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.15f))
            ) {
                val fraction = if (top > 0) (point.y / top).coerceIn(0.0, 1.0).toFloat() else 0f
                Box(
                    Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(fraction)
                        .clip(CircleShape)
                        .background(AppColors.Calorie)
                )
            }
            Spacer(Modifier.height(10.dp))
        }
    }
}

@Composable
private fun Legend(series: List<CoachChartSeries>) {
    val colors = palette()
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
        series.forEachIndexed { index, entry ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = 12.dp)) {
                Box(Modifier.size(8.dp).background(colors[index % colors.size], CircleShape))
                Spacer(Modifier.width(5.dp))
                Text(
                    entry.label,
                    fontSize = 11.sp,
                    maxLines = 1,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }
        }
    }
}

/**
 * Domain colours, in the order `docs/charts.md` lists them, so a chat chart and a Browse chart use
 * the same palette. The spec caps a chart at four series, so four is all that is ever needed.
 */
@Composable
private fun palette(): List<Color> =
    listOf(AppColors.Calorie, AppColors.Protein, AppColors.Carbs, AppColors.Fat)

internal fun seriesOf(spec: JsonObject): List<CoachChartSeries> =
    spec.arr("series").orEmpty().filterIsInstance<JsonObject>().map { entry ->
        CoachChartSeries(
            label = entry.str("label").orEmpty(),
            points = entry.arr("points").orEmpty().filterIsInstance<JsonObject>().map { point ->
                CoachChartPoint(
                    x = point.str("x").orEmpty(),
                    y = point.double("y") ?: 0.0,
                    y2 = point.double("y2")
                )
            }
        )
    }

/** Evenly spaced sample of at most [max] labels, always keeping the first and the last. */
internal fun List<String>.thinnedTo(max: Int): List<String> {
    if (size <= max) return this
    val step = (size - 1).toDouble() / (max - 1)
    return (0 until max).map { this[(it * step).toInt().coerceIn(0, size - 1)] }
}
