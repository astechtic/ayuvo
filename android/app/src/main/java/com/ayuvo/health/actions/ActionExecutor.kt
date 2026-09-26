package com.ayuvo.health.actions

import com.ayuvo.health.data.ExerciseItem
import com.ayuvo.health.data.metrics.AppMetricAggregator
import com.ayuvo.health.data.metrics.AppMetricId
import com.ayuvo.health.data.metrics.MetricAggregation
import com.ayuvo.health.data.metrics.MetricKey
import com.ayuvo.health.medications.model.DoseAction
import com.ayuvo.health.medications.model.DoseStatus
import com.ayuvo.health.medications.model.TimelineItem
import com.ayuvo.health.models.BodyFatEntry
import com.ayuvo.health.models.BodyMeasurement
import com.ayuvo.health.models.FastingSession
import com.ayuvo.health.models.FoodEntry
import com.ayuvo.health.models.FoodSource
import com.ayuvo.health.models.MealType
import com.ayuvo.health.models.PlannedExercise
import com.ayuvo.health.models.WaterEntry
import com.ayuvo.health.models.WeightEntry
import com.ayuvo.health.models.WorkoutDate
import com.ayuvo.health.models.WorkoutWeightUnit
import java.time.Instant
import java.util.Locale
import java.util.UUID
import kotlin.math.roundToInt

/**
 * Runs catalog actions against [ActionEnvironment] (docs/actions.md). One implementation serves
 * every surface: deep links, launcher shortcuts, App Actions, the in-app confirm sheet and Coach.
 *
 * [run] never asks for confirmation itself — callers show the confirm sheet (or the Coach card)
 * when [ValidatedAction.confirm] is set, and only then call [run].
 */
class ActionExecutor(val catalog: ActionCatalog, private val env: ActionEnvironment) {
    val validator = ActionValidator(catalog)

    suspend fun prefs(): ActionPrefs = env.prefs()

    suspend fun validate(request: ActionRequest): ValidationResult =
        validator.validate(request.id, request.params, request.source, env.prefs().validatorPrefs)

    /** Validate and run in one step; for reads (Coach tools, tests). Throws [ActionException]. */
    suspend fun perform(request: ActionRequest): ActionResult = when (val v = validate(request)) {
        is ValidationResult.Failed -> throw ActionException(v.code, v.param)
        is ValidationResult.Ok -> run(v.action)
    }

    suspend fun run(action: ValidatedAction): ActionResult {
        val result = when (action.spec.kind) {
            ActionKind.GET, ActionKind.SEARCH -> read(action)
            ActionKind.SET -> write(action).also { env.afterWrite(action.spec.id) }
            ActionKind.OPEN -> ActionResult(action.spec.id, openFields(action))
        }
        return result.copy(screen = result.screen ?: action.spec.screenFor(action.params))
    }

    // -- Reads -------------------------------------------------------------------------------------

