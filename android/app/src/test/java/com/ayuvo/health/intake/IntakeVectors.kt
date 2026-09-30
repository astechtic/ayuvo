package com.ayuvo.health.intake

import com.ayuvo.health.data.intake.AdherenceLog
import com.ayuvo.health.data.intake.CoverageDay
import com.ayuvo.health.data.intake.DriGoalsInput
import com.ayuvo.health.data.intake.EnergyBalance
import com.ayuvo.health.data.intake.EnergyBalanceInput
import com.ayuvo.health.data.intake.IntakeConfig
import com.ayuvo.health.data.intake.IntakeNutrientGoals
import com.ayuvo.health.data.intake.LabLinks
import com.ayuvo.health.data.intake.LabLinksInput
import com.ayuvo.health.data.intake.LabValue
import com.ayuvo.health.data.intake.MedicationAdherence
import com.ayuvo.health.data.intake.MedsAdherenceInput
import com.ayuvo.health.data.intake.NutrientCoverageInput
import com.ayuvo.health.data.intake.NutritionDayInput
import com.ayuvo.health.data.intake.NutritionDerivation
import com.ayuvo.health.data.intake.NutritionItem
import com.ayuvo.health.data.intake.PairedDifferenceInput
import com.ayuvo.health.data.intake.StrengthExercise
import com.ayuvo.health.data.intake.StrengthSession
import com.ayuvo.health.data.intake.StrengthSet
import com.ayuvo.health.data.intake.StrengthWeek
import com.ayuvo.health.data.intake.StrengthWeekInput
import com.ayuvo.health.data.intake.SupplementDailyInput
import com.ayuvo.health.medications.logic.MedicationJson
import com.ayuvo.health.medications.logic.MedicationJson.double
import com.ayuvo.health.medications.logic.MedicationJson.int
import com.ayuvo.health.medications.logic.MedicationJson.long
import com.ayuvo.health.medications.logic.MedicationJson.objOrNull
import com.ayuvo.health.medications.logic.MedicationJson.str
import com.ayuvo.health.records.processing.RecordsVectors
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import java.io.File

/** Locates the shared intake contract from the Gradle unit-test working directory (android/app). */
object IntakeTestFiles {
    fun shared(relative: String): File? =
        listOf("../../shared/intake/$relative", "../shared/intake/$relative", "shared/intake/$relative")
            .map(::File).firstOrNull { it.exists() }

    fun asset(relative: String): File? =
        listOf("src/main/assets/intake/$relative", "app/src/main/assets/intake/$relative").map(::File).firstOrNull { it.exists() }

    val config: IntakeConfig by lazy { IntakeConfig.parse(shared("intake_config.json")!!.readText()) }
}

/** Runs `shared/intake/test-vectors/<file>` through the Kotlin engines (dispatch mirrors `intake_reference.FUNCTIONS`). */
object IntakeVectors {
    val FILES = linkedMapOf(
        "nutrition_day.json" to "nutrition_day",
        "dri_goals.json" to "dri_goals",
        "nutrient_coverage.json" to "nutrient_coverage",
        "supplement_daily.json" to "supplement_daily",
        "meds_adherence.json" to "meds_adherence",
        "strength_week.json" to "strength_week",
        "energy_balance.json" to "energy_balance",
        "paired_difference.json" to "paired_difference",
        "lab_nutrient_links.json" to "lab_nutrient_links"
    )

    data class Outcome(val file: String, val passed: Int, val total: Int, val failures: List<String>)

    fun run(file: String): Outcome {
        val f = IntakeTestFiles.shared("test-vectors/$file") ?: run { fail("shared/intake/test-vectors/$file missing"); error("") }
        val root = MedicationJson.json.parseToJsonElement(f.readText()) as JsonObject
        val function = root.str("function") ?: error("$file has no function")
        val cases = root["cases"] as JsonArray
        val failures = mutableListOf<String>()
        var passed = 0
        for (c in cases) {
            val case = c as JsonObject
            val name = case.str("name") ?: "?"
            val actual = try {
                runCase(function, case["input"] as JsonObject)
            } catch (e: Throwable) {
                failures += "$name: threw ${e.javaClass.simpleName}: ${e.message}"
                continue
            }
            val diff = RecordsVectors.diff(case["expected"]!!, actual, "$")
            if (diff == null) passed++ else failures += "$name: $diff\n    actual=$actual"
        }
        return Outcome(file, passed, cases.size, failures)
    }

    fun assertAll(file: String) {
        val o = run(file)
        println("VECTORS intake/${o.file}: ${o.passed}/${o.total}")
        assertTrue("${o.file}: ${o.passed}/${o.total} passed\n" + o.failures.joinToString("\n"), o.failures.isEmpty() && o.total > 0)
    }

    // -- Decoding --------------------------------------------------------------------------------

