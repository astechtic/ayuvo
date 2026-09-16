package com.ayuvo.health.medications.logic

import com.ayuvo.health.medications.model.DoseLog
import com.ayuvo.health.medications.model.DoseStatus
import com.ayuvo.health.medications.model.Medication
import com.ayuvo.health.medications.model.MedicationSchedule
import com.ayuvo.health.medications.model.MedicationStatus
import com.ayuvo.health.medications.model.Occurrence
import com.ayuvo.health.medications.model.PrnRow
import com.ayuvo.health.medications.model.TimelineItem
import com.ayuvo.health.medications.model.TimelineKind
import com.ayuvo.health.medications.model.TimelineSlot
import com.ayuvo.health.medications.model.TodaySummary
import com.ayuvo.health.medications.model.TodayTimeline
import java.util.Collections
import java.util.IdentityHashMap

/**
 * `today_timeline` (docs/medications.md §8): the local day of `now`. Items = occurrences of ACTIVE
 * medications' schedule rows inside the day (every row, so closed versions still contribute their
 * earlier doses) plus stored logs of the day that match no occurrence (paused/stopped medicines'
 * logs, PRN logs = kind `prn`).
 */
object TodayTimelineBuilder {

    fun build(medications: List<Medication>, schedules: List<MedicationSchedule>, logs: List<DoseLog>, nowMs: Long, timeZone: String): TodayTimeline {
        val byId = medications.associateBy { it.id }
        val date = MedicationLocalTime.localDateOf(nowMs, timeZone)
        val (start, end) = MedicationLocalTime.dayWindow(date, timeZone)
        val occurrences = Occurrences.expandAll(medications, schedules, start, end, timeZone, setOf(MedicationStatus.ACTIVE))
        val index = DoseResolutions.index(logs)
        val items = mutableListOf<TimelineItem>()
        val matched: MutableSet<DoseLog> = Collections.newSetFromMap(IdentityHashMap())
        for (occ in occurrences) {
            val med = byId.getValue(occ.medicationId)
            val log = index[occ.scheduleId to occ.scheduledAtMs]
            val r = DoseResolutions.resolve(occ, log, nowMs)
            if (log != null) matched += log
            items += TimelineItem(
                medicationId = occ.medicationId, scheduleId = occ.scheduleId, scheduledAtMs = occ.scheduledAtMs,
                status = r.status, isLate = r.isLate, logId = log?.id, snoozedUntilMs = log?.snoozedUntilMs,
                doseQuantity = log?.doseQuantity ?: med.doseQuantity, doseUnit = log?.doseUnit ?: med.doseUnit,
                kind = TimelineKind.SCHEDULED
            )
        }
        for (log in logs) {
            if (log in matched) continue
            val t = log.scheduledAtMs
            if (t < start || t >= end) continue
            if (log.medicationId !in byId) continue
            if (!log.status.isStored) continue
            val kind = if (log.scheduleId == null) TimelineKind.PRN else TimelineKind.SCHEDULED
            val r = DoseResolutions.resolve(Occurrence(log.medicationId, log.scheduleId ?: "", t), log, nowMs)
            items += TimelineItem(
                medicationId = log.medicationId, scheduleId = log.scheduleId, scheduledAtMs = t, status = r.status,
                isLate = r.isLate, logId = log.id, snoozedUntilMs = log.snoozedUntilMs, doseQuantity = log.doseQuantity,
                doseUnit = log.doseUnit, kind = kind
            )
        }
        val groups = sortedMapOf<String, MutableList<TimelineItem>>()
        for (it in items) groups.getOrPut(MedicationLocalTime.localHhmmOf(it.scheduledAtMs, timeZone)) { mutableListOf() } += it
        val ordered = groups.map { (slot, list) ->
            list.sortWith(
                compareBy<TimelineItem> { it.scheduledAtMs }
                    .thenBy { MedicationLocalTime.foldName(byId[it.medicationId]?.name) }
                    .thenBy { it.medicationId }.thenBy { it.scheduleId ?: "" }.thenBy { it.logId ?: "" }
            )
            TimelineSlot(slot, list.toList())
        }
        val scheduled = items.filter { it.kind == TimelineKind.SCHEDULED }
        val summary = TodaySummary(
            total = scheduled.size,
            taken = scheduled.count { it.status == DoseStatus.TAKEN },
            upcoming = scheduled.count { it.status == DoseStatus.SCHEDULED },
            due = scheduled.count { it.status == DoseStatus.DUE },
            snoozed = scheduled.count { it.status == DoseStatus.SNOOZED },
            missed = scheduled.count { it.status == DoseStatus.MISSED },
            skipped = scheduled.count { it.status == DoseStatus.SKIPPED }
        )
        val prnMeds = medications.filter { it.isPrn && it.status == MedicationStatus.ACTIVE }
            .sortedWith(compareBy<Medication> { MedicationLocalTime.foldName(it.name) }.thenBy { it.id })
        val prn = prnMeds.map { m ->
            var count = 0
            var last: Long? = null
            for (log in logs) {
                if (log.medicationId != m.id || log.scheduleId != null) continue
                if (log.status != DoseStatus.TAKEN) continue
                val t = log.scheduledAtMs
                if (t in start until end) count++
                val takenAt = log.takenAtMs
                if (takenAt != null && (last == null || takenAt > last!!)) last = takenAt
            }
            PrnRow(m.id, count, last)
        }
        return TodayTimeline(date, summary, ordered, prn)
    }
}
