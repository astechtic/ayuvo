import Foundation
import SQLite3

/// Single-connection SQLite actor for cycle tracking (`cycle.sqlite`, schema from `shared/cycle/schema.sql`). Same
/// shape as `MedicationsDatabase`: WAL, `synchronous=NORMAL`, `busy_timeout=5000`, `PRAGMA user_version` +
/// `cycle_meta.schema_version`. A separate database so "Clear synced health data" never touches it.
actor CycleDatabase {
    nonisolated struct ColumnInfo: Sendable, Hashable {
        let cid: Int
        let name: String
        let type: String
        let notNull: Bool
        let defaultValue: String?
        let primaryKey: Int
    }

    nonisolated let url: URL?
    let connection: HealthDBConnection
    private(set) var isClosed = false

    private init(connection: HealthDBConnection, url: URL?) {
        self.connection = connection
        self.url = url
    }

    // MARK: - Opening

    nonisolated static func open(url: URL, fileManager: FileManager = .default) async throws -> CycleDatabase {
        try CycleLocation.prepareDirectory(url.deletingLastPathComponent(), fileManager: fileManager)
        let connection = try HealthDBConnection(path: url.path, readOnly: false, fileProtection: true)
        let database = CycleDatabase(connection: connection, url: url)
        try await database.configure()
        return database
    }

    /// An unreadable file is moved aside (`cycle.sqlite.corrupt-<ms>` + sidecars) and a fresh database is created.
    nonisolated static func openQuarantiningCorruption(url: URL, fileManager: FileManager = .default) async throws -> (CycleDatabase, quarantined: URL?) {
        do {
            return (try await open(url: url, fileManager: fileManager), nil)
        } catch let error as HealthDBError where error.isCorruption {
            let moved = try HealthDatabaseLocation.quarantine(url, fileManager: fileManager)
            return (try await open(url: url, fileManager: fileManager), moved)
        }
    }

    nonisolated static func inMemory() async throws -> CycleDatabase {
        let connection = try HealthDBConnection(path: ":memory:", readOnly: false, fileProtection: false)
        let database = CycleDatabase(connection: connection, url: nil)
        try await database.configure()
        return database
    }

    private func configure() throws {
        try connection.exec("PRAGMA journal_mode=WAL")
        try connection.exec("PRAGMA synchronous=NORMAL")
        try connection.exec("PRAGMA busy_timeout=5000")
        let check = try connection.scalarText("PRAGMA quick_check(1)")
        guard check == "ok" else {
            throw HealthDBError(kind: .corrupt, code: SQLITE_CORRUPT, message: check ?? "quick_check failed")
        }
        if try userVersion() < CycleSchema.schemaVersion {
            try connection.inTransaction {
                for statement in CycleSchema.statements { try connection.exec(statement) }
                try connection.run("INSERT OR REPLACE INTO cycle_meta (key, value) VALUES ('schema_version', ?)",
                                   [.text("\(CycleSchema.schemaVersion)")])
                try connection.exec("PRAGMA user_version=\(CycleSchema.schemaVersion)")
            }
        }
    }

    // MARK: - Introspection

    func userVersion() throws -> Int {
        Int(try connection.scalarInt64("PRAGMA user_version") ?? 0)
    }

    func meta(_ key: String) throws -> String? {
        try connection.scalarText("SELECT value FROM cycle_meta WHERE key=?", [.text(key)])
    }

    func setMeta(_ key: String, _ value: String) throws {
        try connection.run("INSERT OR REPLACE INTO cycle_meta (key, value) VALUES (?, ?)", [.text(key), .text(value)])
    }

    func tableNames() throws -> [String] {
        var names: [String] = []
        try connection.query("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name") {
            if let name = $0.text(0) { names.append(name) }
        }
        return names
    }

    func indexNames() throws -> [String] {
        var names: [String] = []
        try connection.query("SELECT name FROM sqlite_master WHERE type='index' AND name NOT LIKE 'sqlite_%' ORDER BY name") {
            if let name = $0.text(0) { names.append(name) }
        }
        return names
    }

    func columns(of table: String) throws -> [ColumnInfo] {
        let allowed = CharacterSet(charactersIn: "abcdefghijklmnopqrstuvwxyz_")
        guard table.unicodeScalars.allSatisfy({ allowed.contains($0) }) else {
            throw HealthDBError(kind: .misuse, code: SQLITE_MISUSE, message: "invalid table name")
        }
        var columns: [ColumnInfo] = []
        try connection.query("PRAGMA table_info(\(table))") { s in
            columns.append(ColumnInfo(cid: s.int(0) ?? 0, name: s.text(1) ?? "", type: s.text(2) ?? "", notNull: (s.int(3) ?? 0) != 0,
                                      defaultValue: s.text(4), primaryKey: s.int(5) ?? 0))
        }
        return columns
    }

    func journalMode() throws -> String? {
        try connection.scalarText("PRAGMA journal_mode")
    }

    func close() {
        guard !isClosed else { return }
        isClosed = true
        connection.close()
    }

    /// `BEGIN IMMEDIATE` … `COMMIT` around several typed calls made from inside the actor.
    func inTransaction<T: Sendable>(_ body: () throws -> T) throws -> T {
        try connection.inTransaction(body)
    }

    /// Deletes every row (meta other than `schema_version` too). Used when the file itself cannot be removed.
    func wipeAllData() throws {
        try connection.inTransaction {
            try connection.exec("""
            DELETE FROM cycle_periods;
            DELETE FROM cycle_day_logs;
            DELETE FROM cycle_settings;
            DELETE FROM cycle_meta WHERE key <> 'schema_version';
            """)
        }
    }

    // MARK: - JSON list columns

    nonisolated static func encodeList(_ keys: [String]) -> String {
        VitalsJSON.encode(.arr(keys.map { .str($0) }))
    }

    nonisolated static func decodeList(_ text: String?) -> [String] {
        guard let text, let v = try? VitalsJSON.parse(text), let a = v.array else { return [] }
        return a.compactMap(\.string)
    }

    // MARK: - Periods

    private static let periodColumns = "id, start_day, end_day, platform_ids_json, sync_state, created_ms, updated_ms, deleted"

    private static func decodePeriod(_ s: HealthDBStatement) -> CyclePeriodRecord {
        CyclePeriodRecord(id: s.text(0) ?? "", startDay: s.text(1) ?? "", endDay: s.text(2), platformIDsJSON: s.text(3) ?? "{}",
                          syncState: s.text(4) ?? CycleSyncState.pending, createdMs: s.int64(5) ?? 0, updatedMs: s.int64(6) ?? 0,
                          deleted: (s.int(7) ?? 0) != 0)
    }

    /// Ordered by start day, then id.
    func periods(includeDeleted: Bool = false) throws -> [CyclePeriodRecord] {
        var out: [CyclePeriodRecord] = []
        let filter = includeDeleted ? "" : " WHERE deleted=0"
        try connection.query("SELECT \(Self.periodColumns) FROM cycle_periods\(filter) ORDER BY start_day, id") {
            out.append(Self.decodePeriod($0))
        }
        return out
    }

    func period(id: String) throws -> CyclePeriodRecord? {
        var out: CyclePeriodRecord?
        try connection.query("SELECT \(Self.periodColumns) FROM cycle_periods WHERE id=?", [.text(id)]) { out = Self.decodePeriod($0) }
        return out
    }

    /// Insert or replace by id.
    func upsertPeriod(_ p: CyclePeriodRecord) throws {
        try connection.run("""
        INSERT OR REPLACE INTO cycle_periods (\(Self.periodColumns)) VALUES (?,?,?,?,?,?,?,?)
        """, [.text(p.id), .text(p.startDay), p.endDay.map(SQLValue.text) ?? .null, .text(p.platformIDsJSON), .text(p.syncState),
              .int(p.createdMs), .int(p.updatedMs), .int(p.deleted ? 1 : 0)])
    }

    /// Records the samples written to Health for a period without touching `updated_ms`.
    func setPeriodSync(id: String, platformIDsJSON: String, syncState: String) throws {
        try connection.run("UPDATE cycle_periods SET platform_ids_json=?, sync_state=? WHERE id=?",
                           [.text(platformIDsJSON), .text(syncState), .text(id)])
    }

    // MARK: - Day logs

    private static let logColumns =
        "day, flow, pain, pain_locations_json, symptoms_json, moods_json, note, platform_ids_json, sync_state, updated_ms, deleted"

    private static func decodeLog(_ s: HealthDBStatement) -> CycleDayLogRecord {
        CycleDayLogRecord(day: s.text(0) ?? "", flow: s.text(1), pain: s.int(2), painLocations: decodeList(s.text(3)),
                          symptoms: decodeList(s.text(4)), moods: decodeList(s.text(5)), note: s.text(6),
                          platformIDsJSON: s.text(7) ?? "{}", syncState: s.text(8) ?? CycleSyncState.pending,
                          updatedMs: s.int64(9) ?? 0, deleted: (s.int(10) ?? 0) != 0)
    }

    /// Ordered by day; `from` / `to` inclusive 'yyyy-MM-dd' bounds.
    func dayLogs(from: String? = nil, to: String? = nil, includeDeleted: Bool = false) throws -> [CycleDayLogRecord] {
        var clauses: [String] = []
        var values: [SQLValue] = []
        if !includeDeleted { clauses.append("deleted=0") }
        if let from {
            clauses.append("day>=?")
            values.append(.text(from))
        }
        if let to {
            clauses.append("day<=?")
            values.append(.text(to))
        }
        let filter = clauses.isEmpty ? "" : " WHERE " + clauses.joined(separator: " AND ")
        var out: [CycleDayLogRecord] = []
        try connection.query("SELECT \(Self.logColumns) FROM cycle_day_logs\(filter) ORDER BY day", values) { out.append(Self.decodeLog($0)) }
        return out
    }

    func dayLog(day: String) throws -> CycleDayLogRecord? {
        var out: CycleDayLogRecord?
        try connection.query("SELECT \(Self.logColumns) FROM cycle_day_logs WHERE day=?", [.text(day)]) { out = Self.decodeLog($0) }
        return out
    }

    func upsertDayLog(_ l: CycleDayLogRecord) throws {
        try connection.run("""
        INSERT OR REPLACE INTO cycle_day_logs (\(Self.logColumns)) VALUES (?,?,?,?,?,?,?,?,?,?,?)
        """, [.text(l.day), l.flow.map(SQLValue.text) ?? .null, l.pain.map { .int(Int64($0)) } ?? .null,
              .text(Self.encodeList(l.painLocations)), .text(Self.encodeList(l.symptoms)), .text(Self.encodeList(l.moods)),
              l.note.map(SQLValue.text) ?? .null, .text(l.platformIDsJSON), .text(l.syncState), .int(l.updatedMs),
              .int(l.deleted ? 1 : 0)])
    }

    func setDayLogSync(day: String, platformIDsJSON: String, syncState: String) throws {
        try connection.run("UPDATE cycle_day_logs SET platform_ids_json=?, sync_state=? WHERE day=?",
                           [.text(platformIDsJSON), .text(syncState), .text(day)])
    }

    // MARK: - Settings

    /// The single row, or nil before setup ever saved one.
    func settings() throws -> CycleSettingsRecord? {
        var out: CycleSettingsRecord?
        try connection.query("""
        SELECT setup_done, cycle_length, period_length, luteal_length, settings_json, updated_ms FROM cycle_settings WHERE id=1
        """) { s in
            out = CycleSettingsRecord(setupDone: (s.int(0) ?? 0) != 0, cycleLength: s.int(1), periodLength: s.int(2),
                                      lutealLength: s.int(3), settingsJSON: s.text(4) ?? "{}", updatedMs: s.int64(5) ?? 0)
        }
        return out
    }

    func saveSettings(_ r: CycleSettingsRecord) throws {
        try connection.run("""
        INSERT OR REPLACE INTO cycle_settings (id, setup_done, cycle_length, period_length, luteal_length, settings_json, updated_ms)
        VALUES (1,?,?,?,?,?,?)
        """, [.int(r.setupDone ? 1 : 0), r.cycleLength.map { .int(Int64($0)) } ?? .null, r.periodLength.map { .int(Int64($0)) } ?? .null,
              r.lutealLength.map { .int(Int64($0)) } ?? .null, .text(r.settingsJSON), .int(r.updatedMs)])
    }
}
