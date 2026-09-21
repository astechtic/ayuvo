import XCTest

/// Rebrand smoke walk: onboarding AI step (API key or on-device), the five tabs, the Health tab's
/// Progress | Health Data | Workouts selector, Records, Coach, the three Settings app-info categories, the
/// Licenses screen and the health export description.
///
/// Set `TEST_RUNNER_AYUVO_SMOKE_SHOTS_DIR=<absolute dir>` on the xcodebuild command line to
/// also write a PNG per step there (the simulator can write host paths); otherwise the
/// screenshots are only kept as test attachments.
final class AyuvoSmokeUITests: XCTestCase {

    override func setUpWithError() throws {
        continueAfterFailure = false
    }

    private var shotsDirectory: URL? {
        guard let path = ProcessInfo.processInfo.environment["AYUVO_SMOKE_SHOTS_DIR"], !path.isEmpty else { return nil }
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
            try? screenshot.pngRepresentation.write(to: dir.appendingPathComponent("\(name).png"))
        }
    }

    private func tapLabel(_ element: XCUIElement) {
        element.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
    }

    private func scrollTo(_ element: XCUIElement, in app: XCUIApplication, attempts: Int = 10) {
        for _ in 0..<attempts where !element.isHittable {
            app.swipeUp()
        }
    }

    @discardableResult
    private func settleHealthAccessSheet(_ app: XCUIApplication, timeout: TimeInterval) -> Bool {
        let title = app.staticTexts["Health Access"]
        guard title.waitForExistence(timeout: timeout) else { return false }
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

    private var fixtureURL: URL {
        URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent()
            .deletingLastPathComponent()
            .appendingPathComponent("calorietrackerTests/Fixtures/health/android-sample.zip")
    }

    private func goBack(_ app: XCUIApplication) {
        let back = app.navigationBars.buttons.firstMatch
        if back.waitForExistence(timeout: 3) { back.tap() }
    }

    @MainActor
    func testOnboardingAIStepOffersAPIKeyAndOnDeviceOptions() throws {
        let app = XCUIApplication()
        app.launchArguments += ["-AppleLanguages", "(en)", "-hasCompletedOnboarding", "NO", "--reset-onboarding", "-ayuvoOnboardingStep", "11"]
        app.launch()

        XCTAssertTrue(app.staticTexts["Set Up Your AI"].waitForExistence(timeout: 10), "Onboarding AI step should open")
        XCTAssertFalse(app.staticTexts["Hosted AI"].exists, "Hosted plan chooser must be gone")
        let cloud = app.buttons["onboarding.aiMode.cloud"]
        let onDevice = app.buttons["onboarding.aiMode.onDevice"]
        XCTAssertTrue(cloud.waitForExistence(timeout: 5), "Missing 'Your API key' option")
        XCTAssertTrue(onDevice.exists, "Missing 'On-device' option")
        XCTAssertTrue(app.buttons["Accept & Continue"].firstMatch.waitForExistence(timeout: 5))

        // Cloud mode: the provider form with its API key field.
        if !cloud.isSelected { cloud.tap() }
        XCTAssertTrue(app.staticTexts["API Key"].firstMatch.waitForExistence(timeout: 5), "Cloud mode should ask for an API key")
        XCTAssertFalse(app.otherElements["onboarding.onDevice.card"].exists)
        shot(app, "01-onboarding-ai-cloud")

        // On-device mode: no API key field, Gemma card instead.
        onDevice.tap()
        XCTAssertTrue(onDevice.isSelected, "On-device option should become selected")
        XCTAssertTrue(app.staticTexts["Gemma 4 E2B"].firstMatch.waitForExistence(timeout: 5), "On-device mode should explain Gemma 4")
        XCTAssertTrue(app.staticTexts["API Key"].firstMatch.waitForNonExistence(timeout: 5), "On-device mode must not ask for an API key")
        shot(app, "01b-onboarding-ai-on-device")
        app.swipeUp()
        shot(app, "01c-onboarding-ai-on-device-download")
        app.swipeDown()

        // Back to cloud: the key field returns.
        cloud.tap()
        XCTAssertTrue(app.staticTexts["API Key"].firstMatch.waitForExistence(timeout: 5), "Cloud mode should show the API key field again")
    }

    @MainActor
    func testTabsHealthCoachSettingsAndLicenses() throws {
        let app = XCUIApplication()
        XCTAssertTrue(FileManager.default.fileExists(atPath: fixtureURL.path), "Missing fixture at \(fixtureURL.path)")
        app.launchArguments += [
            "-AppleLanguages", "(en)",
            "-hasCompletedOnboarding", "YES",
            "-healthKitEnabled", "YES",
            "-ayuvoHealthFixture", fixtureURL.path,
        ]
        app.launch()
        settleHealthAccessSheet(app, timeout: 8)

        // Tab bar
        for tab in ["Summary", "Browse", "Records", "Coach", "Settings"] {
            XCTAssertTrue(app.tabBars.buttons[tab].waitForExistence(timeout: 10), "Missing tab \(tab)")
        }
        XCTAssertFalse(app.tabBars.buttons["Home"].exists, "Home became Summary")
        XCTAssertFalse(app.tabBars.buttons["Health"].exists, "The Health tab was replaced by Browse")

        // Summary: rings and favourites
        XCTAssertTrue(app.otherElements["summary.rings"].waitForExistence(timeout: 10), "Summary should show the rings card")
        XCTAssertTrue(app.staticTexts["Favourites"].firstMatch.waitForExistence(timeout: 10))
        _ = app.staticTexts["Steps"].firstMatch.waitForExistence(timeout: 120)
        shot(app, "02-summary")

        // Browse: searchable domain list
        app.tabBars.buttons["Browse"].tap()
        XCTAssertTrue(app.navigationBars["Browse"].waitForExistence(timeout: 8), "Browse should use a real navigation bar")
        XCTAssertTrue(app.searchFields.firstMatch.waitForExistence(timeout: 8), "Browse should be searchable")
        shot(app, "03-browse")
        let activity = app.buttons["browse.row.activity"].firstMatch
        if activity.waitForExistence(timeout: 10) {
            activity.tap()
            XCTAssertTrue(app.navigationBars["Activity"].waitForExistence(timeout: 8), "Category push must work on the Browse stack")
            shot(app, "04-browse-activity")

            // Workouts and the exercise library live under Activity now.
            let workouts = app.buttons["browse.link.workouts"].firstMatch
            if workouts.waitForExistence(timeout: 8) {
                workouts.tap()
                XCTAssertTrue(app.navigationBars["Workouts"].waitForExistence(timeout: 10), "Workouts should push with its own title")
                XCTAssertTrue(app.buttons["Exercise library"].firstMatch.waitForExistence(timeout: 8))
                shot(app, "05-workouts")
                goBack(app)
            }
            goBack(app)
        }

        // Coach
        app.tabBars.buttons["Coach"].tap()
        XCTAssertTrue(app.staticTexts["Ask Ayuvo"].waitForExistence(timeout: 8))
        shot(app, "06-coach-empty-state")

        // Records
        app.tabBars.buttons["Records"].tap()
        XCTAssertTrue(app.navigationBars["Records"].waitForExistence(timeout: 8))
        shot(app, "07b-records")

        // Settings: three app-info categories
        app.tabBars.buttons["Settings"].tap()
        let categories: [(String, String, String)] = [
            ("appUpdates", "App & Updates", "Rate the App"),
            ("helpSupport", "Help & Support", "Contact Support"),
            ("legal", "Legal", "Privacy Policy"),
        ]
        for (identifier, title, expected) in categories {
            let category = app.buttons["settings.category.\(identifier)"]
            scrollTo(category, in: app, attempts: 12)
            XCTAssertTrue(category.waitForExistence(timeout: 5), "Missing Settings category \(identifier)")
            category.tap()
            XCTAssertTrue(app.navigationBars[title].waitForExistence(timeout: 5), "Missing \(title)")
            XCTAssertTrue(app.staticTexts[expected].firstMatch.waitForExistence(timeout: 5), "Missing \(expected) in \(title)")
            shot(app, "08-settings-\(identifier)")
            if identifier == "appUpdates" {
                XCTAssertTrue(app.staticTexts["Ayuvo"].firstMatch.exists, "About header should read Ayuvo")
                XCTAssertFalse(app.staticTexts["Open Source (MIT)"].exists)
            }
            if identifier == "legal" {
                let licenses = app.staticTexts["Licenses"].firstMatch
                XCTAssertTrue(licenses.waitForExistence(timeout: 5))
                tapLabel(licenses)
                XCTAssertTrue(app.navigationBars["Licenses"].waitForExistence(timeout: 5))
                shot(app, "09-licenses")
                let base = app.staticTexts["Base project — MIT"].firstMatch
                XCTAssertTrue(base.waitForExistence(timeout: 5))
                tapLabel(base)
                // SelectableNoticeTextView surfaces as a static text whose value is the notice body.
                let notice = app.staticTexts["Base project MIT licence"].firstMatch
                XCTAssertTrue(notice.waitForExistence(timeout: 8))
                let text = (notice.value as? String) ?? ""
                XCTAssertTrue(text.contains("Copyright (c) 2026 Apoorv Darshan"), "MIT notice of the base project must be retained")
                shot(app, "10-licenses-mit-notice")
                goBack(app)
                goBack(app)
            }
            goBack(app)
        }
        XCTAssertFalse(app.buttons["settings.category.support"].exists)
        XCTAssertFalse(app.buttons["settings.category.community"].exists)

        // Data Management → Export Health Data description carries the new format id
        let dataManagement = app.buttons["settings.category.dataManagement"]
        scrollTo(dataManagement, in: app, attempts: 12)
        if dataManagement.waitForExistence(timeout: 5) {
            dataManagement.tap()
            let export = app.staticTexts["Export Health Data"].firstMatch
            scrollTo(export, in: app)
            if export.waitForExistence(timeout: 5) {
                tapLabel(export)
                let format = app.staticTexts.matching(NSPredicate(format: "label CONTAINS 'ayuvo-health-data'")).firstMatch
                XCTAssertTrue(format.waitForExistence(timeout: 8), "Export sheet should describe the ayuvo-health-data format")
                shot(app, "11-export-health-data")
            }
        }
    }
}
