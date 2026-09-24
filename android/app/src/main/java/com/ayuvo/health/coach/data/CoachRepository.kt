package com.ayuvo.health.coach.data

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import com.ayuvo.health.coach.model.AttachmentKind
import com.ayuvo.health.coach.model.ChatAttachment
import com.ayuvo.health.coach.model.CoachDataSwitches
import com.ayuvo.health.coach.model.CoachMessage
import com.ayuvo.health.coach.model.CoachSource
import com.ayuvo.health.coach.model.Conversation
import com.ayuvo.health.coach.model.ConversationSummary
import com.ayuvo.health.coach.logic.CoachReference
import com.ayuvo.health.medications.logic.MedicationJson
import com.ayuvo.health.medications.logic.MedicationJson.arr
import com.ayuvo.health.medications.logic.MedicationJson.long
import com.ayuvo.health.medications.logic.MedicationJson.str
import com.ayuvo.health.records.coach.CoachRecordRef
import com.ayuvo.health.records.processing.RecordText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.builtins.serializer

/**
 * Typed reads and writes over `ayuvo_coach.db` (docs/coach.md §2, §7). Port of iOS
 * `CoachRepository`; every call runs on [Dispatchers.IO].
 *
 * Deletes are **tombstones** (`deleted = 1`) so an import can never resurrect a conversation the
 * user removed (§11). Blobs, by contrast, are removed for real.
 */
