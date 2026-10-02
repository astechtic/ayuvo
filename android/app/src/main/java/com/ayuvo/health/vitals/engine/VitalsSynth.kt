package com.ayuvo.health.vitals.engine

import com.ayuvo.health.vitals.engine.VitalsMath.TWO_PI
import com.ayuvo.health.vitals.engine.VitalsMath.ifloor
import com.ayuvo.health.vitals.engine.VitalsMath.roundTo
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.math.exp
import kotlin.math.sin

/** Numerical Recipes style LCG modulo 2^31; identical in every port (64-bit integer math). */
class VitalsLcg(seed: Long) {
    private var state: Long = Math.floorMod(seed, M)

    fun uniform(): Double {
        state = (1103515245L * state + 12345L) % M
        return state / 2147483648.0
    }

    fun gauss(): Double {
        var acc = 0.0
        repeat(4) { acc += uniform() }
        return (acc - 2.0) * 1.7320508075688772
    }

    private companion object {
        const val M = 2147483648L
    }
}

/** A synth event `[start_s, end_s)`: finger no_finger|motion|saturate, face motion|dark|second_face|turn. */
data class VitalsSynthEvent(val kind: String, val startS: Double, val endS: Double, val amp: Double)

/** `synth_finger` / `synth_face` spec with the reference's defaults. */
data class VitalsSynthSpec(
    val fs: Double,
    val durationS: Double,
    val hrBpm: Double,
    val rsaMs: Double = 0.0,
    val ibiJitterMs: Double = 0.0,
    val hrDriftBpm: Double = 0.0,
    val respBpm: Double = 15.0,
    val ac: Double = 0.02,
    val wander: Double = 0.004,
    val dc: DoubleArray = doubleArrayOf(210.0, 60.0, 25.0),
    val noise: Double = 0.0005,
    val jitterMs: Double = 0.0,
    val seed: Long = 1,
    val events: List<VitalsSynthEvent> = emptyList(),
    val skin: DoubleArray = doubleArrayOf(160.0, 115.0, 95.0),
    /** roi -> (ac, noise); face only. */
    val rois: Map<String, Pair<Double, Double>> = emptyMap(),
    val illum: Double = 0.0,
    val illumHz: Double = 0.3
) {
    companion object {
        /** Parses the vector / replay JSON spec; absent keys take the reference's `spec.get` defaults. */
        fun parse(o: JsonObject): VitalsSynthSpec {
            fun d(k: String): Double? = VitalsConfig.num(o[k])
            fun arr(k: String): DoubleArray? = (o[k] as? JsonArray)?.map { VitalsConfig.num(it)!! }?.toDoubleArray()
            val base = VitalsSynthSpec(fs = d("fs")!!, durationS = d("duration_s")!!, hrBpm = d("hr_bpm")!!)
            return base.copy(
                rsaMs = d("rsa_ms") ?: base.rsaMs,
                ibiJitterMs = d("ibi_jitter_ms") ?: base.ibiJitterMs,
                hrDriftBpm = d("hr_drift_bpm") ?: base.hrDriftBpm,
                respBpm = d("resp_bpm") ?: base.respBpm,
                ac = d("ac") ?: base.ac,
                wander = d("wander") ?: base.wander,
                dc = arr("dc") ?: base.dc,
                noise = d("noise") ?: base.noise,
                jitterMs = d("jitter_ms") ?: base.jitterMs,
                seed = (o["seed"] as? JsonPrimitive)?.content?.toBigDecimal()?.toLong() ?: base.seed,
                events = (o["events"] as? JsonArray).orEmpty().map {
                    val e = it as JsonObject
                    VitalsSynthEvent(
                        (e["kind"] as JsonPrimitive).content, VitalsConfig.num(e["start_s"])!!, VitalsConfig.num(e["end_s"])!!,
                        VitalsConfig.num(e["amp"]) ?: 0.0
                    )
                },
                skin = arr("skin") ?: base.skin,
                rois = (o["rois"] as? JsonObject)?.entries?.associate { (k, v) ->
                    val r = v as JsonObject
                    k to Pair(VitalsConfig.num(r["ac"])!!, VitalsConfig.num(r["noise"]) ?: 0.001)
                }.orEmpty(),
                illum = d("illum") ?: base.illum,
                illumHz = d("illum_hz") ?: base.illumHz
            )
        }
    }
}

/** Deterministic synthetic signals of `scripts/vitals_reference.py` (test vectors and the replay source). */
object VitalsSynth {

    private fun beatTimes(spec: VitalsSynthSpec, durationMs: Double, rng: VitalsLcg): DoubleArray {
        val beats = ArrayList<Double>()
        var t = 100.0
        val base = 60000.0 / spec.hrBpm
        val respHz = spec.respBpm / 60.0
        while (t < durationMs + 3000.0) {
            beats.add(t)
            var ibi = base + spec.rsaMs * sin(TWO_PI * respHz * t / 1000.0)
            ibi += spec.ibiJitterMs * (rng.uniform() - 0.5) * 2.0
            val drift = spec.hrDriftBpm
            if (drift != 0.0) {
                val hrNow = spec.hrBpm + drift * t / durationMs
                ibi = ibi * spec.hrBpm / hrNow
            }
            t += ibi
        }
        return beats.toDoubleArray()
    }

    private class Cursor(var value: Int)

    private fun pulse(t: Double, beats: DoubleArray, cursor: Cursor): Double {
        while (cursor.value + 1 < beats.size - 1 && beats[cursor.value + 1] <= t) cursor.value += 1
        val span = beats[cursor.value + 1] - beats[cursor.value]
        val phi = (t - beats[cursor.value]) / span
        val a = (phi - 0.2) / 0.07
        val b = (phi - 0.5) / 0.09
        return exp(-a * a) + 0.35 * exp(-b * b)
    }

