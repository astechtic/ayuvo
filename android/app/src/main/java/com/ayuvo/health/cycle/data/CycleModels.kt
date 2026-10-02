package com.ayuvo.health.cycle.data

import com.ayuvo.health.cycle.engine.CycleReminderInput
import com.ayuvo.health.cycle.engine.CycleSettingsInput
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull

/** A row of `cycle_periods` (docs/cycle-tracking.md §2). Days are local `yyyy-MM-dd`; `endDay` null = ongoing. */
data class CyclePeriod(
    val id: String,
    val startDay: String,
    val endDay: String?,
    val platformIdsJson: String = "{}",
    val syncState: String = CycleSyncState.PENDING,
    val createdMs: Long,
    val updatedMs: Long,
    val deleted: Boolean = false
)

/** A row of `cycle_day_logs`, one per local day. Lists hold catalogue keys from cycle_config.json. */
data class CycleDayLog(
    val day: String,
    val flow: String? = null,
    val pain: Int? = null,
    val painLocations: List<String> = emptyList(),
    val symptoms: List<String> = emptyList(),
    val moods: List<String> = emptyList(),
    val note: String? = null,
    val platformIdsJson: String = "{}",
    val syncState: String = CycleSyncState.PENDING,
    val updatedMs: Long = 0,
    val deleted: Boolean = false
) {
    /** True when nothing is logged (a save of an empty log is a delete). */
    val isEmpty: Boolean
        get() = flow == null && pain == null && painLocations.isEmpty() && symptoms.isEmpty() && moods.isEmpty() && note.isNullOrBlank()
}

object CycleSyncState {
    const val PENDING = "pending"
    const val SYNCED = "synced"
    const val FAILED = "failed"
    /** Health sync is off: nothing to write. */
    const val LOCAL = "local"
}

/** Reminder, display and sync preferences stored in `cycle_settings.settings_json`. */
data class CyclePreferences(
    val periodSoon: Boolean = true,
    val daysBefore: Int = 2,
    val periodEnd: Boolean = true,
    val daily: Boolean = false,
    /** Local wall-clock time 'HH:mm' for every cycle reminder. */
    val time: String = "09:00",
    val lockScreenDetails: Boolean = false,
    val showFertility: Boolean = true,
    val healthSync: Boolean = false
) {
    fun toJson(): JsonObject = JsonObject(
        linkedMapOf(
            "reminders" to JsonObject(
                linkedMapOf(
                    "period_soon" to JsonPrimitive(periodSoon),
                    "days_before" to JsonPrimitive(daysBefore),
                    "period_end" to JsonPrimitive(periodEnd),
                    "daily" to JsonPrimitive(daily),
                    "time" to JsonPrimitive(time),
                    "lock_screen_details" to JsonPrimitive(lockScreenDetails)
                )
            ),
            "show_fertility" to JsonPrimitive(showFertility),
            "health_sync" to JsonPrimitive(healthSync)
        )
    )

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun parse(text: String?): CyclePreferences {
            val o = text?.let { runCatching { json.parseToJsonElement(it) as? JsonObject }.getOrNull() } ?: return CyclePreferences()
            return fromJson(o)
        }

        fun fromJson(o: JsonObject): CyclePreferences {
            val d = CyclePreferences()
            val r = o["reminders"] as? JsonObject ?: JsonObject(emptyMap())
            fun b(obj: JsonObject, k: String, def: Boolean) = (obj[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.booleanOrNull ?: def
            return CyclePreferences(
                periodSoon = b(r, "period_soon", d.periodSoon),
                daysBefore = ((r["days_before"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.intOrNull ?: d.daysBefore).coerceIn(1, 3),
                periodEnd = b(r, "period_end", d.periodEnd),
                daily = b(r, "daily", d.daily),
                time = (r["time"] as? JsonPrimitive)?.takeIf { it !is JsonNull && it.isString }?.content
                    ?.takeIf { Regex("^([01]\\d|2[0-3]):[0-5]\\d$").matches(it) } ?: d.time,
                lockScreenDetails = b(r, "lock_screen_details", d.lockScreenDetails),
                showFertility = b(o, "show_fertility", d.showFertility),
                healthSync = b(o, "health_sync", d.healthSync)
            )
        }
    }
}

/** The single `cycle_settings` row. Lengths null = use the configured default. */
data class CycleSettingsRow(
    val setupDone: Boolean = false,
    val cycleLength: Int? = null,
    val periodLength: Int? = null,
    val lutealLength: Int? = null,
    val preferences: CyclePreferences = CyclePreferences(),
    val updatedMs: Long = 0
) {
    /** The engine's view of these settings. */
    fun toEngineInput(): CycleSettingsInput = CycleSettingsInput(
        cycleLength, periodLength, lutealLength,
        CycleReminderInput(preferences.periodSoon, preferences.daysBefore, preferences.periodEnd, preferences.daily)
    )
}

internal object CycleJsonLists {
    private val json = Json { ignoreUnknownKeys = true }

    fun encode(values: List<String>): String = JsonArray(values.map(::JsonPrimitive)).toString()

    fun decode(text: String?): List<String> {
        if (text.isNullOrBlank()) return emptyList()
        val e: JsonElement = runCatching { json.parseToJsonElement(text) }.getOrNull() ?: return emptyList()
        return (e as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content } ?: emptyList()
    }
}
