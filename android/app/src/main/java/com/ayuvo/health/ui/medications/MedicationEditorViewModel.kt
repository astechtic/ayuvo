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
import com.ayuvo.health.medications.model.NutrientInputRow
import com.ayuvo.health.models.AIProvider
import com.ayuvo.health.nutrients.LabelItem
import com.ayuvo.health.nutrients.LabelParseResult
import com.ayuvo.health.nutrients.NutrientLabel
import com.ayuvo.health.nutrients.SupplementLabelAi
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
    val photoBusy: Boolean = false,
    /** "Get nutrients with AI" (docs/nutrients.md §7). */
    val aiBusy: Boolean = false,
    /** Validated AI items awaiting review; nothing is saved until the user confirms. */
    val aiReview: LabelParseResult? = null,
    /** `setup` (no AI configured), `empty` (nothing recognised), or a provider message. */
    val aiError: String? = null
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
                val nutrients = med?.let { store.nutrients(it.id) }.orEmpty().map(NutrientInputRow::fromStored)
                _ui.update {
                    if (med == null) it.copy(loading = false)
                    else it.copy(loading = false, draft = MedicationDraft.from(med, schedule).copy(nutrients = nutrients), existing = med, existingSchedule = schedule)
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
        if (result == null && (existing != null || draft.nutrients.isNotEmpty())) {
            // Rows are replaced as a set; totals are recomputed, so past doses follow the edit (§21).
            runCatching { store.setNutrients(targetId, draft.nutrientRows(targetId), now) }
        }
        _ui.update { it.copy(saving = false, saveError = result) }
        if (result == null) photoStoredForNew = false
        return if (result == null) targetId else null
    }

    /**
     * "Get nutrients with AI" from a label photo (image role) or the name and strength (text role),
     * on exactly the resolved route (no fallback). The validated items open a review sheet.
     */
    fun extractNutrients(photo: ByteArray?) {
        val draft = _ui.value.draft
        viewModelScope.launch {
            _ui.update { it.copy(aiBusy = true, aiError = null, aiReview = null) }
            val prompts = NutrientLabel.prompts
            val route = runCatching { container.foodAnalysis.supplementLabelRoute(photo = photo != null) }.getOrNull()
            if (route == null || prompts == null) {
                _ui.update { it.copy(aiBusy = false, aiError = AI_SETUP) }
                return@launch
            }
            val local = route.provider == AIProvider.LOCAL_GEMMA
            val prompt = SupplementLabelAi.prompt(prompts, local, photo != null, draft.name, draft.strength.orEmpty(), draft.doseUnit.raw)
            val result = try {
                val text = container.foodAnalysis.callSupplementLabelAi(route, prompt, photo, SupplementLabelAi.maxTokens(local, route.contextTokens))
                NutrientLabel.parse(text)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                _ui.update { it.copy(aiBusy = false, aiError = e.message ?: AI_FAILED) }
                return@launch
            }
            _ui.update {
                when {
                    !result.ok -> it.copy(aiBusy = false, aiError = AI_FAILED)
                    result.items.isEmpty() && result.rejected.isEmpty() -> it.copy(aiBusy = false, aiError = AI_EMPTY)
                    else -> it.copy(aiBusy = false, aiReview = result)
                }
            }
        }
    }

    /** Adds the confirmed items as form rows (replacing a row of the same nutrient); not saved yet. */
    fun confirmAiItems(items: List<LabelItem>) {
        update { d ->
            val keys = items.map { it.key }.toSet()
            d.copy(nutrients = d.nutrients.filterNot { it.key in keys } + items.map {
                NutrientInputRow(key = it.key, amount = NutrientInputRow.plain(it.amount), unit = it.unit)
            })
        }
        _ui.update { it.copy(aiReview = null) }
    }

    fun dismissAi() {
        _ui.update { it.copy(aiReview = null, aiError = null) }
    }

    /** Cancelling a brand-new medication must not leave its photo behind. */
    fun discard() {
        if (photoStoredForNew) {
            photos.delete(targetId)
            photoStoredForNew = false
        }
    }

    companion object {
        const val AI_SETUP = "setup"
        const val AI_EMPTY = "empty"
        const val AI_FAILED = "failed"
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
