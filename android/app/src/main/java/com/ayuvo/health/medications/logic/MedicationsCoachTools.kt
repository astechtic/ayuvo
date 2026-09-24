package com.ayuvo.health.medications.logic

import com.ayuvo.health.medications.logic.MedicationJson.arr
import com.ayuvo.health.medications.logic.MedicationJson.double
import com.ayuvo.health.medications.logic.MedicationJson.obj
import com.ayuvo.health.medications.logic.MedicationJson.objOrNull
import com.ayuvo.health.medications.logic.MedicationJson.str
import com.ayuvo.health.medications.model.DoseLog
import com.ayuvo.health.medications.model.DoseStatus
import com.ayuvo.health.medications.model.Medication
import com.ayuvo.health.medications.model.MedicationSchedule
import com.ayuvo.health.medications.model.MedicationStatus
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull

/**
 * Kotlin port of the Coach section of `scripts/medications_reference.py` (docs/medications.md §20,
 * docs/coach.md §3). Tool names, descriptions and input schemas live in
 * `shared/medications/coach_tools.json`, bundled as `assets/medications/coach_tools.json`; a parity
 * test byte-compares the two. Payload shapes are driven by
 * `shared/medications/test-vectors/coach_tools_payloads.json`.
 */
object MedicationsCoachTools {
    const val DOSE_LIMIT = 200
    private val RE_PLACEHOLDER = Regex("\\{([a-z_]+)\\}")

    /** Set once at startup from `assets/medications/coach_tools.json`; the tests inject the shared file. */
    @Volatile
    var contract: MedicationsCoachContract = MedicationsCoachContract.empty

    // -- Errors and prompt strings ----------------------------------------------------------------

    /** `{"error": <errors.key with {placeholders} filled in ONE left-to-right pass>}`. */
    fun error(key: String, values: Map<String, String> = emptyMap()): JsonObject {
        val template = contract.errors[key] ?: key
        return obj("error" to fill(template, values))
    }

    private fun fill(template: String, values: Map<String, String>): String =
        RE_PLACEHOLDER.replace(template) { m -> values[m.groupValues[1]] ?: m.value }

    // -- Argument helpers -------------------------------------------------------------------------

    private fun date(value: JsonElement?, out: MutableList<JsonObject>): String? {
        val text = (value as? JsonPrimitive)?.takeIf { it !is JsonNull && it.isString }?.content
        if (text != null && MedicationLocalTime.parseDate(text) != null) return text
        val shown = text ?: value?.toString() ?: "null"
        out += error("bad_date", mapOf("value" to shown))
        return null
    }

    private fun range(args: JsonObject?, out: MutableList<JsonObject>): Pair<String, String>? {
        val start = date(args?.get("from"), out) ?: return null
        val end = date(args?.get("to"), out) ?: return null
        if (start > end) {
            out += error("date_order")
            return null
        }
        return start to end
    }

    /** Whole numbers clamp to 1..cap; anything else (strings, fractions, booleans) -> cap. */
    private fun limit(value: JsonElement?, cap: Int): Int {
        val p = value as? JsonPrimitive ?: return cap
        if (p is JsonNull || p.isString || p.booleanOrNull != null) return cap
        val d = p.doubleOrNull ?: return cap
        if (d != Math.floor(d) || !d.isFinite()) return cap
        return maxOf(1, minOf(cap, d.toInt()))
    }

    private fun cleanNumber(value: Double?): JsonElement =
        if (value == null) JsonNull else MedicationJson.num(value)

    // -- get_medications --------------------------------------------------------------------------

    /** The open schedule row of a medication (`activeUntilMs == null`), else the newest closed one. */
    fun activeSchedule(schedules: List<MedicationSchedule>, medicationId: String): MedicationSchedule? {
        val rows = schedules.filter { it.medicationId == medicationId }
        val open = rows.filter { it.activeUntilMs == null }
        if (open.isNotEmpty()) return open.sortedWith(compareBy({ it.activeFromMs }, { it.id })).last()
        if (rows.isEmpty()) return null
        return rows.sortedWith(compareBy({ it.activeUntilMs ?: 0L }, { it.id })).last()
    }

