import Foundation

/// On-disk layout of Health Records (contract §2):
/// `Application Support/Ayuvo/Records/records.sqlite`, `…/Records/files/<id>/original.<ext>`,
/// `Caches/Records/render/`, `tmp/records-share/` and the app-group `RecordsInbox/`.
/// `Ayuvo/Records/` is excluded from iCloud / device backup.
nonisolated enum RecordsLocation {
    static let directoryName = "Records"
    static let databaseFileName = "records.sqlite"
    static let filesDirectoryName = "files"
    static let inboxDirectoryName = "RecordsInbox"

    static func directory(fileManager: FileManager = .default) -> URL {
        let base = fileManager.urls(for: .applicationSupportDirectory, in: .userDomainMask).first
            ?? fileManager.temporaryDirectory
        return base
            .appendingPathComponent("Ayuvo", isDirectory: true)
            .appendingPathComponent(directoryName, isDirectory: true)
    }

    static func databaseURL(fileManager: FileManager = .default) -> URL {
        directory(fileManager: fileManager).appendingPathComponent(databaseFileName, isDirectory: false)
    }

    static func filesRoot(fileManager: FileManager = .default) -> URL {
        directory(fileManager: fileManager).appendingPathComponent(filesDirectoryName, isDirectory: true)
    }

    static func renderCacheDirectory(fileManager: FileManager = .default) -> URL {
        let base = fileManager.urls(for: .cachesDirectory, in: .userDomainMask).first ?? fileManager.temporaryDirectory
        return base
            .appendingPathComponent(directoryName, isDirectory: true)
            .appendingPathComponent("render", isDirectory: true)
    }

    static func shareTempDirectory(fileManager: FileManager = .default) -> URL {
        fileManager.temporaryDirectory.appendingPathComponent("records-share", isDirectory: true)
    }

    static var appGroupID: String {
        Bundle.main.object(forInfoDictionaryKey: "AppGroupIdentifier") as? String ?? "group.com.ayuvo.health"
    }

    /// App-group inbox the share extension writes to; nil when the group is unavailable.
    static func inboxDirectory(fileManager: FileManager = .default) -> URL? {
        fileManager.containerURL(forSecurityApplicationGroupIdentifier: appGroupID)?
            .appendingPathComponent(inboxDirectoryName, isDirectory: true)
    }

    /// Creates `directory` and marks it excluded from backup (same rule as the health mirror).
    static func prepareDirectory(_ directory: URL, fileManager: FileManager = .default) throws {
        try HealthDatabaseLocation.prepareDirectory(directory, fileManager: fileManager)
    }
}
