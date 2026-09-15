import Foundation

// Swift port of the Phase 4 section of `scripts/records_reference.py` (docs/health-records.md §26–§31).
// Every function works on a plain store snapshot (`records`, `fields`, `observations`, `highlights`,
// `pages`, `links`, `user_aliases`; list order = row order). The reference wins over prose.
extension RR {
    static let coachFTSWeights: [Double] = [5, 3, 3, 1, 2, 2]
    static let coachPeopleKeys = ["doctor_name", "doctor_specialty", "facility", "department", "patient_name"]
    static let coachClinicalKeys = ["report_name", "test_result", "diagnosis", "symptom", "medication", "procedure"]
    static let coachFlagged: Set<String> = ["critical_low", "critical_high", "low", "high", "abnormal"]
    static let coachFlagWord: [String: String] = ["low": "low", "high": "high", "critical_low": "critical low", "critical_high": "critical high", "abnormal": "abnormal"]
    static let coachStoredFlags: [String: Set<String>] = [
        "abnormal": ["low", "high", "critical_low", "critical_high", "abnormal"], "low": ["low", "critical_low"],
        "high": ["high", "critical_high"], "critical": ["critical_low", "critical_high"], "normal": ["normal"],
    ]
    static let coachGetExcludedKeys: Set<String> = ["test_result", "patient_name", "location"]
    static let rePIILabel = Rx("(?<![a-z0-9])(?:patient|name|uhid|mrn|mr no|ip no|op no|reg no|regn no|registration|phone|mobile|mob|tel|telephone|contact|email|e-mail|address|addr|aadhaar|aadhar|abha|pan no|passport|policy no|member id|id no|lab no|sample id|barcode|dob|d\\.o\\.b|date of birth|birth)(?![a-z0-9])")
    static let rePIIDigits = Rx("[0-9](?:[ -]?[0-9]){9}")
    static let rePlaceholder = Rx("\\{([a-z_]+)\\}")
    static let reCoachISODay = Rx("([0-9]{4})-([0-9]{2})-([0-9]{2})")
    static let reCollapseWS = Rx("[ \t\r\n]+")

    // MARK: Basics

    /// One left-to-right pass; replaced text is never rescanned.
    static func fillPlaceholders(_ template: String, _ values: [String: String]) -> String {
        var out = ""
        var last = 0
        for m in rePlaceholder.finditer(template) {
            guard let name = m.g(1), let value = values[name] else { continue }
            out += template.rSub(last, m.gStart(0)) + value
            last = m.gEnd(0)
        }
        return out + template.rSub(last)
    }

    static func coachError(_ key: String, _ values: [String: String] = [:]) -> RJ {
        .obj(["error": .str(fillPlaceholders(RecordsCoachContract.shared.errors[key] ?? key, values))])
    }

    static func collapseWS(_ s: String?) -> String? {
        guard let s else { return nil }
        return reCollapseWS.sub(s, " ").rStrip(" ")
    }

    static func snapList(_ snapshot: RJ, _ key: String) -> [RJ] { snapshot[key].array ?? [] }

    static func recordMap(_ snapshot: RJ) -> [String: RJ] {
        var map: [String: RJ] = [:]
        for r in snapList(snapshot, "records") { if let id = r["id"].string { map[id] = r } }
        return map
    }

    /// `(sort_date, created_ms, seq)` ordering.
    static func timelineLess(_ a: RJ, _ b: RJ) -> Bool {
        let sa = a["sort_date"].string ?? "", sb = b["sort_date"].string ?? ""
        if sa != sb { return scalarLess(sa, sb) }
        let ca = a["created_ms"].double ?? 0, cb = b["created_ms"].double ?? 0
        if ca != cb { return ca < cb }
        return (a["seq"].double ?? 0) < (b["seq"].double ?? 0)
    }

    static func rowsOf(_ snapshot: RJ, _ recordID: String) -> [RJ] {
        snapList(snapshot, "fields").filter { $0["record_id"].string == recordID }
    }

    static func coachBestValue(_ rows: [RJ], _ key: String, referrer: Bool = false) -> String? {
        var cand = rows.filter { $0["field_key"].string == key }
        if key == "doctor_name" {
            cand = cand.filter { ($0["value_json"]["role"].string == "referrer") == referrer }
        }
        return bestOf(cand)?["value_text"].string
    }

    /// §15 medication line `<name>[ <strength>][ · <frequency>][ · <duration>]`.
    static func medicationText(_ row: RJ) -> String {
        let vj = row["value_json"]
        var text = vj["name"].truthy ? (vj["name"].string ?? "") : (row["value_text"].string ?? "")
        if vj["strength"].truthy, let s = vj["strength"].string { text += " " + s }
        for k in ["frequency", "duration"] where vj[k].truthy {
            if let s = vj[k].string { text += " · " + s }
        }
        return text
    }

    static func parseDayArgument(_ value: RJ) -> (String?, RJ?) {
        if value.isNull { return (nil, nil) }
        if case .str(let s) = value {
            if s.isEmpty { return (nil, nil) }
            guard let m = reCoachISODay.fullmatch(s), let y = Int(m.g(1) ?? ""), let mo = Int(m.g(2) ?? ""), let d = Int(m.g(3) ?? ""),
                  YMD.valid(y, mo, d) != nil else {
                return (nil, coachError("bad_date", ["value": s]))
            }
            return (s, nil)
        }
        return (nil, coachError("bad_date", ["value": value.compactJSON]))
    }

