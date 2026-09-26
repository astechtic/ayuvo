import Foundation
import Testing
@testable import calorietracker

/// Runs every case of `shared/insights/test-vectors/*.json` through the Swift engines (docs/insights.md §2) and
/// requires equality with the Python reference (numbers by value, object keys unordered, absent == null).
struct InsightsVectorTests {
    nonisolated static let vectorFiles = [
        "baseline", "trend", "overnight", "training_load", "recovery", "health_age", "health_age_pace",
        "daily_review", "patterns", "ai_summary",
    ]

    static var vectorsDirectory: URL {
        HealthTestFixtures.repoRootURL.appendingPathComponent("shared/insights/test-vectors")
    }

    @Test func everySharedVectorFileHasARunner() throws {
        let names = try FileManager.default.contentsOfDirectory(atPath: Self.vectorsDirectory.path)
            .filter { $0.hasSuffix(".json") }
            .map { String($0.dropLast(5)) }
        #expect(!names.isEmpty)
        for name in names.sorted() {
            #expect(Self.vectorFiles.contains(name), "no Swift runner for test-vectors/\(name).json")
        }
    }

    @Test(arguments: vectorFiles)
    func vectorFileMatchesReference(_ name: String) throws {
        let url = Self.vectorsDirectory.appendingPathComponent("\(name).json")
        let root = try #require(RJ.parse(try String(contentsOf: url, encoding: .utf8)), "unreadable \(name).json")
        #expect(root["format"].string == "ayuvo-insights-vectors")
        let function = try #require(root["function"].string)
        let prompts = try #require(InsightsAI.bundledPrompts, "ai_explain.md is bundled")
        let cases = root["cases"].array ?? []
        #expect(!cases.isEmpty)
        var passed = 0
        var failures: [String] = []
        for c in cases {
            do {
                let actual = try InsightsVectorRunner.run(function: function, input: c["input"],
                                                          config: InsightsConfig.shared, prompts: prompts)
                if let diff = InsightsVectorRunner.firstDifference(actual, c["expected"]) {
                    failures.append("\(c["name"].string ?? "?"): \(diff)")
                } else {
                    passed += 1
                }
            } catch {
                failures.append("\(c["name"].string ?? "?"): threw \(error)")
            }
        }
        print("INSIGHTS-VECTORS \(name).json \(passed)/\(cases.count)")
        let report = "\(name).json \(passed)/\(cases.count) passed\n" + failures.joined(separator: "\n")
        #expect(failures.isEmpty, Comment(rawValue: report))
    }

