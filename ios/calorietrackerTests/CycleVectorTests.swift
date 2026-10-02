import Foundation
import Testing
@testable import calorietracker

/// Runs every case of `shared/cycle/test-vectors/*.json` through `CycleEngine` (docs/cycle-tracking.md §3) and
/// requires equality with `scripts/cycle_reference.py`: `firstDifference` and, stricter, the same int / float kind and
/// bit-identical doubles.
struct CycleVectorTests {
    /// One file per function in `cycle_reference.FUNCTIONS`.
    nonisolated static let vectorFiles = [
        "apply_period_day", "cycles", "day_status", "days", "normalize", "settings", "snapshot", "trends", "validate_period",
    ]

    static var vectorsDirectory: URL {
        HealthTestFixtures.repoRootURL.appendingPathComponent("shared/cycle/test-vectors")
    }

    @Test func sharedVectorFilesMatchRunnersExactly() throws {
        let names = try FileManager.default.contentsOfDirectory(atPath: Self.vectorsDirectory.path)
            .filter { $0.hasSuffix(".json") }
            .map { String($0.dropLast(5)) }
        #expect(Set(names) == Set(Self.vectorFiles), "runners \(Self.vectorFiles.sorted()) vs files \(names.sorted())")
    }

    @Test(arguments: vectorFiles)
    func vectorFileMatchesReference(_ name: String) throws {
        let url = Self.vectorsDirectory.appendingPathComponent("\(name).json")
        let root = try VitalsJSON.parse(try Data(contentsOf: url))
        #expect(root["format"].string == "ayuvo-cycle-vectors")
        #expect(root["version"].double == 1)
        let function = try #require(root["function"].string)
        #expect(function == name)
        let cases = root["cases"].array ?? []
        #expect(!cases.isEmpty)
        var passed = 0
        var failures: [String] = []
        var strict: [String] = []
        for c in cases {
            let caseName = c["name"].string ?? "?"
            let actual = try CycleVectorRunner.run(function: function, input: c["input"], config: CycleConfig.shared)
            if let diff = InsightsVectorRunner.firstDifference(actual, c["expected"]) {
                failures.append("\(caseName): \(diff)")
            } else {
                passed += 1
            }
            if let diff = VitalsVectorRunner.strictDifference(actual, c["expected"]) { strict.append("\(caseName): \(diff)") }
        }
        print("CYCLE-VECTORS \(name).json \(passed)/\(cases.count) strict-failures \(strict.count)")
        #expect(failures.isEmpty, Comment(rawValue: "\(name).json \(passed)/\(cases.count) passed\n" + failures.joined(separator: "\n")))
        #expect(strict.isEmpty, Comment(rawValue: "strict differences:\n" + strict.joined(separator: "\n")))
    }

