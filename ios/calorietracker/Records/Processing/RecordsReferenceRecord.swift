import Foundation

// §15 highlights / review, §8.1 applyExtraction, §9.3 chunks + validation, §15 hashing,
// §17 query parser (port of `scripts/records_reference.py`).
extension RR {
    // MARK: §15 Highlights

    static let hlSuffix: [String: String] = [
        "low": "below the report reference range", "high": "above the report reference range",
        "critical_low": "marked critical on the report", "critical_high": "marked critical on the report",
        "abnormal": "marked abnormal on the report",
    ]

    static func distance(_ vj: RJ) -> Double {
        guard let v = vj["value_num"].double else { return 0 }
        let f = vj["flag"].string
        if f == "low" || f == "critical_low", let lo = vj["ref_low"].double { return lo != 0 ? (lo - v) / abs(lo) : (lo - v) }
        if f == "high" || f == "critical_high", let hi = vj["ref_high"].double { return hi != 0 ? (v - hi) / abs(hi) : (v - hi) }
        return 0
    }

    static func pyStr(_ value: RJ) -> String {
        switch value {
        case .null: "None"
        case .str(let s): s
        case .int(let i): "\(i)"
        case .num(let d): "\(d)"
        case .bool(let b): b ? "True" : "False"
        default: value.jsonText
        }
    }

    /// §15 "Important highlights" list (Records tab, Summary): non-dismissed `important` highlights of
    /// non-archived records, with `since` only records whose `sort_date` >= since; ordered by
    /// sort_date desc, seq desc, position asc; first `limit`. Mirrors `RecordsDatabase.importantHighlights`.
    static func importantHighlights(_ records: [RJ], _ highlights: [RJ], limit: Int, since: String?) -> [RJ] {
        var byID: [String: RJ] = [:]
        for record in records { if let id = record["id"].string { byID[id] = record } }
        var rows: [(RJ, RJ)] = []
        for h in highlights {
            guard let r = byID[h["record_id"].string ?? ""], !r["archived"].truthy,
                  h["section"].string == "important", !h["dismissed"].truthy else { continue }
            if let since, (r["sort_date"].string ?? "") < since { continue }
            rows.append((r, h))
        }
        func position(_ h: RJ) -> Double { h["position"].double ?? 0 }
        func seq(_ r: RJ) -> Double { r["seq"].double ?? 0 }
        rows = rows.stableSorted { position($0.1) < position($1.1) }
        rows = rows.stableSorted { a, b in
            let (da, db) = (a.0["sort_date"].string ?? "", b.0["sort_date"].string ?? "")
            return da != db ? da > db : seq(a.0) > seq(b.0)
        }
        return rows.prefix(max(limit, 0)).map { _, h in
            .obj(["id": h["id"], "record_id": h["record_id"], "section": h["section"], "text": h["text"],
                  "position": .int(Int(position(h)))])
        }
    }

    static func buildHighlights(_ fields: [RJ], summary: RJ = .null) -> [RJ] {
        var important: [(Int, Double, Int, String, RJ)] = []
        var meds: [(Int, String, RJ)] = []
        var recs: [(Int, String, RJ)] = []
        for (idx, fd) in fields.enumerated() {
            if fd["state"].string == "rejected" { continue }
            let vj = fd["value_json"]
            let key = fd["key"].string
            if key == "test_result", let flag = vj["flag"].string, let suffix = hlSuffix[flag] {
                var text = (vj["name"].truthy ? pyStr(vj["name"]) : pyStr(fd["value_text"])) + ": " + pyStr(vj["value"])
                if vj["unit"].truthy { text += " " + pyStr(vj["unit"]) }
                text += " — " + suffix
                let rank = flag.hasPrefix("critical") ? 0 : (flag == "low" || flag == "high" ? 1 : 2)
                important.append((rank, -distance(vj), idx, text, fd))
            } else if key == "medication" {
                var text = vj["name"].truthy ? pyStr(vj["name"]) : pyStr(fd["value_text"])
                if vj["strength"].truthy { text += " " + pyStr(vj["strength"]) }
                for k in ["frequency", "duration"] where vj[k].truthy { text += " · " + pyStr(vj[k]) }
                meds.append((idx, text, fd))
            } else if key == "recommendation" {
                recs.append((idx, pyStr(fd["value_text"]), fd))
            }
        }
        important = important.stableSorted { ($0.0, $0.1, $0.2) < ($1.0, $1.1, $1.2) }
        var out: [RJ] = []
        func emit(_ section: String, _ rows: [(Int, String, RJ)], _ limit: Int) {
            var seen = Set<String>()
            var pos = 0
            for (idx, text, fd) in rows {
                if seen.contains(text) || pos >= limit { continue }
                seen.insert(text)
                out.append(.obj(["section": .str(section), "text": .str(text), "position": .int(pos), "field_index": .int(idx),
                                 "source_page": fd["source_page"], "confidence": fd["confidence"]]))
                pos += 1
            }
        }
        emit("important", important.map { ($0.2, $0.3, $0.4) }, 8)
        emit("medications", meds, 10)
        emit("recommendations", recs, 5)
        if let text0 = summary["text"].string, !text0.isEmpty {
            var text = text0
            if text.unicodeScalars.count > 400 { text = String(String.UnicodeScalarView(text.unicodeScalars.prefix(399))) + "…" }
            let pages = summary["source_pages"].array ?? []
            out.append(.obj(["section": .str("summary"), "text": .str(text), "position": .int(0), "field_index": .null,
                             "source_page": pages.first ?? .null, "confidence": .null]))
        }
        return out
    }

    // MARK: §15 Review

    static let keyFields: Set<String> = Set(["report_name", "doctor_name", "facility"] + dateKeys)

    static func conflicts(_ rows: [RJ]) -> [String] {
        var vals: [String: Set<String>] = [:]
        for r in rows {
            let key = r["field_key"].string ?? ""
            if r["state"].string == "rejected" || multiValuedKeys.contains(key) { continue }
            let role = r["value_json"]["role"].string
            let slot = key + (role.map { ":" + $0 } ?? "")
            vals[slot, default: []].insert(normalizeValue(key, r["value_text"].string, r["value_json"]))
        }
        return vals.filter { $0.value.count >= 2 }.map(\.key).sorted()
    }

    static func reviewStatus(record: RJ, rows: [RJ], pendingSplit: Bool, pendingDuplicate: Bool) -> RJ {
        var reasons: [String] = []
        let reviewed = record["review_status"].string == "reviewed"
        if !reviewed {
            if record["type_method"].string != "user", record["record_type"].string == "other" || (record["type_confidence"].double ?? 0) < 0.7 {
                reasons.append("type_uncertain")
            }
            if !record["document_date"].truthy { reasons.append("no_date") }
        }
        for slot in conflicts(rows) {
            let key = String(slot.split(separator: ":")[0])
            let states = rows.filter { $0["field_key"].string == key && $0["state"].string != "rejected" }.compactMap { $0["state"].string }
            if states.contains("suggested") { reasons.append("conflict:" + slot) }
        }
        let low = Set(rows.filter { keyFields.contains($0["field_key"].string ?? "") && $0["state"].string == "suggested" && ($0["confidence"].double ?? 0) < 0.8 }
            .compactMap { $0["field_key"].string }).sorted()
        reasons += low.map { "low_confidence:" + $0 }
        if pendingSplit { reasons.append("split_pending") }
        if pendingDuplicate { reasons.append("duplicate_pending") }
        if !reviewed, ["text_unavailable", "ocr_failed"].contains(record["processing_error"].string ?? "") { reasons.append("text_failed") }
        if !reviewed, let ocr = record["ocr_confidence"].double, ocr < 0.6 { reasons.append("ocr_low_confidence") }
        if rows.contains(where: { $0["from_image"].truthy && $0["state"].string == "suggested" }) { reasons.append("ai_image_item") }
        if !reasons.isEmpty { return .obj(["status": .str("needs_review"), "reasons": .arr(reasons.map(RJ.str))]) }
        return .obj(["status": .str(reviewed ? "reviewed" : "none"), "reasons": .arr([])])
    }

