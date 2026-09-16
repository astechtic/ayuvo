import Foundation

// Swift port of the Phase 5 §34 section of `scripts/records_reference.py` (sharing): the summary
// text, redaction target detection and the share-plan warnings. Everything works on the same plain
// store snapshot the Phase 4 functions use (`records`, `fields`, `observations`, `highlights`,
// `pages`). Vectors: `share_summary.json`, `redaction.json`.
extension RR {
    // MARK: - §34 constants

    static let shareDateLabels: [(key: String, label: String)] = [
        ("collection_date", "Collected"), ("report_date", "Reported"), ("visit_date", "Visit"),
        ("prescription_date", "Prescribed"), ("admission_date", "Admitted"),
        ("discharge_date", "Discharged"), ("follow_up_date", "Follow-up"),
    ]
    static let shareFooter = "Shared from Ayuvo. Values were read from the original document and may contain mistakes."
    static let shareHighlightsHeader = "AI highlights (verify against the original report):"
    static let shareNotesHeader = "Notes:"
    static let shareSeparator = "\n\n---\n\n"

    static let shareWarnings: [String: String] = [
        "unknown_record": "A selected record is no longer available and was left out",
        "no_original": "The original file of {title} is missing and was left out",
        "page_missing": "Page {page} of {title} doesn't exist and was left out",
        "page_not_redactable": "Page {page} can't be redacted and was left out",
        "record_not_redactable": "No page of {title} can be redacted, so it was left out",
        "text_layer_lost": "Selected pages are shared as images",
        "redacted_text_layer_lost": "Redacted pages are shared as images",
        "nothing_to_share": "Nothing is selected to share",
    ]

    static let redactionClasses = ["name", "address", "phone", "patient_id", "insurance_id", "other_ids"]
    static let redactionInflate = 0.02
    static let redactionAddressLines = 4
    static let phoneChars = Set("0123456789 -+()")
    static let phoneMinDigits = 7
    static let phoneMaxDigits = 15

    static let reDateRun = Rx("[0-9]{1,4}[-][0-9]{1,2}[-][0-9]{1,4}")
    static let reIDLabel = Rx("(?<![a-z0-9])(?:id|ids|no|number|ref|reference|barcode|code|serial|accession|sample id|lab no)(?![a-z0-9])")
    static let rePhoneLabel = Rx("(?<![a-z0-9])(?:phone|mobile|mob|tel|telephone|cell|contact|whatsapp|ph)(?![a-z0-9])")
    static let rePatientIDLabel = Rx("(?<![a-z0-9])(?:uhid|uhid no|mrn|mr no|mrno|patient id|patient no|hospital no|hosp no|reg no|regn no|registration no|ip no|op no)(?![a-z0-9])")
    static let reInsuranceIDLabel = Rx("(?<![a-z0-9])(?:policy no|policy number|member id|member no|claim no|claim id|tpa id|tpa no|insurance id)(?![a-z0-9])")
    static let reAddressLabel = Rx("(?<![a-z0-9])(?:address|addr|residence|residential)(?![a-z0-9])")

    // MARK: - §34 summary text

    /// `_plan_records`: the plan's records in plan order, unknown ids dropped, repeats collapsed.
    static func planRecords(_ snapshot: RJ, _ plan: RJ) -> [RJ] {
        let map = recordMap(snapshot)
        var out: [RJ] = []
        var seen = Set<String>()
        for id in (plan["record_ids"].array ?? []).compactMap(\.string) {
            guard let record = map[id], seen.insert(id).inserted else { continue }
            out.append(record)
        }
        return out
    }

