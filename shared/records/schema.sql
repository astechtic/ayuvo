-- Ayuvo Health Records — SQLite schema v1 (contract: docs/health-records.md).
--
-- A separate database from the Health Data hub (shared/health/schema.sql):
--   Android: records/data/RecordsSchema.kt   (SQLiteOpenHelper, ayuvo_records.db)
--   iOS:     Records/Data/RecordsSchema.swift (sqlite3, Application Support/Ayuvo/Records/records.sqlite)
-- Both platforms embed these statements VERBATIM (comments stripped, one statement per entry)
-- and a parity test on each platform compares them with this file.
-- Later schema versions live in shared/records/migrations/NNN_name.sql and run in order,
-- each inside one transaction; `records_meta.schema_version` and PRAGMA user_version track the version.
--
-- Conventions
--   ids            TEXT lowercase UUIDs; records.seq (INTEGER PRIMARY KEY) is the records_fts docid.
--   records_fts    default FTS4 'simple' tokenizer on both platforms; text is folded before indexing
--                  and before MATCH (lowercase, diacritics removed) so non-ASCII search behaves the same.
--   timestamps     *_ms INTEGER epoch milliseconds (UTC).
--   document_date  TEXT 'yyyy-MM-dd' (NULL when unknown); precision 'day' | 'month' | 'year'.
--   sort_date      TEXT 'yyyy-MM-dd' = document_date, else the device-local day of created_ms at import.
--                  Written by the app on every insert and whenever document_date changes; drives the timeline.
--   file_path      relative to the records files root; never absolute.
--   Android minSdk 26 = SQLite 3.18: no UPSERT, no FTS5 → UPDATE-then-INSERT and FTS4.
-- Connection settings: journal_mode=WAL, synchronous=NORMAL, busy_timeout=5000, foreign_keys=ON.

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
  notes TEXT);
CREATE INDEX idx_records_timeline ON records(archived, sort_date DESC, created_ms DESC, seq DESC);
CREATE INDEX idx_records_checksum ON records(checksum_sha256);
CREATE INDEX idx_records_parent ON records(parent_id);
CREATE INDEX idx_records_type ON records(record_type);

CREATE TABLE record_pages (
  record_id TEXT NOT NULL REFERENCES records(id) ON DELETE CASCADE,
  page_index INTEGER NOT NULL,
  text TEXT,
  text_source TEXT NOT NULL,
  ocr_confidence REAL,
  width INTEGER, height INTEGER,
  blocks_json TEXT,
  PRIMARY KEY (record_id, page_index));

CREATE TABLE tags (
  id TEXT PRIMARY KEY NOT NULL,
  name TEXT NOT NULL UNIQUE COLLATE NOCASE);

CREATE TABLE record_tags (
  record_id TEXT NOT NULL REFERENCES records(id) ON DELETE CASCADE,
  tag_id TEXT NOT NULL REFERENCES tags(id) ON DELETE CASCADE,
  PRIMARY KEY (record_id, tag_id));
CREATE INDEX idx_record_tags_tag ON record_tags(tag_id);

CREATE VIRTUAL TABLE records_fts USING fts4(title, people, clinical, body, notes_tags, highlights);

CREATE TABLE records_meta (
  key TEXT PRIMARY KEY NOT NULL,
  value TEXT NOT NULL);
