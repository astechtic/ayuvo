package com.ayuvo.health.ui.design

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ayuvo.health.ui.components.ActivityRing

/** Mini chart on the right of a Summary tile: bars for daily totals, dots for readings, a ring for scores and goals. */
sealed interface SummaryTileChart {
    data object None : SummaryTileChart

    /** Daily totals, oldest first (NaN = no data); the newest bar takes the tint. */
    data class Bars(val values: List<Float>) : SummaryTileChart

    /** Readings, oldest first (NaN = no reading); the newest dot takes the tint. */
    data class Dots(val values: List<Float>) : SummaryTileChart

    /** Score or goal progress (0…1) with optional text in the middle. */
    data class Ring(val progress: Float, val text: String? = null) : SummaryTileChart
}

/**
 * Apple Health style Summary card: tinted icon and title with a time and chevron on top, a large value bottom-left
 * and a small chart bottom-right. An empty [value] makes a message tile that only shows [detail].
 */
@Composable
fun SummaryTile(
    title: String,
    icon: ImageVector,
    tint: Color,
    modifier: Modifier = Modifier,
    trailing: String? = null,
    label: String? = null,
    value: String = "",
    unit: String? = null,
    detail: String? = null,
    chart: SummaryTileChart = SummaryTileChart.None,
    onClick: () -> Unit
) {
    SurfaceCard(modifier = modifier, padding = PaddingValues(16.dp), onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(
                title,
                modifier = Modifier.weight(1f),
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = tint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (!trailing.isNullOrBlank()) {
                Spacer(Modifier.width(8.dp))
                Text(trailing, fontSize = 13.sp, color = AyuvoColors.secondaryLabel(), maxLines = 1)
            }
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = AyuvoColors.tertiaryLabel(),
                modifier = Modifier.size(20.dp)
            )
        }
        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                if (!label.isNullOrBlank()) {
                    Text(label, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = AyuvoColors.secondaryLabel())
                }
                if (value.isNotEmpty()) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            value,
                            fontSize = 30.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (!unit.isNullOrBlank()) {
                            Spacer(Modifier.width(4.dp))
                            Text(
                                unit,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = AyuvoColors.secondaryLabel(),
                                maxLines = 1,
                                modifier = Modifier.padding(bottom = 5.dp)
                            )
                        }
                    }
                }
                if (!detail.isNullOrBlank()) {
                    // Without a value the detail is the tile's message, so it reads in the primary style.
                    Text(
                        detail,
                        fontSize = if (value.isEmpty()) 16.sp else 14.sp,
                        fontWeight = if (value.isEmpty()) FontWeight.Medium else FontWeight.Normal,
                        color = if (value.isEmpty()) MaterialTheme.colorScheme.onSurface else AyuvoColors.secondaryLabel(),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            SummaryTileChartView(chart, tint)
        }
    }
}

@Composable
private fun SummaryTileChartView(chart: SummaryTileChart, tint: Color) {
    when (chart) {
        SummaryTileChart.None -> Unit
        is SummaryTileChart.Bars -> {
            Spacer(Modifier.width(12.dp))
            MiniBarChart(chart.values.takeLast(7), tint, Modifier.size(92.dp, 44.dp))
        }
        is SummaryTileChart.Dots -> {
            Spacer(Modifier.width(12.dp))
            MiniDotChart(chart.values.takeLast(7), tint, Modifier.size(92.dp, 44.dp))
        }
        is SummaryTileChart.Ring -> {
            Spacer(Modifier.width(12.dp))
            ActivityRing(
                progress = chart.progress.coerceIn(0f, 1f),
                size = 56.dp,
                strokeWidth = 7.dp,
                gradientColors = listOf(tint),
                showEndDot = false,
                trackColor = tint.copy(alpha = 0.18f)
            ) {
                if (chart.text != null) Text(chart.text, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            }
        }
    }
}

/** Seven-day bars, newest in the tint, the rest grey. Days without data keep a short stub so the week reads. */
@Composable
fun MiniBarChart(values: List<Float>, tint: Color, modifier: Modifier = Modifier) {
    val grey = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.22f)
    Canvas(modifier) {
        val count = values.size.coerceAtLeast(1)
        val gap = 4.dp.toPx()
        val barW = minOf(11.dp.toPx(), (size.width - gap * (count - 1)) / count)
        val top = values.filter { it.isFinite() }.maxOrNull()?.coerceAtLeast(0f) ?: 0f
        val stub = 5.dp.toPx()
        // Right-aligned like the iOS tile.
        val startX = size.width - (barW * count + gap * (count - 1))
        values.forEachIndexed { i, v ->
            val fraction = if (top > 0f && v.isFinite()) (v.coerceAtLeast(0f) / top) else 0f
            val h = maxOf(stub, size.height * fraction)
            drawRoundRect(
                color = if (i == values.lastIndex) tint else grey,
                topLeft = Offset(startX + i * (barW + gap), size.height - h),
                size = Size(barW, h),
                cornerRadius = CornerRadius(2.5.dp.toPx())
            )
        }
    }
}

/** One dot per reading on a faint column, newest in the tint: reads like a range, not a line. */
@Composable
fun MiniDotChart(values: List<Float>, tint: Color, modifier: Modifier = Modifier) {
    val track = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
    val grey = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)
    Canvas(modifier) {
        val finite = values.filter { it.isFinite() }
        if (finite.isEmpty()) return@Canvas
        val dot = 7.dp.toPx()
        val low = finite.min()
        val high = finite.max()
        val span = high - low
        val count = values.size
        val step = if (count > 1) (size.width - dot) / (count - 1) else 0f
        val lastIndex = values.indexOfLast { it.isFinite() }
        values.forEachIndexed { i, v ->
            val cx = (if (count > 1) i * step else (size.width - dot) / 2) + dot / 2
            drawLine(track, Offset(cx, 0f), Offset(cx, size.height), strokeWidth = 3.dp.toPx(), cap = StrokeCap.Round)
            if (!v.isFinite()) return@forEachIndexed
            val fraction = if (span > 0f) (v - low) / span else 0.5f
            val cy = dot / 2 + (size.height - dot) * (1 - fraction)
            drawCircle(if (i == lastIndex) tint else grey, radius = dot / 2, center = Offset(cx, cy))
        }
    }
}