    static func coachDateArgs(_ args: RJ) -> (String?, String?, RJ?) {
        let (lo, e1) = parseDayArgument(args["from"])
        if let e1 { return (nil, nil, e1) }
        let (hi, e2) = parseDayArgument(args["to"])
        if let e2 { return (nil, nil, e2) }
        if let lo, let hi, scalarLess(hi, lo) { return (nil, nil, coachError("date_order")) }
        return (lo, hi, nil)
    }

    static func recordObservations(_ snapshot: RJ, _ recordID: String) -> (Bool, [RJ]) {
        let rows = rowsOf(snapshot, recordID)
        var pos: [String: Int] = [:]
        for (i, f) in rows.enumerated() { if let id = f["id"].string { pos[id] = i } }
        let obs = snapList(snapshot, "observations").filter { $0["record_id"].string == recordID }
        let live = obs.filter { $0["state"].string != "rejected" }.stableSorted { a, b in
            let pa = a["field_id"].string.flatMap { pos[$0] }, pb = b["field_id"].string.flatMap { pos[$0] }
            switch (pa, pb) {
            case let (x?, y?): return x < y
            case (.some, .none): return true
            case (.none, .some): return false
            default:
                let ca = a["created_ms"].double ?? 0, cb = b["created_ms"].double ?? 0
                if ca != cb { return ca < cb }
                return scalarLess(a["id"].string ?? "", b["id"].string ?? "")
            }
        }
        return (!obs.isEmpty, live)
    }

    static func recordTestResults(_ snapshot: RJ, _ recordID: String) -> [RJ] {
        let (hasObs, obs) = recordObservations(snapshot, recordID)
        if hasObs {
            return obs.map { o in
                .obj([
                    "name": o["raw_name"], "analyte": o["analyte_id"], "value": o["value_text"], "unit": o["unit"],
                    "ref_text": o["ref_text"], "ref_low": o["ref_low"], "ref_high": o["ref_high"],
                    "flag": o["flag"].truthy ? o["flag"] : .str("unknown"), "state": o["state"], "source_page": o["source_page"],
                ])
            }
        }
        return rowsOf(snapshot, recordID).filter { $0["field_key"].string == "test_result" && $0["state"].string != "rejected" }.map { f in
            let vj = f["value_json"]
            return .obj([
                "name": vj["name"].truthy ? vj["name"] : f["value_text"], "analyte": .null,
                "value": vj["value"].isNull ? .str("") : vj["value"],
                "unit": .string(canonicalUnitSpelling(vj["unit"].string)), "ref_text": vj["ref_text"],
                "ref_low": vj["ref_low"], "ref_high": vj["ref_high"], "flag": vj["flag"].truthy ? vj["flag"] : .str("unknown"),
                "state": f["state"], "source_page": f["source_page"],
            ])
        }
    }

    // MARK: §28 records_search

    /// The six folded FTS columns of a record (`fts_row`).
    static func ftsRow(_ snapshot: RJ, _ record: RJ, catalog: AnalyteCatalog = .shared) -> [String] {
        let id = record["id"].string ?? ""
        let rows = rowsOf(snapshot, id).filter { $0["state"].string != "rejected" }
        func values(_ keys: [String]) -> [String] {
            keys.flatMap { k in rows.filter { $0["field_key"].string == k }.compactMap { $0["value_text"].string } }
        }
        var clinical: [String] = []
        if let type = record["record_type"].string, type != "other" { clinical.append(type.replacingOccurrences(of: "_", with: " ")) }
        clinical += values(coachClinicalKeys)
        let ids = Set(snapList(snapshot, "observations").compactMap { o -> String? in
            guard o["record_id"].string == id, o["state"].string != "rejected", let a = o["analyte_id"].string, catalog.hasEntry(a) else { return nil }
            return a
        }).sorted(by: scalarLess)
        clinical += ids.compactMap { catalog.analyte(id: $0)?.displayName }
        var distinct: [String] = []
        for v in clinical where !v.isEmpty && !distinct.contains(v) { distinct.append(v) }
        let pages = snapList(snapshot, "pages").filter { $0["record_id"].string == id }
            .stableSorted { ($0["page_index"].double ?? 0) < ($1["page_index"].double ?? 0) }
        let highlights = snapList(snapshot, "highlights").filter { $0["record_id"].string == id && !$0["dismissed"].truthy }
            .stableSorted { a, b in
                let sa = a["section"].string ?? "", sb = b["section"].string ?? ""
                if sa != sb { return scalarLess(sa, sb) }
                return (a["position"].double ?? 0) < (b["position"].double ?? 0)
            }
        let notes = [record["notes"].string ?? ""] + (record["tags"].array ?? []).compactMap(\.string)
        return [
            fold(record["title"].string ?? ""),
            fold(values(coachPeopleKeys).joined(separator: "\n")),
            fold(distinct.joined(separator: "\n")),
            fold(pages.map { $0["text"].string ?? "" }.joined(separator: "\n")),
            fold(notes.joined(separator: " ")),
            fold(highlights.compactMap { $0["text"].string }.joined(separator: "\n")),
        ]
    }

