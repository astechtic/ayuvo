import Foundation
import SQLite3

/// Single-connection SQLite actor for the health mirror. `HealthDataRuntime` opens one
/// read-write instance (the only writer) and one read-only instance for UI / Coach
/// reads; WAL mode lets the reader proceed while pages are being committed.
/// Precedent for the actor + `nonisolated static` shape: `ExerciseMediaStore`.
actor HealthDatabase {
    nonisolated struct ColumnInfo: Sendable, Hashable {
        let cid: Int
        let name: String
        let type: String
        let notNull: Bool
        let defaultValue: String?
        let primaryKey: Int
    }

    nonisolated let url: URL?
    nonisolated let readOnly: Bool
    let connection: HealthDBConnection
    private(set) var isClosed = false

    private init(connection: HealthDBConnection, url: URL?, readOnly: Bool) {
        self.connection = connection
        self.url = url
        self.readOnly = readOnly
    }

    // MARK: - Opening

    /// Opens (creating when needed) the database at `url`, applying pragmas and the
    /// schema. Throws `HealthDBError` with `isCorruption == true` for unreadable files —
    /// see `openQuarantiningCorruption`.
    nonisolated static func open(url: URL, readOnly: Bool = false, fileManager: FileManager = .default) async throws -> HealthDatabase {
        if !readOnly {
            try HealthDatabaseLocation.prepareDirectory(url.deletingLastPathComponent(), fileManager: fileManager)
        }
        let connection = try HealthDBConnection(path: url.path, readOnly: readOnly, fileProtection: true)
        let database = HealthDatabase(connection: connection, url: url, readOnly: readOnly)
        try await database.configure()
        return database
    }

    /// Opens the writer; an unreadable file is moved aside and a fresh mirror is created.
    nonisolated static func openQuarantiningCorruption(url: URL, fileManager: FileManager = .default) async throws -> (HealthDatabase, quarantined: URL?) {
        do {
            return (try await open(url: url, readOnly: false, fileManager: fileManager), nil)
        } catch let error as HealthDBError where error.isCorruption {
            let moved = try HealthDatabaseLocation.quarantine(url, fileManager: fileManager)
            return (try await open(url: url, readOnly: false, fileManager: fileManager), moved)
        }
    }

    /// Private in-memory database for tests.
    nonisolated static func inMemory() async throws -> HealthDatabase {
        let connection = try HealthDBConnection(path: ":memory:", readOnly: false, fileProtection: false)
        let database = HealthDatabase(connection: connection, url: nil, readOnly: false)
        try await database.configure()
        return database
    }

    private func configure() throws {
        if !readOnly {
            try connection.exec("PRAGMA journal_mode=WAL")
            try connection.exec("PRAGMA synchronous=NORMAL")
        }
        try connection.exec("PRAGMA foreign_keys=ON")
        if readOnly {
            try connection.exec("PRAGMA query_only=1")
        }
        let check = try connection.scalarText("PRAGMA quick_check(1)")
        guard check == "ok" else {
            throw HealthDBError(kind: .corrupt, code: SQLITE_CORRUPT, message: check ?? "quick_check failed")
        }
        if !readOnly {
            try applySchema()
        }
    }

    /// Idempotent: creates missing tables/indexes and stamps the version rows.
    func applySchema() throws {
        try connection.inTransaction {
            try connection.exec(HealthSchema.idempotentDDL)
            try setMetaInTransaction("schema_version", "\(HealthSchema.schemaVersion)")
            if try metaValue("registry_version") == nil {
                try setMetaInTransaction("registry_version", "\(HealthSchema.registryVersion)")
            }
            if try metaValue("rollup_rule_version") == nil {
                try setMetaInTransaction("rollup_rule_version", "\(HealthSchema.rollupRuleVersion)")
            }
            if try metaValue("rollups_tz") == nil {
                try setMetaInTransaction("rollups_tz", TimeZone.current.identifier)
            }
        }
    }

    // MARK: - Introspection

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

    func tableInfo(_ table: String) throws -> [ColumnInfo] {
        let allowed = CharacterSet(charactersIn: "abcdefghijklmnopqrstuvwxyz_")
        guard table.unicodeScalars.allSatisfy({ allowed.contains($0) }) else {
            throw HealthDBError(kind: .misuse, code: SQLITE_MISUSE, message: "invalid table name")
        }
        var columns: [ColumnInfo] = []
        try connection.query("PRAGMA table_info(\(table))") { statement in
            columns.append(ColumnInfo(
                cid: statement.int(0) ?? 0,
                name: statement.text(1) ?? "",
                type: statement.text(2) ?? "",
                notNull: (statement.int(3) ?? 0) != 0,
                defaultValue: statement.text(4),
                primaryKey: statement.int(5) ?? 0
            ))
        }
        return columns
    }

    func journalMode() throws -> String? {
        try connection.scalarText("PRAGMA journal_mode")
    }

    func integrityCheck() throws -> Bool {
        try connection.scalarText("PRAGMA integrity_check") == "ok"
    }

    /// Runs the body on the actor with the raw connection (tests / one-off maintenance).
    func withConnection<T: Sendable>(_ body: (HealthDBConnection) throws -> T) throws -> T {
        try body(connection)
    }

    func checkpoint() throws {
        guard !readOnly else { return }
        try connection.exec("PRAGMA wal_checkpoint(TRUNCATE)")
    }

    func vacuum() throws {
        guard !readOnly else { return }
        try connection.exec("VACUUM")
    }

    func close() {
        guard !isClosed else { return }
        isClosed = true
        connection.close()
    }

    nonisolated func fileSizeBytes(fileManager: FileManager = .default) -> Int64 {
        guard let url else { return 0 }
        return HealthDatabaseLocation.totalSizeBytes(for: url, fileManager: fileManager)
    }

    // MARK: - health_meta

    func metaValue(_ key: String) throws -> String? {
        try connection.scalarText("SELECT value FROM health_meta WHERE key=?", [.text(key)])
    }

    func setMeta(_ key: String, _ value: String?) throws {
        try connection.inTransaction {
            try setMetaInTransaction(key, value)
        }
    }

    func setMetaInTransaction(_ key: String, _ value: String?) throws {
        try connection.run(
            "INSERT INTO health_meta (key, value) VALUES (?, ?) ON CONFLICT(key) DO UPDATE SET value=excluded.value",
            [.text(key), .optionalText(value)]
        )
    }

    // MARK: - Wipe

    /// Removes every mirrored row, rollup, source and type meta and resets cursors so
    /// the next sync re-imports from HealthKit. Preferences are untouched.
    func wipeAllData() throws {
        try connection.inTransaction {
            try connection.exec("""
            DELETE FROM health_series_points;
            DELETE FROM health_samples;
            DELETE FROM health_daily_rollups;
            DELETE FROM health_hourly_rollups;
            DELETE FROM health_sources;
            DELETE FROM health_type_meta;
            UPDATE health_sync_state SET cursor=NULL, cursor_issued_ms=NULL, last_sync_ms=NULL, backfill_done=0, status='idle', last_error=NULL, last_error_ms=NULL;
            """)
        }
    }
}
