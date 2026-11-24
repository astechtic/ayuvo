import XCTest

/// App Review demo walkthrough, recorded with `xcrun simctl io <udid> recordVideo` while it runs.
///
///   1. `testA_restoreDemoData` (not recorded): Welcome › Restore from a backup with the Export All Data zip in
///      `TEST_RUNNER_AYUVO_RESTORE_ZIP` (DEBUG `-ayuvoImportAllFile` hook), then marks onboarding complete.
///   2. `testB_walkthrough` (recorded): every main feature at a human pace — pauses, slow scrolls, one thing at a time.
///
/// Not part of CI: both tests skip unless `TEST_RUNNER_AYUVO_DEMO=1`.
final class AppReviewDemoUITests: XCTestCase {
    override func setUpWithError() throws {
        continueAfterFailure = true
        try XCTSkipUnless(ProcessInfo.processInfo.environment["AYUVO_DEMO"] == "1", "demo recording only")
    }

    // MARK: - Helpers

    private func pause(_ seconds: Double = 1.6) { Thread.sleep(forTimeInterval: seconds) }

    /// First hittable element whose label or identifier matches, among buttons, cells, static texts.
    private func element(_ app: XCUIApplication, _ label: String, contains: Bool = false) -> XCUIElement? {
        let pred = contains ? NSPredicate(format: "label CONTAINS[c] %@ OR identifier == %@", label, label)
                            : NSPredicate(format: "label == %@ OR identifier == %@", label, label)
        for q in [app.buttons, app.cells, app.staticTexts, app.otherElements, app.images] {
            let m = q.matching(pred)
            for i in 0..<min(m.count, 6) where m.element(boundBy: i).isHittable {
                return m.element(boundBy: i)
            }
        }
        return nil
    }

    @discardableResult
    private func tap(_ app: XCUIApplication, _ label: String, contains: Bool = false, wait: Double = 4, after: Double = 1.8) -> Bool {
        let deadline = Date().addingTimeInterval(wait)
        while Date() < deadline {
            if let e = element(app, label, contains: contains) {
                e.tap()
                pause(after)
                return true
            }
            RunLoop.current.run(until: Date().addingTimeInterval(0.3))
        }
        print("DEMO: not found: \(label)")
        return false
    }

    /// Scrolls until the label is hittable, then taps it.
    @discardableResult
    private func scrollTap(_ app: XCUIApplication, _ label: String, contains: Bool = false, maxSwipes: Int = 6, after: Double = 1.8) -> Bool {
        for _ in 0...maxSwipes {
            if let e = element(app, label, contains: contains) { e.tap(); pause(after); return true }
            slowScroll(app, up: true, distance: 0.35)
        }
        print("DEMO: not found after scrolling: \(label)")
        return false
    }

