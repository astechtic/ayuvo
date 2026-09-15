package com.ayuvo.health.data.health

import com.ayuvo.health.models.HealthAggregation
import com.ayuvo.health.models.HealthDataType
import com.ayuvo.health.models.HealthKind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import java.time.ZoneId

/** Kind/aggregation/unit for a type id — registry entry or `health_type_meta` for unknown ids. */
data class HealthTypeDescriptor(
    val id: String,
    val kind: HealthKind,
    val aggregation: HealthAggregation,
    val unit: String
) {
    val isDurationLike: Boolean
        get() = aggregation == HealthAggregation.DURATION || kind == HealthKind.SESSION || kind == HealthKind.DURATION

    companion object {
        fun of(type: HealthDataType) = HealthTypeDescriptor(type.id, type.kind, type.aggregation, type.unit)

        fun of(meta: HealthTypeMeta) = HealthTypeDescriptor(
            id = meta.typeId,
            kind = HealthKind.byId(meta.kind) ?: HealthKind.DISCRETE,
            aggregation = runCatching { HealthAggregation.valueOf(meta.aggregation) }.getOrDefault(HealthAggregation.AVERAGE),
            unit = meta.unit
        )

        fun resolve(typeId: String, meta: Map<String, HealthTypeMeta> = emptyMap()): HealthTypeDescriptor =
            HealthDataType.byId(typeId)?.let(::of)
                ?: meta[typeId]?.let(::of)
                ?: HealthTypeDescriptor(typeId, HealthKind.DISCRETE, HealthAggregation.AVERAGE, "none")
    }
}

/**
 * Daily/hourly statistics from sample rows — identical rules on Android and iOS
 * (docs/health-data.md §1.3). Pure; property-tested "incremental == full rebuild".
 *
 * - cumulative: sum, count, avg = sum/count, min/max of row values, last value.
 * - discrete/series: avg weighted by `count`; min/max come from `value2/value3` only when
 *   the row condenses several samples (`count > 1`), otherwise from `value`.
 * - blood_pressure fills `v2_*` from the diastolic column.
 * - duration/session: `sum = duration_s = Σ(end − start)`, count.
 * - category: count + last value (the category code).
 * - Tombstoned rows never contribute; `out_of_bed` sleep stages are ignored here (sleep
 *   nights are derived at read time by [HealthSleepAnalysis]).
 */
object HealthRollupMath {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun rebuildDaily(type: HealthTypeDescriptor, rows: List<HealthSampleRow>, tz: String): List<HealthDailyRollup> =
        rows.asSequence()
            .filter { !it.deleted && it.typeId == type.id }
            .groupBy { it.localDay }
            .map { (day, dayRows) -> rollupFor(type, day, tz, dayRows) }
            .sortedBy { it.day }

    fun rollupFor(type: HealthTypeDescriptor, day: String, tz: String, rows: List<HealthSampleRow>): HealthDailyRollup {
        val live = rows.filter { !it.deleted }
        if (type.id == HealthDataType.SLEEP.id) return sleepRollup(day, tz, live)
        val acc = Accumulator()
        for (row in live) acc.add(type, row)
        return acc.toRollup(type, day, tz)
    }

    /** Fold [added] rows into an existing day rollup. Equal to a full rebuild over the union. */
    fun mergeIncremental(type: HealthTypeDescriptor, existing: HealthDailyRollup?, added: List<HealthSampleRow>): HealthDailyRollup {
        val day = existing?.day ?: added.first().localDay
        val tz = existing?.tz ?: ""
        if (type.id == HealthDataType.SLEEP.id) {
            // Sleep needs interval unions; callers rebuild it from rows instead of merging.
            return existing ?: sleepRollup(day, tz, added)
        }
        val acc = Accumulator.from(type, existing)
        for (row in added) if (!row.deleted) acc.add(type, row)
        return acc.toRollup(type, day, tz).copy(fromPlatformAggregate = existing?.fromPlatformAggregate ?: false, ownSum = existing?.ownSum)
    }

