import Foundation

/// Swift port of `scripts/coach_reference.py` (docs/coach.md). Every function is pure and is driven
/// by `shared/coach/test-vectors/*.json` in `CoachVectorTests`. The Python reference wins over this
/// file and over the prose; keep the two in step in the same change.
///
/// Reuses the records primitives rather than re-implementing them: `RJ` (JSON value), `Rx` (portable
/// regex), `RR.fold` / `RR.piiLine` (the redaction rule of docs/health-records.md §28) and
/// `RecordsCoach.prefixScalars` (code-point slicing).
enum CR {

    // MARK: - Constants (docs/coach.md §4-§10)

    static let maxHeadingLevel = 4
    static let maxListDepth = 3
    static let maxQuoteDepth = 3
    static let tabWidth = 4
    static let indentPerDepth = 2

    static let chartFence = "ayuvo-chart"
    static let chartTypes = ["bar", "grouped_bar", "stacked_bar", "line", "area", "pie", "scatter",
                             "range", "progress"]
    static let rangeTypes: Set<String> = ["range"]
    static let singleSeriesTypes: Set<String> = ["pie", "progress"]

    /// Why a chart could not be read, in the four groups the app has wording for. Every reason has a
    /// group, so the renderer can always say what went wrong.
    static let chartReasonGroups: [String: String] = [
        "invalid_json": "malformed", "not_an_object": "malformed", "bad_point": "malformed",
        "unknown_type": "unsupported",
        "too_many_series": "too_big", "too_many_series_for_type": "too_big",
        "too_many_points": "too_big", "label_too_long": "too_big",
        "no_series": "no_readings", "no_points": "no_readings", "bad_number": "no_readings",
        "missing_y2": "no_readings", "bad_max": "no_readings",
    ]

    /// §5. The wording group for a refusal; an unknown reason reads as malformed.
    static func chartReasonGroup(_ reason: String?) -> String {
        chartReasonGroups[reason ?? ""] ?? "malformed"
    }

    /// Spellings of a type that mean one of `chartTypes`; anything else is still `unknown_type`.
    static let chartTypeAliases: [String: String] = [
        "column": "bar", "columns": "bar", "bars": "bar", "vertical_bar": "bar",
        "horizontal_bar": "bar", "grouped": "grouped_bar", "group_bar": "grouped_bar",
        "multi_bar": "grouped_bar", "stacked": "stacked_bar", "stacked_column": "stacked_bar",
        "lines": "line", "spline": "line", "donut": "pie", "doughnut": "pie",
        "scatter_plot": "scatter", "scatterplot": "scatter", "bubble": "scatter",
        "band": "range", "gauge": "progress", "progress_bar": "progress",
    ]
    /// Keys a spec may carry its parts under. The first key the object *has* wins, null or not.
    static let seriesKeys = ["series", "datasets", "data"]
    static let pointKeys = ["points", "data", "values", "y"]
    static let seriesLabelKeys = ["label", "name", "title"]
    static let labelsKeys = ["labels", "categories", "x_labels"]
    static let labelKeys = ["x", "label", "name", "t"]
    static let valueKeys = ["y", "value", "v"]
    static let highKeys = ["y2", "high", "y_high"]
    static let titleKeys = ["title"]
    static let unitKeys = ["unit", "units"]
    static let xLabelKeys = ["x_label", "xLabel", "x_axis", "xAxis"]
    static let yLabelKeys = ["y_label", "yLabel", "y_axis", "yAxis"]
    static let noteKeys = ["note", "subtitle", "caption"]
    static let maxKeys = ["max", "target"]
    /// Non-numbers a model writes where a reading is missing. They become "no reading", never zero.
    static let jsonNonNumbers = ["-Infinity", "+Infinity", "Infinity", "-infinity", "+infinity",
                                 "infinity", "NaN", "nan", "NAN", "undefined"]
    static let maxSeries = 4
    static let maxPoints = 60
    static let maxLabelChars = 60
    static let maxTitleChars = 120

    static let maxAttachmentPages = 20
    static let maxAttachmentChars = 20_000
    static let maxTurnChars = 40_000

    static let maxTitleLength = 48
    static let minSentenceLength = 12

    static let sources = ["food", "health", "medications", "records"]
    static let foodTools = ["get_data_summary", "get_weight_history", "get_body_fat_history",
                            "get_calorie_totals", "get_food_entries", "get_fasting_history"]
    static let workoutTools = ["get_workout_history", "get_workout_plans", "get_workout_preferences",
                               "get_training_summary", "get_exercise_lift_history"]
    static let healthTools = ["get_health_data_types", "get_health_summary", "get_health_samples",
                              "get_sleep_history"]
    static let medicationTools = ["get_medications", "get_dose_history", "get_medication_adherence"]
    static let recordsTools = ["records_search", "records_get", "records_observation_series"]

    static let archiveFormat = "ayuvo-coach-chats"
    static let archiveVersion = 1

    static let conversationColumns = ["id", "title", "created_ms", "updated_ms", "last_message_ms",
                                      "pinned", "archived", "data_sources", "selected_record_ids",
                                      "provider_override"]
    static let messageColumns = ["id", "conversation_id", "seq", "role", "content", "created_ms",
                                 "updated_ms", "regenerated_from", "variant_index", "record_refs",
                                 "attachment_ids"]
    static let attachmentColumns = ["id", "kind", "filename", "mime_type", "bytes", "sha256",
                                    "page_count", "char_count", "excerpt", "created_ms"]

    static let reRule = Rx("^(?:-{3,}|\\*{3,}|_{3,})$")
    static let reDelimCell = Rx("^:?-{1,}:?$")
    static let reNumbered = Rx("^([0-9]{1,9})[.)] (.*)$")
    static let reTask = Rx("^\\[([ xX])\\] (.*)$")
    static let reMDLink = Rx("\\[([^\\]]*)\\]\\([^)]*\\)")
    static let reMDMarks = Rx("[*_`~]")
    static let reSentenceEnd = Rx("[.!?]")
    static let reWSRun = Rx("[ \t\r\n]+")

    // MARK: - Small helpers

    /// Runs of space/tab/CR/LF collapse to one space; result trimmed.
    static func collapseWS(_ text: String) -> String { reWSRun.sub(text, " ").rStrip(" ") }

    /// Length in Unicode code points (NOT Characters — a grapheme cluster can be several).
    static func cpLen(_ text: String) -> Int { text.unicodeScalars.count }

    /// First `limit` code points.
    static func cpCut(_ text: String, _ limit: Int) -> String {
        RecordsCoach.prefixScalars(text, limit)
    }

    /// Tabs become `tabWidth` spaces so indentation is comparable across editors.
    static func expandTabs(_ line: String) -> String {
        var out = ""
        var column = 0
        for ch in line {
            if ch == "\t" {
                let width = tabWidth - (column % tabWidth)
                out += String(repeating: " ", count: width)
                column += width
            } else {
                out.append(ch)
                column += 1
            }
        }
        return out
    }

    static func indentOf(_ line: String) -> Int {
        var count = 0
        for ch in line {
            if ch == " " { count += 1 } else { break }
        }
        return count
    }

    static func depthFor(_ indent: Int) -> Int { min(maxListDepth, indent / indentPerDepth) }

    /// True for a JSON number that is neither bool, NaN nor +/-Infinity.
    static func isFiniteNumber(_ value: RJ) -> Bool {
        switch value {
        case .int: return true
        case .num(let d): return d.isFinite
        default: return false
        }
    }

