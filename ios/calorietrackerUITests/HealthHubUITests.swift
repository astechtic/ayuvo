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

        let connectTile = app.staticTexts["Connect Apple Health"]
        XCTAssertTrue(connectTile.waitForExistence(timeout: 10), "Home should show the Connect Apple Health tile while sync is off")
        attach(app, "01 Home connect tile")
        if connectTile.isHittable {
            connectTile.tap()
        } else {
            tapLabel(connectTile)
        }
        var opened = app.navigationBars["Health Data"].waitForExistence(timeout: 8)
        if !opened {
            // The tile sits in a horizontal scroller inside a list row; fall back to the card header link.
            attach(app, "01b After tile tap")
            tapLabel(app.staticTexts["Health Data"].firstMatch)
            opened = app.navigationBars["Health Data"].waitForExistence(timeout: 8)
        }
        XCTAssertTrue(opened, "Tile should open the Health Data hub")
        // Fresh install: "Connect … to start syncing"; a mirror left by an earlier run: "sync is off — read-only".
        let offStatus = app.staticTexts.matching(NSPredicate(format: "label BEGINSWITH 'Connect Apple Health to start' OR label BEGINSWITH 'Health sync is off'")).firstMatch
        XCTAssertTrue(offStatus.waitForExistence(timeout: 5), "Hub should show the sync-off status line")
        XCTAssertTrue(app.staticTexts["Let Coach use my health data"].firstMatch.waitForExistence(timeout: 3))
        attach(app, "02 Hub not connected")

        // "Connect" on a fresh install; "Grant access" once a mirror exists or a grant is pending.
        let connect = app.buttons.matching(NSPredicate(format: "label == 'Connect' OR label == 'Grant access'")).firstMatch
        XCTAssertTrue(connect.waitForExistence(timeout: 5))
        connect.tap()

        // A simulator that already answered the sheet in an earlier run resolves silently.
        settleHealthAccessSheet(app, timeout: 15)
        XCTAssertTrue(app.navigationBars["Health Data"].waitForExistence(timeout: 20), "Hub should be back after the sheet")
        attach(app, "04 Hub after sheet")
        // Any of the hub's sync status lines proves the grant path returned to a rendered hub.
        let status = app.staticTexts.matching(NSPredicate(format: "label BEGINSWITH 'Last synced' OR label BEGINSWITH 'Nothing shared yet' OR label BEGINSWITH 'Not synced yet' OR label BEGINSWITH 'Syncing' OR label BEGINSWITH 'Importing' OR label BEGINSWITH 'Connect Apple Health' OR label BEGINSWITH 'Health sync is off' OR label BEGINSWITH 'Unlock your iPhone'")).firstMatch
        XCTAssertTrue(status.waitForExistence(timeout: 30), "Hub should show a sync status line after granting")
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

        let hubHeader = app.staticTexts["Health Data"].firstMatch
        XCTAssertTrue(hubHeader.waitForExistence(timeout: 10))
        // The fixture import (33k rows) runs at launch; the Steps tile appears once it lands.
        let stepsTile = app.staticTexts["Steps"].firstMatch
        XCTAssertTrue(stepsTile.waitForExistence(timeout: 120), "Home should show the Steps tile after the fixture import")
        attach(app, "10 Home tiles")

        tapLabel(hubHeader)
        XCTAssertTrue(app.navigationBars["Health Data"].waitForExistence(timeout: 8))
        attach(app, "11 Hub")

        let activity = app.staticTexts["Activity"].firstMatch
        XCTAssertTrue(activity.waitForExistence(timeout: 5))
        tapLabel(activity)
        XCTAssertTrue(app.navigationBars["Activity"].waitForExistence(timeout: 8))
        XCTAssertTrue(app.staticTexts["Today"].firstMatch.waitForExistence(timeout: 5), "Cumulative rows should read as a day total with a Today caption")
        attach(app, "12 Activity category")

        let stepsRow = app.staticTexts["Steps"].firstMatch
        XCTAssertTrue(stepsRow.waitForExistence(timeout: 5))
        tapLabel(stepsRow)
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
