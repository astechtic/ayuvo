import Foundation

/// Typed view of `shared/cycle/cycle_config.json` (bundled from `Cycle/Resources/cycle_config.json`, a byte-identical
/// copy kept by `scripts/cycle_contract_check.py --write`). Docs: docs/cycle-tracking.md.
nonisolated struct CycleConfig: Decodable, Sendable {
    struct Defaults: Decodable, Sendable {
        let cycleLength: Int
        let periodLength: Int
        let lutealLength: Int
        let reminderDaysBefore: Int
        let reminderTime: String
    }

    struct Limits: Decodable, Sendable {
        let cycleMin: Int
        let cycleMax: Int
        let periodMin: Int
        let periodMax: Int
        let settingCycleMin: Int
        let settingCycleMax: Int
        let settingPeriodMin: Int
        let settingPeriodMax: Int
        let lutealMin: Int
        let lutealMax: Int
        let painMax: Int
        let noteMaxChars: Int
    }

    struct Prediction: Decodable, Sendable {
        let historyWindow: Int
        let historyMinCycles: Int
        let trimmedRangeMinCycles: Int
        let defaultRangeDays: Int
        let projectCycles: Int
        let openPeriodExtraDays: Int
    }

    struct Variability: Decodable, Sendable {
        let minCycles: Int
        let highRangeDays: Int
        let highSdDays: Double
    }

    struct Fertile: Decodable, Sendable {
        let daysBeforeOvulation: Int
        let daysAfterOvulation: Int
    }

    struct InsightRules: Decodable, Sendable {
        let recentCycles: Int
        let typicalCycleLow: Int
        let typicalCycleHigh: Int
        let outOfRangeMinCount: Int
        let lateDays: Int
        let longPeriodDays: Int
        let severePain: Double
    }

    struct FlowLevel: Decodable, Sendable, Identifiable {
        let key: String
        let title: String
        let rank: Int
        let period: Bool
        let healthkit: String?
        let healthConnect: String?
        var id: String { key }
    }

    struct Symptom: Decodable, Sendable, Identifiable {
        let key: String
        let group: String
        let title: String
        let healthkit: String?
        var id: String { key }
    }

    /// Moods, pain locations, symptom groups and phases: a key and an English title.
    struct Item: Decodable, Sendable, Identifiable {
        let key: String
        let title: String
        var id: String { key }
    }

    struct Basis: Decodable, Sendable, Identifiable {
        let key: String
        let title: String
        let about: String
        var id: String { key }
    }

    struct Insight: Decodable, Sendable, Identifiable {
        let key: String
        let template: String
        let professional: Bool
        var id: String { key }
    }

    let format: String
    let configVersion: Int
    let algoVersion: String
    let defaults: Defaults
    let limits: Limits
    let prediction: Prediction
    let variability: Variability
    let fertile: Fertile
    let insightRules: InsightRules
    let flowLevels: [FlowLevel]
    let symptoms: [Symptom]
    let symptomGroups: [Item]
    let moods: [Item]
    let painLocations: [Item]
    let phases: [Item]
    let basis: [Basis]
    let insights: [Insight]
    let disclaimer: String
    let fertilityNote: String

    func flowLevel(_ key: String?) -> FlowLevel? { key.flatMap { k in flowLevels.first { $0.key == k } } }
    func insight(_ key: String) -> Insight? { insights.first { $0.key == key } }

    static func decode(_ data: Data) throws -> CycleConfig {
        let decoder = JSONDecoder()
        decoder.keyDecodingStrategy = .convertFromSnakeCase
        return try decoder.decode(CycleConfig.self, from: data)
    }

    static var bundledURL: URL? { Bundle.main.url(forResource: "cycle_config", withExtension: "json") }

    static func load() -> CycleConfig? {
        guard let url = bundledURL, let data = try? Data(contentsOf: url) else { return nil }
        return try? decode(data)
    }

    static let shared: CycleConfig = {
        guard let config = load() else { fatalError("Cycle/Resources/cycle_config.json is missing or invalid") }
        return config
    }()
}

/// Typed view of `shared/cycle/coach.json` (bundled from `Cycle/Resources/coach.json`): the Coach header, summary line
/// templates and guardrails (docs/cycle-tracking.md §8).
/// `lines` keeps the file's snake_case keys (`cycles`, `no_cycles`, …).
nonisolated struct CycleCoachConfig: Sendable {
    enum DecodeError: Error { case invalid }

    let format: String
    let version: Int
    let mentionsWords: [String]
    let neverShared: [String]
    let header: String
    let notAvailableLine: String
    let lines: [String: String]
    let prompt: [String]

    static var bundledURL: URL? { Bundle.main.url(forResource: "coach", withExtension: "json") }

    /// Parsed by hand: a key-decoding strategy would also rewrite the keys of `lines`.
    static func decode(_ data: Data) throws -> CycleCoachConfig {
        guard let o = try JSONSerialization.jsonObject(with: data) as? [String: Any],
              let format = o["format"] as? String, let version = o["version"] as? Int,
              let header = o["header"] as? String, let notAvailable = o["not_available_line"] as? String
        else { throw DecodeError.invalid }
        return CycleCoachConfig(
            format: format, version: version, mentionsWords: o["mentions_words"] as? [String] ?? [],
            neverShared: o["never_shared"] as? [String] ?? [], header: header, notAvailableLine: notAvailable,
            lines: o["lines"] as? [String: String] ?? [:], prompt: o["prompt"] as? [String] ?? [])
    }

    static func load() -> CycleCoachConfig? {
        guard let url = bundledURL, let data = try? Data(contentsOf: url) else { return nil }
        return try? decode(data)
    }
}
