package com.ayuvo.health.partner

import com.ayuvo.health.data.health.HealthSchemaContractTest
import com.ayuvo.health.partner.data.PartnerDatabase
import com.ayuvo.health.partner.data.PartnerSchema
import com.ayuvo.health.records.data.RecordsSchemaContractTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.io.File

/**
 * PartnerSchema.STATEMENTS must equal `shared/partner/schema.sql` statement for statement, both
 * whitespace-collapsed and under the exact records splitting rule (docs/partner-sync.md §6).
 */
class PartnerSchemaContractTest {

    private fun schemaFile(): File = PartnerTestFiles.shared("schema.sql").also { assertNotNull("shared/partner/schema.sql not found", it) }!!

    @Test
    fun embeddedDdlMatchesSharedSchema() {
        val shared = HealthSchemaContractTest.normalise(schemaFile().readText())
        val embedded = HealthSchemaContractTest.normalise(PartnerSchema.SQL)
        assertEquals(shared, embedded)
        assertEquals(PartnerSchema.STATEMENTS.size, embedded.size)
    }

    @Test
    fun embeddedDdlIsVerbatimPerSplittingRule() {
        assertEquals(RecordsSchemaContractTest.splitStatements(schemaFile().readText()), PartnerSchema.STATEMENTS)
    }

    @Test
    fun tablesIndexesAndVersion() {
        val heads = PartnerSchema.STATEMENTS.map { it.substringBefore('(').trim().split(Regex("\\s+")) }
        assertEquals(PartnerSchema.TABLES, heads.filter { it.take(2) == listOf("CREATE", "TABLE") }.map { it[2] })
        assertEquals(PartnerSchema.INDEXES, heads.filter { it.take(2) == listOf("CREATE", "INDEX") }.map { it[2] })
        assertEquals(PartnerSchema.TABLES.size + PartnerSchema.INDEXES.size, PartnerSchema.STATEMENTS.size)
        assertEquals(1, PartnerSchema.SCHEMA_VERSION)
        assertEquals(PartnerSchema.SCHEMA_VERSION, PartnerDatabase.VERSION)
        assertEquals("ayuvo_partner.db", PartnerDatabase.NAME)
        for (s in PartnerSchema.STATEMENTS) {
            assertFalse("SQLite 3.18 has no upsert: $s", s.uppercase().contains("ON CONFLICT") || s.uppercase().contains("UPSERT"))
        }
    }

    @Test
    fun noSharedMigrations() {
        val dir = PartnerTestFiles.shared("migrations")
        val files = dir?.listFiles()?.filter { it.isFile && it.name.endsWith(".sql") }?.map { it.name }?.sorted().orEmpty()
        assertEquals(PartnerSchema.MIGRATION_FILES, files)
    }
}

/** The catalogs shipped in assets/partner/ are byte-identical to the shared contract files. */
class PartnerAssetsContractTest {
    private fun asset(name: String): File? =
        listOf("src/main/assets/partner/$name", "app/src/main/assets/partner/$name", "android/app/src/main/assets/partner/$name")
            .map(::File).firstOrNull { it.exists() }

    @Test
    fun assetCopiesEqualSharedFiles() {
        for (name in listOf("record_types.json", "protocol.json")) {
            val shared = PartnerTestFiles.shared(name)
            val copy = asset(name)
            assertNotNull("shared/partner/$name", shared)
            assertNotNull("assets/partner/$name", copy)
            assertArrayEquals("assets/partner/$name differs from shared/partner/$name", shared!!.readBytes(), copy!!.readBytes())
        }
    }

    /** Backup exclusion of ayuvo_partner.db and its journal files (docs §2). */
    @Test
    fun partnerDatabaseExcludedFromBackups() {
        for (name in listOf("backup_rules.xml", "data_extraction_rules.xml")) {
            val f = listOf("src/main/res/xml/$name", "app/src/main/res/xml/$name").map(::File).first { it.exists() }
            val text = f.readText()
            for (suffix in listOf("", "-wal", "-shm", "-journal")) {
                val entry = "<exclude domain=\"database\" path=\"ayuvo_partner.db$suffix\" />"
                val expected = if (name == "data_extraction_rules.xml") 2 else 1
                assertEquals("$name: $entry", expected, text.split(entry).size - 1)
            }
        }
    }
}
