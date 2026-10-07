package com.ayuvo.health.data.analytics.engine

import com.ayuvo.health.data.analytics.engine.AnalyticsCore.robust
import com.ayuvo.health.data.analytics.engine.AnalyticsCore.seriesOf
import com.ayuvo.health.data.analytics.engine.AnalyticsMath.addDays
import com.ayuvo.health.data.analytics.engine.AnalyticsMath.int
import com.ayuvo.health.data.analytics.engine.AnalyticsMath.list
import com.ayuvo.health.data.analytics.engine.AnalyticsMath.maps
import com.ayuvo.health.data.analytics.engine.AnalyticsMath.mean
import com.ayuvo.health.data.analytics.engine.AnalyticsMath.median
import com.ayuvo.health.data.analytics.engine.AnalyticsMath.num
import com.ayuvo.health.data.analytics.engine.AnalyticsMath.numOrNull
import com.ayuvo.health.data.analytics.engine.AnalyticsMath.obj
import com.ayuvo.health.data.analytics.engine.AnalyticsMath.objOrNull
import com.ayuvo.health.data.analytics.engine.AnalyticsMath.percentile
import com.ayuvo.health.data.analytics.engine.AnalyticsMath.roundTo
import com.ayuvo.health.data.analytics.engine.AnalyticsMath.sampleSd
import com.ayuvo.health.data.analytics.engine.AnalyticsMath.sqrt
import com.ayuvo.health.data.analytics.engine.AnalyticsMath.str
import com.ayuvo.health.data.analytics.engine.AnalyticsMath.strOrNull
import com.ayuvo.health.data.analytics.engine.AnalyticsMath.windowDays
import kotlin.math.abs
import kotlin.math.floor

/**
 * Per-user ridge forecast with a temporal split and a deployment gate, its feature builder (schema v1), and the
 * structured Coach evidence object (reference `forecast`, `forecast_features`, `evidence`).
 */
object AnalyticsForecast {

    /** Solves A x = b for symmetric positive-definite A; null when A is not positive definite. */
    fun choleskySolve(a: Array<DoubleArray>, b: DoubleArray): DoubleArray? {
        val n = a.size
        val l = Array(n) { DoubleArray(n) }
        for (i in 0 until n) {
            for (j in 0..i) {
                var s = a[i][j]
                for (k in 0 until j) s -= l[i][k] * l[j][k]
                if (i == j) {
                    if (s <= 0) return null
                    l[i][j] = sqrt(s)
                } else {
                    l[i][j] = s / l[j][j]
                }
            }
        }
        val y = DoubleArray(n)
        for (i in 0 until n) {
            var s = b[i]
            for (k in 0 until i) s -= l[i][k] * y[k]
            y[i] = s / l[i][i]
        }
        val x = DoubleArray(n)
        for (i in n - 1 downTo 0) {
            var s = y[i]
            for (k in i + 1 until n) s -= l[k][i] * x[k]
            x[i] = s / l[i][i]
        }
        return x
    }

    class Row(val day: String, val x: Map<String, Double?>, val y: Double)
    class Norm(val median: Double, val mean: Double, val sd: Double, val indicator: Boolean)
    class Model(val norm: LinkedHashMap<String, Norm>, val cols: List<Pair<String, String>>, val beta: DoubleArray, val intercept: Double, val lambda: Double)

    private fun design(names: List<String>, fitRows: List<Row>): Pair<LinkedHashMap<String, Norm>, List<Pair<String, String>>> {
        val norm = LinkedHashMap<String, Norm>()
        for (f in names) {
            val vals = fitRows.mapNotNull { it.x[f] }
            if (vals.isEmpty()) continue
            val med = median(vals)
            val imputed = fitRows.map { it.x[f] ?: med }
            val mu = mean(imputed)
            val sd = if (imputed.size >= 2) sampleSd(imputed, mu) else 0.0
            if (sd == 0.0) continue
            norm[f] = Norm(med, mu, sd, vals.size < fitRows.size)
        }
        val cols = ArrayList<Pair<String, String>>()
        for (f in names) {
            val n = norm[f] ?: continue
            cols += f to "value"
            if (n.indicator) cols += f to "missing"
        }
        return norm to cols
    }

    private fun vector(x: Map<String, Double?>, norm: Map<String, Norm>, cols: List<Pair<String, String>>): DoubleArray =
        DoubleArray(cols.size) { i ->
            val (f, kind) = cols[i]
            val raw = x[f]
            if (kind == "missing") (if (raw == null) 1.0 else 0.0)
            else {
                val n = norm.getValue(f)
                ((raw ?: n.median) - n.mean) / n.sd
            }
        }

