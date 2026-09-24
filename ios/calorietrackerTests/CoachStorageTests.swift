import Foundation
import Testing
@testable import calorietracker

/// `coach.sqlite` (docs/coach.md §2, §7, §12): the schema matches the shared file, conversations and
/// messages round-trip, tombstones stick, orphaned attachment blobs are collected, search works, and
/// the one-time legacy migration moves `coachChatHistory` in exactly once.
struct CoachStorageTests {

    private static func makeRepository() async throws -> (CoachRepository, URL) {
        let root = FileManager.default.temporaryDirectory
            .appendingPathComponent("coach-tests-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        let database = try await CoachDatabase.open(url: root.appendingPathComponent("coach.sqlite"))
        let files = CoachFileStore(root: root.appendingPathComponent("files", isDirectory: true))
        return (CoachRepository(database: database, files: files), root)
    }

    private static func message(_ conversation: String, _ seq: Int, _ role: ChatMessage.Role,
                                _ text: String, attachments: [String] = []) -> ChatMessage {
        ChatMessage(conversationID: conversation, seq: seq, role: role, content: text,
                    createdMs: Int64(1_700_000_000_000 + seq * 1000), attachmentIDs: attachments)
    }

    // MARK: - Schema parity

    @Test func embeddedSchemaMatchesTheSharedFile() throws {
        let url = HealthTestFixtures.repoRootURL.appendingPathComponent("shared/coach/schema.sql")
        let shared = CoachSchema.parseStatements(try String(contentsOf: url, encoding: .utf8))
        #expect(!shared.isEmpty)
        #expect(CoachSchema.statements == shared,
                "CoachSchema.statements drifted from shared/coach/schema.sql")
    }

    @Test func freshDatabaseHasTheDocumentedTablesAndIndexes() async throws {
        let database = try await CoachDatabase.inMemory()
        #expect(try await database.userVersion() == CoachSchema.schemaVersion)
        let tables = try await database.tableNames()
        for name in CoachSchema.tableNames where !name.hasSuffix("_fts") {
            #expect(tables.contains(name), "missing table \(name)")
        }
        let indexes = try await database.indexNames()
        for name in CoachSchema.indexNames {
            #expect(indexes.contains(name), "missing index \(name)")
        }
        await database.close()
    }

    /// The FTS docid must be a rowid alias so VACUUM can never renumber it out from under the index.
    @Test func messagesHaveAStableDocumentID() async throws {
        let database = try await CoachDatabase.inMemory()
        let columns = try await database.columns(of: "messages")
        let docID = try #require(columns.first { $0.name == "doc_id" })
        #expect(docID.primaryKey == 1)
        #expect(docID.type.uppercased() == "INTEGER")
        await database.close()
    }

    // MARK: - Conversations and messages

    @Test func conversationsAndMessagesRoundTrip() async throws {
        let (repository, root) = try await Self.makeRepository()
        defer { try? FileManager.default.removeItem(at: root) }

        var conversation = Conversation(title: "Sleep", createdMs: 1_700_000_000_000)
        conversation.dataSources.set(.records, false)
        conversation.selectedRecordIDs = ["r1"]
        try await repository.createConversation(conversation)

        try await repository.appendMessage(Self.message(conversation.id, 1, .user, "how did I sleep?"))
        try await repository.appendMessage(Self.message(conversation.id, 2, .assistant, "Seven hours."))

        let loaded = try #require(try await repository.conversation(id: conversation.id))
        #expect(loaded.title == "Sleep")
        #expect(loaded.dataSources.isOn(.records) == false)
        #expect(loaded.dataSources.isOn(.health) == true)
        #expect(loaded.selectedRecordIDs == ["r1"])

        let messages = try await repository.messages(conversationID: conversation.id)
        #expect(messages.map(\.content) == ["how did I sleep?", "Seven hours."])
        #expect(messages.map(\.seq) == [1, 2])

        let summaries = try await repository.conversationSummaries()
        #expect(summaries.count == 1)
        #expect(summaries[0].messageCount == 2)
        #expect(summaries[0].snippet == "Seven hours.")
        await repository.close()
    }

