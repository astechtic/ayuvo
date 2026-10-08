import Foundation

// Swift port of `scripts/partner_reference.py` §12 (protocol messages, session script), §13 (package) and §15
// (summary card metrics), plus the vector dispatcher `run_case`.

nonisolated extension PartnerRef {
    private static func vr(_ ok: Bool, _ error: String?) -> RJ { .obj(["ok": .bool(ok), "error": RJ.string(error)]) }

    private static func allCategories(_ v: RJ?) -> Bool {
        guard let list = v?.array else { return false }
        return list.allSatisfy { catalog.isCategory($0) }
    }

    // MARK: §12 message_validate

    /// `{"ok", "error"}`; codes: malformed, unknown_message, unsupported_version.
    static func messageValidate(_ msg: RJ) -> RJ {
        guard msg.isObject, case .str(let t)? = msg.get("t") else { return vr(false, "malformed") }
        guard let required = catalog.messages.first(where: { utf8Equal($0.key, t) })?.value else { return vr(false, "unknown_message") }
        for f in required where !msg.has(f) { return vr(false, "malformed") }
        if t == "HELLO" {
            if !pyEq(msg.get("protocol") ?? .null, .str(catalog.protocolID)) { return vr(false, "malformed") }
            if msg.get("v")?.pyInt != catalog.protocolVersion { return vr(false, "unsupported_version") }
            if !allCategories(msg.get("grants")) { return vr(false, "malformed") }
        }
        if t == "SYNC_REQ" {
            guard let c = msg["cursor"].pyInt, c >= 0 else { return vr(false, "malformed") }
        }
        if t == "CHANGES" {
            guard let from = msg["from_rev"].pyInt, let to = msg["to_rev"].pyInt, to >= from, msg["has_more"].bool != nil,
                  let records = msg["records"].array, records.count <= catalog.batchMax
            else { return vr(false, "malformed") }
        }
        if t == "ACK", msg["committed_rev"].pyInt == nil { return vr(false, "malformed") }
        if t == "PAIR_CONFIRM" {
            guard let accepted = msg["accepted"].bool else { return vr(false, "malformed") }
            if accepted {
                // The initiator's identity is only known from here (its X25519 key came in the IK handshake).
                let key = PartnerEncoding.b64urlDecode(msg.get("ed25519") ?? .null)
                guard case .str(let dev)? = msg.get("device_id"), PyStr.isUUID(dev), let key, key.count == 32,
                      case .str? = msg.get("name"), allCategories(msg.get("grants"))
                else { return vr(false, "malformed") }
            }
        }
        return vr(true, nil)
    }

    // MARK: §12 session_script

    /// Replays the inbound message types of one session and says whether the sequence is legal.
    static func sessionScript(_ inbound: [RJ]) -> RJ {
        var hello = false, syncReq = false, done = false, pulling = true
        var steps = 0
        for m in inbound {
            let t = m.get("t")?.string
            if t == "ERROR" {
                let code = m.get("code") ?? .null
                return .obj(["ok": .bool(false), "error": code.truthy ? code : .str("internal"), "steps": .int(steps)])
            }
            let legal = (t == "HELLO" && !hello) || (hello && !done && (
                (t == "SYNC_REQ" && !syncReq) || (t == "ACK" && syncReq) || (t == "CHANGES" && pulling) || (t == "DONE" && !pulling)))
            if !legal { return .obj(["ok": .bool(false), "error": .str("malformed"), "steps": .int(steps)]) }
            if t == "HELLO" {
                hello = true
            } else if t == "SYNC_REQ" {
                syncReq = true
            } else if t == "CHANGES", !(m.get("has_more") ?? .null).truthy {
                pulling = false
            } else if t == "DONE" {
                done = true
            }
            steps += 1
        }
        let ok = hello && !pulling && done
        return .obj(["ok": .bool(ok), "error": ok ? .null : .str("incomplete"), "steps": .int(steps)])
    }

    // MARK: §13 Package

    static func packageEntryNames(_ categories: [RJ]) -> [String] {
        catalog.packageEntriesFixed + catalog.categories.filter { pyIn(.str($0), categories) }.map { "health/\($0).ndjson" }
    }

    /// `<Name>_Health_YYYY-MM-DD.ayuvo.zip` with the name reduced to ASCII letters/digits joined by "_".
    static func packageFilename(name: RJ, day: String) -> String {
        var parts: [String] = []
        var cur = ""
        for ch in cleanName(name).unicodeScalars {
            let v = ch.value
            if (0x61...0x7A).contains(v) || (0x41...0x5A).contains(v) || (0x30...0x39).contains(v) {
                cur.unicodeScalars.append(ch)
            } else if !cur.isEmpty {
                parts.append(cur)
                cur = ""
            }
        }
        if !cur.isEmpty { parts.append(cur) }
        var base = String(parts.joined(separator: "_").prefix(32))
        while base.hasPrefix("_") { base.removeFirst() }
        while base.hasSuffix("_") { base.removeLast() }
        if base.isEmpty { base = "Partner" }
        return "\(base)_Health_\(day)\(catalog.packageExtension)"
    }

    /// Checks before any record is read; codes in check order (see the reference docstring).
    static func packageValidate(manifest: RJ, entries: [RJ], signatureOK: Bool, partners: [RJ], me: RJ) -> RJ {
        guard manifest.isObject, pyEq(manifest.get("format") ?? .null, .str(catalog.packageFormat)) else { return vr(false, "not_package") }
        guard manifest.get("version")?.pyInt == catalog.packageVersion else { return vr(false, "unsupported_version") }
        for f in ["export_id", "device_id", "recipient_device_id", "created_ms", "from_rev", "to_rev", "categories", "files"] where !manifest.has(f) {
            return vr(false, "malformed")
        }
        guard let cats = manifest["categories"].array, cats.allSatisfy({ catalog.isCategory($0) }),
              Set(cats.compactMap(\.string)).count == cats.count
        else { return vr(false, "malformed") }
        guard let fromRev = manifest["from_rev"].pyInt, let toRev = manifest["to_rev"].pyInt, toRev >= fromRev else { return vr(false, "malformed") }
        guard let files = manifest["files"].array, files.allSatisfy({ f in
            guard f.isObject, case .str? = f.get("name"), case .str(let sha)? = f.get("sha256") else { return false }
            return PyStr.isSHA256Hex(sha)
        }) else { return vr(false, "malformed") }
        for e in entries {
            let nv = e["name"]
            guard case .str(let n) = nv, !n.isEmpty else { return vr(false, "unsafe_entry") }
            if n.hasPrefix("/") || n.unicodeScalars.contains("\\") || n.unicodeScalars.contains("\u{0}")
                || n.split(separator: "/", omittingEmptySubsequences: false).contains(where: { utf8Equal(String($0), "..") }) {
                return vr(false, "unsafe_entry")
            }
        }
        guard pyIn(manifest["device_id"], partners) else { return vr(false, "unknown_sender") }
        guard pyEq(manifest["recipient_device_id"], me) else { return vr(false, "wrong_recipient") }
        guard signatureOK else { return vr(false, "bad_signature") }
        let allowed = packageEntryNames(cats)
        func isAllowed(_ n: String) -> Bool { allowed.contains { utf8Equal($0, n) } }
        var present = PyOrderedDict<RJ>()
        for e in entries {
            guard case .str(let n) = e["name"], !PyStr.hasSuffix(n, "/") else { continue }
            present[n] = e.get("sha256") ?? .null
        }
        var listed = PyOrderedDict<RJ>()
        for f in files { if case .str(let n) = f["name"] { listed[n] = f["sha256"] } }
        let fixed = ["manifest.json", "signature.json"]
        for required in fixed where !present.contains(required) { return vr(false, "missing_entry") }
        for name in present.keys where !isAllowed(name) { return vr(false, "unexpected_entry") }
        for name in listed.keys {
            if !isAllowed(name) || fixed.contains(where: { utf8Equal($0, name) }) { return vr(false, "unexpected_entry") }
            if !present.contains(name) { return vr(false, "missing_entry") }
        }
        for name in present.keys where !fixed.contains(where: { utf8Equal($0, name) }) {
            guard let l = listed[name] else { return vr(false, "unexpected_entry") }
            if !pyEq(present[name]!, l) { return vr(false, "hash_mismatch") }
        }
        return vr(true, nil)
    }

    /// Counts for the import confirmation sheet: total and per category (files[].count).
    static func packageSummary(files: [RJ], categories: [RJ]) -> RJ {
        var per: [String: Int] = [:]
        for f in files {
            guard case .str(let name) = f["name"], PyStr.hasPrefix(name, "health/"), PyStr.hasSuffix(name, ".ndjson") else { continue }
            let len = PyStr.length(name)
            let cat = PyStr.slice(name, 7, max(7, len - 7))
            if len >= 14, pyIn(.str(cat), categories) {
                let c = f.get("count") ?? .null
                per[cat, default: 0] += c.truthy ? (PyStr.int(c) ?? 0) : 0
            }
        }
        let ordered = catalog.categories.filter { per[$0] != nil }
        return .obj([
            "total": .int(per.values.reduce(0, +)),
            "per_category": .arr(ordered.map { .obj(["category": .str($0), "count": .int(per[$0]!)]) }),
        ])
    }

    // MARK: §15 Summary card metrics

    /// Display rounding: floor(v + 0.5).
    static func roundHalfUp(_ v: Double) -> Int { Int((v + 0.5).rounded(.down)) }

    /// Up to `limit` headline metrics for one partner from stored rows; a metric appears only when a row exists.
    static func summaryMetrics(rows: [RJ], today: RJ, yesterday: RJ, grantsReceived: [RJ], limit: Int) -> [RJ] {
        var out: [RJ] = []
        func add(_ key: String, _ category: String, _ value: Int, _ unit: String?, _ day: RJ) {
            out.append(.obj(["key": .str(key), "category": .str(category), "value": .int(value), "unit": RJ.string(unit),
                             "day": day, "shared": .bool(pyIn(.str(category), grantsReceived))]))
        }
        func rowsOf(_ t: String) -> [RJ] { rows.filter { pyEq($0["type"], .str(t)) } }
        func recent(_ r: RJ) -> Bool { pyIn(r["day"], [today, yesterday]) }
        func latestFirst(_ list: [RJ]) -> [RJ] { pyStableSorted(list, reverse: true) { [$0["day"]] } }

        let rec = latestFirst(rowsOf("analytics_day").filter {
            pyEq($0["data"]["metric_id"], .str("recovery_indicator")) && recent($0) && $0["data"]["value"].isPyNum
        })
        if let r = rec.first { add("recovery", "vitals", roundHalfUp(r["data"]["value"].pyNumber ?? 0), nil, r["day"]) }
        let sleep = latestFirst(rowsOf("sleep_night").filter(recent))
        if let s = sleep.first { add("sleep", "sleep", PyStr.int(s["data"]["asleep_min"]) ?? 0, "min", s["day"]) }
        let rhr = latestFirst(rowsOf("metric_day").filter {
            pyEq($0["data"]["type_id"], .str("resting_heart_rate")) && recent($0) && $0["data"]["avg"].isPyNum
        })
        if let r = rhr.first { add("resting_hr", "vitals", roundHalfUp(r["data"]["avg"].pyNumber ?? 0), "bpm", r["day"]) }
        let ex = rowsOf("metric_day").filter {
            pyEq($0["data"]["type_id"], .str("exercise_minutes")) && pyEq($0["day"], today) && $0["data"]["sum"].isPyNum
        }
        let steps = rowsOf("metric_day").filter {
            pyEq($0["data"]["type_id"], .str("steps")) && pyEq($0["day"], today) && $0["data"]["sum"].isPyNum
        }
        if let e = ex.first, (e["data"]["sum"].pyNumber ?? 0) > 0 {
            add("activity", "vitals", roundHalfUp(e["data"]["sum"].pyNumber ?? 0), "min", today)
        } else if let s = steps.first {
            add("steps", "vitals", roundHalfUp(s["data"]["sum"].pyNumber ?? 0), "count", today)
        }
        let doses = rowsOf("dose_log").filter { pyEq($0["day"], today) }
        if !doses.isEmpty {
            let taken = doses.filter { pyEq($0["data"].get("status") ?? .null, .str("taken")) }.count
            add("medicines", "medicines", taken, "of:\(doses.count)", today)
        }
        return Array(out.prefix(max(0, limit)))
    }

    // MARK: Vector dispatcher

    /// Mirrors `run_case(function, input)` of the reference.
    static func runCase(function: String, input inp: RJ) -> RJ {
        let now = inp["now_ms"].pyInt ?? 0
        switch function {
        case "qr":
            switch inp["op"].string {
            case "encode": return .obj(["text": .str(qrEncode(inp["payload"]))])
            case "parse": return qrParse(inp["text"], nowMs: now, selfDeviceID: inp["self_device_id"])
            default: return .obj(["error": .str("bad_op")])
            }
        case "kdf":
            switch inp["op"].string {
            case "psk": return .obj(["psk": RJ.string(pairingPSKHex(tokenB64: inp["token"].string ?? ""))])
            case "sas":
                return .obj(["sas": .str(sasCode(handshakeHash: PartnerEncoding.fromHex(inp["handshake_hash"].string ?? "") ?? Data()))])
            case "fingerprint":
                let fp = fingerprint(x25519B64: inp["x25519"].string ?? "", ed25519B64: inp["ed25519"].string ?? "") ?? ""
                return .obj(["fingerprint": .str(fp), "display": .str(formatFingerprint(fp))])
            default: return .obj(["error": .str("bad_op")])
            }
        case "envelope_validate":
            return envelopeValidate(inp["envelope"], nowMs: now)
        case "merge_apply":
            return mergeApply(existing: inp["existing"].array ?? [], batch: inp["batch"], cursor: inp["cursor"].pyInt ?? 0,
                              granted: inp["granted"].array ?? [], nowMs: now)
        case "grants_received":
            return .obj(["grants": .arr(grantsReceivedUpdate(previous: inp["previous"].array ?? [], grantedNow: inp["granted_now"].array ?? [], nowMs: now))])
        case "retention_prune":
            return .obj(["delete": .arr(retentionPrune(rows: inp["rows"].array ?? [], nowMs: now))])
        case "ledger":
            switch inp["op"].string {
            case "refresh":
                return ledgerRefresh(ledger: inp["ledger"].array ?? [], rev: inp["rev"].pyInt ?? 0, current: inp["current"].array ?? [],
                                     scopes: inp["scopes"].array ?? [])
            case "prune": return .obj(["ledger": .arr(ledgerPrune(ledger: inp["ledger"].array ?? [], intradayDayFrom: inp["day_from"]))])
            case "regrant": return ledgerRegrant(ledger: inp["ledger"].array ?? [], rev: inp["rev"].pyInt ?? 0, category: inp["category"])
            case "delta":
                return ledgerDelta(ledger: inp["ledger"].array ?? [], rev: inp["rev"].pyInt ?? 0, cursor: inp["cursor"].pyInt ?? 0,
                                   grants: inp["grants"].array ?? [], limit: inp.get("limit")?.pyInt ?? catalog.batchMax)
            default: return .obj(["error": .str("bad_op")])
            }
        case "map":
            let env: RJ?
            switch inp["source"].string {
            case "rollup": env = mapRollup(inp["row"])
            case "hourly": env = mapHourly(inp["row"])
            case "sample": env = mapSample(inp["row"], nowMs: now)
            case "analytics": env = mapAnalytics(inp["row"])
            case "medication": env = mapMedication(inp["row"])
            case "schedule": env = mapSchedule(inp["row"])
            case "dose_log": env = mapDoseLog(inp["row"], localDay: inp["local_day"])
            case "report":
                env = mapReportOverview(record: inp["record"], fields: inp["fields"].array ?? [], highlights: inp["highlights"].array ?? [],
                                        observations: inp["observations"].array ?? [])
            default: return .obj(["error": .str("bad_source")])
            }
            return .obj(["envelope": env ?? .null])
        case "message_validate":
            return messageValidate(inp["message"])
        case "session":
            return sessionScript(inp["inbound"].array ?? [])
        case "package":
            switch inp["op"].string {
            case "validate":
                return packageValidate(manifest: inp["manifest"], entries: inp["entries"].array ?? [], signatureOK: inp["signature_ok"].truthy,
                                       partners: inp["partners"].array ?? [], me: inp["me"])
            case "entries": return .obj(["entries": .arr(packageEntryNames(inp["categories"].array ?? []).map(RJ.str))])
            case "filename": return .obj(["filename": .str(packageFilename(name: inp["name"], day: PyStr.str(inp["day"])))])
            case "summary": return packageSummary(files: inp["files"].array ?? [], categories: inp["categories"].array ?? [])
            case "import":
                return packageImport(existing: inp["existing"].array ?? [], cursor: inp["cursor"].pyInt ?? 0, manifest: inp["manifest"],
                                     files: inp["files"], nowMs: now, batchSize: inp.get("batch_size")?.pyInt ?? catalog.batchMax)
            default: return .obj(["error": .str("bad_op")])
            }
        case "summary_metrics":
            return .obj(["metrics": .arr(summaryMetrics(rows: inp["rows"].array ?? [], today: inp["today"], yesterday: inp["yesterday"],
                                                        grantsReceived: inp["grants"].array ?? [], limit: inp.get("limit")?.pyInt ?? 3))])
        default:
            return .obj(["error": .str("unknown function \(function)")])
        }
    }
}
