import Foundation
import PDFKit
import Testing
@testable import calorietracker

// Phase 5 "Sharing & backup" (docs/health-records.md §33–§37).

enum RecordsSharingFixtures {
    static var sharedMigrationURL: URL {
        HealthTestFixtures.repoRootURL.appendingPathComponent("shared/records/migrations/004_sharing.sql")
    }

    static var fixtureArchiveURL: URL {
        HealthTestFixtures.repoRootURL.appendingPathComponent("shared/records/fixtures/ayuvo-records-fixture.zip")
    }

    /// The rows of one archive entry (`*.ndjson` lines, or the JSON array of a `.json` entry).
    static func rows(_ reader: ZipArchiveReader, _ entry: String) throws -> [RJ] {
        guard let zipEntry = reader.entry(named: entry) else { return [] }
        let text = String(decoding: try reader.data(for: zipEntry), as: UTF8.self)
        if entry.hasSuffix(".ndjson") {
            return text.components(separatedBy: "\n").filter { !$0.isEmpty }.compactMap { RJ.parse($0) }
        }
        return RJ.parse(text)?.array ?? []
    }

    /// A store with one lab report, its page, fields, an observation and a highlight.
    @discardableResult
    static func seed(_ db: RecordsDatabase, id: String = "r1", files: RecordFileStore? = nil) async throws -> HealthRecord {
        var record = RecordsTestFixtures.record(id: id, title: "Complete Blood Count", documentDate: "2026-09-12", createdMs: 1_000, type: .labReport)
        record.notes = "Fasting sample"
        record.pageCount = 1
        if let files {
            let data = RecordsTestFixtures.pdfData(pages: 1)
            let copy = try files.writeToTemp(data, id: id)
            record.filePath = try files.finalize(copy, id: id, ext: "pdf")
            record.checksumSHA256 = copy.sha256
            record.fileSize = copy.size
        }
        let page = RecordPage(
            recordID: id, pageIndex: 0,
            text: "City Care Hospital\nPatient Name : Jane Doe\nUHID : AB123456\nPhone : +91 98765 43210\nHemoglobin 7.6 g/dL 12-16",
            textSource: .pdfText, ocrConfidence: 0.9, width: 612, height: 792,
            blocksJSON: """
            [{"t":"City Care Hospital","b":[0.1,0.05,0.5,0.03]},\
            {"t":"Patient Name : Jane Doe","b":[0.1,0.1,0.5,0.03]},\
            {"t":"UHID : AB123456","b":[0.1,0.15,0.4,0.03]},\
            {"t":"Phone : +91 98765 43210","b":[0.1,0.2,0.4,0.03]},\
            {"t":"Hemoglobin 7.6 g/dL 12-16","b":[0.1,0.3,0.6,0.03]}]
            """
        )
        try await db.insert(record, pages: [page], tags: ["cbc"])
        try await db.applyExtraction(recordID: id, RecordExtraction(fields: [
            ExtractedField(key: .facility, valueText: "City Care Hospital", confidence: 0.8, sourcePage: 0, evidence: "City Care Hospital"),
            ExtractedField(key: .doctorName, valueText: "Suresh Menon", confidence: 0.85, sourcePage: 0, evidence: "Dr. Suresh Menon"),
            ExtractedField(key: .patientName, valueText: "Jane Doe", confidence: 0.75, sourcePage: 0, evidence: "Patient Name : Jane Doe"),
            ExtractedField(key: .collectionDate, valueText: "2026-09-12", confidence: 0.9, sourcePage: 0, evidence: "Collected On : 12-Sep-2026"),
            ExtractedField(
                key: .testResult, valueText: "Hemoglobin",
                valueJSON: RJ.obj([
                    "name": .str("Hemoglobin"), "value": .str("7.6"), "value_num": .num(7.6), "unit": .str("g/dL"),
                    "ref_text": .str("12-16"), "ref_low": .num(12), "ref_high": .num(16), "flag": .str("low"),
                ]).compactJSON,
                confidence: 0.9, sourcePage: 0, evidence: "Hemoglobin 7.6 g/dL 12-16"
            ),
            ExtractedField(key: .recommendation, valueText: "Repeat CBC after treatment", confidence: 0.7, sourcePage: 0, evidence: "Repeat CBC after treatment"),
        ]))
        try await db.replaceRuleHighlights(recordID: id, [
            ExtractedHighlight(
                section: .important, text: "Hemoglobin: 7.6 g/dL — below the report reference range",
                method: .rules, provider: nil, sourcePage: 0, confidence: 0.9
            ),
        ])
        // §34 "AI highlights" uses only the validated `summary` highlight.
        try await db.applyExtraction(recordID: id, RecordExtraction(summary: ExtractedHighlight(
            section: .summary, text: "Haemoglobin is low; discuss it with your doctor.",
            method: .aiCloud, provider: "Gemini", sourcePage: 0, confidence: 0.6
        )))
        return try await db.record(id: id) ?? record
    }
}

