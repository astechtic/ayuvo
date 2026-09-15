import Foundation
import Testing
@testable import calorietracker

struct HealthDatabaseTests {
    private typealias F = HealthTestFixtures

    @Test func schemaMatchesSharedContract() async throws {
        let url = F.sharedSchemaURL
        guard FileManager.default.fileExists(atPath: url.path) else {
            Issue.record("shared/health/schema.sql is missing — the contract file has not landed yet")
            return
        }
        let fileDDL = try String(contentsOf: url, encoding: .utf8)
        let embedded = try await HealthDatabase.inMemory()
        let fromFile = try await HealthDatabase.inMemory()
        try await fromFile.withConnection { connection in
            for table in HealthSchema.tableNames {
                try connection.exec("DROP TABLE IF EXISTS \(table)")
            }
            try connection.exec(fileDDL)
        }
        #expect(try await embedded.tableNames() == fromFile.tableNames())
        #expect(try await embedded.indexNames() == fromFile.indexNames())
        for table in HealthSchema.tableNames {
            let a = try await embedded.tableInfo(table)
            let b = try await fromFile.tableInfo(table)
            #expect(a == b, "table \(table) differs from shared/health/schema.sql")
        }
    }

    @Test func embeddedSchemaCreatesEveryTableAndIndex() async throws {
        let db = try await HealthDatabase.inMemory()
        #expect(try await db.tableNames() == HealthSchema.tableNames.sorted())
        #expect(try await db.indexNames() == HealthSchema.indexNames.sorted())
        #expect(try await db.metaValue("schema_version") == "\(HealthSchema.schemaVersion)")
        let columns = try await db.tableInfo("health_samples").map(\.name)
        #expect(columns == HealthDatabase.sampleColumns.components(separatedBy: ", "))
    }

    @Test func onDiskDatabaseUsesWALAndBackupExcludedDirectory() async throws {
        let directory = try F.temporaryDirectory()
        let url = directory.appendingPathComponent("Health/health.sqlite")
        let db = try await HealthDatabase.open(url: url)
        #expect(try await db.journalMode()?.lowercased() == "wal")
        #expect(HealthDatabaseLocation.isExcludedFromBackup(url.deletingLastPathComponent()))
        try await db.upsertSamples([F.row(type: F.steps, start: F.date(2026, 9, 1), value: 10)])
        try await db.checkpoint()
        await db.close()
        #expect(FileManager.default.fileExists(atPath: url.path))
        try HealthDatabaseLocation.removeDatabaseFiles(at: url)
        for sidecar in HealthDatabaseLocation.allFileURLs(for: url) {
            #expect(!FileManager.default.fileExists(atPath: sidecar.path), "\(sidecar.lastPathComponent) survived delete")
        }
        try? FileManager.default.removeItem(at: directory)
    }

    @Test func upsertIsIdempotentAndNewerWins() async throws {
        let db = try await HealthDatabase.inMemory()
        var row = F.row(id: "a", type: F.steps, start: F.date(2026, 9, 1), value: 100, updatedMs: 1000)
        let first = try await db.upsertSamples([row])
        #expect(first == HealthDatabase.UpsertResult(inserted: 1, updated: 0, unchanged: 0))
        let again = try await db.upsertSamples([row])
        #expect(again.unchanged == 1)
        row.value = 120
        row.updatedMs = 999
        let older = try await db.upsertSamples([row])
        #expect(older.unchanged == 1)
        #expect(try await db.sample(id: "a")?.value == 100)
        row.updatedMs = 2000
        let newer = try await db.upsertSamples([row])
        #expect(newer.updated == 1)
        #expect(try await db.sample(id: "a")?.value == 120)
        #expect(try await db.sampleCount() == 1)
    }

    @Test func tombstoneIsNeverResurrectedAndSparesImportedRows() async throws {
        let db = try await HealthDatabase.inMemory()
        let platform = F.row(id: "p", type: F.steps, start: F.date(2026, 9, 1), value: 1, updatedMs: 1000)
        let imported = F.row(id: "i", type: F.steps, start: F.date(2026, 9, 1), value: 2, origin: 1, updatedMs: 1000)
        try await db.upsertSamples([platform, imported])
        try await db.tombstone(ids: ["p", "i"], nowMs: 5000)
        #expect(try await db.sample(id: "p")?.deleted == 1)
        #expect(try await db.sample(id: "i")?.deleted == 0)
        var resurrect = platform
        resurrect.updatedMs = 9000
        try await db.upsertSamples([resurrect])
        #expect(try await db.sample(id: "p")?.deleted == 1)
        #expect(try await db.sampleCount(type: "steps") == 1)
    }

