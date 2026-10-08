package com.ayuvo.health.partner.sources

import com.ayuvo.health.data.health.SleepNight
import com.ayuvo.health.models.FoodEntry
import com.ayuvo.health.models.WaterEntry
import com.ayuvo.health.models.WeightEntry
import com.ayuvo.health.models.WorkoutSession
import com.ayuvo.health.models.WorkoutWeightUnit
import com.ayuvo.health.partner.logic.PartnerJson
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import androidx.health.connect.client.records.ExerciseSessionRecord as E
import java.time.Instant
import java.time.ZoneId
import kotlin.math.roundToLong

/**
 * docs/partner-sync.md §7.3–§7.4: the blob-backed types (`food_entry`, `water_day`, `weight`, `workout`) and
 * `sleep_night`, built from each model's named fields only, following `record_types.json`. No photo, image path,
 * note, GPS track, route, split or lap is ever read. Pure functions (JVM-tested).
 */
object PartnerSourceMaps {
    private const val STRING_MAX = 2000

    private fun num(v: Double?): JsonElement? = v?.takeIf { it.isFinite() }?.let { JsonPrimitive(round(it, 3)) }
    private fun int(v: Long?): JsonElement? = v?.let { JsonPrimitive(it) }
    private fun text(v: String?): JsonElement? = v?.trim()?.takeIf { it.isNotEmpty() }?.let { JsonPrimitive(PartnerJson.pyTake(it, STRING_MAX)) }

    /** Rounds to [decimals] places so float noise never changes a content hash. */
    fun round(v: Double, decimals: Int): Double {
        var f = 1.0
        repeat(decimals) { f *= 10.0 }
        return (v * f).roundToLong() / f
    }

    private fun plain(v: Double): String {
        val r = round(v, 2)
        return if (r == Math.floor(r)) r.toLong().toString() else r.toString()
    }

    fun day(ms: Long, zone: ZoneId): String = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate().toString()

    /** `food_entry`: the diary entry on the local day of its log time; never a photo or image filename. */
    fun food(e: FoodEntry, zone: ZoneId): SourceRecord {
        val logged = e.timestamp.toEpochMilli()
        val serving = when {
            e.selectedServingQuantity != null && !e.selectedServingUnit.isNullOrBlank() ->
                "${plain(e.selectedServingQuantity)} ${e.selectedServingUnit.trim()}"
            e.servingSizeGrams != null && e.servingSizeGrams > 0 -> "${plain(e.servingSizeGrams)} g"
            else -> null
        }
        val data = PartnerJson.dropNone(
            "name" to (text(e.name) ?: JsonPrimitive("Food")),
            "logged_ms" to JsonPrimitive(logged),
            "calories" to JsonPrimitive(e.calories),
            "meal" to JsonPrimitive(e.mealType.name.lowercase()),
            "serving" to text(serving),
            "protein_g" to num(e.protein),
            "carbs_g" to num(e.carbs),
            "fat_g" to num(e.fat),
            "fiber_g" to num(e.fiber),
            "sugar_g" to num(e.sugar),
            "sodium_mg" to num(e.sodium),
            "emoji" to text(e.emoji)
        )
        return SourceRecord("food_entry", e.id.toString(), "nutrition", day(logged, zone), data)
    }

    /** `water_day`: total ml per local day. [goalMl] is attached only to the day it applies to (today). */
    fun waterDays(entries: List<WaterEntry>, zone: ZoneId, today: String, goalMl: Int?): List<SourceRecord> =
        entries.groupBy { day(it.date.toEpochMilli(), zone) }.toSortedMap().map { (d, list) ->
            val data = PartnerJson.dropNone(
                "day" to JsonPrimitive(d),
                "total_ml" to JsonPrimitive(list.sumOf { it.milliliters.toLong() }),
                "goal_ml" to (if (d == today && goalMl != null && goalMl > 0) JsonPrimitive(goalMl) else null)
            )
            SourceRecord("water_day", d, "nutrition", d, data)
        }

    /** `weight`: the app's own weight entries. */
    fun weight(e: WeightEntry, zone: ZoneId): SourceRecord {
        val ms = e.date.toEpochMilli()
        val data = PartnerJson.dropNone("measured_ms" to JsonPrimitive(ms), "kg" to num(e.weightKg))
        return SourceRecord("weight", e.id.toString(), "vitals", day(ms, zone), data)
    }