    private suspend fun read(a: ValidatedAction): ActionResult {
        val id = a.spec.id
        return when (id) {
            "health.metric.get" -> metricGet(a)
            "health.metric.latest" -> metricLatest(a)
            "health.metric.samples" -> metricSamples(a)
            "health.sleep.lastNight" -> {
                if (!env.healthReadAllowed("sleep")) throw ActionException(ActionErrorCode.PERMISSION_REQUIRED, "sleep")
                val night = env.lastSleep(env.nowMs()) ?: throw ActionException(ActionErrorCode.NOT_FOUND)
                ActionResult(id, linkedMapOf("asleep_s" to night.asleepS.toLong(), "start_ms" to night.startMs, "end_ms" to night.endMs))
            }
            "nutrition.summary.get" -> nutritionSummary(a)
            "nutrition.nutrient.get" -> nutrientGet(a)
            "nutrition.meals.list" -> {
                val range = range(a)
                val meal = a.string("meal")
                val items = env.foods()
                    .filter { it.timestamp.toEpochMilli() in range && (meal == null || it.mealType.serial == meal) }
                    .sortedByDescending { it.timestamp }
                    .take(a.int("limit") ?: 50)
                    .map(::foodFields)
                ActionResult(id, items = items)
            }
            "nutrition.targets.get" -> {
                val p = env.profile()
                ActionResult(id, linkedMapOf(
                    "calories" to p?.effectiveCalories?.toLong(),
                    "protein_g" to p?.effectiveProtein?.toLong(),
                    "carbs_g" to p?.effectiveCarbs?.toLong(),
                    "fat_g" to p?.effectiveFat?.toLong()
                ))
            }
            "water.get" -> {
                val range = range(a)
                ActionResult(id, waterStatus(range, singleDayGoalDays(a.string("range"), range)))
            }
            "fasting.status.get" -> ActionResult(id, fastingStatus(env.fastingSessions().lastOrNull { it.isActive }))
            "fasting.history.list" -> {
                val range = range(a)
                val items = env.fastingSessions()
                    .filter { !it.isActive && it.startedAt.toEpochMilli() in range }
                    .sortedByDescending { it.startedAt }
                    .take(a.int("limit") ?: 50)
                    .map(::fastFields)
                ActionResult(id, items = items)
            }
            "weight.get" -> {
                val latest = env.weights().maxByOrNull { it.date } ?: throw ActionException(ActionErrorCode.NOT_FOUND)
                val unit = env.prefs().massUnit
                ActionResult(id, linkedMapOf(
                    "value" to ActionMath.convert(catalog, "mass", "kg", unit, latest.weightKg),
                    "unit" to unit,
                    "t_ms" to latest.date.toEpochMilli()
                ))
            }
            "weight.history" -> {
                val range = range(a)
                val entries = env.weights().filter { it.date.toEpochMilli() in range }
                val points = entries.map { ActionMath.WeightPoint(it.date.toEpochMilli(), it.weightKg) }
                val fields = LinkedHashMap(ActionMath.weightChange(points))
                val items = points.sortedBy { it.tMs }.map { linkedMapOf<String, Any?>("t_ms" to it.tMs, "kg" to ActionMath.roundTo(it.kg, 2)) }
                fields["entries"] = items
                ActionResult(id, fields)
            }
            "body.composition.get" -> {
                val p = env.profile()
                val weight = env.weights().maxByOrNull { it.date }?.weightKg ?: p?.weightKg
                val fat = env.bodyFats().maxByOrNull { it.date }?.bodyFatPercent ?: p?.bodyFatPercentage?.let { it * 100 }
                ActionResult(id, linkedMapOf(
                    "weight_kg" to weight?.let { ActionMath.roundTo(it, 1) },
                    "body_fat_percent" to fat?.let { ActionMath.roundTo(it, 1) },
                    "height_cm" to p?.heightCm?.let { ActionMath.roundTo(it, 1) },
                    "bmi" to ActionMath.bmi(weight, p?.heightCm)
                ))
            }
            "workout.today.get" -> ActionResult(id, workoutDay(todayKey()))
            "workout.history.list" -> {
                val range = range(a)
                val zone = env.zone()
                val items = env.workoutSessions()
                    .filter { s ->
                        val day = WorkoutDate.parse(s.diaryDateKey) ?: s.completedAt.atZone(zone).toLocalDate()
                        day.atStartOfDay(zone).toInstant().toEpochMilli() in range
                    }
                    .sortedByDescending { it.completedAt }
                    .take(a.int("limit") ?: 50)
                    .map { s ->
                        val sets = s.exercises.flatMap { e -> e.sets.filter { it.isPerformed } }
                        linkedMapOf<String, Any?>(
                            "id" to s.id.toString(),
                            "date" to s.diaryDateKey,
                            "exercise_count" to s.exerciseCount.toLong(),
                            "sets" to s.performedSetCount.toLong(),
                            "volume_kg" to ActionMath.setVolume(sets.map { setWork(it.weight, it.weightUnit, it.reps) })["volume_kg"],
                            "duration_s" to s.durationSeconds.toLong()
                        )
                    }
                ActionResult(id, items = items)
            }
            "workout.exercise.stats" -> exerciseStats(a)
            "records.search" -> {
                if (!env.recordsAvailable()) return ActionResult(id)
                val items = env.searchRecords(a.string("query")!!, a.int("limit") ?: 20).map(::recordFields)
                ActionResult(id, items = items)
            }
            "records.latest" -> {
                val hit = if (env.recordsAvailable()) env.latestRecord() else null
                ActionResult(id, recordFields(hit ?: throw ActionException(ActionErrorCode.NOT_FOUND)))
            }
            "records.labValue.get" -> {
                if (!env.recordsAvailable()) throw ActionException(ActionErrorCode.NOT_FOUND, "analyte")
                val analyte = env.resolveAnalyte(a.string("analyte")!!) ?: throw ActionException(ActionErrorCode.NOT_FOUND, "analyte")
                val results = env.labResults(analyte.id).sortedByDescending { it.date.orEmpty() }
                val latest = results.firstOrNull() ?: throw ActionException(ActionErrorCode.NOT_FOUND, "analyte")
                val history = results.take(a.int("limit") ?: 10).map {
                    linkedMapOf<String, Any?>("value" to it.value, "value_text" to it.valueText, "unit" to it.unit, "date" to it.date, "flag" to it.flag)
                }
                ActionResult(id, linkedMapOf(
                    "analyte" to analyte.name,
                    "value" to latest.value,
                    "value_text" to latest.valueText,
                    "unit" to latest.unit,
                    "date" to latest.date,
                    "flag" to latest.flag,
                    "history" to history
                ))
            }
            "medications.today.list" -> {
                val (timeline, names) = medsToday() ?: return ActionResult(id)
                ActionResult(id, items = timeline.map { doseFields(it, names) })
            }
            "medications.next.get" -> {
                val (timeline, names) = medsToday() ?: throw ActionException(ActionErrorCode.NOT_FOUND)
                val next = timeline.filter { it.status.isPending }.minByOrNull { it.scheduledAtMs }
                    ?: throw ActionException(ActionErrorCode.NOT_FOUND)
                ActionResult(id, doseFields(next, names))
            }
            "medications.history.list" -> {
                if (!env.medicationsAvailable()) return ActionResult(id)
                val range = range(a)
                val names = env.medicationNames()
                val items = env.doseHistory(a.string("medication"), HISTORY_SCAN)
                    .filter { it.scheduledAtMs in range }
                    .take(a.int("limit") ?: 100)
                    .map {
                        linkedMapOf<String, Any?>(
                            "medication" to (names[it.medicationId] ?: it.medicationId),
                            "scheduled_ms" to it.scheduledAtMs,
                            "status" to it.status.raw,
                            "taken_ms" to it.takenAtMs
                        )
                    }
                ActionResult(id, items = items)
            }
            "medications.adherence.get" -> {
                if (!env.medicationsAvailable()) return ActionResult(id, linkedMapOf("taken" to 0L, "expected" to 0L, "percent" to null))
                val range = range(a)
                val logs = env.doseHistory(a.string("medication"), HISTORY_SCAN)
                    .filter { it.scheduleId != null && it.scheduledAtMs in range && it.status.isTerminal }
                val taken = logs.count { it.status == DoseStatus.TAKEN }.toLong()
                val expected = logs.size.toLong()
                ActionResult(id, linkedMapOf(
                    "taken" to taken,
                    "expected" to expected,
                    "percent" to if (expected > 0) ActionMath.percent(taken.toDouble(), expected.toDouble()).toLong() else null
                ))
            }
            "goals.get" -> goals()
            "profile.summary.get" -> {
                val p = env.profile()
                val prefs = env.prefs()
                ActionResult(id, linkedMapOf(
                    "age" to p?.age?.toLong(),
                    "height_cm" to p?.heightCm?.let { ActionMath.roundTo(it, 1) },
                    "weight_kg" to (env.weights().maxByOrNull { it.date }?.weightKg ?: p?.weightKg)?.let { ActionMath.roundTo(it, 1) },
                    "mass_unit" to prefs.massUnit,
                    "volume_unit" to prefs.volumeUnit
                ))
            }
            "search.universal" -> ActionResult(id, items = search(a.string("query")!!, a.string("domain") ?: "all", a.int("limit") ?: 20, a.source))
            "insights.recovery.get" -> {
                if (!env.healthReadAllowed("sleep")) throw ActionException(ActionErrorCode.PERMISSION_REQUIRED, "sleep")
                ActionResult(id, InsightsActionFields.recovery(insights().recovery))
            }
            "insights.healthAge.get" -> insights().let { s -> ActionResult(id, InsightsActionFields.healthAge(s.healthAge, s.pace)) }
            "insights.dailyReview.get" -> {
                val s = insights()
                val day = if (a.string("day") == "yesterday") s.today.minusDays(1) else s.today
                ActionResult(id, InsightsActionFields.review(s.review(day), day))
            }
            else -> throw ActionException(ActionErrorCode.UNKNOWN_ACTION)
        }
    }

