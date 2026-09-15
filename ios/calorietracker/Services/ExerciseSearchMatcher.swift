import Foundation

/// Tokenized AND search for the exercise library, with optional query aliases.
enum ExerciseSearchMatcher {
    /// Maps common multi-word phrases to catalog exercise IDs ("0201" is "cable pushdown").
    static let aliases: [String: String] = [
        "cable pushdown": "0201",
        "triceps cable pushdown": "0201",
        "tricep cable pushdown": "0201",
        "tricep pushdown": "0201",
    ]

    static func tokens(from query: String) -> [String] {
        query
            .trimmingCharacters(in: .whitespacesAndNewlines)
            .lowercased()
            .split(whereSeparator: \.isWhitespace)
            .map(String.init)
            .filter { !$0.isEmpty }
    }

    static func matches(searchableText: String, query: String, exerciseID: String) -> Bool {
        let normalizedQuery = query.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        let tokens = tokens(from: normalizedQuery)
        guard !tokens.isEmpty else { return true }

        if let aliasID = aliases[normalizedQuery], aliasID == exerciseID {
            return true
        }

        return tokens.allSatisfy { searchableText.contains($0) }
    }
}