    // MARK: §8.1 applyExtraction

    static func bestRow(_ rows: [RJ], _ key: String) -> RJ? {
        let rank = ["user": 0, "confirmed": 1, "suggested": 2]
        return rows.enumerated().filter { _, r in
            r["field_key"].string == key && r["state"].string != "rejected"
                && (["user", "confirmed"].contains(r["state"].string ?? "") || (r["confidence"].double ?? 0) >= 0.6)
        }.stableSorted { a, b in
            (rank[a.element["state"].string ?? ""] ?? 3, -(a.element["confidence"].double ?? 0), a.offset)
                < (rank[b.element["state"].string ?? ""] ?? 3, -(b.element["confidence"].double ?? 0), b.offset)
        }.first?.element
    }

    /// `newID` supplies inserted ids (vectors: `new-1`, `new-2`, …; the app: UUIDs).
    static func applyExtraction(existingRows: [RJ], newItems: [RJ], record: RJ = .null, classification: RJ = .null,
                                newID: (Int) -> String = { "new-\($0)" }) -> RJ {
        var rows = existingRows.map { $0.object ?? [:] }
        var actions: [RJ] = []
        var inserted = 0
        for it in newItems {
            let key = it["key"].string ?? ""
            let nv = normalizeValue(key, it["value_text"].string, it["value_json"])
            let matchIdx = rows.indices.filter {
                rows[$0]["field_key"]?.string == key
                    && normalizeValue(key, rows[$0]["value_text"]?.string, rows[$0]["value_json"] ?? .null) == nv
            }
            if let locked = matchIdx.first(where: { ["rejected", "confirmed", "user"].contains(rows[$0]["state"]?.string ?? "") }) {
                actions.append(.obj(["action": .str("skip_" + (rows[locked]["state"]?.string ?? "")), "id": rows[locked]["id"] ?? .null]))
                continue
            }
            if let first = matchIdx.first {
                if (it["confidence"].double ?? 0) > (rows[first]["confidence"]?.double ?? 0) {
                    for k in ["value_text", "value_json", "method", "evidence", "source_page", "source_bbox", "confidence"] {
                        rows[first][k] = it[k]
                    }
                    actions.append(.obj(["action": .str("update"), "id": rows[first]["id"] ?? .null]))
                } else {
                    actions.append(.obj(["action": .str("keep"), "id": rows[first]["id"] ?? .null]))
                }
                continue
            }
            inserted += 1
            let row: [String: RJ] = ["id": .str(newID(inserted)), "field_key": .str(key), "value_text": it["value_text"],
                                     "value_json": it["value_json"], "method": it["method"], "confidence": it["confidence"],
                                     "state": .str("suggested"), "source_page": it["source_page"], "source_bbox": it["source_bbox"],
                                     "evidence": it["evidence"]]
            rows.append(row)
            actions.append(.obj(["action": .str("insert"), "id": row["id"]!]))
        }
        let rowValues = rows.map(RJ.obj)
        var result: [String: RJ] = ["rows": .arr(rowValues), "actions": .arr(actions), "conflicts": .arr(conflicts(rowValues).map(RJ.str)), "record": .null]
        if !record.isNull { result["record"] = deriveRecord(record, rowValues, classification) }
        return .obj(result)
    }

    static func deriveRecord(_ record: RJ, _ rows: [RJ], _ classification: RJ = .null) -> RJ {
        var rec = record.object ?? [:]
        if let list = classification.array, !list.isEmpty, rec["type_method"]?.string != "user" {
            var best: RJ?
            for c in list where best == nil || (c["confidence"].double ?? 0) > (best!["confidence"].double ?? 0) { best = c }
            let type = best!["record_type"].string ?? "other"
            rec["record_type"] = .str(type)
            rec["category"] = .str(defaultCategory[type] ?? "other")
            rec["type_confidence"] = best!["confidence"]
            rec["type_method"] = best!["method"]
        }
        let method = rec["document_date_method"]?.string
        if method == nil || method == "import_time" || method == "file_metadata" {
            for key in documentDateOrder {
                if let r = bestRow(rows, key) {
                    rec["document_date"] = r["value_text"]
                    rec["document_date_precision"] = .str(r["value_json"]["precision"].string ?? "day")
                    rec["document_date_method"] = r["method"]
                    rec["sort_date"] = r["value_text"]
                    break
                }
            }
        }
        if rec["title_is_derived"]?.truthy == true {
            if let rn = bestRow(rows, "report_name") {
                rec["title"] = rn["value_text"]
            } else if let fac = bestRow(rows, "facility") {
                rec["title"] = .str((typeLabel[rec["record_type"]?.string ?? "other"] ?? "Record") + " — " + (fac["value_text"].string ?? ""))
            }
        }
        return .obj(rec)
    }

    // MARK: §9.3 Chunks

    static func aiChunks(_ pages: [String], mode: String) -> [RJ] {
        let limit = mode == "cloud" ? 12_000 : 2_500
        var blocks: [(Int, String)] = []
        for (i, t) in pages.enumerated() {
            let body = pfold(t).rStrip("\n")
            if body.rStripWS.isEmpty { continue }
            let header = "=== Page \(i + 1) ===\n"
            let room = limit - header.rLen
            var cur = ""
            for line0 in body.components(separatedBy: "\n") {
                var line = line0
                while line.rLen > room {
                    if !cur.isEmpty {
                        blocks.append((i + 1, header + cur))
                        cur = ""
                    }
                    blocks.append((i + 1, header + line.rSub(0, room)))
                    line = line.rSub(room)
                }
                let cand = cur.isEmpty ? line : cur + "\n" + line
                if cand.rLen > room {
                    blocks.append((i + 1, header + cur))
                    cur = line
                } else {
                    cur = cand
                }
            }
            if !cur.isEmpty { blocks.append((i + 1, header + cur)) }
        }
        var chunks: [(pages: [Int], text: String)] = []
        for (pno, text) in blocks {
            if let last = chunks.last, last.text.rLen + 2 + text.rLen <= limit {
                chunks[chunks.count - 1].text += "\n\n" + text
                if chunks[chunks.count - 1].pages.last != pno { chunks[chunks.count - 1].pages.append(pno) }
            } else {
                chunks.append(([pno], text))
            }
        }
        return chunks.map { .obj(["pages": .arr($0.pages.map(RJ.int)), "text": .str($0.text)]) }
    }

    // MARK: §9.3 Validation

    static func lenientJSON(_ raw: RJ) -> [String: RJ]? {
        if let obj = raw.object { return obj }
        guard let s = raw.string else { return nil }
        let a = s.rFind("{")
        let braces = (s as NSString).range(of: "}", options: .backwards)
        guard a >= 0, braces.location != NSNotFound, braces.location > a,
              let parsed = RJ.parse(s.rSub(a, braces.location + 1)) else { return nil }
        return parsed.object
    }