    /// Deterministic number -> string for coerced chart x labels: integers lose the ".0".
    static func numText(_ value: RJ) -> String {
        switch value {
        case .int(let i): return "\(i)"
        case .num(let d):
            if d.rounded() == d, abs(d) < 1e15 { return "\(Int(d))" }
            return "\(d)"
        default: return ""
        }
    }

    // MARK: - §4 parse_blocks

    static func headingLevel(_ trimmed: String) -> Int? {
        var hashes = 0
        for ch in trimmed {
            if ch == "#" { hashes += 1 } else { break }
        }
        guard hashes >= 1, hashes <= maxHeadingLevel else { return nil }
        guard trimmed.dropFirst(hashes).first == " " else { return nil }
        return hashes
    }

    /// Leading '>' markers (each optionally followed by one space). depth 0 = not a quote.
    static func quoteDepth(_ trimmed: String) -> (Int, String) {
        var depth = 0
        var rest = Substring(trimmed)
        while let first = rest.first, first == ">", depth < maxQuoteDepth {
            depth += 1
            rest = rest.dropFirst()
            if rest.first == " " { rest = rest.dropFirst() }
        }
        return depth == 0 ? (0, trimmed) : (depth, String(rest))
    }

    /// Body after a '-', '*' or '+' marker, or nil.
    static func bulletText(_ body: String) -> String? {
        let chars = Array(body)
        guard chars.count >= 2, chars[0] == "-" || chars[0] == "*" || chars[0] == "+", chars[1] == " "
        else { return nil }
        return String(chars[2...]).rStrip(" ")
    }

    /// Table row cells. A leading and a trailing pipe are decoration and are dropped.
    static func splitRow(_ line: String) -> [String] {
        var text = line.rStrip(" ")
        if text.hasPrefix("|") { text = String(text.dropFirst()) }
        if text.hasSuffix("|"), !text.hasSuffix("\\|") { text = String(text.dropLast()) }
        return text.components(separatedBy: "|").map { $0.rStrip(" ") }
    }

    /// Delimiter row -> alignments, or nil when the row is not a delimiter row.
    static func alignments(_ cells: [String]) -> [String]? {
        guard !cells.isEmpty else { return nil }
        var out: [String] = []
        for cell in cells {
            guard reDelimCell.fullmatch(cell) != nil || reDelimCell.match(cell) != nil else { return nil }
            let left = cell.hasPrefix(":")
            let right = cell.hasSuffix(":")
            out.append(left && right ? "center" : (right ? "right" : "left"))
        }
        return out
    }

    /// §4. Block-level markdown for one assistant message.
    static func parseBlocks(_ markdown: String) -> RJ {
        let raw = markdown.replacingOccurrences(of: "\r\n", with: "\n")
            .replacingOccurrences(of: "\r", with: "\n")
        var lines = raw.components(separatedBy: "\n").map(expandTabs)
        if lines.last == "" { lines.removeLast() }  // the document terminator, not a blank line

        var blocks: [RJ] = []
        var paragraph: [String] = []
        var quote: [String] = []
        var quoteLevel = 0
        var index = 0

        func flushParagraph() {
            guard !paragraph.isEmpty else { return }
            blocks.append(.obj(["kind": .str("paragraph"), "text": .str(paragraph.joined(separator: " "))]))
            paragraph.removeAll()
        }
        func flushQuote() {
            guard !quote.isEmpty else { return }
            blocks.append(.obj(["kind": .str("quote"), "depth": .int(quoteLevel),
                                "text": .str(quote.joined(separator: " "))]))
            quote.removeAll()
        }
        func flushAll() { flushParagraph(); flushQuote() }

        while index < lines.count {
            let line = lines[index]
            let trimmed = line.rStrip(" ")

            if trimmed.hasPrefix("```") {
                flushAll()
                let info = String(trimmed.dropFirst(3)).rStrip(" ").lowercased()
                var body: [String] = []
                index += 1
                while index < lines.count, !lines[index].rStrip(" ").hasPrefix("```") {
                    body.append(lines[index])
                    index += 1
                }
                index += 1  // skip the closing fence (or run past the end)
                let text = body.joined(separator: "\n")
                if info == chartFence {
                    blocks.append(chartBlock(text))
                } else {
                    blocks.append(.obj(["kind": .str("code"),
                                        "lang": info.isEmpty ? .null : .str(info),
                                        "text": .str(text)]))
                }
                continue
            }

            if trimmed.isEmpty {
                flushAll()
                index += 1
                continue
            }

            if reRule.match(trimmed) != nil {
                flushAll()
                blocks.append(.obj(["kind": .str("rule")]))
                index += 1
                continue
            }

            if let level = headingLevel(trimmed) {
                flushAll()
                blocks.append(.obj(["kind": .str("heading"), "level": .int(level),
                                    "text": .str(String(trimmed.dropFirst(level)).rStrip(" "))]))
                index += 1
                continue
            }

            if trimmed.contains("|"), index + 1 < lines.count,
               let aligns = alignments(splitRow(lines[index + 1])) {
                let headers = splitRow(line)
                if aligns.count == headers.count {
                    flushAll()
                    var rows: [RJ] = []
                    index += 2
                    while index < lines.count {
                        let rowLine = lines[index].rStrip(" ")
                        if rowLine.isEmpty || !rowLine.contains("|") { break }
                        var cells = splitRow(lines[index])
                        if cells.count < headers.count {
                            cells += Array(repeating: "", count: headers.count - cells.count)
                        }
                        rows.append(.arr(cells.prefix(headers.count).map { RJ.str($0) }))
                        index += 1
                    }
                    blocks.append(.obj(["kind": .str("table"),
                                        "headers": .arr(headers.map { RJ.str($0) }),
                                        "aligns": .arr(aligns.map { RJ.str($0) }),
                                        "rows": .arr(rows)]))
                    continue
                }
            }

            let (depth, body) = quoteDepth(trimmed)
            if depth > 0 {
                flushParagraph()
                if !quote.isEmpty, depth != quoteLevel { flushQuote() }
                quoteLevel = depth
                quote.append(body.rStrip(" "))
                index += 1
                continue
            }
            flushQuote()

            let indent = indentOf(line)
            if let bullet = bulletText(trimmed) {
                flushParagraph()
                if let task = reTask.match(bullet) {
                    blocks.append(.obj(["kind": .str("task"), "depth": .int(depthFor(indent)),
                                        "checked": .bool(task.g(1) != " "),
                                        "text": .str((task.g(2) ?? "").rStrip(" "))]))
                } else {
                    blocks.append(.obj(["kind": .str("bullet"), "depth": .int(depthFor(indent)),
                                        "text": .str(bullet)]))
                }
                index += 1
                continue
            }

            if let numbered = reNumbered.match(trimmed) {
                flushParagraph()
                blocks.append(.obj(["kind": .str("numbered"), "depth": .int(depthFor(indent)),
                                    "marker": .str(numbered.g(1) ?? ""),
                                    "text": .str((numbered.g(2) ?? "").rStrip(" "))]))
                index += 1
                continue
            }

            paragraph.append(trimmed)
            index += 1
        }

        flushAll()
        return .obj(["blocks": .arr(blocks)])
    }

    // MARK: - Strict JSON (docs/coach.md §5)
    //
    // Platform JSON parsers disagree about what they accept, so a chart spec is never handed to one.
    // Apple's JSONSerialization accepts trailing commas; Android's org.json additionally accepts
    // single quotes, unquoted keys and comments; Python's json accepts NaN and Infinity. A spec that
    // renders on one phone and not another is a parity bug, so all three ports run this scanner
    // instead: RFC 8259 with no extensions.

