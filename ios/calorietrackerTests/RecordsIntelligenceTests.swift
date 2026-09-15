import Foundation
import Testing
import UIKit
@testable import calorietracker

enum RecordsIntelligenceFixtures {
    static var sharedMigrationURL: URL {
        HealthTestFixtures.repoRootURL.appendingPathComponent("shared/records/migrations/002_intelligence.sql")
    }

    struct Row {
        var name: String
        var value: String
        var unit: String
        var range: String
    }

    static let cbcRows = [
        Row(name: "Hemoglobin", value: "9.7", unit: "g/dL", range: "13.0 - 17.0"),
        Row(name: "Total WBC Count", value: "6200", unit: "cells/cumm", range: "4000 - 11000"),
        Row(name: "Platelet Count", value: "450", unit: "10^3/µL", range: "150 - 410"),
    ]

    /// Text-layer lab report with table columns (row joining → two spaces between columns).
    static func labPDF(rows: [Row] = cbcRows, date: String = "12/09/2026", pages: Int = 1, facility: String = "City Diagnostics Laboratory", doctorLine: String? = "Ref. by: Dr. Suresh Menon") -> Data {
        let renderer = UIGraphicsPDFRenderer(bounds: CGRect(x: 0, y: 0, width: 612, height: 792))
        return renderer.pdfData { context in
            for page in 0..<pages {
                context.beginPage()
                let bold: [NSAttributedString.Key: Any] = [.font: UIFont.boldSystemFont(ofSize: 20)]
                let body: [NSAttributedString.Key: Any] = [.font: UIFont.systemFont(ofSize: 12)]
                (facility as NSString).draw(at: CGPoint(x: 60, y: 40), withAttributes: bold)
                ("Complete Blood Count" as NSString).draw(at: CGPoint(x: 60, y: 72), withAttributes: bold)
                ("Patient Name: Asha Rao" as NSString).draw(at: CGPoint(x: 60, y: 110), withAttributes: body)
                ("Age: 34 Y" as NSString).draw(at: CGPoint(x: 300, y: 110), withAttributes: body)
                ("Sex: Female" as NSString).draw(at: CGPoint(x: 420, y: 110), withAttributes: body)
                (doctorLine as NSString?)?.draw(at: CGPoint(x: 60, y: 130), withAttributes: body)
                ("Collected: \(date)" as NSString).draw(at: CGPoint(x: 60, y: 150), withAttributes: body)
                ("Reported: \(date)" as NSString).draw(at: CGPoint(x: 300, y: 150), withAttributes: body)
                ("Test" as NSString).draw(at: CGPoint(x: 60, y: 190), withAttributes: bold)
                ("Result" as NSString).draw(at: CGPoint(x: 250, y: 190), withAttributes: bold)
                ("Units" as NSString).draw(at: CGPoint(x: 340, y: 190), withAttributes: bold)
                ("Reference Range" as NSString).draw(at: CGPoint(x: 440, y: 190), withAttributes: bold)
                for (index, row) in rows.enumerated() {
                    let y = 220 + CGFloat(index) * 24
                    (row.name as NSString).draw(at: CGPoint(x: 60, y: y), withAttributes: body)
                    (row.value as NSString).draw(at: CGPoint(x: 250, y: y), withAttributes: body)
                    (row.unit as NSString).draw(at: CGPoint(x: 340, y: y), withAttributes: body)
                    (row.range as NSString).draw(at: CGPoint(x: 440, y: y), withAttributes: body)
                }
                ("Page \(page + 1) of \(pages)" as NSString).draw(at: CGPoint(x: 260, y: 760), withAttributes: body)
            }
        }
    }

    static func stubEnvironment(cloud: Bool = false, local: Bool = false) -> RecordsAIEnvironment {
        RecordsAIEnvironment(
            appleIntelligenceAvailable: local, gemmaInstalled: false,
            textProvider: .gemini, textProviderReady: cloud, visionProvider: .gemini, visionProviderReady: cloud
        )
    }

    static func dependencies(
        mode: RecordsAIMode?,
        environment: RecordsAIEnvironment = stubEnvironment(),
        transport: RecordsAITransport = FailingTransport(error: .unavailable),
        clock: TestClock = TestClock()
    ) -> RecordProcessingQueue.Dependencies {
        var dependencies = RecordProcessingQueue.Dependencies()
        dependencies.environment = { environment }
        dependencies.aiMode = { mode }
        dependencies.isOnline = { true }
        dependencies.backgroundTasks = NoBackgroundTasks()
        dependencies.today = { "2026-09-15" }
        dependencies.dateOrder = { .dmy }
        dependencies.now = { clock.now }
        dependencies.aiExtractor = RecordsAIExtractor(transport: transport)
        dependencies.schedulesWakeups = false
        return dependencies
    }

    static func importPDF(_ data: Data, repository: RecordsRepository, name: String = "report.pdf") async throws -> HealthRecord {
        let result = try await repository.importItem(RecordImportItem(payload: .data(data), source: .import, importMethod: .filePicker, originalFilename: name))
        return await repository.processBasics(result.record) ?? result.record
    }
}

nonisolated final class TestClock: @unchecked Sendable {
    private let lock = NSLock()
    private var value: Int64 = 1_800_000_000_000
    var now: Int64 {
        lock.lock(); defer { lock.unlock() }
        return value
    }
    func advance(ms: Int64) {
        lock.lock(); value += ms; lock.unlock()
    }
}

nonisolated struct FailingTransport: RecordsAITransport {
    var error: RecordsAIError
    func complete(prompt: String, images: [Data], engine: RecordsAIEngine, maxOutputTokens: Int) async throws -> String {
        throw error
    }
}

