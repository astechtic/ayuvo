import Foundation
import Testing
import UIKit
@testable import calorietracker

enum RecordsTestFixtures {
    static var sharedSchemaURL: URL { HealthTestFixtures.repoRootURL.appendingPathComponent("shared/records/schema.sql") }

    static func temporaryDirectory() throws -> URL {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("records-tests-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        return url
    }

    static func record(
        id: String = UUID().uuidString.lowercased(),
        title: String,
        documentDate: String? = nil,
        createdMs: Int64,
        type: RecordType = .other,
        fileType: RecordFileType = .pdf,
        source: RecordSource = .import,
        favorite: Bool = false,
        archived: Bool = false,
        notes: String? = nil
    ) -> HealthRecord {
        HealthRecord(
            id: id,
            title: title,
            recordType: type,
            category: type.defaultCategory,
            source: source,
            importMethod: .filePicker,
            createdMs: createdMs,
            updatedMs: createdMs,
            documentDate: documentDate,
            documentDatePrecision: documentDate == nil ? nil : .day,
            documentDateMethod: documentDate == nil ? nil : .user,
            mimeType: "application/pdf",
            fileType: fileType,
            favorite: favorite,
            archived: archived,
            notes: notes
        )
    }

    /// Milliseconds for a UTC calendar day at `hour`.
    static func ms(_ day: String, hour: Int = 12) -> Int64 {
        let parts = day.split(separator: "-").compactMap { Int($0) }
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(identifier: "UTC")!
        let date = calendar.date(from: DateComponents(year: parts[0], month: parts[1], day: parts[2], hour: hour))!
        return Int64(date.timeIntervalSince1970 * 1000)
    }

    static func pdfData(pages: Int = 1, text: String = "Complete Blood Count") -> Data {
        let format = UIGraphicsPDFRendererFormat()
        format.documentInfo = [kCGPDFContextCreator as String: "Ayuvo tests"]
        let renderer = UIGraphicsPDFRenderer(bounds: CGRect(x: 0, y: 0, width: 612, height: 792), format: format)
        return renderer.pdfData { context in
            for index in 0..<pages {
                context.beginPage()
                (text + " \(index + 1)" as NSString).draw(at: CGPoint(x: 72, y: 72), withAttributes: [.font: UIFont.systemFont(ofSize: 24)])
            }
        }
    }
}

struct RecordsSchemaTests {
    private typealias F = RecordsTestFixtures

    @Test func embeddedStatementsMatchSharedSchemaVerbatim() throws {
        let url = F.sharedSchemaURL
        guard FileManager.default.fileExists(atPath: url.path) else {
            Issue.record("shared/records/schema.sql is missing")
            return
        }
        let fileStatements = RecordsSchema.parseStatements(try String(contentsOf: url, encoding: .utf8))
        #expect(fileStatements.count == RecordsSchema.statements.count)
        for (shared, embedded) in zip(fileStatements, RecordsSchema.statements) {
            #expect(shared == embedded, "statement differs from shared/records/schema.sql:\n\(shared)")
        }
    }

    @Test func schemaCreatedFromSharedFileMatchesEmbeddedColumnForColumn() async throws {
        let fileSQL = try String(contentsOf: F.sharedSchemaURL, encoding: .utf8)
        // v1 only; migration 002 has its own parity tests (RecordsMigrationTests).
        let embedded = try await RecordsDatabase.inMemory(targetVersion: 1)
        let fromFile = try await RecordsDatabase.inMemory(targetVersion: 0)
        try await fromFile.withConnection { connection in
            try connection.exec(fileSQL)
        }
        #expect(try await embedded.tableNames() == fromFile.tableNames())
        #expect(try await embedded.indexNames() == fromFile.indexNames())
        for table in ["records", "record_pages", "tags", "record_tags", "records_fts", "records_meta"] {
            let a = try await embedded.tableInfo(table)
            let b = try await fromFile.tableInfo(table)
            #expect(a == b, "table \(table) differs from shared/records/schema.sql")
        }
    }

    @Test func embeddedSchemaStampsVersionAndCreatesEverything() async throws {
        let db = try await RecordsDatabase.inMemory()
        #expect(try await db.tableNames() == RecordsSchema.tableNames.sorted())
        #expect(try await db.indexNames() == RecordsSchema.indexNames.sorted())
        #expect(try await db.userVersion() == RecordsSchema.schemaVersion)
        #expect(try await db.metaValue("schema_version") == "\(RecordsSchema.schemaVersion)")
        #expect(try await db.pragmaInt("foreign_keys") == 1)
        #expect(try await db.pragmaInt("busy_timeout") == 5000)
    }

