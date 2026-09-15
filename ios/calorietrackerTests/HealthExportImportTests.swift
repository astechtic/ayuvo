import Compression
import CryptoKit
import Foundation
import Testing
@testable import calorietracker

struct HealthExportImportTests {
    private typealias F = HealthTestFixtures

    private func seededDatabase(rows: Int) async throws -> HealthDatabase {
        let db = try await HealthDatabase.inMemory()
        var samples: [HealthSampleRow] = []
        let base = F.date(2025, 1, 1, 6)
        for index in 0..<rows {
            let start = base.addingTimeInterval(Double(index) * 1800)
            switch index % 4 {
            case 0: samples.append(F.row(id: "s\(index)", type: F.steps, start: start, end: start.addingTimeInterval(600), value: Double(index % 500)))
            case 1: samples.append(F.row(id: "h\(index)", type: F.heartRate, start: start, value: 60 + Double(index % 40), value2: 55, value3: 120, count: 5))
            case 2: samples.append(F.row(id: "w\(index)", type: F.weight, start: start, value: 80 - Double(index % 7) / 10, source: F.bundleID, extraJSON: "{\"ayuvo_weight_id\":\"\(index)\"}"))
            default: samples.append(F.row(id: "d\(index)", type: HealthMetricRegistry.type(id: "dietary_energy")!, start: start, value: 250))
            }
        }
        try await db.upsertSamples(samples)
        try await db.upsertSources([HealthSourceRow(id: "com.apple.health", name: "Health", deviceModel: "Apple Watch", deviceType: nil, lastSeenMs: 1)])
        return db
    }

    private func exportURL() throws -> URL {
        try F.temporaryDirectory().appendingPathComponent("export.zip")
    }

    @Test func tenThousandRowRoundTripPreservesEverythingExportable() async throws {
        let source = try await seededDatabase(rows: 10_000)
        let url = try exportURL()
        defer { try? FileManager.default.removeItem(at: url.deletingLastPathComponent()) }
        let summary = try await HealthExporter.export(reader: source, to: url, appVersion: "7.0", calendar: F.calendar, typeMeta: [:])
        #expect(summary.rowCount == 7500, "dietary_* rows are not exported")
        #expect(summary.typeCount == 3)

        let preview = try HealthImporter.preview(url: url)
        #expect(preview.recordCount == 7500)
        #expect(preview.platform == "ios")
        #expect(preview.unknownTypes.isEmpty)

        let target = try await HealthDatabase.inMemory()
        let result = try await HealthImporter.apply(preview: preview, mode: .merge, writer: target, calendar: F.calendar, ownBundleID: F.bundleID)
        #expect(result.inserted == 7500)
        #expect(result.rejectedUnitMismatch == 0)
        #expect(result.rejectedInvalid == 0)
        #expect(result.checksumMismatches.isEmpty)
        #expect(try await target.sampleCount() == 7500)
        let original = try #require(try await source.sample(id: "h1"))
        let imported = try #require(try await target.sample(id: "h1"))
        #expect(imported.value == original.value && imported.value2 == original.value2 && imported.count == original.count)
        #expect(imported.startMs == original.startMs && imported.endMs == original.endMs)
        #expect(imported.localDay == original.localDay)
        #expect(imported.origin == HealthRowOrigin.fileImport.rawValue)
        #expect(try await target.sample(id: "w2")?.extraJSON?.contains("ayuvo_weight_id") == true)
        #expect(try await target.rollupCount(type: "steps") > 0)
        let sourceRollup = try await source.rebuildAllRollups(type: F.steps, tz: F.calendar.timeZone.identifier, calendar: F.calendar, ownBundleID: F.bundleID)
        #expect(sourceRollup == (try await target.rollupCount(type: "steps")))
    }

    @Test func importedRowsAreImmuneToPlatformDeletionsAndTombstonesWin() async throws {
        let source = try await seededDatabase(rows: 8)
        let url = try exportURL()
        defer { try? FileManager.default.removeItem(at: url.deletingLastPathComponent()) }
        _ = try await HealthExporter.export(reader: source, to: url, appVersion: "7.0", calendar: F.calendar, typeMeta: [:])
        let target = try await HealthDatabase.inMemory()
        // Pre-existing tombstone on the target must survive the import.
        try await target.upsertSamples([F.row(id: "s0", type: F.steps, start: F.date(2025, 1, 1, 6), value: 1, updatedMs: 1)])
        try await target.tombstone(ids: ["s0"], nowMs: 2)
        let preview = try HealthImporter.preview(url: url)
        _ = try await HealthImporter.apply(preview: preview, mode: .merge, writer: target, calendar: F.calendar, ownBundleID: F.bundleID)
        #expect(try await target.sample(id: "s0")?.deleted == 1)
        try await target.tombstone(ids: ["s4"], nowMs: 99_999_999_999_999)
        #expect(try await target.sample(id: "s4")?.deleted == 0, "origin=1 rows ignore platform deletions")
    }

