import CryptoKit
import Foundation
import OSLog

/// Downloads and disk-caches catalogue exercise media (180×180 JPEG thumbnails and GIF
/// demonstrations, © Gym visual) referenced by `ExerciseLibraryItem.imageURL`/`gifURL`.
///
/// - Cached files are keyed by a hash of the remote URL, which is pinned to a dataset
///   commit, so a cached file never goes stale.
/// - Concurrent requests for one URL share a single download.
/// - A failed download is not retried for `failureRetryInterval`, so scrolling offline
///   never hammers the network; the views retry on their own backoff.
/// - The cache is trimmed oldest-first once it grows past `maxCacheBytes`.
actor ExerciseMediaStore {
    nonisolated static let shared = ExerciseMediaStore()

    nonisolated static let maxMediaBytes = 4 * 1_024 * 1_024
    nonisolated static let maxCacheBytes = 64 * 1_024 * 1_024
    nonisolated static let trimmedCacheBytes = 48 * 1_024 * 1_024
    /// Shorter than the views' first 15 s retry, so their backoff actually reaches the network.
    nonisolated static let failureRetryInterval: TimeInterval = 10

    nonisolated private static let logger = Logger(subsystem: "com.ayuvo.health", category: "ExerciseMediaStore")

    private let session: URLSession
    private let cacheDirectory: URL
    private var inFlight: [URL: Task<URL?, Never>] = [:]
    private var failedAt: [URL: Date] = [:]
    private var writesSinceTrim = 0

    init(
        session: URLSession = .shared,
        cacheDirectory: URL = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("ExerciseMedia", isDirectory: true)
    ) {
        self.session = session
        self.cacheDirectory = cacheDirectory
    }

    /// Local file for `remoteURL`, downloading it on a cache miss. Nil while unavailable.
    func localURL(for remoteURL: URL) async -> URL? {
        guard Self.isAllowedRemoteURL(remoteURL) else { return nil }

        let destination = cacheDirectory.appendingPathComponent(Self.cacheFileName(for: remoteURL))
        if FileManager.default.fileExists(atPath: destination.path) {
            return destination
        }

        if let task = inFlight[remoteURL] {
            return await task.value
        }

        if let failedAt = failedAt[remoteURL], Date().timeIntervalSince(failedAt) < Self.failureRetryInterval {
            return nil
        }

        let task = Task { await self.download(remoteURL, to: destination) }
        inFlight[remoteURL] = task
        let result = await task.value
        inFlight[remoteURL] = nil
        if result == nil {
            failedAt[remoteURL] = Date()
        } else {
            failedAt[remoteURL] = nil
        }
        return result
    }

    private func download(_ remoteURL: URL, to destination: URL) async -> URL? {
        do {
            var request = URLRequest(url: remoteURL)
            request.timeoutInterval = 30
            let (data, response) = try await session.data(for: request)
            guard let http = response as? HTTPURLResponse, http.statusCode == 200 else {
                Self.logger.debug("media download rejected: \(remoteURL.lastPathComponent, privacy: .public)")
                return nil
            }
            guard Self.isValidMediaData(data) else {
                Self.logger.debug("media payload invalid: \(remoteURL.lastPathComponent, privacy: .public)")
                return nil
            }

            try FileManager.default.createDirectory(at: cacheDirectory, withIntermediateDirectories: true)
            try data.write(to: destination, options: .atomic)
            writesSinceTrim += 1
            if writesSinceTrim >= 32 {
                writesSinceTrim = 0
                trimCacheIfNeeded()
            }
            return destination
        } catch {
            Self.logger.debug("media download failed: \(remoteURL.lastPathComponent, privacy: .public)")
            return nil
        }
    }

    func trimCacheIfNeeded() {
        let keys: Set<URLResourceKey> = [.fileSizeKey, .contentModificationDateKey]
        guard let files = try? FileManager.default.contentsOfDirectory(
            at: cacheDirectory,
            includingPropertiesForKeys: Array(keys)
        ) else { return }

        let entries = files.compactMap { url -> (url: URL, size: Int, date: Date)? in
            guard let values = try? url.resourceValues(forKeys: keys) else { return nil }
            return (url, values.fileSize ?? 0, values.contentModificationDate ?? .distantPast)
        }
        var total = entries.reduce(0) { $0 + $1.size }
        guard total > Self.maxCacheBytes else { return }

        for entry in entries.sorted(by: { $0.date < $1.date }) {
            guard total > Self.trimmedCacheBytes else { break }
            try? FileManager.default.removeItem(at: entry.url)
            total -= entry.size
        }
    }

    nonisolated static func isAllowedRemoteURL(_ url: URL) -> Bool {
        url.scheme?.lowercased() == "https" && url.host != nil
    }

    nonisolated static func cacheFileName(for remoteURL: URL) -> String {
        let digest = SHA256.hash(data: Data(remoteURL.absoluteString.utf8))
            .prefix(16)
            .map { String(format: "%02x", $0) }
            .joined()
        let pathExtension = remoteURL.pathExtension.lowercased()
        let safeExtension = ["jpg", "jpeg", "gif", "png"].contains(pathExtension) ? pathExtension : "img"
        return "\(digest).\(safeExtension)"
    }

    nonisolated static func isValidMediaData(_ data: Data) -> Bool {
        guard !data.isEmpty, data.count <= maxMediaBytes else { return false }
        return looksLikeJPEG(data) || looksLikeGIF(data) || looksLikePNG(data)
    }

    nonisolated static func looksLikeJPEG(_ data: Data) -> Bool {
        data.count >= 3 && data.prefix(3).elementsEqual([0xFF, 0xD8, 0xFF])
    }

    nonisolated static func looksLikeGIF(_ data: Data) -> Bool {
        data.count >= 6 && (data.prefix(6).elementsEqual(Array("GIF89a".utf8)) || data.prefix(6).elementsEqual(Array("GIF87a".utf8)))
    }

    nonisolated static func looksLikePNG(_ data: Data) -> Bool {
        data.count >= 8 && data.prefix(8).elementsEqual([0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A])
    }
}
