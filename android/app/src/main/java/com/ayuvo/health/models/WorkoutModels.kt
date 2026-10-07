package com.ayuvo.health.models

import com.ayuvo.health.data.ExerciseItem
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.Duration
import java.time.LocalDate
import java.util.Locale
import java.util.UUID
import kotlin.math.ceil
import kotlin.math.roundToInt
import androidx.annotation.StringRes
import com.ayuvo.health.R
import com.ayuvo.health.l10n.AppText

@Serializable
enum class WorkoutTabMode {
    LIBRARY,
    LOG;

    companion object {
        val Default: WorkoutTabMode = LOG
    }
}

@Serializable
enum class WorkoutWeightUnit(val storageValue: String) {
    KG("kg"),
    LBS("lbs");

    companion object {
        fun fromStorage(value: String?): WorkoutWeightUnit =
            entries.firstOrNull { it.storageValue == value } ?: LBS
    }
}

object WorkoutDate {
    fun key(date: LocalDate): String = date.toString()

    fun parse(key: String): LocalDate? = runCatching { LocalDate.parse(key) }.getOrNull()

    fun requireKey(key: String): String =
        requireNotNull(parse(key)) { "Invalid workout date key: $key" }.toString()
}

@Serializable
enum class WorkoutRpeScale {
    STRENGTH,
    CR10,
    BORG;

    val title: String
        get() = when (this) {
            STRENGTH -> "Strength 1–10"
            CR10 -> "CR10 0–10"
            BORG -> "Borg 6–20"
        }

    /** Localized [title]; [title] stays English for Coach and exports. */
    @get:StringRes
    val titleRes: Int
        get() = when (this) {
            STRENGTH -> R.string.core_workout_rpe_strength
            CR10 -> R.string.core_workout_rpe_cr10
            BORG -> R.string.core_workout_rpe_borg
        }

    @get:StringRes
    val shortTitleRes: Int
        get() = when (this) {
            STRENGTH -> R.string.core_workout_rpe_short_strength
            CR10 -> R.string.core_workout_rpe_short_cr10
            BORG -> R.string.core_workout_rpe_short_borg
        }

    val shortTitle: String
        get() = when (this) {
            STRENGTH -> "1–10"
            CR10 -> "CR10"
            BORG -> "Borg"
        }

    val inputPlaceholder: String
        get() = when (this) {
            STRENGTH -> "1–10"
            CR10 -> "0–10"
            BORG -> "6–20"
        }

    val allowsDecimalInput: Boolean get() = this != BORG

    val inputRange: ClosedFloatingPointRange<Double>
        get() = when (this) {
            STRENGTH -> 1.0..10.0
            CR10 -> 0.0..10.0
            BORG -> 6.0..20.0
        }

    /**
     * Matches iOS's in-progress RPE sanitizer, including values such as `7.`
     * while the user is still typing and a single fractional digit where valid.
     */
    fun sanitize(proposedValue: String, previousValue: String = ""): String {
        val normalized = proposedValue.trim().replace(',', '.')
        if (normalized.isEmpty()) return ""

        val filtered = StringBuilder()
        var hasDecimal = false
        var fractionalDigits = 0
        for (character in normalized) {
            when {
                character.isDigit() -> {
                    if (hasDecimal) {
                        if (!allowsDecimalInput || fractionalDigits >= 1) continue
                        fractionalDigits += 1
                    }
                    filtered.append(character)
                }
                character == '.' && allowsDecimalInput && !hasDecimal && filtered.isNotEmpty() -> {
                    hasDecimal = true
                    filtered.append(character)
                }
            }
        }
        if (filtered.isEmpty()) return previousValue

        val result = filtered.toString()
        val numericText = result.removeSuffix(".")
        val value = numericText.toDoubleOrNull() ?: return previousValue
        if (value > inputRange.endInclusive) return inputRange.endInclusive.toInt().toString()
        if (value < inputRange.start && !isPossibleRangePrefix(result)) return previousValue
        return result
    }

    private fun isPossibleRangePrefix(value: String): Boolean {
        val integerPrefix = value.substringBefore('.')
        if (integerPrefix.isEmpty()) return false
        val lower = ceil(inputRange.start).toInt()
        val upper = inputRange.endInclusive.toInt()
        return (lower..upper).any { it.toString().startsWith(integerPrefix) }
    }
}

@Serializable
enum class WorkoutSplit {
    PUSH_PULL_LEGS,
    UPPER_LOWER,
    BODY_PART,
    ARNOLD,
    PUSH_PULL,
    ANTAGONIST,
    HYBRID,
    FULL_BODY,
    CUSTOM;

    val title: String
        get() = when (this) {
            PUSH_PULL_LEGS -> "Push / Pull / Legs"
            UPPER_LOWER -> "Upper / Lower"
            BODY_PART -> "Body-part split"
            ARNOLD -> "Arnold split"
            PUSH_PULL -> "Push / Pull"
            ANTAGONIST -> "Antagonist split"
            HYBRID -> "Hybrid split"
            FULL_BODY -> "Full body"
            CUSTOM -> "Custom"
        }

