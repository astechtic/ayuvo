package com.ayuvo.health.widget

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.glance.appwidget.updateAll
import com.ayuvo.health.MainActivity
import com.ayuvo.health.models.ActiveStrengthSession
import com.ayuvo.health.services.workout.OutdoorLiveState
import com.ayuvo.health.services.workout.OutdoorWorkoutService
import com.ayuvo.health.services.workout.WorkoutActionReceiver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

/**
 * Feeds the Workout widget (docs/widgets.md "Workout"). It watches the GPS recorder's live state and the persisted
 * strength session, and re-renders the widget on every phase change (GPS start, pause, resume, end, recovery, done;
 * strength start and finish) and at most every 30 s for the distance while recording. The widget reads [inputs],
 * which only moves when a render is due, so an open Glance session does not recompose on every GPS fix.
 */
object WorkoutWidgetSync {
    data class Inputs(val live: OutdoorLiveState?, val strength: ActiveStrengthSession?)

    private val _inputs = MutableStateFlow(Inputs(null, null))
    val inputs: StateFlow<Inputs> = _inputs.asStateFlow()

    private var lastKey: WorkoutWidgetThrottle.Key? = null
    private var lastPublishMs = 0L

    fun observe(context: Context, scope: CoroutineScope, strength: Flow<ActiveStrengthSession?>): Job {
        val app = context.applicationContext
        return combine(OutdoorWorkoutService.live, strength.catch { emit(null) }) { live, session -> Inputs(live, session) }
            .onEach { next ->
                val now = System.currentTimeMillis()
                val key = WorkoutWidgetThrottle.keyOf(next.live, next.strength)
                if (!WorkoutWidgetThrottle.shouldPublish(lastKey, key, lastPublishMs, now)) return@onEach
                lastKey = key
                lastPublishMs = now
                _inputs.value = next
                runCatching { WorkoutAppWidget().updateAll(app) }
                    .onFailure { Log.e(TAG, "Workout widget update failed", it) }
                // Quick Settings tile (docs/widgets.md "Lock Screen starters"): re-read on every phase change.
                WorkoutTileService.requestRefresh(app)
            }
            .launchIn(scope)
    }

    private const val TAG = "AyuvoWidget"
}

/** A widget start or open tap, waiting for the workout log to consume it (see WorkoutDiaryScreen). */
object WorkoutWidgetLaunches {
    private val _pending = MutableStateFlow<WorkoutWidgetLaunch?>(null)
    val pending: StateFlow<WorkoutWidgetLaunch?> = _pending.asStateFlow()

    fun post(launch: WorkoutWidgetLaunch) {
        _pending.value = launch
    }

    fun consume(id: Long) {
        if (_pending.value?.id == id) _pending.value = null
    }
}

/** Intents the Workout widget fires. */
object WorkoutWidgetIntents {
    /** Starting a `location` foreground service needs a visible app, so every start goes through MainActivity. */
    fun startIntent(context: Context, start: WorkoutWidgetStart): Intent =
        activity(context, WorkoutWidgetIntentContract.ACTION_START).putExtra(WorkoutWidgetIntentContract.EXTRA_START, start.id)

    fun openIntent(context: Context): Intent = activity(context, WorkoutWidgetIntentContract.ACTION_OPEN)

    /** GPS controls go straight to the running foreground service (like the notification actions). */
    fun serviceIntent(context: Context, control: WorkoutWidgetControl): Intent? {
        val action = when (control) {
            WorkoutWidgetControl.PAUSE -> OutdoorWorkoutService.ACTION_PAUSE
            WorkoutWidgetControl.RESUME -> OutdoorWorkoutService.ACTION_RESUME
            WorkoutWidgetControl.LAP -> OutdoorWorkoutService.ACTION_LAP
            WorkoutWidgetControl.END -> OutdoorWorkoutService.ACTION_END
            WorkoutWidgetControl.SKIP_RECOVERY -> OutdoorWorkoutService.ACTION_SKIP_RECOVERY
            WorkoutWidgetControl.FINISH_STRENGTH -> return null
        }
        return Intent(context, OutdoorWorkoutService::class.java).setAction(action)
    }

    fun finishStrengthIntent(context: Context): Intent =
        Intent(context, WorkoutActionReceiver::class.java).setAction(WorkoutActionReceiver.ACTION_FINISH_STRENGTH)

    private fun activity(context: Context, action: String): Intent =
        Intent(context, MainActivity::class.java).apply {
            this.action = action
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
}
