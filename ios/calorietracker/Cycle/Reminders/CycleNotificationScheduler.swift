import Foundation
import UIKit
import UserNotifications

/// One planned cycle notification (pure, unit-tested).
nonisolated struct CyclePlannedReminder: Sendable, Equatable {
    var identifier: String
    var title: String
    var body: String
    /// Local fire time (`repeats` = every day at hour:minute).
    var components: DateComponents
    var repeats: Bool
}

/// Turns the engine's `reminders` into at most three `cycle.*` requests (docs/cycle-tracking.md §6). Text is discreet
/// by default: nothing about periods shows on the lock screen unless "Show details on lock screen" is on.
nonisolated enum CycleReminderPlanner {
    static let prefix = "cycle."
    static let periodSoonID = "cycle.period_soon"
    static let periodEndID = "cycle.period_end"
    static let dailyID = "cycle.daily_log"
    static let maxPending = 3
    static let routeKey = "cycleRoute"

    static func isCycleIdentifier(_ id: String) -> Bool { id.hasPrefix(prefix) }

    /// 'HH:mm' → (hour, minute); 09:00 for anything malformed.
    static func time(_ text: String) -> (Int, Int) {
        let parts = text.split(separator: ":").compactMap { Int($0) }
        guard parts.count == 2, (0...23).contains(parts[0]), (0...59).contains(parts[1]) else { return (9, 0) }
        return (parts[0], parts[1])
    }

    static func plan(reminders: [CycleReminder], options: CycleSettingsOptions, now: Date, calendar: Calendar = .current) -> [CyclePlannedReminder] {
        let (hour, minute) = time(options.time)
        var out: [CyclePlannedReminder] = []
        for reminder in reminders {
            switch reminder.kind {
            case "daily_log":
                guard options.daily else { continue }
                var c = DateComponents()
                c.hour = hour
                c.minute = minute
                out.append(CyclePlannedReminder(identifier: dailyID, title: title(), body: body(kind: reminder.kind, options: options),
                                                components: c, repeats: true))
            case "period_soon", "period_end":
                guard let day = reminder.day, let n = CycleDay.ordinal(day) else { continue }
                var fireDay = n
                var fire = fireDate(day: fireDay, hour: hour, minute: minute, calendar: calendar)
                if fire <= now {
                    // A period-end nudge whose time passed today moves to tomorrow; a passed "period soon" is dropped.
                    guard reminder.kind == "period_end" else { continue }
                    fireDay += 1
                    fire = fireDate(day: fireDay, hour: hour, minute: minute, calendar: calendar)
                }
                let c = calendar.dateComponents([.year, .month, .day, .hour, .minute], from: fire)
                out.append(CyclePlannedReminder(identifier: reminder.kind == "period_soon" ? periodSoonID : periodEndID, title: title(),
                                                body: body(kind: reminder.kind, options: options), components: c, repeats: false))
            default:
                continue
            }
        }
        return Array(out.prefix(maxPending))
    }

    static func fireDate(day: Int, hour: Int, minute: Int, calendar: Calendar) -> Date {
        let text = CycleDay.string(day)
        var c = DateComponents()
        c.year = Int(text.prefix(4))
        c.month = Int(text.dropFirst(5).prefix(2))
        c.day = Int(text.dropFirst(8).prefix(2))
        c.hour = hour
        c.minute = minute
        return calendar.date(from: c) ?? .distantPast
    }

    static func title() -> String { "Ayuvo" }

    static func body(kind: String, options: CycleSettingsOptions) -> String {
        guard options.lockScreenDetails else {
            return String(localized: "Time to check your tracker.", comment: "Discreet cycle reminder text (no health details on the lock screen)")
        }
        switch kind {
        case "period_soon":
            return String(localized: "Your period may start in about \(options.daysBefore) days.", comment: "Cycle reminder with details shown")
        case "period_end":
            return String(localized: "Is your period still going? Update your tracker.", comment: "Cycle reminder with details shown")
        default:
            return String(localized: "How are you feeling today?", comment: "Daily cycle log reminder with details shown")
        }
    }
}

/// Owns the `cycle.*` notification requests; never touches other features' requests.
@MainActor
final class CycleNotificationScheduler {
    private let runtime: CycleRuntime
    private let defaults: UserDefaults
    private let center: UNUserNotificationCenter
    var clock: () -> Date = { Date() }

