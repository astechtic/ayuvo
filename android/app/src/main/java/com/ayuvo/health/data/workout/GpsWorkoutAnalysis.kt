package com.ayuvo.health.data.workout

import com.ayuvo.health.data.workout.WorkoutMath.haversineM
import com.ayuvo.health.data.workout.WorkoutMath.inPause
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** One lap: [startMs, endMs) and the moving distance inside it (the `gps_track` rules applied to the lap window). */
data class LapResult(val index: Int, val startMs: Long, val endMs: Long, val distanceM: Double)

/**
 * Platform-side helpers around the shared workout engines (docs/workouts-gps.md). Everything here is pure: the
 * contract functions (`gps_track`, `hr_workout`, `vo2max_gps`, …) stay in their ports; this file only builds their
 * inputs (steady segments, lap windows) and holds the MET fallback for energy.
 */
object GpsWorkoutAnalysis {

    /** Tanaka H, Monahan KD, Seals DR. Age-predicted maximal heart rate revisited. JACC 2001;37:153-156. */
    fun tanakaHrMax(ageYears: Double): Double = 208.0 - 0.7 * ageYears

    /** The fixes `gps_track` keeps (accuracy, window and speed gates), in time order. Used for the route and segments. */
    fun keptPoints(inp: GpsTrackInput, cfg: WorkoutConfig): List<GpsPoint> {
        val sp = cfg.sport(inp.sport)
        val th = cfg.thresholds
        val kept = ArrayList<GpsPoint>()
        for (p in inp.points) {
            if (p.hAccM == null || p.hAccM > th.maxHAccuracyM || p.tMs < inp.startMs || p.tMs > inp.endMs) continue
            val q = kept.lastOrNull()
            if (q != null) {
                val dt = (p.tMs - q.tMs) / 1000.0
                if (dt <= 0) continue
                if (haversineM(q.lat, q.lon, p.lat, p.lon) / dt > sp.maxSpeedMps) continue
            }
            kept += p
        }
        return kept
    }

    /**
     * Laps from lap marks: lap i runs from the previous mark (or the start) to mark i; the final lap runs to the end
     * when it is longer than [minLastLapMs]. Each distance is `gps_track` over that window, so pauses and auto-pause
     * are handled exactly as for the whole workout.
     */
    fun laps(inp: GpsTrackInput, lapMarksMs: List<Long>, cfg: WorkoutConfig, minLastLapMs: Long = 5_000L): List<LapResult> {
        val marks = lapMarksMs.filter { it > inp.startMs && it < inp.endMs }.sorted()
        if (marks.isEmpty()) return emptyList()
        val bounds = ArrayList<Pair<Long, Long>>()
        var from = inp.startMs
        for (m in marks) {
            bounds += from to m
            from = m
        }
        if (inp.endMs - from >= minLastLapMs) bounds += from to inp.endMs
        return bounds.mapIndexed { i, (s, e) ->
            val r = GpsTrack.gpsTrack(inp.copy(startMs = s, endMs = e), cfg)
            LapResult(i + 1, s, e, r.distanceM)
        }
    }

