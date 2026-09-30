package com.ayuvo.health.ui.metrics

import com.ayuvo.health.data.derived.DerivedCatalog
import com.ayuvo.health.data.derived.DerivedMetricInfo
import com.ayuvo.health.data.health.DerivedPoint
import com.ayuvo.health.data.metrics.MetricBucket
import com.ayuvo.health.data.metrics.MetricBucketBounds
import com.ayuvo.health.data.metrics.MetricRange
import com.ayuvo.health.data.metrics.MetricSeriesBucket
import com.ayuvo.health.data.metrics.MetricsReference
import com.ayuvo.health.data.metrics.WeekStart
import com.ayuvo.health.ui.health.HealthValueFormatter
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** `aggregation` of a derived metric in `derived_config.json`. */
enum class DerivedAggregation(val raw: String) {
    AVERAGE("average"), SUM("sum"), MAX("max"), LATEST("latest");

    companion object {
        fun of(raw: String): DerivedAggregation = entries.firstOrNull { it.raw == raw } ?: AVERAGE
    }
}

/** Headline of a derived-metric interval: which statistic, and its value (null = no data). */
data class DerivedHeadline(val kind: Kind, val value: Double?, val days: Int) {
    enum class Kind { TOTAL, AVERAGE, HIGHEST, LATEST }
}

/** Where the values of an interval came from (native wins per day, docs/derived-metrics.md §1). */
enum class DerivedSourceMix { NONE, NATIVE, DERIVED, MIXED }

enum class DerivedConfidence { HIGH, MEDIUM, LOW }

/**
 * Pure helpers for derived metrics on the metric detail, Browse and Summary (docs/derived-metrics.md): one value per
 * day, bucketed by the metric's `aggregation`, clock metrics shown as times of day.
 */
object DerivedMetricSupport {
    /** Most fraction digits a derived value shows. */
    const val DISPLAY_DECIMALS = 2

    fun aggregation(info: DerivedMetricInfo): DerivedAggregation = DerivedAggregation.of(info.aggregation)

    /** Buckets of the interval; the Day range is one bucket for the whole day (derived values are daily). */
    fun bounds(range: MetricRange, anchor: LocalDate, zone: ZoneId, weekStart: WeekStart, nowMs: Long): MetricBucketBounds {
        val anchorMs = MetricsReference.localMidnight(anchor, zone)
        val b = MetricsReference.bucketBounds(range, anchorMs, zone, weekStart, nowMs)
        if (range != MetricRange.D) return b
        return b.copy(buckets = listOf(MetricBucket(b.startMs, b.endMs, anchor.toString())))
    }

    /** Aggregates one value per bucket; min/max are the day values inside it. */
    fun series(points: List<DerivedPoint>, bounds: MetricBucketBounds, aggregation: DerivedAggregation, zone: ZoneId): List<MetricSeriesBucket> =
        bounds.buckets.map { b ->
            val inside = points.filter { p -> MetricsReference.localMidnight(p.day, zone).let { it >= b.startMs && it < b.endMs } }
            MetricSeriesBucket(
                startMs = b.startMs,
                endMs = b.endMs,
                value = MetricsReference.round3(aggregate(inside, aggregation)),
                count = inside.size,
                min = inside.minOfOrNull { it.value }?.let { MetricsReference.round3(it) },
                max = inside.maxOfOrNull { it.value }?.let { MetricsReference.round3(it) }
            )
        }

    /**
     * Bucket value: mean for `average`; for `sum` the day's value, or the mean per day with data when a bucket spans
     * several days (weeks on 6M, months on Y), like the app metrics; the highest for `max`; the newest for `latest`.
     */
    fun aggregate(inside: List<DerivedPoint>, aggregation: DerivedAggregation): Double? {
        if (inside.isEmpty()) return null
        return when (aggregation) {
            DerivedAggregation.AVERAGE, DerivedAggregation.SUM -> mean(inside.map { it.value })
            DerivedAggregation.MAX -> inside.maxOf { it.value }
            DerivedAggregation.LATEST -> inside.maxBy { it.day }.value
        }
    }

