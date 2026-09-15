package com.ayuvo.health.records.knowledge

import com.ayuvo.health.records.analytes.AnalyteCatalog
import com.ayuvo.health.records.model.AnalyteMethod
import com.ayuvo.health.records.model.ExtractionMethod
import com.ayuvo.health.records.model.FieldKey
import com.ayuvo.health.records.model.FieldState
import com.ayuvo.health.records.model.NewUserObservation
import com.ayuvo.health.records.model.Observation
import com.ayuvo.health.records.model.ObservationEdit
import com.ayuvo.health.records.model.RecordField
import com.ayuvo.health.records.model.ResultFlag
import com.ayuvo.health.records.processing.DateDetector
import com.ayuvo.health.records.processing.ExtractionWriter
import com.ayuvo.health.records.processing.LabRowParser
import com.ayuvo.health.records.processing.Py
import com.ayuvo.health.records.processing.RecordJson
import com.ayuvo.health.records.processing.RecordJson.string
import com.ayuvo.health.records.processing.RecordText
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

/**
 * §19 observations (observed date, promotion, edits, user aliases), ported from
 * `scripts/records_reference.py` (`observed_date`, `promote_observations`, `edit_observation`,
 * `recompute_flag`, `apply_user_alias`). Pure: the store applies the result inside the transaction
 * that changed the record's fields.
 */
object ObservationRules {

    /** The record columns promotion reads. */
    data class RecordContext(val recordId: String, val documentDate: String?, val sortDate: String?)

    data class ObservedDate(val date: String?, val method: String?)

    /** One promotion decision (`insert`, `reject`, `update`, `keep`) for a field. */
    data class Action(val action: String, val id: String, val fieldId: String)

    data class Promotion(val observations: List<Observation>, val actions: List<Action>, val panels: List<String>) {
        /** Rows that must be written: inserts and changed rows. */
        fun writes(): List<Pair<Boolean, Observation>> {
            val byId = observations.associateBy { it.id }
            return actions.filter { it.action != "keep" }.mapNotNull { a -> byId[a.id]?.let { (a.action == "insert") to it } }
        }
    }

    /** Reference `observed_date`. */
    fun observedDate(record: RecordContext, fields: List<RecordField>): ObservedDate {
        for (key in listOf(FieldKey.COLLECTION_DATE, FieldKey.REPORT_DATE)) {
            ExtractionWriter.best(fields, key)?.let { return ObservedDate(it.valueText, key) }
        }
        if (!record.documentDate.isNullOrEmpty()) return ObservedDate(record.documentDate, "document_date")
        if (!record.sortDate.isNullOrEmpty()) return ObservedDate(record.sortDate, "sort_date")
        return ObservedDate(null, null)
    }

    private fun JsonObject?.num(k: String): Double? = (this?.get(k) as? JsonPrimitive)?.takeIf { it !is JsonNull && !it.isString }?.doubleOrNull

    /** Reference `record_panels`. */
    fun recordPanels(fields: List<RecordField>, catalog: AnalyteCatalog): List<String> {
        val live = fields.filter { it.state != FieldState.REJECTED }
        val texts = live.filter { it.key == FieldKey.REPORT_NAME }.map { it.valueText }
        val names = live.filter { it.key == FieldKey.TEST_RESULT }.map { f -> RecordJson.parseObject(f.valueJson)?.string("name")?.takeIf { it.isNotEmpty() } ?: f.valueText }
        return catalog.detectPanels(texts, names)
    }

