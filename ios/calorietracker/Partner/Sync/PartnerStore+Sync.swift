import Foundation

// Store operations used by the ledger refresher, the session engine and the package code (docs §8–§10, §12–§14).

/// One `ledger_delta` page with each row's category (the session renders envelopes / tombstones from it).
nonisolated struct PartnerDeltaPage: Sendable, Equatable {
    var fromRev: Int64
    var toRev: Int64
    var hasMore: Bool
    var rows: [PartnerLedgerRow]
}

extension PartnerDatabase {
    // MARK: Ledger refresh staging (bounded memory)

    /// Starts a refresh: an empty TEMP staging table for the rendered `current` rows and one for the scopes.
    /// TEMP tables live only on this connection and never appear in `sqlite_master`.
    func refreshBegin() throws {
        try connection.exec("""
            CREATE TEMP TABLE IF NOT EXISTS partner_refresh_current (type TEXT NOT NULL, record_id TEXT NOT NULL, category TEXT NOT NULL,
              day TEXT, content_hash TEXT NOT NULL, PRIMARY KEY (type, record_id));
            CREATE TEMP TABLE IF NOT EXISTS partner_refresh_scope (type TEXT PRIMARY KEY NOT NULL, day_from TEXT);
            DELETE FROM partner_refresh_current;
            DELETE FROM partner_refresh_scope;
            """)
    }

    /// Stages one page of rendered records (`current` rows of `ledger_refresh`).
    func refreshStage(_ records: [PartnerSourceRecord]) throws {
        guard !records.isEmpty else { return }
        try connection.inTransaction {
            let insert = try connection.prepare("INSERT OR REPLACE INTO partner_refresh_current (type, record_id, category, day, content_hash) VALUES (?,?,?,?,?)")
            for r in records {
                insert.reset()
                try insert.bind([.text(r.type), .text(r.recordID), .text(r.category), .optionalText(r.day), .text(r.contentHash)])
                _ = try insert.step()
            }
        }
    }

    /// Records that the refresh looked at `type` (`scopes` of `ledger_refresh`); only scoped types can tombstone.
    func refreshAddScope(type: String, dayFrom: String?) throws {
        try connection.run("INSERT OR REPLACE INTO partner_refresh_scope (type, day_from) VALUES (?, ?)", [.text(type), .optionalText(dayFrom)])
    }

    /// `ledger_refresh` over the staged rows, in one transaction: new / changed rows (hash, category or day) get the
    /// next revs in (type, record_id) byte order, then scoped ledger rows without a current record become tombstones,
    /// also in (type, record_id) order. Reads and writes in pages, so the ledger is never loaded whole.
    @discardableResult
    func refreshCommit(batch: Int = 1000) throws -> (changed: Int, tombstoned: Int, rev: Int64) {
        let result = try connection.inTransaction { () -> (Int, Int, Int64) in
            var rev = try outboundRev()
            var changed = 0
            var tombstoned = 0
            let update = try connection.prepare("UPDATE outbound_ledger SET category=?, day=?, content_hash=?, rev=?, deleted=0 WHERE type=? AND record_id=?")
            let insert = try connection.prepare("INSERT INTO outbound_ledger (category, day, content_hash, rev, deleted, type, record_id) VALUES (?,?,?,?,0,?,?)")
            var after: (String, String)?
            while true {
                var page: [(String, String, String, String?, String)] = []
                var sql = """
                    SELECT c.type, c.record_id, c.category, c.day, c.content_hash FROM partner_refresh_current c
                    LEFT JOIN outbound_ledger l ON l.type = c.type AND l.record_id = c.record_id
                    WHERE (l.type IS NULL OR l.deleted = 1 OR l.content_hash <> c.content_hash OR l.category <> c.category OR l.day IS NOT c.day)
                    """
                var values: [SQLValue] = []
                if let after { sql += " AND (c.type, c.record_id) > (?, ?)"; values += [.text(after.0), .text(after.1)] }
                sql += " ORDER BY c.type, c.record_id LIMIT ?"
                values.append(.int(Int64(batch)))
                try connection.query(sql, values) { s in
                    page.append((s.text(0) ?? "", s.text(1) ?? "", s.text(2) ?? "", s.text(3), s.text(4) ?? ""))
                }
                for (type, id, category, day, hash) in page {
                    rev += 1
                    let v: [SQLValue] = [.text(category), .optionalText(day), .text(hash), .int(rev), .text(type), .text(id)]
                    update.reset()
                    try update.bind(v)
                    _ = try update.step()
                    if connection.changes == 0 {
                        insert.reset()
                        try insert.bind(v)
                        _ = try insert.step()
                    }
                    changed += 1
                }
                guard page.count >= batch, let last = page.last else { break }
                after = (last.0, last.1)
            }
            let tomb = try connection.prepare("UPDATE outbound_ledger SET rev=?, deleted=1 WHERE type=? AND record_id=?")
            after = nil
            while true {
                var page: [(String, String)] = []
                var sql = """
                    SELECT l.type, l.record_id FROM outbound_ledger l JOIN partner_refresh_scope sc ON sc.type = l.type
                    WHERE l.deleted = 0 AND (sc.day_from IS NULL OR l.day IS NULL OR l.day >= sc.day_from)
                    AND NOT EXISTS (SELECT 1 FROM partner_refresh_current c WHERE c.type = l.type AND c.record_id = l.record_id)
                    """
                var values: [SQLValue] = []
                if let after { sql += " AND (l.type, l.record_id) > (?, ?)"; values += [.text(after.0), .text(after.1)] }
                sql += " ORDER BY l.type, l.record_id LIMIT ?"
                values.append(.int(Int64(batch)))
                try connection.query(sql, values) { s in page.append((s.text(0) ?? "", s.text(1) ?? "")) }
                for (type, id) in page {
                    rev += 1
                    tomb.reset()
                    try tomb.bind([.int(rev), .text(type), .text(id)])
                    _ = try tomb.step()
                    tombstoned += 1
                }
                guard page.count >= batch, let last = page.last else { break }
                after = last
            }
            try setOutboundRev(rev)
            return (changed, tombstoned, rev)
        }
        try refreshDiscard()
        return (result.0, result.1, result.2)
    }

