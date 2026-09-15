package com.ayuvo.health.records.processing

import com.ayuvo.health.records.model.ExtractedField
import com.ayuvo.health.records.model.ExtractionMethod
import com.ayuvo.health.records.model.RecordType
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import java.time.LocalDate

/** §9.3 AI response validation, ported from the reference `validate_ai` and `lenient_json`. */
object ExtractionValidator {

    data class ValidatedExtraction(
        val fields: List<ExtractedField>,
        val summary: String?,
        val fromImageItems: Boolean,
        val recordType: RecordType?,
        val dropped: Int
    )

    data class Dropped(val kind: String, val index: Int, val reason: String)

    /** One validated item: reference fields plus `from_image`. */
    data class Item(
        val key: String,
        val valueText: String,
        val valueJson: JsonObject?,
        val evidence: String?,
        val method: String,
        val confidence: Double,
        val sourcePage: Int,
        val fromImage: Boolean
    )

    data class TypeGuess(val recordType: String, val confidence: Double, val method: String)
    data class Summary(val text: String, val sourcePages: List<Int>)

    data class Result(
        val recordType: TypeGuess?,
        val reportName: Item?,
        val items: List<Item>,
        val summary: Summary?,
        val dropped: List<Dropped>,
        val error: String?
    )

    private val WS = Py.re("[ \t\n]+")
    private val RE_NUMS = Py.re("[0-9]+(?:[.,][0-9]+)*")
    private val RE_ISO_VALUE = Py.re("([0-9]{4})-([0-9]{2})(?:-([0-9]{2}))?")
    private val AI_FIELD_KEYS = setOf(
        "doctor_name", "doctor_specialty", "facility", "department", "patient_name",
        "patient_age", "patient_sex", "diagnosis", "symptom", "procedure", "recommendation", "document_time"
    )
    private val AI_DATE_KEYS = setOf("report_date", "collection_date", "prescription_date", "discharge_date", "visit_date", "admission_date", "follow_up_date")
    private val RECORD_TYPES = RecordType.entries.map { it.raw }.toSet()

    private fun ws(s: String): String = WS.matcher(s).replaceAll(" ").trim()
    private fun flat(s: String?): String = Py.strip(WS.matcher(RecordText.fold(s ?: "")).replaceAll(" "), " ")
    private fun numbers(s: String?): List<String> = Py.findall(RE_NUMS, RecordText.fold(s ?: "")).map { it.replace(",", "") }
    private fun numsIn(value: String, evidence: String): Boolean {
        val ev = numbers(evidence).toSet()
        return numbers(value).all { it in ev }
    }

    private fun contains(value: String, ev: String): Boolean {
        val nv = RecordText.normalizedValue(value)
        return nv.isNotEmpty() && " $nv " in " ${RecordText.normalizedValue(ev)} "
    }

    private fun strOf(e: JsonElement?): String? = (e as? JsonPrimitive)?.takeIf { it.isString }?.content

    /** Python `isinstance(x, int)` on a json.loads value (bools excluded by the caller). */
    private fun intOf(e: JsonElement?): Int? {
        val p = e as? JsonPrimitive ?: return null
        if (p is JsonNull || p.isString || p.booleanOrNull != null) return null
        return if (Regex("-?[0-9]+").matches(p.content)) p.content.toIntOrNull() else null
    }

    private fun isBool(e: JsonElement?): Boolean = (e as? JsonPrimitive)?.let { !it.isString && it !is JsonNull && it.booleanOrNull != null } == true

    /** Reference `_conf`: float(x) clamped to [0, 0.9]; unparseable or NaN → 0.5. */
    private fun conf(e: JsonElement?): Double {
        val p = e as? JsonPrimitive ?: return 0.5
        if (p is JsonNull) return 0.5
        val v = when {
            !p.isString && p.booleanOrNull != null -> if (p.booleanOrNull == true) 1.0 else 0.0
            else -> pyFloat(p.content)
        } ?: return 0.5
        if (v.isNaN()) return 0.5
        return maxOf(0.0, minOf(0.9, v))
    }

    private fun pyFloat(text: String): Double? {
        val t = text.trim().lowercase()
        return when (t) {
            "inf", "+inf", "infinity", "+infinity" -> Double.POSITIVE_INFINITY
            "-inf", "-infinity" -> Double.NEGATIVE_INFINITY
            "nan", "+nan", "-nan" -> Double.NaN
            else -> if (Regex("[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:e[+-]?[0-9]+)?").matches(t)) t.toDoubleOrNull() else null
        }
    }

    /** Existing lenient extractor semantics: first '{' to last '}' parsed as an object. */
    fun lenientJson(raw: String): JsonObject? {
        val a = raw.indexOf('{')
        val b = raw.lastIndexOf('}')
        if (a < 0 || b <= a) return null
        return runCatching { kotlinx.serialization.json.Json.parseToJsonElement(raw.substring(a, b + 1)) as? JsonObject }.getOrNull()
    }

