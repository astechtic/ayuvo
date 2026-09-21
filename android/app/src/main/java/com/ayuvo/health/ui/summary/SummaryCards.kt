package com.ayuvo.health.ui.summary

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.Medication
import androidx.compose.material.icons.filled.MonitorWeight
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ayuvo.health.R
import com.ayuvo.health.medications.model.DoseStatus
import com.ayuvo.health.medications.model.TodayTimeline
import com.ayuvo.health.models.FastingSession
import com.ayuvo.health.models.WaterUnit
import com.ayuvo.health.models.WorkoutSession
import com.ayuvo.health.models.formatFastingDuration
import com.ayuvo.health.ui.design.AyuvoColors
import com.ayuvo.health.ui.design.AyuvoPalette
import com.ayuvo.health.ui.design.CategoryIcon
import com.ayuvo.health.ui.design.RingId
import com.ayuvo.health.ui.design.RingSpec
import com.ayuvo.health.ui.design.RingTrio
import com.ayuvo.health.ui.design.SurfaceCard
import com.ayuvo.health.ui.medications.MedicationFormat
import com.ayuvo.health.ui.records.RecordFormat
import com.ayuvo.health.ui.theme.AppColors
import kotlinx.coroutines.delay
import java.text.NumberFormat
import java.time.Instant
import java.util.Locale
import kotlin.math.abs
import com.ayuvo.health.ui.design.RingState as DesignRingState

private fun SummaryRingId.designId(): RingId = when (this) {
    SummaryRingId.EAT -> RingId.EAT
    SummaryRingId.MOVE -> RingId.MOVE
    SummaryRingId.DRINK -> RingId.DRINK
}

private fun SummaryRingId.color(): Color = when (this) {
    SummaryRingId.EAT -> AyuvoPalette.Nutrition
    SummaryRingId.MOVE -> AyuvoPalette.Activity
    SummaryRingId.DRINK -> AyuvoPalette.Hydration
}

private fun SummaryRingId.tag(): String = "summary.ring." + name.lowercase()

private fun whole(v: Double): String = NumberFormat.getIntegerInstance(Locale.getDefault()).format(Math.round(v))

/** Rings card: Eat · Move · Drink with a legend line per ring (docs/ui-structure.md §8.2). */
@Composable
internal fun SummaryRingsCard(
    rings: List<SummaryRing>,
    waterUnit: WaterUnit,
    animationKey: Any,
    onRing: (SummaryRingId, SummaryRingState) -> Unit
) {
    val labels = mapOf(
        SummaryRingId.EAT to stringResource(R.string.summary_ring_eat),
        SummaryRingId.MOVE to stringResource(R.string.summary_ring_move),
        SummaryRingId.DRINK to stringResource(R.string.summary_ring_drink)
    )
    val specs = rings.map { ring ->
        RingSpec(
            id = ring.id.designId(),
            progress = ring.progress,
            color = ring.id.color(),
            state = if (ring.state == SummaryRingState.CONNECT) DesignRingState.CONNECT else DesignRingState.NORMAL,
            contentDescription = labels.getValue(ring.id)
        )
    }
    SurfaceCard(padding = PaddingValues(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RingTrio(
                rings = specs,
                size = 132.dp,
                stroke = 14.dp,
                animationKey = animationKey,
                onRingClick = { id -> rings.firstOrNull { it.id.designId() == id }?.let { onRing(it.id, it.state) } }
            )
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                rings.forEach { ring -> RingLegend(ring, labels.getValue(ring.id), waterUnit) { onRing(ring.id, ring.state) } }
            }
        }
    }
}

