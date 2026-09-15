import Foundation

/// §10 `fold(s)`: Unicode NFKD, combining marks removed, lowercase (locale-independent),
/// `\r\n` / `\r` → `\n`, runs of spaces/tabs collapsed to one space per line, each line trimmed.
/// One definition for FTS, rules and validation on both platforms.
/// Folding helpers used by the app; every definition delegates to the reference port (§10, §8.1).
nonisolated enum RecordsFold {
    static func fold(_ text: String) -> String { RR.fold(text) }

    /// §8.1 `norm`: folded words joined by one space.
    static func normalizedValue(_ text: String) -> String { RR.normText(text) }

    /// §8.1 `normalize_value` for a stored or extracted field.
    static func normalizedFieldValue(key: RecordFieldKey, valueText: String, valueJSON: String?) -> String {
        RR.normalizeValue(key.rawValue, valueText, valueJSON.flatMap(RJ.parse) ?? .null)
    }

    static func collapsedWhitespace(_ text: String) -> String { RR.flat(text) }

    static func letterCount(_ text: String) -> Int {
        text.unicodeScalars.reduce(0) { $0 + ($1.properties.isAlphabetic ? 1 : 0) }
    }

    static func compactNumber(_ value: Double, decimals: Int = 6) -> String {
        guard value.isFinite else { return "0" }
        var text = String(format: "%.\(decimals)f", value)
        if text.contains(".") {
            while text.hasSuffix("0") { text.removeLast() }
            if text.hasSuffix(".") { text.removeLast() }
        }
        if text == "-0" { text = "0" }
        return text
    }
}

nonisolated enum RecordsMath {
    static func pyRound(_ value: Double, _ digits: Int) -> Double {
        guard value.isFinite else { return value }
        return Double(String(format: "%.\(digits)f", value)) ?? value
    }
}

nonisolated enum RecordsJSON {
    static func encode<T: Encodable>(_ value: T) -> String? {
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys, .withoutEscapingSlashes]
        return (try? encoder.encode(value)).flatMap { String(data: $0, encoding: .utf8) }
    }

    static func object(_ json: String?) -> [String: Any]? {
        guard let json, let data = json.data(using: .utf8) else { return nil }
        return try? JSONSerialization.jsonObject(with: data) as? [String: Any]
    }

    static func string(_ object: Any) -> String? {
        guard JSONSerialization.isValidJSONObject(object),
              let data = try? JSONSerialization.data(withJSONObject: object, options: [.sortedKeys, .withoutEscapingSlashes])
        else { return nil }
        return String(data: data, encoding: .utf8)
    }
}
