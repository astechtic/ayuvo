package com.ayuvo.health.records.model

/**
 * Health Records value types. Every enum stores the exact lowercase string from
 * docs/health-records.md §3; unknown stored values fall back to a safe default so a
 * newer schema written by another build never crashes the list.
 */

enum class RecordCategory(val raw: String) {
    LAB_REPORTS("lab_reports"),
    PRESCRIPTIONS("prescriptions"),
    DOCTOR_VISITS("doctor_visits"),
    IMAGING("imaging"),
    HOSPITALIZATION("hospitalization"),
    PROCEDURES("procedures"),
    VACCINATION("vaccination"),
    MEDICATION("medication"),
    INSURANCE_BILLS("insurance_bills"),
    PERSONAL_NOTES("personal_notes"),
    OTHER("other");

    companion object {
        fun fromRaw(raw: String?): RecordCategory = entries.firstOrNull { it.raw == raw } ?: OTHER
    }
}

enum class RecordType(val raw: String, val defaultCategory: RecordCategory) {
    LAB_REPORT("lab_report", RecordCategory.LAB_REPORTS),
    PRESCRIPTION("prescription", RecordCategory.PRESCRIPTIONS),
    CONSULTATION_NOTE("consultation_note", RecordCategory.DOCTOR_VISITS),
    DISCHARGE_SUMMARY("discharge_summary", RecordCategory.HOSPITALIZATION),
    IMAGING_REPORT("imaging_report", RecordCategory.IMAGING),
    DIAGNOSTIC_REPORT("diagnostic_report", RecordCategory.LAB_REPORTS),
    MEDICATION_LIST("medication_list", RecordCategory.MEDICATION),
    VACCINATION_RECORD("vaccination_record", RecordCategory.VACCINATION),
    BILL("bill", RecordCategory.INSURANCE_BILLS),
    INSURANCE("insurance", RecordCategory.INSURANCE_BILLS),
    PERSONAL_NOTE("personal_note", RecordCategory.PERSONAL_NOTES),
    OTHER("other", RecordCategory.OTHER);

    companion object {
        fun fromRaw(raw: String?): RecordType = entries.firstOrNull { it.raw == raw } ?: OTHER
    }
}

enum class RecordSource(val raw: String) {
    IMPORT("import"), PHOTOS("photos"), CAMERA("camera"), SCAN("scan"), PASTE("paste"),
    NOTE("note"), SHARE_IN("share_in"), OPEN_IN("open_in");

    companion object {
        fun fromRaw(raw: String?): RecordSource = entries.firstOrNull { it.raw == raw } ?: IMPORT
    }
}

enum class ImportMethod(val raw: String) {
    FILE_PICKER("file_picker"), PHOTO_PICKER("photo_picker"), CAMERA("camera"),
    DOCUMENT_SCANNER("document_scanner"), PASTE_TEXT("paste_text"), NOTE_EDITOR("note_editor"),
    SHARE_SHEET("share_sheet"), OPEN_IN("open_in"), ARCHIVE_RESTORE("archive_restore");

    companion object {
        fun fromRaw(raw: String?): ImportMethod = entries.firstOrNull { it.raw == raw } ?: FILE_PICKER
    }
}

enum class RecordFileType(val raw: String) {
    PDF("pdf"), IMAGE("image"), TEXT("text"), OTHER("other");

    companion object {
        fun fromRaw(raw: String?): RecordFileType = entries.firstOrNull { it.raw == raw } ?: OTHER
    }
}

enum class ProcessingStatus(val raw: String) {
    SAVED("saved"), QUEUED("queued"), EXTRACTING_TEXT("extracting_text"), ANALYZING("analyzing"),
    AI_PENDING_CONSENT("ai_pending_consent"), READY("ready"), FAILED_PARTIAL("failed_partial");

    companion object {
        fun fromRaw(raw: String?): ProcessingStatus = entries.firstOrNull { it.raw == raw } ?: SAVED
    }
}

enum class ReviewStatus(val raw: String) {
    NONE("none"), NEEDS_REVIEW("needs_review"), REVIEWED("reviewed");

    companion object {
        fun fromRaw(raw: String?): ReviewStatus = entries.firstOrNull { it.raw == raw } ?: NONE
    }
}

enum class DatePrecision(val raw: String) {
    DAY("day"), MONTH("month"), YEAR("year");

    companion object {
        fun fromRaw(raw: String?): DatePrecision? = entries.firstOrNull { it.raw == raw }
    }
}

