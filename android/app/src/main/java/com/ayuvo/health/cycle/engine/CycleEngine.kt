package com.ayuvo.health.cycle.engine

import kotlin.math.floor
import kotlin.math.sqrt

// Port of scripts/cycle_reference.py (docs/cycle-tracking.md §3). Every function must produce exactly what the
// reference produces for shared/cycle/test-vectors; change the reference first, then this file.

/** Local calendar days 'yyyy-MM-dd' <-> days since 1970-01-01 (Howard Hinnant's civil algorithms). */
object CycleDays {
    fun ordinal(text: String): Int {
        var y = text.substring(0, 4).toInt()
        val m = text.substring(5, 7).toInt()
        val d = text.substring(8, 10).toInt()
        if (m <= 2) y -= 1
        val era = Math.floorDiv(y, 400)
        val yoe = y - era * 400
        val mp = if (m > 2) m - 3 else m + 9
        val doy = (153 * mp + 2) / 5 + d - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return era * 146097 + doe - 719468
    }

    fun string(n: Int): String {
        val z = n + 719468
        val era = Math.floorDiv(z, 146097)
        val doe = z - era * 146097
        val yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365
        var y = yoe + era * 400
        val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
        val mp = (5 * doy + 2) / 153
        val d = doy - (153 * mp + 2) / 5 + 1
        val m = if (mp < 10) mp + 3 else mp - 9
        if (m <= 2) y += 1
        return "%04d-%02d-%02d".format(y, m, d)
    }

    fun optString(n: Int?): String? = n?.let(::string)
}

// ---------------------------------------------------------------------------------------------
// Inputs
// ---------------------------------------------------------------------------------------------

/** Raw reminder preferences; null = default. */
data class CycleReminderInput(
    val periodSoon: Boolean? = null,
    val daysBefore: Int? = null,
    val periodEnd: Boolean? = null,
    val daily: Boolean? = null
)

/** Raw user settings; any null falls back to the configured default. */
data class CycleSettingsInput(
    val cycleLength: Int? = null,
    val periodLength: Int? = null,
    val lutealLength: Int? = null,
    val reminders: CycleReminderInput? = null
)

/** One period. `source` is "app", "healthkit" or "health_connect"; `end` null = ongoing. */
data class CyclePeriodInput(val id: String, val start: String, val end: String?, val source: String = "app")

data class CycleDayLogInput(
    val day: String,
    val flow: String? = null,
    val pain: Int? = null,
    val painLocations: List<String> = emptyList(),
    val symptoms: List<String> = emptyList(),
    val moods: List<String> = emptyList()
)

data class CycleState(
    val today: String,
    val settings: CycleSettingsInput? = null,
    val periods: List<CyclePeriodInput> = emptyList(),
    val logs: List<CycleDayLogInput> = emptyList()
)

// ---------------------------------------------------------------------------------------------
// Outputs
// ---------------------------------------------------------------------------------------------

data class CycleReminderSettings(val periodSoon: Boolean, val daysBefore: Int, val periodEnd: Boolean, val daily: Boolean)

data class CycleEffectiveSettings(
    val cycleLength: Int, val periodLength: Int, val lutealLength: Int, val reminders: CycleReminderSettings
)

data class CycleNormalizedPeriod(
    val id: String, val start: String, val end: String?, val ongoing: Boolean, val autoClosed: Boolean,
    val source: String, val members: List<String>, val length: Int?, val valid: Boolean
)

data class CycleDropped(val id: String, val reason: String)

data class CycleNormalized(val periods: List<CycleNormalizedPeriod>, val dropped: List<CycleDropped>)

data class CycleRow(
    val start: String, val cycleLength: Int?, val periodLength: Int?, val cycleValid: Boolean, val periodValid: Boolean
)

data class CycleStats(
    val cycleCount: Int, val cycleLengths: List<Int>, val cycleMedian: Double?, val cycleMean: Double?,
    val cycleSd: Double?, val cycleRange: List<Int>?, val variability: String, val periodCount: Int,
    val periodMedian: Double?
)

data class CycleWindow(
    val cycleStart: String, val predictedStart: Boolean, val ovulation: String?, val fertile: List<String>?,
    val periodEnd: String
)