    /// FTS4 `simple` tokenizer over folded text.
    static func ftsTokens(_ s: String) -> [String] {
        var out: [String] = []
        var cur = String.UnicodeScalarView()
        for c in s.unicodeScalars {
            let v = c.value
            if (97...122).contains(v) || (48...57).contains(v) || (65...90).contains(v) || v >= 0x80 {
                cur.append(c)
            } else if !cur.isEmpty {
                out.append(String(cur))
                cur = String.UnicodeScalarView()
            }
        }
        if !cur.isEmpty { out.append(String(cur)) }
        return out
    }

    static func bm25Pcnalx(terms: [String], docTokens: [[String]], allTokens: [[[String]]], k1: Double = 1.2, b: Double = 0.75) -> Double {
        let n = allTokens.count
        var score = 0.0
        for t in terms {
            for c in docTokens.indices {
                let tf = docTokens[c].filter { $0.hasPrefix(t) }.count
                guard tf > 0 else { continue }
                let df = allTokens.filter { row in row[c].contains { $0.hasPrefix(t) } }.count
                let avg = (allTokens.reduce(0) { $0 + $1[c].count } + n / 2) / max(n, 1)
                let idf = max(log((Double(n - df) + 0.5) / (Double(df) + 0.5)), 1e-6)
                let norm = Double(tf) + k1 * (1 - b + b * Double(docTokens[c].count) / Double(max(avg, 1)))
                score += coachFTSWeights[c] * idf * (Double(tf) * (k1 + 1)) / norm
            }
        }
        return score
    }

    static func recencyBoost(sortDate: String, today: String) -> Double {
        guard let s = YMD.iso(sortDate), let t = YMD.iso(today) else { return 0 }
        let days = max(0, t.jdn - s.jdn)
        return 0.15 * max(0, 1 - Double(days) / 730)
    }

    static func wordsPrefixMatch(_ list: [String], _ wanted: [String]) -> Bool {
        var i = 0
        for w in list where i < wanted.count {
            if w.hasPrefix(wanted[i]) { i += 1 }
        }
        return i == wanted.count
    }

    static func coachObservationMatches(_ o: RJ, _ cond: RJ) -> Bool {
        guard o["analyte_id"].string == cond["analyte_id"].string, !o["analyte_id"].isNull, o["state"].string != "rejected" else { return false }
        if let flag = cond["flag"].string {
            return coachStoredFlags[flag]?.contains(o["flag"].string ?? "") ?? false
        }
        guard let cv = cond["canonical_value"].double, let v = o["canonical_value"].double,
              o["canonical_unit"].string == cond["canonical_unit"].string else { return false }
        switch cond["op"].string {
        case ">": return v > cv
        case ">=": return v >= cv
        case "<": return v < cv
        default: return v <= cv
        }
    }

    static func coachSearchLimit(_ value: RJ) -> Int {
        switch value {
        case .int(let i): return max(1, min(RecordsCoach.searchLimitCap, i))
        case .num(let d):
            guard d == d.rounded(.down), abs(d) < 1e9 else { return RecordsCoach.searchDefaultLimit }
            return max(1, min(RecordsCoach.searchLimitCap, Int(d)))
        default: return RecordsCoach.searchDefaultLimit
        }
    }

