import Foundation
import SQLite3

/// Typed reads and writes over the three tables. Every SQL string here is one of the queries
/// listed in the contract (docs §4) so the indexes serve them. Composite operations that must
/// be atomic wrap several calls in `inTransaction`.
extension MedicationsDatabase {
    // MARK: - Column lists and decoders

    nonisolated static let medicationColumns = "id, name, generic_name, brand_name, strength, form, dose_quantity, dose_unit, food_relation, instructions, start_date, end_date, status, is_prn, photo_path, related_record_id, created_ms, updated_ms"

    nonisolated static let scheduleColumns = "id, medication_id, frequency_kind, times_json, days_json, interval_hours, anchor_time, reminder_enabled, active_from_ms, active_until_ms, created_ms, updated_ms"

    nonisolated static let doseLogColumns = "id, medication_id, schedule_id, scheduled_at_ms, status, taken_at_ms, snoozed_until_ms, dose_quantity, dose_unit, note, created_ms, updated_ms"

    nonisolated static func decodeMedication(_ s: HealthDBStatement) -> Medication {
        Medication(
            id: s.text(0) ?? "",
            name: s.text(1) ?? "",
            genericName: s.text(2),
            brandName: s.text(3),
            strength: s.text(4),
            form: MedicationForm(raw: s.text(5)),
            doseQuantity: s.double(6) ?? 1,
            doseUnit: DoseUnit(raw: s.text(7)),
            foodRelation: FoodRelation(raw: s.text(8)),
            instructions: s.text(9),
            startDate: s.text(10) ?? "",
            endDate: s.text(11),
            status: MedicationStatus(raw: s.text(12)),
            isPRN: (s.int(13) ?? 0) != 0,
            photoPath: s.text(14),
            relatedRecordID: s.text(15),
            createdMs: s.int64(16) ?? 0,
            updatedMs: s.int64(17) ?? 0
        )
    }

    nonisolated static func decodeSchedule(_ s: HealthDBStatement) -> MedicationSchedule {
        MedicationSchedule(
            id: s.text(0) ?? "",
            medicationID: s.text(1) ?? "",
            frequency: ScheduleFrequency(raw: s.text(2)),
            times: MedicationJSON.decodeStrings(s.text(3)),
            days: MedicationJSON.decodeInts(s.text(4)),
            intervalHours: s.int(5),
            anchorTime: s.text(6),
            reminderEnabled: (s.int(7) ?? 1) != 0,
            activeFromMs: s.int64(8) ?? 0,
            activeUntilMs: s.int64(9),
            createdMs: s.int64(10) ?? 0,
            updatedMs: s.int64(11) ?? 0
        )
    }

    nonisolated static func decodeDoseLog(_ s: HealthDBStatement) -> DoseLog {
        DoseLog(
            id: s.text(0) ?? "",
            medicationID: s.text(1) ?? "",
            scheduleID: s.text(2),
            scheduledAtMs: s.int64(3) ?? 0,
            status: DoseStatus(raw: s.text(4)),
            takenAtMs: s.int64(5),
            snoozedUntilMs: s.int64(6),
            doseQuantity: s.double(7) ?? 1,
            doseUnit: DoseUnit(raw: s.text(8)),
            note: s.text(9),
            createdMs: s.int64(10) ?? 0,
            updatedMs: s.int64(11) ?? 0
        )
    }

    /// `%`, `_` and `\` escaped for `LIKE ? ESCAPE '\'`.
    nonisolated static func likePattern(_ text: String) -> String {
        var escaped = ""
        for character in text {
            if character == "\\" || character == "%" || character == "_" { escaped.append("\\") }
            escaped.append(character)
        }
        return "%\(escaped)%"
    }

    // MARK: - Medications

