import Foundation

/// On-disk layout of Medications (contract §2):
/// `Application Support/Ayuvo/Medications/medications.sqlite` and `…/Medications/photos/<id>.jpg`.
/// `Ayuvo/Medications/` is excluded from iCloud / device backup like the health mirror and records.
nonisolated enum MedicationsLocation {
    static let directoryName = "Medications"
    static let databaseFileName = "medications.sqlite"
    static let photosDirectoryName = "photos"

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

    static func photosDirectory(fileManager: FileManager = .default) -> URL {
        directory(fileManager: fileManager).appendingPathComponent(photosDirectoryName, isDirectory: true)
    }

    /// Creates `directory` and marks it excluded from backup (same rule as the health mirror).
    static func prepareDirectory(_ directory: URL, fileManager: FileManager = .default) throws {
        try HealthDatabaseLocation.prepareDirectory(directory, fileManager: fileManager)
    }

    /// Database + sidecars + photos; used by Delete All Data.
    static func removeAll(fileManager: FileManager = .default) throws {
        let root = directory(fileManager: fileManager)
        if fileManager.fileExists(atPath: root.path) {
            try fileManager.removeItem(at: root)
        }
    }
}
