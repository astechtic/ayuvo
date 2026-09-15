import Foundation
import Testing
@testable import calorietracker

/// Runs every case of `shared/records/test-vectors/*.json` through the Swift port and requires
/// exact equality with the reference output (numbers by value, object keys unordered).
struct RecordsVectorTests {
    static let vectorFiles = [
        "fold", "classifier", "dates", "fields", "lab_rows", "boundaries", "highlights", "review",
        "apply_extraction", "ai_validation", "ai_chunks", "hashing", "query_parser",
        "analyte_mapping", "unit_conversion", "observations", "trends", "entities", "relations",
    ]

    /// Every vector file in shared/records/test-vectors must have a runner here.
    @Test func everySharedVectorFileHasARunner() throws {
        let names = try FileManager.default.contentsOfDirectory(atPath: Self.vectorsDirectory.path)
            .filter { $0.hasSuffix(".json") }
            .map { String($0.dropLast(5)) }
        #expect(!names.isEmpty)
        for name in names.sorted() {
            #expect(Self.vectorFiles.contains(name), "no Swift runner for test-vectors/\(name).json")
        }
    }

    static var vectorsDirectory: URL {
        HealthTestFixtures.repoRootURL.appendingPathComponent("shared/records/test-vectors")
    }

    static func firstDifference(_ a: RJ, _ b: RJ, path: String = "$") -> String? {
        switch (a, b) {
        case (.obj(let x), .obj(let y)):
            for key in Set(x.keys).union(y.keys).sorted() {
                guard let xv = x[key] else { return "\(path).\(key) missing in actual" }
                guard let yv = y[key] else { return "\(path).\(key) unexpected in actual" }
                if let diff = firstDifference(xv, yv, path: "\(path).\(key)") { return diff }
            }
            return nil
        case (.arr(let x), .arr(let y)):
            for (i, pair) in zip(x, y).enumerated() {
                if let diff = firstDifference(pair.0, pair.1, path: "\(path)[\(i)]") { return diff }
            }
            return x.count == y.count ? nil : "\(path) count actual \(x.count) expected \(y.count)"
        default:
            return RJ.same(a, b) ? nil : "\(path): actual \(a) expected \(b)"
        }
    }

    @Test(arguments: vectorFiles)
    func vectorFileMatchesReference(_ name: String) throws {
        let url = Self.vectorsDirectory.appendingPathComponent("\(name).json")
        let root = try #require(RJ.parse(try String(contentsOf: url, encoding: .utf8)), "unreadable \(name).json")
        let function = try #require(root["function"].string)
        let cases = root["cases"].array ?? []
        #expect(!cases.isEmpty)
        var passed = 0
        var failures: [String] = []
        for c in cases {
            let actual = RR.runCase(function: function, input: c["input"])
            if let diff = Self.firstDifference(actual, c["expected"]) {
                failures.append("\(c["name"].string ?? "?"): \(diff)")
            } else {
                passed += 1
            }
        }
        print("RECORDS-VECTORS \(name).json \(passed)/\(cases.count)")
        let report = "\(name).json \(passed)/\(cases.count) passed\n" + failures.joined(separator: "\n")
        #expect(failures.isEmpty, Comment(rawValue: report))
    }

    @Test func bundledSharedFilesAreByteIdentical() throws {
        let shared = HealthTestFixtures.repoRootURL.appendingPathComponent("shared/records")
        let ios = HealthTestFixtures.repoRootURL.appendingPathComponent("ios/calorietracker/Records/Resources")
        for file in ["record_types.json", "units.json", "analytes.json"] {
            let a = try Data(contentsOf: shared.appendingPathComponent(file))
            let b = try Data(contentsOf: ios.appendingPathComponent(file))
            #expect(a == b, "\(file) differs from shared/records")
        }
        #expect(!RR.ruleSet.types.isEmpty, "record_types.json is bundled")
        #expect(!RR.units.isEmpty, "units.json is bundled")
    }

    @Test func comparisonDetectsDifferences() {
        let expected: RJ = .obj(["a": .arr([.int(13), .str("x")]), "b": .null])
        #expect(Self.firstDifference(.obj(["a": .arr([.num(13.0), .str("x")]), "b": .null]), expected) == nil)
        #expect(Self.firstDifference(.obj(["a": .arr([.num(13.5), .str("x")]), "b": .null]), expected) != nil)
        #expect(Self.firstDifference(.obj(["a": .arr([.int(13)]), "b": .null]), expected) != nil)
        #expect(Self.firstDifference(.obj(["a": .arr([.int(13), .str("x")])]), expected) != nil)
    }

    /// The embedded prompts equal the fenced blocks of `shared/records/ai_extraction.md`.
    @Test func aiPromptsMatchSharedFile() throws {
        let url = HealthTestFixtures.repoRootURL.appendingPathComponent("shared/records/ai_extraction.md")
        let lines = try String(contentsOf: url, encoding: .utf8).components(separatedBy: "\n")
        var blocks: [String] = []
        var current: [String]?
        for line in lines {
            if line == "```" {
                if let block = current { blocks.append(block.joined(separator: "\n")); current = nil } else { current = [] }
            } else if current != nil {
                current!.append(line)
            }
        }
        #expect(blocks == [RecordsAIPrompt.systemFull, RecordsAIPrompt.userTemplate, RecordsAIPrompt.systemCompact])
        let user = RecordsAIPrompt.user(recordTypeHint: "lab_report", pages: "=== Page 1 ===\n{record_type_hint}")
        #expect(user.contains("Document type hint: lab_report"))
        #expect(user.contains("=== Page 1 ===\n{record_type_hint}"), "page text is never re-substituted")
    }

    /// Migration statement splitting (§8) matches the reference rule on both shared SQL files.
    @Test func statementSplittingFollowsContract() throws {
        let root = HealthTestFixtures.repoRootURL.appendingPathComponent("shared/records")
        for file in ["schema.sql", "migrations/002_intelligence.sql", "migrations/003_knowledge.sql"] {
            let sql = try String(contentsOf: root.appendingPathComponent(file), encoding: .utf8)
            #expect(RecordsSchema.parseStatementsStrict(sql) != nil, "\(file) has text after the last ;")
        }
        #expect(RecordsSchema.parseStatementsStrict("CREATE TABLE a (x INT); -- c\n  -- only comment\nSELECT 1") == nil)
        #expect(RecordsSchema.parseStatementsStrict("CREATE TABLE a (\n  x INT -- note\n);\r\n") == ["CREATE TABLE a (\n  x INT\n)"])
    }
}
