import Foundation
import HealthKit
import Testing
@testable import calorietracker

struct HealthSyncEngineTests {
    private typealias F = HealthTestFixtures

    private func page(_ rows: [HealthSampleRow], deleted: [String] = [], anchor: String) -> HealthKitPage {
        HealthKitPage(rows: rows, deletedIDs: deleted, sources: [], anchor: Data(anchor.utf8))
    }

    private func engine(_ reader: FakeHealthKitReader, _ db: HealthDatabase, types: [HealthMetricType], concurrency: Int = 3) -> HealthSyncEngine {
        HealthSyncEngine(
            reader: reader,
            database: db,
            types: types,
            calendar: F.calendar,
            now: { HealthTestFixtures.date(2026, 9, 14, 12) },
            configuration: .init(pageLimit: 2, bootstrapLimit: 100, bootstrapDays: 7, maxConcurrentTypes: concurrency),
            ownBundleID: F.bundleID
        )
    }

    @Test func fullPassTerminatesStoresCursorAndBuildsRollups() async throws {
        let db = try await HealthDatabase.inMemory()
        let day = F.date(2026, 9, 10, 9)
        let reader = FakeHealthKitReader(scripts: [
            "steps": .init(pages: [
                page([F.row(id: "a", type: F.steps, start: day, value: 100), F.row(id: "b", type: F.steps, start: day.addingTimeInterval(60), value: 50)], anchor: "1"),
                page([F.row(id: "c", type: F.steps, start: day.addingTimeInterval(120), value: 25)], anchor: "2"),
            ]),
        ])
        let outcome = await engine(reader, db, types: [F.steps]).sync(trigger: .manual)
        #expect(outcome == .synced(types: 1, rows: 3))
        let state = try await db.syncState(type: "steps")
        #expect(state?.cursor == FakeHealthKitReader.terminalAnchor.base64EncodedString())
        #expect(state?.backfillDone == 1)
        #expect(state?.status == "idle")
        #expect(state?.lastSyncMs != nil)
        #expect(try await db.sampleCount(type: "steps") == 3)
        let rollup = try await db.dailyRollups(type: "steps", fromDay: "2026-09-10", toDay: "2026-09-10").first
        #expect(rollup?.sum == 175)
        #expect((reader.anchorsSeen["steps"]?.first ?? nil) == nil, "first anchored page starts from nil")
        // The engine stops at the first empty page: the last request carries the previous page's
        // anchor ("2"); the terminal anchor that page returned is what got stored as the cursor.
        #expect((reader.anchorsSeen["steps"]?.last ?? nil) == Data("2".utf8))
        #expect(reader.anchorsSeen["steps"]?.count == 3, "nil, \"1\", \"2\" — one request per page plus the terminating empty page")
    }

    @Test func bootstrapRowsSurviveAFailingAnchoredLoopWithoutStoringACursor() async throws {
        let db = try await HealthDatabase.inMemory()
        let day = F.date(2026, 9, 12, 9)
        var script = FakeHealthKitReader.Script(recent: [F.row(id: "r", type: F.steps, start: day, value: 10)])
        script.error = NSError(domain: "test", code: 7)
        script.failAnchoredOnly = true
        let reader = FakeHealthKitReader(scripts: ["steps": script])
        let outcome = await engine(reader, db, types: [F.steps]).sync(trigger: .appOpen)
        if case .failed = outcome {} else {
            Issue.record("expected .failed, got \(outcome)")
        }
        #expect(try await db.sampleCount(type: "steps") == 1)
        let state = try await db.syncState(type: "steps")
        #expect(state?.cursor == nil)
        #expect(state?.status == "error:7")
        #expect(state?.lastSyncMs == nil, "failures never stamp last_sync_ms")
        #expect(reader.recentCalls == ["steps"])
        #expect(try await db.rollupCount(type: "steps") == 1, "bootstrap rows get rollups immediately")
    }

