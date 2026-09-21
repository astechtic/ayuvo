import UIKit
import XCTest

/// Health Records Phase 1 walk: tabs, Browse › Activity › Workouts, Records timeline with an
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

        for tab in ["Summary", "Browse", "Records", "Coach", "Settings"] {
            XCTAssertTrue(app.tabBars.buttons[tab].waitForExistence(timeout: 10), "Missing tab \(tab)")
        }
        XCTAssertFalse(app.tabBars.buttons["Workouts"].exists, "Workouts lives under Browse › Activity")

        // Browse › Activity › Workouts, and the library push lands on the Browse stack.
        app.tabBars.buttons["Browse"].tap()
        let activityRow = app.buttons["browse.row.activity"].firstMatch
        XCTAssertTrue(activityRow.waitForExistence(timeout: 8), "Browse should list Activity")
        activityRow.tap()
        let workoutsLink = app.buttons["browse.link.workouts"].firstMatch
        XCTAssertTrue(workoutsLink.waitForExistence(timeout: 8), "Activity should link to Workouts")
        workoutsLink.tap()
        XCTAssertTrue(app.navigationBars["Workouts"].waitForExistence(timeout: 10), "Workouts pushes with its own navigation bar")
        XCTAssertTrue(app.buttons["Exercise library"].firstMatch.waitForExistence(timeout: 8),
                      "The workout log should offer the exercise library")

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