    fun rebuildHourly(type: HealthTypeDescriptor, rows: List<HealthSampleRow>, day: String, zone: ZoneId): List<HealthHourlyRollup> {
        val byHour = rows.asSequence()
            .filter { !it.deleted && it.localDay == day }
            .groupBy { HealthDayKeys.hourOf(it.startMs, it.startOffsetS, zone) }
        return byHour.map { (hour, hourRows) ->
            val acc = Accumulator()
            hourRows.forEach { acc.add(type, it) }
            val r = acc.toRollup(type, day, zone.id)
            HealthHourlyRollup(type.id, day, hour, sum = r.sum ?: r.durationS, avg = r.avg, min = r.min, max = r.max, count = r.count)
        }.sortedBy { it.hour }
    }

    /** Virtual `dietary_*` day rollups derived from `nutrition.extra_json` (Android only). */
    fun virtualDietary(nutritionRows: List<HealthSampleRow>, tz: String): Map<HealthDataType, List<HealthDailyRollup>> {
        val out = mutableMapOf<HealthDataType, MutableMap<String, Accumulator>>()
        for (row in nutritionRows) {
            if (row.deleted || row.typeId != HealthDataType.NUTRITION_RECORD.id) continue
            val extra = parseExtra(row.extraJson) ?: continue
            for ((type, key) in HealthDataType.dietaryExtraKeys) {
                val value = (extra[key] as? JsonPrimitive)?.doubleOrNull ?: continue
                val acc = out.getOrPut(type) { mutableMapOf() }.getOrPut(row.localDay) { Accumulator() }
                acc.add(HealthTypeDescriptor.of(type), row.copy(value = value, value2 = null, value3 = null, count = 1))
            }
        }
        return out.mapValues { (type, days) ->
            days.map { (day, acc) -> acc.toRollup(HealthTypeDescriptor.of(type), day, tz) }.sortedBy { it.day }
        }
    }

    fun parseExtra(extraJson: String?): JsonObject? {
        if (extraJson.isNullOrBlank()) return null
        return runCatching { json.parseToJsonElement(extraJson) as? JsonObject }.getOrNull()
    }

    private fun sleepRollup(day: String, tz: String, rows: List<HealthSampleRow>): HealthDailyRollup {
        val night = HealthSleepAnalysis.nightFor(day, rows)
        val asleep = night?.asleepS ?: 0.0
        val count = rows.count { it.categoryValue == HealthSleepCodes.IN_BED || it.count > 0 }
        return HealthDailyRollup(
            typeId = HealthDataType.SLEEP.id,
            day = day,
            tz = tz,
            sum = asleep,
            avg = asleep,
            min = asleep,
            max = asleep,
            count = count,
            lastValue = asleep,
            lastAtMs = night?.endMs ?: rows.maxOfOrNull { it.endMs },
            durationS = night?.inBedS ?: asleep
        )
    }

    /** Running statistics with count-weighted averages; serialisable back to a rollup. */
    class Accumulator {
        var sum = 0.0
        var weightedSum = 0.0
        var count = 0
        var min: Double? = null
        var max: Double? = null
        var lastValue: Double? = null
        var lastAtMs: Long? = null
        var v2WeightedSum = 0.0
        var v2Count = 0
        var v2Min: Double? = null
        var v2Max: Double? = null
        var durationS = 0.0
        var any = false

