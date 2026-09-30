package com.ayuvo.health.services.workout

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.location.LocationListenerCompat
import androidx.core.location.LocationManagerCompat
import androidx.core.location.LocationRequestCompat
import com.ayuvo.health.AppContainer
import com.ayuvo.health.AyuvoApp
import com.ayuvo.health.R
import com.ayuvo.health.data.workout.GpsTrack
import com.ayuvo.health.data.workout.GpsWorkoutAnalysis
import com.ayuvo.health.data.workout.RecordedTrack
import com.ayuvo.health.data.workout.TrackHr
import com.ayuvo.health.data.workout.TrackPoint
import com.ayuvo.health.data.workout.TrackSpan
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlin.math.floor

enum class OutdoorPhase { RECORDING, PAUSED, SAVING, RECOVERY, DONE }

/** What the live screen and the notification show; published by [OutdoorWorkoutService] on every recompute. */
data class OutdoorLiveState(
    val phase: OutdoorPhase,
    val sessionId: String,
    val sport: String,
    val sportTitle: String,
    val cooper: Boolean,
    val startMs: Long,
    /** Active (unpaused) time at [snapshotMs]; the UI adds wall time while [phase] is RECORDING. */
    val activeMs: Long,
    val snapshotMs: Long,
    val distanceM: Double = 0.0,
    val avgPaceSPerKm: Double? = null,
    val avgSpeedMps: Double? = null,
    val currentSpeedMps: Double? = null,
    val heartRate: Double? = null,
    val elevationGainM: Double = 0.0,
    val elevationLossM: Double = 0.0,
    val splitKm: Int = 1,
    val splitDistanceM: Double = 0.0,
    val splitSeconds: Double = 0.0,
    val lastSplitSeconds: Double? = null,
    val laps: Int = 0,
    val accuracyM: Double? = null,
    val hasFix: Boolean = false,
    val altitudeSource: String? = null,
    val recoveryEndsMs: Long? = null,
    val savedSessionId: String? = null,
    val message: String? = null
) {
    fun activeNow(nowMs: Long): Long = if (phase == OutdoorPhase.RECORDING) activeMs + (nowMs - snapshotMs).coerceAtLeast(0) else activeMs
}

/**
 * GPS outdoor workout recorder (docs/workouts-gps.md §4): a `location` foreground service started while the app is
 * in the foreground (When-In-Use location only, no background-location permission). Location comes from the
 * platform [LocationManager] via [LocationManagerCompat] — the fused provider on API 31+, GPS otherwise — with no
 * Play Services. Altitude comes from the barometer when the phone has one. Live numbers re-run `gps_track` over the
 * collected fixes (throttled); the track is persisted to a file for crash recovery.
 */
