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
            "-healthRecordsAiMode", "off",
            "-ayuvoRecordsFixture", pdf.path,
            "-ayuvoRecordsReset",
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
        let title = recordTitle(app)
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
        search.typeText("complete blood")
        XCTAssertTrue(recordTitle(app).waitForExistence(timeout: 5), "Search should find the record title")
        shot(app, "records-ios-06-search")
        search.typeText("zzz")
        XCTAssertTrue(recordTitle(app).waitForNonExistence(timeout: 5), "Non-matching query hides the record")
        // Clear the query (iOS 26 search bars show a close glyph instead of "Cancel").
        search.typeText(String(repeating: XCUIKeyboardKey.delete.rawValue, count: "complete bloodzzz".count))
        if app.buttons["Continue"].firstMatch.exists { app.buttons["Continue"].firstMatch.tap() }
        XCTAssertTrue(recordTitle(app).waitForExistence(timeout: 5))

        // View modes.
        app.buttons["Grid"].firstMatch.tap()
        XCTAssertTrue(recordTitle(app).waitForExistence(timeout: 5))
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

    /// Phase 1 title (from the filename) or the Phase 2 title derived from the report name.
    private func recordTitle(_ app: XCUIApplication) -> XCUIElement {
        app.staticTexts.matching(NSPredicate(format: "label BEGINSWITH 'CBC Report 2026 09 12' OR label BEGINSWITH 'Complete Blood Count'")).firstMatch
    }

    /// Text-layer lab report with table columns and results outside the printed range.
    private func makeLabReportPDF(named name: String) throws -> URL {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent("records-ui-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let url = directory.appendingPathComponent(name)
        let renderer = UIGraphicsPDFRenderer(bounds: CGRect(x: 0, y: 0, width: 612, height: 792))
        let data = renderer.pdfData { context in
            context.beginPage()
            let bold: [NSAttributedString.Key: Any] = [.font: UIFont.boldSystemFont(ofSize: 20)]
            let body: [NSAttributedString.Key: Any] = [.font: UIFont.systemFont(ofSize: 12)]
            func draw(_ text: String, _ x: CGFloat, _ y: CGFloat, _ attributes: [NSAttributedString.Key: Any]) {
                (text as NSString).draw(at: CGPoint(x: x, y: y), withAttributes: attributes)
            }
            draw("Sunrise Diagnostics Laboratory", 60, 40, bold)
            draw("Lipid Profile", 60, 72, bold)
            draw("Patient Name: Meera Iyer", 60, 110, body)
            draw("Age: 52 Y", 300, 110, body)
            draw("Sex: Female", 420, 110, body)
            draw("Collected: 12/09/2026", 60, 150, body)
            draw("Reported: 12/09/2026", 300, 150, body)
            draw("Test", 60, 190, bold)
            draw("Result", 250, 190, bold)
            draw("Units", 340, 190, bold)
            draw("Reference Range", 440, 190, bold)
            let rows = [("Total Cholesterol", "242", "mg/dL", "125 - 200"), ("Triglycerides", "310", "mg/dL", "50 - 150"),
                        ("HDL Cholesterol", "38", "mg/dL", "40 - 60"), ("LDL Cholesterol", "142", "mg/dL", "0 - 130")]
            for (index, row) in rows.enumerated() {
                let y = 220 + CGFloat(index) * 24
                draw(row.0, 60, y, body)
                draw(row.1, 250, y, body)
                draw(row.2, 340, y, body)
                draw(row.3, 440, y, body)
            }
            draw("Dr. Kavita Rao, MD (Pathology)", 60, 360, body)
        }
        try data.write(to: url)
        return url
    }

    /// Phase 2 walk: a text-layer lab PDF is processed (text → rules → highlights → review), the
    /// Important highlights section and detail highlights show, the review sheet opens, and the
    /// universal search "abnormal" finds the record with a removable chip.
    @MainActor
    func testProcessingReviewHighlightsAndAbnormalSearch() throws {
        let pdf = try makeLabReportPDF(named: "scan_0042.pdf")
        let app = XCUIApplication()
        app.launchArguments += [
            "-AppleLanguages", "(en)",
            "-hasCompletedOnboarding", "YES",
            "-healthRecordsViewMode", "timeline",
            "-healthRecordsAiMode", "off",
            "-ayuvoRecordsFixture", pdf.path,
            "-ayuvoRecordsReset",
        ]
        app.launch()
        XCTAssertTrue(app.tabBars.buttons["Records"].waitForExistence(timeout: 10))
        app.tabBars.buttons["Records"].tap()

        let title = app.staticTexts.matching(NSPredicate(format: "label BEGINSWITH 'Lipid Profile'")).firstMatch
        XCTAssertTrue(title.waitForExistence(timeout: 40), "Processing should retitle the record from its report name")
        let highlights = app.descendants(matching: .any)["records.highlightsSection"]
        XCTAssertTrue(highlights.waitForExistence(timeout: 20), "Important highlights appear after processing")
        XCTAssertTrue(app.descendants(matching: .any).matching(NSPredicate(format: "label CONTAINS 'Total Cholesterol: 242 mg/dL'")).firstMatch.waitForExistence(timeout: 5))
        RunLoop.current.run(until: Date().addingTimeInterval(1.0))
        shot(app, "records-p2-ios-ui-01-home-highlights")

        // Detail: highlights card and extracted information with method badges.
        title.tap()
        XCTAssertTrue(app.descendants(matching: .any)["records.detail.highlights"].waitForExistence(timeout: 10))
        XCTAssertTrue(app.staticTexts["Not processed by AI"].firstMatch.waitForExistence(timeout: 5))
        shot(app, "records-p2-ios-ui-02-detail")

        // Review sheet from the detail menu.
        app.buttons["records.detail.menu"].tap()
        let reviewItem = app.buttons["Review details"].firstMatch
        XCTAssertTrue(reviewItem.waitForExistence(timeout: 5))
        reviewItem.tap()
        XCTAssertTrue(app.navigationBars["We found these details"].waitForExistence(timeout: 8), "Review sheet opens")
        RunLoop.current.run(until: Date().addingTimeInterval(0.8))
        shot(app, "records-p2-ios-ui-03-review")
        app.buttons["records.review.later"].tap()
        XCTAssertTrue(app.navigationBars["We found these details"].waitForNonExistence(timeout: 5))
        app.navigationBars.buttons.firstMatch.tap()

        // Universal search: "abnormal" becomes a flag chip, not a text term.
        let search = app.searchFields.firstMatch
        XCTAssertTrue(search.waitForExistence(timeout: 5))
        search.tap()
        search.typeText("abnormal")
        XCTAssertTrue(app.buttons["records.searchChip.flag:abnormal"].waitForExistence(timeout: 8), "Parsed flag chip shows")
        XCTAssertTrue(title.waitForExistence(timeout: 8), "The abnormal lab report is found")
        RunLoop.current.run(until: Date().addingTimeInterval(0.8))
        shot(app, "records-p2-ios-ui-04-search-abnormal")
    }

    /// Manual walk on the simulator (skipped unless `TEST_RUNNER_AYUVO_RECORDS_MANUAL_FIXTURES` points at a
    /// folder with lab_report_cbc.pdf, lab_report_cbc_copy.pdf, hospital_bundle.pdf, thyroid_report_photo.png):
    /// processing, chooser card, near-duplicate sheet, split review + acceptance, OCR image, filters,
    /// Settings AI processing and the ask-mode consent banner.
    @MainActor
    func testManualWalkWithGeneratedFixtures() throws {
        guard let folder = ProcessInfo.processInfo.environment["AYUVO_RECORDS_MANUAL_FIXTURES"], !folder.isEmpty else {
            throw XCTSkip("manual fixtures not provided")
        }
        let files = ["lab_report_cbc.pdf", "lab_report_cbc_copy.pdf", "hospital_bundle.pdf", "thyroid_report_photo.png"]
            .map { URL(fileURLWithPath: folder).appendingPathComponent($0).path }
        let app = XCUIApplication()
        app.launchArguments += ["-AppleLanguages", "(en)", "-hasCompletedOnboarding", "YES", "-healthRecordsViewMode", "timeline",
                                "-ayuvoRecordsFixture", files.joined(separator: ","), "-ayuvoRecordsReset"]
        app.launch()
        XCTAssertTrue(app.tabBars.buttons["Records"].waitForExistence(timeout: 10))
        app.tabBars.buttons["Records"].tap()
        if app.descendants(matching: .any)["records.processingStrip"].waitForExistence(timeout: 8) {
            shot(app, "records-p2-ios-01-processing")
        }
        let strip = app.descendants(matching: .any)["records.processingStrip"]
        _ = strip.waitForExistence(timeout: 10)
        XCTAssertTrue(strip.waitForNonExistence(timeout: 300), "processing finishes")
        RunLoop.current.run(until: Date().addingTimeInterval(1.5))
        shot(app, "records-p2-ios-02-home-chooser-review")
        // AI chooser card: choose "Ask me when needed".
        if app.buttons["records.aiMode.ask"].exists {
            app.buttons["records.aiMode.ask"].tap()
            app.buttons["records.aiChooser.save"].tap()
        }
        RunLoop.current.run(until: Date().addingTimeInterval(1))
        shot(app, "records-p2-ios-03-home-sections")
        app.swipeUp()
        shot(app, "records-p2-ios-03b-home-sections-scrolled")
        app.swipeDown()

        // Near duplicate.
        let duplicates = app.buttons["records.nearDuplicates"]
        for _ in 0..<3 where !duplicates.isHittable { app.swipeUp() }
        if duplicates.waitForExistence(timeout: 10) {
            duplicates.tap()
            XCTAssertTrue(app.navigationBars["This looks like an existing record"].waitForExistence(timeout: 8))
            RunLoop.current.run(until: Date().addingTimeInterval(0.8))
            shot(app, "records-p2-ios-04-near-duplicate")
            app.buttons["records.duplicate.keepBoth"].tap()
        } else {
            XCTFail("near-duplicate entry point missing")
        }
        for _ in 0..<3 { app.swipeDown() }

        // Split review of the 4-page bundle.
        let bundle = app.descendants(matching: .any).matching(NSPredicate(format: "label CONTAINS '4 pages'")).firstMatch
        for _ in 0..<8 where !bundle.exists || bundle.frame.maxY > app.frame.maxY - 220 {
            app.swiftUpSmall()
        }
        XCTAssertTrue(bundle.waitForExistence(timeout: 10))
        bundle.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
        XCTAssertTrue(app.staticTexts["records.detail.title"].waitForExistence(timeout: 10))
        let splitBanner = app.descendants(matching: .any)["records.detail.split"].firstMatch
        for _ in 0..<5 where !(splitBanner.exists && splitBanner.isHittable) {
            app.swipeUp()
            RunLoop.current.run(until: Date().addingTimeInterval(0.5))
        }
        if !splitBanner.exists { shot(app, "records-p2-ios-05-split-banner-missing") }
        XCTAssertTrue(splitBanner.waitForExistence(timeout: 10), "split proposal banner")
        shot(app, "records-p2-ios-05-detail-split-banner")
        splitBanner.tap()
        let save = app.descendants(matching: .any)["records.split.save"].firstMatch
        XCTAssertTrue(save.waitForExistence(timeout: 10))
        RunLoop.current.run(until: Date().addingTimeInterval(2))
        shot(app, "records-p2-ios-06-split-review")
        save.tap()
        let confirm = app.buttons["Save records"].firstMatch
        XCTAssertTrue(confirm.waitForExistence(timeout: 5))
        confirm.tap()
        XCTAssertTrue(save.waitForNonExistence(timeout: 10), "split review closes after saving")
        RunLoop.current.run(until: Date().addingTimeInterval(3))
        shot(app, "records-p2-ios-07-after-split-parent")
        app.navigationBars.buttons.firstMatch.tap()
        RunLoop.current.run(until: Date().addingTimeInterval(6))
        shot(app, "records-p2-ios-08-timeline-children")

    }

    /// Filters sheet (abnormal only) and Settings › Health Records › AI processing on a small store.
    @MainActor
    func testFiltersAndSettingsAIProcessing() throws {
        guard let folder = ProcessInfo.processInfo.environment["AYUVO_RECORDS_MANUAL_FIXTURES"], !folder.isEmpty else {
            throw XCTSkip("manual fixtures not provided")
        }
        let files = ["lab_report_cbc.pdf", "thyroid_report_photo.png"].map { URL(fileURLWithPath: folder).appendingPathComponent($0).path }
        let app = XCUIApplication()
        app.launchArguments += ["-AppleLanguages", "(en)", "-hasCompletedOnboarding", "YES", "-healthRecordsAiMode", "off",
                                "-ayuvoRecordsFixture", files.joined(separator: ","), "-ayuvoRecordsReset"]
        app.launch()
        app.tabBars.buttons["Records"].tap()
        let thyroid = app.staticTexts.matching(NSPredicate(format: "label BEGINSWITH 'Thyroid'")).firstMatch
        XCTAssertTrue(thyroid.waitForExistence(timeout: 120))
        thyroid.tap()
        RunLoop.current.run(until: Date().addingTimeInterval(1.5))
        shot(app, "records-p2-ios-09-ocr-image-detail")
        app.swipeUp()
        shot(app, "records-p2-ios-10-ocr-image-extracted")
        app.navigationBars.buttons.firstMatch.tap()

        app.buttons["records.filters"].tap()
        let abnormal = app.switches["records.filters.abnormal"]
        for _ in 0..<8 where !abnormal.isHittable { app.swipeUp() }
        XCTAssertTrue(abnormal.waitForExistence(timeout: 5))
        abnormal.coordinate(withNormalizedOffset: CGVector(dx: 0.9, dy: 0.5)).tap()
        shot(app, "records-p2-ios-11-filters")
        app.buttons["records.filters.apply"].tap()
        RunLoop.current.run(until: Date().addingTimeInterval(1.5))
        shot(app, "records-p2-ios-12-filtered-abnormal")

        app.tabBars.buttons["Settings"].tap()
        let category = app.buttons["settings.category.healthRecords"]
        for _ in 0..<8 where !category.isHittable { app.swipeUp() }
        category.tap()
        XCTAssertTrue(app.buttons["records.aiMode.local"].waitForExistence(timeout: 5))
        shot(app, "records-p2-ios-13-settings-ai-processing")
    }

    /// Ask mode with no AI configured: waiting records show the consent banner with "Not now".
    @MainActor
    func testAskModeConsentBannerWithoutProvider() throws {
        guard let folder = ProcessInfo.processInfo.environment["AYUVO_RECORDS_MANUAL_FIXTURES"], !folder.isEmpty else {
            throw XCTSkip("manual fixtures not provided")
        }
        let app = XCUIApplication()
        app.launchArguments += ["-AppleLanguages", "(en)", "-hasCompletedOnboarding", "YES", "-healthRecordsAiMode", "ask",
                                "-ayuvoRecordsFixture", URL(fileURLWithPath: folder).appendingPathComponent("visit_note.txt").path,
                                "-ayuvoRecordsReset"]
        app.launch()
        app.tabBars.buttons["Records"].tap()
        let banner = app.descendants(matching: .any)["records.consentBanner"]
        if banner.waitForExistence(timeout: 90) {
            RunLoop.current.run(until: Date().addingTimeInterval(1))
            shot(app, "records-p2-ios-14-ask-consent-banner")
            app.descendants(matching: .any)["records.consent.notNow"].firstMatch.tap()
            XCTAssertTrue(banner.waitForNonExistence(timeout: 10), "Not now clears the wait")
            shot(app, "records-p2-ios-15-after-not-now")
        } else {
            shot(app, "records-p2-ios-14-ask-no-banner")
            XCTFail("consent banner did not appear (record may have no AI gaps)")
        }
    }
}

private extension XCUIApplication {
    /// A short upward drag (about a third of the screen) so rows stop above the tab bar.
    func swiftUpSmall() {
        let start = coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.75))
        let end = coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.45))
        start.press(forDuration: 0.05, thenDragTo: end)
    }
}
