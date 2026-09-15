#!/usr/bin/env python3
"""Render every Ayuvo brand raster / vector asset from `ayuvo_mark.py` + `brand/tints.json`.

Targets
  android    launcher icons (legacy + round + adaptive foreground) for all tints, the
             36 adaptive-icon XMLs, `drawable-nodpi/ic_logo*.png`, and the
             `ic_launcher_background` colour in `values/colors.xml`
  ios        18 `AppIcon*.appiconset` (light / dark / tinted 1024 PNGs + Contents.json),
             the watchOS AppIcon and the `onboardingLogo` imageset
  web        `web/assets/brand/*` (SVG mark, favicons, touch icon) + `web/assets/opengraph.jpg`
  marketing  `marketing/logo`, `marketing/play`, `marketing/appstore`, `marketing/web`
  brand      the SVG sources under `brand/`

Every output is byte-stable, so `--check` re-renders to memory and fails when a
file on disk differs (guards against hand-edited PNGs). `marketing/MANIFEST.json`
lists every raster with its expected (width, height, mode) for the dimension check.

Usage
  python3 scripts/brand/render_icons.py --targets all
  python3 scripts/brand/render_icons.py --targets android,ios --tints rose,blue
  python3 scripts/brand/render_icons.py --check          # CI drift guard
  python3 scripts/brand/render_icons.py --dry-run        # list outputs only
"""

from __future__ import annotations

import argparse
import io
import json
import re
import sys
from pathlib import Path

from PIL import Image, ImageDraw

sys.path.insert(0, str(Path(__file__).resolve().parent))
import ayuvo_mark as mark  # noqa: E402
import compose_marketing as marketing  # noqa: E402

ROOT = Path(__file__).resolve().parents[2]
BRAND = ROOT / "brand"
TINTS_FILE = BRAND / "tints.json"

ANDROID_RES = ROOT / "android/app/src/main/res"
IOS_ASSETS = ROOT / "ios/calorietracker/Assets.xcassets"
WATCH_ASSETS = ROOT / "ios/FudAIWatchApp/Assets.xcassets"
WEB = ROOT / "web"
MARKETING = ROOT / "marketing"

# Android density table (legacy launcher px, adaptive layer px).
DENSITIES = {
    "mdpi": (48, 108),
    "hdpi": (72, 162),
    "xhdpi": (96, 216),
    "xxhdpi": (144, 324),
    "xxxhdpi": (192, 432),
}

# Mark height as a fraction of the canvas per target family.
FRACTION_ICON = 0.62          # iOS / watch / Play / marketing icons (ink background)
FRACTION_ANDROID_LEGACY = 0.60
FRACTION_ANDROID_FOREGROUND = 0.58   # inside the 66/108 dp safe circle
FRACTION_TRANSPARENT_LOGO = 0.92     # full-bleed logos on transparent (splash, onboarding)
FRACTION_FAVICON = 0.84

PNG_OPTS = {"format": "PNG", "optimize": True, "compress_level": 9}


class Outputs:
    """Collects rendered files; writes them or diffs them against disk."""

    def __init__(self, mode: str):
        self.mode = mode  # write | check | dry-run
        self.files: dict[Path, bytes] = {}
        self.manifest: dict[str, list] = {}
        self.diffs: list[str] = []

    def add_image(self, path: Path, image: Image.Image, **save_opts):
        buf = io.BytesIO()
        opts = dict(PNG_OPTS) if path.suffix == ".png" else {}
        if path.suffix in (".jpg", ".jpeg"):
            opts = {"format": "JPEG", "quality": 85, "optimize": True, "progressive": True}
        if path.suffix == ".ico":
            opts = {"format": "ICO", "sizes": save_opts.pop("sizes")}
        opts.update(save_opts)
        image.save(buf, **opts)
        self.files[path] = buf.getvalue()
        if path.suffix != ".ico":
            self.manifest[str(path.relative_to(ROOT))] = [image.width, image.height, image.mode]

    def add_text(self, path: Path, text: str):
        self.files[path] = text.encode("utf-8")

    def flush(self) -> int:
        manifest_path = MARKETING / "MANIFEST.json"
        if self.manifest:
            existing = {}
            if manifest_path.exists():
                try:
                    existing = json.loads(manifest_path.read_text())
                except json.JSONDecodeError:
                    existing = {}
            merged = dict(existing)
            merged.update(self.manifest)
            merged = dict(sorted(merged.items()))
            self.add_text(manifest_path, json.dumps(merged, indent=2) + "\n")
        for path, data in sorted(self.files.items()):
            rel = path.relative_to(ROOT)
            if self.mode == "dry-run":
                print(f"would write {rel} ({len(data)} bytes)")
                continue
            if self.mode == "check":
                if not path.exists():
                    self.diffs.append(f"missing: {rel}")
                elif path.read_bytes() != data:
                    self.diffs.append(f"differs: {rel}")
                continue
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(data)
        if self.mode == "check":
            for d in self.diffs:
                print(d)
            print(f"checked {len(self.files)} files, {len(self.diffs)} differences")
            return 1 if self.diffs else 0
        if self.mode == "write":
            print(f"wrote {len(self.files)} files")
        return 0


