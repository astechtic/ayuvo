package com.ayuvo.health.coach

import com.ayuvo.health.coach.data.CoachRepository
import com.ayuvo.health.coach.logic.CoachReference
import com.ayuvo.health.coach.model.AttachmentKind
import com.ayuvo.health.coach.model.ChatAttachment
import com.ayuvo.health.coach.model.CoachExportFormat
import com.ayuvo.health.coach.model.CoachMessage
import com.ayuvo.health.coach.model.Conversation
import com.ayuvo.health.medications.logic.MedicationJson
import com.ayuvo.health.medications.logic.MedicationJson.int
import com.ayuvo.health.medications.logic.MedicationJson.str
import com.ayuvo.health.medications.logic.MedicationJson.truthy
import com.ayuvo.health.records.coach.CoachRecordRef
import com.ayuvo.health.ui.coach.CoachViewModel
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Message actions and export (docs/coach.md §10). The formats themselves are pinned by the shared
 * `export.json` vectors; this covers the Android side on top — that the model rows reach the
 * reference in the shape it expects, and that regenerate keeps the old answer reachable.
 *
 * The twin of `ios/calorietrackerTests/CoachExportTests.swift`. The parts that need SQLite live
 * there; these unit tests run without a device, so they work on the rows rather than the database.
 */
class CoachExportTest {

    private val conversation = Conversation(
        id = "c1",
        title = "How did I sleep this week?",
        createdMs = 1_700_000_000_000,
        lastMessageMs = 1_700_000_200_000
    )

    private val attachment = ChatAttachment(
        id = "a1", kind = AttachmentKind.PDF, filename = "sleep-study.pdf",
        mimeType = "application/pdf", bytes = 2048, pageCount = 2, charCount = 120,
        excerpt = "Sleep efficiency 88 percent", createdMs = 1_700_000_050_000
    )

    private val prompt = CoachMessage(
        id = "m1", conversationId = "c1", seq = 1, role = CoachMessage.Role.USER,
        content = "How did I sleep this week?", createdMs = 1_700_000_100_000,
        attachmentIds = listOf("a1")
    )

    private val reply = CoachMessage(
        id = "m2", conversationId = "c1", seq = 2, role = CoachMessage.Role.ASSISTANT,
        content = "First answer.", createdMs = 1_700_000_200_000,
        recordRefs = listOf(CoachRecordRef("r1", "Sleep study", "2026-08-02"))
    )

    private fun rows(vararg messages: CoachMessage) = messages.map { CoachRepository.referenceRow(it) }

    // -- Regenerate --------------------------------------------------------------------------------

    @Test
    fun regeneratingKeepsTheOldAnswerAsAnEarlierVersion() {
        val plan = CoachReference.regeneratePlan(rows(prompt, reply), 2)
        assertTrue(plan.truthy("ok"))
        assertEquals("m1", plan.str("prompt_id"))
        assertEquals(1, plan.int("variant_index"))
        assertEquals("m2", plan.str("regenerated_from"))
        // The prompt's attachments come along, so the model sees the same document.
        assertEquals(listOf("a1"), MedicationJson.strings(plan["attachment_ids"]))
    }

    /** A chain of regenerations stays a flat set, not a linked list. */
    @Test
    fun everyVariantPointsAtTheFirstOne() {
        val second = reply.copy(id = "m3", variantIndex = 1, content = "Second.", regeneratedFrom = "m2")
        val plan = CoachReference.regeneratePlan(rows(prompt, reply, second), 2)
        assertEquals(2, plan.int("variant_index"))
        assertEquals("a third version must still point at the first", "m2", plan.str("regenerated_from"))
    }

    @Test
    fun regeneratingSomethingThatIsNotAReplyIsRefused() {
        val plan = CoachReference.regeneratePlan(rows(prompt, reply), 1)
        assertFalse(plan.truthy("ok"))
        assertEquals("not_a_reply", plan.str("reason"))
    }

