import Foundation
import Testing
@testable import calorietracker

/// Engine edge cases on synthetic history (docs/insights.md): missing HRV, an incomplete night, no workouts,
/// no food logs and too little history. The shared vectors pin the exact numbers; these pin the behaviour
/// the screens rely on.
struct InsightsEngineTests {
    static let config = InsightsConfig.shared
    static let today = "2026-09-20"

    /// `days` nights of 23:00–07:00 UTC sleep ending on `today`, HRV / resting HR / breathing / SpO2 around a
    /// stable baseline, and steps.
    static func history(days: Int = 40, today: String = today) -> InsightsInputs {
        var inputs = InsightsInputs()
        inputs.timeZone = "UTC"
        for k in 0..<days {
            let day = InsightsDay.add(today, -k)
            let wake = Int64(InsightsDay.parse(day)!) * 86_400_000 + 7 * 3_600_000
            let wobble = Double(k % 5) - 2
            inputs.sleep[day] = InsightsNight(asleepMin: 440 + wobble * 6, startMs: wake - 8 * 3_600_000, endMs: wake)
            inputs.series["hrv", default: [:]][day] = 48 + wobble
            inputs.series["resting_heart_rate", default: [:]][day] = 55 - wobble / 2
            inputs.series["respiratory_rate", default: [:]][day] = 14.5 + wobble / 10
            inputs.series["blood_oxygen", default: [:]][day] = 96.5 + wobble / 10
            inputs.series["steps", default: [:]][day] = 8_000 + wobble * 300
        }
        return inputs
    }

    @Test func fullHistoryScoresWithEveryComponent() {
        let r = RecoveryEngine.recovery(Self.history(), day: Self.today, config: Self.config)
        #expect(r.status == "ok")
        #expect((0...100).contains(r.score ?? -1))
        #expect(r.components.allSatisfy { $0.available })
        #expect(r.load?.category == "none")
        #expect(r.load?.modifier == 0)
    }

    @Test func missingHRVRenormalisesTheOtherWeights() {
        var inputs = Self.history()
        inputs.series["hrv"]?[Self.today] = nil
        let r = RecoveryEngine.recovery(inputs, day: Self.today, config: Self.config)
        #expect(r.status == "ok")
        let hrv = r.components.first { $0.id == "hrv" }
        #expect(hrv?.available == false)
        #expect(hrv?.value == nil)
        #expect(r.score != nil)
        #expect(!(r.positives + r.negatives).contains { $0.id == "hrv" })
    }

    @Test func nightUnderTwoHoursIsIncompleteNotZero() {
        var inputs = Self.history()
        inputs.sleep[Self.today]?.asleepMin = 95
        let r = RecoveryEngine.recovery(inputs, day: Self.today, config: Self.config)
        #expect(r.status == "no_sleep")
        #expect(r.score == nil)
        let sleep = r.components.first { $0.id == "sleep" }
        #expect(sleep?.value == nil, "an incomplete night must not show as a value")
    }

    @Test func noHeartDataTodayHasNoScore() {
        var inputs = Self.history()
        inputs.series["hrv"]?[Self.today] = nil
        inputs.series["resting_heart_rate"]?[Self.today] = nil
        let r = RecoveryEngine.recovery(inputs, day: Self.today, config: Self.config)
        #expect(r.status == "no_heart_data")
        #expect(r.score == nil)
    }

    @Test func insufficientHistoryCollectsNights() {
        let r = RecoveryEngine.recovery(Self.history(days: 10), day: Self.today, config: Self.config)
        #expect(r.status == "collecting")
        #expect(r.collecting == InsightsCollecting(have: 9, need: 14))
        #expect(r.score == nil)
    }

    @Test func highLoadYesterdayTakesPointsOff() {
        var calm = Self.history()
        // A month of light 30-minute sessions, then a long hard one yesterday.
        let yesterday = InsightsDay.add(Self.today, -1)
        for k in 2...29 where k % 2 == 0 {
            let start = Int64(InsightsDay.parse(InsightsDay.add(Self.today, -k))!) * 86_400_000 + 17 * 3_600_000
            calm.workouts.append(InsightsWorkout(startMs: start, endMs: start + 30 * 60_000, effort: nil))
        }
        let base = RecoveryEngine.recovery(calm, day: Self.today, config: Self.config)
        var hard = calm
        let start = Int64(InsightsDay.parse(yesterday)!) * 86_400_000 + 17 * 3_600_000
        hard.workouts.append(InsightsWorkout(startMs: start, endMs: start + 120 * 60_000, effort: 9))
        let r = RecoveryEngine.recovery(hard, day: Self.today, config: Self.config)
        #expect(r.load?.category == "high")
        #expect(r.load?.modifier == -6)
        #expect((r.score ?? 0) == max(0, (base.score ?? 0) - 6))
        #expect(r.negatives.contains { $0.id == "training_load" })
    }

