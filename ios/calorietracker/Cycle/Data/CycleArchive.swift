import Foundation

/// Export All Data section `cycle` (docs/cycle-tracking.md §7): `cycle/manifest.json`, `cycle/periods.ndjson`,
/// `cycle/day_logs.ndjson` and `cycle/settings.json`. Rows use the column names; the list columns are embedded arrays
/// (`pain_locations`, `symptoms`, `moods`) and `settings_json` is the object `settings`. `platform_ids_json` and
/// `sync_state` are device-specific and stay out. Tombstones are exported so a delete propagates. Import merges by
/// period `id` / log `day`: insert when missing, replace when the incoming `updated_ms` is newer, else skip; settings
/// replace the local row when newer. Imported rows are `pending` for the Health sync.
nonisolated enum CycleArchive {
    static let sectionID = "cycle"
    static let format = "ayuvo-cycle"
    static let formatVersion = 1
    static let directory = "cycle/"
    static let manifestEntry = directory + "manifest.json"
    static let periodsEntry = directory + "periods.ndjson"
    static let dayLogsEntry = directory + "day_logs.ndjson"
    static let settingsEntry = directory + "settings.json"

    enum ArchiveError: LocalizedError, Equatable {
        case notCycle
        case newerVersion

        var errorDescription: String? {
            switch self {
            case .notCycle: String(localized: "The cycle tracking data in this export couldn't be read.")
            case .newerVersion: String(localized: "Made by a newer version of Ayuvo.")
            }
        }
    }

    // MARK: Rows

    private static func list(_ keys: [String]) -> RJ { .arr(keys.map { .str($0) }) }

    private static func strings(_ v: RJ) -> [String] { (v.array ?? []).compactMap(\.string) }

    private static func int(_ v: RJ) -> Int? { v.double.map { Int($0) } }

    static func periodRow(_ p: CyclePeriodRecord) -> RJ {
        .obj(["id": .str(p.id), "start_day": .str(p.startDay), "end_day": .s(p.endDay), "created_ms": .int(Int(p.createdMs)),
              "updated_ms": .int(Int(p.updatedMs)), "deleted": .int(p.deleted ? 1 : 0)])
    }

    static func period(_ row: RJ) -> CyclePeriodRecord? {
        guard let id = row["id"].string, !id.isEmpty, let start = row["start_day"].string, CycleDay.ordinal(start) != nil else { return nil }
        let end = row["end_day"].string
        if let end, CycleDay.ordinal(end) == nil { return nil }
        let updated = Int64(row["updated_ms"].double ?? 0)
        return CyclePeriodRecord(id: id, startDay: start, endDay: end, createdMs: Int64(row["created_ms"].double ?? Double(updated)),
                                 updatedMs: updated, deleted: (row["deleted"].double ?? 0) != 0)
    }

    static func dayLogRow(_ l: CycleDayLogRecord) -> RJ {
        .obj(["day": .str(l.day), "flow": .s(l.flow), "pain": l.pain.map { .int($0) } ?? .null, "pain_locations": list(l.painLocations),
              "symptoms": list(l.symptoms), "moods": list(l.moods), "note": .s(l.deleted ? nil : l.note),
              "updated_ms": .int(Int(l.updatedMs)), "deleted": .int(l.deleted ? 1 : 0)])
    }

    static func dayLog(_ row: RJ) -> CycleDayLogRecord? {
        guard let day = row["day"].string, CycleDay.ordinal(day) != nil else { return nil }
        let deleted = (row["deleted"].double ?? 0) != 0
        if deleted { return CycleDayLogRecord(day: day, updatedMs: Int64(row["updated_ms"].double ?? 0), deleted: true) }
        return CycleDayLogRecord(day: day, flow: row["flow"].string, pain: int(row["pain"]), painLocations: strings(row["pain_locations"]),
                                 symptoms: strings(row["symptoms"]), moods: strings(row["moods"]), note: row["note"].string,
                                 updatedMs: Int64(row["updated_ms"].double ?? 0), deleted: false)
    }

    static func settingsRow(_ s: CycleSettingsRecord) -> RJ {
        let options = (try? VitalsJSON.parse(s.settingsJSON)).flatMap { $0.object == nil ? nil : $0 } ?? .obj([:])
        return .obj(["setup_done": .int(s.setupDone ? 1 : 0), "cycle_length": s.cycleLength.map { .int($0) } ?? .null,
                     "period_length": s.periodLength.map { .int($0) } ?? .null, "luteal_length": s.lutealLength.map { .int($0) } ?? .null,
                     "settings": options, "updated_ms": .int(Int(s.updatedMs))])
    }

    static func settings(_ row: RJ) -> CycleSettingsRecord? {
        guard row.object != nil else { return nil }
        let options = row["settings"].object == nil ? "{}" : VitalsJSON.encode(row["settings"])
        return CycleSettingsRecord(setupDone: (row["setup_done"].double ?? 0) != 0, cycleLength: int(row["cycle_length"]),
                                   periodLength: int(row["period_length"]), lutealLength: int(row["luteal_length"]),
                                   settingsJSON: options, updatedMs: Int64(row["updated_ms"].double ?? 0))
    }

    static func ndjson(_ rows: [RJ]) -> Data { VitalsArchive.ndjson(rows) }

    // MARK: Export

    struct Export: Sendable {
        /// Entry name → bytes, manifest first.
        var entries: [(name: String, data: Data)]
        var counts: [String: Int]
        var isEmpty: Bool
    }

    /// Every period and day log (tombstones included) and the settings row.
    static func export(from db: CycleDatabase) async throws -> Export {
        let periods = try await db.periods(includeDeleted: true)
        let logs = try await db.dayLogs(includeDeleted: true)
        let settings = try await db.settings()
        let counts = ["periods": periods.count, "day_logs": logs.count]
        let manifest: RJ = .obj(["format": .str(format), "format_version": .int(formatVersion),
                                 "counts": .obj(counts.mapValues { .int($0) })])
        var entries: [(name: String, data: Data)] = [
            (manifestEntry, Data((VitalsJSON.encode(manifest) + "\n").utf8)),
            (periodsEntry, ndjson(periods.map(periodRow))),
            (dayLogsEntry, ndjson(logs.map(dayLogRow))),
        ]
        if let settings { entries.append((settingsEntry, Data((VitalsJSON.encode(settingsRow(settings)) + "\n").utf8))) }
        let empty = periods.isEmpty && logs.isEmpty && !(settings?.setupDone ?? false)
        return Export(entries: entries, counts: counts, isEmpty: empty)
    }

    // MARK: Import

    struct ImportResult: Equatable, Sendable {
        var periodsAdded = 0
        var periodsUpdated = 0
        var periodsSkipped = 0
        var dayLogsAdded = 0
        var dayLogsUpdated = 0
        var dayLogsSkipped = 0
        var settingsApplied = false
        var invalidRows = 0
    }

    /// Imports the section from its entries (names as in the zip; a missing data file counts as empty).
    static func importEntries(_ entries: [String: Data], into db: CycleDatabase) async throws -> ImportResult {
        if let manifestData = entries[manifestEntry] {
            guard let manifest = try? VitalsJSON.parse(manifestData), manifest["format"].string == format else {
                throw ArchiveError.notCycle
            }
            if let version = manifest["format_version"].double, Int(version) > formatVersion { throw ArchiveError.newerVersion }
        }
        var result = ImportResult()
        for line in VitalsArchive.lines(entries[periodsEntry]) {
            guard let line, var incoming = period(line) else { result.invalidRows += 1; continue }
            incoming.syncState = CycleSyncState.pending
            if let local = try await db.period(id: incoming.id) {
                guard incoming.updatedMs > local.updatedMs else { result.periodsSkipped += 1; continue }
                incoming.platformIDsJSON = local.platformIDsJSON
                incoming.createdMs = local.createdMs
                try await db.upsertPeriod(incoming)
                result.periodsUpdated += 1
            } else {
                try await db.upsertPeriod(incoming)
                result.periodsAdded += 1
            }
        }
        for line in VitalsArchive.lines(entries[dayLogsEntry]) {
            guard let line, var incoming = dayLog(line) else { result.invalidRows += 1; continue }
            incoming.syncState = CycleSyncState.pending
            if let local = try await db.dayLog(day: incoming.day) {
                guard incoming.updatedMs > local.updatedMs else { result.dayLogsSkipped += 1; continue }
                incoming.platformIDsJSON = local.platformIDsJSON
                try await db.upsertDayLog(incoming)
                result.dayLogsUpdated += 1
            } else {
                try await db.upsertDayLog(incoming)
                result.dayLogsAdded += 1
            }
        }
        if let data = entries[settingsEntry] {
            guard let row = try? VitalsJSON.parse(data), let incoming = settings(row) else {
                result.invalidRows += 1
                return result
            }
            let local = try await db.settings()
            if local == nil || incoming.updatedMs > local!.updatedMs {
                try await db.saveSettings(incoming)
                result.settingsApplied = true
            }
        }
        return result
    }
}
