package com.ayuvo.health.coach

import com.ayuvo.health.coach.export.CoachChatArchiveFormat
import com.ayuvo.health.coach.export.CoachChatArchiveReader
import com.ayuvo.health.coach.export.CoachChatArchiveWriter
import com.ayuvo.health.coach.logic.CoachReference
import com.ayuvo.health.medications.logic.MedicationJson
import com.ayuvo.health.medications.logic.MedicationJson.arr
import com.ayuvo.health.medications.logic.MedicationJson.int
import com.ayuvo.health.medications.logic.MedicationJson.str
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipFile

/**
 * The `ayuvo-coach-chats` container (docs/coach.md §11). The row rules are pinned by the shared
 * `chat_archive.json` vectors; this covers the zip around them: entry order, checksums, the blobs,
 * and that reading one back gives the archive it was written from.
 */
class CoachChatArchiveTest {

    private val snapshot: JsonObject = MedicationJson.obj(
        "conversations" to listOf(
            MedicationJson.obj(
                "id" to "c1", "title" to "Sleep", "created_ms" to 1_000L, "updated_ms" to 2_000L,
                "last_message_ms" to 2_000L, "pinned" to 0, "archived" to 0,
                "data_sources" to MedicationJson.obj(), "selected_record_ids" to emptyList<String>(),
                "provider_override" to null, "deleted" to 0
            ),
            // A tombstone: local only, never exported.
            MedicationJson.obj("id" to "c2", "title" to "Gone", "created_ms" to 1L, "deleted" to 1)
        ),
        "messages" to listOf(
            MedicationJson.obj(
                "id" to "m1", "conversation_id" to "c1", "seq" to 1, "variant_index" to 0,
                "role" to "user", "content" to "How did I sleep?", "created_ms" to 1_500L,
                "updated_ms" to 1_500L, "regenerated_from" to null,
                "record_refs" to emptyList<String>(), "attachment_ids" to listOf("a1"), "deleted" to 0
            )
        ),
        "attachments" to listOf(
            MedicationJson.obj(
                "id" to "a1", "kind" to "pdf", "filename" to "sleep-study.pdf",
                "mime_type" to "application/pdf", "bytes" to 5L, "sha256" to null,
                "page_count" to 1, "char_count" to 10, "excerpt" to "redacted",
                "created_ms" to 1_400L, "deleted" to 0
            ),
            // Referenced by nothing that was exported.
            MedicationJson.obj("id" to "a2", "kind" to "image", "filename" to "x.jpg", "created_ms" to 1L, "deleted" to 0)
        )
    )

    private fun manifest(): JsonObject = MedicationJson.obj(
        "format" to CoachChatArchiveFormat.FORMAT,
        "format_version" to CoachChatArchiveFormat.FORMAT_VERSION,
        "app" to CoachChatArchiveFormat.APP,
        "app_version" to "1.0",
        "platform" to CoachChatArchiveFormat.PLATFORM,
        "exported_at" to 9_000L,
        "zone_id" to "Asia/Kolkata",
        "counts" to MedicationJson.obj("conversations" to 1, "messages" to 1, "attachments" to 1)
    )

    private fun write(target: File): JsonObject {
        val archive = CoachReference.chatArchive(snapshot)
        CoachChatArchiveWriter.write(
            target = target,
            archive = archive,
            manifest = manifest(),
            blobs = listOf(CoachChatArchiveFormat.fileEntry("a1", "sleep-study.pdf") to "%PDF-".toByteArray())
        )
        return archive
    }

    private fun temp(): File =
        File.createTempFile("coach-chats", ".zip").apply { delete() }

    @Test
    fun theContainerHasItsEntriesInReadingOrder() {
        val target = temp()
        try {
            write(target)
            val names = ZipFile(target).use { zip -> zip.entries().toList().map { it.name } }
            assertEquals(
                listOf(
                    CoachChatArchiveFormat.MANIFEST,
                    CoachChatArchiveFormat.CONVERSATIONS,
                    CoachChatArchiveFormat.MESSAGES,
                    CoachChatArchiveFormat.ATTACHMENTS,
                    "attachments/a1/sleep-study.pdf",
                    CoachChatArchiveFormat.CHECKSUMS
                ),
                names
            )
        } finally {
            target.delete()
        }
    }

    /** checksums.json covers every entry except itself. */
    @Test
    fun everyEntryIsChecksummed() {
        val target = temp()
        try {
            write(target)
            val text = ZipFile(target).use { zip ->
                zip.getInputStream(zip.getEntry(CoachChatArchiveFormat.CHECKSUMS)).readBytes()
            }.toString(Charsets.UTF_8)
            val sums = MedicationJson.json.parseToJsonElement(text) as JsonObject
            assertEquals(5, sums.size)
            assertNull(sums[CoachChatArchiveFormat.CHECKSUMS])
            for ((_, value) in sums) {
                assertTrue(value.toString(), Regex("^\"[0-9a-f]{64}\"$").matches(value.toString()))
            }
        } finally {
            target.delete()
        }
    }

