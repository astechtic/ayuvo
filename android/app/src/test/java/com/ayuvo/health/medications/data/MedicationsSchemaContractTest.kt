package com.ayuvo.health.medications.data

import com.ayuvo.health.data.health.HealthSchemaContractTest
import com.ayuvo.health.records.data.RecordsSchemaContractTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * MedicationsSchema.STATEMENTS must equal `shared/medications/schema.sql` statement for
 * statement, both whitespace-collapsed and under the exact §8 splitting rule. The shared file is
 * required (docs/medications.md §4).
 */
class MedicationsSchemaContractTest {

    private fun sharedFile(relative: String): File? =
        listOf("../../$relative", "../$relative", relative).map(::File).firstOrNull { it.exists() }

    @Test
    fun embeddedDdlMatchesSharedSchema() {
        val file = sharedFile("shared/medications/schema.sql")
        assertTrue("shared/medications/schema.sql not found from ${File(".").absolutePath}", file != null)
        val shared = HealthSchemaContractTest.normalise(file!!.readText())
        val embedded = HealthSchemaContractTest.normalise(MedicationsSchema.SQL)
        val diff = buildString {
            for (i in 0 until maxOf(shared.size, embedded.size)) {
                val s = shared.getOrNull(i)
                val e = embedded.getOrNull(i)
                if (s != e) append("statement $i\n  shared:   $s\n  embedded: $e\n")
            }
        }
        assertTrue("schema.sql differs from MedicationsSchema.STATEMENTS:\n$diff", diff.isEmpty())
        assertEquals(MedicationsSchema.STATEMENTS.size, embedded.size)
    }

    /** The statements are embedded exactly as the §8 split of the shared file produces them. */
    @Test
    fun embeddedDdlIsVerbatimPerSplittingRule() {
        val file = sharedFile("shared/medications/schema.sql")
        assertTrue(file != null)
        assertEquals(RecordsSchemaContractTest.splitStatements(file!!.readText()), MedicationsSchema.STATEMENTS)
    }

    @Test
    fun embeddedMigrationsMatchSharedFiles() {
        for ((version, name) in MedicationsSchema.MIGRATION_FILES) {
            val file = sharedFile("shared/medications/migrations/$name")
            assertTrue("shared/medications/migrations/$name not found", file != null)
            assertEquals("$name (exact §8 split)", RecordsSchemaContractTest.splitStatements(file!!.readText()), MedicationsSchema.MIGRATIONS.getValue(version))
        }
        // Versions are contiguous from the base schema to VERSION (an empty range at v1).
        assertEquals((MedicationsSchema.BASE_VERSION + 1..MedicationsSchema.VERSION).toList(), MedicationsSchema.MIGRATIONS.keys.toList())
        assertEquals(MedicationsSchema.MIGRATIONS.keys, MedicationsSchema.MIGRATION_FILES.keys)
        assertEquals(1, MedicationsSchema.VERSION)
    }

    @Test
    fun embeddedDdlDeclaresEveryTableAndIndex() {
        val ddl = MedicationsSchema.SQL
        for (table in MedicationsSchema.TABLES) assertTrue("missing $table", ddl.contains("CREATE TABLE $table ("))
        for (index in MedicationsSchema.INDEXES) {
            assertTrue("missing $index", ddl.contains("CREATE INDEX $index ON ") || ddl.contains("CREATE UNIQUE INDEX $index ON "))
        }
        assertTrue(ddl.contains("CREATE UNIQUE INDEX idx_dose_logs_occurrence ON dose_logs(schedule_id, scheduled_at_ms)"))
        assertTrue(ddl.contains("REFERENCES medications(id) ON DELETE CASCADE"))
        assertTrue(ddl.contains("REFERENCES medication_schedules(id) ON DELETE SET NULL"))
        assertEquals(MedicationsSchema.TABLES.size, ddl.split("CREATE TABLE ").size - 1)
    }

    /** No shared migration file exists that the embedded map does not know about. */
    @Test
    fun noUnembeddedSharedMigrations() {
        val dir = sharedFile("shared/medications/migrations")
        val files = dir?.listFiles()?.filter { it.isFile && it.name.endsWith(".sql") }?.map { it.name }?.sorted().orEmpty()
        assertEquals(files, MedicationsSchema.MIGRATION_FILES.values.sorted())
    }
}
