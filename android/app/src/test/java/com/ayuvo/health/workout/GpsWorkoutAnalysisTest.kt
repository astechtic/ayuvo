package com.ayuvo.health.workout

import com.ayuvo.health.data.derived.DerivedMetricsService
import com.ayuvo.health.data.workout.CardioFitness
import com.ayuvo.health.data.workout.GpsPoint
import com.ayuvo.health.data.workout.GpsTrack
import com.ayuvo.health.data.workout.GpsTrackInput
import com.ayuvo.health.data.workout.GpsWorkoutAnalysis
import com.ayuvo.health.data.workout.HrSample
import com.ayuvo.health.data.workout.RecordedTrack
import com.ayuvo.health.data.workout.TimeSpan
import com.ayuvo.health.data.workout.TrackPoint
import com.ayuvo.health.data.workout.TrackSpan
import com.ayuvo.health.data.workout.Vo2maxGpsInput
import com.ayuvo.health.data.workout.WorkoutHeartRateSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** Platform-side helpers around the shared workout engines (docs/workouts-gps.md). */
class GpsWorkoutAnalysisTest {
    private val cfg = WorkoutTestFiles.config
    private val t0 = 1_780_000_000_000L
    private val metresPerDegree = 6371008.8 * Math.PI / 180.0

    /** A straight run north at [speed] m/s, one fix per second, flat, 5 m accuracy. */
    private fun run(seconds: Int, speed: Double = 2.5, sport: String = "run", pauses: List<TimeSpan> = emptyList()): GpsTrackInput {
        val points = (0..seconds).map { s ->
            GpsPoint(t0 + s * 1000L, 52.0 + s * speed / metresPerDegree, 13.0, 100.0, 5.0, speed)
        }
        return GpsTrackInput(sport, points, pauses, t0, t0 + seconds * 1000L)
    }

    @Test
    fun tanakaMaximumHeartRate() {
        assertEquals(180.0, GpsWorkoutAnalysis.tanakaHrMax(40.0), 1e-9)
        assertEquals(194.0, GpsWorkoutAnalysis.tanakaHrMax(20.0), 1e-9)
    }

    @Test
    fun steadyFlatRunGivesOneSegmentAndAVo2max() {
        val inp = run(600)
        val hr = (0..120).map { HrSample(t0 + it * 5_000L, 150.0) }
        val segments = GpsWorkoutAnalysis.steadySegments(inp, hr, hrMax = 190.0, rhr = 60.0, cfg = cfg)
        assertEquals(1, segments.size)
        val s = segments.single()
        assertEquals(2.5, s.speedMps, 0.01)
        assertEquals(0.0, s.grade, 1e-9)
        assertEquals(150.0, s.hr, 1e-9)
        assertTrue(s.durationS >= cfg.thresholds.minSegmentS)
        val vo2 = CardioFitness.vo2maxGps(Vo2maxGpsInput(segments, 60.0, 190.0, "run"), cfg)
        assertEquals("ok", vo2.status)
        // ACSM running at 150 m/min: 3.5 + (0.2·150) / (90/130) = 46.8 mL/kg/min.
        assertEquals(46.8, vo2.vo2max!!, 0.2)
    }

    @Test
    fun noSegmentWithoutHeartRateOrWhenPausedMidway() {
        assertTrue(GpsWorkoutAnalysis.steadySegments(run(600), emptyList(), 190.0, 60.0, cfg).isEmpty())
        val hr = (0..120).map { HrSample(t0 + it * 5_000L, 150.0) }
        // A manual pause at 5 minutes splits the run into two blocks shorter than six minutes.
        val paused = run(600, pauses = listOf(TimeSpan(t0 + 300_000L, t0 + 310_000L)))
        assertTrue(GpsWorkoutAnalysis.steadySegments(paused, hr, 190.0, 60.0, cfg).isEmpty())
    }

    @Test
    fun lapsSplitTheTrackDistance() {
        val inp = run(400)
        val laps = GpsWorkoutAnalysis.laps(inp, listOf(t0 + 200_000L), cfg)
        assertEquals(2, laps.size)
        val total = GpsTrack.gpsTrack(inp, cfg).distanceM
        assertEquals(total, laps.sumOf { it.distanceM }, 1.0)
        assertEquals(500.0, laps[0].distanceM, 1.0)
    }

