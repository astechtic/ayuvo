import Foundation
import Testing
@testable import calorietracker

/// Runs every case of `shared/coach/test-vectors/*.json` through the Swift port (`CR`) and requires
/// exact equality with the reference output (numbers by value, object keys unordered, array order
/// significant).
struct CoachVectorTests {
    static let vectorFiles = [
        "markdown_blocks", "chart_spec", "chart_repair", "attachment_excerpt", "conversation_title",
        "data_sources", "prompt_gallery", "prompt_chips", "chat_archive", "export",
    ]

    static var vectorsDirectory: URL {
        HealthTestFixtures.repoRootURL.appendingPathComponent("shared/coach/test-vectors")
    }

    static var sharedDirectory: URL {
        HealthTestFixtures.repoRootURL.appendingPathComponent("shared/coach")
    }

    /// Every vector file in shared/coach/test-vectors must have a runner here.
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
        #expect(root["format"].string == "ayuvo-coach-vectors")
        let function = try #require(root["function"].string)
        let cases = root["cases"].array ?? []
        #expect(!cases.isEmpty)
        var passed = 0
        var failures: [String] = []
        for c in cases {
            let actual = CR.runCase(function: function, input: c["input"])
            if let diff = RecordsVectorTests.firstDifference(actual, c["expected"]) {
                failures.append("\(c["name"].string ?? "?"): \(diff)")
            } else {
                passed += 1
            }
        }
        print("COACH-VECTORS \(name).json \(passed)/\(cases.count)")
        let report = "\(name).json \(passed)/\(cases.count) passed\n" + failures.joined(separator: "\n")
        #expect(failures.isEmpty, Comment(rawValue: report))
    }

    /// The bundled catalogs must be byte-identical to the shared ones (`scripts/coach_contract_check.py`
    /// asserts the same thing from the other side).
    @Test(arguments: ["chart_spec", "prompt_gallery"])
    func bundledCatalogMatchesShared(_ name: String) throws {
        let shared = try String(contentsOf: Self.sharedDirectory.appendingPathComponent("\(name).json"),
                                encoding: .utf8)
        let bundled = try #require(Self.bundledText(name), "\(name).json is not bundled in the app")
        #expect(bundled == shared, "\(name).json differs from shared/coach/\(name).json")
    }

    @Test func medicationCoachToolsAreBundledVerbatim() throws {
        let shared = try String(contentsOf: HealthTestFixtures.repoRootURL
            .appendingPathComponent("shared/medications/coach_tools.json"), encoding: .utf8)
        let bundled = try #require(Self.bundledText("medication_coach_tools"))
        #expect(bundled == shared)
    }

    /// The chart prompt the model is given must still carry the rule that keeps charts honest.
    @Test func chartPromptKeepsTheNoInventedValuesRule() throws {
        let section = CoachCatalog.chartsPromptSection
        #expect(section.contains(CR.chartFence))
        #expect(section.contains("Never estimate"))
        for kind in CR.chartTypes {
            #expect(section.contains(kind), "the chart prompt no longer lists \(kind)")
        }
    }

    /// A spec that does not parse must never reach the renderer as a chart.
    @Test func malformedChartsFallBackToACodeBlock() throws {
        for raw in ["{\"type\":\"bar\",\"series\":[{\"points\":[[\"a\",NaN]]}]}",
                    "{\"type\":\"sankey\",\"series\":[{\"points\":[[\"a\",1]]}]}",
                    "{'type':'bar','series':[{'points':[['a',1]]}]}",
                    "not json at all"] {
            let block = CR.chartBlock(raw)
            #expect(block["ok"].bool == false)
            #expect(block["text"].string == raw)
            #expect(block["spec"].isNull)
        }
    }

    /// The repairs of §5 rescue a badly punctuated spec without moving a single number.
    @Test func punctuationIsRepairedButValuesAreNot() throws {
        let raw = "Here you go:\n{\"type\":\"Column Chart\", // sleep\n\"labels\":[\"Mon\",\"Tue\",\"Wed\"],"
            + "\"series\":[{\"label\":\"Asleep\",\"values\":[\"6.2\",NaN,5.4],}],}\nHope that helps."
        let block = CR.chartBlock(raw)
        #expect(block["ok"].bool == true)
        let spec = block["spec"]
        #expect(spec["type"].string == "bar")
        let points = spec["series"].array?.first?["points"].array ?? []
        #expect(points.count == 2)                      // Tuesday had no reading and is not drawn
        #expect(points.first?["x"].string == "Mon")
        #expect(points.first?["y"].double == 6.2)
        #expect(points.last?["x"].string == "Wed")
        #expect(points.last?["y"].double == 5.4)
    }

    /// An attachment excerpt never carries a line the records redaction rule would have dropped.
    @Test func attachmentExcerptRedactsIdentityLines() throws {
        let got = CR.attachmentExcerpt(["Patient Name: Ravi Kumar\nUHID 998877\nHemoglobin 11.2 g/dL"])
        let text = try #require(got["text"].string)
        #expect(!text.contains("Ravi"))
        #expect(!text.contains("998877"))
        #expect(text.contains("Hemoglobin"))
        for line in text.components(separatedBy: "\n") {
            #expect(!RR.piiLine(RR.fold(line), []), "a redacted line survived: \(line)")
        }
    }

    private static func bundledText(_ name: String) -> String? {
        for bundle in [Bundle.main, Bundle(for: Marker.self)] {
            if let url = bundle.url(forResource: name, withExtension: "json"),
               let text = try? String(contentsOf: url, encoding: .utf8) {
                return text
            }
        }
        return nil
    }

    private final class Marker {}
}
