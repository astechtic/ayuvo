package com.ayuvo.health.widget

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import com.ayuvo.health.R

/**
 * Quick Settings "Workout" tile (docs/widgets.md "Lock Screen starters"), reachable from the lock
 * screen. Idle, a tap unlocks and opens the workout log with the start choices; while a GPS workout
 * or strength session runs the tile is active, shows the phase and a tap opens the running workout.
 * Both go through `WIDGET_OPEN`, because a `location` foreground service may only start while the
 * app is visible. State comes from [WorkoutWidgetSync], which asks for a refresh on phase changes.
 */
open class WorkoutTileService : TileService() {
    /** Tile title. */
    protected open val labelRes: Int = R.string.qs_tile_workout_label

    /** What a tap opens: the running workout, or the workout log with the start choices. */
    protected open fun tapIntent(active: Boolean): Intent = WorkoutWidgetIntents.openIntent(this)

    override fun onStartListening() {
        super.onStartListening()
        refresh()
    }

    override fun onClick() {
        super.onClick()
        if (isLocked) unlockAndRun { open() } else open()
    }

    private fun currentModel(): WorkoutWidgetModel {
        val inputs = WorkoutWidgetSync.inputs.value
        return WorkoutWidgetMapper.map(inputs.live, inputs.strength, System.currentTimeMillis())
    }

    private fun open() {
        val intent = tapIntent(active = currentModel() is WorkoutWidgetModel.Active)
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val pending = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                startActivityAndCollapse(pending)
            } else {
                startActivityAndCollapseLegacy(intent)
            }
        }.onFailure { Log.e(TAG, "Workout tile could not open Ayuvo", it) }
    }

    /** Android 13 and older only take an Intent here; the PendingIntent form is API 34. */
    @SuppressLint("StartActivityAndCollapseDeprecated")
    @Suppress("DEPRECATION")
    private fun startActivityAndCollapseLegacy(intent: Intent) {
        startActivityAndCollapse(intent)
    }

    private fun refresh() {
        val tile = qsTile ?: return
        val model = currentModel()
        val label = getString(labelRes)
        val subtitle = when (model) {
            WorkoutWidgetModel.Idle -> getString(R.string.qs_tile_workout_idle)
            is WorkoutWidgetModel.Active -> getString(
                when (model.status) {
                    WorkoutWidgetStatus.RECORDING -> R.string.widget_workout_status_recording
                    WorkoutWidgetStatus.PAUSED -> R.string.widget_workout_status_paused
                    WorkoutWidgetStatus.SAVING -> R.string.widget_workout_status_saving
                    WorkoutWidgetStatus.RECOVERY -> R.string.widget_workout_status_recovery
                    WorkoutWidgetStatus.STRENGTH -> R.string.widget_workout_status_strength
                }
            )
        }
        tile.label = label
        tile.state = if (model is WorkoutWidgetModel.Active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) tile.subtitle = subtitle
        tile.contentDescription = "$label, $subtitle"
        tile.updateTile()
    }

    companion object {
        private const val TAG = "AyuvoWidget"

        /** Asks the system to bind the tiles so they re-read the workout state (no-op when a tile is not added). */
        fun requestRefresh(context: Context) {
            for (service in listOf(WorkoutTileService::class.java, WalkTileService::class.java)) {
                runCatching { requestListeningState(context, ComponentName(context, service)) }
                    .onFailure { Log.w(TAG, "Workout tile refresh request failed", it) }
            }
        }
    }
}

/**
 * Quick Settings "Walk" tile (docs/widgets.md "Lock Screen starters"): idle, a tap unlocks, opens Ayuvo
 * and starts a walk (the Workout widget's start path); while a workout runs it opens that workout.
 */
class WalkTileService : WorkoutTileService() {
    override val labelRes: Int = R.string.widget_workout_walk

    override fun tapIntent(active: Boolean): Intent =
        if (active) WorkoutWidgetIntents.openIntent(this) else WorkoutWidgetIntents.startIntent(this, WorkoutWidgetStart.WALK)
}