    /// Only the newest version of a regenerated reply shows in the transcript; the rest stay reachable.
    @Test func onlyTheNewestVariantIsShown() async throws {
        let (repository, root) = try await Self.makeRepository()
        defer { try? FileManager.default.removeItem(at: root) }
        let conversation = Conversation(createdMs: 1)
        try await repository.createConversation(conversation)
        try await repository.appendMessage(Self.message(conversation.id, 1, .user, "hi"))
        var first = Self.message(conversation.id, 2, .assistant, "first answer")
        try await repository.appendMessage(first)
        first.variantIndex = 1
        var second = ChatMessage(conversationID: conversation.id, seq: 2, variantIndex: 1,
                                 role: .assistant, content: "second answer", createdMs: 9)
        second.regeneratedFrom = first.id
        try await repository.appendMessage(second)

        let shown = try await repository.messages(conversationID: conversation.id)
        #expect(shown.map(\.content) == ["hi", "second answer"])
        let variants = try await repository.variants(conversationID: conversation.id, seq: 2)
        #expect(variants.map(\.content) == ["first answer", "second answer"])
        await repository.close()
    }

    @Test func deletingAConversationTombstonesItAndRemovesItsBlobs() async throws {
        let (repository, root) = try await Self.makeRepository()
        defer { try? FileManager.default.removeItem(at: root) }
        let conversation = Conversation(createdMs: 1)
        try await repository.createConversation(conversation)

        let data = Data("a picture".utf8)
        let attachmentID = UUID().uuidString.lowercased()
        let path = try repository.files.writeOriginal(data, attachmentID: attachmentID, fileExtension: "jpg")
        try await repository.insertAttachment(ChatAttachment(
            id: attachmentID, kind: .image, filename: "photo.jpg", bytes: data.count,
            filePath: path, createdMs: 1))
        try await repository.appendMessage(
            Self.message(conversation.id, 1, .user, "look", attachments: [attachmentID]))
        #expect(repository.files.exists(path))

        try await repository.deleteConversation(id: conversation.id, nowMs: 2)
        #expect(try await repository.conversation(id: conversation.id) == nil)
        #expect(try await repository.conversationSummaries().isEmpty)
        #expect(!repository.files.exists(path), "the attachment blob outlived its only message")
        await repository.close()
    }

    @Test func duplicateCopiesTheSettingsAndNoneOfTheMessages() async throws {
        let (repository, root) = try await Self.makeRepository()
        defer { try? FileManager.default.removeItem(at: root) }
        var conversation = Conversation(title: "Labs", createdMs: 1)
        conversation.dataSources.set(.health, false)
        conversation.selectedRecordIDs = ["r7"]
        try await repository.createConversation(conversation)
        try await repository.appendMessage(Self.message(conversation.id, 1, .user, "explain"))

        let copy = try #require(try await repository.duplicateConversation(id: conversation.id, nowMs: 5))
        #expect(copy.id != conversation.id)
        #expect(copy.title == "Labs")
        #expect(copy.dataSources.isOn(.health) == false)
        #expect(copy.selectedRecordIDs == ["r7"])
        #expect(try await repository.messages(conversationID: copy.id).isEmpty)
        await repository.close()
    }

    @Test func searchFindsAWordInsideAMessage() async throws {
        let (repository, root) = try await Self.makeRepository()
        defer { try? FileManager.default.removeItem(at: root) }
        let a = Conversation(title: "One", createdMs: 1)
        let b = Conversation(title: "Two", createdMs: 2)
        try await repository.createConversation(a)
        try await repository.createConversation(b)
        try await repository.appendMessage(Self.message(a.id, 1, .user, "my hemoglobin was low"))
        try await repository.appendMessage(Self.message(b.id, 1, .user, "step count this week"))

        let hits = try await repository.search("hemoglobin")
        #expect(hits.map(\.id) == [a.id])
        #expect(try await repository.search("zzzz").isEmpty)
        // Folded before indexing and before MATCH, so case and accents do not matter.
        #expect(try await repository.search("HEMOGLOBIN").map(\.id) == [a.id])
        await repository.close()
    }

    @Test func unreferencedAttachmentsArePurged() async throws {
        let (repository, root) = try await Self.makeRepository()
        defer { try? FileManager.default.removeItem(at: root) }
        let orphanID = UUID().uuidString.lowercased()
        let path = try repository.files.writeOriginal(Data("x".utf8), attachmentID: orphanID, fileExtension: "jpg")
        try await repository.insertAttachment(ChatAttachment(
            id: orphanID, kind: .image, filename: "orphan.jpg", filePath: path, createdMs: 1))

        try await repository.purgeUnreferencedAttachments(nowMs: 2)
        #expect(try await repository.attachments(ids: [orphanID]).isEmpty)
        #expect(!repository.files.exists(path))
        await repository.close()
    }

