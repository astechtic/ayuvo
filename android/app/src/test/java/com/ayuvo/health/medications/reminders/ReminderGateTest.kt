package com.ayuvo.health.medications.reminders

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReminderGateTest {
    @Test
    fun `schedules only when the master toggle, the medication toggle and the permission agree`() {
        assertTrue(ReminderGate.shouldSchedule(notificationsEnabled = true, medicationRemindersEnabled = true, canPost = true))
    }

    @Test
    fun `master toggle off cancels everything`() {
        assertFalse(ReminderGate.shouldSchedule(notificationsEnabled = false, medicationRemindersEnabled = true, canPost = true))
    }

    @Test
    fun `medication toggle off cancels even with permission`() {
        assertFalse(ReminderGate.shouldSchedule(notificationsEnabled = true, medicationRemindersEnabled = false, canPost = true))
    }

    @Test
    fun `denied POST_NOTIFICATIONS never arms an alarm`() {
        assertFalse(ReminderGate.shouldSchedule(notificationsEnabled = true, medicationRemindersEnabled = true, canPost = false))
    }
}
