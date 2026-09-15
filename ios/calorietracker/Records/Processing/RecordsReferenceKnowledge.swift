import Foundation

// Phase 3 port of `scripts/records_reference.py` (docs/health-records.md §19–§22): analyte words and
// test-name keys, mapping with disambiguation, panels, unit conversion, observed date, promotion,
// edits, user aliases, trends, mini trends, entities and relation suggestions. Follows the reference
// line by line; the typed adapters live in RecordsKnowledgeCore.swift.
extension RR {
    // MARK: §20 Catalog constants

    static let panelIDs = ["cbc", "iron_studies", "vitamins", "diabetes", "lipid", "lft", "kft", "electrolytes", "thyroid",
                           "cardiac", "coagulation", "hormones", "urine_routine", "urine_albumin", "serology", "pancreas"]
    static let urinePanels: Set<String> = ["urine_routine", "urine_albumin"]

    static let kMethodWords: Set<String> = [
        "serum", "plasma", "blood", "whole", "venous", "capillary", "edta", "fluoride", "heparin",
        "calculated", "calc", "derived", "measured", "method", "automated", "auto", "analyzer", "analyser",
        "hplc", "ifcc", "ngsp", "dcct", "clia", "eclia", "cmia", "cia", "elisa", "elfa", "ria", "ise",
        "turbidimetric", "turbidimetry", "immunoturbidimetric", "immunoturbidimetry", "nephelometric", "nephelometry",
        "photometric", "photometry", "colorimetric", "colorimetry", "spectrophotometric", "spectrophotometry",
        "enzymatic", "kinetic", "jaffe", "jaffes", "westergren", "wintrobe", "ckd", "epi", "mdrd",
        "level", "levels", "conc", "concentration",
    ]
    static let kMethodLeading: Set<String> = ["s", "sr", "se", "ser"]

    static let panelKeywords: [(String, [String])] = [
        ("cbc", ["complete blood count", "cbc", "hemogram", "haemogram", "full blood count", "fbc", "blood count"]),
        ("iron_studies", ["iron studies", "iron profile", "iron panel", "anemia profile", "anaemia profile"]),
        ("vitamins", ["vitamin profile", "vitamin panel", "vitamin b12", "vitamin d"]),
        ("diabetes", ["diabetes profile", "diabetic profile", "diabetes panel", "glucose tolerance", "blood sugar",
                      "hba1c", "glycated", "glycosylated"]),
        ("lipid", ["lipid", "lipids", "lipid profile", "lipid panel"]),
        ("lft", ["liver function", "lft", "hepatic function", "liver panel", "liver profile"]),
        ("kft", ["kidney function", "renal function", "kft", "rft", "renal profile", "kidney profile", "renal panel"]),
        ("electrolytes", ["electrolyte", "electrolytes"]),
        ("thyroid", ["thyroid", "tft"]),
        ("cardiac", ["cardiac", "troponin"]),
        ("coagulation", ["coagulation", "prothrombin", "pt inr"]),
        ("hormones", ["hormone", "hormones", "hormonal", "fertility", "pcos"]),
        ("urine_routine", ["urine", "urinalysis", "cue"]),
        ("urine_albumin", ["microalbumin", "albumin creatinine ratio", "acr", "urine albumin"]),
        ("serology", ["serology", "dengue", "widal", "hiv", "hbsag", "viral markers"]),
        ("pancreas", ["amylase", "lipase", "pancreatic"]),
    ]
    static let panelMinAnalytes = 3

    // MARK: §20 Words, keys, mapping

    static func analyteWords(_ s: String?) -> [String] {
        var out: [String] = []
        var run: [String] = []
        func flush() {
            if run.count >= 2 { out.append(run.joined()) } else { out.append(contentsOf: run) }
            run = []
        }
        for w in words(fold(s ?? "")) {
            if w.unicodeScalars.count == 1, let c = w.unicodeScalars.first, c.value >= 97, c.value <= 122 {
                run.append(w)
                continue
            }
            flush()
            out.append(w)
        }
        flush()
        return out
    }

    static func stripMethodWordList(_ ws: [String]) -> [String] {
        let out = ws.enumerated().filter { !(kMethodWords.contains($0.element) || ($0.offset == 0 && kMethodLeading.contains($0.element))) }.map(\.element)
        return out.isEmpty ? ws : out
    }

    static func stripBrackets(_ f: String) -> String {
        var out = String.UnicodeScalarView()
        var close: Unicode.Scalar?
        var opener: Unicode.Scalar?
        var depth = 0
        for ch in f.unicodeScalars {
            if close == nil {
                if ch == "(" || ch == "[" {
                    opener = ch
                    close = ch == "(" ? ")" : "]"
                    depth = 1
                    out.append(" ")
                } else {
                    out.append(ch)
                }
            } else if ch == opener {
                depth += 1
            } else if ch == close {
                depth -= 1
                if depth == 0 { close = nil }
            }
        }
        return String(out)
    }

