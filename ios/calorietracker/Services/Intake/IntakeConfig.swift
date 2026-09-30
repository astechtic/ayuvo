import Foundation

/// Typed view of `shared/intake/intake_config.json` (bundled as `Services/Intake/Resources/intake_config.json`,
/// byte-identical to the shared file). Every constant the intake engines use comes from here (docs/intake-metrics.md).
nonisolated struct IntakeConfig: Decodable, Sendable {
    let format: String
    let configVersion: Int
    let algoVersion: Int
    let disclaimer: String
    let dri: DRI
    let labLinks: [LabLink]
    let muscleGroups: [String: [String]]
    let sources: [String: String]
    let thresholds: Thresholds

    enum CodingKeys: String, CodingKey {
        case format, configVersion = "config_version", algoVersion = "algo_version", disclaimer, dri,
             labLinks = "lab_links", muscleGroups = "muscle_groups", sources, thresholds
    }

    struct AgeBand: Decodable, Sendable {
        let id: String
        let min: Double
        let max: Double
    }

    struct DRI: Decodable, Sendable {
        let ageBands: [AgeBand]
        /// nutrient → band id → [male, female]
        let goals: [String: [String: [Double]]]
        let source: String
        let upper: [String: Double]

        enum CodingKeys: String, CodingKey {
            case ageBands = "age_bands", goals, source, upper
        }
    }

    struct LabLink: Decodable, Sendable {
        let analytes: [String]
        let id: String
        let nutrient: String
    }

    struct Thresholds: Decodable, Sendable {
        let adaptiveMinIntakeDays: Int
        let adaptiveMinWeighins: Int
        let adaptiveWindowDays: Int
        let adherentPct: Double
        let caffeineMaxMg: Double
        let e1rmMaxReps: Double
        let energyPerKg: Double
        let ewmaAlpha: Double
        let fiberPer1000kcalTarget: Double
        let ironAbsorptionWindowMin: Double
        let ironRichMg: Double
        let naKRatioMax: Double
        let onTimeMin: Double
        let pairMinGroup: Int
        let proteinGPerKgTraining: [Double]
        let proteinPerMealGPerKg: Double
        let saturatedFatMaxPct: Double
        let shortfallMinDays: Int
        let shortfallPct: Double
        let suggestDelayMin: Double
        let suggestMinTaken: Int
        let weeklySetsMax: Double
        let weeklySetsMin: Double

        enum CodingKeys: String, CodingKey {
            case adaptiveMinIntakeDays = "adaptive_min_intake_days", adaptiveMinWeighins = "adaptive_min_weighins",
                 adaptiveWindowDays = "adaptive_window_days", adherentPct = "adherent_pct",
                 caffeineMaxMg = "caffeine_max_mg", e1rmMaxReps = "e1rm_max_reps", energyPerKg = "energy_per_kg",
                 ewmaAlpha = "ewma_alpha", fiberPer1000kcalTarget = "fiber_per_1000kcal_target",
                 ironAbsorptionWindowMin = "iron_absorption_window_min", ironRichMg = "iron_rich_mg",
                 naKRatioMax = "na_k_ratio_max", onTimeMin = "on_time_min", pairMinGroup = "pair_min_group",
                 proteinGPerKgTraining = "protein_g_per_kg_training", proteinPerMealGPerKg = "protein_per_meal_g_per_kg",
                 saturatedFatMaxPct = "saturated_fat_max_pct", shortfallMinDays = "shortfall_min_days",
                 shortfallPct = "shortfall_pct", suggestDelayMin = "suggest_delay_min",
                 suggestMinTaken = "suggest_min_taken", weeklySetsMax = "weekly_sets_max",
                 weeklySetsMin = "weekly_sets_min"
        }
    }

    // MARK: Loading

    /// The bundled config. Missing or malformed resources are a build error, so this traps loudly in development.
    static let shared: IntakeConfig = {
        guard let config = load() else { fatalError("Services/Intake/Resources/intake_config.json is missing or invalid") }
        return config
    }()

    static var bundledURL: URL? { Bundle.main.url(forResource: "intake_config", withExtension: "json") }

    static func load(from url: URL? = bundledURL) -> IntakeConfig? {
        guard let url, let data = try? Data(contentsOf: url) else { return nil }
        return decode(data)
    }

    static func decode(_ data: Data) -> IntakeConfig? {
        try? JSONDecoder().decode(IntakeConfig.self, from: data)
    }
}

/// Helpers shared by the intake engines (the top of `scripts/intake_reference.py`).
nonisolated enum IntakeMath {
    /// `datetime.fromtimestamp(ms / 1000, ZoneInfo(tz))` hour × 60 + minute.
    static func minuteOfDay(_ ms: Int64, _ tz: TimeZone) -> Int {
        let t = DerivedDay.localTime(ms, tz)
        return t.hour * 60 + t.minute
    }

    /// Python-style stable sort by an Int64 key.
    static func stableSorted<T>(_ values: [T], by key: (T) -> Int64) -> [T] {
        values.enumerated()
            .sorted { a, b in
                let ka = key(a.element), kb = key(b.element)
                return ka != kb ? ka < kb : a.offset < b.offset
            }
            .map(\.element)
    }

    /// `sorted(keys)` with Python string ordering.
    static func sortedKeys<V>(_ dict: [String: V]) -> [String] {
        dict.keys.sorted(by: DerivedMath.pyLess)
    }
}
