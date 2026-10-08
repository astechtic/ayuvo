import Foundation

// Swift port of `scripts/partner_reference.py` §8 (merge engine, grants, retention), §10 (outbound ledger)
// and `package_import` (§13). Output rows are sorted by (type, record_id) in UTF-8 byte order (`PartnerKey`).

nonisolated extension PartnerRef {
    static let countKeys = ["inserted", "updated", "deleted", "duplicate", "stale", "rejected"]

    private static func countsRJ(_ c: [String: Int]) -> RJ { .obj(c.mapValues { RJ.int($0) }) }

    private static func zeroCounts() -> [String: Int] { Dictionary(uniqueKeysWithValues: countKeys.map { ($0, 0) }) }

    private static func mergeRefusal(_ error: String, cursor: Int, counts: [String: Int]) -> RJ {
        .obj(["accepted": .bool(false), "error": .str(error), "cursor": .int(cursor), "upserts": .arr([]), "deletes": .arr([]),
              "counts": countsRJ(counts), "rejected": .arr([])])
    }

    // MARK: §8 merge_apply

    /// Apply one CHANGES batch from one owner (see the reference docstring).
    static func mergeApply(existing: [RJ], batch: RJ, cursor: Int, granted: [RJ], nowMs: Int) -> RJ {
        var counts = zeroCounts()
        guard let fromRev = batch.get("from_rev")?.pyInt, let toRev = batch.get("to_rev")?.pyInt, toRev >= fromRev,
              let records = batch.get("records")?.array
        else { return mergeRefusal("malformed", cursor: cursor, counts: counts) }
        if fromRev > cursor { return mergeRefusal("cursor_gap", cursor: cursor, counts: counts) }

        var state: [PartnerKey: Int] = [:]
        for r in existing {
            state[PartnerKey(r["type"], r["record_id"])] = r["rev"].pyInt ?? Int(r["rev"].pyNumber ?? 0)
        }
        var seen: [PartnerKey: Int] = [:]
        var upserts: [PartnerKey: RJ] = [:]
        var deletes: [PartnerKey: RJ] = [:]
        var rejected: [RJ] = []

        for env in records {
            let v = envelopeValidate(env, nowMs: nowMs)
            let rtype: RJ = env.isObject ? (env.get("type") ?? .null) : .null
            let rid: RJ = env.isObject ? (env.get("id") ?? .null) : .null
            if !(v["ok"].bool ?? false) {
                counts["rejected", default: 0] += 1
                rejected.append(.obj(["type": rtype, "id": rid, "reason": v["error"]]))
                continue
            }
            if !pyIn(v["category"], granted) {
                counts["rejected", default: 0] += 1
                rejected.append(.obj(["type": rtype, "id": rid, "reason": .str("not_granted")]))
                continue
            }
            let rev = env["rev"].pyInt ?? 0
            if rev > toRev {
                counts["rejected", default: 0] += 1
                rejected.append(.obj(["type": rtype, "id": rid, "reason": .str("rev_out_of_range")]))
                continue
            }
            let key = PartnerKey(rtype, rid)
            let stored = state[key]
            if let applied = seen[key] {
                if rev <= applied {
                    counts[rev < applied ? "stale" : "duplicate", default: 0] += 1
                    continue
                }
            } else if let stored {
                if rev == stored { counts["duplicate", default: 0] += 1; continue }
                if rev < stored { counts["stale", default: 0] += 1; continue }
            } else if rev <= cursor {
                // Already committed once and gone since (deleted, pruned or purged): never resurrect it.
                counts["stale", default: 0] += 1
                continue
            }
            seen[key] = rev
            let exists = upserts[key] != nil || (stored != nil && deletes[key] == nil)
            if env["deleted"].bool == true {
                upserts[key] = nil
                if exists {
                    if stored != nil { deletes[key] = .obj(["type": rtype, "record_id": rid]) }
                    counts["deleted", default: 0] += 1
                } else {
                    counts["duplicate", default: 0] += 1
                }
                continue
            }
            deletes[key] = nil
            let updatedMs = min(env["updated_ms"].pyInt ?? 0, nowMs + PartnerCatalog.futureSkewMs)
            upserts[key] = .obj([
                "type": rtype, "record_id": rid, "category": v["category"], "rev": .int(rev), "day": env.get("day") ?? .null,
                "ts_ms": envelopeTs(env).map(RJ.int) ?? .null, "updated_ms": .int(updatedMs), "data": env["data"],
            ])
            counts[exists ? "updated" : "inserted", default: 0] += 1
        }
        return .obj([
            "accepted": .bool(true), "error": .null, "cursor": .int(max(cursor, toRev)),
            "upserts": .arr(upserts.keys.sorted().map { upserts[$0]! }),
            "deletes": .arr(deletes.keys.sorted().map { deletes[$0]! }),
            "counts": countsRJ(counts), "rejected": .arr(rejected),
        ])
    }

    // MARK: §13 package_import

    /// Merge a validated package. `files`: {category: [envelope]} in file order. Every batch merges against the
    /// pre-import cursor with the manifest's from_rev/to_rev; the cursor is committed only with the last batch.
    static func packageImport(existing: [RJ], cursor: Int, manifest: RJ, files: RJ, nowMs: Int, batchSize: Int) -> RJ {
        var counts = zeroCounts()
        let granted = manifest["categories"].array ?? []
        var stored: [PartnerKey: RJ] = [:]
        for r in existing { stored[PartnerKey(r["type"], r["record_id"])] = r["rev"] }
        var finalUp: [PartnerKey: RJ] = [:]
        var finalDel: [PartnerKey: RJ] = [:]
        var rejected: [RJ] = []
        var records: [RJ] = []
        let fileMap = files.object ?? [:]
        for cat in catalog.categories {
            if let entry = fileMap.first(where: { utf8Equal($0.key, cat) }) { records.append(contentsOf: entry.value.array ?? []) }
        }
        let size = max(1, batchSize)
        var batches: [[RJ]] = stride(from: 0, to: records.count, by: size).map { Array(records[$0..<min($0 + size, records.count)]) }
        if batches.isEmpty { batches = [[]] }
        for chunk in batches {
            let current = stored.keys.sorted().map { k in RJ.obj(["type": k.type, "record_id": k.recordID, "rev": stored[k]!]) }
            let res = mergeApply(existing: current, batch: .obj(["from_rev": manifest["from_rev"], "to_rev": manifest["to_rev"], "records": .arr(chunk)]),
                                 cursor: cursor, granted: granted, nowMs: nowMs)
            if res["accepted"].bool != true {
                return .obj(["accepted": .bool(false), "error": res["error"], "cursor": .int(cursor), "counts": countsRJ(counts),
                             "rejected": .arr([]), "upserts": .arr([]), "deletes": .arr([])])
            }
            for k in countKeys { counts[k, default: 0] += res["counts"][k].pyInt ?? 0 }
            rejected.append(contentsOf: res["rejected"].array ?? [])
            for d in res["deletes"].array ?? [] {
                let key = PartnerKey(d["type"], d["record_id"])
                stored[key] = nil
                finalUp[key] = nil
                finalDel[key] = d
            }
            for u in res["upserts"].array ?? [] {
                let key = PartnerKey(u["type"], u["record_id"])
                stored[key] = u["rev"]
                finalDel[key] = nil
                finalUp[key] = u
            }
        }
        let toRev = manifest["to_rev"].pyInt ?? cursor
        return .obj([
            "accepted": .bool(true), "error": .null, "cursor": .int(max(cursor, toRev)), "counts": countsRJ(counts),
            "rejected": .arr(rejected), "upserts": .arr(finalUp.keys.sorted().map { finalUp[$0]! }),
            "deletes": .arr(finalDel.keys.sorted().map { finalDel[$0]! }),
        ])
    }

    // MARK: §9 grants_received_update

    /// Rows for every category in CATEGORIES order. A category that was granted and is no longer gets
    /// revoked_ms = now (data kept); re-granting clears revoked_ms.
    static func grantsReceivedUpdate(previous: [RJ], grantedNow: [RJ], nowMs: Int) -> [RJ] {
        var prev = PyOrderedDict<RJ>()
        for p in previous { if case .str(let c) = p["category"] { prev[c] = p } }
        return catalog.categories.map { cat in
            let p = prev[cat] ?? .obj(["granted": .bool(false), "revoked_ms": .null])
            if pyIn(.str(cat), grantedNow) {
                return .obj(["category": .str(cat), "granted": .bool(true), "revoked_ms": .null])
            }
            if p["granted"].truthy {
                return .obj(["category": .str(cat), "granted": .bool(false), "revoked_ms": .int(nowMs)])
            }
            return .obj(["category": .str(cat), "granted": .bool(false), "revoked_ms": p["revoked_ms"]])
        }
    }

    // MARK: §8 retention_prune

    /// Keys of intraday rows (types with retention_days) older than the window, by ts_ms.
    static func retentionPrune(rows: [RJ], nowMs: Int) -> [RJ] {
        rows.compactMap { r in
            guard let days = catalog.spec(r["type"])?.retentionDays, days != 0, let ts = r["ts_ms"].pyInt,
                  ts < nowMs - days * PartnerCatalog.dayMs else { return nil }
            return .obj(["type": r["type"], "record_id": r["record_id"]])
        }
    }

    // MARK: §10 Outbound ledger

    /// Bring the ledger in line with the current shareable records (see the reference docstring).
    static func ledgerRefresh(ledger: [RJ], rev startRev: Int, current: [RJ], scopes: [RJ]) -> RJ {
        var rev = startRev
        var byKey: [PartnerKey: RJ] = [:]
        for r in ledger { byKey[PartnerKey(r["type"], r["record_id"])] = r }
        var scopeOf: [PartnerKey: RJ] = [:]      // keyed by type only (recordID .null)
        for s in scopes { scopeOf[PartnerKey(s["type"], .null)] = s.get("day_from") ?? .null }
        var seen = Set<PartnerKey>()
        var changed: [RJ] = []
        let sortedCurrent = current.map { (PartnerKey($0["type"], $0["record_id"]), $0) }
        for (key, c) in pyStableSortedKeys(sortedCurrent) {
            seen.insert(key)
            let old = byKey[key]
            if let old {
                let differs = old["deleted"].truthy || !pyEq(old["content_hash"], c["content_hash"])
                    || !pyEq(old["category"], c["category"]) || !pyEq(old.get("day") ?? .null, c.get("day") ?? .null)
                if differs { changed.append(c) }
            } else {
                changed.append(c)
            }
        }
        var tomb: [PartnerKey] = []
        for key in byKey.keys.sorted() {
            let r = byKey[key]!
            if r["deleted"].truthy || seen.contains(key) { continue }
            guard let dayFrom = scopeOf[PartnerKey(r["type"], .null)] else { continue }
            if !dayFrom.isNull, let day = r.get("day"), !day.isNull, pyLess(day, dayFrom) { continue }
            tomb.append(key)
        }
        for c in changed {
            rev += 1
            byKey[PartnerKey(c["type"], c["record_id"])] = .obj([
                "type": c["type"], "record_id": c["record_id"], "category": c["category"], "day": c.get("day") ?? .null,
                "content_hash": c["content_hash"], "rev": .int(rev), "deleted": .bool(false),
            ])
        }
        for key in tomb {
            rev += 1
            var r = byKey[key]!.object ?? [:]
            r["rev"] = .int(rev)
            r["deleted"] = .bool(true)
            byKey[key] = .obj(r)
        }
        return .obj([
            "rev": .int(rev), "ledger": .arr(byKey.keys.sorted().map { byKey[$0]! }),
            "changed": .int(changed.count), "tombstoned": .int(tomb.count),
        ])
    }

    /// Stable sort of (key, value) pairs by key.
    private static func pyStableSortedKeys(_ items: [(PartnerKey, RJ)]) -> [(PartnerKey, RJ)] {
        items.enumerated().sorted { l, r in
            if l.element.0 != r.element.0 { return l.element.0 < r.element.0 }
            return l.offset < r.offset
        }.map(\.element)
    }

    /// Local cleanup: drop intraday-type ledger rows whose day is before the window (no rev assigned).
    static func ledgerPrune(ledger: [RJ], intradayDayFrom: RJ) -> [RJ] {
        ledger.filter { r in
            guard let days = catalog.spec(r["type"])?.retentionDays, days != 0,
                  let day = r.get("day"), !day.isNull else { return true }
            return !pyLess(day, intradayDayFrom)
        }
    }

    /// Re-granting a category: every ledger row of the category gets a fresh rev, in (type, record_id) order.
    static func ledgerRegrant(ledger: [RJ], rev startRev: Int, category: RJ) -> RJ {
        var rev = startRev
        let sorted = pyStableSortedKeys(ledger.map { (PartnerKey($0["type"], $0["record_id"]), $0) })
        let out: [RJ] = sorted.map { _, r in
            guard pyEq(r["category"], category), var o = r.object else { return r }
            rev += 1
            o["rev"] = .int(rev)
            return .obj(o)
        }
        return .obj(["rev": .int(rev), "ledger": .arr(out)])
    }

    /// One CHANGES page: rows with rev > cursor in granted categories, ascending rev, at most `limit`.
    static func ledgerDelta(ledger: [RJ], rev: Int, cursor startCursor: Int, grants: [RJ], limit: Int) -> RJ {
        let cursor = startCursor > rev ? 0 : startCursor
        let rows = ledger.enumerated()
            .filter { ($0.element["rev"].pyInt ?? 0) > cursor && pyIn($0.element["category"], grants) }
            .sorted { l, r in
                let a = l.element["rev"].pyInt ?? 0, b = r.element["rev"].pyInt ?? 0
                return a != b ? a < b : l.offset < r.offset
            }
            .map(\.element)
        let page = Array(rows.prefix(max(0, limit)))
        let hasMore = rows.count > limit
        let toRev = hasMore ? (page.last?["rev"].pyInt ?? rev) : rev
        return .obj([
            "from_rev": .int(cursor), "to_rev": .int(toRev), "has_more": .bool(hasMore),
            "keys": .arr(page.map { .obj(["type": $0["type"], "record_id": $0["record_id"], "rev": $0["rev"], "deleted": $0["deleted"]]) }),
        ])
    }
}
