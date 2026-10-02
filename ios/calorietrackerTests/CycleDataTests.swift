import Foundation
import Testing
@testable import calorietracker

/// Cycle tracking storage (docs/cycle-tracking.md §2, §4, §7): schema parity with `shared/cycle/schema.sql`,
/// repository rules, platform periods from `health_samples`, and the Export All Data section with its fixture.
struct CycleDataTests {
    static let nowMs: Int64 = 1_790_000_000_000

    static var fixtureDirectory: URL {
        HealthTestFixtures.repoRootURL.appendingPathComponent("shared/cycle/fixtures/cycle-sample")
    }

    static func fixtureEntries() throws -> [String: Data] {
        var out: [String: Data] = [:]
        for name in ["manifest.json", "periods.ndjson", "day_logs.ndjson", "settings.json"] {
            out[CycleArchive.directory + name] = try Data(contentsOf: fixtureDirectory.appendingPathComponent(name))
        }
        return out
    }

    // MARK: Schema

    @Test func embeddedSchemaMatchesSharedFile() throws {
        let sql = try String(contentsOf: HealthTestFixtures.repoRootURL.appendingPathComponent("shared/cycle/schema.sql"), encoding: .utf8)
        #expect(CycleSchema.parseStatements(sql) == CycleSchema.statements)
    }

    @Test func freshDatabaseHasEveryTableAndVersion() async throws {
        let db = try await CycleDatabase.inMemory()
        #expect(try await db.tableNames() == CycleSchema.tableNames.sorted())
        #expect(try await db.indexNames() == CycleSchema.indexNames.sorted())
        #expect(try await db.userVersion() == CycleSchema.schemaVersion)
        #expect(try await db.meta("schema_version") == "\(CycleSchema.schemaVersion)")
        let columns = try await db.columns(of: "cycle_day_logs").map(\.name)
        #expect(columns.contains("symptoms_json") && columns.contains("note"))
    }

    @Test func fileDatabaseReopensWithoutReapplyingSchema() async throws {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("cycle-db-\(UUID().uuidString)", isDirectory: true)
        defer { try? FileManager.default.removeItem(at: dir) }
        let url = dir.appendingPathComponent("cycle.sqlite")
        let first = try await CycleDatabase.open(url: url)
        let repo = CycleRepository(database: first)
        try await repo.savePeriod(id: nil, start: "2026-09-01", end: "2026-09-05", today: "2026-10-02", nowMs: Self.nowMs)
        await first.close()
        let second = try await CycleDatabase.open(url: url)
        #expect(try await second.periods().count == 1)
        #expect(try await second.journalMode()?.lowercased() == "wal")
        let excluded = try dir.resourceValues(forKeys: [.isExcludedFromBackupKey]).isExcludedFromBackup
        #expect(excluded == true)
        await second.close()
    }

    // MARK: Repository

