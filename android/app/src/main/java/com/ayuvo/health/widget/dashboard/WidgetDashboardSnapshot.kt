package com.ayuvo.health.widget.dashboard

import kotlinx.serialization.Serializable

/**
 * What the Today and My Metrics widgets read (docs/widgets.md "Dashboard snapshot"). The app
 * writes it; widgets never compute from the stores. Every value is null when there is no real
 * data behind it, never 0. Day-scoped values belong to [dayKey] and are ignored on another day.
 */
@Serializable
data class WidgetDashboardSnapshot(
    val version: Int = VERSION,
    val generatedAtMs: Long,
    /** Local day the day-scoped values belong to, `yyyy-MM-dd`. */
    val dayKey: String,
    val themeHex: Int? = null,
    val weightMetric: Boolean = true,
    val waterUnitRaw: String = "ml",
    val eat: RingValue,
    val move: MoveRing,
    val drink: DrinkRing,
    val fasting: FastingInfo,
    /** Null when no medications database exists (it is never created just to look). */
    val medications: MedicationsInfo? = null,
    val weight: BodyReading? = null,
    val bodyFat: BodyReading? = null,
    val workouts: WorkoutsToday? = null,
    /** My Metrics tiles by metric key, pre-formatted with the app's own formatters. */
    val tiles: Map<String, WidgetTile> = emptyMap()
) {
    companion object {
        const val VERSION = 1
    }
}

/** Eat ring: kcal today against the calorie goal. */
@Serializable
data class RingValue(val value: Double?, val goal: Double?)

/** Move ring: steps against the step goal; [connected] false = Health Connect not readable. */
@Serializable
data class MoveRing(val steps: Double?, val goal: Double?, val connected: Boolean)

/** Drink ring: mL today against the water goal; hidden when water tracking is off. */
@Serializable
data class DrinkRing(val ml: Double?, val goal: Double?, val enabled: Boolean)

@Serializable
data class FastingInfo(val enabled: Boolean, val activeStartedAtMs: Long? = null, val goalMinutes: Int? = null)

@Serializable
data class MedicationsInfo(val doses: List<DoseInfo>, val taken: Int, val total: Int)

/** One scheduled dose today; [status] is the store's raw value at write time. */
@Serializable
data class DoseInfo(val name: String, val scheduledAtMs: Long, val status: String)

/** Latest real weight (kg) or body fat (%) with the time it was measured. */
@Serializable
data class BodyReading(val value: Double, val atMs: Long)

@Serializable
data class WorkoutsToday(val count: Int, val minutes: Int, val burnKcal: Int? = null, val firstTitle: String? = null)

/**
 * One pre-formatted tile. [caption] mirrors `HealthTileUi.CaptionKind`; `TODAY` and `LAST_NIGHT`
 * tiles are day-scoped. [progress] is value / goal (clamped 0–1) where the metric has a goal.
 */
@Serializable
data class WidgetTile(
    val number: String,
    val unit: String,
    val caption: String,
    val captionMs: Long? = null,
    val hasData: Boolean,
    val progress: Double? = null
) {
    val isDayScoped: Boolean get() = caption == CAPTION_TODAY || caption == CAPTION_LAST_NIGHT

    companion object {
        const val CAPTION_TODAY = "TODAY"
        const val CAPTION_LAST_NIGHT = "LAST_NIGHT"
    }
}
