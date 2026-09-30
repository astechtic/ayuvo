package com.ayuvo.health.data.workout

import com.ayuvo.health.data.workout.WorkoutMath.roundTo
import com.ayuvo.health.medications.logic.MedicationJson
import kotlinx.serialization.json.JsonObject
import kotlin.math.abs

/** A steady segment of a GPS walk or run. */
data class SteadySegment(val speedMps: Double, val grade: Double, val hr: Double, val durationS: Double)

data class Vo2maxGpsInput(val segments: List<SteadySegment>, val rhr: Double?, val hrMax: Double?, val sport: String?)

data class Vo2maxGpsResult(val vo2max: Double?, val segmentsUsed: Int, val status: String) {
    fun toJson(): JsonObject = MedicationJson.obj("vo2max" to vo2max, "segments_used" to segmentsUsed, "status" to status)
}

data class CooperInput(val distanceM: Double?)

data class CooperResult(val vo2max: Double?) {
    fun toJson(): JsonObject = MedicationJson.obj("vo2max" to vo2max)
}

/** Cardio-fitness estimates: ported from `acsm_vo2`, `vo2max_gps` and `cooper`. */
object CardioFitness {

    /** ACSM oxygen cost (ml/kg/min) at [speedMps] and [grade]; `mode` "run" or walking otherwise. */
    fun acsmVo2(speedMps: Double, grade: Double, mode: String): Double {
        val s = speedMps * 60.0 // m/min
        if (mode == "run") return 0.2 * s + 0.9 * s * grade + 3.5
        return 0.1 * s + 1.8 * s * grade + 3.5
    }

    fun vo2maxGps(inp: Vo2maxGpsInput, cfg: WorkoutConfig): Vo2maxGpsResult {
        val th = cfg.thresholds
        val rhr = inp.rhr
        val hrMax = inp.hrMax
        if (rhr == null || hrMax == null || hrMax <= rhr) return Vo2maxGpsResult(null, 0, "no_heart_rate_reserve")
        var totalW = 0.0
        var total = 0.0
        var used = 0
        for (s in inp.segments) {
            if (s.durationS < th.minSegmentS || abs(s.grade) > th.maxGrade) continue
            val hrr = (s.hr - rhr) / (hrMax - rhr)
            if (hrr < th.minHrr || hrr > th.maxHrr) continue
            val mode = if (s.speedMps >= th.walkRunSplitMps) "run" else "walk"
            val v = 3.5 + (acsmVo2(s.speedMps, s.grade, mode) - 3.5) / hrr
            total += v * s.durationS
            totalW += s.durationS
            used += 1
        }
        if (used == 0) return Vo2maxGpsResult(null, 0, "no_steady_segment")
        return Vo2maxGpsResult(roundTo(total / totalW, 1), used, "ok")
    }

    /** 12-minute test: VO2max = (distance − 504.9) ÷ 44.73 (Cooper 1968). */
    fun cooper(inp: CooperInput, @Suppress("UNUSED_PARAMETER") cfg: WorkoutConfig? = null): CooperResult {
        val d = inp.distanceM
        if (d == null || d <= 504.9) return CooperResult(null)
        return CooperResult(roundTo((d - 504.9) / 44.73, 1))
    }
}
