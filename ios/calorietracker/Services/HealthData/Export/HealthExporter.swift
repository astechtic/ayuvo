import CryptoKit
import Foundation

nonisolated struct HealthExportSummary: Sendable, Equatable {
    var url: URL
    var rowCount: Int
    var typeCount: Int
    var startMs: Int64?
    var endMs: Int64?
}

/// Streams the mirror into a stored zip: manifest → samples.ndjson → sources.json →
/// checksums.json. Runs on the caller's task (the store enters it via `Task.detached`).
nonisolated enum HealthExporter {
    static let pageSize = 5000

    static func export(
        reader: HealthDatabase,
        to destination: URL,
        appVersion: String,
        calendar: Calendar,
        typeMeta: [String: HealthTypeMetaRow],
        now: Date = Date(),
        progress: @Sendable (Int) -> Void = { _ in }
    ) async throws -> HealthExportSummary {
        let fileManager = FileManager.default
        let workDirectory = destination.deletingLastPathComponent().appendingPathComponent("work-\(UUID().uuidString)", isDirectory: true)
        try fileManager.createDirectory(at: workDirectory, withIntermediateDirectories: true)
        defer { try? fileManager.removeItem(at: workDirectory) }

        let sources = Dictionary(uniqueKeysWithValues: try await reader.allSources().map { ($0.id, $0) })
        let deviceOffset = calendar.timeZone.secondsFromGMT(for: now)

        // samples.ndjson (streamed, hashed)
        let samplesURL = workDirectory.appendingPathComponent(HealthExportFormat.samplesEntry)
        fileManager.createFile(atPath: samplesURL.path, contents: nil)
        let samplesHandle = try FileHandle(forWritingTo: samplesURL)
        var samplesHash = SHA256()
        var counts: [String: Int] = [:]
        var rowCount = 0
        var startMs: Int64?
        var endMs: Int64?
        var key: (endMs: Int64, id: String)?
        while true {
            try Task.checkCancellation()
            let page = try await reader.exportPage(
                after: key,
                excludingTypePrefixes: HealthExportFormat.excludedTypePrefixes,
                excludingTypes: HealthExportFormat.excludedTypes,
                limit: pageSize
            )
            guard !page.isEmpty else { break }
            var buffer = Data()
            for row in page {
                let line = HealthExportFormat.sampleLine(row, sourceName: sources[row.sourceID]?.name, deviceZoneOffsetS: deviceOffset)
                guard let data = try? JSONSerialization.data(withJSONObject: line, options: [.sortedKeys]) else { continue }
                buffer.append(data)
                buffer.append(0x0A)
                counts[row.typeID, default: 0] += 1
                rowCount += 1
                startMs = min(startMs ?? row.startMs, row.startMs)
                endMs = max(endMs ?? row.endMs, row.endMs)
            }
            samplesHash.update(data: buffer)
            try samplesHandle.write(contentsOf: buffer)
            key = page.last.map { ($0.endMs, $0.id) }
            progress(rowCount)
        }
        try samplesHandle.close()

        // sources.json
        let sourceEntries = sources.values.sorted { $0.id < $1.id }.map { source in
            HealthExportFormat.SourceEntry(
                id: source.id,
                name: source.name,
                device_model: source.deviceModel,
                device_type: source.deviceType,
                last_seen: source.lastSeenMs.map { ISO8601Fast.format(ms: $0, offsetS: 0) }
            )
        }
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys, .prettyPrinted]
        let sourcesData = try encoder.encode(sourceEntries)

        // manifest.json
        let types = counts.keys.sorted().map { typeID -> HealthExportFormat.TypeEntry in
            let type = HealthMetricRegistry.resolve(typeID: typeID, metaRows: typeMeta)
            return HealthExportFormat.TypeEntry(
                type: typeID,
                category: type.category.rawValue,
                kind: type.kind.rawValue,
                aggregation: type.aggregation.rawValue,
                unit: type.unit,
                display_name: type.englishName,
                native_id: type.hkIdentifiers.first,
                record_count: counts[typeID] ?? 0,
                series_count: 0
            )
        }
        let manifest = HealthExportFormat.Manifest(
            format: HealthExportFormat.format,
            format_version: HealthExportFormat.formatVersion,
            platform: "ios",
            app_version: appVersion,
            exported_at: ISO8601Fast.format(ms: HealthSampleMapper.ms(now), offsetS: deviceOffset),
            zone_id: calendar.timeZone.identifier,
            date_range: HealthExportFormat.DateRange(
                start: startMs.map { ISO8601Fast.format(ms: $0, offsetS: deviceOffset) },
                end: endMs.map { ISO8601Fast.format(ms: $0, offsetS: deviceOffset) }
            ),
            registry_version: HealthSchema.registryVersion,
            percent_convention: HealthExportFormat.percentConvention,
            types: types
        )
        let manifestData = try encoder.encode(manifest)

        // checksums.json
        let checksums: [String: String] = [
            HealthExportFormat.manifestEntry: hex(SHA256.hash(data: manifestData)),
            HealthExportFormat.samplesEntry: hex(samplesHash.finalize()),
            HealthExportFormat.sourcesEntry: hex(SHA256.hash(data: sourcesData)),
        ]
        let checksumsData = try encoder.encode(checksums)

        if fileManager.fileExists(atPath: destination.path) {
            try fileManager.removeItem(at: destination)
        }
        let writer = try ZipArchiveWriter(url: destination)
        try writer.addStored(name: HealthExportFormat.manifestEntry, data: manifestData)
        try writer.addStored(name: HealthExportFormat.samplesEntry, fileURL: samplesURL)
        try writer.addStored(name: HealthExportFormat.sourcesEntry, data: sourcesData)
        try writer.addStored(name: HealthExportFormat.checksumsEntry, data: checksumsData)
        try writer.finish()

        return HealthExportSummary(url: destination, rowCount: rowCount, typeCount: types.count, startMs: startMs, endMs: endMs)
    }

    static func hex<D: Digest>(_ digest: D) -> String {
        digest.map { String(format: "%02x", $0) }.joined()
    }

    /// `Ayuvo-Health-yyyyMMdd.zip` inside a fresh temp directory the caller deletes after sharing.
    static func makeDestinationURL(now: Date = Date(), calendar: Calendar = .current) throws -> URL {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent("ayuvo-health-export-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let components = calendar.dateComponents([.year, .month, .day], from: now)
        let stamp = String(format: "%04d%02d%02d", components.year ?? 1970, components.month ?? 1, components.day ?? 1)
        return directory.appendingPathComponent("Ayuvo-Health-\(stamp).zip")
    }
}
