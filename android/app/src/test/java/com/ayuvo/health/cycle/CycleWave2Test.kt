package com.ayuvo.health.cycle

import androidx.health.connect.client.records.MenstruationFlowRecord
import com.ayuvo.health.cycle.coach.CoachCycleContext
import com.ayuvo.health.cycle.data.CyclePreferences
import com.ayuvo.health.cycle.engine.CycleDayLogInput
import com.ayuvo.health.cycle.engine.CyclePeriodInput
import com.ayuvo.health.cycle.engine.CycleReminder
import com.ayuvo.health.cycle.engine.CycleState
import com.ayuvo.health.cycle.health.CycleHealthConnectWriter
import com.ayuvo.health.cycle.reminders.CycleReminderPlanner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/** Wave 2 pure logic: reminder planning, the Coach summary and the Health Connect mapping (docs/cycle-tracking.md §4, §6, §8). */
class CycleWave2Test {
    private val zone = ZoneId.of("Europe/Berlin")
    private val now = ZonedDateTime.of(2026, 10, 2, 10, 0, 0, 0, zone)

    // -- Reminders (§6) ---------------------------------------------------------------------------

    @Test
    fun periodSoonFiresAtTheReminderTimeOnItsDay() {
        val plan = CycleReminderPlanner.plan(
            listOf(CycleReminder("period_soon", "2026-10-03")), CyclePreferences(time = "08:30"),
            setupDone = true, enabled = true, now = now, nextStart = "2026-10-05"
        )
        assertEquals(1, plan.size)
        assertEquals(CycleReminderPlanner.REQUEST_PERIOD_SOON, plan[0].requestCode)
        assertEquals(ZonedDateTime.of(2026, 10, 3, 8, 30, 0, 0, zone).toInstant().toEpochMilli(), plan[0].atMs)
        assertEquals(2, plan[0].daysAhead)
    }

    @Test
    fun aPastPeriodSoonIsDroppedButPeriodEndAndDailyMoveToTomorrow() {
        val plan = CycleReminderPlanner.plan(
            listOf(CycleReminder("daily_log", null), CycleReminder("period_soon", "2026-10-02"), CycleReminder("period_end", "2026-10-02")),
            CyclePreferences(time = "09:00"), setupDone = true, enabled = true, now = now, nextStart = "2026-10-04"
        )
        assertEquals(listOf("daily_log", "period_end"), plan.map { it.kind })
        val tomorrow9 = ZonedDateTime.of(2026, 10, 3, 9, 0, 0, 0, zone).toInstant().toEpochMilli()
        assertTrue(plan.all { it.atMs == tomorrow9 })
        assertEquals(setOf(3001, 3002, 3003), CycleReminderPlanner.REQUEST_CODES.toSet())
    }

    @Test
    fun nothingIsPlannedBeforeSetupOrWhileHidden() {
        val r = listOf(CycleReminder("daily_log", null))
        assertTrue(CycleReminderPlanner.plan(r, CyclePreferences(), setupDone = false, enabled = true, now = now, nextStart = null).isEmpty())
        assertTrue(CycleReminderPlanner.plan(r, CyclePreferences(), setupDone = true, enabled = false, now = now, nextStart = null).isEmpty())
    }

    // -- Coach (§8) -------------------------------------------------------------------------------

    private val coach by lazy { CoachCycleContext(CoachCycleContext.Coach.parse(CycleTestFiles.shared("coach.json")!!.readText())) }

    private fun state(): CycleState {
        val periods = listOf("2026-06-10", "2026-07-08", "2026-08-07", "2026-09-05").mapIndexed { i, s ->
            CyclePeriodInput("p$i", s, LocalDate.parse(s).plusDays(4).toString())
        }
        val logs = periods.map { CycleDayLogInput(it.start, flow = "heavy", pain = 6, symptoms = listOf("cramps"), moods = listOf("calm")) }
        return CycleState("2026-10-02", null, periods, logs)
    }

