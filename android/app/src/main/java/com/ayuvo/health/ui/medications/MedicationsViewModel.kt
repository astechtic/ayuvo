package com.ayuvo.health.ui.medications

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.ayuvo.health.AppContainer
import com.ayuvo.health.BuildConfig
import com.ayuvo.health.medications.data.MedicationsStore
import com.ayuvo.health.medications.export.MedicationsArchive
import com.ayuvo.health.medications.logic.MedicationConstants
import com.ayuvo.health.medications.logic.MedicationLocalTime
import com.ayuvo.health.medications.model.DoseAction
import com.ayuvo.health.medications.model.ImportResult
import com.ayuvo.health.medications.model.Medication
import com.ayuvo.health.medications.model.MedicationFilter
import com.ayuvo.health.medications.model.MedicationStatus
import com.ayuvo.health.medications.model.TimelineItem
import com.ayuvo.health.medications.model.TodayTimeline
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.ZoneId

/** One-shot outcome the Meds home reports in a snackbar. */
sealed class MedicationsEvent {
    data class Message(val text: MedicationsMessage) : MedicationsEvent()
}

/** Snackbar copy keyed by resource so the ViewModel stays free of Context. */
enum class MedicationsMessage { DOSE_TAKEN, DOSE_SKIPPED, DOSE_SNOOZED, DOSE_UNDONE, ACTION_FAILED, PRN_LOGGED, EXPORTED, EXPORT_FAILED, IMPORT_FAILED }

data class MedicationsUiState(
    val loading: Boolean = true,
    val timeline: TodayTimeline? = null,
    /** The "All medicines" list for the current chip + search. */
    val medications: List<Medication> = emptyList(),
    val filter: MedicationFilter = MedicationFilter(status = MedicationStatus.ACTIVE),
    val counts: Map<MedicationStatus, Int> = emptyMap(),
    val exportBusy: Boolean = false,
    /** Set after an import so the screen can show "Imported N medications". */
    val lastImport: ImportResult? = null
) {
    val totalMedications: Int get() = counts.values.sum()
    /** Reminder banners only matter while something is scheduled. */
    val hasScheduledMedication: Boolean
        get() = timeline?.medications?.values?.any { it.isActive && !it.isPrn } == true
}

/**
 * Meds home (docs/medications.md §8): today's timeline plus the filtered medicine list, re-queried
 * on every store revision, chip change or (debounced) search edit — the `RecordsViewModel` shape.
 */
class MedicationsViewModel(private val container: AppContainer) : ViewModel() {
    private val store: MedicationsStore get() = container.medicationsStore
    private val zone: String get() = ZoneId.systemDefault().id

    private val _ui = MutableStateFlow(MedicationsUiState())
    val ui: StateFlow<MedicationsUiState> = _ui.asStateFlow()

    private val _events = MutableSharedFlow<MedicationsEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<MedicationsEvent> = _events.asSharedFlow()

    private val filter = MutableStateFlow(MedicationFilter(status = MedicationStatus.ACTIVE))

    /** The in-app dose sheet's preselected snooze = Settings › Notifications › Default snooze (10/30/60). */
    val defaultSnoozeMinutes: StateFlow<Int> = container.prefs.medicationSnoozeMinutes
        .stateIn(viewModelScope, SharingStarted.Eagerly, MedicationConstants.DEFAULT_SNOOZE_MINUTES)

    init {
        viewModelScope.launch {
            // Materialize missed doses / auto-complete once per screen so history is right even if no
            // planner ran since the app was last open; the UI derives statuses lazily anyway (§7).
            val now = System.currentTimeMillis()
            runCatching { store.materializeMissed(now, zone) }
            runCatching { store.autoComplete(MedicationLocalTime.localDateOf(now, zone), now) }
        }
        viewModelScope.launch {
            @OptIn(FlowPreview::class)
            val queries = filter.debounce { if (it.query.isEmpty()) 0 else 150 }.distinctUntilChanged()
            combine(store.revision, queries) { rev, f -> rev to f }.collectLatest { (_, f) -> reload(f) }
        }
    }

