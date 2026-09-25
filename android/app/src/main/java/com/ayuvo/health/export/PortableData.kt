package com.ayuvo.health.export

import com.ayuvo.health.models.ActivityLevel
import com.ayuvo.health.models.AutoBalanceMacro
import com.ayuvo.health.models.BodyFatEntry
import com.ayuvo.health.models.BodyMeasurement
import com.ayuvo.health.models.CompletedExercise
import com.ayuvo.health.models.CompletedSet
import com.ayuvo.health.models.FastingDefaults
import com.ayuvo.health.models.FastingSession
import com.ayuvo.health.models.Gender
import com.ayuvo.health.models.MealSchedule
import com.ayuvo.health.models.OptionalNutrient
import com.ayuvo.health.models.PlannedExercise
import com.ayuvo.health.models.PlannedSet
import com.ayuvo.health.models.SupplementalNutrient
import com.ayuvo.health.models.UserProfile
import com.ayuvo.health.models.WeightEntry
import com.ayuvo.health.models.WeightGoal
import com.ayuvo.health.models.WorkoutDate
import com.ayuvo.health.models.WorkoutIntensity
import com.ayuvo.health.models.WorkoutIssue
import com.ayuvo.health.models.WorkoutPreferences
import com.ayuvo.health.models.WorkoutRpeScale
import com.ayuvo.health.models.WorkoutSession
import com.ayuvo.health.models.WorkoutSplit
import com.ayuvo.health.models.WorkoutStrengthNumbers
import com.ayuvo.health.models.WorkoutWeightUnit
import com.ayuvo.health.ui.theme.AppThemeColor
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlin.math.abs
import kotlin.math.floor

/** Why a portable file was turned away before anything was written. */
enum class PortableRefusal { NOT_JSON, WRONG_FORMAT, NEWER_VERSION }

sealed interface PortableParse {
    data class Ok(val document: PortableDocument) : PortableParse
    data class Refused(val reason: PortableRefusal) : PortableParse
}

/**
 * The portable-data section in Android's own types (docs/portable-data.md). Every part is optional:
 * a missing part is an empty list / null / all-null [PortableSettings] and leaves the device as it is.
 */
data class PortableDocument(
    val createdAt: Instant,
    val platform: String,
    val appVersion: String,
    val profile: UserProfile? = null,
    val settings: PortableSettings = PortableSettings(),
    val weights: List<WeightEntry> = emptyList(),
    val bodyFat: List<BodyFatEntry> = emptyList(),
    val bodyMeasurements: List<BodyMeasurement> = emptyList(),
    val fastingSessions: List<FastingSession> = emptyList(),
    val workouts: PortableWorkouts? = null
)

/**
 * Units, shared preferences and the water / fasting settings. A null value is "not in the file"
 * (import) or "not carried" (export); [optionalNutrientGoals] is empty for the same reason.
 */
data class PortableSettings(
    val heightUnit: String? = null,
    val weightUnit: String? = null,
    val waterUnit: String? = null,
    val glucoseUnit: String? = null,
    val weekStartsOnMonday: Boolean? = null,
    val dailyStepGoal: Int? = null,
    val appearanceMode: String? = null,
    val appThemeColor: String? = null,
    val adaptiveGoalsEnabled: Boolean? = null,
    val preferGramsByDefault: Boolean? = null,
    val mealSchedule: MealSchedule? = null,
    val summaryFavourites: List<String>? = null,
    val optionalNutrientGoals: Map<OptionalNutrient, Int> = emptyMap(),
    val waterTrackingEnabled: Boolean? = null,
    val waterDailyGoalMl: Int? = null,
    val fastingTrackingEnabled: Boolean? = null,
    val fastingDefaultGoalMinutes: Int? = null,
    val fastingGoalNotificationEnabled: Boolean? = null
) {
    /** How many settings values this holds (the manifest's `settings` count; the goals map counts once). */
    val count: Int
        get() = listOfNotNull(
            heightUnit, weightUnit, waterUnit, glucoseUnit, weekStartsOnMonday, dailyStepGoal, appearanceMode,
            appThemeColor, adaptiveGoalsEnabled, preferGramsByDefault, mealSchedule, summaryFavourites,
            waterTrackingEnabled, waterDailyGoalMl, fastingTrackingEnabled, fastingDefaultGoalMinutes,
            fastingGoalNotificationEnabled
        ).size + if (optionalNutrientGoals.isEmpty()) 0 else 1

    companion object {
        /** What a fresh install exposes; a snapshot equal to this has no non-default setting to carry. */
        val Defaults = PortableSettings(
            heightUnit = "cm",
            weightUnit = "kg",
            waterUnit = "ml",
            weekStartsOnMonday = true,
            dailyStepGoal = 10_000,
            appearanceMode = "system",
            appThemeColor = AppThemeColor.DEFAULT_KEY,
            adaptiveGoalsEnabled = true,
            preferGramsByDefault = false,
            mealSchedule = MealSchedule.Default,
            waterTrackingEnabled = false,
            waterDailyGoalMl = 2_000,
            fastingTrackingEnabled = false,
            fastingDefaultGoalMinutes = FastingDefaults.GOAL_MINUTES,
            fastingGoalNotificationEnabled = true
        )
    }
}

