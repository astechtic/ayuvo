import Foundation

/// On-disk layout of cycle tracking (docs/cycle-tracking.md §2): `Application Support/Ayuvo/Cycle/cycle.sqlite`.
/// `Ayuvo/Cycle/` is excluded from iCloud / device backup like the health mirror and medications.
nonisolated enum CycleLocation {
    static let directoryName = "Cycle"
    static let databaseFileName = "cycle.sqlite"

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

    /// Creates `directory` and marks it excluded from backup (same rule as the health mirror).
    static func prepareDirectory(_ directory: URL, fileManager: FileManager = .default) throws {
        try HealthDatabaseLocation.prepareDirectory(directory, fileManager: fileManager)
    }
}
