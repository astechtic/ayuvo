package com.ayuvo.health.nutrients

import com.ayuvo.health.data.metrics.AppMetricAggregator
import com.ayuvo.health.data.metrics.AppMetricId
import com.ayuvo.health.data.metrics.AppMetricSnapshot
import com.ayuvo.health.data.metrics.MetricRange
import com.ayuvo.health.models.FoodEntry
import com.ayuvo.health.models.FoodSource
import com.ayuvo.health.models.OptionalNutrient
import com.ayuvo.health.models.OptionalNutrientGoals
import com.ayuvo.health.models.SupplementalNutrient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/** The one food + supplement totals path (docs/nutrients.md §6). */
class NutrientTotalsTest {
    private val zone = ZoneId.of("Asia/Kolkata")
    private val day = LocalDate.of(2026, 9, 22)
    private fun at(hour: Int) = day.atTime(hour, 0).atZone(zone).toInstant()

    @Before
    fun install() = NutrientsTestFiles.install()

    private fun food(hour: Int, vitaminD: Double? = null, calories: Int = 300, creatine: Double? = null) = FoodEntry(
        name = "Meal", calories = calories, protein = 20.0, carbs = 30.0, fat = 10.0, timestamp = at(hour), source = FoodSource.MANUAL,
        vitaminD = vitaminD, supplementalNutrients = creatine?.let { mapOf(SupplementalNutrient.CREATINE.storageKey to it) }.orEmpty()
    )

    private val d3 = listOf(MedicationNutrientRow("d3", "vitamin_d", 1500.0))

    @Test
    fun weeklySixtyThousandIuAddsOnlyTakenDoses() {
        val converted = Nutrients.convertAmount(60000.0, "IU", "vitamin_d")
        assertEquals(1500.0, converted.amount!!, 0.0)
        val doses = listOf(
            SupplementDose("d3", "taken", at(9).toEpochMilli(), 1.0),
            SupplementDose("d3", "skipped", at(9).plusSeconds(7 * 86_400).toEpochMilli(), 1.0),
            SupplementDose("d3", "missed", null, 1.0),
            SupplementDose("d3", "snoozed", at(10).toEpochMilli(), 1.0)
        )
        val totals = NutrientTotals(listOf(food(13, vitaminD = 2.5)), SupplementSnapshot(d3, doses), zone)
        val a = totals.total("vitamin_d", day)
        assertEquals(2.5, a.food!!, 0.0)
        assertEquals(1500.0, a.supplements!!, 0.0)
        assertEquals(1502.5, a.total!!, 0.0)
        // The skipped week adds nothing.
        assertEquals(NutrientAmount.NONE, totals.total("vitamin_d", day.plusDays(7)))
    }

    @Test
    fun supplementsNeverAddCaloriesOrMacros() {
        val rows = d3 + MedicationNutrientRow("d3", "creatine", 5.0)
        val totals = NutrientTotals(listOf(food(8, calories = 400)), SupplementSnapshot(rows, listOf(SupplementDose("d3", "taken", at(9).toEpochMilli(), 2.0))), zone)
        assertEquals(400.0, totals.total(NutrientFields.CALORIES, day).total!!, 0.0)
        assertNull(totals.total(NutrientFields.CALORIES, day).supplements)
        assertEquals(20.0, totals.total(NutrientFields.PROTEIN, day).total!!, 0.0)
        assertEquals(10.0, totals.total("creatine", day).supplements!!, 0.0)
        assertEquals(3000.0, totals.total("vitamin_d", day).supplements!!, 0.0)
    }

    @Test
    fun missingNutrientStaysNullNeverZero() {
        val totals = NutrientTotals(listOf(food(8)), SupplementSnapshot.EMPTY, zone)
        val a = totals.total("iron", day)
        assertNull(a.food)
        assertNull(a.total)
        assertEquals(NutrientAmount.NONE, totals.total("iron", day.minusDays(1)))
        assertEquals(1.5, NutrientTotals(listOf(food(8, creatine = 1.5)), SupplementSnapshot.EMPTY, zone).total("creatine", day).total!!, 0.0)
    }

    @Test
    fun nutrientSeriesIsFoodPlusSupplementsBucketedLikeOtherMetrics() {
        val snap = AppMetricSnapshot(
            food = listOf(food(8, vitaminD = 5.0)),
            supplements = SupplementSnapshot(d3, listOf(SupplementDose("d3", "taken", at(9).toEpochMilli(), 1.0)))
        )
        val entries = AppMetricAggregator.nutrientEntries("vitamin_d", snap)
        assertEquals(listOf(5.0, 1500.0), entries.map { it.value })
        val nowMs = at(23).toEpochMilli()
        val buckets = com.ayuvo.health.data.metrics.MetricsReference.bucketSeries(
            entries, MetricRange.W, day.atStartOfDay(zone).toInstant().toEpochMilli(), zone,
            com.ayuvo.health.data.metrics.WeekStart.MONDAY, com.ayuvo.health.data.metrics.MetricAggregation.SUM
        )
        assertEquals(1505.0, buckets.mapNotNull { it.value }.sum(), 0.0)
        assertTrue(nowMs > 0)
        // Calories stay food only.
        assertEquals(listOf(300.0), AppMetricAggregator.entries(AppMetricId.CALORIES, snap, nowMs, zone).map { it.value })
    }

