package com.ayuvo.health.ui.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppRoutesTest {
    @Test
    fun bottomTabsAreHomeHealthCoachWorkoutsSettings() {
        assertEquals(
            listOf(AppRoutes.HOME, AppRoutes.HEALTH, AppRoutes.COACH, AppRoutes.WORKOUTS, AppRoutes.SETTINGS),
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
}
