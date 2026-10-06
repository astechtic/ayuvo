package com.ayuvo.health.services.ai

import com.ayuvo.health.models.FoodEntry
import com.ayuvo.health.nutrients.NutrientFields
import com.ayuvo.health.nutrients.NutrientProfile
import com.ayuvo.health.nutrients.NutrientReference
import com.ayuvo.health.nutrients.NutrientTotals
import com.ayuvo.health.nutrients.NutrientValueEntry
import com.ayuvo.health.nutrients.Nutrients
import com.ayuvo.health.nutrients.SupplementSnapshot
import java.time.LocalDate
import java.time.ZoneId

/**
 * `get_nutrient_totals` (docs/coach.md §8): every nutrient the user has data for over a date range — food log,
 * taken supplement doses and (for nutrients the food log does not record) other apps' values from Health
 * Connect — with the personalised reference values. Same payload shape as iOS `CoachNutrientReport`.
 *
 * Built only from the shared nutrient paths: [NutrientTotals] (`day_totals`), [Nutrients.loggedDayAverage]
 * and [Nutrients.referenceLines] (docs/nutrients.md §4). A nutrient nobody recorded is listed as missing,
 * never as zero.
 */
object CoachNutrientReport {
    /** Per-day rows are included up to this many days; longer ranges return the range totals only. */
    const val MAX_DAILY_ROWS = 31

    /** Longest range one call reads. */
    const val MAX_RANGE_DAYS = 366

    val NOTES: List<String> = listOf(
        "Totals are what the user logged. A nutrient missing from a food entry is unknown, not zero: never call a nutrient low or deficient because it is listed in no_data_logged or not_tracked_by_food_log.",
        "supplements counts only doses marked taken in Medications, averaged over their dosing interval. Supplements never add calories.",
        "health_other_apps is from other apps through Apple Health / Health Connect, only for nutrients the Ayuvo food log does not record.",
        "Reference values are NASEM Dietary Reference Intakes for adults 19+ who are not pregnant or breastfeeding; upper_limit_scope says which sources the upper limit covers.",
        "Lab results in Health Records measure blood levels, not intake. Mention them only as a complement to these totals, never instead of them."
    )

    /** Reference nutrients the food log does not record, with the health type that can fill them. */
    fun healthBackedTypes(): Map<String, String> =
        NutrientReference.active?.nutrients.orEmpty().filter { !it.appTracked && it.healthType != null }
            .associate { it.key to it.healthType!! }

    private val MACROS = listOf(
        "calories" to "calories_kcal", "protein" to "protein_g", "carbs" to "carbs_g", "fat" to "fat_g"
    )

