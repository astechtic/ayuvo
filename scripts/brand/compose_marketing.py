#!/usr/bin/env python3
"""Marketing composites for Ayuvo: OG image, Play feature graphic, developer header,
store screenshot composites and screenshot placeholders.

Everything is drawn with Pillow from `ayuvo_mark.py` and the self-hosted OFL fonts in
`brand/fonts/`. Outputs are byte-stable so `render_icons.py --check` can diff them.

Usage (standalone)
  python3 scripts/brand/compose_marketing.py --storyboard marketing/storyboard.json
    Builds `marketing/store/**` and `web/assets/screenshots/*` composites from
    `marketing/raw/{ios,android}/*.png`. Missing raw captures produce clearly labelled
    placeholder frames at the right dimensions so the site validates before the real
    screenshots exist.
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

sys.path.insert(0, str(Path(__file__).resolve().parent))
import ayuvo_mark as mark  # noqa: E402

ROOT = Path(__file__).resolve().parents[2]
FONTS = ROOT / "brand/fonts"
MARKETING = ROOT / "marketing"
WEB = ROOT / "web"

INK = "#0F1412"
INK_2 = "#151B18"
PAPER = "#F5F2EC"
PAPER_DIM = (245, 242, 236, 160)
ACCENT_2 = "#30B0C7"

TAGLINE = "Your whole health, in one place."
SUBLINE = "Nutrition · Workouts · Health data · Fasting · Water · AI coach — private, on your device."


# --------------------------------------------------------------------------------------
# Fonts
# --------------------------------------------------------------------------------------


def _font(name: str, size: int, axes: list[float] | None = None) -> ImageFont.FreeTypeFont:
    path = FONTS / name
    font = ImageFont.truetype(str(path), size)
    if axes:
        try:
            font.set_variation_by_axes(axes)
        except (OSError, AttributeError):
            pass
    return font


def display_font(size: int, weight: float = 600.0) -> ImageFont.FreeTypeFont:
    # Fraunces axes order in Pillow: opsz, wght, SOFT, WONK
    return _font("Fraunces[SOFT,WONK,opsz,wght].ttf", size, [min(144, max(9, size / 1.6)), weight, 50, 0])


def body_font(size: int, weight: float = 500.0) -> ImageFont.FreeTypeFont:
    return _font("Manrope[wght].ttf", size, [weight])


def mono_font(size: int, weight: float = 500.0) -> ImageFont.FreeTypeFont:
    return _font("JetBrainsMono[wght].ttf", size, [weight])


def _text_width(draw: ImageDraw.ImageDraw, text: str, font) -> float:
    box = draw.textbbox((0, 0), text, font=font)
    return box[2] - box[0]


# --------------------------------------------------------------------------------------
# Lockup helpers
# --------------------------------------------------------------------------------------


def paste_mark(canvas: Image.Image, size: int, xy: tuple[int, int], fill, background=None):
    """Paste the mark (mark height = 0.92 * size) with its top-left at xy."""
    img = mark.render(size, 0.92, fill, background)
    if img.mode == "RGBA":
        canvas.paste(img, xy, img)
    else:
        canvas.paste(img, xy)


def draw_lockup(canvas: Image.Image, x: int, y: int, mark_px: int, fill, text_colour=PAPER,
                gap_fraction: float = 0.45) -> int:
    """Mark + 'Ayuvo' wordmark; returns the x where the lockup ends."""
    draw = ImageDraw.Draw(canvas)
    paste_mark(canvas, mark_px, (x, y), fill)
    font = display_font(int(mark_px * 0.78), 600)
    tx = x + mark_px + int(mark_px * gap_fraction)
    box = draw.textbbox((0, 0), "Ayuvo", font=font)
    text_h = box[3] - box[1]
    ty = y + (mark_px - text_h) // 2 - box[1]
    draw.text((tx, ty), "Ayuvo", font=font, fill=text_colour)
    return tx + (box[2] - box[0])


def wordmark_svg() -> str:
    return (
        '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 640 200" width="640" height="200" role="img" aria-label="Ayuvo">'
        '<title>Ayuvo</title>'
        '<text x="0" y="150" font-family="Fraunces, \'Iowan Old Style\', Georgia, serif" font-weight="600" '
        'font-size="168" letter-spacing="-3" fill="currentColor" style="font-variation-settings: \'opsz\' 72, \'SOFT\' 50, \'WONK\' 0">Ayuvo</text>'
        '</svg>\n'
    )


def lockup_svg(fill: tuple[str, str]) -> str:
    inner = mark.svg(fill, size=200, mark_height_fraction=0.92)
    # embed the mark as a nested svg and add the wordmark to the right
    return (
        '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 860 200" width="860" height="200" role="img" aria-label="Ayuvo">'
        '<title>Ayuvo</title>'
        f'<svg x="0" y="0" width="200" height="200" viewBox="0 0 200 200">{inner[inner.index(">") + 1:].replace("</svg>", "")}</svg>'
        '<text x="250" y="150" font-family="Fraunces, \'Iowan Old Style\', Georgia, serif" font-weight="600" '
        'font-size="156" letter-spacing="-3" fill="currentColor" style="font-variation-settings: \'opsz\' 72, \'SOFT\' 50, \'WONK\' 0">Ayuvo</text>'
        '</svg>\n'
    )


# --------------------------------------------------------------------------------------
# Composites
# --------------------------------------------------------------------------------------


def _wrap(draw, text: str, font, max_width: int) -> list[str]:
    words = text.split()
    lines, cur = [], ""
    for w in words:
        trial = (cur + " " + w).strip()
        if _text_width(draw, trial, font) <= max_width or not cur:
            cur = trial
        else:
            lines.append(cur)
            cur = w
    if cur:
        lines.append(cur)
    return lines


def opengraph(fill: tuple[str, str], background: str = INK) -> Image.Image:
    w, h = 1200, 630
    img = Image.new("RGB", (w, h), background)
    draw = ImageDraw.Draw(img)
    end_x = draw_lockup(img, 80, 96, 150, fill)
    tag_font = display_font(60, 500)
    draw.text((80, 300), TAGLINE, font=tag_font, fill=PAPER)
    sub_font = body_font(28, 500)
    for i, line in enumerate(_wrap(draw, SUBLINE, sub_font, 1040)):
        draw.text((80, 400 + i * 40), line, font=sub_font, fill=(245, 242, 236, 190))
    foot = body_font(22, 600)
    draw.text((80, 548), "iPhone · Android · No account · Bring your own AI key", font=foot, fill=ACCENT_2)
    # hairline
    draw.line([(80, 520), (w - 80, 520)], fill=(245, 242, 236, 40), width=1)
    return img


def feature_graphic(fill: tuple[str, str], background: str = INK) -> Image.Image:
    w, h = 1024, 500
    img = Image.new("RGB", (w, h), background)
    draw = ImageDraw.Draw(img)
    draw_lockup(img, 64, 70, 130, fill)
    tag_font = display_font(46, 500)
    draw.text((64, 250), TAGLINE, font=tag_font, fill=PAPER)
    sub_font = body_font(22, 500)
    for i, line in enumerate(_wrap(draw, SUBLINE, sub_font, 880)):
        draw.text((64, 330 + i * 32), line, font=sub_font, fill=(245, 242, 236, 190))
    draw.text((64, 430), "Private · On-device · BYOK", font=body_font(20, 700), fill=ACCENT_2)
    return img


def developer_header(fill: tuple[str, str], background: str = INK) -> Image.Image:
    w, h = 4096, 2304
    img = Image.new("RGB", (w, h), background)
    draw = ImageDraw.Draw(img)
    mark_px = 720
    x = (w - (mark_px + int(mark_px * 0.45) + 1560)) // 2
    end_x = draw_lockup(img, x, 560, mark_px, fill)
    tag_font = display_font(150, 500)
    tw = _text_width(draw, TAGLINE, tag_font)
    draw.text(((w - tw) // 2, 1420), TAGLINE, font=tag_font, fill=PAPER)
    sub = "Nutrition · Workouts · Health data · Fasting · Water · AI coach"
    sub_font = body_font(64, 500)
    sw = _text_width(draw, sub, sub_font)
    draw.text(((w - sw) // 2, 1640), sub, font=sub_font, fill=(245, 242, 236, 180))
    return img


def contact_sheet(fill: tuple[str, str], background: str = INK) -> Image.Image:
    """Proof sheet of the mark at the sizes that matter."""
    sizes = [16, 32, 48, 108, 180, 512, 1024]
    pad = 40
    total_w = sum(min(s, 512) for s in sizes) + pad * (len(sizes) + 1)
    h = 512 + pad * 2 + 60
    img = Image.new("RGB", (total_w, h), PAPER)
    draw = ImageDraw.Draw(img)
    x = pad
    label = mono_font(18, 500)
    for s in sizes:
        geom = mark.SMALL if s <= 32 else mark.DEFAULT
        frac = 0.84 if s <= 48 else 0.62
        tile = mark.render(s, frac, fill, background, geometry=geom)
        shown = tile.resize((512, 512), Image.Resampling.NEAREST) if s == 1024 else tile
        img.paste(shown, (x, pad + (512 - shown.height) // 2))
        draw.text((x, pad + 512 + 16), f"{s}px", font=label, fill=INK)
        x += shown.width + pad
    return img


# --------------------------------------------------------------------------------------
# Screenshots: placeholders + composites
# --------------------------------------------------------------------------------------


def placeholder_screenshot(size: tuple[int, int], title: str, note: str) -> Image.Image:
    w, h = size
    img = Image.new("RGB", (w, h), INK_2)
    draw = ImageDraw.Draw(img)
    paste_mark(img, int(w * 0.28), ((w - int(w * 0.28)) // 2, int(h * 0.22)), ("#FF375F", "#FF6B8A"))
    f1 = display_font(int(w * 0.055), 600)
    f2 = body_font(int(w * 0.03), 500)
    tw = _text_width(draw, title, f1)
    draw.text(((w - tw) // 2, int(h * 0.55)), title, font=f1, fill=PAPER)
    for i, line in enumerate(_wrap(draw, note, f2, int(w * 0.8))):
        lw = _text_width(draw, line, f2)
        draw.text(((w - lw) // 2, int(h * 0.63) + i * int(w * 0.045)), line, font=f2, fill=(245, 242, 236, 170))
    return img


def store_composite(screenshot: Image.Image, caption: str, size: tuple[int, int]) -> Image.Image:
    """Device-style composite: caption on top, screenshot in a rounded frame."""
    w, h = size
    img = Image.new("RGB", (w, h), INK)
    draw = ImageDraw.Draw(img)
    cap_font = display_font(int(w * 0.062), 600)
    lines = _wrap(draw, caption, cap_font, int(w * 0.86))
    y = int(h * 0.05)
    for line in lines:
        lw = _text_width(draw, line, cap_font)
        draw.text(((w - lw) // 2, y), line, font=cap_font, fill=PAPER)
        y += int(w * 0.075)
    top = y + int(h * 0.02)
    frame_h = h - top - int(h * 0.02)
    frame_w = int(frame_h * screenshot.width / screenshot.height)
    if frame_w > int(w * 0.88):
        frame_w = int(w * 0.88)
        frame_h = int(frame_w * screenshot.height / screenshot.width)
    shot = screenshot.resize((frame_w, frame_h), Image.Resampling.LANCZOS)
    radius = int(frame_w * 0.11)
    m = Image.new("L", shot.size, 0)
    ImageDraw.Draw(m).rounded_rectangle([0, 0, frame_w - 1, frame_h - 1], radius=radius, fill=255)
    x = (w - frame_w) // 2
    bezel = Image.new("RGB", (frame_w + 24, frame_h + 24), (40, 44, 42))
    bm = Image.new("L", bezel.size, 0)
    ImageDraw.Draw(bm).rounded_rectangle([0, 0, bezel.width - 1, bezel.height - 1], radius=radius + 12, fill=255)
    img.paste(bezel, (x - 12, top - 12), bm)
    img.paste(shot, (x, top), m)
    return img


def build_storyboard(storyboard_path: Path, write=True) -> list[Path]:
    sb = json.loads(storyboard_path.read_text())
    written: list[Path] = []
    for platform, spec in sb["platforms"].items():
        raw_dir = ROOT / spec["raw_dir"]
        raw_size = tuple(spec["raw_size"])
        for n, screen in enumerate(sb["screens"], start=1):
            raw_path = raw_dir / f"{n:02d}-{screen['id']}.png"
            if raw_path.exists():
                shot = Image.open(raw_path).convert("RGB")
            else:
                shot = placeholder_screenshot(raw_size, screen["title"], f"Screenshot pending: capture {platform} {screen['id']} per marketing/seed/README.md")
                if write and spec.get("write_placeholders", True):
                    raw_path.parent.mkdir(parents=True, exist_ok=True)
                    shot.save(raw_path, format="PNG", optimize=True)
                    written.append(raw_path)
            for out_spec in spec["outputs"]:
                out_path = ROOT / out_spec["dir"] / f"{n:02d}-{screen['id']}.png"
                size = tuple(out_spec["size"])
                if out_spec.get("kind") == "composite":
                    img = store_composite(shot, screen["caption"], size)
                else:
                    img = shot.resize(size, Image.Resampling.LANCZOS) if shot.size != size else shot
                if write:
                    out_path.parent.mkdir(parents=True, exist_ok=True)
                    img.save(out_path, format="PNG", optimize=True)
                    written.append(out_path)
    # Website screenshot slots (named, not numbered)
    web_spec = sb.get("web")
    if web_spec:
        raw_dir = ROOT / web_spec["raw_dir"]
        size = tuple(web_spec["size"])
        for n, screen in enumerate(sb["screens"], start=1):
            raw_path = raw_dir / f"{n:02d}-{screen['id']}.png"
            shot = Image.open(raw_path).convert("RGB") if raw_path.exists() else placeholder_screenshot(size, screen["title"], "Screenshot pending")
            if shot.size != size:
                shot = shot.resize(size, Image.Resampling.LANCZOS)
            out_path = ROOT / web_spec["dir"] / f"{screen['id']}.png"
            if write:
                out_path.parent.mkdir(parents=True, exist_ok=True)
                shot.save(out_path, format="PNG", optimize=True)
                written.append(out_path)
    return written


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--storyboard", default=str(MARKETING / "storyboard.json"))
    args = ap.parse_args(argv)
    written = build_storyboard(Path(args.storyboard))
    print(f"wrote {len(written)} screenshot files")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
