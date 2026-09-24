# Optional Google Drive backup (Android) and the app backup archive

Android only: off by default in Settings → Data & Privacy → Backup & Export. Sign-in runs only when the user turns the toggle on.

## iOS

iOS has no cloud backup (iCloud Backup was removed on 2026-09-22, including the CloudKit entitlement). Settings → Data & Privacy → Backup & Export has exactly two rows, on both platforms:

| Row | Accessibility identifier |
|-----|-------------------------|
| Export All Data (`ayuvo-all-data` zip, `AllDataExport`) | `settings.row.exportAllData` |
| Import All Data (`AllDataImport` + `ImportAllDataView`) | `settings.row.importAllData` |

The settings/profile/logs part of that zip (`app-backup/ayuvo-backup.zip`) is the same `ayuvo-cloud-backup` archive described below; `AppBackupService.swift` writes and restores it locally. Import All Data restores it only from an iPhone export (preference keys differ per platform) and then skips the food diary part, which the app backup already carries; from an Android export the app backup is skipped and the food diary JSON is merged instead. Health data, medications and Health Records are always merged by their own importers (nothing is deleted). The retired `cloudBackupEnabled` / `cloudBackupLastAt` / `cloudBackupLastHash` keys are cleared on launch and excluded from the archive.

## Android

Settings → Data & Privacy → Backup & Export: the Google Drive backup section, then the same two rows (Export All Data, Import All Data; `AllDataImportPlan` + `AllDataImportCoordinator`). The per-type Export/Import Diary and Health Data rows, the Browse menu Export/Import Health Data items, the Meds menu Export/Import items and the local Health Records archive export/restore were removed on 2026-09-22; the Drive records backup stays. Import All Data validates `manifest.json` (app `Ayuvo`, format `ayuvo-all-data`, version ≤ 1, safe and present entries; unknown sections ignored), shows a preview, then runs app_backup → food_diary → health_data → medications → health_records → coach_chats on the app scope, reporting each section. The app backup is applied only from an Android export (`CloudBackupCoordinator.applyArchive(fromFile = true)`: keeps the Drive backup state and the device-only excluded keys) and then the food diary part is skipped; from an iPhone export the app backup is skipped and the food diary JSON is merged with `DiaryImportMode.MERGE` (matching entries updated, new ones added, nothing deleted; water deduplicated by id or same time and amount — same rule as iOS `.merge`). Health data (`MERGE`), medications (§14 merge) and Health Records (§35 Merge) never delete.

### Drive setup

1. Create a Google Cloud project and enable the Drive API.
2. OAuth consent screen, External. Scopes: `drive.appdata` and `userinfo.email` only. Do not request full Drive.
3. Create Android OAuth clients for release and debug package names / SHA-1s, plus a Web client ID.
4. Copy `android/oauth.properties.example` to `android/oauth.properties` and set `cloud.backup.web.client.id`.
5. Add test users until **brand verification** (name, logo, homepage, privacy policy). No demo video for `drive.appdata`.
6. Public Play users cannot use Drive backup until brand verification is approved.

## Health

Restore writes the original food / weight / workout UUIDs and marks Health food-restore done. Apple Health / Health Connect upsert by those IDs and do not create duplicates.

## Reserved preference keys

The Health Data hub (see `docs/health-data.md`) keeps its data in a local SQLite database that is **never part of any cloud archive** — not the app backup archive, not the Drive `appdata` file, not device-to-device transfer. Only the preferences below travel with the backup; the health database itself moves between devices exclusively through the health export/import (`docs/health-data-export.md`). `CloudBackupPolicy.VERSION` / `version` stays `1`.

### Cloud-backed (same key names on both platforms)

