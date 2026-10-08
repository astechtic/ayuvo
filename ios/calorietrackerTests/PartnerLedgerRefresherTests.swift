import Foundation
import Testing
@testable import calorietracker

/// `ledger_refresh` / `ledger_prune` through the paged refresher with fake sources (docs/partner-sync.md §10).
struct PartnerLedgerRefresherTests {
    typealias S = PartnerTestSupport

    static let tz = TimeZone(identifier: "UTC")!

    static func days(back n: Int) -> String {
        PartnerDay.adding(-n, to: PartnerDay.string(S.now, timeZone: tz), timeZone: tz)
    }

    static func sample(_ id: String, daysBack: Int) -> PartnerSourceRecord {
        let day = days(back: daysBack)
        let start = Int(S.nowMs) - daysBack * 86_400_000
        return PartnerSourceRecord(type: "sample", recordID: id, category: "vitals", day: day,
                                   data: .obj(["type_id": .str("heart_rate"), "start_ms": .int(start), "end_ms": .int(start), "unit": .str("bpm"), "value": .int(60)]))
    }

    @Test func fullRunThenWindowsWithTombstonesInPages() async throws {
        let (db, dir) = try await S.openDB()
        defer { try? FileManager.default.removeItem(at: dir) }
        let metrics = PartnerFakeSource(type: "metric_day", (0..<60).map { S.metricDay(Self.days(back: $0), steps: 1000 + $0) })
        let foods = PartnerFakeSource(type: "food_entry", (0..<25).map { S.food(String(format: "f%03d", $0), day: Self.days(back: $0 * 10)) })
        let samples = PartnerFakeSource(type: "sample", (0..<12).map { Self.sample(String(format: "s%02d", $0), daysBack: $0) })
        let registry = PartnerSourceRegistry([metrics, foods, samples])
        let refresher = PartnerLedgerRefresher(store: db, registry: registry, pageSize: 7, now: { S.now }, timeZone: Self.tz)

        // First run: everything in full (intraday types limited to today − 7).
        let first = try await refresher.refresh()
        #expect(first.fullRun)
        #expect(first.maxPage <= 7)
        #expect(metrics.pagesSeen.allSatisfy { $0 <= 7 })
        #expect(first.changed == 60 + 25 + 8)
        #expect(first.tombstoned == 0)
        #expect(try await db.meta(PartnerLedgerRefresher.fullDoneKey) == "1")
        #expect(try await db.ledgerCount() == 93)

        // Nothing changed: no new revs.
        let again = try await refresher.refresh()
        #expect(!again.fullRun)
        #expect(again.changed == 0 && again.tombstoned == 0)
        #expect(again.rev == first.rev)

        // Build the expected result with the reference over the same inputs, then change the sources.
        let before = try await db.ledgerRows(types: ["food_entry", "metric_day", "sample"])
        metrics.remove("steps:\(Self.days(back: 45))")      // outside the 30-day window: no tombstone
        metrics.remove("steps:\(Self.days(back: 10))")      // inside: tombstone
        metrics.put(S.metricDay(Self.days(back: 3), steps: 99_999))  // changed: new rev
        foods.remove("f020")                                 // full scope: tombstone at any age
        foods.put(S.food("f999", day: Self.days(back: 1)))  // new
        let windowFrom = Self.days(back: 30), intradayFrom = Self.days(back: 7)
        var current: [RJ] = []
        for r in try await allRecords(metrics, scope: .dayFrom(windowFrom)) + allRecords(foods, scope: .full)
            + allRecords(samples, scope: .dayFrom(intradayFrom)) { current.append(r.ledgerCurrent) }
        let scopes: [RJ] = [
            .obj(["type": .str("metric_day"), "day_from": .str(windowFrom)]), .obj(["type": .str("food_entry")]),
            .obj(["type": .str("sample"), "day_from": .str(intradayFrom)]),
        ]
        let expected = PartnerRef.ledgerRefresh(ledger: before.map(\.rj), rev: Int(first.rev), current: current, scopes: scopes)

        let third = try await refresher.refresh()
        #expect(third.changed == expected["changed"].pyInt)
        #expect(third.tombstoned == expected["tombstoned"].pyInt)
        #expect(third.changed == 2 && third.tombstoned == 2)
        #expect(third.rev == Int64(expected["rev"].pyInt ?? 0))
        let after = try await db.ledgerRows(types: ["food_entry", "metric_day", "sample"])
        let expectedRows = (expected["ledger"].array ?? []).compactMap(PartnerLedgerRow.init(rj:))
        #expect(after == expectedRows)
        #expect(try await db.ledgerRow(type: "metric_day", recordID: "steps:\(Self.days(back: 45))")?.deleted == false)
        #expect(try await db.ledgerRow(type: "metric_day", recordID: "steps:\(Self.days(back: 10))")?.deleted == true)
        #expect(try await db.ledgerRow(type: "food_entry", recordID: "f020")?.deleted == true)
        await db.close()
    }

