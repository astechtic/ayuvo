import Foundation

/// Sniffed type of an imported file (contract §3 `file_type` / `mime_type`).
nonisolated struct RecordSniffResult: Equatable, Sendable {
    var fileType: RecordFileType
    var mimeType: String
    var fileExtension: String
}

/// Decides the type from bytes. The sender's MIME type and the filename are hints only; the
/// filename extension is used for exactly one thing: `.md` text becomes `text/markdown`.
nonisolated enum RecordMimeSniffer {
    static let heifBrands: Set<String> = ["heic", "heix", "hevc", "heim", "heis", "mif1", "msf1"]

    /// - Parameters:
    ///   - prefix: leading bytes of the file (the first 32 are inspected for magic numbers;
    ///     up to 64 KB for the text check).
    ///   - isComplete: `prefix` is the whole file (lets a truncated multi-byte UTF-8 tail count as invalid).
    ///   - filename: original filename hint.
    static func sniff(prefix: Data, isComplete: Bool, filename: String?) -> RecordSniffResult {
        let bytes = [UInt8](prefix.prefix(32))
        if bytes.starts(with: Array("%PDF-".utf8)) {
            return RecordSniffResult(fileType: .pdf, mimeType: "application/pdf", fileExtension: "pdf")
        }
        if bytes.starts(with: [0xFF, 0xD8, 0xFF]) {
            return RecordSniffResult(fileType: .image, mimeType: "image/jpeg", fileExtension: "jpg")
        }
        if bytes.starts(with: [0x89, 0x50, 0x4E, 0x47]) {
            return RecordSniffResult(fileType: .image, mimeType: "image/png", fileExtension: "png")
        }
        if isHEIF(bytes) {
            return RecordSniffResult(fileType: .image, mimeType: "image/heic", fileExtension: "heic")
        }
        if bytes.count >= 12, bytes.starts(with: Array("RIFF".utf8)), Array(bytes[8..<12]) == Array("WEBP".utf8) {
            return RecordSniffResult(fileType: .image, mimeType: "image/webp", fileExtension: "webp")
        }
        if bytes.starts(with: Array("GIF8".utf8)) {
            return RecordSniffResult(fileType: .image, mimeType: "image/gif", fileExtension: "gif")
        }
        if !prefix.isEmpty, isUTF8Text(prefix, isComplete: isComplete) {
            let ext = (filename.map { ($0 as NSString).pathExtension.lowercased() }) ?? ""
            if ext == "md" || ext == "markdown" {
                return RecordSniffResult(fileType: .text, mimeType: "text/markdown", fileExtension: "md")
            }
            return RecordSniffResult(fileType: .text, mimeType: "text/plain", fileExtension: "txt")
        }
        return RecordSniffResult(fileType: .other, mimeType: "application/octet-stream", fileExtension: "bin")
    }

    /// ISO BMFF `ftyp` box whose major brand (or a compatible brand within the first 32 bytes)
    /// is a HEIC/HEIF brand.
    static func isHEIF(_ bytes: [UInt8]) -> Bool {
        guard bytes.count >= 12, Array(bytes[4..<8]) == Array("ftyp".utf8) else { return false }
        var offset = 8
        while offset + 4 <= bytes.count {
            if offset != 12, let brand = String(bytes: bytes[offset..<offset + 4], encoding: .ascii), heifBrands.contains(brand) {
                return true
            }
            offset += 4
        }
        return false
    }

    /// Valid UTF-8 with no NUL bytes. When `isComplete` is false a multi-byte sequence cut off
    /// by the prefix boundary (at most 3 trailing bytes) is tolerated.
    static func isUTF8Text(_ data: Data, isComplete: Bool) -> Bool {
        if data.contains(0) { return false }
        if String(data: data, encoding: .utf8) != nil { return true }
        guard !isComplete else { return false }
        for trim in 1...3 where data.count > trim {
            if String(data: data.dropLast(trim), encoding: .utf8) != nil { return true }
        }
        return false
    }
}
