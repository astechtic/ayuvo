package com.ayuvo.health.widget.dashboard

import com.ayuvo.health.medications.model.DoseStatus
import java.time.Instant
import java.time.ZoneId

/** Next pending dose; [due] = its time has passed without a taken/skipped/missed log. */
data class NextDose(val name: String, val scheduledAtMs: Long, val due: Boolean)

/**
 * Pure render-time rules shared by the widgets (docs/widgets.md): day-scoped values from another
 * day are ignored, fasting elapsed time and the next dose are computed at [nowMs].
 */
object DashboardRender {

    fun isToday(snapshot: WidgetDashboardSnapshot, nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): Boolean =
        snapshot.dayKey == Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate().toString()

    /** First pending dose at or after now; otherwise the earliest overdue pending dose. */
    fun nextDose(snapshot: WidgetDashboardSnapshot, nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): NextDose? {
        if (!isToday(snapshot, nowMs, zone)) return null
        val pending = snapshot.medications?.doses.orEmpty().filterNot { DoseStatus.fromRaw(it.status).isTerminal }
        pending.filter { it.scheduledAtMs >= nowMs }.minByOrNull { it.scheduledAtMs }
            ?.let { return NextDose(it.name, it.scheduledAtMs, due = false) }
        return pending.minByOrNull { it.scheduledAtMs }?.let { NextDose(it.name, it.scheduledAtMs, due = true) }
    }

    /** Minutes since the active fast started, null when no fast is running. */
    fun fastingElapsedMinutes(snapshot: WidgetDashboardSnapshot, nowMs: Long): Long? =
        snapshot.fasting.activeStartedAtMs?.let { ((nowMs - it).coerceAtLeast(0L)) / 60_000L }

    /** The tile to draw for [key]; day-scoped tiles from another day become empty. */
    fun tile(snapshot: WidgetDashboardSnapshot, key: String, nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): WidgetTile {
        val tile = snapshot.tiles[key] ?: return EMPTY
        return if (tile.isDayScoped && !isToday(snapshot, nowMs, zone)) EMPTY else tile
    }

    /** Ring progress 0–1, or null when value or goal is unknown (the ring then shows an empty track). */
    fun progress(value: Double?, goal: Double?): Float? =
        if (value == null || goal == null || goal <= 0) null else (value / goal).coerceIn(0.0, 1.0).toFloat()

    /** Next moment the widgets should redraw on their own: a dose time, the fasting goal or midnight. */
    fun nextBoundaryMs(snapshot: WidgetDashboardSnapshot, nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): Long {
        val midnight = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate().plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val doses = snapshot.medications?.doses.orEmpty()
            .filterNot { DoseStatus.fromRaw(it.status).isTerminal }
            .map { it.scheduledAtMs }
            .filter { it > nowMs }
        val fastGoal = snapshot.fasting.activeStartedAtMs?.let { start ->
            snapshot.fasting.goalMinutes?.let { start + it * 60_000L }
        }?.takeIf { it > nowMs }
        return (doses + listOfNotNull(fastGoal) + midnight).min()
    }

    val EMPTY = WidgetTile(number = "—", unit = "", caption = "NONE", hasData = false)
}
