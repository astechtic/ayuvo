package com.ayuvo.health.ui.medications

import android.content.Context
import android.text.format.DateFormat
import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Snooze
import androidx.compose.material.icons.filled.PauseCircle
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material.icons.filled.TaskAlt
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.ayuvo.health.R
import com.ayuvo.health.medications.logic.MedicationLocalTime
import com.ayuvo.health.medications.model.DoseStatus
import com.ayuvo.health.medications.model.DoseUnit
import com.ayuvo.health.medications.model.FoodRelation
import com.ayuvo.health.medications.model.Medication
import com.ayuvo.health.medications.model.MedicationForm
import com.ayuvo.health.medications.model.MedicationSchedule
import com.ayuvo.health.medications.model.MedicationStatus
import com.ayuvo.health.medications.model.ScheduleFrequency
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/** Display helpers shared by every Medications screen (docs/medications.md §8–§9, §16–§17). */
internal object MedicationFormat {
    /** "1", "½", "1.5" — the dose quantity as people write it. */
    fun quantity(q: Double): String {
        val rounded = q.roundToInt()
        if (abs(q - rounded) < 1e-9) return rounded.toString()
        return when {
            abs(q - 0.5) < 1e-9 -> "½"
            abs(q - 0.25) < 1e-9 -> "¼"
            abs(q - 0.75) < 1e-9 -> "¾"
            abs(q - 1.5) < 1e-9 -> "1½"
            else -> String.format(Locale.getDefault(), "%.2f", q).trimEnd('0').trimEnd('.', ',')
        }
    }

    /** Device 12/24-hour time of an instant. */
    fun time(context: Context, ms: Long): String = DateFormat.getTimeFormat(context).format(Date(ms))

    /** Device 12/24-hour rendering of an `HH:mm` slot. */
    fun slot(context: Context, hhmm: String, zone: ZoneId = ZoneId.systemDefault()): String {
        val (h, m) = MedicationLocalTime.parseHhmm(hhmm) ?: return hhmm
        val instant = LocalDate.now(zone).atTime(LocalTime.of(h, m)).atZone(zone).toInstant()
        return time(context, instant.toEpochMilli())
    }

    fun date(isoDate: String?): String {
        val d = MedicationLocalTime.parseDate(isoDate) ?: return isoDate.orEmpty()
        return d.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))
    }

    fun dateTime(context: Context, ms: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        val date = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate().format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))
        return "$date · ${time(context, ms)}"
    }

    /** "Today", "Yesterday" or the medium date, for history day headers. */
    fun dayHeader(context: Context, isoDate: String, today: LocalDate = LocalDate.now()): String {
        val d = MedicationLocalTime.parseDate(isoDate) ?: return isoDate
        return when (d) {
            today -> context.getString(R.string.medications_today)
            today.minusDays(1) -> context.getString(R.string.medications_yesterday)
            else -> d.format(DateTimeFormatter.ofPattern("EEEE, d MMM", Locale.getDefault()))
        }
    }

    fun weekday(iso: Int): String = DayOfWeek.of(iso.coerceIn(1, 7)).getDisplayName(TextStyle.SHORT, Locale.getDefault())

    fun nameWithStrength(m: Medication): String =
        if (m.strength.isNullOrBlank()) m.name else "${m.name} ${m.strength}"
}

@StringRes
internal fun DoseStatus.labelRes(): Int = when (this) {
    DoseStatus.SCHEDULED -> R.string.medications_status_scheduled
    DoseStatus.DUE -> R.string.medications_status_due
    DoseStatus.TAKEN -> R.string.medications_status_taken
    DoseStatus.SKIPPED -> R.string.medications_status_skipped
    DoseStatus.MISSED -> R.string.medications_status_missed
    DoseStatus.SNOOZED -> R.string.medications_status_snoozed
}

internal fun DoseStatus.icon(): ImageVector = when (this) {
    DoseStatus.SCHEDULED -> Icons.Filled.RadioButtonUnchecked
    DoseStatus.DUE -> Icons.Filled.NotificationsActive
    DoseStatus.TAKEN -> Icons.Filled.CheckCircle
    DoseStatus.SKIPPED -> Icons.Filled.SkipNext
    DoseStatus.MISSED -> Icons.Filled.ErrorOutline
    DoseStatus.SNOOZED -> Icons.Filled.Snooze
}

@StringRes
internal fun MedicationStatus.labelRes(): Int = when (this) {
    MedicationStatus.ACTIVE -> R.string.medications_filter_active
    MedicationStatus.PAUSED -> R.string.medications_filter_paused
    MedicationStatus.COMPLETED -> R.string.medications_filter_completed
    MedicationStatus.STOPPED -> R.string.medications_filter_stopped
}

internal fun MedicationStatus.icon(): ImageVector = when (this) {
    MedicationStatus.ACTIVE -> Icons.Filled.Alarm
    MedicationStatus.PAUSED -> Icons.Filled.PauseCircle
    MedicationStatus.COMPLETED -> Icons.Filled.TaskAlt
    MedicationStatus.STOPPED -> Icons.Filled.StopCircle
}

