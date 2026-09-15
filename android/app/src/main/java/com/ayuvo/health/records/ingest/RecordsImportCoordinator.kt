package com.ayuvo.health.records.ingest

import android.net.Uri
import android.util.Log
import com.ayuvo.health.records.data.RecordsStore
import com.ayuvo.health.records.model.DuplicateCandidate
import com.ayuvo.health.records.model.DuplicateReason
import com.ayuvo.health.records.model.DuplicateResolution
import com.ayuvo.health.records.model.HealthRecord
import com.ayuvo.health.records.processing.RecordProcessingQueue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** A just-imported record whose bytes match an existing one (§4.5). */
data class DuplicateMatch(val newRecord: HealthRecord, val existing: HealthRecord)

/** Result of one import batch, shown once by the Records screen. */
data class RecordsImportNotice(
    val id: Long,
    val savedCount: Int,
    val tooLargeCount: Int,
    val unreadableCount: Int,
    val duplicates: List<DuplicateMatch>
)

/** One item of a batch: a URI, captured bytes or plain text. */
sealed interface ImportItem {
    data class FromUri(val uri: Uri) : ImportItem
    data class FromBytes(val bytes: ByteArray, val filename: String?) : ImportItem
    data class FromText(val text: String) : ImportItem
}

/**
 * Runs imports on the app scope so they outlive the screen (and a share intent's Activity),
 * publishes one [RecordsImportNotice] per batch, then generates thumbnails and metadata dates
 * in the background. The UI observes [RecordsStore.revision] for the rows themselves.
 */
class RecordsImportCoordinator(
    private val scope: CoroutineScope,
    private val importer: () -> RecordImporter,
    private val store: () -> RecordsStore,
    private val queue: () -> RecordProcessingQueue
) {
    private val _notice = MutableStateFlow<RecordsImportNotice?>(null)
    val notice: StateFlow<RecordsImportNotice?> = _notice.asStateFlow()

    private val _inFlight = MutableStateFlow(0)
    val inFlight: StateFlow<Int> = _inFlight.asStateFlow()

    fun import(items: List<ImportItem>, spec: ImportSpec): Job? {
        if (items.isEmpty()) return null
        _inFlight.update { it + items.size }
        return scope.launch {
            val saved = mutableListOf<HealthRecord>()
            val duplicates = mutableListOf<DuplicateMatch>()
            var tooLarge = 0
            var unreadable = 0
            try {
                val importer = importer()
                for (item in items) {
                    val outcome = runCatching {
                        when (item) {
                            is ImportItem.FromUri -> importer.importUri(item.uri, spec)
                            is ImportItem.FromBytes -> importer.importBytes(item.bytes, item.filename, spec)
                            is ImportItem.FromText -> importer.importText(item.text, spec)
                        }
                    }.getOrElse {
                        Log.w(TAG, "Record import failed: ${it.javaClass.simpleName}")
                        ImportOutcome.Refused(RefusalReason.UNREADABLE, null)
                    }
                    when (outcome) {
                        is ImportOutcome.Saved -> {
                            saved += outcome.record
                            outcome.duplicateOf?.let { duplicates += DuplicateMatch(outcome.record, it) }
                        }
                        is ImportOutcome.Refused -> when (outcome.reason) {
                            RefusalReason.TOO_LARGE -> tooLarge++
                            RefusalReason.UNREADABLE -> unreadable++
                        }
                    }
                }
                _notice.value = RecordsImportNotice(
                    id = System.nanoTime(),
                    savedCount = saved.size,
                    tooLargeCount = tooLarge,
                    unreadableCount = unreadable,
                    duplicates = duplicates
                )
            } finally {
                _inFlight.update { (it - items.size).coerceAtLeast(0) }
            }
            // Exact duplicates are remembered so Needs Review and the duplicate sheet can offer them later.
            for (match in duplicates) {
                runCatching {
                    store().addDuplicateCandidate(DuplicateCandidate(match.newRecord.id, match.existing.id, DuplicateReason.CHECKSUM, 1.0))
                }
            }
            // Background stage: never blocks the notice, never removes a record.
            for (record in saved) runCatching { importer().process(record) }
            // Phase 2 pipeline (text → … → index) runs in WorkManager, one record at a time.
            runCatching { queue().enqueue(saved.map { it.id }) }
                .onFailure { Log.w(TAG, "Processing enqueue failed: ${it.javaClass.simpleName}") }
        }
    }

    fun consumeNotice(id: Long) {
        _notice.update { current -> if (current?.id == id) null else current }
    }

    /** Duplicate sheet "Cancel": the new import is deleted. */
    fun discard(record: HealthRecord) {
        scope.launch {
            runCatching {
                queue().cancel(listOf(record.id))
                store().delete(listOf(record.id))
            }
        }
    }

    /** Duplicate sheet "Keep both": resolution only. */
    fun keepBoth(newId: String, existingId: String) {
        scope.launch { runCatching { store().resolveDuplicate(newId, existingId, DuplicateResolution.KEEP_BOTH) } }
    }

    /**
     * Duplicate sheet "Replace" (plan §3.11): the existing record gets the new file and is
     * reprocessed; notes, tags and confirmed values stay; the new row goes.
     */
    fun replace(newId: String, existingId: String, onDone: () -> Unit = {}) {
        scope.launch {
            runCatching {
                queue().cancel(listOf(newId))
                store().replaceWithNew(existingId, newId)
                queue().enqueue(listOf(existingId))
            }.onFailure { Log.w(TAG, "Replace failed: ${it.javaClass.simpleName}") }
            onDone()
        }
    }

    /** Duplicate sheet "Merge": notes and tags move into the existing record; the new one is deleted. */
    fun merge(newId: String, existingId: String, onDone: () -> Unit = {}) {
        scope.launch {
            runCatching {
                queue().cancel(listOf(newId))
                store().mergeInto(existingId, newId)
            }.onFailure { Log.w(TAG, "Merge failed: ${it.javaClass.simpleName}") }
            onDone()
        }
    }

    private companion object {
        const val TAG = "AyuvoRecords"
    }
}
