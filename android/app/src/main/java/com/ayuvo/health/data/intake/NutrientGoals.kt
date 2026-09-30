package com.ayuvo.health.data.intake

import com.ayuvo.health.data.intake.IntakeMath.roundTo
import com.ayuvo.health.medications.logic.MedicationJson
import kotlinx.serialization.json.JsonObject

data class DriGoalsInput(val sex: String?, val age: Double?)

data class DriGoalsResult(val band: String, val goals: Map<String, Double>, val upper: Map<String, Double>) {
    fun toJson(): JsonObject = MedicationJson.obj("band" to band, "goals" to goals, "upper" to upper)
}

data class CoverageDay(val day: String, val totals: Map<String, Double?>)

data class NutrientCoverageInput(val days: List<CoverageDay>, val goals: Map<String, Double?>, val limits: Map<String, Double?>)

data class NutrientCoverageResult(val days: Int, val coverage: Map<String, Double>, val shortfalls: List<String>, val excesses: List<String>) {
    fun toJson(): JsonObject = MedicationJson.obj("days" to days, "coverage" to coverage, "shortfalls" to shortfalls, "excesses" to excesses)
}

data class SupplementDailyInput(val amountPerDose: Double, val dosesTakenMs: List<Long>, val windowDays: Double, val upper: Double?)

data class SupplementDailyResult(val dailyAverage: Double?, val doses: Int, val aboveUpper: Boolean) {
    fun toJson(): JsonObject = MedicationJson.obj("daily_average" to dailyAverage, "doses" to doses, "above_upper" to aboveUpper)
}

/** Ports of `dri_goals`, `nutrient_coverage` and `supplement_daily`. */
object IntakeNutrientGoals {

    fun driGoals(inp: DriGoalsInput, cfg: IntakeConfig): DriGoalsResult {
        val bands = cfg.ageBands
        val age = inp.age
        var band: String? = null
        for (b in bands) if (age != null && b.min <= age && age <= b.max) band = b.id
        if (band == null) band = if (age != null && age < bands.first().min) bands.first().id else bands.last().id
        val goals = LinkedHashMap<String, Double>()
        for ((key, row) in IntakeMath.sortedByKey(cfg.driGoals)) {
            val vals = row.getValue(band)
            goals[key] = when (inp.sex) {
                "male" -> vals[0]
                "female" -> vals[1]
                else -> maxOf(vals[0], vals[1])
            }
        }
        val upper = LinkedHashMap<String, Double>()
        for ((key, v) in IntakeMath.sortedByKey(cfg.driUpper)) upper[key] = v
        return DriGoalsResult(band, goals, upper)
    }

    fun nutrientCoverage(inp: NutrientCoverageInput, cfg: IntakeConfig): NutrientCoverageResult {
        val th = cfg.thresholds
        val days = inp.days
        if (days.size < th.shortfallMinDays) return NutrientCoverageResult(days.size, emptyMap(), emptyList(), emptyList())
        val coverage = LinkedHashMap<String, Double>()
        val shortfalls = ArrayList<String>()
        val excesses = ArrayList<String>()
        for ((key, g) in IntakeMath.sortedByKey(inp.goals)) {
            if (!IntakeMath.truthy(g)) continue
            val vals = ArrayList<Double>()
            for (d in days) vals += (d.totals[key] ?: 0.0) * 100.0 / g!!
            val pct = IntakeMath.mean(vals)
            coverage[key] = roundTo(pct, 0)
            if (pct < th.shortfallPct) shortfalls += key
        }
        for ((key, lim) in IntakeMath.sortedByKey(inp.limits)) {
            val vals = ArrayList<Double>()
            for (d in days) vals += d.totals[key] ?: 0.0
            if (IntakeMath.truthy(lim) && IntakeMath.mean(vals) > lim!!) excesses += key
        }
        return NutrientCoverageResult(days.size, coverage, shortfalls, excesses)
    }

    fun supplementDaily(inp: SupplementDailyInput, @Suppress("UNUSED_PARAMETER") cfg: IntakeConfig? = null): SupplementDailyResult {
        val n = inp.dosesTakenMs.size
        val avg = if (inp.windowDays > 0) inp.amountPerDose * n / inp.windowDays else null
        val up = inp.upper
        return SupplementDailyResult(roundTo(avg, 1), n, avg != null && up != null && avg > up)
    }
}
