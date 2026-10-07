package com.ayuvo.health.data.analytics.engine

import com.ayuvo.health.data.analytics.engine.AnalyticsCore.baseline
import com.ayuvo.health.data.analytics.engine.AnalyticsCore.confidence
import com.ayuvo.health.data.analytics.engine.AnalyticsCore.confidenceBand
import com.ayuvo.health.data.analytics.engine.AnalyticsCore.robust
import com.ayuvo.health.data.analytics.engine.AnalyticsCore.seriesOf
import com.ayuvo.health.data.analytics.engine.AnalyticsCore.statusFor
import com.ayuvo.health.data.analytics.engine.AnalyticsCore.stringsOf
import com.ayuvo.health.data.analytics.engine.AnalyticsCore.trend
import com.ayuvo.health.data.analytics.engine.AnalyticsMath.CODE_POINT_ORDER
import com.ayuvo.health.data.analytics.engine.AnalyticsMath.addDays
import com.ayuvo.health.data.analytics.engine.AnalyticsMath.clamp
import com.ayuvo.health.data.analytics.engine.AnalyticsMath.int
import com.ayuvo.health.data.analytics.engine.AnalyticsMath.list
import com.ayuvo.health.data.analytics.engine.AnalyticsMath.ln
import com.ayuvo.health.data.analytics.engine.AnalyticsMath.localDayOf
import com.ayuvo.health.data.analytics.engine.AnalyticsMath.maps
import com.ayuvo.health.data.analytics.engine.AnalyticsMath.mean
import com.ayuvo.health.data.analytics.engine.AnalyticsMath.median
import com.ayuvo.health.data.analytics.engine.AnalyticsMath.num
import com.ayuvo.health.data.analytics.engine.AnalyticsMath.numOrNull
import com.ayuvo.health.data.analytics.engine.AnalyticsMath.obj
import com.ayuvo.health.data.analytics.engine.AnalyticsMath.objOrNull
import com.ayuvo.health.data.analytics.engine.AnalyticsMath.percentile
import com.ayuvo.health.data.analytics.engine.AnalyticsMath.roundInt
import com.ayuvo.health.data.analytics.engine.AnalyticsMath.roundTo
import com.ayuvo.health.data.analytics.engine.AnalyticsMath.sampleSd
import com.ayuvo.health.data.analytics.engine.AnalyticsMath.sqrt
import com.ayuvo.health.data.analytics.engine.AnalyticsMath.str
import com.ayuvo.health.data.analytics.engine.AnalyticsMath.strOrNull
import com.ayuvo.health.data.analytics.engine.AnalyticsMath.truthy
import com.ayuvo.health.data.analytics.engine.AnalyticsMath.windowDays
import kotlin.math.abs

/** HRV, sleep, training load, heart rate recovery, Recovery Indicator v2 and the anomaly state (reference ports). */
object AnalyticsHealth {

    // -- HRV -----------------------------------------------------------------------------------------

    fun hrvRr(inp: JMap, cfg: AnalyticsConfig): JMap {
        val rr = cfg.root.obj("hrv").obj("rr")
        val ibis = inp.list("ibis")
        val out = linkedMapOf<String, Any?>(
            "status" to "NO_DATA", "rmssd" to null, "sdnn" to null, "ln_rmssd" to null, "mean_hr" to null,
            "n_beats" to ibis.size, "n_accepted" to 0, "n_diffs" to 0, "artifact_fraction" to null,
            "classification" to "SCIENTIFIC_DERIVED"
        )
        if (ibis.isEmpty()) return out
        val raw = ibis.map { ((it as List<*>)[0] as Number).toDouble() }
        val gap = ibis.map { truthy((it as List<*>)[1]) }
        val inRange = raw.map { it >= rr.num("min_ms") && it <= rr.num("max_ms") }
        val h = rr.int("median_window") / 2
        val accepted = ArrayList<Boolean>()
        for (k in raw.indices) {
            var ok = inRange[k]
            if (ok) {
                val local = (k - h..k + h).filter { it in raw.indices && inRange[it] }.map { raw[it] }
                val med = median(local)
                if (abs(raw[k] - med) / med > rr.num("max_rel_deviation")) ok = false
            }
            accepted += ok
        }
        val acc = raw.indices.filter { accepted[it] }.map { raw[it] }
        val diffs = (1 until raw.size).filter { accepted[it] && accepted[it - 1] && !gap[it] }.map { raw[it] - raw[it - 1] }
        val rejected = raw.size - acc.size
        out["n_accepted"] = acc.size
        out["n_diffs"] = diffs.size
        out["artifact_fraction"] = roundTo(rejected / raw.size.toDouble(), 3)
        if (rejected / raw.size.toDouble() > rr.num("max_artifact_fraction")) {
            out["status"] = "INVALID_INPUT"
            return out
        }
        if (acc.size < rr.int("min_beats") || diffs.size < 2) {
            out["status"] = "INSUFFICIENT_DATA"
            return out
        }
        var sq = 0.0
        for (d in diffs) sq += d * d
        val rmssd = sqrt(sq / diffs.size)
        val m = mean(acc)
        out["status"] = "VALID"
        out["rmssd"] = roundTo(rmssd, 2)
        out["sdnn"] = roundTo(sampleSd(acc, m), 2)
        out["ln_rmssd"] = if (rmssd > 0) roundTo(ln(rmssd), 3) else null
        out["mean_hr"] = roundTo(60000.0 / m, 1)
        return out
    }

