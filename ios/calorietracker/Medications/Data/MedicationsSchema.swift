import Foundation

/// Embedded copy of `shared/medications/schema.sql` (schema v1) and every
/// `shared/medications/migrations/NNN_*.sql`: the statements verbatim, comments stripped, one
/// statement per entry. `MedicationsDatabaseTests` compares these lists with the shared file
/// statement-for-statement and column-for-column. A fresh install runs v1 then each migration.
nonisolated enum MedicationsSchema {
    /// Latest `PRAGMA user_version` / `medications_meta.schema_version`.
    static let schemaVersion = 1
    static let baseVersion = 1

    nonisolated struct Migration: Sendable {
        let version: Int
        /// File name under `shared/medications/migrations/`.
        let fileName: String
        let statements: [String]
    }

    static let tableNames: [String] = [
        "medications", "medication_schedules", "dose_logs", "medications_meta",
    ]

    static let indexNames: [String] = [
        "idx_medications_status", "idx_medications_record",
        "idx_schedules_medication", "idx_schedules_open",
        "idx_dose_logs_occurrence", "idx_dose_logs_medication", "idx_dose_logs_scheduled", "idx_dose_logs_status",
    ]

    /// None yet; v2+ will list `Migration(version:fileName:statements:)` entries in order.
    static let migrations: [Migration] = []

    static let statements: [String] = [
        """
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
          updated_ms INTEGER NOT NULL)
        """,
        "CREATE INDEX idx_medications_status ON medications(status, name COLLATE NOCASE)",
        "CREATE INDEX idx_medications_record ON medications(related_record_id)",
        """
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
          updated_ms INTEGER NOT NULL)
        """,
        "CREATE INDEX idx_schedules_medication ON medication_schedules(medication_id, active_from_ms)",
        "CREATE INDEX idx_schedules_open ON medication_schedules(active_until_ms, medication_id)",
        """
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
          updated_ms INTEGER NOT NULL)
        """,
        "CREATE UNIQUE INDEX idx_dose_logs_occurrence ON dose_logs(schedule_id, scheduled_at_ms)",
        "CREATE INDEX idx_dose_logs_medication ON dose_logs(medication_id, scheduled_at_ms DESC)",
        "CREATE INDEX idx_dose_logs_scheduled ON dose_logs(scheduled_at_ms)",
        "CREATE INDEX idx_dose_logs_status ON dose_logs(status, snoozed_until_ms)",
        """
        CREATE TABLE medications_meta (
          key TEXT PRIMARY KEY NOT NULL,
          value TEXT NOT NULL)
        """,
    ]

    /// Contract §8 statement splitting (same rule as the records schema): CRLF/CR → LF; delete
    /// from the first `--` to the end of each line; right-trim lines and drop empty ones; a line
    /// ending with `;` closes the statement, whose lines join with `\n` and lose the final `;`.
    /// Returns nil when text follows the last `;`.
    static func parseStatementsStrict(_ sql: String) -> [String]? {
        let text = sql.replacingOccurrences(of: "\r\n", with: "\n").replacingOccurrences(of: "\r", with: "\n")
        var statements: [String] = []
        var current: [String] = []
        for rawLine in text.components(separatedBy: "\n") {
            var line = rawLine
            if let comment = line.range(of: "--") { line = String(line[..<comment.lowerBound]) }
            while let last = line.last, last == " " || last == "\t" { line.removeLast() }
            guard !line.isEmpty else { continue }
            if line.hasSuffix(";") {
                current.append(String(line.dropLast()))
                statements.append(current.joined(separator: "\n"))
                current = []
            } else {
                current.append(line)
            }
        }
        return current.isEmpty ? statements : nil
    }

    static func parseStatements(_ sql: String) -> [String] {
        parseStatementsStrict(sql) ?? []
    }
}
