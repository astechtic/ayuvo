package com.ayuvo.health.services.health

import com.ayuvo.health.data.health.HealthSyncState
import com.ayuvo.health.data.health.InMemoryHealthDataStore
import com.ayuvo.health.models.HealthDataType
import com.ayuvo.health.services.health.FakeHealthReadSource.Companion.mapped
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class HealthSyncEngineTest {
    private val now = Instant.parse("2026-09-14T12:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val nowMs = now.toEpochMilli()
    private val day = 86_400_000L

    private val source = FakeHealthReadSource()
    private val store = InMemoryHealthDataStore()
    private val prefs = FakeHealthSyncPrefs()

    private fun engine(policy: HealthSyncPolicy = HealthSyncPolicy()) = HealthSyncEngine(
        source = source, store = store, prefs = prefs, clock = clock, zone = { ZoneOffset.UTC }, policy = policy
    )

    private fun grants(vararg types: HealthDataType, history: Boolean = false) = HealthGrants(types.toSet(), history)

    private fun daysAgo(n: Int): Long = nowMs - n * day

    // -- Bootstrap ---------------------------------------------------------------

    @Test
    fun tokenIsTakenBeforeFirstReadAndCommittedWithFirstPage() = runBlocking {
        source.add(HealthDataType.STEPS, mapped(HealthDataType.STEPS, "s1", daysAgo(2), 800.0))
        val outcome = engine().sync(HealthSyncTrigger.MANUAL_REFRESH, grants(HealthDataType.STEPS))

        assertTrue(outcome is HealthSyncOutcome.Synced)
        assertEquals("token:steps", source.calls.first())
        assertTrue(source.calls[1].startsWith("read:steps"))
        val firstRowCommit = store.commits.first { it.rows.isNotEmpty() }
        assertEquals("t0", firstRowCommit.syncStates.single().cursor)
        assertEquals(1, store.liveRows("steps").size)
        val state = store.states.getValue("steps")
        assertEquals(daysAgo(30), state.oldestBackfilledMs)
        assertEquals(nowMs, state.lastSyncMs)
        assertEquals(nowMs, prefs.lastSync)
    }

    @Test
    fun initialWindowFollowsPageTokensAndStoresEveryRow() = runBlocking {
        repeat(25) { i -> source.add(HealthDataType.STEPS, mapped(HealthDataType.STEPS, "s$i", daysAgo(1) + i * 60_000L, 10.0)) }
        engine(HealthSyncPolicy(pageSize = 10)).sync(HealthSyncTrigger.MANUAL_REFRESH, grants(HealthDataType.STEPS))

        assertEquals(25, store.liveRows("steps").size)
        assertEquals(3, source.calls.count { it.startsWith("read:steps:${daysAgo(30)}") })
    }

    // -- Incremental drain ------------------------------------------------------

    @Test
    fun drainAppliesUpsertsDeletionsAndAdvancesTheToken() = runBlocking {
        source.add(HealthDataType.WEIGHT, mapped(HealthDataType.WEIGHT, "w1", daysAgo(3), 80.0))
        val e = engine()
        e.sync(HealthSyncTrigger.MANUAL_REFRESH, grants(HealthDataType.WEIGHT))
        assertEquals("t0", store.states.getValue("weight").cursor)

        source.upsertChange(mapped(HealthDataType.WEIGHT, "w2", daysAgo(1), 79.5))
        source.deleteChange("w1")
        prefs.lastSync = null
        val outcome = e.sync(HealthSyncTrigger.MANUAL_REFRESH, grants(HealthDataType.WEIGHT))

        assertTrue(outcome is HealthSyncOutcome.Synced)
        assertEquals("t2", store.states.getValue("weight").cursor)
        assertEquals(listOf("w2"), store.liveRows("weight").map { it.id })
        assertTrue(store.samples.getValue("w1").deleted)
        // The drain commit carried the page and the new cursor together.
        val drainCommit = store.commits.last { it.deletedIds.isNotEmpty() || it.rows.any { r -> r.id == "w2" } }
        assertEquals("t2", drainCommit.syncStates.single().cursor)
    }

    @Test
    fun deletionsNeverTombstoneImportedRowsAndTombstonesWinOverReimport() = runBlocking {
        val e = engine()
        source.add(HealthDataType.WEIGHT, mapped(HealthDataType.WEIGHT, "w1", daysAgo(3), 80.0))
        e.sync(HealthSyncTrigger.MANUAL_REFRESH, grants(HealthDataType.WEIGHT))
        // An imported row with the same id shape is immune to platform deletions.
        store.commit(
            com.ayuvo.health.data.health.HealthPageCommit(
                rows = listOf(mapped(HealthDataType.WEIGHT, "imp1", daysAgo(5), 81.0).row.copy(origin = 1))
            )
        )
        source.deleteChange("imp1")
        source.deleteChange("w1")
        prefs.lastSync = null
        e.sync(HealthSyncTrigger.MANUAL_REFRESH, grants(HealthDataType.WEIGHT))
        assertFalse(store.samples.getValue("imp1").deleted)
        assertTrue(store.samples.getValue("w1").deleted)

        // A later re-import of w1 with a newer updated_ms cannot resurrect the tombstone.
        store.commit(
            com.ayuvo.health.data.health.HealthPageCommit(
                rows = listOf(mapped(HealthDataType.WEIGHT, "w1", daysAgo(3), 80.0, updatedMs = nowMs + 1).row)
            )
        )
        assertTrue(store.samples.getValue("w1").deleted)
    }

    @Test
    fun newerUpdatedWinsAndOlderIsIgnored() = runBlocking {
        val e = engine()
        source.add(HealthDataType.WEIGHT, mapped(HealthDataType.WEIGHT, "w1", daysAgo(3), 80.0, updatedMs = daysAgo(3)))
        e.sync(HealthSyncTrigger.MANUAL_REFRESH, grants(HealthDataType.WEIGHT))
        source.upsertChange(mapped(HealthDataType.WEIGHT, "w1", daysAgo(3), 79.0, updatedMs = daysAgo(1)))
        source.upsertChange(mapped(HealthDataType.WEIGHT, "w1", daysAgo(3), 85.0, updatedMs = daysAgo(2)))
        prefs.lastSync = null
        e.sync(HealthSyncTrigger.MANUAL_REFRESH, grants(HealthDataType.WEIGHT))
        assertEquals(79.0, store.samples.getValue("w1").value!!, 0.0)
    }

    @Test
    fun expiredTokenReseedsWithOverlapWithoutDuplicates() = runBlocking {
        val e = engine()
        source.add(HealthDataType.WEIGHT, mapped(HealthDataType.WEIGHT, "w1", daysAgo(3), 80.0))
        e.sync(HealthSyncTrigger.MANUAL_REFRESH, grants(HealthDataType.WEIGHT))
        source.expiredTokens += "t0"
        source.add(HealthDataType.WEIGHT, mapped(HealthDataType.WEIGHT, "w2", daysAgo(0) - 3_600_000L, 79.0))
        prefs.lastSync = null
        source.calls.clear()

        e.sync(HealthSyncTrigger.MANUAL_REFRESH, grants(HealthDataType.WEIGHT))

        assertEquals(listOf("changes:t0", "token:weight"), source.calls.take(2))
        assertTrue(source.calls.any { it.startsWith("read:weight:${daysAgo(1)}") })
        assertEquals(2, store.liveRows("weight").size)
        assertEquals("t0", store.states.getValue("weight").cursor)
        assertEquals(HealthSyncState.STATUS_IDLE, store.states.getValue("weight").status)
    }

    // -- Grants -------------------------------------------------------------------

    @Test
    fun revokedTypeKeepsRowsButLosesSyncState() = runBlocking {
        val e = engine()
        source.add(HealthDataType.STEPS, mapped(HealthDataType.STEPS, "s1", daysAgo(1), 100.0))
        source.add(HealthDataType.WEIGHT, mapped(HealthDataType.WEIGHT, "w1", daysAgo(1), 80.0))
        e.sync(HealthSyncTrigger.MANUAL_REFRESH, grants(HealthDataType.STEPS, HealthDataType.WEIGHT))
        prefs.lastSync = null
        e.sync(HealthSyncTrigger.MANUAL_REFRESH, grants(HealthDataType.STEPS))
        assertNull(store.states["weight"])
        assertNotNull(store.states["steps"])
        assertEquals(1, store.liveRows("weight").size)
    }

    @Test
    fun emptyGrantsIsPermissionsResetAndNothingIsCleared() = runBlocking {
        val e = engine()
        source.add(HealthDataType.STEPS, mapped(HealthDataType.STEPS, "s1", daysAgo(1), 100.0))
        e.sync(HealthSyncTrigger.MANUAL_REFRESH, grants(HealthDataType.STEPS))
        prefs.lastSync = null
        val outcome = e.sync(HealthSyncTrigger.MANUAL_REFRESH, HealthGrants(emptySet(), false))
        assertEquals(HealthSyncOutcome.PermissionsReset, outcome)
        assertEquals(1, store.liveRows("steps").size)
        assertNotNull(store.states["steps"])
        assertEquals(HealthSyncPhase.PERMISSIONS_RESET, e.status.value.phase)
    }

    @Test
    fun probeFailureAndHubDisabledSkipWithoutTouchingTheStore() = runBlocking {
        val e = engine()
        assertEquals(HealthSyncOutcome.Skipped(HealthSyncOutcome.Skipped.Reason.PROBE_FAILED), e.sync(HealthSyncTrigger.MANUAL_REFRESH, null))
        prefs.hub = false
        assertEquals(HealthSyncOutcome.Skipped(HealthSyncOutcome.Skipped.Reason.HUB_DISABLED), e.sync(HealthSyncTrigger.MANUAL_REFRESH, grants(HealthDataType.STEPS)))
        assertTrue(store.commits.isEmpty())
        assertTrue(source.calls.isEmpty())
    }

    // -- Without History -------------------------------------------------------------

    @Test
    fun withoutHistoryTheBoundaryErrorBecomesTheFloor() = runBlocking {
        source.boundaryBeforeMs = daysAgo(45)
        source.add(HealthDataType.STEPS, mapped(HealthDataType.STEPS, "s1", daysAgo(1), 100.0))
        engine().sync(HealthSyncTrigger.MANUAL_REFRESH, grants(HealthDataType.STEPS, history = false))
        val state = store.states.getValue("steps")
        assertTrue(state.backfillDone)
        assertFalse(state.backfillWithHistory)
        // The 30-day window read fine; the next chunk back errored, so the floor is its end.
        assertEquals(daysAgo(30), state.backfillFloorMs)
        assertEquals(HealthSyncState.STATUS_LIMITED, state.status)
        assertEquals(daysAgo(30), engine().let { e -> e.refreshStatus(); e.status.value.historyLimitedBeforeMs })
    }

    // -- All-history backfill ----------------------------------------------------------

    @Test
    fun withHistoryBackfillWalksToTheProbedFloorRoundRobin() = runBlocking {
        source.add(HealthDataType.STEPS, mapped(HealthDataType.STEPS, "s-old", daysAgo(100), 500.0))
        source.add(HealthDataType.STEPS, mapped(HealthDataType.STEPS, "s-new", daysAgo(1), 100.0))
        source.add(HealthDataType.WEIGHT, mapped(HealthDataType.WEIGHT, "w-old", daysAgo(70), 82.0))
        source.add(HealthDataType.WEIGHT, mapped(HealthDataType.WEIGHT, "w-new", daysAgo(2), 80.0))

        val outcome = engine().sync(HealthSyncTrigger.MANUAL_REFRESH, grants(HealthDataType.STEPS, HealthDataType.WEIGHT, history = true))

        assertEquals(HealthSyncOutcome.Synced(moreWork = false, typesSynced = 2, rowsChanged = 4), outcome)
        val steps = store.states.getValue("steps")
        assertTrue(steps.backfillDone)
        assertTrue(steps.backfillWithHistory)
        assertEquals(daysAgo(100), steps.backfillFloorMs)
        assertTrue(steps.oldestBackfilledMs!! <= daysAgo(100))
        assertEquals(2, store.liveRows("steps").size)
        assertEquals(2, store.liveRows("weight").size)
        // Round robin: a weight chunk is read between two step chunks.
        val chunkReads = source.calls.filter { it.startsWith("read:") && !it.startsWith("read:steps:${daysAgo(30)}") && !it.startsWith("read:weight:${daysAgo(30)}") }
        assertTrue(chunkReads.size >= 3)
        assertTrue(chunkReads.indexOfFirst { it.startsWith("read:weight") } in 1 until chunkReads.lastIndex + 1)
    }

    @Test
    fun emptyChunkJumpsToTheNewestRecordBelowIt() = runBlocking {
        source.add(HealthDataType.STEPS, mapped(HealthDataType.STEPS, "s-ancient", daysAgo(400), 500.0))
        source.add(HealthDataType.STEPS, mapped(HealthDataType.STEPS, "s-new", daysAgo(1), 100.0))

        engine().sync(HealthSyncTrigger.MANUAL_REFRESH, grants(HealthDataType.STEPS, history = true))

        val state = store.states.getValue("steps")
        assertTrue(state.backfillDone)
        assertEquals(2, store.liveRows("steps").size)
        // One initial window, one empty chunk, one descending probe, one chunk containing the record: no 12-chunk walk.
        val reads = source.calls.count { it.startsWith("read:steps") }
        assertTrue("expected a gap jump, got $reads reads", reads <= 4)
        assertEquals(1, source.calls.count { it == "probeDesc:steps" })
    }

    @Test
    fun historyGrantedLaterReRunsOnlyTheOlderWindow() = runBlocking {
        source.boundaryBeforeMs = daysAgo(45)
        source.add(HealthDataType.STEPS, mapped(HealthDataType.STEPS, "s-new", daysAgo(1), 100.0))
        source.add(HealthDataType.STEPS, mapped(HealthDataType.STEPS, "s-old", daysAgo(200), 300.0))
        val e = engine()
        e.sync(HealthSyncTrigger.MANUAL_REFRESH, grants(HealthDataType.STEPS, history = false))
        assertEquals(1, store.liveRows("steps").size)

        source.boundaryBeforeMs = null
        prefs.lastSync = null
        source.calls.clear()
        e.sync(HealthSyncTrigger.PERMISSIONS_CHANGED, grants(HealthDataType.STEPS, history = true))

        val state = store.states.getValue("steps")
        assertTrue(state.backfillWithHistory)
        assertTrue(state.backfillDone)
        assertEquals(2, store.liveRows("steps").size)
        // The recent window was not re-read; no second token was taken.
        assertFalse(source.calls.any { it.startsWith("read:steps:${daysAgo(30)}") })
        assertFalse(source.calls.any { it == "token:steps" })
    }

    // -- Roll-ups -------------------------------------------------------------------

    @Test
    fun sumTypesUsePlatformAggregatesOthersLocalMath() = runBlocking {
        source.add(HealthDataType.STEPS, mapped(HealthDataType.STEPS, "s1", daysAgo(1), 100.0))
        source.add(HealthDataType.STEPS, mapped(HealthDataType.STEPS, "s2", daysAgo(1) + 60_000L, 200.0))
        source.add(HealthDataType.HEART_RATE, mapped(HealthDataType.HEART_RATE, "h1", daysAgo(1), 60.0, count = 4, value2 = 50.0, value3 = 90.0))
        source.add(HealthDataType.HEART_RATE, mapped(HealthDataType.HEART_RATE, "h2", daysAgo(1) + 60_000L, 80.0, count = 2, value2 = 70.0, value3 = 100.0))
        // Platform dedupes phone + watch: it reports fewer steps than the raw rows sum to.
        source.aggregateOverride = { type, from, to, own ->
            if (type == HealthDataType.STEPS && !own) mapOf(LocalDateOf(daysAgo(1)) to 250.0) else emptyMap()
        }

        engine().sync(HealthSyncTrigger.MANUAL_REFRESH, grants(HealthDataType.STEPS, HealthDataType.HEART_RATE))

        val yesterday = LocalDateOf(daysAgo(1)).toString()
        val steps = store.daily.getValue("steps" to yesterday)
        assertTrue(steps.fromPlatformAggregate)
        assertEquals(250.0, steps.sum!!, 0.0)
        assertEquals(2, steps.count)
        val hr = store.daily.getValue("heart_rate" to yesterday)
        assertFalse(hr.fromPlatformAggregate)
        assertEquals((60.0 * 4 + 80.0 * 2) / 6, hr.avg!!, 1e-9)
        assertEquals(50.0, hr.min!!, 0.0)
        assertEquals(100.0, hr.max!!, 0.0)
        assertEquals(6, hr.count)
    }

    @Test
    fun activeEnergyRollupCarriesOwnSum() = runBlocking {
        source.add(HealthDataType.ACTIVE_ENERGY, mapped(HealthDataType.ACTIVE_ENERGY, "a1", daysAgo(1), 300.0))
        source.aggregateOverride = { type, _, _, own ->
            if (type == HealthDataType.ACTIVE_ENERGY) mapOf(LocalDateOf(daysAgo(1)) to if (own) 120.0 else 420.0) else emptyMap()
        }
        engine().sync(HealthSyncTrigger.MANUAL_REFRESH, grants(HealthDataType.ACTIVE_ENERGY))
        val rollup = store.daily.getValue("active_energy" to LocalDateOf(daysAgo(1)).toString())
        assertEquals(420.0, rollup.sum!!, 0.0)
        assertEquals(120.0, rollup.ownSum!!, 0.0)
    }

    // -- Throttles / quota / budget ----------------------------------------------------

    @Test
    fun appOpenAndManualTriggersAreThrottledHubVisibleIsNot() = runBlocking {
        val e = engine()
        source.add(HealthDataType.STEPS, mapped(HealthDataType.STEPS, "s1", daysAgo(1), 100.0))
        assertTrue(e.sync(HealthSyncTrigger.APP_OPEN, grants(HealthDataType.STEPS)) is HealthSyncOutcome.Synced)
        assertEquals(HealthSyncOutcome.Skipped(HealthSyncOutcome.Skipped.Reason.THROTTLED), e.sync(HealthSyncTrigger.APP_OPEN, grants(HealthDataType.STEPS)))
        assertEquals(HealthSyncOutcome.Skipped(HealthSyncOutcome.Skipped.Reason.THROTTLED), e.sync(HealthSyncTrigger.MANUAL_REFRESH, grants(HealthDataType.STEPS)))
        assertTrue(e.sync(HealthSyncTrigger.HUB_VISIBLE, grants(HealthDataType.STEPS)) is HealthSyncOutcome.Synced)
        prefs.lastSync = nowMs - 31_000L
        assertTrue(e.sync(HealthSyncTrigger.MANUAL_REFRESH, grants(HealthDataType.STEPS)) is HealthSyncOutcome.Synced)
        prefs.lastSync = nowMs - 2 * 60_000L
        assertEquals(HealthSyncOutcome.Skipped(HealthSyncOutcome.Skipped.Reason.THROTTLED), e.sync(HealthSyncTrigger.APP_OPEN, grants(HealthDataType.STEPS)))
    }

    @Test
    fun quotaErrorBacksOffForFortyFiveMinutesWithoutDialogs() = runBlocking {
        source.throwQuota = true
        val e = engine()
        val outcome = e.sync(HealthSyncTrigger.MANUAL_REFRESH, grants(HealthDataType.STEPS))
        assertEquals(HealthSyncOutcome.RateLimited(nowMs + 45 * 60_000L), outcome)
        assertEquals(nowMs + 45 * 60_000L, prefs.rateLimitedUntil)
        assertEquals(HealthSyncPhase.RATE_LIMITED, e.status.value.phase)
        source.throwQuota = false
        assertEquals(HealthSyncOutcome.RateLimited(nowMs + 45 * 60_000L), e.sync(HealthSyncTrigger.MANUAL_REFRESH, grants(HealthDataType.STEPS)))
    }

    @Test
    fun exhaustedIpcBudgetReturnsMoreWorkAndResumesNextRun() = runBlocking {
        repeat(6) { i -> source.add(HealthDataType.STEPS, mapped(HealthDataType.STEPS, "s$i", daysAgo(i * 40 + 1), 100.0)) }
        val e = engine(HealthSyncPolicy(ipcBudgetInteractive = 4))
        val first = e.sync(HealthSyncTrigger.MANUAL_REFRESH, grants(HealthDataType.STEPS, history = true))
        assertTrue(first is HealthSyncOutcome.Synced && first.moreWork)
        assertFalse(store.states.getValue("steps").backfillDone)

        prefs.lastSync = null
        val e2 = engine(HealthSyncPolicy(ipcBudgetInteractive = 600))
        val second = e2.sync(HealthSyncTrigger.MANUAL_REFRESH, grants(HealthDataType.STEPS, history = true))
        assertEquals(false, (second as HealthSyncOutcome.Synced).moreWork)
        assertTrue(store.states.getValue("steps").backfillDone)
        assertEquals(6, store.liveRows("steps").size)
    }

    @Test
    fun readFailureIsIsolatedPerType() = runBlocking {
        source.add(HealthDataType.STEPS, mapped(HealthDataType.STEPS, "s1", daysAgo(1), 100.0))
        val e = engine()
        e.sync(HealthSyncTrigger.MANUAL_REFRESH, grants(HealthDataType.STEPS))
        source.failReads = true
        source.add(HealthDataType.WEIGHT, mapped(HealthDataType.WEIGHT, "w1", daysAgo(1), 80.0))
        prefs.lastSync = null
        val outcome = e.sync(HealthSyncTrigger.MANUAL_REFRESH, grants(HealthDataType.STEPS, HealthDataType.WEIGHT))
        assertTrue(outcome is HealthSyncOutcome.Synced)
        assertTrue(store.states.getValue("weight").status.startsWith("error:"))
        assertEquals(1, store.liveRows("steps").size)
    }

    @Test
    fun interruptedBootstrapKeepsItsTokenAndReReadsTheWindowNextRun() = runBlocking {
        source.failReads = true
        source.add(HealthDataType.WEIGHT, mapped(HealthDataType.WEIGHT, "w1", daysAgo(1), 80.0))
        val e = engine()
        e.sync(HealthSyncTrigger.MANUAL_REFRESH, grants(HealthDataType.WEIGHT))
        val failed = store.states.getValue("weight")
        assertEquals("t0", failed.cursor)
        assertNull(failed.oldestBackfilledMs)
        assertTrue(store.liveRows("weight").isEmpty())

        source.failReads = false
        source.calls.clear()
        prefs.lastSync = null
        val outcome = e.sync(HealthSyncTrigger.MANUAL_REFRESH, grants(HealthDataType.WEIGHT))
        assertTrue(outcome is HealthSyncOutcome.Synced)
        assertTrue(source.calls.none { it.startsWith("token:") })
        assertTrue(source.calls.first().startsWith("read:weight:${daysAgo(30)}"))
        assertEquals(1, store.liveRows("weight").size)
        val resumed = store.states.getValue("weight")
        assertEquals("t0", resumed.cursor)
        assertEquals(daysAgo(30), resumed.oldestBackfilledMs)
        assertEquals(HealthSyncState.STATUS_IDLE, resumed.status)
    }

    @Suppress("TestFunctionName")
    private fun LocalDateOf(ms: Long): java.time.LocalDate = Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalDate()
}
