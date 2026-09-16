import Foundation
import SQLite3

/// Single-connection SQLite actor for Health Records (`records.sqlite`, schema v1 from
/// `shared/records/schema.sql`). Same shape as `HealthDatabase`: WAL, `synchronous=NORMAL`,
/// `foreign_keys=ON`, `busy_timeout=5000`, `PRAGMA user_version` + `records_meta.schema_version`.
/// A separate database from the health mirror so `shared/health` parity is untouched.
actor RecordsDatabase {
    nonisolated struct ColumnInfo: Sendable, Hashable {
        let cid: Int
        let name: String
        let type: String
        let notNull: Bool
        let defaultValue: String?
        let primaryKey: Int
    }

    nonisolated let url: URL?
    let connection: HealthDBConnection
    private(set) var isClosed = false
    /// Zone for the `sort_date` fallback day; tests pin it.
    nonisolated let timeZone: TimeZone
    /// Analyte catalog used for mapping, conversion and FTS names (tests inject a small one).
    nonisolated let catalog: AnalyteCatalog

    private init(connection: HealthDBConnection, url: URL?, timeZone: TimeZone, catalog: AnalyteCatalog) {
        self.connection = connection
        self.url = url
        self.timeZone = timeZone
        self.catalog = catalog
    }

    // MARK: - Opening

    /// `targetVersion` exists for migration tests (open a v1 database); the app always migrates to the latest.
    nonisolated static func open(
        url: URL,
        fileManager: FileManager = .default,
        timeZone: TimeZone = .current,
        targetVersion: Int = RecordsSchema.schemaVersion,
        catalog: AnalyteCatalog = .shared
    ) async throws -> RecordsDatabase {
        try RecordsLocation.prepareDirectory(url.deletingLastPathComponent(), fileManager: fileManager)
        let connection = try HealthDBConnection(path: url.path, readOnly: false, fileProtection: true)
        let database = RecordsDatabase(connection: connection, url: url, timeZone: timeZone, catalog: catalog)
        try await database.configure(targetVersion: targetVersion)
        return database
    }

    /// An unreadable file is moved aside (`records.sqlite.corrupt-<ms>` + sidecars) and a
    /// fresh database is created. The original files under `files/` are untouched.
    nonisolated static func openQuarantiningCorruption(url: URL, fileManager: FileManager = .default) async throws -> (RecordsDatabase, quarantined: URL?) {
        do {
            return (try await open(url: url, fileManager: fileManager), nil)
        } catch let error as HealthDBError where error.isCorruption {
            let moved = try HealthDatabaseLocation.quarantine(url, fileManager: fileManager)
            return (try await open(url: url, fileManager: fileManager), moved)
        }
    }

    nonisolated static func inMemory(timeZone: TimeZone = .current, targetVersion: Int = RecordsSchema.schemaVersion, catalog: AnalyteCatalog = .shared) async throws -> RecordsDatabase {
        let connection = try HealthDBConnection(path: ":memory:", readOnly: false, fileProtection: false)
        let database = RecordsDatabase(connection: connection, url: nil, timeZone: timeZone, catalog: catalog)
        try await database.configure(targetVersion: targetVersion)
        return database
    }

    private func configure(targetVersion: Int) throws {
        try connection.exec("PRAGMA journal_mode=WAL")
        try connection.exec("PRAGMA synchronous=NORMAL")
        try connection.exec("PRAGMA busy_timeout=5000")
        try connection.exec("PRAGMA foreign_keys=ON")
        let check = try connection.scalarText("PRAGMA quick_check(1)")
        guard check == "ok" else {
            throw HealthDBError(kind: .corrupt, code: SQLITE_CORRUPT, message: check ?? "quick_check failed")
        }
        try migrate(to: targetVersion)
        if targetVersion >= RecordsSchema.schemaVersion {
            try reindexAllIfNeeded()
        }
    }

    /// v1 (`schema.sql`) then every migration in order, each step in its own transaction that
    /// also stamps `user_version` + `records_meta.schema_version` (contract §8).
    private func migrate(to targetVersion: Int) throws {
        var version = try userVersion()
        if version < RecordsSchema.baseVersion, targetVersion >= RecordsSchema.baseVersion {
            try connection.inTransaction {
                for statement in RecordsSchema.statements {
                    try connection.exec(statement)
                }
                try stampVersion(RecordsSchema.baseVersion)
            }
            version = RecordsSchema.baseVersion
        }
        for migration in RecordsSchema.migrations where migration.version > version && migration.version <= targetVersion {
            try connection.inTransaction {
                for statement in migration.statements {
                    try connection.exec(statement)
                }
                try stampVersion(migration.version)
            }
            version = migration.version
        }
    }

    private func stampVersion(_ version: Int) throws {
        try connection.run(
            "INSERT OR REPLACE INTO records_meta (key, value) VALUES ('schema_version', ?)",
            [.text("\(version)")]
        )
        try connection.exec("PRAGMA user_version=\(version)")
    }

    // MARK: - Introspection

    func userVersion() throws -> Int {
        Int(try connection.scalarInt64("PRAGMA user_version") ?? 0)
    }

    func metaValue(_ key: String) throws -> String? {
        try connection.scalarText("SELECT value FROM records_meta WHERE key=?", [.text(key)])
    }

    func tableNames() throws -> [String] {
        var names: [String] = []
        try connection.query("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' AND name NOT LIKE 'records_fts_%' ORDER BY name") {
            if let name = $0.text(0) { names.append(name) }
        }
        return names
    }

    func indexNames() throws -> [String] {
        var names: [String] = []
        try connection.query("SELECT name FROM sqlite_master WHERE type='index' AND name NOT LIKE 'sqlite_%' ORDER BY name") {
            if let name = $0.text(0) { names.append(name) }
        }
        return names
    }

    func tableInfo(_ table: String) throws -> [ColumnInfo] {
        let allowed = CharacterSet(charactersIn: "abcdefghijklmnopqrstuvwxyz_")
        guard table.unicodeScalars.allSatisfy({ allowed.contains($0) }) else {
            throw HealthDBError(kind: .misuse, code: SQLITE_MISUSE, message: "invalid table name")
        }
        var columns: [ColumnInfo] = []
        try connection.query("PRAGMA table_info(\(table))") { statement in
            columns.append(ColumnInfo(
                cid: statement.int(0) ?? 0,
                name: statement.text(1) ?? "",
                type: statement.text(2) ?? "",
                notNull: (statement.int(3) ?? 0) != 0,
                defaultValue: statement.text(4),
                primaryKey: statement.int(5) ?? 0
            ))
        }
        return columns
    }

    func journalMode() throws -> String? {
        try connection.scalarText("PRAGMA journal_mode")
    }

    func pragmaInt(_ name: String) throws -> Int? {
        let allowed = CharacterSet(charactersIn: "abcdefghijklmnopqrstuvwxyz_")
        guard name.unicodeScalars.allSatisfy({ allowed.contains($0) }) else { return nil }
        return try connection.scalarInt64("PRAGMA \(name)").map { Int($0) }
    }

    func integrityCheck() throws -> Bool {
        try connection.scalarText("PRAGMA integrity_check") == "ok"
    }

    func withConnection<T: Sendable>(_ body: (HealthDBConnection) throws -> T) throws -> T {
        try body(connection)
    }

    func close() {
        guard !isClosed else { return }
        isClosed = true
        connection.close()
    }

    // MARK: - Records

    nonisolated static let columns = "seq, id, parent_id, page_start, page_end, title, record_type, category, source, import_method, source_app, original_filename, created_ms, updated_ms, document_date, document_date_precision, document_date_method, sort_date, mime_type, file_type, file_size, page_count, file_path, thumbnail_path, checksum_sha256, processing_status, processing_error, review_status, favorite, archived, notes, phash, text_signature, ai_mode_used, ai_provider, type_confidence, type_method, shared_count, last_shared_ms"

    /// Contract §5 order, served by `idx_records_timeline`.
    nonisolated static let timelineOrderSQL = "sort_date DESC, created_ms DESC, seq DESC"

    nonisolated static func decodeRecord<S: HealthDBStatementReading>(_ s: S) -> HealthRecord {
        HealthRecord(
            seq: s.int64(0) ?? 0,
            id: s.text(1) ?? "",
            parentID: s.text(2),
            pageStart: s.int(3),
            pageEnd: s.int(4),
            title: s.text(5) ?? "",
            recordType: RecordType(rawValue: s.text(6) ?? "") ?? .other,
            category: RecordCategory(rawValue: s.text(7) ?? "") ?? .other,
            source: RecordSource(rawValue: s.text(8) ?? "") ?? .import,
            importMethod: RecordImportMethod(rawValue: s.text(9) ?? "") ?? .filePicker,
            sourceApp: s.text(10),
            originalFilename: s.text(11),
            createdMs: s.int64(12) ?? 0,
            updatedMs: s.int64(13) ?? 0,
            documentDate: s.text(14),
            documentDatePrecision: s.text(15).flatMap(RecordDatePrecision.init(rawValue:)),
            documentDateMethod: s.text(16).flatMap(RecordDateMethod.init(rawValue:)),
            sortDate: s.text(17) ?? "",
            mimeType: s.text(18) ?? "application/octet-stream",
            fileType: RecordFileType(rawValue: s.text(19) ?? "") ?? .other,
            fileSize: s.int64(20) ?? 0,
            pageCount: s.int(21) ?? 0,
            filePath: s.text(22),
            thumbnailPath: s.text(23),
            checksumSHA256: s.text(24),
            processingStatus: RecordProcessingStatus(rawValue: s.text(25) ?? "") ?? .saved,
            processingError: s.text(26),
            reviewStatus: RecordReviewStatus(rawValue: s.text(27) ?? "") ?? .none,
            favorite: (s.int(28) ?? 0) != 0,
            archived: (s.int(29) ?? 0) != 0,
            notes: s.text(30),
            phash: s.text(31),
            textSignature: s.text(32),
            aiModeUsed: RecordAIModeUsed(rawValue: s.text(33) ?? "") ?? .none,
            aiProvider: s.text(34),
            typeConfidence: s.double(35),
            typeMethod: s.text(36).flatMap(RecordFieldMethod.init(rawValue:)),
            sharedCount: s.int(37) ?? 0,
            lastSharedMs: s.int64(38)
        )
    }

    /// Inserts the row, its pages and tags and indexes it. Returns the stored record (with `seq`).
    @discardableResult
    func insert(_ record: HealthRecord, pages: [RecordPage] = [], tags: [String] = []) throws -> HealthRecord {
        let sortDate = record.documentDate
            ?? (record.sortDate.isEmpty ? RecordDates.localDayString(ms: record.createdMs, timeZone: timeZone) : record.sortDate)
        try connection.inTransaction {
            try connection.run(
                """
                INSERT INTO records (id, parent_id, page_start, page_end, title, record_type, category, source, import_method, source_app, original_filename, created_ms, updated_ms, document_date, document_date_precision, document_date_method, sort_date, mime_type, file_type, file_size, page_count, file_path, thumbnail_path, checksum_sha256, processing_status, processing_error, review_status, favorite, archived, notes, phash, text_signature, ai_mode_used, ai_provider, type_confidence, type_method, shared_count, last_shared_ms)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                [
                    .text(record.id), .optionalText(record.parentID), .optionalInt(record.pageStart), .optionalInt(record.pageEnd),
                    .text(record.title), .text(record.recordType.rawValue), .text(record.category.rawValue),
                    .text(record.source.rawValue), .text(record.importMethod.rawValue), .optionalText(record.sourceApp),
                    .optionalText(record.originalFilename), .int(record.createdMs), .int(record.updatedMs),
                    .optionalText(record.documentDate), .optionalText(record.documentDatePrecision?.rawValue),
                    .optionalText(record.documentDateMethod?.rawValue), .text(sortDate), .text(record.mimeType), .text(record.fileType.rawValue),
                    .int(record.fileSize), .int(Int64(record.pageCount)), .optionalText(record.filePath),
                    .optionalText(record.thumbnailPath), .optionalText(record.checksumSHA256),
                    .text(record.processingStatus.rawValue), .optionalText(record.processingError),
                    .text(record.reviewStatus.rawValue), .int(record.favorite ? 1 : 0), .int(record.archived ? 1 : 0),
                    .optionalText(record.notes), .optionalText(record.phash), .optionalText(record.textSignature),
                    .text(record.aiModeUsed.rawValue), .optionalText(record.aiProvider), .optionalReal(record.typeConfidence),
                    .optionalText(record.typeMethod?.rawValue),
                    .int(Int64(record.sharedCount)), .optionalInt64(record.lastSharedMs),
                ]
            )
            try replacePagesInTransaction(recordID: record.id, pages: pages)
            if !tags.isEmpty {
                try setTagsInTransaction(recordID: record.id, names: tags)
            }
            try reindexInTransaction(recordID: record.id)
        }
        return try self.record(id: record.id) ?? record
    }

    func record(id: String) throws -> HealthRecord? {
        var result: HealthRecord?
        try connection.query("SELECT \(Self.columns) FROM records WHERE id=?", [.text(id)]) {
            result = Self.decodeRecord($0)
        }
        return result
    }

    func detail(id: String) throws -> RecordDetail? {
        guard let record = try record(id: id) else { return nil }
        var detail = RecordDetail(record: record, tags: try tags(recordID: id), pages: try pages(recordID: id))
        detail.fields = try fields(recordID: id)
        detail.highlights = try highlights(recordID: id)
        detail.job = try job(recordID: id)
        detail.duplicates = try duplicateCandidates(recordID: id, pendingOnly: true)
        detail.splitProposal = try splitProposal(recordID: id)
        if let parentID = record.parentID {
            detail.parent = try self.record(id: parentID)
        }
        detail.children = try children(parentID: id)
        try loadKnowledge(into: &detail)
        return detail
    }

    func recordCount(includeArchived: Bool = true) throws -> Int {
        Int(try connection.scalarInt64(includeArchived ? "SELECT COUNT(*) FROM records" : "SELECT COUNT(*) FROM records WHERE archived=0") ?? 0)
    }

    /// SQL + bindings for one keyset page in contract §5 order. Projection only (no page text).
    private func pageSQL(query: RecordQuery, after cursor: RecordCursor?, limit: Int) -> (sql: String, values: [SQLValue])? {
        var clauses: [String] = ["archived = ?"]
        var values: [SQLValue] = [.int(query.archivedOnly ? 1 : 0)]
        if query.favoritesOnly { clauses.append("favorite = 1") }
        if query.receivedOnly {
            clauses.append("source IN ('\(RecordSource.shareIn.rawValue)', '\(RecordSource.openIn.rawValue)')")
        }
        func appendIn(_ column: String, _ raws: [String]) {
            guard !raws.isEmpty else { return }
            clauses.append("\(column) IN (\(Array(repeating: "?", count: raws.count).joined(separator: ", ")))")
            values.append(contentsOf: raws.sorted().map { .text($0) })
        }
        appendIn("record_type", query.recordTypes.map(\.rawValue))
        appendIn("category", query.categories.map(\.rawValue))
        appendIn("file_type", query.fileTypes.map(\.rawValue))
        if !query.trimmedText.isEmpty {
            guard let match = RecordsSearchText.matchExpression(query.text) else { return nil }
            clauses.append("seq IN (SELECT docid FROM records_fts WHERE records_fts MATCH ?)")
            values.append(.text(match))
        }
        guard (try? appendAdvancedFilters(query, clauses: &clauses, values: &values)) == true else { return nil }
        if let cursor {
            clauses.append("(sort_date < ? OR (sort_date = ? AND created_ms < ?) OR (sort_date = ? AND created_ms = ? AND seq < ?))")
            values.append(contentsOf: [
                .text(cursor.sortDate),
                .text(cursor.sortDate), .int(cursor.createdMs),
                .text(cursor.sortDate), .int(cursor.createdMs), .int(cursor.seq),
            ])
        }
        values.append(.int(Int64(max(1, limit))))
        let sql = "SELECT \(Self.columns) FROM records WHERE \(clauses.joined(separator: " AND ")) ORDER BY \(Self.timelineOrderSQL) LIMIT ?"
        return (sql, values)
    }

    func page(query: RecordQuery, after cursor: RecordCursor?, limit: Int) throws -> [HealthRecord] {
        guard let (sql, values) = pageSQL(query: query, after: cursor, limit: limit) else { return [] }
        var rows: [HealthRecord] = []
        try connection.query(sql, values) { rows.append(Self.decodeRecord($0)) }
        return rows
    }

    /// `EXPLAIN QUERY PLAN` detail lines for the page query (performance tests).
    func pageQueryPlan(query: RecordQuery, after cursor: RecordCursor? = nil, limit: Int = 60) throws -> [String] {
        guard let (sql, values) = pageSQL(query: query, after: cursor, limit: limit) else { return [] }
        var lines: [String] = []
        try connection.query("EXPLAIN QUERY PLAN " + sql, values) { if let detail = $0.text(3) { lines.append(detail) } }
        return lines
    }

    /// Most recently added (not archived), for the Recent strip.
    func recent(limit: Int) throws -> [HealthRecord] {
        var rows: [HealthRecord] = []
        try connection.query("SELECT \(Self.columns) FROM records WHERE archived=0 ORDER BY created_ms DESC, seq DESC LIMIT ?", [.int(Int64(limit))]) {
            rows.append(Self.decodeRecord($0))
        }
        return rows
    }

    /// Other records with the same SHA-256 (contract §4.5).
    func records(withChecksum checksum: String, excluding id: String?) throws -> [HealthRecord] {
        var rows: [HealthRecord] = []
        try connection.query(
            "SELECT \(Self.columns) FROM records WHERE checksum_sha256=? AND id<>? ORDER BY created_ms",
            [.text(checksum), .text(id ?? "")]
        ) { rows.append(Self.decodeRecord($0)) }
        return rows
    }

    func update(id: String, patch: RecordPatch, nowMs: Int64 = RecordDates.nowMs()) throws {
        var sets: [String] = []
        var values: [SQLValue] = []
        if let title = patch.title {
            sets.append("title=?")
            values.append(.text(title))
        }
        if let type = patch.recordType {
            sets.append("record_type=?, type_method=?")
            values.append(.text(type.rawValue))
            values.append(.text(RecordFieldMethod.user.rawValue))
        }
        if let category = patch.category {
            sets.append("category=?, type_method=?")
            values.append(.text(category.rawValue))
            values.append(.text(RecordFieldMethod.user.rawValue))
        }
        if let date = patch.documentDate {
            // sort_date follows document_date; clearing it resets to the local import day.
            let createdMs = try connection.scalarInt64("SELECT created_ms FROM records WHERE id=?", [.text(id)]) ?? nowMs
            sets.append("document_date=?, document_date_precision=?, document_date_method=?, sort_date=?")
            values.append(.optionalText(date))
            values.append(date == nil ? .null : .text(RecordDatePrecision.day.rawValue))
            values.append(date == nil ? .null : .text(RecordDateMethod.user.rawValue))
            values.append(.text(date ?? RecordDates.localDayString(ms: createdMs, timeZone: timeZone)))
        }
        if let notes = patch.notes {
            sets.append("notes=?")
            values.append(.optionalText(notes))
        }
        if let favorite = patch.favorite {
            sets.append("favorite=?")
            values.append(.int(favorite ? 1 : 0))
        }
        if let archived = patch.archived {
            sets.append("archived=?")
            values.append(.int(archived ? 1 : 0))
        }
        guard !sets.isEmpty else { return }
        sets.append("updated_ms=?")
        values.append(.int(nowMs))
        values.append(.text(id))
        try connection.inTransaction {
            try connection.run("UPDATE records SET \(sets.joined(separator: ", ")) WHERE id=?", values)
            if patch.title != nil || patch.notes != nil || patch.recordType != nil {
                try reindexInTransaction(recordID: id)
            }
        }
    }

    func setFlags(ids: [String], favorite: Bool? = nil, archived: Bool? = nil, nowMs: Int64 = RecordDates.nowMs()) throws {
        guard favorite != nil || archived != nil else { return }
        try connection.inTransaction {
            for id in ids {
                if let favorite {
                    try connection.run("UPDATE records SET favorite=?, updated_ms=? WHERE id=?", [.int(favorite ? 1 : 0), .int(nowMs), .text(id)])
                }
                if let archived {
                    try connection.run("UPDATE records SET archived=?, updated_ms=? WHERE id=?", [.int(archived ? 1 : 0), .int(nowMs), .text(id)])
                }
            }
        }
    }

    /// Background basics (contract §4.6): page count, thumbnail, metadata date, status.
    func applyBasics(
        id: String,
        pageCount: Int?,
        thumbnailPath: String?,
        documentDate: String?,
        dateMethod: RecordDateMethod?,
        status: RecordProcessingStatus,
        error: String?,
        pages: [RecordPage]?,
        nowMs: Int64 = RecordDates.nowMs()
    ) throws {
        try connection.inTransaction {
            if let pageCount {
                try connection.run("UPDATE records SET page_count=? WHERE id=?", [.int(Int64(pageCount)), .text(id)])
            }
            if let thumbnailPath {
                try connection.run("UPDATE records SET thumbnail_path=? WHERE id=?", [.text(thumbnailPath), .text(id)])
            }
            if let documentDate, let dateMethod {
                // Never replace a date the user (or a later stage) already set.
                try connection.run(
                    "UPDATE records SET document_date=?, document_date_precision=?, document_date_method=?, sort_date=? WHERE id=? AND document_date IS NULL",
                    [.text(documentDate), .text(RecordDatePrecision.day.rawValue), .text(dateMethod.rawValue), .text(documentDate), .text(id)]
                )
            }
            try connection.run(
                "UPDATE records SET processing_status=?, processing_error=?, updated_ms=? WHERE id=?",
                [.text(status.rawValue), .optionalText(error), .int(nowMs), .text(id)]
            )
            if let pages {
                try replacePagesInTransaction(recordID: id, pages: pages)
                try reindexInTransaction(recordID: id)
            }
        }
    }

    /// Deletes rows (pages and tag links cascade) and their FTS rows. Returns the ids that existed.
    @discardableResult
    func delete(ids: [String]) throws -> [String] {
        var removed: [String] = []
        try connection.inTransaction {
            for id in ids {
                guard let seq = try connection.scalarInt64("SELECT seq FROM records WHERE id=?", [.text(id)]) else { continue }
                try connection.run("DELETE FROM records_fts WHERE docid=?", [.int(seq)])
                try connection.run("DELETE FROM records WHERE id=?", [.text(id)])
                removed.append(id)
            }
            try connection.exec("DELETE FROM tags WHERE id NOT IN (SELECT tag_id FROM record_tags)")
            try deleteOrphanEntitiesInTransaction()
        }
        return removed
    }

    // MARK: - Pages

    func pages(recordID: String) throws -> [RecordPage] {
        var rows: [RecordPage] = []
        try connection.query(
            "SELECT record_id, page_index, text, text_source, ocr_confidence, width, height, blocks_json FROM record_pages WHERE record_id=? ORDER BY page_index",
            [.text(recordID)]
        ) { s in
            rows.append(RecordPage(
                recordID: s.text(0) ?? recordID,
                pageIndex: s.int(1) ?? 0,
                text: s.text(2),
                textSource: RecordPageTextSource(rawValue: s.text(3) ?? "") ?? .plain,
                ocrConfidence: s.double(4),
                width: s.int(5),
                height: s.int(6),
                blocksJSON: s.text(7)
            ))
        }
        return rows
    }

    func replacePagesInTransaction(recordID: String, pages: [RecordPage]) throws {
        try connection.run("DELETE FROM record_pages WHERE record_id=?", [.text(recordID)])
        guard !pages.isEmpty else { return }
        let statement = try connection.prepare(
            "INSERT INTO record_pages (record_id, page_index, text, text_source, ocr_confidence, width, height, blocks_json) VALUES (?, ?, ?, ?, ?, ?, ?, ?)"
        )
        for page in pages {
            statement.reset()
            try statement.bind([
                .text(recordID), .int(Int64(page.pageIndex)), .optionalText(page.text), .text(page.textSource.rawValue),
                .optionalReal(page.ocrConfidence), .optionalInt(page.width), .optionalInt(page.height), .optionalText(page.blocksJSON),
            ])
            _ = try statement.step()
        }
    }

    // MARK: - Tags

    func tags(recordID: String) throws -> [String] {
        var names: [String] = []
        try connection.query(
            "SELECT t.name FROM record_tags rt JOIN tags t ON t.id = rt.tag_id WHERE rt.record_id=? ORDER BY t.name COLLATE NOCASE",
            [.text(recordID)]
        ) { if let name = $0.text(0) { names.append(name) } }
        return names
    }

    func allTagNames() throws -> [String] {
        var names: [String] = []
        try connection.query("SELECT name FROM tags ORDER BY name COLLATE NOCASE") {
            if let name = $0.text(0) { names.append(name) }
        }
        return names
    }

    func setTags(recordID: String, names: [String], nowMs: Int64 = RecordDates.nowMs()) throws {
        try connection.inTransaction {
            try setTagsInTransaction(recordID: recordID, names: names)
            try connection.run("UPDATE records SET updated_ms=? WHERE id=?", [.int(nowMs), .text(recordID)])
            try connection.exec("DELETE FROM tags WHERE id NOT IN (SELECT tag_id FROM record_tags)")
            try reindexInTransaction(recordID: recordID)
        }
    }

    nonisolated static func normalizedTagNames(_ names: [String]) -> [String] {
        var seen = Set<String>()
        var result: [String] = []
        for raw in names {
            let name = raw.trimmingCharacters(in: .whitespacesAndNewlines)
            guard !name.isEmpty, seen.insert(name.lowercased()).inserted else { continue }
            result.append(name)
        }
        return result
    }

    func setTagsInTransaction(recordID: String, names: [String]) throws {
        try connection.run("DELETE FROM record_tags WHERE record_id=?", [.text(recordID)])
        for name in Self.normalizedTagNames(names) {
            var tagID = try connection.scalarText("SELECT id FROM tags WHERE name=? COLLATE NOCASE", [.text(name)])
            if tagID == nil {
                let newID = UUID().uuidString.lowercased()
                try connection.run("INSERT INTO tags (id, name) VALUES (?, ?)", [.text(newID), .text(name)])
                tagID = newID
            }
            try connection.run("INSERT OR IGNORE INTO record_tags (record_id, tag_id) VALUES (?, ?)", [.text(recordID), .text(tagID!)])
        }
    }

    // MARK: - FTS

    /// Rebuilds the record's one FTS row (§17 columns, all folded, rejected fields excluded).
    func reindex(recordID: String) throws {
        try connection.inTransaction {
            try reindexInTransaction(recordID: recordID)
        }
    }

    func reindexInTransaction(recordID: String) throws {
        var seq: Int64?
        var title: String?
        var notes: String?
        var type: RecordType = .other
        try connection.query("SELECT seq, title, notes, record_type FROM records WHERE id=?", [.text(recordID)]) { s in
            seq = s.int64(0)
            title = s.text(1)
            notes = s.text(2)
            type = RecordType(rawValue: s.text(3) ?? "") ?? .other
        }
        guard let seq else { return }
        let tagText = try tags(recordID: recordID).joined(separator: " ")
        var bodyParts: [String] = []
        try connection.query("SELECT text FROM record_pages WHERE record_id=? ORDER BY page_index", [.text(recordID)]) {
            if let text = $0.text(0), !text.isEmpty { bodyParts.append(text) }
        }
        // Reference `fts_row` (§28): people and clinical values in key order (row order inside a key); clinical =
        // the raw type words, the clinical values, then the analyte display names; distinct clinical values.
        var byKey: [String: [String]] = [:]
        try connection.query(
            "SELECT field_key, value_text FROM record_fields WHERE record_id=? AND state<>'rejected' ORDER BY rowid",
            [.text(recordID)]
        ) { s in
            guard let key = s.text(0), let value = s.text(1) else { return }
            byKey[key, default: []].append(value)
        }
        let people = RR.coachPeopleKeys.flatMap { byKey[$0] ?? [] }
        var clinicalValues: [String] = type == .other ? [] : [type.rawValue.replacingOccurrences(of: "_", with: " ")]
        clinicalValues += RR.coachClinicalKeys.flatMap { byKey[$0] ?? [] }
        var analyteIDs: [String] = []
        try connection.query(
            "SELECT DISTINCT analyte_id FROM observations WHERE record_id=? AND analyte_id IS NOT NULL AND state<>'rejected'",
            [.text(recordID)]
        ) { s in
            if let id = s.text(0), catalog.hasEntry(id) { analyteIDs.append(id) }
        }
        clinicalValues += analyteIDs.sorted(by: RR.scalarLess).compactMap { catalog.analyte(id: $0)?.displayName }
        var clinical: [String] = []
        for value in clinicalValues where !value.isEmpty && !clinical.contains(value) {
            clinical.append(value)
        }
        var highlightTexts: [String] = []
        try connection.query("SELECT text FROM record_highlights WHERE record_id=? AND dismissed=0 ORDER BY section, position", [.text(recordID)]) {
            if let text = $0.text(0) { highlightTexts.append(text) }
        }
        try connection.run("DELETE FROM records_fts WHERE docid=?", [.int(seq)])
        try connection.run(
            "INSERT INTO records_fts (docid, title, people, clinical, body, notes_tags, highlights) VALUES (?, ?, ?, ?, ?, ?, ?)",
            [
                .int(seq),
                .text(RecordsSearchText.indexText(title)),
                .text(RecordsSearchText.indexText(people.joined(separator: "\n"))),
                .text(RecordsSearchText.indexText(clinical.joined(separator: "\n"))),
                .text(RecordsSearchText.indexText(bodyParts.joined(separator: "\n"))),
                .text(RecordsSearchText.indexText([notes ?? "", tagText].joined(separator: " "))),
                .text(RecordsSearchText.indexText(highlightTexts.joined(separator: "\n"))),
            ]
        )
    }

    // MARK: - Wipe

    func wipeAllData() throws {
        try connection.inTransaction {
            try connection.exec("""
            DELETE FROM records_fts;
            DELETE FROM record_links;
            DELETE FROM record_entities;
            DELETE FROM entities;
            DELETE FROM analyte_user_aliases;
            DELETE FROM observations;
            DELETE FROM processing_jobs;
            DELETE FROM duplicate_candidates;
            DELETE FROM split_proposals;
            DELETE FROM record_highlights;
            DELETE FROM record_fields;
            DELETE FROM record_tags;
            DELETE FROM tags;
            DELETE FROM record_pages;
            DELETE FROM records;
            """)
        }
    }
}
