import Foundation
import SQLite3

/// A ranked universal-search hit (§17).
nonisolated struct RecordSearchHit: Hashable, Sendable, Identifiable {
    var record: HealthRecord
    /// `snippet()` with `[` `]` around matches, or nil.
    var snippet: String?
    var score: Double
    var id: String { record.id }
}

/// Signature row used by near-duplicate detection.
nonisolated struct RecordSignatureRow: Hashable, Sendable {
    var id: String
    var parentID: String?
    var phash: String?
    var textSignature: String?
    var textIsEmpty: Bool
}

// Phase 2 storage: fields, highlights, processing jobs, duplicates, split proposals,
// `applyExtraction` (§8.1), advanced filters and ranked search.
extension RecordsDatabase {
    nonisolated static let fieldColumns = "id, record_id, field_key, value_text, value_json, method, confidence, state, source_page, source_bbox, evidence, created_ms, updated_ms"

    nonisolated static func decodeField(_ s: HealthDBStatement) -> RecordField? {
        guard let key = RecordFieldKey(rawValue: s.text(2) ?? "") else { return nil }
        return RecordField(
            id: s.text(0) ?? "",
            recordID: s.text(1) ?? "",
            key: key,
            valueText: s.text(3) ?? "",
            valueJSON: s.text(4),
            method: RecordFieldMethod(rawValue: s.text(5) ?? "") ?? .rules,
            confidence: s.double(6) ?? 0,
            state: RecordFieldState(rawValue: s.text(7) ?? "") ?? .suggested,
            sourcePage: s.int(8),
            sourceBBox: s.text(9),
            evidence: s.text(10),
            createdMs: s.int64(11) ?? 0,
            updatedMs: s.int64(12) ?? 0
        )
    }

    // MARK: - Reads

    func fields(recordID: String) throws -> [RecordField] {
        var rows: [RecordField] = []
        try connection.query(
            "SELECT \(Self.fieldColumns) FROM record_fields WHERE record_id=? ORDER BY rowid",
            [.text(recordID)]
        ) { if let field = Self.decodeField($0) { rows.append(field) } }
        return rows
    }

    func field(id: String) throws -> RecordField? {
        var result: RecordField?
        try connection.query("SELECT \(Self.fieldColumns) FROM record_fields WHERE id=?", [.text(id)]) { result = Self.decodeField($0) }
        return result
    }

    func highlights(recordID: String) throws -> [RecordHighlight] {
        var rows: [RecordHighlight] = []
        try connection.query(
            "SELECT id, record_id, section, text, method, provider, field_id, source_page, confidence, dismissed, position, created_ms FROM record_highlights WHERE record_id=? ORDER BY section, position",
            [.text(recordID)]
        ) { s in
            guard let section = RecordHighlightSection(rawValue: s.text(2) ?? "") else { return }
            rows.append(RecordHighlight(
                id: s.text(0) ?? "",
                recordID: s.text(1) ?? recordID,
                section: section,
                text: s.text(3) ?? "",
                method: RecordFieldMethod(rawValue: s.text(4) ?? "") ?? .rules,
                provider: s.text(5),
                fieldID: s.text(6),
                sourcePage: s.int(7),
                confidence: s.double(8) ?? 0,
                dismissed: (s.int(9) ?? 0) != 0,
                position: s.int(10) ?? 0,
                createdMs: s.int64(11) ?? 0
            ))
        }
        return rows
    }

    /// Recent "Important" highlights across non-archived records (home section).
    func importantHighlights(limit: Int) throws -> [(RecordHighlight, HealthRecord)] {
        var rows: [(RecordHighlight, HealthRecord)] = []
        let qualified = Self.qualifiedColumns
        try connection.query(
            """
            SELECT h.id, h.record_id, h.section, h.text, h.method, h.provider, h.field_id, h.source_page, h.confidence, h.dismissed, h.position, h.created_ms, \(qualified)
            FROM record_highlights h JOIN records ON records.id = h.record_id
            WHERE h.section='important' AND h.dismissed=0 AND records.archived=0
            ORDER BY records.sort_date DESC, records.created_ms DESC, h.position LIMIT ?
            """,
            [.int(Int64(limit))]
        ) { s in
            let highlight = RecordHighlight(
                id: s.text(0) ?? "", recordID: s.text(1) ?? "", section: .important, text: s.text(3) ?? "",
                method: RecordFieldMethod(rawValue: s.text(4) ?? "") ?? .rules, provider: s.text(5), fieldID: s.text(6),
                sourcePage: s.int(7), confidence: s.double(8) ?? 0, dismissed: false, position: s.int(10) ?? 0, createdMs: s.int64(11) ?? 0
            )
            rows.append((highlight, Self.decodeRecord(s, offset: 12)))
        }
        return rows
    }

    func job(recordID: String) throws -> RecordProcessingJob? {
        var result: RecordProcessingJob?
        try connection.query(
            "SELECT record_id, stage, attempts, next_attempt_ms, last_error, requested_mode, awaiting_consent, updated_ms FROM processing_jobs WHERE record_id=?",
            [.text(recordID)]
        ) { result = Self.decodeJob($0) }
        return result
    }

    nonisolated static func decodeJob(_ s: HealthDBStatement) -> RecordProcessingJob {
        RecordProcessingJob(
            recordID: s.text(0) ?? "",
            stage: RecordProcessingStage(rawValue: s.text(1) ?? "") ?? .text,
            attempts: s.int(2) ?? 0,
            nextAttemptMs: s.int64(3) ?? 0,
            lastError: s.text(4),
            requestedMode: s.text(5),
            awaitingConsent: (s.int(6) ?? 0) != 0,
            updatedMs: s.int64(7) ?? 0
        )
    }

    func duplicateCandidates(recordID: String, pendingOnly: Bool) throws -> [RecordDuplicateCandidate] {
        var rows: [RecordDuplicateCandidate] = []
        try connection.query(
            "SELECT record_id, existing_id, reason, score, resolution, created_ms FROM duplicate_candidates WHERE (record_id=? OR existing_id=?)\(pendingOnly ? " AND resolution='pending'" : "") ORDER BY score DESC",
            [.text(recordID), .text(recordID)]
        ) { s in
            rows.append(RecordDuplicateCandidate(
                recordID: s.text(0) ?? "",
                existingID: s.text(1) ?? "",
                reason: RecordDuplicateReason(rawValue: s.text(2) ?? "") ?? .content,
                score: s.double(3) ?? 0,
                resolution: RecordDuplicateResolution(rawValue: s.text(4) ?? "") ?? .pending,
                createdMs: s.int64(5) ?? 0
            ))
        }
        return rows
    }

