import Foundation
import SQLite3

/// Typed operations over `partner.sqlite` (docs/partner-sync.md §6, §8–§10). Every write that must be atomic runs
/// in one `BEGIN IMMEDIATE` transaction; upserts are UPDATE-then-INSERT (SQLite 3.18 rule). Partner data lives
/// only here — it is never written to the user's health, medications or records databases.
typealias PartnerStore = PartnerDatabase

// MARK: - Models

nonisolated struct PartnerPeer: Sendable, Equatable {
    var ownerID: String
    var displayName: String
    var fingerprint: String
    var x25519Pub: String
    var ed25519Pub: String
    var platform: String?
    var pairedMs: Int64
    var unpairedMs: Int64?
    var lastHost: String?
    var lastPort: Int?
    var updatedMs: Int64

    var isTrusted: Bool { unpairedMs == nil }
}

nonisolated struct PartnerGrantReceived: Sendable, Equatable {
    var category: String
    var granted: Bool
    var revokedMs: Int64?
    var updatedMs: Int64
}

nonisolated struct PartnerSyncState: Sendable, Equatable {
    var ownerID: String
    var lastRev: Int64 = 0
    var ackedRev: Int64 = 0
    var lastSyncMs: Int64?
    var lastAttemptMs: Int64?
    var lastTransport: String?
    var status: String = "pairing_required"
    var lastError: String?
    var lastExportID: String?
}

nonisolated struct PartnerLedgerRow: Sendable, Equatable {
    var type: String
    var recordID: String
    var category: String
    var day: String?
    var contentHash: String
    var rev: Int64
    var deleted: Bool

    var rj: RJ {
        .obj(["type": .str(type), "record_id": .str(recordID), "category": .str(category), "day": RJ.string(day),
              "content_hash": .str(contentHash), "rev": .int(Int(rev)), "deleted": .bool(deleted)])
    }

    init(type: String, recordID: String, category: String, day: String?, contentHash: String, rev: Int64, deleted: Bool) {
        self.type = type
        self.recordID = recordID
        self.category = category
        self.day = day
        self.contentHash = contentHash
        self.rev = rev
        self.deleted = deleted
    }

    init?(rj: RJ) {
        guard let type = rj["type"].string, let id = rj["record_id"].string, let category = rj["category"].string,
              let hash = rj["content_hash"].string, let rev = rj["rev"].pyInt else { return nil }
        self.init(type: type, recordID: id, category: category, day: rj["day"].string, contentHash: hash, rev: Int64(rev),
                  deleted: rj["deleted"].truthy)
    }
}

nonisolated struct PartnerDeltaKey: Sendable, Equatable {
    var type: String
    var recordID: String
    var rev: Int64
    var deleted: Bool
}

/// One `ledger_delta` page read from the database.
nonisolated struct PartnerDelta: Sendable, Equatable {
    var fromRev: Int64
    var toRev: Int64
    var hasMore: Bool
    var keys: [PartnerDeltaKey]
}

/// A received record row (`partner_records`).
nonisolated struct PartnerRecordRow: Sendable, Equatable {
    var type: String
    var recordID: String
    var category: String
    var rev: Int64
    var day: String?
    var tsMs: Int64?
    var updatedMs: Int64
    var dataJSON: String

    var data: RJ { PartnerJSON.parse(dataJSON) ?? .null }

    /// A merge output row (`{type, record_id, category, rev, day, ts_ms, updated_ms, data}`).
    init?(mergeRow r: RJ) {
        guard let type = r["type"].string, let id = r["record_id"].string, let category = r["category"].string,
              let rev = r["rev"].pyInt else { return nil }
        self.init(type: type, recordID: id, category: category, rev: Int64(rev), day: r["day"].string,
                  tsMs: r["ts_ms"].pyInt.map(Int64.init), updatedMs: Int64(r["updated_ms"].pyInt ?? 0),
                  dataJSON: PartnerJSON.canonical(r["data"]))
    }

    init(type: String, recordID: String, category: String, rev: Int64, day: String?, tsMs: Int64?, updatedMs: Int64, dataJSON: String) {
        self.type = type
        self.recordID = recordID
        self.category = category
        self.rev = rev
        self.day = day
        self.tsMs = tsMs
        self.updatedMs = updatedMs
        self.dataJSON = dataJSON
    }
}

