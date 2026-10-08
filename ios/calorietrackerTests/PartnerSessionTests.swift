import Foundation
import Network
import Testing
@testable import calorietracker

/// Sync engine tests: in-process sessions over an in-memory channel pair, a real loopback TCP + Noise KK session
/// through NWListener / NWConnection, and an in-process pairing (IKpsk2 + PAIR_CONFIRM + initial sync).
@Suite(.serialized)
struct PartnerSessionTests {
    typealias S = PartnerTestSupport

    private func refresh(_ store: PartnerDatabase, _ registry: PartnerSourceRegistry) async throws {
        _ = try await PartnerLedgerRefresher(store: store, registry: registry, now: { S.now }).refresh()
    }

    @Test func trustedSessionSyncsEditsTombstonesAndRevocation() async throws {
        let p = try await S.pair(grantsAtoB: ["vitals", "nutrition"])
        let weights = PartnerFakeSource(type: "weight", [S.weight("w1", kg: 70), S.weight("w2", kg: 71), S.weight("w3", kg: 72)])
        let foods = PartnerFakeSource(type: "food_entry", [S.food("f1")])
        let registry = PartnerSourceRegistry([weights, foods])
        try await refresh(p.a, registry)

        // Initial sync.
        var (ra, rb) = await S.session(p, registryA: registry)
        #expect(ra.ok && rb.ok, "\(ra) \(rb)")
        #expect(try await p.b.recordCount(p.idA.deviceID) == 4)
        #expect(rb.counts["inserted"] == 4)
        #expect(try await p.b.syncState(p.idA.deviceID)?.status == "up_to_date")
        #expect(try await p.b.syncState(p.idA.deviceID)?.lastTransport == "network")
        let rev = try await p.a.outboundRev()
        #expect(try await p.a.syncState(p.idB.deviceID)?.ackedRev == rev)
        #expect(try await p.b.cursor(p.idA.deviceID) == rev)

        // A second session is an empty delta.
        (ra, rb) = await S.session(p, registryA: registry)
        #expect(ra.ok && rb.ok)
        #expect(rb.recordsReceived == 0)
        #expect(rb.pagesReceived == 1)

        // An edit on the sender propagates.
        weights.put(S.weight("w1", kg: 69.5))
        try await refresh(p.a, registry)
        (ra, rb) = await S.session(p, registryA: registry)
        #expect(rb.counts["updated"] == 1)
        #expect(try await p.b.record(p.idA.deviceID, type: "weight", recordID: "w1")?.data["kg"].pyNumber == 69.5)

        // A tombstone propagates.
        weights.remove("w2")
        try await refresh(p.a, registry)
        (ra, rb) = await S.session(p, registryA: registry)
        #expect(rb.counts["deleted"] == 1)
        #expect(try await p.b.record(p.idA.deviceID, type: "weight", recordID: "w2") == nil)
        #expect(try await p.b.recordCount(p.idA.deviceID) == 3)

        // A revoked category stops: no new rows, revoked_ms set, the data stays.
        try await p.a.grantCategory(p.idB.deviceID, category: "vitals", granted: false, nowMs: S.nowMs)
        weights.put(S.weight("w9", kg: 80))
        try await refresh(p.a, registry)
        (ra, rb) = await S.session(p, registryA: registry)
        #expect(ra.ok && rb.ok)
        #expect(try await p.b.record(p.idA.deviceID, type: "weight", recordID: "w9") == nil)
        #expect(try await p.b.record(p.idA.deviceID, type: "weight", recordID: "w1") != nil)
        let vitals = try await p.b.grantsReceived(p.idA.deviceID).first { $0.category == "vitals" }
        #expect(vitals?.granted == false)
        #expect(vitals?.revokedMs == S.nowMs)

        // Re-granting resends the category (ledger_regrant), duplicates absorbed.
        try await p.a.grantCategory(p.idB.deviceID, category: "vitals", granted: true, nowMs: S.nowMs)
        (ra, rb) = await S.session(p, registryA: registry)
        #expect(try await p.b.record(p.idA.deviceID, type: "weight", recordID: "w9") != nil)
        #expect(try await p.b.grantsReceived(p.idA.deviceID).first { $0.category == "vitals" }?.revokedMs == nil)
        await p.cleanup()
    }

