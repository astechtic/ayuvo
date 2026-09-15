package com.ayuvo.health.ui.settings

import com.ayuvo.health.ui.about.AboutSettingsCategory
import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsCategoryTest {
    @Test
    fun hubKeepsEveryFocusedCategory() {
        assertEquals(14, SettingsCategory.entries.size)
        assertEquals(14, SettingsCategory.entries.map { it.titleRes }.toSet().size)
        assertEquals(11, SettingsCategory.preferenceEntries.size)
        assertEquals(3, SettingsCategory.appInfoEntries.size)
        assertEquals(3, SettingsCategory.entries.mapNotNull { it.aboutCategory }.size)
    }

    @Test
    fun appInfoKeepsEveryFocusedCategory() {
        assertEquals(3, AboutSettingsCategory.entries.size)
        assertEquals(3, AboutSettingsCategory.entries.map { it.titleRes }.toSet().size)
    }
}
