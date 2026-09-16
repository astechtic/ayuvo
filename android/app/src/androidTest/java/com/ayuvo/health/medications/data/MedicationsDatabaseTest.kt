package com.ayuvo.health.medications.data

import android.content.ContentValues
import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Fresh-install behaviour of `ayuvo_medications.db` (docs/medications.md §4) on a throwaway file. */
@RunWith(AndroidJUnit4::class)
class MedicationsDatabaseTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun setUp() {
        MedicationsDatabase.deleteDatabaseFiles(context, DB)
    }

    @After
    fun tearDown() {
        MedicationsDatabase.deleteDatabaseFiles(context, DB)
    }

    private fun namesOf(db: SQLiteDatabase, type: String): Set<String> {
        val out = mutableSetOf<String>()
        db.rawQuery("SELECT name FROM sqlite_master WHERE type = ?", arrayOf(type)).use { c ->
            while (c.moveToNext()) out += c.getString(0)
        }
        return out
    }

    private fun insertMedication(db: SQLiteDatabase, id: String = UUID.randomUUID().toString()): String {
        db.insertOrThrow("medications", null, ContentValues().apply {
            put("id", id)
            put("name", "Metformin")
            put("strength", "500 mg")
            put("form", "tablet")
            put("dose_quantity", 1.0)
            put("dose_unit", "tablet")
            put("food_relation", "with")
            put("start_date", "2026-09-16")
            put("status", "active")
            put("is_prn", 0)
            put("created_ms", 1_000L)
            put("updated_ms", 1_000L)
        })
        return id
    }

    private fun insertSchedule(db: SQLiteDatabase, medicationId: String, id: String = UUID.randomUUID().toString()): String {
        db.insertOrThrow("medication_schedules", null, ContentValues().apply {
            put("id", id)
            put("medication_id", medicationId)
            put("frequency_kind", "daily")
            put("times_json", "[\"08:00\",\"20:00\"]")
            put("days_json", "[]")
            put("reminder_enabled", 1)
            put("active_from_ms", 1_000L)
            put("created_ms", 1_000L)
            put("updated_ms", 1_000L)
        })
        return id
    }

    private fun doseLog(medicationId: String, scheduleId: String?, scheduledAtMs: Long, status: String = "taken") =
        ContentValues().apply {
            put("id", UUID.randomUUID().toString())
            put("medication_id", medicationId)
            if (scheduleId == null) putNull("schedule_id") else put("schedule_id", scheduleId)
            put("scheduled_at_ms", scheduledAtMs)
            put("status", status)
            put("taken_at_ms", scheduledAtMs)
            put("dose_quantity", 1.0)
            put("dose_unit", "tablet")
            put("created_ms", scheduledAtMs)
            put("updated_ms", scheduledAtMs)
        }

    @Test
    fun freshInstallCreatesSchemaAndStampsVersion() {
        val helper = MedicationsDatabase(context, DB)
        try {
            val db = helper.writableDatabase
            assertEquals(MedicationsSchema.VERSION, db.version)
            assertTrue(namesOf(db, "table").containsAll(MedicationsSchema.TABLES))
            assertTrue(namesOf(db, "index").containsAll(MedicationsSchema.INDEXES))
            db.rawQuery("SELECT value FROM medications_meta WHERE key = 'schema_version'", null).use { c ->
                assertTrue(c.moveToFirst())
                assertEquals(MedicationsSchema.VERSION.toString(), c.getString(0))
            }
            db.rawQuery("PRAGMA foreign_keys", null).use { c ->
                assertTrue(c.moveToFirst())
                assertEquals(1, c.getInt(0))
            }
            db.rawQuery("PRAGMA journal_mode", null).use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("wal", c.getString(0).lowercase())
            }
            assertTrue(MedicationsDatabase.exists(context, DB))
        } finally {
            helper.close()
        }
    }

    @Test
    fun deletingAMedicationCascadesToSchedulesAndLogs() {
        val helper = MedicationsDatabase(context, DB)
        try {
            val db = helper.writableDatabase
            val medicationId = insertMedication(db)
            val scheduleId = insertSchedule(db, medicationId)
            db.insertOrThrow("dose_logs", null, doseLog(medicationId, scheduleId, 2_000L))
            db.insertOrThrow("dose_logs", null, doseLog(medicationId, null, 3_000L))
            assertEquals(1, db.delete("medications", "id = ?", arrayOf(medicationId)))
            db.rawQuery("SELECT COUNT(*) FROM medication_schedules", null).use { c -> c.moveToFirst(); assertEquals(0, c.getInt(0)) }
            db.rawQuery("SELECT COUNT(*) FROM dose_logs", null).use { c -> c.moveToFirst(); assertEquals(0, c.getInt(0)) }
        } finally {
            helper.close()
        }
    }

    @Test
    fun deletingAScheduleKeepsLogsWithNullScheduleId() {
        val helper = MedicationsDatabase(context, DB)
        try {
            val db = helper.writableDatabase
            val medicationId = insertMedication(db)
            val scheduleId = insertSchedule(db, medicationId)
            db.insertOrThrow("dose_logs", null, doseLog(medicationId, scheduleId, 2_000L))
            assertEquals(1, db.delete("medication_schedules", "id = ?", arrayOf(scheduleId)))
            db.rawQuery("SELECT schedule_id FROM dose_logs", null).use { c ->
                assertTrue(c.moveToFirst())
                assertTrue(c.isNull(0))
            }
        } finally {
            helper.close()
        }
    }

    @Test
    fun occurrenceIndexRejectsDuplicatesButAllowsPrnRows() {
        val helper = MedicationsDatabase(context, DB)
        try {
            val db = helper.writableDatabase
            val medicationId = insertMedication(db)
            val scheduleId = insertSchedule(db, medicationId)
            db.insertOrThrow("dose_logs", null, doseLog(medicationId, scheduleId, 2_000L))
            try {
                db.insertOrThrow("dose_logs", null, doseLog(medicationId, scheduleId, 2_000L, status = "skipped"))
                fail("duplicate (schedule_id, scheduled_at_ms) must be rejected")
            } catch (_: SQLiteConstraintException) {
            }
            // NULL schedule ids are distinct for the unique index: two PRN doses at the same instant are fine.
            db.insertOrThrow("dose_logs", null, doseLog(medicationId, null, 2_000L))
            db.insertOrThrow("dose_logs", null, doseLog(medicationId, null, 2_000L))
            db.rawQuery("SELECT COUNT(*) FROM dose_logs", null).use { c -> c.moveToFirst(); assertEquals(3, c.getInt(0)) }
        } finally {
            helper.close()
        }
    }

    @Test
    fun scheduleForeignKeyRejectsUnknownMedication() {
        val helper = MedicationsDatabase(context, DB)
        try {
            val db = helper.writableDatabase
            try {
                insertSchedule(db, "missing")
                fail("schedule for an unknown medication must be rejected")
            } catch (_: SQLiteConstraintException) {
            }
        } finally {
            helper.close()
        }
    }

    @Test
    fun deleteDatabaseFilesRemovesEverything() {
        val helper = MedicationsDatabase(context, DB)
        helper.writableDatabase
        helper.close()
        assertTrue(MedicationsDatabase.exists(context, DB))
        MedicationsDatabase.deleteDatabaseFiles(context, DB)
        assertFalse(MedicationsDatabase.exists(context, DB))
        assertTrue(MedicationsDatabase.databaseFiles(context, DB).none { it.exists() })
    }

    private companion object {
        const val DB = "ayuvo_medications_test.db"
    }
}
