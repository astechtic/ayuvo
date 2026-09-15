import Foundation

extension HealthDatabase {
    nonisolated static let sampleColumns =
        "id, type_id, start_ms, end_ms, start_offset_s, end_offset_s, local_day, value, value2, value3, value_text, unit, "
        + "category_value, title, extra_json, count, source_id, device, device_type, recording_method, client_record_id, origin, deleted, updated_ms"

    private nonisolated static let insertSampleSQL =
        "INSERT OR IGNORE INTO health_samples (\(sampleColumns)) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)"

    /// Newer wins; tombstones are never resurrected; a platform row keeps `origin = 0`
    /// even when an import touches it.
    private nonisolated static let updateSampleSQL = """
    UPDATE health_samples SET type_id=?, start_ms=?, end_ms=?, start_offset_s=?, end_offset_s=?, local_day=?, value=?, value2=?, value3=?,
      value_text=?, unit=?, category_value=?, title=?, extra_json=?, count=?, source_id=?, device=?, device_type=?, recording_method=?,
      client_record_id=?, origin=CASE WHEN origin=0 THEN 0 ELSE ? END, deleted=?, updated_ms=?
    WHERE id=? AND updated_ms<? AND deleted=0
    """

    nonisolated static func decodeSample(_ s: HealthDBStatement) -> HealthSampleRow {
        HealthSampleRow(
            id: s.text(0) ?? "",
            typeID: s.text(1) ?? "",
            startMs: s.int64(2) ?? 0,
            endMs: s.int64(3) ?? 0,
            startOffsetS: s.int(4),
            endOffsetS: s.int(5),
            localDay: s.text(6) ?? "",
            value: s.double(7),
            value2: s.double(8),
            value3: s.double(9),
            valueText: s.text(10),
            unit: s.text(11) ?? "",
            categoryValue: s.int(12),
            title: s.text(13),
            extraJSON: s.text(14),
            count: s.int(15) ?? 1,
            sourceID: s.text(16) ?? "",
            device: s.text(17),
            deviceType: s.int(18),
            recordingMethod: s.int(19),
            clientRecordID: s.text(20),
            origin: s.int(21) ?? 0,
            deleted: s.int(22) ?? 0,
            updatedMs: s.int64(23) ?? 0
        )
    }

    private nonisolated static func insertValues(_ r: HealthSampleRow) -> [SQLValue] {
        [
            .text(r.id), .text(r.typeID), .int(r.startMs), .int(r.endMs), .optionalInt(r.startOffsetS), .optionalInt(r.endOffsetS),
            .text(r.localDay), .optionalReal(r.value), .optionalReal(r.value2), .optionalReal(r.value3), .optionalText(r.valueText),
            .text(r.unit), .optionalInt(r.categoryValue), .optionalText(r.title), .optionalText(r.extraJSON), .int(Int64(r.count)),
            .text(r.sourceID), .optionalText(r.device), .optionalInt(r.deviceType), .optionalInt(r.recordingMethod),
            .optionalText(r.clientRecordID), .int(Int64(r.origin)), .int(Int64(r.deleted)), .int(r.updatedMs),
        ]
    }

    private nonisolated static func updateValues(_ r: HealthSampleRow) -> [SQLValue] {
        [
            .text(r.typeID), .int(r.startMs), .int(r.endMs), .optionalInt(r.startOffsetS), .optionalInt(r.endOffsetS),
            .text(r.localDay), .optionalReal(r.value), .optionalReal(r.value2), .optionalReal(r.value3), .optionalText(r.valueText),
            .text(r.unit), .optionalInt(r.categoryValue), .optionalText(r.title), .optionalText(r.extraJSON), .int(Int64(r.count)),
            .text(r.sourceID), .optionalText(r.device), .optionalInt(r.deviceType), .optionalInt(r.recordingMethod),
            .optionalText(r.clientRecordID), .int(Int64(r.origin)), .int(Int64(r.deleted)), .int(r.updatedMs),
            .text(r.id), .int(r.updatedMs),
        ]
    }

    // MARK: - Writes

