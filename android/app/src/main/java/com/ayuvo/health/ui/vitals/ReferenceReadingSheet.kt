package com.ayuvo.health.ui.vitals

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ayuvo.health.AppContainer
import com.ayuvo.health.R
import com.ayuvo.health.ui.components.GlassDialog
import com.ayuvo.health.ui.components.GlassPrimaryButton
import com.ayuvo.health.ui.components.GlassTextButton
import com.ayuvo.health.ui.design.AyuvoColors
import com.ayuvo.health.vitals.session.VitalCalibrationHooks
import com.ayuvo.health.vitals.session.VitalReference
import com.ayuvo.health.vitals.storage.VitalScanRecord
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.util.Locale

private fun parseNumber(text: String): Double? =
    text.trim().replace(',', '.').takeIf { it.isNotEmpty() }?.toDoubleOrNull()?.takeIf { it.isFinite() }

private fun fieldText(v: Double?): String = v?.let {
    NumberFormat.getNumberInstance(Locale.getDefault()).apply { maximumFractionDigits = 1; isGroupingUsed = false }.format(it)
}.orEmpty()

/**
 * Reference reading sheet (docs/camera-vitals.md §7.1): chest strap / ECG HR, RMSSD, respiratory rate, oximeter SpO₂,
 * cuff BP and the device name, stored in `reference_json`. On a finger scan the SpO₂ / BP values can also become a
 * personal calibration when the matching estimate toggle is on (the calibration hooks of §7.1).
 */