    private suspend fun insights(): com.ayuvo.health.insights.InsightsSnapshot =
        env.insights() ?: throw ActionException(ActionErrorCode.UNAVAILABLE, detail = "insights_off")

    private suspend fun metricGet(a: ValidatedAction): ActionResult {
        val range = range(a)
        val (samples, facts) = metricData(a.string("metric")!!, range.fromMs, range.toMs)
        val aggregation = a.string("aggregation") ?: facts.aggregation
        val agg = ActionMath.aggregate(samples.filter { it.tMs in range }, aggregation)
        return ActionResult(a.spec.id, linkedMapOf(
            "value" to agg["value"],
            "unit" to if (aggregation == "count") "count" else facts.unit,
            "aggregation" to aggregation,
            "sample_count" to agg["count"],
            "from_ms" to range.fromMs,
            "to_ms" to range.toMs
        ))
    }

    private suspend fun metricLatest(a: ValidatedAction): ActionResult {
        val metric = a.string("metric")!!
        val sample: ActionMath.Sample?
        val unit: String
        when (val key = parseMetric(metric)) {
            is MetricKey.App -> {
                sample = env.appMetricSamples(key.id).filter { it.value != null }.maxByOrNull { it.tMs }
                unit = APP_UNITS.getValue(key.id)
            }
            is MetricKey.Health -> {
                val facts = env.healthFacts(key.typeId) ?: throw ActionException(ActionErrorCode.NOT_FOUND, "metric")
                if (!env.healthReadAllowed(key.typeId)) throw ActionException(ActionErrorCode.PERMISSION_REQUIRED, "metric")
                sample = env.healthLatest(key.typeId)
                unit = facts.unit
            }
        }
        val s = sample ?: throw ActionException(ActionErrorCode.NOT_FOUND)
        return ActionResult(a.spec.id, linkedMapOf("value" to s.value, "unit" to unit, "t_ms" to s.tMs))
    }

    private suspend fun metricSamples(a: ValidatedAction): ActionResult {
        val range = range(a)
        val (samples, facts) = metricData(a.string("metric")!!, range.fromMs, range.toMs)
        val items = samples.filter { it.tMs in range && it.value != null }
            .sortedByDescending { it.tMs }
            .take(a.int("limit") ?: 100)
            .map { linkedMapOf<String, Any?>("t_ms" to it.tMs, "value" to it.value, "unit" to facts.unit) }
        return ActionResult(a.spec.id, items = items)
    }

    private suspend fun metricData(metric: String, fromMs: Long, toMs: Long): Pair<List<ActionMath.Sample>, MetricFacts> =
        when (val key = parseMetric(metric)) {
            is MetricKey.App -> env.appMetricSamples(key.id) to MetricFacts(appAggregation(key.id), APP_UNITS.getValue(key.id))
            is MetricKey.Health -> {
                val facts = env.healthFacts(key.typeId) ?: throw ActionException(ActionErrorCode.NOT_FOUND, "metric")
                if (!env.healthReadAllowed(key.typeId)) throw ActionException(ActionErrorCode.PERMISSION_REQUIRED, "metric")
                env.healthSamples(key.typeId, fromMs, toMs) to facts
            }
        }

    private fun parseMetric(metric: String): MetricKey =
        MetricKey.parse(metric) ?: throw ActionException(ActionErrorCode.NOT_FOUND, "metric")