    /**
     * pages: page texts (OCR text for image pages, "" when none); imagePages: 1-based page numbers sent as
     * images; mode: `local` | `cloud`.
     */
    fun validateAi(pages: List<String>, aiJson: JsonElement, mode: String, today: LocalDate, order: DateOrder, imagePages: Set<Int> = emptySet()): Result {
        val method = if (mode == "local") "ai_local" else "ai_cloud"
        val data: JsonObject? = when (aiJson) {
            is JsonObject -> aiJson
            is JsonPrimitive -> if (aiJson.isString) lenientJson(aiJson.content) else null
            else -> null
        }
        val dropped = mutableListOf<Dropped>()
        val items = mutableListOf<Item>()
        if (data == null) return Result(null, null, emptyList(), null, emptyList(), "parse_error")
        val flatPages = pages.map { flat(it) }

        fun drop(kind: String, i: Int, reason: String) { dropped += Dropped(kind, i, reason) }

        fun pageOf(kind: String, i: Int, item: JsonObject): Int? {
            val sp = item["source_page"]
            if (sp == null || sp is JsonNull || isBool(sp)) { drop(kind, i, "missing_source_page"); return null }
            val n = intOf(sp)
            if (n == null || n < 1 || n > pages.size) { drop(kind, i, "bad_source_page"); return null }
            return n
        }

        fun evidenceOk(kind: String, i: Int, item: JsonObject, sp: Int): String? {
            val ev = strOf(item["evidence"])
            if (ev == null || flat(ev).isEmpty()) { drop(kind, i, "evidence_not_found"); return null }
            val text = flatPages[sp - 1]
            if (text.isEmpty() && sp in imagePages) return "image_only"
            if (flat(ev) !in text) { drop(kind, i, "evidence_not_found"); return null }
            return "ok"
        }

        fun finish(key: String, valueText: String, valueJson: JsonObject?, evidence: String?, sp: Int, status: String, c: Double) {
            var confidence = c
            if (status == "image_only") confidence = minOf(confidence, 0.5)
            items += Item(key, valueText, valueJson, evidence, method, confidence, sp - 1, sp in imagePages)
        }

        var typeGuess: TypeGuess? = null
        strOf(data["record_type"])?.takeIf { it in RECORD_TYPES }?.let { typeGuess = TypeGuess(it, conf(data["record_type_confidence"]), method) }
        var reportName: Item? = null
        strOf(data["report_name"])?.takeIf { RecordText.normalizedValue(it).isNotEmpty() }?.let { rn ->
            for ((pi, p) in pages.withIndex()) {
                if (contains(rn, p)) {
                    reportName = Item("report_name", ws(rn), null, null, method, 0.7, pi, (pi + 1) in imagePages)
                    break
                }
            }
        }

        fun list(name: String): List<JsonElement> = (data[name] as? JsonArray).orEmpty()

        for ((i, e) in list("dates").withIndex()) {
            val d = e as? JsonObject
            if (d == null) { drop("dates", i, "bad_item"); continue }
            val key = strOf(d["key"])
            if (key == null || key !in AI_DATE_KEYS) { drop("dates", i, "unknown_key"); continue }
            val sp = pageOf("dates", i, d) ?: continue
            val st = evidenceOk("dates", i, d, sp) ?: continue
            val v = strOf(d["value"])
            val m = v?.let { Py.full(RE_ISO_VALUE, it) }
            val dv = m?.let { DateDetector.valid(it.group(1).toInt(), it.group(2).toInt(), (it.group(3) ?: "1").toInt()) }
            if (dv == null || !DateDetector.plausible(dv, key, today)) { drop("dates", i, "bad_date"); continue }
            val precision = if (m.group(3) != null) "day" else "month"
            val ev = strOf(d["evidence"])!!
            val found = DateDetector.candidates(RecordText.fold(ev), today, order, bothOrders = true).any { dv in it.dates && it.precision == precision }
            if (!found) { drop("dates", i, "date_not_in_evidence"); continue }
            finish(key, dv.toString(), RuleItem.obj("precision" to precision), ws(ev), sp, st, conf(d["confidence"]))
        }

        for ((i, e) in list("fields").withIndex()) {
            val fd = e as? JsonObject
            if (fd == null) { drop("fields", i, "bad_item"); continue }
            val key = strOf(fd["key"])
            if (key == null || key !in AI_FIELD_KEYS) { drop("fields", i, "unknown_key"); continue }
            val sp = pageOf("fields", i, fd) ?: continue
            val st = evidenceOk("fields", i, fd, sp) ?: continue
            val v = strOf(fd["value"])
            if (v == null || RecordText.normalizedValue(v).isEmpty()) { drop("fields", i, "empty_value"); continue }
            val ev = strOf(fd["evidence"])!!
            if (!numsIn(v, ev)) { drop("fields", i, "number_not_in_evidence"); continue }
            if (key != "patient_sex" && !contains(v, ev)) { drop("fields", i, "value_not_in_evidence"); continue }
            finish(key, ws(v), null, ws(ev), sp, st, conf(fd["confidence"]))
        }

        for ((i, e) in list("test_results").withIndex()) {
            val tr = e as? JsonObject
            if (tr == null) { drop("test_results", i, "bad_item"); continue }
            val sp = pageOf("test_results", i, tr) ?: continue
            val st = evidenceOk("test_results", i, tr, sp) ?: continue
            val name = strOf(tr["name"])
            val value = strOf(tr["value"])
            val ev = strOf(tr["evidence"])!!
            if (name == null || RecordText.normalizedValue(name).isEmpty() || value == null || value.isBlank()) { drop("test_results", i, "empty_value"); continue }
            if (!contains(name, ev)) { drop("test_results", i, "value_not_in_evidence"); continue }
            if (numbers(value).isEmpty() && !contains(value, ev)) { drop("test_results", i, "value_not_in_evidence"); continue }
            if (!numsIn(value, ev)) { drop("test_results", i, "number_not_in_evidence"); continue }
            val vm = Py.full(LabRowParser.RE_VALUE_NUM, RecordText.fold(value).trim())
            var refText = strOf(tr["ref_text"])?.takeIf { it.isNotBlank() }
            if (refText != null && !numsIn(refText, ev)) refText = null
            val tail = LabRowParser.Tail()
            if (refText != null) {
                val rf = Py.strip(RecordText.fold(refText), " ()[]")
                for ((rx, kind) in LabRowParser.REF_PATTERNS) {
                    val fm = Py.full(rx, rf)
                    if (fm != null) { LabRowParser.setRef(tail, fm, kind); break }
                }
            }
            var unit: String? = null
            strOf(tr["unit"])?.takeIf { it.isNotBlank() }?.let { u ->
                val fu = RecordText.fold(u).trim()
                val matched = UnitsCatalog.active.matchAt(fu, 0)
                if (matched != null && matched.folded.length == fu.length) unit = matched.canonical
                else if (RecordText.normalizedValue(u).isNotEmpty() && fu in RecordText.fold(ev)) unit = ws(u)
            }
            val foldedEv = RecordText.fold(ev)
            val vf = RecordText.fold(value).trim()
            // The value occurrence after the printed name whose neighbours are not digits or '.'.
            val kn = foldedEv.indexOf(WS.matcher(RecordText.fold(name)).replaceAll(" ").trim())
            var k = foldedEv.indexOf(vf, if (kn >= 0) kn + RecordText.fold(name).trim().length else 0)
            while (k >= 0 && ((k > 0 && foldedEv[k - 1] in "0123456789.") || (k + vf.length < foldedEv.length && foldedEv[k + vf.length] in "0123456789."))) {
                k = foldedEv.indexOf(vf, k + 1)
            }
            var flagRaw: String? = null
            if (k >= 0) {
                val after = foldedEv.substring(k + vf.length).trimStart(' ')
                val fmm = Py.match(LabRowParser.RE_FLAG, after) ?: Py.match(LabRowParser.RE_FLAG_ATTACHED, foldedEv.substring(k + vf.length))
                if (fmm != null) flagRaw = fmm.group(1)
            }
            tail.flagRaw = flagRaw
            tail.valueNum = vm?.let { LabRowParser.numValue(it.group(2)) }
            tail.comparator = vm?.group(1)
            tail.qualitative = vm == null
            tail.value = value
            tail.refText = refText
            val flag = LabRowParser.resolveFlag(tail)
            val vj = RuleItem.obj(
                "name" to ws(name), "value" to value.trim(), "value_num" to RuleItem.num(tail.valueNum), "unit" to unit,
                "ref_text" to refText, "ref_low" to RuleItem.num(tail.refLow), "ref_high" to RuleItem.num(tail.refHigh), "flag" to flag
            )
            finish("test_result", ws(name), vj, ws(ev), sp, st, conf(tr["confidence"]))
        }

        for ((i, e) in list("medications").withIndex()) {
            val md = e as? JsonObject
            if (md == null) { drop("medications", i, "bad_item"); continue }
            val sp = pageOf("medications", i, md) ?: continue
            val st = evidenceOk("medications", i, md, sp) ?: continue
            val name = strOf(md["name"])
            val ev = strOf(md["evidence"])!!
            if (name == null || RecordText.normalizedValue(name).isEmpty()) { drop("medications", i, "empty_value"); continue }
            if (!contains(name, ev) || !numsIn(name, ev)) { drop("medications", i, "value_not_in_evidence"); continue }
            val pairs = mutableListOf<Pair<String, Any?>>("name" to ws(name))
            for (key in listOf("strength", "form", "dose", "frequency", "duration", "instructions")) {
                val v = strOf(md[key])
                pairs += key to (if (v != null && v.isNotBlank() && numsIn(v, ev) && (key == "form" || contains(v, ev))) ws(v) else null)
            }
            finish("medication", ws(name), RuleItem.obj(*pairs.toTypedArray()), ws(ev), sp, st, conf(md["confidence"]))
        }

        var summary: Summary? = null
        (data["summary"] as? JsonObject)?.let { sm ->
            val text = strOf(sm["text"])
            if (text != null && text.isNotBlank()) {
                val allNums = pages.flatMap { numbers(it) }.toSet()
                if (numbers(text).all { it in allNums }) {
                    val sps = (sm["source_pages"] as? JsonArray).orEmpty().filter { !isBool(it) }.mapNotNull { intOf(it) }.filter { it in 1..pages.size }.map { it - 1 }
                    var t = ws(text)
                    if (t.length > 400) t = t.substring(0, 399) + "…"
                    summary = Summary(t, sps)
                } else {
                    drop("summary", 0, "number_not_in_pages")
                }
            }
        }
        return Result(typeGuess, reportName, items, summary, dropped, null)
    }