// MARK: - §33 migration 004

struct RecordsPhase5MigrationTests {
    private typealias F = RecordsTestFixtures
    private typealias S = RecordsSharingFixtures

    @Test func embeddedMigrationMatchesSharedFileVerbatim() throws {
        let url = S.sharedMigrationURL
        guard FileManager.default.fileExists(atPath: url.path) else {
            Issue.record("shared/records/migrations/004_sharing.sql is missing")
            return
        }
        let shared = RecordsSchema.parseStatements(try String(contentsOf: url, encoding: .utf8))
        let embedded = try #require(RecordsSchema.migrations.first { $0.version == 4 })
        #expect(embedded.fileName == url.lastPathComponent)
        #expect(shared.count == embedded.statements.count)
        for (a, b) in zip(shared, embedded.statements) {
            #expect(a == b, "statement differs from 004_sharing.sql:\n\(a)")
        }
        #expect(RecordsSchema.schemaVersion == 4)
        #expect(RecordsSchema.parseStatementsStrict(try String(contentsOf: url, encoding: .utf8)) != nil)
    }

    @Test func freshInstallHasTheSharingColumnsAndBackupStateTable() async throws {
        let db = try await RecordsDatabase.inMemory()
        #expect(try await db.userVersion() == 4)
        #expect(try await db.tableNames().contains("records_backup_state"))
        let columns = try await db.tableInfo("records").map(\.name)
        #expect(columns.contains("shared_count"))
        #expect(columns.contains("last_shared_ms"))
    }

    @Test func upgradesAVersionThreeDatabaseOnDiskKeepingData() async throws {
        let directory = try F.temporaryDirectory()
        defer { try? FileManager.default.removeItem(at: directory) }
        let url = directory.appendingPathComponent("Ayuvo/Records/records.sqlite")
        let v3 = try await RecordsDatabase.open(url: url, targetVersion: 3)
        #expect(try await v3.userVersion() == 3)
        #expect(try await !v3.tableNames().contains("records_backup_state"))
        try await v3.withConnection { connection in
            try connection.run(
                "INSERT INTO records (id, title, record_type, category, source, import_method, created_ms, updated_ms, document_date, sort_date, mime_type, file_type, notes) VALUES ('old', 'CBC Aug', 'lab_report', 'lab_reports', 'import', 'file_picker', 7, 7, '2026-08-10', '2026-08-10', 'application/pdf', 'pdf', 'keep me')"
            )
            try connection.run("INSERT INTO record_pages (record_id, page_index, text, text_source) VALUES ('old', 0, 'Hemoglobin 8.4', 'pdf_text')")
        }
        await v3.close()

        let upgraded = try await RecordsDatabase.open(url: url)
        #expect(try await upgraded.userVersion() == 4)
        #expect(try await upgraded.metaValue("schema_version") == "4")
        let stored = try #require(try await upgraded.record(id: "old"))
        #expect(stored.title == "CBC Aug")
        #expect(stored.notes == "keep me")
        #expect(stored.sharedCount == 0)
        #expect(stored.lastSharedMs == nil)
        #expect(try await upgraded.pages(recordID: "old").first?.text == "Hemoglobin 8.4")
        #expect(try await upgraded.backupState().isEmpty)
        #expect(try await upgraded.integrityCheck())
        await upgraded.close()
    }

