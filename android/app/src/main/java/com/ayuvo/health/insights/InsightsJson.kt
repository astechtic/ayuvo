package com.ayuvo.health.insights

import com.ayuvo.health.medications.logic.MedicationJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import java.util.Locale
import kotlin.math.floor

/**
 * Engine results in the contract's JSON shape (the keys `insights_reference.py` returns). The
 * vector runner compares these objects, the AI payload is built from them and Coach reads them, so
 * there is one serialisation for all three.
 */
object InsightsJson {
    private fun obj(vararg pairs: Pair<String, Any?>): JsonObject = MedicationJson.obj(*pairs)

    fun collecting(c: Collecting?): JsonElement = c?.let { obj("have" to it.have, "need" to it.need) } ?: JsonNull

    fun baseline(b: BaselineResult): JsonObject = obj(
        "status" to b.status, "n" to b.n, "needed" to b.needed, "coverage" to b.coverage, "mean" to b.mean, "sd" to b.sd,
        "sd_floored" to b.sdFloored, "low" to b.low, "high" to b.high, "recent" to b.recent, "delta" to b.delta, "pct" to b.pct,
        "z" to b.z, "confidence" to b.confidence
    )

    fun trend(t: TrendResult): JsonObject =
        obj("status" to t.status, "n" to t.n, "needed" to t.needed, "slope_pct_per_week" to t.slopePctPerWeek, "direction" to t.direction)

    fun overnight(v: OvernightValue): JsonObject = obj("value" to v.value, "n" to v.n, "fallback" to v.fallback)

    fun trainingLoad(t: TrainingLoadResult): JsonObject = obj(
        "day" to t.day.toString(), "load" to t.load, "minutes" to t.minutes, "sessions" to t.sessions, "mean_28d" to t.mean28d,
        "ratio" to t.ratio, "category" to t.category, "label" to t.label
    )

    private fun signal(s: RecoverySignal) = obj("id" to s.id, "impact" to s.impact, "text" to s.text)

    fun recovery(r: RecoveryResult): JsonObject = obj(
        "day" to r.day.toString(), "status" to r.status, "score" to r.score, "label" to r.label, "label_text" to r.labelText,
        "recommendation" to r.recommendation, "confidence" to r.confidence, "collecting" to collecting(r.collecting),
        "components" to r.components.map {
            obj(
                "id" to it.id, "weight" to it.weight, "available" to it.available, "value" to it.value, "baseline" to it.baseline,
                "delta" to it.delta, "pct" to it.pct, "z" to it.z, "subscore" to it.subscore, "impact" to it.impact,
                "baseline_n" to it.baselineN, "baseline_confidence" to it.baselineConfidence, "fallback" to it.fallback,
                "consistency_deviation_min" to it.consistencyDeviationMin, "consistency_subscore" to it.consistencySubscore
            )
        },
        "positives" to r.positives.map(::signal),
        "negatives" to r.negatives.map(::signal),
        "load" to r.load?.let {
            obj(
                "day" to it.day.toString(), "load" to it.load, "mean_28d" to it.mean28d, "ratio" to it.ratio,
                "category" to it.category, "label" to it.label, "modifier" to it.modifier
            )
        }
    )

    fun healthAge(h: HealthAgeResult): JsonObject = obj(
        "as_of" to h.asOf.toString(), "status" to h.status, "actual_age" to h.actualAge, "health_age" to h.healthAge,
        "difference" to h.difference, "confidence" to h.confidence, "collecting" to collecting(h.collecting),
        "markers_available" to h.markersAvailable, "markers_needed" to h.markersNeeded,
        "markers" to h.markers.map {
            obj(
                "id" to it.id, "method" to it.method, "available" to it.available, "value" to it.value,
                "secondary_value" to it.secondaryValue, "basis" to it.basis, "days" to it.days, "needed_days" to it.neededDays,
                "equivalent_age" to it.equivalentAge, "offset_years" to it.offsetYears, "weight" to it.weight,
                "contribution_years" to it.contributionYears
            )
        }
    )

    fun pace(p: HealthAgePace): JsonObject = obj(
        "status" to p.status, "have" to p.have, "needed" to p.needed, "pace" to p.pace, "direction" to p.direction,
        "points" to p.points.map { obj("day" to it.day.toString(), "health_age" to it.healthAge, "difference" to it.difference) }
    )

