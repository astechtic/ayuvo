import Foundation

/// Embedded copy of `shared/partner/schema.sql` (schema v1), verbatim. `PartnerSchemaTests` requires the text
/// to equal the shared file byte for byte; statements are split with the records/medications rule
/// (`MedicationsSchema.parseStatementsStrict`: comments stripped, one statement per `;` line end).
/// `shared/partner/migrations/` is empty, so there are no migrations yet.
nonisolated enum PartnerSchema {
    static let schemaVersion = 1

    static let tableNames: [String] = [
        "partner_meta", "partners", "partner_grants_out", "partner_grants_received", "partner_records",
        "partner_sync_state", "outbound_ledger",
    ]

    static let indexNames: [String] = [
        "idx_pr_owner_type_day", "idx_pr_owner_type_ts", "idx_pr_owner_category", "idx_ol_rev", "idx_ol_type_day",
    ]

    static var statements: [String] { MedicationsSchema.parseStatements(sql) }

    static let sql = #"""
-- Ayuvo Partner Health Sync — SQLite schema v1 (contract: docs/partner-sync.md §6).
--
-- A separate database; the user's own health, medications and records databases are never altered.
--   Android: partner/data/PartnerSchema.kt (SQLiteOpenHelper, ayuvo_partner.db)
--   iOS:     Partner/Data/PartnerSchema.swift (sqlite3, Application Support/Ayuvo/Partner/partner.sqlite)
-- Both embed this file verbatim and a parity test compares PRAGMA table_info with it.
-- Excluded from every backup (Auto Backup, iCloud, ayuvo-backup.zip, Export All Data).
-- SQLite 3.18 compatible (Android minSdk 26): upserts are UPDATE-then-INSERT, no ON CONFLICT DO UPDATE.
-- Connection settings: journal_mode=WAL, synchronous=NORMAL, busy_timeout=5000, foreign_keys=ON.

CREATE TABLE partner_meta (key TEXT PRIMARY KEY NOT NULL, value TEXT);  -- schema_version, rev (my outbound revision counter), device_id

CREATE TABLE partners (
  owner_id TEXT PRIMARY KEY NOT NULL,      -- the partner's device_id (lowercase UUID)
  display_name TEXT NOT NULL,
  fingerprint TEXT NOT NULL,               -- hex, 32 chars (§3)
  x25519_pub TEXT NOT NULL,                -- base64url, 32 bytes
  ed25519_pub TEXT NOT NULL,               -- base64url, 32 bytes
  platform TEXT,                           -- android|ios
  paired_ms INTEGER NOT NULL,
  unpaired_ms INTEGER,                     -- NULL while trusted; set on unpair (data kept, trust gone)
  last_host TEXT, last_port INTEGER,       -- last address that completed a session (direct-dial fallback)
  updated_ms INTEGER NOT NULL);

CREATE TABLE partner_grants_out (          -- what I share with this partner
  owner_id TEXT NOT NULL REFERENCES partners(owner_id) ON DELETE CASCADE,
  category TEXT NOT NULL,
  granted INTEGER NOT NULL DEFAULT 0,
  updated_ms INTEGER NOT NULL,
  PRIMARY KEY (owner_id, category));

CREATE TABLE partner_grants_received (     -- what this partner shares with me (from HELLO / package manifest)
  owner_id TEXT NOT NULL REFERENCES partners(owner_id) ON DELETE CASCADE,
  category TEXT NOT NULL,
  granted INTEGER NOT NULL DEFAULT 0,
  revoked_ms INTEGER,                      -- set when a previously granted category disappears; data is kept
  updated_ms INTEGER NOT NULL,
  PRIMARY KEY (owner_id, category));

CREATE TABLE partner_records (             -- received data, read-only on this device
  owner_id TEXT NOT NULL REFERENCES partners(owner_id) ON DELETE CASCADE,
  type TEXT NOT NULL,                      -- record type (record_types.json)
  record_id TEXT NOT NULL,
  category TEXT NOT NULL,
  rev INTEGER NOT NULL,                    -- sender revision; higher wins
  day TEXT,                                -- yyyy-MM-dd (sender local day) when the type has one
  ts_ms INTEGER,                           -- primary instant (sample start, entry time, ...)
  updated_ms INTEGER NOT NULL,             -- sender's updatedMs
  data_json TEXT NOT NULL,
  PRIMARY KEY (owner_id, type, record_id));
CREATE INDEX idx_pr_owner_type_day ON partner_records(owner_id, type, day);
CREATE INDEX idx_pr_owner_type_ts ON partner_records(owner_id, type, ts_ms);
CREATE INDEX idx_pr_owner_category ON partner_records(owner_id, category);

CREATE TABLE partner_sync_state (
  owner_id TEXT PRIMARY KEY NOT NULL REFERENCES partners(owner_id) ON DELETE CASCADE,
  last_rev INTEGER NOT NULL DEFAULT 0,     -- their revision I have committed (my cursor into their ledger)
  acked_rev INTEGER NOT NULL DEFAULT 0,    -- my revision they have acknowledged (default package delta start)
  last_sync_ms INTEGER, last_attempt_ms INTEGER,
  last_transport TEXT,                     -- network|package
  status TEXT NOT NULL DEFAULT 'pairing_required',
  last_error TEXT,
  last_export_id TEXT);                    -- last imported package exportId

CREATE TABLE outbound_ledger (             -- my shareable records, one row per (type, record_id)
  type TEXT NOT NULL,
  record_id TEXT NOT NULL,
  category TEXT NOT NULL,
  day TEXT,
  content_hash TEXT NOT NULL,              -- device-local hash of the rendered data (never compared across devices)
  rev INTEGER NOT NULL,
  deleted INTEGER NOT NULL DEFAULT 0,
  PRIMARY KEY (type, record_id));
CREATE INDEX idx_ol_rev ON outbound_ledger(rev);
CREATE INDEX idx_ol_type_day ON outbound_ledger(type, day);

"""#
}
