package com.ayuvo.health.ui.medications

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ayuvo.health.R
import com.ayuvo.health.medications.model.DoseAction
import com.ayuvo.health.medications.model.DoseStatus
import com.ayuvo.health.medications.model.Medication
import com.ayuvo.health.medications.model.TimelineItem
import com.ayuvo.health.ui.components.GlassPrimaryButton
import com.ayuvo.health.ui.components.GlassTextButton
import com.ayuvo.health.ui.components.GlassTextField
import com.ayuvo.health.ui.home.SheetTimePickerDialog
import com.ayuvo.health.ui.records.RecordChip
import com.ayuvo.health.ui.theme.AppColors
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

/**
 * The user's explicit action on one scheduled dose (docs/medications.md §11): Taken now / Taken
 * at a time, Skip, Snooze 10·30·60, an optional note; Undo for a taken or skipped dose.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DoseActionSheet(
    item: TimelineItem,
    medication: Medication?,
    defaultSnoozeMinutes: Int,
    onAction: (action: DoseAction, snoozeMinutes: Int, takenAtMs: Long?, note: String?) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var note by rememberSaveable { mutableStateOf("") }
    var snooze by rememberSaveable { mutableStateOf(defaultSnoozeMinutes) }
    var pickTime by remember { mutableStateOf(false) }
    var timeError by remember { mutableStateOf(false) }
    val zone = ZoneId.systemDefault()
    val resolvable = item.status == DoseStatus.SCHEDULED || item.status == DoseStatus.DUE ||
        item.status == DoseStatus.SNOOZED || item.status == DoseStatus.MISSED
    val canSnooze = item.status == DoseStatus.DUE || item.status == DoseStatus.SNOOZED
    val canUndo = item.status == DoseStatus.TAKEN || item.status == DoseStatus.SKIPPED
    val noteOrNull = note.trim().ifEmpty { null }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        containerColor = medicationSheetColor()
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .navigationBarsPadding()
                .imePadding()
                .padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                medication?.let { MedicationFormat.nameWithStrength(it) } ?: stringResource(R.string.medications_unknown_medicine),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    stringResource(R.string.medications_dose_line, doseText(item.doseQuantity, item.doseUnit), MedicationFormat.time(context, item.scheduledAtMs)),
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
                StatusBadge(status = item.status, late = item.isLate)
            }
            if (item.status == DoseStatus.SNOOZED && item.snoozedUntilMs != null) {
                Text(
                    stringResource(R.string.medications_snoozed_until, MedicationFormat.time(context, item.snoozedUntilMs)),
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
                )
            }
            if (resolvable || canUndo) {
                GlassTextField(
                    value = note,
                    onValueChange = { if (it.length <= 200) note = it },
                    placeholder = stringResource(R.string.medications_note_placeholder)
                )
            }
            if (resolvable) {
                GlassPrimaryButton(
                    text = stringResource(if (item.status == DoseStatus.MISSED) R.string.medications_action_take_late_now else R.string.medications_action_taken_now),
                    onClick = { onAction(DoseAction.TAKEN, snooze, null, noteOrNull) },
                    modifier = Modifier.fillMaxWidth().testTag("medications.action.taken")
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GlassTextButton(
                        text = stringResource(R.string.medications_action_taken_at),
                        onClick = { pickTime = true },
                        modifier = Modifier.weight(1f)
                    )
                    GlassTextButton(
                        text = stringResource(R.string.medications_action_skip),
                        onClick = { onAction(DoseAction.SKIPPED, snooze, null, noteOrNull) },
                        modifier = Modifier.weight(1f).testTag("medications.action.skip")
                    )
                }
                if (timeError) {
                    Text(stringResource(R.string.medications_error_future_time), fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                }
                if (canSnooze) {
                    Text(stringResource(R.string.medications_snooze_for), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SNOOZE_OPTIONS.forEach { minutes ->
                            RecordChip(text = snoozeLabel(minutes), selected = snooze == minutes, onClick = { snooze = minutes })
                        }
                    }
                    GlassTextButton(
                        text = stringResource(R.string.medications_action_snooze),
                        onClick = { onAction(DoseAction.SNOOZED, snooze, null, noteOrNull) },
                        modifier = Modifier.fillMaxWidth().testTag("medications.action.snooze")
                    )
                }
            }
            if (canUndo) {
                GlassTextButton(
                    text = stringResource(R.string.medications_action_undo),
                    onClick = { onAction(DoseAction.UNDO, snooze, null, null) },
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)
                )
            }
            Spacer(Modifier.height(4.dp))
        }
    }
    if (pickTime) {
        val scheduledLocal = Instant.ofEpochMilli(item.scheduledAtMs).atZone(zone)
        SheetTimePickerDialog(
            initialTime = scheduledLocal.toLocalTime(),
            onConfirm = { time: LocalTime ->
                pickTime = false
                val takenAt = scheduledLocal.toLocalDate().atTime(time).atZone(zone).toInstant().toEpochMilli()
                if (takenAt > System.currentTimeMillis()) {
                    timeError = true
                } else {
                    timeError = false
                    onAction(DoseAction.TAKEN, snooze, takenAt, noteOrNull)
                }
            },
            onDismiss = { pickTime = false }
        )
    }
}

/** "Log dose" for an as-needed medicine (docs §11 `log_prn_dose`): time, quantity, note. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PrnLogSheet(
    medication: Medication,
    onLog: (takenAtMs: Long?, doseQuantity: Double?, note: String?) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val zone = ZoneId.systemDefault()
    var takenAt by rememberSaveable { mutableStateOf<Long?>(null) }
    var quantityText by rememberSaveable { mutableStateOf(MedicationFormat.quantity(medication.doseQuantity).replace("½", "0.5")) }
    var note by rememberSaveable { mutableStateOf("") }
    var pickTime by remember { mutableStateOf(false) }
    var timeError by remember { mutableStateOf(false) }
    val quantity = quantityText.replace(',', '.').toDoubleOrNull()
    val quantityValid = quantity != null && quantity > 0 && quantity <= 1000
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        containerColor = medicationSheetColor()
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .navigationBarsPadding()
                .imePadding()
                .padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(stringResource(R.string.medications_action_log_dose), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(MedicationFormat.nameWithStrength(medication), fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.medications_prn_time_label, takenAt?.let { MedicationFormat.time(context, it) } ?: stringResource(R.string.medications_now)),
                    fontSize = 14.sp,
                    modifier = Modifier.weight(1f)
                )
                GlassTextButton(text = stringResource(R.string.medications_action_change_time), onClick = { pickTime = true })
            }
            if (timeError) Text(stringResource(R.string.medications_error_future_time), fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GlassTextField(
                    value = quantityText,
                    onValueChange = { quantityText = it.take(8) },
                    placeholder = stringResource(R.string.medications_field_dose_quantity),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(1f)
                )
                Text(stringResource(medication.doseUnit.labelRes()), fontSize = 14.sp, color = AppColors.Calorie, fontWeight = FontWeight.SemiBold)
            }
            GlassTextField(
                value = note,
                onValueChange = { if (it.length <= 200) note = it },
                placeholder = stringResource(R.string.medications_note_placeholder)
            )
            GlassPrimaryButton(
                text = stringResource(R.string.medications_action_log_dose),
                enabled = quantityValid,
                onClick = { onLog(takenAt, quantity, note.trim().ifEmpty { null }) }
            )
            Spacer(Modifier.width(4.dp))
        }
    }
    if (pickTime) {
        val now = Instant.ofEpochMilli(takenAt ?: System.currentTimeMillis()).atZone(zone)
        SheetTimePickerDialog(
            initialTime = now.toLocalTime(),
            onConfirm = { time: LocalTime ->
                pickTime = false
                val ms = now.toLocalDate().atTime(time).atZone(zone).toInstant().toEpochMilli()
                if (ms > System.currentTimeMillis()) timeError = true else { timeError = false; takenAt = ms }
            },
            onDismiss = { pickTime = false }
        )
    }
}
