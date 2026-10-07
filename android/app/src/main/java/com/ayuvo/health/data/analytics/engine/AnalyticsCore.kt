package com.ayuvo.health.data.analytics.engine

import com.ayuvo.health.data.analytics.engine.AnalyticsMath.CODE_POINT_ORDER
import com.ayuvo.health.data.analytics.engine.AnalyticsMath.canonical
import com.ayuvo.health.data.analytics.engine.AnalyticsMath.clamp
import com.ayuvo.health.data.analytics.engine.AnalyticsMath.fnv1a64
import com.ayuvo.health.data.analytics.engine.AnalyticsMath.int
import com.ayuvo.health.data.analytics.engine.AnalyticsMath.list
import com.ayuvo.health.data.analytics.engine.AnalyticsMath.mad
import com.ayuvo.health.data.analytics.engine.AnalyticsMath.maps
import com.ayuvo.health.data.analytics.engine.AnalyticsMath.median
import com.ayuvo.health.data.analytics.engine.AnalyticsMath.num
import com.ayuvo.health.data.analytics.engine.AnalyticsMath.numOrNull
import com.ayuvo.health.data.analytics.engine.AnalyticsMath.obj
import com.ayuvo.health.data.analytics.engine.AnalyticsMath.percentile
import com.ayuvo.health.data.analytics.engine.AnalyticsMath.roundTo
import com.ayuvo.health.data.analytics.engine.AnalyticsMath.str
import com.ayuvo.health.data.analytics.engine.AnalyticsMath.strOrNull
import com.ayuvo.health.data.analytics.engine.AnalyticsMath.windowDays
import kotlin.math.abs
import kotlin.math.floor

/**
 * Status, confidence, hashing, source policy, units, robust baselines and trends: exact ports of the matching
 * sections of `scripts/analytics_reference.py` (docs/health-analytics.md). Outputs are plain JSON maps with the
 * reference's keys.
 */
object AnalyticsCore {

    // -- status and confidence -----------------------------------------------------------------------

    fun worstStatus(statuses: List<String>, cfg: AnalyticsConfig): String {
        val rank = cfg.root.obj("status_rank")
        var best = "VALID"
        for (s in statuses) if (rank.num(s) > rank.num(best)) best = s
        return best
    }

    fun confidence(
        classification: String, coverage: Double, n: Int, targetN: Int, context: String?, sourceChanged: Boolean,
        cfg: AnalyticsConfig
    ): Double {
        val c = cfg.root.obj("confidence")
        val fCov = clamp(coverage / c.num("target_coverage"), 0.0, 1.0)
        val fN = clamp(n / targetN.toDouble(), 0.0, 1.0)
        val fCtx = c.obj("context_weight").numOrNull(context ?: "wearable") ?: 1.0
        val fSrc = if (sourceChanged) c.num("source_change_factor") else 1.0
        val cap = cfg.root.obj("classifications").obj(classification).num("validity_cap")
        return cap * AnalyticsMath.sqrt(fCov * fN) * fCtx * fSrc
    }

    fun confidenceBand(conf: Double, cfg: AnalyticsConfig): String {
        for (b in cfg.root.obj("confidence").maps("bands")) if (conf >= b.num("min")) return b.str("id")
        return "low"
    }

    fun statusFor(conf: Double, cfg: AnalyticsConfig): String =
        if (conf >= cfg.root.obj("confidence").num("low_confidence_below")) "VALID" else "LOW_CONFIDENCE"

    fun inputHash(inp: JMap): JMap {
        val c = canonical(inp["value"])
        return linkedMapOf("canonical" to c, "hash" to fnv1a64(c))
    }

    // -- source policy -------------------------------------------------------------------------------

    private fun stripGh(source: String): String =
        if (source.startsWith("google_health:")) source.substring("google_health:".length) else source