    /// `records_search_payload`. `scorer` (the app's FTS4 index) returns the BM25 score of every record id
    /// whose FTS row matches all terms; nil scores the snapshot in memory like the reference.
    static func recordsSearchPayload(_ snapshot: RJ, args: RJ, selectedIDs: [String], today: String, dateOrder: String,
                                     catalog: AnalyteCatalog = .shared, scorer: (([String]) -> [String: Double])? = nil) -> RJ {
        let query = args["query"].string ?? ""
        let (lo, hi, err) = coachDateArgs(args)
        if let err { return err }
        let limit = coachSearchLimit(args["limit"])
        let q = parseQuery(query, today: today, dateOrder: dateOrder, catalog: catalog)
        let dateFrom = [q["date_from"].string, lo].compactMap { $0 }.max(by: scalarLess)
        let dateTo = [q["date_to"].string, hi].compactMap { $0 }.min(by: scalarLess)
        var types = (q["record_types"].array ?? []).compactMap(\.string)
        if let rt = args["record_type"].string, recordTypes.contains(rt) {
            types = (types.isEmpty || types.contains(rt)) ? [rt] : ["-"]
        }
        let selected = Set(selectedIDs)
        let records = snapList(snapshot, "records")
        let fields = snapList(snapshot, "fields")
        let observations = snapList(snapshot, "observations")
        var fieldsByRecord: [String: [RJ]] = [:]
        for f in fields where f["state"].string != "rejected" { fieldsByRecord[f["record_id"].string ?? "", default: []].append(f) }
        var obsByRecord: [String: [RJ]] = [:]
        for o in observations { obsByRecord[o["record_id"].string ?? "", default: []].append(o) }
        let wantArchived = q["archived"].truthy
        let scope = records.filter { r in
            selected.isEmpty ? (r["archived"].truthy == wantArchived) : selected.contains(r["id"].string ?? "")
        }
        let flags = (q["flags"].array ?? []).compactMap(\.string)
        let conditions = q["analyte_conditions"].array ?? []
        func passes(_ r: RJ) -> Bool {
            let sortDate = r["sort_date"].string ?? ""
            if !types.isEmpty, !types.contains(r["record_type"].string ?? "") { return false }
            if let dateFrom, scalarLess(sortDate, dateFrom) { return false }
            if let dateTo, scalarLess(dateTo, sortDate) { return false }
            if q["favorites"].truthy, !r["favorite"].truthy { return false }
            if q["needs_review"].truthy, r["review_status"].string != "needs_review" { return false }
            if q["source"].string == "received", !["share_in", "open_in"].contains(r["source"].string ?? "") { return false }
            let rows = fieldsByRecord[r["id"].string ?? ""] ?? []
            if !flags.isEmpty {
                let wanted = Set(flags.flatMap { coachStoredFlags[$0] ?? [] })
                if !rows.contains(where: { $0["field_key"].string == "test_result" && wanted.contains($0["value_json"]["flag"].string ?? "") }) { return false }
            }
            for (key, name) in [("doctor_name", q["doctor"].string), ("facility", q["facility"].string)] {
                guard let name, !normText(name).isEmpty else { continue }
                let wanted = normText(name).components(separatedBy: " ")
                if !rows.contains(where: { f in
                    f["field_key"].string == key && wordsPrefixMatch(normalizeValue(key, f["value_text"].string, f["value_json"]).components(separatedBy: " "), wanted)
                }) { return false }
            }
            let robs = obsByRecord[r["id"].string ?? ""] ?? []
            for cond in conditions where !robs.contains(where: { coachObservationMatches($0, cond) }) { return false }
            return true
        }
        let hits = scope.filter(passes)
        let terms = (q["terms"].array ?? []).compactMap(\.string)
        var ordered: [RJ]
        if !terms.isEmpty {
            var scores: [String: Double] = [:]
            if let scorer {
                scores = scorer(terms)
            } else {
                let allTokens = records.map { ftsRow(snapshot, $0, catalog: catalog).map(ftsTokens) }
                var index: [String: Int] = [:]
                for (i, r) in records.enumerated() { index[r["id"].string ?? ""] = i }
                for r in hits {
                    guard let i = index[r["id"].string ?? ""] else { continue }
                    let toks = allTokens[i]
                    guard terms.allSatisfy({ t in toks.contains { col in col.contains { $0.hasPrefix(t) } } }) else { continue }
                    scores[r["id"].string ?? ""] = bm25Pcnalx(terms: terms, docTokens: toks, allTokens: allTokens)
                }
            }
            let scored: [(Double, RJ)] = hits.compactMap { r in
                guard let s = scores[r["id"].string ?? ""] else { return nil }
                return (s + recencyBoost(sortDate: r["sort_date"].string ?? "", today: today), r)
            }
            ordered = scored.stableSorted { a, b in timelineLess(b.1, a.1) }.stableSorted { $0.0 > $1.0 }.map { $0.1 }
        } else {
            ordered = hits.stableSorted { timelineLess($1, $0) }
        }
        let highlights = snapList(snapshot, "highlights")
        let outRecords: [RJ] = ordered.prefix(limit).map { r in
            let id = r["id"].string ?? ""
            let rows = rowsOf(snapshot, id)
            let hl = highlights.filter { $0["record_id"].string == id && $0["section"].string == "important" && !$0["dismissed"].truthy }
                .stableSorted { ($0["position"].double ?? 0) < ($1["position"].double ?? 0) }
            return .obj([
                "record_id": r["id"], "title": r["title"], "date": r["sort_date"], "record_type": r["record_type"],
                "facility": .string(coachBestValue(rows, "facility")), "doctor": .string(coachBestValue(rows, "doctor_name")),
                "review_status": r["review_status"].truthy ? r["review_status"] : .str("none"),
                "highlights": .arr(hl.prefix(RecordsCoach.highlightsPerRecord).map { $0["text"] }),
            ])
        }
        var values: [RJ] = []
        let bare = Set((q["analytes"].array ?? []).compactMap(\.string))
        if !bare.isEmpty || !conditions.isEmpty {
            let scopeIDs = Set(scope.compactMap { $0["id"].string })
            let cand = observations.filter { o in
                guard scopeIDs.contains(o["record_id"].string ?? ""), o["state"].string != "rejected" else { return false }
                let aid = o["analyte_id"].string
                if !(aid.map { bare.contains($0) } ?? false), !conditions.contains(where: { coachObservationMatches(o, $0) }) { return false }
                let od = o["observed_date"].string
                if dateFrom != nil || dateTo != nil {
                    guard let od else { return false }
                    if let dateFrom, scalarLess(od, dateFrom) { return false }
                    if let dateTo, scalarLess(dateTo, od) { return false }
                }
                return true
            }.stableSorted { a, b in
                let da = a["observed_date"].string ?? "", db = b["observed_date"].string ?? ""
                if da != db { return scalarLess(db, da) }
                let ca = a["created_ms"].double ?? 0, cb = b["created_ms"].double ?? 0
                if ca != cb { return ca > cb }
                return scalarLess(b["id"].string ?? "", a["id"].string ?? "")
            }
            values = cand.prefix(RecordsCoach.searchValuesCap).map { o in
                .obj([
                    "record_id": o["record_id"], "analyte": o["analyte_id"], "name": o["raw_name"], "value": o["value_text"],
                    "unit": o["unit"], "flag": o["flag"].truthy ? o["flag"] : .str("unknown"), "date": o["observed_date"],
                ])
            }
        }
        return .obj(["query": .str(query), "count": .int(outRecords.count), "records": .arr(outRecords), "values": .arr(values)])
    }

    // MARK: §28 records_get

