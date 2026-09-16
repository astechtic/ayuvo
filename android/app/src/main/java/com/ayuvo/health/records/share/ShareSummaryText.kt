package com.ayuvo.health.records.share

import com.ayuvo.health.records.analytes.AnalyteCatalog
import com.ayuvo.health.records.coach.RecordsCoach
import com.ayuvo.health.records.model.FieldKey
import com.ayuvo.health.records.model.FieldState
import com.ayuvo.health.records.model.HealthRecord
import com.ayuvo.health.records.model.HighlightSection
import com.ayuvo.health.records.model.Observation
import com.ayuvo.health.records.model.RecordField
import com.ayuvo.health.records.model.RecordHighlight
import com.ayuvo.health.records.processing.ExtractionWriter
import com.ayuvo.health.records.processing.RecordJson
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The deterministic share summary of docs/health-records.md §34 (reference `share_summary_text`,
 * vectors `test-vectors/share_summary.json`). Pure Kotlin so the JVM vector tests run it directly.
 */
object ShareSummaryText {

    const val FOOTER = "Shared from Ayuvo. Values were read from the original document and may contain mistakes."
    const val NOTES_HEADER = "Notes:"
    const val HIGHLIGHTS_HEADER = "AI highlights (verify against the original report):"
    const val SEPARATOR = "\n\n---\n\n"

    /** Everything one record contributes to the summary. */
    data class Input(
        val record: HealthRecord,
        val fields: List<RecordField> = emptyList(),
        val observations: List<Observation> = emptyList(),
        val highlights: List<RecordHighlight> = emptyList()
    )

    data class Result(val text: String?, val recordIds: List<String>)

    /** §34 `Dates:` labels, in printed order. */
    private val DATE_LABELS: List<Pair<String, String>> = listOf(
        FieldKey.COLLECTION_DATE to "Collected",
        FieldKey.REPORT_DATE to "Reported",
        FieldKey.VISIT_DATE to "Visit",
        FieldKey.PRESCRIPTION_DATE to "Prescribed",
        FieldKey.ADMISSION_DATE to "Admitted",
        FieldKey.DISCHARGE_DATE to "Discharged",
        FieldKey.FOLLOW_UP_DATE to "Follow-up"
    )

    private val FLAG_WORD = mapOf(
        "low" to "low", "high" to "high", "critical_low" to "critical low",
        "critical_high" to "critical high", "abnormal" to "abnormal"
    )

    /** Medication parts appended after the name + strength, each prefixed by `, `. */
    private val MEDICATION_PARTS = listOf("dose", "frequency", "duration", "instructions")

    /**
     * §34 summary of [inputs] in plan order (unknown ids are dropped by the caller). Returns a null
     * text when the summary is unselected or no record survives.
     */
    fun build(
        inputs: List<Input>,
        plan: SharePlan,
        catalog: AnalyteCatalog = AnalyteCatalog.EMPTY,
        typeLabels: Map<String, String>? = null
    ): Result {
        if (!plan.includeSummary || inputs.isEmpty()) return Result(null, emptyList())
        val blocks = inputs.map { block(it, plan, catalog, typeLabels) }
        return Result(blocks.joinToString(SEPARATOR) + "\n" + FOOTER, inputs.map { it.record.id })
    }

    fun block(
        input: Input,
        plan: SharePlan,
        catalog: AnalyteCatalog = AnalyteCatalog.EMPTY,
        typeLabels: Map<String, String>? = null
    ): String {
        val record = input.record
        val rows = input.fields.filter { it.state != FieldState.REJECTED }
        val selected = plan.summaryFields
        val lines = mutableListOf<String>()

        lines += "${collapse(record.title).orEmpty()} — ${record.sortDate}"

        var head = typeLabels?.get(record.recordType.raw)?.takeIf { it.isNotEmpty() }
            ?: RecordsCoach.TYPE_LABELS[record.recordType.raw] ?: "Record"
        if (SummaryField.FACILITY in selected) {
            collapse(best(rows, FieldKey.FACILITY))?.let { head += " · $it" }
        }
        lines += head

        if (SummaryField.DOCTOR in selected) collapse(best(rows, FieldKey.DOCTOR_NAME))?.let { lines += "Doctor: $it" }
        if (SummaryField.PATIENT_NAME in selected) collapse(best(rows, FieldKey.PATIENT_NAME))?.let { lines += "Patient: $it" }

        if (SummaryField.DATES in selected) {
            val parts = DATE_LABELS.mapNotNull { (key, label) -> collapse(best(rows, key))?.let { "$label $it" } }
            if (parts.isNotEmpty()) lines += "Dates: " + parts.joinToString(" · ")
        }

        if (SummaryField.TEST_RESULTS in selected) {
            val items = RecordsCoach.recordTestResults(input.fields, input.observations, catalog)
                .withIndex()
                .sortedWith(compareBy({ flagGroup(it.value.flag) }, { it.index }))
                .map { resultItem(it.value) }
                .filter { it.isNotEmpty() }
            if (items.isNotEmpty()) {
                lines += "Results:"
                items.forEach { lines += "- $it" }
            }
        }

        for ((label, key, field) in SECTIONS) {
            if (field !in selected) continue
            val items = rows.filter { it.key == key }
                .mapNotNull { if (key == FieldKey.MEDICATION) medicationItem(it) else collapse(it.valueText) }
                .filter { it.isNotEmpty() }
            if (items.isEmpty()) continue
            lines += "$label:"
            items.forEach { lines += "- $it" }
        }

        if (plan.includeNotes) {
            val notes = notesLines(record.notes)
            if (notes.isNotEmpty()) {
                lines += NOTES_HEADER
                lines += notes
            }
        }

        if (plan.includeHighlights) {
            val items = input.highlights
                .filter { it.section == HighlightSection.SUMMARY && !it.dismissed }
                .sortedWith(compareBy({ it.position }, { it.id }))
                .mapNotNull { collapse(it.text) }
                .filter { it.isNotEmpty() }
            if (items.isNotEmpty()) {
                lines += HIGHLIGHTS_HEADER
                items.forEach { lines += "- $it" }
            }
        }
        return lines.joinToString("\n")
    }

