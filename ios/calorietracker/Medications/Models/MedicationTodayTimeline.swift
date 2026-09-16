import Foundation

/// Typed mirror of the reference's `today_timeline` output (docs §8) plus the medications the
/// rows refer to, so views never need a second lookup.
nonisolated struct MedicationTodayTimeline: Hashable, Sendable {
    nonisolated struct Summary: Hashable, Sendable {
        var total = 0
        var taken = 0
        var upcoming = 0
        var due = 0
        var snoozed = 0
        var missed = 0
        var skipped = 0
    }

    nonisolated enum Kind: String, Hashable, Sendable {
        case scheduled
        case prn
    }

    /// One dose row of the day. `id` = `medications.today.<medication id>.<scheduled_at_ms>` (docs §19).
    nonisolated struct Item: Identifiable, Hashable, Sendable {
        var medicationID: String
        var scheduleID: String?
        var scheduledAtMs: Int64
        var status: DoseStatus
        var isLate: Bool
        var logID: String?
        var snoozedUntilMs: Int64?
        var doseQuantity: Double?
        var doseUnit: DoseUnit?
        var kind: Kind

        var id: String { "\(medicationID).\(scheduledAtMs)" + (kind == .prn ? ".\(logID ?? "")" : "") }

        var occurrence: DoseOccurrence {
            DoseOccurrence(medicationID: medicationID, scheduleID: scheduleID, scheduledAtMs: scheduledAtMs)
        }
    }

    nonisolated struct Slot: Identifiable, Hashable, Sendable {
        /// `HH:mm` local wall-clock.
        var slot: String
        var items: [Item]
        var id: String { slot }
    }

    nonisolated struct PRNRow: Identifiable, Hashable, Sendable {
        var medicationID: String
        var todayCount: Int
        var lastTakenMs: Int64?
        var id: String { medicationID }
    }

    /// `yyyy-MM-dd` of the day.
    var date: String
    var summary: Summary
    var slots: [Slot]
    var prn: [PRNRow]
    /// Every medication referenced by `slots` / `prn`, keyed by id.
    var medications: [String: Medication]

    static let empty = MedicationTodayTimeline(date: "", summary: Summary(), slots: [], prn: [], medications: [:])

    var isEmpty: Bool { slots.isEmpty && prn.isEmpty }

    func medication(_ id: String) -> Medication? { medications[id] }

    /// Builds the typed timeline from the reference's JSON result.
    init(rj: RJ, medications: [Medication]) {
        date = rj["date"].string ?? ""
        let s = rj["summary"]
        summary = Summary(
            total: MR.int(s["total"]) ?? 0, taken: MR.int(s["taken"]) ?? 0, upcoming: MR.int(s["upcoming"]) ?? 0,
            due: MR.int(s["due"]) ?? 0, snoozed: MR.int(s["snoozed"]) ?? 0, missed: MR.int(s["missed"]) ?? 0,
            skipped: MR.int(s["skipped"]) ?? 0
        )
        slots = (rj["groups"].array ?? []).map { group in
            Slot(slot: group["slot"].string ?? "", items: (group["items"].array ?? []).compactMap { it in
                guard let medicationID = it["medication_id"].string, let scheduledAt = MR.int(it["scheduled_at_ms"]) else { return nil }
                return Item(
                    medicationID: medicationID,
                    scheduleID: it["schedule_id"].string,
                    scheduledAtMs: Int64(scheduledAt),
                    status: DoseStatus(raw: it["status"].string),
                    isLate: it["is_late"].bool ?? false,
                    logID: it["log_id"].string,
                    snoozedUntilMs: MR.int(it["snoozed_until_ms"]).map(Int64.init),
                    doseQuantity: it["dose_quantity"].double,
                    doseUnit: it["dose_unit"].string.map { DoseUnit(raw: $0) },
                    kind: Kind(rawValue: it["kind"].string ?? "") ?? .scheduled
                )
            })
        }
        prn = (rj["prn"].array ?? []).compactMap { row in
            guard let medicationID = row["medication_id"].string else { return nil }
            return PRNRow(medicationID: medicationID, todayCount: MR.int(row["today_count"]) ?? 0,
                          lastTakenMs: MR.int(row["last_taken_ms"]).map(Int64.init))
        }
        self.medications = Dictionary(medications.map { ($0.id, $0) }, uniquingKeysWith: { a, _ in a })
    }

    init(date: String, summary: Summary, slots: [Slot], prn: [PRNRow], medications: [String: Medication]) {
        self.date = date
        self.summary = summary
        self.slots = slots
        self.prn = prn
        self.medications = medications
    }
}

/// One scheduled occurrence the user acts on (docs §11).
nonisolated struct DoseOccurrence: Hashable, Sendable {
    var medicationID: String
    var scheduleID: String?
    var scheduledAtMs: Int64

    var rj: RJ {
        .obj(["medication_id": .str(medicationID), "schedule_id": RJ.string(scheduleID), "scheduled_at_ms": .int(Int(scheduledAtMs))])
    }
}

/// Derived status of one occurrence (`resolve_dose_status`).
nonisolated struct DoseResolution: Hashable, Sendable {
    var status: DoseStatus
    var deadlineMs: Int64
    var isLate: Bool
}

/// `adherence` result (docs §9): "18 / 21 doses taken · 86%".
nonisolated struct MedicationAdherence: Hashable, Sendable {
    var taken: Int
    var expected: Int
    var percent: Int
    var hasData: Bool

    static let none = MedicationAdherence(taken: 0, expected: 0, percent: 0, hasData: false)

    init(taken: Int, expected: Int, percent: Int, hasData: Bool) {
        self.taken = taken
        self.expected = expected
        self.percent = percent
        self.hasData = hasData
    }

    init(rj: RJ) {
        self.init(taken: MR.int(rj["taken"]) ?? 0, expected: MR.int(rj["expected"]) ?? 0,
                  percent: MR.int(rj["percent"]) ?? 0, hasData: rj["has_data"].bool ?? false)
    }
}

/// Outcome of a dose action / PRN log (docs §11).
nonisolated struct DoseActionOutcome: Hashable, Sendable {
    var ok: Bool
    /// Reference error code (`already_taken`, `not_due_yet`, `dose_missed`, …) when `ok == false`.
    var error: String?
    var log: DoseLog?

    static func failure(_ error: String) -> DoseActionOutcome { DoseActionOutcome(ok: false, error: error, log: nil) }
}

/// Medication detail screen model.
nonisolated struct MedicationDetail: Hashable, Sendable {
    var medication: Medication
    /// The open schedule row, if any.
    var schedule: MedicationSchedule?
    /// Every schedule version, oldest first.
    var scheduleHistory: [MedicationSchedule]
    var adherence: MedicationAdherence
    /// Newest first.
    var recentLogs: [DoseLog]
    /// The linked Health Record when it still exists.
    var relatedRecord: HealthRecord?
    /// `true` when `medication.relatedRecordID` is set but the record could not be found.
    var relatedRecordMissing: Bool
}

/// Result of `importArchive` / `merge_archive` (docs §14).
nonisolated struct MedicationImportResult: Hashable, Sendable {
    var insertedMedications = 0
    var updatedMedications = 0
    var insertedSchedules = 0
    var updatedSchedules = 0
    var insertedLogs = 0
    var updatedLogs = 0
    var skipped = 0

    var totalChanges: Int { insertedMedications + updatedMedications + insertedSchedules + updatedSchedules + insertedLogs + updatedLogs }
}
