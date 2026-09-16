package com.ayuvo.health.medications.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/**
 * Medication value types (docs/medications.md §3). Every enum stores the exact lowercase
 * string of the shared contract; unknown stored values fall back to a safe default so a
 * newer schema written by another build never crashes the list.
 */

enum class MedicationForm(val raw: String) {
    TABLET("tablet"), CAPSULE("capsule"), SYRUP("syrup"), INJECTION("injection"),
    CREAM("cream"), DROPS("drops"), INHALER("inhaler"), OTHER("other");

    /** Default dose unit for the form (docs §3). */
    val defaultUnit: DoseUnit
        get() = when (this) {
            TABLET -> DoseUnit.TABLET
            CAPSULE -> DoseUnit.CAPSULE
            DROPS -> DoseUnit.DROP
            INHALER -> DoseUnit.PUFF
            INJECTION -> DoseUnit.UNIT
            CREAM -> DoseUnit.APPLICATION
            SYRUP -> DoseUnit.ML
            OTHER -> DoseUnit.UNIT
        }

    companion object {
        fun fromRaw(raw: String?): MedicationForm = entries.firstOrNull { it.raw == raw } ?: OTHER
    }
}

enum class FoodRelation(val raw: String) {
    BEFORE("before"), WITH("with"), AFTER("after"), ANYTIME("anytime");

    companion object {
        fun fromRaw(raw: String?): FoodRelation = entries.firstOrNull { it.raw == raw } ?: ANYTIME
    }
}

enum class MedicationStatus(val raw: String) {
    ACTIVE("active"), PAUSED("paused"), COMPLETED("completed"), STOPPED("stopped");

    /** Stopped and completed are final (docs §3); the UI offers "Add again" instead. */
    val isFinal: Boolean get() = this == COMPLETED || this == STOPPED

    companion object {
        fun fromRaw(raw: String?): MedicationStatus = entries.firstOrNull { it.raw == raw } ?: ACTIVE
    }
}

enum class ScheduleFrequency(val raw: String) {
    DAILY("daily"), WEEKLY("weekly"), INTERVAL("interval");

    companion object {
        fun fromRaw(raw: String?): ScheduleFrequency = entries.firstOrNull { it.raw == raw } ?: DAILY
    }
}

/** `scheduled` and `due` are derived and never stored; the other four are `dose_logs.status` values. */
enum class DoseStatus(val raw: String) {
    SCHEDULED("scheduled"), DUE("due"), TAKEN("taken"), SKIPPED("skipped"), MISSED("missed"), SNOOZED("snoozed");

    val isStored: Boolean get() = this == TAKEN || this == SKIPPED || this == MISSED || this == SNOOZED

    /** Taken, skipped and missed end the occurrence; a snooze does not. */
    val isTerminal: Boolean get() = this == TAKEN || this == SKIPPED || this == MISSED

    companion object {
        fun fromRaw(raw: String?): DoseStatus = entries.firstOrNull { it.raw == raw } ?: SCHEDULED
    }
}

enum class DoseUnit(val raw: String) {
    TABLET("tablet"), CAPSULE("capsule"), ML("ml"), MG("mg"), G("g"), MCG("mcg"), DROP("drop"),
    PUFF("puff"), UNIT("unit"), SACHET("sachet"), APPLICATION("application"), OTHER("other");

    companion object {
        fun fromRaw(raw: String?): DoseUnit = entries.firstOrNull { it.raw == raw } ?: OTHER
    }
}

/** `lifecycle(action, …)` of the reference (docs §12). */
enum class LifecycleAction(val raw: String) {
    PAUSE("pause"), RESUME("resume"), STOP("stop"), COMPLETE("complete"), EDIT_SCHEDULE("edit_schedule"),
    /** In-place `reminder_enabled` flip of the open row (no new schedule version). */
    SET_REMINDER_ENABLED("set_reminder_enabled");

    companion object {
        fun fromRaw(raw: String?): LifecycleAction? = entries.firstOrNull { it.raw == raw }
    }
}

