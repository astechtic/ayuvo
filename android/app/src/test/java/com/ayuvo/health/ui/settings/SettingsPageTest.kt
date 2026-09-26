package com.ayuvo.health.ui.settings

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.SaverScope
import com.ayuvo.health.ui.about.AboutSettingsCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsPageTest {
    private val saverScope = SaverScope { true }

    @Test
    fun groupsFollowPlanOrder() {
        assertEquals(
            listOf(
                SettingsGroup.HEALTH_PROFILE, SettingsGroup.TRACKING, SettingsGroup.NOTIFICATIONS,
                SettingsGroup.DATA_PRIVACY, SettingsGroup.AI_SPEECH, SettingsGroup.APPEARANCE, SettingsGroup.ABOUT
            ),
            SettingsGroup.entries
        )
        assertEquals(SettingsGroup.entries.size, SettingsGroup.entries.map { it.tag }.toSet().size)
    }

    @Test
    fun everyGroupHasItsPages() {
        assertEquals(
            listOf(SettingsPage.PERSONAL_INFO, SettingsPage.GOALS_TARGETS, SettingsPage.UNITS),
            SettingsGroup.HEALTH_PROFILE.pages
        )
        assertEquals(
            listOf(SettingsPage.NUTRITION, SettingsPage.HYDRATION, SettingsPage.FASTING, SettingsPage.ACTIVITY, SettingsPage.MEDICATIONS, SettingsPage.INSIGHTS),
            SettingsGroup.TRACKING.pages
        )
        assertEquals(listOf(SettingsPage.NOTIFICATIONS), SettingsGroup.NOTIFICATIONS.pages)
        assertEquals(
            listOf(SettingsPage.HEALTH_SYNC, SettingsPage.HEALTH_RECORDS, SettingsPage.BACKUP_EXPORT, SettingsPage.DELETE_DATA),
            SettingsGroup.DATA_PRIVACY.pages
        )
        assertEquals(
            listOf(SettingsPage.AI_PROVIDERS, SettingsPage.SPEECH_TO_TEXT, SettingsPage.CUSTOM_INSTRUCTIONS),
            SettingsGroup.AI_SPEECH.pages
        )
        assertEquals(listOf(SettingsPage.APPEARANCE), SettingsGroup.APPEARANCE.pages)
        assertEquals(listOf(SettingsPage.APP_UPDATES, SettingsPage.HELP_SUPPORT, SettingsPage.LEGAL), SettingsGroup.ABOUT.pages)
        assertEquals(SettingsPage.entries.size, SettingsGroup.entries.sumOf { it.pages.size })
    }

    @Test
    fun rawValuesMatchIosPanesAndAreUnique() {
        assertEquals(
            listOf(
                "personalInfo", "goalsNutrition", "units", "nutritionTracking", "hydration", "fasting", "activity",
                "medications", "insights", "notifications", "healthData", "healthRecords", "dataManagement", "deleteData",
                "aiProviders", "speechToText", "customInstructions", "appearance", "appUpdates", "helpSupport", "legal"
            ),
            SettingsPage.entries.map { it.rawValue }
        )
        assertEquals(SettingsPage.entries.size, SettingsPage.entries.map { it.titleRes }.toSet().size)
        assertEquals("settings.category.healthData", SettingsPage.HEALTH_SYNC.testTag)
    }

    @Test
    fun aboutPagesKeepTheirCategories() {
        assertEquals(
            AboutSettingsCategory.entries,
            SettingsPage.entries.mapNotNull { it.aboutCategory }
        )
        assertTrue(SettingsPage.entries.filter { it.aboutCategory != null }.all { it.group == SettingsGroup.ABOUT })
        assertEquals(listOf(SettingsPage.DELETE_DATA), SettingsPage.entries.filter { it.destructive })
    }

    @Test
    fun fromNameRestoresKnownPagesOnly() {
        SettingsPage.entries.forEach { assertEquals(it, SettingsPage.fromName(it.name)) }
        assertNull(SettingsPage.fromName(null))
        assertNull(SettingsPage.fromName(""))
        assertNull(SettingsPage.fromName("DATA_MANAGEMENT"))
        assertNull(SettingsPage.fromName("healthData"))
    }

    @Test
    fun selectedPageSaverRoundTripsByName() {
        SettingsPage.entries.forEach { page ->
            val saved = with(SelectedSettingsPageSaver) { saverScope.save(mutableStateOf(page)) }
            assertEquals(page.name, saved)
            assertEquals(page, SelectedSettingsPageSaver.restore(saved!!)?.value)
        }
        val root = with(SelectedSettingsPageSaver) { saverScope.save(mutableStateOf<SettingsPage?>(null)) }
        assertEquals("", root)
        assertNull(SelectedSettingsPageSaver.restore(root!!)?.value)
        assertNull(SelectedSettingsPageSaver.restore("REMOVED_PAGE")?.value)
    }
}
