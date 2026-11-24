#!/usr/bin/env python3
"""Render the 3D store screenshots (App Store 6.9" and Google Play phone) with headless Chrome.

    python3 scripts/brand/store3d/render.py                 # both platforms
    python3 scripts/brand/store3d/render.py --platform ios --only 1

Inputs: app captures in marketing/raw/ios-real/ (iOS) and marketing/raw/android/ (Android).
Outputs: marketing/store/ios/6.9/NN-<id>.png (1290x2796) and marketing/store/android/phone/NN-<id>.png
(1080x1920). The scene itself is scripts/brand/store3d/scene.html.
"""
from __future__ import annotations

import argparse
import json
import subprocess
import sys
import tempfile
from pathlib import Path

from PIL import Image

ROOT = Path(__file__).resolve().parents[3]
SCENE = Path(__file__).with_name("scene.html")
CHROME = "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"

THEMES = {
    "rose":     {"bg1": "#FFF3F5", "bg2": "#FFE1E8", "bg3": "#F3EAFF", "accent": "#E8264F"},
    "mint":     {"bg1": "#F1FBF6", "bg2": "#DDF5EA", "bg3": "#E6F6FF", "accent": "#0E9F6E"},
    "sky":      {"bg1": "#F2F8FF", "bg2": "#DDEBFF", "bg3": "#F0EAFF", "accent": "#1F6FEB"},
    "lavender": {"bg1": "#F7F3FF", "bg2": "#E9E0FF", "bg3": "#FFE9F1", "accent": "#6D4AE0"},
    "peach":    {"bg1": "#FFF7EF", "bg2": "#FFE7D3", "bg3": "#FFEFF4", "accent": "#E8551E"},
}

# id, ios capture, android capture, theme, title, accent, sub, objects (x%, y%, size%u, rot, back)
SLIDES = [
    ("summary", "summary.png", None, "rose", "Your whole health,", "in one place",
     "Food, movement, water, medicines and your Health data on one screen.",
     [("heart", 15, 44, 20, -14, False), ("drop", 87, 62, 15, 12, False), ("ring", 86, 36, 17, 0, True),
      ("sphere", 10, 78, 8, 0, False), ("sphere", 92, 86, 6, 0, True)]),
    ("nutrition", "nutrition.png", "02-nutrition.png", "peach", "Snap a meal,", "see every nutrient",
     "Photo, barcode, voice or text. Review 30+ nutrients before you save.",
     [("apple", 14, 46, 20, -10, False), ("leaf", 88, 40, 14, 18, True), ("flame", 88, 72, 15, 8, False),
      ("sphere", 9, 82, 7, 0, False)]),
    ("workouts", "workouts.png", "03-workouts.png", "sky", "Train with", "a real plan",
     "Sets, reps and weight, a 1,300-exercise library and GPS walks, runs and rides.",
     [("dumbbell", 16, 47, 22, 0, False), ("bolt", 88, 40, 13, 14, True), ("flame", 88, 74, 14, -6, False),
      ("sphere", 10, 84, 7, 0, False)]),
    ("heart-rate", "heart-rate.png", "04-heart-rate.png", "rose", "Every health chart,", "one private hub",
     "Mirror Apple Health into a private hub with day-to-year charts.",
     [("heart", 86, 42, 17, 12, False), ("moon", 13, 50, 15, -8, True), ("drop", 13, 78, 12, -10, False),
      ("sphere", 90, 84, 7, 0, False)]),
    ("records", "records.png", None, "mint", "Your reports,", "finally organised",
     "Lab reports, prescriptions and bills, read right on your phone.",
     [("doc", 14, 46, 20, -12, False), ("tube", 88, 66, 16, 0, False), ("sphere", 88, 38, 7, 0, True),
      ("sphere", 10, 84, 6, 0, False)]),
    ("record-extraction", "record-extraction.png", "06-record-extraction.png", "lavender", "Track every test", "across reports",
     "Values are found for you, and you confirm every one.",
     [("bars", 86, 44, 17, 0, False), ("tube", 13, 52, 16, -10, True), ("sparkle", 12, 80, 10, 0, False),
      ("sphere", 90, 82, 7, 0, False)]),
    ("medications", "medications.png", "07-medications.png", "rose", "Reminders for", "every dose",
     "Schedules, reminders and a dose log for each medicine.",
     [("pill", 15, 46, 20, 0, False), ("tablet", 87, 70, 13, 10, False), ("bell", 87, 40, 15, 12, True),
      ("sphere", 10, 84, 6, 0, False)]),
    ("coach", "coach.png", "08-coach.png", "lavender", "An AI coach", "on your terms",
     "Use your own key or run fully on-device. It reads only what you allow.",
     [("chat", 15, 46, 19, -8, False), ("sparkle", 87, 40, 13, 0, True), ("sparkle", 88, 74, 9, 0, False),
      ("sphere", 10, 84, 7, 0, False)]),
    ("export-data", "export-data.png", "09-export-data.png", "mint", "Private by design,", "yours to keep",
     "No account, no ads, no Ayuvo servers. Export everything any time.",
     [("shield", 15, 46, 19, -8, False), ("lock", 87, 70, 14, 10, False), ("sphere", 87, 40, 8, 0, True),
      ("sphere", 10, 84, 6, 0, False)]),
]