    static let reWS = Rx("[ \t\n]+")
    static let reNums = Rx("[0-9]+(?:[.,][0-9]+)*")
    static let aiFieldKeys: Set<String> = ["doctor_name", "doctor_specialty", "facility", "department", "patient_name",
                                           "patient_age", "patient_sex", "diagnosis", "symptom", "procedure", "recommendation", "document_time"]
    static let reISODate = Rx("([0-9]{4})-([0-9]{2})(?:-([0-9]{2}))?")

    static func flat(_ s: String?) -> String { reWS.sub(fold(s ?? ""), " ").rStrip(" ") }
    static func numbers(_ s: String?) -> [String] { reNums.findall(fold(s ?? "")).map { $0.replacingOccurrences(of: ",", with: "") } }
    static func numsIn(_ value: String, _ evidence: String) -> Bool {
        let ev = Set(numbers(evidence))
        return numbers(value).allSatisfy(ev.contains)
    }

    static func conf(_ x: RJ) -> Double {
        let v: Double?
        switch x {
        case .int(let i): v = Double(i)
        case .num(let d): v = d
        case .bool(let b): v = b ? 1 : 0
        case .str(let s): v = Double(s.rStripWS)
        default: v = nil
        }
        guard let v, !v.isNaN else { return 0.5 }
        return max(0, min(0.9, v))
    }

    static func wsCollapse(_ s: String) -> String { reWS.sub(s, " ").rStripWS }

    static func validateAI(pages: [String], aiJSON: RJ, mode: String, today: String, dateOrder: String, imagePages: [Int] = []) -> RJ {
        let method = mode == "local" ? "ai_local" : "ai_cloud"
        let images = Set(imagePages)
        let todayD = YMD.iso(today) ?? YMD(y: 2026, m: 1, d: 1)
        var res: [String: RJ] = ["record_type": .null, "report_name": .null, "items": .arr([]), "summary": .null, "dropped": .arr([]), "error": .null]
        guard let data = lenientJSON(aiJSON) else {
            res["error"] = .str("parse_error")
            return .obj(res)
        }
        let flatPages = pages.map { flat($0) }
        var items: [RJ] = []
        var dropped: [RJ] = []
        func drop(_ kind: String, _ i: Int, _ reason: String) {
            dropped.append(.obj(["kind": .str(kind), "index": .int(i), "reason": .str(reason)]))
        }
        func pageOf(_ kind: String, _ i: Int, _ item: RJ) -> Int? {
            let sp = item["source_page"]
            switch sp {
            case .null, .bool:
                drop(kind, i, "missing_source_page")
                return nil
            case .int(let v) where v >= 1 && v <= pages.count:
                return v
            default:
                drop(kind, i, "bad_source_page")
                return nil
            }
        }
        func evidenceOK(_ kind: String, _ i: Int, _ item: RJ, _ sp: Int) -> String? {
            guard let ev = item["evidence"].string, !flat(ev).isEmpty else {
                drop(kind, i, "evidence_not_found")
                return nil
            }
            let text = flatPages[sp - 1]
            if text.isEmpty, images.contains(sp) { return "image_only" }
            if !text.contains(flat(ev)) {
                drop(kind, i, "evidence_not_found")
                return nil
            }
            return "ok"
        }
        func contains(_ value: String?, _ ev: String?) -> Bool {
            let nv = normText(value)
            return !nv.isEmpty && (" " + normText(ev) + " ").contains(" " + nv + " ")
        }
        func finish(_ item: [String: RJ], _ sp: Int, _ status: String, _ c: Double) {
            var item = item
            var c = c
            if status == "image_only" { c = min(c, 0.5) }
            item["method"] = .str(method)
            item["confidence"] = .number(c)
            item["source_page"] = .int(sp - 1)
            item["from_image"] = .bool(images.contains(sp))
            items.append(.obj(item))
        }
        if let rt = data["record_type"]?.string, recordTypes.contains(rt) {
            res["record_type"] = .obj(["record_type": .str(rt), "confidence": .number(conf(data["record_type_confidence"] ?? .null)), "method": .str(method)])
        }
        if let rn = data["report_name"]?.string, !normText(rn).isEmpty {
            for (pi, p) in pages.enumerated() where contains(rn, p) {
                res["report_name"] = .obj(["key": .str("report_name"), "value_text": .str(wsCollapse(rn)), "value_json": .null, "method": .str(method),
                                           "confidence": .number(0.7), "source_page": .int(pi), "evidence": .null, "from_image": .bool(images.contains(pi + 1))])
                break
            }
        }
        for (i, d) in (data["dates"]?.array ?? []).enumerated() {
            guard d.object != nil else { drop("dates", i, "bad_item"); continue }
            guard let key = d["key"].string, dateKeys.contains(key) else { drop("dates", i, "unknown_key"); continue }
            guard let sp = pageOf("dates", i, d), let st = evidenceOK("dates", i, d, sp) else { continue }
            let v = d["value"].string
            let m = v.flatMap { reISODate.fullmatch($0) }
            let dv = m.flatMap { YMD.valid(Int($0.g(1)!)!, Int($0.g(2)!)!, Int($0.g(3) ?? "1")!) }
            guard let dv, plausible(dv, key, todayD) else { drop("dates", i, "bad_date"); continue }
            let precision = m?.g(3) != nil ? "day" : "month"
            let found = dateCandidates(fold(d["evidence"].string ?? ""), todayD, dateOrder, bothOrders: true)
                .contains { $0.dates.contains(dv) && $0.precision == precision }
            guard found else { drop("dates", i, "date_not_in_evidence"); continue }
            finish(["key": .str(key), "value_text": .str(dv.isoString), "value_json": .obj(["precision": .str(precision)]),
                    "evidence": .str(wsCollapse(d["evidence"].string ?? ""))], sp, st, conf(d["confidence"]))
        }
        for (i, fd) in (data["fields"]?.array ?? []).enumerated() {
            guard fd.object != nil else { drop("fields", i, "bad_item"); continue }
            guard let key = fd["key"].string, aiFieldKeys.contains(key) else { drop("fields", i, "unknown_key"); continue }
            guard let sp = pageOf("fields", i, fd), let st = evidenceOK("fields", i, fd, sp) else { continue }
            guard let v = fd["value"].string, !normText(v).isEmpty else { drop("fields", i, "empty_value"); continue }
            let ev = fd["evidence"].string ?? ""
            guard numsIn(v, ev) else { drop("fields", i, "number_not_in_evidence"); continue }
            if key != "patient_sex", !contains(v, ev) { drop("fields", i, "value_not_in_evidence"); continue }
            finish(["key": .str(key), "value_text": .str(wsCollapse(v)), "value_json": .null, "evidence": .str(wsCollapse(ev))], sp, st, conf(fd["confidence"]))
        }
        for (i, tr) in (data["test_results"]?.array ?? []).enumerated() {
            guard tr.object != nil else { drop("test_results", i, "bad_item"); continue }
            guard let sp = pageOf("test_results", i, tr), let st = evidenceOK("test_results", i, tr, sp) else { continue }
            let ev = tr["evidence"].string ?? ""
            guard let name = tr["name"].string, !normText(name).isEmpty, let value = tr["value"].string, !value.rStripWS.isEmpty else {
                drop("test_results", i, "empty_value")
                continue
            }
            guard contains(name, ev) else { drop("test_results", i, "value_not_in_evidence"); continue }
            if numbers(value).isEmpty, !contains(value, ev) { drop("test_results", i, "value_not_in_evidence"); continue }
            guard numsIn(value, ev) else { drop("test_results", i, "number_not_in_evidence"); continue }
            let vm = reValueNum.fullmatch(fold(value).rStripWS)
            var refText: String? = (tr["ref_text"].string.map { $0.rStripWS.isEmpty ? nil : $0 }) ?? nil
            if let r = refText, !numsIn(r, ev) { refText = nil }
            var tmp = Tail()
            if let refText {
                let rf = fold(refText).rStrip(" ()[]")
                for (rx, kind) in [(reRefRange, "range"), (reRefHigh, "high"), (reRefLow, "low")] {
                    if let fm = rx.fullmatch(rf) {
                        setRef(&tmp, fm, kind)
                        break
                    }
                }
            }
            var unit: String?
            if let u = tr["unit"].string, !u.rStripWS.isEmpty {
                let fu = fold(u).rStripWS
                if let match = matchUnit(fu, 0), match.1 == fu.rLen {
                    unit = match.0
                } else if !normText(u).isEmpty, fold(ev).contains(fu) {
                    unit = wsCollapse(u)
                }
            }
            let tail = fold(ev)
            let vf = fold(value).rStripWS
            let foldedName = fold(name).rStripWS
            let kn = tail.rFind(wsCollapse(fold(name)))
            var k = tail.rFind(vf, kn >= 0 ? kn + foldedName.rLen : 0)
            let tu = Array(tail.utf16)
            let digitsDot = Set("0123456789.".utf16)
            while k >= 0, (k > 0 && digitsDot.contains(tu[k - 1])) || (k + vf.rLen < tu.count && digitsDot.contains(tu[k + vf.rLen])) {
                k = tail.rFind(vf, k + 1)
            }
            var flagRaw: String?
            if k >= 0 {
                let after = tail.rSub(k + vf.rLen).rLStrip(" ")
                if let fm = reFlag.match(after) ?? reFlagAttached.match(tail.rSub(k + vf.rLen)) { flagRaw = fm.g(1) }
            }
            tmp.flagRaw = flagRaw
            tmp.valueNum = vm.map { numValue($0.g(2)!) }
            tmp.comparator = vm?.g(1)
            tmp.qualitative = vm == nil
            tmp.value = value
            tmp.refText = refText
            let flag = resolveFlag(tmp)
            let vj: RJ = .obj(["name": .str(wsCollapse(name)), "value": .str(value.rStripWS), "value_num": .number(tmp.valueNum), "unit": .string(unit),
                               "ref_text": .string(refText), "ref_low": .number(tmp.refLow), "ref_high": .number(tmp.refHigh), "flag": .str(flag)])
            finish(["key": .str("test_result"), "value_text": .str(wsCollapse(name)), "value_json": vj, "evidence": .str(wsCollapse(ev))], sp, st, conf(tr["confidence"]))
        }
        for (i, md) in (data["medications"]?.array ?? []).enumerated() {
            guard md.object != nil else { drop("medications", i, "bad_item"); continue }
            guard let sp = pageOf("medications", i, md), let st = evidenceOK("medications", i, md, sp) else { continue }
            let ev = md["evidence"].string ?? ""
            guard let name = md["name"].string, !normText(name).isEmpty else { drop("medications", i, "empty_value"); continue }
            guard contains(name, ev), numsIn(name, ev) else { drop("medications", i, "value_not_in_evidence"); continue }
            var vj: [String: RJ] = ["name": .str(wsCollapse(name))]
            for k in ["strength", "form", "dose", "frequency", "duration", "instructions"] {
                if let v = md[k].string, !v.rStripWS.isEmpty, numsIn(v, ev), k == "form" || contains(v, ev) {
                    vj[k] = .str(wsCollapse(v))
                } else {
                    vj[k] = .null
                }
            }
            finish(["key": .str("medication"), "value_text": vj["name"]!, "value_json": .obj(vj), "evidence": .str(wsCollapse(ev))], sp, st, conf(md["confidence"]))
        }
        let sm = data["summary"] ?? .null
        if sm.object != nil, let text0 = sm["text"].string, !text0.rStripWS.isEmpty {
            let all = Set(pages.flatMap { numbers($0) })
            if numbers(text0).allSatisfy(all.contains) {
                let sps: [RJ] = (sm["source_pages"].array ?? []).compactMap { p in
                    if case .int(let v) = p, v >= 1, v <= pages.count { return .int(v - 1) }
                    return nil
                }
                var text = wsCollapse(text0)
                if text.unicodeScalars.count > 400 { text = String(String.UnicodeScalarView(text.unicodeScalars.prefix(399))) + "…" }
                res["summary"] = .obj(["text": .str(text), "source_pages": .arr(sps)])
            } else {
                drop("summary", 0, "number_not_in_pages")
            }
        }
        res["items"] = .arr(items)
        res["dropped"] = .arr(dropped)
        return .obj(res)
    }