    static let maxJSONChars = 20_000
    static let maxJSONDepth = 32

    private struct JSONScanner {
        let text: [Character]
        var index = 0

        init(_ raw: String) { text = Array(raw) }

        mutating func skipWhitespace() {
            while index < text.count, text[index] == " " || text[index] == "\t"
                    || text[index] == "\n" || text[index] == "\r" {
                index += 1
            }
        }

        mutating func string() -> String? {
            guard index < text.count, text[index] == "\"" else { return nil }
            index += 1
            var out = ""
            while true {
                guard index < text.count else { return nil }
                let ch = text[index]
                if ch == "\"" { index += 1; return out }
                if ch == "\\" {
                    index += 1
                    guard index < text.count else { return nil }
                    let esc = text[index]
                    if esc == "u" {
                        guard index + 4 < text.count else { return nil }
                        let digits = String(text[(index + 1)...(index + 4)])
                        guard digits.allSatisfy({ $0.isHexDigit }), let code = UInt32(digits, radix: 16),
                              let scalar = Unicode.Scalar(code) else { return nil }
                        out.unicodeScalars.append(scalar)
                        index += 5
                        continue
                    }
                    switch esc {
                    case "\"": out.append("\"")
                    case "\\": out.append("\\")
                    case "/": out.append("/")
                    case "b": out.append("\u{08}")
                    case "f": out.append("\u{0C}")
                    case "n": out.append("\n")
                    case "r": out.append("\r")
                    case "t": out.append("\t")
                    default: return nil
                    }
                    index += 1
                    continue
                }
                if let scalar = ch.unicodeScalars.first, ch.unicodeScalars.count == 1, scalar.value < 0x20 {
                    return nil
                }
                out.append(ch)
                index += 1
            }
        }

        mutating func number() -> RJ? {
            let start = index
            if index < text.count, text[index] == "-" { index += 1 }
            guard index < text.count, text[index].isASCIIDigit else { return nil }
            if text[index] == "0" {
                index += 1
            } else {
                while index < text.count, text[index].isASCIIDigit { index += 1 }
            }
            var isFloat = false
            if index < text.count, text[index] == "." {
                isFloat = true
                index += 1
                guard index < text.count, text[index].isASCIIDigit else { return nil }
                while index < text.count, text[index].isASCIIDigit { index += 1 }
            }
            if index < text.count, text[index] == "e" || text[index] == "E" {
                isFloat = true
                index += 1
                if index < text.count, text[index] == "+" || text[index] == "-" { index += 1 }
                guard index < text.count, text[index].isASCIIDigit else { return nil }
                while index < text.count, text[index].isASCIIDigit { index += 1 }
            }
            let raw = String(text[start..<index])
            if isFloat {
                guard let value = Double(raw), value.isFinite else { return nil }
                return .num(value)
            }
            guard let value = Int(raw) else { return nil }
            return .int(value)
        }

        mutating func literal(_ word: String) -> Bool {
            let chars = Array(word)
            guard index + chars.count <= text.count else { return false }
            guard Array(text[index..<(index + chars.count)]) == chars else { return false }
            index += chars.count
            return true
        }

        mutating func value(_ depth: Int) -> RJ? {
            guard depth <= maxJSONDepth, index < text.count else { return nil }
            let ch = text[index]
            if ch == "{" {
                var out: [String: RJ] = [:]
                index += 1
                skipWhitespace()
                if index < text.count, text[index] == "}" { index += 1; return .obj(out) }
                while true {
                    guard let key = string() else { return nil }
                    skipWhitespace()
                    guard index < text.count, text[index] == ":" else { return nil }
                    index += 1
                    skipWhitespace()
                    guard let member = value(depth + 1) else { return nil }
                    out[key] = member  // a repeated key keeps the last value
                    skipWhitespace()
                    if index < text.count, text[index] == "," {
                        index += 1
                        skipWhitespace()
                        continue       // a ',' must be followed by a member, never by '}'
                    }
                    if index < text.count, text[index] == "}" { index += 1; return .obj(out) }
                    return nil
                }
            }
            if ch == "[" {
                var out: [RJ] = []
                index += 1
                skipWhitespace()
                if index < text.count, text[index] == "]" { index += 1; return .arr(out) }
                while true {
                    guard let item = value(depth + 1) else { return nil }
                    out.append(item)
                    skipWhitespace()
                    if index < text.count, text[index] == "," {
                        index += 1
                        skipWhitespace()
                        continue
                    }
                    if index < text.count, text[index] == "]" { index += 1; return .arr(out) }
                    return nil
                }
            }
            if ch == "\"" { return string().map(RJ.str) }
            if literal("true") { return .bool(true) }
            if literal("false") { return .bool(false) }
            if literal("null") { return .null }
            return number()
        }
    }

    /// RFC 8259 with no extensions. nil = refused; see the note above for why the platform parser is
    /// not used here.
    static func strictJSON(_ raw: String) -> RJ? {
        guard cpLen(raw) <= maxJSONChars else { return nil }
        var scanner = JSONScanner(raw)
        scanner.skipWhitespace()
        guard let value = scanner.value(0) else { return nil }
        scanner.skipWhitespace()
        guard scanner.index == scanner.text.count else { return nil }
        return value
    }

    // MARK: - §5 parse_chart_spec

    private static func chartChars(_ text: [Character], _ start: Int, _ word: String) -> Bool {
        let chars = Array(word)
        guard start + chars.count <= text.count else { return false }
        for (offset, ch) in chars.enumerated() where text[start + offset] != ch { return false }
        return true
    }

    /// Past whitespace and // or /* */ comments, which a model sometimes leaves in a spec.
    private static func skipWSComments(_ text: [Character], _ start: Int) -> Int {
        var i = start
        while i < text.count {
            let ch = text[i]
            if ch == " " || ch == "\t" || ch == "\n" || ch == "\r" { i += 1; continue }
            if ch == "/", i + 1 < text.count, text[i + 1] == "/" {
                while i < text.count, text[i] != "\n" { i += 1 }
                continue
            }
            if ch == "/", i + 1 < text.count, text[i + 1] == "*" {
                i += 2
                while i + 1 < text.count, !(text[i] == "*" && text[i + 1] == "/") { i += 1 }
                i = min(text.count, i + 2)
                continue
            }
            break
        }
        return i
    }

    /// True when `text[i ..< i + length]` is not part of a longer bare word.
    private static func wordBoundary(_ text: [Character], _ i: Int, _ length: Int) -> Bool {
        func bare(_ ch: Character?) -> Bool {
            guard let ch else { return false }
            return ch.isLetter || ch.isNumber || ch == "_"
        }
        return !bare(i > 0 ? text[i - 1] : nil) && !bare(i + length < text.count ? text[i + length] : nil)
    }

    private static let numberChars: Set<Character> = Set("0123456789+-.eE")

    /// The maximal run of number characters starting at `i` (a number token as written).
    private static func numberRun(_ text: [Character], _ i: Int) -> [Character] {
        var end = i
        while end < text.count, numberChars.contains(text[end]) { end += 1 }
        return Array(text[i..<end])
    }

