package com.ayuvo.health.ui.metrics

import com.ayuvo.health.data.health.HealthChartPoint
import com.ayuvo.health.data.metrics.MetricsReference
import com.ayuvo.health.ui.health.HealthChartRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class MetricNavigationTest {
    private val zone = ZoneId.of("America/New_York")
    private val all = HealthChartRange.entries.toList()
    private val noDay = all - HealthChartRange.DAY
    private val today = LocalDate.of(2026, 9, 22)

    private fun point(day: LocalDate) = HealthChartPoint(
        bucketStartMs = MetricsReference.localMidnight(day, zone),
        bucketEndMs = MetricsReference.localMidnight(day.plusDays(1), zone),
        sum = 1.0, avg = 1.0, min = 1.0, max = 1.0, count = 1
    )

    @Test
    fun weekTapOnDayWithDataDrillsToThatDay() {
        val pts = listOf(point(LocalDate.of(2026, 9, 14)), point(LocalDate.of(2026, 9, 15)))
        assertEquals(LocalDate.of(2026, 9, 15), MetricNavigation.drillDay(HealthChartRange.WEEK, pts, 1, true, all, zone))
        assertEquals(LocalDate.of(2026, 9, 14), MetricNavigation.drillDay(HealthChartRange.MONTH, pts, 0, true, all, zone))
    }

    @Test
    fun tapOnlySelectsWithoutDataOrDayRangeOrOnLongRanges() {
        val pts = listOf(point(LocalDate.of(2026, 9, 15)))
        assertNull(MetricNavigation.drillDay(HealthChartRange.WEEK, pts, 0, false, all, zone))
        assertNull(MetricNavigation.drillDay(HealthChartRange.WEEK, pts, 0, true, noDay, zone))
        assertNull(MetricNavigation.drillDay(HealthChartRange.SIX_MONTHS, pts, 0, true, all, zone))
        assertNull(MetricNavigation.drillDay(HealthChartRange.DAY, pts, 0, true, all, zone))
        assertNull(MetricNavigation.drillDay(HealthChartRange.WEEK, pts, 5, true, all, zone))
    }

    @Test
    fun rangeChangeKeepsTheAnchorSoWeekShowsTheDrilledDaysWeek() {
        val drilled = HealthChartRange.DAY to LocalDate.of(2026, 9, 3)
        assertEquals(HealthChartRange.WEEK to LocalDate.of(2026, 9, 3), MetricNavigation.changeRange(drilled, HealthChartRange.WEEK, today))
    }

    @Test
    fun rangeChangeNeverPassesToday() {
        val future = HealthChartRange.WEEK to LocalDate.of(2026, 9, 30)
        assertEquals(HealthChartRange.DAY to today, MetricNavigation.changeRange(future, HealthChartRange.DAY, today))
    }
}