    // MARK: §15 Hashing

    static let fnvOffset: UInt64 = 0xcbf2_9ce4_8422_2325
    static let fnvPrime: UInt64 = 0x0000_0100_0000_01b3

    static func fnv1a64<S: Sequence>(_ data: S, _ h0: UInt64 = fnvOffset) -> UInt64 where S.Element == UInt8 {
        var h = h0
        for b in data {
            h ^= UInt64(b)
            h = h &* fnvPrime
        }
        return h
    }

    static func le64(_ x: UInt64) -> [UInt8] { (0..<8).map { UInt8((x >> (8 * UInt64($0))) & 0xFF) } }

    static func hex16(_ v: UInt64) -> String {
        let raw = String(v, radix: 16)
        return String(repeating: "0", count: max(0, 16 - raw.count)) + raw
    }

    static func dhashHex(_ gray: [[Double]]) -> String? {
        guard gray.count == 8, gray.allSatisfy({ $0.count == 9 }) else { return nil }
        var v: UInt64 = 0
        for r in 0..<8 {
            for c in 0..<8 { v = (v << 1) | (gray[r][c] > gray[r][c + 1] ? 1 : 0) }
        }
        return hex16(v)
    }

    static func hammingHex(_ a: String, _ b: String) -> Int? {
        guard let x = UInt64(a, radix: 16), let y = UInt64(b, radix: 16) else { return nil }
        return (x ^ y).nonzeroBitCount
    }

    static func scalarLess(_ a: String, _ b: String) -> Bool {
        a.unicodeScalars.map(\.value).lexicographicallyPrecedes(b.unicodeScalars.map(\.value))
    }

    static func shingles(_ text: String?) -> [String] {
        let ws = words(fold(text ?? ""))
        if ws.isEmpty { return [] }
        if ws.count < 5 { return [ws.joined(separator: " ")] }
        return Set((0...(ws.count - 5)).map { ws[$0..<($0 + 5)].joined(separator: " ") }).sorted(by: scalarLess)
    }

    static let minhashSeeds: [UInt64] = (1...64).map { fnv1a64(le64(UInt64($0))) }

    static func minhashSignature(_ text: String?) -> String {
        let sh = shingles(text)
        if sh.isEmpty { return "" }
        var mins = [UInt64](repeating: .max, count: 64)
        for x in sh {
            let base = le64(fnv1a64(Array(x.utf8)))
            for k in 0..<64 {
                let v = fnv1a64(base, minhashSeeds[k])
                if v < mins[k] { mins[k] = v }
            }
        }
        return mins.map(hex16).joined(separator: ",")
    }

