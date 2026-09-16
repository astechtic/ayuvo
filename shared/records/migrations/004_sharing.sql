-- Health Records schema v4 — Phase 5 "Sharing & backup" (contract: docs/health-records.md §33–§37).
-- Runs once, in one transaction, when user_version < 4. Embedded VERBATIM on both platforms using the
-- §8 statement-splitting rule, with parity tests, exactly like schema.sql and the earlier migrations.

ALTER TABLE records ADD COLUMN shared_count INTEGER NOT NULL DEFAULT 0;
ALTER TABLE records ADD COLUMN last_shared_ms INTEGER;

CREATE TABLE records_backup_state (
  key TEXT PRIMARY KEY NOT NULL,
  value TEXT NOT NULL);