nonisolated final class ScriptedTransport: RecordsAITransport, @unchecked Sendable {
    private let lock = NSLock()
    var responses: [Result<String, RecordsAIError>]
    private(set) var prompts: [String] = []
    private(set) var tokenLimits: [Int] = []

    init(_ responses: [Result<String, RecordsAIError>]) {
        self.responses = responses
    }

    func complete(prompt: String, images: [Data], engine: RecordsAIEngine, maxOutputTokens: Int) async throws -> String {
        lock.lock()
        prompts.append(prompt)
        tokenLimits.append(maxOutputTokens)
        let next = responses.isEmpty ? .failure(.failed("no response")) : responses.removeFirst()
        lock.unlock()
        return try next.get()
    }

    var callCount: Int {
        lock.lock(); defer { lock.unlock() }
        return prompts.count
    }
}

// MARK: - Schema v2

struct RecordsMigrationTests {
    private typealias F = RecordsTestFixtures
    private typealias I = RecordsIntelligenceFixtures

    @Test func embeddedMigrationMatchesSharedFileVerbatim() throws {
        let url = I.sharedMigrationURL
        guard FileManager.default.fileExists(atPath: url.path) else {
            Issue.record("shared/records/migrations/002_intelligence.sql is missing")
            return
        }
        let shared = RecordsSchema.parseStatements(try String(contentsOf: url, encoding: .utf8))
        let embedded = try #require(RecordsSchema.migrations.first { $0.version == 2 })
        #expect(embedded.fileName == url.lastPathComponent)
        #expect(shared.count == embedded.statements.count)
        for (a, b) in zip(shared, embedded.statements) {
            #expect(a == b, "statement differs from 002_intelligence.sql:\n\(a)")
        }
        #expect(RecordsSchema.schemaVersion == RecordsSchema.migrations.map(\.version).max())
    }

    @Test func freshInstallEqualsSchemaThenMigrationFromSharedFiles() async throws {
        let embedded = try await RecordsDatabase.inMemory()
        let fromFiles = try await RecordsDatabase.inMemory(targetVersion: 0)
        let schema = try String(contentsOf: F.sharedSchemaURL, encoding: .utf8)
        let migration = try String(contentsOf: I.sharedMigrationURL, encoding: .utf8)
        try await fromFiles.withConnection { connection in
            try connection.exec(schema)
            try connection.exec(migration)
        }
        #expect(try await embedded.tableNames() == fromFiles.tableNames())
        #expect(try await embedded.indexNames() == fromFiles.indexNames())
        for table in RecordsSchema.tableNames {
            #expect(try await embedded.tableInfo(table) == fromFiles.tableInfo(table), "table \(table) differs")
        }
        #expect(try await embedded.userVersion() == 2)
        #expect(try await embedded.metaValue("schema_version") == "2")
        #expect(try await embedded.tableNames() == RecordsSchema.tableNames.sorted())
        #expect(try await embedded.indexNames() == RecordsSchema.indexNames.sorted())
    }

    @Test func upgradesAVersionOneDatabaseOnDiskKeepingData() async throws {
        let directory = try F.temporaryDirectory()
        defer { try? FileManager.default.removeItem(at: directory) }
        let url = directory.appendingPathComponent("Ayuvo/Records/records.sqlite")
        let v1 = try await RecordsDatabase.open(url: url, targetVersion: 1)
        #expect(try await v1.userVersion() == 1)
        #expect(try await !v1.tableNames().contains("record_fields"))
        let record = F.record(id: "old", title: "CBC Jul", documentDate: "2026-07-18", createdMs: 5, type: .labReport, notes: "fasting")
        try await v1.withConnection { connection in
            try connection.run(
                "INSERT INTO records (id, title, record_type, category, source, import_method, created_ms, updated_ms, document_date, sort_date, mime_type, file_type, notes) VALUES (?, ?, 'lab_report', 'lab_reports', 'import', 'file_picker', 5, 5, '2026-07-18', '2026-07-18', 'application/pdf', 'pdf', 'fasting')",
                [.text(record.id), .text(record.title)]
            )
            try connection.run("INSERT INTO record_pages (record_id, page_index, text, text_source) VALUES ('old', 0, 'Hemoglobin 9.7', 'pdf_text')")
        }
        await v1.close()

        let upgraded = try await RecordsDatabase.open(url: url)
        #expect(try await upgraded.userVersion() == 2)
        #expect(try await upgraded.metaValue("schema_version") == "2")
        let stored = try #require(try await upgraded.record(id: "old"))
        #expect(stored.title == "CBC Jul")
        #expect(stored.notes == "fasting")
        #expect(stored.aiModeUsed == .none)
        #expect(stored.typeMethod == nil)
        #expect(try await upgraded.pages(recordID: "old").first?.text == "Hemoglobin 9.7")
        #expect(try await upgraded.integrityCheck())
        // The one-time backfill queues Phase 1 records exactly once.
        #expect(try await upgraded.backfillProcessingJobsIfNeeded() == 1)
        #expect(try await upgraded.job(recordID: "old")?.stage == .text)
        #expect(try await upgraded.backfillProcessingJobsIfNeeded() == 0)
        await upgraded.close()
    }
}

// MARK: - applyExtraction, FTS, search

struct RecordsApplyExtractionTests {
    private typealias F = RecordsTestFixtures

