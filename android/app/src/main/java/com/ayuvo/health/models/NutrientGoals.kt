package com.ayuvo.health.models

import com.ayuvo.health.R
import com.ayuvo.health.nutrients.NutrientFields
import com.ayuvo.health.nutrients.NutrientProfile
import com.ayuvo.health.nutrients.NutrientReference
import com.ayuvo.health.nutrients.Nutrients

import kotlinx.serialization.Serializable

enum class HomeTopNutrient(
    val storageKey: String,
    val displayName: String,
    val unit: String,
    val displayNameRes: Int,
    val unitRes: Int
) {
    PROTEIN("protein", "Protein", "g", R.string.nutrition_label_protein, R.string.unit_g),
    CARBS("carbs", "Carbs", "g", R.string.nutrition_label_carbs, R.string.unit_g),
    FAT("fat", "Fat", "g", R.string.nutrition_label_fat, R.string.unit_g),
    FIBER("fiber", "Fiber", "g", R.string.nutrition_label_fiber, R.string.unit_g),
    SUGAR("sugar", "Sugar", "g", R.string.nutrition_label_sugar, R.string.unit_g),
    ADDED_SUGAR("addedSugar", "Added Sugar", "g", R.string.nutrition_label_added_sugar, R.string.unit_g),
    SATURATED_FAT("saturatedFat", "Sat Fat", "g", R.string.nutrient_short_sat_fat, R.string.unit_g),
    CHOLESTEROL("cholesterol", "Cholesterol", "mg", R.string.nutrition_label_cholesterol, R.string.unit_mg),
    CAFFEINE("caffeine", "Caffeine", "mg", R.string.nutrition_label_caffeine, R.string.unit_mg),
    SODIUM("sodium", "Sodium", "mg", R.string.nutrition_label_sodium, R.string.unit_mg),
    POTASSIUM("potassium", "Potassium", "mg", R.string.nutrition_label_potassium, R.string.unit_mg),
    TRANS_FAT("transFat", "Trans Fat", "g", R.string.nutrition_label_trans_fat, R.string.unit_g),
    CALCIUM("calcium", "Calcium", "mg", R.string.nutrition_label_calcium, R.string.unit_mg),
    IRON("iron", "Iron", "mg", R.string.nutrition_label_iron, R.string.unit_mg),
    MAGNESIUM("magnesium", "Magnesium", "mg", R.string.nutrition_label_magnesium, R.string.unit_mg),
    ZINC("zinc", "Zinc", "mg", R.string.nutrition_label_zinc, R.string.unit_mg),
    VITAMIN_A("vitaminA", "Vit A", "mcg", R.string.nutrient_short_vit_a, R.string.unit_mcg),
    VITAMIN_C("vitaminC", "Vit C", "mg", R.string.nutrient_short_vit_c, R.string.unit_mg),
    VITAMIN_D("vitaminD", "Vit D", "mcg", R.string.nutrient_short_vit_d, R.string.unit_mcg),
    VITAMIN_B12("vitaminB12", "B12", "mcg", R.string.nutrient_short_b12, R.string.unit_mcg),
    VITAMIN_E("vitaminE", "Vit E", "mg", R.string.nutrient_short_vit_e, R.string.unit_mg),
    VITAMIN_K("vitaminK", "Vit K", "mcg", R.string.nutrient_short_vit_k, R.string.unit_mcg),
    FOLATE("folate", "Folate", "mcg", R.string.nutrition_label_folate, R.string.unit_mcg),
    OMEGA3("omega3", "Omega", "g", R.string.nutrient_short_omega, R.string.unit_g),
    CREATINE("creatine", "Creatine", "g", R.string.nutrition_label_creatine, R.string.unit_g),
    BETA_ALANINE("betaAlanine", "Beta-Alanine", "g", R.string.nutrition_label_beta_alanine, R.string.unit_g),
    L_CITRULLINE("lCitrulline", "L-Citrulline", "g", R.string.nutrition_label_l_citrulline, R.string.unit_g),
    L_CARNITINE("lCarnitine", "L-Carnitine", "g", R.string.nutrition_label_l_carnitine, R.string.unit_g),
    L_ARGININE("lArginine", "L-Arginine", "g", R.string.nutrition_label_l_arginine, R.string.unit_g),
    TAURINE("taurine", "Taurine", "g", R.string.nutrition_label_taurine, R.string.unit_g),
    BETAINE("betaine", "Betaine", "g", R.string.nutrition_label_betaine, R.string.unit_g),
    HMB("hmb", "HMB", "g", R.string.nutrition_label_hmb, R.string.unit_g);

    val isMacro: Boolean get() = this == PROTEIN || this == CARBS || this == FAT

    /** Shared nutrient key (docs/nutrients.md); the macros use `protein` / `carbs` / `fat`. */
    val referenceKey: String get() = when (this) {
        PROTEIN -> "protein"
        CARBS -> "carbs"
        FAT -> "fat"
        else -> OptionalNutrient.valueOf(name).referenceKey
    }

    /**
     * Today's goal: the profile's macro targets, else the user's custom goal, else the personalised
     * default (`default_goal_int`); null when the nutrient has none (info style, sports without a goal).
     */
    fun goal(profile: UserProfile?, optionalGoals: OptionalNutrientGoals): Int? = when (this) {
        PROTEIN -> profile?.effectiveProtein ?: 150
        CARBS -> profile?.effectiveCarbs ?: 220
        FAT -> profile?.effectiveFat ?: 70
        else -> optionalGoals.effectiveGoal(OptionalNutrient.valueOf(name), NutrientFields.profile(profile))
    }

    companion object {
        val DefaultSelection = listOf(PROTEIN, CARBS, FAT, FIBER)
        val DefaultStorageValue = DefaultSelection.joinToString(",") { it.storageKey }

        fun fromStorage(raw: String?): List<HomeTopNutrient> {
            val selected = raw
                ?.split(",")
                ?.mapNotNull { part ->
                    val key = part.trim()
                    values().firstOrNull { it.storageKey == key || it.name == key }
                }
                .orEmpty()
            return normalized(selected)
        }

        fun toStorage(selection: List<HomeTopNutrient>): String =
            normalized(selection).joinToString(",") { it.storageKey }

        fun normalized(selection: List<HomeTopNutrient>): List<HomeTopNutrient> =
            selection.distinct().take(4).ifEmpty { DefaultSelection }
    }
}

