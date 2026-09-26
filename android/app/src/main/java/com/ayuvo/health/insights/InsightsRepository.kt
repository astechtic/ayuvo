package com.ayuvo.health.insights

import com.ayuvo.health.data.metrics.AppMetricSnapshot
import com.ayuvo.health.models.UserProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId

/**
 * Insights for the UI, Coach, actions and the morning worker (docs/insights.md §3). Nothing is
 * stored: each read rebuilds the inputs from the stores and runs the engines, with one in-memory
 * result cached on the store revisions, the app snapshot, the goals and the day (the same pattern
 * as the metric series cache).
 */
class InsightsRepository(
    private val config: () -> InsightsConfig,
    private val source: InsightsDataSource,
    private val healthRevision: () -> Long,
    private val hubEnabled: suspend () -> Boolean,
    private val appSnapshot: suspend () -> AppMetricSnapshot,
    private val profile: suspend () -> UserProfile?,
    private val settings: suspend () -> InsightsSettings,
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
    private val today: (ZoneId) -> LocalDate = { LocalDate.now(it) }
) {
    private data class Key(
        val healthRevision: Long,
        val settings: InsightsSettings,
        val profile: UserProfile?,
        val hub: Boolean,
        val day: LocalDate,
        val zone: String
    )

    private val mutex = Mutex()
    private var cached: Triple<Key, AppMetricSnapshot, InsightsSnapshot>? = null

    /** The current snapshot, recomputed only when an input changed. */
    suspend fun current(): InsightsSnapshot = withContext(Dispatchers.Default) {
        val z = zone()
        val snap = appSnapshot()
        val s = settings()
        val p = profile()
        val hub = hubEnabled()
        val key = Key(healthRevision(), s, p, hub, today(z), z.id)
        mutex.withLock {
            // The app snapshot is compared by value: an idle provider hands out a fresh but equal copy.
            cached?.let { (k, sn, result) -> if (k == key && (sn === snap || sn == snap)) return@withContext result }
            val cfg = config()
            val bundle = source.build(key.day, z, snap, s, p, hub, cfg)
            val result = HealthAnalyticsEngine.snapshot(bundle, cfg)
            cached = Triple(key, snap, result)
            result
        }
    }

    /** Today's Recovery only (the morning worker); shares the cache when it is warm. */
    suspend fun recoveryToday(): RecoveryResult = current().recovery

    /**
     * Recomputed whenever one of [triggers] emits (store revisions, repository flows, goal and
     * profile changes, a day tick), debounced like the Summary favourites.
     */
    @OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
    fun snapshots(triggers: List<Flow<Any?>>): Flow<InsightsSnapshot> =
        combine(triggers.map { t -> t.onStart { emit(null) } }) { it.toList() }
            .debounce(DEBOUNCE_MS)
            .mapLatest { current() }
            .flowOn(Dispatchers.Default)

    companion object {
        private const val DEBOUNCE_MS = 300L
    }
}