enum class DateMethod(val raw: String) {
    FILE_METADATA("file_metadata"), PDF_TEXT("pdf_text"), OCR("ocr"), RULES("rules"),
    AI_LOCAL("ai_local"), AI_CLOUD("ai_cloud"), USER("user"), IMPORT_TIME("import_time");

    companion object {
        fun fromRaw(raw: String?): DateMethod? = entries.firstOrNull { it.raw == raw }
    }
}

enum class TextSource(val raw: String) {
    PDF_TEXT("pdf_text"), OCR("ocr"), USER("user"), PLAIN("plain");

    companion object {
        fun fromRaw(raw: String?): TextSource = entries.firstOrNull { it.raw == raw } ?: PLAIN
    }
}

/** One `records` row. [seq] is 0 until the row is inserted. */
data class HealthRecord(
    val seq: Long = 0,
    val id: String,
    val parentId: String? = null,
    val pageStart: Int? = null,
    val pageEnd: Int? = null,
    val title: String,
    val recordType: RecordType = RecordType.OTHER,
    val category: RecordCategory = RecordCategory.OTHER,
    val source: RecordSource,
    val importMethod: ImportMethod,
    val sourceApp: String? = null,
    val originalFilename: String? = null,
    val createdMs: Long,
    val updatedMs: Long,
    /** `yyyy-MM-dd`, or null when unknown. */
    val documentDate: String? = null,
    val documentDatePrecision: DatePrecision? = null,
    val documentDateMethod: DateMethod? = null,
    /** Timeline day (§5): [documentDate], else the device-local day of [createdMs] at import. */
    val sortDate: String = RecordSortDate.forRecord(documentDate, createdMs),
    val mimeType: String,
    val fileType: RecordFileType,
    val fileSize: Long = 0,
    val pageCount: Int = 0,
    /** Relative to the records files root. */
    val filePath: String? = null,
    val thumbnailPath: String? = null,
    val checksumSha256: String? = null,
    val processingStatus: ProcessingStatus = ProcessingStatus.SAVED,
    val processingError: String? = null,
    val reviewStatus: ReviewStatus = ReviewStatus.NONE,
    val favorite: Boolean = false,
    val archived: Boolean = false,
    val notes: String? = null,
    /** `records.ai_mode_used` (v2). */
    val aiModeUsed: AiModeUsed = AiModeUsed.NONE,
    /** `records.shared_count` (v4, §33): successful shares of this record. */
    val sharedCount: Int = 0,
    /** `records.last_shared_ms` (v4, §33). */
    val lastSharedMs: Long? = null
) {
    val isReceived: Boolean get() = source == RecordSource.SHARE_IN || source == RecordSource.OPEN_IN

    /** A split child that shows only [pageStart]..[pageEnd] of its parent's file. */
    val isSplitChild: Boolean get() = parentId != null && pageStart != null && pageEnd != null

    val isProcessing: Boolean get() = processingStatus == ProcessingStatus.QUEUED ||
        processingStatus == ProcessingStatus.EXTRACTING_TEXT || processingStatus == ProcessingStatus.ANALYZING

}

/** `records.sort_date` rule (docs/health-records.md §5). */
object RecordSortDate {
    /** The device-local calendar day of [createdMs] (`yyyy-MM-dd`). */
    fun localDay(createdMs: Long, zone: java.time.ZoneId = java.time.ZoneId.systemDefault()): String =
        java.time.Instant.ofEpochMilli(createdMs).atZone(zone).toLocalDate().toString()

    fun forRecord(
        documentDate: String?,
        createdMs: Long,
        zone: java.time.ZoneId = java.time.ZoneId.systemDefault()
    ): String = documentDate ?: localDay(createdMs, zone)
}

data class RecordPage(
    val recordId: String,
    val pageIndex: Int,
    val text: String?,
    val textSource: TextSource,
    val ocrConfidence: Double? = null,
    val width: Int? = null,
    val height: Int? = null,
    val blocksJson: String? = null
)

data class RecordTag(val id: String, val name: String)

/** The quick filter chips on the Records home. */
enum class RecordFilter {
    ALL, REPORTS, PRESCRIPTIONS, LAB, IMAGING, DOCTOR_NOTES, DISCHARGE, BILLS,
    IMAGES, PDFS, NOTES, RECEIVED, FAVORITES, NEEDS_REVIEW, ARCHIVED
}