    /** Structured, never a localized sentence: the model phrases it. */
    private fun schedulePayload(schedule: MedicationSchedule?): JsonElement =
        if (schedule == null) JsonNull else obj(
            "frequency" to schedule.frequency.raw,
            "times" to schedule.times,
            "days" to schedule.days,
            "interval_hours" to schedule.intervalHours,
            "anchor_time" to schedule.anchorTime,
            "reminders_on" to schedule.reminderEnabled
        )

    private fun medicationPayload(m: Medication, schedule: MedicationSchedule?): JsonObject = obj(
        "medication_id" to m.id, "name" to m.name, "generic_name" to m.genericName,
        "brand_name" to m.brandName, "strength" to m.strength, "form" to m.form.raw,
        "dose_quantity" to cleanNumber(m.doseQuantity), "dose_unit" to m.doseUnit.raw,
        "food_relation" to m.foodRelation.raw, "instructions" to m.instructions,
        "status" to m.status.raw, "is_prn" to m.isPrn, "start_date" to m.startDate,
        "end_date" to m.endDate,
        "schedule" to (if (m.isPrn) JsonNull else schedulePayload(schedule))
    )

    /** `get_medications`. Active medicines unless `include_inactive` is exactly true. */
    fun medicationsPayload(snapshot: JsonObject?, args: JsonObject?): JsonObject {
        val includeInactive = (args?.get("include_inactive") as? JsonPrimitive)?.booleanOrNull == true
        val meds = medications(snapshot)
            .filter { includeInactive || it.status == MedicationStatus.ACTIVE }
            .sortedWith(compareBy({ foldName(it.name) }, { it.id }))
        val schedules = schedules(snapshot)
        val rows = meds.map { medicationPayload(it, activeSchedule(schedules, it.id)) }
        return obj("count" to rows.size, "medications" to rows)
    }

    // -- get_dose_history -------------------------------------------------------------------------

    private data class DoseRow(
        val medicationId: String, val name: String?, val scheduledAtMs: Long,
        val status: String, val takenAtMs: Long?, val isPrn: Boolean,
        /** The stored row as written, not the typed model: a typed [DoseLog] cannot represent a
         *  missing `dose_quantity` or `dose_unit`, and the reference emits null for those. */
        val raw: JsonObject?
    )

    private fun doseRowJson(row: DoseRow, timeZone: String): JsonObject = obj(
        "medication_id" to row.medicationId, "name" to row.name,
        "date" to MedicationLocalTime.localDateOf(row.scheduledAtMs, timeZone),
        "scheduled_at" to MedicationLocalTime.localHhmmOf(row.scheduledAtMs, timeZone),
        "scheduled_at_ms" to row.scheduledAtMs,
        "taken_at" to row.takenAtMs?.let { MedicationLocalTime.localHhmmOf(it, timeZone) },
        "status" to row.status, "is_prn" to row.isPrn,
        "dose_quantity" to (row.raw?.let { cleanNumber(it.double("dose_quantity")) } ?: JsonNull),
        "dose_unit" to row.raw?.str("dose_unit"), "note" to row.raw?.str("note")
    )

