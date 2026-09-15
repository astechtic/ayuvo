package com.ayuvo.health.data.health

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * JVM stand-in for SqliteHealthDataStore with the same upsert/tombstone/keyset semantics.
 * Records every commit so tests can assert atomicity (token committed with its page).
 */
class InMemoryHealthDataStore : HealthDataStore {
    private val _revision = MutableStateFlow(0L)
    override val revision: StateFlow<Long> = _revision

    val samples = LinkedHashMap<String, HealthSampleRow>()
    val series = LinkedHashMap<String, MutableList<HealthSeriesPoint>>()
    val daily = LinkedHashMap<Pair<String, String>, HealthDailyRollup>()
    val hourly = LinkedHashMap<Triple<String, String, Int>, HealthHourlyRollup>()
    val sourcesMap = LinkedHashMap<String, HealthSourceRow>()
    val states = LinkedHashMap<String, HealthSyncState>()
    val typeMetas = LinkedHashMap<String, HealthTypeMeta>()
    val metas = LinkedHashMap<String, String>()
    val commits = mutableListOf<HealthPageCommit>()
    var closed = false

    private fun bump() { _revision.value = _revision.value + 1 }

    override suspend fun commit(page: HealthPageCommit): HealthPageCommitResult {
        commits += page
        var inserted = 0
        var updated = 0
        var tombstoned = 0
        val pointsBySample = page.seriesPoints.groupBy { it.sampleId }
        for (row in page.rows) {
            val existing = samples[row.id]
            when {
                existing == null -> { samples[row.id] = row; inserted++ }
                !existing.deleted && existing.updatedMs < row.updatedMs -> { samples[row.id] = row.copy(deleted = false); updated++ }
                else -> continue
            }
            series.remove(row.id)
            pointsBySample[row.id]?.let { series[row.id] = it.distinctBy { p -> p.tMs }.toMutableList() }
        }
        for (id in page.deletedIds) {
            val existing = samples[id] ?: continue
            if (!existing.deleted && existing.origin in page.deleteOrigins) {
                samples[id] = existing.copy(deleted = true, updatedMs = maxOf(existing.updatedMs, System.currentTimeMillis()))
                tombstoned++
            }
        }
        for (s in page.sources) {
            val prior = sourcesMap[s.id]
            sourcesMap[s.id] = if (prior == null) s else prior.copy(
                name = s.name,
                deviceModel = s.deviceModel ?: prior.deviceModel,
                deviceType = s.deviceType ?: prior.deviceType,
                lastSeenMs = maxOf(prior.lastSeenMs ?: 0L, s.lastSeenMs ?: 0L)
            )
        }
        page.syncStates.forEach { states[it.typeId] = it }
        bump()
        return HealthPageCommitResult(inserted, updated, tombstoned)
    }

    override suspend fun samplesBetween(typeId: String, fromMs: Long, toMs: Long, includeDeleted: Boolean): List<HealthSampleRow> =
        samples.values
            .filter { it.typeId == typeId && it.endMs >= fromMs && it.startMs <= toMs && (includeDeleted || !it.deleted) }
            .sortedWith(compareBy<HealthSampleRow> { it.startMs }.thenBy { it.id })

    override suspend fun samplesByIds(ids: Collection<String>): List<HealthSampleRow> = ids.mapNotNull { samples[it] }

    override suspend fun samplesPage(typeId: String, beforeEndMs: Long?, beforeId: String?, limit: Int): List<HealthSampleRow> {
        val ordered = samples.values
            .filter { it.typeId == typeId && !it.deleted }
            .sortedWith(compareByDescending<HealthSampleRow> { it.endMs }.thenByDescending { it.id })
        val filtered = if (beforeEndMs == null || beforeId == null) ordered else ordered.filter {
            it.endMs < beforeEndMs || (it.endMs == beforeEndMs && it.id < beforeId)
        }
        return filtered.take(limit)
    }

    override suspend fun latestSample(typeId: String): HealthSampleRow? = samplesPage(typeId, null, null, 1).firstOrNull()

    override suspend fun sampleCount(typeId: String): Long = samples.values.count { it.typeId == typeId && !it.deleted }.toLong()

    override suspend fun sourceCounts(typeId: String): Map<String, Int> =
        samples.values.filter { it.typeId == typeId && !it.deleted }.groupingBy { it.sourceId }.eachCount()
            .entries.sortedByDescending { it.value }.associate { it.key to it.value }

    override suspend fun typeSummaries(): List<HealthTypeSummary> =
        samples.values.filter { !it.deleted }.groupBy { it.typeId }.map { (typeId, rows) ->
            HealthTypeSummary(
                typeId = typeId,
                count = rows.size.toLong(),
                firstMs = rows.minOf { it.startMs },
                lastMs = rows.maxOf { it.endMs },
                seriesCount = rows.sumOf { series[it.id]?.size ?: 0 }.toLong()
            )
        }