@StringRes
internal fun MedicationForm.labelRes(): Int = when (this) {
    MedicationForm.TABLET -> R.string.medications_form_tablet
    MedicationForm.CAPSULE -> R.string.medications_form_capsule
    MedicationForm.SYRUP -> R.string.medications_form_syrup
    MedicationForm.INJECTION -> R.string.medications_form_injection
    MedicationForm.CREAM -> R.string.medications_form_cream
    MedicationForm.DROPS -> R.string.medications_form_drops
    MedicationForm.INHALER -> R.string.medications_form_inhaler
    MedicationForm.OTHER -> R.string.medications_form_other
}

@StringRes
internal fun FoodRelation.labelRes(): Int = when (this) {
    FoodRelation.BEFORE -> R.string.medications_food_before
    FoodRelation.WITH -> R.string.medications_food_with
    FoodRelation.AFTER -> R.string.medications_food_after
    FoodRelation.ANYTIME -> R.string.medications_food_anytime
}

@StringRes
internal fun DoseUnit.labelRes(): Int = when (this) {
    DoseUnit.TABLET -> R.string.medications_unit_tablet
    DoseUnit.CAPSULE -> R.string.medications_unit_capsule
    DoseUnit.ML -> R.string.medications_unit_ml
    DoseUnit.MG -> R.string.medications_unit_mg
    DoseUnit.G -> R.string.medications_unit_g
    DoseUnit.MCG -> R.string.medications_unit_mcg
    DoseUnit.DROP -> R.string.medications_unit_drop
    DoseUnit.PUFF -> R.string.medications_unit_puff
    DoseUnit.UNIT -> R.string.medications_unit_unit
    DoseUnit.SACHET -> R.string.medications_unit_sachet
    DoseUnit.APPLICATION -> R.string.medications_unit_application
    DoseUnit.OTHER -> R.string.medications_unit_other
}

/** "1 tablet", "2 capsules", "5 ml": countable units pluralize, measured ones do not. */
@Composable
internal fun doseText(quantity: Double, unit: DoseUnit): String {
    val q = MedicationFormat.quantity(quantity)
    val count = if (quantity <= 1.0) 1 else 2
    return when (unit) {
        DoseUnit.TABLET -> pluralStringResource(R.plurals.medications_dose_tablet, count, q)
        DoseUnit.CAPSULE -> pluralStringResource(R.plurals.medications_dose_capsule, count, q)
        DoseUnit.DROP -> pluralStringResource(R.plurals.medications_dose_drop, count, q)
        DoseUnit.PUFF -> pluralStringResource(R.plurals.medications_dose_puff, count, q)
        DoseUnit.UNIT -> pluralStringResource(R.plurals.medications_dose_unit, count, q)
        DoseUnit.SACHET -> pluralStringResource(R.plurals.medications_dose_sachet, count, q)
        DoseUnit.APPLICATION -> pluralStringResource(R.plurals.medications_dose_application, count, q)
        DoseUnit.ML, DoseUnit.MG, DoseUnit.G, DoseUnit.MCG, DoseUnit.OTHER ->
            stringResource(R.string.medications_dose_measured, q, stringResource(unit.labelRes()))
    }
}

/** "Every day · 8:00 AM, 8:00 PM" / "Mon, Wed, Fri · 8:00 AM" / "Every 8 hours from 8:00 AM" / "As needed". */
@Composable
internal fun scheduleDescription(medication: Medication, schedule: MedicationSchedule?): String {
    val context = LocalContext.current
    if (medication.isPrn) return stringResource(R.string.medications_frequency_prn)
    if (schedule == null) return stringResource(R.string.medications_frequency_none)
    val times = schedule.times.joinToString(", ") { MedicationFormat.slot(context, it) }
    return when (schedule.frequency) {
        ScheduleFrequency.DAILY -> stringResource(R.string.medications_frequency_daily, times)
        ScheduleFrequency.WEEKLY -> stringResource(
            R.string.medications_frequency_weekly,
            schedule.days.joinToString(", ") { MedicationFormat.weekday(it) },
            times
        )
        ScheduleFrequency.INTERVAL -> stringResource(
            R.string.medications_frequency_interval,
            schedule.intervalHours ?: 0,
            MedicationFormat.slot(context, schedule.anchorTime ?: "08:00")
        )
    }
}

/** "2 h ago" / "just now" / "3 days ago" for PRN rows and history. */
@Composable
internal fun relativeAgo(ms: Long, nowMs: Long = System.currentTimeMillis()): String {
    val diffMin = ((nowMs - ms) / 60_000L).toInt()
    return when {
        diffMin < 1 -> stringResource(R.string.medications_relative_just_now)
        diffMin < 60 -> pluralStringResource(R.plurals.medications_relative_minutes, diffMin, diffMin)
        diffMin < 60 * 24 -> pluralStringResource(R.plurals.medications_relative_hours, diffMin / 60, diffMin / 60)
        else -> pluralStringResource(R.plurals.medications_relative_days, diffMin / (60 * 24), diffMin / (60 * 24))
    }
}
