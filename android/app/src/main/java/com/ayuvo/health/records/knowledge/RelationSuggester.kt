package com.ayuvo.health.records.knowledge

import com.ayuvo.health.records.processing.RecordText
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.math.min

/** §22 related-record suggestions, ported from the reference `suggest_relations`. Pure. */
object RelationSuggester {

    /** What the scorer knows about one record (reference record/candidate dict). */
    data class RecordFacts(
        val id: String,
        val recordType: String,
        val sortDate: String,
        val archived: Boolean = false,
        val splitParent: Boolean = false,
        val panels: List<String> = emptyList(),
        val reportName: String? = null,
        val analytes: List<String> = emptyList(),
        /** Normalized doctor (primary and referrer) entity names. */
        val doctors: List<String> = emptyList(),
        val facilities: List<String> = emptyList(),
        val followUpDates: List<String> = emptyList()
    )

    data class ExistingLink(val aId: String, val bId: String, val status: String)

    data class Suggestion(val aId: String, val bId: String, val kind: String, val score: Double, val reasons: List<String>) {
        val origin: String get() = "suggested"
        val status: String get() = "suggested"
        fun other(id: String): String = if (id == aId) bId else aId
    }

    const val MAX_SUGGESTIONS = 5
    const val WINDOW_DAYS = 180L
    private val LINK_PRIORITY = listOf("follow_up", "prescription_for", "previous_report", "same_episode")
    private val LAB_LIKE = setOf("lab_report", "imaging_report", "diagnostic_report")

    private fun daysBetween(a: String, b: String): Long = ChronoUnit.DAYS.between(LocalDate.parse(a), LocalDate.parse(b))

    fun suggest(record: RecordFacts, candidates: List<RecordFacts>, existingLinks: List<ExistingLink> = emptyList()): List<Suggestion> {
        if (record.archived && record.splitParent) return emptyList()
        val pairs = existingLinks.map { it.aId to it.bId }.toSet()
        val pending = existingLinks.count { it.status == "suggested" && (record.id == it.aId || record.id == it.bId) }
        val slots = maxOf(0, MAX_SUGGESTIONS - pending)
        data class Found(val negTotal: Double, val gap: Long, val id: String, val s: Suggestion)
        val found = mutableListOf<Found>()
        for (c in candidates) {
            if (c.id == record.id || (c.archived && c.splitParent)) continue
            val gap = daysBetween(record.sortDate, c.sortDate)
            if (abs(gap) > WINDOW_DAYS) continue
            val (a, b) = if (record.id <= c.id) record.id to c.id else c.id to record.id
            if ((a to b) in pairs) continue
            val kinds = LinkedHashMap<String, Double>()
            val reasons = mutableListOf<String>()
            if (record.recordType in LAB_LIKE && c.recordType in LAB_LIKE) {
                val samePanel = record.panels.toSet().intersect(c.panels.toSet()).isNotEmpty()
                val rn = RecordText.normalizedValue(record.reportName ?: "")
                val sameName = rn.isNotEmpty() && rn == RecordText.normalizedValue(c.reportName ?: "")
                if (samePanel || sameName) {
                    var s = 0.5
                    if (samePanel) reasons += "same_panel"
                    if (sameName) reasons += "same_report_name"
                    if (record.analytes.toSet().intersect(c.analytes.toSet()).size >= 3) {
                        s += 0.2
                        reasons += "shared_analytes"
                    }
                    kinds["previous_report"] = s
                }
            }
            val near = abs(gap) <= 30
            if (near && record.doctors.toSet().intersect(c.doctors.toSet()).isNotEmpty()) {
                kinds["same_episode"] = (kinds["same_episode"] ?: 0.0) + 0.4
                reasons += "same_doctor"
            }
            if (near && record.facilities.toSet().intersect(c.facilities.toSet()).isNotEmpty()) {
                kinds["same_episode"] = (kinds["same_episode"] ?: 0.0) + 0.2
                reasons += "same_facility"
            }
            for ((p, v) in listOf(record to c, c to record)) {
                if (p.recordType == "prescription" && (v.recordType == "consultation_note" || v.recordType == "discharge_summary") &&
                    p.doctors.toSet().intersect(v.doctors.toSet()).isNotEmpty() && daysBetween(v.sortDate, p.sortDate) in 0..14
                ) {
                    kinds["prescription_for"] = 0.6
                    reasons += "prescription_after_visit"
                    break
                }
            }
            for ((x, y) in listOf(record to c, c to record)) {
                if (x.followUpDates.any { abs(daysBetween(it, y.sortDate)) <= 7 }) {
                    kinds["follow_up"] = 0.6
                    reasons += "follow_up_date"
                    break
                }
            }
            if (kinds.isEmpty()) continue
            val total = RecordText.round2(min(1.0, LINK_PRIORITY.filter { it in kinds }.sumOf { kinds.getValue(it) }))
            if (total < 0.6) continue
            val kind = kinds.keys.sortedWith(compareBy<String>({ -RecordText.round2(kinds.getValue(it)) }, { LINK_PRIORITY.indexOf(it) })).first()
            found += Found(-total, abs(gap), c.id, Suggestion(a, b, kind, total, reasons))
        }
        return found.sortedWith(compareBy<Found>({ it.negTotal }, { it.gap }, { it.id })).take(slots).map { it.s }
    }
}
