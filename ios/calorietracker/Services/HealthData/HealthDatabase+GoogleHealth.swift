import Foundation

/// `google_health_sync_state` row (schema v3, docs/google-health.md §3).
nonisolated struct GoogleHealthSyncStateRow: Sendable, Hashable {
    var ghType: String
    /// Newest point end seen; the next sync starts at `cursorMs - overlap_days`.
    var cursorMs: Int64?
    /// Non-nil only while a paged fetch is interrupted mid-way.
    var pageToken: String?
    var lastSyncMs: Int64?
    /// Start of the first (90-day) fetch, kept so a resumed first fetch reuses the same filter.
    var backfillFloorMs: Int64?
    var status: String = "idle"
    var lastError: String?
    var lastErrorMs: Int64?

    init(ghType: String) {
        self.ghType = ghType
    }

    var isError: Bool { status.hasPrefix("error") }
    var isScopeMissing: Bool { status == "error:scope" }
    var isUnsupported: Bool { status == "unsupported" }
}

nonisolated enum GoogleHealthMirrorStatus: String, Sendable {
    case pending
    case mirrored
    case skippedDup = "skipped_dup"
    case unsupported
    case disabled
    case error
}

/// A pending write-back row joined with its sample.
nonisolated struct GoogleHealthMirrorCandidate: Sendable, Hashable {
    var row: HealthSampleRow
    var platformID: String?
    var attempts: Int
}

/// One Google Health page: rows + sources + their initial mirror status + the type's state,
/// committed in one transaction.
nonisolated struct GoogleHealthCommitPage: Sendable {
    var rows: [HealthSampleRow] = []
    var sources: [HealthSourceRow] = []
    /// Initial `google_health_mirror` status per sample id.
    var mirrorStatus: [String: GoogleHealthMirrorStatus] = [:]
    var syncState: GoogleHealthSyncStateRow?
}

extension HealthDatabase {
    private nonisolated static let googleStateColumns =
        "gh_type, cursor_ms, page_token, last_sync_ms, backfill_floor_ms, status, last_error, last_error_ms"

    /// Origin-3 upsert: a re-fetched point overwrites only when a value column differs
    /// (the API has no lastModified, so `updated_ms` is the sync clock and cannot decide).
    private nonisolated static let updateGoogleSampleSQL = """
    UPDATE health_samples SET type_id=?, start_ms=?, end_ms=?, start_offset_s=?, end_offset_s=?, local_day=?, value=?, value2=?, value3=?,
      value_text=?, unit=?, category_value=?, title=?, extra_json=?, count=?, source_id=?, device=?, device_type=?, recording_method=?,
      client_record_id=?, updated_ms=?
    WHERE id=? AND origin=3 AND deleted=0 AND NOT (type_id IS ? AND start_ms IS ? AND end_ms IS ? AND start_offset_s IS ? AND end_offset_s IS ?
      AND local_day IS ? AND value IS ? AND value2 IS ? AND value3 IS ? AND value_text IS ? AND unit IS ? AND category_value IS ? AND title IS ?
      AND extra_json IS ? AND source_id IS ? AND device IS ? AND recording_method IS ?)
    """

    nonisolated static func decodeGoogleState(_ s: HealthDBStatement) -> GoogleHealthSyncStateRow {
        var row = GoogleHealthSyncStateRow(ghType: s.text(0) ?? "")
        row.cursorMs = s.int64(1)
        row.pageToken = s.text(2)
        row.lastSyncMs = s.int64(3)
        row.backfillFloorMs = s.int64(4)
        row.status = s.text(5) ?? "idle"
        row.lastError = s.text(6)
        row.lastErrorMs = s.int64(7)
        return row
    }

    // MARK: - google_health_sync_state

    func googleSyncState(ghType: String) throws -> GoogleHealthSyncStateRow? {
        var row: GoogleHealthSyncStateRow?
        try connection.query("SELECT \(Self.googleStateColumns) FROM google_health_sync_state WHERE gh_type=?", [.text(ghType)]) {
            row = Self.decodeGoogleState($0)
        }
        return row
    }

