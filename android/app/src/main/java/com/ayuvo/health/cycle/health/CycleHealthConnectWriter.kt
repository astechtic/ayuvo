package com.ayuvo.health.cycle.health

import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.MenstruationFlowRecord
import androidx.health.connect.client.records.MenstruationPeriodRecord
import androidx.health.connect.client.records.metadata.Metadata
import com.ayuvo.health.cycle.data.CycleDayLog
import com.ayuvo.health.cycle.data.CyclePeriod
import com.ayuvo.health.cycle.data.CyclePlatformPeriods
import com.ayuvo.health.cycle.data.CycleRepository
import com.ayuvo.health.cycle.data.CycleSyncState
import com.ayuvo.health.cycle.engine.CycleConfig
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.LocalDate
import java.time.ZoneId

/**
 * Writes the user's app periods and flow days to Health Connect (docs/cycle-tracking.md §4): one
 * `MenstruationPeriodRecord` per closed period (`ayuvo:cycle:<id>`) and one `MenstruationFlowRecord` per day with a
 * Light/Medium/Heavy flow (`ayuvo:flow:<day>`), with `clientRecordVersion = updated_ms` so every write is an
 * idempotent upsert. Ongoing periods wait until they end. Deletes remove the records by client id. Failures only set
 * `sync_state = failed`; local data is never touched. Nothing here logs values.
 */