    /** Headline over every day in the interval: D shows that day's value, longer ranges the metric's statistic. */
    fun headline(points: List<DerivedPoint>, range: MetricRange, aggregation: DerivedAggregation): DerivedHeadline {
        val days = points.size
        if (points.isEmpty()) {
            return DerivedHeadline(defaultKind(range, aggregation), null, 0)
        }
        val kind = defaultKind(range, aggregation)
        val value = when (kind) {
            DerivedHeadline.Kind.TOTAL, DerivedHeadline.Kind.LATEST -> points.maxBy { it.day }.value
            DerivedHeadline.Kind.AVERAGE -> mean(points.map { it.value })
            DerivedHeadline.Kind.HIGHEST -> points.maxOf { it.value }
        }
        return DerivedHeadline(kind, MetricsReference.round3(value), days)
    }

    private fun defaultKind(range: MetricRange, aggregation: DerivedAggregation): DerivedHeadline.Kind = when {
        aggregation == DerivedAggregation.LATEST -> DerivedHeadline.Kind.LATEST
        aggregation == DerivedAggregation.MAX && range != MetricRange.D -> DerivedHeadline.Kind.HIGHEST
        aggregation == DerivedAggregation.SUM && range == MetricRange.D -> DerivedHeadline.Kind.TOTAL
        range == MetricRange.D -> DerivedHeadline.Kind.LATEST
        else -> DerivedHeadline.Kind.AVERAGE
    }

    fun sourceMix(points: List<DerivedPoint>): DerivedSourceMix {
        if (points.isEmpty()) return DerivedSourceMix.NONE
        val native = points.count { it.sourceKind == "native" }
        return when (native) {
            0 -> DerivedSourceMix.DERIVED
            points.size -> DerivedSourceMix.NATIVE
            else -> DerivedSourceMix.MIXED
        }
    }

    /** Confidence of the Ayuvo estimates in the interval (the lowest quality wins); null without estimates. */
    fun confidence(points: List<DerivedPoint>): DerivedConfidence? {
        val q = points.filter { it.sourceKind == "derived" }.map { it.quality ?: 1.0 }.minOrNull() ?: return null
        return when {
            q >= 0.8 -> DerivedConfidence.HIGH
            q >= 0.5 -> DerivedConfidence.MEDIUM
            else -> DerivedConfidence.LOW
        }
    }

    /** Clock values are minutes after 12:00 on the day before the wake day (23:00 = 660, 07:00 = 1140). */
    fun clockTime(value: Double): LocalTime {
        val minutes = Math.floorMod(Math.round(value) + 720L, 1440L).toInt()
        return LocalTime.of(minutes / 60, minutes % 60)
    }

    fun formatClock(value: Double, is24: Boolean, locale: Locale = Locale.getDefault()): String =
        clockTime(value).format(DateTimeFormatter.ofPattern(if (is24) "HH:mm" else "h:mm a", locale))

    /** Unit label next to a value; clock and unitless metrics have none. */
    fun unitLabel(info: DerivedMetricInfo): String = if (info.isClock) "" else info.unit

    /**
     * Formatted value: a time for clock metrics, a band label when the config has labels for the metric
     * (`step_band`), else the number with the catalog's decimals.
     */
    fun format(info: DerivedMetricInfo, value: Double, is24: Boolean, labels: List<String>? = null, locale: Locale = Locale.getDefault()): String {
        if (info.isClock) return formatClock(value, is24, locale)
        if (!labels.isNullOrEmpty()) {
            val i = Math.round(value).toInt()
            if (i in labels.indices && Math.abs(value - i) < 1e-6) return labels[i]
        }
        // Up to two decimals (trailing zeros dropped), so small values such as a 0.39 % sound dose never read as 0.
        val nf = java.text.NumberFormat.getNumberInstance(locale).apply {
            minimumFractionDigits = 0
            maximumFractionDigits = maxOf(info.decimals, DISPLAY_DECIMALS)
            roundingMode = java.math.RoundingMode.HALF_UP
        }
        return nf.format(value)
    }

    /** The Health registry category (and so the Browse domain) a derived metric is listed under. */
    fun healthCategory(info: DerivedMetricInfo): String = DerivedCatalog.healthCategoryOf(info.category)

    /** The newest point and the 7 days ending [today] (oldest → newest, missing days null) for tiles and rows. */
    fun recent(points: List<DerivedPoint>, today: LocalDate): Pair<DerivedPoint?, List<Double?>> {
        val byDay = points.associateBy { it.day }
        val spark = (6 downTo 0).map { k -> byDay[today.minusDays(k.toLong())]?.value }
        return points.filter { !it.day.isAfter(today) }.maxByOrNull { it.day } to spark
    }

    private fun mean(values: List<Double>): Double {
        var s = 0.0
        for (v in values) s += v
        return s / values.size
    }
}
