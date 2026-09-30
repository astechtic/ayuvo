package com.ayuvo.health.data.health

import com.ayuvo.health.models.HealthDataType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId

/**
 * Read-side façade over the mirror for Home tiles, the hub, the detail screen and Coach.
 * Everything here is a read; writes only ever come from the sync engine and the importer.
 */
class HealthDataRepository(
    private val store: HealthDataStore,
    private val zone: () -> ZoneId = { ZoneId.systemDefault() }
) {
    val revision: StateFlow<Long> get() = store.revision
    val derivedRevision: StateFlow<Long> get() = store.derivedRevision

    /**
     * A derived metric per day with "native wins" applied (docs/derived-metrics.md §1): a day with a platform reading
     * of [nativeTypeId] shows that reading (source kind "native"), other days show Ayuvo's stored estimate.
     */
    suspend fun derivedSeries(metricId: String, nativeTypeId: String?, from: LocalDate, to: LocalDate): List<DerivedPoint> {
        val derived = store.derivedValues(metricId, from.toString(), to.toString()).associateBy { it.day }
        val native = nativeTypeId?.let { type ->
            store.dailyRollups(type, from.toString(), to.toString())
                .mapNotNull { r -> (r.avg ?: r.lastValue ?: r.sum)?.takeIf { r.count > 0 || r.fromPlatformAggregate }?.let { r.day to it } }
                .toMap()
        }.orEmpty()
        return (derived.keys + native.keys).sorted().mapNotNull { day ->
            val n = native[day]
            val d = derived[day]
            when {
                n != null -> DerivedPoint(LocalDate.parse(day), n, null, null, "native")
                d?.value != null -> DerivedPoint(LocalDate.parse(day), d.value, d.value2, d.value3, "derived", d.quality)
                else -> null
            }
        }
    }

    suspend fun derivedMetricIdsWithValues(): List<String> = store.derivedMetricIdsWithValues()

    suspend fun summaries(): Map<String, HealthTypeSummary> =
        store.typeSummaries().associateBy { it.typeId }

    suspend fun latest(typeId: String): HealthSampleRow? = store.latestSample(typeId)

    suspend fun daily(typeId: String, from: LocalDate, to: LocalDate): List<HealthDailyRollup> =
        store.dailyRollups(typeId, from.toString(), to.toString())

    suspend fun hourly(typeId: String, day: LocalDate): List<HealthHourlyRollup> =
        store.hourlyRollups(typeId, day.toString())

    /** Rows whose local day falls in `[from, to]` (rows are fetched with a two-day margin). */
    suspend fun samples(typeId: String, from: LocalDate, to: LocalDate): List<HealthSampleRow> {
        val z = zone()
        val fromMs = from.minusDays(2).atStartOfDay(z).toInstant().toEpochMilli()
        val toMs = to.plusDays(2).atStartOfDay(z).toInstant().toEpochMilli()
        val lo = from.toString()
        val hi = to.toString()
        return store.samplesBetween(typeId, fromMs, toMs).filter { it.localDay in lo..hi }
    }

    suspend fun samplesPage(typeId: String, beforeEndMs: Long?, beforeId: String?, limit: Int): List<HealthSampleRow> =
        store.samplesPage(typeId, beforeEndMs, beforeId, limit)

    suspend fun seriesPoints(typeId: String, fromMs: Long, toMs: Long): List<HealthSeriesPoint> =
        store.seriesPoints(typeId, fromMs, toMs)

    suspend fun sleepNights(from: LocalDate, to: LocalDate): List<SleepNight> = withContext(Dispatchers.Default) {
        HealthSleepAnalysis.nights(samples(HealthDataType.SLEEP.id, from, to))
    }

    suspend fun sources(): List<HealthSourceRow> = store.sources()

    /** Source package → row count for one type (one GROUP BY in the store). */
    suspend fun sourceCounts(typeId: String): Map<String, Int> = store.sourceCounts(typeId)

    /** Source id → display name as reported by the platform / export (`health_sources`). */
    suspend fun sourceNames(): Map<String, String> = store.sources().associate { it.id to it.name }

    suspend fun typeMeta(): Map<String, HealthTypeMeta> = store.typeMeta().associateBy { it.typeId }

    suspend fun descriptor(typeId: String): HealthTypeDescriptor =
        HealthTypeDescriptor.resolve(typeId, typeMeta())

    suspend fun syncStates(): Map<String, HealthSyncState> = store.syncStates().associateBy { it.typeId }

    suspend fun storageBytes(): Long = store.storageBytes()

    suspend fun count(typeId: String): Long = store.sampleCount(typeId)

    // -- Coach ------------------------------------------------------------------
    private var cachedSnapshot: Pair<Triple<Long, Long, List<String>>, HealthCoachSnapshot>? = null

    /**
     * Bounded snapshot for Coach's tools, cached per store and derived revision. [derivedMetrics] are the enabled
     * derived metrics (docs/derived-metrics.md); the ones with values join the tools as `derived:<id>`.
     */
    suspend fun coachSnapshot(
        lastSyncMs: Long?,
        displayName: (String, String?) -> String,
        derivedMetrics: List<com.ayuvo.health.data.derived.DerivedMetricInfo> = emptyList()
    ): HealthCoachSnapshot {
        val key = Triple(store.revision.value, store.derivedRevision.value, derivedMetrics.map { it.id })
        cachedSnapshot?.let { (cachedKey, snapshot) -> if (cachedKey == key) return snapshot.copy(lastSyncMs = lastSyncMs) }
        val built = withContext(Dispatchers.Default) {
            HealthCoachSnapshot.build(this@HealthDataRepository, lastSyncMs, zone(), displayName = displayName, derivedMetrics = derivedMetrics)
        }
        cachedSnapshot = key to built
        return built
    }
}

/** One day of a derived metric after "native wins"; [sourceKind] is "native" or "derived". */
data class DerivedPoint(
    val day: LocalDate,
    val value: Double,
    val value2: Double?,
    val value3: Double?,
    val sourceKind: String,
    val quality: Double? = null
)
