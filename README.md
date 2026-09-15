<p align="center">
  <img src="brand/ayuvo-mark-rose.svg" width="96" height="100" alt="Ayuvo mark">
</p>

<h1 align="center">Ayuvo</h1>

<p align="center"><strong>Your whole health, in one place.</strong></p>

Private, all-in-one health companion for iPhone (SwiftUI) and Android (Jetpack Compose): nutrition, workouts, a local mirror of Apple Health / Health Connect, fasting, water and an AI coach that runs on the API key the user brings. No account, no Ayuvo servers, no analytics.

## What Ayuvo is

| Area | What it does |
|------|--------------|
| **Nutrition** | Meals by photo (up to 10 at once), barcode (Open Food Facts), voice, text, saved meals or manual entry; review calories, macros and 30+ nutrients before saving; "What if?" previews; personalised targets (Mifflin-St Jeor / Katch-McArdle); custom meal times; nutrient goals and Home cards. |
| **Workouts** | Day-by-day diary with sets, reps, weight, RPE; calculated burn; 1,300-exercise library (`shared/exercises`) with photos/animations streamed from the upstream dataset. |
| **Health data hub** | Every Health Connect / HealthKit type the user grants, all history, mirrored into a local SQLite database (`shared/health/schema.sql`, `metric_registry.json`); Home tiles, category lists, D/W/M/6M/Y charts, Show All Data, Data Sources, unit options; export/import as `ayuvo-health-data` zips; never in cloud backups. Contract: `docs/health-data.md`. |
| **Fasting & water** | Optional fasting timer (1–168 h) with editable history; optional water goals, reminders, widgets. Neither is written to Health. |
| **AI coach** | Multi-turn chat with tool calls over the user's own data (diary, workouts, weight trend, fasts, and Health data only behind a visible consent toggle). 15 BYOK providers or on-device Gemma 4 E2B / Apple Intelligence; keys in Keychain / EncryptedSharedPreferences. |
| **Platform** | Apple Watch app + complications, iOS/Android widgets, Siri Shortcuts, iOS Share Extension, 18 languages, 18 accent tints with matching icons, optional iCloud / Google Drive backup (never includes health data or coach chat). |

Tabs on both platforms: **Home · Health · Coach · Workouts · Settings**. The Health tab switches between *Progress* (weight, body fat, calories, workouts) and *Health Data* (the hub).

## Privacy model

Everything is stored on the device. Data leaves only when the user acts, and only to a service they chose:

| Flow | Sent | To | When |
|------|------|----|------|
| BYOK AI | photos, text, coach messages + requested context (health data only with the toggle) | provider the user configured | analyse / ask |
| Speech-to-text | audio clip | provider the user chose (or on-device) | voice input |
| Barcode | barcode number | Open Food Facts | scan |
| Exercise media | plain GET | raw.githubusercontent.com (© Gym visual) | browsing Workouts |
| Model download | plain GET | Hugging Face | tapping Download |
| Cloud backup | archive without health DB / chat | user's own iCloud / Google Drive | toggle on |
| Update check / review | none (public metadata) | Apple / Google | app open |
| OS backups | per OS settings; health DB and chat excluded | Apple / Google | — |

No analytics, crash or ad SDKs; no accounts; no first-party endpoints. Copy rule: never claim data "never leaves the device"; say "kept on this device, never stored in iCloud/Drive backup, shared with your AI provider only through Coach".

## Repo layout

```
ayuvo/
├── ios/            # SwiftUI app (target calorietracker), widgets, watch app, share extension, tests
├── android/        # Kotlin + Compose app (com.ayuvo.health), tests, docs
├── shared/         # exercises dataset + health registry/schema shared by both apps
├── web/            # static site (Firebase Hosting): landing, privacy, terms, support
├── brand/          # mark geometry outputs, tints, fonts, brand README
├── scripts/brand/  # icon/marketing renderer (Pillow), scripts/web_check.py, scripts/brand_residue.py
├── marketing/      # logos, store graphics, screenshot storyboard + seed guide
├── docs/           # health-data contract, export format, cloud backup
├── local-models/   # on-device model catalog + third-party notices
├── APPSTORE.md · PLAYSTORE.md · RELEASE_NOTES.md · DEVELOPMENT.md · SECURITY.md
└── LICENSE · THIRD_PARTY_NOTICES.md · ASSET_CREDITS.md
```

## Build & run

```bash
# iOS
xcodebuild -project ios/calorietracker.xcodeproj -scheme calorietracker \
  -destination 'platform=iOS Simulator,name=iPhone 17 Pro' build

# Android
export ANDROID_HOME=~/Library/Android/sdk
cd android && ./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.ayuvo.health.debug/com.ayuvo.health.MainActivity
```

First launch: onboarding (profile, goals, notifications, Apple Health / Health Connect with the Coach consent toggle, AI provider key). See `DEVELOPMENT.md` for tests, the web site, the brand pipeline, screenshots and the release checklist.

## Release process

- Versions: iOS `MARKETING_VERSION` / `CURRENT_PROJECT_VERSION` in `ios/calorietracker.xcodeproj/project.pbxproj`; Android `versionName` / `versionCode` in `android/app/build.gradle.kts`.
- Notes: add a `## vX.Y` section to `RELEASE_NOTES.md` (`scripts/release_notes.py` refuses to publish without it).
- Tags: `vX.Y` → Xcode Cloud archive + GitHub release entry; `android-vX.Y` → signed AAB uploaded to Play as a draft.
- Store copy: `APPSTORE.md`, `PLAYSTORE.md`; site: `cd web && firebase deploy --only hosting`.

## Licence

MIT — see `LICENSE`. Ayuvo is built on the MIT-licensed Fud AI code base; the original copyright notice is retained there and shown in the apps under Settings → Legal → Licenses. Third-party credits: `THIRD_PARTY_NOTICES.md`, `ASSET_CREDITS.md`, `local-models/legal/`.
