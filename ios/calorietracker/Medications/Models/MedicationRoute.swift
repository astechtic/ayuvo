import Foundation

/// Pushes on the Health tab's stack (and, for `importFromRecord`, on the Records stack).
/// The `medicationRouteDestinations()` view extension is defined next to the views.
nonisolated enum MedicationRoute: Hashable, Sendable {
    case detail(String)
    /// Dose history, optionally for one medication.
    case history(String?)
    /// "Add from prescription" for a Health Record id.
    case importFromRecord(String)
}
