package com.ayuvo.health.ui.records

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.ayuvo.health.AppContainer
import com.ayuvo.health.records.model.HealthRecord
import com.ayuvo.health.records.model.RecordCategory
import com.ayuvo.health.records.model.RecordPage
import com.ayuvo.health.records.model.RecordPatch
import com.ayuvo.health.records.model.RecordTag
import com.ayuvo.health.records.model.RecordType
import com.ayuvo.health.records.ai.RecordsAiOptions
import com.ayuvo.health.records.data.RecordIntelligence
import com.ayuvo.health.records.ingest.DuplicateMatch
import com.ayuvo.health.records.model.DuplicateCandidate
import com.ayuvo.health.records.model.DuplicateResolution
import com.ayuvo.health.records.model.FieldState
import com.ayuvo.health.records.model.ProcessingStage
import com.ayuvo.health.records.model.RecordField
import com.ayuvo.health.records.model.ReviewStatus
import com.ayuvo.health.records.model.SplitStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate

/** Where the viewer should jump: a page (absolute index) and an optional normalized box. */
data class SourceFocus(val page: Int, val bbox: List<Float>?, val token: Long = System.nanoTime())

data class RecordDetailUiState(
    val loading: Boolean = true,
    val record: HealthRecord? = null,
    val pages: List<RecordPage> = emptyList(),
    val tags: List<RecordTag> = emptyList(),
    val allTags: List<RecordTag> = emptyList(),
    val missing: Boolean = false,
    // Phase 2
    val intelligence: RecordIntelligence? = null,
    val aiOptions: RecordsAiOptions? = null,
    val waitingForAi: Int = 0,
    val focus: SourceFocus? = null,
    /** Pending duplicate pairs resolved into records for the sheet. */
    val duplicateMatches: List<Pair<DuplicateCandidate, DuplicateMatch>> = emptyList(),
    val reviewItems: List<ReviewItem> = emptyList()
)

