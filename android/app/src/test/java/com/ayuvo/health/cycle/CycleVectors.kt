package com.ayuvo.health.cycle

import com.ayuvo.health.cycle.engine.CycleConfig
import com.ayuvo.health.cycle.engine.CycleDayLogInput
import com.ayuvo.health.cycle.engine.CycleDayStatus
import com.ayuvo.health.cycle.engine.CycleDays
import com.ayuvo.health.cycle.engine.CycleEngine
import com.ayuvo.health.cycle.engine.CycleNormalizedPeriod
import com.ayuvo.health.cycle.engine.CyclePeriodInput
import com.ayuvo.health.cycle.engine.CycleReminderInput
import com.ayuvo.health.cycle.engine.CycleSettingsInput
import com.ayuvo.health.cycle.engine.CycleState
import com.ayuvo.health.cycle.engine.CycleStats
import com.ayuvo.health.cycle.engine.CycleTrends
import com.ayuvo.health.records.processing.RecordsVectors
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import java.io.File

/** Locates the shared cycle contract from the Gradle unit-test working directory (android/app). */
object CycleTestFiles {
    fun shared(relative: String): File? =
        listOf("../../shared/cycle/$relative", "../shared/cycle/$relative", "shared/cycle/$relative")
            .map(::File).firstOrNull { it.exists() }

    fun asset(relative: String): File? =
        listOf("src/main/assets/cycle/$relative", "app/src/main/assets/cycle/$relative").map(::File).firstOrNull { it.exists() }

    val config: CycleConfig by lazy { CycleConfig.parse(shared("cycle_config.json")!!.readText()) }
    val engine: CycleEngine by lazy { CycleEngine(config) }
}

/** Runs `shared/cycle/test-vectors/<file>` through the Kotlin engine (dispatch mirrors `cycle_reference.FUNCTIONS`). */
object CycleVectors {
    val json = Json { isLenient = false }

    val FILES = linkedMapOf(
        "apply_period_day.json" to "apply_period_day",
        "cycles.json" to "cycles",
        "day_status.json" to "day_status",
        "days.json" to "days",
        "normalize.json" to "normalize",
        "settings.json" to "settings",
        "snapshot.json" to "snapshot",
        "trends.json" to "trends",
        "validate_period.json" to "validate_period"
    )

    fun assertAll(file: String) {
        val f = CycleTestFiles.shared("test-vectors/$file") ?: run { fail("shared/cycle/test-vectors/$file missing"); error("") }
        val root = json.parseToJsonElement(f.readText()) as JsonObject
        val function = str(root["function"])!!
        val failures = mutableListOf<String>()
        val cases = root["cases"] as JsonArray
        for (c in cases) {
            val case = c as JsonObject
            val name = str(case["name"]) ?: "?"
            val actual = try {
                runCase(function, case["input"] as JsonObject)
            } catch (e: Throwable) {
                failures += "$name: threw ${e.javaClass.simpleName}: ${e.message}"; continue
            }
            RecordsVectors.diff(case["expected"]!!, actual, "$")?.let { failures += "$name: $it" }
        }
        assertTrue("$file: ${failures.size}/${cases.size} failed\n" + failures.joinToString("\n"), failures.isEmpty())
    }

    // --- input decoding ---------------------------------------------------------------------