    func insertMedication(_ medication: Medication) throws {
        try connection.run(
            """
            INSERT INTO medications (\(Self.medicationColumns))
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            Self.medicationValues(medication)
        )
    }

    /// Full-row update by id (`created_ms` is kept as stored).
    func updateMedication(_ medication: Medication) throws {
        try connection.run(
            """
            UPDATE medications SET name=?, generic_name=?, brand_name=?, strength=?, form=?, dose_quantity=?, dose_unit=?, food_relation=?, instructions=?, start_date=?, end_date=?, status=?, is_prn=?, photo_path=?, related_record_id=?, updated_ms=?
            WHERE id=?
            """,
            [
                .text(medication.name), .optionalText(medication.genericName), .optionalText(medication.brandName),
                .optionalText(medication.strength), .text(medication.form.rawValue), .real(medication.doseQuantity),
                .text(medication.doseUnit.rawValue), .text(medication.foodRelation.rawValue), .optionalText(medication.instructions),
                .text(medication.startDate), .optionalText(medication.endDate), .text(medication.status.rawValue),
                .int(medication.isPRN ? 1 : 0), .optionalText(medication.photoPath), .optionalText(medication.relatedRecordID),
                .int(medication.updatedMs), .text(medication.id),
            ]
        )
    }

    func setMedicationStatus(id: String, status: MedicationStatus, nowMs: Int64) throws {
        try connection.run("UPDATE medications SET status=?, updated_ms=? WHERE id=?", [.text(status.rawValue), .int(nowMs), .text(id)])
    }

    func medication(id: String) throws -> Medication? {
        var result: Medication?
        try connection.query("SELECT \(Self.medicationColumns) FROM medications WHERE id=?", [.text(id)]) {
            result = Self.decodeMedication($0)
        }
        return result
    }

    /// Filter + search served by `idx_medications_status` (contract §4).
    func medications(status: MedicationStatus? = nil, search: String? = nil) throws -> [Medication] {
        var clauses: [String] = []
        var values: [SQLValue] = []
        if let status {
            clauses.append("status = ?")
            values.append(.text(status.rawValue))
        }
        let trimmed = (search ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        if !trimmed.isEmpty {
            let pattern = Self.likePattern(trimmed)
            clauses.append("(name LIKE ? ESCAPE '\\' OR brand_name LIKE ? ESCAPE '\\' OR generic_name LIKE ? ESCAPE '\\')")
            values.append(contentsOf: [.text(pattern), .text(pattern), .text(pattern)])
        }
        let whereSQL = clauses.isEmpty ? "" : " WHERE " + clauses.joined(separator: " AND ")
        var rows: [Medication] = []
        try connection.query("SELECT \(Self.medicationColumns) FROM medications\(whereSQL) ORDER BY name COLLATE NOCASE, id", values) {
            rows.append(Self.decodeMedication($0))
        }
        return rows
    }

    func medications(relatedRecordID: String) throws -> [Medication] {
        var rows: [Medication] = []
        try connection.query("SELECT \(Self.medicationColumns) FROM medications WHERE related_record_id=? ORDER BY name COLLATE NOCASE, id", [.text(relatedRecordID)]) {
            rows.append(Self.decodeMedication($0))
        }
        return rows
    }

    func allMedications() throws -> [Medication] {
        try medications(status: nil, search: nil)
    }

    func medicationCount() throws -> Int {
        Int(try connection.scalarInt64("SELECT COUNT(*) FROM medications") ?? 0)
    }

    func countsByStatus() throws -> [MedicationStatus: Int] {
        var counts: [MedicationStatus: Int] = [:]
        try connection.query("SELECT status, COUNT(*) FROM medications GROUP BY status") { s in
            counts[MedicationStatus(raw: s.text(0))] = s.int(1) ?? 0
        }
        return counts
    }

    /// Schedules and dose logs cascade.
    @discardableResult
    func deleteMedication(id: String) throws -> Bool {
        try connection.run("DELETE FROM medications WHERE id=?", [.text(id)])
        return connection.changes > 0
    }

    // MARK: - Schedules

    func insertSchedule(_ schedule: MedicationSchedule) throws {
        try connection.run(
            """
            INSERT INTO medication_schedules (\(Self.scheduleColumns))
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            [
                .text(schedule.id), .text(schedule.medicationID), .text(schedule.frequency.rawValue),
                .text(schedule.timesJSON), .text(schedule.daysJSON), .optionalInt(schedule.intervalHours),
                .optionalText(schedule.anchorTime), .int(schedule.reminderEnabled ? 1 : 0),
                .int(schedule.activeFromMs), .optionalInt64(schedule.activeUntilMs),
                .int(schedule.createdMs), .int(schedule.updatedMs),
            ]
        )
    }

