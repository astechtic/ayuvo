package com.ayuvo.health.export

import com.ayuvo.health.data.PreferencesStore
import com.ayuvo.health.data.WorkoutRepository
import com.ayuvo.health.models.OptionalNutrient
import com.ayuvo.health.models.OptionalNutrientGoals
import com.ayuvo.health.models.SupplementalNutrient
import com.ayuvo.health.models.WorkoutPersistedState
import com.ayuvo.health.models.WorkoutPreferences
import kotlinx.coroutines.flow.first
import java.time.Instant
import java.time.ZoneId

/** The section's JSON and the manifest counts that go with it. */
class PortableExport(val json: String, val counts: Map<String, Long>)

/**
 * Builds the `ayuvo-portable-data` section from the stores (docs/portable-data.md). [build] and
 * [workoutsOf] are pure so the format can be tested without Android; [export] only reads.
 */
class PortableDataExporter(
    private val prefs: PreferencesStore,
    private val workouts: WorkoutRepository
) {
    /** Null when there is nothing worth carrying (no profile, no logs, only default settings). */
    suspend fun export(appVersion: String, createdAt: Instant = Instant.now()): PortableExport? =
        build(collect(appVersion, createdAt))

    suspend fun collect(appVersion: String, createdAt: Instant = Instant.now()): PortableDocument {
        val goals = prefs.optionalNutrientGoals.first()
        return PortableDocument(
            createdAt = createdAt,
            platform = PortableFormat.PLATFORM,
            appVersion = appVersion,
            profile = prefs.userProfile.first(),
            settings = PortableSettings(
                heightUnit = prefs.heightUnit.first(),
                weightUnit = prefs.weightUnit.first(),
                waterUnit = prefs.waterUnit.first().storageValue,
                glucoseUnit = prefs.healthGlucoseUnit.first(),
                weekStartsOnMonday = prefs.weekStartsOnMonday.first(),
                dailyStepGoal = prefs.dailyStepGoal.first(),
                appearanceMode = prefs.appearanceMode.first(),
                appThemeColor = prefs.appThemeColor.first(),
                adaptiveGoalsEnabled = prefs.adaptiveGoalsEnabled.first(),
                preferGramsByDefault = prefs.preferGramsByDefault.first(),
                mealSchedule = prefs.mealSchedule.first(),
                // Comma-separated natively; null until the favourites were first migrated.
                summaryFavourites = prefs.summaryFavourites.first()?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() },
                optionalNutrientGoals = goalsToMap(goals),
                waterTrackingEnabled = prefs.waterTrackingEnabled.first(),
                waterDailyGoalMl = prefs.waterDailyGoalMl.first(),
                fastingTrackingEnabled = prefs.fastingTrackingEnabled.first(),
                fastingDefaultGoalMinutes = prefs.fastingDefaultGoalMinutes.first(),
                fastingGoalNotificationEnabled = prefs.fastingGoalNotificationEnabled.first()
            ),
            weights = prefs.weightEntries.first().sortedBy { it.date },
            bodyFat = prefs.bodyFatEntries.first().sortedBy { it.date },
            bodyMeasurements = prefs.bodyMeasurements.first().sortedBy { it.date },
            fastingSessions = prefs.fastingSessions.first().sortedBy { it.startedAt },
            workouts = workoutsOf(workouts.snapshot())
        )
    }

    companion object {
        /** Null when [document] carries nothing; otherwise the encoded file and its manifest counts. */
        fun build(document: PortableDocument, zone: ZoneId = ZoneId.systemDefault()): PortableExport? {
            if (!hasContent(document)) return null
            val workouts = document.workouts
            val counts = linkedMapOf(
                "weights" to document.weights.size.toLong(),
                "body_fat" to document.bodyFat.size.toLong(),
                "body_measurements" to document.bodyMeasurements.size.toLong(),
                "fasting_sessions" to document.fastingSessions.size.toLong(),
                "workout_sessions" to (workouts?.sessions?.size ?: 0).toLong(),
                "user_exercises" to (workouts?.userExercises?.size ?: 0).toLong(),
                "settings" to document.settings.count.toLong()
            ).filterValues { it > 0L }
            return PortableExport(PortableFormat.encode(document, zone), counts)
        }

        fun hasContent(document: PortableDocument): Boolean =
            document.profile != null ||
                document.weights.isNotEmpty() ||
                document.bodyFat.isNotEmpty() ||
                document.bodyMeasurements.isNotEmpty() ||
                document.fastingSessions.isNotEmpty() ||
                document.workouts != null ||
                (document.settings.count > 0 && document.settings != PortableSettings.Defaults)

        /** Health Connect bookkeeping, photos and timers stay behind; a diary with nothing in it is not carried. */
        fun workoutsOf(state: WorkoutPersistedState): PortableWorkouts? {
            val plans = state.dayPlans.entries
                .filter { it.value.exercises.isNotEmpty() }
                .associate { it.key to it.value.exercises }
            val carried = PortableWorkouts(
                preferences = state.preferences.takeUnless { it == WorkoutPreferences() },
                savedExerciseIds = state.savedExerciseIds,
                userExercises = state.userExercises,
                customActivities = state.customActivities,
                dayPlans = plans,
                sessions = state.completedSessions
            )
            return carried.takeUnless { it.isEmpty && it.preferences == null }
        }

        private val supplements = SupplementalNutrient.entries.associateBy { it.optionalNutrient }

        /** Base nutrients always, supplements only when the user set one; empty while every goal is a default. */
        fun goalsToMap(goals: OptionalNutrientGoals): Map<OptionalNutrient, Int> {
            if (goals == OptionalNutrientGoals.Default) return emptyMap()
            return OptionalNutrient.entries.mapNotNull { nutrient ->
                val supplement = supplements[nutrient]
                if (supplement != null && supplement.storageKey !in goals.supplementalNutrients) null
                else nutrient to goals.valueFor(nutrient)
            }.toMap()
        }
    }
}
