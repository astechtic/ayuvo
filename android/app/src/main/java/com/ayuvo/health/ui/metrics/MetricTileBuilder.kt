package com.ayuvo.health.ui.metrics

import com.ayuvo.health.data.derived.DerivedCatalog
import com.ayuvo.health.data.derived.DerivedMetricInfo
import com.ayuvo.health.data.health.DerivedPoint
import com.ayuvo.health.data.health.HealthDataRepository
import com.ayuvo.health.data.metrics.AppMetricAggregator
import com.ayuvo.health.data.metrics.AppMetricId
import com.ayuvo.health.data.metrics.AppMetricSnapshot
import com.ayuvo.health.data.metrics.MetricAggregation
import com.ayuvo.health.data.metrics.MetricKey
import com.ayuvo.health.data.metrics.MetricsReference
import com.ayuvo.health.nutrients.NutrientFields
import com.ayuvo.health.nutrients.NutrientFormat
import com.ayuvo.health.models.HealthDataType
import com.ayuvo.health.ui.health.HealthHomeTileBuilder
import com.ayuvo.health.ui.health.HealthTileUi
import com.ayuvo.health.ui.health.HealthUnitPrefs
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

/** One Summary favourite: its key plus the pre-formatted tile (number "—" and hasData=false when empty). */
data class MetricTileUi(val key: MetricKey, val tile: HealthTileUi)

/**
 * What `derived:<id>` tiles need (docs/derived-metrics.md): the catalog, the ids the switches allow, the band labels
 * and the clock style. Without it (widgets) derived pins are left out; a switched-off metric is hidden, not unpinned.
 */
data class DerivedTileSource(
    val catalog: DerivedCatalog,
    val enabled: Set<String>,
    val labels: Map<String, List<String>> = emptyMap(),
    val is24: Boolean = true
)

/**
 * Builds Summary favourite tiles (docs/ui-structure.md §8.4): app metrics from the shared
 * `sparkline_7d` rule (today's total for summed metrics, latest reading otherwise), health
 * types through [HealthHomeTileBuilder]. Nothing is invented: no data renders "—".
 */
object MetricTileBuilder {

    suspend fun build(
        keys: List<MetricKey>,
        snapshot: AppMetricSnapshot,
        health: HealthDataRepository?,
        hubEnabled: Boolean,
        today: LocalDate,
        nowMs: Long,
        healthUnits: HealthUnitPrefs,
        units: MetricUnits,
        liveStepsToday: Int?,
        hidden: Set<AppMetricId> = emptySet(),
        zone: ZoneId = ZoneId.systemDefault(),
        locale: Locale = Locale.getDefault(),
        derived: DerivedTileSource? = null
    ): List<MetricTileUi> {
        val visible = keys.filterNot {
            (it is MetricKey.App && it.id in hidden) ||
                (it is MetricKey.Derived && (derived == null || it.id !in derived.enabled || it.id !in derived.catalog.byId))
        }
        val healthTypes = visible.filterIsInstance<MetricKey.Health>().mapNotNull { HealthDataType.byId(it.typeId) }
        val healthTiles = if (hubEnabled && health != null && healthTypes.isNotEmpty()) {
            runCatching {
                HealthHomeTileBuilder.build(health, healthTypes, today, today, healthUnits, liveStepsToday, zone, locale)
            }.getOrDefault(emptyList()).associateBy { it.typeId }
        } else emptyMap()
        return visible.map { key ->
            when (key) {
                is MetricKey.App -> MetricTileUi(key, appTile(key.id, snapshot, nowMs, zone, units, locale))
                is MetricKey.Nutrient -> MetricTileUi(key, nutrientTile(key, snapshot, nowMs, zone, locale))
                is MetricKey.Health -> MetricTileUi(key, healthTiles[key.typeId] ?: emptyTile(key.typeId))
                is MetricKey.Derived -> MetricTileUi(key, derivedTile(key, derived!!, if (hubEnabled) health else null, today, zone, locale))
            }
        }
    }

    fun emptyTile(id: String) = HealthTileUi(id, "—", "", HealthTileUi.CaptionKind.NONE, hasData = false)

