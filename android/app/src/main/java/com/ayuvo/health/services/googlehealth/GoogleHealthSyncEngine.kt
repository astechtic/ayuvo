package com.ayuvo.health.services.googlehealth

import com.ayuvo.health.data.health.GoogleHealthMirrorEntry
import com.ayuvo.health.data.health.GoogleHealthSyncState
import com.ayuvo.health.data.health.HealthDataStore
import com.ayuvo.health.data.health.HealthPageCommit
import com.ayuvo.health.data.health.HealthRollupMath
import com.ayuvo.health.data.health.HealthSampleRow
import com.ayuvo.health.data.health.HealthTypeDescriptor
import com.ayuvo.health.models.HealthDataType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicInteger

/** What one run may read and whether new rows are write-back candidates. */
data class GoogleHealthSyncRequest(
    val grantedScopes: Set<String>,
    /** Data groups the user ticked (`scope_groups[].id`). */
    val enabledGroups: Set<String>,
    val writeBack: Boolean
)

sealed class GoogleHealthSyncOutcome {
    data class Synced(val typesSynced: Int, val typesFailed: Int, val rowsChanged: Int) : GoogleHealthSyncOutcome()
    data object Busy : GoogleHealthSyncOutcome()
    /** Consent lost (or the refresh token expired): Settings shows Reconnect; nothing ran past the failure. */
    data object NeedsReconnect : GoogleHealthSyncOutcome()
}

data class GoogleHealthSyncStatus(
    val running: Boolean = false,
    val typesDone: Int = 0,
    val typesTotal: Int = 0,
    val lastSyncMs: Long? = null,
    /** Per `gh_type` status from `google_health_sync_state` (idle, unsupported, error:scope, …). */
    val typeStatus: Map<String, String> = emptyMap()
) {
    val progress: Float? get() = if (running && typesTotal > 0) typesDone.toFloat() / typesTotal else null
}

/**
 * Google Health API → local mirror (docs/google-health.md §3). Pure engine: no Android imports.
 *
 * Under a mutex, per enabled type (≤ `max_concurrent_types` at once): read from `now − 90 d` on the
 * first run and from `cursor − 2 d` afterwards, page by page; each page commits its changed rows,
 * their mirror entries and the type's `page_token` in one transaction, and the cursor moves only with
 * the last page. Unchanged re-fetched points are dropped before the commit so `updated_ms` (and the
 * Health Connect `clientRecordVersion`) stays put. Touched days get their rollups rebuilt.
 */
