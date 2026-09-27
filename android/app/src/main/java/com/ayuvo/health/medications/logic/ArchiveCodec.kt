package com.ayuvo.health.medications.logic

import com.ayuvo.health.medications.logic.MedicationJson.long
import com.ayuvo.health.medications.logic.MedicationJson.str
import com.ayuvo.health.medications.logic.MedicationJson.toJson
import com.ayuvo.health.medications.model.DoseStatus
import com.ayuvo.health.medications.model.DoseUnit
import com.ayuvo.health.medications.model.FoodRelation
import com.ayuvo.health.medications.model.MedicationForm
import com.ayuvo.health.medications.model.MedicationStatus
import com.ayuvo.health.medications.model.MedicationsSnapshot
import com.ayuvo.health.nutrients.NutrientReference
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

/**
 * The `ayuvo-medications` archive v1 (docs/medications.md §14, supplement `nutrients` §21): `export_archive` and
 * `merge_archive`, working on raw JSON rows so invalid archive rows are rejected exactly as in
 * the reference. Nothing is ever deleted by a merge.
 */
object ArchiveCodec {

    val MEDICATION_COLUMNS = listOf(
        "id", "name", "generic_name", "brand_name", "strength", "form", "dose_quantity", "dose_unit", "food_relation",
        "instructions", "start_date", "end_date", "status", "is_prn", "photo_path", "related_record_id", "created_ms", "updated_ms"
    )
    val SCHEDULE_COLUMNS = listOf(
        "id", "medication_id", "frequency_kind", "times", "days", "interval_hours", "anchor_time", "reminder_enabled",
        "active_from_ms", "active_until_ms", "created_ms", "updated_ms"
    )
    val DOSE_LOG_COLUMNS = listOf(
        "id", "medication_id", "schedule_id", "scheduled_at_ms", "status", "taken_at_ms", "snoozed_until_ms",
        "dose_quantity", "dose_unit", "note", "created_ms", "updated_ms"
    )

    const val TABLE_MEDICATIONS = "medications"
    const val TABLE_SCHEDULES = "medication_schedules"
    const val TABLE_DOSE_LOGS = "dose_logs"

    const val TABLE_NUTRIENTS = "medication_nutrients"

    /**
     * One merge decision: `insert` / `update` carry [row]; `skip` carries [reason] (`older`, `invalid`,
     * `orphan`, `occurrence_conflict`; for `medication_nutrients` `unknown_nutrient`, `invalid_amount`,
     * `duplicate_nutrient` with the medication [id] and the [nutrientKey]).
     */
    data class MergeOp(
        val table: String,
        val op: String,
        val id: String?,
        val row: JsonObject? = null,
        val reason: String? = null,
        val nutrientKey: String? = null
    ) {
        fun toJson(): JsonObject = if (op == "skip" && table == TABLE_NUTRIENTS) {
            MedicationJson.obj("table" to table, "op" to op, "id" to id, "nutrient_key" to nutrientKey, "reason" to reason)
        } else if (op == "skip") {
            MedicationJson.obj("table" to table, "op" to op, "id" to id, "reason" to reason)
        } else {
            MedicationJson.obj("table" to table, "op" to op, "id" to id, "row" to row)
        }
    }

    data class MergeResult(val ok: Boolean, val error: String?, val ops: List<MergeOp>) {
        fun toJson(): JsonObject = MedicationJson.obj("ok" to ok, "error" to error, "ops" to ops.map { it.toJson() })
    }

    private fun row(columns: List<String>, src: JsonObject, vararg overrides: Pair<String, JsonElement>): JsonObject {
        val out = LinkedHashMap<String, JsonElement>()
        for (c in columns) out[c] = src[c] ?: JsonNull
        for ((k, v) in overrides) out[k] = v
        return JsonObject(out)
    }

    // -- export -----------------------------------------------------------------------------------

    fun snapshotJson(snapshot: MedicationsSnapshot): JsonObject = MedicationJson.obj(
        "medications" to snapshot.medications.map { it.toJson() },
        "schedules" to snapshot.schedules.map { it.toJson() },
        "dose_logs" to snapshot.doseLogs.map { it.toJson() },
        "medication_nutrients" to snapshot.nutrients.map {
            MedicationJson.obj("medication_id" to it.medicationId, "nutrient_key" to it.nutrientKey, "amount_per_unit" to it.amountPerUnit)
        }
    )

    // -- supplement nutrients (schema v2, §21) ----------------------------------------------------