    @Test
    fun metEstimateFollowsSpeed() {
        assertEquals(3.5, GpsWorkoutAnalysis.metFor("walk", 4.8 / 3.6), 1e-9)
        assertEquals(8.3, GpsWorkoutAnalysis.metFor("run", 8.0 / 3.6), 1e-9)
        assertEquals(6.0, GpsWorkoutAnalysis.metFor("hike", 1.0), 1e-9)
        // 30 minutes of walking at 4.8 km/h for 70 kg: 3.5 × 3.5 × 70 / 200 × 30.
        assertEquals(129, GpsWorkoutAnalysis.metKcal("walk", 4.8 / 3.6, 1800.0, 70.0))
        assertNull(GpsWorkoutAnalysis.metKcal("walk", 1.3, 0.0, 70.0))
    }

    @Test
    fun recentSpeedAndZoneLookup() {
        val inp = run(120)
        val kept = GpsWorkoutAnalysis.keptPoints(inp, cfg)
        assertEquals(2.5, GpsWorkoutAnalysis.recentSpeed(kept, emptyList(), t0 + 120_000L, cfg.sport("run"))!!, 0.01)
        val hr = listOf(HrSample(t0, 100.0), HrSample(t0 + 60_000L, 170.0))
        // 170 bpm at %HRR (170−60)/(190−60) = 0.85 → vigorous (zone 3).
        assertEquals(3, GpsWorkoutAnalysis.zoneAt(t0 + 55_000L, hr, 190.0, 60.0, cfg))
        assertNull(GpsWorkoutAnalysis.zoneAt(t0 + 10 * 60_000L, hr, 190.0, 60.0, cfg))
    }

    @Test
    fun recordedTrackCountsPausesIncludingTheOpenOne() {
        val track = RecordedTrack(
            sessionId = "00000000-0000-0000-0000-000000000001", diaryDateKey = "2026-09-29", sport = "walk",
            startMs = t0, points = listOf(TrackPoint(t0, 52.0, 13.0, acc = 5.0)),
            pauses = listOf(TrackSpan(t0 + 60_000L, t0 + 120_000L)), openPauseStartMs = t0 + 300_000L
        )
        assertEquals(240_000L, track.activeMs(t0 + 400_000L))
        assertEquals(2, track.trackInput(t0 + 400_000L).pauses.size)
    }

    @Test
    fun heartRateStatsWithoutAgeKeepOnlyAveragesAndCoverage() {
        val hr = (0..60).map { HrSample(t0 + it * 10_000L, 120.0) }
        val person = WorkoutHeartRateSource.Person(hrMax = null, rhr = 60.0, sex = null, age = null, weightKg = 70.0)
        val stats = WorkoutHeartRateSource.statsFor(hr, t0, t0 + 600_000L, person, cfg)
        assertEquals(120.0, stats.avgHr!!, 1e-9)
        assertTrue(stats.coveragePct >= 99.0)
        assertTrue(stats.zoneSeconds.isEmpty())
        assertNull(stats.keytelKcal)
        val aged = WorkoutHeartRateSource.statsFor(hr, t0, t0 + 600_000L, person.copy(hrMax = 180.0, age = 40.0), cfg)
        assertEquals(5, aged.zoneSeconds.size)
        assertNotNull(aged.keytelKcal)
        assertNotNull(aged.trimp)
    }

    @Test
    fun minuteSeriesAveragesPerMinute() {
        val series = WorkoutHeartRateSource.minuteSeries(
            listOf(HrSample(60_000L, 100.0), HrSample(90_000L, 110.0), HrSample(180_000L, 120.0))
        )!!
        assertEquals(60_000L, series.startMs)
        assertEquals(listOf(105.0, null, 120.0), series.values)
    }

    @Test
    fun latestGpsVo2maxWinsWithinTheLookback() {
        val d = LocalDate.of(2026, 9, 29)
        val values = listOf(d.minusDays(400) to 50.0, d.minusDays(30) to 44.0, d.minusDays(3) to 45.5, d.plusDays(1) to 60.0)
        assertEquals(45.5, DerivedMetricsService.latestGpsVo2max(values, d)!!, 1e-9)
        assertEquals(44.0, DerivedMetricsService.latestGpsVo2max(values, d.minusDays(10))!!, 1e-9)
        assertNull(DerivedMetricsService.latestGpsVo2max(values.take(1), d))
    }
}
