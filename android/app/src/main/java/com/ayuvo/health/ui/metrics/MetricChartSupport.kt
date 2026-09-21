package com.ayuvo.health.ui.metrics

import com.ayuvo.health.data.health.HealthChartPoint
import com.ayuvo.health.data.metrics.MetricSeriesBucket
import com.ayuvo.health.ui.charts.spreadLabels
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

    fun xLabels(range: HealthChartRange, bucketStarts: List<Long>, zone: ZoneId, locale: Locale = Locale.getDefault()): List<String> {
        val dates = bucketStarts.map { Instant.ofEpochMilli(it).atZone(zone) }
        val monthDay = DateTimeFormatter.ofPattern("MMM d", locale)
        return when (range) {
            HealthChartRange.DAY -> listOf("0", "6", "12", "18", "24")
            HealthChartRange.WEEK -> dates.map { it.dayOfWeek.getDisplayName(TextStyle.SHORT, locale) }
            HealthChartRange.MONTH -> spreadLabels(dates.map { monthDay.format(it) }, 5)
            HealthChartRange.SIX_MONTHS -> spreadLabels(dates.map { monthDay.format(it) }, 6)
            HealthChartRange.YEAR -> dates.map { it.month.getDisplayName(TextStyle.NARROW, locale) }
        }
    }

    fun tooltip(range: HealthChartRange, bucketStartMs: Long, zone: ZoneId, locale: Locale = Locale.getDefault()): String {
        val at = Instant.ofEpochMilli(bucketStartMs).atZone(zone)
        return when (range) {
            HealthChartRange.DAY -> String.format(locale, "%02d:00", at.hour)
            HealthChartRange.YEAR -> DateTimeFormatter.ofPattern("MMM yyyy", locale).format(at)
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