    /** Reading a written archive gives back exactly the rows that went in. */
    @Test
    fun readingBackGivesTheSameArchive() {
        val target = temp()
        try {
            val written = write(target)
            val entries = CoachChatArchiveReader.readEntries(target)
            assertEquals(CoachChatArchiveFormat.FORMAT, entries.archive.str("format"))
            assertEquals(written.arr("conversations")?.size, entries.archive.arr("conversations")?.size)
            assertEquals(written.arr("messages")?.size, entries.archive.arr("messages")?.size)
            assertEquals(written.arr("attachments")?.size, entries.archive.arr("attachments")?.size)
            // Compare canonically: what came back was parsed from the sorted-key entries.
            assertEquals(
                CoachChatArchiveFormat.compact(written.arr("messages")!!),
                CoachChatArchiveFormat.compact(entries.archive.arr("messages")!!)
            )
            assertEquals("sleep-study.pdf", entries.blobs["a1"]?.first)
            assertEquals("%PDF-", entries.blobs["a1"]?.second?.toString(Charsets.UTF_8))
        } finally {
            target.delete()
        }
    }

    /** The tombstone and the unreferenced attachment never leave the device. */
    @Test
    fun tombstonesAndOrphansAreNotExported() {
        val archive = CoachReference.chatArchive(snapshot)
        val ids = archive.arr("conversations").orEmpty().filterIsInstance<JsonObject>().mapNotNull { it.str("id") }
        assertEquals(listOf("c1"), ids)
        val attachments = archive.arr("attachments").orEmpty().filterIsInstance<JsonObject>().mapNotNull { it.str("id") }
        assertEquals(listOf("a1"), attachments)
    }

    /** A second export of the same store is byte-identical (the manifest's clock aside). */
    @Test
    fun reExportIsByteIdentical() {
        val first = temp()
        val second = temp()
        try {
            write(first)
            write(second)
            assertTrue(first.readBytes().contentEquals(second.readBytes()))
        } finally {
            first.delete()
            second.delete()
        }
    }

    /**
     * The shared fixture (`scripts/coach_contract_check.py --write`) is an archive neither platform
     * wrote: reading it here is the proof that a chat exported on an iPhone opens on Android.
     */
    @Test
    fun theSharedFixtureReadsBack() {
        val fixture = CoachTestFiles.shared("coach/fixtures/ayuvo-coach-chats-fixture.zip")
        assertTrue("shared fixture missing — run scripts/coach_contract_check.py --write",
                   fixture != null && fixture.exists())
        val entries = CoachChatArchiveReader.readEntries(fixture!!)
        assertEquals(CoachChatArchiveFormat.FORMAT, entries.archive.str("format"))
        assertEquals(1, entries.archive.int("format_version"))
        assertEquals(2, entries.archive.arr("conversations")?.size)
        assertEquals(3, entries.archive.arr("messages")?.size)
        assertEquals(1, entries.archive.arr("attachments")?.size)
        assertEquals("sleep-study.pdf", entries.blobs["att-0001"]?.first)
        assertEquals("%PDF-", entries.blobs["att-0001"]?.second?.toString(Charsets.UTF_8))

        // The tombstone and the unreferenced attachment were never written.
        val ids = entries.archive.arr("conversations").orEmpty().filterIsInstance<JsonObject>()
            .mapNotNull { it.str("id") }
        assertEquals(listOf("conv-sleep-0001", "conv-diet-0002"), ids)

        val empty = MedicationJson.obj(
            "conversations" to emptyList<String>(),
            "messages" to emptyList<String>(),
            "attachments" to emptyList<String>()
        )
        val merged = CoachReference.mergeChatArchive(empty, entries.archive)
        val counts = merged["counts"] as JsonObject
        assertEquals(2, counts.int("conversations_inserted"))
        assertEquals(3, counts.int("messages_inserted"))
        assertEquals(1, counts.int("attachments_inserted"))
    }

    @Test
    fun anAttachmentNameCanNeverEscapeItsFolder() {
        assertEquals("attachments/a1/passwd", CoachChatArchiveFormat.fileEntry("a1", "../../etc/passwd"))
        assertEquals("attachments/a1/attachment", CoachChatArchiveFormat.fileEntry("a1", "   "))
        assertEquals("attachments/a1/report.pdf", CoachChatArchiveFormat.fileEntry("a1", "C:\\docs\\report.pdf"))
    }
}
