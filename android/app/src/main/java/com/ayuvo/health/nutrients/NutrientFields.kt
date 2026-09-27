package com.ayuvo.health.nutrients

import androidx.annotation.StringRes
import com.ayuvo.health.R
import com.ayuvo.health.models.FoodEntry
import com.ayuvo.health.models.Gender
import com.ayuvo.health.models.HomeTopNutrient
import com.ayuvo.health.models.OptionalNutrient
import com.ayuvo.health.models.SupplementalNutrient
import com.ayuvo.health.models.UserProfile

/**
 * Maps the shared nutrient keys (`nutrient_reference.json` + sports keys, docs/nutrients.md) to the
 * app's [FoodEntry] fields, [OptionalNutrient] goals and display names. The macro keys `calories`,
 * `protein`, `carbs` and `fat` are food only: no supplement row ever carries them.
 */
object NutrientFields {
    const val CALORIES = "calories"
    const val PROTEIN = "protein"
    const val CARBS = "carbs"
    const val FAT = "fat"

    /** Every reference and sports key in reference order (the catalog's `nutrient_metrics` order). */
    val REFERENCE_KEYS: List<String> = listOf(
        "fiber", "sugar", "added_sugar", "saturated_fat", "trans_fat", "monounsaturated_fat", "polyunsaturated_fat",
        "omega_3", "cholesterol", "sodium", "potassium", "calcium", "iron", "magnesium", "zinc", "vitamin_a", "vitamin_c",
        "vitamin_d", "vitamin_e", "vitamin_k", "vitamin_b12", "folate", "caffeine",
        "creatine", "beta_alanine", "l_citrulline", "l_carnitine", "l_arginine", "taurine", "betaine", "hmb"
    )

    /** One food entry's value of [key]; null when the entry did not record it. */
    fun foodValue(entry: FoodEntry, key: String): Double? = when (key) {
        CALORIES -> entry.calories.toDouble()
        PROTEIN -> entry.protein
        CARBS -> entry.carbs
        FAT -> entry.fat
        "fiber" -> entry.fiber
        "sugar" -> entry.sugar
        "added_sugar" -> entry.addedSugar
        "saturated_fat" -> entry.saturatedFat
        "trans_fat" -> entry.transFat
        "monounsaturated_fat" -> entry.monounsaturatedFat
        "polyunsaturated_fat" -> entry.polyunsaturatedFat
        "omega_3" -> entry.omega3
        "cholesterol" -> entry.cholesterol
        "sodium" -> entry.sodium
        "potassium" -> entry.potassium
        "calcium" -> entry.calcium
        "iron" -> entry.iron
        "magnesium" -> entry.magnesium
        "zinc" -> entry.zinc
        "vitamin_a" -> entry.vitaminA
        "vitamin_c" -> entry.vitaminC
        "vitamin_d" -> entry.vitaminD
        "vitamin_e" -> entry.vitaminE
        "vitamin_k" -> entry.vitaminK
        "vitamin_b12" -> entry.vitaminB12
        "folate" -> entry.folate
        "caffeine" -> entry.caffeine
        else -> SupplementalNutrient.entries.firstOrNull { it.apiKey == key }?.let { entry.supplementalNutrients[it.storageKey] }
    }?.takeIf { it.isFinite() }

    /** The goal setting behind [key]; null for mono/poly fat and the macros. */
    fun optionalNutrient(key: String): OptionalNutrient? = OptionalNutrient.entries.firstOrNull { it.referenceKey == key }

    fun homeTopNutrient(key: String): HomeTopNutrient? = HomeTopNutrient.entries.firstOrNull { it.referenceKey == key }

    /** Display name resource for any nutrient key (reference, sports or macro). */
    @StringRes
    fun nameRes(key: String): Int = when (key) {
        CALORIES -> R.string.nutrition_label_calories
        PROTEIN -> R.string.nutrition_label_protein
        CARBS -> R.string.nutrition_label_carbs
        FAT -> R.string.nutrition_label_fat
        "monounsaturated_fat" -> R.string.nutrition_label_mono_fat
        "polyunsaturated_fat" -> R.string.nutrition_label_poly_fat
        else -> optionalNutrient(key)?.displayNameRes ?: R.string.nutrients_unknown_name
    }

    /** Canonical unit (`g`, `mg`, `mcg`, `kcal`). */
    fun unit(key: String): String = when (key) {
        CALORIES -> "kcal"
        PROTEIN, CARBS, FAT -> "g"
        else -> NutrientReference.active?.unitOf(key) ?: optionalNutrient(key)?.unit ?: "g"
    }

    /** Reference lines profile from the user's profile: age, male/female (else unknown) and calorie goal. */
    fun profile(p: UserProfile?): NutrientProfile = if (p == null) NutrientProfile() else NutrientProfile(
        age = p.age.toDouble(),
        sex = when (p.gender) {
            Gender.MALE -> "male"
            Gender.FEMALE -> "female"
            Gender.OTHER -> null
        },
        calorieGoal = p.effectiveCalories.toDouble().takeIf { it > 0 }
    )
}
