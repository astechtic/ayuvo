package com.ayuvo.health.cycle

import com.ayuvo.health.cycle.data.CycleDatabase
import com.ayuvo.health.cycle.data.CycleSchema
import com.ayuvo.health.data.health.HealthSchemaContractTest
import com.ayuvo.health.records.data.RecordsSchemaContractTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** CycleSchema.STATEMENTS must equal `shared/cycle/schema.sql` statement for statement (docs/cycle-tracking.md §2). */
class CycleSchemaContractTest {
    private val shared get() = CycleTestFiles.shared("schema.sql")!!.readText()

    @Test
    fun embeddedDdlMatchesSharedSchema() {
        assertEquals(HealthSchemaContractTest.normalise(shared), HealthSchemaContractTest.normalise(CycleSchema.SQL))
    }

    @Test
    fun embeddedDdlIsVerbatimPerSplittingRule() {
        assertEquals(RecordsSchemaContractTest.splitStatements(shared), CycleSchema.STATEMENTS)
    }

    @Test
    fun embeddedDdlDeclaresEveryTableAndIndex() {
        val ddl = CycleSchema.SQL
        for (table in CycleSchema.TABLES) assertTrue("missing $table", ddl.contains("CREATE TABLE $table ("))
        for (index in CycleSchema.INDEXES) assertTrue("missing $index", ddl.contains("CREATE INDEX $index ON "))
        assertEquals(CycleSchema.TABLES.size, ddl.split("CREATE TABLE ").size - 1)
        assertEquals(1, CycleDatabase.VERSION)
        assertEquals("ayuvo_cycle.db", CycleDatabase.NAME)
    }
}
