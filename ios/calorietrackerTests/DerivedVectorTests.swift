import Foundation
import Testing
@testable import calorietracker

/// Runs every case of `shared/derived/test-vectors/*.json` through the Swift engines (docs/derived-metrics.md) and
/// requires equality with the Python reference (numbers by value, object keys unordered, absent == null).
struct DerivedVectorTests {
    /// One file per function in `derived_reference.FUNCTIONS`.
    nonisolated static let vectorFiles = [
        "heart_day", "hr_max", "vo2max_uth", "rhr_strain", "sleep_nights", "sleep_night", "sleep_regularity",
        "activity_day", "step_streak", "stride", "energy_day", "gait_week", "audio_day", "body_trend",
        "height_conflict", "priority",
    ]

    static var vectorsDirectory: URL {
        HealthTestFixtures.repoRootURL.appendingPathComponent("shared/derived/test-vectors")
    }

    @Test func sharedVectorFilesMatchRunnersExactly() throws {
        let names = try FileManager.default.contentsOfDirectory(atPath: Self.vectorsDirectory.path)
            .filter { $0.hasSuffix(".json") }
            .map { String($0.dropLast(5)) }
        #expect(!names.isEmpty)
        for name in names.sorted() {
            #expect(Self.vectorFiles.contains(name), "no Swift runner for test-vectors/\(name).json")
        }
        for name in Self.vectorFiles {
            #expect(names.contains(name), "runner \(name) has no test-vectors/\(name).json")
        }
        #expect(Set(names) == Set(Self.vectorFiles))
    }

    @Test(arguments: vectorFiles)
    func vectorFileMatchesReference(_ name: String) throws {
        let url = Self.vectorsDirectory.appendingPathComponent("\(name).json")
        let root = try #require(RJ.parse(try String(contentsOf: url, encoding: .utf8)), "unreadable \(name).json")
        #expect(root["format"].string == "ayuvo-derived-vectors")
        let function = try #require(root["function"].string)
        #expect(function == name)
        let cases = root["cases"].array ?? []
        #expect(!cases.isEmpty)
        var passed = 0
        var failures: [String] = []
        for c in cases {
            do {
                let actual = try DerivedVectorRunner.run(function: function, input: c["input"], config: DerivedConfig.shared)
                if let diff = InsightsVectorRunner.firstDifference(actual, c["expected"]) {
                    failures.append("\(c["name"].string ?? "?"): \(diff)")
                } else {
                    passed += 1
                }
            } catch {
                failures.append("\(c["name"].string ?? "?"): threw \(error)")
            }
        }
        print("DERIVED-VECTORS \(name).json \(passed)/\(cases.count)")
        let report = "\(name).json \(passed)/\(cases.count) passed\n" + failures.joined(separator: "\n")
        #expect(failures.isEmpty, Comment(rawValue: report))
    }

    @Test func bundledConfigIsByteIdenticalToShared() throws {
        let shared = try Data(contentsOf: HealthTestFixtures.repoRootURL.appendingPathComponent("shared/derived/derived_config.json"))
        // The app sources sit next to this test target's folder (`<app>Tests` → `<app>`).
        let testsFolder = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
        let source = testsFolder.deletingLastPathComponent()
            .appendingPathComponent(testsFolder.lastPathComponent.replacingOccurrences(of: "Tests", with: ""))
            .appendingPathComponent("Services/Derived/Resources/derived_config.json")
        #expect(try Data(contentsOf: source) == shared, "Services/Derived/Resources/derived_config.json differs from shared/derived")
        let bundled = try Data(contentsOf: try #require(DerivedConfig.bundledURL, "derived_config.json is bundled"))
        #expect(bundled == shared, "bundled derived_config.json differs from shared/derived")
    }

    @Test func bundledConfigDecodes() throws {
        let config = try #require(DerivedConfig.load())
        #expect(config.format == "ayuvo-derived-config")
        #expect(config.configVersion == 1)
        #expect(config.thresholds.hrrZones.count == 4)
        #expect(config.thresholds.trimpK["other"] != nil && config.thresholds.strideFactor["other"] != nil
                && config.thresholds.mifflin["other"] != nil)
    }