    /** Reference `_observation_from_field` (id, created/updated filled by the caller). */
    fun fromField(
        field: RecordField,
        record: RecordContext,
        panels: List<String>,
        userAliases: Map<String, String>?,
        od: ObservedDate,
        catalog: AnalyteCatalog
    ): Observation {
        val vj = RecordJson.parseObject(field.valueJson)
        val raw = vj?.string("name")?.takeIf { it.isNotEmpty() } ?: field.valueText
        val vn = vj.num("value_num")
        val printedUnit = vj?.string("unit")
        val m = catalog.map(raw, panels, userAliases, printedUnit, vn == null)
        val conv = catalog.convert(m.analyteId, vn, printedUnit)
        return Observation(
            id = "",
            recordId = record.recordId,
            fieldId = field.id,
            analyteId = m.analyteId,
            analyteMethod = m.method,
            rawName = raw,
            valueNum = vn,
            valueText = vj?.string("value") ?: "",
            unit = conv.unit,
            canonicalValue = conv.value,
            canonicalUnit = conv.canonicalUnit,
            refLow = vj.num("ref_low"),
            refHigh = vj.num("ref_high"),
            refText = vj?.string("ref_text"),
            flag = ResultFlag.fromRaw(vj?.string("flag")?.takeIf { it.isNotEmpty() } ?: "unknown"),
            observedDate = od.date,
            observedDateMethod = od.method,
            method = field.method,
            confidence = field.confidence,
            state = if (field.state == FieldState.CONFIRMED || field.state == FieldState.USER) FieldState.CONFIRMED else FieldState.SUGGESTED,
            sourcePage = field.sourcePage,
            sourceBbox = field.sourceBbox,
            evidence = field.evidence,
            excludedFromTrends = false
        )
    }

    /** `_OBS_SYNC_KEYS` of [target] copied onto [o]. */
    private fun sync(o: Observation, t: Observation): Observation = o.copy(
        analyteId = t.analyteId, analyteMethod = t.analyteMethod, rawName = t.rawName, valueNum = t.valueNum,
        valueText = t.valueText, unit = t.unit, canonicalValue = t.canonicalValue, canonicalUnit = t.canonicalUnit,
        refLow = t.refLow, refHigh = t.refHigh, refText = t.refText, flag = t.flag, method = t.method,
        confidence = t.confidence, state = t.state, sourcePage = t.sourcePage, sourceBbox = t.sourceBbox, evidence = t.evidence
    )

    /** Reference `promote_observations`; inserted ids come from [newId] (the vectors use `obs-N`). */
    fun promote(
        fields: List<RecordField>,
        existing: List<Observation>,
        record: RecordContext,
        userAliases: Map<String, String>?,
        nowMs: Long,
        catalog: AnalyteCatalog,
        newId: (Int) -> String = { "obs-$it" }
    ): Promotion {
        val panels = recordPanels(fields, catalog)
        val od = observedDate(record, fields)
        val obs = existing.toMutableList()
        val byField = LinkedHashMap<String, Int>()
        obs.forEachIndexed { i, o -> if (o.fieldId != null && o.fieldId !in byField) byField[o.fieldId] = i }
        val actions = mutableListOf<Action>()
        var inserted = 0
        for (fd in fields) {
            if (fd.key != FieldKey.TEST_RESULT) continue
            val idx = byField[fd.id]
            if (idx == null) {
                if (fd.state == FieldState.REJECTED) continue
                inserted++
                val new = fromField(fd, record, panels, userAliases, od, catalog).copy(id = newId(inserted), createdMs = nowMs, updatedMs = nowMs)
                obs += new
                byField[fd.id] = obs.size - 1
                actions += Action("insert", new.id, fd.id)
                continue
            }
            var o = obs[idx]
            if (fd.state == FieldState.REJECTED) {
                if (o.state == FieldState.SUGGESTED || o.state == FieldState.CONFIRMED) {
                    obs[idx] = o.copy(state = FieldState.REJECTED, updatedMs = nowMs)
                    actions += Action("reject", o.id, fd.id)
                } else {
                    actions += Action("keep", o.id, fd.id)
                }
                continue
            }
            var changed = false
            if (o.state == FieldState.SUGGESTED) {
                val target = fromField(fd, record, panels, userAliases, od, catalog)
                val synced = sync(o, target)
                if (synced != o) {
                    o = synced
                    changed = true
                }
            }
            if (o.state != FieldState.REJECTED && o.observedDateMethod != "user") {
                if (o.observedDate != od.date || o.observedDateMethod != od.method) {
                    o = o.copy(observedDate = od.date, observedDateMethod = od.method)
                    changed = true
                }
            }
            if (changed) o = o.copy(updatedMs = nowMs)
            obs[idx] = o
            actions += Action(if (changed) "update" else "keep", o.id, fd.id)
        }
        return Promotion(obs, actions, panels)
    }

