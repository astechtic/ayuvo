import XCTest

/// Camera measurements end to end on the replay source (`-AyuvoVitalsReplay finger|face`, synthetic frames in real
/// time; the simulator has no camera): Summary "+" › Measure › intro › live › results › Save › Camera measurements.
/// Set `AYUVO_SHOTS_DIR` (xcodebuild: `TEST_RUNNER_AYUVO_SHOTS_DIR`) to also write the screenshots as PNG files.
final class VitalsReplayUITests: XCTestCase {
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

    private func launch(_ replay: String, extra: [String] = []) -> XCUIApplication {
        let app = XCUIApplication()
        app.launchArguments += ["-AppleLanguages", "(en)", "-hasCompletedOnboarding", "YES", "-healthKitEnabled", "NO",
                                "-notificationsEnabled", "NO", "-AyuvoVitalsReplay", replay] + extra
        app.launch()
        return app
    }

    private func runScan(_ app: XCUIApplication, mode: String) {
        let add = app.buttons["summary.add"].firstMatch
        XCTAssertTrue(add.waitForExistence(timeout: 15), "Summary + menu")
        add.tap()
        let entry = app.buttons["log.entry.\(mode)Scan"].firstMatch
        XCTAssertTrue(entry.waitForExistence(timeout: 5), "Measure section entry")
        shot(app, "\(mode)-01-summary-menu-measure")
        entry.tap()

        let start = app.buttons["vitals.start"].firstMatch
        XCTAssertTrue(start.waitForExistence(timeout: 8), "Scan intro")
        shot(app, "\(mode)-02-intro")
        start.tap()

        let guidance = app.staticTexts["vitals.guidance"].firstMatch
        XCTAssertTrue(guidance.waitForExistence(timeout: 8), "Live screen")
        // Let the clock run so the live waveform and timer show.
        sleep(15)
        shot(app, "\(mode)-03-live")

        let save = app.buttons["vitals.save"].firstMatch
        XCTAssertTrue(save.waitForExistence(timeout: 150), "Results after the scan")
        sleep(1)
        shot(app, "\(mode)-04-results")
        XCTAssertTrue(app.staticTexts["vitals.quality"].firstMatch.exists)
        app.swipeUp()
        sleep(1)
        shot(app, "\(mode)-05-results-scrolled")
        save.tap()
        // Saved: the results stay for "Add reference reading"; Done closes the flow.
        let done = app.buttons["vitals.done"].firstMatch
        XCTAssertTrue(done.waitForExistence(timeout: 10), "Done after saving")
        done.tap()
        sleep(2)
        shot(app, "\(mode)-05b-after-done")

        // A saved scan from the Summary menu lands on Browse › Camera measurements.
        XCTAssertTrue(app.navigationBars["Camera measurements"].waitForExistence(timeout: 10), "Vitals home after saving")
        XCTAssertTrue(app.buttons["vitals.latest.\(mode)"].firstMatch.waitForExistence(timeout: 10), "Latest \(mode) scan")
        sleep(1)
        shot(app, "\(mode)-06-vitals-home")
    }

    @MainActor
    func testFingerScanReplayToHome() throws {
        let app = launch("finger")
        runScan(app, mode: "finger")
        // Scan detail.
        app.buttons["vitals.latest.finger"].firstMatch.tap()
        XCTAssertTrue(app.buttons["vitals.detail.delete"].firstMatch.waitForExistence(timeout: 10) || app.navigationBars["Scan"].exists)
        sleep(1)
        shot(app, "finger-07-scan-detail")
    }