    @Test func sharedCountAndLastSharedAreBumpedOnce() async throws {
        let db = try await RecordsDatabase.inMemory()
        try await RecordsSharingFixtures.seed(db)
        try await db.markShared(ids: ["r1"], nowMs: 4_242)
        var stored = try #require(try await db.record(id: "r1"))
        #expect(stored.sharedCount == 1)
        #expect(stored.lastSharedMs == 4_242)
        try await db.markShared(ids: ["r1"], nowMs: 5_000)
        stored = try #require(try await db.record(id: "r1"))
        #expect(stored.sharedCount == 2)
        #expect(stored.lastSharedMs == 5_000)
    }

    @Test func backupStateRoundTrips() async throws {
        let db = try await RecordsDatabase.inMemory()
        try await db.setBackupState([RecordsBackupStateKey.lastArchiveMs: "17", RecordsBackupStateKey.lastArchiveRecords: "3"])
        var state = try await db.backupState()
        #expect(state[RecordsBackupStateKey.lastArchiveMs] == "17")
        #expect(state[RecordsBackupStateKey.lastArchiveRecords] == "3")
        try await db.setBackupState([RecordsBackupStateKey.lastArchiveMs: "18"])
        state = try await db.backupState()
        #expect(state[RecordsBackupStateKey.lastArchiveMs] == "18")
    }
}

// MARK: - §34 summary & redaction

struct RecordsShareCoreTests {
    @Test func summaryTextFollowsTheContractLayout() async throws {
        let db = try await RecordsDatabase.inMemory()
        try await RecordsSharingFixtures.seed(db)
        var plan = RecordSharePlan(recordIDs: ["r1"])
        plan.includeNotes = true
        plan.includeHighlights = true
        plan.summaryFields.insert(.patientName)
        let text = try await db.shareSummaryText(plan: plan)
        let lines = text.components(separatedBy: "\n")
        #expect(lines.first == "Complete Blood Count — 2026-09-12")
        #expect(lines.contains("Lab Report · City Care Hospital"))
        #expect(lines.contains("Doctor: Suresh Menon"))
        #expect(lines.contains("Patient: Jane Doe"))
        #expect(lines.contains("Dates: Collected 2026-09-12"))
        #expect(lines.contains("Results:"))
        #expect(lines.contains("- Hemoglobin: 7.6 g/dL (low, ref 12-16)"))
        #expect(lines.contains("Recommendations:"))
        #expect(lines.contains("- Repeat CBC after treatment"))
        #expect(lines.contains("Notes:"))
        #expect(lines.contains("Fasting sample"))
        #expect(lines.contains(RR.shareHighlightsHeader))
        #expect(lines.contains("- Haemoglobin is low; discuss it with your doctor."))
        #expect(lines.last == RR.shareFooter)
    }

    @Test func unselectedSummaryFieldsAreOmitted() async throws {
        let db = try await RecordsDatabase.inMemory()
        try await RecordsSharingFixtures.seed(db)
        var plan = RecordSharePlan(recordIDs: ["r1"])
        plan.summaryFields = [.testResults]
        let text = try await db.shareSummaryText(plan: plan)
        #expect(!text.contains("Doctor:"))
        #expect(!text.contains("Patient:"))
        #expect(!text.contains("Dates:"))
        #expect(!text.contains("Recommendations:"))
        #expect(text.contains("- Hemoglobin: 7.6 g/dL (low, ref 12-16)"))
        #expect(text.contains("Lab Report"))
    }

    @Test func redactionPicksTheIdentifierLines() async throws {
        let db = try await RecordsDatabase.inMemory()
        try await RecordsSharingFixtures.seed(db)
        let all = RecordRedactionClass.allCases.map(\.rawValue)
        let targets = try await db.redactionTargets(recordID: "r1", classes: all)
        #expect(targets["excluded_pages"].array?.isEmpty == true)
        let lines = targets["pages"].array?.first?["lines"].array ?? []
        let indexes = Set(lines.compactMap { $0["index"].double.map { Int($0) } })
        #expect(indexes.contains(1), "patient name line")
        #expect(indexes.contains(2), "UHID line")
        #expect(indexes.contains(3), "phone line")
        #expect(!indexes.contains(0), "the facility header is not an identifier")
        #expect(!indexes.contains(4), "the lab row is kept")
        // §34: the painted box is inflated by 2 % on every side and clamped to 0…1.
        let box = (lines.first?["box"].array ?? []).compactMap(\.double)
        #expect(box.count == 4)
        #expect(box[2] > 0.5)
    }

