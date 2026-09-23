package com.ayuvo.health.data.metrics

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

/** Chart ranges shared by app and health metrics (docs/ui-structure.md §5). */
enum class MetricRange(val raw: String) {
    D("D"), W("W"), M("M"), SIX_MONTHS("6M"), Y("Y");

    companion object {
        fun fromRaw(raw: String): MetricRange = entries.first { it.raw == raw }
    }
}

enum class WeekStart(val raw: String) {
    MONDAY("monday"), SUNDAY("sunday");

    companion object {
        fun fromRaw(raw: String): WeekStart = entries.first { it.raw == raw }
        fun of(mondayFirst: Boolean): WeekStart = if (mondayFirst) MONDAY else SUNDAY
    }
}

enum class MetricAggregation(val raw: String) {
    SUM("sum"), AVG("avg"), LAST("last"), COUNT("count"), DURATION("duration");

    val summed: Boolean get() = this == SUM || this == DURATION || this == COUNT

    companion object {
        fun fromRaw(raw: String): MetricAggregation = entries.first { it.raw == raw }
    }
}

data class MetricBucket(val startMs: Long, val endMs: Long, val label: String)

data class MetricBucketBounds(
    val startMs: Long,
    val endMs: Long,
    val buckets: List<MetricBucket>,
    val canGoForward: Boolean
)

data class MetricAnchor(val date: LocalDate, val anchorMs: Long)

/** One input sample; `value == null` entries are ignored everywhere. */
data class MetricEntry(val tMs: Long, val value: Double?)

data class MetricSeriesBucket(
    val startMs: Long,
    val endMs: Long,
    val value: Double?,
    val count: Int,
    val min: Double?,
    val max: Double?
)

enum class HeadlineKind { TOTAL, AVERAGE, LATEST }

data class MetricHeadline(
    val kind: HeadlineKind,
    val value: Double?,
    val fromMs: Long,
    val toMs: Long,
    val daysWithData: Int
)

data class MetricSparkline(val values: List<Double?>, val min: Double?, val max: Double?, val hasData: Boolean)

data class FastingSpan(val startedAtMs: Long, val endedAtMs: Long?)

data class WorkoutSpan(val diaryDate: String?, val startedAtMs: Long, val durationS: Long, val calories: Double?)

data class WorkoutBucketStats(
    val startMs: Long,
    val endMs: Long,
    val count: Int,
    val durationS: Long,
    val burnKcal: Double?,
    val burnCount: Int
)

enum class RingState(val raw: String) { VALUE("value"), NO_GOAL("no_goal"), NO_DATA("no_data") }

data class RingProgress(val state: RingState, val progress: Double?, val percent: Int?, val over: Boolean)

enum class PinSource(val raw: String) { NEW("new"), MIGRATED("migrated"), DEFAULT("default") }

data class PinsResult(val favourites: List<String>, val source: PinSource)

/** Registry facts `resolve_metric` needs (the Android mirror is `HealthDataType`). */
data class RegistryFacts(val category: String, val aggregation: String, val unit: String)

data class ResolvedMetric(
    val source: String,
    val domain: String,
    val colourHex: String,
    val colourHexDark: String,
    val aggregation: String,
    val chartKind: String,
    val unit: String,
    val goalSource: String,
    val defaultFavouriteOrder: Int?,
    val browseHidden: Boolean,
    val iconAndroid: String,
    val iconIos: String
)

/** Y-axis ticks (`nice_ticks`). */
data class NiceTicks(val min: Double, val max: Double, val step: Double, val ticks: List<Double>)

/** Where a tap on a W/M day bucket leads (`drill_target`). */
data class DrillTarget(val range: MetricRange, val anchorDate: LocalDate, val anchorMs: Long)

/** One sleep row of a single source: stage 0 in bed, 1 unspecified, 2 awake, 3 core, 4 deep, 5 REM, 6 out of bed. */
data class SleepRow(val startMs: Long, val endMs: Long, val stage: Int)

data class SleepStageTotals(val unspecifiedS: Long, val awakeS: Long, val coreS: Long, val deepS: Long, val remS: Long)

data class SleepStagePct(val unspecified: Int?, val core: Int?, val deep: Int?, val rem: Int?)