data class CyclePrediction(
    val basis: String, val cycleLength: Int?, val cycleRange: List<Int>?, val periodLength: Int?,
    val lutealLength: Int, val nextStart: String?, val nextRange: List<String>?, val rawNextStart: String?,
    val lateDays: Int, val ongoing: Boolean, val expectedEnd: String?, val currentCycleDay: Int?,
    val windows: List<CycleWindow>
)

/** `phase` is one of the config `phases` keys. */
data class CycleDayStatus(val day: String, val cycleDay: Int?, val phase: String, val periodDay: Int?, val estimated: Boolean)

data class CycleInsight(val key: String, val params: Map<String, Int>)

/** `kind` = period_soon | period_end | daily_log; `day` null for daily_log. */
data class CycleReminder(val kind: String, val day: String?)

data class CycleSnapshot(
    val periods: List<CycleNormalizedPeriod>, val stats: CycleStats, val prediction: CyclePrediction,
    val today: CycleDayStatus, val insights: List<CycleInsight>, val reminders: List<CycleReminder>
)

data class CycleTrendCycle(
    val start: String, val cycleLength: Int?, val periodLength: Int?, val painMax: Int?, val painMean: Double?,
    val symptomDays: Map<String, Int>, val moodDays: Map<String, Int>, val flow: List<String?>
)

data class CycleFrequency(val key: String, val cycles: Int)

data class CycleTrends(
    val cycles: List<CycleTrendCycle>, val symptomFrequency: List<CycleFrequency>,
    val moodFrequency: List<CycleFrequency>, val flowPattern: List<Double>, val windowCycles: Int
)

data class CyclePeriodValidation(
    val ok: Boolean, val errors: List<String>, val overlaps: List<String>, val merged: List<String?>?, val duration: Int?
)

/** `op` = insert | update | delete; insert has no id; delete has no start/end. */
data class CyclePeriodOp(val op: String, val id: String?, val start: String?, val end: String?)

data class CyclePeriodDayResult(val ops: List<CyclePeriodOp>, val error: String?)

// ---------------------------------------------------------------------------------------------
// Engine
// ---------------------------------------------------------------------------------------------

class CycleEngine(val config: CycleConfig) {

    // --- helpers -----------------------------------------------------------------------------

    private fun clamp(x: Int, lo: Int, hi: Int): Int = if (x < lo) lo else if (x > hi) hi else x

    private fun median(values: List<Int>): Double {
        val s = values.sorted()
        val n = s.size
        return if (n % 2 == 1) s[n / 2].toDouble() else (s[n / 2 - 1] + s[n / 2]) / 2.0
    }

    private fun sampleSd(values: List<Int>): Double? {
        val n = values.size
        if (n < 2) return null
        var t = 0.0
        for (v in values) t += v
        val m = t / n
        var acc = 0.0
        for (v in values) acc += (v - m) * (v - m)
        return sqrt(acc / (n - 1))
    }

    // --- settings ----------------------------------------------------------------------------

    fun effectiveSettings(settings: CycleSettingsInput?): CycleEffectiveSettings {
        val s = settings ?: CycleSettingsInput()
        val d = config.defaults
        val lim = config.limits
        val cycle = clamp(s.cycleLength ?: d.cycleLength, lim.settingCycleMin, lim.settingCycleMax)
        var period = clamp(s.periodLength ?: d.periodLength, lim.settingPeriodMin, lim.settingPeriodMax)
        if (period > cycle - 1) period = cycle - 1
        val luteal = clamp(s.lutealLength ?: d.lutealLength, lim.lutealMin, lim.lutealMax)
        val r = s.reminders ?: CycleReminderInput()
        return CycleEffectiveSettings(
            cycle, period, luteal,
            CycleReminderSettings(
                periodSoon = r.periodSoon ?: true,
                daysBefore = clamp(r.daysBefore ?: d.reminderDaysBefore, 1, 3),
                periodEnd = r.periodEnd ?: true,
                daily = r.daily ?: false
            )
        )
    }

    // --- periods and cycles ------------------------------------------------------------------

    private class Item(val id: String, val start: Int, val end: Int?, val source: String)

