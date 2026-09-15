import Foundation

// Swift port of `scripts/records_reference.py` (docs/health-records.md §8.1, §9.3, §10–§17).
// The reference wins over prose; this file follows it line by line. Strings are indexed in UTF-16
// units (all contract text is BMP, so UTF-16 offsets equal the reference's code point offsets).

// MARK: - JSON value

/// JSON value used for vectors and for the reference's dict-shaped results.
nonisolated enum RJ: Sendable, CustomStringConvertible {
    case null
    case bool(Bool)
    case int(Int)
    case num(Double)
    case str(String)
    case arr([RJ])
    case obj([String: RJ])

    subscript(key: String) -> RJ {
        if case .obj(let dict) = self { return dict[key] ?? .null }
        return .null
    }

    var isNull: Bool { if case .null = self { return true } else { return false } }
    var string: String? { if case .str(let s) = self { return s } else { return nil } }
    var array: [RJ]? { if case .arr(let a) = self { return a } else { return nil } }
    var object: [String: RJ]? { if case .obj(let o) = self { return o } else { return nil } }
    var bool: Bool? { if case .bool(let b) = self { return b } else { return nil } }

    /// Numeric value of an int / number (not bool).
    var double: Double? {
        switch self {
        case .int(let i): Double(i)
        case .num(let d): d
        default: nil
        }
    }

    /// Python truthiness.
    var truthy: Bool {
        switch self {
        case .null: false
        case .bool(let b): b
        case .int(let i): i != 0
        case .num(let d): d != 0
        case .str(let s): !s.isEmpty
        case .arr(let a): !a.isEmpty
        case .obj(let o): !o.isEmpty
        }
    }

    static func number(_ value: Double?) -> RJ {
        guard let value else { return .null }
        if value.rounded() == value, abs(value) < 1e15 { return .int(Int(value)) }
        return .num(value)
    }

    static func string(_ value: String?) -> RJ { value.map(RJ.str) ?? .null }

    static func from(_ any: Any?) -> RJ {
        guard let any, !(any is NSNull) else { return .null }
        if let number = any as? NSNumber {
            if CFGetTypeID(number) == CFBooleanGetTypeID() { return .bool(number.boolValue) }
            if CFNumberIsFloatType(number) { return .num(number.doubleValue) }
            return .int(number.intValue)
        }
        if let s = any as? String { return .str(s) }
        if let a = any as? [Any] { return .arr(a.map { RJ.from($0) }) }
        if let o = any as? [String: Any] { return .obj(o.mapValues { RJ.from($0) }) }
        return .null
    }

    static func parse(_ text: String) -> RJ? {
        guard let data = text.data(using: .utf8),
              let any = try? JSONSerialization.jsonObject(with: data, options: [.fragmentsAllowed]) else { return nil }
        return from(any)
    }

    var anyValue: Any {
        switch self {
        case .null: NSNull()
        case .bool(let b): b
        case .int(let i): i
        case .num(let d): d
        case .str(let s): s
        case .arr(let a): a.map(\.anyValue)
        case .obj(let o): o.mapValues(\.anyValue)
        }
    }

    /// Compact JSON with sorted keys (stored `value_json`).
    var jsonText: String {
        guard JSONSerialization.isValidJSONObject(anyValue),
              let data = try? JSONSerialization.data(withJSONObject: anyValue, options: [.sortedKeys, .withoutEscapingSlashes])
        else {
            if case .str(let s) = self { return "\"\(s)\"" }
            return "null"
        }
        return String(data: data, encoding: .utf8) ?? "null"
    }

    var description: String { jsonText }

    /// §8 key order of `value_json` objects (unknown keys follow, sorted).
    static let valueJSONKeyOrder = ["name", "value", "value_num", "unit", "ref_text", "ref_low", "ref_high", "flag",
                                    "strength", "form", "dose", "frequency", "duration", "instructions", "precision", "role"]

    /// Compact JSON (no spaces) with keys in the §8 order — how `value_json` is stored (§17).
    var compactJSON: String {
        switch self {
        case .null: return "null"
        case .bool(let b): return b ? "true" : "false"
        case .int(let i): return "\(i)"
        case .num(let d):
            if d.rounded() == d, abs(d) < 1e15 { return "\(Int(d))" }
            return "\(d)"
        case .str(let s):
            guard let data = try? JSONSerialization.data(withJSONObject: s, options: [.fragmentsAllowed, .withoutEscapingSlashes]) else { return "\"\"" }
            return String(decoding: data, as: UTF8.self)
        case .arr(let a):
            return "[" + a.map(\.compactJSON).joined(separator: ",") + "]"
        case .obj(let o):
            let known = RJ.valueJSONKeyOrder.filter { o[$0] != nil }
            let rest = o.keys.filter { !RJ.valueJSONKeyOrder.contains($0) }.sorted()
            return "{" + (known + rest).map { RJ.str($0).compactJSON + ":" + o[$0]!.compactJSON }.joined(separator: ",") + "}"
        }
    }

    /// Vector comparison: numbers by value, keys unordered, arrays ordered.
    static func same(_ a: RJ, _ b: RJ) -> Bool {
        switch (a, b) {
        case (.null, .null): return true
        case (.bool(let x), .bool(let y)): return x == y
        case (.str(let x), .str(let y)): return x == y
        case (.arr(let x), .arr(let y)): return x.count == y.count && zip(x, y).allSatisfy { same($0, $1) }
        case (.obj(let x), .obj(let y)):
            guard Set(x.keys) == Set(y.keys) else { return false }
            return x.allSatisfy { same($0.value, y[$0.key]!) }
        default:
            if let x = a.double, let y = b.double { return abs(x - y) < 1e-9 }
            return false
        }
    }
}

// MARK: - String helpers (UTF-16 offsets, Python slicing semantics)

extension String {
    fileprivate var ns: NSString { self as NSString }
    var rLen: Int { utf16.count }

    /// Python `s[a:b]` (clamped, non-negative indexes).
    func rSub(_ a: Int, _ b: Int? = nil) -> String {
        let length = rLen
        let start = max(0, min(a, length))
        let end = max(start, min(b ?? length, length))
        return (self as NSString).substring(with: NSRange(location: start, length: end - start))
    }

    func rChar(_ i: Int) -> String { rSub(i, i + 1) }

    func rFind(_ needle: String, _ start: Int = 0) -> Int {
        let length = rLen
        guard start <= length else { return -1 }
        if needle.isEmpty { return max(0, start) }
        let range = (self as NSString).range(of: needle, options: [.literal], range: NSRange(location: max(0, start), length: length - max(0, start)))
        return range.location == NSNotFound ? -1 : range.location
    }

    func rStarts(_ prefix: String, at position: Int) -> Bool {
        let length = prefix.rLen
        guard position >= 0, position + length <= rLen else { return false }
        return rSub(position, position + length) == prefix
    }

    func rStrip(_ chars: String) -> String { rRStrip(chars).rLStrip(chars) }