class RecordDetailViewModel(
    private val container: AppContainer,
    private val recordId: String
) : ViewModel() {
    private val store get() = container.recordsStore

    private val _ui = MutableStateFlow(RecordDetailUiState())
    val ui: StateFlow<RecordDetailUiState> = _ui.asStateFlow()

    init {
        viewModelScope.launch {
            store.revision.collectLatest { reload() }
        }
        viewModelScope.launch {
            container.prefs.healthRecordsAiMode.collect { refreshAiOptions() }
        }
    }

    private suspend fun reload() {
        runCatching {
            val record = store.record(recordId)
            if (record == null) {
                _ui.update { it.copy(loading = false, record = null, missing = true) }
                return
            }
            // Page text is only needed on screen for text records; boxes load on a source jump.
            val pages = if (record.fileType == com.ayuvo.health.records.model.RecordFileType.TEXT) store.pages(recordId) else emptyList()
            val tags = store.tags(recordId)
            val allTags = store.allTags()
            val intelligence = store.intelligence(recordId)
            val waiting = store.awaitingConsent().size
            val matches = intelligence?.duplicates.orEmpty().mapNotNull { candidate ->
                val newer = store.record(candidate.recordId) ?: return@mapNotNull null
                val older = store.record(candidate.existingId) ?: return@mapNotNull null
                candidate to DuplicateMatch(newer, older)
            }
            val review = intelligence?.let { ReviewSelection.items(it.fields) }.orEmpty()
            _ui.update {
                it.copy(
                    loading = false, record = record, pages = pages, tags = tags, allTags = allTags, missing = false,
                    intelligence = intelligence, waitingForAi = waiting, duplicateMatches = matches, reviewItems = review
                )
            }
        }.onFailure { _ui.update { it.copy(loading = false) } }
    }

    private fun refreshAiOptions() {
        viewModelScope.launch {
            runCatching { container.recordsAiResolver.options() }.onSuccess { o -> _ui.update { it.copy(aiOptions = o) } }
        }
    }

    private val typeLabel: (RecordType) -> String? = { type -> container.appContext.getString(type.labelRes()) }

    // -- Review ---------------------------------------------------------------

    fun confirmField(fieldId: String) = launch { store.setFieldState(fieldId, FieldState.CONFIRMED, typeLabel = typeLabel); reviewChanged() }
    fun rejectField(fieldId: String) = launch { store.setFieldState(fieldId, FieldState.REJECTED, typeLabel = typeLabel); reviewChanged() }
    fun editField(fieldId: String, value: String) = launch { store.setFieldState(fieldId, FieldState.USER, editedValue = value, typeLabel = typeLabel); reviewChanged() }
    fun addField(key: String, value: String) = launch { store.addUserField(recordId, key, value, typeLabel); reviewChanged() }

    /** "Confirm all": every item shown in the sheet is confirmed and the record is marked reviewed. */
    fun confirmAll() = launch {
        val items = _ui.value.reviewItems
        for (item in items) {
            val field = item.field
            if (field.state == FieldState.SUGGESTED && !item.conflict) store.setFieldState(field.id, FieldState.CONFIRMED, typeLabel = typeLabel)
        }
        store.setReviewStatus(listOf(recordId), ReviewStatus.REVIEWED)
    }

    private suspend fun reviewChanged() {
        container.recordsPipeline.evaluateReview(recordId)
        if (ReviewSelection.items(store.fields(recordId)).isEmpty() && _ui.value.record?.reviewStatus == ReviewStatus.NEEDS_REVIEW) {
            val pending = store.intelligence(recordId)
            if (pending != null && pending.duplicates.isEmpty() && pending.split?.status != SplitStatus.PENDING) {
                store.setReviewStatus(listOf(recordId), ReviewStatus.REVIEWED)
            }
        }
    }

    fun dismissHighlight(id: String) = launch { store.dismissHighlight(id) }

    // -- AI consent (§16) -------------------------------------------------------

    /** [mode] is `local`, `cloud` or `off` ("Not now"); applies to this record only. */
    fun decideAi(mode: String) = launch {
        val job = store.job(recordId) ?: com.ayuvo.health.records.model.ProcessingJob(recordId = recordId, stage = ProcessingStage.AI)
        store.saveJob(job.copy(stage = ProcessingStage.AI, requestedMode = mode, awaitingConsent = false, attempts = 0, nextAttemptMs = 0, updatedMs = System.currentTimeMillis()))
        if (mode != "off") store.setStatus(recordId, com.ayuvo.health.records.model.ProcessingStatus.QUEUED, keepError = true)
        container.recordsQueue.schedule(listOf(recordId))
    }

    fun decideAiForAll(mode: String) = launch {
        val ids = store.awaitingConsent()
        for (id in ids) {
            store.job(id)?.let { store.saveJob(it.copy(requestedMode = mode, awaitingConsent = false, attempts = 0, nextAttemptMs = 0)) }
        }
        container.recordsQueue.schedule(ids)
    }

    fun reprocess() = launch { container.recordsQueue.enqueue(listOf(recordId)) }

    // -- Source jump ------------------------------------------------------------

    fun focusField(field: RecordField) {
        val page = field.sourcePage ?: return
        viewModelScope.launch {
            val bbox = SourceBoxes.parse(field.sourceBbox) ?: runCatching {
                SourceBoxes.locate(store.pages(recordId).firstOrNull { it.pageIndex == page }?.blocksJson, field.evidence ?: field.valueText)
            }.getOrNull()
            _ui.update { it.copy(focus = SourceFocus(page, bbox)) }
        }
    }

    fun focusPage(page: Int?) {
        if (page == null) return
        _ui.update { it.copy(focus = SourceFocus(page, null)) }
    }

    // -- Duplicates & splits ----------------------------------------------------

    fun keepBoth(candidate: DuplicateCandidate) = launch { store.resolveDuplicate(candidate.recordId, candidate.existingId, DuplicateResolution.KEEP_BOTH); reviewChanged() }
    fun replaceExisting(candidate: DuplicateCandidate, onDone: () -> Unit) =
        container.recordsImports.replace(candidate.recordId, candidate.existingId, onDone)
    fun mergeIntoExisting(candidate: DuplicateCandidate, onDone: () -> Unit) =
        container.recordsImports.merge(candidate.recordId, candidate.existingId, onDone)
    fun cancelNew(candidate: DuplicateCandidate, onDone: () -> Unit) {
        viewModelScope.launch {
            runCatching { container.deleteRecords(listOf(candidate.recordId)) }
            onDone()
        }
    }

    private fun launch(block: suspend () -> Unit) {
        viewModelScope.launch { runCatching { block() } }
    }

    private fun patch(patch: RecordPatch) {
        viewModelScope.launch { runCatching { store.update(listOf(recordId), patch) } }
    }

    fun rename(title: String) {
        if (title.isNotBlank()) patch(RecordPatch(title = title.trim()))
    }

    fun setDate(date: LocalDate?) {
        patch(if (date == null) RecordPatch(clearDocumentDate = true) else RecordPatch(documentDate = date.toString()))
    }

    /** Changing the type also moves the record to that type's default category. */
    fun setType(type: RecordType) = patch(RecordPatch(recordType = type, category = type.defaultCategory))
    fun setCategory(category: RecordCategory) = patch(RecordPatch(category = category))
    fun setNotes(notes: String) = patch(RecordPatch(notes = notes))
    fun toggleFavorite() = _ui.value.record?.let { patch(RecordPatch(favorite = !it.favorite)) }
    fun toggleArchived() = _ui.value.record?.let { patch(RecordPatch(archived = !it.archived)) }

    fun addTag(name: String) {
        viewModelScope.launch { runCatching { store.addTag(recordId, name) } }
    }

    fun removeTag(tag: RecordTag) {
        viewModelScope.launch { runCatching { store.removeTag(recordId, tag.id) } }
    }

    fun delete(onDone: () -> Unit) {
        viewModelScope.launch {
            runCatching { container.deleteRecords(listOf(recordId)) }
            onDone()
        }
    }

    fun originalFile(): File? = container.recordFiles.resolve(_ui.value.record?.filePath)?.takeIf { it.isFile }

    /**
     * Copies the original into `cacheDir/records-share/` (FileProvider `records_share`) under a
     * readable name. The originals directory itself is never exposed through the provider.
     */
    suspend fun shareableUri(context: Context): Pair<Uri, String>? = withContext(Dispatchers.IO) {
        val record = _ui.value.record ?: return@withContext null
        val source = originalFile() ?: return@withContext null
        val ext = source.extension.ifEmpty { "bin" }
        val base = record.title.replace(Regex("[^\\p{L}\\p{N} ._()-]+"), " ").trim().take(80).ifEmpty { "record" }
        val target = File(container.recordFiles.freshShareDir(), "$base.$ext")
        runCatching { source.copyTo(target, overwrite = true) }.getOrNull() ?: return@withContext null
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", target)
        uri to record.mimeType
    }

    fun shareIntent(uri: Uri, mimeType: String): Intent = Intent(Intent.ACTION_SEND).apply {
        type = mimeType
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    fun viewIntent(uri: Uri, mimeType: String): Intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, mimeType)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    class Factory(private val container: AppContainer, private val recordId: String) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = RecordDetailViewModel(container, recordId) as T
    }
}
