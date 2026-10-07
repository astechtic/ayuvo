import Foundation

/// Per-metric baseline row of the Trends screen.
nonisolated struct InsightsMetricBaseline: Sendable, Equatable, Identifiable {
    let id: String
    let label: String
    let unit: String
    let direction: String
    let baseline: BaselineResult
    let trend: TrendResult
}

/// Everything the Insights screens show for one day, computed in one pass.
nonisolated struct InsightsReport: Sendable, Equatable {
    let today: String
    let recovery: RecoveryResult
    /// Oldest first, ending today (the 30-day chart).
    let recoveryHistory: [RecoveryResult]
    let healthAge: HealthAgeResult
    let pace: HealthAgePaceResult
    let patterns: [PatternResult]
    /// Day → review, for the last `reviewDays` days.
    let reviews: [String: DailyReviewResult]
    let baselines: [InsightsMetricBaseline]
    /// Health analytics (shared/analytics): Recovery v2 detail, signals, HRV, sleep, load, trends, patterns v2,
    /// energy, VO2 max, activity intensity, forecasts and the Coach evidence.
    var analytics: AnalyticsBundle? = nil

    /// The AI / action view of one review day (today when nil).
    func summary(reviewDay: String? = nil) -> InsightsSummary {
        InsightsSummary(recovery: recovery, healthAge: healthAge, healthAgePace: pace,
                        dailyReview: reviews[reviewDay ?? today], patterns: patterns)
    }
}

/// Façade over the engines (docs/insights.md §3): supplies the derived inputs each engine needs — the review
/// day's `recovery()` and `patterns()` for the Daily Review, and a `recovery()` per day as `recovery_scores`
/// for the Pattern engine — so each engine stays a small pure function.
nonisolated enum HealthAnalyticsEngine {
    static let recoveryHistoryDays = 30
    static let reviewDays = 7
    /// Metrics listed on the Trends screen, in order.
    static let trendMetrics = ["sleep", "hrv", "resting_heart_rate", "respiratory_rate", "blood_oxygen", "vo2_max",
                               "steps", "active_energy", "weight", "body_fat"]

    static func report(inputs: InsightsInputs, today: String, profile: InsightsProfile,
                       config: InsightsConfig) -> InsightsReport {
        let window = config.patterns.windowDays
        let a = AnalyticsInputsBuilder.make(inputs, today: today)
        var scores: [String: Double] = [:]
        var byDay: [String: RecoveryResult] = [:]
        var v2ByDay: [String: AJ] = [:]
        // Recovery Indicator v2 (`ayuvo.recovery@2`) replaces v1 in the app; v1 stays in RecoveryEngine for reference.
        for k in stride(from: window, through: 0, by: -1) {
            let day = InsightsDay.add(today, -k)
            let (result, json) = RecoveryV2Adapter.recovery(a, inputs: inputs, day: day)
            byDay[day] = result
            if k < AnalyticsSuite.persistDays { v2ByDay[day] = json }
            if result.status == "ok", let score = result.score { scores[day] = Double(score) }
        }
        var patternInputs = inputs
        patternInputs.recoveryScores = scores
        let patterns = PatternEngine.patterns(patternInputs, asOf: today, config: config)

        var reviews: [String: DailyReviewResult] = [:]
        for k in 0..<reviewDays {
            let day = InsightsDay.add(today, -k)
            reviews[day] = review(inputs, day: day, recovery: byDay[day], patterns: patterns, config: config)
        }
        let history = (0..<recoveryHistoryDays).reversed().compactMap { byDay[InsightsDay.add(today, -$0)] }
        return InsightsReport(
            today: today,
            recovery: byDay[today] ?? RecoveryV2Adapter.recovery(a, inputs: inputs, day: today).0,
            recoveryHistory: history,
            healthAge: HealthAgeEngine.healthAge(inputs, asOf: today, profile: profile, config: config),
            pace: HealthAgeEngine.pace(inputs, asOf: today, profile: profile, config: config),
            patterns: patterns,
            reviews: reviews,
            baselines: baselines(inputs, day: today, config: config),
            analytics: AnalyticsSuite.compute(inputs, today: today, recoveryByDay: v2ByDay)
        )
    }

    /// The Daily Review of `day` with that day's recovery and the surfaced patterns.
    static func review(_ inputs: InsightsInputs, day: String, recovery: RecoveryResult?, patterns: [PatternResult],
                       config: InsightsConfig) -> DailyReviewResult {
        var reviewInputs = inputs
        reviewInputs.recovery = recovery ?? RecoveryV2Adapter.recovery(AnalyticsInputsBuilder.make(inputs, today: day),
                                                                       inputs: inputs, day: day).0
        reviewInputs.patterns = patterns
        return DailyReviewEngine.review(reviewInputs, day: day, config: config)
    }

    /// Baseline, range, today and trend per metric. Sleep uses valid nights only (the Recovery series).
    static func baselines(_ inputs: InsightsInputs, day: String, config: InsightsConfig) -> [InsightsMetricBaseline] {
        var workoutMinutes: [String: Double] = [:]
        for session in TrainingLoadEngine.sessions(inputs.workouts, timeZone: inputs.timeZone, config: config) {
            workoutMinutes[session.day, default: 0] += session.minutes
        }
        return (trendMetrics + ["workout"]).compactMap { id in
            guard let metric = config.metric(id) else { return nil }
            let series: [String: Double]
            switch id {
            case "sleep": series = BaselineEngine.sleepSeries(inputs, config: config)
            case "workout": series = workoutMinutes
            default: series = inputs.series[id] ?? [:]
            }
            guard !series.isEmpty else { return nil }
            return InsightsMetricBaseline(
                id: id, label: metric.label, unit: metric.unit, direction: metric.direction,
                baseline: BaselineEngine.baseline(series, day: day, metric: metric),
                trend: BaselineEngine.trend(series, day: day, metric: metric)
            )
        }
    }
}