    nonisolated struct UpsertResult: Sendable, Equatable {
        var inserted = 0
        var updated = 0
        var unchanged = 0
    }

    /// Upsert inside its own transaction.
    @discardableResult
    func upsertSamples(_ rows: [HealthSampleRow]) throws -> UpsertResult {
        try connection.inTransaction {
            try upsertSamplesInTransaction(rows)
        }
    }

    /// Upsert for callers that already hold a transaction (`commitPage`, importer batches).
    @discardableResult
    func upsertSamplesInTransaction(_ rows: [HealthSampleRow]) throws -> UpsertResult {
        var result = UpsertResult()
        guard !rows.isEmpty else { return result }
        let update = try connection.prepare(Self.updateSampleSQL)
        let insert = try connection.prepare(Self.insertSampleSQL)
        for row in rows {
            update.reset()
            try update.bind(Self.updateValues(row))
            _ = try update.step()
            if connection.changes > 0 {
                result.updated += 1
                continue
            }
            insert.reset()
            try insert.bind(Self.insertValues(row))
            _ = try insert.step()
            if connection.changes > 0 {
                result.inserted += 1
            } else {
                result.unchanged += 1
            }
        }
        return result
    }

    /// Marks platform rows deleted. Imported (`origin = 1`) rows are immune.
    func tombstone(ids: [String], nowMs: Int64) throws {
        try connection.inTransaction {
            try tombstoneInTransaction(ids: ids, nowMs: nowMs)
        }
    }

    func tombstoneInTransaction(ids: [String], nowMs: Int64) throws {
        guard !ids.isEmpty else { return }
        let statement = try connection.prepare(
            "UPDATE health_samples SET deleted=1, updated_ms=MAX(updated_ms, ?) WHERE id=? AND origin=0"
        )
        for id in ids {
            statement.reset()
            try statement.bind([.int(nowMs), .text(id)])
            _ = try statement.step()
        }
    }

    func upsertSources(_ sources: [HealthSourceRow]) throws {
        try connection.inTransaction {
            try upsertSourcesInTransaction(sources)
        }
    }

    func upsertSourcesInTransaction(_ sources: [HealthSourceRow]) throws {
        guard !sources.isEmpty else { return }
        let statement = try connection.prepare("""
        INSERT INTO health_sources (id, name, device_model, device_type, last_seen_ms) VALUES (?,?,?,?,?)
        ON CONFLICT(id) DO UPDATE SET
          name=excluded.name,
          device_model=COALESCE(excluded.device_model, health_sources.device_model),
          device_type=COALESCE(excluded.device_type, health_sources.device_type),
          last_seen_ms=MAX(COALESCE(health_sources.last_seen_ms, 0), COALESCE(excluded.last_seen_ms, 0))
        """)
        for source in sources {
            statement.reset()
            try statement.bind([
                .text(source.id), .text(source.name), .optionalText(source.deviceModel),
                .optionalInt(source.deviceType), .optionalInt64(source.lastSeenMs),
            ])
            _ = try statement.step()
        }
    }

    /// One sync page: rows + tombstones + sources + rollups + cursor in a single transaction.
    @discardableResult
    func commitPage(_ page: HealthCommitPage, nowMs: Int64) throws -> UpsertResult {
        try connection.inTransaction {
            let result = try upsertSamplesInTransaction(page.rows)
            try tombstoneInTransaction(ids: page.deletedIDs, nowMs: nowMs)
            try upsertSourcesInTransaction(page.sources)
            try replaceDailyRollupsInTransaction(page.rollups)
            if let state = page.syncState {
                try setSyncStateInTransaction(state)
            }
            return result
        }
    }

    /// Physically removes rows of one type (import "Replace all" on a type, tests).
    func deleteSamples(type: String) throws {
        try connection.inTransaction {
            try connection.run("DELETE FROM health_samples WHERE type_id=?", [.text(type)])
            try connection.run("DELETE FROM health_daily_rollups WHERE type_id=?", [.text(type)])
            try connection.run("DELETE FROM health_hourly_rollups WHERE type_id=?", [.text(type)])
        }
    }

    // MARK: - Reads

