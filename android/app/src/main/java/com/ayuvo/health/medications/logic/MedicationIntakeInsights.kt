package com.ayuvo.health.medications.logic

import com.ayuvo.health.data.intake.AdherenceLog
import com.ayuvo.health.data.intake.IntakeConfig
import com.ayuvo.health.data.intake.MedicationAdherence
import com.ayuvo.health.data.intake.MedsAdherenceInput
import com.ayuvo.health.data.intake.MedsAdherenceResult
import com.ayuvo.health.medications.model.DoseLog
import com.ayuvo.health.medications.model.MedicationSchedule
import com.ayuvo.health.medications.model.ScheduleFrequency

/**
 * Per-medication adherence over the last [WINDOW_DAYS] days through `meds_adherence` (docs/intake-metrics.md §3):
 * taken ÷ (taken + missed), on-time share, median delay, missed doses per weekday, streak and a suggested reminder
 * time. PRN logs and snoozes never count; skipped doses are left out by the engine.
 */
object MedicationIntakeInsights {
    const val WINDOW_DAYS = 30
    private const val DAY_MS = 86_400_000L

    fun input(logs: List<DoseLog>, nowMs: Long, zoneId: String): MedsAdherenceInput {
        val from = nowMs - WINDOW_DAYS * DAY_MS
        val rows = logs.filter { !it.isPrn && it.scheduledAtMs in from..nowMs && it.status.isTerminal }
            .map { AdherenceLog(it.scheduledAtMs, it.takenAtMs, it.status.raw) }
        return MedsAdherenceInput(zoneId, rows)
    }

    fun compute(logs: List<DoseLog>, nowMs: Long, zoneId: String, cfg: IntakeConfig): MedsAdherenceResult =
        MedicationAdherence.medsAdherence(input(logs, nowMs, zoneId), cfg)

    /** `HH:mm` of minutes after local midnight. */
    fun clockText(minuteOfDay: Int): String {
        val m = Math.floorMod(minuteOfDay, 1440)
        return "%02d:%02d".format(m / 60, m % 60)
    }

    /**
     * [schedule] with its reminder time moved to [clockMin], or null when there is no single reminder time to move
     * (interval schedules, several times a day) or the time is already [clockMin].
     */
    fun movedSchedule(schedule: MedicationSchedule?, clockMin: Int?, nowMs: Long): MedicationSchedule? {
        if (schedule == null || clockMin == null || !schedule.isOpen) return null
        if (schedule.frequency == ScheduleFrequency.INTERVAL || schedule.times.size != 1) return null
        val target = clockText(clockMin)
        if (schedule.times.single() == target) return null
        return schedule.copy(times = listOf(target), updatedMs = nowMs)
    }
}
