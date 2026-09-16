package com.ayuvo.health.ui.records

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ayuvo.health.AppContainer
import com.ayuvo.health.R
import com.ayuvo.health.records.data.RecordsStorageStats
import com.ayuvo.health.records.ingest.ThumbnailMaker
import com.ayuvo.health.records.model.DuplicateCandidate
import com.ayuvo.health.records.model.ProcessingStage
import com.ayuvo.health.records.model.RecordProcessingUpdate
import com.ayuvo.health.records.model.ReviewStatus
import com.ayuvo.health.records.processing.NearDuplicate
import com.ayuvo.health.ui.components.GlassSurface
import com.ayuvo.health.ui.components.GlassTextButton
import com.ayuvo.health.ui.navigation.BottomNavScrollPadding
import com.ayuvo.health.ui.theme.AppColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The five §37 actions. */
enum class StorageAction { CLEAR_CACHE, REBUILD_THUMBNAILS, REBUILD_INDEX, FIND_DUPLICATES, REPROCESS_ALL }

data class RecordsStorageUiState(
    val loading: Boolean = true,
    val stats: RecordsStorageStats = RecordsStorageStats(),
    val running: StorageAction? = null,
    val finished: StorageAction? = null
)

/** Settings › Health Records › Storage (docs/health-records.md §37). */
class RecordsStorageViewModel(private val container: AppContainer) : ViewModel() {
    private val store get() = container.recordsStore
    private val _ui = MutableStateFlow(RecordsStorageUiState())
    val ui: StateFlow<RecordsStorageUiState> = _ui.asStateFlow()

    init {
        refresh()
    }

    /** Sizes are always computed off the main thread (§37). */
    fun refresh() {
        viewModelScope.launch {
            runCatching { container.recordsBackup.storage() }
                .onSuccess { stats -> _ui.update { it.copy(loading = false, stats = stats) } }
                .onFailure { _ui.update { it.copy(loading = false) } }
        }
    }

    fun run(action: StorageAction) {
        if (_ui.value.running != null) return
        _ui.update { it.copy(running = action, finished = null) }
        viewModelScope.launch {
            runCatching {
                when (action) {
                    StorageAction.CLEAR_CACHE -> withContext(Dispatchers.IO) { container.recordFiles.clearGeneratedCache() }
                    StorageAction.REBUILD_INDEX -> store.reindexAll()
                    StorageAction.REBUILD_THUMBNAILS -> rebuildThumbnails()
                    StorageAction.FIND_DUPLICATES -> findDuplicates()
                    StorageAction.REPROCESS_ALL -> reprocessAll()
                }
            }
            _ui.update { it.copy(running = null, finished = action) }
            refresh()
        }
    }

    /** Originals are never touched: only `<id>/thumb.jpg` is written again. */
    private suspend fun rebuildThumbnails() {
        val maker = ThumbnailMaker()
        for (record in store.allRecords()) {
            val file = container.recordFiles.resolve(record.filePath)?.takeIf { it.isFile } ?: continue
            val result = withContext(Dispatchers.IO) { runCatching { maker.make(file, record.fileType, record.mimeType) }.getOrNull() }
            val bitmap = result?.thumbnail ?: continue
            val path = withContext(Dispatchers.IO) { runCatching { container.recordFiles.writeThumbnail(record.id, bitmap) }.getOrNull() }
            bitmap.recycle()
            if (path != null) {
                store.updateProcessing(
                    record.id,
                    RecordProcessingUpdate(
                        thumbnailPath = path,
                        status = record.processingStatus,
                        error = record.processingError
                    )
                )
            }
        }
    }

    /** §15 near-duplicate detection over every record; new pairs open Needs Review. */
    private suspend fun findDuplicates(): Int {
        val hashes = store.hashCandidates("")
        if (hashes.size < 2) return 0
        val records = store.records(hashes.map { it.first }).associateBy { it.id }
        var found = 0
        for (i in hashes.indices) {
            for (j in i + 1 until hashes.size) {
                val (aId, aPhash, aSig) = hashes[i]
                val (bId, bPhash, bSig) = hashes[j]
                val match = NearDuplicate.isCandidate(aPhash, bPhash, aSig, bSig) ?: continue
                val a = records[aId] ?: continue
                val b = records[bId] ?: continue
                // The newer record is the one that gets the "this looks like an existing record" prompt.
                val (newer, older) = if (a.createdMs >= b.createdMs) a to b else b to a
                store.addDuplicateCandidate(DuplicateCandidate(newer.id, older.id, match.first, match.second))
                if (store.pendingDuplicates(newer.id).isNotEmpty()) {
                    store.setReviewStatus(listOf(newer.id), ReviewStatus.NEEDS_REVIEW)
                    found++
                }
            }
        }
        return found
    }

    /** §37: re-runs the pipeline from `text`; §8.1 keeps every user/confirmed value. */
    private suspend fun reprocessAll() {
        val ids = store.allRecords().map { it.id }
        if (ids.isNotEmpty()) container.recordsQueue.enqueue(ids, ProcessingStage.TEXT)
    }

    class Factory(private val container: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = RecordsStorageViewModel(container) as T
    }
}

