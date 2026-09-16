package com.ayuvo.health.medications.logic

import com.ayuvo.health.medications.model.Medication
import com.ayuvo.health.medications.model.MedicationSchedule
import com.ayuvo.health.medications.model.MedicationStatus
import com.ayuvo.health.medications.model.Occurrence
import com.ayuvo.health.medications.model.ScheduleFrequency

/** `expand_occurrences` / `expand_all` / `schedule_slots` (docs/medications.md §6). */
object Occurrences {

    fun sortedUniqueTimes(times: List<String>): List<String> {
        val out = mutableListOf<String>()
        for (t in times) if (MedicationLocalTime.parseHhmm(t) != null && t !in out) out += t
        return out.sorted()
    }

    /** Effective `HH:mm` slots of a schedule row; interval rows derive theirs from anchor + k·interval mod 24 h. */
    fun slots(schedule: MedicationSchedule): List<String> = when (schedule.frequency) {
        ScheduleFrequency.DAILY, ScheduleFrequency.WEEKLY -> sortedUniqueTimes(schedule.times)
        ScheduleFrequency.INTERVAL -> {
            val n = schedule.intervalHours
            val anchor = MedicationLocalTime.parseHhmm(schedule.anchorTime)
            if (n == null || n !in MedicationConstants.INTERVAL_HOURS || anchor == null) emptyList()
            else {
                val set = sortedSetOf<String>()
                for (k in 0 until 24 / n) {
                    var total = (anchor.first + k * n) * 60 + anchor.second
                    total %= 24 * 60
                    set += MedicationLocalTime.formatHhmm(total / 60, total % 60)
                }
                set.toList()
            }
        }
    }

    /**
     * Occurrences of one schedule row inside `[windowStartMs, windowEndMs)`. PRN medications never
     * have occurrences; status is NOT checked (callers filter). Two slots collapsing onto one instant
     * (spring-forward gap) keep only the earlier slot.
     */
    fun expand(schedule: MedicationSchedule, medication: Medication, windowStartMs: Long, windowEndMs: Long, timeZone: String): List<Occurrence> {
        if (medication.isPrn) return emptyList()
        if (windowEndMs <= windowStartMs) return emptyList()
        val slots = slots(schedule)
        if (slots.isEmpty()) return emptyList()
        val startDate = MedicationLocalTime.parseDate(medication.startDate) ?: return emptyList()
        // An unparseable end date reads as "no end date", exactly like the reference's parse_date → None.
        val endDate = medication.endDate?.let { MedicationLocalTime.parseDate(it) }
        val zone = MedicationLocalTime.zone(timeZone)
        val first = MedicationLocalTime.localDate(windowStartMs, zone)
        val last = MedicationLocalTime.localDate(windowEndMs, zone)
        val out = mutableListOf<Occurrence>()
        val seen = HashSet<Long>()
        var d = first
        while (!d.isAfter(last)) {
            if (!d.isBefore(startDate) && (endDate == null || !d.isAfter(endDate))) {
                if (schedule.frequency != ScheduleFrequency.WEEKLY || d.dayOfWeek.value in schedule.days) {
                    val dateStr = MedicationLocalTime.formatDate(d)
                    for (slot in slots) {
                        val hm = MedicationLocalTime.parseHhmm(slot) ?: continue
                        val t = MedicationLocalTime.instant(d, hm.first, hm.second, zone)
                        if (t < schedule.activeFromMs) continue
                        val until = schedule.activeUntilMs
                        if (until != null && t >= until) continue
                        if (t < windowStartMs || t >= windowEndMs) continue
                        if (!seen.add(t)) continue
                        out += Occurrence(medication.id, schedule.id, t, dateStr, slot)
                    }
                }
            }
            d = d.plusDays(1)
        }
        out.sortWith(compareBy<Occurrence> { it.scheduledAtMs }.thenBy { it.slot })
        return out
    }

    /**
     * Occurrences of every schedule row whose medication has one of [statuses] (null = any), sorted by
     * `(scheduled_at_ms, folded name, medication_id, schedule_id, slot)`.
     */
    fun expandAll(
        medications: List<Medication>,
        schedules: List<MedicationSchedule>,
        windowStartMs: Long,
        windowEndMs: Long,
        timeZone: String,
        statuses: Set<MedicationStatus>? = null
    ): List<Occurrence> {
        val byId = medications.associateBy { it.id }
        val out = mutableListOf<Occurrence>()
        for (s in schedules) {
            val m = byId[s.medicationId] ?: continue
            if (statuses != null && m.status !in statuses) continue
            out += expand(s, m, windowStartMs, windowEndMs, timeZone)
        }
        out.sortWith(
            compareBy<Occurrence> { it.scheduledAtMs }
                .thenBy { MedicationLocalTime.foldName(byId[it.medicationId]?.name) }
                .thenBy { it.medicationId }.thenBy { it.scheduleId }.thenBy { it.slot }
        )
        return out
    }
}
