package com.ayuvo.health.records.coach

import com.ayuvo.health.records.analytes.AnalyteCatalog
import com.ayuvo.health.records.model.AnalyteCondition
import com.ayuvo.health.records.model.FieldKey
import com.ayuvo.health.records.model.FieldState
import com.ayuvo.health.records.model.HealthRecord
import com.ayuvo.health.records.model.HighlightSection
import com.ayuvo.health.records.model.LinkKind
import com.ayuvo.health.records.model.LinkStatus
import com.ayuvo.health.records.model.Observation
import com.ayuvo.health.records.model.RecordField
import com.ayuvo.health.records.model.RecordHighlight
import com.ayuvo.health.records.model.RecordPage
import com.ayuvo.health.records.model.RecordSource
import com.ayuvo.health.records.model.RecordType
import com.ayuvo.health.records.model.ReviewStatus
import com.ayuvo.health.records.processing.DateOrder
import com.ayuvo.health.records.processing.ExtractionWriter
import com.ayuvo.health.records.processing.RecordJson
import com.ayuvo.health.records.processing.RecordText
import com.ayuvo.health.records.search.RecordQueryParser
import com.ayuvo.health.records.search.RecordSearchRanking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import java.time.LocalDate
import kotlin.math.floor

/** `{record_id, title, date}` persisted on an assistant message (§26 "Used records"). */
@kotlinx.serialization.Serializable
data class CoachRecordRef(
    @kotlinx.serialization.SerialName("record_id") val recordId: String,
    val title: String,
    val date: String
)

/**
 * Coach × Health Records rules (docs/health-records.md §26–§29), ported function by function from
 * the Phase 4 section of `scripts/records_reference.py`: the three tool payloads, prompt lines,
 * gating, record refs, the compare candidate, the suggestion-chip selection and the on-device block.
 * Everything reads a [RecordsCoachData] so the same code runs over vectors and over SQLite.
 */
object RecordsCoach {
    const val SEARCH_LIMIT_DEFAULT = 10
    const val SEARCH_LIMIT_CAP = 20
    const val SEARCH_VALUES_CAP = 20
    const val SEARCH_HIGHLIGHTS_CAP = 3
    const val GET_TEXT_CHARS = 2000
    const val SERIES_CAP = 100
    const val CONTEXT_MAX_RECORDS = 3
    const val CONTEXT_MAX_CHARS = 1800
    const val MAX_SELECTED = 10
    const val PACK_HEADER = "## Health records (selected by the user)"

    private val FLAGGED = setOf("critical_low", "critical_high", "low", "high", "abnormal")
    private val FLAG_WORD = mapOf("low" to "low", "high" to "high", "critical_low" to "critical low", "critical_high" to "critical high", "abnormal" to "abnormal")
    private val STORED_FLAGS = mapOf(
        "abnormal" to setOf("low", "high", "critical_low", "critical_high", "abnormal"),
        "low" to setOf("low", "critical_low"), "high" to setOf("high", "critical_high"),
        "critical" to setOf("critical_low", "critical_high"), "normal" to setOf("normal")
    )
    private val GET_EXCLUDED_KEYS = setOf(FieldKey.TEST_RESULT, FieldKey.PATIENT_NAME, FieldKey.LOCATION)
    private val LAB_LIKE = setOf(RecordType.LAB_REPORT, RecordType.IMAGING_REPORT, RecordType.DIAGNOSTIC_REPORT)
    private val PEOPLE_KEYS = listOf(FieldKey.DOCTOR_NAME, FieldKey.DOCTOR_SPECIALTY, FieldKey.FACILITY, FieldKey.DEPARTMENT, FieldKey.PATIENT_NAME)
    private val CLINICAL_KEYS = listOf(FieldKey.REPORT_NAME, FieldKey.TEST_RESULT, FieldKey.DIAGNOSIS, FieldKey.SYMPTOM, FieldKey.MEDICATION, FieldKey.PROCEDURE)
    private val RE_PII_LABEL = Regex(
        "(?<![a-z0-9])(?:patient|name|uhid|mrn|mr no|ip no|op no|reg no|regn no|registration|" +
            "phone|mobile|mob|tel|telephone|contact|email|e-mail|address|addr|aadhaar|aadhar|abha|" +
            "pan no|passport|policy no|member id|id no|lab no|sample id|barcode|dob|d\\.o\\.b|" +
            "date of birth|birth)(?![a-z0-9])"
    )
    private val RE_PII_DIGITS = Regex("[0-9](?:[ -]?[0-9]){9}")
    private val RE_TEMPLATE_KEY = Regex("\\{([a-z_]+)\\}")
    private val RE_ISO_DATE = Regex("([0-9]{4})-([0-9]{2})-([0-9]{2})")
    private val RE_WS = Regex("[ \t\r\n]+")

    /** English stored type labels (§8.1) — LLM input, never localized. */
    val TYPE_LABELS: Map<String, String> = mapOf(
        "lab_report" to "Lab Report", "prescription" to "Prescription", "consultation_note" to "Doctor Note",
        "discharge_summary" to "Discharge Summary", "imaging_report" to "Imaging Report", "diagnostic_report" to "Diagnostic Report",
        "medication_list" to "Medication List", "vaccination_record" to "Vaccination Record", "bill" to "Bill",
        "insurance" to "Insurance", "personal_note" to "Note", "other" to "Record"
    )