    static func testNameKeys(_ name: String?) -> [String] {
        let f = fold(name ?? "")
        let k1 = analyteWords(f)
        let k2 = analyteWords(stripBrackets(f))
        var keys: [String] = []
        for ws in [k1, k2, stripMethodWordList(k1), stripMethodWordList(k2)] {
            let k = ws.joined(separator: " ")
            if !k.isEmpty, !keys.contains(k) { keys.append(k) }
        }
        return keys
    }

    static func normalizeTestNameRef(_ name: String?) -> String {
        stripMethodWordList(analyteWords(name)).joined(separator: " ")
    }

    static func canonicalUnitSpelling(_ unit: String?) -> String? {
        guard let unit else { return nil }
        let p = reWS.sub(pfold(unit), " ").rStrip(" ")
        if p.isEmpty { return nil }
        if let m = matchUnit(fold(p), 0), m.1 == p.rLen { return m.0 }
        return p
    }

    static func analyteUnits(_ e: RJ) -> Set<String> {
        var us = Set((e["units"].array ?? []).compactMap { $0["unit"].string })
        if e["canonical_unit"].isNull, e["kind"].string == "numeric" { us.insert("ratio") }
        return us
    }

    static func disambiguate(_ ids: [String], unit: String?, qualitative: Bool?, panels: Set<String>, _ cat: AnalyteCatalog) -> String? {
        var c = ids
        var steps: [(String) -> Bool] = []
        if let unit { steps.append { analyteUnits(cat.entry($0)).contains(unit) } }
        if let qualitative { steps.append { cat.entry($0)["kind"].string == (qualitative ? "qualitative" : "numeric") } }
        steps.append { !Set((cat.entry($0)["panels"].array ?? []).compactMap(\.string)).isDisjoint(with: panels) }
        if panels.isDisjoint(with: urinePanels) { steps.append { cat.entry($0)["category"].string != "urine" } }
        for keep in steps {
            let k = c.filter(keep)
            if k.count == 1 { return k[0] }
            if k.count >= 2 { c = k }
        }
        return nil
    }

    static func mapAnalyteRef(_ name: String?, panels: [String]? = nil, userAliases: [String: String]? = nil, unit: String? = nil, qualitative: Bool? = nil, _ cat: AnalyteCatalog = .shared) -> RJ {
        let keys = testNameKeys(name)
        var res: [String: RJ] = ["analyte_id": .null, "method": .null, "key": .null, "normalized_name": .str(normalizeTestNameRef(name)), "candidates": .arr([])]
        let ua = userAliases ?? [:]
        for k in keys {
            if let aid = ua[k], cat.hasEntry(aid) {
                res["analyte_id"] = .str(aid)
                res["method"] = .str("user_alias")
                res["key"] = .str(k)
                return .obj(res)
            }
        }
        var u = canonicalUnitSpelling(unit)
        if u == nil, (name ?? "").contains("%") { u = "%" }
        let pset = Set(panels ?? [])
        for k in keys {
            guard let ids = cat.index[k], !ids.isEmpty else { continue }
            res["key"] = .str(k)
            let aid = ids.count == 1 ? ids[0] : disambiguate(ids, unit: u, qualitative: qualitative, panels: pset, cat)
            if let aid {
                res["analyte_id"] = .str(aid)
                res["method"] = .str("catalog")
            } else {
                res["candidates"] = .arr(ids.map(RJ.str))
            }
            return .obj(res)
        }
        return .obj(res)
    }

    static func detectPanelsRef(texts: [String], testNames: [String], _ cat: AnalyteCatalog = .shared) -> [String] {
        var found = Set<String>()
        for t in texts {
            let s = " " + analyteWords(t).joined(separator: " ") + " "
            for (panel, phrases) in panelKeywords where phrases.contains(where: { s.contains(" " + $0 + " ") }) {
                found.insert(panel)
            }
        }
        var counts: [String: Int] = [:]
        var seen = Set<String>()
        for n in testNames {
            let m = mapAnalyteRef(n, cat)
            guard m["method"].string == "catalog", let aid = m["analyte_id"].string, !seen.contains(aid) else { continue }
            seen.insert(aid)
            for p in (cat.entry(aid)["panels"].array ?? []).compactMap(\.string) { counts[p, default: 0] += 1 }
        }
        for (p, c) in counts where c >= panelMinAnalytes { found.insert(p) }
        return panelIDs.filter(found.contains)
    }

    static func round4(_ x: Double) -> Double { (x * 10000 + 0.5 + 1e-9).rounded(.down) / 10000 }

    static func convertUnit(_ analyteID: String?, _ value: Double?, _ unit: String?, _ cat: AnalyteCatalog = .shared) -> RJ {
        let u = canonicalUnitSpelling(unit)
        var out: [String: RJ] = ["unit": .string(u), "canonical_value": .null, "canonical_unit": .null, "status": .null]
        guard let analyteID, cat.hasEntry(analyteID) else {
            out["status"] = .str("unmapped")
            return .obj(out)
        }
        let e = cat.entry(analyteID)
        guard let value else {
            out["status"] = .str("no_value")
            return .obj(out)
        }
        var factor: Double?
        var offset: Double?
        if e["canonical_unit"].isNull, e["kind"].string == "numeric" {
            if u == nil || u == "ratio" {
                factor = 1
                offset = 0
            }
        } else if u == nil {
            out["status"] = .str("unit_missing")
            return .obj(out)
        } else {
            for x in e["units"].array ?? [] where x["unit"].string == u {
                factor = x["factor"].double
                offset = x["offset"].double
                break
            }
        }
        guard let factor, let offset else {
            out["status"] = .str("unit_unknown")
            return .obj(out)
        }
        out["canonical_value"] = .number(round4(value * factor + offset))
        out["canonical_unit"] = e["canonical_unit"]
        out["status"] = .str("converted")
        return .obj(out)
    }

