package com.ayuvo.health.ui.navigation

import com.ayuvo.health.data.metrics.AppMetricId
import com.ayuvo.health.data.metrics.MetricKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppRoutesTest {
    @Test
    fun bottomTabsAreSummaryBrowseRecordsCoachSettings() {
        assertEquals(
            listOf(AppRoutes.SUMMARY, AppRoutes.BROWSE, AppRoutes.RECORDS, AppRoutes.COACH, AppRoutes.SETTINGS),
            AppRoutes.bottomTabs
        )
        assertEquals(listOf("summary", "browse", "records", "coach", "settings"), AppRoutes.bottomTabs)
    }

    @Test
    fun selectedBottomTabMapsNestedSettingsToSettings() {
        assertEquals(AppRoutes.SETTINGS, AppRoutes.selectedBottomTab(AppRoutes.SETTINGS))
        assertEquals(AppRoutes.SETTINGS, AppRoutes.selectedBottomTab(AppRoutes.QUICK_ACTIONS))
        assertEquals(AppRoutes.SETTINGS, AppRoutes.selectedBottomTab(AppRoutes.OPTIONAL_NUTRIENT_GOALS))
        assertEquals(AppRoutes.SETTINGS, AppRoutes.selectedBottomTab(AppRoutes.LICENSES))
        assertEquals(AppRoutes.SUMMARY, AppRoutes.selectedBottomTab(AppRoutes.SUMMARY))
        assertNull(AppRoutes.selectedBottomTab(AppRoutes.ONBOARDING))
        assertNull(AppRoutes.selectedBottomTab(null))
    }

    @Test
    fun browseTreeKeepsBrowseSelected() {
        val browseRoutes = listOf(
            AppRoutes.BROWSE, AppRoutes.BROWSE_NUTRITION, AppRoutes.BROWSE_NUTRIENTS, AppRoutes.BROWSE_FASTING,
            AppRoutes.BROWSE_BODY, AppRoutes.BROWSE_MEASUREMENTS, AppRoutes.BROWSE_ACTIVITY, AppRoutes.BROWSE_CATEGORY,
            AppRoutes.browseCategory("heart")
        )
        for (route in browseRoutes) assertEquals(route, AppRoutes.BROWSE, AppRoutes.selectedBottomTab(route))
        assertEquals("browse/category/sleep", AppRoutes.browseCategory("sleep"))
        // "browser" style routes are not part of the tree.
        assertNull(AppRoutes.selectedBottomTab("browsex"))
    }

    @Test
    fun legacyRoutesAreGone() {
        assertNull(AppRoutes.selectedBottomTab("home"))
        assertNull(AppRoutes.selectedBottomTab("health"))
        assertNull(AppRoutes.selectedBottomTab("workouts"))
        assertFalse("home" in AppRoutes.bottomTabs)
        assertFalse("health" in AppRoutes.bottomTabs)
    }

    @Test
    fun sharedRoutesKeepTheTabThatOpenedThem() {
        val metric = AppRoutes.metric(MetricKey.App(AppMetricId.CALORIES))
        // Opened from Summary: Summary stays selected.
        assertEquals(AppRoutes.SUMMARY, AppRoutes.selectedBottomTab(metric, listOf(AppRoutes.SUMMARY)))
        // Opened from a Browse page (possibly through another shared screen).
        assertEquals(AppRoutes.BROWSE, AppRoutes.selectedBottomTab(AppRoutes.METRIC, listOf(AppRoutes.BROWSE_NUTRITION, AppRoutes.BROWSE, AppRoutes.SUMMARY)))
        assertEquals(AppRoutes.SUMMARY, AppRoutes.selectedBottomTab(AppRoutes.medicationDetail("x"), listOf(AppRoutes.MEDICATIONS, AppRoutes.SUMMARY)))
        assertEquals(AppRoutes.SETTINGS, AppRoutes.selectedBottomTab(AppRoutes.WORKOUTS_LOG, listOf(AppRoutes.SETTINGS, AppRoutes.SUMMARY)))
        assertEquals(AppRoutes.RECORDS, AppRoutes.selectedBottomTab(AppRoutes.MEDICATION_IMPORT, listOf(AppRoutes.RECORD_DETAIL_FOCUS, AppRoutes.RECORDS)))
        // Nothing below: fall back to Browse.
        assertEquals(AppRoutes.BROWSE, AppRoutes.selectedBottomTab(AppRoutes.MEDICATIONS))
        assertEquals(AppRoutes.BROWSE, AppRoutes.selectedBottomTab(AppRoutes.HEALTH_TYPE, listOf(null)))
        assertEquals(AppRoutes.BROWSE, AppRoutes.selectedBottomTab(AppRoutes.WORKOUTS_LIBRARY, listOf(AppRoutes.ONBOARDING)))
    }

    @Test
    fun metricRoutesEncodeTheKey() {
        assertEquals("metric/app%3Acalories", AppRoutes.metric(MetricKey.App(AppMetricId.CALORIES)))
        assertEquals("metric/heart_rate", AppRoutes.metric(MetricKey.Health("heart_rate")))
        assertEquals("metric/a.b%20c", AppRoutes.metric(MetricKey.Health("a.b c")))
        assertEquals("health/type/heart_rate", AppRoutes.healthType("heart_rate"))
        assertTrue(AppRoutes.isHealthDetailRoute("metric/app%3Aweight"))
        assertTrue(AppRoutes.isHealthDetailRoute("health/type/steps"))
        assertFalse(AppRoutes.isHealthDetailRoute(AppRoutes.BROWSE))
        assertTrue(AppRoutes.isSharedRoute(AppRoutes.METRIC))
        assertNull(AppRoutes.selectedBottomTab("metricx"))
        assertNull(AppRoutes.selectedBottomTab("healthy"))
    }

    @Test
    fun workoutAndMedicationRoutesAreShared() {
        assertEquals("workouts/log", AppRoutes.WORKOUTS_LOG)
        assertEquals("workouts/library", AppRoutes.WORKOUTS_LIBRARY)
        assertTrue(AppRoutes.isSharedRoute(AppRoutes.WORKOUTS_LOG))
        assertTrue(AppRoutes.isSharedRoute(AppRoutes.MEDICATIONS))
        assertNull(AppRoutes.selectedBottomTab("workoutsx"))
        assertNull(AppRoutes.selectedBottomTab("medicationsx"))
    }

    @Test
    fun medicationRoutes() {
        assertEquals("medications/detail/x", AppRoutes.medicationDetail("x"))
        assertEquals("medications/edit/x", AppRoutes.medicationEdit("x"))
        assertEquals("medications/history", AppRoutes.medicationHistory())
        assertEquals("medications/history?medicationId=x", AppRoutes.medicationHistory("x"))
        assertEquals("medications/import/rec-1", AppRoutes.medicationImport("rec-1"))
        assertEquals("medications/add?recordId=rec-1", AppRoutes.medicationAdd("rec-1"))
        assertEquals("medications/add", AppRoutes.medicationAdd())
        assertTrue(AppRoutes.isMedicationsChildRoute(AppRoutes.medicationDetail("x")))
        assertTrue(AppRoutes.isMedicationsChildRoute(AppRoutes.MEDICATIONS))
        assertFalse(AppRoutes.isMedicationsChildRoute(AppRoutes.BROWSE))
        assertFalse(AppRoutes.isHealthDetailRoute(AppRoutes.medicationDetail("x")))
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
