import Foundation

// Plain inputs and results of the Insights engines (docs/insights.md §3). Result coding keys are the reference's
// snake_case keys, so a result encodes to the same object `scripts/insights_reference.py` returns (nil values are
// simply absent) and decodes from it (the AI vectors feed reference summaries back in).

// MARK: - Template params

/// A template parameter or review detail value: a number, a string or null.
nonisolated enum InsightsParam: Codable, Equatable, Sendable {
    case number(Double)
    case text(String)
    case null

    init(_ value: Double?) { self = value.map(InsightsParam.number) ?? .null }
    init(_ value: Int) { self = .number(Double(value)) }

    /// Text used by `{placeholder}` replacement.
    var templateText: String {
        switch self {
        case .number(let x): InsightsFormat.number(x)
        case .text(let s): s
        case .null: "None"
        }
    }

    var number: Double? { if case .number(let x) = self { return x } else { return nil } }
    var text: String? { if case .text(let s) = self { return s } else { return nil } }

    init(from decoder: Decoder) throws {
        let c = try decoder.singleValueContainer()
        if c.decodeNil() {
            self = .null
        } else if let x = try? c.decode(Double.self) {
            self = .number(x)
        } else {
            self = .text(try c.decode(String.self))
        }
    }

    func encode(to encoder: Encoder) throws {
        var c = encoder.singleValueContainer()
        switch self {
        case .number(let x): try c.encode(x)
        case .text(let s): try c.encode(s)
        case .null: try c.encodeNil()
        }
    }
}

// MARK: - Inputs

/// One main night, keyed by its wake day.
nonisolated struct InsightsNight: Codable, Equatable, Sendable {
    var asleepMin: Double
    var startMs: Int64
    var endMs: Int64

    enum CodingKeys: String, CodingKey { case asleepMin = "asleep_min", startMs = "start_ms", endMs = "end_ms" }
}

/// A health workout or an Ayuvo strength session. `effort` is 1–10 when the platform has one.
nonisolated struct InsightsWorkout: Codable, Equatable, Sendable {
    var startMs: Int64
    var endMs: Int64
    var effort: Double?

    enum CodingKeys: String, CodingKey { case startMs = "start_ms", endMs = "end_ms", effort }
}

/// A timestamped reading, used only to resolve overnight values (never sent anywhere).
nonisolated struct InsightsSample: Codable, Equatable, Sendable {
    var tMs: Int64
    var value: Double

    enum CodingKeys: String, CodingKey { case tMs = "t_ms", value }
}

/// Which features the person uses. Nutrition defaults to tracked; the others default to off.
nonisolated struct InsightsTracking: Codable, Equatable, Sendable {
    var nutrition: Bool = true
    var water: Bool = false
    var workouts: Bool = false
    var fasting: Bool = false

    init(nutrition: Bool = true, water: Bool = false, workouts: Bool = false, fasting: Bool = false) {
        self.nutrition = nutrition
        self.water = water
        self.workouts = workouts
        self.fasting = fasting
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        nutrition = try c.decodeIfPresent(Bool.self, forKey: .nutrition) ?? true
        water = try c.decodeIfPresent(Bool.self, forKey: .water) ?? false
        workouts = try c.decodeIfPresent(Bool.self, forKey: .workouts) ?? false
        fasting = try c.decodeIfPresent(Bool.self, forKey: .fasting) ?? false
    }
}

/// Daily targets and upper limits. A missing or non-positive target means "no target".
nonisolated struct InsightsTargets: Codable, Equatable, Sendable {
    var calories: Double?
    var proteinG: Double?
    var carbsG: Double?
    var fatG: Double?
    var fiberG: Double?
    var waterMl: Double?
    var steps: Double?
    var fastingHours: Double?
    var sugarMaxG: Double?
    var addedSugarMaxG: Double?
    var sodiumMaxMg: Double?
    var saturatedFatMaxG: Double?
    var caffeineMaxMg: Double?

    enum CodingKeys: String, CodingKey {
        case calories, proteinG = "protein_g", carbsG = "carbs_g", fatG = "fat_g", fiberG = "fiber_g",
             waterMl = "water_ml", steps, fastingHours = "fasting_hours", sugarMaxG = "sugar_max_g",
             addedSugarMaxG = "added_sugar_max_g", sodiumMaxMg = "sodium_max_mg",
             saturatedFatMaxG = "saturated_fat_max_g", caffeineMaxMg = "caffeine_max_mg"
    }

    /// Value by config key (`calories`, `protein_g`, `sugar_max_g`, …).
    func value(_ key: String) -> Double? {
        switch key {
        case "calories": calories
        case "protein_g": proteinG
        case "carbs_g": carbsG
        case "fat_g": fatG
        case "fiber_g": fiberG
        case "water_ml": waterMl
        case "steps": steps
        case "fasting_hours": fastingHours
        case "sugar_max_g": sugarMaxG
        case "added_sugar_max_g": addedSugarMaxG
        case "sodium_max_mg": sodiumMaxMg
        case "saturated_fat_max_g": saturatedFatMaxG
        case "caffeine_max_mg": caffeineMaxMg
        default: nil
        }
    }
}

