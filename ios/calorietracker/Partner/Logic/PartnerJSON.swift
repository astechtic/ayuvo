import Foundation

// Python-compatible JSON and string helpers for the Partner Health Sync port of
// `scripts/partner_reference.py` (docs/partner-sync.md). Values use the shared `RJ` enum.
//
// * `PartnerJSON.parse` follows `json.loads`: ints stay `.int` (no `.`/exponent), floats `.num`,
//   duplicate keys keep the last value, NaN/Infinity accepted, control characters in strings rejected.
// * `PartnerJSON.canonical` follows `json.dumps(sort_keys=True, separators=(",", ":"), ensure_ascii=False)`
//   byte for byte: keys in code point order, "/" not escaped, controls as \u00xx.
// * `pyEq` / `pyLess` follow Python `==` / `<` (numbers by value, bools are numbers, strings by code point).
// * String comparisons and dictionary keys use UTF-8 bytes, never Swift's canonical-equivalence `==`.

nonisolated enum PartnerJSON {
    // MARK: Parse

    static func parse(_ text: String) -> RJ? { parse(Array(text.utf8)) }

    static func parse(_ data: Data) -> RJ? { parse([UInt8](data)) }

    static func parse(_ bytes: [UInt8]) -> RJ? {
        var parser = Parser(bytes: bytes)
        parser.skipWS()
        guard let value = parser.value() else { return nil }
        parser.skipWS()
        return parser.index == bytes.count ? value : nil
    }

    private struct Parser {
        let bytes: [UInt8]
        var index = 0
        var depth = 0

        init(bytes: [UInt8]) { self.bytes = bytes }

        mutating func skipWS() {
            while index < bytes.count, bytes[index] == 0x20 || bytes[index] == 0x09 || bytes[index] == 0x0A || bytes[index] == 0x0D {
                index += 1
            }
        }

        mutating func literal(_ word: String) -> Bool {
            let w = Array(word.utf8)
            guard index + w.count <= bytes.count, Array(bytes[index..<index + w.count]) == w else { return false }
            index += w.count
            return true
        }

        mutating func value() -> RJ? {
            guard index < bytes.count else { return nil }
            switch bytes[index] {
            case UInt8(ascii: "{"): return object()
            case UInt8(ascii: "["): return array()
            case UInt8(ascii: "\""): return string().map(RJ.str)
            case UInt8(ascii: "t"): return literal("true") ? .bool(true) : nil
            case UInt8(ascii: "f"): return literal("false") ? .bool(false) : nil
            case UInt8(ascii: "n"): return literal("null") ? .null : nil
            case UInt8(ascii: "N"): return literal("NaN") ? .num(.nan) : nil
            case UInt8(ascii: "I"): return literal("Infinity") ? .num(.infinity) : nil
            default:
                if bytes[index] == UInt8(ascii: "-"), literal("-Infinity") { return .num(-.infinity) }
                return number()
            }
        }

        mutating func object() -> RJ? {
            depth += 1
            defer { depth -= 1 }
            guard depth < 512 else { return nil }
            index += 1
            var out: [String: RJ] = [:]
            skipWS()
            if index < bytes.count, bytes[index] == UInt8(ascii: "}") { index += 1; return .obj(out) }
            while true {
                skipWS()
                guard index < bytes.count, bytes[index] == UInt8(ascii: "\""), let key = string() else { return nil }
                skipWS()
                guard index < bytes.count, bytes[index] == UInt8(ascii: ":") else { return nil }
                index += 1
                skipWS()
                guard let v = value() else { return nil }
                out[key] = v
                skipWS()
                guard index < bytes.count else { return nil }
                if bytes[index] == UInt8(ascii: ",") { index += 1; continue }
                if bytes[index] == UInt8(ascii: "}") { index += 1; return .obj(out) }
                return nil
            }
        }

        mutating func array() -> RJ? {
            depth += 1
            defer { depth -= 1 }
            guard depth < 512 else { return nil }
            index += 1
            var out: [RJ] = []
            skipWS()
            if index < bytes.count, bytes[index] == UInt8(ascii: "]") { index += 1; return .arr(out) }
            while true {
                skipWS()
                guard let v = value() else { return nil }
                out.append(v)
                skipWS()
                guard index < bytes.count else { return nil }
                if bytes[index] == UInt8(ascii: ",") { index += 1; continue }
                if bytes[index] == UInt8(ascii: "]") { index += 1; return .arr(out) }
                return nil
            }
        }

        mutating func hex4() -> UInt32? {
            guard index + 4 <= bytes.count else { return nil }
            var v: UInt32 = 0
            for b in bytes[index..<index + 4] {
                v <<= 4
                switch b {
                case 0x30...0x39: v |= UInt32(b - 0x30)
                case 0x61...0x66: v |= UInt32(b - 0x61 + 10)
                case 0x41...0x46: v |= UInt32(b - 0x41 + 10)
                default: return nil
                }
            }
            index += 4
            return v
        }

        /// Python strict mode: raw control characters (< 0x20) are not allowed inside strings.
        mutating func string() -> String? {
            index += 1
            var out: [UInt8] = []
            while index < bytes.count {
                let b = bytes[index]
                if b == UInt8(ascii: "\"") {
                    index += 1
                    return String(bytes: out, encoding: .utf8)
                }
                if b < 0x20 { return nil }
                if b != UInt8(ascii: "\\") {
                    out.append(b)
                    index += 1
                    continue
                }
                index += 1
                guard index < bytes.count else { return nil }
                let e = bytes[index]
                index += 1
                switch e {
                case UInt8(ascii: "\""): out.append(0x22)
                case UInt8(ascii: "\\"): out.append(0x5C)
                case UInt8(ascii: "/"): out.append(0x2F)
                case UInt8(ascii: "b"): out.append(0x08)
                case UInt8(ascii: "f"): out.append(0x0C)
                case UInt8(ascii: "n"): out.append(0x0A)
                case UInt8(ascii: "r"): out.append(0x0D)
                case UInt8(ascii: "t"): out.append(0x09)
                case UInt8(ascii: "u"):
                    guard var code = hex4() else { return nil }
                    if (0xD800...0xDBFF).contains(code) {
                        // A pair is required; lone surrogates cannot be represented in a Swift String.
                        guard index + 6 <= bytes.count, bytes[index] == UInt8(ascii: "\\"), bytes[index + 1] == UInt8(ascii: "u") else { return nil }
                        index += 2
                        guard let low = hex4(), (0xDC00...0xDFFF).contains(low) else { return nil }
                        code = 0x10000 + ((code - 0xD800) << 10) + (low - 0xDC00)
                    } else if (0xDC00...0xDFFF).contains(code) {
                        return nil
                    }
                    guard let scalar = Unicode.Scalar(code) else { return nil }
                    out.append(contentsOf: Array(String(Character(scalar)).utf8))
                default:
                    return nil
                }
            }
            return nil
        }

        mutating func number() -> RJ? {
            let start = index
            if index < bytes.count, bytes[index] == UInt8(ascii: "-") { index += 1 }
            guard index < bytes.count, isDigit(bytes[index]) else { return nil }
            if bytes[index] == UInt8(ascii: "0") {
                index += 1
            } else {
                while index < bytes.count, isDigit(bytes[index]) { index += 1 }
            }
            var isFloat = false
            if index + 1 < bytes.count, bytes[index] == UInt8(ascii: "."), isDigit(bytes[index + 1]) {
                isFloat = true
                index += 1
                while index < bytes.count, isDigit(bytes[index]) { index += 1 }
            }
            if index < bytes.count, bytes[index] == UInt8(ascii: "e") || bytes[index] == UInt8(ascii: "E") {
                var j = index + 1
                if j < bytes.count, bytes[j] == UInt8(ascii: "+") || bytes[j] == UInt8(ascii: "-") { j += 1 }
                if j < bytes.count, isDigit(bytes[j]) {
                    isFloat = true
                    index = j
                    while index < bytes.count, isDigit(bytes[index]) { index += 1 }
                }
            }
            let text = String(decoding: bytes[start..<index], as: UTF8.self)
            if !isFloat, let i = Int(text) { return .int(i) }
            guard let d = Double(text) else { return nil }
            return .num(d)
        }

        func isDigit(_ b: UInt8) -> Bool { b >= 0x30 && b <= 0x39 }
    }

    // MARK: Canonical dump

    /// `json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=False)`.
    static func canonical(_ value: RJ) -> String {
        var out = ""
        write(value, into: &out)
        return out
    }

    static func canonicalData(_ value: RJ) -> Data { Data(canonical(value).utf8) }

    private static func write(_ value: RJ, into out: inout String) {
        switch value {
        case .null: out += "null"
        case .bool(let b): out += b ? "true" : "false"
        case .int(let i): out += String(i)
        case .num(let d): out += pyFloatRepr(d)
        case .str(let s): writeString(s, into: &out)
        case .arr(let a):
            out += "["
            for (i, v) in a.enumerated() {
                if i > 0 { out += "," }
                write(v, into: &out)
            }
            out += "]"
        case .obj(let o):
            out += "{"
            for (i, key) in o.keys.sorted(by: { utf8Less($0, $1) }).enumerated() {
                if i > 0 { out += "," }
                writeString(key, into: &out)
                out += ":"
                write(o[key]!, into: &out)
            }
            out += "}"
        }
    }

    private static func writeString(_ s: String, into out: inout String) {
        out += "\""
        for scalar in s.unicodeScalars {
            switch scalar.value {
            case 0x22: out += "\\\""
            case 0x5C: out += "\\\\"
            case 0x0A: out += "\\n"
            case 0x0D: out += "\\r"
            case 0x09: out += "\\t"
            case 0x08: out += "\\b"
            case 0x0C: out += "\\f"
            case 0x00..<0x20: out += String(format: "\\u%04x", scalar.value)
            default: out.unicodeScalars.append(scalar)
            }
        }
        out += "\""
    }

    /// Python `repr(float)` (shortest round-trip; Swift's description uses the same algorithm and exponent form).
    static func pyFloatRepr(_ d: Double) -> String {
        if d.isNaN { return "NaN" }
        if d.isInfinite { return d < 0 ? "-Infinity" : "Infinity" }
        return "\(d)"
    }
}