extension HealthRecordsUITests {
    /// Text-layer CBC with a hemoglobin value on a given collection date.
    private func makeCBCPDF(named name: String, date: String, hemoglobin: String, extraRow: (String, String, String, String)? = nil) throws -> URL {
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
            draw("City Diagnostics Laboratory", 60, 40, bold)
            draw("Complete Blood Count", 60, 72, bold)
            draw("Patient Name: Asha Rao", 60, 110, body)
            draw("Age: 34 Y", 300, 110, body)
            draw("Sex: Female", 420, 110, body)
            draw("Ref. by: Dr. Suresh Menon", 60, 130, body)
            draw("Collected: \(date)", 60, 150, body)
            draw("Reported: \(date)", 300, 150, body)
            draw("Test", 60, 190, bold)
            draw("Result", 250, 190, bold)
            draw("Units", 340, 190, bold)
            draw("Reference Range", 440, 190, bold)
            let rows = [("Hemoglobin", hemoglobin, "g/dL", "13.0 - 17.0"), ("Total WBC Count", "6200", "cells/cumm", "4000 - 11000"),
                        ("Platelet Count", "250", "10^3/µL", "150 - 410")] + (extraRow.map { [$0] } ?? [])
            for (index, row) in rows.enumerated() {
                let y = 220 + CGFloat(index) * 24
                draw(row.0, 60, y, body)
                draw(row.1, 250, y, body)
                draw(row.2, 340, y, body)
                draw(row.3, 440, y, body)
            }
        }
        try data.write(to: url)
        return url
    }

    private func scrollTo(_ element: XCUIElement, in app: XCUIApplication, maxSwipes: Int = 10) {
        for _ in 0..<maxSwipes where !(element.exists && element.isHittable) {
            app.swiftUpSmall()
        }
    }

    /// Phase 3 walk: three CBC reports (Jul/Aug/Sep) → detail mini trend "7.2 → 8.4 → 9.7" → full trend →
    /// point → source page, search "hemoglobin was low" shows Values, link / unlink a record.
    @MainActor
    func testKnowledgeTrendSourceValuesSearchAndLinks() throws {
        let files = try [("cbc_jul.pdf", "18/07/2026", "7.2"), ("cbc_aug.pdf", "10/08/2026", "8.4"), ("cbc_sep.pdf", "12/09/2026", "9.7")]
            .map { try makeCBCPDF(named: $0.0, date: $0.1, hemoglobin: $0.2).path }
        let app = XCUIApplication()
        app.launchArguments += ["-AppleLanguages", "(en)", "-hasCompletedOnboarding", "YES", "-healthRecordsViewMode", "timeline",
                                "-healthRecordsAiMode", "off", "-ayuvoRecordsFixture", files.joined(separator: ","), "-ayuvoRecordsReset"]
        app.launch()
        XCTAssertTrue(app.tabBars.buttons["Records"].waitForExistence(timeout: 10))
        app.tabBars.buttons["Records"].tap()

        let strip = app.descendants(matching: .any)["records.processingStrip"]
        let september = app.staticTexts["September 2026"].firstMatch
        XCTAssertTrue(september.waitForExistence(timeout: 60), "records land in the timeline")
        _ = strip.waitForExistence(timeout: 5)
        XCTAssertTrue(strip.waitForNonExistence(timeout: 180), "processing finishes")
        RunLoop.current.run(until: Date().addingTimeInterval(1.5))
        shot(app, "records-p3-ios-ui-01-timeline")

        // Sep record (first in the timeline) → Health data points with the mini trend.
        let title = app.staticTexts.matching(NSPredicate(format: "label BEGINSWITH 'Complete Blood Count'")).firstMatch
        XCTAssertTrue(title.waitForExistence(timeout: 20))
        title.tap()
        XCTAssertTrue(app.staticTexts["records.detail.title"].waitForExistence(timeout: 10))
        let mini = app.descendants(matching: .any)["records.miniTrend.hemoglobin"].firstMatch
        scrollTo(mini, in: app)
        XCTAssertTrue(mini.waitForExistence(timeout: 10), "mini trend for hemoglobin")
        XCTAssertTrue(mini.label.contains("7.2 → 8.4 → 9.7"), "mini trend label: \(mini.label)")
        shot(app, "records-p3-ios-ui-02-detail-data-points")

        // Full trend.
        mini.tap()
        let chart = app.descendants(matching: .any)["records.trend.chart"].firstMatch
        XCTAssertTrue(chart.waitForExistence(timeout: 10), "trend chart")
        RunLoop.current.run(until: Date().addingTimeInterval(1.0))
        shot(app, "records-p3-ios-ui-03-trend")

        // Point → record at the source page.
        let augPoint = app.buttons["records.trend.point.2026-08-10"].firstMatch
        scrollTo(augPoint, in: app)
        XCTAssertTrue(augPoint.waitForExistence(timeout: 5), "Aug point in the table")
        augPoint.tap()
        let source = app.descendants(matching: .any)["records.sourceViewer"].firstMatch
        XCTAssertTrue(source.waitForExistence(timeout: 10), "source viewer opens at the observation's page")
        RunLoop.current.run(until: Date().addingTimeInterval(1.5))
        shot(app, "records-p3-ios-ui-04-source")
        app.buttons["Done"].firstMatch.tap()
        XCTAssertTrue(source.waitForNonExistence(timeout: 5))
        XCTAssertTrue(app.staticTexts["records.detail.title"].waitForExistence(timeout: 5), "Aug record detail")

        // Link / unlink from the Aug record.
        let link = app.buttons["records.related.link"].firstMatch
        scrollTo(link, in: app, maxSwipes: 16)
        XCTAssertTrue(link.waitForExistence(timeout: 5))
        shot(app, "records-p3-ios-ui-05-related")
        link.tap()
        let candidate = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH 'records.linkSheet.candidate.'")).firstMatch
        XCTAssertTrue(candidate.waitForExistence(timeout: 10))
        candidate.tap()
        shot(app, "records-p3-ios-ui-06-link-sheet")
        app.buttons["records.linkSheet.save"].tap()
        let linked = app.descendants(matching: .any).matching(NSPredicate(format: "identifier BEGINSWITH 'records.related.linked.'")).firstMatch
        XCTAssertTrue(linked.waitForExistence(timeout: 10), "linked record appears")
        scrollTo(linked, in: app)
        RunLoop.current.run(until: Date().addingTimeInterval(1.0))
        shot(app, "records-p3-ios-ui-07-linked")
        let menu = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH 'records.related.menu.'")).firstMatch
        XCTAssertTrue(menu.waitForExistence(timeout: 5))
        menu.tap()
        app.buttons["Unlink"].firstMatch.tap()
        let confirm = app.buttons["Unlink"].firstMatch
        XCTAssertTrue(confirm.waitForExistence(timeout: 5))
        confirm.tap()
        XCTAssertTrue(linked.waitForNonExistence(timeout: 10), "unlink removes the link")

        // Back to the Records root and search by value.
        for _ in 0..<3 where !app.navigationBars["Records"].exists {
            app.navigationBars.buttons.firstMatch.tap()
            RunLoop.current.run(until: Date().addingTimeInterval(0.6))
        }
        let search = app.searchFields.firstMatch
        XCTAssertTrue(search.waitForExistence(timeout: 8))
        search.tap()
        search.typeText("hemoglobin was low")
        let values = app.descendants(matching: .any)["records.search.values"].firstMatch
        XCTAssertTrue(values.waitForExistence(timeout: 10), "Values group shows observation hits")
        RunLoop.current.run(until: Date().addingTimeInterval(1.0))
        shot(app, "records-p3-ios-ui-08-search-values")
    }
}

