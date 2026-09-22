import Foundation

/// Settings › Data & Privacy › Backup & Export › Export All Data: one `Ayuvo-Export-YYYY-MM-DD.zip`
/// holding the output of every existing exporter (food diary JSON, `ayuvo-health-data`,
/// `ayuvo-medications`, `ayuvo-records`, the settings/profile backup) plus a `manifest.json`.
/// Each part is written by its own exporter unchanged, so every file inside still imports through
/// its usual Import action. This type runs no exporter itself: it assembles the already-written
/// part files into the outer zip and describes them in the manifest (`ExportAllDataView` drives it).
nonisolated enum AllDataExport {
    static let format = "ayuvo-all-data"
    static let formatVersion = 1
    static let manifestEntry = "manifest.json"

    /// One exporter's output, already on disk.
    struct Part: Sendable, Equatable {
        /// Stable id of the section (`food_diary`, `health_data`, `medications`, `health_records`, `app_backup`).
        var section: String
        /// The existing format the file is in (`ayuvo-food-diary`, `ayuvo-health-data`, …).
        var format: String
        /// Entry name inside the outer zip.
        var name: String
        var fileURL: URL
        /// What the file holds, e.g. `["food_entries": 120, "water_entries": 30]`.
        var counts: [String: Int]
    }

    struct Manifest: Codable, Sendable, Equatable {
        struct File: Codable, Sendable, Equatable {
            var name: String
            var section: String
            var format: String
            var bytes: Int64
            var counts: [String: Int]
        }

        var app: String
        var format: String
        var formatVersion: Int
        var platform: String
        var appVersion: String
        var createdAt: String
        var files: [File]
        /// Sections with nothing to export (left out of the zip), e.g. `["medications"]`.
        var skipped: [String]

        enum CodingKeys: String, CodingKey {
            case app
            case format
            case formatVersion = "format_version"
            case platform
            case appVersion = "app_version"
            case createdAt = "created_at"
            case files
            case skipped
        }
    }

    /// `Ayuvo-Export-2026-09-22.zip` (local day).
    static func fileName(now: Date = Date(), calendar: Calendar = .current) -> String {
        let components = calendar.dateComponents([.year, .month, .day], from: now)
        let day = String(format: "%04d-%02d-%02d", components.year ?? 1970, components.month ?? 1, components.day ?? 1)
        return "Ayuvo-Export-\(day).zip"
    }

    /// ISO-8601 with the device offset, matching the other exporters' `exported_at`.
    static func timestamp(_ date: Date, timeZone: TimeZone = .current) -> String {
        let formatter = ISO8601DateFormatter()
        formatter.timeZone = timeZone
        formatter.formatOptions = [.withInternetDateTime]
        return formatter.string(from: date)
    }

    static func manifest(parts: [Part], skipped: [String], createdAt: Date, appVersion: String, timeZone: TimeZone = .current) -> Manifest {
        let files = parts.map { part -> Manifest.File in
            let size = (try? FileManager.default.attributesOfItem(atPath: part.fileURL.path)[.size] as? NSNumber)?.int64Value ?? 0
            return Manifest.File(name: part.name, section: part.section, format: part.format, bytes: size, counts: part.counts)
        }
        return Manifest(
            app: "Ayuvo",
            format: format,
            formatVersion: formatVersion,
            platform: "ios",
            appVersion: appVersion,
            createdAt: timestamp(createdAt, timeZone: timeZone),
            files: files,
            skipped: skipped
        )
    }

    /// Writes `manifest.json` then every part (stored, streamed from disk) into `destination`.
    /// Parts with duplicate or unsafe names are rejected rather than silently overwritten.
    @discardableResult
    static func assemble(
        parts: [Part],
        skipped: [String],
        to destination: URL,
        createdAt: Date = Date(),
        appVersion: String,
        timeZone: TimeZone = .current
    ) throws -> Manifest {
        var seen: Set<String> = [manifestEntry]
        for part in parts {
            guard RecordsArchiveFormat.isSafeEntryName(part.name), seen.insert(part.name).inserted else {
                throw ZipArchiveError.badEntry(part.name)
            }
        }
        let manifest = manifest(parts: parts, skipped: skipped, createdAt: createdAt, appVersion: appVersion, timeZone: timeZone)
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.prettyPrinted, .sortedKeys, .withoutEscapingSlashes]
        var manifestData = try encoder.encode(manifest)
        manifestData.append(0x0A)

        let fileManager = FileManager.default
        if fileManager.fileExists(atPath: destination.path) {
            try fileManager.removeItem(at: destination)
        }
        let writer = try ZipArchiveWriter(url: destination)
        do {
            try writer.addStored(name: manifestEntry, data: manifestData)
            for part in parts {
                try Task.checkCancellation()
                try writer.addStored(name: part.name, fileURL: part.fileURL)
            }
            try writer.finish()
        } catch {
            try? writer.finish()
            try? fileManager.removeItem(at: destination)
            throw error
        }
        return manifest
    }

    /// Fresh temp directory for one export run; the caller deletes it after sharing.
    static func makeWorkDirectory() throws -> URL {
        let directory = FileManager.default.temporaryDirectory
            .appendingPathComponent("ayuvo-all-data-export-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        return directory
    }
}
