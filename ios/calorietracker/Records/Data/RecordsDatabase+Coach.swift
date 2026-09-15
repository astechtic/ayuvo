import Foundation

// Phase 4 reads for the Coach records tools (docs/health-records.md §26–§31). Each function builds the
// part of the reference "store snapshot" the operation needs and runs the reference port on it; search
// ranking uses the FTS4 index (`matchinfo('pcnalx')`), whose row equals the reference `fts_row`.
extension RecordsDatabase {
    /// Bump when `reindexInTransaction` changes what a row contains; open rebuilds every row once.
    nonisolated static let ftsRowVersion = "4"

    // MARK: Snapshot rows

    nonisolated static func snapshotRecordRow(_ r: HealthRecord) -> RJ {
        .obj([
            "id": .str(r.id), "seq": .int(Int(r.seq)), "title": .str(r.title), "record_type": .str(r.recordType.rawValue),
            "category": .str(r.category.rawValue), "sort_date": .str(r.effectiveDate), "created_ms": .int(Int(r.createdMs)),
            "review_status": .str(r.reviewStatus.rawValue), "ai_mode_used": .str(r.aiModeUsed.rawValue), "page_count": .int(r.pageCount),
            "archived": .bool(r.archived), "favorite": .bool(r.favorite), "source": .str(r.source.rawValue), "notes": .string(r.notes),
        ])
    }

    nonisolated static func snapshotFieldRow(_ f: RecordField) -> RJ {
        guard case .obj(var o) = f.referenceRow() else { return .null }
        o["record_id"] = .str(f.recordID)
        return .obj(o)
    }

    private func placeholders(_ count: Int) -> String { Array(repeating: "?", count: count).joined(separator: ", ") }

    /// Records in row order (`seq`), optionally only `ids`.
    func snapshotRecords(ids: [String]? = nil) throws -> [RJ] {
        var rows: [RJ] = []
        if let ids {
            guard !ids.isEmpty else { return [] }
            try connection.query("SELECT \(Self.columns) FROM records WHERE id IN (\(placeholders(ids.count))) ORDER BY seq", ids.map { .text($0) }) {
                rows.append(Self.snapshotRecordRow(Self.decodeRecord($0)))
            }
        } else {
            try connection.query("SELECT \(Self.columns) FROM records ORDER BY seq") { rows.append(Self.snapshotRecordRow(Self.decodeRecord($0))) }
        }
        return rows
    }

    func snapshotFields(recordIDs: [String]? = nil, keys: [String]? = nil) throws -> [RJ] {
        var clauses: [String] = []
        var values: [SQLValue] = []
        if let recordIDs {
            guard !recordIDs.isEmpty else { return [] }
            clauses.append("record_id IN (\(placeholders(recordIDs.count)))")
            values += recordIDs.map { .text($0) }
        }
        if let keys {
            guard !keys.isEmpty else { return [] }
            clauses.append("field_key IN (\(placeholders(keys.count)))")
            values += keys.map { .text($0) }
        }
        var rows: [RJ] = []
        try connection.query(
            "SELECT \(Self.fieldColumns) FROM record_fields\(clauses.isEmpty ? "" : " WHERE " + clauses.joined(separator: " AND ")) ORDER BY rowid",
            values
        ) { if let f = Self.decodeField($0) { rows.append(Self.snapshotFieldRow(f)) } }
        return rows
    }

    /// Observations in row order. `analyteIDs` limits to those analytes; `includeUnmapped` adds rows without one.
    func snapshotObservations(recordIDs: [String]? = nil, analyteIDs: [String]? = nil, includeUnmapped: Bool = false) throws -> [RJ] {
        var clauses: [String] = []
        var values: [SQLValue] = []
        if let recordIDs {
            guard !recordIDs.isEmpty else { return [] }
            clauses.append("record_id IN (\(placeholders(recordIDs.count)))")
            values += recordIDs.map { .text($0) }
        }
        if let analyteIDs {
            var alternatives: [String] = []
            if !analyteIDs.isEmpty {
                alternatives.append("analyte_id IN (\(placeholders(analyteIDs.count)))")
                values += analyteIDs.map { .text($0) }
            }
            if includeUnmapped { alternatives.append("analyte_id IS NULL") }
            guard !alternatives.isEmpty else { return [] }
            clauses.append("(" + alternatives.joined(separator: " OR ") + ")")
        }
        var rows: [RJ] = []
        try connection.query(
            "SELECT \(Self.observationColumns) FROM observations\(clauses.isEmpty ? "" : " WHERE " + clauses.joined(separator: " AND ")) ORDER BY rowid",
            values
        ) { rows.append(Self.decodeObservation($0).referenceRow) }
        return rows
    }

