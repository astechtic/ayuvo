import XCTest

/// The Manual Entry popover must keep its title and Name field reachable while the keyboard
/// is up: the content scrolls instead of being pushed off the top of the popover.
final class ManualEntryPopoverUITests: XCTestCase {

    override func setUpWithError() throws {
        continueAfterFailure = false
    }

    private func attach(_ app: XCUIApplication, _ name: String) {
        let screenshot = XCTAttachment(screenshot: app.screenshot())
        screenshot.name = name
        screenshot.lifetime = .keepAlways
        add(screenshot)
    }

    @MainActor
    func testManualEntryPopoverScrollsWithKeyboard() throws {
        let app = XCUIApplication()
        app.launchArguments += ["-AppleLanguages", "(en)", "-hasCompletedOnboarding", "YES"]
        app.launch()

        // The food diary (and its + button) lives under Browse › Nutrition.
        XCTAssertTrue(app.tabBars.buttons["Browse"].waitForExistence(timeout: 10))
        app.tabBars.buttons["Browse"].tap()
        let nutrition = app.buttons["browse.row.nutrition"].firstMatch
        XCTAssertTrue(nutrition.waitForExistence(timeout: 8))
        nutrition.tap()

        let add = app.buttons["home.add"]
        XCTAssertTrue(add.waitForExistence(timeout: 10))
        add.tap()
        // The + menu groups methods ("Describe Meal" → Text / Voice / Manual Entry) unless the
        // user flattened it in Settings; handle both shapes.
        var manual = app.buttons["Manual Entry"]
        if !manual.waitForExistence(timeout: 3) {
            let describe = app.buttons["Describe Meal"]
            XCTAssertTrue(describe.waitForExistence(timeout: 3), "Neither Manual Entry nor its Describe Meal group is in the + menu")
            describe.tap()
            manual = app.buttons["Manual Entry"]
        }
        XCTAssertTrue(manual.waitForExistence(timeout: 5))
        manual.tap()

        let title = app.staticTexts["Manual Entry"].firstMatch
        XCTAssertTrue(title.waitForExistence(timeout: 5))
        let nameField = app.textFields["e.g. Homemade salad"].firstMatch
        XCTAssertTrue(nameField.waitForExistence(timeout: 5))
        nameField.tap()
        nameField.typeText("Salad")
        XCTAssertTrue(app.keyboards.firstMatch.waitForExistence(timeout: 5), "Typing should raise the keyboard")
        // First-use keyboard tip ("Type English and Hindi… Continue") sits over the keys; clear it.
        let keyboardTip = app.keyboards.buttons["Continue"].firstMatch
        if keyboardTip.waitForExistence(timeout: 1) {
            keyboardTip.tap()
        }
        attach(app, "20 Manual entry with keyboard")

        XCTAssertTrue(title.exists && title.isHittable, "Title must stay on screen with the keyboard up")
        XCTAssertTrue(nameField.isHittable, "Name field must stay on screen with the keyboard up")

        // Scroll inside the popover (drag on a label, not the text field, so it is not a text
        // selection): Save must become reachable without dismissing the keyboard.
        let save = app.buttons["Save"].firstMatch
        let scroller = app.scrollViews.firstMatch
        for _ in 0..<5 where !(save.exists && save.isHittable) {
            if scroller.exists {
                scroller.swipeUp(velocity: .slow)
            } else {
                app.staticTexts["Fiber (g)"].firstMatch.swipeUp(velocity: .slow)
            }
        }
        XCTAssertTrue(save.waitForExistence(timeout: 3) && save.isHittable, "Save should be reachable by scrolling the popover")
        XCTAssertTrue(app.keyboards.firstMatch.exists, "Scrolling the popover should not dismiss the keyboard")
        attach(app, "21 Manual entry scrolled")

        app.buttons["Cancel"].firstMatch.tap()
    }
}
