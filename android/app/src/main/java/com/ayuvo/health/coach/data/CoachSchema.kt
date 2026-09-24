package com.ayuvo.health.coach.data

/**
 * Verbatim `shared/coach/schema.sql` (v1), comments stripped, one statement per entry.
 * `CoachSchemaContractTest` compares these with the shared file; change the shared file first.
 * A fresh install runs [STATEMENTS] then every migration in order (docs/coach.md §2).
 */
object CoachSchema {
    /** `schema.sql` alone. */
    const val BASE_VERSION = 1

    /** Latest `user_version`: [BASE_VERSION] plus every entry of [MIGRATIONS]. */
    const val VERSION = 1

    val TABLES: List<String> = listOf("conversations", "messages", "attachments", "messages_fts", "coach_meta")

    val INDEXES: List<String> = listOf("idx_conversations_recent", "idx_messages_conversation", "idx_messages_regenerated", "idx_attachments_sha")

    val STATEMENTS: List<String> = listOf(
        """CREATE TABLE conversations (
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
  records_online_decision TEXT)""",
        "CREATE INDEX idx_conversations_recent ON conversations(deleted, archived, pinned DESC, last_message_ms DESC)",
        """CREATE TABLE messages (
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
  attachment_ids_json TEXT)""",
        "CREATE INDEX idx_messages_conversation ON messages(conversation_id, seq, variant_index)",
        "CREATE INDEX idx_messages_regenerated ON messages(regenerated_from)",
        """CREATE TABLE attachments (
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
  deleted INTEGER NOT NULL DEFAULT 0)""",
        "CREATE INDEX idx_attachments_sha ON attachments(sha256)",
        "CREATE VIRTUAL TABLE messages_fts USING fts4(content)",
        """CREATE TABLE coach_meta (
  key TEXT PRIMARY KEY NOT NULL,
  value TEXT)"""
    )

    /** None yet; v2+ maps a version to its `shared/coach/migrations/NNN_*.sql` statements. */
    val MIGRATIONS: Map<Int, List<String>> = emptyMap()
}
