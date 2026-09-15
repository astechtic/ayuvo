package com.ayuvo.health.data.health

import com.ayuvo.health.models.HealthDataType
import java.time.LocalDate
import java.time.ZoneId

/** Per-type summary Coach sees in `get_health_data_types`. */
data class HealthCoachTypeSummary(
    val typeId: String,
    val category: String,
    val displayName: String,
    val unit: String,
    val aggregation: String,
    val kind: String,
    val count: Long,
    val firstMs: Long?,
    val lastMs: Long?,
    val latest: HealthSampleRow?,
    val historyLimitedBeforeMs: Long?
)

/**
 * Everything Coach's four health tools answer from — bounded (≤400 daily rows and ≤200 records
 * per type, ≤120 nights) and built once per store revision. Never persisted; tool payloads are
 * not written to chat history.
 */
data class HealthCoachSnapshot(
    val lastSyncMs: Long?,
    val types: List<HealthCoachTypeSummary>,
    /** type id → daily rollups ascending by day (newest 400). */
    val daily: Map<String, List<HealthDailyRollup>>,
    /** type id → records ascending by start (newest 200). */
    val samples: Map<String, List<HealthSampleRow>>,
    /** Nights ascending by wake day (newest 120). */
    val nights: List<SleepNight>,
    val zoneId: String,
    val platform: String = "Health Connect"
) {
    val isEmpty: Boolean get() = types.isEmpty()

    companion object {
        const val MAX_DAILY_ROWS = 400
        const val MAX_SAMPLES = 200
        const val MAX_NIGHTS = 120

        suspend fun build(
            repo: HealthDataRepository,
            lastSyncMs: Long?,
            zone: ZoneId = ZoneId.systemDefault(),
            today: LocalDate = LocalDate.now(zone),
            displayName: (String, String?) -> String = { id, hint -> hint ?: HealthDataType.byId(id)?.displayFallback() ?: HealthDataType.humanise(id) }
        ): HealthCoachSnapshot {
            val summaries = repo.summaries().values.filter { it.count > 0 }
            val meta = repo.typeMeta()
            val states = repo.syncStates()
            val types = summaries.mapNotNull { s ->
                val registry = HealthDataType.byId(s.typeId)
                if (registry?.isVirtualDietary == true) return@mapNotNull null
                val descriptor = HealthTypeDescriptor.resolve(s.typeId, meta)
                HealthCoachTypeSummary(
                    typeId = s.typeId,
                    category = registry?.category?.id ?: meta[s.typeId]?.category ?: "other",
                    displayName = displayName(s.typeId, meta[s.typeId]?.displayName ?: meta[s.typeId]?.nativeId),
                    unit = descriptor.unit,
                    aggregation = descriptor.aggregation.name,
                    kind = descriptor.kind.id,
                    count = s.count,
                    firstMs = s.firstMs,
                    lastMs = s.lastMs,
                    latest = repo.latest(s.typeId),
                    historyLimitedBeforeMs = states[s.typeId]?.let { if (!it.backfillWithHistory) it.backfillFloorMs else null }
                )
            }.sortedWith(compareBy({ it.category }, { it.typeId }))
            val daily = LinkedHashMap<String, List<HealthDailyRollup>>()
            val samples = LinkedHashMap<String, List<HealthSampleRow>>()
            for (t in types) {
                daily[t.typeId] = repo.daily(t.typeId, today.minusDays((MAX_DAILY_ROWS - 1).toLong()), today).takeLast(MAX_DAILY_ROWS)
                samples[t.typeId] = repo.samplesPage(t.typeId, null, null, MAX_SAMPLES).sortedWith(compareBy({ it.startMs }, { it.id }))
            }
            val nights = if (types.any { it.typeId == HealthDataType.SLEEP.id }) {
                repo.sleepNights(today.minusDays((MAX_NIGHTS - 1).toLong()), today).takeLast(MAX_NIGHTS)
            } else emptyList()
            return HealthCoachSnapshot(lastSyncMs, types, daily, samples, nights, zone.id)
        }
    }
}