/// One day's logged nutrition totals. A day counts as logged only when calories are positive.
nonisolated struct InsightsNutritionDay: Codable, Equatable, Sendable {
    var calories: Double?
    var proteinG: Double?
    var carbsG: Double?
    var fatG: Double?
    var fiberG: Double?
    var sugarG: Double?
    var addedSugarG: Double?
    var sodiumMg: Double?
    var saturatedFatG: Double?
    var caffeineMg: Double?

    enum CodingKeys: String, CodingKey {
        case calories, proteinG = "protein_g", carbsG = "carbs_g", fatG = "fat_g", fiberG = "fiber_g",
             sugarG = "sugar_g", addedSugarG = "added_sugar_g", sodiumMg = "sodium_mg",
             saturatedFatG = "saturated_fat_g", caffeineMg = "caffeine_mg"
    }

    /// Value by config key (`protein_g`, `sugar_g`, `sodium_mg`, …).
    func value(_ key: String) -> Double? {
        switch key {
        case "calories": calories
        case "protein_g": proteinG
        case "carbs_g": carbsG
        case "fat_g": fatG
        case "fiber_g": fiberG
        case "sugar_g": sugarG
        case "added_sugar_g": addedSugarG
        case "sodium_mg": sodiumMg
        case "saturated_fat_g": saturatedFatG
        case "caffeine_mg": caffeineMg
        default: nil
        }
    }
}

/// Profile facts Health Age needs. `sex` is "male", "female" or nil (anything else uses the averaged tables).
nonisolated struct InsightsProfile: Codable, Equatable, Sendable {
    var birthday: String?
    var sex: String?
    var heightCm: Double?

    enum CodingKeys: String, CodingKey { case birthday, sex, heightCm = "height_cm" }
}

/// Everything the engines read (the reference's `inputs` object). A missing value is absent, never zero.
nonisolated struct InsightsInputs: Equatable, Sendable {
    var timeZone: String = "UTC"
    /// "sdnn" on iOS, "rmssd" on Android.
    var hrvKind: String = "sdnn"
    /// metric id → day → value (overnight values already resolved).
    var series: [String: [String: Double]] = [:]
    /// wake day → night.
    var sleep: [String: InsightsNight] = [:]
    var workouts: [InsightsWorkout] = []
    var overnightFallback: [String] = []
    var tracking = InsightsTracking()
    var targets = InsightsTargets()
    var nutrition: [String: InsightsNutritionDay] = [:]
    var waterMl: [String: Double] = [:]
    var fastingHours: [String: Double] = [:]
    var strengthVolume: [String: Double] = [:]
    var recoveryScores: [String: Double] = [:]
    /// The review day's recovery result (Daily Review only).
    var recovery: RecoveryResult?
    /// A patterns result (Daily Review only).
    var patterns: [PatternResult]?

    init() {}
}

// MARK: - Results

nonisolated struct InsightsCollecting: Codable, Equatable, Sendable {
    var have: Int
    var need: Int
}

nonisolated struct BaselineResult: Codable, Equatable, Sendable {
    var status: String
    var n: Int
    var needed: Int
    var coverage: Double
    var mean: Double?
    var sd: Double?
    var sdFloored: Bool
    var low: Double?
    var high: Double?
    var recent: Double?
    var delta: Double?
    var pct: Double?
    var z: Double?
    var confidence: String

    enum CodingKeys: String, CodingKey {
        case status, n, needed, coverage, mean, sd, sdFloored = "sd_floored", low, high, recent, delta, pct, z, confidence
    }

    var isReady: Bool { status == "ok" }
}

