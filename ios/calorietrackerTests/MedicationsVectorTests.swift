import Foundation
import Testing
@testable import calorietracker

/// Runs every case of `shared/medications/test-vectors/*.json` through the Swift port (`MR`) and
/// requires exact equality with the reference output (numbers by value, object keys unordered,
/// array order significant).
struct MedicationsVectorTests {
    static let vectorFiles = [
        "occurrences", "dose_status", "missed", "timeline", "adherence", "reminders",
        "dose_actions", "lifecycle", "auto_complete", "frequency_hint", "archive", "validation",
        "coach_tools_payloads",
    ]

    static var vectorsDirectory: URL {
        HealthTestFixtures.repoRootURL.appendingPathComponent("shared/medications/test-vectors")
    }

    /// Every vector file in shared/medications/test-vectors must have a runner here.
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
        #expect(root["format"].string == "ayuvo-medications-vectors")
        let function = try #require(root["function"].string)
        let cases = root["cases"].array ?? []
        #expect(!cases.isEmpty)
        var passed = 0
        var failures: [String] = []
        for c in cases {
            let actual = MR.runCase(function: function, input: c["input"])
            if let diff = RecordsVectorTests.firstDifference(actual, c["expected"]) {
                failures.append("\(c["name"].string ?? "?"): \(diff)")
            } else {
                passed += 1
            }
        }
        print("MEDICATIONS-VECTORS \(name).json \(passed)/\(cases.count)")
        let report = "\(name).json \(passed)/\(cases.count) passed\n" + failures.joined(separator: "\n")
        #expect(failures.isEmpty, Comment(rawValue: report))
    }

    /// No vector output but an explicit action ever contains a `taken` row (docs §1.1).
    @Test func noFunctionOtherThanAnActionProducesTakenRows() throws {
        for name in ["missed", "reminders", "lifecycle", "auto_complete", "archive"] {
            let url = Self.vectorsDirectory.appendingPathComponent("\(name).json")
            let root = try #require(RJ.parse(try String(contentsOf: url, encoding: .utf8)))
            let function = try #require(root["function"].string)
            for c in root["cases"].array ?? [] {
                let input = c["input"]
                if name == "archive", input["op"].string == "merge" {
                    // Merge only copies rows that already exist in the archive; check inserts of taken rows
                    // come from the archive, never from nothing.
                    let actual = MR.runCase(function: function, input: input)
                    let archiveTaken = Set((input["archive"]["dose_logs"].array ?? []).filter { $0["status"].string == "taken" }.compactMap { $0["id"].string })
                    for op in actual["ops"].array ?? [] where op["row"]["status"].string == "taken" {
                        #expect(archiveTaken.contains(op["row"]["id"].string ?? ""), "\(name): taken row not from the archive")
                    }
                    continue
                }
                let actual = MR.runCase(function: function, input: input)
                #expect(!Self.containsTakenRow(actual), "\(name)/\(c["name"].string ?? "?") produced a taken row")
            }
        }
    }

    private static func containsTakenRow(_ value: RJ) -> Bool {
        switch value {
        case .obj(let o):
            if let row = o["row"], row["status"].string == "taken" { return true }
            return o.values.contains(where: containsTakenRow)
        case .arr(let a):
            return a.contains(where: containsTakenRow)
        default:
            return false
        }
    }
}