    static func piiLine(_ folded: String, _ patientNames: [String]) -> Bool {
        if rePIILabel.search(folded) != nil || rePIIDigits.search(folded) != nil { return true }
        let padded = " " + normText(folded) + " "
        return patientNames.contains { !$0.isEmpty && padded.contains(" " + $0 + " ") }
    }

    static func recordTextExcerpt(_ snapshot: RJ, _ recordID: String) -> String? {
        let names = rowsOf(snapshot, recordID).filter { $0["field_key"].string == "patient_name" && $0["state"].string != "rejected" }
            .map { normText($0["value_text"].string) }
        var parts: [String] = []
        let pages = snapList(snapshot, "pages").filter { $0["record_id"].string == recordID }
            .stableSorted { ($0["page_index"].double ?? 0) < ($1["page_index"].double ?? 0) }
        for p in pages {
            let text = (p["text"].string ?? "").replacingOccurrences(of: "\r\n", with: "\n").replacingOccurrences(of: "\r", with: "\n")
            let kept = text.components(separatedBy: "\n").filter { !piiLine(fold($0), names) }
            let page = kept.joined(separator: "\n").rStrip("\n").rLStrip("\n")
            if !page.rStrip(" \t\n").isEmpty { parts.append(page) }
        }
        let joined = parts.joined(separator: "\n\n")
        return joined.isEmpty ? nil : RecordsCoach.prefixScalars(joined, RecordsCoach.textExcerptLimit)
    }

    static func recordsGetPayload(_ snapshot: RJ, args: RJ, selectedIDs: [String]) -> RJ {
        let rid = args["record_id"].string ?? ""
        if !selectedIDs.isEmpty, !selectedIDs.contains(rid) { return coachError("not_selected", ["id": rid]) }
        guard let rec = recordMap(snapshot)[rid] else { return coachError("unknown_record", ["id": rid]) }
        let rows = rowsOf(snapshot, rid)
        let fields: [RJ] = rows.filter { $0["state"].string != "rejected" && !coachGetExcludedKeys.contains($0["field_key"].string ?? "") }.map { f in
            .obj([
                "key": f["field_key"],
                "value": f["field_key"].string == "medication" ? .str(medicationText(f)) : f["value_text"],
                "state": f["state"], "source_page": f["source_page"],
            ])
        }
        let hls = snapList(snapshot, "highlights").filter { $0["record_id"].string == rid && !$0["dismissed"].truthy }
            .stableSorted { a, b in
                let sa = a["section"].string ?? "", sb = b["section"].string ?? ""
                if sa != sb { return scalarLess(sa, sb) }
                return (a["position"].double ?? 0) < (b["position"].double ?? 0)
            }
        let type = rec["record_type"].string ?? "other"
        var includeText = false
        if case .bool(true) = args["include_text"] { includeText = true }
        return .obj([
            "record_id": .str(rid), "title": rec["title"], "date": rec["sort_date"], "record_type": rec["record_type"],
            "category": rec["category"].truthy ? rec["category"] : .str(defaultCategory[type] ?? "other"),
            "facility": .string(coachBestValue(rows, "facility")), "doctor": .string(coachBestValue(rows, "doctor_name")),
            "referrer": .string(coachBestValue(rows, "doctor_name", referrer: true)), "patient_sex": .string(coachBestValue(rows, "patient_sex")),
            "patient_age": .string(coachBestValue(rows, "patient_age")),
            "review_status": rec["review_status"].truthy ? rec["review_status"] : .str("none"),
            "ai_mode_used": rec["ai_mode_used"].truthy ? rec["ai_mode_used"] : .str("none"),
            "page_count": rec["page_count"].truthy ? rec["page_count"] : .int(0),
            "fields": .arr(fields), "test_results": .arr(recordTestResults(snapshot, rid)),
            "highlights": .arr(hls.map { .obj(["section": $0["section"], "text": $0["text"]]) }),
            "text": includeText ? .string(recordTextExcerpt(snapshot, rid)) : .null,
        ])
    }

    // MARK: §28 records_observation_series

    static func resolveSeriesAnalyte(_ snapshot: RJ, _ name: String, catalog: AnalyteCatalog = .shared) -> String? {
        if catalog.hasEntry(name) { return name }
        let aliases = (snapshot["user_aliases"].object ?? [:]).compactMapValues(\.string)
        return mapAnalyteRef(name, panels: nil, userAliases: aliases, unit: nil, qualitative: nil, catalog)["analyte_id"].string
    }

