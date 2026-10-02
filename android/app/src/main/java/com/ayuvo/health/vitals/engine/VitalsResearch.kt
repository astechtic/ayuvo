package com.ayuvo.health.vitals.engine

import com.ayuvo.health.vitals.engine.VitalsMath.clamp
import com.ayuvo.health.vitals.engine.VitalsMath.mean
import com.ayuvo.health.vitals.engine.VitalsMath.median
import com.ayuvo.health.vitals.engine.VitalsMath.roundTo
import com.ayuvo.health.vitals.engine.VitalsMath.std
import kotlin.math.abs
import kotlin.math.sqrt

/** A personal SpO2 calibration pair on this device model: the scan's ratio and the oximeter reading. */
data class VitalsSpo2Calibration(val ratio: Double, val spo2: Double)

/** A cuff calibration: [features] are `bp_features` of the paired finger scan (null when it had none). */
data class VitalsBpCalibration(
    val tMs: Double,
    val scanGapMin: Double,
    val features: Map<String, Double>?,
    val sbp: Double,
    val dbp: Double
)

class VitalsSpo2Estimate(val value: Double?, val confidence: Double?, val reason: String?)

class VitalsBpEstimate(val sbp: Double?, val dbp: Double?, val confidence: Double?, val reason: String?) {
    fun toJson() = VitalsJson.obj("sbp" to sbp, "dbp" to dbp, "confidence" to confidence, "reason" to reason)
}

class VitalsLinearFit(val a: Double, val b: Double, val rmse: Double)

/** Experimental SpO2 and research blood pressure (Milestone 4) of `scripts/vitals_reference.py`. */
object VitalsResearch {

    /** Median over beats of (AC/DC)_ch1 / (AC/DC)_ch2, AC/DC from the normalised band-passed channels. */
    fun spo2Ratio(channels: Map<String, DoubleArray>, peaks: List<VitalsPeak>, fs: Double, cfg: VitalsConfig): Double? {
        val xs = cfg.research.spo2Channels.map { VitalsSignal.preprocess(channels.getValue(it), fs, cfg, true) }
        val ratios = ArrayList<Double>()
        for (p in peaks) {
            if (p.masked || p.footI == p.i) continue
            val a1 = xs[0][p.i] - xs[0][p.footI]
            val a2 = xs[1][p.i] - xs[1][p.footI]
            if (a1 > 0.0 && a2 > 0.0) ratios.add(a1 / a2)
        }
        if (ratios.size < 5) return null
        return median(ratios)
    }

    fun linearFit(xs: List<Double>, ys: List<Double>): VitalsLinearFit? {
        val mx = mean(xs)
        val my = mean(ys)
        var sxx = 0.0
        var sxy = 0.0
        for (i in xs.indices) {
            sxx += (xs[i] - mx) * (xs[i] - mx)
            sxy += (xs[i] - mx) * (ys[i] - my)
        }
        if (sxx < 1e-9) return null
        val b = sxy / sxx
        val a = my - b * mx
        var res = 0.0
        for (i in xs.indices) {
            val e = ys[i] - (a + b * xs[i])
            res += e * e
        }
        return VitalsLinearFit(a, b, sqrt(res / xs.size))
    }

    /** Unavailable unless a personal calibration exists, the ratio lies inside its range and the result is plausible. */
    fun spo2Estimate(ratio: Double?, quality: Double, calibrations: List<VitalsSpo2Calibration>, cfg: VitalsConfig): VitalsSpo2Estimate {
        val rs = cfg.research
        if (ratio == null) return VitalsSpo2Estimate(null, null, "low_quality")
        if (calibrations.size < rs.spo2MinCalibrations) return VitalsSpo2Estimate(null, null, "needs_calibration")
        val xs = calibrations.map { it.ratio }
        val ys = calibrations.map { it.spo2 }
        val fit = linearFit(xs, ys) ?: return VitalsSpo2Estimate(null, null, "needs_calibration")
        val lo = pyMin(xs) - rs.spo2MaxRMargin
        val hi = pyMax(xs) + rs.spo2MaxRMargin
        if (!(lo <= ratio && ratio <= hi)) return VitalsSpo2Estimate(null, null, "outside_calibration")
        val value = fit.a + fit.b * ratio
        if (!(rs.spo2Range[0] <= value && value <= rs.spo2Range[1])) return VitalsSpo2Estimate(null, null, "outside_calibration")
        val conf = quality / 100.0 * clamp(1.0 - fit.rmse / 3.0, 0.0, 1.0) * clamp(calibrations.size / 6.0, 0.0, 1.0)
        if (conf < 0.5) return VitalsSpo2Estimate(null, null, "low_quality")
        return VitalsSpo2Estimate(value, conf, null)
    }

    /**
     * Median pulse morphology over clean beats: rise, decay, width at 50 % and 25 % of the pulse amplitude, plus heart
     * rate (ms / bpm). null when fewer than 5 usable beats. Keys in `research.bp_features` order of the reference.
     */
    fun bpFeatures(x: DoubleArray, peaks: List<VitalsPeak>, fs: Double, hr: Double, cfg: VitalsConfig): LinkedHashMap<String, Double>? {
        val rise = ArrayList<Double>()
        val decay = ArrayList<Double>()
        val w50 = ArrayList<Double>()
        val w25 = ArrayList<Double>()
        for (k in 0 until peaks.size - 1) {
            val p = peaks[k]
            val nxt = peaks[k + 1]
            val corr = p.corr
            if (p.masked || nxt.masked || corr == null || corr < cfg.ibi.minTemplateCorr) continue
            val f1 = p.footI
            val pi = p.i
            val f2 = nxt.footI
            if (!(f1 < pi && pi < f2)) continue
            val amp = x[pi] - x[f1]
            if (amp <= 0.0) continue
            rise.add((pi - f1) * 1000.0 / fs)
            decay.add((f2 - pi) * 1000.0 / fs)
            var c50 = 0
            var c25 = 0
            for (m in f1..f2) {
                if (x[m] >= x[f1] + 0.5 * amp) c50 += 1
                if (x[m] >= x[f1] + 0.25 * amp) c25 += 1
            }
            w50.add(c50 * 1000.0 / fs)
            w25.add(c25 * 1000.0 / fs)
        }
        if (rise.size < 5) return null
        return linkedMapOf(
            "rise_ms" to roundTo(median(rise), 1), "decay_ms" to roundTo(median(decay), 1),
            "width50_ms" to roundTo(median(w50), 1), "width25_ms" to roundTo(median(w25), 1),
            "hr" to roundTo(hr, 1)
        )
    }