    @Test
    fun loggedDaysCountFoodAndAnyTakenDose() {
        val snap = SupplementSnapshot(d3, emptyList(), listOf(at(9).plusSeconds(86_400).toEpochMilli()))
        val days = NutrientTotals.loggedDays(listOf(food(8)), snap, zone)
        assertEquals(setOf("2026-09-22", "2026-09-23"), days)
    }

    @Test
    fun chartRulesFollowStyleAndHideOnDay() {
        val target = Nutrients.referenceLines("vitamin_d", NutrientProfile(age = 40.0, sex = "female"))
        assertEquals(emptyList<NutrientRule>(), NutrientChartRules.rules(target, MetricRange.D))
        assertEquals(
            listOf(NutrientRule(15.0, NutrientRuleKind.RECOMMENDED), NutrientRule(100.0, NutrientRuleKind.UPPER_LIMIT)),
            NutrientChartRules.rules(target, MetricRange.W)
        )
        assertEquals(15.0, NutrientChartRules.dayTarget(target)!!, 0.0)
        val sodium = Nutrients.referenceLines("sodium", NutrientProfile())
        assertEquals(listOf(NutrientRule(2300.0, NutrientRuleKind.LIMIT)), NutrientChartRules.rules(sodium, MetricRange.M))
        val sugar = Nutrients.referenceLines("sugar", NutrientProfile())
        assertTrue(NutrientChartRules.rules(sugar, MetricRange.Y).isEmpty())
        val sugarGoal = Nutrients.referenceLines("sugar", NutrientProfile(), 40.0)
        assertEquals(listOf(NutrientRule(40.0, NutrientRuleKind.GOAL)), NutrientChartRules.rules(sugarGoal, MetricRange.SIX_MONTHS))
        val custom = Nutrients.referenceLines("vitamin_d", NutrientProfile(), 50.0)
        assertEquals(NutrientRuleKind.GOAL, NutrientChartRules.rules(custom, MetricRange.W).first().kind)
    }

    @Test
    fun personalisedDefaultsTreatTheOldFixedDefaultAsNotCustomised() {
        val goals = OptionalNutrientGoals.Default
        val female40 = NutrientProfile(age = 40.0, sex = "female", calorieGoal = 1800.0)
        // Legacy defaults (vitamin D 20, iron 18, sugar 50) are not custom goals.
        assertNull(goals.customGoal(OptionalNutrient.VITAMIN_D))
        assertEquals(15, goals.effectiveGoal(OptionalNutrient.VITAMIN_D, female40))
        assertEquals(18, goals.effectiveGoal(OptionalNutrient.IRON, female40))
        assertEquals(8, goals.effectiveGoal(OptionalNutrient.IRON, NutrientProfile(age = 60.0, sex = "female")))
        assertEquals(20, goals.effectiveGoal(OptionalNutrient.SATURATED_FAT, female40))
        // Info style: no default goal; a custom one is kept.
        assertNull(goals.effectiveGoal(OptionalNutrient.SUGAR, female40))
        assertNull(goals.effectiveGoal(OptionalNutrient.CHOLESTEROL, female40))
        assertNull(goals.effectiveGoal(OptionalNutrient.TRANS_FAT, female40))
        assertEquals(40, goals.withValue(OptionalNutrient.SUGAR, 40).effectiveGoal(OptionalNutrient.SUGAR, female40))
        assertEquals(25, goals.withValue(OptionalNutrient.VITAMIN_D, 25).effectiveGoal(OptionalNutrient.VITAMIN_D, female40))
        // Sports supplements: no default, custom goals only.
        assertNull(goals.effectiveGoal(OptionalNutrient.CREATINE, female40))
        assertEquals(5, goals.withValue(OptionalNutrient.CREATINE, 5).effectiveGoal(OptionalNutrient.CREATINE, female40))
        // Every optional nutrient maps to a reference or sports key.
        for (n in OptionalNutrient.entries) assertTrue(n.name, NutrientsTestFiles.reference.unitOf(n.referenceKey) != null)
    }

    @Test
    fun formatShowsDashForMissing() {
        assertEquals("—", NutrientFormat.amount(null))
        assertEquals("1,500", NutrientFormat.amount(1500.0, java.util.Locale.US))
        assertEquals("2.5", NutrientFormat.amount(2.5, java.util.Locale.US))
        assertEquals("12.5 mcg", NutrientFormat.withUnit(12.5, "mcg", java.util.Locale.US))
    }
}
