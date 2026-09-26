import Foundation

// The optional "Explain with AI" step: the derived-values payload, canonical JSON, prompt assembly from
// `ai_explain.md` and the output validator. Port of the "AI" section of `scripts/insights_reference.py`.

nonisolated enum InsightsAI {
    struct Prompts: Sendable {
        let cloud: String
        let local: String
        let user: String
    }

    // MARK: Payload

    /// Only derived values: no raw samples, timestamps, dates or names.
    static func payload(_ summary: InsightsSummary) -> RJ {
        var out: [String: RJ] = [:]
        if let r = summary.recovery {
            if r.status == "ok" {
                out["recovery"] = .obj([
                    "status": .str("ok"), "score": RJ.number(r.score.map(Double.init)), "label": RJ.string(r.labelText),
                    "confidence": RJ.string(r.confidence), "training_load": RJ.string(r.load?.label),
                    "signals": .arr((r.positives + r.negatives).map { .obj(["text": .str($0.text), "impact": RJ.number($0.impact)]) }),
                ])
            } else {
                out["recovery"] = .obj(["status": .str(r.status), "collecting": collecting(r.collecting)])
            }
        }
        if let h = summary.healthAge {
            if h.status == "ok" {
                var sec: [String: RJ] = [
                    "status": .str("ok"), "actual_age": RJ.number(h.actualAge), "health_age": RJ.number(h.healthAge),
                    "difference": RJ.number(h.difference), "confidence": RJ.string(h.confidence),
                    "markers": .arr(h.markers.filter(\.available).map {
                        .obj(["id": .str($0.id), "offset_years": RJ.number($0.offsetYears),
                              "contribution_years": RJ.number($0.contributionYears)])
                    }),
                ]
                if let p = summary.healthAgePace, p.status == "ok" {
                    sec["pace"] = RJ.number(p.pace)
                    sec["pace_direction"] = RJ.string(p.direction)
                }
                out["health_age"] = .obj(sec)
            } else {
                out["health_age"] = .obj(["status": .str(h.status), "collecting": collecting(h.collecting)])
            }
        }
        if let v = summary.dailyReview {
            var sec: [String: RJ] = [
                "day_score": RJ.number(v.dayScore.map(Double.init)),
                "areas": .arr(v.areas.filter(\.included).map { .obj(["id": .str($0.id), "score": RJ.number($0.score.map(Double.init))]) }),
                "not_logged": .arr(v.notLogged.map { RJ.string($0.params["area"]?.text) }),
            ]
            for c in DailyReviewEngine.categories {
                sec[c] = .arr(v.items(c).map { .str($0.text) })
            }
            out["daily_review"] = .obj(sec)
        }
        out["patterns"] = .arr((summary.patterns ?? []).filter(\.surfaced).map {
            .obj(["id": .str($0.id), "text": RJ.string($0.text), "n_exposed": .int($0.nExposed),
                  "n_unexposed": .int($0.nUnexposed)])
        })
        return .obj(out)
    }

    private static func collecting(_ c: InsightsCollecting?) -> RJ {
        guard let c else { return .null }
        return .obj(["have": .int(c.have), "need": .int(c.need)])
    }

    // MARK: Canonical JSON

    /// Sorted keys, no whitespace, integral numbers as integers, others with at most 2 decimals.
    static func canonicalJSON(_ value: RJ) -> String {
        switch value {
        case .null: return "null"
        case .bool(let b): return b ? "true" : "false"
        case .int(let i): return canonicalNumber(Double(i))
        case .num(let d): return canonicalNumber(d)
        case .str(let s): return canonicalString(s)
        case .arr(let a): return "[" + a.map(canonicalJSON).joined(separator: ",") + "]"
        case .obj(let o):
            let keys = o.keys.sorted { $0.unicodeScalars.lexicographicallyPrecedes($1.unicodeScalars) }
            return "{" + keys.map { canonicalString($0) + ":" + canonicalJSON(o[$0]!) }.joined(separator: ",") + "}"
        }
    }

    static func canonicalNumber(_ value: Double) -> String {
        let x = InsightsMath.roundTo(value, 2)
        if x == x.rounded(.down) { return String(Int(x)) }
        var s = String(format: "%.2f", x)
        while s.hasSuffix("0") { s.removeLast() }
        if s.hasSuffix(".") { s.removeLast() }
        return s
    }

    private static func canonicalString(_ s: String) -> String {
        var out = "\""
        for ch in s.unicodeScalars {
            switch ch {
            case "\"": out += "\\\""
            case "\\": out += "\\\\"
            case "\n": out += "\\n"
            case "\r": out += "\\r"
            case "\t": out += "\\t"
            default:
                if ch.value < 0x20 {
                    out += String(format: "\\u%04x", ch.value)
                } else {
                    out.unicodeScalars.append(ch)
                }
            }
        }
        return out + "\""
    }

    // MARK: Prompts

    /// Fenced blocks of ai_explain.md under their "## " headings.
    static func loadPrompts(_ markdown: String) -> Prompts? {
        let lines = markdown.components(separatedBy: "\n")
        var blocks: [String: String] = [:]
        var heading: String?
        var i = 0
        while i < lines.count {
            let line = lines[i]
            if line.hasPrefix("## ") {
                heading = String(line.dropFirst(3)).trimmingCharacters(in: .whitespacesAndNewlines)
            } else if line.hasPrefix("```"), let h = heading {
                var j = i + 1
                while j < lines.count, !lines[j].hasPrefix("```") { j += 1 }
                blocks[h] = lines[(i + 1)..<min(j, lines.count)].joined(separator: "\n")
                i = j
            }
            i += 1
        }
        guard let cloud = blocks["System prompt (cloud)"], let local = blocks["System prompt (compact, on-device)"],
              let user = blocks["User template"] else { return nil }
        return Prompts(cloud: cloud, local: local, user: user)
    }

    static let bundledPrompts: Prompts? = {
        guard let url = Bundle.main.url(forResource: "ai_explain", withExtension: "md"),
              let text = try? String(contentsOf: url, encoding: .utf8) else { return nil }
        return loadPrompts(text)
    }()

    /// {system, user}: the user template gets {task} then {payload} by plain replacement.
    static func buildPrompt(kind: InsightsAIKind, payload: RJ, variant: InsightsAIVariant, config: InsightsConfig,
                            prompts: Prompts) -> InsightsPrompt {
        var section: [String: RJ] = [kind.rawValue: payload[kind.rawValue]]
        if kind == .dailyReview {
            section["patterns"] = payload["patterns"].array.map(RJ.arr) ?? .arr([])
        }
        let user = prompts.user
            .replacingOccurrences(of: "{task}", with: config.ai.tasks[kind.rawValue] ?? "")
            .replacingOccurrences(of: "{payload}", with: canonicalJSON(.obj(section)))
        return InsightsPrompt(system: variant == .cloud ? prompts.cloud : prompts.local, user: user)
    }

    // MARK: Validation

    static func utf16Length(_ s: String) -> Int { s.utf16.count }

    /// Strips a ``` fence when present, then returns the first balanced {…} (string- and escape-aware).
    static func extractJSONObject(_ text: String) -> String? {
        var t = Array(text.unicodeScalars)
        func find(_ needle: String, _ from: Int) -> Int {
            let n = Array(needle.unicodeScalars)
            guard from >= 0, t.count >= n.count, from <= t.count - n.count else { return -1 }
            for i in from...(t.count - n.count) where Array(t[i..<(i + n.count)]) == n { return i }
            return -1
        }
        let f = find("```", 0)
        if f >= 0 {
            let nl = find("\n", f)
            let end = nl >= 0 ? find("```", nl + 1) : -1
            if nl >= 0, end >= 0 { t = Array(t[(nl + 1)..<max(nl + 1, end)]) }
        }
        guard let start = t.firstIndex(of: "{") else { return nil }
        var depth = 0
        var inString = false, escaped = false
        for i in start..<t.count {
            let ch = t[i]
            if inString {
                if escaped {
                    escaped = false
                } else if ch == "\\" {
                    escaped = true
                } else if ch == "\"" {
                    inString = false
                }
            } else if ch == "\"" {
                inString = true
            } else if ch == "{" {
                depth += 1
            } else if ch == "}" {
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

    /// Tokens matching `[0-9]+(,[0-9]{3})*([.][0-9]+)?`, left to right.
    static func numberTokens(_ s: String) -> [String] {
        let u = Array(s.unicodeScalars)
        func digit(_ i: Int) -> Bool { i < u.count && u[i].value >= 48 && u[i].value <= 57 }
        var out: [String] = []
        var i = 0
        while i < u.count {
            guard digit(i) else { i += 1; continue }
            var j = i
            while digit(j) { j += 1 }
            while j + 3 < u.count, u[j] == ",", digit(j + 1), digit(j + 2), digit(j + 3) { j += 4 }
            if j < u.count, u[j] == ".", digit(j + 1) {
                j += 1
                while digit(j) { j += 1 }
            }
            var token = String.UnicodeScalarView()
            token.append(contentsOf: u[i..<j])
            out.append(String(token))
            i = j
        }
        return out
    }

    private static func normNumber(_ token: String) -> String {
        canonicalNumber(InsightsMath.roundTo(Double(token.replacingOccurrences(of: ",", with: "")) ?? 0, 2))
    }

    private static func payloadNumbers(_ value: RJ, _ out: inout Set<String>) {
        switch value {
        case .null, .bool: return
        case .int(let i):
            for d in [2, 1, 0] { out.insert(canonicalNumber(InsightsMath.roundTo(abs(Double(i)), d))) }
        case .num(let x):
            for d in [2, 1, 0] { out.insert(canonicalNumber(InsightsMath.roundTo(abs(x), d))) }
        case .str(let s):
            for token in numberTokens(s) { out.insert(normNumber(token)) }
        case .arr(let a):
            for v in a { payloadNumbers(v, &out) }
        case .obj(let o):
            for v in o.values { payloadNumbers(v, &out) }
        }
    }

    /// {ok, errors, output}: the explanation is shown only when ok; otherwise the deterministic text is used.
    static func validate(_ text: String, payload: RJ, config: InsightsConfig) -> InsightsAIValidation {
        let a = config.ai
        guard let raw = extractJSONObject(text), let data = raw.data(using: .utf8),
              let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            return InsightsAIValidation(ok: false, errors: ["parse_error"], output: nil)
        }
        guard let headRaw = obj["headline"] as? String, let list = obj["bullets"] as? [Any],
              let bulletsRaw = list as? [String], !(obj["headline"] is NSNumber) else {
            return InsightsAIValidation(ok: false, errors: ["bad_shape"], output: nil)
        }
        let head = headRaw.trimmingCharacters(in: .whitespacesAndNewlines)
        let bullets = bulletsRaw.map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
        var errors: [String] = []
        if !(1...a.headlineMax).contains(utf16Length(head)) { errors.append("headline_length") }
        if !(1...a.bulletsMax).contains(bullets.count) { errors.append("bullet_count") }
        if bullets.contains(where: { !(1...a.bulletMax).contains(utf16Length($0)) }) { errors.append("bullet_length") }
        var allowed = Set(a.allowedNumbers.map(canonicalNumber))
        payloadNumbers(payload, &allowed)
        for s in [head] + bullets where numberTokens(s).contains(where: { !allowed.contains(normNumber($0)) }) {
            errors.append("unknown_number")
            break
        }
        let lower = ([head] + bullets).joined(separator: " ").lowercased()
        if a.blockedTerms.contains(where: { lower.contains($0) }) { errors.append("blocked_term") }
        let ok = errors.isEmpty
        return InsightsAIValidation(ok: ok, errors: errors, output: ok ? InsightsExplanation(headline: head, bullets: bullets) : nil)
    }
}