    @Test func interruptedPullResumesWithoutDuplicates() async throws {
        let p = try await S.pair(grantsAtoB: ["vitals"])
        let weights = PartnerFakeSource(type: "weight", (0..<1200).map { S.weight(String(format: "w%05d", $0), kg: 60 + Double($0 % 30)) })
        let registry = PartnerSourceRegistry([weights])
        try await refresh(p.a, registry)

        // Drop the connection when A tries to send its second CHANGES page.
        let counter = PartnerCounter()
        let (ra, rb) = await S.session(p, registryA: registry, dropA: { msg in
            if msg["t"].string == "CHANGES" { return counter.increment() < 1 }
            return true
        })
        #expect(!ra.ok && !rb.ok)
        #expect(rb.error == "incomplete")
        // Only the first page committed (pages are also cut to fit one 64 KB frame, so it holds < 500 rows).
        let committed = Int(try await p.b.cursor(p.idA.deviceID))
        #expect(committed > 0 && committed <= 500)
        #expect(try await p.b.recordCount(p.idA.deviceID) == committed)
        #expect(rb.pagesReceived == 1)
        #expect(try await p.b.syncState(p.idA.deviceID)?.status == "sync_failed")

        let (ra2, rb2) = await S.session(p, registryA: registry)
        #expect(ra2.ok && rb2.ok)
        #expect(rb2.counts["inserted"] == 1200 - committed)
        #expect(rb2.counts["duplicate"] == 0)
        #expect(rb2.counts["stale"] == 0)
        #expect(try await p.b.recordCount(p.idA.deviceID) == 1200)
        await p.cleanup()
    }

    @Test func pagesStayWithinOneFrame() async throws {
        let p = try await S.pair(grantsAtoB: ["nutrition"])
        // ~1.9 KB names: 500 of them would far exceed one frame, so pages are cut by size.
        let big = (0..<120).map { i in
            PartnerSourceRecord(type: "food_entry", recordID: String(format: "f%04d", i), category: "nutrition", day: "2026-10-06",
                                data: .obj(["name": .str(String(repeating: "x", count: 1900)), "logged_ms": .int(1), "calories": .int(i)]))
        }
        let registry = PartnerSourceRegistry([PartnerFakeSource(type: "food_entry", big)])
        try await refresh(p.a, registry)
        let (ra, rb) = await S.session(p, registryA: registry)
        #expect(ra.ok && rb.ok)
        #expect(rb.pagesReceived > 1)
        #expect(try await p.b.recordCount(p.idA.deviceID) == 120)
        await p.cleanup()
    }

    @Test func helloFromAnotherDeviceIsNotTrusted() async throws {
        let p = try await S.pair(grantsAtoB: ["vitals"])
        let registry = PartnerSourceRegistry([PartnerFakeSource(type: "weight", [S.weight("w1", kg: 70)])])
        try await refresh(p.a, registry)
        // B believes it talks to someone else: A's HELLO device_id does not match.
        let (chA, chB) = PartnerMemoryChannel.pair()
        var opts = PartnerSessionOptions()
        opts.idleTimeout = 2
        let sa = PartnerSession(store: p.a, registry: registry, local: PartnerLocalInfo(deviceID: p.idA.deviceID, name: "A"),
                                peerID: p.idB.deviceID, channel: chA, options: opts, now: { S.now })
        let other = "11111111-2222-4333-8444-555555555555"
        try await p.b.upsertPartner(S.peer(S.identity(other)))
        let sb = PartnerSession(store: p.b, registry: PartnerSourceRegistry([]), local: PartnerLocalInfo(deviceID: p.idB.deviceID, name: "B"),
                                peerID: other, channel: chB, options: opts, now: { S.now })
        async let ra = sa.run()
        async let rb = sb.run()
        let (oa, ob) = await (ra, rb)
        #expect(ob.error == "not_trusted")
        #expect(oa.error == "not_trusted")
        #expect(try await p.b.recordCount(p.idA.deviceID) == 0)
        await p.cleanup()
    }

    // MARK: - Loopback TCP + Noise KK

