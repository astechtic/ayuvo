package com.ayuvo.health.coach.model

import com.ayuvo.health.records.coach.CoachRecordRef
import java.util.UUID

/** The four data sources Coach can read (docs/coach.md §3). */
enum class CoachSource(val raw: String) {
    FOOD("food"),
    HEALTH("health"),
    MEDICATIONS("medications"),
    RECORDS("records");

    companion object {
        fun fromRaw(raw: String?): CoachSource? = entries.firstOrNull { it.raw == raw }
    }
}

/**
 * The per-conversation data switches (docs/coach.md §8). An absent key means **on**; the switch can
 * only ever narrow what the source's own consent already permits, never grant it.
 */
data class CoachDataSwitches(private val values: Map<String, Boolean> = emptyMap()) {
    fun isOn(source: CoachSource): Boolean = values[source.raw] != false

    fun with(source: CoachSource, on: Boolean): CoachDataSwitches =
        CoachDataSwitches(if (on) values - source.raw else values + (source.raw to false))

    /** Only the off switches, so a source added later defaults to on. */
    fun offSources(): List<String> = values.filterValues { !it }.keys.sorted()

    /** The shape `CoachReference.resolveDataSources` expects. */
    fun asJson(): kotlinx.serialization.json.JsonObject =
        kotlinx.serialization.json.JsonObject(
            values.mapValues { kotlinx.serialization.json.JsonPrimitive(it.value) }
        )

    companion object {
        val ALL_ON = CoachDataSwitches()

        fun of(values: Map<String, Boolean>): CoachDataSwitches =
            CoachDataSwitches(values.filterKeys { CoachSource.fromRaw(it) != null })
    }
}

/** One Coach conversation (docs/coach.md §7). */
data class Conversation(
    val id: String = UUID.randomUUID().toString().lowercase(),
    val title: String = "",
    val createdMs: Long,
    val updatedMs: Long = createdMs,
    val lastMessageMs: Long? = null,
    val pinned: Boolean = false,
    val archived: Boolean = false,
    val dataSources: CoachDataSwitches = CoachDataSwitches.ALL_ON,
    val selectedRecordIds: List<String> = emptyList(),
    val providerOverride: String? = null,
    val recordsOnlineDecision: String? = null
)

/** What a file the user attached looks like (docs/coach.md §6). */
enum class AttachmentKind(val raw: String) {
    IMAGE("image"),
    PDF("pdf"),
    TEXT("text"),
    NOTE("note");

    companion object {
        fun fromRaw(raw: String?): AttachmentKind = entries.firstOrNull { it.raw == raw } ?: TEXT
    }
}

/**
 * A file the user attached to a turn. The blob lives in `CoachFileStore`; only the excerpt — already
 * redacted — is stored here, and it is exactly what was sent to the provider.
 */
data class ChatAttachment(
    val id: String = UUID.randomUUID().toString().lowercase(),
    val kind: AttachmentKind,
    val filename: String,
    val mimeType: String? = null,
    val bytes: Long = 0,
    val sha256: String? = null,
    val pageCount: Int? = null,
    val charCount: Int? = null,
    /** The redacted text sent to the provider; null for an image. */
    val excerpt: String? = null,
    /** Relative to the files root, null when the blob is gone (an import without files). */
    val filePath: String? = null,
    val thumbnailPath: String? = null,
    val createdMs: Long
)

/** One turn of a conversation. Tool payloads are never stored (docs/health-records.md §26). */
data class CoachMessage(
    val id: String = UUID.randomUUID().toString().lowercase(),
    val conversationId: String,
    /** Position in the conversation, 1-based. A regenerated reply keeps the seq it replaces. */
    val seq: Int,
    /** Which version of that reply this is; the transcript shows the highest. */
    val variantIndex: Int = 0,
    val role: Role,
    val content: String,
    val createdMs: Long,
    val updatedMs: Long = createdMs,
    /** The first variant's id when this reply came from "Regenerate". */
    val regeneratedFrom: String? = null,
    val recordRefs: List<CoachRecordRef> = emptyList(),
    val attachmentIds: List<String> = emptyList()
) {
    enum class Role(val raw: String) {
        USER("user"),
        ASSISTANT("assistant");

        companion object {
            fun fromRaw(raw: String?): Role = if (raw == "assistant") ASSISTANT else USER
        }
    }
}

/** One row of the conversation list. */
data class ConversationSummary(
    val conversation: Conversation,
    val snippet: String,
    val messageCount: Int,
    val attachmentCount: Int
)
