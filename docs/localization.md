# Localization

Ayuvo ships in English plus 17 languages on both apps: ar, az, cs, de, es, fr, hi, it, ja, ko, nl, pl, pt-BR, ro, ru, uk, zh-Hans (Android `zh-rCN`, `pt-rBR`).
The list, the CLDR plural categories per language and the product names that are never translated live in `shared/l10n/l10n_config.json`.
The scripts and both coverage tests read that file.

## Rules
- **Every user-visible string is localizable.** That covers screens, alerts, notifications, widgets, Live Activities, Siri/Shortcuts titles, accessibility labels and text shared with the user or their doctor.
- **These stay English** (and are listed in `scripts/l10n/allow/*.json`):
  - AI prompts and the tool descriptions sent to models;
  - SQL;
  - parsers that read English documents;
  - persisted identifiers and raw values;
  - machine formats read back by an importer;
  - logs;
  - citations (author, year, journal).
- **Product names stay as they are:** Ayuvo, Apple Health, HealthKit, Health Connect, MedGemma, Gemma, and the provider names.
- **Numbers follow the user's locale.**
  - iOS uses `FormatStyle` / `Measurement.formatted`.
  - Android uses `NumberFormat` or `String.format(Locale.getDefault(), …)`.
  - `Locale.US` / `en_US_POSIX` are only for machine formats.
- **Counts use real plurals:**
  - iOS: catalog plural variations.
  - Android: `<plurals>`.
  - Never glue an English "s" onto a word.
- **Medical and safety wording is translated faithfully.** Disclaimers keep their exact meaning, and the non-diagnostic copy rules apply in every language.

## Adding a string
**iOS**
- Use `Text("…")`, `String(localized: "…", comment: "…")` or `LocalizedStringResource`.
- The compiler extracts them. Xcode fills `Localizable.xcstrings` on the next IDE build; from the command line, run `python3 scripts/l10n/l10n_sync_ios.py --write` after a build.
- The widget, Watch and share extension targets each have their own `Localizable.xcstrings`.
- App Shortcut phrases live in `AppShortcuts.xcstrings`.
- Count strings: add their English one/other forms with `python3 scripts/l10n/l10n_plurals_ios.py FILE --write`, where each line of FILE is `key<TAB>one<TAB>other`.
- A "%" in plain text that Xcode could read as a format specifier ("10%-dən") gets an invisible word joiner (U+2060) after it; `l10n_apply.py` adds it automatically.

**Android**
- Add the string to the feature's `res/values/*.xml` file, escaping `'` as `\'`.
- Add a comment above the string when its meaning is not obvious.
- Use `stringResource` / `getString`.
- A plain-text string with a "%" gets `formatted="false"`, and symbol-only or product-name strings are copied into every locale. `l10n_apply.py` does both, so `MissingTranslation` can stay an error.
- The per-app language list is `res/xml/locales_config.xml` (referenced from the manifest). Update it when `l10n_config.json` changes; `LocalizationCoverageTest` checks it.

**Shared JSON contracts**
- Display text in `shared/derived`, `shared/workout`, `shared/metrics` and `shared/insights` is looked up by key: `ContractText.text(key, english)` on iOS, `ContractStrings.text(context, key, english)` on Android.
- After changing a contract, run `python3 scripts/l10n/l10n_contracts.py --write`. It regenerates `Contracts.xcstrings`, `strings_contracts.xml` and `ContractStrings.kt`.
- If the English of a key changes, that key needs a new translation.

## Translating
```
python3 scripts/l10n/l10n_extract.py --work /tmp/l10n [--lang de]  # batches of missing strings
# translate /tmp/l10n/<lang>/batch_NNN.json -> batch_NNN.out.json
python3 scripts/l10n/l10n_apply.py --work /tmp/l10n [--lang de]    # validate and write back
```
- **Deduplication.** The same English on iOS and Android is translated once. Existing translations of the same text are reused.
- **Placeholders** are shown to translators as `⟦1⟧`, `⟦2⟧`… and turned back into `%@`, `%1$s`, `{name}` and so on per platform. They switch to positional arguments when a translation reorders them.
- **Same English, different meaning.** Keys listed in `translate_separately` in `l10n_config.json` get their own translation instead of the shared one (for example medication "Strength" vs the workout type).
- **Revised batches.** `l10n_apply.py --refresh` also rewrites translations still in `needs_review`.
- **Glossary.** Each language has a glossary (`shared/l10n/glossary/<lang>.json`) of the app's fixed terms.

## Review
- **Every machine translation starts as `needs_review`.**
  - iOS: the catalog state `needs_review`, which still ships.
  - Android: `shared/l10n/review/<lang>.json`, which maps each resource name to the hash of its English and a state.
- **After a native speaker checks a string**, set it to `translated` (iOS) or `reviewed` (Android ledger).
- **When the English changes**, the ledger hash no longer matches and the report asks for a new translation.

## Checks
- **`python3 scripts/l10n/l10n_report.py [--detail] [--strict]`** shows coverage per platform, file and language. `--strict` exits 1 when anything is missing, empty, `new`, or has mismatched placeholders or plural forms.
- **`python3 scripts/l10n/l10n_codescan.py [--by-file] [--all]`** lists literals that bypass localization.
  - For iOS it counts a literal as localized only when the compiler extracted it, so build first.
  - Allowed English lives in `scripts/l10n/allow/*.json`.
- **`python3 scripts/l10n/l10n_contracts.py`** checks that the contract string files are up to date.
- **Tests:** iOS `LocalizationCoverageTests` and Android `LocalizationCoverageTest` fail on any missing or mismatched translation. Android lint's `MissingTranslation` is an error.
