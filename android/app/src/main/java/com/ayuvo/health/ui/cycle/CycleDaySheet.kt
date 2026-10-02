package com.ayuvo.health.ui.cycle

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ayuvo.health.R
import com.ayuvo.health.cycle.data.CycleDayLog
import com.ayuvo.health.cycle.data.CycleRepository
import com.ayuvo.health.ui.design.AyuvoColors
import com.ayuvo.health.ui.design.AyuvoShapes
import com.ayuvo.health.ui.design.GroupRow
import com.ayuvo.health.ui.design.InsetGroup
import com.ayuvo.health.ui.design.RowTrailing
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/**
 * Day sheet (docs/cycle-tracking.md §5): period-day toggle, flow, pain 0–10 and locations, symptoms by group, moods,
 * note and the read-only Health Connect extras. Saving writes one day log; a flow from Light up on a non-period day
 * also marks it a period day. Future days show their estimate only.
 */
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun CycleDaySheet(vm: CycleViewModel, day: LocalDate, onDismiss: () -> Unit) {
    val ui by vm.ui.collectAsState()
    val cfg = vm.config
    val context = LocalContext.current
    val existing = ui.logs[day.toString()]
    val future = day.isAfter(ui.today)
    var flow by remember(day) { mutableStateOf(existing?.flow) }
    var pain by remember(day) { mutableStateOf(existing?.pain) }
    val locations = remember(day) { mutableStateListOf<String>().apply { addAll(existing?.painLocations.orEmpty()) } }
    val symptoms = remember(day) { mutableStateListOf<String>().apply { addAll(existing?.symptoms.orEmpty()) } }
    val moods = remember(day) { mutableStateListOf<String>().apply { addAll(existing?.moods.orEmpty()) } }
    var note by remember(day) { mutableStateOf(existing?.note.orEmpty()) }
    var platform by remember(day) { mutableStateOf<CycleRepository.PlatformDay?>(null) }
    LaunchedEffect(day) { platform = vm.platformDay(day) }
    val isPeriod = vm.isPeriodDay(day)
    val isAppPeriod = vm.isAppPeriodDay(day)
    val title = DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL).withLocale(Locale.getDefault()).format(day)
    val months by vm.months.collectAsState()
    val status = ui.ring.firstOrNull { it.day == day.toString() }
        ?: months[java.time.YearMonth.from(day)]?.firstOrNull { it.day == day.toString() }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = AyuvoShapes.Sheet,
        containerColor = AyuvoColors.sheetBackground(),
        // The sheet is its own window, so it re-enables resource ids for uiautomator.
        modifier = Modifier.testTag("cycle.daySheet").semantics { testTagsAsResourceId = true }
    ) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).navigationBarsPadding().padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            Text(title, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            status?.let { s ->
                val hidden = !ui.showFertility && (s.phase == "fertile" || s.phase == "ovulation")
                if (!hidden && s.phase != "unknown") {
                    CycleBadge(CycleText.phase(context, cfg, s.phase) + (s.cycleDay?.let { " · " + stringResource(R.string.cycle_day_n, it) } ?: ""))
                }
            }
            if (future) {
                Text(stringResource(R.string.cycle_future_day), fontSize = 14.sp, color = AyuvoColors.secondaryLabel())
                return@Column
            }
            InsetGroup {
                row {
                    GroupRow(
                        stringResource(R.string.cycle_period_day),
                        subtitle = if (isPeriod && !isAppPeriod) stringResource(R.string.cycle_from_health) else null,
                        trailing = RowTrailing.Toggle(isPeriod, { on -> vm.setPeriodDay(day, on) }, enabled = isAppPeriod || !isPeriod),
                        modifier = Modifier.testTag("cycle.day.periodToggle")
                    )
                }
            }
            Section(stringResource(R.string.cycle_flow)) {
                CycleChipGroup {
                    cfg.flowLevels.forEach { f ->
                        CycleChip(CycleText.flow(context, cfg, f.key), flow == f.key, { flow = if (flow == f.key) null else f.key },
                            modifier = Modifier.testTag("cycle.flow.${f.key}"))
                    }
                }
            }
            Section(stringResource(R.string.cycle_pain)) {
                val value = pain
                val painState = if (value == null) stringResource(R.string.cycle_not_logged) else stringResource(R.string.cycle_pain_value, value)
                Text(
                    if (value == null) stringResource(R.string.cycle_not_logged) else stringResource(R.string.cycle_pain_value, value),
                    fontSize = 14.sp, fontWeight = FontWeight.SemiBold
                )
                Slider(
                    value = (value ?: 0).toFloat(),
                    onValueChange = { pain = it.toInt().coerceIn(0, cfg.limits.painMax) },
                    valueRange = 0f..cfg.limits.painMax.toFloat(),
                    steps = cfg.limits.painMax - 1,
                    colors = SliderDefaults.colors(
                        thumbColor = CycleColors.Period, activeTrackColor = CycleColors.Period,
                        inactiveTrackColor = AyuvoColors.fill(), activeTickColor = Color.White, inactiveTickColor = AyuvoColors.secondaryLabel()
                    ),
                    modifier = Modifier.fillMaxWidth().testTag("cycle.pain.slider").semantics {
                        stateDescription = painState
                    }
                )
                Row(Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.cycle_pain_none), fontSize = 12.sp, color = AyuvoColors.secondaryLabel())
                    Spacer(Modifier.weight(1f))
                    Text(stringResource(R.string.cycle_pain_severe), fontSize = 12.sp, color = AyuvoColors.secondaryLabel())
                }
                if (value != null) {
                    TextButton(onClick = { pain = null; locations.clear() }) { Text(stringResource(R.string.cycle_clear_pain)) }
                    Text(stringResource(R.string.cycle_pain_locations), fontSize = 13.sp, color = AyuvoColors.secondaryLabel())
                    CycleChipGroup {
                        cfg.painLocations.forEach { l ->
                            CycleChip(CycleText.painLocation(context, cfg, l.key), l.key in locations, { toggle(locations, l.key) })
                        }
                    }
                }
            }
            cfg.symptomGroups.forEach { g ->
                Section(stringResource(R.string.cycle_symptoms) + " · " + CycleText.symptomGroup(context, cfg, g.key)) {
                    CycleChipGroup {
                        cfg.symptoms.filter { it.group == g.key }.forEach { s ->
                            CycleChip(CycleText.symptom(context, cfg, s.key), s.key in symptoms, { toggle(symptoms, s.key) },
                                modifier = Modifier.testTag("cycle.symptom.${s.key}"))
                        }
                    }
                }
            }
            Section(stringResource(R.string.cycle_mood)) {
                CycleChipGroup {
                    cfg.moods.forEach { m ->
                        CycleChip(CycleText.mood(context, cfg, m.key), m.key in moods, { toggle(moods, m.key) },
                            modifier = Modifier.testTag("cycle.mood.${m.key}"))
                    }
                }
            }
            Section(stringResource(R.string.cycle_note)) {
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it.take(cfg.limits.noteMaxChars) },
                    placeholder = { Text(stringResource(R.string.cycle_note_hint)) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 96.dp).testTag("cycle.note"),
                    supportingText = { Text("${note.length} / ${cfg.limits.noteMaxChars}") }
                )
            }
            platform?.let { p ->
                InsetGroup(header = stringResource(R.string.cycle_from_health)) {
                    p.ovulationTest?.let { code ->
                        row { GroupRow(stringResource(R.string.cycle_ovulation_test), value = ovulationText(code), trailing = RowTrailing.None) }
                    }
                    p.basalTempC?.let { t ->
                        row { GroupRow(stringResource(R.string.cycle_bbt), value = String.format(Locale.getDefault(), "%.2f °C", t), trailing = RowTrailing.None) }
                    }
                }
            }
            Button(
                onClick = {
                    vm.saveDayLog(
                        (existing ?: CycleDayLog(day.toString())).copy(
                            flow = flow, pain = pain, painLocations = if (pain == null) emptyList() else locations.toList(),
                            symptoms = symptoms.toList(), moods = moods.toList(), note = note.ifBlank { null }
                        )
                    )
                    onDismiss()
                },
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("cycle.day.save"),
                colors = ButtonDefaults.buttonColors(containerColor = CycleColors.Period)
            ) { Text(stringResource(R.string.action_save), fontSize = 17.sp, fontWeight = FontWeight.SemiBold) }
            if (existing != null) {
                TextButton(
                    onClick = { vm.saveDayLog(CycleDayLog(day.toString())); onDismiss() },
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                ) { Text(stringResource(R.string.cycle_clear_day), color = MaterialTheme.colorScheme.error) }
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title.uppercase(Locale.getDefault()), fontSize = 12.sp, color = AyuvoColors.secondaryLabel(), modifier = Modifier.padding(start = 4.dp))
        content()
    }
}

private fun toggle(list: MutableList<String>, key: String) {
    if (key in list) list.remove(key) else list.add(key)
}

@Composable
private fun ovulationText(code: Int): String = when (code) {
    1 -> stringResource(R.string.cycle_ovulation_negative)
    2 -> stringResource(R.string.cycle_ovulation_positive)
    4 -> stringResource(R.string.cycle_ovulation_estrogen)
    else -> stringResource(R.string.cycle_ovulation_indeterminate)
}