    /** One day of one metric's discrete rows -> the value the rollup should use (`source_select`). */
    fun sourceSelect(inp: JMap, policy: JMap): JMap {
        val strategy = policy.obj("metrics").strOrNull(inp.str("metric")) ?: policy.str("default_strategy")
        val rows = inp.maps("rows")
            .sortedWith(compareBy<JMap> { it.num("t_ms") }.thenComparator { a, b -> CODE_POINT_ORDER.compare(a.str("id"), b.str("id")) })
            .filter { it["value"] != null }
        val out = linkedMapOf<String, Any?>(
            "strategy" to strategy, "value" to null, "min" to null, "max" to null, "count" to 0, "source" to null,
            "dropped_duplicates" to 0, "sources" to rows.map { it.str("source") }.toSortedSet(CODE_POINT_ORDER).toList()
        )
        if (rows.isEmpty()) return out
        val win = policy.num("dedup_window_s") * 1000
        val tol = policy.list("dedup_value_tolerance").map { (it as Number).toDouble() }
        val kept = ArrayList<JMap>()
        var dropped = 0
        for (r in rows) {
            if (r.num("origin") == 3.0) {
                var dup = false
                for (o in rows) {
                    if (o.num("origin") != 3.0 && o.str("source") == stripGh(r.str("source")) && abs(o.num("t_ms") - r.num("t_ms")) <= win) {
                        if (abs(o.num("value") - r.num("value")) <= maxOf(tol[0], tol[1] * abs(o.num("value")))) {
                            dup = true
                            break
                        }
                    }
                }
                if (dup) {
                    dropped += 1
                    continue
                }
            }
            kept += r
        }
        out["dropped_duplicates"] = dropped
        var chosen: List<JMap> = kept
        if (strategy == "single_best_source_per_day") {
            val by = LinkedHashMap<String, MutableList<JMap>>()
            for (r in kept) by.getOrPut(r.str("source")) { ArrayList() } += r
            val wearable = policy.list("wearable_device_types").map { (it as Number).toDouble() }.toSet()
            fun count(src: String): Int {
                var n = 0
                for (r in by.getValue(src)) n += maxOf(1, (r.numOrNull("count") ?: 1.0).toInt().let { if (it == 0) 1 else it })
                return n
            }
            val src = by.keys.sortedWith(
                compareBy<String> { s -> if (by.getValue(s).any { r -> r.numOrNull("device_type")?.let { it in wearable } == true }) 0 else 1 }
                    .thenByDescending { count(it) }
                    .then(CODE_POINT_ORDER)
            )[0]
            chosen = by.getValue(src)
            out["source"] = src
        }
        var ws = 0.0
        var w = 0
        var lo: Double? = null
        var hi: Double? = null
        for (r in chosen) {
            val n = maxOf(1, (r.numOrNull("count") ?: 1.0).toInt().let { if (it == 0) 1 else it })
            val v = r.num("value")
            ws += v * n
            w += n
            lo = if (lo == null || v < lo) v else lo
            hi = if (hi == null || v > hi) v else hi
        }
        out["value"] = roundTo(ws / w, 6)
        out["min"] = lo
        out["max"] = hi
        out["count"] = w
        return out
    }

    // -- units ---------------------------------------------------------------------------------------

    private val UNIT_FACTORS: Map<Pair<String, String>, Double> = mapOf(
        ("lb" to "kg") to 0.45359237, ("kg" to "lb") to 1.0 / 0.45359237,
        ("ft" to "m") to 0.3048, ("m" to "ft") to 1.0 / 0.3048, ("in" to "m") to 0.0254, ("m" to "in") to 1.0 / 0.0254,
        ("cm" to "m") to 0.01, ("m" to "cm") to 100.0, ("km" to "m") to 1000.0, ("m" to "km") to 0.001,
        ("mi" to "m") to 1609.344, ("m" to "mi") to 1.0 / 1609.344,
        ("kJ" to "kcal") to 1.0 / 4.184, ("kcal" to "kJ") to 4.184,
        ("min" to "s") to 60.0, ("s" to "min") to 1.0 / 60.0, ("h" to "s") to 3600.0, ("s" to "h") to 1.0 / 3600.0,
        ("h" to "min") to 60.0, ("min" to "h") to 1.0 / 60.0, ("ms" to "s") to 0.001, ("s" to "ms") to 1000.0,
        ("L" to "mL") to 1000.0, ("mL" to "L") to 0.001, ("g" to "mg") to 1000.0, ("mg" to "g") to 0.001,
        ("fraction" to "%") to 100.0, ("%" to "fraction") to 0.01
    )

