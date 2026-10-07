import Foundation
import HealthKit

/// A workout as the analytics engine sees it, before it becomes an `AWorkout` (docs/health-analytics.md §5.5).
nonisolated struct AnalyticsWorkoutInput: Equatable, Sendable {
    var startMs: Int64
    var endMs: Int64
    /// CR-10 effort, nil when the session has none (never invented).
    var effort: Double?
    /// Per-session TRIMP when one was computed; nil otherwise.
    var trimp: Double?
    /// Key of the analytics MET table (`met.table`), nil for an unknown activity (no MET is made up).
    var activity: String?
    /// Energy of the session: Ayuvo's estimate for its own sessions, the provider's total for imported ones.
    var kcal: Double?
    /// True for a workout recorded by Apple Health / another app (energy already inside active energy).
    var provider: Bool
}

/// Profile facts the energy algorithm needs (Mifflin–St Jeor).
nonisolated struct AnalyticsProfileFacts: Equatable, Sendable {
    var weightKg: Double?
    var heightCm: Double?
    var age: Double?
    /// male, female or nil.
    var sex: String?
}

/// Inputs only the analytics engine reads; collected next to the Insights inputs by `InsightsDataSource`.
nonisolated struct InsightsAnalyticsInputs: Equatable, Sendable {
    /// wake day → main-night summary (`shared/derived` sleep_night on the main episode, plus nap minutes).
    var nights: [String: ANight] = [:]
    /// metric → day → the source the night value came from (source-change detection).
    var sources: [String: [String: String]] = [:]
    /// metric → days whose value is Ayuvo's derived estimate rather than a platform reading.
    var derivedDays: [String: Set<String>] = [:]
    var workouts: [AnalyticsWorkoutInput] = []
    /// Provider-only series: wrist_temperature, resting_energy, hrr1_provider, vo2_provider, vo2_uth, ayuvo_rmssd.
    var series: [String: [String: Double]] = [:]
    var profile = AnalyticsProfileFacts()
    /// Settings › Insights › Forecast (default off).
    var forecastEnabled = false
}

/// Builds the engine's `AInputs` (the reference's `inputs` object) from the Insights inputs.
nonisolated enum AnalyticsInputsBuilder {
    /// Insights series → analytics metric ids.
    static let seriesMap: [(insights: String, analytics: String)] = [
        ("hrv", "hrv"), ("resting_heart_rate", "resting_heart_rate"), ("respiratory_rate", "respiratory_rate"),
        ("blood_oxygen", "blood_oxygen"), ("steps", "steps"), ("active_energy", "active_energy"), ("weight", "weight"),
        ("vo2_max", "vo2_max"),
    ]

    static func make(_ inputs: InsightsInputs, today: String) -> AInputs {
        var a = AInputs()
        a.timeZone = inputs.timeZone
        for (from, to) in seriesMap {
            if let s = inputs.series[from], !s.isEmpty { a.series[to] = s }
        }
        if let t = inputs.analytics.series["wrist_temperature"], !t.isEmpty { a.series["wrist_temperature"] = t }
        a.contexts = contexts(inputs)
        a.sources = inputs.analytics.sources
        a.nights = inputs.analytics.nights
        // Nights the detailed pass did not cover (no derived sleep math): asleep minutes and timing from the
        // Insights night, never invented efficiency.
        let tz = InsightsDay.timeZone(inputs.timeZone)
        for (day, n) in inputs.sleep where a.nights[day] == nil {
            let mid = InsightsDay.sleepMidpointMinutes(startMs: n.startMs, endMs: n.endMs, wakeDay: day, tz)
            a.nights[day] = ANight(asleepMin: n.asleepMin, midpointClock: Double(mid))
        }
        a.workouts = inputs.analytics.workouts.map {
            AWorkout(startMs: $0.startMs, endMs: $0.endMs, effort: $0.effort, trimp: $0.trimp, activity: $0.activity)
        }
        a.trackingWorkouts = inputs.tracking.workouts
        a.overnightFallback = inputs.overnightFallback
        a.scanFallback = inputs.scanFallback.filter { $0.value.contains(today) }.map(\.key).sorted()
        for (day, n) in inputs.nutrition { a.nutrition[day] = (n.calories, n.proteinG) }
        return a
    }

    /// Camera-scan days are "camera" (never in a wearable baseline); derived-estimate days are "derived".
    static func contexts(_ inputs: InsightsInputs) -> [String: [String: String]] {
        var out: [String: [String: String]] = [:]
        for (metric, days) in inputs.analytics.derivedDays {
            for d in days { out[metric, default: [:]][d] = "derived" }
        }
        for (metric, days) in inputs.scanFallback {
            for d in days { out[metric, default: [:]][d] = "camera" }
        }
        return out
    }

    // MARK: Activity keys

    /// HealthKit workout activity type (raw value) → analytics MET table key. Unknown types return nil.
    static func activityKey(hkRaw: UInt) -> String? {
        guard let type = HKWorkoutActivityType(rawValue: hkRaw) else { return nil }
        switch type {
        case .walking: return "walking"
        case .running: return "running"
        case .cycling: return "cycling"
        case .swimming: return "swimming"
        case .rowing: return "rowing_machine"
        case .elliptical: return "elliptical"
        case .hiking: return "hiking"
        case .yoga: return "yoga"
        case .pilates: return "pilates"
        case .traditionalStrengthTraining, .functionalStrengthTraining: return "strength_training"
        case .highIntensityIntervalTraining: return "hiit"
        case .cardioDance, .socialDance, .dance: return "dancing"
        case .stairClimbing, .stairs: return "stair_climbing"
        case .tennis: return "tennis"
        case .soccer: return "soccer"
        case .basketball: return "basketball"
        case .martialArts, .kickboxing: return "martial_arts"
        case .boxing: return "boxing"
        case .jumpRope: return "jump_rope"
        case .downhillSkiing, .snowboarding: return "skiing_downhill"
        case .crossCountrySkiing: return "cross_country_skiing"
        case .golf: return "golf"
        case .badminton: return "badminton"
        case .tableTennis: return "table_tennis"
        case .volleyball: return "volleyball"
        case .climbing: return "climbing"
        case .skatingSports: return "skating"
        case .flexibility, .mindAndBody, .cooldown: return "mind_body"
        default: return nil
        }
    }

    /// Ayuvo's own strength sessions.
    static let strengthKey = "strength_training"

    // MARK: Profile

    static func profileFacts(_ p: UserProfile, latestWeightKg: Double?, today: Date, calendar: Calendar) -> AnalyticsProfileFacts {
        let years = calendar.dateComponents([.year], from: p.birthday, to: today).year
        let sex: String? = switch p.gender {
        case .male: "male"
        case .female: "female"
        case .other: nil
        }
        let weight = latestWeightKg ?? (p.weightKg > 0 ? p.weightKg : nil)
        return AnalyticsProfileFacts(weightKg: weight, heightCm: p.heightCm > 0 ? p.heightCm : nil,
                                     age: years.map(Double.init), sex: sex)
    }
}
