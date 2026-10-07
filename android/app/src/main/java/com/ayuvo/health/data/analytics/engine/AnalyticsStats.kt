package com.ayuvo.health.data.analytics.engine

import com.ayuvo.health.data.analytics.engine.AnalyticsCore.seriesOf
import com.ayuvo.health.data.analytics.engine.AnalyticsCore.statusFor
import com.ayuvo.health.data.analytics.engine.AnalyticsCore.theilSen
import com.ayuvo.health.data.analytics.engine.AnalyticsMath.addDays
import com.ayuvo.health.data.analytics.engine.AnalyticsMath.atanh
import com.ayuvo.health.data.analytics.engine.AnalyticsMath.clamp
import com.ayuvo.health.data.analytics.engine.AnalyticsMath.daysBetween
import com.ayuvo.health.data.analytics.engine.AnalyticsMath.int
import com.ayuvo.health.data.analytics.engine.AnalyticsMath.list
import com.ayuvo.health.data.analytics.engine.AnalyticsMath.localDayOf
import com.ayuvo.health.data.analytics.engine.AnalyticsMath.maps
import com.ayuvo.health.data.analytics.engine.AnalyticsMath.mean
import com.ayuvo.health.data.analytics.engine.AnalyticsMath.median
import com.ayuvo.health.data.analytics.engine.AnalyticsMath.normalCdf
import com.ayuvo.health.data.analytics.engine.AnalyticsMath.num
import com.ayuvo.health.data.analytics.engine.AnalyticsMath.numOrNull
import com.ayuvo.health.data.analytics.engine.AnalyticsMath.obj
import com.ayuvo.health.data.analytics.engine.AnalyticsMath.objOrNull
import com.ayuvo.health.data.analytics.engine.AnalyticsMath.roundTo
import com.ayuvo.health.data.analytics.engine.AnalyticsMath.sqrt
import com.ayuvo.health.data.analytics.engine.AnalyticsMath.str
import com.ayuvo.health.data.analytics.engine.AnalyticsMath.strOrNull
import com.ayuvo.health.data.analytics.engine.AnalyticsMath.tanh
import com.ayuvo.health.data.analytics.engine.AnalyticsMath.truthy
import com.ayuvo.health.data.analytics.engine.AnalyticsMath.windowDays
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

/** Correlation, personal response, energy, VO2 max trend and MET intensity (reference ports). */
object AnalyticsStats {

    fun ranks(values: List<Double>): List<Double> {
        val order = values.indices.sortedWith(compareBy<Int> { values[it] }.thenBy { it })
        val r = DoubleArray(values.size)
        var i = 0
        while (i < order.size) {
            var j = i
            while (j + 1 < order.size && values[order[j + 1]] == values[order[i]]) j += 1
            val avg = (i + j) / 2.0 + 1.0
            for (k in i..j) r[order[k]] = avg
            i = j + 1
        }
        return r.toList()
    }

    fun pearson(xs: List<Double>, ys: List<Double>): Double? {
        val mx = mean(xs)
        val my = mean(ys)
        var sxx = 0.0
        var syy = 0.0
        var sxy = 0.0
        for (k in xs.indices) {
            val x = xs[k]
            val y = ys[k]
            sxx += (x - mx) * (x - mx)
            syy += (y - my) * (y - my)
            sxy += (x - mx) * (y - my)
        }
        if (sxx == 0.0 || syy == 0.0) return null
        return sxy / sqrt(sxx * syy)
    }

    fun paired(exposure: Map<String, Double>, outcome: Map<String, Double>, lag: Int, asOf: String, window: Int): List<Pair<Double, Double>> {
        val out = ArrayList<Pair<Double, Double>>()
        for (d in windowDays(asOf, -window, -lag)) {
            val x = exposure[d] ?: continue
            val y = outcome[addDays(d, lag)] ?: continue
            out += x to y
        }
        return out
    }

    fun bhQvalues(p: List<Double>): List<Double> {
        val m = p.size
        val order = p.indices.sortedWith(compareBy<Int> { p[it] }.thenBy { it })
        val q = DoubleArray(m)
        var running = 1.0
        for (rank in m downTo 1) {
            val i = order[rank - 1]
            running = minOf(running, p[i] * m / rank)
            q[i] = running
        }
        return q.toList()
    }