    // MARK: §19 Observations

    static let observationKeys = ["id", "record_id", "field_id", "analyte_id", "analyte_method", "raw_name", "value_num",
                                  "value_text", "unit", "canonical_value", "canonical_unit", "ref_low", "ref_high", "ref_text",
                                  "flag", "observed_date", "observed_date_method", "method", "confidence", "state", "source_page",
                                  "source_bbox", "evidence", "excluded_from_trends", "created_ms", "updated_ms"]
    static let obsSyncKeys = ["analyte_id", "analyte_method", "raw_name", "value_num", "value_text", "unit", "canonical_value",
                              "canonical_unit", "ref_low", "ref_high", "ref_text", "flag", "method", "confidence", "state",
                              "source_page", "source_bbox", "evidence"]

    static func observedDateRef(record: RJ, fields: [RJ]) -> RJ {
        for key in ["collection_date", "report_date"] {
            if let r = bestRow(fields, key) { return .obj(["date": r["value_text"], "method": .str(key)]) }
        }
        for key in ["document_date", "sort_date"] where record[key].truthy {
            return .obj(["date": record[key], "method": .str(key)])
        }
        return .obj(["date": .null, "method": .null])
    }

    static func observationFromField(_ fd: RJ, record: RJ, panels: [String], userAliases: [String: String]?, od: RJ, _ cat: AnalyteCatalog) -> [String: RJ] {
        let vj = fd["value_json"]
        let raw: RJ = vj["name"].truthy ? vj["name"] : fd["value_text"]
        let vn = vj["value_num"].double
        let m = mapAnalyteRef(raw.string, panels: panels, userAliases: userAliases, unit: vj["unit"].string, qualitative: vn == nil, cat)
        let conv = convertUnit(m["analyte_id"].string, vn, vj["unit"].string, cat)
        let state = fd["state"].string
        return [
            "id": .null, "record_id": record["id"], "field_id": fd["id"], "analyte_id": m["analyte_id"],
            "analyte_method": m["method"], "raw_name": raw, "value_num": vj["value_num"],
            "value_text": vj["value"].isNull ? .str("") : vj["value"], "unit": conv["unit"],
            "canonical_value": conv["canonical_value"], "canonical_unit": conv["canonical_unit"],
            "ref_low": vj["ref_low"], "ref_high": vj["ref_high"], "ref_text": vj["ref_text"],
            "flag": vj["flag"].truthy ? vj["flag"] : .str("unknown"), "observed_date": od["date"], "observed_date_method": od["method"],
            "method": fd["method"], "confidence": fd["confidence"],
            "state": .str(state == "confirmed" || state == "user" ? "confirmed" : "suggested"),
            "source_page": fd["source_page"], "source_bbox": fd["source_bbox"], "evidence": fd["evidence"],
            "excluded_from_trends": .int(0), "created_ms": .null, "updated_ms": .null,
        ]
    }

    static func recordPanels(_ fields: [RJ], _ cat: AnalyteCatalog = .shared) -> [String] {
        let live = fields.filter { $0["state"].string != "rejected" }
        let texts = live.filter { $0["field_key"].string == "report_name" }.compactMap { $0["value_text"].string }
        let names = live.filter { $0["field_key"].string == "test_result" }.compactMap { r -> String? in
            r["value_json"]["name"].truthy ? r["value_json"]["name"].string : r["value_text"].string
        }
        return detectPanelsRef(texts: texts, testNames: names, cat)
    }

