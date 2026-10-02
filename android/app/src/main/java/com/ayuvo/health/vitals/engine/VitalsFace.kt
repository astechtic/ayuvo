package com.ayuvo.health.vitals.engine

import com.ayuvo.health.vitals.engine.VitalsMath.ifloor
import com.ayuvo.health.vitals.engine.VitalsMath.mean
import com.ayuvo.health.vitals.engine.VitalsMath.median
import com.ayuvo.health.vitals.engine.VitalsMath.oddWindow
import com.ayuvo.health.vitals.engine.VitalsMath.pearson
import com.ayuvo.health.vitals.engine.VitalsMath.roundTo
import com.ayuvo.health.vitals.engine.VitalsMath.std
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Face rPPG frame encoding: parallel arrays plus `rois.<name>` = rows `[r, g, b, skin_frac]`
 * (docs/camera-vitals.md §2.1). ROI order is insertion order.
 */
class VitalsFaceFrames(
    val tMs: DoubleArray,
    val rois: LinkedHashMap<String, List<DoubleArray>>,
    val motion: DoubleArray,
    val yaw: DoubleArray,
    val pitch: DoubleArray,
    val luma: DoubleArray,
    val faceCount: IntArray,
    val faceFraction: DoubleArray
) {
    fun toJson() = VitalsJson.obj(
        "t_ms" to tMs,
        "rois" to rois.mapValues { (_, rows) -> rows.map { it } },
        "motion" to motion, "yaw" to yaw, "pitch" to pitch, "luma" to luma,
        "face_count" to faceCount, "face_fraction" to faceFraction
    )
}

class VitalsEigen(val values: DoubleArray, val vectors: Array<DoubleArray>)

/** `roi_combine` output. [snrDb] keeps every ROI x method candidate (null when the method gave no signal). */
class VitalsRoiCombination(
    val method: String,
    val rois: List<String>,
    val weights: LinkedHashMap<String, Double>,
    val snrDb: LinkedHashMap<String, LinkedHashMap<String, Double?>>,
    val signal: DoubleArray
)

/** Face gates, Jacobi, the rPPG methods and ROI fusion of `scripts/vitals_reference.py`. */
object VitalsFace {

    /** null when frame [i] passes every face gate, else the guidance key. */
    fun faceFrameGate(face: VitalsFaceFrames, i: Int, roiNames: List<String>, cfg: VitalsConfig): String? {
        val g = cfg.face.gates
        if (face.faceCount[i] == 0) return "face_none"
        if (face.faceCount[i] > 1) return "face_multiple"
        if (face.faceFraction[i] < g.minFaceFraction) return "face_far"
        if (face.faceFraction[i] > g.maxFaceFraction) return "face_near"
        if (abs(face.yaw[i]) > g.maxYawDeg || abs(face.pitch[i]) > g.maxPitchDeg) return "face_angle"
        if (face.luma[i] < g.lumaMin) return "light_dark"
        if (face.luma[i] > g.lumaMax) return "light_bright"
        if (face.motion[i] > g.maxMotion) return "hold_still"
        var skin = 0.0
        for (name in roiNames) skin += face.rois.getValue(name)[i][3]
        if (skin / roiNames.size < g.minSkinFraction) return "face_none"
        return null
    }

