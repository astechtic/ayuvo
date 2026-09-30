package com.ayuvo.health.medications

import com.ayuvo.health.intake.IntakeTestFiles
import com.ayuvo.health.medications.logic.MedicationIntakeInsights
import com.ayuvo.health.medications.model.DoseLog
import com.ayuvo.health.medications.model.DoseStatus
import com.ayuvo.health.medications.model.DoseUnit
import com.ayuvo.health.medications.model.MedicationSchedule
import com.ayuvo.health.medications.model.ScheduleFrequency
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 30-day `meds_adherence` from dose logs and the "Move reminder" schedule edit (docs/intake-metrics.md §3). */
class MedicationIntakeInsightsTest {
    private val day = 86_400_000L
    // 2026-09-01 06:00 Asia/Kolkata (00:30 UTC)
    private val first = 1_788_222_600_000L

    private fun log(i: Int, status: DoseStatus, lateMin: Long? = null, prn: Boolean = false): DoseLog {
        val scheduled = first + i * day
        return DoseLog(
            id = "l$i", medicationId = "m", scheduleId = if (prn) null else "s", scheduledAtMs = scheduled, status = status,
            takenAtMs = lateMin?.let { scheduled + it * 60_000L }, doseQuantity = 1.0, doseUnit = DoseUnit.TABLET, createdMs = 0, updatedMs = 0
        )
    }

    @Test
    fun lateTakerGetsASuggestionAndOldOrPrnLogsDoNotCount() {
        val logs = (0 until 10).map { log(it, DoseStatus.TAKEN, lateMin = 150) } +
            log(10, DoseStatus.MISSED) + log(11, DoseStatus.SKIPPED) + log(12, DoseStatus.TAKEN, 150, prn = true) +
            log(-40, DoseStatus.MISSED)
        val now = first + 13 * day
        val r = MedicationIntakeInsights.compute(logs, now, "Asia/Kolkata", IntakeTestFiles.config)
        assertEquals(11, r.scheduled)
        assertEquals(10, r.taken)
        assertEquals(90.9, r.adherencePct!!, 0.0)
        assertEquals(true, r.adherent)
        assertEquals(0.0, r.onTimePct!!, 0.0)
        assertEquals(150.0, r.medianDelayMin!!, 0.0)
        assertEquals(510, r.suggestedClockMin) // 08:30
        assertEquals(0, r.streak)
        assertEquals("08:30", MedicationIntakeInsights.clockText(r.suggestedClockMin!!))
    }

    @Test
    fun onlyASingleReminderTimeMoves() {
        val s = MedicationSchedule(id = "s", medicationId = "m", frequency = ScheduleFrequency.DAILY, times = listOf("06:00"),
            activeFromMs = 0, createdMs = 0, updatedMs = 0)
        assertEquals(listOf("08:30"), MedicationIntakeInsights.movedSchedule(s, 510, 5)!!.times)
        assertNull(MedicationIntakeInsights.movedSchedule(s, 360, 5))
        assertNull(MedicationIntakeInsights.movedSchedule(s.copy(times = listOf("06:00", "18:00")), 510, 5))
        assertNull(MedicationIntakeInsights.movedSchedule(s.copy(frequency = ScheduleFrequency.INTERVAL), 510, 5))
        assertNull(MedicationIntakeInsights.movedSchedule(s, null, 5))
    }
}
