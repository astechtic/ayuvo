package com.ayuvo.health.services.googlehealth

import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.BloodGlucoseRecord
import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.BodyTemperatureRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ElevationGainedRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.FloorsClimbedRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.HeightRecord
import androidx.health.connect.client.records.HydrationRecord
import androidx.health.connect.client.records.MealType
import androidx.health.connect.client.records.NutritionRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.RespiratoryRateRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.Vo2MaxRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.BloodGlucose
import androidx.health.connect.client.units.Energy
import androidx.health.connect.client.units.Length
import androidx.health.connect.client.units.Mass
import androidx.health.connect.client.units.Percentage
import androidx.health.connect.client.units.Temperature
import androidx.health.connect.client.units.Volume
import com.ayuvo.health.data.health.HealthRollupMath
import com.ayuvo.health.data.health.HealthSampleRow
import com.ayuvo.health.data.health.HealthSleepCodes
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import java.time.Instant
import java.time.ZoneOffset
import kotlin.math.roundToLong
import kotlin.reflect.KClass

/**
 * Origin-3 row → Health Connect record for the write-back (docs/google-health.md §4). Every record
 * carries `clientRecordId = "ayuvo_gh_<point id>"` and `clientRecordVersion = updated_ms`, so a
 * rewrite is an idempotent upsert, and HealthRecordMapper skips it on the way back in.
 * Pure over the connect-client data classes (unit-tested on the JVM like HealthRecordMapper).
 */
object GoogleHealthRecordFactory {

    /** Registry slug → record class written for it; null for types with no Health Connect target. */
    fun recordClass(typeId: String): KClass<out Record>? = when (typeId) {
        "steps" -> StepsRecord::class
        "distance" -> DistanceRecord::class
        "floors_climbed" -> FloorsClimbedRecord::class
        "elevation_gained" -> ElevationGainedRecord::class
        "active_energy" -> ActiveCaloriesBurnedRecord::class
        "total_energy" -> TotalCaloriesBurnedRecord::class
        "heart_rate" -> HeartRateRecord::class
        "resting_heart_rate" -> RestingHeartRateRecord::class
        "hrv_rmssd" -> HeartRateVariabilityRmssdRecord::class
        "blood_oxygen" -> OxygenSaturationRecord::class
        "weight" -> WeightRecord::class
        "height" -> HeightRecord::class
        "body_fat" -> BodyFatRecord::class
        "blood_glucose" -> BloodGlucoseRecord::class
        "body_temperature" -> BodyTemperatureRecord::class
        "vo2_max" -> Vo2MaxRecord::class
        "respiratory_rate" -> RespiratoryRateRecord::class
        "sleep" -> SleepSessionRecord::class
        "workout" -> ExerciseSessionRecord::class
        "nutrition" -> NutritionRecord::class
        "hydration" -> HydrationRecord::class
        else -> null
    }