class CycleHealthConnectWriter(
    private val client: () -> HealthConnectClient?,
    private val grantedPermissions: suspend () -> Set<String>?,
    private val repository: CycleRepository,
    private val config: CycleConfig,
    private val zone: () -> ZoneId = { ZoneId.systemDefault() }
) {
    /** Writes or removes every pending row. [syncOn] = the user's "Sync with Health Connect" switch. */
    suspend fun syncPending(syncOn: Boolean) {
        val (periods, logs) = repository.pendingSync()
        if (periods.isEmpty() && logs.isEmpty()) return
        val c = client()
        val granted = grantedPermissions()
        val canWrite = c != null && granted != null && WRITE_PERMISSION in granted
        for (p in periods) syncPeriod(p, c, canWrite, syncOn)
        for (l in logs) syncLog(l, c, canWrite, syncOn)
    }

    private suspend fun syncPeriod(p: CyclePeriod, c: HealthConnectClient?, canWrite: Boolean, syncOn: Boolean) {
        val written = ids(p.platformIdsJson)
        val clientId = CyclePlatformPeriods.CLIENT_PERIOD_PREFIX + p.id
        val remove = p.deleted || p.endDay == null || !syncOn
        if (remove) {
            if (written.isEmpty()) {
                // Nothing in Health Connect. An ongoing period waits (pending) until it ends.
                val state = when {
                    !syncOn -> CycleSyncState.LOCAL
                    p.deleted -> CycleSyncState.SYNCED
                    else -> CycleSyncState.PENDING
                }
                if (state != p.syncState) repository.markPeriodSync(p.id, state, "{}", p.updatedMs)
                return
            }
            if (!canWrite || c == null) {
                if (syncOn || p.deleted) repository.markPeriodSync(p.id, CycleSyncState.FAILED, p.platformIdsJson, p.updatedMs)
                return
            }
            val ok = runCatching {
                c.deleteRecords(MenstruationPeriodRecord::class, emptyList(), listOf(clientId))
            }.isSuccess
            val state = when {
                !ok -> CycleSyncState.FAILED
                !syncOn -> CycleSyncState.LOCAL
                p.deleted -> CycleSyncState.SYNCED
                else -> CycleSyncState.PENDING
            }
            repository.markPeriodSync(p.id, state, if (ok) "{}" else p.platformIdsJson, p.updatedMs)
            if (!ok) Log.w(TAG, "period delete failed")
            return
        }
        if (!canWrite || c == null) {
            repository.markPeriodSync(p.id, CycleSyncState.FAILED, p.platformIdsJson, p.updatedMs)
            return
        }
        val z = zone()
        val start = LocalDate.parse(p.startDay).atStartOfDay(z)
        val end = LocalDate.parse(p.endDay!!).plusDays(1).atStartOfDay(z)
        val result = runCatching {
            c.insertRecords(
                listOf(
                    MenstruationPeriodRecord(
                        startTime = start.toInstant(), startZoneOffset = start.offset,
                        endTime = end.toInstant(), endZoneOffset = end.offset,
                        metadata = Metadata.manualEntry(clientRecordId = clientId, clientRecordVersion = p.updatedMs)
                    )
                )
            )
        }
        result.exceptionOrNull()?.let { Log.w(TAG, "period write failed: ${it.javaClass.simpleName}") }
        repository.markPeriodSync(
            p.id,
            if (result.isSuccess) CycleSyncState.SYNCED else CycleSyncState.FAILED,
            if (result.isSuccess) idsJson(listOf(clientId)) else p.platformIdsJson,
            p.updatedMs
        )
    }

    private suspend fun syncLog(l: CycleDayLog, c: HealthConnectClient?, canWrite: Boolean, syncOn: Boolean) {
        val written = ids(l.platformIdsJson)
        val clientId = CyclePlatformPeriods.CLIENT_FLOW_PREFIX + l.day
        val flow = if (l.deleted || !syncOn) null else hcFlow(l.flow)
        if (flow == null) {
            if (written.isEmpty()) {
                val state = if (!syncOn) CycleSyncState.LOCAL else CycleSyncState.SYNCED
                if (state != l.syncState) repository.markDayLogSync(l.day, state, "{}", l.updatedMs)
                return
            }
            if (!canWrite || c == null) {
                repository.markDayLogSync(l.day, CycleSyncState.FAILED, l.platformIdsJson, l.updatedMs)
                return
            }
            val ok = runCatching { c.deleteRecords(MenstruationFlowRecord::class, emptyList(), listOf(clientId)) }.isSuccess
            if (!ok) Log.w(TAG, "flow delete failed")
            repository.markDayLogSync(
                l.day,
                if (!ok) CycleSyncState.FAILED else if (!syncOn) CycleSyncState.LOCAL else CycleSyncState.SYNCED,
                if (ok) "{}" else l.platformIdsJson,
                l.updatedMs
            )
            return
        }
        if (!canWrite || c == null) {
            repository.markDayLogSync(l.day, CycleSyncState.FAILED, l.platformIdsJson, l.updatedMs)
            return
        }
        // Midday local time keeps the flow sample inside its calendar day whatever the zone.
        val at = LocalDate.parse(l.day).atTime(12, 0).atZone(zone())
        val result = runCatching {
            c.insertRecords(
                listOf(
                    MenstruationFlowRecord(
                        time = at.toInstant(), zoneOffset = at.offset, flow = flow,
                        metadata = Metadata.manualEntry(clientRecordId = clientId, clientRecordVersion = l.updatedMs)
                    )
                )
            )
        }
        result.exceptionOrNull()?.let { Log.w(TAG, "flow write failed: ${it.javaClass.simpleName}") }
        repository.markDayLogSync(
            l.day,
            if (result.isSuccess) CycleSyncState.SYNCED else CycleSyncState.FAILED,
            if (result.isSuccess) idsJson(listOf(clientId)) else l.platformIdsJson,
            l.updatedMs
        )
    }

    /** Health Connect flow constant for a catalogue flow key; null when the level is not written (spotting). */
    internal fun hcFlow(key: String?): Int? = flowCode(config, key)

    companion object {
        private const val TAG = "CycleHealthWrite"
        private const val PLATFORM_KEY = "health_connect"

        /** One permission covers both record types (`WRITE_MENSTRUATION`). */
        val WRITE_PERMISSION: String = HealthPermission.getWritePermission(MenstruationPeriodRecord::class)
        val PERMISSIONS: Set<String> = setOf(
            WRITE_PERMISSION,
            HealthPermission.getWritePermission(MenstruationFlowRecord::class),
            HealthPermission.getReadPermission(MenstruationPeriodRecord::class),
            HealthPermission.getReadPermission(MenstruationFlowRecord::class)
        )

        internal fun flowCode(config: CycleConfig, key: String?): Int? {
            val level = config.flowLevels.firstOrNull { it.key == key } ?: return null
            return when (level.healthConnect) {
                "light" -> MenstruationFlowRecord.FLOW_LIGHT
                "medium" -> MenstruationFlowRecord.FLOW_MEDIUM
                "heavy" -> MenstruationFlowRecord.FLOW_HEAVY
                else -> null
            }
        }

        internal fun ids(json: String): List<String> = runCatching {
            val o = kotlinx.serialization.json.Json.parseToJsonElement(json) as? JsonObject
            (o?.get(PLATFORM_KEY) as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }.orEmpty()
        }.getOrDefault(emptyList())

        internal fun idsJson(ids: List<String>): String =
            JsonObject(mapOf(PLATFORM_KEY to JsonArray(ids.map(::JsonPrimitive)))).toString()
    }
}
