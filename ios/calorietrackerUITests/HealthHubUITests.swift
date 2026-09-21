import XCTest

/// Health Data hub smoke flow on the simulator: Connect tile → hub → Apple Health sheet, and
/// the bulk-data path (fixture import via `-ayuvoHealthFixture`) through category rows,
/// the detail screen, Show All Data paging and Data Sources & Access.
final class HealthHubUITests: XCTestCase {

    override func setUpWithError() throws {
        continueAfterFailure = false
    }

    private func attach(_ app: XCUIApplication, _ name: String) {
        let screenshot = XCTAttachment(screenshot: app.screenshot())
        screenshot.name = name
        screenshot.lifetime = .keepAlways
        add(screenshot)
    }

    private func scrollTo(_ element: XCUIElement, in app: XCUIApplication, attempts: Int = 8) {
        for _ in 0..<attempts where !element.isHittable {
            app.swipeUp()
        }
    }

    /// List rows wrap their labels in NavigationLinks whose hit-test region XCTest sometimes
    /// reports as not hittable; a coordinate tap on the label lands on the row regardless.
    private func tapLabel(_ element: XCUIElement) {
        element.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
    }

    /// Dismisses HealthKit's authorization sheet (a remote view inside the app process) when it
    /// is showing: "Turn On All" then "Allow", falling back to "Don't Allow".
    @discardableResult
    private func settleHealthAccessSheet(_ app: XCUIApplication, timeout: TimeInterval) -> Bool {
        let title = app.staticTexts["Health Access"]
        guard title.waitForExistence(timeout: timeout) else { return false }
        attach(app, "Health access sheet")
        let turnOnAll = app.buttons["Turn On All"].exists ? app.buttons["Turn On All"] : app.staticTexts["Turn On All"]
        if turnOnAll.waitForExistence(timeout: 3) {
            tapLabel(turnOnAll)
        }
        let allow = app.buttons["Allow"].firstMatch
        let deadline = Date().addingTimeInterval(5)
        while Date() < deadline, !(allow.exists && allow.isEnabled) {
            RunLoop.current.run(until: Date().addingTimeInterval(0.25))
        }
        if allow.exists, allow.isEnabled {
            allow.tap()
        } else if app.buttons["Don’t Allow"].firstMatch.exists {
            app.buttons["Don’t Allow"].firstMatch.tap()
        } else if app.buttons["Don't Allow"].firstMatch.exists {
            app.buttons["Don't Allow"].firstMatch.tap()
        }
        _ = title.waitForNonExistence(timeout: 15)
        return true
    }

    @MainActor
    func testConnectTileOpensHubAndHealthAccessSheet() throws {
        let app = XCUIApplication()
        app.launchArguments += ["-AppleLanguages", "(en)", "-hasCompletedOnboarding", "YES", "-healthKitEnabled", "NO"]
        app.launch()

        // The Move ring asks to connect while sync is off; it opens Browse, where the
        // permission card lives.
        let moveRing = app.buttons["summary.ring.move"].firstMatch
        XCTAssertTrue(moveRing.waitForExistence(timeout: 10), "Summary should show the Move ring")
        XCTAssertTrue(app.staticTexts["Connect Health"].firstMatch.waitForExistence(timeout: 5), "Move ring should ask to connect while sync is off")
        attach(app, "01 Summary connect ring")
        moveRing.tap()
        XCTAssertTrue(app.navigationBars["Browse"].waitForExistence(timeout: 8), "The Move ring should open Browse")
        let offStatus = app.buttons["browse.healthStatus"].firstMatch
        // The status row sits under the 17 domain rows.
        for _ in 0..<8 where !offStatus.exists { app.swipeUp() }
        XCTAssertTrue(offStatus.waitForExistence(timeout: 5), "Browse should show the Apple Health status row")
        XCTAssertTrue(offStatus.label.contains("Off"), "The status row should read Off while sync is off; got \(offStatus.label)")
        for _ in 0..<8 where !app.staticTexts["Let Coach use my health data"].firstMatch.exists { app.swipeDown() }
        XCTAssertTrue(app.staticTexts["Let Coach use my health data"].firstMatch.waitForExistence(timeout: 3))
        attach(app, "02 Browse not connected")

        // "Connect" on a fresh install; "Grant access" once a mirror exists or a grant is pending.
        let connect = app.buttons.matching(NSPredicate(format: "label == 'Connect' OR label == 'Grant access'")).firstMatch
        XCTAssertTrue(connect.waitForExistence(timeout: 5))
        connect.tap()

        // A simulator that already answered the sheet in an earlier run resolves silently.
        settleHealthAccessSheet(app, timeout: 15)
        XCTAssertTrue(app.navigationBars["Browse"].waitForExistence(timeout: 20), "Browse should be back after the sheet")
        attach(app, "04 Browse after sheet")
        // The Apple Health row proves the grant path returned to a rendered Browse list; its
        // subtitle is any of the sync status lines.
        let status = app.buttons["browse.healthStatus"].firstMatch
        for _ in 0..<8 where !status.exists { app.swipeUp() }
        XCTAssertTrue(status.waitForExistence(timeout: 30), "Browse should show a sync status line after granting")
        attach(app, "05 Hub status after grant")
    }

