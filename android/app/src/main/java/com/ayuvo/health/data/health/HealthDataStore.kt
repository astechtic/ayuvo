package com.ayuvo.health.data.health

import kotlinx.coroutines.flow.StateFlow

/**
 * Persistence seam for the Health Data mirror. [SqliteHealthDataStore] backs it on device
 * (`ayuvo_health.db`); JVM tests use the in-memory fake. Every method is safe to call from
 * any dispatcher; implementations own their threading.
 */
interface HealthDataStore {
    /** Bumped after every committed write so UI/Coach caches can invalidate cheaply. */
    val revision: StateFlow<Long>

    // -- Samples -----------------------------------------------------------
    suspend fun commit(page: HealthPageCommit): HealthPageCommitResult
    suspend fun samplesBetween(typeId: String, fromMs: Long, toMs: Long, includeDeleted: Boolean = false): List<HealthSampleRow>
    suspend fun samplesByIds(ids: Collection<String>): List<HealthSampleRow>
    /** Keyset page ordered by `(end_ms DESC, id DESC)`; pass the last row's end/id to continue. */
    suspend fun samplesPage(typeId: String, beforeEndMs: Long?, beforeId: String?, limit: Int): List<HealthSampleRow>
    suspend fun latestSample(typeId: String): HealthSampleRow?
    suspend fun sampleCount(typeId: String): Long
    /** Non-deleted rows of one type grouped by `source_id` (single GROUP BY, never a row scan in Kotlin). */
    suspend fun sourceCounts(typeId: String): Map<String, Int>
    suspend fun typeSummaries(): List<HealthTypeSummary>
    /** Streams every non-deleted row of exportable types ordered by `(type_id, end_ms, id)`. */
    suspend fun forEachExportRow(exportedTypeIds: Set<String>, onRow: (HealthSampleRow) -> Unit)
    suspend fun forEachSeriesPoint(onPoint: (HealthSeriesPoint) -> Unit)

    // -- Series points -----------------------------------------------------
    suspend fun seriesPoints(typeId: String, fromMs: Long, toMs: Long): List<HealthSeriesPoint>
    /** Import path: points whose parent row already exists (others are dropped). */
    suspend fun upsertSeriesPoints(points: List<HealthSeriesPoint>): Int
    suspend fun pruneSeriesBefore(cutoffMs: Long): Int

    // -- Roll-ups ----------------------------------------------------------
    suspend fun dailyRollups(typeId: String, fromDay: String, toDay: String): List<HealthDailyRollup>
    suspend fun replaceDailyRollups(typeId: String, days: Collection<String>, rows: List<HealthDailyRollup>)
    suspend fun hourlyRollups(typeId: String, day: String): List<HealthHourlyRollup>
    suspend fun replaceHourlyRollups(typeId: String, day: String, rows: List<HealthHourlyRollup>)
    suspend fun deleteRollupsFor(typeIds: Collection<String>)

    // -- Sync state --------------------------------------------------------
    suspend fun syncStates(): List<HealthSyncState>
    suspend fun syncState(typeId: String): HealthSyncState?
    suspend fun putSyncState(state: HealthSyncState)
    suspend fun clearSyncState(typeIds: Collection<String>)

    // -- Sources / meta ----------------------------------------------------
    suspend fun sources(): List<HealthSourceRow>
    suspend fun upsertSources(sources: List<HealthSourceRow>)
    suspend fun typeMeta(): List<HealthTypeMeta>
    suspend fun upsertTypeMeta(meta: List<HealthTypeMeta>)
    suspend fun meta(key: String): String?
    suspend fun setMeta(key: String, value: String?)

    // -- Maintenance -------------------------------------------------------
    suspend fun storageBytes(): Long
    /** Removes every row but keeps the schema; used by "Clear synced health data" and Replace-all import. */
    suspend fun deleteAll()
    fun close()
}