extension HealthRecordsUITests {
    /// Manual walk (screenshots): edit a value, map an unmapped test, add a value, accept / reject
    /// suggestions and the entity-backed doctor filter.
    @MainActor
    func testKnowledgeManualWalkEditMapAddAndSuggestions() throws {
        let files = try [("cbc_jul.pdf", "18/07/2026", "7.2", nil), ("cbc_aug.pdf", "10/08/2026", "8.4", nil),
                         ("cbc_sep.pdf", "12/09/2026", "9.7", ("Retic Xq Index", "1.2", "%", "0.5 - 2.5"))]
            .map { try makeCBCPDF(named: $0.0, date: $0.1, hemoglobin: $0.2, extraRow: $0.3).path }
        let app = XCUIApplication()
        app.launchArguments += ["-AppleLanguages", "(en)", "-hasCompletedOnboarding", "YES", "-healthRecordsViewMode", "timeline",
                                "-healthRecordsAiMode", "off", "-ayuvoRecordsFixture", files.joined(separator: ","), "-ayuvoRecordsReset"]
        app.launch()
        app.tabBars.buttons["Records"].tap()
        let strip = app.descendants(matching: .any)["records.processingStrip"]
        XCTAssertTrue(app.staticTexts["September 2026"].firstMatch.waitForExistence(timeout: 60))
        _ = strip.waitForExistence(timeout: 5)
        XCTAssertTrue(strip.waitForNonExistence(timeout: 180))
        RunLoop.current.run(until: Date().addingTimeInterval(1.5))
        app.staticTexts.matching(NSPredicate(format: "label BEGINSWITH 'Complete Blood Count'")).firstMatch.tap()

        // Edit the hemoglobin value.
        let hb = app.buttons["records.observation.Hemoglobin"].firstMatch
        scrollTo(hb, in: app)
        XCTAssertTrue(hb.waitForExistence(timeout: 10))
        hb.tap()
        let value = app.textFields["records.observationEdit.value"]
        XCTAssertTrue(value.waitForExistence(timeout: 5))
        shot(app, "records-p3-ios-manual-01-edit-sheet")
        value.tap()
        value.typeText(String(repeating: XCUIKeyboardKey.delete.rawValue, count: 4) + "9.9")
        let unit = app.textFields["records.observationEdit.unit"]
        unit.tap()
        unit.typeText(String(repeating: XCUIKeyboardKey.delete.rawValue, count: 6) + "gm/dl")
        shot(app, "records-p3-ios-manual-02-edit-value-unit")
        app.buttons["records.observationEdit.save"].tap()
        XCTAssertTrue(app.staticTexts.matching(NSPredicate(format: "label CONTAINS '9.9 g/dL'")).firstMatch.waitForExistence(timeout: 8)
            || app.buttons.matching(NSPredicate(format: "label CONTAINS '9.9 g/dL'")).firstMatch.waitForExistence(timeout: 2), "edited value shows with the canonical unit spelling")
        shot(app, "records-p3-ios-manual-03-after-edit")

        // Map the unmapped test.
        let retic = app.buttons["records.observation.Retic Xq Index"].firstMatch
        scrollTo(retic, in: app)
        XCTAssertTrue(retic.waitForExistence(timeout: 5))
        shot(app, "records-p3-ios-manual-04-unmapped-row")
        retic.tap()
        app.buttons["records.observationEdit.analyte"].firstMatch.tap()
        let search = app.searchFields.firstMatch
        XCTAssertTrue(search.waitForExistence(timeout: 5))
        search.tap()
        search.typeText("reticulocyte")
        let pick = app.buttons["records.analytePicker.reticulocyte_pct"].firstMatch
        XCTAssertTrue(pick.waitForExistence(timeout: 5))
        shot(app, "records-p3-ios-manual-05-analyte-picker")
        pick.tap()
        XCTAssertTrue(app.switches["records.observationEdit.remember"].waitForExistence(timeout: 5))
        shot(app, "records-p3-ios-manual-06-mapped-remember")
        app.buttons["records.observationEdit.save"].tap()
        XCTAssertTrue(app.buttons["records.observation.Retic Xq Index"].firstMatch.waitForExistence(timeout: 8))
        shot(app, "records-p3-ios-manual-07-after-map")

        // Add a manual value.
        let add = app.buttons["records.observation.add"].firstMatch
        scrollTo(add, in: app)
        add.tap()
        let name = app.textFields["records.observationAdd.name"]
        XCTAssertTrue(name.waitForExistence(timeout: 5))
        name.tap()
        name.typeText("Home glucose check")
        let addValue = app.textFields["records.observationAdd.value"]
        addValue.tap()
        addValue.typeText("104")
        let addUnit = app.textFields["records.observationAdd.unit"]
        addUnit.tap()
        addUnit.typeText("mg/dL")
        shot(app, "records-p3-ios-manual-08-add-value")
        app.buttons["records.observationAdd.save"].tap()
        XCTAssertTrue(app.buttons["records.observation.Home glucose check"].firstMatch.waitForExistence(timeout: 8), "manual value row")
        scrollTo(app.buttons["records.observation.Home glucose check"].firstMatch, in: app)
        shot(app, "records-p3-ios-manual-09-after-add")

        // Suggestions: accept one, dismiss the other.
        let accept = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH 'records.related.accept.'")).firstMatch
        scrollTo(accept, in: app, maxSwipes: 16)
        XCTAssertTrue(accept.waitForExistence(timeout: 5), "suggested related records")
        shot(app, "records-p3-ios-manual-10-suggestions")
        accept.tap()
        let reject = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH 'records.related.reject.'")).firstMatch
        XCTAssertTrue(reject.waitForExistence(timeout: 8))
        reject.tap()
        XCTAssertTrue(reject.waitForNonExistence(timeout: 8))
        let linked = app.descendants(matching: .any).matching(NSPredicate(format: "identifier BEGINSWITH 'records.related.linked.'")).firstMatch
        XCTAssertTrue(linked.waitForExistence(timeout: 8))
        scrollTo(linked, in: app)
        shot(app, "records-p3-ios-manual-11-accepted")

        // Timeline episode badge and doctor filter.
        app.navigationBars.buttons.firstMatch.tap()
        XCTAssertTrue(app.descendants(matching: .any)["records.row.episode"].firstMatch.waitForExistence(timeout: 8), "episode badge")
        app.swipeUp()
        RunLoop.current.run(until: Date().addingTimeInterval(1))
        shot(app, "records-p3-ios-manual-12-timeline-episode")
        app.buttons["records.filters"].tap()
        let doctor = app.buttons["records.filters.doctor"].firstMatch
        XCTAssertTrue(doctor.waitForExistence(timeout: 5))
        doctor.tap()
        RunLoop.current.run(until: Date().addingTimeInterval(1))
        shot(app, "records-p3-ios-manual-13-doctor-filter")
    }
}

