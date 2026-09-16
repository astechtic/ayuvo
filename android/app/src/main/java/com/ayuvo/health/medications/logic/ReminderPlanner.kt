package com.ayuvo.health.medications.logic

import com.ayuvo.health.medications.model.DoseLog
import com.ayuvo.health.medications.model.DoseStatus
import com.ayuvo.health.medications.model.Medication
import com.ayuvo.health.medications.model.MedicationSchedule
import com.ayuvo.health.medications.model.MedicationStatus
import com.ayuvo.health.medications.model.ReminderEntry
import com.ayuvo.health.medications.model.ReminderKind
import com.ayuvo.health.medications.model.ReminderPlan

/**
 * `plan_reminders` (docs/medications.md §10): reminder entries ascending by fire time, trimmed to
 * `budget` (null = unlimited). Android arms ONE alarm at [ReminderPlan.nextFireMs]; iOS schedules
 * every entry. Snooze entries fire even when the schedule's reminders are off.
 */
object ReminderPlanner {

    fun plan(
        medications: List<Medication>,
        schedules: List<MedicationSchedule>,
        logs: List<DoseLog>,
        nowMs: Long,
        horizonMs: Long,
        timeZone: String,
        budget: Int? = null
    ): ReminderPlan {
        val byId = medications.associateBy { it.id }
        val active = byId.filterValues { it.status == MedicationStatus.ACTIVE }
        val openRows = schedules.filter { it.activeUntilMs == null && it.reminderEnabled && it.medicationId in active }
        val index = DoseResolutions.index(logs)
        val entries = mutableListOf<ReminderEntry>()
        if (horizonMs > 0) {
            val occurrences = Occurrences.expandAll(
                active.values.toList(), openRows, nowMs - MedicationConstants.LATE_FIRE_MS, nowMs + horizonMs,
                timeZone, setOf(MedicationStatus.ACTIVE)
            )
            for (occ in occurrences) {
                if ((occ.scheduleId to occ.scheduledAtMs) in index) continue
                entries += ReminderEntry(occ.medicationId, occ.scheduleId, occ.scheduledAtMs, maxOf(occ.scheduledAtMs, nowMs), ReminderKind.SCHEDULED)
            }
        }
        for (log in logs) {
            if (log.status != DoseStatus.SNOOZED || log.medicationId !in active) continue
            val until = log.snoozedUntilMs ?: continue
            if (until < nowMs - MedicationConstants.LATE_FIRE_MS) continue
            entries += ReminderEntry(log.medicationId, log.scheduleId, log.scheduledAtMs, maxOf(until, nowMs), ReminderKind.SNOOZE)
        }
        entries.sortWith(
            compareBy<ReminderEntry> { it.fireAtMs }
                .thenBy { MedicationLocalTime.foldName(byId[it.medicationId]?.name) }
                .thenBy { it.medicationId }.thenBy { it.scheduledAtMs }.thenBy { it.scheduleId ?: "" }
        )
        var truncated = false
        var out: List<ReminderEntry> = entries
        if (budget != null && budget >= 0 && entries.size > budget) {
            out = entries.take(budget)
            truncated = true
        }
        return ReminderPlan(out, out.firstOrNull()?.fireAtMs, truncated)
    }

    /** Entries whose fire time has arrived (within [toleranceMs]) — what an alarm receiver posts. */
    fun dueEntries(plan: ReminderPlan, nowMs: Long, toleranceMs: Long = 60_000L): List<ReminderEntry> =
        plan.entries.filter { it.fireAtMs <= nowMs + toleranceMs }
}
