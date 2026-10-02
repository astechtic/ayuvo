import Foundation

/// Owns the opened cycle database. Opened lazily on first use (single-flight, quarantining a corrupt file), shared
/// by the store, the export/import and the reminder planner like `MedicationsRuntime.shared`.
@MainActor
final class CycleRuntime {
    static let shared = CycleRuntime()

    let databaseURL: URL
    private(set) var database: CycleDatabase?
    private(set) var openError: Error?
    private(set) var quarantinedURL: URL?
    private var openTask: Task<Void, Never>?
    /// Tests inject an in-memory database.
    private let injected: CycleDatabase?

    init(databaseURL: URL = CycleLocation.databaseURL(), database: CycleDatabase? = nil) {
        self.databaseURL = databaseURL
        self.injected = database
        self.database = database
    }

    var isOpen: Bool { database != nil }

    /// `true` when the database file exists (never opens it).
    var databaseExists: Bool { injected != nil || FileManager.default.fileExists(atPath: databaseURL.path) }

    var repository: CycleRepository? { database.map { CycleRepository(database: $0) } }

    /// Opens once. Returns `true` when usable.
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
                let (database, quarantined) = try await CycleDatabase.openQuarantiningCorruption(url: url)
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

    /// The repository, opening the database first.
    func openRepository() async -> CycleRepository? {
        await openIfNeeded() ? repository : nil
    }

    func close() async {
        if let database, injected == nil { await database.close() }
        if injected == nil { database = nil }
    }

    /// Closes the connection and removes the database and its sidecars (Delete All Data).
    func deleteAllData() async throws {
        if let injected {
            try await injected.wipeAllData()
            return
        }
        await close()
        openError = nil
        try HealthDatabaseLocation.removeDatabaseFiles(at: databaseURL)
    }
}

extension Notification.Name {
    /// Posted after cycle data changed outside the store (import, delete all) so the UI reloads.
    static let cycleDataDidChange = Notification.Name("ayuvo.cycleDataDidChange")
}