    /**
     * `nutrient_problem`: null when (key, amount_per_unit) may be stored, else `unknown_nutrient` |
     * `invalid_amount`. The key is a nutrient_reference.json or sports key; 0 < amount ≤ the cap of
     * its canonical unit (g 100, mg 10000, mcg 100000).
     */
    fun nutrientProblem(key: String?, amount: Double?): String? {
        val ref = NutrientReference.current
        val unit = key?.let(ref::unitOf) ?: return "unknown_nutrient"
        if (amount == null || !amount.isFinite() || amount <= 0 || amount > ref.amountPerUnitMax.getValue(unit)) return "invalid_amount"
        return null
    }

    private fun nutrientProblem(key: JsonElement?, amount: JsonElement?): String? {
        val k = (key as? JsonPrimitive)?.takeIf { it.isString }?.content
        val a = if (MedicationJson.isNumber(amount)) (amount as JsonPrimitive).doubleOrNull else null
        if (k == null) return "unknown_nutrient"
        if (a == null && NutrientReference.current.unitOf(k) != null) return "invalid_amount"
        return nutrientProblem(k, a)
    }

    /** `{medication_id: [{key, amount_per_unit}]}` sorted by key, from the snapshot's `medication_nutrients` rows. */
    private fun snapshotNutrients(snapshot: JsonObject): Map<String, List<JsonObject>> {
        val out = LinkedHashMap<String, MutableList<JsonObject>>()
        for (e in (snapshot["medication_nutrients"] as? JsonArray).orEmpty()) {
            val r = e as? JsonObject ?: continue
            out.getOrPut(r.str("medication_id") ?: "") { mutableListOf() } +=
                MedicationJson.obj("key" to r["nutrient_key"], "amount_per_unit" to r["amount_per_unit"])
        }
        return out.mapValues { (_, rows) -> rows.sortedBy { it.str("key") ?: "" } }
    }

    /**
     * (`nutrients` | null, skip ops) of one archive medication: null when the archive has no
     * `nutrients` list (an older export, keep the local rows); otherwise the valid rows sorted by
     * key and one skip op per bad row in archive order.
     */
    private fun archiveNutrients(m: JsonObject, id: String): Pair<JsonArray?, List<MergeOp>> {
        val raw = m["nutrients"] as? JsonArray ?: return null to emptyList()
        val good = mutableListOf<JsonObject>()
        val skips = mutableListOf<MergeOp>()
        val seen = HashSet<String>()
        for (item in raw) {
            val o = item as? JsonObject
            val keyEl = o?.get("key")
            val key = (keyEl as? JsonPrimitive)?.takeIf { it.isString }?.content
            var reason = nutrientProblem(keyEl, o?.get("amount_per_unit"))
            if (reason == null && key in seen) reason = "duplicate_nutrient"
            if (reason != null) {
                skips += MergeOp(TABLE_NUTRIENTS, "skip", id, reason = reason, nutrientKey = key)
                continue
            }
            seen += key!!
            good += MedicationJson.obj("key" to key, "amount_per_unit" to o!!["amount_per_unit"])
        }
        return JsonArray(good.sortedBy { it.str("key") ?: "" }) to skips
    }

    /** The nutrient rows an applied medication op carries: null = keep the local rows. */
    fun nutrientRows(row: JsonObject): List<Pair<String, Double>>? {
        val arr = row["nutrients"] as? JsonArray ?: return null
        return arr.mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val key = o.str("key") ?: return@mapNotNull null
            val amount = (o["amount_per_unit"] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull ?: return@mapNotNull null
            key to amount
        }
    }

    fun export(snapshot: MedicationsSnapshot, exportedMs: Long, timeZone: String, platform: String, appVersion: String): JsonObject =
        exportJson(snapshotJson(snapshot), exportedMs, timeZone, platform, appVersion)

