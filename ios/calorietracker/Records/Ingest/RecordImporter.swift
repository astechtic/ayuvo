import Foundation

/// One thing to import (a picked file, camera bytes, a scanned PDF, pasted text, a note).
nonisolated struct RecordImportItem: Sendable {
    nonisolated enum Payload: Sendable {
        case file(URL)
        case data(Data)
        case text(String)
    }

    var payload: Payload
    var source: RecordSource
    var importMethod: RecordImportMethod
    /// Hint only: used for the fallback title, the `.md` rule and the filename date.
    var originalFilename: String?
    var userTitle: String?
    var recordType: RecordType?
    var sourceApp: String?
    var notes: String?

    init(
        payload: Payload,
        source: RecordSource,
        importMethod: RecordImportMethod,
        originalFilename: String? = nil,
        userTitle: String? = nil,
        recordType: RecordType? = nil,
        sourceApp: String? = nil,
        notes: String? = nil
    ) {
        self.payload = payload
        self.source = source
        self.importMethod = importMethod
        self.originalFilename = originalFilename
        self.userTitle = userTitle
        self.recordType = recordType
        self.sourceApp = sourceApp
        self.notes = notes
    }
}

nonisolated enum RecordImportError: Error, Equatable, Sendable {
    case tooLarge(limitBytes: Int64)
    case unreadable
    case empty
    case storage(String)

    var message: String {
        switch self {
        case .tooLarge:
            String(localized: "This file is larger than 512 MB and wasn't imported.")
        case .unreadable:
            String(localized: "This file couldn't be read and wasn't imported.")
        case .empty:
            String(localized: "There's nothing to save.")
        case .storage:
            String(localized: "Ayuvo couldn't save this record. Check your free storage and try again.")
        }
    }

    /// Worth keeping a received inbox item for a later retry.
    var isRetryable: Bool {
        if case .storage = self { return true }
        return false
    }
}

nonisolated struct RecordImportResult: Sendable, Equatable {
    var record: HealthRecord
    /// Existing records with the same SHA-256 (contract §4.5).
    var duplicates: [HealthRecord]
}

