package com.ayuvo.health.actions

import com.ayuvo.health.AppContainer
import com.ayuvo.health.data.ExerciseItem
import com.ayuvo.health.data.ExerciseRepository
import com.ayuvo.health.data.metrics.AppMetricAggregator
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
import com.ayuvo.health.models.FoodSource
import com.ayuvo.health.models.HealthAggregation
import com.ayuvo.health.models.HealthDataType
import com.ayuvo.health.models.HealthKind
import com.ayuvo.health.models.UserProfile
import com.ayuvo.health.models.WaterEntry
import com.ayuvo.health.models.WaterUnit
import com.ayuvo.health.models.WeightEntry
import com.ayuvo.health.models.WorkoutDayPlan
import com.ayuvo.health.models.WorkoutSession
import com.ayuvo.health.models.WorkoutWeightUnit
import com.ayuvo.health.records.model.RecordQuery
import kotlinx.coroutines.flow.first
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

/**
 * [ActionEnvironment] backed by the app's existing repositories and stores (manual DI through
 * [AppContainer]). Reads never create the records, medications or health databases; Health
 * Connect permissions are checked, never requested.
 */
class ContainerActionEnvironment(private val container: AppContainer) : ActionEnvironment {
    private val prefsStore get() = container.prefs

    override fun nowMs(): Long = System.currentTimeMillis()
    override fun zone(): ZoneId = ZoneId.systemDefault()

    override suspend fun prefs(): ActionPrefs {
        val weightUnit = prefsStore.weightUnit.first()
        return ActionPrefs(
            massUnit = if (weightUnit == "kg") "kg" else "lb",
            volumeUnit = if (prefsStore.waterUnit.first() == WaterUnit.FLUID_OUNCES) "floz" else "ml",
            lengthUnit = if (prefsStore.heightUnit.first() == "cm") "cm" else "in",
            weekStart = if (prefsStore.weekStartsOnMonday.first()) WeekStart.MONDAY else WeekStart.SUNDAY,
            waterGoalMl = prefsStore.waterDailyGoalMl.first(),
            stepGoal = prefsStore.dailyStepGoal.first(),
            workoutWeightUnit = if (weightUnit == "kg") WorkoutWeightUnit.KG else WorkoutWeightUnit.LBS
        )
    }

    override suspend fun profile(): UserProfile? = container.profileRepository.current()
    override suspend fun saveProfile(profile: UserProfile) = container.profileRepository.save(profile)
    override suspend fun setWaterGoalMl(ml: Int) = prefsStore.setWaterDailyGoalMl(ml)
    override suspend fun setStepGoal(steps: Int) = prefsStore.setDailyStepGoal(steps)

    override suspend fun foods(): List<FoodEntry> = container.foodRepository.entries.first()
    override suspend fun savedFoods(): List<FoodEntry> =
        (container.foodRepository.favorites.first() + container.foodRepository.recent().sortedByDescending { it.timestamp })
            .distinctBy { it.favoriteKey }
    override suspend fun addFood(entry: FoodEntry): Boolean = container.foodRepository.addEntry(entry)