    private class Merged(val id: String, val start: Int, var end: Int?, var source: String, val members: MutableList<String>)

    internal class Norm(
        val id: String, val start: Int, val end: Int?, val ongoing: Boolean, val autoClosed: Boolean,
        val source: String, val members: List<String>, val length: Int?, val valid: Boolean
    )

    internal class Cyc(val start: Int, val cycleLength: Int?, val periodLength: Int?, val cycleValid: Boolean, val periodValid: Boolean)

    private fun effEnd(end: Int?, today: Int): Int = end ?: today

    internal fun normalizePeriods(
        periods: List<CyclePeriodInput>, today: Int, settings: CycleEffectiveSettings
    ): Pair<List<Norm>, List<CycleDropped>> {
        val lim = config.limits
        val items = mutableListOf<Item>()
        val dropped = mutableListOf<CycleDropped>()
        for (p in periods) {
            val s = CycleDays.ordinal(p.start)
            var e = p.end?.let(CycleDays::ordinal)
            if (s > today) {
                dropped += CycleDropped(p.id, "future"); continue
            }
            if (e != null && e < s) {
                dropped += CycleDropped(p.id, "end_before_start"); continue
            }
            if (e != null && e > today) e = today
            items += Item(p.id, s, e, p.source.ifEmpty { "app" })
        }
        val apps = items.filter { it.source == "app" }
        val kept = mutableListOf<Item>()
        for (it in items) {
            if (it.source != "app") {
                var hit = false
                for (a in apps) {
                    if (it.start <= effEnd(a.end, today) + 1 && a.start <= effEnd(it.end, today) + 1) {
                        hit = true; break
                    }
                }
                if (hit) {
                    dropped += CycleDropped(it.id, "duplicate"); continue
                }
            }
            kept += it
        }
        val sortedKept = kept.sortedWith(
            compareBy<Item>({ it.start }, { if (it.source == "app") 0 else 1 }).thenBy { it.id }
        )
        val merged = mutableListOf<Merged>()
        for (it in sortedKept) {
            val last = merged.lastOrNull()
            if (last != null && it.start <= effEnd(last.end, today) + 1) {
                val lastEnd = last.end
                if (lastEnd == null || it.end == null) last.end = null
                else if (it.end > lastEnd) last.end = it.end
                last.members += it.id
                if (it.source == "app") last.source = "app"
                continue
            }
            merged += Merged(it.id, it.start, it.end, it.source, mutableListOf(it.id))
        }
        val out = mutableListOf<Norm>()
        for (m in merged) {
            var ongoing = false
            var autoClosed = false
            var end = m.end
            if (end == null) {
                if (today - m.start + 1 > lim.periodMax) {
                    end = m.start + settings.periodLength - 1
                    autoClosed = true
                } else {
                    ongoing = true
                }
            }
            val length = if (ongoing) null else end!! - m.start + 1
            out += Norm(
                m.id, m.start, end, ongoing, autoClosed, m.source, m.members.toList(), length,
                length != null && !autoClosed && length >= lim.periodMin && length <= lim.periodMax
            )
        }
        return out to dropped
    }

    private fun buildCycles(norm: List<Norm>): List<Cyc> {
        val lim = config.limits
        return norm.mapIndexed { i, p ->
            val length = if (i + 1 < norm.size) norm[i + 1].start - p.start else null
            Cyc(p.start, length, p.length, length != null && length >= lim.cycleMin && length <= lim.cycleMax, p.valid)
        }
    }

