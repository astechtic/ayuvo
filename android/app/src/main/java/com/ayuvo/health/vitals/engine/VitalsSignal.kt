package com.ayuvo.health.vitals.engine

import com.ayuvo.health.vitals.engine.VitalsMath.TWO_PI
import com.ayuvo.health.vitals.engine.VitalsMath.clamp
import com.ayuvo.health.vitals.engine.VitalsMath.ifloor
import com.ayuvo.health.vitals.engine.VitalsMath.oddWindow
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

/** Resampling, masking, filtering and spectra of `scripts/vitals_reference.py`. */
object VitalsSignal {

    class Resampled(val values: DoubleArray, val mask: BooleanArray)

    /**
     * Linear interpolation of an irregular frame series onto `t0 + i*1000/fs`. A grid sample is masked when either
     * neighbouring frame is invalid or the frames around it are more than [maxGapMs] apart.
     */
    fun resampleUniform(tMs: DoubleArray, values: DoubleArray, valid: BooleanArray, fs: Double, maxGapMs: Double): Resampled {
        val n = ifloor((tMs[tMs.size - 1] - tMs[0]) * fs / 1000.0) + 1
        val out = DoubleArray(n)
        val mask = BooleanArray(n)
        var j = 0
        for (i in 0 until n) {
            val ti = tMs[0] + i * 1000.0 / fs
            while (j + 1 < tMs.size - 1 && tMs[j + 1] < ti) j += 1
            if (j + 1 >= tMs.size) {
                out[i] = values[j]
                mask[i] = !valid[j]
                continue
            }
            val span = tMs[j + 1] - tMs[j]
            val frac = if (span <= 0.0) 0.0 else clamp((ti - tMs[j]) / span, 0.0, 1.0)
            out[i] = values[j] + (values[j + 1] - values[j]) * frac
            mask[i] = (!valid[j]) || (!valid[j + 1]) || span > maxGapMs
        }
        return Resampled(out, mask)
    }

    /** Replace masked samples by linear interpolation between the nearest valid neighbours (edges hold). */
    fun fillMasked(x: DoubleArray, mask: BooleanArray): DoubleArray {
        val n = x.size
        val out = x.copyOf()
        var last = -1
        var i = 0
        while (i < n) {
            if (!mask[i]) {
                last = i
                i += 1
                continue
            }
            var k = i
            while (k < n && mask[k]) k += 1
            for (m in i until k) {
                out[m] = if (last < 0 && k >= n) x[m]
                else if (last < 0) x[k]
                else if (k >= n) x[last]
                else x[last] + (x[k] - x[last]) * (m - last).toDouble() / (k - last).toDouble()
            }
            i = k
        }
        return out
    }

    /** Centred moving average with an odd window w, truncated at the edges (prefix sums). */
    fun movingAverage(x: DoubleArray, w: Int): DoubleArray {
        val n = x.size
        val prefix = DoubleArray(n + 1)
        for (i in 0 until n) prefix[i + 1] = prefix[i] + x[i]
        val h = Math.floorDiv(w, 2)
        val out = DoubleArray(n)
        for (i in 0 until n) {
            val lo = if (i - h > 0) i - h else 0
            val hi = if (i + h < n - 1) i + h else n - 1
            out[i] = (prefix[hi + 1] - prefix[lo]) / (hi - lo + 1).toDouble()
        }
        return out
    }

    /** x / moving-average(x) - 1. */
    fun normalize(x: DoubleArray, fs: Double, windowS: Double): DoubleArray {
        val ma = movingAverage(x, oddWindow(windowS, fs))
        return DoubleArray(x.size) { i -> if (ma[i] != 0.0) x[i] / ma[i] - 1.0 else 0.0 }
    }

    fun biquad(kind: String, fHz: Double, fs: Double): DoubleArray {
        val w0 = TWO_PI * fHz / fs
        val cw = cos(w0)
        val sw = sin(w0)
        val alpha = sw / (2.0 * 0.7071067811865476)
        val a0 = 1.0 + alpha
        val b = if (kind == "low") doubleArrayOf((1.0 - cw) / 2.0, 1.0 - cw, (1.0 - cw) / 2.0)
        else doubleArrayOf((1.0 + cw) / 2.0, -(1.0 + cw), (1.0 + cw) / 2.0)
        return doubleArrayOf(b[0] / a0, b[1] / a0, b[2] / a0, (-2.0 * cw) / a0, (1.0 - alpha) / a0)
    }

    private fun applyBiquad(x: DoubleArray, c: DoubleArray): DoubleArray {
        val out = DoubleArray(x.size)
        var x1 = 0.0
        var x2 = 0.0
        var y1 = 0.0
        var y2 = 0.0
        for (i in x.indices) {
            val v = x[i]
            val y = c[0] * v + c[1] * x1 + c[2] * x2 - c[3] * y1 - c[4] * y2
            x2 = x1; x1 = v
            y2 = y1; y1 = y
            out[i] = y
        }
        return out
    }

    /** Zero-phase Butterworth (RBJ biquad) high-pass + low-pass, forward and backward, odd-reflection padding. */
    fun bandpass(x: DoubleArray, fs: Double, loHz: Double, hiHz: Double): DoubleArray {
        val n = x.size
        if (n < 3) return x.copyOf()
        var pad = ifloor(fs + 0.5) * 3
        if (pad > n - 1) pad = n - 1
        val padded = DoubleArray(n + 2 * pad)
        var p = 0
        for (k in pad downTo 1) padded[p++] = 2.0 * x[0] - x[k]
        for (v in x) padded[p++] = v
        for (k in 1..pad) padded[p++] = 2.0 * x[n - 1] - x[n - 1 - k]
        val hp = biquad("high", loHz, fs)
        val lp = biquad("low", hiHz, fs)
        var y = applyBiquad(applyBiquad(padded, hp), lp)
        y.reverse()
        y = applyBiquad(applyBiquad(y, hp), lp)
        y.reverse()
        return y.copyOfRange(pad, pad + n)
    }

