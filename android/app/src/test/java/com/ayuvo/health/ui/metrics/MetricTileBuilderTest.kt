package com.ayuvo.health.ui.metrics

import com.ayuvo.health.data.health.HealthDataRepository
import com.ayuvo.health.data.health.InMemoryHealthDataStore
import com.ayuvo.health.data.metrics.AppMetricFixtures.food
import com.ayuvo.health.data.metrics.AppMetricFixtures.ms
import com.ayuvo.health.data.metrics.AppMetricFixtures.water
import com.ayuvo.health.data.metrics.AppMetricFixtures.weight
import com.ayuvo.health.data.metrics.AppMetricFixtures.zone
import com.ayuvo.health.data.metrics.AppMetricId
import com.ayuvo.health.data.metrics.AppMetricSnapshot
import com.ayuvo.health.data.metrics.MetricKey
import com.ayuvo.health.models.WaterUnit
import com.ayuvo.health.ui.health.HealthTileUi
import com.ayuvo.health.ui.health.HealthUnitPrefs
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.util.Locale

class MetricTileBuilderTest {
    private val now = ms("2026-09-17T20:00")
    private val units = MetricUnits()

    @Test
    fun summedTileShowsTodayWithAnOldestFirstSparkline() {
        val snap = AppMetricSnapshot(food = listOf(food("2026-09-17T08:00", 1234), food("2026-09-15T08:00", 800)))
        val tile = MetricTileBuilder.appTile(AppMetricId.CALORIES, snap, now, zone, units, Locale.US)
        assertTrue(tile.hasData)
        assertEquals("1,234", tile.number)
        assertEquals("kcal", tile.unit)
        assertEquals(HealthTileUi.CaptionKind.TODAY, tile.captionKind)
        assertEquals(7, tile.spark.size)
        assertEquals(1234f, tile.spark.last(), 0f)
        assertEquals(800f, tile.spark[4], 0f)
        assertTrue(tile.spark[0].isNaN())
    }

    @Test
    fun latestTileUsesTheNewestReadingInTheChosenUnit() {
        val snap = AppMetricSnapshot(weight = listOf(weight("2026-09-10T07:00", 81.0), weight("2026-09-16T07:00", 80.0)))
        val tile = MetricTileBuilder.appTile(AppMetricId.WEIGHT, snap, now, zone, MetricUnits(weightMetric = false), Locale.US)
        assertEquals("176.4", tile.number)
        assertEquals("lb", tile.unit)
        assertEquals(HealthTileUi.CaptionKind.RELATIVE, tile.captionKind)
        assertEquals(ms("2026-09-16T07:00"), tile.captionMs)
    }

    @Test
    fun noDataIsADashNeverZero() {
        val tile = MetricTileBuilder.appTile(AppMetricId.WATER, AppMetricSnapshot.EMPTY, now, zone, MetricUnits(waterUnit = WaterUnit.FLUID_OUNCES), Locale.US)
        assertFalse(tile.hasData)
        assertEquals("—", tile.number)
    }

    @Test
    fun hiddenTrackersAndDisconnectedHealthAreHandled() = runBlocking {
        val snap = AppMetricSnapshot(water = listOf(water("2026-09-17T08:00", 500)))
        val keys = listOf(MetricKey.App(AppMetricId.WATER), MetricKey.Health("steps"), MetricKey.App(AppMetricId.CALORIES))
        val tiles = MetricTileBuilder.build(
            keys, snap, HealthDataRepository(InMemoryHealthDataStore()), hubEnabled = false,
            today = LocalDate.of(2026, 9, 17), nowMs = now, healthUnits = HealthUnitPrefs(), units = units,
            liveStepsToday = null, hidden = setOf(AppMetricId.WATER), zone = zone, locale = Locale.US
        )
        assertEquals(listOf("steps", "app:calories"), tiles.map { it.key.storageId })
        assertFalse(tiles[0].tile.hasData)
        assertFalse(tiles[1].tile.hasData)
    }
}
