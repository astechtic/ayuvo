package com.ayuvo.health.records.processing

import com.ayuvo.health.records.model.ExtractedField
import com.ayuvo.health.records.model.ExtractionMethod
import com.ayuvo.health.records.model.FieldState
import com.ayuvo.health.records.model.RecordField
import com.ayuvo.health.records.search.RecordQueryParser
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import java.time.LocalDate

/**
 * Runs `shared/records/test-vectors/<file>` through the Kotlin ports (dispatch mirrors the reference
 * `run_case`) and compares with the expected JSON: objects by key set, arrays in order, numbers by value.
 */
object RecordsVectors {
    private val json = Json { isLenient = false }

    private val config by lazy { RecordTypesConfig.parse(RecordsTestFiles.shared("record_types.json")!!.readText()) }
    private val units by lazy { UnitsCatalog.parseOrDefault(RecordsTestFiles.shared("units.json")!!.readText()) }
    private val classifier by lazy { DocumentClassifier(config) }
    private val fields by lazy { FieldExtractor(config, units) }
    private val labRows by lazy { LabRowParser(units) }
    private val boundaries by lazy { BoundaryDetector(classifier, units) }

    data class Outcome(val file: String, val passed: Int, val total: Int, val failures: List<String>)

    fun run(file: String): Outcome {
        val f = RecordsTestFiles.shared("test-vectors/$file") ?: run { fail("shared/records/test-vectors/$file missing"); error("") }
        val root = json.parseToJsonElement(f.readText()).jsonObj()
        val function = root.str("function") ?: error("$file has no function")
        val cases = (root["cases"] as? JsonArray) ?: error("$file has no cases")
        val failures = mutableListOf<String>()
        var passed = 0
        for (c in cases) {
            val case = c.jsonObj()
            val name = case.str("name") ?: "?"
            val expected = case["expected"]!!
            val actual = try {
                runCase(function, case["input"]!!.jsonObj())
            } catch (e: Throwable) {
                failures += "$name: threw ${e.javaClass.simpleName}: ${e.message}"
                continue
            }
            val diff = diff(expected, actual, "$")
            if (diff == null) passed++ else failures += "$name: $diff\n    expected=${expected.toString().take(1500)}\n    actual  =${actual.toString().take(1500)}"
        }
        return Outcome(file, passed, cases.size, failures)
    }

    fun assertAll(file: String) {
        val o = run(file)
        println("VECTORS ${o.file}: ${o.passed}/${o.total}")
        assertTrue("${o.file}: ${o.passed}/${o.total} passed\n" + o.failures.joinToString("\n"), o.failures.isEmpty() && o.total > 0)
    }

    // -- dispatch ---------------------------------------------------------------------------------

    private fun pages(input: JsonObject): List<String> = (input["pages"] as JsonArray).map { (it as JsonPrimitive).content }
    private fun today(input: JsonObject): LocalDate = LocalDate.parse(input.str("today"))
    private fun order(input: JsonObject): DateOrder = if (input.str("date_order") == "mdy") DateOrder.MDY else DateOrder.DMY

