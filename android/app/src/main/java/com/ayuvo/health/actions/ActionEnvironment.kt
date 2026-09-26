package com.ayuvo.health.actions

import com.ayuvo.health.data.ExerciseItem
import com.ayuvo.health.data.metrics.AppMetricId
import com.ayuvo.health.data.metrics.WeekStart
import com.ayuvo.health.medications.model.AdherenceSummary
import com.ayuvo.health.medications.model.DoseAction
import com.ayuvo.health.medications.model.DoseActionResult
import com.ayuvo.health.medications.model.DoseLog
import com.ayuvo.health.medications.model.TodayTimeline
import com.ayuvo.health.models.BodyFatEntry
import com.ayuvo.health.models.BodyMeasurement
import com.ayuvo.health.models.FastingSession
import com.ayuvo.health.models.FoodEntry
import com.ayuvo.health.models.UserProfile
import com.ayuvo.health.models.WaterEntry
import com.ayuvo.health.models.WeightEntry
import com.ayuvo.health.models.WorkoutDayPlan
import com.ayuvo.health.models.WorkoutSession
import com.ayuvo.health.models.WorkoutWeightUnit
import java.time.ZoneId

/** User preferences actions need (units, goals, week start); unit names are catalog enum values. */
data class ActionPrefs(
    val massUnit: String = "kg",
    val volumeUnit: String = "ml",
    val lengthUnit: String = "cm",
    val weekStart: WeekStart = WeekStart.MONDAY,
    val waterGoalMl: Int = 2500,
    val stepGoal: Int = 10000,
    val workoutWeightUnit: WorkoutWeightUnit = WorkoutWeightUnit.KG
) {
    /** The `prefs` map the validator uses for `default_pref`. */
    val validatorPrefs: Map<String, String>
        get() = mapOf("mass_unit" to massUnit, "volume_unit" to volumeUnit, "length_unit" to lengthUnit)
}

/** A health metric's registry facts: catalog aggregation (`sum`, `average`, `latest`, `count`) and unit. */
data class MetricFacts(val aggregation: String, val unit: String, val durationLike: Boolean = false)

data class SleepSummary(val asleepS: Double, val startMs: Long, val endMs: Long)

/** A search hit from the Health Records store (title only, never a value). */
data class RecordHit(val id: String, val title: String, val kind: String, val date: String?)

data class LabResult(val value: Double?, val valueText: String, val unit: String?, val date: String?, val flag: String?)

/** Resolved lab test: analyte id plus display name. */
data class LabAnalyte(val id: String, val name: String)

/** Food the AI provider estimated from a description. */
data class FoodEstimate(val entry: FoodEntry)

/**
 * Everything the action handlers read or write, so [ActionExecutor] stays free of Android types and
 * runs in JVM unit tests with a fake. [ContainerActionEnvironment] backs it with the app's
 * existing repositories; no business rule is duplicated here.
 */
interface ActionEnvironment {
    fun nowMs(): Long
    fun zone(): ZoneId
    suspend fun prefs(): ActionPrefs

    // Profile / goals
    suspend fun profile(): UserProfile?
    suspend fun saveProfile(profile: UserProfile)
    suspend fun setWaterGoalMl(ml: Int)
    suspend fun setStepGoal(steps: Int)

    // Nutrition
    suspend fun foods(): List<FoodEntry>
    /** Favourites then recent foods, newest first, one per favourite key. */
    suspend fun savedFoods(): List<FoodEntry>
    /** False when an active fast blocks logging (FoodRepository rule). */
    suspend fun addFood(entry: FoodEntry): Boolean
    /** Estimate via the user's AI provider; throws [ActionException] AI_FAILED on failure. */
    suspend fun estimateFood(description: String): FoodEntry

    // Water
    suspend fun water(): List<WaterEntry>
    suspend fun addWater(entry: WaterEntry)

    // Body
    suspend fun weights(): List<WeightEntry>
    suspend fun addWeight(entry: WeightEntry)
    suspend fun bodyFats(): List<BodyFatEntry>
    suspend fun addBodyFat(entry: BodyFatEntry)
    suspend fun setMeasurement(site: BodyMeasurement.Site, cm: Double)

    // Fasting
    suspend fun fastingSessions(): List<FastingSession>
    suspend fun startFast(goalMinutes: Int): FastingSession?
    suspend fun endFast(): FastingSession?

    // Workouts
    suspend fun workoutSessions(): List<WorkoutSession>
    suspend fun workoutPlan(dateKey: String): WorkoutDayPlan
    suspend fun exerciseLibrary(): List<ExerciseItem>
    /** Adds [item] to the day's plan when it is not there yet (never toggles it off). */
    suspend fun ensurePlanned(item: ExerciseItem, dateKey: String)
    suspend fun setSetCount(count: Int, exerciseId: java.util.UUID, dateKey: String)
    suspend fun updateSet(exerciseId: java.util.UUID, setId: java.util.UUID, dateKey: String, weight: String, unit: WorkoutWeightUnit, reps: String, rpe: String?)
    suspend fun finishWorkout(dateKey: String): WorkoutSession?

    // Metrics
    suspend fun appMetricSamples(id: AppMetricId): List<ActionMath.Sample>
    /** Null when [typeId] is unknown. */
    suspend fun healthFacts(typeId: String): MetricFacts?
    /** True when the Health Data hub is on and this type's read permission is granted. Never prompts. */
    suspend fun healthReadAllowed(typeId: String): Boolean
    suspend fun healthSamples(typeId: String, fromMs: Long, toMs: Long): List<ActionMath.Sample>
    suspend fun healthLatest(typeId: String): ActionMath.Sample?
    suspend fun lastSleep(nowMs: Long): SleepSummary?

    // Health Records (empty when no records database exists; never creates one)
    suspend fun recordsAvailable(): Boolean
    suspend fun searchRecords(query: String, limit: Int): List<RecordHit>
    suspend fun latestRecord(): RecordHit?
    suspend fun resolveAnalyte(text: String): LabAnalyte?
    suspend fun labResults(analyteId: String): List<LabResult>
    suspend fun searchAnalytes(query: String, limit: Int): List<LabAnalyte>

    // Medications (empty when no medications database exists)
    suspend fun medicationsAvailable(): Boolean
    suspend fun medicationsToday(): TodayTimeline?
    suspend fun markDose(medicationId: String, scheduleId: String?, scheduledAtMs: Long, action: DoseAction, snoozeMinutes: Int): DoseActionResult
    suspend fun doseHistory(medicationId: String?, limit: Int): List<DoseLog>
    suspend fun medicationNames(): Map<String, String>
    suspend fun adherence(medicationId: String?): AdherenceSummary?

    /** Called after a successful write so widgets, reminders and shortcuts refresh. */
    suspend fun afterWrite(actionId: String) {}
}