    func sample(id: String) throws -> HealthSampleRow? {
        var row: HealthSampleRow?
        try connection.query("SELECT \(Self.sampleColumns) FROM health_samples WHERE id=?", [.text(id)]) {
            row = Self.decodeSample($0)
        }
        return row
    }

    /// Non-deleted rows overlapping `[startMs, endMs)`, oldest first.
    func rows(type: String, startMs: Int64, endMs: Int64, limit: Int? = nil) throws -> [HealthSampleRow] {
        var rows: [HealthSampleRow] = []
        var sql = "SELECT \(Self.sampleColumns) FROM health_samples WHERE type_id=? AND deleted=0 AND end_ms>? AND start_ms<? ORDER BY start_ms, id"
        var values: [SQLValue] = [.text(type), .int(startMs), .int(endMs)]
        if let limit {
            sql += " LIMIT ?"
            values.append(.int(Int64(limit)))
        }
        try connection.query(sql, values) { rows.append(Self.decodeSample($0)) }
        return rows
    }

    func rowsForDay(type: String, day: String) throws -> [HealthSampleRow] {
        var rows: [HealthSampleRow] = []
        try connection.query(
            "SELECT \(Self.sampleColumns) FROM health_samples WHERE type_id=? AND local_day=? AND deleted=0 ORDER BY start_ms, id",
            [.text(type), .text(day)]
        ) { rows.append(Self.decodeSample($0)) }
        return rows
    }

    func rowsForDays(type: String, fromDay: String, toDay: String) throws -> [HealthSampleRow] {
        var rows: [HealthSampleRow] = []
        try connection.query(
            "SELECT \(Self.sampleColumns) FROM health_samples WHERE type_id=? AND local_day BETWEEN ? AND ? AND deleted=0 ORDER BY start_ms, id",
            [.text(type), .text(fromDay), .text(toDay)]
        ) { rows.append(Self.decodeSample($0)) }
        return rows
    }

    func latestRow(type: String) throws -> HealthSampleRow? {
        var row: HealthSampleRow?
        try connection.query(
            "SELECT \(Self.sampleColumns) FROM health_samples WHERE type_id=? AND deleted=0 ORDER BY end_ms DESC, id DESC LIMIT 1",
            [.text(type)]
        ) { row = Self.decodeSample($0) }
        return row
    }

    /// Keyset page ordered by `(end_ms DESC, id DESC)`; pass the last row's key to continue.
    func samplesPage(type: String, before key: (endMs: Int64, id: String)? = nil, limit: Int) throws -> [HealthSampleRow] {
        var rows: [HealthSampleRow] = []
        if let key {
            try connection.query(
                "SELECT \(Self.sampleColumns) FROM health_samples WHERE type_id=? AND deleted=0 AND (end_ms<? OR (end_ms=? AND id<?)) ORDER BY end_ms DESC, id DESC LIMIT ?",
                [.text(type), .int(key.endMs), .int(key.endMs), .text(key.id), .int(Int64(limit))]
            ) { rows.append(Self.decodeSample($0)) }
        } else {
            try connection.query(
                "SELECT \(Self.sampleColumns) FROM health_samples WHERE type_id=? AND deleted=0 ORDER BY end_ms DESC, id DESC LIMIT ?",
                [.text(type), .int(Int64(limit))]
            ) { rows.append(Self.decodeSample($0)) }
        }
        return rows
    }

    /// Export cursor: all non-deleted, exportable rows in `(end_ms, id)` order after `key`.
    func exportPage(after key: (endMs: Int64, id: String)?, excludingTypePrefixes: [String], excludingTypes: Set<String>, limit: Int) throws -> [HealthSampleRow] {
        var rows: [HealthSampleRow] = []
        var sql = "SELECT \(Self.sampleColumns) FROM health_samples WHERE deleted=0"
        var values: [SQLValue] = []
        if let key {
            sql += " AND (end_ms>? OR (end_ms=? AND id>?))"
            values += [.int(key.endMs), .int(key.endMs), .text(key.id)]
        }
        for prefix in excludingTypePrefixes {
            sql += " AND type_id NOT LIKE ?"
            values.append(.text(prefix + "%"))
        }
        for type in excludingTypes.sorted() {
            sql += " AND type_id<>?"
            values.append(.text(type))
        }
        sql += " ORDER BY end_ms, id LIMIT ?"
        values.append(.int(Int64(limit)))
        try connection.query(sql, values) { rows.append(Self.decodeSample($0)) }
        return rows
    }

