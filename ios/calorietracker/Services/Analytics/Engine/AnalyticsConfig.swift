import Foundation

/// `shared/analytics/analytics_config.json` and `shared/health/source_policy.json`, bundled byte-identical
/// (`scripts/analytics_contract_check.py --write`). Kept as JSON so the engine reads it exactly like the reference.
nonisolated struct AnalyticsConfig: Sendable {
    let raw: AJ
    let policy: AJ

    subscript(key: String) -> AJ { raw[key] }

    var configVersion: Int { raw["config_version"].int ?? 0 }
    var tolerance: Double { raw["tolerance"].double ?? 1e-9 }

    func metric(_ id: String) -> AJ { raw["metrics"][id] }
    func cap(_ classification: String) -> Double { raw["classifications"][classification]["validity_cap"].double ?? 1 }
    func minPoints(window: Int) -> Int { raw["baseline"]["min_points"][String(window)].int ?? window }
    var madScale: Double { raw["baseline"]["mad_scale"].double ?? 1.4826 }

    // MARK: Loading

    static let shared: AnalyticsConfig = {
        guard let config = load() else {
            fatalError("Services/Analytics/Resources/analytics_config.json or source_policy.json is missing or invalid")
        }
        return config
    }()

    static var bundledConfigURL: URL? { Bundle.main.url(forResource: "analytics_config", withExtension: "json") }
    static var bundledPolicyURL: URL? { Bundle.main.url(forResource: "source_policy", withExtension: "json") }

    static func load(config: URL? = bundledConfigURL, policy: URL? = bundledPolicyURL) -> AnalyticsConfig? {
        guard let config, let policy, let c = try? Data(contentsOf: config), let p = try? Data(contentsOf: policy) else {
            return nil
        }
        return decode(config: c, policy: p)
    }

    static func decode(config: Data, policy: Data) -> AnalyticsConfig? {
        guard let c = AJ.parse(config), let p = AJ.parse(policy), c["format"].string == "ayuvo-analytics-config",
              p["format"].string == "ayuvo-source-policy" else { return nil }
        return AnalyticsConfig(raw: c, policy: p)
    }
}
