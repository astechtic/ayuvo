import CryptoKit
import Foundation
import Testing
import UIKit
@testable import calorietracker

struct RecordMimeSnifferTests {
    private func sniff(_ bytes: [UInt8], complete: Bool = true, filename: String? = nil) -> RecordSniffResult {
        RecordMimeSniffer.sniff(prefix: Data(bytes), isComplete: complete, filename: filename)
    }

    @Test func magicBytesDecideTheType() {
        #expect(sniff(Array("%PDF-1.7\n".utf8), filename: "photo.jpg").fileType == .pdf)
        #expect(sniff([0xFF, 0xD8, 0xFF, 0xE0, 0, 0x10]).mimeType == "image/jpeg")
        #expect(sniff([0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A]).fileExtension == "png")
        #expect(sniff(Array("RIFF".utf8) + [0, 0, 0, 0] + Array("WEBPVP8 ".utf8)).mimeType == "image/webp")
        #expect(sniff(Array("GIF89a".utf8) + [0]).mimeType == "image/gif")
    }

    @Test func heifBrandsAreRecognizedMajorOrCompatible() {
        let major = [0, 0, 0, 0x18] + Array("ftypheic".utf8) + [0, 0, 0, 0] + Array("mif1heic".utf8)
        #expect(sniff(major).mimeType == "image/heic")
        let compatible = [0, 0, 0, 0x1C] + Array("ftypXXXX".utf8) + [0, 0, 0, 0] + Array("abcdmsf1".utf8)
        #expect(sniff(compatible).fileType == .image)
        // Minor-version bytes spelling a brand do not count.
        let minorOnly = [0, 0, 0, 0x10] + Array("ftypisom".utf8) + Array("heic".utf8)
        #expect(sniff(minorOnly).fileType != .image)
        let mp4 = [0, 0, 0, 0x18] + Array("ftypisom".utf8) + [0, 0, 2, 0] + Array("isomiso2".utf8)
        #expect(sniff(mp4).fileType == .other)
    }

    @Test func textIsValidUTF8WithoutNUL() {
        #expect(sniff(Array("Hemoglobin 9.7 g/dL — ok".utf8)) == RecordSniffResult(fileType: .text, mimeType: "text/plain", fileExtension: "txt"))
        #expect(sniff(Array("# Notes".utf8), filename: "visit.MD").mimeType == "text/markdown")
        #expect(sniff(Array("abc".utf8) + [0, 1, 2]).fileType == .other)
        #expect(sniff([0xC3, 0x28]).fileType == .other)
        // "é" cut in half at the prefix boundary: fine while the file continues, invalid when complete.
        let cut = Array("caf".utf8) + [0xC3]
        #expect(sniff(cut, complete: false).fileType == .text)
        #expect(sniff(cut, complete: true).fileType == .other)
        #expect(sniff([]).fileType == .other)
    }
}

struct RecordTitleAndDateTests {
    @Test func titleFallsBackThroughTheContractChain() {
        #expect(RecordTitleDeriver.title(userTitle: "  My   CBC ", filename: "x.pdf", type: .labReport, date: .now) == "My CBC")
        #expect(RecordTitleDeriver.title(userTitle: nil, filename: "blood_report-sept__2026.pdf", type: .other, date: .now) == "blood report sept 2026")
        #expect(RecordTitleDeriver.title(userTitle: "", filename: "/tmp/in/Scan.PDF", type: nil, date: nil) == "Scan")
        let date = RecordDates.date(fromDay: "2026-09-15")!
        let fallback = RecordTitleDeriver.title(userTitle: nil, filename: "___.pdf", type: .prescription, date: date)
        #expect(fallback.hasPrefix("\(RecordType.prescription.title) — "))
        #expect(RecordTitleDeriver.title(userTitle: nil, filename: nil, type: nil, date: nil) == String(localized: "Untitled record"))
    }

    @Test func filenameDatePatterns() {
        #expect(RecordFilenameDate.parse("CBC_2026-09-12.pdf") == "2026-09-12")
        #expect(RecordFilenameDate.parse("IMG_20250131_101500.jpg") == "2025-01-31")
        #expect(RecordFilenameDate.parse("report 05-03-2024.pdf") == "2024-03-05")
        #expect(RecordFilenameDate.parse("2026-02-30.pdf") == nil)
        #expect(RecordFilenameDate.parse("order 1234567890.pdf") == nil)
        #expect(RecordFilenameDate.parse("scan-99999999.pdf") == nil)
        #expect(RecordFilenameDate.parse(nil) == nil)
    }

