import Foundation
import Testing
@testable import calorietracker

/// The `ayuvo-coach-chats` container and its merge (docs/coach.md §11). The row rules are pinned by
/// the shared `chat_archive.json` vectors; this covers the zip around them and what it does to a
/// real store — the half `CoachChatArchiveTest.kt` cannot reach without a device.
struct CoachChatArchiveTests {

    private struct Store {
        var repository: CoachRepository
        var files: CoachFileStore
        var root: URL
    }

    private static func makeStore() async throws -> Store {
        let root = FileManager.default.temporaryDirectory
            .appendingPathComponent("coach-archive-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        let database = try await CoachDatabase.open(url: root.appendingPathComponent("coach.sqlite"))
        let files = CoachFileStore(root: root.appendingPathComponent("files", isDirectory: true))
        return Store(repository: CoachRepository(database: database, files: files), files: files, root: root)
    }

    /// One conversation, one prompt with a document, one reply.
    @discardableResult
    private static func seed(_ store: Store) async throws -> Conversation {
        var conversation = Conversation(title: "Sleep", createdMs: 1_000)
        conversation.lastMessageMs = 2_000
        try await store.repository.createConversation(conversation)

        let path = try store.files.writeOriginal(Data("%PDF-".utf8), attachmentID: "a1", fileExtension: "pdf")
        var attachment = ChatAttachment(
            id: "a1", kind: .pdf, filename: "sleep-study.pdf", mimeType: "application/pdf",
            bytes: 5, pageCount: 1, charCount: 10, excerpt: "redacted", createdMs: 1_400)
        attachment.filePath = path
        try await store.repository.insertAttachment(attachment)

        try await store.repository.appendMessage(ChatMessage(
            id: "m1", conversationID: conversation.id, seq: 1, role: .user,
            content: "How did I sleep?", createdMs: 1_500, attachmentIDs: ["a1"]))
        try await store.repository.appendMessage(ChatMessage(
            id: "m2", conversationID: conversation.id, seq: 2, role: .assistant,
            content: "You averaged 6 h 40 m.", createdMs: 2_000))
        return conversation
    }

    private static func export(_ store: Store) async throws -> URL {
        let target = store.root.appendingPathComponent(CoachChatArchiveFormat.fileName)
        let writer = CoachChatArchiveWriter(repository: store.repository, files: store.files,
                                            appVersion: "1.0", nowMs: { 9_000 },
                                            timeZone: { "Asia/Kolkata" })
        try await writer.export(to: target)
        return target
    }

    // MARK: - The container

    @Test func theContainerHasItsEntriesInReadingOrder() async throws {
        let store = try await Self.makeStore()
        defer { try? FileManager.default.removeItem(at: store.root) }
        try await Self.seed(store)
        let url = try await Self.export(store)

        let reader = try ZipArchiveReader(url: url)
        #expect(reader.entries.map(\.name) == [
            CoachChatArchiveFormat.manifest,
            CoachChatArchiveFormat.conversations,
            CoachChatArchiveFormat.messages,
            CoachChatArchiveFormat.attachments,
            "attachments/a1/sleep-study.pdf",
            CoachChatArchiveFormat.checksums,
        ])
        await store.repository.close()
    }

    /// checksums.json covers every entry except itself.
    @Test func everyEntryIsChecksummed() async throws {
        let store = try await Self.makeStore()
        defer { try? FileManager.default.removeItem(at: store.root) }
        try await Self.seed(store)
        let url = try await Self.export(store)

        let reader = try ZipArchiveReader(url: url)
        let entry = try #require(reader.entry(named: CoachChatArchiveFormat.checksums))
        let sums = try #require(RJ.parse(String(data: try reader.data(for: entry), encoding: .utf8) ?? ""))
        guard case .obj(let dict) = sums else { Issue.record("checksums is not an object"); return }
        #expect(dict.count == 5)
        #expect(dict[CoachChatArchiveFormat.checksums] == nil)
        for (_, value) in dict {
            let hex = value.string ?? ""
            #expect(hex.count == 64 && hex.allSatisfy { $0.isHexDigit && !$0.isUppercase })
        }
        await store.repository.close()
    }

    @Test func reExportIsByteIdentical() async throws {
        let store = try await Self.makeStore()
        defer { try? FileManager.default.removeItem(at: store.root) }
        try await Self.seed(store)
        let first = try Data(contentsOf: try await Self.export(store))
        let second = try Data(contentsOf: try await Self.export(store))
        #expect(first == second)
        await store.repository.close()
    }

    // MARK: - Import

    @Test func importingIntoAnEmptyStoreBringsTheChatAndItsFileBack() async throws {
        let source = try await Self.makeStore()
        let destination = try await Self.makeStore()
        defer {
            try? FileManager.default.removeItem(at: source.root)
            try? FileManager.default.removeItem(at: destination.root)
        }
        let conversation = try await Self.seed(source)
        let url = try await Self.export(source)

        let result = try await CoachChatArchiveReader(repository: destination.repository,
                                                      files: destination.files).import(from: url)
        #expect(result.ok)
        #expect(result.counts["conversations_inserted"] == 1)
        #expect(result.counts["messages_inserted"] == 2)
        #expect(result.filesRestored == 1)

        let messages = try await destination.repository.allMessages(conversationID: conversation.id)
        #expect(messages.map(\.content) == ["How did I sleep?", "You averaged 6 h 40 m."])
        let attachments = try await destination.repository.attachments(ids: ["a1"])
        let restored = try #require(attachments.first)
        #expect(restored.filename == "sleep-study.pdf")
        #expect(destination.files.data(at: restored.filePath) == Data("%PDF-".utf8))
        // The excerpt travels; it is what the user was shown was sent.
        #expect(restored.excerpt == "redacted")

        // A second import changes nothing (same updated_ms).
        let again = try await CoachChatArchiveReader(repository: destination.repository,
                                                     files: destination.files).import(from: url)
        #expect(again.counts["conversations_inserted"] == 0)
        #expect(again.counts["messages_inserted"] == 0)
        let after = try await destination.repository.allMessages(conversationID: conversation.id)
        #expect(after.count == 2, "a second import must not duplicate a conversation")
        await source.repository.close()
        await destination.repository.close()
    }

    /// Rule: a chat the user deleted here stays deleted, even if the archive still has it.
    @Test func aLocalTombstoneBeatsTheArchive() async throws {
        let store = try await Self.makeStore()
        defer { try? FileManager.default.removeItem(at: store.root) }
        let conversation = try await Self.seed(store)
        let url = try await Self.export(store)
        try await store.repository.deleteConversation(id: conversation.id, nowMs: 5_000)

        let result = try await CoachChatArchiveReader(repository: store.repository, files: store.files)
            .import(from: url)
        #expect(result.ok)
        #expect(result.counts["conversations_skipped_tombstoned"] == 1)
        #expect(try await store.repository.conversation(id: conversation.id) == nil)
        await store.repository.close()
    }

    @Test func aForeignFileIsRefusedRatherThanGuessedAt() async throws {
        let store = try await Self.makeStore()
        defer { try? FileManager.default.removeItem(at: store.root) }
        let target = store.root.appendingPathComponent("not-ours.zip")
        try CoachChatArchiveWriter.write(
            to: target,
            archive: .obj(["conversations": .arr([]), "messages": .arr([]), "attachments": .arr([])]),
            manifest: .obj(["format": .str("ayuvo-records"), "format_version": .int(1)]),
            blobs: []
        )
        let result = try await CoachChatArchiveReader(repository: store.repository, files: store.files)
            .import(from: target)
        #expect(result.error == "bad_format")
        await store.repository.close()
    }

    /// The shared fixture (`scripts/coach_contract_check.py --write`) is an archive neither platform
    /// wrote: reading it here is the proof that a chat exported on Android opens on an iPhone.
    @Test func theSharedFixtureReadsBack() async throws {
        let store = try await Self.makeStore()
        defer { try? FileManager.default.removeItem(at: store.root) }
        let fixture = HealthTestFixtures.repoRootURL
            .appendingPathComponent("shared/coach/fixtures/ayuvo-coach-chats-fixture.zip")
        #expect(FileManager.default.fileExists(atPath: fixture.path),
                "run scripts/coach_contract_check.py --write")

        let entries = try CoachChatArchiveReader.readEntries(at: fixture)
        #expect(entries.archive["format"].string == CoachChatArchiveFormat.format)
        #expect((entries.archive["conversations"].array ?? []).count == 2)
        #expect((entries.archive["messages"].array ?? []).count == 3)
        #expect((entries.archive["attachments"].array ?? []).count == 1)
        // The tombstone and the unreferenced attachment were never written.
        let ids = (entries.archive["conversations"].array ?? []).compactMap { $0["id"].string }
        #expect(ids == ["conv-sleep-0001", "conv-diet-0002"])

        let result = try await CoachChatArchiveReader(repository: store.repository, files: store.files)
            .import(from: fixture)
        #expect(result.ok)
        #expect(result.counts["conversations_inserted"] == 2)
        #expect(result.counts["messages_inserted"] == 3)
        #expect(result.counts["attachments_inserted"] == 1)
        #expect(result.filesRestored == 1)

        let messages = try await store.repository.allMessages(conversationID: "conv-sleep-0001")
        #expect(messages.map(\.content) == ["How did I sleep this week?", "You averaged 6 h 40 m."])
        #expect(messages.last?.recordRefs?.first?.title == "Sleep study")
        // Search has to find an imported message, so the FTS entry was written too.
        let hits = try await store.repository.search("averaged")
        #expect(hits.contains { $0.conversation.id == "conv-sleep-0001" })
        await store.repository.close()
    }

    @Test func anAttachmentNameCanNeverEscapeItsFolder() {
        #expect(CoachChatArchiveFormat.fileEntry(attachmentID: "a1", filename: "../../etc/passwd")
                == "attachments/a1/passwd")
        #expect(CoachChatArchiveFormat.fileEntry(attachmentID: "a1", filename: "   ")
                == "attachments/a1/attachment")
        #expect(CoachChatArchiveFormat.fileEntry(attachmentID: "a1", filename: #"C:\docs\report.pdf"#)
                == "attachments/a1/report.pdf")
    }
}
