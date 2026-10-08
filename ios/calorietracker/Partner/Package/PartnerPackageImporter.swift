import CryptoKit
import Foundation

/// What the confirmation sheet shows before anything is imported ("Health data from <name> · N records").
nonisolated struct PartnerPackagePreview: Sendable {
    let url: URL
    let manifest: RJ
    let senderID: String
    let senderName: String
    let exportID: String
    let createdMs: Int64
    let total: Int
    let perCategory: [(category: String, count: Int)]
}

nonisolated struct PartnerImportOutcome: Sendable, Equatable {
    var accepted = false
    var error: String?
    var cursor: Int64 = 0
    var counts: [String: Int] = [:]
    var rejected = 0

    /// Everything merged as duplicate / stale (a package older than the cursor).
    var alreadyUpToDate: Bool { accepted && (counts["inserted"] ?? 0) + (counts["updated"] ?? 0) + (counts["deleted"] ?? 0) == 0 }
}

nonisolated struct PartnerPackageError: Error, Sendable, Equatable {
    /// `package_validate` codes, or `too_large` / `unreadable`.
    let code: String
}

/// Reads `.ayuvo.zip` packages (docs/partner-sync.md §13): size / entry limits, SHA-256 of every entry, the Ed25519
/// signature over the stored manifest bytes with the STORED partner key, `package_validate`, then a preview; the
/// import feeds the NDJSON files in 500-envelope batches through `merge_apply` against the pre-import cursor and
/// commits the cursor only with the last batch.
nonisolated enum PartnerPackageImporter {
    static let maxFileBytes = 1 << 30            // 1 GiB package
    static let maxEntries = 64
    static let maxManifestBytes = 1 << 20        // 1 MiB
    static let maxEntryBytes = 512 << 20         // 512 MiB uncompressed per entry
    static let maxLineBytes = 1 << 20

    /// True when the zip carries a `manifest.json` with format `ayuvo-partner-sync` (no other entry is read).
    static func isPartnerPackage(_ url: URL) -> Bool {
        guard let size = (try? FileManager.default.attributesOfItem(atPath: url.path)[.size] as? NSNumber)?.intValue,
              size <= maxFileBytes,
              let reader = try? ZipArchiveReader(url: url),
              let entry = reader.entry(named: "manifest.json"), entry.uncompressedSize <= UInt64(maxManifestBytes),
              let data = try? reader.data(for: entry), let manifest = PartnerJSON.parse(data)
        else { return false }
        return manifest["format"].string == PartnerCatalog.shared.packageFormat
    }

    private static func sha256(_ reader: ZipArchiveReader, _ entry: ZipEntry) throws -> String {
        var hasher = SHA256()
        try reader.forEachChunk(of: entry) { hasher.update(data: $0) }
        return PartnerEncoding.hex(hasher.finalize())
    }

    /// Validates the package completely and returns the preview (nothing is written).
    static func open(url: URL, store: PartnerDatabase, me: String) async throws -> PartnerPackagePreview {
        let size = (try? FileManager.default.attributesOfItem(atPath: url.path)[.size] as? NSNumber)?.intValue ?? 0
        guard size <= maxFileBytes else { throw PartnerPackageError(code: "too_large") }
        let reader: ZipArchiveReader
        do { reader = try ZipArchiveReader(url: url) } catch { throw PartnerPackageError(code: "not_package") }
        guard reader.entries.count <= maxEntries else { throw PartnerPackageError(code: "unexpected_entry") }
        guard reader.entries.allSatisfy({ $0.uncompressedSize <= UInt64(maxEntryBytes) }) else { throw PartnerPackageError(code: "too_large") }

        var manifestBytes = Data()
        if let entry = reader.entry(named: "manifest.json") {
            guard entry.uncompressedSize <= UInt64(maxManifestBytes) else { throw PartnerPackageError(code: "too_large") }
            manifestBytes = (try? reader.data(for: entry)) ?? Data()
        }
        let manifest = PartnerJSON.parse(manifestBytes) ?? .null

        var entries: [RJ] = []
        for entry in reader.entries {
            let hash = (try? sha256(reader, entry)) ?? ""
            entries.append(.obj(["name": .str(entry.name), "sha256": .str(hash)]))
        }

        let trusted = try await store.trustedOwnerIDs()
        var signatureOK = false
        if let sender = manifest["device_id"].string, let peer = try await store.partner(sender), peer.isTrusted,
           let sigEntry = reader.entry(named: "signature.json"), sigEntry.uncompressedSize <= UInt64(maxManifestBytes),
           let sigData = try? reader.data(for: sigEntry), let sig = PartnerJSON.parse(sigData),
           sig["alg"].string == "Ed25519", sig["key"].string == peer.ed25519Pub, let s = sig["sig"].string {
            // Verified against the key stored at pairing, over the manifest bytes exactly as stored.
            signatureOK = DeviceIdentity.verify(signatureB64: s, message: manifestBytes, publicKeyB64: peer.ed25519Pub)
        }
        let v = PartnerRef.packageValidate(manifest: manifest, entries: entries, signatureOK: signatureOK,
                                           partners: trusted.map(RJ.str), me: .str(me))
        guard v["ok"].bool == true else { throw PartnerPackageError(code: v["error"].string ?? "malformed") }

        let sender = manifest["device_id"].string ?? ""
        let name = try await store.partner(sender)?.displayName ?? "Partner"
        let summary = PartnerRef.packageSummary(files: manifest["files"].array ?? [], categories: manifest["categories"].array ?? [])
        return PartnerPackagePreview(
            url: url, manifest: manifest, senderID: sender, senderName: name, exportID: manifest["export_id"].string ?? "",
            createdMs: Int64(manifest["created_ms"].pyInt ?? 0), total: summary["total"].pyInt ?? 0,
            perCategory: (summary["per_category"].array ?? []).map { ($0["category"].string ?? "", $0["count"].pyInt ?? 0) })
    }

    /// `package_import`: merges the validated package. The zip is re-validated first (it may have changed on disk).
    static func importPackage(_ preview: PartnerPackagePreview, store: PartnerDatabase, me: String, batchSize: Int = PartnerCatalog.shared.batchMax,
                              now: @escaping @Sendable () -> Date = { Date() }) async throws -> PartnerImportOutcome {
        let checked = try await open(url: preview.url, store: store, me: me)
        let manifest = checked.manifest
        let sender = checked.senderID
        let nowMs = Int64((now().timeIntervalSince1970 * 1000).rounded())
        let categories = (manifest["categories"].array ?? []).compactMap(\.string)
        let fromRev = Int64(manifest["from_rev"].pyInt ?? 0)
        let toRev = Int64(manifest["to_rev"].pyInt ?? 0)
        try await store.updateGrantsReceived(sender, grantedNow: categories, nowMs: nowMs)
        let preCursor = try await store.cursor(sender)

        let merged = try await store.mergePackageFiles(url: preview.url, ownerID: sender, categories: categories, fromRev: fromRev, toRev: toRev,
                                                       cursor: preCursor, batchSize: batchSize, nowMs: nowMs)
        guard merged.error == nil else { throw PartnerPackageError(code: merged.error ?? "malformed") }
        var outcome = merged
        outcome.accepted = true
        for k in PartnerRef.countKeys where outcome.counts[k] == nil { outcome.counts[k] = 0 }

        _ = try await store.updateSyncState(sender) { st in
            st.status = "up_to_date"
            st.lastSyncMs = nowMs
            st.lastTransport = "package"
            st.lastExportID = checked.exportID
            st.lastError = nil
        }
        _ = try await store.retentionPrune(nowMs: nowMs)
        return outcome
    }
}

