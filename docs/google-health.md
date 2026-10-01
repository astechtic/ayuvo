# Google Health API source

Ayuvo can pull a user's data from the **Google Health API** (`health.googleapis.com/v4`), which serves Fitbit and Pixel Watch data. The data is saved into the local health mirror (`docs/health-data.md`), and can optionally be written into Apple Health (iOS) or Health Connect (Android). This page is the cross-platform contract. The machine-readable part is `shared/health/google_health_map.json`, and its test vectors are in `shared/health/test-vectors/google_health/`.

## 1. Flow

```
Settings › Google Health › Connect ─► OAuth (user's Google account) ─► first sync (90 days)
Sync now / app open (opt-in) ─► existing HK/HC mirror sync ─► Google Health sync ─► mirror write-back
```

- **What triggers a network call:** connecting, **Sync now**, and the app-open auto-sync. Auto-sync is opt-in, throttled by `auto_sync_min_interval_s`, and off by default. Nothing runs in the background without the user turning it on.
- **Where requests go:** only Google (`accounts.google.com`, `oauth2.googleapis.com`, `openidconnect.googleapis.com`, `health.googleapis.com`). There is no Ayuvo server.
- **Failures:** a Google failure never fails the platform sync. It shows on its own status row.

## 2. OAuth

| Mode | iOS | Android |
|---|---|---|
| Bundled (default) | `ASWebAuthenticationSession` + PKCE (S256) with the iOS client in `GH_IOS_CLIENT_ID` (Info.plist, from the gitignored `ios/GoogleHealth.xcconfig`). Redirect `com.googleusercontent.apps.<id>:/oauth2redirect`. Refresh token stored in the Keychain. | Identity `AuthorizationClient.authorize(scopes)` with the Android client registered for the package and SHA-1. `BuildConfig.GOOGLE_HEALTH_WEB_CLIENT_ID` comes from `oauth.properties` (`google.health.web.client.id`). A silent re-authorize before every sync returns a fresh access token, so no refresh token is stored. |
| Custom ("use my own client ID") | The user pastes an **iOS** client ID from their own Google Cloud project. The flow is otherwise the same. | The user pastes a **Desktop** client ID and secret. The flow uses Custom Tabs + PKCE and a loopback redirect to `http://127.0.0.1:<ephemeral port>`. Refresh token stored in `KeyStore`. |

- **Scopes:** requested per data group (`scope_groups` in the map) plus `openid email`, which is used for the account label. The user can grant only some scopes; types whose scope wasn't granted get status `error:scope` and are skipped.
- **Disconnect:**
  - revokes the token (`revoke_url`);
  - clears the Keychain or KeyStore entries;
  - deletes `google_health_sync_state` rows;
  - asks whether to delete the local origin-3 rows.
  - Records already written to Apple Health or Health Connect stay there, and the UI says so.

### Google Cloud setup (maintainers, or users of the custom mode)

1. Create a project and enable the **Google Health API**.
2. Set up the OAuth consent screen. Add the `googlehealth.*` read scopes and `openid email`.
   - While the app is unverified it is in testing mode: at most 100 test users, and refresh tokens expire after 7 days.
   - A public release needs Google's sensitive or restricted scope verification.
3. Create the clients:
   - **iOS:** bundle id `com.ayuvo.health`.
   - **Android:** package `com.ayuvo.health` plus the release and debug SHA-1s.
   - **Web:** for the Android `requestOfflineAccess` server client id.
   - **Desktop:** only needed for custom mode on Android.
4. Put the ids into `ios/GoogleHealth.xcconfig` and `android/oauth.properties` (see the `.example` files).

## 3. Storage

- **Where rows go:** into `health_samples` with `origin = 3`.
  - `id = "gh:<point id>"`.
  - `source_id = "google_health:<package or family>"`.
  - The layout follows `docs/health-data.md` §1.3. Sleep uses the Android session + stage layout (§5 there).
  - The rollups, Browse, Coach tools and derived metrics treat these rows like any other.
