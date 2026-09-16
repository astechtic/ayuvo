import Foundation
import SQLite3

/// Bound parameter / column value.
nonisolated enum SQLValue: Sendable, Equatable {
    case null
    case int(Int64)
    case real(Double)
    case text(String)
    case blob(Data)

    static func optionalInt(_ value: Int?) -> SQLValue { value.map { .int(Int64($0)) } ?? .null }
    static func optionalInt64(_ value: Int64?) -> SQLValue { value.map { .int($0) } ?? .null }
    static func optionalReal(_ value: Double?) -> SQLValue { value.map { .real($0) } ?? .null }
    static func optionalText(_ value: String?) -> SQLValue { value.map { .text($0) } ?? .null }
}

nonisolated struct HealthDBError: Error, Sendable, CustomStringConvertible {
    enum Kind: String, Sendable {
        case open, prepare, bind, step, exec, corrupt, misuse, readOnly
    }

    let kind: Kind
    let code: Int32
    let message: String

    var description: String { "HealthDBError.\(kind.rawValue) (\(code)): \(message)" }

    var isCorruption: Bool { code == SQLITE_CORRUPT || code == SQLITE_NOTADB || kind == .corrupt }
}

/// Thin `sqlite3` wrapper. Not thread-safe by itself — every instance is owned by one
/// `HealthDatabase` actor, which serializes access. Statements are prepared per use
/// (or per batch by callers that loop) and finalized in `deinit`.
nonisolated final class HealthDBConnection {
    /// `SQLITE_TRANSIENT`: SQLite copies bound text/blob bytes immediately.
    static let transient = unsafeBitCast(-1, to: sqlite3_destructor_type.self)
    /// `SQLITE_OPEN_FILEPROTECTION_COMPLETEUNTILFIRSTUSERAUTHENTICATION` (iOS-only flag).
    static let fileProtectionFlag: Int32 = 0x0030_0000

    let path: String
    let readOnly: Bool
    private(set) var handle: OpaquePointer?

    init(path: String, readOnly: Bool, fileProtection: Bool) throws {
        self.path = path
        self.readOnly = readOnly
        var flags: Int32 = readOnly ? SQLITE_OPEN_READONLY : (SQLITE_OPEN_READWRITE | SQLITE_OPEN_CREATE)
        flags |= SQLITE_OPEN_FULLMUTEX
        if fileProtection {
            flags |= Self.fileProtectionFlag
        }
        var db: OpaquePointer?
        let rc = sqlite3_open_v2(path, &db, flags, nil)
        guard rc == SQLITE_OK, let db else {
            let message = db.map { String(cString: sqlite3_errmsg($0)) } ?? "sqlite3_open_v2 failed"
            if let db { sqlite3_close_v2(db) }
            throw HealthDBError(kind: .open, code: rc, message: message)
        }
        handle = db
        sqlite3_busy_timeout(db, 5000)
    }

    deinit {
        close()
    }

    func close() {
        if let handle {
            sqlite3_close_v2(handle)
        }
        handle = nil
    }

    var isOpen: Bool { handle != nil }

    var changes: Int {
        guard let handle else { return 0 }
        return Int(sqlite3_changes(handle))
    }

    private func requireHandle() throws -> OpaquePointer {
        guard let handle else {
            throw HealthDBError(kind: .misuse, code: SQLITE_MISUSE, message: "connection is closed")
        }
        return handle
    }

    private func assertOffMain() {
        #if DEBUG
        dispatchPrecondition(condition: .notOnQueue(.main))
        #endif
    }

    /// Runs one or more `;`-separated statements without parameters.
    func exec(_ sql: String) throws {
        assertOffMain()
        let db = try requireHandle()
        var errorPointer: UnsafeMutablePointer<CChar>?
        let rc = sqlite3_exec(db, sql, nil, nil, &errorPointer)
        if rc != SQLITE_OK {
            let message = errorPointer.map { String(cString: $0) } ?? String(cString: sqlite3_errmsg(db))
            sqlite3_free(errorPointer)
            throw HealthDBError(kind: rc == SQLITE_CORRUPT || rc == SQLITE_NOTADB ? .corrupt : .exec, code: rc, message: message)
        }
    }

    func prepare(_ sql: String) throws -> HealthDBStatement {
        assertOffMain()
        let db = try requireHandle()
        var statement: OpaquePointer?
        let rc = sqlite3_prepare_v2(db, sql, -1, &statement, nil)
        guard rc == SQLITE_OK, let statement else {
            throw HealthDBError(kind: .prepare, code: rc, message: "\(String(cString: sqlite3_errmsg(db))) — \(sql)")
        }
        return HealthDBStatement(handle: statement, database: db)
    }

    /// Prepare, bind, step once (for INSERT/UPDATE/DELETE).
    func run(_ sql: String, _ values: [SQLValue] = []) throws {
        let statement = try prepare(sql)
        try statement.bind(values)
        _ = try statement.step()
    }

    /// Prepare, bind and iterate rows.
    func query(_ sql: String, _ values: [SQLValue] = [], _ row: (HealthDBStatement) throws -> Void) throws {
        let statement = try prepare(sql)
        try statement.bind(values)
        while try statement.step() {
            try row(statement)
        }
    }

    func scalarInt64(_ sql: String, _ values: [SQLValue] = []) throws -> Int64? {
        var result: Int64?
        try query(sql, values) { result = $0.int64(0) }
        return result
    }

    func scalarText(_ sql: String, _ values: [SQLValue] = []) throws -> String? {
        var result: String?
        try query(sql, values) { result = $0.text(0) }
        return result
    }

    /// `BEGIN IMMEDIATE` … `COMMIT`, rolling back on any thrown error.
    func inTransaction<T>(_ body: () throws -> T) throws -> T {
        if readOnly {
            return try body()
        }
        try exec("BEGIN IMMEDIATE")
        do {
            let result = try body()
            try exec("COMMIT")
            return result
        } catch {
            try? exec("ROLLBACK")
            throw error
        }
    }
}

