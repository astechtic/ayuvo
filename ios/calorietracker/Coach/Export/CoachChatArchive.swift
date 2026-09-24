import Foundation

/// The `ayuvo-coach-chats` container (docs/coach.md §11). The twin of
/// `coach/export/CoachChatArchive.kt`; both write the same entries, in the same order, with the same
/// compact sorted-key JSON, so an archive made on either platform reads on the other.
nonisolated enum CoachChatArchiveFormat {
    static let format = CR.archiveFormat
    static let formatVersion = CR.archiveVersion
    static let app = "Ayuvo"
    static let platform = "ios"
    static let fileName = "ayuvo-coach-chats.zip"

    static let manifest = "manifest.json"
    static let conversations = "conversations.ndjson"
    static let messages = "messages.ndjson"
    static let attachments = "attachments.ndjson"
    static let filesPrefix = "attachments/"
    static let checksums = "checksums.json"

    /// `attachments/<id>/<filename>`, with anything that could escape the folder removed.
    static func fileEntry(attachmentID: String, filename: String) -> String {
        filesPrefix + attachmentID + "/" + safeName(filename)
    }

    static func safeName(_ filename: String) -> String {
        let base = filename.split(separator: "/").last.map(String.init) ?? filename
        let tail = base.split(separator: "\\").last.map(String.init) ?? base
        let cleaned = tail.trimmingCharacters(in: .whitespacesAndNewlines)
            .filter { character in
                guard let scalar = character.unicodeScalars.first, scalar.value >= 0x20 else { return false }
                return !":\"*?".contains(character)
            }
        return cleaned.isEmpty ? "attachment" : String(cleaned.prefix(120))
    }

    /// Compact JSON with sorted keys — `RJ.jsonText`, which is what Kotlin's `compact` matches.
    static func compact(_ value: RJ) -> String { value.jsonText }
}

nonisolated struct CoachChatExportResult: Sendable {
    var url: URL
    var conversations: Int
    var messages: Int
    var attachments: Int
    var files: Int
    var bytes: Int

    var isEmpty: Bool { conversations == 0 && messages == 0 }
}

nonisolated struct CoachChatImportResult: Sendable {
    var error: String?
    var counts: [String: Int] = [:]
    var filesRestored = 0
    var filesMissing = 0

    var ok: Bool { error == nil }
    var imported: Int {
        (counts["conversations_inserted"] ?? 0) + (counts["conversations_updated"] ?? 0)
            + (counts["messages_inserted"] ?? 0) + (counts["messages_updated"] ?? 0)
    }
}

/// Writes the chats into one zip (docs/coach.md §11). Tombstones and attachments no exported message
/// references are dropped by `CR.chatArchive`, not here.
struct CoachChatArchiveWriter {
    let repository: CoachRepository
    let files: CoachFileStore
    var appVersion: String
    var nowMs: () -> Int64 = { Int64(Date().timeIntervalSince1970 * 1000) }
    var timeZone: () -> String = { TimeZone.current.identifier }

    @discardableResult
    func export(to target: URL) async throws -> CoachChatExportResult {
        let archive = CR.chatArchive(try await repository.snapshotValue())
        let conversations = archive["conversations"].array ?? []
        let messages = archive["messages"].array ?? []
        let attachments = archive["attachments"].array ?? []

        // Only the blobs of attachments that survived the export are carried.
        let ids = attachments.compactMap { $0["id"].string }
        let rows = try await repository.attachments(ids: ids)
        var blobs: [(String, URL)] = []
        for row in rows {
            guard let path = row.filePath, let url = files.resolve(path),
                  FileManager.default.fileExists(atPath: url.path)
            else { continue }
            blobs.append((CoachChatArchiveFormat.fileEntry(attachmentID: row.id, filename: row.filename), url))
        }

        try Self.write(
            to: target,
            archive: archive,
            manifest: manifest(conversations.count, messages.count, attachments.count),
            blobs: blobs
        )
        let size = (try? FileManager.default.attributesOfItem(atPath: target.path))
            .flatMap { ($0[.size] as? NSNumber)?.intValue }
        return CoachChatExportResult(
            url: target,
            conversations: conversations.count,
            messages: messages.count,
            attachments: attachments.count,
            files: blobs.count,
            bytes: size ?? 0
        )
    }

    private func manifest(_ conversations: Int, _ messages: Int, _ attachments: Int) -> RJ {
        .obj([
            "format": .str(CoachChatArchiveFormat.format),
            "format_version": .int(CoachChatArchiveFormat.formatVersion),
            "app": .str(CoachChatArchiveFormat.app),
            "app_version": .str(appVersion),
            "platform": .str(CoachChatArchiveFormat.platform),
            "exported_at": .int(Int(nowMs())),
            "zone_id": .str(timeZone()),
            "counts": .obj([
                "conversations": .int(conversations),
                "messages": .int(messages),
                "attachments": .int(attachments),
            ]),
        ])
    }

