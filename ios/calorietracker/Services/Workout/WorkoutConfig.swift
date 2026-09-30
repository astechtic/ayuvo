import Foundation

/// Typed view of `shared/workout/workout_config.json` (bundled as `Services/Workout/Resources/workout_config.json`,
/// byte-identical to the shared file). Every constant the workout engines use comes from here.
nonisolated struct WorkoutConfig: Decodable, Sendable {
    let format: String
    let configVersion: Int
    let algoVersion: Int
    let disclaimer: String
    let sources: [String: String]
    let sports: [String: Sport]
    let thresholds: Thresholds

    enum CodingKeys: String, CodingKey {
        case format, configVersion = "config_version", algoVersion = "algo_version", disclaimer, sources, sports,
             thresholds
    }

    struct Sport: Decodable, Sendable {
        let autoPauseSpeedMps: Double
        let hcExercise: String
        let hkActivity: String
        let maxSpeedMps: Double
        let metItemId: String
        let title: String

        enum CodingKeys: String, CodingKey {
            case autoPauseSpeedMps = "auto_pause_speed_mps", hcExercise = "hc_exercise", hkActivity = "hk_activity",
                 maxSpeedMps = "max_speed_mps", metItemId = "met_item_id", title
        }
    }

    /// Translated disclaimer for display (table "Contracts").
    var displayDisclaimer: String { ContractText.text("workout.disclaimer", disclaimer) }

    struct Thresholds: Decodable, Sendable {
        let detectBpm: Double
        let detectHrr: Double
        let elevationHysteresisM: Double
        let hrValidMax: Double
        let hrValidMin: Double
        let hrmaxZones: [Double]
        let hrr1AbnormalBelow: Double
        let hrrZones: [Double]
        let keytel: [String: [Double]]
        let keytelMinCoverage: Double
        let maxGrade: Double
        let maxHAccuracyM: Double
        let maxHrr: Double
        let maxSampleGapS: Double
        let mergeGapMin: Double
        let minHrr: Double
        let minSegmentS: Double
        let minWindowMin: Double
        let recoveryToleranceS: Double
        let trimpK: [String: Double]
        let walkRunSplitMps: Double

        enum CodingKeys: String, CodingKey {
            case detectBpm = "detect_bpm", detectHrr = "detect_hrr", elevationHysteresisM = "elevation_hysteresis_m",
                 hrValidMax = "hr_valid_max", hrValidMin = "hr_valid_min", hrmaxZones = "hrmax_zones",
                 hrr1AbnormalBelow = "hrr1_abnormal_below", hrrZones = "hrr_zones", keytel,
                 keytelMinCoverage = "keytel_min_coverage", maxGrade = "max_grade", maxHAccuracyM = "max_h_accuracy_m",
                 maxHrr = "max_hrr", maxSampleGapS = "max_sample_gap_s", mergeGapMin = "merge_gap_min",
                 minHrr = "min_hrr", minSegmentS = "min_segment_s", minWindowMin = "min_window_min",
                 recoveryToleranceS = "recovery_tolerance_s", trimpK = "trimp_k", walkRunSplitMps = "walk_run_split_mps"
        }
    }

    // MARK: Loading

    /// The bundled config. Missing or malformed resources are a build error, so this traps loudly in development.
    static let shared: WorkoutConfig = {
        guard let config = load() else { fatalError("Services/Workout/Resources/workout_config.json is missing or invalid") }
        return config
    }()

    static var bundledURL: URL? { Bundle.main.url(forResource: "workout_config", withExtension: "json") }

    static func load(from url: URL? = bundledURL) -> WorkoutConfig? {
        guard let url, let data = try? Data(contentsOf: url) else { return nil }
        return decode(data)
    }

    static func decode(_ data: Data) -> WorkoutConfig? {
        try? JSONDecoder().decode(WorkoutConfig.self, from: data)
    }
}
