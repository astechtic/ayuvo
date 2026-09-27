import XCTest

/// Nutrient charts walk (docs/nutrients.md): food with vitamin D in the diary, a weekly Vitamin D3 60,000 IU and a
/// daily Multivitamin with nutrients and taken doses (imported through the DEBUG `-ayuvoMedicationsFixture` hook),
/// then Nutrition Details, the Vitamin D chart (W, M), All Nutrients and the medication screens.
///
/// Set `TEST_RUNNER_AYUVO_SMOKE_SHOTS_DIR=<absolute dir>` to also write PNGs there.
final class NutrientsUITests: XCTestCase {
    override func setUpWithError() throws {
        // A screenshot walk: record a missed element and keep going so later screens are still captured.
        continueAfterFailure = true
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

    private let calendar = Calendar.current

    private func day(_ offset: Int, hour: Int, minute: Int = 0) -> Date {
        let start = calendar.startOfDay(for: Date())
        let base = calendar.date(byAdding: .day, value: offset, to: start) ?? start
        return calendar.date(bySettingHour: hour, minute: minute, second: 0, of: base) ?? base
    }

    private func ms(_ date: Date) -> Int64 { Int64(date.timeIntervalSince1970 * 1000) }

    private func iso(_ date: Date) -> String {
        let c = calendar.dateComponents([.year, .month, .day], from: date)
        return String(format: "%04d-%02d-%02d", c.year ?? 1970, c.month ?? 1, c.day ?? 1)
    }

    /// Two weeks of meals with vitamin D, calcium and iron (the `foodEntries` JSON the app stores).
    private func foodEntriesData() throws -> Data {
        var rows: [[String: Any]] = []
        for offset in -13...0 {
            let breakfast = day(offset, hour: 8, minute: 30)
            let lunch = day(offset, hour: 13)
            rows.append([
                "id": UUID().uuidString, "name": "Eggs and toast", "calories": 380, "protein": 20, "carbs": 30, "fat": 18,
                "timestamp": breakfast.timeIntervalSinceReferenceDate, "source": "manual", "mealType": "breakfast",
                "vitaminD": 2.2, "calcium": 120, "iron": 2.4, "sodium": 520,
            ])
            if offset % 2 == 0 {
                rows.append([
                    "id": UUID().uuidString, "name": "Salmon rice bowl", "calories": 610, "protein": 38, "carbs": 62, "fat": 20,
                    "timestamp": lunch.timeIntervalSinceReferenceDate, "source": "manual", "mealType": "lunch",
                    "vitaminD": 11.5, "calcium": 60, "iron": 1.8, "sodium": 740,
                ])
            }
        }
        return try JSONSerialization.data(withJSONObject: rows)
    }

    /// Vitamin D3 60,000 IU weekly (1,500 mcg per capsule) and a daily Multivitamin, with taken and skipped doses.
    private func medicationsArchive() throws -> URL {
        let now = Date()
        let nowMs = ms(now)
        let created = ms(day(-30, hour: 7))
        let weekday = (calendar.component(.weekday, from: now) + 5) % 7 + 1 // ISO 1 = Monday
        func med(_ id: String, _ name: String, _ form: String, _ unit: String, _ nutrients: [[String: Any]]) -> [String: Any] {
            [
                "id": id, "name": name, "generic_name": NSNull(), "brand_name": NSNull(), "strength": NSNull(), "form": form,
                "dose_quantity": 1, "dose_unit": unit, "food_relation": "with", "instructions": NSNull(),
                "start_date": iso(day(-30, hour: 7)), "end_date": NSNull(), "status": "active", "is_prn": 0, "photo_path": NSNull(),
                "related_record_id": NSNull(), "created_ms": created, "updated_ms": created, "nutrients": nutrients,
            ]
        }
        func schedule(_ id: String, _ medID: String, _ kind: String, _ days: [Int]) -> [String: Any] {
            [
                "id": id, "medication_id": medID, "frequency_kind": kind, "times": ["07:00"], "days": days,
                "interval_hours": NSNull(), "anchor_time": NSNull(), "reminder_enabled": 0, "active_from_ms": created,
                "active_until_ms": NSNull(), "created_ms": created, "updated_ms": created,
            ]
        }
        var logs: [[String: Any]] = []
        func log(_ medID: String, _ scheduleID: String, _ offset: Int, _ status: String, _ unit: String) {
            let at = day(offset, hour: 7)
            logs.append([
                "id": "\(medID)-\(offset)", "medication_id": medID, "schedule_id": scheduleID, "scheduled_at_ms": ms(at),
                "status": status, "taken_at_ms": status == "taken" ? ms(at.addingTimeInterval(600)) : NSNull(),
                "snoozed_until_ms": NSNull(), "dose_quantity": 1, "dose_unit": unit, "note": NSNull(),
                "created_ms": ms(at), "updated_ms": ms(at),
            ])
        }
        for week in [-14, -7, 0] { log("ui-med-d3", "ui-sch-d3", week, "taken", "capsule") }
        for offset in -13...0 { log("ui-med-multi", "ui-sch-multi", offset, offset == -3 ? "skipped" : "taken", "tablet") }
        let archive: [String: Any] = [
            "format": "ayuvo-medications", "version": 1, "exported_ms": nowMs, "time_zone": TimeZone.current.identifier,
            "app": ["platform": "ios", "version": "ui-test"],
            "medications": [
                med("ui-med-d3", "Vitamin D3 60,000 IU", "capsule", "capsule", [["key": "vitamin_d", "amount_per_unit": 1500]]),
                med("ui-med-multi", "Multivitamin", "tablet", "tablet", [
                    ["key": "vitamin_c", "amount_per_unit": 80], ["key": "zinc", "amount_per_unit": 10],
                    ["key": "iron", "amount_per_unit": 18], ["key": "vitamin_d", "amount_per_unit": 10],
                    ["key": "folate", "amount_per_unit": 400],
                ]),
            ],
            "schedules": [schedule("ui-sch-d3", "ui-med-d3", "weekly", [weekday]), schedule("ui-sch-multi", "ui-med-multi", "daily", [])],
            "dose_logs": logs,
        ]
        let data = try JSONSerialization.data(withJSONObject: archive, options: [.sortedKeys])
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent("nutrients-ui-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let url = directory.appendingPathComponent("ayuvo-medications.json")
        try data.write(to: url)
        return url
    }

    private func launch() throws -> XCUIApplication {
        let app = XCUIApplication()
        let hex = try foodEntriesData().map { String(format: "%02x", $0) }.joined()
        app.launchArguments += [
            "-AppleLanguages", "(en)",
            "-hasCompletedOnboarding", "YES",
            "-notificationsEnabled", "NO",
            "-ayuvoMedicationsReset",
            "-ayuvoMedicationsFixture", try medicationsArchive().path,
            "-foodEntries", "<\(hex)>",
        ]
        app.launch()
        return app
    }

    private func openBrowseRow(_ app: XCUIApplication, _ id: String) {
        XCTAssertTrue(app.tabBars.buttons["Browse"].waitForExistence(timeout: 10))
        app.tabBars.buttons["Browse"].tap()
        let row = app.buttons["browse.row.\(id)"].firstMatch
        // Pop back to the Browse root when an earlier step left a screen pushed.
        for _ in 0..<4 where !row.waitForExistence(timeout: 1) {
            let back = app.navigationBars.buttons.element(boundBy: 0)
            if back.exists { back.tap() } else { break }
        }
        for _ in 0..<6 where !row.exists { app.swipeUp() }
        XCTAssertTrue(row.waitForExistence(timeout: 8), "Browse should list \(id)")
        row.tap()
    }

    /// Drags from near the bottom edge so the chart's scrub gesture never takes the swipe.
    private func scrollUp(_ app: XCUIApplication) {
        let start = app.coordinate(withNormalizedOffset: CGVector(dx: 0.92, dy: 0.88))
        start.press(forDuration: 0.05, thenDragTo: app.coordinate(withNormalizedOffset: CGVector(dx: 0.92, dy: 0.3)))
    }

    private func scrollTo(_ app: XCUIApplication, _ element: XCUIElement, maxSwipes: Int = 8) {
        for _ in 0..<maxSwipes where !(element.exists && element.isHittable) { scrollUp(app) }
    }

    @MainActor
    func testNutrientChartsAndSupplements() throws {
        let app = try launch()
        openBrowseRow(app, "nutrition")

        let viewMore = app.buttons["View More"].firstMatch
        XCTAssertTrue(viewMore.waitForExistence(timeout: 10))
        viewMore.tap()
        let vitaminD = app.buttons["nutritionDetail.row.vitamin_d"].firstMatch
        XCTAssertTrue(app.buttons["nutritionDetail.row.sugar"].firstMatch.waitForExistence(timeout: 8))
        scrollTo(app, vitaminD)
        sleep(1)
        shot(app, "01_nutrition_details")
        vitaminD.tap()
        XCTAssertTrue(app.otherElements["metric.chart"].firstMatch.waitForExistence(timeout: 10)
                      || app.images["metric.chart"].firstMatch.waitForExistence(timeout: 5))
        sleep(1)
        shot(app, "02_vitamin_d_week")
        app.buttons["M"].firstMatch.tap()
        sleep(2)
        shot(app, "03_vitamin_d_month")
        app.buttons["D"].firstMatch.tap()
        sleep(2)
        shot(app, "04_vitamin_d_day")
        app.buttons["W"].firstMatch.tap()
        sleep(1)
        let learn = app.buttons["metric.nutrient.learnMore"].firstMatch
        scrollTo(app, learn)
        sleep(1)
        shot(app, "05_food_vs_supplements_learn_more")
        scrollUp(app)
        sleep(1)
        shot(app, "06_vitamin_d_about")

        // Sheet → back to the diary, then Trends › All Nutrients.
        // Leave the chart, then close the sheet.
        let done = app.buttons["Done"].firstMatch
        for _ in 0..<3 where !done.exists {
            app.navigationBars.buttons.element(boundBy: 0).tap()
            _ = done.waitForExistence(timeout: 2)
        }
        if done.exists { done.tap() }
        sleep(1)
        let all = app.buttons["browse.nutrition.allNutrients"].firstMatch
        scrollTo(app, all, maxSwipes: 20)
        XCTAssertTrue(all.waitForExistence(timeout: 5))
        all.tap()
        XCTAssertTrue(app.buttons["nutrients.row.vitamin_d"].firstMatch.waitForExistence(timeout: 8))
        sleep(1)
        shot(app, "07_all_nutrients")
        app.swipeUp()
        sleep(1)
        shot(app, "08_all_nutrients_vitamins")

        // Medications: list badge, detail Nutrition card, edit form nutrients section.
        openBrowseRow(app, "medications")
        let d3 = app.buttons["medications.row.ui-med-d3"].firstMatch
        if !d3.waitForExistence(timeout: 8) { scrollTo(app, d3) }
        sleep(1)
        shot(app, "09_medications_list")
        let multi = app.buttons["medications.row.ui-med-multi"].firstMatch
        scrollTo(app, multi)
        multi.tap()
        XCTAssertTrue(app.otherElements["medications.detail.nutrition"].firstMatch.waitForExistence(timeout: 8)
                      || app.staticTexts["Nutrition"].firstMatch.waitForExistence(timeout: 5))
        sleep(1)
        shot(app, "10_medication_detail_nutrition")
        app.buttons["medications.detail.menu"].firstMatch.tap()
        app.buttons["Edit"].firstMatch.tap()
        let add = app.buttons["medications.form.addNutrient"].firstMatch
        XCTAssertTrue(app.buttons["medications.form.save"].waitForExistence(timeout: 8))
        scrollTo(app, add)
        sleep(1)
        shot(app, "11_medication_form_nutrients")
        app.buttons["medications.form.cancel"].firstMatch.tap()
        sleep(1)
        openBrowseRow(app, "medications")

        // The empty Add form's nutrients section (entry and IU conversion are covered by the unit tests).
        let addMedication = app.buttons["medications.add"].firstMatch
        XCTAssertTrue(addMedication.waitForExistence(timeout: 8))
        addMedication.tap()
        XCTAssertTrue(app.textFields["medications.form.name"].firstMatch.waitForExistence(timeout: 8))
        scrollTo(app, add)
        sleep(1)
        shot(app, "12_add_form_nutrients")
    }
}