/// Typed view of a `merge_apply` / `package_import` result.
nonisolated struct PartnerMergeOutcome: Sendable {
    let accepted: Bool
    let error: String?
    let cursor: Int64
    let upserts: [PartnerRecordRow]
    let deletes: [(type: String, recordID: String)]
    let counts: [String: Int]
    let rejected: [RJ]
    let raw: RJ

    init(_ r: RJ) {
        raw = r
        accepted = r["accepted"].bool ?? false
        error = r["error"].string
        cursor = Int64(r["cursor"].pyInt ?? 0)
        upserts = (r["upserts"].array ?? []).compactMap(PartnerRecordRow.init(mergeRow:))
        deletes = (r["deletes"].array ?? []).compactMap { d in
            guard let t = d["type"].string, let id = d["record_id"].string else { return nil }
            return (t, id)
        }
        counts = (r["counts"].object ?? [:]).compactMapValues(\.pyInt)
        rejected = r["rejected"].array ?? []
    }
}

nonisolated struct PartnerStoreError: Error, Sendable, Equatable {
    let message: String
}

// MARK: - Operations

extension PartnerDatabase {
    // MARK: Meta

    func meta(_ key: String) throws -> String? {
        try connection.scalarText("SELECT value FROM partner_meta WHERE key=?", [.text(key)])
    }

    func setMeta(_ key: String, _ value: String?) throws {
        try setMetaRow(key, value)
    }

    private func setMetaRow(_ key: String, _ value: String?) throws {
        try connection.run("UPDATE partner_meta SET value=? WHERE key=?", [.optionalText(value), .text(key)])
        if connection.changes == 0 {
            try connection.run("INSERT INTO partner_meta (key, value) VALUES (?, ?)", [.text(key), .optionalText(value)])
        }
    }

    /// My outbound revision counter (last assigned rev), 0 before the first refresh.
    func outboundRev() throws -> Int64 {
        Int64(try meta("rev") ?? "0") ?? 0
    }

    func setOutboundRev(_ rev: Int64) throws {
        try setMetaRow("rev", String(rev))
    }

    // MARK: Partners

    nonisolated static let partnerColumns = "owner_id, display_name, fingerprint, x25519_pub, ed25519_pub, platform, paired_ms, unpaired_ms, last_host, last_port, updated_ms"

    nonisolated static func decodePartner(_ s: HealthDBStatement) -> PartnerPeer {
        PartnerPeer(ownerID: s.text(0) ?? "", displayName: s.text(1) ?? "", fingerprint: s.text(2) ?? "", x25519Pub: s.text(3) ?? "",
                    ed25519Pub: s.text(4) ?? "", platform: s.text(5), pairedMs: s.int64(6) ?? 0, unpairedMs: s.int64(7),
                    lastHost: s.text(8), lastPort: s.int(9), updatedMs: s.int64(10) ?? 0)
    }