    /// From the `{` at `start`: the root value alone (trailing prose dropped), or, when the body was
    /// cut short, the same text with the containers it left open closed. Completing a truncated body
    /// adds structure, never a value: a body cut off inside a string is left as it is.
    private static func completeOrCut(_ text: [Character], _ start: Int) -> String {
        var stack: [Character] = []
        var inString = false
        var i = start
        while i < text.count {
            let ch = text[i]
            if inString {
                if ch == "\\" { i += 2; continue }
                if ch == "\"" { inString = false }
                i += 1
                continue
            }
            if ch == "\"" { inString = true; i += 1; continue }
            if ch == "{" || ch == "[" {
                stack.append(ch == "{" ? "}" : "]")
                i += 1
                continue
            }
            if ch == "}" || ch == "]" {
                if !stack.isEmpty { stack.removeLast() }
                i += 1
                if stack.isEmpty { return String(text[start..<i]) }
                continue
            }
            i += 1
        }
        if inString || stack.isEmpty { return String(text[start...]) }
        var body = String(text[start...])
        while let last = body.last, last == " " || last == "\t" || last == "\n" || last == "," {
            body.removeLast()
        }
        return body + String(stack.reversed())
    }

    /// §5. The small, deterministic repairs a fence body gets before it is parsed: comments dropped,
    /// a comma before `}`/`]` dropped, NaN/Infinity/undefined and unreadable number tokens (`7.1.0`,
    /// `01`) read as "no reading", prose after the root value dropped and a cut-short body's open
    /// containers closed. Nothing here invents, rounds or moves a number, and nothing reaches inside
    /// a string.
    static func repairChartJSON(_ raw: String) -> String {
        let normalised = raw.replacingOccurrences(of: "\r\n", with: "\n")
            .replacingOccurrences(of: "\r", with: "\n")
        guard cpLen(normalised) <= maxJSONChars else { return normalised }
        let text = Array(normalised)
        var out: [Character] = []
        var i = 0
        while i < text.count {
            let ch = text[i]
            if ch == "\"" {                       // a string is copied verbatim, escapes and all
                out.append(ch)
                i += 1
                while i < text.count {
                    out.append(text[i])
                    if text[i] == "\\", i + 1 < text.count {
                        out.append(text[i + 1])
                        i += 2
                        continue
                    }
                    if text[i] == "\"" { i += 1; break }
                    i += 1
                }
                continue
            }
            if ch == "/", i + 1 < text.count, text[i + 1] == "/" || text[i + 1] == "*" {
                i = skipWSComments(text, i)
                continue
            }
            if ch == "," {
                let next = skipWSComments(text, i + 1)
                if next < text.count, text[next] == "}" || text[next] == "]" { i += 1; continue }
                out.append(ch)
                i += 1
                continue
            }
            var word: String?
            for candidate in jsonNonNumbers
            where chartChars(text, i, candidate) && wordBoundary(text, i, candidate.count) {
                word = candidate
                break
            }
            if let word {
                out.append(contentsOf: "null")
                i += word.count
                continue
            }
            if numberChars.contains(ch), ch != "." && ch != "e" && ch != "E" {
                let token = numberRun(text, i)
                let parsed = strictJSON(String(token))
                out.append(contentsOf: parsed.map(isFiniteNumber) == true ? token : Array("null"))
                i += token.count
                // A quote glued to the end of a number (`5.38"`) can never start a string in JSON.
                // Left in, it opens one that swallows the rest of the line and every reading in it.
                if i < text.count, text[i] == "\"" { i += 1 }
                continue
            }
            out.append(ch)
            i += 1
        }
        let repaired = Array(String(out))
        guard let start = repaired.firstIndex(of: "{") else {
            return String(repaired).trimmingCharacters(in: CharacterSet(charactersIn: " \t\n"))
        }
        return completeOrCut(repaired, start)
            .trimmingCharacters(in: CharacterSet(charactersIn: " \t\n"))
    }

    /// The value of the first of `keys` the object *has* (null counts as having it), or nil.
    private static func firstKey(_ doc: RJ, _ keys: [String]) -> RJ? {
        guard let object = doc.object else { return nil }
        for key in keys where object[key] != nil { return object[key] }
        return nil
    }

    /// A written type -> one of `chartTypes`, or nil.
    private static func chartType(_ value: RJ) -> String? {
        guard let raw = value.string else { return nil }
        var text = raw.trimmingCharacters(in: CharacterSet(charactersIn: " \t\n\r")).lowercased()
            .replacingOccurrences(of: " ", with: "_")
            .replacingOccurrences(of: "-", with: "_")
        if text.hasSuffix("_chart") { text = String(text.dropLast("_chart".count)) }
        if chartTypes.contains(text) { return text }
        return chartTypeAliases[text]
    }

    /// A finite number, or a string holding exactly one. A quoted number is still the reading the
    /// model took; a string with a unit in it ("6.2 h") is not a number.
    private static func chartNumber(_ value: RJ?) -> RJ? {
        guard let value else { return nil }
        if isFiniteNumber(value) { return value }
        if let text = value.string,
           let parsed = strictJSON(text.trimmingCharacters(in: CharacterSet(charactersIn: " \t\n\r"))),
           isFiniteNumber(parsed) {
            return parsed
        }
        return nil
    }

    /// True when a point carries no reading at all, as opposed to an unreadable one.
    private static func chartMissing(_ value: RJ?) -> Bool {
        guard let value else { return true }
        if value.isNull { return true }
        if let text = value.string {
            return text.trimmingCharacters(in: CharacterSet(charactersIn: " \t\n\r")).isEmpty
        }
        return false
    }

    /// Shared x labels, for the `{"labels": [...], "series": [{"values": [...]}]}` shape.
    private static func chartLabels(_ doc: RJ) -> [String] {
        guard let raw = firstKey(doc, labelsKeys)?.array else { return [] }
        return raw.map { item in
            if let text = item.string { return collapseWS(text) }
            if isFiniteNumber(item) { return numText(item) }
            return ""
        }
    }

    private static func positionalLabel(_ labels: [String], _ index: Int) -> String {
        if index >= 0, index < labels.count, !labels[index].isEmpty { return labels[index] }
        return "\(index + 1)"
    }

    static func chartBlock(_ raw: String) -> RJ {
        let parsed = parseChartSpec(raw)
        if parsed["ok"].bool == true {
            return .obj(["kind": .str("chart"), "ok": .bool(true), "spec": parsed["spec"]])
        }
        return .obj(["kind": .str("chart"), "ok": .bool(false),
                     "reason": parsed["reason"], "text": .str(raw)])
    }

    private static func bad(_ reason: String) -> RJ {
        .obj(["ok": .bool(false), "reason": .str(reason)])
    }

    private static func textOrNull(_ value: RJ, _ limit: Int) -> RJ {
        guard let raw = value.string else { return .null }
        let text = collapseWS(raw)
        return text.isEmpty ? .null : .str(cpCut(text, limit))
    }

    /// One parsed point: the point itself, "no reading here", or the reason it was refused.
    private enum PointResult {
        case ok(RJ)
        case skip
        case bad(String)
    }