// MARK: - Python semantics on RJ

nonisolated func utf8Less(_ a: String, _ b: String) -> Bool { a.utf8.lexicographicallyPrecedes(b.utf8) }

nonisolated func utf8Equal(_ a: String, _ b: String) -> Bool { a.utf8.elementsEqual(b.utf8) }

nonisolated extension RJ {
    /// Numeric value as Python sees it (`True == 1`).
    var pyNumber: Double? {
        switch self {
        case .bool(let b): b ? 1 : 0
        case .int(let i): Double(i)
        case .num(let d): d
        default: nil
        }
    }

    /// `isinstance(v, int) and not isinstance(v, bool)`.
    var pyInt: Int? { if case .int(let i) = self { return i } else { return nil } }

    /// `isinstance(v, (int, float)) and not isinstance(v, bool)`.
    var isPyNum: Bool {
        switch self {
        case .int, .num: true
        default: false
        }
    }

    var isObject: Bool { if case .obj = self { return true } else { return false } }

    /// `dict.get(key)` on an object, `nil` when absent (presence differs from a JSON null).
    func get(_ key: String) -> RJ? { object?[key] }

    func has(_ key: String) -> Bool { object?[key] != nil }
}

/// Python `a == b` on JSON values.
nonisolated func pyEq(_ a: RJ, _ b: RJ) -> Bool {
    switch (a, b) {
    case (.null, .null): return true
    case (.str(let x), .str(let y)): return utf8Equal(x, y)
    case (.arr(let x), .arr(let y)): return x.count == y.count && zip(x, y).allSatisfy { pyEq($0, $1) }
    case (.obj(let x), .obj(let y)):
        guard x.count == y.count else { return false }
        return x.allSatisfy { k, v in y[k].map { pyEq(v, $0) } ?? false }
    case (.int(let x), .int(let y)): return x == y
    default:
        if let x = a.pyNumber, let y = b.pyNumber { return x == y }
        return false
    }
}