    /**
     * Symmetric 3x3 eigen-decomposition, 12 fixed cyclic sweeps. Values descending (ties by index), vectors as rows
     * with each vector's largest-magnitude component positive.
     */
    fun jacobiEigen(a: Array<DoubleArray>): VitalsEigen {
        val m = arrayOf(a[0].copyOf(), a[1].copyOf(), a[2].copyOf())
        val v = arrayOf(doubleArrayOf(1.0, 0.0, 0.0), doubleArrayOf(0.0, 1.0, 0.0), doubleArrayOf(0.0, 0.0, 1.0))
        val pqs = arrayOf(intArrayOf(0, 1), intArrayOf(0, 2), intArrayOf(1, 2))
        repeat(12) {
            for (pq in pqs) {
                val p = pq[0]
                val q = pq[1]
                if (abs(m[p][q]) < 1e-300) continue
                val theta = (m[q][q] - m[p][p]) / (2.0 * m[p][q])
                val t = (if (theta >= 0.0) 1.0 else -1.0) / (abs(theta) + sqrt(theta * theta + 1.0))
                val c = 1.0 / sqrt(t * t + 1.0)
                val s = t * c
                for (k in 0 until 3) {
                    val mkp = m[k][p]
                    val mkq = m[k][q]
                    m[k][p] = c * mkp - s * mkq
                    m[k][q] = s * mkp + c * mkq
                }
                for (k in 0 until 3) {
                    val mpk = m[p][k]
                    val mqk = m[q][k]
                    m[p][k] = c * mpk - s * mqk
                    m[q][k] = s * mpk + c * mqk
                }
                for (k in 0 until 3) {
                    val vkp = v[k][p]
                    val vkq = v[k][q]
                    v[k][p] = c * vkp - s * vkq
                    v[k][q] = s * vkp + c * vkq
                }
            }
        }
        class Pair3(val value: Double, val index: Int, val vec: DoubleArray)
        val pairs = ArrayList<Pair3>()
        for (i in 0 until 3) {
            var vec = doubleArrayOf(v[0][i], v[1][i], v[2][i])
            var big = 0
            for (k in 1 until 3) if (abs(vec[k]) > abs(vec[big])) big = k
            if (vec[big] < 0.0) vec = doubleArrayOf(-vec[0], -vec[1], -vec[2])
            pairs.add(Pair3(m[i][i], i, vec))
        }
        // Python sorts by the tuple (-value, index) with float `<`, so -0.0 == 0.0 and ties go by index.
        val sorted = pairs.sortedWith { x, y ->
            val a1 = -x.value
            val b1 = -y.value
            if (a1 < b1) -1 else if (a1 > b1) 1 else x.index.compareTo(y.index)
        }
        return VitalsEigen(DoubleArray(3) { sorted[it].value }, Array(3) { sorted[it].vec })
    }

    private fun cov3(rows: Array<DoubleArray>): Array<DoubleArray> {
        val n = rows[0].size
        val c = Array(3) { DoubleArray(3) }
        for (i in 0 until 3) {
            for (j in 0 until 3) {
                var acc = 0.0
                for (k in 0 until n) acc += rows[i][k] * rows[j][k]
                c[i][j] = acc / n
            }
        }
        return c
    }

    private fun center(x: DoubleArray): DoubleArray {
        val m = mean(x)
        return DoubleArray(x.size) { x[it] - m }
    }

    private fun project(vec: DoubleArray, rows: Array<DoubleArray>): DoubleArray =
        DoubleArray(rows[0].size) { k -> vec[0] * rows[0][k] + vec[1] * rows[1][k] + vec[2] * rows[2][k] }

    private fun bestBySnr(cands: List<DoubleArray>, fs: Double, cfg: VitalsConfig): DoubleArray? {
        var best: DoubleArray? = null
        var bestSnr: Double? = null
        for (c in cands) {
            val s = VitalsSignal.hrSpectrum(c, fs, cfg).snrDb
            if (bestSnr == null || s > bestSnr) {
                best = c
                bestSnr = s
            }
        }
        return best
    }