    // -- small helpers -----------------------------------------------------------------------------

    fun fillPlaceholders(template: String, values: Map<String, String>): String =
        RE_TEMPLATE_KEY.replace(template) { m -> values[m.groupValues[1]] ?: m.value }

    fun collapseWs(s: String?): String? = s?.let { RE_WS.replace(it, " ").trim(' ') }

    fun error(template: String, values: Map<String, String> = emptyMap()): JsonObject = obj("error" to fillPlaceholders(template, values))

    private val TIMELINE: Comparator<HealthRecord> = compareBy<HealthRecord>({ it.sortDate }, { it.createdMs }, { it.seq })

    private fun codePoints(s: String) = s.codePointCount(0, s.length)
    private fun takeCodePoints(s: String, n: Int): String = if (codePoints(s) <= n) s else s.substring(0, s.offsetByCodePoints(0, n))

    private fun role(f: RecordField): String? = RecordJson.parseObject(f.valueJson)?.let { (it["role"] as? JsonPrimitive)?.takeIf { p -> p !is JsonNull }?.contentOrNull }

    /** §8.1 best non-rejected row of [key]; doctor_name: referrer rows when [referrer], else the others. */
    private fun bestValue(rows: List<RecordField>, key: String, referrer: Boolean = false): String? {
        var cand = rows.filter { it.key == key }
        if (key == FieldKey.DOCTOR_NAME) cand = cand.filter { (role(it) == "referrer") == referrer }
        return ExtractionWriter.best(cand, key)?.valueText
    }

    private fun vj(f: RecordField): JsonObject = RecordJson.parseObject(f.valueJson) ?: JsonObject(emptyMap())
    private fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.takeIf { it !is JsonNull && it.isString }?.content
    private fun JsonObject.el(k: String): JsonElement = this[k] ?: JsonNull
    private fun truthy(s: String?) = !s.isNullOrEmpty()

    /** §15 medication line: `<name>[ <strength>][ · <frequency>][ · <duration>]`. */
    fun medicationText(f: RecordField): String {
        val v = vj(f)
        var text = v.str("name").takeIf { truthy(it) } ?: f.valueText
        v.str("strength")?.takeIf { truthy(it) }?.let { text += " $it" }
        for (k in listOf("frequency", "duration")) v.str(k)?.takeIf { truthy(it) }?.let { text += " · $it" }
        return text
    }

    private sealed interface Day {
        data class Ok(val value: String?) : Day
        data class Bad(val error: JsonObject) : Day
    }

    private fun parseDay(contract: RecordsCoachContract, element: JsonElement?): Day {
        if (element == null || element is JsonNull) return Day.Ok(null)
        if (element is JsonPrimitive && element.isString) {
            val value = element.content
            if (value.isEmpty()) return Day.Ok(null)
            val m = RE_ISO_DATE.matchEntire(value) ?: return Day.Bad(error(contract.errors.badDate, mapOf("value" to value)))
            val (y, mo, d) = m.destructured
            val ok = y.toInt() >= 1 && runCatching { LocalDate.of(y.toInt(), mo.toInt(), d.toInt()) }.isSuccess
            return if (ok) Day.Ok(value) else Day.Bad(error(contract.errors.badDate, mapOf("value" to value)))
        }
        return Day.Bad(error(contract.errors.badDate, mapOf("value" to element.toString())))
    }

    private data class Range(val from: String?, val to: String?)

    private fun dateArgs(contract: RecordsCoachContract, args: JsonObject): Pair<Range?, JsonObject?> {
        val lo = when (val d = parseDay(contract, args["from"])) { is Day.Bad -> return null to d.error; is Day.Ok -> d.value }
        val hi = when (val d = parseDay(contract, args["to"])) { is Day.Bad -> return null to d.error; is Day.Ok -> d.value }
        if (lo != null && hi != null && lo > hi) return null to error(contract.errors.dateOrder)
        return Range(lo, hi) to null
    }

    private fun stringArg(args: JsonObject, key: String): String =
        (args[key] as? JsonPrimitive)?.takeIf { it !is JsonNull && it.isString }?.content ?: ""

    // -- §28 test results ------------------------------------------------------------------------------

    data class TestResult(
        val name: String?, val analyte: String?, val value: JsonElement, val unit: String?, val refText: String?,
        val refLow: JsonElement, val refHigh: JsonElement, val flag: String, val state: String, val sourcePage: Int?
    ) {
        fun json() = obj(
            "name" to name, "analyte" to analyte, "value" to value, "unit" to unit, "ref_text" to refText,
            "ref_low" to refLow, "ref_high" to refHigh, "flag" to flag, "state" to state, "source_page" to sourcePage
        )
    }