    @Test func redactionOnlyAppliesSelectedClasses() async throws {
        let db = try await RecordsDatabase.inMemory()
        try await RecordsSharingFixtures.seed(db)
        let nameOnly = try await db.redactionTargets(recordID: "r1", classes: ["name"])
        let lines = nameOnly["pages"].array?.first?["lines"].array ?? []
        #expect(lines.count == 1)
        #expect(lines.first?["index"].double.map { Int($0) } == 1)
        let none = try await db.redactionTargets(recordID: "r1", classes: [])
        #expect(none["pages"].array?.first?["lines"].array?.isEmpty == true)
        #expect(none["classes"].array?.isEmpty == true)
    }

    @Test func planWarningsExplainWhatIsLeftOut() async throws {
        let db = try await RecordsDatabase.inMemory()
        try await RecordsSharingFixtures.seed(db)
        var plan = RecordSharePlan(recordIDs: ["r1", "missing"])
        plan.redactions = [.name]
        let warnings = try await db.sharePlanWarnings(plan: plan)
        #expect(warnings.contains("A selected record is no longer available and was left out"))
        #expect(warnings.contains { $0.hasPrefix("The original file of") }, "the seeded record has no file")
    }

    @Test func builderProducesSummaryRedactedPdfAndPreviews() async throws {
        let directory = try RecordsTestFixtures.temporaryDirectory()
        defer { try? FileManager.default.removeItem(at: directory) }
        let files = RecordFileStore(root: directory.appendingPathComponent("files", isDirectory: true))
        let db = try await RecordsDatabase.inMemory()
        let record = try await RecordsSharingFixtures.seed(db, files: files)
        let detail = try #require(try await db.detail(id: "r1"))
        var plan = RecordSharePlan(recordIDs: ["r1"])
        plan.redactions = [.name, .patientID, .phone]
        let summary = try await db.shareSummaryText(plan: plan)
        let source = RecordShareBuilder.Source(
            record: record, pages: detail.pages, originalURL: files.url(forRelativePath: record.filePath ?? ""),
            redaction: try await db.redactionTargets(recordID: "r1", classes: plan.redactionValues),
            selectedPages: [0]
        )
        let out = directory.appendingPathComponent("share", isDirectory: true)
        let bundle = RecordShareBuilder.build(plan: plan, summary: summary, sources: [source], directory: out)
        #expect(bundle.items.count == 2)
        // §38: the summary is a plain-text file plus the share sheet's own text item.
        let summaryItem = try #require(bundle.items.first { $0.kind == .summary })
        #expect(summaryItem.url.lastPathComponent.hasSuffix(" summary.txt"))
        #expect(bundle.summaryText == summary)
        #expect(try String(contentsOf: summaryItem.url, encoding: .utf8) == summary)
        let redacted = try #require(bundle.items.first { $0.kind == .redacted })
        #expect(redacted.url.lastPathComponent.hasSuffix("-redacted.pdf"))
        #expect(redacted.byteCount > 0)
        #expect(redacted.previewPath != nil)
        #expect(bundle.sharedRecordIDs == ["r1"])
    }

    @Test func pageSubsetKeepsTheTextLayer() throws {
        let directory = try RecordsTestFixtures.temporaryDirectory()
        defer { try? FileManager.default.removeItem(at: directory) }
        let original = directory.appendingPathComponent("original.pdf")
        try RecordsTestFixtures.pdfData(pages: 4).write(to: original)
        let subset = directory.appendingPathComponent("subset.pdf")
        #expect(RecordShareBuilder.writePageSubsetPDF(originalURL: original, pages: [1, 2], to: subset))
        let document = try #require(PDFDocument(url: subset))
        #expect(document.pageCount == 2)
        #expect(document.page(at: 0)?.string?.contains("2") == true)
    }
}

