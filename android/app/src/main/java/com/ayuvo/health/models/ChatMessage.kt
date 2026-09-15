package com.ayuvo.health.models

import com.ayuvo.health.records.coach.CoachRecordRef
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.Instant
import java.util.UUID

@Serializable
data class ChatMessage(
    @Serializable(with = UuidSerializer::class)
    val id: UUID = UUID.randomUUID(),
    val role: Role,
    val content: String,
    val attachmentImageBase64: String? = null,
    @Serializable(with = InstantSerializer::class)
    val timestamp: Instant = Instant.now(),
    /** Health records the reply relied on (docs/health-records.md §26); tool payloads are never stored. */
    @SerialName("record_refs")
    val recordRefs: List<CoachRecordRef> = emptyList()
) {
    @Serializable
    enum class Role {
        @SerialName("user") USER,
        @SerialName("assistant") ASSISTANT
    }
}