/** Day-range sleep chart geometry and totals (`sleep_night_window`). */
data class SleepWindow(
    val bedtimeMs: Long,
    val wakeMs: Long,
    val domainStartMs: Long,
    val domainEndMs: Long,
    val tickStepMs: Long,
    val ticks: List<Long>,
    val asleepS: Long,
    val inBedS: Long,
    val stages: SleepStageTotals,
    val pct: SleepStagePct
)

/** One night for the W/M/6M/Y sleep chart (`sleep_range_series` input). */
data class SleepNightSpan(val wakeDay: String, val bedtimeMs: Long, val wakeMs: Long, val asleepS: Double, val inBedS: Double)

data class SleepRangeBucket(
    val startMs: Long,
    val endMs: Long,
    val count: Int,
    val bedOffsetMin: Double?,
    val wakeOffsetMin: Double?,
    val asleepS: Double?,
    val inBedS: Double?
)

data class SleepRangeDomain(val min: Int, val max: Int, val ticks: List<Int>)

data class SleepRangeHeadline(val nights: Int, val asleepS: Double?, val bedOffsetMin: Double?, val wakeOffsetMin: Double?)

data class SleepRangeSeries(val buckets: List<SleepRangeBucket>, val domain: SleepRangeDomain?, val headline: SleepRangeHeadline)

/**
 * Line-by-line port of `scripts/metrics_reference.py`. Pure and zone-explicit; run against
 * `shared/metrics/test-vectors` by `MetricsVectorTests`. When this file and the reference
 * disagree, the reference wins.
 */
object MetricsReference {
    const val HOUR_MS = 3_600_000L

    /** Half away from zero, 3 decimals. */
    fun round3(x: Double?): Double? {
        if (x == null) return null
        val sign = if (x < 0) -1.0 else 1.0
        return sign * (floor(abs(x) * 1000 + 0.5) / 1000)
    }

    fun localMidnight(d: LocalDate, zone: ZoneId): Long = d.atStartOfDay(zone).toInstant().toEpochMilli()
    fun localDateOf(ms: Long, zone: ZoneId): LocalDate = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()
    private fun localHourOf(ms: Long, zone: ZoneId): Int = Instant.ofEpochMilli(ms).atZone(zone).hour

    fun weekStartOf(d: LocalDate, weekStart: WeekStart): LocalDate {
        val iso = d.dayOfWeek.value // Monday 1 .. Sunday 7
        val offset = if (weekStart == WeekStart.SUNDAY) iso % 7 else iso - 1
        return d.minusDays(offset.toLong())
    }

    private fun intervalDates(range: MetricRange, anchor: LocalDate, weekStart: WeekStart): Pair<LocalDate, LocalDate> = when (range) {
        MetricRange.D -> anchor to anchor.plusDays(1)
        MetricRange.W -> weekStartOf(anchor, weekStart).let { it to it.plusDays(7) }
        MetricRange.M -> anchor.withDayOfMonth(1).let { it to it.plusMonths(1) }
        MetricRange.SIX_MONTHS -> anchor.withDayOfMonth(1).let { it.plusMonths(-5) to it.plusMonths(1) }
        MetricRange.Y -> LocalDate.of(anchor.year, 1, 1) to LocalDate.of(anchor.year + 1, 1, 1)
    }

    fun bucketBounds(range: MetricRange, anchorMs: Long, zone: ZoneId, weekStart: WeekStart, nowMs: Long): MetricBucketBounds {
        val anchor = localDateOf(anchorMs, zone)
        val (first, after) = intervalDates(range, anchor, weekStart)
        val startMs = localMidnight(first, zone)
        val endMs = localMidnight(after, zone)
        val buckets = ArrayList<MetricBucket>()
        when (range) {
            MetricRange.D -> {
                var t = startMs
                while (t < endMs) {
                    buckets += MetricBucket(t, minOf(t + HOUR_MS, endMs), "%02d".format(localHourOf(t, zone)))
                    t += HOUR_MS
                }
            }
            MetricRange.W, MetricRange.M -> {
                var d = first
                while (d < after) {
                    val n = d.plusDays(1)
                    buckets += MetricBucket(localMidnight(d, zone), localMidnight(n, zone), d.toString())
                    d = n
                }
            }
            MetricRange.SIX_MONTHS -> {
                var d = first
                while (d < after) {
                    val n = minOf(weekStartOf(d, weekStart).plusDays(7), after)
                    buckets += MetricBucket(localMidnight(d, zone), localMidnight(n, zone), d.toString())
                    d = n
                }
            }
            MetricRange.Y -> for (m in 1..12) {
                val d = LocalDate.of(first.year, m, 1)
                buckets += MetricBucket(localMidnight(d, zone), localMidnight(d.plusMonths(1), zone), d.toString())
            }
        }
        return MetricBucketBounds(startMs, endMs, buckets, endMs <= nowMs)
    }

