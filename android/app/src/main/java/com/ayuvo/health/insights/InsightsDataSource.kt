package com.ayuvo.health.insights

import com.ayuvo.health.actions.ActionExecutor
import com.ayuvo.health.actions.ActionMath
import com.ayuvo.health.data.health.HealthDataRepository
import com.ayuvo.health.data.health.HealthSampleRow
import com.ayuvo.health.data.metrics.AppMetricSnapshot
import com.ayuvo.health.data.metrics.MetricsReference
import com.ayuvo.health.models.FoodEntry
import com.ayuvo.health.models.Gender
import com.ayuvo.health.models.HealthDataType
import com.ayuvo.health.models.OptionalNutrientGoals
import com.ayuvo.health.models.UserProfile
import java.time.LocalDate
import java.time.ZoneId

/** The goals and switches Insights reads; values only, never stored by Insights. */
data class InsightsSettings(
    val stepGoal: Int,
    val waterGoalMl: Int,
    val waterTracking: Boolean,
    val fastingTracking: Boolean,
    val fastingGoalMinutes: Int,
    val optionalGoals: OptionalNutrientGoals
)

/**
 * Everything the engines need for one day, plus the per-day overnight fallback flags (a recovery
 * for day D reads the flags of D) and what the adapter could not find.
 */
data class InsightsBundle(
    val today: LocalDate,
    val inputs: InsightsInputs,
    val profile: InsightsProfile,
    val fallbackByDay: Map<LocalDate, Set<String>>,
    val healthEnabled: Boolean
) {
    fun inputsFor(day: LocalDate): InsightsInputs = inputs.copy(overnightFallback = fallbackByDay[day].orEmpty())
}

/**
 * Builds plain engine inputs from the stores the app already has (docs/insights.md §3.1). No new
 * database and nothing written: the Health Connect mirror ([HealthDataRepository]) gives sleep
 * nights, overnight HRV (RMSSD), resting heart rate, blood oxygen and breathing rate (samples inside
 * the night window, else the daily rollup, flagged), VO2 max, and steps / active energy from the
 * platform-aggregate daily rollups (the de-duplicated path the Summary Move ring uses); the app
 * snapshot gives food, water, fasting, weight, body fat and strength sessions. A missing value stays
 * missing, never 0.
 */
