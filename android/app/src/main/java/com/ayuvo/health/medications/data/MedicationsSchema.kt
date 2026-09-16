package com.ayuvo.health.medications.data

/**
 * Verbatim `shared/medications/schema.sql` (v1) plus `shared/medications/migrations/NNN_*.sql`,
 * comments stripped, one statement per entry. MedicationsSchemaContractTest compares these with
 * the shared files; change the shared files first. A fresh install runs [STATEMENTS] then every
 * migration in order (docs/medications.md §4); there is no combined DDL.
 */
object MedicationsSchema {
    /** `schema.sql` alone. */
    const val BASE_VERSION = 1

    /** Latest `user_version`: [BASE_VERSION] plus every entry of [MIGRATIONS]. */
    const val VERSION = 1

    val STATEMENTS: List<String> = listOf(
        """CREATE TABLE medications (
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
  updated_ms INTEGER NOT NULL)""",
        "CREATE INDEX idx_medications_status ON medications(status, name COLLATE NOCASE)",
        "CREATE INDEX idx_medications_record ON medications(related_record_id)",
        """CREATE TABLE medication_schedules (
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
  updated_ms INTEGER NOT NULL)""",
        "CREATE INDEX idx_schedules_medication ON medication_schedules(medication_id, active_from_ms)",
        "CREATE INDEX idx_schedules_open ON medication_schedules(active_until_ms, medication_id)",
        """CREATE TABLE dose_logs (
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
  updated_ms INTEGER NOT NULL)""",
        "CREATE UNIQUE INDEX idx_dose_logs_occurrence ON dose_logs(schedule_id, scheduled_at_ms)",
        "CREATE INDEX idx_dose_logs_medication ON dose_logs(medication_id, scheduled_at_ms DESC)",
        "CREATE INDEX idx_dose_logs_scheduled ON dose_logs(scheduled_at_ms)",
        "CREATE INDEX idx_dose_logs_status ON dose_logs(status, snoozed_until_ms)",
        """CREATE TABLE medications_meta (
  key TEXT PRIMARY KEY NOT NULL,
  value TEXT NOT NULL)"""
    )

    /** Migration statements keyed by the `user_version` they produce, applied in order (none at v1). */
    val MIGRATIONS: Map<Int, List<String>> = linkedMapOf()

    /** Shared file name of each migration (contract test). */
    val MIGRATION_FILES: Map<Int, String> = linkedMapOf()

    val SQL: String get() = STATEMENTS.joinToString(";\n", postfix = ";\n")

    fun migrationSql(version: Int): String =
        MIGRATIONS.getValue(version).joinToString(";\n", postfix = ";\n")

    val TABLES: List<String> = listOf("medications", "medication_schedules", "dose_logs", "medications_meta")

    val INDEXES: List<String> = listOf(
        "idx_medications_status", "idx_medications_record",
        "idx_schedules_medication", "idx_schedules_open",
        "idx_dose_logs_occurrence", "idx_dose_logs_medication", "idx_dose_logs_scheduled", "idx_dose_logs_status"
    )
}