    func allGoogleSyncStates() throws -> [GoogleHealthSyncStateRow] {
        var rows: [GoogleHealthSyncStateRow] = []
        try connection.query("SELECT \(Self.googleStateColumns) FROM google_health_sync_state ORDER BY gh_type") {
            rows.append(Self.decodeGoogleState($0))
        }
        return rows
    }

    func setGoogleSyncState(_ row: GoogleHealthSyncStateRow) throws {
        try connection.inTransaction {
            try setGoogleSyncStateInTransaction(row)
        }
    }

    func setGoogleSyncStateInTransaction(_ r: GoogleHealthSyncStateRow) throws {
        try connection.run(
            "INSERT OR REPLACE INTO google_health_sync_state (\(Self.googleStateColumns)) VALUES (?,?,?,?,?,?,?,?)",
            [
                .text(r.ghType), .optionalInt64(r.cursorMs), .optionalText(r.pageToken), .optionalInt64(r.lastSyncMs),
                .optionalInt64(r.backfillFloorMs), .text(r.status), .optionalText(r.lastError), .optionalInt64(r.lastErrorMs),
            ]
        )
    }

    // MARK: - Page commit

    /// Upserts origin-3 rows; returns the ids that were inserted or changed.
    func upsertGoogleSamplesInTransaction(_ rows: [HealthSampleRow]) throws -> (result: UpsertResult, inserted: Set<String>, updated: Set<String>) {
        var result = UpsertResult()
        var inserted = Set<String>()
        var updated = Set<String>()
        guard !rows.isEmpty else { return (result, inserted, updated) }
        let update = try connection.prepare(Self.updateGoogleSampleSQL)
        let insert = try connection.prepare(
            "INSERT OR IGNORE INTO health_samples (\(Self.sampleColumns)) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)"
        )
        for r in rows {
            let compared: [SQLValue] = [
                .text(r.typeID), .int(r.startMs), .int(r.endMs), .optionalInt(r.startOffsetS), .optionalInt(r.endOffsetS),
                .text(r.localDay), .optionalReal(r.value), .optionalReal(r.value2), .optionalReal(r.value3), .optionalText(r.valueText),
                .text(r.unit), .optionalInt(r.categoryValue), .optionalText(r.title), .optionalText(r.extraJSON),
                .text(r.sourceID), .optionalText(r.device), .optionalInt(r.recordingMethod),
            ]
            update.reset()
            try update.bind([
                .text(r.typeID), .int(r.startMs), .int(r.endMs), .optionalInt(r.startOffsetS), .optionalInt(r.endOffsetS),
                .text(r.localDay), .optionalReal(r.value), .optionalReal(r.value2), .optionalReal(r.value3), .optionalText(r.valueText),
                .text(r.unit), .optionalInt(r.categoryValue), .optionalText(r.title), .optionalText(r.extraJSON), .int(Int64(r.count)),
                .text(r.sourceID), .optionalText(r.device), .optionalInt(r.deviceType), .optionalInt(r.recordingMethod),
                .optionalText(r.clientRecordID), .int(r.updatedMs), .text(r.id),
            ] + compared)
            _ = try update.step()
            if connection.changes > 0 {
                result.updated += 1
                updated.insert(r.id)
                continue
            }
            insert.reset()
            try insert.bind([
                .text(r.id), .text(r.typeID), .int(r.startMs), .int(r.endMs), .optionalInt(r.startOffsetS), .optionalInt(r.endOffsetS),
                .text(r.localDay), .optionalReal(r.value), .optionalReal(r.value2), .optionalReal(r.value3), .optionalText(r.valueText),
                .text(r.unit), .optionalInt(r.categoryValue), .optionalText(r.title), .optionalText(r.extraJSON), .int(Int64(r.count)),
                .text(r.sourceID), .optionalText(r.device), .optionalInt(r.deviceType), .optionalInt(r.recordingMethod),
                .optionalText(r.clientRecordID), .int(Int64(HealthRowOrigin.googleHealth.rawValue)), .int(0), .int(r.updatedMs),
            ])
            _ = try insert.step()
            if connection.changes > 0 {
                result.inserted += 1
                inserted.insert(r.id)
            } else {
                result.unchanged += 1
            }
        }
        return (result, inserted, updated)
    }