    @Test func bundledResourcesAreByteIdenticalToShared() throws {
        for (file, url) in [("cycle_config.json", CycleConfig.bundledURL), ("coach.json", CycleCoachConfig.bundledURL)] {
            let shared = try Data(contentsOf: HealthTestFixtures.repoRootURL.appendingPathComponent("shared/cycle/\(file)"))
            let bundled = try Data(contentsOf: try #require(url, "\(file) is bundled"))
            #expect(bundled == shared, "bundled \(file) differs from shared/cycle")
        }
        let coach = try #require(CycleCoachConfig.load())
        #expect(coach.format == "ayuvo-cycle-coach")
        #expect(coach.lines["no_cycles"] != nil)
        #expect(!coach.prompt.isEmpty)
    }

    @Test func configDecodes() throws {
        let cfg = try #require(CycleConfig.load())
        #expect(cfg.format == "ayuvo-cycle-config")
        #expect(cfg.phases.map(\.key) == ["period", "predicted_period", "follicular", "fertile", "ovulation", "luteal", "late", "unknown"])
        #expect(cfg.flowLevels.first { $0.key == "very_heavy" }?.healthConnect == "heavy")
    }

    @Test func dayOrdinalsAndInclusiveDuration() {
        #expect(CycleDay.ordinal("1970-01-01") == 0)
        #expect(CycleDay.ordinal("2024-02-30") != nil)  // day range only, like the reference
        #expect(CycleDay.ordinal("2024-13-01") == nil)
        #expect(CycleDay.string(CycleDay.o("2028-02-29")) == "2028-02-29")
        let v = CycleEngine.validatePeriod(CyclePeriodCandidate(id: nil, start: "2026-09-12", end: "2026-09-16"), periods: [],
                                           today: "2026-09-30", CycleConfig.shared)
        #expect(v.duration == 5 && v.ok)
    }

    @Test func insightTemplateFillsPlaceholders() {
        let insight = CycleInsight(key: "recent_range", params: ["count": 3, "low": 28, "high": 30])
        let template = CycleConfig.shared.insight("recent_range")!.template
        #expect(insight.text(template: template) == "Your last 3 cycles were 28–30 days long.")
    }
}

/// Decodes the vectors' plain inputs into typed engine inputs and runs one case.
nonisolated enum CycleVectorRunner {
    enum Failure: Error { case unknownFunction(String) }

    static func strings(_ x: RJ) -> [String] { (x.array ?? []).compactMap(\.string) }

    static func int(_ x: RJ) -> Int? { x.double.map { Int($0) } }

    /// Python `bool(r.get(key, True))`: absent → nil (default), explicit null → false.
    static func flag(_ o: [String: RJ], _ key: String) -> Bool? {
        guard let v = o[key] else { return nil }
        return v.truthy
    }

    static func settings(_ x: RJ) -> CycleSettingsInput {
        let r = x["reminders"].object ?? [:]
        return CycleSettingsInput(
            cycleLength: int(x["cycle_length"]), periodLength: int(x["period_length"]), lutealLength: int(x["luteal_length"]),
            reminders: CycleReminderInput(periodSoon: flag(r, "period_soon"), daysBefore: r["days_before"].flatMap(int),
                                          periodEnd: flag(r, "period_end"), daily: flag(r, "daily")))
    }

    static func periods(_ x: RJ) -> [CyclePeriodInput] {
        (x.array ?? []).map {
            CyclePeriodInput(id: $0["id"].string ?? "", start: $0["start"].string ?? "", end: $0["end"].string,
                             source: $0["source"].string ?? "app")
        }
    }

    static func logs(_ x: RJ) -> [CycleLogInput] {
        (x.array ?? []).map {
            CycleLogInput(day: $0["day"].string ?? "", flow: $0["flow"].string, pain: int($0["pain"]),
                          painLocations: strings($0["pain_locations"]), symptoms: strings($0["symptoms"]), moods: strings($0["moods"]))
        }
    }

    static func state(_ c: RJ) -> CycleState {
        CycleState(today: c["today"].string ?? "", settings: settings(c["settings"]), periods: periods(c["periods"]), logs: logs(c["logs"]))
    }

    static func run(function: String, input c: RJ, config cfg: CycleConfig) throws -> RJ {
        switch function {
        case "apply_period_day":
            return CycleEngine.applyPeriodDay(day: c["day"].string ?? "", on: c["on"].truthy, periods: periods(c["periods"]),
                                              today: c["today"].string ?? "").json
        case "cycles":
            let m = CycleEngine.model(state(c), cfg)
            return .obj(["cycles": .arr(m.cycles.map(\.json)), "stats": m.stats.json])
        case "day_status":
            let m = CycleEngine.model(state(c), cfg)
            return .arr(CycleEngine.dayStatuses(m, from: CycleDay.o(c["from"].string ?? ""), to: CycleDay.o(c["to"].string ?? "")).map(\.json))
        case "days":
            return .arr(strings(c["days"]).map { text in
                let n = CycleDay.o(text)
                return .obj(["day": .str(text), "ordinal": .int(n), "back": .str(CycleDay.string(n)), "next": .str(CycleDay.string(n + 1)),
                             "prev": .str(CycleDay.string(n - 1))])
            })
        case "normalize":
            let settings = CycleEngine.effectiveSettings(settings(c["settings"]), cfg)
            let (norm, dropped) = CycleEngine.normalizePeriods(periods(c["periods"]), today: CycleDay.o(c["today"].string ?? ""),
                                                               settings: settings, cfg)
            return .obj(["periods": .arr(norm.map(\.json)), "dropped": .arr(dropped.map(\.json))])
        case "settings":
            return CycleEngine.effectiveSettings(settings(c["settings"]), cfg).json
        case "snapshot":
            return CycleEngine.snapshot(state(c), cfg).json
        case "trends":
            let s = state(c)
            return CycleEngine.trends(CycleEngine.model(s, cfg), logs: s.logs, cfg).json
        case "validate_period":
            let cand = c["candidate"]
            return CycleEngine.validatePeriod(CyclePeriodCandidate(id: cand["id"].string, start: cand["start"].string ?? "", end: cand["end"].string),
                                              periods: periods(c["periods"]), today: c["today"].string ?? "", cfg).json
        default:
            throw Failure.unknownFunction(function)
        }
    }
}
