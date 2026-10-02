-- Ayuvo Cycle tracking — SQLite schema v1 (contract: docs/cycle-tracking.md).
--
-- A separate database from Health Data (shared/health/schema.sql) and Medications (shared/medications/schema.sql):
--   Android: cycle/data/CycleSchema.kt   (SQLiteOpenHelper, ayuvo_cycle.db)
--   iOS:     Cycle/Data/CycleSchema.swift (sqlite3, Application Support/Ayuvo/Cycle/cycle.sqlite)
-- Both platforms embed these statements VERBATIM (comments stripped, one statement per entry, split on ';') and a
-- parity test on each platform compares them with this file. Later versions go in shared/cycle/migrations/.
-- The database is excluded from iCloud / Android Auto Backup; it travels only in Export All Data (section "cycle").
--
-- Conventions
--   ids        TEXT 'local:<lowercase uuid>'.
--   *_day      TEXT 'yyyy-MM-dd' device-local calendar day (a period is a run of whole days; no times are stored).
--   *_ms       INTEGER epoch milliseconds (UTC).
--   *_json     JSON arrays of catalogue keys from shared/cycle/cycle_config.json; unknown keys are kept and ignored.
--   platform_ids_json  JSON object of samples Ayuvo wrote to HealthKit / Health Connect for this row
--                      ({"healthkit": [uuid…], "health_connect": [clientRecordId…]}), used to replace or delete them.
--   deleted    soft delete (1) so a delete can still remove the written platform samples and merge on import.
--   Only app-entered rows live here; periods read from HealthKit / Health Connect stay in health_samples.
-- Connection settings: journal_mode=WAL, synchronous=NORMAL, busy_timeout=5000.

CREATE TABLE cycle_periods (
  id TEXT PRIMARY KEY NOT NULL,
  start_day TEXT NOT NULL,
  end_day TEXT,
  platform_ids_json TEXT NOT NULL DEFAULT '{}',
  sync_state TEXT NOT NULL DEFAULT 'pending',
  created_ms INTEGER NOT NULL,
  updated_ms INTEGER NOT NULL,
  deleted INTEGER NOT NULL DEFAULT 0);
CREATE INDEX idx_cycle_periods_start ON cycle_periods(deleted, start_day);

CREATE TABLE cycle_day_logs (
  day TEXT PRIMARY KEY NOT NULL,
  flow TEXT,
  pain INTEGER,
  pain_locations_json TEXT NOT NULL DEFAULT '[]',
  symptoms_json TEXT NOT NULL DEFAULT '[]',
  moods_json TEXT NOT NULL DEFAULT '[]',
  note TEXT,
  platform_ids_json TEXT NOT NULL DEFAULT '{}',
  sync_state TEXT NOT NULL DEFAULT 'pending',
  updated_ms INTEGER NOT NULL,
  deleted INTEGER NOT NULL DEFAULT 0);

CREATE TABLE cycle_settings (
  id INTEGER PRIMARY KEY NOT NULL CHECK (id = 1),
  setup_done INTEGER NOT NULL DEFAULT 0,
  cycle_length INTEGER,
  period_length INTEGER,
  luteal_length INTEGER,
  settings_json TEXT NOT NULL DEFAULT '{}',
  updated_ms INTEGER NOT NULL);

CREATE TABLE cycle_meta (
  key TEXT PRIMARY KEY NOT NULL,
  value TEXT NOT NULL);