    func pendingDuplicateCandidates(limit: Int = 20) throws -> [RecordDuplicateCandidate] {
        var rows: [RecordDuplicateCandidate] = []
        try connection.query(
            "SELECT record_id, existing_id, reason, score, resolution, created_ms FROM duplicate_candidates WHERE resolution='pending' AND reason<>'checksum' ORDER BY created_ms LIMIT ?",
            [.int(Int64(limit))]
        ) { s in
            rows.append(RecordDuplicateCandidate(
                recordID: s.text(0) ?? "", existingID: s.text(1) ?? "",
                reason: RecordDuplicateReason(rawValue: s.text(2) ?? "") ?? .content, score: s.double(3) ?? 0,
                resolution: .pending, createdMs: s.int64(5) ?? 0
            ))
        }
        return rows
    }

    func splitProposal(recordID: String) throws -> RecordSplitProposal? {
        var result: RecordSplitProposal?
        try connection.query(
            "SELECT record_id, segments_json, status, created_ms, updated_ms FROM split_proposals WHERE record_id=?",
            [.text(recordID)]
        ) { s in
            result = RecordSplitProposal(
                recordID: s.text(0) ?? recordID,
                segments: RecordSplitProposal.decodeSegments(s.text(1) ?? "[]"),
                status: RecordSplitStatus(rawValue: s.text(2) ?? "") ?? .pending,
                createdMs: s.int64(3) ?? 0,
                updatedMs: s.int64(4) ?? 0
            )
        }
        return result
    }

    func children(parentID: String) throws -> [HealthRecord] {
        var rows: [HealthRecord] = []
        try connection.query("SELECT \(Self.columns) FROM records WHERE parent_id=? ORDER BY page_start, created_ms", [.text(parentID)]) {
            rows.append(Self.decodeRecord($0))
        }
        return rows
    }

    /// Non-archived records waiting for review (Needs Review section).
    func needsReview(limit: Int) throws -> [HealthRecord] {
        var rows: [HealthRecord] = []
        try connection.query(
            "SELECT \(Self.columns) FROM records WHERE review_status='needs_review' AND archived=0 ORDER BY \(Self.timelineOrderSQL) LIMIT ?",
            [.int(Int64(limit))]
        ) { rows.append(Self.decodeRecord($0)) }
        return rows
    }

    func processingSummary() throws -> RecordsProcessingSummary {
        var summary = RecordsProcessingSummary()
        summary.activeRecords = Int(try connection.scalarInt64("SELECT COUNT(*) FROM processing_jobs WHERE stage<>'done' AND awaiting_consent=0") ?? 0)
        summary.awaitingConsent = Int(try connection.scalarInt64("SELECT COUNT(*) FROM processing_jobs WHERE awaiting_consent=1") ?? 0)
        summary.needsReview = Int(try connection.scalarInt64("SELECT COUNT(*) FROM records WHERE review_status='needs_review' AND archived=0") ?? 0)
        return summary
    }

    func awaitingConsentRecordIDs() throws -> [String] {
        var ids: [String] = []
        try connection.query("SELECT record_id FROM processing_jobs WHERE awaiting_consent=1 ORDER BY updated_ms") {
            if let id = $0.text(0) { ids.append(id) }
        }
        return ids
    }

    /// Distinct doctor / facility names for the Filters sheet.
    func distinctFieldValues(_ key: RecordFieldKey, limit: Int = 200) throws -> [String] {
        var seen = Set<String>()
        var values: [String] = []
        try connection.query(
            "SELECT value_text FROM record_fields WHERE field_key=? AND state<>'rejected' ORDER BY confidence DESC LIMIT 2000",
            [.text(key.rawValue)]
        ) { s in
            guard let value = s.text(0) else { return }
            let folded = RecordsFold.normalizedValue(value)
            guard !folded.isEmpty, seen.insert(folded).inserted, values.count < limit else { return }
            values.append(value)
        }
        return values.sorted { $0.localizedCaseInsensitiveCompare($1) == .orderedAscending }
    }

    // MARK: - Filters & search

    nonisolated static var qualifiedColumns: String {
        columns.split(separator: ",").map { "records." + $0.trimmingCharacters(in: .whitespaces) }.joined(separator: ", ")
    }

    nonisolated static func decodeRecord(_ s: HealthDBStatement, offset: Int32) -> HealthRecord {
        guard offset > 0 else { return decodeRecord(s) }
        return decodeRecord(OffsetStatement(base: s, offset: offset))
    }

    /// Appends Phase 2 filter clauses. Returns false when a filter can never match.
    func appendAdvancedFilters(_ query: RecordQuery, clauses: inout [String], values: inout [SQLValue]) throws -> Bool {
        if query.needsReviewOnly { clauses.append("review_status='needs_review'") }
        if let from = query.dateFrom {
            clauses.append("sort_date >= ?")
            values.append(.text(from))
        }
        if let to = query.dateTo {
            clauses.append("sort_date <= ?")
            values.append(.text(to))
        }
        if query.aiProcessedOnly { clauses.append("ai_mode_used <> 'none'") }
        if query.userConfirmedOnly {
            clauses.append("(review_status='reviewed' OR EXISTS (SELECT 1 FROM record_fields uf WHERE uf.record_id=records.id AND uf.state IN ('confirmed','user')))")
        }
        if !query.flags.isEmpty {
            let stored = Set(query.flags.flatMap(\.storedFlags)).map(\.rawValue).sorted()
            clauses.append("EXISTS (SELECT 1 FROM record_fields ff WHERE ff.record_id=records.id AND ff.field_key='test_result' AND ff.state<>'rejected' AND json_extract(ff.value_json, '$.flag') IN (\(Array(repeating: "?", count: stored.count).joined(separator: ", "))))")
            values.append(contentsOf: stored.map { .text($0) })
        }
        if !query.tags.isEmpty {
            let names = query.tags.sorted()
            clauses.append("EXISTS (SELECT 1 FROM record_tags rt JOIN tags t ON t.id=rt.tag_id WHERE rt.record_id=records.id AND t.name COLLATE NOCASE IN (\(Array(repeating: "?", count: names.count).joined(separator: ", "))))")
            values.append(contentsOf: names.map { .text($0) })
        }
        for (key, name) in [(RecordFieldKey.doctorName, query.doctor), (.facility, query.facility)] {
            guard let name, !RecordsFold.normalizedValue(name).isEmpty else { continue }
            let ids = try recordIDs(key: key, prefixMatching: name)
            guard !ids.isEmpty else { return false }
            clauses.append("records.id IN (\(Array(repeating: "?", count: ids.count).joined(separator: ", ")))")
            values.append(contentsOf: ids.sorted().map { .text($0) })
        }
        for entityID in [query.doctorEntityID, query.facilityEntityID].compactMap({ $0 }) {
            clauses.append("records.id IN (SELECT record_id FROM record_entities WHERE entity_id=?)")
            values.append(.text(entityID))
        }
        let conditions = query.filteringAnalyteConditions
        if !conditions.isEmpty {
            let ids = try recordIDs(matching: conditions)
            guard !ids.isEmpty else { return false }
            clauses.append("records.id IN (\(Array(repeating: "?", count: ids.count).joined(separator: ", ")))")
            values.append(contentsOf: ids.sorted().map { .text($0) })
        }
        return true
    }

