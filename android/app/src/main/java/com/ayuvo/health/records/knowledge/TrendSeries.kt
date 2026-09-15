package com.ayuvo.health.records.knowledge

import com.ayuvo.health.records.analytes.AnalyteCatalog
import com.ayuvo.health.records.model.FieldState
import com.ayuvo.health.records.model.Observation
import com.ayuvo.health.records.model.ResultFlag
import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.pow

/** §21 trends, ported from the reference `trend_series`, `format_value` and `mini_trend`. Pure. */
object TrendSeries {

    data class Point(
        val date: String,
        val value: Double,
        val valueText: String?,
        val unit: String?,
        val flag: String?,
        val refLow: Double?,
        val refHigh: Double?,
        val recordIds: List<String>,
        val observationIds: List<String>
    ) {
        val resultFlag: ResultFlag get() = ResultFlag.fromRaw(flag)
    }

    data class Band(val low: Double?, val high: Double?)

    data class Series(val unit: String?, val convertible: Boolean, val points: List<Point>, val band: Band?)

    data class Trend(val analyteId: String, val displayName: String?, val series: List<Series>, val skippedIds: List<String>) {
        val main: Series? get() = series.firstOrNull()
    }

    fun build(analyteId: String, observations: List<Observation>, catalog: AnalyteCatalog): Trend {
        val e = catalog.analyte(analyteId)
        val rows = observations.filter { it.analyteId == analyteId && it.state != FieldState.REJECTED && !it.excludedFromTrends && !it.observedDate.isNullOrEmpty() }
            .sortedWith(compareBy<Observation>({ it.observedDate }, { it.createdMs }, { it.id }))
        data class MutableSeries(val key: Pair<Boolean, String?>, val points: MutableList<Point> = mutableListOf())
        val series = mutableListOf<MutableSeries>()
        val skipped = mutableListOf<String>()
        for (o in rows) {
            val (key, value) = when {
                o.canonicalValue != null -> (true to o.canonicalUnit) to o.canonicalValue
                o.valueNum != null -> (false to o.unit) to o.valueNum
                else -> { skipped += o.id; continue }
            }
            val s = series.firstOrNull { it.key == key } ?: MutableSeries(key).also { series += it }
            var lo = o.refLow
            var hi = o.refHigh
            if (key.first) {
                lo = lo?.let { catalog.convert(analyteId, it, o.unit).value }
                hi = hi?.let { catalog.convert(analyteId, it, o.unit).value }
            }
            val idx = s.points.indexOfFirst { it.date == o.observedDate && it.value == value }
            if (idx < 0) {
                s.points += Point(o.observedDate!!, value, o.valueText, o.unit, o.flag.raw, lo, hi, listOf(o.recordId), listOf(o.id))
            } else {
                val p = s.points[idx]
                s.points[idx] = p.copy(
                    recordIds = if (o.recordId in p.recordIds) p.recordIds else p.recordIds + o.recordId,
                    observationIds = p.observationIds + o.id
                )
            }
        }
        val built = series.map { s ->
            val band = s.points.lastOrNull { it.refLow != null || it.refHigh != null }?.let { Band(it.refLow, it.refHigh) }
            Series(s.key.second, s.key.first, s.points.toList(), band)
        }
        return Trend(analyteId, e?.displayName, built.filter { it.convertible } + built.filter { !it.convertible }, skipped)
    }

    /** Half-up on the magnitude to [d] decimals (sign kept). */
    fun roundDec(x: Double, d: Int): Double {
        val r = floor(abs(x) * 10.0.pow(d) + 0.5 + 1e-9) / 10.0.pow(d)
        return if (x < 0 && r != 0.0) -r else r
    }

    fun format(x: Double, d: Int): String {
        val r = roundDec(x, d)
        return (if (r < 0) "-" else "") + String.format(Locale.US, "%.${d}f", abs(r))
    }

    data class Mini(
        val show: Boolean,
        val values: List<String>,
        val text: String?,
        val unit: String?,
        val delta: Double?,
        val sinceDate: String?,
        val changeText: String?
    )

    private val NONE = Mini(false, emptyList(), null, null, null, null, null)

    /** Reference `mini_trend` for one observation (the trend as of that report). */
    fun mini(trend: Trend, observationId: String, catalog: AnalyteCatalog): Mini {
        val d = catalog.analyte(trend.analyteId)?.decimals ?: 2
        for (s in trend.series) {
            for ((idx, p) in s.points.withIndex()) {
                if (observationId !in p.observationIds) continue
                val pts = s.points.subList(0, idx + 1)
                if (pts.size < 2) return NONE
                val vals = pts.takeLast(5).map { format(it.value, d) }
                val prev = pts[pts.size - 2]
                val delta = roundDec(p.value - prev.value, d)
                val sign = if (delta > 0) "+" else if (delta < 0) "−" else ""
                var text = sign + String.format(Locale.US, "%.${d}f", abs(delta))
                if (!s.unit.isNullOrEmpty()) text += " " + s.unit
                return Mini(true, vals, vals.joinToString(" → "), s.unit, delta, prev.date, "$text since ${prev.date}")
            }
        }
        return NONE
    }
}