    private suspend fun reload(f: MedicationFilter) {
        val now = System.currentTimeMillis()
        val timeline = runCatching { store.today(now, zone) }.getOrNull()
        val list = runCatching { store.list(f) }.getOrDefault(emptyList())
        val counts = runCatching { store.countByStatus() }.getOrDefault(emptyMap())
        _ui.update { it.copy(loading = false, timeline = timeline, medications = list, filter = f, counts = counts) }
    }

    fun setStatusFilter(status: MedicationStatus?) {
        filter.update { it.copy(status = status) }
    }

    fun setQuery(query: String) {
        filter.update { it.copy(query = query) }
    }

    /** Take / Skip / Snooze / Undo on a timeline row (§11). */
    fun act(item: TimelineItem, action: DoseAction, snoozeMinutes: Int = defaultSnoozeMinutes.value, takenAtMs: Long? = null, note: String? = null) {
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val result = store.act(
                medicationId = item.medicationId,
                scheduleId = item.scheduleId,
                scheduledAtMs = item.scheduledAtMs,
                action = action,
                nowMs = now,
                snoozeMinutes = snoozeMinutes,
                takenAtMs = takenAtMs,
                note = note,
                logId = item.logId
            )
            val message = when {
                !result.ok -> MedicationsMessage.ACTION_FAILED
                action == DoseAction.TAKEN -> MedicationsMessage.DOSE_TAKEN
                action == DoseAction.SKIPPED -> MedicationsMessage.DOSE_SKIPPED
                action == DoseAction.SNOOZED -> MedicationsMessage.DOSE_SNOOZED
                else -> MedicationsMessage.DOSE_UNDONE
            }
            _events.emit(MedicationsEvent.Message(message))
        }
    }

    fun logPrn(medicationId: String, takenAtMs: Long?, doseQuantity: Double?, note: String?) {
        viewModelScope.launch {
            val r = store.logPrn(medicationId, System.currentTimeMillis(), takenAtMs, doseQuantity, note)
            _events.emit(MedicationsEvent.Message(if (r.ok) MedicationsMessage.PRN_LOGGED else MedicationsMessage.ACTION_FAILED))
        }
    }

    /** The `ayuvo-medications.json` bytes for `CreateDocument` (§14). */
    suspend fun exportBytes(): ByteArray? {
        _ui.update { it.copy(exportBusy = true) }
        return try {
            MedicationsArchive.write(store.exportSnapshot(), System.currentTimeMillis(), zone, BuildConfig.VERSION_NAME)
        } catch (_: Exception) {
            null
        } finally {
            _ui.update { it.copy(exportBusy = false) }
        }
    }

    fun reportExport(ok: Boolean) {
        viewModelScope.launch { _events.emit(MedicationsEvent.Message(if (ok) MedicationsMessage.EXPORTED else MedicationsMessage.EXPORT_FAILED)) }
    }

    /** Merges an archive picked with `OpenDocument`; never deletes (§14). */
    fun importBytes(bytes: ByteArray) {
        viewModelScope.launch {
            val archive = runCatching { MedicationsArchive.read(bytes) }.getOrNull()
            if (archive == null) {
                _events.emit(MedicationsEvent.Message(MedicationsMessage.IMPORT_FAILED))
                return@launch
            }
            val result = store.importArchive(archive, System.currentTimeMillis())
            if (result.ok) _ui.update { it.copy(lastImport = result) }
            else _events.emit(MedicationsEvent.Message(MedicationsMessage.IMPORT_FAILED))
        }
    }

    fun consumeImportResult() {
        _ui.update { it.copy(lastImport = null) }
    }

    class Factory(private val container: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = MedicationsViewModel(container) as T
    }
}
