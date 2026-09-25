# Ayuvo brand

## The mark — "Ayuvo Peak"

An open ring (wholeness, one place, a health ring) with a single bold peak rising through the
opening at 12 o'clock: the "A" of Ayuvo, an upward trend line and a pulse crest in one gesture.
Two round-capped strokes, no fills, so it survives 16 px favicons, the watch mask and monochrome
contexts.

Geometry (1024 × 1024 design grid, `scripts/brand/ayuvo_mark.py` is the source of truth):

- Ring: centre (512, 544), centreline radius 352, stroke 112, opening of 72° centred on 12 o'clock.
- Peak: polyline (336, 640) → (512, 152) → (688, 640), round joins and caps.
- Ink bounding box incl. caps: x 104–920, y 96–952 (816 × 856). Optical centre (512, 524) — the mark
  is placed by its optical centre, never the canvas centre.
- Clear space around the mark = one stroke width. `test_render.py` asserts the cap-to-leg,
  apex-to-cap and leg-end-to-ring clearances so nobody can nudge the geometry into a collision.
- Favicon variant (`SMALL`): stroke 140, opening 84°, for 16/32 px.

## Colour

- Primary "Rose" = the app's default accent, `#FF375F → #FF6B8A` (135°). All 18 accent tints in
  `tints.json` are shared verbatim with iOS (`Views/Theme.swift`) and Android (`ui/theme/Color.kt`).
- Icon background ink `#0F1412`, constant across tints (Android `ic_launcher_background`).
- **Website:** the mark is the Blue tint (`#0A84FF → #5EAEFF`) on pale sky `#EEF6FD` (`WEB_TINT` / `WEB_BG` in `render_icons.py`); the apps, store icons and Play graphics keep Rose.
- Secondary for charts and web accents: Teal `#30B0C7`, used sparingly.
- Monochrome: ink on paper (`#0F1412` on `#F5F2EC`) and white on ink. iOS "tinted" appearance uses a
  greyscale mark on transparent.

## Sizing per target

| Target | Mark height | Background |
|--------|-------------|------------|
| iOS / watchOS / Play / App Store / marketing icons | 62 % of canvas | ink (dark iOS variant: transparent) |
| Android adaptive foreground (108 dp canvas) | 58 % (inside the 66 dp safe circle) | transparent |
| Android legacy launcher (48–192 px) | 60 % | ink, rounded square / circle |
| Android `ic_logo*` (splash + About) and iOS `onboardingLogo` | 92 % | transparent |
| Favicon 16/32/48 | 84 % (`SMALL` geometry) | transparent |

## Wordmark and lockup

"Ayuvo" set in Fraunces (OFL, `brand/fonts/`), weight 600, optical size 72, SOFT 50, WONK 0,
tracking −0.02 em, title case. Lockup: mark left, wordmark baseline aligned to the ring's horizontal
centreline, gap = 0.45 × mark height. In-app the name is always system-font text, never an image.
`ayuvo-wordmark.svg` / `ayuvo-lockup.svg` reference the Fraunces family by name (no outlining
toolchain in the repo); the site self-hosts the font so they render correctly there.

## Files

- `ayuvo-mark.svg` (currentColor, tight box), `ayuvo-mark-small.svg`, `ayuvo-mark-rose.svg`,
  `ayuvo-wordmark.svg`, `ayuvo-lockup.svg` — generated, do not edit by hand.
- `tints.json` — 18 tints: key, display name, Android resource suffix, iOS app-icon set, start/end hex.
- `fonts/` — Fraunces, Manrope, JetBrains Mono variable TTFs + OFL licences (used by the Pillow
  compositor; the website uses the woff2 subsets under `web/assets/fonts/`).

## Pipeline

```bash
pip install -r scripts/brand/requirements.txt        # Pillow only
python3 scripts/brand/render_icons.py --targets all    # android, ios, web, marketing, brand
python3 scripts/brand/render_icons.py --check          # CI drift guard (byte-identical re-render)
python3 scripts/brand/test_render.py                   # geometry + size invariants
python3 scripts/brand/compose_marketing.py             # store / web screenshot composites
```

`render_icons.py` writes every launcher/app icon, the adaptive-icon XMLs, the Android
`ic_launcher_background` colour, the web favicons and OG image, and the store/marketing sets, and
records each raster's expected size in `marketing/MANIFEST.json`. Proof sheet:
`marketing/proofs/mark-sizes.png`.