    /// Records whose doctor / facility entity (or, for records without entities yet, field of `key`)
    /// contains every folded query word as a word prefix, in order.
    func recordIDs(key: RecordFieldKey, prefixMatching name: String) throws -> Set<String> {
        let wanted = RecordsFold.normalizedValue(name).split(separator: " ").map(String.init)
        var ids = Set<String>()
        let kind: RecordEntityKind = key == .facility ? .facility : .doctor
        try connection.query(
            "SELECT re.record_id, e.normalized_name FROM record_entities re JOIN entities e ON e.id=re.entity_id WHERE e.kind=?",
            [.text(kind.rawValue)]
        ) { s in
            guard let id = s.text(0), let normalized = s.text(1) else { return }
            if Self.wordsPrefixMatch(words: normalized.split(separator: " ").map(String.init), wanted: wanted) { ids.insert(id) }
        }
        try connection.query(
            "SELECT record_id, value_text FROM record_fields WHERE field_key=? AND state<>'rejected'",
            [.text(key.rawValue)]
        ) { s in
            guard let id = s.text(0), let value = s.text(1) else { return }
            let words = RecordsFold.normalizedValue(value).split(separator: " ").map(String.init)
            if Self.wordsPrefixMatch(words: words, wanted: wanted) { ids.insert(id) }
        }
        return ids
    }

    nonisolated static func wordsPrefixMatch(words: [String], wanted: [String]) -> Bool {
        guard !wanted.isEmpty else { return true }
        var index = 0
        for word in words where index < wanted.count {
            if word.hasPrefix(wanted[index]) { index += 1 }
        }
        return index == wanted.count
    }

    /// Ranked FTS search (§17): BM25 from `matchinfo(records_fts,'pcnalx')` with column weights
    /// title 5, people 3, clinical 3, body 1, notes_tags 2, highlights 2 (k1 1.2, b 0.75) plus a
    /// recency boost. Archived rows follow `query.archivedOnly` like the timeline.
    func search(query: RecordQuery, terms: [String], today: String, limit: Int = 200) throws -> [RecordSearchHit] {
        guard !terms.isEmpty else { return [] }
        let match = terms.map { "\($0)*" }.joined(separator: " ")
        var clauses: [String] = ["records_fts MATCH ?", "records.archived = ?"]
        var values: [SQLValue] = [.text(match), .int(query.archivedOnly ? 1 : 0)]
        if query.favoritesOnly { clauses.append("records.favorite = 1") }
        if query.receivedOnly { clauses.append("records.source IN ('share_in', 'open_in')") }
        func appendIn(_ column: String, _ raws: [String]) {
            guard !raws.isEmpty else { return }
            clauses.append("records.\(column) IN (\(Array(repeating: "?", count: raws.count).joined(separator: ", ")))")
            values.append(contentsOf: raws.sorted().map { .text($0) })
        }
        appendIn("record_type", query.recordTypes.map(\.rawValue))
        appendIn("category", query.categories.map(\.rawValue))
        appendIn("file_type", query.fileTypes.map(\.rawValue))
        guard try appendAdvancedFilters(query, clauses: &clauses, values: &values) else { return [] }
        let sql = """
        SELECT \(Self.qualifiedColumns), matchinfo(records_fts, 'pcnalx'), snippet(records_fts, '[', ']', '…', -1, 12)
        FROM records JOIN records_fts ON records_fts.docid = records.seq
        WHERE \(clauses.joined(separator: " AND "))
        """
        let columnCount = Int32(Self.columns.split(separator: ",").count)
        let todayDate = RecordDates.date(fromDay: today, timeZone: TimeZone(identifier: "UTC")!)
        var hits: [RecordSearchHit] = []
        try connection.query(sql, values) { s in
            let record = Self.decodeRecord(s)
            let info = s.blob(columnCount).map(Self.uint32Array) ?? []
            var score = Self.bm25(matchInfo: info, weights: [5, 3, 3, 1, 2, 2])
            if let todayDate, let sortDate = RecordDates.date(fromDay: record.effectiveDate, timeZone: TimeZone(identifier: "UTC")!) {
                let days = max(0, todayDate.timeIntervalSince(sortDate) / 86_400)
                score += 0.15 * max(0, 1 - days / 730)
            }
            hits.append(RecordSearchHit(record: record, snippet: s.text(columnCount + 1), score: score))
        }
        hits.sort {
            if $0.score != $1.score { return $0.score > $1.score }
            return ($0.record.effectiveDate, $0.record.createdMs, $0.record.seq) > ($1.record.effectiveDate, $1.record.createdMs, $1.record.seq)
        }
        return Array(hits.prefix(limit))
    }

    nonisolated static func uint32Array(_ data: Data) -> [UInt32] {
        data.withUnsafeBytes { raw in
            (0..<(raw.count / 4)).map { raw.loadUnaligned(fromByteOffset: $0 * 4, as: UInt32.self) }
        }
    }