    fun runCase(function: String, input: JsonObject): JsonElement = when (function) {
        "fold" -> {
            val t = input.str("text")!!
            obj("fold" to RecordText.fold(t), "pfold" to RecordText.pfold(t), "normalized" to RecordText.normalizedValue(t), "words" to RecordText.words(RecordText.fold(t)))
        }
        "classify" -> {
            val c = classifier.classify(pages(input))
            obj(
                "record_type" to c.type.raw, "category" to c.category.raw, "confidence" to c.confidence,
                "scores" to JsonObject(c.scores.entries.associate { it.key.raw to RuleItem.num(it.value) })
            )
        }
        "extract_dates" -> obj("items" to DateDetector.extractItems(pages(input), input.str("record_type")!!, today(input), order(input)).map { it.toJson() })
        "extract_fields" -> obj("items" to fields.extractItems(pages(input), input.str("record_type")!!).map { it.toJson() })
        "parse_lab_rows" -> obj("items" to labRows.parseRows(pages(input), input.str("record_type")!!).map { it.toJson() })
        "detect_boundaries" -> {
            val r = boundaries.analyze(pages(input), today(input), order(input))
            obj(
                "page_scores" to r.pageScores.map { obj("page" to it.page, "score" to it.score, "signals" to it.signals) },
                "segments" to r.segments.map { obj("page_start" to it.pageStart, "page_end" to it.pageEnd, "record_type" to it.recordType.raw, "title" to it.title, "confidence" to it.confidence) },
                "propose" to r.propose
            )
        }
        "build_highlights" -> {
            val rows = (input["fields"] as JsonArray).map { e ->
                val o = e.jsonObj()
                HighlightBuilder.Row(o.str("key")!!, o.str("value_text") ?: "", o["value_json"] as? JsonObject, o.num("confidence"), o.num("source_page")?.toInt(), o.str("state"))
            }
            val summary = (input["summary"] as? JsonObject)?.let { s ->
                HighlightBuilder.Summary(s.str("text") ?: "", (s["source_pages"] as? JsonArray).orEmpty().map { (it as JsonPrimitive).content.toInt() })
            }
            val drafts = HighlightBuilder.buildRows(rows, summary)
            val rawFields = input["fields"] as JsonArray
            obj("highlights" to drafts.map { d ->
                val src = d.fieldIndex?.let { rawFields[it].jsonObj() }
                obj(
                    "section" to d.section.raw, "text" to d.text, "position" to d.position, "field_index" to d.fieldIndex,
                    "source_page" to d.sourcePage, "confidence" to (src?.get("confidence") ?: JsonNull)
                )
            })
        }
        "review_status" -> {
            val record = input["record"]!!.jsonObj()
            val rows = (input["rows"] as JsonArray).map { e ->
                val o = e.jsonObj()
                ReviewRules.Row(o.str("field_key")!!, o.str("value_text") ?: "", (o["value_json"] as? JsonObject)?.toString(), o.num("confidence") ?: 0.0, o.str("state")!!, o.bool("from_image") == true)
            }
            val r = ReviewRules.statusOf(
                record.str("record_type"), record.num("type_confidence"), record.str("type_method"), record.str("document_date"),
                record.str("processing_error"), record.str("review_status"), rows, input.bool("pending_split") == true, input.bool("pending_duplicate") == true,
                record.num("ocr_confidence")
            )
            obj("status" to r.status, "reasons" to r.reasons)
        }
        "apply_extraction" -> applyExtraction(input)
        "validate_ai" -> {
            val imagePages = (input["image_pages"] as? JsonArray).orEmpty().map { (it as JsonPrimitive).content.toInt() }.toSet()
            val r = ExtractionValidator.validateAi(pages(input), input["ai_json"]!!, input.str("mode")!!, today(input), order(input), imagePages)
            fun item(i: ExtractionValidator.Item) = obj(
                "key" to i.key, "value_text" to i.valueText, "value_json" to i.valueJson, "method" to i.method,
                "confidence" to i.confidence, "source_page" to i.sourcePage, "evidence" to i.evidence, "from_image" to i.fromImage
            )
            obj(
                "record_type" to r.recordType?.let { obj("record_type" to it.recordType, "confidence" to it.confidence, "method" to it.method) },
                "report_name" to r.reportName?.let(::item),
                "items" to r.items.map(::item),
                "summary" to r.summary?.let { obj("text" to it.text, "source_pages" to it.sourcePages) },
                "dropped" to r.dropped.map { obj("kind" to it.kind, "index" to it.index, "reason" to it.reason) },
                "error" to r.error
            )
        }
        "ai_chunks" -> obj("chunks" to AiChunker.chunks(pages(input), input.str("mode")!!).map { obj("pages" to it.pages, "text" to it.text) })
        "hashing" -> when (input.str("op")) {
            "dhash" -> obj("hex" to NearDuplicate.dHash((input["gray"] as JsonArray).flatMap { row -> (row as JsonArray).map { (it as JsonPrimitive).content.toInt() } }.toIntArray()))
            "hamming" -> obj("distance" to NearDuplicate.hamming(input.str("a")!!, input.str("b")!!))
            "fnv1a64" -> obj("hex" to NearDuplicate.hex(NearDuplicate.fnv1a64(input.str("text")!!.toByteArray(Charsets.UTF_8))))
            "shingles" -> obj("shingles" to NearDuplicate.shingles(input.str("text")!!))
            "minhash" -> obj("signature" to NearDuplicate.minhashSignature(input.str("text")!!))
            "similarity" -> obj("similarity" to NearDuplicate.signatureSimilarity(NearDuplicate.minhashSignature(input.str("a")!!), NearDuplicate.minhashSignature(input.str("b")!!)))
            "near_duplicate" -> {
                val sa = input.str("text_a")?.let { NearDuplicate.minhashSignature(it) } ?: ""
                val sb = input.str("text_b")?.let { NearDuplicate.minhashSignature(it) } ?: ""
                val d = NearDuplicate.nearDuplicate(input.str("phash_a"), input.str("phash_b"), sa, sb)
                obj("candidate" to d.candidate, "reason" to d.reason, "score" to d.score)
            }
            else -> error("unknown hashing op")
        }
        "parse_query" -> {
            val q = RecordQueryParser.parse(input.str("text")!!, today(input), order(input))
            obj(
                "terms" to q.terms, "date_from" to q.dateFrom, "date_to" to q.dateTo, "record_types" to q.recordTypes.map { it.raw },
                "flags" to q.flags.toList(), "doctor" to q.doctor, "facility" to q.facility, "favorites" to q.favorites,
                "needs_review" to q.needsReview, "archived" to q.archived, "source" to q.source,
                "chips" to q.chips.map { obj("kind" to it.kind, "text" to it.label) }, "match" to q.match
            )
        }
        else -> error("unknown function $function")
    }

