package com.ayuvo.health.nutrients

import com.ayuvo.health.intake.IntakeTestFiles
import com.ayuvo.health.medications.model.MedicationSchedule
import com.ayuvo.health.medications.model.ScheduleFrequency
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Supplements averaged over their dosing interval (docs/intake-metrics.md §3). */
class SupplementAveragingTest {
    private fun schedule(freq: ScheduleFrequency, days: List<Int> = emptyList(), hours: Int? = null) =
        MedicationSchedule(id = "s", medicationId = "m", frequency = freq, times = listOf("09:00"), days = days,
            intervalHours = hours, activeFromMs = 0, createdMs = 0, updatedMs = 0)

    @Test
    fun intervalDaysFromSchedule() {
        assertEquals(1, SupplementAveraging.intervalDays(null))
        assertEquals(1, SupplementAveraging.intervalDays(schedule(ScheduleFrequency.DAILY)))
        assertEquals(7, SupplementAveraging.intervalDays(schedule(ScheduleFrequency.WEEKLY, listOf(7))))
        assertEquals(4, SupplementAveraging.intervalDays(schedule(ScheduleFrequency.WEEKLY, listOf(1, 4))))
        assertEquals(1, SupplementAveraging.intervalDays(schedule(ScheduleFrequency.INTERVAL, hours = 12)))
        assertEquals(3, SupplementAveraging.intervalDays(schedule(ScheduleFrequency.INTERVAL, hours = 72)))
    }

    @Test
    fun weeklyDoseSpreadsOverSevenDaysKeepingTheAmount() {
        val t0 = 1_790_000_000_000L
        val snap = SupplementSnapshot(
            rows = listOf(MedicationNutrientRow("vitd", "vitamin_d", 1500.0), MedicationNutrientRow("multi", "zinc", 11.0)),
            doses = listOf(SupplementDose("vitd", "taken", t0, 1.0), SupplementDose("multi", "taken", t0, 1.0)),
            intervalDays = mapOf("vitd" to 7)
        )
        val d = snap.entriesFor("vitamin_d")
        assertEquals(7, d.size)
        assertEquals((0 until 7).map { t0 + it * 86_400_000L }, d.map { it.tMs })
        assertEquals(1500.0, d.sumOf { it.value }, 1e-9)
        assertEquals(1, snap.entriesFor("zinc").size)
        // No intervals: unchanged.
        assertEquals(1, SupplementSnapshot(snap.rows, snap.doses).entriesFor("vitamin_d").size)
    }

    @Test
    fun regimenAboveUpperLimitIsFlagged() {
        val cfg = IntakeTestFiles.config
        val weekly = SupplementAveraging.regimen("vitamin_d", 1500.0, 7, cfg)
        assertEquals(214.3, weekly.dailyAverage!!, 0.0)
        assertTrue(weekly.aboveUpper)
        assertFalse(SupplementAveraging.regimen("zinc", 11.0, 1, cfg).aboveUpper)
        assertFalse(SupplementAveraging.regimen("magnesium", 1000.0, 1, cfg).aboveUpper) // no UL in the config
        assertEquals(100.0, SupplementAveraging.upperLimit("vitamin_d", cfg)!!, 0.0)
    }
}
