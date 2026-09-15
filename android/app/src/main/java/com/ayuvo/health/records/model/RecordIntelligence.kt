package com.ayuvo.health.records.model

/**
 * Phase 2 "Intelligence" value types (docs/health-records.md §8–§17). Every enum stores the exact
 * lowercase contract string; unknown stored values fall back to a safe default.
 */

/** `record_fields.field_key` (§8). */
object FieldKey {
    const val DOCTOR_NAME = "doctor_name"
    const val DOCTOR_SPECIALTY = "doctor_specialty"
    const val FACILITY = "facility"
    const val DEPARTMENT = "department"
    const val PATIENT_NAME = "patient_name"
    const val PATIENT_AGE = "patient_age"
    const val PATIENT_SEX = "patient_sex"
    const val REPORT_NAME = "report_name"
    const val TEST_RESULT = "test_result"
    const val DIAGNOSIS = "diagnosis"
    const val SYMPTOM = "symptom"
    const val MEDICATION = "medication"
    const val PROCEDURE = "procedure"
    const val RECOMMENDATION = "recommendation"
    const val FOLLOW_UP_DATE = "follow_up_date"
    const val VISIT_DATE = "visit_date"
    const val COLLECTION_DATE = "collection_date"
    const val REPORT_DATE = "report_date"
    const val PRESCRIPTION_DATE = "prescription_date"
    const val ADMISSION_DATE = "admission_date"
    const val DISCHARGE_DATE = "discharge_date"
    const val DOCUMENT_TIME = "document_time"
    const val LOCATION = "location"

    val ALL: List<String> = listOf(
        DOCTOR_NAME, DOCTOR_SPECIALTY, FACILITY, DEPARTMENT, PATIENT_NAME, PATIENT_AGE, PATIENT_SEX,
        REPORT_NAME, TEST_RESULT, DIAGNOSIS, SYMPTOM, MEDICATION, PROCEDURE, RECOMMENDATION,
        FOLLOW_UP_DATE, VISIT_DATE, COLLECTION_DATE, REPORT_DATE, PRESCRIPTION_DATE, ADMISSION_DATE,
        DISCHARGE_DATE, DOCUMENT_TIME, LOCATION
    )

    /** Keys that legitimately hold several values; every other key is single-valued (§8.1). */
    val MULTI_VALUED: Set<String> = setOf(TEST_RESULT, DIAGNOSIS, SYMPTOM, MEDICATION, PROCEDURE, RECOMMENDATION)

    val DATE_KEYS: Set<String> = setOf(
        FOLLOW_UP_DATE, VISIT_DATE, COLLECTION_DATE, REPORT_DATE, PRESCRIPTION_DATE, ADMISSION_DATE, DISCHARGE_DATE
    )

    /** Order used for `document_date` derivation (§8.1). */
    val DOCUMENT_DATE_ORDER: List<String> = listOf(REPORT_DATE, COLLECTION_DATE, PRESCRIPTION_DATE, DISCHARGE_DATE, VISIT_DATE)

    /** Key fields reviewed when uncertain (§15). */
    fun isKeyField(key: String): Boolean = key == REPORT_NAME || key == DOCTOR_NAME || key == FACILITY || key in DATE_KEYS

    fun isSingleValued(key: String): Boolean = key !in MULTI_VALUED
}

enum class ExtractionMethod(val raw: String) {
    FILE_METADATA("file_metadata"), PDF_TEXT("pdf_text"), OCR("ocr"), RULES("rules"),
    AI_LOCAL("ai_local"), AI_CLOUD("ai_cloud"), USER("user");

    val isAi: Boolean get() = this == AI_LOCAL || this == AI_CLOUD

    companion object {
        fun fromRaw(raw: String?): ExtractionMethod = entries.firstOrNull { it.raw == raw } ?: RULES
    }
}

enum class FieldState(val raw: String) {
    SUGGESTED("suggested"), CONFIRMED("confirmed"), REJECTED("rejected"), USER("user");

    /** Rows the pipeline never touches again (§8.1). */
    val isSettled: Boolean get() = this != SUGGESTED

    companion object {
        fun fromRaw(raw: String?): FieldState = entries.firstOrNull { it.raw == raw } ?: SUGGESTED
    }
}

enum class ResultFlag(val raw: String) {
    LOW("low"), HIGH("high"), CRITICAL_LOW("critical_low"), CRITICAL_HIGH("critical_high"),
    NORMAL("normal"), ABNORMAL("abnormal"), UNKNOWN("unknown");

    /** Anything the report marks outside its range. */
    val isAbnormal: Boolean get() = this != NORMAL && this != UNKNOWN

    companion object {
        fun fromRaw(raw: String?): ResultFlag = entries.firstOrNull { it.raw == raw } ?: UNKNOWN
    }
}

enum class HighlightSection(val raw: String) {
    IMPORTANT("important"), MEDICATIONS("medications"), RECOMMENDATIONS("recommendations"), SUMMARY("summary");

    companion object {
        fun fromRaw(raw: String?): HighlightSection = entries.firstOrNull { it.raw == raw } ?: IMPORTANT
    }
}