    /**
     * `derived:<id>` tile: the newest day value in the last [DERIVED_LOOKBACK_DAYS] days (native wins) and the 7-day
     * sparkline; "Today" when that day is today, else its date.
     */
    suspend fun derivedTile(
        key: MetricKey.Derived,
        source: DerivedTileSource,
        health: HealthDataRepository?,
        today: LocalDate,
        zone: ZoneId,
        locale: Locale = Locale.getDefault()
    ): HealthTileUi {
        val info = source.catalog.byId[key.id] ?: return emptyTile(key.storageId)
        val points = health?.let {
            runCatching { it.derivedSeries(info.id, info.nativeTypeId, today.minusDays(DERIVED_LOOKBACK_DAYS - 1), today) }.getOrNull()
        }.orEmpty()
        return derivedTile(key, info, points, today, zone, source.labels[info.id], source.is24, locale)
    }

    /** Pure part of [derivedTile]. */
    fun derivedTile(
        key: MetricKey.Derived,
        info: DerivedMetricInfo,
        points: List<DerivedPoint>,
        today: LocalDate,
        zone: ZoneId,
        labels: List<String>?,
        is24: Boolean,
        locale: Locale = Locale.getDefault()
    ): HealthTileUi {
        val (latest, spark) = DerivedMetricSupport.recent(points, today)
        val sparkFloats = spark.map { v -> v?.toFloat() ?: Float.NaN }
        if (latest == null) return emptyTile(key.storageId).copy(spark = sparkFloats)
        val isToday = latest.day == today
        return HealthTileUi(
            typeId = key.storageId,
            number = DerivedMetricSupport.format(info, latest.value, is24, labels, locale),
            unit = DerivedMetricSupport.unitLabel(info),
            captionKind = if (isToday) HealthTileUi.CaptionKind.TODAY else HealthTileUi.CaptionKind.DATE,
            captionMs = if (isToday) null else MetricsReference.localMidnight(latest.day, zone),
            numeric = if (info.isClock || !labels.isNullOrEmpty()) null else latest.value,
            spark = sparkFloats,
            hasData = true
        )
    }

    const val DERIVED_LOOKBACK_DAYS = 30L

    /** `nutrient:<key>` tile: today's food + supplement total and the 7-day sparkline (summed). */
    fun nutrientTile(key: MetricKey.Nutrient, snapshot: AppMetricSnapshot, nowMs: Long, zone: ZoneId, locale: Locale = Locale.getDefault()): HealthTileUi {
        val spark = MetricsReference.sparkline7d(AppMetricAggregator.nutrientEntries(key.key, snapshot), nowMs, zone, MetricAggregation.SUM)
        val sparkFloats = spark.values.map { v -> v?.toFloat() ?: Float.NaN }
        val value = spark.values.last() ?: return emptyTile(key.storageId).copy(spark = sparkFloats)
        return HealthTileUi(
            typeId = key.storageId,
            number = NutrientFormat.amount(value, locale),
            unit = NutrientFields.unit(key.key),
            captionKind = HealthTileUi.CaptionKind.TODAY,
            captionMs = null,
            numeric = value,
            spark = sparkFloats,
            hasData = true
        )
    }

    /** Pure app-metric tile; spark values are oldest → newest, missing days NaN. */
    fun appTile(id: AppMetricId, snapshot: AppMetricSnapshot, nowMs: Long, zone: ZoneId, units: MetricUnits, locale: Locale = Locale.getDefault()): HealthTileUi {
        val spark = AppMetricAggregator.sparkline(id, snapshot, nowMs, zone)
        val sparkFloats = spark.values.map { v -> v?.let { MetricCatalog.display(id, it, units).toFloat() } ?: Float.NaN }
        val summed = AppMetricAggregator.aggregation(id) != MetricAggregation.LAST
        val latest = if (summed) null else AppMetricAggregator.entries(id, snapshot, nowMs, zone)
            .filter { it.value != null && it.tMs <= nowMs }
            .maxByOrNull { it.tMs }
        val value: Double? = if (summed) spark.values.last() else latest?.value
        val key = "app:${id.slug}"
        if (value == null) return emptyTile(key).copy(spark = sparkFloats)
        val display = MetricCatalog.display(id, value, units)
        return HealthTileUi(
            typeId = key,
            number = MetricCatalog.formatDisplay(id, display, locale),
            unit = MetricCatalog.unitLabel(id, units),
            captionKind = if (summed) HealthTileUi.CaptionKind.TODAY else HealthTileUi.CaptionKind.RELATIVE,
            captionMs = latest?.tMs,
            numeric = if (id == AppMetricId.FASTING || id == AppMetricId.WORKOUT_MINUTES) null else display,
            spark = sparkFloats,
            hasData = true
        )
    }
}