    /**
     * `workout` from the workout diary: activity, times, duration, kcal, distance, pace, HR, load (TRIMP) and the
     * performed set/volume summary. The GPS route, splits and laps are never read.
     */
    fun workout(s: WorkoutSession, zone: ZoneId): SourceRecord {
        val interval = s.trainingIntervalMs
        val end = interval?.second ?: s.completedAt.toEpochMilli()
        val start = interval?.first ?: end
        val durationS = if (s.durationSeconds > 0) s.durationSeconds.toLong() else (end - start).coerceAtLeast(0L) / 1000L
        val gps = if (s.isGps) s.gps else null
        val activity = gps?.sport?.takeIf { it.isNotBlank() } ?: "strength_training"
        val title = s.exercises.map { it.name.trim() }.filter { it.isNotEmpty() }.distinct().joinToString(", ")
        val sets = s.performedSetCount
        var volume = 0.0
        for (ex in s.exercises) for (set in ex.sets) {
            if (!set.isPerformed) continue
            val w = set.weight.replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() && it > 0 } ?: continue
            val reps = set.reps.toIntOrNull()?.takeIf { it > 0 } ?: continue
            val kg = if (set.weightUnit == WorkoutWeightUnit.LBS) w * 0.45359237 else w
            volume += kg * reps
        }
        val kcal = s.caloriesBurned ?: gps?.activeKcal
        val data = PartnerJson.dropNone(
            "activity" to JsonPrimitive(activity),
            "title" to text(title),
            "start_ms" to JsonPrimitive(start),
            "end_ms" to JsonPrimitive(end),
            "duration_s" to JsonPrimitive(durationS),
            "kcal" to kcal?.let { JsonPrimitive(it) },
            "distance_m" to gps?.distanceM?.takeIf { it > 0 }?.let { num(it) },
            "pace_s_per_km" to num(gps?.avgPaceSecondsPerKm),
            "avg_hr" to num(s.heartRate?.avgHr),
            "max_hr" to num(s.heartRate?.maxHr),
            "load" to num(s.heartRate?.trimp),
            "sets" to (if (sets > 0) JsonPrimitive(sets) else null),
            "volume_kg" to (if (volume > 0) num(volume) else null),
            "source" to JsonPrimitive("ayuvo")
        )
        return SourceRecord("workout", s.id.toString(), "workouts", day(start, zone), data)
    }

    /** `workout` from a Health Connect / Google Health exercise session row (no route, no notes). */
    fun healthWorkout(id: String, exerciseType: Int?, title: String?, startMs: Long, endMs: Long, localDay: String, sourceName: String?): SourceRecord {
        val data = PartnerJson.dropNone(
            "activity" to JsonPrimitive(PartnerExerciseTypes.name(exerciseType)),
            "title" to text(title),
            "start_ms" to JsonPrimitive(startMs),
            "end_ms" to JsonPrimitive(endMs),
            "duration_s" to JsonPrimitive((endMs - startMs).coerceAtLeast(0L) / 1000L),
            "source" to text(sourceName)
        )
        return SourceRecord("workout", id, "workouts", localDay, data)
    }

    /** Averages measured during a night, each present only when readings exist. */
    data class NightVitals(val avgHr: Double? = null, val avgHrv: Double? = null, val avgRespRate: Double? = null)

    /** `sleep_night`: one per wake day from HealthSleepAnalysis; stage minutes only when stage data exists. */
    fun sleepNight(n: SleepNight, vitals: NightVitals): SourceRecord {
        fun min(s: Double): JsonElement = JsonPrimitive((s / 60.0).roundToLong())
        val staged = n.deepS + n.remS + n.lightS > 0
        val data = PartnerJson.dropNone(
            "day" to JsonPrimitive(n.nightOf),
            "start_ms" to JsonPrimitive(n.startMs),
            "end_ms" to JsonPrimitive(n.endMs),
            "asleep_min" to min(n.asleepS),
            "in_bed_min" to (if (n.inBedS > 0) min(n.inBedS) else null),
            "deep_min" to (if (staged) min(n.deepS) else null),
            "rem_min" to (if (staged) min(n.remS) else null),
            "light_min" to (if (staged) min(n.lightS) else null),
            "awake_min" to (if (staged || n.awakeS > 0) min(n.awakeS) else null),
            "avg_hr" to vitals.avgHr?.let { num(round(it, 1)) },
            "avg_hrv" to vitals.avgHrv?.let { num(round(it, 1)) },
            "avg_resp_rate" to vitals.avgRespRate?.let { num(round(it, 1)) },
            "efficiency" to (if (n.inBedS > 0 && n.asleepS > 0) JsonPrimitive(round(minOf(1.0, n.asleepS / n.inBedS) * 100.0, 1)) else null)
        )
        return SourceRecord("sleep_night", n.nightOf, "sleep", n.nightOf, data)
    }
}

