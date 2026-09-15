import Foundation
import SQLite3

// Phase 3 storage (docs/health-records.md §19–§24): observations + promotion, analyte user aliases,
// entities, record links, trends, value search and relation profiles.
extension RecordsDatabase {
    nonisolated static let observationColumns = "id, record_id, field_id, analyte_id, analyte_method, raw_name, value_num, value_text, unit, canonical_value, canonical_unit, ref_low, ref_high, ref_text, flag, observed_date, observed_date_method, method, confidence, state, source_page, source_bbox, evidence, excluded_from_trends, created_ms, updated_ms"
    nonisolated static let observationColumnCount: Int32 = 26

    nonisolated static var qualifiedObservationColumns: String {
        observationColumns.split(separator: ",").map { "o." + $0.trimmingCharacters(in: .whitespaces) }.joined(separator: ", ")
    }

    nonisolated static func decodeObservation<S: HealthDBStatementReading>(_ s: S) -> RecordObservation {
        RecordObservation(
            id: s.text(0) ?? "",
            recordID: s.text(1) ?? "",
            fieldID: s.text(2),
            analyteID: s.text(3),
            analyteMethod: s.text(4).flatMap(RecordAnalyteMethod.init(rawValue:)),
            rawName: s.text(5) ?? "",
            valueNum: s.double(6),
            valueText: s.text(7) ?? "",
            unit: s.text(8),
            canonicalValue: s.double(9),
            canonicalUnit: s.text(10),
            refLow: s.double(11),
            refHigh: s.double(12),
            refText: s.text(13),
            flag: RecordResultFlag(rawValue: s.text(14) ?? "") ?? .unknown,
            observedDate: s.text(15),
            observedDateMethod: s.text(16).flatMap(RecordObservedDateMethod.init(rawValue:)),
            method: RecordFieldMethod(rawValue: s.text(17) ?? "") ?? .rules,
            confidence: s.double(18) ?? 0,
            state: RecordFieldState(rawValue: s.text(19) ?? "") ?? .suggested,
            sourcePage: s.int(20),
            sourceBBox: s.text(21),
            evidence: s.text(22),
            excludedFromTrends: (s.int(23) ?? 0) != 0,
            createdMs: s.int64(24) ?? 0,
            updatedMs: s.int64(25) ?? 0
        )
    }

    private func observationValues(_ o: RecordObservation) -> [SQLValue] {
        [
            .text(o.id), .text(o.recordID), .optionalText(o.fieldID), .optionalText(o.analyteID), .optionalText(o.analyteMethod?.rawValue),
            .text(o.rawName), .optionalReal(o.valueNum), .text(o.valueText), .optionalText(o.unit), .optionalReal(o.canonicalValue),
            .optionalText(o.canonicalUnit), .optionalReal(o.refLow), .optionalReal(o.refHigh), .optionalText(o.refText), .text(o.flag.rawValue),
            .optionalText(o.observedDate), .optionalText(o.observedDateMethod?.rawValue), .text(o.method.rawValue), .real(o.confidence),
            .text(o.state.rawValue), .optionalInt(o.sourcePage), .optionalText(o.sourceBBox), .optionalText(o.evidence),
            .int(o.excludedFromTrends ? 1 : 0), .int(o.createdMs), .int(o.updatedMs),
        ]
    }

    func insertObservationInTransaction(_ o: RecordObservation) throws {
        try connection.run(
            "INSERT INTO observations (\(Self.observationColumns)) VALUES (\(Array(repeating: "?", count: Int(Self.observationColumnCount)).joined(separator: ", ")))",
            observationValues(o)
        )
    }

    func updateObservationInTransaction(_ o: RecordObservation) throws {
        let columns = Self.observationColumns.split(separator: ",").map { $0.trimmingCharacters(in: .whitespaces) }
        let values = observationValues(o)
        let sets = columns.dropFirst().map { "\($0)=?" }.joined(separator: ", ")
        try connection.run("UPDATE observations SET \(sets) WHERE id=?", Array(values.dropFirst()) + [.text(o.id)])
    }

    // MARK: - Reads

    func observations(recordID: String, includeRejected: Bool = true) throws -> [RecordObservation] {
        var rows: [RecordObservation] = []
        try connection.query(
            "SELECT \(Self.observationColumns) FROM observations WHERE record_id=?\(includeRejected ? "" : " AND state<>'rejected'") ORDER BY (field_id IS NULL), created_ms, rowid",
            [.text(recordID)]
        ) { rows.append(Self.decodeObservation($0)) }
        return rows
    }

