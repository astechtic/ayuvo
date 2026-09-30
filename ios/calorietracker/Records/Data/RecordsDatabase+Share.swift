import Foundation

// Phase 5 reads/writes for sharing and the backup bookkeeping (docs/health-records.md §33–§35).
extension RecordsDatabase {
    /// The store snapshot the §34 summary needs for `recordIDs`, in plan order.
    func shareSnapshot(recordIDs: [String], includePages: Bool = false) throws -> RJ {
        // `snapshotRecordRow` carries what the coach needs; sharing also reads the file columns.
        let records: [RJ] = try snapshotRecords(ids: recordIDs).map { row in
            guard case .obj(var object) = row, let id = object["id"]?.string else { return row }
            try connection.query("SELECT file_path, file_type, page_count FROM records WHERE id=?", [.text(id)]) { s in
                object["file_path"] = .string(s.text(0))
                object["file_type"] = .string(s.text(1))
                object["page_count"] = .int(s.int(2) ?? 0)
            }
            return .obj(object)
        }
        var object: [String: RJ] = [
            "records": .arr(records),
            "fields": .arr(try snapshotFields(recordIDs: recordIDs)),
            "observations": .arr(try snapshotObservations(recordIDs: recordIDs)),
            "highlights": .arr(try snapshotHighlights(recordIDs: recordIDs)),
        ]
        if includePages {
            object["pages"] = .arr(try recordIDs.flatMap { try redactionPages(recordID: $0) })
        }
        return .obj(object)
    }

    /// `record_pages` rows the redactor needs (`record_id`, `page_index`, `blocks_json`).
    func redactionPages(recordID: String) throws -> [RJ] {
        var rows: [RJ] = []
        try connection.query(
            "SELECT record_id, page_index, blocks_json FROM record_pages WHERE record_id=? ORDER BY page_index",
            [.text(recordID)]
        ) { s in
            rows.append(.obj(["record_id": .string(s.text(0)), "page_index": .int(s.int(1) ?? 0), "blocks_json": .string(s.text(2))]))
        }
        return rows
    }

    nonisolated static func sharePlanJSON(_ plan: RecordSharePlan, pageCounts: [String: Int] = [:]) -> RJ {
        var pages: [String: RJ] = [:]
        for id in plan.recordIDs {
            switch plan.pages(for: id) {
            case .all: pages[id] = .str("all")
            case .pages(let list): pages[id] = .arr(list.map(RJ.int))
            }
        }
        return .obj([
            "record_ids": .arr(plan.recordIDs.map(RJ.str)),
            "pages": .obj(pages),
            "include_original": .bool(plan.includeOriginal),
            "include_summary": .bool(plan.includeSummary),
            "summary_fields": .arr(plan.summaryFieldValues.map(RJ.str)),
            "include_highlights": .bool(plan.includeHighlights),
            "include_notes": .bool(plan.includeNotes),
            "redactions": .arr(plan.redactionValues.map(RJ.str)),
        ])
    }

    /// Deterministic structured summary of a plan (§34); empty when nothing is selected.
    func shareSummaryText(plan: RecordSharePlan) throws -> String {
        let snapshot = try shareSnapshot(recordIDs: plan.recordIDs)
        let result = RR.shareSummary(snapshot, plan: Self.sharePlanJSON(plan), typeLabels: RecordsShareText.typeLabels,
                                     labels: RecordsShareText.localized)
        return result["text"].string ?? ""
    }

    /// §34 redaction targets of one record (already inflated, clamped and rounded).
    func redactionTargets(recordID: String, classes: [String]) throws -> RJ {
        let snapshot = try shareSnapshot(recordIDs: [recordID], includePages: true)
        return RR.redactionTargets(snapshot, recordID: recordID, classes: classes)
    }

    /// §34 warning texts of a plan, in reference order.
    func sharePlanWarnings(plan: RecordSharePlan) throws -> [String] {
        let snapshot = try shareSnapshot(recordIDs: plan.recordIDs, includePages: true)
        let result = RR.sharePlanWarnings(snapshot, plan: Self.sharePlanJSON(plan))
        var titles: [String: String] = [:]
        for record in snapshot["records"].array ?? [] {
            guard let id = record["id"].string else { continue }
            titles[id] = (record["title"].string ?? "").split(whereSeparator: \.isWhitespace).joined(separator: " ")
        }
        // `text_layer_lost` is the Android note: PDFKit's page copy keeps the text layer (§34),
        // so iOS never shows it. Every other warning applies to both platforms.
        return (result["warnings"].array ?? [])
            .filter { $0["code"].string != "text_layer_lost" }
            .compactMap { RecordsShareText.warning($0, titles: titles) }
    }

    /// §34: one successful share bumps `shared_count` and stamps `last_shared_ms`.
    func markShared(ids: [String], nowMs: Int64 = RecordDates.nowMs()) throws {
        guard !ids.isEmpty else { return }
        try connection.inTransaction {
            for id in ids {
                try connection.run(
                    "UPDATE records SET shared_count = shared_count + 1, last_shared_ms = ? WHERE id = ?",
                    [.int(nowMs), .text(id)]
                )
            }
        }
    }