    fun correlation(inp: JMap, cfg: AnalyticsConfig): JMap {
        val cc = cfg.root.obj("correlation")
        val meta = cc.maps("pairs").associateBy { it.str("id") }
        val results = ArrayList<LinkedHashMap<String, Any?>>()
        val tested = ArrayList<LinkedHashMap<String, Any?>>()
        val asOf = inp.str("as_of")
        for (pr in inp.maps("pairs")) {
            val exposure = seriesOf(pr["exposure"])
            val outcome = seriesOf(pr["outcome"])
            for (lagV in cc.list("lags")) {
                val lag = (lagV as Number).toInt()
                val pts = paired(exposure, outcome, lag, asOf, cc.int("window_days"))
                val r = linkedMapOf<String, Any?>(
                    "id" to pr.str("id"), "lag" to lag, "n" to pts.size, "status" to "INSUFFICIENT_DATA", "pearson_r" to null,
                    "spearman_rho" to null, "ci_low" to null, "ci_high" to null, "p" to null, "q" to null, "surfaced" to false,
                    "text" to null
                )
                if (pts.size >= cc.int("min_n")) {
                    val xs = pts.map { it.first }
                    val ys = pts.map { it.second }
                    val prR = pearson(xs, ys)
                    val rho = pearson(ranks(xs), ranks(ys))
                    if (prR != null && rho != null) {
                        val z = atanh(clamp(rho, -0.9999, 0.9999))
                        val se = sqrt(cc.num("spearman_se_factor") / (pts.size - 3))
                        val p = 2.0 * (1.0 - normalCdf(abs(z) / se))
                        r["status"] = "VALID"
                        r["pearson_r"] = prR
                        r["spearman_rho"] = rho
                        r["ci_low"] = tanh(z - cc.num("z_975") * se)
                        r["ci_high"] = tanh(z + cc.num("z_975") * se)
                        r["p"] = p
                        tested += r
                    }
                }
                results += r
            }
        }
        val qs = bhQvalues(tested.map { it["p"] as Double })
        for ((r, q) in tested.zip(qs)) {
            r["q"] = q
            val rho = r["spearman_rho"] as Double
            val surfaced = q <= cc.num("q_level") && abs(rho) >= cc.num("min_abs_rho")
            r["surfaced"] = surfaced
            if (surfaced) {
                val m = meta.getValue(r["id"] as String)
                r["text"] = cc.str("template").replace("{exposure}", m.str("exposure_label"))
                    .replace("{direction}", cc.obj("direction_words").str(if (rho > 0) "higher" else "lower"))
                    .replace("{outcome}", m.str("outcome_label"))
                    .replace("{lag}", cc.obj("lag_words").str((r["lag"] as Int).toString()))
                    .replace("{n}", String.format(Locale.ROOT, "%d", r["n"] as Int))
            }
        }
        for (r in results) {
            for ((k, d) in listOf("pearson_r" to 3, "spearman_rho" to 3, "ci_low" to 3, "ci_high" to 3, "p" to 4, "q" to 4)) {
                r[k] = (r[k] as Double?)?.let { roundTo(it, d) }
            }
        }
        return linkedMapOf(
            "algorithm_id" to cc["algorithm_id"], "algorithm_version" to cc["algorithm_version"], "as_of" to asOf,
            "tested" to tested.size, "results" to results, "classification" to "PERSONALIZED_STATISTICAL"
        )
    }

    fun response(inp: JMap, cfg: AnalyticsConfig): JMap {
        val rc = cfg.root.obj("response")
        val window = (inp.numOrNull("window")?.takeIf { it != 0.0 } ?: cfg.root.obj("correlation").num("window_days")).toInt()
        val lag = inp.int("lag")
        val pts = paired(seriesOf(inp["exposure"]), seriesOf(inp["outcome"]), lag, inp.str("as_of"), window)
        val out = linkedMapOf<String, Any?>(
            "algorithm_id" to rc["algorithm_id"], "algorithm_version" to rc["algorithm_version"], "status" to "INSUFFICIENT_DATA",
            "n" to pts.size, "lag" to lag, "effect" to null, "intercept" to null, "ci_low" to null, "ci_high" to null,
            "excludes_zero" to false, "confidence" to 0.0, "classification" to "PERSONALIZED_STATISTICAL"
        )
        if (pts.size < rc.int("min_n")) return out
        val ts = theilSen(pts)
        val b = ts.slope ?: return out
        val n = pts.size
        val s = ts.slopes.sorted()
        val bigN = s.size
        val c = rc.num("z_975") * sqrt(n * (n - 1) * (2.0 * n + 5) / 18.0)
        val lo = clamp(floor((bigN - c) / 2.0), 0.0, (bigN - 1).toDouble()).toInt()
        val hi = clamp(ceil((bigN + c) / 2.0), 0.0, (bigN - 1).toDouble()).toInt()
        val conf = clamp(n / 60.0, 0.0, 1.0) * cfg.root.obj("classifications").obj("PERSONALIZED_STATISTICAL").num("validity_cap")
        out["status"] = statusFor(conf, cfg)
        out["effect"] = roundTo(b, 4)
        out["intercept"] = roundTo(ts.intercept!!, 3)
        out["ci_low"] = roundTo(s[lo], 4)
        out["ci_high"] = roundTo(s[hi], 4)
        out["excludes_zero"] = s[lo] > 0 || s[hi] < 0
        out["confidence"] = roundTo(conf, 2)
        return out
    }