    func observation(id: String) throws -> RecordObservation? {
        var result: RecordObservation?
        try connection.query("SELECT \(Self.observationColumns) FROM observations WHERE id=?", [.text(id)]) { result = Self.decodeObservation($0) }
        return result
    }

    func userAliases() throws -> [String: String] {
        var aliases: [String: String] = [:]
        try connection.query("SELECT normalized_name, analyte_id FROM analyte_user_aliases") { s in
            if let name = s.text(0), let id = s.text(1) { aliases[name] = id }
        }
        return aliases
    }

    /// Detail extras: observations, mini trends and related records.
    func loadKnowledge(into detail: inout RecordDetail) throws {
        let id = detail.record.id
        detail.observations = try observations(recordID: id, includeRejected: false)
        var items: [String: [RecordObservationItem]] = [:]
        for analyteID in Set(detail.observations.compactMap(\.analyteID)) {
            items[analyteID] = try trendItems(analyteID: analyteID)
        }
        var mini: [String: RecordMiniTrend] = [:]
        for o in detail.observations {
            guard let analyteID = o.analyteID, let rows = items[analyteID],
                  let trend = RecordTrendBuilder.miniTrend(analyteID: analyteID, observationID: o.id, items: rows, catalog: catalog)
            else { continue }
            mini[o.id] = trend
        }
        detail.miniTrends = mini
        detail.related = try relatedRecords(recordID: id)
    }

    // MARK: - Promotion (§19)

    /// Promotion + mapping + observed dates + entities for one record, inside the caller's transaction.
    func syncKnowledgeInTransaction(recordID: String, nowMs: Int64) throws {
        try syncObservationsInTransaction(recordID: recordID, nowMs: nowMs)
        try refreshEntitiesInTransaction(recordID: recordID, nowMs: nowMs)
    }

    func syncKnowledge(recordID: String, nowMs: Int64 = RecordDates.nowMs()) throws {
        try connection.inTransaction {
            try syncKnowledgeInTransaction(recordID: recordID, nowMs: nowMs)
            try reindexInTransaction(recordID: recordID)
        }
    }

    /// §19 promotion via the reference (`promote_observations`): inserts, rejections, suggested updates and
    /// observed-date refreshes are written back; promoted rows whose field disappeared are dropped.
    func syncObservationsInTransaction(recordID: String, nowMs: Int64) throws {
        guard let record = try record(id: recordID) else { return }
        let fields = try fields(recordID: recordID)
        var existing = try observations(recordID: recordID)
        let fieldIDs = Set(fields.map(\.id))
        for o in existing where o.method != .user && o.state == .suggested && (o.fieldID.map { !fieldIDs.contains($0) } ?? true) {
            try connection.run("DELETE FROM observations WHERE id=?", [.text(o.id)])
        }
        existing.removeAll { $0.method != .user && $0.state == .suggested && ($0.fieldID.map { !fieldIDs.contains($0) } ?? true) }
        let recordRow: RJ = .obj(["id": .str(recordID), "document_date": .string(record.documentDate), "sort_date": .str(record.effectiveDate)])
        var generated: [String] = []
        let result = RR.promoteObservations(
            fields: fields.map(\.knowledgeRow), existing: existing.map(\.referenceRow), record: recordRow,
            userAliases: try userAliases(), nowMs: .int(Int(nowMs)),
            newID: { _ in
                let id = UUID().uuidString.lowercased()
                generated.append(id)
                return id
            },
            catalog
        )
        let rows = (result["observations"].array ?? []).map { RecordObservation(referenceRow: $0) }
        let byID = Dictionary(rows.map { ($0.id, $0) }, uniquingKeysWith: { a, _ in a })
        for action in result["actions"].array ?? [] {
            guard let id = action["id"].string, let row = byID[id] else { continue }
            switch action["action"].string {
            case "insert": try insertObservationInTransaction(row)
            case "update", "reject": try updateObservationInTransaction(row)
            default: break
            }
        }
        // User-added rows (no field) follow the record date like promoted ones.
        let od = RR.observedDateRef(record: recordRow, fields: fields.map(\.knowledgeRow))
        for o in existing where o.fieldID == nil && o.state != .rejected && o.observedDateMethod != .user
            && (o.observedDate != od["date"].string || o.observedDateMethod?.rawValue != od["method"].string) {
            var next = o
            next.observedDate = od["date"].string
            next.observedDateMethod = od["method"].string.flatMap(RecordObservedDateMethod.init(rawValue:))
            next.updatedMs = nowMs
            try updateObservationInTransaction(next)
        }
    }

