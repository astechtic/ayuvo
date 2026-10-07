package com.ayuvo.health.data.analytics.engine

import com.ayuvo.health.data.analytics.engine.AnalyticsMath.addDays
import com.ayuvo.health.data.analytics.engine.AnalyticsMath.list
import com.ayuvo.health.data.analytics.engine.AnalyticsMath.maps
import com.ayuvo.health.data.analytics.engine.AnalyticsMath.mad
import com.ayuvo.health.data.analytics.engine.AnalyticsMath.median
import com.ayuvo.health.data.analytics.engine.AnalyticsMath.normalCdf
import com.ayuvo.health.data.analytics.engine.AnalyticsMath.obj
import com.ayuvo.health.data.analytics.engine.AnalyticsMath.percentile
import com.ayuvo.health.data.analytics.engine.AnalyticsMath.roundTo
import com.ayuvo.health.data.analytics.engine.AnalyticsMath.str
import com.ayuvo.health.data.analytics.engine.AnalyticsMath.truthy

/**
 * Entry point mirroring `analytics_reference.FUNCTIONS` / `run_case` (docs/health-analytics.md). Inputs and outputs
 * are plain JSON maps ([AnalyticsMath.plain] / [AnalyticsMath.toJson]); the vector encodings
 * (`{"start", "values"}` series, `{"start", "nights"}` nights) are decoded here exactly as the reference does.
 */
object AnalyticsEngine {

    val FUNCTIONS = listOf(
        "source_select", "unit_convert", "input_hash", "robust_stats", "baseline", "baselines", "trend", "hrv_rr",
        "hrv_rr_day", "hrv_status", "sleep_need", "sleep_status", "load", "hrr", "recovery", "anomaly", "correlation",
        "response", "energy", "vo2max_trend", "met_intensity", "forecast", "evidence"
    )

    // -- vector encodings ----------------------------------------------------------------------------

    @Suppress("UNCHECKED_CAST")
    fun decodeSeries(x: Any?): Any? {
        val m = x as? Map<String, Any?> ?: return x
        if (m.keys != setOf("start", "values")) return x
        val start = m["start"] as String
        val out = LinkedHashMap<String, Any?>()
        (m["values"] as List<Any?>).forEachIndexed { i, v -> if (v != null) out[addDays(start, i)] = v }
        return out
    }

    @Suppress("UNCHECKED_CAST")
    fun decodeNights(x: Any?): Any? {
        val m = x as? Map<String, Any?> ?: return x
        if (m.keys != setOf("start", "nights")) return x
        val start = m["start"] as String
        val out = LinkedHashMap<String, Any?>()
        (m["nights"] as List<Any?>).forEachIndexed { i, n ->
            val a = n as? List<Any?> ?: return@forEachIndexed
            out[addDays(start, i)] = linkedMapOf(
                "asleep_min" to a[0], "in_bed_min" to a[1], "efficiency" to a[2], "midpoint_clock" to a[3],
                "bedtime_clock" to a[4], "wake_clock" to a[5], "waso_min" to null, "nap_min" to null
            )
        }
        return out
    }

    @Suppress("UNCHECKED_CAST")
    fun decodeInputs(inp: JMap): JMap {
        val out = LinkedHashMap(inp)
        out["series"] = ((inp["series"] as? Map<String, Any?>) ?: emptyMap()).mapValues { decodeSeries(it.value) }
        if (inp.containsKey("contexts")) out["contexts"] = (inp["contexts"] as Map<String, Any?>).mapValues { decodeSeries(it.value) }
        if (inp.containsKey("sources")) out["sources"] = (inp["sources"] as Map<String, Any?>).mapValues { decodeSeries(it.value) }
        if (inp.containsKey("nights")) out["nights"] = decodeNights(inp["nights"])
        return out
    }

    private fun withDecoded(inp: JMap, vararg keys: String): JMap {
        val q = LinkedHashMap(inp)
        for (k in keys) if (inp.containsKey(k)) q[k] = decodeSeries(inp[k])
        return q
    }

    // -- dispatch ------------------------------------------------------------------------------------

