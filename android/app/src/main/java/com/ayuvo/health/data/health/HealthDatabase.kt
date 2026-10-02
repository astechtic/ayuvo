package com.ayuvo.health.data.health

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.io.File
import java.time.ZoneId

/**
 * `ayuvo_health.db` — the local mirror of Health Connect (plus Google Health API rows, origin 3). Framework SQLite (no Room/KSP),
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
        db.execSQL("INSERT OR REPLACE INTO health_meta(key, value) VALUES ('registry_version', ?)", arrayOf(REGISTRY_VERSION))
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
        if (oldVersion < 2) db.execSQL(DERIVED_DAILY_VALUES)
        if (oldVersion < 3) {
            GOOGLE_HEALTH_STATEMENTS.forEach(db::execSQL)
            db.execSQL("INSERT OR REPLACE INTO health_meta(key, value) VALUES ('registry_version', ?)", arrayOf(REGISTRY_VERSION))
        }
        if (oldVersion < 4) VITAL_STATEMENTS.forEach(db::execSQL)
        db.execSQL("INSERT OR REPLACE INTO health_meta(key, value) VALUES ('schema_version', ?)", arrayOf(newVersion.toString()))
    }

    fun files(): List<File> = databaseFiles(context)

    companion object {
        const val NAME = "ayuvo_health.db"
        const val VERSION = 4
        const val REGISTRY_VERSION = "2"

        /** v2: on-device derived metrics (docs/derived-metrics.md); never exported, rebuilt on demand. */
        const val DERIVED_DAILY_VALUES = """CREATE TABLE derived_daily_values (
  metric_id TEXT NOT NULL, day TEXT NOT NULL,
  value REAL, value2 REAL, value3 REAL,
  quality REAL, source_kind TEXT NOT NULL DEFAULT 'derived',
  algo_version INTEGER NOT NULL, computed_ms INTEGER NOT NULL, PRIMARY KEY (metric_id, day))"""

        /** v3: Google Health API source (docs/google-health.md §3). */
        val GOOGLE_HEALTH_STATEMENTS: List<String> = listOf(
            """CREATE TABLE google_health_sync_state (
  gh_type TEXT PRIMARY KEY NOT NULL,
  cursor_ms INTEGER,
  page_token TEXT,
  last_sync_ms INTEGER, backfill_floor_ms INTEGER,
  status TEXT NOT NULL DEFAULT 'idle',
  last_error TEXT, last_error_ms INTEGER)""",
            """CREATE TABLE google_health_mirror (
  sample_id TEXT PRIMARY KEY NOT NULL REFERENCES health_samples(id) ON DELETE CASCADE,
  platform_id TEXT,
  mirror_status TEXT NOT NULL DEFAULT 'pending',
  mirrored_ms INTEGER, attempts INTEGER NOT NULL DEFAULT 0, last_error TEXT)""",
            "CREATE INDEX idx_ghm_status ON google_health_mirror(mirror_status)"
        )

        /**
         * v4: camera finger PPG / face rPPG scans (docs/camera-vitals.md §7). Never written to `health_samples` or
         * Health Connect; signals are float32 LE + raw deflate, no images and no video.
         */
        val VITAL_STATEMENTS: List<String> = listOf(
            """CREATE TABLE vital_scans (
  id TEXT PRIMARY KEY NOT NULL,
  mode TEXT NOT NULL,
  session_id TEXT,
  start_ms INTEGER NOT NULL, end_ms INTEGER NOT NULL, tz_offset_s INTEGER NOT NULL, local_day TEXT NOT NULL,
  duration_ms INTEGER NOT NULL, platform TEXT NOT NULL, device_model TEXT NOT NULL,
  camera_json TEXT NOT NULL,
  context TEXT NOT NULL DEFAULT 'resting',
  quality_score REAL, reject_reason TEXT,
  quality_json TEXT NOT NULL,
  results_json TEXT NOT NULL,
  algo_version INTEGER NOT NULL,
  reference_json TEXT,
  deleted INTEGER NOT NULL DEFAULT 0, updated_ms INTEGER NOT NULL)""",
            "CREATE INDEX idx_vs_mode_start ON vital_scans(mode, start_ms)",
            "CREATE INDEX idx_vs_day ON vital_scans(local_day)",
            """CREATE TABLE vital_scan_signals (
  scan_id TEXT NOT NULL REFERENCES vital_scans(id) ON DELETE CASCADE,
  kind TEXT NOT NULL,
  sample_rate REAL, encoding TEXT NOT NULL,
  data BLOB NOT NULL, meta_json TEXT, PRIMARY KEY (scan_id, kind))""",
            """CREATE TABLE vital_calibrations (
  id TEXT PRIMARY KEY NOT NULL, kind TEXT NOT NULL,
  device_model TEXT NOT NULL, scan_id TEXT, t_ms INTEGER NOT NULL,
  reference_json TEXT NOT NULL,
  features_json TEXT NOT NULL,
  deleted INTEGER NOT NULL DEFAULT 0, updated_ms INTEGER NOT NULL)""",
            """CREATE TABLE vital_device_profiles (
  device_model TEXT NOT NULL, camera_position TEXT NOT NULL,
  capability_json TEXT NOT NULL, updated_ms INTEGER NOT NULL, PRIMARY KEY (device_model, camera_position))"""
        )

        /** User-owned camera scan tables; "Clear synced health data" (deleteAll) leaves them alone. */
        val VITAL_TABLES: List<String> = listOf("vital_scans", "vital_scan_signals", "vital_calibrations", "vital_device_profiles")

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
            "CREATE TABLE health_meta (key TEXT PRIMARY KEY NOT NULL, value TEXT)",
            DERIVED_DAILY_VALUES
        ) + GOOGLE_HEALTH_STATEMENTS + VITAL_STATEMENTS

        val SCHEMA_SQL: String get() = SCHEMA_STATEMENTS.joinToString(";\n", postfix = ";\n")

        val TABLES: List<String> = listOf(
            "health_samples", "health_series_points", "health_daily_rollups", "health_hourly_rollups",
            "health_sources", "health_sync_state", "health_type_meta", "health_meta", "derived_daily_values",
            "google_health_sync_state", "google_health_mirror"
        ) + VITAL_TABLES

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
