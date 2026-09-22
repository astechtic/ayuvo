import XCTest

/// Widget deep links (docs/widgets.md): every Quick Log action and metric tile lands where the
/// Summary "+" menu (or the metric) goes. Each case launches fresh and opens `ayuvo://…`.
///
/// Optional xcodebuild environment:
/// - `TEST_RUNNER_AYUVO_WIDGET_SHOTS_DIR=<absolute dir>` writes `widget-<case>.png` per landing.
final class WidgetDeepLinkUITests: XCTestCase {
    private enum Landing {
        case tab(String)
        case navigationBar(String)
        case text(String)
    }

    private struct Case {
        let name: String
        let url: String
        var arguments: [String] = []
        let landing: Landing
    }

    private let cases: [Case] = [
        Case(name: "food.menu", url: "ayuvo://log/food.menu", landing: .tab("Browse")),
        Case(name: "food.camera", url: "ayuvo://log/food.camera", landing: .tab("Browse")),
        Case(name: "food.photos", url: "ayuvo://log/food.photos", landing: .tab("Browse")),
        Case(name: "food.barcode", url: "ayuvo://log/food.barcode", landing: .tab("Browse")),
        Case(name: "food.voice", url: "ayuvo://log/food.voice", landing: .tab("Browse")),
        Case(name: "food.text", url: "ayuvo://log/food.text", landing: .tab("Browse")),
        Case(name: "food.manual", url: "ayuvo://log/food.manual", landing: .tab("Browse")),
        Case(name: "food.favorites", url: "ayuvo://log/food.favorites", landing: .tab("Browse")),
        Case(name: "food.recent", url: "ayuvo://log/food.recent", landing: .tab("Browse")),
        Case(name: "food.frequent", url: "ayuvo://log/food.frequent", landing: .tab("Browse")),
        Case(name: "food.copy_from_day", url: "ayuvo://log/food.copy_from_day", landing: .tab("Browse")),
        Case(name: "water-off", url: "ayuvo://log/water", arguments: ["-waterTrackingEnabled", "NO"], landing: .navigationBar("Hydration")),
        Case(name: "water-on", url: "ayuvo://log/water", arguments: ["-waterTrackingEnabled", "YES"], landing: .navigationBar("Custom Water Amount")),
        Case(name: "fasting-off", url: "ayuvo://log/fasting", arguments: ["-fastingTrackingEnabled", "NO"], landing: .navigationBar("Fasting")),
        Case(name: "fasting-on", url: "ayuvo://log/fasting", arguments: ["-fastingTrackingEnabled", "YES"], landing: .tab("Browse")),
        Case(name: "weight", url: "ayuvo://log/weight", landing: .text("Log Weight")),
        Case(name: "body_fat", url: "ayuvo://log/body_fat", landing: .text("Log Body Fat")),
        Case(name: "workout", url: "ayuvo://log/workout", landing: .tab("Browse")),
        Case(name: "medication", url: "ayuvo://log/medication", landing: .navigationBar("Medications")),
        Case(name: "record", url: "ayuvo://log/record", landing: .tab("Records")),
        Case(name: "metric-steps", url: "ayuvo://metric/steps", landing: .navigationBar("Steps")),
        Case(name: "metric-calories", url: "ayuvo://metric/app:calories", landing: .navigationBar("Calories")),
        Case(name: "metric-next-dose", url: "ayuvo://metric/medications:next_dose", landing: .navigationBar("Medications")),
        Case(name: "metric-fasting", url: "ayuvo://metric/app:fasting", landing: .tab("Browse")),
        Case(name: "summary", url: "ayuvo://summary", landing: .tab("Summary")),
    ]

    override func setUpWithError() throws {
        continueAfterFailure = true
    }

    private var shotsDirectory: URL? {
        guard let path = ProcessInfo.processInfo.environment["AYUVO_WIDGET_SHOTS_DIR"], !path.isEmpty else { return nil }
        let url = URL(fileURLWithPath: path, isDirectory: true)
        try? FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        return url
    }

    private func shot(_ app: XCUIApplication, _ name: String) {
        let screenshot = app.screenshot()
        let attachment = XCTAttachment(screenshot: screenshot)
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
        if let dir = shotsDirectory {
            try? screenshot.pngRepresentation.write(to: dir.appendingPathComponent("widget-\(name).png"))
        }
    }

    @MainActor
    func testEveryWidgetLinkLandsLikeTheSummaryMenu() throws {
        let springboard = XCUIApplication(bundleIdentifier: "com.apple.springboard")
        for item in cases {
            let app = XCUIApplication()
            app.launchArguments += ["-AppleLanguages", "(en)", "-hasCompletedOnboarding", "YES"] + item.arguments
            app.launch()
            XCTAssertTrue(app.tabBars.buttons["Summary"].waitForExistence(timeout: 10), "\(item.name): app did not start")
            app.open(URL(string: item.url)!)
            // More than one installed build can claim `ayuvo://`; confirm the system prompt.
            let open = springboard.buttons["Open"]
            if open.waitForExistence(timeout: 3) { open.tap() }
            sleep(2)

            switch item.landing {
            case .tab(let title):
                let tab = app.tabBars.buttons[title]
                XCTAssertTrue(tab.waitForExistence(timeout: 5), "\(item.name): no \(title) tab")
                XCTAssertTrue(tab.isSelected, "\(item.name): expected the \(title) tab")
            case .navigationBar(let title):
                XCTAssertTrue(app.navigationBars[title].waitForExistence(timeout: 6), "\(item.name): expected \(title)")
            case .text(let text):
                XCTAssertTrue(app.staticTexts[text].waitForExistence(timeout: 6), "\(item.name): expected \(text)")
            }
            shot(app, item.name)
            app.terminate()
        }
    }
}