    /** Localized [title]; [title] stays English for Coach. */
    @get:StringRes
    val titleRes: Int
        get() = when (this) {
            PUSH_PULL_LEGS -> R.string.core_workout_split_push_pull_legs
            UPPER_LOWER -> R.string.core_workout_split_upper_lower
            BODY_PART -> R.string.core_workout_split_body_part
            ARNOLD -> R.string.core_workout_split_arnold
            PUSH_PULL -> R.string.core_workout_split_push_pull
            ANTAGONIST -> R.string.core_workout_split_antagonist
            HYBRID -> R.string.core_workout_split_hybrid
            FULL_BODY -> R.string.core_workout_split_full_body
            CUSTOM -> R.string.core_workout_split_custom
        }

    companion object {
        val SelectableValues: List<WorkoutSplit> = listOf(
            FULL_BODY,
            UPPER_LOWER,
            PUSH_PULL_LEGS,
            BODY_PART,
            ARNOLD,
            PUSH_PULL,
            ANTAGONIST,
            HYBRID
        )
    }
}

@Serializable
/** [title] is English (Coach, storage); show [titleRes]. */
enum class WorkoutIssue(val title: String, @StringRes val titleRes: Int) {
    SHOULDER("Shoulder", R.string.core_workout_issue_shoulder),
    ELBOW("Elbow", R.string.core_workout_issue_elbow),
    WRIST("Wrist", R.string.core_workout_issue_wrist),
    LOWER_BACK("Lower back", R.string.core_workout_issue_lower_back),
    HIP("Hip", R.string.core_workout_issue_hip),
    KNEE("Knee", R.string.core_workout_issue_knee),
    ANKLE("Ankle", R.string.core_workout_issue_ankle),
    OTHER("Other", R.string.core_workout_issue_other)
}

@Serializable
data class WorkoutStrengthNumbers(
    val benchPressKg: Double? = null,
    val squatKg: Double? = null,
    val deadliftKg: Double? = null,
    val overheadPressKg: Double? = null
)

@Serializable
data class WorkoutPreferences(
    val targetMuscles: Set<String> = emptySet(),
    val issues: Set<WorkoutIssue> = emptySet(),
    val additionalIssues: String = "",
    val frequencyDays: Int = 3,
    val durationMinutes: Int = 60,
    val split: WorkoutSplit = WorkoutSplit.FULL_BODY,
    val customSplit: String = "",
    val equipment: Set<String> = emptySet(),
    val rpeScale: WorkoutRpeScale = WorkoutRpeScale.STRENGTH,
    val strength: WorkoutStrengthNumbers = WorkoutStrengthNumbers()
) {
    /** Keeps legacy fields decodable while enforcing the final selectable settings. */
    fun sanitized(): WorkoutPreferences = copy(
        additionalIssues = additionalIssues.trim().takeIf { WorkoutIssue.OTHER in issues }.orEmpty(),
        frequencyDays = frequencyDays.coerceIn(1, 7),
        split = if (split == WorkoutSplit.CUSTOM) WorkoutSplit.FULL_BODY else split,
        customSplit = "",
        strength = WorkoutStrengthNumbers(
            benchPressKg = validLoad(strength.benchPressKg),
            squatKg = validLoad(strength.squatKg),
            deadliftKg = validLoad(strength.deadliftKg),
            overheadPressKg = validLoad(strength.overheadPressKg)
        )
    )

    private fun validLoad(value: Double?): Double? =
        value?.takeIf { it.isFinite() && it > 0.0 }
}

@Serializable
data class PlannedSet(
    @Serializable(with = UuidSerializer::class)
    val id: UUID = UUID.randomUUID(),
    val weight: String = "",
    val weightUnit: WorkoutWeightUnit? = null,
    val reps: String = "",
    val rpe: String = "",
    val rpeScale: WorkoutRpeScale? = null,
    /**
     * The set row's ✓. null = not set yet: a new blank set, or one saved before ticks existed,
     * when entering reps alone made it done. Read it through [isCompleted].
     */
    val completed: Boolean? = null
) {
    /** Only ticked sets count toward performed sets, volume, burn and statistics. */
    val isCompleted: Boolean get() = completed ?: reps.isNotBlank()

    val hasLoggedValue: Boolean
        get() = weight.isNotBlank() || reps.isNotBlank() || rpe.isNotBlank()

    fun blankCopy(carryingWeight: Boolean = false): PlannedSet = PlannedSet(
        weight = if (carryingWeight) weight else "",
        weightUnit = if (carryingWeight) weightUnit else null
    )

    /** New sets inherit weight, unit, and reps from the set above as a prefill; RPE stays blank and the set starts unticked. */
    fun copyingFromPrevious(): PlannedSet = PlannedSet(
        weight = weight,
        weightUnit = weightUnit,
        reps = reps,
        completed = false
    )

    fun displayWeight(targetUnit: WorkoutWeightUnit): String {
        val sourceUnit = weightUnit ?: return weight
        val numericWeight = weight.replace(',', '.').toDoubleOrNull()
            ?.takeIf { it.isFinite() } ?: return weight
        if (sourceUnit == targetUnit) return weight

        val poundsPerKilogram = 2.204_622_621_8
        val converted = if (sourceUnit == WorkoutWeightUnit.KG) {
            numericWeight * poundsPerKilogram
        } else {
            numericWeight / poundsPerKilogram
        }
        return String.format(Locale.US, "%.2f", converted)
            .trimEnd('0')
            .trimEnd('.')
    }
}

