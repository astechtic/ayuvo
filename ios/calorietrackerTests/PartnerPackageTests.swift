import Foundation
import Testing
@testable import calorietracker

/// `.ayuvo.zip` packages: the shared signed fixture (validate, signature, import counts, re-import, tamper, wrong key,
/// unknown sender), an export → import round trip between two databases and the entry allow-list.
struct PartnerPackageTests {
    typealias S = PartnerTestSupport

    static var fixtureZip: URL { S.fixturesDirectory.appendingPathComponent("partner-sample.ayuvo.zip") }

    static func fixtureMeta() throws -> RJ {
        let data = try Data(contentsOf: S.fixturesDirectory.appendingPathComponent("partner-sample.json"))
        return try #require(PartnerJSON.parse(data))
    }

    static func senderPeer(_ meta: RJ, ed25519: String? = nil) -> PartnerPeer {
        PartnerPeer(ownerID: meta["sender_device_id"].string ?? "", displayName: "Ananya", fingerprint: meta["sender_fingerprint"].string ?? "",
                    x25519Pub: meta["sender_x25519"].string ?? "", ed25519Pub: ed25519 ?? meta["sender_ed25519"].string ?? "", platform: "android",
                    pairedMs: S.nowMs, unpairedMs: nil, lastHost: nil, lastPort: nil, updatedMs: S.nowMs)
    }

    static func copyFixture() throws -> URL {
        let dir = try HealthTestFixtures.temporaryDirectory()
        let url = dir.appendingPathComponent("partner-sample.ayuvo.zip")
        try FileManager.default.copyItem(at: fixtureZip, to: url)
        return url
    }