    /// `_share_result_item`: `<name>: <value> <unit> (<flag word>, ref <range>)`.
    static func shareResultItem(_ tr: RJ) -> String {
        let name = collapseWS(tr["name"].string) ?? ""
        let rest = ["value", "unit"].compactMap { key -> String? in
            guard let raw = tr[key].string, let value = collapseWS(raw), !value.isEmpty else { return nil }
            return value
        }
        var text = name + (rest.isEmpty ? "" : ": " + rest.joined(separator: " "))
        var inner: [String] = []
        if let word = coachFlagWord[tr["flag"].string ?? ""] { inner.append(word) }
        if let ref = tr["ref_text"].isNull ? nil : stripRefBrackets(tr["ref_text"].string), !ref.isEmpty {
            inner.append("ref " + ref)
        }
        if !inner.isEmpty { text += " (" + inner.joined(separator: ", ") + ")" }
        return text
    }

    /// `_share_medication_item`: `<name> <strength>, <dose>, <frequency>, <duration>, <instructions>`.
    static func shareMedicationItem(_ row: RJ) -> String {
        let vj = row["value_json"]
        var text = collapseWS(vj["name"].truthy ? vj["name"].string : row["value_text"].string) ?? ""
        if vj["strength"].truthy, let strength = collapseWS(vj["strength"].string), !strength.isEmpty {
            text += " " + strength
        }
        for key in ["dose", "frequency", "duration", "instructions"] where vj[key].truthy {
            if let part = collapseWS(vj[key].string), !part.isEmpty { text += ", " + part }
        }
        return text
    }

    /// `_share_notes_lines`: CRLF/CR → LF, each line collapsed, blank lines dropped.
    static func shareNotesLines(_ notes: String?) -> [String] {
        guard let notes, !notes.isEmpty else { return [] }
        return notes.replacingOccurrences(of: "\r\n", with: "\n").replacingOccurrences(of: "\r", with: "\n")
            .components(separatedBy: "\n")
            .compactMap { collapseWS($0) }
            .filter { !$0.isEmpty }
    }

    /// `share_summary_text(snapshot, plan, type_labels)` → `{text, record_ids}`.
    static func shareSummary(_ snapshot: RJ, plan: RJ, typeLabels: [String: String]?) -> RJ {
        let fields = Set((plan["summary_fields"].array ?? []).compactMap(\.string))
        let records = planRecords(snapshot, plan)
        guard plan["include_summary"].truthy, !records.isEmpty else {
            return .obj(["text": .null, "record_ids": .arr([])])
        }
        let highlights = snapList(snapshot, "highlights")
        var blocks: [String] = []
        for record in records {
            let id = record["id"].string ?? ""
            let rows = rowsOf(snapshot, id).filter { $0["state"].string != "rejected" }
            var lines = ["\(collapseWS(record["title"].string) ?? "") — \(record["sort_date"].string ?? "")"]
            var head = coachTypeLabel(typeLabels, record["record_type"].string)
            if fields.contains("facility"), let facility = collapseWS(coachBestValue(rows, "facility")), !facility.isEmpty {
                head += " · " + facility
            }
            lines.append(head)
            if fields.contains("doctor"), let doctor = collapseWS(coachBestValue(rows, "doctor_name")), !doctor.isEmpty {
                lines.append("Doctor: " + doctor)
            }
            if fields.contains("patient_name"), let patient = collapseWS(coachBestValue(rows, "patient_name")), !patient.isEmpty {
                lines.append("Patient: " + patient)
            }
            if fields.contains("dates") {
                let parts = shareDateLabels.compactMap { entry -> String? in
                    guard let value = collapseWS(coachBestValue(rows, entry.key)), !value.isEmpty else { return nil }
                    return entry.label + " " + value
                }
                if !parts.isEmpty { lines.append("Dates: " + parts.joined(separator: " · ")) }
            }
            if fields.contains("test_results") {
                let items = recordTestResults(snapshot, id).enumeratedArray().stableSorted { a, b in
                    let ga = flagGroup(a.element["flag"].string), gb = flagGroup(b.element["flag"].string)
                    if ga != gb { return ga < gb }
                    return a.offset < b.offset
                }.map { shareResultItem($0.element) }.filter { !$0.isEmpty }
                if !items.isEmpty { lines += ["Results:"] + items.map { "- " + $0 } }
            }
            for (label, key, section) in [("Medications", "medication", "medications"),
                                          ("Diagnoses", "diagnosis", "diagnoses"),
                                          ("Recommendations", "recommendation", "recommendations")] {
                guard fields.contains(section) else { continue }
                let items = rows.filter { $0["field_key"].string == key }.compactMap { row -> String? in
                    let text = key == "medication" ? shareMedicationItem(row) : (collapseWS(row["value_text"].string) ?? "")
                    return text.isEmpty ? nil : text
                }
                if !items.isEmpty { lines += [label + ":"] + items.map { "- " + $0 } }
            }
            if plan["include_notes"].truthy {
                let notes = shareNotesLines(record["notes"].string)
                if !notes.isEmpty { lines += [shareNotesHeader] + notes }
            }
            if plan["include_highlights"].truthy {
                let items = highlights
                    .filter { $0["record_id"].string == id && $0["section"].string == "summary" && !$0["dismissed"].truthy }
                    .stableSorted { a, b in
                        let pa = a["position"].double ?? 0, pb = b["position"].double ?? 0
                        if pa != pb { return pa < pb }
                        return scalarLess(a["id"].string ?? "", b["id"].string ?? "")
                    }
                    .compactMap { collapseWS($0["text"].string) }
                    .filter { !$0.isEmpty }
                if !items.isEmpty { lines += [shareHighlightsHeader] + items.map { "- " + $0 } }
            }
            blocks.append(lines.joined(separator: "\n"))
        }
        return .obj([
            "text": .str(blocks.joined(separator: shareSeparator) + "\n" + shareFooter),
            "record_ids": .arr(records.map { $0["id"] }),
        ])
    }

