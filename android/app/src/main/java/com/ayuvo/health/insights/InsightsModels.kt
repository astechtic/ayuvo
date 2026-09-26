package com.ayuvo.health.insights

import java.time.LocalDate
import java.time.ZoneId

/** One main night per wake day: minutes asleep and the night window (docs/insights.md §3.1). */
data class SleepInput(val asleepMin: Double?, val startMs: Long, val endMs: Long)

/** A health workout or an Ayuvo strength session; [effort] is the 1–10 effort score when known. */
data class WorkoutInput(val startMs: Long, val endMs: Long, val effort: Double? = null)

/** Which optional areas the person tracks. Nutrition counts as tracked unless it is switched off. */
data class TrackingFlags(
    val nutrition: Boolean = true,
    val water: Boolean = false,
    val workouts: Boolean = false,
    val fasting: Boolean = false
)

/**
 * The common engine input (the `inputs` object of `insights_reference.py`). Every field is
 * optional: a missing day means "no data", never zero. Nutrition days and targets are keyed by the
 * contract's names (`calories`, `protein_g`, `sugar_max_g`, …).
 */
data class InsightsInputs(
    val timeZone: ZoneId = ZoneId.of("UTC"),
    /** `rmssd` on Android (Health Connect), `sdnn` on iOS: picks the Health Age HRV table. */
    val hrvKind: String = "rmssd",
    val series: Map<String, Map<LocalDate, Double>> = emptyMap(),
    val sleep: Map<LocalDate, SleepInput> = emptyMap(),
    val workouts: List<WorkoutInput> = emptyList(),
    val overnightFallback: Set<String> = emptySet(),
    val tracking: TrackingFlags = TrackingFlags(),
    val targets: Map<String, Double> = emptyMap(),
    val nutrition: Map<LocalDate, Map<String, Double>> = emptyMap(),
    val waterMl: Map<LocalDate, Double> = emptyMap(),
    val fastingHours: Map<LocalDate, Double> = emptyMap(),
    val strengthVolume: Map<LocalDate, Double> = emptyMap(),
    val recoveryScores: Map<LocalDate, Double> = emptyMap(),
    /** The review day's Recovery (Daily Review only). */
    val recovery: RecoveryResult? = null,
    /** The Pattern engine result (Daily Review only). */
    val patterns: List<PatternResult>? = null
) {
    fun series(id: String): Map<LocalDate, Double> = series[id].orEmpty()
}

/** Birthday, sex (`male`, `female` or anything else) and height from the profile. */
data class InsightsProfile(val birthday: LocalDate?, val sex: String?, val heightCm: Double?)

data class Collecting(val have: Int, val need: Int)

data class BaselineResult(
    val status: String,
    val n: Int,
    val needed: Int,
    val coverage: Double,
    val mean: Double?,
    val sd: Double?,
    val sdFloored: Boolean,
    val low: Double?,
    val high: Double?,
    val recent: Double?,
    val delta: Double?,
    val pct: Double?,
    val z: Double?,
    val confidence: String
) {
    val ok: Boolean get() = status == "ok"
}

data class TrendResult(val status: String, val n: Int, val needed: Int, val slopePctPerWeek: Double?, val direction: String?)

data class OvernightValue(val value: Double?, val n: Int, val fallback: Boolean)

data class TrainingLoadResult(
    val day: LocalDate,
    val load: Double,
    val minutes: Double,
    val sessions: Int,
    val mean28d: Double,
    val ratio: Double?,
    val category: String,
    val label: String
)

data class RecoveryComponent(
    val id: String,
    val weight: Double,
    val available: Boolean,
    val value: Double?,
    val baseline: Double?,
    val delta: Double?,
    val pct: Double?,
    val z: Double?,
    val subscore: Double?,
    val impact: Double?,
    val baselineN: Int,
    val baselineConfidence: String,
    val fallback: Boolean,
    val consistencyDeviationMin: Double?,
    val consistencySubscore: Double?
)

data class RecoverySignal(val id: String, val impact: Double, val text: String)

data class RecoveryLoad(
    val day: LocalDate,
    val load: Double,
    val mean28d: Double,
    val ratio: Double?,
    val category: String,
    val label: String,
    val modifier: Int
)

data class RecoveryResult(
    val day: LocalDate,
    /** `ok`, `collecting`, `no_sleep` or `no_heart_data`. */
    val status: String,
    val score: Int?,
    val label: String?,
    val labelText: String?,
    val recommendation: String?,
    val confidence: String?,
    val collecting: Collecting?,
    val components: List<RecoveryComponent>,
    val positives: List<RecoverySignal>,
    val negatives: List<RecoverySignal>,
    val load: RecoveryLoad?
) {
    val ok: Boolean get() = status == "ok"
}

data class HealthAgeMarker(
    val id: String,
    val method: String,
    val available: Boolean,
    val value: Double?,
    val secondaryValue: Double?,
    /** Body composition: `body_fat` or `bmi`. */
    val basis: String?,
    val days: Int,
    val neededDays: Int,
    val equivalentAge: Double?,
    val offsetYears: Double?,
    val weight: Double,
    val contributionYears: Double?
)

data class HealthAgeResult(
    val asOf: LocalDate,
    /** `ok`, `collecting`, `no_birthday` or `unsupported_age`. */
    val status: String,
    val actualAge: Double?,
    val healthAge: Double?,
    val difference: Double?,
    val confidence: String?,
    val markers: List<HealthAgeMarker>,
    val collecting: Collecting?,
    val markersAvailable: Int,
    val markersNeeded: Int
) {
    val ok: Boolean get() = status == "ok"
}

data class HealthAgePoint(val day: LocalDate, val healthAge: Double, val difference: Double)

data class HealthAgePace(
    val status: String,
    val have: Int,
    val needed: Int,
    val pace: Double?,
    val direction: String?,
    val points: List<HealthAgePoint>
) {
    val ok: Boolean get() = status == "ok"
}

data class ReviewArea(val id: String, val included: Boolean, val score: Int?, val weight: Double, val detail: Map<String, Any?>?)

/** A rule result: [params] hold numbers (Int or Double) and strings, [text] is the filled template. */
data class ReviewItem(val ruleId: String, val params: Map<String, Any>, val text: String)

data class DailyReviewResult(
    val day: LocalDate,
    val dayScore: Int?,
    val areas: List<ReviewArea>,
    val notLogged: List<ReviewItem>,
    val wentWell: List<ReviewItem>,
    val needsAttention: List<ReviewItem>,
    val improve: List<ReviewItem>,
    val reduce: List<ReviewItem>
) {
    fun category(id: String): List<ReviewItem> = when (id) {
        "went_well" -> wentWell
        "needs_attention" -> needsAttention
        "improve" -> improve
        "reduce" -> reduce
        else -> emptyList()
    }

    companion object {
        val CATEGORIES = listOf("went_well", "needs_attention", "improve", "reduce")
    }
}

data class PatternResult(
    val id: String,
    val status: String,
    val nExposed: Int,
    val nUnexposed: Int,
    val needed: Int,
    val meanExposed: Double?,
    val meanUnexposed: Double?,
    val diff: Double?,
    val t: Double?,
    val d: Double?,
    val surfaced: Boolean,
    val text: String?,
    val reviewCategory: String?
)
