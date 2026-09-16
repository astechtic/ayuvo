import Foundation

/// One `*.ndjson` table of the `ayuvo-records` archive (docs/health-records.md §35): the entry
/// name, the SQLite table, the columns in schema order (`RR.archiveEntryColumns`) and the export
/// order the reference defines.
nonisolated struct RecordsArchiveTable: Sendable {
    let entry: String
    let table: String
    let columns: [String]
    let order: String

    var columnList: String { columns.joined(separator: ", ") }
}

extension RecordsDatabase {
    /// §35 entry order for the eight streamed tables; `tags.json` and `analyte_user_aliases.json`
    /// are JSON arrays built in memory (they are small).
    nonisolated static var archiveTables: [RecordsArchiveTable] {
        [
            RecordsArchiveTable(entry: "records.ndjson", table: "records", columns: RR.archiveRecordColumns, order: "sort_date, created_ms, id"),
            RecordsArchiveTable(entry: "pages.ndjson", table: "record_pages", columns: RR.archivePageColumns, order: "record_id, page_index"),
            RecordsArchiveTable(entry: "fields.ndjson", table: "record_fields", columns: RR.archiveFieldColumns, order: "record_id, created_ms, id"),
            RecordsArchiveTable(entry: "observations.ndjson", table: "observations", columns: RR.archiveObservationColumns, order: "record_id, created_ms, id"),
            RecordsArchiveTable(entry: "highlights.ndjson", table: "record_highlights", columns: RR.archiveHighlightColumns, order: "record_id, section, position, id"),
            RecordsArchiveTable(entry: "links.ndjson", table: "record_links", columns: RR.archiveLinkColumns, order: "a_id, b_id"),
            RecordsArchiveTable(entry: "entities.ndjson", table: "entities", columns: RR.archiveEntityColumns, order: "kind, normalized_name, id"),
            RecordsArchiveTable(entry: "record_entities.ndjson", table: "record_entities", columns: RR.archiveRecordEntityColumns, order: "record_id, entity_id, role"),
        ]
    }

    nonisolated static let archiveLineCapBytes = RR.archiveLineCap

    nonisolated static func archiveTable(entry: String) -> RecordsArchiveTable? {
        archiveTables.first { $0.entry == entry }
    }

    // MARK: - Export

    /// One `files/` member the archive carries: its zip entry name and the on-disk relative path.
    nonisolated struct ArchiveFileRef: Sendable, Hashable {
        var recordID: String
        var relativePath: String
        var entry: String
    }

    nonisolated struct ArchiveStagedEntry: Sendable, Hashable {
        var entry: String
        var url: URL
    }

    nonisolated struct ArchiveExportPlan: Sendable {
        var staged: [ArchiveStagedEntry] = []
        var tagsJSON: Data = Data()
        var aliasesJSON: Data = Data()
        var files: [ArchiveFileRef] = []
        var recordCount = 0
    }

