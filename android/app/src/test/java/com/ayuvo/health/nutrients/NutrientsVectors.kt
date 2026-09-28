package com.ayuvo.health.nutrients

import com.ayuvo.health.medications.logic.MedicationJson
import com.ayuvo.health.medications.logic.MedicationJson.str
import com.ayuvo.health.records.processing.RecordsVectors
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import java.io.File
import java.time.ZoneId

/** Locates the shared nutrients contract from the Gradle unit-test working directory (android/app). */
object NutrientsTestFiles {
    fun shared(relative: String): File? =
        listOf("../../shared/nutrients/$relative", "../shared/nutrients/$relative", "shared/nutrients/$relative")
            .map(::File).firstOrNull { it.exists() }

    fun asset(relative: String): File? =
        listOf("src/main/assets/nutrients/$relative", "app/src/main/assets/nutrients/$relative").map(::File).firstOrNull { it.exists() }

    val reference: NutrientReference by lazy { NutrientReference.parse(shared("nutrient_reference.json")!!.readText()) }

    /** Installs the shared reference as [NutrientReference.active] (the app installs the asset copy). */
    fun install() {
        NutrientReference.active = reference
    }
}

/**
 * Runs `shared/nutrients/test-vectors/<file>` through the Kotlin port (dispatch mirrors
 * `run_case` of scripts/nutrients_reference.py) and compares by the records rules.
 */
object NutrientsVectors {
    val FILES = linkedMapOf(
        "reference_lines.json" to "reference_lines",
        "default_goal.json" to "default_goal",
        "iu_conversion.json" to "convert_amount",
        "supplement_entries.json" to "supplement_entries",
        "day_totals.json" to "day_totals",
        "logged_day_average.json" to "logged_day_average",
        "label_output.json" to "parse_label_output"
    )

    data class Outcome(val file: String, val passed: Int, val total: Int, val failures: List<String>)

    fun run(file: String): Outcome {
        NutrientsTestFiles.install()
        val f = NutrientsTestFiles.shared("test-vectors/$file") ?: run { fail("shared/nutrients/test-vectors/$file missing"); error("") }
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
        println("VECTORS nutrients/${o.file}: ${o.passed}/${o.total}")
        assertTrue("${o.file}: ${o.passed}/${o.total} passed\n" + o.failures.joinToString("\n"), o.failures.isEmpty() && o.total > 0)
    }

    // -- decoding -------------------------------------------------------------------------------

    /** Python `_is_number`: a JSON number (never a string or bool); anything else → null. */
    private fun num(e: JsonElement?): Double? {
        val p = (e as? JsonPrimitive)?.takeIf { it !is JsonNull } ?: return null
        if (p.isString || p.booleanOrNull != null) return null
        return p.doubleOrNull
    }

    private fun long(e: JsonElement?): Long? {
        val p = (e as? JsonPrimitive)?.takeIf { it !is JsonNull && !it.isString && it.booleanOrNull == null } ?: return null
        return if (Regex("^-?[0-9]+$").matches(p.content)) p.longOrNull else null
    }

    private fun str(e: JsonElement?): String? = (e as? JsonPrimitive)?.takeIf { it !is JsonNull && it.isString }?.content

    private fun objects(e: JsonElement?): List<JsonObject> = (e as? JsonArray).orEmpty().map { it as JsonObject }

    private fun profile(o: JsonObject?): NutrientProfile =
        NutrientProfile(age = num(o?.get("age")), sex = str(o?.get("sex")), calorieGoal = num(o?.get("calorie_goal")))

    private fun supplementEntries(e: JsonElement?): List<SupplementEntry> = objects(e).map {
        SupplementEntry(long(it["t_ms"])!!, str(it["nutrient_key"])!!, num(it["value"]) ?: Double.NaN, str(it["medication_id"]).orEmpty())
    }

