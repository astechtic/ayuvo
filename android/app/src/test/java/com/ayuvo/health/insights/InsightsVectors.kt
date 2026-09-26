package com.ayuvo.health.insights

import com.ayuvo.health.medications.logic.MedicationJson
import com.ayuvo.health.medications.logic.MedicationJson.arr
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
import java.time.LocalDate
import java.time.ZoneId

/** Locates the shared insights contract from the Gradle unit-test working directory (android/app). */
object InsightsTestFiles {
    fun shared(relative: String): File? =
        listOf("../../shared/insights/$relative", "../shared/insights/$relative", "shared/insights/$relative")
            .map(::File).firstOrNull { it.exists() }

    fun asset(relative: String): File? =
        listOf("src/main/assets/insights/$relative", "app/src/main/assets/insights/$relative").map(::File).firstOrNull { it.exists() }

    val config: InsightsConfig by lazy { InsightsConfig.parse(shared("insights_config.json")!!.readText()) }
    val prompts: InsightsAi.Prompts by lazy { InsightsAi.parsePrompts(shared("ai_explain.md")!!.readText()) }
}

/**
 * Runs `shared/insights/test-vectors/<file>` through the Kotlin engines (dispatch mirrors
 * `run_case`, compact encodings expanded like `decode_inputs`).
 */
object InsightsVectors {
    /** Every vector file and the function it must declare; the coverage test pins this to the folder. */
    val FILES = linkedMapOf(
        "baseline.json" to "baseline",
        "trend.json" to "trend",
        "overnight.json" to "overnight_value",
        "training_load.json" to "training_load",
        "recovery.json" to "recovery",
        "health_age.json" to "health_age",
        "health_age_pace.json" to "health_age_pace",
        "daily_review.json" to "daily_review",
        "patterns.json" to "patterns",
        "ai_summary.json" to "ai_summary"
    )

    data class Outcome(val file: String, val passed: Int, val total: Int, val failures: List<String>)

