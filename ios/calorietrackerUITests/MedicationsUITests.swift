import XCTest

/// Medications walk: Browse › Medications with a fixture archive (imported through the
/// DEBUG `-ayuvoMedicationsFixture` hook), Take on a due dose, the detail screen, and the add flow.
///
/// Set `TEST_RUNNER_AYUVO_SMOKE_SHOTS_DIR=<absolute dir>` to also write PNGs there.
final class MedicationsUITests: XCTestCase {
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

    /// An `ayuvo-medications` v1 archive with one daily medicine whose next dose is a few minutes
    /// away (status "scheduled", so the row offers Take) and one as-needed medicine.
    private func makeFixtureArchive() throws -> URL {
        let now = Date()
        let calendar = Calendar.current
        let nowMs = Int64(now.timeIntervalSince1970 * 1000)
        func hhmm(_ date: Date) -> String {
            let c = calendar.dateComponents([.hour, .minute], from: date)
            return String(format: "%02d:%02d", c.hour ?? 0, c.minute ?? 0)
        }
        func iso(_ date: Date) -> String {
            let c = calendar.dateComponents([.year, .month, .day], from: date)
            return String(format: "%04d-%02d-%02d", c.year ?? 1970, c.month ?? 1, c.day ?? 1)
        }
        // Two slots today: one 20 minutes ahead (scheduled) and one 5 minutes ahead; both stay in
        // the future for the whole test run, and the row shows "Take".
        var times = [hhmm(now.addingTimeInterval(5 * 60)), hhmm(now.addingTimeInterval(20 * 60))]
        times = Array(Set(times)).sorted()
        let yesterday = calendar.date(byAdding: .day, value: -1, to: now) ?? now
        let archive: [String: Any] = [
            "format": "ayuvo-medications",
            "version": 1,
            "exported_ms": nowMs,
            "time_zone": TimeZone.current.identifier,
            "app": ["platform": "ios", "version": "ui-test"],
            "medications": [
                [
                    "id": "ui-med-metformin", "name": "Metformin", "generic_name": NSNull(), "brand_name": NSNull(),
                    "strength": "500 mg", "form": "tablet", "dose_quantity": 1, "dose_unit": "tablet",
                    "food_relation": "with", "instructions": NSNull(), "start_date": iso(yesterday), "end_date": NSNull(),
                    "status": "active", "is_prn": 0, "photo_path": NSNull(), "related_record_id": NSNull(),
                    "created_ms": nowMs - 86_400_000, "updated_ms": nowMs - 86_400_000,
                ],
                [
                    "id": "ui-med-paracetamol", "name": "Paracetamol", "generic_name": NSNull(), "brand_name": NSNull(),
                    "strength": "650 mg", "form": "tablet", "dose_quantity": 1, "dose_unit": "tablet",
                    "food_relation": "anytime", "instructions": NSNull(), "start_date": iso(yesterday), "end_date": NSNull(),
                    "status": "active", "is_prn": 1, "photo_path": NSNull(), "related_record_id": NSNull(),
                    "created_ms": nowMs - 86_400_000, "updated_ms": nowMs - 86_400_000,
                ],
            ],
            "schedules": [
                [
                    "id": "ui-sch-metformin", "medication_id": "ui-med-metformin", "frequency_kind": "daily",
                    "times": times, "days": [], "interval_hours": NSNull(), "anchor_time": NSNull(),
                    "reminder_enabled": 1, "active_from_ms": nowMs - 86_400_000, "active_until_ms": NSNull(),
                    "created_ms": nowMs - 86_400_000, "updated_ms": nowMs - 86_400_000,
                ],
            ],
            "dose_logs": [],
        ]
        let data = try JSONSerialization.data(withJSONObject: archive, options: [.sortedKeys, .prettyPrinted])
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent("medications-ui-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let url = directory.appendingPathComponent("ayuvo-medications.json")
        try data.write(to: url)
        return url
    }

    private func launch(fixture: URL?) -> XCUIApplication {
        let app = XCUIApplication()
        app.launchArguments += [
            "-AppleLanguages", "(en)",
            "-hasCompletedOnboarding", "YES",
            "-notificationsEnabled", "NO",
            "-ayuvoMedicationsReset",
        ]
        if let fixture {
            app.launchArguments += ["-ayuvoMedicationsFixture", fixture.path]
        }
        app.launch()
        return app
    }

    private func openMedsPane(_ app: XCUIApplication) {
        XCTAssertTrue(app.tabBars.buttons["Browse"].waitForExistence(timeout: 10))
        app.tabBars.buttons["Browse"].tap()
        let meds = app.buttons["browse.row.medications"].firstMatch
        // Medications sits near the bottom of the domain list.
        for _ in 0..<6 where !meds.exists { app.swipeUp() }
        XCTAssertTrue(meds.waitForExistence(timeout: 8), "Browse should list Medications")
        meds.tap()
        XCTAssertTrue(app.otherElements["medications.home"].firstMatch.waitForExistence(timeout: 8)
                      || app.buttons["medications.add"].waitForExistence(timeout: 8),
                      "Browse › Medications should render the Medications home")
    }

    @MainActor
    func testTodayTimelineTakeAndDetail() throws {
        let fixture = try makeFixtureArchive()
        let app = launch(fixture: fixture)

        for tab in ["Summary", "Browse", "Records", "Coach", "Settings"] {
            XCTAssertTrue(app.tabBars.buttons[tab].waitForExistence(timeout: 10), "Missing tab \(tab)")
        }

        // Summary card appears once there is an active medicine.
        let summaryCard = app.buttons["home.medicationsCard"].firstMatch
        for _ in 0..<4 where !summaryCard.exists { app.swipeUp() }
        XCTAssertTrue(summaryCard.waitForExistence(timeout: 10), "Summary should show the Medications card")
        shot(app, "medications-ios-01-home-card")
        summaryCard.tap()
        XCTAssertTrue(app.navigationBars["Medications"].waitForExistence(timeout: 8))

        // Today timeline with a Take button on the scheduled Metformin dose.
        let take = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH 'medications.take.ui-med-metformin'")).firstMatch
        XCTAssertTrue(take.waitForExistence(timeout: 10), "Today should list the scheduled Metformin dose with Take")
        XCTAssertTrue(app.staticTexts["Metformin 500 mg"].firstMatch.exists)
        shot(app, "medications-ios-02-today")
        take.tap()
        let takenBadge = app.descendants(matching: .any).matching(identifier: "medications.status.taken").firstMatch
        XCTAssertTrue(takenBadge.waitForExistence(timeout: 8), "The dose should show the Taken badge")
        shot(app, "medications-ios-03-taken")

        // As-needed section with Log dose.
        XCTAssertTrue(app.buttons["medications.prn.log.ui-med-paracetamol"].firstMatch.waitForExistence(timeout: 5),
                      "As-needed medicine should offer Log dose")

        // Detail: adherence, schedule and the actions menu.
        let row = app.buttons["medications.row.ui-med-metformin"].firstMatch
        for _ in 0..<4 where !row.isHittable { app.swipeUp() }
        XCTAssertTrue(row.waitForExistence(timeout: 5))
        row.tap()
        XCTAssertTrue(app.navigationBars["Metformin"].waitForExistence(timeout: 8), "Detail should push on the Browse stack")
        XCTAssertTrue(app.staticTexts["medications.detail.adherence"].firstMatch.waitForExistence(timeout: 5))
        shot(app, "medications-ios-04-detail")
        app.buttons["medications.detail.menu"].firstMatch.tap()
        XCTAssertTrue(app.buttons["Pause"].firstMatch.waitForExistence(timeout: 5))
        app.buttons["Pause"].firstMatch.tap()
        let pausedBadge = app.descendants(matching: .any).matching(identifier: "medications.medicationStatus.paused").firstMatch
        XCTAssertTrue(pausedBadge.waitForExistence(timeout: 8), "Detail should show the Paused status")
        app.buttons["medications.detail.menu"].firstMatch.tap()
        XCTAssertTrue(app.buttons["Resume"].firstMatch.waitForExistence(timeout: 5))
        app.buttons["Resume"].firstMatch.tap()
        let activeBadge = app.descendants(matching: .any).matching(identifier: "medications.medicationStatus.active").firstMatch
        XCTAssertTrue(activeBadge.waitForExistence(timeout: 8), "Detail should show the Active status after Resume")
        shot(app, "medications-ios-05-detail-resumed")
        app.navigationBars.buttons.firstMatch.tap()
        XCTAssertTrue(app.buttons["medications.add"].waitForExistence(timeout: 8), "Back lands on the Medications home")
    }

    @MainActor
    func testAddFlowCreatesMedication() throws {
        let app = launch(fixture: nil)
        openMedsPane(app)
        XCTAssertTrue(app.buttons["medications.empty.add"].firstMatch.waitForExistence(timeout: 8), "Empty state should offer Add medication")
        shot(app, "medications-ios-06-empty")
        app.buttons["medications.empty.add"].firstMatch.tap()

        let name = app.textFields["medications.form.name"].firstMatch
        XCTAssertTrue(name.waitForExistence(timeout: 8), "Add form should open")
        name.tap()
        name.typeText("Atorvastatin")
        let strength = app.textFields["medications.form.strength"].firstMatch
        strength.tap()
        strength.typeText("10 mg")
        shot(app, "medications-ios-07-form")
        app.buttons["medications.form.save"].firstMatch.tap()

        XCTAssertTrue(app.staticTexts["Atorvastatin 10 mg"].firstMatch.waitForExistence(timeout: 10), "The new medicine should appear on the Medications home")
        shot(app, "medications-ios-08-added")
    }
}