    /// One point -> the normalised object, `.skip` when it carries no reading, or a reason.
    private static func point(_ raw: RJ, _ index: Int, _ needsY2: Bool, _ labels: [String]) -> PointResult {
        var xValue: RJ?
        var yValue: RJ?
        var y2Value: RJ?
        switch raw {
        case .arr(let items):
            guard !items.isEmpty else { return .bad("bad_point") }
            if items.count == 1 {
                yValue = items[0]
            } else {
                xValue = items[0]
                yValue = items[1]
                if items.count > 2 { y2Value = items[2] }
            }
        case .obj:
            xValue = firstKey(raw, labelKeys)
            yValue = firstKey(raw, valueKeys)
            y2Value = firstKey(raw, highKeys)
        case .null, .str, .int, .num:
            yValue = raw
        default:
            return .bad("bad_point")
        }

        let label: String
        if let text = xValue?.string,
           !text.trimmingCharacters(in: CharacterSet(charactersIn: " \t\n\r")).isEmpty {
            label = collapseWS(text)
        } else if let xValue, isFiniteNumber(xValue) {
            label = numText(xValue)
        } else if chartMissing(xValue) {
            label = positionalLabel(labels, index)
        } else {
            return .bad("bad_point")
        }
        if cpLen(label) > maxLabelChars { return .bad("label_too_long") }

        guard let y = chartNumber(yValue) else {
            // No reading for this point: it is left out, never drawn as zero.
            return chartMissing(yValue) ? .skip : .bad("bad_number")
        }

        var out: [String: RJ] = ["x": .str(label), "y": y]
        if needsY2 {
            guard let y2 = chartNumber(y2Value) else { return .bad("missing_y2") }
            out["y2"] = y2
        } else if !chartMissing(y2Value) {
            guard let y2 = chartNumber(y2Value) else { return .bad("bad_number") }
            out["y2"] = y2
        }
        return .ok(.obj(out))
    }

    /// §5. The body of an ```ayuvo-chart fence. The body is repaired first (`repairChartJSON`), then
    /// read strictly; a known alias, a recognised key and a quoted number are all understood. What
    /// cannot be understood still fails closed, and a point with no reading is left out rather than
    /// drawn as zero: a chart never renders from a partially understood spec.
    static func parseChartSpec(_ raw: String) -> RJ {
        guard let doc = strictJSON(repairChartJSON(raw)) else { return bad("invalid_json") }
        guard case .obj = doc else { return bad("not_an_object") }

        guard let kind = chartType(doc["type"]) else { return bad("unknown_type") }

        var rawSeries: [RJ]
        let seriesValue = firstKey(doc, seriesKeys) ?? .null
        if case .obj = seriesValue {
            rawSeries = [seriesValue]
        } else if let items = seriesValue.array {
            rawSeries = items
        } else {
            return bad("no_series")
        }
        if rawSeries.isEmpty { return bad("no_series") }
        if !rawSeries.contains(where: { $0.object != nil && firstKey($0, pointKeys)?.array != nil }) {
            // Nothing in the list carries points, so the list is not a list of series: it is either a
            // list of point lists (one series each) or one series' points written bare.
            let nested = rawSeries.allSatisfy { entry in
                guard let items = entry.array, !items.isEmpty else { return false }
                return items.allSatisfy { $0.array != nil || $0.object != nil }
            }
            rawSeries = nested ? rawSeries.map { .obj(["points": $0]) } : [.obj(["points": .arr(rawSeries)])]
        }
        if rawSeries.count > maxSeries { return bad("too_many_series") }
        if singleSeriesTypes.contains(kind), rawSeries.count > 1 { return bad("too_many_series_for_type") }

        let labels = chartLabels(doc)
        let needsY2 = rangeTypes.contains(kind)
        var series: [RJ] = []
        var dropped = 0
        for (position, entry) in rawSeries.enumerated() {
            guard case .obj = entry else { return bad("no_series") }
            guard let rawPoints = firstKey(entry, pointKeys)?.array, !rawPoints.isEmpty else {
                return bad("no_points")
            }
            if rawPoints.count > maxPoints { return bad("too_many_points") }
            var points: [RJ] = []
            for (index, rawPoint) in rawPoints.enumerated() {
                switch point(rawPoint, index, needsY2, labels) {
                case .bad(let reason): return self.bad(reason)
                case .skip:                       // a reading the model did not have, or wrote unreadably
                    dropped += 1
                    continue
                case .ok(let value): points.append(value)
                }
            }
            if points.isEmpty { continue }        // every reading was missing: the series is left out
            let label = textOrNull(firstKey(entry, seriesLabelKeys) ?? .null, maxLabelChars)
            series.append(.obj(["label": label.isNull ? .str("\(position + 1)") : label,
                                "points": .arr(points)]))
        }
        if series.isEmpty { return bad("no_points") }

        let rawMax = firstKey(doc, maxKeys)
        var maximum = RJ.null
        if !chartMissing(rawMax) {
            guard let value = chartNumber(rawMax) else { return bad("bad_max") }
            maximum = value
        }
        if kind == "progress", !maximum.isNull, (maximum.double ?? 0) <= 0 { return bad("bad_max") }

        let spec: RJ = .obj([
            "type": .str(kind),
            "title": textOrNull(firstKey(doc, titleKeys) ?? .null, maxTitleChars),
            "unit": textOrNull(firstKey(doc, unitKeys) ?? .null, maxLabelChars),
            "x_label": textOrNull(firstKey(doc, xLabelKeys) ?? .null, maxLabelChars),
            "y_label": textOrNull(firstKey(doc, yLabelKeys) ?? .null, maxLabelChars),
            "note": textOrNull(firstKey(doc, noteKeys) ?? .null, maxTitleChars),
            "max": maximum,
            "series": .arr(series),
            "dropped": .int(dropped),
        ])
        return .obj(["ok": .bool(true), "spec": spec])
    }

    static let chartKindLabels: [String: String] = [
        "bar": "Bar chart", "grouped_bar": "Grouped bar chart", "stacked_bar": "Stacked bar chart",
        "line": "Line chart", "area": "Area chart", "pie": "Pie chart", "scatter": "Scatter chart",
        "range": "Range chart", "progress": "Progress chart",
    ]

    /// One sentence describing a parsed chart, for VoiceOver.
    static func chartAccessibilityText(_ spec: RJ) -> String {
        var parts = [chartKindLabels[spec["type"].string ?? ""] ?? "Chart"]
        if let title = spec["title"].string { parts.append(title) }
        var values: [Double] = []
        for entry in spec["series"].array ?? [] {
            for p in entry["points"].array ?? [] {
                if let y = p["y"].double { values.append(y) }
                if let y2 = p["y2"].double { values.append(y2) }
            }
        }
        if let low = values.min(), let high = values.max() {
            let unit = spec["unit"].string.map { " " + $0 } ?? ""
            parts.append("\(numText(.number(low))) to \(numText(.number(high)))\(unit)")
        }
        return parts.joined(separator: ", ")
    }

    // MARK: - §6 attachment_excerpt

    /// §6. Page texts of one attachment -> the text actually sent to the provider, redacted by the
    /// records rule so a lab PDF in chat is treated exactly like a record read through `records_get`.
    static func attachmentExcerpt(_ pages: [String], maxPages: Int = maxAttachmentPages,
                                  maxChars: Int = maxAttachmentChars) -> RJ {
        let total = pages.count
        var kept: [(Int, String)] = []
        var redacted = 0
        for (offset, page) in pages.prefix(maxPages).enumerated() {
            let text = page.replacingOccurrences(of: "\r\n", with: "\n")
                .replacingOccurrences(of: "\r", with: "\n")
            var lines: [String] = []
            for line in text.components(separatedBy: "\n") {
                if RR.piiLine(RR.fold(line), []) { redacted += 1 } else { lines.append(line) }
            }
            let body = lines.joined(separator: "\n").rStrip("\n")
            if body.rStrip(" \t\n").isEmpty { continue }
            kept.append((offset + 1, body))
        }

        let parts: [String] = total > 1
            ? kept.map { "--- page \($0.0) ---\n\($0.1)" }
            : kept.map(\.1)
        var text = parts.joined(separator: "\n\n")
        let truncated = cpLen(text) > maxChars || total > maxPages
        text = cpCut(text, maxChars)
        return .obj([
            "text": text.isEmpty ? .null : .str(text),
            "pages_total": .int(total),
            "pages_used": .int(kept.count),
            "pages_skipped": .int(max(0, total - maxPages)),
            "chars": .int(cpLen(text)),
            "redacted_lines": .int(redacted),
            "truncated": .bool(truncated),
        ])
    }