extension HealthRecordsUITests {
    /// Phase 4 walk: three CBCs → detail "Ask about this report" → Coach tab with the consent sheet → Allow →
    /// "Analyzing" chip bar with the record and the prefilled prompt → "Change records" picker adds a record.
    @MainActor
    func testAskCoachAboutReportConsentChipBarAndPicker() throws {
        let files = try [("cbc_jul.pdf", "18/07/2026", "7.2"), ("cbc_aug.pdf", "10/08/2026", "8.4"), ("cbc_sep.pdf", "12/09/2026", "9.7")]
            .map { try makeCBCPDF(named: $0.0, date: $0.1, hemoglobin: $0.2).path }
        let app = XCUIApplication()
        app.launchArguments += ["-AppleLanguages", "(en)", "-hasCompletedOnboarding", "YES", "-healthRecordsViewMode", "timeline",
                                "-healthRecordsAiMode", "off", "-healthRecordsCoachAccessEnabled", "NO",
                                "-ayuvoRecordsFixture", files.joined(separator: ","), "-ayuvoRecordsReset"]
        app.launch()
        XCTAssertTrue(app.tabBars.buttons["Records"].waitForExistence(timeout: 10))
        app.tabBars.buttons["Records"].tap()
        let strip = app.descendants(matching: .any)["records.processingStrip"]
        XCTAssertTrue(app.staticTexts["September 2026"].firstMatch.waitForExistence(timeout: 60), "records land in the timeline")
        _ = strip.waitForExistence(timeout: 5)
        XCTAssertTrue(strip.waitForNonExistence(timeout: 180), "processing finishes")
        RunLoop.current.run(until: Date().addingTimeInterval(1.0))

        let title = app.staticTexts.matching(NSPredicate(format: "label BEGINSWITH 'Complete Blood Count'")).firstMatch
        XCTAssertTrue(title.waitForExistence(timeout: 20))
        title.tap()
        XCTAssertTrue(app.staticTexts["records.detail.title"].waitForExistence(timeout: 10))
        let ask = app.buttons["records.detail.askCoach"].firstMatch
        scrollTo(ask, in: app, maxSwipes: 14)
        XCTAssertTrue(ask.waitForExistence(timeout: 5), "Ask about this report")
        let compare = app.buttons["records.detail.compareCoach"].firstMatch
        XCTAssertTrue(compare.waitForExistence(timeout: 5), "Compare with previous report is offered for the Sep CBC")
        shot(app, "records-p4-ios-01-detail-ask-coach")
        ask.tap()

        // Coach tab + consent sheet.
        let allow = app.buttons["coach.recordsConsent.allow"].firstMatch
        XCTAssertTrue(allow.waitForExistence(timeout: 10), "consent sheet on first use")
        XCTAssertTrue(app.staticTexts["Let Coach read your health records?"].firstMatch.exists)
        RunLoop.current.run(until: Date().addingTimeInterval(0.8))
        shot(app, "records-p4-ios-02-consent-sheet")
        allow.tap()

        let bar = app.descendants(matching: .any)["coach.records.bar"].firstMatch
        XCTAssertTrue(bar.waitForExistence(timeout: 10), "chip bar above the input")
        let chip = app.descendants(matching: .any).matching(NSPredicate(format: "identifier BEGINSWITH 'coach.records.chip.'")).firstMatch
        XCTAssertTrue(chip.waitForExistence(timeout: 5))
        XCTAssertTrue(chip.label.contains("Complete Blood Count"), "chip names the record: \(chip.label)")
        let prompt = app.descendants(matching: .any).matching(NSPredicate(format: "value CONTAINS 'Explain this report'")).firstMatch
        XCTAssertTrue(prompt.waitForExistence(timeout: 5), "prefilled, editable prompt")
        RunLoop.current.run(until: Date().addingTimeInterval(0.8))
        shot(app, "records-p4-ios-03-coach-chip-bar")

        // Change records: pick one more.
        if app.keyboards.firstMatch.exists {
            app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.25)).tap()
        }
        let change = app.buttons["coach.records.change"].firstMatch
        XCTAssertTrue(change.waitForExistence(timeout: 5))
        change.tap()
        let candidates = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH 'coach.recordsPicker.candidate.'"))
        XCTAssertTrue(candidates.firstMatch.waitForExistence(timeout: 10), "picker lists recent records")
        RunLoop.current.run(until: Date().addingTimeInterval(0.6))
        shot(app, "records-p4-ios-04-change-records-picker")
        let selectedID = chip.identifier.replacingOccurrences(of: "coach.records.chip.", with: "")
        let other = candidates.allElementsBoundByIndex.first { $0.identifier != "coach.recordsPicker.candidate.\(selectedID)" }
        XCTAssertNotNil(other, "another record to add")
        other?.tap()
        app.buttons["coach.recordsPicker.done"].tap()
        let chips = app.descendants(matching: .any).matching(NSPredicate(format: "identifier BEGINSWITH 'coach.records.chip.'"))
        let deadline = Date().addingTimeInterval(8)
        while chips.count < 2 && Date() < deadline { RunLoop.current.run(until: Date().addingTimeInterval(0.3)) }
        XCTAssertEqual(chips.count, 2, "two records selected")
        shot(app, "records-p4-ios-05-two-records-selected")
    }
}