    @Test func incrementalPassAppliesDeletionsAndRebuildsTouchedDays() async throws {
        let db = try await HealthDatabase.inMemory()
        let day = F.date(2026, 9, 10, 9)
        try await db.upsertSamples([F.row(id: "a", type: F.steps, start: day, value: 100), F.row(id: "b", type: F.steps, start: day, value: 50)])
        var state = HealthSyncStateRow(typeID: "steps")
        state.cursor = Data("old".utf8).base64EncodedString()
        state.backfillDone = 1
        try await db.setSyncState(state)
        try await db.rebuildRollups(type: F.steps, days: ["2026-09-10"], tz: F.calendar.timeZone.identifier, calendar: F.calendar, ownBundleID: F.bundleID)
        let reader = FakeHealthKitReader(scripts: [
            "steps": .init(pages: [page([], deleted: ["a"], anchor: "new")]),
        ])
        _ = await engine(reader, db, types: [F.steps]).sync(trigger: .manual)
        #expect((reader.anchorsSeen["steps"]?.first ?? nil) == Data("old".utf8), "incremental pass resumes from the stored anchor")
        #expect(reader.recentCalls.isEmpty, "no bootstrap once a cursor exists")
        #expect(try await db.sample(id: "a")?.deleted == 1)
        #expect(try await db.dailyRollups(type: "steps", fromDay: "2026-09-10", toDay: "2026-09-10").first?.sum == 50)
    }

    @Test func cancellationKeepsRowsAndCursorConsistent() async throws {
        let db = try await HealthDatabase.inMemory()
        let day = F.date(2026, 9, 10, 9)
        var pages: [HealthKitPage] = []
        for index in 0..<20 {
            pages.append(page([F.row(id: "p\(index)", type: F.steps, start: day.addingTimeInterval(Double(index) * 60), value: 1)], anchor: "\(index)"))
        }
        let reader = FakeHealthKitReader(scripts: ["steps": .init(pages: pages)])
        reader.pageDelayNanoseconds = 5_000_000
        let engine = engine(reader, db, types: [F.steps])
        let task = Task { await engine.sync(trigger: .manual) }
        try await Task.sleep(nanoseconds: 30_000_000)
        task.cancel()
        let outcome = await task.value
        #expect(outcome == .cancelled)
        let rows = try await db.sampleCount(type: "steps")
        let state = try await db.syncState(type: "steps")
        #expect(rows < 20)
        if rows > 0 {
            let expectedAnchor = Data("\(rows - 1)".utf8).base64EncodedString()
            #expect(state?.cursor == expectedAnchor, "cursor always matches the last committed page")
        } else {
            #expect(state?.cursor == nil)
        }
        #expect(state?.backfillDone == 0)
    }

    @Test func tierOrderIsRespectedAndConcurrencyIsBounded() async throws {
        let db = try await HealthDatabase.inMemory()
        var scripts: [String: FakeHealthKitReader.Script] = [:]
        let types = ["heart_rate", "vo2_max", "steps", "weight", "distance_cycling", "blood_oxygen"].map { HealthMetricRegistry.type(id: $0)! }
        for type in types {
            scripts[type.id] = .init(pages: [page([F.row(type: type, start: F.date(2026, 9, 10), value: 1)], anchor: "a")])
        }
        let reader = FakeHealthKitReader(scripts: scripts)
        reader.pageDelayNanoseconds = 2_000_000
        let outcome = await engine(reader, db, types: types, concurrency: 2).sync(trigger: .manual)
        #expect(outcome == .synced(types: 6, rows: 6))
        #expect(reader.maxInFlight <= 2)
        let order = reader.startOrder
        let t3Start = order.firstIndex(of: "heart_rate")!
        let cyclingStart = order.firstIndex(of: "distance_cycling")!
        for t12 in ["vo2_max", "steps", "weight", "blood_oxygen"] {
            #expect(order.firstIndex(of: t12)! < min(t3Start, cyclingStart), "\(t12) must start before tier-3 types")
        }
    }

