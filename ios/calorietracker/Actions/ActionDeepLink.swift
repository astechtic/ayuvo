import Foundation

/// `ayuvo://action/<id>?k=v` and `ayuvo://open/<section>` — Swift port of `parse_deeplink` in
/// `scripts/actions_reference.py` (vectors: `shared/actions/test-vectors/deeplinks.json`). Parsed by hand
/// on the raw string: `URLComponents` does not turn `+` into a space and normalises some escapes.
nonisolated enum ActionDeepLink {
    nonisolated struct Request: Equatable, Sendable {
        let id: String
        let params: [String: String]
    }

    nonisolated enum Parsed: Equatable, Sendable {
        case request(Request)
        /// Not ours (widgets, log-food, share hand-offs…): fall through to the other handlers.
        case notActionLink
        case failure(code: String)
    }

    static func parse(_ url: String) -> Parsed {
        let prefix = "ayuvo://"
        guard url.hasPrefix(prefix) else { return .notActionLink }
        var rest = Substring(url.dropFirst(prefix.count))
        if let hash = rest.firstIndex(of: "#") { rest = rest[..<hash] }
        let path: Substring, query: Substring
        if let mark = rest.firstIndex(of: "?") {
            path = rest[..<mark]
            query = rest[rest.index(after: mark)...]
        } else {
            path = rest
            query = ""
        }
        let host: Substring, tailRaw: Substring
        if let slash = path.firstIndex(of: "/") {
            host = path[..<slash]
            tailRaw = path[path.index(after: slash)...]
        } else {
            host = path
            tailRaw = ""
        }
        guard host == "action" || host == "open" else { return .notActionLink }
        var tail = tailRaw
        if tail.hasSuffix("/") { tail = tail.dropLast() }
        guard !tail.isEmpty, !tail.contains("/") else { return .failure(code: "bad_link") }
        guard let segment = percentDecode(tail), !segment.isEmpty else { return .failure(code: "bad_link") }
        var params: [String: String] = [:]
        if !query.isEmpty {
            for piece in query.split(separator: "&", omittingEmptySubsequences: false) where !piece.isEmpty {
                let key: Substring, value: Substring
                if let eq = piece.firstIndex(of: "=") {
                    key = piece[..<eq]
                    value = piece[piece.index(after: eq)...]
                } else {
                    key = piece
                    value = ""
                }
                guard let decodedKey = percentDecode(key), !decodedKey.isEmpty,
                      let decodedValue = percentDecode(value)
                else { return .failure(code: "bad_link") }
                if params[decodedKey] != nil { return .failure(code: "duplicate_param") }
                params[decodedKey] = decodedValue
            }
        }
        if host == "open" { return .request(Request(id: "open.section", params: ["section": segment])) }
        return .request(Request(id: segment, params: params))
    }

    /// Percent-decode with `+` as space; nil when malformed or not UTF-8.
    static func percentDecode<S: StringProtocol>(_ text: S) -> String? {
        var bytes: [UInt8] = []
        let scalars = Array(text.unicodeScalars)
        var i = 0
        while i < scalars.count {
            let c = scalars[i]
            if c == "%" {
                guard i + 2 < scalars.count, let hi = hexValue(scalars[i + 1]), let lo = hexValue(scalars[i + 2]) else { return nil }
                bytes.append(UInt8(hi * 16 + lo))
                i += 3
                continue
            }
            if c == "+" {
                bytes.append(0x20)
            } else {
                bytes.append(contentsOf: Array(String(c).utf8))
            }
            i += 1
        }
        return String(data: Data(bytes), encoding: .utf8)
    }

    private static func hexValue(_ c: Unicode.Scalar) -> Int? {
        switch c.value {
        case 0x30...0x39: return Int(c.value - 0x30)
        case 0x41...0x46: return Int(c.value - 0x41 + 10)
        case 0x61...0x66: return Int(c.value - 0x61 + 10)
        default: return nil
        }
    }
}
