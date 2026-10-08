import Foundation

// Read-only queries behind the Partner screens (Summary card, dashboard, report detail). They run on the database
// actor, so the main actor never touches SQLite, and every query is bounded by owner, type and a day window.

extension PartnerDatabase {
    /// Received rows of the given types for one owner; with `dayFrom`, only rows on or after that day (rows without a
    /// day, such as medications, are always included).
    func records(_ ownerID: String, types: [String], dayFrom: String?) throws -> [PartnerRecordRow] {
        guard !types.isEmpty else { return [] }
        var out: [PartnerRecordRow] = []
        let placeholders = Array(repeating: "?", count: types.count).joined(separator: ",")
        var sql = "SELECT type, record_id, category, rev, day, ts_ms, updated_ms, data_json FROM partner_records WHERE owner_id=? AND type IN (\(placeholders))"
        var values: [SQLValue] = [.text(ownerID)] + types.map(SQLValue.text)
        if let dayFrom {
            sql += " AND (day IS NULL OR day >= ?)"
            values.append(.text(dayFrom))
        }
        sql += " ORDER BY type, record_id"
        try connection.query(sql, values) { s in
            out.append(PartnerRecordRow(type: s.text(0) ?? "", recordID: s.text(1) ?? "", category: s.text(2) ?? "", rev: s.int64(3) ?? 0,
                                        day: s.text(4), tsMs: s.int64(5), updatedMs: s.int64(6) ?? 0, dataJSON: s.text(7) ?? "null"))
        }
        return out
    }

    /// The newest `<type>` row whose record id starts with `<prefix>` (e.g. the latest `metric_day` of
    /// `resting_heart_rate:`), whatever its age. Uses the primary-key range, never a table scan.
    func latestRecord(_ ownerID: String, type: String, idPrefix: String) throws -> PartnerRecordRow? {
        var out: PartnerRecordRow?
        // ';' sorts right after ':' so [prefix, prefix-with-';') is exactly the ids starting with "<id>:".
        let upper = String(idPrefix.dropLast()) + ";"
        try connection.query("""
            SELECT type, record_id, category, rev, day, ts_ms, updated_ms, data_json FROM partner_records
            WHERE owner_id=? AND type=? AND record_id >= ? AND record_id < ? ORDER BY day DESC, record_id DESC LIMIT 1
            """, [.text(ownerID), .text(type), .text(idPrefix), .text(upper)]) { s in
            out = PartnerRecordRow(type: s.text(0) ?? "", recordID: s.text(1) ?? "", category: s.text(2) ?? "", rev: s.int64(3) ?? 0,
                                   day: s.text(4), tsMs: s.int64(5), updatedMs: s.int64(6) ?? 0, dataJSON: s.text(7) ?? "null")
        }
        return out
    }

    /// The newest row of a type by its timestamp column (app weight entries).
    func latestRecord(_ ownerID: String, type: String) throws -> PartnerRecordRow? {
        var out: PartnerRecordRow?
        try connection.query("""
            SELECT type, record_id, category, rev, day, ts_ms, updated_ms, data_json FROM partner_records
            WHERE owner_id=? AND type=? ORDER BY day DESC, ts_ms DESC LIMIT 1
            """, [.text(ownerID), .text(type)]) { s in
            out = PartnerRecordRow(type: s.text(0) ?? "", recordID: s.text(1) ?? "", category: s.text(2) ?? "", rev: s.int64(3) ?? 0,
                                   day: s.text(4), tsMs: s.int64(5), updatedMs: s.int64(6) ?? 0, dataJSON: s.text(7) ?? "null")
        }
        return out
    }

    /// Received categories that hold at least one row (a revoked category keeps its data).
    func categoriesWithData(_ ownerID: String) throws -> Set<String> {
        var out = Set<String>()
        try connection.query("SELECT DISTINCT category FROM partner_records WHERE owner_id=?", [.text(ownerID)]) {
            if let c = $0.text(0) { out.insert(c) }
        }
        return out
    }
}