    private suspend fun nutritionSummary(a: ValidatedAction): ActionResult {
        val range = range(a)
        val foods = env.foods().filter { it.timestamp.toEpochMilli() in range }
        val p = env.profile()
        val calories = foods.sumOf { it.calories }.toLong()
        val singleDay = a.string("range") in SINGLE_DAY
        val target = p?.effectiveCalories?.toLong()
        return ActionResult(a.spec.id, linkedMapOf(
            "calories" to calories,
            "protein_g" to ActionMath.roundTo(foods.sumOf { it.protein }, 1),
            "carbs_g" to ActionMath.roundTo(foods.sumOf { it.carbs }, 1),
            "fat_g" to ActionMath.roundTo(foods.sumOf { it.fat }, 1),
            "fiber_g" to ActionMath.roundTo(foods.sumOf { it.fiber ?: 0.0 }, 1),
            "calorie_target" to target,
            "protein_target_g" to p?.effectiveProtein?.toLong(),
            "calories_remaining" to if (singleDay && target != null && target > 0) maxOf(0L, target - calories) else null,
            "entry_count" to foods.size.toLong()
        ))
    }

    private suspend fun nutrientGet(a: ValidatedAction): ActionResult {
        val range = range(a)
        val nutrient = a.string("nutrient")!!
        val zone = env.zone()
        val daily = env.foods().filter { it.timestamp.toEpochMilli() in range }
            .groupBy { it.timestamp.atZone(zone).toLocalDate() }
            .map { (day, list) -> ActionMath.Sample(day.atStartOfDay(zone).toInstant().toEpochMilli(), list.sumOf { nutrientValue(it, nutrient) }) }
        val aggregation = a.string("aggregation") ?: "sum"
        val value = (ActionMath.aggregate(daily, aggregation)["value"] as? Number)?.toDouble() ?: 0.0
        val p = env.profile()
        val dailyTarget = when (nutrient) {
            "calories" -> p?.effectiveCalories
            "protein" -> p?.effectiveProtein
            "carbs" -> p?.effectiveCarbs
            "fat" -> p?.effectiveFat
            else -> null
        }?.toDouble()
        val comparable = a.string("range") in SINGLE_DAY || aggregation == "average"
        val progress = ActionMath.progress(value, if (comparable) dailyTarget else null)
        return ActionResult(a.spec.id, linkedMapOf(
            "nutrient" to nutrient,
            "value" to progress["value"],
            "unit" to if (nutrient == "calories") "kcal" else "g",
            "target" to progress["target"],
            "remaining" to progress["remaining"],
            "percent" to progress["percent"]
        ))
    }

    private fun nutrientValue(f: FoodEntry, nutrient: String): Double = when (nutrient) {
        "calories" -> f.calories.toDouble()
        "protein" -> f.protein
        "carbs" -> f.carbs
        "fat" -> f.fat
        "fiber" -> f.fiber ?: 0.0
        else -> 0.0
    }

    private suspend fun waterStatus(range: ActionDateRange, goalDays: Long): Map<String, Any?> {
        val intake = env.water().filter { it.date.toEpochMilli() in range }.sumOf { it.milliliters.toLong() }
        return ActionMath.waterStatus(intake, env.prefs().waterGoalMl.toLong() * goalDays)
    }

    /** A goal scales with the number of days in the range (1 for today/yesterday). */
    private fun singleDayGoalDays(preset: String?, range: ActionDateRange): Long =
        if (preset in SINGLE_DAY) 1L else maxOf(1L, Math.round((range.toMs - range.fromMs) / 86_400_000.0))

    private fun fastingStatus(active: FastingSession?): Map<String, Any?> {
        if (active == null) {
            return linkedMapOf("active" to false, "started_ms" to null, "elapsed_s" to null, "goal_s" to null,
                "remaining_s" to null, "percent" to null, "reached" to false)
        }
        val started = active.startedAt.toEpochMilli()
        val progress = ActionMath.fastingProgress(started, active.goalMinutes.toLong(), env.nowMs())
        return linkedMapOf<String, Any?>("active" to true, "started_ms" to started) + progress
    }

    private fun fastFields(s: FastingSession): Map<String, Any?> {
        val duration = s.durationSeconds()
        val goal = s.goalMinutes * 60L
        return linkedMapOf(
            "id" to s.id.toString(),
            "started_ms" to s.startedAt.toEpochMilli(),
            "ended_ms" to s.endedAt?.toEpochMilli(),
            "duration_s" to duration,
            "goal_s" to goal,
            "reached" to (duration >= goal)
        )
    }

    private suspend fun workoutDay(dateKey: String): Map<String, Any?> {
        val plan = env.workoutPlan(dateKey)
        val done = plan.exercises.flatMap { e -> e.sets.filter { it.reps.isNotBlank() } }
        val prefUnit = env.prefs().workoutWeightUnit
        val volume = ActionMath.setVolume(done.map { setWork(it.weight, it.weightUnit ?: prefUnit, it.reps) })
        return linkedMapOf(
            "exercise_count" to plan.exercises.size.toLong(),
            "sets_done" to volume["sets"],
            "reps_done" to volume["reps"],
            "volume_kg" to volume["volume_kg"],
            "completed" to env.workoutSessions().any { it.diaryDateKey == dateKey }
        )
    }

