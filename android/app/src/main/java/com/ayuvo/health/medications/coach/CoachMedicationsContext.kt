package com.ayuvo.health.medications.coach

import com.ayuvo.health.medications.logic.MedicationJson
import com.ayuvo.health.medications.logic.MedicationJson.arr
import com.ayuvo.health.medications.logic.MedicationJson.objOrNull
import com.ayuvo.health.medications.logic.MedicationJson.str
import com.ayuvo.health.medications.logic.MedicationsCoachTools
import com.ayuvo.health.records.processing.RecordText
import kotlinx.serialization.json.JsonObject

/**
 * What Coach may read about the user's medicines for one turn (docs/coach.md §3,
 * docs/medications.md §20). Built by the view model; null when the user has no medicines or has not
 * turned the source on.
 */
data class CoachMedicationsContext(
    /** `{medications, schedules, dose_logs}` — the same three tables the archive uses. */
    val snapshot: JsonObject,
    val timeZone: String,
    val nowMs: Long,
    val count: Int,
    val activeCount: Int
) {
    val toolsAvailable: Boolean get() = count > 0

    /** `## Data available` lines when the tools are advertised. */
    fun promptLines(): List<String> {
        val lines = MedicationsCoachTools.promptLines(snapshot, accessEnabled = true)
        if (lines["advertise_tools"].toString() != "true") return emptyList()
        return listOfNotNull(lines.str("available_line"), lines.str("guardrails"))
    }

    /**
     * At most 12 lines for providers without tool calling (docs/coach.md §3): one per active
     * medicine, name and schedule only. Never a dose recommendation.
     */
    fun onDeviceBlock(): String? {
        val payload = MedicationsCoachTools.medicationsPayload(snapshot, MedicationJson.obj())
        val rows = payload.arr("medications").orEmpty().filterIsInstance<JsonObject>()
        if (rows.isEmpty()) return null
        val lines = mutableListOf("## Medications")
        for (row in rows.take(MAX_ON_DEVICE_ROWS)) {
            val parts = mutableListOf<String>()
            val name = row.str("name").orEmpty()
            parts += row.str("strength")?.let { "$name $it" } ?: name
            if (row["is_prn"].toString() == "true") {
                parts += "as needed"
            } else {
                val times = MedicationJson.strings(row.objOrNull("schedule")?.get("times"))
                if (times.isNotEmpty()) parts += times.joinToString(", ")
            }
            lines += "- " + parts.joinToString(" · ")
        }
        if (rows.size > MAX_ON_DEVICE_ROWS) lines += "- (+${rows.size - MAX_ON_DEVICE_ROWS} more)"
        return lines.joinToString("\n")
    }

    companion object {
        const val MAX_ON_DEVICE_ROWS = 12

        /** The line shown when the user asks about medicines with the source off. */
        fun notAvailableLine(): String =
            MedicationsCoachTools.contract.prompt["not_available_line"].orEmpty()

        /**
         * Did the user actually ask about medicines? The word list lives in
         * `shared/medications/coach_tools.json` so both platforms answer this identically; the
         * message is folded first, so case and accents do not matter.
         */
        fun mentionsMedicines(message: String?): Boolean {
            val words = MedicationsCoachTools.contract.mentionsWords.toSet()
            if (words.isEmpty() || message.isNullOrBlank()) return false
            return RecordText.fold(message).split(Regex("[^\\p{L}\\p{N}]+"))
                .any { it.isNotEmpty() && it in words }
        }
    }
}
