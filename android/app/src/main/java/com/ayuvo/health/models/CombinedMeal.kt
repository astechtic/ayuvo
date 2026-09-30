package com.ayuvo.health.models

import com.ayuvo.health.services.ai.FoodAnalysis
import java.util.UUID
import com.ayuvo.health.R
import com.ayuvo.health.l10n.AppText

/** Map a diary food to one ingredient line (nested ingredients stay collapsed). */
fun FoodEntry.toMealIngredient(): MealIngredient = MealIngredient(
    name = name,
    grams = servingSizeGrams?.takeIf { it.isFinite() && it > 0.0 } ?: 0.0,
    calories = calories,
    protein = protein,
    carbs = carbs,
    fat = fat,
    imageFilename = allImageFilenames.firstOrNull(),
    additionalImageFilenames = allImageFilenames.drop(1),
    emoji = emoji
)

/** Map an analysis result to one ingredient line (nested ingredients stay collapsed). */
fun FoodAnalysis.toMealIngredient(): MealIngredient = MealIngredient(
    name = name,
    grams = servingSizeGrams.takeIf { it.isFinite() && it > 0.0 } ?: 0.0,
    calories = calories,
    protein = protein,
    carbs = carbs,
    fat = fat,
    emoji = emoji
)

/** Recompute parent macros from an ingredient list. */
fun FoodEntry.withIngredients(ingredients: List<MealIngredient>): FoodEntry {
    val totals = ingredients.totals()
    return copy(
        calories = totals.calories,
        protein = totals.protein,
        carbs = totals.carbs,
        fat = totals.fat,
        servingSizeGrams = totals.grams.takeIf { it > 0 } ?: servingSizeGrams,
        servingUnitOptions = emptyList(),
        selectedServingUnit = null,
        selectedServingQuantity = null,
        ingredients = ingredients
    )
}

object CombinedMeal {
    fun combinedName(entries: List<FoodEntry>): String {
        val names = entries.map { it.name.trim() }.filter { it.isNotEmpty() }
        if (names.isEmpty()) return AppText.orEnglish("Combined meal", R.string.core_combined_meal)
        if (names.size <= 3) return names.joinToString(" + ")
        val first = names.take(2).joinToString(" + ")
        val more = names.size - 2
        return if (AppText.resolver == null) "$first + $more more" else AppText.plural(R.plurals.core_combined_meal_more, more, first, more)
    }

    fun combineFoodEntries(entries: List<FoodEntry>): FoodEntry {
        require(entries.size >= 2) { "Combine requires at least two food entries" }
        val ingredients = entries.map { it.toMealIngredient() }
        val totals = ingredients.totals()
        val latest = entries.maxByOrNull { it.timestamp } ?: entries.first()
        val filenames = entries.flatMap { it.allImageFilenames }.distinct()
        return FoodEntry(
            id = UUID.randomUUID(),
            name = combinedName(entries),
            calories = totals.calories,
            protein = totals.protein,
            carbs = totals.carbs,
            fat = totals.fat,
            timestamp = latest.timestamp,
            imageFilename = filenames.firstOrNull(),
            additionalImageFilenames = filenames.drop(1),
            emoji = entries.firstNotNullOfOrNull { it.emoji },
            source = FoodSource.MANUAL,
            healthConnectOrigin = entries.firstNotNullOfOrNull { it.healthConnectOrigin },
            mealType = latest.mealType,
            servingSizeGrams = totals.grams.takeIf { it > 0 },
            ingredients = ingredients
        )
    }
}

fun combineFoodEntries(entries: List<FoodEntry>): FoodEntry = CombinedMeal.combineFoodEntries(entries)