    @Test
    fun coachSummaryHasNumbersEstimatesAndGuardrailsButNoNotes() {
        val engine = CycleTestFiles.engine
        val s = state()
        val lines = coach.promptLines(engine.snapshot(s), engine.trends(s), CycleTestFiles.config, showFertility = true)
        val text = lines.joinToString("\n")
        assertTrue(text, text.contains("## Cycle tracking"))
        assertTrue(text, text.contains("Recent cycle lengths (oldest to newest): 28, 30, 29 days"))
        assertTrue(text, text.contains("this is an estimate"))
        assertTrue(text, text.contains("Never diagnose"))
        assertTrue(text, text.contains("cramps (4 of 4 cycles)"))
        assertFalse(text.lowercase().contains("note"))
    }

    @Test
    fun onDeviceBlockStaysWithinTwelveLines() {
        val engine = CycleTestFiles.engine
        val s = state()
        val block = coach.onDeviceBlock(engine.snapshot(s), engine.trends(s), CycleTestFiles.config, showFertility = true)
        assertTrue(block.lines().size <= CoachCycleContext.MAX_ON_DEVICE_LINES)
        assertTrue(block.contains("estimate"))
    }

    @Test
    fun hiddenFertilityNeverNamesAFertilePhase() {
        val engine = CycleTestFiles.engine
        // 2026-09-18 is inside the fertile window of the 2026-09-05 cycle.
        val s = state().copy(today = "2026-09-18")
        // The guardrails mention the fertile window in general; the user's own summary never does.
        val text = coach.summaryLines(engine.snapshot(s), engine.trends(s), CycleTestFiles.config, showFertility = false).joinToString("\n")
        assertFalse(text, text.contains("fertile"))
        assertFalse(text, text.contains("ovulation"))
        val shown = coach.summaryLines(engine.snapshot(s), engine.trends(s), CycleTestFiles.config, showFertility = true).joinToString("\n")
        assertTrue(shown, shown.contains("likely fertile window"))
    }

    @Test
    fun notAvailableLineOnlyWhenTheMessageIsAboutCycles() {
        assertTrue(coach.mentionsCycle("When is my next PERIOD?"))
        assertTrue(coach.mentionsCycle("cramps again"))
        assertFalse(coach.mentionsCycle("how much protein did I eat"))
        assertEquals(2, coach.notAvailableLines("my cycle").size)
        assertTrue(coach.notAvailableLines("hello").isEmpty())
        assertTrue(coach.promptLines(CycleTestFiles.engine.snapshot(CycleState("2026-10-02")), CycleTestFiles.engine.trends(CycleState("2026-10-02")),
            CycleTestFiles.config, true).isEmpty())
    }

    // -- Health Connect mapping (§4) ----------------------------------------------------------------

    @Test
    fun flowLevelsMapToHealthConnectAndSpottingIsNotWritten() {
        val cfg = CycleTestFiles.config
        assertEquals(MenstruationFlowRecord.FLOW_LIGHT, CycleHealthConnectWriter.flowCode(cfg, "light"))
        assertEquals(MenstruationFlowRecord.FLOW_MEDIUM, CycleHealthConnectWriter.flowCode(cfg, "medium"))
        assertEquals(MenstruationFlowRecord.FLOW_HEAVY, CycleHealthConnectWriter.flowCode(cfg, "heavy"))
        assertEquals(MenstruationFlowRecord.FLOW_HEAVY, CycleHealthConnectWriter.flowCode(cfg, "very_heavy"))
        assertNull(CycleHealthConnectWriter.flowCode(cfg, "spotting"))
        assertNull(CycleHealthConnectWriter.flowCode(cfg, null))
    }

    @Test
    fun platformIdsRoundTrip() {
        val json = CycleHealthConnectWriter.idsJson(listOf("ayuvo:cycle:local:1"))
        assertEquals(listOf("ayuvo:cycle:local:1"), CycleHealthConnectWriter.ids(json))
        assertTrue(CycleHealthConnectWriter.ids("{}").isEmpty())
        assertTrue(CycleHealthConnectWriter.ids("not json").isEmpty())
    }
}
