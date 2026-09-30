package com.ayuvo.health.services.workout

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.ayuvo.health.AppContainer
import com.ayuvo.health.AyuvoApp
import com.ayuvo.health.R
import com.ayuvo.health.models.WorkoutWeightUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.Instant

/** Finish from the strength-session notification (docs/workouts-gps.md §1). */
class WorkoutActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_FINISH_STRENGTH) return
        val app = context.applicationContext as? AyuvoApp ?: return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                finishStrength(app, app.container)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_FINISH_STRENGTH = "com.ayuvo.health.workout.FINISH_STRENGTH"

        /**
         * Finishes the running strength session with heart-rate statistics for its interval. When nothing is logged
         * yet the session keeps running and the notification says so. Returns the saved session, if any.
         */
        suspend fun finishStrength(context: Context, container: AppContainer): com.ayuvo.health.models.WorkoutSession? {
            val repo = container.workoutRepository
            val active = repo.activeStrengthSessionNow() ?: run {
                WorkoutNotifications.cancelStrength(context)
                return null
            }
            val weight = container.weightRepository.latest.first()?.weightKg
                ?: container.profileRepository.current()?.weightKg ?: 70.0
            val unit = WorkoutWeightUnit.fromStorage(container.prefs.weightUnit.first())
            val saved = repo.finishStrengthSession(weight, unit, Instant.now()) { start, end ->
                container.workoutHeartRate.stats(start.toEpochMilli(), end.toEpochMilli())
            }
            if (saved != null) {
                WorkoutNotifications.cancelStrength(context)
            } else {
                WorkoutNotifications.showStrength(context, active, context.getString(R.string.workout_strength_needs_sets))
            }
            return saved
        }
    }
}