@Composable
fun ReferenceReadingSheet(
    container: AppContainer,
    record: VitalScanRecord,
    experimentalEnabled: Boolean,
    researchEnabled: Boolean,
    onDismiss: () -> Unit,
    onSaved: () -> Unit
) {
    val existing = remember(record.id) { VitalReference.parse(record.referenceJson) ?: VitalReference() }
    var hr by remember { mutableStateOf(fieldText(existing.heartRate)) }
    var rmssd by remember { mutableStateOf(fieldText(existing.rmssd)) }
    var resp by remember { mutableStateOf(fieldText(existing.respiratoryRate)) }
    var spo2 by remember { mutableStateOf(fieldText(existing.spo2)) }
    var sys by remember { mutableStateOf(fieldText(existing.systolic)) }
    var dia by remember { mutableStateOf(fieldText(existing.diastolic)) }
    var device by remember { mutableStateOf(existing.device.orEmpty()) }
    val spo2Ratio = remember(record.id, experimentalEnabled) { VitalCalibrationHooks.spo2Ratio(record, experimentalEnabled) }
    val bpFeatures = remember(record.id, researchEnabled) { VitalCalibrationHooks.bpFeatures(record, researchEnabled) }
    var useSpo2 by remember { mutableStateOf(false) }
    var useBp by remember { mutableStateOf(false) }
    var gap by remember { mutableStateOf("0") }
    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val reference = VitalReference(
        heartRate = parseNumber(hr), rmssd = parseNumber(rmssd), respiratoryRate = parseNumber(resp), spo2 = parseNumber(spo2),
        systolic = parseNumber(sys), diastolic = parseNumber(dia), device = device
    )
    val invalid = reference.invalidField()
    val gapMin = parseNumber(gap)?.takeIf { it in 0.0..1440.0 }
    val spo2CalOk = !useSpo2 || (spo2Ratio != null && reference.spo2 != null)
    val bpCalOk = !useBp || (bpFeatures != null && reference.systolic != null && reference.diastolic != null && gapMin != null)
    val canSave = !saving && invalid == null && spo2CalOk && bpCalOk && (!reference.isEmpty || existing != VitalReference())

    GlassDialog(onDismissRequest = onDismiss) {
        Text(stringResource(R.string.camvitals_reference_title), fontSize = 21.sp, fontWeight = FontWeight.Bold)
        Column(
            Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()).testTag("vitals.reference"),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(stringResource(R.string.camvitals_reference_body), fontSize = 13.sp, color = AyuvoColors.secondaryLabel())
            NumberField(hr, { hr = it }, R.string.camvitals_reference_hr, R.string.camvitals_unit_bpm, invalid == "heart_rate", "vitals.reference.hr")
            NumberField(rmssd, { rmssd = it }, R.string.camvitals_reference_rmssd, R.string.camvitals_unit_ms, invalid == "hrv_rmssd", "vitals.reference.rmssd")
            NumberField(resp, { resp = it }, R.string.camvitals_reference_resp, R.string.camvitals_unit_per_min, invalid == "respiratory_rate", "vitals.reference.resp")
            NumberField(spo2, { spo2 = it }, R.string.camvitals_reference_spo2, R.string.camvitals_unit_percent, invalid == "spo2", "vitals.reference.spo2")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f)) {
                    NumberField(sys, { sys = it }, R.string.camvitals_reference_sys, R.string.camvitals_unit_mmhg, invalid == "blood_pressure", "vitals.reference.sys")
                }
                Column(Modifier.weight(1f)) {
                    NumberField(dia, { dia = it }, R.string.camvitals_reference_dia, R.string.camvitals_unit_mmhg, invalid == "blood_pressure", "vitals.reference.dia")
                }
            }
            OutlinedTextField(
                value = device,
                onValueChange = { device = it.take(VitalReference.MAX_DEVICE_CHARS) },
                label = { Text(stringResource(R.string.camvitals_reference_device)) },
                placeholder = { Text(stringResource(R.string.camvitals_reference_device_hint)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("vitals.reference.device")
            )
            if (spo2Ratio != null) {
                CheckRow(useSpo2, { useSpo2 = it }, R.string.camvitals_use_spo2_calibration, R.string.camvitals_use_spo2_calibration_sub, "vitals.reference.spo2cal")
            }
            if (bpFeatures != null) {
                CheckRow(useBp, { useBp = it }, R.string.camvitals_use_bp_calibration, R.string.camvitals_use_bp_calibration_sub, "vitals.reference.bpcal")
                if (useBp) {
                    NumberField(gap, { gap = it }, R.string.camvitals_scan_gap_min, R.string.camvitals_unit_min, gapMin == null, "vitals.reference.gap")
                }
            }
            if (invalid != null) {
                Text(stringResource(R.string.camvitals_reference_invalid), fontSize = 13.sp, color = MaterialTheme.colorScheme.error)
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            GlassTextButton(
                text = stringResource(R.string.action_cancel),
                onClick = onDismiss,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.68f)
            )
            Spacer(Modifier.width(8.dp))
            GlassPrimaryButton(
                text = stringResource(R.string.action_save),
                enabled = canSave,
                modifier = Modifier.width(132.dp).testTag("vitals.reference.save"),
                onClick = {
                    saving = true
                    scope.launch {
                        val repo = container.vitalScans
                        val now = System.currentTimeMillis()
                        runCatching { repo.updateReference(record.id, reference.toJsonText()) }
                        if (useSpo2 && spo2Ratio != null && reference.spo2 != null) {
                            runCatching { repo.saveCalibration(VitalCalibrationHooks.spo2Calibration(record, spo2Ratio, reference.spo2, now)) }
                        }
                        if (useBp && bpFeatures != null && reference.systolic != null && reference.diastolic != null && gapMin != null) {
                            runCatching {
                                repo.saveCalibration(
                                    VitalCalibrationHooks.bpCalibration(record, bpFeatures, reference.systolic, reference.diastolic, gapMin, now)
                                )
                            }
                        }
                        saving = false
                        onSaved()
                    }
                }
            )
        }
    }
}

@Composable
private fun NumberField(text: String, onChange: (String) -> Unit, label: Int, unit: Int, error: Boolean, tag: String) {
    OutlinedTextField(
        value = text,
        onValueChange = { s -> onChange(s.filter { it.isDigit() || it == '.' || it == ',' }.take(6)) },
        label = { Text(stringResource(label)) },
        suffix = { Text(stringResource(unit)) },
        isError = error,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = Modifier.fillMaxWidth().testTag(tag)
    )
}

@Composable
private fun CheckRow(checked: Boolean, onChange: (Boolean) -> Unit, title: Int, subtitle: Int, tag: String) {
    Row(Modifier.fillMaxWidth().testTag(tag), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = onChange)
        Column(Modifier.weight(1f)) {
            Text(stringResource(title), fontSize = 15.sp)
            Text(stringResource(subtitle), fontSize = 12.sp, color = AyuvoColors.secondaryLabel())
        }
    }
}
