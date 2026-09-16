package com.ayuvo.health.ui.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppRoutesTest {
    @Test
    fun bottomTabsAreHomeHealthRecordsCoachSettings() {
        assertEquals(
            listOf(AppRoutes.HOME, AppRoutes.HEALTH, AppRoutes.RECORDS, AppRoutes.COACH, AppRoutes.SETTINGS),
            AppRoutes.bottomTabs
        )
    }

    @Test
    fun selectedBottomTabMapsNestedSettingsToSettings() {
        assertEquals(AppRoutes.SETTINGS, AppRoutes.selectedBottomTab(AppRoutes.SETTINGS))
        assertEquals(AppRoutes.SETTINGS, AppRoutes.selectedBottomTab(AppRoutes.QUICK_ACTIONS))
        assertEquals(AppRoutes.SETTINGS, AppRoutes.selectedBottomTab(AppRoutes.OPTIONAL_NUTRIENT_GOALS))
        assertEquals(AppRoutes.SETTINGS, AppRoutes.selectedBottomTab(AppRoutes.LICENSES))
        assertEquals(AppRoutes.HOME, AppRoutes.selectedBottomTab(AppRoutes.HOME))
        assertNull(AppRoutes.selectedBottomTab(AppRoutes.ONBOARDING))
        assertNull(AppRoutes.selectedBottomTab(null))
    }

    @Test
    fun healthTabAndTypeDetailsKeepHealthSelected() {
        assertEquals(AppRoutes.HEALTH, AppRoutes.selectedBottomTab(AppRoutes.HEALTH))
        assertEquals(AppRoutes.HEALTH, AppRoutes.selectedBottomTab(AppRoutes.HEALTH_TYPE))
        assertEquals(AppRoutes.HEALTH, AppRoutes.selectedBottomTab(AppRoutes.healthType("heart_rate")))
        assertEquals("health/type/heart_rate", AppRoutes.healthType("heart_rate"))
        assertFalse(AppRoutes.isHealthDetailRoute(AppRoutes.HEALTH))
        assertTrue(AppRoutes.isHealthDetailRoute("health/type/steps"))
        assertFalse(AppRoutes.isHealthDetailRoute(AppRoutes.HOME))
        // "healthy-food" style routes must not be mistaken for the Health tab.
        assertNull(AppRoutes.selectedBottomTab("healthy"))
    }

    @Test
    fun workoutRoutesBelongToTheHealthTab() {
        assertEquals(AppRoutes.HEALTH, AppRoutes.selectedBottomTab("workouts"))
        assertEquals(AppRoutes.HEALTH, AppRoutes.selectedBottomTab("workouts/session/abc"))
        assertNull(AppRoutes.selectedBottomTab("workoutsx"))
    }

    @Test
    fun medicationRoutesBelongToTheHealthTab() {
        assertEquals(AppRoutes.HEALTH, AppRoutes.selectedBottomTab(AppRoutes.medicationAdd()))
        assertEquals(AppRoutes.HEALTH, AppRoutes.selectedBottomTab(AppRoutes.medicationAdd("rec-1")))
        assertEquals(AppRoutes.HEALTH, AppRoutes.selectedBottomTab(AppRoutes.medicationDetail("x")))
        assertEquals(AppRoutes.HEALTH, AppRoutes.selectedBottomTab(AppRoutes.MEDICATION_HISTORY))
        assertEquals("medications/detail/x", AppRoutes.medicationDetail("x"))
        assertEquals("medications/edit/x", AppRoutes.medicationEdit("x"))
        assertEquals("medications/history", AppRoutes.medicationHistory())
        assertEquals("medications/history?medicationId=x", AppRoutes.medicationHistory("x"))
        assertEquals("medications/import/rec-1", AppRoutes.medicationImport("rec-1"))
        assertEquals("medications/add?recordId=rec-1", AppRoutes.medicationAdd("rec-1"))
        assertTrue(AppRoutes.isMedicationsChildRoute(AppRoutes.medicationDetail("x")))
        assertFalse(AppRoutes.isMedicationsChildRoute(AppRoutes.HEALTH))
        assertFalse(AppRoutes.isHealthDetailRoute(AppRoutes.medicationDetail("x")))
        assertNull(AppRoutes.selectedBottomTab("medicationsx"))
    }

    @Test
    fun recordRoutesKeepRecordsSelected() {
        assertEquals(AppRoutes.RECORDS, AppRoutes.selectedBottomTab(AppRoutes.RECORDS))
        assertEquals(AppRoutes.RECORDS, AppRoutes.selectedBottomTab(AppRoutes.RECORD_DETAIL))
        assertEquals("records/detail/abc-123", AppRoutes.recordDetail("abc-123"))
        assertEquals("records/split/abc-123", AppRoutes.recordSplit("abc-123"))
        assertEquals(AppRoutes.RECORDS, AppRoutes.selectedBottomTab(AppRoutes.RECORD_SPLIT))
        assertEquals(AppRoutes.RECORDS, AppRoutes.selectedBottomTab(AppRoutes.recordDetail("abc-123")))
        assertTrue(AppRoutes.isRecordsChildRoute(AppRoutes.recordDetail("x")))
        assertFalse(AppRoutes.isRecordsChildRoute(AppRoutes.RECORDS))
        assertNull(AppRoutes.selectedBottomTab("recordsx"))
    }
}