/// Contract §4: copy the original byte-for-byte, sniff, insert `records` (status `saved`),
/// report exact duplicates. `processBasics` is the best-effort background stage
/// (page count, thumbnail, metadata date, text pages, FTS) that never removes a record.
/// Call from a detached task — every step does file IO.
nonisolated struct RecordImporter: Sendable {
    let database: RecordsDatabase
    let files: RecordFileStore

    func importItem(_ item: RecordImportItem, nowMs: Int64 = RecordDates.nowMs()) async throws -> RecordImportResult {
        let id = UUID().uuidString.lowercased()
        let copy: RecordCopyResult
        var sniff: RecordSniffResult
        var pages: [RecordPage] = []
        do {
            switch item.payload {
            case .file(let url):
                let scoped = url.startAccessingSecurityScopedResource()
                defer { if scoped { url.stopAccessingSecurityScopedResource() } }
                copy = try files.copyToTemp(from: url, id: id)
                sniff = RecordMimeSniffer.sniff(
                    prefix: copy.prefix,
                    isComplete: copy.size <= Int64(copy.prefix.count),
                    filename: item.originalFilename ?? url.lastPathComponent
                )
            case .data(let data):
                guard !data.isEmpty else { throw RecordImportError.empty }
                copy = try files.writeToTemp(data, id: id)
                sniff = RecordMimeSniffer.sniff(prefix: copy.prefix, isComplete: copy.size <= Int64(copy.prefix.count), filename: item.originalFilename)
            case .text(let text):
                guard !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { throw RecordImportError.empty }
                copy = try files.writeToTemp(Data(text.utf8), id: id)
                sniff = RecordSniffResult(fileType: .text, mimeType: "text/plain", fileExtension: "txt")
                pages = [RecordPage(recordID: id, pageIndex: 0, text: text, textSource: .plain)]
            }
        } catch let error as RecordFileError {
            files.delete(id: id)
            switch error {
            case .tooLarge(let limit): throw RecordImportError.tooLarge(limitBytes: limit)
            case .unreadable: throw RecordImportError.unreadable
            case .writeFailed(let message): throw RecordImportError.storage(message)
            }
        } catch {
            files.delete(id: id)
            throw error
        }
        if copy.size == 0 {
            files.delete(id: id)
            throw RecordImportError.empty
        }

        let relativePath: String
        do {
            relativePath = try files.finalize(copy, id: id, ext: sniff.fileExtension)
        } catch {
            files.delete(id: id)
            throw RecordImportError.storage(error.localizedDescription)
        }

        let type = item.recordType ?? (item.source == .note ? .personalNote : .other)
        let filename = item.originalFilename.flatMap { $0.isEmpty ? nil : $0 }
        let title = RecordTitleDeriver.title(
            userTitle: item.userTitle,
            filename: filename,
            type: type,
            date: Date(timeIntervalSince1970: Double(nowMs) / 1000)
        )
        let notes = item.notes.flatMap { $0.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? nil : $0 }
        let record = HealthRecord(
            id: id,
            title: title,
            recordType: type,
            category: type.defaultCategory,
            source: item.source,
            importMethod: item.importMethod,
            sourceApp: item.sourceApp,
            originalFilename: filename,
            createdMs: nowMs,
            updatedMs: nowMs,
            mimeType: sniff.mimeType,
            fileType: sniff.fileType,
            fileSize: copy.size,
            pageCount: pages.count,
            filePath: relativePath,
            checksumSHA256: copy.sha256,
            processingStatus: .saved,
            notes: notes
        )
        let stored: HealthRecord
        do {
            stored = try await database.insert(record, pages: pages)
        } catch {
            files.delete(id: id)
            throw RecordImportError.storage(String(describing: error))
        }
        let duplicates = (try? await database.records(withChecksum: copy.sha256, excluding: id)) ?? []
        return RecordImportResult(record: stored, duplicates: duplicates)
    }

    /// Contract §4.6 background basics. Always leaves the record in place; failures only set
    /// `failed_partial` + `processing_error`.
    @discardableResult
    func processBasics(_ record: HealthRecord) async -> HealthRecord? {
        guard let filePath = record.filePath else { return record }
        let url = files.url(forRelativePath: filePath)
        var status: RecordProcessingStatus = .ready
        var error: String?
        var pageCount: Int?
        var thumbnailPath: String?
        var metadataDate: String?
        var textPages: [RecordPage]?

        switch record.fileType {
        case .pdf, .image:
            let inspection = RecordFileInspector.inspect(url: url, fileType: record.fileType)
            pageCount = inspection.pageCount
            metadataDate = inspection.metadataDate
            if let jpeg = inspection.thumbnailJPEG {
                thumbnailPath = try? files.writeThumbnail(jpeg, id: record.id)
            }
            switch inspection.pdfState {
            case .locked:
                status = .failedPartial
                error = "protected_pdf"
            case .unreadable:
                status = .failedPartial
                error = "unreadable_pdf"
            case .readable, nil:
                if thumbnailPath == nil {
                    status = .failedPartial
                    error = record.fileType == .image ? "unreadable_image" : "thumbnail_failed"
                }
            }
        case .text:
            if record.pageCount == 0 {
                if let text = RecordFileInspector.readText(url: url) {
                    textPages = [RecordPage(recordID: record.id, pageIndex: 0, text: text, textSource: .plain)]
                    pageCount = 1
                } else {
                    status = .failedPartial
                    error = "unreadable_text"
                }
            }
        case .other:
            break
        }
        if metadataDate == nil {
            metadataDate = RecordFilenameDate.parse(record.originalFilename)
        }
        do {
            try await database.applyBasics(
                id: record.id,
                pageCount: pageCount,
                thumbnailPath: thumbnailPath,
                documentDate: metadataDate,
                dateMethod: metadataDate == nil ? nil : .fileMetadata,
                status: status,
                error: error,
                pages: textPages
            )
            return try await database.record(id: record.id)
        } catch {
            return nil
        }
    }
}
