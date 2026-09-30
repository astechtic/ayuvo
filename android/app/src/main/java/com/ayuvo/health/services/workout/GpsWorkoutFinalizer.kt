package com.ayuvo.health.services.workout

import com.ayuvo.health.AppContainer
import com.ayuvo.health.data.workout.CardioFitness
import com.ayuvo.health.data.workout.CooperInput
import com.ayuvo.health.data.workout.GpsTrack
import com.ayuvo.health.data.workout.GpsWorkoutAnalysis
import com.ayuvo.health.data.workout.HeartRateWorkout
import com.ayuvo.health.data.workout.HrRecoveryInput
import com.ayuvo.health.data.workout.HrSample
import com.ayuvo.health.data.workout.RecordedTrack
import com.ayuvo.health.data.workout.Vo2maxGpsInput
import com.ayuvo.health.data.workout.WorkoutConfig
import com.ayuvo.health.data.workout.WorkoutHeartRateSource
import com.ayuvo.health.models.CompletedExercise
import com.ayuvo.health.models.GpsLap
import com.ayuvo.health.models.GpsSplit
import com.ayuvo.health.models.GpsWorkoutSummary
import com.ayuvo.health.models.WorkoutHeartRateStats
import com.ayuvo.health.models.WorkoutSession
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/**
 * Turns a recorded track into a diary session (docs/workouts-gps.md §3): `gps_track` for distance, splits and
 * elevation, `hr_workout` for heart rate, Keytel energy at ≥70% heart-rate coverage (else the MET estimate),
 * `vo2max_gps` from steady flat segments, `cooper` for the 12-minute test and `hr_recovery` once two minutes of
 * heart rate after End exist. Used by the recording service and by the summary's "Update heart rate".
 */
class GpsWorkoutFinalizer(private val container: AppContainer) {
    private val cfg: WorkoutConfig get() = container.workoutConfig
    private val hrSource: WorkoutHeartRateSource get() = container.workoutHeartRate

    /** Builds the session for a finished [track] (its `endMs` must be set); null when no usable fix was recorded. */
    suspend fun buildSession(track: RecordedTrack, recovery: Boolean): WorkoutSession? {
        val end = track.endMs ?: return null
        val input = track.trackInput(end)
        val kept = GpsWorkoutAnalysis.keptPoints(input, cfg)
        if (kept.size < 2) return null
        val result = GpsTrack.gpsTrack(input, cfg)
        val day = LocalDate.ofInstant(Instant.ofEpochMilli(track.startMs), ZoneId.systemDefault())
        val person = hrSource.person(day)
        val hr = heartRate(track, if (recovery) end + RECOVERY_WINDOW_MS else end)
        val stats = if (hr.any { it.tMs in track.startMs until end }) {
            WorkoutHeartRateSource.statsFor(hr, track.startMs, end, person, cfg)
        } else null
        val sport = cfg.sport(track.sport)

        val keytel = stats?.keytelKcal?.takeIf { it.isFinite() && it > 0 }?.let { Math.round(it).toInt() }
        val met = GpsWorkoutAnalysis.metKcal(track.sport, result.avgSpeedMps, result.movingS, person.weightKg ?: DEFAULT_WEIGHT_KG)
        val kcal = keytel ?: met

        var vo2: Double? = null
        var vo2Status: String
        var vo2Segments = 0
        if (track.sport == "walk" || track.sport == "run") {
            val hrMax = person.hrMax
            val segments = if (hrMax != null) GpsWorkoutAnalysis.steadySegments(input, hr, hrMax, person.rhr, cfg) else emptyList()
            val r = CardioFitness.vo2maxGps(Vo2maxGpsInput(segments, person.rhr, hrMax, track.sport), cfg)
            vo2 = r.vo2max
            vo2Status = r.status
            vo2Segments = r.segmentsUsed
        } else {
            vo2Status = "sport_not_supported"
        }
        val activeMs = track.activeMs(end)
        val cooper = if (track.cooper && activeMs >= COOPER_MS - COOPER_TOLERANCE_MS) {
            CardioFitness.cooper(CooperInput(result.distanceM), cfg).vo2max
        } else null
        val recoveryStats = if (recovery) recovery(hr, end) else null
        val laps = GpsWorkoutAnalysis.laps(input, track.lapMarks, cfg).map { GpsLap(it.index, it.startMs, it.endMs, it.distanceM) }

        return WorkoutSession(
            id = UUID.fromString(track.sessionId),
            diaryDateKey = track.diaryDateKey,
            startedAt = Instant.ofEpochMilli(track.startMs),
            completedAt = Instant.ofEpochMilli(end),
            durationSeconds = (activeMs / 1000).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            exercises = listOf(
                CompletedExercise(
                    itemId = sport.metItemId ?: track.sport,
                    name = sport.title,
                    targetMuscles = emptyList(),
                    equipment = "",
                    sets = emptyList(),
                    durationSeconds = result.movingS
                )
            ),
            caloriesBurned = null,
            healthSyncVersion = null,
            kind = WorkoutSession.KIND_GPS,
            realInterval = true,
            intervalSource = "gps",
            heartRate = stats?.let { s ->
                recoveryStats?.let { s.copy(hrr1 = it.hrr1, hrr1FlagLow = it.flagLow, hrr1Confidence = it.confidence) } ?: s
            },
            gps = GpsWorkoutSummary(
                sport = track.sport,
                distanceM = result.distanceM,
                movingSeconds = result.movingS,
                elapsedSeconds = result.elapsedS,
                avgSpeedMps = result.avgSpeedMps,
                avgPaceSecondsPerKm = result.avgPaceSPerKm,
                maxSpeedMps = result.maxSpeedMps,
                splits = result.splits.map { GpsSplit(it.km, it.seconds) },
                laps = laps,
                elevationGainM = result.elevationGainM,
                elevationLossM = result.elevationLossM,
                altitudeSource = track.altitudeSource,
                activeKcal = kcal,
                kcalMethod = if (keytel != null) "keytel" else if (met != null) "met" else null,
                vo2max = vo2,
                vo2maxStatus = vo2Status,
                vo2maxSegments = vo2Segments,
                cooperTest = track.cooper,
                cooperVo2max = cooper,
                keptPoints = result.keptPoints,
                droppedPoints = result.droppedPoints
            )
        )
    }