    fun hrvRrDay(inp: JMap, cfg: AnalyticsConfig): JMap {
        val night = inp.objOrNull("night")
        val results = ArrayList<Pair<Boolean, JMap>>()
        for (s in inp.maps("series").sortedBy { it.num("start_ms") }) {
            val r = hrvRr(mapOf("ibis" to s["ibis"]), cfg)
            if (r["status"] == "VALID") {
                val inside = night != null && night.num("start_ms") <= s.num("start_ms") && s.num("start_ms") <= night.num("end_ms")
                results += inside to r
            }
        }
        val out = linkedMapOf<String, Any?>(
            "status" to "NO_DATA", "rmssd" to null, "sdnn" to null, "ln_rmssd" to null, "n_series" to 0, "context" to null,
            "classification" to "SCIENTIFIC_DERIVED"
        )
        if (!truthy(inp["series"])) return out
        if (results.isEmpty()) {
            out["status"] = "INSUFFICIENT_DATA"
            return out
        }
        val overnight = results.filter { it.first }.map { it.second }
        val use = if (overnight.isNotEmpty()) overnight else results.map { it.second }
        val rm = median(use.map { it.num("rmssd") })
        out["status"] = "VALID"
        out["rmssd"] = roundTo(rm, 2)
        out["sdnn"] = roundTo(median(use.map { it.num("sdnn") }), 2)
        out["ln_rmssd"] = if (rm > 0) roundTo(ln(rm), 3) else null
        out["n_series"] = use.size
        out["context"] = if (overnight.isNotEmpty()) "overnight" else "daytime"
        return out
    }

    fun hrvStatus(inp: JMap, cfg: AnalyticsConfig): JMap {
        val hc = cfg.root.obj("hrv")
        val series = seriesOf(inp["series"])
        val day = inp.str("day")
        val b = baseline(
            mapOf("series" to series, "day" to day, "metric" to "hrv", "window" to hc.num("baseline_window_days"),
                "contexts" to inp["contexts"], "sources" to inp["sources"]), cfg
        )
        val t = trend(mapOf("series" to series, "day" to day, "metric" to "hrv"), cfg)
        val lnv = windowDays(day, -(hc.int("stability_days") - 1), 0).mapNotNull { d -> series[d]?.takeIf { it > 0 }?.let { ln(it) } }
        var cv: Double? = null
        var lnMean: Double? = null
        if (lnv.size >= hc.int("stability_min_days")) {
            lnMean = mean(lnv)
            cv = sampleSd(lnv, lnMean) / lnMean * 100.0
        }
        val kind = inp.strOrNull("kind")
        return linkedMapOf(
            "kind" to kind, "baseline" to b, "trend" to t, "ln_mean_7d" to roundTo(lnMean, 3), "cv_ln_7d" to roundTo(cv, 2),
            "stability_days" to lnv.size,
            "classification" to if (kind == "sdnn" || kind == "rmssd") "PROVIDER_DERIVED" else "SCIENTIFIC_DERIVED"
        )
    }

    // -- Sleep ---------------------------------------------------------------------------------------

    /** Nights with asleep_min >= min_night_minutes. */
    @Suppress("UNCHECKED_CAST")
    fun validNights(nights: Any?, cfg: AnalyticsConfig): Map<String, JMap> {
        val floorMin = cfg.root.obj("sleep").num("min_night_minutes")
        val all = (nights as? Map<String, Any?>) ?: return emptyMap()
        val out = LinkedHashMap<String, JMap>()
        for ((d, n) in all) {
            val night = n as? JMap ?: continue
            val a = night.numOrNull("asleep_min") ?: continue
            if (a >= floorMin) out[d] = night
        }
        return out
    }

    fun sleepNeed(inp: JMap, cfg: AnalyticsConfig): JMap {
        val s = cfg.root.obj("sleep")
        val asleep: Map<String, Double> = if (inp.containsKey("asleep")) {
            seriesOf(inp["asleep"]).filterValues { it >= s.num("min_night_minutes") }
        } else validNights(inp["nights"], cfg).mapValues { it.value.num("asleep_min") }
        val vals = windowDays(inp.str("day"), -s.int("need_window_days"), -1).mapNotNull { asleep[it] }
        if (vals.size < s.int("need_min_nights")) {
            return linkedMapOf("need_min" to s.num("need_default_min"), "source" to "default", "n" to vals.size,
                "classification" to "PERSONALIZED_STATISTICAL")
        }
        val c = s.list("need_clamp_min").map { (it as Number).toDouble() }
        return linkedMapOf("need_min" to roundTo(clamp(median(vals), c[0], c[1]), 1), "source" to "personal", "n" to vals.size,
            "classification" to "PERSONALIZED_STATISTICAL")
    }

