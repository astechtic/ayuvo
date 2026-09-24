import Foundation
import Testing
@testable import calorietracker

/// Message actions and export (docs/coach.md §10). The formats themselves are pinned by the shared
/// `export.json` vectors; these cover the store on top — that regenerate keeps the old answer, that
/// the stepper can reach it, and that an exported file is a real file with the right bytes.
struct CoachExportTests {

    private static func makeStore() async throws -> (CoachRepository, URL) {
        let root = FileManager.default.temporaryDirectory
            .appendingPathComponent("coach-export-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        let database = try await CoachDatabase.open(url: root.appendingPathComponent("coach.sqlite"))
        let files = CoachFileStore(root: root.appendingPathComponent("files", isDirectory: true))
        return (CoachRepository(database: database, files: files), root)
    }

    /// A conversation with one prompt, one reply, and a document on the prompt.
    private static func seed(_ repository: CoachRepository) async throws -> Conversation {
        var conversation = Conversation(title: "How did I sleep this week?", createdMs: 1_700_000_000_000)
        conversation.lastMessageMs = 1_700_000_200_000
        try await repository.createConversation(conversation)

        let attachment = ChatAttachment(
            id: "a1", kind: .pdf, filename: "sleep-study.pdf", mimeType: "application/pdf",
            bytes: 2048, pageCount: 2, charCount: 120,
            excerpt: "Sleep efficiency 88 percent", createdMs: 1_700_000_050_000)
        try await repository.insertAttachment(attachment)

        try await repository.appendMessage(ChatMessage(
            id: "m1", conversationID: conversation.id, seq: 1, role: .user,
            content: "How did I sleep this week?", createdMs: 1_700_000_100_000,
            attachmentIDs: ["a1"]))
        try await repository.appendMessage(ChatMessage(
            id: "m2", conversationID: conversation.id, seq: 2, role: .assistant,
            content: "First answer.", createdMs: 1_700_000_200_000,
            recordRefs: [ChatRecordRef(recordID: "r1", title: "Sleep study", date: "2026-08-02")]))
        return conversation
    }

    // MARK: - Regenerate

    @Test func regeneratingKeepsTheOldAnswerAsAnEarlierVersion() async throws {
        let (repository, root) = try await Self.makeStore()
        defer { try? FileManager.default.removeItem(at: root) }
        let conversation = try await Self.seed(repository)

        let rows = try await repository.allMessages(conversationID: conversation.id)
        let plan = CR.regeneratePlan(messages: rows.map(\.referenceValue), seq: 2)
        #expect(plan["ok"].bool == true)
        #expect(plan["prompt_id"].string == "m1")
        #expect(plan["variant_index"].double == 1)
        #expect(plan["regenerated_from"].string == "m2")
        // The prompt's attachments come along, so the model sees the same document.
        #expect((plan["attachment_ids"].array ?? []).compactMap(\.string) == ["a1"])

        try await repository.appendMessage(ChatMessage(
            id: "m3", conversationID: conversation.id, seq: 2, variantIndex: 1, role: .assistant,
            content: "Second answer.", createdMs: 1_700_000_900_000, regeneratedFrom: "m2"))

        // The transcript shows the newest; both remain reachable.
        let shown = try await repository.messages(conversationID: conversation.id)
        #expect(shown.map(\.content) == ["How did I sleep this week?", "Second answer."])
        let variants = try await repository.variants(conversationID: conversation.id, seq: 2)
        #expect(variants.map(\.content) == ["First answer.", "Second answer."])
        #expect(variants.map(\.variantIndex) == [0, 1])
        await repository.close()
    }

    /// A chain of regenerations stays a flat set, not a linked list.
    @Test func everyVariantPointsAtTheFirstOne() async throws {
        let (repository, root) = try await Self.makeStore()
        defer { try? FileManager.default.removeItem(at: root) }
        let conversation = try await Self.seed(repository)
        try await repository.appendMessage(ChatMessage(
            id: "m3", conversationID: conversation.id, seq: 2, variantIndex: 1, role: .assistant,
            content: "Second.", createdMs: 3, regeneratedFrom: "m2"))

        let rows = try await repository.allMessages(conversationID: conversation.id)
        let plan = CR.regeneratePlan(messages: rows.map(\.referenceValue), seq: 2)
        #expect(plan["variant_index"].double == 2)
        #expect(plan["regenerated_from"].string == "m2", "a third version must still point at the first")
        await repository.close()
    }

    @Test func regeneratingSomethingThatIsNotAReplyIsRefused() async throws {
        let (repository, root) = try await Self.makeStore()
        defer { try? FileManager.default.removeItem(at: root) }
        let conversation = try await Self.seed(repository)
        let rows = try await repository.allMessages(conversationID: conversation.id)
        let plan = CR.regeneratePlan(messages: rows.map(\.referenceValue), seq: 1)
        #expect(plan["ok"].bool == false)
        #expect(plan["reason"].string == "not_a_reply")
        await repository.close()
    }

    // MARK: - Export

    @Test func theMarkdownExportIsAReadableTranscript() async throws {
        let (repository, root) = try await Self.makeStore()
        defer { try? FileManager.default.removeItem(at: root) }
        let conversation = try await Self.seed(repository)
        let messages = try await repository.allMessages(conversationID: conversation.id)
        let attachments = try await repository.attachments(ids: ["a1"])

        let result = CR.conversationMarkdown(
            conversation: conversation.referenceValue,
            messages: messages.map(\.referenceValue),
            attachments: attachments.map(\.referenceValue),
            localDay: "2026-09-24",
            provider: "Gemini"
        )
        let text = try #require(result["text"].string)
        #expect(text.hasPrefix("# How did I sleep this week?"))
        #expect(text.contains("2026-09-24 · 2 messages · Gemini"))
        #expect(text.contains("**You:** How did I sleep this week?"))
        #expect(text.contains("**Coach:** First answer."))
        #expect(text.contains("Attached: sleep-study.pdf"))
        #expect(text.contains("Used records: Sleep study — 2026-08-02"))
        // The excerpt was a redacted copy of a file the user still has; it is never inlined.
        #expect(!text.contains("Sleep efficiency"))
        #expect(result["filename"].string == "how-did-i-sleep-this-week-2026-09-24.md")
        await repository.close()
    }

    @Test func theJSONExportIsOneConversationInTheArchiveShape() async throws {
        let (repository, root) = try await Self.makeStore()
        defer { try? FileManager.default.removeItem(at: root) }
        let conversation = try await Self.seed(repository)
        let messages = try await repository.allMessages(conversationID: conversation.id)
        let attachments = try await repository.attachments(ids: ["a1"])

        let result = CR.conversationJSON(
            conversation: conversation.referenceValue,
            messages: messages.map(\.referenceValue),
            attachments: attachments.map(\.referenceValue),
            localDay: "2026-09-24"
        )
        let archive = result["archive"]
        #expect(archive["format"].string == CR.archiveFormat)
        #expect(archive["format_version"].double == Double(CR.archiveVersion))
        #expect((archive["conversations"].array ?? []).count == 1)
        #expect((archive["messages"].array ?? []).count == 2)
        #expect(result["filename"].string == "how-did-i-sleep-this-week-2026-09-24.json")

        // It must merge back through the same reader as a full backup.
        let merged = CR.mergeChatArchive(
            .obj(["conversations": .arr([]), "messages": .arr([]), "attachments": .arr([])]),
            archive)
        #expect(merged["error"].isNull)
        #expect(merged["counts"]["conversations_inserted"].double == 1)
        #expect(merged["counts"]["messages_inserted"].double == 2)
        await repository.close()
    }

    @Test func anExportedFileIsWrittenWithItsName() throws {
        let file = CoachExportFile(filename: "chat-2026-09-24.md", data: Data("# hi\n".utf8))
        let url = try #require(file.writeToTemporaryFile())
        defer { try? FileManager.default.removeItem(at: url.deletingLastPathComponent()) }
        #expect(url.lastPathComponent == "chat-2026-09-24.md")
        #expect(try String(contentsOf: url, encoding: .utf8) == "# hi\n")
    }

    @Test func aTitleWithNoUsableCharactersStillGivesAFilename() {
        #expect(CR.exportSlug("   ") == "chat")
        #expect(CR.exportSlug("😀😀") == "chat")
        #expect(CR.exportSlug("Compare my last two blood reports!") == "compare-my-last-two-blood-reports")
        #expect(CR.cpLen(CR.exportSlug(String(repeating: "word ", count: 40))) <= CR.maxSlugChars)
    }
}
