#!/usr/bin/env python3
"""Ayuvo mark ("Ayuvo Peak") — single source of truth for the logo geometry.

The mark is an open ring with one bold peak rising through the opening at
12 o'clock. Everything else in the brand pipeline (PNG rasters, SVG files,
tests) derives from the constants and helpers in this module, so the vector
and every raster are guaranteed to agree.

Geometry is expressed on a 1024 x 1024 design grid (design units).
"""

from __future__ import annotations

import math
from dataclasses import dataclass

from PIL import Image, ImageDraw

GRID = 1024

# Ring: centreline circle. The opening is centred on 12 o'clock and spans
# 2 * OPENING_HALF_DEG degrees (measured at the centreline).
RING_CX, RING_CY = 512.0, 544.0
RING_R = 352.0
STROKE = 112.0
OPENING_HALF_DEG = 36.0

# Peak: polyline apex -> legs, drawn with round joins and caps.
PEAK_LEFT = (336.0, 640.0)
PEAK_APEX = (512.0, 152.0)
PEAK_RIGHT = (688.0, 640.0)

# Bounding box of the ink incl. round caps (see bbox()) and the optical centre
# used to place the mark on a canvas.
OPTICAL_CENTRE = (512.0, 524.0)

SUPERSAMPLE = 4


@dataclass(frozen=True)
class Geometry:
    """Resolved geometry with a stroke scale (favicon variants use a thicker stroke)."""

    stroke: float = STROKE
    opening_half_deg: float = OPENING_HALF_DEG

    @property
    def half(self) -> float:
        return self.stroke / 2.0

    def ring_endpoints(self) -> tuple[tuple[float, float], tuple[float, float]]:
        """Centreline end points of the ring arc (right end, left end)."""
        a = math.radians(self.opening_half_deg)
        right = (RING_CX + RING_R * math.sin(a), RING_CY - RING_R * math.cos(a))
        left = (RING_CX - RING_R * math.sin(a), RING_CY - RING_R * math.cos(a))
        return right, left

    def arc_angles(self) -> tuple[float, float]:
        """Pillow arc angles (degrees, clockwise from 3 o'clock): start, end."""
        start = 270.0 + self.opening_half_deg
        end = 270.0 - self.opening_half_deg
        return start, end

    def bbox(self) -> tuple[float, float, float, float]:
        """Ink bounding box (x0, y0, x1, y1) in design units, caps included."""
        x0 = RING_CX - RING_R - self.half
        x1 = RING_CX + RING_R + self.half
        y0 = min(PEAK_APEX[1] - self.half, RING_CY - RING_R - self.half)
        y1 = RING_CY + RING_R + self.half
        return (x0, y0, x1, y1)

    # --- clearances used by the tests -------------------------------------------------

    @staticmethod
    def _point_segment_distance(p, a, b) -> float:
        px, py = p
        ax, ay = a
        bx, by = b
        dx, dy = bx - ax, by - ay
        length_sq = dx * dx + dy * dy
        t = 0.0 if length_sq == 0 else max(0.0, min(1.0, ((px - ax) * dx + (py - ay) * dy) / length_sq))
        fx, fy = ax + t * dx, ay + t * dy
        return math.hypot(px - fx, py - fy)

    def cap_to_leg_clearance(self) -> float:
        """Smallest gap between a ring cap and the nearest peak leg (design units)."""
        right, left = self.ring_endpoints()
        d_right = self._point_segment_distance(right, PEAK_APEX, PEAK_RIGHT)
        d_left = self._point_segment_distance(left, PEAK_APEX, PEAK_LEFT)
        return min(d_right, d_left) - self.stroke

    def apex_to_cap_clearance(self) -> float:
        """Gap between the apex cap and the ring caps."""
        right, _ = self.ring_endpoints()
        return math.hypot(right[0] - PEAK_APEX[0], right[1] - PEAK_APEX[1]) - self.stroke

    def leg_end_to_ring_clearance(self) -> float:
        """Gap between the leg end caps and the ring's inner edge."""
        d = math.hypot(PEAK_RIGHT[0] - RING_CX, PEAK_RIGHT[1] - RING_CY)
        inner = RING_R - self.half
        return inner - (d + self.half)