PLATFORMS = {
    "ios": {"size": (1290, 2796), "raw": ROOT / "marketing/raw/ios-real", "out": ROOT / "marketing/store/ios/6.9",
            "device": {"scale": .665, "aspect": 2.17, "top": .255}, "island": False,
            "health": "Apple Health"},
    # Real iPad captures (iPad Pro 13-inch simulator, 2064x2752) in a tablet frame.
    "ipad": {"size": (2064, 2752), "raw": ROOT / "marketing/raw/ipad", "out": ROOT / "marketing/store/ios/ipad-13",
             "device": {"scale": .655, "aspect": 2752 / 2064, "top": .275, "radius": .055, "bezel": .03, "depth": .03},
             "island": False, "health": "Apple Health", "vars": {"hs": 6.4, "ss": 2.8, "ct": 7, "cr": 20},
             "objscale": 0.62, "tilts": [(7, -13, -3), (7, 13, 3)]},
    "android": {"size": (1080, 1920), "raw": ROOT / "marketing/raw/android", "out": ROOT / "marketing/store/android/phone",
                "device": {"scale": .545, "aspect": 2.222, "top": .285}, "island": False,
                "health": "Health Connect"},
}
TILTS = [(8, -16, -5), (8, 16, 5)]  # alternate left / right lean


