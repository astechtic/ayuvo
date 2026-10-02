package com.ayuvo.health.cycle.data

/**
 * Verbatim `shared/cycle/schema.sql` (v1), comments stripped, one statement per entry. CycleSchemaContractTest
 * compares these with the shared file; change the shared file first (docs/cycle-tracking.md §2).
 */
object CycleSchema {
    const val VERSION = 1

    val STATEMENTS: List<String> = listOf(
        """CREATE TABLE cycle_periods (
  id TEXT PRIMARY KEY NOT NULL,
  start_day TEXT NOT NULL,
  end_day TEXT,
  platform_ids_json TEXT NOT NULL DEFAULT '{}',
  sync_state TEXT NOT NULL DEFAULT 'pending',
  created_ms INTEGER NOT NULL,
  updated_ms INTEGER NOT NULL,
  deleted INTEGER NOT NULL DEFAULT 0)""",
        "CREATE INDEX idx_cycle_periods_start ON cycle_periods(deleted, start_day)",
        """CREATE TABLE cycle_day_logs (
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
  deleted INTEGER NOT NULL DEFAULT 0)""",
        """CREATE TABLE cycle_settings (
  id INTEGER PRIMARY KEY NOT NULL CHECK (id = 1),
  setup_done INTEGER NOT NULL DEFAULT 0,
  cycle_length INTEGER,
  period_length INTEGER,
  luteal_length INTEGER,
  settings_json TEXT NOT NULL DEFAULT '{}',
  updated_ms INTEGER NOT NULL)""",
        """CREATE TABLE cycle_meta (
  key TEXT PRIMARY KEY NOT NULL,
  value TEXT NOT NULL)"""
    )

    val SQL: String get() = STATEMENTS.joinToString(";\n", postfix = ";\n")

    val TABLES: List<String> = listOf("cycle_periods", "cycle_day_logs", "cycle_settings", "cycle_meta")

    val INDEXES: List<String> = listOf("idx_cycle_periods_start")
}
