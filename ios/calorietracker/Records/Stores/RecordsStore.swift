import Foundation
import Observation

nonisolated struct RecordsBanner: Identifiable, Equatable, Sendable {
    let id = UUID()
    var message: String
    var systemImage: String = "checkmark.circle.fill"
}

/// "This looks like an existing record" (contract §4.5). Phase 1: Keep both · Cancel · Open existing.
nonisolated struct RecordDuplicatePrompt: Identifiable, Equatable, Sendable {
    var id: String { newRecord.id }
    var newRecord: HealthRecord
    var existing: HealthRecord
}

/// Main-actor façade for the Records tab: lazily opens the database, keeps the loaded
/// timeline pages, runs imports off the main thread and publishes a `revision` counter that
/// views observe to refresh. Injected with `.environment` from `calorietrackerApp`.
@Observable
@MainActor
final class RecordsStore {
    private let defaults: UserDefaults
    private let databaseURL: URL
    private let files: RecordFileStore
    private let inboxRoot: () -> URL?

    private(set) var repository: RecordsRepository?
    private(set) var openError: String?
    private var openTask: Task<Void, Never>?

    /// Bumped after every change to the records data.
    private(set) var revision = 0
    var viewMode: RecordsViewMode {
        didSet { defaults.set(viewMode.rawValue, forKey: RecordsViewMode.storageKey) }
    }
    private(set) var query = RecordQuery()
    private(set) var records: [HealthRecord] = []
    private(set) var recent: [HealthRecord] = []
    private(set) var totalCount = 0
    private(set) var hasMore = false
    private(set) var isLoadingFirstPage = false
    private(set) var isLoadingMore = false
    private(set) var hasLoadedOnce = false
    private(set) var loadError: String?
    private(set) var pendingImports = 0
    private var loadGeneration = 0
    /// Bumped by Delete All Data so queued background work never recreates wiped storage.
    private var dataGeneration = 0
    private var processingTask: Task<Void, Never>?
    private var isDrainingInbox = false

    var banner: RecordsBanner?
    var duplicatePrompts: [RecordDuplicatePrompt] = []
    var importErrorMessage: String?
    /// Incremented when something outside the tab (share extension, "Open in") wants the
    /// Records tab selected. `ContentView` observes it.
    private(set) var tabRequest = 0
    /// Record to push on the Records stack (consumed by `RecordsHomeView`).
    var navigationRequest: String?

    init(
        defaults: UserDefaults = .standard,
        databaseURL: URL = RecordsLocation.databaseURL(),
        files: RecordFileStore = RecordFileStore(),
        inboxRoot: @escaping () -> URL? = { RecordsLocation.inboxDirectory() }
    ) {
        self.defaults = defaults
        self.databaseURL = databaseURL
        self.files = files
        self.inboxRoot = inboxRoot
        self.viewMode = defaults.string(forKey: RecordsViewMode.storageKey).flatMap(RecordsViewMode.init(rawValue:)) ?? .defaultMode
    }

    // MARK: - Opening

    @discardableResult
    func openIfNeeded() async -> RecordsRepository? {
        if let repository { return repository }
        if let openTask {
            await openTask.value
            return repository
        }
        let url = databaseURL
        let files = files
        let task = Task { [weak self] in
            do {
                let (database, _) = try await RecordsDatabase.openQuarantiningCorruption(url: url)
                self?.repository = RecordsRepository(database: database, files: files)
                self?.openError = nil
            } catch {
                self?.openError = String(describing: error)
            }
        }
        openTask = task
        await task.value
        openTask = nil
        // Exports left from a previous session.
        let shareTemp = RecordsLocation.shareTempDirectory()
        Task.detached(priority: .background) { try? FileManager.default.removeItem(at: shareTemp) }
        return repository
    }

    // MARK: - Browsing

    func setQuery(_ newValue: RecordQuery) {
        guard newValue != query else { return }
        query = newValue
        Task { await reload() }
    }

    func reload() async {
        loadGeneration += 1
        let generation = loadGeneration
        guard let repository = await openIfNeeded() else {
            loadError = String(localized: "Couldn't load records.")
            hasLoadedOnce = true
            return
        }
        if records.isEmpty { isLoadingFirstPage = true }
        let query = query
        do {
            async let page = repository.page(query: query, after: nil)
            async let recentRows = repository.recent()
            async let count = repository.recordCount()
            let (rows, recentList, total) = try await (page, recentRows, count)
            guard generation == loadGeneration else { return }
            records = rows
            recent = recentList
            totalCount = total
            hasMore = rows.count >= RecordsRepository.pageSize
            loadError = nil
        } catch {
            guard generation == loadGeneration else { return }
            loadError = String(localized: "Couldn't load records.")
        }
        isLoadingFirstPage = false
        hasLoadedOnce = true
    }

