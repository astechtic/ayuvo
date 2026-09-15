import Foundation

/// Contract §7: `<app group>/RecordsInbox/<uuid>/item.json` plus at most one `payload.<ext>`.
/// The share extension writes `.tmp-<uuid>/` and renames it when complete; the app ignores
/// `.tmp-*` folders and removes an item folder only after its import finished.
nonisolated struct RecordsInboxItem: Codable, Equatable, Sendable {
    static let format = "ayuvo-records-inbox"
    static let version = 1

    var format: String = RecordsInboxItem.format
    var version: Int = RecordsInboxItem.version
    var originalFilename: String?
    var uti: String?
    var receivedAtMs: Int64
    /// Set only for text items without a payload.
    var text: String?

    enum CodingKeys: String, CodingKey {
        case format
        case version
        case originalFilename = "original_filename"
        case uti
        case receivedAtMs = "received_at_ms"
        case text
    }
}

nonisolated struct RecordsInboxDrainResult: Sendable, Equatable {
    var imported: [RecordImportResult] = []
    var failures: [RecordImportError] = []
}

nonisolated enum RecordsInbox {
    static let itemFileName = "item.json"
    static let payloadBaseName = "payload"
    static let tempPrefix = ".tmp-"

    struct Entry: Sendable {
        var directory: URL
        var item: RecordsInboxItem
        var payload: URL?
    }

    /// Complete, readable items, oldest first. Folders still being written (`.tmp-*`), without a
    /// readable `item.json`, or in a newer format are left untouched.
    static func pendingEntries(root: URL, fileManager: FileManager = .default) -> [Entry] {
        guard let children = try? fileManager.contentsOfDirectory(at: root, includingPropertiesForKeys: [.isDirectoryKey]) else { return [] }
        var entries: [Entry] = []
        for directory in children {
            let name = directory.lastPathComponent
            guard !name.hasPrefix(tempPrefix), !name.hasPrefix("."),
                  (try? directory.resourceValues(forKeys: [.isDirectoryKey]).isDirectory) == true,
                  let data = try? Data(contentsOf: directory.appendingPathComponent(itemFileName)),
                  let item = try? JSONDecoder().decode(RecordsInboxItem.self, from: data),
                  item.format == RecordsInboxItem.format,
                  item.version <= RecordsInboxItem.version
            else { continue }
            let payload = (try? fileManager.contentsOfDirectory(at: directory, includingPropertiesForKeys: nil))?
                .first { ($0.lastPathComponent as NSString).deletingPathExtension == payloadBaseName }
            entries.append(Entry(directory: directory, item: item, payload: payload))
        }
        return entries.sorted {
            ($0.item.receivedAtMs, $0.directory.lastPathComponent) < ($1.item.receivedAtMs, $1.directory.lastPathComponent)
        }
    }

    static func importItem(for entry: Entry) -> RecordImportItem? {
        if let payload = entry.payload {
            return RecordImportItem(
                payload: .file(payload),
                source: .shareIn,
                importMethod: .shareSheet,
                // The payload name is generic; the sender's filename is the title / date hint.
                originalFilename: entry.item.originalFilename
            )
        }
        if let text = entry.item.text, !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            return RecordImportItem(payload: .text(text), source: .shareIn, importMethod: .shareSheet)
        }
        return nil
    }

    /// Imports every ready item. A folder is removed once its import finished: success, or a
    /// failure that can't improve on retry (too large, unreadable, empty). Storage failures stay.
    static func drain(root: URL, importer: RecordImporter, fileManager: FileManager = .default) async -> RecordsInboxDrainResult {
        var result = RecordsInboxDrainResult()
        for entry in pendingEntries(root: root, fileManager: fileManager) {
            guard let item = importItem(for: entry) else {
                result.failures.append(.empty)
                try? fileManager.removeItem(at: entry.directory)
                continue
            }
            do {
                let imported = try await importer.importItem(item)
                result.imported.append(imported)
                try? fileManager.removeItem(at: entry.directory)
            } catch let error as RecordImportError {
                result.failures.append(error)
                if !error.isRetryable {
                    try? fileManager.removeItem(at: entry.directory)
                }
            } catch {
                result.failures.append(.storage(String(describing: error)))
            }
        }
        return result
    }
}