// MARK: - §35 archive

struct RecordsArchiveTests {
    private typealias F = RecordsTestFixtures
    private typealias S = RecordsSharingFixtures

    private func makeStore(_ directory: URL, name: String) -> RecordFileStore {
        RecordFileStore(root: directory.appendingPathComponent(name, isDirectory: true))
    }

    @Test func exportThenImportRestoresRecordsValuesAndFiles() async throws {
        let directory = try F.temporaryDirectory()
        defer { try? FileManager.default.removeItem(at: directory) }
        let sourceFiles = makeStore(directory, name: "source")
        let source = try await RecordsDatabase.inMemory()
        try await S.seed(source, id: "r1", files: sourceFiles)
        try await S.seed(source, id: "r2", files: sourceFiles)
        try await source.setUserLink("r1", "r2", kind: .previousReport)

        let exporter = RecordsArchiveExporter(database: source, files: sourceFiles)
        let archiveURL = directory.appendingPathComponent("archive.zip")
        let result = try await exporter.export(to: archiveURL, includeFiles: true, appVersion: "1.2")
        #expect(result.manifest.format == "ayuvo-records")
        #expect(result.manifest.formatVersion == 1)
        #expect(result.manifest.schemaVersion == 4)
        #expect(result.manifest.platform == "ios")
        #expect(result.manifest.recordCount == 2)
        #expect(result.manifest.fileCount == 2)
        #expect(result.byteCount > 0)

        let reader = try ZipArchiveReader(url: archiveURL)
        let names = reader.entries.map(\.name)
        #expect(names.first == "manifest.json")
        #expect(names.last == "checksums.json")
        for entry in ["records.ndjson", "pages.ndjson", "fields.ndjson", "observations.ndjson",
                      "highlights.ndjson", "links.ndjson", "entities.ndjson", "record_entities.ndjson",
                      "tags.json", "analyte_user_aliases.json"] {
            #expect(names.contains(entry), "missing \(entry)")
        }
        #expect(names.contains { $0.hasPrefix("files/r1/") })

        let targetFiles = makeStore(directory, name: "target")
        let target = try await RecordsDatabase.inMemory()
        let importer = RecordsArchiveImporter(database: target, files: targetFiles)
        let summary = try await importer.run(url: archiveURL, mode: .merge)
        #expect(summary.importedRecords == 2)
        #expect(summary.skippedRecords == 0)
        #expect(summary.importedFiles == 2)
        #expect(summary.missingFiles == 0)
        #expect(summary.checksumWarnings.isEmpty)

        let restored = try #require(try await target.record(id: "r1"))
        #expect(restored.title == "Complete Blood Count")
        #expect(restored.sortDate == "2026-09-12")
        #expect(restored.notes == "Fasting sample")
        #expect(restored.processingStatus == .ready)
        #expect(restored.processingError == nil)
        #expect(try await target.tags(recordID: "r1") == ["cbc"])
        #expect(try await target.pages(recordID: "r1").first?.text?.contains("Hemoglobin") == true)
        let fields = try await target.fields(recordID: "r1")
        #expect(fields.contains { $0.key == .doctorName && $0.valueText == "Suresh Menon" })
        let observations = try await target.observations(recordID: "r1")
        #expect(observations.contains { $0.rawName == "Hemoglobin" && $0.valueText == "7.6" })
        #expect(try await target.highlights(recordID: "r1").count == 2, "the important and the AI summary highlight")
        #expect(try await target.link("r1", "r2") != nil)
        #expect(FileManager.default.fileExists(atPath: targetFiles.url(forRelativePath: restored.filePath ?? "").path))
        // The rebuilt FTS row finds the restored record.
        let hits = try await target.search(query: RecordQuery(), terms: ["hemoglobin"], today: "2026-09-16")
        #expect(!hits.isEmpty)
    }

