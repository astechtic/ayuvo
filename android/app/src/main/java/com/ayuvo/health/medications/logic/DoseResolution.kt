package com.ayuvo.health.medications.logic

import com.ayuvo.health.medications.model.DoseLog
import com.ayuvo.health.medications.model.DoseResolution
import com.ayuvo.health.medications.model.DoseStatus
import com.ayuvo.health.medications.model.Occurrence

/** `resolve_dose_status` (docs/medications.md §7): stored terminal statuses always win. */
object DoseResolutions {

    fun resolve(occurrence: Occurrence, log: DoseLog?, nowMs: Long): DoseResolution {
        val scheduledAt = occurrence.scheduledAtMs
        val grace = MedicationConstants.GRACE_MS
        if (log != null && log.status.isTerminal) {
            val deadline = maxOf(scheduledAt, log.snoozedUntilMs ?: 0L) + grace
            val isLate = log.status == DoseStatus.TAKEN && log.takenAtMs != null && log.takenAtMs > scheduledAt + grace
            return DoseResolution(log.status, deadline, isLate)
        }
        if (log != null && log.status == DoseStatus.SNOOZED) {
            val until = log.snoozedUntilMs ?: scheduledAt
            val deadline = maxOf(scheduledAt, until) + grace
            val status = when {
                nowMs < until -> DoseStatus.SNOOZED
                nowMs < deadline -> DoseStatus.DUE
                else -> DoseStatus.MISSED
            }
            return DoseResolution(status, deadline, false)
        }
        val deadline = scheduledAt + grace
        val status = when {
            nowMs < scheduledAt -> DoseStatus.SCHEDULED
            nowMs < deadline -> DoseStatus.DUE
            else -> DoseStatus.MISSED
        }
        return DoseResolution(status, deadline, false)
    }

    /** `{(schedule_id, scheduled_at_ms): log}` for logs of scheduled doses; the first log per key wins. */
    fun index(logs: List<DoseLog>): Map<Pair<String, Long>, DoseLog> {
        val out = LinkedHashMap<Pair<String, Long>, DoseLog>()
        for (log in logs) {
            val sid = log.scheduleId ?: continue
            out.putIfAbsent(sid to log.scheduledAtMs, log)
        }
        return out
    }
}