/** The Filters sheet (plan §2) plus the structured part of a parsed search (§17). */
data class RecordAdvancedFilters(
    /** Inclusive `sort_date` bounds, `yyyy-MM-dd`. */
    val dateFrom: String? = null,
    val dateTo: String? = null,
    /** Name prefix matched against doctor entities (§19; normalized like the entity name). */
    val doctor: String? = null,
    /** Name prefix matched against facility entities (§19). */
    val facility: String? = null,
    val categories: Set<RecordCategory> = emptySet(),
    val types: Set<RecordType> = emptySet(),
    /** `abnormal`, `low`, `high`, `critical` (§17 flags filter). */
    val flags: Set<String> = emptySet(),
    val tagIds: Set<String> = emptySet(),
    val aiProcessed: Boolean = false,
    val userConfirmed: Boolean = false,
    val favorites: Boolean = false,
    val received: Boolean = false,
    val needsReview: Boolean = false,
    val archived: Boolean = false,
    /** Phase 3: doctor entities picked in the Filters sheet (any of them). */
    val doctorEntityIds: Set<String> = emptySet(),
    /** Phase 3: facility entities picked in the Filters sheet (any of them). */
    val facilityEntityIds: Set<String> = emptySet(),
    /** Phase 3: records with a matching observation for every condition (§23). */
    val analyteConditions: List<AnalyteCondition> = emptyList(),
    /** Phase 3: "Test" filter — records with an observation of any of these analytes. */
    val analyteIds: Set<String> = emptySet()
) {
    /** Count shown on the Filters chip (sheet-owned fields only). */
    val activeCount: Int get() = listOf(
        dateFrom != null || dateTo != null, doctor != null || doctorEntityIds.isNotEmpty(),
        facility != null || facilityEntityIds.isNotEmpty(), categories.isNotEmpty(),
        types.isNotEmpty(), flags.isNotEmpty(), tagIds.isNotEmpty(), aiProcessed, userConfirmed, analyteIds.isNotEmpty()
    ).count { it }

    val isEmpty: Boolean get() = this == RecordAdvancedFilters()

    /** Union of two filter sets: bounds intersect, sets merge, booleans OR. */
    fun and(other: RecordAdvancedFilters): RecordAdvancedFilters = RecordAdvancedFilters(
        dateFrom = listOfNotNull(dateFrom, other.dateFrom).maxOrNull(),
        dateTo = listOfNotNull(dateTo, other.dateTo).minOrNull(),
        doctor = doctor ?: other.doctor,
        facility = facility ?: other.facility,
        categories = categories + other.categories,
        types = types + other.types,
        flags = flags + other.flags,
        tagIds = tagIds + other.tagIds,
        aiProcessed = aiProcessed || other.aiProcessed,
        userConfirmed = userConfirmed || other.userConfirmed,
        favorites = favorites || other.favorites,
        received = received || other.received,
        needsReview = needsReview || other.needsReview,
        archived = archived || other.archived,
        doctorEntityIds = doctorEntityIds + other.doctorEntityIds,
        facilityEntityIds = facilityEntityIds + other.facilityEntityIds,
        analyteConditions = (analyteConditions + other.analyteConditions).distinct(),
        analyteIds = analyteIds + other.analyteIds
    )
}

data class RecordQuery(
    val filter: RecordFilter = RecordFilter.ALL,
    /** Raw search text (Phase 1 behaviour when [terms] is null). */
    val search: String = "",
    val advanced: RecordAdvancedFilters = RecordAdvancedFilters(),
    /** Parsed free-text terms (§17); when set they replace [search] for the FTS MATCH. */
    val terms: List<String>? = null
) {
    val hasTextMatch: Boolean get() = (terms ?: RecordsSearchTerms.split(search)).isNotEmpty()
}

/** Folded search terms of raw text (Phase 1 search). */
object RecordsSearchTerms {
    fun split(input: String): List<String> =
        com.ayuvo.health.records.processing.RecordText.fold(input)
            .split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }.distinct()
}

/** Keyset position in the timeline ordering (docs/health-records.md §5). */
data class RecordCursor(val sortDate: String, val createdMs: Long, val seq: Long)

data class RecordPageResult(val items: List<HealthRecord>, val next: RecordCursor?)

/** Partial update; null leaves the column untouched. [clearDocumentDate] sets it to NULL. */
data class RecordPatch(
    val title: String? = null,
    val documentDate: String? = null,
    val clearDocumentDate: Boolean = false,
    val recordType: RecordType? = null,
    val category: RecordCategory? = null,
    val notes: String? = null,
    val favorite: Boolean? = null,
    val archived: Boolean? = null
)

/** Background (post-import) results written in one statement. */
data class RecordProcessingUpdate(
    val pageCount: Int? = null,
    val thumbnailPath: String? = null,
    val documentDate: String? = null,
    val documentDatePrecision: DatePrecision? = null,
    val documentDateMethod: DateMethod? = null,
    val status: ProcessingStatus,
    val error: String? = null
)