    private fun eventAt(events: List<VitalsSynthEvent>, tS: Double, kind: String): VitalsSynthEvent? =
        events.firstOrNull { it.kind == kind && it.startS <= tS && tS < it.endS }

    /** Finger frames `[t_ms, r, g, b, r_std, sat_frac]`. */
    fun synthFinger(spec: VitalsSynthSpec): List<DoubleArray> {
        val rng = VitalsLcg(spec.seed)
        val fs = spec.fs
        val durationMs = spec.durationS * 1000.0
        val beats = beatTimes(spec, durationMs, rng)
        val respHz = spec.respBpm / 60.0
        val weights = doubleArrayOf(1.0, 1.5, 1.2)
        val dc = spec.dc
        val n = ifloor(spec.durationS * fs)
        val frames = ArrayList<DoubleArray>(n)
        val cursor = Cursor(0)
        for (i in 0 until n) {
            var t = i * 1000.0 / fs + spec.jitterMs * (rng.uniform() - 0.5) * 2.0
            if (t < 0.0) t = 0.0
            val p = pulse(t, beats, cursor)
            val resp = sin(TWO_PI * respHz * t / 1000.0)
            val ac = spec.ac * (1.0 + 0.15 * resp)
            val base = 1.0 + spec.wander * resp
            var vals = DoubleArray(3)
            for (c in 0 until 3) {
                val noise = spec.noise * rng.gauss()
                vals[c] = dc[c] * base * (1.0 - ac * weights[c] * p) * (1.0 + noise)
            }
            var rStd = 8.0
            var sat = 0.02
            val tS = t / 1000.0
            val motion = eventAt(spec.events, tS, "motion")
            if (motion != null) {
                val wobble = motion.amp * sin(TWO_PI * 1.3 * tS) + motion.amp * 0.5
                for (c in 0 until 3) vals[c] = vals[c] * (1.0 + wobble)
                rStd = 18.0
            }
            if (eventAt(spec.events, tS, "no_finger") != null) {
                val r = 60.0 + 5.0 * rng.gauss()
                val g = 55.0 + 5.0 * rng.gauss()
                val b = 50.0 + 5.0 * rng.gauss()
                vals = doubleArrayOf(r, g, b)
                rStd = 45.0
                sat = 0.0
            }
            if (eventAt(spec.events, tS, "saturate") != null) {
                vals[0] = 255.0
                sat = 0.7
            }
            if (vals[0] > 255.0) vals[0] = 255.0
            frames.add(doubleArrayOf(roundTo(t, 3), roundTo(vals[0], 3), roundTo(vals[1], 3), roundTo(vals[2], 3), rStd, sat))
        }
        return frames
    }

    fun synthFace(spec: VitalsSynthSpec): VitalsFaceFrames {
        val rng = VitalsLcg(spec.seed)
        val fs = spec.fs
        val durationMs = spec.durationS * 1000.0
        val beats = beatTimes(spec, durationMs, rng)
        val pbv = doubleArrayOf(0.33, 0.77, 0.53)
        val skin = spec.skin
        val roiNames = spec.rois.keys.sorted()
        val n = ifloor(spec.durationS * fs)
        val tOut = DoubleArray(n)
        val motionOut = DoubleArray(n)
        val yawOut = DoubleArray(n)
        val pitchOut = DoubleArray(n)
        val lumaOut = DoubleArray(n)
        val facesOut = IntArray(n)
        val fractionOut = DoubleArray(n)
        val roiRows = LinkedHashMap<String, MutableList<DoubleArray>>()
        for (name in roiNames) roiRows[name] = ArrayList(n)
        val cursor = Cursor(0)
        for (i in 0 until n) {
            var t = i * 1000.0 / fs + spec.jitterMs * (rng.uniform() - 0.5) * 2.0
            if (t < 0.0) t = 0.0
            val tS = t / 1000.0
            val p = pulse(t, beats, cursor)
            var illum = spec.illum * sin(TWO_PI * spec.illumHz * tS)
            var motionScore = 0.005
            var yaw = 2.0
            var faces = 1
            var lumaScale = 1.0
            val motion = eventAt(spec.events, tS, "motion")
            if (motion != null) {
                illum += motion.amp * sin(TWO_PI * 1.1 * tS)
                motionScore = 0.08
            }
            if (eventAt(spec.events, tS, "dark") != null) lumaScale = 0.2
            if (eventAt(spec.events, tS, "second_face") != null) faces = 2
            if (eventAt(spec.events, tS, "turn") != null) yaw = 35.0
            for (name in roiNames) {
                val (ac, noiseAmp) = spec.rois.getValue(name)
                val vals = DoubleArray(3)
                for (c in 0 until 3) {
                    val noise = noiseAmp * rng.gauss()
                    vals[c] = skin[c] * lumaScale * (1.0 + illum) * (1.0 - ac * pbv[c] * p) * (1.0 + noise)
                }
                roiRows.getValue(name).add(doubleArrayOf(roundTo(vals[0], 3), roundTo(vals[1], 3), roundTo(vals[2], 3), 0.9))
            }
            tOut[i] = roundTo(t, 3)
            motionOut[i] = motionScore
            yawOut[i] = yaw
            pitchOut[i] = 1.0
            lumaOut[i] = roundTo(120.0 * lumaScale, 3)
            facesOut[i] = faces
            fractionOut[i] = 0.4
        }
        val rois = LinkedHashMap<String, List<DoubleArray>>()
        for ((k, v) in roiRows) rois[k] = v
        return VitalsFaceFrames(tOut, rois, motionOut, yawOut, pitchOut, lumaOut, facesOut, fractionOut)
    }
}
