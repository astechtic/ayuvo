package com.ayuvo.health.actions

import com.ayuvo.health.data.ExerciseItem
import com.ayuvo.health.data.metrics.AppMetricId
import com.ayuvo.health.medications.model.AdherenceSummary
import com.ayuvo.health.medications.model.DoseAction
import com.ayuvo.health.medications.model.DoseActionResult
import com.ayuvo.health.medications.model.DoseLog
import com.ayuvo.health.medications.model.DoseStatus
import com.ayuvo.health.medications.model.DoseUnit
import com.ayuvo.health.medications.model.TodayTimeline
import com.ayuvo.health.models.BodyFatEntry
import com.ayuvo.health.models.BodyMeasurement
import com.ayuvo.health.models.FastingSession
import com.ayuvo.health.models.FoodEntry
import com.ayuvo.health.models.FoodSource
import com.ayuvo.health.models.PlannedExercise
import com.ayuvo.health.models.PlannedSet
import com.ayuvo.health.models.UserProfile
import com.ayuvo.health.models.WaterEntry
import com.ayuvo.health.models.WeightEntry
import com.ayuvo.health.models.WorkoutDayPlan
import com.ayuvo.health.models.WorkoutSession
import com.ayuvo.health.models.WorkoutWeightUnit
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

/** In-memory [ActionEnvironment] for JVM tests; every write is recorded so tests can assert on it. */
class FakeActionEnvironment(
    var now: Long = 1789574400000L, // 2026-09-16T16:00Z (Wednesday)
    val zoneId: ZoneId = ZoneId.of("UTC")
) : ActionEnvironment {
    var prefsValue = ActionPrefs()
    var profileValue: UserProfile? = UserProfile(heightCm = 175.0, weightKg = 70.0, customCalories = 2000, customProtein = 140, customCarbs = 220, customFat = 60)
    val foodList = mutableListOf<FoodEntry>()
    val saved = mutableListOf<FoodEntry>()
    val waterList = mutableListOf<WaterEntry>()
    val weightList = mutableListOf<WeightEntry>()
    val fatList = mutableListOf<BodyFatEntry>()
    val measurements = mutableListOf<Pair<BodyMeasurement.Site, Double>>()
    val fasts = mutableListOf<FastingSession>()
    val sessions = mutableListOf<WorkoutSession>()
    val plans = mutableMapOf<String, WorkoutDayPlan>()
    var library = listOf(
        ExerciseItem("0025", "barbell bench press", "chest", "barbell", listOf("pectorals"), emptyList(), emptyList()),
        ExerciseItem("0043", "barbell full squat", "upper legs", "barbell", listOf("glutes"), emptyList(), emptyList())
    )
    var estimate: ((String) -> FoodEntry)? = null
    var healthGranted = setOf<String>()
    val healthData = mutableMapOf<String, List<ActionMath.Sample>>()
    var recordsExist = false
    var medsExist = false
    var timeline: TodayTimeline? = null
    var medNames = mapOf<String, String>()
    val marked = mutableListOf<Triple<String, Long, DoseAction>>()
    val history = mutableListOf<DoseLog>()
    val writes = mutableListOf<String>()

    override fun nowMs(): Long = now
    override fun zone(): ZoneId = zoneId
    override suspend fun prefs(): ActionPrefs = prefsValue
    override suspend fun profile(): UserProfile? = profileValue
    override suspend fun saveProfile(profile: UserProfile) { profileValue = profile }
    override suspend fun setWaterGoalMl(ml: Int) { prefsValue = prefsValue.copy(waterGoalMl = ml) }
    override suspend fun setStepGoal(steps: Int) { prefsValue = prefsValue.copy(stepGoal = steps) }

    override suspend fun foods(): List<FoodEntry> = foodList
    override suspend fun savedFoods(): List<FoodEntry> = saved
    override suspend fun addFood(entry: FoodEntry): Boolean {
        if (fasts.any { it.isActive }) return false
        foodList += entry
        return true
    }
    override suspend fun estimateFood(description: String): FoodEntry =
        estimate?.invoke(description) ?: throw ActionException(ActionErrorCode.AI_FAILED)

    override suspend fun water(): List<WaterEntry> = waterList
    override suspend fun addWater(entry: WaterEntry) { waterList += entry }
    override suspend fun weights(): List<WeightEntry> = weightList
    override suspend fun addWeight(entry: WeightEntry) { weightList += entry }
    override suspend fun bodyFats(): List<BodyFatEntry> = fatList
    override suspend fun addBodyFat(entry: BodyFatEntry) { fatList += entry }
    override suspend fun setMeasurement(site: BodyMeasurement.Site, cm: Double) { measurements += site to cm }

    override suspend fun fastingSessions(): List<FastingSession> = fasts
    override suspend fun startFast(goalMinutes: Int): FastingSession? {
        if (fasts.any { it.isActive }) return null
        return FastingSession(startedAt = Instant.ofEpochMilli(now), goalMinutes = goalMinutes).also { fasts += it }
    }
    override suspend fun endFast(): FastingSession? {
        val i = fasts.indexOfFirst { it.isActive }
        if (i < 0) return null
        return fasts[i].copy(endedAt = Instant.ofEpochMilli(now)).also { fasts[i] = it }
    }

    override suspend fun workoutSessions(): List<WorkoutSession> = sessions
    override suspend fun workoutPlan(dateKey: String): WorkoutDayPlan = plans[dateKey] ?: WorkoutDayPlan(dateKey)
    override suspend fun exerciseLibrary(): List<ExerciseItem> = library
    override suspend fun ensurePlanned(item: ExerciseItem, dateKey: String) {
        val plan = workoutPlan(dateKey)
        if (plan.exercises.none { it.itemId == item.id }) {
            plans[dateKey] = plan.copy(exercises = plan.exercises + PlannedExercise(
                itemId = item.id, name = item.name, bodyPart = item.bodyPart, equipment = item.equipment,
                primaryMuscles = item.primaryMuscles, secondaryMuscles = item.secondaryMuscles, instructions = item.instructions
            ))
        }
    }
    override suspend fun setSetCount(count: Int, exerciseId: UUID, dateKey: String) {
        val plan = workoutPlan(dateKey)
        plans[dateKey] = plan.copy(exercises = plan.exercises.map { e ->
            if (e.id != exerciseId) e else e.copy(sets = e.sets + List(count - e.sets.size) { PlannedSet() })
        })
    }
    override suspend fun updateSet(exerciseId: UUID, setId: UUID, dateKey: String, weight: String, unit: WorkoutWeightUnit, reps: String, rpe: String?) {
        val plan = workoutPlan(dateKey)
        plans[dateKey] = plan.copy(exercises = plan.exercises.map { e ->
            if (e.id != exerciseId) e else e.copy(sets = e.sets.map { s ->
                if (s.id != setId) s else s.copy(weight = weight, weightUnit = unit, reps = reps, rpe = rpe ?: "")
            })
        })
    }
    override suspend fun finishWorkout(dateKey: String): WorkoutSession? {
        writes += "finish:$dateKey"
        return null
    }

    override suspend fun appMetricSamples(id: AppMetricId): List<ActionMath.Sample> = when (id) {
        AppMetricId.WATER -> waterList.map { ActionMath.Sample(it.date.toEpochMilli(), it.milliliters.toDouble()) }
        AppMetricId.WEIGHT -> weightList.map { ActionMath.Sample(it.date.toEpochMilli(), it.weightKg) }
        AppMetricId.CALORIES -> foodList.map { ActionMath.Sample(it.timestamp.toEpochMilli(), it.calories.toDouble()) }
        else -> emptyList()
    }
    override suspend fun healthFacts(typeId: String): MetricFacts? = when (typeId) {
        "steps" -> MetricFacts("sum", "count")
        "heart_rate" -> MetricFacts("average", "count/min")
        "sleep" -> MetricFacts("sum", "s", durationLike = true)
        else -> null
    }
    override suspend fun healthReadAllowed(typeId: String): Boolean = typeId in healthGranted
    override suspend fun healthSamples(typeId: String, fromMs: Long, toMs: Long): List<ActionMath.Sample> =
        healthData[typeId].orEmpty().filter { it.tMs in fromMs until toMs }
    override suspend fun healthLatest(typeId: String): ActionMath.Sample? = healthData[typeId]?.maxByOrNull { it.tMs }
    override suspend fun lastSleep(nowMs: Long): SleepSummary? = null

    override suspend fun recordsAvailable(): Boolean = recordsExist
    override suspend fun searchRecords(query: String, limit: Int): List<RecordHit> =
        if (query.contains("lipid", ignoreCase = true)) listOf(RecordHit("r1", "Lipid panel", "lab_report", "2026-09-01")) else emptyList()
    override suspend fun latestRecord(): RecordHit? = RecordHit("r1", "Lipid panel", "lab_report", "2026-09-01")
    override suspend fun resolveAnalyte(text: String): LabAnalyte? = if (text == "hba1c") LabAnalyte("hba1c", "HbA1c") else null
    override suspend fun labResults(analyteId: String): List<LabResult> =
        listOf(LabResult(5.9, "5.9", "%", "2026-06-01", "normal"), LabResult(6.1, "6.1", "%", "2026-09-01", "high"))
    override suspend fun searchAnalytes(query: String, limit: Int): List<LabAnalyte> = emptyList()

    override suspend fun medicationsAvailable(): Boolean = medsExist
    override suspend fun medicationsToday(): TodayTimeline? = timeline
    override suspend fun markDose(medicationId: String, scheduleId: String?, scheduledAtMs: Long, action: DoseAction, snoozeMinutes: Int): DoseActionResult {
        marked += Triple(medicationId, scheduledAtMs, action)
        val status = when (action) {
            DoseAction.TAKEN -> DoseStatus.TAKEN
            DoseAction.SKIPPED -> DoseStatus.SKIPPED
            else -> DoseStatus.SNOOZED
        }
        return DoseActionResult(true, null, DoseLog("l1", medicationId, scheduleId, scheduledAtMs, status, doseQuantity = 1.0, doseUnit = DoseUnit.TABLET, createdMs = now, updatedMs = now), "insert")
    }
    override suspend fun doseHistory(medicationId: String?, limit: Int): List<DoseLog> = history
    override suspend fun medicationNames(): Map<String, String> = medNames
    override suspend fun adherence(medicationId: String?): AdherenceSummary? = null

    override suspend fun afterWrite(actionId: String) { writes += actionId }

    fun food(name: String, kcal: Int, protein: Double, atMs: Long = now) =
        FoodEntry(name = name, calories = kcal, protein = protein, carbs = 0.0, fat = 0.0, timestamp = Instant.ofEpochMilli(atMs), source = FoodSource.MANUAL)
}