    func rLStrip(_ chars: String) -> String {
        let set = Set(chars.utf16)
        let units = Array(utf16)
        var start = 0
        while start < units.count, set.contains(units[start]) { start += 1 }
        return rSub(start)
    }

    func rRStrip(_ chars: String) -> String {
        let set = Set(chars.utf16)
        let units = Array(utf16)
        var end = units.count
        while end > 0, set.contains(units[end - 1]) { end -= 1 }
        return rSub(0, end)
    }

    /// Python `str.strip()` (whitespace).
    var rStripWS: String { trimmingCharacters(in: .whitespacesAndNewlines) }

    /// Python `s.split(" ")` (keeps empty pieces).
    var rSplitSpace: [String] { components(separatedBy: " ") }

    func rCount(_ needle: String) -> Int { components(separatedBy: needle).count - 1 }
}

// MARK: - Regex with Python re semantics

nonisolated struct RM {
    let result: NSTextCheckingResult
    let text: NSString
    var start: Int { result.range.location }
    var end: Int { result.range.location + result.range.length }
    var group0: String { text.substring(with: result.range) }

    func g(_ i: Int) -> String? {
        let range = result.range(at: i)
        return range.location == NSNotFound ? nil : text.substring(with: range)
    }

    func gStart(_ i: Int) -> Int { result.range(at: i).location }
    func gEnd(_ i: Int) -> Int { let r = result.range(at: i); return r.location + r.length }
}

nonisolated final class Rx: @unchecked Sendable {
    let pattern: String
    let regex: NSRegularExpression
    let full: NSRegularExpression

    init(_ pattern: String) {
        self.pattern = pattern
        regex = try! NSRegularExpression(pattern: pattern)
        full = try! NSRegularExpression(pattern: "(?:" + pattern + ")\\z")
    }

    /// `re.search(s, pos)`: lookbehind sees the text before `pos`; `^` only at 0.
    func search(_ s: String, _ pos: Int = 0) -> RM? {
        let length = s.rLen
        guard pos <= length else { return nil }
        let range = NSRange(location: pos, length: length - pos)
        guard let m = regex.firstMatch(in: s, options: [.withTransparentBounds, .withoutAnchoringBounds], range: range) else { return nil }
        return RM(result: m, text: s as NSString)
    }

    /// `re.match(s)`: anchored at 0.
    func match(_ s: String) -> RM? {
        guard let m = regex.firstMatch(in: s, options: [.anchored], range: NSRange(location: 0, length: s.rLen)) else { return nil }
        return RM(result: m, text: s as NSString)
    }

    func fullmatch(_ s: String) -> RM? {
        guard let m = full.firstMatch(in: s, options: [.anchored], range: NSRange(location: 0, length: s.rLen)) else { return nil }
        return RM(result: m, text: s as NSString)
    }

    func finditer(_ s: String) -> [RM] {
        regex.matches(in: s, range: NSRange(location: 0, length: s.rLen)).map { RM(result: $0, text: s as NSString) }
    }

    func findall(_ s: String) -> [String] { finditer(s).map(\.group0) }

    func sub(_ s: String, _ replacement: String) -> String {
        regex.stringByReplacingMatches(in: s, range: NSRange(location: 0, length: s.rLen), withTemplate: NSRegularExpression.escapedTemplate(for: replacement))
    }

    func split(_ s: String) -> [String] {
        var out: [String] = []
        var last = 0
        for m in finditer(s) {
            out.append(s.rSub(last, m.start))
            last = m.end
        }
        out.append(s.rSub(last))
        return out
    }
}

// MARK: - Calendar date

nonisolated struct YMD: Hashable, Comparable, Sendable {
    var y: Int
    var m: Int
    var d: Int

    static func daysIn(_ y: Int, _ m: Int) -> Int {
        switch m {
        case 1, 3, 5, 7, 8, 10, 12: return 31
        case 4, 6, 9, 11: return 30
        default: return (y % 4 == 0 && y % 100 != 0) || y % 400 == 0 ? 29 : 28
        }
    }

    static func valid(_ y: Int, _ m: Int, _ d: Int) -> YMD? {
        guard (1...9999).contains(y), (1...12).contains(m), d >= 1, d <= daysIn(y, m) else { return nil }
        return YMD(y: y, m: m, d: d)
    }

    static func iso(_ text: String) -> YMD? {
        let parts = text.split(separator: "-").compactMap { Int($0) }
        guard parts.count == 3 else { return nil }
        return valid(parts[0], parts[1], parts[2])
    }

    var jdn: Int {
        let a = (14 - m) / 12
        let yy = y + 4800 - a
        let mm = m + 12 * a - 3
        return d + (153 * mm + 2) / 5 + 365 * yy + yy / 4 - yy / 100 + yy / 400 - 32045
    }

    init(y: Int, m: Int, d: Int) {
        self.y = y
        self.m = m
        self.d = d
    }

    init(jdn: Int) {
        let a = jdn + 32044
        let b = (4 * a + 3) / 146097
        let c = a - 146097 * b / 4
        let dd = (4 * c + 3) / 1461
        let e = c - 1461 * dd / 4
        let mm = (5 * e + 2) / 153
        d = e - (153 * mm + 2) / 5 + 1
        m = mm + 3 - 12 * (mm / 10)
        y = 100 * b + dd - 4800 + mm / 10
    }

    func days(_ n: Int) -> YMD { YMD(jdn: jdn + n) }

    func months(_ n: Int) -> YMD {
        let total = y * 12 + (m - 1) + n
        let ny = total / 12
        let nm = total % 12 + 1
        return YMD(y: ny, m: nm, d: min(d, YMD.daysIn(ny, nm)))
    }

    func years(_ n: Int) -> YMD {
        if m == 2, d == 29, YMD.daysIn(y + n, 2) == 28 { return YMD(y: y + n, m: 2, d: 28) }
        return YMD(y: y + n, m: m, d: d)
    }

    /// Monday = 0.
    var weekday: Int { jdn % 7 }

    var isoString: String { String(format: "%04d-%02d-%02d", y, m, d) }

    static func < (a: YMD, b: YMD) -> Bool { (a.y, a.m, a.d) < (b.y, b.m, b.d) }
}

extension Array {
    /// Stable sort (Python `sorted`).
    func stableSorted(by less: (Element, Element) -> Bool) -> [Element] {
        enumerated().sorted { a, b in
            if less(a.element, b.element) { return true }
            if less(b.element, a.element) { return false }
            return a.offset < b.offset
        }.map(\.element)
    }
}

// MARK: - Reference core

