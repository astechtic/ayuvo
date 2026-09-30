package com.ayuvo.health.data.derived

import com.ayuvo.health.data.PreferencesStore
import com.ayuvo.health.data.intake.EnergyBalance
import com.ayuvo.health.data.intake.EnergyBalanceInput
import com.ayuvo.health.data.intake.IntakeConfig
import com.ayuvo.health.data.intake.NutritionDayInput
import com.ayuvo.health.data.intake.NutritionDerivation
import com.ayuvo.health.data.intake.NutritionItem
import com.ayuvo.health.models.FoodEntry
import kotlinx.coroutines.flow.first
import java.time.LocalDate
import java.time.ZoneId

/**
 * Inputs of the intake-derived metrics (docs/intake-metrics.md §1): per local day, the food diary as `nutrition_day`
 * items (eaten-at time, meal, macros and the nutrients the rules read), the latest weight on or before the day and the
 * bedtime of the following night; and `energy_balance` from daily logged kcal, the day's measured total energy burned
 * (`tdee`) and the weigh-ins. Results land in [DerivedMetricsService]'s per-day function map under `nutrition_day` and
 * `energy_balance`, so the catalog's nutrition metrics get rows through the usual switches and `rowsFor`.
 *
 * A food belongs to the local day it was eaten (`eatenAt`, else its log time).
 */
class DerivedIntakeInputs(
    food: List<FoodEntry>,
    private val zone: ZoneId,
    private val weights: Map<LocalDate, Double>,
    private val cfg: IntakeConfig
) {
    private val byDay: Map<LocalDate, List<FoodEntry>> = food.groupBy { LocalDate.ofInstant(it.effectiveEatenAt, zone) }

    /** Logged kcal per day (logged days only), the `energy_balance` intake map. */
    val intakeByDay: Map<String, Double> = byDay.entries.associate { (d, list) ->
        var kcal = 0.0
        for (e in list) kcal += e.calories.toDouble()
        d.toString() to kcal
    }

    private val weightsText: Map<String, Double> = weights.entries.associate { it.key.toString() to it.value }

    /** `nutrition_day` for [day]; null when nothing was eaten that day. */
    fun nutritionDay(day: LocalDate, bedtimeMs: Long?): Map<String, Any?>? {
        val items = byDay[day] ?: return null
        return NutritionDerivation.nutritionDay(
            NutritionDayInput(zone.id, weightOn(day), items.map(::item), bedtimeMs), cfg
        ).toMap()
    }

    /** `energy_balance` for [day] with the day's measured total energy burned [tdee]; null without any food log. */
    fun energyBalance(day: LocalDate, tdee: Double?): Map<String, Any?>? {
        if (intakeByDay.isEmpty()) return null
        val key = day.toString()
        return EnergyBalance.energyBalance(
            EnergyBalanceInput(key, intakeByDay, tdee?.let { mapOf(key to it) }.orEmpty(), weightsText), cfg
        ).toMap()
    }

    /**
     * Adds this day's results to [out] (the per-day function map of DerivedMetricsService.compute). The bedtime is the
     * first sleep record of the night that wakes on the next day ([nextNightRows]); `tdee` is read from `energy_day`.
     */
    fun addTo(out: MutableMap<String, Map<String, Any?>>, day: LocalDate, nextNightRows: List<SleepRow>?) {
        nutritionDay(day, nextNightRows?.minOfOrNull { it.startMs })?.let { out[NUTRITION_DAY] = it }
        val tdee = (out[ENERGY_DAY]?.get("tdee") as? Number)?.toDouble()
        energyBalance(day, tdee)?.let { out[ENERGY_BALANCE] = it }
    }

    private fun weightOn(day: LocalDate): Double? {
        var best: LocalDate? = null
        for (d in weights.keys) if (!d.isAfter(day) && (best == null || d.isAfter(best))) best = d
        return best?.let { weights[it] }
    }

    companion object {
        const val NUTRITION_DAY = "nutrition_day"
        const val ENERGY_BALANCE = "energy_balance"
        private const val ENERGY_DAY = "energy_day"
        private val TEA_OR_COFFEE = Regex("\\b(coffee|tea|chai)\\b", RegexOption.IGNORE_CASE)

        /** Food diary + intake config, or null when either is missing (nothing to derive). */
        suspend fun load(prefs: PreferencesStore, zone: ZoneId, weights: Map<LocalDate, Double>): DerivedIntakeInputs? {
            val cfg = IntakeConfig.active ?: return null
            val food = prefs.foodEntries.first()
            if (food.isEmpty()) return null
            return DerivedIntakeInputs(food, zone, weights, cfg)
        }

        /** Caffeine, or a name that says coffee, tea or chai (tannins and polyphenols; docs/intake-metrics.md). */
        fun isTeaOrCoffee(e: FoodEntry): Boolean = (e.caffeine ?: 0.0) > 0 || TEA_OR_COFFEE.containsMatchIn(e.name)

        fun item(e: FoodEntry): NutritionItem = NutritionItem(
            eatenMs = e.effectiveEatenAt.toEpochMilli(),
            meal = e.mealType.name.lowercase(),
            calories = e.calories.toDouble(),
            proteinG = e.protein,
            carbsG = e.carbs,
            fatG = e.fat,
            saturatedFatG = e.saturatedFat,
            fiberG = e.fiber,
            sodiumMg = e.sodium,
            potassiumMg = e.potassium,
            ironMg = e.iron,
            caffeineMg = e.caffeine,
            isTeaOrCoffee = isTeaOrCoffee(e)
        )
    }
}