    init(runtime: CycleRuntime? = nil, defaults: UserDefaults = .standard, center: UNUserNotificationCenter = .current()) {
        self.runtime = runtime ?? .shared
        self.defaults = defaults
        self.center = center
    }

    /// The app-wide master switch (`NotificationSettingsView`) and "Show cycle tracking".
    var notificationsWanted: Bool {
        (defaults.object(forKey: "notificationsEnabled") as? Bool ?? false) && CycleSettings.enabled(defaults)
    }

    func replan() async {
        guard runtime.databaseExists || runtime.isOpen, notificationsWanted,
              let repository = await runtime.openRepository(),
              let settings = try? await repository.settings(), settings.setupDone else {
            await removeAll()
            return
        }
        let authorization = await center.notificationSettings().authorizationStatus
        guard authorization != .denied, authorization != .notDetermined else {
            await removeAll()
            return
        }
        let now = clock()
        guard let state = try? await repository.state(today: CycleDates.todayString(now),
                                                       platformPeriods: await CycleStore.loadPlatformPeriods()) else { return }
        let snapshot = CycleEngine.snapshot(state, .shared)
        let planned = CycleReminderPlanner.plan(reminders: snapshot.reminders, options: settings.options, now: now)
        await removeAll()
        for item in planned {
            let content = UNMutableNotificationContent()
            content.title = item.title
            content.body = item.body
            content.sound = .default
            content.userInfo = [CycleReminderPlanner.routeKey: "home"]
            let trigger = UNCalendarNotificationTrigger(dateMatching: item.components, repeats: item.repeats)
            try? await center.add(UNNotificationRequest(identifier: item.identifier, content: content, trigger: trigger))
        }
    }

    func removeAll() async {
        let pending = await center.pendingNotificationRequests().map(\.identifier).filter(CycleReminderPlanner.isCycleIdentifier)
        if !pending.isEmpty { center.removePendingNotificationRequests(withIdentifiers: pending) }
    }
}

/// App-lifetime owner: re-plans on foreground, time-zone and significant time changes, and after writes.
@MainActor
final class CycleReminderRuntime {
    static let shared = CycleReminderRuntime()

    let scheduler: CycleNotificationScheduler
    private var observers: [NSObjectProtocol] = []
    private var debounceTask: Task<Void, Never>?
    private var started = false

    init(scheduler: CycleNotificationScheduler? = nil) {
        self.scheduler = scheduler ?? CycleNotificationScheduler()
    }

    func start() {
        guard !started else { return }
        started = true
        let names: [Notification.Name] = [
            .NSSystemTimeZoneDidChange,
            UIApplication.significantTimeChangeNotification,
            UIApplication.didBecomeActiveNotification,
        ]
        for name in names {
            observers.append(NotificationCenter.default.addObserver(forName: name, object: nil, queue: .main) { [weak self] _ in
                Task { @MainActor [weak self] in self?.replanSoon() }
            })
        }
    }

    func replanSoon() {
        debounceTask?.cancel()
        debounceTask = Task { [weak self] in
            try? await Task.sleep(nanoseconds: 300_000_000)
            guard !Task.isCancelled else { return }
            await self?.replan()
        }
    }

    func replan() async {
        debounceTask?.cancel()
        debounceTask = nil
        await scheduler.replan()
    }
}

extension Notification.Name {
    /// A cycle reminder was tapped: `ContentView` opens Browse › Cycle tracking.
    static let cycleOpenRequested = Notification.Name("Ayuvo.cycleOpenRequested")
}

/// A pending "open cycle tracking" request that survives a cold launch (same pattern as the medication coordinator).
enum CycleRouteCoordinator {
    private static let key = "cycle.openPending"

    @MainActor
    static func request(defaults: UserDefaults = .standard) {
        defaults.set(true, forKey: key)
        NotificationCenter.default.post(name: .cycleOpenRequested, object: nil)
    }

    static func consumePending(defaults: UserDefaults = .standard) -> Bool {
        guard defaults.bool(forKey: key) else { return false }
        defaults.removeObject(forKey: key)
        return true
    }
}