    private fun fit(rows: List<Row>, names: List<String>, lam: Double): Model? {
        val (norm, cols) = design(names, rows)
        val xs = rows.map { vector(it.x, norm, cols) }
        val yMean = mean(rows.map { it.y })
        val p = cols.size
        val a = Array(p) { DoubleArray(p) }
        val b = DoubleArray(p)
        for ((x, r) in xs.zip(rows)) {
            val yc = r.y - yMean
            for (i in 0 until p) {
                b[i] += x[i] * yc
                for (j in 0 until p) a[i][j] += x[i] * x[j]
            }
        }
        for (i in 0 until p) a[i][i] += lam
        val beta = if (p > 0) choleskySolve(a, b) ?: return null else DoubleArray(0)
        return Model(norm, cols, beta, yMean, lam)
    }

    private fun predict(model: Model, x: Map<String, Double?>): Double {
        val v = vector(x, model.norm, model.cols)
        var s = model.intercept
        for (i in model.beta.indices) s += model.beta[i] * v[i]
        return s
    }

    private class Metrics(val mae: Double, val rmse: Double, val bias: Double, val r2: Double?)

    private fun metrics(preds: List<Double>, ys: List<Double>): Metrics {
        val n = ys.size
        var ae = 0.0
        var se = 0.0
        var bias = 0.0
        for (k in ys.indices) {
            val p = preds[k]
            val y = ys[k]
            ae += abs(p - y)
            se += (p - y) * (p - y)
            bias += p - y
        }
        val my = mean(ys)
        var sst = 0.0
        for (y in ys) sst += (y - my) * (y - my)
        return Metrics(ae / n, sqrt(se / n), bias / n, if (sst > 0) 1.0 - se / sst else null)
    }

