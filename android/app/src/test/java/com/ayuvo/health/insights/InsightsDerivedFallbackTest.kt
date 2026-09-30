package com.ayuvo.health.insights

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import java.time.LocalDate

/** Derived resting heart rate / VO2 max only fill days without a native value (docs/derived-metrics.md §1). */
class InsightsDerivedFallbackTest {
    private val d1 = LocalDate.of(2026, 9, 1)
    private val d2 = d1.plusDays(1)
    private val d3 = d1.plusDays(2)

    @Test
    fun nativeWinsAndGapsAreFilled() {
        val merged = InsightsDataSource.withDerivedFallback(mapOf(d1 to 60.0, d3 to 62.0), mapOf(d1 to 50.0, d2 to 51.0))
        assertEquals(listOf(d1 to 60.0, d2 to 51.0, d3 to 62.0), merged.toList())
    }

    @Test
    fun noEstimatesLeavesTheSeriesAlone() {
        val native = mapOf(d1 to 60.0)
        assertSame(native, InsightsDataSource.withDerivedFallback(native, emptyMap()))
        assertEquals(mapOf(d2 to 51.0), InsightsDataSource.withDerivedFallback(emptyMap(), mapOf(d2 to 51.0)))
    }

    @Test
    fun fallbackPairsAreTheDocumentedOnes() {
        assertEquals(listOf("resting_heart_rate" to "resting_hr_derived", "vo2_max" to "vo2max_estimate"), InsightsDataSource.DERIVED_FALLBACK)
    }
}