    private suspend fun exerciseStats(a: ValidatedAction): ActionResult {
        val range = range(a)
        val zone = env.zone()
        val item = resolveExercise(a.string("exercise")!!)
        val sessions = env.workoutSessions().filter { s ->
            val day = WorkoutDate.parse(s.diaryDateKey) ?: s.completedAt.atZone(zone).toLocalDate()
            day.atStartOfDay(zone).toInstant().toEpochMilli() in range
        }
        val matches = sessions.mapNotNull { s ->
            s.exercises.filter { it.itemId == item.id || it.name.equals(item.name, ignoreCase = true) }.takeIf { it.isNotEmpty() }
        }
        val sets = matches.flatten().flatMap { e -> e.sets.filter { it.isPerformed } }
        val work = sets.map { setWork(it.weight, it.weightUnit, it.reps) }
        val volume = ActionMath.setVolume(work)
        return ActionResult(a.spec.id, linkedMapOf(
            "exercise" to item.name,
            "sessions" to matches.size.toLong(),
            "sets" to volume["sets"],
            "reps" to volume["reps"],
            "volume_kg" to volume["volume_kg"],
            "best_weight_kg" to work.maxOfOrNull { it.weightKg }?.let { ActionMath.roundTo(it, 1) }
        ))
    }

    private suspend fun goals(): ActionResult {
        val p = env.profile()
        val prefs = env.prefs()
        val today = ActionDateRange.resolve("today", env.nowMs(), env.zone(), prefs.weekStart)
        val foods = env.foods().filter { it.timestamp.toEpochMilli() in today }
        val water = env.water().filter { it.date.toEpochMilli() in today }.sumOf { it.milliliters.toLong() }
        fun pct(value: Double, goal: Int?): Long? = goal?.takeIf { it > 0 }?.let { ActionMath.percent(value, it.toDouble()).toLong() }
        val progress = linkedMapOf<String, Any?>(
            "calories_percent" to pct(foods.sumOf { it.calories }.toDouble(), p?.effectiveCalories),
            "protein_percent" to pct(foods.sumOf { it.protein }, p?.effectiveProtein),
            "water_percent" to pct(water.toDouble(), prefs.waterGoalMl)
        )
        return ActionResult("goals.get", linkedMapOf(
            "calories" to p?.effectiveCalories?.toLong(),
            "protein_g" to p?.effectiveProtein?.toLong(),
            "carbs_g" to p?.effectiveCarbs?.toLong(),
            "fat_g" to p?.effectiveFat?.toLong(),
            "water_ml" to prefs.waterGoalMl.toLong(),
            "steps" to prefs.stepGoal.toLong(),
            "goal_weight_kg" to p?.goalWeightKg?.let { ActionMath.roundTo(it, 1) },
            "progress" to progress
        ))
    }

    private suspend fun search(query: String, domain: String, limit: Int, source: ActionSource): List<Map<String, Any?>> {
        val q = normalize(query)
        val out = mutableListOf<Map<String, Any?>>()
        fun want(d: String) = domain == "all" || domain == d
        fun hit(d: String, id: String, title: String, subtitle: String?) =
            linkedMapOf<String, Any?>("domain" to d, "id" to id, "title" to title, "subtitle" to subtitle)
        if (want("food")) {
            env.savedFoods().filter { normalize(it.name).contains(q) }.distinctBy { it.favoriteKey }
                .forEach { out += hit("food", it.favoriteKey, it.name, null) }
        }
        if (want("exercises")) {
            env.exerciseLibrary().filter { normalize(it.name).contains(q) }.sortedBy { it.name.length }.take(limit)
                .forEach { out += hit("exercises", it.id, it.name, it.bodyPart) }
        }
        if (want("metrics")) {
            AppMetricId.entries.filter { normalize(it.slug).contains(q) }.forEach { out += hit("metrics", it.key, it.slug.replace('_', ' '), null) }
            val healthId = q.replace(' ', '_')
            if (env.healthFacts(healthId) != null) out += hit("metrics", healthId, q, null)
        }
        // Coach only sees records and medications through their own consented tools.
        if (want("records") && source != ActionSource.COACH && env.recordsAvailable()) {
            env.searchRecords(query, limit).forEach { out += hit("records", it.id, it.title, it.date) }
            env.searchAnalytes(query, limit).forEach { out += hit("records", "analyte:${it.id}", it.name, null) }
        }
        if (want("medications") && source != ActionSource.COACH && env.medicationsAvailable()) {
            env.medicationNames().filter { normalize(it.value).contains(q) }.forEach { out += hit("medications", it.key, it.value, null) }
        }
        return out.take(limit)
    }

    // -- Writes ------------------------------------------------------------------------------------

