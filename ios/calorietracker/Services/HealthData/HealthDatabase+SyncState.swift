import Foundation

extension HealthDatabase {
    private nonisolated static let syncStateColumns =
        "type_id, cursor, cursor_issued_ms, last_sync_ms, earliest_authorized_ms, earliest_probe_ms, backfill_floor_ms, oldest_backfilled_ms, "
        + "backfill_done, backfill_with_history, status, last_error, last_error_ms, ipc_calls_total"

    nonisolated static func decodeSyncState(_ s: HealthDBStatement) -> HealthSyncStateRow {
        var row = HealthSyncStateRow(typeID: s.text(0) ?? "")
        row.cursor = s.text(1)
        row.cursorIssuedMs = s.int64(2)
        row.lastSyncMs = s.int64(3)
        row.earliestAuthorizedMs = s.int64(4)
        row.earliestProbeMs = s.int64(5)
        row.backfillFloorMs = s.int64(6)
        row.oldestBackfilledMs = s.int64(7)
        row.backfillDone = s.int(8) ?? 0
        row.backfillWithHistory = s.int(9) ?? 0
        row.status = s.text(10) ?? "idle"
        row.lastError = s.text(11)
        row.lastErrorMs = s.int64(12)
        row.ipcCallsTotal = s.int(13) ?? 0
        return row
    }

    func syncState(type: String) throws -> HealthSyncStateRow? {
        var row: HealthSyncStateRow?
        try connection.query("SELECT \(Self.syncStateColumns) FROM health_sync_state WHERE type_id=?", [.text(type)]) {
            row = Self.decodeSyncState($0)
        }
        return row
    }

    func allSyncStates() throws -> [HealthSyncStateRow] {
        var rows: [HealthSyncStateRow] = []
        try connection.query("SELECT \(Self.syncStateColumns) FROM health_sync_state ORDER BY type_id") {
            rows.append(Self.decodeSyncState($0))
        }
        return rows
    }

    func setSyncState(_ row: HealthSyncStateRow) throws {
        try connection.inTransaction {
            try setSyncStateInTransaction(row)
        }
    }

    func setSyncStateInTransaction(_ r: HealthSyncStateRow) throws {
        try connection.run(
            "INSERT OR REPLACE INTO health_sync_state (\(Self.syncStateColumns)) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            [
                .text(r.typeID), .optionalText(r.cursor), .optionalInt64(r.cursorIssuedMs), .optionalInt64(r.lastSyncMs),
                .optionalInt64(r.earliestAuthorizedMs), .optionalInt64(r.earliestProbeMs), .optionalInt64(r.backfillFloorMs),
                .optionalInt64(r.oldestBackfilledMs), .int(Int64(r.backfillDone)), .int(Int64(r.backfillWithHistory)),
                .text(r.status), .optionalText(r.lastError), .optionalInt64(r.lastErrorMs), .int(Int64(r.ipcCallsTotal)),
            ]
        )
    }

    func updateStatus(type: String, status: String, error: String?, nowMs: Int64) throws {
        var row = try syncState(type: type) ?? HealthSyncStateRow(typeID: type)
        row.status = status
        if let error {
            row.lastError = error
            row.lastErrorMs = nowMs
        }
        try setSyncState(row)
    }

    func clearCursor(type: String) throws {
        var row = try syncState(type: type) ?? HealthSyncStateRow(typeID: type)
        row.cursor = nil
        row.cursorIssuedMs = nil
        row.backfillDone = 0
        try setSyncState(row)
    }

    func clearAllCursors() throws {
        try connection.inTransaction {
            try connection.exec("UPDATE health_sync_state SET cursor=NULL, cursor_issued_ms=NULL, backfill_done=0, status='idle'")
        }
    }

    // MARK: - health_type_meta (unknown / imported types)

    func upsertTypeMeta(_ meta: HealthTypeMetaRow) throws {
        try connection.inTransaction {
            try upsertTypeMetaInTransaction(meta)
        }
    }

    func upsertTypeMetaInTransaction(_ m: HealthTypeMetaRow) throws {
        try connection.run(
            "INSERT OR REPLACE INTO health_type_meta (type_id, category, kind, aggregation, unit, display_name, platform, native_id) VALUES (?,?,?,?,?,?,?,?)",
            [
                .text(m.typeID), .text(m.category), .text(m.kind), .text(m.aggregation), .text(m.unit),
                .optionalText(m.displayName), .optionalText(m.platform), .optionalText(m.nativeID),
            ]
        )
    }

    func allTypeMeta() throws -> [HealthTypeMetaRow] {
        var rows: [HealthTypeMetaRow] = []
        try connection.query("SELECT type_id, category, kind, aggregation, unit, display_name, platform, native_id FROM health_type_meta ORDER BY type_id") { s in
            rows.append(HealthTypeMetaRow(
                typeID: s.text(0) ?? "", category: s.text(1) ?? "other", kind: s.text(2) ?? "discrete",
                aggregation: s.text(3) ?? "LATEST", unit: s.text(4) ?? "count", displayName: s.text(5),
                platform: s.text(6), nativeID: s.text(7)
            ))
        }
        return rows
    }
}
