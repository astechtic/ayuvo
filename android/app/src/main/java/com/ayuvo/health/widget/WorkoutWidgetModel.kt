package com.ayuvo.health.widget

import com.ayuvo.health.models.ActiveStrengthSession
import com.ayuvo.health.services.workout.OutdoorLiveState
import com.ayuvo.health.services.workout.OutdoorPhase
import com.ayuvo.health.services.workout.WorkoutFormat

/**
 * Workout widget contract (docs/widgets.md "Workout"). Pure Kotlin so the live-state mapping, the refresh throttle
 * and the start-intent parsing are unit-tested without Android.
 */

/** A start button on the idle widget. GPS sports use the workout config ids. */
enum class WorkoutWidgetStart(val id: String, val gpsSport: String?) {
    WALK("walk", "walk"),
    RUN("run", "run"),
    CYCLE("cycle", "cycle"),
    HIKE("hike", "hike"),
    STRENGTH("strength", null);

    companion object {
        fun fromId(id: String?): WorkoutWidgetStart? = entries.firstOrNull { it.id == id }
    }
}

enum class WorkoutWidgetStatus { RECORDING, PAUSED, SAVING, RECOVERY, STRENGTH }

/** Buttons on the active widget; GPS controls go to the running service, Finish to [com.ayuvo.health.services.workout.WorkoutActionReceiver]. */
enum class WorkoutWidgetControl { PAUSE, RESUME, LAP, END, SKIP_RECOVERY, FINISH_STRENGTH }

sealed interface WorkoutWidgetModel {
    data object Idle : WorkoutWidgetModel

    data class Active(
        /** Workout config sport id, or [STRENGTH_SPORT]. */
        val sport: String,
        /** The sport title from the workout config; null for a strength session (the widget uses its own string). */
        val title: String?,
        val status: WorkoutWidgetStatus,
        /** Elapsed active time at render time; the remaining recovery time when [countDown]. */
        val elapsedMs: Long,
        /** The chronometer runs (recording, recovery countdown, strength session); false when paused or saving. */
        val ticking: Boolean,
        val countDown: Boolean,
        /** "2.41 km"; null for a strength session. */
        val distanceText: String?,
        /** Average pace ("5:37 /km"), or average speed for cycling; null for a strength session. */
        val paceText: String?,
        val paceIsSpeed: Boolean,
        /** Recording or paused before the first fix: the widget says "Waiting for GPS" instead of numbers. */
        val waitingForGps: Boolean,
        val controls: List<WorkoutWidgetControl>
    ) : WorkoutWidgetModel

    companion object {
        const val STRENGTH_SPORT = "strength"
    }
}

object WorkoutWidgetMapper {
    /**
     * A running GPS workout wins over a strength session. A finished GPS workout (DONE) is idle again, or shows the
     * strength session when one is running. The widget refreshes about every 30 s, so it shows average pace, not the
     * momentary pace the notification shows.
     */
    fun map(live: OutdoorLiveState?, strength: ActiveStrengthSession?, nowMs: Long): WorkoutWidgetModel {
        if (live != null && live.phase != OutdoorPhase.DONE) return gps(live, nowMs)
        if (strength != null) {
            return WorkoutWidgetModel.Active(
                sport = WorkoutWidgetModel.STRENGTH_SPORT,
                title = null,
                status = WorkoutWidgetStatus.STRENGTH,
                elapsedMs = (nowMs - strength.startedAt.toEpochMilli()).coerceAtLeast(0),
                ticking = true,
                countDown = false,
                distanceText = null,
                paceText = null,
                paceIsSpeed = false,
                waitingForGps = false,
                controls = listOf(WorkoutWidgetControl.FINISH_STRENGTH)
            )
        }
        return WorkoutWidgetModel.Idle
    }

