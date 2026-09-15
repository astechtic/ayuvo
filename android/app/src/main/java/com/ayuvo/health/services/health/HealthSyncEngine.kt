package com.ayuvo.health.services.health

import com.ayuvo.health.data.health.HealthDailyRollup
import com.ayuvo.health.data.health.HealthDataStore
import com.ayuvo.health.data.health.HealthDayKeys
import com.ayuvo.health.data.health.HealthHourlyRollup
import com.ayuvo.health.data.health.HealthPageCommit
import com.ayuvo.health.data.health.HealthRollupMath
import com.ayuvo.health.data.health.HealthSampleRow
import com.ayuvo.health.data.health.HealthSyncState
import com.ayuvo.health.data.health.HealthTypeDescriptor
import com.ayuvo.health.data.health.LocalHealthSources
import com.ayuvo.health.models.HealthDataType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

enum class HealthSyncTrigger { APP_OPEN, MANUAL_REFRESH, HUB_VISIBLE, PERMISSIONS_CHANGED, IMPORT_COMPLETED }

/** Result of the coordinator's single permission probe: granted hub types + history flag. */
data class HealthGrants(val types: Set<HealthDataType>, val history: Boolean)

sealed class HealthSyncOutcome {
    data class Synced(val moreWork: Boolean, val typesSynced: Int, val rowsChanged: Int) : HealthSyncOutcome()
    data class Skipped(val reason: Reason) : HealthSyncOutcome() {
        enum class Reason { HUB_DISABLED, THROTTLED, PROBE_FAILED, BUSY, CANCELLED }
    }
    data class RateLimited(val untilMs: Long) : HealthSyncOutcome()
    object PermissionsReset : HealthSyncOutcome()
}

data class HealthSyncPolicy(
    val appOpenThrottleMs: Long = 3 * 60_000L,
    val manualThrottleMs: Long = 30_000L,
    val ipcBudgetAppOpen: Int = 150,
    val ipcBudgetInteractive: Int = 600,
    val pageSize: Int = 1000,
    val chunkDays: Long = 30,
    val aggregateWindowDays: Long = 92,
    val seriesRetentionDays: Long = 365,
    val quotaBackoffMs: Long = 45 * 60_000L,
    val maxHistoryDays: Long = 3650,
    val initialWindowDays: Long = 30
)

/** Device-local throttle state (never in cloud backup); PreferencesStore implements it. */
interface HealthSyncPrefs {
    suspend fun hubEnabled(): Boolean
    suspend fun lastSyncAtMs(): Long?
    suspend fun setLastSyncAtMs(ms: Long?)
    suspend fun rateLimitedUntilMs(): Long?
    suspend fun setRateLimitedUntilMs(ms: Long?)
}

enum class HealthSyncPhase { IDLE, SYNCING, IMPORTING_HISTORY, RATE_LIMITED, PERMISSIONS_RESET }

data class HealthSyncStatus(
    val phase: HealthSyncPhase = HealthSyncPhase.IDLE,
    val running: Boolean = false,
    val lastSyncMs: Long? = null,
    val typeCount: Int = 0,
    /** 0..1 of the all-history import, null when nothing is importing. */
    val progress: Float? = null,
    val progressFromYear: Int? = null,
    val progressToYear: Int? = null,
    val rateLimitedUntilMs: Long? = null,
    /** Earliest readable instant for types without History access (hub banner). */
    val historyLimitedBeforeMs: Long? = null,
    val lastError: String? = null
)

/**
 * Pure sync engine: Health Connect → local mirror. No Android imports; the coordinator
 * ([com.ayuvo.health.AppContainer.requestHealthSync]) supplies grants from
 * its single permission probe and launches this on the app scope.
 *
 * Run (under a mutex): gates → per newly granted type an initial 30-day window with the
 * changes token taken *before* the first read (page + token committed atomically) → incremental
 * `getChanges` drain (expired token → re-token + overlap re-read) → all-history backfill
 * round-robin one chunk per type per pass (probe-bounded, gap-jumping) → daily/hourly stats
 * for dirty days (platform aggregates for SUM types, local math otherwise) → series pruning →
 * local-app rows. Quota errors back off 45 min; the IPC budget bounds every run.
 */
