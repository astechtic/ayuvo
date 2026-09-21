# Health Data hub — shared contract

This page is the cross-platform contract for the **Health Data** hub: the local mirror of Health Connect (Android) and HealthKit (iOS) that powers Summary › Favourites, Browse and the metric detail screens, the Coach health tools and the `ayuvo-health-data` export. Android (`android/…/models/HealthDataType.kt`, `data/health/`) and iOS (`ios/…/Models/Health/HealthMetricType.swift`, `Services/HealthData/`) implement it independently; the only defenses against drift are the parity tests that read the two shared files and the rules written here. Change the shared files first, then both platforms in the same PR.

Files:

| File | Purpose | Consumed by |
|---|---|---|
| `shared/health/metric_registry.json` | Every known data type (slug, category, kind, units, platform identifiers, permissions, codes) | `HealthDataTypeContractTest.kt`, `HealthMetricRegistryTests.swift` (via `#filePath`) |
| `shared/health/schema.sql` | SQLite DDL, embedded verbatim on both platforms | `SchemaParityTest` (Android instrumented), `HealthDatabaseTests` (iOS) |
| `docs/health-data.md` | This page: conventions, rollup/sleep rules, Coach tools, privacy, accessibility ids | humans, reviewers |
| `docs/health-data-export.md` | The `ayuvo-health-data` v1 zip format | exporter/importer on both platforms |

## 1. Registry conventions

`registry_version` is bumped whenever an entry is added, removed or its `kind`/`aggregation`/`unit`/`day_attribution`/`category_codes` change; the value is stored in `health_meta.registry_version` and in every export manifest. Renaming a slug is not allowed (rows and exports carry slugs); add a new slug and keep the old one as `status: "reserved"` if it must go away.

Fields per entry (all keys present on every entry; `null` when not applicable):

| Field | Meaning |
|---|---|
| `id` | snake_case slug. Stored in `health_samples.type_id`, used as the Coach `data_type` key and the export `type_id`. Never renamed. |
| `display_name` | English display name. Platforms localize by literal key (`health_type_<id>` on Android, the literal entry in `Localizable.xcstrings` on iOS). |
| `category` | One of the 13 categories below. |
| `kind` | `cumulative` \| `discrete` \| `duration` \| `category` \| `session` \| `series` — decides the value layout and rollup rule (§4). |
| `aggregation` | `SUM` \| `DURATION` \| `AVERAGE` \| `MIN_MAX` \| `LATEST` \| `COUNT` — the headline statistic shown in the hub and used by Coach `highlights`. |
| `unit` | Canonical display/storage/export unit string (§1.2). Every row of the type stores exactly this string in `health_samples.unit`. |
| `hk_unit` | The exact HealthKit unit spelling used to read the quantity (`HKUnit(from:)` parseable), or a Swift constructor expression when no string exists (only `blood_glucose`: `HKUnit.moleUnit(with: .milli, molarMass: HKUnitMolarMassBloodGlucose).unitDivided(by: .liter())`, whose `unitString` is `mmol<180.1558800000541>/L`, displayed as `mmol/L`). `null` for category kinds (no HKUnit) and for the Android-only pseudo-units `days`/`none`. Strings are frozen after the Phase 0 simulator run (`hk_units_verified` flips to `true`). |
| `day_attribution` | `start` (local day of `start_ms`), `end` (local day of `end_ms`; sleep = wake day) or `span` (one rollup row per covered local day). |
| `hk_identifiers` | Raw HealthKit identifier strings mapped to this slug (`HKQuantityTypeIdentifier…`, `HKCategoryTypeIdentifier…`, `HKCorrelationTypeIdentifierBloodPressure`, `HKWorkoutTypeIdentifier`, `HKStateOfMindTypeIdentifier`, `HKScoredAssessmentTypeIdentifier…`, `HKDataTypeIdentifier…`). Empty for Android-only types. iOS resolves each string through the optional `HKObjectType` factories; a string the running SDK does not know is skipped, never crashes. |
| `hk_identifiers_verified` | `false` while an identifier spelling still has to be confirmed against the SDK on a simulator (iOS 26/27 additions). Phase 0 exit gate: all `true` or the entry is `reserved`. |
| `hc_record` | Health Connect record class simple name (`StepsRecord`), or `null`. |
| `hc_permission` | Full permission string (`android.permission.health.READ_STEPS`), or `null`. Types without their own permission ride on another one: `cycling_cadence` → `READ_EXERCISE`, `step_cadence` → `READ_STEPS`, `menstruation_period`/`menstrual_flow` → `READ_MENSTRUATION`, `mindfulness_session` → `android.permission.health.READ_MINDFULNESS` (string, not the constant name). |
| `hc_feature_flag` | `HealthConnectFeatures` constant name (`FEATURE_SKIN_TEMPERATURE`, `FEATURE_PLANNED_EXERCISE`, `FEATURE_MINDFULNESS_SESSION`) that must report available before the permission is requested, or `null`. Use the constants, never the numbers. |
| `android_tier` | `CORE` (first sheet + onboarding), `EXTENDED` (per-category Allow rows) or `null` (not requested on Android). |
| `sdk_available` | `true` when `hc_record` exists in `connect-client` 1.1.0. `false` for iOS-only entries and for `activity_intensity` (1.2.0-alpha). `HealthPermissionsManifestTest` iterates entries with `sdk_available == true`. |
| `derived_from` | Android only: `"nutrition"` on the `dietary_*` entries — they are rollup-only virtual types computed from `nutrition` rows' `extra_json`, never stored as rows. `null` elsewhere. |
| `status` | `active` or `reserved` (known slug, not synced in v1: `activity_intensity`, `activity_summary`, `electrocardiogram`, `heartbeat_series`, `audiogram`). Reserved entries are excluded from permission requests, hub lists and parity tests that touch the platform SDK. |
| `exported` | `false` only for `nutrition` and `dietary_*` (the food-diary export already covers intake); everything else is written to and accepted from `ayuvo-health-data` archives. |
| `category_codes` | Object `{"<canonical code>": "<label>"}` for kinds that fill `category_value`; `null` otherwise. Codes are canonical (platform values are mapped on ingest, §5) except `workout` (platform-native activity code, label in `extra_json.activity_type`) and `symptom_*` (raw HealthKit values — no Android counterpart). |
| `notes` | Free-text value layout notes for humans. |