    /// Okapi BM25 over FTS4 `pcnalx` matchinfo.
    nonisolated static func bm25(matchInfo info: [UInt32], weights: [Double], k1: Double = 1.2, b: Double = 0.75) -> Double {
        guard info.count >= 3 else { return 0 }
        let phrases = Int(info[0])
        let columns = Int(info[1])
        let rows = Double(info[2])
        guard info.count >= 3 + 2 * columns + 3 * columns * phrases else { return 0 }
        let averages = Array(info[3..<(3 + columns)]).map(Double.init)
        let lengths = Array(info[(3 + columns)..<(3 + 2 * columns)]).map(Double.init)
        let xOffset = 3 + 2 * columns
        var score = 0.0
        for phrase in 0..<phrases {
            for column in 0..<columns {
                let base = xOffset + 3 * (column + phrase * columns)
                let hitsHere = Double(info[base])
                let docsWithHits = Double(info[base + 2])
                guard hitsHere > 0, averages[column] > 0 else { continue }
                var idf = log((rows - docsWithHits + 0.5) / (docsWithHits + 0.5))
                if idf <= 0 { idf = 1e-6 }
                let weight = column < weights.count ? weights[column] : 1
                let denominator = hitsHere + k1 * (1 - b + b * (lengths[column] / averages[column]))
                score += weight * idf * (hitsHere * (k1 + 1)) / denominator
            }
        }
        return score
    }

    // MARK: - Jobs

    /// Creates `processing_jobs` rows at stage `text` for records without one and marks them `queued`.
    @discardableResult
    func enqueueProcessing(ids: [String], nowMs: Int64 = RecordDates.nowMs(), restart: Bool = false) throws -> Int {
        var count = 0
        try connection.inTransaction {
            for id in ids {
                guard try connection.scalarInt64("SELECT 1 FROM records WHERE id=?", [.text(id)]) != nil else { continue }
                if restart {
                    try connection.run("DELETE FROM processing_jobs WHERE record_id=?", [.text(id)])
                } else if try connection.scalarInt64("SELECT 1 FROM processing_jobs WHERE record_id=?", [.text(id)]) != nil {
                    continue
                }
                try connection.run(
                    "INSERT INTO processing_jobs (record_id, stage, attempts, next_attempt_ms, awaiting_consent, updated_ms) VALUES (?, 'text', 0, 0, 0, ?)",
                    [.text(id), .int(nowMs)]
                )
                try connection.run(
                    "UPDATE records SET processing_status='queued', updated_ms=? WHERE id=? AND processing_status<>'failed_partial'",
                    [.int(nowMs), .text(id)]
                )
                count += 1
            }
        }
        return count
    }

    /// One-time Phase 1 backfill (tracked in `records_meta`): every top-level record without a job.
    func backfillProcessingJobsIfNeeded(nowMs: Int64 = RecordDates.nowMs()) throws -> Int {
        guard try metaValue("processing_backfill_v2") == nil else { return 0 }
        var ids: [String] = []
        try connection.query("SELECT id FROM records WHERE parent_id IS NULL AND id NOT IN (SELECT record_id FROM processing_jobs) ORDER BY created_ms") {
            if let id = $0.text(0) { ids.append(id) }
        }
        let count = try enqueueProcessing(ids: ids, nowMs: nowMs)
        try connection.run("INSERT OR REPLACE INTO records_meta (key, value) VALUES ('processing_backfill_v2', '1')")
        return count
    }

    /// Next job to run: not done, not waiting for consent, due. Oldest first.
    func nextRunnableJob(nowMs: Int64) throws -> RecordProcessingJob? {
        var result: RecordProcessingJob?
        try connection.query(
            "SELECT record_id, stage, attempts, next_attempt_ms, last_error, requested_mode, awaiting_consent, updated_ms FROM processing_jobs WHERE stage<>'done' AND (awaiting_consent=0 OR stage<>'ai') AND next_attempt_ms<=? ORDER BY next_attempt_ms, updated_ms LIMIT 1",
            [.int(nowMs)]
        ) { result = Self.decodeJob($0) }
        return result
    }

    /// Earliest future `next_attempt_ms` among waiting jobs.
    func nextScheduledAttemptMs() throws -> Int64? {
        try connection.scalarInt64("SELECT MIN(next_attempt_ms) FROM processing_jobs WHERE stage<>'done' AND (awaiting_consent=0 OR stage<>'ai')")
    }

    func updateJob(
        recordID: String,
        stage: RecordProcessingStage? = nil,
        attempts: Int? = nil,
        nextAttemptMs: Int64? = nil,
        lastError: String?? = nil,
        requestedMode: String?? = nil,
        awaitingConsent: Bool? = nil,
        nowMs: Int64 = RecordDates.nowMs()
    ) throws {
        var sets: [String] = ["updated_ms=?"]
        var values: [SQLValue] = [.int(nowMs)]
        if let stage { sets.append("stage=?"); values.append(.text(stage.rawValue)) }
        if let attempts { sets.append("attempts=?"); values.append(.int(Int64(attempts))) }
        if let nextAttemptMs { sets.append("next_attempt_ms=?"); values.append(.int(nextAttemptMs)) }
        if let lastError { sets.append("last_error=?"); values.append(.optionalText(lastError)) }
        if let requestedMode { sets.append("requested_mode=?"); values.append(.optionalText(requestedMode)) }
        if let awaitingConsent { sets.append("awaiting_consent=?"); values.append(.int(awaitingConsent ? 1 : 0)) }
        values.append(.text(recordID))
        try connection.run("UPDATE processing_jobs SET \(sets.joined(separator: ", ")) WHERE record_id=?", values)
    }

    func setProcessingStatus(id: String, status: RecordProcessingStatus, error: String?? = nil, nowMs: Int64 = RecordDates.nowMs()) throws {
        if let error {
            try connection.run("UPDATE records SET processing_status=?, processing_error=?, updated_ms=? WHERE id=?", [.text(status.rawValue), .optionalText(error), .int(nowMs), .text(id)])
        } else {
            try connection.run("UPDATE records SET processing_status=?, updated_ms=? WHERE id=?", [.text(status.rawValue), .int(nowMs), .text(id)])
        }
    }

    func setAIModeUsed(id: String, mode: RecordAIModeUsed, provider: String?) throws {
        try connection.run("UPDATE records SET ai_mode_used=?, ai_provider=? WHERE id=?", [.text(mode.rawValue), .optionalText(provider), .text(id)])
    }

    func setSignatures(id: String, phash: String?, textSignature: String?) throws {
        try connection.run("UPDATE records SET phash=?, text_signature=? WHERE id=?", [.optionalText(phash), .optionalText(textSignature), .text(id)])
    }

