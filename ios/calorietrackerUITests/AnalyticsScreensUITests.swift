import XCTest

/// Health analytics screens on synthetic data (`-AyuvoAnalyticsDemo`, DEBUG only; docs/health-analytics.md):
/// Insights hub, Recovery v2, Health signals, Trends, Patterns and the Forecast switch in Settings.
/// Set `TEST_RUNNER_AYUVO_SHOTS_DIR=<absolute dir>` to also write the screenshots as PNG files.
final class AnalyticsScreensUITests: XCTestCase {
    override func setUpWithError() throws {
        continueAfterFailure = false
    }

    private func shot(_ app: XCUIApplication, _ name: String) {
        let screenshot = app.screenshot()
        let attachment = XCTAttachment(screenshot: screenshot)
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
        if let dir = ProcessInfo.processInfo.environment["AYUVO_SHOTS_DIR"], !dir.isEmpty {
            try? FileManager.default.createDirectory(atPath: dir, withIntermediateDirectories: true)
            try? screenshot.pngRepresentation.write(to: URL(fileURLWithPath: dir).appendingPathComponent("\(name).png"))
        }
    }

    private func launch() -> XCUIApplication {
        let app = XCUIApplication()
        app.launchArguments += ["-AppleLanguages", "(en)", "-hasCompletedOnboarding", "YES", "-healthKitEnabled", "NO",
                                "-notificationsEnabled", "NO", "-AyuvoAnalyticsDemo"]
        app.launch()
        return app
    }

    private func openInsights(_ app: XCUIApplication) {
        XCTAssertTrue(app.tabBars.buttons["Summary"].waitForExistence(timeout: 15), "app did not start")
        app.open(URL(string: "ayuvo://open/insights")!)
        let open = XCUIApplication(bundleIdentifier: "com.apple.springboard").buttons["Open"]
        if open.waitForExistence(timeout: 3) { open.tap() }
        XCTAssertTrue(app.buttons["insights.hub.recovery"].waitForExistence(timeout: 10), "Insights hub")
    }

    private func scrollShots(_ app: XCUIApplication, _ name: String, pages: Int) {
        shot(app, "\(name)-1")
        for page in 2...max(2, pages) where page <= pages {
            app.swipeUp()
            sleep(1)
            shot(app, "\(name)-\(page)")
        }
    }

    private func back(_ app: XCUIApplication) {
        app.navigationBars.buttons.firstMatch.tap()
        sleep(1)
    }

    @MainActor
    func testAnalyticsScreens() throws {
        let app = launch()
        openInsights(app)
        sleep(2)
        shot(app, "analytics-01-hub")

        app.buttons["insights.hub.recovery"].tap()
        XCTAssertTrue(app.otherElements["recovery.summary"].waitForExistence(timeout: 10)
                      || app.staticTexts.containing(NSPredicate(format: "label CONTAINS 'baseline'")).firstMatch.waitForExistence(timeout: 5),
                      "Recovery v2 summary")
        sleep(1)
        scrollShots(app, "analytics-02-recovery", pages: 3)
        app.buttons["insights.info"].firstMatch.tap()
        sleep(1)
        shot(app, "analytics-03-recovery-methodology")
        app.buttons["Done"].firstMatch.tap()
        sleep(1)
        back(app)

        app.buttons["insights.hub.signals"].tap()
        sleep(2)
        scrollShots(app, "analytics-04-signals", pages: 5)
        back(app)

        app.buttons["insights.hub.trends"].tap()
        sleep(2)
        scrollShots(app, "analytics-05-trends", pages: 3)
        back(app)

        app.buttons["insights.hub.patterns"].tap()
        sleep(2)
        scrollShots(app, "analytics-06-patterns", pages: 2)
        back(app)

        let settingsTab = app.tabBars.buttons["Settings"]
        settingsTab.tap()
        let row = app.buttons["settings.category.insights"]
        for _ in 0..<10 where !(row.exists && row.isHittable) { app.swipeUp() }
        XCTAssertTrue(row.waitForExistence(timeout: 5), "Settings › Insights")
        row.tap()
        let forecast = app.switches["settings.insights.forecast"]
        for _ in 0..<6 where !(forecast.exists && forecast.isHittable) { app.swipeUp() }
        XCTAssertTrue(forecast.waitForExistence(timeout: 5), "Forecast switch")
        sleep(1)
        shot(app, "analytics-07-settings-forecast")
    }
}