    /**
     * rgb: [R, G, B] uniformly sampled, gap-filled ROI means. Returns {method: band-passed pulse signal or null},
     * every output sign-aligned to the green method.
     */
    fun rppgMethods(rgb: Array<DoubleArray>, fs: Double, cfg: VitalsConfig): LinkedHashMap<String, DoubleArray?> {
        val sig = cfg.signal
        val lo = sig.hrBandHz[0]
        val hi = sig.hrBandHz[1]
        val n = rgb[0].size
        val w = oddWindow(sig.detrendWindowS, fs)
        val norm = Array(3) { c ->
            val ma = VitalsSignal.movingAverage(rgb[c], w)
            DoubleArray(n) { k -> if (ma[k] != 0.0) rgb[c][k] / ma[k] else 1.0 }
        }
        val out = LinkedHashMap<String, DoubleArray?>()
        out["green"] = VitalsSignal.bandpass(DoubleArray(n) { 1.0 - norm[1][it] }, fs, lo, hi)
        val chroma = DoubleArray(n) { k ->
            val s = rgb[0][k] + rgb[1][k] + rgb[2][k]
            if (s != 0.0) rgb[1][k] / s else 0.0
        }
        val maC = VitalsSignal.movingAverage(chroma, w)
        out["normalized"] = VitalsSignal.bandpass(DoubleArray(n) { k -> if (maC[k] != 0.0) 1.0 - chroma[k] / maC[k] else 0.0 }, fs, lo, hi)
        val xs = DoubleArray(n) { k -> 3.0 * norm[0][k] - 2.0 * norm[1][k] }
        val ys = DoubleArray(n) { k -> 1.5 * norm[0][k] + norm[1][k] - 1.5 * norm[2][k] }
        val xf = VitalsSignal.bandpass(xs, fs, lo, hi)
        val yf = VitalsSignal.bandpass(ys, fs, lo, hi)
        val sy = std(yf)
        val alpha = if (sy > 0.0) std(xf) / sy else 0.0
        out["chrom"] = DoubleArray(n) { k -> xf[k] - alpha * yf[k] }

        val l = ifloor(cfg.face.posWindowS * fs + 0.5)
        val h = DoubleArray(n)
        if (n >= l) {
            val s1 = DoubleArray(l)
            val s2 = DoubleArray(l)
            val hw = DoubleArray(l)
            for (m in 0..n - l) {
                val means = DoubleArray(3)
                for (c in 0 until 3) {
                    var acc = 0.0
                    for (k in m until m + l) acc += rgb[c][k]
                    means[c] = acc / l
                }
                if (means[0] == 0.0 || means[1] == 0.0 || means[2] == 0.0) continue
                for (k in m until m + l) {
                    val rn = rgb[0][k] / means[0]
                    val gn = rgb[1][k] / means[1]
                    val bn = rgb[2][k] / means[2]
                    s1[k - m] = gn - bn
                    s2[k - m] = gn + bn - 2.0 * rn
                }
                val sd2 = std(s2)
                val a = if (sd2 > 0.0) std(s1) / sd2 else 0.0
                for (k in 0 until l) hw[k] = s1[k] + a * s2[k]
                val hm = mean(hw)
                for (k in 0 until l) h[m + k] += hw[k] - hm
            }
        }
        out["pos"] = VitalsSignal.bandpass(h, fs, lo, hi)

        val bpRows = Array(3) { c -> center(VitalsSignal.bandpass(norm[c], fs, lo, hi)) }
        val eig = jacobiEigen(cov3(bpRows))
        val values = eig.values
        val vectors = eig.vectors
        out["pca"] = bestBySnr(vectors.map { project(it, bpRows) }, fs, cfg)
        out["ica"] = null
        if (values[2] > 1e-14) {
            val white = Array(3) { i -> DoubleArray(3) { j -> vectors[i][j] / sqrt(values[i]) } }
            val z = Array(3) { i -> project(white[i], bpRows) }
            var wm = arrayOf(doubleArrayOf(1.0, 0.0, 0.0), doubleArrayOf(0.0, 1.0, 0.0), doubleArrayOf(0.0, 0.0, 1.0))
            for (iter in 0 until 60) {
                val new = Array(3) { DoubleArray(3) }
                for (i in 0 until 3) {
                    val wx = project(wm[i], z)
                    val gsum = DoubleArray(3)
                    var dsum = 0.0
                    for (k in 0 until n) {
                        // Cube nonlinearity: + * only, bit-identical on every platform (see the reference).
                        val g = wx[k] * wx[k] * wx[k]
                        dsum += 3.0 * wx[k] * wx[k]
                        for (j in 0 until 3) gsum[j] += z[j][k] * g
                    }
                    for (j in 0 until 3) new[i][j] = gsum[j] / n - dsum / n * wm[i][j]
                }
                val wwt = Array(3) { DoubleArray(3) }
                for (i in 0 until 3) {
                    for (j in 0 until 3) {
                        var acc = 0.0
                        for (k in 0 until 3) acc += new[i][k] * new[j][k]
                        wwt[i][j] = acc
                    }
                }
                val e = jacobiEigen(wwt)
                val ev = e.values
                val evec = e.vectors
                if (ev[2] <= 1e-300) break
                val invSqrt = Array(3) { DoubleArray(3) }
                for (i in 0 until 3) {
                    for (j in 0 until 3) {
                        var acc = 0.0
                        for (k in 0 until 3) acc += evec[k][i] * evec[k][j] / sqrt(ev[k])
                        invSqrt[i][j] = acc
                    }
                }
                val next = Array(3) { DoubleArray(3) }
                for (i in 0 until 3) {
                    for (j in 0 until 3) {
                        var acc = 0.0
                        for (k in 0 until 3) acc += invSqrt[i][k] * new[k][j]
                        next[i][j] = acc
                    }
                }
                wm = next
            }
            out["ica"] = bestBySnr(List(3) { project(wm[it], z) }, fs, cfg)
        }
        val ref = out["green"]!!
        for (name in cfg.face.methods) {
            val s = out[name]
            if (s == null || name == "green") continue
            if (pearson(s, ref) < 0.0) out[name] = DoubleArray(s.size) { -s[it] }
        }
        return out
    }