    static func signatureSimilarity(_ a: String?, _ b: String?) -> Double {
        guard let a, let b, !a.isEmpty, !b.isEmpty else { return 0 }
        let xa = a.split(separator: ","), xb = b.split(separator: ",")
        guard xa.count == 64, xb.count == 64 else { return 0 }
        return Double((0..<64).filter { xa[$0] == xb[$0] }.count) / 64
    }

    static func nearDuplicate(phashA: String?, phashB: String?, sigA: String?, sigB: String?) -> RJ {
        let sim = signatureSimilarity(sigA, sigB)
        if let phashA, let phashB, !phashA.isEmpty, !phashB.isEmpty, let hd = hammingHex(phashA, phashB) {
            if hd <= 6, sim >= 0.9 || ((sigA ?? "").isEmpty && (sigB ?? "").isEmpty) {
                let score = (sigA ?? "").isEmpty ? 1 - Double(hd) / 64 : sim
                return .obj(["candidate": .bool(true), "reason": .str("phash"), "score": .number(round2(score))])
            }
        }
        if sim >= 0.95 { return .obj(["candidate": .bool(true), "reason": .str("content"), "score": .number(round2(sim))]) }
        return .obj(["candidate": .bool(false), "reason": .null, "score": .number(round2(sim))])
    }

    // MARK: §17 Query parser

    static let qTypes: [([String], [String])] = [
        (["blood", "tests"], ["lab_report"]), (["blood", "test"], ["lab_report"]),
        (["blood", "reports"], ["lab_report"]), (["blood", "report"], ["lab_report"]),
        (["test", "reports"], ["lab_report"]), (["test", "report"], ["lab_report"]),
        (["lab", "reports"], ["lab_report"]), (["lab", "report"], ["lab_report"]),
        (["doctor", "notes"], ["consultation_note"]), (["doctor", "note"], ["consultation_note"]),
        (["discharge", "summary"], ["discharge_summary"]), (["discharge", "summaries"], ["discharge_summary"]),
        (["x", "ray"], ["imaging_report"]), (["x", "rays"], ["imaging_report"]),
        (["ct", "scan"], ["imaging_report"]), (["ct", "scans"], ["imaging_report"]),
        (["mri", "scan"], ["imaging_report"]),
        (["reports"], ["lab_report", "diagnostic_report", "imaging_report"]),
        (["report"], ["lab_report", "diagnostic_report", "imaging_report"]),
        (["lab"], ["lab_report"]), (["labs"], ["lab_report"]),
        (["prescription"], ["prescription"]), (["prescriptions"], ["prescription"]), (["rx"], ["prescription"]),
        (["medicine"], ["prescription"]), (["medicines"], ["prescription"]),
        (["scan"], ["imaging_report"]), (["scans"], ["imaging_report"]), (["imaging"], ["imaging_report"]),
        (["xray"], ["imaging_report"]), (["xrays"], ["imaging_report"]), (["mri"], ["imaging_report"]),
        (["ct"], ["imaging_report"]), (["ultrasound"], ["imaging_report"]), (["usg"], ["imaging_report"]),
        (["discharge"], ["discharge_summary"]),
        (["consultation"], ["consultation_note"]), (["consultations"], ["consultation_note"]),
        (["visit"], ["consultation_note"]), (["visits"], ["consultation_note"]),
        (["bill"], ["bill"]), (["bills"], ["bill"]), (["invoice"], ["bill"]), (["invoices"], ["bill"]),
        (["receipt"], ["bill"]), (["receipts"], ["bill"]),
        (["insurance"], ["insurance"]), (["claim"], ["insurance"]), (["claims"], ["insurance"]),
        (["vaccine"], ["vaccination_record"]), (["vaccines"], ["vaccination_record"]),
        (["vaccination"], ["vaccination_record"]), (["vaccinations"], ["vaccination_record"]),
        (["note"], ["personal_note"]), (["notes"], ["personal_note"]),
    ]
    static let qTypeAndTerm: Set<String> = ["mri", "ct", "xray", "ultrasound", "usg"]
    static let qFlags: [([String], String)] = [(["out", "of", "range"], "abnormal"), (["abnormal"], "abnormal"), (["abnormalities"], "abnormal"),
                                               (["abnormality"], "abnormal"), (["low"], "low"), (["high"], "high"), (["elevated"], "high"),
                                               (["raised"], "high"), (["critical"], "critical")]
    static let qStates: [([String], String)] = [(["shared", "with", "me"], "source"), (["needs", "review"], "needs_review"),
                                                (["to", "review"], "needs_review"), (["favorites"], "favorites"), (["favorite"], "favorites"),
                                                (["favourites"], "favorites"), (["favourite"], "favorites"), (["starred"], "favorites"),
                                                (["received"], "source"), (["archived"], "archived")]
    static let qStop: Set<String> = ["a", "an", "the", "my", "of", "with", "where", "was", "were", "is", "are", "from", "for", "in",
                                     "on", "show", "find", "all", "me", "and", "or", "to", "by", "at", "any", "which", "that",
                                     "had", "has", "have", "please", "get", "list", "records", "record", "documents", "document",
                                     "files", "file", "last", "latest", "recent", "this", "since", "before", "after", "between",
                                     "doctor", "dr", "hospital"]
    static let qOrderFlags = ["abnormal", "low", "high", "critical"]
    static let qMonthFull: Set<String> = ["january", "february", "march", "april", "june", "july", "august", "september", "october", "november", "december"]
    static let qPeriods: [String: String] = ["day": "day", "days": "day", "week": "week", "weeks": "week", "month": "month", "months": "month", "year": "year", "years": "year"]

    struct QTok {
        /// `w`, `date` or `op`.
        var kind: String
        var word: String
        var date: YMD?
        var text: String
        var start: Int
        var end: Int
        var isDate: Bool { kind == "date" }
        var isWord: Bool { kind == "w" }
    }

    static func qText(_ text: String) -> String { fold(text).components(separatedBy: "\n").joined(separator: " ") }

    /// Reference `_q_plain_tokens`: alphanumeric runs (a digit run + `.` + digits is one token), comparison ops.
    static func qPlainTokens(_ f: String, _ start: Int, _ end: Int, _ toks: inout [QTok]) {
        let u = Array(f.utf16)
        func alnum(_ i: Int) -> Bool {
            guard let scalar = Unicode.Scalar(u[i]) else { return false }
            return isAlnum(scalar)
        }
        func digit(_ i: Int) -> Bool { u[i] >= 48 && u[i] <= 57 }
        var i = start
        while i < end {
            if alnum(i) {
                var j = i
                while j < end, alnum(j) { j += 1 }
                if (i..<j).allSatisfy(digit), j + 1 < end, u[j] == 46, digit(j + 1) {
                    var k = j + 1
                    while k < end, digit(k) { k += 1 }
                    if k == end || !alnum(k) { j = k }
                }
                let w = f.rSub(i, j)
                toks.append(QTok(kind: "w", word: w, date: nil, text: w, start: i, end: j))
                i = j
            } else if u[i] == 60 || u[i] == 62 || u[i] == 0x2264 || u[i] == 0x2265 {
                if (u[i] == 60 || u[i] == 62), i + 1 < end, u[i + 1] == 61 {
                    toks.append(QTok(kind: "op", word: f.rSub(i, i + 2), date: nil, text: f.rSub(i, i + 2), start: i, end: i + 2))
                    i += 2
                } else {
                    let op = u[i] == 0x2264 ? "<=" : (u[i] == 0x2265 ? ">=" : f.rSub(i, i + 1))
                    toks.append(QTok(kind: "op", word: op, date: nil, text: f.rSub(i, i + 1), start: i, end: i + 1))
                    i += 1
                }
            } else {
                i += 1
            }
        }
    }

