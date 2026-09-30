package com.ayuvo.health.data.derived

import com.ayuvo.health.data.derived.DerivedMath.ASLEEP
import com.ayuvo.health.data.derived.DerivedMath.MINUTE
import com.ayuvo.health.data.derived.DerivedMath.mean
import com.ayuvo.health.data.derived.DerivedMath.roundTo
import com.ayuvo.health.data.derived.DerivedMath.sampleSd
import com.ayuvo.health.data.derived.DerivedMath.totalMs
import com.ayuvo.health.data.derived.DerivedMath.union
import com.ayuvo.health.medications.logic.MedicationJson
import kotlinx.serialization.json.JsonObject
import java.time.LocalDate
import java.time.ZoneId
import java.util.TreeMap
import kotlin.math.abs

/**
 * One sleep row. Codes as shared/health/metric_registry.json "sleep": 0 in bed, 1 asleep, 2 awake,
 * 3 light/core, 4 deep, 5 REM, 6 out of bed. [source] is only read by [SleepDerivation.sleepNights].
 */
data class SleepRow(val startMs: Long, val endMs: Long, val code: Int, val source: String? = null) {
    val span: Span get() = Span(startMs, endMs)
    fun toJsonRow(): List<Long> = listOf(startMs, endMs, code.toLong())
}

data class SleepNightsInput(val zone: ZoneId, val rows: List<SleepRow>)

data class SleepNightChoice(val source: String, val rows: List<SleepRow>, val window: Span?) {
    fun toJson(): JsonObject = MedicationJson.obj(
        "source" to source, "rows" to rows.map { it.toJsonRow() },
        "window" to window?.let { MedicationJson.obj("start_ms" to it.startMs, "end_ms" to it.endMs) }
    )
}

/** Wake day → chosen source's night, in ascending day order. */
data class SleepNightsResult(val nights: Map<LocalDate, SleepNightChoice>) {
    fun toJson(): JsonObject = JsonObject(nights.entries.associate { it.key.toString() to it.value.toJson() })
}

data class SleepNightInput(val zone: ZoneId, val wakeDay: LocalDate, val rows: List<SleepRow>)

data class SleepNightResult(
    val asleepMin: Double? = null,
    val inBedMin: Double? = null,
    val efficiency: Double? = null,
    val onsetLatencyMin: Double? = null,
    val deepPct: Double? = null,
    val remPct: Double? = null,
    val lightPct: Double? = null,
    val wakeups: Int? = null,
    val wasoMin: Double? = null,
    val bedtimeClock: Int? = null,
    val wakeClock: Int? = null,
    val midpointClock: Int? = null,
    val deepLatencyMin: Double? = null,
    val remLatencyMin: Double? = null,
    val staged: Boolean = false
) {
    fun toJson(): JsonObject = MedicationJson.obj(
        "asleep_min" to asleepMin, "in_bed_min" to inBedMin, "efficiency" to efficiency, "onset_latency_min" to onsetLatencyMin,
        "deep_pct" to deepPct, "rem_pct" to remPct, "light_pct" to lightPct, "wakeups" to wakeups, "waso_min" to wasoMin,
        "bedtime_clock" to bedtimeClock, "wake_clock" to wakeClock, "midpoint_clock" to midpointClock,
        "deep_latency_min" to deepLatencyMin, "rem_latency_min" to remLatencyMin, "staged" to staged
    )
}

data class SleepRegularityInput(
    val zone: ZoneId,
    val day: LocalDate,
    /** Null (or 0) → config `sleep_need_min`. */
    val needMin: Double?,
    /** Wake day → that night's rows; days without a night are absent. */
    val nights: Map<LocalDate, List<SleepRow>>
)

data class SleepRegularityResult(
    val nights: Int,
    val bedtimeSd: Double?,
    val wakeSd: Double?,
    val sri: Double?,
    val sriPairs: Int,
    val socialJetlagMin: Double?,
    val msfscClock: Double?,
    val sleepDebtMin: Double?
) {
    fun toJson(): JsonObject = MedicationJson.obj(
        "nights" to nights, "bedtime_sd" to bedtimeSd, "wake_sd" to wakeSd, "sri" to sri, "sri_pairs" to sriPairs,
        "social_jetlag_min" to socialJetlagMin, "msfsc_clock" to msfscClock, "sleep_debt_min" to sleepDebtMin
    )
}

/** Sleep derivations: ported from `sleep_nights`, `sleep_night` and `sleep_regularity`. */
object SleepDerivation {

