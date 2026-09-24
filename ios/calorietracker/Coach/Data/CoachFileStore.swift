import CryptoKit
import Foundation
import UIKit

/// Attachment blobs on disk (docs/coach.md §2): `Coach/files/<attachment id>/original.<ext>` plus an
/// optional `thumb.jpg`. Mirrors `RecordFileStore`; paths are stored in the database **relative** to
/// the files root so moving the container never breaks them, and `resolve` refuses to escape it.
nonisolated struct CoachFileStore: Sendable {
    /// An attachment larger than this is refused before anything is written.
    static let maxFileBytes = 64 * 1024 * 1024
    static let thumbnailMaxDimension: CGFloat = 320

    let root: URL
    private let fileManager: FileManager

    init(root: URL? = nil, fileManager: FileManager = .default) {
        self.root = root ?? CoachLocation.filesDirectory(fileManager: fileManager)
        self.fileManager = fileManager
    }

    func directory(for attachmentID: String) -> URL {
        root.appendingPathComponent(attachmentID, isDirectory: true)
    }

    /// A stored relative path -> an absolute URL, or nil when the path tries to escape the root.
    func resolve(_ relativePath: String?) -> URL? {
        guard let relativePath, !relativePath.isEmpty else { return nil }
        if relativePath.hasPrefix("/") || relativePath.contains("..") { return nil }
        return root.appendingPathComponent(relativePath, isDirectory: false)
    }

    func relativePath(_ url: URL) -> String? {
        let base = root.standardizedFileURL.path
        let path = url.standardizedFileURL.path
        guard path.hasPrefix(base + "/") else { return nil }
        return String(path.dropFirst(base.count + 1))
    }

    static func sha256(_ data: Data) -> String {
        SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined()
    }

    /// Writes `data` as the attachment's original and returns the relative path. The directory is
    /// created on demand; an existing original is replaced.
    @discardableResult
    func writeOriginal(_ data: Data, attachmentID: String, fileExtension: String) throws -> String {
        guard data.count <= Self.maxFileBytes else {
            throw CocoaError(.fileWriteOutOfSpace)
        }
        let folder = directory(for: attachmentID)
        try prepare(folder)
        let ext = fileExtension.isEmpty ? "bin" : fileExtension
        let url = folder.appendingPathComponent("\(CoachLocation.originalBaseName).\(ext)", isDirectory: false)
        try data.write(to: url, options: .atomic)
        return relativePath(url) ?? "\(attachmentID)/\(CoachLocation.originalBaseName).\(ext)"
    }

    /// Downsamples and writes `thumb.jpg`; returns the relative path, or nil when the image could not
    /// be decoded (a thumbnail is a convenience, never a reason to fail the attachment).
    @discardableResult
    func writeThumbnail(_ image: UIImage, attachmentID: String) -> String? {
        guard let scaled = Self.downsample(image, maxDimension: Self.thumbnailMaxDimension),
              let data = scaled.jpegData(compressionQuality: 0.7)
        else { return nil }
        let folder = directory(for: attachmentID)
        guard (try? prepare(folder)) != nil else { return nil }
        let url = folder.appendingPathComponent(CoachLocation.thumbnailFileName, isDirectory: false)
        guard (try? data.write(to: url, options: .atomic)) != nil else { return nil }
        return relativePath(url)
    }

    func data(at relativePath: String?) -> Data? {
        guard let url = resolve(relativePath) else { return nil }
        return try? Data(contentsOf: url)
    }

    func image(at relativePath: String?) -> UIImage? {
        guard let data = data(at: relativePath) else { return nil }
        return UIImage(data: data)
    }

    func exists(_ relativePath: String?) -> Bool {
        guard let url = resolve(relativePath) else { return false }
        return fileManager.fileExists(atPath: url.path)
    }

    /// Removes one attachment's folder. Missing is success — the caller is deleting it either way.
    func delete(attachmentID: String) {
        let folder = directory(for: attachmentID)
        if fileManager.fileExists(atPath: folder.path) {
            try? fileManager.removeItem(at: folder)
        }
    }

    func deleteAll() {
        if fileManager.fileExists(atPath: root.path) {
            try? fileManager.removeItem(at: root)
        }
    }

    /// Folders with no row left in the database, so a crash between the two never leaks a blob.
    func orphanedDirectories(knownIDs: Set<String>) -> [String] {
        let names = (try? fileManager.contentsOfDirectory(atPath: root.path)) ?? []
        return names.filter { !knownIDs.contains($0) }
    }

    func totalBytes() -> Int64 {
        guard let walker = fileManager.enumerator(at: root, includingPropertiesForKeys: [.fileSizeKey]) else { return 0 }
        var total: Int64 = 0
        for case let url as URL in walker {
            let size = (try? url.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0
            total += Int64(size)
        }
        return total
    }

    private func prepare(_ folder: URL) throws {
        if !fileManager.fileExists(atPath: folder.path) {
            try fileManager.createDirectory(at: folder, withIntermediateDirectories: true)
        }
    }

    /// Longest side capped at `maxDimension`, aspect preserved. Returns the original when it already
    /// fits, so a small image is never re-encoded.
    static func downsample(_ image: UIImage, maxDimension: CGFloat) -> UIImage? {
        let longest = max(image.size.width, image.size.height)
        guard longest > maxDimension, longest > 0 else { return image }
        let scale = maxDimension / longest
        let size = CGSize(width: (image.size.width * scale).rounded(), height: (image.size.height * scale).rounded())
        let format = UIGraphicsImageRendererFormat.default()
        format.scale = 1
        return UIGraphicsImageRenderer(size: size, format: format).image { _ in
            image.draw(in: CGRect(origin: .zero, size: size))
        }
    }
}
