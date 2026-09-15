# `ayuvo-health-data` export format, version 1

The platform-neutral archive that moves the Health Data hub's local mirror between devices and between Android and iOS. It is the **only** way health data leaves a device other than through Coach; the cloud backups never contain it (`docs/health-data.md` §8).

Related contract: `docs/health-data.md` (slugs, units, codes), `shared/health/metric_registry.json`, `shared/health/schema.sql`.

## 1. Container

- A zip file, suggested name `ayuvo-health-data-<yyyyMMdd-HHmm>.zip`, MIME `application/zip`.
- Entries appear in **this order** so a single-pass reader can show a preview before reading the bulk data:
  1. `manifest.json`
  2. `samples.ndjson`
  3. `series.ndjson` (optional)
  4. `sources.json`
  5. `rollups.ndjson` (optional)
  6. `checksums.json`
- Text is UTF-8, `\n` line endings, one JSON object per line in `.ndjson` entries, no BOM.
- `manifest.json` is **hash-free** (it is written before the bulk entries are known) — integrity lives in `checksums.json`.

## 2. `manifest.json`

```json
{
  "format": "ayuvo-health-data",
  "format_version": 1,
  "platform": "android",
  "app_version": "3.2.0 (412)",
  "exported_at": "2026-03-04T07:15:30.000+05:30",
  "zone_id": "Asia/Kolkata",
  "date_range": {"from": "2019-06-01", "to": "2026-03-04"},
  "registry_version": 1,
  "percent_convention": "0-100",
  "types": [
    {"type": "steps", "category": "activity", "kind": "cumulative", "aggregation": "SUM", "unit": "count",
     "display_name": "Steps", "native_id": null, "record_count": 18234, "series_count": 0}
  ]
}
```

| Field | Meaning |
|---|---|
| `format` | always `ayuvo-health-data` |
| `format_version` | `1`; readers reject anything greater (fail closed) |
| `platform` | `android` \| `ios` |
| `app_version` | free text (version name + build) |
| `exported_at` | ISO-8601 with offset and ms |
| `zone_id` | IANA zone of the exporting device (informational; every timestamp carries its own offset) |
| `date_range` | `from`/`to` local days (`yyyy-MM-dd`) of the oldest and newest non-deleted sample, `null` when empty |
| `registry_version` | the exporter's registry version |
| `percent_convention` | always `0-100` |
| `types[]` | one entry per `type_id` present in `samples.ndjson`: `type`, `category`, `kind`, `aggregation`, `unit`, `display_name`, `native_id` (raw platform id for runtime `other` types, else `null`), `record_count`, `series_count` |

## 3. `samples.ndjson`

One line per `health_samples` row. Rows with `deleted = 1` are **omitted**; rows of `nutrition` and every `dietary_*` type are **omitted** (`exported: false` in the registry — the food-diary export already covers intake; `hydration` **is** exported).

```json
{"id":"7c2a…","type_id":"heart_rate","start":"2026-03-04T06:58:00.000+05:30","end":"2026-03-04T07:03:00.000+05:30","updated":"2026-03-04T07:05:12.331+05:30","value":71.5,"value2":64,"value3":88,"value_text":null,"unit":"count/min","category_value":null,"title":null,"extra":null,"count":30,"source_id":"com.google.android.apps.fitness","source_name":"Fitbit","device":"Pixel Watch 3","device_type":2,"recording_method":1,"client_record_id":null,"origin":0}
```

| Column (`health_samples`) | Field | Conversion |
|---|---|---|
| `id` | `id` | as is |
| `type_id` | `type_id` | as is (slug or raw native id) |
| `start_ms` + `start_offset_s` | `start` | ISO-8601 `yyyy-MM-dd'T'HH:mm:ss.SSSXXX` in the row's own offset (device zone at export when `NULL`) |
| `end_ms` + `end_offset_s` | `end` | likewise |
| `updated_ms` | `updated` | ISO-8601 in the same offset as `start` |
| `value`, `value2`, `value3` | same | numbers or `null` |
| `value_text` | `value_text` | string or `null` |
| `unit` | `unit` | canonical unit string |
| `category_value` | `category_value` | integer or `null` |
| `title` | `title` | string or `null` |
| `extra_json` | `extra` | parsed JSON **object** or `null` (never a string) |
| `count` | `count` | integer ≥ 1 |
| `source_id` | `source_id` | package name / bundle id |
| — (`health_sources.name`) | `source_name` | denormalized for readers without `sources.json`, may be `null` |
| `device`, `device_type`, `recording_method`, `client_record_id` | same | as is / `null` |
| `origin` | `origin` | `0` platform, `1` file import, `2` local adapter — the **exporter's** value; the importer overwrites it (§6) |
| `local_day` | — | not exported; recomputed on import from `start`/`end` and the type's `day_attribution` |
| `deleted` | — | not exported (deleted rows are skipped) |

Line order: by `type_id`, then `end` ascending. Every field is present on every line (`null` when empty) so line parsers can be strict.

## 4. `series.ndjson` (optional)

Present only when the exporter has `health_series_points` rows (Android). One line per point: `{"s": "<sample_id>", "t": <epoch ms>, "v": <value>}`, ordered by `s`, then `t`. Points whose parent sample is not in `samples.ndjson` are invalid. iOS exports no series entry and ignores series on import (points are re-derivable from the platform, so nothing is lost).