    fun sleepStatus(inp: JMap, cfg: AnalyticsConfig): JMap {
        val s = cfg.root.obj("sleep")
        val nights = validNights(inp["nights"], cfg)
        val day = inp.str("day")
        val need = sleepNeed(mapOf("nights" to inp["nights"], "day" to day), cfg)
        val needMin = need.num("need_min")
        val last = nights[day]
        val out = linkedMapOf<String, Any?>(
            "status" to "NO_DATA", "day" to day, "asleep_min" to null, "in_bed_min" to null, "efficiency" to null,
            "waso_min" to null, "nap_min" to null, "need_min" to needMin, "need_source" to need["source"],
            "debt_min" to null, "debt_nights" to 0, "bedtime_sd" to null, "wake_sd" to null, "variability_nights" to 0,
            "duration" to null, "efficiency_baseline" to null, "confidence" to 0.0
        )
        val debtDays = windowDays(day, -(s.int("debt_window_days") - 1), 0).filter { it in nights }
        if (debtDays.isNotEmpty()) {
            var debt = 0.0
            for (d in debtDays) debt += maxOf(0.0, needMin - nights.getValue(d).num("asleep_min"))
            out["debt_min"] = roundTo(debt, 0)
            out["debt_nights"] = debtDays.size
        }
        val varDays = windowDays(day, -(s.int("variability_window_days") - 1), 0).filter {
            it in nights && nights.getValue(it)["bedtime_clock"] != null && nights.getValue(it)["wake_clock"] != null
        }
        out["variability_nights"] = varDays.size
        if (varDays.size >= s.int("variability_min_nights")) {
            val beds = varDays.map { nights.getValue(it).num("bedtime_clock") }
            val wakes = varDays.map { nights.getValue(it).num("wake_clock") }
            out["bedtime_sd"] = roundTo(sampleSd(beds, mean(beds)), 1)
            out["wake_sd"] = roundTo(sampleSd(wakes, mean(wakes)), 1)
        }
        val dur = nights.mapValues { it.value.num("asleep_min") }
        val eff = nights.entries.mapNotNull { (d, n) -> n.numOrNull("efficiency")?.let { d to it } }.toMap()
        val w = s.int("baseline_window_days")
        out["duration"] = baseline(mapOf("series" to dur, "day" to day, "metric" to "sleep_duration", "window" to w.toDouble()), cfg)
        out["efficiency_baseline"] = baseline(mapOf("series" to eff, "day" to day, "metric" to "sleep_efficiency", "window" to w.toDouble()), cfg)
        if (last != null) {
            @Suppress("UNCHECKED_CAST")
            val raw = ((inp["nights"] as? Map<String, Any?>)?.get(day) as? JMap) ?: emptyMap()
            out["asleep_min"] = roundTo(last.num("asleep_min"), 0)
            out["in_bed_min"] = roundTo(last.numOrNull("in_bed_min"), 0)
            out["efficiency"] = roundTo(last.numOrNull("efficiency"), 1)
            out["waso_min"] = roundTo(last.numOrNull("waso_min"), 0)
            out["nap_min"] = roundTo(raw.numOrNull("nap_min"), 0)
            val nHist = windowDays(day, -w, -1).count { it in nights }
            val conf = confidence("PROVIDER_DERIVED", nHist / w.toDouble(), nHist, cfg.root.obj("confidence").int("target_n"), "wearable", false, cfg)
            out["confidence"] = roundTo(conf, 2)
            out["status"] = statusFor(conf, cfg)
        }
        return out
    }

    // -- Training load -------------------------------------------------------------------------------

    class Session(var startMs: Long, var endMs: Long, var effort: Double?, var trimp: Double?) {
        var day: String = ""
        var minutes: Double = 0.0
    }

    fun sessions(workouts: List<JMap>, timeZone: String): List<Session> {
        val ws = workouts.filter { it.num("end_ms") > it.num("start_ms") }
            .sortedWith(compareBy<JMap> { it.num("start_ms") }.thenBy { it.num("end_ms") })
        val out = ArrayList<Session>()
        for (w in ws) {
            val start = w.num("start_ms").toLong()
            val end = w.num("end_ms").toLong()
            val effort = w.numOrNull("effort")
            val trimp = w.numOrNull("trimp")
            val last = out.lastOrNull()
            if (last != null && start < last.endMs) {
                last.endMs = maxOf(last.endMs, end)
                if (effort != null) last.effort = if (last.effort == null) effort else maxOf(last.effort!!, effort)
                if (trimp != null) last.trimp = if (last.trimp == null) trimp else last.trimp!! + trimp
            } else {
                out += Session(start, end, effort, trimp)
            }
        }
        for (s in out) {
            s.day = localDayOf(s.startMs, timeZone)
            s.minutes = (s.endMs - s.startMs) / 60000.0
        }
        return out
    }

    class LoadDay {
        var minutes = 0.0
        var rpeLoad = 0.0
        var rpeMinutes = 0.0
        var trimp = 0.0
        var trimpMinutes = 0.0
        fun method(m: String): Double = when (m) {
            "trimp" -> trimp
            "rpe_load" -> rpeLoad
            else -> minutes
        }
    }

    fun loadDays(workouts: List<JMap>, timeZone: String): Map<String, LoadDay> {
        val days = LinkedHashMap<String, LoadDay>()
        for (s in sessions(workouts, timeZone)) {
            val d = days.getOrPut(s.day) { LoadDay() }
            d.minutes += s.minutes
            if (s.effort != null) {
                d.rpeLoad += s.effort!! * s.minutes
                d.rpeMinutes += s.minutes
            }
            if (s.trimp != null) {
                d.trimp += s.trimp!!
                d.trimpMinutes += s.minutes
            }
        }
        return days
    }

