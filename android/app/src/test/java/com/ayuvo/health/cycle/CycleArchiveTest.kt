package com.ayuvo.health.cycle

import com.ayuvo.health.cycle.data.CycleArchive
import com.ayuvo.health.cycle.engine.CycleDayLogInput
import com.ayuvo.health.cycle.engine.CyclePeriodInput
import com.ayuvo.health.cycle.engine.CycleSettingsInput
import com.ayuvo.health.cycle.engine.CycleState
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Section `cycle` codec (docs/cycle-tracking.md §7) against shared/cycle/fixtures/cycle-sample. */
class CycleArchiveTest {
    private fun fixture(name: String) = CycleTestFiles.shared("fixtures/cycle-sample/$name")!!.readText()
    private val manifest get() = Json.parseToJsonElement(fixture("manifest.json")).jsonObject

    private fun bundle() = CycleArchive.read(fixture("periods.ndjson"), fixture("day_logs.ndjson"), fixture("settings.json"))

    @Test
    fun fixtureDecodesWithManifestCounts() {
        val b = bundle()
        val counts = manifest["counts"]!!.jsonObject
        assertEquals(counts["periods"]!!.jsonPrimitive.int, b.periods.size)
        assertEquals(counts["day_logs"]!!.jsonPrimitive.int, b.dayLogs.size)
        assertEquals(0, b.skippedLines)
        val tomb = b.dayLogs.single { it.deleted }
        assertEquals("2026-09-20", tomb.day)
        assertNull(tomb.note)
        val settings = b.settings!!
        assertTrue(settings.setupDone)
        assertEquals(29, settings.cycleLength)
        assertNull(settings.lutealLength)
        assertFalse(settings.preferences.healthSync)
        assertEquals(listOf("lower_abdomen", "lower_back"), b.dayLogs.first { it.day == "2026-09-06" }.painLocations)
    }

    @Test
    fun fixtureSnapshotMatchesManifestExpected() {
        val b = bundle()
        val exp = manifest["expected"]!!.jsonObject
        val s = b.settings!!
        val state = CycleState(
            today = exp["today"]!!.jsonPrimitive.content,
            settings = CycleSettingsInput(s.cycleLength, s.periodLength, s.lutealLength),
            periods = b.periods.filter { !it.deleted }.map { CyclePeriodInput(it.id, it.startDay, it.endDay) },
            logs = b.dayLogs.filter { !it.deleted }.map { CycleDayLogInput(it.day, it.flow, it.pain, it.painLocations, it.symptoms, it.moods) }
        )
        val snap = CycleTestFiles.engine.snapshot(state)
        assertEquals(exp["next_start"]!!.jsonPrimitive.content, snap.prediction.nextStart)
        assertEquals(exp["basis"]!!.jsonPrimitive.content, snap.prediction.basis)
        assertEquals(exp["cycle_length"]!!.jsonPrimitive.int, snap.prediction.cycleLength)
        assertEquals(exp["today_phase"]!!.jsonPrimitive.content, snap.today.phase)
        assertEquals(exp["live_day_logs"]!!.jsonPrimitive.int, state.logs.size)
    }

    @Test
    fun encodeRoundTripsAndNeverExportsDeviceFields() {
        val b = bundle()
        val entries = CycleArchive.encode(b).toMap()
        assertEquals(listOf(CycleArchive.PERIODS, CycleArchive.DAY_LOGS, CycleArchive.SETTINGS), CycleArchive.encode(b).map { it.first })
        assertEquals("periods", entries.getValue(CycleArchive.PERIODS).second)
        assertNull(entries.getValue(CycleArchive.SETTINGS).second)
        for ((_, e) in entries) {
            assertFalse(e.first.contains("platform_ids"))
            assertFalse(e.first.contains("sync_state"))
        }
        val again = CycleArchive.read(
            entries.getValue(CycleArchive.PERIODS).first, entries.getValue(CycleArchive.DAY_LOGS).first,
            entries.getValue(CycleArchive.SETTINGS).first
        )
        assertEquals(b.periods, again.periods)
        assertEquals(b.dayLogs, again.dayLogs)
        assertEquals(b.settings, again.settings)
        // Each line is a sorted-key JSON object.
        val first = Json.parseToJsonElement(entries.getValue(CycleArchive.DAY_LOGS).first.lines().first()) as JsonObject
        assertEquals(first.keys.sorted(), first.keys.toList())
        assertEquals(JsonPrimitive(0), first["deleted"])
    }

    @Test
    fun badLinesAreSkippedAndMergeRuleKeepsNewerLocalRows() {
        val b = CycleArchive.read("{\"id\":\"x\"}\nnot json\n" + fixture("periods.ndjson"), null, null)
        assertEquals(4, b.periods.size)
        assertEquals(2, b.skippedLines)
        assertNull(b.settings)
        assertTrue(CycleArchive.shouldApply(null, 1))
        assertTrue(CycleArchive.shouldApply(1, 2))
        assertFalse(CycleArchive.shouldApply(2, 2))
        assertFalse(CycleArchive.shouldApply(3, 2))
    }
}