extension PartnerDatabase {
    /// Streams the category files of a validated package (category order, file order) in `batchSize` batches. Every
    /// batch is merged against the PRE-IMPORT `cursor` with the manifest's from/to revs and categories and written in
    /// its own transaction; the cursor commits only with the last batch, so an interrupted import re-runs as
    /// duplicates. Runs inside the actor, so only one batch of lines is in memory at a time.
    func mergePackageFiles(url: URL, ownerID: String, categories: [String], fromRev: Int64, toRev: Int64, cursor: Int64,
                           batchSize: Int, nowMs: Int64) throws -> PartnerImportOutcome {
        let reader = try ZipArchiveReader(url: url)
        var outcome = PartnerImportOutcome()
        var pending: [RJ]?
        var batch: [RJ] = []
        var failure: String?

        func flush(_ records: [RJ], last: Bool) throws {
            guard failure == nil else { return }
            let r = try mergePackageBatch(ownerID: ownerID, records: records, fromRev: fromRev, toRev: toRev, cursor: cursor,
                                          granted: categories, commitCursor: last, nowMs: nowMs)
            guard r.accepted else {
                failure = r.error ?? "malformed"
                return
            }
            for (k, v) in r.counts { outcome.counts[k, default: 0] += v }
            outcome.rejected += r.rejected.count
            outcome.cursor = r.cursor
        }

        for cat in PartnerCatalog.shared.categories where categories.contains(cat) {
            guard let entry = reader.entry(named: "health/\(cat).ndjson") else { continue }
            try reader.forEachLine(of: entry, maxLineBytes: PartnerPackageImporter.maxLineBytes) { line in
                guard !line.isEmpty else { return }
                batch.append(PartnerJSON.parse(line) ?? .null)
                if batch.count >= batchSize {
                    if let p = pending { try flush(p, last: false) }
                    pending = batch
                    batch = []
                }
            }
        }
        if !batch.isEmpty {
            if let p = pending { try flush(p, last: false) }
            pending = batch
        }
        try flush(pending ?? [], last: true)
        outcome.error = failure
        return outcome
    }

    /// One `package_import` batch in ONE transaction: stored revs for the batch keys, `merge_apply` against the
    /// pre-import cursor with the manifest's from/to revs and categories, the writes, and the cursor only when
    /// `commitCursor` (the last batch).
    func mergePackageBatch(ownerID: String, records: [RJ], fromRev: Int64, toRev: Int64, cursor: Int64, granted: [String],
                           commitCursor: Bool, nowMs: Int64) throws -> PartnerMergeOutcome {
        try connection.inTransaction {
            let keys: [(type: String, recordID: String)] = records.compactMap { env in
                guard let t = env["type"].string, let id = env["id"].string else { return nil }
                return (t, id)
            }
            let existing = try existingRevs(ownerID, keys: keys)
            let batch: RJ = .obj(["from_rev": .int(Int(fromRev)), "to_rev": .int(Int(toRev)), "records": .arr(records)])
            let outcome = PartnerMergeOutcome(PartnerRef.mergeApply(existing: existing, batch: batch, cursor: Int(cursor),
                                                                    granted: granted.map(RJ.str), nowMs: Int(nowMs)))
            if outcome.accepted {
                try writeMerge(ownerID: ownerID, result: outcome, commitCursor: commitCursor, afterEachWrite: nil)
            }
            return outcome
        }
    }
}