    @Test func bundledConfigAndPromptAreByteIdenticalToShared() throws {
        let shared = HealthTestFixtures.repoRootURL.appendingPathComponent("shared/insights")
        // The app sources sit next to this test target's folder (`<app>Tests` → `<app>`).
        let testsFolder = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
        let ios = testsFolder.deletingLastPathComponent()
            .appendingPathComponent(testsFolder.lastPathComponent.replacingOccurrences(of: "Tests", with: ""))
            .appendingPathComponent("Insights/Resources")
        for file in ["insights_config.json", "ai_explain.md"] {
            let a = try Data(contentsOf: shared.appendingPathComponent(file))
            let b = try Data(contentsOf: ios.appendingPathComponent(file))
            #expect(a == b, "\(file) differs from shared/insights")
        }
        let bundled = try Data(contentsOf: try #require(InsightsConfig.bundledURL, "insights_config.json is bundled"))
        #expect(bundled == (try Data(contentsOf: shared.appendingPathComponent("insights_config.json"))))
    }

    @Test func bundledConfigCoversEverythingTheEnginesKnow() throws {
        let config = try #require(InsightsConfig.load())
        #expect(config.configVersion == 1)
        #expect(Set(config.recovery.components.map(\.mode)).isSubset(of: ["higher", "lower", "sleep", "band", "drop_only"]))
        #expect(config.recovery.components.reduce(0.0) { $0 + $1.weight } == 100)
        for id in config.recovery.components.map(\.id) + ["training_load"] {
            #expect(config.recovery.contributors[id] != nil, "no contributor template for \(id)")
        }
        for c in config.recovery.components {
            #expect(config.metrics[c.metric] != nil, "recovery component \(c.id) has no metric")
        }
        #expect(Set(config.healthAge.markers.map(\.method)).isSubset(of: ["age_norm", "dose_response", "sleep", "workouts", "body_composition"]))
        #expect(config.healthAge.markers.reduce(0.0) { $0 + $1.weight } == 100)
        let hrv = try #require(config.marker("hrv"))
        #expect(hrv.tablesByKind?["sdnn"] != nil && hrv.tablesByKind?["rmssd"] != nil)
        #expect(config.marker("vo2_max")?.tables?["male"] != nil)
        for rule in config.dailyReview.rules {
            #expect(DailyReviewEngine.ruleIDs.contains(rule.id), "DailyReviewEngine does not implement \(rule.id)")
            #expect(DailyReviewEngine.categories.contains(rule.category), "\(rule.id) has unknown category")
        }
        #expect(Set(config.patterns.pairs.map(\.exposure)).isSubset(of: ["late_intense_workout", "high_load", "water_goal_met", "protein_target_met", "short_sleep"]))
        #expect(Set(config.patterns.pairs.map(\.outcome)).isSubset(of: ["sleep_minutes", "recovery_score", "strength_volume", "steps"]))
        for key in ["baselines", "recovery", "health_age", "daily_review", "patterns", "background"] {
            #expect(!(config.methodology[key]?.sections.isEmpty ?? true), "methodology.\(key) missing")
        }
        #expect(config.metrics["hrv"]?.iosHealthType == "hrv_sdnn")
        #expect(InsightsAI.bundledPrompts != nil)
    }

    @Test func comparisonTreatsAbsentAsNullButCatchesDifferences() {
        #expect(InsightsVectorRunner.firstDifference(.obj([:]), .obj(["a": .null])) == nil)
        #expect(InsightsVectorRunner.firstDifference(.obj(["a": .int(1)]), .obj(["a": .num(1.0)])) == nil)
        #expect(InsightsVectorRunner.firstDifference(.obj(["a": .int(1)]), .obj(["a": .int(2)])) != nil)
        #expect(InsightsVectorRunner.firstDifference(.obj([:]), .obj(["a": .int(0)])) != nil)
        #expect(InsightsVectorRunner.firstDifference(.arr([.int(1)]), .arr([.int(1), .int(2)])) != nil)
    }
}


/// Expands the vectors' compact encodings (reference `decode_inputs`) and runs one case through the engines.
nonisolated enum InsightsVectorRunner {
    static func series(_ x: RJ) -> [String: Double] {
        guard let o = x.object else { return [:] }
        if Set(o.keys) == ["start", "values"], let start = o["start"]?.string, let values = o["values"]?.array {
            var out: [String: Double] = [:]
            for (i, v) in values.enumerated() {
                if let d = v.double { out[InsightsDay.add(start, i)] = d }
            }
            return out
        }
        return o.compactMapValues(\.double)
    }

    static func nights(_ x: RJ) -> [String: InsightsNight] {
        guard let o = x.object else { return [:] }
        if Set(o.keys) == ["start", "nights"], let start = o["start"]?.string, let list = o["nights"]?.array {
            var out: [String: InsightsNight] = [:]
            for (i, n) in list.enumerated() {
                guard let a = n.array, a.count == 3, let m = a[0].double, let s = a[1].double, let e = a[2].double else { continue }
                out[InsightsDay.add(start, i)] = InsightsNight(asleepMin: m, startMs: Int64(s), endMs: Int64(e))
            }
            return out
        }
        return o.compactMapValues { n in
            guard let m = n["asleep_min"].double, let s = n["start_ms"].double, let e = n["end_ms"].double else { return nil }
            return InsightsNight(asleepMin: m, startMs: Int64(s), endMs: Int64(e))
        }
    }

    static func decode<T: Decodable>(_ type: T.Type, _ x: RJ) throws -> T {
        try JSONDecoder().decode(T.self, from: Data(x.jsonText.utf8))
    }

    static func inputs(_ x: RJ) throws -> InsightsInputs {
        var inp = InsightsInputs()
        if let tz = x["time_zone"].string { inp.timeZone = tz }
        if let k = x["hrv_kind"].string { inp.hrvKind = k }
        for (k, v) in x["series"].object ?? [:] { inp.series[k] = series(v) }
        inp.sleep = nights(x["sleep"])
        if !x["workouts"].isNull { inp.workouts = try decode([InsightsWorkout].self, x["workouts"]) }
        inp.overnightFallback = (x["overnight_fallback"].array ?? []).compactMap(\.string)
        if !x["tracking"].isNull { inp.tracking = try decode(InsightsTracking.self, x["tracking"]) }
        if !x["targets"].isNull { inp.targets = try decode(InsightsTargets.self, x["targets"]) }
        if let n = x["nutrition"].object {
            inp.nutrition = try n.filter { !$0.value.isNull }.mapValues { try decode(InsightsNutritionDay.self, $0) }
        }
        inp.waterMl = series(x["water_ml"])
        inp.fastingHours = series(x["fasting_hours"])
        inp.strengthVolume = series(x["strength_volume"])
        inp.recoveryScores = series(x["recovery_scores"])
        // The vectors pass only the fields the review reads.
        if !x["recovery"].isNull {
            let r = x["recovery"]
            inp.recovery = RecoveryResult(day: r["day"].string ?? "", status: r["status"].string ?? "",
                                          score: r["score"].double.map { Int($0) }, label: r["label"].string,
                                          components: [], positives: [], negatives: [])
        }
        if let list = x["patterns"].array {
            inp.patterns = list.map { p in
                PatternResult(id: p["id"].string ?? "", status: p["status"].string ?? "ok", nExposed: 0, nUnexposed: 0,
                              needed: 0, surfaced: p["surfaced"].bool ?? false, text: p["text"].string)
            }
        }
        return inp
    }

    static func encode<T: Encodable>(_ value: T) throws -> RJ {
        let data = try JSONEncoder().encode(value)
        return RJ.parse(String(decoding: data, as: UTF8.self)) ?? .null
    }

    static func run(function: String, input c: RJ, config: InsightsConfig, prompts: InsightsAI.Prompts) throws -> RJ {
        switch function {
        case "baseline":
            return try encode(BaselineEngine.baseline(series(c["series"]), day: c["day"].string ?? "",
                                                      metric: config.metrics[c["metric"].string ?? ""]!))
        case "trend":
            return try encode(BaselineEngine.trend(series(c["series"]), day: c["day"].string ?? "",
                                                   metric: config.metrics[c["metric"].string ?? ""]!))
        case "overnight_value":
            let samples = try decode([InsightsSample].self, c["samples"])
            let night: InsightsNight? = c["night"].isNull ? nil : InsightsNight(
                asleepMin: 0, startMs: Int64(c["night"]["start_ms"].double ?? 0), endMs: Int64(c["night"]["end_ms"].double ?? 0))
            return try encode(BaselineEngine.overnightValue(samples: samples, night: night, fallback: c["fallback_value"].double))
        case "training_load":
            return try encode(TrainingLoadEngine.trainingLoad(try decode([InsightsWorkout].self, c["workouts"]),
                                                              day: c["day"].string ?? "", timeZone: c["time_zone"].string ?? "UTC",
                                                              config: config))
        case "recovery":
            return try encode(RecoveryEngine.recovery(try inputs(c["inputs"]), day: c["day"].string ?? "", config: config))
        case "health_age":
            return try encode(HealthAgeEngine.healthAge(try inputs(c["inputs"]), asOf: c["as_of"].string ?? "",
                                                        profile: try decode(InsightsProfile.self, c["profile"]), config: config))
        case "health_age_pace":
            return try encode(HealthAgeEngine.pace(try inputs(c["inputs"]), asOf: c["as_of"].string ?? "",
                                                   profile: try decode(InsightsProfile.self, c["profile"]), config: config))
        case "daily_review":
            return try encode(DailyReviewEngine.review(try inputs(c["inputs"]), day: c["day"].string ?? "", config: config))
        case "patterns":
            return try encode(PatternEngine.patterns(try inputs(c["inputs"]), asOf: c["as_of"].string ?? "", config: config))
        case "ai_summary":
            let summary = try decode(InsightsSummary.self, c["summary"])
            let payload = InsightsAI.payload(summary)
            let kind = InsightsAIKind(rawValue: c["kind"].string ?? "") ?? .recovery
            let variant = InsightsAIVariant(rawValue: c["variant"].string ?? "") ?? .cloud
            let prompt = InsightsAI.buildPrompt(kind: kind, payload: payload, variant: variant, config: config, prompts: prompts)
            let validations = try (c["outputs"].array ?? []).map {
                try encode(InsightsAI.validate($0.string ?? "", payload: payload, config: config))
            }
            return .obj(["payload": payload, "prompt": try encode(prompt), "validations": .arr(validations)])
        default:
            return .null
        }
    }

    /// First difference; a key absent from `actual` equals null in `expected`. Numbers by value.
    static func firstDifference(_ actual: RJ, _ expected: RJ, path: String = "$") -> String? {
        switch (actual, expected) {
        case (.obj(let x), .obj(let y)):
            for key in Set(x.keys).union(y.keys).sorted() {
                let xv = x[key] ?? .null
                guard let yv = y[key] else {
                    if xv.isNull { continue }
                    return "\(path).\(key) unexpected in actual"
                }
                if let diff = firstDifference(xv, yv, path: "\(path).\(key)") { return diff }
            }
            return nil
        case (.arr(let x), .arr(let y)):
            for (i, pair) in zip(x, y).enumerated() {
                if let diff = firstDifference(pair.0, pair.1, path: "\(path)[\(i)]") { return diff }
            }
            return x.count == y.count ? nil : "\(path) count actual \(x.count) expected \(y.count)"
        default:
            return RJ.same(actual, expected) ? nil : "\(path): actual \(actual) expected \(expected)"
        }
    }
}