    /**
     * per_roi: {roi: {method: signal}}. Picks the method with the highest median SNR across ROIs (config order breaks
     * ties), then SNR-weights the unit-variance ROI signals at or above min_roi_snr_db.
     */
    fun roiCombine(perRoi: Map<String, Map<String, DoubleArray?>>, fs: Double, cfg: VitalsConfig): VitalsRoiCombination {
        val rois = perRoi.keys.sorted()
        val methods = cfg.face.methods
        val table = LinkedHashMap<String, LinkedHashMap<String, Double?>>()
        for (roi in rois) {
            val row = LinkedHashMap<String, Double?>()
            for (name in methods) row[name] = perRoi.getValue(roi)[name]?.let { VitalsSignal.hrSpectrum(it, fs, cfg).snrDb }
            table[roi] = row
        }
        var bestM: String? = null
        var bestMed: Double? = null
        for (name in methods) {
            val vals = ArrayList<Double>()
            for (roi in rois) table.getValue(roi)[name]?.let { vals.add(it) }
            if (vals.isEmpty()) continue
            val med = median(vals)
            if (bestMed == null || med > bestMed) {
                bestM = name
                bestMed = med
            }
        }
        val method = bestM!!
        fun snr(roi: String): Double? = table.getValue(roi)[method]
        var used = ArrayList<String>()
        for (roi in rois) {
            val s = snr(roi)
            if (s != null && s >= cfg.face.minRoiSnrDb) used.add(roi)
        }
        if (used.isEmpty()) {
            var top: String? = null
            for (roi in rois) {
                val s = snr(roi) ?: continue
                val t = top?.let { snr(it) }
                if (t == null || s > t) top = roi
            }
            used = arrayListOf(top ?: rois[0])
        }
        val n = perRoi.getValue(used[0]).getValue(method)!!.size
        val combined = DoubleArray(n)
        var wsum = 0.0
        val weights = LinkedHashMap<String, Double>()
        for (roi in used) {
            val s = perRoi.getValue(roi).getValue(method)!!
            val sd = std(s)
            val w = 10.0.pow(snr(roi)!! / 10.0)
            weights[roi] = w
            wsum += w
            for (k in 0 until n) combined[k] += w * (if (sd > 0.0) s[k] / sd else 0.0)
        }
        for (k in 0 until n) combined[k] = combined[k] / wsum
        val snrOut = LinkedHashMap<String, LinkedHashMap<String, Double?>>()
        for (roi in rois) {
            val row = LinkedHashMap<String, Double?>()
            for (name in methods) row[name] = roundTo(table.getValue(roi)[name], 2)
            snrOut[roi] = row
        }
        val wOut = LinkedHashMap<String, Double>()
        for (roi in used) wOut[roi] = roundTo(weights.getValue(roi) / wsum, 3)
        return VitalsRoiCombination(method, used, wOut, snrOut, combined)
    }
}