    @Suppress("UNCHECKED_CAST")
    fun run(function: String, inp: JMap, cfg: AnalyticsConfig): Any? = when (function) {
        "source_select" -> AnalyticsCore.sourceSelect(inp, cfg.policy)
        "unit_convert" -> AnalyticsCore.unitConvert((inp["value"] as? Number)?.toDouble(), inp.str("from"), inp.str("to"))
        "input_hash" -> AnalyticsCore.inputHash(inp)
        "robust_stats" -> {
            val v = inp.list("values").map { (it as Number).toDouble() }
            val points = inp.list("points").map { p -> (p as List<*>).let { (it[0] as Number).toDouble() to (it[1] as Number).toDouble() } }
            linkedMapOf(
                "median" to roundTo(median(v), 6), "mad" to roundTo(mad(v), 6),
                "p10" to roundTo(percentile(v, 10.0), 6), "p90" to roundTo(percentile(v, 90.0), 6),
                "theil_sen" to if (truthy(inp["points"])) AnalyticsCore.theilSen(points).let { listOf(roundTo(it.slope, 6), roundTo(it.intercept, 6)) } else null,
                "ranks" to AnalyticsStats.ranks(v),
                "normal_cdf" to inp.list("z").map { roundTo(normalCdf((it as Number).toDouble()), 9) },
                "bh" to AnalyticsStats.bhQvalues(inp.list("p").map { (it as Number).toDouble() }).map { roundTo(it, 9) }
            )
        }
        "baseline" -> AnalyticsCore.baseline(withDecoded(inp, "series", "contexts", "sources"), cfg)
        "baselines" -> AnalyticsCore.baselines(withDecoded(inp, "series", "contexts", "sources"), cfg)
        "trend" -> AnalyticsCore.trend(withDecoded(inp, "series", "contexts", "sources"), cfg)
        "hrv_rr" -> AnalyticsHealth.hrvRr(inp, cfg)
        "hrv_rr_day" -> AnalyticsHealth.hrvRrDay(inp, cfg)
        "hrv_status" -> AnalyticsHealth.hrvStatus(withDecoded(inp, "series"), cfg)
        "sleep_need", "sleep_status" -> {
            val q = LinkedHashMap(inp)
            if (inp.containsKey("nights")) q["nights"] = decodeNights(inp["nights"])
            if (inp.containsKey("asleep")) q["asleep"] = decodeSeries(inp["asleep"])
            if (function == "sleep_need") AnalyticsHealth.sleepNeed(q, cfg) else AnalyticsHealth.sleepStatus(q, cfg)
        }
        "load" -> AnalyticsHealth.load(inp, cfg)
        "hrr" -> AnalyticsHealth.hrr(inp, cfg)
        "recovery" -> AnalyticsHealth.recovery(mapOf("inputs" to decodeInputs(inp.obj("inputs")), "day" to inp["day"]), cfg)
        "anomaly" -> AnalyticsHealth.anomaly(mapOf("inputs" to decodeInputs(inp.obj("inputs")), "day" to inp["day"]), cfg)
        "correlation" -> AnalyticsStats.correlation(
            mapOf("as_of" to inp["as_of"], "pairs" to inp.maps("pairs").map { p ->
                mapOf("id" to p["id"], "exposure" to decodeSeries(p["exposure"]), "outcome" to decodeSeries(p["outcome"]))
            }), cfg
        )
        "response" -> AnalyticsStats.response(withDecoded(inp, "exposure", "outcome"), cfg)
        "energy" -> AnalyticsStats.energy(inp, cfg)
        "vo2max_trend" -> AnalyticsStats.vo2maxTrend(
            mapOf("day" to inp["day"], "readings" to (inp.obj("readings")).mapValues { decodeSeries(it.value) }), cfg
        )
        "met_intensity" -> AnalyticsStats.metIntensity(inp, cfg)
        "forecast" -> {
            val inputs = decodeInputs(inp.obj("inputs"))
            val asOf = inp.str("as_of")
            val target = inp.str("target")
            val feats = AnalyticsForecast.forecastFeatures(inputs, inp.str("from"), asOf, cfg)
            val targetSeries = AnalyticsCore.seriesOf((inputs["series"] as Map<String, Any?>)[target])
            val res = LinkedHashMap(AnalyticsForecast.forecast(target, feats, targetSeries, asOf, cfg))
            if (truthy(inp["include_features"])) {
                res["features_sample"] = LinkedHashMap<String, Any?>().also { m -> for (d in feats.keys.sorted().takeLast(2)) m[d] = feats[d] }
            }
            res
        }
        "evidence" -> AnalyticsForecast.evidence(inp, cfg)
        else -> error("unknown function $function")
    }
}
