#!/usr/bin/env python3
"""Per-page social cards (1200x630) for the website: web/assets/og/<slug>.jpg.

Built from the redacted captures in marketing/raw/ios-real/ (scripts/web/redact_screens.py) and
the page titles in scripts/web/pages.py, using the brand lockup and fonts from
scripts/brand/compose_marketing.py. Re-run after changing a page headline or a screenshot:

    python3 scripts/web/social_cards.py
"""
from __future__ import annotations

import sys
from pathlib import Path

from PIL import Image, ImageDraw, ImageFilter

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "scripts" / "brand"))
sys.path.insert(0, str(ROOT / "scripts" / "web"))

import compose_marketing as cm  # noqa: E402
import pages as pages_mod  # noqa: E402

RAW = ROOT / "marketing" / "raw" / "ios-real"
OUT = ROOT / "web" / "assets" / "og"
BLUE = ("#0A84FF", "#5EAEFF")  # the website mark: the "blue" tint in brand/tints.json (render_icons.WEB_TINT)
LIGHT = "#EEF6FD"
TEXT = (15, 42, 71)
W, H = 1200, 630


def phone_layer(shot: Image.Image, width: int, angle: float) -> Image.Image:
    height = int(width * shot.height / shot.width)
    shot = shot.resize((width, height), Image.Resampling.LANCZOS).convert("RGBA")
    radius = int(width * 0.115)
    mask = Image.new("L", shot.size, 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, width - 1, height - 1], radius=radius, fill=255)
    bez = 9
    frame = Image.new("RGBA", (width + 2 * bez, height + 2 * bez), (0, 0, 0, 0))
    fm = Image.new("L", frame.size, 0)
    ImageDraw.Draw(fm).rounded_rectangle([0, 0, frame.width - 1, frame.height - 1], radius=radius + bez, fill=255)
    frame.paste((44, 50, 47, 255), (0, 0), fm)
    frame.paste(shot, (bez, bez), mask)
    shadow = Image.new("RGBA", (frame.width + 120, frame.height + 120), (0, 0, 0, 0))
    sm = Image.new("L", shadow.size, 0)
    ImageDraw.Draw(sm).rounded_rectangle([60, 70, 60 + frame.width, 70 + frame.height], radius=radius + bez, fill=95)
    shadow.putalpha(sm.filter(ImageFilter.GaussianBlur(28)))
    shadow.paste(frame, (60, 60), frame)
    return shadow.rotate(angle, expand=True, resample=Image.Resampling.BICUBIC)


def background() -> Image.Image:
    img = Image.new("RGB", (W, H), LIGHT)
    glow = Image.new("RGB", (W, H), LIGHT)
    ImageDraw.Draw(glow).ellipse([640, -300, 1500, 520], fill=(150, 200, 250))
    ImageDraw.Draw(glow).ellipse([820, 300, 1400, 800], fill=(150, 225, 215))
    glow = glow.filter(ImageFilter.GaussianBlur(120))
    return Image.blend(img, glow, 0.85)


def card(page) -> Image.Image:
    img = background()
    draw = ImageDraw.Draw(img)
    cm.draw_lockup(img, 64, 56, 54, BLUE, text_colour=TEXT)
    font = cm.display_font(66, 600)
    lines = cm._wrap(draw, page.og_headline or page.title, font, 560)[:4]
    y = 190
    for line in lines:
        draw.text((64, y), line, font=font, fill=TEXT)
        y += 78
    draw.text((64, 552), "PRIVATE BY DESIGN · NO ACCOUNT · OPEN SOURCE", font=cm.mono_font(17, 600), fill=(11, 87, 194))
    shots = [Image.open(RAW / f"{s}.png").convert("RGB") for s in page.og_screens[:2] if (RAW / f"{s}.png").exists()]
    if not shots:
        shots = [Image.open(RAW / "summary.png").convert("RGB")]
    if len(shots) == 1:
        layer = phone_layer(shots[0], 300, -6)
        img.paste(layer, (860 - layer.width // 2 + 80, 90), layer)
    else:
        a = phone_layer(shots[0], 270, -7)
        b = phone_layer(shots[1], 270, 5)
        img.paste(a, (700, 130), a)
        img.paste(b, (900, 60), b)
    return img


def main() -> int:
    OUT.mkdir(parents=True, exist_ok=True)
    made = 0
    for page in pages_mod.all_pages():
        if not page.in_sitemap:
            continue
        card(page).save(OUT / f"{page.slug}.jpg", quality=88, optimize=True, progressive=True)
        made += 1
    print(f"wrote {made} cards to {OUT.relative_to(ROOT)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