    // MARK: - §34 redaction targets

    /// `_page_blocks`: the page's `blocks_json` as an array, or nil when it cannot be redacted.
    static func pageBlocks(_ page: RJ) -> [RJ]? {
        let raw = page["blocks_json"]
        if raw.isNull { return nil }
        if let text = raw.string {
            let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
            guard !trimmed.isEmpty, let parsed = RJ.parse(trimmed) else { return nil }
            return parsed.array
        }
        return raw.array
    }

    /// `_block_box`: a 4-number `b`, else nil.
    static func blockBox(_ block: RJ) -> [Double]? {
        guard block.object != nil, let values = block["b"].array, values.count == 4 else { return nil }
        let numbers = values.compactMap { value -> Double? in
            if case .bool = value { return nil }
            return value.double
        }
        return numbers.count == 4 ? numbers : nil
    }

    /// `_inflate_box`: inflated on every side, clamped to 0…1, `round4`.
    static func inflateBox(_ box: [Double]) -> [Double] {
        let x0 = max(0, box[0] - redactionInflate)
        let y0 = max(0, box[1] - redactionInflate)
        var x1 = min(1, box[0] + box[2] + redactionInflate)
        var y1 = min(1, box[1] + box[3] + redactionInflate)
        if x1 < x0 { x1 = x0 }
        if y1 < y0 { y1 = y0 }
        return [round4(x0), round4(y0), round4(x1 - x0), round4(y1 - y0)]
    }

    static func patientNameTargets(_ rows: [RJ]) -> (full: [String], parts: Set<String>) {
        guard let value = coachBestValue(rows, "patient_name"), !value.isEmpty else { return ([], []) }
        let full = words(fold(value))
        return (full, Set(full.filter { $0.count >= 3 }))
    }

    static func lineHasName(_ lineWords: [String], _ full: [String], _ parts: Set<String>) -> Bool {
        if !full.isEmpty, full.count <= lineWords.count {
            for start in 0...(lineWords.count - full.count) where Array(lineWords[start..<(start + full.count)]) == full {
                return true
            }
        }
        return lineWords.contains { parts.contains($0) }
    }