    @Test func replaceAllClearsExistingRowsAndCursors() async throws {
        let source = try await seededDatabase(rows: 8)
        let url = try exportURL()
        defer { try? FileManager.default.removeItem(at: url.deletingLastPathComponent()) }
        _ = try await HealthExporter.export(reader: source, to: url, appVersion: "7.0", calendar: F.calendar, typeMeta: [:])
        let target = try await HealthDatabase.inMemory()
        try await target.upsertSamples([F.row(id: "old", type: F.steps, start: F.date(2024, 1, 1), value: 1)])
        var state = HealthSyncStateRow(typeID: "steps")
        state.cursor = "x"
        try await target.setSyncState(state)
        let preview = try HealthImporter.preview(url: url)
        _ = try await HealthImporter.apply(preview: preview, mode: .replaceAll, writer: target, calendar: F.calendar, ownBundleID: F.bundleID)
        #expect(try await target.sample(id: "old") == nil)
        #expect(try await target.syncState(type: "steps")?.cursor == nil)
        #expect(try await target.sampleCount() == 6)
    }

    // MARK: - Hand-built archives

    private func archive(manifest: [String: Any], samples: String, checksums: Bool = true) throws -> URL {
        let url = try exportURL()
        let writer = try ZipArchiveWriter(url: url)
        let manifestData = try JSONSerialization.data(withJSONObject: manifest, options: [.sortedKeys])
        let samplesData = Data(samples.utf8)
        try writer.addStored(name: HealthExportFormat.manifestEntry, data: manifestData)
        try writer.addStored(name: HealthExportFormat.samplesEntry, data: samplesData)
        try writer.addStored(name: HealthExportFormat.sourcesEntry, data: Data("[]".utf8))
        if checksums {
            let sums = [
                HealthExportFormat.manifestEntry: HealthExporter.hex(SHA256.hash(data: manifestData)),
                HealthExportFormat.samplesEntry: HealthExporter.hex(SHA256.hash(data: Data("tampered".utf8))),
            ]
            try writer.addStored(name: HealthExportFormat.checksumsEntry, data: try JSONSerialization.data(withJSONObject: sums))
        }
        try writer.finish()
        return url
    }

    private func manifest(version: Int = 1, format: String = HealthExportFormat.format, types: [[String: Any]]) -> [String: Any] {
        [
            "format": format, "format_version": version, "platform": "android", "app_version": "7.0",
            "exported_at": "2026-09-14T10:00:00.000+02:00", "zone_id": "Europe/Berlin",
            "date_range": ["start": "2026-09-01T00:00:00.000+02:00", "end": "2026-09-13T00:00:00.000+02:00"],
            "registry_version": 1, "percent_convention": "0-100", "types": types,
        ]
    }

    private func line(_ fields: [String: Any]) -> String {
        var object: [String: Any] = [
            "id": UUID().uuidString.lowercased(), "type_id": "steps", "start": "2026-09-10T08:00:00.000+02:00", "end": "2026-09-10T08:10:00.000+02:00",
            "updated": "2026-09-10T08:10:00.000Z", "value": 100, "unit": "count", "count": 1, "source_id": "com.google.android.apps.fitness",
            "source_name": "Fit", "origin": 0,
        ]
        for (key, value) in fields { object[key] = value }
        return String(data: try! JSONSerialization.data(withJSONObject: object), encoding: .utf8)!
    }

