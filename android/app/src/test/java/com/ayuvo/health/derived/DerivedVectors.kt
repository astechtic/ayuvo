package com.ayuvo.health.derived

import com.ayuvo.health.data.derived.ActivityDayInput
import com.ayuvo.health.data.derived.ActivityDerivation
import com.ayuvo.health.data.derived.AudioDayInput
import com.ayuvo.health.data.derived.AudioSample
import com.ayuvo.health.data.derived.BodyDerivation
import com.ayuvo.health.data.derived.BodyTrendInput
import com.ayuvo.health.data.derived.DerivedConfig
import com.ayuvo.health.data.derived.DerivedPriority
import com.ayuvo.health.data.derived.EnergyDayInput
import com.ayuvo.health.data.derived.EnergyDerivation
import com.ayuvo.health.data.derived.GaitWeekInput
import com.ayuvo.health.data.derived.HeartDayInput
import com.ayuvo.health.data.derived.HeartDerivation
import com.ayuvo.health.data.derived.HeightConflictInput
import com.ayuvo.health.data.derived.HeightReading
import com.ayuvo.health.data.derived.HrMaxInput
import com.ayuvo.health.data.derived.MinuteSeries
import com.ayuvo.health.data.derived.MobilityDerivation
import com.ayuvo.health.data.derived.NativeReading
import com.ayuvo.health.data.derived.PriorityInput
import com.ayuvo.health.data.derived.RhrStrainInput
import com.ayuvo.health.data.derived.SleepDerivation
import com.ayuvo.health.data.derived.SleepNightInput
import com.ayuvo.health.data.derived.SleepNightsInput
import com.ayuvo.health.data.derived.SleepRegularityInput
import com.ayuvo.health.data.derived.SleepRow
import com.ayuvo.health.data.derived.Span
import com.ayuvo.health.data.derived.StepSource
import com.ayuvo.health.data.derived.StepStreakInput
import com.ayuvo.health.data.derived.StrideInput
import com.ayuvo.health.data.derived.Vo2MaxInput
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
import kotlinx.serialization.json.longOrNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import java.io.File
import java.time.LocalDate
import java.time.ZoneId

/** Locates the shared derived-metrics contract from the Gradle unit-test working directory (android/app). */
object DerivedTestFiles {
    fun shared(relative: String): File? =
        listOf("../../shared/derived/$relative", "../shared/derived/$relative", "shared/derived/$relative")
            .map(::File).firstOrNull { it.exists() }

    fun asset(relative: String): File? =
        listOf("src/main/assets/derived/$relative", "app/src/main/assets/derived/$relative").map(::File).firstOrNull { it.exists() }

    val config: DerivedConfig by lazy { DerivedConfig.parse(shared("derived_config.json")!!.readText()) }
}

/**
 * Runs `shared/derived/test-vectors/<file>` through the Kotlin derivations (dispatch mirrors
 * `derived_reference.FUNCTIONS` / `run_case`).
 */
object DerivedVectors {
    /** Every vector file and the function it must declare; the coverage test pins this to the folder. */
    val FILES = linkedMapOf(
        "heart_day.json" to "heart_day",
        "hr_max.json" to "hr_max",
        "vo2max_uth.json" to "vo2max_uth",
        "rhr_strain.json" to "rhr_strain",
        "sleep_nights.json" to "sleep_nights",
        "sleep_night.json" to "sleep_night",
        "sleep_regularity.json" to "sleep_regularity",
        "activity_day.json" to "activity_day",
        "step_streak.json" to "step_streak",
        "stride.json" to "stride",
        "energy_day.json" to "energy_day",
        "gait_week.json" to "gait_week",
        "audio_day.json" to "audio_day",
        "body_trend.json" to "body_trend",
        "height_conflict.json" to "height_conflict",
        "priority.json" to "priority"
    )

    data class Outcome(val file: String, val passed: Int, val total: Int, val failures: List<String>)

    fun run(file: String): Outcome {
        val f = DerivedTestFiles.shared("test-vectors/$file") ?: run { fail("shared/derived/test-vectors/$file missing"); error("") }
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
        println("VECTORS derived/${o.file}: ${o.passed}/${o.total}")
        assertTrue("${o.file}: ${o.passed}/${o.total} passed\n" + o.failures.joinToString("\n"), o.failures.isEmpty() && o.total > 0)
    }

    // -- Decoding --------------------------------------------------------------------------------

    private fun day(s: String): LocalDate = LocalDate.parse(s)

    private fun zone(o: JsonObject): ZoneId = ZoneId.of(o.str("time_zone")!!)

    private fun num(e: JsonElement?): Double? =
        (e as? JsonPrimitive)?.takeIf { it !is JsonNull && !it.isString && it.booleanOrNull == null }?.doubleOrNull

    private fun lng(e: JsonElement?): Long = (e as JsonPrimitive).let { it.longOrNull ?: it.doubleOrNull!!.toLong() }

    private fun numbers(e: JsonElement?): List<Double?> = (e as? JsonArray).orEmpty().map(::num)

    private fun minuteSeries(e: JsonElement?): MinuteSeries? {
        val o = e as? JsonObject ?: return null
        return MinuteSeries(o.long("start_ms")!!, numbers(o["values"]))
    }

    private fun span(e: JsonElement?): Span? = (e as? JsonObject)?.let { Span(it.long("start_ms")!!, it.long("end_ms")!!) }

