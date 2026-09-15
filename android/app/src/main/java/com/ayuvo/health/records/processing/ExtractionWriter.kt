package com.ayuvo.health.records.processing

import com.ayuvo.health.records.model.DateMethod
import com.ayuvo.health.records.model.DatePrecision
import com.ayuvo.health.records.model.ExtractedField
import com.ayuvo.health.records.model.FieldKey
import com.ayuvo.health.records.model.FieldState
import com.ayuvo.health.records.model.RecordField
import com.ayuvo.health.records.model.RecordType
import com.ayuvo.health.records.processing.RecordJson.string

/**
 * The `applyExtraction` write rule (docs/health-records.md §8.1), ported from the reference
 * `apply_extraction` / `derive_record` / `conflicts`:
 * 1. a row with the same key + normalized value in rejected/confirmed/user → skip;
 * 2. else the first matching (suggested) row → update only when the new confidence is higher;
 * 3. otherwise insert as suggested.
 */
object ExtractionWriter {

    sealed interface Op {
        val field: ExtractedField
        data class Insert(override val field: ExtractedField) : Op
        data class Update(val existingId: String, override val field: ExtractedField) : Op
        data class Skip(override val field: ExtractedField, val reason: String) : Op
    }

    /** Reference `normalize_value`. */
    fun normalizedKey(key: String, valueText: String, valueJson: String?): String = when {
        key in FieldKey.DATE_KEYS || key == FieldKey.DOCUMENT_TIME -> valueText.trim()
        key == FieldKey.DOCTOR_NAME || key == FieldKey.PATIENT_NAME -> {
            // Honorifics are not part of the identity: "Dr Neha-Gupta" == "Neha Gupta", "Mrs. Rao" == "Rao".
            var ws = RecordText.normalizedValue(valueText).split(" ")
            val drop = if (key == FieldKey.DOCTOR_NAME) DOCTOR_TITLES else PATIENT_TITLES
            while (ws.size > 1 && ws[0] in drop) ws = ws.drop(1)
            ws.joinToString(" ")
        }
        key == FieldKey.TEST_RESULT -> {
            val json = RecordJson.parseObject(valueJson)
            val name = json?.string("name") ?: valueText
            RecordText.normalizedValue(name) + "|" + RecordText.normalizedValue(json?.string("value").orEmpty())
        }
        else -> RecordText.normalizedValue(valueText)
    }

    /** Working row of the reference algorithm. */
    class WorkRow(
        val id: String,
        val key: String,
        var valueText: String,
        var valueJson: String?,
        var confidence: Double,
        val state: String,
        /** Latest extracted content for inserted/updated rows. */
        var incoming: ExtractedField? = null,
        val inserted: Boolean = false
    )

    data class Action(val action: String, val id: String)

    /** Reference loop: returns the actions; [rows] is mutated (inserted rows get ids `new-1`, …). */
    fun apply(rows: MutableList<WorkRow>, items: List<ExtractedField>): List<Action> {
        val actions = mutableListOf<Action>()
        var inserted = 0
        for (it in items) {
            val nv = normalizedKey(it.key, it.valueText, it.valueJson)
            val match = rows.filter { r -> r.key == it.key && normalizedKey(r.key, r.valueText, r.valueJson) == nv }
            val locked = match.filter { r -> r.state == "rejected" || r.state == "confirmed" || r.state == "user" }
            if (locked.isNotEmpty()) {
                actions += Action("skip_" + locked[0].state, locked[0].id)
                continue
            }
            if (match.isNotEmpty()) {
                val r = match[0]
                if (it.confidence > r.confidence) {
                    r.valueText = it.valueText
                    r.valueJson = it.valueJson
                    r.confidence = it.confidence
                    r.incoming = it
                    actions += Action("update", r.id)
                } else {
                    actions += Action("keep", r.id)
                }
                continue
            }
            inserted++
            val row = WorkRow("new-$inserted", it.key, it.valueText, it.valueJson, it.confidence, "suggested", it, inserted = true)
            rows += row
            actions += Action("insert", row.id)
        }
        return actions
    }

    fun plan(existing: List<RecordField>, extracted: List<ExtractedField>): List<Op> {
        val rows = existing.map { WorkRow(it.id, it.key, it.valueText, it.valueJson, it.confidence, it.state.raw) }.toMutableList()
        val usable = extracted.filter { it.valueText.isNotBlank() }
        val actions = apply(rows, usable)
        // One write per touched row carrying its final content; everything else is a skip.
        val ops = mutableListOf<Op>()
        for (row in rows) {
            val incoming = row.incoming ?: continue
            ops += if (row.inserted) Op.Insert(incoming) else Op.Update(row.id, incoming)
        }
        actions.forEachIndexed { i, a -> if (a.action != "insert" && a.action != "update") ops += Op.Skip(usable[i], a.action) }
        return ops
    }

    /** Reference `_best_row`: user, then confirmed, then suggested ≥ 0.6 by confidence, earliest on ties. */
    fun best(fields: List<RecordField>, key: String): RecordField? {
        val rank = mapOf(FieldState.USER to 0, FieldState.CONFIRMED to 1, FieldState.SUGGESTED to 2)
        return fields.withIndex()
            .filter { (_, r) -> r.key == key && r.state != FieldState.REJECTED && (r.state == FieldState.USER || r.state == FieldState.CONFIRMED || r.confidence >= MIN_DERIVE_CONFIDENCE) }
            .sortedWith(compareBy<IndexedValue<RecordField>>({ rank.getValue(it.value.state) }, { -it.value.confidence }, { it.index }))
            .firstOrNull()?.value
    }

    data class SlotRow(val key: String, val valueText: String, val valueJson: String?, val state: String)

