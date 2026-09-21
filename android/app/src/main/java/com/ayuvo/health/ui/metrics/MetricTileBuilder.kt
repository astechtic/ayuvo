package com.ayuvo.health.ui.metrics

import com.ayuvo.health.data.health.HealthDataRepository
import com.ayuvo.health.data.metrics.AppMetricAggregator
import com.ayuvo.health.data.metrics.AppMetricId
import com.ayuvo.health.data.metrics.AppMetricSnapshot
import com.ayuvo.health.data.metrics.MetricAggregation
import com.ayuvo.health.data.metrics.MetricKey
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
        locale: Locale = Locale.getDefault()
    ): List<MetricTileUi> {
        val visible = keys.filterNot { it is MetricKey.App && it.id in hidden }
        val healthTypes = visible.filterIsInstance<MetricKey.Health>().mapNotNull { HealthDataType.byId(it.typeId) }
        val healthTiles = if (hubEnabled && health != null && healthTypes.isNotEmpty()) {
            runCatching {
                HealthHomeTileBuilder.build(health, healthTypes, today, today, healthUnits, liveStepsToday, zone, locale)
            }.getOrDefault(emptyList()).associateBy { it.typeId }
        } else emptyMap()
        return visible.map { key ->
            when (key) {
                is MetricKey.App -> MetricTileUi(key, appTile(key.id, snapshot, nowMs, zone, units, locale))
                is MetricKey.Health -> MetricTileUi(key, healthTiles[key.typeId] ?: emptyTile(key.typeId))
            }
        }
    }

    fun emptyTile(id: String) = HealthTileUi(id, "—", "", HealthTileUi.CaptionKind.NONE, hasData = false)

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
