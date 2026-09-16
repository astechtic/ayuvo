import Foundation
import Testing
@testable import calorietracker

/// Pending-route hand-off from a notification tap to `ContentView` (same shape as `QuickActionCoordinator`).
@MainActor
struct MedicationCoordinatorTests {
    private func makeDefaults() throws -> (UserDefaults, String) {
        let suite = "MedicationCoordinatorTests.\(UUID().uuidString)"
        let defaults = try #require(UserDefaults(suiteName: suite))
        defaults.removePersistentDomain(forName: suite)
        return (defaults, suite)
    }

    @Test func nothingPendingByDefault() throws {
        let (defaults, suite) = try makeDefaults()
        defer { defaults.removePersistentDomain(forName: suite) }
        #expect(MedicationCoordinator.consumePending(defaults: defaults) == nil)
    }

    @Test func requestWithoutIDOpensTheSegmentOnce() throws {
        let (defaults, suite) = try makeDefaults()
        defer { defaults.removePersistentDomain(forName: suite) }
        MedicationCoordinator.request(medicationID: nil, defaults: defaults)
        let pending = MedicationCoordinator.consumePending(defaults: defaults)
        #expect(pending == .some(nil))
        #expect(MedicationCoordinator.consumePending(defaults: defaults) == nil, "consumed exactly once")
    }

    @Test func requestWithIDPushesThatMedication() throws {
        let (defaults, suite) = try makeDefaults()
        defer { defaults.removePersistentDomain(forName: suite) }
        MedicationCoordinator.request(medicationID: "med-42", defaults: defaults)
        #expect(defaults.string(forKey: MedicationSettings.pendingRouteKey) == "med-42")
        #expect(MedicationCoordinator.consumePending(defaults: defaults) == .some("med-42"))
        #expect(defaults.object(forKey: MedicationSettings.pendingRouteKey) == nil)
    }

    @Test func requestPostsTheRouteNotification() async throws {
        let (defaults, suite) = try makeDefaults()
        defer { defaults.removePersistentDomain(forName: suite) }
        await confirmation("route requested") { confirm in
            let token = NotificationCenter.default.addObserver(forName: .medicationRouteRequested, object: nil, queue: nil) { note in
                if note.object as? String == "med-7" { confirm() }
            }
            MedicationCoordinator.request(medicationID: "med-7", defaults: defaults)
            NotificationCenter.default.removeObserver(token)
        }
        _ = MedicationCoordinator.consumePending(defaults: defaults)
    }
}