    /// `_line_has_phone_number`: a 7…15 digit run of digits, spaces, `-`, `+`, `(`, `)` that is not a date.
    static func lineHasPhoneNumber(_ folded: String) -> Bool {
        let characters = Array(folded)
        var i = 0
        while i < characters.count {
            guard phoneChars.contains(characters[i]) else {
                i += 1
                continue
            }
            var j = i
            while j < characters.count, phoneChars.contains(characters[j]) { j += 1 }
            var run = String(characters[i..<j])
            while run.hasPrefix(" ") { run.removeFirst() }
            while run.hasSuffix(" ") { run.removeLast() }
            let digits = run.filter { $0.isASCII && $0.isNumber }.count
            if digits >= phoneMinDigits, digits <= phoneMaxDigits, reDateRun.fullmatch(run) == nil { return true }
            i = j
        }
        return false
    }

    static let reRangeSpaced = Rx("[0-9]+(?:[.][0-9]+)? [-–—] [0-9]+(?:[.][0-9]+)?")
    static let reRangeDecimal = Rx("[0-9]+[.][0-9]+ ?[-–—] ?[0-9]+(?:[.][0-9]+)?|[0-9]+(?:[.][0-9]+)? ?[-–—] ?[0-9]+[.][0-9]+")

    /// `_lab_result_line`: a printed result line, so its digit runs are values and ranges rather than
    /// a phone number — it parses as a §13 lab row, carries a number immediately followed (optional
    /// space) by a `units.json` unit, or is itself a printed numeric range.
    static func labResultLine(raw: String?, folded: String) -> Bool {
        let lines = pageLines(raw ?? "", 0)
        if let first = lines.first, parseLabLine(first) != nil { return true }
        var pos = 0
        while !folded.isEmpty, let m = reNumberUnit.search(folded, pos) {
            if matchUnit(folded, m.end) != nil { return true }
            pos = m.start + 1
        }
        let trimmed = folded.rStrip(" ").rLStrip(" ")
        return reRangeSpaced.fullmatch(trimmed) != nil || reRangeDecimal.fullmatch(trimmed) != nil
    }

    static func lineHasOtherID(_ folded: String, _ lineWords: [String]) -> Bool {
        guard reIDLabel.search(folded) != nil else { return false }
        return lineWords.contains { word in
            word.count >= 6
                && word.contains(where: { $0.isASCII && $0.isLetter })
                && word.contains(where: { $0.isASCII && $0.isNumber })
        }
    }

    /// `_redaction_classes`: the requested classes in `REDACTION_CLASSES` order.
    static func resolvedRedactionClasses(_ classes: [String]) -> [String] {
        let wanted = Set(classes)
        return redactionClasses.filter { wanted.contains($0) }
    }

    static func shareWarning(_ code: String, recordID: String? = nil, pageIndex: Int? = nil, title: String? = nil) -> RJ {
        let values = ["title": title ?? "", "page": pageIndex.map { "\($0 + 1)" } ?? ""]
        return .obj([
            "code": .str(code), "record_id": .string(recordID),
            "page_index": pageIndex.map { RJ.int($0) } ?? .null,
            "text": .str(fillPlaceholders(shareWarnings[code] ?? "", values)),
        ])
    }