    fun recordTestResults(fields: List<RecordField>, observations: List<Observation>, catalog: AnalyteCatalog): List<TestResult> {
        if (observations.isNotEmpty()) {
            val pos = fields.withIndex().associate { it.value.id to it.index }
            return observations.filter { it.state != FieldState.REJECTED }
                .sortedWith(compareBy<Observation>(
                    { if (it.fieldId in pos) 0 else 1 }, { pos[it.fieldId] ?: 0 },
                    { if (it.fieldId in pos) 0L else it.createdMs }, { if (it.fieldId in pos) "" else it.id }
                ))
                .map { o ->
                    TestResult(
                        o.rawName, o.analyteId, JsonPrimitive(o.valueText), o.unit, o.refText, num(o.refLow), num(o.refHigh),
                        o.flag.raw, o.state.raw, o.sourcePage
                    )
                }
        }
        return fields.filter { it.key == FieldKey.TEST_RESULT && it.state != FieldState.REJECTED }.map { f ->
            val v = vj(f)
            val value = v["value"]?.takeIf { it !is JsonNull } ?: JsonPrimitive("")
            TestResult(
                name = v.str("name").takeIf { truthy(it) } ?: f.valueText,
                analyte = null, value = value, unit = catalog.canonicalUnitSpelling(v.str("unit")), refText = v.str("ref_text"),
                refLow = v.el("ref_low"), refHigh = v.el("ref_high"),
                flag = v.str("flag").takeIf { truthy(it) } ?: "unknown", state = f.state.raw, sourcePage = f.sourcePage
            )
        }
    }

    // -- §28 FTS row + BM25 -------------------------------------------------------------------------

    /** records_fts row (§17 columns, Phase 3 analyte names), every column folded; column order of `records_fts`. */
    fun ftsRow(
        record: HealthRecord, tagNames: List<String>, fields: List<RecordField>, observations: List<Observation>,
        highlights: List<RecordHighlight>, pages: List<RecordPage>, catalog: AnalyteCatalog
    ): List<String> {
        val rows = fields.filter { it.state != FieldState.REJECTED }
        fun values(keys: List<String>) = keys.flatMap { k -> rows.filter { it.key == k }.map { it.valueText } }
        val clinical = mutableListOf<String>()
        if (record.recordType != RecordType.OTHER) clinical += record.recordType.raw.replace('_', ' ')
        clinical += values(CLINICAL_KEYS)
        clinical += observations.filter { it.state != FieldState.REJECTED && it.analyteId != null && catalog.analyte(it.analyteId) != null }
            .map { it.analyteId!! }.distinct().sorted().map { catalog.displayName(it)!! }
        val distinct = clinical.filter { it.isNotEmpty() }.distinct()
        return listOf(
            RecordText.fold(record.title),
            RecordText.fold(values(PEOPLE_KEYS).joinToString("\n")),
            RecordText.fold(distinct.joinToString("\n")),
            RecordText.fold(pages.sortedBy { it.pageIndex }.joinToString("\n") { it.text ?: "" }),
            RecordText.fold((listOf(record.notes ?: "") + tagNames).joinToString(" ")),
            RecordText.fold(highlights.filter { !it.dismissed }.sortedWith(compareBy({ it.section.raw }, { it.position })).joinToString("\n") { it.text })
        )
    }

