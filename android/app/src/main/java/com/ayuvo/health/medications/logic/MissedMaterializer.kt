package com.ayuvo.health.medications.logic

import com.ayuvo.health.medications.model.DoseLog
import com.ayuvo.health.medications.model.DoseStatus
import com.ayuvo.health.medications.model.Medication
import com.ayuvo.health.medications.model.MissedOp
import com.ayuvo.health.medications.model.Occurrence

/** Deterministic `new-1`, `new-2`, … ids; the store substitutes UUIDs in the same order. */
class NewIds : () -> String {
    private var n = 0
    override fun invoke(): String = "new-${++n}"
}

/**
 * `materialize_missed` (docs/medications.md §7): rows to write so that every occurrence that
 * resolves to `missed` has a stored `missed` log. Never produces a `taken` row.
 */
object MissedMaterializer {

    fun pending(
        occurrences: List<Occurrence>,
        logs: List<DoseLog>,
        medicationById: Map<String, Medication>,
        nowMs: Long,
        ids: () -> String = NewIds()
    ): List<MissedOp> {
        val index = DoseResolutions.index(logs)
        val ops = mutableListOf<MissedOp>()
        for (occ in occurrences) {
            val log = index[occ.scheduleId to occ.scheduledAtMs]
            if (DoseResolutions.resolve(occ, log, nowMs).status != DoseStatus.MISSED) continue
            if (log == null) {
                val med = medicationById[occ.medicationId] ?: continue
                ops += MissedOp(
                    MissedOp.INSERT,
                    DoseLog(
                        id = ids(), medicationId = occ.medicationId, scheduleId = occ.scheduleId,
                        scheduledAtMs = occ.scheduledAtMs, status = DoseStatus.MISSED, takenAtMs = null,
                        snoozedUntilMs = null, doseQuantity = med.doseQuantity, doseUnit = med.doseUnit,
                        note = null, createdMs = nowMs, updatedMs = nowMs
                    )
                )
            } else if (log.status == DoseStatus.SNOOZED) {
                ops += MissedOp(MissedOp.UPDATE, log.copy(status = DoseStatus.MISSED, updatedMs = nowMs))
            }
        }
        return ops
    }
}
