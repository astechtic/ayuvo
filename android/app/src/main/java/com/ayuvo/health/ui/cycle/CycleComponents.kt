package com.ayuvo.health.ui.cycle

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ayuvo.health.ui.design.AyuvoColors
import com.ayuvo.health.ui.design.AyuvoPalette
import java.time.LocalDate
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** Colours of the cycle marks. Status is never colour alone: shapes and text carry it too (§5). */
object CycleColors {
    val Period = AyuvoPalette.Cycle
    val Fertile = Color(0xFF00A7A0)
    val Ovulation = Color(0xFF007C77)

    @Composable
    fun track(): Color = AyuvoColors.fill()
}

/** The kind of a ring / calendar day, from the engine phase. */
enum class CycleMark { PERIOD, PREDICTED_PERIOD, FERTILE, OVULATION, LATE, NONE }

fun markOf(phase: String, showFertility: Boolean): CycleMark = when (phase) {
    "period" -> CycleMark.PERIOD
    "predicted_period" -> CycleMark.PREDICTED_PERIOD
    "fertile" -> if (showFertility) CycleMark.FERTILE else CycleMark.NONE
    "ovulation" -> if (showFertility) CycleMark.OVULATION else CycleMark.NONE
    "late" -> CycleMark.LATE
    else -> CycleMark.NONE
}

/**
 * The cycle ring: one arc segment per day of the current cycle (period, estimated period, fertile window, estimated
 * ovulation, other), starting at the top, with a marker on today. The centre slot carries the cycle day and phase in
 * text; [description] is the screen-reader summary.
 */
@Composable
fun CycleRing(
    marks: List<CycleMark>,
    todayIndex: Int?,
    description: String,
    modifier: Modifier = Modifier,
    center: @Composable () -> Unit
) {
    val track = CycleColors.track()
    val todayColor = MaterialTheme.colorScheme.onSurface
    Box(modifier.aspectRatio(1f).semantics { contentDescription = description }, contentAlignment = Alignment.Center) {
        Canvas(Modifier.matchParentSize()) {
            val n = marks.size.coerceAtLeast(1)
            val strokeW = size.minDimension * 0.085f
            val radius = (size.minDimension - strokeW) / 2f - 6.dp.toPx()
            val topLeft = Offset(size.width / 2 - radius, size.height / 2 - radius)
            val arcSize = Size(radius * 2, radius * 2)
            val gap = if (n > 40) 0.6f else 1.6f
            val sweep = 360f / n
            for (i in 0 until n) {
                val start = -90f + i * sweep + gap / 2
                val s = sweep - gap
                when (marks.getOrElse(i) { CycleMark.NONE }) {
                    CycleMark.PERIOD -> arc(CycleColors.Period, start, s, topLeft, arcSize, strokeW)
                    CycleMark.PREDICTED_PERIOD -> {
                        arc(CycleColors.Period.copy(alpha = 0.22f), start, s, topLeft, arcSize, strokeW)
                        arc(CycleColors.Period, start, s, topLeft, arcSize, strokeW * 0.28f)
                    }
                    CycleMark.FERTILE -> arc(CycleColors.Fertile.copy(alpha = 0.55f), start, s, topLeft, arcSize, strokeW)
                    CycleMark.OVULATION -> arc(CycleColors.Ovulation, start, s, topLeft, arcSize, strokeW)
                    CycleMark.LATE -> arc(CycleColors.Period.copy(alpha = 0.35f), start, s, topLeft, arcSize, strokeW * 0.5f)
                    CycleMark.NONE -> arc(track, start, s, topLeft, arcSize, strokeW)
                }
            }
            if (todayIndex != null && todayIndex in 0 until n) {
                val angle = Math.toRadians((-90.0 + (todayIndex + 0.5) * sweep))
                val c = Offset(size.width / 2 + (radius * cos(angle)).toFloat(), size.height / 2 + (radius * sin(angle)).toFloat())
                drawCircle(Color.White, radius = strokeW * 0.62f, center = c)
                drawCircle(todayColor, radius = strokeW * 0.62f, center = c, style = Stroke(width = 3.dp.toPx()))
            }
        }
        center()
    }
}

private fun DrawScope.arc(color: Color, start: Float, sweep: Float, topLeft: Offset, size: Size, width: Float) {
    drawArc(color, start, sweep, useCenter = false, topLeft = topLeft, size = size, style = Stroke(width = width, cap = StrokeCap.Butt))
}