    /**
     * Every dose of the window: scheduled occurrences with their resolved status, stored logs of
     * scheduled doses that match no occurrence (the schedule was edited), and PRN logs.
     */
    private fun doseRows(meds: List<Medication>, schedules: List<MedicationSchedule>,
                         allLogs: List<Pair<DoseLog, JsonObject>>, medicationId: String?,
                         startMs: Long, endMs: Long, nowMs: Long, timeZone: String): List<DoseRow> {
        val byId = meds.associateBy { it.id }
        val pairs = allLogs.filter { (log, _) ->
            log.scheduledAtMs in startMs until endMs && (medicationId == null || log.medicationId == medicationId)
        }
        val logs = pairs.map { it.first }
        val rawOf = java.util.IdentityHashMap<DoseLog, JsonObject>()
        for ((log, raw) in pairs) rawOf[log] = raw
        val index = DoseResolutions.index(logs)
        val matched = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<DoseLog, Boolean>())
        val rows = mutableListOf<DoseRow>()
        for (occ in Occurrences.expandAll(meds, schedules, startMs, endMs, timeZone)) {
            if (medicationId != null && occ.medicationId != medicationId) continue
            val log = index[occ.scheduleId to occ.scheduledAtMs]
            if (log != null) matched += log
            val resolved = DoseResolutions.resolve(occ, log, nowMs)
            val status = if (resolved.status == DoseStatus.TAKEN && resolved.isLate) "taken_late"
                         else resolved.status.raw
            rows += DoseRow(occ.medicationId, byId[occ.medicationId]?.name, occ.scheduledAtMs,
                            status, log?.takenAtMs, false, log?.let { rawOf[it] })
        }
        for (log in logs) {
            if (log in matched) continue
            val isPrn = log.scheduleId == null
            rows += DoseRow(log.medicationId, byId[log.medicationId]?.name, log.scheduledAtMs,
                            log.status.raw, log.takenAtMs, isPrn, rawOf[log])
        }
        return rows.sortedWith(
            compareByDescending<DoseRow> { it.scheduledAtMs }
                .thenBy { foldName(it.name) }
                .thenBy { it.medicationId }
        )
    }

    /** `get_dose_history`. Newest first, `limit` omitted -> 200. */
    fun doseHistoryPayload(snapshot: JsonObject?, args: JsonObject?, nowMs: Long, timeZone: String): JsonObject {
        val errors = mutableListOf<JsonObject>()
        val (start, end) = range(args, errors) ?: return errors.first()
        val meds = medications(snapshot)
        val requested = args?.str("medication_id")
        if (requested != null && meds.none { it.id == requested }) {
            return error("unknown_medication", mapOf("id" to requested))
        }
        val startMs = MedicationLocalTime.dayWindow(start, timeZone).first
        val endMs = MedicationLocalTime.dayWindow(end, timeZone).second
        val rows = doseRows(meds, schedules(snapshot), doseLogs(snapshot), requested, startMs, endMs, nowMs, timeZone)
        val kept = rows.take(limit(args?.get("limit"), DOSE_LIMIT))
        return obj("from" to start, "to" to end, "count" to kept.size,
                   "doses" to kept.map { doseRowJson(it, timeZone) })
    }

    // -- get_medication_adherence -----------------------------------------------------------------

    private fun counts(rows: List<DoseRow>): Map<String, Int> {
        val out = mutableMapOf("taken" to 0, "taken_late" to 0, "skipped" to 0, "missed" to 0, "open" to 0)
        for (row in rows) {
            when (row.status) {
                "taken_late" -> { out["taken"] = out["taken"]!! + 1; out["taken_late"] = out["taken_late"]!! + 1 }
                "taken" -> out["taken"] = out["taken"]!! + 1
                "skipped" -> out["skipped"] = out["skipped"]!! + 1
                "missed" -> out["missed"] = out["missed"]!! + 1
                else -> out["open"] = out["open"]!! + 1
            }
        }
        return out
    }

    /** The scheduled slot with the most missed or skipped doses; ties go to the earlier time. */
    private fun mostMissedTime(rows: List<DoseRow>, timeZone: String): String? {
        val tally = mutableMapOf<String, Int>()
        for (row in rows) {
            if ((row.status != "missed" && row.status != "skipped") || row.isPrn) continue
            val slot = MedicationLocalTime.localHhmmOf(row.scheduledAtMs, timeZone)
            tally[slot] = (tally[slot] ?: 0) + 1
        }
        if (tally.isEmpty()) return null
        return tally.entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .first().key
    }

    /**
     * `get_medication_adherence`. `percent` comes from §9 [Adherence], so the tool and the app's own
     * adherence screen can never disagree.
     */
    fun adherencePayload(snapshot: JsonObject?, args: JsonObject?, nowMs: Long, timeZone: String): JsonObject {
        val errors = mutableListOf<JsonObject>()
        val (start, end) = range(args, errors) ?: return errors.first()
        val meds = medications(snapshot)
        val requested = args?.str("medication_id")
        if (requested != null && meds.none { it.id == requested }) {
            return error("unknown_medication", mapOf("id" to requested))
        }
        val startMs = MedicationLocalTime.dayWindow(start, timeZone).first
        val endMs = MedicationLocalTime.dayWindow(end, timeZone).second
        val schedules = schedules(snapshot)
        val allLogs = doseLogs(snapshot)
        val occurrences = Occurrences.expandAll(meds, schedules, startMs, endMs, timeZone)
        val windowLogs = allLogs.map { it.first }.filter { it.scheduledAtMs in startMs until endMs }
        val rows = doseRows(meds, schedules, allLogs, requested, startMs, endMs, nowMs, timeZone)

        fun block(mid: String?): MutableMap<String, Any?> {
            val scoped = if (mid == null) rows else rows.filter { it.medicationId == mid }
            val c = counts(scoped)
            val summary = Adherence.compute(occurrences, windowLogs, nowMs, mid)
            return mutableMapOf(
                "scheduled" to summary.expected, "taken" to summary.taken,
                "taken_late" to c["taken_late"], "skipped" to c["skipped"], "missed" to c["missed"],
                "still_open" to c["open"], "percent" to summary.percent, "has_data" to summary.hasData,
                "most_missed_time" to mostMissedTime(scoped, timeZone)
            )
        }

        val per = mutableListOf<JsonObject>()
        for (med in meds.filter { requested == null || it.id == requested }
            .sortedWith(compareBy({ foldName(it.name) }, { it.id }))) {
            val entry = block(med.id)
            if (entry["scheduled"] == 0 && entry["still_open"] == 0) continue
            entry["medication_id"] = med.id
            entry["name"] = med.name
            per += JsonObject(entry.mapValues { MedicationJson.element(it.value) })
        }
        return obj("from" to start, "to" to end,
                   "overall" to JsonObject(block(requested).mapValues { MedicationJson.element(it.value) }),
                   "medications" to per)
    }

    // -- Prompt lines -----------------------------------------------------------------------------

    /** The `## Data available` lines for medications (docs/coach.md §3). */
    fun promptLines(snapshot: JsonObject?, accessEnabled: Boolean): JsonObject {
        val meds = medications(snapshot)
        val active = meds.count { it.status == MedicationStatus.ACTIVE }
        if (!accessEnabled || meds.isEmpty()) {
            return obj("advertise_tools" to false, "available_line" to null,
                       "not_available_line" to contract.prompt["not_available_line"],
                       "guardrails" to null)
        }
        val line = fill(contract.prompt["available_line"] ?: "",
                        mapOf("n" to meds.size.toString(), "active" to active.toString()))
        return obj("advertise_tools" to true, "available_line" to line,
                   "not_available_line" to null, "guardrails" to contract.prompt["guardrails"])
    }

    // -- Snapshot helpers -------------------------------------------------------------------------

    private fun medications(snapshot: JsonObject?): List<Medication> =
        snapshot?.arr("medications").orEmpty().filterIsInstance<JsonObject>().map(MedicationJson::medication)

    private fun schedules(snapshot: JsonObject?): List<MedicationSchedule> =
        snapshot?.arr("schedules").orEmpty().filterIsInstance<JsonObject>().map(MedicationJson::schedule)

    private fun doseLogs(snapshot: JsonObject?): List<Pair<DoseLog, JsonObject>> =
        snapshot?.arr("dose_logs").orEmpty().filterIsInstance<JsonObject>()
            .map { MedicationJson.doseLog(it) to it }

    /** Sort key for medication names: lowercase, single spaces, trimmed (reference `fold_name`). */
    private fun foldName(s: String?): String = (s ?: "").lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")

    // -- Vector dispatch --------------------------------------------------------------------------

    fun runCase(input: JsonObject): JsonElement = when (val tool = input.str("tool")) {
        "get_medications" -> medicationsPayload(input.objOrNull("snapshot"), input.objOrNull("args"))
        "get_dose_history" -> doseHistoryPayload(
            input.objOrNull("snapshot"), input.objOrNull("args"),
            (input["now_ms"] as? JsonPrimitive)?.content?.toLongOrNull() ?: 0L,
            input.str("time_zone") ?: "UTC"
        )
        "get_medication_adherence" -> adherencePayload(
            input.objOrNull("snapshot"), input.objOrNull("args"),
            (input["now_ms"] as? JsonPrimitive)?.content?.toLongOrNull() ?: 0L,
            input.str("time_zone") ?: "UTC"
        )
        "prompt_lines" -> promptLines(
            input.objOrNull("snapshot"),
            (input["access_enabled"] as? JsonPrimitive)?.booleanOrNull == true
        )
        else -> error("unknown tool $tool")
    }
}