    fun runCase(function: String, input: JsonObject): JsonElement = when (function) {
        "reference_lines" -> {
            val r = Nutrients.referenceLines(str(input["key"])!!, profile(input["profile"] as? JsonObject), num(input["custom_goal"]))
            MedicationJson.obj(
                "key" to r.key, "band" to r.band, "sex" to r.sex, "style" to r.style, "unit" to r.unit,
                "recommended" to r.recommended, "upper_limit" to r.upperLimit, "limit" to r.limit,
                "recommended_label" to r.recommendedLabel, "limit_label" to r.limitLabel, "upper_limit_scope" to r.upperLimitScope,
                "reference_recommended" to r.referenceRecommended, "reference_limit" to r.referenceLimit,
                "recommended_kind" to r.recommendedKind, "source_ids" to r.sourceIds, "error" to r.error
            )
        }
        "default_goal" -> {
            val v = Nutrients.defaultGoal(str(input["key"])!!, profile(input["profile"] as? JsonObject))
            MedicationJson.obj("value" to v, "value_int" to Nutrients.defaultGoalInt(v))
        }
        "convert_amount" -> {
            val c = Nutrients.convertAmount(num(input["value"]), str(input["unit"]), str(input["key"])!!, str(input["form"]))
            MedicationJson.obj("ok" to c.ok, "amount" to c.amount, "unit" to c.unit, "error" to c.error)
        }
        "supplement_entries" -> {
            val rows = objects(input["medication_nutrients"]).map {
                MedicationNutrientRow(str(it["medication_id"])!!, str(it["nutrient_key"])!!, num(it["amount_per_unit"])!!)
            }
            val doses = objects(input["dose_logs"]).map {
                SupplementDose(str(it["medication_id"])!!, str(it["status"]).orEmpty(), long(it["taken_at_ms"]), num(it["dose_quantity"]))
            }
            MedicationJson.obj("entries" to Nutrients.supplementEntries(rows, doses).map {
                MedicationJson.obj("t_ms" to it.tMs, "nutrient_key" to it.nutrientKey, "value" to it.value, "medication_id" to it.medicationId)
            })
        }
        "day_totals" -> {
            val food = objects(input["food_entries"]).map { e ->
                val n = (e["nutrients"] as? JsonObject).orEmpty()
                FoodNutrients(long(e["t_ms"])!!, n.entries.associate { (k, v) -> k to num(v) })
            }
            val totals = Nutrients.dayTotals(food, supplementEntries(input["supplement_entries"]), str(input["day"])!!, ZoneId.of(str(input["time_zone"])!!))
            MedicationJson.obj("totals" to MedicationJson.obj(*totals.map { (k, a) ->
                k to MedicationJson.obj("food" to a.food, "supplements" to a.supplements, "total" to a.total)
            }.toTypedArray()))
        }
        "logged_day_average" -> {
            val entries = objects(input["entries"]).map { NutrientValueEntry(long(it["t_ms"])!!, num(it["value"])) }
            val interval = input["interval"] as JsonObject
            val days = (input["logged_days"] as? JsonArray).orEmpty().mapNotNull { str(it) }
            val r = Nutrients.loggedDayAverage(
                entries, days, long(interval["start_ms"])!!, long(interval["end_ms"])!!, ZoneId.of(str(input["time_zone"])!!),
                key = str(input["key"])
            )
            MedicationJson.obj("average" to r.average, "logged_days" to r.loggedDays)
        }
        "parse_label_output" -> {
            val r = NutrientLabel.parse(str(input["text"]))
            MedicationJson.obj(
                "ok" to r.ok, "error" to r.error, "serving_units" to r.servingUnits,
                "items" to r.items.map { MedicationJson.obj("key" to it.key, "amount" to it.amount, "unit" to it.unit, "form" to it.form) },
                "rejected" to r.rejected.map { MedicationJson.obj("index" to it.index, "code" to it.code) }
            )
        }
        else -> error("unknown function $function")
    }
}