/** Health Connect `ExerciseSessionRecord.EXERCISE_TYPE_*` codes → stable snake_case activity names. */
object PartnerExerciseTypes {
    private val NAMES: Map<Int, String> = mapOf(
        E.EXERCISE_TYPE_BADMINTON to "badminton",
        E.EXERCISE_TYPE_BASEBALL to "baseball",
        E.EXERCISE_TYPE_BASKETBALL to "basketball",
        E.EXERCISE_TYPE_BIKING to "biking",
        E.EXERCISE_TYPE_BIKING_STATIONARY to "biking_stationary",
        E.EXERCISE_TYPE_BOOT_CAMP to "boot_camp",
        E.EXERCISE_TYPE_BOXING to "boxing",
        E.EXERCISE_TYPE_CALISTHENICS to "calisthenics",
        E.EXERCISE_TYPE_CRICKET to "cricket",
        E.EXERCISE_TYPE_DANCING to "dancing",
        E.EXERCISE_TYPE_ELLIPTICAL to "elliptical",
        E.EXERCISE_TYPE_EXERCISE_CLASS to "exercise_class",
        E.EXERCISE_TYPE_FENCING to "fencing",
        E.EXERCISE_TYPE_FOOTBALL_AMERICAN to "football_american",
        E.EXERCISE_TYPE_FOOTBALL_AUSTRALIAN to "football_australian",
        E.EXERCISE_TYPE_FRISBEE_DISC to "frisbee_disc",
        E.EXERCISE_TYPE_GOLF to "golf",
        E.EXERCISE_TYPE_GUIDED_BREATHING to "guided_breathing",
        E.EXERCISE_TYPE_GYMNASTICS to "gymnastics",
        E.EXERCISE_TYPE_HANDBALL to "handball",
        E.EXERCISE_TYPE_HIGH_INTENSITY_INTERVAL_TRAINING to "high_intensity_interval_training",
        E.EXERCISE_TYPE_HIKING to "hiking",
        E.EXERCISE_TYPE_ICE_HOCKEY to "ice_hockey",
        E.EXERCISE_TYPE_ICE_SKATING to "ice_skating",
        E.EXERCISE_TYPE_MARTIAL_ARTS to "martial_arts",
        E.EXERCISE_TYPE_PADDLING to "paddling",
        E.EXERCISE_TYPE_PARAGLIDING to "paragliding",
        E.EXERCISE_TYPE_PILATES to "pilates",
        E.EXERCISE_TYPE_RACQUETBALL to "racquetball",
        E.EXERCISE_TYPE_ROCK_CLIMBING to "rock_climbing",
        E.EXERCISE_TYPE_ROLLER_HOCKEY to "roller_hockey",
        E.EXERCISE_TYPE_ROWING to "rowing",
        E.EXERCISE_TYPE_ROWING_MACHINE to "rowing_machine",
        E.EXERCISE_TYPE_RUGBY to "rugby",
        E.EXERCISE_TYPE_RUNNING to "running",
        E.EXERCISE_TYPE_RUNNING_TREADMILL to "running_treadmill",
        E.EXERCISE_TYPE_SAILING to "sailing",
        E.EXERCISE_TYPE_SCUBA_DIVING to "scuba_diving",
        E.EXERCISE_TYPE_SKATING to "skating",
        E.EXERCISE_TYPE_SKIING to "skiing",
        E.EXERCISE_TYPE_SNOWBOARDING to "snowboarding",
        E.EXERCISE_TYPE_SNOWSHOEING to "snowshoeing",
        E.EXERCISE_TYPE_SOCCER to "soccer",
        E.EXERCISE_TYPE_SOFTBALL to "softball",
        E.EXERCISE_TYPE_SQUASH to "squash",
        E.EXERCISE_TYPE_STAIR_CLIMBING to "stair_climbing",
        E.EXERCISE_TYPE_STAIR_CLIMBING_MACHINE to "stair_climbing_machine",
        E.EXERCISE_TYPE_STRENGTH_TRAINING to "strength_training",
        E.EXERCISE_TYPE_STRETCHING to "stretching",
        E.EXERCISE_TYPE_SURFING to "surfing",
        E.EXERCISE_TYPE_SWIMMING_OPEN_WATER to "swimming_open_water",
        E.EXERCISE_TYPE_SWIMMING_POOL to "swimming_pool",
        E.EXERCISE_TYPE_TABLE_TENNIS to "table_tennis",
        E.EXERCISE_TYPE_TENNIS to "tennis",
        E.EXERCISE_TYPE_VOLLEYBALL to "volleyball",
        E.EXERCISE_TYPE_WALKING to "walking",
        E.EXERCISE_TYPE_WATER_POLO to "water_polo",
        E.EXERCISE_TYPE_WEIGHTLIFTING to "weightlifting",
        E.EXERCISE_TYPE_WHEELCHAIR to "wheelchair",
        E.EXERCISE_TYPE_YOGA to "yoga",
        E.EXERCISE_TYPE_OTHER_WORKOUT to "other_workout"
    )

    fun name(code: Int?): String = code?.let { NAMES[it] } ?: "other_workout"
}
