import Foundation
import UniformTypeIdentifiers

/// Turns a file the user attached into the text Coach will send (docs/coach.md §6).
///
/// Everything happens **on device**: PDFs go through the Records extractor (PDF text layer first,
/// Vision OCR when the layer is empty), plain text is read as-is. The pages then run through the
/// shared `attachment_excerpt`, which drops every line the records redaction rule would drop — so a
/// lab PDF attached to chat is treated exactly like a record read through `records_get`.
///
/// Only the resulting excerpt is sent, and rule 4 means the user can see it: the composer chip opens
/// a sheet showing this exact text.
nonisolated struct CoachAttachmentProcessor: Sendable {
    nonisolated struct Outcome: Sendable {
        var attachment: ChatAttachment
        /// True when the file yielded no readable text at all (an unreadable scan, or everything
        /// redacted). The composer says so rather than silently attaching nothing.
        var isEmpty: Bool
    }

    nonisolated enum Failure: LocalizedError {
        case tooLarge
        case unreadable
        case unsupported

        var errorDescription: String? {
            switch self {
            case .tooLarge: String(localized: "That file is too large to attach.")
            case .unreadable: String(localized: "That file could not be read.")
            case .unsupported: String(localized: "That file type can't be attached.")
            }
        }
    }

    var extractor = RecordTextExtractor()
    var files: CoachFileStore

    /// Reads `url` (a security-scoped document URL from the file importer), extracts and redacts its
    /// text, and stores the original blob.
    func process(url: URL, nowMs: Int64 = Int64(Date().timeIntervalSince1970 * 1000)) async throws -> Outcome {
        let scoped = url.startAccessingSecurityScopedResource()
        defer { if scoped { url.stopAccessingSecurityScopedResource() } }

        guard let data = try? Data(contentsOf: url, options: .mappedIfSafe) else {
            throw Failure.unreadable
        }
        guard data.count <= CoachFileStore.maxFileBytes else { throw Failure.tooLarge }

        let filename = url.lastPathComponent
        let type = UTType(filenameExtension: url.pathExtension.lowercased())
        let kind: ChatAttachment.Kind
        if type?.conforms(to: .pdf) == true || url.pathExtension.lowercased() == "pdf" {
            kind = .pdf
        } else if type?.conforms(to: .text) == true || type?.conforms(to: .plainText) == true {
            kind = .text
        } else {
            throw Failure.unsupported
        }

        let attachmentID = UUID().uuidString.lowercased()
        let pages = try await pageTexts(kind: kind, url: url, data: data, attachmentID: attachmentID)
        let excerpt = CR.attachmentExcerpt(pages)
        let text = excerpt["text"].string

        guard let path = try? files.writeOriginal(data, attachmentID: attachmentID,
                                                  fileExtension: url.pathExtension.lowercased())
        else { throw Failure.unreadable }

        let attachment = ChatAttachment(
            id: attachmentID,
            kind: kind,
            filename: filename.isEmpty ? String(localized: "Document") : filename,
            mimeType: type?.preferredMIMEType,
            bytes: data.count,
            sha256: CoachFileStore.sha256(data),
            pageCount: excerpt["pages_total"].double.map { Int($0) },
            charCount: excerpt["chars"].double.map { Int($0) },
            excerpt: text,
            filePath: path,
            createdMs: nowMs
        )
        return Outcome(attachment: attachment, isEmpty: text == nil)
    }

    /// A typed or pasted note: no file, no extraction, but the same redaction so a note holding an
    /// ID number is treated like any other text.
    func processNote(_ raw: String, nowMs: Int64 = Int64(Date().timeIntervalSince1970 * 1000)) -> Outcome {
        let excerpt = CR.attachmentExcerpt([raw])
        let text = excerpt["text"].string
        let attachment = ChatAttachment(
            id: UUID().uuidString.lowercased(),
            kind: .note,
            filename: String(localized: "Note"),
            mimeType: "text/plain",
            bytes: raw.utf8.count,
            pageCount: nil,
            charCount: excerpt["chars"].double.map { Int($0) },
            excerpt: text,
            createdMs: nowMs
        )
        return Outcome(attachment: attachment, isEmpty: text == nil)
    }

    private func pageTexts(kind: ChatAttachment.Kind, url: URL, data: Data, attachmentID: String) async throws -> [String] {
        switch kind {
        case .pdf:
            let result = await extractor.extract(
                recordID: attachmentID,
                url: url,
                fileType: .pdf,
                pageRange: 1...CR.maxAttachmentPages
            )
            if result.pages.isEmpty, result.error != nil { throw Failure.unreadable }
            return result.pages
                .sorted { $0.pageIndex < $1.pageIndex }
                .map { $0.text ?? "" }
        case .text, .note:
            guard let text = String(data: data, encoding: .utf8)
                ?? String(data: data, encoding: .isoLatin1)
            else { throw Failure.unreadable }
            return [text]
        case .image:
            throw Failure.unsupported
        }
    }
}
