package com.ayuvo.health.export

import com.ayuvo.health.data.BodyFatRepository
import com.ayuvo.health.data.FastingRepository
import com.ayuvo.health.data.PreferencesStore
import com.ayuvo.health.data.WeightRepository
import com.ayuvo.health.data.WorkoutRepository
import com.ayuvo.health.models.BodyFatEntry
import com.ayuvo.health.models.BodyMeasurement
import com.ayuvo.health.models.FastingDefaults
import com.ayuvo.health.models.FastingSession
import com.ayuvo.health.models.OptionalNutrient
import com.ayuvo.health.models.OptionalNutrientGoals
import com.ayuvo.health.models.PlannedExercise
import com.ayuvo.health.models.UserProfile
import com.ayuvo.health.models.WaterUnit
import com.ayuvo.health.models.WeightEntry
import com.ayuvo.health.models.WorkoutDayPlan
import com.ayuvo.health.models.WorkoutPersistedState
import com.ayuvo.health.models.WorkoutSession
import kotlinx.coroutines.flow.first
import java.time.ZoneId
import kotlin.math.abs

/** Thrown for a file that is turned away before anything is written; the message is shown to the user. */
class PortableRefusedException(val reason: PortableRefusal) : Exception(
    when (reason) {
        PortableRefusal.NEWER_VERSION -> "This file was made by a newer version of Ayuvo"
        PortableRefusal.WRONG_FORMAT, PortableRefusal.NOT_JSON -> "This isn't an Ayuvo profile and logs file"
    }
)

/** What one import changed; every count is rows or values that were new here. */
data class PortableImportResult(
    val appliedProfile: Boolean = false,
    val settings: Int = 0,
    val weights: Int = 0,
    val bodyFat: Int = 0,
    val bodyMeasurements: Int = 0,
    val fastingSessions: Int = 0,
    val workoutSessions: Int = 0,
    val userExercises: Int = 0,
    val dayPlans: Int = 0
) {
    /** The single number Import All Data reports for the section. */
    val total: Long
        get() = (if (appliedProfile) 1 else 0) + settings.toLong() + weights + bodyFat + bodyMeasurements +
            fastingSessions + workoutSessions + userExercises + dayPlans

    val counts: Map<String, Long>
        get() = linkedMapOf(
            "weights" to weights.toLong(),
            "body_fat" to bodyFat.toLong(),
            "body_measurements" to bodyMeasurements.toLong(),
            "fasting_sessions" to fastingSessions.toLong(),
            "workout_sessions" to workoutSessions.toLong(),
            "user_exercises" to userExercises.toLong(),
            "settings" to settings.toLong()
        ).filterValues { it > 0L }
}

class Merged<T>(val items: List<T>, val added: Int) {
    /** The stores keep their lists in date order; an unchanged list is left exactly as it was. */
    fun <K : Comparable<K>> sortedBy(key: (T) -> K): Merged<T> = if (added == 0) this else Merged(items.sortedBy(key), added)
}

class WorkoutMerge(
    val state: WorkoutPersistedState,
    val sessionsAdded: Int,
    val userExercisesAdded: Int,
    val dayPlansAdded: Int
)

/** The merge rules of docs/portable-data.md as pure functions: skip what is here, delete nothing. */
object PortableMerge {
    private const val WEIGHT_TOLERANCE_KG = 0.001
    private const val FRACTION_TOLERANCE = 0.0001

    fun weights(existing: List<WeightEntry>, incoming: List<WeightEntry>): Merged<WeightEntry> =
        timed(existing, incoming, { it.id }, { it.date.epochSecond }, { it.weightKg }, WEIGHT_TOLERANCE_KG).sortedBy { it.date }

    fun bodyFat(existing: List<BodyFatEntry>, incoming: List<BodyFatEntry>): Merged<BodyFatEntry> =
        timed(existing, incoming, { it.id }, { it.date.epochSecond }, { it.bodyFatFraction }, FRACTION_TOLERANCE).sortedBy { it.date }