    @Test func dayStartFollowsZoneinfoAcrossDSTAndWeekdays() {
        let ny = TimeZone(identifier: "America/New_York")!
        // 2026-03-08 midnight EST, 2026-03-09 midnight EDT.
        #expect(DerivedDay.dayStartMs("2026-03-08", ny) == 1_772_946_000_000)
        #expect(DerivedDay.dayStartMs("2026-03-09", ny) == 1_773_028_800_000)
        // Values from derived_reference.day_start_ms: skipped midnights use the pre-transition offset, repeated
        // midnights the first occurrence (zoneinfo fold=0).
        let expected: [(String, String, Int64)] = [
            ("America/Santiago", "2026-09-06", 1_788_667_200_000), ("America/Santiago", "2026-09-07", 1_788_750_000_000),
            ("America/Santiago", "2026-04-05", 1_775_361_600_000), ("Asia/Tehran", "2016-03-21", 1_458_505_800_000),
            ("America/Havana", "2016-11-06", 1_478_404_800_000), ("America/Havana", "2016-11-07", 1_478_494_800_000),
            ("Asia/Beirut", "2016-10-30", 1_477_778_400_000),
        ]
        for (zone, day, ms) in expected {
            #expect(DerivedDay.dayStartMs(day, zone) == ms, "\(zone) \(day)")
        }
        #expect(DerivedDay.isoWeekday("2026-03-15") == 7)
        #expect(DerivedDay.isoWeekday("1970-01-01") == 4)
        #expect(DerivedMath.roundTo(-0.04, 1) == 0 && DerivedMath.roundTo(2.25, 1) == 2.3)
    }
}