/// Python `a < b` for the scalar sort keys the reference uses (numbers, strings; arrays element-wise).
nonisolated func pyLess(_ a: RJ, _ b: RJ) -> Bool {
    switch (a, b) {
    case (.str(let x), .str(let y)): return utf8Less(x, y)
    case (.int(let x), .int(let y)): return x < y
    case (.arr(let x), .arr(let y)):
        for (u, v) in zip(x, y) where !pyEq(u, v) { return pyLess(u, v) }
        return x.count < y.count
    default:
        if let x = a.pyNumber, let y = b.pyNumber { return x < y }
        return false
    }
}

/// `x in list` (Python membership by `==`).
nonisolated func pyIn(_ x: RJ, _ list: [RJ]) -> Bool { list.contains { pyEq(x, $0) } }

nonisolated func pyIn(_ x: RJ, _ list: [String]) -> Bool {
    guard case .str(let s) = x else { return false }
    return list.contains { utf8Equal($0, s) }
}

/// Lexicographic tuple comparison of Python sort keys; stable sort (Python `sorted` is stable).
nonisolated func pyStableSorted<T>(_ items: [T], reverse: Bool = false, key: (T) -> [RJ]) -> [T] {
    let keyed = items.enumerated().map { (offset: $0.offset, key: key($0.element), item: $0.element) }
    return keyed.sorted { l, r in
        let lt = reverse ? pyLess(.arr(r.key), .arr(l.key)) : pyLess(.arr(l.key), .arr(r.key))
        if lt { return true }
        let gt = reverse ? pyLess(.arr(l.key), .arr(r.key)) : pyLess(.arr(r.key), .arr(l.key))
        if gt { return false }
        return l.offset < r.offset
    }.map(\.item)
}

