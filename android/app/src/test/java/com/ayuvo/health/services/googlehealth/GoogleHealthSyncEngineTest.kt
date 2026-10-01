package com.ayuvo.health.services.googlehealth

import com.ayuvo.health.data.health.GoogleHealthMirrorEntry
import com.ayuvo.health.data.health.GoogleHealthSyncState
import com.ayuvo.health.data.health.HealthSampleRow
import com.ayuvo.health.data.health.InMemoryHealthDataStore
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/** Cursor/page-token bookkeeping, partial scopes, change detection and mirror candidates. */
class GoogleHealthSyncEngineTest {
    private val map = GoogleHealthTestFiles.map
    private val now = Instant.parse("2026-03-10T00:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val zone = ZoneId.of("UTC")
    private val activity = "https://www.googleapis.com/auth/googlehealth.activity_and_fitness.readonly"

    /** Serves scripted pages per gh_type; a page given as an exception is thrown instead. */
    private class FakeSource(val pages: MutableMap<String, ArrayDeque<Any>>) : GoogleHealthSource {
        val calls = mutableListOf<Triple<String, String, String?>>()
        override suspend fun listDataPoints(entry: GoogleHealthMap.TypeEntry, filter: String, pageToken: String?, pageSize: Int): GoogleHealthPage {
            calls += Triple(entry.ghType, filter, pageToken)
            val next = pages[entry.ghType]?.removeFirstOrNull() ?: return GoogleHealthPage(emptyList(), null)
            if (next is Exception) throw next
            return next as GoogleHealthPage
        }
    }

    private fun stepsPoint(id: String, start: String, end: String, count: Int, pkg: String = "com.fitbit.FitbitMobile", platform: String = "FITBIT"): JsonObject =
        Json.parseToJsonElement(
            """{"name":"users/me/dataTypes/steps/dataPoints/$id","dataSource":{"platform":"$platform","application":{"packageName":"$pkg"}},
               "steps":{"interval":{"startTime":"$start","endTime":"$end"},"count":"$count"}}"""
        ).jsonObject

    private fun engine(store: InMemoryHealthDataStore, source: FakeSource) =
        GoogleHealthSyncEngine(map, source, store, clock, { zone })

    private fun request(scopes: Set<String> = setOf(activity), groups: Set<String> = setOf("activity"), writeBack: Boolean = true) =
        GoogleHealthSyncRequest(scopes, groups, writeBack)

    @Test
    fun firstSyncPagesFrom90DaysAndMovesTheCursorWithTheLastPage() = runBlocking {
        val store = InMemoryHealthDataStore()
        val source = FakeSource(
            mutableMapOf(
                "steps" to ArrayDeque(
                    listOf(
                        GoogleHealthPage(listOf(stepsPoint("a", "2026-03-08T10:00:00Z", "2026-03-08T10:15:00Z", 100)), "p2"),
                        GoogleHealthPage(listOf(stepsPoint("b", "2026-03-09T10:00:00Z", "2026-03-09T10:15:00Z", 200)), null)
                    )
                )
            )
        )
        val outcome = engine(store, source).sync(request())
        assertTrue(outcome is GoogleHealthSyncOutcome.Synced)

        val stepsCalls = source.calls.filter { it.first == "steps" }
        assertEquals("steps.interval.civil_start_time >= \"2025-12-10T00:00:00\"", stepsCalls[0].second)
        assertEquals(listOf(null, "p2"), stepsCalls.map { it.third })

        // Page 1 committed its token; the last page cleared it and set the cursor in the same commit.
        val stepsCommits = store.commits.filter { c -> c.googleStates.any { it.ghType == "steps" } }
        assertEquals("p2", stepsCommits[0].googleStates.single().pageToken)
        assertEquals(listOf("gh:a"), stepsCommits[0].rows.map { it.id })
        val final = store.googleStates.getValue("steps")
        assertNull(final.pageToken)
        assertEquals(Instant.parse("2026-03-09T10:15:00Z").toEpochMilli(), final.cursorMs)
        assertEquals(GoogleHealthSyncState.STATUS_IDLE, final.status)
        assertEquals(HealthSampleRow.ORIGIN_GOOGLE_HEALTH, store.samples.getValue("gh:b").origin)
        assertEquals(GoogleHealthMirrorEntry.STATUS_PENDING, store.googleMirror.getValue("gh:b").mirrorStatus)
        assertNotNull(store.daily["steps" to "2026-03-09"])
        assertEquals(200.0, store.daily.getValue("steps" to "2026-03-09").sum!!, 0.0)

        // Types of groups the user did not tick never ran; ungranted scopes are not asked for.
        assertTrue(source.calls.none { it.first == "heart-rate" })
    }

    @Test
    fun laterSyncsOverlapTwoDaysAndResumeAnInterruptedPage() = runBlocking {
        val store = InMemoryHealthDataStore()
        val cursor = Instant.parse("2026-03-09T00:00:00Z").toEpochMilli()
        store.googleStates["steps"] = GoogleHealthSyncState("steps", cursorMs = cursor, pageToken = "resume-me", backfillFloorMs = 0)
        val source = FakeSource(mutableMapOf("steps" to ArrayDeque(listOf(GoogleHealthPage(emptyList(), null)))))
        engine(store, source).sync(request())
        val call = source.calls.first { it.first == "steps" }
        assertEquals("steps.interval.civil_start_time >= \"2026-03-07T00:00:00\"", call.second)
        assertEquals("resume-me", call.third)
        assertEquals(cursor, store.googleStates.getValue("steps").cursorMs)
    }

    @Test
    fun unchangedRefetchIsNotRewritten() = runBlocking {
        val store = InMemoryHealthDataStore()
        val point = stepsPoint("a", "2026-03-08T10:00:00Z", "2026-03-08T10:15:00Z", 100)
        val source = FakeSource(mutableMapOf("steps" to ArrayDeque(listOf(GoogleHealthPage(listOf(point), null), GoogleHealthPage(listOf(point), null)))))
        val e = engine(store, source)
        e.sync(request())
        store.googleMirror["gh:a"] = store.googleMirror.getValue("gh:a").copy(mirrorStatus = GoogleHealthMirrorEntry.STATUS_MIRRORED)
        val commitsBefore = store.commits.flatMap { it.rows }.size
        e.sync(request())
        assertEquals(commitsBefore, store.commits.flatMap { it.rows }.size)
        assertEquals(GoogleHealthMirrorEntry.STATUS_MIRRORED, store.googleMirror.getValue("gh:a").mirrorStatus)
    }

    @Test
    fun echoAndPlatformDuplicatesAreStoredButNotMirrored() = runBlocking {
        val store = InMemoryHealthDataStore()
        val start = Instant.parse("2026-03-08T12:00:00Z").toEpochMilli()
        store.samples["hc-1"] = HealthSampleRow(
            id = "hc-1", typeId = "steps", startMs = start, endMs = start + 900_000, localDay = "2026-03-08",
            value = 300.0, unit = "count", sourceId = "com.example", updatedMs = 1
        )
        val source = FakeSource(
            mutableMapOf(
                "steps" to ArrayDeque(
                    listOf(
                        GoogleHealthPage(
                            listOf(
                                stepsPoint("hc", "2026-03-08T08:00:00Z", "2026-03-08T08:15:00Z", 10, pkg = "com.google.android.apps.healthdata"),
                                stepsPoint("dup", "2026-03-08T12:00:00Z", "2026-03-08T12:15:00Z", 301, platform = "FITBIT"),
                                stepsPoint("ok", "2026-03-08T14:00:00Z", "2026-03-08T14:15:00Z", 50, platform = "FITBIT"),
                                // docs/google-health.md §4: dataSource.platform alone never skips the write-back (Pixel Watch on Android).
                                stepsPoint("android", "2026-03-08T16:00:00Z", "2026-03-08T16:15:00Z", 70, platform = "ANDROID")
                            ),
                            null
                        )
                    )
                ),
                "active-zone-minutes" to ArrayDeque(
                    listOf(
                        GoogleHealthPage(
                            listOf(
                                Json.parseToJsonElement(
                                    """{"name":"x/dataPoints/z1","activeZoneMinutes":{"interval":{"startTime":"2026-03-08T09:00:00Z","endTime":"2026-03-08T09:01:00Z"},"activeZoneMinutes":"2","heartRateZone":"CARDIO"}}"""
                                ).jsonObject
                            ),
                            null
                        )
                    )
                )
            )
        )
        engine(store, source).sync(request())
        assertEquals(GoogleHealthMirrorEntry.STATUS_SKIPPED_DUP, store.googleMirror.getValue("gh:hc").mirrorStatus)
        assertEquals(GoogleHealthMirrorEntry.STATUS_SKIPPED_DUP, store.googleMirror.getValue("gh:dup").mirrorStatus)
        assertEquals(GoogleHealthMirrorEntry.STATUS_PENDING, store.googleMirror.getValue("gh:ok").mirrorStatus)
        assertEquals(GoogleHealthMirrorEntry.STATUS_PENDING, store.googleMirror.getValue("gh:android").mirrorStatus)
        assertEquals(GoogleHealthMirrorEntry.STATUS_UNSUPPORTED, store.googleMirror.getValue("gh:z1").mirrorStatus)
        val azm = store.samples.getValue("gh:z1")
        assertEquals(120.0, azm.value!!, 0.0)
        assertEquals(2, azm.categoryValue)
    }

    @Test
    fun writeBackOffParksNewRows() = runBlocking {
        val store = InMemoryHealthDataStore()
        val source = FakeSource(mutableMapOf("steps" to ArrayDeque(listOf(GoogleHealthPage(listOf(stepsPoint("a", "2026-03-08T10:00:00Z", "2026-03-08T10:15:00Z", 100, platform = "FITBIT")), null)))))
        engine(store, source).sync(request(writeBack = false))
        assertEquals(GoogleHealthMirrorEntry.STATUS_DISABLED, store.googleMirror.getValue("gh:a").mirrorStatus)
    }

    @Test
    fun partialScopesMarkTypesAndOptionalTypesBecomeUnsupported() = runBlocking {
        val store = InMemoryHealthDataStore()
        val source = FakeSource(
            mutableMapOf(
                "total-calories" to ArrayDeque(listOf(GoogleHealthException.Unsupported(404))),
                "floors" to ArrayDeque(listOf(GoogleHealthException.Scope())),
                "distance" to ArrayDeque(listOf(GoogleHealthException.Http(500)))
            )
        )
        val outcome = engine(store, source).sync(request(groups = setOf("activity", "sleep")))
        assertTrue(outcome is GoogleHealthSyncOutcome.Synced)
        assertEquals(GoogleHealthSyncState.STATUS_ERROR_SCOPE, store.googleStates.getValue("sleep").status)
        assertTrue(source.calls.none { it.first == "sleep" })
        assertEquals(GoogleHealthSyncState.STATUS_UNSUPPORTED, store.googleStates.getValue("total-calories").status)
        assertEquals(GoogleHealthSyncState.STATUS_ERROR_SCOPE, store.googleStates.getValue("floors").status)
        assertEquals("error:500", store.googleStates.getValue("distance").status)
        assertEquals(1, (outcome as GoogleHealthSyncOutcome.Synced).typesFailed)

        // An unsupported optional type is not asked again within a week.
        val again = FakeSource(mutableMapOf())
        engine(store, again).sync(request())
        assertTrue(again.calls.none { it.first == "total-calories" })
    }

    @Test
    fun authFailureStopsTheRunAndKeepsThePageToken() = runBlocking {
        val store = InMemoryHealthDataStore()
        val source = FakeSource(
            mutableMapOf(
                "steps" to ArrayDeque(
                    listOf(
                        GoogleHealthPage(listOf(stepsPoint("a", "2026-03-08T10:00:00Z", "2026-03-08T10:15:00Z", 100)), "p2"),
                        GoogleHealthException.Auth()
                    )
                )
            )
        )
        val outcome = engine(store, source).sync(request(groups = setOf("activity")))
        assertEquals(GoogleHealthSyncOutcome.NeedsReconnect, outcome)
        assertEquals("p2", store.googleStates.getValue("steps").pageToken)
        assertNull(store.googleStates.getValue("steps").cursorMs)
    }

    @Test
    fun clearDropsStateAndOptionallyRows() = runBlocking {
        val store = InMemoryHealthDataStore()
        val source = FakeSource(mutableMapOf("steps" to ArrayDeque(listOf(GoogleHealthPage(listOf(stepsPoint("a", "2026-03-08T10:00:00Z", "2026-03-08T10:15:00Z", 100)), null)))))
        val e = engine(store, source)
        e.sync(request())
        e.clear(deleteRows = true)
        assertTrue(store.googleStates.isEmpty())
        assertNull(store.samples["gh:a"])
        assertNull(store.daily["steps" to "2026-03-08"]?.sum?.takeIf { it > 0 })
    }
}
