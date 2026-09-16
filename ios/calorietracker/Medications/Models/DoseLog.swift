import Foundation

/// Dose status (docs §7). `scheduled` and `due` are derived and never stored; `dose_logs.status`
/// only ever holds `taken`, `skipped`, `missed` or `snoozed`.
nonisolated enum DoseStatus: String, CaseIterable, Codable, Sendable, Identifiable {
    case scheduled
    case due
    case taken
    case skipped
    case missed
    case snoozed

    var id: String { rawValue }

    init(raw: String?) {
        self = raw.flatMap(DoseStatus.init(rawValue:)) ?? .scheduled
    }

    /// Statuses that may be persisted in `dose_logs`.
    static let stored: Set<DoseStatus> = [.taken, .skipped, .missed, .snoozed]

    /// Statuses that end an occurrence (no reminder, counted in adherence).
    var isTerminal: Bool { self == .taken || self == .skipped || self == .missed }

    var title: String {
        switch self {
        case .scheduled: String(localized: "Scheduled")
        case .due: String(localized: "Due")
        case .taken: String(localized: "Taken")
        case .skipped: String(localized: "Skipped")
        case .missed: String(localized: "Missed")
        case .snoozed: String(localized: "Snoozed")
        }
    }

    /// Icon + text together, never colour alone (docs §16).
    var systemImage: String {
        switch self {
        case .scheduled: "circle.dashed"
        case .due: "bell"
        case .taken: "checkmark.circle"
        case .skipped: "forward"
        case .missed: "exclamationmark.triangle"
        case .snoozed: "clock"
        }
    }
}

/// User actions on one occurrence (docs §11). `undo` deletes the user's own taken/skipped/snoozed row.
nonisolated enum DoseAction: String, CaseIterable, Codable, Sendable {
    case taken
    case skipped
    case snoozed
    case undo
}

/// One row of `dose_logs`. PRN doses have `scheduleID == nil` and `scheduledAtMs == takenAtMs`.
/// `doseQuantity`/`doseUnit` snapshot the medication at the time of the dose so history survives edits.
nonisolated struct DoseLog: Identifiable, Hashable, Sendable, Codable {
    var id: String
    var medicationID: String
    var scheduleID: String?
    var scheduledAtMs: Int64
    var status: DoseStatus
    var takenAtMs: Int64?
    var snoozedUntilMs: Int64?
    var doseQuantity: Double
    var doseUnit: DoseUnit
    var note: String?
    var createdMs: Int64
    var updatedMs: Int64

    init(
        id: String = UUID().uuidString.lowercased(),
        medicationID: String,
        scheduleID: String? = nil,
        scheduledAtMs: Int64,
        status: DoseStatus,
        takenAtMs: Int64? = nil,
        snoozedUntilMs: Int64? = nil,
        doseQuantity: Double,
        doseUnit: DoseUnit,
        note: String? = nil,
        createdMs: Int64,
        updatedMs: Int64
    ) {
        self.id = id
        self.medicationID = medicationID
        self.scheduleID = scheduleID
        self.scheduledAtMs = scheduledAtMs
        self.status = status
        self.takenAtMs = takenAtMs
        self.snoozedUntilMs = snoozedUntilMs
        self.doseQuantity = doseQuantity
        self.doseUnit = doseUnit
        self.note = note
        self.createdMs = createdMs
        self.updatedMs = updatedMs
    }

    var isPRN: Bool { scheduleID == nil }
}