/** `processing_jobs.stage`, in pipeline order (§8, §9; Phase 3 adds `observations` and `relations`). */
enum class ProcessingStage(val raw: String) {
    TEXT("text"), CLASSIFY("classify"), BOUNDARIES("boundaries"), RULES("rules"), AI("ai"),
    VALIDATE("validate"), HIGHLIGHTS("highlights"), REVIEW("review"), NEAR_DUPLICATE("near_duplicate"),
    OBSERVATIONS("observations"), RELATIONS("relations"), INDEX("index"), DONE("done");

    fun next(): ProcessingStage = entries.getOrElse(ordinal + 1) { DONE }

    companion object {
        fun fromRaw(raw: String?): ProcessingStage = entries.firstOrNull { it.raw == raw } ?: TEXT
    }
}

/** `records.ai_mode_used`. */
enum class AiModeUsed(val raw: String) {
    NONE("none"), LOCAL("local"), CLOUD("cloud");

    companion object {
        fun fromRaw(raw: String?): AiModeUsed = entries.firstOrNull { it.raw == raw } ?: NONE
    }
}

/** Preference `healthRecordsAiMode` (§16). Null (unset) is handled by callers. */
enum class RecordsAiMode(val raw: String) {
    LOCAL("local"), CLOUD("cloud"), ASK("ask"), OFF("off");

    companion object {
        fun fromRaw(raw: String?): RecordsAiMode? = entries.firstOrNull { it.raw == raw }
    }
}

enum class DuplicateReason(val raw: String) {
    CHECKSUM("checksum"), PHASH("phash"), CONTENT("content");

    companion object {
        fun fromRaw(raw: String?): DuplicateReason = entries.firstOrNull { it.raw == raw } ?: CONTENT
    }
}

enum class DuplicateResolution(val raw: String) {
    PENDING("pending"), KEEP_BOTH("keep_both"), REPLACED("replaced"), MERGED("merged"), CANCELLED("cancelled");

    companion object {
        fun fromRaw(raw: String?): DuplicateResolution = entries.firstOrNull { it.raw == raw } ?: PENDING
    }
}

enum class SplitStatus(val raw: String) {
    PENDING("pending"), ACCEPTED("accepted"), REJECTED("rejected");

    companion object {
        fun fromRaw(raw: String?): SplitStatus = entries.firstOrNull { it.raw == raw } ?: PENDING
    }
}

/** `processing_error` values (§9). */
object ProcessingError {
    const val TEXT_UNAVAILABLE = "text_unavailable"
    const val OCR_FAILED = "ocr_failed"
    const val AI_FAILED = "ai_failed"
    const val AI_UNAVAILABLE = "ai_unavailable"
    const val PROTECTED_PDF = "protected_pdf"
    const val UNSUPPORTED = "unsupported"
}

/** One `record_fields` row. */
data class RecordField(
    val id: String,
    val recordId: String,
    val key: String,
    val valueText: String,
    val valueJson: String? = null,
    val method: ExtractionMethod,
    val confidence: Double,
    val state: FieldState = FieldState.SUGGESTED,
    val sourcePage: Int? = null,
    val sourceBbox: String? = null,
    val evidence: String? = null,
    val createdMs: Long = 0,
    val updatedMs: Long = 0
)

/** A value produced by rules or AI before [com.ayuvo.health.records.processing.ExtractionWriter] decides what to store. */
data class ExtractedField(
    val key: String,
    val valueText: String,
    val valueJson: String? = null,
    val method: ExtractionMethod,
    val confidence: Double,
    val sourcePage: Int? = null,
    val sourceBbox: String? = null,
    val evidence: String? = null
)

/** One `record_highlights` row. */
data class RecordHighlight(
    val id: String,
    val recordId: String,
    val section: HighlightSection,
    val text: String,
    val method: ExtractionMethod,
    val provider: String? = null,
    val fieldId: String? = null,
    val sourcePage: Int? = null,
    val confidence: Double = 0.0,
    val dismissed: Boolean = false,
    val position: Int = 0,
    val createdMs: Long = 0
)

/** One `processing_jobs` row. */
data class ProcessingJob(
    val recordId: String,
    val stage: ProcessingStage,
    val attempts: Int = 0,
    val nextAttemptMs: Long = 0,
    val lastError: String? = null,
    /** Per-record AI choice from the Ask flow (`local` / `cloud` / `off`), else null = preference. */
    val requestedMode: String? = null,
    val awaitingConsent: Boolean = false,
    val updatedMs: Long = 0
)

data class DuplicateCandidate(
    val recordId: String,
    val existingId: String,
    val reason: DuplicateReason,
    val score: Double,
    val resolution: DuplicateResolution = DuplicateResolution.PENDING,
    val createdMs: Long = 0
)

/** `split_proposals.segments_json` entry; pages are 0-based inclusive. */
data class SplitSegment(
    val pageStart: Int,
    val pageEnd: Int,
    val recordType: RecordType,
    val title: String,
    val confidence: Double
)

data class SplitProposal(
    val recordId: String,
    val segments: List<SplitSegment>,
    val status: SplitStatus = SplitStatus.PENDING,
    val createdMs: Long = 0,
    val updatedMs: Long = 0
)

/** Intelligence columns of `records` (v2) that the list projection does not carry. */
data class RecordIntelligenceColumns(
    val phash: String? = null,
    val textSignature: String? = null,
    val aiModeUsed: AiModeUsed = AiModeUsed.NONE,
    val aiProvider: String? = null,
    val typeConfidence: Double? = null,
    val typeMethod: ExtractionMethod? = null
)
