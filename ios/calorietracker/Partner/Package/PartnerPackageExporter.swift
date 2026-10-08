import CryptoKit
import Foundation

/// Writes a `.ayuvo.zip` for ONE recipient (docs/partner-sync.md §13): envelopes are streamed from `ledger_delta` in
/// 500-row pages into one NDJSON temp file per category (rev order), each hashed while written; then the zip is
/// assembled with `manifest.json` first, `signature.json` = Ed25519 over the stored manifest bytes, the profile, the
/// revision file and the category files. Only allow-listed entry names are ever written.
nonisolated struct PartnerPackageExporter: Sendable {
    nonisolated struct Output: Sendable {
        let url: URL
        let exportID: String
        let fromRev: Int64
        let toRev: Int64
        let categories: [String]
        let counts: [String: Int]
    }

    let store: PartnerDatabase
    let registry: PartnerSourceRegistry
    let identity: DeviceIdentity
    let senderName: String
    var platform = "ios"
    var now: @Sendable () -> Date = { Date() }

    private final class CategoryFile {
        let url: URL
        let handle: FileHandle
        var hasher = SHA256()
        var bytes = 0
        var count = 0

        init(url: URL) throws {
            FileManager.default.createFile(atPath: url.path, contents: nil)
            self.url = url
            handle = try FileHandle(forWritingTo: url)
        }

        func append(_ line: Data) throws {
            try handle.write(contentsOf: line)
            hasher.update(data: line)
            bytes += line.count
            count += 1
        }
    }

    static func jsonFile(_ value: RJ) -> Data {
        var d = PartnerJSON.canonicalData(value)
        d.append(0x0A)
        return d
    }

    /// `sinceLastSync`: only changes after the recipient's `acked_rev`; otherwise everything shared (`from_rev = 0`).
    func export(recipient: PartnerPeer, sinceLastSync: Bool, directory: URL) async throws -> Output {
        let fm = FileManager.default
        let work = fm.temporaryDirectory.appendingPathComponent("partner-export-\(UUID().uuidString.lowercased())", isDirectory: true)
        try fm.createDirectory(at: work, withIntermediateDirectories: true)
        defer { try? fm.removeItem(at: work) }

        let nowDate = now()
        let stamp = Int64((nowDate.timeIntervalSince1970 * 1000).rounded())
        let grants = try await store.grantsOut(recipient.ownerID)
        let start: Int64 = sinceLastSync ? (try await store.syncState(recipient.ownerID)?.ackedRev ?? 0) : 0

        var files: [String: CategoryFile] = [:]
        var cursor = start
        var fromRev: Int64?
        var toRev = start
        while true {
            let page = try await store.ledgerDeltaPage(cursor: cursor, grants: grants)
            if fromRev == nil { fromRev = page.fromRev }
            for row in page.rows {
                let env = await registry.envelope(type: row.type, recordID: row.recordID, ledgerCategory: row.category, rev: row.rev,
                                                  deleted: row.deleted, nowMs: stamp)
                let category = env["category"].string ?? row.category
                guard grants.contains(category) else { continue }
                let file: CategoryFile
                if let f = files[category] {
                    file = f
                } else {
                    file = try CategoryFile(url: work.appendingPathComponent("\(category).ndjson"))
                    files[category] = file
                }
                try file.append(Self.jsonFile(env))
            }
            toRev = max(page.toRev, page.fromRev)
            cursor = toRev
            if !page.hasMore { break }
        }
        for f in files.values { try f.handle.close() }

        let exportID = UUID().uuidString.lowercased()
        let profile = Self.jsonFile(.obj([
            "device_id": .str(identity.deviceID), "name": .str(PartnerRef.cleanName(senderName)), "platform": .str(platform),
            "fingerprint": .str(identity.fingerprint),
        ]))
        let from = fromRev ?? start
        let revision = Self.jsonFile(.obj(["from_rev": .int(Int(from)), "to_rev": .int(Int(toRev))]))
        var listed: [RJ] = [
            .obj(["name": .str("profile/partner.json"), "sha256": .str(PartnerEncoding.hex(SHA256.hash(data: profile))),
                  "bytes": .int(profile.count), "count": .int(1)]),
            .obj(["name": .str("sync/revision.json"), "sha256": .str(PartnerEncoding.hex(SHA256.hash(data: revision))),
                  "bytes": .int(revision.count), "count": .int(1)]),
        ]
        var counts: [String: Int] = [:]
        let order = PartnerCatalog.shared.categories.filter { files[$0] != nil }
        for cat in order {
            guard let f = files[cat] else { continue }
            listed.append(.obj(["name": .str("health/\(cat).ndjson"), "sha256": .str(PartnerEncoding.hex(f.hasher.finalize())),
                                "bytes": .int(f.bytes), "count": .int(f.count)]))
            counts[cat] = f.count
        }
        let manifest = Self.jsonFile(.obj([
            "format": .str(PartnerCatalog.shared.packageFormat), "version": .int(PartnerCatalog.shared.packageVersion),
            "export_id": .str(exportID), "device_id": .str(identity.deviceID), "recipient_device_id": .str(recipient.ownerID),
            "created_ms": .int(Int(stamp)), "from_rev": .int(Int(from)), "to_rev": .int(Int(toRev)),
            "categories": .arr(PartnerCatalog.shared.categories.filter(grants.contains).map(RJ.str)), "files": .arr(listed),
        ]))
        let signature = Self.jsonFile(.obj([
            "alg": .str("Ed25519"), "key": .str(identity.ed25519PublicB64),
            "sig": .str(PartnerEncoding.b64urlEncode(try identity.sign(manifest))),
        ]))

        try fm.createDirectory(at: directory, withIntermediateDirectories: true)
        let filename = PartnerRef.packageFilename(name: .str(senderName), day: PartnerDay.string(nowDate))
        let url = directory.appendingPathComponent(filename)
        try? fm.removeItem(at: url)
        let writer = try ZipArchiveWriter(url: url)
        try writer.addStored(name: "manifest.json", data: manifest)
        try writer.addStored(name: "signature.json", data: signature)
        try writer.addStored(name: "profile/partner.json", data: profile)
        try writer.addStored(name: "sync/revision.json", data: revision)
        for cat in order {
            guard let f = files[cat] else { continue }
            try writer.addStored(name: "health/\(cat).ndjson", fileURL: f.url)
        }
        try writer.finish()
        return Output(url: url, exportID: exportID, fromRev: from, toRev: toRev, categories: PartnerCatalog.shared.categories.filter(grants.contains),
                      counts: counts)
    }
}