    /**
     * Builds the record, or throws IllegalArgumentException when the value is outside the range
     * Health Connect accepts (the entry is then marked `error`, never retried).
     */
    fun build(row: HealthSampleRow, stageRows: List<HealthSampleRow> = emptyList()): Record? {
        val metadata = metadata(row)
        val start = Instant.ofEpochMilli(row.startMs)
        val startOffset = row.startOffsetS?.let(ZoneOffset::ofTotalSeconds)
        val endOffset = (row.endOffsetS ?: row.startOffsetS)?.let(ZoneOffset::ofTotalSeconds)
        // Interval records need end > start; point-in-time Google samples get Health Connect's usual minute.
        val end = Instant.ofEpochMilli(if (row.endMs > row.startMs) row.endMs else row.startMs + 60_000L)
        val v = row.value
        return when (row.typeId) {
            "steps" -> StepsRecord(start, startOffset, end, endOffset, count = v.required().roundToLong(), metadata = metadata)
            "distance" -> DistanceRecord(start, startOffset, end, endOffset, distance = Length.meters(v.required()), metadata = metadata)
            "floors_climbed" -> FloorsClimbedRecord(start, startOffset, end, endOffset, floors = v.required(), metadata = metadata)
            "elevation_gained" -> ElevationGainedRecord(start, startOffset, end, endOffset, elevation = Length.meters(v.required()), metadata = metadata)
            "active_energy" -> ActiveCaloriesBurnedRecord(start, startOffset, end, endOffset, energy = Energy.kilocalories(v.required()), metadata = metadata)
            "total_energy" -> TotalCaloriesBurnedRecord(start, startOffset, end, endOffset, energy = Energy.kilocalories(v.required()), metadata = metadata)
            "heart_rate" -> HeartRateRecord(
                startTime = start, startZoneOffset = startOffset,
                endTime = Instant.ofEpochMilli(maxOf(row.endMs, row.startMs)), endZoneOffset = endOffset,
                samples = listOf(HeartRateRecord.Sample(start, v.required().roundToLong())),
                metadata = metadata
            )
            "resting_heart_rate" -> RestingHeartRateRecord(start, startOffset, beatsPerMinute = v.required().roundToLong(), metadata = metadata)
            "hrv_rmssd" -> HeartRateVariabilityRmssdRecord(start, startOffset, heartRateVariabilityMillis = v.required(), metadata = metadata)
            "blood_oxygen" -> OxygenSaturationRecord(start, startOffset, percentage = Percentage(v.required()), metadata = metadata)
            "weight" -> WeightRecord(start, startOffset, weight = Mass.kilograms(v.required()), metadata = metadata)
            "height" -> HeightRecord(start, startOffset, height = Length.meters(v.required()), metadata = metadata)
            "body_fat" -> BodyFatRecord(start, startOffset, percentage = Percentage(v.required()), metadata = metadata)
            "blood_glucose" -> {
                val extra = extra(row)
                BloodGlucoseRecord(
                    time = start, zoneOffset = startOffset, metadata = metadata,
                    level = BloodGlucose.millimolesPerLiter(v.required()),
                    specimenSource = row.categoryValue?.takeIf { it in 0..6 } ?: BloodGlucoseRecord.SPECIMEN_SOURCE_UNKNOWN,
                    mealType = mealType(extra?.str("meal_type")),
                    relationToMeal = relationToMeal(extra?.str("measurement_timing"))
                )
            }
            "body_temperature" -> BodyTemperatureRecord(
                time = start, zoneOffset = startOffset, metadata = metadata,
                temperature = Temperature.celsius(v.required())
            )
            "vo2_max" -> Vo2MaxRecord(time = start, zoneOffset = startOffset, metadata = metadata, vo2MillilitersPerMinuteKilogram = v.required())
            "respiratory_rate" -> RespiratoryRateRecord(start, startOffset, rate = v.required(), metadata = metadata)
            "sleep" -> {
                // Stages must be sorted, inside the session and non-overlapping: drop the ones that are not.
                val stages = stageRows.sortedBy { it.startMs }
                    .filter { it.endMs > it.startMs && it.startMs >= row.startMs && it.endMs <= row.endMs }
                    .fold(mutableListOf<SleepSessionRecord.Stage>()) { acc, s ->
                        if (acc.isEmpty() || !Instant.ofEpochMilli(s.startMs).isBefore(acc.last().endTime)) {
                            acc += SleepSessionRecord.Stage(Instant.ofEpochMilli(s.startMs), Instant.ofEpochMilli(s.endMs), sleepStage(s.categoryValue))
                        }
                        acc
                    }
                SleepSessionRecord(
                    startTime = start, startZoneOffset = startOffset,
                    endTime = Instant.ofEpochMilli(row.endMs), endZoneOffset = endOffset,
                    metadata = metadata, title = null, notes = null, stages = stages
                )
            }
            "workout" -> ExerciseSessionRecord(
                startTime = start, startZoneOffset = startOffset,
                endTime = Instant.ofEpochMilli(row.endMs), endZoneOffset = endOffset,
                metadata = metadata,
                exerciseType = exerciseType(row.title),
                title = row.title?.let(::humanise)
            )
            "nutrition" -> {
                val extra = extra(row)
                fun g(slug: String) = extra?.num(slug)?.let { Mass.grams(it) }
                fun mg(slug: String) = extra?.num(slug)?.let { Mass.milligrams(it) }
                fun mcg(slug: String) = extra?.num(slug)?.let { Mass.micrograms(it) }
                NutritionRecord(
                    startTime = start, startZoneOffset = startOffset, endTime = end, endZoneOffset = endOffset,
                    metadata = metadata,
                    name = row.title,
                    mealType = row.categoryValue?.takeIf { it in 1..4 } ?: MealType.MEAL_TYPE_UNKNOWN,
                    energy = v?.let { Energy.kilocalories(it) },
                    protein = g("dietary_protein"),
                    totalCarbohydrate = g("dietary_carbohydrates"),
                    totalFat = g("dietary_fat_total"),
                    saturatedFat = g("dietary_fat_saturated"),
                    monounsaturatedFat = g("dietary_fat_monounsaturated"),
                    polyunsaturatedFat = g("dietary_fat_polyunsaturated"),
                    dietaryFiber = g("dietary_fiber"),
                    sugar = g("dietary_sugar"),
                    cholesterol = mg("dietary_cholesterol"),
                    sodium = mg("dietary_sodium"),
                    potassium = mg("dietary_potassium"),
                    calcium = mg("dietary_calcium"),
                    iron = mg("dietary_iron"),
                    magnesium = mg("dietary_magnesium"),
                    zinc = mg("dietary_zinc"),
                    phosphorus = mg("dietary_phosphorus"),
                    caffeine = mg("dietary_caffeine"),
                    vitaminA = mcg("dietary_vitamin_a"),
                    vitaminB6 = mg("dietary_vitamin_b6"),
                    vitaminB12 = mcg("dietary_vitamin_b12"),
                    vitaminC = mg("dietary_vitamin_c"),
                    vitaminD = mcg("dietary_vitamin_d"),
                    vitaminE = mg("dietary_vitamin_e"),
                    vitaminK = mcg("dietary_vitamin_k"),
                    thiamin = mg("dietary_thiamin"),
                    riboflavin = mg("dietary_riboflavin"),
                    niacin = mg("dietary_niacin"),
                    folate = mcg("dietary_folate"),
                    biotin = mcg("dietary_biotin"),
                    pantothenicAcid = mg("dietary_pantothenic_acid")
                )
            }
            "hydration" -> HydrationRecord(start, startOffset, end, endOffset, volume = Volume.milliliters(v.required()), metadata = metadata)
            else -> null
        }
    }

