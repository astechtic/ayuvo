import CryptoKit
import Foundation

// The portable `ayuvo-records` archive (docs/health-records.md §35, plan §3.14).
// Written entry by entry with the app's own `ZipArchiveWriter` (no new dependency): the text
// entries are deflated from a staged temp file, the originals are stored (they are already
// compressed), and nothing larger than one chunk is ever held in memory.

nonisolated enum RecordsArchiveFormat {
    static let format = "ayuvo-records"
    static let version = 1
    static let manifestEntry = "manifest.json"
    static let tagsEntry = "tags.json"
    static let aliasesEntry = "analyte_user_aliases.json"
    static let checksumsEntry = "checksums.json"
    static let filesPrefix = "files/"
    static let fileExtension = "ayuvorecords"

    /// `Ayuvo-records-2026-09-16.ayuvorecords.zip` — the share sheet shows a meaningful name.
    static func suggestedFileName(day: String) -> String {
        "Ayuvo-records-\(day).zip"
    }

    /// Entry names are never absolute and never escape the archive root.
    static func isSafeEntryName(_ name: String) -> Bool {
        guard !name.isEmpty, !name.hasPrefix("/"), !name.contains("\\"), !name.contains("..") else { return false }
        return !name.hasSuffix("/")
    }
}

nonisolated struct RecordsArchiveManifest: Codable, Sendable, Hashable {
    var format: String
    var formatVersion: Int
    var schemaVersion: Int
    var app: String
    var appVersion: String
    var platform: String
    var createdMs: Int64
    var timeZone: String
    var recordCount: Int
    var fileCount: Int
    var totalFileBytes: Int64

    enum CodingKeys: String, CodingKey {
        case format
        case formatVersion = "format_version"
        case schemaVersion = "schema_version"
        case app
        case appVersion = "app_version"
        case platform
        case createdMs = "created_ms"
        case timeZone = "time_zone"
        case recordCount = "record_count"
        case fileCount = "file_count"
        case totalFileBytes = "total_file_bytes"
    }
}

nonisolated enum RecordsArchiveError: LocalizedError, Sendable, Equatable {
    case notAnArchive
    case needsNewerApp
    case unreadable(String)
    case storageUnavailable

    var errorDescription: String? {
        switch self {
        case .notAnArchive: String(localized: "This is not an Ayuvo Health Records archive.")
        case .needsNewerApp: String(localized: "This archive needs a newer version of Ayuvo.")
        case .unreadable(let name): String(localized: "The archive entry “\(name)” is damaged.")
        case .storageUnavailable: String(localized: "Couldn't open Health Records storage.")
        }
    }
}

nonisolated struct RecordsArchiveProgress: Sendable, Hashable {
    enum Stage: String, Sendable {
        case metadata
        case files
        case rows
        case indexing
        case done
    }

    var stage: Stage
    var done: Int
    var total: Int

    var fraction: Double { total <= 0 ? 0 : min(1, Double(done) / Double(total)) }
}

nonisolated struct RecordsArchiveResult: Sendable, Hashable {
    var url: URL
    var manifest: RecordsArchiveManifest
    var byteCount: Int64
}

nonisolated enum RecordsImportMode: String, CaseIterable, Sendable, Identifiable {
    case merge
    case replace

    var id: String { rawValue }

    var title: String {
        switch self {
        case .merge: String(localized: "Merge")
        case .replace: String(localized: "Replace")
        }
    }

    var subtitle: String {
        switch self {
        case .merge: String(localized: "Adds records that aren't on this iPhone yet. Nothing is removed.")
        case .replace: String(localized: "Deletes every record on this iPhone first, then imports the archive.")
        }
    }
}

nonisolated struct RecordsImportSummary: Sendable, Hashable {
    var mode: RecordsImportMode
    var importedRecords = 0
    var skippedRecords = 0
    var importedFiles = 0
    var missingFiles = 0
    var checksumWarnings: [String] = []
    var manifest: RecordsArchiveManifest?
}