    private fun cycleStats(cycles: List<Cyc>): CycleStats {
        val window = config.prediction.historyWindow
        val lengths = cycles.filter { it.cycleValid }.map { it.cycleLength!! }.takeLast(window)
        val periods = cycles.filter { it.periodValid }.map { it.periodLength!! }.takeLast(window)
        val n = lengths.size
        var median: Double? = null
        var mean: Double? = null
        var sd: Double? = null
        var range: List<Int>? = null
        var variability = "insufficient"
        if (n > 0) {
            val s = lengths.sorted()
            median = CycleMath.roundTo(median(lengths), 1)
            var t = 0.0
            for (v in lengths) t += v
            mean = CycleMath.roundTo(t / n, 1)
            sd = sampleSd(lengths)?.let { CycleMath.roundTo(it, 2) }
            range = if (n >= config.prediction.trimmedRangeMinCycles) listOf(s[1], s[s.size - 2]) else listOf(s[0], s[s.size - 1])
            val v = config.variability
            if (n >= v.minCycles) {
                val raw = sampleSd(lengths)
                val high = s[s.size - 1] - s[0] >= v.highRangeDays || (raw != null && raw > v.highSdDays)
                variability = if (high) "high" else "regular"
            }
        }
        return CycleStats(
            n, lengths, median, mean, sd, range, variability, periods.size,
            if (periods.isNotEmpty()) CycleMath.roundTo(median(periods), 1) else null
        )
    }

    // --- model -------------------------------------------------------------------------------

    internal class Frame(
        val start: Int, val boundary: Int, val rawBoundary: Int, val loggedEnd: Int?, val periodEnd: Int,
        val predicted: Boolean, val ongoing: Boolean, val isLast: Boolean, val estimate: Boolean
    ) {
        var ovulation: Int? = null
        var fertile: IntArray? = null
    }

    internal class Model(
        val today: Int, val settings: CycleEffectiveSettings, val norm: List<Norm>, val dropped: List<CycleDropped>,
        val stats: CycleStats
    ) {
        var basis = "none"
        var length = 0
        var range = IntArray(2)
        var period = 0
        var rawNext = 0
        var eff = 0
        var late = 0
        var nextRange = IntArray(2)
        var frames: List<Frame> = emptyList()
        var cycles: List<Cyc> = emptyList()
    }

    internal fun model(state: CycleState): Model {
        val today = CycleDays.ordinal(state.today)
        val settings = effectiveSettings(state.settings)
        val (norm, dropped) = normalizePeriods(state.periods, today, settings)
        val cycles = buildCycles(norm)
        val stats = cycleStats(cycles)
        val model = Model(today, settings, norm, dropped, stats)
        model.cycles = cycles
        if (norm.isEmpty()) return model
        val pc = config.prediction
        val span = pc.defaultRangeDays
        val n = stats.cycleCount
        val basis: String
        val length: Int
        val lo: Int
        val hi: Int
        if (n == 0) {
            basis = "default"
            length = settings.cycleLength
            lo = length - span; hi = length + span
        } else if (n < pc.historyMinCycles) {
            basis = "limited"
            val vals = stats.cycleLengths + settings.cycleLength
            length = CycleMath.halfUpInt(median(vals))
            val s = vals.sorted()
            lo = minOf(s[0], length - span); hi = maxOf(s[s.size - 1], length + span)
        } else {
            basis = "history"
            length = CycleMath.halfUpInt(stats.cycleMedian!!)
            lo = minOf(stats.cycleRange!![0], length); hi = maxOf(stats.cycleRange[1], length)
        }
        var period = if (stats.periodCount > 0) CycleMath.halfUpInt(stats.periodMedian!!) else settings.periodLength
        if (period > length - 1) period = length - 1
        val last = norm.last()
        val rawNext = last.start + length
        val eff: Int
        val late: Int
        if (rawNext > today) {
            eff = rawNext; late = 0
        } else {
            eff = today + 1; late = today - rawNext
        }
        var rangeLo = eff + (lo - length)
        if (rangeLo < today + 1) rangeLo = today + 1
        var rangeHi = eff + (hi - length)
        if (rangeHi < rangeLo) rangeHi = rangeLo
        val lim = config.limits
        val frames = mutableListOf<Frame>()
        for ((i, p) in norm.withIndex()) {
            val isLast = i + 1 == norm.size
            val boundary = if (isLast) eff else norm[i + 1].start
            val rawBoundary = if (isLast) rawNext else boundary
            val loggedEnd: Int
            var periodEnd: Int
            if (p.ongoing) {
                loggedEnd = today
                periodEnd = p.start + period - 1
                if (periodEnd < today) periodEnd = today
            } else {
                loggedEnd = p.end!!
                periodEnd = p.end
            }
            val gap = boundary - p.start
            val estimate = isLast || (gap >= lim.cycleMin && gap <= lim.cycleMax)
            frames += Frame(p.start, boundary, rawBoundary, loggedEnd, periodEnd, false, p.ongoing, isLast, estimate)
        }
        for (k in 0 until pc.projectCycles) {
            val s = eff + k * length
            frames += Frame(s, s + length, s + length, null, s + period - 1, true, false, false, true)
        }
        for (f in frames) {
            if (f.estimate) ovulation(f, settings.lutealLength)
        }
        model.basis = basis
        model.length = length
        model.range = intArrayOf(lo, hi)
        model.period = period
        model.rawNext = rawNext
        model.eff = eff
        model.late = late
        model.nextRange = intArrayOf(rangeLo, rangeHi)
        model.frames = frames
        return model
    }