    /**
     * Steady segments for `vo2max_gps`: consecutive blocks of at least `min_segment_s` of continuous moving time
     * (no manual pause, above the auto-pause speed), whose one-minute speeds vary by at most [maxSpeedCv], with
     * altitude at both ends and heart rate covering at least `keytel_min_coverage` of the block. The grade is the
     * altitude change over the block distance; `vo2max_gps` itself rejects grades and %HRR outside its limits.
     */
    fun steadySegments(
        inp: GpsTrackInput,
        hr: List<HrSample>,
        hrMax: Double,
        rhr: Double?,
        cfg: WorkoutConfig,
        maxSpeedCv: Double = 0.15
    ): List<SteadySegment> {
        val sp = cfg.sport(inp.sport)
        val th = cfg.thresholds
        val kept = keptPoints(inp, cfg)
        val blockS = th.minSegmentS
        val out = ArrayList<SteadySegment>()

        // Continuous moving runs: indices of kept points joined by valid moving segments.
        var runStart = -1
        fun flushRun(endIdx: Int) {
            if (runStart < 0 || endIdx <= runStart) return
            var i = runStart
            while (i < endIdx) {
                // Grow a block from i until it holds blockS of moving time.
                var j = i
                var time = 0.0
                var dist = 0.0
                while (j < endIdx && time < blockS) {
                    val a = kept[j]
                    val b = kept[j + 1]
                    time += (b.tMs - a.tMs) / 1000.0
                    dist += haversineM(a.lat, a.lon, b.lat, b.lon)
                    j += 1
                }
                if (time < blockS) break
                block(kept.subList(i, j + 1), time, dist, hr, hrMax, rhr, cfg, maxSpeedCv)?.let { out += it }
                i = j
            }
        }
        for (k in 0 until kept.size - 1) {
            val q = kept[k]
            val p = kept[k + 1]
            val dt = (p.tMs - q.tMs) / 1000.0
            val moving = !inPause(q.tMs, inp.pauses) && !inPause(p.tMs, inp.pauses) && dt > 0 &&
                haversineM(q.lat, q.lon, p.lat, p.lon) / dt >= sp.autoPauseSpeedMps
            if (moving) {
                if (runStart < 0) runStart = k
            } else {
                flushRun(k)
                runStart = -1
            }
        }
        flushRun(kept.size - 1)
        return out
    }

    private fun block(
        pts: List<GpsPoint>,
        timeS: Double,
        distM: Double,
        hr: List<HrSample>,
        hrMax: Double,
        rhr: Double?,
        cfg: WorkoutConfig,
        maxSpeedCv: Double
    ): SteadySegment? {
        if (distM <= 0.0 || timeS <= 0.0) return null
        val a0 = pts.first().altM ?: return null
        val a1 = pts.last().altM ?: return null
        // One-minute bins of speed; a steady block has a low coefficient of variation.
        val t0 = pts.first().tMs
        val binDist = HashMap<Long, Double>()
        val binTime = HashMap<Long, Double>()
        for (k in 0 until pts.size - 1) {
            val a = pts[k]
            val b = pts[k + 1]
            val bin = (a.tMs - t0) / 60_000L
            binDist[bin] = (binDist[bin] ?: 0.0) + haversineM(a.lat, a.lon, b.lat, b.lon)
            binTime[bin] = (binTime[bin] ?: 0.0) + (b.tMs - a.tMs) / 1000.0
        }
        val speeds = binTime.keys.filter { (binTime[it] ?: 0.0) >= 30.0 }.map { binDist[it]!! / binTime[it]!! }
        if (speeds.size < 2) return null
        val mean = speeds.average()
        val sd = sqrt(speeds.sumOf { (it - mean) * (it - mean) } / speeds.size)
        if (mean <= 0.0 || sd / mean > maxSpeedCv) return null
        val start = pts.first().tMs
        val end = pts.last().tMs
        val stats = HeartRateWorkout.hrWorkout(HrWorkoutInput(hr, start, end, hrMax, rhr, null, null, null), cfg)
        val avg = stats.avgHr ?: return null
        if (stats.coveragePct < cfg.thresholds.keytelMinCoverage * 100.0) return null
        return SteadySegment(speedMps = distM / timeS, grade = (a1 - a0) / distM, hr = avg, durationS = timeS)
    }

    // -- Energy without heart rate --------------------------------------------------------------------