    /// `redaction_targets(snapshot, record_id, classes)`.
    static func redactionTargets(_ snapshot: RJ, recordID: String, classes: [String]) -> RJ {
        let wanted = resolvedRedactionClasses(classes)
        let wantedSet = Set(wanted)
        let rows = rowsOf(snapshot, recordID).filter { $0["state"].string != "rejected" }
        let names = wantedSet.contains("name") ? patientNameTargets(rows) : ([], Set<String>())
        let pages = snapList(snapshot, "pages")
            .filter { $0["record_id"].string == recordID }
            .stableSorted { ($0["page_index"].double ?? 0) < ($1["page_index"].double ?? 0) }
        var out: [RJ] = []
        var excluded: [RJ] = []
        var warnings: [RJ] = []
        for page in pages {
            let pageIndex = Int(page["page_index"].double ?? 0)
            guard let blocks = pageBlocks(page) else {
                if !wanted.isEmpty {
                    excluded.append(.int(pageIndex))
                    warnings.append(shareWarning("page_not_redactable", recordID: recordID, pageIndex: pageIndex))
                }
                continue
            }
            // The raw `t` keeps the 2-space cell boundaries the §13 lab-row parser needs.
            let raw = blocks.map { $0.object != nil ? $0["t"].string : nil }
            let folded = raw.map { text -> String in
                guard let collapsed = collapseWS(text), !collapsed.isEmpty else { return "" }
                return fold(collapsed)
            }
            let lineWords = folded.map { words($0) }
            var hits: [Int: Set<String>] = [:]
            func add(_ index: Int, _ cls: String) { hits[index, default: []].insert(cls) }
            for (i, line) in folded.enumerated() {
                if wantedSet.contains("name"), lineHasName(lineWords[i], names.0, names.1) { add(i, "name") }
                if wantedSet.contains("phone") {
                    // A phone label always wins; a bare digit run only when the line is not a result line.
                    if rePhoneLabel.search(line) != nil {
                        add(i, "phone")
                    } else if lineHasPhoneNumber(line), !labResultLine(raw: raw[i], folded: line) {
                        add(i, "phone")
                    }
                }
                if wantedSet.contains("patient_id"), rePatientIDLabel.search(line) != nil { add(i, "patient_id") }
                if wantedSet.contains("insurance_id"), reInsuranceIDLabel.search(line) != nil { add(i, "insurance_id") }
                if wantedSet.contains("other_ids"), lineHasOtherID(line, lineWords[i]) { add(i, "other_ids") }
                if wantedSet.contains("address"), reAddressLabel.search(line) != nil {
                    var j = i
                    while j < folded.count, j < i + redactionAddressLines {
                        if j > i, lineWords[j].isEmpty { break }
                        add(j, "address")
                        j += 1
                    }
                }
            }
            var lines: [RJ] = []
            var bad = false
            for index in hits.keys.sorted() {
                guard let box = blockBox(blocks[index]) else {
                    bad = true
                    break
                }
                let matched = hits[index] ?? []
                lines.append(.obj([
                    "index": .int(index),
                    "classes": .arr(redactionClasses.filter { matched.contains($0) }.map(RJ.str)),
                    "box": .arr(inflateBox(box).map { RJ.number($0) }),
                ]))
            }
            if bad {
                excluded.append(.int(pageIndex))
                warnings.append(shareWarning("page_not_redactable", recordID: recordID, pageIndex: pageIndex))
                continue
            }
            out.append(.obj(["page_index": .int(pageIndex), "lines": .arr(lines)]))
        }
        return .obj([
            "record_id": .str(recordID), "classes": .arr(wanted.map(RJ.str)),
            "pages": .arr(out), "excluded_pages": .arr(excluded), "warnings": .arr(warnings),
        ])
    }

    // MARK: - §34 share plan warnings

    /// `_plan_pages` → (wanted, missing, isAll).
    static func planPages(_ plan: RJ, record: RJ) -> (wanted: [Int], missing: [RJ], isAll: Bool) {
        let count = Int(record["page_count"].double ?? 0)
        let spec = plan["pages"][record["id"].string ?? ""]
        if spec.isNull || spec.string == "all" {
            return (count > 0 ? Array(0..<count) : [], [], true)
        }
        guard let list = spec.array else { return (count > 0 ? Array(0..<count) : [], [], true) }
        var wanted: [Int] = []
        var missing: [RJ] = []
        var seen = Set<String>()
        for value in list {
            let key = value.compactJSON
            guard seen.insert(key).inserted else { continue }
            var index: Int?
            if case .int(let i) = value { index = i }
            if let index, index >= 0, index < count {
                wanted.append(index)
            } else {
                missing.append(value)
            }
        }
        return (wanted, missing, false)
    }

