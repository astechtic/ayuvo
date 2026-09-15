-- Health Records schema v2 — Phase 2 "Intelligence" (contract: docs/health-records.md §8–§14).
-- Runs once, in one transaction, when user_version < 2. Embedded VERBATIM on both platforms
-- (comments stripped, one statement per entry) with a parity test, exactly like schema.sql.
-- A fresh install runs schema.sql then every migration in order; there is no combined v2 DDL.

ALTER TABLE records ADD COLUMN phash TEXT;
ALTER TABLE records ADD COLUMN text_signature TEXT;
ALTER TABLE records ADD COLUMN ai_mode_used TEXT NOT NULL DEFAULT 'none';
ALTER TABLE records ADD COLUMN ai_provider TEXT;
ALTER TABLE records ADD COLUMN type_confidence REAL;
ALTER TABLE records ADD COLUMN type_method TEXT;
CREATE INDEX idx_records_status ON records(processing_status);
CREATE INDEX idx_records_review ON records(review_status, archived);

CREATE TABLE record_fields (
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
  updated_ms INTEGER NOT NULL);
CREATE INDEX idx_record_fields_record ON record_fields(record_id, field_key);
CREATE INDEX idx_record_fields_key_value ON record_fields(field_key, value_text);

CREATE TABLE record_highlights (
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
  created_ms INTEGER NOT NULL);
CREATE INDEX idx_record_highlights_record ON record_highlights(record_id, section, position);

CREATE TABLE processing_jobs (
  record_id TEXT PRIMARY KEY NOT NULL REFERENCES records(id) ON DELETE CASCADE,
  stage TEXT NOT NULL,
  attempts INTEGER NOT NULL DEFAULT 0,
  next_attempt_ms INTEGER NOT NULL DEFAULT 0,
  last_error TEXT,
  requested_mode TEXT,
  awaiting_consent INTEGER NOT NULL DEFAULT 0,
  updated_ms INTEGER NOT NULL);
CREATE INDEX idx_processing_jobs_next ON processing_jobs(awaiting_consent, next_attempt_ms);

CREATE TABLE duplicate_candidates (
  record_id TEXT NOT NULL REFERENCES records(id) ON DELETE CASCADE,
  existing_id TEXT NOT NULL REFERENCES records(id) ON DELETE CASCADE,
  reason TEXT NOT NULL,
  score REAL NOT NULL,
  resolution TEXT NOT NULL DEFAULT 'pending',
  created_ms INTEGER NOT NULL,
  PRIMARY KEY (record_id, existing_id));

CREATE TABLE split_proposals (
  record_id TEXT PRIMARY KEY NOT NULL REFERENCES records(id) ON DELETE CASCADE,
  segments_json TEXT NOT NULL,
  status TEXT NOT NULL DEFAULT 'pending',
  created_ms INTEGER NOT NULL,
  updated_ms INTEGER NOT NULL);