    private fun gps(live: OutdoorLiveState, nowMs: Long): WorkoutWidgetModel.Active {
        val speed = WorkoutFormat.usesSpeed(live.sport)
        val recovery = live.phase == OutdoorPhase.RECOVERY
        val status = when (live.phase) {
            OutdoorPhase.RECORDING -> WorkoutWidgetStatus.RECORDING
            OutdoorPhase.PAUSED -> WorkoutWidgetStatus.PAUSED
            OutdoorPhase.SAVING -> WorkoutWidgetStatus.SAVING
            else -> WorkoutWidgetStatus.RECOVERY
        }
        val controls = when (live.phase) {
            OutdoorPhase.RECORDING -> listOf(WorkoutWidgetControl.PAUSE, WorkoutWidgetControl.LAP, WorkoutWidgetControl.END)
            OutdoorPhase.PAUSED -> listOf(WorkoutWidgetControl.RESUME, WorkoutWidgetControl.END)
            OutdoorPhase.RECOVERY -> listOf(WorkoutWidgetControl.SKIP_RECOVERY)
            else -> emptyList()
        }
        return WorkoutWidgetModel.Active(
            sport = live.sport,
            title = live.sportTitle.ifBlank { null },
            status = status,
            elapsedMs = if (recovery) ((live.recoveryEndsMs ?: nowMs) - nowMs).coerceAtLeast(0) else live.activeNow(nowMs),
            ticking = live.phase == OutdoorPhase.RECORDING || recovery,
            countDown = recovery,
            distanceText = WorkoutFormat.distanceKm(live.distanceM),
            paceText = if (speed) WorkoutFormat.speedKmh(live.avgSpeedMps) else WorkoutFormat.pace(live.avgPaceSPerKm),
            paceIsSpeed = speed,
            waitingForGps = !live.hasFix && (live.phase == OutdoorPhase.RECORDING || live.phase == OutdoorPhase.PAUSED),
            controls = controls
        )
    }
}

/**
 * When the widget is re-rendered: on every phase change (start, pause, resume, end, recovery, done, strength
 * start/finish) and, while a GPS workout records, at most every [DISTANCE_REFRESH_MS] for its distance.
 */
object WorkoutWidgetThrottle {
    const val DISTANCE_REFRESH_MS = 30_000L

    /** [gpsHasFix] is part of the key so "Waiting for GPS" is replaced as soon as the first fix arrives. */
    data class Key(val gpsSessionId: String?, val gpsPhase: OutdoorPhase?, val gpsHasFix: Boolean, val strengthStartMs: Long?)

    fun keyOf(live: OutdoorLiveState?, strength: ActiveStrengthSession?): Key =
        Key(live?.sessionId, live?.phase, live?.hasFix == true, strength?.startedAt?.toEpochMilli())

    fun shouldPublish(previous: Key?, next: Key, lastPublishMs: Long, nowMs: Long): Boolean =
        previous != next ||
            (next.gpsPhase == OutdoorPhase.RECORDING && nowMs - lastPublishMs >= DISTANCE_REFRESH_MS)
}

/** What a widget tap asks the app to do; `start == null` opens the running workout (or the workout log). */
data class WorkoutWidgetLaunch(val start: WorkoutWidgetStart?, val id: Long = System.nanoTime())

/** Intent contract between the Workout widget and MainActivity (docs/widgets.md "Deep links"). */
object WorkoutWidgetIntentContract {
    const val ACTION_START = "com.ayuvo.health.workout.WIDGET_START"
    const val ACTION_OPEN = "com.ayuvo.health.workout.WIDGET_OPEN"
    const val EXTRA_START = "workout_start"

    /** An unknown start id opens the workout log rather than guessing a sport; foreign actions return null. */
    fun launchFrom(action: String?, startId: String?): WorkoutWidgetLaunch? = when (action) {
        ACTION_START -> WorkoutWidgetLaunch(WorkoutWidgetStart.fromId(startId))
        ACTION_OPEN -> WorkoutWidgetLaunch(null)
        else -> null
    }
}
