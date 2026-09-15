package com.ayuvo.health.records.processing

import com.ayuvo.health.records.model.RecordField
import com.ayuvo.health.records.model.RecordType
import com.ayuvo.health.records.model.ReviewStatus

/** Inputs of [ReviewRules.evaluate]. */
data class ReviewInput(
    val recordType: RecordType,
    val typeConfidence: Double?,
    val documentDate: String?,
    val fields: List<RecordField>,
    val pendingSplit: Boolean,
    val pendingDuplicate: Boolean,
    val processingError: String?,
    /** Any suggested AI item that came from a page sent as an image. */
    val hasAiImageItems: Boolean,
    val currentStatus: ReviewStatus,
    /** `records.type_method`; `user` means the type is settled. */
    val typeMethod: String? = null,
    /** Mean `record_pages.ocr_confidence` over OCR'd pages; null when unknown. */
    val ocrConfidenceMean: Double? = null
)

/** §15 review status, ported from the reference `review_status`. */
object ReviewRules {

    /** Reference row shape for [statusOf]. */
    data class Row(val key: String, val valueText: String, val valueJson: String?, val confidence: Double, val state: String, val fromImage: Boolean = false)

    data class Result(val status: String, val reasons: List<String>)

    private val KEY_FIELDS = setOf("report_name", "doctor_name", "facility") + DateDetector.DOCUMENT_DATE_ORDER + listOf("admission_date", "follow_up_date")

    fun statusOf(
        recordType: String?,
        typeConfidence: Double?,
        typeMethod: String?,
        documentDate: String?,
        processingError: String?,
        reviewStatus: String?,
        rows: List<Row>,
        pendingSplit: Boolean,
        pendingDuplicate: Boolean,
        ocrConfidence: Double? = null
    ): Result {
        val reasons = mutableListOf<String>()
        val reviewed = reviewStatus == "reviewed"
        if (!reviewed) {
            if (typeMethod != "user" && (recordType == "other" || (typeConfidence ?: 0.0) < 0.7)) reasons += "type_uncertain"
            if (documentDate.isNullOrEmpty()) reasons += "no_date"
        }
        for (slot in ExtractionWriter.conflictSlots(rows.map { ExtractionWriter.SlotRow(it.key, it.valueText, it.valueJson, it.state) })) {
            val key = slot.substringBefore(':')
            val states = rows.filter { it.key == key && it.state != "rejected" }.map { it.state }
            if ("suggested" in states) reasons += "conflict:$slot"
        }
        reasons += rows.filter { it.key in KEY_FIELDS && it.state == "suggested" && it.confidence < 0.8 }.map { it.key }.toSortedSet().map { "low_confidence:$it" }
        if (pendingSplit) reasons += "split_pending"
        if (pendingDuplicate) reasons += "duplicate_pending"
        if (!reviewed && (processingError == "text_unavailable" || processingError == "ocr_failed")) reasons += "text_failed"
        if (!reviewed && ocrConfidence != null && ocrConfidence < 0.6) reasons += "ocr_low_confidence"
        if (rows.any { it.fromImage && it.state == "suggested" }) reasons += "ai_image_item"
        if (reasons.isNotEmpty()) return Result("needs_review", reasons)
        return Result(if (reviewed) "reviewed" else "none", emptyList())
    }

    fun reasons(input: ReviewInput): List<String> {
        val rows = input.fields.map { Row(it.key, it.valueText, it.valueJson, it.confidence, it.state.raw) }.toMutableList()
        val result = statusOf(
            input.recordType.raw, input.typeConfidence, input.typeMethod, input.documentDate, input.processingError,
            input.currentStatus.raw, rows, input.pendingSplit, input.pendingDuplicate, input.ocrConfidenceMean
        )
        return if (input.hasAiImageItems && "ai_image_item" !in result.reasons) result.reasons + "ai_image_item" else result.reasons
    }

    fun evaluate(input: ReviewInput): ReviewStatus = when {
        reasons(input).isNotEmpty() -> ReviewStatus.NEEDS_REVIEW
        input.currentStatus == ReviewStatus.REVIEWED -> ReviewStatus.REVIEWED
        else -> ReviewStatus.NONE
    }
}