    private fun toField(o: JsonObject, index: Int): RecordField = RecordField(
        id = o.str("id") ?: "row-$index",
        recordId = "r",
        key = o.str("field_key")!!,
        valueText = o.str("value_text") ?: "",
        valueJson = (o["value_json"] as? JsonObject)?.toString(),
        method = ExtractionMethod.entries.firstOrNull { it.raw == o.str("method") } ?: ExtractionMethod.RULES,
        confidence = o.num("confidence") ?: 0.0,
        state = FieldState.fromRaw(o.str("state")),
        sourcePage = o.num("source_page")?.toInt(),
        evidence = o.str("evidence")
    )

    private fun applyExtraction(input: JsonObject): JsonElement {
        val existing = (input["existing_rows"] as JsonArray).map { it.jsonObj() }
        val items = (input["new_items"] as JsonArray).map { it.jsonObj() }
        val work = existing.map { o ->
            ExtractionWriter.WorkRow(o.str("id")!!, o.str("field_key")!!, o.str("value_text") ?: "", (o["value_json"] as? JsonObject)?.toString(), o.num("confidence") ?: 0.0, o.str("state")!!)
        }.toMutableList()
        val extracted = items.map { o ->
            ExtractedField(
                o.str("key")!!, o.str("value_text") ?: "", (o["value_json"] as? JsonObject)?.toString(),
                ExtractionMethod.entries.firstOrNull { it.raw == o.str("method") } ?: ExtractionMethod.RULES,
                o.num("confidence") ?: 0.0, o.num("source_page")?.toInt(), o.str("source_bbox"), o.str("evidence")
            )
        }
        val actions = ExtractionWriter.apply(work, extracted)
        // Replay the decisions on the JSON rows exactly like the reference mutates its dicts.
        val rows = existing.map { LinkedHashMap<String, JsonElement>(it) }.toMutableList()
        val ids = work.map { it.id }
        for ((i, a) in actions.withIndex()) {
            val it = items[i]
            when (a.action) {
                "update" -> {
                    val row = rows[ids.indexOf(a.id)]
                    for (k in listOf("value_text", "value_json", "method", "evidence", "source_page", "source_bbox", "confidence")) row[k] = it[k] ?: JsonNull
                }
                "insert" -> rows += linkedMapOf(
                    "id" to JsonPrimitive(a.id), "field_key" to (it["key"] ?: JsonNull), "value_text" to (it["value_text"] ?: JsonNull),
                    "value_json" to (it["value_json"] ?: JsonNull), "method" to (it["method"] ?: JsonNull), "confidence" to (it["confidence"] ?: JsonNull),
                    "state" to JsonPrimitive("suggested"), "source_page" to (it["source_page"] ?: JsonNull), "source_bbox" to (it["source_bbox"] ?: JsonNull),
                    "evidence" to (it["evidence"] ?: JsonNull)
                )
            }
        }
        val rowObjs = rows.map { JsonObject(it) }
        val conflicts = ExtractionWriter.conflictSlots(rowObjs.map { ExtractionWriter.SlotRow(it.str("field_key")!!, it.str("value_text") ?: "", (it["value_json"] as? JsonObject)?.toString(), it.str("state")!!) })
        val record = (input["record"] as? JsonObject)?.let { r ->
            val state = ExtractionWriter.RecordState(
                title = r.str("title") ?: "", titleIsDerived = r.bool("title_is_derived") == true, recordType = r.str("record_type") ?: "other",
                category = r.str("category") ?: "other", typeConfidence = r.num("type_confidence"), typeMethod = r.str("type_method"),
                documentDate = r.str("document_date"), documentDatePrecision = r.str("document_date_precision"),
                documentDateMethod = r.str("document_date_method"), sortDate = r.str("sort_date")
            )
            val classification = (input["classification"] as? JsonArray)?.map { c ->
                val o = c.jsonObj()
                ExtractionWriter.ClassificationInput(o.str("record_type")!!, o.num("confidence") ?: 0.0, o.str("method")!!)
            }
            val out = ExtractionWriter.deriveRecord(state, rowObjs.mapIndexed { i, o -> toField(o, i) }, classification)
            obj(
                "title" to out.title, "title_is_derived" to out.titleIsDerived, "record_type" to out.recordType, "category" to out.category,
                "type_confidence" to out.typeConfidence, "type_method" to out.typeMethod, "document_date" to out.documentDate,
                "document_date_precision" to out.documentDatePrecision, "document_date_method" to out.documentDateMethod, "sort_date" to out.sortDate
            )
        }
        return obj("rows" to rowObjs, "actions" to actions.map { obj("action" to it.action, "id" to it.id) }, "conflicts" to conflicts, "record" to record)
    }