    @Test func commitPageStoresRowsAndCursorAtomically() async throws {
        let db = try await HealthDatabase.inMemory()
        var state = HealthSyncStateRow(typeID: "steps")
        state.cursor = "anchor-1"
        state.lastSyncMs = 42
        let page = HealthCommitPage(
            rows: [F.row(id: "r1", type: F.steps, start: F.date(2026, 9, 1), value: 5)],
            deletedIDs: [],
            sources: [HealthSourceRow(id: "com.apple.health", name: "Health", deviceModel: "Watch", deviceType: nil, lastSeenMs: 1)],
            syncState: state
        )
        try await db.commitPage(page, nowMs: 100)
        #expect(try await db.sampleCount() == 1)
        #expect(try await db.syncState(type: "steps")?.cursor == "anchor-1")
        #expect(try await db.allSources().count == 1)
    }

    @Test func keysetPagingIsStableAndComplete() async throws {
        let db = try await HealthDatabase.inMemory()
        var rows: [HealthSampleRow] = []
        let base = F.date(2026, 8, 1)
        for index in 0..<120 {
            // Many rows share the same end_ms so ordering must fall back to id.
            let start = base.addingTimeInterval(Double(index / 4) * 3600)
            rows.append(F.row(id: String(format: "%03d", index), type: F.heartRate, start: start, value: 60))
        }
        try await db.upsertSamples(rows)
        var seen: [String] = []
        var key: (endMs: Int64, id: String)?
        while true {
            let page = try await db.samplesPage(type: "heart_rate", before: key, limit: 50)
            if page.isEmpty { break }
            seen.append(contentsOf: page.map(\.id))
            key = (page.last!.endMs, page.last!.id)
        }
        #expect(seen.count == 120)
        #expect(Set(seen).count == 120)
    }

    @Test func corruptFileIsQuarantinedAndReplaced() async throws {
        let directory = try F.temporaryDirectory()
        let url = directory.appendingPathComponent("Health/health.sqlite")
        try FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
        try Data("definitely not a sqlite file".utf8).write(to: url)
        let (db, quarantined) = try await HealthDatabase.openQuarantiningCorruption(url: url)
        #expect(quarantined != nil)
        #expect(try await db.integrityCheck())
        #expect(try await db.tableNames().contains("health_samples"))
        await db.close()
        try? FileManager.default.removeItem(at: directory)
    }

    @Test func wipeAllDataResetsCursorsButKeepsSchema() async throws {
        let db = try await HealthDatabase.inMemory()
        var state = HealthSyncStateRow(typeID: "steps")
        state.cursor = "x"
        state.backfillDone = 1
        try await db.setSyncState(state)
        try await db.upsertSamples([F.row(type: F.steps, start: F.date(2026, 9, 1), value: 1)])
        try await db.wipeAllData()
        #expect(try await db.sampleCount() == 0)
        let after = try await db.syncState(type: "steps")
        #expect(after?.cursor == nil)
        #expect(after?.backfillDone == 0)
        #expect(try await db.tableNames().count == HealthSchema.tableNames.count)
    }

    @Test func rebuildRollupsMatchesMathAndRemovesEmptyDays() async throws {
        let db = try await HealthDatabase.inMemory()
        let day = F.date(2026, 9, 3, 8)
        let rows = [
            F.row(id: "s1", type: F.steps, start: day, value: 1000),
            F.row(id: "s2", type: F.steps, start: day.addingTimeInterval(3600), value: 500),
        ]
        try await db.upsertSamples(rows)
        let dayKey = rows[0].localDay
        try await db.rebuildRollups(type: F.steps, days: [dayKey], tz: F.calendar.timeZone.identifier, calendar: F.calendar, ownBundleID: F.bundleID)
        let stored = try await db.dailyRollups(type: "steps", fromDay: dayKey, toDay: dayKey).first
        let expected = HealthRollupMath.dailyRollup(rows: rows, type: F.steps, day: dayKey, tz: F.calendar.timeZone.identifier, ownBundleID: F.bundleID, calendar: F.calendar)
        #expect(stored?.sum == expected?.sum)
        #expect(stored?.count == 2)
        try await db.tombstone(ids: ["s1", "s2"], nowMs: 99_999_999_999)
        try await db.rebuildRollups(type: F.steps, days: [dayKey], tz: F.calendar.timeZone.identifier, calendar: F.calendar, ownBundleID: F.bundleID)
        #expect(try await db.rollupCount(type: "steps") == 0)
    }

    @Test func typeSummariesReportCountsRangeAndLatest() async throws {
        let db = try await HealthDatabase.inMemory()
        try await db.upsertSamples([
            F.row(id: "w1", type: F.weight, start: F.date(2026, 9, 1), value: 80),
            F.row(id: "w2", type: F.weight, start: F.date(2026, 9, 5), value: 79),
        ])
        let summary = try await db.typeSummaries().first { $0.typeID == "weight" }
        #expect(summary?.count == 2)
        #expect(summary?.latest?.id == "w2")
        #expect(summary?.firstStartMs == F.ms(F.date(2026, 9, 1)))
    }
}
