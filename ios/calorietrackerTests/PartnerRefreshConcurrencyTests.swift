import Foundation
import Testing
@testable import calorietracker

/// docs/partner-sync.md §10–§11: the window's ledger refresh runs alongside discovery; a session waits for it (bounded)
/// before serving and otherwise serves the ledger as it stands; a refresh finished < 2 min ago is skipped. Also the
/// stale transient status a window killed with the app leaves behind.
@Suite(.serialized)
struct PartnerRefreshConcurrencyTests {
    typealias S = PartnerTestSupport

    final class Clock: @unchecked Sendable {
        private let lock = NSLock()
        private var t = Date(timeIntervalSince1970: 1_791_374_400)
        var now: Date { lock.withLock { t } }
        func advance(_ s: TimeInterval) { lock.withLock { t = t.addingTimeInterval(s) } }
    }

    private func coordinator(_ p: S.Pair, clock: Clock) -> PartnerSyncCoordinator {
        PartnerSyncCoordinator(store: p.a, identity: p.idA, local: PartnerLocalInfo(deviceID: p.idA.deviceID, name: "A"),
                               makeSources: { (PartnerSourceRegistry([]), []) }, now: { S.now }, clock: { clock.now })
    }

    /// One session pair; A serves after waiting (≤ `wait` s) for A's coordinator refresh.
    private func sessions(_ p: S.Pair, _ coord: PartnerSyncCoordinator, registryA: PartnerSourceRegistry,
                          wait: TimeInterval) async -> (PartnerSessionOutcome, PartnerSessionOutcome) {
        let (chA, chB) = PartnerMemoryChannel.pair()
        var opts = PartnerSessionOptions()
        opts.idleTimeout = 3
        opts.ledgerWait = wait
        let sa = PartnerSession(store: p.a, registry: registryA, local: PartnerLocalInfo(deviceID: p.idA.deviceID, name: "A"),
                                peerID: p.idB.deviceID, channel: chA, options: opts, now: { S.now },
                                awaitLedger: { t in await coord.waitForLedger(timeout: t) })
        let sb = PartnerSession(store: p.b, registry: PartnerSourceRegistry([]), local: PartnerLocalInfo(deviceID: p.idB.deviceID, name: "B"),
                                peerID: p.idA.deviceID, channel: chB, options: opts, now: { S.now })
        async let ra = sa.run()
        async let rb = sb.run()
        return await (ra, rb)
    }

    @Test func sessionDuringALongRefreshServesThePreRefreshLedger() async throws {
        let p = try await S.pair(grantsAtoB: ["vitals"])
        let weights = PartnerFakeSource(type: "weight", [S.weight("w1", kg: 70)])
        let registry = PartnerSourceRegistry([weights])
        _ = try await PartnerLedgerRefresher(store: p.a, registry: registry, now: { S.now }).refresh()
        let before = try await p.a.outboundRev()

        let clock = Clock()
        let coord = coordinator(p, clock: clock)
        weights.put(S.weight("w2", kg: 71))
        let gate = PartnerLatch()
        weights.gate = gate
        #expect(await coord.startLedgerRefresh(registry: registry))
        #expect(await coord.isRefreshingLedger)

        let (ra, rb) = await sessions(p, coord, registryA: registry, wait: 0.3)
        #expect(ra.ok && rb.ok, "\(ra) \(rb)")
        #expect(await coord.isRefreshingLedger, "the refresh is still running")
        #expect(try await p.b.record(p.idA.deviceID, type: "weight", recordID: "w1") != nil)
        #expect(try await p.b.record(p.idA.deviceID, type: "weight", recordID: "w2") == nil, "not in the ledger yet")
        #expect(try await p.b.cursor(p.idA.deviceID) == before)

        await gate.open()
        weights.gate = nil
        while await coord.isRefreshingLedger { try await Task.sleep(nanoseconds: 20_000_000) }
        // The next session carries the newer change.
        let (ra2, rb2) = await sessions(p, coord, registryA: registry, wait: 0.3)
        #expect(ra2.ok && rb2.ok)
        #expect(rb2.counts["inserted"] == 1)
        #expect(try await p.b.record(p.idA.deviceID, type: "weight", recordID: "w2") != nil)
        await p.cleanup()
    }

    @Test func sessionWaitsForAShortRefresh() async throws {
        let p = try await S.pair(grantsAtoB: ["vitals"])
        let weights = PartnerFakeSource(type: "weight", [S.weight("w1", kg: 70)])
        let registry = PartnerSourceRegistry([weights])
        let coord = coordinator(p, clock: Clock())
        let gate = PartnerLatch()
        weights.gate = gate
        #expect(await coord.startLedgerRefresh(registry: registry))
        Task {
            try? await Task.sleep(nanoseconds: 200_000_000)
            await gate.open()
        }
        let (ra, rb) = await sessions(p, coord, registryA: registry, wait: 10)
        #expect(ra.ok && rb.ok, "\(ra) \(rb)")
        #expect(!(await coord.isRefreshingLedger))
        #expect(try await p.b.record(p.idA.deviceID, type: "weight", recordID: "w1") != nil, "served after the refresh")
        #expect(try await p.b.cursor(p.idA.deviceID) == (try await p.a.outboundRev()))
        await p.cleanup()
    }

