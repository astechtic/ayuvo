package com.ayuvo.health.data.workout

import com.ayuvo.health.data.workout.WorkoutMath.roundTo
import com.ayuvo.health.medications.logic.MedicationJson
import kotlinx.serialization.json.JsonObject
import kotlin.math.abs
import kotlin.math.exp

/** One heart-rate sample: per-second from a watch, per-minute from a band. */
data class HrSample(val tMs: Long, val bpm: Double)

data class HrWorkoutInput(
    /** In time order. */
    val samples: List<HrSample>,
    val startMs: Long,
    val endMs: Long,
    val hrMax: Double,
    val rhr: Double?,
    val sex: String?,
    val age: Double?,
    val weightKg: Double?
)

data class HrWorkoutResult(
    val avgHr: Double?,
    /** The raw peak sample value. */
    val maxHr: Double?,
    val coveragePct: Double,
    /** Five entries: below light, light, moderate, vigorous, near-maximal. */
    val zoneSeconds: List<Double>,
    val trimp: Double?,
    val kcal: Double?,
    val zoneMethod: String
) {
    fun toJson(): JsonObject = MedicationJson.obj(
        "avg_hr" to avgHr, "max_hr" to maxHr, "coverage_pct" to coveragePct, "zone_seconds" to zoneSeconds,
        "trimp" to trimp, "kcal" to kcal, "zone_method" to zoneMethod
    )
}

data class HrRecoveryInput(val samples: List<HrSample>, val endMs: Long)

data class HrRecoveryResult(val hrr1: Double?, val flagLow: Boolean, val confidence: String?) {
    fun toJson(): JsonObject = MedicationJson.obj("hrr1" to hrr1, "flag_low" to flagLow, "confidence" to confidence)
}

/** Minute heart rate: value i belongs to minute `startMs + i · 60000`. */
data class HrMinuteSeries(val startMs: Long, val values: List<Double?>)

data class WorkoutWindowsInput(val hr: HrMinuteSeries, val rhr: Double?, val hrMax: Double)

data class HrWindow(val startMs: Long, val endMs: Long, val minutes: Long, val avgHr: Double, val maxHr: Double) {
    fun toJson(): JsonObject =
        MedicationJson.obj("start_ms" to startMs, "end_ms" to endMs, "minutes" to minutes, "avg_hr" to avgHr, "max_hr" to maxHr)
}

data class WorkoutWindowsResult(val levelBpm: Double, val windows: List<HrWindow>) {
    fun toJson(): JsonObject = MedicationJson.obj("level_bpm" to levelBpm, "windows" to windows.map { it.toJson() })
}

/** Heart rate during a workout: ported from `_zone`, `hr_workout`, `hr_recovery` and `workout_windows`. */
object HeartRateWorkout {
    private const val MINUTE = 60000L

    fun zone(hr: Double, hrMax: Double, rhr: Double?, th: WorkoutConfig.Thresholds): Int {
        val x: Double
        val cuts: List<Double>
        if (rhr != null && hrMax > rhr) {
            x = (hr - rhr) / (hrMax - rhr)
            cuts = th.hrrZones
        } else {
            x = hr / hrMax
            cuts = th.hrmaxZones
        }
        var z = 0
        for (c in cuts) if (x >= c) z += 1
        return z
    }