    fun unitConvert(value: Double?, from: String, to: String): JMap {
        if (value == null) return linkedMapOf("value" to null, "ok" to true)
        if (from == to) return linkedMapOf("value" to roundTo(value, 9), "ok" to true)
        if (from == "degF" && to == "degC") return linkedMapOf("value" to roundTo((value - 32.0) * 5.0 / 9.0, 9), "ok" to true)
        if (from == "degC" && to == "degF") return linkedMapOf("value" to roundTo(value * 9.0 / 5.0 + 32.0, 9), "ok" to true)
        val f = UNIT_FACTORS[from to to] ?: return linkedMapOf("value" to null, "ok" to false)
        return linkedMapOf("value" to roundTo(value * f, 9), "ok" to true)
    }

    // -- robust baseline -----------------------------------------------------------------------------

    /** Raw (unrounded) `_robust` result. */
    class Robust(
        val windowDays: Int, val n: Int, val needed: Int, val coverage: Double, val value: Double?, val context: String?,
        val median: Double?, val mad: Double?, val spread: Double?, val p10: Double?, val p90: Double?,
        val deviation: Double?, val pct: Double?, val z: Double?, val sourceChanged: Boolean
    )

    private val DEFAULT_ALLOWED = listOf("wearable", "provider", "manual", "derived")

    fun robust(
        series: Map<String, Double>, day: String, window: Int, minPoints: Int, spreadFloor: Double, cfg: AnalyticsConfig,
        contexts: Map<String, String>? = null, allowed: List<String>? = null, sources: Map<String, String>? = null,
        recent: String = "day"
    ): Robust {
        val ctx = contexts ?: emptyMap()
        val allow = allowed ?: DEFAULT_ALLOWED
        val vals = ArrayList<Double>()
        val srcs = ArrayList<String>()
        for (d in windowDays(day, -window, -1)) {
            val v = series[d] ?: continue
            if ((ctx[d] ?: "wearable") !in allow) continue
            vals += v
            sources?.get(d)?.let { srcs += it }
        }
        val today: Double? = if (recent == "median7") {
            val last = windowDays(day, -6, 0).mapNotNull { series[it] }
            if (last.isNotEmpty()) median(last) else null
        } else series[day]
        val n = vals.size
        val context = if (today != null) (ctx[day] ?: "wearable") else null
        if (n < minPoints) {
            return Robust(window, n, minPoints, n / window.toDouble(), today, context, null, null, null, null, null, null, null, null, false)
        }
        val med = median(vals)
        val m = mad(vals)
        val pc = cfg.root.obj("baseline").list("percentiles").map { (it as Number).toDouble() }
        val spread = maxOf(cfg.root.obj("baseline").num("mad_scale") * m, spreadFloor)
        var changed = false
        if (sources != null && srcs.isNotEmpty() && sources[day] != null) {
            val counts = LinkedHashMap<String, Int>()
            for (s in srcs) counts[s] = (counts[s] ?: 0) + 1
            val top = counts.keys.sortedWith(compareByDescending<String> { counts.getValue(it) }.then(CODE_POINT_ORDER))[0]
            changed = sources[day] != top
        }
        var deviation: Double? = null
        var pct: Double? = null
        var z: Double? = null
        if (today != null) {
            deviation = today - med
            pct = if (med == 0.0) null else (today - med) / abs(med) * 100.0
            z = (today - med) / spread
        }
        return Robust(
            window, n, minPoints, n / window.toDouble(), today, context, med, m, spread, percentile(vals, pc[0]),
            percentile(vals, pc[1]), deviation, pct, z, changed
        )
    }

    fun zDir(z: Double, direction: String): Double = when (direction) {
        "higher_better" -> z
        "lower_better" -> -z
        else -> -abs(z)
    }

    fun baseline(inp: JMap, cfg: AnalyticsConfig): JMap {
        val m = cfg.root.obj("metrics").obj(inp.str("metric"))
        val window = (inp.numOrNull("window")?.takeIf { it != 0.0 } ?: 28.0).toInt()
        val windows = cfg.root.obj("baseline").list("windows").map { (it as Number).toInt() }
        require(window in windows) { "window $window not in config baseline.windows" }
        @Suppress("UNCHECKED_CAST")
        val b = robust(
            seriesOf(inp["series"]), inp.str("day"), window, cfg.root.obj("baseline").obj("min_points").int(window.toString()),
            m.num("spread_floor"), cfg, stringsOf(inp["contexts"]), null, stringsOf(inp["sources"]),
            inp.strOrNull("recent") ?: "day"
        )
        return baselineOut(b, m, cfg)
    }