/** What the workout diary carries in both directions; Health Connect bookkeeping never appears. */
data class PortableWorkouts(
    val preferences: WorkoutPreferences? = null,
    val savedExerciseIds: Set<String> = emptySet(),
    val userExercises: List<PlannedExercise> = emptyList(),
    val customActivities: List<PlannedExercise> = emptyList(),
    val dayPlans: Map<String, List<PlannedExercise>> = emptyMap(),
    val sessions: List<WorkoutSession> = emptyList()
) {
    val isEmpty: Boolean
        get() = savedExerciseIds.isEmpty() && userExercises.isEmpty() && customActivities.isEmpty() &&
            dayPlans.isEmpty() && sessions.isEmpty()
}

/**
 * Reads and writes `ayuvo-portable-data` JSON. Parsing works on the JSON tree field by field so one
 * bad value (an unknown enum, a percentage where a fraction belongs) drops just that value or row,
 * never the file; only a wrong `format`, a newer `format_version` or non-JSON refuses it.
 */
@OptIn(ExperimentalSerializationApi::class)
object PortableFormat {
    const val FORMAT = "ayuvo-portable-data"
    const val FORMAT_VERSION = 1
    const val FILE_NAME = "ayuvo-portable-data.json"
    const val ENTRY_PATH = "portable-data/$FILE_NAME"
    const val PLATFORM = "android"
    const val MAX_FILE_BYTES = 32L * 1024 * 1024
    const val MAX_FAVOURITES = 12

    private val printer = Json {
        prettyPrint = true
        prettyPrintIndent = "  "
        encodeDefaults = true
        explicitNulls = false
    }

