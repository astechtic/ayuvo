#!/usr/bin/env python3
"""Turn the raw iPhone captures in ayuvo_screenshots/ into publishable, redacted screens.

The raw captures are real device screenshots: real prescriptions, a real doctor and
hospital, real lab values, a real body-weight, real times and battery levels, and third
party exercise photos. This script writes redacted copies to marketing/raw/ios-real/ and
nothing else may be published from the raw folder.

    python3 scripts/web/redact_screens.py            # write marketing/raw/ios-real/*.png
    python3 scripts/web/redact_screens.py --check    # exit 1 if a source is missing

Every region below was read off the 588x1280 captures. Text that had to change is
redrawn in SF (the app's own UI font) with sample values.
"""
from __future__ import annotations

import sys
from pathlib import Path

from PIL import Image, ImageChops, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parents[2]
SRC = ROOT / "ayuvo_screenshots"
OUT = ROOT / "marketing" / "raw" / "ios-real"
SF = "/System/Library/Fonts/SFNS.ttf"

# slug -> (source file, selected tab)
SOURCES = {
    "summary": ("summary_home.jpeg", "summary"),
    "browse": ("browse.jpeg", "browse"),
    "nutrition": ("nutritation_and _food_management.jpeg", "browse"),
    "workouts": ("workouts_managements.jpeg", "browse"),
    "exercises": ("excersices.jpeg", "browse"),
    "heart-rate": ("heart_rate_data.jpeg", "browse"),
    "coach": ("coach.jpeg", "coach"),
    "medications": ("medications.jpeg", "browse"),
    "records": ("health_records_managements.jpeg", "records"),
    "record-extraction": ("record_data_exterations.jpeg", "records"),
    "settings": ("settings.jpeg", "settings"),
    "export-data": ("export_chat.jpeg", None),  # a sheet, no tab bar
}

W, H = 588, 1280
BAR_Y0, BAR_Y1 = 1152, 1256
PILL_Y0, PILL_Y1 = 1163, 1246
TAB_X = {  # pill x-range per tab
    "summary": (34, 172),
    "browse": (140, 266),
    "records": (234, 362),
    "coach": (330, 458),
    "settings": (426, 554),
}

WHITE = (255, 255, 255)
GREY = (142, 142, 147)
CARD = (28, 28, 30)
ORANGE = (255, 159, 10)


def font(size: int, weight: int = 400) -> ImageFont.FreeTypeFont:
    f = ImageFont.truetype(SF, size)
    f.set_variation_by_axes([100, min(max(size, 17), 96), 400, weight])
    return f


def erase(im: Image.Image, box, sample):
    """Paint a box with the colour found at `sample` (a clean pixel of the same surface)."""
    colour = im.getpixel(sample)
    ImageDraw.Draw(im).rectangle(box, fill=colour)
    return colour


def text(im, xy, s, size, weight=400, fill=WHITE, anchor="lm"):
    ImageDraw.Draw(im).text(xy, s, font=font(size, weight), fill=fill, anchor=anchor)


def status_bar(im: Image.Image):
    """9:41 and a full battery; the signal/wifi glyphs are generic and stay."""
    bg = im.getpixel((300, 6))
    d = ImageDraw.Draw(im)
    d.rectangle((50, 28, 168, 68), fill=bg)
    d.text((108, 47), "9:41", font=font(30, 600), fill=WHITE, anchor="mm")
    d.rectangle((492, 30, 552, 66), fill=bg)
    d.rounded_rectangle((498, 36, 540, 58), radius=7, outline=(120, 120, 124), width=2)
    d.rounded_rectangle((501, 39, 537, 55), radius=4, fill=WHITE)
    d.rounded_rectangle((542, 43, 545, 51), radius=1, fill=(120, 120, 124))


