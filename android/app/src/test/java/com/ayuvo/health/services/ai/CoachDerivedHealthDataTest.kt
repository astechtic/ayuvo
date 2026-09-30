package com.ayuvo.health.services.ai

import com.ayuvo.health.data.health.DerivedPoint
import com.ayuvo.health.data.health.HealthCoachDerivedMetric
import com.ayuvo.health.data.health.HealthCoachSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** Derived metrics in Coach's health tools (docs/derived-metrics.md): data only, no new tools. */
class CoachDerivedHealthDataTest {
    private val clock = Clock.fixed(Instant.parse("2026-09-14T12:00:00Z"), ZoneOffset.UTC)
    private val today = LocalDate.of(2026, 9, 14)

    private val rhr = HealthCoachDerivedMetric(
        id = "resting_hr_derived",
        title = "Resting Heart Rate (estimated)",
        category = "heart",
        unit = "bpm",
        aggregation = "average",
        method = "Lowest average of 5 consecutive minutes inside last night's main sleep.",
        nativeTypeId = "resting_heart_rate",
        days = listOf(
            DerivedPoint(today.minusDays(2), 58.0, null, null, "derived", 1.0),
            DerivedPoint(today.minusDays(1), 55.0, null, null, "native"),
            DerivedPoint(today, 57.456, null, null, "derived", 1.0)
        )
    )

    private fun data(derived: List<HealthCoachDerivedMetric>) = CoachHealthData(
        HealthCoachSnapshot(null, emptyList(), emptyMap(), emptyMap(), emptyList(), "UTC", derived = derived), clock
    )

    @Test
    fun dataTypesListDerivedMetrics() {
        val out = data(listOf(rhr)).dataTypes()
        assertEquals(1, out["count"])
        @Suppress("UNCHECKED_CAST")
        val entry = (out["data_types"] as List<Map<String, Any?>>).single()
        assertEquals("derived:resting_hr_derived", entry["data_type"])
        assertEquals(true, entry["derived"])
        assertEquals(rhr.method, entry["method"])
        assertEquals("AVERAGE", entry["aggregation"])
        assertEquals("resting_heart_rate", entry["native_data_type"])
        assertEquals(3, entry["count"])
        assertEquals(today.toString(), entry["last"])
    }

    @Test
    fun summaryWorksForDerivedMetrics() {
        val out = data(listOf(rhr)).summary("derived:resting_hr_derived", "2026-09-13", "2026-09-14", null)
        assertEquals("derived:resting_hr_derived", out["data_type"])
        @Suppress("UNCHECKED_CAST")
        val days = out["days"] as List<Map<String, Any?>>
        assertEquals(listOf("native", "derived"), days.map { it["source"] })
        assertEquals(57.46, days.last()["value"])
        @Suppress("UNCHECKED_CAST")
        val highlights = out["highlights"] as Map<String, Any?>
        assertEquals(57.46, highlights["latest"])
        assertEquals(null, highlights["total"])
        assertTrue(data(emptyList()).summary("derived:resting_hr_derived", null, null, null).containsKey("error"))
        assertTrue(data(listOf(rhr)).samples("derived:resting_hr_derived", null, null, null).containsKey("error"))
    }

    @Test
    fun withoutDerivedMetricsThePayloadIsUnchanged() {
        val out = data(emptyList()).dataTypes()
        assertEquals(0, out["count"])
        assertEquals(emptyList<Any>(), out["data_types"])
        assertTrue(HealthCoachSnapshot(null, emptyList(), emptyMap(), emptyMap(), emptyList(), "UTC").isEmpty)
    }
}
