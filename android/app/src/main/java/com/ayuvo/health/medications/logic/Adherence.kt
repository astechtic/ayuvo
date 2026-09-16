package com.ayuvo.health.medications.logic

import com.ayuvo.health.medications.model.AdherenceSummary
import com.ayuvo.health.medications.model.DoseLog
import com.ayuvo.health.medications.model.DoseStatus
import com.ayuvo.health.medications.model.Occurrence
import java.util.Collections
import java.util.IdentityHashMap

/**
 * `adherence` (docs/medications.md §9): taken / expected over the occurrences and logs passed
 * (already restricted to the window). PRN logs never count.
 */
object Adherence {

    fun compute(occurrences: List<Occurrence>, logs: List<DoseLog>, nowMs: Long, medicationId: String? = null): AdherenceSummary {
        val index = DoseResolutions.index(logs)
        var expected = 0
        var taken = 0
        val matched: MutableSet<DoseLog> = Collections.newSetFromMap(IdentityHashMap())
        for (occ in occurrences) {
            if (medicationId != null && occ.medicationId != medicationId) continue
            val log = index[occ.scheduleId to occ.scheduledAtMs]
            val status = DoseResolutions.resolve(occ, log, nowMs).status
            if (log != null) matched += log
            if (status.isTerminal) {
                expected++
                if (status == DoseStatus.TAKEN) taken++
            }
        }
        for (log in logs) {
            if (log in matched || log.scheduleId == null) continue
            if (medicationId != null && log.medicationId != medicationId) continue
            if (log.status.isTerminal) {
                expected++
                if (log.status == DoseStatus.TAKEN) taken++
            }
        }
        val percent = if (expected > 0) Math.floor(taken * 100.0 / expected + 0.5).toInt() else 0
        return AdherenceSummary(taken, expected, percent, expected > 0)
    }
}