def load_tints() -> dict:
    data = json.loads(TINTS_FILE.read_text())
    for t in data["tints"]:
        t["gradient"] = (t["start"], t["end"])
    return data


def rounded_mask(size: int, radius_fraction: float) -> Image.Image:
    ss = 4
    big = size * ss
    m = Image.new("L", (big, big), 0)
    ImageDraw.Draw(m).rounded_rectangle([0, 0, big - 1, big - 1], radius=int(big * radius_fraction), fill=255)
    return m.resize((size, size), Image.Resampling.LANCZOS)


def circle_mask(size: int) -> Image.Image:
    ss = 4
    big = size * ss
    m = Image.new("L", (big, big), 0)
    ImageDraw.Draw(m).ellipse([0, 0, big - 1, big - 1], fill=255)
    return m.resize((size, size), Image.Resampling.LANCZOS)


def with_shape(image_rgb: Image.Image, shape_mask: Image.Image) -> Image.Image:
    out = Image.new("RGBA", image_rgb.size, (0, 0, 0, 0))
    out.paste(image_rgb, (0, 0), shape_mask)
    return out


# --------------------------------------------------------------------------------------
# Targets
# --------------------------------------------------------------------------------------


def render_brand(out: Outputs, data: dict):
    rose = next(t for t in data["tints"] if t["key"] == data["default"])
    out.add_text(BRAND / "ayuvo-mark.svg", mark.svg(None))
    out.add_text(BRAND / "ayuvo-mark-small.svg", mark.svg(None, geometry=mark.SMALL))
    out.add_text(BRAND / "ayuvo-mark-rose.svg", mark.svg(rose["gradient"]))
    out.add_text(BRAND / "ayuvo-wordmark.svg", marketing.wordmark_svg())
    out.add_text(BRAND / "ayuvo-lockup.svg", marketing.lockup_svg(rose["gradient"]))


def render_android(out: Outputs, data: dict, tint_keys: set[str] | None):
    bg = data["background"]
    for t in data["tints"]:
        if tint_keys and t["key"] not in tint_keys:
            continue
        suffix = t["android_suffix"]
        base = "ic_launcher" if not suffix else f"ic_launcher_{suffix}"
        fg_name = f"{base}_foreground"
        for density, (legacy_px, adaptive_px) in DENSITIES.items():
            d = ANDROID_RES / f"mipmap-{density}"
            legacy_rgb = mark.render(legacy_px, FRACTION_ANDROID_LEGACY, t["gradient"], bg)
            out.add_image(d / f"{base}.png", with_shape(legacy_rgb, rounded_mask(legacy_px, 0.18)))
            out.add_image(d / f"{base}_round.png", with_shape(legacy_rgb, circle_mask(legacy_px)))
            out.add_image(d / f"{fg_name}.png", mark.render(adaptive_px, FRACTION_ANDROID_FOREGROUND, t["gradient"], None))
        anydpi = ANDROID_RES / "mipmap-anydpi-v26"
        out.add_text(anydpi / f"{base}.xml", adaptive_icon_xml(fg_name))
        out.add_text(anydpi / f"{base}_round.xml", adaptive_icon_xml(fg_name))
        if t["key"] in data["android_logo_tints"]:
            logo_name = "ic_logo" if not suffix else f"ic_logo_{suffix}"
            out.add_image(ANDROID_RES / "drawable-nodpi" / f"{logo_name}.png",
                          mark.render(1024, FRACTION_TRANSPARENT_LOGO, t["gradient"], None))
    # Launcher background colour lives in colors.xml (kept in sync with brand/tints.json).
    colors = ANDROID_RES / "values/colors.xml"
    if colors.exists():
        text = colors.read_text()
        argb = "#FF" + bg.lstrip("#").upper()
        new = re.sub(r'(<color name="ic_launcher_background">)#[0-9A-Fa-f]{6,8}(</color>)', rf"\g<1>{argb}\g<2>", text)
        out.add_text(colors, new)


