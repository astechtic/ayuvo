import Foundation

nonisolated enum CycleStoreError: Error, Equatable, Sendable {
    case notOpen
    case notFound
    /// `validate_period` error codes (`future_start`, `overlap`, …).
    case validation([String])
}

/// The only place `cycle.sqlite` rows meet `CycleEngine` (docs/cycle-tracking.md §2–§3). Sendable; call from any task.
/// Every write bumps `updated_ms` and marks the row `pending` for the Health sync; deletes are soft (tombstones).
nonisolated struct CycleRepository: Sendable {
    let database: CycleDatabase
    var config: CycleConfig = .shared

    // MARK: Reads

    func periods() async throws -> [CyclePeriodRecord] { try await database.periods() }

    func period(id: String) async throws -> CyclePeriodRecord? { try await database.period(id: id) }

    func dayLogs(from: String? = nil, to: String? = nil) async throws -> [CycleDayLogRecord] {
        try await database.dayLogs(from: from, to: to)
    }

    func dayLog(day: String) async throws -> CycleDayLogRecord? {
        guard let log = try await database.dayLog(day: day), !log.deleted else { return nil }
        return log
    }

    /// The saved settings, or a fresh (not set up) record.
    func settings() async throws -> CycleSettingsRecord {
        try await database.settings() ?? CycleSettingsRecord()
    }

    /// Rows that still need writing to (or removing from) Apple Health, tombstones included.
    func pendingSync() async throws -> (periods: [CyclePeriodRecord], logs: [CycleDayLogRecord]) {
        let periods = try await database.periods(includeDeleted: true).filter { $0.syncState == CycleSyncState.pending || $0.syncState == CycleSyncState.failed }
        let logs = try await database.dayLogs(includeDeleted: true).filter { $0.syncState == CycleSyncState.pending || $0.syncState == CycleSyncState.failed }
        return (periods, logs)
    }

    /// Engine input from the live rows plus the platform periods read from `health_samples`
    /// (`CyclePlatformPeriods.load`). `today` is the device-local day.
    func state(today: String, platformPeriods: [CyclePeriodInput] = []) async throws -> CycleState {
        let settings = try await settings()
        let periods = try await database.periods().map(\.engineInput)
        let logs = try await database.dayLogs().map(\.engineInput)
        return CycleState(today: today, settings: settings.engineInput, periods: periods + platformPeriods, logs: logs)
    }

    // MARK: Periods

    /// Validates against today and the other app periods, then inserts (`id` nil) or updates. Throws
    /// `.validation` with the `validate_period` codes; an overlap must be resolved by the caller (merge or adjust).
    @discardableResult
    func savePeriod(id: String?, start: String, end: String?, today: String, nowMs: Int64) async throws -> CyclePeriodRecord {
        let others = try await database.periods().map(\.engineInput)
        let check = CycleEngine.validatePeriod(CyclePeriodCandidate(id: id, start: start, end: end), periods: others, today: today, config)
        guard check.ok else { throw CycleStoreError.validation(check.errors) }
        var record: CyclePeriodRecord
        if let id {
            guard let existing = try await database.period(id: id), !existing.deleted else { throw CycleStoreError.notFound }
            record = existing
            record.startDay = start
            record.endDay = end
            record.updatedMs = nowMs
        } else {
            record = CyclePeriodRecord(id: CyclePeriodRecord.newID(), startDay: start, endDay: end, createdMs: nowMs, updatedMs: nowMs)
        }
        record.syncState = CycleSyncState.pending
        try await database.upsertPeriod(record)
        return record
    }

    /// Soft delete: the row stays as a tombstone so its Health samples can be removed and imports merge.
    func deletePeriod(id: String, nowMs: Int64) async throws {
        guard var record = try await database.period(id: id), !record.deleted else { return }
        record.deleted = true
        record.updatedMs = nowMs
        record.syncState = CycleSyncState.pending
        try await database.upsertPeriod(record)
    }

    /// Applies `CycleEngine.applyPeriodDay` operations in one transaction. Returns the ids inserted.
    @discardableResult
    func apply(_ ops: [CyclePeriodOp], nowMs: Int64) async throws -> [String] {
        var inserted: [String] = []
        for op in ops {
            switch op {
            case .insert(let start, let end):
                let record = CyclePeriodRecord(id: CyclePeriodRecord.newID(), startDay: start, endDay: end, createdMs: nowMs, updatedMs: nowMs)
                try await database.upsertPeriod(record)
                inserted.append(record.id)
            case .update(let id, let start, let end):
                guard var record = try await database.period(id: id) else { throw CycleStoreError.notFound }
                record.startDay = start
                record.endDay = end
                record.updatedMs = nowMs
                record.deleted = false
                record.syncState = CycleSyncState.pending
                try await database.upsertPeriod(record)
            case .delete(let id):
                try await deletePeriod(id: id, nowMs: nowMs)
            }
        }
        return inserted
    }

    /// "This is a period day" toggle (`apply_period_day`).
    @discardableResult
    func setPeriodDay(_ day: String, on: Bool, today: String, nowMs: Int64) async throws -> CyclePeriodDayResult {
        let periods = try await database.periods().map(\.engineInput)
        let result = CycleEngine.applyPeriodDay(day: day, on: on, periods: periods, today: today)
        if let error = result.error { throw CycleStoreError.validation([error]) }
        try await apply(result.ops, nowMs: nowMs)
        return result
    }

    // MARK: Day logs

    /// Upserts the day's log. An empty log becomes a tombstone (nothing logged that day). Choosing a period flow
    /// (Light upwards) on a non-period day marks it a period day (docs §5); Spotting does not. A day inside a period
    /// read from Health (`platformPeriods`) is left alone: an app period there would replace the whole Health period.
    func saveDayLog(_ log: CycleDayLogRecord, today: String, nowMs: Int64, platformPeriods: [CyclePeriodInput] = []) async throws {
        if CycleDay.o(log.day) > CycleDay.o(today) { throw CycleStoreError.validation(["future"]) }
        var record = log
        record.pain = log.pain.map { min(max($0, 0), config.limits.painMax) }
        if let note = record.note {
            let trimmed = note.trimmingCharacters(in: .whitespacesAndNewlines)
            record.note = trimmed.isEmpty ? nil : String(trimmed.prefix(config.limits.noteMaxChars))
        }
        if let existing = try await database.dayLog(day: log.day) { record.platformIDsJSON = existing.platformIDsJSON }
        record.updatedMs = nowMs
        record.syncState = CycleSyncState.pending
        if record.isEmpty {
            record = CycleDayLogRecord(day: log.day, platformIDsJSON: record.platformIDsJSON, updatedMs: nowMs, deleted: true)
        }
        try await database.upsertDayLog(record)
        let day = CycleDay.o(record.day)
        let inPlatformPeriod = platformPeriods.contains { p in
            CycleDay.o(p.start) <= day && day <= (p.end.map(CycleDay.o) ?? CycleDay.o(today))
        }
        if let flow = config.flowLevel(record.flow), flow.period, !inPlatformPeriod {
            try await setPeriodDay(record.day, on: true, today: today, nowMs: nowMs)
        }
    }

    /// Clears the day: a tombstone with every field (and the note) blank.
    func deleteDayLog(day: String, nowMs: Int64) async throws {
        let existing = try await database.dayLog(day: day)
        guard let existing, !existing.deleted else { return }
        try await database.upsertDayLog(CycleDayLogRecord(day: day, platformIDsJSON: existing.platformIDsJSON, updatedMs: nowMs, deleted: true))
    }

    // MARK: Settings

    func saveSettings(_ settings: CycleSettingsRecord, nowMs: Int64) async throws {
        var record = settings
        let lim = config.limits
        record.cycleLength = settings.cycleLength.map { min(max($0, lim.settingCycleMin), lim.settingCycleMax) }
        record.periodLength = settings.periodLength.map { min(max($0, lim.settingPeriodMin), lim.settingPeriodMax) }
        record.lutealLength = settings.lutealLength.map { min(max($0, lim.lutealMin), lim.lutealMax) }
        record.updatedMs = nowMs
        try await database.saveSettings(record)
    }

    // MARK: Health sync bookkeeping (Wave 2 writer)

    func markPeriodSynced(id: String, platformIDsJSON: String, state: String = CycleSyncState.synced) async throws {
        try await database.setPeriodSync(id: id, platformIDsJSON: platformIDsJSON, syncState: state)
    }

    func markDayLogSynced(day: String, platformIDsJSON: String, state: String = CycleSyncState.synced) async throws {
        try await database.setDayLogSync(day: day, platformIDsJSON: platformIDsJSON, syncState: state)
    }

    // MARK: Delete all

    /// "Delete all cycle data" (Settings): every row and the settings. Health samples are the writer's job.
    func deleteAll() async throws {
        try await database.wipeAllData()
    }
}