    val METHODS = listOf("trimp", "rpe_load", "minutes")

    fun load(inp: JMap, cfg: AnalyticsConfig): JMap {
        val lc = cfg.root.obj("load")
        val day = inp.str("day")
        val methodsOut = linkedMapOf<String, Any?>()
        val out = linkedMapOf<String, Any?>(
            "status" to "NO_DATA", "day" to day, "state" to null, "state_label" to null, "primary_method" to null,
            "history_days" to 0, "methods" to methodsOut, "today" to null, "classification" to "SCIENTIFIC_DERIVED"
        )
        if (!(if (inp.containsKey("tracking")) truthy(inp["tracking"]) else true)) return out
        val days = loadDays(inp.maps("workouts"), inp.strOrNull("time_zone") ?: "UTC")
        val known = days.keys.filter { it <= day }.sorted()
        if (known.isEmpty()) {
            out["status"] = "INSUFFICIENT_HISTORY"
            return out
        }
        val start = maxOf(known[0], addDays(day, -lc.int("max_history_days")))
        val series = METHODS.associateWith { ArrayList<Double>() }
        var d = start
        while (d <= day) {
            val x = days[d]
            for (m in METHODS) series.getValue(m) += x?.method(m) ?: 0.0
            d = addDays(d, 1)
        }
        val n = series.getValue("minutes").size
        out["history_days"] = n
        val la = 2.0 / (lc.num("acute_days") + 1.0)
        val lch = 2.0 / (lc.num("chronic_days") + 1.0)
        val acute = METHODS.associateWith { DoubleArray(n) }
        val chronic = METHODS.associateWith { DoubleArray(n) }
        for (m in METHODS) {
            val seed = series.getValue(m).take(lc.int("chronic_days"))
            var a = mean(seed)
            var c = a
            for ((i, x) in series.getValue(m).withIndex()) {
                a = la * x + (1.0 - la) * a
                c = lch * x + (1.0 - lch) * c
                acute.getValue(m)[i] = a
                chronic.getValue(m)[i] = c
            }
        }
        val stateWindow = lc.int("state_window_days")
        val lo = maxOf(0, n - 1 - stateWindow)
        var mins = 0.0
        var tmins = 0.0
        var rmins = 0.0
        for (wd in windowDays(day, -stateWindow, 0)) {
            val x = days[wd] ?: continue
            mins += x.minutes
            tmins += x.trimpMinutes
            rmins += x.rpeMinutes
        }
        val cov = mapOf(
            "minutes" to if (mins > 0) 1.0 else 0.0, "trimp" to if (mins > 0) tmins / mins else 0.0,
            "rpe_load" to if (mins > 0) rmins / mins else 0.0
        )
        for (m in METHODS) {
            val aL = acute.getValue(m)[n - 1]
            val cL = chronic.getValue(m)[n - 1]
            val ratio = if (cL > 0) aL / cL else null
            methodsOut[m] = linkedMapOf(
                "acute" to roundTo(aL, 1), "chronic" to roundTo(cL, 1), "ratio" to roundTo(ratio, 2),
                "coverage" to roundTo(cov.getValue(m), 2), "today" to roundTo(series.getValue(m)[n - 1], 1)
            )
        }
        val minCov = lc.num("method_min_coverage")
        val primary = when {
            cov.getValue("trimp") >= minCov -> "trimp"
            cov.getValue("rpe_load") >= minCov -> "rpe_load"
            else -> "minutes"
        }
        out["primary_method"] = primary
        @Suppress("UNCHECKED_CAST")
        out["today"] = (methodsOut[primary] as JMap)["today"]
        if (n < lc.int("min_history_days")) {
            out["status"] = "INSUFFICIENT_HISTORY"
            return out
        }
        val histA = ArrayList<Double>()
        val histR = ArrayList<Double>()
        val ap = acute.getValue(primary)
        val cp = chronic.getValue(primary)
        for (i in maxOf(lo, lc.int("min_history_days") - 1) until n - lc.int("acute_days")) {
            histA += ap[i]
            if (cp[i] > 0) histR += ap[i] / cp[i]
        }
        if (histA.size < lc.int("state_min_days") || histR.size < lc.int("state_min_days")) {
            out["status"] = "INSUFFICIENT_HISTORY"
            return out
        }
        val p = lc.obj("percentiles")
        val gd = lc.obj("guards")
        val aNow = ap[n - 1]
        val rNow = if (cp[n - 1] > 0) aNow / cp[n - 1] else null
        val aMed = median(histA)
        val state = when {
            rNow != null && rNow >= gd.num("spike_min_ratio") && rNow > percentile(histR, p.num("spike_ratio_above")) -> "LOAD_SPIKE"
            aNow > percentile(histA, p.num("high_above")) && aNow >= gd.num("high_min_vs_median") * aMed -> "LOAD_HIGH"
            rNow != null && rNow >= gd.num("increasing_min_ratio") && rNow > percentile(histR, p.num("increasing_ratio_above")) -> "LOAD_INCREASING"
            aNow < percentile(histA, p.num("reduced_below")) && aNow <= gd.num("reduced_max_vs_median") * aMed -> "LOAD_REDUCED"
            else -> "LOAD_STABLE"
        }
        val conf = confidence("SCIENTIFIC_DERIVED", cov.getValue(primary), histA.size, stateWindow, "wearable", false, cfg)
        out["status"] = statusFor(conf, cfg)
        out["state"] = state
        out["state_label"] = lc.obj("states").str(state)
        out["confidence"] = roundTo(conf, 2)
        return out
    }

