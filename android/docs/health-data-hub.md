# Health Data hub (Android)

**Health** tab → **Health Data** segment (the tab's other segment is Progress) → per-type detail; Home's Health strip and its "See All" land on the same segment. Every data type Health Connect
offers is mirrored, with all readable history, into a local SQLite database
(`ayuvo_health.db`, framework `SQLiteOpenHelper`, WAL). The database is excluded from Android
Auto Backup / device transfer (`res/xml/backup_rules.xml`, `data_extraction_rules.xml`) and
never enters the Drive backup; only preferences travel (`healthHubEnabled`,
`coachHealthDataEnabled`, `coachHealthDataConsentedAt`, `healthHomeTiles`, `healthGlucoseUnit`).
Device-local cursors/throttles (`healthHubPromptedVersion`, `healthHubLastSyncAt`,
`healthHubRateLimitedUntil`) and `coachChatHistory` are in `CloudBackupPolicy.excludedKeys`.

The registry (`models/HealthDataType.kt`), DDL (`data/health/HealthDatabase.kt`), rollup and
sleep rules are the Android mirror of `shared/health/metric_registry.json`,
`shared/health/schema.sql` and `docs/health-data.md`; `HealthDataTypeContractTest` and
`HealthSchemaContractTest` compare them when the shared files are present.

## Architecture

```
HealthConnectClient → HealthConnectManager (hub permission sets, capabilitiesOrNull, featureAvailable)
                       └ HealthConnectReadSource : HealthReadSource   (only hub file using the client; maps records via HealthRecordMapper)
AppContainer.requestHealthSync(trigger) [AppContainer.scope] → legacy syncHealthConnectReads() → HealthSyncEngine.sync(trigger, grants)
                                                                    └ HealthDataStore seam → SqliteHealthDataStore | InMemoryHealthDataStore (tests)
HomeViewModel.healthStrip · HealthHubViewModel · HealthTypeDetailViewModel · CoachHealthData · HealthDataExporter/Importer
```

* `HealthSyncEngine` is pure Kotlin (no android.* imports, injected clock/log) and owns the run:
  gates (hub enabled, rate-limit back-off, APP_OPEN 3 min / MANUAL 30 s throttles, single
  permission probe), initial 30-day window per newly granted type with the changes token taken
  **before** the first read (page + token committed atomically), incremental `getChanges` drain
  (expired token → re-token + overlap re-read), all-history backfill (round-robin 30-day chunks
  per type, probe-bounded, gap-jumping) when History is granted, daily/hourly statistics
  (platform aggregates for SUM types with `own_sum` for active energy; local math otherwise),
  series pruning (365 d) and Ayuvo's own weight/body-fat/height as `origin=2` rows.
* Without `READ_HEALTH_DATA_HISTORY` the readable window ends 30 days before the first grant;
  the first boundary error is recorded as `backfill_floor_ms` and the hub shows
  "History before <date> isn't available · Grant history access".
* Quota errors back off 45 minutes with a calm subtitle; the IPC budget (150 app-open / 600
  interactive) bounds every run and returns `moreWork` so the hub keeps syncing while visible.
* Revoking a type keeps its rows (read-only) and clears its cursor. An empty grant set shows
  "Permissions were reset or restored from another device" with a re-grant CTA. Turning the
  toggle off keeps the data; "Clear synced health data" / Delete All Data remove it.
* "Manage Health Connect Access" goes through `HealthConnectManager.openManageAccess(context)`:
  the app-specific `MANAGE_HEALTH_PERMISSIONS` screen is refused (SecurityException) for
  third-party callers on the platform controller, so it falls through to the Health Connect home
  (`HEALTH_HOME_SETTINGS` on 14+, the standalone app's settings before), the manage-data intent
  and finally this app's system settings page; a toast reports when nothing could open.
* Hub rows for cumulative / duration types show the latest day's total (today when present),
  not the last 10-minute chunk. The detail "Show All Data" list reveals five records at a time
  (keyset paged, formatted in the view model); source labels prefer the installed app name,
  then the platform/export source name, then the package id.

## States to verify (manual checklist)

1. Fresh install → Home shows the **Connect Health Connect** tile → hub connect card with the
   pre-checked Coach consent toggle → Connect → Health Connect sheet (CORE types + History).
2. Partial grant → only granted categories populate; other categories show "Allow … access".
3. Pull-to-refresh spinner always clears (outcome returned even when skipped/rate-limited).
4. Every granted type has a cursor after one run (`health_sync_state`).
5. Detail: D/W/M/6M/Y with scrubbing; Show All Data pages past 100 rows; Data Sources lists the
   watch app and "Ayuvo" for own rows; Options change units and Home tiles.
6. Delete a record in the platform app → refresh → gone. Revoke one type → data stays,
   Allow row shows. Grant History later → older months arrive over successive opens with the
   "Importing history… 2019–2026 · 41 %" line.
7. Totals equal Health Connect; sleep 23:00–07:00 lands on the wake day; a time-zone change
   leaves historic days untouched; airplane mode → subtitle only, cursors intact.
8. Export → Delete All Data → Import → identical counts and highlights, checksums match.
9. Coach "How did I sleep this week?" → `get_sleep_history`; on-device model answers from the
   7-day summary block; Settings › Health & Data toggle off removes the tools.
10. TalkBack: hub subtitle is a live region, rows ≥ 48 dp, charts carry a text summary.

## Verification commands

```
cd android && ./gradlew :app:testDebugUnitTest :app:lintRelease :app:assembleRelease
./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.package=com.ayuvo.health.data.health
```

Play Console: the Health Connect declaration must list every `READ_*` permission in
`AndroidManifest.xml` plus `READ_HEALTH_DATA_HISTORY`, with the justification "user views,
charts and exports their own data in the Health Data hub"; Data safety must state that health
info is shared with the AI provider after user consent (Coach toggle).
