package com.ayuvo.health.vitals.engine

import com.ayuvo.health.vitals.engine.VitalsMath.TWO_PI
import com.ayuvo.health.vitals.engine.VitalsMath.clamp
import com.ayuvo.health.vitals.engine.VitalsMath.ifloor
import com.ayuvo.health.vitals.engine.VitalsMath.mean
import com.ayuvo.health.vitals.engine.VitalsMath.median
import com.ayuvo.health.vitals.engine.VitalsMath.oddWindow
import com.ayuvo.health.vitals.engine.VitalsMath.pearson
import com.ayuvo.health.vitals.engine.VitalsMath.roundTo
import com.ayuvo.health.vitals.engine.VitalsMath.sampleStd
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** One systolic peak: [tMs] relative to sample 0 with parabolic sub-sample timing. */
class VitalsPeak(
    val i: Int,
    val tMs: Double,
    val amp: Double,
    val footI: Int,
    val foot: Double,
    var corr: Double?,
    val masked: Boolean
)

/** `ibi_clean` output. [ibiMs] and [ibiTMs] are rounded to 0.1 ms, as the reference stores them. */
class VitalsIbi(
    val ibiMs: DoubleArray,
    val ibiTMs: DoubleArray,
    val ibiQuality: DoubleArray,
    val accepted: BooleanArray,
    val acceptedCount: Int,
    val acceptedFraction: Double
)

class VitalsHrvTime(val rmssd: Double, val sdnn: Double, val meanNn: Double, val pnn50: Double, val n: Int)
class VitalsHrvFreq(val lfHf: Double, val lfNu: Double, val hfNu: Double)
class VitalsResp(val value: Double?, val estimates: List<Double>, val spread: Double?)

/** Pulses, IBI, HRV and respiration of `scripts/vitals_reference.py`. */
object VitalsBeats {

    /**
     * Elgendi two-moving-average systolic peak detector with parabolic sub-sample timing, feet, and per-beat
     * template correlation (`pulse_detect`).
     */
    fun pulseDetect(x: DoubleArray, fs: Double, mask: BooleanArray, cfg: VitalsConfig, hrHintBpm: Double? = null): List<VitalsPeak> {
        val sig = cfg.signal
        val n = x.size
        val y = DoubleArray(n) { if (x[it] > 0.0) x[it] * x[it] else 0.0 }
        val maPeak = VitalsSignal.movingAverage(y, oddWindow(sig.peakWindowS, fs))
        val maBeat = VitalsSignal.movingAverage(y, oddWindow(sig.beatWindowS, fs))
        val offset = sig.peakOffset * mean(y)
        val w1 = oddWindow(sig.peakWindowS, fs)
        val cands = ArrayList<Int>()
        var i = 0
        while (i < n) {
            if (maPeak[i] > maBeat[i] + offset) {
                var k = i
                while (k < n && maPeak[k] > maBeat[k] + offset) k += 1
                if (k - i >= w1) {
                    var best = i
                    for (m in i until k) if (x[m] > x[best]) best = m
                    cands.add(best)
                }
                i = k
            } else {
                i += 1
            }
        }
        var refractory = sig.peakRefractoryS * fs
        if (hrHintBpm != null && hrHintBpm > 0.0) {
            val adaptive = sig.refractoryFraction * 60.0 / hrHintBpm * fs
            if (adaptive > refractory) refractory = adaptive
        }
        val idx = ArrayList<Int>()
        for (c in cands) {
            if (idx.isNotEmpty() && (c - idx[idx.size - 1]) < refractory) {
                if (x[c] > x[idx[idx.size - 1]]) idx[idx.size - 1] = c
            } else {
                idx.add(c)
            }
        }
        val peaks = ArrayList<VitalsPeak>()
        for (k in idx.indices) {
            val p = idx[k]
            var delta = 0.0
            var amp = x[p]
            if (0 < p && p < n - 1) {
                val a = x[p - 1]
                val b = x[p]
                val c = x[p + 1]
                val den = a - 2.0 * b + c
                if (den != 0.0) {
                    delta = clamp(0.5 * (a - c) / den, -0.5, 0.5)
                    amp = b - 0.25 * (a - c) * delta
                }
            }
            var lo = if (k > 0) idx[k - 1] else p - ifloor(0.6 * fs)
            if (lo < 0) lo = 0
            var footI = p
            for (m in lo..p) if (x[m] < x[footI]) footI = m
            peaks.add(VitalsPeak(p, (p + delta) * 1000.0 / fs, amp, footI, x[footI], null, mask[p]))
        }
        if (peaks.size >= 3) {
            val diffs = ArrayList<Double>()
            for (k in 1 until idx.size) diffs.add((idx[k] - idx[k - 1]).toDouble())
            val med = median(diffs)
            val before = ifloor(0.3 * med + 0.5)
            val after = ifloor(0.6 * med + 0.5)
            val segs = ArrayList<DoubleArray>()
            val owners = ArrayList<Int>()
            for (k in peaks.indices) {
                val p = peaks[k].i
                if (p - before >= 0 && p + after < n) {
                    segs.add(x.copyOfRange(p - before, p + after + 1))
                    owners.add(k)
                }
            }
            if (segs.size >= 2) {
                val length = before + after + 1
                val template = DoubleArray(length)
                for (j in 0 until length) {
                    var acc = 0.0
                    for (s in segs) acc += s[j]
                    template[j] = acc / segs.size
                }
                for (q in segs.indices) peaks[owners[q]].corr = pearson(segs[q], template)
            }
        }
        return peaks
    }