    private fun ovulation(frame: Frame, luteal: Int) {
        val rb = frame.rawBoundary
        val pend = frame.periodEnd
        var ov = rb - luteal
        if (ov < pend + 1) ov = pend + 1
        if (ov > rb - 2) ov = rb - 2
        if (ov <= pend) return
        val fc = config.fertile
        var lo = ov - fc.daysBeforeOvulation
        if (lo < pend + 1) lo = pend + 1
        var hi = ov + fc.daysAfterOvulation
        if (hi > rb - 1) hi = rb - 1
        frame.ovulation = ov
        frame.fertile = intArrayOf(lo, hi)
    }

    private fun status(model: Model, day: Int): CycleDayStatus {
        val frames = model.frames
        if (frames.isEmpty() || day < frames.first().start || day >= frames.last().boundary) {
            return CycleDayStatus(CycleDays.string(day), null, "unknown", null, false)
        }
        val f = frames.first { it.start <= day && day < it.boundary }
        val cycleDay = day - f.start + 1
        var periodDay: Int? = null
        var estimated = true
        val fertile = f.fertile
        val phase = if (!f.predicted && day <= f.loggedEnd!!) {
            periodDay = cycleDay; estimated = false; "period"
        } else if (day <= f.periodEnd) {
            periodDay = cycleDay; "predicted_period"
        } else if (f.isLast && !f.ongoing && model.rawNext <= day && day <= model.today) {
            "late"
        } else if (f.ovulation == null || fertile == null) {
            "unknown"
        } else if (day == f.ovulation) {
            "ovulation"
        } else if (fertile[0] <= day && day <= fertile[1]) {
            "fertile"
        } else if (day < fertile[0]) {
            "follicular"
        } else {
            "luteal"
        }
        return CycleDayStatus(CycleDays.string(day), cycleDay, phase, periodDay, estimated)
    }

    private fun prediction(model: Model): CyclePrediction {
        if (model.basis == "none") {
            return CyclePrediction(
                "none", null, null, null, model.settings.lutealLength, null, null, null, 0, false, null, null, emptyList()
            )
        }
        val last = model.norm.last()
        val windows = model.frames.filter { it.isLast || it.predicted }.map { f ->
            CycleWindow(
                CycleDays.string(f.start), f.predicted, CycleDays.optString(f.ovulation),
                f.fertile?.let { listOf(CycleDays.string(it[0]), CycleDays.string(it[1])) },
                CycleDays.string(f.periodEnd)
            )
        }
        return CyclePrediction(
            basis = model.basis, cycleLength = model.length, cycleRange = model.range.toList(),
            periodLength = model.period, lutealLength = model.settings.lutealLength,
            nextStart = CycleDays.string(model.eff),
            nextRange = listOf(CycleDays.string(model.nextRange[0]), CycleDays.string(model.nextRange[1])),
            rawNextStart = CycleDays.string(model.rawNext), lateDays = model.late, ongoing = last.ongoing,
            expectedEnd = if (last.ongoing) CycleDays.string(last.start + model.period - 1) else null,
            currentCycleDay = model.today - last.start + 1, windows = windows
        )
    }

    // --- trends, insights, reminders ---------------------------------------------------------

