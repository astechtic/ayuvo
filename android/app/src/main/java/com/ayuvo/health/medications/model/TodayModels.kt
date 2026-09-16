package com.ayuvo.health.medications.model

/**
 * Result types of the shared medication logic (docs/medications.md §6–§15). Field names mirror
 * the reference's JSON keys one to one so the vector tests and the store read the same shapes.
 */

/** One scheduled dose instance produced by `expand_occurrences` (§6). */
data class Occurrence(
    val medicationId: String,
    val scheduleId: String,
    val scheduledAtMs: Long,
    /** `yyyy-MM-dd` in the planning zone; empty when the occurrence was rebuilt from a log. */
    val localDate: String = "",
    /** `HH:mm` slot; empty when rebuilt from a log. */
    val slot: String = ""
)

/** `resolve_dose_status` (§7). */
data class DoseResolution(
    val status: DoseStatus,
    val deadlineMs: Long,
    val isLate: Boolean
)

/** `materialize_missed` op: insert a new `missed` row or update a stored `snoozed` row (§7). */
data class MissedOp(val op: String, val log: DoseLog) {
    companion object {
        const val INSERT = "insert"
        const val UPDATE = "update"
    }
}

enum class TimelineKind(val raw: String) {
    SCHEDULED("scheduled"), PRN("prn")
}

/** One row of the Today screen (§8). */
data class TimelineItem(
    val medicationId: String,
    val scheduleId: String?,
    val scheduledAtMs: Long,
    val status: DoseStatus,
    val isLate: Boolean,
    val logId: String?,
    val snoozedUntilMs: Long?,
    val doseQuantity: Double,
    val doseUnit: DoseUnit,
    val kind: TimelineKind
) {
    /** The occurrence key used for dose actions on scheduled items. */
    val occurrence: Occurrence get() = Occurrence(medicationId, scheduleId ?: "", scheduledAtMs)
}

data class TimelineSlot(val slot: String, val items: List<TimelineItem>)

data class PrnRow(val medicationId: String, val todayCount: Int, val lastTakenMs: Long?)

data class TodaySummary(
    val total: Int = 0,
    val taken: Int = 0,
    val upcoming: Int = 0,
    val due: Int = 0,
    val snoozed: Int = 0,
    val missed: Int = 0,
    val skipped: Int = 0
)

/** `today_timeline` (§8). [medications] is filled by the store for display; it is not part of the contract output. */
data class TodayTimeline(
    val date: String,
    val summary: TodaySummary,
    val groups: List<TimelineSlot>,
    val prn: List<PrnRow>,
    val medications: Map<String, Medication> = emptyMap()
) {
    val isEmpty: Boolean get() = groups.isEmpty() && prn.isEmpty()
}

/** `adherence` (§9): `percent = floor(taken * 100 / expected + 0.5)`. */
data class AdherenceSummary(
    val taken: Int,
    val expected: Int,
    val percent: Int,
    val hasData: Boolean
)

enum class ReminderKind(val raw: String) {
    SCHEDULED("scheduled"), SNOOZE("snooze")
}

/** One planned reminder (§10). Identity = `medicationId:scheduledAtMs`. */
data class ReminderEntry(
    val medicationId: String,
    val scheduleId: String?,
    val scheduledAtMs: Long,
    val fireAtMs: Long,
    val kind: ReminderKind
) {
    val identity: String get() = "$medicationId:$scheduledAtMs"
}

data class ReminderPlan(
    val entries: List<ReminderEntry>,
    val nextFireMs: Long?,
    val truncated: Boolean
)

/** `apply_dose_action` (§11). [op] is `insert`, `update` or `delete`; [log] is the row to write (or delete). */
data class DoseActionResult(
    val ok: Boolean,
    val error: String?,
    val log: DoseLog?,
    val op: String?
)

/** `log_prn_dose` (§11). */
data class PrnLogResult(val ok: Boolean, val error: String?, val log: DoseLog?)

/** One `lifecycle` write (§12): `set_status` carries [status]; the schedule ops carry [id]. */
data class LifecycleOp(val op: String, val id: String? = null, val status: MedicationStatus? = null) {
    companion object {
        const val SET_STATUS = "set_status"
        const val CLOSE_SCHEDULE = "close_schedule"
        const val INSERT_SCHEDULE = "insert_schedule"
        const val UPDATE_SCHEDULE = "update_schedule"
    }
}

data class ValidationError(val field: String, val code: String)

/** `lifecycle` (§12): every schedule row of the medication after the change plus the ops to run. */
data class LifecycleResult(
    val ok: Boolean,
    val error: String?,
    val medication: Medication,
    val schedules: List<MedicationSchedule>,
    val ops: List<LifecycleOp>,
    /** Only set for `invalid_schedule`. */
    val errors: List<ValidationError>? = null
)

/** `frequency_hint` (§13): a pre-filled Add-medication draft; [notes] lists every default applied. */
data class FrequencyDraft(
    val name: String,
    val strength: String?,
    val form: MedicationForm,
    val doseQuantity: Double?,
    val doseUnit: DoseUnit,
    val isPrn: Boolean,
    val frequency: ScheduleFrequency?,
    val times: List<String>,
    val days: List<Int>,
    val intervalHours: Int?,
    val anchorTime: String?,
    val durationDays: Int?,
    val foodRelation: FoodRelation,
    val instructions: String?,
    val confidence: Double,
    val notes: List<String>
)

/** Every row of the three tables; the archive export input and the merge's local side (§14). */
data class MedicationsSnapshot(
    val medications: List<Medication> = emptyList(),
    val schedules: List<MedicationSchedule> = emptyList(),
    val doseLogs: List<DoseLog> = emptyList()
)

/** Outcome of importing an `ayuvo-medications` archive (§14). */
data class ImportResult(
    val ok: Boolean,
    val error: String?,
    val inserted: Int = 0,
    val updated: Int = 0,
    val skipped: Int = 0
)

/** Keyset cursor for dose-log history pages (`scheduled_at_ms DESC, id DESC`). */
data class DoseLogCursor(val scheduledAtMs: Long, val id: String) {
    companion object {
        fun after(log: DoseLog): DoseLogCursor = DoseLogCursor(log.scheduledAtMs, log.id)
    }
}
