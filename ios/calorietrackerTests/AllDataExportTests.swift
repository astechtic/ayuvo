import Foundation
import Testing
@testable import calorietracker

struct AllDataExportTests {
    private func tempDirectory() throws -> URL {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("all-data-tests-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        return url
    }

    @Test func fileNameUsesLocalDay() {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(identifier: "Asia/Kolkata")!
        // 2026-09-21T20:00:00Z is already 22 September in Kolkata.
        let date = Date(timeIntervalSince1970: 1_790_020_800)
        #expect(AllDataExport.fileName(now: date, calendar: calendar) == "Ayuvo-Export-2026-09-22.zip")
    }

    @Test func assemblesManifestFirstThenEveryPartUnchanged() throws {
        let directory = try tempDirectory()
        defer { try? FileManager.default.removeItem(at: directory) }
        let diary = Data("{\"days\":[]}".utf8)
        let medications = Data("{\"format\":\"ayuvo-medications\"}\n".utf8)
        let diaryURL = directory.appendingPathComponent("diary.json")
        let medicationsURL = directory.appendingPathComponent("meds.json")
        try diary.write(to: diaryURL)
        try medications.write(to: medicationsURL)
        let parts = [
            AllDataExport.Part(section: "food_diary", format: "ayuvo-food-diary", name: "food-diary/diary.json", fileURL: diaryURL,
                               counts: ["food_entries": 3, "water_entries": 1]),
            AllDataExport.Part(section: "medications", format: "ayuvo-medications", name: "medications/ayuvo-medications.json", fileURL: medicationsURL,
                               counts: ["medications": 2]),
        ]
        let destination = directory.appendingPathComponent("out.zip")
        let created = Date(timeIntervalSince1970: 1_790_000_000)
        let manifest = try AllDataExport.assemble(
            parts: parts,
            skipped: ["health_data", "health_records"],
            to: destination,
            createdAt: created,
            appVersion: "7.1",
            timeZone: TimeZone(identifier: "UTC")!
        )

        let reader = try ZipArchiveReader(url: destination)
        #expect(reader.entries.map(\.name) == ["manifest.json", "food-diary/diary.json", "medications/ayuvo-medications.json"])
        #expect(try reader.data(for: reader.entry(named: "food-diary/diary.json")!) == diary)
        #expect(try reader.data(for: reader.entry(named: "medications/ayuvo-medications.json")!) == medications)

        let decoded = try JSONDecoder().decode(
            AllDataExport.Manifest.self,
            from: reader.data(for: reader.entry(named: "manifest.json")!)
        )
        #expect(decoded == manifest)
        #expect(decoded.app == "Ayuvo")
        #expect(decoded.format == AllDataExport.format)
        #expect(decoded.formatVersion == AllDataExport.formatVersion)
        #expect(decoded.platform == "ios")
        #expect(decoded.appVersion == "7.1")
        #expect(decoded.createdAt == "2026-09-21T14:13:20Z")
        #expect(decoded.skipped == ["health_data", "health_records"])
        #expect(decoded.files.map(\.name) == parts.map(\.name))
        #expect(decoded.files[0].bytes == Int64(diary.count))
        #expect(decoded.files[0].counts == ["food_entries": 3, "water_entries": 1])
        #expect(decoded.files[1].format == "ayuvo-medications")
    }

    @Test func manifestUsesSnakeCaseKeys() throws {
        let directory = try tempDirectory()
        defer { try? FileManager.default.removeItem(at: directory) }
        let destination = directory.appendingPathComponent("empty.zip")
        try AllDataExport.assemble(parts: [], skipped: ["food_diary"], to: destination, appVersion: "1")
        let reader = try ZipArchiveReader(url: destination)
        let json = try JSONSerialization.jsonObject(with: reader.data(for: reader.entry(named: "manifest.json")!)) as? [String: Any]
        #expect(json?["format_version"] as? Int == AllDataExport.formatVersion)
        #expect(json?["created_at"] is String)
        #expect(json?["app_version"] as? String == "1")
        #expect((json?["files"] as? [Any])?.isEmpty == true)
    }

    @Test func rejectsDuplicateOrUnsafeEntryNames() throws {
        let directory = try tempDirectory()
        defer { try? FileManager.default.removeItem(at: directory) }
        let file = directory.appendingPathComponent("a.json")
        try Data("{}".utf8).write(to: file)
        let destination = directory.appendingPathComponent("bad.zip")
        let part = AllDataExport.Part(section: "food_diary", format: "x", name: "a.json", fileURL: file, counts: [:])
        #expect(throws: ZipArchiveError.self) {
            try AllDataExport.assemble(parts: [part, part], skipped: [], to: destination, appVersion: "1")
        }
        var escaping = part
        escaping.name = "../a.json"
        #expect(throws: ZipArchiveError.self) {
            try AllDataExport.assemble(parts: [escaping], skipped: [], to: destination, appVersion: "1")
        }
        var manifestClash = part
        manifestClash.name = AllDataExport.manifestEntry
        #expect(throws: ZipArchiveError.self) {
            try AllDataExport.assemble(parts: [manifestClash], skipped: [], to: destination, appVersion: "1")
        }
    }
}