    @Test func onDiskDatabaseUsesWALInBackupExcludedDirectoryAndReopens() async throws {
        let directory = try F.temporaryDirectory()
        defer { try? FileManager.default.removeItem(at: directory) }
        let url = directory.appendingPathComponent("Ayuvo/Records/records.sqlite")
        let db = try await RecordsDatabase.open(url: url)
        #expect(try await db.journalMode()?.lowercased() == "wal")
        #expect(try await db.pragmaInt("synchronous") == 1)
        #expect(HealthDatabaseLocation.isExcludedFromBackup(url.deletingLastPathComponent()))
        try await db.insert(F.record(title: "Kept", createdMs: 1_000))
        await db.close()
        let reopened = try await RecordsDatabase.open(url: url)
        #expect(try await reopened.recordCount() == 1)
        #expect(try await reopened.userVersion() == RecordsSchema.schemaVersion)
        await reopened.close()
    }

    @Test func corruptFileIsQuarantinedAndReplaced() async throws {
        let directory = try F.temporaryDirectory()
        defer { try? FileManager.default.removeItem(at: directory) }
        let url = directory.appendingPathComponent("Records/records.sqlite")
        try FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
        try Data("not a database at all, just bytes".utf8).write(to: url)
        let (db, quarantined) = try await RecordsDatabase.openQuarantiningCorruption(url: url)
        #expect(quarantined != nil)
        #expect(try await db.integrityCheck())
        #expect(try await db.tableNames().contains("records"))
        await db.close()
    }

    @Test func locationIsApplicationSupportAyuvoRecords() {
        #expect(RecordsLocation.databaseURL().path.hasSuffix("Application Support/Ayuvo/Records/records.sqlite"))
        #expect(RecordsLocation.filesRoot().path.hasSuffix("Application Support/Ayuvo/Records/files"))
        #expect(RecordsLocation.renderCacheDirectory().path.hasSuffix("Caches/Records/render"))
    }
}

struct RecordsDatabaseTests {
    private typealias F = RecordsTestFixtures

    @Test func insertReadsBackEveryColumn() async throws {
        let db = try await RecordsDatabase.inMemory()
        var record = F.record(title: "CBC", documentDate: "2026-09-12", createdMs: 42, type: .labReport, favorite: true, notes: "fasting")
        record.checksumSHA256 = "abc"
        record.filePath = "\(record.id)/original.pdf"
        record.pageCount = 3
        let stored = try await db.insert(record, pages: [RecordPage(recordID: record.id, pageIndex: 0, text: "Hb 9.7", textSource: .pdfText)], tags: ["Blood", "blood", " CBC "])
        #expect(stored.seq > 0)
        var expected = record
        expected.seq = stored.seq
        expected.sortDate = "2026-09-12"
        #expect(stored == expected)
        let detail = try await db.detail(id: record.id)
        #expect(detail?.tags == ["blood", "CBC"] || detail?.tags == ["Blood", "CBC"])
        #expect(detail?.pages.first?.text == "Hb 9.7")
    }

    @Test func ftsSearchIsFoldedAndPrefixed() async throws {
        let db = try await RecordsDatabase.inMemory()
        let a = F.record(title: "Hémoglobine — Café Clinic", createdMs: 1_000)
        let b = F.record(title: "X-Ray chest", createdMs: 2_000, notes: "Dr. Müller follow-up")
        let c = F.record(title: "Invoice", createdMs: 3_000)
        try await db.insert(a)
        try await db.insert(b)
        try await db.insert(c, pages: [RecordPage(recordID: c.id, pageIndex: 0, text: "Paracetamol 500 mg", textSource: .plain)], tags: ["Pharmacy"])

        func search(_ text: String) async throws -> [String] {
            try await db.page(query: RecordQuery(text: text), after: nil, limit: 50).map(\.id)
        }
        #expect(try await search("hemoglobine") == [a.id])
        #expect(try await search("HÉMO") == [a.id])
        #expect(try await search("cafe clinic") == [a.id])
        #expect(try await search("clinic cafe") == [a.id])
        #expect(try await search("ray") == [b.id])
        #expect(try await search("x-ray") == [b.id])
        #expect(try await search("muller") == [b.id])
        #expect(try await search("paracet") == [c.id])
        #expect(try await search("pharm") == [c.id])
        #expect(try await search("\"*()") == [])
        #expect(try await search("hemoglobine invoice") == [])

        try await db.update(id: c.id, patch: RecordPatch(title: "Pharmacy bill Ñandú"))
        #expect(try await search("nandu") == [c.id])
        try await db.setTags(recordID: a.id, names: ["Anémie"])
        #expect(try await search("anemie") == [a.id])
    }