    private fun trends(model: Model, logs: List<CycleDayLogInput>): CycleTrends {
        val byDay = HashMap<Int, CycleDayLogInput>()
        for (lg in logs) byDay[CycleDays.ordinal(lg.day)] = lg
        val days = byDay.keys.sorted()
        val ranks = HashMap<String, Int>()
        for (fl in config.flowLevels) if (fl.period) ranks[fl.key] = fl.rank
        val window = config.prediction.historyWindow
        val cyclesOut = mutableListOf<CycleTrendCycle>()
        for (f in model.frames) {
            if (f.predicted) continue
            val end = if (f.isLast) model.today + 1 else f.boundary
            val pains = mutableListOf<Int>()
            val symptoms = LinkedHashMap<String, Int>()
            val moods = LinkedHashMap<String, Int>()
            for (d in days) {
                if (d < f.start || d >= end) continue
                val lg = byDay.getValue(d)
                lg.pain?.let { pains += it }
                for (s in lg.symptoms) symptoms[s] = (symptoms[s] ?: 0) + 1
                for (m in lg.moods) moods[m] = (moods[m] ?: 0) + 1
            }
            val plen = f.loggedEnd!! - f.start + 1
            val flow = mutableListOf<String?>()
            for (k in 0 until minOf(plen, 10)) {
                val fk = byDay[f.start + k]?.flow
                flow += if (fk != null && fk in ranks) fk else null
            }
            var painMax: Int? = null
            for (v in pains) if (painMax == null || v > painMax) painMax = v
            var painTotal = 0.0
            for (v in pains) painTotal += v
            cyclesOut += CycleTrendCycle(
                start = CycleDays.string(f.start),
                cycleLength = if (f.isLast) null else f.boundary - f.start,
                periodLength = if (f.ongoing) null else plen,
                painMax = painMax,
                painMean = if (pains.isNotEmpty()) CycleMath.roundTo(painTotal / pains.size, 1) else null,
                symptomDays = symptoms, moodDays = moods, flow = flow
            )
        }
        val recent = cyclesOut.takeLast(window)

        fun frequency(catalog: List<CycleConfig.Item>, pick: (CycleTrendCycle) -> Map<String, Int>): List<CycleFrequency> {
            val res = mutableListOf<Triple<Int, Int, CycleFrequency>>()
            for ((index, item) in catalog.withIndex()) {
                var n = 0
                for (c in recent) if ((pick(c)[item.key] ?: 0) > 0) n += 1
                if (n > 0) res += Triple(-n, index, CycleFrequency(item.key, n))
            }
            return res.sortedWith(compareBy({ it.first }, { it.second })).map { it.third }
        }

        val flowPattern = mutableListOf<Double>()
        for (k in 0 until 10) {
            val vals = mutableListOf<Int>()
            for (c in recent) {
                val v = c.flow.getOrNull(k)
                if (v != null) vals += ranks.getValue(v)
            }
            if (vals.isEmpty()) break
            var t = 0.0
            for (v in vals) t += v
            flowPattern += CycleMath.roundTo(t / vals.size, 2)
        }
        return CycleTrends(
            cyclesOut, frequency(config.symptoms) { it.symptomDays }, frequency(config.moods) { it.moodDays },
            flowPattern, recent.size
        )
    }

    private fun insights(model: Model, trends: CycleTrends): List<CycleInsight> {
        val rules = config.insightRules
        val out = mutableListOf<CycleInsight>()
        if (model.basis == "none") return out
        val stats = model.stats
        val lengths = stats.cycleLengths
        val k = rules.recentCycles
        if (lengths.size >= k) {
            val recent = lengths.takeLast(k).sorted()
            out += if (recent.first() == recent.last()) {
                CycleInsight("recent_same", linkedMapOf("count" to k, "low" to recent.first()))
            } else {
                CycleInsight("recent_range", linkedMapOf("count" to k, "low" to recent.first(), "high" to recent.last()))
            }
        } else {
            out += CycleInsight("few_cycles", emptyMap())
        }
        if (stats.variability == "high") out += CycleInsight("variable", emptyMap())
        if (model.late >= rules.lateDays) out += CycleInsight("late", linkedMapOf("days" to model.late))
        val lastK = lengths.takeLast(k)
        if (lastK.size >= rules.outOfRangeMinCount) {
            var nOut = 0
            for (v in lastK) if (v < rules.typicalCycleLow || v > rules.typicalCycleHigh) nOut += 1
            if (nOut >= rules.outOfRangeMinCount) {
                out += CycleInsight(
                    "out_of_range",
                    linkedMapOf("count" to nOut, "total" to lastK.size, "low" to rules.typicalCycleLow, "high" to rules.typicalCycleHigh)
                )
            }
        }
        var lastPeriod: Norm? = null
        for (p in model.norm) if (!p.ongoing && !p.autoClosed) lastPeriod = p
        if (lastPeriod != null && lastPeriod.length!! >= rules.longPeriodDays) {
            out += CycleInsight("long_period", linkedMapOf("days" to lastPeriod.length))
        }
        val withPain = trends.cycles.mapNotNull { it.painMax }
        if (withPain.size >= 2 && withPain[withPain.size - 1] >= rules.severePain && withPain[withPain.size - 2] >= rules.severePain) {
            out += CycleInsight("severe_pain", emptyMap())
        }
        return out
    }

