import Foundation

/// Typed view of `shared/insights/insights_config.json` (bundled as `Insights/Resources/insights_config.json`,
/// byte-identical to the shared file). Every constant the engines use comes from here.
nonisolated struct InsightsConfig: Decodable, Sendable {
    let configVersion: Int
    let ai: AI
    let dailyReview: DailyReview
    let disclaimers: [String: String]
    let healthAge: HealthAge
    let methodology: [String: Methodology]
    let metrics: [String: Metric]
    let patterns: Patterns
    let recovery: Recovery
    let sleep: Sleep
    let trainingLoad: TrainingLoad

    enum CodingKeys: String, CodingKey {
        case configVersion = "config_version", ai, dailyReview = "daily_review", disclaimers,
             healthAge = "health_age", methodology, metrics, patterns, recovery, sleep, trainingLoad = "training_load"
    }

    // MARK: Sections

    struct AI: Decodable, Sendable {
        let allowedNumbers: [Double]
        let blockedTerms: [String]
        let bulletMax: Int
        let bulletsMax: Int
        let headlineMax: Int
        let maxOutputTokens: [String: Int]
        let statusLabels: [String: String]
        let tasks: [String: String]

        enum CodingKeys: String, CodingKey {
            case allowedNumbers = "allowed_numbers", blockedTerms = "blocked_terms", bulletMax = "bullet_max",
                 bulletsMax = "bullets_max", headlineMax = "headline_max", maxOutputTokens = "max_output_tokens",
                 statusLabels = "status_labels", tasks
        }
    }

    struct Metric: Decodable, Sendable {
        let direction: String
        let healthType: [String: String?]
        let highConfidenceCoverage: Double
        let highConfidenceMinN: Int
        let label: String
        let minPoints: Int
        let recent: String
        let sdFloor: Double
        let trendDays: Int
        let trendMinPoints: Int
        let trendStablePctPerWeek: Double
        let unit: String
        let windowDays: Int

        enum CodingKeys: String, CodingKey {
            case direction, healthType = "health_type", highConfidenceCoverage = "high_confidence_coverage",
                 highConfidenceMinN = "high_confidence_min_n", label, minPoints = "min_points", recent,
                 sdFloor = "sd_floor", trendDays = "trend_days", trendMinPoints = "trend_min_points",
                 trendStablePctPerWeek = "trend_stable_pct_per_week", unit, windowDays = "window_days"
        }

        var iosHealthType: String? { healthType["ios"] ?? nil }
    }

    struct Sleep: Decodable, Sendable {
        let minNightMinutes: Double
        enum CodingKeys: String, CodingKey { case minNightMinutes = "min_night_minutes" }
    }

    struct TrainingLoad: Decodable, Sendable {
        let defaultIntensity: Double
        let effortDivisor: Double
        let highAbove: Double
        let intenseMinIntensity: Double
        let intenseMinMinutesWithoutEffort: Double
        let intensityMax: Double
        let intensityMin: Double
        let labels: [String: String]
        let lateHour: Int
        let lightBelow: Double
        let meanWindowDays: Int
        let source: String

        enum CodingKeys: String, CodingKey {
            case defaultIntensity = "default_intensity", effortDivisor = "effort_divisor", highAbove = "high_above",
                 intenseMinIntensity = "intense_min_intensity",
                 intenseMinMinutesWithoutEffort = "intense_min_minutes_without_effort", intensityMax = "intensity_max",
                 intensityMin = "intensity_min", labels, lateHour = "late_hour", lightBelow = "light_below",
                 meanWindowDays = "mean_window_days", source
        }
    }

    struct Recovery: Decodable, Sendable {
        struct Band: Decodable, Sendable {
            let id: String
            let label: String
            let min: Int
            let recommendation: String
        }

        struct Component: Decodable, Sendable {
            let id: String
            let metric: String
            let mode: String
            let weight: Double
            let toleranceZ: Double?
            let durationShare: Double?
            let consistencyShare: Double?

            enum CodingKeys: String, CodingKey {
                case id, metric, mode, weight, toleranceZ = "tolerance_z", durationShare = "duration_share",
                     consistencyShare = "consistency_share"
            }
        }

        struct LoadModifier: Decodable, Sendable {
            let high: Int
            let veryHigh: Int
            let veryHighRatio: Double
            enum CodingKeys: String, CodingKey { case high, veryHigh = "very_high", veryHighRatio = "very_high_ratio" }
        }

        let bands: [Band]
        let components: [Component]
        let consistencyMinNights: Int
        let consistencyWindowDays: Int
        let consistencyZeroAtMin: Double
        let contributors: [String: String]
        let heartComponents: [String]
        let loadModifier: LoadModifier
        let source: String
        let subscoreCenter: Double
        let subscoreSlope: Double

        enum CodingKeys: String, CodingKey {
            case bands, components, consistencyMinNights = "consistency_min_nights",
                 consistencyWindowDays = "consistency_window_days", consistencyZeroAtMin = "consistency_zero_at_min",
                 contributors, heartComponents = "heart_components", loadModifier = "load_modifier", source,
                 subscoreCenter = "subscore_center", subscoreSlope = "subscore_slope"
        }
    }

    struct HealthAge: Decodable, Sendable {
        struct Confidence: Decodable, Sendable {
            let highMinMarkers: Int
            let highRequires: String
            let mediumMinMarkers: Int
            enum CodingKeys: String, CodingKey {
                case highMinMarkers = "high_min_markers", highRequires = "high_requires",
                     mediumMinMarkers = "medium_min_markers"
            }
        }

        struct Pace: Decodable, Sendable {
            let decliningAbove: Double
            let improvingBelow: Double
            let minPoints: Int
            let weeks: Int
            enum CodingKeys: String, CodingKey {
                case decliningAbove = "declining_above", improvingBelow = "improving_below", minPoints = "min_points", weeks
            }
        }

        struct Marker: Decodable, Sendable {
            let id: String
            let label: String
            let method: String
            let minDays: Int
            let capYears: Double
            let weight: Double
            let unit: String
            let source: String
            let series: String?
            /// dose_response
            let points: [[Double]]?
            /// age_norm, not by kind: sex → [[age, value]]
            let tables: [String: [[Double]]]?
            /// age_norm by HRV kind: kind → sex → [[age, value]]
            let tablesByKind: [String: [String: [[Double]]]]?
            /// sleep
            let durationPoints: [[Double]]?
            let regularityPoints: [[Double]]?
            /// workouts
            let weeks: Int?
            let minutesPoints: [[Double]]?
            let activeSharePoints: [[Double]]?
            /// body composition
            let bmiPoints: [[Double]]?
            let bodyFatPoints: [String: [[Double]]]?

            enum CodingKeys: String, CodingKey {
                case id, label, method, minDays = "min_days", capYears = "cap_years", weight, unit, source, series,
                     points, tables, tablesByKind = "tables_by_kind", durationPoints = "duration_points",
                     regularityPoints = "regularity_points", weeks, minutesPoints = "minutes_points",
                     activeSharePoints = "active_share_points", bmiPoints = "bmi_points",
                     bodyFatPoints = "body_fat_points"
            }

            init(from decoder: Decoder) throws {
                let c = try decoder.container(keyedBy: CodingKeys.self)
                id = try c.decode(String.self, forKey: .id)
                label = try c.decode(String.self, forKey: .label)
                method = try c.decode(String.self, forKey: .method)
                minDays = try c.decode(Int.self, forKey: .minDays)
                capYears = try c.decode(Double.self, forKey: .capYears)
                weight = try c.decode(Double.self, forKey: .weight)
                unit = try c.decode(String.self, forKey: .unit)
                source = try c.decode(String.self, forKey: .source)
                series = try c.decodeIfPresent(String.self, forKey: .series)
                points = try c.decodeIfPresent([[Double]].self, forKey: .points)
                let byKind = try c.decodeIfPresent(Bool.self, forKey: .tablesByKind) ?? false
                if byKind {
                    tables = nil
                    tablesByKind = try c.decodeIfPresent([String: [String: [[Double]]]].self, forKey: .tables)
                } else {
                    tables = try c.decodeIfPresent([String: [[Double]]].self, forKey: .tables)
                    tablesByKind = nil
                }
                durationPoints = try c.decodeIfPresent([[Double]].self, forKey: .durationPoints)
                regularityPoints = try c.decodeIfPresent([[Double]].self, forKey: .regularityPoints)
                weeks = try c.decodeIfPresent(Int.self, forKey: .weeks)
                minutesPoints = try c.decodeIfPresent([[Double]].self, forKey: .minutesPoints)
                activeSharePoints = try c.decodeIfPresent([[Double]].self, forKey: .activeSharePoints)
                bmiPoints = try c.decodeIfPresent([[Double]].self, forKey: .bmiPoints)
                bodyFatPoints = try c.decodeIfPresent([String: [[Double]]].self, forKey: .bodyFatPoints)
            }
        }

        let ageMax: Double
        let ageMin: Double
        let collectingDays: Int
        let confidence: Confidence
        let coreMarkers: [String]
        let daysPerYear: Double
        let markers: [Marker]
        let minActualAge: Double
        let minMarkers: Int
        let pace: Pace
        let source: String
        let totalCapYears: Double
        let windowDays: Int

        enum CodingKeys: String, CodingKey {
            case ageMax = "age_max", ageMin = "age_min", collectingDays = "collecting_days", confidence,
                 coreMarkers = "core_markers", daysPerYear = "days_per_year", markers, minActualAge = "min_actual_age",
                 minMarkers = "min_markers", pace, source, totalCapYears = "total_cap_years", windowDays = "window_days"
        }
    }

    struct DailyReview: Decodable, Sendable {
        struct Area: Decodable, Sendable {
            let id: String
            let label: String
            let weight: Double
        }

        struct Nutrition: Decodable, Sendable {
            let calorieTolerancePct: Double
            let calorieZeroAtPct: Double
            let macroTolerancePct: Double
            let macroZeroAtPct: Double
            let proteinMinShare: Double
            enum CodingKeys: String, CodingKey {
                case calorieTolerancePct = "calorie_tolerance_pct", calorieZeroAtPct = "calorie_zero_at_pct",
                     macroTolerancePct = "macro_tolerance_pct", macroZeroAtPct = "macro_zero_at_pct",
                     proteinMinShare = "protein_min_share"
            }
        }

        struct ReduceNutrient: Decodable, Sendable {
            let goalKey: String
            let id: String
            let label: String
            let unit: String
            enum CodingKeys: String, CodingKey { case goalKey = "goal_key", id, label, unit }
        }

        struct Rule: Decodable, Sendable {
            let id: String
            let category: String
            let template: String
            let params: [String]
            let minShare: Double?
            let belowShare: Double?
            let minMinutes: Double?
            let belowMinutes: Double?
            let marginMinutes: Double?
            let stepsPerMinute: Double?
            let walkRoundMin: Int?
            let maxMinutes: Int?
            let roundMin: Int?

            enum CodingKeys: String, CodingKey {
                case id, category, template, params, minShare = "min_share", belowShare = "below_share",
                     minMinutes = "min_minutes", belowMinutes = "below_minutes", marginMinutes = "margin_minutes",
                     stepsPerMinute = "steps_per_minute", walkRoundMin = "walk_round_min", maxMinutes = "max_minutes",
                     roundMin = "round_min"
            }
        }

        let areas: [Area]
        let averageMinDays: Int
        let averageWindowDays: Int
        let maxItemsPerCategory: Int
        let notLoggedTemplate: String
        let nutrition: Nutrition
        let reduceNutrients: [ReduceNutrient]
        let rules: [Rule]
        let sleepTargetMin: Double
        let trainingRestScore: Double

        enum CodingKeys: String, CodingKey {
            case areas, averageMinDays = "average_min_days", averageWindowDays = "average_window_days",
                 maxItemsPerCategory = "max_items_per_category", notLoggedTemplate = "not_logged_template", nutrition,
                 reduceNutrients = "reduce_nutrients", rules, sleepTargetMin = "sleep_target_min",
                 trainingRestScore = "training_rest_score"
        }
    }

    struct Patterns: Decodable, Sendable {
        struct Pair: Decodable, Sendable {
            let id: String
            let exposure: String
            let outcome: String
            let lagDays: Int
            let decimals: Int
            let unit: String
            let moreWord: String
            let lessWord: String
            let template: String
            let reviewCategory: String?

            enum CodingKeys: String, CodingKey {
                case id, exposure, outcome, lagDays = "lag_days", decimals, unit, moreWord = "more_word",
                     lessWord = "less_word", template, reviewCategory = "review_category"
            }
        }

        let minAbsD: Double
        let minAbsT: Double
        let minGroup: Int
        let pairs: [Pair]
        let partialTodayOutcomes: [String]
        let shortSleepMarginMin: Double
        let source: String
        let windowDays: Int

        enum CodingKeys: String, CodingKey {
            case minAbsD = "min_abs_d", minAbsT = "min_abs_t", minGroup = "min_group", pairs,
                 partialTodayOutcomes = "partial_today_outcomes", shortSleepMarginMin = "short_sleep_margin_min",
                 source, windowDays = "window_days"
        }
    }

    struct Methodology: Decodable, Sendable {
        struct Section: Decodable, Sendable, Hashable {
            let heading: String
            let body: String
        }

        let title: String
        let sections: [Section]
    }

    // MARK: Loading

    /// The bundled config. Missing or malformed resources are a build error, so this traps loudly in development.
    static let shared: InsightsConfig = {
        guard let config = load() else { fatalError("Insights/Resources/insights_config.json is missing or invalid") }
        return config
    }()

    static var bundledURL: URL? { Bundle.main.url(forResource: "insights_config", withExtension: "json") }

    static func load(from url: URL? = bundledURL) -> InsightsConfig? {
        guard let url, let data = try? Data(contentsOf: url) else { return nil }
        return decode(data)
    }

    static func decode(_ data: Data) -> InsightsConfig? {
        try? JSONDecoder().decode(InsightsConfig.self, from: data)
    }

    func metric(_ id: String) -> Metric? { metrics[id] }

    func marker(_ id: String) -> HealthAge.Marker? { healthAge.markers.first { $0.id == id } }

    func disclaimer(_ key: String) -> String { disclaimers[key] ?? disclaimers["general"] ?? "" }
}