    /** Gaussian elimination with partial pivoting; null when singular. */
    fun solveLinear(a: Array<DoubleArray>, b: DoubleArray): DoubleArray? {
        val n = b.size
        val m = Array(n) { i -> DoubleArray(n + 1) { j -> if (j < n) a[i][j] else b[i] } }
        for (col in 0 until n) {
            var piv = col
            for (r in col + 1 until n) if (abs(m[r][col]) > abs(m[piv][col])) piv = r
            if (abs(m[piv][col]) < 1e-12) return null
            val tmp = m[col]
            m[col] = m[piv]
            m[piv] = tmp
            for (r in col + 1 until n) {
                val f = m[r][col] / m[col][col]
                for (c in col..n) m[r][c] -= f * m[col][c]
            }
        }
        val out = DoubleArray(n)
        for (i in n - 1 downTo 0) {
            var acc = m[i][n]
            for (c in i + 1 until n) acc -= m[i][c] * out[c]
            out[i] = acc / m[i][i]
        }
        return out
    }

    /**
     * Per-user ridge regression from pulse morphology to cuff readings. Research only: unavailable unless at least
     * bp_min_calibrations recent calibrations exist.
     */
    fun bpResearch(
        features: Map<String, Double>?,
        calibrations: List<VitalsBpCalibration>,
        nowMs: Double,
        quality: Double,
        cfg: VitalsConfig
    ): VitalsBpEstimate {
        val rs = cfg.research
        val names = rs.bpFeatures
        fun complete(f: Map<String, Double>?) = f != null && names.all { f[it] != null }
        if (!complete(features)) return VitalsBpEstimate(null, null, null, "few_beats")
        features!!
        val maxAge = rs.bpCalibrationMaxAgeDays * 86400000.0
        val cals = ArrayList<VitalsBpCalibration>()
        for (c in calibrations) {
            if (c.features == null || c.scanGapMin > rs.bpCalibrationMaxGapMin) continue
            if (nowMs - c.tMs > maxAge) continue
            if (!complete(c.features)) continue
            cals.add(c)
        }
        if (cals.size < rs.bpMinCalibrations) return VitalsBpEstimate(null, null, null, "needs_calibration")
        val means = DoubleArray(names.size)
        val sds = DoubleArray(names.size)
        for ((j, name) in names.withIndex()) {
            val col = cals.map { it.features!!.getValue(name) }
            means[j] = mean(col)
            val s = std(col)
            sds[j] = if (s > 1e-6) s else 0.0
        }
        val z = cals.map { c ->
            DoubleArray(names.size) { j -> if (sds[j] > 0.0) (c.features!!.getValue(names[j]) - means[j]) / sds[j] else 0.0 }
        }
        val zc = DoubleArray(names.size)
        for (j in names.indices) {
            val zj = if (sds[j] > 0.0) (features.getValue(names[j]) - means[j]) / sds[j] else 0.0
            if (abs(zj) > rs.bpMaxAbsZ) return VitalsBpEstimate(null, null, null, "outside_calibration")
            zc[j] = zj
        }
        val preds = DoubleArray(2)
        for ((t, target) in listOf("sbp", "dbp").withIndex()) {
            val ys = cals.map { if (target == "sbp") it.sbp else it.dbp }
            val ym = mean(ys)
            val p = names.size
            val ata = Array(p) { DoubleArray(p) }
            val atb = DoubleArray(p)
            for (i in 0 until p) {
                for (j in 0 until p) {
                    var acc = 0.0
                    for (r in z.indices) acc += z[r][i] * z[r][j]
                    ata[i][j] = acc + (if (i == j) rs.bpRidgeLambda else 0.0)
                }
                var acc = 0.0
                for (r in z.indices) acc += z[r][i] * (ys[r] - ym)
                atb[i] = acc
            }
            val beta = solveLinear(ata, atb) ?: return VitalsBpEstimate(null, null, null, "needs_calibration")
            var pred = ym
            for (j in 0 until p) pred += beta[j] * zc[j]
            preds[t] = pred
        }
        val sbp = preds[0]
        val dbp = preds[1]
        if (!(70.0 <= sbp && sbp <= 200.0 && 40.0 <= dbp && dbp <= 130.0 && dbp < sbp)) {
            return VitalsBpEstimate(null, null, null, "outside_calibration")
        }
        val conf = clamp(cals.size / 10.0, 0.0, 0.5) * quality / 100.0
        return VitalsBpEstimate(roundTo(sbp, 0), roundTo(dbp, 0), conf, null)
    }

    /** Python `min` over floats (first minimum wins). */
    private fun pyMin(xs: List<Double>): Double {
        var m = xs[0]
        for (v in xs) if (v < m) m = v
        return m
    }

    private fun pyMax(xs: List<Double>): Double {
        var m = xs[0]
        for (v in xs) if (v > m) m = v
        return m
    }
}