    func refreshDiscard() throws {
        try connection.exec("DELETE FROM partner_refresh_current; DELETE FROM partner_refresh_scope;")
    }

    func ledgerCount() throws -> Int {
        Int(try connection.scalarInt64("SELECT COUNT(*) FROM outbound_ledger") ?? 0)
    }

    func ledgerRow(type: String, recordID: String) throws -> PartnerLedgerRow? {
        var out: PartnerLedgerRow?
        try connection.query("SELECT type, record_id, category, day, content_hash, rev, deleted FROM outbound_ledger WHERE type=? AND record_id=?",
                             [.text(type), .text(recordID)]) { out = Self.decodeLedger($0) }
        return out
    }

    // MARK: Delta with categories

    /// `ledger_delta` (same semantics as `ledgerDelta`) returning full ledger rows.
    func ledgerDeltaPage(cursor startCursor: Int64, grants: [String], limit: Int = PartnerCatalog.shared.batchMax) throws -> PartnerDeltaPage {
        let rev = try outboundRev()
        let cursor = startCursor > rev ? 0 : startCursor
        var rows: [PartnerLedgerRow] = []
        if !grants.isEmpty, limit > 0 {
            let placeholders = Array(repeating: "?", count: grants.count).joined(separator: ",")
            try connection.query("""
                SELECT type, record_id, category, day, content_hash, rev, deleted FROM outbound_ledger
                WHERE rev > ? AND category IN (\(placeholders)) ORDER BY rev, type, record_id LIMIT ?
                """, [.int(cursor)] + grants.map(SQLValue.text) + [.int(Int64(limit) + 1)]) { rows.append(Self.decodeLedger($0)) }
        }
        let hasMore = rows.count > limit
        if hasMore { rows.removeLast(rows.count - limit) }
        return PartnerDeltaPage(fromRev: cursor, toRev: hasMore ? (rows.last?.rev ?? rev) : rev, hasMore: hasMore, rows: rows)
    }

    // MARK: Sync state

    /// Read-modify-write of one partner's sync state (atomic on the actor).
    @discardableResult
    func updateSyncState(_ ownerID: String, _ body: (inout PartnerSyncState) -> Void) throws -> PartnerSyncState {
        var st = try syncState(ownerID) ?? PartnerSyncState(ownerID: ownerID)
        body(&st)
        try saveSyncState(st)
        return st
    }

    func setLastAddress(_ ownerID: String, host: String, port: Int, nowMs: Int64) throws {
        try connection.run("UPDATE partners SET last_host=?, last_port=?, updated_ms=? WHERE owner_id=?",
                           [.text(host), .int(Int64(port)), .int(nowMs), .text(ownerID)])
    }

    /// Partners whose X25519 key is trusted (unpaired excluded) — the KK responder's candidate list.
    func trustedPeers() throws -> [PartnerPeer] {
        try partners(includeUnpaired: false)
    }

    /// Sets every grant-out row of a partner (categories not listed are turned off).
    func setGrantsOut(_ ownerID: String, granted: [String], nowMs: Int64) throws {
        try connection.inTransaction {
            for cat in PartnerCatalog.shared.categories {
                try setGrantOut(ownerID, category: cat, granted: granted.contains(cat), nowMs: nowMs)
            }
        }
    }

    /// Turns a category on or off for a partner. Turning it (back) on runs `ledger_regrant` (§9): every ledger row
    /// of the category gets a fresh rev, so a partner whose cursor moved past them while it was off receives them.
    func grantCategory(_ ownerID: String, category: String, granted: Bool, nowMs: Int64) throws {
        let wasOn = try grantsOut(ownerID).contains(category)
        try setGrantOut(ownerID, category: category, granted: granted, nowMs: nowMs)
        if granted, !wasOn { try ledgerRegrant(category: category) }
    }

    /// Received rows of one owner (tests / UI).
    func records(_ ownerID: String, type: String? = nil) throws -> [PartnerRecordRow] {
        var out: [PartnerRecordRow] = []
        var sql = "SELECT type, record_id, category, rev, day, ts_ms, updated_ms, data_json FROM partner_records WHERE owner_id=?"
        var values: [SQLValue] = [.text(ownerID)]
        if let type { sql += " AND type=?"; values.append(.text(type)) }
        sql += " ORDER BY type, record_id"
        try connection.query(sql, values) { s in
            out.append(PartnerRecordRow(type: s.text(0) ?? "", recordID: s.text(1) ?? "", category: s.text(2) ?? "", rev: s.int64(3) ?? 0,
                                        day: s.text(4), tsMs: s.int64(5), updatedMs: s.int64(6) ?? 0, dataJSON: s.text(7) ?? "null"))
        }
        return out
    }

    /// Re-pairing with changed keys: data and cursors are dropped (§4 rules).
    func resetForNewKeys(_ ownerID: String) throws {
        try connection.inTransaction {
            try connection.run("DELETE FROM partner_records WHERE owner_id=?", [.text(ownerID)])
            try connection.run("UPDATE partner_sync_state SET last_rev=0, acked_rev=0 WHERE owner_id=?", [.text(ownerID)])
        }
    }
}
