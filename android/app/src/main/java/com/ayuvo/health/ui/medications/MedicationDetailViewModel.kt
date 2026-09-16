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
    val actionError: String? = null
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
        _ui.update {
            it.copy(
                loading = false, medication = med, schedule = schedule, recentLogs = logs,
                adherence = adherence, relatedRecord = record, relatedRecordMissing = missing
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
    }
}