    @Test func refreshFinishedUnderTwoMinutesAgoIsSkipped() async throws {
        let p = try await S.pair(grantsAtoB: ["vitals"])
        let weights = PartnerFakeSource(type: "weight", [S.weight("w1", kg: 70)])
        let registry = PartnerSourceRegistry([weights])
        let clock = Clock()
        let coord = coordinator(p, clock: clock)
        #expect(await coord.startLedgerRefresh(registry: registry))
        while await coord.isRefreshingLedger { try await Task.sleep(nanoseconds: 20_000_000) }
        #expect(await coord.lastRefreshAt == clock.now)
        let rev = try await p.a.outboundRev()

        clock.advance(119)
        weights.put(S.weight("w2", kg: 71))
        #expect(!(await coord.startLedgerRefresh(registry: registry)), "fresh: skipped")
        #expect(!(await coord.isRefreshingLedger))
        await coord.waitForLedger(timeout: 5) // nothing in flight: returns at once
        #expect(try await p.a.outboundRev() == rev)

        clock.advance(1)
        #expect(await coord.startLedgerRefresh(registry: registry), "2 min: refreshes again")
        while await coord.isRefreshingLedger { try await Task.sleep(nanoseconds: 20_000_000) }
        #expect(try await p.a.outboundRev() == rev + 1)
        await p.cleanup()
    }

    @Test func refreshesOnOneDatabaseNeverInterleave() async throws {
        // Two refresher instances (window + export) on one database share one run (TEMP staging is per connection).
        let (db, dir) = try await S.openDB()
        defer { try? FileManager.default.removeItem(at: dir) }
        let weights = PartnerFakeSource(type: "weight", (0..<50).map { S.weight(String(format: "w%02d", $0), kg: 70) })
        let gate = PartnerLatch()
        weights.gate = gate
        let registry = PartnerSourceRegistry([weights])
        let r1 = PartnerLedgerRefresher(store: db, registry: registry, pageSize: 7, now: { S.now })
        let r2 = PartnerLedgerRefresher(store: db, registry: registry, pageSize: 7, now: { S.now })
        async let a = r1.refresh()
        async let b = r2.refresh()
        try await Task.sleep(nanoseconds: 100_000_000)
        await gate.open()
        let (ra, rb) = try await (a, b)
        #expect(ra.changed == 50 && rb.changed == 50)
        #expect(try await db.ledgerCount() == 50)
        #expect(try await db.outboundRev() == 50)
        await db.close()
    }

    @Test func killedWindowStatusBecomesPartnerUnavailable() async throws {
        let p = try await S.pair(grantsAtoB: ["vitals"])
        let idC = S.identity("33333333-4444-4555-8666-777777777777")
        try await p.a.upsertPartner(S.peer(idC, name: "C"))
        try await p.a.updateSyncState(p.idB.deviceID) {
            $0.status = "syncing"; $0.lastSyncMs = 1234; $0.lastError = "incomplete"
        }
        try await p.a.updateSyncState(idC.deviceID) { $0.status = "up_to_date"; $0.lastSyncMs = 99 }

        #expect(try await PartnerSyncCoordinator.settleStaleStatus(store: p.a) == 1)
        let st = try #require(try await p.a.syncState(p.idB.deviceID))
        #expect(st.status == "partner_unavailable")
        #expect(st.lastSyncMs == 1234, "last_sync_ms kept")
        #expect(st.lastError == "incomplete", "last_error kept")
        #expect(try await p.a.syncState(idC.deviceID)?.status == "up_to_date")

        try await p.a.updateSyncState(idC.deviceID) { $0.status = "connecting" }
        #expect(try await PartnerSyncCoordinator.settleStaleStatus(store: p.a, skip: [idC.deviceID]) == 0, "a live session is left alone")
        // The coordinator does it once, before its first window.
        let coord = coordinator(p, clock: Clock())
        await coord.clearStaleStatus()
        #expect(try await p.a.syncState(idC.deviceID)?.status == "partner_unavailable")
        try await p.a.updateSyncState(idC.deviceID) { $0.status = "syncing" }
        await coord.clearStaleStatus()
        #expect(try await p.a.syncState(idC.deviceID)?.status == "syncing", "only once per coordinator")
        await p.cleanup()
    }

    /// Quick profile: a full refresh of ~7.4k records (the Android emulator's data size) through the paged refresher.
    @Test func fullRefreshOfSevenThousandRecordsProfile() async throws {
        let (db, dir) = try await S.openDB()
        defer { try? FileManager.default.removeItem(at: dir) }
        let tz = TimeZone(identifier: "UTC")!
        let today = PartnerDay.string(S.now, timeZone: tz)
        let metrics = PartnerFakeSource(type: "metric_day", (0..<2600).map { S.metricDay(PartnerDay.adding(-$0, to: today, timeZone: tz), steps: $0) })
        let foods = PartnerFakeSource(type: "food_entry", (0..<2400).map { S.food(String(format: "f%05d", $0), kcal: $0) })
        let weights = PartnerFakeSource(type: "weight", (0..<2400).map { S.weight(String(format: "w%05d", $0), kg: 60 + Double($0 % 30)) })
        let refresher = PartnerLedgerRefresher(store: db, registry: PartnerSourceRegistry([metrics, foods, weights]), now: { S.now }, timeZone: tz)
        let full = try await refresher.refresh()
        let window = try await refresher.refresh()
        let line = "PartnerProfile full refresh: \(full.rows) rows in \(full.elapsedMs) ms \(full.typeMs.sorted { $0.key < $1.key }); " +
            "window refresh: \(window.rows) rows in \(window.elapsedMs) ms"
        print(line)
        // `TEST_RUNNER_PARTNER_PROFILE_OUT=<file> xcodebuild test …` keeps the numbers (stdout is not in the xcodebuild log).
        if let out = ProcessInfo.processInfo.environment["PARTNER_PROFILE_OUT"] { try? (line + "\n").write(toFile: out, atomically: true, encoding: .utf8) }
        #expect(full.changed == 7400)
        #expect(window.changed == 0)
        await db.close()
    }
}