    /// A finger-like drag instead of a fling.
    private func slowScroll(_ app: XCUIApplication, up: Bool = true, distance: CGFloat = 0.4) {
        let win = app.windows.firstMatch
        let from = win.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: up ? 0.72 : 0.3))
        let to = win.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: up ? 0.72 - distance : 0.3 + distance))
        from.press(forDuration: 0.05, thenDragTo: to, withVelocity: 600, thenHoldForDuration: 0.15)
        pause(1.0)
    }

    private func back(_ app: XCUIApplication, after: Double = 1.4) {
        let b = app.navigationBars.buttons.element(boundBy: 0)
        if b.exists, b.isHittable { b.tap() } else {
            let win = app.windows.firstMatch
            win.coordinate(withNormalizedOffset: CGVector(dx: 0.02, dy: 0.4))
                .press(forDuration: 0.05, thenDragTo: win.coordinate(withNormalizedOffset: CGVector(dx: 0.8, dy: 0.4)))
        }
        pause(after)
    }

    private func dismissSheet(_ app: XCUIApplication) {
        for label in ["Done", "Close", "Cancel", "Not Now", "Later"] where element(app, label) != nil {
            tap(app, label, after: 1.2); return
        }
        let win = app.windows.firstMatch
        win.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.15))
            .press(forDuration: 0.05, thenDragTo: win.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.95)))
        pause(1.2)
    }

    private func tab(_ app: XCUIApplication, _ name: String) {
        for _ in 0..<3 {
            let b = app.tabBars.buttons[name]
            if b.waitForExistence(timeout: 4) { b.tap() } else { tap(app, name, after: 0.5) }
            pause(1.5)
            if app.navigationBars[name].exists || app.staticTexts[name].firstMatch.exists && (app.tabBars.buttons[name].isSelected) { break }
        }
        pause(1.0)
    }

    private func launch(_ extra: [String] = []) -> XCUIApplication {
        let app = XCUIApplication()
        app.launchArguments += ["-AppleLanguages", "(en)", "-AppleLocale", "en_US", "-appearanceMode", "dark",
                                "-hasCompletedOnboarding", "YES", "-AyuvoVitalsReplay", "finger"] + extra
        app.launch()
        return app
    }

    // MARK: - 1. Restore demo data

    @MainActor
    func testA_restoreDemoData() throws {
        let zip = try XCTUnwrap(ProcessInfo.processInfo.environment["AYUVO_RESTORE_ZIP"])
        let app = XCUIApplication()
        app.launchArguments += ["-AppleLanguages", "(en)", "-hasCompletedOnboarding", "NO", "--reset-onboarding", "-ayuvoImportAllFile", zip]
        app.launch()
        let restore = app.buttons["onboarding.restoreBackup"]
        XCTAssertTrue(restore.waitForExistence(timeout: 20))
        restore.tap()
        let importButton = app.buttons["settings.importAll.import"]
        _ = app.staticTexts["In this file"].waitForExistence(timeout: 45)
        for _ in 0..<12 where !(importButton.exists && importButton.isHittable) { app.swipeUp(); sleep(1) }
        if !importButton.waitForExistence(timeout: 5) {
            let a = XCTAttachment(screenshot: app.screenshot()); a.name = "after restore tap"; a.lifetime = .keepAlways; add(a)
            print("DEMO TREE:", app.debugDescription.split(separator: "\n").filter { $0.contains("label") || $0.contains("identifier") }.suffix(40).joined(separator: "\n"))
        }
        importButton.tap()
        XCTAssertTrue(app.buttons["Done"].waitForExistence(timeout: 60))
        app.buttons["Done"].tap()
        sleep(4)
        app.terminate()
    }

    // MARK: - 2. Walkthrough (recorded)

    @discardableResult
    private func tapID(_ app: XCUIApplication, _ id: String, wait: Double = 5, after: Double = 1.8, scroll: Bool = false) -> Bool {
        let e = app.descendants(matching: .any).matching(identifier: id).firstMatch
        let attempts = scroll ? 9 : Int(wait / 0.3)
        for _ in 0..<max(attempts, 1) {
            if e.exists && e.isHittable {
                e.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
                pause(after)
                return true
            }
            if scroll { slowScroll(app, distance: 0.3) } else { RunLoop.current.run(until: Date().addingTimeInterval(0.3)) }
        }
        print("DEMO: id not found: \(id)")
        return false
    }

    private func healthPermissionSheet(_ app: XCUIApplication) {
        let turnOnAll = app.descendants(matching: .any).matching(NSPredicate(format: "label == 'Turn On All'")).firstMatch
        guard turnOnAll.waitForExistence(timeout: 8) else { print("DEMO: no Health sheet"); return }
        pause(2.0)
        turnOnAll.tap(); pause(1.8)
        let allow = app.buttons["Allow"].firstMatch
        if allow.waitForExistence(timeout: 5) { allow.tap() }
        pause(3.0)
    }

    @MainActor
    func testB_walkthrough() throws {
        let app = launch(["-healthKitEnabled", "NO"])
        print("DEMO-START")
        pause(3.5)

        // 1. Summary: rings, today cards, favourites.
        pause(2.0)
        slowScroll(app, distance: 0.35); pause(1.6)
        slowScroll(app, distance: 0.35); pause(1.6)
        slowScroll(app, up: false, distance: 0.8); pause(1.2)

        // 2. The "+" log menu: every way to add data.
        if tapID(app, "summary.add", after: 2.5) {
            pause(2.0)
            // Close the menu with a tap outside it (top-left, over the large title).
            let win = app.windows.firstMatch
            for _ in 0..<3 where app.buttons["log.entry.workout"].exists || app.staticTexts["Workout"].isHittable {
                win.coordinate(withNormalizedOffset: CGVector(dx: 0.08, dy: 0.09)).tap()
                pause(1.2)
            }
            pause(1.0)
        }

        // 3. Browse: connect Apple Health with the consent toggle visible, then the categories.
        tab(app, "Browse")
        pause(2.0)
        if tap(app, "Grant access", contains: true, after: 1.5) {
            healthPermissionSheet(app)
        }
        pause(1.5)
        if tapID(app, "browse.row.nutrition", scroll: true) {
            pause(2.0); slowScroll(app, distance: 0.4); pause(1.8); slowScroll(app, up: false, distance: 0.4); back(app)
        }
        if tapID(app, "browse.row.activity", scroll: true) {
            pause(1.5)
            if tapID(app, "browse.link.workouts") { pause(2.0); slowScroll(app, distance: 0.4); pause(1.5); back(app) }
            if tapID(app, "browse.link.exerciseLibrary") {
                pause(2.0); slowScroll(app, distance: 0.45); pause(1.4)
                let cell = app.cells.element(boundBy: 1)
                if cell.exists, cell.isHittable { cell.tap(); pause(3.5); back(app) }
                back(app)
            }
            back(app)
        }
        if tapID(app, "browse.row.heart", scroll: true) {
            pause(1.5)
            if tap(app, "Heart Rate", contains: true) { pause(1.5); tap(app, "M", after: 2.5); back(app) }
            back(app)
        }
        if tapID(app, "browse.row.sleep", scroll: true) {
            pause(1.5)
            if tap(app, "Sleep", contains: false, after: 1.5) { tap(app, "W", after: 2.5); back(app) }
            back(app)
        }
        if tapID(app, "browse.row.medications", scroll: true) {
            pause(2.5); slowScroll(app, distance: 0.35); pause(1.6); back(app)
        }
        if tapID(app, "browse.row.cameraMeasurements", scroll: true) {
            pause(2.0)
            if tap(app, "Finger scan", contains: true, after: 2.0) {
                tap(app, "Start", contains: true, wait: 6, after: 2.0)
                pause(30)
                dismissSheet(app)
            }
            back(app)
        }
        if tapID(app, "browse.row.cycleTracking", scroll: true) {
            pause(2.5); slowScroll(app, distance: 0.35); pause(1.6); back(app)
        }
        slowScroll(app, up: false, distance: 0.9)
        if tapID(app, "browse.row.insights", scroll: true) { pause(3.0); back(app) }

        // 4. Records: choose how AI may help (Skip AI here), then one record's extracted values.
        tab(app, "Records")
        pause(2.0)
        if tapID(app, "records.aiMode.off", wait: 3, after: 1.2) { tapID(app, "records.aiChooser.save", after: 2.0) }
        if tap(app, "Complete Blood Count", contains: true, after: 2.5) {
            slowScroll(app, distance: 0.4); pause(1.8)
            slowScroll(app, distance: 0.4); pause(1.8)
            back(app)
        }

        // 5. Coach: prompt gallery, sources and consent.
        tab(app, "Coach")
        pause(3.0)

        // 6. Settings: health consent, AI providers (no key on this device), partner sync, export, delete, legal.
        tab(app, "Settings")
        pause(2.0)
        for pane in ["healthData", "aiProviders", "partnerHealth", "dataManagement", "deleteData", "legal"] {
            if tapID(app, "settings.category.\(pane)", scroll: true) {
                pause(2.5); slowScroll(app, distance: 0.3); pause(1.2); back(app)
            }
        }

        tab(app, "Summary")
        pause(3.0)
        print("DEMO-END")
        pause(4.0)
    }

    // MARK: - 3. Store screenshots (iPad or iPhone), written to TEST_RUNNER_AYUVO_SHOTS_DIR

    private func storeShot(_ name: String) {
        pause(2.0)
        guard let dir = ProcessInfo.processInfo.environment["AYUVO_SHOTS_DIR"] else { return }
        try? FileManager.default.createDirectory(atPath: dir, withIntermediateDirectories: true)
        let png = XCUIScreen.main.screenshot().pngRepresentation
        try? png.write(to: URL(fileURLWithPath: dir).appendingPathComponent("\(name).png"))
        print("DEMO: shot \(name)")
    }

    @MainActor
    func testC_storeScreens() throws {
        let app = launch(["-healthKitEnabled", "NO"])
        pause(4.0)
        tab(app, "Summary")
        storeShot("summary")

        tab(app, "Browse")
        if tapID(app, "browse.row.nutrition", scroll: true) { storeShot("nutrition"); back(app) }
        if tapID(app, "browse.row.activity", scroll: true) {
            if tapID(app, "browse.link.workouts") { storeShot("workouts"); back(app) }
            back(app)
        }
        if tapID(app, "browse.row.heart", scroll: true) {
            if tap(app, "Heart Rate", contains: true) { tap(app, "M", after: 2.5); storeShot("heart-rate"); back(app) }
            back(app)
        }
        if tapID(app, "browse.row.medications", scroll: true) { storeShot("medications"); back(app) }

        tab(app, "Records")
        if tapID(app, "records.aiMode.off", wait: 3, after: 1.0) { tapID(app, "records.aiChooser.save", after: 2.0) }
        storeShot("records")
        if tap(app, "Complete Blood Count", contains: true, after: 2.5) {
            for _ in 0..<2 { slowScroll(app, distance: 0.35) }
            storeShot("record-extraction")
            back(app)
        }

        tab(app, "Coach")
        storeShot("coach")

        tab(app, "Settings")
        if tapID(app, "settings.category.dataManagement", scroll: true) {
            if tapID(app, "settings.row.exportAllData", wait: 4) { storeShot("export-data"); dismissSheet(app) }
            back(app)
        }
    }
}
