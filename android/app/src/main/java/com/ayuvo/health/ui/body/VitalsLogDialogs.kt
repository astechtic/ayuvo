package com.ayuvo.health.ui.body

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import com.ayuvo.health.AppContainer
import com.ayuvo.health.R
import com.ayuvo.health.data.health.ManualVitalType
import com.ayuvo.health.data.health.ManualVitals
import com.ayuvo.health.ui.components.GlassDialog
import com.ayuvo.health.ui.components.GlassPrimaryButton
import com.ayuvo.health.ui.components.GlassTextButton
import com.ayuvo.health.ui.components.UnitToggle
import com.ayuvo.health.ui.health.healthUnitPrefsFlow
import com.ayuvo.health.ui.records.RecordChip
import com.ayuvo.health.ui.theme.AppColors
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/* Manual blood glucose / body temperature logging (docs/health-data.md §2.2, docs/ui-structure.md §8). */

data class BloodGlucoseInput(val value: Double, val mgdl: Boolean, val atMs: Long, val specimen: Int, val relationToMeal: Int)

data class BodyTemperatureInput(val value: Double, val fahrenheit: Boolean, val atMs: Long, val location: Int)

/** Choice lists in display order; codes are the Health Connect ones stored in the row. */
internal object VitalsLogChoices {
    val relations: List<Pair<Int, Int>> = listOf(
        ManualVitals.RELATION_GENERAL to R.string.vitals_relation_general,
        ManualVitals.RELATION_FASTING to R.string.vitals_relation_fasting,
        ManualVitals.RELATION_BEFORE_MEAL to R.string.vitals_relation_before_meal,
        ManualVitals.RELATION_AFTER_MEAL to R.string.vitals_relation_after_meal
    )
    val specimens: List<Pair<Int, Int>> = listOf(
        ManualVitals.SPECIMEN_CAPILLARY_BLOOD to R.string.vitals_specimen_capillary,
        ManualVitals.SPECIMEN_INTERSTITIAL_FLUID to R.string.vitals_specimen_interstitial,
        ManualVitals.SPECIMEN_PLASMA to R.string.vitals_specimen_plasma,
        ManualVitals.SPECIMEN_WHOLE_BLOOD to R.string.vitals_specimen_whole_blood
    )
    val locations: List<Pair<Int, Int>> = listOf(
        0 to R.string.vitals_location_none,
        1 to R.string.vitals_location_armpit,
        2 to R.string.vitals_location_finger,
        3 to R.string.vitals_location_forehead,
        4 to R.string.vitals_location_mouth,
        5 to R.string.vitals_location_rectum,
        6 to R.string.vitals_location_temporal_artery,
        7 to R.string.vitals_location_toe,
        8 to R.string.vitals_location_ear,
        9 to R.string.vitals_location_wrist,
        10 to R.string.vitals_location_vagina
    )
}

/** Locale-tolerant decimal parsing ("5,4" and "5.4"). */
internal fun parseVitalValue(text: String): Double? =
    text.trim().replace(',', '.').takeIf { it.isNotEmpty() }?.toDoubleOrNull()?.takeIf { it.isFinite() }

private fun formatVital(value: Double, decimals: Int): String =
    NumberFormat.getNumberInstance(Locale.getDefault()).apply {
        maximumFractionDigits = decimals
        minimumFractionDigits = 0
        isGroupingUsed = false
    }.format(value)