class HealthSyncEngine(
    private val source: HealthReadSource,
    private val store: HealthDataStore,
    private val prefs: HealthSyncPrefs,
    private val clock: Clock = Clock.systemUTC(),
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
    private val policy: HealthSyncPolicy = HealthSyncPolicy(),
    private val localSources: LocalHealthSources? = null,
    private val log: (String) -> Unit = {}
) {
    private val mutex = Mutex()
    private val _status = MutableStateFlow(HealthSyncStatus())
    val status: StateFlow<HealthSyncStatus> = _status

    @Volatile
    private var cancelRequested = false

    /** Runs [block] while no sync can run (used by Delete All Data / Clear / Replace-all import). */
    suspend fun <T> withPaused(block: suspend () -> T): T {
        cancelRequested = true
        return mutex.withLock {
            cancelRequested = false
            block()
        }
    }

    fun cancel() {
        cancelRequested = true
    }

    /** Rebuilds the published status from the store (hub open, app start). */
    suspend fun refreshStatus() {
        val states = store.syncStates()
        _status.value = statusFrom(states, running = false, phaseOverride = null)
    }

    suspend fun sync(trigger: HealthSyncTrigger, grants: HealthGrants?): HealthSyncOutcome {
        if (!prefs.hubEnabled()) return HealthSyncOutcome.Skipped(HealthSyncOutcome.Skipped.Reason.HUB_DISABLED)
        val now = clock.millis()
        prefs.rateLimitedUntilMs()?.let { until ->
            if (now < until) {
                _status.value = _status.value.copy(phase = HealthSyncPhase.RATE_LIMITED, rateLimitedUntilMs = until)
                return HealthSyncOutcome.RateLimited(until)
            }
        }
        val throttle = when (trigger) {
            HealthSyncTrigger.APP_OPEN -> policy.appOpenThrottleMs
            HealthSyncTrigger.MANUAL_REFRESH -> policy.manualThrottleMs
            else -> 0L
        }
        if (throttle > 0) {
            val last = prefs.lastSyncAtMs()
            if (last != null && now - last < throttle) return HealthSyncOutcome.Skipped(HealthSyncOutcome.Skipped.Reason.THROTTLED)
        }
        if (grants == null) return HealthSyncOutcome.Skipped(HealthSyncOutcome.Skipped.Reason.PROBE_FAILED)
        if (grants.types.isEmpty()) {
            _status.value = statusFrom(store.syncStates(), running = false, phaseOverride = HealthSyncPhase.PERMISSIONS_RESET)
            return HealthSyncOutcome.PermissionsReset
        }

        if (trigger == HealthSyncTrigger.APP_OPEN) {
            if (!mutex.tryLock()) return HealthSyncOutcome.Skipped(HealthSyncOutcome.Skipped.Reason.BUSY)
            try {
                return runLocked(trigger, grants)
            } finally {
                mutex.unlock()
            }
        }
        return mutex.withLock { runLocked(trigger, grants) }
    }

    private suspend fun runLocked(trigger: HealthSyncTrigger, grants: HealthGrants): HealthSyncOutcome {
        cancelRequested = false
        val run = Run(
            now = clock.millis(),
            budget = if (trigger == HealthSyncTrigger.APP_OPEN) policy.ipcBudgetAppOpen else policy.ipcBudgetInteractive,
            grants = grants,
            zone = zone()
        )
        _status.value = _status.value.copy(running = true, phase = HealthSyncPhase.SYNCING, rateLimitedUntilMs = null)
        var outcome: HealthSyncOutcome
        try {
            run.prepareStates()
            run.initialWindows()
            run.drainChanges()
            run.backfillHistory()
            run.alwaysRecentDays()
            run.pruneSeries()
            run.localRows()
            prefs.setLastSyncAtMs(run.now)
            outcome = HealthSyncOutcome.Synced(
                moreWork = run.moreWork(),
                typesSynced = run.typesTouched.size,
                rowsChanged = run.rowsChanged
            )
        } catch (_: BudgetExhausted) {
            prefs.setLastSyncAtMs(run.now)
            outcome = HealthSyncOutcome.Synced(moreWork = true, typesSynced = run.typesTouched.size, rowsChanged = run.rowsChanged)
        } catch (_: RunCancelled) {
            outcome = HealthSyncOutcome.Skipped(HealthSyncOutcome.Skipped.Reason.CANCELLED)
        } catch (quota: HealthQuotaExceededException) {
            val until = run.now + policy.quotaBackoffMs
            prefs.setRateLimitedUntilMs(until)
            log("Health Connect quota hit; backing off until $until")
            outcome = HealthSyncOutcome.RateLimited(until)
        }
        run.flushStates()
        val states = store.syncStates()
        _status.value = statusFrom(
            states,
            running = false,
            phaseOverride = (outcome as? HealthSyncOutcome.RateLimited)?.let { HealthSyncPhase.RATE_LIMITED }
        ).copy(rateLimitedUntilMs = (outcome as? HealthSyncOutcome.RateLimited)?.untilMs)
        return outcome
    }

    // -- One run ---------------------------------------------------------------

    private class BudgetExhausted : RuntimeException()
    private class RunCancelled : RuntimeException()

    private inner class Run(val now: Long, val budget: Int, val grants: HealthGrants, val zone: ZoneId) {
        var ipc = 0
        var rowsChanged = 0
        val typesTouched = LinkedHashSet<String>()
        val states = LinkedHashMap<String, HealthSyncState>()
        val dirtyStates = LinkedHashSet<String>()
        /** Types whose backfill still has chunks left after this run. */
        var backfillPending = false
        val rolledUpDays = HashMap<String, MutableSet<String>>()
        val descriptors = HashMap<String, HealthTypeDescriptor>()

        private fun descriptor(type: HealthDataType) = descriptors.getOrPut(type.id) { HealthTypeDescriptor.of(type) }

        fun moreWork(): Boolean = backfillPending || ipc >= budget

        suspend fun <T> call(block: suspend () -> T): T {
            if (cancelRequested) throw RunCancelled()
            if (ipc >= budget) throw BudgetExhausted()
            ipc++
            return block()
        }

        fun state(type: HealthDataType): HealthSyncState = states.getOrPut(type.id) { HealthSyncState(typeId = type.id) }

        fun update(type: HealthDataType, transform: (HealthSyncState) -> HealthSyncState) {
            states[type.id] = transform(state(type)).copy(ipcCallsTotal = state(type).ipcCallsTotal)
            dirtyStates += type.id
        }

        suspend fun flushStates() {
            for (id in dirtyStates) states[id]?.let { store.putSyncState(it) }
            dirtyStates.clear()
        }

        suspend fun prepareStates() {
            store.syncStates().forEach { states[it.typeId] = it }
            val grantedIds = grants.types.mapTo(HashSet()) { it.id }
            // Types no longer granted: drop their cursors, keep their rows (read-only history).
            val revoked = states.keys.filter { it !in grantedIds }
            if (revoked.isNotEmpty()) {
                store.clearSyncState(revoked)
                revoked.forEach(states::remove)
            }
            for (type in grants.types) {
                val s = state(type)
                if (grants.history && !s.backfillWithHistory && s.cursor != null) {
                    // History newly granted: re-run only the older window; keep what we have.
                    update(type) { it.copy(earliestProbeMs = null, backfillDone = false, backfillWithHistory = true, backfillFloorMs = null, status = HealthSyncState.STATUS_IMPORTING) }
                } else if (!grants.history && s.backfillWithHistory) {
                    update(type) { it.copy(backfillWithHistory = false) }
                }
            }
        }

        // -- Initial window ---------------------------------------------------

        /**
         * Bootstraps newly granted types and resumes interrupted bootstraps: a type whose token
         * exists but whose 30-day window never completed (`oldestBackfilledMs == null`) re-reads
         * the window with the same token; a no-History type whose walk-back stopped resumes it.
         */
        suspend fun initialWindows() {
            for (type in grants.types) {
                val s = state(type)
                val needsWindow = s.cursor == null || s.oldestBackfilledMs == null
                val needsWalkBack = !grants.history && !s.backfillDone
                if (!needsWindow && !needsWalkBack) continue
                try {
                    initialWindow(type)
                } catch (e: HealthReadFailedException) {
                    recordError(type, e)
                }
            }
        }

        private suspend fun initialWindow(type: HealthDataType) {
            if (state(type).cursor == null) {
                val token = call { source.changesToken(type) }
                if (token == null) {
                    recordError(type, HealthReadFailedException(IllegalStateException("changes token unavailable")))
                    return
                }
                update(type) { it.copy(cursor = token, cursorIssuedMs = now, status = HealthSyncState.STATUS_BOOTSTRAPPING) }
            }
            if (state(type).oldestBackfilledMs == null) {
                // Persist the token together with the first page (or alone when the window is empty).
                val windowStart = now - policy.initialWindowDays * DAY_MS
                var committedToken = false
                val days = readWindow(type, windowStart, now + HOUR_MS) { rows, series, sources ->
                    store.commit(HealthPageCommit(rows, series, sources, syncStates = if (committedToken) emptyList() else listOf(state(type))))
                    committedToken = true
                }
                if (!committedToken) store.putSyncState(state(type))
                update(type) {
                    it.copy(
                        oldestBackfilledMs = windowStart,
                        lastSyncMs = now,
                        backfillWithHistory = grants.history,
                        status = if (grants.history) HealthSyncState.STATUS_IMPORTING else HealthSyncState.STATUS_IDLE
                    )
                }
                typesTouched += type.id
                rollup(type, days)
            }
            if (!grants.history && !state(type).backfillDone) walkBackWithoutHistory(type)
        }

        /**
         * Without History the readable window is 30 days before the *first* grant; find its edge.
         * One ascending probe over everything older decides whether a walk is needed at all: the
         * platform's boundary error makes the current oldest point the floor (`limited`), no data
         * finishes immediately, and a hit bounds the chunked walk.
         */
        private suspend fun walkBackWithoutHistory(type: HealthDataType) {
            var chunkEnd = state(type).oldestBackfilledMs ?: return
            val floorLimit = now - policy.maxHistoryDays * DAY_MS
            if (chunkEnd <= floorLimit) {
                update(type) { it.copy(backfillFloorMs = floorLimit, backfillDone = true, status = HealthSyncState.STATUS_IDLE) }
                return
            }
            val target = try {
                when (val probe = call { source.earliestRecordTime(type, floorLimit, chunkEnd) }) {
                    is HealthProbe.Found -> maxOf(probe.ms, floorLimit)
                    HealthProbe.NoData -> {
                        update(type) { it.copy(backfillFloorMs = floorLimit, backfillDone = true, status = HealthSyncState.STATUS_IDLE) }
                        return
                    }
                    HealthProbe.Failed -> floorLimit
                }
            } catch (_: HealthReadBoundaryException) {
                update(type) { it.copy(backfillFloorMs = chunkEnd, backfillDone = true, status = HealthSyncState.STATUS_LIMITED) }
                return
            }
            while (chunkEnd > target) {
                val chunkStart = maxOf(chunkEnd - policy.chunkDays * DAY_MS, target)
                try {
                    val days = readWindow(type, chunkStart, chunkEnd) { rows, series, sources ->
                        store.commit(HealthPageCommit(rows, series, sources))
                    }
                    update(type) { it.copy(oldestBackfilledMs = chunkStart) }
                    rollup(type, days)
                    chunkEnd = chunkStart
                } catch (_: HealthReadBoundaryException) {
                    // The chunk before [chunkEnd] is outside the readable window: chunkEnd is the floor.
                    update(type) { it.copy(backfillFloorMs = chunkEnd, backfillDone = true, status = HealthSyncState.STATUS_LIMITED) }
                    return
                }
            }
            update(type) { it.copy(backfillFloorMs = target, backfillDone = true, status = HealthSyncState.STATUS_IDLE) }
        }

        /** `limited` survives later phases; everything else settles to `idle`. */
        private fun settled(s: HealthSyncState): String =
            if (s.status == HealthSyncState.STATUS_LIMITED) HealthSyncState.STATUS_LIMITED else HealthSyncState.STATUS_IDLE

        // -- Incremental drain ------------------------------------------------

        suspend fun drainChanges() {
            for (type in grants.types) {
                val s = state(type)
                val token = s.cursor ?: continue
                // An incomplete bootstrap re-reads its window next run; the token still covers it.
                if (s.status == HealthSyncState.STATUS_BOOTSTRAPPING || s.oldestBackfilledMs == null) continue
                try {
                    drain(type, token)
                } catch (e: HealthReadFailedException) {
                    recordError(type, e)
                }
            }
        }

        private suspend fun drain(type: HealthDataType, startToken: String) {
            var token = startToken
            val dirtyDays = LinkedHashSet<String>()
            while (true) {
                val page = call { source.changes(token) }
                if (page.expired) {
                    reseedAfterExpiredToken(type)
                    return
                }
                val deletedRows = if (page.deletedIds.isNotEmpty()) store.samplesByIds(page.deletedIds) else emptyList()
                val rows = page.upserts.flatMap { it.allRows }
                val series = page.upserts.flatMap { it.series }
                val sources = page.upserts.mapNotNull { it.source }.distinctBy { it.id }
                val next = page.nextToken ?: token
                update(type) { it.copy(cursor = next, cursorIssuedMs = now, lastSyncMs = now, status = settled(it)) }
                val result = store.commit(
                    HealthPageCommit(rows, series, sources, syncStates = listOf(state(type)), deletedIds = page.deletedIds)
                )
                rowsChanged += result.changedIds
                rows.forEach { dirtyDays += it.localDay }
                deletedRows.forEach { dirtyDays += it.localDay }
                // Stage rows of a deleted sleep session share the parent's id prefix.
                if (page.deletedIds.isNotEmpty()) {
                    val stageIds = page.deletedIds.flatMap { id -> (0 until 64).map { "$id:$it" } }
                    val stages = store.samplesByIds(stageIds)
                    if (stages.isNotEmpty()) {
                        store.commit(HealthPageCommit(deletedIds = stages.map { it.id }))
                        stages.forEach { dirtyDays += it.localDay }
                    }
                }
                token = next
                if (!page.hasMore) break
            }
            typesTouched += type.id
            if (dirtyDays.isNotEmpty()) rollup(type, dirtyDays)
        }

        private suspend fun reseedAfterExpiredToken(type: HealthDataType) {
            val previousSync = state(type).lastSyncMs
            val token = call { source.changesToken(type) }
            if (token == null) {
                update(type) { it.copy(cursor = null, status = HealthSyncState.STATUS_ERROR_PREFIX + "token") }
                return
            }
            update(type) { it.copy(cursor = token, cursorIssuedMs = now) }
            val overlapStart = previousSync?.minus(DAY_MS) ?: (now - policy.initialWindowDays * DAY_MS)
            var committedToken = false
            val dirty = LinkedHashSet<String>()
            var chunkStart = overlapStart
            while (chunkStart < now + HOUR_MS) {
                val chunkEnd = minOf(chunkStart + policy.chunkDays * DAY_MS, now + HOUR_MS)
                dirty += readWindow(type, chunkStart, chunkEnd) { rows, series, sources ->
                    store.commit(HealthPageCommit(rows, series, sources, syncStates = if (committedToken) emptyList() else listOf(state(type))))
                    committedToken = true
                }
                chunkStart = chunkEnd
            }
            if (!committedToken) store.putSyncState(state(type))
            update(type) { it.copy(lastSyncMs = now, status = settled(it)) }
            typesTouched += type.id
            rollup(type, dirty)
        }

        // -- All-history backfill -------------------------------------------

        suspend fun backfillHistory() {
            if (!grants.history) return
            val pending = grants.types.filter { type ->
                val s = state(type)
                s.cursor != null && !s.backfillDone
            }.toMutableList()
            if (pending.isEmpty()) return
            // Probe floors first, one IPC per type without a floor.
            val iterator = pending.listIterator()
            while (iterator.hasNext()) {
                val type = iterator.next()
                val s = state(type)
                if (s.backfillFloorMs != null) continue
                when (val probe = call { source.earliestRecordTime(type, now - policy.maxHistoryDays * DAY_MS, now) }) {
                    is HealthProbe.Found -> update(type) {
                        it.copy(earliestProbeMs = probe.ms, backfillFloorMs = maxOf(probe.ms, now - policy.maxHistoryDays * DAY_MS), status = HealthSyncState.STATUS_IMPORTING)
                    }
                    HealthProbe.NoData -> {
                        update(type) { it.copy(backfillDone = true, backfillFloorMs = it.oldestBackfilledMs ?: now, status = HealthSyncState.STATUS_IDLE) }
                        iterator.remove()
                    }
                    HealthProbe.Failed -> iterator.remove()
                }
            }
            // Round-robin one chunk per type per pass until every floor is reached or the budget ends.
            var progressed = true
            while (pending.isNotEmpty() && progressed) {
                progressed = false
                val passIterator = pending.listIterator()
                while (passIterator.hasNext()) {
                    val type = passIterator.next()
                    try {
                        val done = backfillChunk(type)
                        progressed = true
                        if (done) passIterator.remove()
                    } catch (e: HealthReadFailedException) {
                        recordError(type, e)
                        passIterator.remove()
                    } catch (_: HealthReadBoundaryException) {
                        // Even with History the platform may refuse the oldest window: stop there.
                        update(type) { it.copy(backfillDone = true, backfillFloorMs = it.oldestBackfilledMs, status = HealthSyncState.STATUS_IDLE) }
                        passIterator.remove()
                    }
                }
            }
            backfillPending = pending.isNotEmpty()
        }

        /** Reads one chunk downwards; returns true when the floor is reached. */
        private suspend fun backfillChunk(type: HealthDataType): Boolean {
            val s = state(type)
            val floor = s.backfillFloorMs ?: return true
            val chunkEnd = s.oldestBackfilledMs ?: (now - policy.initialWindowDays * DAY_MS)
            if (chunkEnd <= floor) {
                update(type) { it.copy(backfillDone = true, status = HealthSyncState.STATUS_IDLE) }
                return true
            }
            val chunkStart = maxOf(chunkEnd - policy.chunkDays * DAY_MS, floor)
            var rowsInChunk = 0
            val days = readWindow(type, chunkStart, chunkEnd) { rows, series, sources ->
                rowsInChunk += rows.size
                store.commit(HealthPageCommit(rows, series, sources))
            }
            typesTouched += type.id
            if (rowsInChunk == 0 && chunkStart > floor) {
                // Empty chunk: jump the cursor to the newest record below it instead of walking gaps.
                when (val probe = call { source.latestRecordTime(type, floor, chunkStart) }) {
                    is HealthProbe.Found -> update(type) { it.copy(oldestBackfilledMs = maxOf(floor, probe.ms + 1)) }
                    HealthProbe.NoData -> update(type) { it.copy(oldestBackfilledMs = floor, backfillDone = true, status = HealthSyncState.STATUS_IDLE) }
                    HealthProbe.Failed -> update(type) { it.copy(oldestBackfilledMs = chunkStart) }
                }
            } else {
                update(type) { it.copy(oldestBackfilledMs = chunkStart) }
                rollup(type, days)
            }
            val reached = (state(type).oldestBackfilledMs ?: Long.MAX_VALUE) <= floor
            if (reached) update(type) { it.copy(backfillDone = true, status = HealthSyncState.STATUS_IDLE) }
            return reached
        }

        // -- Reading ----------------------------------------------------------

        /** Pages `[fromMs, toMs)` ascending; commits via [onPage]; returns the local days touched. */
        private suspend fun readWindow(
            type: HealthDataType,
            fromMs: Long,
            toMs: Long,
            onPage: suspend (rows: List<HealthSampleRow>, series: List<com.ayuvo.health.data.health.HealthSeriesPoint>, sources: List<com.ayuvo.health.data.health.HealthSourceRow>) -> Unit
        ): Set<String> {
            if (toMs <= fromMs) return emptySet()
            val days = LinkedHashSet<String>()
            var pageToken: String? = null
            do {
                val page = call { source.readPage(type, fromMs, toMs, pageToken, policy.pageSize, ascending = true) }
                if (page.records.isNotEmpty()) {
                    val rows = page.records.flatMap { it.allRows }
                    val series = page.records.flatMap { it.series }
                    val sources = page.records.mapNotNull { it.source }.distinctBy { it.id }
                    onPage(rows, series, sources)
                    rowsChanged += rows.size
                    rows.forEach { days += it.localDay }
                }
                pageToken = page.nextPageToken
            } while (!pageToken.isNullOrEmpty())
            return days
        }

        // -- Roll-ups ---------------------------------------------------------

        suspend fun alwaysRecentDays() {
            val today = LocalDate.now(clock.withZone(zone))
            val recent = setOf(today.toString(), today.minusDays(1).toString())
            for (type in grants.types) {
                if (state(type).cursor == null) continue
                try {
                    rollup(type, recent)
                    hourlyToday(type, today)
                } catch (e: HealthReadFailedException) {
                    recordError(type, e)
                }
            }
        }

        private suspend fun rollup(type: HealthDataType, days: Set<String>) {
            if (days.isEmpty()) return
            val already = rolledUpDays.getOrPut(type.id) { HashSet() }
            val fresh = days.filter { it !in already }.toSet()
            if (fresh.isEmpty()) return
            already += fresh
            val tz = zone.id
            val descriptor = descriptor(type)
            val sortedDays = fresh.mapNotNull { runCatching { LocalDate.parse(it) }.getOrNull() }.sorted()
            if (sortedDays.isEmpty()) return
            val rows = rowsForDays(type, sortedDays.first(), sortedDays.last()).filter { it.localDay in fresh }
            val local = HealthRollupMath.rebuildDaily(descriptor, rows, tz).associateBy { it.day }.toMutableMap()

            if (type.usesPlatformAggregate) {
                val floor = state(type).backfillFloorMs?.takeIf { !state(type).backfillWithHistory }
                val windows = windowsOf(sortedDays, policy.aggregateWindowDays.toInt())
                for ((from, to) in windows) {
                    val clampedFrom = if (floor != null) maxOf(from, Instant.ofEpochMilli(floor).atZone(zone).toLocalDate()) else from
                    if (clampedFrom.isAfter(to)) continue
                    val totals = call { source.aggregateDaily(type, clampedFrom, to, ownOriginOnly = false) } ?: continue
                    val own = if (type == HealthDataType.ACTIVE_ENERGY) {
                        call { source.aggregateDaily(type, clampedFrom, to, ownOriginOnly = true) }
                    } else null
                    for ((day, total) in totals) {
                        val key = day.toString()
                        if (key !in fresh) continue
                        val base = local[key] ?: HealthDailyRollup(type.id, key, tz)
                        local[key] = base.copy(
                            sum = total,
                            durationS = if (descriptor.isDurationLike) total else base.durationS,
                            avg = if (base.count > 0) total / base.count else total,
                            lastValue = base.lastValue ?: total,
                            ownSum = own?.get(day),
                            fromPlatformAggregate = true
                        )
                    }
                }
            }
            store.replaceDailyRollups(type.id, fresh, local.values.toList())
            if (type == HealthDataType.NUTRITION_RECORD) {
                val virtual = HealthRollupMath.virtualDietary(rows, tz)
                for ((dietary, rollups) in virtual) {
                    store.replaceDailyRollups(dietary.id, fresh, rollups.filter { it.day in fresh })
                }
            }
        }

        private suspend fun hourlyToday(type: HealthDataType, today: LocalDate) {
            val day = today.toString()
            if (type.usesPlatformAggregate) {
                val hourly = call { source.aggregateHourly(type, today) } ?: return
                store.replaceHourlyRollups(
                    type.id, day,
                    hourly.map { (hour, value) -> HealthHourlyRollup(type.id, day, hour, sum = value, avg = value, min = value, max = value, count = 1) }
                )
            } else {
                val rows = rowsForDays(type, today, today).filter { it.localDay == day }
                store.replaceHourlyRollups(type.id, day, HealthRollupMath.rebuildHourly(descriptor(type), rows, day, zone))
            }
        }

        private suspend fun rowsForDays(type: HealthDataType, first: LocalDate, last: LocalDate): List<HealthSampleRow> {
            val from = first.minusDays(2).atStartOfDay(zone).toInstant().toEpochMilli()
            val to = last.plusDays(2).atStartOfDay(zone).toInstant().toEpochMilli()
            return store.samplesBetween(type.id, from, to)
        }

        private fun windowsOf(days: List<LocalDate>, maxDays: Int): List<Pair<LocalDate, LocalDate>> {
            val out = mutableListOf<Pair<LocalDate, LocalDate>>()
            var start = days.first()
            var end = start
            for (d in days.drop(1)) {
                if (java.time.temporal.ChronoUnit.DAYS.between(start, d) >= maxDays) {
                    out += start to end
                    start = d
                }
                end = d
            }
            out += start to end
            return out
        }

        // -- Maintenance -----------------------------------------------------

        suspend fun pruneSeries() {
            store.pruneSeriesBefore(now - policy.seriesRetentionDays * DAY_MS)
        }

        suspend fun localRows() {
            val adapter = localSources ?: return
            rowsChanged += runCatching { adapter.contribute(store, now) }
                .onFailure { log("Local health rows failed: ${it.javaClass.simpleName}") }
                .getOrDefault(0)
        }

        private fun recordError(type: HealthDataType, e: Throwable) {
            log("Health sync ${type.id} failed: ${e.javaClass.simpleName}")
            update(type) {
                it.copy(status = HealthSyncState.STATUS_ERROR_PREFIX + (e.cause ?: e).javaClass.simpleName, lastError = e.javaClass.simpleName, lastErrorMs = now)
            }
        }
    }

    private fun statusFrom(states: List<HealthSyncState>, running: Boolean, phaseOverride: HealthSyncPhase?): HealthSyncStatus {
        val now = clock.millis()
        val withFloor = states.filter { it.backfillWithHistory && it.backfillFloorMs != null && !it.backfillDone }
        val progress = if (withFloor.isNotEmpty()) {
            val done = withFloor.sumOf { (now - (it.oldestBackfilledMs ?: now)).coerceAtLeast(0L).toDouble() }
            val total = withFloor.sumOf { (now - it.backfillFloorMs!!).coerceAtLeast(1L).toDouble() }
            (done / total).toFloat().coerceIn(0f, 1f)
        } else null
        val importing = withFloor.isNotEmpty()
        val zoneNow = zone()
        val fromYear = withFloor.minOfOrNull { it.backfillFloorMs!! }?.let { Instant.ofEpochMilli(it).atZone(zoneNow).year }
        val limited = states.filter { !it.backfillWithHistory && it.backfillFloorMs != null }.minOfOrNull { it.backfillFloorMs!! }
        return HealthSyncStatus(
            phase = phaseOverride ?: when {
                running -> HealthSyncPhase.SYNCING
                importing -> HealthSyncPhase.IMPORTING_HISTORY
                else -> HealthSyncPhase.IDLE
            },
            running = running,
            lastSyncMs = states.mapNotNull { it.lastSyncMs }.maxOrNull(),
            typeCount = states.count { it.cursor != null },
            progress = progress,
            progressFromYear = fromYear,
            progressToYear = if (importing) Instant.ofEpochMilli(now).atZone(zoneNow).year else null,
            historyLimitedBeforeMs = limited,
            lastError = states.firstOrNull { it.status.startsWith(HealthSyncState.STATUS_ERROR_PREFIX) }?.lastError
        )
    }

    private companion object {
        const val DAY_MS = 86_400_000L
        const val HOUR_MS = 3_600_000L
    }
}

/** Local day helper shared by the engine and tests. */
internal fun localDayKey(ms: Long, zone: ZoneId): String = HealthDayKeys.dayOf(ms, null, zone).toString()