    // -- Heart rate recovery -------------------------------------------------------------------------

    fun hrr(inp: JMap, cfg: AnalyticsConfig): JMap {
        val hc = cfg.root.obj("hrr")
        val end = inp.num("end_ms")
        val samples = inp.list("samples").map { s -> (s as List<*>).let { (it[0] as Number).toDouble() to (it[1] as Number).toDouble() } }
            .sortedBy { it.first }
        val tol = hc.num("tolerance_s") * 1000
        val before = samples.filter { end - 60000 <= it.first && it.first <= end }.map { it.second }
        val out = linkedMapOf<String, Any?>(
            "status" to "NO_DATA", "peak" to null, "hrr1" to null, "hrr2" to null, "confidence" to 0.0,
            "classification" to "SCIENTIFIC_DERIVED"
        )
        if (samples.isEmpty()) return out
        fun at(offset: Double): Double? {
            val c = samples.filter { abs(it.first - (end + offset)) <= tol }
                .map { Triple(abs(it.first - (end + offset)), it.first, it.second) }
                .sortedWith(compareBy<Triple<Double, Double, Double>> { it.first }.thenBy { it.second }.thenBy { it.third })
            return c.firstOrNull()?.third
        }
        val h1 = at(60000.0)
        val h2 = at(120000.0)
        if (before.isEmpty() || h1 == null) {
            out["status"] = "INSUFFICIENT_DATA"
            return out
        }
        val peak = before.max()
        val gaps = (0 until samples.size - 1).map { samples[it + 1].first - samples[it].first }
        val dense = gaps.isNotEmpty() && gaps.max() <= hc.num("dense_max_gap_s") * 1000
        val conf = if (dense) hc.num("confidence_dense") else hc.num("confidence_sparse")
        out["status"] = statusFor(conf, cfg)
        out["peak"] = roundTo(peak, 0)
        out["hrr1"] = roundTo(peak - h1, 0)
        out["hrr2"] = if (h2 != null) roundTo(peak - h2, 0) else null
        out["confidence"] = conf
        return out
    }

    // -- Recovery Indicator v2 -----------------------------------------------------------------------

    private fun sub(zDir: Double, rc: JMap): Double = clamp(rc.num("subscore_center") + rc.num("subscore_slope") * zDir, 0.0, 100.0)

    private class SleepComponent(val sub: Double?, val parts: Map<String, Double>, val conf: Double, val asleepMin: Double?, val needMin: Double?)

    private fun sleepComponent(inputs: JMap, day: String, rc: JMap, cfg: AnalyticsConfig): SleepComponent {
        val sc = rc.obj("sleep")
        val partsW = rc.maps("components").first { it.str("id") == "sleep" }.obj("parts")
        val nights = validNights(inputs["nights"], cfg)
        val last = nights[day] ?: return SleepComponent(null, emptyMap(), 0.0, null, null)
        val need = sleepNeed(mapOf("nights" to inputs["nights"], "day" to day), cfg)
        val needMin = need.num("need_min")
        val parts = LinkedHashMap<String, Double>()
        val deficit = maxOf(0.0, needMin - last.num("asleep_min"))
        parts["duration"] = clamp(100.0 * (1.0 - deficit / sc.num("duration_zero_deficit_min")), 0.0, 100.0)
        val eff = nights.entries.mapNotNull { (d, n) -> n.numOrNull("efficiency")?.let { d to it } }.toMap()
        if (last["efficiency"] != null) {
            val b = robust(eff, day, rc.int("baseline_window_days"), rc.int("min_points"),
                cfg.root.obj("metrics").obj("sleep_efficiency").num("spread_floor"), cfg)
            if (b.z != null) parts["efficiency"] = sub(b.z, rc)
        }
        if (last["midpoint_clock"] != null) {
            val prior = windowDays(day, -sc.int("regularity_window_days"), -1)
                .filter { it in nights && nights.getValue(it)["midpoint_clock"] != null }
                .map { nights.getValue(it).num("midpoint_clock") }
            if (prior.size >= sc.int("regularity_min_nights")) {
                val dev = abs(last.num("midpoint_clock") - median(prior))
                parts["regularity"] = clamp(100.0 * (1.0 - dev / sc.num("regularity_zero_at_min")), 0.0, 100.0)
            }
        }
        var ws = 0.0
        var acc = 0.0
        for (k in listOf("duration", "efficiency", "regularity")) {
            val v = parts[k] ?: continue
            ws += partsW.num(k)
            acc += partsW.num(k) * v
        }
        val hist = windowDays(day, -rc.int("baseline_window_days"), -1).count { it in nights }
        val conf = cfg.root.obj("classifications").obj("PROVIDER_DERIVED").num("validity_cap") *
            sqrt(clamp(hist / sc.num("history_target_nights"), 0.0, 1.0)) * (if (need["source"] == "personal") 1.0 else 0.8)
        return SleepComponent(acc / ws, parts, conf, last.num("asleep_min"), needMin)
    }