    /** `forecast`: features {day: {name: value?}}, target series, as_of. */
    fun forecast(target: String, features: Map<String, Map<String, Double?>>, targetSeries: Map<String, Double>, asOf: String, cfg: AnalyticsConfig): JMap {
        val fc = cfg.root.obj("forecast")
        val names = fc.list("features").map { it as String }
        val rows = ArrayList<Row>()
        for (d in features.keys.sorted()) {
            val nxt = addDays(d, 1)
            val y = targetSeries[nxt]
            if (nxt <= asOf && y != null) rows += Row(d, features.getValue(d), y)
        }
        val out = linkedMapOf<String, Any?>(
            "model_id" to fc.str("algorithm_id") + "." + target, "model_version" to fc["model_version"],
            "feature_schema_version" to fc["feature_schema_version"], "target" to target, "status" to "INSUFFICIENT_HISTORY",
            "n_rows" to rows.size, "n_train" to 0, "n_val" to 0, "n_test" to 0, "lambda" to null, "train_start" to null,
            "train_end" to null, "val_start" to null, "val_end" to null, "test_start" to null, "test_end" to null,
            "features_used" to emptyList<String>(), "coefficients" to emptyMap<String, Any?>(), "intercept" to null,
            "normalization" to emptyMap<String, Any?>(), "metrics" to null, "baselines" to null, "deployed" to false,
            "prediction" to null, "interval_low" to null, "interval_high" to null, "classification" to "ML_PREDICTED"
        )
        val n = rows.size
        if (n < fc.int("min_rows")) return out
        val split = fc.list("split").map { (it as Number).toDouble() }
        val nTr = floor(split[0] * n).toInt()
        val nVa = floor(split[1] * n).toInt()
        val nTe = n - nTr - nVa
        if (nTe < fc.int("min_test_rows")) return out
        val tr = rows.subList(0, nTr)
        val va = rows.subList(nTr, nTr + nVa)
        val te = rows.subList(nTr + nVa, n)
        var best: Model? = null
        var bestMae: Double? = null
        for (lamV in fc.list("lambdas")) {
            val m = fit(tr, names, (lamV as Number).toDouble()) ?: continue
            val mae = metrics(va.map { predict(m, it.x) }, va.map { it.y }).mae
            if (bestMae == null || mae <= bestMae) {
                best = m
                bestMae = mae
            }
        }
        if (best == null) {
            out["status"] = "INVALID_INPUT"
            return out
        }
        val lam = best.lambda
        val resid = va.map { abs(predict(best, it.x) - it.y) }.sorted()
        val half = percentile(resid, fc.num("interval_percentile"))
        val m2 = fit(tr + va, names, lam)!!
        val preds = te.map { predict(m2, it.x) }
        val ys = te.map { it.y }
        val met = metrics(preds, ys)
        var covered = 0
        for (k in ys.indices) if (abs(preds[k] - ys[k]) <= half) covered += 1
        val coverage80 = covered / ys.size.toDouble()
        val persist = ArrayList<Double?>()
        val med28 = ArrayList<Double?>()
        val bDays = fc.int("baseline_median_days")
        for (r in te) {
            val win = windowDays(r.day, -(bDays - 1), 0)
            val prev = win.mapNotNull { targetSeries[it] }
            var last: Double? = null
            for (d in win) targetSeries[d]?.let { last = it }
            persist += last
            med28 += if (prev.isNotEmpty()) median(prev) else null
        }
        fun baseMae(ps: List<Double?>): Double? {
            val pairs = ps.zip(ys).filter { it.first != null }
            return if (pairs.isNotEmpty()) metrics(pairs.map { it.first!! }, pairs.map { it.second }).mae else null
        }
        val bP = baseMae(persist)
        val bM = baseMae(med28)
        val refs = listOfNotNull(bP, bM)
        val deployed = refs.isNotEmpty() && met.mae <= (1.0 - fc.num("min_improvement")) * refs.min()
        val final = fit(rows, names, lam)!!
        val pred = features[asOf]?.let { predict(final, it) }
        out["status"] = if (deployed) "VALID" else "LOW_CONFIDENCE"
        out["n_train"] = nTr
        out["n_val"] = nVa
        out["n_test"] = nTe
        out["lambda"] = lam
        out["train_start"] = tr.first().day
        out["train_end"] = tr.last().day
        out["val_start"] = va.first().day
        out["val_end"] = va.last().day
        out["test_start"] = te.first().day
        out["test_end"] = te.last().day
        out["features_used"] = names.filter { it in final.norm }
        out["coefficients"] = LinkedHashMap<String, Any?>().also { m ->
            for (i in final.cols.indices) m["${final.cols[i].first}:${final.cols[i].second}"] = roundTo(final.beta[i], 6)
        }
        out["intercept"] = roundTo(final.intercept, 6)
        out["normalization"] = LinkedHashMap<String, Any?>().also { m ->
            for ((f, v) in final.norm) m[f] = linkedMapOf(
                "median" to roundTo(v.median, 6), "mean" to roundTo(v.mean, 6), "sd" to roundTo(v.sd, 6), "indicator" to v.indicator
            )
        }
        out["metrics"] = linkedMapOf(
            "mae" to roundTo(met.mae, 4), "rmse" to roundTo(met.rmse, 4), "bias" to roundTo(met.bias, 4),
            "r2" to roundTo(met.r2, 4), "coverage80" to roundTo(coverage80, 4)
        )
        out["baselines"] = linkedMapOf("persistence_mae" to roundTo(bP, 4), "median28_mae" to roundTo(bM, 4))
        out["deployed"] = deployed
        out["prediction"] = roundTo(pred, 2)
        out["interval_low"] = pred?.let { roundTo(it - half, 2) }
        out["interval_high"] = pred?.let { roundTo(it + half, 2) }
        return out
    }