    fun stepAnchor(range: MetricRange, anchorMs: Long, direction: Int, zone: ZoneId, nowMs: Long): MetricAnchor {
        require(direction == -1 || direction == 1) { "bad direction $direction" }
        val d = localDateOf(anchorMs, zone)
        var r = when (range) {
            MetricRange.D -> d.plusDays(direction.toLong())
            MetricRange.W -> d.plusDays(7L * direction)
            MetricRange.M -> d.plusMonths(direction.toLong())
            MetricRange.SIX_MONTHS -> d.plusMonths(6L * direction)
            MetricRange.Y -> d.plusMonths(12L * direction)
        }
        val today = localDateOf(nowMs, zone)
        if (r > today) r = today
        return MetricAnchor(r, localMidnight(r, zone))
    }

    private data class Item(val tMs: Long, val index: Int, val value: Double)

    private fun clean(entries: List<MetricEntry>): List<Item> =
        entries.mapIndexedNotNull { i, e -> e.value?.let { Item(e.tMs, i, it) } }

    private fun unitValue(aggregation: MetricAggregation, v: Double) = if (aggregation == MetricAggregation.COUNT) 1.0 else v

    private fun latest(items: List<Item>): Item? {
        var best: Item? = null
        for (it in items) {
            val b = best
            if (b == null || it.tMs > b.tMs || (it.tMs == b.tMs && it.index > b.index)) best = it
        }
        return best
    }

    private fun sum(values: Iterable<Double>): Double {
        var s = 0.0
        for (v in values) s += v
        return s
    }

    private fun aggregate(items: List<Item>, aggregation: MetricAggregation, dailyMean: Boolean, zone: ZoneId): Double? {
        if (items.isEmpty()) return null
        return when {
            aggregation == MetricAggregation.AVG -> sum(items.map { it.value }) / items.size
            aggregation == MetricAggregation.LAST -> latest(items)!!.value
            dailyMean -> {
                val days = LinkedHashMap<LocalDate, Double>()
                for (it in items) {
                    val k = localDateOf(it.tMs, zone)
                    days[k] = (days[k] ?: 0.0) + unitValue(aggregation, it.value)
                }
                sum(days.values) / days.size
            }
            else -> sum(items.map { unitValue(aggregation, it.value) })
        }
    }

    fun bucketSeries(
        entries: List<MetricEntry>,
        range: MetricRange,
        anchorMs: Long,
        zone: ZoneId,
        weekStart: WeekStart,
        aggregation: MetricAggregation
    ): List<MetricSeriesBucket> {
        val bounds = bucketBounds(range, anchorMs, zone, weekStart, anchorMs)
        val items = clean(entries)
        val dailyMean = (range == MetricRange.SIX_MONTHS || range == MetricRange.Y) && aggregation.summed
        return bounds.buckets.map { b ->
            val inside = items.filter { b.startMs <= it.tMs && it.tMs < b.endMs }
            val value = aggregate(inside, aggregation, dailyMean, zone)
            val rawRange = inside.isNotEmpty() && (aggregation == MetricAggregation.AVG || aggregation == MetricAggregation.LAST)
            MetricSeriesBucket(
                startMs = b.startMs, endMs = b.endMs, value = round3(value), count = inside.size,
                min = if (rawRange) round3(inside.minOf { it.value }) else null,
                max = if (rawRange) round3(inside.maxOf { it.value }) else null
            )
        }
    }