@Serializable
/** [title] is English (Coach, storage); show [titleRes]. */
enum class WorkoutIntensity(val title: String, @StringRes val titleRes: Int) {
    LIGHT("Light", R.string.core_workout_intensity_light),
    MODERATE("Moderate", R.string.core_workout_intensity_moderate),
    VIGOROUS("Vigorous", R.string.core_workout_intensity_vigorous)
}

/** Persist only transitions; wall-clock anchors keep timers alive across process death. */
@Serializable
data class ExerciseTimer(
    val accumulatedSeconds: Double = 0.0,
    @Serializable(with = InstantSerializer::class)
    val runningSince: Instant? = null,
    val savedDurationSeconds: Double? = null,
    val intensity: WorkoutIntensity = WorkoutIntensity.MODERATE
) {
    val isRunning: Boolean get() = runningSince != null
    val isSaved: Boolean
        get() = !isRunning && savedDurationSeconds?.let { it.isFinite() && it > 0.0 } == true
    val savedSeconds: Double
        get() = if (isSaved) savedDurationSeconds?.takeIf { it.isFinite() && it > 0.0 } ?: 0.0 else 0.0

    fun elapsedSeconds(at: Instant = Instant.now()): Double {
        val accumulated = accumulatedSeconds.takeIf { it.isFinite() && it >= 0.0 } ?: 0.0
        val running = runningSince?.let {
            val elapsed = Duration.between(it, at)
            (elapsed.seconds.toDouble() + elapsed.nano / 1_000_000_000.0).coerceAtLeast(0.0)
        } ?: 0.0
        return accumulated + running
    }

    fun start(at: Instant = Instant.now()): ExerciseTimer =
        if (isRunning) this else copy(runningSince = at, savedDurationSeconds = null)

    fun pause(at: Instant = Instant.now()): ExerciseTimer =
        if (!isRunning) this else copy(accumulatedSeconds = elapsedSeconds(at), runningSince = null)

    fun stop(at: Instant = Instant.now()): ExerciseTimer {
        val duration = elapsedSeconds(at)
        return copy(accumulatedSeconds = duration, runningSince = null, savedDurationSeconds = duration)
    }

    fun restart(at: Instant = Instant.now()): ExerciseTimer = ExerciseTimer(
        runningSince = at,
        intensity = intensity
    )
}

enum class ExerciseTimerAction { START, PAUSE, RESUME, STOP, RESTART, DISCARD }

@Serializable
data class PlannedExercise(
    @Serializable(with = UuidSerializer::class)
    val id: UUID = UUID.randomUUID(),
    val itemId: String,
    val name: String,
    val bodyPart: String,
    val equipment: String,
    val primaryMuscles: List<String>,
    val secondaryMuscles: List<String>,
    val instructions: List<String>,
    /** User-created exercise photo filenames; catalogue exercises use [imageUrl]/[gifUrl]. */
    val imagePaths: List<String> = emptyList(),
    val imageUrl: String? = null,
    val gifUrl: String? = null,
    val sets: List<PlannedSet> = listOf(PlannedSet()),
    val timer: ExerciseTimer? = null
) {
    val isCardio: Boolean
        get() = bodyPart.equals("cardio", ignoreCase = true)

    val hasCalculableWork: Boolean
        get() = (timer?.savedSeconds ?: 0.0) > 0.0 ||
            (!isCardio && sets.any { it.isCompleted && (it.reps.toIntOrNull() ?: 0) > 0 })

    fun copiedForNewDay(includeSetDetails: Boolean = false): PlannedExercise = copy(
        id = UUID.randomUUID(),
        // Details are a prefill for the new day; it hasn't been done yet.
        sets = if (includeSetDetails) sets.map { it.copy(id = UUID.randomUUID(), completed = false) } else listOf(PlannedSet()),
        timer = if (includeSetDetails) timer else null
    )

    fun asExerciseItem(): ExerciseItem = ExerciseItem(
        id = itemId,
        name = name,
        bodyPart = bodyPart,
        equipment = equipment,
        primaryMuscles = primaryMuscles,
        secondaryMuscles = secondaryMuscles,
        instructions = instructions,
        imagePaths = imagePaths,
        imageUrl = imageUrl,
        gifUrl = gifUrl
    )

    companion object {
        fun from(item: ExerciseItem): PlannedExercise = PlannedExercise(
            itemId = item.id,
            name = item.name,
            bodyPart = item.bodyPart,
            equipment = item.equipment,
            primaryMuscles = item.primaryMuscles,
            secondaryMuscles = item.secondaryMuscles,
            instructions = item.instructions,
            imagePaths = item.imagePaths,
            imageUrl = item.imageUrl,
            gifUrl = item.gifUrl
        )
    }
}

@Serializable
data class WorkoutDayPlan(
    val dateKey: String,
    val exercises: List<PlannedExercise> = emptyList()
)

