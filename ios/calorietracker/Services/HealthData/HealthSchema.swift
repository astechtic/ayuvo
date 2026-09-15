import Foundation

/// Embedded copy of `shared/health/schema.sql`. `HealthDatabaseTests` compares the
/// tables this DDL creates against the shared file (`PRAGMA table_info`), so the two
/// must stay identical column-for-column.
nonisolated enum HealthSchema {
    static let schemaVersion = 1
    static let registryVersion = 1
    static let rollupRuleVersion = 1

    static let tableNames: [String] = [
        "health_samples", "health_series_points", "health_daily_rollups", "health_hourly_rollups",
        "health_sources", "health_sync_state", "health_type_meta", "health_meta",
    ]

    static let indexNames: [String] = ["idx_hs_type_end", "idx_hs_type_start", "idx_hs_type_day", "idx_hsp_type_t"]

    static let ddl = """
    CREATE TABLE health_samples (
      id TEXT PRIMARY KEY NOT NULL,        -- HC Metadata.id | HK uuid (lowercase; HK sleep stages keep their own uuid) | "<session_id>:<n>" HC stage rows | "local:<uuid>" adapter rows
      type_id TEXT NOT NULL,               -- registry slug or raw native id
      start_ms INTEGER NOT NULL, end_ms INTEGER NOT NULL,
      start_offset_s INTEGER, end_offset_s INTEGER,       -- zone offsets as the platform gave them (NULL → device zone at sync)
      local_day TEXT NOT NULL,             -- yyyy-MM-dd per day_attribution (sleep = end/wake day)
      value REAL, value2 REAL, value3 REAL, value_text TEXT,
      unit TEXT NOT NULL, category_value INTEGER, title TEXT, extra_json TEXT,
      count INTEGER NOT NULL DEFAULT 1,    -- samples condensed into this row
      source_id TEXT NOT NULL,             -- package name | bundle id
      device TEXT, device_type INTEGER, recording_method INTEGER, client_record_id TEXT,
      origin INTEGER NOT NULL DEFAULT 0,   -- 0 platform, 1 file import, 2 local app adapter
      deleted INTEGER NOT NULL DEFAULT 0,  -- tombstone; always wins over imports
      updated_ms INTEGER NOT NULL);        -- platform lastModified; newer wins
    CREATE INDEX idx_hs_type_end   ON health_samples(type_id, end_ms DESC);
    CREATE INDEX idx_hs_type_start ON health_samples(type_id, start_ms);
    CREATE INDEX idx_hs_type_day   ON health_samples(type_id, local_day);
    CREATE TABLE health_series_points (   -- intra-record samples (HC HeartRate/Speed/Power/Cadence/SkinTemperature); empty on iOS
      sample_id TEXT NOT NULL REFERENCES health_samples(id) ON DELETE CASCADE,
      type_id TEXT NOT NULL, t_ms INTEGER NOT NULL, value REAL NOT NULL, PRIMARY KEY (sample_id, t_ms));
    CREATE INDEX idx_hsp_type_t ON health_series_points(type_id, t_ms);
    CREATE TABLE health_daily_rollups (
      type_id TEXT NOT NULL, day TEXT NOT NULL, tz TEXT NOT NULL,
      sum REAL, avg REAL, min REAL, max REAL, count INTEGER NOT NULL DEFAULT 0,
      last_value REAL, last_at_ms INTEGER, v2_avg REAL, v2_min REAL, v2_max REAL,
      duration_s REAL, own_sum REAL,       -- own_sum: Ayuvo's own tagged active_energy for that day
      from_platform_aggregate INTEGER NOT NULL DEFAULT 0, PRIMARY KEY (type_id, day));
    CREATE TABLE health_hourly_rollups (type_id TEXT NOT NULL, day TEXT NOT NULL, hour INTEGER NOT NULL,
      sum REAL, avg REAL, min REAL, max REAL, count INTEGER NOT NULL DEFAULT 0, PRIMARY KEY (type_id, day, hour));
    CREATE TABLE health_sources (id TEXT PRIMARY KEY NOT NULL, name TEXT NOT NULL, device_model TEXT, device_type INTEGER, last_seen_ms INTEGER);
    CREATE TABLE health_sync_state (
      type_id TEXT PRIMARY KEY NOT NULL,
      cursor TEXT, cursor_issued_ms INTEGER, last_sync_ms INTEGER,             -- HC changes token | base64 HKQueryAnchor
      earliest_authorized_ms INTEGER,                                          -- iOS 27 limited grant boundary
      earliest_probe_ms INTEGER, backfill_floor_ms INTEGER, oldest_backfilled_ms INTEGER,
      backfill_done INTEGER NOT NULL DEFAULT 0, backfill_with_history INTEGER NOT NULL DEFAULT 0,
      status TEXT NOT NULL DEFAULT 'idle',                                     -- idle|bootstrapping|importing|syncing|limited|locked|error:<code>
      last_error TEXT, last_error_ms INTEGER, ipc_calls_total INTEGER NOT NULL DEFAULT 0);
    CREATE TABLE health_type_meta (type_id TEXT PRIMARY KEY NOT NULL, category TEXT NOT NULL, kind TEXT NOT NULL,
      aggregation TEXT NOT NULL, unit TEXT NOT NULL, display_name TEXT, platform TEXT, native_id TEXT);  -- unknown/imported types
    CREATE TABLE health_meta (key TEXT PRIMARY KEY NOT NULL, value TEXT);      -- registry_version, rollup_rule_version, rollups_tz, schema_version
    """

    /// The same DDL made re-runnable (`IF NOT EXISTS`) for `applySchema()`.
    static var idempotentDDL: String {
        ddl
            .replacingOccurrences(of: "CREATE TABLE ", with: "CREATE TABLE IF NOT EXISTS ")
            .replacingOccurrences(of: "CREATE INDEX ", with: "CREATE INDEX IF NOT EXISTS ")
    }
}
