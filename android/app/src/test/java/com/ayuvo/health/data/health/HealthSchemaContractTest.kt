package com.ayuvo.health.data.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import java.io.File

/**
 * The embedded DDL must equal `shared/health/schema.sql` statement for statement (comments
 * and whitespace ignored). Skipped while the shared file does not exist yet; the structural
 * assertions on the embedded DDL always run.
 */
class HealthSchemaContractTest {

    @Test
    fun embeddedDdlCreatesEveryTableAndIndex() {
        val ddl = HealthDatabase.SCHEMA_SQL
        for (table in HealthDatabase.TABLES) assertTrue("missing $table", ddl.contains("CREATE TABLE $table"))
        for (index in listOf("idx_hs_type_end", "idx_hs_type_start", "idx_hs_type_day", "idx_hsp_type_t", "idx_ghm_status", "idx_vs_mode_start", "idx_vs_day")) {
            assertTrue("missing $index", ddl.contains("CREATE INDEX $index"))
        }
        assertTrue(ddl.contains("REFERENCES health_samples(id) ON DELETE CASCADE"))
        assertTrue(ddl.contains("REFERENCES vital_scans(id) ON DELETE CASCADE"))
        assertEquals(HealthDatabase.SCHEMA_STATEMENTS.size, normalise(ddl).size)
        assertEquals(5, HealthDatabase.VERSION)
        // The v4 -> v5 migration adds exactly the analytics statements, which are also part of a fresh schema.
        assertTrue(HealthDatabase.SCHEMA_STATEMENTS.containsAll(HealthDatabase.ANALYTICS_STATEMENTS))
        assertTrue(ddl.contains("CREATE INDEX idx_ar_metric_end"))
        // The v3 -> v4 migration adds exactly the vitals statements, which are also part of a fresh schema.
        assertTrue(HealthDatabase.SCHEMA_STATEMENTS.containsAll(HealthDatabase.VITAL_STATEMENTS))
        for (table in HealthDatabase.VITAL_TABLES) {
            assertTrue("migration misses $table", HealthDatabase.VITAL_STATEMENTS.any { it.startsWith("CREATE TABLE $table ") })
        }
        // Scans never land in the Health Connect mirror tables.
        assertTrue(HealthDatabase.VITAL_STATEMENTS.none { it.contains("health_samples") })
        assertEquals("2", HealthDatabase.REGISTRY_VERSION)
        assertEquals(com.ayuvo.health.models.HealthDataType.REGISTRY_VERSION.toString(), HealthDatabase.REGISTRY_VERSION)
    }

    @Test
    fun embeddedDdlMatchesSharedSchemaWhenPresent() {
        val file = listOf("../../shared/health/schema.sql", "../shared/health/schema.sql", "shared/health/schema.sql")
            .map(::File).firstOrNull { it.exists() }
        Assume.assumeTrue("shared/health/schema.sql not present yet", file != null)
        val shared = normalise(file!!.readText())
        val embedded = normalise(HealthDatabase.SCHEMA_SQL)
        val diff = buildString {
            val n = maxOf(shared.size, embedded.size)
            for (i in 0 until n) {
                val s = shared.getOrNull(i)
                val e = embedded.getOrNull(i)
                if (s != e) append("statement $i\n  shared:   $s\n  embedded: $e\n")
            }
        }
        assertTrue("schema.sql differs from HealthDatabase.SCHEMA_STATEMENTS:\n$diff", diff.isEmpty())
    }

    companion object {
        /** Strip `--` comments, split on `;`, collapse whitespace (also inside parentheses). */
        fun normalise(sql: String): List<String> = sql.lines()
            .map { line -> line.substringBefore("--") }
            .joinToString("\n")
            .split(';')
            .map { it.replace(Regex("\\s+"), " ").replace(" (", "(").replace("( ", "(").replace(" )", ")").replace(" ,", ",").trim() }
            .filter { it.isNotEmpty() }
    }
}
