package com.ayuvo.health.data.metrics

import com.ayuvo.health.metrics.MetricsTestFiles
import org.junit.Assert.assertEquals
import org.junit.Test

class FavoritePinsTest {
    private val catalog = MetricsTestFiles.catalog
    private fun keys(vararg ids: String) = ids.map { MetricKey.parse(it)!! }

    @Test
    fun nothingStoredGivesTheCatalogDefaults() {
        val r = FavoritePins.resolve(catalog, null, null)
        assertEquals(PinSource.DEFAULT, r.source)
        assertEquals(
            listOf("app:calories", "app:protein", "steps", "app:water", "app:weight", "sleep", "heart_rate", "active_energy"),
            r.favourites
        )
    }

    @Test
    fun legacyTilesAreKeptThenAppDefaultsAppended() {
        val r = FavoritePins.resolve(catalog, null, "sleep, steps,unknown_type")
        assertEquals(PinSource.MIGRATED, r.source)
        assertEquals(listOf("sleep", "steps", "app:calories", "app:protein", "app:water", "app:weight"), r.favourites)
        assertEquals(emptyList<String>(), FavoritePins.resolve(catalog, null, "").favourites)
    }

    @Test
    fun newValueWinsAndIsCapped() {
        val raw = (AppMetricId.entries.map { it.key } + listOf("steps", "sleep", "steps")).joinToString(",")
        val r = FavoritePins.resolve(catalog, raw, "heart_rate")
        assertEquals(PinSource.NEW, r.source)
        assertEquals(catalog.favouritesMax, r.favourites.size)
        assertEquals(emptyList<String>(), FavoritePins.resolve(catalog, "", "steps").favourites)
    }

    @Test
    fun togglingAHealthPinKeepsAppPins() {
        val current = keys("app:calories", "steps", "app:water")
        assertEquals(keys("app:calories", "app:water"), FavoritePins.toggled(current, MetricKey.Health("steps"), false, 12))
        assertEquals(keys("app:calories", "steps", "app:water", "sleep"), FavoritePins.toggled(current, MetricKey.Health("sleep"), true, 12))
        assertEquals(current, FavoritePins.toggled(current, MetricKey.App(AppMetricId.WATER), true, 12))
        assertEquals(current, FavoritePins.toggled(current, MetricKey.Health("sleep"), true, 3))
    }

    @Test
    fun replacingHealthPinsKeepsAppPinsFirst() {
        val current = keys("steps", "app:calories", "sleep", "app:weight")
        assertEquals(keys("app:calories", "app:weight", "heart_rate", "steps"), FavoritePins.withHealth(current, listOf("heart_rate", "steps", "heart_rate"), 12))
        assertEquals("app:calories,steps", FavoritePins.serialize(listOf("app:calories", "steps")))
    }
}