extension HealthRecordsUITests {
    /// Phase 5 walk: record detail → Share → "What will be shared" (items, summary fields and the
    /// six redaction toggles) → Preview builds the files and shows a page-1 preview;
    /// then Settings › Health Records › Backup & restore exports an archive.
    /// A CBC report that carries a patient name, a UHID and a phone number, so the §34 redaction
    /// classes have something to black out.
    private func makeIdentifiedCBCPDF(named name: String) throws -> URL {
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
            draw("City Diagnostics Laboratory", 60, 40, bold)
            draw("Complete Blood Count", 60, 72, bold)
            draw("Patient Name: Asha Rao", 60, 110, body)
            draw("UHID: AB123456", 60, 130, body)
            draw("Mobile: +91 98765 43210", 60, 150, body)
            draw("Ref. by: Dr. Suresh Menon", 60, 170, body)
            draw("Collected: 12/09/2026", 60, 190, body)
            draw("Reported: 12/09/2026", 300, 190, body)
            draw("Test", 60, 230, bold)
            draw("Result", 250, 230, bold)
            draw("Units", 340, 230, bold)
            draw("Reference Range", 440, 230, bold)
            let rows = [("Hemoglobin", "7.6", "g/dL", "13.0 - 17.0"), ("Total WBC Count", "6200", "cells/cumm", "4000 - 11000"),
                        ("Platelet Count", "250", "10^3/µL", "150 - 410")]
            for (index, row) in rows.enumerated() {
                let y = 260 + CGFloat(index) * 24
                draw(row.0, 60, y, body)
                draw(row.1, 250, y, body)
                draw(row.2, 340, y, body)
                draw(row.3, 440, y, body)
            }
        }
        try data.write(to: url)
        return url
    }

    @MainActor
    func testShareScreenRedactionTogglesAndArchiveExport() throws {
        let pdf = try makeIdentifiedCBCPDF(named: "cbc_sep.pdf")
        let app = XCUIApplication()
        app.launchArguments += ["-AppleLanguages", "(en)", "-hasCompletedOnboarding", "YES", "-healthRecordsViewMode", "timeline",
                                "-healthRecordsAiMode", "off",
                                "-ayuvoRecordsFixture", pdf.path, "-ayuvoRecordsReset"]
        app.launch()
        XCTAssertTrue(app.tabBars.buttons["Records"].waitForExistence(timeout: 10))
        app.tabBars.buttons["Records"].tap()
        XCTAssertTrue(app.navigationBars["Records"].waitForExistence(timeout: 8))
        let strip = app.descendants(matching: .any)["records.processingStrip"]
        _ = strip.waitForExistence(timeout: 5)
        _ = strip.waitForNonExistence(timeout: 180)

        let title = app.staticTexts.matching(NSPredicate(format: "label BEGINSWITH 'Complete Blood Count' OR label BEGINSWITH 'Cbc Sep'")).firstMatch
        XCTAssertTrue(title.waitForExistence(timeout: 30), "the imported record reaches the timeline")
        title.tap()
        XCTAssertTrue(app.staticTexts["records.detail.title"].waitForExistence(timeout: 10))

        let share = app.buttons["records.detail.share"].firstMatch
        scrollTo(share, in: app, maxSwipes: 14)
        XCTAssertTrue(share.waitForExistence(timeout: 5), "detail offers Share")
        share.tap()

        // "What will be shared": the checklist of §34.
        XCTAssertTrue(app.navigationBars["What will be shared"].waitForExistence(timeout: 10))
        XCTAssertTrue(app.switches["records.share.includeOriginal"].waitForExistence(timeout: 5), "Original document")
        RunLoop.current.run(until: Date().addingTimeInterval(0.6))
        shot(app, "records-p5-ios-01-share-plan")
        // The list is lazy: walk down it in order, checking each toggle as it renders.
        let summaryToggle = app.switches["records.share.includeSummary"]
        scrollTo(summaryToggle, in: app, maxSwipes: 8)
        XCTAssertTrue(summaryToggle.exists, "Structured summary")
        for field in ["doctor", "facility", "patient_name", "dates", "test_results", "medications", "diagnoses", "recommendations"] {
            let toggle = app.switches["records.share.field.\(field)"]
            scrollTo(toggle, in: app, maxSwipes: 8)
            XCTAssertTrue(toggle.exists, "summary field \(field)")
        }
        for redaction in ["name", "address", "phone", "patient_id", "insurance_id", "other_ids"] {
            let toggle = app.switches["records.share.redaction.\(redaction)"]
            scrollTo(toggle, in: app, maxSwipes: 10)
            XCTAssertTrue(toggle.exists, "redaction toggle \(redaction)")
        }

        // Turn on the identifier redactions and build the files.
        for redaction in ["name", "phone", "patient_id"] {
            let toggle = app.switches["records.share.redaction.\(redaction)"]
            scrollTo(toggle, in: app, maxSwipes: 8)
            if (toggle.value as? String) != "1" {
                // Tap the switch itself: the row's centre lands on the label.
                toggle.coordinate(withNormalizedOffset: CGVector(dx: 0.92, dy: 0.5)).tap()
            }
            XCTAssertEqual(toggle.value as? String, "1", "redaction \(redaction) is on")
        }
        shot(app, "records-p5-ios-02-share-redactions")
        let preview = app.buttons["records.share.preview"]
        XCTAssertTrue(preview.waitForExistence(timeout: 5))
        preview.tap()

        XCTAssertTrue(app.navigationBars["Ready to share"].waitForExistence(timeout: 60), "the files are built")
        let confirm = app.buttons["records.share.confirm"]
        XCTAssertTrue(confirm.waitForExistence(timeout: 20), "final confirm names the file count")
        XCTAssertTrue(app.images["records.share.previewImage"].firstMatch.waitForExistence(timeout: 20), "page-1 preview of each produced file")
        XCTAssertTrue(
            app.staticTexts.matching(NSPredicate(format: "label CONTAINS '(redacted)'")).firstMatch.waitForExistence(timeout: 20),
            "the original is shared as the redacted copy"
        )
        XCTAssertTrue(
            app.staticTexts.matching(NSPredicate(format: "label CONTAINS '-redacted.pdf'")).firstMatch.exists,
            "the redacted PDF is the file that goes to the share sheet"
        )
        RunLoop.current.run(until: Date().addingTimeInterval(0.8))
        shot(app, "records-p5-ios-03-share-preview")
        app.buttons["Cancel"].firstMatch.tap()
        _ = app.navigationBars["What will be shared"].waitForExistence(timeout: 5)
        app.buttons["Cancel"].firstMatch.tap()

        // Settings › Health Records › Backup & restore → Create archive.
        app.tabBars.buttons["Settings"].tap()
        let category = app.buttons["settings.category.healthRecords"]
        for _ in 0..<8 where !category.isHittable { app.swipeUp() }
        XCTAssertTrue(category.waitForExistence(timeout: 5))
        category.tap()
        XCTAssertTrue(app.navigationBars["Health Records"].waitForExistence(timeout: 5))
        let storage = app.buttons["records.settings.storage"]
        scrollTo(storage, in: app, maxSwipes: 14)
        XCTAssertTrue(storage.waitForExistence(timeout: 5), "Storage screen link")
        storage.tap()
        XCTAssertTrue(app.navigationBars["Storage"].waitForExistence(timeout: 8))
        XCTAssertTrue(app.staticTexts["records.storage.total"].waitForExistence(timeout: 20), "sizes finish computing")
        for action in ["clearCache", "rebuildThumbnails", "rebuildIndex", "findDuplicates", "reprocessAll"] {
            XCTAssertTrue(app.buttons["records.storage.\(action)"].exists, "storage action \(action)")
        }
        RunLoop.current.run(until: Date().addingTimeInterval(0.5))
        shot(app, "records-p5-ios-04-storage")
        app.navigationBars.buttons.firstMatch.tap()

        let backup = app.buttons["records.settings.backup"]
        scrollTo(backup, in: app, maxSwipes: 14)
        XCTAssertTrue(backup.waitForExistence(timeout: 5), "Backup & restore link")
        backup.tap()
        XCTAssertTrue(app.navigationBars["Backup & restore"].waitForExistence(timeout: 8))
        XCTAssertTrue(app.staticTexts["No archive created yet."].firstMatch.waitForExistence(timeout: 8))
        shot(app, "records-p5-ios-05-backup")
        app.buttons["records.backup.export"].tap()

        // The system share sheet appears over the archive; dismiss it and check the status row.
        let activity = app.otherElements["ActivityListView"].firstMatch
        if activity.waitForExistence(timeout: 60) {
            shot(app, "records-p5-ios-06-archive-share-sheet")
            if app.buttons["Close"].firstMatch.exists {
                app.buttons["Close"].firstMatch.tap()
            } else {
                app.swipeDown()
            }
        }
        let lastArchive = app.staticTexts["Last archive"].firstMatch
        XCTAssertTrue(lastArchive.waitForExistence(timeout: 60), "the export produced an archive and stamped records_backup_state")
        XCTAssertTrue(app.staticTexts["Archive size"].firstMatch.waitForExistence(timeout: 10))
        shot(app, "records-p5-ios-07-archive-done")
    }
}

