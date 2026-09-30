package com.ayuvo.health.ui.medications

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.ayuvo.health.AppContainer
import com.ayuvo.health.medications.model.AdherenceSummary
import com.ayuvo.health.medications.model.DoseLog
import com.ayuvo.health.medications.model.LifecycleAction
import com.ayuvo.health.medications.model.Medication
import com.ayuvo.health.medications.model.MedicationSchedule
import com.ayuvo.health.nutrients.MedicationNutrientRow
import com.ayuvo.health.nutrients.NutrientTotals
import com.ayuvo.health.nutrients.SupplementSnapshot
import com.ayuvo.health.records.model.HealthRecord
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.ZoneId

data class MedicationDetailUiState(
    val loading: Boolean = true,
    val medication: Medication? = null,
    /** The open row, or the latest closed generation while paused. */
    val schedule: MedicationSchedule? = null,
    val recentLogs: List<DoseLog> = emptyList(),
    val adherence: AdherenceSummary? = null,
    val relatedRecord: HealthRecord? = null,
    /** True when the medication links a record that no longer exists. */
    val relatedRecordMissing: Boolean = false,
    val deleted: Boolean = false,
    val actionError: String? = null,
    /** Supplement nutrients per dose unit (schema v2, §21), sorted by key. */
    val nutrients: List<MedicationNutrientRow> = emptyList(),
    /** Today's contribution per nutrient key from this medication's taken doses. */
    val todayNutrients: Map<String, Double> = emptyMap(),
    /** `meds_adherence` over the last 30 days (docs/intake-metrics.md §3); null for PRN or without the intake config. */
    val intakeAdherence: com.ayuvo.health.data.intake.MedsAdherenceResult? = null,
    /** `HH:mm` the single reminder time can move to, when the engine suggests one. */
    val suggestedReminder: String? = null,
    /** Nutrient key → `supplement_daily` of this regimen (dose averaged over its interval, against the upper limit). */
    val nutrientRegimens: Map<String, com.ayuvo.health.data.intake.SupplementDailyResult> = emptyMap()
)

/** Medication detail (docs/medications.md): info, schedule, adherence, recent history, lifecycle. */
class MedicationDetailViewModel(private val container: AppContainer, private val medicationId: String) : ViewModel() {
    private val store get() = container.medicationsStore
    private val zone: String get() = ZoneId.systemDefault().id

    private val _ui = MutableStateFlow(MedicationDetailUiState())
    val ui: StateFlow<MedicationDetailUiState> = _ui.asStateFlow()

    init {
        viewModelScope.launch { store.revision.collectLatest { reload() } }
    }

    private suspend fun reload() {
        val med = store.medication(medicationId)
        if (med == null) {
            _ui.update { it.copy(loading = false, medication = null) }
            return
        }
        val schedule = store.schedules(med.id, openOnly = true).firstOrNull()
            ?: store.schedules(med.id, openOnly = false).maxByOrNull { it.activeUntilMs ?: Long.MAX_VALUE }
        val logs = store.history(med.id, null, RECENT_LIMIT)
        val adherence = if (med.isPrn) null else store.adherence(med.id, System.currentTimeMillis(), zone)
        var record: HealthRecord? = null
        var missing = false
        val recordId = med.relatedRecordId
        if (recordId != null) {
            record = if (container.recordsDatabaseExists()) runCatching { container.recordsStore.record(recordId) }.getOrNull() else null
            missing = record == null
        }
        val nutrients = store.nutrients(med.id)
        val interval = com.ayuvo.health.nutrients.SupplementAveraging.intervalDays(schedule)
        val today = if (nutrients.isEmpty()) emptyMap() else {
            val z = ZoneId.systemDefault()
            val day = java.time.LocalDate.now(z)
            val doses = store.supplementDoses().filter { it.medicationId == med.id }
            NutrientTotals(emptyList(), SupplementSnapshot(nutrients, doses, intervalDays = mapOf(med.id to interval)), z).supplementEntries(day)
                .groupBy { it.nutrientKey }.mapValues { (_, list) -> list.sumOf { e -> e.value } }
        }
        val intakeCfg = runCatching { container.intakeConfig }.getOrNull()
        val now = System.currentTimeMillis()
        val intakeAdherence = if (med.isPrn || intakeCfg == null) null else {
            val logs30 = store.history(med.id, null, INSIGHT_LIMIT)
            com.ayuvo.health.medications.logic.MedicationIntakeInsights.compute(logs30, now, zone, intakeCfg)
        }
        val suggested = intakeAdherence?.suggestedClockMin?.let { min ->
            com.ayuvo.health.medications.logic.MedicationIntakeInsights.movedSchedule(schedule, min, now)?.times?.single()
        }
        val regimens = nutrients.associate { row ->
            row.nutrientKey to com.ayuvo.health.nutrients.SupplementAveraging.regimen(row.nutrientKey, row.amountPerUnit * med.doseQuantity, interval, intakeCfg)
        }
        _ui.update {
            it.copy(
                loading = false, medication = med, schedule = schedule, recentLogs = logs,
                adherence = adherence, relatedRecord = record, relatedRecordMissing = missing,
                nutrients = nutrients, todayNutrients = today,
                intakeAdherence = intakeAdherence, suggestedReminder = suggested, nutrientRegimens = regimens
            )
        }
    }

    /** pause / resume / stop (§12); the store reports an error code when the transition is invalid. */
    fun lifecycle(action: LifecycleAction) {
        viewModelScope.launch {
            val error = store.setStatus(medicationId, action, System.currentTimeMillis())
            _ui.update { it.copy(actionError = error) }
        }
    }

    fun setReminderEnabled(enabled: Boolean) {
        viewModelScope.launch {
            val error = store.setReminderEnabled(medicationId, enabled, System.currentTimeMillis())
            _ui.update { it.copy(actionError = error) }
        }
    }

    fun logPrn(takenAtMs: Long?, doseQuantity: Double?, note: String?) {
        viewModelScope.launch {
            val r = store.logPrn(medicationId, System.currentTimeMillis(), takenAtMs, doseQuantity, note)
            _ui.update { it.copy(actionError = r.error) }
        }
    }

    /** "Move reminder to HH:MM?": replaces the single reminder time (a new schedule generation, docs/medications.md §12). */
    fun moveReminder() {
        viewModelScope.launch {
            val med = _ui.value.medication ?: return@launch
            val min = _ui.value.intakeAdherence?.suggestedClockMin ?: return@launch
            val now = System.currentTimeMillis()
            val moved = com.ayuvo.health.medications.logic.MedicationIntakeInsights.movedSchedule(_ui.value.schedule, min, now) ?: return@launch
            val error = runCatching {
                store.update(med.copy(updatedMs = now), moved.copy(id = java.util.UUID.randomUUID().toString().lowercase(), createdMs = now), now)
            }.getOrElse { "store_error" }
            _ui.update { it.copy(actionError = error) }
        }
    }

    fun delete(onDeleted: () -> Unit) {
        viewModelScope.launch {
            store.delete(medicationId)
            _ui.update { it.copy(deleted = true) }
            onDeleted()
        }
    }

    fun clearError() {
        _ui.update { it.copy(actionError = null) }
    }

    class Factory(private val container: AppContainer, private val medicationId: String) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = MedicationDetailViewModel(container, medicationId) as T
    }

    private companion object {
        const val RECENT_LIMIT = 10
        /** Enough history for 30 days of several doses a day. */
        const val INSIGHT_LIMIT = 400
    }
}