    @Test func neverOverwritesLockedRowsAndUpgradesOnlyWithHigherConfidence() async throws {
        let db = try await RecordsDatabase.inMemory()
        var record = F.record(id: "r", title: "blood report", createdMs: 1, type: .other)
        record.originalFilename = "blood_report.pdf"
        record.documentDate = nil
        record.documentDateMethod = nil
        record.documentDatePrecision = nil
        try await db.insert(record)

        try await db.applyExtraction(recordID: "r", RecordExtraction(fields: [
            ExtractedField(key: .doctorName, valueText: "Suresh Menon", confidence: 0.7, sourcePage: 0, evidence: "Dr. Suresh Menon"),
            ExtractedField(key: .reportDate, valueText: "2026-09-12", confidence: 0.9, sourcePage: 0),
            ExtractedField(key: .reportName, valueText: "Complete Blood Count", confidence: 0.7, sourcePage: 0),
        ]))
        var fields = try await db.fields(recordID: "r")
        #expect(fields.count == 3)
        var stored = try #require(try await db.record(id: "r"))
        #expect(stored.title == "Complete Blood Count", "import-derived title follows report_name")
        #expect(stored.documentDate == "2026-09-12")
        #expect(stored.sortDate == "2026-09-12")
        #expect(stored.documentDateMethod == .rules)

        // Higher confidence, same normalized value → updated in place.
        try await db.applyExtraction(recordID: "r", RecordExtraction(fields: [
            ExtractedField(key: .doctorName, valueText: "SURESH  MENON.", method: .aiCloud, confidence: 0.85, sourcePage: 1),
        ]))
        fields = try await db.fields(recordID: "r")
        let doctor = try #require(fields.first { $0.key == .doctorName })
        #expect(fields.filter { $0.key == .doctorName }.count == 1)
        #expect(doctor.confidence == 0.85)
        #expect(doctor.method == .aiCloud)

        // Lower confidence → unchanged.
        try await db.applyExtraction(recordID: "r", RecordExtraction(fields: [
            ExtractedField(key: .doctorName, valueText: "Suresh Menon", confidence: 0.5),
        ]))
        #expect(try await db.field(id: doctor.id)?.confidence == 0.85)

        // Rejected / confirmed / user rows are never touched or duplicated.
        try await db.setFieldState(fieldID: doctor.id, state: .rejected)
        try await db.applyExtraction(recordID: "r", RecordExtraction(fields: [
            ExtractedField(key: .doctorName, valueText: "Suresh Menon", confidence: 0.95),
        ]))
        fields = try await db.fields(recordID: "r")
        #expect(fields.filter { $0.key == .doctorName }.map(\.state) == [.rejected])
        #expect(fields.first { $0.key == .doctorName }?.confidence == 0.85)

        // A different value for a single-valued key is a conflict, not an overwrite.
        try await db.applyExtraction(recordID: "r", RecordExtraction(fields: [
            ExtractedField(key: .reportDate, valueText: "2026-09-13", confidence: 0.95),
        ]))
        fields = try await db.fields(recordID: "r")
        #expect(fields.filter { $0.key == .reportDate }.count == 2)
        #expect(RecordReviewEvaluator.conflicts(fields)["report_date"]?.count == 2)
        stored = try #require(try await db.record(id: "r"))
        #expect(stored.documentDate == "2026-09-12", "document date only fills an unknown / metadata date")

        // User edits are never replaced by derived columns.
        try await db.update(id: "r", patch: RecordPatch(title: "My CBC", recordType: .labReport))
        try await db.applyExtraction(recordID: "r", RecordExtraction(fields: [
            ExtractedField(key: .reportName, valueText: "Haemogram", confidence: 0.9),
        ], recordType: .prescription, typeConfidence: 0.9, typeMethod: .aiCloud))
        stored = try #require(try await db.record(id: "r"))
        #expect(stored.title == "My CBC")
        #expect(stored.recordType == .labReport)
        #expect(stored.typeMethod == .user)
    }

    @Test func ftsIndexesAllColumnsFoldedWithoutRejectedFields() async throws {
        let db = try await RecordsDatabase.inMemory()
        let record = F.record(id: "r", title: "Report", createdMs: 1, type: .labReport)
        try await db.insert(record, pages: [RecordPage(recordID: "r", pageIndex: 0, text: "Body text only", textSource: .pdfText)])
        try await db.applyExtraction(recordID: "r", RecordExtraction(fields: [
            ExtractedField(key: .doctorName, valueText: "Müller", confidence: 0.9),
            ExtractedField(key: .diagnosis, valueText: "Anémia", confidence: 0.9),
            ExtractedField(key: .facility, valueText: "Wrong Clinic", confidence: 0.6),
        ]))
        try await db.replaceRuleHighlights(recordID: "r", [ExtractedHighlight(section: .important, text: "Ferritin low", method: .rules, sourcePage: 0, confidence: 0.9)])
        let wrong = try #require(try await db.fields(recordID: "r").first { $0.key == .facility })
        try await db.setFieldState(fieldID: wrong.id, state: .rejected)
        let row = try await db.withConnection { connection -> [String] in
            var values: [String] = []
            try connection.query("SELECT title, people, clinical, body, notes_tags, highlights FROM records_fts WHERE docid=(SELECT seq FROM records WHERE id='r')") { s in
                values = (0..<6).map { s.text(Int32($0)) ?? "" }
            }
            return values
        }
        #expect(row[0] == "report")
        #expect(row[1] == "muller")
        #expect(row[2].contains("anemia"))
        #expect(row[2].contains("lab report"))
        #expect(row[3] == "body text only")
        #expect(row[5] == "ferritin low")
        #expect(!row[1].contains("wrong"))
    }

