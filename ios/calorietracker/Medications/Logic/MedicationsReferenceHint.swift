import Foundation

// §13 Frequency hint from the Records pipeline's medication field value_json
// (port of the reference; see MedicationsReferenceCore.swift).

nonisolated extension MR {
    private static let formMap: [(String, String)] = [
        ("tablet", "tablet"), ("tab", "tablet"), ("capsule", "capsule"), ("cap", "capsule"),
        ("syrup", "syrup"), ("syp", "syrup"), ("syr", "syrup"), ("suspension", "syrup"), ("susp", "syrup"),
        ("solution", "syrup"), ("soln", "syrup"), ("injection", "injection"), ("inj", "injection"),
        ("ointment", "cream"), ("oint", "cream"), ("cream", "cream"), ("gel", "cream"), ("lotion", "cream"),
        ("drops", "drops"), ("drop", "drops"), ("inhaler", "inhaler"), ("nebulisation", "inhaler"),
        ("nebulization", "inhaler"), ("neb", "inhaler"), ("respules", "inhaler"), ("respule", "inhaler"),
        ("sachet", "other"), ("powder", "other"), ("spray", "other"),
    ]
    private static let reTriplet = Rx("^([0-9]+(?:/[0-9]+)?|[0-9]+\\.[0-9]+)-([0-9]+(?:/[0-9]+)?|[0-9]+\\.[0-9]+)-"
                                      + "([0-9]+(?:/[0-9]+)?|[0-9]+\\.[0-9]+)(?:-([0-9]+(?:/[0-9]+)?|[0-9]+\\.[0-9]+))?$")
    private static let reEveryH = Rx("(?:^|[^a-z0-9])(?:every|each) ([0-9]{1,2}) ?(?:hours|hour|hrs|hr|hourly|h)(?:$|[^a-z0-9])")
    private static let reQNH = Rx("(?:^|[^a-z0-9])q([0-9]{1,2}) ?h(?:$|[^a-z0-9])")
    private static let reNHourly = Rx("(?:^|[^a-z0-9])([0-9]{1,2}) ?hourly(?:$|[^a-z0-9])")
    private static let reDuration = Rx("(?:^|[^a-z0-9])(?:x|for)? ?([0-9]{1,3}) ?(days|day|d|weeks|week|wks|wk|w|months|month|mths|mth|m)"
                                       + "(?:$|[^a-z0-9])")
    private static let reDose = Rx("(?:^|[^a-z0-9])([0-9]+(?:\\.[0-9]+)?|1/2) ?(tablets|tablet|tabs|tab|capsules|capsule|caps|cap|"
                                   + "puffs|puff|drops|drop|sachets|sachet|teaspoons|teaspoon|tsp|ml)(?:$|[^a-z0-9])")
    private static let reBefore = Rx("before (?:food|meals|meal|breakfast|lunch|dinner|eating)")
    private static let reAfter = Rx("after (?:food|meals|meal|breakfast|lunch|dinner|eating)")
    private static let reWith = Rx("with (?:food|meals|meal|milk|water)")

    private static let once = ["once daily", "once a day", "one time a day", "every day", "everyday", "daily", "od", "0d", "qd", "1 time a day"]
    private static let twice = ["twice daily", "twice a day", "two times a day", "two times daily", "2 times a day", "bd", "bid"]
    private static let thrice = ["thrice daily", "thrice a day", "three times a day", "three times daily", "3 times a day", "tds", "tid"]
    private static let four = ["four times a day", "four times daily", "4 times a day", "qid", "qds"]
    private static let weekly = ["once weekly", "once a week", "weekly", "every week"]
    private static let prn = ["sos", "prn", "if needed", "if required", "when required", "when needed", "as needed", "as required",
                              "as directed when needed"]
    private static let night = ["hs", "qhs", "at night", "at bedtime", "nocte", "bedtime", "nightly"]
    private static let stat = ["stat"]

    /// Lowercased, `×` → `x`, `½` → `1/2`, whitespace collapsed.
    static func norm(_ v: RJ) -> String {
        guard let s = v.string else { return "" }
        let t = s.replacingOccurrences(of: "×", with: "x").replacingOccurrences(of: "½", with: "1/2").lowercased()
        return t.replacingOccurrences(of: "\t", with: " ").split(whereSeparator: { $0.isWhitespace }).joined(separator: " ")
    }

    private static let wordChars = Set("abcdefghijklmnopqrstuvwxyz0123456789/".utf16)

    /// Whole-word phrase match on normalized text (word chars = `[a-z0-9/]`).
    static func hasPhrase(_ text: String, _ phrase: String) -> Bool {
        var start = 0
        let units = Array(text.utf16)
        let needle = Array(phrase.utf16)
        while true {
            let i = text.rFind(phrase, start)
            if i < 0 { return false }
            let before: UInt16? = i > 0 ? units[i - 1] : nil
            let afterIndex = i + needle.count
            let after: UInt16? = afterIndex < units.count ? units[afterIndex] : nil
            let beforeOK = before.map { !wordChars.contains($0) } ?? true
            let afterOK = after.map { !wordChars.contains($0) } ?? true
            if beforeOK && afterOK { return true }
            start = i + 1
        }
    }

    private static func anyPhrase(_ text: String, _ phrases: [String]) -> Bool {
        phrases.contains { hasPhrase(text, $0) }
    }

    private static func num(_ tok: String) -> Double {
        if tok.contains("/") {
            let parts = tok.split(separator: "/", maxSplits: 1, omittingEmptySubsequences: false).map(String.init)
            guard parts.count == 2, let a = Double(parts[0]), let b = Double(parts[1]) else { return 0 }
            return b != 0 ? a / b : 0
        }
        return Double(tok) ?? 0
    }

    private static func mapForm(_ formText: RJ) -> (String?, Bool) {
        let f = norm(formText).rRStrip(".")
        if f.isEmpty { return (nil, false) }
        for (word, canon) in formMap where f == word || f.hasPrefix(word) {
            return (canon, canon != word)
        }
        return ("other", true)
    }

    /// Prefilled Add-medication draft from a Records `medication` field value_json
    /// `{name, strength, form, dose, frequency, duration, instructions}`.
    static func frequencyHint(_ valueJSON: RJ) -> RJ {
        let v = valueJSON.isNull ? RJ.obj([:]) : valueJSON
        var notes: [String] = []
        let name = strip(v["name"].string ?? "")
        let strengthStripped = strip(v["strength"].string ?? "")
        let strength: RJ = strengthStripped.isEmpty ? .null : .str(strengthStripped)
        let instructionsStripped = strip(v["instructions"].string ?? "")
        let instructionsRaw: RJ = instructionsStripped.isEmpty ? .null : .str(instructionsStripped)
        var (formOpt, mapped) = mapForm(v["form"])
        let form: String
        if let f = formOpt {
            form = f
            if mapped { notes.append("form_mapped") }
        } else {
            form = "other"
            notes.append("form_defaulted")
        }
        formOpt = form
        let freq = norm(v["frequency"])
        let instr = norm(v["instructions"])
        let dur = norm(v["duration"])
        let doseText = norm(v["dose"])

        var isPRN = false
        var kind: String?
        var times: [String] = []
        var days: [Int] = []
        var interval: Int?
        var anchor: String?
        var quantity: Double?
        var explicit = false
        let combined = strip(freq + " " + instr)

        if let m = reTriplet.match(freq) {
            let parts = (1...4).compactMap { m.g($0) }
            let nums = parts.map(num)
            let table = nums.count == 4 ? slots4 : slots3
            times = nums.enumerated().filter { $0.element > 0 }.map { table[$0.offset] }
            let nonzero = nums.filter { $0 > 0 }
            if !nonzero.isEmpty {
                explicit = true
                kind = "daily"
                quantity = nonzero[0]
                if Set(nonzero).count != 1 { notes.append("uneven_doses") }
            }
        }
        if !explicit {
            if anyPhrase(combined, prn) {
                isPRN = true
                explicit = true
            } else if anyPhrase(freq, four) {
                kind = "daily"; times = slots4; explicit = true
            } else if anyPhrase(freq, thrice) {
                kind = "daily"; times = slots3; explicit = true
            } else if anyPhrase(freq, twice) {
                kind = "daily"; times = slots2; explicit = true
            } else if let mm = reEveryH.search(freq) ?? reQNH.search(freq) ?? reNHourly.search(freq) {
                let n = Int(mm.g(1) ?? "") ?? 0
                explicit = true
                if intervalHours.contains(n) {
                    kind = "interval"; interval = n; anchor = intervalAnchor
                } else {
                    kind = "daily"; times = slots3
                    notes.append("interval_rounded")
                }
            } else if anyPhrase(freq, weekly) {
                kind = "weekly"; days = weeklyDefaultDays; times = slots1; explicit = true
                notes.append("weekday_defaulted")
            } else if anyPhrase(freq, night) {
                kind = "daily"; times = slotNight; explicit = true
            } else if anyPhrase(freq, once) {
                kind = "daily"; times = slots1; explicit = true
            } else if anyPhrase(freq, stat) {
                kind = "daily"; times = slots1; explicit = true
            }
        }
        var durationDays: Int?
        if anyPhrase(freq, stat) && !isPRN { durationDays = 1 }
        if !explicit {
            kind = "daily"; times = slots1
            notes.append("frequency_defaulted")
        }
        if kind == "daily", times.count == 1, !anyPhrase(freq, night), anyPhrase(instr, night) {
            times = slotNight
        }
        if !isPRN && kind != "interval" { notes.append("time_defaulted") }

        if let md = reDuration.search(dur), durationDays == nil, let n = Int(md.g(1) ?? ""), let unit = md.g(2), let first = unit.first {
            if first == "d" {
                durationDays = n
            } else if first == "w" {
                durationDays = n * 7
            } else {
                durationDays = n * 30
            }
        }

        var doseUnit: String?
        let mdose = reDose.search(doseText) ?? (form == "syrup" ? reDose.search(norm(v["strength"])) : nil)
        if let mdose {
            quantity = num(mdose.g(1) ?? "")
            let u = mdose.g(2) ?? ""
            if u.hasPrefix("tab") {
                doseUnit = "tablet"
            } else if u.hasPrefix("cap") {
                doseUnit = "capsule"
            } else if u.hasPrefix("puff") {
                doseUnit = "puff"
            } else if u.hasPrefix("drop") {
                doseUnit = "drop"
            } else if u.hasPrefix("sachet") {
                doseUnit = "sachet"
            } else if u == "tsp" || u == "teaspoon" || u == "teaspoons" {
                doseUnit = "ml"
                quantity = (quantity ?? 0) * 5
            } else {
                doseUnit = "ml"
            }
        }
        if doseUnit == nil { doseUnit = defaultUnitForForm[form] ?? "unit" }
        if quantity == nil {
            if form == "syrup" {
                notes.append("dose_required")
            } else {
                quantity = 1
                notes.append("dose_defaulted")
            }
        }

        var food = "anytime"
        if hasPhrase(instr, "empty stomach") || reBefore.search(instr) != nil {
            food = "before"
        } else if reAfter.search(instr) != nil {
            food = "after"
        } else if reWith.search(instr) != nil {
            food = "with"
        }

        let confidence: Double
        if isPRN {
            confidence = 0.9
        } else if !explicit {
            confidence = 0.3
        } else if ["interval_rounded", "weekday_defaulted", "uneven_doses"].contains(where: { notes.contains($0) }) {
            confidence = 0.6
        } else {
            confidence = 0.9
        }
        return .obj([
            "name": .str(name), "strength": strength, "form": .str(form), "dose_quantity": RJ.number(quantity),
            "dose_unit": .str(doseUnit ?? "unit"), "is_prn": .bool(isPRN),
            "frequency_kind": isPRN ? .null : RJ.string(kind),
            "times": .arr(isPRN ? [] : times.map(RJ.str)), "days": .arr(isPRN ? [] : days.map(RJ.int)),
            "interval_hours": isPRN ? .null : (interval.map(RJ.int) ?? .null),
            "anchor_time": isPRN ? .null : RJ.string(anchor),
            "duration_days": durationDays.map(RJ.int) ?? .null, "food_relation": .str(food), "instructions": instructionsRaw,
            "confidence": .num(confidence), "notes": .arr(notes.map(RJ.str)),
        ])
    }
}
