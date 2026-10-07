-- Ayuvo Health Data hub — SQLite schema (contract version: see docs/health-data.md).
--
-- This file is embedded VERBATIM on both platforms:
--   Android: HealthDatabase.kt (SQLiteOpenHelper, ayuvo_health.db)
--   iOS:     Services/HealthData/HealthSchema.swift (sqlite3, Application Support/Ayuvo/Health/health.sqlite)
-- Each platform has a parity test that compares `PRAGMA table_info` of a freshly created
-- database against this file. Change this file first; then both mirrors in the same PR.
--
-- Compatibility: Android minSdk 26 ships SQLite 3.18, which has no `INSERT … ON CONFLICT DO UPDATE`.
-- Upserts on both platforms are therefore UPDATE-then-INSERT:
--   UPDATE health_samples SET … WHERE id = ? AND updated_ms < ? AND deleted = 0;
--   INSERT INTO health_samples … when no row with that id exists.
-- Deletions are tombstones (deleted = 1); platform rows are never physically deleted.
-- Connection settings on both platforms: journal_mode=WAL, synchronous=NORMAL, busy_timeout=5000, foreign_keys=ON.

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
  origin INTEGER NOT NULL DEFAULT 0,   -- 0 platform, 1 file import, 2 local app adapter, 3 Google Health API
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
CREATE TABLE derived_daily_values (   -- on-device derived metrics (docs/derived-metrics.md); never exported, rebuilt on demand
  metric_id TEXT NOT NULL, day TEXT NOT NULL,
  value REAL, value2 REAL, value3 REAL,
  quality REAL, source_kind TEXT NOT NULL DEFAULT 'derived',   -- 'derived' only; native readings stay in health_samples
  algo_version INTEGER NOT NULL, computed_ms INTEGER NOT NULL, PRIMARY KEY (metric_id, day));
CREATE TABLE google_health_sync_state (   -- v3: per Google Health API data type (shared/health/google_health_map.json gh_type)
  gh_type TEXT PRIMARY KEY NOT NULL,
  cursor_ms INTEGER,                   -- newest point end seen; next sync starts at cursor_ms - overlap_days
  page_token TEXT,                     -- non-NULL only while a paged fetch is interrupted mid-way
  last_sync_ms INTEGER, backfill_floor_ms INTEGER,
  status TEXT NOT NULL DEFAULT 'idle', -- idle|syncing|unsupported|error:scope|error:<code>
  last_error TEXT, last_error_ms INTEGER);
CREATE TABLE google_health_mirror (       -- v3: write-back of origin-3 rows to HealthKit / Health Connect
  sample_id TEXT PRIMARY KEY NOT NULL REFERENCES health_samples(id) ON DELETE CASCADE,
  platform_id TEXT,                    -- HK uuid | HC Metadata.id once written
  mirror_status TEXT NOT NULL DEFAULT 'pending',  -- pending|mirrored|skipped_dup|unsupported|disabled|error
  mirrored_ms INTEGER, attempts INTEGER NOT NULL DEFAULT 0, last_error TEXT);
CREATE INDEX idx_ghm_status ON google_health_mirror(mirror_status);
CREATE TABLE vital_scans (                -- v4: camera finger PPG / face rPPG scans (docs/camera-vitals.md); never written to Health
  id TEXT PRIMARY KEY NOT NULL,            -- local:<uuid>
  mode TEXT NOT NULL,                      -- finger_ppg|face_rppg
  session_id TEXT,                         -- shared by a finger + face compare pair
  start_ms INTEGER NOT NULL, end_ms INTEGER NOT NULL, tz_offset_s INTEGER NOT NULL, local_day TEXT NOT NULL,
  duration_ms INTEGER NOT NULL, platform TEXT NOT NULL, device_model TEXT NOT NULL,
  camera_json TEXT NOT NULL,               -- lens, position, resolution, target/achieved fps, exposure, ISO, WB, torch
  context TEXT NOT NULL DEFAULT 'resting', -- resting|after_activity|other
  quality_score REAL, reject_reason TEXT,
  quality_json TEXT NOT NULL,              -- engine quality object (components kept)
  results_json TEXT NOT NULL,              -- {metric_id: envelope} plus ibi arrays and indicators
  algo_version INTEGER NOT NULL,
  reference_json TEXT,                     -- user-entered reference readings (chest strap / ECG / oximeter / cuff)
  deleted INTEGER NOT NULL DEFAULT 0, updated_ms INTEGER NOT NULL);