@Serializable
data class CompletedSet(
    @Serializable(with = UuidSerializer::class)
    val id: UUID = UUID.randomUUID(),
    val setNumber: Int,
    val weight: String,
    val weightUnit: WorkoutWeightUnit,
    val reps: String,
    val rpe: String,
    val rpeScale: WorkoutRpeScale? = null,
    /** Whether the set was ticked. null = saved before ticks existed, when every set with reps counted. */
    val completed: Boolean? = null
) {
    /** A set is performed once it is ticked and has reps; load or RPE alone is incomplete. */
    val isPerformed: Boolean get() = completed != false && reps.isNotEmpty()
}

@Serializable
data class CompletedExercise(
    @Serializable(with = UuidSerializer::class)
    val id: UUID = UUID.randomUUID(),
    val itemId: String,
    val name: String,
    val targetMuscles: List<String>,
    val equipment: String,
    val sets: List<CompletedSet>,
    val durationSeconds: Double? = null,
    val intensity: WorkoutIntensity? = null
)

@Serializable
data class WorkoutSession(
    @Serializable(with = UuidSerializer::class)
    val id: UUID = UUID.randomUUID(),
    val diaryDateKey: String,
    @Serializable(with = InstantSerializer::class)
    val startedAt: Instant,
    @Serializable(with = InstantSerializer::class)
    val completedAt: Instant,
    val durationSeconds: Int = 0,
    val exercises: List<CompletedExercise>,
    val caloriesBurned: Int? = null,
    val healthSyncVersion: Int? = null,
    /** Null for diary (strength) sessions; [KIND_GPS] for a recorded outdoor workout (docs/workouts-gps.md). */
    val kind: String? = null,
    /**
     * True when [startedAt]..[completedAt] is the real workout interval: Start/Finish session, a confirmed
     * heart-rate window, or a GPS recording. Legacy daily snapshots stay false (a zero-length point in time).
     */
    val realInterval: Boolean = false,
    /** "session" (Start/Finish), "hr_window" (confirmed suggestion), "manual" (edited times) or "gps". */
    val intervalSource: String? = null,
    val heartRate: WorkoutHeartRateStats? = null,
    val gps: GpsWorkoutSummary? = null
) {
    val isGps: Boolean get() = kind == KIND_GPS && gps != null

    /** Real interval with a positive length (a Health Connect session can be written over it). */
    val hasRealInterval: Boolean get() = realInterval && completedAt.isAfter(startedAt)

    val durationMinutes: Int get() = ceil(durationSeconds.coerceAtLeast(0) / 60.0).toInt()
    val exerciseCount: Int get() = exercises.size
    val performedSetCount: Int get() = exercises.sumOf { exercise -> exercise.sets.count { it.isPerformed } }
    val repCount: Int get() = exercises.sumOf { exercise -> exercise.sets.filter { it.isPerformed }.sumOf { it.reps.toIntOrNull() ?: 0 } }

    /**
     * Session RPE on the CR-10 scale (0–10) for training load (Foster 2001): the mean of the performed sets' logged RPE,
     * each normalized from its own scale. Null when no performed set has an RPE (never the estimator's default).
     */
    val sessionEffortCr10: Double?
        get() {
            var total = 0.0
            var n = 0
            for (exercise in exercises) for (set in exercise.sets) {
                if (!set.isPerformed) continue
                val v = set.rpe.replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() } ?: continue
                val normalized = when (set.rpeScale ?: WorkoutRpeScale.STRENGTH) {
                    WorkoutRpeScale.STRENGTH -> (v - 1.0) / 9.0
                    WorkoutRpeScale.CR10 -> v / 10.0
                    WorkoutRpeScale.BORG -> (v - 6.0) / 14.0
                }
                total += normalized.coerceIn(0.0, 1.0) * 10.0
                n += 1
            }
            return if (n > 0) total / n else null
        }

    /**
     * The training interval in epoch ms: the real interval when there is one, else the saved duration ending at
     * [completedAt] (legacy daily snapshots store started == completed). Null when neither gives a positive length;
     * an unknown duration is never invented.
     */
    val trainingIntervalMs: Pair<Long, Long>?
        get() {
            val end = completedAt.toEpochMilli()
            val start = startedAt.toEpochMilli()
            if (end > start) return start to end
            if (durationSeconds > 0) return (end - durationSeconds * 1000L) to end
            return null
        }

    companion object {
        const val KIND_GPS = "gps"
    }
}

/** Heart rate during a workout (`hr_workout` + `hr_recovery` of scripts/workout_reference.py). Never written to Health. */
@Serializable
data class WorkoutHeartRateStats(
    val avgHr: Double? = null,
    val maxHr: Double? = null,
    val coveragePct: Double = 0.0,
    /** Five entries: below light, light, moderate, vigorous, near-maximal. */
    val zoneSeconds: List<Double> = emptyList(),
    val trimp: Double? = null,
    /** Keytel 2005 energy; only present when heart rate covered at least 70% of the workout. */
    val keytelKcal: Double? = null,
    val zoneMethod: String? = null,
    val restingHr: Double? = null,
    val hrMax: Double? = null,
    val hrr1: Double? = null,
    val hrr1FlagLow: Boolean = false,
    val hrr1Confidence: String? = null
)