    @Test func rankedSearchWeightsColumnsFiltersFlagsAndSnippets() async throws {
        let db = try await RecordsDatabase.inMemory(timeZone: TimeZone(identifier: "UTC")!)
        try await db.insert(F.record(id: "title", title: "Hemoglobin trend", documentDate: "2025-01-01", createdMs: 1))
        try await db.insert(F.record(id: "body", title: "CBC", documentDate: "2026-09-10", createdMs: 2, type: .labReport),
                            pages: [RecordPage(recordID: "body", pageIndex: 0, text: "Some words then hemoglobin appears in the body text", textSource: .pdfText)])
        let abnormal = RecordTestResultValue(name: "Hemoglobin", value: "9.7", valueNum: 9.7, unit: "g/dL", refLow: 13, refHigh: 17, flag: .low)
        try await db.applyExtraction(recordID: "body", RecordExtraction(fields: [
            ExtractedField(key: .testResult, valueText: "Hemoglobin", valueJSON: RecordsJSON.encode(abnormal), confidence: 0.9),
        ]))
        try await db.insert(F.record(id: "bodyonly", title: "Notes", documentDate: "2025-01-01", createdMs: 3),
                            pages: [RecordPage(recordID: "bodyonly", pageIndex: 0, text: "Some words then hemoglobin appears in the body text", textSource: .pdfText)])
        let hits = try await db.search(query: RecordQuery(), terms: ["hemo"], today: "2026-09-15")
        #expect(Set(hits.map(\.record.id)) == ["title", "body", "bodyonly"])
        let order = hits.map(\.record.id)
        #expect(order.firstIndex(of: "title")! < order.firstIndex(of: "bodyonly")!, "title weight 5 outranks body weight 1 at equal recency")
        #expect(hits.allSatisfy { $0.snippet?.contains("[") == true })
        var flagged = RecordQuery()
        flagged.flags = [.abnormal]
        #expect(try await db.search(query: flagged, terms: ["hemo"], today: "2026-09-15").map(\.record.id) == ["body"])
        #expect(try await db.page(query: flagged, after: nil, limit: 10).map(\.id) == ["body"])
        var dated = RecordQuery()
        dated.dateFrom = "2026-01-01"
        #expect(try await db.page(query: dated, after: nil, limit: 10).map(\.id) == ["body"])
    }

    @Test func doctorPrefixFilterMatchesFoldedWords() {
        #expect(RecordsDatabase.wordsPrefixMatch(words: ["suresh", "menon"], wanted: ["men"]))
        #expect(RecordsDatabase.wordsPrefixMatch(words: ["suresh", "menon"], wanted: ["sur", "men"]))
        #expect(!RecordsDatabase.wordsPrefixMatch(words: ["suresh", "menon"], wanted: ["rao"]))
    }

    @Test func bm25HandlesEmptyAndMalformedMatchInfo() {
        #expect(RecordsDatabase.bm25(matchInfo: [], weights: [1]) == 0)
        #expect(RecordsDatabase.bm25(matchInfo: [1, 1, 5], weights: [1]) == 0)
    }
}

// MARK: - Pipeline

@Suite(.serialized)
struct RecordsPipelineTests {
    private typealias I = RecordsIntelligenceFixtures

    private func makeRepository() async throws -> (RecordsRepository, URL) {
        let directory = try RecordsTestFixtures.temporaryDirectory()
        let db = try await RecordsDatabase.inMemory()
        return (RecordsRepository(database: db, files: RecordFileStore(root: directory.appendingPathComponent("files"))), directory)
    }

    @Test func labPDFFlowsThroughEveryStage() async throws {
        let (repository, directory) = try await makeRepository()
        defer { try? FileManager.default.removeItem(at: directory) }
        let record = try await I.importPDF(I.labPDF(), repository: repository, name: "scan_0001.pdf")
        let queue = RecordProcessingQueue(repository: repository, dependencies: I.dependencies(mode: .off))
        await queue.enqueue(ids: [record.id])
        await queue.drain()

        let db = repository.database
        let detail = try #require(try await db.detail(id: record.id))
        #expect(detail.job?.stage == .done)
        #expect(detail.record.processingStatus == .ready)
        #expect(detail.record.recordType == .labReport)
        #expect(detail.record.typeMethod == .rules)
        #expect(detail.pages.first?.textSource == .pdfText)
        #expect(detail.pages.first?.blocksJSON?.contains("\"b\"") == true)
        let text = try #require(detail.pages.first?.text)
        #expect(text.contains("Hemoglobin  9.7  g/dL  13.0 - 17.0"), "row joining keeps table columns: \(text)")
        let results = detail.fields.filter { $0.key == .testResult }
        #expect(results.count == 3, "fields: \(detail.fields.map { "\($0.key.rawValue)=\($0.valueText)" })")
        let hemoglobin = try #require(results.first { $0.valueText == "Hemoglobin" }?.testResult)
        #expect(hemoglobin.flag == .low)
        #expect(hemoglobin.refLow == 13 && hemoglobin.refHigh == 17)
        #expect(results.first { $0.valueText == "Platelet Count" }?.testResult?.flag == .high)
        #expect(detail.fields.contains { $0.key == .collectionDate && $0.valueText == "2026-09-12" })
        #expect(detail.fields.contains { $0.key == .reportDate && $0.valueText == "2026-09-12" })
        #expect(detail.fields.contains { $0.key == .facility && $0.valueText == "City Diagnostics Laboratory" })
        #expect(detail.fields.contains { $0.key == .doctorName && $0.valueText == "Suresh Menon" && $0.isRoleReferrer })
        #expect(detail.fields.contains { $0.key == .patientName && $0.valueText == "Asha Rao" })
        #expect(detail.record.documentDate == "2026-09-12")
        #expect(detail.record.title == "Complete Blood Count")
        let important = detail.highlights.filter { $0.section == .important }.map(\.text)
        #expect(important.first == "Hemoglobin: 9.7 g/dL — below the report reference range")
        #expect(important.contains { $0.hasPrefix("Platelet Count: 450") && $0.hasSuffix("above the report reference range") })
        #expect(detail.record.phash?.count == 16)
        #expect(detail.record.textSignature?.split(separator: ",").count == 64)
        #expect(detail.record.aiModeUsed == .none)
        let hits = try await db.search(query: RecordQuery(), terms: ["menon"], today: "2026-09-15")
        #expect(hits.map(\.record.id) == [record.id])
        let parsed = RecordQueryParser.parse("abnormal reports", today: "2026-09-15")
        #expect(try await db.page(query: parsed.applied(to: RecordQuery()), after: nil, limit: 10).map(\.id) == [record.id])
    }