    /// Streams every data entry into `directory` and returns the plan the zip writer follows.
    func stageArchiveEntries(into directory: URL) throws -> ArchiveExportPlan {
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        var plan = ArchiveExportPlan()
        for table in Self.archiveTables {
            let url = directory.appendingPathComponent(table.entry)
            FileManager.default.createFile(atPath: url.path, contents: nil)
            guard let handle = try? FileHandle(forWritingTo: url) else { continue }
            defer { try? handle.close() }
            var buffer = Data()
            let isPages = table.entry == "pages.ndjson"
            try connection.query("SELECT \(table.columnList) FROM \(table.table) ORDER BY \(table.order)") { statement in
                var row = RR.archiveRow(Self.archiveRowJSON(statement, table.columns), table.columns)
                if isPages { row = RR.truncatePageRow(row).row }
                buffer.append(Data(RR.archiveCompactJSON(row).utf8))
                buffer.append(0x0A)
                if buffer.count >= 512 * 1024 {
                    try handle.write(contentsOf: buffer)
                    buffer.removeAll(keepingCapacity: true)
                }
            }
            if !buffer.isEmpty { try handle.write(contentsOf: buffer) }
            try handle.synchronize()
            plan.staged.append(ArchiveStagedEntry(entry: table.entry, url: url))
        }

        // tags.json / analyte_user_aliases.json through the reference ordering.
        var snapshot: [String: RJ] = [:]
        var records: [RJ] = []
        try connection.query("SELECT id, sort_date, created_ms, file_path, thumbnail_path FROM records") { s in
            records.append(.obj([
                "id": .string(s.text(0)), "sort_date": .string(s.text(1)), "created_ms": .number(s.double(2)),
                "file_path": .string(s.text(3)), "thumbnail_path": .string(s.text(4)),
            ]))
        }
        snapshot["records"] = .arr(records)
        var tags: [RJ] = []
        try connection.query("SELECT id, name FROM tags") { s in
            tags.append(.obj(["id": .string(s.text(0)), "name": .string(s.text(1))]))
        }
        snapshot["tags"] = .arr(tags)
        var recordTags: [RJ] = []
        try connection.query("SELECT record_id, tag_id FROM record_tags") { s in
            recordTags.append(.obj(["record_id": .string(s.text(0)), "tag_id": .string(s.text(1))]))
        }
        snapshot["record_tags"] = .arr(recordTags)
        var aliases: [RJ] = []
        try connection.query("SELECT normalized_name, analyte_id, created_ms FROM analyte_user_aliases") { s in
            aliases.append(.obj([
                "normalized_name": .string(s.text(0)), "analyte_id": .string(s.text(1)), "created_ms": .number(s.double(2)),
            ]))
        }
        snapshot["analyte_user_aliases"] = .arr(aliases)

        let computed = RR.archiveRows(.obj(snapshot))
        for entry in computed where entry.name == "tags.json" || entry.name == "analyte_user_aliases.json" {
            let text = RR.archiveEntryText(name: entry.name, rows: entry.rows)
            if entry.name == "tags.json" { plan.tagsJSON = Data(text.utf8) } else { plan.aliasesJSON = Data(text.utf8) }
        }

        for record in RR.archiveRecordOrder(.obj(snapshot)) {
            guard let id = record["id"].string else { continue }
            plan.recordCount += 1
            for key in ["file_path", "thumbnail_path"] {
                guard let path = record[key].string, !path.isEmpty else { continue }
                let name = (path as NSString).lastPathComponent
                plan.files.append(ArchiveFileRef(recordID: id, relativePath: path, entry: "files/\(id)/\(name)"))
            }
        }
        return plan
    }

    /// The SQLite row as a JSON object keyed by column name (NULLs become `.null`).
    /// `value_json` and `source_bbox` are stored as text but travel as JSON values (§35);
    /// `blocks_json` and `reasons_json` stay strings.
    nonisolated static func archiveRowJSON(_ statement: HealthDBStatement, _ columns: [String]) -> RJ {
        var object: [String: RJ] = [:]
        for (index, column) in columns.enumerated() {
            var value = archiveValue(statement, Int32(index))
            if RR.archiveParsedJSONColumns.contains(column), let text = value.string, let parsed = RJ.parse(text) {
                value = parsed
            }
            object[column] = value
        }
        return .obj(object)
    }

    nonisolated static func archiveValue(_ statement: HealthDBStatement, _ index: Int32) -> RJ {
        if statement.columnTypeIsInteger(index) { return statement.int64(index).map { RJ.int(Int($0)) } ?? .null }
        if statement.columnTypeIsText(index) { return statement.text(index).map(RJ.str) ?? .null }
        if statement.isNull(index) { return .null }
        return statement.double(index).map(RJ.num) ?? .null
    }

    // MARK: - Import

    nonisolated static let archiveInsertableTables: Set<String> = Set(archiveTables.map(\.table))

    /// Replace mode (§35): every record, derived row and file goes first.
    func wipeForArchiveReplace() throws {
        try wipeAllData()
    }