enum class OptionalNutrient(
    val displayName: String,
    val unit: String,
    val defaultGoal: Int,
    val displayNameRes: Int,
    val unitRes: Int
) {
    SUGAR("Sugar", "g", 50, R.string.nutrition_label_sugar, R.string.unit_g),
    ADDED_SUGAR("Added Sugar", "g", 25, R.string.nutrition_label_added_sugar, R.string.unit_g),
    FIBER("Fiber", "g", 30, R.string.nutrition_label_fiber, R.string.unit_g),
    SATURATED_FAT("Saturated Fat", "g", 20, R.string.nutrition_label_saturated_fat, R.string.unit_g),
    CHOLESTEROL("Cholesterol", "mg", 300, R.string.nutrition_label_cholesterol, R.string.unit_mg),
    CAFFEINE("Caffeine", "mg", 400, R.string.nutrition_label_caffeine, R.string.unit_mg),
    SODIUM("Sodium", "mg", 2300, R.string.nutrition_label_sodium, R.string.unit_mg),
    POTASSIUM("Potassium", "mg", 3500, R.string.nutrition_label_potassium, R.string.unit_mg),
    TRANS_FAT("Trans Fat", "g", 0, R.string.nutrition_label_trans_fat, R.string.unit_g),
    CALCIUM("Calcium", "mg", 1000, R.string.nutrition_label_calcium, R.string.unit_mg),
    IRON("Iron", "mg", 18, R.string.nutrition_label_iron, R.string.unit_mg),
    MAGNESIUM("Magnesium", "mg", 400, R.string.nutrition_label_magnesium, R.string.unit_mg),
    ZINC("Zinc", "mg", 11, R.string.nutrition_label_zinc, R.string.unit_mg),
    VITAMIN_A("Vitamin A", "mcg", 900, R.string.nutrition_label_vitamin_a, R.string.unit_mcg),
    VITAMIN_C("Vitamin C", "mg", 90, R.string.nutrition_label_vitamin_c, R.string.unit_mg),
    VITAMIN_D("Vitamin D", "mcg", 20, R.string.nutrition_label_vitamin_d, R.string.unit_mcg),
    VITAMIN_B12("Vitamin B12", "mcg", 3, R.string.nutrition_label_vitamin_b12, R.string.unit_mcg),
    VITAMIN_E("Vitamin E", "mg", 15, R.string.nutrition_label_vitamin_e, R.string.unit_mg),
    VITAMIN_K("Vitamin K", "mcg", 120, R.string.nutrition_label_vitamin_k, R.string.unit_mcg),
    FOLATE("Folate", "mcg", 400, R.string.nutrition_label_folate, R.string.unit_mcg),
    OMEGA3("Omega-3", "g", 2, R.string.nutrition_label_omega3, R.string.unit_g),
    CREATINE("Creatine", "g", 0, R.string.nutrition_label_creatine, R.string.unit_g),
    BETA_ALANINE("Beta-Alanine", "g", 0, R.string.nutrition_label_beta_alanine, R.string.unit_g),
    L_CITRULLINE("L-Citrulline", "g", 0, R.string.nutrition_label_l_citrulline, R.string.unit_g),
    L_CARNITINE("L-Carnitine", "g", 0, R.string.nutrition_label_l_carnitine, R.string.unit_g),
    L_ARGININE("L-Arginine", "g", 0, R.string.nutrition_label_l_arginine, R.string.unit_g),
    TAURINE("Taurine", "g", 0, R.string.nutrition_label_taurine, R.string.unit_g),
    BETAINE("Betaine", "g", 0, R.string.nutrition_label_betaine, R.string.unit_g),
    HMB("HMB", "g", 0, R.string.nutrition_label_hmb, R.string.unit_g);

    /**
     * Shared nutrient key (`nutrient_reference.json` / sports keys). [defaultGoal] is the legacy fixed
     * default: a stored goal equal to it counts as "not customised" (docs/nutrients.md §4.3).
     */
    val referenceKey: String get() = when (this) {
        OMEGA3 -> "omega_3"
        else -> name.lowercase()
    }
}