    static func promoteObservations(fields: [RJ], existing: [RJ], record: RJ, userAliases: [String: String]?, nowMs: RJ = .int(0),
                                    newID: (Int) -> String = { "obs-\($0)" }, _ cat: AnalyteCatalog = .shared) -> RJ {
        let panels = recordPanels(fields, cat)
        let od = observedDateRef(record: record, fields: fields)
        var obs = existing.map { $0.object ?? [:] }
        var byField: [String: Int] = [:]
        for (i, o) in obs.enumerated() {
            if let f = o["field_id"]?.string, byField[f] == nil { byField[f] = i }
        }
        var actions: [RJ] = []
        var inserted = 0
        for fd in fields where fd["field_key"].string == "test_result" {
            let fid = fd["id"].string ?? ""
            guard let idx = byField[fid] else {
                if fd["state"].string == "rejected" { continue }
                inserted += 1
                var new = observationFromField(fd, record: record, panels: panels, userAliases: userAliases, od: od, cat)
                new["id"] = .str(newID(inserted))
                new["created_ms"] = nowMs
                new["updated_ms"] = nowMs
                obs.append(new)
                byField[fid] = obs.count - 1
                actions.append(.obj(["action": .str("insert"), "id": new["id"]!, "field_id": fd["id"]]))
                continue
            }
            var o = obs[idx]
            if fd["state"].string == "rejected" {
                if ["suggested", "confirmed"].contains(o["state"]?.string ?? "") {
                    o["state"] = .str("rejected")
                    o["updated_ms"] = nowMs
                    obs[idx] = o
                    actions.append(.obj(["action": .str("reject"), "id": o["id"] ?? .null, "field_id": fd["id"]]))
                } else {
                    actions.append(.obj(["action": .str("keep"), "id": o["id"] ?? .null, "field_id": fd["id"]]))
                }
                continue
            }
            var changed = false
            if o["state"]?.string == "suggested" {
                let target = observationFromField(fd, record: record, panels: panels, userAliases: userAliases, od: od, cat)
                for k in obsSyncKeys where !RJ.same(o[k] ?? .null, target[k] ?? .null) {
                    o[k] = target[k]
                    changed = true
                }
            }
            if o["state"]?.string != "rejected", o["observed_date_method"]?.string != "user" {
                if !RJ.same(o["observed_date"] ?? .null, od["date"]) || !RJ.same(o["observed_date_method"] ?? .null, od["method"]) {
                    o["observed_date"] = od["date"]
                    o["observed_date_method"] = od["method"]
                    changed = true
                }
            }
            if changed { o["updated_ms"] = nowMs }
            obs[idx] = o
            actions.append(.obj(["action": .str(changed ? "update" : "keep"), "id": o["id"] ?? .null, "field_id": fd["id"]]))
        }
        return .obj(["observations": .arr(obs.map(RJ.obj)), "actions": .arr(actions), "panels": .arr(panels.map(RJ.str))])
    }

    static func parseRefText(_ refText: String?) -> (Double?, Double?) {
        guard let refText else { return (nil, nil) }
        let rf = fold(refText).rStrip(" ()[]")
        for (rx, kind) in [(reRefRange, "range"), (reRefHigh, "high"), (reRefLow, "low")] {
            if let fm = rx.fullmatch(rf) {
                var tmp = Tail()
                setRef(&tmp, fm, kind)
                return (tmp.refLow, tmp.refHigh)
            }
        }
        return (nil, nil)
    }

    static let reEditRange = Rx("([0-9]{1,3})[ ]?-[ ]?([0-9]{1,3})")
    static let reEditISODate = Rx("([0-9]{4})-([0-9]{2})-([0-9]{2})")

    static func valueParts(_ valueText: String?) -> (Double?, String?, Bool, Bool) {
        let vf = fold(valueText ?? "").rStrip(" ")
        if reEditRange.fullmatch(vf) != nil { return (nil, nil, true, false) }
        if let vm = reValueNum.fullmatch(vf) { return (numValue(vm.g(2)!), vm.g(1), false, false) }
        return (nil, nil, false, true)
    }

    static func convertBetween(_ analyteID: String?, _ value: Double?, _ fromUnit: String?, _ toUnit: String?, _ cat: AnalyteCatalog) -> Double? {
        guard let value, let analyteID, cat.hasEntry(analyteID), let fromUnit, let toUnit else { return nil }
        let units = cat.entry(analyteID)["units"].array ?? []
        guard let f = units.first(where: { $0["unit"].string == fromUnit }), let t = units.first(where: { $0["unit"].string == toUnit }) else { return nil }
        let canonical = value * (f["factor"].double ?? 1) + (f["offset"].double ?? 0)
        return round4((canonical - (t["offset"].double ?? 0)) / (t["factor"].double ?? 1))
    }

    static func recomputeFlag(_ valueText: String?, _ refText: String?, _ refLow: Double?, _ refHigh: Double?) -> String {
        let (vn, comp, rng, qual) = valueParts(valueText)
        var value = fold(valueText ?? "").rStrip(" ")
        if rng { value = value.replacingOccurrences(of: " ", with: "") }
        var res = Tail()
        res.refLow = refLow
        res.refHigh = refHigh
        res.valueNum = vn
        res.comparator = comp
        res.rangeValue = rng
        res.qualitative = qual
        res.value = value
        res.refText = refText
        return resolveFlag(res)
    }

