import Foundation

/// Database + originals together: everything that must touch both (import, delete) goes
/// through here so rows and files never drift apart. Sendable; call from detached tasks.
nonisolated struct RecordsRepository: Sendable {
    static let pageSize = 60

    let database: RecordsDatabase
    let files: RecordFileStore

    var importer: RecordImporter { RecordImporter(database: database, files: files) }

    // MARK: Reads

    func page(query: RecordQuery, after cursor: RecordCursor?, limit: Int = RecordsRepository.pageSize) async throws -> [HealthRecord] {
        try await database.page(query: query, after: cursor, limit: limit)
    }

    func recent(limit: Int = 10) async throws -> [HealthRecord] {
        try await database.recent(limit: limit)
    }

    func detail(id: String) async throws -> RecordDetail? {
        try await database.detail(id: id)
    }

    func recordCount() async throws -> Int {
        try await database.recordCount()
    }

    func allTagNames() async throws -> [String] {
        try await database.allTagNames()
    }

    func originalURL(for record: HealthRecord) -> URL? {
        record.filePath.map(files.url(forRelativePath:))
    }

    func thumbnailURL(for record: HealthRecord) -> URL? {
        record.thumbnailPath.map(files.url(forRelativePath:))
    }

    // MARK: Writes

    func update(id: String, patch: RecordPatch) async throws {
        try await database.update(id: id, patch: patch)
    }

    func setTags(id: String, names: [String]) async throws {
        try await database.setTags(recordID: id, names: names)
    }

    func setFavorite(ids: [String], _ favorite: Bool) async throws {
        try await database.setFlags(ids: ids, favorite: favorite)
    }

    func setArchived(ids: [String], _ archived: Bool) async throws {
        try await database.setFlags(ids: ids, archived: archived)
    }

    /// Plain delete (no recovery): rows first, then each record's directory.
    func delete(ids: [String]) async throws {
        let removed = try await database.delete(ids: ids)
        for id in Set(removed).union(ids) {
            files.delete(id: id)
        }
    }

    func importItem(_ item: RecordImportItem) async throws -> RecordImportResult {
        try await importer.importItem(item)
    }

    func processBasics(_ record: HealthRecord) async -> HealthRecord? {
        await importer.processBasics(record)
    }

    func storageBytes() -> Int64 {
        let databaseBytes = database.url.map { HealthDatabaseLocation.totalSizeBytes(for: $0) } ?? 0
        return databaseBytes + files.totalSizeBytes()
    }
}