    /**
     * Gross MET for a sport at a moving speed, from the 2024 Adult Compendium of Physical Activities
     * (https://pacompendium.com/walking, /running, /bicycling, /walking hiking). Linear between table points.
     */
    fun metFor(sport: String, speedMps: Double?): Double {
        val kmh = (speedMps ?: 0.0) * 3.6
        val table = when (sport) {
            "walk" -> WALK
            "run" -> RUN
            "cycle" -> CYCLE
            "hike" -> return 6.0
            else -> return 4.0
        }
        if (kmh <= table.first().first) return table.first().second
        if (kmh >= table.last().first) return table.last().second
        for (i in 0 until table.size - 1) {
            val (x0, y0) = table[i]
            val (x1, y1) = table[i + 1]
            if (kmh <= x1) return y0 + (y1 - y0) * (kmh - x0) / (x1 - x0)
        }
        return table.last().second
    }

    /** MET energy over the moving time: MET × 3.5 × kg ÷ 200 per minute (the formula the diary estimator uses). */
    fun metKcal(sport: String, avgSpeedMps: Double?, movingS: Double, weightKg: Double): Int? {
        if (movingS <= 0.0 || !weightKg.isFinite() || weightKg <= 0.0) return null
        val kcal = metFor(sport, avgSpeedMps) * 3.5 * weightKg.coerceIn(35.0, 300.0) / 200.0 * (movingS / 60.0)
        return kcal.roundToInt().takeIf { it > 0 }
    }

    // -- Live display ------------------------------------------------------------------------------------

    /** Moving speed over the last [windowMs] of kept fixes (m/s), or null while standing or paused. */
    fun recentSpeed(kept: List<GpsPoint>, pauses: List<TimeSpan>, nowMs: Long, sport: WorkoutConfig.Sport, windowMs: Long = 30_000L): Double? {
        if (kept.size < 2) return null
        var dist = 0.0
        var time = 0.0
        for (k in kept.size - 1 downTo 1) {
            val p = kept[k]
            val q = kept[k - 1]
            if (p.tMs < nowMs - windowMs) break
            if (inPause(q.tMs, pauses) || inPause(p.tMs, pauses)) continue
            val dt = (p.tMs - q.tMs) / 1000.0
            if (dt <= 0) continue
            dist += haversineM(q.lat, q.lon, p.lat, p.lon)
            time += dt
        }
        if (time <= 0.0) return null
        val v = dist / time
        return v.takeIf { it >= sport.autoPauseSpeedMps }
    }

    /** Heart-rate zone (0–4) of the sample nearest [tMs] within [toleranceMs], for colouring the route. */
    fun zoneAt(tMs: Long, hr: List<HrSample>, hrMax: Double, rhr: Double?, cfg: WorkoutConfig, toleranceMs: Long = 60_000L): Int? {
        if (hr.isEmpty()) return null
        var lo = 0
        var hi = hr.size - 1
        while (lo < hi) {
            val mid = (lo + hi) / 2
            if (hr[mid].tMs < tMs) lo = mid + 1 else hi = mid
        }
        val candidates = listOfNotNull(hr.getOrNull(lo), hr.getOrNull(lo - 1))
        val best = candidates.minByOrNull { kotlin.math.abs(it.tMs - tMs) } ?: return null
        if (kotlin.math.abs(best.tMs - tMs) > toleranceMs) return null
        return HeartRateWorkout.zone(best.bpm, hrMax, rhr, cfg.thresholds)
    }

    private val WALK = listOf(3.2 to 2.8, 4.0 to 3.0, 4.8 to 3.5, 5.6 to 4.3, 6.4 to 5.0, 7.2 to 7.0, 8.0 to 8.3)
    private val RUN = listOf(6.4 to 6.0, 8.0 to 8.3, 9.7 to 9.8, 11.3 to 11.0, 12.9 to 11.8, 14.5 to 12.8, 16.1 to 14.5, 17.7 to 16.0, 19.3 to 19.0)
    private val CYCLE = listOf(12.0 to 3.5, 16.0 to 5.8, 19.0 to 6.8, 22.5 to 8.0, 25.7 to 10.0, 30.6 to 12.0, 35.0 to 15.8)
}