    static func qTokens(_ text: String, _ today: YMD, _ dateOrder: String) -> [QTok] {
        let f = qText(text)
        var toks: [QTok] = []
        var pos = 0
        for c in dateCandidates(f, today, dateOrder) where c.precision == "day" {
            qPlainTokens(f, pos, c.start, &toks)
            toks.append(QTok(kind: "date", word: "", date: c.dates[0], text: f.rSub(c.start, c.end), start: c.start, end: c.end))
            pos = c.end
        }
        qPlainTokens(f, pos, f.rLen, &toks)
        return toks
    }

    static func periodRange(_ unit: String, _ start: YMD) -> (YMD, YMD) {
        switch unit {
        case "day": return (start, start)
        case "week":
            let s = start.days(-start.weekday)
            return (s, s.days(6))
        case "month":
            let s = YMD(y: start.y, m: start.m, d: 1)
            return (s, s.months(1).days(-1))
        default:
            return (YMD(y: start.y, m: 1, d: 1), YMD(y: start.y, m: 12, d: 31))
        }
    }

    static let re1to3Digits = Rx("[0-9]{1,3}")
    static let re4Digits = Rx("[0-9]{4}")

    static func qDatespec(_ toks: [QTok], _ i: Int, _ today: YMD, _ allowBareAbbrev: Bool) -> (YMD, YMD, Int)? {
        guard i < toks.count else { return nil }
        let t = toks[i]
        if t.isDate, let d = t.date { return (d, d, 1) }
        let v = t.word
        if v == "today" { return (today, today, 1) }
        if v == "yesterday" { let d = today.days(-1); return (d, d, 1) }
        let nxt = i + 1 < toks.count && toks[i + 1].isWord ? toks[i + 1].word : nil
        let nxt2 = i + 2 < toks.count && toks[i + 2].isWord ? toks[i + 2].word : nil
        if ["this", "current"].contains(v), let nxt, ["week", "month", "year"].contains(nxt) {
            let r = periodRange(nxt, today)
            return (r.0, r.1, 2)
        }
        if ["last", "past", "previous"].contains(v), let nxt, ["week", "month", "year"].contains(nxt) {
            let ref: YMD
            switch nxt {
            case "week": ref = today.days(-7)
            case "month": ref = YMD(y: today.y, m: today.m, d: 1).months(-1)
            default: ref = YMD(y: today.y - 1, m: 1, d: 1)
            }
            let r = periodRange(nxt, ref)
            return (r.0, r.1, 2)
        }
        if ["last", "past", "previous"].contains(v), let nxt, re1to3Digits.fullmatch(nxt) != nil, let nxt2, let unit = qPeriods[nxt2] {
            let n = Int(nxt)!
            let s: YMD
            switch unit {
            case "day": s = today.days(-n)
            case "week": s = today.days(-7 * n)
            case "month": s = today.months(-n)
            default: s = today.years(-n)
            }
            return (s, today, 3)
        }
        if let mo = monthNum[v] {
            if let nxt, re4Digits.fullmatch(nxt) != nil, let y = Int(nxt), (1900...2100).contains(y) {
                let s = YMD(y: y, m: mo, d: 1)
                return (s, s.months(1).days(-1), 2)
            }
            if qMonthFull.contains(v) || allowBareAbbrev {
                let y = mo <= today.m ? today.y : today.y - 1
                let s = YMD(y: y, m: mo, d: 1)
                return (s, s.months(1).days(-1), 1)
            }
            return nil
        }
        if re4Digits.fullmatch(v) != nil, let y = Int(v), (1900...2100).contains(y) {
            return (YMD(y: y, m: 1, d: 1), YMD(y: y, m: 12, d: 31), 1)
        }
        return nil
    }

    static func matchPhrase<V>(_ toks: [QTok], _ i: Int, _ table: [([String], V)]) -> ([String], V)? {
        for (phrase, value) in table {
            let n = phrase.count
            if i + n <= toks.count, (0..<n).allSatisfy({ toks[i + $0].isWord && toks[i + $0].word == phrase[$0] }) {
                return (phrase, value)
            }
        }
        return nil
    }

    static let qReserved: Set<String> = {
        var s = qStop
        for (p, _) in qTypes { s.formUnion(p) }
        for (p, _) in qFlags { s.formUnion(p) }
        for (p, _) in qStates { s.formUnion(p) }
        return s
    }()

    // MARK: §23 Query analyte conditions

    static let qCondFlags: [([String], String)] = qFlags + [(["normal"], "normal")]
    static let qConnectors: Set<String> = ["was", "is", "were", "are"]
    static let qCmpWords: [([String], String)] = [(["greater", "than"], ">"), (["more", "than"], ">"), (["less", "than"], "<"), (["at", "least"], ">="),
                                                  (["at", "most"], "<="), (["above"], ">"), (["over"], ">"), (["below"], "<"), (["under"], "<")]
    static let reQNumber = Rx("[0-9]+(?:\\.[0-9]+)?")

    struct QAliasTable {
        var table: [[String]: [String]]
        var longest: Int
    }

    static func qAliasTable(_ cat: AnalyteCatalog) -> QAliasTable {
        var reserved = qStop.union(kMethodWords)
        for (p, _) in qTypes { reserved.formUnion(p) }
        for (p, _) in qFlags { reserved.formUnion(p) }
        for (p, _) in qStates { reserved.formUnion(p) }
        var table: [[String]: [String]] = [:]
        for e in cat.entries {
            guard let id = e["id"].string else { continue }
            for a in (e["aliases"].array ?? []).compactMap(\.string) {
                for ws in [words(fold(a)), analyteWords(a)] {
                    if ws.joined(separator: " ").rLen <= 1 || ws.allSatisfy(reserved.contains) { continue }
                    if !(table[ws] ?? []).contains(id) { table[ws, default: []].append(id) }
                }
            }
        }
        return QAliasTable(table: table, longest: table.keys.map(\.count).max() ?? 0)
    }

    static let sharedQAliasTable = qAliasTable(.shared)

    static func qMatchAlias(_ toks: [QTok], _ i: Int, _ t: QAliasTable) -> (Int, [String])? {
        var n = min(t.longest, toks.count - i)
        while n > 0 {
            if (0..<n).allSatisfy({ toks[i + $0].isWord }), let ids = t.table[(0..<n).map { toks[i + $0].word }], !ids.isEmpty {
                return (n, ids)
            }
            n -= 1
        }
        return nil
    }

    static func qResolve(_ ids: [String], _ unit: String?, _ cat: AnalyteCatalog) -> String? {
        var c = ids
        let keeps: [(String) -> Bool] = [
            { unit != nil && analyteUnits(cat.entry($0)).contains(unit!) },
            { cat.entry($0)["category"].string != "urine" },
            { cat.entry($0)["kind"].string == "numeric" },
        ]
        for keep in keeps where c.count > 1 {
            let k = c.filter(keep)
            if !k.isEmpty { c = k }
        }
        return c.count == 1 ? c[0] : nil
    }

    struct QCond {
        var flag: String?
        var op: String?
        var value: Double?
        var unit: String?
        var end: Int
        var endPos: Int
    }

