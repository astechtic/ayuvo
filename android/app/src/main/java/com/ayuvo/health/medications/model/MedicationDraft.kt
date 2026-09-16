package com.ayuvo.health.medications.model

import com.ayuvo.health.medications.logic.MedicationConstants
import com.ayuvo.health.medications.logic.MedicationJson
import kotlinx.serialization.json.JsonObject

/**
 * The schedule half of the Add / Edit form (docs/medications.md §15). Validated as JSON by
 * `DraftValidation` so the rules stay identical to the reference.
 */
data class ScheduleDraft(
    val frequency: ScheduleFrequency? = ScheduleFrequency.DAILY,
    val times: List<String> = emptyList(),
    val days: List<Int> = emptyList(),
    val intervalHours: Int? = null,
    val anchorTime: String? = null,
    val reminderEnabled: Boolean = true
) {
    /** The `new_schedule` object of `lifecycle('edit_schedule')`. */
    fun toJson(): JsonObject = MedicationJson.obj(
        "frequency_kind" to frequency?.raw,
        "times" to times,
        "days" to days,
        "interval_hours" to intervalHours,
        "anchor_time" to anchorTime,
        "reminder_enabled" to if (reminderEnabled) 1 else 0
    )

    /**
     * The open row of a NEW medication (docs §4 "creation"): active from `now − GRACE_MS`, so a
     * dose that became due within the last two hours still shows today and can be taken, while
     * earlier slots of the day never existed (no phantom missed rows). Edits and resumes go
     * through the reference's `lifecycle`, which uses `now`.
     */
    fun toSchedule(id: String, medicationId: String, nowMs: Long): MedicationSchedule = MedicationSchedule(
        id = id,
        medicationId = medicationId,
        frequency = frequency ?: ScheduleFrequency.DAILY,
        times = if (frequency == ScheduleFrequency.INTERVAL) emptyList() else times,
        days = if (frequency == ScheduleFrequency.WEEKLY) days else emptyList(),
        intervalHours = if (frequency == ScheduleFrequency.INTERVAL) intervalHours else null,
        anchorTime = if (frequency == ScheduleFrequency.INTERVAL) anchorTime else null,
        reminderEnabled = reminderEnabled,
        activeFromMs = nowMs - MedicationConstants.GRACE_MS,
        activeUntilMs = null,
        createdMs = nowMs,
        updatedMs = nowMs
    )

    /** Same dosing rule as [other] (ids, versioning columns and the reminder flag ignored). */
    fun sameRuleAs(other: MedicationSchedule): Boolean =
        frequency == other.frequency && times == other.times && days == other.days &&
            intervalHours == other.intervalHours && anchorTime == other.anchorTime

    companion object {
        fun from(schedule: MedicationSchedule): ScheduleDraft = ScheduleDraft(
            frequency = schedule.frequency, times = schedule.times, days = schedule.days,
            intervalHours = schedule.intervalHours, anchorTime = schedule.anchorTime,
            reminderEnabled = schedule.reminderEnabled
        )
    }
}

/** The Add / Edit form state (docs §15). `validate_draft` runs on [toValidationJson]. */
data class MedicationDraft(
    val name: String = "",
    val genericName: String? = null,
    val brandName: String? = null,
    val strength: String? = null,
    val form: MedicationForm = MedicationForm.TABLET,
    val doseQuantity: Double? = 1.0,
    val doseUnit: DoseUnit = DoseUnit.TABLET,
    val foodRelation: FoodRelation = FoodRelation.ANYTIME,
    val instructions: String? = null,
    /** `yyyy-MM-dd`. */
    val startDate: String,
    val endDate: String? = null,
    val isPrn: Boolean = false,
    val photoPath: String? = null,
    val relatedRecordId: String? = null,
    val schedule: ScheduleDraft = ScheduleDraft()
) {
    /** The `draft` object of `validate_draft`. */
    fun toValidationJson(): JsonObject = MedicationJson.obj(
        "name" to name,
        "strength" to strength,
        "form" to form.raw,
        "dose_quantity" to doseQuantity,
        "dose_unit" to doseUnit.raw,
        "food_relation" to foodRelation.raw,
        "start_date" to startDate,
        "end_date" to endDate,
        "is_prn" to if (isPrn) 1 else 0,
        "frequency_kind" to if (isPrn) null else schedule.frequency?.raw,
        "times" to if (isPrn) emptyList() else schedule.times,
        "days" to if (isPrn) emptyList() else schedule.days,
        "interval_hours" to if (isPrn) null else schedule.intervalHours,
        "anchor_time" to if (isPrn) null else schedule.anchorTime,
        "instructions" to instructions
    )

    fun toMedication(id: String, nowMs: Long, status: MedicationStatus = MedicationStatus.ACTIVE, createdMs: Long = nowMs): Medication =
        Medication(
            id = id,
            name = name.trim(),
            genericName = genericName?.trim()?.takeIf { it.isNotEmpty() },
            brandName = brandName?.trim()?.takeIf { it.isNotEmpty() },
            strength = strength?.trim()?.takeIf { it.isNotEmpty() },
            form = form,
            doseQuantity = doseQuantity ?: 1.0,
            doseUnit = doseUnit,
            foodRelation = foodRelation,
            instructions = instructions?.trim()?.takeIf { it.isNotEmpty() },
            startDate = startDate,
            endDate = endDate,
            status = status,
            isPrn = isPrn,
            photoPath = photoPath,
            relatedRecordId = relatedRecordId,
            createdMs = createdMs,
            updatedMs = nowMs
        )

    fun toSchedule(id: String, medicationId: String, nowMs: Long): MedicationSchedule? =
        if (isPrn) null else schedule.toSchedule(id, medicationId, nowMs)

    companion object {
        fun from(medication: Medication, schedule: MedicationSchedule?): MedicationDraft = MedicationDraft(
            name = medication.name,
            genericName = medication.genericName,
            brandName = medication.brandName,
            strength = medication.strength,
            form = medication.form,
            doseQuantity = medication.doseQuantity,
            doseUnit = medication.doseUnit,
            foodRelation = medication.foodRelation,
            instructions = medication.instructions,
            startDate = medication.startDate,
            endDate = medication.endDate,
            isPrn = medication.isPrn,
            photoPath = medication.photoPath,
            relatedRecordId = medication.relatedRecordId,
            schedule = schedule?.let { ScheduleDraft.from(it) } ?: ScheduleDraft()
        )

        /** A draft from a Records prescription hint (§13); [startDate] is the device-local today. */
        fun fromHint(hint: FrequencyDraft, startDate: String, relatedRecordId: String?, endDateFor: (Int) -> String?): MedicationDraft =
            MedicationDraft(
                name = hint.name,
                strength = hint.strength,
                form = hint.form,
                doseQuantity = hint.doseQuantity,
                doseUnit = hint.doseUnit,
                foodRelation = hint.foodRelation,
                instructions = hint.instructions,
                startDate = startDate,
                endDate = hint.durationDays?.let(endDateFor),
                isPrn = hint.isPrn,
                relatedRecordId = relatedRecordId,
                schedule = if (hint.isPrn) ScheduleDraft() else ScheduleDraft(
                    frequency = hint.frequency ?: ScheduleFrequency.DAILY,
                    times = hint.times, days = hint.days,
                    intervalHours = hint.intervalHours, anchorTime = hint.anchorTime
                )
            )
    }
}