    /** Non-overlapping windows whose RMS exceeds artifact_rms_factor x the lower-quartile window RMS are masked. */
    fun amplitudeMask(x: DoubleArray, fs: Double, cfg: VitalsConfig): BooleanArray {
        val sig = cfg.signal
        val n = x.size
        val w = ifloor(sig.artifactWindowS * fs + 0.5)
        val rms = ArrayList<Double>()
        var start = 0
        while (start < n) {
            val end = if (start + w < n) start + w else n
            var acc = 0.0
            for (k in start until end) acc += x[k] * x[k]
            rms.add(sqrt(acc / (end - start).toDouble()))
            start += w
        }
        val ordered = VitalsMath.sortedPy(rms)
        val ref = ordered[ifloor(0.25 * (ordered.size - 1))]
        return BooleanArray(n) { k -> ref > 0.0 && rms[Math.floorDiv(k, w)] > sig.artifactRmsFactor * ref }
    }

    fun unionMask(a: BooleanArray, b: BooleanArray): BooleanArray = BooleanArray(a.size) { a[it] || b[it] }

    fun maskedShare(mask: BooleanArray): Double {
        var count = 0
        for (m in mask) if (m) count += 1
        return count.toDouble() / mask.size
    }

    fun preprocess(x: DoubleArray, fs: Double, cfg: VitalsConfig, invert: Boolean): DoubleArray {
        val sig = cfg.signal
        val norm = normalize(x, fs, sig.detrendWindowS)
        if (invert) for (i in norm.indices) norm[i] = -norm[i]
        return bandpass(norm, fs, sig.hrBandHz[0], sig.hrBandHz[1])
    }

    // -- Spectrum, SNR, heart rate ----------------------------------------------------------------

    fun freqGrid(lo: Double, hi: Double, step: Double): DoubleArray {
        val count = ifloor((hi - lo) / step + 1e-9) + 1
        return DoubleArray(count) { k -> lo + k * step }
    }

    fun welch(x: DoubleArray, fs: Double, freqs: DoubleArray, segmentS: Double, overlap: Double): DoubleArray {
        val n = x.size
        var seg = ifloor(segmentS * fs + 0.5)
        if (seg > n) seg = n
        var hop = ifloor(seg * (1.0 - overlap) + 0.5)
        if (hop < 1) hop = 1
        val window = DoubleArray(seg) { k -> if (seg > 1) 0.5 - 0.5 * cos(TWO_PI * k / (seg - 1)) else 1.0 }
        // cos/sin of TWO_PI*f*k/fs depend only on (f, k): computed once, same values as the per-segment loop.
        val cosT = Array(freqs.size) { DoubleArray(seg) }
        val sinT = Array(freqs.size) { DoubleArray(seg) }
        for (fi in freqs.indices) {
            val tf = TWO_PI * freqs[fi]
            for (k in 0 until seg) {
                val ang = tf * k / fs
                cosT[fi][k] = cos(ang)
                sinT[fi][k] = sin(ang)
            }
        }
        val power = DoubleArray(freqs.size)
        var segments = 0
        var start = 0
        val y = DoubleArray(seg)
        while (start + seg <= n) {
            var t = 0.0
            for (k in start until start + seg) t += x[k]
            val m = t / seg
            for (k in 0 until seg) y[k] = (x[start + k] - m) * window[k]
            for (fi in freqs.indices) {
                var re = 0.0
                var im = 0.0
                val ct = cosT[fi]
                val st = sinT[fi]
                for (k in 0 until seg) {
                    re += y[k] * ct[k]
                    im += y[k] * st[k]
                }
                power[fi] += re * re + im * im
            }
            segments += 1
            start += hop
        }
        for (fi in power.indices) power[fi] = power[fi] / segments
        return power
    }

    fun spectralPeak(freqs: DoubleArray, power: DoubleArray): Double {
        var best = 0
        for (i in 1 until power.size) if (power[i] > power[best]) best = i
        var f = freqs[best]
        if (0 < best && best < power.size - 1) {
            val a = power[best - 1]
            val b = power[best]
            val c = power[best + 1]
            val den = a - 2.0 * b + c
            if (den != 0.0) f = f + 0.5 * (a - c) / den * (freqs[1] - freqs[0])
        }
        return f
    }

    fun snrDb(freqs: DoubleArray, power: DoubleArray, f0: Double): Double {
        var sig = 0.0
        var noise = 0.0
        for (i in freqs.indices) {
            val f = freqs[i]
            if (abs(f - f0) <= 0.1 || abs(f - 2.0 * f0) <= 0.2) sig += power[i] else noise += power[i]
        }
        if (sig <= 0.0) return -30.0
        if (noise <= 0.0) return 30.0
        return clamp(10.0 * log10(sig / noise), -30.0, 30.0)
    }

    class Spectrum(val hrBpm: Double, val snrDb: Double)

    fun hrSpectrum(x: DoubleArray, fs: Double, cfg: VitalsConfig): Spectrum {
        val sig = cfg.signal
        val freqs = freqGrid(sig.hrBandHz[0], sig.hrBandHz[1], sig.spectrumStepHz)
        val power = welch(x, fs, freqs, sig.welchSegmentS, sig.welchOverlap)
        val f0 = spectralPeak(freqs, power)
        return Spectrum(f0 * 60.0, snrDb(freqs, power, f0))
    }
}
