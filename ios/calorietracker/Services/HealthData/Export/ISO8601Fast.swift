import Foundation

/// Allocation-light ISO-8601 writer/parser for the export format
/// (`yyyy-MM-dd'T'HH:mm:ss.SSS±HH:MM`). One `DateFormatter` per row would dominate a
/// 100k-row export; this does the civil-date arithmetic directly.
nonisolated enum ISO8601Fast {
    static func format(ms: Int64, offsetS: Int) -> String {
        let localMs = ms + Int64(offsetS) * 1000
        let days = floorDiv(localMs, 86_400_000)
        let msOfDay = localMs - days * 86_400_000
        let (year, month, day) = civil(fromDays: days)
        let hour = Int(msOfDay / 3_600_000)
        let minute = Int((msOfDay % 3_600_000) / 60_000)
        let second = Int((msOfDay % 60_000) / 1000)
        let millis = Int(msOfDay % 1000)
        let sign = offsetS < 0 ? "-" : "+"
        let absOffset = abs(offsetS)
        return String(
            format: "%04d-%02d-%02dT%02d:%02d:%02d.%03d%@%02d:%02d",
            year, month, day, hour, minute, second, millis, sign, absOffset / 3600, (absOffset % 3600) / 60
        )
    }

    /// Accepts `Z`, `±HH:MM`, `±HHMM`, `±HH`, an optional fraction (1–9 digits) and a missing
    /// offset (treated as UTC). Returns epoch milliseconds plus the offset in seconds.
    static func parse(_ text: String) -> (ms: Int64, offsetS: Int)? {
        let s = Array(text.utf8)
        guard s.count >= 19, s[4] == UInt8(ascii: "-"), s[7] == UInt8(ascii: "-"),
              s[10] == UInt8(ascii: "T") || s[10] == UInt8(ascii: " "),
              s[13] == UInt8(ascii: ":"), s[16] == UInt8(ascii: ":")
        else { return nil }
        guard let year = int(s, 0, 4), let month = int(s, 5, 2), let day = int(s, 8, 2),
              let hour = int(s, 11, 2), let minute = int(s, 14, 2), let second = int(s, 17, 2)
        else { return nil }
        var index = 19
        var millis = 0
        if index < s.count, s[index] == UInt8(ascii: ".") || s[index] == UInt8(ascii: ",") {
            index += 1
            var digits = 0
            var fraction = 0
            while index < s.count, s[index] >= 48, s[index] <= 57 {
                if digits < 3 {
                    fraction = fraction * 10 + Int(s[index] - 48)
                }
                digits += 1
                index += 1
            }
            guard digits > 0 else { return nil }
            while digits < 3 {
                fraction *= 10
                digits += 1
            }
            millis = fraction
        }
        var offset = 0
        if index < s.count {
            switch s[index] {
            case UInt8(ascii: "Z"), UInt8(ascii: "z"):
                index += 1
            case UInt8(ascii: "+"), UInt8(ascii: "-"):
                let negative = s[index] == UInt8(ascii: "-")
                index += 1
                guard let oh = int(s, index, 2) else { return nil }
                index += 2
                var om = 0
                if index < s.count, s[index] == UInt8(ascii: ":") { index += 1 }
                if index + 2 <= s.count, let parsed = int(s, index, 2) {
                    om = parsed
                    index += 2
                }
                offset = (oh * 3600 + om * 60) * (negative ? -1 : 1)
            default:
                return nil
            }
        }
        guard index == s.count else { return nil }
        guard (1...12).contains(month), (1...31).contains(day), hour < 24, minute < 60, second < 61 else { return nil }
        let days = days(fromCivil: year, month, day)
        let localMs = days * 86_400_000 + Int64(hour) * 3_600_000 + Int64(minute) * 60_000 + Int64(second) * 1000 + Int64(millis)
        return (localMs - Int64(offset) * 1000, offset)
    }

    // MARK: - Civil date arithmetic (Howard Hinnant's algorithms)

    static func days(fromCivil year: Int, _ month: Int, _ day: Int) -> Int64 {
        let y = Int64(month <= 2 ? year - 1 : year)
        let era = floorDiv(y, 400)
        let yoe = y - era * 400
        let mp = Int64((month + 9) % 12)
        let doy = (153 * mp + 2) / 5 + Int64(day) - 1
        let doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return era * 146_097 + doe - 719_468
    }

    static func civil(fromDays z0: Int64) -> (Int, Int, Int) {
        let z = z0 + 719_468
        let era = floorDiv(z, 146_097)
        let doe = z - era * 146_097
        let yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365
        let y = yoe + era * 400
        let doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
        let mp = (5 * doy + 2) / 153
        let d = doy - (153 * mp + 2) / 5 + 1
        let m = mp < 10 ? mp + 3 : mp - 9
        return (Int(m <= 2 ? y + 1 : y), Int(m), Int(d))
    }

    private static func floorDiv(_ a: Int64, _ b: Int64) -> Int64 {
        let q = a / b
        return (a % b != 0 && (a < 0) != (b < 0)) ? q - 1 : q
    }

    private static func int(_ s: [UInt8], _ start: Int, _ length: Int) -> Int? {
        guard start + length <= s.count else { return nil }
        var value = 0
        for i in start..<(start + length) {
            let c = s[i]
            guard c >= 48, c <= 57 else { return nil }
            value = value * 10 + Int(c - 48)
        }
        return value
    }
}