    // -- energy --------------------------------------------------------------------------------------

    fun mifflin(weightKg: Double, heightCm: Double, age: Double, sex: String?, ec: JMap): Double {
        val m = ec.obj("mifflin")
        return 10.0 * weightKg + 6.25 * heightCm - 5.0 * age + (m.numOrNull(sex?.takeIf { it.isNotEmpty() } ?: "other") ?: m.num("other"))
    }

    fun energy(inp: JMap, cfg: AnalyticsConfig): JMap {
        val ec = cfg.root.obj("energy")
        val p = inp.objOrNull("profile") ?: emptyMap()
        var pred: Double? = null
        if (truthy(p["weight_kg"]) && truthy(p["height_cm"]) && p["age"] != null) {
            pred = mifflin(p.num("weight_kg"), p.num("height_cm"), p.num("age"), p.strOrNull("sex"), ec)
        }
        val out = linkedMapOf<String, Any?>(
            "algorithm_id" to ec["algorithm_id"], "algorithm_version" to ec["algorithm_version"], "status" to "NO_DATA",
            "predicted_resting_kcal" to roundTo(pred, 0), "provider_basal_kcal" to null, "provider_active_kcal" to null,
            "provider_workout_kcal" to null, "ayuvo_extra_kcal" to 0.0, "resting_kcal" to null, "resting_source" to null,
            "active_kcal" to null, "estimated_daily_expenditure" to null, "confidence" to 0.0,
            "classification" to "SCIENTIFIC_DERIVED"
        )
        val basal = inp.numOrNull("provider_basal_kcal")
        val active = inp.numOrNull("provider_active_kcal")
        val workoutKcal = inp.numOrNull("provider_workout_kcal")
        for (v in listOf(basal, active, workoutKcal)) {
            if (v != null && v < 0) {
                out["status"] = "INVALID_INPUT"
                return out
            }
        }
        out["provider_basal_kcal"] = roundTo(basal, 0)
        out["provider_active_kcal"] = roundTo(active, 0)
        out["provider_workout_kcal"] = roundTo(workoutKcal, 0)
        var extra = 0.0
        val providerWorkouts = inp.maps("provider_workouts")
        for (s in inp.maps("ayuvo_sessions")) {
            val covered = providerWorkouts.any { w -> w.num("start_ms") < s.num("end_ms") && s.num("start_ms") < w.num("end_ms") }
            val kcal = s.numOrNull("kcal")
            if (!covered && kcal != null && kcal > 0) extra += kcal
        }
        out["ayuvo_extra_kcal"] = roundTo(extra, 0)
        val rest: Double
        val src: String
        val cRest: Double
        if (basal != null && basal > 0 && (pred == null || basal >= ec.num("bmr_min_share") * pred)) {
            rest = basal; src = "provider"; cRest = ec.obj("confidence").num("provider_resting")
        } else if (pred != null) {
            rest = pred; src = "predicted"; cRest = ec.obj("confidence").num("predicted_resting")
        } else return out
        out["resting_kcal"] = roundTo(rest, 0)
        out["resting_source"] = src
        if (active == null) {
            out["status"] = "INSUFFICIENT_DATA"
            return out
        }
        val act = active + extra
        val tdee = (rest + act) / (1.0 - ec.num("tef_share"))
        val conf = cRest * ec.obj("confidence").num("provider_active") *
            cfg.root.obj("classifications").obj("SCIENTIFIC_DERIVED").num("validity_cap")
        out["status"] = statusFor(conf, cfg)
        out["active_kcal"] = roundTo(act, 0)
        out["estimated_daily_expenditure"] = roundTo(tdee, 0)
        out["confidence"] = roundTo(conf, 2)
        return out
    }

    // -- VO2 max trend -------------------------------------------------------------------------------