    func existingRecordKeys() throws -> (ids: Set<String>, checksums: Set<String>) {
        var ids = Set<String>()
        var checksums = Set<String>()
        try connection.query("SELECT id, checksum_sha256 FROM records") { s in
            if let id = s.text(0) { ids.insert(id) }
            if let checksum = s.text(1), !checksum.isEmpty { checksums.insert(checksum) }
        }
        return (ids, checksums)
    }

    /// Inserts archive rows into `table`. Rows missing a required column are dropped (§35 reader
    /// validation); unknown columns are ignored; existing primary keys are kept.
    @discardableResult
    func insertArchiveRows(table: RecordsArchiveTable, rows: [RJ]) throws -> Int {
        guard !rows.isEmpty, Self.archiveInsertableTables.contains(table.table) else { return 0 }
        let required = RR.archiveRequiredColumns[table.entry] ?? []
        var inserted = 0
        try connection.inTransaction {
            for row in rows {
                guard let object = row.object, required.allSatisfy({ !(object[$0] ?? .null).isNull }) else { continue }
                var columns: [String] = []
                var values: [SQLValue] = []
                for column in table.columns {
                    guard let value = object[column], !value.isNull else { continue }
                    columns.append(column)
                    values.append(Self.sqlValue(value))
                }
                guard !columns.isEmpty else { continue }
                let placeholders = Array(repeating: "?", count: columns.count).joined(separator: ", ")
                try connection.run(
                    "INSERT OR IGNORE INTO \(table.table) (\(columns.joined(separator: ", "))) VALUES (\(placeholders))",
                    values
                )
                inserted += connection.changes
            }
        }
        return inserted
    }

    nonisolated static func sqlValue(_ value: RJ) -> SQLValue {
        switch value {
        case .null: .null
        case .bool(let b): .int(b ? 1 : 0)
        case .int(let i): .int(Int64(i))
        case .num(let d): .real(d)
        case .str(let s): .text(s)
        case .arr, .obj: .text(value.compactJSON)
        }
    }

    /// `entities.ndjson` → the live entity ids, keyed by the archive's id. An entity whose
    /// `(kind, normalized_name)` already exists keeps the local row (§19 upsert key).
    func importArchiveEntities(_ rows: [RJ]) throws -> [String: String] {
        var map: [String: String] = [:]
        guard !rows.isEmpty else { return map }
        try connection.inTransaction {
            for row in rows {
                guard let id = row["id"].string, let kind = row["kind"].string else { continue }
                let normalized = row["normalized_name"].string ?? ""
                if let existing = try connection.scalarText(
                    "SELECT id FROM entities WHERE kind=? AND normalized_name=?", [.text(kind), .text(normalized)]
                ) {
                    map[id] = existing
                    if let specialty = row["specialty"].string, !specialty.isEmpty {
                        try connection.run("UPDATE entities SET specialty=? WHERE id=? AND (specialty IS NULL OR specialty='')",
                                           [.text(specialty), .text(existing)])
                    }
                    continue
                }
                let now = RecordDates.nowMs()
                try connection.run(
                    "INSERT OR IGNORE INTO entities (id, kind, display_name, normalized_name, specialty, created_ms, updated_ms) VALUES (?, ?, ?, ?, ?, ?, ?)",
                    [.text(id), .text(kind), .text(row["display_name"].string ?? ""), .text(normalized),
                     .optionalText(row["specialty"].string),
                     .int(Int64(row["created_ms"].double ?? Double(now))), .int(Int64(row["updated_ms"].double ?? Double(now)))]
                )
                map[id] = id
            }
        }
        return map
    }

    /// `record_entities.ndjson`, with entity ids remapped onto the live rows.
    func importArchiveRecordEntities(_ rows: [RJ], entityMap: [String: String]) throws {
        guard !rows.isEmpty else { return }
        try connection.inTransaction {
            for row in rows {
                guard let recordID = row["record_id"].string, let role = row["role"].string,
                      let archiveEntity = row["entity_id"].string, let entityID = entityMap[archiveEntity] else { continue }
                try connection.run(
                    "INSERT OR IGNORE INTO record_entities (record_id, entity_id, role) VALUES (?, ?, ?)",
                    [.text(recordID), .text(entityID), .text(role)]
                )
            }
        }
    }

