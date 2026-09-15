package com.ayuvo.health.services.health

import com.ayuvo.health.data.health.HealthSampleRow
import com.ayuvo.health.data.health.HealthSeriesPoint
import com.ayuvo.health.data.health.HealthSourceRow
import com.ayuvo.health.models.HealthDataType
import java.time.LocalDate

/** One platform record mapped to mirror rows: the main row, its series points, child rows (sleep stages) and its source. */
data class HealthMapped(
    val row: HealthSampleRow,
    val series: List<HealthSeriesPoint> = emptyList(),
    val extraRows: List<HealthSampleRow> = emptyList(),
    val source: HealthSourceRow? = null
) {
    val allRows: List<HealthSampleRow> get() = listOf(row) + extraRows
}

data class HealthReadPage(val records: List<HealthMapped>, val nextPageToken: String?)

data class HealthChangesPage(
    val upserts: List<HealthMapped>,
    val deletedIds: List<String>,
    val nextToken: String?,
    val hasMore: Boolean,
    val expired: Boolean
)

sealed class HealthProbe {
    data class Found(val ms: Long) : HealthProbe()
    object NoData : HealthProbe()
    object Failed : HealthProbe()
}

/**
 * Everything the sync engine needs from the platform, already mapped to mirror rows so the
 * engine (and its JVM tests via a fake) never touch androidx.health. Implementations throw
 * [HealthQuotaExceededException] / [HealthReadBoundaryException] / [HealthReadFailedException].
 */
interface HealthReadSource {
    /** A changes token for one type, or null when the call failed. */
    suspend fun changesToken(type: HealthDataType): String?

    suspend fun changes(token: String): HealthChangesPage

    suspend fun readPage(
        type: HealthDataType,
        fromMs: Long,
        toMs: Long,
        pageToken: String?,
        pageSize: Int,
        ascending: Boolean = true
    ): HealthReadPage

    /** Earliest record start in `[fromMs, toMs)` (ascending pageSize=1 probe). */
    suspend fun earliestRecordTime(type: HealthDataType, fromMs: Long, toMs: Long): HealthProbe

    /** Latest record start in `[fromMs, toMs)` (descending pageSize=1 probe) — the gap jump. */
    suspend fun latestRecordTime(type: HealthDataType, fromMs: Long, toMs: Long): HealthProbe

    /** Daily platform aggregate (priority-deduped) for SUM types; null when the call failed. */
    suspend fun aggregateDaily(type: HealthDataType, fromDay: LocalDate, toDay: LocalDate, ownOriginOnly: Boolean): Map<LocalDate, Double>?

    /** Hourly platform aggregate for one local day; null when the call failed. */
    suspend fun aggregateHourly(type: HealthDataType, day: LocalDate): Map<Int, Double>?
}
