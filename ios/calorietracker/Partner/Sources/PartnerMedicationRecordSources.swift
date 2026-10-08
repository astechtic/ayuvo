import Foundation

// Medicines (docs/partner-sync.md §7.5) and report overviews (§7.6). Both read their database through a READ-ONLY
// connection and select named columns only: never photo_path / related_record_id, never record files, pages, OCR
// text, evidence, bounding boxes, source pages or notes.

// MARK: - medication

nonisolated struct PartnerMedicationSource: PartnerRecordSource {
    let type = "medication"
    let reader: PartnerSQLiteReader

    private static let columns = "id, name, generic_name, brand_name, strength, form, dose_quantity, dose_unit, food_relation, instructions, start_date, end_date, status, is_prn, updated_ms"

    private static func map(_ s: HealthDBStatement) -> PartnerSourceRecord? {
        let row: RJ = .obj([
            "id": s.rjText(0), "name": s.rjText(1), "generic_name": s.rjText(2), "brand_name": s.rjText(3), "strength": s.rjText(4),
            "form": s.rjText(5), "dose_quantity": s.rjNumber(6), "dose_unit": s.rjText(7), "food_relation": s.rjText(8),
            "instructions": s.rjText(9), "start_date": s.rjText(10), "end_date": s.rjText(11), "status": s.rjText(12),
            "is_prn": s.rjInt(13),
        ])
        return PartnerSourceRecord(mapped: PartnerRef.mapMedication(row), updatedMs: s.int64(14))
    }

    func scan(scope: PartnerSourceScope, pageSize: Int, onPage: ([PartnerSourceRecord]) async throws -> Void) async throws {
        try await PartnerKeysetPager.run(reader: reader, type: type, pageSize: pageSize, query: { after in
            let (clause, keys) = PartnerKeysetPager.after(["id"], after)
            return ("SELECT \(Self.columns) FROM medications WHERE 1=1\(clause) ORDER BY id LIMIT ?", keys)
        }, keyColumns: [0], map: Self.map, onPage: onPage)
    }

    func record(id: String) async throws -> PartnerSourceRecord? {
        try await reader.read { c -> PartnerSourceRecord? in
            var out: PartnerSourceRecord?
            try c.query("SELECT \(Self.columns) FROM medications WHERE id=?", [.text(id)]) { out = Self.map($0) }
            return out
        } ?? nil
    }
}

// MARK: - medication_schedule

nonisolated struct PartnerScheduleSource: PartnerRecordSource {
    let type = "medication_schedule"
    let reader: PartnerSQLiteReader

    private static let columns = "id, medication_id, frequency_kind, times_json, days_json, interval_hours, anchor_time, active_from_ms, active_until_ms, updated_ms"

    private static func map(_ s: HealthDBStatement) -> PartnerSourceRecord? {
        let row: RJ = .obj([
            "id": s.rjText(0), "medication_id": s.rjText(1), "frequency_kind": s.rjText(2), "times_json": s.rjText(3),
            "days_json": s.rjText(4), "interval_hours": s.rjInt(5), "anchor_time": s.rjText(6), "active_from_ms": s.rjInt(7),
            "active_until_ms": s.rjInt(8),
        ])
        return PartnerSourceRecord(mapped: PartnerRef.mapSchedule(row), updatedMs: s.int64(9))
    }

    func scan(scope: PartnerSourceScope, pageSize: Int, onPage: ([PartnerSourceRecord]) async throws -> Void) async throws {
        try await PartnerKeysetPager.run(reader: reader, type: type, pageSize: pageSize, query: { after in
            let (clause, keys) = PartnerKeysetPager.after(["id"], after)
            return ("SELECT \(Self.columns) FROM medication_schedules WHERE 1=1\(clause) ORDER BY id LIMIT ?", keys)
        }, keyColumns: [0], map: Self.map, onPage: onPage)
    }

    func record(id: String) async throws -> PartnerSourceRecord? {
        try await reader.read { c -> PartnerSourceRecord? in
            var out: PartnerSourceRecord?
            try c.query("SELECT \(Self.columns) FROM medication_schedules WHERE id=?", [.text(id)]) { out = Self.map($0) }
            return out
        } ?? nil
    }
}

// MARK: - dose_log

