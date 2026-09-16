package com.ayuvo.health.medications.logic

import com.ayuvo.health.medications.model.DoseLog
import com.ayuvo.health.medications.model.DoseStatus
import com.ayuvo.health.medications.model.DoseUnit
import com.ayuvo.health.medications.model.FoodRelation
import com.ayuvo.health.medications.model.Medication
import com.ayuvo.health.medications.model.MedicationForm
import com.ayuvo.health.medications.model.MedicationSchedule
import com.ayuvo.health.medications.model.MedicationStatus
import com.ayuvo.health.medications.model.Occurrence
import com.ayuvo.health.medications.model.ScheduleFrequency
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/**
 * The contract's JSON shapes (docs/medications.md): row objects keyed by the SQL column names
 * with `times` / `days` as arrays and the 0/1 integer flags of the database. Used by the vector
 * runner, the archive codec and the store; kotlinx.serialization like the records module.
 */
object MedicationJson {
    val json = Json { ignoreUnknownKeys = true; isLenient = false }

    private val intPattern = Regex("^-?[0-9]+$")

    // -- building --------------------------------------------------------------------------------

    fun obj(vararg pairs: Pair<String, Any?>): JsonObject = JsonObject(pairs.associate { (k, v) -> k to element(v) })

    fun element(value: Any?): JsonElement = when (value) {
        null -> JsonNull
        is JsonElement -> value
        is String -> JsonPrimitive(value)
        is Boolean -> JsonPrimitive(value)
        is Int -> JsonPrimitive(value)
        is Long -> JsonPrimitive(value)
        is Double -> num(value)
        is Float -> num(value.toDouble())
        is Number -> JsonPrimitive(value)
        is List<*> -> JsonArray(value.map(::element))
        is Map<*, *> -> JsonObject(value.entries.associate { (k, v) -> k.toString() to element(v) })
        else -> JsonPrimitive(value.toString())
    }

    /** Integral doubles are written as integers (`1` not `1.0`), as the reference's `_clean_number` does. */
    fun num(value: Double): JsonElement =
        if (value.isFinite() && value == Math.floor(value) && Math.abs(value) < 1e15) JsonPrimitive(value.toLong()) else JsonPrimitive(value)

    // -- reading ----------------------------------------------------------------------------------

    private fun prim(e: JsonElement?): JsonPrimitive? = (e as? JsonPrimitive)?.takeIf { it !is JsonNull }

    fun JsonObject.str(key: String): String? = prim(this[key])?.takeIf { it.isString }?.content
    fun JsonObject.long(key: String): Long? = prim(this[key])?.takeIf { !it.isString }?.longOrNull
    fun JsonObject.int(key: String): Int? = long(key)?.toInt()
    fun JsonObject.double(key: String): Double? = prim(this[key])?.takeIf { !it.isString && it.booleanOrNull == null }?.doubleOrNull
    fun JsonObject.arr(key: String): JsonArray? = this[key] as? JsonArray
    fun JsonObject.objOrNull(key: String): JsonObject? = this[key] as? JsonObject
    fun JsonObject.has(key: String): Boolean = containsKey(key)

    /** Python `_truthy`: 0/1 flags, booleans, non-empty strings. */
    fun JsonObject.truthy(key: String): Boolean = truthy(this[key])

    fun truthy(e: JsonElement?): Boolean {
        val p = prim(e) ?: return false
        p.booleanOrNull?.let { return it }
        if (p.isString) return p.content.isNotEmpty()
        return (p.doubleOrNull ?: 0.0) != 0.0
    }

    /** Python `_is_int`: a JSON integer literal (not a bool, not `1.0`). */
    fun isInt(e: JsonElement?): Boolean {
        val p = prim(e) ?: return false
        return !p.isString && p.booleanOrNull == null && intPattern.matches(p.content)
    }

    /** Python `_is_number`: an int or float, never a bool. */
    fun isNumber(e: JsonElement?): Boolean {
        val p = prim(e) ?: return false
        return !p.isString && p.booleanOrNull == null && p.doubleOrNull != null
    }

    fun isString(e: JsonElement?): Boolean = prim(e)?.isString == true

    fun strings(e: JsonElement?): List<String> = (e as? JsonArray).orEmpty().mapNotNull { prim(it)?.takeIf { p -> p.isString }?.content }
    fun ints(e: JsonElement?): List<Int> = (e as? JsonArray).orEmpty().mapNotNull { prim(it)?.takeIf { p -> !p.isString }?.longOrNull?.toInt() }

    // -- row codecs (column order = the reference's *_COLUMNS lists) -------------------------------