    /// `share_plan_warnings(snapshot, plan)` → `{warnings, pages, records}`.
    static func sharePlanWarnings(_ snapshot: RJ, plan: RJ) -> RJ {
        var warnings: [RJ] = []
        var kept: [(recordID: String, pages: [Int])] = []
        let map = recordMap(snapshot)
        let redacting = !resolvedRedactionClasses((plan["redactions"].array ?? []).compactMap(\.string)).isEmpty
        let original = plan["include_original"].truthy
        var anyOriginal = false
        for id in (plan["record_ids"].array ?? []).compactMap(\.string) {
            guard let record = map[id] else {
                warnings.append(shareWarning("unknown_record", recordID: id))
                continue
            }
            let title = collapseWS(record["title"].string) ?? ""
            let planned = planPages(plan, record: record)
            for value in planned.missing {
                var pageText = value.compactJSON
                if case .int(let i) = value { pageText = "\(i + 1)" }
                warnings.append(.obj([
                    "code": .str("page_missing"), "record_id": .str(id),
                    "page_index": value,
                    "text": .str(fillPlaceholders(shareWarnings["page_missing"] ?? "", ["title": title, "page": pageText])),
                ]))
            }
            var pages = planned.wanted
            if original, redacting {
                let targets = redactionTargets(snapshot, recordID: id, classes: (plan["redactions"].array ?? []).compactMap(\.string))
                let excluded = Set((targets["excluded_pages"].array ?? []).compactMap { $0.double.map { Int($0) } })
                for warning in targets["warnings"].array ?? [] {
                    if let index = warning["page_index"].double.map({ Int($0) }), planned.wanted.contains(index) {
                        warnings.append(warning)
                    }
                }
                pages = planned.wanted.filter { !excluded.contains($0) }
                if !planned.wanted.isEmpty, pages.isEmpty {
                    warnings.append(shareWarning("record_not_redactable", recordID: id, title: title))
                }
            }
            if original {
                if !record["file_path"].truthy {
                    warnings.append(shareWarning("no_original", recordID: id, title: title))
                    pages = []
                } else if !pages.isEmpty {
                    anyOriginal = true
                }
            } else {
                pages = []
            }
            kept.append((id, pages))
        }
        if original, anyOriginal {
            if redacting {
                warnings.append(shareWarning("redacted_text_layer_lost"))
            } else if kept.contains(where: { entry in
                guard let record = map[entry.recordID] else { return false }
                return !planPages(plan, record: record).isAll && !entry.pages.isEmpty && record["file_type"].string == "pdf"
            }) {
                warnings.append(shareWarning("text_layer_lost"))
            }
        }
        let summary = shareSummary(snapshot, plan: plan, typeLabels: nil)
        if summary["text"].isNull, !anyOriginal {
            warnings.append(shareWarning("nothing_to_share"))
        }
        return .obj([
            "warnings": .arr(warnings),
            "pages": .arr(kept.map { .obj(["record_id": .str($0.recordID), "pages": .arr($0.pages.map(RJ.int))]) }),
            "records": .arr(kept.map { .str($0.recordID) }),
        ])
    }

    // MARK: - Vector dispatch

    static func runShareCase(function: String, input: RJ, fixtures: RJ) -> RJ {
        var snapshot = input["snapshot"]
        if let name = snapshot.string { snapshot = fixtures["snapshots"][name] }
        switch function {
        case "share_summary":
            var labels: [String: String]?
            if let object = input["type_labels"].object { labels = object.compactMapValues(\.string) }
            return shareSummary(snapshot, plan: input["plan"], typeLabels: labels)
        case "redaction":
            switch input["op"].string ?? "targets" {
            case "plan_warnings":
                return sharePlanWarnings(snapshot, plan: input["plan"])
            default:
                return redactionTargets(snapshot, recordID: input["record_id"].string ?? "",
                                        classes: (input["classes"].array ?? []).compactMap(\.string))
            }
        case "archive":
            return runArchiveCase(input: input, snapshot: snapshot)
        default:
            return .null
        }
    }
}
