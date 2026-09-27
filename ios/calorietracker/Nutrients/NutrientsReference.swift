import Foundation

// Swift port of `scripts/nutrients_reference.py` (docs/nutrients.md). The reference wins over prose;
// every function follows it line by line so `NutrientsVectorTests` runs the shared cases unchanged.
// Rounding is round_to(x, d) = floor(x·10^d + 0.5) / 10^d (never −0); sums run in input order and are
// rounded once, at the output; instants are epoch ms and days "yyyy-MM-dd" in an IANA zone.

nonisolated enum NutrientsReference {
    static let amountDecimals = 6
    static let limitDecimals = 1
    static let sexes = ["male", "female"]
    /// mcg per unit.
    static let massFactors: [String: Double] = ["g": 1_000_000, "mg": 1_000, "mcg": 1]
    static let labelRecommended = "Recommended"
    static let labelLimit = "Limit"
    static let labelGoal = "Your goal"

    static let data = NutrientReferenceData.shared
    static let byKey: [String: NutrientReferenceData.Nutrient] = data.byKey
    static let sportsByKey: [String: NutrientReferenceData.Sports] = data.sportsByKey
    /// `app_tracked` reference nutrients by key (docs/nutrients.md §3). The others exist only for the health
    /// nutrition types and feed `referenceLines` / `defaultGoal`.
    static let trackedByKey: [String: NutrientReferenceData.Nutrient] = byKey.filter { $0.value.appTracked }

    /// Canonical unit of an app-tracked reference nutrient or a sports supplement; nil for anything else
    /// (unknown keys and `app_tracked: false` nutrients, which cannot be supplement nutrients).
    static func nutrientUnit(_ key: String) -> String? {
        if let n = trackedByKey[key] { return n.unit }
        return sportsByKey[key]?.unit
    }

    /// True when `key` is an app-tracked reference nutrient or a sports supplement.
    static func isAppNutrient(_ key: String) -> Bool { nutrientUnit(key) != nil }

    /// App-tracked reference keys (display order) followed by the sports supplement keys.
    static var allKeys: [String] { data.trackedNutrients.map(\.key) + data.sportsSupplements.map(\.key) }

    // MARK: - Numbers and days

    static func roundOptional(_ x: Double?, _ decimals: Int) -> Double? {
        guard let x else { return nil }
        let scale = pow(10.0, Double(decimals))
        let v = floor(x * scale + 0.5) / scale
        return v == 0 ? 0 : v
    }

    static func roundTo(_ x: Double, _ decimals: Int) -> Double {
        let scale = pow(10.0, Double(decimals))
        let v = floor(x * scale + 0.5) / scale
        return v == 0 ? 0 : v
    }

    static func roundHalfUpInt(_ x: Double) -> Int { Int(floor(x + 0.5)) }

    static func zone(_ identifier: String) -> MetricsReference.Zone { MetricsReference.Zone(identifier) }

    static func localDayOf(_ ms: Int64, zone: MetricsReference.Zone) -> String {
        zone.day(of: ms).text
    }

    static func localMidnightMs(_ day: String, zone: MetricsReference.Zone) -> Int64? {
        MetricsReference.LocalDay.parse(day).map { zone.midnight($0) }
    }

    // MARK: - Profile → band, reference lines, default goals

    nonisolated struct Profile: Equatable, Sendable {
        var age: Double?
        /// "male" | "female" | anything else (unknown).
        var sex: String?
        var calorieGoal: Double?

        static let unknown = Profile(age: nil, sex: nil, calorieGoal: nil)
    }

    /// No age or under 19 → default band (31-50); otherwise the band containing floor(age).
    static func bandForAge(_ age: Double?) -> String {
        guard let age, age.isFinite else { return data.defaultBand }
        let a = Int(floor(age))
        if a < 19 { return data.defaultBand }
        for band in data.ageBands where a >= band.min && (band.max == nil || a <= band.max!) {
            return band.id
        }
        return data.defaultBand
    }

    static func sex(_ profile: Profile) -> String? {
        guard let s = profile.sex, sexes.contains(s) else { return nil }
        return s
    }

    /// Unknown sex: the higher (recommended) or the lower (upper limit) of the two sexes.
    private static func bySex(male: [String: Double], female: [String: Double], band: String, sex: String?, pickHigh: Bool) -> Double? {
        if let sex {
            return (sex == "male" ? male : female)[band]
        }
        guard let m = male[band], let f = female[band] else { return nil }
        if pickHigh { return m >= f ? m : f }
        return m <= f ? m : f
    }

    private static func limitValue(_ limit: NutrientReferenceData.Limit?, calorieGoal: Double?) -> Double? {
        guard let limit else { return nil }
        switch limit.kind {
        case "fixed":
            return limit.value
        case "pct_energy":
            guard let calorieGoal, calorieGoal.isFinite, calorieGoal > 0, let pct = limit.pct, let kcal = limit.kcalPerUnit else { return nil }
            return roundTo(calorieGoal * pct / 100.0 / kcal, limitDecimals)
        default:
            return nil
        }
    }

    nonisolated struct Lines: Equatable, Sendable {
        var key: String
        var band: String
        var sex: String?
        var style: String?
        var unit: String?
        var recommended: Double?
        var upperLimit: Double?
        var limit: Double?
        var recommendedLabel = NutrientsReference.labelRecommended
        var limitLabel = NutrientsReference.labelLimit
        var upperLimitScope: String?
        var referenceRecommended: Double?
        var referenceLimit: Double?
        var recommendedKind: String?
        var sourceIDs: [String] = []
        var error: String?
    }

    /// Chart lines for one nutrient. A custom goal (a number > 0) replaces the Recommended line (target
    /// style and sports supplements) or the Limit line (limit and info styles) and is labelled "Your goal".
    static func referenceLines(key: String, profile: Profile, customGoal: Double? = nil) -> Lines {
        let band = bandForAge(profile.age)
        let sex = sex(profile)
        let goal: Double? = customGoal.flatMap { $0.isFinite && $0 > 0 ? $0 : nil }
        var base = Lines(key: key, band: band, sex: sex)
        if let sports = sportsByKey[key] {
            base.style = "target"
            base.unit = sports.unit
            if let goal {
                base.recommended = goal
                base.recommendedLabel = labelGoal
            }
            return base
        }
        guard let n = byKey[key] else {
            base.error = "unknown_nutrient"
            return base
        }
        let rec = n.recommended.flatMap { bySex(male: $0.male, female: $0.female, band: band, sex: sex, pickHigh: true) }
        let ul = n.upperLimit.flatMap { bySex(male: $0.male, female: $0.female, band: band, sex: sex, pickHigh: false) }
        let lim = limitValue(n.limit, calorieGoal: profile.calorieGoal)
        base.style = n.style
        base.unit = n.unit
        base.recommended = rec
        base.upperLimit = ul
        base.limit = lim
        base.upperLimitScope = n.upperLimit?.scope
        base.referenceRecommended = rec
        base.referenceLimit = lim
        base.recommendedKind = n.recommended?.kind
        base.sourceIDs = n.sourceIDs
        if let goal {
            if n.style == "target" {
                base.recommended = goal
                base.recommendedLabel = labelGoal
            } else {
                base.limit = goal
                base.limitLabel = labelGoal
            }
        }
        return base
    }

    /// Recommended (target style) or Limit (limit style); nil for info style, sports and unknown keys.
    static func defaultGoal(key: String, profile: Profile) -> Double? {
        guard let n = byKey[key], n.style != "info" else { return nil }
        let r = referenceLines(key: key, profile: profile)
        return n.style == "target" ? r.recommended : r.limit
    }

    /// Integer goal: round half up, never below 1 when the exact value is > 0. Nil stays nil.
    static func defaultGoalInt(_ value: Double?) -> Int? {
        guard let value else { return nil }
        let i = roundHalfUpInt(value)
        return value > 0 && i < 1 ? 1 : i
    }

    // MARK: - Unit conversion

    /// "g" | "mg" | "mcg" | "iu" | nil. Trimmed and lower-cased; ug / µg / μg → mcg.
    static func normalizeUnit(_ unit: String?) -> String? {
        guard let unit else { return nil }
        var u = unit.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        u = data.unitAliases[u] ?? u
        if massFactors[u] != nil || u == "iu" { return u }
        return nil
    }

    nonisolated struct Conversion: Equatable, Sendable {
        var ok: Bool
        var amount: Double?
        var unit: String?
        var error: String?

        static func fail(_ code: String) -> Conversion { Conversion(ok: false, amount: nil, unit: nil, error: code) }
    }

    /// Amount in the nutrient's canonical unit (rounded to 6 decimals). `value` nil = not a finite number.
    static func convertAmount(_ value: Double?, unit: String?, key: String, form: String? = nil) -> Conversion {
        guard let unitC = nutrientUnit(key) else { return .fail("unknown_nutrient") }
        guard let value, value.isFinite, value > 0 else { return .fail("invalid_amount") }
        guard let u = normalizeUnit(unit) else { return .fail("unsupported_unit") }
        let n = byKey[key]
        var amount: Double
        if u == "iu" {
            guard let iu = n?.iu else { return .fail("iu_not_supported") }
            if let forms = iu.forms {
                guard let form, !form.isEmpty else { return .fail("form_required") }
                guard let factor = forms[form] else { return .fail("unknown_form") }
                amount = value * factor
            } else {
                amount = value * (iu.mcgPerIU ?? 0)
            }
            amount = amount * (massFactors[iu.unit] ?? 1) / (massFactors[unitC] ?? 1)
        } else {
            amount = value * (massFactors[u] ?? 1) / (massFactors[unitC] ?? 1)
            if let form, let factor = n?.massForms?[form] {
                amount = amount / factor
            }
        }
        return Conversion(ok: true, amount: roundTo(amount, amountDecimals), unit: unitC, error: nil)
    }

    // MARK: - Supplement contributions and day totals

    nonisolated struct MedicationNutrientInput: Equatable, Sendable {
        var medicationID: String
        var nutrientKey: String
        var amountPerUnit: Double
    }

    nonisolated struct DoseInput: Equatable, Sendable {
        var medicationID: String
        var status: String
        var takenAtMs: Int64?
        var doseQuantity: Double?
    }

    nonisolated struct SupplementEntry: Equatable, Hashable, Sendable {
        var tMs: Int64
        var nutrientKey: String
        var value: Double
        var medicationID: String
    }

    /// One entry per nutrient of the medication for every TAKEN dose with a taken_at_ms:
    /// value = dose_quantity (nil → 1) × amount_per_unit. Sorted by (t_ms, nutrient_key, medication_id).
    static func supplementEntries(nutrients: [MedicationNutrientInput], doseLogs: [DoseInput]) -> [SupplementEntry] {
        var perMed: [String: [MedicationNutrientInput]] = [:]
        for row in nutrients { perMed[row.medicationID, default: []].append(row) }
        var out: [SupplementEntry] = []
        for log in doseLogs {
            guard log.status == "taken", let taken = log.takenAtMs else { continue }
            let q = log.doseQuantity ?? 1
            for row in perMed[log.medicationID] ?? [] {
                out.append(SupplementEntry(tMs: taken, nutrientKey: row.nutrientKey,
                                           value: roundTo(q * row.amountPerUnit, amountDecimals), medicationID: log.medicationID))
            }
        }
        return out.enumerated().sorted { a, b in
            let l = a.element, r = b.element
            if l.tMs != r.tMs { return l.tMs < r.tMs }
            if l.nutrientKey != r.nutrientKey { return l.nutrientKey < r.nutrientKey }
            if l.medicationID != r.medicationID { return l.medicationID < r.medicationID }
            return a.offset < b.offset
        }.map(\.element)
    }

    nonisolated struct FoodInput: Equatable, Sendable {
        var tMs: Int64
        /// Key → value (nil = named but no value).
        var nutrients: [String: Double?]
    }

    nonisolated struct DayTotal: Equatable, Sendable {
        var food: Double?
        var supplements: Double?
        var total: Double?

        static let empty = DayTotal(food: nil, supplements: nil, total: nil)
    }

    /// {key: {food, supplements, total}} for the local `day`. A part with no values is nil; total is nil only
    /// when both parts are nil. Sums in input order, rounded to 6 decimals at the end.
    static func dayTotals(food: [FoodInput], supplements: [SupplementEntry], day: String, zone: MetricsReference.Zone) -> [String: DayTotal] {
        var acc: [String: (Double?, Double?)] = [:]
        for e in food where localDayOf(e.tMs, zone: zone) == day {
            for (k, v) in e.nutrients {
                var a = acc[k] ?? (nil, nil)
                if let v, v.isFinite { a.0 = a.0.map { $0 + v } ?? v }
                acc[k] = a
            }
        }
        for s in supplements where localDayOf(s.tMs, zone: zone) == day {
            var a = acc[s.nutrientKey] ?? (nil, nil)
            if s.value.isFinite { a.1 = a.1.map { $0 + s.value } ?? s.value }
            acc[s.nutrientKey] = a
        }
        var out: [String: DayTotal] = [:]
        for (k, parts) in acc {
            let (f, p) = parts
            let total: Double? = (f == nil && p == nil) ? nil : (f ?? 0) + (p ?? 0)
            out[k] = DayTotal(food: roundOptional(f, amountDecimals), supplements: roundOptional(p, amountDecimals), total: roundOptional(total, amountDecimals))
        }
        return out
    }

    nonisolated struct LoggedDayAverage: Equatable, Sendable {
        var average: Double?
        var loggedDays: Int
    }

    /// Average per logged day over [startMs, endMs). `entries` are ONE nutrient's food and supplement entries;
    /// `loggedDays` the caller's days with any food entry or taken dose (counted when their local midnight is
    /// inside the interval), plus the day of every non-null entry inside the interval.
    static func loggedDayAverage(entries: [MetricsReference.Entry], loggedDays: [String], startMs: Int64, endMs: Int64,
                                 zone: MetricsReference.Zone) -> LoggedDayAverage {
        var days = Set<String>()
        for d in loggedDays {
            guard let m = localMidnightMs(d, zone: zone) else { continue }
            if startMs <= m && m < endMs { days.insert(d) }
        }
        var total = 0.0
        var seen = false
        for e in entries {
            guard startMs <= e.tMs, e.tMs < endMs, let value = e.value, value.isFinite else { continue }
            total += value
            seen = true
            days.insert(localDayOf(e.tMs, zone: zone))
        }
        let n = days.count
        guard seen, n > 0 else { return LoggedDayAverage(average: nil, loggedDays: n) }
        return LoggedDayAverage(average: roundTo(total / Double(n), amountDecimals), loggedDays: n)
    }

    // MARK: - AI supplement label output

    /// First balanced {...} of the text (string and escape aware) after stripping a ``` fence; nil if absent.
    static func extractJSONObject(_ text: String) -> String? {
        var t = Array(text.trimmingCharacters(in: .whitespacesAndNewlines).unicodeScalars)
        let fence = Array("```".unicodeScalars)
        func hasPrefix(_ a: [Unicode.Scalar], _ p: [Unicode.Scalar]) -> Bool { a.count >= p.count && Array(a[0..<p.count]) == p }
        func rstrip(_ a: [Unicode.Scalar]) -> [Unicode.Scalar] {
            var end = a.count
            while end > 0, CharacterSet.whitespacesAndNewlines.contains(a[end - 1]) { end -= 1 }
            return Array(a[0..<end])
        }
        if hasPrefix(t, fence) {
            if let nl = t.firstIndex(of: "\n") {
                t = Array(t[(nl + 1)...])
            } else {
                t = []
            }
            let stripped = rstrip(t)
            if stripped.count >= 3, Array(stripped[(stripped.count - 3)...]) == fence {
                t = Array(stripped[0..<(stripped.count - 3)])
            }
        }
        guard let start = t.firstIndex(of: "{") else { return nil }
        var depth = 0
        var inString = false
        var escaped = false
        for i in start..<t.count {
            let c = t[i]
            if inString {
                if escaped {
                    escaped = false
                } else if c == "\\" {
                    escaped = true
                } else if c == "\"" {
                    inString = false
                }
            } else if c == "\"" {
                inString = true
            } else if c == "{" {
                depth += 1
            } else if c == "}" {
                depth -= 1
                if depth == 0 {
                    var s = String.UnicodeScalarView()
                    s.append(contentsOf: t[start...i])
                    return String(s)
                }
            }
        }
        return nil
    }

    nonisolated struct LabelItem: Equatable, Hashable, Sendable {
        var key: String
        var amount: Double
        var unit: String
        var form: String?
    }

    nonisolated struct LabelRejection: Equatable, Sendable {
        var index: Int
        var code: String
    }

    nonisolated struct LabelResult: Equatable, Sendable {
        var ok: Bool
        var error: String?
        /// The raw `serving_units` (1 when missing); nil when parsing failed or it was invalid.
        var servingUnits: RJ?
        var items: [LabelItem]
        var rejected: [LabelRejection]

        static func == (lhs: LabelResult, rhs: LabelResult) -> Bool {
            lhs.ok == rhs.ok && lhs.error == rhs.error && lhs.items == rhs.items && lhs.rejected == rhs.rejected
                && RJ.same(lhs.servingUnits ?? .null, rhs.servingUnits ?? .null)
        }
    }

    private static func finiteNumber(_ v: RJ) -> Double? {
        switch v {
        case .int(let i): return Double(i)
        case .num(let d): return d.isFinite ? d : nil
        default: return nil
        }
    }

    /// Validates a model answer for the supplement-label prompt. Nothing here is saved.
    static func parseLabelOutput(_ text: String) -> LabelResult {
        guard let raw = extractJSONObject(text), let doc = RJ.parse(raw) else {
            return LabelResult(ok: false, error: "parse_error", servingUnits: nil, items: [], rejected: [])
        }
        guard doc.object != nil, let rawItems = doc["items"].array else {
            return LabelResult(ok: false, error: "bad_shape", servingUnits: nil, items: [], rejected: [])
        }
        var serving = doc["serving_units"]
        if serving.isNull { serving = .int(1) }
        guard let servingValue = finiteNumber(serving), servingValue > 0 else {
            return LabelResult(ok: true, error: nil, servingUnits: nil, items: [],
                               rejected: rawItems.indices.map { LabelRejection(index: $0, code: "bad_serving") })
        }
        var items: [LabelItem] = []
        var rejected: [LabelRejection] = []
        var seen = Set<String>()
        for (i, it) in rawItems.enumerated() {
            guard it.object != nil, let rawKey = it["key"].string else {
                rejected.append(LabelRejection(index: i, code: "bad_item"))
                continue
            }
            let formValue = it["form"]
            if !formValue.isNull, formValue.string == nil {
                rejected.append(LabelRejection(index: i, code: "bad_item"))
                continue
            }
            let form = formValue.string
            let key = rawKey.trimmingCharacters(in: .whitespacesAndNewlines)
            guard nutrientUnit(key) != nil else {
                rejected.append(LabelRejection(index: i, code: "unknown_nutrient"))
                continue
            }
            if seen.contains(key) {
                rejected.append(LabelRejection(index: i, code: "duplicate_nutrient"))
                continue
            }
            let c = convertAmount(finiteNumber(it["amount"]), unit: it["unit"].string, key: key, form: form)
            guard c.ok, let converted = c.amount, let unit = c.unit else {
                rejected.append(LabelRejection(index: i, code: c.error ?? "invalid_amount"))
                continue
            }
            let amount = roundTo(converted / servingValue, amountDecimals)
            if amount <= 0 {
                rejected.append(LabelRejection(index: i, code: "invalid_amount"))
                continue
            }
            if amount > (data.amountPerUnitMax[unit] ?? .infinity) {
                rejected.append(LabelRejection(index: i, code: "amount_too_large"))
                continue
            }
            seen.insert(key)
            items.append(LabelItem(key: key, amount: amount, unit: unit, form: form))
        }
        return LabelResult(ok: true, error: nil, servingUnits: serving, items: items, rejected: rejected)
    }

    nonisolated struct Prompts: Equatable, Sendable {
        var cloud: String
        var local: String
        var userPhoto: String
        var userText: String
    }

    /// {cloud, local, user_photo, user_text} from the fenced blocks under the matching headings.
    static func loadPrompts(_ markdown: String) -> Prompts? {
        func block(_ heading: String) -> String? {
            guard let h = markdown.range(of: heading),
                  let open = markdown.range(of: "```\n", range: h.upperBound..<markdown.endIndex),
                  let close = markdown.range(of: "\n```", range: open.upperBound..<markdown.endIndex) else { return nil }
            return String(markdown[open.upperBound..<close.lowerBound])
        }
        guard let cloud = block("## System prompt (cloud)"),
              let photo = block("## User template (label photo)"),
              let text = block("## User template (name and strength)"),
              let local = block("## System prompt (compact, on-device)") else { return nil }
        return Prompts(cloud: cloud, local: local, userPhoto: photo, userText: text)
    }

    static let bundledPrompts: Prompts? = {
        final class Marker {}
        for bundle in [Bundle.main, Bundle(for: Marker.self)] {
            if let url = bundle.url(forResource: "ai_supplement_label", withExtension: "md"),
               let text = try? String(contentsOf: url, encoding: .utf8) {
                return loadPrompts(text)
            }
        }
        return nil
    }()
}