    func signatureRows(excluding id: String) throws -> [RecordSignatureRow] {
        var rows: [RecordSignatureRow] = []
        try connection.query(
            "SELECT id, parent_id, phash, text_signature FROM records WHERE id<>? AND (phash IS NOT NULL OR text_signature IS NOT NULL)",
            [.text(id)]
        ) { s in
            let signature = s.text(3)
            rows.append(RecordSignatureRow(id: s.text(0) ?? "", parentID: s.text(1), phash: s.text(2), textSignature: signature, textIsEmpty: (signature ?? "").isEmpty))
        }
        return rows
    }

    /// Stores pages (text stage) and commits them with the FTS row.
    func storePages(recordID: String, pages: [RecordPage], pageCount: Int?) throws {
        try connection.inTransaction {
            try replacePagesInTransaction(recordID: recordID, pages: pages)
            if let pageCount {
                try connection.run("UPDATE records SET page_count=? WHERE id=? AND page_count=0", [.int(Int64(pageCount)), .text(recordID)])
            }
            try reindexInTransaction(recordID: recordID)
        }
    }

    // MARK: - Classification

    func applyClassification(id: String, type: RecordType, confidence: Double, nowMs: Int64 = RecordDates.nowMs()) throws {
        guard let record = try record(id: id), record.typeMethod != .user else { return }
        // A type chosen at import (a note) stays; `other` never replaces a known type.
        if record.typeMethod == nil, record.recordType != .other, record.source == .note { return }
        if type == .other, record.recordType != .other, record.typeMethod != .rules { return }
        try connection.inTransaction {
            try connection.run(
                "UPDATE records SET record_type=?, category=?, type_confidence=?, type_method='rules', updated_ms=? WHERE id=?",
                [.text(type.rawValue), .text(type.defaultCategory.rawValue), .real(confidence), .int(nowMs), .text(id)]
            )
            try reindexInTransaction(recordID: id)
        }
    }

    // MARK: - applyExtraction (§8.1)

    /// Writes extracted values in one transaction: never touches `rejected`/`confirmed`/`user`
    /// rows, upgrades a `suggested` row only with higher confidence, inserts new values as
    /// `suggested`, then refreshes the derived record columns and the FTS row.
    func applyExtraction(recordID: String, _ extraction: RecordExtraction, nowMs: Int64 = RecordDates.nowMs()) throws {
        try connection.inTransaction {
            try applyExtractionInTransaction(recordID: recordID, extraction, nowMs: nowMs)
        }
    }

    func applyExtractionInTransaction(recordID: String, _ extraction: RecordExtraction, nowMs: Int64) throws {
        guard try record(id: recordID) != nil else { return }
        let existing = try fields(recordID: recordID)
        var generated: [String] = []
        let result = RR.applyExtraction(
            existingRows: existing.map { $0.referenceRow() },
            newItems: extraction.fields.map(\.referenceItem),
            newID: { _ in
                let id = UUID().uuidString.lowercased()
                generated.append(id)
                return id
            }
        )
        let rows = result["rows"].array ?? []
        let byID = Dictionary(existing.map { ($0.id, $0) }, uniquingKeysWith: { a, _ in a })
        for action in result["actions"].array ?? [] {
            guard let id = action["id"].string, let row = rows.first(where: { $0["id"].string == id }) else { continue }
            let bbox: String? = row["source_bbox"].array.map { values in
                Self.bboxJSON(values.compactMap(\.double))
            } ?? row["source_bbox"].string
            let values: [SQLValue] = [
                .text(row["value_text"].string ?? ""), row["value_json"].isNull ? .null : .text(row["value_json"].compactJSON),
                .text(row["method"].string ?? "rules"), .optionalText(row["evidence"].string),
                .optionalInt(row["source_page"].double.map { Int($0) }), .optionalText(bbox), .real(row["confidence"].double ?? 0),
            ]
            switch action["action"].string {
            case "update" where byID[id] != nil:
                try connection.run(
                    "UPDATE record_fields SET value_text=?, value_json=?, method=?, evidence=?, source_page=?, source_bbox=?, confidence=?, updated_ms=? WHERE id=?",
                    values + [.int(nowMs), .text(id)]
                )
            case "insert":
                try connection.run(
                    "INSERT INTO record_fields (id, record_id, field_key, value_text, value_json, method, evidence, source_page, source_bbox, confidence, state, created_ms, updated_ms) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'suggested', ?, ?)",
                    [.text(id), .text(recordID), .text(row["field_key"].string ?? "")] + values + [.int(nowMs), .int(nowMs)]
                )
            default:
                break
            }
        }
        if let summary = extraction.summary {
            try connection.run("DELETE FROM record_highlights WHERE record_id=? AND section='summary'", [.text(recordID)])
            try connection.run(
                "INSERT INTO record_highlights (id, record_id, section, text, method, provider, source_page, confidence, dismissed, position, created_ms) VALUES (?, ?, 'summary', ?, ?, ?, ?, ?, 0, 0, ?)",
                [.text(UUID().uuidString.lowercased()), .text(recordID), .text(summary.text), .text(summary.method.rawValue),
                 .optionalText(summary.provider), .optionalInt(summary.sourcePage), .real(summary.confidence), .int(nowMs)]
            )
        }
        var classification: [RJ] = []
        if let type = extraction.recordType, let confidence = extraction.typeConfidence {
            classification.append(.obj(["record_type": .str(type.rawValue), "confidence": .number(confidence), "method": .str((extraction.typeMethod ?? .rules).rawValue)]))
        }
        try updateDerivedColumnsInTransaction(recordID: recordID, nowMs: nowMs, classification: classification)
        // §19 promotion in the same transaction as the write rule.
        try syncKnowledgeInTransaction(recordID: recordID, nowMs: nowMs)
        try reindexInTransaction(recordID: recordID)
    }

    nonisolated static func bboxJSON(_ box: [Double]) -> String {
        "[" + box.map { RecordsFold.compactNumber($0, decimals: 4) }.joined(separator: ",") + "]"
    }

