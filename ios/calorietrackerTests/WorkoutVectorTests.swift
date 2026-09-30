import Foundation
import Testing
@testable import calorietracker

/// Runs every case of `shared/workout/test-vectors/*.json` through the Swift engines (docs/workouts-gps.md) and
/// requires equality with `scripts/workout_reference.py` (numbers by value, object keys unordered, absent == null).
struct WorkoutVectorTests {
    /// One file per function in `workout_reference.FUNCTIONS`.
    nonisolated static let vectorFiles = ["gps_track", "hr_workout", "hr_recovery", "vo2max_gps", "cooper", "workout_windows"]

    static var vectorsDirectory: URL {
        HealthTestFixtures.repoRootURL.appendingPathComponent("shared/workout/test-vectors")
    }

    @Test func sharedVectorFilesMatchRunnersExactly() throws {
        let names = try FileManager.default.contentsOfDirectory(atPath: Self.vectorsDirectory.path)
            .filter { $0.hasSuffix(".json") }
            .map { String($0.dropLast(5)) }
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
        #expect(root["format"].string == "ayuvo-workout-vectors")
        let function = try #require(root["function"].string)
        #expect(function == name)
        let cases = root["cases"].array ?? []
        #expect(!cases.isEmpty)
        var passed = 0
        var failures: [String] = []
        for c in cases {
            do {
                let actual = try WorkoutVectorRunner.run(function: function, input: c["input"], config: WorkoutConfig.shared)
                if let diff = InsightsVectorRunner.firstDifference(actual, c["expected"]) {
                    failures.append("\(c["name"].string ?? "?"): \(diff)")
                } else {
                    passed += 1
                }
            } catch {
                failures.append("\(c["name"].string ?? "?"): threw \(error)")
            }
        }
        print("WORKOUT-VECTORS \(name).json \(passed)/\(cases.count)")
        let report = "\(name).json \(passed)/\(cases.count) passed\n" + failures.joined(separator: "\n")
        #expect(failures.isEmpty, Comment(rawValue: report))
    }

    @Test func bundledConfigIsByteIdenticalToShared() throws {
        let shared = try Data(contentsOf: HealthTestFixtures.repoRootURL.appendingPathComponent("shared/workout/workout_config.json"))
        // The app sources sit next to this test target's folder (`<app>Tests` → `<app>`).
        let testsFolder = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
        let source = testsFolder.deletingLastPathComponent()
            .appendingPathComponent(testsFolder.lastPathComponent.replacingOccurrences(of: "Tests", with: ""))
            .appendingPathComponent("Services/Workout/Resources/workout_config.json")
        #expect(try Data(contentsOf: source) == shared, "Services/Workout/Resources/workout_config.json differs from shared/workout")
        let bundled = try Data(contentsOf: try #require(WorkoutConfig.bundledURL, "workout_config.json is bundled"))
        #expect(bundled == shared, "bundled workout_config.json differs from shared/workout")
    }

    @Test func bundledConfigDecodes() throws {
        let config = try #require(WorkoutConfig.load())
        #expect(config.format == "ayuvo-workout-config")
        #expect(Set(config.sports.keys) == ["walk", "run", "cycle", "hike"])
        #expect(config.thresholds.keytel["other"]?.count == 4 && config.thresholds.trimpK["other"] != nil)
    }
}


/// Decodes the vectors' plain inputs into typed engine inputs and runs one case.
nonisolated enum WorkoutVectorRunner {
    enum Failure: Error { case unknownFunction(String), unknownSport(String) }

    static func ms(_ x: RJ) -> Int64? { x.double.map { Int64($0) } }

    static func samples(_ x: RJ) -> [HeartRateWorkout.Sample] {
        (x.array ?? []).compactMap { s in
            guard let a = s.array, a.count >= 2, let t = ms(a[0]), let b = a[1].double else { return nil }
            return HeartRateWorkout.Sample(tMs: t, bpm: b)
        }
    }

    static func output(_ result: some DerivedOutput) -> RJ { RJ.from(result.jsonObject) }

    static func run(function: String, input c: RJ, config: WorkoutConfig) throws -> RJ {
        switch function {
        case "gps_track":
            let points: [GpsTrack.Point] = (c["points"].array ?? []).compactMap { p in
                guard let a = p.array, a.count >= 6, let t = ms(a[0]), let lat = a[1].double, let lon = a[2].double
                else { return nil }
                return GpsTrack.Point(tMs: t, lat: lat, lon: lon, altM: a[3].double, hAccM: a[4].double, speedMps: a[5].double)
            }
            let pauses: [(Int64, Int64)] = (c["pauses"].array ?? []).compactMap { p in
                guard let a = p.array, a.count >= 2, let s = ms(a[0]), let e = ms(a[1]) else { return nil }
                return (s, e)
            }
            let sport = c["sport"].string ?? ""
            let inp = GpsTrack.Input(sport: sport, points: points, pauses: pauses,
                                     startMs: ms(c["start_ms"]) ?? 0, endMs: ms(c["end_ms"]) ?? 0)
            guard let result = GpsTrack.gpsTrack(inp, config: config) else { throw Failure.unknownSport(sport) }
            return output(result)
        case "hr_workout":
            let inp = HeartRateWorkout.WorkoutInput(samples: samples(c["samples"]), startMs: ms(c["start_ms"]) ?? 0,
                                                    endMs: ms(c["end_ms"]) ?? 0, hrMax: c["hr_max"].double ?? 0,
                                                    rhr: c["rhr"].double, sex: c["sex"].string, age: c["age"].double,
                                                    weightKg: c["weight_kg"].double)
            return output(HeartRateWorkout.hrWorkout(inp, config: config))
        case "hr_recovery":
            return output(HeartRateWorkout.hrRecovery(samples: samples(c["samples"]), endMs: ms(c["end_ms"]) ?? 0,
                                                      config: config))
        case "vo2max_gps":
            let segments: [CardioFitness.Segment] = (c["segments"].array ?? []).map { s in
                CardioFitness.Segment(speedMps: s["speed_mps"].double ?? 0, grade: s["grade"].double ?? 0,
                                      hr: s["hr"].double ?? 0, durationS: s["duration_s"].double ?? 0)
            }
            return output(CardioFitness.vo2maxGps(segments: segments, rhr: c["rhr"].double, hrMax: c["hr_max"].double,
                                                  config: config))
        case "cooper":
            return output(CardioFitness.cooper(distanceM: c["distance_m"].double))
        case "workout_windows":
            let hr = DerivedMinuteSeries(startMs: ms(c["hr"]["start_ms"]) ?? 0,
                                         values: (c["hr"]["values"].array ?? []).map(\.double))
            return output(HeartRateWorkout.workoutWindows(hr: hr, rhr: c["rhr"].double, hrMax: c["hr_max"].double ?? 0,
                                                          config: config))
        default:
            throw Failure.unknownFunction(function)
        }
    }
}