    private fun num(e: JsonElement?): Double? =
        (e as? JsonPrimitive)?.takeIf { it !is JsonNull && !it.isString && it.booleanOrNull == null }?.doubleOrNull

    private fun objects(e: JsonElement?): List<JsonObject> = (e as? JsonArray).orEmpty().map { it as JsonObject }

    private fun numMap(e: JsonElement?): Map<String, Double?> = (e as? JsonObject)?.entries?.associate { it.key to num(it.value) }.orEmpty()

    private fun numMapNotNull(e: JsonElement?): Map<String, Double> = numMap(e).mapNotNull { (k, v) -> v?.let { k to it } }.toMap()

    fun runCase(function: String, i: JsonObject): JsonElement {
        val cfg = IntakeTestFiles.config
        return when (function) {
            "nutrition_day" -> NutritionDerivation.nutritionDay(
                NutritionDayInput(
                    timeZone = i.str("time_zone")!!, weightKg = i.double("weight_kg"),
                    items = objects(i["items"]).map {
                        NutritionItem(
                            eatenMs = it.long("eaten_ms")!!, meal = it.str("meal"), calories = it.double("calories"),
                            proteinG = it.double("protein_g"), carbsG = it.double("carbs_g"), fatG = it.double("fat_g"),
                            saturatedFatG = it.double("saturated_fat_g"), fiberG = it.double("fiber_g"), sodiumMg = it.double("sodium_mg"),
                            potassiumMg = it.double("potassium_mg"), ironMg = it.double("iron_mg"), caffeineMg = it.double("caffeine_mg"),
                            isTeaOrCoffee = MedicationJson.truthy(it["is_tea_or_coffee"])
                        )
                    },
                    bedtimeMs = i.long("bedtime_ms")
                ), cfg
            ).toJson()
            "dri_goals" -> IntakeNutrientGoals.driGoals(DriGoalsInput(i.str("sex"), i.double("age")), cfg).toJson()
            "nutrient_coverage" -> IntakeNutrientGoals.nutrientCoverage(
                NutrientCoverageInput(
                    days = objects(i["days"]).map { CoverageDay(it.str("day")!!, numMap(it["totals"])) },
                    goals = numMap(i["goals"]), limits = numMap(i["limits"])
                ), cfg
            ).toJson()
            "supplement_daily" -> IntakeNutrientGoals.supplementDaily(
                SupplementDailyInput(
                    amountPerDose = i.double("amount_per_dose")!!, dosesTakenMs = objects(i["doses"]).map { it.long("taken_ms") ?: 0L },
                    windowDays = i.double("window_days")!!, upper = i.double("upper")
                ), cfg
            ).toJson()
            "meds_adherence" -> MedicationAdherence.medsAdherence(
                MedsAdherenceInput(
                    timeZone = i.str("time_zone")!!,
                    logs = objects(i["logs"]).map { AdherenceLog(it.long("scheduled_ms")!!, it.long("taken_ms"), it.str("status")!!) }
                ), cfg
            ).toJson()
            "strength_week" -> StrengthWeek.strengthWeek(
                StrengthWeekInput(
                    sessions = objects(i["sessions"]).map { s ->
                        StrengthSession(s.str("day").orEmpty(), objects(s["exercises"]).map { e ->
                            StrengthExercise(
                                e.str("name")!!, MedicationJson.strings(e["primary_muscles"]),
                                objects(e["sets"]).map { st -> StrengthSet(st.double("reps"), st.double("weight_kg")) }
                            )
                        })
                    },
                    plannedSessions = i.int("planned_sessions")
                ), cfg
            ).toJson()
            "energy_balance" -> EnergyBalance.energyBalance(
                EnergyBalanceInput(i.str("day")!!, numMapNotNull(i["intake"]), numMapNotNull(i["tdee"]), numMapNotNull(i["weights"])), cfg
            ).toJson()
            "paired_difference" -> EnergyBalance.pairedDifference(
                PairedDifferenceInput(
                    exposure = (i.objOrNull("exposure")?.entries.orEmpty()).associate { it.key to MedicationJson.truthy(it.value) },
                    outcome = numMap(i["outcome"]), lagDays = i.int("lag_days") ?: 0
                ), cfg
            ).toJson()
            "lab_nutrient_links" -> LabLinks.labNutrientLinks(
                LabLinksInput(
                    labs = objects(i["labs"]).map { LabValue(it.str("analyte")!!, it.double("value")!!, it.double("ref_low"), it.double("ref_high")) },
                    intakeAvg = numMap(i["intake_avg"]), goals = numMap(i["goals"]),
                    supplementNutrients = MedicationJson.strings(i["supplement_nutrients"])
                ), cfg
            ).toJson()
            else -> error("unknown function $function")
        }
    }
}
