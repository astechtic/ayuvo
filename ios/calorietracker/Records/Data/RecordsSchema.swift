import Foundation

/// Embedded copy of `shared/records/schema.sql` (schema v1): the statements verbatim,
/// comments stripped, one statement per entry. `RecordsSchemaTests` compares this list
/// with the shared file statement-for-statement and column-for-column.
nonisolated enum RecordsSchema {
    static let schemaVersion = 1

    static let tableNames: [String] = ["records", "record_pages", "tags", "record_tags", "records_fts", "records_meta"]

    static let indexNames: [String] = ["idx_records_timeline", "idx_records_checksum", "idx_records_parent", "idx_records_type", "idx_record_tags_tag"]

    static let statements: [String] = [
        """
        CREATE TABLE records (
          seq INTEGER PRIMARY KEY,
          id TEXT NOT NULL UNIQUE,
          parent_id TEXT REFERENCES records(id) ON DELETE SET NULL,
          page_start INTEGER, page_end INTEGER,
          title TEXT NOT NULL,
          record_type TEXT NOT NULL DEFAULT 'other',
          category TEXT NOT NULL DEFAULT 'other',
          source TEXT NOT NULL,
          import_method TEXT NOT NULL,
          source_app TEXT,
          original_filename TEXT,
          created_ms INTEGER NOT NULL,
          updated_ms INTEGER NOT NULL,
          document_date TEXT,
          document_date_precision TEXT,
          document_date_method TEXT,
          sort_date TEXT NOT NULL,
          mime_type TEXT NOT NULL,
          file_type TEXT NOT NULL,
          file_size INTEGER NOT NULL DEFAULT 0,
          page_count INTEGER NOT NULL DEFAULT 0,
          file_path TEXT,
          thumbnail_path TEXT,
          checksum_sha256 TEXT,
          processing_status TEXT NOT NULL DEFAULT 'saved',
          processing_error TEXT,
          review_status TEXT NOT NULL DEFAULT 'none',
          favorite INTEGER NOT NULL DEFAULT 0,
          archived INTEGER NOT NULL DEFAULT 0,
          notes TEXT)
        """,
        "CREATE INDEX idx_records_timeline ON records(archived, sort_date DESC, created_ms DESC, seq DESC)",
        "CREATE INDEX idx_records_checksum ON records(checksum_sha256)",
        "CREATE INDEX idx_records_parent ON records(parent_id)",
        "CREATE INDEX idx_records_type ON records(record_type)",
        """
        CREATE TABLE record_pages (
          record_id TEXT NOT NULL REFERENCES records(id) ON DELETE CASCADE,
          page_index INTEGER NOT NULL,
          text TEXT,
          text_source TEXT NOT NULL,
          ocr_confidence REAL,
          width INTEGER, height INTEGER,
          blocks_json TEXT,
          PRIMARY KEY (record_id, page_index))
        """,
        """
        CREATE TABLE tags (
          id TEXT PRIMARY KEY NOT NULL,
          name TEXT NOT NULL UNIQUE COLLATE NOCASE)
        """,
        """
        CREATE TABLE record_tags (
          record_id TEXT NOT NULL REFERENCES records(id) ON DELETE CASCADE,
          tag_id TEXT NOT NULL REFERENCES tags(id) ON DELETE CASCADE,
          PRIMARY KEY (record_id, tag_id))
        """,
        "CREATE INDEX idx_record_tags_tag ON record_tags(tag_id)",
        "CREATE VIRTUAL TABLE records_fts USING fts4(title, people, clinical, body, notes_tags, highlights)",
        """
        CREATE TABLE records_meta (
          key TEXT PRIMARY KEY NOT NULL,
          value TEXT NOT NULL)
        """,
    ]

    /// Splits a `.sql` file the way both platforms embed it: drop `--` comment lines,
    /// split on `;`, trim, skip empties.
    static func parseStatements(_ sql: String) -> [String] {
        let kept = sql
            .components(separatedBy: "\n")
            .filter { !$0.trimmingCharacters(in: .whitespaces).hasPrefix("--") }
            .joined(separator: "\n")
        return kept
            .components(separatedBy: ";")
            .map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
            .filter { !$0.isEmpty }
    }
}
