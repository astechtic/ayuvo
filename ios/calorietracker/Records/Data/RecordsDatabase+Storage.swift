import Foundation

/// One record's on-disk footprint for the §37 storage screen.
nonisolated struct RecordStorageRow: Sendable, Hashable {
    var id: String
    var fileType: RecordFileType
    var filePath: String?
    var thumbnailPath: String?
}

// Phase 5 storage management (docs/health-records.md §37).
extension RecordsDatabase {
    func storageRows() throws -> [RecordStorageRow] {
        var rows: [RecordStorageRow] = []
        try connection.query("SELECT id, file_type, file_path, thumbnail_path FROM records ORDER BY seq") { s in
            rows.append(RecordStorageRow(
                id: s.text(0) ?? "",
                fileType: RecordFileType(rawValue: s.text(1) ?? "") ?? .other,
                filePath: s.text(2),
                thumbnailPath: s.text(3)
            ))
        }
        return rows
    }

    func pageRowCount() throws -> Int {
        Int(try connection.scalarInt64("SELECT COUNT(*) FROM record_pages") ?? 0)
    }

    /// Row counts of every table the archive carries (§35.1 fixture assertions, storage screen).
    func archiveRowCounts() throws -> [String: Int] {
        var counts: [String: Int] = [:]
        for table in ["record_pages", "record_fields", "observations", "record_highlights", "record_links",
                      "entities", "record_entities", "tags", "record_tags", "analyte_user_aliases"] {
            counts[table] = Int(try connection.scalarInt64("SELECT COUNT(*) FROM \(table)") ?? 0)
        }
        return counts
    }

    func allRecordIDs() throws -> [String] {
        var ids: [String] = []
        try connection.query("SELECT id FROM records ORDER BY seq") { if let id = $0.text(0) { ids.append(id) } }
        return ids
    }

    /// §37 "Rebuild search index": every FTS row from the current rows.
    func rebuildAllFTSRows() throws {
        try connection.inTransaction {
            var ids: [String] = []
            try connection.query("SELECT id FROM records ORDER BY seq") { if let id = $0.text(0) { ids.append(id) } }
            try connection.exec("DELETE FROM records_fts")
            for id in ids { try reindexInTransaction(recordID: id) }
            try connection.run("INSERT OR REPLACE INTO records_meta (key, value) VALUES ('fts_row_version', ?)", [.text(Self.ftsRowVersion)])
        }
    }

    /// §37 "Find duplicates": re-runs §15 near-duplicate matching over the stored signatures and
    /// records any new candidate as pending. Returns how many were added.
    @discardableResult
    func rescanNearDuplicates(nowMs: Int64 = RecordDates.nowMs()) throws -> Int {
        let rows = try signatureRows(excluding: "")
        guard rows.count > 1 else { return 0 }
        var checksums: [String: String] = [:]
        try connection.query("SELECT id, checksum_sha256 FROM records") { s in
            if let id = s.text(0), let checksum = s.text(1) { checksums[id] = checksum }
        }
        var known = Set<String>()
        try connection.query("SELECT record_id, existing_id FROM duplicate_candidates") { s in
            guard let a = s.text(0), let b = s.text(1) else { return }
            known.insert(a + "|" + b)
            known.insert(b + "|" + a)
        }
        var inserted = 0
        var touched = Set<String>()
        for i in rows.indices {
            let a = rows[i]
            guard a.parentID == nil else { continue }
            for j in (i + 1)..<rows.count {
                let b = rows[j]
                guard b.parentID == nil, !known.contains(a.id + "|" + b.id) else { continue }
                if let ca = checksums[a.id], let cb = checksums[b.id], !ca.isEmpty, ca == cb { continue }
                guard let match = RecordNearDuplicate.match(
                    phashA: a.phash, signatureA: a.textSignature, phashB: b.phash, signatureB: b.textSignature
                ) else { continue }
                switch match {
                case .phash(let score):
                    try insertDuplicateCandidate(recordID: a.id, existingID: b.id, reason: .phash, score: score, nowMs: nowMs)
                case .content(let score):
                    try insertDuplicateCandidate(recordID: a.id, existingID: b.id, reason: .content, score: score, nowMs: nowMs)
                }
                known.insert(a.id + "|" + b.id)
                known.insert(b.id + "|" + a.id)
                touched.insert(a.id)
                inserted += 1
            }
        }
        for id in touched.sorted() {
            try setReviewStatus(id: id, .needsReview, nowMs: nowMs)
        }
        return inserted
    }
}