    @Test func pagingFollowsContractOrderAndIsComplete() async throws {
        let db = try await RecordsDatabase.inMemory(timeZone: TimeZone(identifier: "UTC")!)
        // sort_date = document_date, else the device-local (here UTC) day of created_ms.
        let rows = [
            F.record(id: "a", title: "a", documentDate: "2026-09-01", createdMs: F.ms("2026-09-10")),
            F.record(id: "b", title: "b", documentDate: nil, createdMs: F.ms("2026-09-05", hour: 8)),
            F.record(id: "c", title: "c", documentDate: "2026-09-05", createdMs: F.ms("2026-09-05", hour: 20)),
            F.record(id: "d", title: "d", documentDate: "2025-01-01", createdMs: F.ms("2026-09-11")),
            F.record(id: "e", title: "e", documentDate: nil, createdMs: F.ms("2026-09-12")),
            F.record(id: "f", title: "f", documentDate: "2026-09-05", createdMs: F.ms("2026-09-05", hour: 20)),
            F.record(id: "g", title: "g", documentDate: "2026-09-30", createdMs: 1, archived: true),
        ]
        for row in rows { try await db.insert(row) }
        // e (09-12) · c/f same date + created → seq DESC (f inserted later) · b (09-05, earlier created) · a · d
        let expected = ["e", "f", "c", "b", "a", "d"]
        #expect(try await db.page(query: .all, after: nil, limit: 100).map(\.id) == expected)

        var seen: [String] = []
        var cursor: RecordCursor?
        while true {
            let page = try await db.page(query: .all, after: cursor, limit: 2)
            if page.isEmpty { break }
            seen.append(contentsOf: page.map(\.id))
            cursor = RecordCursor(page.last!)
        }
        #expect(seen == expected)

        var archived = RecordQuery()
        archived.archivedOnly = true
        #expect(try await db.page(query: archived, after: nil, limit: 10).map(\.id) == ["g"])
        #expect(try await db.recent(limit: 2).map(\.id) == ["e", "d"])
    }

    @Test func sortDateUsesTheDeviceLocalImportDay() async throws {
        // 00:30 on 1 Oct in Kolkata (UTC+5:30) is still 30 Sep in UTC.
        let kolkata = TimeZone(identifier: "Asia/Kolkata")!
        let db = try await RecordsDatabase.inMemory(timeZone: kolkata)
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = kolkata
        let created = calendar.date(from: DateComponents(year: 2026, month: 10, day: 1, hour: 0, minute: 30))!
        let createdMs = Int64(created.timeIntervalSince1970 * 1000)
        #expect(RecordDates.dayString(from: created, timeZone: TimeZone(identifier: "UTC")!) == "2026-09-30")
        let record = F.record(id: "late", title: "late", createdMs: createdMs)
        let stored = try await db.insert(record)
        #expect(stored.sortDate == "2026-10-01")
        #expect(stored.monthKey == "2026-10")
        let older = F.record(id: "sep", title: "sep", documentDate: "2026-09-30", createdMs: createdMs + 1)
        try await db.insert(older)
        #expect(try await db.page(query: .all, after: nil, limit: 10).map(\.id) == ["late", "sep"])
    }

    @Test func editingDocumentDateMovesSortDateAndClearingResetsIt() async throws {
        let utc = TimeZone(identifier: "UTC")!
        let db = try await RecordsDatabase.inMemory(timeZone: utc)
        let record = F.record(id: "r", title: "r", createdMs: F.ms("2026-09-15"))
        #expect(try await db.insert(record).sortDate == "2026-09-15")
        try await db.update(id: "r", patch: RecordPatch(documentDate: .some("2024-02-29")))
        #expect(try await db.record(id: "r")?.sortDate == "2024-02-29")
        try await db.update(id: "r", patch: RecordPatch(documentDate: .some(nil)))
        #expect(try await db.record(id: "r")?.sortDate == "2026-09-15")
        // Metadata dates from the background stage set it too, but never override a user date.
        try await db.applyBasics(id: "r", pageCount: nil, thumbnailPath: nil, documentDate: "2025-05-05", dateMethod: .fileMetadata, status: .ready, error: nil, pages: nil)
        #expect(try await db.record(id: "r")?.sortDate == "2025-05-05")
        try await db.update(id: "r", patch: RecordPatch(documentDate: .some("2026-01-01")))
        try await db.applyBasics(id: "r", pageCount: nil, thumbnailPath: nil, documentDate: "2020-01-01", dateMethod: .fileMetadata, status: .ready, error: nil, pages: nil)
        #expect(try await db.record(id: "r")?.sortDate == "2026-01-01")
    }

