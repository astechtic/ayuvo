package com.ayuvo.health.metrics

import com.ayuvo.health.R
import com.ayuvo.health.data.metrics.AppMetricAggregator
import com.ayuvo.health.data.metrics.AppMetricId
import com.ayuvo.health.data.metrics.MetricAggregation
import com.ayuvo.health.ui.metrics.MetricCatalog
import com.ayuvo.health.ui.metrics.MetricIcons
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The bundled catalog is the shared file, and everything Android resolves from it exists. */
class MetricCatalogContractTest {
    private val catalog = MetricsTestFiles.catalog

    private fun stringId(name: String): Int? = runCatching { R.string::class.java.getField(name).getInt(null) }.getOrNull()

    @Test
    fun assetIsByteIdenticalToSharedCatalog() {
        val shared = MetricsTestFiles.shared("metric_catalog.json")
        assertNotNull(shared)
        val asset = listOf("src/main/assets/metrics/metric_catalog.json", "app/src/main/assets/metrics/metric_catalog.json")
            .map(::File).first { it.exists() }
        assertArrayEquals(shared!!.readBytes(), asset.readBytes())
    }

    @Test
    fun everyAndroidIconResolves() {
        val names = catalog.domains.map { it.iconAndroid } + catalog.metrics.map { it.iconAndroid } +
            catalog.overrides.mapNotNull { it.iconAndroid }
        for (n in names) assertNotNull("icon $n", MetricIcons.byName(n))
    }

    @Test
    fun healthIconsFollowOverrideThenDomainFallback() {
        // Steps has a curated icon, so it no longer borrows the Activity domain's flame.
        assertEquals("AutoMirrored.Filled.DirectionsWalk", MetricCatalog.resolve(catalog, com.ayuvo.health.data.metrics.MetricKey.Health("steps")).iconAndroid)
        val bmr = MetricCatalog.resolve(catalog, com.ayuvo.health.data.metrics.MetricKey.Health("basal_metabolic_rate"))
        assertEquals(catalog.domainById.getValue(bmr.domain).iconAndroid, bmr.iconAndroid)
    }

    @Test
    fun healthTilesResolveMetricThenOverrideThenDomainIcon() {
        // Every curated override icon is what a health tile / row shows.
        for (o in catalog.overrides) {
            val name = o.iconAndroid ?: continue
            assertEquals(o.id, MetricIcons.byName(name), MetricCatalog.icon(catalog, com.ayuvo.health.data.metrics.MetricKey.Health(o.id)))
        }
        // Steps shows the walking figure, not the Activity domain's calorie flame.
        val steps = MetricCatalog.icon(catalog, com.ayuvo.health.data.metrics.MetricKey.Health("steps"))
        assertEquals(MetricIcons.byName("AutoMirrored.Filled.DirectionsWalk"), steps)
        org.junit.Assert.assertNotEquals(MetricIcons.byName(catalog.domainById.getValue("activity").iconAndroid), steps)
        // No override → domain icon; unknown key → Other domain icon; app metric → its own icon.
        val bmr = com.ayuvo.health.data.metrics.MetricKey.Health("basal_metabolic_rate")
        assertEquals(MetricIcons.byName(catalog.domainById.getValue(MetricCatalog.resolve(catalog, bmr).domain).iconAndroid),
            MetricCatalog.icon(catalog, bmr))
        assertEquals(MetricIcons.byName(catalog.domainById.getValue("other").iconAndroid),
            MetricCatalog.icon(catalog, com.ayuvo.health.data.metrics.MetricKey.Health("com.example.unknown")))
        assertEquals(MetricIcons.byName(catalog.metricByKey.getValue("app:weight").iconAndroid),
            MetricCatalog.icon(catalog, com.ayuvo.health.data.metrics.MetricKey.App(AppMetricId.WEIGHT)))
        // The curated names added for health overrides are all mapped.
        for (n in listOf("Filled.Straighten", "Filled.Stairs", "Filled.Bolt", "Filled.Thermostat", "Filled.Bloodtype",
            "Filled.Speed", "Filled.Height", "Filled.Calculate", "AutoMirrored.Filled.DirectionsWalk")) {
            assertTrue(n, n in MetricIcons.names)
        }
    }

    @Test
    fun everyTitleResourceExists() {
        for (d in catalog.domains) assertNotNull("string ${d.titleRes}", stringId(d.titleRes))
        for (m in catalog.metrics) assertNotNull("string ${m.titleRes}", stringId(m.titleRes))
    }

    @Test
    fun appMetricIdsMatchTheCatalogOneToOne() {
        assertEquals(catalog.metrics.map { it.key }.toSet(), AppMetricId.entries.map { it.key }.toSet())
        for (id in AppMetricId.entries) {
            val spec = catalog.metricByKey.getValue(id.key)
            assertEquals(id.key, MetricAggregation.fromRaw(spec.aggregation), AppMetricAggregator.aggregation(id))
            assertEquals(id.key, stringId(spec.titleRes), MetricCatalog.titleRes(id))
            assertEquals(id.key, stringId("metric_about_${id.slug}"), MetricCatalog.aboutRes(id))
            assertTrue(id.key, spec.ranges.isNotEmpty())
        }
    }

    @Test
    fun healthCategoriesAllMapToADomain() {
        for (c in com.ayuvo.health.models.HealthCategory.entries) {
            val domain = catalog.categoryDomains[c.id]
            assertNotNull(c.id, domain)
            assertNotNull(c.id, catalog.domainById[domain])
        }
    }
}