def adaptive_icon_xml(foreground: str) -> str:
    return (
        '<?xml version="1.0" encoding="utf-8"?>\n'
        '<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n'
        '    <background android:drawable="@color/ic_launcher_background" />\n'
        f'    <foreground android:drawable="@mipmap/{foreground}" />\n'
        '</adaptive-icon>\n'
    )


def ios_contents(light: str, dark: str, tinted: str) -> str:
    doc = {
        "images": [
            {"filename": light, "idiom": "universal", "platform": "ios", "size": "1024x1024"},
            {"appearances": [{"appearance": "luminosity", "value": "dark"}],
             "filename": dark, "idiom": "universal", "platform": "ios", "size": "1024x1024"},
            {"appearances": [{"appearance": "luminosity", "value": "tinted"}],
             "filename": tinted, "idiom": "universal", "platform": "ios", "size": "1024x1024"},
        ],
        "info": {"author": "xcode", "version": 1},
    }
    return json.dumps(doc, indent=2) + "\n"


def render_ios(out: Outputs, data: dict, tint_keys: set[str] | None):
    bg = data["background"]
    for t in data["tints"]:
        if tint_keys and t["key"] not in tint_keys:
            continue
        set_dir = IOS_ASSETS / f"{t['ios_appiconset']}.appiconset"
        slug = "appicon" if not t["android_suffix"] else "appicon-" + t["android_suffix"].replace("_", "-")
        light, dark, tinted = f"{slug}.png", f"{slug}-dark.png", f"{slug}-tinted.png"
        out.add_image(set_dir / light, mark.render(1024, FRACTION_ICON, t["gradient"], bg))
        out.add_image(set_dir / dark, mark.render(1024, FRACTION_ICON, t["gradient"], None))
        out.add_image(set_dir / tinted, mark.render(1024, FRACTION_ICON, None, None, greyscale=True))
        out.add_text(set_dir / "Contents.json", ios_contents(light, dark, tinted))
    if not tint_keys or data["default"] in tint_keys:
        rose = next(t for t in data["tints"] if t["key"] == data["default"])
        out.add_image(WATCH_ASSETS / "AppIcon.appiconset/appicon.png", mark.render(1024, FRACTION_ICON, rose["gradient"], bg))
        logo_dir = IOS_ASSETS / "onboardingLogo.imageset"
        out.add_image(logo_dir / "logo@2x.png", mark.render(600, FRACTION_TRANSPARENT_LOGO, rose["gradient"], None))
        out.add_image(logo_dir / "logo@3x.png", mark.render(900, FRACTION_TRANSPARENT_LOGO, rose["gradient"], None))


