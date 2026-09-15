import Foundation

/// Contract §4.4 title rule: user-entered → filename without extension (underscores / dashes
/// → spaces, whitespace collapsed) → `"<Type> — <date>"` → "Untitled record".
nonisolated enum RecordTitleDeriver {
    static func title(userTitle: String?, filename: String?, type: RecordType?, date: Date?) -> String {
        if let user = userTitle.map(collapse), !user.isEmpty {
            return user
        }
        if let fromFile = filenameTitle(filename), !fromFile.isEmpty {
            return fromFile
        }
        if let type, let date {
            return "\(type.title) — \(date.formatted(date: .abbreviated, time: .omitted))"
        }
        return String(localized: "Untitled record")
    }

    static func filenameTitle(_ filename: String?) -> String? {
        guard let filename else { return nil }
        let name = (filename as NSString).lastPathComponent
        var base = (name as NSString).deletingPathExtension
        if base.isEmpty { base = name }
        let spaced = base
            .replacingOccurrences(of: "_", with: " ")
            .replacingOccurrences(of: "-", with: " ")
        let result = collapse(spaced)
        return result.isEmpty ? nil : result
    }

    static func collapse(_ text: String) -> String {
        text.split(whereSeparator: { $0.isWhitespace }).joined(separator: " ")
    }
}

/// Contract §4.6 filename date patterns: `yyyy-MM-dd`, `yyyyMMdd`, `dd-MM-yyyy`. Only real
/// calendar dates between 1900 and 2100 are accepted; digits must not continue on either side.
nonisolated enum RecordFilenameDate {
    static func parse(_ filename: String?) -> String? {
        guard let filename else { return nil }
        let name = (filename as NSString).lastPathComponent
        let patterns: [(String, (Substring, Substring, Substring) -> (Int, Int, Int)?)] = [
            (#"(?<!\d)(\d{4})-(\d{2})-(\d{2})(?!\d)"#, { y, m, d in Int(y).flatMap { y in Int(m).flatMap { m in Int(d).map { (y, m, $0) } } } }),
            (#"(?<!\d)(\d{2})-(\d{2})-(\d{4})(?!\d)"#, { d, m, y in Int(y).flatMap { y in Int(m).flatMap { m in Int(d).map { (y, m, $0) } } } }),
            (#"(?<!\d)(\d{4})(\d{2})(\d{2})(?!\d)"#, { y, m, d in Int(y).flatMap { y in Int(m).flatMap { m in Int(d).map { (y, m, $0) } } } }),
        ]
        for (pattern, map) in patterns {
            guard let regex = try? NSRegularExpression(pattern: pattern) else { continue }
            let range = NSRange(name.startIndex..., in: name)
            for match in regex.matches(in: name, range: range) where match.numberOfRanges == 4 {
                guard let r1 = Range(match.range(at: 1), in: name),
                      let r2 = Range(match.range(at: 2), in: name),
                      let r3 = Range(match.range(at: 3), in: name),
                      let (year, month, day) = map(name[r1], name[r2], name[r3]),
                      let valid = validDay(year: year, month: month, day: day)
                else { continue }
                return valid
            }
        }
        return nil
    }

    static func validDay(year: Int, month: Int, day: Int) -> String? {
        guard (1900...2100).contains(year), (1...12).contains(month), (1...31).contains(day) else { return nil }
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(identifier: "UTC")!
        let components = DateComponents(year: year, month: month, day: day)
        guard let date = calendar.date(from: components),
              calendar.component(.day, from: date) == day,
              calendar.component(.month, from: date) == month
        else { return nil }
        return String(format: "%04d-%02d-%02d", year, month, day)
    }
}
