# Optional iCloud / Google Drive backup

Off by default in Settings → Data Management. Sign-in runs only when the user turns the toggle on.

## iOS

1. Enable the iCloud CloudKit container `iCloud.com.ayuvo.health` in the Apple Developer portal and Xcode.
2. Deploy the `AyuvoBackup` record type to the CloudKit **Production** schema before shipping (see below).
3. The phone’s iCloud account is enough. There is no Sign in with Apple.

### CloudKit schema

**Container:** `iCloud.com.ayuvo.health`

**Private Database — Development (done):**

| Record type | Field | Type |
|-------------|-------|------|
| `AyuvoBackup` | `backupAsset` | Asset |
| `AyuvoBackup` | `contentSha256` | String |

The app writes a single record named `current` in the user’s private database (`CloudBackupService.swift`). The smoke test uses a separate record named `smoke-test` so it never overwrites or deletes the user’s real backup.

**Production — deploy before App Store release:**

1. Open [CloudKit Console](https://icloud.developer.apple.com/) and select container `iCloud.com.ayuvo.health`.
2. Choose **Schema** in the left sidebar.
3. Confirm **Development** shows record type `AyuvoBackup` with fields `backupAsset` (Asset) and `contentSha256` (String). If Development is missing them, add the record type and fields there first, then test on a Development build.
4. Open the **Deploy Schema Changes…** action (top of the Schema page).
5. Review the diff — it should add `AyuvoBackup` with the two fields above to **Production**.
6. Click **Deploy to Production** and confirm. Production schema changes are irreversible; double-check field types before confirming.
7. After deploy, run the smoke test below on a **Release** device build signed with the production profile.

### CloudKit smoke test (device)

Automated upload → download/validate → delete check against the dedicated `smoke-test` record. Safe for Release builds; runs once per launch when the argument is present. Does not call production restore (no local prefs, photos, or HealthKit recovery flags are changed). If upload succeeds but a later step fails, the smoke-test record is still deleted before exit.

**Launch argument:** `-ayuvo.cloudBackup.smokeTest`

**Requirements:** iPhone signed into iCloud; Development schema for debug builds, Production schema deployed for Release builds.

**Run from Xcode (Release configuration recommended for pre-ship validation):**

1. Product → Scheme → Edit Scheme → **Run** → **Arguments**.
2. Under **Arguments Passed On Launch**, add `-ayuvo.cloudBackup.smokeTest`.
3. Select a physical iPhone (simulator iCloud is unreliable for CloudKit private DB).
4. Run. On first launch the app uploads to `smoke-test`, validates the downloaded archive hash, deletes the smoke-test record, and leaves the user’s `current` backup and local data untouched.

**Run on an installed Release build:**

```bash
# Replace DEVICE_UDID with the connected iPhone UDID from Xcode or `xcrun xctrace list devices`
xcrun devicectl device process launch --device DEVICE_UDID \
  com.ayuvo.health \
  -ayuvo.cloudBackup.smokeTest
```

Or attach the same argument in an Xcode **Test** or **Profile** scheme when validating a Release archive.

**Expected log line** (Xcode console or Console.app, filter `CloudBackupSmoke` or `AyuvoCloudBackupSmokeTest`):

```
AyuvoCloudBackupSmokeTest: PASS
```

On failure:

```
AyuvoCloudBackupSmokeTest: FAIL: <reason>
```

Common failures: not signed into iCloud, Production schema not deployed (Release builds), or network/CloudKit outage.

### UI automation identifiers

Settings → Data Management → iCloud Backup controls:

| Control | Accessibility identifier |
|---------|-------------------------|
| Toggle | `settings.cloudBackup.toggle` |
| Last backup label | `settings.cloudBackup.lastBackup` |
| Back up now | `settings.cloudBackup.backupNow` |
| Restore now | `settings.cloudBackup.restoreNow` |
| Delete cloud backup | `settings.cloudBackup.delete` |

## Android

1. Create a Google Cloud project and enable the Drive API.
2. OAuth consent screen, External. Scopes: `drive.appdata` and `userinfo.email` only. Do not request full Drive.
3. Create Android OAuth clients for release and debug package names / SHA-1s, plus a Web client ID.
4. Copy `android/oauth.properties.example` to `android/oauth.properties` and set `cloud.backup.web.client.id`.
5. Add test users until **brand verification** (name, logo, homepage, privacy policy). No demo video for `drive.appdata`.
6. Public Play users cannot use Drive backup until brand verification is approved.

## Health

Restore writes the original food / weight / workout UUIDs and marks Health food-restore done. Apple Health / Health Connect upsert by those IDs and do not create duplicates.

## Reserved preference keys

The Health Data hub (see `docs/health-data.md`) keeps its data in a local SQLite database that is **never part of any cloud archive** — not the iCloud (`AyuvoBackup`) record, not the Drive `appdata` file, not device-to-device transfer. Only the preferences below travel with the backup; the health database itself moves between devices exclusively through the health export/import (`docs/health-data-export.md`). `CloudBackupPolicy.VERSION` / `version` stays `1`.

### Cloud-backed (same key names on both platforms)

| Key | Type | Meaning |
|-----|------|---------|
| `healthHomeTiles` | JSON array of type slugs | Which data types are pinned to the Home strip / tile |
| `healthGlucoseUnit` | `mmol/L` \| `mg/dL` | Glucose display unit (iOS: overrides the HealthKit preferred unit when set) |
| `coachHealthDataEnabled` | bool | "Let Coach use my health data" — set only by the visible consent toggle, never silently |
| `coachHealthDataConsentedAt` | ISO-8601 string | When the user confirmed that toggle |
| `healthHubEnabled` | bool, **Android only** | Hub on/off (iOS gates the hub on the existing `healthKitEnabled`) |

### Device-local (excluded from cloud backup)

| Platform | Keys | Exclusion mechanism |
|----------|------|---------------------|
| iOS | `healthKitHubPromptedVersion`, `healthKitHubLastSyncAt`, `healthKitHubRateLimitedUntil`, `healthKitBackgroundDeliveryVersion` | `healthKit` prefix rule in `CloudBackupPolicy.include` (`CloudBackupArchive.swift`) |
| Android | `healthHubPromptedVersion`, `healthHubLastSyncAt`, `healthHubRateLimitedUntil` | listed in `CloudBackupPolicy.excludedKeys` (`CloudBackupArchive.kt`) |

Restoring onto a new device resets the throttle keys and re-checks health authorization; the hub shows "Grant access" until the user re-grants.

### Excluded on both platforms

- `coachChatHistory` — Coach conversations may quote health values, so the key is in `CloudBackupPolicy.excludedKeys` on both platforms (App Store 5.1.3(ii)); a backup test asserts it.
- The health database files: Android `ayuvo_health.db`, `ayuvo_health.db-wal`, `ayuvo_health.db-shm`, `ayuvo_health.db-journal` (excluded in `backup_rules.xml` and in both `<cloud-backup>` and `<device-transfer>` of `data_extraction_rules.xml`); iOS `Application Support/Ayuvo/Health/` (`isExcludedFromBackup = true`).
- No health **values** are ever written to UserDefaults / DataStore; backup tests assert that no key holds numeric samples.