    fun baselineOut(b: Robust, m: JMap, cfg: AnalyticsConfig): JMap {
        val status: String
        var conf = 0.0
        if (b.median == null) status = "INSUFFICIENT_HISTORY"
        else if (b.value == null) status = "NO_DATA"
        else {
            conf = confidence(m.str("classification"), b.coverage, b.n, cfg.root.obj("confidence").int("target_n"), b.context, b.sourceChanged, cfg)
            status = statusFor(conf, cfg)
        }
        val d = m.int("decimals") + 1
        return linkedMapOf(
            "status" to status, "window_days" to b.windowDays, "n" to b.n, "needed" to b.needed,
            "coverage" to roundTo(b.coverage, 2), "median" to roundTo(b.median, d), "mad" to roundTo(b.mad, d),
            "spread" to roundTo(b.spread, d), "p10" to roundTo(b.p10, d), "p90" to roundTo(b.p90, d),
            "value" to roundTo(b.value, d), "context" to b.context, "deviation" to roundTo(b.deviation, d),
            "pct" to roundTo(b.pct, 1), "z" to roundTo(b.z, 2),
            "z_dir" to b.z?.let { roundTo(zDir(it, m.str("direction")), 2) },
            "source_changed" to b.sourceChanged, "confidence" to roundTo(conf, 2),
            "classification" to "PERSONALIZED_STATISTICAL"
        )
    }

    fun baselines(inp: JMap, cfg: AnalyticsConfig): JMap {
        val out = linkedMapOf<String, Any?>()
        for (w in cfg.root.obj("baseline").list("windows").map { (it as Number).toInt() }) {
            val q = LinkedHashMap(inp)
            q["window"] = w.toDouble()
            out[w.toString()] = baseline(q, cfg)
        }
        return out
    }

    // -- trend ---------------------------------------------------------------------------------------

    /** (slope, intercept, slopes) or (null, null, empty) — the reference `theil_sen`. */
    data class TheilSen(val slope: Double?, val intercept: Double?, val slopes: List<Double>)

    fun theilSen(points: List<Pair<Double, Double>>): TheilSen {
        val slopes = ArrayList<Double>()
        for (i in points.indices) for (j in i + 1 until points.size) {
            val dx = points[j].first - points[i].first
            if (dx != 0.0) slopes += (points[j].second - points[i].second) / dx
        }
        if (slopes.isEmpty()) return TheilSen(null, null, slopes)
        val b = median(slopes)
        val a = median(points.map { it.second - b * it.first })
        return TheilSen(b, a, slopes)
    }

    fun ewma(values: List<Double>, span: Double): Double? {
        val alpha = 2.0 / (span + 1.0)
        var e: Double? = null
        for (v in values) e = if (e == null) v else alpha * v + (1.0 - alpha) * e
        return e
    }

    fun cusum(z: List<Double>, k: Double, h: Double): Pair<Int?, String?> {
        var sp = 0.0
        var sn = 0.0
        for ((i, x) in z.withIndex()) {
            sp = maxOf(0.0, sp + x - k)
            sn = maxOf(0.0, sn - x - k)
            if (sp > h) return i to "up"
            if (sn > h) return i to "down"
        }
        return null to null
    }