    // MARK: - Edits (§24)

    @discardableResult
    func editObservation(id: String, edit: RecordObservationEdit, nowMs: Int64 = RecordDates.nowMs()) throws -> RecordObservation? {
        guard let current = try observation(id: id) else { return nil }
        var next = current
        if edit != RecordObservationEdit(excludedFromTrends: edit.excludedFromTrends) || edit.excludedFromTrends != nil {
            let result = RR.editObservationRef(current.referenceRow, RecordObservationEditing.patch(edit, nowMs: nowMs), catalog)
            guard result["error"].isNull else { return nil }
            next = RecordObservation(referenceRow: result["observation"])
        }
        var touched: Set<String> = [current.recordID]
        try connection.inTransaction {
            try updateObservationInTransaction(next)
            if edit.rememberAlias, let analyteID = next.analyteID {
                let unmapped = edit.applyAliasToExisting ? try unmappedObservations(excluding: id) : []
                let result = RR.applyUserAliasRef(unmapped.map(\.referenceRow), rawName: next.rawName, analyteID: analyteID, nowMs: .int(Int(nowMs)), catalog)
                if let key = result["alias"]["normalized_name"].string, !key.isEmpty {
                    try connection.run(
                        "INSERT INTO analyte_user_aliases (normalized_name, analyte_id, created_ms) VALUES (?, ?, ?) ON CONFLICT(normalized_name) DO UPDATE SET analyte_id=excluded.analyte_id",
                        [.text(key), .text(analyteID), .int(nowMs)]
                    )
                }
                let updated = Set((result["updated_ids"].array ?? []).compactMap(\.string))
                for row in (result["observations"].array ?? []).map({ RecordObservation(referenceRow: $0) }) where updated.contains(row.id) {
                    try updateObservationInTransaction(row)
                    touched.insert(row.recordID)
                }
            }
            for recordID in touched { try reindexInTransaction(recordID: recordID) }
        }
        return next
    }

    /// Remove from the record's data points (§24 remove = `rejected`).
    func removeObservation(id: String, nowMs: Int64 = RecordDates.nowMs()) throws {
        guard let current = try observation(id: id) else { return }
        try connection.inTransaction {
            try connection.run("UPDATE observations SET state='rejected', updated_ms=? WHERE id=?", [.int(nowMs), .text(id)])
            try reindexInTransaction(recordID: current.recordID)
        }
    }

    /// Unmapped, non-rejected observations (optionally only those sharing `rawName`'s §20 key).
    func unmappedObservations(sameNameAs rawName: String? = nil, excluding id: String? = nil) throws -> [RecordObservation] {
        var rows: [RecordObservation] = []
        let key = rawName.map { RR.normalizeTestNameRef($0) }
        try connection.query(
            "SELECT \(Self.observationColumns) FROM observations WHERE analyte_id IS NULL AND state<>'rejected' AND id<>?",
            [.text(id ?? "")]
        ) { s in
            let o = Self.decodeObservation(s)
            if let key, !RR.testNameKeys(o.rawName).contains(key) { return }
            rows.append(o)
        }
        return rows
    }

    /// "Add value" (§24): `method = user`, no source.
    @discardableResult
    func addUserObservation(recordID: String, draft: RecordObservationDraft, nowMs: Int64 = RecordDates.nowMs()) throws -> RecordObservation? {
        let value = draft.valueText.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !value.isEmpty, try record(id: recordID) != nil else { return nil }
        let analyte = draft.analyteID.flatMap { catalog.analyte(id: $0) }
        let name = draft.name.trimmingCharacters(in: .whitespacesAndNewlines)
        let base = RecordObservation(
            id: UUID().uuidString.lowercased(), recordID: recordID, fieldID: nil, analyteID: analyte?.id,
            analyteMethod: analyte == nil ? nil : .user, rawName: name.isEmpty ? (analyte?.displayName ?? value) : name,
            valueNum: nil, valueText: value, unit: nil, canonicalValue: nil, canonicalUnit: nil,
            refLow: nil, refHigh: nil, refText: nil, flag: .unknown,
            observedDate: draft.observedDate, observedDateMethod: .user, method: .user, confidence: 1, state: .user,
            sourcePage: nil, sourceBBox: nil, evidence: nil, excludedFromTrends: false, createdMs: nowMs, updatedMs: nowMs
        )
        // Value, unit and range go through the §24 edit so canonical value and flag follow the same rules.
        let edit = RecordObservationEdit(valueText: value, unit: .some(draft.unit), refText: .some(draft.refText))
        let result = RR.editObservationRef(base.referenceRow, RecordObservationEditing.patch(edit, nowMs: nowMs), catalog)
        guard result["error"].isNull else { return nil }
        var o = RecordObservation(referenceRow: result["observation"])
        o.state = .user
        o.createdMs = nowMs
        try connection.inTransaction {
            try insertObservationInTransaction(o)
            try reindexInTransaction(recordID: recordID)
        }
        return o
    }