def rounded_glyph_dumbbell(im, box, colour=ORANGE):
    x0, y0, x1, y1 = box
    cx, cy = (x0 + x1) / 2, (y0 + y1) / 2
    d = ImageDraw.Draw(im)
    s = (x1 - x0) / 94
    d.rounded_rectangle((cx - 26 * s, cy - 3.5 * s, cx + 26 * s, cy + 3.5 * s), radius=3 * s, fill=colour)
    for sign in (-1, 1):
        d.rounded_rectangle((cx + sign * 26 * s - 5 * s, cy - 15 * s, cx + sign * 26 * s + 5 * s, cy + 15 * s), radius=3 * s, fill=colour)
        d.rounded_rectangle((cx + sign * 34 * s - 4 * s, cy - 10 * s, cx + sign * 34 * s + 4 * s, cy + 10 * s), radius=3 * s, fill=colour)


def neutral_tile(im, box, fill=(44, 44, 46), radius=18):
    """Replace a third-party exercise photo with a neutral tile."""
    ImageDraw.Draw(im).rounded_rectangle(box, radius=radius, fill=fill)
    rounded_glyph_dumbbell(im, box)


def document_card(im, box, title):
    x0, y0, x1, y1 = box
    d = ImageDraw.Draw(im)
    d.rounded_rectangle(box, radius=16, fill=(30, 30, 32))
    cx = (x0 + x1) // 2
    d.rounded_rectangle((cx - 26, y0 + 34, cx + 26, y0 + 108), radius=7, outline=(10, 132, 255), width=3)
    for i, dy in enumerate((54, 68, 82)):
        d.line((cx - 15, y0 + dy, cx + (15 if i < 2 else 2), y0 + dy), fill=(10, 132, 255), width=3)
    text(im, (x0 + 14, y1 - 22), title, 18, 600)


def fade_bottom(im: Image.Image, y_start=1088, y_end=BAR_Y0):
    px = im.load()
    for y in range(y_start, H):
        a = 1.0 if y >= y_end else (y - y_start) / (y_end - y_start)
        for x in range(W):
            r, g, b = px[x, y]
            px[x, y] = (int(r * (1 - a)), int(g * (1 - a)), int(b * (1 - a)))


def pill_mask(x0, x1):
    m = Image.new("L", (W, H), 0)
    ImageDraw.Draw(m).rounded_rectangle((x0, PILL_Y0, x1, PILL_Y1), radius=42, fill=255)
    return m


def clean_tab_bar(im: Image.Image, selected: str, own: Image.Image, coach: Image.Image, browse: Image.Image):
    """Drop a clean, ghost-free tab bar on `im`.

    `coach` and `browse` are original captures whose bar sits over empty background:
    coach.jpeg (Coach selected) and medications.jpeg (Browse selected). Every other pill
    comes from `own`, the capture being fixed, where the dark selected pill is clean.
    """
    box = (0, BAR_Y0, W, BAR_Y1)
    base = coach.copy()
    # un-select Coach using the Browse capture, whose Coach slot is unselected and clean
    x0, x1 = TAB_X["coach"]
    base.paste(browse.crop((x0 - 4, BAR_Y0, x1 + 4, BAR_Y1)), (x0 - 4, BAR_Y0))
    if selected == "browse":
        source = browse
    elif selected == "coach":
        source = coach
    else:
        source = own
    x0, x1 = TAB_X[selected]
    if source is own:
        # The capture's own pill still shows ghosted content from behind the glass:
        # keep only its icon and label, on a flat pill of the clean capture's colour.
        cx = (x0 + x1) // 2
        flat = Image.new("RGB", (W, H), (14, 14, 16))
        region = (cx - 50, 1170, cx + 50, 1242)
        crop = own.crop(region)
        r, _, b = crop.split()
        blue = ImageChops.subtract(b, r).point(lambda v: min(255, max(0, (v - 30) * 4)))  # icon + label are the only blue pixels
        flat.paste(crop, region[:2], blue)
        d = ImageDraw.Draw(flat)
        d.rounded_rectangle((x0, PILL_Y0, x1, PILL_Y1), radius=42, outline=(62, 62, 64), width=2)
        source = flat
    base.paste(source, (0, 0), pill_mask(x0, x1))
    im.paste(base.crop(box), (0, BAR_Y0))


