import Foundation

/// Builds the message text that actually goes to the provider when the turn has documents
/// (docs/coach.md §6).
///
/// The excerpts are appended verbatim — already redacted by `attachment_excerpt`, and exactly what
/// the excerpt sheet showed the user (rule 4). The whole turn is capped by `turn_excerpts`, so three
/// long PDFs cannot blow the context window; a document that no longer fits is named but empty, and
/// the model is told so rather than being handed a silent truncation.
nonisolated enum CoachAttachmentComposer {
    static func messageWithAttachments(_ text: String, _ attachments: [ChatAttachment]) -> String {
        let documents = attachments.filter { $0.kind != .image }
        guard !documents.isEmpty else { return text }

        let budgeted = CR.turnExcerpts(documents.map { attachment in
            RJ.obj([
                "id": .str(attachment.id),
                "filename": .str(attachment.filename),
                "text": RJ.string(attachment.excerpt),
            ])
        })
        let allowance = Dictionary(
            (budgeted["attachments"].array ?? []).compactMap { entry -> (String, Int)? in
                guard let id = entry["id"].string, let chars = entry["chars"].double else { return nil }
                return (id, Int(chars))
            },
            uniquingKeysWith: { first, _ in first }
        )

        var parts = [text]
        for attachment in documents {
            let limit = allowance[attachment.id] ?? 0
            let excerpt = CR.cpCut(attachment.excerpt ?? "", limit)
            parts.append("")
            parts.append("--- \(attachment.kind == .note ? "Note" : "Attached file"): \(attachment.filename) ---")
            if excerpt.isEmpty {
                parts.append("(no readable text)")
            } else {
                parts.append(excerpt)
                if CR.cpLen(attachment.excerpt ?? "") > limit {
                    parts.append("(truncated)")
                }
            }
        }
        return parts.joined(separator: "\n")
    }
}
