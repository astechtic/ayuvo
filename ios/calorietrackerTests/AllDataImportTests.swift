import Foundation
import Testing
@testable import calorietracker

struct AllDataImportTests {
    private func manifest(
        platform: String = "ios",
        version: Int = 1,
        format: String = "ayuvo-all-data",
        files: [(String, String)]
    ) -> Data {
        let list = files.map { #"{"name":"\#($0.0)","section":"\#($0.1)","format":"x","bytes":1,"counts":{"rows":2}}"# }
        let json = #"{"app":"Ayuvo","format":"\#(format)","format_version":\#(version),"platform":"\#(platform)","app_version":"1","created_at":"2026-09-22T10:00:00+05:30","files":[\#(list.joined(separator: ","))],"skipped":[]}"#
        return Data(json.utf8)
    }

    private let allFiles: [(String, String)] = [
        ("health-records/r.zip", "health_records"),
        ("food-diary/d.json", "food_diary"),
        ("medications/m.json", "medications"),
        ("app-backup/ayuvo-backup.zip", "app_backup"),
        ("health-data/h.zip", "health_data"),
        ("coach-chats/ayuvo-coach-chats.zip", "coach_chats"),
    ]

    @Test func validSamePlatformPlanRunsInOrderAndLetsTheBackupCoverTheDiary() throws {
        let plan = try AllDataImport.plan(manifestData: manifest(files: allFiles), entryNames: Set(allFiles.map(\.0) + ["manifest.json"]))
        #expect(plan.items.map(\.section) == [.appBackup, .foodDiary, .healthData, .medications, .healthRecords, .coachChats])
        #expect(plan.items.first { $0.section == .appBackup }?.disposition == .importIt)
        let diary = try #require(plan.items.first { $0.section == .foodDiary })
        #expect(diary.disposition == .coveredByAppBackup)
        #expect(!AllDataImport.shouldImport(diary, appBackupRestored: true))
        #expect(AllDataImport.shouldImport(diary, appBackupRestored: false), "a failed backup restore falls back to the diary JSON")
        #expect(plan.items.first { $0.section == .healthData }?.counts == ["rows": 2])
        #expect(plan.isFromThisPlatform)
    }

    @Test func androidExportSkipsSettingsAndImportsTheDiary() throws {
        let plan = try AllDataImport.plan(manifestData: manifest(platform: "android", files: allFiles), entryNames: Set(allFiles.map(\.0)))
        let backup = try #require(plan.items.first { $0.section == .appBackup })
        #expect(backup.disposition == .otherPlatform)
        #expect(!AllDataImport.shouldImport(backup, appBackupRestored: false))
        #expect(plan.items.first { $0.section == .foodDiary }?.disposition == .importIt)
        #expect(!plan.isFromThisPlatform)
    }

    @Test func androidManifestExtrasDecode() throws {
        let json = #"{"app":"Ayuvo","format":"ayuvo-all-data","format_version":1,"created_at":"2026-09-22T04:30:00Z","app_version":"1.0","platform":"android","files":[{"name":"medications/ayuvo-medications.json","section":"medications","format":"ayuvo-medications","description":"Meds","bytes":120,"sha256":"ab","counts":{"medications":2}}],"skipped":["food_diary"],"skipped_reasons":{"food_diary":"empty"}}"#
        let plan = try AllDataImport.plan(manifestData: Data(json.utf8), entryNames: ["medications/ayuvo-medications.json"])
        #expect(plan.items.map(\.section) == [.medications])
        #expect(plan.items[0].counts == ["medications": 2])
    }

    @Test func newerVersionAndForeignFilesAreRejected() {
        #expect(throws: AllDataImport.ImportError.newerVersion) {
            try AllDataImport.plan(manifestData: manifest(version: 2, files: []), entryNames: [])
        }
        #expect(throws: AllDataImport.ImportError.notAnExport) {
            try AllDataImport.plan(manifestData: manifest(format: "ayuvo-records", files: []), entryNames: [])
        }
        #expect(throws: AllDataImport.ImportError.notAnExport) {
            try AllDataImport.plan(manifestData: nil, entryNames: [])
        }
        #expect(throws: AllDataImport.ImportError.notAnExport) {
            try AllDataImport.plan(manifestData: Data("not json".utf8), entryNames: [])
        }
    }

    @Test func unsafeOrMissingEntriesRejectTheFile() {
        #expect(throws: AllDataImport.ImportError.damaged) {
            try AllDataImport.plan(manifestData: manifest(files: [("../evil.json", "food_diary")]), entryNames: ["../evil.json"])
        }
        #expect(throws: AllDataImport.ImportError.damaged) {
            try AllDataImport.plan(manifestData: manifest(files: [("food-diary/d.json", "food_diary")]), entryNames: [])
        }
    }

    @Test func unknownSectionsAreIgnored() throws {
        let plan = try AllDataImport.plan(
            manifestData: manifest(files: [("future/x.bin", "future_thing"), ("food-diary/d.json", "food_diary")]),
            entryNames: ["food-diary/d.json"]
        )
        #expect(plan.items.map(\.section) == [.foodDiary])
        #expect(plan.items[0].disposition == .importIt)
    }

    @Test func roundTripsAnAssembledExportAndExtractsEachPart() throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent("all-data-import-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: directory) }
        let diary = Data("{\"days\":[]}".utf8)
        let meds = Data(repeating: 7, count: 700_000)
        let diaryURL = directory.appendingPathComponent("d.json")
        let medsURL = directory.appendingPathComponent("m.json")
        try diary.write(to: diaryURL)
        try meds.write(to: medsURL)
        let zip = directory.appendingPathComponent("export.zip")
        try AllDataExport.assemble(parts: [
            .init(section: "medications", format: "ayuvo-medications", name: "medications/m.json", fileURL: medsURL, counts: ["medications": 1]),
            .init(section: "food_diary", format: "ayuvo-food-diary", name: "food-diary/d.json", fileURL: diaryURL, counts: ["food_entries": 0]),
        ], skipped: [], to: zip, appVersion: "1")

        let plan = try AllDataImport.plan(url: zip)
        #expect(plan.items.map(\.section) == [.foodDiary, .medications])
        #expect(AllDataImport.countsText(plan.items[1].counts) == "1 medications")

        let reader = try ZipArchiveReader(url: zip)
        let out = directory.appendingPathComponent("m-out.json")
        try AllDataImport.extract(entryNamed: "medications/m.json", from: reader, to: out)
        #expect(try Data(contentsOf: out) == meds)
    }

    @Test func diaryMergeUpdatesMatchesAddsNewAndKeepsEverythingElse() throws {
        let calendar = Calendar.current
        let day = try #require(calendar.date(from: DateComponents(year: 2026, month: 9, day: 20, hour: 9)))
        let kept = FoodEntry(name: "Only on phone", calories: 100, protein: 1, carbs: 1, fat: 1, timestamp: day, source: .manual, mealType: .breakfast)
        let shared = FoodEntry(name: "Oats", calories: 300, protein: 10, carbs: 50, fat: 5, timestamp: day, source: .manual, mealType: .breakfast)
        var edited = shared
        edited.calories = 320
        let added = FoodEntry(name: "Apple", calories: 80, protein: 0, carbs: 20, fat: 0, timestamp: day, source: .manual, mealType: .snack)
        let water = WaterEntry(date: day, milliliters: 250)
        let newWater = WaterEntry(date: day.addingTimeInterval(3600), milliliters: 300)
        let preview = DiaryImportPreview(
            entries: [edited, added],
            startDate: calendar.startOfDay(for: day),
            endDate: calendar.startOfDay(for: day),
            waterEntries: [WaterEntry(date: day, milliliters: 250), newWater],
            includesWater: true
        )

        let food = DiaryImporter.applying(preview, to: [kept, shared], mode: .merge)
        #expect(food.count == 3)
        #expect(food.contains { $0.id == kept.id })
        #expect(food.first { $0.id == shared.id }?.calories == 320)
        #expect(food.contains { $0.name == "Apple" })

        let merged = DiaryImporter.applyingWater(preview, to: [water], mode: .merge)
        #expect(merged.count == 2, "same time + amount is the same drink even with a different id")
        #expect(merged.contains { $0.id == water.id })
        #expect(merged.contains { $0.id == newWater.id })
    }
}