/** `shared/medications/coach_tools.json` (bundled as `assets/medications/coach_tools.json`). */
data class MedicationsCoachContract(
    val tools: List<Tool>,
    val prompt: Map<String, String>,
    val errors: Map<String, String>,
    /** `prompt.mentions_words` — shared so "did the user ask about medicines?" cannot drift. */
    val mentionsWords: List<String> = emptyList()
) {
    data class Tool(val name: String, val description: String, val schemaText: String, val schema: JsonObject)

    val names: List<String> get() = tools.map { it.name }

    fun tool(name: String): Tool? = tools.firstOrNull { it.name == name }

    companion object {
        const val FORMAT = "ayuvo-medications-coach-tools"
        const val ASSET_PATH = "medications/coach_tools.json"
        val empty = MedicationsCoachContract(emptyList(), emptyMap(), emptyMap(), emptyList())

        fun parse(text: String): MedicationsCoachContract? {
            val root = MedicationJson.json.parseToJsonElement(text) as? JsonObject ?: return null
            if (root.str("format") != FORMAT) return null
            val rawSchemas = rawSchemaTexts(text)
            val tools = root.arr("tools").orEmpty().filterIsInstance<JsonObject>().mapIndexedNotNull { i, entry ->
                val name = entry.str("name") ?: return@mapIndexedNotNull null
                val description = entry.str("description") ?: return@mapIndexedNotNull null
                val schema = entry.objOrNull("input_schema") ?: return@mapIndexedNotNull null
                Tool(name, description, rawSchemas.getOrElse(i) { schema.toString() }, schema)
            }
            val prompt = root.objOrNull("prompt").orEmpty().mapNotNull { (k, v) ->
                (v as? JsonPrimitive)?.takeIf { it.isString }?.let { k to it.content }
            }.toMap()
            val errors = root.objOrNull("errors").orEmpty().mapNotNull { (k, v) ->
                (v as? JsonPrimitive)?.takeIf { it.isString }?.let { k to it.content }
            }.toMap()
            val mentions = MedicationJson.strings(root.objOrNull("prompt")?.get("mentions_words"))
            return MedicationsCoachContract(tools, prompt, errors, mentions)
        }

        /**
         * The exact `input_schema` text as written in the file, so the platforms advertise the same
         * bytes (docs/health-records.md §26 applies the same rule to the records tools).
         */
        fun rawSchemaTexts(text: String): List<String> {
            val marker = "\"input_schema\":"
            val out = mutableListOf<String>()
            var index = text.indexOf(marker)
            while (index >= 0) {
                var cursor = index + marker.length
                while (cursor < text.length && text[cursor] == ' ') cursor++
                if (cursor >= text.length || text[cursor] != '{') break
                val start = cursor
                var depth = 0
                var inString = false
                var escaped = false
                while (cursor < text.length) {
                    val c = text[cursor]
                    when {
                        escaped -> escaped = false
                        c == '\\' && inString -> escaped = true
                        c == '"' -> inString = !inString
                        !inString && c == '{' -> depth++
                        !inString && c == '}' -> {
                            depth--
                            if (depth == 0) { cursor++; break }
                        }
                    }
                    cursor++
                }
                out += text.substring(start, cursor)
                index = text.indexOf(marker, cursor)
            }
            return out
        }
    }
}
