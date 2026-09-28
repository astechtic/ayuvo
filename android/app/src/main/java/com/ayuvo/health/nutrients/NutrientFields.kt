package com.ayuvo.health.nutrients

import android.content.Context
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

    /**
     * The FOOD LOG's keys: the 23 `app_tracked` reference keys in reference order, then the sports
     * keys. The `app_tracked: false` nutrients (copper, thiamin, …) are not food fields; every
     * supplement / chart key is [NutrientReference.supplementKeys].
     */
    val REFERENCE_KEYS: List<String> = listOf(
        "fiber", "sugar", "added_sugar", "saturated_fat", "trans_fat", "monounsaturated_fat", "polyunsaturated_fat",
        "omega_3", "cholesterol", "sodium", "potassium", "calcium", "iron", "magnesium", "zinc", "vitamin_a", "vitamin_c",
        "vitamin_d", "vitamin_e", "vitamin_k", "vitamin_b12", "folate", "caffeine",
        "creatine", "beta_alanine", "l_citrulline", "l_carnitine", "l_arginine", "taurine", "betaine", "hmb"
    )

    /** Groups of the supplement nutrient picker (docs/medications.md §21), in display order. */
    enum class PickerGroup(@StringRes val titleRes: Int) {
        VITAMINS(R.string.browse_section_nutrition_vitamins),
        MINERALS(R.string.browse_section_nutrition_minerals),
        OTHER(R.string.browse_section_nutrition_other),
        SPORTS(R.string.browse_section_nutrition_supplements)
    }

    /** Picker group of a supplement key: the reference category (carbs, fats, other → Other), sports → Sports. */
    fun supplementPickerGroup(key: String): PickerGroup {
        val ref = NutrientReference.active
        if (ref?.sportsByKey?.containsKey(key) == true) return PickerGroup.SPORTS
        return when (ref?.byKey?.get(key)?.category) {
            "vitamins" -> PickerGroup.VITAMINS
            "minerals" -> PickerGroup.MINERALS
            else -> PickerGroup.OTHER
        }
    }

    /**
     * Every supplement nutrient (`SUPPLEMENT_KEYS`: all 37 reference nutrients plus the sports
     * supplements), grouped Vitamins / Minerals / Other / Sports, reference order inside a group.
     */
    fun supplementPickerKeys(): List<String> {
        val keys = NutrientReference.active?.supplementKeys ?: REFERENCE_KEYS
        return PickerGroup.entries.flatMap { g -> keys.filter { supplementPickerGroup(it) == g } }
    }

    /**
     * Nutrition Details order (docs/nutrients.md §5b): each of [untracked] (reference order) goes
     * after the last row of [base] (then of the rows already inserted) of the same reference
     * category, minerals after zinc and vitamins after folate; a category [base] lacks → the end.
     */
    fun withUntrackedRows(base: List<String>, untracked: List<String>): List<String> {
        val ref = NutrientReference.active ?: return base
        val out = base.toMutableList()
        for (k in untracked) {
            if (k in out) continue
            val category = ref.byKey[k]?.category
            val at = out.indexOfLast { ref.byKey[it]?.category == category }
            if (at < 0) out += k else out.add(at + 1, k)
        }
        return out
    }

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

    /** Display name resource for a food-log key (reference, sports or macro); see [displayName] for any key. */
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

    /**
     * Display name of any nutrient key: the app's name for food-log, sports and macro keys, else
     * the reference `name` (copper, thiamin, … are not in [OptionalNutrient]).
     */
    fun displayName(context: Context, key: String): String {
        val res = nameRes(key)
        if (res != R.string.nutrients_unknown_name) return context.getString(res)
        return NutrientReference.active?.byKey?.get(key)?.name?.takeIf { it.isNotBlank() } ?: context.getString(res)
    }

    /** True when the food log records [key] (`food_tracked`); false for copper, thiamin, … and unknown keys. */
    fun foodTracked(key: String): Boolean = NutrientReference.active?.foodTracked(key) ?: (key in REFERENCE_KEYS)

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
