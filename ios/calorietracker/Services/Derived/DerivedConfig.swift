import Foundation

/// Typed view of `shared/derived/derived_config.json` (bundled as `Services/Derived/Resources/derived_config.json`,
/// byte-identical to the shared file). Every constant the derived engines use comes from `thresholds`.
nonisolated struct DerivedConfig: Decodable, Sendable {
    let format: String
    let configVersion: Int
    let algoVersion: Int
    let categories: [String]
    let disclaimer: String
    let labels: [String: [String]]
    let thresholds: Thresholds

    enum CodingKeys: String, CodingKey {
        case format, configVersion = "config_version", algoVersion = "algo_version", categories, disclaimer, labels,
             thresholds
    }

    struct Thresholds: Decodable, Sendable {
        let activeHourFirst: Int
        let activeHourLast: Int
        let activeHourSteps: Double
        let asymmetryFlagCount: Int
        let asymmetryPct: Double
        let bmi: [String: [Double]]
        let bmrMinShare: Double
        let briskCadence: Double
        let daytimeMinMinutes: Int
        let dipMinWakeMin: Int
        let episodeGapHours: Double
        let etaMinRate: Double
        let ewmaAlpha: Double
        let freeWakeWeekdays: [Int]
        let healthyBmi: [String: [Double]]
        let heightConflictM: Double
        let hrMaxLookbackDays: Int
        let hrValidMax: Double
        let hrValidMin: Double
        let hrmaxZones: [Double]
        let hrrZones: [Double]
        let mifflin: [String: Double]
        let palBands: [Double]
        let peakMinutes: Int
        let phoneMinWearableSteps: Double
        let phoneRatio: Double
        let rateMinWeighins: Int
        let rateWindowDays: Int
        let regularityMinNights: Int
        let regularityWindowDays: Int
        let rhrMinCoverage: Double
        let rhrMinSleepMin: Double
        let rhrWindowMin: Int
        let sedentaryEndHour: Int
        let sedentaryMinMinutes: Int
        let sedentaryStartHour: Int
        let sedentaryStillMin: Int
        let sleepNeedMin: Double
        let slowGaitMps: Double
        let socialMinFree: Int
        let socialMinWork: Int
        let soundExchangeDb: Double
        let soundRefDb: Double
        let soundWeeklyHours: Double
        let sriMinPairs: Int
        let stepBands: [Double]
        let strainDeltaBpm: Double
        let strainMinDays: Int
        let strainSdFloor: Double
        let strainWindowDays: Int
        let strideFactor: [String: Double]
        let strideMinSteps: Double
        let tanaka: [Double]
        let tefShare: Double
        let trimpA: [String: Double]
        let trimpK: [String: Double]
        let trimpMinHrr: Double
        let uthFactor: Double
        let validWearMin: Int
        let wakeWindowHours: Double
        let wakeupMinGapMin: Double
        let walkingMaxSteps: Double
        let walkingMinMinutes: Int
        let walkingMinSteps: Double
        let walkingRunMin: Int

        enum CodingKeys: String, CodingKey {
            case activeHourFirst = "active_hour_first", activeHourLast = "active_hour_last",
                 activeHourSteps = "active_hour_steps", asymmetryFlagCount = "asymmetry_flag_count",
                 asymmetryPct = "asymmetry_pct", bmi, bmrMinShare = "bmr_min_share", briskCadence = "brisk_cadence",
                 daytimeMinMinutes = "daytime_min_minutes", dipMinWakeMin = "dip_min_wake_min",
                 episodeGapHours = "episode_gap_hours", etaMinRate = "eta_min_rate", ewmaAlpha = "ewma_alpha",
                 freeWakeWeekdays = "free_wake_weekdays", healthyBmi = "healthy_bmi",
                 heightConflictM = "height_conflict_m", hrMaxLookbackDays = "hr_max_lookback_days",
                 hrValidMax = "hr_valid_max", hrValidMin = "hr_valid_min", hrmaxZones = "hrmax_zones",
                 hrrZones = "hrr_zones", mifflin, palBands = "pal_bands", peakMinutes = "peak_minutes",
                 phoneMinWearableSteps = "phone_min_wearable_steps", phoneRatio = "phone_ratio",
                 rateMinWeighins = "rate_min_weighins", rateWindowDays = "rate_window_days",
                 regularityMinNights = "regularity_min_nights", regularityWindowDays = "regularity_window_days",
                 rhrMinCoverage = "rhr_min_coverage", rhrMinSleepMin = "rhr_min_sleep_min",
                 rhrWindowMin = "rhr_window_min", sedentaryEndHour = "sedentary_end_hour",
                 sedentaryMinMinutes = "sedentary_min_minutes", sedentaryStartHour = "sedentary_start_hour",
                 sedentaryStillMin = "sedentary_still_min", sleepNeedMin = "sleep_need_min",
                 slowGaitMps = "slow_gait_mps", socialMinFree = "social_min_free", socialMinWork = "social_min_work",
                 soundExchangeDb = "sound_exchange_db", soundRefDb = "sound_ref_db",
                 soundWeeklyHours = "sound_weekly_hours", sriMinPairs = "sri_min_pairs", stepBands = "step_bands",
                 strainDeltaBpm = "strain_delta_bpm", strainMinDays = "strain_min_days",
                 strainSdFloor = "strain_sd_floor", strainWindowDays = "strain_window_days",
                 strideFactor = "stride_factor", strideMinSteps = "stride_min_steps", tanaka, tefShare = "tef_share",
                 trimpA = "trimp_a", trimpK = "trimp_k", trimpMinHrr = "trimp_min_hrr", uthFactor = "uth_factor",
                 validWearMin = "valid_wear_min", wakeWindowHours = "wake_window_hours",
                 wakeupMinGapMin = "wakeup_min_gap_min", walkingMaxSteps = "walking_max_steps",
                 walkingMinMinutes = "walking_min_minutes", walkingMinSteps = "walking_min_steps",
                 walkingRunMin = "walking_run_min"
        }

        /// `table.get(sex or "other", table["other"])`.
        static func bySex(_ table: [String: Double], _ sex: String?) -> Double {
            let key = (sex?.isEmpty ?? true) ? "other" : sex!
            return table[key] ?? table["other"] ?? 0
        }
    }

    /// Translated disclaimer for display.
    var displayDisclaimer: String { ContractText.text("derived.disclaimer", disclaimer) }

    /// Translated band label `labels[name][index]` for display; engines keep the English list.
    func displayLabel(_ name: String, _ index: Int) -> String? {
        guard let list = labels[name], list.indices.contains(index) else { return nil }
        return ContractText.text("derived.labels.\(name).\(index)", list[index])
    }

    /// Translated form of an English band label produced by an engine (looked up by its position in `labels[name]`).
    func displayLabel(_ name: String, english: String) -> String {
        guard let index = labels[name]?.firstIndex(of: english) else { return english }
        return displayLabel(name, index) ?? english
    }

    // MARK: Loading

    /// The bundled config. Missing or malformed resources are a build error, so this traps loudly in development.
    static let shared: DerivedConfig = {
        guard let config = load() else { fatalError("Services/Derived/Resources/derived_config.json is missing or invalid") }
        return config
    }()

    static var bundledURL: URL? { Bundle.main.url(forResource: "derived_config", withExtension: "json") }

    static func load(from url: URL? = bundledURL) -> DerivedConfig? {
        guard let url, let data = try? Data(contentsOf: url) else { return nil }
        return decode(data)
    }

    static func decode(_ data: Data) -> DerivedConfig? {
        try? JSONDecoder().decode(DerivedConfig.self, from: data)
    }
}