    /**
     * Night grouping (docs/health-data.md §1.3): rows sorted by (start, input order) chain into episodes
     * while each next row starts at most `episode_gap_hours` after the episode's latest end; an episode
     * belongs to the local day of its latest-ending row (the first such row on ties, like Python `max`).
     * Per wake day the source with the most unioned asleep time wins (ties: more rows, then the smaller
     * source id by code point); out-of-bed rows are ignored.
     */
    fun sleepNights(inp: SleepNightsInput, cfg: DerivedConfig): SleepNightsResult {
        val tz = inp.zone
        val gap = cfg.thresholds.episodeGapHours * 3600000
        val rows = inp.rows.filter { it.endMs >= it.startMs }
        val order = rows.indices.sortedWith(compareBy({ rows[it].startMs }, { it }))
        val groups = TreeMap<LocalDate, MutableList<SleepRow>>()
        val episode = ArrayList<SleepRow>()
        var end = 0L

        fun flush() {
            if (episode.isEmpty()) return
            var last = episode[0]
            for (r in episode) if (r.endMs > last.endMs) last = r
            groups.getOrPut(DerivedMath.localDayOf(last.endMs, tz)) { ArrayList() }.addAll(episode)
        }

        for (i in order) {
            val r = rows[i]
            if (episode.isNotEmpty() && r.startMs > end + gap) {
                flush()
                episode.clear()
            }
            if (episode.isEmpty()) end = r.endMs
            episode += r
            end = maxOf(end, r.endMs)
        }
        flush()

        val out = LinkedHashMap<LocalDate, SleepNightChoice>()
        for ((day, group) in groups) {
            val by = LinkedHashMap<String, MutableList<SleepRow>>()
            for (r in group) if (r.code != 6) by.getOrPut(r.source ?: error("sleep row without source")) { ArrayList() } += r
            if (by.isEmpty()) continue
            val asleepTotal = by.mapValues { (_, rs) -> totalMs(union(rs.filter { it.code in ASLEEP }.map { it.span })) }
            val ranked = by.keys.sortedWith(
                compareByDescending<String> { asleepTotal.getValue(it) }
                    .thenByDescending { by.getValue(it).size }
                    .then(DerivedMath.CODE_POINT_ORDER)
            )
            val src = ranked[0]
            val chosen = by.getValue(src).sortedWith(compareBy({ it.startMs }, { it.endMs }, { it.code }))
            val asleep = union(chosen.filter { it.code in ASLEEP }.map { it.span })
            out[day] = SleepNightChoice(
                src, chosen.map { SleepRow(it.startMs, it.endMs, it.code) },
                if (asleep.isNotEmpty()) Span(asleep.first().startMs, asleep.last().endMs) else null
            )
        }
        return SleepNightsResult(out)
    }

    fun sleepNight(inp: SleepNightInput, cfg: DerivedConfig): SleepNightResult {
        val th = cfg.thresholds
        val tz = inp.zone
        val wd = inp.wakeDay
        val rows = inp.rows.filter { it.code != 6 && it.endMs > it.startMs }
        val asleep = union(rows.filter { it.code in ASLEEP }.map { it.span })
        if (asleep.isEmpty()) return SleepNightResult()
        val asleepMs = totalMs(asleep)
        val bedRows = rows.filter { it.code == 0 }.map { it.span }
        val firstRow = rows.minOf { it.startMs }
        var inBed = if (bedRows.isNotEmpty()) totalMs(union(bedRows)) else totalMs(union(rows.map { it.span }))
        if (inBed < asleepMs) inBed = rows.maxOf { it.endMs } - firstRow
        val onset = asleep.first().startMs
        val final = asleep.last().endMs

        var onsetLatencyMin: Double? = null
        if (bedRows.isNotEmpty()) {
            val bedStart = bedRows.minOf { it.startMs }
            onsetLatencyMin = roundTo(maxOf(0L, onset - bedStart) / 60000.0, 1)
        }
        val staged = rows.filter { it.code == 3 || it.code == 4 || it.code == 5 }
        var deepPct: Double? = null
        var remPct: Double? = null
        var lightPct: Double? = null
        var deepLatency: Double? = null
        var remLatency: Double? = null
        if (staged.isNotEmpty()) {
            fun stage(code: Int) = totalMs(union(rows.filter { it.code == code }.map { it.span }))
            val deep = stage(4)
            val rem = stage(5)
            val light = stage(3)
            deepPct = roundTo(deep * 100.0 / asleepMs, 1)
            remPct = roundTo(rem * 100.0 / asleepMs, 1)
            lightPct = roundTo(light * 100.0 / asleepMs, 1)
            // Python walks sorted(staged) and keeps the first start per code: the earliest start.
            val firsts = HashMap<Int, Long>()
            for (r in staged.sortedWith(compareBy({ it.startMs }, { it.endMs }, { it.code }))) if (r.code !in firsts) firsts[r.code] = r.startMs
            firsts[4]?.let { deepLatency = roundTo((it - onset) / 60000.0, 1) }
            firsts[5]?.let { remLatency = roundTo((it - onset) / 60000.0, 1) }
        }
        var wakeups = 0
        var waso = 0L
        for (i in 0 until asleep.size - 1) {
            val gap = asleep[i + 1].startMs - asleep[i].endMs
            waso += gap
            if (gap >= th.wakeupMinGapMin * MINUTE) wakeups += 1
        }
        return SleepNightResult(
            asleepMin = roundTo(asleepMs / 60000.0, 1),
            inBedMin = roundTo(inBed / 60000.0, 1),
            efficiency = roundTo(minOf(100.0, asleepMs * 100.0 / inBed), 1),
            onsetLatencyMin = onsetLatencyMin,
            deepPct = deepPct,
            remPct = remPct,
            lightPct = lightPct,
            wakeups = wakeups,
            wasoMin = roundTo(waso / 60000.0, 1),
            bedtimeClock = DerivedMath.clockMin(firstRow, wd, tz),
            wakeClock = DerivedMath.clockMin(final, wd, tz),
            midpointClock = DerivedMath.clockMin(onset + Math.floorDiv(final - onset, 2L), wd, tz),
            deepLatencyMin = deepLatency,
            remLatencyMin = remLatency,
            staged = staged.isNotEmpty()
        )
    }

