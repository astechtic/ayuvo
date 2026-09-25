# Contributing to Ayuvo

Thanks for helping. Ayuvo is an MIT-licensed, privacy-first health app for iPhone (SwiftUI) and Android
(Jetpack Compose). The source is at <https://github.com/astechtic/ayuvo>. It is inspired by
[Fud AI](https://github.com/apoorvdarshan/fud-ai) and keeps that project's MIT notice in `LICENSE`.

## Ground rules

- **Privacy first.** No analytics, crash, ad or tracking SDKs, no accounts and no Ayuvo-operated servers. A new
  network request must be triggered by the user and go to a service they chose; document it in
  `web/_src/pages/privacy.html` and the README table.
- **Never write that data "never leaves the device".** Say it is kept on this device and leaves only when the
  user acts, to a service they chose.
- **No medical claims.** Ayuvo describes; it does not diagnose or advise on doses. Keep the disclaimers in place
  wherever a health model or medication feature is described.
- **Contract first.** Behaviour that must match on both platforms (health registry, export formats, AI routing,
  Coach tools) lives in `shared/` and `docs/` with test vectors. Change the contract and its vectors first, then
  both platforms in the same pull request.

## Set up

Follow `DEVELOPMENT.md`: Xcode 16+ for iOS, Android Studio (JDK 17) for Android, Pillow for the Python tooling.

```bash
# iOS
xcodebuild -project ios/calorietracker.xcodeproj -scheme calorietracker \
  -destination 'platform=iOS Simulator,name=iPhone 17 Pro' build

# Android
cd android && ./gradlew :app:assembleDebug :app:testDebugUnitTest
```

## Good first contributions

- **Translations:** iOS string catalogs (`ios/calorietracker/*.xcstrings`) and Android `res/values-*/`. The brand
  name "Ayuvo" is never translated.
- **Exercise data and health metrics:** `shared/exercises`, `shared/health/metric_registry.json`. Both apps read them.
- **Docs and website:** the site is generated. Edit `scripts/web/pages.py`, run `python3 scripts/web_build.py`,
  and check it with `python3 scripts/web_check.py` and `python3 scripts/web_build.py --check`.
- **Bugs and small features:** open an issue first for anything larger than a fix.

## Pull request checklist

- [ ] One focused change, with a description of the user-visible behaviour
- [ ] Both platforms updated when a shared contract changed, with the vectors regenerated
- [ ] Tests added or updated (`./gradlew :app:testDebugUnitTest`, `xcodebuild ... test`)
- [ ] `python3 scripts/web_check.py`, `python3 scripts/brand/render_icons.py --check` when the site or brand changed
- [ ] No API keys, personal data or real health documents in code, fixtures or screenshots
- [ ] New third-party code or assets are credited in `THIRD_PARTY_NOTICES.md` / `ASSET_CREDITS.md`

## Screenshots and personal data

Raw device captures can contain real records, medications and body measurements. They stay out of the repo
(`ayuvo_screenshots/` is ignored). Publishable screenshots come from `python3 scripts/web/redact_screens.py`, or from
a demo profile as described in `marketing/seed/README.md`.

## Reporting security issues

Do not open a public issue. Email <yaaratech@gmail.com> with the subject "Security" as described in `SECURITY.md`.

## Licence

By contributing you agree that your work is released under the MIT licence in `LICENSE`.
