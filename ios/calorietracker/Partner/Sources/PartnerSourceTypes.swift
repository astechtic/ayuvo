import CryptoKit
import Foundation

// Outbound sources (docs/partner-sync.md §7, §10): one renderer per record type. A source reads the user's own
// stores READ-ONLY, yields `{type, record_id, category, day, data}` for a scope in bounded pages, and renders a single
// record by id when a CHANGES page or a package is written. Sources never write anything anywhere.

/// Which records of a type a ledger refresh looks at (`scopes` of `ledger_refresh`).
nonisolated enum PartnerSourceScope: Sendable, Equatable {
    case full
    /// Records whose day is on or after this `yyyy-MM-dd`.
    case dayFrom(String)

    var dayFrom: String? {
        if case .dayFrom(let day) = self { return day }
        return nil
    }

    /// `day >= day_from` (records without a day are always in scope, like the reference).
    func contains(day: String?) -> Bool {
        guard let from = dayFrom, let day else { return true }
        return !utf8Less(day, from)
    }
}

/// One shareable record rendered from a source.
nonisolated struct PartnerSourceRecord: Sendable, Equatable {
    let type: String
    let recordID: String
    let category: String
    let day: String?
    let data: RJ
    /// Source modification time when the store has one (health rows, medications, records); else nil (render time).
    var updatedMs: Int64?

    static func == (a: PartnerSourceRecord, b: PartnerSourceRecord) -> Bool {
        a.type == b.type && a.recordID == b.recordID && a.category == b.category && a.day == b.day
            && PartnerJSON.canonical(a.data) == PartnerJSON.canonical(b.data) && a.updatedMs == b.updatedMs
    }

    init(type: String, recordID: String, category: String, day: String?, data: RJ, updatedMs: Int64? = nil) {
        self.type = type
        self.recordID = recordID
        self.category = category
        self.day = day
        self.data = data
        self.updatedMs = updatedMs
    }

    /// From a reference mapper result `{type, id, category, day, data}` (nil when the mapper said "not shareable").
    init?(mapped env: RJ?, updatedMs: Int64? = nil) {
        guard let env, env.isObject, let type = env["type"].string, let category = env["category"].string else { return nil }
        let id: String
        switch env["id"] {
        case .str(let s): id = s
        case .int(let i): id = String(i)
        default: return nil
        }
        self.init(type: type, recordID: id, category: category, day: env["day"].string, data: env["data"], updatedMs: updatedMs)
    }

    /// Device-local content hash: SHA-256 hex of the canonical JSON of `{category, day, data}` (§10).
    var contentHash: String {
        let body: RJ = .obj(["category": .str(category), "day": RJ.string(day), "data": data])
        return PartnerEncoding.hex(SHA256.hash(data: PartnerJSON.canonicalData(body)))
    }

    /// A `current` row of `ledger_refresh`.
    var ledgerCurrent: RJ {
        .obj(["type": .str(type), "record_id": .str(recordID), "category": .str(category), "day": RJ.string(day),
              "content_hash": .str(contentHash)])
    }

    /// Wire envelope (§7) with the ledger's rev.
    func envelope(rev: Int64, nowMs: Int64) -> RJ {
        var env: [String: RJ] = [
            "type": .str(type), "id": .str(recordID), "category": .str(category), "rev": .int(Int(rev)),
            "deleted": .bool(false), "updated_ms": .int(Int(min(updatedMs ?? nowMs, nowMs))), "data": data,
        ]
        if PartnerCatalog.shared.types[type]?.day == true, let day { env["day"] = .str(day) }
        return .obj(env)
    }

    /// Tombstone envelope (no data) for a ledger row whose record is gone or deleted.
    static func tombstone(type: String, recordID: String, category: String, rev: Int64, nowMs: Int64) -> RJ {
        .obj(["type": .str(type), "id": .str(recordID), "category": .str(category), "rev": .int(Int(rev)),
              "deleted": .bool(true), "updated_ms": .int(Int(nowMs))])
    }
}

/// A source could not be read (database locked, blob undecodable…). The refresher skips the type entirely so a
/// read failure never turns into tombstones.
nonisolated struct PartnerSourceUnavailable: Error, Sendable, CustomStringConvertible {
    let type: String
    let reason: String
    var description: String { "partner source \(type) unavailable: \(reason)" }
}

/// One renderer per record type.
nonisolated protocol PartnerRecordSource: Sendable {
    /// The `record_types.json` type this source renders.
    var type: String { get }
    /// Streams every record in `scope` in pages of at most `pageSize`. Throws `PartnerSourceUnavailable` when the
    /// store cannot be read (the caller then leaves the ledger of this type alone).
    func scan(scope: PartnerSourceScope, pageSize: Int, onPage: ([PartnerSourceRecord]) async throws -> Void) async throws
    /// The current record with this id, or nil when it no longer exists / is no longer shareable.
    func record(id: String) async throws -> PartnerSourceRecord?
}

