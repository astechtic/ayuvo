-- Ayuvo Coach — SQLite schema v1 (contract: docs/coach.md).
--
-- A separate database from Health Data (shared/health/schema.sql), Health Records
-- (shared/records/schema.sql) and Medications (shared/medications/schema.sql):
--   Android: coach/data/CoachSchema.kt   (SQLiteOpenHelper, ayuvo_coach.db)
--   iOS:     Coach/Data/CoachSchema.swift (sqlite3, Application Support/Ayuvo/Coach/coach.sqlite)
-- Both platforms embed these statements VERBATIM (comments stripped, one statement per entry, split with the
-- docs/health-records.md §8 rule) and a parity test on each platform compares them with this file.
-- Later versions live in shared/coach/migrations/NNN_name.sql and run in order, each in one transaction;
-- `coach_meta.schema_version` and PRAGMA user_version track the version.
--
-- Conventions
--   ids            TEXT lowercase UUIDs.
--   *_ms           INTEGER epoch milliseconds (UTC).
--   deleted        1 = tombstone. Tombstones are LOCAL: they are never exported and always beat an
--                  incoming archive row, so a deleted conversation is never resurrected by an import.
--   *_json         JSON stored as TEXT. `data_sources_json` = {"food":bool,"health":bool,
--                  "medications":bool,"records":bool}; an absent key means "on" (docs/coach.md §8).
--                  `record_refs_json` = [{"record_id","title","date"}] (docs/health-records.md §26).
--                  `attachment_ids_json` = [TEXT] in the order the user attached them.
--   doc_id         INTEGER PRIMARY KEY (a rowid alias, so VACUUM never renumbers it) and the
--                  messages_fts docid. Internal: it is never exported.
--   seq            Message order inside a conversation, 1-based, never reused. Regenerated replies keep
--                  the seq of the reply they replace and differ by variant_index, so the stepper can
--                  walk versions without renumbering the thread.
--   messages_fts   default FTS4 'simple' tokenizer on both platforms, like records_fts; content is
--                  folded before indexing and before MATCH so non-ASCII search behaves the same.
--   TOOL PAYLOADS ARE NEVER STORED. Only the visible text, record_refs and attachment ids persist
--   (docs/health-records.md §26). No table here holds a health value.
--   Android minSdk 26 = SQLite 3.18: no UPSERT -> UPDATE-then-INSERT.
-- Connection settings: journal_mode=WAL, synchronous=NORMAL, busy_timeout=5000, foreign_keys=ON.
-- The database file and the attachment directory are excluded from OS backup and device transfer on
-- both platforms; they reach a cloud only through the explicit opt-in of docs/cloud-backup.md.

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
  records_online_decision TEXT);
CREATE INDEX idx_conversations_recent ON conversations(deleted, archived, pinned DESC, last_message_ms DESC);

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
  attachment_ids_json TEXT);
CREATE INDEX idx_messages_conversation ON messages(conversation_id, seq, variant_index);
CREATE INDEX idx_messages_regenerated ON messages(regenerated_from);

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
  deleted INTEGER NOT NULL DEFAULT 0);
CREATE INDEX idx_attachments_sha ON attachments(sha256);

CREATE VIRTUAL TABLE messages_fts USING fts4(content);

CREATE TABLE coach_meta (
  key TEXT PRIMARY KEY NOT NULL,
  value TEXT);
