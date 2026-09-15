package com.ayuvo.health.records.ingest

import android.net.Uri
import android.util.Log
import com.ayuvo.health.records.data.RecordsStore
import com.ayuvo.health.records.model.HealthRecord
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
    private val store: () -> RecordsStore
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
            // Background stage: never blocks the notice, never removes a record.
            for (record in saved) runCatching { importer().process(record) }
        }
    }

    fun consumeNotice(id: Long) {
        _notice.update { current -> if (current?.id == id) null else current }
    }

    /** Duplicate sheet "Cancel": the new import is deleted. */
    fun discard(record: HealthRecord) {
        scope.launch { runCatching { store().delete(listOf(record.id)) } }
    }

    private companion object {
        const val TAG = "AyuvoRecords"
    }
}
