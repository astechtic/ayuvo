package com.ayuvo.health.ui.medications

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ayuvo.health.AppContainer
import com.ayuvo.health.R
import com.ayuvo.health.medications.logic.DraftValidation
import com.ayuvo.health.medications.logic.FrequencyHint
import com.ayuvo.health.medications.logic.MedicationJson
import com.ayuvo.health.medications.logic.MedicationLocalTime
import com.ayuvo.health.medications.model.MedicationDraft
import com.ayuvo.health.medications.model.ValidationError
import com.ayuvo.health.records.model.FieldKey
import com.ayuvo.health.records.model.FieldState
import com.ayuvo.health.records.model.HealthRecord
import com.ayuvo.health.ui.components.GlassPrimaryButton
import com.ayuvo.health.ui.components.GlassSurface
import com.ayuvo.health.ui.components.GlassTextButton
import com.ayuvo.health.ui.navigation.BottomNavScrollPadding
import com.ayuvo.health.ui.records.RecordFormat
import com.ayuvo.health.ui.theme.AppColors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import java.time.LocalDate
import java.util.UUID

/** One extracted medicine the user can tick, edit and confirm (docs/medications.md §13). */
data class MedicationCandidate(
    val key: String,
    val draft: MedicationDraft,
    val selected: Boolean = true,
    /** `frequency_hint.notes`, shown as "defaults applied" hints. */
    val notes: List<String> = emptyList(),
    val errors: List<ValidationError> = emptyList()
)

data class ImportFromRecordUiState(
    val loading: Boolean = true,
    val record: HealthRecord? = null,
    val processing: Boolean = false,
    val candidates: List<MedicationCandidate> = emptyList(),
    val saving: Boolean = false,
    val created: Int? = null
)

/** Reads a record's `medication` fields into pre-filled drafts; nothing is created until Confirm. */
class ImportFromRecordViewModel(private val container: AppContainer, private val recordId: String) : ViewModel() {
    private val _ui = MutableStateFlow(ImportFromRecordUiState())
    val ui: StateFlow<ImportFromRecordUiState> = _ui.asStateFlow()

    init { reload() }

    fun reload() {
        viewModelScope.launch {
            if (!container.recordsDatabaseExists()) {
                _ui.update { it.copy(loading = false, record = null) }
                return@launch
            }
            val record = runCatching { container.recordsStore.record(recordId) }.getOrNull()
            if (record == null) {
                _ui.update { it.copy(loading = false, record = null) }
                return@launch
            }
            val fields = runCatching { container.recordsStore.fields(recordId) }.getOrDefault(emptyList())
                .filter { it.key == FieldKey.MEDICATION && it.state != FieldState.REJECTED }
            val today = LocalDate.now()
            val todayText = MedicationLocalTime.formatDate(today)
            val candidates = fields.map { field ->
                val json = field.valueJson?.let { runCatching { MedicationJson.json.parseToJsonElement(it) as? JsonObject }.getOrNull() }
                val hint = if (json != null) FrequencyHint.fromRecordField(json)
                else FrequencyHint.fromValues(name = field.valueText, strength = null, form = null, dose = null, frequency = null, duration = null, instructions = null)
                val draft = MedicationDraft.fromHint(hint, todayText, recordId) { days ->
                    MedicationLocalTime.formatDate(today.plusDays((days - 1).coerceAtLeast(0).toLong()))
                }
                MedicationCandidate(key = field.id, draft = draft, notes = hint.notes)
            }
            _ui.update { it.copy(loading = false, record = record, processing = record.isProcessing && candidates.isEmpty(), candidates = candidates) }
        }
    }

    fun toggle(key: String) {
        _ui.update { s -> s.copy(candidates = s.candidates.map { if (it.key == key) it.copy(selected = !it.selected) else it }) }
    }

    fun edit(key: String, draft: MedicationDraft) {
        _ui.update { s -> s.copy(candidates = s.candidates.map { if (it.key == key) it.copy(draft = draft, errors = emptyList(), selected = true) else it }) }
    }