@Serializable
data class OptionalNutrientGoals(
    val sugar: Int = OptionalNutrient.SUGAR.defaultGoal,
    val addedSugar: Int = OptionalNutrient.ADDED_SUGAR.defaultGoal,
    val fiber: Int = OptionalNutrient.FIBER.defaultGoal,
    val saturatedFat: Int = OptionalNutrient.SATURATED_FAT.defaultGoal,
    val cholesterol: Int = OptionalNutrient.CHOLESTEROL.defaultGoal,
    val caffeine: Int = OptionalNutrient.CAFFEINE.defaultGoal,
    val sodium: Int = OptionalNutrient.SODIUM.defaultGoal,
    val potassium: Int = OptionalNutrient.POTASSIUM.defaultGoal,
    val transFat: Int = OptionalNutrient.TRANS_FAT.defaultGoal,
    val calcium: Int = OptionalNutrient.CALCIUM.defaultGoal,
    val iron: Int = OptionalNutrient.IRON.defaultGoal,
    val magnesium: Int = OptionalNutrient.MAGNESIUM.defaultGoal,
    val zinc: Int = OptionalNutrient.ZINC.defaultGoal,
    val vitaminA: Int = OptionalNutrient.VITAMIN_A.defaultGoal,
    val vitaminC: Int = OptionalNutrient.VITAMIN_C.defaultGoal,
    val vitaminD: Int = OptionalNutrient.VITAMIN_D.defaultGoal,
    val vitaminB12: Int = OptionalNutrient.VITAMIN_B12.defaultGoal,
    val vitaminE: Int = OptionalNutrient.VITAMIN_E.defaultGoal,
    val vitaminK: Int = OptionalNutrient.VITAMIN_K.defaultGoal,
    val folate: Int = OptionalNutrient.FOLATE.defaultGoal,
    val omega3: Int = OptionalNutrient.OMEGA3.defaultGoal,
    val supplementalNutrients: Map<String, Int> = emptyMap()
) {
    fun valueFor(nutrient: OptionalNutrient): Int = when (nutrient) {
        OptionalNutrient.SUGAR -> sugar
        OptionalNutrient.ADDED_SUGAR -> addedSugar
        OptionalNutrient.FIBER -> fiber
        OptionalNutrient.SATURATED_FAT -> saturatedFat
        OptionalNutrient.CHOLESTEROL -> cholesterol
        OptionalNutrient.CAFFEINE -> caffeine
        OptionalNutrient.CREATINE -> supplementalNutrients[SupplementalNutrient.CREATINE.storageKey] ?: nutrient.defaultGoal
        OptionalNutrient.BETA_ALANINE -> supplementalNutrients[SupplementalNutrient.BETA_ALANINE.storageKey] ?: nutrient.defaultGoal
        OptionalNutrient.L_CITRULLINE -> supplementalNutrients[SupplementalNutrient.L_CITRULLINE.storageKey] ?: nutrient.defaultGoal
        OptionalNutrient.L_CARNITINE -> supplementalNutrients[SupplementalNutrient.L_CARNITINE.storageKey] ?: nutrient.defaultGoal
        OptionalNutrient.L_ARGININE -> supplementalNutrients[SupplementalNutrient.L_ARGININE.storageKey] ?: nutrient.defaultGoal
        OptionalNutrient.TAURINE -> supplementalNutrients[SupplementalNutrient.TAURINE.storageKey] ?: nutrient.defaultGoal
        OptionalNutrient.BETAINE -> supplementalNutrients[SupplementalNutrient.BETAINE.storageKey] ?: nutrient.defaultGoal
        OptionalNutrient.HMB -> supplementalNutrients[SupplementalNutrient.HMB.storageKey] ?: nutrient.defaultGoal
        OptionalNutrient.SODIUM -> sodium
        OptionalNutrient.POTASSIUM -> potassium
        OptionalNutrient.TRANS_FAT -> transFat
        OptionalNutrient.CALCIUM -> calcium
        OptionalNutrient.IRON -> iron
        OptionalNutrient.MAGNESIUM -> magnesium
        OptionalNutrient.ZINC -> zinc
        OptionalNutrient.VITAMIN_A -> vitaminA
        OptionalNutrient.VITAMIN_C -> vitaminC
        OptionalNutrient.VITAMIN_D -> vitaminD
        OptionalNutrient.VITAMIN_B12 -> vitaminB12
        OptionalNutrient.VITAMIN_E -> vitaminE
        OptionalNutrient.VITAMIN_K -> vitaminK
        OptionalNutrient.FOLATE -> folate
        OptionalNutrient.OMEGA3 -> omega3
    }

    fun withValue(nutrient: OptionalNutrient, value: Int): OptionalNutrientGoals {
        val safe = value.coerceIn(0, MaximumCustomGoal)
        return when (nutrient) {
            OptionalNutrient.SUGAR -> copy(sugar = safe)
            OptionalNutrient.ADDED_SUGAR -> copy(addedSugar = safe)
            OptionalNutrient.FIBER -> copy(fiber = safe)
            OptionalNutrient.SATURATED_FAT -> copy(saturatedFat = safe)
            OptionalNutrient.CHOLESTEROL -> copy(cholesterol = safe)
            OptionalNutrient.CAFFEINE -> copy(caffeine = safe)
            OptionalNutrient.CREATINE -> copy(supplementalNutrients = supplementalNutrients + (SupplementalNutrient.CREATINE.storageKey to safe))
            OptionalNutrient.BETA_ALANINE -> copy(supplementalNutrients = supplementalNutrients + (SupplementalNutrient.BETA_ALANINE.storageKey to safe))
            OptionalNutrient.L_CITRULLINE -> copy(supplementalNutrients = supplementalNutrients + (SupplementalNutrient.L_CITRULLINE.storageKey to safe))
            OptionalNutrient.L_CARNITINE -> copy(supplementalNutrients = supplementalNutrients + (SupplementalNutrient.L_CARNITINE.storageKey to safe))
            OptionalNutrient.L_ARGININE -> copy(supplementalNutrients = supplementalNutrients + (SupplementalNutrient.L_ARGININE.storageKey to safe))
            OptionalNutrient.TAURINE -> copy(supplementalNutrients = supplementalNutrients + (SupplementalNutrient.TAURINE.storageKey to safe))
            OptionalNutrient.BETAINE -> copy(supplementalNutrients = supplementalNutrients + (SupplementalNutrient.BETAINE.storageKey to safe))
            OptionalNutrient.HMB -> copy(supplementalNutrients = supplementalNutrients + (SupplementalNutrient.HMB.storageKey to safe))
            OptionalNutrient.SODIUM -> copy(sodium = safe)
            OptionalNutrient.POTASSIUM -> copy(potassium = safe)
            OptionalNutrient.TRANS_FAT -> copy(transFat = safe)
            OptionalNutrient.CALCIUM -> copy(calcium = safe)
            OptionalNutrient.IRON -> copy(iron = safe)
            OptionalNutrient.MAGNESIUM -> copy(magnesium = safe)
            OptionalNutrient.ZINC -> copy(zinc = safe)
            OptionalNutrient.VITAMIN_A -> copy(vitaminA = safe)
            OptionalNutrient.VITAMIN_C -> copy(vitaminC = safe)
            OptionalNutrient.VITAMIN_D -> copy(vitaminD = safe)
            OptionalNutrient.VITAMIN_B12 -> copy(vitaminB12 = safe)
            OptionalNutrient.VITAMIN_E -> copy(vitaminE = safe)
            OptionalNutrient.VITAMIN_K -> copy(vitaminK = safe)
            OptionalNutrient.FOLATE -> copy(folate = safe)
            OptionalNutrient.OMEGA3 -> copy(omega3 = safe)
        }
    }

    /** The user's own goal: a stored value > 0 that differs from the legacy fixed default; else null. */
    fun customGoal(nutrient: OptionalNutrient): Int? = valueFor(nutrient).takeIf { it > 0 && it != nutrient.defaultGoal }

    /**
     * The goal in effect: [customGoal], else the personalised default `default_goal_int(default_goal(key,
     * profile))` from the nutrient reference; null for info-style nutrients and sports supplements
     * without a custom goal (and when the reference is not installed).
     */
    fun effectiveGoal(nutrient: OptionalNutrient, profile: NutrientProfile): Int? {
        customGoal(nutrient)?.let { return it }
        // DRI defaults by sex and age band (docs/intake-metrics.md §3), e.g. 8 mg iron for adult men.
        com.ayuvo.health.data.intake.IntakeDefaults.defaultGoal(nutrient, profile)?.let { return it }
        if (NutrientReference.active == null) return null
        return Nutrients.defaultGoalInt(Nutrients.defaultGoal(nutrient.referenceKey, profile))
    }

    companion object {
        const val MaximumCustomGoal = 999_999
        val Default = OptionalNutrientGoals()
    }
}