    private fun item(i: ReviewItem) = obj("rule_id" to i.ruleId, "params" to i.params, "text" to i.text)

    fun review(v: DailyReviewResult): JsonObject = obj(
        "day" to v.day.toString(), "day_score" to v.dayScore,
        "areas" to v.areas.map { obj("id" to it.id, "included" to it.included, "score" to it.score, "weight" to it.weight, "detail" to it.detail) },
        "not_logged" to v.notLogged.map(::item),
        "went_well" to v.wentWell.map(::item),
        "needs_attention" to v.needsAttention.map(::item),
        "improve" to v.improve.map(::item),
        "reduce" to v.reduce.map(::item)
    )

    fun pattern(p: PatternResult): JsonObject = obj(
        "id" to p.id, "status" to p.status, "n_exposed" to p.nExposed, "n_unexposed" to p.nUnexposed, "needed" to p.needed,
        "mean_exposed" to p.meanExposed, "mean_unexposed" to p.meanUnexposed, "diff" to p.diff, "t" to p.t, "d" to p.d,
        "surfaced" to p.surfaced, "text" to p.text, "review_category" to p.reviewCategory
    )

    fun patterns(list: List<PatternResult>): JsonArray = JsonArray(list.map(::pattern))

    /** The `summary` object `ai_payload` reads; absent sections are left out. */
    fun summary(
        recovery: RecoveryResult?,
        healthAge: HealthAgeResult?,
        pace: HealthAgePace?,
        review: DailyReviewResult?,
        patterns: List<PatternResult>?
    ): JsonObject {
        val m = LinkedHashMap<String, JsonElement>()
        recovery?.let { m["recovery"] = recovery(it) }
        healthAge?.let { m["health_age"] = healthAge(it) }
        pace?.let { m["health_age_pace"] = pace(it) }
        review?.let { m["daily_review"] = review(it) }
        patterns?.let { m["patterns"] = patterns(it) }
        return JsonObject(m)
    }

    // -- Canonical JSON (docs/insights.md §2.2, ai_explain.md) ------------------------------------

    /** Integral → integer digits; otherwise at most 2 decimals with trailing zeros removed. */
    fun canonicalNumber(v: Double): String {
        val x = InsightsMath.roundTo(v, 2)
        if (x == floor(x)) return java.math.BigDecimal(x).toBigInteger().toString()
        return String.format(Locale.ROOT, "%.2f", x).trimEnd('0').trimEnd('.')
    }

    fun canonicalString(s: String): String {
        val sb = StringBuilder("\"")
        for (ch in s) {
            when {
                ch == '"' -> sb.append("\\\"")
                ch == '\\' -> sb.append("\\\\")
                ch == '\n' -> sb.append("\\n")
                ch == '\r' -> sb.append("\\r")
                ch == '\t' -> sb.append("\\t")
                ch.code < 0x20 -> sb.append(String.format(Locale.ROOT, "\\u%04x", ch.code))
                else -> sb.append(ch)
            }
        }
        return sb.append('"').toString()
    }

    /** Compact JSON with keys sorted by code point, the bytes both platforms send in the prompt. */
    fun canonical(e: JsonElement): String = when (e) {
        is JsonNull -> "null"
        is JsonPrimitive -> when {
            e.isString -> canonicalString(e.content)
            e.booleanOrNull != null -> e.booleanOrNull.toString()
            else -> canonicalNumber(e.doubleOrNull ?: error("not a number: $e"))
        }
        is JsonArray -> e.joinToString(",", "[", "]") { canonical(it) }
        is JsonObject -> e.keys.sortedWith(CODE_POINT_ORDER).joinToString(",", "{", "}") { canonicalString(it) + ":" + canonical(e.getValue(it)) }
    }

    /** Python's `sorted()` on str: by Unicode code point, not UTF-16 unit. */
    private val CODE_POINT_ORDER = Comparator<String> { a, b ->
        val ai = a.codePoints().toArray()
        val bi = b.codePoints().toArray()
        for (i in 0 until minOf(ai.size, bi.size)) if (ai[i] != bi[i]) return@Comparator ai[i].compareTo(bi[i])
        ai.size.compareTo(bi.size)
    }
}