def render(platform: str, idx: int, slide, tmp: Path, number: int | None = None) -> Path:
    number = number or idx + 1
    cfg = PLATFORMS[platform]
    sid, ios_img, and_img, theme, title, accent, sub, objs = slide
    img = cfg["raw"] / (and_img if platform == "android" else ios_img)  # None for Android = slide skipped
    if not img.exists():
        sys.exit(f"missing capture {img}")
    w, h = cfg["size"]
    rx, ry, rz = cfg.get("tilts", TILTS)[(number - 1) % 2]
    params = {
        "platform": platform, "w": w, "h": h, "seed": idx * 31 + 5,
        "theme": THEMES[theme], "title": title, "accent": accent,
        "sub": sub.replace("Apple Health", cfg["health"]),
        "image": img.as_uri(), "island": cfg["island"],
        "device": {**cfg["device"], "rx": rx, "ry": ry, "rz": rz},
        "objects": [{"kind": k, "x": x if platform != "ipad" else 50 + (x - 50) * 1.08, "y": y if platform != "ipad" else 30 + (y - 30) * 1.1,
                     "size": s * cfg.get("objscale", 0.9 if platform == "android" else 1), "rot": r, "back": b}
                    for k, x, y, s, r, b in objs],
        "vars": cfg.get("vars", {}),
    }
    # The page sits next to scene.html so its relative font URLs resolve; parameters are injected inline
    # because headless Chrome drops the hash of a file:// URL.
    page = SCENE.with_name(f"_render-{platform}-{idx}.html")
    page.write_text(SCENE.read_text().replace("<script>", "<script>window.__P = " + json.dumps(params) + ";", 1))
    raw = tmp / f"{platform}-{idx}.png"
    try:
        subprocess.run([CHROME, "--headless=new", "--disable-gpu", "--hide-scrollbars", "--allow-file-access-from-files",
                        "--force-device-scale-factor=1", f"--window-size={w},{h}", "--virtual-time-budget=5000",
                        f"--screenshot={raw}", page.as_uri()], check=True, capture_output=True)
    finally:
        page.unlink(missing_ok=True)
    out = cfg["out"] / f"{number:02d}-{sid}.png"
    im = Image.open(raw).convert("RGB")
    if im.size != (w, h):
        im = im.crop((0, 0, w, h))
    out.parent.mkdir(parents=True, exist_ok=True)
    im.save(out, optimize=True)
    return out


def feature_graphic(tmp: Path) -> Path:
    """Google Play feature graphic, 1024x500, same scene: copy left, tilted phone right."""
    w, h = 1024, 500
    params = {
        "platform": "android", "w": w, "h": h, "seed": 11, "dots": 14,
        "theme": THEMES["rose"], "title": "Your whole health,", "accent": "in one place",
        "sub": "Nutrition, workouts, records, medicines and an AI coach. Private, on your phone.",
        "vars": {"hs": 4.7, "ss": 2.25, "ct": 10, "cr": 50},
        "image": (ROOT / "marketing/raw/android/02-nutrition.png").as_uri(), "island": False,
        "device": {"scale": .25, "aspect": 2.222, "top": .1, "dx": .25, "rx": 10, "ry": -18, "rz": 8},
        "objects": [{"kind": "heart", "x": 60, "y": 30, "size": 9, "rot": -12, "back": False},
                    {"kind": "apple", "x": 91, "y": 68, "size": 9, "rot": 10, "back": False},
                    {"kind": "pill", "x": 63, "y": 80, "size": 7, "rot": 0, "back": True},
                    {"kind": "sphere", "x": 92, "y": 20, "size": 3.5, "rot": 0, "back": False}],
    }
    page = SCENE.with_name("_render-feature.html")
    page.write_text(SCENE.read_text().replace("<script>", "<script>window.__P = " + json.dumps(params) + ";", 1))
    raw = tmp / "feature.png"
    try:
        subprocess.run([CHROME, "--headless=new", "--disable-gpu", "--hide-scrollbars", "--allow-file-access-from-files",
                        "--force-device-scale-factor=1", f"--window-size={w},{h}", "--virtual-time-budget=5000",
                        f"--screenshot={raw}", page.as_uri()], check=True, capture_output=True)
    finally:
        page.unlink(missing_ok=True)
    out = ROOT / "marketing/play/feature-graphic-1024x500.png"
    Image.open(raw).convert("RGB").crop((0, 0, w, h)).save(out, optimize=True)
    return out


