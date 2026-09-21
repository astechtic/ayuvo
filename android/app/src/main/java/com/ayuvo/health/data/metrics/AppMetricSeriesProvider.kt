package com.ayuvo.health.data.metrics

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicLong

/** Source of [AppMetricSnapshot]s; the app implementation combines the repository flows. */
fun interface AppMetricSources {
    fun snapshots(): Flow<AppMetricSnapshot>
}

/** Combines the six repositories once so every consumer shares one JSON decode per change. */
class RepositoryMetricSources(
    private val food: Flow<List<com.ayuvo.health.models.FoodEntry>>,
    private val water: Flow<List<com.ayuvo.health.models.WaterEntry>>,
    private val fasting: Flow<List<com.ayuvo.health.models.FastingSession>>,
    private val weight: Flow<List<com.ayuvo.health.models.WeightEntry>>,
    private val bodyFat: Flow<List<com.ayuvo.health.models.BodyFatEntry>>,
    private val workouts: Flow<List<com.ayuvo.health.models.WorkoutSession>>
) : AppMetricSources {
    override fun snapshots(): Flow<AppMetricSnapshot> = combine(
        combine(food, water, fasting) { f, w, fa -> Triple(f, w, fa) },
        combine(weight, bodyFat, workouts) { we, b, wo -> Triple(we, b, wo) }
    ) { a, b -> AppMetricSnapshot(a.first, a.second, a.third, b.first, b.second, b.third) }
}

data class AppMetricSeries(
    val id: AppMetricId,
    val range: MetricRange,
    val anchorMs: Long,
    val bounds: MetricBucketBounds,
    val buckets: List<MetricSeriesBucket>,
    val headline: MetricHeadline,
    /** Entries inside the interval, newest first (for "Show All Data"). */
    val rows: List<MetricEntry>
) {
    val hasData: Boolean get() = buckets.any { it.value != null }
}

/**
 * Cached app-metric series (docs/ui-structure.md §5). The snapshot is shared while anyone
 * observes [revision]; computations run on [dispatcher] and are cached by
 * (key, range, anchor, week start, revision) in a small LRU.
 */
class AppMetricSeriesProvider(
    sources: AppMetricSources,
    scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
    private val now: () -> Long = { System.currentTimeMillis() }
) {
    private val counter = AtomicLong(0)

    /** Reset to null 5 s after the last observer leaves, so a stale snapshot is never served. */
    private val versioned: StateFlow<Pair<Long, AppMetricSnapshot>?> = sources.snapshots()
        .map { snap -> counter.incrementAndGet() to snap }
        .stateIn(scope, SharingStarted.WhileSubscribed(5_000, replayExpirationMillis = 0), null)

    /** Bumps whenever any underlying repository emits. */
    val revision: StateFlow<Long> = versioned.map { it?.first ?: 0L }
        .stateIn(scope, SharingStarted.WhileSubscribed(5_000), 0L)

    private data class CacheKey(val id: AppMetricId, val range: MetricRange, val anchorMs: Long, val weekStart: WeekStart, val revision: Long, val zone: String)

    private val mutex = Mutex()
    private val cache = object : LinkedHashMap<CacheKey, AppMetricSeries>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<CacheKey, AppMetricSeries>?): Boolean = size > CACHE_SIZE
    }

    private suspend fun current(): Pair<Long, AppMetricSnapshot> = versioned.value ?: versioned.filterNotNull().first()

    suspend fun snapshot(): AppMetricSnapshot = current().second

    suspend fun series(id: AppMetricId, range: MetricRange, anchorMs: Long, weekStart: WeekStart): AppMetricSeries {
        val (rev, snap) = current()
        val z = zone()
        val key = CacheKey(id, range, anchorMs, weekStart, rev, z.id)
        mutex.withLock { cache[key] }?.let { return it }
        val nowMs = now()
        val computed = withContext(dispatcher) {
            val entries = AppMetricAggregator.entries(id, snap, nowMs, z)
            val agg = AppMetricAggregator.aggregation(id)
            val bounds = MetricsReference.bucketBounds(range, anchorMs, z, weekStart, nowMs)
            AppMetricSeries(
                id = id, range = range, anchorMs = anchorMs, bounds = bounds,
                buckets = MetricsReference.bucketSeries(entries, range, anchorMs, z, weekStart, agg),
                headline = MetricsReference.headline(entries, range, anchorMs, z, weekStart, agg),
                rows = entries.filter { it.value != null && it.tMs >= bounds.startMs && it.tMs < bounds.endMs }.sortedByDescending { it.tMs }
            )
        }
        mutex.withLock { cache[key] = computed }
        return computed
    }

    suspend fun sparkline(id: AppMetricId): MetricSparkline {
        val snap = snapshot()
        val nowMs = now()
        val z = zone()
        return withContext(dispatcher) { AppMetricAggregator.sparkline(id, snap, nowMs, z) }
    }

    internal suspend fun cacheSize(): Int = mutex.withLock { cache.size }

    companion object {
        const val CACHE_SIZE = 16
    }
}
