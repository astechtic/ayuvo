import CryptoKit
import Foundation

nonisolated enum HealthImportMode: Sendable {
    case merge
    case replaceAll
}

nonisolated enum HealthImportError: LocalizedError, Sendable, Equatable {
    case fileTooLarge
    case notAnArchive
    case invalidManifest
    case unsupportedFormat
    case newerFormat
    case missingSamples
    case lineTooLong

    var errorDescription: String? {
        switch self {
        case .fileTooLarge: return "This file is too large to import."
        case .notAnArchive: return "This is not a Ayuvo health data archive."
        case .invalidManifest: return "The archive's manifest could not be read."
        case .unsupportedFormat: return "This archive is not Ayuvo health data."
        case .newerFormat: return "This health data archive needs a newer Ayuvo."
        case .missingSamples: return "The archive does not contain any health records."
        case .lineTooLong: return "The archive contains a record that is too large to import."
        }
    }
}

nonisolated struct HealthImportPreview: Sendable, Equatable {
    var url: URL
    var platform: String
    var appVersion: String
    var exportedAt: String
    var recordCount: Int
    var typeCount: Int
    var rangeStart: String?
    var rangeEnd: String?
    var unknownTypes: [String]
    var manifestTypes: [HealthExportFormat.TypeEntry]
}

nonisolated struct HealthImportResult: Sendable, Equatable {
    var inserted = 0
    var updated = 0
    var unchanged = 0
    var rejectedUnitMismatch = 0
    var rejectedInvalid = 0
    var touchedTypes: [String] = []
    var checksumMismatches: [String] = []
}