    func loadNextPageIfNeeded(currentID: String) {
        guard hasMore, !isLoadingMore, let repository,
              let index = records.firstIndex(where: { $0.id == currentID }),
              index >= records.count - 12,
              let last = records.last
        else { return }
        isLoadingMore = true
        let generation = loadGeneration
        let query = query
        Task {
            defer { isLoadingMore = false }
            guard let rows = try? await repository.page(query: query, after: RecordCursor(last)),
                  generation == loadGeneration
            else { return }
            let known = Set(records.map(\.id))
            records.append(contentsOf: rows.filter { !known.contains($0.id) })
            hasMore = rows.count >= RecordsRepository.pageSize
        }
    }

    private func didChange() async {
        revision += 1
        await reload()
    }

    func detail(id: String) async -> RecordDetail? {
        guard let repository = await openIfNeeded() else { return nil }
        return try? await repository.detail(id: id)
    }

    func originalURL(for record: HealthRecord) -> URL? {
        record.filePath.map(files.url(forRelativePath:))
    }

    func thumbnailURL(for record: HealthRecord) -> URL? {
        record.thumbnailPath.map(files.url(forRelativePath:))
    }

    func allTagNames() async -> [String] {
        guard let repository = await openIfNeeded() else { return [] }
        return (try? await repository.allTagNames()) ?? []
    }

    // MARK: - Editing

    func update(id: String, patch: RecordPatch) async {
        guard let repository = await openIfNeeded() else { return }
        try? await repository.update(id: id, patch: patch)
        await didChange()
    }

    func setTags(id: String, names: [String]) async {
        guard let repository = await openIfNeeded() else { return }
        try? await repository.setTags(id: id, names: names)
        await didChange()
    }

    func setFavorite(ids: [String], _ favorite: Bool) async {
        guard let repository = await openIfNeeded() else { return }
        try? await repository.setFavorite(ids: ids, favorite)
        await didChange()
    }

    func setArchived(ids: [String], _ archived: Bool) async {
        guard let repository = await openIfNeeded() else { return }
        try? await repository.setArchived(ids: ids, archived)
        await didChange()
    }

    func delete(ids: [String]) async {
        guard let repository = await openIfNeeded() else { return }
        try? await Task.detached(priority: .userInitiated) {
            try await repository.delete(ids: ids)
        }.value
        duplicatePrompts.removeAll { ids.contains($0.newRecord.id) || ids.contains($0.existing.id) }
        await didChange()
    }

    // MARK: - Importing

    /// Imports sequentially off the main thread. Each record appears as soon as its original is
    /// saved; thumbnails and dates fill in afterwards.
    func importItems(_ items: [RecordImportItem], announce: Bool = true) async {
        guard !items.isEmpty else { return }
        guard let repository = await openIfNeeded() else {
            importErrorMessage = String(localized: "Couldn't open Health Records storage.")
            return
        }
        pendingImports += items.count
        var saved: [RecordImportResult] = []
        var firstError: RecordImportError?
        for item in items {
            do {
                let result = try await Task.detached(priority: .utility) {
                    try await repository.importItem(item)
                }.value
                saved.append(result)
                await didChange()
            } catch let error as RecordImportError {
                firstError = firstError ?? error
            } catch {
                firstError = firstError ?? .storage(String(describing: error))
            }
            pendingImports -= 1
        }
        await finishImports(saved, repository: repository, announce: announce)
        if let firstError {
            importErrorMessage = firstError.message
        }
    }

    private func finishImports(_ results: [RecordImportResult], repository: RecordsRepository, announce: Bool) async {
        guard !results.isEmpty else { return }
        for result in results {
            if let existing = result.duplicates.first {
                duplicatePrompts.append(RecordDuplicatePrompt(newRecord: result.record, existing: existing))
            }
        }
        if announce {
            showBanner(results.count == 1
                ? String(localized: "Saved to Health Records")
                : String(localized: "\(results.count) records saved to Health Records"))
        }
        let records = results.map(\.record)
        let generation = dataGeneration
        let previous = processingTask
        processingTask = Task { [weak self] in
            await previous?.value
            for record in records {
                guard self?.dataGeneration == generation else { return }
                _ = await Task.detached(priority: .utility) {
                    await repository.processBasics(record)
                }.value
                guard self?.dataGeneration == generation else { return }
                await self?.didChange()
            }
        }
    }

