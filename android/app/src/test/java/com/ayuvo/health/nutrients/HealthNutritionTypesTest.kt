package com.ayuvo.health.nutrients

import com.ayuvo.health.data.metrics.MetricKey
import com.ayuvo.health.data.metrics.MetricRange
import com.ayuvo.health.medications.logic.ArchiveCodec
import com.ayuvo.health.medications.model.NutrientInputRow
import com.ayuvo.health.metrics.MetricsTestFiles
import com.ayuvo.health.models.Gender
import com.ayuvo.health.models.HealthDataType
import com.ayuvo.health.models.OptionalNutrientGoals
import com.ayuvo.health.models.UserProfile
import com.ayuvo.health.ui.health.HealthGoalUi
import com.ayuvo.health.ui.metrics.MetricCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Health nutrition types (docs/nutrients.md §5a, docs/ui-structure.md §4 "Health nutrition types"):
 * `dietary_*` details resolve to the same reference lines, About and guide as `nutrient:` charts;
 * untracked nutrients (copper) have lines, a supplements-only app chart and are supplement nutrients.
 */
class HealthNutritionTypesTest {
    private val catalog = MetricsTestFiles.catalog

    /** 35-year-old woman (band 31-50) with a 2,000 kcal goal. */
    private val profile = UserProfile(
        gender = Gender.FEMALE,
        birthday = ZonedDateTime.now(ZoneId.systemDefault()).minusYears(35).minusDays(10).toInstant(),
        customCalories = 2000
    )

    @Before
    fun install() = NutrientsTestFiles.install()

    private fun resolve(id: String) = MetricCatalog.resolve(catalog, MetricKey.Health(id))

    @Test
    fun vitaminDHealthTypeGetsTheNutrientLines() {
        val r = resolve("dietary_vitamin_d")
        assertEquals("vitamin_d", r.nutrientKey)
        assertEquals("vitamin-d", r.learnSlug)
        assertEquals("nutrient:vitamin_d", r.nutrientMetric)
        assertEquals(true, r.foodTracked)
        val g = HealthGoalUi.resolve(r, profile, OptionalNutrientGoals())
        assertTrue(g.isNutrient)
        assertEquals(MetricKey.Nutrient("vitamin_d"), g.nutrientMetric)
        assertNull(g.goal)
        val lines = g.lines!!
        assertEquals(Nutrients.referenceLines("vitamin_d", NutrientFields.profile(profile)), lines)
        assertEquals("31-50", lines.band)
        assertEquals(15.0, lines.recommended!!, 0.0)
        assertEquals(100.0, lines.upperLimit!!, 0.0)
        for (range in listOf(MetricRange.W, MetricRange.M, MetricRange.SIX_MONTHS, MetricRange.Y)) {
            assertEquals(range.raw, listOf(NutrientRuleKind.RECOMMENDED, NutrientRuleKind.UPPER_LIMIT), NutrientChartRules.rules(lines, range).map { it.kind })
        }
        assertTrue(NutrientChartRules.rules(lines, MetricRange.D).isEmpty())
        assertEquals(15.0, NutrientChartRules.dayTarget(lines)!!, 0.0)
    }

    @Test
    fun trackedCustomGoalReplacesRecommended() {
        val goals = OptionalNutrientGoals(vitaminD = 25)
        val g = HealthGoalUi.resolve(resolve("dietary_vitamin_d"), profile, goals)
        assertEquals(25, g.customGoal)
        assertEquals(25.0, g.lines!!.recommended!!, 0.0)
        assertTrue(g.lines!!.recommendedIsGoal)
        assertEquals(15.0, g.lines!!.referenceRecommended!!, 0.0)
    }

    @Test
    fun copperHasLinesGuideAndASupplementsOnlyAppChart() {
        val r = resolve("dietary_copper")
        assertEquals("health", r.source)
        assertEquals("nutrient.reference", r.goalSource)
        assertEquals("copper", r.nutrientKey)
        assertEquals("copper", r.learnSlug)
        assertEquals("nutrient:copper", r.nutrientMetric)
        assertEquals(false, r.foodTracked)
        val g = HealthGoalUi.resolve(r, profile, OptionalNutrientGoals())
        assertEquals(MetricKey.Nutrient("copper"), g.nutrientMetric)
        assertEquals(false, g.foodTracked)
        assertNull(g.customGoal)
        val lines = g.lines!!
        assertNull(lines.error)
        assertEquals(0.9, lines.recommended!!, 0.0)
        assertEquals(10.0, lines.upperLimit!!, 0.0)
        assertEquals(2, NutrientChartRules.rules(lines, MetricRange.W).size)
        assertEquals("mg", HealthDataType.byId("dietary_copper")!!.unit)
    }