    fun run(file: String): Outcome {
        val f = InsightsTestFiles.shared("test-vectors/$file") ?: run { fail("shared/insights/test-vectors/$file missing"); error("") }
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
            if (diff == null) passed++ else failures += "$name: $diff"
        }
        return Outcome(file, passed, cases.size, failures)
    }

    fun assertAll(file: String) {
        val o = run(file)
        println("VECTORS insights/${o.file}: ${o.passed}/${o.total}")
        assertTrue("${o.file}: ${o.passed}/${o.total} passed\n" + o.failures.joinToString("\n"), o.failures.isEmpty() && o.total > 0)
    }

    // -- Decoding (compact encodings of docs/insights.md §2.1) ---------------------------------------

    private fun day(s: String): LocalDate = LocalDate.parse(s)

    private fun num(e: JsonElement?): Double? = (e as? JsonPrimitive)?.takeIf { it !is JsonNull && !it.isString }?.doubleOrNull

    fun series(e: JsonElement?): Map<LocalDate, Double> {
        val o = e as? JsonObject ?: return emptyMap()
        if (o.keys == setOf("start", "values")) {
            val start = day(o.str("start")!!)
            val out = LinkedHashMap<LocalDate, Double>()
            (o["values"] as JsonArray).forEachIndexed { i, v -> num(v)?.let { out[start.plusDays(i.toLong())] = it } }
            return out
        }
        return o.entries.mapNotNull { (k, v) -> num(v)?.let { day(k) to it } }.toMap()
    }

    fun nights(e: JsonElement?): Map<LocalDate, SleepInput> {
        val o = e as? JsonObject ?: return emptyMap()
        if (o.keys == setOf("start", "nights")) {
            val start = day(o.str("start")!!)
            val out = LinkedHashMap<LocalDate, SleepInput>()
            (o["nights"] as JsonArray).forEachIndexed { i, n ->
                if (n is JsonArray) out[start.plusDays(i.toLong())] = SleepInput(num(n[0]), num(n[1])!!.toLong(), num(n[2])!!.toLong())
            }
            return out
        }
        return o.entries.mapNotNull { (k, v) ->
            val n = v as? JsonObject ?: return@mapNotNull null
            day(k) to SleepInput(n.double("asleep_min"), n.long("start_ms")!!, n.long("end_ms")!!)
        }.toMap()
    }

    fun workouts(e: JsonElement?): List<WorkoutInput> = (e as? JsonArray).orEmpty().map {
        val o = it as JsonObject
        WorkoutInput(o.long("start_ms")!!, o.long("end_ms")!!, o.double("effort"))
    }

    private fun bool(o: JsonObject?, key: String): Boolean? = (o?.get(key) as? JsonPrimitive)?.booleanOrNull

    /** Only the fields the Daily Review reads from a `recovery()` result. */
    private fun recoveryBrief(o: JsonObject?): RecoveryResult? = o?.let {
        RecoveryResult(
            day = LocalDate.MIN, status = it.str("status") ?: "", score = it.int("score"), label = it.str("label"), labelText = null,
            recommendation = null, confidence = null, collecting = null, components = emptyList(), positives = emptyList(),
            negatives = emptyList(), load = null
        )
    }

    private fun patternBrief(o: JsonObject): PatternResult = PatternResult(
        id = o.str("id")!!, status = o.str("status") ?: "ok", nExposed = o.int("n_exposed") ?: 0, nUnexposed = o.int("n_unexposed") ?: 0,
        needed = o.int("needed") ?: 0, meanExposed = null, meanUnexposed = null, diff = null, t = null, d = null,
        surfaced = bool(o, "surfaced") == true, text = o.str("text"), reviewCategory = o.str("review_category")
    )

    fun inputs(o: JsonObject): InsightsInputs {
        val tracking = o.objOrNull("tracking")
        return InsightsInputs(
            timeZone = ZoneId.of(o.str("time_zone") ?: "UTC"),
            hrvKind = o.str("hrv_kind") ?: "sdnn",
            series = o.objOrNull("series")?.entries?.associate { (k, v) -> k to series(v) }.orEmpty(),
            sleep = nights(o["sleep"]),
            workouts = workouts(o["workouts"]),
            overnightFallback = MedicationJson.strings(o["overnight_fallback"]).toSet(),
            tracking = TrackingFlags(
                nutrition = bool(tracking, "nutrition") ?: true,
                water = bool(tracking, "water") == true,
                workouts = bool(tracking, "workouts") == true,
                fasting = bool(tracking, "fasting") == true
            ),
            targets = o.objOrNull("targets")?.entries?.mapNotNull { (k, v) -> num(v)?.let { k to it } }?.toMap().orEmpty(),
            nutrition = o.objOrNull("nutrition")?.entries?.associate { (k, v) ->
                day(k) to (v as JsonObject).entries.mapNotNull { (nk, nv) -> num(nv)?.let { nk to it } }.toMap()
            }.orEmpty(),
            waterMl = series(o["water_ml"]),
            fastingHours = series(o["fasting_hours"]),
            strengthVolume = series(o["strength_volume"]),
            recoveryScores = series(o["recovery_scores"]),
            recovery = recoveryBrief(o.objOrNull("recovery")),
            patterns = (o["patterns"] as? JsonArray)?.map { patternBrief(it as JsonObject) }
        )
    }

    private fun profile(o: JsonObject?): InsightsProfile = InsightsProfile(
        birthday = o?.str("birthday")?.let(::day), sex = o?.str("sex"), heightCm = o?.double("height_cm")
    )

    fun runCase(function: String, input: JsonObject): JsonElement {
        val cfg = InsightsTestFiles.config
        return when (function) {
            "baseline" -> InsightsJson.baseline(BaselineEngine.baseline(series(input["series"]), day(input.str("day")!!), cfg.metric(input.str("metric")!!)))
            "trend" -> InsightsJson.trend(BaselineEngine.trend(series(input["series"]), day(input.str("day")!!), cfg.metric(input.str("metric")!!)))
            "overnight_value" -> {
                val samples = input.arr("samples").orEmpty().map { val s = it as JsonObject; s.long("t_ms")!! to s.double("value")!! }
                val night = input.objOrNull("night")?.let { SleepInput(null, it.long("start_ms")!!, it.long("end_ms")!!) }
                InsightsJson.overnight(BaselineEngine.overnightValue(samples, night, input.double("fallback_value")))
            }
            "training_load" -> InsightsJson.trainingLoad(
                TrainingLoad.trainingLoad(workouts(input["workouts"]), day(input.str("day")!!), ZoneId.of(input.str("time_zone")!!), cfg)
            )
            "recovery" -> InsightsJson.recovery(RecoveryEngine.recovery(inputs(input.objOrNull("inputs")!!), day(input.str("day")!!), cfg))
            "health_age" -> InsightsJson.healthAge(
                HealthAgeEngine.healthAge(inputs(input.objOrNull("inputs")!!), day(input.str("as_of")!!), profile(input.objOrNull("profile")), cfg)
            )
            "health_age_pace" -> InsightsJson.pace(
                HealthAgeEngine.pace(inputs(input.objOrNull("inputs")!!), day(input.str("as_of")!!), profile(input.objOrNull("profile")), cfg)
            )
            "daily_review" -> InsightsJson.review(DailyReviewEngine.review(inputs(input.objOrNull("inputs")!!), day(input.str("day")!!), cfg))
            "patterns" -> InsightsJson.patterns(PatternEngine.patterns(inputs(input.objOrNull("inputs")!!), day(input.str("as_of")!!), cfg))
            "ai_summary" -> {
                val payload = InsightsAi.payload(input.objOrNull("summary")!!)
                val prompt = InsightsAi.buildPrompt(input.str("kind")!!, payload, input.str("variant")!!, cfg, InsightsTestFiles.prompts)
                MedicationJson.obj(
                    "payload" to payload,
                    "prompt" to MedicationJson.obj("system" to prompt.system, "user" to prompt.user),
                    "validations" to MedicationJson.strings(input["outputs"]).map { text ->
                        val v = InsightsAi.validate(text, payload, cfg)
                        MedicationJson.obj(
                            "ok" to v.ok, "errors" to v.errors,
                            "output" to v.output?.let { MedicationJson.obj("headline" to it.headline, "bullets" to it.bullets) }
                        )
                    }
                )
            }
            else -> error("unknown function $function")
        }
    }
}