    @Test func exifAndPDFDates() {
        #expect(RecordFileInspector.parseEXIFDate("2026:09:12 08:30:00") == "2026-09-12")
        #expect(RecordFileInspector.parseEXIFDate("0000:00:00 00:00:00") == nil)
        let pdfDate = RecordFileInspector.parsePDFDate("D:20260912083000+05'30'")
        #expect(pdfDate.map { RecordDates.dayString(from: $0) } == "2026-09-12")
        #expect(RecordFileInspector.parsePDFDate("garbage") == nil)
    }

    @Test func searchTextFolding() {
        #expect(RecordsSearchText.fold("ÉCOLE Ärzte Ñ") == "ecole arzte n")
        #expect(RecordsSearchText.matchExpression("  Hb-A1c  \"x\" ") == "hb* a1c* x*")
        #expect(RecordsSearchText.matchExpression(" *** ") == nil)
    }
}

struct RecordImporterTests {
    private typealias F = RecordsTestFixtures

    private func makeRepository() async throws -> (RecordsRepository, URL) {
        let directory = try F.temporaryDirectory()
        let db = try await RecordsDatabase.inMemory()
        return (RecordsRepository(database: db, files: RecordFileStore(root: directory.appendingPathComponent("files"))), directory)
    }

    @Test func fileImportCopiesBytesHashesAndDetectsExactDuplicates() async throws {
        let (repository, directory) = try await makeRepository()
        defer { try? FileManager.default.removeItem(at: directory) }
        let pdf = F.pdfData(pages: 2)
        let source = directory.appendingPathComponent("CBC_2026-09-12.pdf")
        try pdf.write(to: source)

        let first = try await repository.importItem(RecordImportItem(payload: .file(source), source: .import, importMethod: .filePicker, originalFilename: source.lastPathComponent))
        #expect(first.duplicates.isEmpty)
        let record = first.record
        #expect(record.fileType == .pdf)
        #expect(record.mimeType == "application/pdf")
        #expect(record.title == "CBC 2026 09 12")
        #expect(record.processingStatus == .saved)
        #expect(record.fileSize == Int64(pdf.count))
        #expect(record.filePath == "\(record.id)/original.pdf")
        #expect(record.checksumSHA256 == SHA256.hash(data: pdf).map { String(format: "%02x", $0) }.joined())
        let stored = try Data(contentsOf: repository.files.url(forRelativePath: record.filePath!))
        #expect(stored == pdf)
        #expect(!FileManager.default.fileExists(atPath: repository.files.directory(for: record.id).appendingPathComponent("original.tmp").path))

        let processed = try #require(await repository.processBasics(record))
        #expect(processed.processingStatus == .ready)
        #expect(processed.pageCount == 2)
        #expect(processed.thumbnailPath == "\(record.id)/thumb.jpg")
        #expect(processed.documentDate != nil)
        #expect(processed.documentDateMethod == .fileMetadata)

        let second = try await repository.importItem(RecordImportItem(payload: .data(pdf), source: .shareIn, importMethod: .shareSheet))
        #expect(second.duplicates.map(\.id) == [record.id])
    }