    /**
     * Skips a row whose id is already here and, because Health Connect / HealthKit ids differ per
     * platform, a row with the same second and the same value as one that is.
     */
    private fun <T> timed(
        existing: List<T>,
        incoming: List<T>,
        id: (T) -> Any,
        second: (T) -> Long,
        value: (T) -> Double,
        tolerance: Double
    ): Merged<T> {
        val ids = existing.mapTo(HashSet(), id)
        val valuesBySecond = HashMap<Long, MutableList<Double>>()
        existing.forEach { valuesBySecond.getOrPut(second(it)) { mutableListOf() } += value(it) }
        val added = mutableListOf<T>()
        for (row in incoming) {
            if (!ids.add(id(row))) continue
            val sameMoment = valuesBySecond.getOrPut(second(row)) { mutableListOf() }
            if (sameMoment.any { abs(it - value(row)) < tolerance }) continue
            sameMoment += value(row)
            added += row
        }
        return Merged(existing + added, added.size)
    }

    fun measurements(existing: List<BodyMeasurement>, incoming: List<BodyMeasurement>): Merged<BodyMeasurement> {
        val ids = existing.mapTo(HashSet()) { it.id }
        val added = incoming.filter { it.hasAnyValue && ids.add(it.id) }
        return Merged(existing + added, added.size).sortedBy { it.date }
    }

    /** No second active fast and no overlap: such a row is dropped, never adjusted. */
    fun fasting(existing: List<FastingSession>, incoming: List<FastingSession>): Merged<FastingSession> {
        val result = existing.toMutableList()
        var added = 0
        for (row in incoming.sortedBy { it.startedAt }) {
            if (result.any { it.id == row.id }) continue
            val session = row.copy(
                endedAt = row.endedAt?.let { maxOf(it, row.startedAt) },
                goalMinutes = row.goalMinutes.coerceIn(FastingDefaults.MIN_GOAL_MINUTES, FastingDefaults.MAX_GOAL_MINUTES)
            )
            if (session.isActive && result.any { it.isActive }) continue
            if (result.any { FastingRepository.overlaps(session, it) }) continue
            result += session
            added++
        }
        return Merged(result.sortedBy { it.startedAt }, added)
    }

    /**
     * Sessions and user exercises / custom activities by id, saved ids as a union, day plans per date
     * (a date already planned here is kept), preferences replaced. The state keeps its own version and
     * every Health Connect bookkeeping field; imported sessions carry no health sync version.
     */
    fun workouts(current: WorkoutPersistedState, incoming: PortableWorkouts): WorkoutMerge {
        val sessionIds = current.completedSessions.mapTo(HashSet()) { it.id }
        val burnDays = current.completedSessions.filter { it.caloriesBurned != null }.mapTo(HashSet()) { it.diaryDateKey }
        val sessions = mutableListOf<WorkoutSession>()
        for (session in incoming.sessions) {
            if (session.id in sessionIds) continue
            if (session.caloriesBurned != null) {
                // The calculated daily burn is one per day; a burn the user deleted stays deleted.
                if (session.id.toString() in current.healthDeletionTombstones) continue
                if (!burnDays.add(session.diaryDateKey)) continue
            }
            sessionIds += session.id
            sessions += session.copy(healthSyncVersion = null)
        }

        fun newExercises(existing: List<PlannedExercise>, rows: List<PlannedExercise>): List<PlannedExercise> {
            val ids = existing.mapTo(HashSet()) { it.id }
            return rows.filter { ids.add(it.id) }
        }
        val userExercises = newExercises(current.userExercises, incoming.userExercises)
        val customActivities = newExercises(current.customActivities, incoming.customActivities)

        val plans = current.dayPlans.toMutableMap()
        var plansAdded = 0
        for ((day, exercises) in incoming.dayPlans) {
            if (plans[day]?.exercises?.isNotEmpty() == true) continue
            plans[day] = WorkoutDayPlan(dateKey = day, exercises = exercises)
            plansAdded++
        }

        val next = current.copy(
            dayPlans = plans,
            completedSessions = current.completedSessions + sessions,
            savedExerciseIds = current.savedExerciseIds + incoming.savedExerciseIds,
            customActivities = current.customActivities + customActivities,
            userExercises = current.userExercises + userExercises,
            preferences = incoming.preferences ?: current.preferences
        )
        return WorkoutMerge(next, sessions.size, userExercises.size, plansAdded)
    }
}

