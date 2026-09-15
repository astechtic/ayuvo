package com.ayuvo.health.records.data

/**
 * Verbatim `shared/records/schema.sql` (v1) plus `shared/records/migrations/NNN_*.sql`, comments
 * stripped, one statement per entry. RecordsSchemaContractTest compares these with the shared
 * files; change the shared files first. A fresh install runs [STATEMENTS] then every migration in
 * order (docs/health-records.md §8); there is no combined DDL.
 */
object RecordsSchema {
    /** `schema.sql` alone. */
    const val BASE_VERSION = 1

    /** Latest `user_version`: [BASE_VERSION] plus every entry of [MIGRATIONS]. */
    const val VERSION = 2

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

    /** `migrations/002_intelligence.sql` (user_version 2), verbatim. */
    val MIGRATION_002: List<String> = listOf(
        "ALTER TABLE records ADD COLUMN phash TEXT",
        "ALTER TABLE records ADD COLUMN text_signature TEXT",
        "ALTER TABLE records ADD COLUMN ai_mode_used TEXT NOT NULL DEFAULT 'none'",
        "ALTER TABLE records ADD COLUMN ai_provider TEXT",
        "ALTER TABLE records ADD COLUMN type_confidence REAL",
        "ALTER TABLE records ADD COLUMN type_method TEXT",
        "CREATE INDEX idx_records_status ON records(processing_status)",
        "CREATE INDEX idx_records_review ON records(review_status, archived)",
        """CREATE TABLE record_fields (
  id TEXT PRIMARY KEY NOT NULL,
  record_id TEXT NOT NULL REFERENCES records(id) ON DELETE CASCADE,
  field_key TEXT NOT NULL,
  value_text TEXT NOT NULL,
  value_json TEXT,
  method TEXT NOT NULL,
  confidence REAL NOT NULL DEFAULT 0,
  state TEXT NOT NULL DEFAULT 'suggested',
  source_page INTEGER,
  source_bbox TEXT,
  evidence TEXT,
  created_ms INTEGER NOT NULL,
  updated_ms INTEGER NOT NULL)""",
        "CREATE INDEX idx_record_fields_record ON record_fields(record_id, field_key)",
        "CREATE INDEX idx_record_fields_key_value ON record_fields(field_key, value_text)",
        """CREATE TABLE record_highlights (
  id TEXT PRIMARY KEY NOT NULL,
  record_id TEXT NOT NULL REFERENCES records(id) ON DELETE CASCADE,
  section TEXT NOT NULL,
  text TEXT NOT NULL,
  method TEXT NOT NULL,
  provider TEXT,
  field_id TEXT REFERENCES record_fields(id) ON DELETE CASCADE,
  source_page INTEGER,
  confidence REAL NOT NULL DEFAULT 0,
  dismissed INTEGER NOT NULL DEFAULT 0,
  position INTEGER NOT NULL DEFAULT 0,
  created_ms INTEGER NOT NULL)""",
        "CREATE INDEX idx_record_highlights_record ON record_highlights(record_id, section, position)",
        """CREATE TABLE processing_jobs (
  record_id TEXT PRIMARY KEY NOT NULL REFERENCES records(id) ON DELETE CASCADE,
  stage TEXT NOT NULL,
  attempts INTEGER NOT NULL DEFAULT 0,
  next_attempt_ms INTEGER NOT NULL DEFAULT 0,
  last_error TEXT,
  requested_mode TEXT,
  awaiting_consent INTEGER NOT NULL DEFAULT 0,
  updated_ms INTEGER NOT NULL)""",
        "CREATE INDEX idx_processing_jobs_next ON processing_jobs(awaiting_consent, next_attempt_ms)",
        """CREATE TABLE duplicate_candidates (
  record_id TEXT NOT NULL REFERENCES records(id) ON DELETE CASCADE,
  existing_id TEXT NOT NULL REFERENCES records(id) ON DELETE CASCADE,
  reason TEXT NOT NULL,
  score REAL NOT NULL,
  resolution TEXT NOT NULL DEFAULT 'pending',
  created_ms INTEGER NOT NULL,
  PRIMARY KEY (record_id, existing_id))""",
        """CREATE TABLE split_proposals (
  record_id TEXT PRIMARY KEY NOT NULL REFERENCES records(id) ON DELETE CASCADE,
  segments_json TEXT NOT NULL,
  status TEXT NOT NULL DEFAULT 'pending',
  created_ms INTEGER NOT NULL,
  updated_ms INTEGER NOT NULL)"""
    )

    /** Migration statements keyed by the `user_version` they produce, applied in order. */
    val MIGRATIONS: Map<Int, List<String>> = linkedMapOf(2 to MIGRATION_002)

    /** Shared file name of each migration (contract test). */
    val MIGRATION_FILES: Map<Int, String> = linkedMapOf(2 to "002_intelligence.sql")

    val SQL: String get() = STATEMENTS.joinToString(";\n", postfix = ";\n")

    fun migrationSql(version: Int): String =
        MIGRATIONS.getValue(version).joinToString(";\n", postfix = ";\n")

    val TABLES: List<String> = listOf("records", "record_pages", "tags", "record_tags", "records_fts", "records_meta")

    /** Tables added by migrations (v2). */
    val MIGRATION_TABLES: List<String> = listOf(
        "record_fields", "record_highlights", "processing_jobs", "duplicate_candidates", "split_proposals"
    )

    val INDEXES: List<String> = listOf(
        "idx_records_timeline", "idx_records_checksum", "idx_records_parent", "idx_records_type", "idx_record_tags_tag"
    )
}