    @Test func mergeSkipsRecordsAlreadyPresentAndReplaceWipesFirst() async throws {
        let directory = try F.temporaryDirectory()
        defer { try? FileManager.default.removeItem(at: directory) }
        let sourceFiles = makeStore(directory, name: "source")
        let source = try await RecordsDatabase.inMemory()
        try await S.seed(source, id: "r1", files: sourceFiles)
        let archiveURL = directory.appendingPathComponent("archive.zip")
        _ = try await RecordsArchiveExporter(database: source, files: sourceFiles).export(to: archiveURL, includeFiles: true, appVersion: "1.2")

        let targetFiles = makeStore(directory, name: "target")
        let target = try await RecordsDatabase.inMemory()
        try await S.seed(target, id: "r1", files: targetFiles)
        try await S.seed(target, id: "local", files: targetFiles)

        let importer = RecordsArchiveImporter(database: target, files: targetFiles)
        let merged = try await importer.run(url: archiveURL, mode: .merge)
        #expect(merged.importedRecords == 0)
        #expect(merged.skippedRecords == 1)
        #expect(try await target.recordCount() == 2, "merge never removes anything")

        let replaced = try await importer.run(url: archiveURL, mode: .replace)
        #expect(replaced.importedRecords == 1)
        #expect(try await target.recordCount() == 1)
        #expect(try await target.record(id: "local") == nil)
    }

    @Test func metadataOnlyArchiveMarksRecordsFileMissing() async throws {
        let directory = try F.temporaryDirectory()
        defer { try? FileManager.default.removeItem(at: directory) }
        let sourceFiles = makeStore(directory, name: "source")
        let source = try await RecordsDatabase.inMemory()
        try await S.seed(source, id: "r1", files: sourceFiles)
        let archiveURL = directory.appendingPathComponent("meta.zip")
        let result = try await RecordsArchiveExporter(database: source, files: sourceFiles)
            .export(to: archiveURL, includeFiles: false, appVersion: "1.2")
        #expect(result.manifest.fileCount == 0)

        let targetFiles = makeStore(directory, name: "target")
        let target = try await RecordsDatabase.inMemory()
        let summary = try await RecordsArchiveImporter(database: target, files: targetFiles).run(url: archiveURL, mode: .merge)
        #expect(summary.importedRecords == 1)
        #expect(summary.missingFiles == 1)
        let restored = try #require(try await target.record(id: "r1"))
        #expect(restored.filePath == nil)
        #expect(restored.processingError == "file_missing")
    }

