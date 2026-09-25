import Foundation

/// Settings › Data & Privacy › Backup & Export › Import All Data: reads an `ayuvo-all-data` zip
/// (`AllDataExport`, from either platform) and plans which of its parts to hand to their existing
/// importers. Pure logic only; `ImportAllDataView` extracts the entries and runs the importers.
nonisolated enum AllDataImport {
    /// Known sections, in the order they are imported. Settings go first so a same-platform
    /// restore can stand in for the food diary part (the app backup already carries the diary) and
    /// for the portable profile-and-logs part.
    enum Section: String, CaseIterable, Sendable {
        case appBackup = "app_backup"
        case portableData = "portable_data"
        case foodDiary = "food_diary"
        case healthData = "health_data"
        case medications
        case healthRecords = "health_records"
        case coachChats = "coach_chats"
    }

    enum Disposition: Equatable, Sendable {
        /// Handed to the section's importer.
        case importIt
        /// Food diary or portable data when a same-platform app backup is in the file: the backup
        /// restores the diary, water, profile and logs, so importing the JSON too would duplicate
        /// or overwrite them. Imported after all if the app backup fails.
        case coveredByAppBackup
        /// App backup from another platform: its preference keys don't map across platforms.
        case otherPlatform
    }

    struct Item: Equatable, Sendable {
        var section: Section
        var entryName: String
        var format: String
        var counts: [String: Int]
        var disposition: Disposition
    }

    struct Plan: Equatable, Sendable {
        var manifest: AllDataExport.Manifest
        var items: [Item]

        var isFromThisPlatform: Bool { manifest.platform == platform }
    }

    enum ImportError: LocalizedError, Equatable {
        case notAnExport
        case newerVersion
        case damaged

        var errorDescription: String? {
            switch self {
            case .notAnExport: String(localized: "This isn't an Ayuvo export.")
            case .newerVersion: String(localized: "This file was made by a newer version of Ayuvo. Update the app to import it.")
            case .damaged: String(localized: "This export is damaged or incomplete.")
            }
        }
    }

    static let platform = "ios"

    private struct Header: Decodable {
        var app: String?
        var format: String?
        var formatVersion: Int?

        enum CodingKeys: String, CodingKey {
            case app
            case format
            case formatVersion = "format_version"
        }
    }

    /// Validates `manifest.json` and the entries it lists. `entryNames` are the names present in
    /// the zip. Unknown sections are ignored; an unsafe or missing entry rejects the whole file.
    static func plan(manifestData: Data?, entryNames: Set<String>, platform: String = platform) throws -> Plan {
        guard let manifestData,
              let header = try? JSONDecoder().decode(Header.self, from: manifestData),
              header.app?.caseInsensitiveCompare("Ayuvo") == .orderedSame,
              header.format == AllDataExport.format,
              let version = header.formatVersion
        else { throw ImportError.notAnExport }
        guard version <= AllDataExport.formatVersion else { throw ImportError.newerVersion }
        guard let manifest = try? JSONDecoder().decode(AllDataExport.Manifest.self, from: manifestData) else {
            throw ImportError.notAnExport
        }

        var bySection: [Section: AllDataExport.Manifest.File] = [:]
        for file in manifest.files {
            guard let section = Section(rawValue: file.section) else { continue }
            guard RecordsArchiveFormat.isSafeEntryName(file.name),
                  file.name != AllDataExport.manifestEntry,
                  entryNames.contains(file.name)
            else { throw ImportError.damaged }
            if bySection[section] == nil { bySection[section] = file }
        }

        let sameAppBackup = bySection[.appBackup] != nil && manifest.platform == platform
        let items = Section.allCases.compactMap { section -> Item? in
            guard let file = bySection[section] else { return nil }
            let disposition: Disposition
            switch section {
            case .appBackup: disposition = sameAppBackup ? .importIt : .otherPlatform
            case .portableData, .foodDiary: disposition = sameAppBackup ? .coveredByAppBackup : .importIt
            default: disposition = .importIt
            }
            return Item(section: section, entryName: file.name, format: file.format, counts: file.counts, disposition: disposition)
        }
        return Plan(manifest: manifest, items: items)
    }

    /// Reads the manifest and entry names of the zip at `url`.
    static func plan(url: URL) throws -> Plan {
        guard let reader = try? ZipArchiveReader(url: url) else { throw ImportError.notAnExport }
        let manifestData = reader.entry(named: AllDataExport.manifestEntry).flatMap { try? reader.data(for: $0) }
        return try plan(manifestData: manifestData, entryNames: Set(reader.entries.map(\.name)))
    }

    /// Whether `item` still runs once the app backup outcome is known.
    static func shouldImport(_ item: Item, appBackupRestored: Bool) -> Bool {
        switch item.disposition {
        case .importIt: true
        case .coveredByAppBackup: !appBackupRestored
        case .otherPlatform: false
        }
    }

    /// Streams one entry of the zip to `destination` (records archives can be hundreds of MB).
    static func extract(entryNamed name: String, from reader: ZipArchiveReader, to destination: URL) throws {
        guard let entry = reader.entry(named: name) else { throw ImportError.damaged }
        FileManager.default.createFile(atPath: destination.path, contents: nil)
        let handle = try FileHandle(forWritingTo: destination)
        defer { try? handle.close() }
        try reader.forEachChunk(of: entry) { chunk in
            try handle.write(contentsOf: chunk)
        }
    }

    /// "120 food entries · 30 water entries" from manifest counts (zeros left out).
    static func countsText(_ counts: [String: Int]) -> String {
        counts.sorted { $0.key < $1.key }
            .filter { $0.value > 0 }
            .map { "\($0.value.formatted()) \($0.key.replacingOccurrences(of: "_", with: " "))" }
            .joined(separator: " · ")
    }

    /// Fresh temp directory for one import run; the caller deletes it when done.
    static func makeWorkDirectory() throws -> URL {
        let directory = FileManager.default.temporaryDirectory
            .appendingPathComponent("ayuvo-all-data-import-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        return directory
    }
}