| Key | Type | Meaning |
|-----|------|---------|
| `healthHomeTiles` | JSON array of type slugs | Which data types are pinned to the Home strip / tile |
| `healthGlucoseUnit` | `mmol/L` \| `mg/dL` | Glucose display unit (iOS: overrides the HealthKit preferred unit when set) |
| `coachHealthDataEnabled` | bool | "Let Coach use my health data" — set only by the visible consent toggle, never silently |
| `coachHealthDataConsentedAt` | ISO-8601 string | When the user confirmed that toggle |
| `coachMedicationsEnabled` | bool | "Let Coach use my medicines" — default **false**, set only by the confirmation in the composer's data switcher (`docs/coach.md` §3) |
| `coachMedicationsConsentedAt` | ISO-8601 string | When the user confirmed that |
| `coachChatBackupEnabled` | bool, **Android only** | "Include Coach chats" in the Drive backup — default **false**, set only by the consent sheet (`docs/coach.md` §12) |
| `coachChatBackupConsentedAt` | ISO-8601 string, **Android only** | When the user confirmed that |
| `healthHubEnabled` | bool, **Android only** | Hub on/off (iOS gates the hub on the existing `healthKitEnabled`) |

### Device-local (excluded from cloud backup)

| Platform | Keys | Exclusion mechanism |
|----------|------|---------------------|
| iOS | `healthKitHubPromptedVersion`, `healthKitHubLastSyncAt`, `healthKitHubRateLimitedUntil`, `healthKitBackgroundDeliveryVersion` | `healthKit` prefix rule in `CloudBackupPolicy.include` (`CloudBackupArchive.swift`) |
| Android | `healthHubPromptedVersion`, `healthHubLastSyncAt`, `healthHubRateLimitedUntil` | listed in `CloudBackupPolicy.excludedKeys` (`CloudBackupArchive.kt`) |

Restoring onto a new device resets the throttle keys and re-checks health authorization; the hub shows "Grant access" until the user re-grants.

### Excluded on both platforms

- Coach conversations moved out of the preferences entirely: they live in `ayuvo_coach.db` plus the `ayuvo-coach/` attachment directory (`docs/coach.md` §2), both excluded from Android auto-backup and device transfer (`backup_rules.xml`, `data_extraction_rules.xml`) and from iOS device backup (`isExcludedFromBackup`). `coachChatHistory` **stays** in `CloudBackupPolicy.excludedKeys` on both platforms even though nothing writes it any more: an upgrading device still holds the old key until the §12 migration runs, and a restore from an older archive can put it back. Removing it would open exactly that window, so the guard is permanent and the backup tests keep asserting it.
### Coach chats in the Drive backup (Android only, opt-in)

Reversing an earlier decision, and only with the user's say-so. The Drive archive can now carry an
`ayuvo-coach-chats` zip at `coach-chats/ayuvo-coach-chats.zip` (`docs/coach.md` §11), but **only**
while `coachChatBackupEnabled` is true. That preference defaults to false and is set by one thing: an
affirmative tap on the consent sheet under Settings → Data & Privacy → Backup & Export → Google Drive
backup → "Include Coach chats", which states plainly that a conversation can quote health values,
test results and medicines, and that they go to the user's own Drive app-data folder.

Why the reversal is safe where the old blanket exclusion was the right call: the old `coachChatHistory`
preference travelled *silently* with every backup, which store policy 5.1.3(ii) does not allow. This
does not: it is off until the user turns it on, it is one sentence away from off again, and the next
backup after it is turned off simply omits the entry. `CloudBackupArchive.pack` takes the archive
bytes or null and nothing else decides; `contentHash` folds the chats' own digest in, so a changed
transcript still triggers the next auto-backup, and a store with no chats hashes exactly as before.
Restoring **merges** (`CoachChatArchiveReader`), so restoring twice cannot duplicate a conversation
and a chat the user deleted here stays deleted.

What the toggle does not touch: the coach database and its attachment files remain excluded from
Android's own auto-backup and device transfer, and from iOS device backup, whatever it says. iOS has
no cloud backup at all, so there is no toggle there — on iOS chats travel only in the user-initiated
Export All Data zip.

- The health database files: Android `ayuvo_health.db`, `ayuvo_health.db-wal`, `ayuvo_health.db-shm`, `ayuvo_health.db-journal` (excluded in `backup_rules.xml` and in both `<cloud-backup>` and `<device-transfer>` of `data_extraction_rules.xml`); iOS `Application Support/Ayuvo/Health/` (`isExcludedFromBackup = true`).
- No health **values** are ever written to UserDefaults / DataStore; backup tests assert that no key holds numeric samples.