    /// Rows + sources + mirror rows + the type's state in one transaction. A changed row that
    /// was already written back goes back to `pending` (the writer replaces the platform copy).
    @discardableResult
    func commitGooglePage(_ page: GoogleHealthCommitPage) throws -> (result: UpsertResult, changedIDs: Set<String>) {
        try connection.inTransaction {
            let upsert = try upsertGoogleSamplesInTransaction(page.rows)
            try upsertSourcesInTransaction(page.sources)
            let insertMirror = try connection.prepare(
                "INSERT OR IGNORE INTO google_health_mirror (sample_id, mirror_status) VALUES (?, ?)"
            )
            let rewrite = try connection.prepare(
                "UPDATE google_health_mirror SET mirror_status='pending', attempts=0, last_error=NULL WHERE sample_id=? AND mirror_status IN ('mirrored', 'error')"
            )
            for id in upsert.inserted.union(upsert.updated).sorted() {
                guard let status = page.mirrorStatus[id] else { continue }
                insertMirror.reset()
                try insertMirror.bind([.text(id), .text(status.rawValue)])
                _ = try insertMirror.step()
                if status == .pending, upsert.updated.contains(id) {
                    rewrite.reset()
                    try rewrite.bind([.text(id)])
                    _ = try rewrite.step()
                }
            }
            if let state = page.syncState {
                try setGoogleSyncStateInTransaction(state)
            }
            return (upsert.result, upsert.inserted.union(upsert.updated))
        }
    }

    // MARK: - Echo guard

    /// Ids of `rows` that an origin-0 (platform) row of the same type already covers: start and
    /// end within `windowMs` and value within `epsilonRatio` (`echo_guard` duplicate rule).
    func platformDuplicateIDs(_ rows: [HealthSampleRow], windowMs: Int64, epsilonRatio: Double) throws -> Set<String> {
        var duplicates = Set<String>()
        guard !rows.isEmpty else { return duplicates }
        let statement = try connection.prepare("""
        SELECT value FROM health_samples
        WHERE type_id=? AND origin=0 AND deleted=0 AND start_ms BETWEEN ? AND ? AND end_ms BETWEEN ? AND ?
        """)
        for row in rows {
            statement.reset()
            try statement.bind([
                .text(row.typeID), .int(row.startMs - windowMs), .int(row.startMs + windowMs),
                .int(row.endMs - windowMs), .int(row.endMs + windowMs),
            ])
            while try statement.step() {
                let other = statement.double(0)
                if Self.valuesMatch(row.value, other, epsilonRatio: epsilonRatio) {
                    duplicates.insert(row.id)
                    break
                }
            }
        }
        return duplicates
    }

    nonisolated static func valuesMatch(_ a: Double?, _ b: Double?, epsilonRatio: Double) -> Bool {
        switch (a, b) {
        case (nil, nil): return true
        case let (a?, b?):
            let scale = max(abs(a), abs(b))
            return scale == 0 || abs(a - b) <= epsilonRatio * scale
        default: return false
        }
    }

    // MARK: - google_health_mirror

