package com.ayuvo.health.records.data

/**
 * Verbatim `shared/records/schema.sql` (v1), comments stripped, one statement per entry.
 * RecordsSchemaContractTest compares these with the shared file; change the shared file first.
 */
object RecordsSchema {
    const val VERSION = 1

    val STATEMENTS: List<String> = listOf(
        """CREATE TABLE records (
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
  notes TEXT)""",
        "CREATE INDEX idx_records_timeline ON records(archived, sort_date DESC, created_ms DESC, seq DESC)",
        "CREATE INDEX idx_records_checksum ON records(checksum_sha256)",
        "CREATE INDEX idx_records_parent ON records(parent_id)",
        "CREATE INDEX idx_records_type ON records(record_type)",
        """CREATE TABLE record_pages (
  record_id TEXT NOT NULL REFERENCES records(id) ON DELETE CASCADE,
  page_index INTEGER NOT NULL,
  text TEXT,
  text_source TEXT NOT NULL,
  ocr_confidence REAL,
  width INTEGER, height INTEGER,
  blocks_json TEXT,
  PRIMARY KEY (record_id, page_index))""",
        """CREATE TABLE tags (
  id TEXT PRIMARY KEY NOT NULL,
  name TEXT NOT NULL UNIQUE COLLATE NOCASE)""",
        """CREATE TABLE record_tags (
  record_id TEXT NOT NULL REFERENCES records(id) ON DELETE CASCADE,
  tag_id TEXT NOT NULL REFERENCES tags(id) ON DELETE CASCADE,
  PRIMARY KEY (record_id, tag_id))""",
        "CREATE INDEX idx_record_tags_tag ON record_tags(tag_id)",
        "CREATE VIRTUAL TABLE records_fts USING fts4(title, people, clinical, body, notes_tags, highlights)",
        """CREATE TABLE records_meta (
  key TEXT PRIMARY KEY NOT NULL,
  value TEXT NOT NULL)"""
    )

    val SQL: String get() = STATEMENTS.joinToString(";\n", postfix = ";\n")

    val TABLES: List<String> = listOf("records", "record_pages", "tags", "record_tags", "records_fts", "records_meta")

    val INDEXES: List<String> = listOf(
        "idx_records_timeline", "idx_records_checksum", "idx_records_parent", "idx_records_type", "idx_record_tags_tag"
    )
}
