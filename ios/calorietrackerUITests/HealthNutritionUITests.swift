import XCTest

/// Health nutrition types walk (docs/nutrients.md §5a): Browse › Nutrition › All Apple Health Nutrition → Vitamin D
/// (reference lines, About, Learn more, the link to the Ayuvo chart), Copper (lines, and a link to its supplements-only Ayuvo chart) and Dietary
/// Energy (calorie goal rule).
///
/// Needs `dietary_vitamin_d`, `dietary_copper` and `dietary_energy` rows in the app's Health mirror. The
/// `ayuvo-health-data` importer skips `dietary_*` types (they are not exportable), so seed the simulator's
/// `health.sqlite` first and run with `TEST_RUNNER_AYUVO_HEALTH_NUTRITION_SEEDED=1`; otherwise the test is skipped.
/// Set `TEST_RUNNER_AYUVO_SMOKE_SHOTS_DIR=<absolute dir>` to also write PNGs there.
final class HealthNutritionUITests: XCTestCase {
    override func setUpWithError() throws {
        continueAfterFailure = true
    }

    private func shot(_ app: XCUIApplication, _ name: String) {
        let screenshot = app.screenshot()
        let attachment = XCTAttachment(screenshot: screenshot)
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
        if let path = ProcessInfo.processInfo.environment["AYUVO_SMOKE_SHOTS_DIR"], !path.isEmpty {
            let directory = URL(fileURLWithPath: path, isDirectory: true)
            try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
            try? screenshot.pngRepresentation.write(to: directory.appendingPathComponent("\(name).png"))
        }
    }

    /// Dismisses HealthKit's authorization sheet when it shows ("Turn On All", then "Allow").
    private func settleHealthAccessSheet(_ app: XCUIApplication) {
        let title = app.staticTexts["Health Access"]
        guard title.waitForExistence(timeout: 8) else { return }
        let turnOnAll = app.buttons["Turn On All"].exists ? app.buttons["Turn On All"] : app.staticTexts["Turn On All"]
        if turnOnAll.waitForExistence(timeout: 3) { turnOnAll.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap() }
        let allow = app.buttons["Allow"].firstMatch
        if allow.waitForExistence(timeout: 5), allow.isEnabled { allow.tap() }
        _ = title.waitForNonExistence(timeout: 15)
    }

    /// Drags from near the bottom edge so the chart's scrub gesture never takes the swipe.
    private func scrollUp(_ app: XCUIApplication, to dy: CGFloat = 0.3) {
        let start = app.coordinate(withNormalizedOffset: CGVector(dx: 0.92, dy: 0.88))
        start.press(forDuration: 0.05, thenDragTo: app.coordinate(withNormalizedOffset: CGVector(dx: 0.92, dy: dy)))
    }

    private func scrollTo(_ app: XCUIApplication, _ element: XCUIElement, maxSwipes: Int = 8) {
        for _ in 0..<maxSwipes where !(element.exists && element.isHittable) { scrollUp(app) }
    }

    private func back(_ app: XCUIApplication) {
        app.navigationBars.buttons.element(boundBy: 0).tap()
        sleep(1)
    }

    private func openType(_ app: XCUIApplication, _ title: String) {
        let row = app.staticTexts[title].firstMatch
        scrollTo(app, row)
        XCTAssertTrue(row.waitForExistence(timeout: 5), "All Apple Health Nutrition should list \(title)")
        row.tap()
        XCTAssertTrue(app.navigationBars[title].waitForExistence(timeout: 8))
        sleep(2)
    }