    private func allRecords(_ s: PartnerFakeSource, scope: PartnerSourceScope) async throws -> [PartnerSourceRecord] {
        var out: [PartnerSourceRecord] = []
        try await s.scan(scope: scope, pageSize: 1000) { out += $0 }
        return out
    }

    @Test func unreadableSourceIsSkippedWithoutTombstones() async throws {
        let (db, dir) = try await S.openDB()
        defer { try? FileManager.default.removeItem(at: dir) }
        let foods = PartnerFakeSource(type: "food_entry", [S.food("f1"), S.food("f2")])
        let metrics = PartnerFakeSource(type: "metric_day", [S.metricDay(Self.days(back: 1), steps: 5)])
        let registry = PartnerSourceRegistry([foods, metrics])
        let refresher = PartnerLedgerRefresher(store: db, registry: registry, now: { S.now }, timeZone: Self.tz)
        _ = try await refresher.refresh()
        foods.unavailable = true
        metrics.unavailable = true
        let r = try await refresher.refresh()
        #expect(r.skipped == ["metric_day", "food_entry"])
        #expect(r.tombstoned == 0)
        #expect(try await db.ledgerRow(type: "food_entry", recordID: "f1")?.deleted == false)
        await db.close()
    }

    @Test func firstRunWithAFailedWindowSourceStaysAFullRun() async throws {
        let (db, dir) = try await S.openDB()
        defer { try? FileManager.default.removeItem(at: dir) }
        let metrics = PartnerFakeSource(type: "metric_day", [S.metricDay(Self.days(back: 90), steps: 5)])
        metrics.unavailable = true
        let refresher = PartnerLedgerRefresher(store: db, registry: PartnerSourceRegistry([metrics]), now: { S.now }, timeZone: Self.tz)
        _ = try await refresher.refresh()
        #expect(try await db.meta(PartnerLedgerRefresher.fullDoneKey) == nil)
        metrics.unavailable = false
        let r = try await refresher.refresh()
        #expect(r.fullRun)
        #expect(r.changed == 1)   // the 90-day-old row is picked up by the full run
        await db.close()
    }

    @Test func intradayLedgerRowsArePrunedWithoutARev() async throws {
        let (db, dir) = try await S.openDB()
        defer { try? FileManager.default.removeItem(at: dir) }
        let old = PartnerLedgerRow(type: "sample", recordID: "old", category: "vitals", day: Self.days(back: 20), contentHash: "x", rev: 1, deleted: false)
        try await db.upsertLedgerRows([old])
        try await db.setOutboundRev(1)
        let r = try await PartnerLedgerRefresher(store: db, registry: PartnerSourceRegistry([PartnerFakeSource(type: "sample")]),
                                                 now: { S.now }, timeZone: Self.tz).refresh()
        #expect(r.pruned == 1)
        #expect(try await db.ledgerRow(type: "sample", recordID: "old") == nil)
        #expect(try await db.outboundRev() == 1)
        await db.close()
    }
}
