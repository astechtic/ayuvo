import BackgroundTasks
import Foundation

/// Best-effort refill of the rolling reminder window while the app is not used (docs §10).
/// iOS decides when (never in Low Power Mode or with Background App Refresh off), so this only
/// supplements the foreground, store-write and notification-action replans.
enum MedicationBackgroundRefresh {
    static let taskIdentifier = "com.ayuvo.health.medications.refresh"
    /// Earliest next run after scheduling.
    static let minimumInterval: TimeInterval = 6 * 60 * 60

    /// Must run before the app finishes launching (`AppDelegate.didFinishLaunching`).
    static func register() {
        BGTaskScheduler.shared.register(forTaskWithIdentifier: taskIdentifier, using: nil) { task in
            guard let refresh = task as? BGAppRefreshTask else {
                task.setTaskCompleted(success: false)
                return
            }
            Task { @MainActor in await perform(refresh) }
        }
    }

    /// Asks for a refresh no earlier than `minimumInterval` from now (idempotent; replaces a pending request).
    static func schedule() {
        let request = BGAppRefreshTaskRequest(identifier: taskIdentifier)
        request.earliestBeginDate = Date(timeIntervalSinceNow: minimumInterval)
        try? BGTaskScheduler.shared.submit(request)
    }

    @MainActor
    static func perform(_ task: BGAppRefreshTask) async {
        schedule()
        let work = Task { @MainActor in
            await MedicationReminderRuntime.shared.replan()
        }
        task.expirationHandler = { work.cancel() }
        await work.value
        task.setTaskCompleted(success: !work.isCancelled)
    }
}
