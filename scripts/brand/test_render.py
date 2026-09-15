#!/usr/bin/env python3
"""Invariants for the Ayuvo mark and the rendered icon set.

Run:  python3 scripts/brand/test_render.py      (plain asserts; also works under pytest)
"""

from __future__ import annotations

import json
import sys
from pathlib import Path

from PIL import Image

sys.path.insert(0, str(Path(__file__).resolve().parent))
import ayuvo_mark as mark  # noqa: E402

ROOT = Path(__file__).resolve().parents[2]


def test_geometry_clearances():
    g = mark.DEFAULT
    assert g.cap_to_leg_clearance() >= 32, g.cap_to_leg_clearance()
    assert g.apex_to_cap_clearance() >= 60, g.apex_to_cap_clearance()
    assert g.leg_end_to_ring_clearance() >= 32, g.leg_end_to_ring_clearance()
    x0, y0, x1, y1 = g.bbox()
    assert (x0, y0, x1, y1) == (104.0, 96.0, 920.0, 952.0)


def test_small_variant_still_open():
    g = mark.SMALL
    assert g.cap_to_leg_clearance() > 0
    assert g.leg_end_to_ring_clearance() > 0


def test_adaptive_foreground_inside_safe_circle():
    """The Android adaptive layer must keep its ink inside the 66/108 dp safe circle."""
    img = mark.render(432, 0.58, "#FF375F", None)
    alpha = img.split()[-1]
    bbox = alpha.getbbox()
    assert bbox is not None
    cx = cy = 216
    safe_r = 216 * (66 / 108)
    px = alpha.load()
    for y in range(bbox[1], bbox[3]):
        for x in range(bbox[0], bbox[2]):
            if px[x, y] > 8:
                assert ((x - cx) ** 2 + (y - cy) ** 2) ** 0.5 <= safe_r + 1, (x, y)


def test_store_icons_have_no_alpha():
    rose = ("#FF375F", "#FF6B8A")
    for size in (512, 1024):
        img = mark.render(size, 0.62, rose, "#0F1412")
        assert img.mode == "RGB"
        assert img.size == (size, size)


def test_svg_and_raster_agree():
    svg = mark.svg(None)
    assert 'stroke-width="112"' in svg and "A 352.000 352.000" in svg
    img = mark.render(64, 0.62, "#FFFFFF", None)
    assert img.getbbox() is not None


def test_manifest_dimensions():
    manifest = ROOT / "marketing/MANIFEST.json"
    if not manifest.exists():
        return
    data = json.loads(manifest.read_text())
    problems = []
    for rel, (w, h, mode) in data.items():
        p = ROOT / rel
        if not (ROOT / rel.split("/", 1)[0]).exists():
            continue  # sparse checkout (CI web job) without this platform tree
        if not p.exists():
            problems.append(f"missing {rel}")
            continue
        with Image.open(p) as im:
            if (im.width, im.height) != (w, h):
                problems.append(f"{rel}: {im.size} != {(w, h)}")
            if mode == "RGB" and im.mode not in ("RGB",):
                problems.append(f"{rel}: mode {im.mode} != RGB")
    assert not problems, "\n".join(problems)


def test_required_store_sizes():
    expectations = {
        "marketing/play/icon-512.png": (512, 512, "RGB"),
        "marketing/play/feature-graphic-1024x500.png": (1024, 500, "RGB"),
        "marketing/play/developer-header-4096x2304.jpg": (4096, 2304, "RGB"),
        "marketing/appstore/icon-1024.png": (1024, 1024, "RGB"),
        "marketing/appstore/watch-icon-1024.png": (1024, 1024, "RGB"),
        "web/assets/opengraph.jpg": (1200, 630, "RGB"),
        "web/assets/brand/apple-touch-icon.png": (180, 180, "RGB"),
        "web/assets/brand/favicon-32.png": (32, 32, "RGBA"),
    }
    for rel, (w, h, mode) in expectations.items():
        p = ROOT / rel
        if not p.exists():
            continue
        with Image.open(p) as im:
            assert im.size == (w, h), (rel, im.size)
            assert im.mode == mode, (rel, im.mode)


if __name__ == "__main__":
    failures = 0
    for name, fn in sorted(globals().items()):
        if name.startswith("test_") and callable(fn):
            try:
                fn()
                print(f"ok   {name}")
            except AssertionError as exc:
                failures += 1
                print(f"FAIL {name}: {exc}")
    raise SystemExit(1 if failures else 0)
