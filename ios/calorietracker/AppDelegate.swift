import FirebaseCore
import UIKit
import UserNotifications

/// Minimal app delegate, attached via `@UIApplicationDelegateAdaptor`: configures Firebase core
/// (no Analytics or other data-collecting products) and handles local notifications: present the
/// "Update Available" banner while the app is foreground (the update check runs at launch) and open
/// the App Store when it's tapped; medication reminders (docs/medications.md §16) show in the
/// foreground too, and their Taken / Skip / Snooze actions are applied in the background by
/// `MedicationActionHandler` before any SwiftUI view exists.
final class AppDelegate: NSObject, UIApplicationDelegate, UNUserNotificationCenterDelegate {
    func application(
        _ application: UIApplication,
        didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil
    ) -> Bool {
        FirebaseApp.configure()
        UNUserNotificationCenter.current().delegate = self
        // Both must happen before launch completes: the category so the actions render on the first
        // reminder, the background task so iOS accepts later `BGAppRefreshTaskRequest`s.
        MedicationNotificationScheduler.registerCategory()
        MedicationBackgroundRefresh.register()
        MedicationReminderRuntime.shared.start()
        QuickActionSettings.registerApplicationShortcuts()
        WatchSnapshotSync.shared.activate()
        return true
    }

    func application(
        _ application: UIApplication,
        configurationForConnecting connectingSceneSession: UISceneSession,
        options: UIScene.ConnectionOptions
    ) -> UISceneConfiguration {
        let configuration = UISceneConfiguration(
            name: "Default Configuration",
            sessionRole: connectingSceneSession.role
        )
        configuration.delegateClass = SceneDelegate.self
        return configuration
    }

    // Retained for iOS versions/configurations that still deliver shortcuts to the app delegate.
    func application(
        _ application: UIApplication,
        performActionFor shortcutItem: UIApplicationShortcutItem,
        completionHandler: @escaping (Bool) -> Void
    ) {
        completionHandler(QuickActionCoordinator.handle(shortcutItem))
    }

    /// Show the update banner and due-dose reminders even when the app is in the foreground; leave
    /// the other scheduled reminders to their default (no foreground interruption).
    func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        willPresent notification: UNNotification
    ) async -> UNNotificationPresentationOptions {
        let identifier = notification.request.identifier
        if identifier == NotificationManager.appUpdateNotificationID
            || MedicationReminderPlanner.isMedicationIdentifier(identifier) {
            return [.banner, .sound, .list]
        }
        return []
    }

    /// Medication actions first (Taken / Skip / Snooze are applied here, a plain tap routes to the
    /// Meds segment); otherwise open the App Store listing when the update notification is tapped.
    func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        didReceive response: UNNotificationResponse
    ) async {
        if await MedicationActionHandler.handle(response) { return }
        let userInfo = response.notification.request.content.userInfo
        if let urlString = userInfo["updateURL"] as? String, let url = URL(string: urlString) {
            await UIApplication.shared.open(url)
        }
    }
}