@Serializable
data class GpsSplit(val km: Int, val seconds: Double)

@Serializable
data class GpsLap(val index: Int, val startMs: Long, val endMs: Long, val distanceM: Double)

/** Summary of a recorded GPS workout; the route itself lives in `files/workouts/tracks/<session id>.json`. */
@Serializable
data class GpsWorkoutSummary(
    val sport: String,
    val distanceM: Double = 0.0,
    val movingSeconds: Double = 0.0,
    val elapsedSeconds: Double = 0.0,
    val avgSpeedMps: Double? = null,
    val avgPaceSecondsPerKm: Double? = null,
    val maxSpeedMps: Double? = null,
    val splits: List<GpsSplit> = emptyList(),
    val laps: List<GpsLap> = emptyList(),
    val elevationGainM: Double = 0.0,
    val elevationLossM: Double = 0.0,
    /** "barometer" or "gps". */
    val altitudeSource: String? = null,
    val activeKcal: Int? = null,
    /** "keytel" (heart rate covered ≥70%) or "met". */
    val kcalMethod: String? = null,
    val vo2max: Double? = null,
    val vo2maxStatus: String? = null,
    val vo2maxSegments: Int = 0,
    val cooperTest: Boolean = false,
    val cooperVo2max: Double? = null,
    val keptPoints: Int = 0,
    val droppedPoints: Int = 0,
    val healthSynced: Boolean = false,
    val healthSyncVersion: Int = 1
) {
    /** The cardio-fitness value this workout contributes (docs/workouts-gps.md §3). */
    val bestVo2max: Double? get() = cooperVo2max ?: vo2max
}

/** A strength session started with Start session; survives process death until Finish or Cancel. */
@Serializable
data class ActiveStrengthSession(
    val dateKey: String,
    @Serializable(with = InstantSerializer::class)
    val startedAt: Instant
)

@Serializable
data class WorkoutPersistedState(
    val version: Int = CurrentVersion,
    val dayPlans: Map<String, WorkoutDayPlan> = emptyMap(),
    val completedSessions: List<WorkoutSession> = emptyList(),
    val savedExerciseIds: Set<String> = emptySet(),
    val customActivities: List<PlannedExercise> = emptyList(),
    val userExercises: List<PlannedExercise> = emptyList(),
    val preferences: WorkoutPreferences = WorkoutPreferences(),
    val mode: WorkoutTabMode = WorkoutTabMode.Default,
    /**
     * Pending deletes double as tombstones so a health restore cannot resurrect them.
     * The date key is retained because Health Connect deletion is scoped by stable id + day.
     */
    val healthDeletionTombstones: Map<String, String> = emptyMap(),
    /** Deletes awaiting Health Connect confirmation; tombstones outlive a successful write
     * until a subsequent owned-record read proves the sample is no longer visible. */
    val pendingHealthDeleteIds: Set<String> = emptySet(),
    /** Failed/deferred health writes can be retried without losing local calculations. */
    val pendingHealthUpsertIds: Set<String> = emptySet(),
    val activeStrengthSession: ActiveStrengthSession? = null
) {
    fun sanitized(): WorkoutPersistedState = if (version != CurrentVersion) {
        WorkoutPersistedState()
    } else {
        copy(preferences = preferences.sanitized())
    }

    companion object {
        /**
         * v2 switched the exercise catalogue to exercises-dataset (numeric ids). Workout
         * state from v1 references the retired free-exercise-db ids and is discarded.
         */
        const val CurrentVersion = 2
    }
}

data class WorkoutBurnEstimate(
    val calories: Int,
    val performedSetCount: Int,
    val repCount: Int
)