extension HealthRecordsUITests {
    /// Phase 5 backup walk: two CBCs → archive → Delete All Data (real Settings flow) →
    /// restore the archive → the records, their values and their links are back.
    @MainActor
    func testArchiveExportDeleteAllDataAndRestore() throws {
        let files = try [("cbc_jul.pdf", "18/07/2026", "7.2"), ("cbc_sep.pdf", "12/09/2026", "9.7")]
            .map { try makeCBCPDF(named: $0.0, date: $0.1, hemoglobin: $0.2).path }
        let archive = FileManager.default.temporaryDirectory
            .appendingPathComponent("ayuvo-p5-\(UUID().uuidString)", isDirectory: true)
            .appendingPathComponent("ayuvo-records.zip")

        let app = XCUIApplication()
        app.launchArguments += ["-AppleLanguages", "(en)", "-hasCompletedOnboarding", "YES", "-healthRecordsViewMode", "timeline",
                                "-healthRecordsAiMode", "off",
                                "-ayuvoRecordsFixture", files.joined(separator: ","), "-ayuvoRecordsReset",
                                "-ayuvoRecordsArchiveOut", archive.path]
        app.launch()
        app.tabBars.buttons["Records"].tap()
        let strip = app.descendants(matching: .any)["records.processingStrip"]
        XCTAssertTrue(app.staticTexts["September 2026"].firstMatch.waitForExistence(timeout: 60))
        _ = strip.waitForExistence(timeout: 5)
        XCTAssertTrue(strip.waitForNonExistence(timeout: 180), "processing finishes")
        RunLoop.current.run(until: Date().addingTimeInterval(1.0))
        shot(app, "records-p5-ios-10-before-backup")

        // Background the app: the DEBUG hook writes the §35 archive to a host path.
        XCUIDevice.shared.press(.home)
        let deadline = Date().addingTimeInterval(60)
        while !FileManager.default.fileExists(atPath: archive.path), Date() < deadline {
            RunLoop.current.run(until: Date().addingTimeInterval(0.5))
        }
        XCTAssertTrue(FileManager.default.fileExists(atPath: archive.path), "the export produced an archive file")
        let size = ((try? FileManager.default.attributesOfItem(atPath: archive.path))?[.size] as? NSNumber)?.intValue ?? 0
        XCTAssertGreaterThan(size, 500, "the archive holds the records and their originals")

        // Delete All Data through Settings, then restore the archive on the next launch.
        app.activate()
        app.tabBars.buttons["Settings"].tap()
        let dataCategory = app.buttons["settings.category.deleteData"]
        for _ in 0..<10 where !dataCategory.isHittable { app.swipeUp() }
        if dataCategory.waitForExistence(timeout: 5) { dataCategory.tap() }
        XCTAssertTrue(app.navigationBars["Delete All Data"].waitForExistence(timeout: 5), "Delete All Data pane opens")
        let deleteAll = app.collectionViews.buttons.matching(NSPredicate(format: "label CONTAINS 'Delete All Data'")).firstMatch
        for _ in 0..<12 where !deleteAll.isHittable { app.swipeUp() }
        XCTAssertTrue(deleteAll.waitForExistence(timeout: 8), "Delete All Data is in Settings › Data & Privacy › Delete All Data")
        deleteAll.tap()
        let confirm = app.buttons["Delete Everything"].firstMatch
        XCTAssertTrue(confirm.waitForExistence(timeout: 5), "Delete All Data confirms first")
        confirm.tap()
        RunLoop.current.run(until: Date().addingTimeInterval(2.0))
        app.tabBars.buttons["Records"].tap()
        XCTAssertTrue(app.staticTexts.matching(NSPredicate(format: "label BEGINSWITH 'Complete Blood Count'")).firstMatch
            .waitForNonExistence(timeout: 20), "Delete All Data wiped Health Records")
        shot(app, "records-p5-ios-11-after-delete-all")
        app.terminate()

        // Restore.
        let restored = XCUIApplication()
        restored.launchArguments += ["-AppleLanguages", "(en)", "-hasCompletedOnboarding", "YES",
                                     "-healthRecordsViewMode", "timeline", "-healthRecordsAiMode", "off",
                                     "-ayuvoRecordsArchive", archive.path, "-ayuvoRecordsArchiveMode", "merge"]
        restored.launch()
        restored.tabBars.buttons["Records"].tap()
        let title = restored.staticTexts.matching(NSPredicate(format: "label BEGINSWITH 'Complete Blood Count'")).firstMatch
        XCTAssertTrue(title.waitForExistence(timeout: 60), "the archive restored the records")
        XCTAssertTrue(restored.staticTexts["September 2026"].firstMatch.waitForExistence(timeout: 20))
        XCTAssertTrue(restored.staticTexts["July 2026"].firstMatch.waitForExistence(timeout: 20))
        shot(restored, "records-p5-ios-12-restored-timeline")

        title.tap()
        XCTAssertTrue(restored.staticTexts["records.detail.title"].waitForExistence(timeout: 10))
        let observation = restored.descendants(matching: .any).matching(NSPredicate(format: "identifier BEGINSWITH 'records.observation.'")).firstMatch
        scrollTo(observation, in: restored, maxSwipes: 14)
        XCTAssertTrue(observation.waitForExistence(timeout: 10), "health data points came back")
        // A restored suggestion renders under "Suggested" (accept/reject), an accepted one as "Linked".
        let related = restored.descendants(matching: .any)
            .matching(NSPredicate(format: "identifier BEGINSWITH 'records.related.'")).firstMatch
        scrollTo(related, in: restored, maxSwipes: 16)
        XCTAssertTrue(related.waitForExistence(timeout: 10), "related records came back")
        shot(restored, "records-p5-ios-13-restored-detail")

        // Storage numbers after the restore.
        restored.tabBars.buttons["Settings"].tap()
        let category = restored.buttons["settings.category.healthRecords"]
        for _ in 0..<8 where !category.isHittable { restored.swipeUp() }
        XCTAssertTrue(category.waitForExistence(timeout: 5))
        category.tap()
        let storage = restored.buttons["records.settings.storage"]
        scrollTo(storage, in: restored, maxSwipes: 14)
        storage.tap()
        XCTAssertTrue(restored.staticTexts["records.storage.total"].waitForExistence(timeout: 30))
        shot(restored, "records-p5-ios-14-storage-after-restore")
        restored.buttons["records.storage.rebuildIndex"].tap()
        let run = restored.buttons["Rebuild search index"].firstMatch
        if run.waitForExistence(timeout: 5) { run.tap() }
        let done = restored.buttons["OK"].firstMatch
        XCTAssertTrue(done.waitForExistence(timeout: 30), "a storage action finishes with a confirmation")
        done.tap()
        shot(restored, "records-p5-ios-15-storage-action")
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
