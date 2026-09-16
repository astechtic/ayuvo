package com.ayuvo.health.medications.logic

import com.ayuvo.health.medications.logic.MedicationJson.has
import com.ayuvo.health.medications.logic.MedicationJson.int
import com.ayuvo.health.medications.logic.MedicationJson.str
import com.ayuvo.health.medications.logic.MedicationJson.truthy
import com.ayuvo.health.medications.model.LifecycleAction
import com.ayuvo.health.medications.model.LifecycleOp
import com.ayuvo.health.medications.model.LifecycleResult
import com.ayuvo.health.medications.model.Medication
import com.ayuvo.health.medications.model.MedicationSchedule
import com.ayuvo.health.medications.model.MedicationStatus
import com.ayuvo.health.medications.model.ScheduleFrequency
import kotlinx.serialization.json.JsonObject

/**
 * `lifecycle` (docs/medications.md §12): status transitions and schedule versioning. Returns every
 * schedule row of the medication after the change plus the ops to run. [newSchedule] is the raw
 * `new_schedule` object (validated with the shared schedule rules) so the port and the reference
 * reject exactly the same input.
 */
object Lifecycle {

    fun apply(
        action: LifecycleAction?,
        medication: Medication,
        schedules: List<MedicationSchedule>,
        nowMs: Long,
        newSchedule: JsonObject? = null,
        ids: () -> String = NewIds()
    ): LifecycleResult {
        val rows = schedules.filter { it.medicationId == medication.id }.toMutableList()
        var med = medication
        val status = med.status
        val openIndexes = rows.indices.filter { rows[it].activeUntilMs == null }

        fun fail(error: String, errors: List<com.ayuvo.health.medications.model.ValidationError>? = null) =
            LifecycleResult(false, error, med, rows.toList(), emptyList(), errors)

        if (openIndexes.size > 1) return fail("multiple_open_schedules")
        val ops = mutableListOf<LifecycleOp>()

        fun closeOpen() {
            for (i in openIndexes) {
                rows[i] = rows[i].copy(activeUntilMs = nowMs, updatedMs = nowMs)
                ops += LifecycleOp(LifecycleOp.CLOSE_SCHEDULE, id = rows[i].id)
            }
        }

        fun setStatus(new: MedicationStatus) {
            med = med.copy(status = new, updatedMs = nowMs)
            ops += LifecycleOp(LifecycleOp.SET_STATUS, status = new)
        }

        when (action) {
            LifecycleAction.PAUSE -> {
                if (status != MedicationStatus.ACTIVE) return fail("invalid_transition")
                closeOpen()
                setStatus(MedicationStatus.PAUSED)
            }
            LifecycleAction.RESUME -> {
                if (status != MedicationStatus.PAUSED) return fail("invalid_transition")
                val closed = rows.filter { it.activeUntilMs != null }
                if (closed.isNotEmpty()) {
                    val latest = closed.maxOf { it.activeUntilMs!! }
                    for (r in closed.filter { it.activeUntilMs == latest }) {
                        val copy = r.copy(id = ids(), activeFromMs = nowMs, activeUntilMs = null, createdMs = nowMs, updatedMs = nowMs)
                        rows += copy
                        ops += LifecycleOp(LifecycleOp.INSERT_SCHEDULE, id = copy.id)
                    }
                }
                setStatus(MedicationStatus.ACTIVE)
            }
            LifecycleAction.STOP, LifecycleAction.COMPLETE -> {
                if (status != MedicationStatus.ACTIVE && status != MedicationStatus.PAUSED) return fail("invalid_transition")
                closeOpen()
                setStatus(if (action == LifecycleAction.STOP) MedicationStatus.STOPPED else MedicationStatus.COMPLETED)
            }
            LifecycleAction.EDIT_SCHEDULE -> {
                if (status != MedicationStatus.ACTIVE) return fail("invalid_transition")
                if (med.isPrn) return fail("prn_has_schedule")
                if (newSchedule == null) return fail("schedule_required")
                val errs = DraftValidation.validateSchedule(newSchedule)
                if (errs.isNotEmpty()) return fail("invalid_schedule", errs)
                closeOpen()
                val kind = ScheduleFrequency.fromRaw(newSchedule.str("frequency_kind"))
                val row = MedicationSchedule(
                    id = ids(), medicationId = med.id, frequency = kind,
                    times = if (kind != ScheduleFrequency.INTERVAL) MedicationJson.strings(newSchedule["times"]) else emptyList(),
                    days = if (kind == ScheduleFrequency.WEEKLY) MedicationJson.ints(newSchedule["days"]) else emptyList(),
                    intervalHours = if (kind == ScheduleFrequency.INTERVAL) newSchedule.int("interval_hours") else null,
                    anchorTime = if (kind == ScheduleFrequency.INTERVAL) newSchedule.str("anchor_time") else null,
                    reminderEnabled = if (newSchedule.has("reminder_enabled")) newSchedule.truthy("reminder_enabled") else true,
                    activeFromMs = nowMs, activeUntilMs = null, createdMs = nowMs, updatedMs = nowMs
                )
                rows += row
                ops += LifecycleOp(LifecycleOp.INSERT_SCHEDULE, id = row.id)
                med = med.copy(updatedMs = nowMs)
            }
            LifecycleAction.SET_REMINDER_ENABLED -> {
                if (newSchedule == null || !newSchedule.has("reminder_enabled")) return fail("schedule_required")
                if (openIndexes.isEmpty()) return fail("no_open_schedule")
                val i = openIndexes[0]
                rows[i] = rows[i].copy(reminderEnabled = newSchedule.truthy("reminder_enabled"), updatedMs = nowMs)
                ops += LifecycleOp(LifecycleOp.UPDATE_SCHEDULE, id = rows[i].id)
            }
            null -> return fail("bad_action")
        }
        rows.sortWith(compareBy<MedicationSchedule> { it.activeFromMs }.thenBy { it.id })
        return LifecycleResult(true, null, med, rows.toList(), ops)
    }
}

/** `auto_complete` (docs §12): active/paused medications whose end date is before today. */
object AutoComplete {
    fun due(medications: List<Medication>, todayLocalDate: String): List<String> {
        val today = MedicationLocalTime.parseDate(todayLocalDate) ?: return emptyList()
        return medications.filter { m ->
            (m.status == MedicationStatus.ACTIVE || m.status == MedicationStatus.PAUSED) &&
                m.endDate?.let { MedicationLocalTime.parseDate(it) }?.isBefore(today) == true
        }.map { it.id }
    }
}