    // MARK: - records_backup_state (§33)

    func backupState() throws -> [String: String] {
        var state: [String: String] = [:]
        try connection.query("SELECT key, value FROM records_backup_state") { s in
            if let key = s.text(0), let value = s.text(1) { state[key] = value }
        }
        return state
    }

    func setBackupState(_ values: [String: String]) throws {
        guard !values.isEmpty else { return }
        try connection.inTransaction {
            for key in values.keys.sorted() {
                try connection.run(
                    "INSERT OR REPLACE INTO records_backup_state (key, value) VALUES (?, ?)",
                    [.text(key), .text(values[key] ?? "")]
                )
            }
        }
    }
}

/// Keys of `records_backup_state` (§33). Android additionally uses the `drive_*` keys.
nonisolated enum RecordsBackupStateKey {
    static let lastArchiveMs = "last_archive_ms"
    static let lastArchiveSize = "last_archive_size"
    static let lastArchiveRecords = "last_archive_records"
    static let lastRestoreMs = "last_restore_ms"
}

/// Localized text of the §34 share summary and warnings (the reference port keeps the English the vectors pin).
nonisolated enum RecordsShareText {
    /// Record type names in the user's language.
    static var typeLabels: [String: String] {
        Dictionary(uniqueKeysWithValues: RecordType.allCases.map { ($0.rawValue, $0.title) })
    }

    static var localized: RecordsShareLabels {
        RecordsShareLabels(
            dateLabels: [
                "collection_date": String(localized: "Collected", comment: "Shared record summary: date label"),
                "report_date": String(localized: "Reported", comment: "Shared record summary: date label"),
                "visit_date": String(localized: "Visit", comment: "Shared record summary: date label"),
                "prescription_date": String(localized: "Prescribed", comment: "Shared record summary: date label"),
                "admission_date": String(localized: "Admitted", comment: "Shared record summary: date label"),
                "discharge_date": String(localized: "Discharged", comment: "Shared record summary: date label"),
                "follow_up_date": String(localized: "Follow-up", comment: "Shared record summary: date label"),
            ],
            flagWords: [
                "low": String(localized: "low", comment: "Shared record summary: lab result flag"),
                "high": String(localized: "high", comment: "Shared record summary: lab result flag"),
                "critical_low": String(localized: "critical low", comment: "Shared record summary: lab result flag"),
                "critical_high": String(localized: "critical high", comment: "Shared record summary: lab result flag"),
                "abnormal": String(localized: "abnormal", comment: "Shared record summary: lab result flag"),
            ],
            ref: String(localized: "ref", comment: "Shared record summary: abbreviation before a lab reference range"),
            doctor: String(localized: "Doctor", comment: "Shared record summary: line label"),
            patient: String(localized: "Patient", comment: "Shared record summary: line label"),
            dates: String(localized: "Dates", comment: "Shared record summary: line label"),
            results: String(localized: "Results", comment: "Shared record summary: section label"),
            medications: String(localized: "Medications", comment: "Shared record summary: section label"),
            diagnoses: String(localized: "Diagnoses", comment: "Shared record summary: section label"),
            recommendations: String(localized: "Recommendations", comment: "Shared record summary: section label"),
            notesHeader: String(localized: "Notes:", comment: "Shared record summary: section header"),
            highlightsHeader: String(localized: "AI highlights (verify against the original report):", comment: "Shared record summary: section header"),
            footer: String(localized: "Shared from Ayuvo. Values were read from the original document and may contain mistakes.", comment: "Shared record summary: footer")
        )
    }

    /// A §34 plan warning in the user's language, from its code; the reference English for unknown codes.
    static func warning(_ w: RJ, titles: [String: String]) -> String? {
        let title = w["record_id"].string.flatMap { titles[$0] } ?? ""
        let page: String
        if case .int(let index) = w["page_index"] { page = (index + 1).formatted() } else { page = w["page_index"].compactJSON }
        switch w["code"].string {
        case "unknown_record": return String(localized: "A selected record is no longer available and was left out", comment: "Share warning")
        case "no_original": return String(localized: "The original file of \(title) is missing and was left out", comment: "Share warning; record title")
        case "page_missing": return String(localized: "Page \(page) of \(title) doesn't exist and was left out", comment: "Share warning; page number, record title")
        case "page_not_redactable": return String(localized: "Page \(page) can't be redacted and was left out", comment: "Share warning; page number")
        case "record_not_redactable": return String(localized: "No page of \(title) can be redacted, so it was left out", comment: "Share warning; record title")
        case "text_layer_lost": return String(localized: "Selected pages are shared as images", comment: "Share warning")
        case "redacted_text_layer_lost": return String(localized: "Redacted pages are shared as images", comment: "Share warning")
        case "nothing_to_share": return String(localized: "Nothing is selected to share", comment: "Share warning")
        default: return w["text"].string
        }
    }
}