def redact(slug: str, im: Image.Image) -> None:
    if slug == "records":
        for box, sample in (((70, 476, 528, 586), (300, 474)), ((70, 598, 528, 736), (300, 474)), ((70, 758, 528, 860), (300, 474))):
            erase(im, box, sample)
        rows = (
            (("LDL cholesterol H: 118 mg/dL — above", "the report reference range"), (496, 529), "Lipid Test · 5 Sep 2026", 561),
            (("Triglycerides (calculation) H: 182", "mg/dL — above the report reference", "range"), (620, 653, 687), "Lipid Test · 5 Sep 2026", 719),
            (("Vitamin D L: 22 ng/mL — below the", "report reference range"), (777, 811), "Vitamin Panel · 12 Sep 2026", 843),
        )
        for lines, ys, sub, suby in rows:
            for line, y in zip(lines, ys):
                text(im, (78, y), line, 23)
            text(im, (73, suby), sub, 17, 400, GREY)
        ImageDraw.Draw(im).rectangle((0, 944, W, 1160), fill=(0, 0, 0))
        for i, (x0, name) in enumerate(((24, "Lipid panel"), (205, "Prescription"), (387, "Vitamin panel"))):
            document_card(im, (x0, 956, x0 + 164, 1120), name)
    elif slug == "record-extraction":
        # blurred content of the previous screen shows behind the status bar: black it out,
        # then bring back the generic signal / wifi glyphs from another capture
        ImageDraw.Draw(im).rectangle((0, 0, W, 86), fill=(0, 0, 0))
        im.paste(Image.open(SRC / SOURCES["summary"][0]).convert("RGB").crop((405, 30, 492, 66)), (405, 30))
        surface = (480, 446)
        erase(im, (40, 428, 420, 466), surface)
        text(im, (44, 446), "Sample City Hospital", 25)
        erase(im, (40, 504, 420, 542), (480, 522))
        text(im, (44, 522), "Dr. A. Sample", 25)
        erase(im, (40, 748, 420, 818), (480, 780))
        text(im, (44, 765), "2D echo colour", 25)
        text(im, (44, 799), "Doppler report", 25)
        for y, label, value in ((946, "LV ESV", "32 mL"), (1009, "LVEF", "62 %")):
            erase(im, (40, y - 22, 526, y + 22), (300, y - 30))
            text(im, (44, y), label, 25, 600)
            lw = int(font(25, 600).getlength(label))
            chip = (44 + lw + 10, y - 15, 44 + lw + 10 + 112, y + 15)
            ImageDraw.Draw(im).rounded_rectangle(chip, radius=15, fill=(50, 50, 52))
            text(im, ((chip[0] + chip[2]) // 2, y), "Not mapped", 18, 500, GREY, "mm")
            text(im, (520, y), value, 25, 600, WHITE, "rm")
    elif slug == "medications":
        tile = im.crop((40, 903, 101, 964))
        for y in (515, 794):
            im.paste(tile, (40, y))
    elif slug == "settings":
        erase(im, (148, 326, 420, 358), (450, 342))
        text(im, (150, 342), "Age 30 · 175 cm · 72 kg", 22, 400, GREY)
    elif slug == "workouts":
        neutral_tile(im, (70, 866, 164, 960), fill=(58, 58, 60), radius=20)
    elif slug == "exercises":
        for y0 in (489, 686, 883, 1080):
            neutral_tile(im, (30, y0, 181, y0 + 152))


def main() -> int:
    missing = [name for name, _ in SOURCES.values() if not (SRC / name).exists()]
    if missing:
        print("missing sources:", *missing, sep="\n  ")
        return 1
    if "--check" in sys.argv:
        return 0
    OUT.mkdir(parents=True, exist_ok=True)
    raw = {slug: Image.open(SRC / name).convert("RGB") for slug, (name, _) in SOURCES.items()}
    for slug, (name, tab) in SOURCES.items():
        im = raw[slug].copy()
        assert im.size == (W, H), (slug, im.size)
        redact(slug, im)
        if tab:
            fade_bottom(im)
            clean_tab_bar(im, tab, raw[slug], raw["coach"], raw["medications"])
        status_bar(im)
        im.save(OUT / f"{slug}.png", optimize=True)
        print("wrote", (OUT / f"{slug}.png").relative_to(ROOT))
    return 0


if __name__ == "__main__":
    sys.exit(main())