@Composable
fun RecordsStorageScreen(container: AppContainer, onBack: () -> Unit) {
    val vm: RecordsStorageViewModel = viewModel(key = "records-storage", factory = RecordsStorageViewModel.Factory(container))
    val ui by vm.ui.collectAsState()
    var confirm by remember { mutableStateOf<StorageAction?>(null) }
    val stats = ui.stats

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 12.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.records_title), tint = AppColors.Calorie) }
            Text(stringResource(R.string.records_storage_title), fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        }
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = BottomNavScrollPadding),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            item(key = "sizes") {
                GlassSurface(Modifier.fillMaxWidth(), padding = 0.dp) {
                    Column(Modifier.padding(14.dp)) {
                        if (ui.loading) {
                            Box(Modifier.fillMaxWidth().height(80.dp), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(color = AppColors.Calorie)
                            }
                        } else {
                            Text(
                                stringResource(R.string.records_storage_counts, stats.recordCount, stats.pageCount),
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                            )
                            Spacer(Modifier.height(10.dp))
                            SizeBar(stringResource(R.string.records_storage_documents), stats.documentBytes, stats.totalBytes, bold = true)
                            SizeBar(stringResource(R.string.records_storage_pdfs), stats.pdfBytes, stats.totalBytes, indent = true)
                            SizeBar(stringResource(R.string.records_storage_images), stats.imageBytes, stats.totalBytes, indent = true)
                            SizeBar(stringResource(R.string.records_storage_text), stats.textBytes, stats.totalBytes, indent = true)
                            SizeBar(stringResource(R.string.records_storage_thumbnails), stats.thumbnailBytes, stats.totalBytes)
                            SizeBar(stringResource(R.string.records_storage_render), stats.renderCacheBytes, stats.totalBytes)
                            SizeBar(stringResource(R.string.records_storage_database), stats.databaseBytes, stats.totalBytes)
                            SizeBar(stringResource(R.string.records_storage_backups), stats.backupBytes, stats.totalBytes)
                            Spacer(Modifier.height(6.dp))
                            SizeBar(stringResource(R.string.records_storage_total), stats.totalBytes, stats.totalBytes, bold = true)
                        }
                    }
                }
            }
            item(key = "actions") {
                GlassSurface(Modifier.fillMaxWidth(), padding = 0.dp) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (action in StorageAction.entries) {
                            val label = stringResource(action.labelRes())
                            GlassTextButton(
                                text = if (ui.running == action) stringResource(R.string.records_storage_working) else label,
                                onClick = { confirm = action },
                                enabled = ui.running == null,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        Text(
                            stringResource(R.string.records_storage_note),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
                        )
                        ui.finished?.let { done ->
                            Text(
                                stringResource(R.string.records_storage_done, stringResource(done.labelRes())),
                                fontSize = 12.sp,
                                color = AppColors.Calorie
                            )
                        }
                    }
                }
            }
        }
    }

    confirm?.let { action ->
        val label = stringResource(action.labelRes())
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(stringResource(R.string.records_storage_confirm, label)) },
            text = { Text(stringResource(R.string.records_storage_note)) },
            confirmButton = {
                TextButton(onClick = {
                    confirm = null
                    vm.run(action)
                }) { Text(label) }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text(stringResource(android.R.string.cancel)) } }
        )
    }
}

@Composable
private fun SizeBar(label: String, bytes: Long, total: Long, indent: Boolean = false, bold: Boolean = false) {
    val fraction = if (total <= 0) 0f else (bytes.toFloat() / total).coerceIn(0f, 1f)
    Column(Modifier.padding(start = if (indent) 12.dp else 0.dp, top = 4.dp, bottom = 4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, fontSize = if (indent) 13.sp else 14.sp, fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal, modifier = Modifier.weight(1f))
            Text(formatBytes(bytes), fontSize = 13.sp, fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal)
        }
        Spacer(Modifier.height(4.dp))
        Box(
            Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(50))
                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
        ) {
            Box(Modifier.fillMaxWidth(fraction).height(4.dp).clip(RoundedCornerShape(50)).background(AppColors.Calorie.copy(alpha = 0.7f)))
        }
    }
}

/** Human size, matching the export screens ("1.2 MB"). */
internal fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = listOf("KB", "MB", "GB")
    var value = bytes.toDouble() / 1024
    var unit = 0
    while (value >= 1024 && unit < units.size - 1) {
        value /= 1024
        unit++
    }
    return String.format(java.util.Locale.US, if (value >= 10) "%.0f %s" else "%.1f %s", value, units[unit])
}

private fun StorageAction.labelRes(): Int = when (this) {
    StorageAction.CLEAR_CACHE -> R.string.records_storage_action_clear_cache
    StorageAction.REBUILD_THUMBNAILS -> R.string.records_storage_action_rebuild_thumbs
    StorageAction.REBUILD_INDEX -> R.string.records_storage_action_rebuild_index
    StorageAction.FIND_DUPLICATES -> R.string.records_storage_action_find_duplicates
    StorageAction.REPROCESS_ALL -> R.string.records_storage_action_reprocess
}
