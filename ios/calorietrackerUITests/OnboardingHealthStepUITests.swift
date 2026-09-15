import XCTest

/// Onboarding "Connect to Apple Health" step: the Coach consent card must keep the same side
/// margins as the Continue button (its switch used to be pushed against the trailing edge).
final class OnboardingHealthStepUITests: XCTestCase {

    override func setUpWithError() throws {
        continueAfterFailure = false
    }

    @MainActor
    func testCoachConsentCardKeepsMarginsOnAppleHealthStep() throws {
        let app = XCUIApplication()
        // `-hasCompletedOnboarding NO` (argument domain) beats any persisted value, so the
        // onboarding flow is shown regardless of earlier runs on this simulator.
        app.launchArguments += ["-AppleLanguages", "(en)", "-hasCompletedOnboarding", "NO", "--reset-onboarding", "-ayuvoOnboardingStep", "10"]
        app.launch()

        XCTAssertTrue(app.staticTexts["Let Coach use my health data"].firstMatch.waitForExistence(timeout: 10), "Apple Health step should open directly")
        let toggle = app.switches.firstMatch
        XCTAssertTrue(toggle.waitForExistence(timeout: 5))
        let screen = app.frame
        // The Toggle element spans label + switch inside the card (24 pt margin + 16 pt inset).
        let row = toggle.frame
        let leftMargin = row.minX - screen.minX
        let rightMargin = screen.maxX - row.maxX
        XCTAssertGreaterThanOrEqual(leftMargin, 24, "consent row should sit inside the card margin")
        XCTAssertGreaterThanOrEqual(rightMargin, 24, "switch must not touch the trailing edge")
        XCTAssertEqual(leftMargin, rightMargin, accuracy: 4, "consent row should be centred with equal margins")

        let continueButton = app.buttons["Continue"].firstMatch
        XCTAssertTrue(continueButton.exists)
        XCTAssertLessThanOrEqual(continueButton.frame.minX, row.minX, "card aligns with (or inside) the Continue button's margin")
        XCTAssertEqual(toggle.value as? String, "1", "consent stays pre-checked")

        let screenshot = XCTAttachment(screenshot: app.screenshot())
        screenshot.name = "30 Onboarding Apple Health step"
        screenshot.lifetime = .keepAlways
        add(screenshot)
    }
}