    private fun reminders(model: Model): List<CycleReminder> {
        val r = model.settings.reminders
        val out = mutableListOf<CycleReminder>()
        if (r.daily) out += CycleReminder("daily_log", null)
        if (model.basis == "none") return out
        val last = model.norm.last()
        if (r.periodSoon && !last.ongoing && model.late == 0 && model.rawNext > model.today) {
            val day = model.eff - r.daysBefore
            if (day >= model.today) out += CycleReminder("period_soon", CycleDays.string(day))
        }
        if (r.periodEnd && last.ongoing) {
            var day = last.start + model.period - 1 + config.prediction.openPeriodExtraDays
            if (day < model.today) day = model.today
            out += CycleReminder("period_end", CycleDays.string(day))
        }
        return out
    }

    // --- public API --------------------------------------------------------------------------

    private fun normOut(norm: List<Norm>): List<CycleNormalizedPeriod> = norm.map { p ->
        CycleNormalizedPeriod(
            p.id, CycleDays.string(p.start), CycleDays.optString(p.end), p.ongoing, p.autoClosed, p.source,
            p.members, p.length, p.valid
        )
    }

    fun normalize(today: String, periods: List<CyclePeriodInput>, settings: CycleSettingsInput? = null): CycleNormalized {
        val (norm, dropped) = normalizePeriods(periods, CycleDays.ordinal(today), effectiveSettings(settings))
        return CycleNormalized(normOut(norm), dropped)
    }

    fun cycles(state: CycleState): Pair<List<CycleRow>, CycleStats> {
        val m = model(state)
        val rows = m.cycles.map {
            CycleRow(CycleDays.string(it.start), it.cycleLength, it.periodLength, it.cycleValid, it.periodValid)
        }
        return rows to m.stats
    }

    /** Everything the dashboard needs (reference `snapshot`). */
    fun snapshot(state: CycleState): CycleSnapshot {
        val m = model(state)
        val t = trends(m, state.logs)
        return CycleSnapshot(normOut(m.norm), m.stats, prediction(m), status(m, m.today), insights(m, t), reminders(m))
    }

    /** Per-day status for every day in [from, to] inclusive (reference `day_status`). */
    fun dayStatus(state: CycleState, from: String, to: String): List<CycleDayStatus> {
        val m = model(state)
        val out = mutableListOf<CycleDayStatus>()
        var d = CycleDays.ordinal(from)
        val last = CycleDays.ordinal(to)
        while (d <= last) {
            out += status(m, d); d += 1
        }
        return out
    }

    fun trends(state: CycleState): CycleTrends = trends(model(state), state.logs)