    /// The container itself: manifest, the three ndjson entries, the blobs, then `checksums.json`
    /// last because it covers every other entry. Nothing here reads the database.
    static func write(to target: URL, archive: RJ, manifest: RJ, blobs: [(String, URL)]) throws {
        try? FileManager.default.removeItem(at: target)
        let writer = try ZipArchiveWriter(url: target)
        var checksums: [String: RJ] = [:]

        func add(_ name: String, _ data: Data) throws {
            try writer.addStored(name: name, data: data)
            checksums[name] = .str(CoachFileStore.sha256(data))
        }

        try add(CoachChatArchiveFormat.manifest, Data((CoachChatArchiveFormat.compact(manifest) + "\n").utf8))
        try add(CoachChatArchiveFormat.conversations, ndjson(archive["conversations"].array ?? []))
        try add(CoachChatArchiveFormat.messages, ndjson(archive["messages"].array ?? []))
        try add(CoachChatArchiveFormat.attachments, ndjson(archive["attachments"].array ?? []))
        for (name, url) in blobs {
            let data = try Data(contentsOf: url)
            try add(name, data)
        }
        let sums = RJ.obj(checksums)
        try writer.addStored(name: CoachChatArchiveFormat.checksums,
                             data: Data((CoachChatArchiveFormat.compact(sums) + "\n").utf8))
        try writer.finish()
    }

    private static func ndjson(_ rows: [RJ]) -> Data {
        var text = ""
        for row in rows {
            text += CoachChatArchiveFormat.compact(row)
            text += "\n"
        }
        return Data(text.utf8)
    }
}

/// Reads an `ayuvo-coach-chats` archive and **merges** it (docs/coach.md §11): nothing local is ever
/// deleted, a local tombstone always wins, and a blob the archive did not carry leaves the row
/// pointing at nothing rather than failing the import.
struct CoachChatArchiveReader {
    let repository: CoachRepository
    let files: CoachFileStore

    struct Entries: Sendable {
        var archive: RJ
        /// attachment id -> (filename, bytes)
        var blobs: [String: (String, Data)]
    }

    func `import`(from source: URL) async throws -> CoachChatImportResult {
        let read = try Self.readEntries(at: source)
        let merged = CR.mergeChatArchive(try await repository.snapshotValue(), read.archive)
        if let error = merged["error"].string { return CoachChatImportResult(error: error) }
        try await repository.applySnapshot(merged["snapshot"])

        var restored = 0
        for (id, payload) in read.blobs {
            let ext = (payload.0 as NSString).pathExtension
            guard let path = try? files.writeOriginal(payload.1, attachmentID: id,
                                                      fileExtension: ext.isEmpty ? "bin" : ext)
            else { continue }
            try? await repository.setAttachmentFile(id: id, path: path)
            restored += 1
        }
        var counts: [String: Int] = [:]
        if case .obj(let dict) = merged["counts"] {
            for (key, value) in dict { counts[key] = Int(value.double ?? 0) }
        }
        let expected = (read.archive["attachments"].array ?? []).count
        return CoachChatImportResult(counts: counts, filesRestored: restored,
                                     filesMissing: max(0, expected - restored))
    }

    static func readEntries(at source: URL) throws -> Entries {
        let reader = try ZipArchiveReader(url: source)
        func text(_ name: String) -> String {
            guard let entry = reader.entry(named: name), let data = try? reader.data(for: entry) else { return "" }
            return String(data: data, encoding: .utf8) ?? ""
        }
        func rows(_ name: String) -> [RJ] {
            text(name).split(separator: "\n", omittingEmptySubsequences: true).compactMap {
                RJ.parse(String($0))
            }
        }
        let manifest = RJ.parse(text(CoachChatArchiveFormat.manifest)) ?? .null
        var blobs: [String: (String, Data)] = [:]
        for entry in reader.entries where entry.name.hasPrefix(CoachChatArchiveFormat.filesPrefix) {
            let rest = entry.name.dropFirst(CoachChatArchiveFormat.filesPrefix.count)
            let parts = rest.split(separator: "/", omittingEmptySubsequences: true)
            guard parts.count == 2, let data = try? reader.data(for: entry) else { continue }
            blobs[String(parts[0])] = (String(parts[1]), data)
        }
        let archive = RJ.obj([
            "format": .str(manifest["format"].string ?? ""),
            "format_version": .int(Int(manifest["format_version"].double ?? 0)),
            "conversations": .arr(rows(CoachChatArchiveFormat.conversations)),
            "messages": .arr(rows(CoachChatArchiveFormat.messages)),
            "attachments": .arr(rows(CoachChatArchiveFormat.attachments)),
        ])
        return Entries(archive: archive, blobs: blobs)
    }
}
