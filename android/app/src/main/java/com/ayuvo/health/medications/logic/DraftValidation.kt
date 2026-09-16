package com.ayuvo.health.medications.logic

import com.ayuvo.health.medications.logic.MedicationJson.str
import com.ayuvo.health.medications.logic.MedicationJson.truthy
import com.ayuvo.health.medications.model.DoseUnit
import com.ayuvo.health.medications.model.FoodRelation
import com.ayuvo.health.medications.model.MedicationForm
import com.ayuvo.health.medications.model.ScheduleFrequency
import com.ayuvo.health.medications.model.ValidationError
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

/**
 * `validate_draft` / `_validate_schedule` (docs/medications.md §15) on the raw form JSON, so a
 * wrongly typed value fails exactly as in the reference. Codes are the contract's.
 */
object DraftValidation {

    private val FORMS = MedicationForm.entries.map { it.raw }.toSet()
    private val FOOD = FoodRelation.entries.map { it.raw }.toSet()
    private val UNITS = DoseUnit.entries.map { it.raw }.toSet()
    private val KINDS = ScheduleFrequency.entries.map { it.raw }.toSet()

    private fun present(o: JsonObject, key: String): Boolean = o[key] != null && o[key] !is JsonNull
    private fun isList(o: JsonObject, key: String): Boolean = o[key] is JsonArray
    private fun nonEmptyList(o: JsonObject, key: String): Boolean = (o[key] as? JsonArray)?.isNotEmpty() == true

    fun validateSchedule(s: JsonObject, prefix: String = ""): List<ValidationError> {
        val errs = mutableListOf<ValidationError>()
        val kind = s.str("frequency_kind")
        if (kind == null || kind !in KINDS) {
            errs += ValidationError(prefix + "frequency_kind", "frequency_required")
            return errs
        }
        if (kind == "daily" || kind == "weekly") {
            if (!nonEmptyList(s, "times")) {
                errs += ValidationError(prefix + "times", "times_required")
            } else {
                val arr = s["times"] as JsonArray
                val strings = arr.map { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
                val ok = strings.all { it != null && MedicationLocalTime.parseHhmm(it) != null }
                val values = strings.map { it ?: "" }
                if (!ok || values.size > MedicationConstants.MAX_TIMES || values.toSet().size != values.size || values.sorted() != values) {
                    errs += ValidationError(prefix + "times", "times_invalid")
                }
            }
        }
        if (kind == "weekly") {
            if (!nonEmptyList(s, "days")) {
                errs += ValidationError(prefix + "days", "days_required")
            } else {
                val arr = s["days"] as JsonArray
                val allInts = arr.all { MedicationJson.isInt(it) && (it as JsonPrimitive).content.toLong() in 1L..7L }
                val values = arr.map { (it as? JsonPrimitive)?.takeIf { p -> !p.isString }?.doubleOrNull ?: Double.NaN }
                if (!allInts || values.toSet().size != values.size || values.sorted() != values) {
                    errs += ValidationError(prefix + "days", "days_invalid")
                }
            }
        } else if (nonEmptyList(s, "days")) {
            errs += ValidationError(prefix + "days", "days_not_allowed")
        }
        if (kind == "interval") {
            val n = s["interval_hours"]
            if (!MedicationJson.isInt(n) || (n as JsonPrimitive).content.toLong().toInt() !in MedicationConstants.INTERVAL_HOURS) {
                errs += ValidationError(prefix + "interval_hours", "interval_invalid")
            }
            val anchor = s["anchor_time"]
            val anchorStr = (anchor as? JsonPrimitive)?.takeIf { it !is JsonNull }
            if (anchorStr == null || (anchorStr.isString && anchorStr.content.isEmpty())) {
                errs += ValidationError(prefix + "anchor_time", "anchor_required")
            } else if (!anchorStr.isString || MedicationLocalTime.parseHhmm(anchorStr.content) == null) {
                errs += ValidationError(prefix + "anchor_time", "anchor_invalid")
            }
        }
        return errs
    }

    /** Errors of an Add/Edit form draft in field order; empty = valid. */
    fun validate(draft: JsonObject): List<ValidationError> {
        val errs = mutableListOf<ValidationError>()
        val d = draft
        val name = d.str("name")
        if (name == null || name.trim().isEmpty() || MedicationLocalTime.codePoints(name.trim()) > MedicationConstants.NAME_MAX) {
            errs += ValidationError("name", "name_required")
        }
        if (present(d, "strength")) {
            val strength = d.str("strength")
            if (strength == null || MedicationLocalTime.codePoints(strength) > MedicationConstants.STRENGTH_MAX) {
                errs += ValidationError("strength", "strength_too_long")
            }
        }
        if (d.str("form") !in FORMS) errs += ValidationError("form", "form_invalid")
        val q = d["dose_quantity"]
        val qv = if (MedicationJson.isNumber(q)) (q as JsonPrimitive).doubleOrNull else null
        if (qv == null || qv <= 0 || qv > MedicationConstants.DOSE_QUANTITY_MAX) errs += ValidationError("dose_quantity", "dose_quantity_invalid")
        if (d.str("dose_unit") !in UNITS) errs += ValidationError("dose_unit", "dose_unit_invalid")
        if (d.str("food_relation") !in FOOD) errs += ValidationError("food_relation", "food_relation_invalid")
        val start = MedicationLocalTime.parseDate(d.str("start_date"))
        if (start == null) errs += ValidationError("start_date", "start_date_invalid")
        if (present(d, "end_date")) {
            val end = MedicationLocalTime.parseDate(d.str("end_date"))
            if (end == null) errs += ValidationError("end_date", "end_date_invalid")
            else if (start != null && end.isBefore(start)) errs += ValidationError("end_date", "end_date_before_start")
        }
        val isPrn = d.truthy("is_prn")
        val hasSchedule = present(d, "frequency_kind") || nonEmptyList(d, "times") || nonEmptyList(d, "days") || present(d, "interval_hours")
        if (isPrn) {
            if (hasSchedule) errs += ValidationError("frequency_kind", "prn_has_schedule")
        } else {
            errs += validateSchedule(d)
        }
        if (present(d, "instructions")) {
            val instructions = d.str("instructions")
            if (instructions == null || MedicationLocalTime.codePoints(instructions) > MedicationConstants.INSTRUCTIONS_MAX) {
                errs += ValidationError("instructions", "instructions_too_long")
            }
        }
        if (present(d, "note")) {
            val note = d.str("note")
            if (note == null || MedicationLocalTime.codePoints(note) > MedicationConstants.NOTE_MAX) {
                errs += ValidationError("note", "note_too_long")
            }
        }
        return errs
    }
}