    /// Split children take the parent's observations whose source page falls in their range.
    func copyObservationsInTransaction(from parent: [RecordObservation], toChild childID: String, pageStart: Int, range: ClosedRange<Int>, fieldIDMap: [String: String], nowMs: Int64) throws {
        for o in parent where o.sourcePage.map(range.contains) == true {
            var copy = o
            copy.id = UUID().uuidString.lowercased()
            copy.recordID = childID
            copy.fieldID = o.fieldID.flatMap { fieldIDMap[$0] }
            if o.fieldID != nil, copy.fieldID == nil { continue }
            copy.sourcePage = (o.sourcePage ?? pageStart) - pageStart
            copy.createdMs = nowMs
            copy.updatedMs = nowMs
            try insertObservationInTransaction(copy)
        }
    }

    // MARK: - Trends (§21)

    /// Every non-rejected observation of an analyte with its record (split parents skipped), newest first.
    func trendItems(analyteID: String) throws -> [RecordObservationItem] {
        var items: [RecordObservationItem] = []
        try connection.query(
            """
            SELECT \(Self.qualifiedObservationColumns), \(Self.qualifiedColumns)
            FROM observations o JOIN records ON records.id = o.record_id
            WHERE o.analyte_id=? AND o.state<>'rejected'
              AND NOT (records.archived=1 AND EXISTS (SELECT 1 FROM records c WHERE c.parent_id=records.id))
            ORDER BY o.observed_date DESC, o.created_ms DESC
            """,
            [.text(analyteID)]
        ) { s in
            items.append(RecordObservationItem(observation: Self.decodeObservation(s), record: Self.decodeRecord(s, offset: Self.observationColumnCount)))
        }
        return items
    }

    func trend(analyteID: String) throws -> RecordAnalyteTrend {
        let items = try trendItems(analyteID: analyteID)
        return RecordAnalyteTrend(analyteID: analyteID, series: RecordTrendBuilder.series(items, analyteID: analyteID, catalog: catalog), items: items)
    }

    /// `EXPLAIN QUERY PLAN` of the trend query (performance tests).
    func trendQueryPlan(analyteID: String) throws -> [String] {
        var lines: [String] = []
        try connection.query("EXPLAIN QUERY PLAN SELECT id FROM observations WHERE analyte_id=? AND state<>'rejected' ORDER BY observed_date", [.text(analyteID)]) {
            if let detail = $0.text(3) { lines.append(detail) }
        }
        return lines
    }

    // MARK: - Values search (§23)

    func recordIDs(matching conditions: [RecordAnalyteCondition]) throws -> Set<String> {
        var result: Set<String>?
        for condition in conditions {
            var ids = Set<String>()
            try connection.query(
                "SELECT \(Self.observationColumns) FROM observations WHERE analyte_id=? AND state<>'rejected'",
                [.text(condition.analyteID)]
            ) { s in
                let o = Self.decodeObservation(s)
                if RecordAnalyteQueryParser.matches(condition, observation: o, catalog: catalog) { ids.insert(o.recordID) }
            }
            result = result.map { $0.intersection(ids) } ?? ids
        }
        return result ?? []
    }

    /// Observation hits for the Values group: matching values (latest first) per condition.
    func valueHits(conditions: [RecordAnalyteCondition], archived: Bool = false, limitPerCondition: Int = 12) throws -> [RecordValueHit] {
        var hits: [RecordValueHit] = []
        var seen = Set<String>()
        for condition in conditions {
            var count = 0
            try connection.query(
                """
                SELECT \(Self.qualifiedObservationColumns), \(Self.qualifiedColumns)
                FROM observations o JOIN records ON records.id = o.record_id
                WHERE o.analyte_id=? AND o.state<>'rejected' AND records.archived=?
                ORDER BY o.observed_date DESC, o.created_ms DESC
                """,
                [.text(condition.analyteID), .int(archived ? 1 : 0)]
            ) { s in
                guard count < limitPerCondition else { return }
                let o = Self.decodeObservation(s)
                guard RecordAnalyteQueryParser.matches(condition, observation: o, catalog: catalog), seen.insert(o.id).inserted else { return }
                hits.append(RecordValueHit(observation: o, record: Self.decodeRecord(s, offset: Self.observationColumnCount)))
                count += 1
            }
        }
        return hits
    }

