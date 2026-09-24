package com.ayuvo.health.coach

import com.ayuvo.health.coach.data.CoachSchema
import com.ayuvo.health.coach.model.CoachDataSwitches
import com.ayuvo.health.coach.model.CoachMessage
import com.ayuvo.health.coach.model.CoachSource
import com.ayuvo.health.coach.data.CoachRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `CoachSchema` must stay byte-identical to `shared/coach/schema.sql` (docs/coach.md §2); the SQL
 * itself is exercised on the emulator, not here (unit tests have no SQLite).
 */
class CoachSchemaContractTest {

    /** docs/health-records.md §8 statement splitting, the same rule the other schemas use. */
    private fun statements(sql: String): List<String> {
        val text = sql.replace("\r\n", "\n").replace("\r", "\n")
        val out = mutableListOf<String>()
        val current = mutableListOf<String>()
        for (rawLine in text.split("\n")) {
            var line = rawLine
            val comment = line.indexOf("--")
            if (comment >= 0) line = line.substring(0, comment)
            line = line.trimEnd(' ', '\t')
            if (line.isEmpty()) continue
            if (line.endsWith(";")) {
                current += line.dropLast(1)
                out += current.joinToString("\n")
                current.clear()
            } else {
                current += line
            }
        }
        assertTrue("text after the last ';'", current.isEmpty())
        return out
    }

    @Test
    fun embeddedSchemaMatchesTheSharedFile() {
        val shared = statements(CoachTestFiles.shared("coach/schema.sql")!!.readText())
        assertTrue(shared.isNotEmpty())
        assertEquals(shared, CoachSchema.STATEMENTS)
    }

    @Test
    fun tableAndIndexNamesMatchTheSchema() {
        val tables = CoachSchema.STATEMENTS
            .filter { it.startsWith("CREATE TABLE") || it.startsWith("CREATE VIRTUAL TABLE") }
            .map { if (it.startsWith("CREATE VIRTUAL")) it.split(" ")[3] else it.split(" ")[2] }
        assertEquals(tables, CoachSchema.TABLES)
        val indexes = CoachSchema.STATEMENTS
            .filter { it.startsWith("CREATE INDEX") }
            .map { it.split(" ")[2] }
        assertEquals(indexes, CoachSchema.INDEXES)
    }

    /** The FTS docid must be a rowid alias so VACUUM can never renumber it out from under the index. */
    @Test
    fun messagesDeclareAStableDocumentId() {
        val messages = CoachSchema.STATEMENTS.first { it.startsWith("CREATE TABLE messages") }
        assertTrue(messages.contains("doc_id INTEGER PRIMARY KEY"))
        assertTrue(messages.contains("id TEXT NOT NULL UNIQUE"))
    }

    /** No table may hold a health value; only the visible text and references persist (§1 rule 2). */
    @Test
    fun noColumnStoresAToolPayload() {
        val joined = CoachSchema.STATEMENTS.joinToString("\n")
        for (forbidden in listOf("tool_payload", "tool_result", "value REAL", "samples")) {
            assertFalse("schema gained a $forbidden column", joined.contains(forbidden))
        }
    }
}

/** Behaviour of the model types that the repository and the UI both depend on. */
class CoachModelTest {

    @Test
    fun anAbsentSwitchMeansOn() {
        val switches = CoachDataSwitches.ALL_ON
        for (source in CoachSource.entries) assertTrue(switches.isOn(source))
        assertTrue(switches.offSources().isEmpty())
    }

    @Test
    fun turningASourceOffIsRecordedAndReversible() {
        var switches = CoachDataSwitches.ALL_ON.with(CoachSource.RECORDS, false)
        assertFalse(switches.isOn(CoachSource.RECORDS))
        assertTrue(switches.isOn(CoachSource.HEALTH))
        assertEquals(listOf("records"), switches.offSources())
        switches = switches.with(CoachSource.RECORDS, true)
        assertTrue(switches.isOn(CoachSource.RECORDS))
        assertTrue(switches.offSources().isEmpty())
    }

    @Test
    fun unknownSourcesAreDropped() {
        val switches = CoachDataSwitches.of(mapOf("records" to false, "astrology" to false))
        assertEquals(listOf("records"), switches.offSources())
    }

    /** Only the newest version of a regenerated reply shows in the transcript (§10). */
    @Test
    fun latestVariantsKeepsTheHighestVariantPerSeq() {
        val rows = listOf(
            CoachMessage(conversationId = "c", seq = 1, role = CoachMessage.Role.USER,
                         content = "hi", createdMs = 1),
            CoachMessage(conversationId = "c", seq = 2, variantIndex = 0,
                         role = CoachMessage.Role.ASSISTANT, content = "first", createdMs = 2),
            CoachMessage(conversationId = "c", seq = 2, variantIndex = 1,
                         role = CoachMessage.Role.ASSISTANT, content = "second", createdMs = 3)
        )
        assertEquals(listOf("hi", "second"), CoachRepository.latestVariants(rows).map { it.content })
    }

    @Test
    fun aMessageDefaultsToItsOwnIdAndTimestamps() {
        val message = CoachMessage(conversationId = "c", seq = 1, role = CoachMessage.Role.USER,
                                   content = "x", createdMs = 42)
        assertNotNull(message.id)
        assertEquals(42L, message.updatedMs)
        assertTrue(message.attachmentIds.isEmpty())
        assertTrue(message.recordRefs.isEmpty())
    }
}