class CoachRepository(
    private val helper: CoachDatabase,
    val files: CoachFileStore
) {
    private val json = MedicationJson.json

    private suspend fun <T> read(body: (SQLiteDatabase) -> T): T =
        withContext(Dispatchers.IO) { body(helper.readableDatabase) }

    private suspend fun <T> write(body: (SQLiteDatabase) -> T): T =
        withContext(Dispatchers.IO) { body(helper.writableDatabase) }

    // -- Conversations ---------------------------------------------------------------------------

    private val conversationColumns =
        "id, title, created_ms, updated_ms, last_message_ms, pinned, archived, " +
            "data_sources_json, selected_record_ids_json, provider_override, records_online_decision"

    /** Newest first, pinned above the rest; tombstoned rows never appear. */
    suspend fun conversationSummaries(includeArchived: Boolean = false, limit: Int = 200): List<ConversationSummary> =
        read { db ->
            val where = if (includeArchived) "deleted = 0" else "deleted = 0 AND archived = 0"
            val rows = mutableListOf<Conversation>()
            db.rawQuery(
                "SELECT $conversationColumns FROM conversations WHERE $where " +
                    "ORDER BY pinned DESC, COALESCE(last_message_ms, updated_ms) DESC, created_ms DESC LIMIT ?",
                arrayOf(limit.toString())
            ).use { c -> while (c.moveToNext()) rows += conversation(c) }

            rows.map { conversation ->
                var snippet = ""
                db.rawQuery(
                    "SELECT content FROM messages WHERE conversation_id = ? AND deleted = 0 " +
                        "ORDER BY seq DESC, variant_index DESC LIMIT 1",
                    arrayOf(conversation.id)
                ).use { c -> if (c.moveToNext()) snippet = CoachReference.collapseWs(c.getString(0)) }
                val messages = countOf(db,
                    "SELECT COUNT(*) FROM messages WHERE conversation_id = ? AND deleted = 0",
                    conversation.id)
                val attachments = countOf(db,
                    "SELECT COUNT(*) FROM messages WHERE conversation_id = ? AND deleted = 0 " +
                        "AND attachment_ids_json IS NOT NULL AND attachment_ids_json <> '[]'",
                    conversation.id)
                ConversationSummary(conversation, CoachReference.cpCut(snippet, 140), messages, attachments)
            }
        }

    suspend fun conversation(id: String): Conversation? = read { db ->
        db.rawQuery(
            "SELECT $conversationColumns FROM conversations WHERE id = ? AND deleted = 0",
            arrayOf(id)
        ).use { c -> if (c.moveToNext()) conversation(c) else null }
    }

    /** The conversation to open on launch: the most recent non-archived one, or null. */
    suspend fun mostRecentConversationId(): String? = read { db ->
        db.rawQuery(
            "SELECT id FROM conversations WHERE deleted = 0 AND archived = 0 " +
                "ORDER BY COALESCE(last_message_ms, updated_ms) DESC, created_ms DESC LIMIT 1",
            null
        ).use { c -> if (c.moveToNext()) c.getString(0) else null }
    }

    suspend fun createConversation(conversation: Conversation): Conversation = write { db ->
        db.insertWithOnConflict("conversations", null, values(conversation), SQLiteDatabase.CONFLICT_REPLACE)
        conversation
    }

    suspend fun updateConversation(conversation: Conversation, nowMs: Long) {
        write { db ->
            val cv = values(conversation.copy(updatedMs = nowMs))
            cv.remove("id")
            cv.remove("created_ms")
            db.update("conversations", cv, "id = ?", arrayOf(conversation.id))
        }
    }

    suspend fun renameConversation(id: String, title: String, nowMs: Long) {
        write { db ->
            val cv = ContentValues().apply {
                put("title", title)
                put("updated_ms", nowMs)
            }
            db.update("conversations", cv, "id = ?", arrayOf(id))
        }
    }

    /**
     * Tombstones the conversation and its messages, then removes the blobs of attachments no
     * surviving message still references (§1 rule 5).
     */
    suspend fun deleteConversation(id: String, nowMs: Long) {
        val orphans = write { db ->
            db.beginTransaction()
            try {
                db.execSQL("UPDATE conversations SET deleted = 1, updated_ms = ? WHERE id = ?",
                           arrayOf<Any>(nowMs, id))
                db.execSQL(
                    "DELETE FROM messages_fts WHERE docid IN " +
                        "(SELECT doc_id FROM messages WHERE conversation_id = ?)",
                    arrayOf<Any>(id))
                db.execSQL("UPDATE messages SET deleted = 1, updated_ms = ? WHERE conversation_id = ?",
                           arrayOf<Any>(nowMs, id))
                val ids = unreferencedAttachmentIds(db)
                db.setTransactionSuccessful()
                ids
            } finally {
                db.endTransaction()
            }
        }
        removeAttachments(orphans)
    }

    /** "Start again from this chat": the title, switches and record selection, none of the messages. */
    suspend fun duplicateConversation(id: String, nowMs: Long): Conversation? {
        val source = conversation(id) ?: return null
        val copy = Conversation(
            title = source.title,
            createdMs = nowMs,
            updatedMs = nowMs,
            dataSources = source.dataSources,
            selectedRecordIds = source.selectedRecordIds,
            providerOverride = source.providerOverride
        )
        return createConversation(copy)
    }

    suspend fun setPinned(id: String, pinned: Boolean, nowMs: Long) {
        write { db ->
            val cv = ContentValues().apply {
                put("pinned", if (pinned) 1 else 0)
                put("updated_ms", nowMs)
            }
            db.update("conversations", cv, "id = ?", arrayOf(id))
        }
    }

    /** "Delete all chats": the user asked for it gone, so the tables and the blobs are emptied. */
    suspend fun deleteEverything() {
        write { db ->
            db.beginTransaction()
            try {
                db.execSQL("DELETE FROM messages_fts")
                db.execSQL("DELETE FROM messages")
                db.execSQL("DELETE FROM attachments")
                db.execSQL("DELETE FROM conversations")
                db.execSQL("DELETE FROM coach_meta WHERE key <> 'schema_version'")
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }
        files.deleteAll()
    }

    // -- Messages --------------------------------------------------------------------------------

    private val messageColumns =
        "id, conversation_id, seq, variant_index, role, content, created_ms, updated_ms, " +
            "regenerated_from, record_refs_json, attachment_ids_json"

    suspend fun messages(conversationId: String): List<CoachMessage> = read { db ->
        val rows = mutableListOf<CoachMessage>()
        db.rawQuery(
            "SELECT $messageColumns FROM messages WHERE conversation_id = ? AND deleted = 0 " +
                "ORDER BY seq ASC, variant_index ASC",
            arrayOf(conversationId)
        ).use { c -> while (c.moveToNext()) rows += message(c) }
        latestVariants(rows)
    }

    /**
     * Every row of a conversation, all variants included — what the export and the regenerate plan
     * read (§10).
     */
    suspend fun allMessages(conversationId: String): List<CoachMessage> = read { db ->
        val rows = mutableListOf<CoachMessage>()
        db.rawQuery(
            "SELECT $messageColumns FROM messages WHERE conversation_id = ? AND deleted = 0 " +
                "ORDER BY seq ASC, variant_index ASC",
            arrayOf(conversationId)
        ).use { c -> while (c.moveToNext()) rows += message(c) }
        rows
    }

    /** Every stored version of one reply, oldest first, for the `‹ 1/2 ›` stepper (§10). */
    suspend fun variants(conversationId: String, seq: Int): List<CoachMessage> = read { db ->
        val rows = mutableListOf<CoachMessage>()
        db.rawQuery(
            "SELECT $messageColumns FROM messages WHERE conversation_id = ? AND seq = ? AND deleted = 0 " +
                "ORDER BY variant_index ASC",
            arrayOf(conversationId, seq.toString())
        ).use { c -> while (c.moveToNext()) rows += message(c) }
        rows
    }

    suspend fun nextSeq(conversationId: String): Int = read { db ->
        db.rawQuery(
            "SELECT COALESCE(MAX(seq), 0) + 1 FROM messages WHERE conversation_id = ?",
            arrayOf(conversationId)
        ).use { c -> if (c.moveToNext()) c.getInt(0) else 1 }
    }

    suspend fun appendMessage(message: CoachMessage): CoachMessage = write { db ->
        db.beginTransaction()
        try {
            val docId = db.insertWithOnConflict("messages", null, values(message), SQLiteDatabase.CONFLICT_REPLACE)
            if (docId > 0) {
                val cv = ContentValues().apply {
                    put("docid", docId)
                    put("content", RecordText.fold(message.content))
                }
                db.insertWithOnConflict("messages_fts", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
            }
            db.execSQL(
                "UPDATE conversations SET last_message_ms = ?, updated_ms = ? WHERE id = ?",
                arrayOf<Any>(message.createdMs, message.updatedMs, message.conversationId))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        message
    }

    /** Replaces an assistant reply's text in place (streaming and error-retry paths). */
    suspend fun updateMessageContent(id: String, content: String, nowMs: Long) {
        write { db ->
            db.beginTransaction()
            try {
                db.execSQL("UPDATE messages SET content = ?, updated_ms = ? WHERE id = ?",
                           arrayOf<Any>(content, nowMs, id))
                val docId = db.rawQuery("SELECT doc_id FROM messages WHERE id = ?", arrayOf(id))
                    .use { c -> if (c.moveToNext()) c.getLong(0) else null }
                if (docId != null) {
                    db.execSQL("DELETE FROM messages_fts WHERE docid = ?", arrayOf<Any>(docId))
                    val cv = ContentValues().apply {
                        put("docid", docId)
                        put("content", RecordText.fold(content))
                    }
                    db.insertWithOnConflict("messages_fts", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }
    }

    suspend fun deleteMessage(id: String, nowMs: Long) {
        write { db ->
            db.beginTransaction()
            try {
                db.execSQL(
                    "DELETE FROM messages_fts WHERE docid IN (SELECT doc_id FROM messages WHERE id = ?)",
                    arrayOf<Any>(id))
                db.execSQL("UPDATE messages SET deleted = 1, updated_ms = ? WHERE id = ?",
                           arrayOf<Any>(nowMs, id))
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }
    }

    // -- Search ----------------------------------------------------------------------------------

    /**
     * Conversations whose messages match [query], most recently active first. Text is folded before
     * indexing and before MATCH, so search behaves the same on both platforms and for non-ASCII.
     */
    suspend fun search(query: String, limit: Int = 50): List<ConversationSummary> {
        val terms = RecordText.fold(query).split(" ").filter { it.isNotEmpty() }
        if (terms.isEmpty()) return conversationSummaries()
        val match = terms.joinToString(" ") { "$it*" }
        val ids = read { db ->
            val out = mutableListOf<String>()
            db.rawQuery(
                "SELECT DISTINCT m.conversation_id FROM messages_fts f " +
                    "JOIN messages m ON m.doc_id = f.docid " +
                    "JOIN conversations c ON c.id = m.conversation_id " +
                    "WHERE messages_fts MATCH ? AND m.deleted = 0 AND c.deleted = 0 LIMIT ?",
                arrayOf(match, limit.toString())
            ).use { c -> while (c.moveToNext()) out += c.getString(0) }
            out
        }.toSet()
        return conversationSummaries(includeArchived = true, limit = 500).filter { it.conversation.id in ids }
    }

    // -- Attachments -----------------------------------------------------------------------------

    private val attachmentColumns =
        "id, kind, filename, mime_type, bytes, sha256, page_count, char_count, excerpt, " +
            "file_path, thumbnail_path, created_ms"

    suspend fun insertAttachment(attachment: ChatAttachment): ChatAttachment = write { db ->
        db.insertWithOnConflict("attachments", null, values(attachment), SQLiteDatabase.CONFLICT_REPLACE)
        attachment
    }

    suspend fun attachments(ids: List<String>): List<ChatAttachment> {
        if (ids.isEmpty()) return emptyList()
        val byId = read { db ->
            val out = mutableMapOf<String, ChatAttachment>()
            for (id in ids) {
                db.rawQuery(
                    "SELECT $attachmentColumns FROM attachments WHERE id = ? AND deleted = 0",
                    arrayOf(id)
                ).use { c -> if (c.moveToNext()) out[id] = attachment(c) }
            }
            out
        }
        return ids.mapNotNull { byId[it] }
    }

    /** Removes the rows and the blobs of attachments nothing references any more. */
    suspend fun purgeUnreferencedAttachments() {
        val orphans = read { db -> unreferencedAttachmentIds(db) }
        removeAttachments(orphans)
        val known = read { db ->
            val ids = mutableSetOf<String>()
            db.rawQuery("SELECT id FROM attachments", null).use { c -> while (c.moveToNext()) ids += c.getString(0) }
            ids
        }
        // A crash between the row write and the file write can leave a folder with no row.
        withContext(Dispatchers.IO) {
            files.orphanedDirectories(known).forEach(files::delete)
        }
    }

    private suspend fun removeAttachments(ids: List<String>) {
        if (ids.isEmpty()) return
        write { db -> ids.forEach { db.delete("attachments", "id = ?", arrayOf(it)) } }
        withContext(Dispatchers.IO) { ids.forEach(files::delete) }
    }

    // -- Archive (docs/coach.md §11) ---------------------------------------------------------------

    /**
     * The whole store as `CoachReference` sees it, **tombstones included** — a merge has to know
     * what the user deleted so an import can never resurrect it.
     */
    suspend fun snapshotJson(): JsonObject = read { db ->
        val conversations = mutableListOf<JsonElement>()
        db.rawQuery("SELECT $conversationColumns, deleted FROM conversations ORDER BY created_ms, id", null)
            .use { c ->
                while (c.moveToNext()) conversations += withDeleted(referenceRow(conversation(c)), c.getInt(11))
            }
        val messages = mutableListOf<JsonElement>()
        db.rawQuery("SELECT $messageColumns, deleted FROM messages ORDER BY conversation_id, seq, id", null)
            .use { c ->
                while (c.moveToNext()) messages += withDeleted(referenceRow(message(c)), c.getInt(11))
            }
        val attachments = mutableListOf<JsonElement>()
        db.rawQuery("SELECT $attachmentColumns, deleted FROM attachments ORDER BY created_ms, id", null)
            .use { c ->
                while (c.moveToNext()) attachments += withDeleted(referenceRow(attachment(c)), c.getInt(12))
            }
        MedicationJson.obj(
            "conversations" to JsonArray(conversations),
            "messages" to JsonArray(messages),
            "attachments" to JsonArray(attachments)
        )
    }

    /**
     * Writes a merged snapshot back (docs/coach.md §11). Only the reference's own columns are
     * touched: a row's local blob paths and its FTS entry survive an import that only changed text.
     */
    suspend fun applySnapshot(snapshot: JsonObject) {
        write { db ->
            db.beginTransaction()
            try {
                for (row in snapshot.arr("conversations").orEmpty().filterIsInstance<JsonObject>()) {
                    val id = row.str("id") ?: continue
                    db.insertWithOnConflict("conversations", null, conversationValues(row, id),
                                            SQLiteDatabase.CONFLICT_REPLACE)
                }
                for (row in snapshot.arr("messages").orEmpty().filterIsInstance<JsonObject>()) {
                    val id = row.str("id") ?: continue
                    upsertMessage(db, row, id)
                }
                for (row in snapshot.arr("attachments").orEmpty().filterIsInstance<JsonObject>()) {
                    val id = row.str("id") ?: continue
                    db.insertWithOnConflict("attachments", null, attachmentValues(db, row, id),
                                            SQLiteDatabase.CONFLICT_REPLACE)
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }
    }

    /** Points an imported attachment row at the blob the archive carried. */
    suspend fun setAttachmentFile(id: String, relativePath: String?) {
        write { db ->
            db.update("attachments", ContentValues().apply {
                if (relativePath == null) putNull("file_path") else put("file_path", relativePath)
            }, "id = ?", arrayOf(id))
        }
    }

    private fun conversationValues(row: JsonObject, id: String) = ContentValues().apply {
        put("id", id)
        put("title", row.str("title") ?: "")
        put("created_ms", row.long("created_ms") ?: 0L)
        put("updated_ms", row.long("updated_ms") ?: 0L)
        row.long("last_message_ms")?.let { put("last_message_ms", it) } ?: putNull("last_message_ms")
        put("pinned", if (MedicationJson.truthy(row["pinned"])) 1 else 0)
        put("archived", if (MedicationJson.truthy(row["archived"])) 1 else 0)
        put("deleted", if (MedicationJson.truthy(row["deleted"])) 1 else 0)
        val switches = CoachDataSwitches.of(
            (row["data_sources"] as? JsonObject).orEmpty()
                .mapValues { (_, v) -> MedicationJson.truthy(v) }
        )
        encodeSwitches(switches)?.let { put("data_sources_json", it) } ?: putNull("data_sources_json")
        encodeStrings(MedicationJson.strings(row["selected_record_ids"]))
            ?.let { put("selected_record_ids_json", it) } ?: putNull("selected_record_ids_json")
        row.str("provider_override")?.let { put("provider_override", it) } ?: putNull("provider_override")
    }

    /** Keeps the row's `doc_id`, so the FTS entry it owns stays the one this message writes to. */
    private fun upsertMessage(db: SQLiteDatabase, row: JsonObject, id: String) {
        val content = row.str("content") ?: ""
        val values = ContentValues().apply {
            put("id", id)
            put("conversation_id", row.str("conversation_id") ?: "")
            put("seq", row.long("seq") ?: 0L)
            put("variant_index", row.long("variant_index") ?: 0L)
            put("role", row.str("role") ?: CoachMessage.Role.USER.raw)
            put("content", content)
            put("created_ms", row.long("created_ms") ?: 0L)
            put("updated_ms", row.long("updated_ms") ?: 0L)
            put("deleted", if (MedicationJson.truthy(row["deleted"])) 1 else 0)
            row.str("regenerated_from")?.let { put("regenerated_from", it) } ?: putNull("regenerated_from")
            row["record_refs"]?.takeIf { it is JsonArray && it.isNotEmpty() }
                ?.let { put("record_refs_json", MedicationJson.json.encodeToString(JsonElement.serializer(), it)) }
                ?: putNull("record_refs_json")
            encodeStrings(MedicationJson.strings(row["attachment_ids"]))
                ?.let { put("attachment_ids_json", it) } ?: putNull("attachment_ids_json")
        }
        val docId = db.rawQuery("SELECT doc_id FROM messages WHERE id = ?", arrayOf(id))
            .use { c -> if (c.moveToNext()) c.getLong(0) else null }
        if (docId != null) {
            values.put("doc_id", docId)
            db.execSQL("DELETE FROM messages_fts WHERE docid = ?", arrayOf<Any>(docId))
        }
        val written = db.insertWithOnConflict("messages", null, values, SQLiteDatabase.CONFLICT_REPLACE)
        val newDocId = docId ?: written
        if (newDocId > 0 && !MedicationJson.truthy(row["deleted"])) {
            db.execSQL("INSERT INTO messages_fts (docid, content) VALUES (?, ?)",
                       arrayOf<Any>(newDocId, RecordText.fold(content)))
        }
    }

    private fun attachmentValues(db: SQLiteDatabase, row: JsonObject, id: String) = ContentValues().apply {
        put("id", id)
        put("kind", row.str("kind") ?: AttachmentKind.TEXT.raw)
        put("filename", row.str("filename") ?: "")
        row.str("mime_type")?.let { put("mime_type", it) } ?: putNull("mime_type")
        put("bytes", row.long("bytes") ?: 0L)
        row.str("sha256")?.let { put("sha256", it) } ?: putNull("sha256")
        row.long("page_count")?.let { put("page_count", it) } ?: putNull("page_count")
        row.long("char_count")?.let { put("char_count", it) } ?: putNull("char_count")
        row.str("excerpt")?.let { put("excerpt", it) } ?: putNull("excerpt")
        put("created_ms", row.long("created_ms") ?: 0L)
        put("deleted", if (MedicationJson.truthy(row["deleted"])) 1 else 0)
        // The blob is the local copy's business; an import only adds one when it carried the file.
        db.rawQuery("SELECT file_path, thumbnail_path FROM attachments WHERE id = ?", arrayOf(id)).use { c ->
            if (c.moveToNext()) {
                if (!c.isNull(0)) put("file_path", c.getString(0)) else putNull("file_path")
                if (!c.isNull(1)) put("thumbnail_path", c.getString(1)) else putNull("thumbnail_path")
            } else {
                putNull("file_path")
                putNull("thumbnail_path")
            }
        }
    }

    private fun withDeleted(row: JsonObject, deleted: Int): JsonObject =
        JsonObject(row + ("deleted" to JsonPrimitive(deleted)))

    // -- Meta ------------------------------------------------------------------------------------

    suspend fun meta(key: String): String? = read { db ->
        db.rawQuery("SELECT value FROM coach_meta WHERE key = ?", arrayOf(key))
            .use { c -> if (c.moveToNext()) c.getString(0) else null }
    }

    suspend fun setMeta(key: String, value: String) {
        write { db ->
            val cv = ContentValues().apply {
                put("key", key)
                put("value", value)
            }
            db.insertWithOnConflict("coach_meta", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
        }
    }

    fun close() = helper.close()

    // -- Row mapping -----------------------------------------------------------------------------

    private fun countOf(db: SQLiteDatabase, sql: String, arg: String): Int =
        db.rawQuery(sql, arrayOf(arg)).use { c -> if (c.moveToNext()) c.getInt(0) else 0 }

    private fun unreferencedAttachmentIds(db: SQLiteDatabase): List<String> {
        val referenced = mutableSetOf<String>()
        db.rawQuery("SELECT attachment_ids_json FROM messages WHERE deleted = 0", null).use { c ->
            while (c.moveToNext()) referenced += decodeStrings(c.getString(0))
        }
        val orphans = mutableListOf<String>()
        db.rawQuery("SELECT id FROM attachments", null).use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0)
                if (id !in referenced) orphans += id
            }
        }
        return orphans
    }

    private fun values(conversation: Conversation) = ContentValues().apply {
        put("id", conversation.id)
        put("title", conversation.title)
        put("created_ms", conversation.createdMs)
        put("updated_ms", conversation.updatedMs)
        conversation.lastMessageMs?.let { put("last_message_ms", it) } ?: putNull("last_message_ms")
        put("pinned", if (conversation.pinned) 1 else 0)
        put("archived", if (conversation.archived) 1 else 0)
        put("deleted", 0)
        put("data_sources_json", encodeSwitches(conversation.dataSources))
        put("selected_record_ids_json", encodeStrings(conversation.selectedRecordIds))
        put("provider_override", conversation.providerOverride)
        put("records_online_decision", conversation.recordsOnlineDecision)
    }

    private fun values(message: CoachMessage) = ContentValues().apply {
        put("id", message.id)
        put("conversation_id", message.conversationId)
        put("seq", message.seq)
        put("variant_index", message.variantIndex)
        put("role", message.role.raw)
        put("content", message.content)
        put("created_ms", message.createdMs)
        put("updated_ms", message.updatedMs)
        put("deleted", 0)
        put("regenerated_from", message.regeneratedFrom)
        put("record_refs_json", encodeRefs(message.recordRefs))
        put("attachment_ids_json", encodeStrings(message.attachmentIds))
    }

    private fun values(attachment: ChatAttachment) = ContentValues().apply {
        put("id", attachment.id)
        put("kind", attachment.kind.raw)
        put("filename", attachment.filename)
        put("mime_type", attachment.mimeType)
        put("bytes", attachment.bytes)
        put("sha256", attachment.sha256)
        attachment.pageCount?.let { put("page_count", it) } ?: putNull("page_count")
        attachment.charCount?.let { put("char_count", it) } ?: putNull("char_count")
        put("excerpt", attachment.excerpt)
        put("file_path", attachment.filePath)
        put("thumbnail_path", attachment.thumbnailPath)
        put("created_ms", attachment.createdMs)
        put("deleted", 0)
    }

    private fun conversation(c: Cursor) = Conversation(
        id = c.getString(0),
        title = c.getString(1) ?: "",
        createdMs = c.getLong(2),
        updatedMs = c.getLong(3),
        lastMessageMs = if (c.isNull(4)) null else c.getLong(4),
        pinned = c.getInt(5) != 0,
        archived = c.getInt(6) != 0,
        dataSources = decodeSwitches(c.getString(7)),
        selectedRecordIds = decodeStrings(c.getString(8)),
        providerOverride = c.getString(9),
        recordsOnlineDecision = c.getString(10)
    )

    private fun message(c: Cursor) = CoachMessage(
        id = c.getString(0),
        conversationId = c.getString(1),
        seq = c.getInt(2),
        variantIndex = c.getInt(3),
        role = CoachMessage.Role.fromRaw(c.getString(4)),
        content = c.getString(5) ?: "",
        createdMs = c.getLong(6),
        updatedMs = c.getLong(7),
        regeneratedFrom = c.getString(8),
        recordRefs = decodeRefs(c.getString(9)),
        attachmentIds = decodeStrings(c.getString(10))
    )

    private fun attachment(c: Cursor) = ChatAttachment(
        id = c.getString(0),
        kind = AttachmentKind.fromRaw(c.getString(1)),
        filename = c.getString(2) ?: "",
        mimeType = c.getString(3),
        bytes = c.getLong(4),
        sha256 = c.getString(5),
        pageCount = if (c.isNull(6)) null else c.getInt(6),
        charCount = if (c.isNull(7)) null else c.getInt(7),
        excerpt = c.getString(8),
        filePath = c.getString(9),
        thumbnailPath = c.getString(10),
        createdMs = c.getLong(11)
    )

    // -- JSON columns ----------------------------------------------------------------------------

    private fun encodeStrings(values: List<String>): String? =
        if (values.isEmpty()) null else json.encodeToString(ListSerializer(String.serializer()), values)

    private fun decodeStrings(text: String?): List<String> =
        if (text.isNullOrEmpty()) emptyList()
        else runCatching { json.decodeFromString(ListSerializer(String.serializer()), text) }.getOrDefault(emptyList())

    private fun encodeRefs(refs: List<CoachRecordRef>): String? =
        if (refs.isEmpty()) null else json.encodeToString(ListSerializer(CoachRecordRef.serializer()), refs)

    private fun decodeRefs(text: String?): List<CoachRecordRef> =
        if (text.isNullOrEmpty()) emptyList()
        else runCatching { json.decodeFromString(ListSerializer(CoachRecordRef.serializer()), text) }
            .getOrDefault(emptyList())

    private fun encodeSwitches(switches: CoachDataSwitches): String? {
        val off = switches.offSources()
        if (off.isEmpty()) return null
        return json.encodeToString(
            kotlinx.serialization.builtins.MapSerializer(String.serializer(), Boolean.serializer()),
            off.associateWith { false }
        )
    }

    private fun decodeSwitches(text: String?): CoachDataSwitches {
        if (text.isNullOrEmpty()) return CoachDataSwitches.ALL_ON
        val map = runCatching {
            json.decodeFromString(
                kotlinx.serialization.builtins.MapSerializer(String.serializer(), Boolean.serializer()),
                text
            )
        }.getOrDefault(emptyMap())
        return CoachDataSwitches.of(map)
    }

    companion object {
        /** The rows as `CoachReference` expects them (docs/coach.md §10, §11). */
        fun referenceRow(conversation: Conversation): JsonObject = MedicationJson.obj(
            "id" to conversation.id, "title" to conversation.title,
            "created_ms" to conversation.createdMs, "updated_ms" to conversation.updatedMs,
            "last_message_ms" to conversation.lastMessageMs,
            "pinned" to if (conversation.pinned) 1 else 0,
            "archived" to if (conversation.archived) 1 else 0,
            "data_sources" to conversation.dataSources.asJson(),
            "selected_record_ids" to conversation.selectedRecordIds,
            "provider_override" to conversation.providerOverride
        )

        fun referenceRow(message: CoachMessage): JsonObject = MedicationJson.obj(
            "id" to message.id, "conversation_id" to message.conversationId, "seq" to message.seq,
            "variant_index" to message.variantIndex, "role" to message.role.raw,
            "content" to message.content, "created_ms" to message.createdMs,
            "updated_ms" to message.updatedMs, "regenerated_from" to message.regeneratedFrom,
            "record_refs" to message.recordRefs.map { ref ->
                MedicationJson.obj("record_id" to ref.recordId, "title" to ref.title, "date" to ref.date)
            },
            "attachment_ids" to message.attachmentIds
        )

        fun referenceRow(attachment: ChatAttachment): JsonObject = MedicationJson.obj(
            "id" to attachment.id, "kind" to attachment.kind.raw, "filename" to attachment.filename,
            "mime_type" to attachment.mimeType, "bytes" to attachment.bytes,
            "sha256" to attachment.sha256, "page_count" to attachment.pageCount,
            "char_count" to attachment.charCount, "excerpt" to attachment.excerpt,
            "created_ms" to attachment.createdMs
        )

        /** Only the newest variant of each reply is shown; the stepper reaches the rest. */
        fun latestVariants(rows: List<CoachMessage>): List<CoachMessage> {
            val best = linkedMapOf<Int, CoachMessage>()
            for (row in rows) {
                val existing = best[row.seq]
                if (existing == null || existing.variantIndex < row.variantIndex) best[row.seq] = row
            }
            return best.values.toList()
        }

        /** Handy for callers that need the source list without importing the model. */
        val SOURCES: List<CoachSource> = CoachSource.entries.toList()
    }
}