nonisolated struct PartnerDoseLogSource: PartnerRecordSource {
    let type = "dose_log"
    let reader: PartnerSQLiteReader
    var timeZone: TimeZone = .current

    private static let columns = "id, medication_id, schedule_id, scheduled_at_ms, status, taken_at_ms, dose_quantity, dose_unit, note, updated_ms"

    private func map(_ s: HealthDBStatement) -> PartnerSourceRecord? {
        let row: RJ = .obj([
            "id": s.rjText(0), "medication_id": s.rjText(1), "schedule_id": s.rjText(2), "scheduled_at_ms": s.rjInt(3),
            "status": s.rjText(4), "taken_at_ms": s.rjInt(5), "dose_quantity": s.rjNumber(6), "dose_unit": s.rjText(7),
            "note": s.rjText(8),
        ])
        let day = PartnerDay.string(ms: s.int64(3) ?? 0, timeZone: timeZone)
        return PartnerSourceRecord(mapped: PartnerRef.mapDoseLog(row, localDay: .str(day)), updatedMs: s.int64(9))
    }

    func scan(scope: PartnerSourceScope, pageSize: Int, onPage: ([PartnerSourceRecord]) async throws -> Void) async throws {
        let selfCopy = self
        try await PartnerKeysetPager.run(reader: reader, type: type, pageSize: pageSize, query: { after in
            let (clause, keys) = PartnerKeysetPager.after(["id"], after)
            return ("SELECT \(Self.columns) FROM dose_logs WHERE 1=1\(clause) ORDER BY id LIMIT ?", keys)
        }, keyColumns: [0], map: { selfCopy.map($0) }, onPage: onPage)
    }

    func record(id: String) async throws -> PartnerSourceRecord? {
        let selfCopy = self
        return try await reader.read { c -> PartnerSourceRecord? in
            var out: PartnerSourceRecord?
            try c.query("SELECT \(Self.columns) FROM dose_logs WHERE id=?", [.text(id)]) { out = selfCopy.map($0) }
            return out
        } ?? nil
    }
}

// MARK: - report_overview

/// Frame fit (docs/partner-sync.md §7.6): every envelope must fit one Noise frame on the network. An oversized
/// `report_overview` drops `results` entries from the end — never `abnormal`, `summary` or `highlights` — until it
/// fits; it is trimmed at the source so the ledger hash, CHANGES pages and packages all carry the same overview.
/// Only other records that still do not fit are skipped on the network (`oversize_skipped`).
nonisolated enum PartnerFrameFit {
    /// Room for one envelope (plus its rev / deleted / updated_ms wire fields) inside a one-frame CHANGES page.
    static var maxEnvelopeBytes: Int { PartnerFraming.maxJSONMessage - 512 }

    static func fitReportOverview(_ env: RJ?, maxBytes: Int = maxEnvelopeBytes) -> RJ? {
        guard let env, case .obj(var top) = env, case .obj(var data)? = top["data"], case .arr(let results)? = data["results"],
              PartnerJSON.canonicalData(env).count > maxBytes else { return env }
        func render(_ keep: Int) -> RJ {
            var d = data
            d["results"] = keep > 0 ? .arr(Array(results.prefix(keep))) : nil
            var t = top
            t["data"] = .obj(d)
            return .obj(t)
        }
        func fits(_ keep: Int) -> Bool { PartnerJSON.canonicalData(render(keep)).count <= maxBytes }
        // Size grows with the kept prefix, so the largest fitting prefix is found by binary search.
        var lo = 0
        var hi = results.count - 1
        if !fits(0) { hi = 0 }
        while lo < hi {
            let mid = (lo + hi + 1) / 2
            if fits(mid) { lo = mid } else { hi = mid - 1 }
        }
        data["results"] = lo > 0 ? .arr(Array(results.prefix(lo))) : nil
        top["data"] = .obj(data)
        return .obj(top)
    }
}