    /** Every row of the three tables, arrays ordered by id, `photo_path` null. */
    fun exportJson(snapshot: JsonObject, exportedMs: Long, timeZone: String, platform: String, appVersion: String): JsonObject {
        fun rows(key: String) = (snapshot[key] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        val byMed = snapshotNutrients(snapshot)
        val meds = rows("medications").map { m ->
            val r = row(MEDICATION_COLUMNS, m, "photo_path" to JsonNull)
            JsonObject(r + ("nutrients" to JsonArray(byMed[r.str("id") ?: ""].orEmpty())))
        }.sortedBy { it.str("id") ?: "" }
        val scheds = rows("schedules").map { s ->
            row(SCHEDULE_COLUMNS, s, "times" to JsonArray(MedicationJson.strings(s["times"]).map(::JsonPrimitive)),
                "days" to JsonArray(MedicationJson.ints(s["days"]).map(::JsonPrimitive)))
        }.sortedBy { it.str("id") ?: "" }
        val logs = rows("dose_logs").map { row(DOSE_LOG_COLUMNS, it) }.sortedBy { it.str("id") ?: "" }
        return MedicationJson.obj(
            "format" to MedicationConstants.ARCHIVE_FORMAT, "version" to MedicationConstants.ARCHIVE_VERSION,
            "exported_ms" to exportedMs, "time_zone" to timeZone,
            "app" to MedicationJson.obj("platform" to platform, "version" to appVersion),
            "medications" to meds, "schedules" to scheds, "dose_logs" to logs
        )
    }

    // -- validation of archive rows ---------------------------------------------------------------

    private val FORMS = MedicationForm.entries.map { it.raw }.toSet()
    private val FOOD = FoodRelation.entries.map { it.raw }.toSet()
    private val STATUSES = MedicationStatus.entries.map { it.raw }.toSet()
    private val UNITS = DoseUnit.entries.map { it.raw }.toSet()
    private val STORED = DoseStatus.entries.filter { it.isStored }.map { it.raw }.toSet()

    private fun nonEmptyString(e: JsonElement?): Boolean = MedicationJson.isString(e) && (e as JsonPrimitive).content.isNotEmpty()
    private fun isNull(e: JsonElement?): Boolean = e == null || e is JsonNull

    private fun validMedication(m: JsonObject): Boolean {
        if (!nonEmptyString(m["id"])) return false
        val name = m.str("name")
        if (name == null || name.trim().isEmpty()) return false
        if (m.str("form") !in FORMS || m.str("food_relation") !in FOOD || m.str("status") !in STATUSES) return false
        if (!MedicationJson.isNumber(m["dose_quantity"]) || m.str("dose_unit") !in UNITS) return false
        if (MedicationLocalTime.parseDate(m.str("start_date")) == null) return false
        if (!isNull(m["end_date"]) && MedicationLocalTime.parseDate(m.str("end_date")) == null) return false
        if (!MedicationJson.isInt(m["created_ms"]) || !MedicationJson.isInt(m["updated_ms"])) return false
        return true
    }

    private fun validSchedule(s: JsonObject): Boolean {
        if (!nonEmptyString(s["id"])) return false
        if (!MedicationJson.isString(s["medication_id"]) || !MedicationJson.isInt(s["active_from_ms"])) return false
        if (!isNull(s["active_until_ms"]) && !MedicationJson.isInt(s["active_until_ms"])) return false
        if (!MedicationJson.isInt(s["created_ms"]) || !MedicationJson.isInt(s["updated_ms"])) return false
        return DraftValidation.validateSchedule(s).isEmpty()
    }

    private fun validLog(l: JsonObject): Boolean {
        if (!nonEmptyString(l["id"])) return false
        if (!MedicationJson.isString(l["medication_id"]) || !MedicationJson.isInt(l["scheduled_at_ms"])) return false
        if (l.str("status") !in STORED) return false
        if (!isNull(l["schedule_id"]) && !MedicationJson.isString(l["schedule_id"])) return false
        if (!MedicationJson.isNumber(l["dose_quantity"]) || !MedicationJson.isString(l["dose_unit"])) return false
        if (!MedicationJson.isInt(l["created_ms"]) || !MedicationJson.isInt(l["updated_ms"])) return false
        return true
    }

    // -- merge ------------------------------------------------------------------------------------

    fun merge(local: MedicationsSnapshot, archive: JsonObject, nowMs: Long): MergeResult = mergeJson(snapshotJson(local), archive, nowMs)

    /**
     * Per row by id: absent → insert, newer `updated_ms` → update, else `skip:older`. A future
     * `updated_ms` is clamped to [nowMs]. The caller runs the ops in one transaction, medications first.
     */
    fun mergeJson(snapshot: JsonObject, archive: JsonObject, nowMs: Long): MergeResult {
        if (archive.str("format") != MedicationConstants.ARCHIVE_FORMAT) return MergeResult(false, "bad_format", emptyList())
        val version = (archive["version"] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull
        if (version != MedicationConstants.ARCHIVE_VERSION.toDouble()) return MergeResult(false, "unsupported_version", emptyList())
        val ops = mutableListOf<MergeOp>()
        fun rows(o: JsonObject, key: String) = (o[key] as? JsonArray).orEmpty()
        val localMeds = rows(snapshot, "medications").mapNotNull { it as? JsonObject }.associateBy { it.str("id") ?: "" }
        val localScheds = rows(snapshot, "schedules").mapNotNull { it as? JsonObject }.associateBy { it.str("id") ?: "" }
        val localLogs = rows(snapshot, "dose_logs").mapNotNull { it as? JsonObject }.associateBy { it.str("id") ?: "" }
        val occupied = HashMap<Pair<String, Long>, String>()
        for (l in localLogs.values) {
            val sid = l.str("schedule_id") ?: continue
            occupied[sid to (l.long("scheduled_at_ms") ?: 0L)] = l.str("id") ?: ""
        }
        val knownMeds = localMeds.keys.toMutableSet()
        val knownScheds = localScheds.keys.toMutableSet()

        fun decide(table: String, src: JsonObject, local: JsonObject?, columns: List<String>): Boolean {
            var r = row(columns, src)
            val updated = r.long("updated_ms")
            if (MedicationJson.isInt(r["updated_ms"]) && updated != null && updated > nowMs) {
                r = row(columns, r, "updated_ms" to JsonPrimitive(nowMs))
            }
            val id = r.str("id")
            if (local == null) {
                ops += MergeOp(table, "insert", id, row = r)
                return true
            }
            if ((r.long("updated_ms") ?: 0L) > (local.long("updated_ms") ?: 0L)) {
                ops += MergeOp(table, "update", id, row = r)
                return true
            }
            ops += MergeOp(table, "skip", id, reason = "older")
            return false
        }

        for (e in rows(archive, "medications")) {
            val m = e as? JsonObject
            if (m == null || !validMedication(m)) {
                ops += MergeOp(TABLE_MEDICATIONS, "skip", m?.str("id"), reason = "invalid")
                continue
            }
            val id = m.str("id")!!
            val localRow = localMeds[id]
            val photo: JsonElement = if (localRow == null) JsonNull else (localRow["photo_path"] ?: JsonNull)
            val src = JsonObject(m + ("photo_path" to photo))
            val (nutrients, nutrientSkips) = archiveNutrients(m, id)
            if (decide(TABLE_MEDICATIONS, src, localRow, MEDICATION_COLUMNS)) {
                val last = ops.removeAt(ops.lastIndex)
                ops += last.copy(row = JsonObject(last.row!! + ("nutrients" to (nutrients ?: JsonNull))))
                ops += nutrientSkips
            }
            knownMeds += id
        }
        for (e in rows(archive, "schedules")) {
            val s = e as? JsonObject
            if (s == null || !validSchedule(s)) {
                ops += MergeOp(TABLE_SCHEDULES, "skip", s?.str("id"), reason = "invalid")
                continue
            }
            val id = s.str("id")!!
            if (s.str("medication_id") !in knownMeds) {
                ops += MergeOp(TABLE_SCHEDULES, "skip", id, reason = "orphan")
                continue
            }
            decide(TABLE_SCHEDULES, s, localScheds[id], SCHEDULE_COLUMNS)
            knownScheds += id
        }
        for (e in rows(archive, "dose_logs")) {
            val l = e as? JsonObject
            if (l == null || !validLog(l)) {
                ops += MergeOp(TABLE_DOSE_LOGS, "skip", l?.str("id"), reason = "invalid")
                continue
            }
            val id = l.str("id")!!
            val sid = l.str("schedule_id")
            if (l.str("medication_id") !in knownMeds || (sid != null && sid !in knownScheds)) {
                ops += MergeOp(TABLE_DOSE_LOGS, "skip", id, reason = "orphan")
                continue
            }
            val scheduledAt = l.long("scheduled_at_ms") ?: 0L
            if (sid != null) {
                val holder = occupied[sid to scheduledAt]
                if (holder != null && holder != id) {
                    ops += MergeOp(TABLE_DOSE_LOGS, "skip", id, reason = "occurrence_conflict")
                    continue
                }
            }
            if (decide(TABLE_DOSE_LOGS, l, localLogs[id], DOSE_LOG_COLUMNS) && sid != null) {
                occupied[sid to scheduledAt] = id
            }
        }
        return MergeResult(true, null, ops)
    }
}