/// A `(type, record_id)` tuple key compared by UTF-8 bytes (Python tuple order on str = code point order).
nonisolated struct PartnerKey: Hashable, Comparable, Sendable {
    let type: RJ
    let recordID: RJ
    private let t: [UInt8]
    private let r: [UInt8]

    init(_ type: RJ, _ recordID: RJ) {
        self.type = type
        self.recordID = recordID
        t = Self.bytes(type)
        r = Self.bytes(recordID)
    }

    init(type: String, recordID: String) { self.init(.str(type), .str(recordID)) }

    private static func bytes(_ v: RJ) -> [UInt8] {
        if case .str(let s) = v { return Array(s.utf8) }
        return Array(PartnerJSON.canonical(v).utf8) + [0xFF]
    }

    static func == (a: PartnerKey, b: PartnerKey) -> Bool { a.t == b.t && a.r == b.r }
    func hash(into h: inout Hasher) { h.combine(t); h.combine(r) }
    static func < (a: PartnerKey, b: PartnerKey) -> Bool {
        if a.t != b.t { return a.t.lexicographicallyPrecedes(b.t) }
        return a.r.lexicographicallyPrecedes(b.r)
    }
}

/// Insertion-ordered dictionary keyed by UTF-8 string bytes (Python dict semantics: first insertion fixes the
/// position, the last assignment wins the value).
nonisolated struct PyOrderedDict<Value> {
    private(set) var keys: [String] = []
    private var values: [[UInt8]: Value] = [:]

    subscript(key: String) -> Value? {
        get { values[Array(key.utf8)] }
        set {
            let k = Array(key.utf8)
            if let newValue {
                if values[k] == nil { keys.append(key) }
                values[k] = newValue
            } else if values.removeValue(forKey: k) != nil {
                keys.removeAll { Array($0.utf8) == k }
            }
        }
    }

    func contains(_ key: String) -> Bool { values[Array(key.utf8)] != nil }
}

// MARK: - Python string helpers