    static func recordsSeriesPayload(_ snapshot: RJ, args: RJ, selectedIDs: [String], catalog: AnalyteCatalog = .shared) -> RJ {
        let name = args["analyte"].string ?? ""
        let (lo, hi, err) = coachDateArgs(args)
        if let err { return err }
        if name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty { return coachError("unknown_analyte", ["analyte": name]) }
        let recs = recordMap(snapshot)
        let selected = Set(selectedIDs)
        let rows = snapList(snapshot, "observations").filter { o in
            guard o["state"].string != "rejected", !o["excluded_from_trends"].truthy, let od = o["observed_date"].string,
                  let rid = o["record_id"].string, let rec = recs[rid] else { return false }
            if selected.isEmpty ? rec["archived"].truthy : !selected.contains(rid) { return false }
            if let lo, scalarLess(od, lo) { return false }
            if let hi, scalarLess(hi, od) { return false }
            return true
        }
        var aid = resolveSeriesAnalyte(snapshot, name, catalog: catalog)
        var picked = rows.filter { aid != nil && $0["analyte_id"].string == aid }
        if picked.isEmpty {
            let key = normalizeTestNameRef(name)
            picked = rows.filter { $0["analyte_id"].isNull && normalizeTestNameRef($0["raw_name"].string ?? "") == key }
            aid = nil
        }
        guard !picked.isEmpty else { return coachError("unknown_analyte", ["analyte": name]) }
        picked = picked.stableSorted { a, b in
            let da = a["observed_date"].string ?? "", db = b["observed_date"].string ?? ""
            if da != db { return scalarLess(da, db) }
            let ca = a["created_ms"].double ?? 0, cb = b["created_ms"].double ?? 0
            if ca != cb { return ca < cb }
            return scalarLess(a["id"].string ?? "", b["id"].string ?? "")
        }
        var seen = Set<String>()
        var points: [RJ] = []
        for o in picked {
            let od = o["observed_date"].string ?? ""
            let vkey: String
            if let cv = o["canonical_value"].double {
                vkey = "c|\(cv)"
            } else {
                vkey = "u|\(o["unit"].compactJSON)|\(o["value_text"].compactJSON)"
            }
            guard seen.insert(od + "|" + vkey).inserted else { continue }
            points.append(.obj([
                "date": .str(od), "value": o["value_text"], "unit": o["unit"], "canonical_value": o["canonical_value"],
                "flag": o["flag"].truthy ? o["flag"] : .str("unknown"), "ref_low": o["ref_low"], "ref_high": o["ref_high"],
                "record_id": o["record_id"], "record_title": recs[o["record_id"].string ?? ""]?["title"] ?? .null, "state": o["state"],
            ]))
        }
        points = Array(points.suffix(RecordsCoach.seriesPointCap))
        let entry = aid.flatMap { catalog.analyte(id: $0) }
        return .obj([
            "analyte": .string(aid),
            "display_name": entry.map { .str($0.displayName) } ?? picked[0]["raw_name"],
            "unit": .string(entry?.canonicalUnit),
            "count": .int(points.count),
            "points": .arr(points),
        ])
    }

    // MARK: §26 prompt lines, gating, refs

    static func coachTypeLabel(_ labels: [String: String]?, _ type: String?) -> String {
        if let type, let label = labels?[type], !label.isEmpty { return label }
        return typeLabel[type ?? ""] ?? "Record"
    }

    static func selectedRecords(_ snapshot: RJ, _ selectedIDs: [String]) -> [RJ] {
        let recs = recordMap(snapshot)
        var picked: [RJ] = []
        var seen = Set<String>()
        for id in selectedIDs {
            if let r = recs[id], seen.insert(id).inserted { picked.append(r) }
        }
        return picked.stableSorted { timelineLess($1, $0) }
    }

    static func coachPromptLines(_ snapshot: RJ, accessEnabled: Bool, selectedIDs: [String], typeLabels: [String: String]?) -> RJ {
        let p = RecordsCoachContract.shared.prompt
        var out: [String: RJ] = ["advertise_tools": .bool(false), "available_line": .null, "guardrails": .null, "selected_lines": .arr([]), "not_available_line": .null]
        guard accessEnabled else {
            out["not_available_line"] = .string(p["not_available_line"])
            return .obj(out)
        }
        let live = snapList(snapshot, "records").filter { !$0["archived"].truthy }
        guard !live.isEmpty else { return .obj(out) }
        out["advertise_tools"] = .bool(true)
        let latest = live.compactMap { $0["sort_date"].string }.max(by: scalarLess) ?? ""
        out["available_line"] = .str(fillPlaceholders(p["available_line"] ?? "", ["n": "\(live.count)", "latest_date": latest]))
        out["guardrails"] = .string(p["guardrails"])
        let sel = selectedRecords(snapshot, selectedIDs)
        if !sel.isEmpty {
            out["selected_lines"] = .arr([.string(p["selected_header"])] + sel.map { r in
                .str(fillPlaceholders(p["selected_line"] ?? "", [
                    "record_id": r["id"].string ?? "", "title": collapseWS(r["title"].string) ?? "",
                    "date": r["sort_date"].string ?? "", "type_label": coachTypeLabel(typeLabels, r["record_type"].string),
                ]))
            })
        }
        return .obj(out)
    }

    static func mentionsRecords(_ message: String) -> Bool {
        let toks = words(fold(message)).map { QTok(kind: "w", word: $0, date: nil, text: $0, start: 0, end: 0) }
        let table: [([String], [String])] = qTypes + [(["record"], []), (["records"], [])]
        return toks.indices.contains { matchPhrase(toks, $0, table) != nil }
    }

    /// `record_refs(tool_calls, packed)`: tool calls are `{name, result}`; packed rows `{record_id, title, date}`.
    static func recordRefs(toolCalls: [RJ], packed: [RJ]?) -> [RJ] {
        var out: [RJ] = []
        var seen = Set<String>()
        func add(_ id: RJ, _ title: RJ, _ date: RJ) {
            guard let rid = id.string, seen.insert(rid).inserted else { return }
            out.append(.obj(["record_id": .str(rid), "title": title, "date": date]))
        }
        for r in packed ?? [] { add(r["record_id"], r["title"], r["date"]) }
        for call in toolCalls {
            let res = call["result"]
            guard case .obj(let o) = res, o["error"] == nil else { continue }
            switch call["name"].string {
            case "records_get": add(res["record_id"], res["title"], res["date"])
            case "records_observation_series":
                for pt in res["points"].array ?? [] { add(pt["record_id"], pt["record_title"], pt["date"]) }
            default: break
            }
        }
        return out
    }

