-- Ayuvo Medications — SQLite schema v1 (contract: docs/medications.md).
--
-- A separate database from Health Data (shared/health/schema.sql) and Health Records (shared/records/schema.sql):
--   Android: medications/data/MedicationsSchema.kt   (SQLiteOpenHelper, ayuvo_medications.db)
--   iOS:     Medications/Data/MedicationsSchema.swift (sqlite3, Application Support/Ayuvo/Medications/medications.sqlite)
-- Both platforms embed these statements VERBATIM (comments stripped, one statement per entry, split with the
-- docs/health-records.md §8 rule) and a parity test on each platform compares them with this file.
-- Later versions live in shared/medications/migrations/NNN_name.sql and run in order, each in one transaction;
-- `medications_meta.schema_version` and PRAGMA user_version track the version (fresh install = schema.sql + migrations).
--
-- Conventions
--   ids           TEXT lowercase UUIDs.
--   *_ms          INTEGER epoch milliseconds (UTC).   *_date TEXT 'yyyy-MM-dd' (device-local calendar day).
--   times_json    JSON array of 'HH:mm' local wall-clock strings, unique, ascending.
--   days_json     JSON array of ISO weekdays 1=Mon…7=Sun (empty unless frequency_kind = 'weekly').
--   enums         lowercase snake_case strings stored verbatim (docs §3); readers map unknown values to 'other' / ignore.
--   schedules     versioned: at most ONE row per medication with active_until_ms IS NULL. Rows are never edited after
--                 creation except to set active_until_ms/updated_ms; edits insert a new row. Dose logs keep the row
--                 that produced them, so history survives every edit.
--   dose_logs     only taken | skipped | missed | snoozed are stored; scheduled/due are derived. PRN doses have
--                 schedule_id NULL and scheduled_at_ms = taken_at_ms. dose_quantity/dose_unit are a snapshot.
--   Android minSdk 26 = SQLite 3.18: no UPSERT → UPDATE-then-INSERT; no FTS needed.
-- Connection settings: journal_mode=WAL, synchronous=NORMAL, busy_timeout=5000, foreign_keys=ON.

CREATE TABLE medications (
  id TEXT PRIMARY KEY NOT NULL,
  name TEXT NOT NULL,
  generic_name TEXT,
  brand_name TEXT,
  strength TEXT,
  form TEXT NOT NULL DEFAULT 'other',
  dose_quantity REAL NOT NULL DEFAULT 1,
  dose_unit TEXT NOT NULL DEFAULT 'tablet',
  food_relation TEXT NOT NULL DEFAULT 'anytime',
  instructions TEXT,
  start_date TEXT NOT NULL,
  end_date TEXT,
  status TEXT NOT NULL DEFAULT 'active',
  is_prn INTEGER NOT NULL DEFAULT 0,
  photo_path TEXT,
  related_record_id TEXT,
  created_ms INTEGER NOT NULL,
  updated_ms INTEGER NOT NULL);
CREATE INDEX idx_medications_status ON medications(status, name COLLATE NOCASE);
CREATE INDEX idx_medications_record ON medications(related_record_id);

CREATE TABLE medication_schedules (
  id TEXT PRIMARY KEY NOT NULL,
  medication_id TEXT NOT NULL REFERENCES medications(id) ON DELETE CASCADE,
  frequency_kind TEXT NOT NULL,
  times_json TEXT NOT NULL DEFAULT '[]',
  days_json TEXT NOT NULL DEFAULT '[]',
  interval_hours INTEGER,
  anchor_time TEXT,
  reminder_enabled INTEGER NOT NULL DEFAULT 1,
  active_from_ms INTEGER NOT NULL,
  active_until_ms INTEGER,
  created_ms INTEGER NOT NULL,
  updated_ms INTEGER NOT NULL);
CREATE INDEX idx_schedules_medication ON medication_schedules(medication_id, active_from_ms);
CREATE INDEX idx_schedules_open ON medication_schedules(active_until_ms, medication_id);

CREATE TABLE dose_logs (
  id TEXT PRIMARY KEY NOT NULL,
  medication_id TEXT NOT NULL REFERENCES medications(id) ON DELETE CASCADE,
  schedule_id TEXT REFERENCES medication_schedules(id) ON DELETE SET NULL,
  scheduled_at_ms INTEGER NOT NULL,
  status TEXT NOT NULL,
  taken_at_ms INTEGER,
  snoozed_until_ms INTEGER,
  dose_quantity REAL NOT NULL,
  dose_unit TEXT NOT NULL,
  note TEXT,
  created_ms INTEGER NOT NULL,
  updated_ms INTEGER NOT NULL);
CREATE UNIQUE INDEX idx_dose_logs_occurrence ON dose_logs(schedule_id, scheduled_at_ms);
CREATE INDEX idx_dose_logs_medication ON dose_logs(medication_id, scheduled_at_ms DESC);
CREATE INDEX idx_dose_logs_scheduled ON dose_logs(scheduled_at_ms);
CREATE INDEX idx_dose_logs_status ON dose_logs(status, snoozed_until_ms);

CREATE TABLE medications_meta (
  key TEXT PRIMARY KEY NOT NULL,
  value TEXT NOT NULL);