class GoogleHealthSyncEngine(
    private val map: GoogleHealthMap,
    private val source: GoogleHealthSource,
    private val store: HealthDataStore,
    private val clock: Clock = Clock.systemUTC(),
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
    private val mapper: GoogleHealthMapper = GoogleHealthMapper(map, zone),
    private val log: (String) -> Unit = {}
) {
    private val mutex = Mutex()
    private val _status = MutableStateFlow(GoogleHealthSyncStatus())
    val status: StateFlow<GoogleHealthSyncStatus> = _status

    suspend fun refreshStatus() {
        val states = store.googleSyncStates()
        _status.value = GoogleHealthSyncStatus(
            lastSyncMs = states.mapNotNull { it.lastSyncMs }.maxOrNull(),
            typeStatus = states.associate { it.ghType to it.status }
        )
    }

    /** [wait] = false returns [GoogleHealthSyncOutcome.Busy] instead of queueing behind a running sync. */
    suspend fun sync(request: GoogleHealthSyncRequest, wait: Boolean = true): GoogleHealthSyncOutcome {
        if (!wait) {
            if (!mutex.tryLock()) return GoogleHealthSyncOutcome.Busy
            return try { runLocked(request) } finally { mutex.unlock() }
        }
        return mutex.withLock { runLocked(request) }
    }

    /** Disconnect: drops cursors, optionally the origin-3 rows, and rebuilds the affected rollups. */
    suspend fun clear(deleteRows: Boolean) = mutex.withLock {
        val touched = store.clearGoogleHealth(deleteRows)
        for ((typeId, days) in touched) rebuildRollups(typeId, days)
        refreshStatus()
    }

    private suspend fun runLocked(request: GoogleHealthSyncRequest): GoogleHealthSyncOutcome {
        val now = clock.millis()
        val states = store.googleSyncStates().associateBy { it.ghType }
        val types = map.types.filter { it.scopeGroup in request.enabledGroups }
        val done = AtomicInteger(0)
        val synced = AtomicInteger(0)
        val failed = AtomicInteger(0)
        val rows = AtomicInteger(0)
        _status.update { it.copy(running = true, typesDone = 0, typesTotal = types.size) }
        val semaphore = Semaphore(map.api.maxConcurrentTypes.coerceAtLeast(1))
        val outcome = try {
            coroutineScope {
                types.map { entry ->
                    async {
                        semaphore.withPermit {
                            when (val r = syncType(entry, states[entry.ghType], request, now)) {
                                is TypeResult.Ok -> { synced.incrementAndGet(); rows.addAndGet(r.rowsChanged) }
                                TypeResult.Failed -> failed.incrementAndGet()
                                TypeResult.Skipped -> Unit
                            }
                            _status.update { it.copy(typesDone = done.incrementAndGet()) }
                        }
                    }
                }.awaitAll()
            }
            GoogleHealthSyncOutcome.Synced(synced.get(), failed.get(), rows.get())
        } catch (e: GoogleHealthException.Auth) {
            log("Google Health sync needs reconnect")
            GoogleHealthSyncOutcome.NeedsReconnect
        }
        val after = store.googleSyncStates()
        _status.value = GoogleHealthSyncStatus(
            running = false,
            lastSyncMs = after.mapNotNull { it.lastSyncMs }.maxOrNull(),
            typeStatus = after.associate { it.ghType to it.status }
        )
        return outcome
    }

    private sealed class TypeResult {
        data class Ok(val rowsChanged: Int) : TypeResult()
        data object Failed : TypeResult()
        data object Skipped : TypeResult()
    }

    private suspend fun syncType(
        entry: GoogleHealthMap.TypeEntry,
        saved: GoogleHealthSyncState?,
        request: GoogleHealthSyncRequest,
        now: Long
    ): TypeResult {
        var state = saved ?: GoogleHealthSyncState(ghType = entry.ghType)
        if (!map.isGranted(entry, request.grantedScopes)) {
            store.putGoogleSyncStates(listOf(state.copy(status = GoogleHealthSyncState.STATUS_ERROR_SCOPE, pageToken = null)))
            return TypeResult.Skipped
        }
        // An optional type the account does not serve is re-probed weekly, not on every Sync now.
        if (state.status == GoogleHealthSyncState.STATUS_UNSUPPORTED && (state.lastErrorMs ?: 0L) > now - UNSUPPORTED_RETRY_MS) {
            return TypeResult.Skipped
        }
        val floor = state.backfillFloorMs ?: (now - map.api.initialBackfillDays * DAY_MS)
        val fromMs = state.cursorMs?.let { it - map.api.overlapDays * DAY_MS } ?: floor
        state = state.copy(backfillFloorMs = floor, status = GoogleHealthSyncState.STATUS_SYNCING)
        val filter = mapper.filter(entry, fromMs)
        var token = state.pageToken
        var restarted = false
        var maxEnd: Long? = null
        var changed = 0
        val touchedDays = LinkedHashSet<String>()
        try {
            while (true) {
                val page = try {
                    source.listDataPoints(entry, filter, token, entry.pageSize)
                } catch (e: GoogleHealthException.Http) {
                    // A stale page token (filter window moved, token expired) restarts the window once.
                    if (e.code == 400 && token != null && !restarted) {
                        restarted = true
                        token = null
                        continue
                    }
                    throw e
                }
                val mapped = page.points.mapNotNull { point ->
                    runCatching { mapper.map(entry, point, now) }.getOrNull()
                }
                mapped.forEach { m -> maxEnd = maxOf(maxEnd ?: Long.MIN_VALUE, minOf(m.row.endMs, now)) }
                val fresh = changedOnly(mapped)
                val mirror = mirrorEntries(entry, fresh, request.writeBack)
                val next = page.nextPageToken
                state = if (next != null) {
                    state.copy(pageToken = next)
                } else {
                    state.copy(
                        pageToken = null,
                        cursorMs = listOfNotNull(state.cursorMs, maxEnd).maxOrNull() ?: now,
                        lastSyncMs = now,
                        status = GoogleHealthSyncState.STATUS_IDLE,
                        lastError = null,
                        lastErrorMs = null
                    )
                }
                val freshRows = fresh.flatMap { it.allRows }
                store.commit(
                    HealthPageCommit(
                        rows = freshRows,
                        sources = fresh.mapNotNull { it.source }.distinctBy { it.id },
                        googleStates = listOf(state),
                        googleMirror = mirror
                    )
                )
                changed += freshRows.size
                freshRows.forEach { touchedDays += it.localDay }
                if (next == null) break
                token = next
            }
        } catch (e: GoogleHealthException) {
            when (e) {
                is GoogleHealthException.Auth -> {
                    store.putGoogleSyncStates(listOf(state.copy(status = GoogleHealthSyncState.STATUS_IDLE)))
                    throw e
                }
                is GoogleHealthException.Scope -> store.putGoogleSyncStates(listOf(state.copy(status = GoogleHealthSyncState.STATUS_ERROR_SCOPE, pageToken = null)))
                is GoogleHealthException.Unsupported -> store.putGoogleSyncStates(
                    listOf(state.copy(status = GoogleHealthSyncState.STATUS_UNSUPPORTED, pageToken = null, lastError = e.message, lastErrorMs = now))
                )
                else -> store.putGoogleSyncStates(listOf(state.copy(status = errorStatus(e), lastError = e.message, lastErrorMs = now)))
            }
            rebuildRollups(entry.typeId, touchedDays)
            log("Google Health ${entry.ghType} failed: ${e.javaClass.simpleName}")
            return if (e is GoogleHealthException.Unsupported || e is GoogleHealthException.Scope) TypeResult.Skipped else TypeResult.Failed
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Unexpected failure (malformed JSON, storage): keep the page token so the next run resumes.
            store.putGoogleSyncStates(listOf(state.copy(status = GoogleHealthSyncState.STATUS_ERROR_PREFIX + "internal", lastError = e.javaClass.simpleName, lastErrorMs = now)))
            log("Google Health ${entry.ghType} failed: ${e.javaClass.simpleName}")
            return TypeResult.Failed
        }
        rebuildRollups(entry.typeId, touchedDays)
        return TypeResult.Ok(changed)
    }

    private fun errorStatus(e: GoogleHealthException): String = GoogleHealthSyncState.STATUS_ERROR_PREFIX + when (e) {
        is GoogleHealthException.Http -> e.code.toString()
        is GoogleHealthException.Network -> "network"
        else -> "unknown"
    }

    /** Points whose stored rows already hold the same values are dropped (upsert rule, §3). */
    private suspend fun changedOnly(mapped: List<GoogleHealthMapped>): List<GoogleHealthMapped> {
        if (mapped.isEmpty()) return mapped
        val existing = store.samplesByIds(mapped.flatMap { m -> m.allRows.map { it.id } }).associateBy { it.id }
        return mapped.filter { m ->
            // A tombstoned id never comes back; anything new or different is written.
            if (existing[m.row.id]?.deleted == true) return@filter false
            m.allRows.any { row -> existing[row.id]?.let { !sameValues(it, row) } ?: true }
        }
    }

    private fun sameValues(a: HealthSampleRow, b: HealthSampleRow): Boolean =
        a.copy(updatedMs = 0L) == b.copy(updatedMs = 0L)

    private suspend fun mirrorEntries(
        entry: GoogleHealthMap.TypeEntry,
        mapped: List<GoogleHealthMapped>,
        writeBack: Boolean
    ): List<GoogleHealthMirrorEntry> {
        if (mapped.isEmpty()) return emptyList()
        if (entry.hc == null) {
            return mapped.map { GoogleHealthMirrorEntry(it.row.id, mirrorStatus = GoogleHealthMirrorEntry.STATUS_UNSUPPORTED) }
        }
        val window = map.echoGuard.duplicateWindowMs
        val platformRows = store.samplesBetween(
            entry.typeId,
            mapped.minOf { it.row.startMs } - window,
            mapped.maxOf { it.row.endMs } + window
        ).filter { it.origin == HealthSampleRow.ORIGIN_PLATFORM }
        return mapped.map { m ->
            val status = when {
                mapper.isEcho(m.dataSource, GoogleHealthMapper.RUNNING_OS) -> GoogleHealthMirrorEntry.STATUS_SKIPPED_DUP
                mapper.isDuplicateOfPlatform(m.row, platformRows) -> GoogleHealthMirrorEntry.STATUS_SKIPPED_DUP
                !writeBack -> GoogleHealthMirrorEntry.STATUS_DISABLED
                else -> GoogleHealthMirrorEntry.STATUS_PENDING
            }
            GoogleHealthMirrorEntry(m.row.id, mirrorStatus = status)
        }
    }

    // -- Roll-ups ----------------------------------------------------------------------------------

    /**
     * Rebuilds daily (and today's hourly) rollups of [typeId] for [days] from local rows. Days whose
     * rollup came from a Health Connect aggregate keep it: Health Connect already dedupes across
     * sources, and the next platform sync folds the mirrored Google records into that total.
     */
    private suspend fun rebuildRollups(typeId: String, days: Set<String>) {
        if (days.isEmpty()) return
        val type = HealthDataType.byId(typeId) ?: return
        val z = zone()
        val sorted = days.mapNotNull { runCatching { LocalDate.parse(it) }.getOrNull() }.sorted()
        if (sorted.isEmpty()) return
        val from = sorted.first().minusDays(2).atStartOfDay(z).toInstant().toEpochMilli()
        val to = sorted.last().plusDays(2).atStartOfDay(z).toInstant().toEpochMilli()
        val rows = store.samplesBetween(typeId, from, to).filter { it.localDay in days }
        var fresh: Set<String> = days
        if (type.usesPlatformAggregate) {
            val platformDays = store.dailyRollups(typeId, sorted.first().toString(), sorted.last().toString())
                .filter { it.fromPlatformAggregate }
                .mapTo(HashSet()) { it.day }
            fresh = days - platformDays
        }
        if (fresh.isEmpty()) return
        val descriptor = HealthTypeDescriptor.of(type)
        val freshRows = rows.filter { it.localDay in fresh }
        store.replaceDailyRollups(typeId, fresh, HealthRollupMath.rebuildDaily(descriptor, freshRows, z.id))
        if (type == HealthDataType.NUTRITION_RECORD) {
            for ((dietary, rollups) in HealthRollupMath.virtualDietary(freshRows, z.id)) {
                store.replaceDailyRollups(dietary.id, fresh, rollups.filter { it.day in fresh })
            }
        }
        val today = LocalDate.now(clock.withZone(z)).toString()
        if (today in fresh) {
            store.replaceHourlyRollups(typeId, today, HealthRollupMath.rebuildHourly(descriptor, freshRows, today, z))
        }
    }

    companion object {
        private const val DAY_MS = 86_400_000L
        private const val UNSUPPORTED_RETRY_MS = 7 * DAY_MS
    }
}
