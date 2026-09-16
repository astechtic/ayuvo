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

    /**
     * docs §8 statement splitting, applied literally: migrations are embedded as exactly these
     * strings (indentation kept), so the comparison is exact rather than whitespace-collapsed.
     */
    @Test
    fun migrationsAreEmbeddedVerbatimPerSplittingRule() {
        for ((version, name) in RecordsSchema.MIGRATION_FILES) {
            val file = listOf("../../shared/records/migrations/$name", "../shared/records/migrations/$name", "shared/records/migrations/$name")
                .map(::File).firstOrNull { it.exists() }
            assertTrue("shared/records/migrations/$name not found", file != null)
            assertEquals("$name (exact §8 split)", splitStatements(file!!.readText()), RecordsSchema.MIGRATIONS.getValue(version))
        }
    }

    @Test
    fun knowledgeMigrationDeclaresPhase3Schema() {
        val sql = RecordsSchema.migrationSql(3)
        for (table in listOf("observations", "analyte_user_aliases", "entities", "record_entities", "record_links")) {
            assertTrue("missing $table", sql.contains("CREATE TABLE $table ("))
            assertTrue(table in RecordsSchema.MIGRATION_TABLES)
        }
        for (index in listOf("idx_observations_trend", "idx_observations_record", "idx_observations_field", "idx_entities_kind_name", "idx_record_entities_entity", "idx_record_links_b")) {
            assertTrue("missing $index", sql.contains(" $index ON "))
        }
    }

    /** docs §33: v4 adds the two `records` share columns and `records_backup_state`. */
    @Test
    fun sharingMigrationDeclaresPhase5Schema() {
        val sql = RecordsSchema.migrationSql(4)
        assertTrue(sql.contains("ALTER TABLE records ADD COLUMN shared_count INTEGER NOT NULL DEFAULT 0"))
        assertTrue(sql.contains("ALTER TABLE records ADD COLUMN last_shared_ms INTEGER"))
        assertTrue(sql.contains("CREATE TABLE records_backup_state ("))
        assertTrue("records_backup_state" in RecordsSchema.MIGRATION_TABLES)
        assertEquals(4, RecordsSchema.VERSION)
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
        val added = RecordsSchema.MIGRATIONS.values.flatten()
            .filter { it.startsWith("ALTER TABLE records ADD COLUMN ") }
            .map { it.removePrefix("ALTER TABLE records ADD COLUMN ").substringBefore(' ') }
        assertTrue(all.drop(declared.size).all { it in added })
    }

    companion object {
        /** `statements(path)` of scripts/records_contract_check.py (docs §8). */
        fun splitStatements(source: String): List<String> {
            val text = source.replace("\r\n", "\n").replace('\r', '\n')
            val out = mutableListOf<String>()
            val cur = mutableListOf<String>()
            for (raw in text.split('\n')) {
                val k = raw.indexOf("--")
                val line = (if (k < 0) raw else raw.substring(0, k)).trimEnd()
                if (line.isEmpty()) continue
                cur += line
                if (line.endsWith(";")) {
                    val stmt = cur.joinToString("\n").dropLast(1).trimEnd()
                    if (stmt.isNotBlank()) out += stmt
                    cur.clear()
                }
            }
            check(cur.isEmpty()) { "text after the last ';'" }
            return out
        }
    }
}
