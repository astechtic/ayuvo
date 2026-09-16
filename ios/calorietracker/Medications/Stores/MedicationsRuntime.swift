import Foundation

/// Owns the opened medications database and the photo store. Opened lazily on first use; the
/// notification action handler (running before any SwiftUI view exists) and the store share
/// `MedicationsRuntime.shared` exactly like `HealthDataRuntime.shared`.
@MainActor
final class MedicationsRuntime {
    static let shared = MedicationsRuntime()

    let databaseURL: URL
    let photos: MedicationPhotoStore
    private(set) var database: MedicationsDatabase?
    private(set) var openError: Error?
    private(set) var quarantinedURL: URL?
    private var openTask: Task<Void, Never>?

    init(
        databaseURL: URL = MedicationsLocation.databaseURL(),
        photosDirectory: URL = MedicationsLocation.photosDirectory()
    ) {
        self.databaseURL = databaseURL
        self.photos = MedicationPhotoStore(directory: photosDirectory)
    }

    var isOpen: Bool { database != nil }
    var isDegraded: Bool { database == nil && openError != nil }

    /// `true` when the database file exists (never opens it, so planning can skip an empty install).
    var databaseExists: Bool { FileManager.default.fileExists(atPath: databaseURL.path) }

    var repository: MedicationsRepository? {
        database.map { MedicationsRepository(database: $0, photos: photos) }
    }

    /// Opens once (single-flight, quarantining a corrupt file). Returns `true` when usable.
    @discardableResult
    func openIfNeeded() async -> Bool {
        if database != nil { return true }
        if let openTask {
            await openTask.value
            return database != nil
        }
        let url = databaseURL
        let task = Task { [weak self] in
            do {
                let (database, quarantined) = try await MedicationsDatabase.openQuarantiningCorruption(url: url)
                guard let self else { return }
                self.database = database
                self.quarantinedURL = quarantined
                self.openError = nil
            } catch {
                self?.openError = error
            }
        }
        openTask = task
        await task.value
        openTask = nil
        return database != nil
    }

    func close() async {
        if let database { await database.close() }
        database = nil
    }

    /// Closes the connection and removes the database, its sidecars and every photo (Delete All Data).
    func deleteAllData() async throws {
        await close()
        openError = nil
        try HealthDatabaseLocation.removeDatabaseFiles(at: databaseURL)
        photos.deleteAll()
    }

    func storageBytes() -> Int64 {
        HealthDatabaseLocation.totalSizeBytes(for: databaseURL) + photos.totalSizeBytes()
    }
}