/** Saved exercise timers replace that exercise's rep-based estimate. */
object WorkoutBurnEstimator {
    fun estimate(
        exercises: List<PlannedExercise>,
        bodyWeightKg: Double,
        defaultWeightUnit: WorkoutWeightUnit,
        defaultRpeScale: WorkoutRpeScale
    ): WorkoutBurnEstimate? {
        val safeBodyWeight = if (bodyWeightKg.isFinite()) bodyWeightKg.coerceIn(35.0, 300.0) else 70.0
        var performedSetCount = 0
        var repCount = 0
        var activeMinutes = 0.0
        var recoveryMinutes = 0.0
        var effortTotal = 0.0
        var relativeLoadTotal = 0.0
        var exercisesWithWork = 0
        var timedCalories = 0.0
        var strengthSetCount = 0

        for (exercise in exercises) {
            val timedSeconds = exercise.timer?.savedSeconds ?: 0.0
            if (timedSeconds > 0.0 || exercise.isCardio) {
                if (timedSeconds > 0.0) {
                    val intensity = timerIntensity(exercise, defaultRpeScale)
                    val met = timedMet(exercise, intensity)
                    timedCalories += met * 3.5 * safeBodyWeight / 200.0 * (timedSeconds / 60.0)
                }
                // Logged reps remain useful statistics; their estimate must not be added twice.
                exercise.sets.forEach { set ->
                    if (!set.isCompleted) return@forEach
                    val reps = set.reps.toIntOrNull()?.takeIf { it > 0 } ?: return@forEach
                    performedSetCount += 1
                    repCount += reps.coerceAtMost(100)
                }
                continue
            }
            var performedInExercise = 0
            for (set in exercise.sets) {
                if (!set.isCompleted) continue
                val rawReps = set.reps.toIntOrNull()?.takeIf { it > 0 } ?: continue
                val reps = rawReps.coerceAtMost(100)
                performedSetCount += 1
                strengthSetCount += 1
                performedInExercise += 1
                repCount += reps
                activeMinutes += (reps * 2.75 / 60.0).coerceIn(0.30, 1.50)
                recoveryMinutes += 1.60
                effortTotal += normalizedEffort(set.rpe, set.rpeScale ?: defaultRpeScale)
                relativeLoadTotal += relativeLoad(
                    text = set.weight,
                    unit = set.weightUnit ?: defaultWeightUnit,
                    bodyWeightKg = safeBodyWeight
                )
            }
            if (performedInExercise > 0) exercisesWithWork += 1
        }

        if (strengthSetCount == 0 && timedCalories <= 0.0) return null

        recoveryMinutes = (recoveryMinutes - 1.60).coerceAtLeast(0.0)
        val transitionMinutes = exercisesWithWork * 0.75
        val estimatedMinutes = (activeMinutes + recoveryMinutes + transitionMinutes).coerceAtLeast(4.0)
        val averageEffort = effortTotal / strengthSetCount.coerceAtLeast(1)
        val averageRelativeLoad = relativeLoadTotal / strengthSetCount.coerceAtLeast(1)
        val met = (3.8 + (2.4 * averageEffort) + (0.5 * averageRelativeLoad)).coerceIn(3.5, 8.0)
        val strengthCalories = if (strengthSetCount > 0) met * 3.5 * safeBodyWeight / 200.0 * estimatedMinutes else 0.0
        val rawCalories = strengthCalories + timedCalories

        return WorkoutBurnEstimate(
            calories = rawCalories.roundToInt().coerceIn(1, 5_000),
            performedSetCount = performedSetCount,
            repCount = repCount
        )
    }

    /** RPE drives timed effort; missing RPE preserves legacy effort or the moderate default. */
    fun timerIntensity(exercise: PlannedExercise, defaultRpeScale: WorkoutRpeScale): WorkoutIntensity {
        val efforts = exercise.sets.mapNotNull { set ->
            set.rpe.replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() } ?: return@mapNotNull null
            normalizedEffort(set.rpe, set.rpeScale ?: defaultRpeScale)
        }
        if (efforts.isEmpty()) return exercise.timer?.intensity ?: WorkoutIntensity.MODERATE
        return when {
            efforts.average() < 0.4 -> WorkoutIntensity.LIGHT
            efforts.average() >= 0.75 -> WorkoutIntensity.VIGOROUS
            else -> WorkoutIntensity.MODERATE
        }
    }

    /**
     * Representative effort estimates from the 2024 Adult Compendium of Physical Activities:
     * https://pacompendium.com/{bicycling,walking,running,sports,conditioning-exercise}/
     * Effort labels are approximate; pace, watts, and individual efficiency are not measured.
     */
    private fun timedMet(exercise: PlannedExercise, intensity: WorkoutIntensity): Double {
        val values = if (exercise.isCardio) {
            // Catalogue ids from shared/exercises/exercises.json.
            when (exercise.itemId) {
                "2138", "0798" -> listOf(3.5, 6.0, 10.8) // stationary bike run / walk
                "3666", "Walking_Outdoor" -> listOf(2.8, 3.8, 4.8) // incline treadmill walk
                "0685", "0684", "3656", "Running_Outdoor" -> listOf(6.5, 8.5, 10.5) // run
                "2612" -> listOf(8.3, 11.8, 12.3) // jump rope
                "2311" -> listOf(4.5, 6.8, 9.3) // walking on stepmill
                "2141" -> listOf(5.0, 5.0, 9.0) // elliptical cross trainer
                else -> listOf(3.5, 5.0, 7.5)
            }
        } else {
            listOf(3.5, 5.0, 6.0)
        }
        return values[intensity.ordinal]
    }

    private fun normalizedEffort(text: String, scale: WorkoutRpeScale): Double {
        val value = text.replace(',', '.').toDoubleOrNull() ?: return 0.60
        val normalized = when (scale) {
            WorkoutRpeScale.STRENGTH -> (value - 1.0) / 9.0
            WorkoutRpeScale.CR10 -> value / 10.0
            WorkoutRpeScale.BORG -> (value - 6.0) / 14.0
        }
        return normalized.coerceIn(0.0, 1.0)
    }

    private fun relativeLoad(text: String, unit: WorkoutWeightUnit, bodyWeightKg: Double): Double {
        val value = text.toDoubleOrNull()?.takeIf { it.isFinite() && it > 0.0 } ?: return 0.0
        val kilograms = if (unit == WorkoutWeightUnit.KG) value else value / 2.204_622_621_8
        return (kilograms / bodyWeightKg).coerceIn(0.0, 2.0)
    }
}