    @Test func periodSaveEditDeleteAndValidation() async throws {
        let repo = CycleRepository(database: try await CycleDatabase.inMemory())
        let saved = try await repo.savePeriod(id: nil, start: "2026-09-01", end: "2026-09-05", today: "2026-10-02", nowMs: Self.nowMs)
        #expect(saved.id.hasPrefix("local:") && saved.syncState == CycleSyncState.pending)
        await #expect(throws: CycleStoreError.validation(["overlap"])) {
            try await repo.savePeriod(id: nil, start: "2026-09-04", end: "2026-09-08", today: "2026-10-02", nowMs: Self.nowMs)
        }
        await #expect(throws: CycleStoreError.validation(["future_start"])) {
            try await repo.savePeriod(id: nil, start: "2026-10-05", end: nil, today: "2026-10-02", nowMs: Self.nowMs)
        }
        let edited = try await repo.savePeriod(id: saved.id, start: "2026-08-31", end: "2026-09-04", today: "2026-10-02", nowMs: Self.nowMs + 1)
        #expect(edited.startDay == "2026-08-31" && edited.createdMs == Self.nowMs && edited.updatedMs == Self.nowMs + 1)
        try await repo.deletePeriod(id: saved.id, nowMs: Self.nowMs + 2)
        #expect(try await repo.periods().isEmpty)
        let tomb = try #require(try await repo.database.period(id: saved.id))
        #expect(tomb.deleted && tomb.syncState == CycleSyncState.pending)
        #expect(try await repo.pendingSync().periods.map(\.id) == [saved.id])
    }

    @Test func periodDayToggleUsesEngineOperations() async throws {
        let repo = CycleRepository(database: try await CycleDatabase.inMemory())
        try await repo.setPeriodDay("2026-10-02", on: true, today: "2026-10-02", nowMs: Self.nowMs)
        var periods = try await repo.periods()
        #expect(periods.count == 1 && periods[0].startDay == "2026-10-02" && periods[0].endDay == nil)
        try await repo.setPeriodDay("2026-10-01", on: true, today: "2026-10-02", nowMs: Self.nowMs)
        periods = try await repo.periods()
        #expect(periods.count == 1 && periods[0].startDay == "2026-10-01")
        try await repo.setPeriodDay("2026-10-01", on: false, today: "2026-10-02", nowMs: Self.nowMs)
        periods = try await repo.periods()
        #expect(periods.map(\.startDay) == ["2026-10-02"])
    }

    @Test func dayLogUpsertTombstoneAndFlowMarksPeriod() async throws {
        let repo = CycleRepository(database: try await CycleDatabase.inMemory())
        var log = CycleDayLogRecord(day: "2026-09-20", flow: "spotting", pain: 12, symptoms: ["cramps"], note: "  sore  ", updatedMs: 0)
        try await repo.saveDayLog(log, today: "2026-10-02", nowMs: Self.nowMs)
        var stored = try #require(try await repo.dayLog(day: "2026-09-20"))
        #expect(stored.pain == 10 && stored.note == "sore" && stored.updatedMs == Self.nowMs)
        #expect(try await repo.periods().isEmpty, "spotting is not a period day")
        log.flow = "heavy"
        try await repo.saveDayLog(log, today: "2026-10-02", nowMs: Self.nowMs + 1)
        #expect(try await repo.periods().map(\.startDay) == ["2026-09-20"])
        // A day inside a Health period is left to Health.
        let platform = [CyclePeriodInput(id: "healthkit:x", start: "2026-08-01", end: "2026-08-05", source: "healthkit")]
        try await repo.saveDayLog(CycleDayLogRecord(day: "2026-08-02", flow: "medium", updatedMs: 0), today: "2026-10-02",
                                  nowMs: Self.nowMs, platformPeriods: platform)
        #expect(try await repo.periods().count == 1)
        try await repo.deleteDayLog(day: "2026-09-20", nowMs: Self.nowMs + 2)
        #expect(try await repo.dayLog(day: "2026-09-20") == nil)
        stored = try #require(try await repo.database.dayLog(day: "2026-09-20"))
        #expect(stored.deleted && stored.note == nil && stored.symptoms.isEmpty)
        await #expect(throws: CycleStoreError.validation(["future"])) {
            try await repo.saveDayLog(CycleDayLogRecord(day: "2026-10-03", pain: 1, updatedMs: 0), today: "2026-10-02", nowMs: Self.nowMs)
        }
    }

    @Test func settingsClampAndStateFeedsEngine() async throws {
        let repo = CycleRepository(database: try await CycleDatabase.inMemory())
        #expect(try await repo.settings().setupDone == false)
        var s = CycleSettingsRecord(setupDone: true, cycleLength: 99, periodLength: 5)
        var options = s.options
        options.daily = true
        options.time = "20:30"
        s.options = options
        try await repo.saveSettings(s, nowMs: Self.nowMs)
        let loaded = try await repo.settings()
        #expect(loaded.cycleLength == 60 && loaded.options.daily && loaded.options.time == "20:30" && loaded.options.showFertility)
        try await repo.savePeriod(id: nil, start: "2026-09-01", end: "2026-09-05", today: "2026-10-02", nowMs: Self.nowMs)
        let state = try await repo.state(today: "2026-10-02")
        let snap = CycleEngine.snapshot(state, CycleConfig.shared)
        #expect(snap.prediction.basis == "default" && snap.prediction.cycleLength == 60)
        #expect(snap.reminders.contains { $0.kind == "daily_log" })
        try await repo.deleteAll()
        let remaining = try await repo.periods()
        let settingsAfter = try await repo.database.settings()
        #expect(remaining.isEmpty && settingsAfter == nil)
    }

    // MARK: Platform periods

    static func flowRow(_ id: String, _ day: String, value: Int = 3, source: String = "com.apple.health", extra: String? = nil) -> HealthSampleRow {
        var row = HealthSampleRow(id: id, typeID: "menstrual_flow", startMs: 0, endMs: 0, startOffsetS: 0, endOffsetS: 0, localDay: day,
                                  unit: "", sourceID: source, updatedMs: 0)
        row.categoryValue = value
        row.extraJSON = extra
        return row
    }

    @Test func platformFlowDaysBecomeRunsAndOwnSamplesAreSkipped() {
        let rows = [
            Self.flowRow("a", "2026-09-01"), Self.flowRow("b", "2026-09-02"), Self.flowRow("c", "2026-09-03", value: 5),
            Self.flowRow("d", "2026-09-04"), Self.flowRow("e", "2026-09-05", extra: #"{"HKMenstrualCycleStart":1}"#),
            Self.flowRow("f", "2026-09-06"), Self.flowRow("own", "2026-09-20", source: "com.ayuvo.health"),
            Self.flowRow("tagged", "2026-09-25", extra: #"{"ayuvo_cycle":1}"#),
        ]
        let periods = CyclePlatformPeriods.periods(flowRows: rows, periodRows: [], ownSourceID: "com.ayuvo.health")
        #expect(periods.map { "\($0.start)…\($0.end ?? "")" } == ["2026-09-01…2026-09-02", "2026-09-04…2026-09-04", "2026-09-05…2026-09-06"])
        #expect(periods.allSatisfy { $0.source == "healthkit" } && periods[0].id == "healthkit:a")

        var span = HealthSampleRow(id: "hc1", typeID: "menstruation_period", startMs: 1_788_566_400_000, endMs: 1_788_566_400_000 + 5 * 86_400_000,
                                   startOffsetS: 0, endOffsetS: 0, localDay: "2026-09-05", unit: "days", sourceID: "com.other", updatedMs: 0)
        span.clientRecordID = nil
        var own = span
        own.id = "hc2"
        own.clientRecordID = "ayuvo:cycle:local:x"
        let spans = CyclePlatformPeriods.periods(flowRows: [], periodRows: [span, own], ownSourceID: "com.ayuvo.health")
        #expect(spans.count == 1 && spans[0].start == "2026-09-05" && spans[0].end == "2026-09-09" && spans[0].source == "health_connect")
    }

    // MARK: Archive

    @Test func fixtureImportsWithCountsAndExpectedSnapshot() async throws {
        let db = try await CycleDatabase.inMemory()
        let entries = try Self.fixtureEntries()
        let result = try await CycleArchive.importEntries(entries, into: db)
        let manifest = try VitalsJSON.parse(try #require(entries[CycleArchive.manifestEntry]))
        #expect(result.periodsAdded == Int(manifest["counts"]["periods"].double ?? -1))
        #expect(result.dayLogsAdded == Int(manifest["counts"]["day_logs"].double ?? -1))
        #expect(result.settingsApplied && result.invalidRows == 0)
        let repo = CycleRepository(database: db)
        let expected = manifest["expected"]
        let logs = try await repo.dayLogs()
        #expect(logs.count == Int(expected["live_day_logs"].double ?? -1))
        #expect(logs.first?.note == "Sample note" && logs.first?.symptoms == ["cramps", "fatigue"])
        let snap = CycleEngine.snapshot(try await repo.state(today: expected["today"].string ?? ""), CycleConfig.shared)
        #expect(snap.prediction.nextStart == expected["next_start"].string)
        #expect(snap.prediction.basis == expected["basis"].string)
        #expect(snap.prediction.cycleLength == Int(expected["cycle_length"].double ?? -1))
        #expect(snap.today.phase == expected["today_phase"].string)
        #expect(try await db.periods(includeDeleted: true).allSatisfy { $0.syncState == CycleSyncState.pending })
    }

    @Test func importMergesByNewerUpdateAndRoundTrips() async throws {
        let source = try await CycleDatabase.inMemory()
        let entries = try Self.fixtureEntries()
        _ = try await CycleArchive.importEntries(entries, into: source)
        let again = try await CycleArchive.importEntries(entries, into: source)
        #expect(again.periodsAdded == 0 && again.periodsSkipped == 4 && again.dayLogsSkipped == 6 && !again.settingsApplied)

        // A newer local edit survives an older incoming copy; the local platform ids are kept on replace.
        var p = try #require(try await source.periods().first)
        p.platformIDsJSON = #"{"healthkit":["u1"]}"#
        p.updatedMs = 1_900_000_000_000
        p.endDay = "2026-06-15"
        try await source.upsertPeriod(p)
        _ = try await CycleArchive.importEntries(entries, into: source)
        #expect(try await source.period(id: p.id)?.endDay == "2026-06-15")

        let export = try await CycleArchive.export(from: source)
        #expect(export.entries.map(\.name) == [CycleArchive.manifestEntry, CycleArchive.periodsEntry, CycleArchive.dayLogsEntry,
                                               CycleArchive.settingsEntry])
        #expect(export.counts == ["periods": 4, "day_logs": 6] && !export.isEmpty)
        let periodsText = String(decoding: try #require(export.entries.first { $0.name == CycleArchive.periodsEntry }?.data), as: UTF8.self)
        #expect(!periodsText.contains("platform_ids") && !periodsText.contains("sync_state"))

        let target = try await CycleDatabase.inMemory()
        var map: [String: Data] = [:]
        for e in export.entries { map[e.name] = e.data }
        let result = try await CycleArchive.importEntries(map, into: target)
        #expect(result.periodsAdded == 4 && result.dayLogsAdded == 6 && result.settingsApplied)
        let a = try await source.periods(includeDeleted: true).map { [$0.id, $0.startDay, $0.endDay ?? "", "\($0.updatedMs)", "\($0.deleted)"] }
        let b = try await target.periods(includeDeleted: true).map { [$0.id, $0.startDay, $0.endDay ?? "", "\($0.updatedMs)", "\($0.deleted)"] }
        #expect(a == b)
        let la = try await source.dayLogs(includeDeleted: true).map(CycleArchive.dayLogRow).map { VitalsJSON.encode($0) }
        let lb = try await target.dayLogs(includeDeleted: true).map(CycleArchive.dayLogRow).map { VitalsJSON.encode($0) }
        #expect(la == lb)
        let targetSettings = try await target.settings()
        let sourceSettings = try await source.settings()
        #expect(targetSettings?.settingsJSON == sourceSettings?.settingsJSON)
    }

    @Test func newerFormatIsRejected() async throws {
        let db = try await CycleDatabase.inMemory()
        var entries = try Self.fixtureEntries()
        entries[CycleArchive.manifestEntry] = Data(#"{"format":"ayuvo-cycle","format_version":2}"#.utf8)
        await #expect(throws: CycleArchive.ArchiveError.newerVersion) { try await CycleArchive.importEntries(entries, into: db) }
        entries[CycleArchive.manifestEntry] = Data(#"{"format":"other"}"#.utf8)
        await #expect(throws: CycleArchive.ArchiveError.notCycle) { try await CycleArchive.importEntries(entries, into: db) }
    }

    @Test func allDataPlanListsEveryCycleEntry() throws {
        let manifest: [String: Any] = [
            "app": "Ayuvo", "format": "ayuvo-all-data", "format_version": 1, "platform": "android", "app_version": "1",
            "created_at": "2026-10-02T00:00:00Z", "skipped": [],
            "files": [["name": "cycle/manifest.json", "section": "cycle", "format": "ayuvo-cycle", "bytes": 1, "counts": ["periods": 4, "day_logs": 6]]],
        ]
        let data = try JSONSerialization.data(withJSONObject: manifest)
        let names: Set<String> = ["manifest.json", "cycle/manifest.json", "cycle/periods.ndjson", "cycle/day_logs.ndjson", "cycle/settings.json"]
        let plan = try AllDataImport.plan(manifestData: data, entryNames: names)
        let item = try #require(plan.items.first { $0.section == .cycle })
        #expect(item.entryNames == ["cycle/day_logs.ndjson", "cycle/manifest.json", "cycle/periods.ndjson", "cycle/settings.json"])
        #expect(item.counts == ["periods": 4, "day_logs": 6])
    }
}