    @Test func unitMismatchIsRejectedUnknownTypeAcceptedAndChecksumMismatchIsAWarning() async throws {
        let types: [[String: Any]] = [
            ["type": "steps", "category": "activity", "kind": "cumulative", "aggregation": "SUM", "unit": "count", "record_count": 2, "series_count": 0],
            ["type": "skin_temperature_delta", "category": "vitals", "kind": "discrete", "aggregation": "AVERAGE", "unit": "degC", "display_name": "Skin Temp", "record_count": 1, "series_count": 0],
        ]
        let samples = [
            line([:]),
            line(["unit": "steps"]),
            line(["type_id": "skin_temperature_delta", "unit": "degC", "value": 0.4]),
            "not json",
        ].joined(separator: "\n")
        let url = try archive(manifest: manifest(types: types), samples: samples)
        defer { try? FileManager.default.removeItem(at: url.deletingLastPathComponent()) }
        let preview = try HealthImporter.preview(url: url)
        #expect(preview.unknownTypes == ["skin_temperature_delta"])
        #expect(preview.platform == "android")
        let target = try await HealthDatabase.inMemory()
        let result = try await HealthImporter.apply(preview: preview, mode: .merge, writer: target, calendar: F.calendar, ownBundleID: F.bundleID)
        #expect(result.inserted == 2)
        #expect(result.rejectedUnitMismatch == 1)
        #expect(result.rejectedInvalid == 1)
        #expect(result.checksumMismatches == [HealthExportFormat.samplesEntry])
        #expect(try await target.allTypeMeta().map(\.typeID) == ["skin_temperature_delta"])
        #expect(try await target.sampleCount(type: "skin_temperature_delta") == 1)
        let imported = try #require(try await target.rows(type: "steps", startMs: 0, endMs: .max).first)
        #expect(imported.localDay == "2026-09-10")
        #expect(imported.startOffsetS == 7200)
    }

    @Test func newerMajorAndForeignFormatsFailClosed() throws {
        let newer = try archive(manifest: manifest(version: 2, types: []), samples: "", checksums: false)
        defer { try? FileManager.default.removeItem(at: newer.deletingLastPathComponent()) }
        #expect(throws: HealthImportError.newerFormat) { try HealthImporter.preview(url: newer) }
        let foreign = try archive(manifest: manifest(format: "ayuvo-cloud-backup", types: []), samples: "", checksums: false)
        defer { try? FileManager.default.removeItem(at: foreign.deletingLastPathComponent()) }
        #expect(throws: HealthImportError.unsupportedFormat) { try HealthImporter.preview(url: foreign) }
        let garbage = try exportURL()
        try Data("hello".utf8).write(to: garbage)
        defer { try? FileManager.default.removeItem(at: garbage.deletingLastPathComponent()) }
        #expect(throws: HealthImportError.notAnArchive) { try HealthImporter.preview(url: garbage) }
    }

    @Test func androidFixtureImportsWhenPresent() async throws {
        let url = F.androidFixtureURL
        guard FileManager.default.fileExists(atPath: url.path) else {
            // Committed by the Android side before Phase 4 closes; nothing to verify yet.
            return
        }
        let preview = try HealthImporter.preview(url: url)
        #expect(preview.platform == "android")
        let target = try await HealthDatabase.inMemory()
        let result = try await HealthImporter.apply(preview: preview, mode: .merge, writer: target, calendar: F.calendar, ownBundleID: F.bundleID)
        #expect(result.inserted == preview.recordCount - result.rejectedUnitMismatch - result.rejectedInvalid)
        #expect(result.checksumMismatches.isEmpty)
    }

    @Test func iso8601FastRoundTripsOffsetsAndFractions() {
        let cases: [(String, Int64, Int)] = [
            ("2026-09-10T08:00:00.000+02:00", 1_789_020_000_000, 7200),
            ("2026-09-10T06:00:00Z", 1_789_020_000_000, 0),
            ("2026-09-10T01:30:00.5-04:30", 1_789_020_000_500, -16200),
            ("2026-09-10T06:00:00.123456789+0000", 1_789_020_000_123, 0),
        ]
        for (text, ms, offset) in cases {
            let parsed = ISO8601Fast.parse(text)
            #expect(parsed?.ms == ms, Comment(rawValue: text))
            #expect(parsed?.offsetS == offset, Comment(rawValue: text))
        }
        #expect(ISO8601Fast.format(ms: 1_789_020_000_000, offsetS: 7200) == "2026-09-10T08:00:00.000+02:00")
        #expect(ISO8601Fast.format(ms: 0, offsetS: -19800) == "1969-12-31T18:30:00.000-05:30")
        #expect(ISO8601Fast.parse("2026-13-01T00:00:00Z") == nil)
        #expect(ISO8601Fast.parse("garbage") == nil)
        let leap = ISO8601Fast.parse("2024-02-29T12:00:00Z")!
        #expect(ISO8601Fast.format(ms: leap.ms, offsetS: 0) == "2024-02-29T12:00:00.000+00:00")
    }
}