    private val instantFormat = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)

    /** `2026-09-25T10:30:00.000Z`. */
    fun formatInstant(instant: Instant): String = instantFormat.format(instant)

    /** Accepts the exported form, an offset (`+05:30`) and a missing fraction. */
    fun parseInstant(text: String?): Instant? {
        val value = text?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return runCatching { OffsetDateTime.parse(value).toInstant() }.getOrNull()
            ?: runCatching { Instant.parse(value) }.getOrNull()
    }

    // -- Enum spellings ---------------------------------------------------------------------------
    // Android stores the workout enums by constant name; the file uses lower snake case. Written out
    // (not derived from `name`) so renaming a constant can't silently change the file format.

    fun splitWire(split: WorkoutSplit): String = when (split) {
        WorkoutSplit.PUSH_PULL_LEGS -> "push_pull_legs"
        WorkoutSplit.UPPER_LOWER -> "upper_lower"
        WorkoutSplit.BODY_PART -> "body_part"
        WorkoutSplit.ARNOLD -> "arnold"
        WorkoutSplit.PUSH_PULL -> "push_pull"
        WorkoutSplit.ANTAGONIST -> "antagonist"
        WorkoutSplit.HYBRID -> "hybrid"
        WorkoutSplit.FULL_BODY -> "full_body"
        WorkoutSplit.CUSTOM -> "custom"
    }

    fun issueWire(issue: WorkoutIssue): String = when (issue) {
        WorkoutIssue.SHOULDER -> "shoulder"
        WorkoutIssue.ELBOW -> "elbow"
        WorkoutIssue.WRIST -> "wrist"
        WorkoutIssue.LOWER_BACK -> "lower_back"
        WorkoutIssue.HIP -> "hip"
        WorkoutIssue.KNEE -> "knee"
        WorkoutIssue.ANKLE -> "ankle"
        WorkoutIssue.OTHER -> "other"
    }

    fun rpeScaleWire(scale: WorkoutRpeScale): String = when (scale) {
        WorkoutRpeScale.STRENGTH -> "strength"
        WorkoutRpeScale.CR10 -> "cr10"
        WorkoutRpeScale.BORG -> "borg"
    }

    fun weightUnitWire(unit: WorkoutWeightUnit): String = when (unit) {
        WorkoutWeightUnit.KG -> "kg"
        WorkoutWeightUnit.LBS -> "lbs"
    }

    fun intensityWire(intensity: WorkoutIntensity): String = when (intensity) {
        WorkoutIntensity.LIGHT -> "light"
        WorkoutIntensity.MODERATE -> "moderate"
        WorkoutIntensity.VIGOROUS -> "vigorous"
    }

    /** `custom` has no Android counterpart; the store coerces it to full body too. */
    fun splitFromWire(text: String?): WorkoutSplit? =
        if (text?.trim().equals("custom", ignoreCase = true)) WorkoutSplit.FULL_BODY
        else fromWire(WorkoutSplit.entries, ::splitWire, text)

    fun issueFromWire(text: String?): WorkoutIssue? = fromWire(WorkoutIssue.entries, ::issueWire, text)
    fun rpeScaleFromWire(text: String?): WorkoutRpeScale? = fromWire(WorkoutRpeScale.entries, ::rpeScaleWire, text)
    fun weightUnitFromWire(text: String?): WorkoutWeightUnit? = fromWire(WorkoutWeightUnit.entries, ::weightUnitWire, text)
    fun intensityFromWire(text: String?): WorkoutIntensity? = fromWire(WorkoutIntensity.entries, ::intensityWire, text)

    private fun <E : Enum<E>> fromWire(entries: List<E>, wire: (E) -> String, text: String?): E? {
        val wanted = text?.trim() ?: return null
        return entries.firstOrNull { wire(it).equals(wanted, ignoreCase = true) }
    }

    /** Profile enums already carry the strings both platforms store (`male`, `veryActive`). */
    private fun <E : Enum<E>> profileWire(entry: E, serializer: KSerializer<E>): String =
        serializer.descriptor.getElementName(entry.ordinal)

    private fun <E : Enum<E>> profileFromWire(entries: List<E>, serializer: KSerializer<E>, text: String?): E? =
        fromWire(entries, { profileWire(it, serializer) }, text)

    private val nutrientKeys: Map<OptionalNutrient, String> = buildMap {
        put(OptionalNutrient.SUGAR, "sugar")
        put(OptionalNutrient.ADDED_SUGAR, "addedSugar")
        put(OptionalNutrient.FIBER, "fiber")
        put(OptionalNutrient.SATURATED_FAT, "saturatedFat")
        put(OptionalNutrient.CHOLESTEROL, "cholesterol")
        put(OptionalNutrient.CAFFEINE, "caffeine")
        put(OptionalNutrient.SODIUM, "sodium")
        put(OptionalNutrient.POTASSIUM, "potassium")
        put(OptionalNutrient.TRANS_FAT, "transFat")
        put(OptionalNutrient.CALCIUM, "calcium")
        put(OptionalNutrient.IRON, "iron")
        put(OptionalNutrient.MAGNESIUM, "magnesium")
        put(OptionalNutrient.ZINC, "zinc")
        put(OptionalNutrient.VITAMIN_A, "vitaminA")
        put(OptionalNutrient.VITAMIN_C, "vitaminC")
        put(OptionalNutrient.VITAMIN_D, "vitaminD")
        put(OptionalNutrient.VITAMIN_B12, "vitaminB12")
        put(OptionalNutrient.VITAMIN_E, "vitaminE")
        put(OptionalNutrient.VITAMIN_K, "vitaminK")
        put(OptionalNutrient.FOLATE, "folate")
        put(OptionalNutrient.OMEGA3, "omega3")
        // The supplements live in a separate map natively; the file has one flat object.
        SupplementalNutrient.entries.forEach { put(it.optionalNutrient, it.storageKey) }
    }

    fun nutrientKey(nutrient: OptionalNutrient): String = nutrientKeys.getValue(nutrient)

    private val nutrientsByKey: Map<String, OptionalNutrient> = nutrientKeys.entries.associate { (n, key) -> key to n }

    // -- Writing ----------------------------------------------------------------------------------

    fun encode(document: PortableDocument, zone: ZoneId = ZoneId.systemDefault()): String =
        printer.encodeToString(JsonElement.serializer(), toJson(document, zone)) + "\n"

    fun toJson(document: PortableDocument, zone: ZoneId = ZoneId.systemDefault()): JsonObject = buildJsonObject {
        put("app", "Ayuvo")
        put("format", FORMAT)
        put("format_version", FORMAT_VERSION)
        put("created_at", formatInstant(document.createdAt))
        put("platform", document.platform)
        put("app_version", document.appVersion)
        document.profile?.let { put("profile", profileJson(it, zone)) }
        unitsJson(document.settings)?.let { put("units", it) }
        preferencesJson(document.settings)?.let { put("preferences", it) }
        waterJson(document.settings)?.let { put("water", it) }
        fastingJson(document.settings, document.fastingSessions)?.let { put("fasting", it) }
        if (document.weights.isNotEmpty()) put("weights", rows(document.weights) { weightJson(it) })
        if (document.bodyFat.isNotEmpty()) put("body_fat", rows(document.bodyFat) { bodyFatJson(it) })
        if (document.bodyMeasurements.isNotEmpty()) put("body_measurements", rows(document.bodyMeasurements) { measurementJson(it) })
        document.workouts?.takeUnless { it.isEmpty && it.preferences == null }?.let { put("workouts", workoutsJson(it)) }
    }

    private fun <T> rows(list: List<T>, build: (T) -> JsonObject): JsonArray = JsonArray(list.map(build))

    private fun strings(list: Collection<String>): JsonArray = JsonArray(list.map { JsonPrimitive(it) })

    private fun JsonObjectBuilder.opt(key: String, value: String?) { if (value != null) put(key, value) }
    private fun JsonObjectBuilder.opt(key: String, value: Double?) { if (value != null) put(key, value) }
    private fun JsonObjectBuilder.opt(key: String, value: Int?) { if (value != null) put(key, value) }
    private fun JsonObjectBuilder.opt(key: String, value: Boolean?) { if (value != null) put(key, value) }

    private fun profileJson(p: UserProfile, zone: ZoneId): JsonObject = buildJsonObject {
        opt("name", p.name)
        put("gender", profileWire(p.gender, Gender.serializer()))
        // The local calendar day, so a birthday doesn't shift when the file is read in another zone.
        put("birthday", p.birthday.atZone(zone).toLocalDate().toString())
        put("height_cm", p.heightCm)
        put("weight_kg", p.weightKg)
        put("activity_level", profileWire(p.activityLevel, ActivityLevel.serializer()))
        put("goal", profileWire(p.goal, WeightGoal.serializer()))
        opt("body_fat_fraction", p.bodyFatPercentage)
        opt("goal_body_fat_fraction", p.goalBodyFatPercentage)
        opt("weekly_change_kg", p.weeklyChangeKg)
        opt("goal_weight_kg", p.goalWeightKg)
        opt("custom_calories", p.customCalories)
        opt("custom_protein_g", p.customProtein)
        opt("custom_fat_g", p.customFat)
        opt("custom_carbs_g", p.customCarbs)
        opt("auto_balance_macro", p.autoBalanceMacro?.let { profileWire(it, AutoBalanceMacro.serializer()) })
        put("allergens", strings(p.allergenSensitivities))
        put("calories_locked", p.caloriesLocked)
        put("locked_macros", strings(p.lockedMacros.sortedBy { it.ordinal }.map { profileWire(it, AutoBalanceMacro.serializer()) }))
    }

    private fun unitsJson(s: PortableSettings): JsonObject? {
        val json = buildJsonObject {
            opt("height_unit", s.heightUnit)
            opt("weight_unit", s.weightUnit)
            opt("water_unit", s.waterUnit)
            opt("glucose_unit", s.glucoseUnit)
        }
        return json.takeIf { it.isNotEmpty() }
    }

    private fun preferencesJson(s: PortableSettings): JsonObject? {
        val json = buildJsonObject {
            opt("week_starts_on_monday", s.weekStartsOnMonday)
            opt("daily_step_goal", s.dailyStepGoal)
            opt("appearance_mode", s.appearanceMode)
            opt("app_theme_color", s.appThemeColor)
            opt("adaptive_goals_enabled", s.adaptiveGoalsEnabled)
            opt("food_measurement_prefer_grams", s.preferGramsByDefault)
            s.mealSchedule?.let {
                put("meal_start_minutes", buildJsonObject {
                    put("breakfast", it.breakfastStartMinutes)
                    put("lunch", it.lunchStartMinutes)
                    put("dinner", it.dinnerStartMinutes)
                    put("snack", it.snackStartMinutes)
                })
            }
            s.summaryFavourites?.let { put("summary_favourites", strings(it)) }
            if (s.optionalNutrientGoals.isNotEmpty()) {
                put("optional_nutrient_goals", buildJsonObject {
                    s.optionalNutrientGoals.entries.sortedBy { it.key.ordinal }.forEach { (nutrient, value) ->
                        put(nutrientKey(nutrient), value)
                    }
                })
            }
        }
        return json.takeIf { it.isNotEmpty() }
    }

    private fun waterJson(s: PortableSettings): JsonObject? {
        val settings = buildJsonObject {
            opt("tracking_enabled", s.waterTrackingEnabled)
            opt("daily_goal_ml", s.waterDailyGoalMl)
        }
        return if (settings.isEmpty()) null else buildJsonObject { put("settings", settings) }
    }

    private fun fastingJson(s: PortableSettings, sessions: List<FastingSession>): JsonObject? {
        val settings = buildJsonObject {
            opt("tracking_enabled", s.fastingTrackingEnabled)
            opt("default_goal_minutes", s.fastingDefaultGoalMinutes)
            opt("goal_notification_enabled", s.fastingGoalNotificationEnabled)
        }
        if (settings.isEmpty() && sessions.isEmpty()) return null
        return buildJsonObject {
            if (settings.isNotEmpty()) put("settings", settings)
            if (sessions.isNotEmpty()) {
                put("sessions", rows(sessions) {
                    buildJsonObject {
                        put("id", it.id.toString())
                        put("started_at", formatInstant(it.startedAt))
                        it.endedAt?.let { end -> put("ended_at", formatInstant(end)) }
                        put("goal_minutes", it.goalMinutes)
                    }
                })
            }
        }
    }

    private fun weightJson(e: WeightEntry) = buildJsonObject {
        put("id", e.id.toString())
        put("date", formatInstant(e.date))
        put("weight_kg", e.weightKg)
    }

    private fun bodyFatJson(e: BodyFatEntry) = buildJsonObject {
        put("id", e.id.toString())
        put("date", formatInstant(e.date))
        put("fraction", e.bodyFatFraction)
    }

    private fun measurementJson(e: BodyMeasurement) = buildJsonObject {
        put("id", e.id.toString())
        put("date", formatInstant(e.date))
        opt("neck_cm", e.neckCm)
        opt("waist_cm", e.waistCm)
        opt("hips_cm", e.hipsCm)
        opt("chest_cm", e.chestCm)
        opt("upper_arm_cm", e.upperArmCm)
        opt("thigh_cm", e.thighCm)
        opt("calf_cm", e.calfCm)
        opt("wrist_cm", e.wristCm)
    }

    private fun workoutsJson(w: PortableWorkouts): JsonObject = buildJsonObject {
        w.preferences?.let { put("preferences", workoutPreferencesJson(it)) }
        put("saved_exercise_ids", strings(w.savedExerciseIds.sorted()))
        put("user_exercises", rows(w.userExercises) { exerciseJson(it) })
        put("custom_activities", rows(w.customActivities) { exerciseJson(it) })
        put("day_plans", buildJsonObject {
            w.dayPlans.toSortedMap().forEach { (day, exercises) ->
                if (exercises.isNotEmpty()) put(day, rows(exercises) { exerciseJson(it) })
            }
        })
        put("sessions", rows(w.sessions) { sessionJson(it) })
    }

    private fun workoutPreferencesJson(p: WorkoutPreferences): JsonObject = buildJsonObject {
        put("target_muscles", strings(p.targetMuscles.sorted()))
        put("issues", strings(p.issues.sortedBy { it.ordinal }.map(::issueWire)))
        put("additional_issues", p.additionalIssues)
        put("frequency_days", p.frequencyDays)
        put("duration_minutes", p.durationMinutes)
        put("split", splitWire(p.split))
        put("custom_split", p.customSplit)
        put("equipment", strings(p.equipment.sorted()))
        put("rpe_scale", rpeScaleWire(p.rpeScale))
        put("strength", buildJsonObject {
            opt("bench_press_kg", p.strength.benchPressKg)
            opt("squat_kg", p.strength.squatKg)
            opt("deadlift_kg", p.strength.deadliftKg)
            opt("overhead_press_kg", p.strength.overheadPressKg)
        })
    }

    private fun exerciseJson(e: PlannedExercise): JsonObject = buildJsonObject {
        put("id", e.id.toString())
        put("item_id", e.itemId)
        put("name", e.name)
        put("body_part", e.bodyPart)
        put("equipment", e.equipment)
        put("primary_muscles", strings(e.primaryMuscles))
        put("secondary_muscles", strings(e.secondaryMuscles))
        put("instructions", strings(e.instructions))
        opt("image_url", e.imageUrl)
        opt("gif_url", e.gifUrl)
        put("sets", rows(e.sets) { set ->
            buildJsonObject {
                put("id", set.id.toString())
                put("weight", set.weight)
                opt("weight_unit", set.weightUnit?.let(::weightUnitWire))
                put("reps", set.reps)
                put("rpe", set.rpe)
                opt("rpe_scale", set.rpeScale?.let(::rpeScaleWire))
            }
        })
    }

    private fun sessionJson(s: WorkoutSession): JsonObject = buildJsonObject {
        put("id", s.id.toString())
        put("diary_date_key", s.diaryDateKey)
        put("started_at", formatInstant(s.startedAt))
        put("completed_at", formatInstant(s.completedAt))
        put("duration_seconds", s.durationSeconds)
        opt("calories_burned", s.caloriesBurned)
        put("exercises", rows(s.exercises) { ex ->
            buildJsonObject {
                put("id", ex.id.toString())
                put("item_id", ex.itemId)
                put("name", ex.name)
                put("target_muscles", strings(ex.targetMuscles))
                put("equipment", ex.equipment)
                opt("duration_seconds", ex.durationSeconds)
                opt("intensity", ex.intensity?.let(::intensityWire))
                put("sets", rows(ex.sets) { set ->
                    buildJsonObject {
                        put("id", set.id.toString())
                        put("set_number", set.setNumber)
                        put("weight", set.weight)
                        put("weight_unit", weightUnitWire(set.weightUnit))
                        put("reps", set.reps)
                        put("rpe", set.rpe)
                        opt("rpe_scale", set.rpeScale?.let(::rpeScaleWire))
                    }
                })
            }
        })
    }

    // -- Reading ----------------------------------------------------------------------------------

    fun parse(text: String, zone: ZoneId = ZoneId.systemDefault()): PortableParse {
        val root = try {
            Json.parseToJsonElement(text)
        } catch (_: IllegalArgumentException) {
            return PortableParse.Refused(PortableRefusal.NOT_JSON)
        }
        val o = root as? JsonObject ?: return PortableParse.Refused(PortableRefusal.NOT_JSON)
        if (o.str("format") != FORMAT) return PortableParse.Refused(PortableRefusal.WRONG_FORMAT)
        val version = o.int("format_version") ?: return PortableParse.Refused(PortableRefusal.WRONG_FORMAT)
        if (version > FORMAT_VERSION) return PortableParse.Refused(PortableRefusal.NEWER_VERSION)
        if (version < 1) return PortableParse.Refused(PortableRefusal.WRONG_FORMAT)

        return PortableParse.Ok(
            PortableDocument(
                createdAt = parseInstant(o.str("created_at")) ?: Instant.EPOCH,
                platform = o.str("platform").orEmpty(),
                appVersion = o.str("app_version").orEmpty(),
                profile = o.obj("profile")?.let { profileFrom(it, zone) },
                settings = settingsFrom(o),
                weights = o.rows("weights").mapNotNull(::weightFrom),
                bodyFat = o.rows("body_fat").mapNotNull(::bodyFatFrom),
                bodyMeasurements = o.rows("body_measurements").mapNotNull(::measurementFrom),
                fastingSessions = o.obj("fasting")?.rows("sessions").orEmpty().mapNotNull(::fastingFrom),
                workouts = o.obj("workouts")?.let(::workoutsFrom)
            )
        )
    }

    private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

    private fun JsonObject.rows(key: String): List<JsonObject> =
        (this[key] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()

    private fun JsonElement?.primitive(): JsonPrimitive? = (this as? JsonPrimitive)?.takeUnless { it is JsonNull }

    /** A JSON string only; a number where text belongs is not text. */
    private fun JsonObject.str(key: String): String? = this[key].primitive()?.takeIf { it.isString }?.content

    /** Text stored as a string by both apps (weights, reps, RPE); a bare number is read as its text. */
    private fun JsonObject.text(key: String): String? = this[key].primitive()?.content

    private fun JsonObject.bool(key: String): Boolean? = this[key].primitive()?.takeIf { !it.isString }?.booleanOrNull

    private fun JsonObject.num(key: String): Double? = this[key].primitive()?.doubleOrNull?.takeIf { it.isFinite() }

    private fun JsonObject.int(key: String): Int? =
        num(key)?.takeIf { it == floor(it) && abs(it) <= Int.MAX_VALUE }?.toInt()

    private fun JsonObject.strings(key: String): List<String>? =
        (this[key] as? JsonArray)?.mapNotNull { it.primitive()?.takeIf { p -> p.isString }?.content }

    private fun uuid(text: String?): UUID? = text?.trim()?.let { runCatching { UUID.fromString(it) }.getOrNull() }

    private fun localDate(text: String?): LocalDate? = text?.trim()?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

    private fun profileFrom(o: JsonObject, zone: ZoneId): UserProfile? {
        // A profile is only replaced by a complete one: guessing a birthday or height would skew BMR.
        val gender = profileFromWire(Gender.entries, Gender.serializer(), o.str("gender")) ?: return null
        val birthday = birthdayFrom(o.str("birthday"), zone) ?: return null
        val height = o.num("height_cm")?.takeIf { it in 30.0..300.0 } ?: return null
        val weight = o.num("weight_kg")?.takeIf { it in 10.0..700.0 } ?: return null
        val activity = profileFromWire(ActivityLevel.entries, ActivityLevel.serializer(), o.str("activity_level")) ?: return null
        val goal = profileFromWire(WeightGoal.entries, WeightGoal.serializer(), o.str("goal")) ?: return null
        fun fraction(key: String) = o.num(key)?.takeIf { it > 0.0 && it < 1.0 }
        fun calories(key: String) = o.int(key)?.takeIf { it in 0..20_000 }
        fun grams(key: String) = o.int(key)?.takeIf { it in 0..5_000 }
        val locked = o.strings("locked_macros").orEmpty()
            .mapNotNull { profileFromWire(AutoBalanceMacro.entries, AutoBalanceMacro.serializer(), it) }
            .distinct().take(2).toSet()
        return UserProfile(
            name = o.str("name")?.trim()?.takeIf { it.isNotEmpty() },
            gender = gender,
            birthday = birthday,
            heightCm = height,
            weightKg = weight,
            activityLevel = activity,
            goal = goal,
            bodyFatPercentage = fraction("body_fat_fraction"),
            goalBodyFatPercentage = fraction("goal_body_fat_fraction"),
            weeklyChangeKg = o.num("weekly_change_kg")?.takeIf { it in 0.0..5.0 },
            goalWeightKg = o.num("goal_weight_kg")?.takeIf { it in 10.0..700.0 },
            customCalories = calories("custom_calories"),
            customProtein = grams("custom_protein_g"),
            customFat = grams("custom_fat_g"),
            customCarbs = grams("custom_carbs_g"),
            autoBalanceMacro = profileFromWire(AutoBalanceMacro.entries, AutoBalanceMacro.serializer(), o.str("auto_balance_macro")),
            allergenSensitivities = o.strings("allergens").orEmpty().map { it.trim() }.filter { it.isNotEmpty() }.distinct(),
            caloriesLocked = o.bool("calories_locked") ?: false,
            lockedMacros = locked
        )
    }

    private fun birthdayFrom(text: String?, zone: ZoneId): java.time.Instant? {
        // Readers build start-of-day in their own zone; a full timestamp (older drafts) is reduced to its day.
        val day = localDate(text) ?: parseInstant(text)?.atZone(zone)?.toLocalDate() ?: return null
        if (day.year < 1900 || day.isAfter(LocalDate.now(zone))) return null
        return day.atStartOfDay(zone).toInstant()
    }

    private fun canonical(allowed: List<String>, text: String?): String? =
        text?.trim()?.let { wanted -> allowed.firstOrNull { it.equals(wanted, ignoreCase = true) } }

    private fun settingsFrom(o: JsonObject): PortableSettings {
        val units = o.obj("units")
        val prefs = o.obj("preferences")
        val water = o.obj("water")?.obj("settings")
        val fasting = o.obj("fasting")?.obj("settings")
        return PortableSettings(
            heightUnit = canonical(listOf("cm", "ftin"), units?.str("height_unit")),
            weightUnit = canonical(listOf("kg", "lbs"), units?.str("weight_unit")),
            waterUnit = canonical(listOf("ml", "floz"), units?.str("water_unit")),
            glucoseUnit = canonical(listOf("mmol/L", "mg/dL"), units?.str("glucose_unit")),
            weekStartsOnMonday = prefs?.bool("week_starts_on_monday"),
            dailyStepGoal = prefs?.int("daily_step_goal")?.takeIf { it in 1_000..50_000 },
            appearanceMode = canonical(listOf("system", "light", "dark"), prefs?.str("appearance_mode")),
            appThemeColor = canonical(AppThemeColor.entries.map { it.key }, prefs?.str("app_theme_color")),
            adaptiveGoalsEnabled = prefs?.bool("adaptive_goals_enabled"),
            preferGramsByDefault = prefs?.bool("food_measurement_prefer_grams"),
            mealSchedule = prefs?.obj("meal_start_minutes")?.let(::mealScheduleFrom),
            summaryFavourites = prefs?.strings("summary_favourites")
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() && ',' !in it }
                ?.distinct()?.take(MAX_FAVOURITES),
            optionalNutrientGoals = prefs?.obj("optional_nutrient_goals")?.let(::nutrientGoalsFrom).orEmpty(),
            waterTrackingEnabled = water?.bool("tracking_enabled"),
            waterDailyGoalMl = water?.int("daily_goal_ml")?.takeIf { it in 100..20_000 },
            fastingTrackingEnabled = fasting?.bool("tracking_enabled"),
            fastingDefaultGoalMinutes = fasting?.int("default_goal_minutes")
                ?.takeIf { it in FastingDefaults.MIN_GOAL_MINUTES..FastingDefaults.MAX_GOAL_MINUTES },
            fastingGoalNotificationEnabled = fasting?.bool("goal_notification_enabled")
        )
    }

    /** All four start times or none: strictly increasing and inside the day, else the group is ignored. */
    private fun mealScheduleFrom(o: JsonObject): MealSchedule? {
        val schedule = MealSchedule(
            breakfastStartMinutes = o.int("breakfast") ?: return null,
            lunchStartMinutes = o.int("lunch") ?: return null,
            dinnerStartMinutes = o.int("dinner") ?: return null,
            snackStartMinutes = o.int("snack") ?: return null
        )
        return schedule.takeIf { it.isValid }
    }

    private fun nutrientGoalsFrom(o: JsonObject): Map<OptionalNutrient, Int> {
        val out = linkedMapOf<OptionalNutrient, Int>()
        for (key in o.keys) {
            val nutrient = nutrientsByKey[key] ?: continue
            val value = o.int(key)?.takeIf { it in 0..999_999 } ?: continue
            out[nutrient] = value
        }
        return out
    }

    private fun weightFrom(o: JsonObject): WeightEntry? {
        return WeightEntry(
            id = uuid(o.str("id")) ?: return null,
            date = parseInstant(o.str("date")) ?: return null,
            weightKg = o.num("weight_kg")?.takeIf { it > 0.0 && it <= 1_000.0 } ?: return null
        )
    }

    private fun bodyFatFrom(o: JsonObject): BodyFatEntry? {
        return BodyFatEntry(
            id = uuid(o.str("id")) ?: return null,
            date = parseInstant(o.str("date")) ?: return null,
            // A fraction is 0..1; 27 (a percentage) is refused rather than stored as 2700 %.
            bodyFatFraction = o.num("fraction")?.takeIf { it > 0.0 && it < 1.0 } ?: return null
        )
    }

    private fun measurementFrom(o: JsonObject): BodyMeasurement? {
        fun site(key: String) = o.num(key)?.takeIf { it > 0.0 && it <= 500.0 }
        return BodyMeasurement(
            id = uuid(o.str("id")) ?: return null,
            date = parseInstant(o.str("date")) ?: return null,
            neckCm = site("neck_cm"),
            waistCm = site("waist_cm"),
            hipsCm = site("hips_cm"),
            chestCm = site("chest_cm"),
            upperArmCm = site("upper_arm_cm"),
            thighCm = site("thigh_cm"),
            calfCm = site("calf_cm"),
            wristCm = site("wrist_cm")
        ).takeIf { it.hasAnyValue }
    }

    private fun fastingFrom(o: JsonObject): FastingSession? {
        val started = parseInstant(o.str("started_at")) ?: return null
        // An ended_at that is present but unreadable would turn a finished fast into an active one.
        val ended = if (o.containsKey("ended_at") && o["ended_at"] !is JsonNull) {
            parseInstant(o.str("ended_at"))?.let { maxOf(it, started) } ?: return null
        } else {
            null
        }
        return FastingSession(
            id = uuid(o.str("id")) ?: return null,
            startedAt = started,
            endedAt = ended,
            goalMinutes = (o.int("goal_minutes") ?: FastingDefaults.GOAL_MINUTES)
                .coerceIn(FastingDefaults.MIN_GOAL_MINUTES, FastingDefaults.MAX_GOAL_MINUTES)
        )
    }

    private fun workoutsFrom(o: JsonObject): PortableWorkouts = PortableWorkouts(
        preferences = o.obj("preferences")?.let(::workoutPreferencesFrom),
        savedExerciseIds = o.strings("saved_exercise_ids").orEmpty().filter { it.isNotBlank() }.toCollection(LinkedHashSet()),
        userExercises = o.rows("user_exercises").mapNotNull(::exerciseFrom),
        customActivities = o.rows("custom_activities").mapNotNull(::exerciseFrom),
        dayPlans = buildMap {
            val plans = o.obj("day_plans") ?: return@buildMap
            for ((day, value) in plans) {
                val key = localDate(day)?.let { WorkoutDate.key(it) } ?: continue
                val exercises = (value as? JsonArray)?.mapNotNull { it as? JsonObject }?.mapNotNull(::exerciseFrom).orEmpty()
                if (exercises.isNotEmpty()) put(key, exercises)
            }
        },
        sessions = o.rows("sessions").mapNotNull(::sessionFrom)
    )

    private fun workoutPreferencesFrom(o: JsonObject): WorkoutPreferences {
        val base = WorkoutPreferences()
        val strength = o.obj("strength")
        return WorkoutPreferences(
            targetMuscles = o.strings("target_muscles")?.filter { it.isNotBlank() }?.toCollection(LinkedHashSet()) ?: base.targetMuscles,
            issues = o.strings("issues")?.mapNotNull(::issueFromWire)?.toSet() ?: base.issues,
            additionalIssues = o.str("additional_issues").orEmpty(),
            frequencyDays = o.int("frequency_days")?.takeIf { it in 1..7 } ?: base.frequencyDays,
            durationMinutes = o.int("duration_minutes")?.takeIf { it in 5..600 } ?: base.durationMinutes,
            split = splitFromWire(o.str("split")) ?: base.split,
            customSplit = o.str("custom_split").orEmpty(),
            equipment = o.strings("equipment")?.filter { it.isNotBlank() }?.toCollection(LinkedHashSet()) ?: base.equipment,
            rpeScale = rpeScaleFromWire(o.str("rpe_scale")) ?: base.rpeScale,
            strength = WorkoutStrengthNumbers(
                benchPressKg = strength?.num("bench_press_kg"),
                squatKg = strength?.num("squat_kg"),
                deadliftKg = strength?.num("deadlift_kg"),
                overheadPressKg = strength?.num("overhead_press_kg")
            )
        )
    }

    private fun exerciseFrom(o: JsonObject): PlannedExercise? {
        return PlannedExercise(
            id = uuid(o.str("id")) ?: return null,
            itemId = o.str("item_id")?.trim()?.takeIf { it.isNotEmpty() } ?: return null,
            name = o.str("name")?.takeIf { it.isNotBlank() } ?: return null,
            bodyPart = o.str("body_part").orEmpty(),
            equipment = o.str("equipment").orEmpty(),
            primaryMuscles = o.strings("primary_muscles").orEmpty(),
            secondaryMuscles = o.strings("secondary_muscles").orEmpty(),
            instructions = o.strings("instructions").orEmpty(),
            imageUrl = o.str("image_url")?.takeIf { it.isNotBlank() },
            gifUrl = o.str("gif_url")?.takeIf { it.isNotBlank() },
            sets = o.rows("sets").map(::plannedSetFrom)
        )
    }

    private fun plannedSetFrom(o: JsonObject) = PlannedSet(
        id = uuid(o.str("id")) ?: UUID.randomUUID(),
        weight = o.text("weight").orEmpty(),
        weightUnit = weightUnitFromWire(o.str("weight_unit")),
        reps = o.text("reps").orEmpty(),
        rpe = o.text("rpe").orEmpty(),
        rpeScale = rpeScaleFromWire(o.str("rpe_scale"))
    )

    private fun sessionFrom(o: JsonObject): WorkoutSession? {
        val started = parseInstant(o.str("started_at"))
        val completed = parseInstant(o.str("completed_at")) ?: started ?: return null
        return WorkoutSession(
            id = uuid(o.str("id")) ?: return null,
            diaryDateKey = localDate(o.str("diary_date_key"))?.let { WorkoutDate.key(it) } ?: return null,
            startedAt = started ?: completed,
            completedAt = completed,
            durationSeconds = (o.int("duration_seconds") ?: 0).coerceAtLeast(0),
            exercises = o.rows("exercises").mapNotNull(::completedExerciseFrom),
            caloriesBurned = o.int("calories_burned")?.takeIf { it > 0 }?.coerceAtMost(MAX_CALORIES_BURNED),
            // Health Connect bookkeeping is never carried: an imported session is not a pending write.
            healthSyncVersion = null
        )
    }

    private fun completedExerciseFrom(o: JsonObject): CompletedExercise? {
        val sets = mutableListOf<CompletedSet>()
        var previousUnit: WorkoutWeightUnit? = null
        for ((index, row) in o.rows("sets").withIndex()) {
            // A set without a unit takes the previous set's, else the app's own default (lbs).
            val unit = weightUnitFromWire(row.str("weight_unit")) ?: previousUnit ?: WorkoutWeightUnit.fromStorage(null)
            previousUnit = unit
            sets += CompletedSet(
                id = uuid(row.str("id")) ?: UUID.randomUUID(),
                setNumber = row.int("set_number")?.takeIf { it > 0 } ?: (index + 1),
                weight = row.text("weight").orEmpty(),
                weightUnit = unit,
                reps = row.text("reps").orEmpty(),
                rpe = row.text("rpe").orEmpty(),
                rpeScale = rpeScaleFromWire(row.str("rpe_scale"))
            )
        }
        return CompletedExercise(
            id = uuid(o.str("id")) ?: UUID.randomUUID(),
            itemId = o.str("item_id").orEmpty(),
            name = o.str("name")?.takeIf { it.isNotBlank() } ?: return null,
            targetMuscles = o.strings("target_muscles").orEmpty(),
            equipment = o.str("equipment").orEmpty(),
            sets = sets,
            durationSeconds = o.num("duration_seconds")?.takeIf { it >= 0.0 },
            intensity = intensityFromWire(o.str("intensity"))
        )
    }

    /** The store's own clamp for a calculated daily burn. */
    private const val MAX_CALORIES_BURNED = 5_000
}
