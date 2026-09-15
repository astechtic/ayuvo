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
