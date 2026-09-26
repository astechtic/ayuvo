package com.ayuvo.health.insights

import kotlinx.serialization.json.JsonObject
import java.time.LocalDate

/** One metric on the Trends screen: its baseline and 28-day trend as of today. */
data class MetricInsight(val metric: InsightsConfig.Metric, val baseline: BaselineResult, val trend: TrendResult)

/**
 * Everything the Insights surfaces show for one day, recomputed from the stores on demand
 * (docs/insights.md §3: nothing persisted). History (the 30-day Recovery chart, the 12 weekly
 * Health Age points) is recomputed "as of" earlier days.
 */
data class InsightsSnapshot(
    val today: LocalDate,
    val healthEnabled: Boolean,
    val recovery: RecoveryResult,
    /** Oldest first, ending today. */
    val recoveryHistory: List<RecoveryResult>,
    val healthAge: HealthAgeResult,
    val pace: HealthAgePace,
    /** The last [REVIEW_DAYS] days, today last. */
    val reviews: List<DailyReviewResult>,
    val patterns: List<PatternResult>,
    val baselines: List<MetricInsight>,
    val profile: InsightsProfile
) {
    fun review(day: LocalDate): DailyReviewResult? = reviews.firstOrNull { it.day == day }

    /** The `summary` object the AI payload is built from, with the review of [reviewDay]. */
    fun summaryJson(reviewDay: LocalDate = today): JsonObject =
        InsightsJson.summary(recovery, healthAge, pace, review(reviewDay), patterns)

    companion object {
        const val REVIEW_DAYS = 7
        const val RECOVERY_HISTORY_DAYS = 30
    }
}

/**
 * The façade over the pure engines (docs/insights.md §3). It supplies the two derived inputs so
 * each engine stays small: Patterns get `recovery_scores` from a Recovery for every day of their
 * window, and each Daily Review gets its day's Recovery and the Patterns result.
 */
object HealthAnalyticsEngine {

    fun recovery(bundle: InsightsBundle, day: LocalDate, cfg: InsightsConfig): RecoveryResult =
        RecoveryEngine.recovery(bundle.inputsFor(day), day, cfg)

    fun snapshot(bundle: InsightsBundle, cfg: InsightsConfig): InsightsSnapshot {
        val today = bundle.today
        val window = cfg.patterns.windowDays
        val recoveries = LinkedHashMap<LocalDate, RecoveryResult>()
        for (k in window downTo 0) {
            val d = today.minusDays(k.toLong())
            recoveries[d] = recovery(bundle, d, cfg)
        }
        val scores = recoveries.filterValues { it.ok }.mapValues { it.value.score!!.toDouble() }
        val patterns = PatternEngine.patterns(bundle.inputs.copy(recoveryScores = scores), today, cfg)
        val reviews = (InsightsSnapshot.REVIEW_DAYS - 1 downTo 0).map { k ->
            val d = today.minusDays(k.toLong())
            DailyReviewEngine.review(bundle.inputsFor(d).copy(recovery = recoveries[d], patterns = patterns), d, cfg)
        }
        val history = (InsightsSnapshot.RECOVERY_HISTORY_DAYS - 1 downTo 0).map { k ->
            val d = today.minusDays(k.toLong())
            recoveries[d] ?: recovery(bundle, d, cfg)
        }
        return InsightsSnapshot(
            today = today,
            healthEnabled = bundle.healthEnabled,
            recovery = recoveries.getValue(today),
            recoveryHistory = history,
            healthAge = HealthAgeEngine.healthAge(bundle.inputs, today, bundle.profile, cfg),
            pace = HealthAgeEngine.pace(bundle.inputs, today, bundle.profile, cfg),
            reviews = reviews,
            patterns = patterns,
            baselines = baselines(bundle, today, cfg),
            profile = bundle.profile
        )
    }

    /** Baseline and trend for every configured metric that has any data (sleep from the nights). */
    fun baselines(bundle: InsightsBundle, today: LocalDate, cfg: InsightsConfig): List<MetricInsight> =
        cfg.metrics.values.mapNotNull { m ->
            val series = if (m.id == "sleep") BaselineEngine.sleepSeries(bundle.inputs, cfg) else bundle.inputs.series(m.id)
            if (series.isEmpty()) return@mapNotNull null
            MetricInsight(m, BaselineEngine.baseline(series, today, m), BaselineEngine.trend(series, today, m))
        }
}
