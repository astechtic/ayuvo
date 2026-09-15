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

data class RecordDetailUiState(
    val loading: Boolean = true,
    val record: HealthRecord? = null,
    val pages: List<RecordPage> = emptyList(),
    val tags: List<RecordTag> = emptyList(),
    val allTags: List<RecordTag> = emptyList(),
    val missing: Boolean = false
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
    }

    private suspend fun reload() {
        runCatching {
            val record = store.record(recordId)
            if (record == null) {
                _ui.update { it.copy(loading = false, record = null, missing = true) }
                return
            }
            val pages = if (record.fileType == com.ayuvo.health.records.model.RecordFileType.TEXT) store.pages(recordId) else emptyList()
            val tags = store.tags(recordId)
            val allTags = store.allTags()
            _ui.update { it.copy(loading = false, record = record, pages = pages, tags = tags, allTags = allTags, missing = false) }
        }.onFailure { _ui.update { it.copy(loading = false) } }
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
            runCatching { store.delete(listOf(recordId)) }
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