    @Test func errorsAreIsolatedPerType() async throws {
        let db = try await HealthDatabase.inMemory()
        let reader = FakeHealthKitReader(scripts: [
            "steps": .init(pages: [page([F.row(type: F.steps, start: F.date(2026, 9, 10), value: 1)], anchor: "a")]),
            "weight": .init(error: NSError(domain: "test", code: 42)),
        ])
        let outcome = await engine(reader, db, types: [F.steps, F.weight]).sync(trigger: .manual)
        #expect(outcome == .synced(types: 1, rows: 1), "only the type that finished counts as synced")
        #expect(try await db.syncState(type: "weight")?.status == "error:42")
        #expect(try await db.syncState(type: "weight")?.lastError != nil)
        #expect(try await db.syncState(type: "steps")?.status == "idle")
    }

    @Test func inaccessibleHealthDatabaseLocksQuietly() async throws {
        let db = try await HealthDatabase.inMemory()
        let locked = HKError(.errorDatabaseInaccessible)
        let reader = FakeHealthKitReader(scripts: [
            "steps": .init(error: locked),
            "weight": .init(pages: [page([F.row(type: F.weight, start: F.date(2026, 9, 10), value: 80)], anchor: "a")]),
        ])
        let outcome = await engine(reader, db, types: [F.steps, F.weight], concurrency: 1).sync(trigger: .manual)
        #expect(outcome == .locked)
        #expect(try await db.syncState(type: "steps")?.status == "locked")
        #expect(try await db.sampleCount(type: "weight") == 0, "the run stops after the lock")
    }

    @Test func widenedLimitedHistoryBoundaryResetsTheCursor() async throws {
        let db = try await HealthDatabase.inMemory()
        var state = HealthSyncStateRow(typeID: "steps")
        state.cursor = Data("old".utf8).base64EncodedString()
        state.backfillDone = 1
        state.earliestAuthorizedMs = F.ms(F.date(2026, 8, 15))
        try await db.setSyncState(state)
        let reader = FakeHealthKitReader(
            scripts: ["steps": .init(pages: [page([F.row(type: F.steps, start: F.date(2026, 9, 10), value: 1)], anchor: "a")])],
            limits: ["steps": F.date(2026, 1, 1)]
        )
        _ = await engine(reader, db, types: [F.steps]).sync(trigger: .manual)
        #expect((reader.anchorsSeen["steps"]?.first ?? nil) == nil, "an earlier boundary means more history: start over")
        let after = try await db.syncState(type: "steps")
        #expect(after?.earliestAuthorizedMs == F.ms(F.date(2026, 1, 1)))
        #expect(after?.status == "limited")
    }

    @Test func unchangedLimitedBoundaryKeepsTheCursor() async throws {
        let db = try await HealthDatabase.inMemory()
        var state = HealthSyncStateRow(typeID: "steps")
        state.cursor = Data("old".utf8).base64EncodedString()
        state.backfillDone = 1
        state.earliestAuthorizedMs = F.ms(F.date(2026, 8, 15))
        try await db.setSyncState(state)
        let reader = FakeHealthKitReader(scripts: ["steps": .init()], limits: ["steps": F.date(2026, 8, 15)])
        _ = await engine(reader, db, types: [F.steps]).sync(trigger: .manual)
        #expect((reader.anchorsSeen["steps"]?.first ?? nil) == Data("old".utf8))
    }

    @Test func progressReportsTypesAndRows() async throws {
        let db = try await HealthDatabase.inMemory()
        let reader = FakeHealthKitReader(scripts: [
            "steps": .init(pages: [page([F.row(type: F.steps, start: F.date(2026, 9, 10), value: 1)], anchor: "a")]),
        ])
        let recorder = ProgressRecorder()
        _ = await engine(reader, db, types: [F.steps]).sync(trigger: .manual) { recorder.record($0) }
        let last = recorder.last
        #expect(last?.typesTotal == 1)
        #expect(last?.typesDone == 1)
        #expect(last?.rowsCommitted == 1)
    }
}

final class ProgressRecorder: @unchecked Sendable {
    private let lock = NSLock()
    private var updates: [HealthSyncProgress] = []

    func record(_ progress: HealthSyncProgress) {
        lock.lock()
        updates.append(progress)
        lock.unlock()
    }

    var last: HealthSyncProgress? {
        lock.lock()
        defer { lock.unlock() }
        return updates.last
    }
}
