import UIKit
import XCTest

/// Health Records Phase 1 walk: tabs, Home → Health › Workouts shortcut, Records timeline with an
/// imported PDF (via the DEBUG `-ayuvoRecordsFixture` hook, which runs the real importer),
/// detail viewer, search, view modes and Settings › Health Records.
///
/// Set `TEST_RUNNER_AYUVO_SMOKE_SHOTS_DIR=<absolute dir>` to also write PNGs there.
final class HealthRecordsUITests: XCTestCase {
    override func setUpWithError() throws {
        continueAfterFailure = false
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

    private func makeFixturePDF(named name: String) throws -> URL {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent("records-ui-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let url = directory.appendingPathComponent(name)
        let renderer = UIGraphicsPDFRenderer(bounds: CGRect(x: 0, y: 0, width: 612, height: 792))
        let data = renderer.pdfData { context in
            for page in 1...2 {
                context.beginPage()
                let title = "Complete Blood Count — page \(page)" as NSString
                title.draw(at: CGPoint(x: 60, y: 60), withAttributes: [.font: UIFont.boldSystemFont(ofSize: 26)])
                let body = "Hemoglobin 9.7 g/dL (13.0 – 17.0)\nWBC 6.2 x10^3/uL\nPlatelets 250 x10^3/uL" as NSString
                body.draw(in: CGRect(x: 60, y: 120, width: 480, height: 300), withAttributes: [.font: UIFont.systemFont(ofSize: 18)])
            }
        }
        try data.write(to: url)
        return url
    }

    @MainActor
    func testRecordsTabImportDetailSearchAndWorkoutsPane() throws {
        let pdf = try makeFixturePDF(named: "CBC_Report_2026-09-12.pdf")
        let app = XCUIApplication()
        app.launchArguments += [
            "-AppleLanguages", "(en)",
            "-hasCompletedOnboarding", "YES",
            "-healthRecordsViewMode", "timeline",
            "-ayuvoRecordsFixture", pdf.path,
        ]
        app.launch()

        for tab in ["Home", "Health", "Records", "Coach", "Settings"] {
            XCTAssertTrue(app.tabBars.buttons[tab].waitForExistence(timeout: 10), "Missing tab \(tab)")
        }
        XCTAssertFalse(app.tabBars.buttons["Workouts"].exists, "Workouts moved into the Health tab")

        // Home → Workouts shortcut → Health tab, Workouts pane.
        let shortcut = app.buttons["home.workoutsShortcut"]
        for _ in 0..<4 where !shortcut.isHittable { app.swipeUp() }
        XCTAssertTrue(shortcut.waitForExistence(timeout: 5), "Home should offer a Workouts shortcut")
        shot(app, "records-ios-01-home-shortcut")
        shortcut.tap()
        XCTAssertTrue(app.buttons["Workouts"].firstMatch.waitForExistence(timeout: 5))
        XCTAssertTrue(
            app.buttons["Exercise library"].firstMatch.waitForExistence(timeout: 8)
                || app.textFields["workouts.search"].waitForExistence(timeout: 2),
            "Workouts pane should show the workout log or library"
        )
        XCTAssertFalse(app.navigationBars["Workouts"].exists, "No navigation bar above the Health selector")
        shot(app, "records-ios-02-health-workouts")

        // The library push must land on the Health stack.
        let library = app.buttons["Exercise library"].firstMatch
        if library.exists {
            library.tap()
        }
        let firstExercise = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH 'workouts.exercise.'")).firstMatch
        if firstExercise.waitForExistence(timeout: 8) {
            firstExercise.tap()
            XCTAssertTrue(app.navigationBars.firstMatch.waitForExistence(timeout: 5), "Exercise detail should push inside the Health tab")
            shot(app, "records-ios-03-workouts-push")
            app.navigationBars.buttons.firstMatch.tap()
        }

        // Records timeline shows the imported PDF.
        app.tabBars.buttons["Records"].tap()
        XCTAssertTrue(app.navigationBars["Records"].waitForExistence(timeout: 8))
        let title = app.staticTexts["CBC Report 2026 09 12"].firstMatch
        XCTAssertTrue(title.waitForExistence(timeout: 15), "Imported PDF should appear in the timeline")
        XCTAssertTrue(app.staticTexts["September 2026"].firstMatch.waitForExistence(timeout: 10), "Timeline groups by the file date month")
        RunLoop.current.run(until: Date().addingTimeInterval(1.5))
        shot(app, "records-ios-04-timeline")

        // Detail renders the original.
        title.tap()
        XCTAssertTrue(app.staticTexts["records.detail.title"].waitForExistence(timeout: 8))
        XCTAssertTrue(app.descendants(matching: .any)["records.detail.viewer"].waitForExistence(timeout: 8), "Viewer should render")
        RunLoop.current.run(until: Date().addingTimeInterval(1.0))
        shot(app, "records-ios-05-detail")
        app.navigationBars.buttons.firstMatch.tap()

        // Search finds the title (folded, prefix).
        let search = app.searchFields.firstMatch
        XCTAssertTrue(search.waitForExistence(timeout: 5))
        search.tap()
        search.typeText("cbc rep")
        XCTAssertTrue(app.staticTexts["CBC Report 2026 09 12"].firstMatch.waitForExistence(timeout: 5), "Search should find the record title")
        shot(app, "records-ios-06-search")
        search.typeText("zzz")
        XCTAssertTrue(app.staticTexts["CBC Report 2026 09 12"].firstMatch.waitForNonExistence(timeout: 5), "Non-matching query hides the record")
        // Clear the query (iOS 26 search bars show a close glyph instead of "Cancel").
        search.typeText(String(repeating: XCUIKeyboardKey.delete.rawValue, count: "cbc repzzz".count))
        if app.buttons["Continue"].firstMatch.exists { app.buttons["Continue"].firstMatch.tap() }
        XCTAssertTrue(app.staticTexts["CBC Report 2026 09 12"].firstMatch.waitForExistence(timeout: 5))

        // View modes.
        app.buttons["Grid"].firstMatch.tap()
        XCTAssertTrue(app.staticTexts["CBC Report 2026 09 12"].firstMatch.waitForExistence(timeout: 5))
        shot(app, "records-ios-07-grid")
        app.buttons["Timeline"].firstMatch.tap()
        // Leave search (the keyboard hides the tab bar): the close glyph beside the field.
        app.coordinate(withNormalizedOffset: CGVector(dx: 0.904, dy: 0.1055)).tap()
        _ = app.keyboards.firstMatch.waitForNonExistence(timeout: 5)

        // Settings › Health Records explainer.
        app.tabBars.buttons["Settings"].tap()
        let category = app.buttons["settings.category.healthRecords"]
        for _ in 0..<8 where !category.isHittable { app.swipeUp() }
        XCTAssertTrue(category.waitForExistence(timeout: 5))
        category.tap()
        XCTAssertTrue(app.navigationBars["Health Records"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.staticTexts["How Ayuvo handles your health records"].firstMatch.waitForExistence(timeout: 5)
            || app.staticTexts["HOW AYUVO HANDLES YOUR HEALTH RECORDS"].firstMatch.waitForExistence(timeout: 2))
        shot(app, "records-ios-08-settings")
    }
}
