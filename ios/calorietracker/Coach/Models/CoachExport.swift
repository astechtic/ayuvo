import Foundation

/// What "Export this chat" produces (docs/coach.md §10).
nonisolated enum CoachExportFormat: String, CaseIterable, Sendable {
    /// A readable transcript for a person.
    case markdown
    /// One conversation in the `ayuvo-coach-chats` shape, so it imports through the same reader as
    /// a full backup (§11).
    case json

    var title: String {
        switch self {
        case .markdown: String(localized: "Export as Markdown")
        case .json: String(localized: "Export as JSON")
        }
    }

    var systemImage: String {
        switch self {
        case .markdown: "doc.text"
        case .json: "curlybraces"
        }
    }
}

nonisolated struct CoachExportFile: Identifiable, Sendable {
    var filename: String
    var data: Data

    var id: String { filename }

    /// Writes the file to a temporary directory so the share sheet can hand out a URL.
    func writeToTemporaryFile() -> URL? {
        let directory = FileManager.default.temporaryDirectory
            .appendingPathComponent("coach-export-\(UUID().uuidString)", isDirectory: true)
        guard (try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)) != nil
        else { return nil }
        let url = directory.appendingPathComponent(filename, isDirectory: false)
        guard (try? data.write(to: url, options: .atomic)) != nil else { return nil }
        return url
    }
}