    func snapshotHighlights(recordIDs: [String]? = nil, importantOnly: Bool = false) throws -> [RJ] {
        var clauses: [String] = importantOnly ? ["section='important'", "dismissed=0"] : []
        var values: [SQLValue] = []
        if let recordIDs {
            guard !recordIDs.isEmpty else { return [] }
            clauses.append("record_id IN (\(placeholders(recordIDs.count)))")
            values += recordIDs.map { .text($0) }
        }
        var rows: [RJ] = []
        try connection.query(
            "SELECT id, record_id, section, text, position, dismissed FROM record_highlights\(clauses.isEmpty ? "" : " WHERE " + clauses.joined(separator: " AND ")) ORDER BY rowid",
            values
        ) { s in
            rows.append(.obj([
                "id": .string(s.text(0)), "record_id": .string(s.text(1)), "section": .string(s.text(2)), "text": .string(s.text(3)),
                "position": .int(s.int(4) ?? 0), "dismissed": .bool((s.int(5) ?? 0) != 0),
            ]))
        }
        return rows
    }

    func snapshotPages(recordID: String) throws -> [RJ] {
        var rows: [RJ] = []
        try connection.query("SELECT record_id, page_index, text FROM record_pages WHERE record_id=? ORDER BY page_index", [.text(recordID)]) { s in
            rows.append(.obj(["record_id": .string(s.text(0)), "page_index": .int(s.int(1) ?? 0), "text": .string(s.text(2))]))
        }
        return rows
    }

    func snapshotLinks(kind: RecordLinkKind? = nil) throws -> [RJ] {
        var rows: [RJ] = []
        try connection.query(
            "SELECT a_id, b_id, kind, origin, status FROM record_links\(kind == nil ? "" : " WHERE kind=?") ORDER BY rowid",
            kind.map { [SQLValue.text($0.rawValue)] } ?? [SQLValue]()
        ) { s in
            rows.append(.obj(["a_id": .string(s.text(0)), "b_id": .string(s.text(1)), "kind": .string(s.text(2)), "origin": .string(s.text(3)), "status": .string(s.text(4))]))
        }
        return rows
    }

    private func snapshotUserAliases() throws -> RJ {
        .obj(try userAliases().mapValues { RJ.str($0) })
    }

    // MARK: Tools (§28)

    /// Runs one records tool on the store. `selectedIDs` restricts it (§27).
    func coachToolPayload(name: String, args: RJ, selectedIDs: [String], today: String, dateOrder: String) throws -> RJ {
        switch name {
        case RecordsCoachContract.searchTool:
            return try coachSearchPayload(args: args, selectedIDs: selectedIDs, today: today, dateOrder: dateOrder)
        case RecordsCoachContract.getTool:
            let rid = args["record_id"].string ?? ""
            var include = false
            if case .bool(true) = args["include_text"] { include = true }
            let known = selectedIDs.isEmpty || selectedIDs.contains(rid)
            let snapshot: RJ = .obj([
                "records": .arr(known ? try snapshotRecords(ids: [rid]) : []),
                "fields": .arr(known ? try snapshotFields(recordIDs: [rid]) : []),
                "observations": .arr(known ? try snapshotObservations(recordIDs: [rid]) : []),
                "highlights": .arr(known ? try snapshotHighlights(recordIDs: [rid]) : []),
                "pages": .arr(known && include ? try snapshotPages(recordID: rid) : []),
            ])
            return RR.recordsGetPayload(snapshot, args: args, selectedIDs: selectedIDs)
        case RecordsCoachContract.seriesTool:
            let argument = args["analyte"].string ?? ""
            let aliases = try snapshotUserAliases()
            let resolved = RR.resolveSeriesAnalyte(.obj(["user_aliases": aliases]), argument, catalog: catalog)
            let snapshot: RJ = .obj([
                "records": .arr(try snapshotRecords()),
                "observations": .arr(try snapshotObservations(analyteIDs: resolved.map { [$0] } ?? [], includeUnmapped: true)),
                "user_aliases": aliases,
            ])
            return RR.recordsSeriesPayload(snapshot, args: args, selectedIDs: selectedIDs, catalog: catalog)
        default:
            return RR.coachError("unavailable")
        }
    }

