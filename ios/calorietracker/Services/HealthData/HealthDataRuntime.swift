import Foundation
import HealthKit

/// Owns the SQLite writer/reader pair and the HealthKit reader. Databases are opened
/// lazily on first use; if the file cannot be opened the runtime runs degraded (the hub
/// shows an error state, nothing else in the app is affected).
@MainActor
final class HealthDataRuntime {
    static let shared = HealthDataRuntime()

    let databaseURL: URL
    let hkReader: LiveHealthKitReader
    private(set) var writer: HealthDatabase?
    private(set) var reader: HealthDatabase?
    private(set) var openError: Error?
    private(set) var quarantinedURL: URL?
    private var openTask: Task<Void, Never>?

    /// Installed by `HealthDataStore`; `HealthKitManager.requestAuthorization()` calls
    /// `authorizationDidChange()` so a widened grant triggers a sync without a store handle.
    var onAuthorizationChanged: (() -> Void)?

    init(
        databaseURL: URL = HealthDatabaseLocation.databaseURL(),
        hkReader: LiveHealthKitReader = LiveHealthKitReader()
    ) {
        self.databaseURL = databaseURL
        self.hkReader = hkReader
    }

    var isDegraded: Bool { writer == nil && openError != nil }
    var isOpen: Bool { writer != nil }

    /// Opens both connections once. Returns `true` when the database is usable.
    @discardableResult
    func openIfNeeded() async -> Bool {
        if writer != nil { return true }
        if let openTask {
            await openTask.value
            return writer != nil
        }
        let url = databaseURL
        let task = Task { [weak self] in
            do {
                let (writer, quarantined) = try await HealthDatabase.openQuarantiningCorruption(url: url)
                let reader = try await HealthDatabase.open(url: url, readOnly: true)
                guard let self else { return }
                self.writer = writer
                self.reader = reader
                self.quarantinedURL = quarantined
                self.openError = nil
            } catch {
                self?.openError = error
            }
        }
        openTask = task
        await task.value
        openTask = nil
        return writer != nil
    }

    func makeEngine(types: [HealthMetricType], configuration: HealthSyncEngine.Configuration = .init()) -> HealthSyncEngine? {
        guard let writer else { return nil }
        return HealthSyncEngine(reader: hkReader, database: writer, types: types, configuration: configuration)
    }

    /// Closes both connections and removes the database + sidecars. The directory stays
    /// (still excluded from backup) so the next sync can recreate the mirror.
    func closeAndDeleteFiles() async throws {
        if let writer { await writer.close() }
        if let reader { await reader.close() }
        writer = nil
        reader = nil
        openError = nil
        try HealthDatabaseLocation.removeDatabaseFiles(at: databaseURL)
    }

    func databaseSizeBytes() -> Int64 {
        HealthDatabaseLocation.totalSizeBytes(for: databaseURL)
    }

    func authorizationDidChange() {
        onAuthorizationChanged?()
    }
}
