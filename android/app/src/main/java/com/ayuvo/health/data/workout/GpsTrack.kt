package com.ayuvo.health.data.workout

import com.ayuvo.health.data.workout.WorkoutMath.haversineM
import com.ayuvo.health.data.workout.WorkoutMath.inPause
import com.ayuvo.health.data.workout.WorkoutMath.roundTo
import com.ayuvo.health.medications.logic.MedicationJson
import kotlinx.serialization.json.JsonObject

/** One GPS fix; `altM` is barometric/relative altitude when the device has one, else GPS altitude. */
data class GpsPoint(val tMs: Long, val lat: Double, val lon: Double, val altM: Double?, val hAccM: Double?, val speedMps: Double?)

data class GpsTrackInput(
    val sport: String,
    /** In time order. */
    val points: List<GpsPoint>,
    /** Manual pauses. */
    val pauses: List<TimeSpan>,
    val startMs: Long,
    val endMs: Long
)

data class KmSplit(val km: Int, val seconds: Double)

data class GpsTrackResult(
    val distanceM: Double,
    val movingS: Double,
    val elapsedS: Double,
    val avgSpeedMps: Double?,
    val avgPaceSPerKm: Double?,
    val maxSpeedMps: Double?,
    val splits: List<KmSplit>,
    val elevationGainM: Double,
    val elevationLossM: Double,
    val keptPoints: Int,
    val droppedPoints: Int
) {
    fun toJson(): JsonObject = MedicationJson.obj(
        "distance_m" to distanceM, "moving_s" to movingS, "elapsed_s" to elapsedS, "avg_speed_mps" to avgSpeedMps,
        "avg_pace_s_per_km" to avgPaceSPerKm, "max_speed_mps" to maxSpeedMps,
        "splits" to splits.map { MedicationJson.obj("km" to it.km, "seconds" to it.seconds) },
        "elevation_gain_m" to elevationGainM, "elevation_loss_m" to elevationLossM,
        "kept_points" to keptPoints, "dropped_points" to droppedPoints
    )
}

/** GPS outdoor workout summary: ported from `gps_track`. */
object GpsTrack {

    fun gpsTrack(inp: GpsTrackInput, cfg: WorkoutConfig): GpsTrackResult {
        val sp = cfg.sport(inp.sport)
        val th = cfg.thresholds
        val pauses = inp.pauses

        // 1. accuracy, window and speed gates
        val kept = ArrayList<GpsPoint>()
        var dropped = 0
        for (p in inp.points) {
            if (p.hAccM == null || p.hAccM > th.maxHAccuracyM || p.tMs < inp.startMs || p.tMs > inp.endMs) {
                dropped += 1
                continue
            }
            val q = kept.lastOrNull()
            if (q != null) {
                val dt = (p.tMs - q.tMs) / 1000.0
                if (dt <= 0) {
                    dropped += 1
                    continue
                }
                if (haversineM(q.lat, q.lon, p.lat, p.lon) / dt > sp.maxSpeedMps) {
                    dropped += 1
                    continue
                }
            }
            kept += p
        }

        // 2–3. moving segments, auto-pause and splits
        var distance = 0.0
        var moving = 0.0
        val splits = ArrayList<Double>()
        var nextKm = 1000.0
        var maxSpeed = 0.0
        for (i in 0 until kept.size - 1) {
            val q = kept[i]
            val p = kept[i + 1]
            if (inPause(q.tMs, pauses) || inPause(p.tMs, pauses)) continue
            val dt = (p.tMs - q.tMs) / 1000.0
            val d = haversineM(q.lat, q.lon, p.lat, p.lon)
            val v = d / dt
            if (v < sp.autoPauseSpeedMps) continue
            while (distance + d >= nextKm) {
                val frac = (nextKm - distance) / d
                splits += roundTo(moving + frac * dt, 1)
                nextKm += 1000.0
            }
            distance += d
            moving += dt
            if (v > maxSpeed) maxSpeed = v
        }
        val perKm = ArrayList<KmSplit>()
        var prev = 0.0
        splits.forEachIndexed { i, s ->
            perKm += KmSplit(i + 1, roundTo(s - prev, 1))
            prev = s
        }

        // 4. elevation with hysteresis
        var gain = 0.0
        var loss = 0.0
        var anchor: Double? = null
        for (p in kept) {
            val alt = p.altM ?: continue
            val a = anchor
            if (a == null) {
                anchor = alt
            } else if (alt - a >= th.elevationHysteresisM) {
                gain += alt - a
                anchor = alt
            } else if (a - alt >= th.elevationHysteresisM) {
                loss += a - alt
                anchor = alt
            }
        }
        val elapsed = (inp.endMs - inp.startMs) / 1000.0
        val avgSpeed = if (moving > 0) distance / moving else null
        return GpsTrackResult(
            distanceM = roundTo(distance, 1),
            movingS = roundTo(moving, 1),
            elapsedS = roundTo(elapsed, 1),
            avgSpeedMps = roundTo(avgSpeed, 2),
            avgPaceSPerKm = if (avgSpeed != null && avgSpeed != 0.0) roundTo(1000.0 / avgSpeed, 0) else null,
            maxSpeedMps = if (moving > 0) roundTo(maxSpeed, 2) else null,
            splits = perKm,
            elevationGainM = roundTo(gain, 1),
            elevationLossM = roundTo(loss, 1),
            keptPoints = kept.size,
            droppedPoints = dropped
        )
    }
}
