package com.ayuvo.health.medications.logic

import com.ayuvo.health.medications.logic.MedicationJson.int
import com.ayuvo.health.medications.logic.MedicationJson.long
import com.ayuvo.health.medications.logic.MedicationJson.objOrNull
import com.ayuvo.health.medications.logic.MedicationJson.str
import com.ayuvo.health.medications.logic.MedicationJson.toJson
import com.ayuvo.health.medications.model.DoseAction
import com.ayuvo.health.medications.model.LifecycleAction
import com.ayuvo.health.medications.model.LifecycleOp
import com.ayuvo.health.medications.model.MedicationsSnapshot
import com.ayuvo.health.records.processing.RecordsVectors
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import java.io.File

/** Locates shared medication contract files from the Gradle unit-test working directory (android/app). */
object MedicationsTestFiles {
    fun shared(relative: String): File? =
        listOf("../../shared/medications/$relative", "../shared/medications/$relative", "shared/medications/$relative")
            .map(::File).firstOrNull { it.exists() }
}

/**
 * Runs `shared/medications/test-vectors/<file>` through the Kotlin ports (dispatch mirrors the
 * reference `run_case`) and compares with the expected JSON using the records comparison rules.
 */
object MedicationsVectors {
    private val json = MedicationJson.json

    data class Outcome(val file: String, val passed: Int, val total: Int, val failures: List<String>)

    fun run(file: String): Outcome {
        val f = MedicationsTestFiles.shared("test-vectors/$file") ?: run { fail("shared/medications/test-vectors/$file missing"); error("") }
        val root = json.parseToJsonElement(f.readText()) as JsonObject
        val function = root.str("function") ?: error("$file has no function")
        val cases = (root["cases"] as? JsonArray) ?: error("$file has no cases")
        val failures = mutableListOf<String>()
        var passed = 0
        for (c in cases) {
            val case = c as JsonObject
            val name = case.str("name") ?: "?"
            val expected = case["expected"]!!
            val actual = try {
                runCase(function, case["input"] as JsonObject)
            } catch (e: Throwable) {
                failures += "$name: threw ${e.javaClass.simpleName}: ${e.message}\n    ${e.stackTrace.take(4).joinToString("\n    ")}"
                continue
            }
            val diff = RecordsVectors.diff(expected, actual, "$")
            if (diff == null) passed++ else failures += "$name: $diff\n    expected=${expected.toString().take(1500)}\n    actual  =${actual.toString().take(1500)}"
        }
        return Outcome(file, passed, cases.size, failures)
    }

    fun assertAll(file: String) {
        val o = run(file)
        println("VECTORS medications/${o.file}: ${o.passed}/${o.total}")
        assertTrue("${o.file}: ${o.passed}/${o.total} passed\n" + o.failures.joinToString("\n"), o.failures.isEmpty() && o.total > 0)
    }

    // -- dispatch ---------------------------------------------------------------------------------

    private fun objects(e: JsonElement?): List<JsonObject> = (e as? JsonArray).orEmpty().map { it as JsonObject }
    private fun meds(input: JsonObject) = objects(input["medications"]).map(MedicationJson::medication)
    private fun scheds(input: JsonObject) = objects(input["schedules"]).map(MedicationJson::schedule)
    private fun logs(input: JsonObject) = objects(input["logs"]).map(MedicationJson::doseLog)
    private fun occs(input: JsonObject) = objects(input["occurrences"]).map(MedicationJson::occurrence)
    private fun snapshot(o: JsonObject?) = MedicationsSnapshot(
        medications = objects(o?.get("medications")).map(MedicationJson::medication),
        schedules = objects(o?.get("schedules")).map(MedicationJson::schedule),
        doseLogs = objects(o?.get("dose_logs")).map(MedicationJson::doseLog)
    )
    private fun double(o: JsonObject, key: String): Double? = (o[key] as? JsonPrimitive)?.takeIf { it !is JsonNull && !it.isString }?.doubleOrNull