    /// §6. Excerpts of every document attached to one turn, cut to the per-turn budget in order.
    static func turnExcerpts(_ attachments: [RJ], maxTurnChars: Int = maxTurnChars) -> RJ {
        var used = 0
        var out: [RJ] = []
        for item in attachments {
            let text = item["text"].string ?? ""
            let room = max(0, maxTurnChars - used)
            let cut = cpCut(text, room)
            used += cpLen(cut)
            out.append(.obj(["id": item["id"], "filename": item["filename"],
                             "chars": .int(cpLen(cut)), "truncated": .bool(cpLen(cut) < cpLen(text))]))
        }
        return .obj(["attachments": .arr(out), "chars": .int(used)])
    }

    // MARK: - §7 conversation_title

    /// `[text](url)` -> `text`. Written out rather than a template replacement because `Rx.sub`
    /// escapes its template, so "$1" would be inserted literally.
    static func stripMarkdownLinks(_ text: String) -> String {
        var out = ""
        var last = 0
        for m in reMDLink.finditer(text) {
            out += text.rSub(last, m.start)
            out += m.g(1) ?? ""
            last = m.end
        }
        out += text.rSub(last)
        return out
    }

    /// §7. Title derived from the first user message. Empty input -> "" (the platform then shows its
    /// own localized "New chat").
    static func conversationTitle(_ text: String) -> RJ {
        var stripped = stripMarkdownLinks(text)
        stripped = reMDMarks.sub(stripped, "")
        stripped = collapseWS(stripped)
        while stripped.hasPrefix("#") { stripped = String(stripped.dropFirst()) }
        while stripped.hasPrefix(">") { stripped = String(stripped.dropFirst()) }
        stripped = stripped.rStrip(" ")
        if stripped.isEmpty { return .obj(["title": .str("")]) }

        if let match = reSentenceEnd.search(stripped), match.start + 1 >= minSentenceLength {
            stripped = stripped.rSub(0, match.start).rStrip(" ")
        }
        if cpLen(stripped) <= maxTitleLength { return .obj(["title": .str(stripped)]) }

        var cut = cpCut(stripped, maxTitleLength)
        if let space = cut.lastIndex(of: " ") {
            let head = String(cut[cut.startIndex..<space])
            if cpLen(head) >= minSentenceLength { cut = head }
        }
        return .obj(["title": .str(cut.rStrip(" ") + "\u{2026}")])
    }

    // MARK: - §8 resolve_data_sources

    /// §8. A source is effective only when it is available, consented and not switched off. The
    /// switch can only narrow: turning it on never grants consent.
    static func resolveDataSources(available: RJ, consents: RJ, switches: RJ,
                                   workoutsAvailable: Bool) -> RJ {
        var effective: [String: RJ] = [:]
        var blocked: [RJ] = []
        var on: [String: Bool] = [:]
        for name in sources {
            let isAvailable = available[name].truthy
            let consented = name == "food" ? true : consents[name].truthy
            let switchedOn = switches[name].bool != false
            let value = isAvailable && consented && switchedOn
            effective[name] = .bool(value)
            on[name] = value
            if !isAvailable {
                blocked.append(.obj(["source": .str(name), "reason": .str("unavailable")]))
            } else if !consented {
                blocked.append(.obj(["source": .str(name), "reason": .str("not_consented")]))
            } else if !switchedOn {
                blocked.append(.obj(["source": .str(name), "reason": .str("switched_off")]))
            }
        }

        var tools: [String] = []
        if on["food"] == true {
            tools += foodTools
            if workoutsAvailable { tools += workoutTools }
        }
        if on["health"] == true { tools += healthTools }
        if on["medications"] == true { tools += medicationTools }
        if on["records"] == true { tools += recordsTools }
        return .obj(["sources": .obj(effective), "tools": .arr(tools.map { RJ.str($0) }),
                     "blocked": .arr(blocked)])
    }

    // MARK: - §9 prompt gallery

    /// §9. The gallery entries offered for a set of effective sources, grouped by category in catalog
    /// order. An entry whose `requires` is not fully satisfied is absent, never greyed out.
    static func galleryFor(_ sourcesValue: RJ, catalog: RJ) -> RJ {
        let entries = catalog["prompts"].array ?? []
        let categories = (catalog["categories"].array ?? []).compactMap(\.string)
        let active = Set(sources.filter { sourcesValue[$0].truthy })
        let chosen = entries
            .filter { entry in
                let required = Set((entry["requires"].array ?? []).compactMap(\.string))
                return required.isSubset(of: active)
            }
            .stableSorted { a, b in
                let ao = a["order"].double ?? 0
                let bo = b["order"].double ?? 0
                if ao != bo { return ao < bo }
                return (a["id"].string ?? "") < (b["id"].string ?? "")
            }
        var grouped: [RJ] = []
        for category in categories {
            let ids = chosen.filter { $0["category"].string == category }.compactMap { $0["id"].string }
            if !ids.isEmpty {
                grouped.append(.obj(["category": .str(category), "ids": .arr(ids.map { RJ.str($0) })]))
            }
        }
        return .obj(["categories": .arr(grouped), "count": .int(chosen.count)])
    }

    /// The chips above the composer, by weight goal. They are gallery ids, not their own strings, so
    /// a chip and the gallery card that says the same thing are one entry with one translation.
    static let chipGoals: [String: [String]] = [
        "lose": ["reach_my_goal", "week_review", "dinner_tonight", "one_thing_to_change"],
        "gain": ["reach_my_goal", "protein_sources", "week_review", "one_thing_to_change"],
        "maintain": ["month_over_month", "week_review", "macro_balance", "whole_picture"],
    ]
    /// No goal set yet: nothing that assumes a direction.
    static let chipDefault = ["week_review", "reach_my_goal", "one_thing_to_change"]
    static let maxChips = 6

    /// §9. The chips offered above the composer: the goal's own prompts, with a training and a sleep
    /// prompt in front when there is something to read. Gated by ``galleryFor``, so a chip can never
    /// promise data the user has not connected.
    static func chipsFor(goal: String?, hasWorkouts: Bool, hasSleep: Bool,
                         sources: RJ, catalog: RJ) -> RJ {
        var offered = Set<String>()
        for group in galleryFor(sources, catalog: catalog)["categories"].array ?? [] {
            offered.formUnion((group["ids"].array ?? []).compactMap(\.string))
        }
        var wanted: [String] = []
        if hasSleep { wanted.append("sleep_week") }
        if hasWorkouts { wanted.append("training_review") }
        wanted += chipGoals[goal ?? ""] ?? chipDefault
        var ids: [String] = []
        for entry in wanted where offered.contains(entry) && !ids.contains(entry) {
            ids.append(entry)
        }
        return .obj(["ids": .arr(ids.prefix(maxChips).map { RJ.str($0) })])
    }

    // MARK: - §11 archive

    private static func rows(_ snapshot: RJ, _ table: String) -> [RJ] { snapshot[table].array ?? [] }
    private static func live(_ list: [RJ]) -> [RJ] { list.filter { !$0["deleted"].truthy } }

    private static func pick(_ row: RJ, _ columns: [String]) -> RJ {
        var out: [String: RJ] = [:]
        for key in columns { out[key] = row[key] }
        return .obj(out)
    }