    fun clientRecordId(row: HealthSampleRow): String =
        row.clientRecordId ?: GoogleHealthMapper.clientRecordId(row.id.removePrefix(GoogleHealthMapper.ID_PREFIX))

    private fun metadata(row: HealthSampleRow): Metadata {
        val id = clientRecordId(row)
        val version = row.updatedMs
        val device = Device(type = Device.TYPE_UNKNOWN, model = row.device)
        return when (row.recordingMethod) {
            1 -> Metadata.activelyRecorded(device, id, version)
            2 -> Metadata.autoRecorded(device, id, version)
            else -> Metadata.unknownRecordingMethod(id, version, device.takeIf { row.device != null })
        }
    }

    private fun Double?.required(): Double = this?.takeIf { it.isFinite() } ?: throw IllegalArgumentException("missing value")

    private fun extra(row: HealthSampleRow): JsonObject? = HealthRollupMath.parseExtra(row.extraJson)

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.num(key: String): Double? = (this[key] as? JsonPrimitive)?.doubleOrNull

    /** Canonical sleep code (registry `category_codes`) → Health Connect stage. */
    fun sleepStage(code: Int?): Int = when (code) {
        HealthSleepCodes.AWAKE -> SleepSessionRecord.STAGE_TYPE_AWAKE
        HealthSleepCodes.LIGHT -> SleepSessionRecord.STAGE_TYPE_LIGHT
        HealthSleepCodes.DEEP -> SleepSessionRecord.STAGE_TYPE_DEEP
        HealthSleepCodes.REM -> SleepSessionRecord.STAGE_TYPE_REM
        HealthSleepCodes.OUT_OF_BED -> SleepSessionRecord.STAGE_TYPE_OUT_OF_BED
        else -> SleepSessionRecord.STAGE_TYPE_SLEEPING
    }

