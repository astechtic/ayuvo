import Foundation
import Testing
@testable import calorietracker

/// Runs every case of `shared/analytics/test-vectors/*.json` through the Swift analytics engine
/// (docs/health-analytics.md) and requires agreement with the Python reference: numbers within
/// `config.tolerance` (absolute or relative), everything else exact, object keys unordered, absent == null.
struct AnalyticsVectorTests {
    nonisolated static let vectorFiles = AnalyticsEngine.functions

    static var root: URL { HealthTestFixtures.repoRootURL }
    static var vectorsDirectory: URL { root.appendingPathComponent("shared/analytics/test-vectors") }

    static let config: AnalyticsConfig = {
        AnalyticsConfig.load(config: root.appendingPathComponent("shared/analytics/analytics_config.json"),
                             policy: root.appendingPathComponent("shared/health/source_policy.json"))!
    }()

    static func firstDifference(_ a: AJ, _ b: AJ, tolerance: Double, path: String = "$") -> String? {
        switch (a, b) {
        case (.num(let x), .num(let y)):
            let diff = abs(x - y)
            if diff <= tolerance || diff <= tolerance * max(abs(x), abs(y)) { return nil }
            return "\(path): \(x) != \(y)"
        case (.obj(let x), .obj(let y)):
            for k in Set(x.keys).union(y.keys).sorted() {
                if let d = firstDifference(x[k] ?? .null, y[k] ?? .null, tolerance: tolerance, path: "\(path).\(k)") { return d }
            }
            return nil
        case (.arr(let x), .arr(let y)):
            if x.count != y.count { return "\(path): length \(x.count) != \(y.count)" }
            for i in x.indices {
                if let d = firstDifference(x[i], y[i], tolerance: tolerance, path: "\(path)[\(i)]") { return d }
            }
            return nil
        default:
            return a == b ? nil : "\(path): \(a) != \(b)"
        }
    }

    @Test func sharedVectorFilesMatchRunnersExactly() throws {
        let names = try FileManager.default.contentsOfDirectory(atPath: Self.vectorsDirectory.path)
            .filter { $0.hasSuffix(".json") }
            .map { String($0.dropLast(5)) }
        #expect(Set(names) == Set(Self.vectorFiles), "every vector file needs a Swift runner and vice versa")
    }

    @Test(arguments: vectorFiles)
    func vectorFileMatchesReference(_ name: String) throws {
        let url = Self.vectorsDirectory.appendingPathComponent("\(name).json")
        let doc = try #require(AJ.parse(try Data(contentsOf: url)), "unreadable \(name).json")
        #expect(doc["format"].string == "ayuvo-analytics-vectors")
        #expect(doc["function"].string == name)
        let cases = doc["cases"].array
        #expect(!cases.isEmpty)
        var failures: [String] = []
        for c in cases {
            guard let got = AnalyticsEngine.run(function: name, input: c["input"], Self.config) else {
                failures.append("\(c["name"].string ?? "?"): no runner")
                continue
            }
            if let d = Self.firstDifference(got, c["expected"], tolerance: Self.config.tolerance) {
                failures.append("\(c["name"].string ?? "?"): \(d)")
            }
        }
        print("ANALYTICS-VECTORS \(name).json \(cases.count - failures.count)/\(cases.count)")
        #expect(failures.isEmpty, Comment(rawValue: "\(name).json\n" + failures.joined(separator: "\n")))
    }

    @Test func bundledCopiesAreByteIdenticalToShared() throws {
        let testsFolder = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
        let app = testsFolder.deletingLastPathComponent()
            .appendingPathComponent(testsFolder.lastPathComponent.replacingOccurrences(of: "Tests", with: ""))
        for (shared, name, bundled) in [
            ("shared/analytics/analytics_config.json", "analytics_config.json", AnalyticsConfig.bundledConfigURL),
            ("shared/health/source_policy.json", "source_policy.json", AnalyticsConfig.bundledPolicyURL),
        ] {
            let truth = try Data(contentsOf: Self.root.appendingPathComponent(shared))
            let source = app.appendingPathComponent("Services/Analytics/Resources/\(name)")
            #expect(try Data(contentsOf: source) == truth, "Services/Analytics/Resources/\(name) differs from \(shared)")
            let inBundle = try Data(contentsOf: try #require(bundled, "\(name) is bundled"))
            #expect(inBundle == truth, "bundled \(name) differs from \(shared)")
        }
        #expect(AnalyticsConfig.load() != nil)
    }

    @Test func canonicalHashMatchesKnownFNV() {
        #expect(AMath.fnv1a64("") == "cbf29ce484222325")
        #expect(AMath.fnv1a64("a") == "af63dc4c8601ec8c")
    }
}