    func showBanner(_ message: String, systemImage: String = "checkmark.circle.fill") {
        let banner = RecordsBanner(message: message, systemImage: systemImage)
        self.banner = banner
        Task { [weak self] in
            try? await Task.sleep(nanoseconds: 3_000_000_000)
            if self?.banner?.id == banner.id { self?.banner = nil }
        }
    }

    // MARK: - Duplicates

    func resolveDuplicateKeepBoth(_ prompt: RecordDuplicatePrompt) {
        duplicatePrompts.removeAll { $0.id == prompt.id }
    }

    /// Cancel: the new import is deleted.
    func resolveDuplicateCancel(_ prompt: RecordDuplicatePrompt) async {
        duplicatePrompts.removeAll { $0.id == prompt.id }
        await delete(ids: [prompt.newRecord.id])
    }

    /// Open existing: the new copy is discarded and the existing record opens.
    func resolveDuplicateOpenExisting(_ prompt: RecordDuplicatePrompt) async {
        duplicatePrompts.removeAll { $0.id == prompt.id }
        await delete(ids: [prompt.newRecord.id])
        navigationRequest = prompt.existing.id
    }

    // MARK: - Receiving

    /// Imports everything the share extension left in the app-group inbox.
    func drainInbox() async {
        guard !isDrainingInbox, let root = inboxRoot(),
              FileManager.default.fileExists(atPath: root.path)
        else { return }
        isDrainingInbox = true
        defer { isDrainingInbox = false }
        guard let repository = await openIfNeeded() else { return }
        let importer = repository.importer
        let result = await Task.detached(priority: .userInitiated) {
            await RecordsInbox.drain(root: root, importer: importer)
        }.value
        if !result.imported.isEmpty {
            tabRequest += 1
            await didChange()
            await finishImports(result.imported, repository: repository, announce: true)
        }
        if let failure = result.failures.first {
            importErrorMessage = failure.message
        }
    }

    /// "Open in Ayuvo" from Files / Mail: the system hands over a file URL.
    func importOpenIn(url: URL) async {
        tabRequest += 1
        let item = RecordImportItem(
            payload: .file(url),
            source: .openIn,
            importMethod: .openIn,
            originalFilename: url.lastPathComponent
        )
        await importItems([item])
        // Copies the system placed in Documents/Inbox are ours to clean up.
        if url.path.contains("/Documents/Inbox/") {
            try? FileManager.default.removeItem(at: url)
        }
    }

    #if DEBUG
    func recordsCountForFixture() async -> Int {
        guard let repository = await openIfNeeded() else { return 0 }
        return (try? await repository.recordCount()) ?? 0
    }
    #endif

    // MARK: - Storage

    func storageBytes() async -> Int64 {
        guard let repository = await openIfNeeded() else { return 0 }
        return await Task.detached(priority: .utility) { repository.storageBytes() }.value
    }

    /// Delete All Data: database (+ sidecars), originals, render cache, share temp and inbox.
    func deleteAllData() async {
        loadGeneration += 1
        dataGeneration += 1
        if let openTask { await openTask.value }
        await processingTask?.value
        processingTask = nil
        if let repository {
            await repository.database.close()
        }
        repository = nil
        let databaseURL = databaseURL
        let files = files
        let inbox = inboxRoot()
        await Task.detached(priority: .userInitiated) {
            try? HealthDatabaseLocation.removeDatabaseFiles(at: databaseURL)
            files.deleteAll()
            try? FileManager.default.removeItem(at: RecordsLocation.renderCacheDirectory())
            try? FileManager.default.removeItem(at: RecordsLocation.shareTempDirectory())
            if let inbox { try? FileManager.default.removeItem(at: inbox) }
        }.value
        records = []
        recent = []
        totalCount = 0
        hasMore = false
        duplicatePrompts = []
        banner = nil
        navigationRequest = nil
        query = RecordQuery()
        viewMode = .defaultMode
        defaults.removeObject(forKey: RecordsViewMode.storageKey)
        revision += 1
    }
}
