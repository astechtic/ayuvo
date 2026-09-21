package com.ayuvo.health.data.health

import com.ayuvo.health.data.metrics.MetricRange
import com.ayuvo.health.data.metrics.MetricsReference
import com.ayuvo.health.data.metrics.WeekStart
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

enum class HealthBucket { HOUR, DAY, WEEK, MONTH }

/** One chart bucket: sum/avg/min/max/count over the rows or series points that fall inside it. */
data class HealthChartPoint(
    val bucketStartMs: Long,
    val bucketEndMs: Long,
    val sum: Double? = null,
    val avg: Double? = null,
    val min: Double? = null,
    val max: Double? = null,
    val count: Int = 0,
    val v2Avg: Double? = null,
    val v2Min: Double? = null,
    val v2Max: Double? = null
) {
    val isEmpty: Boolean get() = count == 0
}

/**
 * Buckets samples (or intra-record series points) into chart points for the detail
 * screen. Pure and zone-explicit so it can be unit-tested; heavy ranges run it on
 * `Dispatchers.Default` from the ViewModel.
 */
object HealthSeriesAggregator {

    /**
     * Chart bucket bounds (start ms to end ms) for [range] anchored at [anchor], using the shared
     * calendar-aligned rules (docs/ui-structure.md §5, [MetricsReference.bucketBounds]).
     */
    fun bucketBounds(range: MetricRange, anchor: LocalDate, zone: ZoneId, weekStart: WeekStart = WeekStart.MONDAY): List<Pair<Long, Long>> =
        MetricsReference.bucketBounds(range, MetricsReference.localMidnight(anchor, zone), zone, weekStart, Long.MAX_VALUE)
            .buckets.map { it.startMs to it.endMs }

    /** Buckets by sample rows (cumulative sums, discrete averages, min/max with condensed rows). */
    fun bucketRows(
        type: HealthTypeDescriptor,
        rows: List<HealthSampleRow>,
        bounds: List<Pair<Long, Long>>
    ): List<HealthChartPoint> {
        if (bounds.isEmpty()) return emptyList()
        val accs = Array(bounds.size) { HealthRollupMath.Accumulator() }
        val live = rows.filter { !it.deleted }.sortedBy { it.startMs }
        var b = 0
        for (row in live) {
            val anchor = if (type.isDurationLike) row.startMs else row.startMs
            while (b < bounds.size && bounds[b].second <= anchor) b++
            if (b >= bounds.size) break
            if (anchor < bounds[b].first) continue
            accs[b].add(type, row)
        }
        return bounds.mapIndexed { i, (s, e) ->
            val r = accs[i].toRollup(type, "", "")
            HealthChartPoint(
                bucketStartMs = s, bucketEndMs = e,
                sum = if (type.isDurationLike) r.durationS else r.sum,
                avg = r.avg, min = r.min, max = r.max, count = r.count,
                v2Avg = r.v2Avg, v2Min = r.v2Min, v2Max = r.v2Max
            )
        }
    }

    /** Buckets by intra-record series points (heart rate, speed, …): avg/min/max per bucket. */
    fun bucketPoints(points: List<HealthSeriesPoint>, bounds: List<Pair<Long, Long>>): List<HealthChartPoint> {
        if (bounds.isEmpty()) return emptyList()
        val sums = DoubleArray(bounds.size)
        val counts = IntArray(bounds.size)
        val mins = DoubleArray(bounds.size) { Double.POSITIVE_INFINITY }
        val maxs = DoubleArray(bounds.size) { Double.NEGATIVE_INFINITY }
        var b = 0
        for (p in points.sortedBy { it.tMs }) {
            while (b < bounds.size && bounds[b].second <= p.tMs) b++
            if (b >= bounds.size) break
            if (p.tMs < bounds[b].first) continue
            sums[b] += p.value
            counts[b]++
            if (p.value < mins[b]) mins[b] = p.value
            if (p.value > maxs[b]) maxs[b] = p.value
        }
        return bounds.mapIndexed { i, (s, e) ->
            if (counts[i] == 0) HealthChartPoint(s, e)
            else HealthChartPoint(s, e, sum = sums[i], avg = sums[i] / counts[i], min = mins[i], max = maxs[i], count = counts[i])
        }
    }

    /** Daily rollups → chart points for W/M (one per day), 6M (weekly), Y (monthly). */
    fun bucketDaily(type: HealthTypeDescriptor, rollups: List<HealthDailyRollup>, bounds: List<Pair<Long, Long>>, zone: ZoneId): List<HealthChartPoint> {
        if (bounds.isEmpty()) return emptyList()
        val byDayMs = rollups.associateBy { LocalDate.parse(it.day).atStartOfDay(zone).toInstant().toEpochMilli() }
        return bounds.map { (s, e) ->
            val inBucket = byDayMs.filterKeys { it >= s && it < e }.values.filter { it.count > 0 }
            if (inBucket.isEmpty()) return@map HealthChartPoint(s, e)
            val count = inBucket.sumOf { it.count }
            val sum = inBucket.sumOf { (if (type.isDurationLike) it.durationS ?: it.sum else it.sum) ?: 0.0 }
            val avg = when {
                type.aggregation.name == "SUM" || type.isDurationLike -> sum / inBucket.size
                else -> inBucket.sumOf { (it.avg ?: 0.0) * it.count } / count
            }
            HealthChartPoint(
                bucketStartMs = s, bucketEndMs = e,
                sum = sum, avg = avg,
                min = inBucket.mapNotNull { it.min }.minOrNull(),
                max = inBucket.mapNotNull { it.max }.maxOrNull(),
                count = count,
                v2Avg = inBucket.mapNotNull { it.v2Avg }.takeIf { it.isNotEmpty() }?.average(),
                v2Min = inBucket.mapNotNull { it.v2Min }.minOrNull(),
                v2Max = inBucket.mapNotNull { it.v2Max }.maxOrNull()
            )
        }
    }

    fun daysBetween(from: LocalDate, to: LocalDate): Long = ChronoUnit.DAYS.between(from, to)
}