    // MARK: §27 selection helpers

    static func compareCandidateRef(_ snapshot: RJ, recordID: String) -> RJ {
        let none: RJ = .obj(["previous_id": .null, "rule": .null])
        let recs = recordMap(snapshot)
        guard let rec = recs[recordID] else { return none }
        func older(_ c: RJ) -> Bool { c["id"].string != recordID && !c["archived"].truthy && timelineLess(c, rec) }
        var linked: [RJ] = []
        for l in snapList(snapshot, "links") {
            let a = l["a_id"].string, b = l["b_id"].string
            guard l["kind"].string == "previous_report", l["status"].string != "rejected", a == recordID || b == recordID else { continue }
            if let other = recs[(a == recordID ? b : a) ?? ""], older(other) { linked.append(other) }
        }
        if let newest = maxTimeline(linked) { return .obj(["previous_id": newest["id"], "rule": .str("link")]) }
        guard labLike.contains(rec["record_type"].string ?? "") else { return none }
        var analytesByRecord: [String: Set<String>] = [:]
        for o in snapList(snapshot, "observations") where o["state"].string != "rejected" {
            if let aid = o["analyte_id"].string, !aid.isEmpty { analytesByRecord[o["record_id"].string ?? "", default: []].insert(aid) }
        }
        let mine = analytesByRecord[recordID] ?? []
        let shared = snapList(snapshot, "records").filter { c in
            older(c) && labLike.contains(c["record_type"].string ?? "") && mine.intersection(analytesByRecord[c["id"].string ?? ""] ?? []).count >= 3
        }
        if let newest = maxTimeline(shared) { return .obj(["previous_id": newest["id"], "rule": .str("shared_analytes")]) }
        return none
    }

    /// Python `max(key=_timeline_key)`: the first of the maximal elements.
    static func maxTimeline(_ rows: [RJ]) -> RJ? {
        var best: RJ?
        for r in rows where best == nil || timelineLess(best!, r) { best = r }
        return best
    }

    static func latestLabSelection(_ snapshot: RJ) -> RJ {
        let labs = snapList(snapshot, "records").filter { $0["record_type"].string == "lab_report" && !$0["archived"].truthy }
            .stableSorted { timelineLess($1, $0) }
        guard let first = labs.first, let firstID = first["id"].string else {
            return .obj(["show_chips": .bool(false), "latest": .arr([]), "compare": .null])
        }
        let prev = compareCandidateRef(snapshot, recordID: firstID)["previous_id"]
        return .obj([
            "show_chips": .bool(true), "latest": .arr(labs.prefix(3).map { $0["id"] }),
            "compare": prev.isNull ? .null : .arr([.str(firstID), prev]),
        ])
    }

    // MARK: §29 on-device block

    static func stripRefBrackets(_ refText: String?) -> String? {
        guard var t = collapseWS(refText) else { return nil }
        let scalars = Array(t.unicodeScalars)
        if scalars.count >= 2, (scalars.first == "(" && scalars.last == ")") || (scalars.first == "[" && scalars.last == "]") {
            var view = String.UnicodeScalarView()
            view.append(contentsOf: scalars[1..<(scalars.count - 1)])
            t = String(view).rStrip(" ").rLStrip(" ")
        }
        return t.isEmpty ? nil : t
    }

    static func resultItem(_ tr: RJ) -> String {
        let parts = ["name", "value", "unit"].compactMap { k -> String? in
            guard let s = tr[k].string, let c = collapseWS(s), !c.isEmpty else { return nil }
            return c
        }
        var inner: [String] = []
        if let word = coachFlagWord[tr["flag"].string ?? ""] { inner.append(word) }
        if let ref = tr["ref_text"].string.flatMap(stripRefBrackets) { inner.append("ref " + ref) }
        var text = parts.joined(separator: " ")
        if !inner.isEmpty { text += " (" + inner.joined(separator: ", ") + ")" }
        return text
    }

    static func flagGroup(_ flag: String?) -> Int {
        switch flag {
        case "critical_low", "critical_high": 0
        case "low", "high": 1
        case "abnormal": 2
        default: 3
        }
    }

