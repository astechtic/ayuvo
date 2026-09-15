package com.ayuvo.health.records.processing

import com.ayuvo.health.records.model.FieldState
import com.ayuvo.health.records.model.HighlightSection
import com.ayuvo.health.records.model.RecordField
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlin.math.abs

/** §15 deterministic highlights, ported from the reference `build_highlights`. */
object HighlightBuilder {

    data class HighlightDraft(
        val section: HighlightSection,
        val text: String,
        val fieldId: String?,
        val sourcePage: Int?,
        val position: Int,
        val confidence: Double,
        /** Index of the source row in the input list (null for the summary). */
        val fieldIndex: Int? = null
    )

    /** Reference field shape. */
    data class Row(val key: String, val valueText: String, val valueJson: JsonObject?, val confidence: Double?, val sourcePage: Int?, val state: String?, val id: String? = null)

    data class Summary(val text: String, val sourcePages: List<Int>)

    private val SUFFIX = mapOf(
        "low" to "below the report reference range", "high" to "above the report reference range",
        "critical_low" to "marked critical on the report", "critical_high" to "marked critical on the report",
        "abnormal" to "marked abnormal on the report"
    )

    private fun str(o: JsonObject?, k: String): String? = (o?.get(k) as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
    private fun num(o: JsonObject?, k: String): Double? = (o?.get(k) as? JsonPrimitive)?.takeIf { it !is JsonNull && !it.isString }?.doubleOrNull
    private fun truthy(s: String?): Boolean = !s.isNullOrEmpty()

    private fun distance(vj: JsonObject?): Double {
        val v = num(vj, "value_num") ?: return 0.0
        val lo = num(vj, "ref_low")
        val hi = num(vj, "ref_high")
        val f = str(vj, "flag")
        if ((f == "low" || f == "critical_low") && lo != null) return if (lo != 0.0) (lo - v) / abs(lo) else lo - v
        if ((f == "high" || f == "critical_high") && hi != null) return if (hi != 0.0) (v - hi) / abs(hi) else v - hi
        return 0.0
    }

    fun buildRows(fields: List<Row>, summary: Summary? = null): List<HighlightDraft> {
        data class Imp(val rank: Int, val negDist: Double, val idx: Int, val text: String, val row: Row)
        val important = mutableListOf<Imp>()
        val meds = mutableListOf<Triple<Int, String, Row>>()
        val recs = mutableListOf<Triple<Int, String, Row>>()
        for ((idx, fd) in fields.withIndex()) {
            if (fd.state == "rejected") continue
            val vj = fd.valueJson
            val flag = str(vj, "flag")
            if (fd.key == "test_result" && flag in SUFFIX) {
                val name = str(vj, "name").takeIf(::truthy) ?: fd.valueText
                var text = "$name: ${str(vj, "value") ?: "None"}"
                str(vj, "unit")?.takeIf(::truthy)?.let { text += " $it" }
                text += " — " + SUFFIX.getValue(flag!!)
                val rank = if (flag.startsWith("critical")) 0 else if (flag == "low" || flag == "high") 1 else 2
                important += Imp(rank, -distance(vj), idx, text, fd)
            } else if (fd.key == "medication") {
                var text = str(vj, "name").takeIf(::truthy) ?: fd.valueText
                str(vj, "strength")?.takeIf(::truthy)?.let { text += " $it" }
                for (k in listOf("frequency", "duration")) str(vj, k)?.takeIf(::truthy)?.let { text += " · $it" }
                meds += Triple(idx, text, fd)
            } else if (fd.key == "recommendation") {
                recs += Triple(idx, fd.valueText, fd)
            }
        }
        important.sortWith(compareBy<Imp>({ it.rank }, { it.negDist }, { it.idx }))
        val out = mutableListOf<HighlightDraft>()
        fun emit(section: HighlightSection, rows: List<Triple<Int, String, Row>>, limit: Int) {
            val seen = HashSet<String>()
            var pos = 0
            for ((idx, text, fd) in rows) {
                if (text in seen || pos >= limit) continue
                seen += text
                out += HighlightDraft(section, text, fd.id, fd.sourcePage, pos, fd.confidence ?: 0.0, idx)
                pos++
            }
        }
        emit(HighlightSection.IMPORTANT, important.map { Triple(it.idx, it.text, it.row) }, 8)
        emit(HighlightSection.MEDICATIONS, meds, 10)
        emit(HighlightSection.RECOMMENDATIONS, recs, 5)
        if (summary != null && summary.text.isNotEmpty()) {
            val text = if (summary.text.length > 400) summary.text.substring(0, 399) + "…" else summary.text
            out += HighlightDraft(HighlightSection.SUMMARY, text, null, summary.sourcePages.firstOrNull(), 0, 0.0, null)
        }
        return out
    }

    fun build(fields: List<RecordField>): List<HighlightDraft> = buildRows(fields.map {
        Row(it.key, it.valueText, RecordJson.parseObject(it.valueJson), it.confidence, it.sourcePage, it.state.raw, it.id)
    }).filter { it.section != HighlightSection.SUMMARY }

    @Suppress("unused")
    private val stateRejected = FieldState.REJECTED
}