    fun vo2maxTrend(inp: JMap, cfg: AnalyticsConfig): JMap {
        val fc = cfg.root.obj("fitness")
        val day = inp.str("day")
        val kindsOut = linkedMapOf<String, Any?>()
        val out = linkedMapOf<String, Any?>(
            "algorithm_id" to fc["algorithm_id"], "algorithm_version" to fc["algorithm_version"], "day" to day,
            "kinds" to kindsOut, "primary" to null
        )
        val readings = inp.objOrNull("readings") ?: emptyMap()
        for (kindV in fc.list("kinds")) {
            val kind = kindV as String
            val s = seriesOf(readings[kind])
            val days = windowDays(day, -(fc.int("window_days") - 1), 0).filter { s[it] != null }
            val r = linkedMapOf<String, Any?>(
                "n" to days.size, "latest" to null, "latest_day" to null, "change" to null, "slope_per_30d" to null,
                "classification" to fc.obj("kind_classification").str(kind), "status" to "NO_DATA"
            )
            if (days.isNotEmpty()) {
                val last = days.last()
                r["latest"] = roundTo(s.getValue(last), 1)
                r["latest_day"] = last
                r["status"] = if (kind == "provider") "PROVIDER_REPORTED" else "VALID"
                val old = days.filter { daysBetween(it, last) >= fc.int("change_min_gap_days") }.map { s.getValue(it) }
                if (old.isNotEmpty()) r["change"] = roundTo(s.getValue(last) - median(old), 1)
                if (days.size >= fc.int("slope_min_points")) {
                    val b = theilSen(days.map { daysBetween(days[0], it).toDouble() to s.getValue(it) }).slope
                    r["slope_per_30d"] = b?.let { roundTo(it * 30.0, 2) }
                }
                if (kind == "uth") r["status"] = "EXPERIMENTAL"
                if (out["primary"] == null) out["primary"] = kind
            }
            kindsOut[kind] = r
        }
        return out
    }

    // -- MET intensity -------------------------------------------------------------------------------

    private class Merged(val startMs: Double, var endMs: Double, var met: Double?, var key: String?)

    fun metIntensity(inp: JMap, cfg: AnalyticsConfig): JMap {
        val mc = cfg.root.obj("met")
        val tz = inp.strOrNull("time_zone") ?: "UTC"
        val table = mc.obj("table")
        val acts = inp.maps("activities").filter { it.num("end_ms") > it.num("start_ms") }
            .sortedWith(compareBy<JMap> { it.num("start_ms") }.thenBy { it.num("end_ms") })
        val merged = ArrayList<Merged>()
        for (a in acts) {
            val e = table.objOrNull(a.strOrNull("key") ?: "")
            val met = e?.num("met")
            val last = merged.lastOrNull()
            if (last != null && a.num("start_ms") < last.endMs) {
                last.endMs = maxOf(last.endMs, a.num("end_ms"))
                if (met != null && (last.met == null || met > last.met!!)) {
                    last.met = met
                    last.key = a.strOrNull("key")
                }
            } else merged += Merged(a.num("start_ms"), a.num("end_ms"), met, a.strOrNull("key"))
        }
        val day = inp.str("day")
        val first = addDays(day, -(mc.int("window_days") - 1))
        var light = 0.0
        var moderate = 0.0
        var vigorous = 0.0
        var unknown = 0.0
        var metMinutes = 0.0
        var sessions = 0
        val bands = mc.obj("bands")
        for (m in merged) {
            val d = localDayOf(m.startMs.toLong(), tz)
            if (d < first || d > day) continue
            val mins = (m.endMs - m.startMs) / 60000.0
            sessions += 1
            val met = m.met
            if (met == null) {
                unknown += mins
                continue
            }
            metMinutes += met * mins
            if (met >= bands.num("vigorous_min")) vigorous += mins
            else if (met >= bands.num("moderate_min")) moderate += mins
            else light += mins
        }
        val modEq = moderate + 2.0 * vigorous
        return linkedMapOf(
            "algorithm_id" to mc["algorithm_id"], "algorithm_version" to mc["algorithm_version"], "day" to day,
            "light_min" to roundTo(light, 1), "moderate_min" to roundTo(moderate, 1), "vigorous_min" to roundTo(vigorous, 1),
            "unknown_min" to roundTo(unknown, 1), "met_minutes" to roundTo(metMinutes, 1),
            "moderate_equivalent_min" to roundTo(modEq, 1),
            "meets_who" to (modEq >= mc.num("who_weekly_moderate_equivalent_min")), "sessions" to sessions,
            "classification" to "SCIENTIFIC_DERIVED"
        )
    }
}
