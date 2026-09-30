package com.ayuvo.health.ui.metrics

import com.ayuvo.health.data.derived.DerivedCatalog
import com.ayuvo.health.data.health.DerivedPoint
import com.ayuvo.health.data.metrics.FavoritePins
import com.ayuvo.health.data.metrics.MetricKey
import com.ayuvo.health.data.metrics.MetricRange
import com.ayuvo.health.data.metrics.WeekStart
import com.ayuvo.health.derived.DerivedTestFiles
import com.ayuvo.health.metrics.MetricsTestFiles
import com.ayuvo.health.ui.health.HealthTileUi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Locale

/** Derived metrics on the detail, Browse and Summary (docs/derived-metrics.md): keys, pins, bucketing, formatting. */
class DerivedMetricSupportTest {
    private val zone = ZoneOffset.UTC
    private val catalog: DerivedCatalog by lazy { DerivedCatalog.parse(DerivedTestFiles.shared("derived_config.json")!!.readText()) }
    private val today = LocalDate.of(2026, 9, 16) // Wednesday

    private fun p(day: LocalDate, v: Double, kind: String = "derived", q: Double? = 1.0) = DerivedPoint(day, v, null, null, kind, if (kind == "derived") q else null)

    @Test
    fun derivedKeysParseAndRoundTrip() {
        val key = MetricKey.parse("derived:resting_hr_derived")
        assertEquals(MetricKey.Derived("resting_hr_derived"), key)
        assertEquals("derived:resting_hr_derived", key!!.storageId)
        assertEquals(MetricKey.Derived("peak_30_cadence"), MetricKey.parse(" derived:peak_30_cadence "))
        assertNull(MetricKey.parse("derived:"))
        assertNull(MetricKey.parse("derived:Bad-Id"))
    }

    @Test
    fun favouritesCarryDerivedPinsOnlyWhenKnown() {
        val metricCatalog = MetricsTestFiles.catalog
        val known = catalog.metrics.map { "derived:${it.id}" }.toSet()
        val raw = "steps,derived:resting_hr_derived,derived:not_a_metric"
        assertEquals(listOf("steps", "derived:resting_hr_derived"), FavoritePins.resolve(metricCatalog, raw, null, known).favourites)
        // Without the derived catalog the shared rule drops them, as before.
        assertEquals(listOf("steps"), FavoritePins.resolve(metricCatalog, raw, null).favourites)
        // Replacing the health pins keeps derived and nutrient pins.
        val current = listOf(MetricKey.Derived("bedtime"), MetricKey.Health("steps"), MetricKey.Nutrient("iron"))
        assertEquals(
            listOf(MetricKey.Derived("bedtime"), MetricKey.Nutrient("iron"), MetricKey.Health("sleep")),
            FavoritePins.withHealth(current, listOf("sleep"), 12)
        )
    }

    @Test
    fun clockValuesAreMinutesAfterNoonOfThePreviousDay() {
        assertEquals("23:00", DerivedMetricSupport.formatClock(660.0, true, Locale.US))
        assertEquals("07:00", DerivedMetricSupport.formatClock(1140.0, true, Locale.US))
        assertEquals("12:00", DerivedMetricSupport.formatClock(0.0, true, Locale.US))
        assertEquals("7:30 AM", DerivedMetricSupport.formatClock(1170.0, false, Locale.US))
        val bedtime = catalog.byId.getValue("bedtime")
        assertTrue(bedtime.isClock)
        assertEquals("", DerivedMetricSupport.unitLabel(bedtime))
        assertEquals("22:45", DerivedMetricSupport.format(bedtime, 645.0, true, locale = Locale.US))
    }

    @Test
    fun valuesShowUpToTwoDecimalsAndBandLabels() {
        val vo2 = catalog.byId.getValue("vo2max_estimate")
        assertEquals("42.36", DerivedMetricSupport.format(vo2, 42.36, true, locale = Locale.US))
        assertEquals("0.39", DerivedMetricSupport.format(catalog.byId.getValue("sound_dose"), 0.386, true, locale = Locale.US))
        val band = catalog.byId.getValue("step_band")
        val labels = DerivedTestFiles.config.labels["step_band"]
        assertEquals(labels!![3], DerivedMetricSupport.format(band, 3.0, true, labels, Locale.US))
        val rhr = catalog.byId.getValue("resting_hr_derived")
        assertEquals("57.6", DerivedMetricSupport.format(rhr, 57.6, true, locale = Locale.US))
        assertEquals("58", DerivedMetricSupport.format(rhr, 58.0, true, locale = Locale.US))
    }

