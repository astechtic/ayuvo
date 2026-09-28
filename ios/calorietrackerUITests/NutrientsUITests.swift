import XCTest

/// Nutrient charts walk (docs/nutrients.md): food with vitamin D in the diary, a weekly Vitamin D3 60,000 IU and a
/// daily Multivitamin with nutrients and taken doses (imported through the DEBUG `-ayuvoMedicationsFixture` hook),
/// then Nutrition Details, the Vitamin D chart (W, M), All Nutrients and the medication screens. The Multivitamin
/// also lists copper, iodine and thiamin (`app_tracked: false`: supplements-only rows and charts).
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
                    // app_tracked: false nutrients: the food log never records them, so they count supplements only.
                    ["key": "copper", "amount_per_unit": 0.9], ["key": "iodine", "amount_per_unit": 140],
                    ["key": "thiamin", "amount_per_unit": 1.4],
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

    private func launch(extraArguments: [String] = []) throws -> XCUIApplication {
        let app = XCUIApplication()
        let hex = try foodEntriesData().map { String(format: "%02x", $0) }.joined()
        app.launchArguments += [
            "-AppleLanguages", "(en)",
            "-hasCompletedOnboarding", "YES",
            "-notificationsEnabled", "NO",
            "-ayuvoMedicationsReset",
            "-ayuvoMedicationsFixture", try medicationsArchive().path,
            "-foodEntries", "<\(hex)>",
        ] + extraArguments
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
        // The list is lazy and now holds all 45 nutrients: the first row is loaded, vitamin D further down.
        XCTAssertTrue(app.buttons["nutrients.row.sugar"].firstMatch.waitForExistence(timeout: 8))
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

    /// Every reference nutrient can be a supplement nutrient (docs/nutrients.md §3, §5, §5b): the Add nutrient menu
    /// lists B1 / iodine / copper, Nutrition Details shows the untracked rows the active Multivitamin lists, and the
    /// copper chart says it counts supplements only.
    @MainActor
    func testUntrackedSupplementNutrients() throws {
        let app = try launch()
        openBrowseRow(app, "nutrition")
        let viewMore = app.buttons["View More"].firstMatch
        XCTAssertTrue(viewMore.waitForExistence(timeout: 10))
        viewMore.tap()
        XCTAssertTrue(app.buttons["nutritionDetail.row.sugar"].firstMatch.waitForExistence(timeout: 8))
        let copper = app.buttons["nutritionDetail.row.copper"].firstMatch
        scrollTo(app, copper, maxSwipes: 10)
        XCTAssertTrue(copper.exists, "Nutrition Details lists copper (active Multivitamin)")
        XCTAssertTrue(app.buttons["nutritionDetail.row.iodine"].firstMatch.exists)
        XCTAssertFalse(app.buttons["nutritionDetail.row.manganese"].firstMatch.exists, "no medication lists manganese")
        sleep(1)
        shot(app, "01_nutrition_details_untracked_minerals")
        let thiamin = app.buttons["nutritionDetail.row.thiamin"].firstMatch
        scrollTo(app, thiamin, maxSwipes: 10)
        XCTAssertTrue(thiamin.exists)
        sleep(1)
        shot(app, "02_nutrition_details_untracked_vitamins")
        for _ in 0..<6 where !(copper.exists && copper.isHittable) {
            let top = app.coordinate(withNormalizedOffset: CGVector(dx: 0.92, dy: 0.35))
            top.press(forDuration: 0.05, thenDragTo: app.coordinate(withNormalizedOffset: CGVector(dx: 0.92, dy: 0.75)))
        }
        copper.tap()
        let note = app.descendants(matching: .any)["metric.nutrient.supplementsOnly"].firstMatch
        XCTAssertTrue(note.waitForExistence(timeout: 10), "copper chart shows the supplements-only note")
        XCTAssertTrue(app.staticTexts["Food isn't recorded for Copper: this chart counts supplements only."].firstMatch.exists)
        XCTAssertFalse(app.staticTexts["Food vs Supplements"].firstMatch.exists, "no Food vs Supplements split")
        sleep(2)
        shot(app, "03_copper_chart_supplements_only")
        app.buttons["M"].firstMatch.tap()
        sleep(2)
        shot(app, "04_copper_chart_month")
        let learn = app.buttons["metric.nutrient.learnMore"].firstMatch
        scrollTo(app, learn)
        sleep(1)
        shot(app, "05_copper_about")

        let done = app.buttons["Done"].firstMatch
        for _ in 0..<3 where !done.exists {
            app.navigationBars.buttons.element(boundBy: 0).tap()
            _ = done.waitForExistence(timeout: 2)
        }
        if done.exists { done.tap() }
        sleep(1)

        // Medication detail: the Nutrition card with copper and iodine.
        openBrowseRow(app, "medications")
        let multi = app.buttons["medications.row.ui-med-multi"].firstMatch
        if !multi.waitForExistence(timeout: 8) { scrollTo(app, multi) }
        multi.tap()
        let copperRow = app.descendants(matching: .any)["medications.detail.nutrient.copper"].firstMatch
        XCTAssertTrue(copperRow.waitForExistence(timeout: 8), "the Nutrition card lists copper")
        for _ in 0..<8 where !(copperRow.exists && copperRow.isHittable) { scrollUp(app) }
        XCTAssertTrue(app.descendants(matching: .any)["medications.detail.nutrient.iodine"].firstMatch.exists)
        sleep(1)
        shot(app, "06_medication_detail_copper_iodine")
        app.navigationBars.buttons.element(boundBy: 0).tap()
        sleep(1)

        // Empty Add form: the Add nutrient menu lists every reference nutrient, grouped.
        let addMedication = app.buttons["medications.add"].firstMatch
        XCTAssertTrue(addMedication.waitForExistence(timeout: 8))
        addMedication.tap()
        XCTAssertTrue(app.textFields["medications.form.name"].firstMatch.waitForExistence(timeout: 8))
        let add = app.descendants(matching: .any)["medications.form.addNutrient"].firstMatch
        scrollTo(app, add)
        add.coordinate(withNormalizedOffset: CGVector(dx: 0.3, dy: 0.5)).tap()
        if !app.buttons["Vitamin B1 (Thiamin)"].firstMatch.waitForExistence(timeout: 3) {
            print("ADD-NUTRIENT-TREE\n\(app.debugDescription)")
        }
        XCTAssertTrue(app.buttons["Vitamin B1 (Thiamin)"].firstMatch.exists, "the menu lists Vitamin B1 (Thiamin)")
        sleep(1)
        shot(app, "07_add_nutrient_menu_vitamins")
        func menuItem(_ label: String) -> XCUIElement {
            app.descendants(matching: .any).matching(NSPredicate(format: "label == %@", label)).firstMatch
        }
        let menuCopper = menuItem("Copper")
        // A quick flick inside the menu scrolls it (a slow press-and-drag would select an item).
        for _ in 0..<4 where !(menuCopper.exists && menuCopper.isHittable) {
            app.coordinate(withNormalizedOffset: CGVector(dx: 0.35, dy: 0.7))
                .press(forDuration: 0.01, thenDragTo: app.coordinate(withNormalizedOffset: CGVector(dx: 0.35, dy: 0.3)),
                       withVelocity: .fast, thenHoldForDuration: 0)
            sleep(1)
        }
        XCTAssertTrue(menuItem("Iodine").exists, "the menu lists Iodine")
        XCTAssertTrue(menuCopper.exists, "the menu lists Copper")
        sleep(1)
        shot(app, "08_add_nutrient_menu_minerals")
        menuCopper.tap()
        let amount = app.textFields["medications.form.nutrient.copper.amount"].firstMatch
        XCTAssertTrue(amount.waitForExistence(timeout: 5), "copper row added to the form")
        sleep(1)
        shot(app, "09_add_form_copper_row")
    }

    /// Regression: "Get nutrients with AI › From a label photo" used to dismiss the whole Edit medication sheet
    /// (the picker was attached to a Form section). The picker must open over the form and Cancel must return to it.
    /// Ollama needs no API key, so it enables the photo route without a network call (nothing is sent: the picker is cancelled).
    @MainActor
    func testLabelPhotoPickerKeepsEditForm() throws {
        let app = try launch(extraArguments: ["-selectedAIProvider", "Ollama (Local)"])
        openBrowseRow(app, "medications")
        let multi = app.buttons["medications.row.ui-med-multi"].firstMatch
        if !multi.waitForExistence(timeout: 8) { scrollTo(app, multi) }
        multi.tap()
        let menu = app.buttons["medications.detail.menu"].firstMatch
        XCTAssertTrue(menu.waitForExistence(timeout: 8))
        menu.tap()
        app.buttons["Edit"].firstMatch.tap()
        XCTAssertTrue(app.buttons["medications.form.save"].waitForExistence(timeout: 8))
        // A SwiftUI Menu is a pop-up button, not a plain button.
        let ai = app.descendants(matching: .any)["medications.form.aiNutrients"].firstMatch
        scrollTo(app, app.buttons["medications.form.addNutrient"].firstMatch)
        XCTAssertTrue(ai.waitForExistence(timeout: 5), "AI button should show with a provider set")
        ai.tap()
        let fromPhoto = app.buttons["From a label photo"].firstMatch
        XCTAssertTrue(fromPhoto.waitForExistence(timeout: 5))
        fromPhoto.tap()
        sleep(3)
        shot(app, "13_label_photo_picker")
        // The system picker runs out of process: close it with its X, then open it again and pick a photo.
        let close = app.buttons["Close"].firstMatch
        if close.waitForExistence(timeout: 5) { close.tap() }
        sleep(2)
        shot(app, "14_after_picker_close")
        XCTAssertTrue(app.buttons["medications.form.save"].waitForExistence(timeout: 5), "Closing the picker must return to the edit form")
        for _ in 0..<10 where !ai.isHittable { sleep(1) }
        ai.tap()
        XCTAssertTrue(fromPhoto.waitForExistence(timeout: 5))
        fromPhoto.tap()
        let photo = app.images.firstMatch
        if photo.waitForExistence(timeout: 8) { photo.tap() }
        // Ollama is not running on the simulator, so the read fails and the form shows the hand-entry message.
        let message = app.staticTexts["The AI couldn't read this right now. Add the nutrients by hand."].firstMatch
        _ = message.waitForExistence(timeout: 40)
        sleep(1)
        shot(app, "15_after_photo_read")
        XCTAssertTrue(app.buttons["medications.form.save"].exists, "Picking a photo must keep the edit form open")
    }
}
