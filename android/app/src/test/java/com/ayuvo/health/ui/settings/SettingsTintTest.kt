package com.ayuvo.health.ui.settings

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import org.junit.Assert.assertEquals
import org.junit.Test

/** Root row icon colours: fixed, meaningful and shared with iOS (never the theme accent). */
class SettingsTintTest {
    private fun hex(color: Color) = "#%06X".format(color.toArgb() and 0xFFFFFF)

    @Test
    fun rootRowsUseTheSharedMapping() {
        val expected = mapOf(
            SettingsPage.PERSONAL_INFO to "#8E8E93",
            SettingsPage.GOALS_TARGETS to "#34C759",
            SettingsPage.UNITS to "#FF9500",
            SettingsPage.NUTRITION to "#34C759",
            SettingsPage.HYDRATION to "#007AFF",
            SettingsPage.FASTING to "#00C7BE",
            SettingsPage.ACTIVITY to "#FF9500",
            SettingsPage.MEDICATIONS to "#32ADE6",
            SettingsPage.NOTIFICATIONS to "#FF3B30",
            SettingsPage.HEALTH_SYNC to "#FF2D55",
            SettingsPage.HEALTH_RECORDS to "#5856D6",
            SettingsPage.BACKUP_EXPORT to "#007AFF",
            SettingsPage.DELETE_DATA to "#FF3B30",
            SettingsPage.AI_PROVIDERS to "#AF52DE",
            SettingsPage.SPEECH_TO_TEXT to "#FF9500",
            SettingsPage.CUSTOM_INSTRUCTIONS to "#5E5CE6",
            SettingsPage.APPEARANCE to "#007AFF",
            SettingsPage.APP_UPDATES to "#8E8E93",
            SettingsPage.HELP_SUPPORT to "#34C759",
            SettingsPage.LEGAL to "#8E8E93"
        )
        assertEquals(SettingsPage.entries.toSet(), expected.keys)
        SettingsPage.entries.forEach { assertEquals(it.name, expected.getValue(it), hex(it.tint)) }
    }
}
