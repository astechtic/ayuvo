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

    /**
     * Every embedded migration equals its shared file. Splitting rule (docs §8, pending the
     * reference's wording): drop `--` comment text, split on `;`, collapse whitespace.
     */
    @Test
    fun embeddedMigrationsMatchSharedFiles() {
        for ((version, name) in RecordsSchema.MIGRATION_FILES) {
            val file = listOf("../../shared/records/migrations/$name", "../shared/records/migrations/$name", "shared/records/migrations/$name")
                .map(::File).firstOrNull { it.exists() }
            assertTrue("shared/records/migrations/$name not found", file != null)
            val shared = HealthSchemaContractTest.normalise(file!!.readText())
            val embedded = HealthSchemaContractTest.normalise(RecordsSchema.migrationSql(version))
            val diff = buildString {
                for (i in 0 until maxOf(shared.size, embedded.size)) {
                    val s = shared.getOrNull(i)
                    val e = embedded.getOrNull(i)
                    if (s != e) append("statement $i\n  shared:   $s\n  embedded: $e\n")
                }
            }
            assertTrue("$name differs from RecordsSchema.MIGRATIONS[$version]:\n$diff", diff.isEmpty())
            assertEquals(RecordsSchema.MIGRATIONS.getValue(version).size, embedded.size)
        }
        // Versions are contiguous from the base schema to VERSION.
        assertEquals((RecordsSchema.BASE_VERSION + 1..RecordsSchema.VERSION).toList(), RecordsSchema.MIGRATIONS.keys.toList())
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
        val selected = SqliteRecordsStore.BASE_COLUMNS.split(',').map { it.trim() }
        assertEquals(declared, selected)
        val all = SqliteRecordsStore.COLUMNS.split(',').map { it.trim() }
        assertEquals(SqliteRecordsStore.COLUMN_COUNT, all.size)
        val added = RecordsSchema.MIGRATION_002.filter { it.startsWith("ALTER TABLE records ADD COLUMN ") }
            .map { it.removePrefix("ALTER TABLE records ADD COLUMN ").substringBefore(' ') }
        assertTrue(all.drop(declared.size).all { it in added })
    }
}