    // -- JSON helpers -----------------------------------------------------------------------------

    fun obj(vararg pairs: Pair<String, Any?>): JsonObject = RuleItem.obj(*pairs)

    private fun JsonElement.jsonObj(): JsonObject = this as JsonObject
    private fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
    private fun JsonObject.num(k: String): Double? = (this[k] as? JsonPrimitive)?.takeIf { it !is JsonNull && !it.isString }?.doubleOrNull
    private fun JsonObject.bool(k: String): Boolean? = (this[k] as? JsonPrimitive)?.takeIf { it !is JsonNull && !it.isString }?.booleanOrNull

    /** Null when equal; otherwise the path and a short reason. */
    fun diff(expected: JsonElement, actual: JsonElement, path: String): String? {
        when (expected) {
            is JsonNull -> return if (actual is JsonNull) null else "$path: expected null, got $actual"
            is JsonObject -> {
                if (actual !is JsonObject) return "$path: expected object, got $actual"
                if (expected.keys != actual.keys) return "$path: keys ${expected.keys} vs ${actual.keys}"
                for (k in expected.keys) diff(expected[k]!!, actual[k]!!, "$path.$k")?.let { return it }
                return null
            }
            is JsonArray -> {
                if (actual !is JsonArray) return "$path: expected array, got $actual"
                if (expected.size != actual.size) return "$path: size ${expected.size} vs ${actual.size}"
                for (i in expected.indices) diff(expected[i], actual[i], "$path[$i]")?.let { return it }
                return null
            }
            is JsonPrimitive -> {
                if (actual !is JsonPrimitive || actual is JsonNull) return "$path: expected $expected, got $actual"
                if (expected.isString || actual.isString) return if (expected.isString == actual.isString && expected.content == actual.content) null else "$path: expected $expected, got $actual"
                val eb = expected.booleanOrNull
                val ab = actual.booleanOrNull
                if (eb != null || ab != null) return if (eb == ab) null else "$path: expected $expected, got $actual"
                val en = expected.doubleOrNull
                val an = actual.doubleOrNull
                return if (en != null && an != null && en == an) null else "$path: expected $expected, got $actual"
            }
        }
    }
}