## 5. `sources.json`

Array of `health_sources` rows referenced by the samples:

```json
[{"id": "com.google.android.apps.fitness", "name": "Fitbit", "device_model": "Pixel Watch 3", "device_type": 2, "last_seen": "2026-03-04T07:03:00.000+05:30"}]
```

## 6. `rollups.ndjson` (optional)

One line per `health_daily_rollups` row for the exported types (same field names as the columns, `day` as `yyyy-MM-dd`). Written to let a reader preview totals without recomputing; **importers never trust it** — rollups are rebuilt for every touched type after import.

## 7. `checksums.json`

`{"<entry name>": "<lowercase hex sha256>"}` for every entry except itself, e.g. `{"manifest.json": "…", "samples.ndjson": "…", "sources.json": "…"}`. A mismatch on import is a **warning** shown in the preview ("This file may be damaged"), not a rejection.

## 8. Import rules (both platforms)

1. Source: Android `ActivityResultContracts.OpenDocument` (`application/zip`), iOS `fileImporter` (`.zip`). Copy to a temp file before reading.
2. Caps: archive ≤ **512 MB** (compressed size, after reading the central directory / on stream), any single line ≤ **64 KB**; exceeding either aborts with a clear error.
3. Read `manifest.json` first. Reject when `format != "ayuvo-health-data"` or `format_version > 1` ("This file was made by a newer version of Ayuvo") — fail closed exactly like `DiaryImporter` / `CloudBackupArchive`.
4. Preview before writing: platform, app version, exported date, date range, per-type counts, unknown types (slugs not in the local registry), unit mismatches (§8.7), checksum status, estimated rows.
5. Modes:
   - **Merge** — upsert by `id` with the UPDATE-then-INSERT rule; a row with a newer `updated` wins; a local **tombstone always wins** (a deleted row is never resurrected); nothing is deleted; imported rows get `origin = 1` and are immune to platform deletions; `client_record_id`/`device` are copied as is.
   - **Replace all** — after a confirmation, delete every `health_samples`/`health_series_points`/`health_daily_rollups`/`health_hourly_rollups`/`health_sources` row and the `health_type_meta` rows for imported types, then insert. Sync cursors are dropped so the next sync re-seeds from the platform.
6. Unknown slugs are accepted: a `health_type_meta` row is created from the manifest `types[]` entry with `category = "other"` (`platform` = manifest platform, `native_id` from the manifest) so the rows are visible in the hub and exportable again.
7. `unit` must equal the type's canonical unit (registry, or `health_type_meta.unit` for runtime types): mismatching lines are **rejected and counted** (shown in the result), never auto-converted.
8. `nutrition` and `dietary_*` lines, if present in a foreign file, are skipped and counted.
9. `local_day` is recomputed from `start`/`end` + `day_attribution` in the row's own offset. Zone offsets are preserved in `start_offset_s`/`end_offset_s`.
10. `series.ndjson`: Android inserts points whose parent row was inserted or updated by this import and is within the 365-day series retention; iOS ignores the entry.
11. `sources.json` rows are upserted by `id` (`last_seen` max-merged).
12. Every transaction is bounded (≤ 5 000 lines) so a cancelled import leaves a consistent database; the result screen shows inserted / updated / skipped-older / skipped-tombstoned / rejected-unit / unknown-type counts.
13. After the write: rebuild rollups for every touched type (`HealthRollupMath` full rebuild), register the types in the hub, then trigger a sync (`IMPORT_COMPLETED`) so platform rows take over where they exist.
14. Fixtures: an Android-produced zip is committed to the iOS test target (`calorietrackerTests/Fixtures/health/`) and an iOS-produced zip to the Android test resources before Phase 4 closes; both importers must accept both.

## 9. Platform notes

- **Android** writes with `java.util.zip.ZipOutputStream` (DEFLATE entries, one entry at a time, streamed from cursors — never the whole table in memory) and reads with `ZipInputStream` in entry order; the `ZipEntry` size caps are enforced on the counted stream. DTOs use kotlinx.serialization (existing `**$$serializer` keep rules; no Gson, no new R8 rules).
- **iOS** writes **stored** (method 0) entries from a temp file per entry with CRC-32 (`Utilities/CRC32.swift`) and sizes known up front, so no deflate implementation is needed; it reads through a **central-directory** parser (`Services/HealthData/Export/ZipArchive.swift`) that supports methods 0 and 8 (via `Compression`'s zlib raw deflate) **and** data descriptors (bit 3), which is what Android's `ZipOutputStream` emits. The same reader replaces `CloudBackupZip.unpack`, fixing Android → iOS cloud restore as a side effect.
- Both platforms enforce the line cap while streaming, refuse entries outside the six names above, and refuse path separators in entry names.
- Export runs off the main thread (Android `Dispatchers.IO`; iOS `Task.detached(priority: .utility)`), writes to the cache directory, then hands the file to SAF `CreateDocument` / Share via `FileProvider` (Android) or `UIActivityViewController` (iOS, temp file deleted in `completionWithItemsHandler`).