    @Test func loopbackTCPSessionAndUntrustedInitiatorRejected() async throws {
        let p = try await S.pair(grantsAtoB: ["vitals"], grantsBtoA: ["nutrition"])
        let registryA = PartnerSourceRegistry([PartnerFakeSource(type: "weight", [S.weight("w1", kg: 70), S.weight("w2", kg: 71)])])
        let registryB = PartnerSourceRegistry([PartnerFakeSource(type: "food_entry", [S.food("f1"), S.food("f2"), S.food("f3")])])
        try await refresh(p.a, registryA)
        try await refresh(p.b, registryB)
        let coordinatorB = PartnerSyncCoordinator(store: p.b, identity: p.idB, local: PartnerLocalInfo(deviceID: p.idB.deviceID, name: "B"),
                                                  makeSources: { (registryB, []) }, now: { S.now })

        let listener = try PartnerListener(advertise: false)
        let port = try await listener.start(onConnection: { conn in
            let io = PartnerNWConnection(connection: conn)
            io.startInbound()
            Task { await coordinatorB.respond(io: io) }
        })
        defer { listener.cancel() }

        // Trusted initiator A.
        let conn = PartnerNWConnection(host: "127.0.0.1", port: port)
        try await conn.start(timeout: 5)
        var peerB = S.peer(p.idB)
        peerB.lastHost = nil
        let channel = try await PartnerHandshake.kkInitiate(io: conn, identity: p.idA, peer: peerB)
        let session = PartnerSession(store: p.a, registry: registryA, local: PartnerLocalInfo(deviceID: p.idA.deviceID, name: "A"),
                                     peerID: p.idB.deviceID, channel: channel, now: { S.now })
        let outcome = await session.run()
        #expect(outcome.ok, "\(outcome)")
        #expect(outcome.recordsReceived == 3)
        #expect(try await p.a.recordCount(p.idB.deviceID) == 3)
        // B's side finishes asynchronously after DONE.
        try await waitUntil { (try? await p.b.recordCount(p.idA.deviceID)) == 2 }
        #expect(try await p.b.recordCount(p.idA.deviceID) == 2)

        // An untrusted initiator (keys unknown to B) is rejected before anything else.
        let stranger = S.identity("22222222-3333-4444-8555-666666666666")
        let conn2 = PartnerNWConnection(host: "127.0.0.1", port: port)
        try await conn2.start(timeout: 5)
        await #expect(throws: PartnerNetError.self) {
            _ = try await PartnerHandshake.kkInitiate(io: conn2, identity: stranger, peer: peerB)
        }
        await conn2.close()
        #expect(try await p.b.partner(stranger.deviceID) == nil)

