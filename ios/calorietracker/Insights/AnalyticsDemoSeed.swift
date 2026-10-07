#if DEBUG
import Foundation

/// UI-test / screenshot launch argument (DEBUG builds only): `-AyuvoAnalyticsDemo` replaces the Insights report with
/// one computed from 130 days of deterministic synthetic inputs, so every Insights and analytics screen has data on
/// a simulator without Apple Health. Nothing is written to any store.
enum AnalyticsDemoSeed {
    static var isRequested: Bool { ProcessInfo.processInfo.arguments.contains("-AyuvoAnalyticsDemo") }

    /// Numerical Recipes LCG; `noise()` is roughly normal with SD 1.
    private struct LCG {
        var s: UInt32
        mutating func u() -> Double {
            s = s &* 1_664_525 &+ 1_013_904_223
            return Double(s) / 4_294_967_296.0
        }
        mutating func noise() -> Double { (u() + u() + u() + u() - 2.0) * 1.732 }
    }

    static func report(today: String, timeZone: String) -> InsightsReport {
        let inputs = inputs(today: today, timeZone: timeZone)
        let profile = InsightsProfile(birthday: "1991-05-04", sex: "male", heightCm: 178)
        return HealthAnalyticsEngine.report(inputs: inputs, today: today, profile: profile, config: .shared)
    }

    static func inputs(today: String, timeZone: String, days: Int = 130) -> InsightsInputs {
        var g = LCG(s: 20_261_007)
        var inputs = InsightsInputs()
        inputs.timeZone = timeZone
        let tz = DerivedDay.timeZone(timeZone)
        var series: [String: [String: Double]] = [:]
        for k in 0..<days {
            let day = InsightsDay.add(today, -(days - 1 - k))
            let last = k == days - 1
            series["hrv", default: [:]][day] = last ? 44 : (55 + 5 * g.noise()).rounded()
            series["resting_heart_rate", default: [:]][day] = last ? 57.5 : ((54 + 1.5 * g.noise()) * 10).rounded() / 10
            series["respiratory_rate", default: [:]][day] = ((14.2 + 0.3 * g.noise()) * 10).rounded() / 10
            series["blood_oxygen", default: [:]][day] = ((96 + 0.6 * g.noise()) * 10).rounded() / 10
            series["steps", default: [:]][day] = (8500 + 2000 * g.noise()).rounded()
            series["active_energy", default: [:]][day] = (450 + 80 * g.noise()).rounded()
            series["weight", default: [:]][day] = ((72.8 - Double(k) * 0.006 + 0.3 * g.noise()) * 10).rounded() / 10
            inputs.analytics.series["wrist_temperature", default: [:]][day] = ((0.05 * g.noise()) * 100).rounded() / 100
            inputs.analytics.series["resting_energy", default: [:]][day] = 1650 + (20 * g.noise()).rounded()
            if k % 9 == 0 { series["vo2_max", default: [:]][day] = ((43.5 + Double(k) * 0.012) * 10).rounded() / 10 }
            if k % 4 == 0 { inputs.analytics.series["hrr1_provider", default: [:]][day] = (30 + 4 * g.noise()).rounded() }
            if k >= days - 40 { inputs.analytics.series["ayuvo_rmssd", default: [:]][day] = ((42 + 4 * g.noise()) * 10).rounded() / 10 }
            inputs.nutrition[day] = InsightsNutritionDay(calories: (2250 + 150 * g.noise()).rounded(), proteinG: 120)

            // Night ending on `day`: ~7.3 h asleep, last night short.
            let asleep = last ? 395 : (440 + 30 * g.noise()).rounded()
            let inBed = (asleep / 0.91).rounded()
            let mid = (900 + 20 * g.noise()).rounded()
            let start = DerivedDay.dayStartMs(day, tz) + Int64((mid - inBed / 2 - 720 - 1440) * 60_000)
            inputs.sleep[day] = InsightsNight(asleepMin: asleep, startMs: start, endMs: start + Int64(inBed * 60_000))
            inputs.analytics.nights[day] = ANight(asleepMin: asleep, inBedMin: inBed, efficiency: (asleep * 1000 / inBed).rounded() / 10,
                                                  wasoMin: 18, bedtimeClock: mid - inBed / 2, wakeClock: mid + inBed / 2,
                                                  midpointClock: mid, napMin: k % 11 == 0 ? 30 : 0)

            // Every other day a 45-minute run; the last week trains more.
            let minutes = k >= days - 7 ? 75.0 : (k % 2 == 0 ? 45.0 : 0)
            if minutes > 0 {
                let s = DerivedDay.dayStartMs(day, tz) + 7 * 3_600_000
                let w = AnalyticsWorkoutInput(startMs: s, endMs: s + Int64(minutes * 60_000), effort: minutes > 60 ? 8 : 6,
                                              trimp: minutes * 1.3, activity: "running", kcal: minutes * 10, provider: true)
                inputs.analytics.workouts.append(w)
                inputs.workouts.append(InsightsWorkout(startMs: w.startMs, endMs: w.endMs, effort: w.effort))
            }
        }
        inputs.series = series
        inputs.analytics.series["vo2_provider"] = series["vo2_max"]
        inputs.tracking = InsightsTracking(nutrition: true, water: false, workouts: true, fasting: false)
        inputs.analytics.profile = AnalyticsProfileFacts(weightKg: 72, heightCm: 178, age: 35, sex: "male")
        inputs.analytics.forecastEnabled = true
        return inputs
    }
}
#endif