    private fun mealType(name: String?): Int = when (name) {
        "BREAKFAST" -> MealType.MEAL_TYPE_BREAKFAST
        "LUNCH" -> MealType.MEAL_TYPE_LUNCH
        "DINNER" -> MealType.MEAL_TYPE_DINNER
        "SNACK" -> MealType.MEAL_TYPE_SNACK
        else -> MealType.MEAL_TYPE_UNKNOWN
    }

    private fun relationToMeal(name: String?): Int = when (name) {
        "BEFORE_MEAL" -> BloodGlucoseRecord.RELATION_TO_MEAL_BEFORE_MEAL
        "AFTER_MEAL" -> BloodGlucoseRecord.RELATION_TO_MEAL_AFTER_MEAL
        "FASTING" -> BloodGlucoseRecord.RELATION_TO_MEAL_FASTING
        "GENERAL" -> BloodGlucoseRecord.RELATION_TO_MEAL_GENERAL
        else -> BloodGlucoseRecord.RELATION_TO_MEAL_UNKNOWN
    }

    private fun humanise(name: String): String =
        name.lowercase().split('_').filter { it.isNotBlank() }.joinToString(" ") { it.replaceFirstChar(Char::uppercaseChar) }

    /** Google `exerciseType` name → `ExerciseSessionRecord.EXERCISE_TYPE_*` (unknown names: other workout). */
    fun exerciseType(name: String?): Int = when (name?.uppercase()) {
        "RUNNING", "RUN" -> ExerciseSessionRecord.EXERCISE_TYPE_RUNNING
        "TREADMILL", "TREADMILL_RUNNING", "RUNNING_TREADMILL" -> ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL
        "WALKING", "WALK" -> ExerciseSessionRecord.EXERCISE_TYPE_WALKING
        "HIKING", "HIKE" -> ExerciseSessionRecord.EXERCISE_TYPE_HIKING
        "BIKING", "CYCLING", "OUTDOOR_BIKE", "BIKE" -> ExerciseSessionRecord.EXERCISE_TYPE_BIKING
        "STATIONARY_BIKE", "SPINNING", "INDOOR_CYCLING", "BIKING_STATIONARY" -> ExerciseSessionRecord.EXERCISE_TYPE_BIKING_STATIONARY
        "SWIMMING", "POOL_SWIMMING", "SWIMMING_POOL", "SWIM" -> ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_POOL
        "OPEN_WATER_SWIMMING", "SWIMMING_OPEN_WATER" -> ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_OPEN_WATER
        "ELLIPTICAL" -> ExerciseSessionRecord.EXERCISE_TYPE_ELLIPTICAL
        "ROWING" -> ExerciseSessionRecord.EXERCISE_TYPE_ROWING
        "ROWING_MACHINE", "INDOOR_ROWING" -> ExerciseSessionRecord.EXERCISE_TYPE_ROWING_MACHINE
        "STRENGTH_TRAINING", "WEIGHTS", "WEIGHT_TRAINING" -> ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING
        "WEIGHTLIFTING" -> ExerciseSessionRecord.EXERCISE_TYPE_WEIGHTLIFTING
        "HIIT", "HIGH_INTENSITY_INTERVAL_TRAINING", "INTERVAL_WORKOUT" -> ExerciseSessionRecord.EXERCISE_TYPE_HIGH_INTENSITY_INTERVAL_TRAINING
        "YOGA" -> ExerciseSessionRecord.EXERCISE_TYPE_YOGA
        "PILATES" -> ExerciseSessionRecord.EXERCISE_TYPE_PILATES
        "DANCING", "DANCE" -> ExerciseSessionRecord.EXERCISE_TYPE_DANCING
        "BOXING", "KICKBOXING" -> ExerciseSessionRecord.EXERCISE_TYPE_BOXING
        "MARTIAL_ARTS" -> ExerciseSessionRecord.EXERCISE_TYPE_MARTIAL_ARTS
        "STRETCHING" -> ExerciseSessionRecord.EXERCISE_TYPE_STRETCHING
        "CALISTHENICS", "BODYWEIGHT" -> ExerciseSessionRecord.EXERCISE_TYPE_CALISTHENICS
        "BOOT_CAMP", "BOOTCAMP", "CIRCUIT_TRAINING" -> ExerciseSessionRecord.EXERCISE_TYPE_BOOT_CAMP
        "STAIR_CLIMBING", "STAIRS" -> ExerciseSessionRecord.EXERCISE_TYPE_STAIR_CLIMBING
        "STAIR_CLIMBER", "STAIR_CLIMBING_MACHINE" -> ExerciseSessionRecord.EXERCISE_TYPE_STAIR_CLIMBING_MACHINE
        "TENNIS" -> ExerciseSessionRecord.EXERCISE_TYPE_TENNIS
        "TABLE_TENNIS" -> ExerciseSessionRecord.EXERCISE_TYPE_TABLE_TENNIS
        "BADMINTON" -> ExerciseSessionRecord.EXERCISE_TYPE_BADMINTON
        "SQUASH" -> ExerciseSessionRecord.EXERCISE_TYPE_SQUASH
        "GOLF" -> ExerciseSessionRecord.EXERCISE_TYPE_GOLF
        "SOCCER", "FOOTBALL" -> ExerciseSessionRecord.EXERCISE_TYPE_SOCCER
        "BASKETBALL" -> ExerciseSessionRecord.EXERCISE_TYPE_BASKETBALL
        "VOLLEYBALL" -> ExerciseSessionRecord.EXERCISE_TYPE_VOLLEYBALL
        "BASEBALL" -> ExerciseSessionRecord.EXERCISE_TYPE_BASEBALL
        "CRICKET" -> ExerciseSessionRecord.EXERCISE_TYPE_CRICKET
        "SKIING", "CROSS_COUNTRY_SKIING" -> ExerciseSessionRecord.EXERCISE_TYPE_SKIING
        "SNOWBOARDING" -> ExerciseSessionRecord.EXERCISE_TYPE_SNOWBOARDING
        "SKATING", "ICE_SKATING" -> ExerciseSessionRecord.EXERCISE_TYPE_SKATING
        "ROCK_CLIMBING", "CLIMBING" -> ExerciseSessionRecord.EXERCISE_TYPE_ROCK_CLIMBING
        "PADDLING", "KAYAKING", "CANOEING" -> ExerciseSessionRecord.EXERCISE_TYPE_PADDLING
        "SURFING" -> ExerciseSessionRecord.EXERCISE_TYPE_SURFING
        "WHEELCHAIR" -> ExerciseSessionRecord.EXERCISE_TYPE_WHEELCHAIR
        "GUIDED_BREATHING", "BREATHING" -> ExerciseSessionRecord.EXERCISE_TYPE_GUIDED_BREATHING
        else -> ExerciseSessionRecord.EXERCISE_TYPE_OTHER_WORKOUT
    }
}
