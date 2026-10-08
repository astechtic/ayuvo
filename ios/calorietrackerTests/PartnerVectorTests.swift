import Foundation
import Testing
@testable import calorietracker

/// Runs every case of `shared/partner/test-vectors/*.json` (except the cacophony Noise files, replayed by
/// `PartnerNoiseTests`) through the Swift port (`PartnerRef`) and requires exact equality with the reference
/// output (numbers by value, object keys unordered, array order significant).
struct PartnerVectorTests {
    static let vectorFiles = [
        "qr", "kdf", "envelope_validate", "merge_apply", "grants_received", "retention_prune", "ledger", "map",
        "message_validate", "session", "package", "summary_metrics",
    ]

    static var vectorsDirectory: URL {
        HealthTestFixtures.repoRootURL.appendingPathComponent("shared/partner/test-vectors")
    }

    static func load(_ name: String) throws -> RJ {
        let data = try Data(contentsOf: vectorsDirectory.appendingPathComponent("\(name).json"))
        return try #require(PartnerJSON.parse(data), "unreadable \(name).json")
    }

    /// Every vector file in shared/partner/test-vectors must have a runner.
    @Test func everySharedVectorFileHasARunner() throws {
        let names = try FileManager.default.contentsOfDirectory(atPath: Self.vectorsDirectory.path)
            .filter { $0.hasSuffix(".json") }
            .map { String($0.dropLast(5)) }
        #expect(!names.isEmpty)
        for name in names.sorted() {
            #expect(Self.vectorFiles.contains(name) || PartnerNoiseTests.files.contains(name), "no Swift runner for test-vectors/\(name).json")
        }
    }

    @Test(arguments: vectorFiles)
    func vectorFileMatchesReference(_ name: String) throws {
        let root = try Self.load(name)
        #expect(root["format"].string == "ayuvo-partner-vectors")
        let function = try #require(root["function"].string)
        let cases = root["cases"].array ?? []
        #expect(!cases.isEmpty)
        var passed = 0
        var failures: [String] = []
        for c in cases {
            let actual = PartnerRef.runCase(function: function, input: c["input"])
            if let diff = RecordsVectorTests.firstDifference(actual, c["expected"]) {
                failures.append("\(c["name"].string ?? "?"): \(diff)")
            } else {
                passed += 1
            }
        }
        print("PARTNER-VECTORS \(name).json \(passed)/\(cases.count)")
        let report = "\(name).json \(passed)/\(cases.count) passed\n" + failures.joined(separator: "\n")
        #expect(failures.isEmpty, Comment(rawValue: report))
    }

    /// Re-applying every accepted merge vector's batch on its own result changes nothing (docs §8).
    @Test func mergeIsIdempotent() throws {
        let root = try Self.load("merge_apply")
        var checked = 0
        for c in root["cases"].array ?? [] {
            let inp = c["input"], exp = c["expected"]
            guard exp["accepted"].bool == true else { continue }
            var stored: [PartnerKey: RJ] = [:]
            for r in inp["existing"].array ?? [] { stored[PartnerKey(r["type"], r["record_id"])] = r["rev"] }
            for d in exp["deletes"].array ?? [] { stored[PartnerKey(d["type"], d["record_id"])] = nil }
            for u in exp["upserts"].array ?? [] { stored[PartnerKey(u["type"], u["record_id"])] = u["rev"] }
            let existing = stored.keys.sorted().map { k in RJ.obj(["type": k.type, "record_id": k.recordID, "rev": stored[k]!]) }
            let again = PartnerRef.mergeApply(existing: existing, batch: inp["batch"], cursor: exp["cursor"].pyInt ?? 0,
                                              granted: inp["granted"].array ?? [], nowMs: inp["now_ms"].pyInt ?? 0)
            #expect(again["upserts"].array?.isEmpty == true, "\(c["name"].string ?? "?") re-upserts")
            #expect(again["deletes"].array?.isEmpty == true, "\(c["name"].string ?? "?") re-deletes")
            #expect(again["cursor"].pyInt == exp["cursor"].pyInt)
            checked += 1
        }
        #expect(checked > 0)
    }

    /// No mapped envelope contains a forbidden key, and every one validates.
    @Test func mappedEnvelopesArePrivateAndValid() throws {
        let root = try Self.load("map")
        for c in root["cases"].array ?? [] {
            let env = PartnerRef.runCase(function: "map", input: c["input"])["envelope"]
            guard !env.isNull, var obj = env.object else { continue }
            #expect(!PartnerRef.hasForbiddenKey(env["data"]), "\(c["name"].string ?? "?") leaks a forbidden key")
            obj["rev"] = .int(1)
            obj["deleted"] = .bool(false)
            obj["updated_ms"] = .int(0)
            #expect(PartnerRef.envelopeValidate(.obj(obj), nowMs: 0)["ok"].bool == true, "\(c["name"].string ?? "?") fails validation")
        }
    }