- **Upsert rule:** origin-3 rows follow the same upsert rule. A re-fetched point overwrites the stored row only when a value column differs. Platform deletions never touch origin-3 rows, and Google deletions are not observed in v1.
- **`google_health_sync_state`:** one row per `gh_type`.
  - The first sync reads from `now − initial_backfill_days`. Later syncs read from `cursor_ms − overlap_days`.
  - `page_token` is saved with each committed page, so an interrupted fetch can resume.
  - The cursor moves only after the last page commits, in the same transaction.
- **`google_health_mirror`:** one row per origin-3 sample that is a mirror candidate.
- **Google-only slugs** (registry v2): `active_zone_minutes`, `activity_level`, `sedentary_period`, `calories_in_hr_zone`, `swim_lengths`, `daily_hrv`, `daily_blood_oxygen`, `nightly_temperature_deviation`, `ecg_recording`. They are never read from or written to HealthKit or Health Connect.
- **ECG:** waveforms and irregular-rhythm beat lists are dropped (`drop_fields`); only the summaries are stored.
- **Backups:** these rows follow the health-database rules (never in cloud backups). Tokens live only in the Keychain or KeyStore. The account email, connected time, client mode, granted scopes and toggles are device-local preference keys, prefixed `googleHealth`, and are excluded from cloud backup.

## 4. Mirror write-back

- **Which rows are written:** when "Also write to Apple Health / Health Connect" is on (the default), origin-3 rows whose map entry has `hk` (iOS) or `hc` (Android).
  - iOS tags each sample with metadata `ayuvo_ghealth_id`.
  - Android sets `clientRecordId = "ayuvo_gh_<id>"` and `clientRecordVersion = updated_ms`.
- **No duplicates:** the platform reader skips records carrying those tags, because the origin-3 row is already the canonical copy.
- **Echo guard:** a point is stored but not mirrored (`skipped_dup`) when either of these is true:
  - its `dataSource.application.packageName` is in `echo_guard.skip_mirror_when_package_in` (`dataSource.platform` alone is not used: it names the phone that uploaded, so Pixel Watch data would never reach Health Connect);
  - an origin-0 row of the same type exists whose start and end are within `duplicate_window_ms` and whose value is within `duplicate_value_epsilon_ratio`.
- **Types with no platform target** are marked `unsupported`.
- **iOS:** the extra share types are requested in step 4 of the setup flow, which bumps `typesVersion`. Workouts become an `HKWorkout` (the activity type is mapped by name from `exerciseType`). Sleep stages become `HKCategoryValueSleepAnalysis` samples, and nutrition becomes an `HKCorrelation` food entry.
- **Android:** the WRITE permissions listed in the map are requested in step 4 of the setup flow. Failed writes are retried by `HealthWriteRetryWorker`.

## 5. Settings

The `googleHealth` page sits in Data & Privacy, after Health Sync.
- **Disconnected:** a "Connect Google Health" row opens a 4-step setup:
  1. Intro and privacy.
  2. Choose data groups, with an Advanced section for a custom client ID.
  3. Google consent.
  4. Platform write permission and the first sync.
- **Connected:** account, last synced, per-group status, Sync now, Auto-sync on app open, Write to Apple Health / Health Connect, Manage data types, Reconnect (shown when consent is lost), and Disconnect.

Accessibility ids:

| Surface | Identifier |
|---|---|
| Settings category row | `settings.category.googleHealth` |
| Connect | `settings.row.googleHealth.connect` |
| Sync now | `settings.row.googleHealth.syncNow` |
| Auto-sync toggle | `settings.row.googleHealth.autoSync` |
| Write-back toggle | `settings.row.googleHealth.writeBack` |
| Manage data types | `settings.row.googleHealth.manage` |
| Reconnect | `settings.row.googleHealth.reconnect` |
| Disconnect | `settings.row.googleHealth.disconnect` |
| Setup step container | `googleHealth.setup.step.<1-4>` |
| Setup continue button | `googleHealth.setup.continue` |
| Custom client id field | `googleHealth.setup.clientId` |