    @Test func archivesWithANewerFormatVersionAreRejected() async throws {
        let directory = try F.temporaryDirectory()
        defer { try? FileManager.default.removeItem(at: directory) }
        let url = directory.appendingPathComponent("future.zip")
        let writer = try ZipArchiveWriter(url: url)
        let manifest = """
        {"format":"ayuvo-records","format_version":2,"schema_version":9,"app":"Ayuvo","app_version":"9","platform":"android",\
        "created_ms":1,"time_zone":"UTC","record_count":0,"file_count":0,"total_file_bytes":0}
        """
        try writer.addStored(name: "manifest.json", data: Data(manifest.utf8))
        try writer.finish()
        #expect(throws: RecordsArchiveError.needsNewerApp) {
            try RecordsArchiveImporter.inspect(url: url)
        }
    }

    @Test func nonArchiveFilesAreRejected() async throws {
        let directory = try F.temporaryDirectory()
        defer { try? FileManager.default.removeItem(at: directory) }
        let url = directory.appendingPathComponent("not-a-zip.txt")
        try Data("hello".utf8).write(to: url)
        #expect(throws: RecordsArchiveError.notAnArchive) {
            try RecordsArchiveImporter.inspect(url: url)
        }
    }

    /// The shared fixture both platforms must import, with the §35.1 contents.
    @Test func importsTheSharedFixtureArchive() async throws {
        let url = RecordsSharingFixtures.fixtureArchiveURL
        guard FileManager.default.fileExists(atPath: url.path) else {
            Issue.record("shared/records/fixtures/ayuvo-records-fixture.zip is missing")
            return
        }
        let directory = try F.temporaryDirectory()
        defer { try? FileManager.default.removeItem(at: directory) }
        let files = makeStore(directory, name: "fixture")
        let db = try await RecordsDatabase.inMemory()
        let manifest = try RecordsArchiveImporter.inspect(url: url)
        #expect(manifest.format == "ayuvo-records")
        #expect(manifest.formatVersion == 1)
        #expect(manifest.schemaVersion == 4)
        #expect(manifest.platform == "android")
        #expect(manifest.appVersion == "1.4")
        #expect(manifest.createdMs == 1_757_462_400_000)
        #expect(manifest.timeZone == "Asia/Kolkata")
        #expect(manifest.recordCount == 4)
        #expect(manifest.fileCount == 7)
        #expect(manifest.totalFileBytes == 2_922)

        let summary = try await RecordsArchiveImporter(database: db, files: files).run(url: url, mode: .merge)
        #expect(summary.importedRecords == 4)
        #expect(summary.skippedRecords == 0)
        #expect(summary.importedFiles == 7)
        #expect(summary.missingFiles == 0)
        #expect(summary.checksumWarnings.isEmpty, "fixture checksums match")

        // §35.1 contents.
        #expect(try await db.recordCount() == 4)
        let counts = try await db.archiveRowCounts()
        #expect(counts["record_pages"] == 4)
        #expect(counts["record_fields"] == 32)
        #expect(counts["observations"] == 8)
        #expect(counts["record_highlights"] == 10)
        #expect(counts["record_links"] == 2)
        #expect(counts["entities"] == 4)
        #expect(counts["record_entities"] == 7)
        #expect(counts["tags"] == 2)
        #expect(counts["analyte_user_aliases"] == 1)

        let cbc = try #require(try await db.record(id: "rec-cbc-0001"))
        #expect(cbc.title == "Complete Blood Count")
        #expect(cbc.recordType == .labReport)
        #expect(cbc.sortDate == "2026-09-13")
        #expect(cbc.favorite)
        #expect(cbc.reviewStatus == .needsReview)
        #expect(cbc.sharedCount == 1)
        #expect(cbc.processingStatus == .ready)
        #expect(try await db.record(id: "rec-lipid-0004")?.archived == true)
        #expect(try await db.record(id: "rec-note-0003")?.recordType == .personalNote)
        #expect(try await db.observations(recordID: "rec-lipid-0004").count == 5)
        #expect(try await db.link("rec-cbc-0001", "rec-rx-0002")?.kind == .prescriptionFor)
        #expect(try await db.link("rec-cbc-0001", "rec-note-0003")?.origin == .user)
        #expect(try await db.userAliases()["hb estimation"] == "hemoglobin")
        #expect(FileManager.default.fileExists(atPath: files.url(forRelativePath: cbc.filePath ?? "").path))

        // Round-trip: exporting the imported store reproduces the same rows.
        let again = directory.appendingPathComponent("round-trip.zip")
        let exported = try await RecordsArchiveExporter(database: db, files: files)
            .export(to: again, includeFiles: true, appVersion: "1.4")
        #expect(exported.manifest.recordCount == 4)
        #expect(exported.manifest.fileCount == 7)
        let original = try ZipArchiveReader(url: url)
        let copy = try ZipArchiveReader(url: again)
        // Rows compare structurally (§9.2: numbers by value, object key order irrelevant). Bytes
        // cannot match exactly: SQLite REAL columns lose the archive's int/float distinction
        // (the fixture holds both `value_num: 96` and `ref_high: 17.0` in REAL columns).
        for entry in ["records.ndjson", "pages.ndjson", "fields.ndjson", "observations.ndjson", "highlights.ndjson",
                      "links.ndjson", "entities.ndjson", "record_entities.ndjson", "tags.json", "analyte_user_aliases.json"] {
            let a = try RecordsSharingFixtures.rows(original, entry)
            let b = try RecordsSharingFixtures.rows(copy, entry)
            #expect(a.count == b.count, "\(entry) has \(b.count) rows, expected \(a.count)")
            for (index, pair) in zip(a, b).enumerated() {
                let diff = RecordsVectorTests.firstDifference(pair.1, pair.0)
                #expect(diff == nil, "\(entry) row \(index): \(diff ?? "")")
            }
        }

        let target = try await RecordsDatabase.inMemory()
        let targetFiles = makeStore(directory, name: "round-trip")
        let back = try await RecordsArchiveImporter(database: target, files: targetFiles).run(url: again, mode: .merge)
        #expect(back.importedRecords == 4)
        #expect(back.checksumWarnings.isEmpty)
    }
}