    /// The Android-exported sample archive checked in for the unit tests; the app process reads
    /// host paths on the simulator, so the fixture never has to be copied into its container.
    private var fixtureURL: URL {
        URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent()
            .deletingLastPathComponent()
            .appendingPathComponent("calorietrackerTests/Fixtures/health/android-sample.zip")
    }

    @MainActor
    func testBulkFixtureFlowsThroughHubDetailAndAllData() throws {
        let app = XCUIApplication()
        XCTAssertTrue(FileManager.default.fileExists(atPath: fixtureURL.path), "Missing fixture at \(fixtureURL.path)")
        app.launchArguments += [
            "-AppleLanguages", "(en)",
            "-hasCompletedOnboarding", "YES",
            "-healthKitEnabled", "YES",
            "-ayuvoHealthFixture", fixtureURL.path,
        ]
        app.launch()

        // Sync being on with nothing granted pops the access sheet; settle it before touching Home.
        settleHealthAccessSheet(app, timeout: 8)

        XCTAssertTrue(app.staticTexts["Favourites"].firstMatch.waitForExistence(timeout: 10))
        // The fixture import (33k rows) runs at launch; the Steps tile appears once it lands.
        let stepsTile = app.staticTexts["Steps"].firstMatch
        XCTAssertTrue(stepsTile.waitForExistence(timeout: 120), "Summary should show the Steps favourite after the fixture import")
        attach(app, "10 Summary favourites")

        app.tabBars.buttons["Browse"].tap()
        XCTAssertTrue(app.navigationBars["Browse"].waitForExistence(timeout: 8))
        attach(app, "11 Browse")

        let activity = app.buttons["browse.row.activity"].firstMatch
        XCTAssertTrue(activity.waitForExistence(timeout: 5))
        activity.tap()
        XCTAssertTrue(app.navigationBars["Activity"].waitForExistence(timeout: 8))
        XCTAssertTrue(app.staticTexts["Today"].firstMatch.waitForExistence(timeout: 5), "Cumulative rows should read as a day total with a Today caption")
        attach(app, "12 Activity category")

        let stepsRow = app.buttons["browse.metric.steps"].firstMatch
        XCTAssertTrue(stepsRow.waitForExistence(timeout: 5))
        stepsRow.tap()
        XCTAssertTrue(app.navigationBars["Steps"].waitForExistence(timeout: 8))
        XCTAssertTrue(app.staticTexts["Total"].waitForExistence(timeout: 10))
        attach(app, "13 Steps detail week")

        let showAll = app.staticTexts["Show All Data"].firstMatch
        scrollTo(showAll, in: app)
        XCTAssertTrue(showAll.waitForExistence(timeout: 5))
        tapLabel(showAll)
        let loadMore = app.buttons["Load more"].firstMatch
        XCTAssertTrue(loadMore.waitForExistence(timeout: 10), "Show All Data should reveal five records and a Load more button")
        let rowsBefore = app.cells.count
        attach(app, "14 Show All Data first five")
        loadMore.tap()
        XCTAssertTrue(app.cells.count > rowsBefore || app.buttons["Load more"].waitForExistence(timeout: 5))
        attach(app, "15 Show All Data after Load more")
        app.navigationBars.buttons.firstMatch.tap()

        let sources = app.staticTexts["Data Sources & Access"].firstMatch
        scrollTo(sources, in: app)
        XCTAssertTrue(sources.waitForExistence(timeout: 5))
        tapLabel(sources)
        XCTAssertTrue(app.staticTexts["Acme Watch"].waitForExistence(timeout: 8), "Source rows should show the export's source name")
        let manage = app.buttons["Manage Apple Health Access"].firstMatch
        XCTAssertTrue(manage.waitForExistence(timeout: 5))
        attach(app, "16 Data Sources & Access")
        manage.tap()
        let health = XCUIApplication(bundleIdentifier: "com.apple.Health")
        let opened = health.wait(for: .runningForeground, timeout: 10)
        attach(health, "17 Health app after Manage access")
        XCTAssertTrue(opened, "Manage Apple Health Access should open the Health app")
        app.activate()
        XCTAssertTrue(app.navigationBars["Data Sources & Access"].waitForExistence(timeout: 10))
    }
}