    // MARK: - Entities (§19)

    /// §19 `rebuild_entities`: upsert by (kind, normalized_name) keeping an existing display name and
    /// filling a NULL specialty; the record's `record_entities` rows are replaced.
    func refreshEntitiesInTransaction(recordID: String, nowMs: Int64) throws {
        let fields = try fields(recordID: recordID)
        let result = RR.rebuildEntities(fields.map(\.knowledgeRow))
        try connection.run("DELETE FROM record_entities WHERE record_id=?", [.text(recordID)])
        var ids: [String: String] = [:]
        for entity in result["entities"].array ?? [] {
            guard let kind = entity["kind"].string, let normalized = entity["normalized_name"].string else { continue }
            var entityID = try connection.scalarText("SELECT id FROM entities WHERE kind=? AND normalized_name=?", [.text(kind), .text(normalized)])
            if entityID == nil {
                let id = UUID().uuidString.lowercased()
                try connection.run(
                    "INSERT INTO entities (id, kind, display_name, normalized_name, specialty, created_ms, updated_ms) VALUES (?, ?, ?, ?, ?, ?, ?)",
                    [.text(id), .text(kind), .text(entity["display_name"].string ?? normalized), .text(normalized), .optionalText(entity["specialty"].string), .int(nowMs), .int(nowMs)]
                )
                entityID = id
            } else if let specialty = entity["specialty"].string {
                try connection.run("UPDATE entities SET specialty=?, updated_ms=? WHERE id=? AND specialty IS NULL", [.text(specialty), .int(nowMs), .text(entityID!)])
            }
            ids[kind + "|" + normalized] = entityID
        }
        for link in result["record_entities"].array ?? [] {
            guard let id = ids[(link["kind"].string ?? "") + "|" + (link["normalized_name"].string ?? "")], let role = link["role"].string else { continue }
            try connection.run("INSERT OR IGNORE INTO record_entities (record_id, entity_id, role) VALUES (?, ?, ?)", [.text(recordID), .text(id), .text(role)])
        }
        try deleteOrphanEntitiesInTransaction()
    }

    func deleteOrphanEntitiesInTransaction() throws {
        try connection.exec("DELETE FROM entities WHERE NOT EXISTS (SELECT 1 FROM record_entities re WHERE re.entity_id = entities.id)")
    }

    /// Doctor or facility entities with their (non-archived) record counts, by name.
    func entities(kind: RecordEntityKind) throws -> [RecordEntity] {
        var rows: [RecordEntity] = []
        try connection.query(
            """
            SELECT e.id, e.kind, e.display_name, e.normalized_name, e.specialty, COUNT(DISTINCT re.record_id)
            FROM entities e JOIN record_entities re ON re.entity_id = e.id JOIN records r ON r.id = re.record_id AND r.archived = 0
            WHERE e.kind=? GROUP BY e.id ORDER BY e.display_name COLLATE NOCASE
            """,
            [.text(kind.rawValue)]
        ) { s in
            rows.append(RecordEntity(id: s.text(0) ?? "", kind: kind, displayName: s.text(2) ?? "", normalizedName: s.text(3) ?? "", specialty: s.text(4), recordCount: s.int(5) ?? 0))
        }
        return rows
    }

    func entityIDs(recordID: String) throws -> [(entityID: String, role: RecordEntityRole)] {
        var rows: [(String, RecordEntityRole)] = []
        try connection.query("SELECT entity_id, role FROM record_entities WHERE record_id=? ORDER BY role, entity_id", [.text(recordID)]) { s in
            if let id = s.text(0), let role = RecordEntityRole(rawValue: s.text(1) ?? "") { rows.append((id, role)) }
        }
        return rows.map { (entityID: $0.0, role: $0.1) }
    }

    // MARK: - Links (§19)