    @Test func askModeWaitsForConsentThenRunsCloudAIWithTokenOverride() async throws {
        let (repository, directory) = try await makeRepository()
        defer { try? FileManager.default.removeItem(at: directory) }
        let rows = [I.Row(name: "Serum Ferritin", value: "8", unit: "ng/mL", range: "15 - 150")]
        let pdf = I.labPDF(rows: rows, facility: "Sunrise Health", doctorLine: nil)
        let record = try await I.importPDF(pdf, repository: repository)
        let response = """
        ```json
        {"record_type":"lab_report","record_type_confidence":0.8,"report_name":null,"dates":[],
         "fields":[
          {"key":"facility","value":"Sunrise Health","source_page":1,"evidence":"Sunrise Health","confidence":0.97},
          {"key":"diagnosis","value":"Iron deficiency","source_page":1,"evidence":"not on the page","confidence":0.8},
          {"key":"patient_age","value":"43","source_page":1,"evidence":"Age: 34 Y","confidence":0.8},
          {"key":"department","value":"Pathology","evidence":"Pathology","confidence":0.8}
         ],"test_results":[],"medications":[],
         "summary":{"text":"Ferritin is 8 ng/mL, below the 15 - 150 range on this report.","source_pages":[1]}}
        ```
        """
        let transport = ScriptedTransport([.success(response)])
        let environment = I.stubEnvironment(cloud: true)
        let queue = RecordProcessingQueue(repository: repository, dependencies: I.dependencies(mode: .ask, environment: environment, transport: transport))
        await queue.enqueue(ids: [record.id])
        await queue.drain()
        let db = repository.database
        var detail = try #require(try await db.detail(id: record.id))
        #expect(detail.job?.awaitingConsent == true)
        #expect(detail.record.processingStatus == .aiPendingConsent)
        #expect(!detail.highlights.isEmpty, "non-AI stages still ran")
        #expect(transport.callCount == 0)
        #expect(try await db.awaitingConsentRecordIDs() == [record.id])

        await queue.decide(ids: [record.id], request: .cloud)
        await queue.drain()
        detail = try #require(try await db.detail(id: record.id))
        #expect(transport.callCount == 1)
        #expect(transport.tokenLimits == [3_000])
        #expect(detail.job?.awaitingConsent == false)
        #expect(detail.record.aiModeUsed == .cloud)
        #expect(detail.record.aiProvider == AIProvider.gemini.displayName)
        #expect(detail.record.processingStatus == .ready)
        let facility = try #require(detail.fields.first { $0.key == .facility })
        #expect(facility.confidence == 0.9, "AI confidence is clamped to 0.9")
        #expect(facility.method == .aiCloud)
        #expect(!detail.fields.contains { $0.key == .diagnosis }, "evidence not on the page is dropped")
        #expect(!detail.fields.contains { $0.key == .patientAge && $0.valueText == "43" }, "numbers not in the evidence are dropped")
        #expect(!detail.fields.contains { $0.key == .department }, "items without source_page are dropped")
        let summary = try #require(detail.highlights.first { $0.section == .summary })
        #expect(summary.method == .aiCloud)
    }

    @Test func notNowClearsTheWaitAndFailuresRetryThenContinueWithoutAI() async throws {
        let (repository, directory) = try await makeRepository()
        defer { try? FileManager.default.removeItem(at: directory) }
        let clock = TestClock()
        let transport = ScriptedTransport([.failure(.failed("500")), .failure(.busy), .failure(.failed("500")), .failure(.failed("500"))])
        let text = try await repository.importItem(RecordImportItem(payload: .text("Some note without any details at all, just words."), source: .paste, importMethod: .pasteText))
        let other = try await repository.importItem(RecordImportItem(payload: .text("Another plain note that has nothing structured inside."), source: .paste, importMethod: .pasteText))
        let queue = RecordProcessingQueue(repository: repository, dependencies: I.dependencies(mode: .ask, environment: I.stubEnvironment(cloud: true), transport: transport, clock: clock))
        await queue.enqueue(ids: [text.record.id, other.record.id])
        await queue.drain()
        let db = repository.database
        #expect(try await db.awaitingConsentRecordIDs().count == 2)

        await queue.decide(ids: [other.record.id], request: .none)
        #expect(try await db.record(id: other.record.id)?.processingStatus == .ready)
        #expect(try await db.job(recordID: other.record.id)?.awaitingConsent == false)

        await queue.decide(ids: [text.record.id], request: .cloud)
        await queue.drain()
        var job = try #require(try await db.job(recordID: text.record.id))
        #expect(job.stage == .ai)
        #expect(job.attempts == 1)
        #expect(job.nextAttemptMs == clock.now + 30_000)

        clock.advance(ms: 30_000)
        await queue.drain()
        job = try #require(try await db.job(recordID: text.record.id))
        #expect(job.attempts == 1, "busy local model waits without counting an attempt")
        #expect(job.nextAttemptMs == clock.now + 20_000)

        clock.advance(ms: 20_000)
        await queue.drain()
        job = try #require(try await db.job(recordID: text.record.id))
        #expect(job.attempts == 2)
        #expect(job.nextAttemptMs == clock.now + 300_000)

        clock.advance(ms: 300_000)
        await queue.drain()
        job = try #require(try await db.job(recordID: text.record.id))
        #expect(job.stage == .done)
        let record = try #require(try await db.record(id: text.record.id))
        #expect(record.processingError == "ai_failed")
        #expect(record.processingStatus == .failedPartial)
        #expect(transport.callCount == 4)
    }