    /** Validates every ticked candidate, creates them all, and reports how many were added. */
    fun confirm() {
        viewModelScope.launch {
            val chosen = _ui.value.candidates.filter { it.selected }
            val validated = chosen.map { it.copy(errors = DraftValidation.validate(it.draft.toValidationJson())) }
            if (validated.any { it.errors.isNotEmpty() }) {
                _ui.update { s -> s.copy(candidates = s.candidates.map { c -> validated.firstOrNull { it.key == c.key } ?: c }) }
                return@launch
            }
            _ui.update { it.copy(saving = true) }
            var created = 0
            val now = System.currentTimeMillis()
            for (c in chosen) {
                val id = UUID.randomUUID().toString().lowercase()
                val medication = c.draft.toMedication(id, now)
                val schedule = c.draft.toSchedule(UUID.randomUUID().toString().lowercase(), id, now)
                if (runCatching { container.medicationsStore.create(medication, schedule) }.isSuccess) created++
            }
            _ui.update { it.copy(saving = false, created = created) }
        }
    }

    class Factory(private val container: AppContainer, private val recordId: String) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = ImportFromRecordViewModel(container, recordId) as T
    }
}

/** "Add from prescription": review the extracted medicines, edit any, confirm (docs §13). */
@Composable
fun ImportFromRecordScreen(
    container: AppContainer,
    recordId: String,
    onBack: () -> Unit,
    onDone: (created: Int) -> Unit,
    onAddManually: (String) -> Unit
) {
    val vm: ImportFromRecordViewModel = viewModel(key = "med-import-$recordId", factory = ImportFromRecordViewModel.Factory(container, recordId))
    val ui by vm.ui.collectAsState()
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(ui.created) { ui.created?.let(onDone) }
    val selectedCount = ui.candidates.count { it.selected }
    Column(Modifier.fillMaxSize()) {
        MedicationTopBar(title = stringResource(R.string.medications_import_title), onBack = onBack)
        when {
            ui.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            ui.record == null -> CenteredMessage(stringResource(R.string.medications_record_missing))
            ui.processing -> Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.medications_import_processing), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
                GlassTextButton(text = stringResource(R.string.action_retry), onClick = vm::reload)
                GlassTextButton(text = stringResource(R.string.medications_import_add_manually), onClick = { onAddManually(recordId) })
            }
            ui.candidates.isEmpty() -> Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.medications_import_none), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
                GlassPrimaryButton(text = stringResource(R.string.medications_import_add_manually), onClick = { onAddManually(recordId) })
            }
            else -> LazyColumn(
                Modifier.fillMaxSize().testTag("medications.import.$recordId"),
                contentPadding = PaddingValues(start = 16.dp, top = 4.dp, end = 16.dp, bottom = BottomNavScrollPadding),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                item(key = "record") {
                    val record = ui.record ?: return@item
                    Text("${record.title} · ${RecordFormat.displayDate(record)}", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 6.dp))
                    Text(
                        stringResource(R.string.medications_import_check_note),
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp)
                    )
                }
                itemsIndexed(ui.candidates, key = { _, c -> c.key }) { _, candidate ->
                    CandidateCard(candidate = candidate, onToggle = { vm.toggle(candidate.key) }, onEdit = { editing = candidate.key })
                }
                item(key = "confirm") {
                    Spacer(Modifier.height(4.dp))
                    GlassPrimaryButton(
                        text = pluralStringResource(R.plurals.medications_import_confirm, selectedCount, selectedCount),
                        enabled = selectedCount > 0 && !ui.saving,
                        onClick = vm::confirm
                    )
                }
                item(key = "disclaimer") { MedicationsDisclaimer() }
            }
        }
    }
    val editingCandidate = ui.candidates.firstOrNull { it.key == editing }
    if (editingCandidate != null) {
        CandidateEditorDialog(
            container = container,
            candidate = editingCandidate,
            onSave = { draft -> vm.edit(editingCandidate.key, draft); editing = null },
            onDismiss = { editing = null }
        )
    }
}

@Composable
private fun CenteredMessage(text: String) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(text, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f))
    }
}