        fun add(type: HealthTypeDescriptor, row: HealthSampleRow) {
            if (row.deleted) return
            any = true
            val weight = row.count.coerceAtLeast(1)
            val value = row.value
            when {
                type.isDurationLike -> {
                    durationS += row.durationS
                    count += 1
                    if (value != null) {
                        sum += value
                        weightedSum += value
                        trackMinMax(value, value)
                    }
                }
                type.kind == HealthKind.CATEGORY -> {
                    count += 1
                    val code = row.categoryValue?.toDouble() ?: value
                    if (code != null) trackMinMax(code, code)
                }
                type.kind == HealthKind.CUMULATIVE -> {
                    if (value != null) {
                        sum += value
                        weightedSum += value
                        trackMinMax(value, value)
                    }
                    count += 1
                }
                else -> { // DISCRETE / SERIES
                    if (value != null) {
                        weightedSum += value * weight
                        sum += value
                        count += weight
                        val condensed = weight > 1 && row.value2 != null && row.value3 != null && type.id != HealthDataType.BLOOD_PRESSURE.id
                        if (condensed) trackMinMax(row.value2!!, row.value3!!) else trackMinMax(value, value)
                    }
                }
            }
            if (type.id == HealthDataType.BLOOD_PRESSURE.id) {
                row.value2?.let { dia ->
                    v2WeightedSum += dia * weight
                    v2Count += weight
                    v2Min = minOf(v2Min ?: dia, dia)
                    v2Max = maxOf(v2Max ?: dia, dia)
                }
            }
            val last = when {
                type.kind == HealthKind.CATEGORY -> row.categoryValue?.toDouble() ?: value
                type.isDurationLike -> value ?: row.durationS
                else -> value
            }
            if (last != null && (lastAtMs == null || row.endMs >= lastAtMs!!)) {
                lastAtMs = row.endMs
                lastValue = last
            }
        }

        private fun trackMinMax(lo: Double, hi: Double) {
            min = minOf(min ?: lo, lo)
            max = maxOf(max ?: hi, hi)
        }

        fun toRollup(type: HealthTypeDescriptor, day: String, tz: String): HealthDailyRollup {
            if (!any) return HealthDailyRollup(type.id, day, tz)
            val isBp = type.id == HealthDataType.BLOOD_PRESSURE.id
            return when {
                type.isDurationLike -> HealthDailyRollup(
                    typeId = type.id, day = day, tz = tz,
                    sum = durationS, avg = if (count > 0) durationS / count else null,
                    min = min, max = max, count = count, lastValue = lastValue, lastAtMs = lastAtMs,
                    durationS = durationS
                )
                type.kind == HealthKind.CATEGORY -> HealthDailyRollup(
                    typeId = type.id, day = day, tz = tz,
                    count = count, min = min, max = max, lastValue = lastValue, lastAtMs = lastAtMs
                )
                type.kind == HealthKind.CUMULATIVE -> HealthDailyRollup(
                    typeId = type.id, day = day, tz = tz,
                    sum = sum, avg = if (count > 0) sum / count else null,
                    min = min, max = max, count = count, lastValue = lastValue, lastAtMs = lastAtMs
                )
                else -> HealthDailyRollup(
                    typeId = type.id, day = day, tz = tz,
                    sum = sum,
                    avg = if (count > 0) weightedSum / count else null,
                    min = min, max = max, count = count, lastValue = lastValue, lastAtMs = lastAtMs,
                    v2Avg = if (isBp && v2Count > 0) v2WeightedSum / v2Count else null,
                    v2Min = if (isBp) v2Min else null,
                    v2Max = if (isBp) v2Max else null
                )
            }
        }

        companion object {
            fun from(type: HealthTypeDescriptor, existing: HealthDailyRollup?): Accumulator {
                val acc = Accumulator()
                if (existing == null || existing.count == 0) return acc
                acc.any = true
                acc.count = existing.count
                acc.min = existing.min
                acc.max = existing.max
                acc.lastValue = existing.lastValue
                acc.lastAtMs = existing.lastAtMs
                acc.durationS = existing.durationS ?: 0.0
                acc.sum = existing.sum ?: 0.0
                acc.weightedSum = when {
                    type.isDurationLike -> existing.sum ?: 0.0
                    type.kind == HealthKind.CUMULATIVE -> existing.sum ?: 0.0
                    else -> (existing.avg ?: 0.0) * existing.count
                }
                if (type.id == HealthDataType.BLOOD_PRESSURE.id && existing.v2Avg != null) {
                    acc.v2Count = existing.count
                    acc.v2WeightedSum = existing.v2Avg * existing.count
                    acc.v2Min = existing.v2Min
                    acc.v2Max = existing.v2Max
                }
                return acc
            }
        }
    }
}