    nonisolated static func decodeLink(_ s: HealthDBStatementReading) -> RecordLink {
        let reasons = s.text(6).flatMap { RJ.parse($0)?.array?.compactMap(\.string) } ?? []
        return RecordLink(
            aID: s.text(0) ?? "", bID: s.text(1) ?? "",
            kind: RecordLinkKind(rawValue: s.text(2) ?? "") ?? .related,
            origin: RecordLinkOrigin(rawValue: s.text(3) ?? "") ?? .suggested,
            status: RecordLinkStatus(rawValue: s.text(4) ?? "") ?? .suggested,
            score: s.double(5) ?? 0, reasons: reasons, createdMs: s.int64(7) ?? 0, updatedMs: s.int64(8) ?? 0
        )
    }

    nonisolated static let linkColumns = "l.a_id, l.b_id, l.kind, l.origin, l.status, l.score, l.reasons_json, l.created_ms, l.updated_ms"

    func link(_ x: String, _ y: String) throws -> RecordLink? {
        let (a, b) = RecordLink.orderedPair(x, y)
        var result: RecordLink?
        try connection.query("SELECT \(Self.linkColumns) FROM record_links l WHERE l.a_id=? AND l.b_id=?", [.text(a), .text(b)]) { result = Self.decodeLink($0) }
        return result
    }

    /// Links of a record except rejected suggestions, with the other record: accepted first.
    func relatedRecords(recordID: String) throws -> [RecordRelated] {
        var rows: [RecordRelated] = []
        try connection.query(
            """
            SELECT \(Self.linkColumns), \(Self.qualifiedColumns)
            FROM record_links l JOIN records ON records.id = (CASE WHEN l.a_id=? THEN l.b_id ELSE l.a_id END)
            WHERE (l.a_id=? OR l.b_id=?) AND l.status<>'rejected'
            ORDER BY (l.status='accepted') DESC, l.score DESC, records.sort_date DESC
            """,
            [.text(recordID), .text(recordID), .text(recordID)]
        ) { s in
            rows.append(RecordRelated(link: Self.decodeLink(s), record: Self.decodeRecord(s, offset: 9)))
        }
        return rows
    }

    /// A user link replaces any suggestion on the pair (`origin user`, `status accepted`).
    func setUserLink(_ x: String, _ y: String, kind: RecordLinkKind, nowMs: Int64 = RecordDates.nowMs()) throws {
        guard x != y else { return }
        let (a, b) = RecordLink.orderedPair(x, y)
        try connection.run(
            """
            INSERT INTO record_links (a_id, b_id, kind, origin, status, score, reasons_json, created_ms, updated_ms) VALUES (?, ?, ?, 'user', 'accepted', 1, NULL, ?, ?)
            ON CONFLICT(a_id, b_id) DO UPDATE SET kind=excluded.kind, origin='user', status='accepted', score=1, updated_ms=excluded.updated_ms
            """,
            [.text(a), .text(b), .text(kind.rawValue), .int(nowMs), .int(nowMs)]
        )
    }

    /// Unlink: a user row is deleted; a suggestion becomes `rejected` so it never reappears.
    func unlink(_ x: String, _ y: String, nowMs: Int64 = RecordDates.nowMs()) throws {
        let (a, b) = RecordLink.orderedPair(x, y)
        guard let row = try link(a, b) else { return }
        if row.origin == .user {
            try connection.run("DELETE FROM record_links WHERE a_id=? AND b_id=?", [.text(a), .text(b)])
        } else {
            try connection.run("UPDATE record_links SET status='rejected', updated_ms=? WHERE a_id=? AND b_id=?", [.int(nowMs), .text(a), .text(b)])
        }
    }

    func acceptSuggestion(_ x: String, _ y: String, nowMs: Int64 = RecordDates.nowMs()) throws {
        let (a, b) = RecordLink.orderedPair(x, y)
        try connection.run("UPDATE record_links SET status='accepted', updated_ms=? WHERE a_id=? AND b_id=? AND status='suggested'", [.int(nowMs), .text(a), .text(b)])
    }

    /// Stores suggestions for a record: pairs that already have a row are skipped, at most 5 open suggestions.
    @discardableResult
    func insertSuggestions(recordID: String, _ suggestions: [RecordRelationSuggestion], nowMs: Int64 = RecordDates.nowMs()) throws -> Int {
        var inserted = 0
        try connection.inTransaction {
            let open = Int(try connection.scalarInt64("SELECT COUNT(*) FROM record_links WHERE (a_id=? OR b_id=?) AND status='suggested'", [.text(recordID), .text(recordID)]) ?? 0)
            var room = max(0, RecordRelationSuggester.maxSuggestions - open)
            for suggestion in suggestions.stableSorted(by: { $0.score > $1.score }) where room > 0 {
                let (a, b) = RecordLink.orderedPair(recordID, suggestion.otherID)
                guard try link(a, b) == nil, try record(id: suggestion.otherID) != nil else { continue }
                let reasons = "[" + suggestion.reasons.map { RJ.str($0).compactJSON }.joined(separator: ",") + "]"
                try connection.run(
                    "INSERT INTO record_links (a_id, b_id, kind, origin, status, score, reasons_json, created_ms, updated_ms) VALUES (?, ?, ?, 'suggested', 'suggested', ?, ?, ?, ?)",
                    [.text(a), .text(b), .text(suggestion.kind.rawValue), .real(suggestion.score), .text(reasons), .int(nowMs), .int(nowMs)]
                )
                inserted += 1
                room -= 1
            }
        }
        return inserted
    }

