# Development

Internal guide for building, testing and releasing Ayuvo. The repo is private; there is no
contribution process.

## Setup

- **iOS:** Xcode 16+ (Xcode 26 for iOS 27 APIs), open `ios/calorietracker.xcodeproj`. Local SPM package
  `ios/Packages/LiteRTLM` and `argmax-oss-swift` resolve on first build. Signing: set your team on the
  `calorietracker` target and its extensions (`DEVELOPMENT_TEAM` placeholder `AYUVO_TEAM_ID`).
- **Android:** Android Studio Narwhal+, JDK 17. `ANDROID_HOME=~/Library/Android/sdk`. Copy
  `android/oauth.properties.example` → `android/oauth.properties` for Drive backup (new OAuth clients for
  `com.ayuvo.health` / `.debug`).
- **AI key for testing:** Settings → AI Providers → Google Gemini → `gemini-3.5-flash-lite` → paste a key.
- **Python tooling:** `pip install -r scripts/brand/requirements.txt` (Pillow).

## Tests

```bash
# Android (CI command)
cd android && ./gradlew :app:testDebugUnitTest :app:lintRelease
./gradlew :app:connectedDebugAndroidTest      # emulator API 34+ with Health Connect
python3 android/scripts/check_brand_residue.py

# iOS
xcodebuild -project ios/calorietracker.xcodeproj -scheme calorietracker \
  -destination 'platform=iOS Simulator,name=iPhone 17 Pro' test

# Website, brand, repo hygiene
python3 scripts/web_check.py                  # add --store-docs, --fail-on-placeholders before release
python3 scripts/brand/render_icons.py --check
python3 scripts/brand/test_render.py
python3 scripts/brand_residue.py              # non-app dirs: no old-brand tokens
python3 local-models/verify_catalog.py        # add --online before release
```

## Website

`web/` is a static Firebase Hosting site (see `web/README.md`).

```bash
cd web && firebase emulators:start --only hosting --project demo-ayuvo   # http://127.0.0.1:5055
cd web && firebase hosting:channel:deploy preview --expires 7d
cd web && firebase deploy --only hosting
```

## Brand pipeline

`brand/README.md` documents the mark. `scripts/brand/render_icons.py --targets all` regenerates every
icon on both platforms, the web favicons/OG image and the marketing sets; `--check` must pass in CI.
Screenshots: `marketing/seed/README.md` → `python3 scripts/brand/compose_marketing.py`.

## Localisation

18 languages: iOS string catalogs (`ios/calorietracker/*.xcstrings`), Android `res/values-*/`. New
strings ship English-first; translations are marked `needs_review` (iOS) or listed in the locale TODO
until reviewed. Brand name "Ayuvo" is never translated.

## Release checklist

1. Bump versions (`MARKETING_VERSION` / `CURRENT_PROJECT_VERSION`; `versionName` / `versionCode`).
2. Add `## vX.Y` to `RELEASE_NOTES.md`; run `python3 scripts/release_notes.py vX.Y`.
3. Update `APPSTORE.md` / `PLAYSTORE.md` "What's New".
4. `python3 scripts/web_check.py --store-docs --fail-on-placeholders`, deploy the site if legal text changed.
5. Tag `vX.Y` (iOS, Xcode Cloud) and `android-vX.Y` (Play draft via `.github/workflows/android-release.yml`).
6. Roll out in App Store Connect / Play Console.

## Data contracts

- `docs/health-data.md`, `docs/health-data-export.md` — Health data hub (shared registry/schema, export
  format `ayuvo-health-data`, Coach tools identical on both platforms).
- `docs/coach.md` — Coach chat (conversations, attachments, markdown/chart blocks, data switches,
  prompt gallery, `ayuvo-coach-chats` archive); tool sources point at the three docs above.
- `docs/cloud-backup.md` — optional iCloud / Google Drive backup; reserved preference keys.
- `android/docs/` — Android-specific notes.