    @Test func localModeWithoutOnDeviceAIMarksUnavailableAndOfflineCloudWaits() async throws {
        let (repository, directory) = try await makeRepository()
        defer { try? FileManager.default.removeItem(at: directory) }
        let note = try await repository.importItem(RecordImportItem(payload: .text("Plain words with no details."), source: .paste, importMethod: .pasteText))
        let queue = RecordProcessingQueue(repository: repository, dependencies: I.dependencies(mode: .local))
        await queue.enqueue(ids: [note.record.id])
        await queue.drain()
        #expect(try await repository.database.record(id: note.record.id)?.processingError == "ai_unavailable")

        var offline = I.dependencies(mode: .cloud, environment: I.stubEnvironment(cloud: true), transport: ScriptedTransport([]))
        offline.isOnline = { false }
        let second = try await repository.importItem(RecordImportItem(payload: .text("More plain words here."), source: .paste, importMethod: .pasteText))
        let offlineQueue = RecordProcessingQueue(repository: repository, dependencies: offline)
        await offlineQueue.enqueue(ids: [second.record.id])
        await offlineQueue.drain()
        let job = try #require(try await repository.database.job(recordID: second.record.id))
        #expect(job.stage == .ai)
        #expect(job.attempts == 0)
    }

    @Test func nearDuplicateSplitAcceptanceAndDuplicateActions() async throws {
        let (repository, directory) = try await makeRepository()
        defer { try? FileManager.default.removeItem(at: directory) }
        let db = repository.database
        let first = try await I.importPDF(I.labPDF(), repository: repository, name: "a.pdf")
        // Same visible content, different bytes (different PDF creation metadata).
        var secondData = I.labPDF()
        secondData.append(Data("\n% trailing comment".utf8))
        let second = try await I.importPDF(secondData, repository: repository, name: "b.pdf")
        #expect(first.checksumSHA256 != second.checksumSHA256)
        let queue = RecordProcessingQueue(repository: repository, dependencies: I.dependencies(mode: .off))
        await queue.enqueue(ids: [first.id])
        await queue.drain()
        await queue.enqueue(ids: [second.id])
        await queue.drain()
        let candidates = try await db.pendingDuplicateCandidates()
        #expect(candidates.count == 1)
        #expect(candidates.first?.recordID == second.id)
        #expect(candidates.first?.existingID == first.id)
        #expect(try await db.record(id: second.id)?.reviewStatus == .needsReview)

        try await repository.setTags(id: second.id, names: ["iron"])
        try await repository.update(id: second.id, patch: RecordPatch(notes: .some("from lab portal")))
        try await repository.mergeDuplicate(newID: second.id, existingID: first.id)
        #expect(try await db.record(id: second.id) == nil)
        #expect(try await db.tags(recordID: first.id) == ["iron"])
        #expect(try await db.record(id: first.id)?.notes == "from lab portal")

        // Replace swaps the original and reprocesses, keeping notes.
        let third = try await I.importPDF(I.labPDF(date: "13/09/2026"), repository: repository, name: "c.pdf")
        try await repository.replaceDuplicate(newID: third.id, existingID: first.id)
        let replaced = try #require(try await db.record(id: first.id))
        #expect(replaced.checksumSHA256 == third.checksumSHA256)
        #expect(replaced.filePath == "\(first.id)/original.pdf")
        #expect(replaced.notes == "from lab portal")
        #expect(try await db.job(recordID: first.id)?.stage == .text)
        #expect(try await db.record(id: third.id) == nil)
        #expect(FileManager.default.fileExists(atPath: repository.files.url(forRelativePath: replaced.filePath!).path))

        // Split: a 4-page PDF with two different reports.
        let renderer = UIGraphicsPDFRenderer(bounds: CGRect(x: 0, y: 0, width: 612, height: 792))
        let combined = renderer.pdfData { context in
            let lab = [("City Diagnostics Laboratory", "Complete Blood Count", "Page 1 of 2"), ("City Diagnostics Laboratory", "Complete Blood Count", "Page 2 of 2")]
            let rx = [("Sunrise Clinic OPD", "Prescription Rx", "Page 1 of 2"), ("Sunrise Clinic OPD", "Prescription Rx", "Page 2 of 2")]
            for (index, page) in (lab + rx).enumerated() {
                context.beginPage()
                let font: [NSAttributedString.Key: Any] = [.font: UIFont.systemFont(ofSize: 14)]
                (page.0 as NSString).draw(at: CGPoint(x: 60, y: 40), withAttributes: font)
                (page.1 as NSString).draw(at: CGPoint(x: 60, y: 70), withAttributes: font)
                let body = index < 2
                    ? "Specimen collected on 12/09/2026\nHemoglobin  9.7  g/dL  13 - 17\nReference range as per method"
                    : "Patient Name: Ravi Kumar  Age: 60 Y\nDate: 20/08/2026\nTab Metformin 500 mg BD x 30 days after food\nTab Amlodipine 5 mg OD"
                (body as NSString).draw(in: CGRect(x: 60, y: 110, width: 480, height: 300), withAttributes: font)
                (page.2 as NSString).draw(at: CGPoint(x: 260, y: 760), withAttributes: font)
            }
        }
        let parent = try await I.importPDF(combined, repository: repository, name: "hospital_file.pdf")
        await queue.enqueue(ids: [parent.id])
        await queue.drain()
        let proposal = try #require(try await db.splitProposal(recordID: parent.id))
        #expect(proposal.segments.map { [$0.pageStart, $0.pageEnd] } == [[0, 1], [2, 3]])
        #expect(proposal.segments.map(\.recordType) == [.labReport, .prescription])
        #expect(try await db.record(id: parent.id)?.reviewStatus == .needsReview)
        let children = try await db.acceptSplit(parentID: parent.id, segments: proposal.segments)
        #expect(children.count == 2)
        let parentAfter = try #require(try await db.record(id: parent.id))
        #expect(parentAfter.archived)
        await queue.drain()
        let rxChild = try #require(try await db.record(id: children[1]))
        #expect(rxChild.parentID == parent.id)
        #expect(rxChild.pageStart == 2 && rxChild.pageEnd == 3)
        #expect(rxChild.filePath == parent.filePath)
        #expect(rxChild.recordType == .prescription)
        #expect(try await db.pages(recordID: rxChild.id).count == 2)
        let meds = try await db.fields(recordID: rxChild.id).filter { $0.key == .medication }
        #expect(meds.map(\.valueText).sorted() == ["Amlodipine", "Metformin"])
        #expect(try await db.splitProposal(recordID: parent.id)?.status == .accepted)
        #expect(try await repository.expandedForDelete([parent.id]).count == 3)
    }
}

