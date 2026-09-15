import CryptoKit
import Foundation

nonisolated enum RecordFileError: Error, Equatable, Sendable {
    /// The source is larger than `RecordFileStore.maxFileBytes`; nothing was kept.
    case tooLarge(limit: Int64)
    case unreadable(String)
    case writeFailed(String)
}

/// Result of streaming a source into `<root>/<id>/original.tmp`.
nonisolated struct RecordCopyResult: Sendable, Equatable {
    var tempURL: URL
    var sha256: String
    var size: Int64
    /// First bytes of the file for sniffing (up to `RecordFileStore.sniffPrefixBytes`).
    var prefix: Data
}

/// Originals and thumbnails under the records files root (contract §2). Every original is
/// copied byte-for-byte: stream → `original.tmp` (SHA-256 while copying) → atomic rename.
/// Paths stored in the database are relative to `root`.
nonisolated struct RecordFileStore: Sendable {
    static let maxFileBytes: Int64 = 512 * 1_024 * 1_024
    static let sniffPrefixBytes = 64 * 1_024
    static let chunkBytes = 1_024 * 1_024
    static let thumbnailFileName = "thumb.jpg"

    let root: URL

    init(root: URL = RecordsLocation.filesRoot()) {
        self.root = root
    }

    func directory(for id: String) -> URL {
        root.appendingPathComponent(id, isDirectory: true)
    }

    func url(forRelativePath path: String) -> URL {
        root.appendingPathComponent(path, isDirectory: false)
    }

    static func originalRelativePath(id: String, ext: String) -> String {
        "\(id)/original.\(ext)"
    }

    static func thumbnailRelativePath(id: String) -> String {
        "\(id)/\(thumbnailFileName)"
    }

    private func prepareRecordDirectory(_ id: String) throws -> URL {
        let fileManager = FileManager.default
        if !fileManager.fileExists(atPath: root.path) {
            // Parent `Ayuvo/Records/` carries the backup exclusion; mark `files/` too.
            try RecordsLocation.prepareDirectory(root)
        }
        let directory = directory(for: id)
        try fileManager.createDirectory(at: directory, withIntermediateDirectories: true)
        return directory
    }

    /// Streams `source` into `<id>/original.tmp`, hashing as it goes. Aborts (and removes the
    /// record directory) past `maxBytes`.
    func copyToTemp(from source: URL, id: String, maxBytes: Int64 = RecordFileStore.maxFileBytes) throws -> RecordCopyResult {
        guard let input = InputStream(url: source) else {
            throw RecordFileError.unreadable(source.lastPathComponent)
        }
        let directory = try prepareRecordDirectory(id)
        let tempURL = directory.appendingPathComponent("original.tmp")
        FileManager.default.createFile(atPath: tempURL.path, contents: nil)
        guard let output = try? FileHandle(forWritingTo: tempURL) else {
            try? FileManager.default.removeItem(at: directory)
            throw RecordFileError.writeFailed(tempURL.lastPathComponent)
        }
        input.open()
        defer { input.close() }
        var hasher = SHA256()
        var size: Int64 = 0
        var prefix = Data()
        var buffer = [UInt8](repeating: 0, count: Self.chunkBytes)
        do {
            while true {
                let read = input.read(&buffer, maxLength: buffer.count)
                if read < 0 {
                    throw RecordFileError.unreadable(input.streamError?.localizedDescription ?? source.lastPathComponent)
                }
                if read == 0 { break }
                size += Int64(read)
                if size > maxBytes {
                    throw RecordFileError.tooLarge(limit: maxBytes)
                }
                let chunk = Data(bytes: buffer, count: read)
                hasher.update(data: chunk)
                if prefix.count < Self.sniffPrefixBytes {
                    prefix.append(chunk.prefix(Self.sniffPrefixBytes - prefix.count))
                }
                try output.write(contentsOf: chunk)
            }
            try output.synchronize()
            try output.close()
        } catch {
            try? output.close()
            try? FileManager.default.removeItem(at: directory)
            if let fileError = error as? RecordFileError { throw fileError }
            throw RecordFileError.writeFailed(error.localizedDescription)
        }
        return RecordCopyResult(tempURL: tempURL, sha256: Self.hex(hasher.finalize()), size: size, prefix: prefix)
    }

    /// Writes in-memory bytes (pasted text, camera JPEG, scanned PDF) the same way.
    func writeToTemp(_ data: Data, id: String, maxBytes: Int64 = RecordFileStore.maxFileBytes) throws -> RecordCopyResult {
        guard Int64(data.count) <= maxBytes else { throw RecordFileError.tooLarge(limit: maxBytes) }
        let directory = try prepareRecordDirectory(id)
        let tempURL = directory.appendingPathComponent("original.tmp")
        do {
            try data.write(to: tempURL, options: .atomic)
        } catch {
            try? FileManager.default.removeItem(at: directory)
            throw RecordFileError.writeFailed(error.localizedDescription)
        }
        return RecordCopyResult(
            tempURL: tempURL,
            sha256: Self.hex(SHA256.hash(data: data)),
            size: Int64(data.count),
            prefix: data.prefix(Self.sniffPrefixBytes)
        )
    }

    /// Renames `original.tmp` to `original.<ext>` and returns the relative path.
    func finalize(_ copy: RecordCopyResult, id: String, ext: String) throws -> String {
        let relative = Self.originalRelativePath(id: id, ext: ext)
        let destination = url(forRelativePath: relative)
        let fileManager = FileManager.default
        if fileManager.fileExists(atPath: destination.path) {
            try fileManager.removeItem(at: destination)
        }
        try fileManager.moveItem(at: copy.tempURL, to: destination)
        return relative
    }

    func writeThumbnail(_ jpeg: Data, id: String) throws -> String {
        _ = try prepareRecordDirectory(id)
        let relative = Self.thumbnailRelativePath(id: id)
        try jpeg.write(to: url(forRelativePath: relative), options: .atomic)
        return relative
    }

    func delete(id: String) {
        guard !id.isEmpty, !id.contains("/"), !id.contains("..") else { return }
        try? FileManager.default.removeItem(at: directory(for: id))
    }

    func deleteAll() {
        try? FileManager.default.removeItem(at: root)
    }

    func size(ofRelativePath path: String) -> Int64 {
        let attributes = try? FileManager.default.attributesOfItem(atPath: url(forRelativePath: path).path)
        return (attributes?[.size] as? NSNumber)?.int64Value ?? 0
    }

    /// Total bytes under the files root (originals + thumbnails).
    func totalSizeBytes() -> Int64 {
        guard let enumerator = FileManager.default.enumerator(at: root, includingPropertiesForKeys: [.fileSizeKey]) else { return 0 }
        var total: Int64 = 0
        for case let url as URL in enumerator {
            total += Int64((try? url.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0)
        }
        return total
    }

    static func hex(_ digest: SHA256.Digest) -> String {
        digest.map { String(format: "%02x", $0) }.joined()
    }
}