class InsightsDataSource(private val healthRepository: () -> HealthDataRepository?) {
    /** [hubOn]: the Health Data hub is enabled; without it only the app's own logs are read. */
    suspend fun build(
        today: LocalDate,
        zone: ZoneId,
        snap: AppMetricSnapshot,
        s: InsightsSettings,
        p: UserProfile?,
        hubOn: Boolean,
        cfg: InsightsConfig
    ): InsightsBundle {
        val from = today.minusDays(HISTORY_DAYS)
        val series = LinkedHashMap<String, Map<LocalDate, Double>>()
        val fallback = HashMap<LocalDate, MutableSet<String>>()
        val sleep = LinkedHashMap<LocalDate, SleepInput>()
        val workouts = ArrayList<WorkoutInput>()
        val health = if (hubOn) healthRepository() else null
        if (health != null) {
            for (n in health.sleepNights(from, today)) {
                val day = runCatching { LocalDate.parse(n.nightOf) }.getOrNull() ?: continue
                sleep[day] = SleepInput(n.asleepS / 60.0, n.startMs, n.endMs)
            }
            for ((metric, type) in OVERNIGHT) {
                val daily = health.daily(type.id, from, today).filter { it.count > 0 }.associate { LocalDate.parse(it.day) to it.avg }
                val rows = health.samples(type.id, from.minusDays(1), today).filter { !it.deleted && it.value != null }.sortedBy { it.startMs }
                val values = overnightSeries(rows, sleep, daily, from, today) { day -> fallback.getOrPut(day) { HashSet() } += metric }
                if (values.isNotEmpty()) series[metric] = values
            }
            dailyAverage(health, HealthDataType.VO2_MAX, from, today).takeIf { it.isNotEmpty() }?.let { series["vo2_max"] = it }
            dailySum(health, HealthDataType.STEPS, from, today).takeIf { it.isNotEmpty() }?.let { series["steps"] = it }
            dailySum(health, HealthDataType.ACTIVE_ENERGY, from, today).takeIf { it.isNotEmpty() }?.let { series["active_energy"] = it }
            for (row in health.samples(HealthDataType.WORKOUT.id, from.minusDays(1), today)) {
                if (!row.deleted && row.endMs > row.startMs) workouts += WorkoutInput(row.startMs, row.endMs, null)
            }
        }
        // Ayuvo strength sessions join the health workouts; overlaps are merged by the engine.
        for (w in snap.workouts) {
            val start = w.startedAt.toEpochMilli()
            val end = w.completedAt.toEpochMilli()
            if (end > start && !MetricsReference.localDateOf(start, zone).isBefore(from.minusDays(1))) workouts += WorkoutInput(start, end, null)
        }
        daily(snap.weight.map { MetricsReference.localDateOf(it.date.toEpochMilli(), zone) to it.weightKg }, from).takeIf { it.isNotEmpty() }?.let { series["weight"] = it }
        daily(snap.bodyFat.map { MetricsReference.localDateOf(it.date.toEpochMilli(), zone) to it.bodyFatPercent }, from).takeIf { it.isNotEmpty() }?.let { series["body_fat"] = it }

        val loads = TrainingLoad.dailyLoads(workouts, zone, cfg)
        loads.filterKeys { !it.isBefore(from) && !it.isAfter(today) }.mapValues { it.value.minutes }
            .takeIf { it.isNotEmpty() }?.let { series["workout"] = it }

        val strength = HashMap<LocalDate, Double>()
        for (w in snap.workouts) {
            val day = MetricsReference.workoutDay(w.diaryDateKey, w.startedAt.toEpochMilli(), zone)
            if (day.isBefore(from)) continue
            val sets = w.exercises.flatMap { e -> e.sets.filter { it.isPerformed } }
            val volume = ActionMath.setVolume(sets.map { ActionExecutor.setWork(it.weight, it.weightUnit, it.reps) })["volume_kg"] as Double
            strength[day] = (strength[day] ?: 0.0) + volume
        }

        val water = HashMap<LocalDate, Double>()
        for (e in snap.water) {
            val day = MetricsReference.localDateOf(e.date.toEpochMilli(), zone)
            if (!day.isBefore(from)) water[day] = (water[day] ?: 0.0) + e.milliliters
        }
        val fasting = HashMap<LocalDate, Double>()
        for (f in snap.fasting) {
            val end = f.endedAt ?: continue
            val day = MetricsReference.localDateOf(end.toEpochMilli(), zone)
            if (day.isBefore(from)) continue
            val hours = f.durationSeconds() / 3600.0
            if (hours > (fasting[day] ?: 0.0)) fasting[day] = hours
        }

        val nutrition = nutritionByDay(snap.food, zone, from)
        val recentWorkouts = workouts.any { !MetricsReference.localDateOf(it.startMs, zone).isBefore(today.minusDays(WORKOUT_TRACKING_DAYS)) }
        val inputs = InsightsInputs(
            timeZone = zone,
            hrvKind = "rmssd",
            series = series,
            sleep = sleep,
            workouts = workouts,
            tracking = TrackingFlags(nutrition = true, water = s.waterTracking, workouts = recentWorkouts, fasting = s.fastingTracking),
            targets = targets(p, s),
            nutrition = nutrition,
            waterMl = water,
            fastingHours = fasting,
            strengthVolume = strength
        )
        return InsightsBundle(today, inputs, profileOf(p, zone), fallback, health != null)
    }