/** A selectable chip (flow, symptom, mood, pain location); selected state is announced, not only coloured. */
@Composable
fun CycleChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = CycleColors.Period
) {
    val shape = RoundedCornerShape(50)
    Box(
        modifier
            .heightIn(min = 40.dp)
            .clip(shape)
            .background(if (selected) tint else AyuvoColors.fill())
            .border(if (selected) 0.dp else 1.dp, if (selected) Color.Transparent else AyuvoColors.separator(), shape)
            .semantics {
                role = Role.Checkbox
                this.selected = selected
            }
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            (if (selected) "✓ " else "") + label,
            fontSize = 14.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) Color.White else MaterialTheme.colorScheme.onSurface
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CycleChipGroup(content: @Composable () -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
}

/** A small capsule label (basis badge, "From Health Connect", "Not synced"). */
@Composable
fun CycleBadge(text: String, color: Color = AyuvoColors.secondaryLabel(), modifier: Modifier = Modifier) {
    Text(
        text,
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.14f))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        color = color
    )
}

/** One legend entry: the same shape the calendar draws, then its label. */
@Composable
fun CycleLegendItem(mark: CycleMark, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(18.dp)) { DayMarkBackground(mark, today = false, size = 18.dp) }
        Spacer(Modifier.width(6.dp))
        Text(label, fontSize = 12.sp, color = AyuvoColors.secondaryLabel())
    }
}

/** Calendar mark drawing shared by the grid and the legend. */
@Composable
fun DayMarkBackground(mark: CycleMark, today: Boolean, size: androidx.compose.ui.unit.Dp) {
    val todayColor = MaterialTheme.colorScheme.onSurface
    Canvas(Modifier.size(size)) {
        val r = min(this.size.width, this.size.height) / 2f
        val c = Offset(this.size.width / 2, this.size.height / 2)
        when (mark) {
            CycleMark.PERIOD -> drawCircle(CycleColors.Period, r * 0.92f, c)
            CycleMark.PREDICTED_PERIOD -> drawCircle(
                CycleColors.Period, r * 0.86f, c,
                style = Stroke(width = 2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 3.dp.toPx())))
            )
            CycleMark.FERTILE -> drawRoundRect(
                CycleColors.Fertile.copy(alpha = 0.22f),
                topLeft = Offset(c.x - r * 0.95f, c.y - r * 0.95f), size = Size(r * 1.9f, r * 1.9f),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(r * 0.45f)
            )
            CycleMark.OVULATION -> {
                drawCircle(CycleColors.Ovulation, r * 0.86f, c, style = Stroke(width = 2.5.dp.toPx()))
                drawCircle(CycleColors.Ovulation, r * 0.16f, Offset(c.x, c.y + r * 0.62f))
            }
            CycleMark.LATE -> drawCircle(
                CycleColors.Period.copy(alpha = 0.5f), r * 0.86f, c,
                style = Stroke(width = 1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(), 3.dp.toPx())))
            )
            CycleMark.NONE -> Unit
        }
        if (today) drawCircle(todayColor, r, c, style = Stroke(width = 2.dp.toPx()))
    }
}

/** A row of the flow strip in history: one small square per period day, height ~ flow rank. */
@Composable
fun FlowStrip(flow: List<String?>, ranks: Map<String, Int>, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.Bottom) {
        flow.forEach { key ->
            val rank = key?.let { ranks[it] } ?: 0
            val h = if (rank == 0) 4.dp else (4 + rank * 3).dp
            Box(
                Modifier
                    .width(8.dp)
                    .size(width = 8.dp, height = h)
                    .clip(RoundedCornerShape(2.dp))
                    .background(if (rank == 0) AyuvoColors.fill() else CycleColors.Period.copy(alpha = 0.35f + 0.13f * rank))
            )
        }
    }
}

/** Big centred number + caption used inside the ring. */
@Composable
fun RingCenter(title: String, value: String, caption: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, fontSize = 13.sp, color = AyuvoColors.secondaryLabel(), textAlign = TextAlign.Center)
        Text(value, fontSize = 44.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        Text(caption, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 24.dp))
    }
}

internal fun LocalDate.isBetween(a: LocalDate, b: LocalDate): Boolean = !isBefore(a) && !isAfter(b)