nonisolated enum PyStr {
    /// `str.isspace()` code points (Unicode White_Space plus the U+001C…U+001F separators Python includes).
    static func isSpace(_ s: Unicode.Scalar) -> Bool {
        switch s.value {
        case 0x09...0x0D, 0x1C...0x20, 0x85, 0xA0, 0x1680, 0x2000...0x200A, 0x2028, 0x2029, 0x202F, 0x205F, 0x3000: true
        default: false
        }
    }

    static func string(_ scalars: some Sequence<Unicode.Scalar>) -> String {
        var view = String.UnicodeScalarView()
        view.append(contentsOf: scalars)
        return String(view)
    }

    static func isASCIISpace(_ s: Unicode.Scalar) -> Bool { [0x20, 0x09, 0x0A, 0x0D, 0x0B, 0x0C].contains(s.value) }

    /// `s.strip(" \t\n\r\x0b\x0c")` (ASCII whitespace only).
    static func stripASCII(_ s: String) -> String {
        let a = Array(s.unicodeScalars)
        var lo = 0, hi = a.count
        while lo < hi, isASCIISpace(a[lo]) { lo += 1 }
        while hi > lo, isASCIISpace(a[hi - 1]) { hi -= 1 }
        return string(a[lo..<hi])
    }

    /// `s.rstrip(" \t\n\r\x0b\x0c")`.
    static func rstripASCII(_ s: String) -> String {
        let a = Array(s.unicodeScalars)
        var hi = a.count
        while hi > 0, isASCIISpace(a[hi - 1]) { hi -= 1 }
        return string(a[0..<hi])
    }

    /// `s.strip()`.
    static func strip(_ s: String) -> String {
        let a = Array(s.unicodeScalars)
        var lo = 0, hi = a.count
        while lo < hi, isSpace(a[lo]) { lo += 1 }
        while hi > lo, isSpace(a[hi - 1]) { hi -= 1 }
        return string(a[lo..<hi])
    }

    /// `s.rstrip()`.
    static func rstrip(_ s: String) -> String {
        let a = Array(s.unicodeScalars)
        var hi = a.count
        while hi > 0, isSpace(a[hi - 1]) { hi -= 1 }
        return string(a[0..<hi])
    }

    /// `s.split()` (no argument).
    static func splitWS(_ s: String) -> [String] {
        var out: [String] = []
        var cur: [Unicode.Scalar] = []
        for c in s.unicodeScalars {
            if isSpace(c) {
                if !cur.isEmpty { out.append(string(cur)); cur = [] }
            } else {
                cur.append(c)
            }
        }
        if !cur.isEmpty { out.append(string(cur)) }
        return out
    }

    /// `s[a:b]` on code points (non-negative bounds, clamped).
    static func slice(_ s: String, _ a: Int, _ b: Int? = nil) -> String {
        let scalars = Array(s.unicodeScalars)
        let lo = max(0, min(a, scalars.count))
        let hi = max(lo, min(b ?? scalars.count, scalars.count))
        return string(scalars[lo..<hi])
    }

    static func length(_ s: String) -> Int { s.unicodeScalars.count }

    static func hasPrefix(_ s: String, _ p: String) -> Bool { s.utf8.starts(with: p.utf8) }

    static func hasSuffix(_ s: String, _ p: String) -> Bool { s.utf8.reversed().starts(with: p.utf8.reversed()) }

    /// Whole-string matching (the reference's `_full`): the pattern body must cover every byte.
    static func dollarBody(_ s: String) -> [UInt8] { Array(s.utf8) }

    /// `re.split("[ \t\n\r\x0b\x0c]+", s)` with empty pieces dropped (ASCII whitespace only).
    static func splitASCIIWS(_ s: String) -> [String] {
        s.unicodeScalars.split(whereSeparator: { [0x20, 0x09, 0x0A, 0x0D, 0x0B, 0x0C].contains($0.value) }).map { string($0) }
    }

    private static func isDigit(_ b: UInt8) -> Bool { b >= 0x30 && b <= 0x39 }
    private static func isLowerHex(_ b: UInt8) -> Bool { isDigit(b) || (b >= 0x61 && b <= 0x66) }

    /// `^[0-9]{4}-[0-9]{2}-[0-9]{2}$`
    static func isDay(_ s: String) -> Bool {
        let b = dollarBody(s)
        guard b.count == 10 else { return false }
        for (i, c) in b.enumerated() {
            if i == 4 || i == 7 { if c != UInt8(ascii: "-") { return false } } else if !isDigit(c) { return false }
        }
        return true
    }

    /// `^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$`
    static func isUUID(_ s: String) -> Bool {
        let b = dollarBody(s)
        guard b.count == 36 else { return false }
        for (i, c) in b.enumerated() {
            if [8, 13, 18, 23].contains(i) { if c != UInt8(ascii: "-") { return false } } else if !isLowerHex(c) { return false }
        }
        return true
    }

    /// `^[0-9a-f]{64}$`
    static func isSHA256Hex(_ s: String) -> Bool {
        let b = dollarBody(s)
        return b.count == 64 && b.allSatisfy(isLowerHex)
    }

    /// `^[0-9a-fA-F.:%a-z]+:[0-9]{1,5}$`
    static func isHost(_ s: String) -> Bool {
        let b = dollarBody(s)
        guard let colon = b.lastIndex(of: UInt8(ascii: ":")), colon > 0 else { return false }
        let port = b[(colon + 1)...]
        guard (1...5).contains(port.count), port.allSatisfy(isDigit) else { return false }
        return b[..<colon].allSatisfy { c in
            isDigit(c) || (c >= 0x61 && c <= 0x7A) || (c >= 0x41 && c <= 0x46) || c == UInt8(ascii: ".")
                || c == UInt8(ascii: ":") || c == UInt8(ascii: "%")
        }
    }

    /// Python `str(v)` / `"%s" % v` for the scalar values ids are built from.
    static func str(_ v: RJ) -> String {
        switch v {
        case .str(let s): s
        case .int(let i): String(i)
        case .num(let d): d.isNaN ? "nan" : d.isInfinite ? (d < 0 ? "-inf" : "inf") : "\(d)"
        case .bool(let b): b ? "True" : "False"
        case .null: "None"
        default: PartnerJSON.canonical(v)
        }
    }

    /// Python `int(v)` (truncation toward zero) for numbers.
    static func int(_ v: RJ) -> Int? {
        switch v {
        case .int(let i): i
        case .bool(let b): b ? 1 : 0
        case .num(let d): d.isFinite ? Int(d.rounded(.towardZero)) : nil
        case .str(let s): Int(strip(s))
        default: nil
        }
    }
}

