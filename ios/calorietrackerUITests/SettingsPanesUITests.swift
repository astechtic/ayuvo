import XCTest

/// Settings split (plan §6): the hub shows the profile header and one section per group, and
/// every pane opens from its `settings.category.<rawValue>` row with its moved rows in place.
///
/// Optional xcodebuild environment:
/// - `TEST_RUNNER_AYUVO_SETTINGS_SHOTS_DIR=<absolute dir>` writes `i7-<pane>-<mode>.png` per pane.
/// - `TEST_RUNNER_AYUVO_SETTINGS_APPEARANCE=light|dark` (default light).
/// - `TEST_RUNNER_AYUVO_SETTINGS_CONTENT_SIZE=<UIContentSizeCategory raw value>` for Dynamic Type runs.
final class SettingsPanesUITests: XCTestCase {
    private struct Pane {
        let id: String
        let title: String
        let expected: [String]
    }

    private let panes: [Pane] = [
        Pane(id: "personalInfo", title: "Personal Info", expected: ["Birthday", "Body Measurements"]),
        Pane(id: "goalsNutrition", title: "Goals & Targets", expected: ["Weight Goal", "Adaptive Goals"]),
        Pane(id: "units", title: "Units", expected: ["Height & Length", "Water Unit", "Blood Glucose", "Week Starts On"]),
        Pane(id: "nutritionTracking", title: "Nutrition", expected: ["Meal Times", "Default to Grams", "Save to Photos", "Quick Actions", "+ Menu"]),
        Pane(id: "hydration", title: "Hydration", expected: ["Water Tracking"]),
        Pane(id: "fasting", title: "Fasting", expected: ["Fasting Tracking"]),
        Pane(id: "activity", title: "Activity", expected: ["Daily Step Goal", "Training Split"]),
        Pane(id: "medications", title: "Medications", expected: ["Open Medications", "Dose Reminders"]),
        Pane(id: "notifications", title: "Notifications", expected: []),
        Pane(id: "healthData", title: "Health Sync", expected: ["Apple Health", "Browse Health Data", "Sync Now"]),
        Pane(id: "healthRecords", title: "Health Records", expected: ["Storage"]),
        Pane(id: "dataManagement", title: "Backup & Export", expected: ["Export All Data", "Import All Data"]),
        Pane(id: "deleteData", title: "Delete All Data", expected: ["Clear synced health data", "Clear Food Log"]),
        Pane(id: "aiProviders", title: "AI Providers", expected: ["Provider"]),
        Pane(id: "speechToText", title: "Speech-to-Text", expected: ["Language"]),
        Pane(id: "customInstructions", title: "Custom Instructions", expected: ["Save"]),
        Pane(id: "appearance", title: "Appearance", expected: ["Theme Color"]),
        Pane(id: "appUpdates", title: "App & Updates", expected: ["Rate the App"]),
        Pane(id: "helpSupport", title: "Help & Support", expected: ["Contact Support"]),
        Pane(id: "legal", title: "Legal", expected: ["Privacy Policy"]),
    ]

    override func setUpWithError() throws {
        continueAfterFailure = false
    }

    private var environment: [String: String] { ProcessInfo.processInfo.environment }

    private var mode: String { environment["AYUVO_SETTINGS_APPEARANCE"].flatMap { $0.isEmpty ? nil : $0 } ?? "light" }

    private var shotsDirectory: URL? {
        guard let path = environment["AYUVO_SETTINGS_SHOTS_DIR"], !path.isEmpty else { return nil }
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

    private func launch() -> XCUIApplication {
        let app = XCUIApplication()
        app.launchArguments += ["-AppleLanguages", "(en)", "-hasCompletedOnboarding", "YES", "-appearanceMode", mode]
        if let size = environment["AYUVO_SETTINGS_CONTENT_SIZE"], !size.isEmpty {
            app.launchArguments += ["-UIPreferredContentSizeCategoryName", size]
        }
        app.launch()
        return app
    }

    @MainActor
    func testHubAndEveryPaneShowTheirRows() throws {
        let app = launch()
        let settingsTab = app.tabBars.buttons["Settings"]
        XCTAssertTrue(settingsTab.waitForExistence(timeout: 10))
        settingsTab.tap()

        let header = app.buttons["settings.profile"]
        XCTAssertTrue(header.waitForExistence(timeout: 5), "Profile header row")
        XCTAssertTrue(app.staticTexts["Health Profile"].exists || app.staticTexts["HEALTH PROFILE"].exists)
        shot(app, "i7-hub-\(mode)")

        header.tap()
        XCTAssertTrue(app.navigationBars["Personal Info"].waitForExistence(timeout: 5), "Profile header opens Personal Info")
        app.navigationBars["Personal Info"].buttons.firstMatch.tap()

        for pane in panes {
            let row = app.buttons["settings.category.\(pane.id)"]
            for _ in 0..<10 where !(row.exists && row.isHittable) { app.swipeUp() }
            XCTAssertTrue(row.waitForExistence(timeout: 5), "Missing hub row \(pane.id)")
            row.tap()
            let bar = app.navigationBars[pane.title]
            XCTAssertTrue(bar.waitForExistence(timeout: 5), "\(pane.id) did not open \(pane.title)")
            for text in pane.expected {
                let element = app.descendants(matching: .any).matching(NSPredicate(format: "label == %@", text)).firstMatch
                for _ in 0..<10 where !element.exists { app.swipeUp() }
                XCTAssertTrue(element.waitForExistence(timeout: 3), "\(pane.title) is missing \(text)")
            }
            // Screenshot from the top of the pane.
            for _ in 0..<6 { app.swipeDown() }
            shot(app, "i7-\(pane.id)-\(mode)")
            bar.buttons.firstMatch.tap()
            XCTAssertTrue(row.waitForExistence(timeout: 5))
        }
    }
}
