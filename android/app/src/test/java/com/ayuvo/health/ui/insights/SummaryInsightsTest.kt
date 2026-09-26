package com.ayuvo.health.ui.insights

import com.ayuvo.health.insights.HealthAnalyticsEngine
import com.ayuvo.health.insights.InsightsFixtures
import com.ayuvo.health.insights.InsightsTestFiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Summary Insights section rules (docs/insights.md §7). */
class SummaryInsightsTest {
    private val cfg = InsightsTestFiles.config

    @Test
    fun hiddenWhenOffOrWithoutHealthSync() {
        val snap = HealthAnalyticsEngine.snapshot(InsightsFixtures.bundle(InsightsFixtures.inputs()), cfg)
        assertNull(SummaryInsights.from(snap, enabled = false))
        val noHealth = HealthAnalyticsEngine.snapshot(InsightsFixtures.bundle(InsightsFixtures.inputs(), healthEnabled = false), cfg)
        assertNull(SummaryInsights.from(noHealth, enabled = true))
        assertNull(SummaryInsights.from(null, enabled = true))
    }

    @Test
    fun oneLearningCardWhileBaselinesAreCollected() {
        val snap = HealthAnalyticsEngine.snapshot(InsightsFixtures.bundle(InsightsFixtures.inputs(days = 8)), cfg)
        assertEquals(SummaryInsights.Learning(7, 14), SummaryInsights.from(snap, enabled = true))
    }

    @Test
    fun cardsCarryTheTopTwoSignals() {
        val snap = HealthAnalyticsEngine.snapshot(InsightsFixtures.bundle(InsightsFixtures.inputs(days = 120)), cfg)
        val cards = SummaryInsights.from(snap, enabled = true) as SummaryInsights.Cards
        assertTrue(cards.topSignals.size <= 2)
        val all = (snap.recovery.positives + snap.recovery.negatives).sortedByDescending { kotlin.math.abs(it.impact) }
        assertEquals(all.take(2).map { it.text }, cards.topSignals)
        assertEquals(snap.reviews.last().dayScore, cards.dayScore)
    }

    @Test
    fun missingValuesFormatAsADash() {
        assertEquals(InsightsFormat.MISSING, InsightsFormat.number(null))
        assertEquals(InsightsFormat.MISSING, InsightsFormat.metricValue("hrv", "ms", null))
        assertEquals(InsightsFormat.MISSING, InsightsFormat.markerValue("vo2_max", null, null, null))
        assertEquals("7h 14m", InsightsFormat.metricValue("sleep", "min", 434.0))
    }
}