    @MainActor
    func testHealthNutritionTypesShowReferenceLines() throws {
        try XCTSkipIf(ProcessInfo.processInfo.environment["AYUVO_HEALTH_NUTRITION_SEEDED"] != "1", "Health mirror not seeded")
        let app = XCUIApplication()
        app.launchArguments += [
            "-AppleLanguages", "(en)",
            "-hasCompletedOnboarding", "YES",
            "-notificationsEnabled", "NO",
            // Sync off: the seeded mirror stays as it is and stays readable.
            "-healthKitEnabled", "NO",
        ]
        app.launch()

        XCTAssertTrue(app.tabBars.buttons["Browse"].waitForExistence(timeout: 10))
        app.tabBars.buttons["Browse"].tap()
        let nutrition = app.buttons["browse.row.nutrition"].firstMatch
        for _ in 0..<6 where !nutrition.exists { app.swipeUp() }
        XCTAssertTrue(nutrition.waitForExistence(timeout: 8))
        nutrition.tap()
        let allHealth = app.buttons["browse.nutrition.allHealth"].firstMatch
        scrollTo(app, allHealth, maxSwipes: 20)
        XCTAssertTrue(allHealth.waitForExistence(timeout: 5))
        allHealth.tap()
        XCTAssertTrue(app.staticTexts["Vitamin D"].firstMatch.waitForExistence(timeout: 10))
        XCTAssertFalse(app.staticTexts["Nothing shared yet"].firstMatch.exists, "The seeded nutrition types should have data")
        sleep(1)
        shot(app, "01_all_apple_health_nutrition")

        // Vitamin D: W with Recommended + Upper limit, About, Learn more, link to the Ayuvo chart.
        openType(app, "Vitamin D")
        shot(app, "02_vitamin_d_week")
        let appChart = app.buttons["metric.nutrient.appChart"].firstMatch
        scrollUp(app, to: 0.45)
        sleep(1)
        shot(app, "03_vitamin_d_data_about")
        let learn = app.buttons["metric.nutrient.learnMore"].firstMatch
        scrollTo(app, learn)
        sleep(1)
        shot(app, "04_vitamin_d_about_learn_more")
        for _ in 0..<4 where !(appChart.exists && appChart.isHittable) {
            let top = app.coordinate(withNormalizedOffset: CGVector(dx: 0.92, dy: 0.35))
            top.press(forDuration: 0.05, thenDragTo: app.coordinate(withNormalizedOffset: CGVector(dx: 0.92, dy: 0.75)))
        }
        XCTAssertTrue(appChart.exists, "Vitamin D is app-tracked: the Ayuvo chart link shows")
        appChart.tap()
        XCTAssertTrue(app.buttons["metric.nutrient.learnMore"].firstMatch.waitForExistence(timeout: 2)
                      || app.otherElements["metric.nutrient.split"].firstMatch.waitForExistence(timeout: 8)
                      || app.staticTexts["Food vs Supplements"].firstMatch.waitForExistence(timeout: 5))
        sleep(1)
        shot(app, "05_ayuvo_vitamin_d_chart")
        back(app)
        for _ in 0..<4 { app.swipeDown() }
        app.buttons["M"].firstMatch.tap()
        sleep(2)
        shot(app, "06_vitamin_d_month")
        app.buttons["D"].firstMatch.tap()
        sleep(2)
        shot(app, "07_vitamin_d_day")
        back(app)

        // Copper: lines and Learn more, and a link to the Ayuvo chart, which counts supplements only (the food log
        // does not record copper).
        openType(app, "Copper")
        shot(app, "08_copper_week")
        let copperLearn = app.buttons["metric.nutrient.learnMore"].firstMatch
        scrollTo(app, copperLearn)
        sleep(1)
        shot(app, "09_copper_about_learn_more")
        let copperChart = app.buttons["metric.nutrient.appChart"].firstMatch
        for _ in 0..<4 where !(copperChart.exists && copperChart.isHittable) {
            let top = app.coordinate(withNormalizedOffset: CGVector(dx: 0.92, dy: 0.35))
            top.press(forDuration: 0.05, thenDragTo: app.coordinate(withNormalizedOffset: CGVector(dx: 0.92, dy: 0.75)))
        }
        XCTAssertTrue(copperChart.exists, "Copper links to its Ayuvo chart")
        XCTAssertTrue(app.staticTexts["Supplements logged in Ayuvo Medications"].firstMatch.exists)
        back(app)

        // Dietary Energy: the calorie goal rule.
        openType(app, "Dietary Energy")
        shot(app, "10_dietary_energy_week")
        XCTAssertFalse(app.buttons["metric.nutrient.learnMore"].firstMatch.exists)
    }
}
