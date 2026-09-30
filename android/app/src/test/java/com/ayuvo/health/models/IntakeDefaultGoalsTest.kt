package com.ayuvo.health.models

import com.ayuvo.health.data.intake.IntakeDefaults
import com.ayuvo.health.intake.IntakeTestFiles
import com.ayuvo.health.nutrients.NutrientProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Optional nutrient DEFAULT goals from `dri_goals` (docs/intake-metrics.md §3). */
class IntakeDefaultGoalsTest {
    private val cfg = IntakeTestFiles.config

    @Test
    fun ironDefaultsBySexAndAge() {
        assertEquals(8, IntakeDefaults.defaultGoal(OptionalNutrient.IRON, NutrientProfile(age = 34.0, sex = "male"), cfg))
        assertEquals(18, IntakeDefaults.defaultGoal(OptionalNutrient.IRON, NutrientProfile(age = 34.0, sex = "female"), cfg))
        assertEquals(8, IntakeDefaults.defaultGoal(OptionalNutrient.IRON, NutrientProfile(age = 60.0, sex = "female"), cfg))
        // "Other" takes the higher of the two.
        assertEquals(18, IntakeDefaults.defaultGoal(OptionalNutrient.IRON, NutrientProfile(age = 34.0, sex = null), cfg))
        // Under-19s use the 19-30 band for now.
        assertEquals(400, IntakeDefaults.defaultGoal(OptionalNutrient.MAGNESIUM, NutrientProfile(age = 17.0, sex = "male"), cfg))
    }

    @Test
    fun onlyDriNutrientsWithAnAgeGetADriDefault() {
        assertNull(IntakeDefaults.defaultGoal(OptionalNutrient.SODIUM, NutrientProfile(age = 34.0, sex = "male"), cfg))
        assertNull(IntakeDefaults.defaultGoal(OptionalNutrient.IRON, NutrientProfile(age = null, sex = "male"), cfg))
        assertNull(IntakeDefaults.defaultGoal(OptionalNutrient.IRON, NutrientProfile(age = 34.0, sex = "male"), null))
        assertEquals(IntakeDefaults.OPTIONAL.keys, cfg.driGoals.keys)
    }

    @Test
    fun aCustomGoalStillWins() {
        val goals = OptionalNutrientGoals.Default.withValue(OptionalNutrient.IRON, 12)
        assertEquals(12, goals.effectiveGoal(OptionalNutrient.IRON, NutrientProfile(age = 34.0, sex = "male")))
    }
}
