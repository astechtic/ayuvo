# Ayuvo health records — AI extraction prompt (v1)

Contract: `docs/health-records.md` §9.3. Response shape: `ai_extraction.schema.json`. Validation: `scripts/records_reference.py` `validate_ai` (vectors `test-vectors/ai_validation.json`). Chunking: `ai_chunks` (vectors `test-vectors/ai_chunks.json`).

Both platforms embed the text between the ```` ``` ```` fences below verbatim (no trailing newline inside a block) and substitute the placeholders with plain string replacement (no templating engine): `{record_type_hint}` first, then `{pages}`, each exactly once, so text inside a page is never re-substituted.

## Placeholders
- `{record_type_hint}`: the rules classifier result (§10) when it is not `other`, else `unknown`.
- `{pages}`: one chunk from `ai_chunks(pages, mode)`: blocks `=== Page N ===\n<page text>` joined by a blank line, N 1-based within the record (or segment). Page text is the §10 `pfold` of `record_pages.text`.

## Which variant
| Mode | System prompt | Chunk limit | Max output tokens |
|---|---|---|---|
| `cloud` (BYOK text provider) | full | 12,000 characters | 3,000 |
| `local` Gemma (4,096-token context) | compact | 2,500 characters | min(3,000, 4,096 − prompt tokens − 64) |
| `local` Apple Intelligence | compact | 2,500 characters | provider limit |

Vision calls (a page with no usable text) use the same system prompt; the user template's `{pages}` is `=== Page N ===\n(image attached)` and the image is attached to that call. One page per local vision call.

Chunks of one record are sent one call at a time; items from all calls are validated and merged by `applyExtraction` (§8.1). `record_type`/`report_name`/`summary` are taken from the call covering page 1.

## System prompt (full)
```
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
```

## User template
```
Document type hint: {record_type_hint}

{pages}

Return the JSON object.
```

## System prompt (compact, on-device)
```
Extract facts from this medical document. Output only JSON:
{"record_type":"","record_type_confidence":0,"report_name":null,"dates":[],"fields":[],"test_results":[],"medications":[],"summary":null}
dates item {key,value,evidence,source_page,confidence}: key report_date|collection_date|visit_date|prescription_date|admission_date|discharge_date|follow_up_date, value YYYY-MM-DD.
fields item {key,value,evidence,source_page,confidence}: key doctor_name|doctor_specialty|facility|department|patient_name|patient_age|patient_sex|diagnosis|symptom|procedure|recommendation.
test_results item {name,value,unit,ref_text,flag,evidence,source_page,confidence}.
medications item {name,strength,form,dose,frequency,duration,instructions,evidence,source_page,confidence}.
Copy evidence exactly from the page line. source_page = N of "=== Page N ===". Only printed values: never guess, convert or diagnose. No birth dates. flag null unless printed. Unsure: leave out. summary null.
```

## What the validator does with the answer (summary of §9.3)
- Lenient parse: text from the first `{` to the last `}`; anything else → `parse_error`, no items.
- Every item needs an integer `source_page` in range and `evidence` whose folded, whitespace-collapsed form is a substring of that page's folded, whitespace-collapsed text (pages sent as images without OCR text skip this and are capped at confidence 0.5).
- Names and values (except `patient_sex`) must appear in the evidence as whole normalized words; every number in a value must appear in the evidence; dates must be readable from the evidence by §11 (either day/month order for ambiguous numeric dates).
- Model flags are ignored: the flag is recomputed from a printed marker after the value or the printed range.
- `ref_text`, `strength`, `dose`, `frequency`, `duration`, `instructions` that are not in the evidence are set to null (the item is kept); `form` only needs its numbers in the evidence ("tablet" for "Tab.").
- Confidence is clamped to [0, 0.9]; `method` = `ai_local` / `ai_cloud`.