    /// §11. A store snapshot -> the `ayuvo-coach-chats` document. Tombstones are local and never
    /// exported; ordering is fixed so a re-export is byte-identical.
    static func chatArchive(_ snapshot: RJ) -> RJ {
        let conversations = live(rows(snapshot, "conversations")).stableSorted { a, b in
            let ac = a["created_ms"].double ?? 0
            let bc = b["created_ms"].double ?? 0
            if ac != bc { return ac < bc }
            return (a["id"].string ?? "") < (b["id"].string ?? "")
        }
        let known = Set(conversations.compactMap { $0["id"].string })
        let messages = live(rows(snapshot, "messages"))
            .filter { known.contains($0["conversation_id"].string ?? "") }
            .stableSorted { a, b in
                let ac = a["conversation_id"].string ?? ""
                let bc = b["conversation_id"].string ?? ""
                if ac != bc { return ac < bc }
                let asq = a["seq"].double ?? 0
                let bsq = b["seq"].double ?? 0
                if asq != bsq { return asq < bsq }
                return (a["id"].string ?? "") < (b["id"].string ?? "")
            }
        var used = Set<String>()
        for message in messages {
            for id in message["attachment_ids"].array ?? [] {
                if let text = id.string { used.insert(text) }
            }
        }
        let attachments = live(rows(snapshot, "attachments"))
            .filter { used.contains($0["id"].string ?? "") }
            .stableSorted { a, b in
                let ac = a["created_ms"].double ?? 0
                let bc = b["created_ms"].double ?? 0
                if ac != bc { return ac < bc }
                return (a["id"].string ?? "") < (b["id"].string ?? "")
            }
        return .obj([
            "format": .str(archiveFormat),
            "format_version": .int(archiveVersion),
            "conversations": .arr(conversations.map { pick($0, conversationColumns) }),
            "messages": .arr(messages.map { pick($0, messageColumns) }),
            "attachments": .arr(attachments.map { pick($0, attachmentColumns) }),
        ])
    }

    private static func mergeRows(_ local: inout [[String: RJ]], _ incoming: [RJ], _ columns: [String],
                                  _ counts: inout [String: Int], _ prefix: String) {
        var byID: [String: Int] = [:]
        for (index, row) in local.enumerated() {
            if let id = row["id"]?.string { byID[id] = index }
        }
        for row in incoming {
            guard let id = row["id"].string, !id.isEmpty else {
                counts[prefix + "_rejected", default: 0] += 1
                continue
            }
            guard let position = byID[id] else {
                var merged: [String: RJ] = [:]
                for key in columns { merged[key] = row[key] }
                merged["deleted"] = .int(0)
                local.append(merged)
                byID[id] = local.count - 1
                counts[prefix + "_inserted", default: 0] += 1
                continue
            }
            if local[position]["deleted"]?.truthy == true {
                counts[prefix + "_skipped_tombstoned", default: 0] += 1
                continue
            }
            let incomingUpdated = row["updated_ms"].double ?? 0
            let localUpdated = local[position]["updated_ms"]?.double ?? 0
            if incomingUpdated > localUpdated {
                for key in columns { local[position][key] = row[key] }
                counts[prefix + "_updated", default: 0] += 1
            } else {
                counts[prefix + "_skipped_older", default: 0] += 1
            }
        }
    }

    /// §11. Merge an archive into a snapshot. Nothing is ever deleted; a local tombstone always wins.
    static func mergeChatArchive(_ snapshot: RJ, _ archive: RJ) -> RJ {
        var conversations = rows(snapshot, "conversations").map { $0.object ?? [:] }
        var messages = rows(snapshot, "messages").map { $0.object ?? [:] }
        var attachments = rows(snapshot, "attachments").map { $0.object ?? [:] }

        var counts: [String: Int] = [:]
        for prefix in ["conversations", "messages", "attachments"] {
            for suffix in ["inserted", "updated", "skipped_older", "skipped_tombstoned", "rejected"] {
                counts[prefix + "_" + suffix] = 0
            }
        }
        counts["messages_skipped_orphan"] = 0

        func result(_ error: RJ) -> RJ {
            .obj([
                "snapshot": .obj([
                    "conversations": .arr(conversations.map { RJ.obj($0) }),
                    "messages": .arr(messages.map { RJ.obj($0) }),
                    "attachments": .arr(attachments.map { RJ.obj($0) }),
                ]),
                "counts": .obj(counts.mapValues { RJ.int($0) }),
                "error": error,
            ])
        }

        guard archive["format"].string == archiveFormat else { return result(.str("bad_format")) }
        if (archive["format_version"].double ?? 0) > Double(archiveVersion) {
            return result(.str("newer_version"))
        }

        mergeRows(&conversations, archive["conversations"].array ?? [], conversationColumns,
                  &counts, "conversations")
        var liveIDs = Set<String>()
        for row in conversations where row["deleted"]?.truthy != true {
            if let id = row["id"]?.string { liveIDs.insert(id) }
        }
        var keptMessages: [RJ] = []
        for row in archive["messages"].array ?? [] {
            if liveIDs.contains(row["conversation_id"].string ?? "") {
                keptMessages.append(row)
            } else {
                counts["messages_skipped_orphan", default: 0] += 1
            }
        }
        mergeRows(&messages, keptMessages, messageColumns, &counts, "messages")
        mergeRows(&attachments, archive["attachments"].array ?? [], attachmentColumns,
                  &counts, "attachments")
        return result(.null)
    }

    // MARK: - §10 Export

    static let maxSlugChars = 40
    static let reSlugDrop = Rx("[^a-z0-9]+")

    /// A filename stem from a conversation title: lowercase, runs of anything else become one `-`,
    /// trimmed, capped. An empty title gives "chat", so a file is never named by an accident.
    static func exportSlug(_ title: String) -> String {
        var slug = reSlugDrop.sub(title.lowercased(), "-").rStrip("-")
        slug = cpCut(slug, maxSlugChars).rStrip("-")
        return slug.isEmpty ? "chat" : slug
    }

    /// §10 "Export as Markdown". A readable transcript. Attachment *contents* are never inlined —
    /// only their names, because the excerpt was a redacted copy of a file the user still has.
    static func conversationMarkdown(conversation: RJ, messages: [RJ], attachments: [RJ],
                                     localDay: String, provider: String? = nil) -> RJ {
        var byID: [String: RJ] = [:]
        for attachment in attachments {
            if let id = attachment["id"].string { byID[id] = attachment }
        }
        let rows = messages.stableSorted { a, b in
            let asq = a["seq"].double ?? 0
            let bsq = b["seq"].double ?? 0
            if asq != bsq { return asq < bsq }
            return (a["variant_index"].double ?? 0) < (b["variant_index"].double ?? 0)
        }
        let shown = latestVariants(rows)

        let title = conversation["title"].string.flatMap { $0.isEmpty ? nil : $0 } ?? "New chat"
        var header = [localDay, "\(shown.count) messages"]
        if let provider { header.append(provider) }
        var lines = ["# " + collapseWS(title), "", header.joined(separator: " · "), "", "---"]

        for message in shown {
            lines.append("")
            let speaker = message["role"].string == "assistant" ? "Coach" : "You"
            lines.append("**\(speaker):** " + (message["content"].string ?? ""))
            let names = (message["attachment_ids"].array ?? []).compactMap { id -> String? in
                guard let key = id.string else { return nil }
                return byID[key]?["filename"].string ?? key
            }
            if !names.isEmpty {
                lines.append("")
                lines.append("Attached: " + names.joined(separator: ", "))
            }
            let refs = message["record_refs"].array ?? []
            if !refs.isEmpty {
                lines.append("")
                lines.append("Used records: " + refs.map { ref in
                    let name = ref["title"].string ?? ref["record_id"].string ?? ""
                    return "\(name) — \(ref["date"].string ?? "")"
                }.joined(separator: "; "))
            }
        }
        return .obj([
            "text": .str(lines.joined(separator: "\n") + "\n"),
            "filename": .str("\(exportSlug(title))-\(localDay).md"),
        ])
    }

