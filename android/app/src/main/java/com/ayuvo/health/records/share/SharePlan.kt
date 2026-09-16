package com.ayuvo.health.records.share

/** `summary_fields` of a [SharePlan] (docs/health-records.md §34). */
enum class SummaryField(val raw: String) {
    DOCTOR("doctor"), FACILITY("facility"), PATIENT_NAME("patient_name"), DATES("dates"),
    TEST_RESULTS("test_results"), MEDICATIONS("medications"), DIAGNOSES("diagnoses"),
    RECOMMENDATIONS("recommendations");

    companion object {
        fun fromRaw(raw: String?): SummaryField? = entries.firstOrNull { it.raw == raw }

        /** Everything except the patient's own name, which is off by default. */
        val DEFAULTS: Set<SummaryField> = entries.toSet() - PATIENT_NAME
    }
}

/** `redactions` of a [SharePlan] (§34). */
enum class RedactionClass(val raw: String) {
    NAME("name"), ADDRESS("address"), PHONE("phone"), PATIENT_ID("patient_id"),
    INSURANCE_ID("insurance_id"), OTHER_IDS("other_ids");

    companion object {
        fun fromRaw(raw: String?): RedactionClass? = entries.firstOrNull { it.raw == raw }
    }
}

/**
 * What the user chose on the "What will be shared" screen (§34). [pages] holds the selected page
 * indexes per record; a record missing from the map (or mapped to null) shares every page.
 */
data class SharePlan(
    val recordIds: List<String>,
    val pages: Map<String, List<Int>?> = emptyMap(),
    val includeOriginal: Boolean = true,
    val includeSummary: Boolean = true,
    val summaryFields: Set<SummaryField> = SummaryField.DEFAULTS,
    val includeHighlights: Boolean = false,
    val includeNotes: Boolean = false,
    val redactions: Set<RedactionClass> = emptySet()
) {
    val isEmpty: Boolean get() = recordIds.isEmpty() || (!includeOriginal && !includeSummary)

    /** Selected pages of [recordId] within `0 until pageCount`, or null for "all". */
    fun pagesOf(recordId: String, pageCount: Int): List<Int>? {
        val selected = pages[recordId] ?: return null
        val all = (0 until pageCount).toList()
        val kept = selected.filter { it in 0 until pageCount }.distinct().sorted()
        return if (kept.isEmpty() || kept == all) null else kept
    }

    fun with(field: SummaryField, on: Boolean): SharePlan =
        copy(summaryFields = if (on) summaryFields + field else summaryFields - field)

    fun with(redaction: RedactionClass, on: Boolean): SharePlan =
        copy(redactions = if (on) redactions + redaction else redactions - redaction)
}
