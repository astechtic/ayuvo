package com.ayuvo.health.ui.medications

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.ayuvo.health.AppContainer
import com.ayuvo.health.medications.model.DoseLog
import com.ayuvo.health.medications.model.DoseLogCursor
import com.ayuvo.health.medications.model.Medication
import com.ayuvo.health.medications.model.MedicationFilter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class MedicationHistoryUiState(
    val loading: Boolean = true,
    val logs: List<DoseLog> = emptyList(),
    val medications: Map<String, Medication> = emptyMap(),
    val canLoadMore: Boolean = false,
    val medication: Medication? = null
)

/** Dose history (docs/medications.md §9): keyset pages of 60, all medicines or one. */
class MedicationHistoryViewModel(private val container: AppContainer, private val medicationId: String?) : ViewModel() {
    private val store get() = container.medicationsStore
    private val _ui = MutableStateFlow(MedicationHistoryUiState())
    val ui: StateFlow<MedicationHistoryUiState> = _ui.asStateFlow()
    private val pageLock = Mutex()

    init {
        viewModelScope.launch { store.revision.collectLatest { reloadFirstPage() } }
    }

    private suspend fun reloadFirstPage() = pageLock.withLock {
        val meds = store.list(MedicationFilter()).associateBy { it.id }
        val logs = store.history(medicationId, null, PAGE)
        _ui.update {
            it.copy(
                loading = false, logs = logs, medications = meds, canLoadMore = logs.size == PAGE,
                medication = medicationId?.let(meds::get)
            )
        }
    }

    fun loadMore() {
        viewModelScope.launch {
            pageLock.withLock {
                val state = _ui.value
                val last = state.logs.lastOrNull() ?: return@withLock
                if (!state.canLoadMore) return@withLock
                val more = store.history(medicationId, DoseLogCursor.after(last), PAGE)
                _ui.update { it.copy(logs = it.logs + more, canLoadMore = more.size == PAGE) }
            }
        }
    }

    class Factory(private val container: AppContainer, private val medicationId: String?) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = MedicationHistoryViewModel(container, medicationId) as T
    }

    private companion object {
        const val PAGE = 60
    }
}