@Composable
internal fun AddBloodGlucoseDialog(
    initialMgDl: Boolean,
    onUnitChange: (Boolean) -> Unit,
    onDismiss: () -> Unit,
    onSubmit: (BloodGlucoseInput) -> Unit
) {
    var mgdl by remember { mutableStateOf(initialMgDl) }
    var text by remember { mutableStateOf("") }
    var atMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var relation by remember { mutableIntStateOf(ManualVitals.RELATION_GENERAL) }
    var specimen by remember { mutableIntStateOf(ManualVitals.SPECIMEN_CAPILLARY_BLOOD) }
    val value = parseVitalValue(text)
    val valid = value != null && ManualVitals.glucoseInRange(value, mgdl)
    val unitLabel = stringResource(if (mgdl) R.string.health_unit_mg_dl else R.string.health_unit_mmol_l)
    val range = if (mgdl) {
        stringResource(R.string.vitals_log_range, formatVital(ManualVitals.GLUCOSE_MIN_MGDL, 0), formatVital(ManualVitals.GLUCOSE_MAX_MGDL, 0), unitLabel)
    } else {
        stringResource(R.string.vitals_log_range, formatVital(ManualVitals.GLUCOSE_MIN_MMOL, 1), formatVital(ManualVitals.GLUCOSE_MAX_MMOL, 1), unitLabel)
    }
    VitalsDialogFrame(
        title = R.string.vitals_log_glucose_title,
        saveEnabled = valid,
        onDismiss = onDismiss,
        onSave = { if (value != null && valid) onSubmit(BloodGlucoseInput(value, mgdl, atMs, specimen, relation)) }
    ) {
        UnitToggle(
            stringResource(R.string.health_unit_mmol_l),
            stringResource(R.string.health_unit_mg_dl),
            !mgdl,
            { mmolSelected ->
                val newMgdl = !mmolSelected
                if (newMgdl != mgdl) {
                    parseVitalValue(text)?.let { v ->
                        text = if (newMgdl) formatVital(ManualVitals.mmolToMgdl(v), 0) else formatVital(ManualVitals.mgdlToMmol(v), 1)
                    }
                    mgdl = newMgdl
                    onUnitChange(newMgdl)
                }
            },
            Modifier.fillMaxWidth().testTag("log.glucose.unit")
        )
        ValueField(text, { text = it }, unitLabel, range, text.isNotBlank() && !valid, "log.glucose.value")
        DateTimeRow(atMs) { atMs = it }
        ChoiceGroup(R.string.vitals_log_relation, VitalsLogChoices.relations, relation, "log.glucose.relation") { relation = it }
        ChoiceGroup(R.string.vitals_log_specimen, VitalsLogChoices.specimens, specimen, "log.glucose.specimen") { specimen = it }
    }
}

@Composable
internal fun AddBodyTemperatureDialog(
    initialFahrenheit: Boolean,
    onDismiss: () -> Unit,
    onSubmit: (BodyTemperatureInput) -> Unit
) {
    var fahrenheit by remember { mutableStateOf(initialFahrenheit) }
    var text by remember { mutableStateOf("") }
    var atMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var location by remember { mutableIntStateOf(ManualVitals.LOCATION_UNKNOWN) }
    val value = parseVitalValue(text)
    val valid = value != null && ManualVitals.temperatureInRange(value, fahrenheit)
    val unitLabel = if (fahrenheit) "°F" else "°C"
    val range = if (fahrenheit) {
        stringResource(R.string.vitals_log_range, formatVital(ManualVitals.TEMP_MIN_F, 1), formatVital(ManualVitals.TEMP_MAX_F, 1), unitLabel)
    } else {
        stringResource(R.string.vitals_log_range, formatVital(ManualVitals.TEMP_MIN_C, 1), formatVital(ManualVitals.TEMP_MAX_C, 1), unitLabel)
    }
    VitalsDialogFrame(
        title = R.string.vitals_log_temperature_title,
        saveEnabled = valid,
        onDismiss = onDismiss,
        onSave = { if (value != null && valid) onSubmit(BodyTemperatureInput(value, fahrenheit, atMs, location)) }
    ) {
        UnitToggle(
            "°C",
            "°F",
            !fahrenheit,
            { celsiusSelected ->
                val newF = !celsiusSelected
                if (newF != fahrenheit) {
                    parseVitalValue(text)?.let { v ->
                        text = formatVital(if (newF) ManualVitals.celsiusToFahrenheit(v) else ManualVitals.fahrenheitToCelsius(v), 1)
                    }
                    fahrenheit = newF
                }
            },
            Modifier.fillMaxWidth().testTag("log.temperature.unit")
        )
        ValueField(text, { text = it }, unitLabel, range, text.isNotBlank() && !valid, "log.temperature.value")
        DateTimeRow(atMs) { atMs = it }
        ChoiceGroup(R.string.vitals_log_location, VitalsLogChoices.locations, location, "log.temperature.location") { location = it }
    }
}

@Composable
private fun VitalsDialogFrame(
    @StringRes title: Int,
    saveEnabled: Boolean,
    onDismiss: () -> Unit,
    onSave: () -> Unit,
    content: @Composable () -> Unit
) {
    GlassDialog(onDismissRequest = onDismiss) {
        Text(stringResource(title), fontSize = 21.sp, fontWeight = FontWeight.Bold)
        Column(
            Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) { content() }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            GlassTextButton(
                text = stringResource(R.string.action_cancel),
                onClick = onDismiss,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.68f)
            )
            Spacer(Modifier.width(8.dp))
            GlassPrimaryButton(
                text = stringResource(R.string.action_save),
                onClick = onSave,
                enabled = saveEnabled,
                modifier = Modifier.width(132.dp).testTag("log.save")
            )
        }
    }
}

@Composable
private fun ValueField(text: String, onChange: (String) -> Unit, unit: String, range: String, error: Boolean, tag: String) {
    OutlinedTextField(
        value = text,
        onValueChange = { s -> onChange(s.filter { it.isDigit() || it == '.' || it == ',' }.take(6)) },
        label = { Text(stringResource(R.string.vitals_log_value)) },
        suffix = { Text(unit) },
        supportingText = { Text(range) },
        isError = error,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = Modifier.fillMaxWidth().testTag(tag)
    )
}

