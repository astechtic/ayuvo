import Foundation
import Testing

/// Every String Catalog of every target must be translated into every language of
/// `shared/l10n/l10n_config.json`, with the same format arguments as English
/// (docs/localization.md). `scripts/l10n/l10n_report.py --detail` lists what is missing.
struct LocalizationCoverageTests {
    private struct Config: Decodable {
        struct Language: Decodable { let ios: String }
        let languages: [Language]
        let plural_categories: [String: [String]]
        let do_not_translate: [String]
        let ios_catalog_folders: [String]
    }

    private static let repo = URL(fileURLWithPath: #filePath)
        .deletingLastPathComponent()
        .deletingLastPathComponent()
        .deletingLastPathComponent()

    /// Same pattern as `scripts/l10n/l10n_lib.py`: the space flag is left out, so "50% of" is text.
    private static let placeholder = try! NSRegularExpression(
        pattern: #"%(?:(\d+)\$)?[-+#0,]*\d*(?:\.\d+)?(?:ll|l|h|q|z|t|j)?[@dDuUxXoOfeEgGcCsSaA]|%#@\w+@"#)

    private func config() throws -> Config {
        let url = Self.repo.appendingPathComponent("shared/l10n/l10n_config.json")
        return try JSONDecoder().decode(Config.self, from: Data(contentsOf: url))
    }

    private func catalogs(_ config: Config) throws -> [URL] {
        var found: [URL] = []
        for folder in config.ios_catalog_folders {
            let dir = Self.repo.appendingPathComponent("ios/\(folder)")
            let names = try FileManager.default.contentsOfDirectory(atPath: dir.path)
            found += names.filter { $0.hasSuffix(".xcstrings") }.map { dir.appendingPathComponent($0) }
        }
        return found
    }

    /// Specifiers with positions removed; sorted when every argument is positional.
    private func signature(_ original: String) -> [String] {
        // "%%" is an escaped percent sign, so "80%%-dən" is not "%-d".
        let text = original.replacingOccurrences(of: "%%", with: "\u{0}\u{0}")
        let range = NSRange(text.startIndex..., in: text)
        let matches = Self.placeholder.matches(in: text, range: range)
        let specs = matches.map { match -> String in
            let spec = String(text[Range(match.range, in: text)!])
            return spec.replacingOccurrences(of: #"\d+\$"#, with: "", options: .regularExpression)
        }
        let positional = !matches.isEmpty && matches.allSatisfy { $0.range(at: 1).location != NSNotFound }
        return positional ? specs.sorted() : specs
    }

    private func needsTranslation(_ english: String, _ config: Config) -> Bool {
        let range = NSRange(english.startIndex..., in: english)
        let text = Self.placeholder.stringByReplacingMatches(in: english, range: range, withTemplate: "")
        guard text.contains(where: \.isLetter) else { return false }
        return !config.do_not_translate.contains(english.trimmingCharacters(in: .whitespaces))
    }

    @Test func everyCatalogIsFullyTranslated() throws {
        let config = try config()
        var problems: [String] = []
        for url in try catalogs(config) {
            let json = try JSONSerialization.jsonObject(with: Data(contentsOf: url)) as! [String: Any]
            let strings = json["strings"] as? [String: [String: Any]] ?? [:]
            for (key, entry) in strings {
                if entry["shouldTranslate"] as? Bool == false { continue }
                let locs = entry["localizations"] as? [String: [String: Any]] ?? [:]
                let englishUnit = (locs["en"]?["stringUnit"] as? [String: Any])?["value"] as? String
                let english = englishUnit ?? key
                let englishPlural = (locs["en"]?["variations"] as? [String: Any])?["plural"] as? [String: Any]
                if englishPlural == nil, !needsTranslation(english, config) { continue }
                let isFormat = signature(key).isEmpty == false || englishPlural != nil
                for language in config.languages.map(\.ios) {
                    let where_ = "\(url.lastPathComponent) [\(language)] \(key.prefix(60))"
                    guard let loc = locs[language] else {
                        problems.append("missing: \(where_)")
                        continue
                    }
                    if let unit = loc["stringUnit"] as? [String: Any] {
                        let value = unit["value"] as? String ?? ""
                        if value.trimmingCharacters(in: .whitespaces).isEmpty { problems.append("empty: \(where_)") }
                        if unit["state"] as? String == "new" { problems.append("state new: \(where_)") }
                        // Plain text (contract text included): a "%" is just a percent sign.
                        if isFormat && signature(value) != signature(english) {
                            problems.append("placeholders: \(where_)")
                        }
                    } else if let plural = (loc["variations"] as? [String: Any])?["plural"] as? [String: [String: Any]] {
                        for category in config.plural_categories[language] ?? ["other"] where plural[category] == nil {
                            problems.append("plural \(category) missing: \(where_)")
                        }
                    } else {
                        problems.append("no value: \(where_)")
                    }
                }
            }
        }
        #expect(problems.isEmpty, "\(problems.count) localization problems, e.g. \(problems.sorted().prefix(20))")
    }

    @Test func knownRegionsListEveryLanguage() throws {
        let config = try config()
        let project = try String(
            contentsOf: Self.repo.appendingPathComponent("ios/calorietracker.xcodeproj/project.pbxproj"), encoding: .utf8)
        for language in config.languages.map(\.ios) {
            #expect(project.contains("\t\(language),") || project.contains("\"\(language)\","),
                    "\(language) missing from knownRegions")
        }
    }
}