    private fun rows(e: JsonElement?): List<SleepRow> = (e as? JsonArray).orEmpty().map { r ->
        val a = r as JsonArray
        SleepRow(lng(a[0]), lng(a[1]), lng(a[2]).toInt(), a.getOrNull(3)?.let { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content })
    }

    /** `{day: number | null}`; null values are absent, which is how the reference's `s.get(d) is not None` reads them. */
    private fun daySeries(e: JsonElement?): Map<LocalDate, Double> =
        (e as? JsonObject)?.entries?.mapNotNull { (k, v) -> num(v)?.let { day(k) to it } }?.toMap().orEmpty()

    private fun bool(o: JsonObject, key: String): Boolean = (o[key] as? JsonPrimitive)?.booleanOrNull == true

    fun runCase(function: String, i: JsonObject): JsonElement {
        val cfg = DerivedTestFiles.config
        return when (function) {
            "heart_day" -> HeartDerivation.heartDay(
                HeartDayInput(
                    zone = zone(i), day = day(i.str("day")!!), sex = i.str("sex"), hr = minuteSeries(i["hr"]),
                    steps = minuteSeries(i["steps"]), night = span(i["night"]), hrMax = i.double("hr_max")!!, rhrRef = i.double("rhr_ref")
                ), cfg
            ).toJson()
            "hr_max" -> HeartDerivation.hrMax(HrMaxInput(i.double("age")!!, numbers(i["observed"]), i.double("rhr")), cfg).toJson()
            "vo2max_uth" -> HeartDerivation.vo2maxUth(Vo2MaxInput(i.double("hr_max"), i.str("hr_max_method"), i.double("rhr")), cfg).toJson()
            "rhr_strain" -> HeartDerivation.rhrStrain(RhrStrainInput(daySeries(i["series"]), day(i.str("day")!!)), cfg).toJson()
            "sleep_nights" -> SleepDerivation.sleepNights(SleepNightsInput(zone(i), rows(i["rows"])), cfg).toJson()
            "sleep_night" -> SleepDerivation.sleepNight(SleepNightInput(zone(i), day(i.str("wake_day")!!), rows(i["rows"])), cfg).toJson()
            "sleep_regularity" -> SleepDerivation.sleepRegularity(
                SleepRegularityInput(
                    zone = zone(i), day = day(i.str("day")!!), needMin = i.double("need_min"),
                    nights = i.objOrNull("nights")?.entries?.mapNotNull { (k, v) ->
                        val n = v as? JsonObject
                        if (n == null || n.isEmpty()) null else day(k) to rows(n["rows"])
                    }?.toMap().orEmpty()
                ), cfg
            ).toJson()
            "activity_day" -> ActivityDerivation.activityDay(
                ActivityDayInput(
                    zone = zone(i), day = day(i.str("day")!!),
                    sources = i.objOrNull("sources")?.entries?.associate { (k, v) ->
                        val s = v as JsonObject
                        k to StepSource(s.str("kind")!!, numbers(s["hourly"]))
                    }.orEmpty(),
                    minuteSteps = minuteSeries(i["minute_steps"]), wear = minuteSeries(i["wear"]), stepsTotal = i.double("steps_total")
                ), cfg
            ).toJson()
            "step_streak" -> ActivityDerivation.stepStreak(
                StepStreakInput(daySeries(i["series"]), day(i.str("day")!!), i.double("goal")!!, i.int("window_days")!!), cfg
            ).toJson()
            "stride" -> ActivityDerivation.stride(StrideInput(i.double("distance_m"), i.double("steps"), i.double("height_cm"), i.str("sex")), cfg).toJson()
            "energy_day" -> EnergyDerivation.energyDay(
                EnergyDayInput(i.double("resting_kcal"), i.double("active_kcal"), i.double("weight_kg"), i.double("height_cm"), i.double("age"), i.str("sex")),
                cfg
            ).toJson()
            "gait_week" -> MobilityDerivation.gaitWeek(
                GaitWeekInput(numbers(i["walking_speed"]), numbers(i["double_support"]), numbers(i["asymmetry"])), cfg
            ).toJson()
            "audio_day" -> MobilityDerivation.audioDay(
                AudioDayInput((i["samples"] as? JsonArray).orEmpty().map { s ->
                    val a = s as JsonArray
                    AudioSample(lng(a[0]), lng(a[1]), num(a[2])!!)
                }), cfg
            ).toJson()
            "body_trend" -> BodyDerivation.bodyTrend(
                BodyTrendInput(daySeries(i["weights"]), day(i.str("day")!!), i.double("height_m"), i.double("goal_kg"), i.str("scheme")), cfg
            ).toJson()
            "height_conflict" -> BodyDerivation.heightConflict(
                HeightConflictInput((i["heights"] as? JsonArray).orEmpty().map { h ->
                    val o = h as JsonObject
                    HeightReading(o.double("value_m")!!, o.str("source"))
                }), cfg
            ).toJson()
            "priority" -> DerivedPriority.priority(
                PriorityInput(
                    enabled = bool(i, "enabled"),
                    native = i.objOrNull("native")?.let { NativeReading(it.double("value"), it.str("source")) },
                    derived = i.double("derived")
                ), cfg
            ).toJson()
            else -> error("unknown function $function")
        }
    }
}