    /** The transcript shows the newest version; the stepper reaches the rest. */
    @Test
    fun onlyTheNewestVariantIsShown() {
        val second = reply.copy(id = "m3", variantIndex = 1, content = "Second answer.", regeneratedFrom = "m2")
        val shown = CoachRepository.latestVariants(listOf(prompt, reply, second))
        assertEquals(listOf("How did I sleep this week?", "Second answer."), shown.map { it.content })
    }

    // -- Export ------------------------------------------------------------------------------------

    @Test
    fun theMarkdownExportIsAReadableTranscript() {
        val result = CoachReference.conversationMarkdown(
            CoachRepository.referenceRow(conversation),
            rows(prompt, reply),
            listOf(CoachRepository.referenceRow(attachment)),
            "2026-09-24",
            "Gemini"
        )
        val text = result.str("text")!!
        assertTrue(text.startsWith("# How did I sleep this week?"))
        assertTrue(text.contains("2026-09-24 · 2 messages · Gemini"))
        assertTrue(text.contains("**You:** How did I sleep this week?"))
        assertTrue(text.contains("**Coach:** First answer."))
        assertTrue(text.contains("Attached: sleep-study.pdf"))
        assertTrue(text.contains("Used records: Sleep study — 2026-08-02"))
        // The excerpt was a redacted copy of a file the user still has; it is never inlined.
        assertFalse(text.contains("Sleep efficiency"))
        assertEquals("how-did-i-sleep-this-week-2026-09-24.md", result.str("filename"))
    }

    @Test
    fun theJsonExportIsOneConversationInTheArchiveShape() {
        val result = CoachReference.conversationJson(
            CoachRepository.referenceRow(conversation),
            rows(prompt, reply),
            listOf(CoachRepository.referenceRow(attachment)),
            "2026-09-24"
        )
        val archive = result["archive"] as JsonObject
        assertEquals(CoachReference.ARCHIVE_FORMAT, archive.str("format"))
        assertEquals(CoachReference.ARCHIVE_VERSION, archive.int("format_version"))
        assertEquals(1, (archive["conversations"] as JsonArray).size)
        assertEquals(2, (archive["messages"] as JsonArray).size)
        assertEquals("how-did-i-sleep-this-week-2026-09-24.json", result.str("filename"))

        // It must merge back through the same reader as a full backup.
        val empty = MedicationJson.obj(
            "conversations" to emptyList<String>(),
            "messages" to emptyList<String>(),
            "attachments" to emptyList<String>()
        )
        val merged = CoachReference.mergeChatArchive(empty, archive)
        val error = merged["error"]
        assertTrue("the merge refused it: $error", error == null || error is JsonNull)
        val counts = merged["counts"] as JsonObject
        assertEquals(1, counts.int("conversations_inserted"))
        assertEquals(2, counts.int("messages_inserted"))
    }

    @Test
    fun aTitleWithNoUsableCharactersStillGivesAFilename() {
        assertEquals("chat", CoachReference.exportSlug("   "))
        assertEquals("chat", CoachReference.exportSlug("😀😀"))
        assertEquals(
            "compare-my-last-two-blood-reports",
            CoachReference.exportSlug("Compare my last two blood reports!")
        )
        assertTrue(
            CoachReference.cpLen(CoachReference.exportSlug("word ".repeat(40))) <= CoachReference.MAX_SLUG_CHARS
        )
    }

    /** The exported file carries the mime type the share sheet needs. */
    @Test
    fun anExportedFileKnowsWhatItIs() {
        assertEquals("text/markdown", CoachExportFormat.MARKDOWN.mimeType)
        assertEquals("application/json", CoachExportFormat.JSON.mimeType)
    }

    /** The day stamp in the filename is the device's own day, not UTC. */
    @Test
    fun theDayStampIsLocal() {
        val day = CoachViewModel.localDay(1_700_000_200_000)
        assertTrue(day, Regex("^\\d{4}-\\d{2}-\\d{2}$").matches(day))
    }
}