/// Decodes the vectors' plain inputs into typed engine inputs and runs one case.
nonisolated enum DerivedVectorRunner {
    enum Failure: Error { case unknownFunction(String) }

    static func ms(_ x: RJ) -> Int64? { x.double.map { Int64($0) } }

    static func minuteSeries(_ x: RJ) -> DerivedMinuteSeries? {
        guard !x.isNull, let start = ms(x["start_ms"]) else { return nil }
        return DerivedMinuteSeries(startMs: start, values: (x["values"].array ?? []).map(\.double))
    }

    static func sleepRows(_ x: RJ) -> [DerivedSleepRow] {
        (x.array ?? []).compactMap { r in
            guard let a = r.array, a.count >= 3, let s = ms(a[0]), let e = ms(a[1]), let c = a[2].double else { return nil }
            return DerivedSleepRow(startMs: s, endMs: e, code: Int(c))
        }
    }

    static func daySeries(_ x: RJ) -> [String: Double] { (x.object ?? [:]).compactMapValues(\.double) }

    static func doubles(_ x: RJ) -> [Double?] { (x.array ?? []).map(\.double) }

    static func output(_ result: some DerivedOutput) -> RJ { RJ.from(result.jsonObject) }

    static func run(function: String, input c: RJ, config: DerivedConfig) throws -> RJ {
        let tz = c["time_zone"].string ?? "UTC"
        switch function {
        case "heart_day":
            let night: HeartDerivation.Night? = c["night"].isNull ? nil
                : HeartDerivation.Night(startMs: ms(c["night"]["start_ms"]) ?? 0, endMs: ms(c["night"]["end_ms"]) ?? 0)
            let inp = HeartDerivation.DayInput(timeZone: tz, day: c["day"].string ?? "", sex: c["sex"].string,
                                               hr: minuteSeries(c["hr"]), steps: minuteSeries(c["steps"]), night: night,
                                               hrMax: c["hr_max"].double ?? 0, rhrRef: c["rhr_ref"].double)
            return output(HeartDerivation.heartDay(inp, config: config))
        case "hr_max":
            return output(HeartDerivation.hrMax(age: c["age"].double ?? 0, observed: doubles(c["observed"]),
                                                rhr: c["rhr"].double, config: config))
        case "vo2max_uth":
            return output(HeartDerivation.vo2maxUth(hrMax: c["hr_max"].double, hrMaxMethod: c["hr_max_method"].string,
                                                    rhr: c["rhr"].double, config: config))
        case "rhr_strain":
            return output(HeartDerivation.rhrStrain(series: daySeries(c["series"]), day: c["day"].string ?? "", config: config))
        case "sleep_nights":
            let rows: [SleepDerivation.SourceRow] = (c["rows"].array ?? []).compactMap { r in
                guard let a = r.array, a.count >= 4, let s = ms(a[0]), let e = ms(a[1]), let code = a[2].double else { return nil }
                let source = a[3].string ?? a[3].jsonText
                return SleepDerivation.SourceRow(startMs: s, endMs: e, code: Int(code), source: source)
            }
            return output(SleepDerivation.sleepNights(timeZone: tz, rows: rows, config: config))
        case "sleep_night":
            return output(SleepDerivation.sleepNight(timeZone: tz, wakeDay: c["wake_day"].string ?? "",
                                                     rows: sleepRows(c["rows"]), config: config))
        case "sleep_regularity":
            var nights: [String: [DerivedSleepRow]] = [:]
            for (day, n) in c["nights"].object ?? [:] where n.truthy {
                nights[day] = sleepRows(n["rows"])
            }
            return output(SleepDerivation.sleepRegularity(timeZone: tz, day: c["day"].string ?? "",
                                                          needMin: c["need_min"].double, nights: nights, config: config))
        case "activity_day":
            var sources: [String: ActivityDerivation.Source] = [:]
            for (name, s) in c["sources"].object ?? [:] {
                sources[name] = ActivityDerivation.Source(kind: s["kind"].string ?? "", hourly: doubles(s["hourly"]))
            }
            let inp = ActivityDerivation.DayInput(timeZone: tz, day: c["day"].string ?? "", sources: sources,
                                                  minuteSteps: minuteSeries(c["minute_steps"]), wear: minuteSeries(c["wear"]),
                                                  stepsTotal: c["steps_total"].double)
            return output(ActivityDerivation.activityDay(inp, config: config))
        case "step_streak":
            return output(ActivityDerivation.stepStreak(series: daySeries(c["series"]), day: c["day"].string ?? "",
                                                        goal: c["goal"].double ?? 0, windowDays: Int(c["window_days"].double ?? 0)))
        case "stride":
            return output(ActivityDerivation.stride(distanceM: c["distance_m"].double, steps: c["steps"].double,
                                                    heightCm: c["height_cm"].double, sex: c["sex"].string, config: config))
        case "energy_day":
            let inp = EnergyDerivation.DayInput(restingKcal: c["resting_kcal"].double, activeKcal: c["active_kcal"].double,
                                                weightKg: c["weight_kg"].double, heightCm: c["height_cm"].double,
                                                age: c["age"].double, sex: c["sex"].string)
            return output(EnergyDerivation.energyDay(inp, config: config))
        case "gait_week":
            return output(MobilityDerivation.gaitWeek(walkingSpeed: doubles(c["walking_speed"]),
                                                      doubleSupport: doubles(c["double_support"]),
                                                      asymmetry: doubles(c["asymmetry"]), config: config))
        case "audio_day":
            let samples: [MobilityDerivation.AudioSample] = (c["samples"].array ?? []).compactMap { s in
                guard let a = s.array, a.count >= 3, let st = a[0].double, let en = a[1].double, let db = a[2].double
                else { return nil }
                return MobilityDerivation.AudioSample(startMs: st, endMs: en, db: db)
            }
            return output(MobilityDerivation.audioDay(samples: samples, config: config))
        case "body_trend":
            return output(BodyDerivation.bodyTrend(weights: daySeries(c["weights"]), day: c["day"].string ?? "",
                                                   heightM: c["height_m"].double, goalKg: c["goal_kg"].double,
                                                   scheme: c["scheme"].string, config: config))
        case "height_conflict":
            let heights = (c["heights"].array ?? []).compactMap { $0["value_m"].double }
            return output(BodyDerivation.heightConflict(heights: heights, config: config))
        case "priority":
            let native: DerivedPriority.Native? = c["native"].isNull ? nil
                : DerivedPriority.Native(value: c["native"]["value"].double, source: c["native"]["source"].string)
            return output(DerivedPriority.priority(enabled: c["enabled"].truthy, native: native, derived: c["derived"].double))
        default:
            throw Failure.unknownFunction(function)
        }
    }
}