/// A `records` row plus its structured children. Archived records are not shared (the mapper returns nil).
nonisolated struct PartnerReportOverviewSource: PartnerRecordSource {
    let type = "report_overview"
    let reader: PartnerSQLiteReader

    /// Named columns only — never file_path, thumbnail_path, original_filename, checksum, notes or page text.
    private static let recordColumns = "seq, id, title, record_type, category, document_date, sort_date, archived, updated_ms"

    private static func overview(_ c: HealthDBConnection, seq: Int64, recordRow: RJ, updatedMs: Int64?,
                                 hasFields: Bool, hasHighlights: Bool, hasObservations: Bool) throws -> PartnerSourceRecord? {
        guard let id = recordRow["id"].string else { return nil }
        var fields: [RJ] = []
        if hasFields {
            try c.query("SELECT id, field_key, value_text, value_json, confidence, state FROM record_fields WHERE record_id=?", [.text(id)]) { s in
                fields.append(.obj(["id": s.rjText(0), "field_key": s.rjText(1), "value_text": s.rjText(2), "value_json": s.rjText(3),
                                    "confidence": s.rjNumber(4), "state": s.rjText(5)]))
            }
        }
        var highlights: [RJ] = []
        if hasHighlights {
            try c.query("SELECT id, section, text, dismissed, position FROM record_highlights WHERE record_id=?", [.text(id)]) { s in
                highlights.append(.obj(["id": s.rjText(0), "section": s.rjText(1), "text": s.rjText(2), "dismissed": s.rjInt(3),
                                        "position": s.rjInt(4)]))
            }
        }
        var observations: [RJ] = []
        if hasObservations {
            try c.query("""
                SELECT id, raw_name, analyte_id, value_text, value_num, unit, ref_low, ref_high, ref_text, flag, observed_date, state
                FROM observations WHERE record_id=?
                """, [.text(id)]) { s in
                observations.append(.obj([
                    "id": s.rjText(0), "raw_name": s.rjText(1), "analyte_id": s.rjText(2), "value_text": s.rjText(3),
                    "value_num": s.rjNumber(4), "unit": s.rjText(5), "ref_low": s.rjNumber(6), "ref_high": s.rjNumber(7),
                    "ref_text": s.rjText(8), "flag": s.rjText(9), "observed_date": s.rjText(10), "state": s.rjText(11),
                ]))
            }
        }
        let env = PartnerRef.mapReportOverview(record: recordRow, fields: fields, highlights: highlights, observations: observations)
        return PartnerSourceRecord(mapped: PartnerFrameFit.fitReportOverview(env), updatedMs: updatedMs)
    }

    private static func recordRow(_ s: HealthDBStatement) -> RJ {
        .obj(["id": s.rjText(1), "title": s.rjText(2), "record_type": s.rjText(3), "category": s.rjText(4),
              "document_date": s.rjText(5), "sort_date": s.rjText(6), "archived": s.rjInt(7)])
    }

    private func tables() async throws -> (Bool, Bool, Bool) {
        (try await reader.hasTable("record_fields"), try await reader.hasTable("record_highlights"), try await reader.hasTable("observations"))
    }

    func scan(scope: PartnerSourceScope, pageSize: Int, onPage: ([PartnerSourceRecord]) async throws -> Void) async throws {
        let (f, h, o): (Bool, Bool, Bool)
        do { (f, h, o) = try await tables() } catch { throw PartnerSourceUnavailable(type: type, reason: String(describing: error)) }
        var afterSeq: Int64 = Int64.min
        while true {
            let page: (records: [PartnerSourceRecord], last: Int64?, rows: Int)
            do {
                page = try await reader.read { c -> ([PartnerSourceRecord], Int64?, Int) in
                    var heads: [(Int64, RJ, Int64?)] = []
                    try c.query("SELECT \(Self.recordColumns) FROM records WHERE archived=0 AND seq > ? ORDER BY seq LIMIT ?",
                                [.int(afterSeq), .int(Int64(pageSize))]) { s in
                        heads.append((s.int64(0) ?? 0, Self.recordRow(s), s.int64(8)))
                    }
                    var out: [PartnerSourceRecord] = []
                    for (seq, row, updated) in heads {
                        if let r = try Self.overview(c, seq: seq, recordRow: row, updatedMs: updated, hasFields: f, hasHighlights: h, hasObservations: o) {
                            out.append(r)
                        }
                    }
                    return (out, heads.last?.0, heads.count)
                } ?? ([], nil, 0)
            } catch {
                throw PartnerSourceUnavailable(type: type, reason: String(describing: error))
            }
            if !page.records.isEmpty { try await onPage(page.records) }
            guard page.rows >= pageSize, let last = page.last else { return }
            afterSeq = last
        }
    }

    func record(id: String) async throws -> PartnerSourceRecord? {
        let (f, h, o) = try await tables()
        return try await reader.read { c -> PartnerSourceRecord? in
            var head: (Int64, RJ, Int64?)?
            try c.query("SELECT \(Self.recordColumns) FROM records WHERE id=?", [.text(id)]) { s in
                head = (s.int64(0) ?? 0, Self.recordRow(s), s.int64(8))
            }
            guard let head else { return nil }
            return try Self.overview(c, seq: head.0, recordRow: head.1, updatedMs: head.2, hasFields: f, hasHighlights: h, hasObservations: o)
        } ?? nil
    }
}