CREATE INDEX idx_vs_mode_start ON vital_scans(mode, start_ms);
CREATE INDEX idx_vs_day ON vital_scans(local_day);
CREATE TABLE vital_scan_signals (         -- v4: per-scan signals for reprocessing; no images, no video
  scan_id TEXT NOT NULL REFERENCES vital_scans(id) ON DELETE CASCADE,
  kind TEXT NOT NULL,                      -- frame_stats|processed|mask|beats
  sample_rate REAL, encoding TEXT NOT NULL,-- f32le+deflate (row-major, meta_json.columns)
  data BLOB NOT NULL, meta_json TEXT, PRIMARY KEY (scan_id, kind));
CREATE TABLE vital_calibrations (         -- v4: personal SpO2 / BP calibration pairs (experimental / research)
  id TEXT PRIMARY KEY NOT NULL, kind TEXT NOT NULL,     -- spo2|bp
  device_model TEXT NOT NULL, scan_id TEXT, t_ms INTEGER NOT NULL,
  reference_json TEXT NOT NULL,            -- {"spo2": 98} | {"sbp": 118, "dbp": 76, "scan_gap_min": 2}
  features_json TEXT NOT NULL,             -- {"ratio": 0.66} | bp_features
  deleted INTEGER NOT NULL DEFAULT 0, updated_ms INTEGER NOT NULL);
CREATE TABLE vital_device_profiles (      -- v4: camera capability profile per device model and camera position
  device_model TEXT NOT NULL, camera_position TEXT NOT NULL,   -- back|front
  capability_json TEXT NOT NULL, updated_ms INTEGER NOT NULL, PRIMARY KEY (device_model, camera_position));
CREATE TABLE analytics_results (          -- v5: health analytics (docs/health-analytics.md); never exported, recomputable
  metric_id TEXT NOT NULL,                 -- recovery_indicator|anomaly|hrv_status|sleep_status|load|hrr|trend:<metric>|correlation|forecast:<target>|energy|met_week|vo2max_trend|hrv_rr
  period_start TEXT NOT NULL, period_end TEXT NOT NULL,   -- yyyy-MM-dd local days
  algorithm_id TEXT NOT NULL, algorithm_version INTEGER NOT NULL, config_version INTEGER NOT NULL,
  status TEXT NOT NULL, classification TEXT NOT NULL,
  value REAL, value2 REAL, value3 REAL, unit TEXT,
  confidence REAL, coverage REAL, input_count INTEGER, baseline_window_days INTEGER,
  result_json TEXT NOT NULL,               -- the full reference-shaped result
  provenance_json TEXT NOT NULL,           -- {algorithm, config_version, inputs, sources, sample_count, window, coverage, fallbacks}
  input_hash TEXT NOT NULL, computed_ms INTEGER NOT NULL,
  PRIMARY KEY (metric_id, period_start, algorithm_version));
CREATE INDEX idx_ar_metric_end ON analytics_results(metric_id, period_end);
CREATE TABLE analytics_state (            -- v5: incremental processing bookkeeping
  metric_id TEXT PRIMARY KEY NOT NULL, algorithm_version INTEGER NOT NULL, config_version INTEGER NOT NULL,
  last_processed_day TEXT, updated_ms INTEGER NOT NULL);
CREATE TABLE ml_models (                  -- v5: per-user forecast models (shared/analytics forecast); never exported
  model_id TEXT NOT NULL, model_version INTEGER NOT NULL,   -- model_version increments on every retrain
  algorithm_version INTEGER NOT NULL, target TEXT NOT NULL, feature_schema_version INTEGER NOT NULL,
  train_start TEXT, train_end TEXT, val_start TEXT, val_end TEXT, test_start TEXT, test_end TEXT,
  lambda REAL, coefficients_json TEXT NOT NULL, normalization_json TEXT NOT NULL,
  metrics_json TEXT NOT NULL, baseline_metrics_json TEXT NOT NULL,
  deployed INTEGER NOT NULL DEFAULT 0, created_ms INTEGER NOT NULL, PRIMARY KEY (model_id, model_version));