    private fun str(e: JsonElement?): String? = (e as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
    private fun int(e: JsonElement?): Int? = (e as? JsonPrimitive)?.takeIf { it !is JsonNull }?.intOrNull
    private fun bool(e: JsonElement?): Boolean? = (e as? JsonPrimitive)?.takeIf { it !is JsonNull }?.booleanOrNull
    private fun strs(e: JsonElement?): List<String> = (e as? JsonArray)?.map { (it as JsonPrimitive).content } ?: emptyList()

    private fun settings(e: JsonElement?): CycleSettingsInput? {
        val o = e as? JsonObject ?: return null
        val r = o["reminders"] as? JsonObject
        return CycleSettingsInput(
            int(o["cycle_length"]), int(o["period_length"]), int(o["luteal_length"]),
            r?.let { CycleReminderInput(bool(it["period_soon"]), int(it["days_before"]), bool(it["period_end"]), bool(it["daily"])) }
        )
    }

    private fun periods(e: JsonElement?): List<CyclePeriodInput> = (e as? JsonArray)?.map {
        val o = it as JsonObject
        CyclePeriodInput(str(o["id"])!!, str(o["start"])!!, str(o["end"]), str(o["source"]) ?: "app")
    } ?: emptyList()

    private fun logs(e: JsonElement?): List<CycleDayLogInput> = (e as? JsonArray)?.map {
        val o = it as JsonObject
        CycleDayLogInput(str(o["day"])!!, str(o["flow"]), int(o["pain"]), strs(o["pain_locations"]), strs(o["symptoms"]), strs(o["moods"]))
    } ?: emptyList()

    private fun state(o: JsonObject) = CycleState(str(o["today"])!!, settings(o["settings"]), periods(o["periods"]), logs(o["logs"]))

    // --- output encoding --------------------------------------------------------------------

    private fun p(v: Any?): JsonElement = when (v) {
        null -> JsonNull
        is String -> JsonPrimitive(v)
        is Int -> JsonPrimitive(v)
        is Double -> JsonPrimitive(v)
        is Boolean -> JsonPrimitive(v)
        is List<*> -> JsonArray(v.map(::p))
        is Map<*, *> -> JsonObject(v.entries.associate { it.key as String to p(it.value) })
        is JsonElement -> v
        else -> error("cannot encode ${v::class}")
    }

    private fun obj(vararg pairs: Pair<String, Any?>): JsonObject = JsonObject(pairs.associate { it.first to p(it.second) })

    private fun period(n: CycleNormalizedPeriod) = obj(
        "id" to n.id, "start" to n.start, "end" to n.end, "ongoing" to n.ongoing, "auto_closed" to n.autoClosed,
        "source" to n.source, "members" to n.members, "length" to n.length, "valid" to n.valid
    )

    private fun stats(s: CycleStats) = obj(
        "cycle_count" to s.cycleCount, "cycle_lengths" to s.cycleLengths, "cycle_median" to s.cycleMedian,
        "cycle_mean" to s.cycleMean, "cycle_sd" to s.cycleSd, "cycle_range" to s.cycleRange,
        "variability" to s.variability, "period_count" to s.periodCount, "period_median" to s.periodMedian
    )

    private fun status(d: CycleDayStatus) = obj(
        "day" to d.day, "cycle_day" to d.cycleDay, "phase" to d.phase, "period_day" to d.periodDay, "estimated" to d.estimated
    )

    private fun trends(t: CycleTrends) = obj(
        "cycles" to t.cycles.map {
            obj(
                "start" to it.start, "cycle_length" to it.cycleLength, "period_length" to it.periodLength,
                "pain_max" to it.painMax, "pain_mean" to it.painMean, "symptom_days" to it.symptomDays,
                "mood_days" to it.moodDays, "flow" to it.flow
            )
        },
        "symptom_frequency" to t.symptomFrequency.map { obj("key" to it.key, "cycles" to it.cycles) },
        "mood_frequency" to t.moodFrequency.map { obj("key" to it.key, "cycles" to it.cycles) },
        "flow_pattern" to t.flowPattern, "window_cycles" to t.windowCycles
    )

    fun runCase(function: String, inp: JsonObject): JsonElement {
        val engine = CycleTestFiles.engine
        return when (function) {
            "days" -> JsonArray(strs(inp["days"]).map {
                val n = CycleDays.ordinal(it)
                obj("day" to it, "ordinal" to n, "back" to CycleDays.string(n), "next" to CycleDays.string(n + 1), "prev" to CycleDays.string(n - 1))
            })
            "settings" -> engine.effectiveSettings(settings(inp["settings"])).let { s ->
                obj(
                    "cycle_length" to s.cycleLength, "period_length" to s.periodLength, "luteal_length" to s.lutealLength,
                    "reminders" to obj(
                        "period_soon" to s.reminders.periodSoon, "days_before" to s.reminders.daysBefore,
                        "period_end" to s.reminders.periodEnd, "daily" to s.reminders.daily
                    )
                )
            }
            "normalize" -> engine.normalize(str(inp["today"])!!, periods(inp["periods"]), settings(inp["settings"])).let { n ->
                obj("periods" to n.periods.map(::period), "dropped" to n.dropped.map { obj("id" to it.id, "reason" to it.reason) })
            }
            "cycles" -> engine.cycles(state(inp)).let { (rows, s) ->
                obj(
                    "cycles" to rows.map {
                        obj(
                            "start" to it.start, "cycle_length" to it.cycleLength, "period_length" to it.periodLength,
                            "cycle_valid" to it.cycleValid, "period_valid" to it.periodValid
                        )
                    },
                    "stats" to stats(s)
                )
            }
            "snapshot" -> engine.snapshot(state(inp)).let { s ->
                val pr = s.prediction
                obj(
                    "periods" to s.periods.map(::period), "stats" to stats(s.stats),
                    "prediction" to obj(
                        "basis" to pr.basis, "cycle_length" to pr.cycleLength, "cycle_range" to pr.cycleRange,
                        "period_length" to pr.periodLength, "luteal_length" to pr.lutealLength,
                        "next_start" to pr.nextStart, "next_range" to pr.nextRange, "raw_next_start" to pr.rawNextStart,
                        "late_days" to pr.lateDays, "ongoing" to pr.ongoing, "expected_end" to pr.expectedEnd,
                        "current_cycle_day" to pr.currentCycleDay,
                        "windows" to pr.windows.map {
                            obj(
                                "cycle_start" to it.cycleStart, "predicted_start" to it.predictedStart,
                                "ovulation" to it.ovulation, "fertile" to it.fertile, "period_end" to it.periodEnd
                            )
                        }
                    ),
                    "today" to status(s.today),
                    "insights" to s.insights.map { obj("key" to it.key, "params" to it.params) },
                    "reminders" to s.reminders.map { obj("kind" to it.kind, "day" to it.day) }
                )
            }
            "day_status" -> JsonArray(engine.dayStatus(state(inp), str(inp["from"])!!, str(inp["to"])!!).map(::status))
            "trends" -> trends(engine.trends(state(inp)))
            "validate_period" -> {
                val c = inp["candidate"] as JsonObject
                engine.validatePeriod(str(inp["today"])!!, str(c["id"]), str(c["start"])!!, str(c["end"]), periods(inp["periods"])).let {
                    obj("ok" to it.ok, "errors" to it.errors, "overlaps" to it.overlaps, "merged" to it.merged, "duration" to it.duration)
                }
            }
            "apply_period_day" -> engine.applyPeriodDay(
                str(inp["today"])!!, str(inp["day"])!!, bool(inp["on"])!!, periods(inp["periods"])
            ).let { r ->
                obj(
                    "ops" to r.ops.map { op ->
                        when (op.op) {
                            "insert" -> obj("op" to "insert", "start" to op.start, "end" to op.end)
                            "delete" -> obj("op" to "delete", "id" to op.id)
                            else -> obj("op" to op.op, "id" to op.id, "start" to op.start, "end" to op.end)
                        }
                    },
                    "error" to r.error
                )
            }
            else -> error("unknown function $function")
        }
    }
}