DEFAULT = Geometry()
SMALL = Geometry(stroke=140.0, opening_half_deg=42.0)  # favicon 16/32 px: thicker stroke, wider opening


# --------------------------------------------------------------------------------------
# Raster
# --------------------------------------------------------------------------------------


def _scaled(p, scale: float, offset: tuple[float, float]):
    return (offset[0] + p[0] * scale, offset[1] + p[1] * scale)


def mask(size: int, mark_height_fraction: float, geometry: Geometry = DEFAULT,
         offset_px: tuple[float, float] = (0.0, 0.0)) -> Image.Image:
    """Return an "L" mask of the mark centred (optically) on a size x size canvas.

    The mark's ink height (bbox height) equals `mark_height_fraction * size`.
    `offset_px` nudges the whole mark (used by composites).
    """
    ss = SUPERSAMPLE
    big = size * ss
    x0, y0, x1, y1 = geometry.bbox()
    scale = (mark_height_fraction * big) / (y1 - y0)
    ocx, ocy = OPTICAL_CENTRE
    origin = (big / 2.0 - ocx * scale + offset_px[0] * ss, big / 2.0 - ocy * scale + offset_px[1] * ss)

    img = Image.new("L", (big, big), 0)
    draw = ImageDraw.Draw(img)
    stroke_px = geometry.stroke * scale
    half = stroke_px / 2.0

    # Ring arc (Pillow arcs are drawn clockwise from start to end; end < start wraps).
    # Pillow grows a wide arc *inward* from its bounding box, so the box is the
    # outer edge: centreline radius + half the stroke.
    cx, cy = _scaled((RING_CX, RING_CY), scale, origin)
    r_outer = RING_R * scale + half
    start, end = geometry.arc_angles()
    draw.arc([cx - r_outer, cy - r_outer, cx + r_outer, cy + r_outer], start=start, end=end, fill=255,
             width=max(1, int(round(stroke_px))))
    for pt in geometry.ring_endpoints():
        px, py = _scaled(pt, scale, origin)
        draw.ellipse([px - half, py - half, px + half, py + half], fill=255)

    # Peak polyline with round joins + caps.
    pts = [_scaled(p, scale, origin) for p in (PEAK_LEFT, PEAK_APEX, PEAK_RIGHT)]
    draw.line(pts, fill=255, width=max(1, int(round(stroke_px))), joint="curve")
    for px, py in pts:
        draw.ellipse([px - half, py - half, px + half, py + half], fill=255)

    return img.resize((size, size), Image.Resampling.LANCZOS)


def gradient(size: int, start_hex: str, end_hex: str, angle_deg: float = 135.0) -> Image.Image:
    """Linear gradient RGB image (CSS-style angle: 135deg = top-left -> bottom-right)."""
    s = _rgb(start_hex)
    e = _rgb(end_hex)
    # CSS: 0deg points up, 90deg right. Direction vector for 135deg = (sin, -cos) in screen coords.
    a = math.radians(angle_deg)
    dx, dy = math.sin(a), -math.cos(a)
    img = Image.new("RGB", (size, size))
    px = img.load()
    # projection range over the square
    corners = [(0, 0), (size, 0), (0, size), (size, size)]
    projs = [x * dx + y * dy for x, y in corners]
    lo, hi = min(projs), max(projs)
    span = hi - lo or 1.0
    for y in range(size):
        for x in range(size):
            t = ((x * dx + y * dy) - lo) / span
            px[x, y] = tuple(int(round(s[i] + (e[i] - s[i]) * t)) for i in range(3))
    return img


def _rgb(hex_str: str) -> tuple[int, int, int]:
    h = hex_str.lstrip("#")
    if len(h) == 8:  # #AARRGGBB
        h = h[2:]
    return tuple(int(h[i:i + 2], 16) for i in (0, 2, 4))