    /**
     * [health]: nutrient key → local day (yyyy-MM-dd) → amount in the nutrient's unit, from other apps.
     * [customGoals]: nutrient key → the user's custom goal (Settings › Nutrient goals).
     */
    fun payload(
        foods: List<FoodEntry>,
        supplements: SupplementSnapshot,
        from: LocalDate,
        to: LocalDate,
        zone: ZoneId,
        profile: NutrientProfile,
        customGoals: Map<String, Double> = emptyMap(),
        health: Map<String, Map<String, Double>> = emptyMap()
    ): Map<String, Any?> {
        val ref = NutrientReference.current
        val lastDay = minOf(to, from.plusDays((MAX_RANGE_DAYS - 1).toLong()))
        val days = generateSequence(from) { it.plusDays(1) }.takeWhile { !it.isAfter(lastDay) }.toList()
        val startMs = from.atStartOfDay(zone).toInstant().toEpochMilli()
        val endMs = lastDay.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val totals = NutrientTotals(foods, supplements, zone)
        val rangeFoods = foods.filter { it.timestamp.toEpochMilli() in startMs until endMs }
        val loggedDays = NutrientTotals.loggedDays(foods, supplements, zone).filter { Nutrients.localMidnightMs(it, zone) in startMs until endMs }
        val dayTotals = days.map { totals.day(it) }
        val keys = ref.nutrients.map { it.key } + ref.sports.map { it.key }

        // Other apps' values sit at local noon of their day so they land in that day for every average.
        fun healthEntries(key: String): List<NutrientValueEntry> = health[key].orEmpty().mapNotNull { (day, value) ->
            val midnight = runCatching { Nutrients.localMidnightMs(day, zone) }.getOrNull() ?: return@mapNotNull null
            if (!value.isFinite() || midnight !in startMs until endMs) null else NutrientValueEntry(midnight + 43_200_000L, value)
        }

        val rows = mutableListOf<Map<String, Any?>>()
        val noDataLogged = mutableListOf<String>()
        val notTracked = mutableListOf<String>()
        for (key in keys) {
            val food = sumOrNull(dayTotals.map { it[key]?.food })
            val supp = sumOrNull(dayTotals.map { it[key]?.supplements })
            val healthValues = healthEntries(key)
            val healthSum = if (healthValues.isEmpty()) null else healthValues.sumOf { it.value ?: 0.0 }
            if (food == null && supp == null && healthSum == null) {
                if (key in ref.sportsByKey) continue
                if (NutrientFields.foodTracked(key)) noDataLogged += key else notTracked += key
                continue
            }
            val combined = (food ?: 0.0) + (supp ?: 0.0) + (healthSum ?: 0.0)
            val average = Nutrients.loggedDayAverage(
                NutrientTotals.seriesEntries(key, foods, supplements) + healthValues, loggedDays, startMs, endMs, zone, key
            )
            val lines = Nutrients.referenceLines(key, profile, customGoals[key])
            val spec = ref.byKey[key]
            val row = linkedMapOf<String, Any?>(
                "key" to key,
                "name" to (spec?.name ?: key),
                "unit" to (lines.unit ?: NutrientFields.unit(key)),
                "category" to (spec?.category ?: "sports"),
                "food_tracked" to NutrientFields.foodTracked(key),
                "food" to food?.let(::round2),
                "supplements" to supp?.let(::round2),
                "total" to round2(combined),
                "average_per_logged_day" to average.average?.let(::round2),
                "logged_days" to average.loggedDays
            )
            if (healthSum != null) row["health_other_apps"] = round2(healthSum)
            lines.recommended?.let { recommended ->
                row["recommended"] = round2(recommended)
                row["recommended_kind"] = if (lines.recommendedIsGoal) "custom_goal" else lines.recommendedKind
                average.average?.takeIf { recommended > 0 }?.let { row["percent_of_recommended"] = Nutrients.roundHalfUpInt(it / recommended * 100) }
            }
            lines.limit?.let { limit ->
                row["limit"] = round2(limit)
                row["limit_kind"] = if (lines.limitIsGoal) "custom_goal" else "reference"
                average.average?.let { row["above_limit"] = it > limit }
            }
            lines.upperLimit?.let { upper ->
                row["upper_limit"] = round2(upper)
                row["upper_limit_scope"] = lines.upperLimitScope
                spec?.upperLimit?.note?.let { row["upper_limit_note"] = it }
                // A supplements-only UL (magnesium, vitamin E, niacin, folic acid) is compared with the supplement part.
                val supplementsOnly = lines.upperLimitScope in setOf("supplements_only", "folic_acid_only")
                val basis = if (supplementsOnly) (supp ?: 0.0) / maxOf(average.loggedDays, 1) else average.average
                basis?.let { row["above_upper_limit"] = it > upper }
            }
            rows += row
        }

        val foodDays = rangeFoods.map { it.timestamp.atZone(zone).toLocalDate() }.toSet().size
        val macros = linkedMapOf<String, Any?>()
        for ((key, name) in MACROS) {
            val total = rangeFoods.sumOf { NutrientFields.foodValue(it, key) ?: 0.0 }
            macros[name] = if (rangeFoods.isEmpty()) linkedMapOf("total" to null, "average_per_logged_day" to null)
            else linkedMapOf("total" to round2(total), "average_per_logged_day" to round2(total / maxOf(foodDays, 1)))
        }

        val payload = linkedMapOf<String, Any?>(
            "from" to from.toString(),
            "to" to lastDay.toString(),
            "days_in_range" to days.size,
            "logged_days" to loggedDays.size,
            "food_entries" to rangeFoods.size,
            "profile" to linkedMapOf("sex" to (profile.sex ?: "unspecified"), "age_band" to Nutrients.bandForAge(profile.age)),
            "macros" to macros,
            "nutrients" to rows,
            "no_data_logged" to noDataLogged,
            "not_tracked_by_food_log" to notTracked,
            "notes" to NOTES
        )
        if (days.size <= MAX_DAILY_ROWS) {
            val withData = rows.map { it["key"] as String }
            payload["days"] = days.mapIndexed { index, day ->
                val text = day.toString()
                val values = linkedMapOf<String, Double>()
                for (key in withData) {
                    val dayTotal = dayTotals[index][key]?.total
                    val healthValue = health[key]?.get(text)
                    if (dayTotal != null || healthValue != null) values[key] = round2((dayTotal ?: 0.0) + (healthValue ?: 0.0))
                }
                linkedMapOf(
                    "date" to text,
                    "food_entries" to rangeFoods.count { it.timestamp.atZone(zone).toLocalDate() == day },
                    "totals" to values
                )
            }
        } else {
            payload["days_omitted"] = "Range longer than $MAX_DAILY_ROWS days: per-day rows omitted; ask for a shorter range for day-by-day detail."
        }
        return payload
    }

    private fun sumOrNull(values: List<Double?>): Double? =
        values.filterNotNull().takeIf { it.isNotEmpty() }?.sum()

    private fun round2(value: Double): Double = Nutrients.roundTo(value, 2) ?: value
}