    fun medication(o: JsonObject): Medication = Medication(
        id = o.str("id") ?: "",
        name = o.str("name") ?: "",
        genericName = o.str("generic_name"),
        brandName = o.str("brand_name"),
        strength = o.str("strength"),
        form = MedicationForm.fromRaw(o.str("form")),
        doseQuantity = o.double("dose_quantity") ?: 1.0,
        doseUnit = DoseUnit.fromRaw(o.str("dose_unit")),
        foodRelation = FoodRelation.fromRaw(o.str("food_relation")),
        instructions = o.str("instructions"),
        startDate = o.str("start_date") ?: "",
        endDate = o.str("end_date"),
        status = MedicationStatus.fromRaw(o.str("status")),
        isPrn = o.truthy("is_prn"),
        photoPath = o.str("photo_path"),
        relatedRecordId = o.str("related_record_id"),
        createdMs = o.long("created_ms") ?: 0L,
        updatedMs = o.long("updated_ms") ?: 0L
    )

    fun Medication.toJson(): JsonObject = obj(
        "id" to id, "name" to name, "generic_name" to genericName, "brand_name" to brandName, "strength" to strength,
        "form" to form.raw, "dose_quantity" to doseQuantity, "dose_unit" to doseUnit.raw, "food_relation" to foodRelation.raw,
        "instructions" to instructions, "start_date" to startDate, "end_date" to endDate, "status" to status.raw,
        "is_prn" to if (isPrn) 1 else 0, "photo_path" to photoPath, "related_record_id" to relatedRecordId,
        "created_ms" to createdMs, "updated_ms" to updatedMs
    )

    fun schedule(o: JsonObject): MedicationSchedule = MedicationSchedule(
        id = o.str("id") ?: "",
        medicationId = o.str("medication_id") ?: "",
        frequency = ScheduleFrequency.fromRaw(o.str("frequency_kind")),
        times = strings(o["times"]),
        days = ints(o["days"]),
        intervalHours = o.int("interval_hours"),
        anchorTime = o.str("anchor_time"),
        reminderEnabled = o.truthy("reminder_enabled"),
        activeFromMs = o.long("active_from_ms") ?: 0L,
        activeUntilMs = o.long("active_until_ms"),
        createdMs = o.long("created_ms") ?: 0L,
        updatedMs = o.long("updated_ms") ?: 0L
    )

    fun MedicationSchedule.toJson(): JsonObject = obj(
        "id" to id, "medication_id" to medicationId, "frequency_kind" to frequency.raw, "times" to times, "days" to days,
        "interval_hours" to intervalHours, "anchor_time" to anchorTime, "reminder_enabled" to if (reminderEnabled) 1 else 0,
        "active_from_ms" to activeFromMs, "active_until_ms" to activeUntilMs, "created_ms" to createdMs, "updated_ms" to updatedMs
    )

    fun doseLog(o: JsonObject): DoseLog = DoseLog(
        id = o.str("id") ?: "",
        medicationId = o.str("medication_id") ?: "",
        scheduleId = o.str("schedule_id"),
        scheduledAtMs = o.long("scheduled_at_ms") ?: 0L,
        status = DoseStatus.fromRaw(o.str("status")),
        takenAtMs = o.long("taken_at_ms"),
        snoozedUntilMs = o.long("snoozed_until_ms"),
        doseQuantity = o.double("dose_quantity") ?: 0.0,
        doseUnit = DoseUnit.fromRaw(o.str("dose_unit")),
        note = o.str("note"),
        createdMs = o.long("created_ms") ?: 0L,
        updatedMs = o.long("updated_ms") ?: 0L
    )

    fun DoseLog.toJson(): JsonObject = obj(
        "id" to id, "medication_id" to medicationId, "schedule_id" to scheduleId, "scheduled_at_ms" to scheduledAtMs,
        "status" to status.raw, "taken_at_ms" to takenAtMs, "snoozed_until_ms" to snoozedUntilMs,
        "dose_quantity" to doseQuantity, "dose_unit" to doseUnit.raw, "note" to note, "created_ms" to createdMs, "updated_ms" to updatedMs
    )

    fun occurrence(o: JsonObject): Occurrence = Occurrence(
        medicationId = o.str("medication_id") ?: "",
        scheduleId = o.str("schedule_id") ?: "",
        scheduledAtMs = o.long("scheduled_at_ms") ?: 0L,
        localDate = o.str("local_date") ?: "",
        slot = o.str("slot") ?: ""
    )

    fun Occurrence.toJson(): JsonObject = obj(
        "medication_id" to medicationId, "schedule_id" to scheduleId, "scheduled_at_ms" to scheduledAtMs,
        "local_date" to localDate, "slot" to slot
    )
}