/// The set of sources the ledger refresher, the session server and the package exporter use.
nonisolated struct PartnerSourceRegistry: Sendable {
    let sources: [String: any PartnerRecordSource]

    init(_ list: [any PartnerRecordSource]) {
        var map: [String: any PartnerRecordSource] = [:]
        for s in list { map[s.type] = s }
        sources = map
    }

    func source(for type: String) -> (any PartnerRecordSource)? { sources[type] }

    /// Types in contract order.
    var types: [String] { PartnerCatalog.shared.typeOrder.filter { sources[$0] != nil } }

    /// Renders one ledger key for sending: the current record, or a tombstone when the source no longer has it
    /// (or its category moved, which grants were checked against).
    func envelope(type: String, recordID: String, ledgerCategory: String, rev: Int64, deleted: Bool, nowMs: Int64) async -> RJ {
        if !deleted, let source = sources[type],
           let rec = try? await source.record(id: recordID), rec.category == ledgerCategory {
            return rec.envelope(rev: rev, nowMs: nowMs)
        }
        return PartnerSourceRecord.tombstone(type: type, recordID: recordID, category: ledgerCategory, rev: rev, nowMs: nowMs)
    }
}

// MARK: - Days

nonisolated enum PartnerDay {
    static func formatter(_ timeZone: TimeZone = .current) -> DateFormatter {
        let f = DateFormatter()
        f.calendar = Calendar(identifier: .gregorian)
        f.locale = Locale(identifier: "en_US_POSIX")
        f.timeZone = timeZone
        f.dateFormat = "yyyy-MM-dd"
        return f
    }

    static func string(_ date: Date, timeZone: TimeZone = .current) -> String {
        var cal = Calendar(identifier: .gregorian)
        cal.timeZone = timeZone
        let c = cal.dateComponents([.year, .month, .day], from: date)
        return String(format: "%04d-%02d-%02d", c.year ?? 0, c.month ?? 0, c.day ?? 0)
    }

    static func string(ms: Int64, timeZone: TimeZone = .current) -> String {
        string(Date(timeIntervalSince1970: Double(ms) / 1000), timeZone: timeZone)
    }

    /// `day` shifted by `days` (calendar days).
    static func adding(_ days: Int, to day: String, timeZone: TimeZone = .current) -> String {
        guard let date = date(day, timeZone: timeZone) else { return day }
        var cal = Calendar(identifier: .gregorian)
        cal.timeZone = timeZone
        return string(cal.date(byAdding: .day, value: days, to: date) ?? date, timeZone: timeZone)
    }

    static func date(_ day: String, timeZone: TimeZone = .current) -> Date? {
        let parts = day.split(separator: "-").compactMap { Int($0) }
        guard parts.count == 3 else { return nil }
        var cal = Calendar(identifier: .gregorian)
        cal.timeZone = timeZone
        return cal.date(from: DateComponents(year: parts[0], month: parts[1], day: parts[2]))
    }

    /// Local midnight of `day` + `hour` hours, in ms.
    static func hourStartMs(day: String, hour: Int, timeZone: TimeZone = .current) -> Int64? {
        guard let d = date(day, timeZone: timeZone) else { return nil }
        var cal = Calendar(identifier: .gregorian)
        cal.timeZone = timeZone
        guard let h = cal.date(byAdding: .hour, value: hour, to: d) else { return nil }
        return Int64((h.timeIntervalSince1970 * 1000).rounded())
    }
}

// MARK: - Read-only SQLite access to the user's own databases

/// A lazily opened READ-ONLY connection to one of the user's own databases (health mirror, medications, records).
/// A missing file means "no data" (nothing is created); a file that cannot be opened makes reads throw.
actor PartnerSQLiteReader {
    nonisolated let url: URL
    private var connection: HealthDBConnection?

    init(url: URL) {
        self.url = url
    }

    var fileExists: Bool { FileManager.default.fileExists(atPath: url.path) }

    /// Runs `body` on the read-only connection; nil when the database file does not exist.
    func read<T: Sendable>(_ body: (HealthDBConnection) throws -> T) throws -> T? {
        if connection == nil {
            guard FileManager.default.fileExists(atPath: url.path) else { return nil }
            let c = try HealthDBConnection(path: url.path, readOnly: true, fileProtection: false)
            try c.exec("PRAGMA query_only=1")
            connection = c
        }
        guard let connection else { return nil }
        return try body(connection)
    }

    /// True when `table` exists (older installs may lack later tables).
    func hasTable(_ table: String) throws -> Bool {
        try read { c in
            try (c.scalarInt64("SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name=?", [.text(table)]) ?? 0) > 0
        } ?? false
    }

    func close() {
        connection?.close()
        connection = nil
    }
}

nonisolated extension HealthDBStatement {
    /// REAL / INTEGER column as JSON (`RJ.number` keeps integral values as ints).
    func rjNumber(_ index: Int32) -> RJ {
        switch columnType(index) {
        case 1: return int64(index).map { RJ.int(Int($0)) } ?? .null      // SQLITE_INTEGER
        case 2: return RJ.number(double(index))                         // SQLITE_FLOAT
        default: return .null
        }
    }

    func rjText(_ index: Int32) -> RJ { RJ.string(text(index)) }

    func rjInt(_ index: Int32) -> RJ { int64(index).map { RJ.int(Int($0)) } ?? .null }
}
