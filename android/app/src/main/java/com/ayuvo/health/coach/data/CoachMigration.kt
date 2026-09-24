package com.ayuvo.health.coach.data

import android.graphics.BitmapFactory
import android.util.Base64
import com.ayuvo.health.coach.logic.CoachReference
import com.ayuvo.health.coach.model.AttachmentKind
import com.ayuvo.health.coach.model.ChatAttachment
import com.ayuvo.health.coach.model.CoachMessage
import com.ayuvo.health.coach.model.Conversation
import com.ayuvo.health.data.PreferencesStore
import com.ayuvo.health.medications.logic.MedicationJson
import com.ayuvo.health.medications.logic.MedicationJson.str
import com.ayuvo.health.records.coach.CoachRecordRef
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.util.UUID

/**
 * One-time move of the legacy `coachChatHistory` preference into `ayuvo_coach.db`
 * (docs/coach.md §12).
 *
 * Runs once, guarded by a `coach_meta` row, on the first launch after the update. A corrupt blob
 * yields an empty store rather than a crash: the user loses a history they could not read anyway,
 * and the app still starts.
 */
object CoachMigration {
    const val META_KEY = "legacy_history_migrated"

    data class Result(
        val conversationId: String? = null,
        val messageCount: Int = 0,
        val attachmentCount: Int = 0,
        /** The key held bytes that did not decode; the store is left empty and the key cleared. */
        val wasCorrupt: Boolean = false
    ) {
        companion object {
            val NOTHING_TO_DO = Result()
        }
    }

    /** Imports the legacy blob if it is still there. Safe to call on every launch. */
    suspend fun runIfNeeded(
        repository: CoachRepository,
        prefs: PreferencesStore,
        nowMs: Long = System.currentTimeMillis()
    ): Result {
        if (repository.meta(META_KEY) == "1") return Result.NOTHING_TO_DO
        val raw = prefs.legacyChatHistoryJson()
        prefs.clearLegacyChatHistory()
        repository.setMeta(META_KEY, "1")
        if (raw.isNullOrEmpty()) return Result.NOTHING_TO_DO

        val rows = runCatching { MedicationJson.json.parseToJsonElement(raw) as? JsonArray }.getOrNull()
            ?: return Result(wasCorrupt = true)
        val legacy = rows.filterIsInstance<JsonObject>()
        if (legacy.isEmpty()) return Result.NOTHING_TO_DO

        val firstUserText = legacy.firstOrNull { it.str("role") == "user" }?.str("content").orEmpty()
        val title = CoachReference.conversationTitle(firstUserText).str("title").orEmpty()
        val createdMs = timestampOf(legacy.firstOrNull()) ?: nowMs
        val conversation = Conversation(title = title, createdMs = createdMs, updatedMs = nowMs)
        runCatching { repository.createConversation(conversation) }.getOrNull() ?: return Result()

        var attachments = 0
        var stored = 0
        legacy.forEachIndexed { index, row ->
            val at = timestampOf(row) ?: (createdMs + index)
            val attachmentIds = mutableListOf<String>()
            val base64 = row.str("attachmentImageBase64")
            if (!base64.isNullOrEmpty()) {
                storeLegacyImage(base64, repository, at)?.let {
                    attachmentIds += it
                    attachments++
                }
            }
            val message = CoachMessage(
                conversationId = conversation.id,
                seq = index + 1,
                role = CoachMessage.Role.fromRaw(row.str("role")),
                content = row.str("content").orEmpty(),
                createdMs = at,
                recordRefs = decodeRefs(row["record_refs"]),
                attachmentIds = attachmentIds
            )
            if (runCatching { repository.appendMessage(message) }.isSuccess) stored++
        }
        return Result(conversation.id, stored, attachments, wasCorrupt = false)
    }

    /**
     * The old history inlined a base64 JPEG thumbnail per message; it becomes a real attachment so
     * the new bubble can load it the same way as everything else.
     */
    private suspend fun storeLegacyImage(base64: String, repository: CoachRepository, createdMs: Long): String? {
        val data = runCatching { Base64.decode(base64, Base64.DEFAULT) }.getOrNull() ?: return null
        if (data.isEmpty()) return null
        val id = UUID.randomUUID().toString().lowercase()
        val path = repository.files.writeOriginal(data, id, "jpg") ?: return null
        val thumbnail = runCatching { BitmapFactory.decodeByteArray(data, 0, data.size) }.getOrNull()
            ?.let { repository.files.writeThumbnail(it, id) }
        val attachment = ChatAttachment(
            id = id,
            kind = AttachmentKind.IMAGE,
            filename = "photo.jpg",
            mimeType = "image/jpeg",
            bytes = data.size.toLong(),
            sha256 = CoachFileStore.sha256(data),
            filePath = path,
            thumbnailPath = thumbnail,
            createdMs = createdMs
        )
        return if (runCatching { repository.insertAttachment(attachment) }.isSuccess) {
            id
        } else {
            repository.files.delete(id)
            null
        }
    }

    /** The old rows stored an ISO-8601 instant; anything else is treated as missing. */
    private fun timestampOf(row: JsonObject?): Long? {
        val value = row?.get("timestamp") as? JsonPrimitive ?: return null
        value.longOrNull?.let { return it }
        val text = value.content
        return runCatching { java.time.Instant.parse(text).toEpochMilli() }.getOrNull()
    }

    private fun decodeRefs(element: kotlinx.serialization.json.JsonElement?): List<CoachRecordRef> {
        val array = element as? JsonArray ?: return emptyList()
        return runCatching {
            MedicationJson.json.decodeFromJsonElement(ListSerializer(CoachRecordRef.serializer()), array)
        }.getOrDefault(emptyList())
    }
}