    /// Inserts or replaces a partner (keeps its received data and cursor) and makes sure it has a sync state row.
    func upsertPartner(_ p: PartnerPeer) throws {
        try connection.inTransaction {
            let values: [SQLValue] = [
                .text(p.displayName), .text(p.fingerprint), .text(p.x25519Pub), .text(p.ed25519Pub), .optionalText(p.platform),
                .int(p.pairedMs), .optionalInt64(p.unpairedMs), .optionalText(p.lastHost), .optionalInt(p.lastPort), .int(p.updatedMs),
                .text(p.ownerID),
            ]
            try connection.run("""
                UPDATE partners SET display_name=?, fingerprint=?, x25519_pub=?, ed25519_pub=?, platform=?, paired_ms=?,
                unpaired_ms=?, last_host=?, last_port=?, updated_ms=? WHERE owner_id=?
                """, values)
            if connection.changes == 0 {
                try connection.run("INSERT INTO partners (\(Self.partnerColumns)) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                                   [values.last!] + values.dropLast())
            }
            try connection.run("INSERT OR IGNORE INTO partner_sync_state (owner_id) VALUES (?)", [.text(p.ownerID)])
        }
    }

    func partner(_ ownerID: String) throws -> PartnerPeer? {
        var out: PartnerPeer?
        try connection.query("SELECT \(Self.partnerColumns) FROM partners WHERE owner_id=?", [.text(ownerID)]) { out = Self.decodePartner($0) }
        return out
    }

    /// Partners in pairing order; unpaired rows (data kept, trust gone) only when asked for.
    func partners(includeUnpaired: Bool = true) throws -> [PartnerPeer] {
        var out: [PartnerPeer] = []
        let filter = includeUnpaired ? "" : " WHERE unpaired_ms IS NULL"
        try connection.query("SELECT \(Self.partnerColumns) FROM partners\(filter) ORDER BY paired_ms, owner_id") { out.append(Self.decodePartner($0)) }
        return out
    }

    /// Trusted owner ids (unpaired excluded) — the KK responder and package validation use these.
    func trustedOwnerIDs() throws -> [String] {
        try partners(includeUnpaired: false).map(\.ownerID)
    }

    /// Unpair: trust removed, data kept.
    func unpair(_ ownerID: String, nowMs: Int64) throws {
        try connection.inTransaction {
            try connection.run("UPDATE partners SET unpaired_ms=?, updated_ms=? WHERE owner_id=?", [.int(nowMs), .int(nowMs), .text(ownerID)])
            try connection.run("UPDATE partner_sync_state SET status='pairing_required' WHERE owner_id=?", [.text(ownerID)])
        }
    }

    /// Delete partner data: the partner's rows go and its cursor resets to 0 so a later sync re-sends what is shared.
    func deletePartnerData(_ ownerID: String) throws {
        try connection.inTransaction {
            try connection.run("DELETE FROM partner_records WHERE owner_id=?", [.text(ownerID)])
            try connection.run("UPDATE partner_sync_state SET last_rev=0 WHERE owner_id=?", [.text(ownerID)])
        }
    }

    /// Remove partner: trust and data (grants, records and sync state cascade).
    func removePartner(_ ownerID: String) throws {
        try connection.inTransaction {
            try connection.run("DELETE FROM partner_records WHERE owner_id=?", [.text(ownerID)])
            try connection.run("DELETE FROM partners WHERE owner_id=?", [.text(ownerID)])
        }
    }

    // MARK: Grants

    func setGrantOut(_ ownerID: String, category: String, granted: Bool, nowMs: Int64) throws {
        try connection.run("UPDATE partner_grants_out SET granted=?, updated_ms=? WHERE owner_id=? AND category=?",
                           [.int(granted ? 1 : 0), .int(nowMs), .text(ownerID), .text(category)])
        if connection.changes == 0 {
            try connection.run("INSERT INTO partner_grants_out (owner_id, category, granted, updated_ms) VALUES (?,?,?,?)",
                               [.text(ownerID), .text(category), .int(granted ? 1 : 0), .int(nowMs)])
        }
    }

    /// Categories I currently share with this partner, in contract order (default off).
    func grantsOut(_ ownerID: String) throws -> [String] {
        var on = Set<String>()
        try connection.query("SELECT category FROM partner_grants_out WHERE owner_id=? AND granted=1", [.text(ownerID)]) {
            if let c = $0.text(0) { on.insert(c) }
        }
        return PartnerCatalog.shared.categories.filter(on.contains)
    }

    func grantsReceived(_ ownerID: String) throws -> [PartnerGrantReceived] {
        var out: [PartnerGrantReceived] = []
        try connection.query("SELECT category, granted, revoked_ms, updated_ms FROM partner_grants_received WHERE owner_id=?", [.text(ownerID)]) { s in
            out.append(PartnerGrantReceived(category: s.text(0) ?? "", granted: (s.int(1) ?? 0) != 0, revokedMs: s.int64(2), updatedMs: s.int64(3) ?? 0))
        }
        let order = PartnerCatalog.shared.categories
        return out.sorted { (order.firstIndex(of: $0.category) ?? .max) < (order.firstIndex(of: $1.category) ?? .max) }
    }

    func grantedCategoriesReceived(_ ownerID: String) throws -> [String] {
        try grantsReceived(ownerID).filter(\.granted).map(\.category)
    }

    /// Applies HELLO / manifest grants via `grants_received_update` (revoked categories keep their data).
    @discardableResult
    func updateGrantsReceived(_ ownerID: String, grantedNow: [String], nowMs: Int64) throws -> [PartnerGrantReceived] {
        try connection.inTransaction {
            let previous = try grantsReceived(ownerID).map { g in
                RJ.obj(["category": .str(g.category), "granted": .bool(g.granted), "revoked_ms": g.revokedMs.map { RJ.int(Int($0)) } ?? .null])
            }
            let rows = PartnerRef.grantsReceivedUpdate(previous: previous, grantedNow: grantedNow.map(RJ.str), nowMs: Int(nowMs))
            for r in rows {
                let values: [SQLValue] = [.int(r["granted"].truthy ? 1 : 0), .optionalInt(r["revoked_ms"].pyInt), .int(nowMs),
                                          .text(ownerID), .text(r["category"].string ?? "")]
                try connection.run("UPDATE partner_grants_received SET granted=?, revoked_ms=?, updated_ms=? WHERE owner_id=? AND category=?", values)
                if connection.changes == 0 {
                    try connection.run("INSERT INTO partner_grants_received (granted, revoked_ms, updated_ms, owner_id, category) VALUES (?,?,?,?,?)", values)
                }
            }
            return try grantsReceived(ownerID)
        }
    }

    // MARK: Sync state

    func syncState(_ ownerID: String) throws -> PartnerSyncState? {
        var out: PartnerSyncState?
        try connection.query("""
            SELECT owner_id, last_rev, acked_rev, last_sync_ms, last_attempt_ms, last_transport, status, last_error, last_export_id
            FROM partner_sync_state WHERE owner_id=?
            """, [.text(ownerID)]) { s in
            out = PartnerSyncState(ownerID: s.text(0) ?? "", lastRev: s.int64(1) ?? 0, ackedRev: s.int64(2) ?? 0, lastSyncMs: s.int64(3),
                                   lastAttemptMs: s.int64(4), lastTransport: s.text(5), status: s.text(6) ?? "pairing_required",
                                   lastError: s.text(7), lastExportID: s.text(8))
        }
        return out
    }

    func saveSyncState(_ st: PartnerSyncState) throws {
        let values: [SQLValue] = [.int(st.lastRev), .int(st.ackedRev), .optionalInt64(st.lastSyncMs), .optionalInt64(st.lastAttemptMs),
                                  .optionalText(st.lastTransport), .text(st.status), .optionalText(st.lastError), .optionalText(st.lastExportID),
                                  .text(st.ownerID)]
        try connection.run("""
            UPDATE partner_sync_state SET last_rev=?, acked_rev=?, last_sync_ms=?, last_attempt_ms=?, last_transport=?, status=?,
            last_error=?, last_export_id=? WHERE owner_id=?
            """, values)
        if connection.changes == 0 {
            try connection.run("""
                INSERT INTO partner_sync_state (last_rev, acked_rev, last_sync_ms, last_attempt_ms, last_transport, status, last_error,
                last_export_id, owner_id) VALUES (?,?,?,?,?,?,?,?,?)
                """, values)
        }
    }

    /// My committed cursor into this partner's ledger.
    func cursor(_ ownerID: String) throws -> Int64 {
        try connection.scalarInt64("SELECT last_rev FROM partner_sync_state WHERE owner_id=?", [.text(ownerID)]) ?? 0
    }

    // MARK: Received records (§8)

    /// `existing` rows for `merge_apply`, read only for the keys of one batch (never the whole table).
    func existingRevs(_ ownerID: String, keys: [(type: String, recordID: String)]) throws -> [RJ] {
        let statement = try connection.prepare("SELECT rev FROM partner_records WHERE owner_id=? AND type=? AND record_id=?")
        var out: [RJ] = []
        for key in keys {
            statement.reset()
            try statement.bind([.text(ownerID), .text(key.type), .text(key.recordID)])
            if try statement.step(), let rev = statement.int(0) {
                out.append(.obj(["type": .str(key.type), "record_id": .str(key.recordID), "rev": .int(rev)]))
            }
        }
        return out
    }

    /// Writes one merge result: deletes, upserts (UPDATE-then-INSERT) and — when `commitCursor` — the new
    /// `partner_sync_state.last_rev`, all in ONE transaction. `afterEachWrite` exists for rollback tests.
    func applyMerge(ownerID: String, result: PartnerMergeOutcome, commitCursor: Bool = true,
                    afterEachWrite: (@Sendable (Int) throws -> Void)? = nil) throws {
        guard result.accepted else { return }
        try connection.inTransaction {
            try writeMerge(ownerID: ownerID, result: result, commitCursor: commitCursor, afterEachWrite: afterEachWrite)
        }
    }

    /// Writes one merge result inside the caller's transaction (package batches use it).
    func writeMerge(ownerID: String, result: PartnerMergeOutcome, commitCursor: Bool,
                            afterEachWrite: (@Sendable (Int) throws -> Void)?) throws {
        var writes = 0
        let delete = try connection.prepare("DELETE FROM partner_records WHERE owner_id=? AND type=? AND record_id=?")
        for d in result.deletes {
            delete.reset()
            try delete.bind([.text(ownerID), .text(d.type), .text(d.recordID)])
            _ = try delete.step()
            writes += 1
            try afterEachWrite?(writes)
        }
        let update = try connection.prepare("""
            UPDATE partner_records SET category=?, rev=?, day=?, ts_ms=?, updated_ms=?, data_json=?
            WHERE owner_id=? AND type=? AND record_id=?
            """)
        let insert = try connection.prepare("""
            INSERT INTO partner_records (category, rev, day, ts_ms, updated_ms, data_json, owner_id, type, record_id)
            VALUES (?,?,?,?,?,?,?,?,?)
            """)
        for u in result.upserts {
            let values: [SQLValue] = [.text(u.category), .int(u.rev), .optionalText(u.day), .optionalInt64(u.tsMs), .int(u.updatedMs),
                                      .text(u.dataJSON), .text(ownerID), .text(u.type), .text(u.recordID)]
            update.reset()
            try update.bind(values)
            _ = try update.step()
            if connection.changes == 0 {
                insert.reset()
                try insert.bind(values)
                _ = try insert.step()
            }
            writes += 1
            try afterEachWrite?(writes)
        }
        if commitCursor {
            try connection.run("UPDATE partner_sync_state SET last_rev=? WHERE owner_id=?", [.int(result.cursor), .text(ownerID)])
            if connection.changes == 0 {
                try connection.run("INSERT INTO partner_sync_state (owner_id, last_rev) VALUES (?, ?)", [.text(ownerID), .int(result.cursor)])
            }
        }
    }

    /// Reads the cursor, current grants and the batch's stored revs, runs `merge_apply` and writes the result —
    /// all inside one transaction, so a killed sync resumes from the last committed batch.
    func mergeBatch(ownerID: String, batch: RJ, nowMs: Int64) throws -> PartnerMergeOutcome {
        try connection.inTransaction {
            let cursor = try cursor(ownerID)
            let granted = try grantedCategoriesReceived(ownerID).map(RJ.str)
            let keys: [(type: String, recordID: String)] = (batch["records"].array ?? []).compactMap { env in
                guard let t = env["type"].string, let id = env["id"].string else { return nil }
                return (t, id)
            }
            let existing = try existingRevs(ownerID, keys: keys)
            let outcome = PartnerMergeOutcome(PartnerRef.mergeApply(existing: existing, batch: batch, cursor: Int(cursor), granted: granted, nowMs: Int(nowMs)))
            if outcome.accepted {
                try writeMerge(ownerID: ownerID, result: outcome, commitCursor: true, afterEachWrite: nil)
            }
            return outcome
        }
    }

    func record(_ ownerID: String, type: String, recordID: String) throws -> PartnerRecordRow? {
        var out: PartnerRecordRow?
        try connection.query("""
            SELECT type, record_id, category, rev, day, ts_ms, updated_ms, data_json FROM partner_records
            WHERE owner_id=? AND type=? AND record_id=?
            """, [.text(ownerID), .text(type), .text(recordID)]) { s in
            out = PartnerRecordRow(type: s.text(0) ?? "", recordID: s.text(1) ?? "", category: s.text(2) ?? "", rev: s.int64(3) ?? 0,
                                   day: s.text(4), tsMs: s.int64(5), updatedMs: s.int64(6) ?? 0, dataJSON: s.text(7) ?? "null")
        }
        return out
    }

    func recordCount(_ ownerID: String) throws -> Int {
        Int(try connection.scalarInt64("SELECT COUNT(*) FROM partner_records WHERE owner_id=?", [.text(ownerID)]) ?? 0)
    }

    /// `retention_prune` in SQL: received rows of types with `retention_days` whose ts_ms is older than the window.
    @discardableResult
    func retentionPrune(nowMs: Int64) throws -> Int {
        let catalog = PartnerCatalog.shared
        var deleted = 0
        try connection.inTransaction {
            for type in catalog.typeOrder {
                guard let days = catalog.types[type]?.retentionDays, days > 0 else { continue }
                try connection.run("DELETE FROM partner_records WHERE type=? AND ts_ms IS NOT NULL AND ts_ms < ?",
                                   [.text(type), .int(nowMs - Int64(days) * Int64(PartnerCatalog.dayMs))])
                deleted += connection.changes
            }
        }
        return deleted
    }

    // MARK: Outbound ledger (§10)

    nonisolated static func decodeLedger(_ s: HealthDBStatement) -> PartnerLedgerRow {
        PartnerLedgerRow(type: s.text(0) ?? "", recordID: s.text(1) ?? "", category: s.text(2) ?? "", day: s.text(3),
                         contentHash: s.text(4) ?? "", rev: s.int64(5) ?? 0, deleted: (s.int(6) ?? 0) != 0)
    }

    func ledgerRows(types: [String]) throws -> [PartnerLedgerRow] {
        var out: [PartnerLedgerRow] = []
        for type in types {
            try connection.query("SELECT type, record_id, category, day, content_hash, rev, deleted FROM outbound_ledger WHERE type=? ORDER BY record_id",
                                 [.text(type)]) { out.append(Self.decodeLedger($0)) }
        }
        return out
    }

    private func writeLedgerRow(_ r: PartnerLedgerRow, update: HealthDBStatement, insert: HealthDBStatement) throws {
        let values: [SQLValue] = [.text(r.category), .optionalText(r.day), .text(r.contentHash), .int(r.rev), .int(r.deleted ? 1 : 0),
                                  .text(r.type), .text(r.recordID)]
        update.reset()
        try update.bind(values)
        _ = try update.step()
        if connection.changes == 0 {
            insert.reset()
            try insert.bind(values)
            _ = try insert.step()
        }
    }

    private func ledgerWriters() throws -> (HealthDBStatement, HealthDBStatement) {
        (try connection.prepare("UPDATE outbound_ledger SET category=?, day=?, content_hash=?, rev=?, deleted=? WHERE type=? AND record_id=?"),
         try connection.prepare("INSERT INTO outbound_ledger (category, day, content_hash, rev, deleted, type, record_id) VALUES (?,?,?,?,?,?,?)"))
    }

    /// Bulk UPDATE-then-INSERT of ledger rows (one transaction).
    func upsertLedgerRows(_ rows: [PartnerLedgerRow]) throws {
        try connection.inTransaction {
            let (update, insert) = try ledgerWriters()
            for r in rows { try writeLedgerRow(r, update: update, insert: insert) }
        }
    }

    /// `ledger_refresh` for the given scopes: loads the ledger rows of the scoped / rendered types, assigns revs
    /// exactly like the reference and writes the changed rows plus the new counter in one transaction.
    @discardableResult
    func applyLedgerRefresh(current: [RJ], scopes: [RJ]) throws -> (changed: Int, tombstoned: Int, rev: Int64) {
        try connection.inTransaction {
            let oldRev = try outboundRev()
            var types: [String] = []
            for t in scopes.compactMap({ $0["type"].string }) + current.compactMap({ $0["type"].string }) where !types.contains(t) {
                types.append(t)
            }
            let ledger = try ledgerRows(types: types).map(\.rj)
            let res = PartnerRef.ledgerRefresh(ledger: ledger, rev: Int(oldRev), current: current, scopes: scopes)
            let (update, insert) = try ledgerWriters()
            for row in res["ledger"].array ?? [] where (row["rev"].pyInt ?? 0) > Int(oldRev) {
                guard let r = PartnerLedgerRow(rj: row) else { continue }
                try writeLedgerRow(r, update: update, insert: insert)
            }
            let newRev = Int64(res["rev"].pyInt ?? Int(oldRev))
            try setOutboundRev(newRev)
            return (res["changed"].pyInt ?? 0, res["tombstoned"].pyInt ?? 0, newRev)
        }
    }

    /// `ledger_prune`: drops intraday ledger rows whose day is before the window (no rev).
    @discardableResult
    func ledgerPrune(intradayDayFrom: String) throws -> Int {
        let catalog = PartnerCatalog.shared
        var deleted = 0
        try connection.inTransaction {
            for type in catalog.typeOrder {
                guard let days = catalog.types[type]?.retentionDays, days > 0 else { continue }
                try connection.run("DELETE FROM outbound_ledger WHERE type=? AND day IS NOT NULL AND day < ?", [.text(type), .text(intradayDayFrom)])
                deleted += connection.changes
            }
        }
        return deleted
    }

    /// `ledger_regrant`: every row of the category gets a fresh rev in (type, record_id) byte order.
    @discardableResult
    func ledgerRegrant(category: String) throws -> Int64 {
        try connection.inTransaction {
            var rev = try outboundRev()
            var keys: [(String, String)] = []
            try connection.query("SELECT type, record_id FROM outbound_ledger WHERE category=? ORDER BY type, record_id", [.text(category)]) {
                keys.append(($0.text(0) ?? "", $0.text(1) ?? ""))
            }
            let update = try connection.prepare("UPDATE outbound_ledger SET rev=? WHERE type=? AND record_id=?")
            for (type, id) in keys {
                rev += 1
                update.reset()
                try update.bind([.int(rev), .text(type), .text(id)])
                _ = try update.step()
            }
            try setOutboundRev(rev)
            return rev
        }
    }

    /// `ledger_delta` page: `rev > ? AND category IN (…) ORDER BY rev LIMIT ?` — never loads the full table.
    func ledgerDelta(cursor startCursor: Int64, grants: [String], limit: Int = PartnerCatalog.shared.batchMax) throws -> PartnerDelta {
        let rev = try outboundRev()
        let cursor = startCursor > rev ? 0 : startCursor
        var keys: [PartnerDeltaKey] = []
        if !grants.isEmpty, limit > 0 {
            let placeholders = Array(repeating: "?", count: grants.count).joined(separator: ",")
            try connection.query("""
                SELECT type, record_id, rev, deleted FROM outbound_ledger WHERE rev > ? AND category IN (\(placeholders))
                ORDER BY rev, type, record_id LIMIT ?
                """, [.int(cursor)] + grants.map(SQLValue.text) + [.int(Int64(limit) + 1)]) { s in
                keys.append(PartnerDeltaKey(type: s.text(0) ?? "", recordID: s.text(1) ?? "", rev: s.int64(2) ?? 0, deleted: (s.int(3) ?? 0) != 0))
            }
        }
        let hasMore = keys.count > limit
        if hasMore { keys.removeLast(keys.count - limit) }
        return PartnerDelta(fromRev: cursor, toRev: hasMore ? (keys.last?.rev ?? rev) : rev, hasMore: hasMore, keys: keys)
    }
}