    fun runCase(function: String, input: JsonObject): JsonElement = when (function) {
        "expand_occurrences" -> MedicationJson.obj(
            "occurrences" to Occurrences.expand(
                MedicationJson.schedule(input["schedule"] as JsonObject), MedicationJson.medication(input["medication"] as JsonObject),
                input.long("window_start_ms")!!, input.long("window_end_ms")!!, input.str("time_zone")!!
            ).map { it.toJson() }
        )
        "resolve_dose_status" -> {
            val r = DoseResolutions.resolve(MedicationJson.occurrence(input["occurrence"] as JsonObject), input.objOrNull("log")?.let(MedicationJson::doseLog), input.long("now_ms")!!)
            MedicationJson.obj("status" to r.status.raw, "deadline_ms" to r.deadlineMs, "is_late" to r.isLate)
        }
        "materialize_missed" -> MedicationJson.obj(
            "ops" to MissedMaterializer.pending(occs(input), logs(input), meds(input).associateBy { it.id }, input.long("now_ms")!!)
                .map { MedicationJson.obj("op" to it.op, "row" to it.log.toJson()) }
        )
        "today_timeline" -> {
            val t = TodayTimelineBuilder.build(meds(input), scheds(input), logs(input), input.long("now_ms")!!, input.str("time_zone")!!)
            MedicationJson.obj(
                "date" to t.date,
                "summary" to MedicationJson.obj(
                    "total" to t.summary.total, "taken" to t.summary.taken, "upcoming" to t.summary.upcoming, "due" to t.summary.due,
                    "snoozed" to t.summary.snoozed, "missed" to t.summary.missed, "skipped" to t.summary.skipped
                ),
                "groups" to t.groups.map { g ->
                    MedicationJson.obj("slot" to g.slot, "items" to g.items.map { it ->
                        MedicationJson.obj(
                            "medication_id" to it.medicationId, "schedule_id" to it.scheduleId, "scheduled_at_ms" to it.scheduledAtMs,
                            "status" to it.status.raw, "is_late" to it.isLate, "log_id" to it.logId, "snoozed_until_ms" to it.snoozedUntilMs,
                            "dose_quantity" to it.doseQuantity, "dose_unit" to it.doseUnit.raw, "kind" to it.kind.raw
                        )
                    })
                },
                "prn" to t.prn.map { MedicationJson.obj("medication_id" to it.medicationId, "today_count" to it.todayCount, "last_taken_ms" to it.lastTakenMs) }
            )
        }
        "adherence" -> {
            val a = Adherence.compute(occs(input), logs(input), input.long("now_ms")!!, input.str("medication_id"))
            MedicationJson.obj("taken" to a.taken, "expected" to a.expected, "percent" to a.percent, "has_data" to a.hasData)
        }
        "plan_reminders" -> {
            val p = ReminderPlanner.plan(meds(input), scheds(input), logs(input), input.long("now_ms")!!, input.long("horizon_ms")!!, input.str("time_zone")!!, input.int("budget"))
            MedicationJson.obj(
                "entries" to p.entries.map {
                    MedicationJson.obj("medication_id" to it.medicationId, "schedule_id" to it.scheduleId, "scheduled_at_ms" to it.scheduledAtMs, "fire_at_ms" to it.fireAtMs, "kind" to it.kind.raw)
                },
                "next_fire_ms" to p.nextFireMs, "truncated" to p.truncated
            )
        }
        "dose_actions" -> when (input.str("op")) {
            "apply_dose_action" -> {
                val r = DoseActions.apply(
                    DoseAction.fromRaw(input.str("action")), MedicationJson.occurrence(input["occurrence"] as JsonObject),
                    input.objOrNull("existing_log")?.let(MedicationJson::doseLog), MedicationJson.medication(input["medication"] as JsonObject),
                    input.long("now_ms")!!, input.int("snooze_minutes"), input.long("taken_at_ms"), input.str("note")
                )
                MedicationJson.obj("ok" to r.ok, "error" to r.error, "row" to r.log?.toJson(), "op" to r.op)
            }
            "log_prn_dose" -> {
                val r = PrnLogging.log(MedicationJson.medication(input["medication"] as JsonObject), input.long("now_ms")!!, input.long("taken_at_ms"), double(input, "dose_quantity"), input.str("note"))
                MedicationJson.obj("ok" to r.ok, "error" to r.error, "row" to r.log?.toJson())
            }
            else -> error("dose_actions op")
        }
        "lifecycle" -> {
            val r = Lifecycle.apply(LifecycleAction.fromRaw(input.str("action")), MedicationJson.medication(input["medication"] as JsonObject), scheds(input), input.long("now_ms")!!, input.objOrNull("new_schedule"))
            val pairs = mutableListOf<Pair<String, Any?>>(
                "ok" to r.ok, "error" to r.error, "medication" to r.medication.toJson(), "schedules" to r.schedules.map { it.toJson() },
                "ops" to r.ops.map { op -> if (op.op == LifecycleOp.SET_STATUS) MedicationJson.obj("op" to op.op, "status" to op.status?.raw) else MedicationJson.obj("op" to op.op, "id" to op.id) }
            )
            if (r.errors != null) pairs += "errors" to r.errors.map { MedicationJson.obj("field" to it.field, "code" to it.code) }
            MedicationJson.obj(*pairs.toTypedArray())
        }
        "auto_complete" -> MedicationJson.obj("medication_ids" to AutoComplete.due(meds(input), input.str("today")!!))
        "frequency_hint" -> FrequencyHint.toJson(FrequencyHint.fromRecordField(input.objOrNull("value_json")))
        "archive" -> when (input.str("op")) {
            "export" -> ArchiveCodec.exportJson(input["snapshot"] as JsonObject, input.long("exported_ms")!!, input.str("time_zone")!!, input.str("platform")!!, input.str("app_version")!!)
            "merge" -> ArchiveCodec.mergeJson(input["snapshot"] as JsonObject, input["archive"] as JsonObject, input.long("now_ms")!!).toJson()
            else -> error("archive op")
        }
        "validate_draft" -> MedicationJson.obj("errors" to DraftValidation.validate(input["draft"] as JsonObject).map { MedicationJson.obj("field" to it.field, "code" to it.code) })
        "coach_tools" -> {
            // The contract is loaded from assets at runtime; the tests inject the shared file so a
            // drift between the two is caught by MedicationsCoachContractTest, not silently here.
            MedicationsCoachTools.contract = MedicationsCoachContract.parse(
                MedicationsTestFiles.shared("coach_tools.json")!!.readText()
            )!!
            MedicationsCoachTools.runCase(input)
        }
        else -> error("unknown function $function")
    }

    @Suppress("unused")
    private fun typedSnapshot(input: JsonObject) = snapshot(input.objOrNull("snapshot"))
}
