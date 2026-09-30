package com.ayuvo.health.data.intake

import com.ayuvo.health.data.intake.IntakeMath.roundTo
import com.ayuvo.health.medications.logic.MedicationJson
import kotlinx.serialization.json.JsonObject
import kotlin.math.abs

/** One logged food item with its eaten-at instant (docs/intake-metrics.md §3). Missing nutrients are null. */
data class NutritionItem(
    val eatenMs: Long,
    val meal: String?,
    val calories: Double? = null,
    val proteinG: Double? = null,
    val carbsG: Double? = null,
    val fatG: Double? = null,
    val saturatedFatG: Double? = null,
    val fiberG: Double? = null,
    val sodiumMg: Double? = null,
    val potassiumMg: Double? = null,
    val ironMg: Double? = null,
    val caffeineMg: Double? = null,
    val isTeaOrCoffee: Boolean = false
)

data class NutritionDayInput(
    val timeZone: String,
    val weightKg: Double?,
    val items: List<NutritionItem>,
    val bedtimeMs: Long?
)

data class NutritionDayResult(
    val items: Int,
    val calories: Double?,
    val proteinPct: Double?,
    val carbsPct: Double?,
    val fatPct: Double?,
    val proteinGPerKg: Double?,
    val saturatedFatPct: Double?,
    val fiberPer1000kcal: Double?,
    val naKRatio: Double?,
    val caffeineMg: Double?,
    val lastCaffeineMin: Int?,
    val eatingWindowMin: Double?,
    val lastMealToBedMin: Double?,
    val mealsWithProteinTarget: Int?,
    val meals: Int,
    val ironAbsorptionRisk: Int
) {
    fun toMap(): Map<String, Any?> = linkedMapOf(
        "items" to items, "calories" to calories, "protein_pct" to proteinPct, "carbs_pct" to carbsPct, "fat_pct" to fatPct,
        "protein_g_per_kg" to proteinGPerKg, "saturated_fat_pct" to saturatedFatPct, "fiber_per_1000kcal" to fiberPer1000kcal,
        "na_k_ratio" to naKRatio, "caffeine_mg" to caffeineMg, "last_caffeine_min" to lastCaffeineMin,
        "eating_window_min" to eatingWindowMin, "last_meal_to_bed_min" to lastMealToBedMin,
        "meals_with_protein_target" to mealsWithProteinTarget, "meals" to meals, "iron_absorption_risk" to ironAbsorptionRisk
    )

    fun toJson(): JsonObject = MedicationJson.element(toMap()) as JsonObject
}

/** Port of `nutrition_day`. */
object NutritionDerivation {

    fun nutritionDay(inp: NutritionDayInput, cfg: IntakeConfig): NutritionDayResult {
        val th = cfg.thresholds
        val items = inp.items.sortedBy { it.eatenMs } // stable, like Python's sorted
        if (items.isEmpty()) {
            return NutritionDayResult(0, null, null, null, null, null, null, null, null, null, null, null, null, null, 0, 0)
        }
        fun total(key: (NutritionItem) -> Double?): Double {
            var t = 0.0
            for (i in items) t += key(i) ?: 0.0
            return t
        }
        val kcal = total { it.calories }
        val p = total { it.proteinG }
        val c = total { it.carbsG }
        val f = total { it.fatG }
        val energy = 4.0 * p + 4.0 * c + 9.0 * f
        var proteinPct: Double? = null
        var carbsPct: Double? = null
        var fatPct: Double? = null
        var satPct: Double? = null
        if (energy > 0) {
            proteinPct = roundTo(400.0 * p / energy, 1)
            carbsPct = roundTo(400.0 * c / energy, 1)
            fatPct = roundTo(900.0 * f / energy, 1)
            satPct = roundTo(900.0 * total { it.saturatedFatG } / energy, 1)
        }
        val w = inp.weightKg
        val hasWeight = IntakeMath.truthy(w)
        val perKg = if (hasWeight) roundTo(p / w!!, 2) else null
        val fiber = if (kcal > 0) roundTo(total { it.fiberG } * 1000.0 / kcal, 1) else null
        val k = total { it.potassiumMg }
        // molar ratio: sodium 22.99 g/mol, potassium 39.10 g/mol
        val naK = if (k > 0) roundTo((total { it.sodiumMg } / 22.99) / (k / 39.10), 2) else null
        val caf = items.filter { (it.caffeineMg ?: 0.0) > 0 }
        val lastCaf = if (caf.isNotEmpty()) IntakeMath.minuteOfDay(caf.last().eatenMs, inp.timeZone) else null
        val window = roundTo((items.last().eatenMs - items.first().eatenMs) / 60000.0, 0)
        val toBed = inp.bedtimeMs?.let { roundTo((it - items.last().eatenMs) / 60000.0, 0) }
        val meals = LinkedHashMap<String, MutableList<NutritionItem>>()
        for (i in items) meals.getOrPut(i.meal?.takeIf { it.isNotEmpty() } ?: "other") { ArrayList() } += i
        var withTarget: Int? = null
        if (hasWeight) {
            val target = th.proteinPerMealGPerKg * w!!
            var hit = 0
            for (name in IntakeMath.sortedKeys(meals.keys)) {
                var mp = 0.0
                for (i in meals.getValue(name)) mp += i.proteinG ?: 0.0
                if (mp >= target) hit += 1
            }
            withTarget = hit
        }
        var risk = 0
        val windowMs = th.ironAbsorptionWindowMin * 60000
        for ((ti, t) in items.withIndex()) {
            if (!t.isTeaOrCoffee) continue
            for ((ii, i) in items.withIndex()) {
                if ((i.ironMg ?: 0.0) >= th.ironRichMg && abs(i.eatenMs - t.eatenMs) <= windowMs && ii != ti) {
                    risk += 1
                    break
                }
            }
        }
        return NutritionDayResult(
            items = items.size, calories = roundTo(kcal, 0), proteinPct = proteinPct, carbsPct = carbsPct, fatPct = fatPct,
            proteinGPerKg = perKg, saturatedFatPct = satPct, fiberPer1000kcal = fiber, naKRatio = naK,
            caffeineMg = roundTo(total { it.caffeineMg }, 0), lastCaffeineMin = lastCaf, eatingWindowMin = window,
            lastMealToBedMin = toBed, mealsWithProteinTarget = withTarget, meals = meals.size, ironAbsorptionRisk = risk
        )
    }
}
