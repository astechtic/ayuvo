package com.ayuvo.health.cycle.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Section `cycle` of Export All Data (docs/cycle-tracking.md §7): `cycle/periods.ndjson`, `cycle/day_logs.ndjson`
 * and `cycle/settings.json`. Rows use the column names; list columns are embedded arrays (`pain_locations`,
 * `symptoms`, `moods`) and `settings_json` is the object `settings`. `platform_ids_json` and `sync_state` are
 * device-specific and never exported. Deleted rows travel as tombstones so a delete propagates.
 *
 * Pure JVM, so the codec and the merge rule are unit-tested; [CycleRepository.importArchive] applies it.
 */
object CycleArchive {
    const val SECTION = "cycle"
    const val FORMAT = "ayuvo-cycle"
    const val FORMAT_VERSION = 1
    const val DIR = "cycle"
    const val PERIODS = "$DIR/periods.ndjson"
    const val DAY_LOGS = "$DIR/day_logs.ndjson"
    const val SETTINGS = "$DIR/settings.json"
    val ENTRIES = listOf(PERIODS, DAY_LOGS, SETTINGS)

    const val COUNT_PERIODS = "periods"
    const val COUNT_DAY_LOGS = "day_logs"

    private val DAY = Regex("^\\d{4}-\\d{2}-\\d{2}$")

    class Bundle(
        val periods: List<CyclePeriod>,
        val dayLogs: List<CycleDayLog>,
        val settings: CycleSettingsRow?,
        /** Lines that could not be read (bad JSON, missing or malformed fields). */
        val skippedLines: Int = 0
    ) {
        val isEmpty: Boolean get() = periods.isEmpty() && dayLogs.isEmpty() && settings == null
    }

    /** What an import changed: rows inserted or replaced by newer ones, and whether the settings row was applied. */
    data class ImportResult(val periods: Int, val dayLogs: Int, val settingsApplied: Boolean, val skipped: Int) {
        val total: Long get() = (periods + dayLogs + if (settingsApplied) 1 else 0).toLong()
    }

    private val json = Json { ignoreUnknownKeys = true; isLenient = false }

    private fun line(fields: Map<String, JsonElement>): String = JsonObject(fields.toSortedMap()).toString()

    private fun s(v: String?): JsonElement = v?.let(::JsonPrimitive) ?: JsonNull

    private fun list(values: List<String>): JsonElement = JsonArray(values.map(::JsonPrimitive))

    fun encodePeriod(p: CyclePeriod): String = line(
        mapOf(
            "id" to JsonPrimitive(p.id),
            "start_day" to JsonPrimitive(p.startDay),
            "end_day" to s(p.endDay),
            "created_ms" to JsonPrimitive(p.createdMs),
            "updated_ms" to JsonPrimitive(p.updatedMs),
            "deleted" to JsonPrimitive(if (p.deleted) 1 else 0)
        )
    )

    fun encodeDayLog(l: CycleDayLog): String = line(
        mapOf(
            "day" to JsonPrimitive(l.day),
            "flow" to s(l.flow),
            "pain" to (l.pain?.let(::JsonPrimitive) ?: JsonNull),
            "pain_locations" to list(l.painLocations),
            "symptoms" to list(l.symptoms),
            "moods" to list(l.moods),
            "note" to s(if (l.deleted) null else l.note),
            "updated_ms" to JsonPrimitive(l.updatedMs),
            "deleted" to JsonPrimitive(if (l.deleted) 1 else 0)
        )
    )

    fun encodeSettings(r: CycleSettingsRow): String = line(
        mapOf(
            "setup_done" to JsonPrimitive(if (r.setupDone) 1 else 0),
            "cycle_length" to (r.cycleLength?.let(::JsonPrimitive) ?: JsonNull),
            "period_length" to (r.periodLength?.let(::JsonPrimitive) ?: JsonNull),
            "luteal_length" to (r.lutealLength?.let(::JsonPrimitive) ?: JsonNull),
            "settings" to r.preferences.toJson(),
            "updated_ms" to JsonPrimitive(r.updatedMs)
        )
    ) + "\n"

    fun ndjson(lines: List<String>): String = if (lines.isEmpty()) "" else lines.joinToString("\n", postfix = "\n")

