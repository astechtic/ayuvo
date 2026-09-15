-- Health Records schema v3 — Phase 3 "Health knowledge base" (contract: docs/health-records.md §19–§24).
-- Runs once, in one transaction, when user_version < 3. Embedded VERBATIM on both platforms using the
-- §8 statement-splitting rule, with parity tests, exactly like schema.sql and 002.

CREATE TABLE observations (
  id TEXT PRIMARY KEY NOT NULL,
  record_id TEXT NOT NULL REFERENCES records(id) ON DELETE CASCADE,
  field_id TEXT REFERENCES record_fields(id) ON DELETE SET NULL,
  analyte_id TEXT,
  analyte_method TEXT,
  raw_name TEXT NOT NULL,
  value_num REAL,
  value_text TEXT NOT NULL,
  unit TEXT,
  canonical_value REAL,
  canonical_unit TEXT,
  ref_low REAL,
  ref_high REAL,
  ref_text TEXT,
  flag TEXT NOT NULL DEFAULT 'unknown',
  observed_date TEXT,
  observed_date_method TEXT,
  method TEXT NOT NULL,
  confidence REAL NOT NULL DEFAULT 0,
  state TEXT NOT NULL DEFAULT 'suggested',
  source_page INTEGER,
  source_bbox TEXT,
  evidence TEXT,
  excluded_from_trends INTEGER NOT NULL DEFAULT 0,
  created_ms INTEGER NOT NULL,
  updated_ms INTEGER NOT NULL);
CREATE INDEX idx_observations_trend ON observations(analyte_id, observed_date);
CREATE INDEX idx_observations_record ON observations(record_id);
CREATE INDEX idx_observations_field ON observations(field_id);

CREATE TABLE analyte_user_aliases (
  normalized_name TEXT PRIMARY KEY NOT NULL,
  analyte_id TEXT NOT NULL,
  created_ms INTEGER NOT NULL);

CREATE TABLE entities (
  id TEXT PRIMARY KEY NOT NULL,
  kind TEXT NOT NULL,
  display_name TEXT NOT NULL,
  normalized_name TEXT NOT NULL,
  specialty TEXT,
  created_ms INTEGER NOT NULL,
  updated_ms INTEGER NOT NULL);
CREATE UNIQUE INDEX idx_entities_kind_name ON entities(kind, normalized_name);

CREATE TABLE record_entities (
  record_id TEXT NOT NULL REFERENCES records(id) ON DELETE CASCADE,
  entity_id TEXT NOT NULL REFERENCES entities(id) ON DELETE CASCADE,
  role TEXT NOT NULL,
  PRIMARY KEY (record_id, entity_id, role));
CREATE INDEX idx_record_entities_entity ON record_entities(entity_id, role);

CREATE TABLE record_links (
  a_id TEXT NOT NULL REFERENCES records(id) ON DELETE CASCADE,
  b_id TEXT NOT NULL REFERENCES records(id) ON DELETE CASCADE,
  kind TEXT NOT NULL,
  origin TEXT NOT NULL,
  status TEXT NOT NULL,
  score REAL NOT NULL DEFAULT 0,
  reasons_json TEXT,
  created_ms INTEGER NOT NULL,
  updated_ms INTEGER NOT NULL,
  PRIMARY KEY (a_id, b_id));
CREATE INDEX idx_record_links_b ON record_links(b_id);