nonisolated struct TrendResult: Codable, Equatable, Sendable {
    var status: String
    var n: Int
    var needed: Int
    var slopePctPerWeek: Double?
    /// improving, stable, declining or changing (band metrics).
    var direction: String?

    enum CodingKeys: String, CodingKey { case status, n, needed, slopePctPerWeek = "slope_pct_per_week", direction }
}

nonisolated struct OvernightValue: Codable, Equatable, Sendable {
    var value: Double?
    var n: Int
    var fallback: Bool
}

nonisolated struct TrainingLoadResult: Codable, Equatable, Sendable {
    var day: String
    var load: Double
    var minutes: Double
    var sessions: Int
    var mean28d: Double
    var ratio: Double?
    /// none, light, moderate or high.
    var category: String
    var label: String

    enum CodingKeys: String, CodingKey { case day, load, minutes, sessions, mean28d = "mean_28d", ratio, category, label }
}

nonisolated struct RecoveryComponent: Codable, Equatable, Sendable, Identifiable {
    var id: String
    var weight: Double
    var available: Bool
    var value: Double?
    var baseline: Double?
    var delta: Double?
    var pct: Double?
    var z: Double?
    var subscore: Double?
    var impact: Double?
    var baselineN: Int
    var baselineConfidence: String
    var fallback: Bool
    var consistencyDeviationMin: Double?
    var consistencySubscore: Double?

    enum CodingKeys: String, CodingKey {
        case id, weight, available, value, baseline, delta, pct, z, subscore, impact, baselineN = "baseline_n",
             baselineConfidence = "baseline_confidence", fallback,
             consistencyDeviationMin = "consistency_deviation_min", consistencySubscore = "consistency_subscore"
    }
}

nonisolated struct RecoverySignal: Codable, Equatable, Sendable, Identifiable {
    var id: String
    var impact: Double
    var text: String
}

nonisolated struct RecoveryLoad: Codable, Equatable, Sendable {
    var day: String
    var load: Double
    var mean28d: Double
    var ratio: Double?
    var category: String
    var label: String
    var modifier: Int

    enum CodingKeys: String, CodingKey { case day, load, mean28d = "mean_28d", ratio, category, label, modifier }
}

nonisolated struct RecoveryResult: Codable, Equatable, Sendable {
    var day: String
    /// ok, collecting, no_sleep or no_heart_data.
    var status: String
    var score: Int?
    /// good, moderate or low.
    var label: String?
    var labelText: String?
    var recommendation: String?
    var confidence: String?
    var collecting: InsightsCollecting?
    var components: [RecoveryComponent]
    var positives: [RecoverySignal]
    var negatives: [RecoverySignal]
    var load: RecoveryLoad?

    enum CodingKeys: String, CodingKey {
        case day, status, score, label, labelText = "label_text", recommendation, confidence, collecting, components,
             positives, negatives, load
    }

    var isReady: Bool { status == "ok" && score != nil }
}

nonisolated struct HealthAgeMarker: Codable, Equatable, Sendable, Identifiable {
    var id: String
    var method: String
    var available: Bool
    var value: Double?
    var secondaryValue: Double?
    /// body_fat or bmi for body composition.
    var basis: String?
    var days: Int
    var neededDays: Int
    var equivalentAge: Double?
    var offsetYears: Double?
    var weight: Double
    var contributionYears: Double?

    enum CodingKeys: String, CodingKey {
        case id, method, available, value, secondaryValue = "secondary_value", basis, days, neededDays = "needed_days",
             equivalentAge = "equivalent_age", offsetYears = "offset_years", weight,
             contributionYears = "contribution_years"
    }
}

nonisolated struct HealthAgeResult: Codable, Equatable, Sendable {
    var asOf: String
    /// ok, no_birthday, unsupported_age or collecting.
    var status: String
    var actualAge: Double?
    var healthAge: Double?
    var difference: Double?
    var confidence: String?
    var markers: [HealthAgeMarker]
    var collecting: InsightsCollecting?
    var markersAvailable: Int
    var markersNeeded: Int

    enum CodingKeys: String, CodingKey {
        case asOf = "as_of", status, actualAge = "actual_age", healthAge = "health_age", difference, confidence,
             markers, collecting, markersAvailable = "markers_available", markersNeeded = "markers_needed"
    }

    var isReady: Bool { status == "ok" && healthAge != nil }
}