    /**
     * Re-reads heart rate for a saved workout (a watch often syncs after the workout ends) and updates heart-rate
     * statistics, recovery, energy and VO₂max. The Health Connect records are rewritten when energy changes.
     */
    suspend fun refreshHeartRate(sessionId: UUID): WorkoutSession? {
        val session = container.workoutRepository.snapshot().completedSessions.firstOrNull { it.id == sessionId && it.isGps } ?: return null
        val track = container.gpsTrackStore.load(sessionId.toString()) ?: return null
        val recoveryDue = System.currentTimeMillis() >= session.completedAt.toEpochMilli() + RECOVERY_WINDOW_MS
        val rebuilt = buildSession(track, recovery = recoveryDue) ?: return null
        val updated = container.workoutRepository.updateGpsSession(sessionId) { old ->
            val oldGps = old.gps ?: return@updateGpsSession old
            val newGps = rebuilt.gps ?: return@updateGpsSession old
            old.copy(
                heartRate = rebuilt.heartRate ?: old.heartRate,
                gps = oldGps.copy(
                    activeKcal = newGps.activeKcal,
                    kcalMethod = newGps.kcalMethod,
                    vo2max = newGps.vo2max,
                    vo2maxStatus = newGps.vo2maxStatus,
                    vo2maxSegments = newGps.vo2maxSegments,
                    cooperVo2max = newGps.cooperVo2max ?: oldGps.cooperVo2max
                )
            )
        }
        if (updated?.gps?.bestVo2max != session.gps?.bestVo2max) runCatching { container.derivedMetrics.refresh() }
        return updated
    }

    /** Health Connect heart rate (fresh) merged with what the recorder polled, by time. */
    private suspend fun heartRate(track: RecordedTrack, toMs: Long): List<HrSample> {
        val read = hrSource.samples(track.startMs - PRE_START_MS, toMs)
        val polled = track.hrSamples()
        if (read.isEmpty()) return polled
        if (polled.isEmpty()) return read
        // Prefer the read source; fill only times it does not cover.
        val merged = read.toMutableList()
        val first = read.first().tMs
        val last = read.last().tMs
        merged += polled.filter { it.tMs < first || it.tMs > last }
        return merged.sortedBy { it.tMs }.distinctBy { it.tMs }
    }

    private fun recovery(hr: List<HrSample>, endMs: Long) =
        HeartRateWorkout.hrRecovery(HrRecoveryInput(hr, endMs), cfg).takeIf { it.hrr1 != null }

    companion object {
        const val RECOVERY_WINDOW_MS = 120_000L
        const val COOPER_MS = 12 * 60_000L
        private const val COOPER_TOLERANCE_MS = 5_000L
        private const val PRE_START_MS = 0L
        private const val DEFAULT_WEIGHT_KG = 70.0

        /** Whether heart-rate statistics are present and not yet complete (recovery pending or low coverage). */
        fun needsHeartRateRefresh(s: WorkoutSession): Boolean {
            val hr: WorkoutHeartRateStats = s.heartRate ?: return true
            return hr.hrr1 == null || hr.coveragePct < 70.0
        }
    }
}
