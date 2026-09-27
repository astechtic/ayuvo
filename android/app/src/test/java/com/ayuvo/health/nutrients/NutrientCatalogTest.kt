package com.ayuvo.health.nutrients

import com.ayuvo.health.AppLinks
import com.ayuvo.health.R
import com.ayuvo.health.data.metrics.MetricKey
import com.ayuvo.health.metrics.MetricsTestFiles
import com.ayuvo.health.ui.metrics.MetricCatalog
import com.ayuvo.health.ui.navigation.AppRoutes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** `nutrient:<key>` metrics: key grammar, routes, catalog, browse sections and names. */
class NutrientCatalogTest {
    private val catalog = MetricsTestFiles.catalog

    @Test
    fun metricKeyParsesAndRoundTrips() {
        assertEquals(MetricKey.Nutrient("vitamin_d"), MetricKey.parse("nutrient:vitamin_d"))
        assertEquals("nutrient:vitamin_d", MetricKey.Nutrient("vitamin_d").storageId)
        assertNull(MetricKey.parse("nutrient:"))
        assertNull(MetricKey.parse("nutrient:Vitamin D"))
        assertEquals("metric/nutrient%3Avitamin_d", AppRoutes.metric(MetricKey.Nutrient("vitamin_d")))
        assertTrue(AppRoutes.isHealthDetailRoute(AppRoutes.metric(MetricKey.Nutrient("iron"))))
    }

    @Test
    fun catalogListsReferenceThenSportsKeysAndResolvesThem() {
        assertEquals(NutrientFields.REFERENCE_KEYS, catalog.nutrientMetrics.map { it.key })
        assertEquals(NutrientsTestFiles.reference.allKeys, catalog.nutrientMetrics.map { it.key })
        val r = MetricCatalog.resolve(catalog, MetricKey.Nutrient("vitamin_d"))
        assertEquals("nutrient", r.source)
        assertEquals("nutrition", r.domain)
        assertEquals("mcg", r.unit)
        assertEquals("unknown", MetricCatalog.resolve(catalog, MetricKey.Nutrient("niacin")).source)
        assertTrue(MetricCatalog.resolve(catalog, MetricKey.Nutrient("fiber")).browseHidden)
        for (m in catalog.nutrientMetrics) {
            assertEquals(m.key, NutrientsTestFiles.reference.unitOf(m.key), m.unit)
            assertNotEquals(m.key, R.string.nutrients_unknown_name, NutrientFields.nameRes(m.key))
            m.learnSlug?.let { assertEquals("${AppLinks.SITE_URL}/nutrients/$it", AppLinks.nutrientUrl(it)) }
        }
    }

    @Test
    fun browseSectionsHaveTitlesAndHideFiber() {
        val sections = MetricCatalog.nutrientBrowseSections(catalog)
        assertEquals(
            listOf("nutrition.carbs", "nutrition.fats", "nutrition.minerals", "nutrition.vitamins", "nutrition.other", "nutrition.supplements"),
            sections.map { it.first.id }
        )
        assertTrue(sections.flatMap { it.second }.none { it.key == "fiber" })
        for (s in catalog.browseSections.filter { it.domain == "nutrition" }) assertNotNull(s.id, MetricCatalog.browseSectionTitleRes(s.id))
    }
}