// MARK: - Pure pieces

struct RecordsIntelligenceUnitTests {
    @Test func aiModeResolutionMatrix() {
        let none = RecordsIntelligenceFixtures.stubEnvironment()
        let cloud = RecordsIntelligenceFixtures.stubEnvironment(cloud: true)
        let local = RecordsIntelligenceFixtures.stubEnvironment(local: true)
        #expect(RecordsAIModeResolver.resolve(mode: .off, request: nil, environment: cloud) == .skip(nil))
        #expect(RecordsAIModeResolver.resolve(mode: nil, request: nil, environment: cloud) == .askConsent)
        #expect(RecordsAIModeResolver.resolve(mode: .ask, request: nil, environment: cloud) == .askConsent)
        #expect(RecordsAIModeResolver.resolve(mode: .local, request: nil, environment: none) == .skip(.aiUnavailable))
        #expect(RecordsAIModeResolver.resolve(mode: .local, request: nil, environment: local) == .run(.appleIntelligence))
        var gemma = none
        gemma.gemmaInstalled = true
        #expect(RecordsAIModeResolver.resolve(mode: .local, request: nil, environment: gemma) == .run(.gemma))
        #expect(RecordsAIModeResolver.resolve(mode: .cloud, request: nil, environment: none) == .skip(.aiUnavailable))
        #expect(RecordsAIModeResolver.resolve(mode: .cloud, request: nil, environment: cloud) == .run(.cloud(provider: .gemini)))
        var appleText = local
        appleText.textProvider = .appleIntelligence
        appleText.textProviderReady = true
        #expect(RecordsAIModeResolver.resolve(mode: .cloud, request: nil, environment: appleText) == .run(.appleIntelligence), "a local primary provider counts as local")
        #expect(RecordsAIModeResolver.resolve(mode: .ask, request: .cloud, environment: cloud) == .run(.cloud(provider: .gemini)))
        #expect(RecordsAIModeResolver.resolve(mode: .ask, request: RecordsAIRequest.none, environment: cloud) == .skip(nil))
        #expect(RecordsAIEngine.cloud(provider: .openai).chunkLimit == 12_000)
        #expect(RecordsAIEngine.gemma.chunkLimit == 2_500)
        #expect(!RecordsAIEngine.appleIntelligence.supportsImages)
    }

    @Test func chunksRespectLimitsAndSkipPagesWithoutText() {
        let long = Array(repeating: "Hemoglobin value line.", count: 400).joined(separator: "\n")
        let pages = [long, "   ", "Short page with enough letters here"]
        let local = RR.aiChunks(pages, mode: "local")
        #expect(local.allSatisfy { ($0["text"].string ?? "").rLen <= 2_500 })
        #expect(local.contains { ($0["pages"].array ?? []).contains { $0.double == 3 } })
        #expect(!local.contains { ($0["pages"].array ?? []).contains { $0.double == 2 } })
        #expect(RR.aiChunks(pages, mode: "cloud").allSatisfy { ($0["text"].string ?? "").rLen <= 12_000 })
    }

    @Test func textLayoutJoinsRowsLeftToRightInReadingOrder() {
        let lines = [
            RecordTextLine(text: "g/dL", box: [0.5, 0.101, 0.05, 0.02], confidence: 0.9),
            RecordTextLine(text: "Hemoglobin", box: [0.1, 0.1, 0.2, 0.02], confidence: 0.8),
            RecordTextLine(text: "9.7", box: [0.35, 0.105, 0.05, 0.02], confidence: 1.0),
            RecordTextLine(text: "Header", box: [0.1, 0.02, 0.3, 0.03], confidence: nil),
        ]
        let joined = RecordTextLayout.join(lines)
        #expect(joined.text == "Header\nHemoglobin  9.7  g/dL")
        #expect(joined.confidence == 0.9)
        #expect(joined.blocksJSON.hasPrefix("[{\"t\":\"Header\",\"b\":[0.1,0.02,0.3,0.03]}"))
    }