/** [title] is the English key (saved selections, Coach); show [titleRes] when set, else [title] (a muscle name). */
data class WorkoutSplitGroup(
    val title: String,
    val muscles: Set<String>,
    @StringRes val titleRes: Int? = null
) {
    companion object {
        // Muscle vocabulary of the exercises-dataset catalogue (target + secondary muscles).
        private val chest = arrayOf("Chest", "Pectorals", "Upper Chest", "Serratus Anterior")
        private val back = arrayOf("Lats", "Upper Back", "Back", "Rhomboids", "Spine")
        private val lowerBack = arrayOf("Lower Back")
        private val traps = arrayOf("Traps", "Levator Scapulae")
        private val shoulders = arrayOf("Shoulders", "Delts", "Rear Deltoids", "Rotator Cuff")
        private val biceps = arrayOf("Biceps", "Brachialis")
        private val triceps = arrayOf("Triceps")
        private val forearms = arrayOf("Forearms", "Wrist Extensors", "Wrist Flexors", "Wrists", "Grip Muscles", "Hands")
        private val neck = arrayOf("Neck", "Sternocleidomastoid")
        private val quads = arrayOf("Quadriceps", "Quads")
        private val posteriorLegs = arrayOf("Hamstrings", "Glutes")
        private val calves = arrayOf("Calves", "Soleus", "Shins", "Ankles", "Ankle Stabilizers", "Feet")
        private val hips = arrayOf("Abductors", "Adductors", "Inner Thighs", "Groin", "Hip Flexors")
        private val core = arrayOf("Abs", "Abdominals", "Core", "Obliques", "Lower Abs")
        private val cardio = arrayOf("Cardiovascular System")

        fun groups(split: WorkoutSplit, availableMuscles: List<String>): List<WorkoutSplitGroup> {
            val namesByLowercase = availableMuscles.associateBy { it.lowercase() }
            fun matching(vararg candidates: Array<String>): Set<String> =
                candidates.flatMap { it.asList() }.mapNotNull { namesByLowercase[it.lowercase()] }.toSet()

            fun group(title: String, @StringRes titleRes: Int, muscles: Set<String>) = WorkoutSplitGroup(title, muscles, titleRes)

            return when (split) {
                WorkoutSplit.PUSH_PULL_LEGS -> listOf(
                    group("Push", R.string.core_workout_group_push, matching(chest, shoulders, triceps)),
                    group("Pull", R.string.core_workout_group_pull, matching(biceps, forearms, back, traps, neck)),
                    group("Legs", R.string.core_workout_group_legs, matching(hips, calves, posteriorLegs, lowerBack, quads)),
                    group("Core", R.string.core_workout_group_core, matching(core)),
                    group("Cardio", R.string.core_workout_group_cardio, matching(cardio))
                )
                WorkoutSplit.UPPER_LOWER -> listOf(
                    group("Upper", R.string.core_workout_group_upper, matching(biceps, chest, forearms, back, neck, shoulders, traps, triceps)),
                    group("Lower", R.string.core_workout_group_lower, matching(hips, calves, posteriorLegs, lowerBack, quads)),
                    group("Core", R.string.core_workout_group_core, matching(core)),
                    group("Cardio", R.string.core_workout_group_cardio, matching(cardio))
                )
                WorkoutSplit.BODY_PART -> listOf(
                    group("Chest", R.string.core_workout_group_chest, matching(chest)),
                    group("Back", R.string.core_workout_group_back, matching(back, lowerBack, traps)),
                    group("Shoulders", R.string.core_workout_group_shoulders, matching(shoulders, traps)),
                    group("Arms", R.string.core_workout_group_arms, matching(biceps, triceps, forearms)),
                    group("Legs", R.string.core_workout_group_legs, matching(hips, calves, posteriorLegs, quads)),
                    group("Core", R.string.core_workout_group_core, matching(core)),
                    group("Cardio", R.string.core_workout_group_cardio, matching(cardio))
                )
                WorkoutSplit.ARNOLD -> listOf(
                    group("Chest + Back", R.string.core_workout_group_chest_back, matching(chest, back, lowerBack, traps)),
                    group("Shoulders + Arms", R.string.core_workout_group_shoulders_arms, matching(shoulders, biceps, triceps, forearms, neck)),
                    group("Legs", R.string.core_workout_group_legs, matching(hips, calves, posteriorLegs, quads)),
                    group("Core", R.string.core_workout_group_core, matching(core))
                )
                WorkoutSplit.PUSH_PULL -> listOf(
                    group("Push", R.string.core_workout_group_push, matching(chest, shoulders, triceps, quads, calves)),
                    group("Pull", R.string.core_workout_group_pull, matching(biceps, forearms, back, traps, posteriorLegs, lowerBack)),
                    group("Accessory/Core", R.string.core_workout_group_accessory_core, matching(core, hips, neck))
                )
                WorkoutSplit.ANTAGONIST -> listOf(
                    group("Chest + Back", R.string.core_workout_group_chest_back, matching(chest, back, lowerBack, traps)),
                    group("Biceps + Triceps", R.string.core_workout_group_biceps_triceps, matching(biceps, triceps, forearms)),
                    group("Quads + Hamstrings/Glutes", R.string.core_workout_group_quads_hamstrings_glutes, matching(quads, posteriorLegs)),
                    group("Shoulders + Lats/Traps", R.string.core_workout_group_shoulders_lats_traps, matching(shoulders, arrayOf("Lats"), traps)),
                    group("Core/Accessory", R.string.core_workout_group_core_accessory, matching(core, hips, calves, neck))
                )
                WorkoutSplit.HYBRID -> listOf(
                    group("Strength/Compound", R.string.core_workout_group_strength_compound, matching(chest, back, lowerBack, posteriorLegs, quads, shoulders, traps)),
                    group("Accessory/Hypertrophy", R.string.core_workout_group_accessory_hypertrophy, matching(biceps, triceps, forearms, calves, hips, core, neck))
                )
                WorkoutSplit.FULL_BODY, WorkoutSplit.CUSTOM -> emptyList()
            }
        }

        fun selectionGroups(
            split: WorkoutSplit,
            availablePrimaryMuscles: List<String>,
            availableSecondaryMuscles: List<String>
        ): List<WorkoutSplitGroup> {
            val available = (availablePrimaryMuscles + availableSecondaryMuscles).toSet().sorted()
            val configured = groups(split, available).filter { it.muscles.isNotEmpty() }
            return configured.ifEmpty { available.map { WorkoutSplitGroup(it, setOf(it)) } }
        }
    }
}