    /// §8.1 `derive_record`: type columns, document date + sort date, title (while import-derived).
    func updateDerivedColumnsInTransaction(recordID: String, nowMs: Int64, classification: [RJ] = []) throws {
        guard let record = try record(id: recordID) else { return }
        let fields = try fields(recordID: recordID)
        let input: RJ = .obj([
            "title": .str(record.title), "title_is_derived": .bool(RecordDerivedColumns.isImportDerivedTitle(record: record, fields: fields)),
            "record_type": .str(record.recordType.rawValue), "category": .str(record.category.rawValue),
            "type_confidence": .number(record.typeConfidence), "type_method": .string(record.typeMethod?.rawValue),
            "document_date": .string(record.documentDate), "document_date_precision": .string(record.documentDatePrecision?.rawValue),
            "document_date_method": .string(record.documentDateMethod?.rawValue), "sort_date": .str(record.effectiveDate),
        ])
        let derived = RR.deriveRecord(input, fields.map { $0.referenceRow() }, classification.isEmpty ? .null : .arr(classification))
        var sets: [String] = []
        var values: [SQLValue] = []
        func set(_ column: String, _ value: RJ, current: String?) {
            let text: String? = value.isNull ? nil : (value.string ?? value.double.map { RecordsFold.compactNumber($0) })
            guard text != current else { return }
            sets.append("\(column)=?")
            if let number = value.double, value.string == nil { values.append(.real(number)) } else { values.append(.optionalText(text)) }
        }
        set("title", derived["title"], current: record.title)
        set("record_type", derived["record_type"], current: record.recordType.rawValue)
        set("category", derived["category"], current: record.category.rawValue)
        set("type_confidence", derived["type_confidence"], current: record.typeConfidence.map { RecordsFold.compactNumber($0) })
        set("type_method", derived["type_method"], current: record.typeMethod?.rawValue)
        set("document_date", derived["document_date"], current: record.documentDate)
        set("document_date_precision", derived["document_date_precision"], current: record.documentDatePrecision?.rawValue)
        set("document_date_method", derived["document_date_method"], current: record.documentDateMethod?.rawValue)
        if let sort = derived["sort_date"].string, sort != record.effectiveDate, derived["document_date"].string != nil {
            sets.append("sort_date=?")
            values.append(.text(sort))
        }
        guard !sets.isEmpty else { return }
        sets.append("updated_ms=?")
        values.append(.int(nowMs))
        values.append(.text(recordID))
        try connection.run("UPDATE records SET \(sets.joined(separator: ", ")) WHERE id=?", values)
    }

    // MARK: - Field review

    /// Confirm / reject / edit one field. Editing stores the user's value as `user` (evidence kept).
    func setFieldState(fieldID: String, state: RecordFieldState, editedValue: String? = nil, nowMs: Int64 = RecordDates.nowMs()) throws {
        guard let field = try field(id: fieldID) else { return }
        try connection.inTransaction {
            if let editedValue {
                var json = field.valueJSON
                if field.key == .testResult, var result = field.testResult {
                    result.value = editedValue
                    result.valueNum = Double(editedValue.replacingOccurrences(of: ",", with: "."))
                    json = RecordsJSON.encode(result).flatMap(RJ.parse)?.compactJSON
                }
                try connection.run(
                    "UPDATE record_fields SET value_text=?, value_json=?, state='user', method='user', confidence=1, updated_ms=? WHERE id=?",
                    [.text(field.key == .testResult ? field.valueText : editedValue), .optionalText(json), .int(nowMs), .text(fieldID)]
                )
            } else {
                try connection.run("UPDATE record_fields SET state=?, updated_ms=? WHERE id=?", [.text(state.rawValue), .int(nowMs), .text(fieldID)])
            }
            if field.key.isDate, state != .rejected, let value = editedValue ?? Optional(field.valueText),
               RecordFieldKey.documentDatePriority.contains(field.key) {
                try confirmDocumentDateIfPrimary(recordID: field.recordID, key: field.key, value: value, nowMs: nowMs)
            }
            try updateDerivedColumnsInTransaction(recordID: field.recordID, nowMs: nowMs)
            try syncKnowledgeInTransaction(recordID: field.recordID, nowMs: nowMs)
            try reindexInTransaction(recordID: field.recordID)
        }
    }

    /// A confirmed / edited date of the highest-priority key becomes the user's document date.
    private func confirmDocumentDateIfPrimary(recordID: String, key: RecordFieldKey, value: String, nowMs: Int64) throws {
        let fields = try fields(recordID: recordID).filter { $0.state != .rejected && $0.key.isDate }
        let primary = RecordFieldKey.documentDatePriority.first { priority in fields.contains { $0.key == priority } }
        guard primary == key, RecordDates.date(fromDay: value) != nil else { return }
        try connection.run(
            "UPDATE records SET document_date=?, document_date_precision='day', document_date_method='user', sort_date=?, updated_ms=? WHERE id=? AND (document_date_method IS NULL OR document_date_method<>'user' OR document_date=?)",
            [.text(value), .text(value), .int(nowMs), .text(recordID), .text(value)]
        )
    }

    /// "Add information": a user-entered field.
    func addUserField(recordID: String, key: RecordFieldKey, value: String, nowMs: Int64 = RecordDates.nowMs()) throws {
        let trimmed = value.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
        try connection.inTransaction {
            try connection.run(
                "INSERT INTO record_fields (id, record_id, field_key, value_text, method, confidence, state, created_ms, updated_ms) VALUES (?, ?, ?, ?, 'user', 1, 'user', ?, ?)",
                [.text(UUID().uuidString.lowercased()), .text(recordID), .text(key.rawValue), .text(trimmed), .int(nowMs), .int(nowMs)]
            )
            if key.isDate {
                try confirmDocumentDateIfPrimary(recordID: recordID, key: key, value: trimmed, nowMs: nowMs)
            }
            try updateDerivedColumnsInTransaction(recordID: recordID, nowMs: nowMs)
            try syncKnowledgeInTransaction(recordID: recordID, nowMs: nowMs)
            try reindexInTransaction(recordID: recordID)
        }
    }

    /// "Confirm all": every suggested field confirmed, record `reviewed`, pending split/duplicate untouched.
    func confirmAllFields(recordID: String, nowMs: Int64 = RecordDates.nowMs()) throws {
        try connection.inTransaction {
            try connection.run("UPDATE record_fields SET state='confirmed', updated_ms=? WHERE record_id=? AND state='suggested'", [.int(nowMs), .text(recordID)])
            try connection.run("UPDATE records SET review_status='reviewed', updated_ms=? WHERE id=?", [.int(nowMs), .text(recordID)])
            try updateDerivedColumnsInTransaction(recordID: recordID, nowMs: nowMs)
            try syncKnowledgeInTransaction(recordID: recordID, nowMs: nowMs)
            try reindexInTransaction(recordID: recordID)
        }
    }

    // MARK: - Highlights & review