    override suspend fun forEachExportRow(exportedTypeIds: Set<String>, onRow: (HealthSampleRow) -> Unit) {
        samples.values
            .filter { !it.deleted && it.typeId in exportedTypeIds }
            .sortedWith(compareBy<HealthSampleRow> { it.typeId }.thenBy { it.endMs }.thenBy { it.id })
            .forEach(onRow)
    }

    override suspend fun forEachSeriesPoint(onPoint: (HealthSeriesPoint) -> Unit) {
        series.entries
            .filter { samples[it.key]?.deleted == false }
            .flatMap { it.value }
            .sortedWith(compareBy<HealthSeriesPoint> { it.typeId }.thenBy { it.sampleId }.thenBy { it.tMs })
            .forEach(onPoint)
    }

    override suspend fun seriesPoints(typeId: String, fromMs: Long, toMs: Long): List<HealthSeriesPoint> =
        series.entries
            .filter { samples[it.key]?.deleted == false }
            .flatMap { it.value }
            .filter { it.typeId == typeId && it.tMs in fromMs..toMs }
            .sortedBy { it.tMs }

    override suspend fun upsertSeriesPoints(points: List<HealthSeriesPoint>): Int {
        var written = 0
        for (p in points) {
            if (!samples.containsKey(p.sampleId)) continue
            val list = series.getOrPut(p.sampleId) { mutableListOf() }
            list.removeAll { it.tMs == p.tMs }
            list += p
            written++
        }
        if (written > 0) bump()
        return written
    }

    override suspend fun pruneSeriesBefore(cutoffMs: Long): Int {
        var removed = 0
        for (list in series.values) {
            val before = list.size
            list.removeAll { it.tMs < cutoffMs }
            removed += before - list.size
        }
        if (removed > 0) bump()
        return removed
    }

    override suspend fun dailyRollups(typeId: String, fromDay: String, toDay: String): List<HealthDailyRollup> =
        daily.values.filter { it.typeId == typeId && it.day >= fromDay && it.day <= toDay }.sortedBy { it.day }

    override suspend fun replaceDailyRollups(typeId: String, days: Collection<String>, rows: List<HealthDailyRollup>) {
        days.forEach { daily.remove(typeId to it) }
        rows.forEach { daily[it.typeId to it.day] = it }
        bump()
    }

    override suspend fun hourlyRollups(typeId: String, day: String): List<HealthHourlyRollup> =
        hourly.values.filter { it.typeId == typeId && it.day == day }.sortedBy { it.hour }

    override suspend fun replaceHourlyRollups(typeId: String, day: String, rows: List<HealthHourlyRollup>) {
        hourly.keys.filter { it.first == typeId && it.second == day }.forEach { hourly.remove(it) }
        rows.forEach { hourly[Triple(it.typeId, it.day, it.hour)] = it }
        bump()
    }

    override suspend fun deleteRollupsFor(typeIds: Collection<String>) {
        daily.keys.filter { it.first in typeIds }.forEach { daily.remove(it) }
        hourly.keys.filter { it.first in typeIds }.forEach { hourly.remove(it) }
        bump()
    }

    override suspend fun syncStates(): List<HealthSyncState> = states.values.toList()
    override suspend fun syncState(typeId: String): HealthSyncState? = states[typeId]
    override suspend fun putSyncState(state: HealthSyncState) { states[state.typeId] = state; bump() }
    override suspend fun clearSyncState(typeIds: Collection<String>) { typeIds.forEach { states.remove(it) }; bump() }

    override suspend fun sources(): List<HealthSourceRow> = sourcesMap.values.sortedBy { it.name }
    override suspend fun upsertSources(sources: List<HealthSourceRow>) { sources.forEach { sourcesMap[it.id] = it }; bump() }
    override suspend fun typeMeta(): List<HealthTypeMeta> = typeMetas.values.toList()
    override suspend fun upsertTypeMeta(meta: List<HealthTypeMeta>) { meta.forEach { typeMetas[it.typeId] = it }; bump() }
    override suspend fun meta(key: String): String? = metas[key]
    override suspend fun setMeta(key: String, value: String?) { if (value == null) metas.remove(key) else metas[key] = value; bump() }

    override suspend fun storageBytes(): Long = samples.size * 200L

    override suspend fun deleteAll() {
        samples.clear(); series.clear(); daily.clear(); hourly.clear(); sourcesMap.clear(); states.clear(); typeMetas.clear()
        bump()
    }

    override fun close() { closed = true }

    fun liveRows(typeId: String): List<HealthSampleRow> = samples.values.filter { it.typeId == typeId && !it.deleted }
}