    // MARK: - §12 migration

    @Test func legacyHistoryMovesInOnceWithItsImage() async throws {
        let (repository, root) = try await Self.makeRepository()
        defer { try? FileManager.default.removeItem(at: root) }
        let defaults = try #require(UserDefaults(suiteName: "coach-migration-\(UUID().uuidString)"))
        defer { defaults.removePersistentDomain(forName: defaults.description) }

        let legacy: [[String: Any]] = [
            ["id": UUID().uuidString, "role": "user", "content": "How did I sleep this week?",
             "timestamp": Date(timeIntervalSince1970: 1_700_000_000).timeIntervalSinceReferenceDate,
             "attachmentImageData": Data("jpeg".utf8).base64EncodedString()],
            ["id": UUID().uuidString, "role": "assistant", "content": "About seven hours.",
             "timestamp": Date(timeIntervalSince1970: 1_700_000_060).timeIntervalSinceReferenceDate],
        ]
        defaults.set(try JSONSerialization.data(withJSONObject: legacy), forKey: CoachMigration.legacyKey)

        let first = await CoachMigration.runIfNeeded(repository: repository, defaults: defaults, nowMs: 1)
        #expect(first.messageCount == 2)
        #expect(first.attachmentCount == 1)
        #expect(first.wasCorrupt == false)
        #expect(defaults.data(forKey: CoachMigration.legacyKey) == nil, "the legacy key survived")

        let conversationID = try #require(first.conversationID)
        let conversation = try #require(try await repository.conversation(id: conversationID))
        #expect(conversation.title == "How did I sleep this week")
        let messages = try await repository.messages(conversationID: conversationID)
        #expect(messages.count == 2)
        #expect(messages[0].attachmentIDs.count == 1)

        // Running again must not duplicate anything.
        let second = await CoachMigration.runIfNeeded(repository: repository, defaults: defaults, nowMs: 2)
        #expect(second == .nothingToDo)
        #expect(try await repository.conversationSummaries().count == 1)
        await repository.close()
    }

    @Test func aCorruptLegacyBlobLeavesAnEmptyStore() async throws {
        let (repository, root) = try await Self.makeRepository()
        defer { try? FileManager.default.removeItem(at: root) }
        let defaults = try #require(UserDefaults(suiteName: "coach-corrupt-\(UUID().uuidString)"))
        defer { defaults.removePersistentDomain(forName: defaults.description) }
        defaults.set(Data("not json".utf8), forKey: CoachMigration.legacyKey)

        let result = await CoachMigration.runIfNeeded(repository: repository, defaults: defaults, nowMs: 1)
        #expect(result.wasCorrupt)
        #expect(result.conversationID == nil)
        #expect(try await repository.conversationSummaries().isEmpty)
        #expect(defaults.data(forKey: CoachMigration.legacyKey) == nil)
        await repository.close()
    }

    @Test func noLegacyKeyIsANoOp() async throws {
        let (repository, root) = try await Self.makeRepository()
        defer { try? FileManager.default.removeItem(at: root) }
        let defaults = try #require(UserDefaults(suiteName: "coach-empty-\(UUID().uuidString)"))
        defer { defaults.removePersistentDomain(forName: defaults.description) }
        let result = await CoachMigration.runIfNeeded(repository: repository, defaults: defaults, nowMs: 1)
        #expect(result == .nothingToDo)
        await repository.close()
    }

    // MARK: - Privacy

    /// The Coach directory must never be swept into a device backup (docs/coach.md §2).
    @Test func theCoachDirectoryIsExcludedFromBackup() throws {
        let directory = CoachLocation.directory()
        try CoachLocation.prepareDirectory(directory)
        let values = try directory.resourceValues(forKeys: [.isExcludedFromBackupKey])
        #expect(values.isExcludedFromBackup == true)
    }

    @Test func aFileStorePathCannotEscapeItsRoot() throws {
        let store = CoachFileStore(root: FileManager.default.temporaryDirectory
            .appendingPathComponent("coach-escape-\(UUID().uuidString)", isDirectory: true))
        #expect(store.resolve("../../etc/passwd") == nil)
        #expect(store.resolve("/etc/passwd") == nil)
        #expect(store.resolve("abc/original.jpg") != nil)
    }
}