    /// Replaces the deterministic (non-summary) highlights, keeping dismissed texts dismissed.
    func replaceRuleHighlights(recordID: String, _ items: [ExtractedHighlight], nowMs: Int64 = RecordDates.nowMs()) throws {
        try connection.inTransaction {
            var dismissed = Set<String>()
            try connection.query("SELECT text FROM record_highlights WHERE record_id=? AND dismissed=1", [.text(recordID)]) {
                if let text = $0.text(0) { dismissed.insert(text) }
            }
            try connection.run("DELETE FROM record_highlights WHERE record_id=? AND section<>'summary'", [.text(recordID)])
            var positions: [RecordHighlightSection: Int] = [:]
            for item in items where item.section != .summary {
                let position = positions[item.section, default: 0]
                positions[item.section] = position + 1
                try connection.run(
                    "INSERT INTO record_highlights (id, record_id, section, text, method, provider, source_page, confidence, dismissed, position, created_ms) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    [.text(UUID().uuidString.lowercased()), .text(recordID), .text(item.section.rawValue), .text(item.text),
                     .text(item.method.rawValue), .optionalText(item.provider), .optionalInt(item.sourcePage), .real(item.confidence),
                     .int(dismissed.contains(item.text) ? 1 : 0), .int(Int64(position)), .int(nowMs)]
                )
            }
            try reindexInTransaction(recordID: recordID)
        }
    }

    func dismissHighlight(id: String) throws {
        try connection.run("UPDATE record_highlights SET dismissed=1 WHERE id=?", [.text(id)])
    }

    func setReviewStatus(id: String, _ status: RecordReviewStatus, nowMs: Int64 = RecordDates.nowMs()) throws {
        try connection.run("UPDATE records SET review_status=?, updated_ms=? WHERE id=?", [.text(status.rawValue), .int(nowMs), .text(id)])
    }

    // MARK: - Duplicates

    func insertDuplicateCandidate(recordID: String, existingID: String, reason: RecordDuplicateReason, score: Double, nowMs: Int64 = RecordDates.nowMs()) throws {
        try connection.run(
            "INSERT OR IGNORE INTO duplicate_candidates (record_id, existing_id, reason, score, resolution, created_ms) VALUES (?, ?, ?, ?, 'pending', ?)",
            [.text(recordID), .text(existingID), .text(reason.rawValue), .real(score), .int(nowMs)]
        )
    }

    func resolveDuplicate(recordID: String, existingID: String, resolution: RecordDuplicateResolution) throws {
        try connection.run(
            "UPDATE duplicate_candidates SET resolution=? WHERE record_id=? AND existing_id=?",
            [.text(resolution.rawValue), .text(recordID), .text(existingID)]
        )
    }

    /// Merge: notes and tags of `newID` move into `existingID` (links arrive in Phase 3).
    func mergeRecordMetadata(from newID: String, into existingID: String, nowMs: Int64 = RecordDates.nowMs()) throws {
        guard let new = try record(id: newID), let existing = try record(id: existingID) else { return }
        let mergedTags = RecordsDatabase.normalizedTagNames(try tags(recordID: existingID) + tags(recordID: newID))
        let notes = [existing.notes, new.notes].compactMap { $0?.trimmingCharacters(in: .whitespacesAndNewlines) }.filter { !$0.isEmpty }
        let mergedNotes = notes.isEmpty ? nil : Array(NSOrderedSet(array: notes)).compactMap { $0 as? String }.joined(separator: "\n\n")
        try connection.inTransaction {
            try connection.run("UPDATE records SET notes=?, updated_ms=? WHERE id=?", [.optionalText(mergedNotes), .int(nowMs), .text(existingID)])
            try setTagsInTransaction(recordID: existingID, names: mergedTags)
            try moveLinksInTransaction(from: newID, to: existingID, nowMs: nowMs)
            try reindexInTransaction(recordID: existingID)
        }
    }

    /// Replace: the existing record takes the new file's path, checksum, size, type and page
    /// basics; its extracted rows are cleared for reprocessing. Notes and tags stay.
    func replaceOriginal(existingID: String, with newRecord: HealthRecord, nowMs: Int64 = RecordDates.nowMs()) throws {
        try connection.inTransaction {
            try connection.run(
                """
                UPDATE records SET file_path=?, thumbnail_path=?, checksum_sha256=?, file_size=?, mime_type=?, file_type=?, page_count=?,
                  original_filename=COALESCE(?, original_filename), phash=NULL, text_signature=NULL, ai_mode_used='none', ai_provider=NULL,
                  processing_error=NULL, review_status='none', updated_ms=? WHERE id=?
                """,
                [.optionalText(newRecord.filePath), .optionalText(newRecord.thumbnailPath), .optionalText(newRecord.checksumSHA256),
                 .int(newRecord.fileSize), .text(newRecord.mimeType), .text(newRecord.fileType.rawValue), .int(Int64(newRecord.pageCount)),
                 .optionalText(newRecord.originalFilename), .int(nowMs), .text(existingID)]
            )
            try connection.run("DELETE FROM record_pages WHERE record_id=?", [.text(existingID)])
            try connection.run("DELETE FROM record_highlights WHERE record_id=?", [.text(existingID)])
            try connection.run("DELETE FROM record_fields WHERE record_id=? AND state='suggested'", [.text(existingID)])
            try connection.run("DELETE FROM split_proposals WHERE record_id=?", [.text(existingID)])
            try connection.run("DELETE FROM processing_jobs WHERE record_id=?", [.text(existingID)])
            try syncKnowledgeInTransaction(recordID: existingID, nowMs: nowMs)
            try reindexInTransaction(recordID: existingID)
        }
    }

    // MARK: - Splits

    func upsertSplitProposal(recordID: String, segments: [RecordSplitSegment], nowMs: Int64 = RecordDates.nowMs()) throws {
        if let existing = try splitProposal(recordID: recordID), existing.status != .pending { return }
        try connection.run("DELETE FROM split_proposals WHERE record_id=?", [.text(recordID)])
        guard segments.count >= 2 else { return }
        try connection.run(
            "INSERT INTO split_proposals (record_id, segments_json, status, created_ms, updated_ms) VALUES (?, ?, 'pending', ?, ?)",
            [.text(recordID), .text(RecordSplitProposal.encodeSegments(segments)), .int(nowMs), .int(nowMs)]
        )
    }

    func setSplitStatus(recordID: String, _ status: RecordSplitStatus, nowMs: Int64 = RecordDates.nowMs()) throws {
        try connection.run("UPDATE split_proposals SET status=?, updated_ms=? WHERE record_id=?", [.text(status.rawValue), .int(nowMs), .text(recordID)])
    }

    /// Split acceptance (plan §3.11): one child per segment sharing the parent's file, with the
    /// page range, pages and the fields / highlights whose `source_page` falls in the range.
    /// The parent is archived. Returns the child ids.
    func acceptSplit(parentID: String, segments: [RecordSplitSegment], nowMs: Int64 = RecordDates.nowMs()) throws -> [String] {
        guard let parent = try record(id: parentID), segments.count >= 2 else { return [] }
        let pages = try pages(recordID: parentID)
        let fields = try fields(recordID: parentID)
        let parentTags = try tags(recordID: parentID)
        let parentObservations = try observations(recordID: parentID, includeRejected: false)
        var childIDs: [String] = []
        try connection.inTransaction {
            for (index, segment) in segments.enumerated() {
                let childID = UUID().uuidString.lowercased()
                let range = segment.pageStart...segment.pageEnd
                let title = segment.title.isEmpty ? "\(parent.title) (\(index + 1))" : segment.title
                try connection.run(
                    """
                    INSERT INTO records (id, parent_id, page_start, page_end, title, record_type, category, source, import_method, source_app, original_filename, created_ms, updated_ms, sort_date, mime_type, file_type, file_size, page_count, file_path, thumbnail_path, checksum_sha256, processing_status, review_status, favorite, archived, type_confidence, type_method)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, ?, ?, NULL, NULL, 'queued', 'none', 0, 0, ?, 'rules')
                    """,
                    [.text(childID), .text(parentID), .int(Int64(segment.pageStart)), .int(Int64(segment.pageEnd)), .text(title),
                     .text(segment.recordType.rawValue), .text(segment.recordType.defaultCategory.rawValue), .text(parent.source.rawValue),
                     .text(parent.importMethod.rawValue), .optionalText(parent.sourceApp), .optionalText(parent.originalFilename),
                     .int(nowMs), .int(nowMs + Int64(index)), .text(parent.effectiveDate), .text(parent.mimeType), .text(parent.fileType.rawValue),
                     .int(Int64(range.count)), .optionalText(parent.filePath), .real(segment.confidence)]
                )
                let childPages = pages.filter { range.contains($0.pageIndex) }.map { page -> RecordPage in
                    var copy = page
                    copy.recordID = childID
                    copy.pageIndex = page.pageIndex - segment.pageStart
                    return copy
                }
                try replacePagesInTransaction(recordID: childID, pages: childPages)
                var fieldIDMap: [String: String] = [:]
                for field in fields where field.sourcePage.map(range.contains) == true && field.state != .rejected {
                    let newFieldID = UUID().uuidString.lowercased()
                    fieldIDMap[field.id] = newFieldID
                    try connection.run(
                        "INSERT INTO record_fields (id, record_id, field_key, value_text, value_json, method, confidence, state, source_page, source_bbox, evidence, created_ms, updated_ms) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                        [.text(newFieldID), .text(childID), .text(field.key.rawValue), .text(field.valueText), .optionalText(field.valueJSON),
                         .text(field.method.rawValue), .real(field.confidence), .text(field.state.rawValue), .int(Int64((field.sourcePage ?? 0) - segment.pageStart)),
                         .optionalText(field.sourceBBox), .optionalText(field.evidence), .int(nowMs), .int(nowMs)]
                    )
                }
                try copyObservationsInTransaction(from: parentObservations, toChild: childID, pageStart: segment.pageStart, range: range, fieldIDMap: fieldIDMap, nowMs: nowMs)
                try syncKnowledgeInTransaction(recordID: childID, nowMs: nowMs)
                if !parentTags.isEmpty { try setTagsInTransaction(recordID: childID, names: parentTags) }
                try connection.run(
                    "INSERT INTO processing_jobs (record_id, stage, attempts, next_attempt_ms, awaiting_consent, updated_ms) VALUES (?, 'classify', 0, 0, 0, ?)",
                    [.text(childID), .int(nowMs)]
                )
                try reindexInTransaction(recordID: childID)
                childIDs.append(childID)
            }
            try connection.run("UPDATE split_proposals SET status='accepted', segments_json=?, updated_ms=? WHERE record_id=?",
                               [.text(RecordSplitProposal.encodeSegments(segments)), .int(nowMs), .text(parentID)])
            try connection.run("UPDATE records SET archived=1, review_status='reviewed', updated_ms=? WHERE id=?", [.int(nowMs), .text(parentID)])
            try linkSplitInTransaction(parentID: parentID, childIDs: childIDs, nowMs: nowMs)
        }
        return childIDs
    }
}