    /** Pipeline facade: [pageTexts] keyed by 0-based page index, [imagePages] 0-based. */
    fun validate(
        responseText: String,
        pageTexts: Map<Int, String>,
        imagePages: Set<Int>,
        method: ExtractionMethod,
        provider: String?,
        today: LocalDate,
        dateOrder: DateOrder = DateOrder.DMY
    ): ValidatedExtraction {
        val size = (pageTexts.keys.maxOrNull() ?: -1) + 1
        val pages = (0 until size).map { pageTexts[it].orEmpty() }
        val result = validateAi(
            pages, JsonPrimitive(responseText), if (method == ExtractionMethod.AI_LOCAL) "local" else "cloud",
            today, dateOrder, imagePages.map { it + 1 }.toSet()
        )
        val all = listOfNotNull(result.reportName) + result.items
        val fields = all.map { item ->
            ExtractedField(
                key = item.key, valueText = item.valueText, valueJson = item.valueJson?.toString(), method = method,
                confidence = item.confidence, sourcePage = item.sourcePage, evidence = item.evidence
            )
        }
        return ValidatedExtraction(
            fields = fields,
            summary = result.summary?.text,
            fromImageItems = all.any { it.fromImage },
            recordType = result.recordType?.recordType?.let(RecordType::fromRaw),
            dropped = result.dropped.size
        )
    }
}