    /** `forecast_features`: one feature row (schema v1) per day from..to; missing stays null. */
    @Suppress("UNCHECKED_CAST")
    fun forecastFeatures(inputs: JMap, from: String, to: String, cfg: AnalyticsConfig): LinkedHashMap<String, Map<String, Double?>> {
        val series = (inputs["series"] as? Map<String, Any?>) ?: emptyMap()
        val nights = AnalyticsHealth.validNights(inputs["nights"], cfg)
        val nutrition = (inputs["nutrition"] as? Map<String, Any?>) ?: emptyMap()
        val tz = inputs.strOrNull("time_zone") ?: "UTC"
        val workouts = inputs.list("workouts")
        val daysLoad = AnalyticsHealth.loadDays(inputs.maps("workouts"), tz)
        val parsed = HashMap<String, Map<String, Double>>()
        fun s(f: String) = parsed.getOrPut(f) { seriesOf(series[f]) }
        val out = LinkedHashMap<String, Map<String, Double?>>()
        var d = from
        while (d <= to) {
            val row = LinkedHashMap<String, Double?>()
            for (f in listOf("hrv", "resting_heart_rate", "steps", "active_energy", "respiratory_rate", "wrist_temperature", "weight")) {
                row[f] = s(f)[d]
            }
            for (f in listOf("hrv", "resting_heart_rate")) {
                val m = cfg.root.obj("metrics").obj(f)
                val b = robust(s(f), d, 28, cfg.root.obj("baseline").obj("min_points").int("28"), m.num("spread_floor"), cfg)
                row[f + "_z"] = b.z?.let { roundTo(it, 6) }
            }
            val night = nights[d]
            row["sleep_duration"] = night?.num("asleep_min")
            row["sleep_efficiency"] = night?.numOrNull("efficiency")
            row["sleep_midpoint"] = night?.numOrNull("midpoint_clock")
            val ld = AnalyticsHealth.load(mapOf("workouts" to workouts, "day" to d, "time_zone" to tz, "tracking" to true), cfg)
            val pm = ld["primary_method"] as String?
            val pmOut = pm?.let { (ld["methods"] as JMap)[it] as JMap }
            row["load_acute"] = pmOut?.numOrNull("acute")
            row["load_chronic"] = pmOut?.numOrNull("chronic")
            row["load_ratio"] = pmOut?.numOrNull("ratio")
            row["workout_minutes"] = daysLoad[d]?.minutes ?: (if (pm != null) 0.0 else null)
            val food = nutrition[d] as? JMap
            row["energy_intake"] = food?.numOrNull("calories")
            row["protein_g"] = food?.numOrNull("protein_g")
            out[d] = row
            d = addDays(d, 1)
        }
        return out
    }

    // -- Coach evidence ------------------------------------------------------------------------------

    @Suppress("UNCHECKED_CAST")
    fun evidence(inp: JMap, cfg: AnalyticsConfig): JMap {
        val items = ArrayList<Any?>()
        inp.objOrNull("recovery")?.let { r ->
            val item = linkedMapOf<String, Any?>(
                "metric" to "recovery_indicator", "status" to r["status"],
                "algorithm" to "${r.str("algorithm_id")}@${(r["algorithm_version"] as Number).toInt()}",
                "classification" to r["classification"], "confidence" to r["confidence"]
            )
            if (r["score"] != null) {
                item["score"] = r["score"]
                item["label"] = r["label"]
                item["drivers"] = r.maps("drivers").map { d ->
                    linkedMapOf("metric" to d["id"], "direction" to d["direction"], "value" to d["value"], "baseline" to d["baseline"],
                        "z" to d["z"], "unit" to d["unit"])
                }
                item["warnings"] = r.maps("warnings").map { "${it["code"]}:${it["metric"]}" }
            }
            items += item
        }
        inp.objOrNull("anomaly")?.let { a ->
            items += linkedMapOf(
                "metric" to "multi_signal_deviation", "status" to a["status"], "state" to a["state"],
                "algorithm" to "${a.str("algorithm_id")}@${(a["algorithm_version"] as Number).toInt()}",
                "classification" to a["classification"], "confidence" to a["confidence"], "persistent" to a["persistent"],
                "signals" to a.maps("signals").map { s -> linkedMapOf("metric" to s["id"], "z" to s["z"], "flagged" to s["flagged"]) }
            )
        }
        inp.objOrNull("hrv")?.let { h ->
            val b = h.obj("baseline")
            items += linkedMapOf(
                "metric" to "hrv", "kind" to h["kind"], "status" to b["status"], "value" to b["value"], "baseline" to b["median"],
                "z" to b["z"], "trend" to h.obj("trend")["label"], "classification" to h["classification"],
                "confidence" to b["confidence"], "unit" to "ms"
            )
        }
        inp.objOrNull("sleep")?.let { s ->
            items += linkedMapOf(
                "metric" to "sleep", "status" to s["status"], "asleep_min" to s["asleep_min"], "need_min" to s["need_min"],
                "need_source" to s["need_source"], "debt_min" to s["debt_min"], "efficiency" to s["efficiency"],
                "bedtime_sd" to s["bedtime_sd"], "confidence" to s["confidence"]
            )
        }
        inp.objOrNull("load")?.let { ld ->
            val pm = ld.strOrNull("primary_method")
            val m = pm?.let { ld.obj("methods").obj(it) }
            items += linkedMapOf(
                "metric" to "training_load", "status" to ld["status"], "state" to ld["state"], "method" to pm,
                "acute" to m?.get("acute"), "chronic" to m?.get("chronic"), "ratio" to m?.get("ratio"),
                "classification" to ld["classification"]
            )
        }
        return linkedMapOf("evidence_version" to 1, "items" to items)
    }
}