    /// `tags.json`: `[{id, name, record_ids}]`. A tag whose name already exists keeps the local row.
    func importArchiveTags(_ rows: [RJ], keptRecordIDs: Set<String>) throws {
        guard !rows.isEmpty else { return }
        try connection.inTransaction {
            for row in rows {
                guard let id = row["id"].string, let rawName = row["name"].string else { continue }
                let name = rawName.trimmingCharacters(in: .whitespacesAndNewlines)
                guard !name.isEmpty else { continue }
                var tagID = try connection.scalarText("SELECT id FROM tags WHERE name=? COLLATE NOCASE", [.text(name)])
                if tagID == nil {
                    try connection.run("INSERT OR IGNORE INTO tags (id, name) VALUES (?, ?)", [.text(id), .text(name)])
                    tagID = id
                }
                guard let tagID else { continue }
                for recordID in (row["record_ids"].array ?? []).compactMap(\.string) where keptRecordIDs.contains(recordID) {
                    try connection.run("INSERT OR IGNORE INTO record_tags (record_id, tag_id) VALUES (?, ?)",
                                       [.text(recordID), .text(tagID)])
                }
            }
        }
    }

    /// `analyte_user_aliases.json`: `[{normalized_name, analyte_id, created_ms}]`; local mappings win.
    func importArchiveAliases(_ rows: [RJ], nowMs: Int64 = RecordDates.nowMs()) throws {
        guard !rows.isEmpty else { return }
        try connection.inTransaction {
            for row in rows {
                guard let key = row["normalized_name"].string, let analyteID = row["analyte_id"].string,
                      catalog.hasEntry(analyteID) else { continue }
                try connection.run(
                    "INSERT OR IGNORE INTO analyte_user_aliases (normalized_name, analyte_id, created_ms) VALUES (?, ?, ?)",
                    [.text(key), .text(analyteID), .int(Int64(row["created_ms"].double ?? Double(nowMs)))]
                )
            }
        }
    }

    /// §35: imported records are `ready` and carry a finished processing job, so neither the
    /// Phase 1 backfill nor the queue reprocesses details the archive already holds.
    func finishArchiveImport(missingFileIDs: [String], importedIDs: [String], nowMs: Int64 = RecordDates.nowMs()) throws {
        try connection.inTransaction {
            for id in importedIDs {
                try connection.run(
                    "INSERT OR REPLACE INTO processing_jobs (record_id, stage, attempts, next_attempt_ms, awaiting_consent, updated_ms) VALUES (?, 'done', 0, 0, 0, ?)",
                    [.text(id), .int(nowMs)]
                )
            }
            try connection.exec("UPDATE records SET processing_status='ready' WHERE processing_status<>'ready'")
            for id in missingFileIDs {
                try connection.run(
                    "UPDATE records SET file_path=NULL, processing_error=? WHERE id=?",
                    [.text("file_missing"), .text(id)]
                )
            }
        }
        // The one-time Phase 1 backfill must not queue the imported records either.
        _ = try backfillProcessingJobsIfNeeded(nowMs: nowMs)
        try connection.exec("UPDATE records SET processing_status='ready' WHERE processing_status='queued' AND id IN (SELECT record_id FROM processing_jobs WHERE stage='done')")
        try rebuildAllFTSRows()
    }
}

extension HealthDBStatement {
    /// `SQLITE_TEXT` (3) / `SQLITE_INTEGER` (1) without importing SQLite3 here.
    func columnTypeIsText(_ index: Int32) -> Bool { columnType(index) == 3 }
    func columnTypeIsInteger(_ index: Int32) -> Bool { columnType(index) == 1 }
}
