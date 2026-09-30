package com.ayuvo.health.data.intake

import com.ayuvo.health.data.intake.IntakeMath.roundTo
import com.ayuvo.health.medications.logic.MedicationJson
import kotlinx.serialization.json.JsonObject
import kotlin.math.abs
import kotlin.math.floor

/** One dose of one medication: status "taken" | "missed" | "skipped". */
data class AdherenceLog(val scheduledMs: Long, val takenMs: Long?, val status: String)

data class MedsAdherenceInput(val timeZone: String, val logs: List<AdherenceLog>)

data class MedsAdherenceResult(
    val scheduled: Int,
    val taken: Int,
    val adherencePct: Double?,
    val adherent: Boolean?,
    val onTimePct: Double?,
    val medianDelayMin: Double?,
    /** Suggested reminder clock in minutes after local midnight, rounded to 15 minutes. */
    val suggestedClockMin: Int?,
    /** Missed doses per ISO weekday, Monday first. */
    val missedByWeekday: List<Int>,
    val streak: Int
) {
    fun toJson(): JsonObject = MedicationJson.obj(
        "scheduled" to scheduled, "taken" to taken, "adherence_pct" to adherencePct, "adherent" to adherent,
        "on_time_pct" to onTimePct, "median_delay_min" to medianDelayMin, "suggested_clock_min" to suggestedClockMin,
        "missed_by_weekday" to missedByWeekday, "streak" to streak
    )
}

/** Port of `meds_adherence`. */
object MedicationAdherence {

    fun medsAdherence(inp: MedsAdherenceInput, cfg: IntakeConfig): MedsAdherenceResult {
        val th = cfg.thresholds
        val tz = inp.timeZone
        val logs = inp.logs.sortedBy { it.scheduledMs }
        val taken = logs.filter { it.status == "taken" && it.takenMs != null }
        val missed = logs.filter { it.status == "missed" }
        val denom = taken.size + missed.size
        val byWeekday = MutableList(7) { 0 }
        if (denom == 0) return MedsAdherenceResult(0, 0, null, null, null, null, null, byWeekday, 0)
        val pct = taken.size * 100.0 / denom
        var onTime: Double? = null
        var medianDelay: Double? = null
        var suggested: Int? = null
        if (taken.isNotEmpty()) {
            val delays = taken.map { (it.takenMs!! - it.scheduledMs) / 60000.0 }
            val on = delays.count { abs(it) <= th.onTimeMin }
            onTime = roundTo(on * 100.0 / taken.size, 1)
            val md = IntakeMath.median(delays)
            medianDelay = roundTo(md, 0)
            if (taken.size >= th.suggestMinTaken && md > th.suggestDelayMin) {
                val c = IntakeMath.median(taken.map { IntakeMath.minuteOfDay(it.takenMs!!, tz).toDouble() })
                suggested = Math.floorMod(floor(c / 15.0 + 0.5).toInt() * 15, 1440)
            }
        }
        for (l in missed) byWeekday[IntakeMath.local(l.scheduledMs, tz).dayOfWeek.value - 1] += 1
        var streak = 0
        for (l in logs.asReversed()) {
            if (l.status == "taken") streak += 1 else if (l.status == "missed") break
        }
        return MedsAdherenceResult(
            scheduled = denom, taken = taken.size, adherencePct = roundTo(pct, 1), adherent = pct >= th.adherentPct,
            onTimePct = onTime, medianDelayMin = medianDelay, suggestedClockMin = suggested, missedByWeekday = byWeekday, streak = streak
        )
    }
}