    fun hrWorkout(inp: HrWorkoutInput, cfg: WorkoutConfig): HrWorkoutResult {
        val th = cfg.thresholds
        val s0 = inp.startMs
        val s1 = inp.endMs
        val samples = inp.samples.filter { s0 <= it.tMs && it.tMs < s1 && th.hrValidMin <= it.bpm && it.bpm <= th.hrValidMax }
        val cap = th.maxSampleGapS * 1000L
        var covered = 0L
        val zones = DoubleArray(5)
        var weighted = 0.0
        var trimp = 0.0
        var kcal = 0.0
        var peak: Double? = null
        val hrMax = inp.hrMax
        val rhr = inp.rhr
        val k = WorkoutMath.bySex(th.trimpK, inp.sex)
        val kc = WorkoutMath.bySex(th.keytel, inp.sex)
        val energy = inp.weightKg != null && inp.weightKg != 0.0 && inp.age != null
        for ((i, s) in samples.withIndex()) {
            val t = s.tMs
            val bpm = s.bpm
            val nxt = if (i + 1 < samples.size) samples[i + 1].tMs else s1
            val dur = minOf(nxt, t + cap, s1) - t
            if (dur <= 0) continue
            covered += dur
            val minutes = dur / 60000.0
            weighted += bpm * dur
            zones[zone(bpm, hrMax, rhr, th)] += dur / 1000.0
            if (rhr != null && hrMax > rhr) {
                val x = (bpm - rhr) / (hrMax - rhr)
                if (x > 0) trimp += minutes * x * 0.64 * exp(k * x)
            }
            if (energy) {
                val perMin = (kc[0] + kc[1] * bpm + kc[2] * inp.weightKg!! + kc[3] * inp.age!!) / 4.184
                kcal += maxOf(0.0, perMin) * minutes
            }
            if (peak == null || bpm > peak) peak = bpm
        }
        val window = s1 - s0
        val coverage = if (window > 0) covered.toDouble() / window.toDouble() else 0.0
        val anyCovered = covered != 0L
        return HrWorkoutResult(
            avgHr = if (anyCovered) roundTo(weighted / covered, 1) else null,
            maxHr = peak,
            coveragePct = roundTo(coverage * 100.0, 1),
            zoneSeconds = zones.map { roundTo(it, 0) },
            trimp = if (rhr != null && anyCovered) roundTo(trimp, 1) else null,
            kcal = if (anyCovered && coverage >= th.keytelMinCoverage && inp.weightKg != null && inp.weightKg != 0.0) roundTo(kcal, 0) else null,
            zoneMethod = if (rhr != null && hrMax > rhr) "hrr" else "hrmax"
        )
    }

    /**
     * HRR1 = highest HR in the last 60 s before end − HR at end + 60 s (the sample nearest end + 60 s within
     * ± recovery_tolerance_s; ties broken like Python's tuple sort: earlier time, then lower bpm).
     */
    fun hrRecovery(inp: HrRecoveryInput, cfg: WorkoutConfig): HrRecoveryResult {
        val th = cfg.thresholds
        val end = inp.endMs
        val samples = inp.samples
        val before = samples.filter { end - 60000 <= it.tMs && it.tMs <= end }.map { it.bpm }
        val tol = th.recoveryToleranceS * 1000L
        val target = end + 60000
        val after = samples.filter { abs(it.tMs - target) <= tol }
            .sortedWith(compareBy<HrSample>({ abs(it.tMs - target) }, { it.tMs }, { it.bpm }))
        if (before.isEmpty() || after.isEmpty()) return HrRecoveryResult(null, false, null)
        val peak = before.max()
        val drop = peak - after[0].bpm
        val gaps = (0 until samples.size - 1).map { samples[it + 1].tMs - samples[it].tMs }
        val dense = gaps.isNotEmpty() && gaps.max() <= 10000
        return HrRecoveryResult(roundTo(drop, 0), drop < th.hrr1AbnormalBelow, if (dense) "medium" else "low")
    }

    /**
     * Strength-session windows: a minute is active at HR ≥ min(detect_bpm, rhr + detect_hrr × (hr_max − rhr))
     * (detect_bpm alone without a usable resting HR); active minutes at most merge_gap_min idle minutes apart join.
     */
    fun workoutWindows(inp: WorkoutWindowsInput, cfg: WorkoutConfig): WorkoutWindowsResult {
        val th = cfg.thresholds
        val rhr = inp.rhr
        var level = th.detectBpm
        if (rhr != null && inp.hrMax > rhr) level = minOf(level, rhr + th.detectHrr * (inp.hrMax - rhr))
        val series = inp.hr
        val t0 = series.startMs
        val active = ArrayList<Long>()
        series.values.forEachIndexed { i, v -> if (v != null && v >= level) active += t0 + i * MINUTE }
        val windows = ArrayList<LongArray>()
        for (t in active) {
            val last = windows.lastOrNull()
            if (last != null && t - last[1] <= (th.mergeGapMin + 1) * MINUTE) last[1] = t else windows += longArrayOf(t, t)
        }
        val out = ArrayList<HrWindow>()
        for ((s, e) in windows.map { it[0] to it[1] }) {
            val minutes = Math.floorDiv(e - s, MINUTE) + 1
            if (minutes < th.minWindowMin) continue
            val vals = ArrayList<Double>()
            var t = s
            while (t < e + MINUTE) {
                series.values[Math.floorDiv(t - t0, MINUTE).toInt()]?.let { vals += it }
                t += MINUTE
            }
            out += HrWindow(s, e + MINUTE, minutes, roundTo(WorkoutMath.mean(vals), 1), vals.max())
        }
        return WorkoutWindowsResult(roundTo(level, 1), out)
    }
}