/**
 * The writes an import makes, one seam so the order and the "no Health write" rule are testable
 * without DataStore. Nothing here ever deletes a row or reaches Health Connect.
 */
interface PortableDataStore {
    suspend fun updateWeights(transform: (List<WeightEntry>) -> List<WeightEntry>)
    suspend fun updateBodyFat(transform: (List<BodyFatEntry>) -> List<BodyFatEntry>)
    suspend fun updateBodyMeasurements(transform: (List<BodyMeasurement>) -> List<BodyMeasurement>)
    suspend fun updateFastingSessions(transform: (List<FastingSession>) -> List<FastingSession>)
    suspend fun updateWorkoutState(transform: (WorkoutPersistedState) -> WorkoutPersistedState)

    /** Writes only the non-null values; the goals map is merged onto the goals already here. */
    suspend fun applySettings(settings: PortableSettings)

    suspend fun saveProfile(profile: UserProfile)

    /** Keeps the profile's weight / body fat equal to the newest log entry, as adding an entry does. */
    suspend fun syncProfileToLatestLogs(weights: Boolean, bodyFat: Boolean)
}

/** Imports one `ayuvo-portable-data` file: parse, validate, merge, then the profile last. */
class PortableDataImporter(
    private val store: PortableDataStore,
    private val zone: ZoneId = ZoneId.systemDefault()
) {
    /** @throws PortableRefusedException for non-JSON, another format or a newer `format_version` (nothing is written). */
    suspend fun import(text: String): PortableImportResult =
        when (val parsed = PortableFormat.parse(text, zone)) {
            is PortableParse.Ok -> apply(parsed.document)
            is PortableParse.Refused -> throw PortableRefusedException(parsed.reason)
        }

    suspend fun apply(document: PortableDocument): PortableImportResult {
        var weights = 0
        var bodyFat = 0
        var measurements = 0
        var fasting = 0

        if (document.weights.isNotEmpty()) {
            store.updateWeights { current -> PortableMerge.weights(current, document.weights).also { weights = it.added }.items }
        }
        if (document.bodyFat.isNotEmpty()) {
            store.updateBodyFat { current -> PortableMerge.bodyFat(current, document.bodyFat).also { bodyFat = it.added }.items }
        }
        if (document.bodyMeasurements.isNotEmpty()) {
            store.updateBodyMeasurements { current ->
                PortableMerge.measurements(current, document.bodyMeasurements).also { measurements = it.added }.items
            }
        }
        if (document.fastingSessions.isNotEmpty()) {
            store.updateFastingSessions { current ->
                PortableMerge.fasting(current, document.fastingSessions).also { fasting = it.added }.items
            }
        }
        var sessions = 0
        var userExercises = 0
        var dayPlans = 0
        document.workouts?.let { incoming ->
            store.updateWorkoutState { current ->
                val merge = PortableMerge.workouts(current, incoming)
                sessions = merge.sessionsAdded
                userExercises = merge.userExercisesAdded
                dayPlans = merge.dayPlansAdded
                merge.state
            }
        }

        // A file without a profile leaves the device's alone, but new log entries still move it to the newest one.
        if (document.profile == null && (weights > 0 || bodyFat > 0)) {
            store.syncProfileToLatestLogs(weights = weights > 0, bodyFat = bodyFat > 0)
        }
        if (document.settings.count > 0) store.applySettings(document.settings)

        // Last, so the "sync the profile to the latest entry" side effect of the logs above can't undo it.
        document.profile?.let { store.saveProfile(it) }

        return PortableImportResult(
            appliedProfile = document.profile != null,
            settings = document.settings.count,
            weights = weights,
            bodyFat = bodyFat,
            bodyMeasurements = measurements,
            fastingSessions = fasting,
            workoutSessions = sessions,
            userExercises = userExercises,
            dayPlans = dayPlans
        )
    }
}