    static func editObservationRef(_ obs: RJ, _ patch: RJ, _ cat: AnalyteCatalog = .shared) -> RJ {
        var o = obs.object ?? [:]
        let p = patch.object ?? [:]
        func has(_ k: String) -> Bool { p[k] != nil }
        if let aid = patch["analyte_id"].string, !cat.hasEntry(aid) { return .obj(["observation": obs, "error": .str("unknown_analyte")]) }
        if !patch["observed_date"].isNull {
            guard let s = patch["observed_date"].string, let m = reEditISODate.fullmatch(s),
                  YMD.valid(Int(m.g(1)!)!, Int(m.g(2)!)!, Int(m.g(3)!)!) != nil
            else { return .obj(["observation": obs, "error": .str("bad_date")]) }
        }
        if has("value"), reWS.sub(patch["value"].string ?? "", " ").rStrip(" ").isEmpty {
            return .obj(["observation": obs, "error": .str("empty_value")])
        }
        let now = patch["now_ms"]
        if patch["remove"].truthy {
            o["state"] = .str("rejected")
            o["updated_ms"] = now
            return .obj(["observation": .obj(o), "error": .null])
        }
        if has("value") {
            let text = reWS.sub(patch["value"].string ?? "", " ").rStrip(" ")
            o["value_text"] = .str(text)
            o["value_num"] = .number(valueParts(text).0)
        }
        var unitChanged = false
        var oldUnit: String?
        if has("unit") {
            let newUnit = canonicalUnitSpelling(patch["unit"].string)
            oldUnit = o["unit"]?.string
            unitChanged = newUnit != oldUnit
            o["unit"] = .string(newUnit)
        }
        if has("ref_text") {
            let rt = reWS.sub(patch["ref_text"].string ?? "", " ").rStrip(" ")
            let text: String? = rt.isEmpty ? nil : rt
            let (lo, hi) = parseRefText(text)
            o["ref_text"] = .string(text)
            o["ref_low"] = .number(lo)
            o["ref_high"] = .number(hi)
        }
        if has("analyte_id") {
            o["analyte_id"] = patch["analyte_id"]
            o["analyte_method"] = patch["analyte_id"].isNull ? .null : .str("user")
        }
        if has("observed_date") {
            o["observed_date"] = patch["observed_date"]
            o["observed_date_method"] = .str("user")
        }
        if has("excluded_from_trends") { o["excluded_from_trends"] = .int(patch["excluded_from_trends"].truthy ? 1 : 0) }
        var rangesOK = true
        let curLow = o["ref_low"]?.double, curHigh = o["ref_high"]?.double
        if unitChanged, !has("ref_text"), curLow != nil || curHigh != nil {
            let aid = o["analyte_id"]?.string
            let lo = convertBetween(aid, curLow, oldUnit, o["unit"]?.string, cat)
            let hi = convertBetween(aid, curHigh, oldUnit, o["unit"]?.string, cat)
            if (curLow != nil && lo == nil) || (curHigh != nil && hi == nil) {
                o["ref_low"] = .null
                o["ref_high"] = .null
                rangesOK = false
            } else {
                o["ref_low"] = .number(lo)
                o["ref_high"] = .number(hi)
            }
        }
        if !rangesOK {
            o["flag"] = .str("unknown")
        } else if has("value") || has("ref_text") || unitChanged {
            o["flag"] = .str(recomputeFlag(o["value_text"]?.string, o["ref_text"]?.string, o["ref_low"]?.double, o["ref_high"]?.double))
        }
        let conv = convertUnit(o["analyte_id"]?.string, o["value_num"]?.double, o["unit"]?.string, cat)
        o["canonical_value"] = conv["canonical_value"]
        o["canonical_unit"] = conv["canonical_unit"]
        o["state"] = .str("user")
        o["updated_ms"] = now
        return .obj(["observation": .obj(o), "error": .null])
    }

    static func applyUserAliasRef(_ observations: [RJ], rawName: String?, analyteID: String?, nowMs: RJ = .int(0), _ cat: AnalyteCatalog = .shared) -> RJ {
        let key = normalizeTestNameRef(rawName)
        guard let analyteID, cat.hasEntry(analyteID) else {
            return .obj(["alias": .null, "observations": .arr(observations), "updated_ids": .arr([]), "error": .str("unknown_analyte")])
        }
        var out: [RJ] = []
        var updated: [RJ] = []
        for o0 in observations {
            var o = o0.object ?? [:]
            if o["state"]?.string != "rejected", (o["analyte_id"] ?? .null).isNull, testNameKeys(o["raw_name"]?.string).contains(key) {
                let conv = convertUnit(analyteID, o["value_num"]?.double, o["unit"]?.string, cat)
                o["analyte_id"] = .str(analyteID)
                o["analyte_method"] = .str("user_alias")
                o["canonical_value"] = conv["canonical_value"]
                o["canonical_unit"] = conv["canonical_unit"]
                o["updated_ms"] = nowMs
                updated.append(o["id"] ?? .null)
            }
            out.append(.obj(o))
        }
        return .obj(["alias": .obj(["normalized_name": .str(key), "analyte_id": .str(analyteID)]), "observations": .arr(out),
                     "updated_ids": .arr(updated), "error": .null])
    }

    // MARK: §21 Trends