Top-level fields: `registry_version` (1), `percent_convention` (`"0-100"`), `hk_units_verified` (Phase 0 flag), `hk_authorization_only_identifiers` (the blood-pressure quantity types that must be in the HealthKit read set but are never synced as rows), `categories`, `kinds`, `aggregations`, `day_attributions`, `units`, `metrics`.

### 1.1 Categories (Apple Health order)

`activity, body, cycle_tracking, hearing, heart, mental_wellbeing, mobility, nutrition, respiratory, sleep, symptoms, vitals, other`. Android hides a category that has no registry type with `sdk_available == true` **and** no stored rows (hearing, mobility, symptoms on a device that never imported an iOS export). iOS shows all 13 (empty ones dimmed, last).

### 1.2 Units and value conventions

Canonical `unit` strings: `count, m, kcal, kcal/d, count/min, ms, %, degC, mmHg, mmol/L, kg, s, L, mL, g, mg, mcg, m/s, W, dBASPL, mL/min·kg, kcal/hr·kg, count/hr, IU, L/min, mcS, days, none`. `days` and `none` are Android-only pseudo-units (also used for dimensionless category rows such as `state_of_mind` valence) and are exempt from the HealthKit parse/compatibility tests.

- **Percent is stored 0–100** on both platforms (iOS multiplies HealthKit's 0–1 fraction by 100 on ingest, divides on write-back).
- **Durations are seconds** (`s`), including time-quantity types (`exercise_minutes`, `stand_minutes`, `move_minutes`, `time_in_daylight`) — the minute value is converted on ingest.
- Distances `m`, mass `kg`, energy `kcal`, temperature `degC`, glucose `mmol/L` (the `healthGlucoseUnit` preference only changes display).
- Unknown native types (a HealthKit identifier or Health Connect record the registry does not know) are stored under their **raw native id** as `type_id`, with category `other`, the first compatible canonical unit (iOS: `quantity.is(compatibleWith:)`; else `value = NULL` and `value_text` = the platform's description), and are registered at runtime in `health_type_meta` (`platform`, `native_id`). They are exported and imported like any other type.
- Numbers in tool payloads and exports are JSON numbers rounded to at most 3 decimals; times are ISO-8601 with zone offset and milliseconds (`2026-03-04T07:15:30.000+05:30`); days are `yyyy-MM-dd`.

### 1.3 Value layout per kind

| kind | `value` | `value2` / `value3` | `count` | other columns |
|---|---|---|---|---|
| `cumulative` | quantity total of the row (HK `sumQuantity`) | — | condensed samples | — |
| `discrete` | reading, or the average when `count > 1` (HK `averageQuantity`) | min / max when `count > 1` | condensed samples | — |
| `series` | average over the record's inner samples (HC HeartRate/Speed/Power/Cadence/SkinTemperature) | min / max | number of inner samples | inner samples in `health_series_points` (Android only, ≤365 days) |
| `duration` | seconds (end − start for sessions and category-backed durations; the quantity in seconds for time-quantity types) | — | 1 | `category_value` optional (e.g. mindfulness type) |
| `session` | seconds (end − start) | type-specific | 1 | `category_value`, `title`, `extra_json` |
| `category` | `NULL` unless the type carries a scalar (`state_of_mind` valence, assessment score) | — | 1 | `category_value` (canonical code), `value_text` (label) |

Type-specific layouts:

- `workout` — `value` = duration s, `category_value` = platform activity code, `title` = platform title/name, `extra_json` = `{activity_type, energy_kcal, distance_m, segments, laps, has_route, platform}`. Codes are platform-native (`ExerciseSessionRecord.exerciseType` vs `HKWorkoutActivityType.rawValue`); readers must use `extra_json.activity_type` for the label.
- `blood_pressure` — `value` = systolic, `value2` = diastolic (mmHg), `category_value` = body position (§5), `extra_json.measurement_location` (HC). One row per HC record / HK correlation.
- `blood_glucose` — `value` mmol/L, `category_value` = specimen source (§5), `extra_json = {relation_to_meal, meal_type}` (HC) / `{meal_time}` (HK `HKMetadataKeyBloodGlucoseMealTime`).
- `skin_temperature` — `value` = average delta °C, `value2`/`value3` min/max delta, `extra_json.baseline_c`, series points = deltas.
- `heart_rate` (Android) — `value` avg, `value2` min, `value3` max, `count` = samples, points in `health_series_points`. iOS rows are single samples (`count = 1`).
- `nutrition` (Android) — `value` = kcal, `value2` = protein g, `value3` = carbohydrates g, `category_value` = meal type (§5), `title` = name, `extra_json` = every non-null nutrient keyed by the **dietary slug** in that slug's canonical unit (`{"dietary_fat_total": 12.4, "dietary_vitamin_b12": 1.1, …}`); the virtual `dietary_*` rollups are computed from these keys.
- `menstruation_period` — `value` = span in days, `day_attribution = span`.
- `state_of_mind` — `value` = valence (−1…1, unit `none`), `category_value` = kind (§5), `extra_json = {labels: [...], associations: [...], valence_classification}`.
- `assessment_gad7` / `assessment_phq9` — `value` = score, `category_value` = risk code, `extra_json.answers`.
- `insulin_delivery` — `value` IU, `category_value` = reason (1 basal, 2 bolus).
- `symptom_*` — `category_value` = raw HealthKit value, `value_text` = label from `category_codes`.

## 2. Storage

DDL: `shared/health/schema.sql` (embedded verbatim; do not restate it here). Tables: `health_samples`, `health_series_points`, `health_daily_rollups`, `health_hourly_rollups`, `health_sources`, `health_sync_state`, `health_type_meta`, `health_meta`; indexes `idx_hs_type_end`, `idx_hs_type_start`, `idx_hs_type_day`, `idx_hsp_type_t`.

- Android: `ayuvo_health.db` via framework `SQLiteOpenHelper` (no Room). iOS: `Application Support/Ayuvo/Health/health.sqlite` via a thin `sqlite3` wrapper (no GRDB/SwiftData), opened with `SQLITE_OPEN_FULLMUTEX | SQLITE_OPEN_FILEPROTECTION_COMPLETEUNTILFIRSTUSERAUTHENTICATION`.
- Pragmas on both: `journal_mode=WAL`, `synchronous=NORMAL`, `busy_timeout=5000`, `foreign_keys=ON`.
- `health_meta` keys: `schema_version` (1), `registry_version`, `rollup_rule_version` (1), `rollups_tz` (IANA zone the rollups were built in; a change triggers a full rebuild).
- Row ids: HC `Metadata.id`; HK `uuid.uuidString.lowercased()`; HC sleep stage rows `<record_id>:<n>`; local adapter rows `local:<uuid>`; iOS activity summaries `activity_summary:yyyy-MM-dd`.
- `origin`: `0` platform, `1` file import, `2` local app adapter (Ayuvo's own weight/body-fat/height entries; `source_id` = own package/bundle id, label "Ayuvo").
- Show All Data pages by keyset `(end_ms DESC, id DESC)`, never `OFFSET`.

### 2.1 Upsert and tombstone rules (both platforms)

1. `UPDATE health_samples SET … WHERE id = ? AND updated_ms < ? AND deleted = 0;` then, when no row with that id exists, `INSERT`. (SQLite 3.18 on Android minSdk 26 has no `ON CONFLICT DO UPDATE`.) A row with an equal or newer `updated_ms` is left untouched.
2. Child series points are rewritten only when the parent row changed (delete by `sample_id`, re-insert).
3. Platform deletions (`DeletionChange` / `HKDeletedObject` / weekly reconciliation) set `deleted = 1, updated_ms = now` — only on `origin = 0` rows. Platform rows are never physically deleted; "Clear synced health data" deletes the whole database file(s).
4. A tombstone always wins: an import or a re-read never resurrects a `deleted = 1` row (the UPDATE predicate `deleted = 0` plus an INSERT guarded by existence).
5. Imported rows (`origin = 1`) are immune to platform deletions and never carry a platform cursor; the sync engine only touches `origin = 0`.
6. Each page of platform results is committed with its cursor (HC changes token / archived `HKQueryAnchor`) and `last_sync_ms` in **one** transaction; `last_sync_ms` is never stamped on failure.

## 3. Sync state

`health_sync_state.status` ∈ `idle | bootstrapping | importing | syncing | limited | locked | error:<code>`. `cursor` = HC changes token or base64 `HKQueryAnchor`. `earliest_authorized_ms` = the iOS 27 limited-grant boundary ("past 30 days"); `backfill_floor_ms` = the oldest instant the platform lets us read (Android without `READ_HEALTH_DATA_HISTORY`: 30 days before the first grant; with it: the earliest record probe, clamped to 10 years). Hub copy for a floor: "History before <date> isn't available · Grant history access" (Android) / "History before <date> isn't shared with Ayuvo — Health › Sharing › Apps › Ayuvo" (iOS).

## 4. Rollup rules (`HealthRollupMath`, identical on both, property-tested "incremental == full rebuild")

- **Day** = local day of `start_ms` using the row's own offset (`start_offset_s`, else the device zone at sync). `day_attribution = end` types (`sleep`, `sleeping_wrist_temperature`, `sleeping_breathing_disturbances`) use `end_ms`/`end_offset_s`. `span` types (`menstruation_period`, `pregnancy`, `lactation`) contribute one rollup row per covered local day (`count = 1`, `duration_s` = overlap seconds).
- Rows with `deleted = 1` are ignored. `tz` on the rollup row is the zone the day was computed in.
- **cumulative**: `sum = Σ value`, `count = Σ count`, `avg = sum / count`, `min`/`max` over `value`, `last_value`/`last_at_ms` = the row with the greatest `end_ms`.
- **discrete / series**: `avg` = Σ(value × count) / Σ count (weighted by `count`); `min`/`max` from `value2`/`value3` **only** when `kind ∈ {discrete, series}` and the row's `count > 1` (condensed samples), otherwise from `value`; `last_value` as above. `blood_pressure` is the only type filling `v2_avg/v2_min/v2_max` (diastolic).
- **duration / session**: `sum = duration_s = Σ value` (seconds), `count` = rows.
- **category**: `count` = rows, `last_value` = `category_value` of the latest row (`value` when the type carries a scalar).
- **nutrition** (Android): cumulative on `value` (kcal) plus one virtual `dietary_<key>` rollup per `extra_json` key (`sum`, `count`, `avg`).
- Android SUM types (`steps`, `distance`, `active_energy`, `total_energy`, `floors_climbed`, `elevation_gained`, `hydration`, `wheelchair_pushes`, `workout` duration) take the day total from Health Connect `aggregateGroupByPeriod(Period.ofDays(1))` (priority-deduplicated across phone + watch) and mark `from_platform_aggregate = 1`. Everything else is computed locally on both platforms; `mindfulness_session` is always local (the 1.1.0 aggregate is broken). iOS computes everything locally.
- Today and yesterday are re-aggregated on every sync; hourly cache rows for dirty days are deleted before re-aggregation. Hourly rollups exist for SUM types (D chart) only.
- **`own_sum`** (only `active_energy`): the day's active energy written by Ayuvo's own workout sync. Android: a second aggregate with an own-package `DataOrigin` filter per ≤92-day window. iOS: `SUM(value)` over rows with `source_id = <own bundle id> AND extra_json LIKE '%ayuvo_workout_session_id%'`. `active_energy` rollups **include** these rows so hub totals match the Health app; consumers (Coach, Home burn line, GoalEvidence) subtract `own_sum` instead of adding Ayuvo burn on top. After a cross-platform import a consumer must match **both** own ids (`com.apoorvdarshan.calorietracker` bundle id and the Android package name).

## 5. Category code mappings

Canonical codes are stored in `category_value`; the platform's raw value is mapped on ingest (and mapped back for display only). Labels come from `category_codes` in the registry.

**Sleep (`sleep`)** — canonical `0 in_bed, 1 asleep_unspecified, 2 awake, 3 light, 4 deep, 5 rem, 6 out_of_bed` (6 is excluded from both in-bed and asleep time).

| Health Connect `SleepSessionRecord.Stage.stage` | canonical | HealthKit `HKCategoryValueSleepAnalysis` | canonical |
|---|---|---|---|
| `STAGE_TYPE_UNKNOWN` | 1 | `inBed` (0) | 0 |
| `STAGE_TYPE_AWAKE`, `STAGE_TYPE_AWAKE_IN_BED` | 2 | `asleepUnspecified` (1) / legacy `asleep` | 1 |
| `STAGE_TYPE_SLEEPING` | 1 | `awake` (2) | 2 |
| `STAGE_TYPE_OUT_OF_BED` | 6 | `asleepCore` (3) | 3 |
| `STAGE_TYPE_LIGHT` | 3 | `asleepDeep` (4) | 4 |
| `STAGE_TYPE_DEEP` | 4 | `asleepREM` (5) | 5 |
| `STAGE_TYPE_REM` | 5 | — | — |

- Android: one `SleepSessionRecord` → one **session row** (`id` = record id, `category_value = 0`, `value` = session seconds, `title`, `extra_json.notes`) + one row per stage (`id = <record_id>:<n>`, `extra_json.session_id = <record_id>`, `value` = stage seconds). A session **without stages** additionally gets a single code-1 row `<record_id>:0` spanning the whole session, so the night counts entirely as asleep.
- iOS: each `HKCategorySample` keeps its own uuid; no synthetic ids and no `session_id` — nights are grouped at read time.
- Nights are derived on read by the shared `HealthSleepAnalysis`: group rows by wake day (`local_day`, `day_attribution = end`); per wake day pick the **source with the longest asleep time** (codes 1, 3, 4, 5), union overlapping intervals within that source, ignore the other sources; `in_bed_s` = union of codes 0–5, `asleep_s` = union of codes 1, 3, 4, 5, per-stage seconds from the unions. The daily rollup `sum` = `asleep_s`. Never use Health Connect's calendar-day `SLEEP_DURATION_TOTAL`.

**Menstrual flow (`menstrual_flow`)** — canonical (= HealthKit) `1 unspecified, 2 light, 3 medium, 4 heavy, 5 none`. Health Connect: `FLOW_UNKNOWN → 1`, `FLOW_LIGHT → 2`, `FLOW_MEDIUM → 3`, `FLOW_HEAVY → 4`.

**Ovulation test (`ovulation_test`)** — canonical (= HealthKit) `1 negative, 2 positive (LH surge), 3 indeterminate, 4 estrogen_surge`. Health Connect: `RESULT_INCONCLUSIVE → 3`, `RESULT_POSITIVE → 2`, `RESULT_HIGH → 4`, `RESULT_NEGATIVE → 1`.

**Cervical mucus (`cervical_mucus`)** — canonical `0 unknown, 1 dry, 2 sticky, 3 creamy, 4 watery, 5 egg_white, 6 unusual`. HealthKit values 1–5 map 1:1; Health Connect appearance values map 1:1 (`APPEARANCE_UNKNOWN → 0`, `APPEARANCE_UNUSUAL → 6`), HC `sensation` → `extra_json.sensation` (`light|medium|heavy`).

**Sexual activity (`sexual_activity`)** — canonical `0 unknown, 1 protected, 2 unprotected`. Health Connect `protectionUsed` maps 1:1; HealthKit `HKMetadataKeySexualActivityProtectionUsed` `true → 1`, `false → 2`, absent → 0.

**Blood pressure body position (`blood_pressure`)** — canonical (= Health Connect) `0 unknown, 1 standing_up, 2 sitting_down, 3 lying_down, 4 reclining`; HealthKit rows are always 0.

**Blood glucose specimen source (`blood_glucose`)** — canonical (= Health Connect) `0 unknown, 1 interstitial_fluid, 2 capillary_blood, 3 plasma, 4 serum, 5 tears, 6 whole_blood`; HealthKit rows are 0.

**Mindfulness type (`mindfulness_session`)** — canonical (= Health Connect) `0 unknown, 1 meditation, 2 other, 3 breathing, 4 music, 5 movement, 6 unguided`; HealthKit rows are 0.

**Meal type (`nutrition`)** — `0 unknown, 1 breakfast, 2 lunch, 3 dinner, 4 snack` (Health Connect `MealType`).

**Events** (`intermenstrual_bleeding`, `high_heart_rate_event`, `low_heart_rate_event`, `irregular_heart_rhythm_event`, `toothbrushing_event`, `handwashing_event`, `pregnancy`, `lactation`, …) — `0 occurred`.

**Symptoms** — raw HealthKit values: `HKCategoryValueSeverity` `0 unspecified, 1 not_present, 2 mild, 3 moderate, 4 severe`; `HKCategoryValuePresence` (`symptom_sleep_changes`, `symptom_mood_changes`) `0 present, 1 not_present`; `HKCategoryValueAppetiteChanges` (`symptom_appetite_changes`) `0 unspecified, 1 no_change, 2 decreased, 3 increased`.

## 6. Blood pressure on iOS (correlation rule)

`blood_pressure` is synced as the `HKCorrelation` (`HKAnchoredObjectQueryDescriptor` with `HKSamplePredicate.correlation(type:predicate:)`): row id = correlation uuid, `value`/`value2` from `correlation.objects(for: systolicType/diastolicType)`, tombstone by correlation uuid. `HKQuantityTypeIdentifierBloodPressureSystolic` and `…Diastolic` stay in the **authorization** read set (correlation types cannot be authorized; see `hk_authorization_only_identifiers` in the registry) but are **not** synced as separate rows and never appear in the hub. Android stores one row per `BloodPressureRecord` with the same layout, so both platforms produce identical `blood_pressure` rows for export.

## 7. Coach tool contract

Tool names, descriptions, JSON schemas and payload shapes are **byte-identical** on both platforms (Android `CoachTools.kt` / `CoachHealthData.kt`, iOS `CoachTools.swift` / `CoachHealthQuery.swift`); the tests `CoachHealthToolsTest` / `CoachHealthToolsTests` assert the strings below.

### 7.1 Gating and consent

- Tools are advertised only when health sync is enabled (`healthHubEnabled` on Android, `healthKitEnabled` on iOS) **and** `coachHealthDataEnabled` is `true`.
- `coachHealthDataEnabled` defaults to on but is set only by an **affirmative act**: every connect step (onboarding, the Browse connect card, Settings › Data & Privacy › Health Sync) shows a visible, pre-checked toggle "Let Coach use my health data (sent to your AI provider / your AI provider)" that the user confirms with Connect; `coachHealthDataConsentedAt` (ISO-8601) is stored at that moment. Legacy Android users and iOS v9→v10 re-prompt users pass through that screen before the flag is set — never a silent flip. The toggle also lives in Settings › Data & Privacy › Health Sync.
- When gated off, the tools are absent from the advertised list and the system prompt contains: `No health data is available: the user has not connected a health platform, or has turned off Coach access to it.`
- Tool payloads are never persisted into chat history; only the visible assistant text is. `coachChatHistory` is excluded from cloud backups on both platforms (§8).

### 7.2 Tools

**`get_health_data_types`**

Description (exact):

```
List the health data types synced from the phone's health platform (steps, heart rate, sleep, blood pressure, ...) with record counts, units, and earliest/latest dates. Call this first before any other health tool, and to learn the exact data_type keys.
```

Input schema (exact):

```json
{"type":"object","properties":{}}
```

Payload:

```json
{
  "health_data_enabled": true,
  "last_sync": "2026-03-04T07:15:30.000+05:30",
  "count": 14,
  "data_types": [
    {
      "data_type": "steps",
      "category": "activity",
      "display_name": "Steps",
      "unit": "count",
      "aggregation": "SUM",
      "count": 18234,
      "first": "2019-06-01T00:00:00.000+05:30",
      "last": "2026-03-04T06:58:00.000+05:30",
      "latest": {"at": "2026-03-04T06:58:00.000+05:30", "value": 412, "value_text": null},
      "history_limited_before": "2026-02-02"
    }
  ]
}
```

Rules: registry types with at least one non-deleted row, plus runtime `other` types; `dietary_*` and `nutrition` are listed (so the model knows they exist) but the guardrail sends intake questions to the diary tools; `history_limited_before` is present only when `backfill_floor_ms`/`earliest_authorized_ms` is set for the type; `last_sync` is the max `last_sync_ms` over types, `null` when never synced. Sorted by category order, then `display_name`.

**`get_health_summary`**

Description (exact):

```
Daily statistics for one health data type between two dates (inclusive): per-day sum, average, min, max and record count in the type's unit, plus range highlights. Use for questions like "how did I sleep this week?", "average resting heart rate in March", or step trends. data_type must be a key returned by get_health_data_types.
```

Input schema (exact):

```json
{"type":"object","properties":{"data_type":{"type":"string","description":"A data_type key returned by get_health_data_types, e.g. steps, heart_rate, sleep."},"from":{"type":"string","description":"Inclusive start date, ISO yyyy-MM-dd."},"to":{"type":"string","description":"Inclusive end date, ISO yyyy-MM-dd."},"limit":{"type":"integer","description":"Maximum number of daily buckets to return, most recent first (cap 400)."}},"required":["data_type","from","to"]}
```

Payload:

```json
{
  "data_type": "resting_heart_rate",
  "unit": "count/min",
  "from": "2026-03-01",
  "to": "2026-03-07",
  "highlights": {"total": null, "average": 58.4, "min": 55, "max": 62, "latest": 57},
  "days": [
    {"date": "2026-03-07", "sum": null, "avg": 57, "min": 57, "max": 57, "count": 1,
     "duration_s": null, "v2_avg": null, "v2_min": null, "v2_max": null, "own_sum": null}
  ]
}
```

Rules: `days` come from `health_daily_rollups` (`sum = duration_s` for `duration`/`session` kinds; `sleep` uses `HealthSleepAnalysis` nights so `sum` = asleep seconds), most recent first, truncated to `limit` (omitted → 400); days without data are omitted. `highlights`: `total` = Σ sum (SUM/DURATION types, else `null`), `average` = count-weighted mean of `avg` (`null` for SUM types where it is Σ/days instead — use `total`/days), `min`/`max` over the day rows, `latest` = `last_value` of the most recent day. Optional keys (`duration_s`, `v2_*`, `own_sum`) are emitted as `null` when not applicable, never omitted.

**`get_health_samples`**

Description (exact):

```
Individual health records (start/end time, value, source app and device) for one data type between two dates (inclusive). Use only when per-reading detail matters, e.g. a specific blood-pressure reading or a workout. Prefer get_health_summary for trends.
```

Input schema (exact):

```json
{"type":"object","properties":{"data_type":{"type":"string","description":"A data_type key returned by get_health_data_types, e.g. blood_pressure, workout."},"from":{"type":"string","description":"Inclusive start date, ISO yyyy-MM-dd."},"to":{"type":"string","description":"Inclusive end date, ISO yyyy-MM-dd."},"limit":{"type":"integer","description":"Maximum number of records to return, newest first (cap 200)."}},"required":["data_type","from","to"]}
```

Payload:

```json
{
  "data_type": "blood_pressure",
  "unit": "mmHg",
  "count": 2,
  "records": [
    {"start": "2026-03-04T07:10:00.000+05:30", "end": "2026-03-04T07:10:00.000+05:30",
     "value": 121, "value2": 79, "value3": null, "value_text": null, "category_value": 2,
     "title": null, "extra": null, "source": "Omron connect", "device": "HEM-7361T"}
  ]
}
```

Rules: non-deleted rows whose `[start_ms, end_ms]` intersects the local range, newest `end_ms` first, `limit` omitted → 200; `count` is the number returned; `source` is the `health_sources.name` (fallback: `source_id`); `extra` is the parsed `extra_json` object or `null`.

**`get_sleep_history`**

Description (exact):

```
Per-night sleep sessions between two dates (inclusive): bedtime, wake time, time in bed, time asleep and light/deep/REM/awake durations in seconds, with the source device. Use for questions about sleep duration, quality or consistency.
```

Input schema (exact):

```json
{"type":"object","properties":{"from":{"type":"string","description":"Inclusive start date (wake day), ISO yyyy-MM-dd."},"to":{"type":"string","description":"Inclusive end date (wake day), ISO yyyy-MM-dd."},"limit":{"type":"integer","description":"Maximum number of nights to return, most recent first (cap 120)."}},"required":["from","to"]}
```

Payload:

```json
{
  "from": "2026-03-01",
  "to": "2026-03-07",
  "count": 1,
  "nights": [
    {"night_of": "2026-03-07", "start": "2026-03-06T23:04:00.000+05:30", "end": "2026-03-07T06:51:00.000+05:30",
     "in_bed_s": 28020, "asleep_s": 26100, "light_s": 14400, "deep_s": 5400, "rem_s": 6300, "awake_s": 1920,
     "source": "Apple Watch"}
  ]
}
```

Rules: `night_of` is the wake day; nights per §5 (`HealthSleepAnalysis`); most recent first; `limit` omitted → 120; stage seconds are `0` (not `null`) when the source recorded no stages, in which case `light_s = 0` and `asleep_s` = the unspecified-asleep total.

**Errors** (all tools): `{"error": "<message>"}` — `unknown data_type '<x>'; call get_health_data_types` · `from must be on or before to` · `invalid date '<x>' (expected yyyy-MM-dd)` · `health data is not available`.

### 7.3 Prompt lines and guardrails (exact text)

`## Data available` gains one line: `<N> health data types synced from Health Connect, last synced <relative time>.` (Android) / `<N> health data types synced from Apple Health, last synced <relative time>.` (iOS), followed by:

```
Health data guardrails:
- Platform active energy already includes workouts logged in Ayuvo. Never add Ayuvo workout burn on top of it; use own_sum from get_health_summary to separate the two.
- Use the diary tools (get_calorie_totals, get_food_entries) for food intake, not the dietary_* or nutrition health types.
- Never diagnose from heart rate, blood pressure, glucose or other readings. Describe patterns and suggest talking to a clinician about anything concerning.
```

On-device / LiteRT modes (no tools) append a `## Health (last 7 days)` block of at most 12 lines, one per type with data, in category order: `- <display_name>: <headline stat> <unit> (<n> days)` where the headline is `total` for SUM/DURATION, `average` otherwise, plus `- Sleep: avg <h>h <m>m asleep over <n> nights`.

## 8. Privacy and backup rules

- The health database is **never** part of any cloud archive. Android `backup_rules.xml` (`<full-backup-content>`) and `data_extraction_rules.xml` (`<cloud-backup>` **and** `<device-transfer>`) exclude `ayuvo_health.db`, `ayuvo_health.db-wal`, `ayuvo_health.db-shm` and `ayuvo_health.db-journal` (`<exclude domain="database" …/>`). iOS sets `isExcludedFromBackup = true` on `Application Support/Ayuvo/Health/` and opens the file with complete-until-first-authentication protection. Neither the Drive nor the CloudKit archive touches it (`CloudBackupPolicy.VERSION`/`version` stays 1). Cross-platform or cross-device movement is by health export/import only (`docs/health-data-export.md`).
- **Cloud-backed preference keys** (same names on both platforms, listed in `docs/cloud-backup.md` › Reserved preference keys): `healthHomeTiles` (JSON array of slugs), `healthGlucoseUnit` (`mmol/L` \| `mg/dL`; iOS: overrides the HealthKit preferred unit when set), `coachHealthDataEnabled` (bool), `coachHealthDataConsentedAt` (ISO-8601). Android only: `healthHubEnabled` (iOS gates on the existing `healthKitEnabled`).
- **Device-local keys** (excluded from cloud backup): iOS `healthKitHubPromptedVersion`, `healthKitHubLastSyncAt`, `healthKitHubRateLimitedUntil`, `healthKitBackgroundDeliveryVersion` (auto-excluded by the `healthKit` prefix in `CloudBackupPolicy.include`); Android `healthHubPromptedVersion`, `healthHubLastSyncAt`, `healthHubRateLimitedUntil` (added to `CloudBackupPolicy.excludedKeys`).
- `coachChatHistory` is added to `CloudBackupPolicy.excludedKeys` on both platforms in the Coach PR (App Store 5.1.3(ii)); a backup test asserts it.
- **No health values in UserDefaults/DataStore, ever** — snapshots for Summary and Browse live in memory only; backup tests assert that no key holds numeric samples.
- Restore onto a new device re-checks authorization (`getRequestStatusForAuthorization` / `capabilitiesOrNull`) and shows "Grant access", never "Nothing shared yet"; throttle keys are reset on restore.
- Copy never claims data "never leaves the device"; it says "kept on this device, never stored in iCloud/Drive backup, shared with your AI provider only through Coach".

## 9. Accessibility identifiers

Same strings on both platforms (`Modifier.testTag`/`semantics { testTag }` on Android, `.accessibilityIdentifier` on iOS).

| Surface | Identifier |
|---|---|
| Hub list / scroll container | `health.hub.list` |
| Hub refresh button | `health.hub.refresh` |
| Hub status line (live region) | `health.hub.status` |
| Hub import progress bar | `health.hub.progress` |
| Category card / row | `health.hub.category.<category>` |
| Data type row | `health.hub.type.<id>` |
| Per-category Allow row (Android) | `health.hub.allow.<category>` |
| Connect / Grant access CTA | `health.hub.connect` |
| Coach consent toggle on the connect step | `health.hub.coachConsent` |
| Summary favourite tile (was Home tile) | `summary.favourite.<id>` |
| Summary favourites "Edit" (was Home strip "See All") | `summary.favourites.edit` |
| Detail range segment | `metric.range.<D\|W\|M\|6M\|Y>` |
| Detail previous / next anchor | `metric.prev`, `metric.next` |
| Detail chart | `metric.chart` |
| Show All Data list | `metric.allData` |
| Data Sources & Access | `metric.sources` |
| Unit picker | `metric.unit` |
| Add to Favourites toggle (was Show on Home) | `metric.favourite` |
| Settings › Health Sync › Let Coach use health data | `settings.health.coachToggle` |
| Settings › Health Sync › Sync Now | `settings.health.syncNow` |
| Settings › Delete All Data › Clear synced health data | `settings.health.clearData` |
| Settings › Backup & Export › Export Health Data | `settings.health.export` |
| Settings › Backup & Export › Import Health Data | `settings.health.import` |

The metric detail and Summary identifiers are shared with app metrics and defined in `docs/ui-structure.md` §9. The former `health.home.*`, `health.detail.*`, `settings.health.hub` and `settings.health.homeTile` identifiers are retired: Browse replaces the "All Health Data" row and the one-time hub CTA row (Browse's Health Sync footer opens Settings › Health Sync), and Summary › Favourites › Edit replaces the iOS home-tile toggle.

**Favourites storage.** Pinned tiles move from `healthHomeTiles` to `summaryFavourites` (comma-separated keys, max 12; health ids unprefixed, app metrics `app:<name>`), migrated once by the rule in `docs/ui-structure.md` §7.9. `healthHomeTiles` is no longer written. Its real on-disk format was a comma-separated string on Android and a string array on iOS (not the JSON array described in §8); the migration accepts both by joining the iOS array with ",".

## 10. Versioning summary

| Constant | Value | Where |
|---|---|---|
| `registry_version` | 1 | registry JSON, `health_meta`, export manifest |
| `schema_version` | 1 | `health_meta` |
| `rollup_rule_version` | 1 | `health_meta` (bump → full rollup rebuild) |
| `percent_convention` | `0-100` | registry JSON, export manifest |
| Export `format` / `format_version` | `ayuvo-health-data` / 1 | `docs/health-data-export.md` |
| Android `HUB_PERMISSIONS_VERSION` | 1 | `healthHubPromptedVersion` |
| iOS `typesVersion` | 10 | `healthKitTypesVersion` (excluded from backup) |