    @Test func pastedTextAndNotesGetOnePlainPage() async throws {
        let (repository, directory) = try await makeRepository()
        defer { try? FileManager.default.removeItem(at: directory) }
        let pasted = try await repository.importItem(RecordImportItem(payload: .text("Take 1 tablet daily"), source: .paste, importMethod: .pasteText))
        #expect(pasted.record.fileType == .text)
        #expect(pasted.record.filePath?.hasSuffix("original.txt") == true)
        #expect(pasted.record.recordType == .other)
        let pages = try await repository.database.pages(recordID: pasted.record.id)
        #expect(pages == [RecordPage(recordID: pasted.record.id, pageIndex: 0, text: "Take 1 tablet daily", textSource: .plain)])

        let note = try await repository.importItem(RecordImportItem(payload: .text("Felt dizzy"), source: .note, importMethod: .noteEditor, userTitle: "Symptoms"))
        #expect(note.record.recordType == .personalNote)
        #expect(note.record.category == .personalNotes)
        #expect(note.record.title == "Symptoms")

        await #expect(throws: RecordImportError.empty) {
            try await repository.importItem(RecordImportItem(payload: .text("   \n"), source: .paste, importMethod: .pasteText))
        }
    }

    @Test func textFileImportIndexesItsBodyAfterBasics() async throws {
        let (repository, directory) = try await makeRepository()
        defer { try? FileManager.default.removeItem(at: directory) }
        let source = directory.appendingPathComponent("visit.md")
        try Data("# Visit\nFerritin low".utf8).write(to: source)
        let result = try await repository.importItem(RecordImportItem(payload: .file(source), source: .import, importMethod: .filePicker, originalFilename: "visit.md"))
        #expect(result.record.mimeType == "text/markdown")
        #expect(try await repository.page(query: RecordQuery(text: "ferritin"), after: nil).isEmpty)
        _ = await repository.processBasics(result.record)
        #expect(try await repository.page(query: RecordQuery(text: "ferritin"), after: nil).map(\.id) == [result.record.id])
    }

    @Test func corruptPDFIsKeptAsFailedPartial() async throws {
        let (repository, directory) = try await makeRepository()
        defer { try? FileManager.default.removeItem(at: directory) }
        let result = try await repository.importItem(RecordImportItem(payload: .data(Data("%PDF-1.4 truncated garbage".utf8)), source: .import, importMethod: .filePicker, originalFilename: "broken.pdf"))
        let processed = try #require(await repository.processBasics(result.record))
        #expect(processed.processingStatus == .failedPartial)
        #expect(processed.processingError == "unreadable_pdf")
        #expect(try await repository.recordCount() == 1)
    }

    @Test func copyAbortsPastTheSizeCapAndKeepsNothing() throws {
        let directory = try F.temporaryDirectory()
        defer { try? FileManager.default.removeItem(at: directory) }
        let files = RecordFileStore(root: directory.appendingPathComponent("files"))
        let source = directory.appendingPathComponent("big.bin")
        try Data(repeating: 7, count: 4_096).write(to: source)
        #expect(throws: RecordFileError.tooLarge(limit: 1_000)) {
            try files.copyToTemp(from: source, id: "abc", maxBytes: 1_000)
        }
        #expect(!FileManager.default.fileExists(atPath: files.directory(for: "abc").path))
        #expect(RecordFileStore.maxFileBytes == 512 * 1_024 * 1_024)
    }

    @Test func imageImportMakesThumbnail() async throws {
        let (repository, directory) = try await makeRepository()
        defer { try? FileManager.default.removeItem(at: directory) }
        let image = UIGraphicsImageRenderer(size: CGSize(width: 1200, height: 800)).image { context in
            UIColor.systemTeal.setFill()
            context.fill(CGRect(x: 0, y: 0, width: 1200, height: 800))
        }
        let jpeg = try #require(image.jpegData(compressionQuality: 0.9))
        let result = try await repository.importItem(RecordImportItem(payload: .data(jpeg), source: .camera, importMethod: .camera))
        #expect(result.record.fileType == .image)
        let processed = try #require(await repository.processBasics(result.record))
        #expect(processed.processingStatus == .ready)
        let thumbPath = try #require(processed.thumbnailPath)
        let thumb = try #require(UIImage(contentsOfFile: repository.files.url(forRelativePath: thumbPath).path))
        #expect(max(thumb.size.width * thumb.scale, thumb.size.height * thumb.scale) <= 320)
    }
}

struct RecordsInboxTests {
    private typealias F = RecordsTestFixtures

