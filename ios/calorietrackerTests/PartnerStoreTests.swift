import Foundation
import Testing
@testable import calorietracker

/// Integration tests of `PartnerStore` (= `PartnerDatabase`) against a temp-directory database.
struct PartnerStoreTests {
    static let owner = "0b8f3c2a-5d1e-4c7a-9f00-1a2b3c4d5e6f"
    static let now: Int64 = 1_791_374_400_000

    static func openTemp() async throws -> (PartnerDatabase, URL) {
        let dir = try HealthTestFixtures.temporaryDirectory()
        let db = try await PartnerDatabase.open(url: dir.appendingPathComponent("Partner/partner.sqlite"))
        return (db, dir)
    }

    static func peer(_ id: String = owner) -> PartnerPeer {
        PartnerPeer(ownerID: id, displayName: "Ananya", fingerprint: String(repeating: "a", count: 32), x25519Pub: "x", ed25519Pub: "e",
                    platform: "android", pairedMs: now, unpairedMs: nil, lastHost: nil, lastPort: nil, updatedMs: now)
    }

    static func weight(_ id: String, rev: Int, kg: Double = 70, deleted: Bool = false) -> RJ {
        var env: [String: RJ] = ["type": .str("weight"), "id": .str(id), "category": .str("vitals"), "rev": .int(rev),
                                 "deleted": .bool(deleted), "updated_ms": .int(Int(now))]
        if !deleted {
            env["day"] = .str("2026-10-07")
            env["data"] = .obj(["measured_ms": .int(Int(now)), "kg": .num(kg)])
        }
        return .obj(env)
    }

    static func batch(from: Int, to: Int, _ records: [RJ]) -> RJ {
        .obj(["from_rev": .int(from), "to_rev": .int(to), "records": .arr(records)])
    }

    static func paired() async throws -> (PartnerDatabase, URL) {
        let (db, dir) = try await openTemp()
        try await db.upsertPartner(peer())
        try await db.updateGrantsReceived(owner, grantedNow: ["vitals", "sleep"], nowMs: now)
        return (db, dir)
    }

    @Test func mergeBatchWritesRowsAndCursorAndIsIdempotent() async throws {
        let (db, dir) = try await Self.paired()
        defer { try? FileManager.default.removeItem(at: dir) }
        let b = Self.batch(from: 0, to: 3, [Self.weight("w1", rev: 1), Self.weight("w2", rev: 2), Self.weight("w3", rev: 3)])
        let first = try await db.mergeBatch(ownerID: Self.owner, batch: b, nowMs: Self.now)
        #expect(first.accepted)
        #expect(first.counts["inserted"] == 3)
        #expect(try await db.cursor(Self.owner) == 3)
        #expect(try await db.recordCount(Self.owner) == 3)
        let again = try await db.mergeBatch(ownerID: Self.owner, batch: Self.batch(from: 0, to: 3, b["records"].array!), nowMs: Self.now)
        #expect(again.accepted && again.upserts.isEmpty && again.deletes.isEmpty)
        #expect(again.counts["duplicate"] == 3)

        // UPDATE-then-INSERT: a higher rev updates the existing row in place.
        let upd = try await db.mergeBatch(ownerID: Self.owner, batch: Self.batch(from: 3, to: 4, [Self.weight("w1", rev: 4, kg: 71.5)]), nowMs: Self.now)
        #expect(upd.counts["updated"] == 1)
        let row = try #require(try await db.record(Self.owner, type: "weight", recordID: "w1"))
        #expect(row.rev == 4)
        #expect(row.data["kg"].pyNumber == 71.5)
        #expect(row.tsMs == Self.now)
        #expect(try await db.recordCount(Self.owner) == 3)

        // A tombstone deletes; a cursor gap is refused whole and writes nothing.
        let del = try await db.mergeBatch(ownerID: Self.owner, batch: Self.batch(from: 4, to: 5, [Self.weight("w2", rev: 5, deleted: true)]), nowMs: Self.now)
        #expect(del.counts["deleted"] == 1)
        #expect(try await db.record(Self.owner, type: "weight", recordID: "w2") == nil)
        let gap = try await db.mergeBatch(ownerID: Self.owner, batch: Self.batch(from: 9, to: 10, [Self.weight("w9", rev: 10)]), nowMs: Self.now)
        #expect(gap.error == "cursor_gap")
        #expect(try await db.cursor(Self.owner) == 5)

        // Ungranted categories are rejected per record.
        let sleep: RJ = .obj(["type": .str("sleep_night"), "id": .str("2026-10-07"), "category": .str("sleep"), "rev": .int(6), "deleted": .bool(false),
                              "updated_ms": .int(1), "day": .str("2026-10-07"),
                              "data": .obj(["day": .str("2026-10-07"), "start_ms": .int(1), "end_ms": .int(2), "asleep_min": .int(400)])])
        try await db.updateGrantsReceived(Self.owner, grantedNow: ["vitals"], nowMs: Self.now + 1)
        let rej = try await db.mergeBatch(ownerID: Self.owner, batch: Self.batch(from: 5, to: 6, [sleep]), nowMs: Self.now)
        #expect(rej.counts["rejected"] == 1)
        let grants = try await db.grantsReceived(Self.owner)
        #expect(grants.first { $0.category == "sleep" }?.revokedMs == Self.now + 1)
        await db.close()
    }