def header_asset(tmp: Path) -> Path:
    """App Store product-page Header + Search Results asset: 5244x2950 PNG, no alpha (one universal spec).
    Apple crops the header to 21:9, so the title and phones stay inside the middle ~76% of the height."""
    w, h = 5244, 2950
    raw = ROOT / "marketing/raw/ios-real"
    def dev(img, scale, top, dx, ry, rz):
        return {"image": (raw / img).as_uri(),
                "device": {"scale": scale, "aspect": 2.17, "top": top, "dx": dx, "rx": 6, "ry": ry, "rz": rz}}
    side = [dev("nutrition.png", .094, .405, -.16, 22, 4), dev("heart-rate.png", .094, .405, .16, -22, -4)]
    params = {
        "platform": "ios", "w": w, "h": h, "seed": 23, "dots": 40,
        "theme": THEMES["rose"], "title": "Your whole health,", "accent": "in one place", "inlineAccent": True,
        "sub": "Nutrition, workouts, records, medicines and an AI coach. Private, on your iPhone.",
        "vars": {"hs": 3.0, "ss": 1.25, "ct": 7.6, "cr": 7.5}, "centerCopy": True,
        "brand": {"mark": (ROOT / "brand/ayuvo-mark-rose.svg").as_uri(), "size": 1.5},
        "image": (raw / "summary.png").as_uri(), "island": False,
        "device": {"scale": .104, "aspect": 2.17, "top": .345, "dx": 0, "rx": 6, "ry": 0, "rz": 0},
        "extraDevices": side,
        "objects": [{"kind": "heart", "x": 23.5, "y": 49, "size": 4.6, "rot": -12, "back": False},
                    {"kind": "apple", "x": 27, "y": 74, "size": 3.8, "rot": 8, "back": False},
                    {"kind": "pill", "x": 76.5, "y": 49, "size": 3.8, "rot": 0, "back": False},
                    {"kind": "dumbbell", "x": 73.5, "y": 74, "size": 4.2, "rot": 0, "back": False},
                    {"kind": "drop", "x": 59.5, "y": 42, "size": 2.2, "rot": 10, "back": True},
                    {"kind": "sparkle", "x": 40.5, "y": 42, "size": 2.0, "rot": 0, "back": True},
                    {"kind": "sphere", "x": 16, "y": 62, "size": 1.4, "rot": 0, "back": False},
                    {"kind": "sphere", "x": 84, "y": 62, "size": 1.3, "rot": 0, "back": False}],
    }
    params["title"] = params["title"].rstrip()
    page = SCENE.with_name("_render-header.html")
    page.write_text(SCENE.read_text().replace("<script>", "<script>window.__P = " + json.dumps(params) + ";", 1))
    shot = tmp / "header.png"
    try:
        subprocess.run([CHROME, "--headless=new", "--disable-gpu", "--hide-scrollbars", "--allow-file-access-from-files",
                        "--force-device-scale-factor=1", f"--window-size={w},{h}", "--virtual-time-budget=8000",
                        f"--screenshot={shot}", page.as_uri()], check=True, capture_output=True)
    finally:
        page.unlink(missing_ok=True)
    out = ROOT / "marketing/appstore/header-search-5244x2950.png"
    Image.open(shot).convert("RGB").crop((0, 0, w, h)).save(out, optimize=True)
    return out


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--platform", choices=["ios", "ipad", "android", "feature", "header", "all"], default="all")
    ap.add_argument("--only", type=int, help="1-based slide number")
    a = ap.parse_args()
    if a.platform == "header":
        with tempfile.TemporaryDirectory() as t:
            print(header_asset(Path(t)).relative_to(ROOT))
        return
    if a.platform == "feature":
        with tempfile.TemporaryDirectory() as t:
            print(feature_graphic(Path(t)).relative_to(ROOT))
        return
    plats = ["ios", "ipad", "android"] if a.platform == "all" else [a.platform]
    with tempfile.TemporaryDirectory() as t:
        for p in plats:
            slides = [(i, s) for i, s in enumerate(SLIDES) if (s[2] if p == "android" else s[1])
                      and (p != "ipad" or (PLATFORMS[p]["raw"] / s[1]).exists())]  # iPad: only screens captured
            if not a.only:
                for old in PLATFORMS[p]["out"].glob("*.png"):
                    old.unlink()
            for n, (i, s) in enumerate(slides, 1):
                if a.only and a.only != n:
                    continue
                print(render(p, i, s, Path(t), n).relative_to(ROOT))


if __name__ == "__main__":
    main()
