import Foundation

/// Typed reads and writes over `coach.sqlite` (docs/coach.md §2, §7). Everything the UI needs runs
/// through here; `CoachStore` holds the observable state on top.
///
/// Deletes are **tombstones** (`deleted = 1`) so an import can never resurrect a conversation the
/// user removed (§11). Blobs, by contrast, are removed for real — `purgeDeleted` collects them.
actor CoachRepository {
    private let database: CoachDatabase
    nonisolated let files: CoachFileStore

    init(database: CoachDatabase, files: CoachFileStore) {
        self.database = database
        self.files = files
    }

    // MARK: - Conversations

    /// Newest first, pinned above the rest; tombstoned rows never appear.
    func conversationSummaries(includeArchived: Bool = false, limit: Int = 200) async throws -> [ConversationSummary] {
        try await database.withConnection { connection in
            var rows: [Conversation] = []
            let sql = """
            SELECT id, title, created_ms, updated_ms, last_message_ms, pinned, archived,
                   data_sources_json, selected_record_ids_json, provider_override, records_online_decision
            FROM conversations
            WHERE deleted = 0\(includeArchived ? "" : " AND archived = 0")
            ORDER BY pinned DESC, COALESCE(last_message_ms, updated_ms) DESC, created_ms DESC
            LIMIT ?
            """
            try connection.query(sql, [.int(Int64(limit))]) { rows.append(Self.conversation(from: $0)) }

            var summaries: [ConversationSummary] = []
            for conversation in rows {
                var snippet = ""
                var messages = 0
                var attachments = 0
                try connection.query("""
                SELECT content, attachment_ids_json FROM messages
                WHERE conversation_id = ? AND deleted = 0
                ORDER BY seq DESC, variant_index DESC LIMIT 1
                """, [.text(conversation.id)]) { statement in
                    snippet = CR.collapseWS(statement.text(0) ?? "")
                }
                messages = Int(try connection.scalarInt64(
                    "SELECT COUNT(*) FROM messages WHERE conversation_id = ? AND deleted = 0",
                    [.text(conversation.id)]) ?? 0)
                attachments = Int(try connection.scalarInt64("""
                SELECT COUNT(*) FROM messages
                WHERE conversation_id = ? AND deleted = 0
                  AND attachment_ids_json IS NOT NULL AND attachment_ids_json <> '[]'
                """, [.text(conversation.id)]) ?? 0)
                summaries.append(ConversationSummary(
                    conversation: conversation,
                    snippet: CR.cpCut(snippet, 140),
                    messageCount: messages,
                    attachmentCount: attachments
                ))
            }
            return summaries
        }
    }

    func conversation(id: String) async throws -> Conversation? {
        try await database.withConnection { connection in
            var found: Conversation?
            try connection.query("""
            SELECT id, title, created_ms, updated_ms, last_message_ms, pinned, archived,
                   data_sources_json, selected_record_ids_json, provider_override, records_online_decision
            FROM conversations WHERE id = ? AND deleted = 0
            """, [.text(id)]) { found = Self.conversation(from: $0) }
            return found
        }
    }

    /// The conversation to open on launch: the most recent non-archived one, or nil when there is none.
    func mostRecentConversationID() async throws -> String? {
        try await database.withConnection { connection in
            try connection.scalarText("""
            SELECT id FROM conversations WHERE deleted = 0 AND archived = 0
            ORDER BY COALESCE(last_message_ms, updated_ms) DESC, created_ms DESC LIMIT 1
            """)
        }
    }

    @discardableResult
    func createConversation(_ conversation: Conversation) async throws -> Conversation {
        try await database.withConnection { connection in
            try connection.run("""
            INSERT INTO conversations
              (id, title, created_ms, updated_ms, last_message_ms, pinned, archived, deleted,
               data_sources_json, selected_record_ids_json, provider_override, records_online_decision)
            VALUES (?, ?, ?, ?, ?, ?, ?, 0, ?, ?, ?, ?)
            """, [
                .text(conversation.id), .text(conversation.title), .int(conversation.createdMs),
                .int(conversation.updatedMs),
                conversation.lastMessageMs.map { .int($0) } ?? .null,
                .int(conversation.pinned ? 1 : 0), .int(conversation.archived ? 1 : 0),
                conversation.dataSources.json.map { .text($0) } ?? .null,
                Self.jsonArray(conversation.selectedRecordIDs).map { .text($0) } ?? .null,
                conversation.providerOverride.map { .text($0) } ?? .null,
                conversation.recordsOnlineDecision.map { .text($0) } ?? .null,
            ])
        }
        return conversation
    }

    func updateConversation(_ conversation: Conversation, nowMs: Int64) async throws {
        try await database.withConnection { connection in
            try connection.run("""
            UPDATE conversations
            SET title = ?, updated_ms = ?, last_message_ms = ?, pinned = ?, archived = ?,
                data_sources_json = ?, selected_record_ids_json = ?, provider_override = ?,
                records_online_decision = ?
            WHERE id = ?
            """, [
                .text(conversation.title), .int(nowMs),
                conversation.lastMessageMs.map { .int($0) } ?? .null,
                .int(conversation.pinned ? 1 : 0), .int(conversation.archived ? 1 : 0),
                conversation.dataSources.json.map { .text($0) } ?? .null,
                Self.jsonArray(conversation.selectedRecordIDs).map { .text($0) } ?? .null,
                conversation.providerOverride.map { .text($0) } ?? .null,
                conversation.recordsOnlineDecision.map { .text($0) } ?? .null,
                .text(conversation.id),
            ])
        }
    }

    func renameConversation(id: String, title: String, nowMs: Int64) async throws {
        try await database.withConnection { connection in
            try connection.run("UPDATE conversations SET title = ?, updated_ms = ? WHERE id = ?",
                               [.text(title), .int(nowMs), .text(id)])
        }
    }

    /// Tombstones the conversation and its messages, then removes the blobs of attachments no
    /// surviving message still references (§1 rule 5).
    func deleteConversation(id: String, nowMs: Int64) async throws {
        let orphans = try await database.inTransaction { [database] in
            try database.connection.run(
                "UPDATE conversations SET deleted = 1, updated_ms = ? WHERE id = ?",
                [.int(nowMs), .text(id)])
            try database.connection.run(
                "UPDATE messages SET deleted = 1, updated_ms = ? WHERE conversation_id = ?",
                [.int(nowMs), .text(id)])
            try database.connection.run("""
            DELETE FROM messages_fts WHERE docid IN
              (SELECT doc_id FROM messages WHERE conversation_id = ?)
            """, [.text(id)])
            return try Self.unreferencedAttachmentIDs(database.connection)
        }
        try await removeAttachments(orphans, nowMs: nowMs)
    }

    /// "Start again from this chat": the title, switches and record selection, none of the messages.
    func duplicateConversation(id: String, nowMs: Int64) async throws -> Conversation? {
        guard let source = try await conversation(id: id) else { return nil }
        let copy = Conversation(
            createdMs: nowMs,
            updatedMs: nowMs,
            pinned: false,
            archived: false,
            dataSources: source.dataSources,
            selectedRecordIDs: source.selectedRecordIDs,
            providerOverride: source.providerOverride
        )
        var titled = copy
        titled.title = source.title
        return try await createConversation(titled)
    }

    func setPinned(id: String, pinned: Bool, nowMs: Int64) async throws {
        try await database.withConnection { connection in
            try connection.run("UPDATE conversations SET pinned = ?, updated_ms = ? WHERE id = ?",
                               [.int(pinned ? 1 : 0), .int(nowMs), .text(id)])
        }
    }

    /// "Delete all chats": every row tombstoned is pointless here — the user asked for it gone, so the
    /// tables and the blobs are emptied.
    func deleteEverything() async throws {
        try await database.wipeAllData()
        files.deleteAll()
    }

    // MARK: - Messages

    func messages(conversationID: String) async throws -> [ChatMessage] {
        try await database.withConnection { connection in
            var rows: [ChatMessage] = []
            try connection.query("""
            SELECT id, conversation_id, seq, variant_index, role, content, created_ms, updated_ms,
                   regenerated_from, record_refs_json, attachment_ids_json
            FROM messages
            WHERE conversation_id = ? AND deleted = 0
            ORDER BY seq ASC, variant_index ASC
            """, [.text(conversationID)]) { rows.append(Self.message(from: $0)) }
            return Self.latestVariants(rows)
        }
    }

    /// Every row of a conversation, all variants included — what the export and the regenerate plan
    /// read (§10).
    func allMessages(conversationID: String) async throws -> [ChatMessage] {
        try await database.withConnection { connection in
            var rows: [ChatMessage] = []
            try connection.query("""
            SELECT id, conversation_id, seq, variant_index, role, content, created_ms, updated_ms,
                   regenerated_from, record_refs_json, attachment_ids_json
            FROM messages
            WHERE conversation_id = ? AND deleted = 0
            ORDER BY seq ASC, variant_index ASC
            """, [.text(conversationID)]) { rows.append(Self.message(from: $0)) }
            return rows
        }
    }

    /// Every stored version of one reply, oldest first, for the `‹ 1/2 ›` stepper (§10).
    func variants(conversationID: String, seq: Int) async throws -> [ChatMessage] {
        try await database.withConnection { connection in
            var rows: [ChatMessage] = []
            try connection.query("""
            SELECT id, conversation_id, seq, variant_index, role, content, created_ms, updated_ms,
                   regenerated_from, record_refs_json, attachment_ids_json
            FROM messages
            WHERE conversation_id = ? AND seq = ? AND deleted = 0
            ORDER BY variant_index ASC
            """, [.text(conversationID), .int(Int64(seq))]) { rows.append(Self.message(from: $0)) }
            return rows
        }
    }

    func nextSeq(conversationID: String) async throws -> Int {
        try await database.withConnection { connection in
            Int(try connection.scalarInt64(
                "SELECT COALESCE(MAX(seq), 0) + 1 FROM messages WHERE conversation_id = ?",
                [.text(conversationID)]) ?? 1)
        }
    }

    @discardableResult
    func appendMessage(_ message: ChatMessage) async throws -> ChatMessage {
        try await database.inTransaction { [database] in
            try Self.insert(message, into: database.connection)
            try database.connection.run("""
            UPDATE conversations SET last_message_ms = ?, updated_ms = ? WHERE id = ?
            """, [.int(message.createdMs), .int(message.updatedMs), .text(message.conversationID)])
        }
        return message
    }

    /// Replaces an assistant reply's text in place (streaming and error-retry paths).
    func updateMessageContent(id: String, content: String, nowMs: Int64) async throws {
        try await database.inTransaction { [database] in
            try database.connection.run(
                "UPDATE messages SET content = ?, updated_ms = ? WHERE id = ?",
                [.text(content), .int(nowMs), .text(id)])
            if let docID = try database.connection.scalarInt64(
                "SELECT doc_id FROM messages WHERE id = ?", [.text(id)]) {
                try database.connection.run("DELETE FROM messages_fts WHERE docid = ?", [.int(docID)])
                try database.connection.run("INSERT INTO messages_fts (docid, content) VALUES (?, ?)",
                                            [.int(docID), .text(RR.fold(content))])
            }
        }
    }

    func deleteMessage(id: String, nowMs: Int64) async throws {
        try await database.inTransaction { [database] in
            try database.connection.run("UPDATE messages SET deleted = 1, updated_ms = ? WHERE id = ?",
                                        [.int(nowMs), .text(id)])
            try database.connection.run(
                "DELETE FROM messages_fts WHERE docid IN (SELECT doc_id FROM messages WHERE id = ?)",
                [.text(id)])
        }
    }

    // MARK: - Search

    /// Conversations whose messages match `query`, most recently active first. Text is folded before
    /// indexing and before MATCH, so search behaves the same on both platforms and for non-ASCII.
    func search(_ query: String, limit: Int = 50) async throws -> [ConversationSummary] {
        let terms = RR.fold(query).split(separator: " ").filter { !$0.isEmpty }
        guard !terms.isEmpty else { return try await conversationSummaries() }
        let match = terms.map { "\($0)*" }.joined(separator: " ")
        let ids: [String] = try await database.withConnection { connection in
            var out: [String] = []
            try connection.query("""
            SELECT DISTINCT m.conversation_id FROM messages_fts f
            JOIN messages m ON m.doc_id = f.docid
            JOIN conversations c ON c.id = m.conversation_id
            WHERE messages_fts MATCH ? AND m.deleted = 0 AND c.deleted = 0
            LIMIT ?
            """, [.text(match), .int(Int64(limit))]) { statement in
                if let id = statement.text(0) { out.append(id) }
            }
            return out
        }
        let all = try await conversationSummaries(includeArchived: true, limit: 500)
        let wanted = Set(ids)
        return all.filter { wanted.contains($0.id) }
    }

    // MARK: - Attachments

    @discardableResult
    func insertAttachment(_ attachment: ChatAttachment) async throws -> ChatAttachment {
        try await database.withConnection { connection in
            try connection.run("""
            INSERT INTO attachments
              (id, kind, filename, mime_type, bytes, sha256, page_count, char_count, excerpt,
               file_path, thumbnail_path, created_ms, deleted)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0)
            """, [
                .text(attachment.id), .text(attachment.kind.rawValue), .text(attachment.filename),
                attachment.mimeType.map { .text($0) } ?? .null, .int(Int64(attachment.bytes)),
                attachment.sha256.map { .text($0) } ?? .null,
                attachment.pageCount.map { .int(Int64($0)) } ?? .null,
                attachment.charCount.map { .int(Int64($0)) } ?? .null,
                attachment.excerpt.map { .text($0) } ?? .null,
                attachment.filePath.map { .text($0) } ?? .null,
                attachment.thumbnailPath.map { .text($0) } ?? .null,
                .int(attachment.createdMs),
            ])
        }
        return attachment
    }

    func attachments(ids: [String]) async throws -> [ChatAttachment] {
        guard !ids.isEmpty else { return [] }
        return try await database.withConnection { connection in
            var out: [String: ChatAttachment] = [:]
            for id in ids {
                try connection.query("""
                SELECT id, kind, filename, mime_type, bytes, sha256, page_count, char_count, excerpt,
                       file_path, thumbnail_path, created_ms
                FROM attachments WHERE id = ? AND deleted = 0
                """, [.text(id)]) { out[id] = Self.attachment(from: $0) }
            }
            return ids.compactMap { out[$0] }
        }
    }

    /// Removes the rows and the blobs of attachments nothing references any more.
    func purgeUnreferencedAttachments(nowMs: Int64) async throws {
        let orphans = try await database.withConnection { try Self.unreferencedAttachmentIDs($0) }
        try await removeAttachments(orphans, nowMs: nowMs)
        // A crash between the row write and the file write can leave a folder with no row.
        let known = try await database.withConnection { connection -> Set<String> in
            var ids = Set<String>()
            try connection.query("SELECT id FROM attachments") { if let id = $0.text(0) { ids.insert(id) } }
            return ids
        }
        for stray in files.orphanedDirectories(knownIDs: known) {
            files.delete(attachmentID: stray)
        }
    }

    private func removeAttachments(_ ids: [String], nowMs: Int64) async throws {
        guard !ids.isEmpty else { return }
        try await database.withConnection { connection in
            for id in ids {
                try connection.run("DELETE FROM attachments WHERE id = ?", [.text(id)])
            }
        }
        for id in ids {
            files.delete(attachmentID: id)
        }
    }

    // MARK: - Archive (docs/coach.md §11)

    /// The whole store as `CR` sees it, **tombstones included** — a merge has to know what the user
    /// deleted so an import can never resurrect it.
    func snapshotValue() async throws -> RJ {
        try await database.withConnection { connection in
            var conversations: [RJ] = []
            try connection.query("""
            SELECT id, title, created_ms, updated_ms, last_message_ms, pinned, archived,
                   data_sources_json, selected_record_ids_json, provider_override,
                   records_online_decision, deleted
            FROM conversations ORDER BY created_ms, id
            """) { statement in
                conversations.append(Self.withDeleted(Self.conversation(from: statement).referenceValue,
                                                      statement.int(11) ?? 0))
            }
            var messages: [RJ] = []
            try connection.query("""
            SELECT id, conversation_id, seq, variant_index, role, content, created_ms, updated_ms,
                   regenerated_from, record_refs_json, attachment_ids_json, deleted
            FROM messages ORDER BY conversation_id, seq, id
            """) { statement in
                messages.append(Self.withDeleted(Self.message(from: statement).referenceValue,
                                                 statement.int(11) ?? 0))
            }
            var attachments: [RJ] = []
            try connection.query("""
            SELECT id, kind, filename, mime_type, bytes, sha256, page_count, char_count, excerpt,
                   file_path, thumbnail_path, created_ms, deleted
            FROM attachments ORDER BY created_ms, id
            """) { statement in
                attachments.append(Self.withDeleted(Self.attachment(from: statement).referenceValue,
                                                    statement.int(12) ?? 0))
            }
            return .obj([
                "conversations": .arr(conversations),
                "messages": .arr(messages),
                "attachments": .arr(attachments),
            ])
        }
    }

    /// Writes a merged snapshot back. Only the reference's own columns are touched, so a row's local
    /// blob paths and its FTS entry survive an import that only changed text.
    func applySnapshot(_ snapshot: RJ) async throws {
        try await database.inTransaction { [database] in
            let connection = database.connection
            for row in snapshot["conversations"].array ?? [] {
                guard let id = row["id"].string, !id.isEmpty else { continue }
                try connection.run("""
                INSERT INTO conversations
                  (id, title, created_ms, updated_ms, last_message_ms, pinned, archived, deleted,
                   data_sources_json, selected_record_ids_json, provider_override)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(id) DO UPDATE SET
                  title = excluded.title, created_ms = excluded.created_ms,
                  updated_ms = excluded.updated_ms, last_message_ms = excluded.last_message_ms,
                  pinned = excluded.pinned, archived = excluded.archived, deleted = excluded.deleted,
                  data_sources_json = excluded.data_sources_json,
                  selected_record_ids_json = excluded.selected_record_ids_json,
                  provider_override = excluded.provider_override
                """, [
                    .text(id), .text(row["title"].string ?? ""),
                    .int(Int64(row["created_ms"].double ?? 0)), .int(Int64(row["updated_ms"].double ?? 0)),
                    row["last_message_ms"].double.map { .int(Int64($0)) } ?? .null,
                    .int(row["pinned"].truthy ? 1 : 0), .int(row["archived"].truthy ? 1 : 0),
                    .int(row["deleted"].truthy ? 1 : 0),
                    Self.switchesJSON(row["data_sources"]).map { .text($0) } ?? .null,
                    Self.encodeStringArray((row["selected_record_ids"].array ?? []).compactMap(\.string))
                        .map { .text($0) } ?? .null,
                    row["provider_override"].string.map { .text($0) } ?? .null,
                ])
            }
            for row in snapshot["messages"].array ?? [] {
                guard let id = row["id"].string, !id.isEmpty else { continue }
                try Self.upsertMessage(row, id: id, into: connection)
            }
            for row in snapshot["attachments"].array ?? [] {
                guard let id = row["id"].string, !id.isEmpty else { continue }
                try connection.run("""
                INSERT INTO attachments
                  (id, kind, filename, mime_type, bytes, sha256, page_count, char_count, excerpt,
                   created_ms, deleted)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(id) DO UPDATE SET
                  kind = excluded.kind, filename = excluded.filename, mime_type = excluded.mime_type,
                  bytes = excluded.bytes, sha256 = excluded.sha256, page_count = excluded.page_count,
                  char_count = excluded.char_count, excerpt = excluded.excerpt,
                  created_ms = excluded.created_ms, deleted = excluded.deleted
                """, [
                    .text(id), .text(row["kind"].string ?? "text"), .text(row["filename"].string ?? ""),
                    row["mime_type"].string.map { .text($0) } ?? .null,
                    .int(Int64(row["bytes"].double ?? 0)),
                    row["sha256"].string.map { .text($0) } ?? .null,
                    row["page_count"].double.map { .int(Int64($0)) } ?? .null,
                    row["char_count"].double.map { .int(Int64($0)) } ?? .null,
                    row["excerpt"].string.map { .text($0) } ?? .null,
                    .int(Int64(row["created_ms"].double ?? 0)),
                    .int(row["deleted"].truthy ? 1 : 0),
                ])
            }
        }
    }

    /// Points an imported attachment row at the blob the archive carried.
    func setAttachmentFile(id: String, path: String?) async throws {
        try await database.withConnection { connection in
            try connection.run("UPDATE attachments SET file_path = ? WHERE id = ?",
                               [path.map { .text($0) } ?? .null, .text(id)])
        }
    }

    /// Keeps the row's `doc_id`, so the FTS entry it owns stays the one this message writes to.
    private static func upsertMessage(_ row: RJ, id: String, into connection: HealthDBConnection) throws {
        let content = row["content"].string ?? ""
        let deleted = row["deleted"].truthy
        let existing = try connection.scalarInt64("SELECT doc_id FROM messages WHERE id = ?", [.text(id)])
        if let existing {
            try connection.run("DELETE FROM messages_fts WHERE docid = ?", [.int(existing)])
        }
        try connection.run("""
        INSERT INTO messages
          (id, conversation_id, seq, variant_index, role, content, created_ms, updated_ms, deleted,
           regenerated_from, record_refs_json, attachment_ids_json)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        ON CONFLICT(id) DO UPDATE SET
          conversation_id = excluded.conversation_id, seq = excluded.seq,
          variant_index = excluded.variant_index, role = excluded.role, content = excluded.content,
          created_ms = excluded.created_ms, updated_ms = excluded.updated_ms,
          deleted = excluded.deleted, regenerated_from = excluded.regenerated_from,
          record_refs_json = excluded.record_refs_json,
          attachment_ids_json = excluded.attachment_ids_json
        """, [
            .text(id), .text(row["conversation_id"].string ?? ""),
            .int(Int64(row["seq"].double ?? 0)), .int(Int64(row["variant_index"].double ?? 0)),
            .text(row["role"].string ?? "user"), .text(content),
            .int(Int64(row["created_ms"].double ?? 0)), .int(Int64(row["updated_ms"].double ?? 0)),
            .int(deleted ? 1 : 0),
            row["regenerated_from"].string.map { .text($0) } ?? .null,
            Self.refsJSON(row["record_refs"]).map { .text($0) } ?? .null,
            Self.encodeStringArray((row["attachment_ids"].array ?? []).compactMap(\.string))
                .map { .text($0) } ?? .null,
        ])
        guard !deleted,
              let docID = try connection.scalarInt64("SELECT doc_id FROM messages WHERE id = ?", [.text(id)])
        else { return }
        try connection.run("INSERT INTO messages_fts (docid, content) VALUES (?, ?)",
                           [.int(docID), .text(RR.fold(content))])
    }

    private static func withDeleted(_ row: RJ, _ deleted: Int) -> RJ {
        guard case .obj(var dict) = row else { return row }
        dict["deleted"] = .int(deleted)
        return .obj(dict)
    }

    /// `{"health": false}` — only the off switches, exactly as the column stores them.
    private static func switchesJSON(_ value: RJ) -> String? {
        guard case .obj(let dict) = value else { return nil }
        let off = dict.filter { !$0.value.truthy }.mapValues { _ in false }
        guard !off.isEmpty, let data = try? JSONEncoder().encode(off) else { return nil }
        return String(data: data, encoding: .utf8)
    }

    private static func refsJSON(_ value: RJ) -> String? {
        let refs = (value.array ?? []).compactMap { entry -> ChatRecordRef? in
            guard let recordID = entry["record_id"].string else { return nil }
            return ChatRecordRef(recordID: recordID,
                                 title: entry["title"].string ?? "",
                                 date: entry["date"].string ?? "")
        }
        return encodeRecordRefs(refs)
    }

    // MARK: - Meta

    func meta(_ key: String) async throws -> String? { try await database.meta(key) }
    func setMeta(_ key: String, _ value: String) async throws { try await database.setMeta(key, value) }

    func close() async { await database.close() }

    // MARK: - Row mapping

    private static func unreferencedAttachmentIDs(_ connection: HealthDBConnection) throws -> [String] {
        var referenced = Set<String>()
        try connection.query("SELECT attachment_ids_json FROM messages WHERE deleted = 0") { statement in
            for id in decodeStringArray(statement.text(0)) { referenced.insert(id) }
        }
        var orphans: [String] = []
        try connection.query("SELECT id FROM attachments") { statement in
            if let id = statement.text(0), !referenced.contains(id) { orphans.append(id) }
        }
        return orphans
    }

    /// Only the newest variant of each reply is shown in the transcript; the stepper reaches the rest.
    static func latestVariants(_ rows: [ChatMessage]) -> [ChatMessage] {
        var best: [Int: ChatMessage] = [:]
        var order: [Int] = []
        for row in rows {
            if best[row.seq] == nil { order.append(row.seq) }
            if let existing = best[row.seq], existing.variantIndex >= row.variantIndex { continue }
            best[row.seq] = row
        }
        return order.compactMap { best[$0] }
    }

    private static func insert(_ message: ChatMessage, into connection: HealthDBConnection) throws {
        try connection.run("""
        INSERT INTO messages
          (id, conversation_id, seq, variant_index, role, content, created_ms, updated_ms, deleted,
           regenerated_from, record_refs_json, attachment_ids_json)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, 0, ?, ?, ?)
        """, [
            .text(message.id), .text(message.conversationID), .int(Int64(message.seq)),
            .int(Int64(message.variantIndex)), .text(message.role.rawValue), .text(message.content),
            .int(message.createdMs), .int(message.updatedMs),
            message.regeneratedFrom.map { .text($0) } ?? .null,
            encodeRecordRefs(message.recordRefs).map { .text($0) } ?? .null,
            encodeStringArray(message.attachmentIDs).map { .text($0) } ?? .null,
        ])
        if let docID = try connection.scalarInt64("SELECT doc_id FROM messages WHERE id = ?", [.text(message.id)]) {
            try connection.run("INSERT INTO messages_fts (docid, content) VALUES (?, ?)",
                               [.int(docID), .text(RR.fold(message.content))])
        }
    }

    private static func conversation(from statement: HealthDBStatement) -> Conversation {
        Conversation(
            id: statement.text(0) ?? "",
            title: statement.text(1) ?? "",
            createdMs: statement.int64(2) ?? 0,
            updatedMs: statement.int64(3) ?? 0,
            lastMessageMs: statement.int64(4),
            pinned: (statement.int(5) ?? 0) != 0,
            archived: (statement.int(6) ?? 0) != 0,
            dataSources: CoachDataSwitches.fromJSON(statement.text(7)),
            selectedRecordIDs: decodeStringArray(statement.text(8)),
            providerOverride: statement.text(9),
            recordsOnlineDecision: statement.text(10)
        )
    }

    private static func message(from statement: HealthDBStatement) -> ChatMessage {
        ChatMessage(
            id: statement.text(0) ?? "",
            conversationID: statement.text(1) ?? "",
            seq: statement.int(2) ?? 1,
            variantIndex: statement.int(3) ?? 0,
            role: ChatMessage.Role(rawValue: statement.text(4) ?? "user") ?? .user,
            content: statement.text(5) ?? "",
            createdMs: statement.int64(6) ?? 0,
            updatedMs: statement.int64(7) ?? 0,
            regeneratedFrom: statement.text(8),
            recordRefs: decodeRecordRefs(statement.text(9)),
            attachmentIDs: decodeStringArray(statement.text(10))
        )
    }

    private static func attachment(from statement: HealthDBStatement) -> ChatAttachment {
        ChatAttachment(
            id: statement.text(0) ?? "",
            kind: ChatAttachment.Kind(raw: statement.text(1)),
            filename: statement.text(2) ?? "",
            mimeType: statement.text(3),
            bytes: statement.int(4) ?? 0,
            sha256: statement.text(5),
            pageCount: statement.int(6),
            charCount: statement.int(7),
            excerpt: statement.text(8),
            filePath: statement.text(9),
            thumbnailPath: statement.text(10),
            createdMs: statement.int64(11) ?? 0
        )
    }

    // MARK: - JSON columns

    static func encodeStringArray(_ values: [String]) -> String? {
        guard !values.isEmpty, let data = try? JSONEncoder().encode(values) else { return nil }
        return String(data: data, encoding: .utf8)
    }

    static func decodeStringArray(_ text: String?) -> [String] {
        guard let text, let data = text.data(using: .utf8),
              let decoded = try? JSONDecoder().decode([String].self, from: data)
        else { return [] }
        return decoded
    }

    static func encodeRecordRefs(_ refs: [ChatRecordRef]?) -> String? {
        guard let refs, !refs.isEmpty, let data = try? JSONEncoder().encode(refs) else { return nil }
        return String(data: data, encoding: .utf8)
    }

    static func decodeRecordRefs(_ text: String?) -> [ChatRecordRef]? {
        guard let text, let data = text.data(using: .utf8),
              let decoded = try? JSONDecoder().decode([ChatRecordRef].self, from: data)
        else { return nil }
        return decoded.isEmpty ? nil : decoded
    }

    private static func jsonArray(_ values: [String]) -> String? { encodeStringArray(values) }
}
