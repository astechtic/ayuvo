package com.ayuvo.health.partner.data

/**
 * Verbatim `shared/partner/schema.sql` (v1), comments stripped, one statement per entry, split exactly like the
 * records rule (scripts/records_contract_check.py `statements`). PartnerSchemaContractTest compares these with
 * the shared file; change the shared file first (docs/partner-sync.md §6). `shared/partner/migrations/` is empty.
 */
object PartnerSchema {
    const val SCHEMA_VERSION = 1

    val STATEMENTS: List<String> = listOf(
        "CREATE TABLE partner_meta (key TEXT PRIMARY KEY NOT NULL, value TEXT)",
        """CREATE TABLE partners (
  owner_id TEXT PRIMARY KEY NOT NULL,
  display_name TEXT NOT NULL,
  fingerprint TEXT NOT NULL,
  x25519_pub TEXT NOT NULL,
  ed25519_pub TEXT NOT NULL,
  platform TEXT,
  paired_ms INTEGER NOT NULL,
  unpaired_ms INTEGER,
  last_host TEXT, last_port INTEGER,
  updated_ms INTEGER NOT NULL)""",
        """CREATE TABLE partner_grants_out (
  owner_id TEXT NOT NULL REFERENCES partners(owner_id) ON DELETE CASCADE,
  category TEXT NOT NULL,
  granted INTEGER NOT NULL DEFAULT 0,
  updated_ms INTEGER NOT NULL,
  PRIMARY KEY (owner_id, category))""",
        """CREATE TABLE partner_grants_received (
  owner_id TEXT NOT NULL REFERENCES partners(owner_id) ON DELETE CASCADE,
  category TEXT NOT NULL,
  granted INTEGER NOT NULL DEFAULT 0,
  revoked_ms INTEGER,
  updated_ms INTEGER NOT NULL,
  PRIMARY KEY (owner_id, category))""",
        """CREATE TABLE partner_records (
  owner_id TEXT NOT NULL REFERENCES partners(owner_id) ON DELETE CASCADE,
  type TEXT NOT NULL,
  record_id TEXT NOT NULL,
  category TEXT NOT NULL,
  rev INTEGER NOT NULL,
  day TEXT,
  ts_ms INTEGER,
  updated_ms INTEGER NOT NULL,
  data_json TEXT NOT NULL,
  PRIMARY KEY (owner_id, type, record_id))""",
        "CREATE INDEX idx_pr_owner_type_day ON partner_records(owner_id, type, day)",
        "CREATE INDEX idx_pr_owner_type_ts ON partner_records(owner_id, type, ts_ms)",
        "CREATE INDEX idx_pr_owner_category ON partner_records(owner_id, category)",
        """CREATE TABLE partner_sync_state (
  owner_id TEXT PRIMARY KEY NOT NULL REFERENCES partners(owner_id) ON DELETE CASCADE,
  last_rev INTEGER NOT NULL DEFAULT 0,
  acked_rev INTEGER NOT NULL DEFAULT 0,
  last_sync_ms INTEGER, last_attempt_ms INTEGER,
  last_transport TEXT,
  status TEXT NOT NULL DEFAULT 'pairing_required',
  last_error TEXT,
  last_export_id TEXT)""",
        """CREATE TABLE outbound_ledger (
  type TEXT NOT NULL,
  record_id TEXT NOT NULL,
  category TEXT NOT NULL,
  day TEXT,
  content_hash TEXT NOT NULL,
  rev INTEGER NOT NULL,
  deleted INTEGER NOT NULL DEFAULT 0,
  PRIMARY KEY (type, record_id))""",
        "CREATE INDEX idx_ol_rev ON outbound_ledger(rev)",
        "CREATE INDEX idx_ol_type_day ON outbound_ledger(type, day)"
    )

    val SQL: String get() = STATEMENTS.joinToString(";\n", postfix = ";\n")

    val TABLES: List<String> = listOf(
        "partner_meta", "partners", "partner_grants_out", "partner_grants_received", "partner_records",
        "partner_sync_state", "outbound_ledger"
    )

    val INDEXES: List<String> = listOf(
        "idx_pr_owner_type_day", "idx_pr_owner_type_ts", "idx_pr_owner_category", "idx_ol_rev", "idx_ol_type_day"
    )

    /** Shared migration files (none in v1). */
    val MIGRATION_FILES: List<String> = emptyList()
}