    // -- edits (§24) ----------------------------------------------------------------------------

    private val WS = Regex("[ \t\n]+")
    private val RE_EDIT_RANGE = Py.re("([0-9]{1,3})[ ]?-[ ]?([0-9]{1,3})")
    private val RE_ISO_DATE = Py.re("([0-9]{4})-([0-9]{2})-([0-9]{2})")

    /** Reference `_parse_ref_text`. */
    fun parseRefText(refText: String?): Pair<Double?, Double?> {
        if (refText == null) return null to null
        val rf = Py.strip(RecordText.fold(refText), " ()[]")
        for ((rx, kind) in LabRowParser.REF_PATTERNS) {
            val fm = Py.full(rx, rf) ?: continue
            return when (kind) {
                "range" -> LabRowParser.numValue(fm.group(1)) to LabRowParser.numValue(fm.group(2))
                "high" -> null to LabRowParser.numValue(fm.group(1))
                else -> LabRowParser.numValue(fm.group(1)) to null
            }
        }
        return null to null
    }

    private data class ValueParts(val num: Double?, val comparator: String?, val range: Boolean, val qualitative: Boolean)

    private fun valueParts(valueText: String?): ValueParts {
        val vf = Py.strip(RecordText.fold(valueText ?: ""), " ")
        if (Py.full(RE_EDIT_RANGE, vf) != null) return ValueParts(null, null, true, false)
        val vm = Py.full(LabRowParser.RE_VALUE_NUM, vf)
        if (vm != null) return ValueParts(LabRowParser.numValue(vm.group(2)), vm.group(1), false, false)
        return ValueParts(null, null, false, true)
    }

    /** Reference `recompute_flag`. */
    fun recomputeFlag(valueText: String?, refText: String?, refLow: Double?, refHigh: Double?): String {
        val vp = valueParts(valueText)
        var value = Py.strip(RecordText.fold(valueText ?: ""), " ")
        if (vp.range) value = value.replace(" ", "")
        return LabRowParser.resolveFlag(
            LabRowParser.Tail(
                value = value, valueNum = vp.num, qualitative = vp.qualitative, comparator = vp.comparator,
                rangeValue = vp.range, refText = refText, refLow = refLow, refHigh = refHigh
            )
        )
    }

    data class EditResult(val observation: Observation, val error: String?)