    private func writeItem(root: URL, name: String, item: RecordsInboxItem, payload: (String, Data)?) throws {
        let directory = root.appendingPathComponent(name, isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        try JSONEncoder().encode(item).write(to: directory.appendingPathComponent("item.json"))
        if let (filename, data) = payload {
            try data.write(to: directory.appendingPathComponent(filename))
        }
    }

    @Test func itemJSONUsesContractKeys() throws {
        let data = try JSONEncoder().encode(RecordsInboxItem(originalFilename: "a.pdf", uti: "com.adobe.pdf", receivedAtMs: 1_789_000_000_000, text: nil))
        let object = try #require(try JSONSerialization.jsonObject(with: data) as? [String: Any])
        #expect(Set(object.keys) == ["format", "version", "original_filename", "uti", "received_at_ms"])
        #expect(object["format"] as? String == "ayuvo-records-inbox")
        #expect(object["version"] as? Int == 1)
        let decoded = try JSONDecoder().decode(RecordsInboxItem.self, from: Data(#"{"format":"ayuvo-records-inbox","version":1,"original_filename":null,"uti":"public.plain-text","received_at_ms":5,"text":"hi"}"#.utf8))
        #expect(decoded.text == "hi")
        #expect(decoded.receivedAtMs == 5)
    }

    @Test func drainImportsReadyItemsAndRemovesThem() async throws {
        let directory = try F.temporaryDirectory()
        defer { try? FileManager.default.removeItem(at: directory) }
        let inbox = directory.appendingPathComponent("RecordsInbox", isDirectory: true)
        let db = try await RecordsDatabase.inMemory()
        let repository = RecordsRepository(database: db, files: RecordFileStore(root: directory.appendingPathComponent("files")))

        try writeItem(root: inbox, name: UUID().uuidString, item: RecordsInboxItem(originalFilename: "blood_report.pdf", uti: "com.adobe.pdf", receivedAtMs: 2_000, text: nil), payload: ("payload.pdf", F.pdfData()))
        try writeItem(root: inbox, name: UUID().uuidString, item: RecordsInboxItem(originalFilename: nil, uti: "public.plain-text", receivedAtMs: 1_000, text: "Dr. Rao: review in 2 weeks"), payload: nil)
        // Still being written by the extension: never touched.
        try writeItem(root: inbox, name: ".tmp-\(UUID().uuidString)", item: RecordsInboxItem(originalFilename: "x.pdf", uti: nil, receivedAtMs: 500, text: nil), payload: ("payload.pdf", F.pdfData()))
        // A future format is left for a newer app.
        try writeItem(root: inbox, name: "future", item: RecordsInboxItem(version: 2, originalFilename: nil, uti: nil, receivedAtMs: 1, text: "later"), payload: nil)

        let result = await RecordsInbox.drain(root: inbox, importer: repository.importer)

        #expect(result.failures.isEmpty)
        #expect(result.imported.count == 2)
        #expect(result.imported.map(\.record.source) == [.shareIn, .shareIn])
        #expect(result.imported.map(\.record.importMethod) == [.shareSheet, .shareSheet])
        #expect(result.imported.first?.record.fileType == .text)
        #expect(result.imported.last?.record.title == "blood report")
        let remaining = Set(try FileManager.default.contentsOfDirectory(atPath: inbox.path))
        #expect(remaining.count == 2)
        #expect(remaining.contains("future"))
        #expect(remaining.contains { $0.hasPrefix(".tmp-") })
        #expect(try await db.recordCount() == 2)

        let again = await RecordsInbox.drain(root: inbox, importer: repository.importer)
        #expect(again.imported.isEmpty)
    }

    @Test func cloudBackupExcludesHealthRecordsKeys() {
        #expect(!CloudBackupPolicy.include(RecordsViewMode.storageKey))
        #expect(!CloudBackupPolicy.include("healthRecordsAiMode"))
        #expect(CloudBackupPolicy.include("healthHomeTiles"))
    }
}

@MainActor
struct RecordsStoreTests {
    @Test func viewModeDefaultsToTimelineAndPersists() throws {
        let suite = "records-store-tests-\(UUID().uuidString)"
        let defaults = try #require(UserDefaults(suiteName: suite))
        defer { defaults.removePersistentDomain(forName: suite) }
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(suite)
        let store = RecordsStore(defaults: defaults, databaseURL: directory.appendingPathComponent("records.sqlite"), files: RecordFileStore(root: directory.appendingPathComponent("files")), inboxRoot: { nil })
        #expect(store.viewMode == .timeline)
        store.viewMode = .grid
        #expect(defaults.string(forKey: "healthRecordsViewMode") == "grid")
        let reloaded = RecordsStore(defaults: defaults, databaseURL: directory.appendingPathComponent("records.sqlite"), inboxRoot: { nil })
        #expect(reloaded.viewMode == .grid)
    }

    @Test func importDrainAndDeleteAllDataThroughTheStore() async throws {
        let suite = "records-store-tests-\(UUID().uuidString)"
        let defaults = try #require(UserDefaults(suiteName: suite))
        defer { defaults.removePersistentDomain(forName: suite) }
        let directory = try RecordsTestFixtures.temporaryDirectory()
        defer { try? FileManager.default.removeItem(at: directory) }
        let inbox = directory.appendingPathComponent("RecordsInbox")
        let databaseURL = directory.appendingPathComponent("Records/records.sqlite")
        let files = RecordFileStore(root: directory.appendingPathComponent("Records/files"))
        let store = RecordsStore(defaults: defaults, databaseURL: databaseURL, files: files, inboxRoot: { inbox })

        await store.importItems([RecordImportItem(payload: .text("Vitamin D 18 ng/mL"), source: .paste, importMethod: .pasteText, userTitle: "Vitamin D")])
        #expect(store.records.map(\.title) == ["Vitamin D"])
        #expect(store.banner?.message == String(localized: "Saved to Health Records"))

        let item = RecordsInboxItem(originalFilename: nil, uti: "public.plain-text", receivedAtMs: 1_000, text: "Shared note")
        let itemDirectory = inbox.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: itemDirectory, withIntermediateDirectories: true)
        try JSONEncoder().encode(item).write(to: itemDirectory.appendingPathComponent("item.json"))
        let tabRequest = store.tabRequest
        await store.drainInbox()
        #expect(store.tabRequest == tabRequest + 1)
        // Background thumbnail/date passes also reload; settle on the latest page.
        await store.reload()
        #expect(store.totalCount == 2)

        await store.deleteAllData()
        #expect(store.records.isEmpty)
        for url in HealthDatabaseLocation.allFileURLs(for: databaseURL) {
            #expect(!FileManager.default.fileExists(atPath: url.path), "\(url.lastPathComponent) survived Delete All Data")
        }
        #expect(!FileManager.default.fileExists(atPath: files.root.path))
        #expect(!FileManager.default.fileExists(atPath: inbox.path))
    }
}