    static func qCondition(_ toks: [QTok], _ j: Int, _ f: String) -> QCond? {
        var k = j
        if k < toks.count, toks[k].isWord, qConnectors.contains(toks[k].word) { k += 1 }
        if let fl = matchPhrase(toks, k, qCondFlags) {
            let end = k + fl.0.count
            return QCond(flag: fl.1, end: end, endPos: toks[end - 1].end)
        }
        var op: String?
        var k2 = 0
        if k < toks.count, toks[k].kind == "op" {
            op = toks[k].word
            k2 = k + 1
        } else if let cm = matchPhrase(toks, k, qCmpWords) {
            op = cm.1
            k2 = k + cm.0.count
        }
        guard let op, k2 < toks.count, toks[k2].isWord, reQNumber.fullmatch(toks[k2].word) != nil else { return nil }
        var end = k2 + 1
        var endPos = toks[k2].end
        var pos = endPos
        let u = Array(f.utf16)
        while pos < u.count, u[pos] == 32 { pos += 1 }
        var unit: String?
        if pos < u.count, let um = matchUnit(f, pos) {
            unit = um.0
            endPos = um.1
            while end < toks.count, toks[end].start < um.1 { end += 1 }
        }
        return QCond(op: op, value: Double(toks[k2].word), unit: unit, end: end, endPos: endPos)
    }

    static func qConditionOut(_ aid: String, _ cond: QCond, _ cat: AnalyteCatalog) -> RJ {
        if let flag = cond.flag {
            return .obj(["analyte_id": .str(aid), "flag": .str(flag), "op": .null, "value": .null, "unit": .null, "canonical_value": .null, "canonical_unit": .null])
        }
        var cv: RJ
        var cu: RJ
        if cond.unit == nil {
            cv = .number(round4(cond.value ?? 0))
            cu = cat.entry(aid)["canonical_unit"]
        } else {
            let conv = convertUnit(aid, cond.value, cond.unit, cat)
            cv = conv["canonical_value"]
            cu = conv["canonical_unit"]
        }
        return .obj(["analyte_id": .str(aid), "flag": .null, "op": .string(cond.op), "value": .number(cond.value), "unit": .string(cond.unit),
                     "canonical_value": cv, "canonical_unit": cu])
    }

    static func parseQuery(_ text: String, today: String, dateOrder: String, catalog cat: AnalyteCatalog = .shared) -> RJ {
        let todayD = YMD.iso(today) ?? YMD(y: 2026, m: 1, d: 1)
        let toks = qTokens(text, todayD, dateOrder)
        let f = qText(text)
        let aliasTable = cat === AnalyteCatalog.shared ? sharedQAliasTable : qAliasTable(cat)
        var terms: [String] = []
        var favorites = false, needsReview = false, archived = false
        var source: String?
        var doctor: String?, facility: String?
        var chips: [RJ] = []
        var conditions: [RJ] = []
        var analytes: [String] = []
        var types = Set<String>(), flags = Set<String>()
        var ranges: [(YMD?, YMD?)] = []
        var i = 0
        func chip(_ kind: String, _ a: Int, _ b: Int) {
            chips.append(.obj(["kind": .str(kind), "text": .str(toks[a..<min(b, toks.count)].map(\.text).joined(separator: " "))]))
        }
        while i < toks.count {
            let t = toks[i]
            let v = t.word
            if t.isWord, v == "from" || v == "between", let a = qDatespec(toks, i + 1, todayD, true) {
                let j = i + 1 + a.2
                if j < toks.count, !toks[j].isDate, ["to", "and", "till", "until"].contains(toks[j].word), let b = qDatespec(toks, j + 1, todayD, true) {
                    ranges.append((a.0, b.1))
                    chip("date", i, j + 1 + b.2)
                    i = j + 1 + b.2
                    continue
                }
                ranges.append((a.0, a.1))
                chip("date", i, j)
                i = j
                continue
            }
            if t.isWord, ["since", "after", "before", "in", "during"].contains(v), let a = qDatespec(toks, i + 1, todayD, true) {
                switch v {
                case "since": ranges.append((a.0, nil))
                case "after": ranges.append((a.1.days(1), nil))
                case "before": ranges.append((nil, a.0.days(-1)))
                default: ranges.append((a.0, a.1))
                }
                chip("date", i, i + 1 + a.2)
                i += 1 + a.2
                continue
            }
            if let a = qDatespec(toks, i, todayD, false) {
                ranges.append((a.0, a.1))
                chip("date", i, i + a.2)
                i += a.2
                continue
            }
            if !t.isWord {
                i += 1
                continue
            }
            if let (phrase, what) = matchPhrase(toks, i, qStates) {
                switch what {
                case "favorites": favorites = true
                case "needs_review": needsReview = true
                case "archived": archived = true
                default: source = "received"
                }
                chip(what, i, i + phrase.count)
                i += phrase.count
                continue
            }
            if let (phrase, vals) = matchPhrase(toks, i, qTypes) {
                types.formUnion(vals)
                chip("type", i, i + phrase.count)
                if phrase.count == 1, qTypeAndTerm.contains(phrase[0]), !terms.contains(phrase[0]) { terms.append(phrase[0]) }
                i += phrase.count
                continue
            }
            if let (n, ids) = qMatchAlias(toks, i, aliasTable) {
                let cond = qCondition(toks, i + n, f)
                let aid = qResolve(ids, cond?.unit, cat)
                if let aid, let cond {
                    conditions.append(qConditionOut(aid, cond, cat))
                    chips.append(.obj(["kind": .str("analyte"), "text": .str(f.rSub(toks[i].start, cond.endPos))]))
                    i = cond.end
                    continue
                }
                if let aid {
                    if !analytes.contains(aid) { analytes.append(aid) }
                    for tk in toks[i..<(i + n)] where !qStop.contains(tk.word) && !terms.contains(tk.word) { terms.append(tk.word) }
                    i += n
                    continue
                }
            }
            if let cf = matchPhrase(toks, i, qCondFlags) {
                if let al = qMatchAlias(toks, i + cf.0.count, aliasTable), let aid = qResolve(al.1, nil, cat) {
                    let end = i + cf.0.count + al.0
                    conditions.append(qConditionOut(aid, QCond(flag: cf.1, end: end, endPos: toks[end - 1].end), cat))
                    chips.append(.obj(["kind": .str("analyte"), "text": .str(f.rSub(toks[i].start, toks[end - 1].end))]))
                    i = end
                    continue
                }
            }
            if let (phrase, val) = matchPhrase(toks, i, qFlags) {
                flags.insert(val)
                chip("flag", i, i + phrase.count)
                i += phrase.count
                continue
            }
            if ["doctor", "dr", "at", "hospital"].contains(v) {
                var j = i + 1
                var name: [String] = []
                while j < toks.count, toks[j].isWord, name.count < 3, !qReserved.contains(toks[j].word), qDatespec(toks, j, todayD, false) == nil {
                    name.append(toks[j].word)
                    j += 1
                }
                if !name.isEmpty {
                    if v == "doctor" || v == "dr" {
                        doctor = name.joined(separator: " ")
                        chip("doctor", i, j)
                    } else {
                        facility = name.joined(separator: " ")
                        chip("facility", i, j)
                    }
                    i = j
                    continue
                }
            }
            for w in words(v) where !qStop.contains(w) && !terms.contains(w) { terms.append(w) }
            i += 1
        }
        var dateFrom: RJ = .null, dateTo: RJ = .null
        if !ranges.isEmpty {
            let lo = ranges.compactMap(\.0)
            let hi = ranges.compactMap(\.1)
            if let m = lo.max() { dateFrom = .str(m.isoString) }
            if let m = hi.min() { dateTo = .str(m.isoString) }
        }
        return .obj([
            "terms": .arr(terms.map(RJ.str)), "date_from": dateFrom, "date_to": dateTo,
            "record_types": .arr(recordTypes.filter(types.contains).map(RJ.str)),
            "flags": .arr(qOrderFlags.filter(flags.contains).map(RJ.str)),
            "doctor": .string(doctor), "facility": .string(facility), "favorites": .bool(favorites), "needs_review": .bool(needsReview),
            "archived": .bool(archived), "source": .string(source), "analyte_conditions": .arr(conditions),
            "analytes": .arr(analytes.map(RJ.str)), "chips": .arr(chips),
            "match": terms.isEmpty ? .null : .str(terms.map { $0 + "*" }.joined(separator: " ")),
        ])
    }