nonisolated enum RR {
    static let recordTypes = ["lab_report", "prescription", "consultation_note", "discharge_summary", "imaging_report",
                              "diagnostic_report", "medication_list", "vaccination_record", "bill", "insurance",
                              "personal_note", "other"]
    static let defaultCategory: [String: String] = [
        "lab_report": "lab_reports", "diagnostic_report": "lab_reports", "prescription": "prescriptions",
        "consultation_note": "doctor_visits", "discharge_summary": "hospitalization", "imaging_report": "imaging",
        "medication_list": "medication", "vaccination_record": "vaccination", "bill": "insurance_bills",
        "insurance": "insurance_bills", "personal_note": "personal_notes", "other": "other",
    ]
    static let typeLabel: [String: String] = [
        "lab_report": "Lab Report", "prescription": "Prescription", "consultation_note": "Doctor Note",
        "discharge_summary": "Discharge Summary", "imaging_report": "Imaging Report",
        "diagnostic_report": "Diagnostic Report", "medication_list": "Medication List",
        "vaccination_record": "Vaccination Record", "bill": "Bill", "insurance": "Insurance",
        "personal_note": "Note", "other": "Record",
    ]
    static let dateKeys = ["report_date", "collection_date", "prescription_date", "discharge_date", "visit_date",
                           "admission_date", "follow_up_date"]
    static let documentDateOrder = ["report_date", "collection_date", "prescription_date", "discharge_date", "visit_date"]
    static let multiValuedKeys: Set<String> = ["test_result", "diagnosis", "symptom", "medication", "procedure", "recommendation"]
    static let fieldKeys = ["doctor_name", "doctor_specialty", "facility", "department", "patient_name", "patient_age",
                            "patient_sex", "report_name", "test_result", "diagnosis", "symptom", "medication", "procedure",
                            "recommendation", "follow_up_date", "visit_date", "collection_date", "report_date",
                            "prescription_date", "admission_date", "discharge_date", "document_time", "location"]

    // MARK: Shared data files

    struct TypeRule {
        var scope: String
        var rx: Rx
        var weight: Int
    }

    struct TypeDef {
        var id: String
        var category: String
        var rules: [TypeRule]
    }

    struct RuleSet {
        var threshold: Int
        var margin: Int
        var types: [TypeDef]
    }

    static func loadRuleSet(_ data: Data) -> RuleSet? {
        guard case .obj(let root)? = RJ.parse(String(decoding: data, as: UTF8.self)),
              let threshold = root["threshold"]?.double, let margin = root["margin"]?.double,
              let types = root["types"]?.array else { return nil }
        return RuleSet(threshold: Int(threshold), margin: Int(margin), types: types.map { t in
            TypeDef(id: t["id"].string ?? "", category: t["category"].string ?? "other", rules: (t["rules"].array ?? []).map { r in
                TypeRule(scope: r["scope"].string ?? "body", rx: Rx(r["pattern"].string ?? "(?!)"), weight: Int(r["weight"].double ?? 0))
            })
        })
    }

    static func loadUnits(_ data: Data) -> [(String, String)] {
        guard let root = RJ.parse(String(decoding: data, as: UTF8.self)), let list = root["units"].array else { return [] }
        var seen: [String: String] = [:]
        for u in list {
            let canonical = u["canonical"].string ?? ""
            for v in (u["variants"].array ?? []).compactMap(\.string) + [canonical] {
                let fv = fold(v)
                if !fv.isEmpty, seen[fv] == nil { seen[fv] = canonical }
            }
        }
        return seen.map { ($0.key, $0.value) }.sorted { a, b in
            if a.0.rLen != b.0.rLen { return a.0.rLen > b.0.rLen }
            return Array(a.0.unicodeScalars.map(\.value)).lexicographicallyPrecedes(b.0.unicodeScalars.map(\.value))
        }
    }

    private static func bundled(_ name: String) -> Data? {
        let bundles = [Bundle.main, Bundle(for: BundleMarker.self)]
        for bundle in bundles {
            if let url = bundle.url(forResource: name, withExtension: "json"), let data = try? Data(contentsOf: url) { return data }
        }
        return nil
    }

    private final class BundleMarker {}

    static let ruleSet: RuleSet = bundled("record_types").flatMap(loadRuleSet) ?? RuleSet(threshold: 4, margin: 2, types: [])
    static let units: [(String, String)] = bundled("units").map(loadUnits) ?? []

    // MARK: §10 Folding

    static let zeroWidth: Set<UInt32> = [0x00AD, 0x200B, 0x200C, 0x200D, 0x2060, 0xFEFF]

    static func pre(_ s: String) -> String {
        let text = s.replacingOccurrences(of: "\r\n", with: "\n").replacingOccurrences(of: "\r", with: "\n")
        var scalars = String.UnicodeScalarView()
        for scalar in text.decomposedStringWithCompatibilityMapping.unicodeScalars {
            if zeroWidth.contains(scalar.value) { continue }
            switch scalar.properties.generalCategory {
            case .nonspacingMark, .spacingMark, .enclosingMark: continue
            default: scalars.append(scalar)
            }
        }
        return String(scalars)
    }

    static func collapse(_ line: String) -> String {
        var out = String.UnicodeScalarView()
        var prevSpace = false
        for scalar in line.unicodeScalars {
            if scalar == " " || scalar == "\t" {
                if !prevSpace { out.append(" ") }
                prevSpace = true
            } else {
                out.append(scalar)
                prevSpace = false
            }
        }
        return String(out).rStrip(" ")
    }

    static func lowerCP(_ s: String) -> String {
        var out = String.UnicodeScalarView()
        for scalar in s.unicodeScalars {
            let lower = scalar.properties.lowercaseMapping.unicodeScalars
            if lower.count == 1, let first = lower.first { out.append(first) } else { out.append(scalar) }
        }
        return String(out)
    }

    static func splitLines(_ s: String) -> [String] { s.components(separatedBy: "\n") }

    static func pfold(_ s: String) -> String { splitLines(pre(s)).map(collapse).joined(separator: "\n") }

    static func fold(_ s: String) -> String { lowerCP(pfold(s)) }

    static let cellSplit = Rx("[ ]*\t[ \t]*|[ ]{2,}")

    struct Line {
        var page: Int
        var index: Int
        var p: String
        var f: String
        var cells: [(Int, Int)]

        func cellEnd(_ pos: Int) -> Int {
            for (s, e) in cells where s <= pos && pos < e { return e }
            return p.rLen
        }

        func same(_ other: Line?) -> Bool { other.map { $0.page == page && $0.index == index } ?? false }
    }

    static func pageLines(_ text: String?, _ page: Int) -> [Line] {
        splitLines(pre(text ?? "")).enumerated().map { i, raw in
            let parts = cellSplit.split(raw).map { $0.rStrip(" \t") }.filter { !$0.isEmpty }
            var cells: [(Int, Int)] = []
            var pos = 0
            for c in parts {
                cells.append((pos, pos + c.rLen))
                pos += c.rLen + 1
            }
            let p = parts.joined(separator: " ")
            return Line(page: page, index: i, p: p, f: lowerCP(p), cells: cells)
        }
    }

    static func nonempty(_ lines: [Line]) -> [Line] { lines.filter { !$0.p.isEmpty } }
    static func head(_ lines: [Line]) -> [Line] { Array(nonempty(lines).prefix(25)) }

    static func round2(_ x: Double) -> Double { (x * 100 + 0.5 + 1e-9).rounded(.down) / 100 }

    static func isAlnum(_ scalar: Unicode.Scalar) -> Bool {
        let v = scalar.value
        if v < 128 {
            return (v >= 97 && v <= 122) || (v >= 48 && v <= 57) || (v >= 65 && v <= 90)
        }
        switch scalar.properties.generalCategory {
        case .uppercaseLetter, .lowercaseLetter, .titlecaseLetter, .modifierLetter, .otherLetter, .decimalNumber: return true
        default: return false
        }
    }

    static func words(_ folded: String) -> [String] {
        var out: [String] = []
        var cur = String.UnicodeScalarView()
        for scalar in folded.unicodeScalars {
            if isAlnum(scalar) {
                cur.append(scalar)
            } else if !cur.isEmpty {
                out.append(String(cur))
                cur = String.UnicodeScalarView()
            }
        }
        if !cur.isEmpty { out.append(String(cur)) }
        return out
    }

    static func normText(_ s: String?) -> String { words(fold(s ?? "")).joined(separator: " ") }

    static let normDoctorTitles: Set<String> = ["dr", "doctor", "prof"]
    static let normPatientTitles: Set<String> = ["mr", "mrs", "ms", "miss", "master", "mstr", "baby", "smt", "shri", "sri", "kumari", "kum", "mx"]

    static func normalizeValue(_ key: String, _ valueText: String?, _ valueJSON: RJ = .null) -> String {
        if dateKeys.contains(key) || key == "document_time" {
            return (valueText ?? "").rStripWS
        }
        if key == "doctor_name" || key == "patient_name" {
            var ws = normText(valueText).rSplitSpace
            let drop = key == "doctor_name" ? normDoctorTitles : normPatientTitles
            while ws.count > 1, drop.contains(ws[0]) { ws.removeFirst() }
            return ws.joined(separator: " ")
        }
        if key == "test_result" {
            let name = valueJSON["name"].isNull ? valueText : valueJSON["name"].string
            let value = valueJSON["value"].truthy ? valueJSON["value"].string : ""
            return normText(name) + "|" + normText(value ?? "")
        }
        return normText(valueText)
    }

    // MARK: §10 Classifier

    static func classifyLines(_ linesByPage: [[Line]]) -> [(String, String, Int)] {
        let headTexts = (linesByPage.first.map(head) ?? []).map(\.f)
        let body = linesByPage.flatMap { $0 }.map(\.f).filter { !$0.isEmpty }
        return ruleSet.types.map { t in
            var s = 0
            for r in t.rules {
                let scope = r.scope == "head" ? headTexts : body
                if scope.contains(where: { r.rx.search($0) != nil }) { s += r.weight }
            }
            return (t.id, t.category, s)
        }
    }

    static func classify(_ pages: [String]) -> RJ {
        let scores = classifyLines(pages.enumerated().map { pageLines($1, $0) })
        var bestI = 0
        for (i, s) in scores.enumerated() where s.2 > scores[bestI].2 { bestI = i }
        let best = scores.isEmpty ? 0 : scores[bestI].2
        let second = scores.enumerated().filter { $0.offset != bestI }.map(\.element.2).max() ?? 0
        var outScores: [String: RJ] = [:]
        for s in scores { outScores[s.0] = .int(s.2) }
        if !scores.isEmpty, best >= ruleSet.threshold, best - second >= ruleSet.margin {
            let excess = Double(min(best - second - ruleSet.margin, 3))
            let strength = min(Double(best) / Double(ruleSet.threshold), 2.0)
            let conf = round2(min(0.95, 0.55 + 0.10 * excess + 0.05 * strength))
            return .obj(["record_type": .str(scores[bestI].0), "category": .str(scores[bestI].1), "confidence": .number(conf), "scores": .obj(outScores)])
        }
        return .obj(["record_type": .str("other"), "category": .str("other"), "confidence": .int(0), "scores": .obj(outScores)])
    }

    static func pageBestType(_ lines: [Line]) -> String? {
        let scores = classifyLines([lines])
        guard !scores.isEmpty else { return nil }
        var bestI = 0
        for (i, s) in scores.enumerated() where s.2 > scores[bestI].2 { bestI = i }
        return scores[bestI].2 >= ruleSet.threshold ? scores[bestI].0 : nil
    }

    // MARK: §11 Dates

    static let monthAlt = "january|february|march|april|june|july|august|september|october|november|december|jan|feb|mar|apr|may|jun|jul|aug|sept|sep|oct|nov|dec"
    static let monthNum: [String: Int] = [
        "january": 1, "february": 2, "march": 3, "april": 4, "may": 5, "june": 6, "july": 7, "august": 8, "september": 9,
        "october": 10, "november": 11, "december": 12, "jan": 1, "feb": 2, "mar": 3, "apr": 4, "jun": 6, "jul": 7, "aug": 8,
        "sept": 9, "sep": 9, "oct": 10, "nov": 11, "dec": 12,
    ]

    static let reISO = Rx(#"(?<![0-9])([0-9]{4})([-/.])([0-9]{1,2})\2([0-9]{1,2})(?![0-9])"#)
    static let reNUM = Rx(#"(?<![0-9])([0-9]{1,2})([-/.])([0-9]{1,2})\2([0-9]{4}|[0-9]{2})(?![0-9])"#)
    static let reDMYText = Rx(#"(?<![0-9a-z])([0-9]{1,2})(?:st|nd|rd|th)?[ ./-]?("# + monthAlt + #")\.?(?![a-z])[ ,./'-]*([0-9]{4}|[0-9]{2})(?![0-9])"#)
    static let reMDYText = Rx(#"(?<![a-z])("# + monthAlt + #")\.?[ ./-]?([0-9]{1,2})(?:st|nd|rd|th)?(?![0-9a-z])[ ,./-]*([0-9]{4})(?![0-9])"#)
    static let reMYText = Rx(#"(?<![a-z])("# + monthAlt + #")\.?(?![a-z])[ ,'./-]*([0-9]{4})(?![0-9])"#)

    struct DateCand {
        var start: Int
        var end: Int
        var dates: [YMD]
        var precision: String
    }

    static func twoDigitYear(_ yy: Int, _ today: YMD) -> Int { yy <= (today.y + 1) % 100 ? 2000 + yy : 1900 + yy }

    static func dateCandidates(_ f: String, _ today: YMD, _ dateOrder: String, bothOrders: Bool = false) -> [DateCand] {
        var raw: [(Int, Int, Int, RM)] = []
        for (prio, rx) in [reISO, reNUM, reDMYText, reMDYText, reMYText].enumerated() {
            var pos = 0
            while let m = rx.search(f, pos) {
                raw.append((m.start, -(m.end - m.start), prio, m))
                pos = m.start + 1
            }
        }
        raw = raw.stableSorted { ($0.0, $0.1, $0.2) < ($1.0, $1.1, $1.2) }
        var out: [DateCand] = []
        var lastEnd = 0
        for (start, _, prio, m) in raw {
            if start < lastEnd { continue }
            var dates: [YMD] = []
            var precision = "day"
            switch prio {
            case 0:
                if let d = YMD.valid(Int(m.g(1)!)!, Int(m.g(3)!)!, Int(m.g(4)!)!) { dates = [d] }
            case 1:
                let a = Int(m.g(1)!)!, b = Int(m.g(3)!)!
                let ytxt = m.g(4)!, sep = m.g(2)!
                if ytxt.rLen == 2 && sep == "." { continue }
                let y = ytxt.rLen == 4 ? Int(ytxt)! : twoDigitYear(Int(ytxt)!, today)
                if a > 12 && b > 12 { continue }
                let orders: [String]
                if a > 12 { orders = ["dmy"] } else if b > 12 { orders = ["mdy"] } else if bothOrders {
                    orders = [dateOrder, dateOrder == "dmy" ? "mdy" : "dmy"]
                } else { orders = [dateOrder] }
                for o in orders {
                    let d = o == "dmy" ? YMD.valid(y, b, a) : YMD.valid(y, a, b)
                    if let d, !dates.contains(d) { dates.append(d) }
                }
            case 2:
                let ytxt = m.g(3)!
                let y = ytxt.rLen == 4 ? Int(ytxt)! : twoDigitYear(Int(ytxt)!, today)
                if let d = YMD.valid(y, monthNum[m.g(2)!]!, Int(m.g(1)!)!) { dates = [d] }
            case 3:
                if let d = YMD.valid(Int(m.g(3)!)!, monthNum[m.g(1)!]!, Int(m.g(2)!)!) { dates = [d] }
            default:
                if let d = YMD.valid(Int(m.g(2)!)!, monthNum[m.g(1)!]!, 1) { dates = [d] }
                precision = "month"
            }
            if dates.isEmpty { continue }
            out.append(DateCand(start: m.start, end: m.end, dates: dates, precision: precision))
            lastEnd = m.end
        }
        return out
    }

    static func plausible(_ d: YMD, _ key: String, _ today: YMD) -> Bool {
        if d < YMD(y: 1900, m: 1, d: 1) { return false }
        if key == "follow_up_date" { return d <= today.years(2) }
        return d <= today.days(1)
    }

    static func lbl(_ alt: String) -> Rx { Rx("(?<![a-z])(?:" + alt + ")(?![a-z])") }

    static let dateLabels: [(kind: String, key: String?, rx: Rx)] = [
        ("dob", nil, lbl(#"dob|d\.o\.b\.?|date of birth|birth ?date|born on|born"#)),
        ("ignore", nil, lbl(#"printed(?: on)?|print date|generated(?: on)?|registered(?: on)?|registration(?: date)?|reg\.? date|received(?: on)?|valid (?:till|upto|up to|until)|expiry(?: date)?|exp\.?(?: date)?|mfg\.?(?: date)?|manufactur[a-z]*|lmp|edd"#)),
        ("key", "collection_date", lbl(#"collected(?: on| at)?|collection(?: date)?|date of collection|sample date|sample collected(?: on)?|sample collection(?: date)?|specimen collected|drawn(?: on)?|coll\.? date|date of sample"#)),
        ("key", "report_date", lbl(#"reported(?: on)?|report date|date of report|reporting date|released(?: on)?|authenticated(?: on)?|authori[sz]ed(?: on)?|result date|approved on|verified on"#)),
        ("key", "visit_date", lbl(#"visit date|date of visit|consultation date|date of consultation|opd date|visited on|seen on|appointment date|encounter date"#)),
        ("key", "prescription_date", lbl(#"rx date|prescription date|date of prescription"#)),
        ("key", "admission_date", lbl(#"date of admission|doa|admitted on|admission date|admitted"#)),
        ("key", "discharge_date", lbl(#"date of discharge|dod|discharged on|discharge date|discharged"#)),
        ("key", "follow_up_date", lbl(#"follow[ -]?up(?: on| date| visit)?|review on|review date|next review|next visit(?: on)?|revisit(?: on)?|f/u|come (?:back|again) on|next due(?: on| date)?|due on"#)),
        ("typed", nil, lbl(#"invoice date|bill date|receipt date|date of issue|issue date|certificate date|date of vaccination|vaccination date|vaccinated on|date of dose|dose date|date administered|study date|date of study|exam date|date of examination|examination date|scan date|test date|date of test|date of procedure|procedure date|date of surgery|claim date|date of service|service date"#)),
        ("bare", nil, lbl(#"date|dated|dt"#)),
    ]
    static let gapWords: Set<String> = ["on", "at", "dt", "date", "dated", "time", "and", "of", "is"]

    static func typeDefaultKey(_ recordType: String) -> String {
        ["lab_report": "report_date", "imaging_report": "report_date", "diagnostic_report": "report_date",
         "consultation_note": "visit_date", "prescription": "prescription_date"][recordType] ?? "report_date"
    }

    struct LabelMatch {
        var start: Int
        var end: Int
        var order: Int
        var kind: String
        var key: String?
    }

    static func labelMatches(_ f: String) -> [LabelMatch] {
        var out: [LabelMatch] = []
        for (order, label) in dateLabels.enumerated() {
            var pos = 0
            while let m = label.rx.search(f, pos) {
                out.append(LabelMatch(start: m.start, end: m.end, order: order, kind: label.kind, key: label.key))
                pos = m.start + 1
            }
        }
        return out
    }

    static func nonOverlappingLabels(_ f: String) -> [LabelMatch] {
        let ms = labelMatches(f).stableSorted { ($0.start, -($0.end - $0.start), $0.order) < ($1.start, -($1.end - $1.start), $1.order) }
        var out: [LabelMatch] = []
        var last = 0
        for t in ms where t.start >= last {
            out.append(t)
            last = t.end
        }
        return out
    }

    static let reAZ09 = Rx("[a-z0-9]+")

    static func gapOK(_ gap: String) -> Bool {
        if gap.rLen > 24 { return false }
        return reAZ09.findall(gap).allSatisfy { gapWords.contains($0) }
    }

    static let reTime = Rx(#"(?<![0-9:])([01]?[0-9]|2[0-3]):([0-5][0-9])(?::[0-5][0-9])?[ ]?(am|pm|a\.m\.?|p\.m\.?)?(?![0-9a-z])"#)
    static let reRelFollow = Rx(#"(?<![a-z])(?:follow[ -]?up|review|revisit|come back|next visit|see me)(?: [a-z]+){0,4} (?:after|in) ([0-9]{1,3}) ?(days?|weeks?|wks?|months?|mths?)(?![a-z])"#)

    struct Pos: Comparable, Hashable {
        var page: Int
        var line: Int
        var col: Int
        static func < (a: Pos, b: Pos) -> Bool { (a.page, a.line, a.col) < (b.page, b.line, b.col) }
    }

    /// Internal item shape shared by dates, fields and lab rows.
    struct Item {
        var key: String
        var valueText: String
        var valueJSON: RJ
        var confidence: Double
        var pos: Pos
        var line: Line
        var date: YMD?
        var precision: String = "day"
        var end: Int = 0

        var sourcePage: Int { line.page }
        var evidence: String { line.p }

        var publicJSON: RJ {
            .obj(["key": .str(key), "value_text": .str(valueText), "value_json": valueJSON, "confidence": .number(confidence),
                  "source_page": .int(pos.page), "evidence": .str(line.p)])
        }
    }

    static func dateItems(_ linesByPage: [[Line]], _ recordType: String, _ today: YMD, _ dateOrder: String) -> [Item] {
        var labelled: [Item] = []
        var unlabeled: [Item] = []
        var relative: [(RM, Line)] = []
        let defaultKey = typeDefaultKey(recordType)
        let headIDs = Set((linesByPage.first.map(head) ?? []).map { Pos(page: $0.page, line: $0.index, col: 0) })
        for lines in linesByPage {
            var prev: Line?
            for ln in lines {
                if ln.f.isEmpty { continue }
                let cands = dateCandidates(ln.f, today, dateOrder)
                let inHead = headIDs.contains(Pos(page: ln.page, line: ln.index, col: 0))
                let lineHasLabel = !labelMatches(ln.f).isEmpty
                var prevPairs: [Int: LabelMatch] = [:]
                if !cands.isEmpty, !lineHasLabel, let prev, dateCandidates(prev.f, today, dateOrder).isEmpty {
                    var labels: [LabelMatch] = []
                    for (cs, ce) in prev.cells {
                        labels += nonOverlappingLabels(prev.f.rSub(cs, ce)).map {
                            LabelMatch(start: $0.start + cs, end: $0.end + cs, order: $0.order, kind: $0.kind, key: $0.key)
                        }
                    }
                    if !labels.isEmpty {
                        var rest = prev.f
                        for l in labels.reversed() { rest = rest.rSub(0, l.start) + " " + rest.rSub(l.end) }
                        let leftoverOK = gapOK(rest.rStripWS) || labels.count == cands.count
                        if labels.count == cands.count && leftoverOK {
                            for i in cands.indices { prevPairs[i] = labels[i] }
                        } else if gapOK(prev.f.rSub(labels.last!.end)) {
                            prevPairs[0] = labels.last!
                        }
                    }
                }
                var segStart = 0
                for (ci, c) in cands.enumerated() {
                    let seg = ln.f.rSub(segStart, c.start)
                    segStart = c.end
                    let d = c.dates[0]
                    let pos = Pos(page: ln.page, line: ln.index, col: c.start)
                    var lab: LabelMatch?
                    var conf = 0.0
                    var ms = labelMatches(seg)
                    if !ms.isEmpty {
                        ms = ms.stableSorted { (-$0.end, $0.start, $0.order) < (-$1.end, $1.start, $1.order) }
                        let best = ms[0]
                        let gap = seg.rSub(best.end)
                        if gapOK(gap) || (best.key == "follow_up_date" && gap.rLen <= 32) {
                            lab = best
                            conf = 0.9
                        }
                    } else if let pair = prevPairs[ci] {
                        lab = pair
                        conf = 0.8
                    }
                    if let lab {
                        if lab.kind == "dob" || lab.kind == "ignore" { continue }
                        var key = lab.key ?? defaultKey
                        if lab.kind == "typed" { key = defaultKey }
                        if lab.kind == "bare" {
                            if !inHead { continue }
                            key = defaultKey
                            conf = (recordType == "prescription" || recordType == "consultation_note") ? 0.8 : 0.6
                        }
                        if !plausible(d, key, today) { continue }
                        labelled.append(Item(key: key, valueText: d.isoString, valueJSON: .null, confidence: conf, pos: pos, line: ln, date: d, precision: c.precision, end: c.end))
                    } else if inHead, plausible(d, defaultKey, today) {
                        unlabeled.append(Item(key: defaultKey, valueText: d.isoString, valueJSON: .null, confidence: 0.6, pos: pos, line: ln, date: d, precision: c.precision, end: c.end))
                    }
                }
                if !labelled.contains(where: { $0.key == "follow_up_date" && $0.line.same(ln) }), let m = reRelFollow.search(ln.f) {
                    relative.append((m, ln))
                }
                prev = ln
            }
        }
        if let u = unlabeled.first {
            if !labelled.contains(where: { $0.key == u.key }) && !labelled.contains(where: { $0.date == u.date }) {
                labelled.append(u)
            }
        }
        let base = bestDocumentDate(labelled)
        for (m, ln) in relative {
            guard let base, let baseDate = base.date else { continue }
            let n = Int(m.g(1)!)!
            let unit = m.g(2)!
            let d: YMD
            if unit.hasPrefix("d") { d = baseDate.days(n) } else if unit.hasPrefix("w") { d = baseDate.days(7 * n) } else { d = baseDate.months(n) }
            if plausible(d, "follow_up_date", today) {
                labelled.append(Item(key: "follow_up_date", valueText: d.isoString, valueJSON: .null, confidence: 0.7, pos: Pos(page: ln.page, line: ln.index, col: m.start), line: ln, date: d, precision: "day", end: m.end))
            }
        }
        return labelled
    }

    static func bestDocumentDate(_ items: [Item]) -> Item? {
        for key in documentDateOrder {
            let c = items.filter { $0.key == key }.stableSorted { (-$0.confidence, $0.pos) < (-$1.confidence, $1.pos) }
            if let first = c.first { return first }
        }
        return nil
    }

    static func dedup(_ items: [Item], _ keyfn: (Item) -> String) -> [Item] {
        var best: [String: Item] = [:]
        var order: [String] = []
        for it in items.stableSorted(by: { $0.pos < $1.pos }) {
            let k = keyfn(it)
            if best[k] == nil {
                best[k] = it
                order.append(k)
            } else if it.confidence > best[k]!.confidence {
                best[k] = it
            }
        }
        return order.map { best[$0]! }.stableSorted {
            ($0.pos, fieldKeys.firstIndex(of: $0.key) ?? 99) < ($1.pos, fieldKeys.firstIndex(of: $1.key) ?? 99)
        }
    }

    static func extractDates(_ pages: [String], _ recordType: String, _ today: String, _ dateOrder: String) -> [RJ] {
        guard let todayD = YMD.iso(today) else { return [] }
        let linesByPage = pages.enumerated().map { pageLines($1, $0) }
        let items = dedup(dateItems(linesByPage, recordType, todayD, dateOrder)) { "\($0.key)|\($0.date?.isoString ?? "")" }
        var out: [RJ] = items.map { it in
            .obj(["key": .str(it.key), "value_text": .str(it.date!.isoString), "value_json": .obj(["precision": .str(it.precision)]),
                  "confidence": .number(it.confidence), "source_page": .int(it.pos.page), "evidence": .str(it.line.p)])
        }
        if let primary = bestDocumentDate(items) {
            let ln = primary.line
            var tail = ln.f.rSub(primary.end)
            let next = dateCandidates(tail, todayD, dateOrder)
            if let first = next.first { tail = tail.rSub(0, first.start) }
            if let m = reTime.search(tail) {
                var h = Int(m.g(1)!)!
                let mi = Int(m.g(2)!)!
                var ok = true
                if let ap = m.g(3) {
                    if h < 1 || h > 12 { ok = false } else if ap.hasPrefix("a") { h = h == 12 ? 0 : h } else { h = h == 12 ? 12 : h + 12 }
                }
                if ok {
                    out.append(.obj(["key": .str("document_time"), "value_text": .str(String(format: "%02d:%02d", h, mi)), "value_json": .null,
                                     "confidence": .number(0.7), "source_page": .int(ln.page), "evidence": .str(ln.p)]))
                }
            }
        }
        return out
    }

    // MARK: §13 Units and lab rows

    static let unitEnd = Set(" )]},;".utf16)

    static func matchUnit(_ t: String, _ pos: Int) -> (String, Int)? {
        let units16 = Array(t.utf16)
        for (fv, canon) in units where t.rStarts(fv, at: pos) {
            let e = pos + fv.rLen
            if e == units16.count || unitEnd.contains(units16[e]) { return (canon, e) }
        }
        return nil
    }

    static let numPattern = #"(?:[0-9]{1,3}(?:,[0-9]{2,3})+(?:\.[0-9]+)?|[0-9]+(?:\.[0-9]+)?)"#
    static let reValueRange = Rx("([0-9]{1,3})-([0-9]{1,3})")
    static let reValueNum = Rx("(?:(<=|>=|<|>|≤|≥)[ ]?)?(" + numPattern + ")")
    static let reValueQual = Rx("(non[ -]?reactive|not detected|positive|negative|reactive|detected|nil|absent|present|trace|normal|abnormal)(?![a-z])")
    static let reFlag = Rx(#"(critical high|critical low|critical|\(high\)|\(low\)|\(h\)|\(l\)|\[h\]|\[l\]|high|low|hh|ll|h|l|\*|↑↑|↓↓|↑|↓)(?![^ ])"#)
    static let reFlagAttached = Rx(#"(hh|ll|h|l|\*|↑|↓)(?![^ ])"#)
    static let flagMap: [String: String] = [
        "critical high": "critical_high", "hh": "critical_high", "↑↑": "critical_high",
        "critical low": "critical_low", "ll": "critical_low", "↓↓": "critical_low",
        "high": "high", "h": "high", "↑": "high", "(h)": "high", "[h]": "high", "(high)": "high",
        "low": "low", "l": "low", "↓": "low", "(l)": "low", "[l]": "low", "(low)": "low",
        "*": "abnormal", "critical": "critical",
    ]
    static let reRefRange = Rx("(" + numPattern + ")[ ]?(?:-|–|—|to)[ ]?(" + numPattern + ")")
    static let reRefHigh = Rx("(?:<=|≤|<|up ?to|less than|below)[ ]?(" + numPattern + ")")
    static let reRefLow = Rx("(?:>=|≥|>|more than|greater than|above)[ ]?(" + numPattern + ")")
    static let headerWords: Set<String> = ["test", "tests", "parameter", "parameters", "investigation", "investigations", "result",
                                           "results", "unit", "units", "reference", "ref", "range", "interval", "value", "values",
                                           "flag", "method", "observed", "biological", "normal", "description", "name"]
    static let metaFirst: Set<String> = ["age", "sex", "gender", "name", "patient", "pt", "uhid", "mrn", "reg", "regn",
                                         "registration", "lab", "sample", "specimen", "ref", "referred", "bill", "invoice",
                                         "receipt", "date", "time", "page", "phone", "mobile", "mob", "tel", "pin", "pincode",
                                         "report", "collected", "received", "reported", "printed", "dr", "doctor", "consultant",
                                         "visit", "bed", "ward", "ip", "op", "opd", "ipd", "policy", "claim", "member", "batch",
                                         "lot", "dose", "id", "sr", "sl", "no", "plot", "sector", "gst", "gstin", "amount",
                                         "qty", "rate", "barcode", "accession", "order", "client", "location"]
    static let reDigit = Rx("[0-9]")
    static let reAZ = Rx("[a-z]+")
    static let reAZ1 = Rx("[a-z]")

    static func numValue(_ txt: String) -> Double { Double(txt.replacingOccurrences(of: ",", with: "")) ?? 0 }

    static func isHeaderLine(_ f: String) -> Bool {
        if reDigit.search(f) != nil { return false }
        return Set(reAZ.findall(f)).intersection(headerWords).count >= 2
    }

    struct Tail {
        var value: String?
        var valueNum: Double?
        var qualitative = false
        var comparator: String?
        var rangeValue = false
        var flagRaw: String?
        var unit: String?
        var refText: String?
        var refLow: Double?
        var refHigh: Double?
    }

    static func setRef(_ res: inout Tail, _ fm: RM, _ kind: String) {
        switch kind {
        case "range":
            res.refLow = numValue(fm.g(1)!)
            res.refHigh = numValue(fm.g(2)!)
        case "high": res.refHigh = numValue(fm.g(1)!)
        default: res.refLow = numValue(fm.g(1)!)
        }
    }

    static func parseTail(_ t: String, _ tp: String) -> Tail? {
        let n = t.rLen
        let tu = Array(t.utf16)
        var res = Tail()
        func boundaryOK(_ e: Int) -> Bool { e == n || tu[e] == 32 }
        var pos = 0
        if let m = reValueRange.match(t), m.end == n || tu[m.end] == 32, !t.rStarts(".", at: m.end) {
            res.value = tp.rSub(0, m.end)
            res.rangeValue = true
            pos = m.end
        } else if let m = reValueNum.match(t) {
            let e = m.end
            res.value = tp.rSub(0, e)
            res.comparator = m.g(1)
            res.valueNum = numValue(m.g(2)!)
            pos = e
            if !(e == n || [32, 40, 91].contains(tu[e]) || matchUnit(t, e) != nil) {
                guard let am = reFlagAttached.match(t.rSub(e)) else { return nil }
                res.flagRaw = am.g(1)
                pos = e + am.end
            }
        } else if let m = reValueQual.match(t) {
            res.value = tp.rSub(0, m.end)
            res.qualitative = true
            pos = m.end
        } else {
            return nil
        }
        func skip(_ p: Int) -> Int {
            var p = p
            while p < n, tu[p] == 32 { p += 1 }
            return p
        }
        func tryFlag(_ p: Int) -> Int {
            if res.flagRaw == nil, p < n, let fm = reFlag.match(t.rSub(p)) {
                res.flagRaw = fm.g(1)
                return p + fm.end
            }
            return p
        }
        func tryUnit(_ p: Int) -> Int {
            if res.unit == nil, p < n, let u = matchUnit(t, p) {
                res.unit = u.0
                return u.1
            }
            return p
        }
        func tryRef(_ p: Int) -> Int {
            if res.refText != nil || p >= n { return p }
            if tu[p] == 40 || tu[p] == 91 {
                let close = tu[p] == 40 ? ")" : "]"
                let q = t.rFind(close, p + 1)
                if q < 0 { return p }
                let inner = t.rSub(p + 1, q).rStrip(" ")
                if reDigit.search(inner) == nil { return p }
                res.refText = tp.rSub(p, q + 1)
                for (rx, kind) in [(reRefRange, "range"), (reRefHigh, "high"), (reRefLow, "low")] {
                    if let fm = rx.fullmatch(inner) {
                        setRef(&res, fm, kind)
                        break
                    }
                }
                return q + 1
            }
            for (rx, kind) in [(reRefRange, "range"), (reRefHigh, "high"), (reRefLow, "low")] {
                if let fm = rx.match(t.rSub(p)), boundaryOK(p + fm.end) {
                    res.refText = tp.rSub(p, p + fm.end)
                    setRef(&res, fm, kind)
                    return p + fm.end
                }
            }
            return p
        }
        pos = tryFlag(skip(pos))
        pos = tryUnit(skip(pos))
        pos = tryFlag(skip(pos))
        pos = tryRef(skip(pos))
        pos = tryUnit(skip(pos))
        pos = tryFlag(skip(pos))
        let lp = skip(pos)
        let leftover = t.rSub(lp)
        if !leftover.isEmpty {
            if reDigit.search(leftover) != nil || leftover.rSplitSpace.count > 4 { return nil }
            if res.qualitative && res.refText == nil && leftover.rSplitSpace.count <= 3 {
                res.refText = tp.rSub(lp)
            } else if res.unit == nil && res.refText == nil {
                return nil
            }
        }
        return res
    }

    static let reNameTok = Rx("[0-9.,:;/-]+")
    static let reCompare = Rx("[<>=≤≥]")

    static func validName(_ fname: String) -> Bool {
        guard !fname.isEmpty, fname.rLen <= 60, let first = fname.utf16.first, first >= 97, first <= 122 else { return false }
        if reAZ1.findall(fname).count < 2 { return false }
        if fname.rSplitSpace.contains(where: { reNameTok.fullmatch($0) != nil }) { return false }
        if reCompare.search(fname) != nil { return false }
        if let w = reAZ.findall(fname).first, metaFirst.contains(w) { return false }
        if !dateCandidates(fname, YMD(y: 2100, m: 1, d: 1), "dmy").isEmpty { return false }
        return true
    }

    static let qualNegative: Set<String> = ["negative", "nil", "absent", "non-reactive", "nonreactive", "non reactive", "not detected", "normal"]
    static let qualPositive: Set<String> = ["positive", "reactive", "present", "detected", "trace", "abnormal"]

    static func qualFamily(_ f: String) -> String? {
        if qualNegative.contains(f) { return "neg" }
        if qualPositive.contains(f) { return "pos" }
        return nil
    }

    static func resolveFlag(_ res: Tail) -> String {
        let lo = res.refLow, hi = res.refHigh, v = res.valueNum
        let computable = v != nil && res.comparator == nil && !res.rangeValue
        if let raw = res.flagRaw {
            let f = flagMap[raw] ?? "abnormal"
            if f == "abnormal", computable, let lo, v! < lo { return "low" }
            if f == "abnormal", computable, let hi, v! > hi { return "high" }
            if f != "critical" { return f }
            if computable, let lo, v! < lo { return "critical_low" }
            if computable, let hi, v! > hi { return "critical_high" }
            return "abnormal"
        }
        if res.qualitative {
            let fv = fold(res.value ?? "")
            let vf = qualFamily(fv)
            let rf = qualFamily(fold(res.refText ?? "").rStrip(" ()[]"))
            if fv == "abnormal" { return "abnormal" }
            if let vf, let rf { return vf == rf ? "normal" : "abnormal" }
            return "unknown"
        }
        if res.rangeValue, lo != nil || hi != nil {
            let parts = fold(res.value ?? "").split(separator: "-").compactMap { Double($0) }
            guard parts.count == 2 else { return "unknown" }
            let a = parts[0], b = parts[1]
            if let hi, a > hi { return "high" }
            if let lo, b < lo { return "low" }
            if (lo == nil || a >= lo!) && (hi == nil || b <= hi!) { return "normal" }
            return "unknown"
        }
        if computable, lo != nil || hi != nil {
            if let lo, v! < lo { return "low" }
            if let hi, v! > hi { return "high" }
            return "normal"
        }
        return "unknown"
    }

    static let reLead = Rx("(?:[0-9]{1,2}[.)] |[-•*·>] ?)")

    static func parseLabLine(_ ln: Line) -> Item? {
        let f = ln.f, p = ln.p
        if f.isEmpty || isHeaderLine(f) { return nil }
        let off = reLead.match(f)?.end ?? 0
        let fu = Array(f.utf16)
        var candidates: [Int] = []
        if ln.cells.count >= 2 { candidates.append(ln.cells[1].0) }
        if off + 1 < fu.count {
            for i in (off + 1)..<fu.count where fu[i - 1] == 32 && fu[i] != 32 && !candidates.contains(i) {
                candidates.append(i)
            }
        }
        for i in candidates {
            let nameF = f.rSub(off, i).rRStrip(" :.=-–_")
            let nameP = p.rSub(off, off + nameF.rLen)
            if !validName(nameF) { continue }
            guard let res = parseTail(f.rSub(i), p.rSub(i)) else { continue }
            if res.rangeValue && res.unit == nil { continue }
            if res.comparator != nil && res.unit == nil { continue }
            if !res.qualitative && res.unit == nil && res.refText == nil && res.flagRaw == nil && nameF.rSplitSpace.count > 4 { continue }
            let parts = 2 + (res.unit != nil ? 1 : 0) + (res.refText != nil ? 1 : 0)
            let conf: Double = parts == 4 ? 0.9 : parts == 3 ? 0.8 : (res.qualitative ? 0.7 : 0.6)
            let flag = resolveFlag(res)
            let vj: RJ = .obj(["name": .str(nameP), "value": .string(res.value), "value_num": .number(res.valueNum), "unit": .string(res.unit),
                               "ref_text": .string(res.refText), "ref_low": .number(res.refLow), "ref_high": .number(res.refHigh), "flag": .str(flag)])
            return Item(key: "test_result", valueText: nameP, valueJSON: vj, confidence: conf, pos: Pos(page: ln.page, line: ln.index, col: 0), line: ln)
        }
        return nil
    }

    static let reNumberUnit = Rx("(?<![0-9a-z.])" + numPattern + "[ ]?")

    static func countUnitLines(_ linesByPage: [[Line]]) -> Int {
        var n = 0
        for lines in linesByPage {
            for ln in lines where !ln.f.isEmpty {
                var pos = 0
                while let m = reNumberUnit.search(ln.f, pos) {
                    if matchUnit(ln.f, m.end) != nil {
                        n += 1
                        break
                    }
                    pos = m.start + 1
                }
            }
        }
        return n
    }

    static func parseLabRows(_ pages: [String], _ recordType: String) -> [RJ] {
        let linesByPage = pages.enumerated().map { pageLines($1, $0) }
        if recordType != "lab_report", recordType != "diagnostic_report", countUnitLines(linesByPage) < 3 { return [] }
        let out = linesByPage.flatMap { $0.compactMap(parseLabLine) }
        return dedup(out) { normalizeValue("test_result", $0.valueText, $0.valueJSON) }.map(\.publicJSON)
    }
}