    func pendingMirrorCandidates(limit: Int, excludingTypeIDs: Set<String> = []) throws -> [GoogleHealthMirrorCandidate] {
        var candidates: [GoogleHealthMirrorCandidate] = []
        let columns = Self.sampleColumns.split(separator: ",").map { "s." + $0.trimmingCharacters(in: .whitespaces) }.joined(separator: ", ")
        var sql = """
        SELECT \(columns), m.platform_id, m.attempts FROM google_health_mirror m JOIN health_samples s ON s.id = m.sample_id
        WHERE m.mirror_status='pending' AND s.deleted=0
        """
        var values: [SQLValue] = []
        for typeID in excludingTypeIDs.sorted() {
            sql += " AND s.type_id<>?"
            values.append(.text(typeID))
        }
        sql += " ORDER BY s.type_id, s.start_ms, s.id LIMIT ?"
        values.append(.int(Int64(limit)))
        try connection.query(sql, values) { s in
            candidates.append(GoogleHealthMirrorCandidate(row: Self.decodeSample(s), platformID: s.text(24), attempts: s.int(25) ?? 0))
        }
        return candidates
    }

    /// Records one write-back outcome per sample id (`platformIDs` only for `mirrored`).
    func markMirror(_ ids: [String], status: GoogleHealthMirrorStatus, platformIDs: [String: String] = [:], error: String? = nil, nowMs: Int64) throws {
        guard !ids.isEmpty else { return }
        try connection.inTransaction {
            let statement = try connection.prepare("""
            UPDATE google_health_mirror SET mirror_status=?, platform_id=COALESCE(?, platform_id),
              mirrored_ms=CASE WHEN ?='mirrored' THEN ? ELSE mirrored_ms END,
              attempts=attempts + CASE WHEN ? IN ('mirrored', 'error') THEN 1 ELSE 0 END, last_error=?
            WHERE sample_id=?
            """)
            for id in ids {
                statement.reset()
                try statement.bind([
                    .text(status.rawValue), .optionalText(platformIDs[id]), .text(status.rawValue), .int(nowMs),
                    .text(status.rawValue), .optionalText(error), .text(id),
                ])
                _ = try statement.step()
            }
        }
    }

    /// Write-back toggle: `pending` ⇄ `disabled`.
    func setMirrorWriteBack(enabled: Bool) throws {
        try connection.inTransaction {
            if enabled {
                try connection.exec("UPDATE google_health_mirror SET mirror_status='pending' WHERE mirror_status='disabled'")
            } else {
                try connection.exec("UPDATE google_health_mirror SET mirror_status='disabled' WHERE mirror_status='pending'")
            }
        }
    }

    /// Rows that failed fewer than `maxAttempts` times are queued again.
    func retryMirrorErrors(maxAttempts: Int) throws {
        try connection.inTransaction {
            try connection.run("UPDATE google_health_mirror SET mirror_status='pending' WHERE mirror_status='error' AND attempts < ?", [.int(Int64(maxAttempts))])
        }
    }

    func mirrorStatusCounts() throws -> [String: Int] {
        var counts: [String: Int] = [:]
        try connection.query("SELECT mirror_status, COUNT(*) FROM google_health_mirror GROUP BY mirror_status") { s in
            counts[s.text(0) ?? ""] = s.int(1) ?? 0
        }
        return counts
    }

    func googleSampleCount() throws -> Int {
        Int(try connection.scalarInt64("SELECT COUNT(*) FROM health_samples WHERE origin=3 AND deleted=0") ?? 0)
    }

    // MARK: - Disconnect

    /// Clears every Google cursor; with `deleteRows` also removes the origin-3 rows (their
    /// mirror rows cascade). Returns the `(type, day)` pairs whose rollups must be rebuilt.
    @discardableResult
    func clearGoogleHealth(deleteRows: Bool) throws -> [String: Set<String>] {
        try connection.inTransaction {
            try connection.exec("DELETE FROM google_health_sync_state")
            guard deleteRows else { return [:] }
            var touched: [String: Set<String>] = [:]
            try connection.query("SELECT DISTINCT type_id, local_day FROM health_samples WHERE origin=3") { s in
                touched[s.text(0) ?? "", default: []].insert(s.text(1) ?? "")
            }
            try connection.exec("DELETE FROM health_samples WHERE origin=3")
            return touched
        }
    }
}