    /** IBIs between consecutive peaks with per-IBI quality (`ibi_clean`). */
    fun ibiClean(peaks: List<VitalsPeak>, cfg: VitalsConfig): VitalsIbi {
        val ib = cfg.ibi
        val m0 = if (peaks.isEmpty()) 0 else peaks.size - 1
        val raw = DoubleArray(m0)
        val times = DoubleArray(m0)
        val baseQ = arrayOfNulls<Double>(m0)
        for (k in 1 until peaks.size) {
            val a = peaks[k - 1]
            val b = peaks[k]
            raw[k - 1] = b.tMs - a.tMs
            times[k - 1] = b.tMs
            val ca = a.corr ?: 0.8
            val cb = b.corr ?: 0.8
            val q = if (ca < cb) ca else cb
            val bad = a.masked || b.masked || q < ib.minTemplateCorr
            baseQ[k - 1] = if (bad) null else clamp(q, 0.0, 1.0)
        }
        val inRange = BooleanArray(m0) { ib.minMs <= raw[it] && raw[it] <= ib.maxMs }
        val quality = DoubleArray(m0)
        val accepted = BooleanArray(m0)
        val h = Math.floorDiv(ib.medianWindow, 2)
        for (k in 0 until m0) {
            var ok = inRange[k] && baseQ[k] != null
            if (ok) {
                val local = ArrayList<Double>()
                for (m in k - h..k + h) if (0 <= m && m < m0 && inRange[m]) local.add(raw[m])
                val med = median(local)
                if (abs(raw[k] - med) / med > ib.maxRelDeviation) ok = false
            }
            accepted[k] = ok
            quality[k] = if (ok) roundTo(baseQ[k]!!, 2) else 0.0
        }
        var count = 0
        for (a in accepted) if (a) count += 1
        return VitalsIbi(
            VitalsMath.roundList(raw, 1), VitalsMath.roundList(times, 1), quality, accepted, count,
            if (m0 > 0) roundTo(count.toDouble() / m0, 3) else 0.0
        )
    }

    fun hrvTime(ibiMs: DoubleArray, accepted: BooleanArray): VitalsHrvTime? {
        val acc = ArrayList<Double>()
        val diffs = ArrayList<Double>()
        for (k in ibiMs.indices) {
            if (accepted[k]) {
                acc.add(ibiMs[k])
                if (k > 0 && accepted[k - 1]) diffs.add(ibiMs[k] - ibiMs[k - 1])
            }
        }
        if (acc.size < 2 || diffs.isEmpty()) return null
        var sq = 0.0
        var over = 0
        for (d in diffs) {
            sq += d * d
            if (abs(d) > 50.0) over += 1
        }
        return VitalsHrvTime(sqrt(sq / diffs.size), sampleStd(acc)!!, mean(acc), 100.0 * over / diffs.size, acc.size)
    }

