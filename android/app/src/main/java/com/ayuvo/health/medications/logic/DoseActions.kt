package com.ayuvo.health.medications.logic

import com.ayuvo.health.medications.model.DoseAction
import com.ayuvo.health.medications.model.DoseActionResult
import com.ayuvo.health.medications.model.DoseLog
import com.ayuvo.health.medications.model.DoseStatus
import com.ayuvo.health.medications.model.Medication
import com.ayuvo.health.medications.model.MedicationStatus
import com.ayuvo.health.medications.model.Occurrence
import com.ayuvo.health.medications.model.PrnLogResult

/**
 * `apply_dose_action` (docs/medications.md §11): the user's explicit action on one scheduled
 * occurrence. `taken` here and [PrnLogging.log] are the ONLY ways a `taken` row comes into existence.
 */
object DoseActions {

    fun apply(
        action: DoseAction?,
        occurrence: Occurrence,
        existingLog: DoseLog?,
        medication: Medication,
        nowMs: Long,
        snoozeMinutes: Int?,
        takenAtMs: Long? = null,
        note: String? = null,
        ids: () -> String = NewIds()
    ): DoseActionResult {
        fun fail(error: String) = DoseActionResult(false, error, null, null)
        if (action == null) return fail("bad_action")
        if (note != null && MedicationLocalTime.codePoints(note) > MedicationConstants.NOTE_MAX) return fail("note_too_long")
        val stored = existingLog?.status
        val derived = DoseResolutions.resolve(occurrence, existingLog, nowMs).status

        fun baseRow(status: DoseStatus, takenAt: Long? = existingLog?.takenAtMs, snoozedUntil: Long? = existingLog?.snoozedUntilMs): DoseLog =
            if (existingLog != null) {
                existingLog.copy(status = status, takenAtMs = takenAt, snoozedUntilMs = snoozedUntil, updatedMs = nowMs, note = note ?: existingLog.note)
            } else {
                DoseLog(
                    id = ids(), medicationId = occurrence.medicationId, scheduleId = occurrence.scheduleId,
                    scheduledAtMs = occurrence.scheduledAtMs, status = status, takenAtMs = takenAt, snoozedUntilMs = snoozedUntil,
                    doseQuantity = medication.doseQuantity, doseUnit = medication.doseUnit, note = note,
                    createdMs = nowMs, updatedMs = nowMs
                )
            }

        val op = if (existingLog != null) "update" else "insert"
        return when (action) {
            DoseAction.TAKEN -> {
                if (stored == DoseStatus.TAKEN) return fail("already_taken")
                val takenAt = takenAtMs ?: nowMs
                if (takenAt > nowMs) return fail("taken_at_in_future")
                DoseActionResult(true, null, baseRow(DoseStatus.TAKEN, takenAt = takenAt), op)
            }
            DoseAction.SKIPPED -> {
                if (stored == DoseStatus.TAKEN) return fail("already_taken")
                if (stored == DoseStatus.SKIPPED) return fail("already_resolved")
                DoseActionResult(true, null, baseRow(DoseStatus.SKIPPED, takenAt = null), op)
            }
            DoseAction.SNOOZED -> {
                if (snoozeMinutes == null || snoozeMinutes !in MedicationConstants.SNOOZE_MINUTES) return fail("bad_snooze")
                if (derived == DoseStatus.SCHEDULED) return fail("not_due_yet")
                if (derived == DoseStatus.MISSED) return fail("dose_missed")
                if (derived == DoseStatus.TAKEN || derived == DoseStatus.SKIPPED) return fail("already_resolved")
                val until = nowMs + snoozeMinutes * 60_000L
                DoseActionResult(true, null, baseRow(DoseStatus.SNOOZED, snoozedUntil = until), op)
            }
            DoseAction.UNDO -> {
                if (existingLog == null) return fail("nothing_to_undo")
                if (stored == DoseStatus.MISSED) return fail("cannot_undo_missed")
                DoseActionResult(true, null, existingLog, "delete")
            }
        }
    }
}

/** `log_prn_dose` (docs §11): an as-needed dose = a `taken` row with `schedule_id NULL` and `scheduled_at_ms = taken_at_ms`. */
object PrnLogging {

    fun log(
        medication: Medication,
        nowMs: Long,
        takenAtMs: Long? = null,
        doseQuantity: Double? = null,
        note: String? = null,
        ids: () -> String = NewIds()
    ): PrnLogResult {
        if (!medication.isPrn) return PrnLogResult(false, "not_prn", null)
        if (medication.status != MedicationStatus.ACTIVE) return PrnLogResult(false, "not_active", null)
        val takenAt = takenAtMs ?: nowMs
        if (takenAt > nowMs) return PrnLogResult(false, "taken_at_in_future", null)
        if (doseQuantity != null && (doseQuantity.isNaN() || doseQuantity <= 0 || doseQuantity > MedicationConstants.DOSE_QUANTITY_MAX)) {
            return PrnLogResult(false, "dose_quantity_invalid", null)
        }
        if (note != null && MedicationLocalTime.codePoints(note) > MedicationConstants.NOTE_MAX) return PrnLogResult(false, "note_too_long", null)
        val row = DoseLog(
            id = ids(), medicationId = medication.id, scheduleId = null, scheduledAtMs = takenAt, status = DoseStatus.TAKEN,
            takenAtMs = takenAt, snoozedUntilMs = null, doseQuantity = doseQuantity ?: medication.doseQuantity,
            doseUnit = medication.doseUnit, note = note, createdMs = nowMs, updatedMs = nowMs
        )
        return PrnLogResult(true, null, row)
    }
}