    fun headline(
        entries: List<MetricEntry>,
        range: MetricRange,
        anchorMs: Long,
        zone: ZoneId,
        weekStart: WeekStart,
        aggregation: MetricAggregation
    ): MetricHeadline {
        val bounds = bucketBounds(range, anchorMs, zone, weekStart, anchorMs)
        val s = bounds.startMs
        val e = bounds.endMs
        val items = clean(entries).filter { s <= it.tMs && it.tMs < e }
        val days = items.map { localDateOf(it.tMs, zone) }.toSet().size
        return when {
            aggregation.summed -> {
                val kind = if (range == MetricRange.D) HeadlineKind.TOTAL else HeadlineKind.AVERAGE
                val total = sum(items.map { unitValue(aggregation, it.value) })
                val value = if (items.isEmpty()) null else if (kind == HeadlineKind.TOTAL) total else total / days
                MetricHeadline(kind, round3(value), s, e, days)
            }
            aggregation == MetricAggregation.AVG -> {
                val value = if (items.isEmpty()) null else sum(items.map { it.value }) / items.size
                MetricHeadline(HeadlineKind.AVERAGE, round3(value), s, e, days)
            }
            else -> {
                val best = latest(items) ?: return MetricHeadline(HeadlineKind.LATEST, null, s, e, 0)
                MetricHeadline(HeadlineKind.LATEST, round3(best.value), best.tMs, best.tMs, days)
            }
        }
    }

    fun sparkline7d(entries: List<MetricEntry>, nowMs: Long, zone: ZoneId, aggregation: MetricAggregation): MetricSparkline {
        val today = localDateOf(nowMs, zone)
        val items = clean(entries)
        val values = (6 downTo 0).map { k ->
            val d = today.minusDays(k.toLong())
            val s = localMidnight(d, zone)
            val e = localMidnight(d.plusDays(1), zone)
            round3(aggregate(items.filter { s <= it.tMs && it.tMs < e }, aggregation, false, zone))
        }
        val present = values.filterNotNull()
        return MetricSparkline(values, present.minOrNull(), present.maxOrNull(), present.isNotEmpty())
    }

    /** Seconds fasted per local day, ascending, only days with seconds > 0. */
    fun fastingSecondsPerDay(sessions: List<FastingSpan>, nowMs: Long, zone: ZoneId): List<Pair<LocalDate, Long>> {
        val perDay = HashMap<LocalDate, Long>()
        for (s in sessions) {
            val start = s.startedAtMs
            val end = s.endedAtMs ?: nowMs
            if (end <= start) continue
            var d = localDateOf(start, zone)
            while (true) {
                val ds = localMidnight(d, zone)
                val de = localMidnight(d.plusDays(1), zone)
                if (ds >= end) break
                val overlap = minOf(end, de) - maxOf(start, ds)
                if (overlap > 0) perDay[d] = (perDay[d] ?: 0L) + overlap / 1000
                d = d.plusDays(1)
            }
        }
        return perDay.entries.filter { it.value > 0 }.sortedBy { it.key }.map { it.key to it.value }
    }

    fun workoutDay(diaryDate: String?, startedAtMs: Long, zone: ZoneId): LocalDate {
        val parsed = diaryDate?.takeIf { DATE_RE.matches(it) }?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        return parsed ?: localDateOf(startedAtMs, zone)
    }

    fun workoutStatsPerBucket(
        sessions: List<WorkoutSpan>,
        range: MetricRange,
        anchorMs: Long,
        zone: ZoneId,
        weekStart: WeekStart
    ): List<WorkoutBucketStats> {
        val bounds = bucketBounds(range, anchorMs, zone, weekStart, anchorMs)
        val placed = sessions.map { localMidnight(workoutDay(it.diaryDate, it.startedAtMs, zone), zone) to it }
        return bounds.buckets.map { b ->
            val inside = placed.filter { b.startMs <= it.first && it.first < b.endMs }.map { it.second }
            val burns = inside.mapNotNull { it.calories }
            WorkoutBucketStats(
                startMs = b.startMs, endMs = b.endMs, count = inside.size,
                durationS = inside.sumOf { it.durationS },
                burnKcal = if (burns.isEmpty()) null else sum(burns),
                burnCount = burns.size
            )
        }
    }

