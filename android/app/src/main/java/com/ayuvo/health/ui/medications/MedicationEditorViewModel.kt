package com.ayuvo.health.ui.medications

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.ayuvo.health.AppContainer
import com.ayuvo.health.medications.logic.DraftValidation
import com.ayuvo.health.medications.logic.MedicationLocalTime
import com.ayuvo.health.medications.model.Medication
import com.ayuvo.health.medications.model.MedicationDraft
import com.ayuvo.health.medications.model.MedicationSchedule
import com.ayuvo.health.medications.model.ValidationError
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.util.UUID

data class MedicationEditorUiState(
    val loading: Boolean = true,
    val draft: MedicationDraft = MedicationDraft(startDate = MedicationLocalTime.formatDate(LocalDate.now())),
    val errors: List<ValidationError> = emptyList(),
    /** Set when editing; null when adding. */
    val existing: Medication? = null,
    val existingSchedule: MedicationSchedule? = null,
    val saving: Boolean = false,
    /** Reference error code from the store (`invalid_transition`, …) when a save is refused. */
    val saveError: String? = null,
    val photoBusy: Boolean = false
) {
    val isEditing: Boolean get() = existing != null
    fun error(field: String): String? = errors.firstOrNull { it.field == field }?.code
}

/**
 * Add / Edit form (docs/medications.md §15). The medication id is fixed up front so a photo can be
 * stored before the row exists; cancelling a new medication removes that photo again.
 */
class MedicationEditorViewModel(
    private val container: AppContainer,
    private val medicationId: String?,
    private val relatedRecordId: String?,
    initialDraft: MedicationDraft?
) : ViewModel() {
    private val store get() = container.medicationsStore
    private val photos get() = container.medicationPhotos

    val targetId: String = medicationId ?: UUID.randomUUID().toString().lowercase()
    private var photoStoredForNew = false

    private val _ui = MutableStateFlow(
        MedicationEditorUiState(
            loading = medicationId != null,
            draft = initialDraft ?: MedicationDraft(
                startDate = MedicationLocalTime.formatDate(LocalDate.now()),
                relatedRecordId = relatedRecordId
            )
        )
    )
    val ui: StateFlow<MedicationEditorUiState> = _ui.asStateFlow()

    init {
        if (medicationId != null) {
            viewModelScope.launch {
                val med = store.medication(medicationId)
                val schedule = med?.let { store.schedules(it.id, openOnly = true).firstOrNull() }
                    // A paused medication keeps its rule as the latest closed generation (§12).
                    ?: med?.let { store.schedules(it.id, openOnly = false).maxByOrNull { s -> s.activeUntilMs ?: Long.MAX_VALUE } }
                _ui.update {
                    if (med == null) it.copy(loading = false)
                    else it.copy(loading = false, draft = MedicationDraft.from(med, schedule), existing = med, existingSchedule = schedule)
                }
            }
        }
    }

    fun update(transform: (MedicationDraft) -> MedicationDraft) {
        _ui.update { state ->
            val next = transform(state.draft)
            // Re-validate only the fields that already showed an error, so typing clears them.
            val errors = if (state.errors.isEmpty()) emptyList() else DraftValidation.validate(next.toValidationJson())
            state.copy(draft = next, errors = errors, saveError = null)
        }
    }

    fun setPhoto(uri: Uri) {
        viewModelScope.launch {
            _ui.update { it.copy(photoBusy = true) }
            val path = photos.save(targetId, uri)
            if (path != null && medicationId == null) photoStoredForNew = true
            _ui.update { it.copy(photoBusy = false, draft = if (path != null) it.draft.copy(photoPath = path) else it.draft) }
        }
    }

    fun setPhoto(bytes: ByteArray) {
        viewModelScope.launch {
            _ui.update { it.copy(photoBusy = true) }
            val path = photos.save(targetId, bytes)
            if (path != null && medicationId == null) photoStoredForNew = true
            _ui.update { it.copy(photoBusy = false, draft = if (path != null) it.draft.copy(photoPath = path) else it.draft) }
        }
    }

    fun removePhoto() {
        photos.delete(targetId)
        photoStoredForNew = false
        _ui.update { it.copy(draft = it.draft.copy(photoPath = null)) }
    }

    /** Validates, then inserts or updates; returns the medication id on success. */
    suspend fun save(): String? {
        val state = _ui.value
        val errors = DraftValidation.validate(state.draft.toValidationJson())
        if (errors.isNotEmpty()) {
            _ui.update { it.copy(errors = errors) }
            return null
        }
        _ui.update { it.copy(saving = true, saveError = null) }
        val now = System.currentTimeMillis()
        val draft = state.draft
        val existing = state.existing
        val result: String? = if (existing == null) {
            val medication = draft.toMedication(targetId, now)
            val schedule = draft.toSchedule(UUID.randomUUID().toString().lowercase(), targetId, now)
            runCatching { store.create(medication, schedule); null }.getOrElse { "store_error" }
        } else {
            val medication = draft.toMedication(existing.id, now, status = existing.status, createdMs = existing.createdMs)
            val schedule = draft.toSchedule(UUID.randomUUID().toString().lowercase(), existing.id, now)
            runCatching { store.update(medication, schedule, now) }.getOrElse { "store_error" }
        }
        _ui.update { it.copy(saving = false, saveError = result) }
        if (result == null) photoStoredForNew = false
        return if (result == null) targetId else null
    }

    /** Cancelling a brand-new medication must not leave its photo behind. */
    fun discard() {
        if (photoStoredForNew) {
            photos.delete(targetId)
            photoStoredForNew = false
        }
    }

    class Factory(
        private val container: AppContainer,
        private val medicationId: String?,
        private val relatedRecordId: String?,
        private val initialDraft: MedicationDraft? = null
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            MedicationEditorViewModel(container, medicationId, relatedRecordId, initialDraft) as T
    }
}
