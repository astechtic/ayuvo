import Foundation
import SQLite3

/// On-disk layout (docs/partner-sync.md §2): `Application Support/Ayuvo/Partner/partner.sqlite`. The directory
/// is excluded from iCloud / device backup, and nothing in CloudBackup, ayuvo-backup.zip or Export All Data reads
/// it (those archive explicit stores only). Received partner data is never merged into the user's own stores.
nonisolated enum PartnerLocation {
    static let directoryName = "Partner"
    static let databaseFileName = "partner.sqlite"

    static func directory(fileManager: FileManager = .default) -> URL {
        let base = fileManager.urls(for: .applicationSupportDirectory, in: .userDomainMask).first
            ?? fileManager.temporaryDirectory
        return base
            .appendingPathComponent("Ayuvo", isDirectory: true)
            .appendingPathComponent(directoryName, isDirectory: true)
    }

    static func databaseURL(fileManager: FileManager = .default) -> URL {
        directory(fileManager: fileManager).appendingPathComponent(databaseFileName, isDirectory: false)
    }

    /// Creates `directory` and marks it excluded from backup.
    static func prepareDirectory(_ directory: URL, fileManager: FileManager = .default) throws {
        try HealthDatabaseLocation.prepareDirectory(directory, fileManager: fileManager)
    }

    /// Database + sidecars; used by Delete All Data.
    static func removeAll(fileManager: FileManager = .default) throws {
        let root = directory(fileManager: fileManager)
        if fileManager.fileExists(atPath: root.path) {
            try fileManager.removeItem(at: root)
        }
    }
}

/// Single-connection SQLite actor for Partner Health Sync (`partner.sqlite`, schema v1 from
/// `shared/partner/schema.sql`). WAL, `synchronous=NORMAL`, `foreign_keys=ON`, `busy_timeout=5000`,
/// `PRAGMA user_version` + `partner_meta.schema_version`. Opened on demand only — nothing runs at app startup.
actor PartnerDatabase {
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

    nonisolated static func open(url: URL = PartnerLocation.databaseURL(), fileManager: FileManager = .default) async throws -> PartnerDatabase {
        try PartnerLocation.prepareDirectory(url.deletingLastPathComponent(), fileManager: fileManager)
        let connection = try HealthDBConnection(path: url.path, readOnly: false, fileProtection: true)
        let database = PartnerDatabase(connection: connection, url: url)
        try await database.configure()
        return database
    }

    /// An unreadable file is moved aside (`partner.sqlite.corrupt-<ms>` + sidecars) and a fresh database created.
    nonisolated static func openQuarantiningCorruption(url: URL = PartnerLocation.databaseURL(), fileManager: FileManager = .default) async throws -> (PartnerDatabase, quarantined: URL?) {
        do {
            return (try await open(url: url, fileManager: fileManager), nil)
        } catch let error as HealthDBError where error.isCorruption {
            let moved = try HealthDatabaseLocation.quarantine(url, fileManager: fileManager)
            return (try await open(url: url, fileManager: fileManager), moved)
        }
    }

    nonisolated static func inMemory() async throws -> PartnerDatabase {
        let connection = try HealthDBConnection(path: ":memory:", readOnly: false, fileProtection: false)
        let database = PartnerDatabase(connection: connection, url: nil)
        try await database.configure()
        return database
    }

    private func configure() throws {
        try connection.exec("PRAGMA journal_mode=WAL")
        try connection.exec("PRAGMA synchronous=NORMAL")
        try connection.exec("PRAGMA busy_timeout=5000")
        try connection.exec("PRAGMA foreign_keys=ON")
        let check = try connection.scalarText("PRAGMA quick_check(1)")
        guard check == "ok" else {
            throw HealthDBError(kind: .corrupt, code: SQLITE_CORRUPT, message: check ?? "quick_check failed")
        }
        if try userVersion() < PartnerSchema.schemaVersion {
            try connection.inTransaction {
                for statement in PartnerSchema.statements {
                    try connection.exec(statement)
                }
                try connection.run("INSERT OR REPLACE INTO partner_meta (key, value) VALUES ('schema_version', ?)",
                                   [.text("\(PartnerSchema.schemaVersion)")])
                try connection.exec("PRAGMA user_version=\(PartnerSchema.schemaVersion)")
            }
        }
    }

    // MARK: - Introspection

    func userVersion() throws -> Int {
        Int(try connection.scalarInt64("PRAGMA user_version") ?? 0)
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
            columns.append(ColumnInfo(cid: s.int(0) ?? 0, name: s.text(1) ?? "", type: s.text(2) ?? "",
                                      notNull: (s.int(3) ?? 0) != 0, defaultValue: s.text(4), primaryKey: s.int(5) ?? 0))
        }
        return columns
    }

    func journalMode() throws -> String? { try connection.scalarText("PRAGMA journal_mode") }

    func pragmaInt(_ name: String) throws -> Int? {
        let allowed = CharacterSet(charactersIn: "abcdefghijklmnopqrstuvwxyz_")
        guard name.unicodeScalars.allSatisfy({ allowed.contains($0) }) else { return nil }
        return try connection.scalarInt64("PRAGMA \(name)").map { Int($0) }
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
}