    @Test
    fun macroTypesUseTheProfileGoal() {
        val energy = resolve("dietary_energy")
        assertEquals("profile.calories", energy.goalSource)
        assertNull(energy.nutrientKey)
        assertNull(energy.learnSlug)
        assertNull(energy.nutrientMetric)
        assertNull(energy.foodTracked)
        val g = HealthGoalUi.resolve(energy, profile, OptionalNutrientGoals())
        assertFalse(g.isNutrient)
        assertNull(g.lines)
        assertEquals(2000.0, g.goal!!, 0.0)
        assertEquals(profile.effectiveProtein.toDouble(), HealthGoalUi.resolve(resolve("dietary_protein"), profile, OptionalNutrientGoals()).goal!!, 0.0)
        assertEquals(profile.effectiveCarbs.toDouble(), HealthGoalUi.resolve(resolve("dietary_carbohydrates"), profile, OptionalNutrientGoals()).goal!!, 0.0)
        assertEquals(profile.effectiveFat.toDouble(), HealthGoalUi.resolve(resolve("dietary_fat_total"), profile, OptionalNutrientGoals()).goal!!, 0.0)
        assertNull(HealthGoalUi.resolve(resolve("heart_rate"), profile, OptionalNutrientGoals()).goal)
    }

    @Test
    fun everyDietaryTypeResolvesToItsReferenceNutrient() {
        val ref = NutrientsTestFiles.reference
        val dietary = HealthDataType.entries.filter { it.id.startsWith("dietary_") }
        assertTrue(dietary.isNotEmpty())
        val macros = setOf("dietary_energy", "dietary_protein", "dietary_carbohydrates", "dietary_fat_total")
        for (t in dietary) {
            val r = resolve(t.id)
            if (t.id in macros) {
                assertNull(t.id, r.nutrientKey)
                continue
            }
            val spec = ref.byHealthType[t.id]
            assertNotNull(t.id, spec)
            assertEquals(t.id, spec!!.key, r.nutrientKey)
            assertEquals(t.id, spec.slug, r.learnSlug)
            assertEquals(t.id, "nutrient:${spec.key}", r.nutrientMetric)
            assertEquals(t.id, spec.appTracked, r.foodTracked)
            assertEquals(t.id, spec.unit, t.unit)
        }
    }

    @Test
    fun everyReferenceNutrientIsASupplementNutrient() {
        val ref = NutrientsTestFiles.reference
        assertEquals("mg", ref.unitOf("copper"))
        assertEquals("mcg", ref.unitOf("iodine"))
        assertTrue("copper" in ref.supplementKeys)
        assertEquals(ref.nutrients.map { it.key } + ref.sports.map { it.key }, ref.supplementKeys)
        assertEquals(45, ref.supplementKeys.size)
        // The food log still does not record copper.
        assertFalse("copper" in NutrientFields.REFERENCE_KEYS)
        assertFalse(ref.foodTracked("copper"))
        assertTrue(ref.foodTracked("zinc"))
        assertTrue(ref.foodTracked("creatine"))
        assertFalse(ref.foodTracked("grape_seed_extract"))
        assertEquals(1.7, Nutrients.convertAmount(1.7, "mg", "copper").amount!!, 0.0)
        assertEquals(null, NutrientInputRow(key = "thiamin", amount = "1.4", unit = "mg").convert().error)
        assertNull(ArchiveCodec.nutrientProblem("copper", 1.0))
        assertEquals("unknown_nutrient", ArchiveCodec.nutrientProblem("grape_seed_extract", 1.0))
        val parsed = NutrientLabel.parse("""{"items": [{"key": "copper", "amount": 2, "unit": "mg"}, {"key": "grape_seed_extract", "amount": 11, "unit": "mg"}]}""")
        assertEquals(listOf("copper"), parsed.items.map { it.key })
        assertEquals(listOf(LabelRejection(1, "unknown_nutrient")), parsed.rejected)
        // The supplement picker lists every key, grouped Vitamins / Minerals / Other / Sports.
        val picker = NutrientFields.supplementPickerKeys()
        assertEquals(ref.supplementKeys.toSet(), picker.toSet())
        assertEquals(45, picker.size)
        assertEquals(NutrientFields.PickerGroup.entries, picker.map { NutrientFields.supplementPickerGroup(it) }.distinct())
        assertTrue(picker.indexOf("thiamin") < picker.indexOf("copper"))
        // The reference itself still knows copper for its lines and default goal.
        assertEquals(0.9, Nutrients.defaultGoal("copper", NutrientFields.profile(profile))!!, 0.0)
        assertEquals(23, ref.trackedNutrients.size)
        assertEquals(37, ref.nutrients.size)
    }
}
