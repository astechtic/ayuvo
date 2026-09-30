package com.ayuvo.health.data.intake

import android.content.Context
import com.ayuvo.health.medications.logic.MedicationJson
import com.ayuvo.health.medications.logic.MedicationJson.double
import com.ayuvo.health.medications.logic.MedicationJson.int
import com.ayuvo.health.medications.logic.MedicationJson.objOrNull
import com.ayuvo.health.medications.logic.MedicationJson.str
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

/**
 * `assets/intake/intake_config.json`, a byte copy of `shared/intake/intake_config.json`
 * (docs/intake-metrics.md). `scripts/intake_reference.py` is the normative algorithm.
 */
class IntakeConfig(val root: JsonObject) {

    data class AgeBand(val id: String, val min: Double, val max: Double)

    data class LabLinkRule(val id: String, val analytes: List<String>, val nutrient: String)

    data class Thresholds(
        val adaptiveMinIntakeDays: Int,
        val adaptiveMinWeighins: Int,
        val adaptiveWindowDays: Int,
        val adherentPct: Double,
        val caffeineMaxMg: Double,
        val e1rmMaxReps: Double,
        val energyPerKg: Double,
        val ewmaAlpha: Double,
        val fiberPer1000kcalTarget: Double,
        val ironAbsorptionWindowMin: Double,
        val ironRichMg: Double,
        val naKRatioMax: Double,
        val onTimeMin: Double,
        val pairMinGroup: Int,
        val proteinGPerKgTraining: List<Double>,
        val proteinPerMealGPerKg: Double,
        val saturatedFatMaxPct: Double,
        val shortfallMinDays: Int,
        val shortfallPct: Double,
        val suggestDelayMin: Double,
        val suggestMinTaken: Int,
        val weeklySetsMax: Double,
        val weeklySetsMin: Double
    )

    val algoVersion: Int = root.int("algo_version") ?: 0
    val configVersion: Int = root.int("config_version") ?: 0
    val disclaimer: String = root.str("disclaimer").orEmpty()
    val sources: Map<String, String> =
        root.objOrNull("sources")?.entries?.associate { it.key to (it.value as JsonPrimitive).content }.orEmpty()

    private val dri: JsonObject = root.objOrNull("dri") ?: error("config has no dri")
    val ageBands: List<AgeBand> = (dri["age_bands"] as JsonArray).map {
        val o = it as JsonObject
        AgeBand(o.str("id")!!, o.double("min")!!, o.double("max")!!)
    }
    /** nutrient key → band id → [male, female]. */
    val driGoals: Map<String, Map<String, List<Double>>> = (dri.objOrNull("goals") ?: JsonObject(emptyMap())).entries.associate { (k, v) ->
        k to (v as JsonObject).entries.associate { (band, vals) -> band to numbers(vals) }
    }
    val driUpper: Map<String, Double> =
        dri.objOrNull("upper")?.entries?.associate { it.key to (it.value as JsonPrimitive).doubleOrNull!! }.orEmpty()
    val labLinks: List<LabLinkRule> = (root["lab_links"] as? JsonArray).orEmpty().map {
        val o = it as JsonObject
        LabLinkRule(o.str("id")!!, MedicationJson.strings(o["analytes"]), o.str("nutrient")!!)
    }
    /** "push" / "pull" / "legs" → muscle names. */
    val muscleGroups: Map<String, List<String>> =
        root.objOrNull("muscle_groups")?.entries?.associate { it.key to MedicationJson.strings(it.value) }.orEmpty()
    val thresholds: Thresholds = thresholds(root.objOrNull("thresholds") ?: error("config has no thresholds"))

    companion object {
        const val ASSET_PATH = "intake/intake_config.json"

        @Volatile
        var active: IntakeConfig? = null

        fun parse(text: String): IntakeConfig = IntakeConfig(MedicationJson.json.parseToJsonElement(text) as JsonObject)

        /** Loads (once) from assets and publishes [active]. */
        fun get(context: Context): IntakeConfig = active ?: synchronized(this) {
            active ?: parse(context.assets.open(ASSET_PATH).bufferedReader().use { it.readText() }).also { active = it }
        }

        private fun numbers(e: JsonElement?): List<Double> = (e as? JsonArray).orEmpty().map { (it as JsonPrimitive).doubleOrNull!! }

        private fun thresholds(o: JsonObject): Thresholds {
            fun d(k: String): Double = o.double(k) ?: error("thresholds.$k missing")
            fun i(k: String): Int = o.int(k) ?: error("thresholds.$k missing")
            return Thresholds(
                adaptiveMinIntakeDays = i("adaptive_min_intake_days"),
                adaptiveMinWeighins = i("adaptive_min_weighins"),
                adaptiveWindowDays = i("adaptive_window_days"),
                adherentPct = d("adherent_pct"),
                caffeineMaxMg = d("caffeine_max_mg"),
                e1rmMaxReps = d("e1rm_max_reps"),
                energyPerKg = d("energy_per_kg"),
                ewmaAlpha = d("ewma_alpha"),
                fiberPer1000kcalTarget = d("fiber_per_1000kcal_target"),
                ironAbsorptionWindowMin = d("iron_absorption_window_min"),
                ironRichMg = d("iron_rich_mg"),
                naKRatioMax = d("na_k_ratio_max"),
                onTimeMin = d("on_time_min"),
                pairMinGroup = i("pair_min_group"),
                proteinGPerKgTraining = numbers(o["protein_g_per_kg_training"]),
                proteinPerMealGPerKg = d("protein_per_meal_g_per_kg"),
                saturatedFatMaxPct = d("saturated_fat_max_pct"),
                shortfallMinDays = i("shortfall_min_days"),
                shortfallPct = d("shortfall_pct"),
                suggestDelayMin = d("suggest_delay_min"),
                suggestMinTaken = i("suggest_min_taken"),
                weeklySetsMax = d("weekly_sets_max"),
                weeklySetsMin = d("weekly_sets_min")
            )
        }
    }
}