    @Test func applyMergeRollsBackWhenAnErrorIsThrownMidBatch() async throws {
        let (db, dir) = try await Self.paired()
        defer { try? FileManager.default.removeItem(at: dir) }
        let records = (1...10).map { Self.weight("w\($0)", rev: $0) }
        let result = PartnerMergeOutcome(PartnerRef.mergeApply(existing: [], batch: Self.batch(from: 0, to: 10, records), cursor: 0,
                                                               granted: [.str("vitals")], nowMs: Int(Self.now)))
        #expect(result.upserts.count == 10)
        struct Boom: Error {}
        await #expect(throws: Boom.self) {
            try await db.applyMerge(ownerID: Self.owner, result: result, afterEachWrite: { n in if n == 6 { throw Boom() } })
        }
        #expect(try await db.recordCount(Self.owner) == 0)
        #expect(try await db.cursor(Self.owner) == 0)
        try await db.applyMerge(ownerID: Self.owner, result: result)
        #expect(try await db.recordCount(Self.owner) == 10)
        #expect(try await db.cursor(Self.owner) == 10)

        // Package batches commit rows without moving the cursor until the last batch.
        let more = PartnerMergeOutcome(PartnerRef.mergeApply(existing: [], batch: Self.batch(from: 10, to: 12, [Self.weight("w11", rev: 11)]),
                                                             cursor: 10, granted: [.str("vitals")], nowMs: Int(Self.now)))
        try await db.applyMerge(ownerID: Self.owner, result: more, commitCursor: false)
        #expect(try await db.cursor(Self.owner) == 10)
        #expect(try await db.recordCount(Self.owner) == 11)
        await db.close()
    }

    @Test func deleteDataUnpairAndRemove() async throws {
        let (db, dir) = try await Self.paired()
        defer { try? FileManager.default.removeItem(at: dir) }
        _ = try await db.mergeBatch(ownerID: Self.owner, batch: Self.batch(from: 0, to: 2, [Self.weight("w1", rev: 1), Self.weight("w2", rev: 2)]), nowMs: Self.now)
        try await db.unpair(Self.owner, nowMs: Self.now + 5)
        #expect(try await db.trustedOwnerIDs().isEmpty)
        #expect(try await db.partner(Self.owner)?.unpairedMs == Self.now + 5)
        #expect(try await db.recordCount(Self.owner) == 2, "unpair keeps data")
        try await db.deletePartnerData(Self.owner)
        #expect(try await db.recordCount(Self.owner) == 0)
        #expect(try await db.cursor(Self.owner) == 0)
        #expect(try await db.partner(Self.owner) != nil)
        try await db.removePartner(Self.owner)
        #expect(try await db.partners().isEmpty)
        #expect(try await db.syncState(Self.owner) == nil)
        await db.close()
    }

    @Test func retentionPruneDropsOldIntradayRows() async throws {
        let (db, dir) = try await Self.paired()
        defer { try? FileManager.default.removeItem(at: dir) }
        func hr(_ id: String, _ start: Int64, rev: Int) -> RJ {
            .obj(["type": .str("sample"), "id": .str(id), "category": .str("vitals"), "rev": .int(rev), "deleted": .bool(false),
                  "updated_ms": .int(1), "day": .str("2026-10-01"),
                  "data": .obj(["type_id": .str("heart_rate"), "start_ms": .int(Int(start)), "end_ms": .int(Int(start)), "unit": .str("bpm"), "value": .int(60)])])
        }
        let day: Int64 = 86_400_000
        _ = try await db.mergeBatch(ownerID: Self.owner, batch: Self.batch(from: 0, to: 3, [
            hr("old", Self.now - 8 * day, rev: 1), hr("new", Self.now - day, rev: 2), Self.weight("w", rev: 3),
        ]), nowMs: Self.now)
        #expect(try await db.retentionPrune(nowMs: Self.now) == 1)
        #expect(try await db.record(Self.owner, type: "sample", recordID: "old") == nil)
        #expect(try await db.record(Self.owner, type: "sample", recordID: "new") != nil)
        await db.close()
    }

    @Test func ledgerRefreshRegrantAndDeltaFollowTheReference() async throws {
        let (db, dir) = try await Self.openTemp()
        defer { try? FileManager.default.removeItem(at: dir) }
        func cur(_ type: String, _ id: String, _ cat: String, _ hash: String, day: String? = nil) -> RJ {
            .obj(["type": .str(type), "record_id": .str(id), "category": .str(cat), "day": RJ.string(day), "content_hash": .str(hash)])
        }
        let scopes: [RJ] = [.obj(["type": .str("weight")]), .obj(["type": .str("food_entry")])]
        let first = try await db.applyLedgerRefresh(current: [cur("weight", "b", "vitals", "h1"), cur("weight", "a", "vitals", "h1"),
                                                              cur("food_entry", "f", "nutrition", "h")], scopes: scopes)
        #expect(first.changed == 3 && first.rev == 3)
        let second = try await db.applyLedgerRefresh(current: [cur("weight", "a", "vitals", "h2"), cur("food_entry", "f", "nutrition", "h")], scopes: scopes)
        #expect(second.changed == 1 && second.tombstoned == 1 && second.rev == 5)
        let delta = try await db.ledgerDelta(cursor: 0, grants: ["vitals"])
        #expect(delta.keys.map(\.recordID) == ["a", "b"])
        #expect(delta.keys.map(\.rev) == [4, 5])
        #expect(delta.keys.last?.deleted == true)
        #expect(delta.toRev == 5 && !delta.hasMore)
        // Cursor ahead of the counter restarts from 0.
        #expect(try await db.ledgerDelta(cursor: 99, grants: ["nutrition"]).fromRev == 0)
        #expect(try await db.ledgerRegrant(category: "nutrition") == 6)
        #expect(try await db.ledgerDelta(cursor: 5, grants: ["nutrition"]).keys.map(\.recordID) == ["f"])
        await db.close()
    }

    /// 100,000 ledger rows: delta pages are bounded (≤ 500), revs strictly increase and the walk covers every
    /// granted row exactly once.
    @Test func ledgerDeltaPagesThroughOneHundredThousandRows() async throws {
        let (db, dir) = try await Self.openTemp()
        defer { try? FileManager.default.removeItem(at: dir) }
        let total = 100_000
        let rows = (1...total).map { i in
            PartnerLedgerRow(type: "sample", recordID: String(format: "s%06d", i), category: i % 10 == 0 ? "sleep" : "vitals",
                             day: "2026-10-07", contentHash: "h", rev: Int64(i), deleted: false)
        }
        try await db.upsertLedgerRows(rows)
        try await db.setOutboundRev(Int64(total))
        var cursor: Int64 = 0
        var seen = 0
        var lastRev: Int64 = 0
        var pages = 0
        while true {
            let page = try await db.ledgerDelta(cursor: cursor, grants: ["vitals"], limit: 500)
            #expect(page.keys.count <= 500)
            for k in page.keys {
                #expect(k.rev > lastRev)
                lastRev = k.rev
            }
            seen += page.keys.count
            pages += 1
            cursor = page.toRev
            if !page.hasMore { break }
        }
        #expect(seen == 90_000)
        #expect(cursor == Int64(total))
        #expect(pages == 180)
        await db.close()
    }

    @Test func grantsOutAndSyncState() async throws {
        let (db, dir) = try await Self.paired()
        defer { try? FileManager.default.removeItem(at: dir) }
        #expect(try await db.grantsOut(Self.owner).isEmpty, "default off")
        try await db.setGrantOut(Self.owner, category: "sleep", granted: true, nowMs: Self.now)
        try await db.setGrantOut(Self.owner, category: "vitals", granted: true, nowMs: Self.now)
        try await db.setGrantOut(Self.owner, category: "sleep", granted: false, nowMs: Self.now)
        #expect(try await db.grantsOut(Self.owner) == ["vitals"])
        var state = try #require(try await db.syncState(Self.owner))
        #expect(state.status == "pairing_required")
        state.status = "up_to_date"
        state.ackedRev = 42
        try await db.saveSyncState(state)
        #expect(try await db.syncState(Self.owner)?.ackedRev == 42)
        await db.close()
    }

    @Test func deviceIdentityPersistsAndSigns() throws {
        let store = InMemoryPartnerSecretStore()
        let a = try DeviceIdentity.loadOrCreate(store: store)
        let b = try DeviceIdentity.loadOrCreate(store: store)
        #expect(a.deviceID == b.deviceID)
        #expect(PyStr.isUUID(a.deviceID))
        #expect(a.x25519Public == b.x25519Public && a.ed25519Public == b.ed25519Public)
        #expect(a.fingerprint.count == 32)
        #expect(a.formattedFingerprint.split(separator: " ").count == 8)
        let msg = Data("manifest".utf8)
        let sig = try a.sign(msg)
        #expect(DeviceIdentity.verify(signature: sig, message: msg, publicKey: b.ed25519Public))
        #expect(!DeviceIdentity.verify(signature: sig, message: Data("other".utf8), publicKey: b.ed25519Public))
        #expect(DeviceIdentity.verify(signatureB64: PartnerEncoding.b64urlEncode(sig), message: msg, publicKeyB64: a.ed25519PublicB64))
        DeviceIdentity.reset(store: store)
        #expect(try DeviceIdentity.loadOrCreate(store: store).deviceID != a.deviceID)
    }

    @Test func keychainDataHelperRoundTrips() {
        let key = "partner.tests.\(UUID().uuidString)"
        defer { KeychainHelper.delete(key: key) }
        let data = Data((0..<32).map { UInt8($0) })
        #expect(KeychainHelper.saveDeviceOnly(key: key, data: data))
        #expect(KeychainHelper.loadData(key: key) == data)
        KeychainHelper.delete(key: key)
        #expect(KeychainHelper.loadData(key: key) == nil)
    }
}