    private val SECTIONS: List<Triple<String, String, SummaryField>> = listOf(
        Triple("Medications", FieldKey.MEDICATION, SummaryField.MEDICATIONS),
        Triple("Diagnoses", FieldKey.DIAGNOSIS, SummaryField.DIAGNOSES),
        Triple("Recommendations", FieldKey.RECOMMENDATION, SummaryField.RECOMMENDATIONS)
    )

    /**
     * `<name>: <value> <unit> (<flag word>, ref <ref text>)`; the colon only appears when a value or a
     * unit follows (§34).
     */
    fun resultItem(result: RecordsCoach.TestResult): String {
        val name = collapse(result.name).orEmpty()
        val rest = listOfNotNull(collapse(primitive(result.value)), collapse(result.unit))
        var text = name + if (rest.isNotEmpty()) ": " + rest.joinToString(" ") else ""
        val inner = mutableListOf<String>()
        FLAG_WORD[result.flag]?.let { inner += it }
        stripRefBrackets(result.refText)?.let { inner += "ref $it" }
        if (inner.isNotEmpty()) text += " (" + inner.joinToString(", ") + ")"
        return text
    }

    /** `<name> <strength>, <dose>, <frequency>, <duration>, <instructions>` (§34). */
    fun medicationItem(field: RecordField): String {
        val v = RecordJson.parseObject(field.valueJson) ?: JsonObject(emptyMap())
        var text = collapse(str(v, "name") ?: field.valueText).orEmpty()
        collapse(str(v, "strength"))?.let { text += " $it" }
        for (key in MEDICATION_PARTS) collapse(str(v, key))?.let { text += ", $it" }
        return text
    }

    /** CRLF/CR → LF, every line whitespace-collapsed, blank lines dropped (§34). */
    fun notesLines(notes: String?): List<String> {
        if (notes.isNullOrEmpty()) return emptyList()
        return notes.replace("\r\n", "\n").replace('\r', '\n').split('\n').mapNotNull { collapse(it) }
    }

    private fun flagGroup(flag: String) = when (flag) {
        "critical_low", "critical_high" -> 0
        "low", "high" -> 1
        "abnormal" -> 2
        else -> 3
    }

    /** §8.1 best non-rejected row; `doctor_name` ignores referrer rows. */
    internal fun best(rows: List<RecordField>, key: String, referrer: Boolean = false): String? {
        var candidates = rows.filter { it.key == key }
        if (key == FieldKey.DOCTOR_NAME) candidates = candidates.filter { (role(it) == "referrer") == referrer }
        return ExtractionWriter.best(candidates, key)?.valueText
    }

    private fun role(field: RecordField): String? =
        RecordJson.parseObject(field.valueJson)?.let { str(it, "role") }

    private fun str(o: JsonObject, k: String): String? =
        (o[k] as? JsonPrimitive)?.takeIf { it !is JsonNull && it.isString }?.content

    private fun primitive(e: JsonElement): String? = (e as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content

    /** Runs of space, tab, CR and LF → one space, trimmed; empty becomes null. */
    internal fun collapse(s: String?): String? = RecordsCoach.collapseWs(s)?.takeIf { it.isNotEmpty() }

    /** §29: one enclosing `()` / `[]` removed from a reference range. */
    internal fun stripRefBrackets(refText: String?): String? {
        var t = RecordsCoach.collapseWs(refText) ?: return null
        if (t.length >= 2 && ((t.first() == '(' && t.last() == ')') || (t.first() == '[' && t.last() == ']'))) {
            t = t.substring(1, t.length - 1).trim(' ')
        }
        return t.takeIf { it.isNotEmpty() }
    }
}
