import Foundation

nonisolated enum MedicationStoreError: Error, Equatable, Sendable {
    case notOpen
    case notFound
    case validation([DraftError])
    /// A reference `lifecycle` error code (`invalid_transition`, `prn_has_schedule`, …).
    case lifecycle(String)
    /// A reference `merge_archive` error code (`bad_format`, `unsupported_version`) or a read failure.
    case archive(String)
}

/// Database + photos together; the only place SQL rows meet the reference logic (`MR`). Sendable;
/// call from any task. All times are epoch ms; `zone` is the device's IANA zone at call time.
nonisolated struct MedicationsRepository: Sendable {
    static let historyPageSize = 60
    /// Default start of the missed-materialization window when the cursor has never been written.
    static let missedLookbackMs: Int64 = 30 * 86_400_000

    let database: MedicationsDatabase
    let photos: MedicationPhotoStore

    // MARK: - Reads

    func medications(status: MedicationStatus?, search: String?) async throws -> [Medication] {
        try await database.medications(status: status, search: search)
    }

    func medication(id: String) async throws -> Medication? {
        try await database.medication(id: id)
    }

    func countsByStatus() async throws -> [MedicationStatus: Int] {
        try await database.countsByStatus()
    }

    func openSchedule(medicationID: String) async throws -> MedicationSchedule? {
        try await database.schedules(medicationID: medicationID, includeEnded: false).first
    }

    func scheduleHistory(medicationID: String) async throws -> [MedicationSchedule] {
        try await database.schedules(medicationID: medicationID, includeEnded: true)
    }

    /// The Today model (docs §8): the day's logs plus each PRN medicine's latest taken row.
    func today(nowMs: Int64, zone: String) async throws -> MedicationTodayTimeline {
        let date = MR.localDateOf(Int(nowMs), zone: zone)
        guard let (start, end) = MR.dayWindow(date, zone: zone) else { return .empty }
        let medications = try await database.allMedications()
        let schedules = try await database.schedules(overlapping: Int64(start), Int64(end))
        var logs = try await database.doseLogs(from: Int64(start), to: Int64(end))
        let known = Set(logs.map(\.id))
        for log in try await database.latestPRNLogs().values.sorted(by: { $0.id < $1.id }) where !known.contains(log.id) {
            logs.append(log)
        }
        let result = MR.todayTimeline(medications: medications.map(\.rj), schedules: schedules.map(\.rj), logs: logs.map(\.rj),
                                      now: Int(nowMs), zone: zone)
        return MedicationTodayTimeline(rj: result, medications: medications)
    }

    /// Rolling 7-day adherence (docs §9) for one medication or all.
    func adherence(medicationID: String?, nowMs: Int64, zone: String) async throws -> MedicationAdherence {
        let end = Int(nowMs) + 1
        let start = Int(nowMs) - MR.adherenceWindowMs
        let medications = try await database.allMedications()
        let schedules = try await database.schedules(overlapping: Int64(start), Int64(end))
        let logs = try await database.doseLogs(medicationID: medicationID, from: Int64(start), to: Int64(end))
        let occurrences = MR.expandAll(medications: medications.map(\.rj), schedules: schedules.map(\.rj),
                                       windowStart: start, windowEnd: end, zone: zone, statuses: nil)
        return MedicationAdherence(rj: MR.adherence(occurrences: occurrences, logs: logs.map(\.rj), now: Int(nowMs), medicationID: medicationID))
    }

    /// History page, newest first (keyset).
    func history(medicationID: String?, before: DoseLog?, limit: Int = MedicationsRepository.historyPageSize) async throws -> [DoseLog] {
        try await database.doseLogPage(medicationID: medicationID, before: before.map(MedicationsDatabase.DoseLogCursor.init), limit: limit)
    }

    /// Reminder plan (docs §10) over every active medication's open rows and pending snoozes.
    func reminderPlan(nowMs: Int64, horizonMs: Int64, zone: String, budget: Int?) async throws -> ReminderPlan {
        let medications = try await database.medications(status: .active, search: nil)
        let schedules = try await database.openSchedules()
        let logs = try await database.doseLogs(from: nowMs - Int64(MR.lateFireMs), to: nowMs + horizonMs)
        let snoozed = try await database.snoozedLogs(since: nowMs - Int64(MR.lateFireMs))
        let known = Set(logs.map(\.id))
        let all = logs + snoozed.filter { !known.contains($0.id) }
        return MR.planReminders(medications: medications, schedules: schedules, logs: all, nowMs: nowMs, horizonMs: horizonMs, zone: zone, budget: budget)
    }

    /// Entries due right now (fire time within `LATE_FIRE_MS` of `nowMs`).
    func dueNow(nowMs: Int64, zone: String) async throws -> [PlannedReminder] {
        let plan = try await reminderPlan(nowMs: nowMs, horizonMs: Int64(MR.lateFireMs), zone: zone, budget: nil)
        return plan.entries.filter { $0.fireAtMs <= nowMs + Int64(MR.lateFireMs) }
    }

    func detail(id: String, nowMs: Int64, zone: String, recentLimit: Int = 10) async throws -> MedicationDetail? {
        guard let medication = try await database.medication(id: id) else { return nil }
        let history = try await scheduleHistory(medicationID: id)
        let adherence = try await adherence(medicationID: id, nowMs: nowMs, zone: zone)
        let recent = try await database.doseLogPage(medicationID: id, before: nil, limit: recentLimit)
        return MedicationDetail(
            medication: medication,
            schedule: history.first(where: \.isOpen),
            scheduleHistory: history,
            adherence: adherence,
            recentLogs: recent,
            relatedRecord: nil,
            relatedRecordMissing: false
        )
    }

    func storageBytes() -> Int64 {
        let databaseBytes = database.url.map { HealthDatabaseLocation.totalSizeBytes(for: $0) } ?? 0
        return databaseBytes + photos.totalSizeBytes()
    }

    // MARK: - Writes

    func create(draft: MedicationDraft, nowMs: Int64, zone: String) async throws -> Medication {
        let errors = draft.validationErrors
        guard errors.isEmpty else { throw MedicationStoreError.validation(errors) }
        let medication = draft.medication(existing: nil, nowMs: nowMs)
        let schedule = draft.schedule(medicationID: medication.id, nowMs: nowMs, zone: zone)
        try await database.createMedication(medication, schedule: schedule)
        return medication
    }

    /// Edits: medication columns always; a changed schedule goes through the reference's
    /// `edit_schedule` (active medicines only), a reminder toggle updates the open row in place,
    /// switching to PRN closes the open row.
    func update(draft: MedicationDraft, existing: Medication, nowMs: Int64) async throws -> Medication {
        let errors = draft.validationErrors
        guard errors.isEmpty else { throw MedicationStoreError.validation(errors) }
        let updated = draft.medication(existing: existing, nowMs: nowMs)
        let open = try await openSchedule(medicationID: existing.id)
        if draft.isPRN {
            try await database.applyLifecycle(medication: updated, closeOpenAt: open == nil ? nil : nowMs, insert: [], setReminder: nil)
            return updated
        }
        if draft.scheduleDiffers(from: open) {
            guard updated.status == .active else { throw MedicationStoreError.lifecycle("invalid_transition") }
            let schedules = try await scheduleHistory(medicationID: existing.id)
            let result = MR.lifecycle(action: "edit_schedule", medication: updated.rj, schedules: schedules.map(\.rj),
                                      now: Int(nowMs), newSchedule: draft.newScheduleRJ)
            let applied = try Self.lifecycleWrites(result, fallback: updated)
            try await database.applyLifecycle(medication: applied.medication, closeOpenAt: applied.closeOpenAt, insert: applied.inserts,
                                              setReminder: applied.setReminder.map { ($0.0, $0.1, nowMs) })
            return applied.medication
        }
        if let open, open.reminderEnabled != draft.reminderEnabled {
            try await database.applyLifecycle(medication: updated, closeOpenAt: nil, insert: [], setReminder: (open.id, draft.reminderEnabled, nowMs))
            return updated
        }
        try await database.updateMedication(updated)
        return updated
    }

    /// `pause` / `resume` / `stop` / `complete` through the reference (docs §12).
    @discardableResult
    func lifecycle(_ action: LifecycleAction, id: String, nowMs: Int64) async throws -> Medication {
        try await runLifecycle(action.rawValue, id: id, nowMs: nowMs, newSchedule: .null)
    }

    /// Toggles reminders on the open row without versioning it.
    @discardableResult
    func setReminderEnabled(id: String, enabled: Bool, nowMs: Int64) async throws -> Medication {
        try await runLifecycle("set_reminder_enabled", id: id, nowMs: nowMs, newSchedule: .obj(["reminder_enabled": .int(enabled ? 1 : 0)]))
    }

    private func runLifecycle(_ action: String, id: String, nowMs: Int64, newSchedule: RJ) async throws -> Medication {
        guard let medication = try await database.medication(id: id) else { throw MedicationStoreError.notFound }
        let schedules = try await scheduleHistory(medicationID: id)
        let result = MR.lifecycle(action: action, medication: medication.rj, schedules: schedules.map(\.rj), now: Int(nowMs), newSchedule: newSchedule)
        let applied = try Self.lifecycleWrites(result, fallback: medication)
        try await database.applyLifecycle(medication: applied.medication, closeOpenAt: applied.closeOpenAt, insert: applied.inserts,
                                          setReminder: applied.setReminder.map { ($0.0, $0.1, nowMs) })
        return applied.medication
    }

    private struct LifecycleWrites {
        var medication: Medication
        var closeOpenAt: Int64?
        var inserts: [MedicationSchedule]
        var setReminder: (String, Bool)?
    }

    /// Translates a reference `lifecycle` result into database writes (ids `new-N` → UUIDs).
    private static func lifecycleWrites(_ result: RJ, fallback: Medication) throws -> LifecycleWrites {
        guard result["ok"].bool == true else { throw MedicationStoreError.lifecycle(result["error"].string ?? "unknown") }
        let substitution = MedicationIDSubstitution()
        let rows = (result["schedules"].array ?? []).map(substitution.resolvedRow)
        var writes = LifecycleWrites(medication: Medication(rj: result["medication"]) ?? fallback, closeOpenAt: nil, inserts: [], setReminder: nil)
        for op in result["ops"].array ?? [] {
            switch op["op"].string {
            case "close_schedule":
                writes.closeOpenAt = writes.medication.updatedMs
            case "insert_schedule":
                let id = substitution.resolve(op["id"].string)
                if let row = rows.first(where: { $0["id"].string == id }), let schedule = MedicationSchedule(rj: row) {
                    writes.inserts.append(schedule)
                }
            case "update_schedule":
                if let id = op["id"].string, let row = rows.first(where: { $0["id"].string == id }) {
                    writes.setReminder = (id, row["reminder_enabled"].truthy)
                }
            default:
                break
            }
        }
        return writes
    }

    /// Taken / Skip / Snooze / Undo on one occurrence (docs §11).
    func act(_ action: DoseAction, on occurrence: DoseOccurrence, nowMs: Int64, snoozeMinutes: Int, takenAtMs: Int64?, note: String?) async throws -> DoseActionOutcome {
        guard let medication = try await database.medication(id: occurrence.medicationID) else { throw MedicationStoreError.notFound }
        var existing: DoseLog?
        if let scheduleID = occurrence.scheduleID {
            existing = try await database.doseLog(scheduleID: scheduleID, scheduledAtMs: occurrence.scheduledAtMs)
        }
        let result = MR.applyDoseAction(action: action.rawValue, occurrence: occurrence.rj, existingLog: existing?.rj ?? .null,
                                        medication: medication.rj, now: Int(nowMs), snoozeMinutes: .int(snoozeMinutes),
                                        takenAtMs: takenAtMs.map { .int(Int($0)) } ?? .null, note: RJ.string(note))
        guard result["ok"].bool == true else { return .failure(result["error"].string ?? "unknown") }
        switch result["op"].string {
        case "delete":
            if let existing { try await database.deleteDoseLog(id: existing.id) }
            return DoseActionOutcome(ok: true, error: nil, log: nil)
        default:
            let substitution = MedicationIDSubstitution()
            guard let log = DoseLog(rj: substitution.resolvedRow(result["row"])) else { return .failure("bad_row") }
            let stored = try await database.upsertDoseLog(log)
            return DoseActionOutcome(ok: true, error: nil, log: stored)
        }
    }

    /// An as-needed dose (docs §11).
    func logPRN(medicationID: String, nowMs: Int64, takenAtMs: Int64?, quantity: Double?, note: String?) async throws -> DoseActionOutcome {
        guard let medication = try await database.medication(id: medicationID) else { throw MedicationStoreError.notFound }
        let result = MR.logPRNDose(medication: medication.rj, now: Int(nowMs), takenAtMs: takenAtMs.map { .int(Int($0)) } ?? .null,
                                   doseQuantity: RJ.number(quantity), note: RJ.string(note))
        guard result["ok"].bool == true else { return .failure(result["error"].string ?? "unknown") }
        let substitution = MedicationIDSubstitution()
        guard let log = DoseLog(rj: substitution.resolvedRow(result["row"])) else { return .failure("bad_row") }
        let stored = try await database.upsertDoseLog(log)
        return DoseActionOutcome(ok: true, error: nil, log: stored)
    }

    /// Removes a PRN row (the user's own taken dose).
    func deleteLog(id: String) async throws {
        try await database.deleteDoseLog(id: id)
    }

    func delete(id: String) async throws {
        try await database.deleteMedication(id: id)
        photos.delete(medicationID: id)
    }

    // MARK: - Maintenance (docs §7, §12)

    /// Writes `missed` rows for every unlogged occurrence inside
    /// `[missed_materialized_until_ms (default now − 30 d), now − GRACE_MS)` and advances the cursor.
    @discardableResult
    func materializeMissed(nowMs: Int64, zone: String) async throws -> Int {
        let cursor = try await database.meta(MedicationsDatabase.missedCursorKey).flatMap { Int64($0) } ?? (nowMs - Self.missedLookbackMs)
        let windowEnd = nowMs - Int64(MR.graceMs)
        guard windowEnd > cursor else { return 0 }
        let medications = try await database.allMedications()
        let schedules = try await database.schedules(overlapping: cursor, windowEnd)
        let logs = try await database.doseLogs(from: cursor, to: windowEnd)
        let occurrences = MR.expandAll(medications: medications.map(\.rj), schedules: schedules.map(\.rj),
                                       windowStart: Int(cursor), windowEnd: Int(windowEnd), zone: zone, statuses: nil)
        let result = MR.materializeMissed(occurrences: occurrences, logs: logs.map(\.rj),
                                          medicationByID: MR.medicationsByID(medications.map(\.rj)), now: Int(nowMs))
        let substitution = MedicationIDSubstitution()
        let rows = (result["ops"].array ?? []).compactMap { DoseLog(rj: substitution.resolvedRow($0["row"])) }
        try await database.applyDoseLogs(rows, missedCursorMs: windowEnd)
        return rows.count
    }

    /// Completes every active/paused medication whose end date has passed. Returns the ids.
    @discardableResult
    func autoComplete(nowMs: Int64, zone: String) async throws -> [String] {
        let medications = try await database.medications(status: nil, search: nil)
        let today = MR.localDateOf(Int(nowMs), zone: zone)
        let ids = MR.autoComplete(medications: medications.map(\.rj), today: .str(today)).compactMap(\.string)
        for id in ids {
            _ = try? await lifecycle(.complete, id: id, nowMs: nowMs)
        }
        return ids
    }

    func markPlanned(nowMs: Int64) async throws {
        try await database.setMeta(MedicationsDatabase.lastPlannedKey, "\(nowMs)")
    }

    // MARK: - Archive (docs §14)

    /// The same three tables the archive uses, for the Coach tools (docs/medications.md §20).
    func coachSnapshot() async throws -> RJ { try await snapshot() }

    private func snapshot() async throws -> RJ {
        let medications = try await database.allMedications()
        let schedules = try await database.allSchedules()
        let logs = try await database.allDoseLogs()
        return .obj(["medications": .arr(medications.map(\.rj)), "schedules": .arr(schedules.map(\.rj)), "dose_logs": .arr(logs.map(\.rj))])
    }

    func exportArchive(nowMs: Int64, zone: String, platform: String, appVersion: String) async throws -> MedicationArchive {
        let json = MR.exportArchive(snapshot: try await snapshot(), exportedMs: .int(Int(nowMs)), timeZone: .str(zone),
                                    platform: .str(platform), appVersion: .str(appVersion))
        return MedicationArchive(json: json)
    }

    func mergeArchive(_ archive: MedicationArchive, nowMs: Int64) async throws -> MedicationImportResult {
        let result = MR.mergeArchive(snapshot: try await snapshot(), archive: archive.json, now: Int(nowMs))
        guard result["ok"].bool == true else { throw MedicationStoreError.archive(result["error"].string ?? "bad_format") }
        var summary = MedicationImportResult()
        var medications: [(MedicationsDatabase.MergeOp, Medication)] = []
        var schedules: [(MedicationsDatabase.MergeOp, MedicationSchedule)] = []
        var logs: [(MedicationsDatabase.MergeOp, DoseLog)] = []
        for op in result["ops"].array ?? [] {
            let kind: MedicationsDatabase.MergeOp
            switch op["op"].string {
            case "insert": kind = .insert
            case "update": kind = .update
            default:
                summary.skipped += 1
                continue
            }
            switch op["table"].string {
            case "medications":
                guard let row = Medication(rj: op["row"]) else { summary.skipped += 1; continue }
                medications.append((kind, row))
                if kind == .insert { summary.insertedMedications += 1 } else { summary.updatedMedications += 1 }
            case "medication_schedules":
                guard let row = MedicationSchedule(rj: op["row"]) else { summary.skipped += 1; continue }
                schedules.append((kind, row))
                if kind == .insert { summary.insertedSchedules += 1 } else { summary.updatedSchedules += 1 }
            case "dose_logs":
                guard let row = DoseLog(rj: op["row"]) else { summary.skipped += 1; continue }
                logs.append((kind, row))
                if kind == .insert { summary.insertedLogs += 1 } else { summary.updatedLogs += 1 }
            default:
                summary.skipped += 1
            }
        }
        try await database.applyMerge(medications: medications, schedules: schedules, logs: logs)
        return summary
    }
}
