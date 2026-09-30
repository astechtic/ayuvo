package com.ayuvo.health.data.derived

import com.ayuvo.health.data.derived.DerivedMath.roundTo
import com.ayuvo.health.medications.logic.MedicationJson
import kotlinx.serialization.json.JsonObject
import java.time.LocalDate
import kotlin.math.abs

data class BodyTrendInput(
    /** Last weigh-in per day, kg. */
    val weights: Map<LocalDate, Double>,
    val day: LocalDate,
    val heightM: Double?,
    val goalKg: Double?,
    /** "who" or "asian"; null → "who". */
    val scheme: String?
)

data class BodyTrendResult(
    val trendKg: Double? = null,
    val rateKgWeek: Double? = null,
    val bmi: Double? = null,
    val bmiCategoryIndex: Int? = null,
    val healthyLowKg: Double? = null,
    val healthyHighKg: Double? = null,
    val etaWeeks: Double? = null
) {
    fun toJson(): JsonObject = MedicationJson.obj(
        "trend_kg" to trendKg, "rate_kg_week" to rateKgWeek, "bmi" to bmi, "bmi_category_index" to bmiCategoryIndex,
        "healthy_low_kg" to healthyLowKg, "healthy_high_kg" to healthyHighKg, "eta_weeks" to etaWeeks
    )
}

/** Latest height per source, metres. */
data class HeightReading(val valueM: Double, val source: String?)

data class HeightConflictInput(val heights: List<HeightReading>)

data class HeightConflictResult(val conflict: Boolean, val values: List<Double>) {
    fun toJson(): JsonObject = MedicationJson.obj("conflict" to conflict, "values" to values)
}

/** Body derivations: ported from `body_trend` and `height_conflict`. */
object BodyDerivation {

    fun bodyTrend(inp: BodyTrendInput, cfg: DerivedConfig): BodyTrendResult {
        val th = cfg.thresholds
        val w = inp.weights
        val days = w.keys.filter { it <= inp.day }.sorted()
        val scheme = inp.scheme?.takeIf { it.isNotEmpty() } ?: "who"
        val h = inp.heightM?.takeIf { it != 0.0 }
        var healthyLow: Double? = null
        var healthyHigh: Double? = null
        if (h != null) {
            val (lo, hi) = th.healthyBmi.getValue(scheme)
            healthyLow = roundTo(lo * h * h, 1)
            healthyHigh = roundTo(hi * h * h, 1)
        }
        if (days.isEmpty()) return BodyTrendResult(healthyLowKg = healthyLow, healthyHighKg = healthyHigh)

        val trend = HashMap<LocalDate, Double>()
        var cur: Double? = null
        var d = days[0]
        while (d <= inp.day) {
            w[d]?.let { x -> cur = cur?.let { it + th.ewmaAlpha * (x - it) } ?: x }
            trend[d] = cur!!
            d = DerivedMath.addDays(d, 1)
        }
        val t = trend.getValue(inp.day)
        var rateOut: Double? = null
        var eta: Double? = null
        val start = DerivedMath.addDays(inp.day, -th.rateWindowDays)
        val recent = days.count { it > start }
        val startTrend = trend[start]
        if (startTrend != null && recent >= th.rateMinWeighins) {
            val rate = (t - startTrend) * 7.0 / th.rateWindowDays
            rateOut = roundTo(rate, 2)
            val g = inp.goalKg
            if (g != null && abs(rate) >= th.etaMinRate && (g - t) * rate > 0) eta = roundTo((g - t) / rate, 1)
        }
        var bmiOut: Double? = null
        var cat: Int? = null
        if (h != null) {
            val bmi = t / (h * h)
            var c = 0
            for (cut in th.bmi.getValue(scheme)) if (bmi >= cut) c += 1
            bmiOut = roundTo(bmi, 1)
            cat = c
        }
        return BodyTrendResult(
            trendKg = roundTo(t, 2), rateKgWeek = rateOut, bmi = bmiOut, bmiCategoryIndex = cat,
            healthyLowKg = healthyLow, healthyHighKg = healthyHigh, etaWeeks = eta
        )
    }

    fun heightConflict(inp: HeightConflictInput, cfg: DerivedConfig): HeightConflictResult {
        val vals = inp.heights.map { roundTo(it.valueM, 2) }.toSortedSet().toList()
        return HeightConflictResult(vals.size >= 2 && vals.last() - vals.first() > cfg.thresholds.heightConflictM, vals)
    }
}
