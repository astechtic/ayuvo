package com.ayuvo.health.data.health

import com.ayuvo.health.data.metrics.MetricRange
import com.ayuvo.health.data.metrics.WeekStart
import com.ayuvo.health.models.HealthDataType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

class HealthSeriesAggregatorTest {
    private val zone = ZoneOffset.UTC
    private val day = LocalDate.of(2026, 9, 10)
    private val dayStart = day.atStartOfDay(zone).toInstant().toEpochMilli()
    private val h = 3_600_000L

    private fun row(type: HealthDataType, id: String, startMs: Long, value: Double, endMs: Long = startMs, count: Int = 1, v2: Double? = null, v3: Double? = null) =
        HealthSampleRow(
            id = id, typeId = type.id, startMs = startMs, endMs = endMs, localDay = HealthDayKeys.dayOf(startMs, 0, zone).toString(),
            value = value, value2 = v2, value3 = v3, unit = type.unit, count = count, sourceId = "s", updatedMs = startMs
        )

    @Test
    fun hourBucketsCoverTheDayAndSumCumulativeRows() {
        val bounds = HealthSeriesAggregator.bucketBounds(MetricRange.D, day, zone)
        assertEquals(24, bounds.size)
        val rows = listOf(row(HealthDataType.STEPS, "a", dayStart + 10 * 60_000, 100.0), row(HealthDataType.STEPS, "b", dayStart + 50 * 60_000, 50.0), row(HealthDataType.STEPS, "c", dayStart + 7 * h, 20.0))
        val points = HealthSeriesAggregator.bucketRows(HealthTypeDescriptor.of(HealthDataType.STEPS), rows, bounds)
        assertEquals(150.0, points[0].sum!!, 0.0)
        assertEquals(2, points[0].count)
        assertTrue(points[1].isEmpty)
        assertEquals(20.0, points[7].sum!!, 0.0)
    }

    @Test
    fun seriesPointsBucketToAverageMinMax() {
        val bounds = HealthSeriesAggregator.bucketBounds(MetricRange.D, day, zone)
        val points = listOf(60.0, 80.0, 100.0).mapIndexed { i, v -> HealthSeriesPoint("hr", "heart_rate", dayStart + 2 * h + i * 60_000, v) }
        val out = HealthSeriesAggregator.bucketPoints(points, bounds)
        assertEquals(80.0, out[2].avg!!, 0.0)
        assertEquals(60.0, out[2].min!!, 0.0)
        assertEquals(100.0, out[2].max!!, 0.0)
        assertEquals(3, out[2].count)
        assertTrue(out[3].isEmpty)
    }

    @Test
    fun calendarWeekSixMonthAndYearBoundsTileTheInterval() {
        // 2026-09-10 is a Thursday: the calendar week starts on Monday 2026-09-07.
        val week = HealthSeriesAggregator.bucketBounds(MetricRange.W, day, zone)
        assertEquals(7, week.size)
        assertEquals(LocalDate.of(2026, 9, 7).atStartOfDay(zone).toInstant().toEpochMilli(), week.first().first)
        val sunday = HealthSeriesAggregator.bucketBounds(MetricRange.W, day, zone, WeekStart.SUNDAY)
        assertEquals(LocalDate.of(2026, 9, 6).atStartOfDay(zone).toInstant().toEpochMilli(), sunday.first().first)
        val sixMonths = HealthSeriesAggregator.bucketBounds(MetricRange.SIX_MONTHS, day, zone)
        assertEquals(LocalDate.of(2026, 4, 1).atStartOfDay(zone).toInstant().toEpochMilli(), sixMonths.first().first)
        assertEquals(LocalDate.of(2026, 10, 1).atStartOfDay(zone).toInstant().toEpochMilli(), sixMonths.last().second)
        sixMonths.zipWithNext().forEach { (a, b) -> assertEquals(a.second, b.first) }
        val year = HealthSeriesAggregator.bucketBounds(MetricRange.Y, day, zone)
        assertEquals(12, year.size)
        assertEquals(LocalDate.of(2026, 1, 1).atStartOfDay(zone).toInstant().toEpochMilli(), year.first().first)
    }

    @Test
    fun dailyRollupsAggregateIntoLargerBucketsWithWeightedAverages() {
        val bounds = listOf(day.atStartOfDay(zone).toInstant().toEpochMilli() to day.plusDays(7).atStartOfDay(zone).toInstant().toEpochMilli())
        val rollups = listOf(
            HealthDailyRollup("heart_rate", day.toString(), "UTC", avg = 60.0, min = 50.0, max = 70.0, count = 2),
            HealthDailyRollup("heart_rate", day.plusDays(1).toString(), "UTC", avg = 90.0, min = 80.0, max = 120.0, count = 1)
        )
        val out = HealthSeriesAggregator.bucketDaily(HealthTypeDescriptor.of(HealthDataType.HEART_RATE), rollups, bounds, zone)
        assertEquals(1, out.size)
        assertEquals(70.0, out[0].avg!!, 1e-9)
        assertEquals(50.0, out[0].min!!, 0.0)
        assertEquals(120.0, out[0].max!!, 0.0)
        assertEquals(3, out[0].count)
        assertNull(out[0].v2Avg)
    }
}
