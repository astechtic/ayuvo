import BackgroundTasks
import Foundation
import HealthKit
import UIKit
import UserNotifications

/// Notification copy for Insights. Both only say that something is ready: no values, ever, so nothing
/// personal shows on the lock screen (docs/insights.md §4).
enum InsightsNotifications {
    static let recoveryIdentifier = "insights.recovery"
    /// Kept from the old Daily Summary so an already-scheduled request is replaced, not duplicated.
    static let reviewIdentifier = "smart.summary"
    /// `userInfo` key read by `AppDelegate` to route a tap.
    static let routeKey = "insightsRoute"

    static func recoveryReadyContent() -> UNMutableNotificationContent {
        let content = UNMutableNotificationContent()
        content.title = String(localized: "Your recovery is ready")
        content.body = String(localized: "Open Ayuvo to see this morning's Recovery.")
        content.sound = .default
        content.userInfo = [routeKey: "screen:insights.recovery"]
        return content
    }

    static func dailyReviewContent() -> UNMutableNotificationContent {
        let content = UNMutableNotificationContent()
        content.title = String(localized: "Your daily review is ready")
        content.body = String(localized: "See how today went in Ayuvo.")
        content.sound = .default
        content.userInfo = [routeKey: "screen:insights.review"]
        return content
    }

    /// The Insights screen a tapped notification opens, if it is one of ours.
    static func route(from userInfo: [AnyHashable: Any]) -> ActionRoute? {
        guard let target = userInfo[routeKey] as? String, target.hasPrefix("screen:insights") else { return nil }
        return .target(target)
    }
}

/// Morning Recovery refresh (docs/insights.md §6). A `BGAppRefreshTask` that becomes eligible from 05:00 and an
/// `HKObserverQuery` with hourly background delivery on sleep analysis both run `refresh()`: an incremental
/// mirror sync, then Recovery, then — only when the opt-in notification is on and a score exists — "Your
/// recovery is ready". iOS decides when either actually runs.
enum InsightsBackgroundRefresh {
    static let taskIdentifier = "com.ayuvo.health.insights.refresh"
    static let earliestHour = 5
    /// The morning notification is not sent after this hour.
    static let latestNotifyHour = 12

    @MainActor private static var sleepObserver: HKObserverQuery?
    /// The app's store while the UI is alive, so a refresh joins its sync instead of racing it.
    @MainActor static weak var liveHealthStore: HealthDataStore?

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

    /// Next 05:00 after `now` (today's when it is still ahead).
    static func nextEarliestDate(after now: Date = Date(), calendar: Calendar = .current) -> Date {
        let today = calendar.date(bySettingHour: earliestHour, minute: 0, second: 0, of: now) ?? now
        if today > now { return today }
        return calendar.date(byAdding: .day, value: 1, to: today) ?? now.addingTimeInterval(86_400)
    }

    /// Asks for the next morning run (idempotent; replaces a pending request). Nothing is asked while Insights or
    /// Health sync is off.
    static func schedule(defaults: UserDefaults = .standard) {
        guard InsightsSettings.isEnabled(defaults), defaults.bool(forKey: "healthKitEnabled") else {
            BGTaskScheduler.shared.cancel(taskRequestWithIdentifier: taskIdentifier)
            return
        }
        let request = BGAppRefreshTaskRequest(identifier: taskIdentifier)
        request.earliestBeginDate = nextEarliestDate()
        try? BGTaskScheduler.shared.submit(request)
    }

    @MainActor
    static func perform(_ task: BGAppRefreshTask) async {
        schedule()
        let work = Task { @MainActor in await refresh() }
        task.expirationHandler = { work.cancel() }
        await work.value
        task.setTaskCompleted(success: !work.isCancelled)
    }

    /// Watches sleep analysis with hourly background delivery so a synced night can wake the app.
    @MainActor
    static func startSleepObserver(defaults: UserDefaults = .standard) {
        guard sleepObserver == nil, InsightsSettings.isEnabled(defaults), defaults.bool(forKey: "healthKitEnabled"),
              HKHealthStore.isHealthDataAvailable() else { return }
        let store = HealthKitManager.sharedHealthStore
        let type = HKCategoryType(.sleepAnalysis)
        let query = HKObserverQuery(sampleType: type, predicate: nil) { _, completionHandler, error in
            guard error == nil else {
                completionHandler()
                return
            }
            Task { @MainActor in
                // HealthKit also fires an observer once on registration and while the app is open; the
                // foreground already syncs on open, so only background wake-ups do work here.
                if UIApplication.shared.applicationState == .background { await refresh() }
                completionHandler()
            }
        }
        store.execute(query)
        store.enableBackgroundDelivery(for: type, frequency: .hourly) { _, _ in }
        sleepObserver = query
    }

    @MainActor
    static func stopSleepObserver() {
        guard let query = sleepObserver else { return }
        HealthKitManager.sharedHealthStore.stop(query)
        HealthKitManager.sharedHealthStore.disableBackgroundDelivery(for: HKCategoryType(.sleepAnalysis)) { _, _ in }
        sleepObserver = nil
    }

    /// Sync, compute, maybe notify. Before 05:00 it does nothing (last night is not over).
    @MainActor
    static func refresh(now: Date = Date(), defaults: UserDefaults = .standard, calendar: Calendar = .current) async {
        guard InsightsSettings.isEnabled(defaults), defaults.bool(forKey: "healthKitEnabled") else { return }
        let hour = calendar.component(.hour, from: now)
        guard hour >= earliestHour else { return }
        await syncMirror()
        guard !Task.isCancelled else { return }
        let report = await InsightsDataSource.standalone(defaults: defaults, calendar: calendar).report(now: now)
        guard report.recovery.isReady else { return }
        await notifyRecoveryReady(day: report.today, hour: hour, defaults: defaults)
    }

    /// Incremental anchored sync of the mirror (the same engine pass as opening the app).
    @MainActor
    static func syncMirror() async {
        if let store = liveHealthStore {
            _ = await store.sync(.appOpen)
            return
        }
        let runtime = HealthDataRuntime.shared
        guard await runtime.openIfNeeded(), let engine = runtime.makeEngine(types: HealthMetricRegistry.syncableTypes()) else { return }
        _ = await Task.detached(priority: .utility) {
            await engine.sync(trigger: .appOpen) { _ in }
        }.value
    }

    @MainActor
    static func notifyRecoveryReady(day: String, hour: Int, defaults: UserDefaults) async {
        guard InsightsSettings.morningRecoveryEnabled(defaults), hour < latestNotifyHour,
              defaults.string(forKey: InsightsSettings.lastRecoveryNotifiedDayKey) != day else { return }
        let center = UNUserNotificationCenter.current()
        guard await center.notificationSettings().authorizationStatus == .authorized else { return }
        let request = UNNotificationRequest(identifier: InsightsNotifications.recoveryIdentifier,
                                            content: InsightsNotifications.recoveryReadyContent(), trigger: nil)
        do {
            try await center.add(request)
            defaults.set(day, forKey: InsightsSettings.lastRecoveryNotifiedDayKey)
        } catch {
            // Best effort; the score is there when the app opens.
        }
    }
}
