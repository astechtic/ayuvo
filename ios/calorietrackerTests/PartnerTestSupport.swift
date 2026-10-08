import CryptoKit
import Foundation
@testable import calorietracker

/// A mutable in-memory source for session / refresher tests.
final class PartnerFakeSource: PartnerRecordSource, @unchecked Sendable {
    let type: String
    private let lock = NSLock()
    private var records: [String: PartnerSourceRecord] = [:]
    var unavailable = false
    /// When set, `scan` waits for it to open (a refresh "in flight").
    var gate: PartnerLatch?
    private(set) var pagesSeen: [Int] = []

    init(type: String, _ records: [PartnerSourceRecord] = []) {
        self.type = type
        for r in records { self.records[r.recordID] = r }
    }

    func put(_ r: PartnerSourceRecord) { lock.withLock { records[r.recordID] = r } }
    func remove(_ id: String) { lock.withLock { records[id] = nil } }
    var count: Int { lock.withLock { records.count } }

    func scan(scope: PartnerSourceScope, pageSize: Int, onPage: ([PartnerSourceRecord]) async throws -> Void) async throws {
        if let gate { await gate.wait() }
        if unavailable { throw PartnerSourceUnavailable(type: type, reason: "test") }
        let all = lock.withLock { records.values.sorted { utf8Less($0.recordID, $1.recordID) } }.filter { scope.contains(day: $0.day) }
        var page: [PartnerSourceRecord] = []
        for r in all {
            page.append(r)
            if page.count == pageSize {
                lock.withLock { pagesSeen.append(page.count) }
                try await onPage(page)
                page = []
            }
        }
        if !page.isEmpty {
            lock.withLock { pagesSeen.append(page.count) }
            try await onPage(page)
        }
    }

    func record(id: String) async throws -> PartnerSourceRecord? { lock.withLock { records[id] } }
}

/// A one-shot latch: `wait()` suspends until `open()`.
actor PartnerLatch {
    private var isOpen = false
    private var waiters: [CheckedContinuation<Void, Never>] = []

    func wait() async {
        if isOpen { return }
        await withCheckedContinuation { waiters.append($0) }
    }

    func open() {
        isOpen = true
        let w = waiters
        waiters = []
        for c in w { c.resume() }
    }
}

enum PartnerTestSupport {
    static let now: Date = Date(timeIntervalSince1970: 1_791_374_400)   // 2026-10-07 12:00 UTC
    static var nowMs: Int64 { Int64(now.timeIntervalSince1970 * 1000) }

    static func identity(_ id: String) -> DeviceIdentity {
        DeviceIdentity(deviceID: id, agreementKey: Curve25519.KeyAgreement.PrivateKey(), signingKey: Curve25519.Signing.PrivateKey())
    }

    static func peer(_ identity: DeviceIdentity, name: String = "Partner") -> PartnerPeer {
        PartnerPeer(ownerID: identity.deviceID, displayName: name, fingerprint: identity.fingerprint, x25519Pub: identity.x25519PublicB64,
                    ed25519Pub: identity.ed25519PublicB64, platform: "ios", pairedMs: nowMs, unpairedMs: nil, lastHost: nil, lastPort: nil,
                    updatedMs: nowMs)
    }

    static func openDB() async throws -> (PartnerDatabase, URL) {
        let dir = try HealthTestFixtures.temporaryDirectory()
        return (try await PartnerDatabase.open(url: dir.appendingPathComponent("Partner/partner.sqlite")), dir)
    }

    static func weight(_ id: String, kg: Double, day: String = "2026-10-06") -> PartnerSourceRecord {
        PartnerSourceRecord(type: "weight", recordID: id, category: "vitals", day: day, data: .obj(["measured_ms": .int(Int(nowMs) - 3_600_000), "kg": .num(kg)]))
    }

    static func food(_ id: String, day: String = "2026-10-06", kcal: Int = 300) -> PartnerSourceRecord {
        PartnerSourceRecord(type: "food_entry", recordID: id, category: "nutrition", day: day,
                            data: .obj(["name": .str("Oats"), "logged_ms": .int(Int(nowMs) - 7_200_000), "calories": .int(kcal)]))
    }

    static func metricDay(_ day: String, steps: Int) -> PartnerSourceRecord {
        PartnerSourceRecord(type: "metric_day", recordID: "steps:\(day)", category: "vitals", day: day,
                            data: .obj(["type_id": .str("steps"), "day": .str(day), "unit": .str("count"), "sum": .int(steps)]))
    }

    /// Two paired databases: A shares `grantsAtoB` with B, B shares `grantsBtoA` with A.
    struct Pair {
        let a: PartnerDatabase, b: PartnerDatabase
        let idA: DeviceIdentity, idB: DeviceIdentity
        let dirs: [URL]
        func cleanup() async {
            await a.close()
            await b.close()
            for d in dirs { try? FileManager.default.removeItem(at: d) }
        }
    }

    static func pair(grantsAtoB: [String], grantsBtoA: [String] = []) async throws -> Pair {
        let (a, dirA) = try await openDB()
        let (b, dirB) = try await openDB()
        let idA = identity("0b8f3c2a-5d1e-4c7a-9f00-1a2b3c4d5e6f")
        let idB = identity("7e6d5c4b-3a29-4180-8f7e-6d5c4b3a2918")
        try await a.upsertPartner(peer(idB, name: "B"))
        try await b.upsertPartner(peer(idA, name: "A"))
        try await a.setGrantsOut(idB.deviceID, granted: grantsAtoB, nowMs: nowMs)
        try await b.setGrantsOut(idA.deviceID, granted: grantsBtoA, nowMs: nowMs)
        return Pair(a: a, b: b, idA: idA, idB: idB, dirs: [dirA, dirB])
    }

    /// Runs one session on each side over an in-memory channel pair.
    static func session(_ p: Pair, registryA: PartnerSourceRegistry, registryB: PartnerSourceRegistry = PartnerSourceRegistry([]),
                        options: PartnerSessionOptions = PartnerSessionOptions(),
                        dropA: (@Sendable (RJ) -> Bool)? = nil) async -> (PartnerSessionOutcome, PartnerSessionOutcome) {
        let (chA, chB) = PartnerMemoryChannel.pair()
        chA.onSend = dropA
        var opts = options
        opts.idleTimeout = 3
        let sa = PartnerSession(store: p.a, registry: registryA, local: PartnerLocalInfo(deviceID: p.idA.deviceID, name: "A"),
                                peerID: p.idB.deviceID, channel: chA, options: opts, now: { now })
        let sb = PartnerSession(store: p.b, registry: registryB, local: PartnerLocalInfo(deviceID: p.idB.deviceID, name: "B"),
                                peerID: p.idA.deviceID, channel: chB, options: opts, now: { now })
        async let ra = sa.run()
        async let rb = sb.run()
        return await (ra, rb)
    }

    static var fixturesDirectory: URL { HealthTestFixtures.repoRootURL.appendingPathComponent("shared/partner/fixtures") }
}