    private suspend fun write(a: ValidatedAction): ActionResult {
        val id = a.spec.id
        return when (id) {
            "nutrition.food.log" -> {
                val meal = a.string("meal")?.let(::mealType) ?: MealType.currentMeal
                val entry = if (a.number("calories") != null) {
                    FoodEntry(
                        name = a.string("name")!!,
                        calories = a.number("calories")!!.roundToInt(),
                        protein = a.number("protein") ?: 0.0,
                        carbs = a.number("carbs") ?: 0.0,
                        fat = a.number("fat") ?: 0.0,
                        timestamp = Instant.ofEpochMilli(env.nowMs()),
                        source = FoodSource.MANUAL,
                        mealType = meal
                    )
                } else {
                    env.estimateFood(a.string("description")!!).copy(timestamp = Instant.ofEpochMilli(env.nowMs()), mealType = meal)
                }
                if (!env.addFood(entry)) throw ActionException(ActionErrorCode.CONFLICT, detail = "active_fast")
                ActionResult(id, foodFields(entry))
            }
            "nutrition.food.logSaved" -> {
                val food = resolveFood(a.string("food")!!)
                val servings = a.number("servings") ?: 1.0
                val entry = food.copy(
                    id = UUID.randomUUID(),
                    timestamp = Instant.ofEpochMilli(env.nowMs()),
                    calories = (food.calories * servings).roundToInt(),
                    protein = food.protein * servings,
                    carbs = food.carbs * servings,
                    fat = food.fat * servings,
                    mealType = a.string("meal")?.let(::mealType) ?: MealType.currentMeal,
                    healthConnectOrigin = null,
                    healthConnectRecordId = null
                )
                if (!env.addFood(entry)) throw ActionException(ActionErrorCode.CONFLICT, detail = "active_fast")
                ActionResult(id, foodFields(entry))
            }
            "water.log" -> {
                val unit = a.string("unit") ?: "ml"
                val ml = ActionMath.toCanonical(catalog, "volume", unit, a.number("amount")!!).roundToInt().coerceAtLeast(1)
                env.addWater(WaterEntry(date = Instant.ofEpochMilli(env.nowMs()), milliliters = ml))
                val today = ActionDateRange.resolve("today", env.nowMs(), env.zone(), env.prefs().weekStart)
                ActionResult(id, linkedMapOf<String, Any?>("added_ml" to ml.toLong()) + waterStatus(today, 1))
            }
            "weight.log" -> {
                val unit = a.string("unit") ?: "kg"
                val value = a.number("value")!!
                val kg = ActionMath.toCanonical(catalog, "mass", unit, value)
                val entry = WeightEntry(date = Instant.ofEpochMilli(env.nowMs()), weightKg = kg)
                env.addWeight(entry)
                ActionResult(id, linkedMapOf("value" to value, "unit" to unit, "t_ms" to entry.date.toEpochMilli()))
            }
            "body.fat.log" -> {
                val percent = a.number("percent")!!
                val entry = BodyFatEntry(date = Instant.ofEpochMilli(env.nowMs()), bodyFatFraction = percent / 100.0)
                env.addBodyFat(entry)
                ActionResult(id, linkedMapOf("value" to percent, "unit" to "%", "t_ms" to entry.date.toEpochMilli()))
            }
            "body.measurement.log" -> {
                val site = BodyMeasurement.Site.valueOf(a.string("site")!!.uppercase(Locale.ROOT))
                val unit = a.string("unit") ?: "cm"
                val value = a.number("value")!!
                env.setMeasurement(site, ActionMath.toCanonical(catalog, "length", unit, value))
                ActionResult(id, linkedMapOf("site" to a.string("site"), "value" to value, "unit" to unit, "t_ms" to env.nowMs()))
            }
            "fasting.start" -> {
                if (env.fastingSessions().any { it.isActive }) throw ActionException(ActionErrorCode.CONFLICT, detail = "fast_active")
                val minutes = ((a.number("goal_hours") ?: 16.0) * 60).roundToInt()
                val session = env.startFast(minutes) ?: throw ActionException(ActionErrorCode.CONFLICT, detail = "fast_active")
                ActionResult(id, fastingStatus(session))
            }
            "fasting.stop" -> {
                val ended = env.endFast() ?: throw ActionException(ActionErrorCode.NOT_FOUND, detail = "no_active_fast")
                ActionResult(id, fastFields(ended))
            }
            "workout.start" -> ActionResult(id)
            "workout.finish" -> {
                val key = todayKey()
                if (env.workoutPlan(key).exercises.isEmpty()) throw ActionException(ActionErrorCode.NOT_FOUND, detail = "no_workout")
                env.finishWorkout(key)
                ActionResult(id, workoutDay(key))
            }
            "workout.set.log" -> logSet(a)
            "medication.dose.mark" -> markDose(a)
            "goals.update" -> updateGoal(a)
            else -> throw ActionException(ActionErrorCode.UNKNOWN_ACTION)
        }
    }

    private suspend fun logSet(a: ValidatedAction): ActionResult {
        val key = todayKey()
        val item = resolveExercise(a.string("exercise")!!)
        env.ensurePlanned(item, key)
        fun planned(plan: com.ayuvo.health.models.WorkoutDayPlan): PlannedExercise =
            plan.exercises.firstOrNull { it.itemId == item.id } ?: throw ActionException(ActionErrorCode.NOT_FOUND, "exercise")
        var exercise = planned(env.workoutPlan(key))
        var index = exercise.sets.indexOfFirst { it.reps.isBlank() }
        if (index < 0) {
            if (exercise.sets.size >= MAX_SETS) throw ActionException(ActionErrorCode.CONFLICT, detail = "max_sets")
            env.setSetCount(exercise.sets.size + 1, exercise.id, key)
            exercise = planned(env.workoutPlan(key))
            index = exercise.sets.indexOfFirst { it.reps.isBlank() }
            if (index < 0) throw ActionException(ActionErrorCode.CONFLICT, detail = "max_sets")
        }
        val unitRaw = a.string("unit") ?: "kg"
        val unit = if (unitRaw == "lb") WorkoutWeightUnit.LBS else WorkoutWeightUnit.KG
        val weight = a.number("weight") ?: 0.0
        val reps = a.int("reps")!!
        env.updateSet(exercise.id, exercise.sets[index].id, key, formatNumber(weight), unit, reps.toString(), a.number("rpe")?.let(::formatNumber))
        val day = workoutDay(key)
        return ActionResult(a.spec.id, linkedMapOf(
            "exercise" to exercise.name,
            "set_number" to (index + 1).toLong(),
            "reps" to reps.toLong(),
            "weight_kg" to ActionMath.roundTo(ActionMath.toCanonical(catalog, "mass", unitRaw, weight), 2),
            "sets_today" to day["sets_done"],
            "volume_kg" to day["volume_kg"]
        ))
    }