// MARK: - Hex / base64url

nonisolated enum PartnerEncoding {
    private static let alphabet = Array("ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_".utf8)

    /// `b64url_encode`: URL-safe alphabet, no padding.
    static func b64urlEncode(_ data: Data) -> String {
        let bytes = [UInt8](data)
        var out: [UInt8] = []
        out.reserveCapacity((bytes.count + 2) / 3 * 4)
        var i = 0
        while i + 3 <= bytes.count {
            let n = UInt32(bytes[i]) << 16 | UInt32(bytes[i + 1]) << 8 | UInt32(bytes[i + 2])
            out += [alphabet[Int(n >> 18 & 63)], alphabet[Int(n >> 12 & 63)], alphabet[Int(n >> 6 & 63)], alphabet[Int(n & 63)]]
            i += 3
        }
        let rest = bytes.count - i
        if rest == 1 {
            let n = UInt32(bytes[i]) << 16
            out += [alphabet[Int(n >> 18 & 63)], alphabet[Int(n >> 12 & 63)]]
        } else if rest == 2 {
            let n = UInt32(bytes[i]) << 16 | UInt32(bytes[i + 1]) << 8
            out += [alphabet[Int(n >> 18 & 63)], alphabet[Int(n >> 12 & 63)], alphabet[Int(n >> 6 & 63)]]
        }
        return String(decoding: out, as: UTF8.self)
    }

    /// `b64url_decode`: strict alphabet `[A-Za-z0-9_-]`, no padding, length % 4 != 1. Like Python, unused
    /// trailing bits are ignored. `nil` when invalid (a trailing newline never decodes in the reference either).
    static func b64urlDecode(_ text: String) -> Data? {
        let chars = Array(text.utf8)
        guard !chars.isEmpty, chars.count % 4 != 1 else { return nil }
        var values: [UInt8] = []
        values.reserveCapacity(chars.count)
        for c in chars {
            switch c {
            case 0x41...0x5A: values.append(c - 0x41)
            case 0x61...0x7A: values.append(c - 0x61 + 26)
            case 0x30...0x39: values.append(c - 0x30 + 52)
            case UInt8(ascii: "-"): values.append(62)
            case UInt8(ascii: "_"): values.append(63)
            default: return nil
            }
        }
        var out: [UInt8] = []
        out.reserveCapacity(values.count * 3 / 4)
        var acc: UInt32 = 0
        var bits = 0
        for v in values {
            acc = acc << 6 | UInt32(v)
            bits += 6
            if bits >= 8 {
                bits -= 8
                out.append(UInt8(acc >> UInt32(bits) & 0xFF))
            }
        }
        return Data(out)
    }

    static func b64urlDecode(_ value: RJ) -> Data? {
        guard case .str(let s) = value else { return nil }
        return b64urlDecode(s)
    }

    static func hex(_ data: some Sequence<UInt8>) -> String {
        data.map { String(format: "%02x", $0) }.joined()
    }

    /// `bytes.fromhex` (lower/upper case, no separators). `nil` when invalid.
    static func fromHex(_ text: String) -> Data? {
        let chars = Array(text.utf8)
        guard chars.count % 2 == 0 else { return nil }
        var out = Data(capacity: chars.count / 2)
        var i = 0
        while i < chars.count {
            guard let hi = nibble(chars[i]), let lo = nibble(chars[i + 1]) else { return nil }
            out.append(hi << 4 | lo)
            i += 2
        }
        return out
    }

    private static func nibble(_ c: UInt8) -> UInt8? {
        switch c {
        case 0x30...0x39: c - 0x30
        case 0x61...0x66: c - 0x61 + 10
        case 0x41...0x46: c - 0x41 + 10
        default: nil
        }
    }
}