    static func packCoachRecords(_ snapshot: RJ, selectedIDs: [String], typeLabels: [String: String]?) -> RJ {
        struct Block {
            var record: RJ
            var facility: String?
            var doctor: String?
            var results: [(text: String, flagged: Bool)]
            var dropped = 0
            var lists: [[String]]  // medications, diagnoses, recommendations
        }
        let sel = Array(selectedRecords(snapshot, selectedIDs).prefix(RecordsCoach.packMaxRecords))
        guard !sel.isEmpty else { return .obj(["text": .null, "record_ids": .arr([]), "dropped_results": .int(0)]) }
        var blocks: [Block] = sel.map { r in
            let id = r["id"].string ?? ""
            let rows = rowsOf(snapshot, id).filter { $0["state"].string != "rejected" }
            let results = recordTestResults(snapshot, id).enumeratedArray().stableSorted { a, b in
                let ga = flagGroup(a.element["flag"].string), gb = flagGroup(b.element["flag"].string)
                if ga != gb { return ga < gb }
                return a.offset < b.offset
            }
            let meds: [String] = rows.filter { $0["field_key"].string == "medication" }.map { f in
                let vj = f["value_json"]
                let parts: [String?] = [vj["name"].truthy ? vj["name"].string : f["value_text"].string, vj["strength"].string, vj["frequency"].string]
                return parts.compactMap { x -> String? in
                    guard let x, !x.isEmpty, let c = collapseWS(x), !c.isEmpty else { return nil }
                    return c
                }.joined(separator: " ")
            }.filter { !$0.isEmpty }
            func texts(_ key: String) -> [String] {
                rows.filter { $0["field_key"].string == key }.compactMap { collapseWS($0["value_text"].string) }.filter { !$0.isEmpty }
            }
            return Block(
                record: r,
                facility: collapseWS(coachBestValue(rows, "facility")),
                doctor: collapseWS(coachBestValue(rows, "doctor_name")),
                results: results.map { (resultItem($0.element), coachFlagged.contains($0.element["flag"].string ?? "")) },
                lists: [meds, texts("diagnosis"), texts("recommendation")]
            )
        }
        func render() -> String {
            var lines = ["## Health records (selected by the user)"]
            for b in blocks {
                let r = b.record
                lines.append("### \(collapseWS(r["title"].string) ?? "") — \(r["sort_date"].string ?? "") (\(coachTypeLabel(typeLabels, r["record_type"].string)))")
                let fd = [b.facility.flatMap { $0.isEmpty ? nil : "Facility: " + $0 }, b.doctor.flatMap { $0.isEmpty ? nil : "Doctor: " + $0 }].compactMap { $0 }
                if !fd.isEmpty { lines.append(fd.joined(separator: " · ")) }
                if !b.results.isEmpty || b.dropped > 0 {
                    var line = "Results:"
                    if !b.results.isEmpty { line += " " + b.results.map(\.text).joined(separator: "; ") }
                    if b.dropped > 0 { line += " (+\(b.dropped) more results not shown)" }
                    lines.append(line)
                }
                for (label, list) in zip(["Medications", "Diagnoses", "Recommendations"], b.lists) where !list.isEmpty {
                    lines.append(label + ": " + list.joined(separator: "; "))
                }
            }
            return lines.joined(separator: "\n")
        }
        func dropOne() -> Bool {
            for flagged in [false, true] {
                for bi in blocks.indices.reversed() {
                    if let i = blocks[bi].results.lastIndex(where: { $0.flagged == flagged }) {
                        blocks[bi].results.remove(at: i)
                        blocks[bi].dropped += 1
                        return true
                    }
                }
            }
            for listIndex in [2, 1, 0] {
                for bi in blocks.indices.reversed() where !blocks[bi].lists[listIndex].isEmpty {
                    blocks[bi].lists[listIndex].removeLast()
                    return true
                }
            }
            if blocks.count > 1 {
                blocks.removeLast()
                return true
            }
            return false
        }
        var text = render()
        while text.unicodeScalars.count > RecordsCoach.packBudget, dropOne() { text = render() }
        return .obj([
            "text": .str(text), "record_ids": .arr(blocks.map { $0.record["id"] }),
            "dropped_results": .int(blocks.reduce(0) { $0 + $1.dropped }),
        ])
    }

    // MARK: Vectors

    /// Phase 4 vector dispatch; `input.snapshot` may name a fixture snapshot.
    static func runCoachCase(function: String, input: RJ, fixtures: RJ) -> RJ {
        var snapshot = input["snapshot"]
        if let name = snapshot.string { snapshot = fixtures["snapshots"][name] }
        let selected = (input["selected_ids"].array ?? []).compactMap(\.string)
        let labels = input["type_labels"].object?.compactMapValues(\.string)
        switch function {
        case "coach_tools":
            switch input["tool"].string {
            case "records_search":
                return recordsSearchPayload(snapshot, args: input["args"], selectedIDs: selected, today: input["today"].string ?? "", dateOrder: input["date_order"].string ?? "dmy")
            case "records_get":
                return recordsGetPayload(snapshot, args: input["args"], selectedIDs: selected)
            case "records_observation_series":
                return recordsSeriesPayload(snapshot, args: input["args"], selectedIDs: selected)
            default:
                return .null
            }
        case "pack_coach_records":
            return packCoachRecords(snapshot, selectedIDs: selected, typeLabels: labels)
        case "coach_prompt":
            switch input["op"].string {
            case "prompt_lines":
                return coachPromptLines(snapshot, accessEnabled: input["access_enabled"].truthy, selectedIDs: selected, typeLabels: labels)
            case "mentions_records":
                return .obj(["mentions": .bool(mentionsRecords(input["message"].string ?? ""))])
            case "record_refs":
                return .obj(["record_refs": .arr(recordRefs(toolCalls: input["tool_calls"].array ?? [], packed: input["packed"].array))])
            case "compare_candidate":
                return compareCandidateRef(snapshot, recordID: input["record_id"].string ?? "")
            case "latest_lab_selection":
                return latestLabSelection(snapshot)
            default:
                return .null
            }
        default:
            return .null
        }
    }
}

extension Array {
    /// `enumerated()` as an array, so `stableSorted` (Array-only) applies.
    nonisolated func enumeratedArray() -> [(offset: Int, element: Element)] {
        enumerated().map { ($0.offset, $0.element) }
    }
}