    fun trend(inp: JMap, cfg: AnalyticsConfig): JMap {
        val t = cfg.root.obj("trend")
        val m = cfg.root.obj("metrics").obj(inp.str("metric"))
        val window = (inp.numOrNull("window")?.takeIf { it != 0.0 } ?: t.num("window_days")).toInt()
        val series = seriesOf(inp["series"])
        val day = inp.str("day")
        val days = windowDays(day, -(window - 1), 0)
        val pts = days.withIndex().mapNotNull { (i, d) -> series[d]?.let { i.toDouble() to it } }
        val out = linkedMapOf<String, Any?>(
            "status" to "INSUFFICIENT_DATA", "label" to "INSUFFICIENT_DATA", "window_days" to window, "sample_count" to pts.size,
            "coverage" to roundTo(pts.size / window.toDouble(), 2), "slope_per_day" to null, "pct_per_week" to null,
            "recent_median" to null, "prior_median" to null, "pct_change" to null, "ewma" to null, "latest_z" to null,
            "change_point_day" to null, "change_direction" to null, "confidence" to 0.0
        )
        if (pts.size < t.int("min_points")) return out
        val ys = pts.map { it.second }
        val med = median(ys)
        val slope = theilSen(pts).slope!!
        val pctWeek = if (med == 0.0) null else slope * 7.0 / abs(med) * 100.0
        val recentDays = t.int("recent_days")
        val recent = pts.filter { it.first >= window - recentDays }.map { it.second }
        val prior = pts.filter { it.first < window - recentDays }.map { it.second }
        val rm = if (recent.isNotEmpty()) median(recent) else null
        val pm = if (prior.isNotEmpty()) median(prior) else null
        val madScale = cfg.root.obj("baseline").num("mad_scale")
        val spread = maxOf(madScale * mad(ys), m.num("spread_floor"))
        var latestZ: Double? = null
        if (series[day] != null && prior.isNotEmpty()) {
            val others = pts.filter { it.first != (window - 1).toDouble() }.map { it.second }
            val oSpread = maxOf(madScale * mad(others), m.num("spread_floor"))
            latestZ = (series.getValue(day) - median(others)) / oSpread
        }
        var cpDay: String? = null
        var cpDir: String? = null
        val c = t.obj("cusum")
        if (pts.size >= c.int("min_points")) {
            val refN = c.int("reference_points")
            val ref = pts.take(refN).map { it.second }
            val rMed = median(ref)
            val rSpread = maxOf(madScale * mad(ref), m.num("spread_floor"))
            val (idx, dir) = cusum(pts.drop(refN).map { (it.second - rMed) / rSpread }, c.num("k"), c.num("h"))
            cpDir = dir
            if (idx != null) cpDay = days[pts[refN + idx].first.toInt()]
        }
        val label = when {
            latestZ != null && abs(latestZ) >= t.num("unusual_abs_z") -> "UNUSUAL"
            pctWeek == null || abs(pctWeek) < m.num("trend_stable_pct_per_week") -> "STABLE"
            m.str("direction") == "band" -> "CHANGING"
            (pctWeek > 0) == (m.str("direction") == "higher_better") -> "IMPROVING"
            else -> "DECLINING"
        }
        val conf = confidence(m.str("classification"), pts.size / window.toDouble(), pts.size, window, "wearable", false, cfg)
        val d = m.int("decimals") + 1
        out["status"] = statusFor(conf, cfg)
        out["label"] = label
        out["slope_per_day"] = roundTo(slope, 4)
        out["pct_per_week"] = roundTo(pctWeek, 2)
        out["recent_median"] = roundTo(rm, d)
        out["prior_median"] = roundTo(pm, d)
        out["pct_change"] = roundTo(if (rm == null || pm == null || pm == 0.0) null else (rm - pm) / abs(pm) * 100.0, 1)
        out["ewma"] = roundTo(ewma(ys, t.num("ewma_span_days")), d)
        out["latest_z"] = roundTo(latestZ, 2)
        out["change_point_day"] = cpDay
        out["change_direction"] = cpDir
        out["confidence"] = roundTo(conf, 2)
        out["median"] = roundTo(med, d)
        out["spread"] = roundTo(spread, d)
        return out
    }

    // -- helpers -------------------------------------------------------------------------------------

    @Suppress("UNCHECKED_CAST")
    fun seriesOf(v: Any?): Map<String, Double> =
        ((v as? Map<String, Any?>) ?: emptyMap()).entries.mapNotNull { (d, x) -> (x as? Number)?.let { d to it.toDouble() } }.toMap()

    @Suppress("UNCHECKED_CAST")
    fun stringsOf(v: Any?): Map<String, String>? =
        (v as? Map<String, Any?>)?.entries?.mapNotNull { (d, x) -> (x as? String)?.let { d to it } }?.toMap()

    /** Python `int(math.floor(x))`. */
    fun floorInt(x: Double): Int = floor(x).toInt()
}