    @Test
    fun bucketsFollowTheMetricAggregation() {
        val week = DerivedMetricSupport.bounds(MetricRange.W, today, zone, WeekStart.MONDAY, Long.MAX_VALUE)
        assertEquals(7, week.buckets.size)
        val points = listOf(p(today.minusDays(2), 60.0), p(today, 56.0))
        val w = DerivedMetricSupport.series(points, week, DerivedAggregation.AVERAGE, zone)
        assertEquals(60.0, w[0].value) // Monday 14 Sep
        assertEquals(56.0, w[2].value)
        assertNull(w[1].value)

        val year = DerivedMetricSupport.bounds(MetricRange.Y, today, zone, WeekStart.MONDAY, Long.MAX_VALUE)
        val sep = listOf(p(today.minusDays(2), 30.0), p(today.minusDays(1), 10.0), p(today, 20.0))
        val bySum = DerivedMetricSupport.series(sep, year, DerivedAggregation.SUM, zone)
        assertEquals(20.0, bySum[8].value) // mean per day with data
        assertEquals(30.0, DerivedMetricSupport.series(sep, year, DerivedAggregation.MAX, zone)[8].value)
        assertEquals(20.0, DerivedMetricSupport.series(sep, year, DerivedAggregation.LATEST, zone)[8].value)
        assertEquals(10.0, bySum[8].min)

        val day = DerivedMetricSupport.bounds(MetricRange.D, today, zone, WeekStart.MONDAY, Long.MAX_VALUE)
        assertEquals(1, day.buckets.size)
        assertEquals(20.0, DerivedMetricSupport.series(sep, day, DerivedAggregation.AVERAGE, zone).single().value)
    }

    @Test
    fun headlineKinds() {
        val pts = listOf(p(today.minusDays(1), 10.0), p(today, 20.0))
        assertEquals(DerivedHeadline(DerivedHeadline.Kind.AVERAGE, 15.0, 2), DerivedMetricSupport.headline(pts, MetricRange.W, DerivedAggregation.AVERAGE))
        assertEquals(DerivedHeadline.Kind.HIGHEST, DerivedMetricSupport.headline(pts, MetricRange.M, DerivedAggregation.MAX).kind)
        assertEquals(20.0, DerivedMetricSupport.headline(pts, MetricRange.M, DerivedAggregation.MAX).value)
        assertEquals(DerivedHeadline(DerivedHeadline.Kind.LATEST, 20.0, 2), DerivedMetricSupport.headline(pts, MetricRange.Y, DerivedAggregation.LATEST))
        assertEquals(DerivedHeadline.Kind.TOTAL, DerivedMetricSupport.headline(pts.takeLast(1), MetricRange.D, DerivedAggregation.SUM).kind)
        assertNull(DerivedMetricSupport.headline(emptyList(), MetricRange.W, DerivedAggregation.AVERAGE).value)
    }

    @Test
    fun sourceAndConfidence() {
        val native = p(today.minusDays(1), 55.0, kind = "native")
        val low = p(today, 44.0, q = 0.3)
        assertEquals(DerivedSourceMix.NONE, DerivedMetricSupport.sourceMix(emptyList()))
        assertEquals(DerivedSourceMix.NATIVE, DerivedMetricSupport.sourceMix(listOf(native)))
        assertEquals(DerivedSourceMix.DERIVED, DerivedMetricSupport.sourceMix(listOf(low)))
        assertEquals(DerivedSourceMix.MIXED, DerivedMetricSupport.sourceMix(listOf(native, low)))
        assertNull(DerivedMetricSupport.confidence(listOf(native)))
        assertEquals(DerivedConfidence.LOW, DerivedMetricSupport.confidence(listOf(native, low)))
        assertEquals(DerivedConfidence.MEDIUM, DerivedMetricSupport.confidence(listOf(p(today, 1.0, q = 0.6))))
        assertEquals(DerivedConfidence.HIGH, DerivedMetricSupport.confidence(listOf(p(today, 1.0))))
    }

    @Test
    fun categoriesMapToBrowseDomains() {
        val metricCatalog = MetricsTestFiles.catalog
        for (info in catalog.metrics) {
            val category = DerivedMetricSupport.healthCategory(info)
            assertTrue("${info.id} → $category", metricCatalog.categoryDomains.containsKey(category))
            assertFalse(category == "other")
        }
        assertEquals("activity", DerivedMetricSupport.healthCategory(catalog.byId.getValue("tdee")))
    }

    @Test
    fun tileShowsTheNewestDayAndSparkline() {
        val key = MetricKey.Derived("resting_hr_derived")
        val info = catalog.byId.getValue(key.id)
        val tile = MetricTileBuilder.derivedTile(key, info, listOf(p(today.minusDays(3), 58.0), p(today.minusDays(1), 56.4)), today, zone, null, true, Locale.US)
        assertTrue(tile.hasData)
        assertEquals("56.4", tile.number)
        assertEquals("bpm", tile.unit)
        assertEquals(HealthTileUi.CaptionKind.DATE, tile.captionKind)
        assertEquals(7, tile.spark.size)
        assertTrue(tile.spark[3] == 58f && tile.spark[6].isNaN())
        val empty = MetricTileBuilder.derivedTile(key, info, emptyList(), today, zone, null, true, Locale.US)
        assertFalse(empty.hasData)
        assertEquals(HealthTileUi.CaptionKind.TODAY, MetricTileBuilder.derivedTile(key, info, listOf(p(today, 50.0)), today, zone, null, true).captionKind)
    }
}