    @Suppress("UNCHECKED_CAST")
    fun recovery(inp: JMap, cfg: AnalyticsConfig): JMap {
        val rc = cfg.root.obj("recovery")
        val inputs = inp.obj("inputs")
        val day = inp.str("day")
        val series = (inputs["series"] as? Map<String, Any?>) ?: emptyMap()
        val contexts = (inputs["contexts"] as? Map<String, Any?>) ?: emptyMap()
        val sources = (inputs["sources"] as? Map<String, Any?>) ?: emptyMap()
        val fallback = inputs.list("overnight_fallback").map { it as String }.toSet()
        val scans = inputs.list("scan_fallback").map { it as String }.toSet()
        val comps = ArrayList<LinkedHashMap<String, Any?>>()
        val warnings = ArrayList<Map<String, Any?>>()
        var totalW = 0.0
        for (c in rc.maps("components")) totalW += c.num("weight")
        var heartHist = 0
        val heartIds = rc.list("heart_components").map { it as String }
        val sNum = HashMap<String, Double?>()  // unrounded subscore / confidence / impact per component id
        val cNum = HashMap<String, Double>()
        for (c in rc.maps("components")) {
            val id = c.str("id")
            val item = linkedMapOf<String, Any?>(
                "id" to id, "weight" to c["weight"], "available" to false, "value" to null, "baseline" to null, "z" to null,
                "subscore" to null, "impact" to null, "confidence" to 0.0, "parts" to null, "unit" to null, "n" to 0,
                "context" to null
            )
            if (c.str("mode") == "sleep") {
                val sc = sleepComponent(inputs, day, rc, cfg)
                item["unit"] = "min"
                if (sc.sub != null) {
                    item["available"] = true
                    item["subscore"] = sc.sub
                    item["confidence"] = sc.conf
                    item["value"] = sc.asleepMin
                    item["baseline"] = sc.needMin
                    item["parts"] = LinkedHashMap(sc.parts.mapValues { roundTo(it.value, 1) })
                    sNum[id] = sc.sub
                    cNum[id] = sc.conf
                }
                comps += item
                continue
            }
            val m = cfg.root.obj("metrics").obj(c.str("metric"))
            item["unit"] = m["unit"]
            val b = robust(
                seriesOf(series[c.str("metric")]), day, rc.int("baseline_window_days"), rc.int("min_points"), m.num("spread_floor"), cfg,
                stringsOf(contexts[c.str("metric")]), null, stringsOf(sources[c.str("metric")])
            )
            item["value"] = b.value
            item["baseline"] = b.median
            item["z"] = b.z
            item["n"] = b.n
            item["context"] = b.context
            if (id in heartIds && b.median != null) heartHist += 1
            if (b.z != null) {
                val z = b.z
                val s = when (c.str("mode")) {
                    "higher" -> sub(z, rc)
                    "lower" -> sub(-z, rc)
                    "band" -> sub(-maxOf(0.0, abs(z) - c.num("tolerance_z")), rc)
                    else -> sub(-maxOf(0.0, -z - c.num("tolerance_z")), rc)
                }
                val conf = confidence(m.str("classification"), b.coverage, b.n, cfg.root.obj("confidence").int("target_n"), b.context, b.sourceChanged, cfg)
                item["available"] = true
                item["subscore"] = s
                item["confidence"] = conf
                sNum[id] = s
                cNum[id] = conf
                if (b.sourceChanged) warnings += linkedMapOf("code" to "source_changed", "metric" to id)
                if (c.str("metric") in fallback) warnings += linkedMapOf("code" to "overnight_fallback", "metric" to id)
                if (c.str("metric") in scans || b.context == "camera") warnings += linkedMapOf("code" to "camera", "metric" to id)
            }
            comps += item
        }
        val by = comps.associateBy { it["id"] as String }
        val nights = validNights(inputs["nights"], cfg)
        val sleepHist = windowDays(day, -rc.int("baseline_window_days"), -1).count { it in nights }
        val drivers = ArrayList<LinkedHashMap<String, Any?>>()
        val out = linkedMapOf<String, Any?>(
            "algorithm_id" to rc["algorithm_id"], "algorithm_version" to rc["algorithm_version"],
            "weights_version" to rc["weights_version"], "config_version" to cfg.root["config_version"], "day" to day,
            "status" to "VALID", "reason" to null, "score" to null, "label" to null, "label_text" to null,
            "recommendation" to null, "confidence" to 0.0, "confidence_band" to "low", "coverage" to null,
            "collecting" to null, "components" to comps, "drivers" to drivers, "positives" to emptyList<String>(),
            "negatives" to emptyList<String>(), "load" to null, "warnings" to emptyList<Any?>(), "summary" to null,
            "classification" to "PERSONALIZED_STATISTICAL"
        )
        val minPoints = rc.int("min_points")
        if (heartHist == 0 || sleepHist < minPoints) {
            val have = minOf(sleepHist, (heartIds.map { (by.getValue(it)["n"] as Number).toInt() } + 0).max())
            out["status"] = "INSUFFICIENT_HISTORY"
            out["reason"] = "collecting"
            out["collecting"] = linkedMapOf("have" to minOf(have, minPoints), "need" to minPoints)
            return roundRecovery(out, comps, drivers)
        }
        if (by.getValue("sleep")["available"] != true) {
            out["status"] = "NO_DATA"
            out["reason"] = "no_sleep"
            return roundRecovery(out, comps, drivers)
        }
        if (heartIds.none { by.getValue(it)["available"] == true }) {
            out["status"] = "NO_DATA"
            out["reason"] = "no_heart_data"
            return roundRecovery(out, comps, drivers)
        }
        val avail = comps.filter { it["available"] == true }
        var wsum = 0.0
        for (i in avail) wsum += (i["weight"] as Number).toDouble()
        var score = 0.0
        var conf = 0.0
        val impact = HashMap<String, Double>()
        for (i in avail) {
            val id = i["id"] as String
            val w = (i["weight"] as Number).toDouble()
            score += w * sNum.getValue(id)!!
            conf += w * cNum.getValue(id)
            impact[id] = (sNum.getValue(id)!! - rc.num("subscore_center")) * w / wsum
            i["impact"] = impact[id]
        }
        score /= wsum
        conf /= totalW
        for (i in comps) if (i["available"] != true) warnings += linkedMapOf("code" to "missing", "metric" to i["id"])
        val tracking = inputs.objOrNull("tracking")
        val ld = load(
            mapOf(
                "workouts" to inputs.list("workouts"), "day" to addDays(day, -1), "time_zone" to (inputs.strOrNull("time_zone") ?: "UTC"),
                "tracking" to (if (tracking != null && tracking.containsKey("workouts")) tracking["workouts"] else true)
            ), cfg
        )
        val ldStatus = ld["status"] as String
        val mod = if (ldStatus == "VALID" || ldStatus == "LOW_CONFIDENCE") (ld["state"] as? String)?.let { rc.obj("load_modifier").numOrNull(it) } ?: 0.0 else 0.0
        val final = clamp(roundInt(score + mod).toDouble(), 0.0, 100.0).toInt()
        val band = rc.maps("bands").first { final >= it.num("min") }
        val tpl = rc.obj("drivers")
        val neutral = rc.num("impact_neutral")
        for (i in avail) {
            val id = i["id"] as String
            val im = impact.getValue(id)
            val direction = if (im > neutral) "positive" else if (im < -neutral) "negative" else "neutral"
            var key = direction
            // Never call a short night "adequate" because its timing and efficiency were good.
            val base = (i["baseline"] as? Number)?.toDouble()
            val value = (i["value"] as? Number)?.toDouble()
            if (id == "sleep" && direction != "negative" && base != null && value != null &&
                base - value >= rc.obj("sleep").num("short_note_deficit_min")
            ) key = direction + "_short"
            drivers += linkedMapOf(
                "id" to id, "direction" to direction, "impact" to im, "value" to i["value"], "baseline" to i["baseline"],
                "z" to i["z"], "unit" to i["unit"], "text" to tpl.obj(id).str(key), "text_key" to "$id.$key"
            )
        }
        val methods = ld["methods"] as JMap
        val pm = ld["primary_method"] as String?
        if (mod < 0) {
            drivers += linkedMapOf(
                "id" to "training_load", "direction" to "negative", "impact" to mod, "value" to ld["today"],
                "baseline" to null, "z" to null, "unit" to "AU", "text" to tpl.obj("training_load").str("negative"),
                "text_key" to "training_load.negative"
            )
        }
        drivers.sortWith(compareBy<LinkedHashMap<String, Any?>> { -abs((it["impact"] as Number).toDouble()) }
            .thenComparator { a, b -> CODE_POINT_ORDER.compare(a["id"] as String, b["id"] as String) })
        val neg = drivers.filter { it["direction"] == "negative" }
        val pos = drivers.filter { it["direction"] == "positive" }
        val st = rc.obj("summary")
        val parts = ArrayList<String>()
        if (neg.isNotEmpty()) parts += st.str("lead_negative").replace("{items}", neg.joinToString("; ") { it["text"] as String })
        if (pos.isNotEmpty()) parts += st.str("lead_positive").replace("{items}", pos.joinToString("; ") { it["text"] as String })
        val seen = HashSet<Pair<Any?, Any?>>()
        val uniq = ArrayList<Map<String, Any?>>()
        for (w in warnings) if (seen.add(w["code"] to w["metric"])) uniq += w
        out["status"] = statusFor(conf, cfg)
        out["score"] = final
        out["label"] = band["id"]
        out["label_text"] = band["label"]
        out["recommendation"] = band["recommendation"]
        out["confidence"] = conf
        out["confidence_band"] = confidenceBand(conf, cfg)
        out["coverage"] = wsum / totalW
        out["positives"] = pos.map { it["id"] }
        out["negatives"] = neg.map { it["id"] }
        out["warnings"] = uniq
        out["summary"] = if (parts.isNotEmpty()) parts.joinToString(" ") else st.str("none")
        out["load"] = linkedMapOf(
            "day" to ld["day"], "state" to ld["state"], "status" to ld["status"], "method" to pm, "modifier" to mod,
            "acute" to pm?.let { (methods[it] as JMap)["acute"] },
            "chronic" to pm?.let { (methods[it] as JMap)["chronic"] },
            "ratio" to pm?.let { (methods[it] as JMap)["ratio"] }
        )
        return roundRecovery(out, comps, drivers)
    }

