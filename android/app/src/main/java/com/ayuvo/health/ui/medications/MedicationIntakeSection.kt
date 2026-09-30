package com.ayuvo.health.ui.medications

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ayuvo.health.R
import com.ayuvo.health.data.intake.MedsAdherenceResult
import com.ayuvo.health.data.intake.SupplementDailyResult
import com.ayuvo.health.nutrients.NutrientFields
import com.ayuvo.health.nutrients.NutrientFormat
import com.ayuvo.health.nutrients.SupplementAveraging
import com.ayuvo.health.ui.components.GlassDialog
import com.ayuvo.health.ui.components.GlassDialogActions
import com.ayuvo.health.ui.design.SurfaceCard
import com.ayuvo.health.ui.theme.AppColors
import com.ayuvo.health.ui.util.clockTimePattern
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.roundToInt

/** Formats `HH:mm` with the device clock setting. */
@Composable
internal fun reminderClockText(hhmm: String): String {
    val context = LocalContext.current
    val formatter = remember(context) { DateTimeFormatter.ofPattern(clockTimePattern(context), Locale.getDefault()) }
    return runCatching { LocalTime.parse(hhmm).format(formatter) }.getOrDefault(hhmm)
}

/**
 * Last-30-days adherence card (docs/intake-metrics.md §3): % of doses taken (≥ 80% adherent), on-time share, median
 * delay, streak, missed doses per weekday and, when the engine suggests one, "Move reminder to HH:MM?".
 */
@Composable
internal fun MedicationAdherenceInsightsCard(
    result: MedsAdherenceResult,
    suggestedReminder: String?,
    onMoveReminder: () -> Unit
) {
    var confirmMove by rememberSaveable { mutableStateOf(false) }
    val muted = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
    val divider = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
    SurfaceCard(Modifier.fillMaxWidth().testTag("medications.detail.intake_adherence"), padding = PaddingValues(0.dp)) {
        Column(Modifier.padding(vertical = 4.dp)) {
            Text(
                stringResource(R.string.intake_adherence_title),
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = muted,
                modifier = Modifier.padding(start = 16.dp, top = 10.dp, end = 16.dp, bottom = 2.dp)
            )
            val pct = result.adherencePct
            if (pct == null) {
                Text(
                    stringResource(R.string.intake_adherence_none),
                    fontSize = 14.sp,
                    color = muted,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                )
                return@Column
            }
            Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    stringResource(R.string.intake_adherence_pct, NutrientFormat.amount(pct), result.taken, result.scheduled),
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    stringResource(if (result.adherent == true) R.string.intake_adherence_adherent else R.string.intake_adherence_below),
                    fontSize = 13.sp,
                    color = if (result.adherent == true) AppColors.Calorie else muted
                )
            }
            HorizontalDivider(color = divider)
            result.onTimePct?.let { StatRow(stringResource(R.string.intake_adherence_on_time), stringResource(R.string.intake_percent, NutrientFormat.amount(it))) }
            result.medianDelayMin?.let {
                HorizontalDivider(color = divider)
                StatRow(stringResource(R.string.intake_adherence_median_delay), stringResource(R.string.intake_minutes, it.roundToInt()))
            }
            HorizontalDivider(color = divider)
            StatRow(stringResource(R.string.intake_adherence_streak), stringResource(R.string.intake_doses, result.streak))
            if (result.missedByWeekday.any { it > 0 }) {
                HorizontalDivider(color = divider)
                Text(
                    stringResource(R.string.intake_adherence_missed_by_weekday),
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp)
                )
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
                    result.missedByWeekday.forEachIndexed { i, n ->
                        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(DayOfWeek.of(i + 1).getDisplayName(TextStyle.SHORT, Locale.getDefault()), fontSize = 11.sp, color = muted)
                            Text(n.toString(), fontSize = 15.sp, fontWeight = if (n > 0) FontWeight.SemiBold else FontWeight.Normal)
                        }
                    }
                }
            }
            if (suggestedReminder != null) {
                HorizontalDivider(color = divider)
                Text(
                    stringResource(R.string.intake_move_reminder, reminderClockText(suggestedReminder)),
                    color = AppColors.Calorie,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { confirmMove = true }
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                        .testTag("medications.detail.move_reminder")
                )
            }
        }
    }
    if (confirmMove && suggestedReminder != null) {
        GlassDialog(onDismissRequest = { confirmMove = false }) {
            Text(stringResource(R.string.intake_move_reminder, reminderClockText(suggestedReminder)), fontSize = 21.sp, fontWeight = FontWeight.Bold)
            Text(stringResource(R.string.intake_move_reminder_body), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f))
            GlassDialogActions(
                primaryText = stringResource(R.string.intake_move_reminder_action),
                onPrimary = { confirmMove = false; onMoveReminder() },
                dismissText = stringResource(R.string.action_cancel),
                onDismiss = { confirmMove = false }
            )
        }
    }
}

@Composable
private fun StatRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
        Text(value, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f), textAlign = TextAlign.End)
    }
}

/**
 * Supplement regimens whose daily average over the dosing interval is above the upper limit. The copy defers to the
 * prescriber (prescribed short courses can exceed it) and never says to stop.
 */
@Composable
internal fun SupplementUpperLimitNotes(regimens: Map<String, SupplementDailyResult>) {
    val context = LocalContext.current
    for ((key, r) in regimens) {
        if (!r.aboveUpper) continue
        val unit = NutrientFields.unit(key)
        val upper = SupplementAveraging.upperLimit(key) ?: continue
        Text(
            stringResource(
                R.string.intake_supplement_above_upper,
                NutrientFields.displayName(context, key),
                NutrientFormat.withUnit(r.dailyAverage, unit),
                NutrientFormat.withUnit(upper, unit)
            ),
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp).testTag("medications.detail.upper.$key")
        )
    }
}
