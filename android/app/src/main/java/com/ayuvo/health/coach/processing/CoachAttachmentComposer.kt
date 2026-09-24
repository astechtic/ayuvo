package com.ayuvo.health.coach.processing

import com.ayuvo.health.coach.logic.CoachReference
import com.ayuvo.health.coach.model.AttachmentKind
import com.ayuvo.health.coach.model.ChatAttachment
import com.ayuvo.health.medications.logic.MedicationJson
import com.ayuvo.health.medications.logic.MedicationJson.arr
import com.ayuvo.health.medications.logic.MedicationJson.str
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Builds the message text that actually goes to the provider when the turn has documents
 * (docs/coach.md §6).
 *
 * The excerpts are appended verbatim — already redacted by `attachment_excerpt`, and exactly what the
 * excerpt sheet showed the user (rule 4). The whole turn is capped by `turnExcerpts`, so three long
 * PDFs cannot blow the context window; a document that no longer fits is named but empty, and the
 * model is told so rather than being handed a silent truncation.
 */
object CoachAttachmentComposer {

    fun messageWithAttachments(text: String, attachments: List<ChatAttachment>): String {
        val documents = attachments.filter { it.kind != AttachmentKind.IMAGE }
        if (documents.isEmpty()) return text

        val budgeted = CoachReference.turnExcerpts(
            documents.map {
                MedicationJson.obj("id" to it.id, "filename" to it.filename, "text" to it.excerpt)
            }
        )
        val allowance = budgeted.arr("attachments").orEmpty()
            .filterIsInstance<JsonObject>()
            .mapNotNull { entry ->
                val id = entry.str("id") ?: return@mapNotNull null
                val chars = (entry["chars"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 0
                id to chars
            }.toMap()

        return buildString {
            append(text)
            for (attachment in documents) {
                val limit = allowance[attachment.id] ?: 0
                val excerpt = CoachReference.cpCut(attachment.excerpt, limit)
                val label = if (attachment.kind == AttachmentKind.NOTE) "Note" else "Attached file"
                append("\n\n--- ").append(label).append(": ").append(attachment.filename).append(" ---\n")
                if (excerpt.isEmpty()) {
                    append("(no readable text)")
                } else {
                    append(excerpt)
                    if (CoachReference.cpLen(attachment.excerpt) > limit) append("\n(truncated)")
                }
            }
        }
    }
}
