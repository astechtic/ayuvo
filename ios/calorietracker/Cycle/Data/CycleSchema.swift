import Foundation

/// Embedded copy of `shared/cycle/schema.sql` (schema v1): the statements verbatim, comments stripped, one statement
/// per entry. `CycleDatabaseTests` compares this list with the shared file statement for statement.
nonisolated enum CycleSchema {
    /// Latest `PRAGMA user_version` / `cycle_meta.schema_version`.
    static let schemaVersion = 1

    static let tableNames: [String] = ["cycle_periods", "cycle_day_logs", "cycle_settings", "cycle_meta"]

    static let indexNames: [String] = ["idx_cycle_periods_start"]

    static let statements: [String] = [
        """
        CREATE TABLE cycle_periods (
          id TEXT PRIMARY KEY NOT NULL,
          start_day TEXT NOT NULL,
          end_day TEXT,
          platform_ids_json TEXT NOT NULL DEFAULT '{}',
          sync_state TEXT NOT NULL DEFAULT 'pending',
          created_ms INTEGER NOT NULL,
          updated_ms INTEGER NOT NULL,
          deleted INTEGER NOT NULL DEFAULT 0)
        """,
        "CREATE INDEX idx_cycle_periods_start ON cycle_periods(deleted, start_day)",
        """
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
          deleted INTEGER NOT NULL DEFAULT 0)
        """,
        """
        CREATE TABLE cycle_settings (
          id INTEGER PRIMARY KEY NOT NULL CHECK (id = 1),
          setup_done INTEGER NOT NULL DEFAULT 0,
          cycle_length INTEGER,
          period_length INTEGER,
          luteal_length INTEGER,
          settings_json TEXT NOT NULL DEFAULT '{}',
          updated_ms INTEGER NOT NULL)
        """,
        """
        CREATE TABLE cycle_meta (
          key TEXT PRIMARY KEY NOT NULL,
          value TEXT NOT NULL)
        """,
    ]

    /// Same splitting rule as `MedicationsSchema.parseStatementsStrict` (docs/health-records.md §8).
    static func parseStatements(_ sql: String) -> [String] {
        MedicationsSchema.parseStatements(sql)
    }
}