    fun validatePeriod(
        today: String, candidateId: String?, start: String, end: String?, periods: List<CyclePeriodInput>
    ): CyclePeriodValidation {
        val t = CycleDays.ordinal(today)
        val s = CycleDays.ordinal(start)
        val e = end?.let(CycleDays::ordinal)
        val errors = mutableListOf<String>()
        if (s > t) errors += "future_start"
        if (e != null && e > t) errors += "future_end"
        if (e != null && e < s) errors += "end_before_start"
        val duration = if (e == null || e < s) null else e - s + 1
        if (duration != null && duration > config.limits.periodMax) errors += "too_long"
        val overlaps = mutableListOf<String>()
        var merged: List<String?>? = null
        if (errors.isEmpty() && e == null) {
            for (p in periods) {
                if (p.id != candidateId && p.source == "app" && CycleDays.ordinal(p.start) > s) {
                    errors += "open_not_latest"; break
                }
            }
        }
        if (errors.isEmpty()) {
            val lo = s
            val hi = e ?: t
            var mLo = s
            var mHi = hi
            var openEnd = e == null
            for (p in periods) {
                if (p.id == candidateId || p.source != "app") continue
                val ps = CycleDays.ordinal(p.start)
                val pe = p.end?.let(CycleDays::ordinal) ?: t
                if (ps <= hi + 1 && lo <= pe + 1) {
                    overlaps += p.id
                    if (ps < mLo) mLo = ps
                    if (pe > mHi) mHi = pe
                    if (p.end == null) openEnd = true
                }
            }
            if (overlaps.isNotEmpty()) {
                errors += "overlap"
                merged = listOf(CycleDays.string(mLo), if (openEnd) null else CycleDays.string(mHi))
            }
        }
        overlaps.sort()
        return CyclePeriodValidation(errors.isEmpty(), errors, overlaps, merged, duration)
    }

    private class AppPeriod(val id: String, val start: Int, val end: Int?)

    /** The "period day" toggle for one day (reference `apply_period_day`). Only app periods are touched. */
    fun applyPeriodDay(today: String, day: String, on: Boolean, periods: List<CyclePeriodInput>): CyclePeriodDayResult {
        val t = CycleDays.ordinal(today)
        val d = CycleDays.ordinal(day)
        if (d > t) return CyclePeriodDayResult(emptyList(), "future")
        val apps = periods.filter { it.source == "app" }
            .map { AppPeriod(it.id, CycleDays.ordinal(it.start), it.end?.let(CycleDays::ordinal)) }
            .sortedWith(compareBy<AppPeriod> { it.start }.thenBy { it.id })
        val containing = apps.firstOrNull { it.start <= d && d <= effEnd(it.end, t) }
        val ops = mutableListOf<CyclePeriodOp>()
        if (on) {
            if (containing != null) return CyclePeriodDayResult(emptyList(), null)
            var before: AppPeriod? = null
            var after: AppPeriod? = null
            for (p in apps) {
                if (p.end != null && p.end + 1 == d) before = p
                if (p.start - 1 == d) after = p
            }
            if (before != null && after != null) {
                ops += CyclePeriodOp("update", before.id, CycleDays.string(before.start), CycleDays.optString(after.end))
                ops += CyclePeriodOp("delete", after.id, null, null)
            } else if (before != null) {
                ops += CyclePeriodOp("update", before.id, CycleDays.string(before.start), if (d == t) null else CycleDays.string(d))
            } else if (after != null) {
                ops += CyclePeriodOp("update", after.id, CycleDays.string(d), CycleDays.optString(after.end))
            } else {
                ops += CyclePeriodOp("insert", null, CycleDays.string(d), if (d == t) null else CycleDays.string(d))
            }
            return CyclePeriodDayResult(ops, null)
        }
        val p = containing ?: return CyclePeriodDayResult(emptyList(), null)
        val end = effEnd(p.end, t)
        if (p.start == d && end == d) {
            ops += CyclePeriodOp("delete", p.id, null, null)
        } else if (p.start == d) {
            ops += CyclePeriodOp("update", p.id, CycleDays.string(d + 1), CycleDays.optString(p.end))
        } else {
            ops += CyclePeriodOp("update", p.id, CycleDays.string(p.start), CycleDays.string(d - 1))
        }
        return CyclePeriodDayResult(ops, null)
    }
}

/** Half-up rounding shared with the reference (`round_to`, `round_half_up_int`). */
object CycleMath {
    fun roundTo(x: Double, decimals: Int): Double {
        var scale = 1.0
        repeat(decimals) { scale *= 10.0 }
        val v = floor(x * scale + 0.5) / scale
        return if (v == 0.0) 0.0 else v
    }

    fun halfUpInt(x: Double): Int = floor(x + 0.5).toInt()
}