    /** FTS4 `simple` tokenizer over folded text: runs of ASCII letters/digits and code points ≥ U+0080. */
    fun ftsTokens(s: String): List<String> {
        val out = mutableListOf<String>()
        val cur = StringBuilder()
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            if (cp in 'a'.code..'z'.code || cp in '0'.code..'9'.code || cp in 'A'.code..'Z'.code || cp >= 0x80) cur.appendCodePoint(cp)
            else if (cur.isNotEmpty()) { out += cur.toString(); cur.setLength(0) }
            i += Character.charCount(cp)
        }
        if (cur.isNotEmpty()) out += cur.toString()
        return out
    }

    /** FTS4 `matchinfo('pcnalx')` of one row for `term*` phrases, computed from tokens (for [RecordSearchRanking.bm25]). */
    fun matchinfo(terms: List<String>, doc: List<List<String>>, all: List<List<List<String>>>): IntArray {
        val c = doc.size
        val n = all.size
        val out = IntArray(3 + 2 * c + 3 * c * terms.size)
        out[0] = terms.size; out[1] = c; out[2] = n
        for (col in 0 until c) {
            out[3 + col] = if (n == 0) 0 else ((all.sumOf { it[col].size.toLong() } + n / 2) / n).toInt()
            out[3 + c + col] = doc[col].size
        }
        terms.forEachIndexed { p, t ->
            for (col in 0 until c) {
                val base = 3 + 2 * c + 3 * (col + p * c)
                out[base] = doc[col].count { it.startsWith(t) }
                out[base + 1] = all.sumOf { row -> row[col].count { it.startsWith(t) } }
                out[base + 2] = all.count { row -> row[col].any { it.startsWith(t) } }
            }
        }
        return out
    }

    /** `0.15 × max(0, 1 − days/730)`, days = max(0, today − sort_date). */
    fun recencyBoost(sortDate: String, today: LocalDate): Double = RecordSearchRanking.recencyBoost(sortDate, today)

    private fun wordsPrefixMatch(words: List<String>, wanted: List<String>): Boolean {
        var i = 0
        for (w in words) if (i < wanted.size && w.startsWith(wanted[i])) i++
        return i == wanted.size
    }

    private fun observationMatches(o: Observation, cond: AnalyteCondition): Boolean {
        if (o.analyteId != cond.analyteId || o.state == FieldState.REJECTED) return false
        cond.flag?.let { return o.flag.raw in STORED_FLAGS[it].orEmpty() }
        val cv = cond.canonicalValue ?: return false
        val v = o.canonicalValue ?: return false
        if (o.canonicalUnit != cond.canonicalUnit) return false
        return when (cond.op) { ">" -> v > cv; ">=" -> v >= cv; "<" -> v < cv; "<=" -> v <= cv; else -> false }
    }

    private fun searchLimit(e: JsonElement?): Int {
        val p = e as? JsonPrimitive ?: return SEARCH_LIMIT_DEFAULT
        if (p is JsonNull || p.isString || p.booleanOrNull != null) return SEARCH_LIMIT_DEFAULT
        val d = p.doubleOrNull ?: return SEARCH_LIMIT_DEFAULT
        if (d != floor(d)) return SEARCH_LIMIT_DEFAULT
        return d.coerceIn(1.0, SEARCH_LIMIT_CAP.toDouble()).toInt()
    }

    // -- records_search -----------------------------------------------------------------------------------

    suspend fun search(
        data: RecordsCoachData, contract: RecordsCoachContract, args: JsonObject, selectedIds: List<String>,
        today: LocalDate, dateOrder: DateOrder
    ): JsonObject {
        val query = stringArg(args, "query")
        val (range, err) = dateArgs(contract, args)
        if (err != null) return err
        val limit = searchLimit(args["limit"])
        val catalog = data.catalog
        val q = RecordQueryParser.parse(query, today, dateOrder, catalog)
        val dateFrom = listOfNotNull(q.dateFrom, range!!.from).maxOrNull()
        val dateTo = listOfNotNull(q.dateTo, range.to).minOrNull()
        var types: Set<String>? = q.recordTypes.map { it.raw }.toSet().takeIf { it.isNotEmpty() }
        val rt = (args["record_type"] as? JsonPrimitive)?.takeIf { it !is JsonNull && it.isString }?.content
        if (rt != null && RecordType.entries.any { it.raw == rt }) {
            types = if (types == null || rt in types) setOf(rt) else setOf("-")
        }
        val selected = selectedIds.toSet()
        val records = data.allRecords()
        val scope = records.filter { r -> if (selected.isNotEmpty()) r.id in selected else r.archived == q.archived }
        val needFields = q.flags.isNotEmpty() || !q.doctor.isNullOrEmpty() || !q.facility.isNullOrEmpty()
        val prefiltered = scope.filter { r ->
            (types == null || r.recordType.raw in types!!) &&
                (dateFrom == null || r.sortDate >= dateFrom) && (dateTo == null || r.sortDate <= dateTo) &&
                (!q.favorites || r.favorite) && (!q.needsReview || r.reviewStatus == ReviewStatus.NEEDS_REVIEW) &&
                (!q.received || r.source == RecordSource.SHARE_IN || r.source == RecordSource.OPEN_IN)
        }
        val fieldsById = if (needFields) data.fieldsFor(prefiltered.map { it.id }) else emptyMap()
        val condObs = if (q.analyteConditions.isEmpty()) emptyMap() else data.observationsOfAnalytes(q.analyteConditions.map { it.analyteId }.toSet()).groupBy { it.recordId }
        val hits = prefiltered.filter { r ->
            val rows = fieldsById[r.id].orEmpty().filter { it.state != FieldState.REJECTED }
            if (q.flags.isNotEmpty()) {
                val wanted = q.flags.flatMap { STORED_FLAGS[it].orEmpty() }.toSet()
                if (rows.none { it.key == FieldKey.TEST_RESULT && vj(it).str("flag") in wanted }) return@filter false
            }
            for ((key, name) in listOf(FieldKey.DOCTOR_NAME to q.doctor, FieldKey.FACILITY to q.facility)) {
                if (name == null || RecordText.normalizedValue(name).isEmpty()) continue
                val wanted = RecordText.normalizedValue(name).split(" ")
                if (rows.none { it.key == key && wordsPrefixMatch(ExtractionWriter.normalizedKey(key, it.valueText, it.valueJson).split(" "), wanted) }) return@filter false
            }
            val robs = condObs[r.id].orEmpty()
            q.analyteConditions.all { cond -> robs.any { observationMatches(it, cond) } }
        }
        val ordered = if (q.terms.isNotEmpty()) {
            val scores = data.bm25(q.terms)
            hits.filter { it.id in scores }
                .map { it to (scores.getValue(it.id) + recencyBoost(it.sortDate, today)) }
                .sortedWith(compareByDescending<Pair<HealthRecord, Double>> { it.second }.thenByDescending { it.first.sortDate }.thenByDescending { it.first.createdMs }.thenByDescending { it.first.seq })
                .map { it.first }
        } else {
            hits.sortedWith(TIMELINE.reversed())
        }
        val shown = ordered.take(limit)
        val shownIds = shown.map { it.id }
        val shownFields = data.fieldsFor(shownIds)
        val shownHighlights = data.highlightsFor(shownIds)
        val outRecords = shown.map { r ->
            val rows = shownFields[r.id].orEmpty()
            val hl = shownHighlights[r.id].orEmpty().filter { it.section == HighlightSection.IMPORTANT && !it.dismissed }.sortedBy { it.position }
            obj(
                "record_id" to r.id, "title" to r.title, "date" to r.sortDate, "record_type" to r.recordType.raw,
                "facility" to bestValue(rows, FieldKey.FACILITY), "doctor" to bestValue(rows, FieldKey.DOCTOR_NAME),
                "review_status" to r.reviewStatus.raw, "highlights" to hl.take(SEARCH_HIGHLIGHTS_CAP).map { it.text }
            )
        }
        val values = mutableListOf<JsonObject>()
        if (q.analytes.isNotEmpty() || q.analyteConditions.isNotEmpty()) {
            val scopeIds = scope.map { it.id }.toSet()
            val bare = q.analytes.toSet()
            val candidates = data.observationsOfAnalytes(bare + q.analyteConditions.map { it.analyteId }).filter { o ->
                if (o.recordId !in scopeIds || o.state == FieldState.REJECTED) return@filter false
                if (o.analyteId !in bare && q.analyteConditions.none { observationMatches(o, it) }) return@filter false
                val od = o.observedDate
                if ((dateFrom != null || dateTo != null) && od == null) return@filter false
                !((dateFrom != null && od!! < dateFrom) || (dateTo != null && od!! > dateTo))
            }.sortedWith(compareByDescending<Observation> { it.observedDate ?: "" }.thenByDescending { it.createdMs }.thenByDescending { it.id })
            for (o in candidates.take(SEARCH_VALUES_CAP)) {
                values += obj(
                    "record_id" to o.recordId, "analyte" to o.analyteId, "name" to o.rawName, "value" to o.valueText,
                    "unit" to o.unit, "flag" to o.flag.raw, "date" to o.observedDate
                )
            }
        }
        return obj("query" to query, "count" to outRecords.size, "records" to outRecords, "values" to values)
    }

    // -- records_get ------------------------------------------------------------------------------------------

    /** Public so Coach attachments are redacted by exactly this rule (docs/coach.md §6). */
    fun piiLine(folded: String, patientNames: List<String>): Boolean {
        if (RE_PII_LABEL.containsMatchIn(folded) || RE_PII_DIGITS.containsMatchIn(folded)) return true
        val padded = " " + RecordText.normalizedValue(folded) + " "
        return patientNames.any { it.isNotEmpty() && padded.contains(" $it ") }
    }

    fun textExcerpt(fields: List<RecordField>, pages: List<RecordPage>): String? {
        val names = fields.filter { it.key == FieldKey.PATIENT_NAME && it.state != FieldState.REJECTED }.map { RecordText.normalizedValue(it.valueText) }
        val parts = mutableListOf<String>()
        for (p in pages.sortedBy { it.pageIndex }) {
            val text = (p.text ?: "").replace("\r\n", "\n").replace("\r", "\n")
            val kept = text.split("\n").filter { !piiLine(RecordText.fold(it), names) }
            val page = kept.joinToString("\n").trim('\n')
            if (page.trim(' ', '\t', '\n').isNotEmpty()) parts += page
        }
        val text = parts.joinToString("\n\n")
        return if (text.isEmpty()) null else takeCodePoints(text, GET_TEXT_CHARS)
    }

    suspend fun get(data: RecordsCoachData, contract: RecordsCoachContract, args: JsonObject, selectedIds: List<String>): JsonObject {
        val rid = stringArg(args, "record_id")
        if (selectedIds.isNotEmpty() && rid !in selectedIds) return error(contract.errors.notSelected, mapOf("id" to rid))
        val rec = data.allRecords().firstOrNull { it.id == rid } ?: return error(contract.errors.unknownRecord, mapOf("id" to rid))
        val rows = data.fields(rid)
        val fields = rows.filter { it.state != FieldState.REJECTED && it.key !in GET_EXCLUDED_KEYS }.map { f ->
            obj("key" to f.key, "value" to (if (f.key == FieldKey.MEDICATION) medicationText(f) else f.valueText), "state" to f.state.raw, "source_page" to f.sourcePage)
        }
        val hls = data.highlights(rid).filter { !it.dismissed }.sortedWith(compareBy({ it.section.raw }, { it.position }))
        val includeText = (args["include_text"] as? JsonPrimitive)?.let { it !is JsonNull && !it.isString && it.booleanOrNull == true } == true
        return obj(
            "record_id" to rid, "title" to rec.title, "date" to rec.sortDate, "record_type" to rec.recordType.raw,
            "category" to rec.category.raw, "facility" to bestValue(rows, FieldKey.FACILITY), "doctor" to bestValue(rows, FieldKey.DOCTOR_NAME),
            "referrer" to bestValue(rows, FieldKey.DOCTOR_NAME, referrer = true), "patient_sex" to bestValue(rows, FieldKey.PATIENT_SEX),
            "patient_age" to bestValue(rows, FieldKey.PATIENT_AGE), "review_status" to rec.reviewStatus.raw,
            "ai_mode_used" to rec.aiModeUsed.raw, "page_count" to rec.pageCount, "fields" to fields,
            "test_results" to recordTestResults(rows, data.observations(rid), data.catalog).map { it.json() },
            "highlights" to hls.map { obj("section" to it.section.raw, "text" to it.text) },
            "text" to (if (includeText) textExcerpt(rows, data.pages(rid)) else null)
        )
    }

    // -- records_observation_series ---------------------------------------------------------------------------

    fun resolveSeriesAnalyte(name: String, catalog: AnalyteCatalog, userAliases: Map<String, String>): String? =
        if (catalog.analyte(name) != null) name else catalog.map(name, null, userAliases, null, null).analyteId

    suspend fun series(data: RecordsCoachData, contract: RecordsCoachContract, args: JsonObject, selectedIds: List<String>): JsonObject {
        val name = stringArg(args, "analyte")
        val (range, err) = dateArgs(contract, args)
        if (err != null) return err
        if (name.isBlank()) return error(contract.errors.unknownAnalyte, mapOf("analyte" to name))
        val recs = data.allRecords().associateBy { it.id }
        val selected = selectedIds.toSet()
        fun usable(o: Observation): Boolean {
            val od = o.observedDate ?: return false
            val rec = recs[o.recordId] ?: return false
            return o.state != FieldState.REJECTED && !o.excludedFromTrends &&
                (if (selected.isNotEmpty()) o.recordId in selected else !rec.archived) &&
                (range!!.from == null || od >= range.from) && (range.to == null || od <= range.to)
        }
        var aid = resolveSeriesAnalyte(name, data.catalog, data.userAliases())
        var picked = if (aid != null) data.observationsOfAnalytes(listOf(aid)).filter(::usable) else emptyList()
        if (picked.isEmpty()) {
            val key = AnalyteCatalog.normalizeTestName(name)
            picked = data.unmappedObservations().filter { usable(it) && AnalyteCatalog.normalizeTestName(it.rawName) == key }
            aid = null
        }
        if (picked.isEmpty()) return error(contract.errors.unknownAnalyte, mapOf("analyte" to name))
        val sorted = picked.sortedWith(compareBy<Observation>({ it.observedDate }, { it.createdMs }, { it.id }))
        val seen = HashSet<List<Any?>>()
        val points = mutableListOf<JsonObject>()
        for (o in sorted) {
            val vkey: List<Any?> = if (o.canonicalValue != null) listOf("c", o.canonicalValue) else listOf("u", o.unit, o.valueText)
            if (!seen.add(listOf(o.observedDate) + vkey)) continue
            points += obj(
                "date" to o.observedDate, "value" to o.valueText, "unit" to o.unit, "canonical_value" to o.canonicalValue,
                "flag" to o.flag.raw, "ref_low" to o.refLow, "ref_high" to o.refHigh, "record_id" to o.recordId,
                "record_title" to recs.getValue(o.recordId).title, "state" to o.state.raw
            )
        }
        val kept = points.takeLast(SERIES_CAP)
        val entry = aid?.let { data.catalog.analyte(it) }
        return obj(
            "analyte" to aid, "display_name" to (entry?.displayName ?: sorted.first().rawName),
            "unit" to entry?.canonicalUnit, "count" to kept.size, "points" to kept
        )
    }

    // -- §26 prompt lines, gating, refs ---------------------------------------------------------------------

    data class PromptLines(
        val advertiseTools: Boolean,
        val availableLine: String?,
        val guardrails: String?,
        val selectedLines: List<String>,
        val notAvailableLine: String?
    )

    private fun typeLabel(labels: Map<String, String>?, type: RecordType) = labels?.get(type.raw)?.takeIf { it.isNotEmpty() } ?: TYPE_LABELS[type.raw] ?: "Record"

    private fun selectedRecords(records: List<HealthRecord>, selectedIds: List<String>): List<HealthRecord> {
        val byId = records.associateBy { it.id }
        return selectedIds.mapNotNull { byId[it] }.distinctBy { it.id }.sortedWith(TIMELINE.reversed())
    }

    suspend fun promptLines(data: RecordsCoachData, contract: RecordsCoachContract, accessEnabled: Boolean, selectedIds: List<String>, typeLabels: Map<String, String>? = null): PromptLines {
        val p = contract.prompt
        if (!accessEnabled) return PromptLines(false, null, null, emptyList(), p.notAvailableLine)
        val records = data.allRecords()
        val live = records.filter { !it.archived }
        if (live.isEmpty()) return PromptLines(false, null, null, emptyList(), null)
        val available = fillPlaceholders(p.availableLine, mapOf("n" to live.size.toString(), "latest_date" to live.maxOf { it.sortDate }))
        val sel = selectedRecords(records, selectedIds)
        val lines = if (sel.isEmpty()) emptyList() else listOf(p.selectedHeader) + sel.map { r ->
            fillPlaceholders(p.selectedLine, mapOf("record_id" to r.id, "title" to (collapseWs(r.title) ?: ""), "date" to r.sortDate, "type_label" to typeLabel(typeLabels, r.recordType)))
        }
        return PromptLines(true, available, p.guardrails, lines, null)
    }

    /** §26: the §8.1 words of fold(message) contain a §17 type phrase or the word record/records. */
    fun mentionsRecords(message: String?): Boolean = RecordQueryParser.mentionsRecordType(message ?: "", extraWords = setOf("record", "records"))

    data class ToolCall(val name: String, val result: JsonObject)

    fun recordRefs(toolCalls: List<ToolCall>, packed: List<CoachRecordRef> = emptyList()): List<CoachRecordRef> {
        val out = LinkedHashMap<String, CoachRecordRef>()
        fun add(id: String?, title: String?, date: String?) {
            if (id != null && id !in out) out[id] = CoachRecordRef(id, title ?: "", date ?: "")
        }
        packed.forEach { add(it.recordId, it.title, it.date) }
        for (call in toolCalls) {
            val res = call.result
            if ("error" in res) continue
            fun JsonObject.s(k: String) = (this[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull
            when (call.name) {
                "records_get" -> add(res.s("record_id"), res.s("title"), res.s("date"))
                "records_observation_series" -> (res["points"] as? JsonArray).orEmpty().forEach { pt ->
                    (pt as? JsonObject)?.let { add(it.s("record_id"), it.s("record_title"), it.s("date")) }
                }
            }
        }
        return out.values.toList()
    }

    // -- §27 selection helpers ----------------------------------------------------------------------------------

    data class CompareResult(val previousId: String?, val rule: String?)

    suspend fun compareCandidate(data: RecordsCoachData, recordId: String): CompareResult {
        val records = data.allRecords()
        val rec = records.firstOrNull { it.id == recordId } ?: return CompareResult(null, null)
        val byId = records.associateBy { it.id }
        fun older(c: HealthRecord) = c.id != recordId && !c.archived && TIMELINE.compare(c, rec) < 0
        val linked = data.links(recordId).filter { it.kind == LinkKind.PREVIOUS_REPORT && it.status != LinkStatus.REJECTED && (it.aId == recordId || it.bId == recordId) }
            .mapNotNull { l -> byId[if (l.aId == recordId) l.bId else l.aId] }.filter(::older)
        linked.maxWithOrNull(TIMELINE)?.let { return CompareResult(it.id, "link") }
        if (rec.recordType !in LAB_LIKE) return CompareResult(null, null)
        val candidates = records.filter { older(it) && it.recordType in LAB_LIKE }
        val obs = data.observationsFor(candidates.map { it.id } + recordId)
        fun analytes(id: String) = obs[id].orEmpty().filter { it.state != FieldState.REJECTED && !it.analyteId.isNullOrEmpty() }.map { it.analyteId!! }.toSet()
        val mine = analytes(recordId)
        val shared = candidates.filter { (mine intersect analytes(it.id)).size >= 3 }
        return shared.maxWithOrNull(TIMELINE)?.let { CompareResult(it.id, "shared_analytes") } ?: CompareResult(null, null)
    }

    data class LabSelection(val showChips: Boolean, val latest: List<String>, val compare: List<String>?)

    suspend fun latestLabSelection(data: RecordsCoachData): LabSelection {
        val labs = data.allRecords().filter { it.recordType == RecordType.LAB_REPORT && !it.archived }.sortedWith(TIMELINE.reversed())
        if (labs.isEmpty()) return LabSelection(false, emptyList(), null)
        val prev = compareCandidate(data, labs[0].id).previousId
        return LabSelection(true, labs.take(3).map { it.id }, prev?.let { listOf(labs[0].id, it) })
    }

    // -- §29 on-device block -----------------------------------------------------------------------------------

    data class Packed(val text: String?, val recordIds: List<String>, val droppedResults: Int, val records: List<HealthRecord> = emptyList())

    private fun stripRefBrackets(refText: String?): String? {
        var t = collapseWs(refText)
        if (t != null && t.length >= 2 && ((t.first() == '(' && t.last() == ')') || (t.first() == '[' && t.last() == ']'))) {
            t = t.substring(1, t.length - 1).trim(' ')
        }
        return t?.takeIf { it.isNotEmpty() }
    }

    private fun elementText(e: JsonElement): String? = (e as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content

    private fun resultItem(tr: TestResult): String {
        val parts = listOf(tr.name, elementText(tr.value), tr.unit).mapNotNull { collapseWs(it)?.takeIf { s -> s.isNotEmpty() } }
        val inner = mutableListOf<String>()
        FLAG_WORD[tr.flag]?.let { inner += it }
        stripRefBrackets(tr.refText)?.let { inner += "ref $it" }
        var text = parts.joinToString(" ")
        if (inner.isNotEmpty()) text += " (" + inner.joinToString(", ") + ")"
        return text
    }

    private fun flagGroup(flag: String) = when (flag) {
        "critical_low", "critical_high" -> 0
        "low", "high" -> 1
        "abnormal" -> 2
        else -> 3
    }

    private class Block(
        val record: HealthRecord, val facility: String?, val doctor: String?,
        val results: MutableList<Pair<String, Boolean>>, var dropped: Int,
        val medications: MutableList<String>, val diagnoses: MutableList<String>, val recommendations: MutableList<String>
    )

    suspend fun pack(data: RecordsCoachData, selectedIds: List<String>, typeLabels: Map<String, String>? = null): Packed {
        val sel = selectedRecords(data.allRecords(), selectedIds).take(CONTEXT_MAX_RECORDS)
        if (sel.isEmpty()) return Packed(null, emptyList(), 0)
        val fieldsById = data.fieldsFor(sel.map { it.id })
        val obsById = data.observationsFor(sel.map { it.id })
        val blocks = sel.map { r ->
            val allRows = fieldsById[r.id].orEmpty()
            val rows = allRows.filter { it.state != FieldState.REJECTED }
            val results = recordTestResults(allRows, obsById[r.id].orEmpty(), data.catalog).withIndex()
                .sortedWith(compareBy({ flagGroup(it.value.flag) }, { it.index })).map { it.value }
            val meds = rows.filter { it.key == FieldKey.MEDICATION }.map { f ->
                val v = vj(f)
                listOf(v.str("name").takeIf { truthy(it) } ?: f.valueText, v.str("strength"), v.str("frequency"))
                    .filter { truthy(it) }.mapNotNull { collapseWs(it)?.takeIf { s -> s.isNotEmpty() } }.joinToString(" ")
            }.filter { it.isNotEmpty() }
            fun texts(key: String) = rows.filter { it.key == key }.mapNotNull { collapseWs(it.valueText)?.takeIf { s -> s.isNotEmpty() } }
            Block(
                r, collapseWs(bestValue(rows, FieldKey.FACILITY)), collapseWs(bestValue(rows, FieldKey.DOCTOR_NAME)),
                results.map { resultItem(it) to (it.flag in FLAGGED) }.toMutableList(), 0,
                meds.toMutableList(), texts(FieldKey.DIAGNOSIS).toMutableList(), texts(FieldKey.RECOMMENDATION).toMutableList()
            )
        }.toMutableList()

        fun render(): String {
            val lines = mutableListOf(PACK_HEADER)
            for (b in blocks) {
                val r = b.record
                lines += "### ${collapseWs(r.title)} — ${r.sortDate} (${typeLabel(typeLabels, r.recordType)})"
                val fd = listOfNotNull(b.facility?.takeIf { it.isNotEmpty() }?.let { "Facility: $it" }, b.doctor?.takeIf { it.isNotEmpty() }?.let { "Doctor: $it" })
                if (fd.isNotEmpty()) lines += fd.joinToString(" · ")
                if (b.results.isNotEmpty() || b.dropped > 0) {
                    var line = "Results:"
                    if (b.results.isNotEmpty()) line += " " + b.results.joinToString("; ") { it.first }
                    if (b.dropped > 0) line += " (+${b.dropped} more results not shown)"
                    lines += line
                }
                if (b.medications.isNotEmpty()) lines += "Medications: " + b.medications.joinToString("; ")
                if (b.diagnoses.isNotEmpty()) lines += "Diagnoses: " + b.diagnoses.joinToString("; ")
                if (b.recommendations.isNotEmpty()) lines += "Recommendations: " + b.recommendations.joinToString("; ")
            }
            return lines.joinToString("\n")
        }

        fun dropOne(): Boolean {
            for (flagged in listOf(false, true)) {
                for (b in blocks.asReversed()) {
                    val i = b.results.indexOfLast { it.second == flagged }
                    if (i >= 0) {
                        b.results.removeAt(i)
                        b.dropped++
                        return true
                    }
                }
            }
            for (pick in listOf<(Block) -> MutableList<String>>({ it.recommendations }, { it.diagnoses }, { it.medications })) {
                for (b in blocks.asReversed()) {
                    val list = pick(b)
                    if (list.isNotEmpty()) {
                        list.removeAt(list.lastIndex)
                        return true
                    }
                }
            }
            if (blocks.size > 1) {
                blocks.removeAt(blocks.lastIndex)
                return true
            }
            return false
        }

        var text = render()
        while (codePoints(text) > CONTEXT_MAX_CHARS && dropOne()) text = render()
        return Packed(text, blocks.map { it.record.id }, blocks.sumOf { it.dropped }, blocks.map { it.record })
    }

    // -- selection ----------------------------------------------------------------------------------------------

    fun ref(record: HealthRecord) = CoachRecordRef(record.id, record.title, record.sortDate)

    /** Selection order kept, duplicates removed, capped at 10. */
    fun normalizeSelection(ids: List<String>): List<String> = ids.distinct().take(MAX_SELECTED)

    /** "Explain this trend": every record of the series, newest first, at most 10. */
    fun trendSelection(points: List<Pair<String, List<String>>>): List<String> =
        points.withIndex().sortedWith(compareByDescending<IndexedValue<Pair<String, List<String>>>> { it.value.first }.thenByDescending { it.index })
            .flatMap { it.value.second }.distinct().take(MAX_SELECTED)

    // -- JSON ---------------------------------------------------------------------------------------------------

    private fun num(d: Double?): JsonElement = if (d == null || !d.isFinite()) JsonNull else JsonPrimitive(d)

    fun obj(vararg pairs: Pair<String, Any?>): JsonObject =
        JsonObject(linkedMapOf(*pairs.map { (k, v) -> k to element(v) }.toTypedArray()))

    private fun element(v: Any?): JsonElement = when (v) {
        is Double -> num(v)
        else -> RecordJson.element(v)
    }
}
