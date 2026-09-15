package com.ayuvo.health.records.data

import com.ayuvo.health.data.health.HealthSchemaContractTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * RecordsSchema.STATEMENTS must equal `shared/records/schema.sql` statement for statement
 * (comments and whitespace ignored). Unlike the health contract, the shared file is required.
 */
class RecordsSchemaContractTest {

    @Test
    fun embeddedDdlMatchesSharedSchema() {
        val file = listOf("../../shared/records/schema.sql", "../shared/records/schema.sql", "shared/records/schema.sql")
            .map(::File).firstOrNull { it.exists() }
        assertTrue("shared/records/schema.sql not found from ${File(".").absolutePath}", file != null)
        val shared = HealthSchemaContractTest.normalise(file!!.readText())
        val embedded = HealthSchemaContractTest.normalise(RecordsSchema.SQL)
        val diff = buildString {
            for (i in 0 until maxOf(shared.size, embedded.size)) {
                val s = shared.getOrNull(i)
                val e = embedded.getOrNull(i)
                if (s != e) append("statement $i\n  shared:   $s\n  embedded: $e\n")
            }
        }
        assertTrue("schema.sql differs from RecordsSchema.STATEMENTS:\n$diff", diff.isEmpty())
        assertEquals(RecordsSchema.STATEMENTS.size, embedded.size)
    }

    @Test
    fun embeddedDdlDeclaresEveryTableAndIndex() {
        val ddl = RecordsSchema.SQL
        for (table in RecordsSchema.TABLES) {
            assertTrue("missing $table", ddl.contains("CREATE TABLE $table") || ddl.contains("CREATE VIRTUAL TABLE $table"))
        }
        for (index in RecordsSchema.INDEXES) assertTrue("missing $index", ddl.contains("CREATE INDEX $index"))
        assertTrue(ddl.contains("USING fts4(title, people, clinical, body, notes_tags, highlights)"))
        assertTrue(ddl.contains("REFERENCES records(id) ON DELETE CASCADE"))
    }

    @Test
    fun selectColumnsCoverEveryRecordsColumn() {
        val create = RecordsSchema.STATEMENTS.first { it.startsWith("CREATE TABLE records (") }
        val body = create.substring(create.indexOf('(') + 1, create.lastIndexOf(')'))
        val declared = body.split(',').map { it.trim().substringBefore(' ') }.filter { it.isNotEmpty() }
        val selected = SqliteRecordsStore.COLUMNS.split(',').map { it.trim() }
        assertEquals(declared, selected)
    }
}
