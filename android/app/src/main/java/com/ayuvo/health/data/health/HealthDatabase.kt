package com.ayuvo.health.data.health

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.io.File
import java.time.ZoneId

/**
 * `ayuvo_health.db` — the local mirror of Health Connect. Framework SQLite (no Room/KSP),
 * WAL journal, foreign keys on. The DDL below is embedded verbatim from
 * `shared/health/schema.sql`; HealthSchemaContractTest keeps the two in step and the
 * instrumented SchemaParityTest checks the live `PRAGMA table_info`.
 *
 * The file and its `-wal`/`-shm`/`-journal` siblings are excluded from Android backup
 * (backup_rules.xml / data_extraction_rules.xml) and never ride on the Drive backup.
 */
class HealthDatabase(private val context: Context) : SQLiteOpenHelper(context, NAME, null, VERSION) {

    override fun onConfigure(db: SQLiteDatabase) {
        db.setForeignKeyConstraintsEnabled(true)
        db.enableWriteAheadLogging()
    }

    override fun onCreate(db: SQLiteDatabase) {
        SCHEMA_STATEMENTS.forEach(db::execSQL)
        db.execSQL("INSERT OR REPLACE INTO health_meta(key, value) VALUES ('schema_version', ?)", arrayOf(VERSION.toString()))
        db.execSQL("INSERT OR REPLACE INTO health_meta(key, value) VALUES ('registry_version', '1')")
        db.execSQL("INSERT OR REPLACE INTO health_meta(key, value) VALUES ('rollup_rule_version', '1')")
        db.execSQL(
            "INSERT OR REPLACE INTO health_meta(key, value) VALUES ('rollups_tz', ?)",
            arrayOf(ZoneId.systemDefault().id)
        )
    }

    override fun onOpen(db: SQLiteDatabase) {
        // rawQuery so the pragma actually runs (execSQL discards PRAGMA result rows on some builds).
        db.rawQuery("PRAGMA synchronous=NORMAL", null).use { it.moveToFirst() }
        db.rawQuery("PRAGMA busy_timeout=5000", null).use { it.moveToFirst() }
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // v1 is the first schema; future migrations append here.
    }

    fun files(): List<File> = databaseFiles(context)

    companion object {
        const val NAME = "ayuvo_health.db"
        const val VERSION = 1

        /** Verbatim `shared/health/schema.sql`, one statement per entry. */
        val SCHEMA_STATEMENTS: List<String> = listOf(
            """CREATE TABLE health_samples (
  id TEXT PRIMARY KEY NOT NULL,
  type_id TEXT NOT NULL,
  start_ms INTEGER NOT NULL, end_ms INTEGER NOT NULL,
  start_offset_s INTEGER, end_offset_s INTEGER,
  local_day TEXT NOT NULL,
  value REAL, value2 REAL, value3 REAL, value_text TEXT,
  unit TEXT NOT NULL, category_value INTEGER, title TEXT, extra_json TEXT,
  count INTEGER NOT NULL DEFAULT 1,
  source_id TEXT NOT NULL,
  device TEXT, device_type INTEGER, recording_method INTEGER, client_record_id TEXT,
  origin INTEGER NOT NULL DEFAULT 0,
  deleted INTEGER NOT NULL DEFAULT 0,
  updated_ms INTEGER NOT NULL)""",
            "CREATE INDEX idx_hs_type_end   ON health_samples(type_id, end_ms DESC)",
            "CREATE INDEX idx_hs_type_start ON health_samples(type_id, start_ms)",
            "CREATE INDEX idx_hs_type_day   ON health_samples(type_id, local_day)",
            """CREATE TABLE health_series_points (
  sample_id TEXT NOT NULL REFERENCES health_samples(id) ON DELETE CASCADE,
  type_id TEXT NOT NULL, t_ms INTEGER NOT NULL, value REAL NOT NULL, PRIMARY KEY (sample_id, t_ms))""",
            "CREATE INDEX idx_hsp_type_t ON health_series_points(type_id, t_ms)",
            """CREATE TABLE health_daily_rollups (
  type_id TEXT NOT NULL, day TEXT NOT NULL, tz TEXT NOT NULL,
  sum REAL, avg REAL, min REAL, max REAL, count INTEGER NOT NULL DEFAULT 0,
  last_value REAL, last_at_ms INTEGER, v2_avg REAL, v2_min REAL, v2_max REAL,
  duration_s REAL, own_sum REAL,
  from_platform_aggregate INTEGER NOT NULL DEFAULT 0, PRIMARY KEY (type_id, day))""",
            """CREATE TABLE health_hourly_rollups (type_id TEXT NOT NULL, day TEXT NOT NULL, hour INTEGER NOT NULL,
  sum REAL, avg REAL, min REAL, max REAL, count INTEGER NOT NULL DEFAULT 0, PRIMARY KEY (type_id, day, hour))""",
            "CREATE TABLE health_sources (id TEXT PRIMARY KEY NOT NULL, name TEXT NOT NULL, device_model TEXT, device_type INTEGER, last_seen_ms INTEGER)",
            """CREATE TABLE health_sync_state (
  type_id TEXT PRIMARY KEY NOT NULL,
  cursor TEXT, cursor_issued_ms INTEGER, last_sync_ms INTEGER,
  earliest_authorized_ms INTEGER,
  earliest_probe_ms INTEGER, backfill_floor_ms INTEGER, oldest_backfilled_ms INTEGER,
  backfill_done INTEGER NOT NULL DEFAULT 0, backfill_with_history INTEGER NOT NULL DEFAULT 0,
  status TEXT NOT NULL DEFAULT 'idle',
  last_error TEXT, last_error_ms INTEGER, ipc_calls_total INTEGER NOT NULL DEFAULT 0)""",
            """CREATE TABLE health_type_meta (type_id TEXT PRIMARY KEY NOT NULL, category TEXT NOT NULL, kind TEXT NOT NULL,
  aggregation TEXT NOT NULL, unit TEXT NOT NULL, display_name TEXT, platform TEXT, native_id TEXT)""",
            "CREATE TABLE health_meta (key TEXT PRIMARY KEY NOT NULL, value TEXT)"
        )

        val SCHEMA_SQL: String get() = SCHEMA_STATEMENTS.joinToString(";\n", postfix = ";\n")

        val TABLES: List<String> = listOf(
            "health_samples", "health_series_points", "health_daily_rollups", "health_hourly_rollups",
            "health_sources", "health_sync_state", "health_type_meta", "health_meta"
        )

        fun databaseFiles(context: Context): List<File> {
            val base = context.getDatabasePath(NAME)
            return listOf(base, File(base.path + "-wal"), File(base.path + "-shm"), File(base.path + "-journal"))
        }

        /** Deletes the database and every journal sibling; callers must close the helper first. */
        fun deleteDatabaseFiles(context: Context) {
            context.deleteDatabase(NAME)
            databaseFiles(context).forEach { runCatching { if (it.exists()) it.delete() } }
        }
    }
}