    /** Reference `edit_observation`. */
    fun edit(obs: Observation, patch: ObservationEdit, nowMs: Long, catalog: AnalyteCatalog): EditResult {
        if (patch.setAnalyte && patch.analyteId != null && patch.analyteId !in catalog.byId) return EditResult(obs, "unknown_analyte")
        if (patch.setObservedDate && patch.observedDate != null) {
            val m = Py.full(RE_ISO_DATE, patch.observedDate)
            if (m == null || DateDetector.valid(m.group(1).toInt(), m.group(2).toInt(), m.group(3).toInt()) == null) return EditResult(obs, "bad_date")
        }
        if (patch.setValue && (patch.value ?: "").replace(WS, " ").trim(' ').isEmpty()) return EditResult(obs, "empty_value")
        if (patch.remove) return EditResult(obs.copy(state = FieldState.REJECTED, updatedMs = nowMs), null)
        var o = obs
        if (patch.setValue) {
            val v = patch.value!!.replace(WS, " ").trim(' ')
            o = o.copy(valueText = v, valueNum = valueParts(v).num)
        }
        var unitChanged = false
        val oldUnit = o.unit
        if (patch.setUnit) {
            val newUnit = catalog.canonicalUnitSpelling(patch.unit)
            unitChanged = newUnit != o.unit
            o = o.copy(unit = newUnit)
        }
        if (patch.setRefText) {
            val rt = (patch.refText ?: "").replace(WS, " ").trim(' ').ifEmpty { null }
            val (lo, hi) = parseRefText(rt)
            o = o.copy(refText = rt, refLow = lo, refHigh = hi)
        }
        if (patch.setAnalyte) o = o.copy(analyteId = patch.analyteId, analyteMethod = if (patch.analyteId != null) AnalyteMethod.USER else null)
        if (patch.setObservedDate) o = o.copy(observedDate = patch.observedDate, observedDateMethod = "user")
        patch.excludedFromTrends?.let { o = o.copy(excludedFromTrends = it) }
        var rangesOk = true
        if (unitChanged && !patch.setRefText && (o.refLow != null || o.refHigh != null)) {
            // The printed range is in the original unit: convert the bounds, or drop them (ref_text stays as printed).
            val lo = catalog.convertBetween(o.analyteId, o.refLow, oldUnit, o.unit)
            val hi = catalog.convertBetween(o.analyteId, o.refHigh, oldUnit, o.unit)
            if ((o.refLow != null && lo == null) || (o.refHigh != null && hi == null)) {
                o = o.copy(refLow = null, refHigh = null)
                rangesOk = false
            } else {
                o = o.copy(refLow = lo, refHigh = hi)
            }
        }
        if (!rangesOk) o = o.copy(flag = ResultFlag.UNKNOWN)
        else if (patch.setValue || patch.setRefText || unitChanged) o = o.copy(flag = ResultFlag.fromRaw(recomputeFlag(o.valueText, o.refText, o.refLow, o.refHigh)))
        val conv = catalog.convert(o.analyteId, o.valueNum, o.unit)
        o = o.copy(canonicalValue = conv.value, canonicalUnit = conv.canonicalUnit, state = FieldState.USER, updatedMs = nowMs)
        return EditResult(o, null)
    }

    data class AliasResult(val normalizedName: String?, val analyteId: String?, val observations: List<Observation>, val updatedIds: List<String>, val error: String?)

    /** Reference `apply_user_alias`. */
    fun applyUserAlias(observations: List<Observation>, rawName: String, analyteId: String, nowMs: Long, catalog: AnalyteCatalog): AliasResult {
        val key = AnalyteCatalog.normalizeTestName(rawName)
        if (analyteId !in catalog.byId) return AliasResult(null, null, observations, emptyList(), "unknown_analyte")
        val updated = mutableListOf<String>()
        val out = observations.map { o ->
            if (o.state != FieldState.REJECTED && o.analyteId == null && key in AnalyteCatalog.testNameKeys(o.rawName)) {
                val conv = catalog.convert(analyteId, o.valueNum, o.unit)
                updated += o.id
                o.copy(analyteId = analyteId, analyteMethod = AnalyteMethod.USER_ALIAS, canonicalValue = conv.value, canonicalUnit = conv.canonicalUnit, updatedMs = nowMs)
            } else o
        }
        return AliasResult(key, analyteId, out, updated, null)
    }

    /** "Add value" (§24): `method = user`, no source; flag from the entered range only. */
    fun newUserObservation(input: NewUserObservation, id: String, catalog: AnalyteCatalog, observed: ObservedDate, nowMs: Long): Observation {
        val base = Observation(
            id = id,
            recordId = input.recordId,
            rawName = input.name.trim().ifEmpty { catalog.displayName(input.analyteId) ?: "" },
            valueText = input.valueText,
            method = ExtractionMethod.USER,
            confidence = 1.0,
            state = FieldState.USER,
            observedDate = observed.date,
            observedDateMethod = observed.method,
            createdMs = nowMs,
            updatedMs = nowMs
        )
        return edit(
            base,
            ObservationEdit(
                setValue = true, value = input.valueText,
                setUnit = true, unit = input.unit,
                setRefText = true, refText = input.refText,
                setAnalyte = true, analyteId = input.analyteId?.takeIf { it in catalog.byId },
                // §24: default date = §19 observed_date; always user-owned once added.
                setObservedDate = true, observedDate = input.observedDate ?: observed.date
            ),
            nowMs,
            catalog
        ).observation
    }
}