/** §9.3 page chunking for AI calls, ported from the reference `ai_chunks`. */
object AiChunker {
    const val CLOUD_CHARS = 12_000
    const val LOCAL_CHARS = 2_500

    data class Chunk(val pages: List<Int>, val text: String)

    /** Pages as "=== Page N ===\n<pfold text>" (N 1-based), packed greedily into chunks ≤ the mode's limit. */
    fun chunks(pages: List<String>, mode: String): List<Chunk> {
        val limit = if (mode == "local") LOCAL_CHARS else CLOUD_CHARS
        val blocks = mutableListOf<Pair<Int, String>>()
        for ((i, t) in pages.withIndex()) {
            val body = RecordText.pfold(t).trim('\n')
            if (body.isBlank()) continue
            val header = "=== Page ${i + 1} ===\n"
            val room = limit - header.length
            var cur = ""
            for (line0 in body.split('\n')) {
                var line = line0
                while (line.length > room) {
                    if (cur.isNotEmpty()) { blocks += (i + 1) to header + cur; cur = "" }
                    blocks += (i + 1) to header + line.substring(0, room)
                    line = line.substring(room)
                }
                val cand = if (cur.isEmpty()) line else cur + "\n" + line
                if (cand.length > room) { blocks += (i + 1) to header + cur; cur = line } else cur = cand
            }
            if (cur.isNotEmpty()) blocks += (i + 1) to header + cur
        }
        val out = mutableListOf<Pair<MutableList<Int>, StringBuilder>>()
        for ((pno, text) in blocks) {
            val last = out.lastOrNull()
            if (last != null && last.second.length + 2 + text.length <= limit) {
                last.second.append("\n\n").append(text)
                if (last.first.last() != pno) last.first += pno
            } else {
                out += mutableListOf(pno) to StringBuilder(text)
            }
        }
        return out.map { Chunk(it.first, it.second.toString()) }
    }
}
