import Foundation
import UIKit

/// App-lifetime owner of the medication reminder scheduler. Installs the system observers that
/// must re-plan without any SwiftUI view being alive (time-zone change, significant time change,
/// foreground) and offers a debounced `replanSoon()` for chatty callers.
@MainActor
final class MedicationReminderRuntime {
    static let shared = MedicationReminderRuntime()

    let scheduler: MedicationNotificationScheduler
    private var observers: [NSObjectProtocol] = []
    private var debounceTask: Task<Void, Never>?
    private var started = false

    static let debounceNanoseconds: UInt64 = 300_000_000

    init(scheduler: MedicationNotificationScheduler? = nil) {
        self.scheduler = scheduler ?? MedicationNotificationScheduler()
    }

    /// Installs the observers once (`AppDelegate.didFinishLaunching`).
    func start() {
        guard !started else { return }
        started = true
        let center = NotificationCenter.default
        let names: [Notification.Name] = [
            .NSSystemTimeZoneDidChange,
            UIApplication.significantTimeChangeNotification,
            UIApplication.didBecomeActiveNotification,
        ]
        for name in names {
            observers.append(center.addObserver(forName: name, object: nil, queue: .main) { [weak self] _ in
                Task { @MainActor in self?.replanSoon() }
            })
        }
    }

    func stop() {
        observers.forEach(NotificationCenter.default.removeObserver)
        observers.removeAll()
        started = false
    }

    /// Coalesces bursts (store writes, foreground + time change) into one replan.
    func replanSoon() {
        debounceTask?.cancel()
        debounceTask = Task { [weak self] in
            try? await Task.sleep(nanoseconds: MedicationReminderRuntime.debounceNanoseconds)
            guard !Task.isCancelled else { return }
            await self?.replan()
        }
    }

    func replan() async {
        debounceTask?.cancel()
        debounceTask = nil
        await scheduler.replan()
    }

    func cancelAll() async {
        debounceTask?.cancel()
        debounceTask = nil
        await scheduler.cancelAll()
    }
}
