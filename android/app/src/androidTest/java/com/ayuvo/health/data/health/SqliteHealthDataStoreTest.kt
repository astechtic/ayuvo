package com.ayuvo.health.data.health

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SqliteHealthDataStoreTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var helper: HealthDatabase
    private lateinit var store: SqliteHealthDataStore

    @Before
    fun setUp() {
        HealthDatabase.deleteDatabaseFiles(context)
        helper = HealthDatabase(context)
        store = SqliteHealthDataStore(helper)
    }

    @After
    fun tearDown() {
        store.close()
        HealthDatabase.deleteDatabaseFiles(context)
    }

    private fun row(id: String, endMs: Long, value: Double, updatedMs: Long = endMs, origin: Int = 0, type: String = "steps") = HealthSampleRow(
        id = id, typeId = type, startMs = endMs - 60_000, endMs = endMs, localDay = "2026-09-13", value = value, unit = "count",
        sourceId = "com.example.watch", origin = origin, updatedMs = updatedMs
    )

    @Test
    fun upsertKeepsNewerAndIgnoresOlderAndInsertsMissing() = runBlocking {
        val first = store.commit(HealthPageCommit(rows = listOf(row("a", 1_000, 10.0, updatedMs = 5))))
        assertEquals(1, first.inserted)
        val older = store.commit(HealthPageCommit(rows = listOf(row("a", 1_000, 99.0, updatedMs = 4))))
        assertEquals(0, older.updated)
        assertEquals(10.0, store.samplesByIds(listOf("a")).single().value!!, 0.0)
        val newer = store.commit(HealthPageCommit(rows = listOf(row("a", 1_000, 11.0, updatedMs = 6))))
        assertEquals(1, newer.updated)
        assertEquals(11.0, store.samplesByIds(listOf("a")).single().value!!, 0.0)
        assertEquals(3, store.revision.value)
    }

    @Test
    fun tombstoneIsNeverResurrectedAndRespectsOrigins() = runBlocking {
        store.commit(HealthPageCommit(rows = listOf(row("a", 1_000, 10.0), row("imp", 2_000, 20.0, origin = 1))))
        val result = store.commit(HealthPageCommit(deletedIds = listOf("a", "imp")))
        assertEquals(1, result.tombstoned)
        assertTrue(store.samplesByIds(listOf("a")).single().deleted)
        assertFalse(store.samplesByIds(listOf("imp")).single().deleted)
        store.commit(HealthPageCommit(rows = listOf(row("a", 1_000, 42.0, updatedMs = Long.MAX_VALUE / 2))))
        assertTrue(store.samplesByIds(listOf("a")).single().deleted)
        assertEquals(listOf("imp"), store.samplesBetween("steps", 0, Long.MAX_VALUE).map { it.id })
        assertEquals(1L, store.sampleCount("steps"))
    }

    @Test
    fun keysetPagingIsStableAcrossEqualEndTimes() = runBlocking {
        val rows = (0 until 25).map { i -> row("r${i.toString().padStart(2, '0')}", endMs = 1_000 + (i / 5) * 1_000L, value = i.toDouble()) }
        store.commit(HealthPageCommit(rows = rows))
        val seen = mutableListOf<String>()
        var beforeEnd: Long? = null
        var beforeId: String? = null
        while (true) {
            val page = store.samplesPage("steps", beforeEnd, beforeId, 7)
            if (page.isEmpty()) break
            seen += page.map { it.id }
            beforeEnd = page.last().endMs
            beforeId = page.last().id
        }
        assertEquals(25, seen.size)
        assertEquals(25, seen.toSet().size)
        assertEquals(seen.sortedWith(compareByDescending<String> { rows.first { r -> r.id == it }.endMs }.thenByDescending { it }), seen)
        assertEquals("r24", store.latestSample("steps")!!.id)
    }

    @Test
    fun sourceCountsGroupLiveRowsBySourceNewestSourceFirst() = runBlocking {
        val rows = (0 until 7).map { i -> row("s$i", endMs = 1_000L + i, value = 1.0).copy(sourceId = if (i < 5) "com.example.watch" else "com.example.phone") }
        store.commit(HealthPageCommit(rows = rows + row("other", 9_000, 1.0, type = "weight")))
        store.commit(HealthPageCommit(deletedIds = listOf("s0")))
        val counts = store.sourceCounts("steps")
        assertEquals(listOf("com.example.watch", "com.example.phone"), counts.keys.toList())
        assertEquals(4, counts["com.example.watch"])
        assertEquals(2, counts["com.example.phone"])
        assertTrue(store.sourceCounts("heart_rate").isEmpty())
    }

    @Test
    fun seriesPointsAreRewrittenOnlyWhenTheParentChanges() = runBlocking {
        val points = listOf(HealthSeriesPoint("hr", "heart_rate", 1_000, 60.0), HealthSeriesPoint("hr", "heart_rate", 2_000, 70.0))
        store.commit(HealthPageCommit(rows = listOf(row("hr", 3_000, 65.0, updatedMs = 1, type = "heart_rate")), seriesPoints = points))
        assertEquals(2, store.seriesPoints("heart_rate", 0, 10_000).size)
        // Same updated_ms: the parent is unchanged, the (empty) series must not be rewritten.
        store.commit(HealthPageCommit(rows = listOf(row("hr", 3_000, 65.0, updatedMs = 1, type = "heart_rate"))))
        assertEquals(2, store.seriesPoints("heart_rate", 0, 10_000).size)
        store.commit(HealthPageCommit(rows = listOf(row("hr", 3_000, 66.0, updatedMs = 2, type = "heart_rate")), seriesPoints = points.take(1)))
        assertEquals(1, store.seriesPoints("heart_rate", 0, 10_000).size)
        assertEquals(1, store.pruneSeriesBefore(5_000))
        assertEquals(1, store.typeSummaries().single().count)
    }

    @Test
    fun syncStateRollupsSourcesAndMetaRoundTrip() = runBlocking {
        val state = HealthSyncState("steps", cursor = "tok", cursorIssuedMs = 1, lastSyncMs = 2, backfillFloorMs = 3, oldestBackfilledMs = 4, backfillDone = true, backfillWithHistory = true, status = "idle")
        store.putSyncState(state)
        assertEquals(state, store.syncState("steps"))
        store.clearSyncState(listOf("steps"))
        assertNull(store.syncState("steps"))

        val rollup = HealthDailyRollup("steps", "2026-09-13", "UTC", sum = 100.0, avg = 50.0, min = 20.0, max = 80.0, count = 2, lastValue = 80.0, lastAtMs = 9, ownSum = 12.0, fromPlatformAggregate = true)
        store.replaceDailyRollups("steps", listOf("2026-09-13"), listOf(rollup))
        assertEquals(rollup, store.dailyRollups("steps", "2026-09-01", "2026-09-30").single())
        store.replaceHourlyRollups("steps", "2026-09-13", listOf(HealthHourlyRollup("steps", "2026-09-13", 7, sum = 30.0, count = 1)))
        assertEquals(7, store.hourlyRollups("steps", "2026-09-13").single().hour)

        store.upsertSources(listOf(HealthSourceRow("com.example.watch", "Watch", deviceModel = "Band", deviceType = 2, lastSeenMs = 10)))
        store.upsertSources(listOf(HealthSourceRow("com.example.watch", "Watch 2", lastSeenMs = 5)))
        val source = store.sources().single()
        assertEquals("Watch 2", source.name)
        assertEquals("Band", source.deviceModel)
        assertEquals(10L, source.lastSeenMs)

        assertEquals("1", store.meta("schema_version"))
        store.setMeta("registry_version", "2")
        assertEquals("2", store.meta("registry_version"))
        store.upsertTypeMeta(listOf(HealthTypeMeta("raw_x", "other", "discrete", "AVERAGE", "none", nativeId = "HKQuantityTypeIdentifierX")))
        assertEquals("HKQuantityTypeIdentifierX", store.typeMeta().single().nativeId)
    }

    @Test
    fun deleteAllClearsRowsKeepsSchemaAndFilesAreRemovable() = runBlocking {
        store.commit(HealthPageCommit(rows = listOf(row("a", 1_000, 10.0))))
        assertTrue(store.storageBytes() > 0)
        store.deleteAll()
        assertEquals(0L, store.sampleCount("steps"))
        assertEquals("1", store.meta("schema_version"))
        assertTrue(helper.writableDatabase.isWriteAheadLoggingEnabled)
        store.close()
        HealthDatabase.deleteDatabaseFiles(context)
        assertTrue(HealthDatabase.databaseFiles(context).none { it.exists() })
    }
}