def render(size: int, mark_height_fraction: float, fill: str | tuple[str, str] | None,
           background: str | None, geometry: Geometry = DEFAULT, greyscale: bool = False,
           offset_px: tuple[float, float] = (0.0, 0.0)) -> Image.Image:
    """Render the mark.

    fill: hex colour, (start_hex, end_hex) gradient, or None for white.
    background: hex colour or None (transparent).
    greyscale: render the mark as a light grey (iOS tinted appearance).
    Returns RGBA when background is None, else RGB.
    """
    m = mask(size, mark_height_fraction, geometry, offset_px)
    if greyscale:
        colour_layer = Image.new("RGB", (size, size), (236, 236, 236))
    elif fill is None:
        colour_layer = Image.new("RGB", (size, size), (255, 255, 255))
    elif isinstance(fill, tuple):
        colour_layer = gradient(size, fill[0], fill[1])
    else:
        colour_layer = Image.new("RGB", (size, size), _rgb(fill))

    if background is None:
        out = Image.new("RGBA", (size, size), (0, 0, 0, 0))
        out.paste(colour_layer, (0, 0), m)
        return out
    out = Image.new("RGB", (size, size), _rgb(background))
    out.paste(colour_layer, (0, 0), m)
    return out


# --------------------------------------------------------------------------------------
# SVG
# --------------------------------------------------------------------------------------


def _arc_path(geometry: Geometry) -> str:
    right, left = geometry.ring_endpoints()
    # Large arc from the right end point clockwise round to the left end point.
    return (f"M {right[0]:.3f} {right[1]:.3f} "
            f"A {RING_R:.3f} {RING_R:.3f} 0 1 1 {left[0]:.3f} {left[1]:.3f}")


def _peak_path() -> str:
    return (f"M {PEAK_LEFT[0]:.3f} {PEAK_LEFT[1]:.3f} "
            f"L {PEAK_APEX[0]:.3f} {PEAK_APEX[1]:.3f} "
            f"L {PEAK_RIGHT[0]:.3f} {PEAK_RIGHT[1]:.3f}")


def svg(fill: str | tuple[str, str] | None = "currentColor", background: str | None = None,
        geometry: Geometry = DEFAULT, size: int | None = None, mark_height_fraction: float | None = None,
        title: str = "Ayuvo") -> str:
    """Return an SVG document for the mark.

    fill: hex, (start,end) gradient, or "currentColor" (default).
    When `size` and `mark_height_fraction` are given, the mark is placed on a
    square canvas like the raster renderer does; otherwise the viewBox is the
    tight ink bounding box.
    """
    x0, y0, x1, y1 = geometry.bbox()
    defs = ""
    stroke = "currentColor" if fill is None else fill
    if isinstance(fill, tuple):
        defs = (f'<defs><linearGradient id="g" x1="0" y1="0" x2="1" y2="1">'
                f'<stop offset="0" stop-color="{fill[0]}"/><stop offset="1" stop-color="{fill[1]}"/>'
                f'</linearGradient></defs>')
        stroke = "url(#g)"
    if size is not None and mark_height_fraction is not None:
        scale = (mark_height_fraction * size) / (y1 - y0)
        ocx, ocy = OPTICAL_CENTRE
        tx = size / 2.0 - ocx * scale
        ty = size / 2.0 - ocy * scale
        view = f'0 0 {size} {size}'
        transform = f' transform="translate({tx:.3f} {ty:.3f}) scale({scale:.6f})"'
        w = h = size
    else:
        view = f'{x0:.3f} {y0:.3f} {x1 - x0:.3f} {y1 - y0:.3f}'
        transform = ""
        w, h = x1 - x0, y1 - y0
    bg = ""
    if background:
        bg = f'<rect width="100%" height="100%" fill="{background}"/>'
    return (
        f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="{view}" width="{w:.0f}" height="{h:.0f}" role="img" aria-label="{title}">'
        f'<title>{title}</title>{defs}{bg}'
        f'<g{transform} fill="none" stroke="{stroke}" stroke-width="{geometry.stroke:.0f}" stroke-linecap="round" stroke-linejoin="round">'
        f'<path d="{_arc_path(geometry)}"/><path d="{_peak_path()}"/></g></svg>\n'
    )


if __name__ == "__main__":
    g = DEFAULT
    print("bbox", g.bbox())
    print("cap->leg clearance", round(g.cap_to_leg_clearance(), 1))
    print("apex->cap clearance", round(g.apex_to_cap_clearance(), 1))
    print("leg end->ring clearance", round(g.leg_end_to_ring_clearance(), 1))