/// Streams an `ayuvo-records` archive from the live store.
nonisolated struct RecordsArchiveExporter: Sendable {
    let database: RecordsDatabase
    let files: RecordFileStore

    static func sha256(ofFile url: URL) -> String? {
        guard let handle = try? FileHandle(forReadingFrom: url) else { return nil }
        defer { try? handle.close() }
        var hasher = SHA256()
        while let chunk = try? handle.read(upToCount: ZipArchiveReader.chunkSize), !chunk.isEmpty {
            hasher.update(data: chunk)
        }
        return RecordFileStore.hex(hasher.finalize())
    }

    static func sha256(ofData data: Data) -> String {
        RecordFileStore.hex(SHA256.hash(data: data))
    }

    func export(
        to url: URL,
        includeFiles: Bool,
        appVersion: String,
        nowMs: Int64 = RecordDates.nowMs(),
        progress: @Sendable (RecordsArchiveProgress) -> Void = { _ in }
    ) async throws -> RecordsArchiveResult {
        let staging = url.deletingLastPathComponent().appendingPathComponent(".staging-\(UUID().uuidString)", isDirectory: true)
        defer { try? FileManager.default.removeItem(at: staging) }
        progress(RecordsArchiveProgress(stage: .metadata, done: 0, total: 1))
        let plan = try await database.stageArchiveEntries(into: staging)
        let fileRefs = includeFiles ? plan.files : []
        var totalFileBytes: Int64 = 0
        for ref in fileRefs { totalFileBytes += files.size(ofRelativePath: ref.relativePath) }

        let manifest = RecordsArchiveManifest(
            format: RecordsArchiveFormat.format,
            formatVersion: RecordsArchiveFormat.version,
            schemaVersion: RecordsSchema.schemaVersion,
            app: "Ayuvo",
            appVersion: appVersion,
            platform: "ios",
            createdMs: nowMs,
            timeZone: TimeZone.current.identifier,
            recordCount: plan.recordCount,
            fileCount: fileRefs.count,
            totalFileBytes: totalFileBytes
        )
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys]
        let manifestData = try encoder.encode(manifest)

        try? FileManager.default.removeItem(at: url)
        let writer = try ZipArchiveWriter(url: url)
        var checksums: [String: String] = [:]

        try writer.addStored(name: RecordsArchiveFormat.manifestEntry, data: manifestData)
        checksums[RecordsArchiveFormat.manifestEntry] = Self.sha256(ofData: manifestData)

        for staged in plan.staged {
            let deflated = staging.appendingPathComponent(staged.entry + ".z")
            let result = try DeflateFile.compress(source: staged.url, destination: deflated)
            try writer.addDeflated(name: staged.entry, deflatedURL: deflated, uncompressedSize: result.uncompressedSize, crc: result.crc)
            checksums[staged.entry] = Self.sha256(ofFile: staged.url) ?? ""
            try? FileManager.default.removeItem(at: deflated)
            try? FileManager.default.removeItem(at: staged.url)
        }
        try writer.addStored(name: RecordsArchiveFormat.tagsEntry, data: plan.tagsJSON)
        checksums[RecordsArchiveFormat.tagsEntry] = Self.sha256(ofData: plan.tagsJSON)
        try writer.addStored(name: RecordsArchiveFormat.aliasesEntry, data: plan.aliasesJSON)
        checksums[RecordsArchiveFormat.aliasesEntry] = Self.sha256(ofData: plan.aliasesJSON)

        var done = 0
        for ref in fileRefs {
            let source = files.url(forRelativePath: ref.relativePath)
            done += 1
            progress(RecordsArchiveProgress(stage: .files, done: done, total: fileRefs.count))
            guard FileManager.default.fileExists(atPath: source.path) else { continue }
            try writer.addStored(name: ref.entry, fileURL: source)
            checksums[ref.entry] = Self.sha256(ofFile: source) ?? ""
        }

        let checksumData = (try? JSONSerialization.data(withJSONObject: checksums, options: [.sortedKeys])) ?? Data("{}".utf8)
        try writer.addStored(name: RecordsArchiveFormat.checksumsEntry, data: checksumData)
        try writer.finish()

        let attributes = try? FileManager.default.attributesOfItem(atPath: url.path)
        let byteCount = (attributes?[.size] as? NSNumber)?.int64Value ?? 0
        try? await database.setBackupState([
            RecordsBackupStateKey.lastArchiveMs: "\(nowMs)",
            RecordsBackupStateKey.lastArchiveSize: "\(byteCount)",
            RecordsBackupStateKey.lastArchiveRecords: "\(plan.recordCount)",
        ])
        progress(RecordsArchiveProgress(stage: .done, done: 1, total: 1))
        return RecordsArchiveResult(url: url, manifest: manifest, byteCount: byteCount)
    }
}

