# ayuvo-health.web.app

Static marketing + legal site for Ayuvo, hosted on Firebase Hosting. No server code, no
analytics, no third-party requests (fonts and icons are self-hosted; `scripts/web_check.py`
asserts this).

## Pages

| URL | File | Purpose |
|-----|------|---------|
| `/` | `index.html` | Landing page (features, how it works, FAQ + JSON-LD) |
| `/privacy` | `privacy.html` | Privacy Policy (linked from both apps and the store listings) |
| `/terms` | `terms.html` | Terms of Service |
| `/support` | `support.html` | Support / FAQ page (App Store "Support URL", Play "Website") |
| `/404.html` | `404.html` | Firebase serves it for unknown paths |

Clean URLs are on (`firebase.json`), so `/privacy.html` 301-redirects to `/privacy`.

## Placeholders to fill before going live

`FIREBASE_PROJECT_ID` (`.firebaserc`), `SUPPORT_EMAIL`, `LEGAL_ENTITY_NAME`, `LEGAL_ADDRESS`,
`GOVERNING_LAW`, `EFFECTIVE_DATE`, `APP_STORE_URL`, `PLAY_STORE_URL`, `AYUVO_APPSTORE_ID`
(the `apple-itunes-app` meta tag). `python3 scripts/web_check.py --fail-on-placeholders`
lists every remaining token.

## Validate, preview, deploy

```bash
python3 scripts/web_check.py                     # files, links, hosts, CSP hygiene, JSON-LD, images
cd web && firebase emulators:start --only hosting --project demo-ayuvo
cd web && firebase hosting:channel:deploy preview --expires 7d
cd web && firebase deploy --only hosting
```

Brand assets under `assets/brand/` and `assets/opengraph.jpg` are rendered by
`scripts/brand/render_icons.py --targets web`; screenshots under `assets/screenshots/` come
from `scripts/brand/compose_marketing.py` (see `marketing/seed/README.md`).
