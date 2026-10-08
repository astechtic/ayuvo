package com.ayuvo.health.partner.logic

import kotlin.math.floor

/** docs/partner-sync.md §15: the Summary card's headline metrics for one partner. */
object PartnerSummary {
    /** Display rounding shared by both ports: floor(v + 0.5). */
    fun roundHalfUp(v: Double): Long = floor(v + 0.5).toLong()

    private fun inDays(r: StoredRecord, vararg days: String) = r.day != null && r.day in days

    /**
     * Up to [limit] metrics in priority order recovery, sleep, resting_hr, activity|steps, medicines. A metric
     * appears only when a stored row exists (nothing is fabricated); one from a category the partner no longer
     * shares is flagged `shared = false`.
     */
    fun metrics(rows: List<StoredRecord>, today: String, yesterday: String, grantsReceived: Collection<String>, limit: Int = 3): List<SummaryMetric> {
        val out = mutableListOf<SummaryMetric>()
        val granted = grantsReceived.toSet()
        fun add(key: String, category: String, value: Long, unit: String?, day: String) {
            out += SummaryMetric(key, category, value, unit, day, category in granted)
        }
        fun ofType(t: String) = rows.filter { it.type == t }

        val rec = ofType("analytics_day").filter {
            PartnerJson.str(it.data["metric_id"]) == "recovery_indicator" && inDays(it, today, yesterday) && PartnerJson.isNum(it.data["value"])
        }.sortedByDescending { it.day }
        rec.firstOrNull()?.let { add("recovery", "vitals", roundHalfUp(PartnerJson.double(it.data["value"])!!), null, it.day!!) }

        val sleep = ofType("sleep_night").filter { inDays(it, today, yesterday) }.sortedByDescending { it.day }
        sleep.firstOrNull()?.let {
            // Python int(): truncation toward zero.
            add("sleep", "sleep", PartnerJson.double(it.data["asleep_min"])!!.toLong(), "min", it.day!!)
        }

        val rhr = ofType("metric_day").filter {
            PartnerJson.str(it.data["type_id"]) == "resting_heart_rate" && inDays(it, today, yesterday) && PartnerJson.isNum(it.data["avg"])
        }.sortedByDescending { it.day }
        rhr.firstOrNull()?.let { add("resting_hr", "vitals", roundHalfUp(PartnerJson.double(it.data["avg"])!!), "bpm", it.day!!) }

        val ex = ofType("metric_day").filter {
            PartnerJson.str(it.data["type_id"]) == "exercise_minutes" && it.day == today && PartnerJson.isNum(it.data["sum"])
        }
        val steps = ofType("metric_day").filter {
            PartnerJson.str(it.data["type_id"]) == "steps" && it.day == today && PartnerJson.isNum(it.data["sum"])
        }
        val exSum = ex.firstOrNull()?.let { PartnerJson.double(it.data["sum"])!! }
        if (exSum != null && exSum > 0) add("activity", "vitals", roundHalfUp(exSum), "min", today)
        else if (steps.isNotEmpty()) add("steps", "vitals", roundHalfUp(PartnerJson.double(steps[0].data["sum"])!!), "count", today)

        val doses = ofType("dose_log").filter { it.day == today }
        if (doses.isNotEmpty()) {
            val taken = doses.count { PartnerJson.str(it.data["status"]) == "taken" }
            add("medicines", "medicines", taken.toLong(), "of:${doses.size}", today)
        }
        return out.take(limit)
    }
}