    func sampleCount(type: String? = nil, includeDeleted: Bool = false) throws -> Int {
        var sql = "SELECT COUNT(*) FROM health_samples"
        var clauses: [String] = []
        var values: [SQLValue] = []
        if let type {
            clauses.append("type_id=?")
            values.append(.text(type))
        }
        if !includeDeleted { clauses.append("deleted=0") }
        if !clauses.isEmpty { sql += " WHERE " + clauses.joined(separator: " AND ") }
        return Int(try connection.scalarInt64(sql, values) ?? 0)
    }

    func distinctTypeIDs() throws -> [String] {
        var ids: [String] = []
        try connection.query("SELECT DISTINCT type_id FROM health_samples WHERE deleted=0 ORDER BY type_id") {
            if let id = $0.text(0) { ids.append(id) }
        }
        return ids
    }

    func distinctDays(type: String) throws -> [String] {
        var days: [String] = []
        try connection.query("SELECT DISTINCT local_day FROM health_samples WHERE type_id=? AND deleted=0 ORDER BY local_day", [.text(type)]) {
            if let day = $0.text(0) { days.append(day) }
        }
        return days
    }

    /// Per-type counts, range and latest row (hub rows, Coach type list).
    func typeSummaries() throws -> [HealthTypeSummary] {
        var summaries: [HealthTypeSummary] = []
        try connection.query(
            "SELECT type_id, COUNT(*), MIN(start_ms), MAX(end_ms) FROM health_samples WHERE deleted=0 GROUP BY type_id ORDER BY type_id"
        ) { s in
            summaries.append(HealthTypeSummary(
                typeID: s.text(0) ?? "",
                count: s.int(1) ?? 0,
                firstStartMs: s.int64(2),
                lastEndMs: s.int64(3),
                latest: nil
            ))
        }
        for index in summaries.indices {
            summaries[index].latest = try latestRow(type: summaries[index].typeID)
        }
        return summaries
    }

    nonisolated struct SourceUsage: Sendable, Hashable, Identifiable {
        var source: HealthSourceRow
        var rowCount: Int
        var lastMs: Int64?
        var id: String { source.id }
    }

    /// Sources contributing rows to `type` (or to everything when nil), most recent first.
    func sourceUsage(type: String?) throws -> [SourceUsage] {
        var usages: [SourceUsage] = []
        var sql = """
        SELECT s.source_id, COUNT(*), MAX(s.end_ms), src.name, src.device_model, src.device_type, src.last_seen_ms
        FROM health_samples s LEFT JOIN health_sources src ON src.id = s.source_id
        WHERE s.deleted=0
        """
        var values: [SQLValue] = []
        if let type {
            sql += " AND s.type_id=?"
            values.append(.text(type))
        }
        sql += " GROUP BY s.source_id ORDER BY MAX(s.end_ms) DESC"
        try connection.query(sql, values) { s in
            let id = s.text(0) ?? ""
            usages.append(SourceUsage(
                source: HealthSourceRow(id: id, name: s.text(3) ?? id, deviceModel: s.text(4), deviceType: s.int(5), lastSeenMs: s.int64(6)),
                rowCount: s.int(1) ?? 0,
                lastMs: s.int64(2)
            ))
        }
        return usages
    }

    func allSources() throws -> [HealthSourceRow] {
        var sources: [HealthSourceRow] = []
        try connection.query("SELECT id, name, device_model, device_type, last_seen_ms FROM health_sources ORDER BY id") { s in
            sources.append(HealthSourceRow(id: s.text(0) ?? "", name: s.text(1) ?? "", deviceModel: s.text(2), deviceType: s.int(3), lastSeenMs: s.int64(4)))
        }
        return sources
    }
}