    static func trendSeriesRef(_ observations: [RJ], analyteID: String, _ cat: AnalyteCatalog = .shared) -> RJ {
        let rows = observations.filter {
            $0["analyte_id"].string == analyteID && $0["state"].string != "rejected" && !$0["excluded_from_trends"].truthy && $0["observed_date"].truthy
        }.stableSorted { a, b in
            (a["observed_date"].string ?? "", a["created_ms"].double ?? 0, a["id"].string ?? "") < (b["observed_date"].string ?? "", b["created_ms"].double ?? 0, b["id"].string ?? "")
        }
        struct S {
            var kind: String
            var unit: RJ
            var points: [[String: RJ]] = []
        }
        var series: [S] = []
        var skipped: [RJ] = []
        for o in rows {
            let kind: String
            let unit: RJ
            let value: RJ
            if !o["canonical_value"].isNull {
                kind = "c"; unit = o["canonical_unit"]; value = o["canonical_value"]
            } else if !o["value_num"].isNull {
                kind = "u"; unit = o["unit"]; value = o["value_num"]
            } else {
                skipped.append(o["id"])
                continue
            }
            var si = series.firstIndex { $0.kind == kind && RJ.same($0.unit, unit) }
            if si == nil {
                series.append(S(kind: kind, unit: unit))
                si = series.count - 1
            }
            var lo = o["ref_low"]
            var hi = o["ref_high"]
            if kind == "c" {
                lo = lo.isNull ? .null : convertUnit(analyteID, lo.double, o["unit"].string, cat)["canonical_value"]
                hi = hi.isNull ? .null : convertUnit(analyteID, hi.double, o["unit"].string, cat)["canonical_value"]
            }
            if let pi = series[si!].points.firstIndex(where: { RJ.same($0["date"]!, o["observed_date"]) && RJ.same($0["value"]!, value) }) {
                var p = series[si!].points[pi]
                var rids = p["record_ids"]!.array!
                if !rids.contains(where: { RJ.same($0, o["record_id"]) }) { rids.append(o["record_id"]) }
                p["record_ids"] = .arr(rids)
                p["observation_ids"] = .arr(p["observation_ids"]!.array! + [o["id"]])
                series[si!].points[pi] = p
            } else {
                series[si!].points.append([
                    "date": o["observed_date"], "value": value, "value_text": o["value_text"], "unit": o["unit"], "flag": o["flag"],
                    "ref_low": lo, "ref_high": hi, "record_ids": .arr([o["record_id"]]), "observation_ids": .arr([o["id"]]),
                ])
            }
        }
        func emit(_ s: S) -> RJ {
            var band: RJ = .null
            for p in s.points.reversed() where !(p["ref_low"] ?? .null).isNull || !(p["ref_high"] ?? .null).isNull {
                band = .obj(["low": p["ref_low"] ?? .null, "high": p["ref_high"] ?? .null])
                break
            }
            return .obj(["unit": s.unit, "convertible": .bool(s.kind == "c"), "points": .arr(s.points.map(RJ.obj)), "band": band])
        }
        let ordered = series.filter { $0.kind == "c" } + series.filter { $0.kind != "c" }
        let e = cat.hasEntry(analyteID) ? cat.entry(analyteID) : RJ.null
        return .obj(["analyte_id": .str(analyteID), "display_name": e["display_name"], "series": .arr(ordered.map(emit)), "skipped_ids": .arr(skipped)])
    }

    static func roundDec(_ x: Double, _ d: Int) -> Double {
        let scale = pow(10.0, Double(d))
        let r = (abs(x) * scale + 0.5 + 1e-9).rounded(.down) / scale
        return x < 0 && r != 0 ? -r : r
    }

    static func formatValue(_ x: Double, _ d: Int) -> String {
        let r = roundDec(x, d)
        return (r < 0 ? "-" : "") + String(format: "%.\(d)f", abs(r))
    }

    static func miniTrendRef(_ trend: RJ, observationID: String, _ cat: AnalyteCatalog = .shared) -> RJ {
        let none: RJ = .obj(["show": .bool(false), "values": .arr([]), "text": .null, "unit": .null, "change": .null, "change_text": .null])
        let aid = trend["analyte_id"].string ?? ""
        let d = cat.hasEntry(aid) ? Int(cat.entry(aid)["decimals"].double ?? 2) : 2
        for s in trend["series"].array ?? [] {
            let points = s["points"].array ?? []
            for (idx, p) in points.enumerated() {
                guard (p["observation_ids"].array ?? []).contains(where: { $0.string == observationID }) else { continue }
                let pts = Array(points[0...idx])
                if pts.count < 2 { return none }
                let vals = pts.suffix(5).map { formatValue($0["value"].double ?? 0, d) }
                let prev = pts[pts.count - 2]
                let delta = roundDec((p["value"].double ?? 0) - (prev["value"].double ?? 0), d)
                let sign = delta > 0 ? "+" : (delta < 0 ? "−" : "")
                var text = sign + String(format: "%.\(d)f", abs(delta))
                if s["unit"].truthy { text += " " + (s["unit"].string ?? "") }
                return .obj(["show": .bool(true), "values": .arr(vals.map(RJ.str)), "text": .str(vals.joined(separator: " → ")), "unit": s["unit"],
                             "change": .obj(["delta": .number(delta), "unit": s["unit"], "since_date": prev["date"]]),
                             "change_text": .str(text + " since " + (prev["date"].string ?? ""))])
            }
        }
        return none
    }

    // MARK: §19 Entities

