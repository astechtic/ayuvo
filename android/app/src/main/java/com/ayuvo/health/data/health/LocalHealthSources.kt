package com.ayuvo.health.data.health

import com.ayuvo.health.models.BodyFatEntry
import com.ayuvo.health.models.HealthDataType
import com.ayuvo.health.models.WeightEntry
import java.time.ZoneId
import java.util.UUID

/**
 * Contributes Ayuvo's own logged weight / body-fat / height to the mirror as `origin=2`
 * rows (`local:<uuid>`, source = this package, label "Ayuvo") so the hub and Coach see
 * them next to platform data.
 *
 * Dedupe against platform rows (rule mirrored from WeightRepository.importExternalWeights):
 * external imports get `id = ownRecordId(clientRecordId) ?: nameUUIDFromBytes(clientRecordId ?: recordId)`,
 * so a local entry is skipped when a platform row carries `client_record_id == "ayuvo_<entry.id>"`
 * (our own write echoed back) or when `entry.id == nameUUIDFromBytes(row.client_record_id ?: row.id)`
 * (the entry originated from the platform).
 */
class LocalHealthSources(
    private val weights: suspend () -> List<WeightEntry>,
    private val bodyFats: suspend () -> List<BodyFatEntry>,
    private val heightCm: suspend () -> Double?,
    private val packageName: String,
    private val zone: () -> ZoneId = { ZoneId.systemDefault() }
) {

    /** Upserts new local rows, tombstones removed ones. Returns the number of rows changed. */
    suspend fun contribute(store: HealthDataStore, nowMs: Long): Int {
        val z = zone()
        var changed = 0
        changed += contributeType(
            store, nowMs, z, HealthDataType.WEIGHT,
            weights().map { LocalEntry(it.id, it.date.toEpochMilli(), it.weightKg) }
        )
        changed += contributeType(
            store, nowMs, z, HealthDataType.BODY_FAT,
            bodyFats().map { LocalEntry(it.id, it.date.toEpochMilli(), it.bodyFatFraction * 100.0) }
        )
        changed += contributeHeight(store, nowMs, z)
        return changed
    }

    private suspend fun contributeType(
        store: HealthDataStore,
        nowMs: Long,
        z: ZoneId,
        type: HealthDataType,
        entries: List<LocalEntry>
    ): Int {
        val existing = store.samplesBetween(type.id, 0L, Long.MAX_VALUE, includeDeleted = true)
        val platformRows = existing.filter { it.origin == HealthSampleRow.ORIGIN_PLATFORM }
        val skip = candidateIds(platformRows)
        val wanted = entries.filter { it.id !in skip }
        val wantedIds = wanted.mapTo(HashSet()) { "$LOCAL_PREFIX${it.id}" }
        val existingLocal = existing.filter { it.origin == HealthSampleRow.ORIGIN_LOCAL_APP }
        val existingById = existingLocal.associateBy { it.id }

        val rows = wanted.mapNotNull { entry ->
            val id = "$LOCAL_PREFIX${entry.id}"
            val prior = existingById[id]
            // Stable updated_ms keeps re-runs idempotent; a changed value bumps it so the upsert wins.
            val updated = if (prior != null && prior.value != entry.value && !prior.deleted) nowMs else entry.atMs
            if (prior != null && !prior.deleted && prior.value == entry.value && prior.startMs == entry.atMs) return@mapNotNull null
            HealthSampleRow(
                id = id,
                typeId = type.id,
                startMs = entry.atMs,
                endMs = entry.atMs,
                startOffsetS = z.rules.getOffset(java.time.Instant.ofEpochMilli(entry.atMs)).totalSeconds,
                endOffsetS = z.rules.getOffset(java.time.Instant.ofEpochMilli(entry.atMs)).totalSeconds,
                localDay = HealthDayKeys.localDay(type, entry.atMs, entry.atMs, null, null, z),
                value = entry.value,
                unit = type.unit,
                sourceId = packageName,
                recordingMethod = RECORDING_METHOD_MANUAL,
                clientRecordId = "$CLIENT_PREFIX${entry.id}",
                origin = HealthSampleRow.ORIGIN_LOCAL_APP,
                updatedMs = maxOf(updated, prior?.updatedMs?.plus(1) ?: 0L)
            )
        }
        val removed = existingLocal.filter { !it.deleted && it.id !in wantedIds }.map { it.id }
        if (rows.isEmpty() && removed.isEmpty()) return 0
        val result = store.commit(
            HealthPageCommit(
                rows = rows,
                sources = listOf(HealthSourceRow(id = packageName, name = LOCAL_SOURCE_NAME, lastSeenMs = nowMs)),
                deletedIds = removed,
                deleteOrigins = setOf(HealthSampleRow.ORIGIN_LOCAL_APP)
            )
        )
        return result.changedIds
    }

    private suspend fun contributeHeight(store: HealthDataStore, nowMs: Long, z: ZoneId): Int {
        val cm = heightCm()?.takeIf { it > 0.0 } ?: return 0
        val meters = cm / 100.0
        val prior = store.samplesByIds(listOf(HEIGHT_ID)).firstOrNull()
        if (prior != null && !prior.deleted && prior.value != null && kotlin.math.abs(prior.value - meters) < 1e-6) return 0
        val at = prior?.startMs ?: nowMs
        val row = HealthSampleRow(
            id = HEIGHT_ID,
            typeId = HealthDataType.HEIGHT.id,
            startMs = at,
            endMs = at,
            startOffsetS = z.rules.getOffset(java.time.Instant.ofEpochMilli(at)).totalSeconds,
            endOffsetS = z.rules.getOffset(java.time.Instant.ofEpochMilli(at)).totalSeconds,
            localDay = HealthDayKeys.localDay(HealthDataType.HEIGHT, at, at, null, null, z),
            value = meters,
            unit = HealthDataType.HEIGHT.unit,
            sourceId = packageName,
            recordingMethod = RECORDING_METHOD_MANUAL,
            origin = HealthSampleRow.ORIGIN_LOCAL_APP,
            updatedMs = maxOf(nowMs, (prior?.updatedMs ?: 0L) + 1)
        )
        return store.commit(
            HealthPageCommit(rows = listOf(row), sources = listOf(HealthSourceRow(id = packageName, name = LOCAL_SOURCE_NAME, lastSeenMs = nowMs)))
        ).changedIds
    }

    data class LocalEntry(val id: UUID, val atMs: Long, val value: Double)

    companion object {
        const val LOCAL_PREFIX = "local:"
        const val HEIGHT_ID = "local:height"
        const val CLIENT_PREFIX = "ayuvo_"
        const val LOCAL_SOURCE_NAME = "Ayuvo"
        /** Metadata.RECORDING_METHOD_MANUAL_ENTRY without importing androidx here. */
        const val RECORDING_METHOD_MANUAL = 3

        /** Local entry ids that already exist as platform rows (echoed writes or platform imports). */
        fun candidateIds(platformRows: List<HealthSampleRow>): Set<UUID> {
            val out = HashSet<UUID>()
            for (row in platformRows) {
                val cid = row.clientRecordId?.takeIf { it.isNotBlank() }
                if (cid != null && cid.startsWith(CLIENT_PREFIX)) {
                    runCatching { UUID.fromString(cid.removePrefix(CLIENT_PREFIX)) }.getOrNull()?.let(out::add)
                }
                out += UUID.nameUUIDFromBytes((cid ?: row.id).toByteArray())
            }
            return out
        }
    }
}
