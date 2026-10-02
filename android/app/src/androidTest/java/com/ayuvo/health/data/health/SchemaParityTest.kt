package com.ayuvo.health.data.health

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** The live database must expose exactly the columns the shared DDL declares, in order. */
@RunWith(AndroidJUnit4::class)
class SchemaParityTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var helper: HealthDatabase

    @Before
    fun setUp() {
        HealthDatabase.deleteDatabaseFiles(context)
        helper = HealthDatabase(context)
    }

    @After
    fun tearDown() {
        helper.close()
        HealthDatabase.deleteDatabaseFiles(context)
    }

    @Test
    fun tableInfoMatchesTheEmbeddedDdl() {
        val db = helper.readableDatabase
        val expected = expectedColumns()
        assertEquals(HealthDatabase.TABLES.toSet(), expected.keys)
        for ((table, columns) in expected) {
            val actual = mutableListOf<String>()
            db.rawQuery("PRAGMA table_info($table)", null).use { c ->
                val nameIdx = c.getColumnIndexOrThrow("name")
                while (c.moveToNext()) actual += c.getString(nameIdx)
            }
            assertEquals("columns of $table", columns, actual)
        }
        val indexes = mutableSetOf<String>()
        db.rawQuery("SELECT name FROM sqlite_master WHERE type = 'index' AND name LIKE 'idx_%'", null).use { c ->
            while (c.moveToNext()) indexes += c.getString(0)
        }
        assertEquals(
            setOf("idx_hs_type_end", "idx_hs_type_start", "idx_hs_type_day", "idx_hsp_type_t", "idx_ghm_status", "idx_vs_mode_start", "idx_vs_day"),
            indexes
        )
        db.rawQuery("PRAGMA foreign_keys", null).use { c -> c.moveToFirst(); assertEquals(1, c.getInt(0)) }
        db.rawQuery("PRAGMA journal_mode", null).use { c -> c.moveToFirst(); assertEquals("wal", c.getString(0).lowercase()) }
        assertEquals(HealthDatabase.VERSION, db.version)
        assertTrue(helper.files().first().exists())
    }

    /** A v3 database (no vitals tables) upgrades additively to v4 and keeps its rows. */
    @Test
    fun upgradeFromV3AddsTheVitalsTables() {
        val db = helper.writableDatabase
        db.execSQL(
            "INSERT INTO health_samples(id, type_id, start_ms, end_ms, local_day, unit, source_id, updated_ms) " +
                "VALUES ('a', 'steps', 1, 2, '2026-10-01', 'count', 'src', 3)"
        )
        for (table in HealthDatabase.VITAL_TABLES.reversed()) db.execSQL("DROP TABLE $table")
        db.version = 3
        helper.close()

        helper = HealthDatabase(context)
        val upgraded = helper.readableDatabase
        assertEquals(4, upgraded.version)
        val tables = mutableSetOf<String>()
        upgraded.rawQuery("SELECT name FROM sqlite_master WHERE type = 'table'", null).use { c ->
            while (c.moveToNext()) tables += c.getString(0)
        }
        assertTrue(tables.containsAll(HealthDatabase.VITAL_TABLES))
        upgraded.rawQuery("SELECT COUNT(*) FROM health_samples", null).use { c -> c.moveToFirst(); assertEquals(1, c.getInt(0)) }
        upgraded.rawQuery("SELECT value FROM health_meta WHERE key = 'schema_version'", null).use { c ->
            c.moveToFirst(); assertEquals("4", c.getString(0))
        }
    }

    /** Column names per table parsed from the CREATE TABLE statements (first token of each column def). */
    private fun expectedColumns(): Map<String, List<String>> {
        val out = LinkedHashMap<String, List<String>>()
        for (statement in HealthDatabase.SCHEMA_STATEMENTS) {
            if (!statement.startsWith("CREATE TABLE")) continue
            val table = statement.removePrefix("CREATE TABLE").trim().substringBefore('(').trim()
            val body = statement.substring(statement.indexOf('(') + 1, statement.lastIndexOf(')'))
            val columns = splitTopLevel(body)
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("PRIMARY KEY", ignoreCase = true) }
                .map { it.substringBefore(' ') }
            out[table] = columns
        }
        return out
    }

    private fun splitTopLevel(body: String): List<String> {
        val parts = mutableListOf<String>()
        var depth = 0
        val current = StringBuilder()
        for (ch in body) {
            when (ch) {
                '(' -> { depth++; current.append(ch) }
                ')' -> { depth--; current.append(ch) }
                ',' -> if (depth == 0) { parts += current.toString(); current.clear() } else current.append(ch)
                else -> current.append(ch)
            }
        }
        if (current.isNotBlank()) parts += current.toString()
        return parts
    }
}