    static let doctorTitles: Set<String> = ["dr", "doctor", "prof", "professor"]
    static let entityQualifications: Set<String> = qualifications.union(["mrcgp", "fcps", "facp", "frcpath", "dpm", "dortho", "dlo", "dvd", "dnbe", "fnb", "fracs", "mams"])
    static let facilityWord: [String: String?] = [
        "hospitals": "hospital", "clinics": "clinic", "laboratories": "lab", "laboratory": "lab",
        "labs": "lab", "diagnostics": "diagnostic", "centre": "center", "centres": "center",
        "centers": "center", "pathlabs": "pathlab", "speciality": "specialty", "specialities": "specialty",
        "specialties": "specialty", "pvt": nil, "private": nil, "ltd": nil, "limited": nil,
        "llp": nil, "inc": nil, "and": nil,
    ]
    static let facilityJoin: [(String, String, String)] = [("health", "care", "healthcare"), ("path", "lab", "pathlab"), ("multi", "specialty", "multispecialty"),
                                                           ("super", "specialty", "superspecialty"), ("poly", "clinic", "polyclinic")]

    static func lettersOf(_ tok: String) -> String {
        String(String.UnicodeScalarView(fold(tok).unicodeScalars.filter { $0.value >= 97 && $0.value <= 122 }))
    }

    static func doctorDisplayName(_ value: String?) -> String {
        var p = reWS.sub(pfold(value ?? ""), " ").rStrip(" ")
        let cuts = [p.rFind(","), p.rFind("(")].filter { $0 >= 0 }
        if let cut = cuts.min() { p = p.rSub(0, cut) }
        var toks = p.rStrip(" .-").rSplitSpace.filter { !$0.isEmpty }
        if let t = toks.first {
            let t0 = fold(t)
            for pre in ["dr.", "prof."] where t0.hasPrefix(pre) && t0.rLen > pre.rLen {
                toks = [t.rSub(0, pre.rLen), t.rSub(pre.rLen)] + toks.dropFirst()
                break
            }
        }
        while toks.count > 1, doctorTitles.contains(lettersOf(toks[0])) { toks.removeFirst() }
        while toks.count > 1, entityQualifications.contains(lettersOf(toks[toks.count - 1])) { toks.removeLast() }
        return toks.joined(separator: " ").rStrip(" .-'")
    }

    static func normalizeDoctorName(_ value: String?) -> String { analyteWords(doctorDisplayName(value)).joined(separator: " ") }

    static func facilityDisplayName(_ value: String?) -> String { reWS.sub(pfold(value ?? ""), " ").rStrip(" ,.-|") }

    static func normalizeFacilityName(_ value: String?) -> String {
        let ws = analyteWords(facilityDisplayName(value))
        var out: [String] = []
        for w in ws {
            if let mapped = facilityWord[w] {
                if let m = mapped { out.append(m) }
            } else {
                out.append(w)
            }
        }
        var joined: [String] = []
        for w in out {
            if let last = joined.last, let pair = facilityJoin.first(where: { $0.0 == last && $0.1 == w }) {
                joined[joined.count - 1] = pair.2
                continue
            }
            joined.append(w)
        }
        if joined.first == "the", joined.count > 1 { joined.removeFirst() }
        return joined.isEmpty ? ws.joined(separator: " ") : joined.joined(separator: " ")
    }

    static func bestOf(_ rows: [RJ]) -> RJ? {
        let rank = ["user": 0, "confirmed": 1, "suggested": 2]
        return rows.enumerated().filter { _, r in
            r["state"].string != "rejected" && (["user", "confirmed"].contains(r["state"].string ?? "") || (r["confidence"].double ?? 0) >= 0.6)
        }.stableSorted { a, b in
            (rank[a.element["state"].string ?? ""] ?? 3, -(a.element["confidence"].double ?? 0), a.offset)
                < (rank[b.element["state"].string ?? ""] ?? 3, -(b.element["confidence"].double ?? 0), b.offset)
        }.first?.element
    }

    static func rebuildEntities(_ fields: [RJ]) -> RJ {
        func role(_ r: RJ) -> String? { r["value_json"]["role"].string }
        let docs = fields.filter { $0["field_key"].string == "doctor_name" }
        let prim = bestOf(docs.filter { role($0) != "referrer" })
        let ref = bestOf(docs.filter { role($0) == "referrer" })
        let spec = bestRow(fields, "doctor_specialty")
        let fac = bestRow(fields, "facility")
        var entities: [[String: RJ]] = []
        var links: [RJ] = []
        func add(_ kind: String, _ display: String, _ norm: String, _ specialty: String?, _ rl: String) {
            guard !norm.isEmpty else { return }
            var idx = entities.firstIndex { $0["kind"]?.string == kind && $0["normalized_name"]?.string == norm }
            if idx == nil {
                entities.append(["kind": .str(kind), "display_name": .str(display), "normalized_name": .str(norm), "specialty": .null])
                idx = entities.count - 1
            }
            if let specialty, !specialty.isEmpty, !(entities[idx!]["specialty"]?.truthy ?? false) { entities[idx!]["specialty"] = .str(specialty) }
            let link: RJ = .obj(["kind": .str(kind), "normalized_name": .str(norm), "role": .str(rl)])
            if !links.contains(where: { RJ.same($0, link) }) { links.append(link) }
        }
        if let prim {
            add("doctor", doctorDisplayName(prim["value_text"].string), normalizeDoctorName(prim["value_text"].string), spec?["value_text"].string, "doctor")
        }
        if let ref {
            add("doctor", doctorDisplayName(ref["value_text"].string), normalizeDoctorName(ref["value_text"].string), nil, "referrer")
        }
        if let fac {
            add("facility", facilityDisplayName(fac["value_text"].string), normalizeFacilityName(fac["value_text"].string), nil, "facility")
        }
        return .obj(["entities": .arr(entities.map(RJ.obj)), "record_entities": .arr(links)])
    }