@Composable
private fun RingLegend(ring: SummaryRing, label: String, waterUnit: WaterUnit, onClick: () -> Unit) {
    val color = ring.id.color()
    val unit = when (ring.id) {
        SummaryRingId.EAT -> stringResource(R.string.summary_unit_kcal)
        SummaryRingId.MOVE -> stringResource(R.string.summary_unit_steps)
        SummaryRingId.DRINK -> waterUnit.symbol
    }
    fun fmt(v: Double): String = if (ring.id == SummaryRingId.DRINK) whole(waterUnit.displayAmount(v.toInt())) else whole(v)
    val text = when (ring.state) {
        SummaryRingState.CONNECT -> stringResource(R.string.summary_ring_connect)
        SummaryRingState.NO_DATA -> stringResource(R.string.summary_ring_no_data)
        SummaryRingState.NO_GOAL -> ring.value?.let { "${fmt(it)} $unit" } ?: stringResource(R.string.summary_ring_no_data)
        SummaryRingState.VALUE -> "${fmt(ring.value ?: 0.0)}/${fmt(ring.goal ?: 0.0)} $unit"
    }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .clickable(role = Role.Button, onClick = onClick)
            .testTag(ring.id.tag())
            .padding(vertical = 2.dp)
    ) {
        Text(label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
        Text(
            text,
            fontSize = if (ring.state == SummaryRingState.CONNECT) 15.sp else 18.sp,
            fontWeight = FontWeight.Bold,
            color = if (ring.state == SummaryRingState.CONNECT) AppColors.Calorie else color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** A tappable Today card: tinted icon, title, subtitle, optional trailing, optional progress bar. */
@Composable
internal fun TodayCard(
    icon: ImageVector,
    tint: Color,
    title: String,
    subtitle: String?,
    modifier: Modifier = Modifier,
    trailing: String? = null,
    progress: Float? = null,
    onClick: () -> Unit
) {
    SurfaceCard(modifier = modifier, padding = PaddingValues(14.dp), onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CategoryIcon(icon, tint, size = 32.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (!subtitle.isNullOrBlank()) {
                    Text(subtitle, fontSize = 13.sp, color = AyuvoColors.secondaryLabel(), maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            if (trailing != null) {
                Spacer(Modifier.width(8.dp))
                Text(trailing, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = tint)
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = AyuvoColors.tertiaryLabel())
        }
        if (progress != null) {
            Spacer(Modifier.height(10.dp))
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth().height(5.dp).clip(CircleShape),
                color = tint,
                trackColor = tint.copy(alpha = 0.16f)
            )
        }
    }
}

/** Medications today: taken / total and the next due dose (only while a medication is active or paused). */
@Composable
internal fun MedicationsTodayCard(timeline: TodayTimeline, onOpen: () -> Unit) {
    val context = LocalContext.current
    val summary = timeline.summary
    val next = timeline.groups.asSequence().flatMap { it.items.asSequence() }
        .firstOrNull { it.status == DoseStatus.DUE || it.status == DoseStatus.SNOOZED || it.status == DoseStatus.SCHEDULED }
    val nextMedication = next?.let { timeline.medications[it.medicationId] }
    val subtitle = when {
        next != null && nextMedication != null ->
            stringResource(R.string.home_medications_next, MedicationFormat.nameWithStrength(nextMedication), MedicationFormat.time(context, next.scheduledAtMs))
        summary.total == 0 -> stringResource(R.string.home_medications_none_today)
        else -> stringResource(R.string.home_medications_done)
    }
    // Two ids until the UI tests move: the old Home card id wraps the new Summary one.
    Box(Modifier.testTag("home.medicationsCard")) {
        TodayCard(
            icon = Icons.Filled.Medication,
            tint = AyuvoPalette.Medications,
            title = if (summary.total > 0) stringResource(R.string.home_medications_taken, summary.taken, summary.total)
            else stringResource(R.string.home_medications_title),
            subtitle = subtitle,
            modifier = Modifier.testTag("summary.card.medications"),
            onClick = onOpen
        )
    }
}

/** Active fast: live elapsed time against the goal. */
@Composable
internal fun FastingTodayCard(session: FastingSession, onOpen: () -> Unit) {
    var now by remember(session.id) { mutableStateOf(Instant.now()) }
    LaunchedEffect(session.id) {
        while (true) {
            delay(30_000)
            now = Instant.now()
        }
    }
    val elapsed = session.durationSeconds(now)
    val goalSeconds = session.goalMinutes * 60L
    TodayCard(
        icon = Icons.Filled.Timer,
        tint = AyuvoPalette.Fasting,
        title = stringResource(R.string.fasting_in_progress),
        subtitle = stringResource(R.string.fasting_goal_format, formatFastingDuration(goalSeconds)),
        trailing = formatFastingDuration(elapsed),
        progress = if (goalSeconds > 0) (elapsed.toFloat() / goalSeconds).coerceIn(0f, 1f) else null,
        modifier = Modifier.testTag("summary.card.fasting"),
        onClick = onOpen
    )
}

/** Workouts logged today: count, time and (when estimated) energy. */
@Composable
internal fun WorkoutTodayCard(sessions: List<WorkoutSession>, onOpen: () -> Unit) {
    val minutes = sessions.sumOf { it.durationMinutes }
    val burns = sessions.mapNotNull { it.caloriesBurned }
    val parts = buildList {
        if (minutes > 0) add(stringResource(R.string.summary_minutes_format, minutes))
        if (burns.isNotEmpty()) add(stringResource(R.string.kcal_value_format, burns.sum()))
    }
    TodayCard(
        icon = Icons.Filled.FitnessCenter,
        tint = AyuvoPalette.Activity,
        title = pluralStringResource(R.plurals.summary_workouts_today, sessions.size, sessions.size),
        subtitle = parts.joinToString(" · ").ifBlank { null },
        modifier = Modifier.testTag("summary.card.workouts"),
        onClick = onOpen
    )
}

/** One highlight card (records highlight, weight trend or latest workout). */
@Composable
internal fun HighlightCard(
    highlight: SummaryHighlight,
    weightMetric: Boolean,
    onOpenRecord: (String) -> Unit,
    onOpenWeight: () -> Unit,
    onOpenWorkouts: () -> Unit
) {
    when (highlight) {
        is SummaryHighlight.Record -> TodayCard(
            icon = Icons.Filled.Description,
            tint = AyuvoPalette.Records,
            title = highlight.item.highlight.text,
            subtitle = "${highlight.item.record.title} · ${RecordFormat.displayDate(highlight.item.record)}",
            modifier = Modifier.testTag("summary.card.records"),
            onClick = { onOpenRecord(highlight.item.record.id) }
        )
        is SummaryHighlight.WeightTrend -> {
            val weekly = if (weightMetric) highlight.weeklyChangeKg else highlight.weeklyChangeKg * 2.20462
            val unit = if (weightMetric) stringResource(R.string.unit_kg) else stringResource(R.string.unit_lbs)
            val amount = String.format(Locale.getDefault(), "%.1f %s", abs(weekly), unit)
            val title = when {
                abs(weekly) < 0.05 -> stringResource(R.string.summary_weight_steady)
                weekly < 0 -> stringResource(R.string.summary_weight_down, amount)
                else -> stringResource(R.string.summary_weight_up, amount)
            }
            TodayCard(
                icon = Icons.Filled.MonitorWeight,
                tint = AyuvoPalette.Body,
                title = title,
                subtitle = pluralStringResource(R.plurals.summary_weight_trend_basis, highlight.weighIns, highlight.weighIns),
                onClick = onOpenWeight
            )
        }
        is SummaryHighlight.LatestWorkout -> {
            val s = highlight.session
            val first = s.exercises.firstOrNull()?.name
            val title = when {
                first == null -> stringResource(R.string.home_workout_fallback_title)
                s.exerciseCount > 1 -> stringResource(R.string.home_workout_title_plus_more, first, s.exerciseCount - 1)
                else -> first
            }
            val parts = buildList {
                add(com.ayuvo.health.ui.health.relativeTimeText(s.completedAt.toEpochMilli()))
                if (s.durationMinutes > 0) add(stringResource(R.string.summary_minutes_format, s.durationMinutes))
                s.caloriesBurned?.let { add(stringResource(R.string.kcal_value_format, it)) }
            }
            TodayCard(
                icon = Icons.Filled.FitnessCenter,
                tint = AyuvoPalette.Activity,
                title = title,
                subtitle = stringResource(R.string.summary_latest_workout) + " · " + parts.joinToString(" · "),
                onClick = onOpenWorkouts
            )
        }
    }
}

/** "Get More From Ayuvo": remaining setup steps; done rows disappear, the card can be dismissed. */
@Composable
internal fun ChecklistCard(
    items: List<ChecklistItem>,
    onItem: (ChecklistItem) -> Unit,
    onDismiss: () -> Unit
) {
    SurfaceCard(padding = PaddingValues(0.dp), modifier = Modifier.testTag("summary.checklist")) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, top = 6.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.summary_get_more), fontSize = 17.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            IconButton(onClick = onDismiss, modifier = Modifier.testTag("summary.checklist.dismiss")) {
                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.summary_checklist_dismiss), tint = AyuvoColors.secondaryLabel())
            }
        }
        items.forEach { item ->
            val (icon, tint, title, subtitle) = when (item) {
                ChecklistItem.CONNECT_HEALTH -> Quad(Icons.Filled.Favorite, AyuvoPalette.Heart, R.string.summary_check_connect, R.string.summary_check_connect_body)
                ChecklistItem.REMINDERS -> Quad(Icons.Filled.Notifications, AyuvoPalette.Warning, R.string.summary_check_reminders, R.string.summary_check_reminders_body)
                ChecklistItem.ADD_RECORD -> Quad(Icons.Filled.Description, AyuvoPalette.Records, R.string.summary_check_record, R.string.summary_check_record_body)
                ChecklistItem.ADD_MEDICATIONS -> Quad(Icons.Filled.Medication, AyuvoPalette.Medications, R.string.summary_check_medications, R.string.summary_check_medications_body)
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .clickable(role = Role.Button) { onItem(item) }
                    .testTag("summary.checklist.${item.name.lowercase()}")
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CategoryIcon(icon, tint, size = 30.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(title), fontSize = 16.sp, fontWeight = FontWeight.Medium)
                    Text(stringResource(subtitle), fontSize = 13.sp, color = AyuvoColors.secondaryLabel())
                }
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = AyuvoColors.tertiaryLabel(), modifier = Modifier.size(22.dp))
            }
        }
        Spacer(Modifier.height(6.dp))
    }
}

private data class Quad<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)