    /// Summary "+" › Compare finger & face: finger scan → save → reference reading → Next: face scan → save →
    /// Compare, then Validation and Calibration from Camera measurements.
    @MainActor
    func testCompareFlowReplay() throws {
        let app = launch("finger", extra: ["-vitalsExperimentalEnabled", "YES", "-vitalsResearchEnabled", "YES"])
        let add = app.buttons["summary.add"].firstMatch
        XCTAssertTrue(add.waitForExistence(timeout: 15), "Summary + menu")
        add.tap()
        let entry = app.buttons["log.entry.compareScan"].firstMatch
        XCTAssertTrue(entry.waitForExistence(timeout: 5), "Compare finger & face entry")
        shot(app, "compare-01-summary-menu")
        entry.tap()

        // Finger scan.
        XCTAssertTrue(app.buttons["vitals.start"].firstMatch.waitForExistence(timeout: 8), "Finger intro")
        app.buttons["vitals.start"].firstMatch.tap()
        XCTAssertTrue(app.buttons["vitals.save"].firstMatch.waitForExistence(timeout: 150), "Finger results")
        app.buttons["vitals.save"].firstMatch.tap()
        let next = app.buttons["vitals.next"].firstMatch
        XCTAssertTrue(next.waitForExistence(timeout: 10), "Next: face scan")
        XCTAssertEqual(next.label, "Next: face scan")
        shot(app, "compare-02-finger-saved")

        // Reference reading sheet on the saved finger scan.
        app.buttons["vitals.reference"].firstMatch.tap()
        let hr = app.textFields["vitals.reference.heartRate"].firstMatch
        XCTAssertTrue(hr.waitForExistence(timeout: 8), "Reference sheet")
        hr.tap()
        hr.typeText("71")
        let device = app.textFields["vitals.reference.device"].firstMatch
        if device.exists {
            device.tap()
            device.typeText("Polar H10")
        }
        sleep(1)
        shot(app, "compare-03-reference-sheet")
        app.buttons["vitals.reference.save"].firstMatch.tap()
        XCTAssertTrue(next.waitForExistence(timeout: 10), "Back on the results")
        next.tap()

        // Face scan, linked to the same session.
        XCTAssertTrue(app.buttons["vitals.start"].firstMatch.waitForExistence(timeout: 8), "Face intro")
        app.buttons["vitals.start"].firstMatch.tap()
        XCTAssertTrue(app.buttons["vitals.save"].firstMatch.waitForExistence(timeout: 150), "Face results")
        app.buttons["vitals.save"].firstMatch.tap()
        XCTAssertTrue(next.waitForExistence(timeout: 10), "Compare button")
        XCTAssertEqual(next.label, "Compare")
        XCTAssertFalse(app.staticTexts["vitals.unlinkedNotice"].exists)
        next.tap()

        // Compare screen.
        XCTAssertTrue(app.staticTexts["vitals.compare.status"].firstMatch.waitForExistence(timeout: 10)
                      || app.otherElements["vitals.compare.status"].firstMatch.waitForExistence(timeout: 2), "Compare status")
        sleep(1)
        shot(app, "compare-04-compare-screen")
        app.swipeUp()
        sleep(1)
        shot(app, "compare-05-compare-screen-scrolled")
        app.buttons["vitals.compare.done"].firstMatch.tap()

        // Camera measurements › Validation and Calibration.
        XCTAssertTrue(app.navigationBars["Camera measurements"].waitForExistence(timeout: 10), "Vitals home")
        let validation = app.buttons["vitals.validation"].firstMatch
        XCTAssertTrue(validation.waitForExistence(timeout: 5), "Validation link")
        validation.tap()
        XCTAssertTrue(app.navigationBars["Validation"].waitForExistence(timeout: 8), "Validation screen")
        sleep(1)
        shot(app, "compare-06-validation")
        app.navigationBars.buttons.element(boundBy: 0).tap()
        let calibration = app.buttons["vitals.calibration"].firstMatch
        XCTAssertTrue(calibration.waitForExistence(timeout: 5), "Calibration link (experimental on)")
        calibration.tap()
        XCTAssertTrue(app.navigationBars["Calibration"].waitForExistence(timeout: 8), "Calibration screen")
        sleep(1)
        shot(app, "compare-07-calibration")
        app.navigationBars.buttons.element(boundBy: 0).tap()

        // The finger scan's detail links back to the comparison.
        XCTAssertTrue(app.buttons["vitals.latest.finger"].firstMatch.waitForExistence(timeout: 5))
        app.buttons["vitals.latest.finger"].firstMatch.tap()
        XCTAssertTrue(app.buttons["vitals.detail.compare"].firstMatch.waitForExistence(timeout: 8), "Compare link on scan detail")
        shot(app, "compare-08-scan-detail")
    }

    @MainActor
    func testFaceScanReplayToHome() throws {
        let app = launch("face")
        runScan(app, mode: "face")
        app.swipeUp()
        sleep(1)
        shot(app, "face-07-vitals-home-scrolled")
    }
}
