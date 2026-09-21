package com.ayuvo.health.ui.health

import com.ayuvo.health.data.health.HealthDataRepository
import com.ayuvo.health.models.HealthAggregation
import com.ayuvo.health.models.HealthDataType
import com.ayuvo.health.models.HealthKind
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

/** One Home tile: pre-formatted number/unit plus a numeric value for the count-up animation. */
data class HealthTileUi(
    val typeId: String,
    val number: String,
    val unit: String,
    /** "Today", "Last night", a date, or a relative time. */
    val captionKind: CaptionKind,
    val captionMs: Long? = null,
    val numeric: Double? = null,
    val spark: List<Float> = emptyList(),
    val hasData: Boolean
) {
    enum class CaptionKind { TODAY, LAST_NIGHT, DATE, RELATIVE, NONE }
}

/** Reads only the selected day (indexed) plus one LIMIT-1 query per LATEST-style tile. */
object HealthHomeTileBuilder {

    suspend fun build(
        repo: HealthDataRepository,
        types: List<HealthDataType>,
        day: LocalDate,
        today: LocalDate,
        unitPrefs: HealthUnitPrefs,
        liveStepsToday: Int?,
        zone: ZoneId = ZoneId.systemDefault(),
        locale: Locale = Locale.getDefault()
    ): List<HealthTileUi> = types.map { type -> tile(repo, type, day, today, unitPrefs, liveStepsToday, zone, locale) }

    private suspend fun tile(
        repo: HealthDataRepository,
        type: HealthDataType,
        day: LocalDate,
        today: LocalDate,
        unitPrefs: HealthUnitPrefs,
        liveStepsToday: Int?,
        zone: ZoneId,
        locale: Locale
    ): HealthTileUi {
        val weekStart = day.minusDays(6)
        val rollups = repo.daily(type.id, weekStart, day)
        val byDay = rollups.associateBy { it.day }
        val todayKey = day.toString()
        val isToday = day == today
        // Missing days are NaN (a gap), never a fabricated 0.
        fun spark(pick: (com.ayuvo.health.data.health.HealthDailyRollup) -> Double?): List<Float> =
            (0..6).map { i -> byDay[weekStart.plusDays(i.toLong()).toString()]?.let(pick)?.toFloat() ?: Float.NaN }

        return when {
            type == HealthDataType.SLEEP -> {
                val night = repo.sleepNights(day, day).firstOrNull()
                val nights = repo.sleepNights(weekStart, day).associateBy { it.nightOf }
                val asleep = night?.asleepS
                HealthTileUi(
                    typeId = type.id,
                    number = asleep?.let { HealthValueFormatter.duration(it) } ?: "—",
                    unit = "",
                    captionKind = if (asleep == null) HealthTileUi.CaptionKind.NONE else if (isToday) HealthTileUi.CaptionKind.LAST_NIGHT else HealthTileUi.CaptionKind.DATE,
                    captionMs = day.atStartOfDay(zone).toInstant().toEpochMilli(),
                    numeric = null,
                    spark = (0..6).map { i -> nights[weekStart.plusDays(i.toLong()).toString()]?.asleepS?.toFloat() ?: Float.NaN },
                    hasData = asleep != null
                )
            }
            type == HealthDataType.BLOOD_PRESSURE -> {
                val latest = repo.latest(type.id)
                val f = HealthValueFormatter.formatBloodPressure(latest?.value, latest?.value2, locale)
                HealthTileUi(type.id, f.number, f.unit, if (latest == null) HealthTileUi.CaptionKind.NONE else HealthTileUi.CaptionKind.RELATIVE, latest?.endMs, null, spark { it.avg }, latest != null)
            }
            type.isSumType || type.isDurationLike -> {
                val rollup = byDay[todayKey]
                var value = if (type.isDurationLike) rollup?.durationS ?: rollup?.sum else rollup?.sum
                if (type == HealthDataType.STEPS && isToday && liveStepsToday != null) value = liveStepsToday.toDouble()
                val f = HealthValueFormatter.format(type.id, value, unitPrefs, locale)
                HealthTileUi(
                    typeId = type.id,
                    number = f.number,
                    unit = f.unit,
                    captionKind = if (value == null) HealthTileUi.CaptionKind.NONE else if (isToday) HealthTileUi.CaptionKind.TODAY else HealthTileUi.CaptionKind.DATE,
                    captionMs = day.atStartOfDay(zone).toInstant().toEpochMilli(),
                    numeric = if (type.isDurationLike) null else value,
                    spark = spark { if (type.isDurationLike) it.durationS ?: it.sum else it.sum },
                    hasData = value != null
                )
            }
            type.aggregation == HealthAggregation.MIN_MAX && type.kind != HealthKind.CATEGORY -> {
                val rollup = byDay[todayKey]
                if (rollup != null && rollup.min != null && rollup.max != null) {
                    val lo = HealthValueFormatter.format(type.id, rollup.min, unitPrefs, locale)
                    val hi = HealthValueFormatter.format(type.id, rollup.max, unitPrefs, locale)
                    HealthTileUi(type.id, "${lo.number}–${hi.number}", hi.unit, if (isToday) HealthTileUi.CaptionKind.TODAY else HealthTileUi.CaptionKind.DATE, day.atStartOfDay(zone).toInstant().toEpochMilli(), null, spark { it.avg }, true)
                } else {
                    latestTile(repo, type, unitPrefs, locale, spark { it.avg })
                }
            }
            type.kind == HealthKind.CATEGORY -> {
                val rollup = byDay[todayKey]
                val count = rollup?.count?.takeIf { it > 0 }
                HealthTileUi(type.id, count?.toString() ?: "—", "", if (count == null) HealthTileUi.CaptionKind.NONE else if (isToday) HealthTileUi.CaptionKind.TODAY else HealthTileUi.CaptionKind.DATE, day.atStartOfDay(zone).toInstant().toEpochMilli(), count?.toDouble(), spark { it.count.toDouble() }, count != null)
            }
            type.aggregation == HealthAggregation.AVERAGE -> {
                val rollup = byDay[todayKey]
                if (rollup?.avg != null) {
                    val f = HealthValueFormatter.format(type.id, rollup.avg, unitPrefs, locale)
                    HealthTileUi(type.id, f.number, f.unit, if (isToday) HealthTileUi.CaptionKind.TODAY else HealthTileUi.CaptionKind.DATE, day.atStartOfDay(zone).toInstant().toEpochMilli(), rollup.avg, spark { it.avg }, true)
                } else {
                    latestTile(repo, type, unitPrefs, locale, spark { it.avg })
                }
            }
            else -> latestTile(repo, type, unitPrefs, locale, spark { it.lastValue })
        }
    }

    private suspend fun latestTile(repo: HealthDataRepository, type: HealthDataType, unitPrefs: HealthUnitPrefs, locale: Locale, spark: List<Float>): HealthTileUi {
        val latest = repo.latest(type.id)
        val f = HealthValueFormatter.format(type.id, latest?.value, unitPrefs, locale)
        return HealthTileUi(type.id, f.number, f.unit, if (latest == null) HealthTileUi.CaptionKind.NONE else HealthTileUi.CaptionKind.RELATIVE, latest?.endMs, latest?.value, spark, latest != null)
    }
}
