package com.ayuvo.health.medications.reminders

/**
 * Pure "may medication reminders be scheduled at all" rule (docs/medications.md §10), kept
 * separate so it can be covered by local JVM tests like `ReminderDispatchPolicy`.
 */
internal object ReminderGate {
    fun shouldSchedule(
        notificationsEnabled: Boolean,
        medicationRemindersEnabled: Boolean,
        canPost: Boolean
    ): Boolean = notificationsEnabled && medicationRemindersEnabled && canPost
}