    fun ringProgress(value: Double?, goal: Double?): RingProgress {
        if (goal == null || goal <= 0) return RingProgress(RingState.NO_GOAL, null, null, false)
        if (value == null) return RingProgress(RingState.NO_DATA, null, null, false)
        val p = (value / goal).coerceIn(0.0, 1.0)
        return RingProgress(RingState.VALUE, round3(p), floor(value * 100.0 / goal + 0.5).toInt(), value > goal)
    }

    fun defaultFavourites(catalog: MetricCatalogData): List<String> {
        val rows = ArrayList<Pair<Int, String>>()
        catalog.metrics.forEach { m -> m.defaultFavouriteOrder?.let { rows += it to m.key } }
        catalog.overrides.forEach { o -> o.defaultFavouriteOrder?.let { rows += it to o.id } }
        return rows.sortedWith(compareBy<Pair<Int, String>> { it.first }.thenBy { it.second }).map { it.second }
    }

    private fun validPin(catalog: MetricCatalogData, key: String, known: Set<String>): Boolean =
        if (key.startsWith("app:")) key in catalog.metricByKey else key in known

    private fun parsePins(catalog: MetricCatalogData, raw: String, known: Set<String>, max: Int): MutableList<String> {
        val out = ArrayList<String>()
        for (part in raw.split(',')) {
            val k = part.trim()
            if (k.isEmpty() || k in out || !validPin(catalog, k, known)) continue
            out += k
        }
        return out.take(max).toMutableList()
    }

    fun favouritePinsMigrate(
        catalog: MetricCatalogData,
        newRaw: String?,
        legacyRaw: String?,
        knownHealthIds: Set<String>,
        max: Int
    ): PinsResult {
        if (newRaw != null) return PinsResult(parsePins(catalog, newRaw, knownHealthIds, max), PinSource.NEW)
        if (legacyRaw != null) {
            if (legacyRaw.isBlank()) return PinsResult(emptyList(), PinSource.MIGRATED)
            val pins = parsePins(catalog, legacyRaw, knownHealthIds, max)
            for (k in defaultFavourites(catalog)) {
                if (pins.size >= max) break
                if (k.startsWith("app:") && k !in pins) pins += k
            }
            return PinsResult(pins, PinSource.MIGRATED)
        }
        val pins = defaultFavourites(catalog).filter { validPin(catalog, it, knownHealthIds) }.take(max)
        return PinsResult(pins, PinSource.DEFAULT)
    }

    fun resolveMetric(catalog: MetricCatalogData, key: String, registry: (String) -> RegistryFacts?): ResolvedMetric {
        if (key.startsWith("app:")) {
            val m = catalog.metricByKey[key] ?: return unknown(catalog)
            val d = catalog.domainById.getValue(m.domain)
            return ResolvedMetric(
                "app", m.domain, d.colourHex, d.colourHexDark, m.aggregation, m.chartKind, m.unit.canonical,
                m.goalSource, m.defaultFavouriteOrder, false, m.iconAndroid, m.iconIos
            )
        }
        val r = registry(key) ?: return unknown(catalog)
        val o = catalog.overrideById[key]
        val domain = o?.domain ?: catalog.categoryDomains.getValue(r.category)
        val d = catalog.domainById.getValue(domain)
        return ResolvedMetric(
            "health", domain, d.colourHex, d.colourHexDark,
            catalog.aggregationMap.getValue(r.aggregation), catalog.chartKindMap.getValue(r.aggregation),
            r.unit, o?.goalSource ?: "none", o?.defaultFavouriteOrder, o?.browseHidden ?: false,
            o?.iconAndroid ?: d.iconAndroid, o?.iconIos ?: d.iconIos
        )
    }

    private fun unknown(catalog: MetricCatalogData): ResolvedMetric {
        val d = catalog.domainById.getValue("other")
        return ResolvedMetric("unknown", "other", d.colourHex, d.colourHexDark, "last", "line", "none", "none", null, false, d.iconAndroid, d.iconIos)
    }

    // -- Charts: axis ticks, drill-down, sleep (docs/charts.md) ---------------------------------

    private val NICE_STEPS = doubleArrayOf(1.0, 2.0, 2.5, 5.0, 10.0)
    private val SLEEP_ASLEEP_STAGES = setOf(1, 3, 4, 5)
    const val SLEEP_MIN_DAY_SPAN_MS = 4 * HOUR_MS
    private const val SLEEP_MIN_RANGE_SPAN_MIN = 240