nonisolated struct HealthAgePacePoint: Codable, Equatable, Sendable {
    var day: String
    var healthAge: Double?
    var difference: Double?

    enum CodingKeys: String, CodingKey { case day, healthAge = "health_age", difference }
}

nonisolated struct HealthAgePaceResult: Codable, Equatable, Sendable {
    var status: String
    var have: Int
    var needed: Int
    /// Years of Health Age change per calendar year.
    var pace: Double?
    /// improving, stable or declining.
    var direction: String?
    var points: [HealthAgePacePoint]
}

nonisolated struct ReviewArea: Codable, Equatable, Sendable, Identifiable {
    var id: String
    var included: Bool
    var score: Int?
    var weight: Double
    var detail: [String: InsightsParam]?
}

nonisolated struct ReviewItem: Codable, Equatable, Sendable {
    var ruleId: String
    var params: [String: InsightsParam]
    var text: String

    enum CodingKeys: String, CodingKey { case ruleId = "rule_id", params, text }
}

nonisolated struct DailyReviewResult: Codable, Equatable, Sendable {
    var day: String
    var dayScore: Int?
    var areas: [ReviewArea]
    var notLogged: [ReviewItem]
    var wentWell: [ReviewItem]
    var needsAttention: [ReviewItem]
    var improve: [ReviewItem]
    var reduce: [ReviewItem]

    enum CodingKeys: String, CodingKey {
        case day, dayScore = "day_score", areas, notLogged = "not_logged", wentWell = "went_well",
             needsAttention = "needs_attention", improve, reduce
    }

    /// Items of a category id (went_well, needs_attention, improve, reduce).
    func items(_ category: String) -> [ReviewItem] {
        switch category {
        case "went_well": wentWell
        case "needs_attention": needsAttention
        case "improve": improve
        case "reduce": reduce
        default: []
        }
    }
}

nonisolated struct PatternResult: Codable, Equatable, Sendable, Identifiable {
    var id: String
    /// ok or insufficient.
    var status: String
    var nExposed: Int
    var nUnexposed: Int
    var needed: Int
    var meanExposed: Double?
    var meanUnexposed: Double?
    var diff: Double?
    var t: Double?
    var d: Double?
    var surfaced: Bool
    var text: String?
    var reviewCategory: String?

    enum CodingKeys: String, CodingKey {
        case id, status, nExposed = "n_exposed", nUnexposed = "n_unexposed", needed, meanExposed = "mean_exposed",
             meanUnexposed = "mean_unexposed", diff, t, d, surfaced, text, reviewCategory = "review_category"
    }
}

/// What the AI step and the read-only actions see.
nonisolated struct InsightsSummary: Codable, Equatable, Sendable {
    var recovery: RecoveryResult?
    var healthAge: HealthAgeResult?
    var healthAgePace: HealthAgePaceResult?
    var dailyReview: DailyReviewResult?
    var patterns: [PatternResult]?

    enum CodingKeys: String, CodingKey {
        case recovery, healthAge = "health_age", healthAgePace = "health_age_pace", dailyReview = "daily_review",
             patterns
    }

    init(recovery: RecoveryResult? = nil, healthAge: HealthAgeResult? = nil, healthAgePace: HealthAgePaceResult? = nil,
         dailyReview: DailyReviewResult? = nil, patterns: [PatternResult]? = nil) {
        self.recovery = recovery
        self.healthAge = healthAge
        self.healthAgePace = healthAgePace
        self.dailyReview = dailyReview
        self.patterns = patterns
    }
}

// MARK: - AI

nonisolated enum InsightsAIKind: String, Codable, CaseIterable, Sendable {
    case recovery, healthAge = "health_age", dailyReview = "daily_review"
}

nonisolated enum InsightsAIVariant: String, Codable, CaseIterable, Sendable {
    case cloud, local
}

nonisolated struct InsightsPrompt: Codable, Equatable, Sendable {
    var system: String
    var user: String
}

nonisolated struct InsightsExplanation: Codable, Equatable, Sendable {
    var headline: String
    var bullets: [String]
}

nonisolated struct InsightsAIValidation: Codable, Equatable, Sendable {
    var ok: Bool
    var errors: [String]
    var output: InsightsExplanation?
}
