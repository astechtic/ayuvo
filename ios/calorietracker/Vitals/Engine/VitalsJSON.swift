import Foundation

// JSON for the camera-vitals contract (docs/camera-vitals.md). The engine's results are `RJ` values whose int / float
// kinds follow the Python reference exactly, so `VitalsJSON.encode` writes what
// `json.dumps(result, sort_keys=True, separators=(",", ":"))` writes in `scripts/vitals_reference.py`, and
// `VitalsJSON.parse` keeps the int / float distinction of the text (Foundation's parser does not).

nonisolated enum VitalsJSON {
    enum ParseError: Error { case invalid(Int) }

    // MARK: Parsing

    /// Strict JSON parser: integers without '.', 'e' or 'E' become `.int`, every other number `.num` (correctly
    /// rounded by `Double(String)`).
    static func parse(_ data: Data) throws -> RJ {
        var parser = Parser(bytes: [UInt8](data))
        parser.skipWhitespace()
        let value = try parser.value()
        parser.skipWhitespace()
        guard parser.index == parser.bytes.count else { throw ParseError.invalid(parser.index) }
        return value
    }

    static func parse(_ text: String) throws -> RJ { try parse(Data(text.utf8)) }

    private struct Parser {
        let bytes: [UInt8]
        var index = 0

        mutating func skipWhitespace() {
            while index < bytes.count, bytes[index] == 0x20 || bytes[index] == 0x0A || bytes[index] == 0x0D || bytes[index] == 0x09 {
                index += 1
            }
        }

        mutating func expect(_ literal: String) throws {
            for b in literal.utf8 {
                guard index < bytes.count, bytes[index] == b else { throw ParseError.invalid(index) }
                index += 1
            }
        }

        mutating func value() throws -> RJ {
            guard index < bytes.count else { throw ParseError.invalid(index) }
            switch bytes[index] {
            case UInt8(ascii: "{"):
                index += 1
                var out: [String: RJ] = [:]
                skipWhitespace()
                if index < bytes.count, bytes[index] == UInt8(ascii: "}") { index += 1; return .obj(out) }
                while true {
                    skipWhitespace()
                    let key = try string()
                    skipWhitespace()
                    try expect(":")
                    skipWhitespace()
                    out[key] = try value()
                    skipWhitespace()
                    guard index < bytes.count else { throw ParseError.invalid(index) }
                    if bytes[index] == UInt8(ascii: ",") { index += 1; continue }
                    if bytes[index] == UInt8(ascii: "}") { index += 1; return .obj(out) }
                    throw ParseError.invalid(index)
                }
            case UInt8(ascii: "["):
                index += 1
                var out: [RJ] = []
                skipWhitespace()
                if index < bytes.count, bytes[index] == UInt8(ascii: "]") { index += 1; return .arr(out) }
                while true {
                    skipWhitespace()
                    out.append(try value())
                    skipWhitespace()
                    guard index < bytes.count else { throw ParseError.invalid(index) }
                    if bytes[index] == UInt8(ascii: ",") { index += 1; continue }
                    if bytes[index] == UInt8(ascii: "]") { index += 1; return .arr(out) }
                    throw ParseError.invalid(index)
                }
            case UInt8(ascii: "\""):
                return .str(try string())
            case UInt8(ascii: "t"):
                try expect("true"); return .bool(true)
            case UInt8(ascii: "f"):
                try expect("false"); return .bool(false)
            case UInt8(ascii: "n"):
                try expect("null"); return .null
            default:
                return try number()
            }
        }

        mutating func number() throws -> RJ {
            let start = index
            var isFloat = false
            while index < bytes.count {
                let b = bytes[index]
                if b == UInt8(ascii: ".") || b == UInt8(ascii: "e") || b == UInt8(ascii: "E") {
                    isFloat = true
                } else if !(b == UInt8(ascii: "-") || b == UInt8(ascii: "+") || (b >= 0x30 && b <= 0x39)) {
                    break
                }
                index += 1
            }
            guard index > start, let token = String(bytes: bytes[start..<index], encoding: .ascii) else {
                throw ParseError.invalid(start)
            }
            if !isFloat, let i = Int(token) { return .int(i) }
            guard let d = Double(token) else { throw ParseError.invalid(start) }
            return .num(d)
        }

        mutating func hex4() throws -> UInt16 {
            guard index + 4 <= bytes.count, let s = String(bytes: bytes[index..<index + 4], encoding: .ascii),
                  let v = UInt16(s, radix: 16) else { throw ParseError.invalid(index) }
            index += 4
            return v
        }

        mutating func string() throws -> String {
            try expect("\"")
            var units: [UInt16] = []
            var raw: [UInt8] = []
            func flushRaw() {
                if !raw.isEmpty { units.append(contentsOf: String(decoding: raw, as: UTF8.self).utf16); raw.removeAll() }
            }
            while true {
                guard index < bytes.count else { throw ParseError.invalid(index) }
                let b = bytes[index]
                index += 1
                if b == UInt8(ascii: "\"") { break }
                if b != UInt8(ascii: "\\") { raw.append(b); continue }
                flushRaw()
                guard index < bytes.count else { throw ParseError.invalid(index) }
                let e = bytes[index]
                index += 1
                switch e {
                case UInt8(ascii: "\""): units.append(0x22)
                case UInt8(ascii: "\\"): units.append(0x5C)
                case UInt8(ascii: "/"): units.append(0x2F)
                case UInt8(ascii: "b"): units.append(0x08)
                case UInt8(ascii: "f"): units.append(0x0C)
                case UInt8(ascii: "n"): units.append(0x0A)
                case UInt8(ascii: "r"): units.append(0x0D)
                case UInt8(ascii: "t"): units.append(0x09)
                case UInt8(ascii: "u"): units.append(try hex4())
                default: throw ParseError.invalid(index)
                }
            }
            flushRaw()
            return String(decoding: units, as: UTF16.self)
        }
    }

    // MARK: Encoding

    /// Python `json.dumps(value, sort_keys=True, separators=(",", ":"))` (ensure_ascii). Floats use the shortest
    /// round-trip form like Python's `repr`; non-finite floats are written as null (the reference never emits them).
    static func encode(_ value: RJ) -> String {
        var out = ""
        write(value, into: &out)
        return out
    }

    static func number(_ d: Double) -> String {
        guard d.isFinite else { return "null" }
        // Swift's description is the shortest round-trip digits with the same fixed / exponent switch as Python's
        // repr (exponent form below 1e-4 and from 1e16), e.g. 3.0, 0.0001, 1e-05, 1e+16.
        return "\(d)"
    }

    private static func write(_ value: RJ, into out: inout String) {
        switch value {
        case .null: out += "null"
        case .bool(let b): out += b ? "true" : "false"
        case .int(let i): out += String(i)
        case .num(let d): out += number(d)
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
            // Python sorts str keys by code point; UTF-16 order differs only above the BMP (never in this contract).
            for (i, key) in o.keys.sorted(by: { Array($0.unicodeScalars.map(\.value)).lexicographicallyPrecedes($1.unicodeScalars.map(\.value)) }).enumerated() {
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
        for u in s.utf16 {
            switch u {
            case 0x22: out += "\\\""
            case 0x5C: out += "\\\\"
            case 0x0A: out += "\\n"
            case 0x0D: out += "\\r"
            case 0x09: out += "\\t"
            case 0x08: out += "\\b"
            case 0x0C: out += "\\f"
            case 0x20..<0x7F: out.unicodeScalars.append(Unicode.Scalar(u)!)
            default: out += String(format: "\\u%04x", u)
            }
        }
        out += "\""
    }
}

/// RJ builders for reference-shaped output: Python floats are `.num`, ints `.int`, None `.null`.
nonisolated extension RJ {
    static func f(_ x: Double?) -> RJ { x.map { .num($0) } ?? .null }
    static func s(_ x: String?) -> RJ { x.map { .str($0) } ?? .null }
    static func fs(_ xs: [Double]) -> RJ { .arr(xs.map { .num($0) }) }
    static func fs(_ xs: [Double?]) -> RJ { .arr(xs.map { RJ.f($0) }) }
}