    private fun roundRecovery(out: LinkedHashMap<String, Any?>, comps: List<LinkedHashMap<String, Any?>>, drivers: List<LinkedHashMap<String, Any?>>): JMap {
        fun r(v: Any?, d: Int): Double? = (v as? Number)?.let { roundTo(it.toDouble(), d) }
        for (i in comps) {
            for (k in listOf("value", "baseline", "z")) i[k] = r(i[k], 2)
            for (k in listOf("subscore", "impact")) i[k] = r(i[k], 1)
            i["confidence"] = r(i["confidence"], 2)
        }
        for (d in drivers) {
            d["impact"] = r(d["impact"], 1)
            for (k in listOf("value", "baseline", "z")) d[k] = r(d[k], 2)
        }
        out["confidence"] = r(out["confidence"], 2)
        out["coverage"] = r(out["coverage"], 2)
        return out
    }

    // -- Multi-signal anomaly ------------------------------------------------------------------------

    @Suppress("UNCHECKED_CAST")
    private fun signalSeries(inputs: JMap, metric: String, cfg: AnalyticsConfig): Map<String, Double> = when (metric) {
        "sleep_duration" -> validNights(inputs["nights"], cfg).mapValues { it.value.num("asleep_min") }
        "training_load" -> loadDays(inputs.maps("workouts"), inputs.strOrNull("time_zone") ?: "UTC").mapValues { it.value.minutes }
        else -> seriesOf((inputs["series"] as? Map<String, Any?>)?.get(metric))
    }