        // An unpaired partner is no longer accepted either.
        try await p.b.unpair(p.idA.deviceID, nowMs: S.nowMs)
        let conn3 = PartnerNWConnection(host: "127.0.0.1", port: port)
        try await conn3.start(timeout: 5)
        await #expect(throws: PartnerNetError.self) {
            _ = try await PartnerHandshake.kkInitiate(io: conn3, identity: p.idA, peer: peerB)
        }
        await conn3.close()
        await p.cleanup()
    }

    private func waitUntil(timeout: TimeInterval = 10, _ condition: () async -> Bool) async throws {
        let end = Date().addingTimeInterval(timeout)
        while Date() < end {
            if await condition() { return }
            try await Task.sleep(nanoseconds: 50_000_000)
        }
    }

    // MARK: - Pairing (in-process)

    @Test func pairingStoresBothPartnersAndRunsTheInitialSync() async throws {
        let (a, dirA) = try await S.openDB()
        let (b, dirB) = try await S.openDB()
        defer { for d in [dirA, dirB] { try? FileManager.default.removeItem(at: d) } }
        let idA = S.identity("0b8f3c2a-5d1e-4c7a-9f00-1a2b3c4d5e6f")
        let idB = S.identity("7e6d5c4b-3a29-4180-8f7e-6d5c4b3a2918")
        let registryA = PartnerSourceRegistry([PartnerFakeSource(type: "weight", [S.weight("w1", kg: 70)])])
        let registryB = PartnerSourceRegistry([PartnerFakeSource(type: "food_entry", [S.food("f1")])])
        let coordA = PartnerSyncCoordinator(store: a, identity: idA, local: PartnerLocalInfo(deviceID: idA.deviceID, name: "Ananya"),
                                            makeSources: { (registryA, []) }, now: { S.now })
        let coordB = PartnerSyncCoordinator(store: b, identity: idB, local: PartnerLocalInfo(deviceID: idB.deviceID, name: "Ravi"),
                                            makeSources: { (registryB, []) }, now: { S.now })
        let statesA = PartnerStateLog()
        let statesB = PartnerStateLog()
        let shower = PartnerPairing(store: a, identity: idA, coordinator: coordA, now: { S.now }) { statesA.append($0) }
        let scanner = PartnerPairing(store: b, identity: idB, coordinator: coordB, now: { S.now }) { statesB.append($0) }

        // A shows a code (built here without a listener), B scans it over an in-memory frame pipe.
        let token = Data((0..<32).map { UInt8($0) })
        await shower.setPSK(PartnerRef.pairingPSK(token: token))
        let qr = PartnerRef.qrEncode(.obj([
            "protocol": .str("ayuvo-partner-sync"), "v": .int(1), "device_id": .str(idA.deviceID), "name": .str("Ananya"),
            "x25519": .str(idA.x25519PublicB64), "ed25519": .str(idA.ed25519PublicB64), "token": .str(PartnerEncoding.b64urlEncode(token)),
            "exp_ms": .int(Int(S.nowMs) + 300_000), "hosts": .arr([]),
        ]))
        let (ioA, ioB) = PartnerMemoryFrameIO.pair()
        async let accepted: Void = shower.accept(ioA)
        await scanner.scan(qr, io: ioB)
        await accepted
        guard case .confirmCode(let sasB, let nameB, _) = await scanner.state, case .confirmCode(let sasA, _, _) = await shower.state else {
            Issue.record("no SAS: \(await scanner.state) / \(await shower.state)")
            return
        }
        #expect(sasA == sasB)
        #expect(sasA.count == 6)
        #expect(nameB == "Ananya")

        async let ca: Void = shower.confirm(accepted: true, grants: ["vitals"], name: "Ananya")
        async let cb: Void = scanner.confirm(accepted: true, grants: ["nutrition"], name: "Ravi")
        _ = await (ca, cb)
        #expect(await shower.state == .paired(ownerID: idB.deviceID, name: "Ravi"))
        #expect(await scanner.state == .paired(ownerID: idA.deviceID, name: "Ananya"))
        #expect(try await a.partner(idB.deviceID)?.x25519Pub == idB.x25519PublicB64)
        #expect(try await b.partner(idA.deviceID)?.ed25519Pub == idA.ed25519PublicB64)
        #expect(try await a.grantsOut(idB.deviceID) == ["vitals"])
        #expect(try await b.grantedCategoriesReceived(idA.deviceID) == ["vitals"])
        // Initial sync on the same connection.
        #expect(try await b.recordCount(idA.deviceID) == 1)
        #expect(try await a.recordCount(idB.deviceID) == 1)
        #expect(statesB.contains { if case .syncing = $0 { return true } else { return false } })
        await a.close()
        await b.close()
    }

    @Test func pairingWithAWrongTokenFails() async throws {
        let (a, dirA) = try await S.openDB()
        let (b, dirB) = try await S.openDB()
        defer { for d in [dirA, dirB] { try? FileManager.default.removeItem(at: d) } }
        let idA = S.identity("0b8f3c2a-5d1e-4c7a-9f00-1a2b3c4d5e6f")
        let idB = S.identity("7e6d5c4b-3a29-4180-8f7e-6d5c4b3a2918")
        let coordA = PartnerSyncCoordinator(store: a, identity: idA, local: PartnerLocalInfo(deviceID: idA.deviceID, name: "A"))
        let coordB = PartnerSyncCoordinator(store: b, identity: idB, local: PartnerLocalInfo(deviceID: idB.deviceID, name: "B"))
        let shower = PartnerPairing(store: a, identity: idA, coordinator: coordA, now: { S.now }) { _ in }
        let scanner = PartnerPairing(store: b, identity: idB, coordinator: coordB, now: { S.now }) { _ in }
        await shower.setPSK(PartnerRef.pairingPSK(token: Data(repeating: 1, count: 32)))
        let qr = PartnerRef.qrEncode(.obj([
            "protocol": .str("ayuvo-partner-sync"), "v": .int(1), "device_id": .str(idA.deviceID), "name": .str("A"),
            "x25519": .str(idA.x25519PublicB64), "ed25519": .str(idA.ed25519PublicB64),
            "token": .str(PartnerEncoding.b64urlEncode(Data(repeating: 2, count: 32))), "exp_ms": .int(Int(S.nowMs) + 300_000), "hosts": .arr([]),
        ]))
        let (ioA, ioB) = PartnerMemoryFrameIO.pair()
        async let accepted: Void = shower.accept(ioA)
        await scanner.scan(qr, io: ioB)
        await accepted
        #expect(await scanner.state == .failed("not_trusted"))
        #expect(try await b.partners().isEmpty)
        await a.close()
        await b.close()
    }
}

final class PartnerCounter: @unchecked Sendable {
    private let lock = NSLock()
    private var n = 0
    /// Returns the value before incrementing.
    func increment() -> Int { lock.withLock { defer { n += 1 }; return n } }
}

final class PartnerStateLog: @unchecked Sendable {
    private let lock = NSLock()
    private var states: [PartnerPairingState] = []
    func append(_ s: PartnerPairingState) { lock.withLock { states.append(s) } }
    func contains(where f: (PartnerPairingState) -> Bool) -> Bool { lock.withLock { states.contains(where: f) } }
}
