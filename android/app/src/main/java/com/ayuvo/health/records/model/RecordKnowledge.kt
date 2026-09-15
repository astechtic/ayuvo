package com.ayuvo.health.records.model

/**
 * Phase 3 "Health knowledge base" value types (docs/health-records.md §19–§24). Enums store the
 * exact lowercase contract strings; unknown stored values fall back to a safe default.
 */

/** `observations.analyte_method` (§19); null = unmapped. */
enum class AnalyteMethod(val raw: String) {
    CATALOG("catalog"), USER_ALIAS("user_alias"), USER("user");

    companion object {
        fun fromRaw(raw: String?): AnalyteMethod? = entries.firstOrNull { it.raw == raw }
    }
}

/** `observations.observed_date_method` (§19): which source gave `observed_date`. */
object ObservedDateMethod {
    const val COLLECTION_DATE = "collection_date"
    const val REPORT_DATE = "report_date"
    const val DOCUMENT_DATE = "document_date"
    const val SORT_DATE = "sort_date"
    const val USER = "user"
}

/** One `observations` row. */
data class Observation(
    val id: String,
    val recordId: String,
    val fieldId: String? = null,
    val analyteId: String? = null,
    val analyteMethod: AnalyteMethod? = null,
    val rawName: String,
    val valueNum: Double? = null,
    val valueText: String,
    val unit: String? = null,
    val canonicalValue: Double? = null,
    val canonicalUnit: String? = null,
    val refLow: Double? = null,
    val refHigh: Double? = null,
    val refText: String? = null,
    val flag: ResultFlag = ResultFlag.UNKNOWN,
    val observedDate: String? = null,
    val observedDateMethod: String? = null,
    val method: ExtractionMethod,
    val confidence: Double = 0.0,
    val state: FieldState = FieldState.SUGGESTED,
    val sourcePage: Int? = null,
    val sourceBbox: String? = null,
    val evidence: String? = null,
    val excludedFromTrends: Boolean = false,
    val createdMs: Long = 0,
    val updatedMs: Long = 0
)

/**
 * A user edit of one observation (§24, reference `edit_observation` patch). Each `set*` flag marks a
 * key as present; a present null clears it (unit, ref text, analyte = unmapped).
 */
data class ObservationEdit(
    val setValue: Boolean = false,
    val value: String? = null,
    val setUnit: Boolean = false,
    val unit: String? = null,
    val setRefText: Boolean = false,
    val refText: String? = null,
    val setAnalyte: Boolean = false,
    val analyteId: String? = null,
    val setObservedDate: Boolean = false,
    val observedDate: String? = null,
    val excludedFromTrends: Boolean? = null,
    val remove: Boolean = false,
    /** Remapping also stores an `analyte_user_aliases` row for this test name (§19). */
    val rememberAlias: Boolean = true
) {
    val isEmpty: Boolean get() = !setValue && !setUnit && !setRefText && !setAnalyte && !setObservedDate && excludedFromTrends == null && !remove
}

/** Input of "Add value" (§24): user observation without a source. */
data class NewUserObservation(
    val recordId: String,
    val analyteId: String?,
    val name: String,
    val valueText: String,
    val unit: String?,
    val observedDate: String?,
    val refText: String? = null
)

/** `record_links.kind` (§19). */
enum class LinkKind(val raw: String) {
    FOLLOW_UP("follow_up"), PRESCRIPTION_FOR("prescription_for"), SAME_EPISODE("same_episode"),
    PREVIOUS_REPORT("previous_report"), RELATED("related"), SPLIT_FROM("split_from");

    companion object {
        fun fromRaw(raw: String?): LinkKind = entries.firstOrNull { it.raw == raw } ?: RELATED
    }
}

enum class LinkOrigin(val raw: String) {
    USER("user"), SUGGESTED("suggested");

    companion object {
        fun fromRaw(raw: String?): LinkOrigin = entries.firstOrNull { it.raw == raw } ?: SUGGESTED
    }
}

enum class LinkStatus(val raw: String) {
    SUGGESTED("suggested"), ACCEPTED("accepted"), REJECTED("rejected");

    companion object {
        fun fromRaw(raw: String?): LinkStatus = entries.firstOrNull { it.raw == raw } ?: SUGGESTED
    }
}

/** One `record_links` row; [aId] < [bId] in string order. */
data class RecordLink(
    val aId: String,
    val bId: String,
    val kind: LinkKind,
    val origin: LinkOrigin,
    val status: LinkStatus,
    val score: Double = 0.0,
    val reasons: List<String> = emptyList(),
    val createdMs: Long = 0,
    val updatedMs: Long = 0
) {
    fun other(recordId: String): String = if (recordId == aId) bId else aId

    /** Linked = shown under "Linked" (accepted suggestions and user links). */
    val isLinked: Boolean get() = status == LinkStatus.ACCEPTED

    companion object {
        /** The stored ordered pair of two ids. */
        fun pair(x: String, y: String): Pair<String, String> = if (x < y) x to y else y to x
    }
}

/** A link with the record on its other side. */
data class RelatedRecord(val link: RecordLink, val record: HealthRecord)

/** `entities.kind`. */
enum class EntityKind(val raw: String) {
    DOCTOR("doctor"), FACILITY("facility");

    companion object {
        fun fromRaw(raw: String?): EntityKind = entries.firstOrNull { it.raw == raw } ?: DOCTOR
    }
}

/** `record_entities.role`. */
object EntityRole {
    const val DOCTOR = "doctor"
    const val REFERRER = "referrer"
    const val FACILITY = "facility"
}

/** One `entities` row with how many records reference it (filter pickers). */
data class HealthEntity(
    val id: String,
    val kind: EntityKind,
    val displayName: String,
    val normalizedName: String,
    val specialty: String? = null,
    val recordCount: Int = 0
)

/** §23 Values group: one observation hit with its record. */
data class ValueHit(
    val observation: Observation,
    val displayName: String,
    val record: HealthRecord
)

/** §23 analyte condition of a parsed query: a flag, or a comparison on `canonical_value`. */
data class AnalyteCondition(
    val analyteId: String,
    /** `low`, `high`, `abnormal`, `normal`, `critical`; null for a comparison. */
    val flag: String? = null,
    /** `>`, `>=`, `<`, `<=`; null for a flag condition. */
    val op: String? = null,
    /** The number as typed in [unit] (reference `value`). */
    val value: Double? = null,
    val unit: String? = null,
    /** [value] converted to the analyte's canonical unit; compared with `canonical_value`. */
    val canonicalValue: Double? = null,
    val canonicalUnit: String? = null
)