    fun lombScargle(tS: DoubleArray, y: DoubleArray, freqs: DoubleArray): DoubleArray {
        val power = DoubleArray(freqs.size)
        for (fi in freqs.indices) {
            val w = TWO_PI * freqs[fi]
            var s2 = 0.0
            var c2 = 0.0
            for (t in tS) {
                s2 += sin(2.0 * w * t)
                c2 += cos(2.0 * w * t)
            }
            val tau = atan2(s2, c2) / (2.0 * w)
            var yc = 0.0
            var ys = 0.0
            var cc = 0.0
            var ss = 0.0
            for (k in tS.indices) {
                val c = cos(w * (tS[k] - tau))
                val s = sin(w * (tS[k] - tau))
                yc += y[k] * c
                ys += y[k] * s
                cc += c * c
                ss += s * s
            }
            var p = 0.0
            if (cc > 0.0) p += yc * yc / cc
            if (ss > 0.0) p += ys * ys / ss
            power[fi] = 0.5 * p
        }
        return power
    }

    fun hrvFreq(ibiMs: DoubleArray, ibiTMs: DoubleArray, accepted: BooleanArray, cfg: VitalsConfig): VitalsHrvFreq? {
        val hv = cfg.hrv
        val tS = ArrayList<Double>()
        val vals = ArrayList<Double>()
        for (k in ibiMs.indices) {
            if (accepted[k]) {
                tS.add(ibiTMs[k] / 1000.0)
                vals.add(ibiMs[k])
            }
        }
        if (vals.size < hv.minBeats) return null
        val m = mean(vals)
        val y = DoubleArray(vals.size) { vals[it] - m }
        val freqs = VitalsSignal.freqGrid(hv.lfBand[0], hv.hfBand[1], hv.freqGridStepHz)
        val power = lombScargle(tS.toDoubleArray(), y, freqs)
        var lf = 0.0
        var hf = 0.0
        for (i in freqs.indices) {
            if (freqs[i] < hv.lfBand[1] - 1e-9) lf += power[i] else hf += power[i]
        }
        if (lf + hf <= 0.0 || hf <= 0.0) return null
        return VitalsHrvFreq(lf / hf, 100.0 * lf / (lf + hf), 100.0 * hf / (lf + hf))
    }

    // -- Respiration ------------------------------------------------------------------------------

    fun respFromSeries(tMs: DoubleArray, values: DoubleArray, cfg: VitalsConfig): Double? {
        val rp = cfg.respiration
        if (values.size < 4) return null
        val fs = rp.resampleHz
        val valid = BooleanArray(values.size) { true }
        val grid = VitalsSignal.resampleUniform(tMs, values, valid, fs, 1e12).values
        if (grid.size < 8) return null
        val m = mean(grid)
        val y = DoubleArray(grid.size)
        for (k in grid.indices) {
            val win = 0.5 - 0.5 * cos(TWO_PI * k / (grid.size - 1))
            y[k] = (grid[k] - m) * win
        }
        val freqs = VitalsSignal.freqGrid(rp.bandHz[0], rp.bandHz[1], rp.gridStepHz)
        val power = DoubleArray(freqs.size)
        for (fi in freqs.indices) {
            val f = freqs[fi]
            var re = 0.0
            var im = 0.0
            for (k in y.indices) {
                val ang = TWO_PI * f * k / fs
                re += y[k] * cos(ang)
                im += y[k] * sin(ang)
            }
            power[fi] = re * re + im * im
        }
        return VitalsSignal.spectralPeak(freqs, power) * 60.0
    }

    /** Smart fusion (Karlen 2013): the estimates must agree within agreement_per_min (sample SD), else unavailable. */
    fun respRate(series: List<Pair<DoubleArray, DoubleArray>>, cfg: VitalsConfig): VitalsResp {
        val est = ArrayList<Double>()
        for ((t, v) in series) respFromSeries(t, v, cfg)?.let { est.add(it) }
        if (est.size < 2) return VitalsResp(null, VitalsMath.roundList(est, 1), null)
        val spread = sampleStd(est)!!
        val value = if (spread <= cfg.respiration.agreementPerMin) mean(est) else null
        return VitalsResp(value, VitalsMath.roundList(est, 1), spread)
    }
}
