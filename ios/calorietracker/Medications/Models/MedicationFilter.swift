import Foundation

/// Chips on the Medications home list.
nonisolated enum MedicationFilter: String, CaseIterable, Codable, Sendable, Identifiable {
    case active
    case paused
    case completed
    case stopped
    case all

    var id: String { rawValue }

    var title: String {
        switch self {
        case .active: String(localized: "Active")
        case .paused: String(localized: "Paused")
        case .completed: String(localized: "Completed")
        case .stopped: String(localized: "Stopped")
        case .all: String(localized: "All")
        }
    }

    /// The `medications.status` value this chip selects; `nil` = every status.
    var status: MedicationStatus? {
        switch self {
        case .active: .active
        case .paused: .paused
        case .completed: .completed
        case .stopped: .stopped
        case .all: nil
        }
    }
}