    override suspend fun estimateFood(description: String): FoodEntry {
        val analysis = try {
            container.foodAnalysis.analyzeText(description)
        } catch (e: Throwable) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            throw ActionException(ActionErrorCode.AI_FAILED, detail = e.message)
        }
        return FoodEntry(
            name = analysis.name,
            calories = analysis.calories,
            protein = analysis.protein,
            carbs = analysis.carbs,
            fat = analysis.fat,
            emoji = analysis.emoji,
            source = FoodSource.TEXT_INPUT,
            sugar = analysis.sugar,
            addedSugar = analysis.addedSugar,
            fiber = analysis.fiber,
            saturatedFat = analysis.saturatedFat,
            monounsaturatedFat = analysis.monounsaturatedFat,
            polyunsaturatedFat = analysis.polyunsaturatedFat,
            cholesterol = analysis.cholesterol,
            caffeine = analysis.caffeine,
            servingSizeGrams = analysis.servingSizeGrams
        )
    }

    override suspend fun water(): List<WaterEntry> = container.waterRepository.entries.first()
    override suspend fun addWater(entry: WaterEntry) = container.waterRepository.add(entry)

    override suspend fun weights(): List<WeightEntry> = container.weightRepository.entries.first()
    override suspend fun addWeight(entry: WeightEntry) { container.weightRepository.addEntry(entry) }
    override suspend fun bodyFats(): List<BodyFatEntry> = container.bodyFatRepository.entries.first()
    override suspend fun addBodyFat(entry: BodyFatEntry) = container.bodyFatRepository.addEntry(entry)
    override suspend fun setMeasurement(site: BodyMeasurement.Site, cm: Double) =
        container.bodyMeasurementRepository.setValue(site, cm)

    override suspend fun fastingSessions(): List<FastingSession> = container.fastingRepository.sessions.first()
    override suspend fun startFast(goalMinutes: Int): FastingSession? = container.fastingRepository.start(goalMinutes)
    override suspend fun endFast(): FastingSession? = container.fastingRepository.endActive()

    override suspend fun workoutSessions(): List<WorkoutSession> = container.workoutRepository.completedSessions.first()
    override suspend fun workoutPlan(dateKey: String): WorkoutDayPlan = container.workoutRepository.planNow(dateKey)
    override suspend fun exerciseLibrary(): List<ExerciseItem> = ExerciseRepository.get(container.appContext).exercises

    override suspend fun ensurePlanned(item: ExerciseItem, dateKey: String) {
        if (workoutPlan(dateKey).exercises.none { it.itemId == item.id }) container.workoutRepository.toggleExercise(item, dateKey)
    }

    override suspend fun setSetCount(count: Int, exerciseId: UUID, dateKey: String) =
        container.workoutRepository.setSetCount(count, exerciseId, dateKey)

    override suspend fun updateSet(exerciseId: UUID, setId: UUID, dateKey: String, weight: String, unit: WorkoutWeightUnit, reps: String, rpe: String?) =
        container.workoutRepository.updateSet(exerciseId, setId, dateKey, weight = weight, weightUnit = unit, reps = reps, rpe = rpe)

    override suspend fun finishWorkout(dateKey: String): WorkoutSession? {
        val profile = profile()
        val bodyWeight = weights().maxByOrNull { it.date }?.weightKg ?: profile?.weightKg ?: 70.0
        return container.workoutRepository.calculateBurn(dateKey, bodyWeight, prefs().workoutWeightUnit)
    }

    override suspend fun appMetricSamples(id: AppMetricId): List<ActionMath.Sample> =
        AppMetricAggregator.entries(id, container.appMetrics.snapshot(), nowMs(), zone()).map { ActionMath.Sample(it.tMs, it.value) }

    override suspend fun healthFacts(typeId: String): MetricFacts? {
        val type = HealthDataType.byId(typeId) ?: return null
        val durationLike = type.aggregation == HealthAggregation.DURATION || type.kind == HealthKind.SESSION || type.kind == HealthKind.DURATION
        val aggregation = when (type.aggregation) {
            HealthAggregation.SUM, HealthAggregation.DURATION -> "sum"
            HealthAggregation.AVERAGE, HealthAggregation.MIN_MAX -> "average"
            HealthAggregation.LATEST -> "latest"
            HealthAggregation.COUNT -> "count"
        }
        return MetricFacts(aggregation, if (durationLike) "s" else type.unit, durationLike)
    }

    override suspend fun healthReadAllowed(typeId: String): Boolean {
        if (!prefsStore.healthHubEnabled.first()) return false
        if (!container.health.isAvailable()) return false
        val caps = container.health.capabilitiesOrNull() ?: return false
        return caps.hubReadTypes.any { it.id == typeId }
    }

    override suspend fun healthSamples(typeId: String, fromMs: Long, toMs: Long): List<ActionMath.Sample> {
        val zone = zone()
        val durationLike = healthFacts(typeId)?.durationLike == true
        val from = Instant.ofEpochMilli(fromMs).atZone(zone).toLocalDate()
        val to = Instant.ofEpochMilli(toMs - 1).atZone(zone).toLocalDate()
        return container.healthRepository.samples(typeId, from, to)
            .filter { !it.deleted }
            .map { ActionMath.Sample(it.startMs, if (durationLike) it.durationS else it.value) }
    }

    override suspend fun healthLatest(typeId: String): ActionMath.Sample? {
        val row = container.healthRepository.latest(typeId) ?: return null
        val durationLike = healthFacts(typeId)?.durationLike == true
        return ActionMath.Sample(row.startMs, if (durationLike) row.durationS else row.value)
    }

    override suspend fun lastSleep(nowMs: Long): SleepSummary? {
        val today = Instant.ofEpochMilli(nowMs).atZone(zone()).toLocalDate()
        val night = container.healthRepository.sleepNights(today.minusDays(1), today).maxByOrNull { it.nightOf } ?: return null
        return SleepSummary(night.asleepS, night.startMs, night.endMs)
    }

    override suspend fun recordsAvailable(): Boolean = container.recordsDatabaseExists()

    override suspend fun searchRecords(query: String, limit: Int): List<RecordHit> =
        container.recordsStore.search(RecordQuery(search = query), limit).map { hit ->
            RecordHit(hit.record.id, hit.record.title, hit.record.recordType.raw, hit.record.documentDate ?: hit.record.sortDate)
        }

    override suspend fun latestRecord(): RecordHit? =
        container.recordsStore.recent(1).firstOrNull()?.let { RecordHit(it.id, it.title, it.recordType.raw, it.documentDate ?: it.sortDate) }

    override suspend fun resolveAnalyte(text: String): LabAnalyte? {
        val catalog = container.analyteCatalog
        catalog.analyte(text)?.let { return LabAnalyte(it.id, it.displayName) }
        val q = ActionExecutor.normalize(text)
        val match = catalog.analytes.firstOrNull { a -> ActionExecutor.normalize(a.displayName) == q || a.aliases.any { ActionExecutor.normalize(it) == q } }
            ?: catalog.analytes.firstOrNull { a -> ActionExecutor.normalize(a.displayName).contains(q) }
        return match?.let { LabAnalyte(it.id, it.displayName) }
    }

    override suspend fun searchAnalytes(query: String, limit: Int): List<LabAnalyte> {
        val q = ActionExecutor.normalize(query)
        return container.analyteCatalog.analytes
            .filter { a -> ActionExecutor.normalize(a.displayName).contains(q) || a.aliases.any { ActionExecutor.normalize(it).contains(q) } }
            .take(limit)
            .map { LabAnalyte(it.id, it.displayName) }
    }

    override suspend fun labResults(analyteId: String): List<LabResult> =
        container.recordsStore.analyteObservations(analyteId).map {
            LabResult(it.valueNum, it.valueText, it.unit, it.observedDate, it.flag.raw)
        }

    override suspend fun medicationsAvailable(): Boolean = container.medicationsDatabaseExists()

    override suspend fun medicationsToday(): TodayTimeline? =
        container.medicationsStore.today(nowMs(), zone().id)

    override suspend fun markDose(medicationId: String, scheduleId: String?, scheduledAtMs: Long, action: DoseAction, snoozeMinutes: Int): DoseActionResult =
        container.medicationsStore.act(medicationId, scheduleId, scheduledAtMs, action, nowMs(), snoozeMinutes)

    override suspend fun doseHistory(medicationId: String?, limit: Int): List<DoseLog> =
        container.medicationsStore.history(medicationId, null, limit)

    override suspend fun medicationNames(): Map<String, String> =
        container.medicationsStore.list().associate { it.id to it.name }

    override suspend fun adherence(medicationId: String?): AdherenceSummary? =
        container.medicationsStore.adherence(medicationId, nowMs(), zone().id)

    override suspend fun afterWrite(actionId: String) {
        when (actionId) {
            "medication.dose.mark" -> runCatching { container.medicationReminders.replan() }
            "fasting.start", "fasting.stop" -> runCatching {
                if (prefsStore.notificationsEnabled.first() && prefsStore.fastingGoalNotificationEnabled.first()) {
                    container.notifications.scheduleFastingGoal(container.fastingRepository.active())
                } else if (actionId == "fasting.stop") {
                    container.notifications.cancelFastingGoal()
                }
            }
        }
    }
}
