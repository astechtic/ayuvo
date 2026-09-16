import Foundation
import SQLite3

/// Composite writes that must be atomic (the versioning rule, missed materialization, archive merge)
/// plus the full-row updates the archive merge needs. Each runs inside one `BEGIN IMMEDIATE`
/// transaction on the actor.
extension MedicationsDatabase {
    // MARK: - Full-row updates by id

    func updateSchedule(_ schedule: MedicationSchedule) throws {
        try connection.run(
            """
            UPDATE medication_schedules SET medication_id=?, frequency_kind=?, times_json=?, days_json=?, interval_hours=?, anchor_time=?, reminder_enabled=?, active_from_ms=?, active_until_ms=?, updated_ms=?
            WHERE id=?
            """,
            [
                .text(schedule.medicationID), .text(schedule.frequency.rawValue), .text(schedule.timesJSON), .text(schedule.daysJSON),
                .optionalInt(schedule.intervalHours), .optionalText(schedule.anchorTime), .int(schedule.reminderEnabled ? 1 : 0),
                .int(schedule.activeFromMs), .optionalInt64(schedule.activeUntilMs), .int(schedule.updatedMs), .text(schedule.id),
            ]
        )
    }

    func updateDoseLog(_ log: DoseLog) throws {
        try connection.run(
            """
            UPDATE dose_logs SET medication_id=?, schedule_id=?, scheduled_at_ms=?, status=?, taken_at_ms=?, snoozed_until_ms=?, dose_quantity=?, dose_unit=?, note=?, updated_ms=?
            WHERE id=?
            """,
            [
                .text(log.medicationID), .optionalText(log.scheduleID), .int(log.scheduledAtMs), .text(log.status.rawValue),
                .optionalInt64(log.takenAtMs), .optionalInt64(log.snoozedUntilMs), .real(log.doseQuantity), .text(log.doseUnit.rawValue),
                .optionalText(log.note), .int(log.updatedMs), .text(log.id),
            ]
        )
    }

    // MARK: - Whole-table reads (export, planning)

    func allSchedules() throws -> [MedicationSchedule] {
        var rows: [MedicationSchedule] = []
        try connection.query("SELECT \(Self.scheduleColumns) FROM medication_schedules ORDER BY medication_id, active_from_ms, id") {
            rows.append(Self.decodeSchedule($0))
        }
        return rows
    }

    func allDoseLogs() throws -> [DoseLog] {
        var rows: [DoseLog] = []
        try connection.query("SELECT \(Self.doseLogColumns) FROM dose_logs ORDER BY scheduled_at_ms, id") {
            rows.append(Self.decodeDoseLog($0))
        }
        return rows
    }

    // MARK: - Composite writes

    /// New medication plus its open schedule row (none for PRN), atomically.
    func createMedication(_ medication: Medication, schedule: MedicationSchedule?) throws {
        try connection.inTransaction {
            try insertMedication(medication)
            if let schedule { try insertSchedule(schedule) }
        }
    }

    /// One lifecycle change (docs §12): the medication row, optional closing of every open schedule
    /// row at `closeOpenAt`, inserted rows and an in-place reminder toggle, in one transaction.
    func applyLifecycle(medication: Medication, closeOpenAt: Int64?, insert: [MedicationSchedule], setReminder: (scheduleID: String, enabled: Bool, nowMs: Int64)?) throws {
        try connection.inTransaction {
            try updateMedication(medication)
            if let closeOpenAt { try closeSchedules(medicationID: medication.id, at: closeOpenAt) }
            for schedule in insert { try insertSchedule(schedule) }
            if let setReminder { try setReminderEnabled(scheduleID: setReminder.scheduleID, enabled: setReminder.enabled, nowMs: setReminder.nowMs) }
        }
    }

    /// Upserts every row (missed materialization) and advances the cursor, atomically.
    func applyDoseLogs(_ logs: [DoseLog], missedCursorMs: Int64?) throws {
        try connection.inTransaction {
            for log in logs { try upsertDoseLog(log) }
            if let missedCursorMs { try setMeta(MedicationsDatabase.missedCursorKey, "\(missedCursorMs)") }
        }
    }

    nonisolated static let missedCursorKey = "missed_materialized_until_ms"
    nonisolated static let lastPlannedKey = "last_planned_ms"

    nonisolated enum MergeOp: Sendable { case insert, update }

    /// Archive merge (docs §14): medications first, then schedules, then logs, in one transaction.
    func applyMerge(medications: [(MergeOp, Medication)], schedules: [(MergeOp, MedicationSchedule)], logs: [(MergeOp, DoseLog)]) throws {
        try connection.inTransaction {
            for (op, m) in medications {
                switch op {
                case .insert: try insertMedication(m)
                case .update: try updateMedication(m)
                }
            }
            for (op, s) in schedules {
                switch op {
                case .insert: try insertSchedule(s)
                case .update: try updateSchedule(s)
                }
            }
            for (op, l) in logs {
                switch op {
                case .insert: try upsertDoseLog(l)
                case .update: try updateDoseLog(l)
                }
            }
        }
    }
}
