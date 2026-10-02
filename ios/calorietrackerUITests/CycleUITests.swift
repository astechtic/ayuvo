import XCTest

/// Cycle tracking end to end (docs/cycle-tracking.md §5) on DEBUG launch arguments:
/// `-AyuvoCycleReset` empties the cycle database, `-AyuvoCycleSeed` writes four regular periods and a few day logs
/// relative to today. Set `AYUVO_SHOTS_DIR` (xcodebuild: `TEST_RUNNER_AYUVO_SHOTS_DIR`) to also write PNG screenshots.
final class CycleUITests: XCTestCase {
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

    private func launch(_ extra: [String]) -> XCUIApplication {
        let app = XCUIApplication()
        app.launchArguments += ["-AppleLanguages", "(en)", "-hasCompletedOnboarding", "YES", "-healthKitEnabled", "NO",
                                "-notificationsEnabled", "NO", "-cycleEnabled", "YES", "-AyuvoCycleReset"] + extra
        app.launch()
        return app
    }

    private func openCycle(_ app: XCUIApplication) {
        XCTAssertTrue(app.tabBars.buttons["Browse"].waitForExistence(timeout: 15))
        app.tabBars.buttons["Browse"].tap()
        let row = app.descendants(matching: .any).matching(identifier: "browse.row.cycleTracking").firstMatch
        _ = row.waitForExistence(timeout: 3)
        var tries = 0
        while !(row.exists && row.isHittable) && tries < 10 {
            app.swipeUp(velocity: .slow)
            tries += 1
        }
        if !(row.exists && row.isHittable) { shot(app, "cycle-00-browse-missing-row") }
        XCTAssertTrue(row.exists, "Browse › Cycle tracking row")
        row.tap()
    }

    private static var todayString: String {
        let c = Calendar.current.dateComponents([.year, .month, .day], from: Date())
        return String(format: "%04d-%02d-%02d", c.year!, c.month!, c.day!)
    }

    @MainActor
    func testSeededDashboardCalendarDayHistoryInsights() throws {
        let app = launch(["-AyuvoCycleSeed"])
        openCycle(app)

        XCTAssertTrue(app.otherElements["cycle.ring"].firstMatch.waitForExistence(timeout: 10)
                      || app.images["cycle.ring"].firstMatch.waitForExistence(timeout: 2)
                      || app.staticTexts["cycle.status"].firstMatch.waitForExistence(timeout: 5), "Dashboard ring")
        XCTAssertTrue(app.staticTexts["cycle.status"].firstMatch.exists, "Status line")
        sleep(1)
        shot(app, "cycle-01-dashboard")
        app.swipeUp()
        sleep(1)
        shot(app, "cycle-02-dashboard-scrolled")
        app.swipeDown()

        // Calendar and today's day sheet.
        let calendarButton = app.buttons["cycle.action.calendar"].firstMatch
        XCTAssertTrue(calendarButton.waitForExistence(timeout: 5))
        calendarButton.tap()
        let todayCell = app.buttons["cycle.calendar.day.\(Self.todayString)"].firstMatch
        XCTAssertTrue(todayCell.waitForExistence(timeout: 8), "Today in the calendar")
        sleep(1)
        shot(app, "cycle-03-calendar")
        todayCell.tap()

        let save = app.buttons["cycle.day.save"].firstMatch
        XCTAssertTrue(save.waitForExistence(timeout: 8), "Day sheet")
        let light = app.buttons["cycle.day.flow.spotting"].firstMatch
        XCTAssertTrue(light.waitForExistence(timeout: 5))
        light.tap()
        let painToggle = app.switches["cycle.day.painToggle"].firstMatch
        if painToggle.waitForExistence(timeout: 3) { painToggle.switches.firstMatch.exists ? painToggle.switches.firstMatch.tap() : painToggle.tap() }
        sleep(1)
        shot(app, "cycle-04-day-sheet")
        app.swipeUp()
        let headache = app.buttons["cycle.day.symptom.headache"].firstMatch
        if headache.waitForExistence(timeout: 3) { headache.tap() }
        let calm = app.buttons["cycle.day.mood.calm"].firstMatch
        if !calm.exists { app.swipeUp() }
        if calm.waitForExistence(timeout: 3) { calm.tap() }
        sleep(1)
        shot(app, "cycle-05-day-sheet-symptoms")
        save.tap()
        XCTAssertTrue(todayCell.waitForExistence(timeout: 8), "Back on the calendar after saving")
        sleep(1)
        shot(app, "cycle-06-calendar-logged")

        // History.
        app.navigationBars.buttons.element(boundBy: 0).tap()
        let history = app.buttons["cycle.action.history"].firstMatch
        XCTAssertTrue(history.waitForExistence(timeout: 5))
        history.tap()
        XCTAssertTrue(app.navigationBars["History"].waitForExistence(timeout: 8))
        sleep(1)
        shot(app, "cycle-07-history")
        let firstRow = app.cells.firstMatch
        if firstRow.waitForExistence(timeout: 3) {
            firstRow.tap()
            sleep(1)
            shot(app, "cycle-08-cycle-detail")
            app.navigationBars.buttons.element(boundBy: 0).tap()
        }
        app.navigationBars.buttons.element(boundBy: 0).tap()

        // Insights.
        let insights = app.buttons["cycle.action.insights"].firstMatch
        XCTAssertTrue(insights.waitForExistence(timeout: 5))
        insights.tap()
        XCTAssertTrue(app.navigationBars["Insights"].waitForExistence(timeout: 8))
        XCTAssertTrue(app.staticTexts["cycle.disclaimer"].firstMatch.waitForExistence(timeout: 5) || app.staticTexts["Average cycle"].exists)
        sleep(1)
        shot(app, "cycle-09-insights")
        app.swipeUp()
        sleep(1)
        shot(app, "cycle-10-insights-charts")
        app.swipeUp()
        sleep(1)
        shot(app, "cycle-11-insights-more")
    }

