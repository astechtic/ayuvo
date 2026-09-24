import Foundation

/// On-disk layout of Coach (docs/coach.md §2): `Application Support/Ayuvo/Coach/coach.sqlite` and
/// `…/Coach/files/<attachment id>/original.<ext>` + `thumb.jpg`.
///
/// `Ayuvo/Coach/` is excluded from device backup and opened with complete-until-first-authentication
/// protection, exactly like the health mirror, records and medications. Conversations quote health
/// values, so they leave the device only through the user-initiated export (§11).
nonisolated enum CoachLocation {
    static let directoryName = "Coach"
    static let databaseFileName = "coach.sqlite"
    static let filesDirectoryName = "files"
    static let originalBaseName = "original"
    static let thumbnailFileName = "thumb.jpg"

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

    static func filesDirectory(fileManager: FileManager = .default) -> URL {
        directory(fileManager: fileManager).appendingPathComponent(filesDirectoryName, isDirectory: true)
    }

    /// Creates `directory` and marks it excluded from backup (same rule as the health mirror).
    static func prepareDirectory(_ directory: URL, fileManager: FileManager = .default) throws {
        try HealthDatabaseLocation.prepareDirectory(directory, fileManager: fileManager)
    }

    /// Database + sidecars + attachment blobs; used by "Delete all chats" and Delete All Data.
    static func removeAll(fileManager: FileManager = .default) throws {
        let root = directory(fileManager: fileManager)
        if fileManager.fileExists(atPath: root.path) {
            try fileManager.removeItem(at: root)
        }
    }
}
