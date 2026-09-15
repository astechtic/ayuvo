import Foundation

/// Contract §4.7: text is folded (lowercase, NFD with combining marks removed) before it is
/// written to `records_fts` and before it is matched; query terms become `term*` joined by
/// spaces (implicit AND). FTS4's `simple` tokenizer splits on ASCII punctuation, so the query
/// side does the same to keep both halves aligned.
nonisolated enum RecordsSearchText {
    /// Same folding as rules and validation (§10).
    static func fold(_ text: String) -> String {
        RecordsFold.fold(text)
    }

    /// Folded terms of a user query. Anything that is not a letter or digit separates terms,
    /// which also strips FTS syntax (`"`, `*`, `-`, `:`, parentheses).
    static func terms(_ query: String) -> [String] {
        RR.words(fold(query))
    }

    /// Folded text for an FTS column: the same term split as queries, so non-ASCII punctuation
    /// (which `simple` would keep inside a token, e.g. `—`) separates words on both sides.
    static func indexText(_ text: String?) -> String {
        guard let text, !text.isEmpty else { return "" }
        return terms(text).joined(separator: " ")
    }

    /// `MATCH` expression, or nil when the query has no searchable term.
    static func matchExpression(_ query: String) -> String? {
        let terms = terms(query)
        guard !terms.isEmpty else { return nil }
        return terms.map { "\($0)*" }.joined(separator: " ")
    }
}