    @Test func skewedPhotoRowsJoinThroughTheNearestNeighbour() {
        // A ~2° skew: each cell sits a little lower than the one to its left; the last cell is more
        // than half a line height below the first one but close to its neighbour.
        let lines = [
            RecordTextLine(text: "Haemoglobin", box: [0.05, 0.300, 0.20, 0.020], confidence: 0.9),
            RecordTextLine(text: "11.2", box: [0.30, 0.308, 0.06, 0.020], confidence: 0.9),
            RecordTextLine(text: "g/dL", box: [0.45, 0.316, 0.06, 0.020], confidence: 0.9),
            RecordTextLine(text: "13.0 - 17.0", box: [0.60, 0.324, 0.15, 0.020], confidence: 0.9),
            RecordTextLine(text: "Total Leucocyte Count", box: [0.05, 0.340, 0.25, 0.020], confidence: 0.9),
        ]
        #expect(RecordTextLayout.join(lines).text == "Haemoglobin  11.2  g/dL  13.0 - 17.0\nTotal Leucocyte Count")
    }

    @Test func nearDuplicateHashing() {
        #expect(RR.fnv1a64(Array("".utf8)) == 0xcbf2_9ce4_8422_2325)
        #expect(RR.fnv1a64(Array("a".utf8)) == 0xaf63_dc4c_8601_ec8c)
        #expect(RR.hammingHex("0000000000000000", "000000000000000f") == 4)
        let a = RecordNearDuplicate.textSignature(text: "Complete blood count hemoglobin 9.7 g/dL reference range 13 to 17 collected today")
        let b = RecordNearDuplicate.textSignature(text: "COMPLETE BLOOD COUNT  hemoglobin 9.7 g/dL reference range 13 to 17 collected today")
        #expect(RR.signatureSimilarity(a, b) == 1)
        #expect(RecordNearDuplicate.textSignature(text: "   ") == "")
        #expect(RecordNearDuplicate.match(phashA: "0000000000000000", signatureA: "", phashB: "0000000000000003", signatureB: "") == .phash(score: 0.97))
    }

    @Test func queryParserMapsDatesTypesFlagsAndPeople() {
        let today = "2026-09-15"
        var parsed = RecordQueryParser.parse("abnormal blood reports from dr rao in august 2026", today: today)
        #expect(parsed.flags == [.abnormal])
        #expect(parsed.recordTypes == [.labReport])
        #expect(parsed.doctor == "rao")
        #expect(parsed.dateFrom == "2026-08-01" && parsed.dateTo == "2026-08-31")
        #expect(parsed.terms.isEmpty)
        parsed = RecordQueryParser.parse("prescriptions last month", today: today)
        #expect(parsed.recordTypes == [.prescription])
        #expect(parsed.dateFrom == "2026-08-01" && parsed.dateTo == "2026-08-31")
        parsed = RecordQueryParser.parse("hemoglobin low 2025", today: today)
        #expect(parsed.terms == ["hemoglobin"])
        #expect(parsed.flags == [.low])
        #expect(parsed.dateFrom == "2025-01-01" && parsed.dateTo == "2025-12-31")
        parsed = RecordQueryParser.parse("x-ray at apollo hospital", today: today)
        #expect(parsed.recordTypes == [.imagingReport])
        #expect(parsed.facility == "apollo hospital" || parsed.facility == "apollo")
        parsed = RecordQueryParser.parse("december", today: today)
        #expect(parsed.dateFrom == "2025-12-01")
        parsed = RecordQueryParser.parse("last 3 months favorites needs review", today: today)
        #expect(parsed.dateFrom == "2026-06-15" && parsed.dateTo == today)
        #expect(parsed.favorites && parsed.needsReview)
        let query = parsed.applied(to: RecordQuery(), removing: [ParsedRecordQuery.Chip.favorites.id])
        #expect(!query.favoritesOnly && query.needsReviewOnly)
    }

    @Test func perCallTokenOverrideLeavesDefaultsUnchanged() {
        #expect(GeminiService.effectiveMaxOutputTokens(nil) == AIProviderSettings.maxResponseTokens)
        #expect(GeminiService.effectiveMaxOutputTokens(3_000) == 3_000)
        #expect(GeminiService.geminiGenerationConfig(maxOutputTokens: nil) == nil)
        #expect(GeminiService.geminiGenerationConfig(maxOutputTokens: 3_000)?["maxOutputTokens"] as? Int == 3_000)
    }

    @Test func aiModePreferenceIsExcludedFromCloudBackup() {
        #expect(RecordsAIMode.storageKey == "healthRecordsAiMode")
        #expect(!CloudBackupPolicy.include(RecordsAIMode.storageKey))
        #expect(RecordsAIMode.allCases.map(\.rawValue) == ["local", "cloud", "ask", "off"])
    }

    @Test func reviewRuleReasons() {
        var record = RecordsTestFixtures.record(title: "x", documentDate: "2026-09-12", createdMs: 1, type: .labReport)
        record.typeConfidence = 0.9
        record.typeMethod = .rules
        let confident = RecordField(id: "1", recordID: "x", key: .doctorName, valueText: "A", method: .rules, confidence: 0.85, state: .suggested, createdMs: 0, updatedMs: 0)
        #expect(RecordReviewEvaluator.status(.init(record: record, fields: [confident], pendingSplit: false, pendingDuplicate: false)) == .none)
        var unsure = confident
        unsure.confidence = 0.7
        #expect(RecordReviewEvaluator.reasons(.init(record: record, fields: [unsure], pendingSplit: false, pendingDuplicate: false)) == ["low_confidence:doctor_name"])
        record.reviewStatus = .reviewed
        #expect(RecordReviewEvaluator.status(.init(record: record, fields: [unsure], pendingSplit: false, pendingDuplicate: false)) == .needsReview, "unconfirmed key rows reopen review")
        #expect(RecordReviewEvaluator.status(.init(record: record, fields: [confident], pendingSplit: false, pendingDuplicate: false)) == .reviewed)
        #expect(RecordReviewEvaluator.status(.init(record: record, fields: [], pendingSplit: true, pendingDuplicate: false)) == .needsReview)
    }
}