    @Test func bundledSharedFilesAreByteIdentical() throws {
        let shared = HealthTestFixtures.repoRootURL.appendingPathComponent("shared/partner")
        let resources = HealthTestFixtures.repoRootURL.appendingPathComponent("ios/calorietracker/Partner/Resources")
        for (sharedName, bundled) in [("record_types.json", "partner_record_types.json"), ("protocol.json", "partner_protocol.json")] {
            let a = try Data(contentsOf: shared.appendingPathComponent(sharedName))
            let b = try Data(contentsOf: resources.appendingPathComponent(bundled))
            #expect(a == b, "\(bundled) differs from shared/partner/\(sharedName)")
        }
        #expect(PartnerCatalog.shared.categories == ["vitals", "sleep", "nutrition", "workouts", "medicines", "report_overviews"])
        #expect(PartnerCatalog.shared.types.count == 14)
        #expect(PartnerCatalog.shared.prologue == "ayuvo-partner-sync/1")
    }

    @Test func canonicalJSONMatchesPythonDumps() {
        let value: RJ = .obj(["b": .str("a/b \"q\" \u{1}\n é"), "a": .arr([.int(1), .num(1.5), .num(2.0), .bool(true), .null]),
                              "é": .int(0), "Z": .num(1e16)])
        #expect(PartnerJSON.canonical(value) == "{\"Z\":1e+16,\"a\":[1,1.5,2.0,true,null],\"b\":\"a/b \\\"q\\\" \\u0001\\n é\",\"é\":0}")
        #expect(PartnerJSON.parse("5.0") .map { if case .num = $0 { return true } else { return false } } == true)
        #expect(PartnerJSON.parse("5")?.pyInt == 5)
        #expect(PartnerJSON.parse("{\"a\":1,\"a\":2}")?["a"].pyInt == 2)
        #expect(PartnerJSON.parse("\"\u{1}\"") == nil)
    }

    @Test func keysSortByUTF8Bytes() {
        // UTF-16 order would put U+FF21 (fullwidth A) after U+1F600; code point / UTF-8 order puts it first.
        let a = PartnerKey(type: "t", recordID: "\u{FF21}"), b = PartnerKey(type: "t", recordID: "\u{1F600}")
        #expect(a < b)
        #expect(PartnerKey(type: "t", recordID: "Z") < PartnerKey(type: "t", recordID: "a"))
        #expect(PartnerKey(type: "t", recordID: "e\u{301}") != PartnerKey(type: "t", recordID: "\u{E9}"))
    }

    @Test func base64urlIsStrict() {
        #expect(PartnerEncoding.b64urlDecode("QUJD") == Data("ABC".utf8))
        #expect(PartnerEncoding.b64urlDecode("QQ") == Data("A".utf8))
        #expect(PartnerEncoding.b64urlDecode("QQ==") == nil)
        #expect(PartnerEncoding.b64urlDecode("Q") == nil)
        #expect(PartnerEncoding.b64urlDecode("QUJD\n") == nil)
        #expect(PartnerEncoding.b64urlDecode("QU+D") == nil)
        #expect(PartnerEncoding.b64urlDecode("") == nil)
        let raw = Data((0..<64).map { UInt8($0 * 4 % 256) })
        #expect(PartnerEncoding.b64urlDecode(PartnerEncoding.b64urlEncode(raw)) == raw)
    }

    @Test func hkdfMatchesRFC5869TestCase1() {
        let ikm = Data(repeating: 0x0b, count: 22)
        let salt = PartnerEncoding.fromHex("000102030405060708090a0b0c")!
        let info = PartnerEncoding.fromHex("f0f1f2f3f4f5f6f7f8f9")!
        let okm = PartnerRef.hkdfSHA256(ikm: ikm, salt: salt, info: info, length: 42)
        #expect(PartnerEncoding.hex(okm) == "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865")
    }

    @Test func qrRoundTrip() {
        let payload: RJ = .obj([
            "protocol": .str("ayuvo-partner-sync"), "v": .int(1), "device_id": .str("0b8f3c2a-5d1e-4c7a-9f00-1a2b3c4d5e6f"),
            "name": .str("  Ana  /  B "), "x25519": .str(PartnerEncoding.b64urlEncode(Data(repeating: 1, count: 32))),
            "ed25519": .str(PartnerEncoding.b64urlEncode(Data(repeating: 2, count: 32))),
            "token": .str(PartnerEncoding.b64urlEncode(Data(repeating: 3, count: 32))), "exp_ms": .int(1_000_600_000),
            "hosts": .arr([.str("192.168.1.2:5000")]),
        ])
        let parsed = PartnerRef.qrParse(.str(PartnerRef.qrEncode(payload)), nowMs: 1_000_000_000, selfDeviceID: .str("x"))
        #expect(parsed["ok"].bool == true)
        #expect(parsed["payload"]["name"].string == "Ana / B")
    }
}