    companion object {
        /** Patterns look back 120 days and each Recovery needs 60 more; Health Age pace needs 12 weeks + 90 days. */
        const val HISTORY_DAYS = 200L
        private const val WORKOUT_TRACKING_DAYS = 90L

        /** Overnight metrics and their Android health types (`hrv_kind` = RMSSD). */
        val OVERNIGHT: List<Pair<String, HealthDataType>> = listOf(
            "hrv" to HealthDataType.HRV_RMSSD,
            "resting_heart_rate" to HealthDataType.RESTING_HEART_RATE,
            "respiratory_rate" to HealthDataType.RESPIRATORY_RATE,
            "blood_oxygen" to HealthDataType.BLOOD_OXYGEN
        )

        fun profileOf(p: UserProfile?, zone: ZoneId): InsightsProfile = InsightsProfile(
            birthday = p?.birthday?.atZone(zone)?.toLocalDate(),
            sex = when (p?.gender) {
                Gender.MALE -> "male"
                Gender.FEMALE -> "female"
                else -> p?.gender?.name?.lowercase()
            },
            heightCm = p?.heightCm?.takeIf { it > 0 }
        )

        fun targets(p: UserProfile?, s: InsightsSettings): Map<String, Double> {
            val g = s.optionalGoals
            val raw = linkedMapOf(
                "calories" to p?.effectiveCalories?.toDouble(),
                "protein_g" to p?.effectiveProtein?.toDouble(),
                "carbs_g" to p?.effectiveCarbs?.toDouble(),
                "fat_g" to p?.effectiveFat?.toDouble(),
                "fiber_g" to g.fiber.toDouble(),
                "water_ml" to s.waterGoalMl.toDouble(),
                "steps" to s.stepGoal.toDouble(),
                "fasting_hours" to s.fastingGoalMinutes / 60.0,
                "sugar_max_g" to g.sugar.toDouble(),
                "added_sugar_max_g" to g.addedSugar.toDouble(),
                "sodium_max_mg" to g.sodium.toDouble(),
                "saturated_fat_max_g" to g.saturatedFat.toDouble(),
                "caffeine_max_mg" to g.caffeine.toDouble()
            )
            return raw.filterValues { it != null && it > 0 }.mapValues { it.value!! }
        }

        /** Per local day: calorie and macro totals, and each optional nutrient only when some entry has it. */
        fun nutritionByDay(foods: List<FoodEntry>, zone: ZoneId, from: LocalDate): Map<LocalDate, Map<String, Double>> =
            foods.groupBy { MetricsReference.localDateOf(it.timestamp.toEpochMilli(), zone) }
                .filterKeys { !it.isBefore(from) }
                .mapValues { (_, list) ->
                    val m = LinkedHashMap<String, Double>()
                    m["calories"] = list.sumOf { it.calories }.toDouble()
                    m["protein_g"] = list.sumOf { it.protein }
                    m["carbs_g"] = list.sumOf { it.carbs }
                    m["fat_g"] = list.sumOf { it.fat }
                    fun optional(key: String, f: (FoodEntry) -> Double?) {
                        val present = list.mapNotNull(f)
                        if (present.isNotEmpty()) m[key] = present.sum()
                    }
                    optional("fiber_g") { it.fiber }
                    optional("sugar_g") { it.sugar }
                    optional("added_sugar_g") { it.addedSugar }
                    optional("sodium_mg") { it.sodium }
                    optional("saturated_fat_g") { it.saturatedFat }
                    optional("caffeine_mg") { it.caffeine }
                    m
                }

        /**
         * [OVERNIGHT] values per wake day: samples inside that night's window, else the day's rollup
         * (reported through [onFallback]). Samples are sorted by start, so each night is a binary search.
         */
        fun overnightSeries(
            rows: List<HealthSampleRow>,
            nights: Map<LocalDate, SleepInput>,
            daily: Map<LocalDate, Double?>,
            from: LocalDate,
            to: LocalDate,
            onFallback: (LocalDate) -> Unit
        ): Map<LocalDate, Double> {
            val times = LongArray(rows.size) { rows[it].startMs }
            val out = LinkedHashMap<LocalDate, Double>()
            var day = from
            while (!day.isAfter(to)) {
                val night = nights[day]
                val inside = if (night == null) emptyList() else {
                    var lo = lowerBound(times, night.startMs)
                    val list = ArrayList<Pair<Long, Double>>()
                    while (lo < times.size && times[lo] <= night.endMs) {
                        list += times[lo] to rows[lo].value!!
                        lo++
                    }
                    list
                }
                val v = BaselineEngine.overnightValue(inside, night, daily[day])
                if (v.value != null) {
                    out[day] = v.value
                    if (v.fallback) onFallback(day)
                }
                day = day.plusDays(1)
            }
            return out
        }

        private fun lowerBound(a: LongArray, key: Long): Int {
            var lo = 0
            var hi = a.size
            while (lo < hi) {
                val mid = (lo + hi) ushr 1
                if (a[mid] < key) lo = mid + 1 else hi = mid
            }
            return lo
        }

        private fun daily(points: List<Pair<LocalDate, Double>>, from: LocalDate): Map<LocalDate, Double> =
            points.filter { !it.first.isBefore(from) }.groupBy({ it.first }, { it.second }).mapValues { InsightsMath.mean(it.value) }

        private suspend fun dailyAverage(h: HealthDataRepository, type: HealthDataType, from: LocalDate, to: LocalDate): Map<LocalDate, Double> =
            h.daily(type.id, from, to).filter { it.count > 0 && it.avg != null }.associate { LocalDate.parse(it.day) to it.avg!! }

        private suspend fun dailySum(h: HealthDataRepository, type: HealthDataType, from: LocalDate, to: LocalDate): Map<LocalDate, Double> =
            h.daily(type.id, from, to).filter { it.sum != null && (it.count > 0 || it.fromPlatformAggregate) }.associate { LocalDate.parse(it.day) to it.sum!! }
    }
}
