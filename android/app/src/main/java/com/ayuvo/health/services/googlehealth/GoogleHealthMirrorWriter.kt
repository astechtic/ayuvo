package com.ayuvo.health.services.googlehealth

import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.Record
import com.ayuvo.health.data.health.GoogleHealthMirrorEntry
import com.ayuvo.health.data.health.HealthDataStore
import com.ayuvo.health.data.health.HealthSampleRow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Clock

data class GoogleHealthMirrorResult(val written: Int, val failed: Int, val deferred: Boolean)

/**
 * Writes `pending` Google Health rows into Health Connect (docs/google-health.md §4).
 *
 * `disabled` doubles as "not writable right now": rows wait there while the write-back toggle is
 * off or their WRITE_* permission is missing, and every flush with write-back on re-queues them.
 * A failed insert keeps the batch pending (attempts + 1, `error` after [MAX_ATTEMPTS]) and asks
 * [HealthWriteRetryWorker][com.ayuvo.health.services.health.HealthWriteRetryWorker] to try later.
 */
class GoogleHealthMirrorWriter(
    private val store: HealthDataStore,
    /** Inserts and returns Health Connect record ids in order; throws on failure. */
    private val insert: suspend (List<Record>) -> List<String>,
    /** Granted permissions, or null when the probe failed. */
    private val grantedPermissions: suspend () -> Set<String>?,
    private val scheduleRetry: () -> Unit = {},
    private val clock: Clock = Clock.systemUTC(),
    private val log: (String) -> Unit = {}
) {
    private val mutex = Mutex()

    /** [writeBack] false only parks pending rows; at most [maxRecords] records are written per call. */
    suspend fun flush(writeBack: Boolean, maxRecords: Int = MAX_RECORDS_PER_FLUSH): GoogleHealthMirrorResult = mutex.withLock {
        if (!writeBack) {
            store.moveGoogleMirrorStatus(GoogleHealthMirrorEntry.STATUS_PENDING, GoogleHealthMirrorEntry.STATUS_DISABLED)
            return@withLock GoogleHealthMirrorResult(0, 0, deferred = false)
        }
        val granted = grantedPermissions()
        if (granted == null) {
            scheduleRetry()
            return@withLock GoogleHealthMirrorResult(0, 0, deferred = true)
        }
        store.moveGoogleMirrorStatus(GoogleHealthMirrorEntry.STATUS_DISABLED, GoogleHealthMirrorEntry.STATUS_PENDING)
        var written = 0
        var failed = 0
        while (written + failed < maxRecords) {
            val batch = store.googleMirrorEntries(GoogleHealthMirrorEntry.STATUS_PENDING, BATCH_SIZE)
            if (batch.isEmpty()) break
            val now = clock.millis()
            val updates = mutableListOf<GoogleHealthMirrorEntry>()
            val toInsert = mutableListOf<Triple<GoogleHealthMirrorEntry, HealthSampleRow, Record>>()
            for ((entry, row) in batch) {
                val cls = GoogleHealthRecordFactory.recordClass(row.typeId)
                when {
                    cls == null -> updates += entry.copy(mirrorStatus = GoogleHealthMirrorEntry.STATUS_UNSUPPORTED)
                    HealthPermission.getWritePermission(cls) !in granted -> updates += entry.copy(mirrorStatus = GoogleHealthMirrorEntry.STATUS_DISABLED)
                    else -> try {
                        val record = GoogleHealthRecordFactory.build(row, stagesFor(row))
                        if (record != null) toInsert += Triple(entry, row, record)
                        else updates += entry.copy(mirrorStatus = GoogleHealthMirrorEntry.STATUS_UNSUPPORTED)
                    } catch (e: IllegalArgumentException) {
                        // Out-of-range value: Health Connect will never accept it.
                        updates += entry.copy(mirrorStatus = GoogleHealthMirrorEntry.STATUS_ERROR, attempts = entry.attempts + 1, lastError = "invalid")
                        failed++
                    }
                }
            }
            var insertFailed = false
            for (chunk in toInsert.chunked(INSERT_CHUNK)) {
                try {
                    val ids = insert(chunk.map { it.third })
                    chunk.forEachIndexed { i, (entry, _, _) ->
                        updates += entry.copy(
                            mirrorStatus = GoogleHealthMirrorEntry.STATUS_MIRRORED,
                            platformId = ids.getOrNull(i),
                            mirroredMs = now,
                            lastError = null
                        )
                    }
                    written += chunk.size
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log("Google Health write-back failed: ${e.javaClass.simpleName}")
                    chunk.forEach { (entry, _, _) ->
                        val attempts = entry.attempts + 1
                        updates += entry.copy(
                            mirrorStatus = if (attempts >= MAX_ATTEMPTS) GoogleHealthMirrorEntry.STATUS_ERROR else GoogleHealthMirrorEntry.STATUS_PENDING,
                            attempts = attempts,
                            lastError = e.javaClass.simpleName
                        )
                    }
                    failed += chunk.size
                    insertFailed = true
                    break
                }
            }
            store.updateGoogleMirror(updates)
            if (insertFailed) {
                scheduleRetry()
                return@withLock GoogleHealthMirrorResult(written, failed, deferred = true)
            }
        }
        GoogleHealthMirrorResult(written, failed, deferred = false)
    }

    /** Sleep sessions carry their stage rows (`gh:<id>:<n>`) inside the one record. */
    private suspend fun stagesFor(row: HealthSampleRow): List<HealthSampleRow> {
        if (row.typeId != "sleep") return emptyList()
        val prefix = row.id + ":"
        return store.samplesBetween(row.typeId, row.startMs, row.endMs).filter { it.id.startsWith(prefix) }
    }

    companion object {
        const val BATCH_SIZE = 500
        const val INSERT_CHUNK = 100
        const val MAX_ATTEMPTS = 5
        const val MAX_RECORDS_PER_FLUSH = 10_000
    }
}
