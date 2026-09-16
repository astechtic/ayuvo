import Foundation

extension Notification.Name {
    /// Posted after `MedicationCoordinator.request`; `ContentView` drains the pending route.
    static let medicationRouteRequested = Notification.Name("Ayuvo.medicationRouteRequested")
    /// Posted by the notification-action handler after it changed a dose log in the background,
    /// so a live `MedicationStore` reloads and re-plans.
    static let medicationDoseDidChange = Notification.Name("Ayuvo.medicationDoseDidChange")
}

/// One-shot hand-off from a medication notification tap (or an `ayuvo://medications` URL) to the
/// Meds segment, mirroring `QuickActionCoordinator`: the pending route survives a cold launch
/// in UserDefaults and is consumed by `ContentView.consumePendingLaunchRoutes()`.
enum MedicationCoordinator {
    /// Sentinel stored when the request only asks to open the Meds segment.
    private static let openSegmentValue = "*"

    /// `medicationID == nil` opens the Meds segment; an id also pushes that medication's detail.
    @MainActor
    static func request(medicationID: String?, defaults: UserDefaults = .standard) {
        defaults.set(medicationID ?? openSegmentValue, forKey: MedicationSettings.pendingRouteKey)
        NotificationCenter.default.post(name: .medicationRouteRequested, object: medicationID)
    }

    /// `nil` = nothing pending; `.some(nil)` = open the Meds segment; `.some(id)` = open that medication.
    static func consumePending(defaults: UserDefaults = .standard) -> String?? {
        defer { defaults.removeObject(forKey: MedicationSettings.pendingRouteKey) }
        guard let raw = defaults.string(forKey: MedicationSettings.pendingRouteKey) else { return nil }
        return .some(raw == openSegmentValue ? nil : raw)
    }
}
