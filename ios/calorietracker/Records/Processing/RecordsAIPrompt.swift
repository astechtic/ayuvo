import Foundation

/// Embedded copy of the fenced blocks of `shared/records/ai_extraction.md` (v1). A parity test
/// compares them with the shared file. Placeholders are substituted with plain replacement:
/// `{record_type_hint}` first, then `{pages}`.
nonisolated enum RecordsAIPrompt {
    static let systemFull = #"""
You extract facts from one person's own medical document for their private health records app.
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
5. When unsure, leave the item out. Empty arrays are correct. confidence is 0 to 1.
"""#

    static let userTemplate = #"""
Document type hint: {record_type_hint}

{pages}

Return the JSON object.
"""#

    static let systemCompact = #"""
Extract facts from this medical document. Output only JSON:
{"record_type":"","record_type_confidence":0,"report_name":null,"dates":[],"fields":[],"test_results":[],"medications":[],"summary":null}
dates item {key,value,evidence,source_page,confidence}: key report_date|collection_date|visit_date|prescription_date|admission_date|discharge_date|follow_up_date, value YYYY-MM-DD.
fields item {key,value,evidence,source_page,confidence}: key doctor_name|doctor_specialty|facility|department|patient_name|patient_age|patient_sex|diagnosis|symptom|procedure|recommendation.
test_results item {name,value,unit,ref_text,flag,evidence,source_page,confidence}.
medications item {name,strength,form,dose,frequency,duration,instructions,evidence,source_page,confidence}.
Copy evidence exactly from the page line. source_page = N of "=== Page N ===". Only printed values: never guess, convert or diagnose. No birth dates. flag null unless printed. Unsure: leave out. summary null.
"""#

    static func system(local: Bool) -> String { local ? systemCompact : systemFull }

    /// User message for one chunk (`{pages}` = an `ai_chunks` text, or `=== Page N ===\n(image attached)`).
    static func user(recordTypeHint: String, pages: String) -> String {
        userTemplate
            .replacingOccurrences(of: "{record_type_hint}", with: recordTypeHint)
            .replacingOccurrences(of: "{pages}", with: pages)
    }

    /// Whole prompt for transports that take one text (system + user).
    static func combined(local: Bool, recordTypeHint: String, pages: String) -> String {
        system(local: local) + "\n\n" + user(recordTypeHint: recordTypeHint, pages: pages)
    }
}
