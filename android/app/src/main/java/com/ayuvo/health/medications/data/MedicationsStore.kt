package com.ayuvo.health.medications.data

import com.ayuvo.health.medications.model.AdherenceSummary
import com.ayuvo.health.medications.model.DoseAction
import com.ayuvo.health.medications.model.DoseActionResult
import com.ayuvo.health.medications.model.DoseLog
import com.ayuvo.health.medications.model.DoseLogCursor
import com.ayuvo.health.medications.model.ImportResult
import com.ayuvo.health.medications.model.LifecycleAction
import com.ayuvo.health.medications.model.Medication
import com.ayuvo.health.medications.model.MedicationFilter
import com.ayuvo.health.medications.model.MedicationSchedule
import com.ayuvo.health.medications.model.MedicationStatus
import com.ayuvo.health.medications.model.MedicationsSnapshot
import com.ayuvo.health.medications.model.PrnLogResult
import com.ayuvo.health.medications.model.ReminderPlan
import com.ayuvo.health.medications.model.TodayTimeline
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonObject

/**
 * The medications database behind the Meds segment, the reminder planner and the archive
 * (docs/medications.md). Every write runs in one transaction and bumps [revision]; the pure rules
 * live in `medications/logic` and are only wired together here.
 */
interface MedicationsStore {
    /** Bumps after every committed write; screens re-query on change. */
    val revision: StateFlow<Long>

    // -- medications ------------------------------------------------------------------------------
    suspend fun list(filter: MedicationFilter = MedicationFilter()): List<Medication>
    suspend fun medication(id: String): Medication?
    suspend fun medicationsForRecord(recordId: String): List<Medication>
    suspend fun countByStatus(): Map<MedicationStatus, Int>

    /** Inserts the medication and, unless it is PRN, its open schedule row. Returns the medication id. */
    suspend fun create(medication: Medication, schedule: MedicationSchedule?): String

    /**
     * Updates the medication's own columns and applies [newSchedule] (ids ignored): a changed rule
     * on an active medication closes the open row and inserts a new one (`edit_schedule`); on a paused
     * medication it is stored as the latest closed generation so `resume` picks it up; only the
     * reminder flag changing is an in-place `set_reminder_enabled`. PRN medications keep no open row.
     * Returns the reference's error code (`invalid_schedule`, `unknown_medication`, …) or null.
     */
    suspend fun update(medication: Medication, newSchedule: MedicationSchedule?, nowMs: Long): String?

    /** `pause` / `resume` / `stop` / `complete`; returns the reference's error code or null. */
    suspend fun setStatus(id: String, action: LifecycleAction, nowMs: Long): String?
    suspend fun setReminderEnabled(medicationId: String, enabled: Boolean, nowMs: Long): String?
    suspend fun delete(id: String)

    // -- schedules --------------------------------------------------------------------------------
    suspend fun schedules(medicationId: String, openOnly: Boolean = true): List<MedicationSchedule>
    suspend fun openSchedules(): List<MedicationSchedule>

    // -- today / doses ----------------------------------------------------------------------------
    suspend fun today(nowMs: Long, zoneId: String): TodayTimeline

    /**
     * The user's explicit action on one scheduled occurrence. [logId] addresses a PRN log for `undo`
     * (PRN rows have no schedule). Snooze needs [snoozeMinutes] ∈ {10, 30, 60}.
     */
    suspend fun act(
        medicationId: String,
        scheduleId: String?,
        scheduledAtMs: Long,
        action: DoseAction,
        nowMs: Long,
        snoozeMinutes: Int,
        takenAtMs: Long? = null,
        note: String? = null,
        logId: String? = null
    ): DoseActionResult

    suspend fun logPrn(medicationId: String, nowMs: Long, takenAtMs: Long? = null, doseQuantity: Double? = null, note: String? = null): PrnLogResult
    suspend fun doseLog(scheduleId: String, scheduledAtMs: Long): DoseLog?

    /** Keyset page of history (`scheduled_at_ms DESC, id DESC`), all medications when [medicationId] is null. */
    suspend fun history(medicationId: String?, before: DoseLogCursor?, limit: Int = 60): List<DoseLog>
    suspend fun adherence(medicationId: String?, nowMs: Long, zoneId: String): AdherenceSummary

    /** Writes `missed` rows for every dose past its grace window since the last run; returns the number of rows written. */
    suspend fun materializeMissed(nowMs: Long, zoneId: String): Int

    /** Completes active/paused medications whose end date is before [todayLocal]; returns how many. */
    suspend fun autoComplete(todayLocal: String, nowMs: Long): Int

    /** `plan_reminders` over the whole database (docs §10). */
    suspend fun planReminders(nowMs: Long, horizonMs: Long, zoneId: String, budget: Int? = null): ReminderPlan

    // -- archive ----------------------------------------------------------------------------------
    suspend fun exportSnapshot(): MedicationsSnapshot

    /** Merges a parsed `ayuvo-medications` archive (docs §14) in one transaction; never deletes. */
    suspend fun importArchive(archive: JsonObject, nowMs: Long): ImportResult

    // -- meta -------------------------------------------------------------------------------------
    suspend fun meta(key: String): String?
    suspend fun setMeta(key: String, value: String)
}