nonisolated final class HealthDBStatement {
    private let handle: OpaquePointer
    private let database: OpaquePointer

    init(handle: OpaquePointer, database: OpaquePointer) {
        self.handle = handle
        self.database = database
    }

    deinit {
        sqlite3_finalize(handle)
    }

    func reset() {
        sqlite3_reset(handle)
        sqlite3_clear_bindings(handle)
    }

    func bind(_ values: [SQLValue]) throws {
        for (offset, value) in values.enumerated() {
            try bind(Int32(offset + 1), value)
        }
    }

    func bind(_ index: Int32, _ value: SQLValue) throws {
        let rc: Int32
        switch value {
        case .null:
            rc = sqlite3_bind_null(handle, index)
        case .int(let v):
            rc = sqlite3_bind_int64(handle, index, v)
        case .real(let v):
            rc = sqlite3_bind_double(handle, index, v)
        case .text(let v):
            rc = sqlite3_bind_text(handle, index, v, -1, HealthDBConnection.transient)
        case .blob(let v):
            rc = v.withUnsafeBytes { buffer in
                sqlite3_bind_blob(handle, index, buffer.baseAddress, Int32(buffer.count), HealthDBConnection.transient)
            }
        }
        if rc != SQLITE_OK {
            throw HealthDBError(kind: .bind, code: rc, message: String(cString: sqlite3_errmsg(database)))
        }
    }

    /// Advances one row. `true` while a row is available; `false` when done.
    func step() throws -> Bool {
        let rc = sqlite3_step(handle)
        switch rc {
        case SQLITE_ROW:
            return true
        case SQLITE_DONE:
            return false
        case SQLITE_CORRUPT, SQLITE_NOTADB:
            throw HealthDBError(kind: .corrupt, code: rc, message: String(cString: sqlite3_errmsg(database)))
        default:
            throw HealthDBError(kind: .step, code: rc, message: String(cString: sqlite3_errmsg(database)))
        }
    }

    var columnCount: Int32 { sqlite3_column_count(handle) }

    func columnName(_ index: Int32) -> String {
        String(cString: sqlite3_column_name(handle, index))
    }

    func isNull(_ index: Int32) -> Bool {
        sqlite3_column_type(handle, index) == SQLITE_NULL
    }

    /// `SQLITE_INTEGER` / `SQLITE_FLOAT` / `SQLITE_TEXT` / `SQLITE_BLOB` / `SQLITE_NULL`.
    func columnType(_ index: Int32) -> Int32 {
        sqlite3_column_type(handle, index)
    }

    func int64(_ index: Int32) -> Int64? {
        isNull(index) ? nil : sqlite3_column_int64(handle, index)
    }

    func int(_ index: Int32) -> Int? {
        int64(index).map { Int($0) }
    }

    func double(_ index: Int32) -> Double? {
        isNull(index) ? nil : sqlite3_column_double(handle, index)
    }

    func text(_ index: Int32) -> String? {
        guard !isNull(index), let pointer = sqlite3_column_text(handle, index) else { return nil }
        return String(cString: pointer)
    }

    func blob(_ index: Int32) -> Data? {
        guard !isNull(index), let pointer = sqlite3_column_blob(handle, index) else { return nil }
        let count = Int(sqlite3_column_bytes(handle, index))
        return Data(bytes: pointer, count: count)
    }
}
