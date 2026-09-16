import Foundation
import SQLite3

/// Single-connection SQLite actor for Medications (`medications.sqlite`, schema v1 from
/// `shared/medications/schema.sql`). Same shape as `RecordsDatabase`: WAL, `synchronous=NORMAL`,
/// `foreign_keys=ON`, `busy_timeout=5000`, `PRAGMA user_version` + `medications_meta.schema_version`.
/// A separate database from the health mirror and records so their parity tests are untouched.
actor MedicationsDatabase {
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

    /// `targetVersion` exists for migration tests; the app always migrates to the latest.
    nonisolated static func open(
        url: URL,
        fileManager: FileManager = .default,
        targetVersion: Int = MedicationsSchema.schemaVersion
    ) async throws -> MedicationsDatabase {
        try MedicationsLocation.prepareDirectory(url.deletingLastPathComponent(), fileManager: fileManager)
        let connection = try HealthDBConnection(path: url.path, readOnly: false, fileProtection: true)
        let database = MedicationsDatabase(connection: connection, url: url)
        try await database.configure(targetVersion: targetVersion)
        return database
    }

    /// An unreadable file is moved aside (`medications.sqlite.corrupt-<ms>` + sidecars) and a
    /// fresh database is created. Photos are untouched.
    nonisolated static func openQuarantiningCorruption(url: URL, fileManager: FileManager = .default) async throws -> (MedicationsDatabase, quarantined: URL?) {
        do {
            return (try await open(url: url, fileManager: fileManager), nil)
        } catch let error as HealthDBError where error.isCorruption {
            let moved = try HealthDatabaseLocation.quarantine(url, fileManager: fileManager)
            return (try await open(url: url, fileManager: fileManager), moved)
        }
    }

    nonisolated static func inMemory(targetVersion: Int = MedicationsSchema.schemaVersion) async throws -> MedicationsDatabase {
        let connection = try HealthDBConnection(path: ":memory:", readOnly: false, fileProtection: false)
        let database = MedicationsDatabase(connection: connection, url: nil)
        try await database.configure(targetVersion: targetVersion)
        return database
    }

    private func configure(targetVersion: Int) throws {
        try connection.exec("PRAGMA journal_mode=WAL")
        try connection.exec("PRAGMA synchronous=NORMAL")
        try connection.exec("PRAGMA busy_timeout=5000")
        try connection.exec("PRAGMA foreign_keys=ON")
        let check = try connection.scalarText("PRAGMA quick_check(1)")
        guard check == "ok" else {
            throw HealthDBError(kind: .corrupt, code: SQLITE_CORRUPT, message: check ?? "quick_check failed")
        }
        try migrate(to: targetVersion)
    }

    /// v1 (`schema.sql`) then every migration in order, each step in its own transaction that
    /// also stamps `user_version` + `medications_meta.schema_version`.
    private func migrate(to targetVersion: Int) throws {
        var version = try userVersion()
        if version < MedicationsSchema.baseVersion, targetVersion >= MedicationsSchema.baseVersion {
            try connection.inTransaction {
                for statement in MedicationsSchema.statements {
                    try connection.exec(statement)
                }
                try stampVersion(MedicationsSchema.baseVersion)
            }
            version = MedicationsSchema.baseVersion
        }
        for migration in MedicationsSchema.migrations where migration.version > version && migration.version <= targetVersion {
            try connection.inTransaction {
                for statement in migration.statements {
                    try connection.exec(statement)
                }
                try stampVersion(migration.version)
            }
            version = migration.version
        }
    }

    private func stampVersion(_ version: Int) throws {
        try connection.run(
            "INSERT OR REPLACE INTO medications_meta (key, value) VALUES ('schema_version', ?)",
            [.text("\(version)")]
        )
        try connection.exec("PRAGMA user_version=\(version)")
    }

    // MARK: - Introspection

    func userVersion() throws -> Int {
        Int(try connection.scalarInt64("PRAGMA user_version") ?? 0)
    }

    func meta(_ key: String) throws -> String? {
        try connection.scalarText("SELECT value FROM medications_meta WHERE key=?", [.text(key)])
    }

    func setMeta(_ key: String, _ value: String) throws {
        try connection.run("INSERT OR REPLACE INTO medications_meta (key, value) VALUES (?, ?)", [.text(key), .text(value)])
    }

    func removeMeta(_ key: String) throws {
        try connection.run("DELETE FROM medications_meta WHERE key=?", [.text(key)])
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

    func pragmaInt(_ name: String) throws -> Int? {
        let allowed = CharacterSet(charactersIn: "abcdefghijklmnopqrstuvwxyz_")
        guard name.unicodeScalars.allSatisfy({ allowed.contains($0) }) else { return nil }
        return try connection.scalarInt64("PRAGMA \(name)").map { Int($0) }
    }

    func integrityCheck() throws -> Bool {
        try connection.scalarText("PRAGMA integrity_check") == "ok"
    }

    func withConnection<T: Sendable>(_ body: (HealthDBConnection) throws -> T) throws -> T {
        try body(connection)
    }

    /// `BEGIN IMMEDIATE` … `COMMIT` around several typed calls made from inside the actor.
    func inTransaction<T: Sendable>(_ body: () throws -> T) throws -> T {
        try connection.inTransaction(body)
    }

    func close() {
        guard !isClosed else { return }
        isClosed = true
        connection.close()
    }

    /// Deletes every row (schema and meta keys other than `schema_version` stay). Used by Delete All Data
    /// when the file itself cannot be removed.
    func wipeAllData() throws {
        try connection.inTransaction {
            try connection.exec("""
            DELETE FROM dose_logs;
            DELETE FROM medication_schedules;
            DELETE FROM medications;
            DELETE FROM medications_meta WHERE key <> 'schema_version';
            """)
        }
    }
}