    private suspend fun markDose(a: ValidatedAction): ActionResult {
        val (timeline, names) = medsToday() ?: throw ActionException(ActionErrorCode.NOT_FOUND, "dose")
        val item = resolveDose(a.string("dose")!!, timeline, names)
        val action = DoseAction.fromRaw(a.string("action")) ?: throw ActionException(ActionErrorCode.BAD_ENUM, "action")
        val result = env.markDose(item.medicationId, item.scheduleId, item.scheduledAtMs, action, a.int("snooze_minutes") ?: 10)
        if (!result.ok) throw ActionException(ActionErrorCode.CONFLICT, "dose", result.error)
        val status = result.log?.status ?: item.status
        return ActionResult(a.spec.id, doseFields(item.copy(status = status), names))
    }

    private suspend fun updateGoal(a: ValidatedAction): ActionResult {
        val goal = a.string("goal")!!
        val value = a.number("value")!!.roundToInt()
        val previous: Int?
        when (goal) {
            "water" -> { previous = env.prefs().waterGoalMl; env.setWaterGoalMl(value) }
            "steps" -> { previous = env.prefs().stepGoal; env.setStepGoal(value) }
            else -> {
                val p = env.profile() ?: throw ActionException(ActionErrorCode.UNAVAILABLE, detail = "no_profile")
                previous = when (goal) {
                    "calories" -> p.effectiveCalories
                    "protein" -> p.effectiveProtein
                    "carbs" -> p.effectiveCarbs
                    else -> p.effectiveFat
                }
                env.saveProfile(when (goal) {
                    "calories" -> p.copy(customCalories = value)
                    "protein" -> p.copy(customProtein = value)
                    "carbs" -> p.copy(customCarbs = value)
                    else -> p.copy(customFat = value)
                })
            }
        }
        return ActionResult(a.spec.id, linkedMapOf("goal" to goal, "value" to value.toLong(), "previous" to previous?.toLong()))
    }

    private fun openFields(a: ValidatedAction): Map<String, Any?> = LinkedHashMap(a.params)

    // -- Resolution helpers ------------------------------------------------------------------------

    private suspend fun resolveExercise(query: String): ExerciseItem {
        val q = normalize(query)
        val planned = env.workoutPlan(todayKey()).exercises.map {
            ExerciseItem(it.itemId, it.name, it.bodyPart, it.equipment, it.primaryMuscles, it.secondaryMuscles, it.instructions)
        }
        val all = (planned + env.exerciseLibrary()).distinctBy { it.id }
        return all.firstOrNull { it.id == query }
            ?: all.firstOrNull { normalize(it.name) == q || normalize(it.id) == q }
            ?: all.filter { normalize(it.name).startsWith(q) }.minByOrNull { it.name.length }
            ?: all.filter { normalize(it.name).contains(q) }.minByOrNull { it.name.length }
            ?: throw ActionException(ActionErrorCode.NOT_FOUND, "exercise")
    }

    private suspend fun resolveFood(query: String): FoodEntry {
        val saved = env.savedFoods()
        val q = normalize(query)
        return saved.firstOrNull { it.id.toString() == query || it.favoriteKey == query }
            ?: saved.firstOrNull { normalize(it.name) == q }
            ?: saved.firstOrNull { normalize(it.name).contains(q) }
            ?: throw ActionException(ActionErrorCode.NOT_FOUND, "food")
    }

    private suspend fun medsToday(): Pair<List<TimelineItem>, Map<String, String>>? {
        if (!env.medicationsAvailable()) return null
        val timeline = env.medicationsToday() ?: return null
        val names = timeline.medications.mapValues { it.value.name } + env.medicationNames().filterKeys { it !in timeline.medications }
        val items = timeline.groups.flatMap { it.items }.filter { it.scheduleId != null }.sortedBy { it.scheduledAtMs }
        return items to names
    }

    /**
     * A dose reference: the entity id `medicationId|scheduleId|scheduledAtMs`, `next`, or text such
     * as "morning" or a medication name — matched only against today's pending doses.
     */
    internal fun resolveDose(ref: String, items: List<TimelineItem>, names: Map<String, String>): TimelineItem {
        doseIdParts(ref)?.let { (med, sched, at) ->
            return items.firstOrNull { it.medicationId == med && it.scheduleId == sched && it.scheduledAtMs == at }
                ?: throw ActionException(ActionErrorCode.NOT_FOUND, "dose")
        }
        val pending = items.filter { it.status.isPending }.sortedBy { it.scheduledAtMs }
        val q = normalize(ref)
        if (q == "next") return pending.firstOrNull() ?: throw ActionException(ActionErrorCode.NOT_FOUND, "dose")
        val zone = env.zone()
        val part = DAY_PARTS.entries.firstOrNull { q.contains(it.key) }
        var matches = pending
        if (part != null) matches = matches.filter { Instant.ofEpochMilli(it.scheduledAtMs).atZone(zone).hour in part.value }
        val words = q.split(' ').filter { it.isNotBlank() && it !in DOSE_FILLER && DAY_PARTS.keys.none { k -> it == k } }
        if (words.isNotEmpty()) {
            val byName = matches.filter { item -> val n = normalize(names[item.medicationId].orEmpty()); words.all { n.contains(it) } }
            if (byName.isNotEmpty() || part == null) matches = byName
        }
        return matches.firstOrNull() ?: throw ActionException(ActionErrorCode.NOT_FOUND, "dose")
    }