    fun niceTicks(min: Double, max: Double, count: Int, includeZero: Boolean): NiceTicks {
        var lo = min
        var hi = max
        if (includeZero) {
            lo = minOf(lo, 0.0)
            hi = maxOf(hi, 0.0)
        }
        if (hi <= lo) {
            if (lo == 0.0) hi = 1.0 else { lo -= 1.0; hi += 1.0 }
        }
        val intervals = maxOf(1, count - 1)
        val raw = (hi - lo) / intervals
        val mag = 10.0.pow(floor(log10(raw)))
        var step = 20.0 * mag
        for (s in NICE_STEPS) {
            val cand = s * mag
            if (ceil(hi / cand - 1e-9) - floor(lo / cand + 1e-9) <= intervals) {
                step = cand
                break
            }
        }
        val first = floor(lo / step + 1e-9).toLong()
        val last = ceil(hi / step - 1e-9).toLong()
        val ticks = (first..last).map { round3(it * step)!! }
        return NiceTicks(ticks.first(), ticks.last(), round3(step)!!, ticks)
    }

    fun xTicks(range: MetricRange, anchorMs: Long, zone: ZoneId, weekStart: WeekStart): List<Int> {
        val buckets = bucketBounds(range, anchorMs, zone, weekStart, anchorMs).buckets
        return when (range) {
            MetricRange.D -> {
                val out = ArrayList<Int>()
                val seen = HashSet<Int>()
                buckets.forEachIndexed { i, b ->
                    val h = localHourOf(b.startMs, zone)
                    if (h % 6 == 0 && seen.add(h)) out += i
                }
                out
            }
            MetricRange.W, MetricRange.Y -> buckets.indices.toList()
            MetricRange.M -> buckets.indices.filter { i ->
                val d = LocalDate.parse(buckets[i].label)
                weekStartOf(d, weekStart) == d
            }
            MetricRange.SIX_MONTHS -> {
                val out = ArrayList<Int>()
                var prev = -1
                buckets.forEachIndexed { i, b ->
                    val m = LocalDate.parse(b.label).monthValue
                    if (m != prev) {
                        out += i
                        prev = m
                    }
                }
                out
            }
        }
    }

    fun drillTarget(range: MetricRange, bucketStartMs: Long, hasData: Boolean, metricRanges: Collection<MetricRange>, zone: ZoneId): DrillTarget? {
        if ((range != MetricRange.W && range != MetricRange.M) || !hasData || MetricRange.D !in metricRanges) return null
        val d = localDateOf(bucketStartMs, zone)
        return DrillTarget(MetricRange.D, d, localMidnight(d, zone))
    }

    private fun unionMs(intervals: List<Pair<Long, Long>>): Long {
        var total = 0L
        var curS = 0L
        var curE: Long? = null
        for ((s, e) in intervals.sortedWith(compareBy<Pair<Long, Long>> { it.first }.thenBy { it.second })) {
            if (e <= s) continue
            val ce = curE
            if (ce == null || s > ce) {
                if (ce != null) total += ce - curS
                curS = s
                curE = e
            } else if (e > ce) {
                curE = e
            }
        }
        curE?.let { total += it - curS }
        return total
    }

    private fun pct(part: Long, whole: Long): Int? = if (whole == 0L) null else floor(part * 100.0 / whole + 0.5).toInt()

    private fun localInstant(d: LocalDate, hour: Int, minute: Int, zone: ZoneId): Long =
        ZonedDateTime.of(d, LocalTime.of(hour, minute), zone).toInstant().toEpochMilli()

