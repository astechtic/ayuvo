package com.ayuvo.health.ui.health

import com.ayuvo.health.R
import com.ayuvo.health.data.health.HealthBucket
import com.ayuvo.health.data.metrics.MetricBucketBounds
import com.ayuvo.health.data.metrics.MetricRange
import com.ayuvo.health.data.metrics.MetricsReference
import com.ayuvo.health.data.metrics.WeekStart
import java.time.LocalDate
import java.time.ZoneId

/**
 * Detail chart ranges D/W/M/6M/Y. Windows, buckets and anchor steps follow the shared
 * calendar-aligned contract (docs/ui-structure.md §5) through [MetricsReference].
 */
enum class HealthChartRange(val labelRes: Int, val metricRange: MetricRange) {
    DAY(R.string.health_range_day, MetricRange.D),
    WEEK(R.string.health_range_week, MetricRange.W),
    MONTH(R.string.health_range_month, MetricRange.M),
    SIX_MONTHS(R.string.health_range_six_months, MetricRange.SIX_MONTHS),
    YEAR(R.string.health_range_year, MetricRange.Y);

    /** Calendar interval containing [anchor] as an inclusive date range. */
    fun window(anchor: LocalDate, weekStart: WeekStart = WeekStart.MONDAY, zone: ZoneId = ZoneId.systemDefault()): ClosedRange<LocalDate> {
        val b = bounds(anchor, weekStart, zone, Long.MAX_VALUE)
        return MetricsReference.localDateOf(b.startMs, zone)..MetricsReference.localDateOf(b.endMs, zone).minusDays(1)
    }

    fun bounds(anchor: LocalDate, weekStart: WeekStart, zone: ZoneId, nowMs: Long): MetricBucketBounds =
        MetricsReference.bucketBounds(metricRange, MetricsReference.localMidnight(anchor, zone), zone, weekStart, nowMs)

    /** One range back (-1) or forward (+1), never past today. */
    fun step(anchor: LocalDate, direction: Int, zone: ZoneId, nowMs: Long): LocalDate =
        MetricsReference.stepAnchor(metricRange, MetricsReference.localMidnight(anchor, zone), direction, zone, nowMs).date

    /** 6M and Y bars of summed metrics show the daily average (§5). */
    val plotsDailyAverage: Boolean get() = this == SIX_MONTHS || this == YEAR

    val bucket: HealthBucket
        get() = when (this) {
            DAY -> HealthBucket.HOUR
            WEEK, MONTH -> HealthBucket.DAY
            SIX_MONTHS -> HealthBucket.WEEK
            YEAR -> HealthBucket.MONTH
        }

    companion object {
        fun of(range: MetricRange): HealthChartRange = entries.first { it.metricRange == range }
    }
}