    /// Split acceptance: parent ↔ child and sibling pairs, `split_from`, accepted.
    func linkSplitInTransaction(parentID: String, childIDs: [String], nowMs: Int64) throws {
        let ids = [parentID] + childIDs
        for i in 0..<ids.count {
            for j in (i + 1)..<ids.count {
                let (a, b) = RecordLink.orderedPair(ids[i], ids[j])
                try connection.run(
                    """
                    INSERT INTO record_links (a_id, b_id, kind, origin, status, score, reasons_json, created_ms, updated_ms) VALUES (?, ?, 'split_from', 'suggested', 'accepted', 1, '["split"]', ?, ?)
                    ON CONFLICT(a_id, b_id) DO NOTHING
                    """,
                    [.text(a), .text(b), .int(nowMs), .int(nowMs)]
                )
            }
        }
    }

    /// Duplicate merge: the new record's links move to the existing record.
    func moveLinksInTransaction(from oldID: String, to newID: String, nowMs: Int64) throws {
        var rows: [RecordLink] = []
        try connection.query("SELECT \(Self.linkColumns) FROM record_links l WHERE l.a_id=? OR l.b_id=?", [.text(oldID), .text(oldID)]) { rows.append(Self.decodeLink($0)) }
        for row in rows {
            let other = row.other(than: oldID)
            try connection.run("DELETE FROM record_links WHERE a_id=? AND b_id=?", [.text(row.aID), .text(row.bID)])
            guard other != newID else { continue }
            let (a, b) = RecordLink.orderedPair(newID, other)
            let reasons = "[" + row.reasons.map { RJ.str($0).compactJSON }.joined(separator: ",") + "]"
            try connection.run(
                "INSERT OR IGNORE INTO record_links (a_id, b_id, kind, origin, status, score, reasons_json, created_ms, updated_ms) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                [.text(a), .text(b), .text(row.kind.rawValue), .text(row.origin.rawValue), .text(row.status.rawValue), .real(row.score), .text(reasons), .int(row.createdMs), .int(nowMs)]
            )
        }
    }

    /// Accepted links (not `split_from`) touching any of `recordIDs`: record → linked record ids.
    func episodeNeighbours(recordIDs: [String]) throws -> [String: Set<String>] {
        guard !recordIDs.isEmpty else { return [:] }
        var result: [String: Set<String>] = [:]
        let wanted = Set(recordIDs)
        for chunk in stride(from: 0, to: recordIDs.count, by: 400).map({ Array(recordIDs[$0..<min($0 + 400, recordIDs.count)]) }) {
            let placeholders = Array(repeating: "?", count: chunk.count).joined(separator: ", ")
            try connection.query(
                "SELECT a_id, b_id FROM record_links WHERE status='accepted' AND kind<>'split_from' AND (a_id IN (\(placeholders)) OR b_id IN (\(placeholders)))",
                chunk.map { .text($0) } + chunk.map { .text($0) }
            ) { s in
                guard let a = s.text(0), let b = s.text(1) else { return }
                if wanted.contains(a) { result[a, default: []].insert(b) }
                if wanted.contains(b) { result[b, default: []].insert(a) }
            }
        }
        return result
    }

    // MARK: - Relation profiles (§22)