/// Reads `ayuvo-health-data` archives (either platform). Fails closed on unknown formats or
/// newer majors like `DiaryImporter` / `CloudBackupArchive`; the checksum check is a warning.
nonisolated enum HealthImporter {
    static let batchSize = 2000

    static func preview(url: URL) throws -> HealthImportPreview {
        let values = try url.resourceValues(forKeys: [.fileSizeKey])
        if let size = values.fileSize, size > HealthExportFormat.maxArchiveBytes {
            throw HealthImportError.fileTooLarge
        }
        let reader: ZipArchiveReader
        do {
            reader = try ZipArchiveReader(url: url)
        } catch {
            throw HealthImportError.notAnArchive
        }
        let manifest = try manifest(from: reader)
        guard reader.entry(named: HealthExportFormat.samplesEntry) != nil else { throw HealthImportError.missingSamples }
        let unknown = manifest.types.map(\.type).filter { HealthMetricRegistry.type(id: $0) == nil }
        return HealthImportPreview(
            url: url,
            platform: manifest.platform,
            appVersion: manifest.app_version,
            exportedAt: manifest.exported_at,
            recordCount: manifest.types.reduce(0) { $0 + $1.record_count },
            typeCount: manifest.types.count,
            rangeStart: manifest.date_range.start,
            rangeEnd: manifest.date_range.end,
            unknownTypes: unknown,
            manifestTypes: manifest.types
        )
    }

    static func manifest(from reader: ZipArchiveReader) throws -> HealthExportFormat.Manifest {
        guard let entry = reader.entry(named: HealthExportFormat.manifestEntry) else { throw HealthImportError.unsupportedFormat }
        let data: Data
        do {
            data = try reader.data(for: entry)
        } catch {
            throw HealthImportError.invalidManifest
        }
        guard let manifest = try? JSONDecoder().decode(HealthExportFormat.Manifest.self, from: data) else {
            throw HealthImportError.invalidManifest
        }
        guard manifest.format == HealthExportFormat.format else { throw HealthImportError.unsupportedFormat }
        guard manifest.format_version <= HealthExportFormat.formatVersion else { throw HealthImportError.newerFormat }
        return manifest
    }

    static func apply(
        preview: HealthImportPreview,
        mode: HealthImportMode,
        writer: HealthDatabase,
        calendar: Calendar,
        ownBundleID: String,
        nowMs: Int64 = HealthSampleMapper.ms(Date()),
        progress: @Sendable (Int) -> Void = { _ in }
    ) async throws -> HealthImportResult {
        let reader = try ZipArchiveReader(url: preview.url)
        let manifest = try manifest(from: reader)
        guard let samplesEntry = reader.entry(named: HealthExportFormat.samplesEntry) else { throw HealthImportError.missingSamples }

        var result = HealthImportResult()
        result.checksumMismatches = checksumMismatches(reader: reader)

        if mode == .replaceAll {
            try await writer.wipeAllData()
        }

        // Types: registry entries, or `health_type_meta` rows under `other` for unknown slugs.
        var types: [String: HealthMetricType] = [:]
        for entry in manifest.types {
            if let known = HealthMetricRegistry.type(id: entry.type) {
                types[entry.type] = known
            } else {
                let meta = HealthTypeMetaRow(
                    typeID: entry.type,
                    category: HealthCategory(rawValue: entry.category) != nil ? entry.category : HealthCategory.other.rawValue,
                    kind: entry.kind,
                    aggregation: entry.aggregation,
                    unit: entry.unit,
                    displayName: entry.display_name,
                    platform: manifest.platform,
                    nativeID: entry.native_id
                )
                try await writer.upsertTypeMeta(meta)
                types[entry.type] = HealthMetricRegistry.type(fromMeta: meta)
            }
        }

        // sources.json
        if let sourcesEntry = reader.entry(named: HealthExportFormat.sourcesEntry),
           let data = try? reader.data(for: sourcesEntry),
           let entries = try? JSONDecoder().decode([HealthExportFormat.SourceEntry].self, from: data) {
            try await writer.upsertSources(entries.map {
                HealthSourceRow(
                    id: $0.id, name: $0.name, deviceModel: $0.device_model, deviceType: $0.device_type,
                    lastSeenMs: $0.last_seen.flatMap(ISO8601Fast.parse)?.ms
                )
            })
        }

        // samples.ndjson in batches
        var batch: [HealthSampleRow] = []
        var touched = Set<String>()
        var processed = 0
        var lineError: Error?
        var pendingSources: [String: HealthSourceRow] = [:]

        func flush() async throws {
            guard !batch.isEmpty else { return }
            let upsert = try await writer.upsertSamples(batch)
            result.inserted += upsert.inserted
            result.updated += upsert.updated
            result.unchanged += upsert.unchanged
            batch.removeAll(keepingCapacity: true)
            if !pendingSources.isEmpty {
                try await writer.upsertSources(Array(pendingSources.values))
                pendingSources.removeAll()
            }
            progress(processed)
        }

        // Lines are parsed synchronously per chunk; batches are awaited between chunks.
        var chunkRows: [HealthSampleRow] = []
        do {
            try reader.forEachLine(of: samplesEntry, maxLineBytes: HealthExportFormat.maxLineBytes) { line in
                guard !line.isEmpty else { return }
                guard let object = try? JSONSerialization.jsonObject(with: line) as? [String: Any],
                      let typeID = object["type_id"] as? String
                else {
                    result.rejectedInvalid += 1
                    return
                }
                guard HealthExportFormat.isExportable(typeID: typeID) else { return }
                let type = types[typeID] ?? HealthMetricRegistry.resolve(typeID: typeID, unit: (object["unit"] as? String) ?? "count")
                guard let row = HealthExportFormat.row(fromLine: object, type: type, calendar: calendar) else {
                    result.rejectedInvalid += 1
                    return
                }
                guard row.unit == type.unit else {
                    result.rejectedUnitMismatch += 1
                    return
                }
                if let name = object["source_name"] as? String, pendingSources[row.sourceID] == nil {
                    pendingSources[row.sourceID] = HealthSourceRow(id: row.sourceID, name: name, deviceModel: row.device, deviceType: row.deviceType, lastSeenMs: row.endMs)
                }
                chunkRows.append(row)
                touched.insert(typeID)
                processed += 1
            }
        } catch {
            lineError = error
        }
        if let lineError { throw lineError }

        for row in chunkRows {
            batch.append(row)
            if batch.count >= batchSize {
                try await flush()
                try Task.checkCancellation()
            }
        }
        try await flush()

        // Rollups for every touched type (plus everything when replacing).
        let rebuildTypes = mode == .replaceAll ? Set(types.keys).union(touched) : touched
        for typeID in rebuildTypes.sorted() {
            let type = types[typeID] ?? HealthMetricRegistry.resolve(typeID: typeID)
            try await writer.rebuildAllRollups(type: type, tz: calendar.timeZone.identifier, calendar: calendar, ownBundleID: ownBundleID)
        }
        result.touchedTypes = touched.sorted()
        return result
    }

    /// Entries whose recorded SHA-256 does not match the archive bytes (warning only).
    static func checksumMismatches(reader: ZipArchiveReader) -> [String] {
        guard let entry = reader.entry(named: HealthExportFormat.checksumsEntry),
              let data = try? reader.data(for: entry),
              let checksums = try? JSONDecoder().decode([String: String].self, from: data)
        else { return [] }
        var mismatches: [String] = []
        for (name, expected) in checksums.sorted(by: { $0.key < $1.key }) {
            guard let target = reader.entry(named: name) else { continue }
            var hasher = SHA256()
            do {
                try reader.forEachChunk(of: target) { hasher.update(data: $0) }
            } catch {
                mismatches.append(name)
                continue
            }
            if HealthExporter.hex(hasher.finalize()) != expected.lowercased() {
                mismatches.append(name)
            }
        }
        return mismatches
    }
}
