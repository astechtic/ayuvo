package com.ayuvo.health.data.intake

import com.ayuvo.health.models.OptionalNutrient
import com.ayuvo.health.nutrients.NutrientProfile
import com.ayuvo.health.nutrients.Nutrients
import kotlin.math.floor

/**
 * Default nutrient goals from `dri_goals` (docs/intake-metrics.md §3): NASEM DRIs for the person's sex and age band.
 * Only DEFAULTS come from here; a goal the person set themselves always wins (OptionalNutrientGoals.customGoal).
 * DRI keys carry their unit (`iron_mg`); the shared nutrient key is the part before the unit (`iron`).
 */
object IntakeDefaults {

    /** `dri.goals` keys → the optional nutrient whose default they provide. */
    val OPTIONAL: Map<String, OptionalNutrient> = linkedMapOf(
        "calcium_mg" to OptionalNutrient.CALCIUM,
        "fiber_g" to OptionalNutrient.FIBER,
        "folate_mcg" to OptionalNutrient.FOLATE,
        "iron_mg" to OptionalNutrient.IRON,
        "magnesium_mg" to OptionalNutrient.MAGNESIUM,
        "potassium_mg" to OptionalNutrient.POTASSIUM,
        "vitamin_a_mcg" to OptionalNutrient.VITAMIN_A,
        "vitamin_b12_mcg" to OptionalNutrient.VITAMIN_B12,
        "vitamin_c_mg" to OptionalNutrient.VITAMIN_C,
        "vitamin_d_mcg" to OptionalNutrient.VITAMIN_D,
        "vitamin_e_mg" to OptionalNutrient.VITAMIN_E,
        "vitamin_k_mcg" to OptionalNutrient.VITAMIN_K,
        "zinc_mg" to OptionalNutrient.ZINC
    )

    private val BY_NUTRIENT: Map<OptionalNutrient, String> = OPTIONAL.entries.associate { it.value to it.key }

    /** `iron_mg` → `iron` (the key of nutrient_reference.json, NutrientFields and supplement rows). */
    fun referenceKey(driKey: String): String = driKey.substringBeforeLast('_')

    /** `iron` → `iron_mg` for any DRI goal or upper-limit key of [cfg]. */
    fun driKey(referenceKey: String, cfg: IntakeConfig): String? =
        (cfg.driGoals.keys + cfg.driUpper.keys).firstOrNull { referenceKey(it) == referenceKey }

    fun driKey(nutrient: OptionalNutrient): String? = BY_NUTRIENT[nutrient]

    /** `dri_goals` for [profile]; the age is floored to whole years like the nutrient reference. */
    fun goals(profile: NutrientProfile, cfg: IntakeConfig): DriGoalsResult =
        IntakeNutrientGoals.driGoals(DriGoalsInput(profile.sex, profile.age?.let { floor(it) }), cfg)

    /**
     * The default goal of [nutrient] for [profile] as a whole number (half-up, like `default_goal_int`); null when the
     * nutrient has no DRI, the profile has no age or the intake config is not loaded (callers then fall back to the
     * nutrient reference).
     */
    fun defaultGoal(nutrient: OptionalNutrient, profile: NutrientProfile, cfg: IntakeConfig? = IntakeConfig.active): Int? {
        val c = cfg ?: return null
        val key = driKey(nutrient) ?: return null
        if (profile.age == null) return null
        return Nutrients.defaultGoalInt(goals(profile, c).goals[key])
    }
}