    /// The processed record's profile, candidates within ±180 days and the link rows touching the record.
    func relationInputs(recordID: String) throws -> (RecordRelationProfile, [RecordRelationProfile], [RecordLink])? {
        guard let record = try record(id: recordID) else { return nil }
        let day = YMD.iso(record.effectiveDate) ?? YMD(y: 2000, m: 1, d: 1)
        var candidates: [HealthRecord] = []
        try connection.query(
            "SELECT \(Self.columns) FROM records WHERE archived IN (0, 1) AND sort_date BETWEEN ? AND ? AND id<>?",
            [.text(day.days(-RecordRelationSuggester.windowDays).isoString), .text(day.days(RecordRelationSuggester.windowDays).isoString), .text(recordID)]
        ) { candidates.append(Self.decodeRecord($0)) }
        var links: [RecordLink] = []
        try connection.query("SELECT \(Self.linkColumns) FROM record_links l WHERE l.a_id=? OR l.b_id=?", [.text(recordID), .text(recordID)]) {
            links.append(Self.decodeLink($0))
        }
        let profiles = try relationProfiles(for: [record] + candidates)
        guard let own = profiles.first(where: { $0.id == recordID }) else { return nil }
        return (own, profiles.filter { $0.id != recordID }, links)
    }

    func relationProfiles(for records: [HealthRecord]) throws -> [RecordRelationProfile] {
        guard !records.isEmpty else { return [] }
        var analytes: [String: [String]] = [:]
        var doctors: [String: [String]] = [:]
        var facilities: [String: [String]] = [:]
        var fieldRows: [String: [RecordField]] = [:]
        var parents = Set<String>()
        for chunk in stride(from: 0, to: records.count, by: 300).map({ Array(records[$0..<min($0 + 300, records.count)]) }) {
            let ids = chunk.map(\.id)
            let placeholders = Array(repeating: "?", count: ids.count).joined(separator: ", ")
            let bind = ids.map { SQLValue.text($0) }
            try connection.query("SELECT DISTINCT record_id, analyte_id FROM observations WHERE analyte_id IS NOT NULL AND state<>'rejected' AND record_id IN (\(placeholders)) ORDER BY analyte_id", bind) { s in
                if let r = s.text(0), let a = s.text(1) { analytes[r, default: []].append(a) }
            }
            try connection.query("SELECT re.record_id, e.kind, e.normalized_name FROM record_entities re JOIN entities e ON e.id=re.entity_id WHERE re.record_id IN (\(placeholders)) ORDER BY e.normalized_name", bind) { s in
                guard let r = s.text(0), let name = s.text(2) else { return }
                if s.text(1) == RecordEntityKind.facility.rawValue {
                    if !(facilities[r] ?? []).contains(name) { facilities[r, default: []].append(name) }
                } else if !(doctors[r] ?? []).contains(name) {
                    doctors[r, default: []].append(name)
                }
            }
            try connection.query(
                "SELECT \(Self.fieldColumns) FROM record_fields WHERE field_key IN ('report_name', 'follow_up_date', 'test_result') AND record_id IN (\(placeholders)) ORDER BY rowid",
                bind
            ) { s in
                if let field = Self.decodeField(s) { fieldRows[field.recordID, default: []].append(field) }
            }
            try connection.query("SELECT DISTINCT parent_id FROM records WHERE parent_id IN (\(placeholders))", bind) { s in
                if let p = s.text(0) { parents.insert(p) }
            }
        }
        return records.map { record in
            let rows = (fieldRows[record.id] ?? []).map(\.knowledgeRow)
            return RecordRelationProfile(
                id: record.id, recordType: record.recordType, sortDate: record.effectiveDate, archived: record.archived,
                splitParent: parents.contains(record.id), reportName: RR.bestRow(rows, "report_name")?["value_text"].string,
                panels: RR.recordPanels(rows, catalog), analytes: analytes[record.id] ?? [], doctors: doctors[record.id] ?? [],
                facilities: facilities[record.id] ?? [],
                followUpDates: (fieldRows[record.id] ?? []).filter { $0.key == .followUpDate && $0.state != .rejected }.map(\.valueText)
            )
        }
    }

    // MARK: - Backfill (v2 → v3)

    /// One-time: finished v2 jobs re-enter the pipeline at `observations` behind fresh imports.
    @discardableResult
    func backfillKnowledgeJobsIfNeeded(nowMs: Int64 = RecordDates.nowMs()) throws -> Int {
        guard try metaValue("knowledge_backfill_v3") == nil else { return 0 }
        var count = 0
        try connection.inTransaction {
            count = Int(try connection.scalarInt64("SELECT COUNT(*) FROM processing_jobs WHERE stage='done'") ?? 0)
            try connection.run("UPDATE processing_jobs SET stage='observations', attempts=0, next_attempt_ms=1, updated_ms=? WHERE stage='done'", [.int(nowMs)])
            try connection.run("INSERT OR REPLACE INTO records_meta (key, value) VALUES ('knowledge_backfill_v3', '1')")
        }
        return count
    }
}