/** The app's stores: every write goes through DataStore helpers that keep an unreadable blob, never the wiping restore. */
class PreferencesPortableStore(
    private val prefs: PreferencesStore,
    private val weightRepository: WeightRepository,
    private val bodyFatRepository: BodyFatRepository,
    private val workoutRepository: WorkoutRepository
) : PortableDataStore {
    override suspend fun updateWeights(transform: (List<WeightEntry>) -> List<WeightEntry>) {
        prefs.updateWeightEntries(transform)
    }

    override suspend fun updateBodyFat(transform: (List<BodyFatEntry>) -> List<BodyFatEntry>) {
        prefs.updateBodyFatEntries(transform)
    }

    override suspend fun updateBodyMeasurements(transform: (List<BodyMeasurement>) -> List<BodyMeasurement>) {
        prefs.updateBodyMeasurements(transform)
    }

    override suspend fun updateFastingSessions(transform: (List<FastingSession>) -> List<FastingSession>) {
        prefs.updateFastingSessions(transform)
    }

    override suspend fun updateWorkoutState(transform: (WorkoutPersistedState) -> WorkoutPersistedState) {
        workoutRepository.importPortable(transform)
    }

    override suspend fun applySettings(settings: PortableSettings) {
        settings.heightUnit?.let { prefs.setHeightUnit(it) }
        settings.weightUnit?.let { prefs.setWeightUnit(it) }
        settings.waterUnit?.let { prefs.setWaterUnit(WaterUnit.fromStorage(it)) }
        settings.glucoseUnit?.let { prefs.setHealthGlucoseUnit(it) }
        settings.weekStartsOnMonday?.let { prefs.setWeekStartsOnMonday(it) }
        settings.dailyStepGoal?.let { prefs.setDailyStepGoal(it) }
        settings.appearanceMode?.let { prefs.setAppearanceMode(it) }
        settings.appThemeColor?.let { prefs.setAppThemeColor(it) }
        settings.adaptiveGoalsEnabled?.let { prefs.setAdaptiveGoalsEnabled(it) }
        settings.preferGramsByDefault?.let { prefs.setPreferGramsByDefault(it) }
        settings.mealSchedule?.let { prefs.setMealSchedule(it) }
        settings.summaryFavourites?.let { prefs.setSummaryFavourites(it.joinToString(",")) }
        if (settings.optionalNutrientGoals.isNotEmpty()) {
            prefs.setOptionalNutrientGoals(mergedGoals(prefs.optionalNutrientGoals.first(), settings.optionalNutrientGoals))
        }
        settings.waterTrackingEnabled?.let { prefs.setWaterTrackingEnabled(it) }
        settings.waterDailyGoalMl?.let { prefs.setWaterDailyGoalMl(it) }
        settings.fastingTrackingEnabled?.let { prefs.setFastingTrackingEnabled(it) }
        settings.fastingDefaultGoalMinutes?.let { prefs.setFastingDefaultGoalMinutes(it) }
        settings.fastingGoalNotificationEnabled?.let { prefs.setFastingGoalNotificationEnabled(it) }
    }

    override suspend fun saveProfile(profile: UserProfile) {
        prefs.setUserProfile(profile)
    }

    override suspend fun syncProfileToLatestLogs(weights: Boolean, bodyFat: Boolean) {
        // replaceAll re-saves the list and moves the profile's weight / body fat to the newest entry; no Health write.
        if (weights) weightRepository.replaceAll(prefs.weightEntries.first())
        if (bodyFat) bodyFatRepository.replaceAll(prefs.bodyFatEntries.first())
    }

    companion object {
        fun mergedGoals(current: OptionalNutrientGoals, incoming: Map<OptionalNutrient, Int>): OptionalNutrientGoals =
            incoming.entries.fold(current) { goals, (nutrient, value) -> goals.withValue(nutrient, value) }
    }
}