/// Reads a statement's columns shifted by `offset` so `decodeRecord` can decode joined rows.
private nonisolated final class OffsetStatement: HealthDBStatementReading {
    let base: HealthDBStatement
    let offset: Int32
    init(base: HealthDBStatement, offset: Int32) {
        self.base = base
        self.offset = offset
    }
    func int64(_ index: Int32) -> Int64? { base.int64(index + offset) }
    func int(_ index: Int32) -> Int? { base.int(index + offset) }
    func double(_ index: Int32) -> Double? { base.double(index + offset) }
    func text(_ index: Int32) -> String? { base.text(index + offset) }
}

nonisolated protocol HealthDBStatementReading {
    func int64(_ index: Int32) -> Int64?
    func int(_ index: Int32) -> Int?
    func double(_ index: Int32) -> Double?
    func text(_ index: Int32) -> String?
}

extension HealthDBStatement: HealthDBStatementReading {}

/// §8.1 derived record columns.
nonisolated enum RecordDerivedColumns {
    /// Phase 1 derivation (filename title, `<Type> — <date>`, "Untitled record") or a title this
    /// rule wrote earlier (a report name or `<Type label> — <facility>` with the English labels).
    static func isImportDerivedTitle(record: HealthRecord, fields: [RecordField]) -> Bool {
        let title = record.title
        if title == String(localized: "Untitled record") || title == "Untitled record" { return true }
        if let fromFile = RecordTitleDeriver.filenameTitle(record.originalFilename), fromFile == title { return true }
        for type in RecordType.allCases {
            if title.hasPrefix("\(type.title) — ") || title.hasPrefix("\(RR.typeLabel[type.rawValue] ?? "") — ") { return true }
        }
        return fields.contains { $0.key == .reportName && $0.valueText == title }
    }
}
