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


## Nutrient guide photos (website, `web/assets/nutrients/`)

One food photo per `/nutrients/<slug>` page, downloaded from Unsplash or Pexels (free licences; Unsplash+ images are not used),
cropped to 3:2 and converted to WebP (1200x800 hero, 600x400 thumbnail) by the build helper, and credited in a caption on each page.
The same photo also appears on that page's social card (`web/assets/og/nutrients-<slug>.jpg`). Licences checked 2026-09-27:
[Unsplash License](https://unsplash.com/license), [Pexels License](https://www.pexels.com/license/).

- `vitamin-a.webp`: Carrots. Photo by [Nick Fewings](https://unsplash.com/@jannerboy62) on [Unsplash](https://unsplash.com/photos/a-pile-of-carrots-with-green-tops-and-leaves-IZq1FV87qpM), Unsplash License.
- `vitamin-c.webp`: Mandarin oranges. Photo by [Erol Ahmed](https://unsplash.com/@erol) on [Unsplash](https://unsplash.com/photos/orange-fruits-_MYcIi9DgYQ), Unsplash License.
- `vitamin-d.webp`: White button mushrooms. Photo by [Waldemar Brandt](https://unsplash.com/@waldemarbrandt67w) on [Unsplash](https://unsplash.com/photos/bunch-of-white-mushrooms-Ql9oYxramg0), Unsplash License.
- `vitamin-e.webp`: Almonds. Photo by [Tamanna Rumee](https://unsplash.com/@tamanna_rumee) on [Unsplash](https://unsplash.com/photos/a-pile-of-almonds-on-a-dark-background-CpjYvMnNNqU), Unsplash License.
- `vitamin-k.webp`: Kale. Photo by [Monika Borys](https://unsplash.com/@fotoinshadows) on [Unsplash](https://unsplash.com/photos/wut5BTxFHTc), Unsplash License.
- `vitamin-b12.webp`: Cooked clams in their shells. Photo by [Bob Nease](https://unsplash.com/@bobnease) on [Unsplash](https://unsplash.com/photos/a-pile-of-fresh-clams-with-shells-xKJFrXdlaqo), Unsplash License.
- `folate.webp`: Fresh green asparagus spears. Photo by [engin akyurt](https://unsplash.com/@enginakyurt) on [Unsplash](https://unsplash.com/photos/close-up-of-fresh-green-asparagus-spears-vZI6sTly1p4), Unsplash License.
- `calcium.webp`: Bowl of yogurt with granola. Photo by [Welcome](https://unsplash.com/@________________1a) on [Unsplash](https://unsplash.com/photos/a-bowl-of-granola-and-yogurt-with-a-spoon-lE3LsH1Tx-I), Unsplash License.
- `iron.webp`: Dry brown lentils in a purple bowl. Photo by [César Hernández](https://unsplash.com/@cesarphoto) on [Unsplash](https://unsplash.com/photos/a-purple-bowl-filled-with-lots-of-food-ezcaFWpDKKM), Unsplash License.
- `magnesium.webp`: Pumpkin seeds. Photo by [CHUTTERSNAP](https://unsplash.com/@chuttersnap) on [Unsplash](https://unsplash.com/photos/J8_U_vokuKk), Unsplash License.
- `potassium.webp`: Ripe bananas. Photo by [Fabrizio Frigeni](https://unsplash.com/@ffrige) on [Unsplash](https://unsplash.com/photos/lcrZgpLTNvU), Unsplash License.
- `sodium.webp`: Salt on a wooden spoon. Photo by [Jason Tuinstra](https://unsplash.com/@wjtuinstra) on [Unsplash](https://unsplash.com/photos/brown-wooden-spoon-4OfaTz6SdYs), Unsplash License.
- `zinc.webp`: Dry chickpeas in a dark pan. Photo by [Karyna Panchenko](https://unsplash.com/@karyna_panchenko) on [Unsplash](https://unsplash.com/photos/5352eOUYay4), Unsplash License.
- `saturated-fat.webp`: Butter block. Photo by [Sorin Gheorghita](https://unsplash.com/@sxtcxtc) on [Unsplash](https://unsplash.com/photos/094mP_CBdpM), Unsplash License.
- `monounsaturated-fat.webp`: Olive oil with olives. Photo by [Mareefe](https://www.pexels.com/@mareefe/) on [Pexels](https://www.pexels.com/photo/photo-of-olives-on-cup-of-olive-oil-1022385/), Pexels License.
- `polyunsaturated-fat.webp`: Walnuts. Photo by [Tom Hermans](https://unsplash.com/@tomhermans) on [Unsplash](https://unsplash.com/photos/3w6qAk35xAg), Unsplash License.
- `trans-fat.webp`: French fries. Photo by [Mitchell Luo](https://unsplash.com/@mitchel3uo) on [Unsplash](https://unsplash.com/photos/a-basket-of-french-fries-sitting-on-top-of-a-wooden-table-ChXHveqrb28), Unsplash License.
- `cholesterol.webp`: Eggs in a carton. Photo by [Debby Hudson](https://unsplash.com/@hudsoncrafted) on [Unsplash](https://unsplash.com/photos/five-eggs-on-brown-carton-tray-ya3a7-6OGXM), Unsplash License.
- `omega-3.webp`: Raw salmon fillet. Photo by [David B Townsend](https://unsplash.com/@dbtownsend) on [Unsplash](https://unsplash.com/photos/flat-lay-photography-of-raw-salmon-fish-fV3zTanbO80), Unsplash License.
- `fiber.webp`: Bowls of oatmeal topped with fresh fruit. Photo by [Brooke Lark](https://unsplash.com/@brookelark) on [Unsplash](https://unsplash.com/photos/two-bowls-of-oatmeal-with-fruits-W9OKrxBqiZA), Unsplash License.
- `sugar.webp`: Sugar cubes. Photo by [Daniel Kraus](https://unsplash.com/@bovender) on [Unsplash](https://unsplash.com/photos/a-pile-of-sugar-cubes-sitting-on-top-of-each-other-TXVntZ190Ao), Unsplash License.
- `added-sugar.webp`: Iced donuts with sprinkles. Photo by [Alexander Grey](https://unsplash.com/@sharonmccutcheon) on [Unsplash](https://unsplash.com/photos/shallow-focus-photography-of-assorted-doughnuts-with-sprinkles-s4RaGIo2eYI), Unsplash License.
- `caffeine.webp`: Cup of black coffee with coffee beans. Photo by [Michael C](https://unsplash.com/@michealcopley03) on [Unsplash](https://unsplash.com/photos/coffee-cup-with-coffee-beans-close-up-photography-A3K6lKG4yKY), Unsplash License.

Guides for the 14 nutrients the food log does not track (charted from Apple Health / Health Connect), same rules:

- `phosphorus.webp`: Hard cheese wedges. Photo by [Monserrat Soldú](https://www.pexels.com/@monserratsoldu/) on [Pexels](https://www.pexels.com/photo/sliced-cheese-and-knife-808196/), Pexels License.
- `chloride.webp`: Tomatoes on the vine. Photo by [Kaboompics.com](https://www.pexels.com/@karola-g/) on [Pexels](https://www.pexels.com/photo/fresh-ripe-red-tomatoes-with-water-drops-4022083/), Pexels License.
- `copper.webp`: Cashew nuts. Photo by [Emma Miller](https://unsplash.com/@littlegemstudio) on [Unsplash](https://unsplash.com/photos/a-pile-of-cashews-sitting-on-top-of-a-wooden-table-juOaQQKFFKo), Unsplash License.
- `manganese.webp`: Hazelnuts. Photo by [Jonas Svidras](https://www.pexels.com/@jonas-svidras/) on [Pexels](https://www.pexels.com/photo/hazelnuts-939955/), Pexels License.
- `selenium.webp`: Brazil nuts at a market. Photo by [Raíssa Lisboa](https://www.pexels.com/@raissa-lisboa-1266934798/) on [Pexels](https://www.pexels.com/photo/abundance-of-nuts-at-bazaar-25067701/), Pexels License.
- `chromium.webp`: Green and purple grapes. Photo by [KWON JUNHO](https://unsplash.com/@juno1412) on [Unsplash](https://unsplash.com/photos/w1pLDJtYjbM), Unsplash License.
- `molybdenum.webp`: Dried red kidney beans. Photo by [Zoshua Colah](https://unsplash.com/@zoshuacolah) on [Unsplash](https://unsplash.com/photos/a-pile-of-dried-red-kidney-beans-QTGbQFlTvb8), Unsplash License.
- `iodine.webp`: Seaweed salad. Photo by [makafood](https://www.pexels.com/@makafood-82669418/) on [Pexels](https://www.pexels.com/photo/meal-with-leaves-on-plate-8956831/), Pexels License.
- `vitamin-b6.webp`: Potatoes. Photo by [Lars Blankers](https://unsplash.com/@lmablankers) on [Unsplash](https://unsplash.com/photos/B0s3Xndk6tw), Unsplash License.
- `thiamin.webp`: Black beans. Photo by [Mikey Frost](https://unsplash.com/@frostyfilmsandphoto) on [Unsplash](https://unsplash.com/photos/Rq9eUgGy_eA), Unsplash License.
- `riboflavin.webp`: Glass of milk. Photo by [an_vision](https://unsplash.com/@anvision) on [Unsplash](https://unsplash.com/photos/5SN5N5-JM3c), Unsplash License.
- `niacin.webp`: Peanuts in their shells. Photo by [Isai Dzib](https://unsplash.com/@isaidzib) on [Unsplash](https://unsplash.com/photos/TysS85XkjgI), Unsplash License.
- `biotin.webp`: Sunflower seeds. Photo by [engin akyurt](https://unsplash.com/@enginakyurt) on [Unsplash](https://unsplash.com/photos/a-close-up-of-a-pile-of-sunflower-seeds-Xku7nuvaM4A), Unsplash License.
- `pantothenic-acid.webp`: Halved avocado. Photo by [John Vid](https://unsplash.com/@vanvid) on [Unsplash](https://unsplash.com/photos/an-avocado-cut-in-half-on-a-table-xPpq_ylNNMs), Unsplash License.
