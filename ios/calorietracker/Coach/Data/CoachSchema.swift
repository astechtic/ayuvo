import Foundation

/// Embedded copy of `shared/coach/schema.sql` (schema v1): the statements verbatim, comments
/// stripped, one statement per entry. `CoachDatabaseTests` compares these lists with the shared file
/// statement-for-statement, so a change to one without the other fails the build.
nonisolated enum CoachSchema {
    /// Latest `PRAGMA user_version` / `coach_meta.schema_version`.
    static let schemaVersion = 1
    static let baseVersion = 1

    nonisolated struct Migration: Sendable {
        let version: Int
        /// File name under `shared/coach/migrations/`.
        let fileName: String
        let statements: [String]
    }

    static let tableNames: [String] = [
        "conversations",
        "messages",
        "attachments",
        "messages_fts",
        "coach_meta",
    ]

    static let indexNames: [String] = [
        "idx_conversations_recent",
        "idx_messages_conversation",
        "idx_messages_regenerated",
        "idx_attachments_sha",
    ]

    /// None yet; v2+ will list `Migration(version:fileName:statements:)` entries in order.
    static let migrations: [Migration] = []

    static let statements: [String] = [
        """
        CREATE TABLE conversations (
          id TEXT PRIMARY KEY NOT NULL,
          title TEXT NOT NULL DEFAULT '',
          created_ms INTEGER NOT NULL,
          updated_ms INTEGER NOT NULL,
          last_message_ms INTEGER,
          pinned INTEGER NOT NULL DEFAULT 0,
          archived INTEGER NOT NULL DEFAULT 0,
          deleted INTEGER NOT NULL DEFAULT 0,
          data_sources_json TEXT,
          selected_record_ids_json TEXT,
          provider_override TEXT,
          records_online_decision TEXT)
        """,
        "CREATE INDEX idx_conversations_recent ON conversations(deleted, archived, pinned DESC, last_message_ms DESC)",
        """
        CREATE TABLE messages (
          doc_id INTEGER PRIMARY KEY,
          id TEXT NOT NULL UNIQUE,
          conversation_id TEXT NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
          seq INTEGER NOT NULL,
          variant_index INTEGER NOT NULL DEFAULT 0,
          role TEXT NOT NULL,
          content TEXT NOT NULL DEFAULT '',
          created_ms INTEGER NOT NULL,
          updated_ms INTEGER NOT NULL,
          deleted INTEGER NOT NULL DEFAULT 0,
          regenerated_from TEXT,
          record_refs_json TEXT,
          attachment_ids_json TEXT)
        """,
        "CREATE INDEX idx_messages_conversation ON messages(conversation_id, seq, variant_index)",
        "CREATE INDEX idx_messages_regenerated ON messages(regenerated_from)",
        """
        CREATE TABLE attachments (
          id TEXT PRIMARY KEY NOT NULL,
          kind TEXT NOT NULL,
          filename TEXT NOT NULL DEFAULT '',
          mime_type TEXT,
          bytes INTEGER NOT NULL DEFAULT 0,
          sha256 TEXT,
          page_count INTEGER,
          char_count INTEGER,
          excerpt TEXT,
          file_path TEXT,
          thumbnail_path TEXT,
          created_ms INTEGER NOT NULL,
          deleted INTEGER NOT NULL DEFAULT 0)
        """,
        "CREATE INDEX idx_attachments_sha ON attachments(sha256)",
        "CREATE VIRTUAL TABLE messages_fts USING fts4(content)",
        """
        CREATE TABLE coach_meta (
          key TEXT PRIMARY KEY NOT NULL,
          value TEXT)
        """,
    ]

    /// docs/health-records.md §8 statement splitting, shared with `MedicationsSchema`.
    static func parseStatements(_ sql: String) -> [String] {
        MedicationsSchema.parseStatements(sql)
    }
}