    // MARK: §22 Relations

    static let linkPriority = ["follow_up", "prescription_for", "previous_report", "same_episode"]
    static let labLike: Set<String> = ["lab_report", "imaging_report", "diagnostic_report"]

    static func daysBetween(_ a: String?, _ b: String?) -> Int {
        guard let x = YMD.iso(a ?? ""), let y = YMD.iso(b ?? "") else { return Int.max / 4 }
        return y.jdn - x.jdn
    }

    static func suggestRelations(record: RJ, candidates: [RJ], today: String, existingLinks: [RJ]) -> RJ {
        if record["archived"].truthy, record["split_parent"].truthy { return .obj(["links": .arr([])]) }
        let rid = record["id"].string ?? ""
        let pairs = Set(existingLinks.map { ($0["a_id"].string ?? "") + "\u{1}" + ($0["b_id"].string ?? "") })
        let pending = existingLinks.filter { $0["status"].string == "suggested" && ($0["a_id"].string == rid || $0["b_id"].string == rid) }.count
        let slots = max(0, 5 - pending)
        func strs(_ v: RJ) -> Set<String> { Set((v.array ?? []).compactMap(\.string)) }
        var found: [(Double, Int, String, RJ)] = []
        for c in candidates {
            let cid = c["id"].string ?? ""
            if cid == rid || (c["archived"].truthy && c["split_parent"].truthy) { continue }
            let gap = daysBetween(record["sort_date"].string, c["sort_date"].string)
            if abs(gap) > 180 { continue }
            let (a, b) = rid < cid ? (rid, cid) : (cid, rid)
            if pairs.contains(a + "\u{1}" + b) { continue }
            var kinds: [String: Double] = [:]
            var reasons: [String] = []
            if labLike.contains(record["record_type"].string ?? ""), labLike.contains(c["record_type"].string ?? "") {
                let samePanel = !strs(record["panels"]).isDisjoint(with: strs(c["panels"]))
                let rn = normText(record["report_name"].string ?? "")
                let sameName = !rn.isEmpty && rn == normText(c["report_name"].string ?? "")
                if samePanel || sameName {
                    var s = 0.5
                    if samePanel { reasons.append("same_panel") }
                    if sameName { reasons.append("same_report_name") }
                    if strs(record["analytes"]).intersection(strs(c["analytes"])).count >= 3 {
                        s += 0.2
                        reasons.append("shared_analytes")
                    }
                    kinds["previous_report"] = s
                }
            }
            let near = abs(gap) <= 30
            if near, !strs(record["doctors"]).isDisjoint(with: strs(c["doctors"])) {
                kinds["same_episode", default: 0] += 0.4
                reasons.append("same_doctor")
            }
            if near, !strs(record["facilities"]).isDisjoint(with: strs(c["facilities"])) {
                kinds["same_episode", default: 0] += 0.2
                reasons.append("same_facility")
            }
            for (p, v) in [(record, c), (c, record)] {
                let d = daysBetween(v["sort_date"].string, p["sort_date"].string)
                if p["record_type"].string == "prescription", ["consultation_note", "discharge_summary"].contains(v["record_type"].string ?? ""),
                   !strs(p["doctors"]).isDisjoint(with: strs(v["doctors"])), d >= 0, d <= 14 {
                    kinds["prescription_for"] = 0.6
                    reasons.append("prescription_after_visit")
                    break
                }
            }
            for (x, y) in [(record, c), (c, record)] {
                if (x["follow_up_dates"].array ?? []).contains(where: { abs(daysBetween($0.string, y["sort_date"].string)) <= 7 }) {
                    kinds["follow_up"] = 0.6
                    reasons.append("follow_up_date")
                    break
                }
            }
            if kinds.isEmpty { continue }
            let total = round2(min(1.0, linkPriority.compactMap { kinds[$0] }.reduce(0, +)))
            if total < 0.6 { continue }
            let kind = kinds.keys.sorted { x, y in
                (-round2(kinds[x]!), linkPriority.firstIndex(of: x) ?? 9) < (-round2(kinds[y]!), linkPriority.firstIndex(of: y) ?? 9)
            }[0]
            found.append((-total, abs(gap), cid, .obj(["a_id": .str(a), "b_id": .str(b), "kind": .str(kind), "origin": .str("suggested"),
                                                       "status": .str("suggested"), "score": .number(total), "reasons": .arr(reasons.map(RJ.str))])))
        }
        found = found.stableSorted { ($0.0, $0.1, $0.2) < ($1.0, $1.1, $1.2) }
        return .obj(["links": .arr(found.prefix(slots).map(\.3))])
    }
}
