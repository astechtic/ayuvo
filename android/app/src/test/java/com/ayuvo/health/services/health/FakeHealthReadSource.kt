package com.ayuvo.health.services.health

import com.ayuvo.health.data.health.HealthDayKeys
import com.ayuvo.health.data.health.HealthSampleRow
import com.ayuvo.health.data.health.HealthSeriesPoint
import com.ayuvo.health.data.health.HealthSourceRow
import com.ayuvo.health.models.HealthDataType
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Scripted Health Connect. Platform contents live in [records]; incremental changes are a
 * log that tokens index into ("t<N>"); every call is appended to [calls] for order/IPC asserts.
 */
class FakeHealthReadSource : HealthReadSource {
    val records = LinkedHashMap<HealthDataType, MutableList<HealthMapped>>()
    /** Change log: each entry is either an upsert or a deletion id. */
    val changeLog = mutableListOf<Pair<HealthMapped?, String?>>()
    val expiredTokens = mutableSetOf<String>()
    val calls = mutableListOf<String>()
    var changesPageSize = 100
    /** Reads whose window starts before this instant throw a boundary error (no History). */
    var boundaryBeforeMs: Long? = null
    var throwQuota = false
    var failReads = false
    var tokenAvailable = true
    var aggregateOverride: ((HealthDataType, LocalDate, LocalDate, Boolean) -> Map<LocalDate, Double>?)? = null
    var hourlyOverride: Map<Int, Double>? = null

    fun add(type: HealthDataType, mapped: HealthMapped) {
        records.getOrPut(type) { mutableListOf() } += mapped
    }

    fun upsertChange(mapped: HealthMapped) {
        add(HealthDataType.byId(mapped.row.typeId)!!, mapped)
        changeLog += mapped to null
    }

    fun deleteChange(id: String) {
        changeLog += null to id
    }

    private fun quotaCheck() { if (throwQuota) throw HealthQuotaExceededException(IllegalStateException("Rate limited: quota exceeded")) }

    override suspend fun changesToken(type: HealthDataType): String? {
        calls += "token:${type.id}"
        quotaCheck()
        return if (tokenAvailable) "t${changeLog.size}" else null
    }

    override suspend fun changes(token: String): HealthChangesPage {
        calls += "changes:$token"
        quotaCheck()
        if (token in expiredTokens) return HealthChangesPage(emptyList(), emptyList(), null, hasMore = false, expired = true)
        val from = token.removePrefix("t").toInt()
        val slice = changeLog.drop(from).take(changesPageSize)
        val next = from + slice.size
        return HealthChangesPage(
            upserts = slice.mapNotNull { it.first },
            deletedIds = slice.mapNotNull { it.second },
            nextToken = "t$next",
            hasMore = next < changeLog.size,
            expired = false
        )
    }

    override suspend fun readPage(type: HealthDataType, fromMs: Long, toMs: Long, pageToken: String?, pageSize: Int, ascending: Boolean): HealthReadPage {
        calls += "read:${type.id}:$fromMs:$toMs:${pageToken ?: 0}"
        quotaCheck()
        if (failReads) throw HealthReadFailedException(IllegalStateException("binder"))
        boundaryBeforeMs?.let { if (fromMs < it) throw HealthReadBoundaryException(IllegalArgumentException("Reads before 30 days require history")) }
        val all = (records[type] ?: emptyList())
            .filter { it.row.startMs >= fromMs && it.row.startMs < toMs }
            .sortedBy { it.row.startMs }
            .let { if (ascending) it else it.reversed() }
        val offset = pageToken?.toInt() ?: 0
        val page = all.drop(offset).take(pageSize)
        val next = if (offset + page.size < all.size) (offset + page.size).toString() else null
        return HealthReadPage(page, next)
    }

    override suspend fun earliestRecordTime(type: HealthDataType, fromMs: Long, toMs: Long): HealthProbe {
        calls += "probeAsc:${type.id}"
        quotaCheck()
        // Probes are plain reads on the platform: the same window rule applies.
        boundaryBeforeMs?.let { if (fromMs < it) throw HealthReadBoundaryException(IllegalArgumentException("Reads before 30 days require history")) }
        val hit = (records[type] ?: emptyList()).filter { it.row.startMs >= fromMs && it.row.startMs < toMs }.minByOrNull { it.row.startMs }
        return hit?.let { HealthProbe.Found(it.row.startMs) } ?: HealthProbe.NoData
    }

    override suspend fun latestRecordTime(type: HealthDataType, fromMs: Long, toMs: Long): HealthProbe {
        calls += "probeDesc:${type.id}"
        quotaCheck()
        val hit = (records[type] ?: emptyList()).filter { it.row.startMs >= fromMs && it.row.startMs < toMs }.maxByOrNull { it.row.startMs }
        return hit?.let { HealthProbe.Found(it.row.startMs) } ?: HealthProbe.NoData
    }

    override suspend fun aggregateDaily(type: HealthDataType, fromDay: LocalDate, toDay: LocalDate, ownOriginOnly: Boolean): Map<LocalDate, Double>? {
        calls += "aggDaily:${type.id}:$fromDay:$toDay:$ownOriginOnly"
        quotaCheck()
        aggregateOverride?.let { return it(type, fromDay, toDay, ownOriginOnly) }
        val out = LinkedHashMap<LocalDate, Double>()
        for (m in records[type] ?: emptyList()) {
            val day = LocalDate.parse(m.row.localDay)
            if (day < fromDay || day > toDay) continue
            out[day] = (out[day] ?: 0.0) + (m.row.value ?: 0.0)
        }
        return out
    }

    override suspend fun aggregateHourly(type: HealthDataType, day: LocalDate): Map<Int, Double>? {
        calls += "aggHourly:${type.id}:$day"
        quotaCheck()
        return hourlyOverride ?: emptyMap()
    }

    companion object {
        /** A simple single-value platform row at [startMs] (UTC local day). */
        fun mapped(
            type: HealthDataType,
            id: String,
            startMs: Long,
            value: Double,
            endMs: Long = startMs,
            updatedMs: Long = startMs,
            source: String = "com.example.watch",
            categoryValue: Int? = null,
            count: Int = 1,
            value2: Double? = null,
            value3: Double? = null,
            series: List<HealthSeriesPoint> = emptyList(),
            extraRows: List<HealthSampleRow> = emptyList()
        ): HealthMapped = HealthMapped(
            row = HealthSampleRow(
                id = id,
                typeId = type.id,
                startMs = startMs,
                endMs = endMs,
                startOffsetS = 0,
                endOffsetS = 0,
                localDay = HealthDayKeys.localDay(type, startMs, endMs, 0, 0, ZoneOffset.UTC),
                value = value,
                value2 = value2,
                value3 = value3,
                unit = type.unit,
                categoryValue = categoryValue,
                count = count,
                sourceId = source,
                updatedMs = updatedMs
            ),
            series = series,
            extraRows = extraRows,
            source = HealthSourceRow(id = source, name = source, lastSeenMs = updatedMs)
        )
    }
}

class FakeHealthSyncPrefs(var hub: Boolean = true) : HealthSyncPrefs {
    var lastSync: Long? = null
    var rateLimitedUntil: Long? = null
    override suspend fun hubEnabled(): Boolean = hub
    override suspend fun lastSyncAtMs(): Long? = lastSync
    override suspend fun setLastSyncAtMs(ms: Long?) { lastSync = ms }
    override suspend fun rateLimitedUntilMs(): Long? = rateLimitedUntil
    override suspend fun setRateLimitedUntilMs(ms: Long?) { rateLimitedUntil = ms }
}