// MARK: - Shared vectors (share_summary.json, redaction.json, archive.json)

/// The Phase 5 vectors run through the same pure port the app calls. They are written by the
/// reference (`scripts/records_reference.py`); until a file lands the case is reported as skipped.
struct RecordsShareVectorTests {
    static let files = ["share_summary", "redaction", "archive"]

    @Test(arguments: files)
    func shareVectorFileMatchesReference(_ name: String) throws {
        let url = RecordsVectorTests.vectorsDirectory.appendingPathComponent("\(name).json")
        guard FileManager.default.fileExists(atPath: url.path) else {
            print("RECORDS-VECTORS \(name).json not shipped yet — skipped")
            return
        }
        let root = try #require(RJ.parse(try String(contentsOf: url, encoding: .utf8)), "unreadable \(name).json")
        let function = try #require(root["function"].string)
        let cases = root["cases"].array ?? []
        #expect(!cases.isEmpty)
        var passed = 0
        var failures: [String] = []
        for c in cases {
            let actual = RR.runShareCase(function: function, input: c["input"], fixtures: root["fixtures"])
            if let diff = RecordsVectorTests.firstDifference(actual, c["expected"]) {
                failures.append("\(c["name"].string ?? "?"): \(diff)")
            } else {
                passed += 1
            }
        }
        print("RECORDS-VECTORS \(name).json \(passed)/\(cases.count)")
        #expect(failures.isEmpty, Comment(rawValue: "\(name).json \(passed)/\(cases.count) passed\n" + failures.joined(separator: "\n")))
    }
}

// MARK: - §37 storage

struct RecordsStorageTests {
    private typealias F = RecordsTestFixtures

    @Test func storageRowsAndCountsMatchTheStore() async throws {
        let directory = try F.temporaryDirectory()
        defer { try? FileManager.default.removeItem(at: directory) }
        let files = RecordFileStore(root: directory.appendingPathComponent("files", isDirectory: true))
        let db = try await RecordsDatabase.inMemory()
        try await RecordsSharingFixtures.seed(db, id: "r1", files: files)
        let rows = try await db.storageRows()
        #expect(rows.count == 1)
        #expect(rows.first?.fileType == .pdf)
        let bytes = files.size(ofRelativePath: try #require(rows.first?.filePath))
        #expect(bytes > 0)
        #expect(try await db.pageRowCount() == 1)
        #expect(try await db.allRecordIDs() == ["r1"])
    }

    @Test func rebuildingTheSearchIndexKeepsResults() async throws {
        let db = try await RecordsDatabase.inMemory()
        try await RecordsSharingFixtures.seed(db)
        try await db.rebuildAllFTSRows()
        let hits = try await db.search(query: RecordQuery(), terms: ["hemoglobin"], today: "2026-09-16")
        #expect(hits.contains { $0.record.id == "r1" })
    }

    @Test func findDuplicatesRecordsNewCandidates() async throws {
        let db = try await RecordsDatabase.inMemory()
        try await RecordsSharingFixtures.seed(db, id: "a")
        try await RecordsSharingFixtures.seed(db, id: "b")
        // Identical page text and pHash: §15 near duplicates.
        try await db.setSignatures(id: "a", phash: "0f0f0f0f0f0f0f0f", textSignature: RecordNearDuplicate.textSignature(text: "Hemoglobin 7.6 g/dL 12-16"))
        try await db.setSignatures(id: "b", phash: "0f0f0f0f0f0f0f0f", textSignature: RecordNearDuplicate.textSignature(text: "Hemoglobin 7.6 g/dL 12-16"))
        let found = try await db.rescanNearDuplicates()
        #expect(found == 1)
        #expect(try await db.rescanNearDuplicates() == 0, "candidates are never duplicated")
        #expect(try await db.record(id: "a")?.reviewStatus == .needsReview)
    }
}