    @Test func timelinePageUsesTheTimelineIndexWithoutTempSort() async throws {
        let db = try await RecordsDatabase.inMemory()
        for index in 0..<50 {
            try await db.insert(F.record(title: "r\(index)", documentDate: index % 3 == 0 ? "2026-0\(1 + index % 9)-10" : nil, createdMs: Int64(index) * 86_400_000))
        }
        try await db.withConnection { try $0.exec("ANALYZE") }
        let plan = try await db.pageQueryPlan(query: .all)
        #expect(plan.contains { $0.contains("idx_records_timeline") }, "plan: \(plan)")
        #expect(!plan.contains { $0.contains("TEMP B-TREE") }, "plan: \(plan)")
        var archived = RecordQuery()
        archived.archivedOnly = true
        let archivedPlan = try await db.pageQueryPlan(query: archived, after: RecordCursor(sortDate: "2026-05-01", createdMs: 0, seq: 10))
        #expect(archivedPlan.contains { $0.contains("idx_records_timeline") }, "plan: \(archivedPlan)")
        #expect(!archivedPlan.contains { $0.contains("TEMP B-TREE") }, "plan: \(archivedPlan)")
    }

    @Test func filtersCombine() async throws {
        let db = try await RecordsDatabase.inMemory()
        try await db.insert(F.record(id: "lab", title: "lab", createdMs: 3, type: .labReport, fileType: .pdf, favorite: true))
        try await db.insert(F.record(id: "rx", title: "rx", createdMs: 2, type: .prescription, fileType: .image, source: .shareIn))
        try await db.insert(F.record(id: "note", title: "note", createdMs: 1, type: .personalNote, fileType: .text, source: .openIn))
        func ids(_ query: RecordQuery) async throws -> [String] {
            try await db.page(query: query, after: nil, limit: 10).map(\.id)
        }
        var q = RecordQuery()
        q.favoritesOnly = true
        #expect(try await ids(q) == ["lab"])
        q = RecordQuery()
        q.receivedOnly = true
        #expect(try await ids(q) == ["rx", "note"])
        q = RecordQuery()
        q.fileTypes = [.image, .text]
        #expect(try await ids(q) == ["rx", "note"])
        q = RecordQuery()
        q.recordTypes = [.prescription]
        #expect(try await ids(q) == ["rx"])
        q = RecordQuery()
        q.categories = [.labReports, .personalNotes]
        #expect(try await ids(q) == ["lab", "note"])
        try await db.setFlags(ids: ["lab"], archived: true)
        #expect(try await ids(RecordQuery()) == ["rx", "note"])
    }

    @Test func deleteCascadesRowsTagsFTSAndFiles() async throws {
        let directory = try F.temporaryDirectory()
        defer { try? FileManager.default.removeItem(at: directory) }
        let db = try await RecordsDatabase.inMemory()
        let files = RecordFileStore(root: directory.appendingPathComponent("files"))
        let repository = RecordsRepository(database: db, files: files)
        let result = try await repository.importItem(RecordImportItem(payload: .text("Hemoglobin 9.7 g/dL"), source: .paste, importMethod: .pasteText, userTitle: "CBC"))
        let id = result.record.id
        try await repository.setTags(id: id, names: ["blood"])
        #expect(FileManager.default.fileExists(atPath: files.directory(for: id).path))
        #expect(try await db.page(query: RecordQuery(text: "hemoglobin"), after: nil, limit: 5).count == 1)

        try await repository.delete(ids: [id])

        #expect(try await db.record(id: id) == nil)
        #expect(try await db.pages(recordID: id).isEmpty)
        #expect(try await db.allTagNames().isEmpty)
        let ftsRows = try await db.withConnection { try $0.scalarInt64("SELECT COUNT(*) FROM records_fts") }
        #expect(ftsRows == 0)
        let tagLinks = try await db.withConnection { try $0.scalarInt64("SELECT COUNT(*) FROM record_tags") }
        #expect(tagLinks == 0)
        #expect(!FileManager.default.fileExists(atPath: files.directory(for: id).path))
    }

    @Test func updatePatchAndClearDate() async throws {
        let db = try await RecordsDatabase.inMemory()
        let record = F.record(title: "Old", createdMs: 5)
        try await db.insert(record)
        try await db.update(id: record.id, patch: RecordPatch(title: "New", recordType: .bill, category: .insuranceBills, documentDate: .some("2026-01-02"), notes: .some("n")), nowMs: 99)
        var stored = try #require(try await db.record(id: record.id))
        #expect(stored.title == "New")
        #expect(stored.recordType == .bill)
        #expect(stored.documentDate == "2026-01-02")
        #expect(stored.documentDateMethod == .user)
        #expect(stored.updatedMs == 99)
        try await db.update(id: record.id, patch: RecordPatch(documentDate: .some(nil), notes: .some(nil)))
        stored = try #require(try await db.record(id: record.id))
        #expect(stored.documentDate == nil)
        #expect(stored.notes == nil)
    }
}