    @Test func noWorkoutsMeansRestNotAPenalty() {
        let load = TrainingLoadEngine.trainingLoad([], day: Self.today, timeZone: "UTC", config: Self.config)
        #expect(load.category == "none")
        #expect(load.ratio == nil)
        #expect(load.label == "Rest")
    }

    @Test func noFoodLogsIsNotLoggedAndNeverLowersTheScore() {
        var inputs = Self.history()
        inputs.targets.steps = 8_000
        inputs.targets.calories = 2_000
        inputs.recovery = RecoveryEngine.recovery(inputs, day: Self.today, config: Self.config)
        let review = DailyReviewEngine.review(inputs, day: Self.today, config: Self.config)
        let nutrition = review.areas.first { $0.id == "nutrition" }
        #expect(nutrition?.included == false)
        #expect(nutrition?.score == nil)
        #expect(review.notLogged.contains { $0.params["area"]?.text == "nutrition" })
        #expect(review.dayScore != nil)
        // Logging a perfect day can only add a 100-scored area.
        var logged = inputs
        logged.nutrition[Self.today] = InsightsNutritionDay(calories: 2_000)
        let withFood = DailyReviewEngine.review(logged, day: Self.today, config: Self.config)
        #expect((withFood.dayScore ?? 0) >= (review.dayScore ?? 0))
    }

    @Test func nothingLoggedHasNoDayScore() {
        var inputs = InsightsInputs()
        inputs.timeZone = "UTC"
        let review = DailyReviewEngine.review(inputs, day: Self.today, config: Self.config)
        #expect(review.dayScore == nil)
        #expect(review.areas.allSatisfy { !$0.included && $0.score == nil })
        #expect(review.wentWell.isEmpty && review.needsAttention.isEmpty)
    }

    @Test func healthAgeNeedsBirthdayAndCoreMarkers() {
        let inputs = Self.history(days: 95)
        let noBirthday = HealthAgeEngine.healthAge(inputs, asOf: Self.today, profile: InsightsProfile(), config: Self.config)
        #expect(noBirthday.status == "no_birthday")
        #expect(noBirthday.healthAge == nil)
        let profile = InsightsProfile(birthday: "1986-03-01", sex: "female", heightCm: 168)
        let ok = HealthAgeEngine.healthAge(inputs, asOf: Self.today, profile: profile, config: Self.config)
        #expect(ok.status == "ok")
        #expect(ok.healthAge != nil)
        #expect(ok.markers.first { $0.id == "vo2_max" }?.available == false)
        var thin = InsightsInputs()
        thin.series["steps"] = inputs.series["steps"]
        let collecting = HealthAgeEngine.healthAge(thin, asOf: Self.today, profile: profile, config: Self.config)
        #expect(collecting.status == "collecting")
        #expect(collecting.collecting?.need == 30)
    }

    @Test func facadeBuildsEveryPieceWithoutFabricatingValues() {
        let inputs = Self.history(days: 60)
        let profile = InsightsProfile(birthday: "1990-01-15", sex: "male", heightCm: 180)
        let report = HealthAnalyticsEngine.report(inputs: inputs, today: Self.today, profile: profile, config: Self.config)
        #expect(report.recovery.status == "ok")
        #expect(report.recoveryHistory.count == HealthAnalyticsEngine.recoveryHistoryDays)
        #expect(report.recoveryHistory.last?.day == Self.today)
        #expect(report.reviews.count == HealthAnalyticsEngine.reviewDays)
        #expect(report.patterns.count == Self.config.patterns.pairs.count)
        #expect(report.baselines.contains { $0.id == "sleep" && $0.baseline.isReady })
        #expect(!report.baselines.contains { $0.id == "vo2_max" }, "a metric without data is left out, not shown as 0")
        let summary = report.summary()
        #expect(summary.dailyReview?.day == Self.today)
    }
}