/// Reads an `ayuvo-records` archive back into the store (§35 Merge / Replace).
nonisolated struct RecordsArchiveImporter: Sendable {
    let database: RecordsDatabase
    let files: RecordFileStore

    static let batchSize = 400
    static let maxLineBytes = RecordsDatabase.archiveLineCapBytes * 2

    static func manifest(in reader: ZipArchiveReader) throws -> RecordsArchiveManifest {
        guard let entry = reader.entry(named: RecordsArchiveFormat.manifestEntry),
              let data = try? reader.data(for: entry),
              let manifest = try? JSONDecoder().decode(RecordsArchiveManifest.self, from: data)
        else { throw RecordsArchiveError.notAnArchive }
        guard manifest.format == RecordsArchiveFormat.format else { throw RecordsArchiveError.notAnArchive }
        guard manifest.formatVersion <= RecordsArchiveFormat.version else { throw RecordsArchiveError.needsNewerApp }
        return manifest
    }

    static func inspect(url: URL) throws -> RecordsArchiveManifest {
        guard let reader = try? ZipArchiveReader(url: url) else { throw RecordsArchiveError.notAnArchive }
        return try manifest(in: reader)
    }

    func run(
        url: URL,
        mode: RecordsImportMode,
        nowMs: Int64 = RecordDates.nowMs(),
        progress: @Sendable (RecordsArchiveProgress) -> Void = { _ in }
    ) async throws -> RecordsImportSummary {
        guard let reader = try? ZipArchiveReader(url: url) else { throw RecordsArchiveError.notAnArchive }
        let manifest = try Self.manifest(in: reader)
        var summary = RecordsImportSummary(mode: mode)
        summary.manifest = manifest

        var checksums: [String: String] = [:]
        if let entry = reader.entry(named: RecordsArchiveFormat.checksumsEntry),
           let data = try? reader.data(for: entry),
           let map = try? JSONSerialization.jsonObject(with: data) as? [String: String] {
            checksums = map
        }

        if mode == .replace {
            try await database.wipeForArchiveReplace()
            files.deleteAll()
        }
        let existing = try await database.existingRecordKeys()

        // 1. records
        var importedSet = Set<String>()
        var filePaths: [String: [String]] = [:]
        if let table = RecordsDatabase.archiveTable(entry: "records.ndjson"), let entry = reader.entry(named: table.entry) {
            var pending: [[RJ]] = []
            var batch: [RJ] = []
            var skipped = 0
            try Self.forEachRow(reader: reader, entry: entry) { row in
                guard let id = row["id"].string else { return }
                let checksum = row["checksum_sha256"].string ?? ""
                if existing.ids.contains(id) || (!checksum.isEmpty && existing.checksums.contains(checksum)) {
                    skipped += 1
                    return
                }
                guard importedSet.insert(id).inserted else { return }
                filePaths[id] = [row["file_path"].string, row["thumbnail_path"].string].compactMap { $0 }.filter { !$0.isEmpty }
                batch.append(row)
                if batch.count >= Self.batchSize {
                    pending.append(batch)
                    batch.removeAll(keepingCapacity: true)
                }
            }
            if !batch.isEmpty { pending.append(batch) }
            summary.skippedRecords = skipped
            for chunk in pending {
                summary.importedRecords += try await database.insertArchiveRows(table: table, rows: chunk)
                progress(RecordsArchiveProgress(stage: .rows, done: summary.importedRecords, total: max(manifest.recordCount, 1)))
            }
        }

        // 2. child tables that follow their record
        var entityRows: [RJ] = []
        var recordEntityRows: [RJ] = []
        for table in RecordsDatabase.archiveTables where table.entry != "records.ndjson" {
            guard let entry = reader.entry(named: table.entry) else { continue }
            if table.table == "entities" {
                try Self.forEachRow(reader: reader, entry: entry) { entityRows.append($0) }
                continue
            }
            if table.table == "record_entities" {
                try Self.forEachRow(reader: reader, entry: entry) { row in
                    if importedSet.contains(row["record_id"].string ?? "") { recordEntityRows.append(row) }
                }
                continue
            }
            var pending: [[RJ]] = []
            var batch: [RJ] = []
            try Self.forEachRow(reader: reader, entry: entry) { row in
                guard Self.belongs(row, to: importedSet) else { return }
                batch.append(row)
                if batch.count >= Self.batchSize {
                    pending.append(batch)
                    batch.removeAll(keepingCapacity: true)
                }
            }
            if !batch.isEmpty { pending.append(batch) }
            for chunk in pending { _ = try await database.insertArchiveRows(table: table, rows: chunk) }
        }

        // 3. entities are upserted by (kind, normalized_name); record_entities follow the live ids.
        let usedEntities = Set(recordEntityRows.compactMap { $0["entity_id"].string })
        let entityMap = try await database.importArchiveEntities(entityRows.filter { usedEntities.contains($0["id"].string ?? "") })
        try await database.importArchiveRecordEntities(recordEntityRows, entityMap: entityMap)

        // 4. tags.json — `[{id, name, record_ids}]`
        if let entry = reader.entry(named: RecordsArchiveFormat.tagsEntry), let data = try? reader.data(for: entry),
           let rows = RJ.parse(String(decoding: data, as: UTF8.self))?.array {
            try await database.importArchiveTags(rows, keptRecordIDs: importedSet)
        }

        // 5. analyte_user_aliases.json — `[{normalized_name, analyte_id, created_ms}]`
        if let entry = reader.entry(named: RecordsArchiveFormat.aliasesEntry), let data = try? reader.data(for: entry),
           let rows = RJ.parse(String(decoding: data, as: UTF8.self))?.array {
            try await database.importArchiveAliases(rows, nowMs: nowMs)
        }

        // 6. files
        var missingFileIDs: [String] = []
        var doneFiles = 0
        let fileEntries = reader.entries.filter { $0.name.hasPrefix(RecordsArchiveFormat.filesPrefix) }
        for (id, paths) in filePaths.sorted(by: { $0.key < $1.key }) {
            var wroteOriginal = paths.isEmpty
            for path in paths {
                let name = (path as NSString).lastPathComponent
                let entryName = "\(RecordsArchiveFormat.filesPrefix)\(id)/\(name)"
                guard RecordsArchiveFormat.isSafeEntryName(entryName),
                      let entry = fileEntries.first(where: { $0.name == entryName }) else { continue }
                let destination = files.url(forRelativePath: "\(id)/\(name)")
                do {
                    try FileManager.default.createDirectory(at: destination.deletingLastPathComponent(), withIntermediateDirectories: true)
                    try? FileManager.default.removeItem(at: destination)
                    FileManager.default.createFile(atPath: destination.path, contents: nil)
                    let handle = try FileHandle(forWritingTo: destination)
                    defer { try? handle.close() }
                    var hasher = SHA256()
                    try reader.forEachChunk(of: entry) { chunk in
                        hasher.update(data: chunk)
                        try handle.write(contentsOf: chunk)
                    }
                    if let expected = checksums[entryName], !expected.isEmpty, expected != RecordFileStore.hex(hasher.finalize()) {
                        summary.checksumWarnings.append(entryName)
                    }
                    summary.importedFiles += 1
                    if name.hasPrefix("original.") { wroteOriginal = true }
                } catch {
                    summary.checksumWarnings.append(entryName)
                }
                doneFiles += 1
                progress(RecordsArchiveProgress(stage: .files, done: doneFiles, total: max(manifest.fileCount, 1)))
            }
            if !wroteOriginal {
                missingFileIDs.append(id)
                summary.missingFiles += 1
            }
        }

        // 7. statuses + FTS rebuild
        progress(RecordsArchiveProgress(stage: .indexing, done: 0, total: 1))
        try await database.finishArchiveImport(missingFileIDs: missingFileIDs, importedIDs: importedSet.sorted(), nowMs: nowMs)
        try? await database.setBackupState([RecordsBackupStateKey.lastRestoreMs: "\(nowMs)"])
        progress(RecordsArchiveProgress(stage: .done, done: 1, total: 1))
        return summary
    }

    /// Rows whose owning record was imported (links need both ends).
    static func belongs(_ row: RJ, to imported: Set<String>) -> Bool {
        if let a = row["a_id"].string, let b = row["b_id"].string {
            return imported.contains(a) && imported.contains(b)
        }
        guard let recordID = row["record_id"].string else { return false }
        return imported.contains(recordID)
    }

    static func forEachRow(reader: ZipArchiveReader, entry: ZipEntry, _ body: (RJ) throws -> Void) throws {
        try reader.forEachLine(of: entry, maxLineBytes: maxLineBytes) { line in
            guard !line.isEmpty else { return }
            guard let any = try? JSONSerialization.jsonObject(with: line) else { return }
            let row = RJ.from(any)
            guard row.object != nil else { return }
            try body(row)
        }
    }
}