    /// Inserts several rows atomically (resume copies a whole generation).
    func insertSchedules(_ schedules: [MedicationSchedule]) throws {
        try connection.inTransaction {
            for schedule in schedules {
                try insertSchedule(schedule)
            }
        }
    }

    /// The versioning rule in one transaction: close the open rows at `ms`, then insert `replacements`.
    func replaceOpenSchedules(medicationID: String, at ms: Int64, with replacements: [MedicationSchedule]) throws {
        try connection.inTransaction {
            try closeSchedules(medicationID: medicationID, at: ms)
            for schedule in replacements {
                try insertSchedule(schedule)
            }
        }
    }

    /// Closes every open row of the medication at `ms` (the versioning rule). Returns the number closed.
    @discardableResult
    func closeSchedules(medicationID: String, at ms: Int64) throws -> Int {
        try connection.run(
            "UPDATE medication_schedules SET active_until_ms=?, updated_ms=? WHERE medication_id=? AND active_until_ms IS NULL",
            [.int(ms), .int(ms), .text(medicationID)]
        )
        return connection.changes
    }

    func setReminderEnabled(scheduleID: String, enabled: Bool, nowMs: Int64) throws {
        try connection.run(
            "UPDATE medication_schedules SET reminder_enabled=?, updated_ms=? WHERE id=?",
            [.int(enabled ? 1 : 0), .int(nowMs), .text(scheduleID)]
        )
    }

    func schedule(id: String) throws -> MedicationSchedule? {
        var result: MedicationSchedule?
        try connection.query("SELECT \(Self.scheduleColumns) FROM medication_schedules WHERE id=?", [.text(id)]) {
            result = Self.decodeSchedule($0)
        }
        return result
    }

    /// Versions of one medication, oldest first (`idx_schedules_medication`).
    func schedules(medicationID: String, includeEnded: Bool) throws -> [MedicationSchedule] {
        let sql = includeEnded
            ? "SELECT \(Self.scheduleColumns) FROM medication_schedules WHERE medication_id=? ORDER BY active_from_ms, id"
            : "SELECT \(Self.scheduleColumns) FROM medication_schedules WHERE medication_id=? AND active_until_ms IS NULL ORDER BY active_from_ms, id"
        var rows: [MedicationSchedule] = []
        try connection.query(sql, [.text(medicationID)]) { rows.append(Self.decodeSchedule($0)) }
        return rows
    }

    /// Every open row across medications (`idx_schedules_open`), for planning.
    func openSchedules() throws -> [MedicationSchedule] {
        var rows: [MedicationSchedule] = []
        try connection.query("SELECT \(Self.scheduleColumns) FROM medication_schedules WHERE active_until_ms IS NULL ORDER BY medication_id, active_from_ms, id") {
            rows.append(Self.decodeSchedule($0))
        }
        return rows
    }

    /// Every schedule row (all versions) whose active window overlaps `[fromMs, toMs)`; used by adherence/history.
    func schedules(overlapping fromMs: Int64, _ toMs: Int64) throws -> [MedicationSchedule] {
        var rows: [MedicationSchedule] = []
        try connection.query(
            "SELECT \(Self.scheduleColumns) FROM medication_schedules WHERE active_from_ms < ? AND (active_until_ms IS NULL OR active_until_ms > ?) ORDER BY medication_id, active_from_ms, id",
            [.int(toMs), .int(fromMs)]
        ) { rows.append(Self.decodeSchedule($0)) }
        return rows
    }

