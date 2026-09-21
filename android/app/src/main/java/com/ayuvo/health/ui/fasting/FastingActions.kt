package com.ayuvo.health.ui.fasting

import com.ayuvo.health.AppContainer
import com.ayuvo.health.models.FastingSession
import kotlinx.coroutines.flow.first
import java.util.UUID

/**
 * Start / end / cancel / update / delete a fast and keep the goal notification in step.
 * Shared by the Nutrition diary ([com.ayuvo.health.ui.home.HomeViewModel]), the Fasting screen
 * and the Summary log sheet so every entry point behaves the same.
 */
class FastingActions(private val container: AppContainer) {

    suspend fun start(goalMinutes: Int) {
        container.fastingRepository.start(goalMinutes)
        syncNotification()
    }

    /** False when the ended session would overlap another fast (nothing is written). */
    suspend fun end(updatedSession: FastingSession? = null): Boolean {
        val ended = container.fastingRepository.endActive(updatedSession = updatedSession)
        if (ended != null) container.notifications.cancelFastingGoal()
        return ended != null
    }

    suspend fun cancel() {
        container.fastingRepository.cancelActive()
        container.notifications.cancelFastingGoal()
    }

    /** False when the edit would overlap another fast (nothing is written). */
    suspend fun update(session: FastingSession): Boolean {
        val ok = container.fastingRepository.update(session)
        if (ok) syncNotification()
        return ok
    }

    suspend fun delete(id: UUID) {
        container.fastingRepository.delete(id)
        syncNotification()
    }

    suspend fun syncNotification() {
        val shouldNotify = container.prefs.notificationsEnabled.first() &&
            container.prefs.fastingTrackingEnabled.first() &&
            container.prefs.fastingGoalNotificationEnabled.first() &&
            container.notifications.canPostNotifications()
        if (shouldNotify) {
            container.notifications.scheduleFastingGoal(container.fastingRepository.active())
        } else {
            container.notifications.cancelFastingGoal()
        }
    }
}