data class ExerciseLiftSet(
    val weight: String,
    val weightUnit: WorkoutWeightUnit?,
    val reps: String
)

data class ExerciseLiftDay(
    val dateKey: String,
    val sets: List<ExerciseLiftSet>
)

object ExerciseLiftHistory {
    fun normalizedName(name: String): String =
        name.trim().lowercase().replace(Regex("\\s+"), " ")

    fun matches(itemId: String, name: String, candidateItemId: String, candidateName: String): Boolean {
        if (itemId.isNotEmpty()) return itemId == candidateItemId
        val left = normalizedName(name)
        val right = normalizedName(candidateName)
        return left.isNotEmpty() && left == right
    }

    fun performedSets(completed: List<CompletedSet>): List<ExerciseLiftSet> =
        completed.filter { it.isPerformed }.map {
            ExerciseLiftSet(
                weight = it.weight.trim(),
                weightUnit = it.weightUnit,
                reps = it.reps.trim()
            )
        }

    fun formatSetLine(set: ExerciseLiftSet, displayUnit: WorkoutWeightUnit): String {
        if (set.reps.isEmpty()) return ""
        if (set.weight.isEmpty()) return AppText.orEnglish("${set.reps} reps", R.string.core_workout_set_reps_only, set.reps)
        val planned = PlannedSet(weight = set.weight, weightUnit = set.weightUnit, reps = set.reps)
        val weight = planned.displayWeight(displayUnit)
        return AppText.orEnglish("$weight ${displayUnit.storageValue} × ${set.reps}", R.string.fu_workout_set_line, weight, displayUnit.storageValue, set.reps)
    }

    fun formatSummary(sets: List<ExerciseLiftSet>, displayUnit: WorkoutWeightUnit): String =
        sets.mapNotNull { line ->
            formatSetLine(line, displayUnit).takeIf { it.isNotEmpty() }
        }.joinToString(", ")

    fun history(
        state: WorkoutPersistedState,
        itemId: String,
        name: String,
        beforeDateKey: String,
        limit: Int = 90
    ): List<ExerciseLiftDay> {
        val before = WorkoutDate.requireKey(beforeDateKey)
        val dateKeys = state.completedSessions
            .map { it.diaryDateKey }
            .distinct()
            .filter { it < before }
            .sortedDescending()
        val results = mutableListOf<ExerciseLiftDay>()
        for (key in dateKeys) {
            if (results.size >= limit) break
            val sets = liftSets(state, itemId, name, key)
            if (sets.isNotEmpty()) results += ExerciseLiftDay(key, sets)
        }
        return results
    }

    fun lastSummary(
        state: WorkoutPersistedState,
        itemId: String,
        name: String,
        beforeDateKey: String,
        displayUnit: WorkoutWeightUnit
    ): String? {
        val latest = history(state, itemId, name, beforeDateKey, limit = 1).firstOrNull() ?: return null
        return formatSummary(latest.sets, displayUnit).takeIf { it.isNotEmpty() }
    }

    private fun liftSets(
        state: WorkoutPersistedState,
        itemId: String,
        name: String,
        dateKey: String
    ): List<ExerciseLiftSet> {
        return preferredHistorySession(state, dateKey)
            ?.exercises
            ?.firstOrNull { matches(itemId, name, it.itemId, it.name) }
            ?.let { performedSets(it.sets) }
            .orEmpty()
    }

    private fun preferredHistorySession(state: WorkoutPersistedState, dateKey: String): WorkoutSession? {
        val sessions = state.completedSessions.filter { it.diaryDateKey == dateKey }
        if (sessions.isEmpty()) return null
        val burns = sessions.filter { it.caloriesBurned != null }
        if (burns.isNotEmpty()) {
            return burns.maxWith(
                compareBy<WorkoutSession> { it.healthSyncVersion ?: 0 }.thenBy { it.completedAt }
            )
        }
        return sessions.maxByOrNull { it.completedAt }
    }
}
