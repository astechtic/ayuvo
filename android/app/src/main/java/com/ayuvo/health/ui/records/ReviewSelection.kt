package com.ayuvo.health.ui.records

import com.ayuvo.health.records.model.FieldKey
import com.ayuvo.health.records.model.FieldState
import com.ayuvo.health.records.model.RecordField
import com.ayuvo.health.records.processing.ExtractionWriter
import com.ayuvo.health.records.processing.RecordJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.floatOrNull

/** One row of the "We found these details" sheet. */
data class ReviewItem(val field: RecordField, val conflict: Boolean, val keyField: Boolean)

/**
 * Which suggested values the review sheet shows (plan §2, docs §15): conflicting values of a
 * single-valued key (side by side), key fields (report name, doctor, facility, dates) and anything
 * below 0.8 confidence. Confirmed, user and rejected rows never come back.
 */
object ReviewSelection {
    const val UNCERTAIN_BELOW = 0.8

    fun items(fields: List<RecordField>): List<ReviewItem> {
        val conflicts = ExtractionWriter.conflicts(fields)
        val conflictingKeys = conflicts.keys
        return fields
            .filter { it.state == FieldState.SUGGESTED }
            .filter { it.key in conflictingKeys || FieldKey.isKeyField(it.key) || it.confidence < UNCERTAIN_BELOW }
            // A key already settled by the user is not asked again unless another value conflicts with it.
            .filter { field -> it_isOpen(field, fields, conflictingKeys) }
            .map { ReviewItem(it, conflict = it.key in conflictingKeys, keyField = FieldKey.isKeyField(it.key)) }
            .sortedWith(
                compareByDescending<ReviewItem> { it.conflict }
                    .thenByDescending { it.keyField }
                    .thenBy { FieldKey.ALL.indexOf(it.field.key).let { i -> if (i < 0) Int.MAX_VALUE else i } }
                    .thenBy { it.field.sourcePage ?: Int.MAX_VALUE }
                    .thenByDescending { it.field.confidence }
            )
    }

    @Suppress("FunctionName")
    private fun it_isOpen(field: RecordField, all: List<RecordField>, conflictingKeys: Set<String>): Boolean {
        if (field.key in conflictingKeys) return true
        if (!FieldKey.isSingleValued(field.key)) return true
        return all.none { it.key == field.key && (it.state == FieldState.CONFIRMED || it.state == FieldState.USER) }
    }
}

/** `source_bbox` / `blocks_json` helpers for the tap-to-source outline (normalized x, y, w, h). */
object SourceBoxes {
    fun parse(text: String?): List<Float>? {
        val array = RecordJson.parseArray(text) ?: return null
        val values = array.mapNotNull { (it as? JsonPrimitive)?.floatOrNull }
        return values.takeIf { it.size == 4 }
    }

    /** §25 lab-row outline: first line containing the folded evidence, else the line with the most shared words. */
    fun locateEvidence(blocksJson: String?, evidence: String?): List<Float>? {
        val needle = com.ayuvo.health.records.processing.RecordText.collapsed(evidence)
        if (needle.isEmpty()) return null
        val blocks = RecordJson.parseArray(blocksJson) ?: return null
        val needleWords = com.ayuvo.health.records.processing.RecordText.words(needle).toSet()
        var best: List<Float>? = null
        var bestOverlap = 0
        for (element in blocks) {
            val obj = element as? JsonObject ?: continue
            val line = com.ayuvo.health.records.processing.RecordText.collapsed((obj["t"] as? JsonPrimitive)?.content)
            val box = (obj["b"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.floatOrNull }?.takeIf { it.size == 4 } ?: continue
            if (line.isEmpty()) continue
            if (line.contains(needle)) return box
            val overlap = com.ayuvo.health.records.processing.RecordText.words(line).toSet().intersect(needleWords).size
            if (overlap > bestOverlap) { bestOverlap = overlap; best = box }
        }
        return best
    }

    /** Union of every line box vertically overlapping [box]'s centre (a table row's cells). */
    fun rowOf(blocksJson: String?, box: List<Float>): List<Float> {
        val blocks = RecordJson.parseArray(blocksJson) ?: return box
        val cy = box[1] + box[3] / 2
        var l = box[0]; var t = box[1]; var r = box[0] + box[2]; var b = box[1] + box[3]
        for (element in blocks) {
            val bb = ((element as? JsonObject)?.get("b") as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.floatOrNull }?.takeIf { it.size == 4 } ?: continue
            if (cy >= bb[1] && cy <= bb[1] + bb[3]) {
                l = minOf(l, bb[0]); t = minOf(t, bb[1]); r = maxOf(r, bb[0] + bb[2]); b = maxOf(b, bb[1] + bb[3])
            }
        }
        return listOf(l, t, r - l, b - t)
    }

    /** The first line box whose folded text contains the folded [evidence] (or the reverse). */
    fun locate(blocksJson: String?, evidence: String?): List<Float>? {
        val needle = com.ayuvo.health.records.processing.RecordText.collapsed(evidence)
        if (needle.isEmpty()) return null
        val blocks = RecordJson.parseArray(blocksJson) ?: return null
        var partial: List<Float>? = null
        for (element in blocks) {
            val obj = element as? JsonObject ?: continue
            val line = com.ayuvo.health.records.processing.RecordText.collapsed((obj["t"] as? JsonPrimitive)?.content)
            val box = (obj["b"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.floatOrNull }?.takeIf { it.size == 4 } ?: continue
            if (line.isEmpty()) continue
            if (line.contains(needle)) return box
            if (partial == null && needle.contains(line) && line.length >= 4) partial = box
        }
        return partial
    }
}