/** Date and time of the reading; defaults to now and never moves into the future. */
@Composable
private fun DateTimeRow(atMs: Long, onChange: (Long) -> Unit) {
    val context = LocalContext.current
    val zone = remember { ZoneId.systemDefault() }
    val fmt = remember { DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withLocale(Locale.getDefault()) }
    val at = LocalDateTime.ofInstant(Instant.ofEpochMilli(atMs), zone)
    Row(
        Modifier.fillMaxWidth().clickable { pickDateTime(context, at, zone, onChange) }.padding(vertical = 6.dp).testTag("log.time"),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(stringResource(R.string.vitals_log_time), fontSize = 16.sp, modifier = Modifier.weight(1f))
        Text(at.format(fmt), fontSize = 16.sp, color = AppColors.Calorie, fontWeight = FontWeight.Medium)
    }
}

private fun pickDateTime(context: Context, current: LocalDateTime, zone: ZoneId, onChange: (Long) -> Unit) {
    val date = DatePickerDialog(context, { _, y, m, d ->
        TimePickerDialog(context, { _, h, min ->
            val picked = LocalDateTime.of(y, m + 1, d, h, min).atZone(zone).toInstant().toEpochMilli()
            onChange(minOf(picked, System.currentTimeMillis()))
        }, current.hour, current.minute, android.text.format.DateFormat.is24HourFormat(context)).show()
    }, current.year, current.monthValue - 1, current.dayOfMonth)
    date.datePicker.maxDate = System.currentTimeMillis()
    date.show()
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChoiceGroup(@StringRes label: Int, choices: List<Pair<Int, Int>>, selected: Int, tag: String, onSelect: (Int) -> Unit) {
    Column(Modifier.testTag(tag), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(label), fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            choices.forEach { (code, res) ->
                RecordChip(text = stringResource(res), selected = code == selected, onClick = { onSelect(code) })
            }
        }
    }
}

/**
 * Shows the log dialog of [type] and saves through [AppContainer.manualVitals]. On the first save
 * of a type with health sync on, the Health Connect write permission is requested in context; a
 * denial still saves the entry locally (docs/health-data.md §2.2).
 */
@Composable
fun ManualVitalLogHost(container: AppContainer, type: ManualVitalType, onDone: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val units by remember { healthUnitPrefsFlow(container.prefs) }.collectAsState(initial = null)
    val asked = remember { context.getSharedPreferences(ASKED_PREFS, Context.MODE_PRIVATE) }
    var pending by remember { mutableStateOf<(suspend () -> Unit)?>(null) }

    fun persist(save: suspend () -> Unit) {
        // App scope: the entry must land even when the screen goes away mid-save.
        container.scope.launch { runCatching { save() } }
        onDone()
    }

    val launcher = rememberLauncherForActivityResult(container.health.permissionRequestContract()) {
        asked.edit().putBoolean(type.typeId, true).apply()
        pending?.let(::persist)
        pending = null
    }

    fun submit(save: suspend () -> Unit) {
        scope.launch {
            val eligible = !asked.getBoolean(type.typeId, false) &&
                container.prefs.healthConnectEnabled.first() &&
                runCatching { container.health.isAvailable() }.getOrDefault(false)
            val permission = container.health.manualVitalWritePermission(type)
            val missing = if (eligible) runCatching { container.health.missingPermissions(setOf(permission)) }.getOrNull() else null
            if (!missing.isNullOrEmpty()) {
                pending = save
                runCatching { launcher.launch(missing) }.onFailure { pending = null; persist(save) }
            } else {
                persist(save)
            }
        }
    }

    val u = units ?: return
    when (type) {
        ManualVitalType.BLOOD_GLUCOSE -> AddBloodGlucoseDialog(
            initialMgDl = u.glucoseMgDl,
            onUnitChange = { mg -> scope.launch { container.prefs.setHealthGlucoseUnit(if (mg) "mg/dL" else "mmol/L") } },
            onDismiss = onDone
        ) { input ->
            submit { container.manualVitals.logGlucose(input.value, input.mgdl, input.atMs, input.specimen, input.relationToMeal) }
        }
        ManualVitalType.BODY_TEMPERATURE -> AddBodyTemperatureDialog(
            initialFahrenheit = u.fahrenheit,
            onDismiss = onDone
        ) { input ->
            submit { container.manualVitals.logTemperature(input.value, input.fahrenheit, input.atMs, input.location) }
        }
    }
}

/** Only an "already asked" flag per type lives here; values are never stored outside the database. */
private const val ASKED_PREFS = "ayuvo_manual_vitals"
