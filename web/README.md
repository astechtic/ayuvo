# ayuvo-health.web.app

Static marketing + legal site for Ayuvo, hosted on Firebase Hosting. No server code, no analytics, no
third-party requests (fonts and icons are self-hosted; `scripts/web_check.py` asserts this).

**The HTML is generated.** Do not edit `web/*.html` by hand. Edit the content in `scripts/web/pages.py` (components:
`scripts/web/site_lib.py`; legal and support bodies: `web/_src/pages/*.html`), then run `python3 scripts/web_build.py`.

## Pages

| URL | Purpose |
|-----|---------|
| `/` | Home: privacy-first pitch, feature bento, records, on-device AI (MedGemma), Health hub, switch phones, open source, FAQ |
| `/features` | Feature overview, Watch, widgets, Siri Shortcuts, languages |
| `/features/nutrition`, `/workouts`, `/health-data`, `/records-and-medications`, `/coach`, `/fasting-and-water` | One page per feature, each with "what this sends, and to whom" |
| `/features/switch-phones` | Move between iPhone and Android with Export All Data |
| `/features/on-device-ai` | On-device models incl. MedGemma 1.5 4B (table generated from `local-models/catalog.v2.json`) |
| `/privacy-first` | Private by design: principles and every data flow |
| `/ai-providers` | 15 bring-your-own-key providers |
| `/open-source` | MIT licence, repo layout, how to contribute, credits |
| `/download` | Store badges (or "coming soon"), requirements, build from source |
| `/privacy`, `/terms`, `/support` | Legal and support (linked from both apps and the store listings; keep these URLs stable) |
| `/404.html` | Firebase serves it for unknown paths |

Clean URLs are on (`firebase.json`), so `/privacy.html` 301-redirects to `/privacy`.

## Store listings

`STORES` in `scripts/web/site_lib.py` holds the App Store and Google Play URLs. While a store's `live` flag is
`False` the site shows "Coming soon" instead of the official badge (a badge must always link to a real listing), and
omits `installUrl` from the structured data. Set `live` to `True` once the listing resolves.

## Copy lint

`python3 scripts/web_build.py --check` fails the build on: claims that data "never leaves the device", absolute privacy or
compliance claims, diagnosis wording, superlatives about medical models, invented traction figures, iCloud backup claims,
a MedGemma mention without its disclaimer, titles over 62 characters, descriptions outside 70-165, and pages without
exactly one `<h1>`.

## Placeholders to fill before going live

`GOVERNING_LAW` in the Terms (`web/_src/pages/terms.html`). `python3 scripts/web_check.py --fail-on-placeholders` lists
any remaining token.

## Validate, preview, deploy

```bash
python3 scripts/web_build.py && python3 scripts/web_check.py       # add --fail-on-placeholders --store-docs before release
cd web && firebase emulators:start --only hosting --project demo-ayuvo
cd web && firebase hosting:channel:deploy preview --expires 7d
cd web && firebase deploy --only hosting
```

Brand assets under `assets/brand/` are rendered by `scripts/brand/render_icons.py --targets web`. Screenshots under
`assets/screens/` come from `scripts/web/redact_screens.py` (raw device captures never go in the repo); social cards
under `assets/og/` from `scripts/web/social_cards.py`.