    private func coachSearchPayload(args: RJ, selectedIDs: [String], today: String, dateOrder: String) throws -> RJ {
        let q = RR.parseQuery(args["query"].string ?? "", today: today, dateOrder: dateOrder, catalog: catalog)
        let analyteIDs = Array(Set((q["analytes"].array ?? []).compactMap(\.string)
            + (q["analyte_conditions"].array ?? []).compactMap { $0["analyte_id"].string })).sorted()
        var keys = ["doctor_name", "facility"]
        if !(q["flags"].array ?? []).isEmpty { keys.append("test_result") }
        let snapshot: RJ = .obj([
            "records": .arr(try snapshotRecords()),
            "fields": .arr(try snapshotFields(keys: keys)),
            "observations": .arr(analyteIDs.isEmpty ? [] : try snapshotObservations(analyteIDs: analyteIDs)),
            "highlights": .arr(try snapshotHighlights(importantOnly: true)),
        ])
        var scoreError: Error?
        let payload = RR.recordsSearchPayload(snapshot, args: args, selectedIDs: selectedIDs, today: today, dateOrder: dateOrder, catalog: catalog) { terms in
            do {
                return try self.ftsScores(terms: terms)
            } catch {
                scoreError = error
                return [:]
            }
        }
        if let scoreError { throw scoreError }
        return payload
    }

    /// BM25 (§17/§28) of every record whose FTS row prefix-matches all terms, keyed by record id.
    func ftsScores(terms: [String]) throws -> [String: Double] {
        guard !terms.isEmpty else { return [:] }
        let match = terms.map { "\($0)*" }.joined(separator: " ")
        var scores: [String: Double] = [:]
        try connection.query(
            "SELECT records.id, matchinfo(records_fts, 'pcnalx') FROM records_fts JOIN records ON records.seq = records_fts.docid WHERE records_fts MATCH ?",
            [.text(match)]
        ) { s in
            guard let id = s.text(0) else { return }
            scores[id] = Self.bm25(matchInfo: s.blob(1).map(Self.uint32Array) ?? [], weights: RR.coachFTSWeights)
        }
        return scores
    }

    // MARK: Prompt, packing, entry points

    func coachPromptLines(accessEnabled: Bool, selectedIDs: [String]) throws -> RJ {
        let snapshot: RJ = .obj(["records": .arr(accessEnabled ? try snapshotRecords() : [])])
        return RR.coachPromptLines(snapshot, accessEnabled: accessEnabled, selectedIDs: selectedIDs, typeLabels: RecordsCoach.typeLabels)
    }

    func coachPack(selectedIDs: [String]) throws -> RJ {
        let snapshot: RJ = .obj([
            "records": .arr(try snapshotRecords(ids: selectedIDs)),
            "fields": .arr(try snapshotFields(recordIDs: selectedIDs)),
            "observations": .arr(try snapshotObservations(recordIDs: selectedIDs)),
        ])
        return RR.packCoachRecords(snapshot, selectedIDs: selectedIDs, typeLabels: RecordsCoach.typeLabels)
    }

    private func compareSnapshot() throws -> RJ {
        var observations: [RJ] = []
        try connection.query("SELECT record_id, analyte_id, state FROM observations WHERE analyte_id IS NOT NULL ORDER BY rowid") { s in
            observations.append(.obj(["record_id": .string(s.text(0)), "analyte_id": .string(s.text(1)), "state": .string(s.text(2))]))
        }
        return .obj([
            "records": .arr(try snapshotRecords()),
            "observations": .arr(observations),
            "links": .arr(try snapshotLinks(kind: .previousReport)),
        ])
    }

    /// §27 "Compare with previous report" partner of a record, if any.
    func coachCompareCandidate(recordID: String) throws -> HealthRecord? {
        let result = RR.compareCandidateRef(try compareSnapshot(), recordID: recordID)
        return try result["previous_id"].string.flatMap { try record(id: $0) }
    }

    /// §27 Coach chips: the latest lab reports and the compare pair.
    func coachLatestLabSelection() throws -> (latest: [HealthRecord], compare: [HealthRecord]) {
        let result = RR.latestLabSelection(try compareSnapshot())
        let latest = try (result["latest"].array ?? []).compactMap(\.string).compactMap { try record(id: $0) }
        let compare = try (result["compare"].array ?? []).compactMap(\.string).compactMap { try record(id: $0) }
        return (latest, compare.count == 2 ? compare : [])
    }

    func coachRecords(ids: [String]) throws -> [HealthRecord] {
        try ids.compactMap { try record(id: $0) }
    }

    // MARK: FTS row upgrade

    /// Rebuilds every FTS row once when the row definition changed (§31 alignment with `fts_row`).
    func reindexAllIfNeeded() throws {
        guard try metaValue("fts_row_version") != Self.ftsRowVersion else { return }
        try connection.inTransaction {
            var ids: [String] = []
            try connection.query("SELECT id FROM records ORDER BY seq") { if let id = $0.text(0) { ids.append(id) } }
            for id in ids { try reindexInTransaction(recordID: id) }
            try connection.run("INSERT OR REPLACE INTO records_meta (key, value) VALUES ('fts_row_version', ?)", [.text(Self.ftsRowVersion)])
        }
    }
}