@Composable
private fun CandidateCard(candidate: MedicationCandidate, onToggle: () -> Unit, onEdit: () -> Unit) {
    val draft = candidate.draft
    GlassSurface(Modifier.fillMaxWidth().clickable(onClick = onToggle), cornerRadius = 20.dp, padding = 12.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = candidate.selected, onCheckedChange = { onToggle() }, colors = CheckboxDefaults.colors(checkedColor = AppColors.Calorie))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    listOfNotNull(draft.name.ifBlank { stringResource(R.string.medications_unknown_medicine) }, draft.strength).joinToString(" "),
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp
                )
                val dose = draft.doseQuantity?.let { doseText(it, draft.doseUnit) } ?: stringResource(R.string.medications_import_dose_missing)
                Text(
                    "$dose · " + candidateScheduleText(draft),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
                )
                if (candidate.notes.any { it.endsWith("_defaulted") || it == "interval_rounded" || it == "uneven_doses" }) {
                    Text(stringResource(R.string.medications_import_defaults_note), fontSize = 12.sp, color = AppColors.Calorie)
                }
                candidate.errors.firstOrNull()?.let { Text(validationText(it.code).orEmpty(), fontSize = 12.sp, color = MaterialTheme.colorScheme.error) }
            }
            Spacer(Modifier.width(6.dp))
            GlassTextButton(text = stringResource(R.string.medications_action_edit), onClick = onEdit)
        }
    }
}

@Composable
private fun candidateScheduleText(draft: MedicationDraft): String {
    if (draft.isPrn) return stringResource(R.string.medications_frequency_prn)
    val schedule = draft.schedule
    val end = draft.endDate?.let { " · " + stringResource(R.string.medications_import_until, MedicationFormat.date(it)) }.orEmpty()
    val context = androidx.compose.ui.platform.LocalContext.current
    return when (schedule.frequency) {
        com.ayuvo.health.medications.model.ScheduleFrequency.INTERVAL ->
            stringResource(R.string.medications_frequency_interval, schedule.intervalHours ?: 0, MedicationFormat.slot(context, schedule.anchorTime ?: "08:00")) + end
        com.ayuvo.health.medications.model.ScheduleFrequency.WEEKLY ->
            stringResource(R.string.medications_frequency_weekly, schedule.days.joinToString(", ") { MedicationFormat.weekday(it) }, schedule.times.joinToString(", ") { MedicationFormat.slot(context, it) }) + end
        else -> stringResource(R.string.medications_frequency_daily, schedule.times.joinToString(", ") { MedicationFormat.slot(context, it) }) + end
    }
}

/** Full-screen editor for one candidate; it never touches the store — Confirm on the list does. */
@Composable
private fun CandidateEditorDialog(
    container: AppContainer,
    candidate: MedicationCandidate,
    onSave: (MedicationDraft) -> Unit,
    onDismiss: () -> Unit
) {
    var draft by remember(candidate.key) { mutableStateOf(candidate.draft) }
    var errors by remember(candidate.key) { mutableStateOf(candidate.errors) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Column(Modifier.fillMaxSize().padding(top = 24.dp), verticalArrangement = Arrangement.Top) {
            GlassSurface(Modifier.fillMaxSize(), cornerRadius = 28.dp, padding = 0.dp) {
                Column(Modifier.fillMaxSize()) {
                    MedicationTopBar(title = stringResource(R.string.medications_edit_title), onBack = onDismiss)
                    Column(
                        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding().padding(horizontal = 12.dp).padding(bottom = 24.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        MedicationEditorForm(
                            draft = draft,
                            errors = errors,
                            onChange = { transform ->
                                draft = transform(draft)
                                if (errors.isNotEmpty()) errors = DraftValidation.validate(draft.toValidationJson())
                            },
                            isEditing = false,
                            container = container,
                            allowRecordLink = false
                        )
                        GlassPrimaryButton(
                            text = stringResource(R.string.action_done),
                            onClick = {
                                val found = DraftValidation.validate(draft.toValidationJson())
                                if (found.isEmpty()) onSave(draft) else errors = found
                            }
                        )
                        GlassTextButton(text = stringResource(R.string.action_cancel), onClick = onDismiss, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        }
    }
}