    /** Reference `conflicts`: single-valued slots (doctor_name referrers separate) with ≥ 2 values, sorted. */
    fun conflictSlots(rows: List<SlotRow>): List<String> {
        val vals = LinkedHashMap<String, MutableSet<String>>()
        for (r in rows) {
            if (r.state == "rejected" || r.key in FieldKey.MULTI_VALUED) continue
            val role = RecordJson.parseObject(r.valueJson)?.string("role")
            val slot = r.key + (if (!role.isNullOrEmpty()) ":$role" else "")
            vals.getOrPut(slot) { LinkedHashSet() } += normalizedKey(r.key, r.valueText, r.valueJson)
        }
        return vals.filterValues { it.size >= 2 }.keys.sorted()
    }

    /** Conflicting rows grouped by slot (UI). */
    fun conflicts(fields: List<RecordField>): Map<String, List<RecordField>> {
        val slots = conflictSlots(fields.map { SlotRow(it.key, it.valueText, it.valueJson, it.state.raw) })
        return slots.associateWith { slot ->
            val key = slot.substringBefore(':')
            val role = slot.substringAfter(':', "").ifEmpty { null }
            fields.filter { f ->
                f.key == key && f.state != FieldState.REJECTED && RecordJson.parseObject(f.valueJson)?.string("role").let { it.isNullOrEmpty() && role == null || it == role }
            }
        }
    }

    data class RecordColumns(
        val title: String,
        val titleImportDerived: Boolean,
        val documentDate: String?,
        val documentDateMethod: DateMethod?,
        /** Current type; the derived "<Type label> — <facility>" title uses its English label. */
        val recordType: RecordType? = null
    )

    data class Derived(
        val title: String? = null,
        val documentDate: String? = null,
        val documentDateMethod: DateMethod? = null,
        val documentDatePrecision: DatePrecision? = null
    )

    /** The record columns `derive_record` reads and writes (contract strings). */
    data class RecordState(
        val title: String,
        val titleIsDerived: Boolean,
        val recordType: String,
        val category: String,
        val typeConfidence: Double?,
        val typeMethod: String?,
        val documentDate: String?,
        val documentDatePrecision: String?,
        val documentDateMethod: String?,
        val sortDate: String?
    )

    data class ClassificationInput(val recordType: String, val confidence: Double, val method: String)

    /** Reference `derive_record`. */
    fun deriveRecord(record: RecordState, rows: List<RecordField>, classification: List<ClassificationInput>?): RecordState {
        var rec = record
        if (!classification.isNullOrEmpty() && rec.typeMethod != "user") {
            var best: ClassificationInput? = null
            for (c in classification) if (best == null || c.confidence > best.confidence) best = c
            best!!
            rec = rec.copy(
                recordType = best.recordType,
                category = RecordType.fromRaw(best.recordType).defaultCategory.raw,
                typeConfidence = best.confidence,
                typeMethod = best.method
            )
        }
        if (rec.documentDateMethod == null || rec.documentDateMethod == "import_time" || rec.documentDateMethod == "file_metadata") {
            for (key in FieldKey.DOCUMENT_DATE_ORDER) {
                val r = best(rows, key) ?: continue
                rec = rec.copy(
                    documentDate = r.valueText,
                    documentDatePrecision = RecordJson.parseObject(r.valueJson)?.string("precision")?.takeIf { it.isNotEmpty() } ?: "day",
                    documentDateMethod = r.method.raw,
                    sortDate = r.valueText
                )
                break
            }
        }
        if (rec.titleIsDerived) {
            val rn = best(rows, FieldKey.REPORT_NAME)
            val fac = best(rows, FieldKey.FACILITY)
            if (rn != null) rec = rec.copy(title = rn.valueText)
            else if (fac != null) rec = rec.copy(title = BoundaryDetector.TYPE_LABEL.getValue(RecordType.fromRaw(rec.recordType).raw) + " — " + fac.valueText)
        }
        return rec
    }

    /** Store facade over [deriveRecord]: only the columns that change (classification is its own stage). */
    fun derive(columns: RecordColumns, fields: List<RecordField>, typeLabel: String?): Derived {
        val before = RecordState(
            title = columns.title,
            titleIsDerived = columns.titleImportDerived,
            recordType = columns.recordType?.raw ?: "other",
            category = columns.recordType?.defaultCategory?.raw ?: "other",
            typeConfidence = null,
            typeMethod = null,
            documentDate = columns.documentDate,
            documentDatePrecision = null,
            documentDateMethod = columns.documentDateMethod?.raw,
            sortDate = columns.documentDate
        )
        var after = deriveRecord(before, fields, null)
        // Without a known record type, keep the caller's (possibly localized) label for the facility title.
        if (columns.recordType == null && typeLabel != null && after.title != before.title && best(fields, FieldKey.REPORT_NAME) == null) {
            best(fields, FieldKey.FACILITY)?.let { after = after.copy(title = "$typeLabel — ${it.valueText}") }
        }
        val dateChanged = after.documentDate != before.documentDate || after.documentDateMethod != before.documentDateMethod
        return Derived(
            title = after.title.takeIf { it != before.title },
            documentDate = after.documentDate.takeIf { dateChanged },
            documentDateMethod = if (dateChanged) DateMethod.fromRaw(after.documentDateMethod) ?: DateMethod.RULES else null,
            documentDatePrecision = if (dateChanged) DatePrecision.fromRaw(after.documentDatePrecision) else null
        )
    }

    const val MIN_DERIVE_CONFIDENCE = 0.6

    private val DOCTOR_TITLES = setOf("dr", "doctor", "prof")
    private val PATIENT_TITLES = setOf("mr", "mrs", "ms", "miss", "master", "mstr", "baby", "smt", "shri", "sri", "kumari", "kum", "mx")
}
