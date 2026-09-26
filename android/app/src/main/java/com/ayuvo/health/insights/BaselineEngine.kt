package com.ayuvo.health.insights

import com.ayuvo.health.insights.InsightsMath.mean
import com.ayuvo.health.insights.InsightsMath.roundTo
import com.ayuvo.health.insights.InsightsMath.sampleSd
import com.ayuvo.health.insights.InsightsMath.values
import java.time.LocalDate

/**
 * Personal baselines (docs/insights.md, "Your personal baselines"): the prior `window_days` (the day
 * itself excluded), mean ± max(SD, SD floor), today's deviation and the 28-day trend. Pure; ported
 * line for line from `_baseline_raw`, `baseline`, `trend` and `overnight_value`.
 */
object BaselineEngine {

    /** Unrounded baseline, the form the other engines build on. */
    data class Raw(
        val status: String,
        val n: Int,
        val needed: Int,
        val coverage: Double,
        val recent: Double?,
        val mean: Double?,
        val sd: Double?,
        val sdUsed: Double?,
        val low: Double?,
        val high: Double?,
        val delta: Double?,
        val pct: Double?,
        val z: Double?,
        val confidence: String
    ) {
        val ok: Boolean get() = status == "ok"
    }

    fun raw(series: Map<LocalDate, Double>, day: LocalDate, m: InsightsConfig.Metric): Raw {
        val window = m.windowDays
        val vals = values(series, day, -window, -1).map { it.second }
        val n = vals.size
        val recent = if (m.recent == "mean7") {
            val last7 = values(series, day, -6, 0).map { it.second }
            if (last7.isEmpty()) null else mean(last7)
        } else {
            series[day]
        }
        val coverage = n / window.toDouble()
        if (n < m.minPoints) {
            return Raw("insufficient", n, m.minPoints, coverage, recent, null, null, null, null, null, null, null, null, "low")
        }
        val mu = mean(vals)
        val sd = sampleSd(vals, mu)
        val used = maxOf(sd, m.sdFloor)
        val confidence = if (coverage >= m.highConfidenceCoverage && n >= m.highConfidenceMinN) "high" else "medium"
        val delta = recent?.let { it - mu }
        val pct = recent?.let { if (mu == 0.0) null else (it - mu) / mu * 100.0 }
        val z = recent?.let { (it - mu) / used }
        return Raw("ok", n, m.minPoints, coverage, recent, mu, sd, used, mu - used, mu + used, delta, pct, z, confidence)
    }

    fun baseline(series: Map<LocalDate, Double>, day: LocalDate, m: InsightsConfig.Metric): BaselineResult {
        val b = raw(series, day, m)
        return BaselineResult(
            status = b.status, n = b.n, needed = b.needed, coverage = roundTo(b.coverage, 2),
            mean = roundTo(b.mean, 2), sd = roundTo(b.sd, 2), sdFloored = b.sd != null && b.sd < m.sdFloor,
            low = roundTo(b.low, 2), high = roundTo(b.high, 2), recent = roundTo(b.recent, 2),
            delta = roundTo(b.delta, 2), pct = roundTo(b.pct, 1), z = roundTo(b.z, 2), confidence = b.confidence
        )
    }

    /** Least-squares slope over the last `trend_days` (the day included) as % of the baseline mean per week. */
    fun trend(series: Map<LocalDate, Double>, day: LocalDate, m: InsightsConfig.Metric): TrendResult {
        val b = raw(series, day, m)
        val pts = values(series, day, -(m.trendDays - 1), 0)
        val insufficient = TrendResult("insufficient", pts.size, m.trendMinPoints, null, null)
        if (!b.ok || pts.size < m.trendMinPoints || b.mean == 0.0) return insufficient
        val slope = InsightsMath.olsSlope(pts.map { it.first.toDouble() to it.second }) ?: return insufficient
        val pct = slope * 7.0 / b.mean!! * 100.0
        val direction = when {
            kotlin.math.abs(pct) < m.trendStablePctPerWeek -> "stable"
            m.direction == "band" -> "changing"
            (pct > 0) == (m.direction == "higher_better") -> "improving"
            else -> "declining"
        }
        return TrendResult("ok", pts.size, m.trendMinPoints, roundTo(pct, 2), direction)
    }

    /**
     * Mean of the samples inside the night window (both ends inclusive). No night, or no sample in
     * it, gives the daily-rollup [fallbackValue] (possibly null) flagged as a fallback.
     */
    fun overnightValue(samples: List<Pair<Long, Double>>, night: SleepInput?, fallbackValue: Double?): OvernightValue {
        if (night != null) {
            val inside = samples.filter { it.first >= night.startMs && it.first <= night.endMs }.map { it.second }
            if (inside.isNotEmpty()) return OvernightValue(roundTo(mean(inside), 2), inside.size, false)
        }
        return OvernightValue(fallbackValue?.let { roundTo(it, 2) }, 0, true)
    }

    // -- Sleep helpers -----------------------------------------------------------------------------

    /** Nights long enough to count; shorter ones are incomplete recordings. */
    fun validNights(inputs: InsightsInputs, cfg: InsightsConfig): Map<LocalDate, SleepInput> =
        inputs.sleep.filterValues { it.asleepMin != null && it.asleepMin >= cfg.minNightMinutes }

    fun sleepSeries(inputs: InsightsInputs, cfg: InsightsConfig): Map<LocalDate, Double> =
        validNights(inputs, cfg).mapValues { it.value.asleepMin!! }
}