class OutdoorWorkoutService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var container: AppContainer
    private var track: RecordedTrack? = null
    private var locationListener: LocationListenerCompat? = null
    private var sensorListener: SensorEventListener? = null
    private var pressureAltitude: Double? = null
    private var lastRecomputeMs = 0L
    private var lastPersistMs = 0L
    private var lastNotifyMs = 0L
    private var lastAccuracy: Double? = null
    private var hrJob: Job? = null
    private var tickJob: Job? = null
    private var finishing = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        container = (application as AyuvoApp).container
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> start(intent.getStringExtra(EXTRA_SPORT) ?: "walk", intent.getBooleanExtra(EXTRA_COOPER, false))
            ACTION_RESUME_RECOVERED -> resumeRecovered()
            ACTION_SAVE_RECOVERED -> saveRecovered()
            ACTION_PAUSE -> pause()
            ACTION_RESUME -> resume()
            ACTION_LAP -> lap()
            ACTION_END -> end()
            ACTION_DISCARD -> discard()
            ACTION_SKIP_RECOVERY -> skipRecovery()
            else -> if (track == null) {
                // Restarted by the system without an intent: keep what was recorded and let the app offer recovery.
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopSensors()
        scope.cancel()
        super.onDestroy()
    }

    // -- Commands -------------------------------------------------------------------------------------

    private fun start(sport: String, cooper: Boolean) {
        if (track != null) return
        val cfg = container.workoutConfig
        if (cfg.sports[sport] == null) return abandonStart()
        val now = System.currentTimeMillis()
        val t = RecordedTrack(
            sessionId = UUID.randomUUID().toString(),
            diaryDateKey = LocalDate.now().toString(),
            sport = sport,
            cooper = cooper && (sport == "run" || sport == "walk"),
            startMs = now,
            altitudeSource = if (hasBarometer()) "barometer" else "gps",
            lastAliveMs = now
        )
        begin(t)
    }

    private fun resumeRecovered() {
        if (track != null) return
        val saved = container.gpsTrackStore.loadInProgress() ?: return abandonStart()
        if (saved.endMs != null) return finishSaved(saved)
        val now = System.currentTimeMillis()
        // The time the recorder was not running counts as a manual pause.
        val gap = if (saved.openPauseStartMs == null && now > saved.lastAliveMs) listOf(TrackSpan(saved.lastAliveMs, now)) else emptyList()
        begin(saved.copy(pauses = saved.pauses + gap, lastAliveMs = now))
    }

    private fun saveRecovered() {
        if (track != null) return
        val saved = container.gpsTrackStore.loadInProgress() ?: return abandonStart()
        val end = maxOf(saved.lastAliveMs, saved.points.lastOrNull()?.t ?: saved.startMs, saved.startMs + 1_000)
        finishSaved(saved.copy(endMs = saved.endMs ?: end, openPauseStartMs = null,
            pauses = saved.pauses + listOfNotNull(saved.openPauseStartMs?.let { TrackSpan(it, end) })))
    }

    private fun finishSaved(t: RecordedTrack) {
        track = t
        if (!goForeground(notificationFor(snapshot(t, OutdoorPhase.SAVING)))) return
        finish(t)
    }

    private fun begin(t: RecordedTrack) {
        track = t
        val state = snapshot(t, if (t.openPauseStartMs != null) OutdoorPhase.PAUSED else OutdoorPhase.RECORDING)
        if (!goForeground(notificationFor(state))) {
            track = null
            return
        }
        publish(state)
        persist(force = true)
        startLocation()
        startBarometer()
        startHeartRatePolling()
        startTicker()
    }

    /** A startForegroundService call must reach startForeground even when there is nothing to record. */
    private fun abandonStart() {
        goForeground(WorkoutNotifications.build(this, WorkoutNotifications.Content(getString(R.string.workout_live_channel), "")))
        stopForegroundCompat()
        stopSelf()
    }

    private fun pause() {
        val t = track ?: return
        if (t.openPauseStartMs != null || t.endMs != null) return
        track = t.copy(openPauseStartMs = System.currentTimeMillis())
        recompute(force = true)
        persist(force = true)
    }

    private fun resume() {
        val t = track ?: return
        val open = t.openPauseStartMs ?: return
        val now = System.currentTimeMillis()
        track = t.copy(openPauseStartMs = null, pauses = t.pauses + TrackSpan(open, now))
        recompute(force = true)
        persist(force = true)
    }

    private fun lap() {
        val t = track ?: return
        if (t.endMs != null) return
        track = t.copy(lapMarks = t.lapMarks + System.currentTimeMillis())
        recompute(force = true)
        persist(force = true)
    }

    private fun end() {
        val t = track ?: return
        if (t.endMs != null || finishing) return
        val now = System.currentTimeMillis()
        val closed = t.copy(
            endMs = now,
            openPauseStartMs = null,
            pauses = t.pauses + listOfNotNull(t.openPauseStartMs?.let { TrackSpan(it, now) }),
            lastAliveMs = now
        )
        track = closed
        finish(closed)
    }

    private fun discard() {
        stopSensors()
        container.gpsTrackStore.clearInProgress()
        track = null
        _live.value = null
        stopForegroundCompat()
        stopSelf()
    }

    private fun skipRecovery() {
        recoveryJob?.cancel()
        val id = _live.value?.savedSessionId
        if (id != null) {
            scope.launch {
                runCatching { GpsWorkoutFinalizer(container).refreshHeartRate(UUID.fromString(id)) }
                done(id)
            }
        } else {
            done(null)
        }
    }

    private var recoveryJob: Job? = null

    /** Saves the diary session and Health records, then measures one-minute recovery if heart rate is readable. */
    private fun finish(t: RecordedTrack) {
        finishing = true
        stopSensors()
        container.gpsTrackStore.saveInProgress(t)
        val saving = snapshot(t, OutdoorPhase.SAVING)
        publish(saving)
        notify(saving, force = true)
        scope.launch {
            val finalizer = GpsWorkoutFinalizer(container)
            val session = withContext(Dispatchers.IO) {
                runCatching { finalizer.buildSession(t, recovery = false) }
                    .onFailure { Log.w(TAG, "Workout analysis failed: ${it.javaClass.simpleName}") }
                    .getOrNull()
            }
            if (session == null) {
                withContext(Dispatchers.IO) { container.gpsTrackStore.clearInProgress() }
                _live.value = saving.copy(phase = OutdoorPhase.DONE, message = getString(R.string.workout_summary_no_route))
                stopForegroundCompat()
                stopSelf()
                return@launch
            }
            withContext(Dispatchers.IO) {
                container.gpsTrackStore.saveFinished(t)
                container.gpsTrackStore.clearInProgress()
            }
            runCatching { container.workoutRepository.saveGpsSession(session) }
                .onFailure { Log.w(TAG, "Workout save failed: ${it.javaClass.simpleName}") }
            if (session.gps?.bestVo2max != null) scope.launch(Dispatchers.Default) { runCatching { container.derivedMetrics.refresh() } }

            val canReadHr = runCatching { container.health.isAvailable() && container.health.hasHeartRateRead() }.getOrDefault(false) ||
                t.hr.isNotEmpty()
            val end = t.endMs ?: System.currentTimeMillis()
            if (!canReadHr) return@launch done(session.id.toString())
            val recoveryEnd = end + GpsWorkoutFinalizer.RECOVERY_WINDOW_MS
            val recovering = saving.copy(phase = OutdoorPhase.RECOVERY, recoveryEndsMs = recoveryEnd, savedSessionId = session.id.toString())
            publish(recovering)
            notify(recovering, force = true)
            recoveryJob = scope.launch {
                delay((recoveryEnd - System.currentTimeMillis()).coerceAtLeast(0) + RECOVERY_SYNC_GRACE_MS)
                withContext(Dispatchers.IO) { runCatching { finalizer.refreshHeartRate(session.id) } }
                done(session.id.toString())
            }
        }
    }

    private fun done(sessionId: String?) {
        val last = _live.value
        _live.value = last?.copy(phase = OutdoorPhase.DONE, savedSessionId = sessionId ?: last.savedSessionId)
        track = null
        finishing = false
        stopForegroundCompat()
        stopSelf()
    }

    // -- Sensors ------------------------------------------------------------------------------------

    @SuppressLint("MissingPermission")
    private fun startLocation() {
        if (!hasFineLocation(this)) return
        val lm = getSystemService(LocationManager::class.java) ?: return
        val provider = if (Build.VERSION.SDK_INT >= 31 && lm.allProviders.contains(LocationManager.FUSED_PROVIDER)) {
            LocationManager.FUSED_PROVIDER
        } else LocationManager.GPS_PROVIDER
        val request = LocationRequestCompat.Builder(LOCATION_INTERVAL_MS)
            .setQuality(LocationRequestCompat.QUALITY_HIGH_ACCURACY)
            .setMinUpdateIntervalMillis(LOCATION_INTERVAL_MS)
            .build()
        val listener = LocationListenerCompat { onLocation(it) }
        locationListener = listener
        runCatching {
            LocationManagerCompat.requestLocationUpdates(lm, provider, request, ContextCompat.getMainExecutor(this), listener)
        }.onFailure { Log.w(TAG, "Location updates failed: ${it.javaClass.simpleName}") }
    }

    private fun onLocation(location: Location) {
        val t = track ?: return
        if (t.endMs != null) return
        // Fix time on the wall clock: the fix's age by the monotonic clock, not the GPS time.
        val ageMs = (SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos) / 1_000_000L
        val tMs = System.currentTimeMillis() - ageMs.coerceIn(0, 60_000)
        if (t.points.isNotEmpty() && tMs <= t.points.last().t) return
        val alt = pressureAltitude ?: if (location.hasAltitude()) location.altitude else null
        val point = TrackPoint(
            t = tMs,
            lat = location.latitude,
            lon = location.longitude,
            alt = alt,
            acc = if (location.hasAccuracy()) location.accuracy.toDouble() else null,
            spd = if (location.hasSpeed()) location.speed.toDouble() else null
        )
        lastAccuracy = point.acc
        track = t.copy(points = t.points + point, lastAliveMs = tMs)
        recompute(force = false)
        persist(force = false)
    }

    private fun hasBarometer(): Boolean =
        getSystemService(SensorManager::class.java)?.getDefaultSensor(Sensor.TYPE_PRESSURE) != null

    private fun startBarometer() {
        val sm = getSystemService(SensorManager::class.java) ?: return
        val sensor = sm.getDefaultSensor(Sensor.TYPE_PRESSURE) ?: return
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val hPa = event.values.firstOrNull() ?: return
                if (hPa <= 0f) return
                val alt = SensorManager.getAltitude(SensorManager.PRESSURE_STANDARD_ATMOSPHERE, hPa).toDouble()
                // Light smoothing: barometric noise is ~0.1 hPa (about 1 m).
                pressureAltitude = pressureAltitude?.let { it + 0.2 * (alt - it) } ?: alt
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        sensorListener = listener
        sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL)
    }

    /** Live heart rate: recent Health Connect samples (a watch or strap that writes live to Health Connect). */
    private fun startHeartRatePolling() {
        hrJob?.cancel()
        hrJob = scope.launch {
            while (isActive) {
                val t = track ?: break
                if (t.endMs != null) break
                val now = System.currentTimeMillis()
                val from = maxOf(t.startMs, t.hr.lastOrNull()?.t?.plus(1) ?: t.startMs, now - HR_LOOKBACK_MS)
                val samples = withContext(Dispatchers.IO) {
                    runCatching {
                        if (!container.health.isAvailable()) null
                        else container.health.readHeartRateSamples(Instant.ofEpochMilli(from), Instant.ofEpochMilli(now + 1))
                    }.getOrNull()
                }
                if (samples == null) {
                    delay(HR_POLL_MS * 4)
                    continue
                }
                val cur = track ?: break
                val known = cur.hr.lastOrNull()?.t ?: Long.MIN_VALUE
                val fresh = samples.filter { it.first.toEpochMilli() > known }.map { TrackHr(it.first.toEpochMilli(), it.second.toDouble()) }
                if (fresh.isNotEmpty()) {
                    track = cur.copy(hr = cur.hr + fresh)
                    recompute(force = true)
                }
                delay(HR_POLL_MS)
            }
        }
    }

    /** Once a second: Cooper auto-end and a heartbeat for the crash-recovery file. */
    private fun startTicker() {
        tickJob?.cancel()
        tickJob = scope.launch {
            while (isActive) {
                delay(1_000)
                val t = track ?: break
                if (t.endMs != null) break
                val now = System.currentTimeMillis()
                if (t.cooper && t.activeMs(now) >= GpsWorkoutFinalizer.COOPER_MS) {
                    end()
                    break
                }
                if (now - lastRecomputeMs >= 5_000) recompute(force = true)
                if (now - lastPersistMs >= PERSIST_MS) {
                    track = t.copy(lastAliveMs = now)
                    persist(force = true)
                }
            }
        }
    }

    private fun stopSensors() {
        locationListener?.let { l ->
            getSystemService(LocationManager::class.java)?.let { lm -> runCatching { LocationManagerCompat.removeUpdates(lm, l) } }
        }
        locationListener = null
        sensorListener?.let { getSystemService(SensorManager::class.java)?.unregisterListener(it) }
        sensorListener = null
        hrJob?.cancel()
        tickJob?.cancel()
    }

    // -- Live numbers -----------------------------------------------------------------------------------

    private fun recompute(force: Boolean) {
        val t = track ?: return
        val now = System.currentTimeMillis()
        if (!force && now - lastRecomputeMs < RECOMPUTE_MS) return
        lastRecomputeMs = now
        val phase = when {
            t.endMs != null -> OutdoorPhase.SAVING
            t.openPauseStartMs != null -> OutdoorPhase.PAUSED
            else -> OutdoorPhase.RECORDING
        }
        val state = snapshot(t, phase)
        publish(state)
        notify(state, force)
    }

    private fun snapshot(t: RecordedTrack, phase: OutdoorPhase): OutdoorLiveState {
        val cfg = container.workoutConfig
        val now = t.endMs ?: System.currentTimeMillis()
        val input = t.trackInput(now)
        val result = GpsTrack.gpsTrack(input, cfg)
        val sport = cfg.sport(t.sport)
        val kept = GpsWorkoutAnalysis.keptPoints(input, cfg)
        val done = result.splits.sumOf { it.seconds }
        val latestHr = t.hr.lastOrNull()?.takeIf { now - it.t <= LIVE_HR_MAX_AGE_MS }?.bpm
        return OutdoorLiveState(
            phase = phase,
            sessionId = t.sessionId,
            sport = t.sport,
            sportTitle = sport.title,
            cooper = t.cooper,
            startMs = t.startMs,
            activeMs = t.activeMs(now),
            snapshotMs = now,
            distanceM = result.distanceM,
            avgPaceSPerKm = result.avgPaceSPerKm,
            avgSpeedMps = result.avgSpeedMps,
            currentSpeedMps = GpsWorkoutAnalysis.recentSpeed(kept, input.pauses, now, sport),
            heartRate = latestHr,
            elevationGainM = result.elevationGainM,
            elevationLossM = result.elevationLossM,
            splitKm = floor(result.distanceM / 1000.0).toInt() + 1,
            splitDistanceM = result.distanceM - floor(result.distanceM / 1000.0) * 1000.0,
            splitSeconds = (result.movingS - done).coerceAtLeast(0.0),
            lastSplitSeconds = result.splits.lastOrNull()?.seconds,
            laps = t.lapMarks.size,
            accuracyM = lastAccuracy,
            hasFix = t.points.isNotEmpty(),
            altitudeSource = t.altitudeSource,
            savedSessionId = _live.value?.savedSessionId
        )
    }

    private fun publish(state: OutdoorLiveState) {
        _live.value = state
    }

    private fun persist(force: Boolean) {
        val t = track ?: return
        val now = System.currentTimeMillis()
        if (!force && now - lastPersistMs < PERSIST_MS) return
        lastPersistMs = now
        scope.launch(Dispatchers.IO) { runCatching { container.gpsTrackStore.saveInProgress(t) } }
    }

    // -- Notification -----------------------------------------------------------------------------------

    private fun notify(state: OutdoorLiveState, force: Boolean) {
        val now = System.currentTimeMillis()
        if (!force && now - lastNotifyMs < NOTIFY_MS) return
        lastNotifyMs = now
        if (!WorkoutNotifications.canPost(this)) return
        runCatching { NotificationManagerCompat.from(this).notify(WorkoutNotifications.GPS_NOTIFICATION_ID, notificationFor(state)) }
    }

    private fun notificationFor(s: OutdoorLiveState): android.app.Notification {
        val now = System.currentTimeMillis()
        val pace = if (WorkoutFormat.usesSpeed(s.sport)) WorkoutFormat.speedKmh(s.currentSpeedMps ?: s.avgSpeedMps)
        else WorkoutFormat.paceFromSpeed(s.currentSpeedMps)
        val numbers = listOfNotNull(
            WorkoutFormat.distanceKm(s.distanceM),
            pace,
            s.heartRate?.let { WorkoutFormat.bpm(it) }
        ).joinToString(" · ")
        val actions = when (s.phase) {
            OutdoorPhase.RECORDING -> listOf(
                action(ACTION_PAUSE, R.string.workout_action_pause, 1),
                action(ACTION_LAP, R.string.workout_action_lap, 2),
                action(ACTION_END, R.string.workout_action_end, 3)
            )
            OutdoorPhase.PAUSED -> listOf(
                action(ACTION_RESUME, R.string.workout_action_resume, 4),
                action(ACTION_END, R.string.workout_action_end, 3)
            )
            OutdoorPhase.RECOVERY -> listOf(action(ACTION_SKIP_RECOVERY, R.string.workout_action_skip, 5))
            else -> emptyList()
        }
        val content = when (s.phase) {
            OutdoorPhase.RECORDING, OutdoorPhase.PAUSED -> WorkoutNotifications.Content(
                title = if (s.phase == OutdoorPhase.PAUSED) "${s.sportTitle} · ${getString(R.string.workout_live_paused)}" else s.sportTitle,
                text = if (s.hasFix) numbers else getString(R.string.workout_live_waiting_gps),
                chronometerBaseMs = if (s.phase == OutdoorPhase.RECORDING) now - s.activeNow(now) else null,
                progress = if (s.cooper) ((s.activeNow(now).toDouble() / GpsWorkoutFinalizer.COOPER_MS) * 1000).toInt()
                else s.splitDistanceM.toInt(),
                progressMax = 1000,
                chip = WorkoutFormat.distanceKm(s.distanceM),
                actions = actions
            )
            OutdoorPhase.SAVING -> WorkoutNotifications.Content(
                title = s.sportTitle,
                text = getString(R.string.workout_live_saving) + " " + numbers
            )
            OutdoorPhase.RECOVERY, OutdoorPhase.DONE -> WorkoutNotifications.Content(
                title = getString(R.string.workout_live_recovery_title),
                text = getString(R.string.workout_live_recovery_text),
                chronometerBaseMs = s.recoveryEndsMs,
                countDown = true,
                actions = actions
            )
        }
        return WorkoutNotifications.build(this, content)
    }

    private fun action(action: String, title: Int, code: Int) = WorkoutNotifications.Action(
        R.drawable.ic_widget_walk,
        getString(title),
        PendingIntent.getService(
            this, 100 + code,
            Intent(this, OutdoorWorkoutService::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    )

    private fun goForeground(notification: android.app.Notification): Boolean {
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
        return runCatching {
            ServiceCompat.startForeground(this, WorkoutNotifications.GPS_NOTIFICATION_ID, notification, type)
        }.onFailure {
            // Location permission missing or a background start: nothing is recorded.
            Log.w(TAG, "Foreground start failed: ${it.javaClass.simpleName}")
            _live.value = null
            stopSelf()
        }.isSuccess
    }

    private fun stopForegroundCompat() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
    }

    companion object {
        private const val TAG = "AyuvoWorkout"
        const val ACTION_START = "com.ayuvo.health.workout.START"
        const val ACTION_PAUSE = "com.ayuvo.health.workout.PAUSE"
        const val ACTION_RESUME = "com.ayuvo.health.workout.RESUME"
        const val ACTION_LAP = "com.ayuvo.health.workout.LAP"
        const val ACTION_END = "com.ayuvo.health.workout.END"
        const val ACTION_DISCARD = "com.ayuvo.health.workout.DISCARD"
        const val ACTION_SKIP_RECOVERY = "com.ayuvo.health.workout.SKIP_RECOVERY"
        const val ACTION_RESUME_RECOVERED = "com.ayuvo.health.workout.RESUME_RECOVERED"
        const val ACTION_SAVE_RECOVERED = "com.ayuvo.health.workout.SAVE_RECOVERED"
        const val EXTRA_SPORT = "sport"
        const val EXTRA_COOPER = "cooper"

        private const val LOCATION_INTERVAL_MS = 1_000L
        private const val RECOMPUTE_MS = 2_000L
        private const val NOTIFY_MS = 3_000L
        private const val PERSIST_MS = 10_000L
        private const val HR_POLL_MS = 10_000L
        private const val HR_LOOKBACK_MS = 5 * 60_000L
        private const val LIVE_HR_MAX_AGE_MS = 90_000L
        private const val RECOVERY_SYNC_GRACE_MS = 15_000L

        private val _live = MutableStateFlow<OutdoorLiveState?>(null)
        /** The recording (or the one just finished) in this process; null when none. */
        val live: StateFlow<OutdoorLiveState?> = _live.asStateFlow()

        fun hasFineLocation(context: Context): Boolean =
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

        /** Starts a recording; call only from the foreground UI after the location permission was granted. */
        fun start(context: Context, sport: String, cooper: Boolean) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, OutdoorWorkoutService::class.java).setAction(ACTION_START)
                    .putExtra(EXTRA_SPORT, sport).putExtra(EXTRA_COOPER, cooper)
            )
        }

        /** Sends a control action (pause, resume, lap, end, discard, skip, recovery) to the running service. */
        fun send(context: Context, action: String) {
            val intent = Intent(context, OutdoorWorkoutService::class.java).setAction(action)
            if (action == ACTION_RESUME_RECOVERED || action == ACTION_SAVE_RECOVERED) {
                ContextCompat.startForegroundService(context, intent)
            } else {
                runCatching { context.startService(intent) }
            }
        }

        /** Clears a finished workout's live state once the UI has shown its summary. */
        fun acknowledgeDone() {
            if (_live.value?.phase == OutdoorPhase.DONE) _live.value = null
        }
    }
}
