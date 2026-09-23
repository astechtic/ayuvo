package com.ayuvo.health.ui.metrics

import com.ayuvo.health.data.health.HealthChartPoint
import com.ayuvo.health.data.metrics.MetricSeriesBucket
import com.ayuvo.health.data.metrics.MetricsReference
import com.ayuvo.health.data.metrics.WeekStart
import com.ayuvo.health.ui.charts.ChartClock
import com.ayuvo.health.ui.health.HealthChartRange
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.util.Locale

/** Labels and chart-point conversion shared by app and health metric details. */
object MetricChartSupport {

    /** "10 Sep 2026", "7 – 13 Sep 2026", "Sep 2026", "Apr – Sep 2026", "2026". */
    fun windowLabel(range: HealthChartRange, window: ClosedRange<LocalDate>, locale: Locale = Locale.getDefault()): String {
        val medium = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale)
        val monthYear = DateTimeFormatter.ofPattern("MMM yyyy", locale)
        return when (range) {
            HealthChartRange.DAY -> medium.format(window.start)
            HealthChartRange.WEEK -> "${medium.format(window.start)} – ${medium.format(window.endInclusive)}"
            HealthChartRange.MONTH -> DateTimeFormatter.ofPattern("MMMM yyyy", locale).format(window.start)
            HealthChartRange.SIX_MONTHS -> "${monthYear.format(window.start)} – ${monthYear.format(window.endInclusive)}"
            HealthChartRange.YEAR -> window.start.year.toString()
        }
    }

    /**
     * X-axis labels as (bucket index, text) from `x_ticks`: D 0/6/12/18 h (12/24 h), W narrow
     * weekdays, M week-start days, 6M first bucket of each month, Y narrow months.
     */
    fun xLabels(
        range: HealthChartRange,
        anchor: LocalDate,
        weekStart: WeekStart,
        zone: ZoneId,
        is24: Boolean,
        locale: Locale = Locale.getDefault()
    ): List<Pair<Int, String>> {
        val anchorMs = MetricsReference.localMidnight(anchor, zone)
        val buckets = MetricsReference.bucketBounds(range.metricRange, anchorMs, zone, weekStart, anchorMs).buckets
        return MetricsReference.xTicks(range.metricRange, anchorMs, zone, weekStart).map { i ->
            val at = Instant.ofEpochMilli(buckets[i].startMs).atZone(zone)
            i to when (range) {
                HealthChartRange.DAY -> if (is24) at.hour.toString() else ChartClock.time(buckets[i].startMs, zone, false, locale, short = true)
                HealthChartRange.WEEK -> at.dayOfWeek.getDisplayName(TextStyle.NARROW_STANDALONE, locale)
                HealthChartRange.MONTH -> at.dayOfMonth.toString()
                HealthChartRange.SIX_MONTHS -> at.month.getDisplayName(TextStyle.SHORT_STANDALONE, locale)
                HealthChartRange.YEAR -> at.month.getDisplayName(TextStyle.NARROW_STANDALONE, locale)
            }
        }
    }

    /** Date or time of one bucket, shown under the headline while it is selected. */
    fun tooltip(range: HealthChartRange, bucketStartMs: Long, bucketEndMs: Long, zone: ZoneId, is24: Boolean, locale: Locale = Locale.getDefault()): String {
        val at = Instant.ofEpochMilli(bucketStartMs).atZone(zone)
        return when (range) {
            HealthChartRange.DAY -> "${ChartClock.time(bucketStartMs, zone, is24, locale)} – ${ChartClock.time(bucketEndMs, zone, is24, locale)}"
            HealthChartRange.SIX_MONTHS -> "${DateTimeFormatter.ofPattern("MMM d", locale).format(at)} – ${DateTimeFormatter.ofPattern("MMM d", locale).format(Instant.ofEpochMilli(bucketEndMs - 1).atZone(zone))}"
            HealthChartRange.YEAR -> DateTimeFormatter.ofPattern("MMMM yyyy", locale).format(at)
            else -> DateTimeFormatter.ofPattern("EEE, MMM d", locale).format(at)
        }
    }

    /** Shared-contract buckets → chart points (values already converted with [convert]). */
    fun points(buckets: List<MetricSeriesBucket>, convert: (Double) -> Double): List<HealthChartPoint> = buckets.map { b ->
        val v = b.value?.let(convert)
        HealthChartPoint(
            bucketStartMs = b.startMs,
            bucketEndMs = b.endMs,
            sum = v,
            avg = v,
            min = b.min?.let(convert) ?: v,
            max = b.max?.let(convert) ?: v,
            count = if (v == null) 0 else b.count.coerceAtLeast(1)
        )
    }
}

/** Range / anchor moves of the metric detail screens (docs/charts.md "Tap a day → Day chart"). */
object MetricNavigation {
    /** Day to open when bucket [index] of [points] is tapped on W/M, or null when the tap only selects. */
    fun drillDay(range: HealthChartRange, points: List<HealthChartPoint>, index: Int, hasData: Boolean, ranges: List<HealthChartRange>, zone: ZoneId): LocalDate? {
        val p = points.getOrNull(index) ?: return null
        return drillDay(range, p.bucketStartMs, hasData, ranges, zone)
    }

    fun drillDay(range: HealthChartRange, bucketStartMs: Long, hasData: Boolean, ranges: List<HealthChartRange>, zone: ZoneId): LocalDate? =
        MetricsReference.drillTarget(range.metricRange, bucketStartMs, hasData, ranges.map { it.metricRange }, zone)?.anchorDate

    /** A range change keeps the anchor (never past today), so W after a drill shows that day's week. */
    fun changeRange(current: Pair<HealthChartRange, LocalDate>, range: HealthChartRange, today: LocalDate): Pair<HealthChartRange, LocalDate> =
        range to minOf(current.second, today)
}
