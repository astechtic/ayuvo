# Asset Credits

Exercise data, names, English instructions, and catalogue IDs in the Workouts tab come from [exercises-dataset](https://github.com/hasaneyldrm/exercises-dataset) (MIT License for code, data structure, and instruction text; Copyright (c) 2026 Hasan Emir Yıldırım), pinned to a specific commit and bundled as `shared/exercises/exercises.json` by `scripts/import_exercises_dataset.py`. The upstream `LICENSE` and `NOTICE.md` are kept alongside it. iOS and Android bundle the same file.

Exercise thumbnails and animated GIFs are © Gym visual — https://gymvisual.com/. They are not bundled; the apps load them at runtime from the upstream repository and show this attribution on exercise detail screens. That media is governed by Gym visual's Terms & Conditions, not by the MIT License.

Barcode product lookups are powered by the [Open Food Facts](https://world.openfoodfacts.org) database, queried live via its public API. Open Food Facts data is available under the [Open Database License (ODbL)](https://opendatacommons.org/licenses/odbl/1-0/); the database is made by a community of contributors. Ayuvo does not bundle the database — nutrition facts are fetched per scanned barcode.

The Ayuvo mark and every app icon are rendered from `scripts/brand/ayuvo_mark.py` (see `brand/README.md`); no third-party icon artwork is bundled.

Muscle glyph assets (the muscle-filter icons in the Workouts tab) are cropped/rasterized derivatives of SVG muscle paths from [`react-muscle-highlighter`](https://github.com/soroojshehryar/react-muscle-highlighter) 1.2.0, MIT License. The generated app assets are bundled locally (iOS asset catalog, Android `app/src/main/assets/muscle/`) and do not depend on the upstream repository at runtime.

Copyright (c) 2024 My Muscle Contributors

Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation files (the "Software"), to deal in the Software without restriction, including without limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT.

## Store badges, platform marks and trademarks (website and marketing)

- `marketing/badges/appstore-black.svg`: Apple's "Download on the App Store" badge, downloaded unmodified from
  Apple's marketing tools. Use it only as a link to the real App Store listing (the site shows a plain
  "coming soon" note until the listing is live: `STORES` in `scripts/web/site_lib.py`).
- `marketing/badges/googleplay.png`: Google's "Get it on Google Play" badge (`en_badge_web_generic.png`), unmodified,
  with Google's clear-space margin left in place. Same rule: link it only to the live listing.
- **Works with Apple Health** is a badge Apple supplies to Apple Developer Program members
  (<https://developer.apple.com/licensing-trademarks/works-with-apple-health/>). It is not bundled here and must not
  be redrawn. Its rules: one badge per promotion page, subordinate to the main message, unmodified, not on
  social media or "About Us" pages, with the Apple trademark line. Until the artwork is added the site uses the plain
  text "Works with Apple Health". To add it, save the files under `marketing/badges/` and place one badge per page.
- **Health Connect** has no downloadable "works with" badge on Google's Health Connect pages at the time of writing
  (<https://developer.android.com/health-and-fitness/health-connect/ui/promote>). The site uses the name in text
  only; check <https://developer.android.com/distribute/marketing-tools/brand-guidelines> before adding a logo.
- **Gemma and MedGemma** are trademarks of Google. The site uses the names in text only, no Google, DeepMind or
  Gemma logos, and states that Ayuvo is independent and not endorsed by Google.
- The footer of every page carries Apple's required attribution ("Apple, the Apple logo, iPhone and Apple Watch are
  trademarks of Apple Inc., registered in the U.S. and other countries.") and Google's trademark line.
- The GitHub mark in `scripts/web/site_lib.py` (icon sprite) is used only to link to the project's GitHub page.
- Screenshots on the site are redacted captures of Ayuvo itself (`scripts/web/redact_screens.py`). Third-party exercise
  photos (© Gym visual) are replaced by neutral tiles in every published screenshot.