    private class Signal(val id: String, val value: Double?, val median: Double?, val z: Double, val zBad: Double, val flagged: Boolean)

    @Suppress("UNCHECKED_CAST")
    private fun anomalyDay(inputs: JMap, day: String, ac: JMap, cfg: AnalyticsConfig): Pair<String?, List<Signal>> {
        val sigs = ArrayList<Signal>()
        for (s in ac.maps("signals")) {
            val metric = s.str("metric")
            val m = cfg.root.obj("metrics").obj(metric)
            val series = signalSeries(inputs, metric, cfg)
            if (metric == "training_load" && series[day] == null) continue
            val b = robust(series, day, ac.int("baseline_window_days"), ac.int("min_points"), m.num("spread_floor"), cfg,
                stringsOf((inputs["contexts"] as? Map<String, Any?>)?.get(metric)))
            val z = b.z ?: continue
            val zb = when (s.str("bad")) {
                "low" -> -z
                "high" -> z
                else -> abs(z)
            }
            sigs += Signal(s.str("id"), b.value, b.median, z, zb, zb >= ac.num("mild_z"))
        }
        val flagged = sigs.filter { it.flagged }
        val state = when {
            sigs.size < ac.int("min_signals") -> null
            flagged.size >= ac.int("multi_signal_count") -> "MULTI_SIGNAL_DEVIATION"
            sigs.any { it.zBad >= ac.num("significant_z") } -> "SIGNIFICANT_DEVIATION"
            flagged.isNotEmpty() -> "MILD_DEVIATION"
            else -> "NORMAL"
        }
        return state to sigs
    }

    fun anomaly(inp: JMap, cfg: AnalyticsConfig): JMap {
        val ac = cfg.root.obj("anomaly")
        val inputs = inp.obj("inputs")
        val day = inp.str("day")
        val (state, sigs) = anomalyDay(inputs, day, ac, cfg)
        val out = linkedMapOf<String, Any?>(
            "algorithm_id" to ac["algorithm_id"], "algorithm_version" to ac["algorithm_version"], "day" to day,
            "status" to "INSUFFICIENT_DATA", "state" to null, "message" to null, "persistent" to false, "persistent_note" to null,
            "signals" to sigs.map {
                linkedMapOf(
                    "id" to it.id, "value" to roundTo(it.value, 2), "median" to roundTo(it.median, 2), "z" to roundTo(it.z, 2),
                    "z_bad" to roundTo(it.zBad, 2), "flagged" to it.flagged
                )
            },
            "flagged_count" to 0, "classification" to "PERSONALIZED_STATISTICAL", "confidence" to 0.0
        )
        if (state == null) return out
        val serious = setOf("SIGNIFICANT_DEVIATION", "MULTI_SIGNAL_DEVIATION")
        var persistent = state in serious
        for (k in 1 until ac.int("persistent_days")) {
            if (!persistent) break
            persistent = anomalyDay(inputs, addDays(day, -k), ac, cfg).first in serious
        }
        val conf = clamp(sigs.size / ac.maps("signals").size.toDouble(), 0.0, 1.0) *
            cfg.root.obj("classifications").obj("PERSONALIZED_STATISTICAL").num("validity_cap")
        out["status"] = statusFor(conf, cfg)
        out["state"] = state
        out["message"] = ac.obj("states").str(state)
        out["persistent"] = persistent
        out["persistent_note"] = if (persistent) ac["persistent_note"] else null
        out["flagged_count"] = sigs.count { it.flagged }
        out["confidence"] = roundTo(conf, 2)
        return out
    }
}