    private class NightInfo(val asleep: List<Span>, val asleepMin: Double, val bed: Int, val wake: Int, val mid: Int)

    fun sleepRegularity(inp: SleepRegularityInput, cfg: DerivedConfig): SleepRegularityResult {
        val th = cfg.thresholds
        val tz = inp.zone
        val day = inp.day
        val need = inp.needMin?.takeIf { it != 0.0 } ?: th.sleepNeedMin
        val win = (th.regularityWindowDays - 1 downTo 0).map { DerivedMath.addDays(day, -it) }
        val nights = HashMap<LocalDate, NightInfo>()
        for (d in win) {
            val n = inp.nights[d] ?: continue
            val rows = n.filter { it.code != 6 && it.endMs > it.startMs }
            val asleep = union(rows.filter { it.code in ASLEEP }.map { it.span })
            if (asleep.isEmpty()) continue
            val first = rows.minOf { it.startMs }
            val onset = asleep.first().startMs
            val final = asleep.last().endMs
            nights[d] = NightInfo(
                asleep, totalMs(asleep) / 60000.0, DerivedMath.clockMin(first, d, tz), DerivedMath.clockMin(final, d, tz),
                DerivedMath.clockMin(onset + Math.floorDiv(final - onset, 2L), d, tz)
            )
        }
        val days = win.filter { it in nights }
        var bedtimeSd: Double? = null
        var wakeSd: Double? = null
        if (days.size >= th.regularityMinNights) {
            val beds = days.map { nights.getValue(it).bed.toDouble() }
            val wakes = days.map { nights.getValue(it).wake.toDouble() }
            bedtimeSd = roundTo(sampleSd(beds, mean(beds)), 1)
            wakeSd = roundTo(sampleSd(wakes, mean(wakes)), 1)
        }
        // SRI over consecutive noon-to-noon windows
        var agree = 0L
        var total = 0L
        var pairs = 0
        for (i in 0 until win.size - 1) {
            val a = nights[win[i]] ?: continue
            val b = nights[win[i + 1]] ?: continue
            pairs += 1
            val sa = DerivedMath.dayStartMs(DerivedMath.addDays(win[i], -1), tz) + 12 * DerivedMath.HOUR
            val sb = DerivedMath.dayStartMs(DerivedMath.addDays(win[i + 1], -1), tz) + 12 * DerivedMath.HOUR
            for (m in 0 until 1440) {
                val ta = sa + m * MINUTE
                val tb = sb + m * MINUTE
                val xa = a.asleep.any { it.startMs <= ta && ta < it.endMs }
                val xb = b.asleep.any { it.startMs <= tb && tb < it.endMs }
                if (xa == xb) agree += 1
                total += 1
            }
        }
        val sri = if (pairs >= th.sriMinPairs) roundTo(200.0 * agree / total - 100.0, 1) else null
        val free = days.filter { DerivedMath.isoWeekday(it) in th.freeWakeWeekdays }
        val work = days.filter { DerivedMath.isoWeekday(it) !in th.freeWakeWeekdays }
        var socialJetlag: Double? = null
        var msfsc: Double? = null
        if (free.size >= th.socialMinFree && work.size >= th.socialMinWork) {
            val mf = mean(free.map { nights.getValue(it).mid.toDouble() })
            val mw = mean(work.map { nights.getValue(it).mid.toDouble() })
            socialJetlag = roundTo(abs(mf - mw), 1)
            val sdf = mean(free.map { nights.getValue(it).asleepMin })
            val sdw = mean(work.map { nights.getValue(it).asleepMin })
            var msf = mf
            if (sdf > sdw) {
                val sdweek = (5.0 * sdw + 2.0 * sdf) / 7.0
                msf = mf - (sdf - sdweek) / 2.0
            }
            msfsc = roundTo(msf, 1)
        }
        var debtMin: Double? = null
        if (days.isNotEmpty()) {
            var debt = 0.0
            for (d in days) debt += maxOf(0.0, need - nights.getValue(d).asleepMin)
            debtMin = roundTo(debt, 1)
        }
        return SleepRegularityResult(
            nights = nights.size, bedtimeSd = bedtimeSd, wakeSd = wakeSd, sri = sri, sriPairs = pairs,
            socialJetlagMin = socialJetlag, msfscClock = msfsc, sleepDebtMin = debtMin
        )
    }
}