    @Test func fixtureSignatureVerifiesAndImportsToTheRecordedCounts() async throws {
        let meta = try Self.fixtureMeta()
        let reader = try ZipArchiveReader(url: Self.fixtureZip)
        #expect(reader.entries.first?.name == "manifest.json")
        let manifestEntry = try #require(reader.entry(named: "manifest.json"))
        let signatureEntry = try #require(reader.entry(named: "signature.json"))
        let manifestBytes = try reader.data(for: manifestEntry)
        let signatureBytes = try reader.data(for: signatureEntry)
        let signature = try #require(PartnerJSON.parse(signatureBytes))
        #expect(signature["key"].string == meta["sender_ed25519"].string)
        #expect(DeviceIdentity.verify(signatureB64: signature["sig"].string ?? "", message: manifestBytes, publicKeyB64: meta["sender_ed25519"].string ?? ""))
        #expect(PartnerPackageImporter.isPartnerPackage(Self.fixtureZip))

        let (db, dir) = try await S.openDB()
        defer { try? FileManager.default.removeItem(at: dir) }
        try await db.upsertPartner(Self.senderPeer(meta))
        let me = meta["recipient_device_id"].string ?? ""
        let fixtureNow = Date(timeIntervalSince1970: Double(meta["now_ms"].pyInt ?? 0) / 1000)
        let url = try Self.copyFixture()
        let preview = try await PartnerPackageImporter.open(url: url, store: db, me: me)
        let summary = meta["expected"]["summary"]
        #expect(preview.total == summary["total"].pyInt)
        #expect(preview.perCategory.map(\.category) == (summary["per_category"].array ?? []).compactMap { $0["category"].string })
        #expect(preview.perCategory.map(\.count) == (summary["per_category"].array ?? []).compactMap { $0["count"].pyInt })
        #expect(preview.senderName == "Ananya")

        let first = try await PartnerPackageImporter.importPackage(preview, store: db, me: me, now: { fixtureNow })
        let expectedFirst = meta["expected"]["first_import"]
        #expect(first.cursor == Int64(expectedFirst["cursor"].pyInt ?? -1))
        for (k, v) in expectedFirst["counts"].object ?? [:] { #expect(first.counts[k] == v.pyInt, "\(k)") }
        #expect(try await db.recordCount(preview.senderID) == 10)
        let st = try await db.syncState(preview.senderID)
        #expect(st?.lastTransport == "package")
        #expect(st?.lastExportID == preview.exportID)
        #expect(try await db.grantedCategoriesReceived(preview.senderID) == PartnerCatalog.shared.categories)

        let second = try await PartnerPackageImporter.importPackage(preview, store: db, me: me, now: { fixtureNow })
        let expectedSecond = meta["expected"]["second_import"]
        #expect(second.cursor == Int64(expectedSecond["cursor"].pyInt ?? -1))
        for (k, v) in expectedSecond["counts"].object ?? [:] { #expect(second.counts[k] == v.pyInt, "\(k)") }
        #expect(second.alreadyUpToDate)
        await db.close()
    }

    private func expectError(_ code: String, url: URL, peer: PartnerPeer?, me: String) async throws {
        let (db, dir) = try await S.openDB()
        defer { try? FileManager.default.removeItem(at: dir) }
        if let peer { try await db.upsertPartner(peer) }
        do {
            _ = try await PartnerPackageImporter.open(url: url, store: db, me: me)
            Issue.record("expected \(code)")
        } catch let e as PartnerPackageError {
            #expect(e.code == code)
        }
        #expect(try await db.recordCount(peer?.ownerID ?? "") == 0)
        await db.close()
    }

    @Test func oneFlippedByteIsAHashMismatch() async throws {
        let meta = try Self.fixtureMeta()
        let url = try Self.copyFixture()
        var data = try Data(contentsOf: url)
        let reader = try ZipArchiveReader(data: data)
        let entry = try #require(reader.entry(named: "health/vitals.ndjson"))
        let index = Int(entry.dataOffset) + Int(entry.compressedSize) / 2
        data[index] ^= 0x01
        try data.write(to: url)
        try await expectError("hash_mismatch", url: url, peer: Self.senderPeer(meta), me: meta["recipient_device_id"].string ?? "")
    }

    @Test func wrongKeyIsABadSignature() async throws {
        let meta = try Self.fixtureMeta()
        let other = S.identity("0b8f3c2a-5d1e-4c7a-9f00-1a2b3c4d5e6f")
        try await expectError("bad_signature", url: try Self.copyFixture(), peer: Self.senderPeer(meta, ed25519: other.ed25519PublicB64),
                              me: meta["recipient_device_id"].string ?? "")
    }

    @Test func unknownSenderAndWrongRecipientAreRefused() async throws {
        let meta = try Self.fixtureMeta()
        try await expectError("unknown_sender", url: try Self.copyFixture(), peer: nil, me: meta["recipient_device_id"].string ?? "")
        var unpaired = Self.senderPeer(meta)
        unpaired.unpairedMs = S.nowMs
        try await expectError("unknown_sender", url: try Self.copyFixture(), peer: unpaired, me: meta["recipient_device_id"].string ?? "")
        try await expectError("wrong_recipient", url: try Self.copyFixture(), peer: Self.senderPeer(meta), me: "99999999-0000-4000-8000-000000000000")
    }

    @Test func notAZipIsNotAPackage() async throws {
        let dir = try HealthTestFixtures.temporaryDirectory()
        defer { try? FileManager.default.removeItem(at: dir) }
        let url = dir.appendingPathComponent("x.zip")
        try Data("hello".utf8).write(to: url)
        #expect(!PartnerPackageImporter.isPartnerPackage(url))
    }

    @Test func exportImportRoundTripWritesOnlyAllowListedEntries() async throws {
        let p = try await S.pair(grantsAtoB: ["vitals", "nutrition", "medicines"])
        let weights = PartnerFakeSource(type: "weight", (0..<620).map { S.weight(String(format: "w%04d", $0), kg: 60 + Double($0 % 20)) })
        let foods = PartnerFakeSource(type: "food_entry", [S.food("f1"), S.food("f2")])
        let registry = PartnerSourceRegistry([weights, foods])
        _ = try await PartnerLedgerRefresher(store: p.a, registry: registry, now: { S.now }).refresh()
        weights.remove("w0003")
        _ = try await PartnerLedgerRefresher(store: p.a, registry: registry, now: { S.now }).refresh()

        let outDir = try HealthTestFixtures.temporaryDirectory()
        defer { try? FileManager.default.removeItem(at: outDir) }
        let exporter = PartnerPackageExporter(store: p.a, registry: registry, identity: p.idA, senderName: "Ana Maria!", now: { S.now })
        let out = try await exporter.export(recipient: S.peer(p.idB), sinceLastSync: false, directory: outDir)
        #expect(out.url.lastPathComponent.hasPrefix("Ana_Maria_Health_"))
        #expect(out.url.lastPathComponent.hasSuffix(".ayuvo.zip"))
        #expect(out.fromRev == 0)
        #expect(out.counts["vitals"] == 620)       // 619 live + 1 tombstone
        #expect(out.counts["nutrition"] == 2)
        #expect(out.counts["medicines"] == nil)    // granted but empty: no file

        let reader = try ZipArchiveReader(url: out.url)
        let names = reader.entries.map(\.name)
        #expect(names.first == "manifest.json")
        let allowed = Set(PartnerRef.packageEntryNames(out.categories.map(RJ.str)))
        #expect(names.allSatisfy(allowed.contains), "\(names)")
        for entry in reader.entries where entry.name.hasSuffix(".ndjson") {
            try reader.forEachLine(of: entry, maxLineBytes: 1 << 20) { line in
                let env = PartnerJSON.parse(line) ?? .null
                #expect(PartnerRef.envelopeValidate(env, nowMs: Int(S.nowMs))["ok"].bool == true)
            }
        }

        // B imports A's package.
        let preview = try await PartnerPackageImporter.open(url: out.url, store: p.b, me: p.idB.deviceID)
        #expect(preview.total == 622)
        let result = try await PartnerPackageImporter.importPackage(preview, store: p.b, me: p.idB.deviceID, now: { S.now })
        #expect(result.counts["inserted"] == 621)
        #expect(result.counts["duplicate"] == 1)    // the tombstone of a row B never had
        #expect(result.cursor == out.toRev)
        #expect(try await p.b.recordCount(p.idA.deviceID) == 621)
        #expect(try await p.b.record(p.idA.deviceID, type: "weight", recordID: "w0003") == nil)

        // "Changes since last sync" after a session acknowledged everything is empty.
        try await p.a.updateSyncState(p.idB.deviceID) { $0.ackedRev = out.toRev }
        let delta = try await exporter.export(recipient: S.peer(p.idB), sinceLastSync: true, directory: outDir)
        #expect(delta.fromRev == out.toRev)
        #expect(delta.counts.isEmpty)
        await p.cleanup()
    }
}
