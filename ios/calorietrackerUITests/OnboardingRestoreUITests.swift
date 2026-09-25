import XCTest

/// Welcome › "Restore from a backup": an Android export (portable data only, no iPhone app backup)
/// restores the profile, so onboarding skips the profile steps and lands on the notifications step.
/// The zip is handed over with the DEBUG `-ayuvoImportAllFile` hook (no Files picker on a simulator).
final class OnboardingRestoreUITests: XCTestCase {
    override func setUpWithError() throws {
        continueAfterFailure = false
    }

    @MainActor
    func testAndroidExportRestoresProfileAndJumpsToNotificationsStep() throws {
        let zip = try XCTUnwrap(ProcessInfo.processInfo.environment["AYUVO_RESTORE_ZIP"], "set TEST_RUNNER_AYUVO_RESTORE_ZIP to an export zip")
        let app = XCUIApplication()
        app.launchArguments += ["-AppleLanguages", "(en)", "-hasCompletedOnboarding", "NO", "--reset-onboarding", "-ayuvoImportAllFile", zip]
        app.launch()

        let restore = app.buttons["onboarding.restoreBackup"]
        XCTAssertTrue(restore.waitForExistence(timeout: 15), "Welcome step shows the restore button")
        restore.tap()

        let importButton = app.buttons["settings.importAll.import"]
        XCTAssertTrue(importButton.waitForExistence(timeout: 15), "the export's preview appears")
        XCTAssertTrue(app.staticTexts["Profile, goals & logs"].exists, "portable section is listed")
        add(screenshot(app, "Restore preview"))
        importButton.tap()

        let done = app.buttons["Done"]
        XCTAssertTrue(done.waitForExistence(timeout: 20), "import finishes")
        add(screenshot(app, "Restore result"))
        done.tap()

        XCTAssertTrue(app.staticTexts["Be reminded to\nlog meals"].waitForExistence(timeout: 10)
            || app.staticTexts.matching(NSPredicate(format: "label CONTAINS 'log meals'")).firstMatch.waitForExistence(timeout: 10),
            "restored onboarding continues with the notifications step")
        XCTAssertFalse(app.buttons.matching(NSPredicate(format: "label == 'Get Started'")).firstMatch.exists)
        add(screenshot(app, "Notifications step after restore"))
        // Give cfprefsd time to persist the restored values before the app goes away.
        sleep(3)
        app.terminate()
    }

    private func screenshot(_ app: XCUIApplication, _ name: String) -> XCTAttachment {
        let attachment = XCTAttachment(screenshot: app.screenshot())
        attachment.name = name
        attachment.lifetime = .keepAlways
        return attachment
    }
}