    /// §10 "Export as JSON": one conversation in the §11 archive shape, so a file exported from one
    /// chat imports through exactly the same reader as a full backup.
    static func conversationJSON(conversation: RJ, messages: [RJ], attachments: [RJ],
                                 localDay: String) -> RJ {
        let id = conversation["id"].string ?? ""
        let snapshot: RJ = .obj([
            "conversations": .arr([conversation]),
            "messages": .arr(messages.filter { $0["conversation_id"].string == id }),
            "attachments": .arr(attachments),
        ])
        let title = conversation["title"].string.flatMap { $0.isEmpty ? nil : $0 } ?? "New chat"
        return .obj([
            "archive": chatArchive(snapshot),
            "filename": .str("\(exportSlug(title))-\(localDay).json"),
        ])
    }

    /// §10 "Regenerate": which user turn to re-send, and what the new reply row looks like.
    ///
    /// Nothing is deleted — the new reply keeps the seq it replaces and takes the next variant
    /// index, so the stepper can walk the versions. `regenerated_from` always points at the FIRST
    /// variant, so a chain of regenerations stays a flat set rather than a linked list.
    static func regeneratePlan(messages: [RJ], seq: Int) -> RJ {
        let rows = messages.stableSorted { a, b in
            let asq = a["seq"].double ?? 0
            let bsq = b["seq"].double ?? 0
            if asq != bsq { return asq < bsq }
            return (a["variant_index"].double ?? 0) < (b["variant_index"].double ?? 0)
        }
        let variants = rows.filter {
            Int($0["seq"].double ?? -1) == seq && $0["role"].string == "assistant"
        }
        guard let first = variants.first else {
            return .obj(["ok": .bool(false), "reason": .str("not_a_reply")])
        }
        var prompt: RJ?
        for message in rows where Int(message["seq"].double ?? 0) < seq && message["role"].string == "user" {
            prompt = message
        }
        guard let prompt else {
            return .obj(["ok": .bool(false), "reason": .str("no_prompt")])
        }
        let nextVariant = (variants.map { Int($0["variant_index"].double ?? 0) }.max() ?? 0) + 1
        return .obj([
            "ok": .bool(true),
            "prompt_id": prompt["id"],
            "prompt_seq": prompt["seq"],
            "seq": .int(seq),
            "variant_index": .int(nextVariant),
            "regenerated_from": first["regenerated_from"].isNull ? first["id"] : first["regenerated_from"],
            "attachment_ids": .arr(prompt["attachment_ids"].array ?? []),
        ])
    }

    /// Only the newest version of each reply is shown; the stepper reaches the rest.
    static func latestVariants(_ rows: [RJ]) -> [RJ] {
        var best: [Int: RJ] = [:]
        var order: [Int] = []
        for row in rows {
            let seq = Int(row["seq"].double ?? 0)
            if best[seq] == nil { order.append(seq) }
            if let existing = best[seq],
               Int(existing["variant_index"].double ?? 0) >= Int(row["variant_index"].double ?? 0) {
                continue
            }
            best[seq] = row
        }
        return order.compactMap { best[$0] }
    }

    // MARK: - Vector dispatch

    /// Mirrors `run_case` in scripts/coach_reference.py.
    static func runCase(function: String, input: RJ) -> RJ {
        switch function {
        case "parse_blocks":
            return parseBlocks(input["markdown"].string ?? "")
        case "parse_chart_spec":
            return parseChartSpec(input["raw"].string ?? "")
        case "repair_chart_json":
            return .obj(["text": .str(repairChartJSON(input["raw"].string ?? ""))])
        case "attachment_excerpt":
            let pages = (input["pages"].array ?? []).map { $0.string ?? "" }
            let maxPages = input["max_pages"].double.map { Int($0) } ?? maxAttachmentPages
            let maxChars = input["max_chars"].double.map { Int($0) } ?? maxAttachmentChars
            return attachmentExcerpt(pages, maxPages: maxPages, maxChars: maxChars)
        case "conversation_title":
            return conversationTitle(input["text"].string ?? "")
        case "resolve_data_sources":
            return resolveDataSources(available: input["available"], consents: input["consents"],
                                      switches: input["switches"],
                                      workoutsAvailable: input["workouts_available"].bool ?? false)
        case "gallery_for":
            let catalog = input["catalog"].isNull ? CoachCatalog.gallery : input["catalog"]
            return galleryFor(input["sources"], catalog: catalog)
        case "chips_for":
            let catalog = input["catalog"].isNull ? CoachCatalog.gallery : input["catalog"]
            return chipsFor(goal: input["goal"].string, hasWorkouts: input["has_workouts"].bool ?? false,
                            hasSleep: input["has_sleep"].bool ?? false,
                            sources: input["sources"], catalog: catalog)
        case "chat_archive":
            if !input["archive"].isNull {
                return mergeChatArchive(input["snapshot"], input["archive"])
            }
            return chatArchive(input["snapshot"])
        case "export":
            switch input["op"].string ?? "" {
            case "markdown":
                return conversationMarkdown(conversation: input["conversation"],
                                            messages: input["messages"].array ?? [],
                                            attachments: input["attachments"].array ?? [],
                                            localDay: input["local_day"].string ?? "",
                                            provider: input["provider"].string)
            case "json":
                return conversationJSON(conversation: input["conversation"],
                                        messages: input["messages"].array ?? [],
                                        attachments: input["attachments"].array ?? [],
                                        localDay: input["local_day"].string ?? "")
            case "regenerate":
                return regeneratePlan(messages: input["messages"].array ?? [],
                                      seq: Int(input["seq"].double ?? 0))
            case "slug":
                return .obj(["slug": .str(exportSlug(input["title"].string ?? ""))])
            default:
                return .obj(["error": .str("unknown export op")])
            }
        default:
            return .obj(["error": .str("unknown function \(function)")])
        }
    }
}

private extension Character {
    /// ASCII 0-9 only; `isNumber` would also accept other Unicode digits.
    var isASCIIDigit: Bool { self >= "0" && self <= "9" }
}

/// The two shared catalogs, bundled verbatim (`Coach/Resources/`). A parity test in
/// `scripts/coach_contract_check.py` byte-compares them with `shared/coach/`.
enum CoachCatalog {
    static let gallery: RJ = load("prompt_gallery")
    static let chartSpec: RJ = load("chart_spec")

    /// The `## Charts` section appended to the system prompt (docs/coach.md §5).
    static var chartsPromptSection: String { chartSpec["prompt"]["charts_section"].string ?? "" }
    static var chartGuardrails: String { chartSpec["prompt"]["guardrails"].string ?? "" }

    private static func load(_ name: String) -> RJ {
        for bundle in [Bundle.main, Bundle(for: BundleMarker.self)] {
            if let url = bundle.url(forResource: name, withExtension: "json"),
               let text = try? String(contentsOf: url, encoding: .utf8),
               let parsed = RJ.parse(text) {
                return parsed
            }
        }
        return .obj([:])
    }

    private final class BundleMarker {}
}
