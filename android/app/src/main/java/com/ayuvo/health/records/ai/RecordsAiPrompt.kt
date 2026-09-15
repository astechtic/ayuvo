package com.ayuvo.health.records.ai

/**
 * Prompts for the Health Records AI stage (docs/health-records.md §9.3) and the search rewriter (§17).
 *
 * [EXTRACTION_TEMPLATE] must equal `shared/records/ai_extraction.md` (RecordsAiPromptContractTest).
 * Until the reference agent publishes that file this is a placeholder with the same placeholders:
 * `{{record_type}}` and `{{pages}}`.
 */
object RecordsAiPrompt {
    /** The prompt is the shared `ai_extraction.md` v1 text (RecordsAiPromptContractTest compares the fenced blocks). */
    const val IS_DRAFT_PROMPT = false

    val SYSTEM_FULL: String = """You extract facts from one person's own medical document for their private health records app.
Return ONLY one JSON object, no markdown, no prose, exactly this shape:
{"record_type":"","record_type_confidence":0,"report_name":null,"dates":[],"fields":[],"test_results":[],"medications":[],"summary":null}

Items:
- dates: {"key","value","evidence","source_page","confidence"}. key: report_date, collection_date, visit_date, prescription_date, admission_date, discharge_date, follow_up_date. value: YYYY-MM-DD, or YYYY-MM when only month and year are printed.
- fields: {"key","value","evidence","source_page","confidence"}. key: doctor_name, doctor_specialty, facility, department, patient_name, patient_age, patient_sex, diagnosis, symptom, procedure, recommendation, document_time. One item per diagnosis, symptom, procedure or recommendation.
- test_results: {"name","value","unit","ref_text","flag","evidence","source_page","confidence"}. One item per printed result row. value exactly as printed ("11.2", "<0.5", "Negative"). unit and ref_text as printed, or null. flag: low, high, critical_low, critical_high or abnormal only when the report prints a marker (H, L, *, High, Low, Critical); otherwise null.
- medications: {"name","strength","form","dose","frequency","duration","instructions","evidence","source_page","confidence"}. Each part as printed ("650 mg", "1-0-1", "BD", "x 5 days", "after food"), or null.
- record_type: lab_report, prescription, consultation_note, discharge_summary, imaging_report, diagnostic_report, medication_list, vaccination_record, bill, insurance, personal_note or other.
- report_name: the printed title of the report, or null.
- summary: {"text","source_pages"} with at most 3 short sentences restating what the document says, or null.

Rules:
1. Use only what is printed. Never infer, calculate, convert, correct or guess a value.
2. evidence is an exact copy of the printed line that contains the value (at most 200 characters). source_page is N from "=== Page N ===".
3. Do not add a diagnosis, flag, advice or explanation that is not written. Never extract dates of birth.
4. The summary uses only numbers printed in the document and gives no medical advice.
5. When unsure, leave the item out. Empty arrays are correct. confidence is 0 to 1."""

    val USER_TEMPLATE: String = """Document type hint: {record_type_hint}

{pages}

Return the JSON object."""

    val SYSTEM_COMPACT: String = """Extract facts from this medical document. Output only JSON:
{"record_type":"","record_type_confidence":0,"report_name":null,"dates":[],"fields":[],"test_results":[],"medications":[],"summary":null}
dates item {key,value,evidence,source_page,confidence}: key report_date|collection_date|visit_date|prescription_date|admission_date|discharge_date|follow_up_date, value YYYY-MM-DD.
fields item {key,value,evidence,source_page,confidence}: key doctor_name|doctor_specialty|facility|department|patient_name|patient_age|patient_sex|diagnosis|symptom|procedure|recommendation.
test_results item {name,value,unit,ref_text,flag,evidence,source_page,confidence}.
medications item {name,strength,form,dose,frequency,duration,instructions,evidence,source_page,confidence}.
Copy evidence exactly from the page line. source_page = N of "=== Page N ===". Only printed values: never guess, convert or diagnose. No birth dates. flag null unless printed. Unsure: leave out. summary null."""

    /** Kept for callers of the Phase 2 placeholder: the full system prompt followed by the user template. */
    val EXTRACTION_TEMPLATE: String get() = SYSTEM_FULL + "\n\n" + USER_TEMPLATE

    /** `{record_type_hint}`: the rules classifier result when not `other`, else `unknown`. */
    fun hint(recordType: String?): String = recordType?.takeIf { it.isNotBlank() && it != "other" } ?: "unknown"

    /**
     * One call's prompt. The shared transport has a single prompt string, so the system prompt
     * precedes the user template (plain replacement, `{record_type_hint}` first, then `{pages}`).
     */
    fun render(local: Boolean, recordType: String?, pages: String): String {
        val user = USER_TEMPLATE.replaceFirst("{record_type_hint}", hint(recordType)).replaceFirst("{pages}", pages)
        return (if (local) SYSTEM_COMPACT else SYSTEM_FULL) + "\n\n" + user
    }

    /** Vision call: `{pages}` is `=== Page N ===\n(image attached)` (N 1-based). */
    fun renderImage(local: Boolean, recordType: String?, pageNumber: Int): String =
        render(local, recordType, "=== Page $pageNumber ===\n(image attached)")

    /** Only the query text is sent — never record content (§17 AiQueryRewriter). */
    fun queryRewrite(query: String): String = """
Rewrite a search over the user's personal health records into structured filters. You receive only the search text.
Respond ONLY with JSON of this shape (omit unknown keys, never invent names):
{"terms":["free text words"],"date_from":"yyyy-MM-dd","date_to":"yyyy-MM-dd","record_types":["lab_report|prescription|consultation_note|discharge_summary|imaging_report|diagnostic_report|medication_list|vaccination_record|bill|insurance|personal_note|other"],"flags":["abnormal|low|high|critical"],"doctor":"...","facility":"...","favorites":false,"needs_review":false}
Search text: ${query.trim().take(MAX_QUERY_CHARS)}
""".trim()

    const val MAX_QUERY_CHARS = 300
}