/** `apply_dose_action(action, …)` of the reference (docs §11). */
enum class DoseAction(val raw: String) {
    TAKEN("taken"), SKIPPED("skipped"), SNOOZED("snoozed"), UNDO("undo");

    companion object {
        fun fromRaw(raw: String?): DoseAction? = entries.firstOrNull { it.raw == raw }
    }
}

/** One `medications` row. */
data class Medication(
    val id: String,
    val name: String,
    val genericName: String? = null,
    val brandName: String? = null,
    val strength: String? = null,
    val form: MedicationForm = MedicationForm.OTHER,
    val doseQuantity: Double = 1.0,
    val doseUnit: DoseUnit = DoseUnit.TABLET,
    val foodRelation: FoodRelation = FoodRelation.ANYTIME,
    val instructions: String? = null,
    /** `yyyy-MM-dd`, device-local day. */
    val startDate: String,
    /** `yyyy-MM-dd`, or null for "no end date". */
    val endDate: String? = null,
    val status: MedicationStatus = MedicationStatus.ACTIVE,
    val isPrn: Boolean = false,
    /** Relative to the medications photo root. */
    val photoPath: String? = null,
    /** `records.id` of the linked Health Record (no FK: the records DB is separate). */
    val relatedRecordId: String? = null,
    val createdMs: Long,
    val updatedMs: Long
) {
    val isActive: Boolean get() = status == MedicationStatus.ACTIVE
}

/**
 * One `medication_schedules` row. Rows are versioned: at most one row per medication has
 * [activeUntilMs] == null; edits insert a new row rather than changing an existing one.
 */
data class MedicationSchedule(
    val id: String,
    val medicationId: String,
    val frequency: ScheduleFrequency = ScheduleFrequency.DAILY,
    /** `HH:mm` local wall-clock strings, unique and ascending. */
    val times: List<String> = emptyList(),
    /** ISO weekdays 1 = Monday … 7 = Sunday; empty unless [frequency] is WEEKLY. */
    val days: List<Int> = emptyList(),
    val intervalHours: Int? = null,
    val anchorTime: String? = null,
    val reminderEnabled: Boolean = true,
    val activeFromMs: Long,
    val activeUntilMs: Long? = null,
    val createdMs: Long,
    val updatedMs: Long
) {
    val isOpen: Boolean get() = activeUntilMs == null
}

/** One `dose_logs` row. PRN doses have [scheduleId] == null and [scheduledAtMs] == [takenAtMs]. */
data class DoseLog(
    val id: String,
    val medicationId: String,
    val scheduleId: String? = null,
    val scheduledAtMs: Long,
    val status: DoseStatus,
    val takenAtMs: Long? = null,
    val snoozedUntilMs: Long? = null,
    /** Snapshot of the medication dose at logging time, so history survives later edits. */
    val doseQuantity: Double,
    val doseUnit: DoseUnit,
    val note: String? = null,
    val createdMs: Long,
    val updatedMs: Long
) {
    val isPrn: Boolean get() = scheduleId == null
}

/** Search text plus an optional status chip on the Medications home. */
data class MedicationFilter(
    val query: String = "",
    val status: MedicationStatus? = null
)

/**
 * One-shot request to open the Medications segment (and optionally a medication) from a
 * notification tap; [id] lets the consumer acknowledge exactly this request.
 */
data class MedicationRequest(
    val medicationId: String?,
    val id: Long = System.nanoTime()
)

/** `times_json` / `days_json` codec (compact JSON arrays, kotlinx.serialization like the records module). */
object ScheduleJson {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun encodeTimes(times: List<String>): String = JsonArray(times.map(::JsonPrimitive)).toString()

    fun decodeTimes(text: String?): List<String> =
        parseArray(text).mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.contentOrNull }

    fun encodeDays(days: List<Int>): String = JsonArray(days.map(::JsonPrimitive)).toString()

    fun decodeDays(text: String?): List<Int> =
        parseArray(text).mapNotNull { (it as? JsonPrimitive)?.intOrNull }

    private fun parseArray(text: String?): List<kotlinx.serialization.json.JsonElement> =
        text?.let { runCatching { json.parseToJsonElement(it) as? JsonArray }.getOrNull() }.orEmpty()
}
