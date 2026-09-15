package com.ayuvo.health.data.health

/**
 * Row types for the local Health Data mirror (`ayuvo_health.db`). Column names and
 * semantics follow `shared/health/schema.sql` one-to-one so export/import and the
 * iOS reader stay interchangeable. Pure Kotlin — no Android imports.
 */
data class HealthSampleRow(
    val id: String,
    val typeId: String,
    val startMs: Long,
    val endMs: Long,
    val startOffsetS: Int? = null,
    val endOffsetS: Int? = null,
    val localDay: String,
    val value: Double? = null,
    val value2: Double? = null,
    val value3: Double? = null,
    val valueText: String? = null,
    val unit: String,
    val categoryValue: Int? = null,
    val title: String? = null,
    val extraJson: String? = null,
    val count: Int = 1,
    val sourceId: String,
    val device: String? = null,
    val deviceType: Int? = null,
    val recordingMethod: Int? = null,
    val clientRecordId: String? = null,
    val origin: Int = ORIGIN_PLATFORM,
    val deleted: Boolean = false,
    val updatedMs: Long
) {
    val durationS: Double get() = ((endMs - startMs).coerceAtLeast(0L)) / 1000.0

    companion object {
        const val ORIGIN_PLATFORM = 0
        const val ORIGIN_IMPORT = 1
        const val ORIGIN_LOCAL_APP = 2
    }
}

data class HealthSeriesPoint(
    val sampleId: String,
    val typeId: String,
    val tMs: Long,
    val value: Double
)

data class HealthSourceRow(
    val id: String,
    val name: String,
    val deviceModel: String? = null,
    val deviceType: Int? = null,
    val lastSeenMs: Long? = null
)

data class HealthDailyRollup(
    val typeId: String,
    val day: String,
    val tz: String,
    val sum: Double? = null,
    val avg: Double? = null,
    val min: Double? = null,
    val max: Double? = null,
    val count: Int = 0,
    val lastValue: Double? = null,
    val lastAtMs: Long? = null,
    val v2Avg: Double? = null,
    val v2Min: Double? = null,
    val v2Max: Double? = null,
    val durationS: Double? = null,
    val ownSum: Double? = null,
    val fromPlatformAggregate: Boolean = false
)

data class HealthHourlyRollup(
    val typeId: String,
    val day: String,
    val hour: Int,
    val sum: Double? = null,
    val avg: Double? = null,
    val min: Double? = null,
    val max: Double? = null,
    val count: Int = 0
)

data class HealthSyncState(
    val typeId: String,
    val cursor: String? = null,
    val cursorIssuedMs: Long? = null,
    val lastSyncMs: Long? = null,
    val earliestAuthorizedMs: Long? = null,
    val earliestProbeMs: Long? = null,
    val backfillFloorMs: Long? = null,
    val oldestBackfilledMs: Long? = null,
    val backfillDone: Boolean = false,
    val backfillWithHistory: Boolean = false,
    val status: String = STATUS_IDLE,
    val lastError: String? = null,
    val lastErrorMs: Long? = null,
    val ipcCallsTotal: Long = 0
) {
    companion object {
        const val STATUS_IDLE = "idle"
        const val STATUS_BOOTSTRAPPING = "bootstrapping"
        const val STATUS_IMPORTING = "importing"
        const val STATUS_SYNCING = "syncing"
        const val STATUS_LIMITED = "limited"
        const val STATUS_LOCKED = "locked"
        const val STATUS_ERROR_PREFIX = "error:"
    }
}

data class HealthTypeMeta(
    val typeId: String,
    val category: String,
    val kind: String,
    val aggregation: String,
    val unit: String,
    val displayName: String? = null,
    val platform: String? = null,
    val nativeId: String? = null
)

/** Per-type summary for the hub, Home tiles and Coach's `get_health_data_types`. */
data class HealthTypeSummary(
    val typeId: String,
    val count: Long,
    val firstMs: Long?,
    val lastMs: Long?,
    val seriesCount: Long = 0
)

/**
 * Everything one sync page commits atomically: rows, their series points, sources, per-type
 * cursor updates and platform deletions. Sync-state changes ride in the same transaction so a
 * cursor is never persisted without the page it acknowledges.
 */
data class HealthPageCommit(
    val rows: List<HealthSampleRow> = emptyList(),
    val seriesPoints: List<HealthSeriesPoint> = emptyList(),
    val sources: List<HealthSourceRow> = emptyList(),
    val syncStates: List<HealthSyncState> = emptyList(),
    val deletedIds: List<String> = emptyList(),
    /**
     * Which row origins a deletion may tombstone. Platform deletions touch only `origin=0`
     * (imported rows are immune); the local-app adapter passes `{2}` for its own rows.
     */
    val deleteOrigins: Set<Int> = setOf(HealthSampleRow.ORIGIN_PLATFORM)
) {
    val isEmpty: Boolean
        get() = rows.isEmpty() && seriesPoints.isEmpty() && sources.isEmpty() && syncStates.isEmpty() && deletedIds.isEmpty()
}

data class HealthPageCommitResult(
    val inserted: Int,
    val updated: Int,
    val tombstoned: Int
) {
    val changedIds: Int get() = inserted + updated + tombstoned
}
