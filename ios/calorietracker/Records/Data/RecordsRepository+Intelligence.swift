import Foundation

// Phase 2 repository operations that touch rows and files together.
extension RecordsRepository {
    func processingSummary() async throws -> RecordsProcessingSummary {
        try await database.processingSummary()
    }

    func needsReview(limit: Int = 10) async throws -> [HealthRecord] {
        try await database.needsReview(limit: limit)
    }

    func importantHighlights(limit: Int = 8) async throws -> [(RecordHighlight, HealthRecord)] {
        try await database.importantHighlights(limit: limit)
    }

    func search(query: RecordQuery, terms: [String], today: String) async throws -> [RecordSearchHit] {
        try await database.search(query: query, terms: terms, today: today)
    }

    func record(id: String) async throws -> HealthRecord? {
        try await database.record(id: id)
    }

    /// Deleting a split parent also deletes its children (they share its file).
    func expandedForDelete(_ ids: [String]) async throws -> [String] {
        var all = ids
        for id in ids {
            all.append(contentsOf: try await database.children(parentID: id).map(\.id))
        }
        var seen = Set<String>()
        return all.filter { seen.insert($0).inserted }
    }

    /// Duplicate "Replace" (plan §3.11): the existing record takes the new file (original and
    /// thumbnail move into its folder), keeps its notes / tags / confirmed fields, and is
    /// reprocessed; the new record is removed.
    func replaceDuplicate(newID: String, existingID: String) async throws {
        guard let new = try await database.record(id: newID),
              let existing = try await database.record(id: existingID),
              let newPath = new.filePath,
              new.parentID == nil, existing.parentID == nil
        else { return }
        let fileManager = FileManager.default
        let ext = (newPath as NSString).pathExtension
        let existingDirectory = files.directory(for: existingID)
        try fileManager.createDirectory(at: existingDirectory, withIntermediateDirectories: true)
        let staged = existingDirectory.appendingPathComponent("original.replacing.\(ext)")
        try? fileManager.removeItem(at: staged)
        try fileManager.copyItem(at: files.url(forRelativePath: newPath), to: staged)
        if let oldPath = existing.filePath, oldPath.hasPrefix(existingID + "/") {
            try? fileManager.removeItem(at: files.url(forRelativePath: oldPath))
        }
        let destinationPath = RecordFileStore.originalRelativePath(id: existingID, ext: ext)
        let destination = files.url(forRelativePath: destinationPath)
        try? fileManager.removeItem(at: destination)
        try fileManager.moveItem(at: staged, to: destination)
        var thumbnailPath: String?
        if let newThumb = new.thumbnailPath {
            let target = RecordFileStore.thumbnailRelativePath(id: existingID)
            try? fileManager.removeItem(at: files.url(forRelativePath: target))
            if (try? fileManager.copyItem(at: files.url(forRelativePath: newThumb), to: files.url(forRelativePath: target))) != nil {
                thumbnailPath = target
            }
        }
        var replacement = new
        replacement.filePath = destinationPath
        replacement.thumbnailPath = thumbnailPath
        try await database.replaceOriginal(existingID: existingID, with: replacement)
        _ = try await database.delete(ids: [newID])
        files.delete(id: newID)
        try await database.enqueueProcessing(ids: [existingID], restart: true)
    }

    /// Duplicate "Merge": notes and tags move into the existing record; the new one is deleted.
    func mergeDuplicate(newID: String, existingID: String) async throws {
        try await database.mergeRecordMetadata(from: newID, into: existingID)
        _ = try await database.delete(ids: [newID])
        files.delete(id: newID)
    }

    func keepBothDuplicate(recordID: String, existingID: String) async throws {
        try await database.resolveDuplicate(recordID: recordID, existingID: existingID, resolution: .keepBoth)
        try await database.resolveDuplicate(recordID: existingID, existingID: recordID, resolution: .keepBoth)
    }
}
