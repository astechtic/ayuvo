import Foundation

/// `Application Support/Ayuvo/Health/health.sqlite`. The directory is excluded from
/// iCloud / iTunes backup (App Store 5.1.3(ii): health data never enters iCloud) and
/// the file is opened with complete-until-first-user-authentication protection.
nonisolated enum HealthDatabaseLocation {
    static let directoryName = "Health"
    static let fileName = "health.sqlite"
    /// SQLite writes these next to the main file; every delete must cover all of them.
    static let sidecarSuffixes = ["", "-wal", "-shm", "-journal"]

    static func directory(fileManager: FileManager = .default) -> URL {
        let base = fileManager.urls(for: .applicationSupportDirectory, in: .userDomainMask).first
            ?? fileManager.temporaryDirectory
        return base
            .appendingPathComponent("Ayuvo", isDirectory: true)
            .appendingPathComponent(directoryName, isDirectory: true)
    }

    static func databaseURL(fileManager: FileManager = .default) -> URL {
        directory(fileManager: fileManager).appendingPathComponent(fileName, isDirectory: false)
    }

    /// Creates the directory and marks it excluded from backup. Safe to call repeatedly.
    static func prepareDirectory(_ directory: URL, fileManager: FileManager = .default) throws {
        try fileManager.createDirectory(at: directory, withIntermediateDirectories: true)
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        var mutable = directory
        try mutable.setResourceValues(values)
    }

    static func isExcludedFromBackup(_ directory: URL) -> Bool {
        (try? directory.resourceValues(forKeys: [.isExcludedFromBackupKey]).isExcludedFromBackup) ?? false
    }

    static func allFileURLs(for databaseURL: URL) -> [URL] {
        sidecarSuffixes.map { suffix in
            suffix.isEmpty
                ? databaseURL
                : databaseURL.deletingLastPathComponent().appendingPathComponent(databaseURL.lastPathComponent + suffix)
        }
    }

    /// Removes the database and every sidecar. Missing files are not an error.
    static func removeDatabaseFiles(at databaseURL: URL, fileManager: FileManager = .default) throws {
        for url in allFileURLs(for: databaseURL) where fileManager.fileExists(atPath: url.path) {
            try fileManager.removeItem(at: url)
        }
    }

    /// Moves an unreadable database aside (`health.sqlite.corrupt-<ms>` + sidecars) so a
    /// fresh mirror can be rebuilt from HealthKit without losing the bytes.
    static func quarantine(_ databaseURL: URL, fileManager: FileManager = .default) throws -> URL {
        let stamp = Int(Date().timeIntervalSince1970 * 1000)
        let target = databaseURL.deletingLastPathComponent()
            .appendingPathComponent("\(databaseURL.lastPathComponent).corrupt-\(stamp)")
        for (source, suffix) in zip(allFileURLs(for: databaseURL), sidecarSuffixes) where fileManager.fileExists(atPath: source.path) {
            let destination = suffix.isEmpty
                ? target
                : target.deletingLastPathComponent().appendingPathComponent(target.lastPathComponent + suffix)
            try fileManager.moveItem(at: source, to: destination)
        }
        return target
    }

    static func totalSizeBytes(for databaseURL: URL, fileManager: FileManager = .default) -> Int64 {
        allFileURLs(for: databaseURL).reduce(into: Int64(0)) { total, url in
            if let size = (try? fileManager.attributesOfItem(atPath: url.path)[.size]) as? NSNumber {
                total += size.int64Value
            }
        }
    }
}