    fun sleepNightWindow(rows: List<SleepRow>, zone: ZoneId): SleepWindow? {
        val use = rows.filter { it.endMs > it.startMs && it.stage in 0..5 }
        if (use.isEmpty()) return null
        val bed = use.minOf { it.startMs }
        val wake = use.maxOf { it.endMs }
        val bdt = Instant.ofEpochMilli(bed).atZone(zone)
        var start = localInstant(bdt.toLocalDate(), bdt.hour, 0, zone)
        if (start > bed) start -= HOUR_MS
        val wdt = Instant.ofEpochMilli(wake).atZone(zone)
        var end = localInstant(wdt.toLocalDate(), wdt.hour, 0, zone)
        if (end < wake) end += HOUR_MS
        if (end - start < SLEEP_MIN_DAY_SPAN_MS) end = start + SLEEP_MIN_DAY_SPAN_MS
        val span = end - start
        val step = if (span <= 4 * HOUR_MS) HOUR_MS else if (span <= 8 * HOUR_MS) 2 * HOUR_MS else 3 * HOUR_MS
        val ticks = ArrayList<Long>()
        var t = start
        while (t <= end) {
            ticks += t
            t += step
        }
        fun union(pred: (SleepRow) -> Boolean) = unionMs(use.filter(pred).map { it.startMs to it.endMs }) / 1000
        val asleep = union { it.stage in SLEEP_ASLEEP_STAGES }
        val inBed = union { true }
        val stages = SleepStageTotals(
            unspecifiedS = union { it.stage == 1 }, awakeS = union { it.stage == 2 }, coreS = union { it.stage == 3 },
            deepS = union { it.stage == 4 }, remS = union { it.stage == 5 }
        )
        val p = SleepStagePct(pct(stages.unspecifiedS, asleep), pct(stages.coreS, asleep), pct(stages.deepS, asleep), pct(stages.remS, asleep))
        return SleepWindow(bed, wake, start, end, step, ticks, asleep, inBed, stages, p)
    }

    /** Wall-clock minutes from 12:00 on the day before [wakeDay] to [tMs]. */
    fun sleepClockOffset(tMs: Long, wakeDay: LocalDate, zone: ZoneId): Int {
        val local = Instant.ofEpochMilli(tMs).atZone(zone)
        val days = ChronoUnit.DAYS.between(wakeDay.minusDays(1), local.toLocalDate()).toInt()
        return days * 1440 + local.hour * 60 + local.minute - 720
    }

    fun sleepRangeSeries(nights: List<SleepNightSpan>, range: MetricRange, anchorMs: Long, zone: ZoneId, weekStart: WeekStart): SleepRangeSeries {
        require(range != MetricRange.D) { "bad range D" }
        val bounds = bucketBounds(range, anchorMs, zone, weekStart, anchorMs)
        data class Placed(val tMs: Long, val night: SleepNightSpan, val bed: Int, val wake: Int)
        val placed = nights.mapNotNull { n ->
            val d = n.wakeDay.takeIf { DATE_RE.matches(it) }?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return@mapNotNull null
            Placed(localMidnight(d, zone), n, sleepClockOffset(n.bedtimeMs, d, zone), sleepClockOffset(n.wakeMs, d, zone))
        }
        fun mean(xs: List<Double>): Double? = if (xs.isEmpty()) null else round3(sum(xs) / xs.size)
        val every = ArrayList<Placed>()
        val out = bounds.buckets.map { b ->
            val inside = placed.filter { b.startMs <= it.tMs && it.tMs < b.endMs }
            every += inside
            SleepRangeBucket(
                b.startMs, b.endMs, inside.size,
                mean(inside.map { it.bed.toDouble() }), mean(inside.map { it.wake.toDouble() }),
                mean(inside.map { it.night.asleepS }), mean(inside.map { it.night.inBedS })
            )
        }
        val filled = out.filter { it.count > 0 }
        val domain = if (filled.isEmpty()) null else {
            val lo = floor(filled.minOf { it.bedOffsetMin!! } / 60.0).toInt() * 60
            var hi = ceil(filled.maxOf { it.wakeOffsetMin!! } / 60.0).toInt() * 60
            if (hi - lo < SLEEP_MIN_RANGE_SPAN_MIN) hi = lo + SLEEP_MIN_RANGE_SPAN_MIN
            SleepRangeDomain(lo, hi, (lo..hi step 120).toList())
        }
        val head = SleepRangeHeadline(
            every.size, mean(every.map { it.night.asleepS }), mean(every.map { it.bed.toDouble() }), mean(every.map { it.wake.toDouble() })
        )
        return SleepRangeSeries(out, domain, head)
    }

    private val DATE_RE = Regex("^[0-9]{4}-[0-9]{2}-[0-9]{2}$")
}