    // MARK: - Dose logs

    /// UPDATE-then-INSERT keyed on `(schedule_id, scheduled_at_ms)` for scheduled doses (minSdk parity: no UPSERT);
    /// PRN rows (`scheduleID == nil`) are always inserted. Returns the stored row (an existing row keeps its
    /// `id` and `created_ms`).
    @discardableResult
    func upsertDoseLog(_ log: DoseLog) throws -> DoseLog {
        if let scheduleID = log.scheduleID {
            try connection.run(
                """
                UPDATE dose_logs SET medication_id=?, status=?, taken_at_ms=?, snoozed_until_ms=?, dose_quantity=?, dose_unit=?, note=?, updated_ms=?
                WHERE schedule_id=? AND scheduled_at_ms=?
                """,
                [
                    .text(log.medicationID), .text(log.status.rawValue), .optionalInt64(log.takenAtMs), .optionalInt64(log.snoozedUntilMs),
                    .real(log.doseQuantity), .text(log.doseUnit.rawValue), .optionalText(log.note), .int(log.updatedMs),
                    .text(scheduleID), .int(log.scheduledAtMs),
                ]
            )
            if connection.changes > 0 {
                return try doseLog(scheduleID: scheduleID, scheduledAtMs: log.scheduledAtMs) ?? log
            }
        }
        try connection.run(
            """
            INSERT INTO dose_logs (\(Self.doseLogColumns))
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            [
                .text(log.id), .text(log.medicationID), .optionalText(log.scheduleID), .int(log.scheduledAtMs),
                .text(log.status.rawValue), .optionalInt64(log.takenAtMs), .optionalInt64(log.snoozedUntilMs),
                .real(log.doseQuantity), .text(log.doseUnit.rawValue), .optionalText(log.note),
                .int(log.createdMs), .int(log.updatedMs),
            ]
        )
        return log
    }

    @discardableResult
    func deleteDoseLog(id: String) throws -> Bool {
        try connection.run("DELETE FROM dose_logs WHERE id=?", [.text(id)])
        return connection.changes > 0
    }

    func doseLog(id: String) throws -> DoseLog? {
        var result: DoseLog?
        try connection.query("SELECT \(Self.doseLogColumns) FROM dose_logs WHERE id=?", [.text(id)]) {
            result = Self.decodeDoseLog($0)
        }
        return result
    }

    func doseLog(scheduleID: String, scheduledAtMs: Int64) throws -> DoseLog? {
        var result: DoseLog?
        try connection.query(
            "SELECT \(Self.doseLogColumns) FROM dose_logs WHERE schedule_id=? AND scheduled_at_ms=?",
            [.text(scheduleID), .int(scheduledAtMs)]
        ) { result = Self.decodeDoseLog($0) }
        return result
    }

    /// Rows with `fromMs <= scheduled_at_ms < toMs` (`idx_dose_logs_scheduled`, or `idx_dose_logs_medication`).
    func doseLogs(medicationID: String? = nil, from fromMs: Int64, to toMs: Int64) throws -> [DoseLog] {
        var rows: [DoseLog] = []
        if let medicationID {
            try connection.query(
                "SELECT \(Self.doseLogColumns) FROM dose_logs WHERE medication_id=? AND scheduled_at_ms >= ? AND scheduled_at_ms < ? ORDER BY scheduled_at_ms, id",
                [.text(medicationID), .int(fromMs), .int(toMs)]
            ) { rows.append(Self.decodeDoseLog($0)) }
        } else {
            try connection.query(
                "SELECT \(Self.doseLogColumns) FROM dose_logs WHERE scheduled_at_ms >= ? AND scheduled_at_ms < ? ORDER BY scheduled_at_ms, id",
                [.int(fromMs), .int(toMs)]
            ) { rows.append(Self.decodeDoseLog($0)) }
        }
        return rows
    }

    nonisolated struct DoseLogCursor: Sendable, Hashable {
        let scheduledAtMs: Int64
        let id: String

        init(scheduledAtMs: Int64, id: String) {
            self.scheduledAtMs = scheduledAtMs
            self.id = id
        }

        init(_ log: DoseLog) {
            self.init(scheduledAtMs: log.scheduledAtMs, id: log.id)
        }
    }

    /// History page, newest first, keyset on `(scheduled_at_ms DESC, id DESC)`.
    func doseLogPage(medicationID: String? = nil, before cursor: DoseLogCursor? = nil, limit: Int) throws -> [DoseLog] {
        var clauses: [String] = []
        var values: [SQLValue] = []
        if let medicationID {
            clauses.append("medication_id = ?")
            values.append(.text(medicationID))
        }
        if let cursor {
            clauses.append("(scheduled_at_ms < ? OR (scheduled_at_ms = ? AND id < ?))")
            values.append(contentsOf: [.int(cursor.scheduledAtMs), .int(cursor.scheduledAtMs), .text(cursor.id)])
        }
        values.append(.int(Int64(max(1, limit))))
        let whereSQL = clauses.isEmpty ? "" : " WHERE " + clauses.joined(separator: " AND ")
        var rows: [DoseLog] = []
        try connection.query(
            "SELECT \(Self.doseLogColumns) FROM dose_logs\(whereSQL) ORDER BY scheduled_at_ms DESC, id DESC LIMIT ?",
            values
        ) { rows.append(Self.decodeDoseLog($0)) }
        return rows
    }

    /// Pending snoozes for the planner (`idx_dose_logs_status`).
    func snoozedLogs(since ms: Int64) throws -> [DoseLog] {
        var rows: [DoseLog] = []
        try connection.query(
            "SELECT \(Self.doseLogColumns) FROM dose_logs WHERE status='snoozed' AND snoozed_until_ms >= ? ORDER BY snoozed_until_ms, id",
            [.int(ms)]
        ) { rows.append(Self.decodeDoseLog($0)) }
        return rows
    }

    /// Most recent PRN dose per medication (for the "As needed" section).
    func latestPRNLogs() throws -> [String: DoseLog] {
        var latest: [String: DoseLog] = [:]
        try connection.query(
            "SELECT \(Self.doseLogColumns) FROM dose_logs WHERE schedule_id IS NULL AND status='taken' ORDER BY scheduled_at_ms DESC, id DESC"
        ) { s in
            let log = Self.decodeDoseLog(s)
            if latest[log.medicationID] == nil { latest[log.medicationID] = log }
        }
        return latest
    }

    func doseLogCount(medicationID: String? = nil) throws -> Int {
        if let medicationID {
            return Int(try connection.scalarInt64("SELECT COUNT(*) FROM dose_logs WHERE medication_id=?", [.text(medicationID)]) ?? 0)
        }
        return Int(try connection.scalarInt64("SELECT COUNT(*) FROM dose_logs") ?? 0)
    }

    // MARK: - Helpers

    nonisolated private static func medicationValues(_ m: Medication) -> [SQLValue] {
        [
            .text(m.id), .text(m.name), .optionalText(m.genericName), .optionalText(m.brandName), .optionalText(m.strength),
            .text(m.form.rawValue), .real(m.doseQuantity), .text(m.doseUnit.rawValue), .text(m.foodRelation.rawValue),
            .optionalText(m.instructions), .text(m.startDate), .optionalText(m.endDate), .text(m.status.rawValue),
            .int(m.isPRN ? 1 : 0), .optionalText(m.photoPath), .optionalText(m.relatedRecordID),
            .int(m.createdMs), .int(m.updatedMs),
        ]
    }
}