    private fun doseFields(item: TimelineItem, names: Map<String, String>): Map<String, Any?> = linkedMapOf(
        "id" to doseId(item),
        "medication" to (names[item.medicationId] ?: ""),
        "scheduled_ms" to item.scheduledAtMs,
        "status" to item.status.raw
    )

    private suspend fun range(a: ValidatedAction): ActionDateRange =
        ActionDateRange.resolve(a.string("range") ?: "today", env.nowMs(), env.zone(), env.prefs().weekStart)

    private fun todayKey(): String = WorkoutDate.key(Instant.ofEpochMilli(env.nowMs()).atZone(env.zone()).toLocalDate())

    companion object {
        private const val HISTORY_SCAN = 1000
        private const val MAX_SETS = 12
        private val SINGLE_DAY = setOf("today", "yesterday")
        private val DAY_PARTS = linkedMapOf("morning" to 4..11, "afternoon" to 12..16, "evening" to 17..20, "night" to 21..23, "bedtime" to 20..23)
        private val DOSE_FILLER = setOf("my", "the", "medicine", "medicines", "medication", "meds", "dose", "pill", "pills", "tablet")

        val APP_UNITS: Map<AppMetricId, String> = mapOf(
            AppMetricId.CALORIES to "kcal", AppMetricId.PROTEIN to "g", AppMetricId.CARBS to "g", AppMetricId.FAT to "g",
            AppMetricId.FIBER to "g", AppMetricId.WATER to "ml", AppMetricId.FASTING to "s", AppMetricId.WEIGHT to "kg",
            AppMetricId.BODY_FAT to "%", AppMetricId.WORKOUTS to "count", AppMetricId.WORKOUT_MINUTES to "s",
            AppMetricId.WORKOUT_BURN to "kcal"
        )

        fun appAggregation(id: AppMetricId): String = when (AppMetricAggregator.aggregation(id)) {
            MetricAggregation.SUM, MetricAggregation.DURATION -> "sum"
            MetricAggregation.AVG -> "average"
            MetricAggregation.LAST -> "latest"
            MetricAggregation.COUNT -> "count"
        }

        fun doseId(item: TimelineItem): String = "${item.medicationId}|${item.scheduleId.orEmpty()}|${item.scheduledAtMs}"

        fun doseIdParts(ref: String): Triple<String, String, Long>? {
            val parts = ref.split('|')
            if (parts.size != 3) return null
            val at = parts[2].toLongOrNull() ?: return null
            return Triple(parts[0], parts[1], at)
        }

        fun normalize(s: String): String =
            s.lowercase(Locale.ROOT).replace(Regex("[_\\-]+"), " ").replace(Regex("\\s+"), " ").trim()

        fun formatNumber(v: Double): String =
            if (v % 1.0 == 0.0) v.toLong().toString() else String.format(Locale.US, "%.2f", v).trimEnd('0').trimEnd('.')

        fun setWork(weight: String, unit: WorkoutWeightUnit?, reps: String): ActionMath.SetWork {
            val w = weight.replace(',', '.').toDoubleOrNull() ?: 0.0
            val kg = if (unit == WorkoutWeightUnit.LBS) w * 0.45359237 else w
            return ActionMath.SetWork(kg, reps.toIntOrNull() ?: 0)
        }

        fun mealType(raw: String): MealType = when (raw) {
            "breakfast" -> MealType.BREAKFAST
            "lunch" -> MealType.LUNCH
            "dinner" -> MealType.DINNER
            "snack" -> MealType.SNACK
            else -> MealType.OTHER
        }

        fun foodFields(f: FoodEntry): Map<String, Any?> = linkedMapOf(
            "id" to f.id.toString(),
            "name" to f.name,
            "calories" to f.calories.toLong(),
            "protein_g" to ActionMath.roundTo(f.protein, 1),
            "carbs_g" to ActionMath.roundTo(f.carbs, 1),
            "fat_g" to ActionMath.roundTo(f.fat, 1),
            "meal" to f.mealType.serial,
            "t_ms" to f.timestamp.toEpochMilli()
        )

        fun recordFields(r: RecordHit): Map<String, Any?> =
            linkedMapOf("id" to r.id, "title" to r.title, "kind" to r.kind, "date" to r.date)
    }
}

/** Catalog `meal_type` value of this meal. */
val MealType.serial: String
    get() = when (this) {
        MealType.BREAKFAST -> "breakfast"
        MealType.LUNCH -> "lunch"
        MealType.DINNER -> "dinner"
        MealType.SNACK -> "snack"
        MealType.OTHER -> "other"
    }

/** Scheduled, due or snoozed: the dose can still be acted on. */
val DoseStatus.isPending: Boolean
    get() = this == DoseStatus.SCHEDULED || this == DoseStatus.DUE || this == DoseStatus.SNOOZED
