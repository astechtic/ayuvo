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
    private var cachedSnapshot: Pair<Long, HealthCoachSnapshot>? = null

    /** Bounded snapshot for Coach's tools, cached per store revision. */
    suspend fun coachSnapshot(lastSyncMs: Long?, displayName: (String, String?) -> String): HealthCoachSnapshot {
        val rev = store.revision.value
        cachedSnapshot?.let { (cachedRev, snapshot) -> if (cachedRev == rev) return snapshot.copy(lastSyncMs = lastSyncMs) }
        val built = withContext(Dispatchers.Default) { HealthCoachSnapshot.build(this@HealthDataRepository, lastSyncMs, zone(), displayName = displayName) }
        cachedSnapshot = rev to built
        return built
    }
}