def render_web(out: Outputs, data: dict):
    rose = next(t for t in data["tints"] if t["key"] == data["default"])
    bg = data["background"]
    brand_dir = WEB / "assets/brand"
    out.add_text(brand_dir / "ayuvo-mark.svg", mark.svg(rose["gradient"]))
    out.add_text(brand_dir / "favicon.svg", mark.svg(rose["gradient"], geometry=mark.SMALL, size=64, mark_height_fraction=FRACTION_FAVICON))
    fav = {s: mark.render(s, FRACTION_FAVICON, rose["gradient"], None, geometry=mark.SMALL) for s in (16, 32, 48)}
    ico = Image.new("RGBA", (48, 48), (0, 0, 0, 0))
    ico.paste(fav[48], (0, 0))
    out.add_image(brand_dir / "favicon.ico", ico, sizes=[(16, 16), (32, 32), (48, 48)])
    out.add_image(brand_dir / "favicon-32.png", fav[32])
    out.add_image(brand_dir / "apple-touch-icon.png", mark.render(180, FRACTION_ICON, rose["gradient"], bg))
    out.add_image(brand_dir / "logo-192.png", with_shape(mark.render(192, FRACTION_ICON, rose["gradient"], bg), rounded_mask(192, 0.22)))
    out.add_image(brand_dir / "logo-512.png", with_shape(mark.render(512, FRACTION_ICON, rose["gradient"], bg), rounded_mask(512, 0.22)))
    out.add_image(WEB / "assets/opengraph.jpg", marketing.opengraph(rose["gradient"], bg))
    out.add_text(brand_dir / "ayuvo-lockup.svg", marketing.lockup_svg(rose["gradient"]))


def render_marketing(out: Outputs, data: dict):
    rose = next(t for t in data["tints"] if t["key"] == data["default"])
    bg = data["background"]
    paper = marketing.PAPER
    logo = MARKETING / "logo"
    out.add_image(logo / "ayuvo-logo-2048.png", mark.render(2048, FRACTION_ICON, rose["gradient"], bg))
    out.add_image(logo / "ayuvo-logo-2048-transparent.png", mark.render(2048, FRACTION_TRANSPARENT_LOGO, rose["gradient"], None))
    out.add_image(logo / "ayuvo-mono-ink.png", mark.render(2048, FRACTION_TRANSPARENT_LOGO, bg, paper))
    out.add_image(logo / "ayuvo-mono-white.png", mark.render(2048, FRACTION_TRANSPARENT_LOGO, "#FFFFFF", None))
    play = MARKETING / "play"
    out.add_image(play / "icon-512.png", mark.render(512, FRACTION_ICON, rose["gradient"], bg))
    out.add_image(play / "feature-graphic-1024x500.png", marketing.feature_graphic(rose["gradient"], bg))
    out.add_image(play / "developer-header-4096x2304.jpg", marketing.developer_header(rose["gradient"], bg))
    appstore = MARKETING / "appstore"
    out.add_image(appstore / "icon-1024.png", mark.render(1024, FRACTION_ICON, rose["gradient"], bg))
    out.add_image(appstore / "watch-icon-1024.png", mark.render(1024, FRACTION_ICON, rose["gradient"], bg))
    out.add_image(MARKETING / "web/opengraph-1200x630.jpg", marketing.opengraph(rose["gradient"], bg))
    out.add_image(MARKETING / "proofs/mark-sizes.png", marketing.contact_sheet(rose["gradient"], bg))


# --------------------------------------------------------------------------------------


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--targets", default="all", help="comma list: brand,android,ios,web,marketing,all")
    ap.add_argument("--tints", default="all", help="comma list of tint keys (android/ios only), or all")
    ap.add_argument("--check", action="store_true", help="re-render and diff against files on disk")
    ap.add_argument("--dry-run", action="store_true", help="list outputs without writing")
    args = ap.parse_args(argv)

    targets = {t.strip() for t in args.targets.split(",")}
    if "all" in targets:
        targets = {"brand", "android", "ios", "web", "marketing"}
    tint_keys = None if args.tints == "all" else {k.strip() for k in args.tints.split(",")}

    mode = "check" if args.check else ("dry-run" if args.dry_run else "write")
    out = Outputs(mode)
    data = load_tints()
    if "brand" in targets:
        render_brand(out, data)
    if "android" in targets:
        render_android(out, data, tint_keys)
    if "ios" in targets:
        render_ios(out, data, tint_keys)
    if "web" in targets:
        render_web(out, data)
    if "marketing" in targets:
        render_marketing(out, data)
    return out.flush()


if __name__ == "__main__":
    raise SystemExit(main())
