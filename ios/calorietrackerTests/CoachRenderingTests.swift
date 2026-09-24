import Foundation
import Testing
@testable import calorietracker

/// Markdown and chart rendering (docs/coach.md §4, §5). The parsing itself is covered by the shared
/// vectors; these guard the layer between the parser and the views — that every block kind survives
/// the flattening, that a bad chart can never reach the chart view, and that the model is actually
/// told how to draw one.
struct CoachRenderingTests {

    /// The cache is keyed by text, so each case gets a unique suffix. The blank line keeps the
    /// sentinel its own paragraph instead of merging into the one under test.
    private func blocks(_ markdown: String) -> [CoachBlock] {
        CoachMarkdownCache.blocks(for: markdown + "\n\n\u{200B}\(UUID().uuidString)")
            .filter { !$0.text.contains("\u{200B}") }
    }

    // MARK: - §4 blocks reaching the view

    @Test func everyBlockKindSurvivesTheFlattening() {
        let markdown = """
        # Title
        Some text.

        ## Second
        - bullet
          - nested
        1. first
        - [x] done
        - [ ] todo

        > quoted

        ---

        | Day | Hours |
        |:--|--:|
        | Mon | 6.2 |

        ```swift
        let a = 1
        ```
        """
        let kinds = blocks(markdown).map(\.kind)
        for expected in ["heading", "paragraph", "bullet", "numbered", "task", "quote", "rule", "table", "code"] {
            #expect(kinds.contains(expected), "no \(expected) block reached the view")
        }
    }

    @Test func nestedListsKeepTheirDepth() {
        let rows = blocks("- one\n  - two\n    - three").filter { $0.kind == "bullet" }
        #expect(rows.map(\.depth) == [0, 1, 2])
    }

    @Test func taskItemsCarryTheirCheckedState() {
        let rows = blocks("- [x] done\n- [ ] todo").filter { $0.kind == "task" }
        #expect(rows.map(\.checked) == [true, false])
        #expect(rows.map(\.text) == ["done", "todo"])
    }

    @Test func aTableKeepsItsAlignmentsAndPadsShortRows() {
        let table = try? #require(blocks("| a | b |\n|:--|--:|\n| 1 |").first { $0.kind == "table" })
        #expect(table?.headers == ["a", "b"])
        #expect(table?.aligns == ["left", "right"])
        #expect(table?.rows == [["1", ""]])
    }

    @Test func aCodeFenceKeepsItsLanguage() {
        let code = try? #require(blocks("```json\n{\"a\":1}\n```").first { $0.kind == "code" })
        #expect(code?.lang == "json")
        #expect(code?.text == "{\"a\":1}")
    }

    @Test func plainTextIsOneParagraph() {
        let rows = blocks("hello\nthere")
        #expect(rows.count == 1)
        #expect(rows[0].kind == "paragraph")
        #expect(rows[0].text == "hello there")
    }

    // MARK: - §5 charts

    @Test func aValidChartReachesTheViewWithItsSpec() throws {
        let markdown = """
        Here it is.

        ```ayuvo-chart
        {"type":"bar","title":"Sleep","unit":"h","series":[{"label":"Asleep","points":[["Mon",6.2],["Tue",7.1]]}]}
        ```
        """
        let chart = try #require(blocks(markdown).first { $0.kind == "chart" })
        #expect(chart.chartOK)
        let spec = try #require(chart.spec)
        #expect(spec["type"].string == "bar")
        #expect(spec["title"].string == "Sleep")
        #expect(CoachChartView.Series.all(from: spec).first?.points.count == 2)
    }

    /// Rule 1: a spec that does not parse is shown as text, never as a guessed chart.
    @Test func aBadChartNeverReachesTheChartView() throws {
        for raw in ["{\"type\":\"bar\",\"series\":[]}",
                    "{\"type\":\"sankey\",\"series\":[{\"points\":[[\"a\",1]]}]}",
                    "not json"] {
            let chart = try #require(blocks("```ayuvo-chart\n\(raw)\n```").first { $0.kind == "chart" })
            #expect(!chart.chartOK, Comment(rawValue: raw))
            #expect(chart.spec == nil)
            #expect(chart.text == raw)
        }
    }

    @Test func seriesAndPointsAreReadInOrder() throws {
        let spec = try #require(CR.parseChartSpec("""
        {"type":"range","series":[{"label":"BP","points":[["Mon",78,121],["Tue",80,126]]}]}
        """)["spec"])
        let series = CoachChartView.Series.all(from: spec)
        #expect(series.count == 1)
        #expect(series[0].label == "BP")
        #expect(series[0].points.map(\.x) == ["Mon", "Tue"])
        #expect(series[0].points.map(\.y) == [78, 80])
        #expect(series[0].points.compactMap(\.y2) == [121, 126])
    }

    @Test func theAccessibilityLabelNamesTheKindAndTheRange() throws {
        let spec = try #require(CR.parseChartSpec("""
        {"type":"bar","title":"Sleep","unit":"h","series":[{"points":[["Mon",6.2],["Tue",7.8]]}]}
        """)["spec"])
        let label = CR.chartAccessibilityText(spec)
        #expect(label.contains("Bar chart"))
        #expect(label.contains("Sleep"))
        #expect(label.contains("6.2"))
        #expect(label.contains("7.8"))
        #expect(label.contains("h"))
    }

    /// The palette is the app's domain colours, so a chat chart matches a Browse chart.
    @Test func thePaletteIsStableAndNeverEmpty() {
        #expect(CoachChartView.palette(1).count == 1)
        #expect(CoachChartView.palette(4).count == 4)
        #expect(CoachChartView.palette(9).count == 4, "a 5th series cannot exist: the cap is 4")
        #expect(CoachChartView.palette(0).count == 1)
    }

    @Test func numbersAreFormattedWithoutTrailingZeroes() {
        #expect(CoachChartView.numberText(7) == "7")
        #expect(CoachChartView.numberText(6.25) == "6.25")
        #expect(CoachChartView.valueText(7, unit: "h") == "7 h")
        #expect(CoachChartView.valueText(7, unit: nil) == "7")
    }

    // MARK: - The model has to know the format

    @Test func theSystemPromptTeachesTheChartBlock() {
        let section = CoachCatalog.chartsPromptSection
        #expect(section.contains("ayuvo-chart"))
        for kind in CR.chartTypes {
            #expect(section.contains(kind), "the prompt no longer lists \(kind)")
        }
        // The rule that keeps a chart honest.
        #expect(section.contains("Never estimate"))
        #expect(CoachCatalog.chartGuardrails.contains("never introduces a number"))
    }
}