    @MainActor
    func testSetupFromEmptyThenPeriodStarted() throws {
        let app = launch([])
        openCycle(app)
        let start = app.buttons["cycle.setup.start"].firstMatch
        let dontRemember = app.switches["cycle.setup.dontRemember"].firstMatch
        XCTAssertTrue(dontRemember.waitForExistence(timeout: 5))
        sleep(1)
        shot(app, "cycle-setup-01")
        dontRemember.switches.firstMatch.exists ? dontRemember.switches.firstMatch.tap() : dontRemember.tap()
        var tries = 0
        while !(start.exists && start.isHittable) && tries < 5 {
            app.swipeUp()
            tries += 1
        }
        XCTAssertTrue(start.waitForExistence(timeout: 5), "Start tracking button")
        sleep(1)
        shot(app, "cycle-setup-02-scrolled")
        start.tap()

        let primary = app.buttons["cycle.primary"].firstMatch
        XCTAssertTrue(primary.waitForExistence(timeout: 10), "Dashboard after setup")
        sleep(1)
        shot(app, "cycle-setup-03-empty-dashboard")
        primary.tap()
        XCTAssertTrue(app.staticTexts["cycle.status"].firstMatch.waitForExistence(timeout: 10), "Estimate after the first period")
        sleep(1)
        shot(app, "cycle-setup-04-period-started")

        // Summary + menu has Period, and the Summary card shows the cycle.
        app.tabBars.buttons["Summary"].tap()
        let card = app.buttons["summary.card.cycle"].firstMatch
        XCTAssertTrue(card.waitForExistence(timeout: 10), "Summary cycle card")
        let add = app.buttons["summary.add"].firstMatch
        XCTAssertTrue(add.waitForExistence(timeout: 5))
        add.tap()
        XCTAssertTrue(app.buttons["log.entry.period"].firstMatch.waitForExistence(timeout: 5), "Period in the + menu")
        shot(app, "cycle-setup-05-summary-menu")
    }
}