    /** Entry path -> (text, manifest count key or null, row count), in [ENTRIES] order; settings only when present. */
    fun encode(bundle: Bundle): List<Pair<String, Triple<String, String?, Int>>> {
        val out = mutableListOf<Pair<String, Triple<String, String?, Int>>>(
            PERIODS to Triple(ndjson(bundle.periods.map(::encodePeriod)), COUNT_PERIODS, bundle.periods.size),
            DAY_LOGS to Triple(ndjson(bundle.dayLogs.map(::encodeDayLog)), COUNT_DAY_LOGS, bundle.dayLogs.size)
        )
        bundle.settings?.let { out += SETTINGS to Triple(encodeSettings(it), null, 1) }
        return out
    }

    // -- Decoding -----------------------------------------------------------------------------------

    private fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.takeIf { it !is JsonNull && it.isString }?.content
    private fun JsonObject.long(k: String): Long? = (this[k] as? JsonPrimitive)?.takeIf { it !is JsonNull && !it.isString }?.longOrNull
    private fun JsonObject.int(k: String): Int? = (this[k] as? JsonPrimitive)?.takeIf { it !is JsonNull && !it.isString }?.intOrNull
    private fun JsonObject.strs(k: String): List<String> =
        (this[k] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content } ?: emptyList()
    private fun JsonObject.flag(k: String): Boolean = when (val p = this[k] as? JsonPrimitive) {
        null, is JsonNull -> false
        else -> p.content == "1" || p.content == "true"
    }

    private fun objects(text: String?): Pair<List<JsonObject>, Int> {
        if (text.isNullOrBlank()) return emptyList<JsonObject>() to 0
        val out = mutableListOf<JsonObject>()
        var bad = 0
        for (raw in text.split('\n')) {
            val l = raw.trim()
            if (l.isEmpty()) continue
            val o = runCatching { json.parseToJsonElement(l) as? JsonObject }.getOrNull()
            if (o == null) bad++ else out += o
        }
        return out to bad
    }

    fun decodePeriod(o: JsonObject): CyclePeriod? {
        val id = o.str("id")?.takeIf { it.isNotBlank() } ?: return null
        val start = o.str("start_day")?.takeIf { DAY.matches(it) } ?: return null
        val end = o.str("end_day")
        if (end != null && !DAY.matches(end)) return null
        val updated = o.long("updated_ms") ?: return null
        return CyclePeriod(
            id = id, startDay = start, endDay = end, createdMs = o.long("created_ms") ?: updated,
            updatedMs = updated, deleted = o.flag("deleted")
        )
    }

    fun decodeDayLog(o: JsonObject): CycleDayLog? {
        val day = o.str("day")?.takeIf { DAY.matches(it) } ?: return null
        val updated = o.long("updated_ms") ?: return null
        val deleted = o.flag("deleted")
        return CycleDayLog(
            day = day, flow = o.str("flow"), pain = o.int("pain")?.coerceIn(0, 10),
            painLocations = o.strs("pain_locations"), symptoms = o.strs("symptoms"), moods = o.strs("moods"),
            note = if (deleted) null else o.str("note"), updatedMs = updated, deleted = deleted
        )
    }

    fun decodeSettings(text: String?): CycleSettingsRow? {
        if (text.isNullOrBlank()) return null
        val o = runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return null
        return CycleSettingsRow(
            setupDone = o.flag("setup_done"),
            cycleLength = o.int("cycle_length"),
            periodLength = o.int("period_length"),
            lutealLength = o.int("luteal_length"),
            preferences = (o["settings"] as? JsonObject)?.let(CyclePreferences::fromJson) ?: CyclePreferences(),
            updatedMs = o.long("updated_ms") ?: 0
        )
    }

    /** Reads the three entries (any may be missing). */
    fun read(periodsText: String?, dayLogsText: String?, settingsText: String?): Bundle {
        val (pObjs, pBad) = objects(periodsText)
        val (lObjs, lBad) = objects(dayLogsText)
        var skipped = pBad + lBad
        val periods = pObjs.mapNotNull { decodePeriod(it) ?: run { skipped++; null } }
        val logs = lObjs.mapNotNull { decodeDayLog(it) ?: run { skipped++; null } }
        return Bundle(periods, logs, decodeSettings(settingsText), skipped)
    }

    /** §7 merge rule: insert when missing, replace when the incoming row is newer, otherwise keep the local row. */
    fun shouldApply(localUpdatedMs: Long?, incomingUpdatedMs: Long): Boolean =
        localUpdatedMs == null || incomingUpdatedMs > localUpdatedMs
}