    // MARK: Vector dispatch

    static func runCase(function: String, input inp: RJ) -> RJ {
        let pages = (inp["pages"].array ?? []).map { $0.string ?? "" }
        switch function {
        case "fold":
            let text = inp["text"].string ?? ""
            return .obj(["fold": .str(fold(text)), "pfold": .str(pfold(text)), "normalized": .str(normText(text)), "words": .arr(words(fold(text)).map(RJ.str))])
        case "classify":
            return classify(pages)
        case "extract_dates":
            return .obj(["items": .arr(extractDates(pages, inp["record_type"].string ?? "other", inp["today"].string ?? "", inp["date_order"].string ?? "dmy"))])
        case "extract_fields":
            return .obj(["items": .arr(extractFields(pages, inp["record_type"].string ?? "other"))])
        case "parse_lab_rows":
            return .obj(["items": .arr(parseLabRows(pages, inp["record_type"].string ?? "other"))])
        case "detect_boundaries":
            return detectBoundaries(pages, inp["today"].string ?? "", inp["date_order"].string ?? "dmy")
        case "build_highlights":
            if inp["op"].string == "important" {
                let since = inp["today"].string.flatMap { RecordDates.highlightsSince(today: $0) }
                return .obj(["since": since.map(RJ.str) ?? .null,
                             "highlights": .arr(importantHighlights(inp["records"].array ?? [], inp["highlights"].array ?? [],
                                                                    limit: inp["limit"].double.map { Int($0) } ?? 8, since: since))])
            }
            return .obj(["highlights": .arr(buildHighlights(inp["fields"].array ?? [], summary: inp["summary"]))])
        case "review_status":
            return reviewStatus(record: inp["record"], rows: inp["rows"].array ?? [], pendingSplit: inp["pending_split"].truthy, pendingDuplicate: inp["pending_duplicate"].truthy)
        case "apply_extraction":
            return applyExtraction(existingRows: inp["existing_rows"].array ?? [], newItems: inp["new_items"].array ?? [], record: inp["record"], classification: inp["classification"])
        case "validate_ai":
            return validateAI(pages: pages, aiJSON: inp["ai_json"], mode: inp["mode"].string ?? "cloud", today: inp["today"].string ?? "",
                              dateOrder: inp["date_order"].string ?? "dmy", imagePages: (inp["image_pages"].array ?? []).compactMap { $0.double.map { Int($0) } })
        case "ai_chunks":
            return .obj(["chunks": .arr(aiChunks(pages, mode: inp["mode"].string ?? "cloud"))])
        case "hashing":
            switch inp["op"].string ?? "" {
            case "dhash":
                let gray = (inp["gray"].array ?? []).map { ($0.array ?? []).compactMap(\.double) }
                return .obj(["hex": .string(dhashHex(gray))])
            case "hamming":
                return .obj(["distance": .number(hammingHex(inp["a"].string ?? "", inp["b"].string ?? "").map(Double.init))])
            case "fnv1a64":
                return .obj(["hex": .str(hex16(fnv1a64(Array((inp["text"].string ?? "").utf8))))])
            case "shingles":
                return .obj(["shingles": .arr(shingles(inp["text"].string).map(RJ.str))])
            case "minhash":
                return .obj(["signature": .str(minhashSignature(inp["text"].string))])
            case "similarity":
                return .obj(["similarity": .number(signatureSimilarity(minhashSignature(inp["a"].string), minhashSignature(inp["b"].string)))])
            case "near_duplicate":
                let sa = inp["text_a"].string.map { minhashSignature($0) } ?? ""
                let sb = inp["text_b"].string.map { minhashSignature($0) } ?? ""
                return nearDuplicate(phashA: inp["phash_a"].string, phashB: inp["phash_b"].string, sigA: sa, sigB: sb)
            default:
                return .null
            }
        case "parse_query":
            return parseQuery(inp["text"].string ?? "", today: inp["today"].string ?? "", dateOrder: inp["date_order"].string ?? "dmy")
        case "map_analyte":
            func aliases(_ v: RJ) -> [String: String]? { v.object.map { $0.compactMapValues(\.string) } }
            switch inp["op"].string ?? "map" {
            case "map":
                return mapAnalyteRef(inp["name"].string, panels: inp["panels"].array?.compactMap(\.string), userAliases: aliases(inp["user_aliases"]),
                                     unit: inp["unit"].string, qualitative: inp["qualitative"].bool)
            case "keys":
                return .obj(["keys": .arr(testNameKeys(inp["name"].string).map(RJ.str)), "normalized_name": .str(normalizeTestNameRef(inp["name"].string))])
            case "detect_panels":
                return .obj(["panels": .arr(detectPanelsRef(texts: (inp["texts"].array ?? []).compactMap(\.string), testNames: (inp["test_names"].array ?? []).compactMap(\.string)).map(RJ.str))])
            default:
                return .null
            }
        case "convert_unit":
            return convertUnit(inp["analyte_id"].string, inp["value"].double, inp["unit"].string)
        case "observations":
            let ua = inp["user_aliases"].object.map { $0.compactMapValues(\.string) }
            switch inp["op"].string ?? "" {
            case "promote":
                return promoteObservations(fields: inp["fields"].array ?? [], existing: inp["existing_observations"].array ?? [], record: inp["record"],
                                           userAliases: ua, nowMs: inp["now_ms"].isNull ? .int(0) : inp["now_ms"])
            case "observed_date":
                return observedDateRef(record: inp["record"], fields: inp["fields"].array ?? [])
            case "edit":
                return editObservationRef(inp["observation"], inp["patch"])
            case "user_alias":
                return applyUserAliasRef(inp["observations"].array ?? [], rawName: inp["raw_name"].string, analyteID: inp["analyte_id"].string,
                                         nowMs: inp["now_ms"].isNull ? .int(0) : inp["now_ms"])
            default:
                return .null
            }
        case "trends":
            let trend = trendSeriesRef(inp["observations"].array ?? [], analyteID: inp["analyte_id"].string ?? "")
            switch inp["op"].string ?? "" {
            case "series": return trend
            case "mini": return miniTrendRef(trend, observationID: inp["observation_id"].string ?? "")
            default: return .null
            }
        case "entities":
            switch inp["op"].string ?? "rebuild" {
            case "rebuild":
                return rebuildEntities(inp["fields"].array ?? [])
            case "normalize":
                let name = inp["name"].string
                return .obj(["doctor_display": .str(doctorDisplayName(name)), "doctor": .str(normalizeDoctorName(name)),
                             "facility_display": .str(facilityDisplayName(name)), "facility": .str(normalizeFacilityName(name))])
            default:
                return .null
            }
        case "suggest_relations":
            return suggestRelations(record: inp["record"], candidates: inp["candidates"].array ?? [], today: inp["today"].string ?? "", existingLinks: inp["existing_links"].array ?? [])
        default:
            return .null
        }
    }
}
