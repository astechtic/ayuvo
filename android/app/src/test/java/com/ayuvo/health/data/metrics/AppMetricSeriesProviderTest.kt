package com.ayuvo.health.data.metrics

import com.ayuvo.health.data.metrics.AppMetricFixtures.food
import com.ayuvo.health.data.metrics.AppMetricFixtures.ms
import com.ayuvo.health.data.metrics.AppMetricFixtures.zone
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test

class AppMetricSeriesProviderTest {
    private val now = ms("2026-09-17T20:00")

    @Test
    fun cachesPerRevisionAndRecomputesWhenDataChanges() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val source = MutableStateFlow(AppMetricSnapshot(food = listOf(food("2026-09-15T08:00", 500))))
            val provider = AppMetricSeriesProvider({ source }, scope, Dispatchers.Unconfined, { zone }, { now })
            val watcher = scope.launch { provider.revision.collect { } }
            val anchor = ms("2026-09-17T00:00")
            withTimeout(5_000) { provider.revision.first { it >= 1 } }

            val first = provider.series(AppMetricId.CALORIES, MetricRange.W, anchor, WeekStart.MONDAY)
            assertSame(first, provider.series(AppMetricId.CALORIES, MetricRange.W, anchor, WeekStart.MONDAY))
            assertEquals(500.0, first.buckets[1].value!!, 0.0)
            assertEquals(1, first.rows.size)

            source.value = AppMetricSnapshot(food = listOf(food("2026-09-15T08:00", 500), food("2026-09-15T12:00", 250)))
            withTimeout(5_000) { provider.revision.first { it >= 2 } }
            val second = provider.series(AppMetricId.CALORIES, MetricRange.W, anchor, WeekStart.MONDAY)
            assertNotSame(first, second)
            assertEquals(750.0, second.buckets[1].value!!, 0.0)
            watcher.cancel()
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun cacheIsBoundedAndWorksWithoutObservers() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val provider = AppMetricSeriesProvider({ MutableStateFlow(AppMetricSnapshot.EMPTY) }, scope, Dispatchers.Unconfined, { zone }, { now })
            repeat(AppMetricSeriesProvider.CACHE_SIZE + 4) { i ->
                val s = provider.series(AppMetricId.WATER, MetricRange.D, ms("2026-09-01T00:00") + i * 86_400_000L, WeekStart.MONDAY)
                assertEquals(false, s.hasData)
            }
            assertEquals(AppMetricSeriesProvider.CACHE_SIZE, provider.cacheSize())
        } finally {
            scope.cancel()
        }
    }
}
