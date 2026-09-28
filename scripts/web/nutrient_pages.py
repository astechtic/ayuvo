"""Builders for /nutrients and /nutrients/<slug> (content in nutrient_facts.py).

Every intake number on these pages (recommended amounts, upper limits, reference limits, IU conversions) is read at
build time from shared/nutrients/nutrient_reference.json, the same file the apps use for their chart lines, so the
site and the app never disagree. Page copy never repeats those numbers. Everything else (what a nutrient does, food
amounts, symptoms, supplement forms, lab markers) lives in nutrient_facts.py with `[n]` markers into each page's
source list and the CHECKED date.

The diagrams (body flow, reference gauge, icons) are original inline SVG drawn here. The site's CSP forbids inline
styles, so the SVG uses presentation attributes and classes styled in web/styles.css.
"""
from __future__ import annotations

import html
import json
import math
import re

from compare_pages import _refs
from nutrient_facts import CATEGORIES, CHECKED, FACTS, ORDER
from site_lib import BASE, EMAIL, ROOT, WEB, Page, asset, cta_section, eyebrow, faq_section, section_head

REFERENCE_PATH = ROOT / "shared" / "nutrients" / "nutrient_reference.json"
PUBLISHED = "2026-09-27"

# ---------------------------------------------------------------------------
# reference numbers (shared contract)
# ---------------------------------------------------------------------------
_REF: dict | None = None


def load_reference() -> dict:
    """Read the shared nutrient reference once. The build fails loudly if it is missing: the pages must not guess."""
    global _REF
    if _REF is None:
        if not REFERENCE_PATH.exists():
            raise SystemExit(f"missing {REFERENCE_PATH.relative_to(ROOT)}: the nutrient pages render their numbers from it")
        data = json.loads(REFERENCE_PATH.read_text(encoding="utf-8"))
        if data.get("format") != "ayuvo-nutrient-reference":
            raise SystemExit(f"{REFERENCE_PATH.name}: unexpected format {data.get('format')!r}")
        _REF = data
    return _REF


def ref_for(slug: str) -> dict:
    for n in load_reference()["nutrients"]:
        if n.get("slug") == slug:
            return n
    raise SystemExit(f"nutrient_reference.json has no nutrient with slug {slug!r}")


def bands() -> list[tuple[str, str]]:
    out = []
    for b in load_reference()["age_bands"]:
        bid = b["id"] if isinstance(b, dict) else str(b)
        label = (b.get("label") if isinstance(b, dict) else None) or bid.replace("-", "–")
        out.append((bid, label))
    return out


def default_band() -> str:
    return load_reference().get("default_band", "31-50")


def fmt(v) -> str:
    """1000 -> 1,000; 2.40 -> 2.4; None -> an em dash (never a made-up zero)."""
    if v is None:
        return "—"
    if isinstance(v, str):
        return v
    if float(v).is_integer():
        return f"{int(v):,}"
    return f"{v:,.2f}".rstrip("0").rstrip(".")


def _band_value(block: dict | None, sex: str, band: str):
    if not block:
        return None
    by_sex = block.get(sex) or {}
    return by_sex.get(band)


def iu_factor(r: dict) -> float | None:
    """IU per unit when the reference gives one simple conversion (vitamin D). Vitamins A and E depend on the form."""
    iu = r.get("iu")
    if isinstance(iu, (int, float)):
        return float(iu)
    if isinstance(iu, dict):
        if isinstance(iu.get("mcg_per_iu"), (int, float)) and iu["mcg_per_iu"] > 0:
            return 1 / iu["mcg_per_iu"]
        for k in ("iu_per_ug", "iu_per_unit", "per_unit", "factor"):
            if isinstance(iu.get(k), (int, float)):
                return float(iu[k])
    return None


UL_SCOPE = {
    "all_sources": "from food, drinks and supplements combined",
    "all": "from food, drinks and supplements combined",
    "supplements": "from supplements and medicines only, not from food",
    "supplements_only": "from supplements and medicines only, not from food",
    "preformed": "for preformed vitamin A (retinol) only, not beta-carotene",
    "preformed_only": "for preformed vitamin A (retinol) only, not beta-carotene",
    "folic_acid": "for folic acid from fortified foods and supplements only, not natural food folate",
    "folic_acid_only": "for folic acid from fortified foods and supplements only, not natural food folate",
    "supplemental": "for supplements and fortified foods only",
}


def ul_scope_text(ul: dict) -> str:
    scope = str(ul.get("scope") or "").strip()
    return UL_SCOPE.get(scope, scope.replace("_", " "))


def ul_all_sources(r: dict) -> bool:
    ul = r.get("upper_limit") or {}
    return str(ul.get("scope", "all_sources")).startswith("all")


def ref_note(s: str) -> str:
    """Reference notes are written for the apps; the site lint bans the agency abbreviation, so spell it out."""
    return re.sub(r"\bFDA\b", "U.S. Food and Drug Administration", s)


def app_tracked(slug: str) -> bool:
    """False for the nutrients the food log does not estimate: the apps chart them from Medications supplements
    and from Apple Health / Health Connect."""
    return ref_for(slug).get("app_tracked", True) is not False


def limit_value(r: dict, kcal: int = 2000):
    lim = r.get("limit")
    if not lim:
        return None
    if lim.get("kind") == "fixed":
        return lim.get("value")
    if lim.get("kind") == "pct_energy":
        return kcal * lim["pct"] / 100 / lim["kcal_per_unit"]
    return None


# ---------------------------------------------------------------------------
# text helpers
# ---------------------------------------------------------------------------
_ALLOWED = ("strong", "em", "sub", "sup", "br")


def t(s: str) -> str:
    """Escape copy but keep a few inline tags, then turn [n] markers into source links."""
    s = html.escape(html.unescape(s or ""), quote=False)
    for tag in _ALLOWED:
        s = s.replace(f"&lt;{tag}&gt;", f"<{tag}>").replace(f"&lt;/{tag}&gt;", f"</{tag}>").replace(f"&lt;{tag}/&gt;", f"<{tag}/>")
    return _refs(s)


def plain(s: str) -> str:
    """Copy for places that cannot carry links (SVG text, alt, JSON-LD)."""
    s = re.sub(r"\s?\[\d+\]", "", s or "")
    return re.sub(r"<[^>]+>", "", html.unescape(s)).strip()


def x(s: str) -> str:
    return html.escape(s, quote=True)


def wrap(text: str, width: int) -> list[str]:
    words, lines, cur = text.split(), [], ""
    for w in words:
        if cur and len(cur) + 1 + len(w) > width:
            lines.append(cur)
            cur = w
        else:
            cur = f"{cur} {w}".strip()
    if cur:
        lines.append(cur)
    return lines or [""]


def lname(f: dict) -> str:
    """The name inside a sentence: "Vitamin D" -> "vitamin D", "Iron" -> "iron"."""
    n = f["name"]
    return f.get("name_lower") or (n[0].lower() + n[1:])


def band(cls: str, inner: str, sid: str = "") -> str:
    i = f' id="{sid}"' if sid else ""
    return f'<section class="{cls}"{i}><div class="container">{inner}</div></section>'


def crumbs_html(trail: list[tuple[str, str]], here: str) -> str:
    items = "".join(f'<li><a href="{u}">{n}</a></li>' for n, u in [("Home", "/")] + trail)
    return f'<ol class="crumbs">{items}<li aria-current="page">{here}</li></ol>'


# ---------------------------------------------------------------------------
# icons (original line glyphs, 24x24, stroked with currentColor)
# ---------------------------------------------------------------------------
GLYPHS = {
    "bone": '<path d="M8 8l8 8"/><circle cx="6.3" cy="8.2" r="2.2"/><circle cx="8.2" cy="6.3" r="2.2"/><circle cx="15.8" cy="17.7" r="2.2"/><circle cx="17.7" cy="15.8" r="2.2"/>',
    "muscle": '<path d="M5 17c-1-4 1-8 4-10l2-3h3l-1 3.5c2.6.4 4.6 2.2 5.4 4.6.8 2.5 0 4.6-2 5.7C13 19.3 8 19.2 5 17z"/><path d="M10.5 12.5c1.2-1 3-1.2 4.4-.4"/>',
    "nerve": '<path d="M2.5 12h4l2.2-5.5 4.4 11 2.3-5.5h6.1"/><circle cx="21.5" cy="12" r="1"/>',
    "shield": '<path d="M12 3l7 2.8v5.4c0 4.6-2.9 8-7 9.8-4.1-1.8-7-5.2-7-9.8V5.8z"/><path d="M9 12l2.2 2.2L15.5 10"/>',
    "blood": '<ellipse cx="12" cy="12" rx="8.5" ry="6"/><ellipse cx="12" cy="12" rx="3.6" ry="2.1"/>',
    "heart": '<path d="M12 20s-7.5-4.6-7.5-10.2A4.2 4.2 0 0 1 12 7.3a4.2 4.2 0 0 1 7.5 2.5C19.5 15.4 12 20 12 20z"/><path d="M6.5 12h3l1.3-2 2 4 1.2-2h3.5"/>',
    "brain": '<path d="M12 5.5a3 3 0 0 0-5.3 1.2A3 3 0 0 0 4.8 12a3 3 0 0 0 1.9 5.1A3 3 0 0 0 12 18.5z"/><path d="M12 5.5a3 3 0 0 1 5.3 1.2 3 3 0 0 1 1.9 5.3 3 3 0 0 1-1.9 5.1A3 3 0 0 1 12 18.5z"/><path d="M12 5.5v13"/>',
    "eye": '<path d="M2.5 12S6 5.5 12 5.5 21.5 12 21.5 12 18 18.5 12 18.5 2.5 12 2.5 12z"/><circle cx="12" cy="12" r="3"/>',
    "energy": '<path d="M13 2.5L5 13.5h6l-1 8 8-11h-6z"/>',
    "skin": '<path d="M3 8c3-2 6 2 9 0s6-2 9 0"/><path d="M3 13c3-2 6 2 9 0s6-2 9 0"/><path d="M3 18c3-2 6 2 9 0s6-2 9 0"/>',
    "cell": '<path d="M7 3c0 5 10 4 10 9s-10 4-10 9"/><path d="M17 3c0 5-10 4-10 9s10 4 10 9"/><path d="M8.5 6.5h7M8.5 17.5h7M10 12h4"/>',
    "gut": '<path d="M7 4.5h9a3 3 0 0 1 0 6H8.5a3 3 0 0 0 0 6H17a2.5 2.5 0 0 1 0 5h-2"/>',
    "clot": '<path d="M12 3s6.5 6.8 6.5 11.2a6.5 6.5 0 0 1-13 0C5.5 9.8 12 3 12 3z"/><path d="M9 14.5h6M12 11.5v6"/>',
    "water": '<path d="M12 3s3.6 4 3.6 6.8a3.6 3.6 0 0 1-7.2 0C8.4 7 12 3 12 3z"/><path d="M3 16c2-1.6 4-1.6 6 0s4 1.6 6 0 4-1.6 6 0"/><path d="M3 20c2-1.6 4-1.6 6 0s4 1.6 6 0 4-1.6 6 0"/>',
    "balance": '<path d="M12 4v16M7.5 20h9M5 7.5h14"/><path d="M5 7.5L2.5 13a2.6 2.6 0 0 0 5 0z"/><path d="M19 7.5L16.5 13a2.6 2.6 0 0 0 5 0z"/>',
    "baby": '<circle cx="12" cy="7.5" r="3.5"/><path d="M5.5 20.5c.5-4.2 3.2-7 6.5-7s6 2.8 6.5 7"/>',
    "wound": '<rect x="2.8" y="8.5" width="18.4" height="7" rx="3.5" transform="rotate(-45 12 12)"/><path d="M10.2 10.2l.01.01M13.8 13.8l.01.01M13.8 10.2l.01.01M10.2 13.8l.01.01"/>',
    "flame": '<path d="M12 21.5a6 6 0 0 1-6-6c0-4 3-5.5 3-9 2 1.2 3.2 3 3.4 4.8C13.8 9.8 14.6 8 14.5 6c2.4 2 3.5 5.2 3.5 9.5a6 6 0 0 1-6 6z"/>',
    "scale": '<rect x="3.5" y="3.5" width="17" height="17" rx="4"/><path d="M8.5 9.5a5 5 0 0 1 7 0L12.8 12"/>',
    "sugar": '<path d="M12 3l8 4.5v9L12 21l-8-4.5v-9z"/><path d="M4 7.5l8 4.5 8-4.5M12 12v9"/>',
    "sun": '<circle cx="12" cy="12" r="4"/><path d="M12 2.5v2.2M12 19.3v2.2M2.5 12h2.2M19.3 12h2.2M5.3 5.3l1.6 1.6M17.1 17.1l1.6 1.6M5.3 18.7l1.6-1.6M17.1 6.9l1.6-1.6"/>',
    "fortified": '<path d="M9 2.8h6M10 2.8v3.6L7.5 10v10.2a1 1 0 0 0 1 1h7a1 1 0 0 0 1-1V10L14 6.4V2.8"/><path d="M7.5 13h9"/>',
    "supplement": '<rect x="2.8" y="8.3" width="18.4" height="7.4" rx="3.7" transform="rotate(-40 12 12)"/><path d="M9.4 9l5.2 6.2" transform="rotate(0)"/>',
    "body": '<circle cx="12" cy="5" r="2.4"/><path d="M12 8v7M7 10.5l5-2 5 2M9 21l3-6 3 6"/>',
    "other": '<circle cx="12" cy="12" r="8.5"/><path d="M12 8v8M8 12h8"/>',
    "down": '<circle cx="12" cy="12" r="9"/><path d="M12 7v10M7.5 12.5L12 17l4.5-4.5"/>',
    "up": '<circle cx="12" cy="12" r="9"/><path d="M12 17V7M7.5 11.5L12 7l4.5 4.5"/>',
    "lab": '<path d="M9 3h6M10 3v6.5L4.8 18.3A2 2 0 0 0 6.5 21h11a2 2 0 0 0 1.7-2.7L14 9.5V3"/><path d="M7.4 14h9.2"/>',
    "star": '<path d="M12 3.5l2.6 5.3 5.9.9-4.3 4.1 1 5.8L12 16.9l-5.2 2.7 1-5.8-4.3-4.1 5.9-.9z"/>',
    "leaf": '<path d="M5 19C4 11 9 5 19.5 4.5 20 15 14 20 5 19z"/><path d="M5 19c3.5-4.5 6.5-7 10-9"/>',
    "fish": '<path d="M3 12c3.2-4 7.2-5.2 11-4 2 .6 3.6 2 4.6 4-1 2-2.6 3.4-4.6 4-3.8 1.2-7.8 0-11-4z"/><path d="M18.6 12l2.9-3v6z"/><circle cx="7.5" cy="11" r=".6"/>',
}


def glyph_sprite() -> str:
    syms = "".join(f'<symbol id="n-{k}" viewBox="0 0 24 24">{v}</symbol>' for k, v in GLYPHS.items())
    return f'<svg width="0" height="0" class="sr-only" aria-hidden="true" focusable="false">{syms}</svg>'


def glyph(name: str, cls: str = "") -> str:
    name = name if name in GLYPHS else "other"
    klass = f"nglyph {cls}".strip()
    return f'<svg class="{klass}" aria-hidden="true" focusable="false"><use href="#n-{name}"/></svg>'


# ---------------------------------------------------------------------------
# SVG: "How your body uses it" flow
# ---------------------------------------------------------------------------
LABEL_FS, SUB_FS = 17, 13.5


def _node_lines(step: dict, inner_w: float, label_fs=LABEL_FS, sub_fs=SUB_FS) -> tuple[list[str], list[str]]:
    lc = max(8, int(inner_w / (label_fs * 0.56)))
    sc = max(10, int(inner_w / (sub_fs * 0.52)))
    return wrap(plain(step["label"]), lc), (wrap(plain(step.get("sub", "")), sc) if step.get("sub") else [])


def _arrow(x1, y1, x2, y2, cls="flow-arrow") -> str:
    ang = math.atan2(y2 - y1, x2 - x1)
    s = 8
    p1 = (x2 - s * math.cos(ang) + s * 0.55 * math.sin(ang), y2 - s * math.sin(ang) - s * 0.55 * math.cos(ang))
    p2 = (x2 - s * math.cos(ang) - s * 0.55 * math.sin(ang), y2 - s * math.sin(ang) + s * 0.55 * math.cos(ang))
    lx, ly = x2 - s * 0.8 * math.cos(ang), y2 - s * 0.8 * math.sin(ang)
    return (f'<line class="{cls}" x1="{x1:.1f}" y1="{y1:.1f}" x2="{lx:.1f}" y2="{ly:.1f}"/>'
            f'<polygon class="{cls}-head" points="{x2:.1f},{y2:.1f} {p1[0]:.1f},{p1[1]:.1f} {p2[0]:.1f},{p2[1]:.1f}"/>')


def _node(i: int, n: int, step: dict, x0: float, y0: float, w: float, h: float, lines) -> str:
    label, sub = lines
    kind = " is-first" if i == 0 else (" is-last" if i == n - 1 else "")
    out = [f'<g class="flow-node{kind}"><rect x="{x0:.1f}" y="{y0:.1f}" width="{w:.1f}" height="{h:.1f}" rx="16"/>',
           f'<text class="flow-num" x="{x0 + 16:.1f}" y="{y0 + 24:.1f}">{i + 1:02d}</text>']
    y = y0 + 24 + 25
    for ln in label:
        out.append(f'<text class="flow-label" x="{x0 + 16:.1f}" y="{y:.1f}">{x(ln)}</text>')
        y += 21
    y += 1
    for ln in sub:
        out.append(f'<text class="flow-sub" x="{x0 + 16:.1f}" y="{y:.1f}">{x(ln)}</text>')
        y += 17.5
    out.append("</g>")
    return "".join(out)


def _node_h(lines) -> float:
    label, sub = lines
    return 24 + 25 + len(label) * 21 + (len(sub) * 17.5 + 2 if sub else 0) + 4


def _side_box(side: dict, x0, y0, w, fs=13.5) -> tuple[str, float]:
    lc = max(8, int((w - 24) / (fs * 0.56)))
    label = wrap(plain(side["label"]), lc)
    sub = wrap(plain(side.get("sub", "")), max(10, int((w - 24) / (12.5 * 0.52)))) if side.get("sub") else []
    h = 16 + len(label) * 18 + len(sub) * 16 + 12
    out = [f'<g class="flow-side"><rect x="{x0:.1f}" y="{y0:.1f}" width="{w:.1f}" height="{h:.1f}" rx="14"/>']
    y = y0 + 16 + 12
    for ln in label:
        out.append(f'<text class="flow-side-label" x="{x0 + 12:.1f}" y="{y:.1f}">{x(ln)}</text>')
        y += 18
    for ln in sub:
        out.append(f'<text class="flow-side-sub" x="{x0 + 12:.1f}" y="{y:.1f}">{x(ln)}</text>')
        y += 16
    out.append("</g>")
    return "".join(out), h


def flow_svg(flow: dict, name: str, sid: str) -> str:
    steps = flow["steps"]
    n = len(steps)
    aria = f"{plain(flow['title'])}: " + " → ".join(plain(s["label"]) for s in steps)
    side = flow.get("side")
    if side:
        aria += f". {plain(side['label'])} {plain(side.get('verb', 'acts on'))} {plain(steps[side['at']]['label'])}."
    return _flow_wide(flow, sid + "-w", aria) + _flow_tall(flow, sid + "-t", aria)


def _flow_wide(flow: dict, sid: str, aria: str) -> str:
    steps, side = flow["steps"], flow.get("side")
    n = len(steps)
    per_row = n if n <= 4 else (3 if n <= 6 else 4)
    rows = [list(range(i, min(i + per_row, n))) for i in range(0, n, per_row)]
    W, M, GAP, RGAP = 980, 14, 50, 70
    w = (W - 2 * M - (per_row - 1) * GAP) / per_row
    lines = [_node_lines(s, w - 32) for s in steps]
    h = max(_node_h(l) for l in lines)
    side_row = None if not side else next(r for r, idx in enumerate(rows) if side["at"] in idx)
    top = M
    parts: list[str] = []
    side_h = 0
    if side:
        side_w = min(w, 250)
        _, side_h = _side_box(side, 0, 0, side_w)
        if side_row == 0:
            top = M + side_h + 46
    ys = [top + r * (h + RGAP) for r in range(len(rows))]
    xs = [M + c * (w + GAP) for c in range(per_row)]
    total_h = ys[-1] + h + M
    for r, idx in enumerate(rows):
        for c, i in enumerate(idx):
            parts.append(_node(i, n, steps[i], xs[c], ys[r], w, h, lines[i]))
            if c + 1 < len(idx):
                parts.append(_arrow(xs[c] + w + 5, ys[r] + h / 2, xs[c + 1] - 5, ys[r] + h / 2))
        if r + 1 < len(rows):
            # elbow from the end of this row to the start of the next one
            x1, y1 = xs[len(idx) - 1] + w / 2, ys[r] + h
            x2, y2 = xs[0] + w / 2, ys[r + 1]
            mid = y1 + RGAP / 2
            parts.append(f'<path class="flow-arrow" d="M{x1:.1f},{y1 + 4:.1f} V{mid:.1f} H{x2:.1f} V{y2 - 9:.1f}"/>')
            parts.append(_arrow(x2, y2 - 12, x2, y2 - 4))
    if side:
        at = side["at"]
        r = side_row
        c = rows[r].index(at)
        side_w = min(w, 250)
        sx = xs[c] + (w - side_w) / 2
        if r == 0:
            sy = M
            box, _ = _side_box(side, sx, sy, side_w)
            parts.append(box)
            parts.append(_arrow(xs[c] + w / 2, sy + side_h + 4, xs[c] + w / 2, ys[0] - 5, "flow-dash"))
            vy = sy + side_h + 27
        else:
            sy = ys[r] + h + 46
            box, _ = _side_box(side, sx, sy, side_w)
            parts.append(box)
            parts.append(_arrow(xs[c] + w / 2, sy - 4, xs[c] + w / 2, ys[r] + h + 5, "flow-dash"))
            vy = ys[r] + h + 27
            total_h = sy + side_h + M
        if side.get("verb"):
            parts.append(f'<text class="flow-verb" x="{xs[c] + w / 2 + 10:.1f}" y="{vy:.1f}">{x(plain(side["verb"]))}</text>')
    return (f'<svg class="flow flow-wide" viewBox="0 0 {W} {total_h:.0f}" role="img" aria-labelledby="{sid}">'
            f'<title id="{sid}">{x(aria)}</title>{"".join(parts)}</svg>')


def _flow_tall(flow: dict, sid: str, aria: str) -> str:
    steps, side = flow["steps"], flow.get("side")
    n = len(steps)
    W, M, GAP = 360, 6, 34
    sw = 118
    w = W - 2 * M - (sw + 14 if side else 0)
    lines = [_node_lines(s, w - 32, 17, 13.5) for s in steps]
    hs = [_node_h(l) for l in lines]
    y, parts = M, []
    for i, s in enumerate(steps):
        parts.append(_node(i, n, s, M, y, w, hs[i], lines[i]))
        if side and side["at"] == i:
            _, sh = _side_box(side, 0, 0, sw, 12.5)
            box, sh = _side_box(side, W - M - sw, max(M, y + (hs[i] - sh) / 2), sw, 12.5)
            parts.append(box)
            parts.append(_arrow(W - M - sw - 3, y + hs[i] / 2, M + w + 5, y + hs[i] / 2, "flow-dash"))
        if i + 1 < n:
            parts.append(_arrow(M + w / 2, y + hs[i] + 4, M + w / 2, y + hs[i] + GAP - 4))
        y += hs[i] + GAP
    total = y - GAP + M
    return (f'<svg class="flow flow-tall" viewBox="0 0 {W} {total:.0f}" role="img" aria-labelledby="{sid}">'
            f'<title id="{sid}">{x(aria)}</title>{"".join(parts)}</svg>')


# ---------------------------------------------------------------------------
# SVG: recommended vs upper limit gauge
# ---------------------------------------------------------------------------
def gauge_svg(r: dict, name: str, sid: str) -> str:
    """A bar from zero, with the adult (default age band) recommended amounts, the upper limit or the reference limit."""
    unit = r.get("unit", "")
    b = default_band()
    blabel = dict(bands()).get(b, b)
    rec = r.get("recommended")
    ul = r.get("upper_limit")
    ticks: list[tuple[float, str, str]] = []   # value, label, cls
    zones: list[tuple[float, float, str]] = []
    ul_v = None
    if rec:
        fv, mv = _band_value(rec, "female", b), _band_value(rec, "male", b)
        kind = rec.get("kind", "RDA")
        if fv is not None and mv is not None and fv == mv:
            ticks.append((fv, f"{kind} {fmt(fv)} {unit}", "g-tick-rec"))
        else:
            if fv is not None:
                ticks.append((fv, f"Women {fmt(fv)} {unit}", "g-tick-rec"))
            if mv is not None:
                ticks.append((mv, f"Men {fmt(mv)} {unit}", "g-tick-rec"))
    if ul and ul_all_sources(r):
        # a UL that covers only supplements (magnesium, vitamin E) or one form (vitamin A, folic acid) is not a cap on
        # the total, so it is not drawn on the same bar; the text beside the gauge explains it
        uf, um = _band_value(ul, "female", b), _band_value(ul, "male", b)
        vals = [v for v in (uf, um) if v is not None]
        if vals:
            ul_v = min(vals)
    lim_v = limit_value(r)
    if not ticks and ul_v is None and lim_v is None:
        return ""
    recs0 = [v for v, _, _ in ticks]
    top0 = max([v for v in [ul_v, lim_v] if v is not None] + recs0)
    vmax0 = top0 * (1.25 if (ul_v or lim_v) else 1.6)
    if len(ticks) == 2 and abs(ticks[0][0] - ticks[1][0]) / vmax0 * 420 < 130:
        # two recommended amounts too close to label apart: one label, two tick marks
        (fv_, _, _), (mv_, _, _) = ticks
        ticks = [(min(fv_, mv_), f"Women {fmt(fv_)} · Men {fmt(mv_)} {unit}", "g-tick-rec"), (max(fv_, mv_), "", "g-tick-rec")]
    recs = [v for v, _, _ in ticks]
    top_ref = max([v for v in [ul_v, lim_v] if v is not None] + recs)
    vmax = top_ref * (1.25 if (ul_v or lim_v) else 1.6)
    W, M = 440, 10
    X0, X1 = M + 4, W - M - 4
    sx = lambda v: X0 + (X1 - X0) * (v / vmax)  # noqa: E731
    # label rows above the bar: a new row whenever a label would crowd the previous one
    rows_needed, lx = 0, -999.0
    for v, lab_, _ in sorted(ticks):
        if not lab_:
            continue
        rows_needed = rows_needed + 1 if sx(v) - lx < 130 and lx > -999 else rows_needed
        lx = sx(v)
    TY, TH = 36 + rows_needed * 24, 18
    if recs and ul_v is not None:
        zones = [(0, min(recs), "g-low"), (min(recs), ul_v, "g-ok"), (ul_v, vmax, "g-high")]
    elif recs and lim_v is not None:
        zones = [(0, min(recs), "g-low"), (min(recs), lim_v, "g-ok"), (lim_v, vmax, "g-high")]
    elif recs:
        zones = [(0, min(recs), "g-low"), (min(recs), vmax, "g-ok")]
    elif lim_v is not None:
        zones = [(0, lim_v, "g-ok"), (lim_v, vmax, "g-high")]
    parts = [f'<rect class="g-track" x="{X0}" y="{TY}" width="{X1 - X0}" height="{TH}" rx="{TH / 2}"/>']
    for a, bnd, cls in zones:
        if bnd > a:
            parts.append(f'<rect class="{cls}" x="{sx(a):.1f}" y="{TY}" width="{sx(bnd) - sx(a):.1f}" height="{TH}"/>')
    parts.append(f'<rect class="g-outline" x="{X0}" y="{TY}" width="{X1 - X0}" height="{TH}" rx="{TH / 2}"/>')
    # recommended ticks above the bar, staggered when they crowd
    last_x, row = -999.0, 0
    for v, label, cls in sorted(ticks):
        px = sx(v)
        if label:
            row = row + 1 if px - last_x < 130 else 0
        ly = TY - 12 - row * 24
        anchor = "start" if px < X0 + 70 else ("end" if px > X1 - 70 else "middle")
        parts.append(f'<line class="g-tick" x1="{px:.1f}" y1="{ly + 5:.1f}" x2="{px:.1f}" y2="{TY + TH + 4:.1f}"/>')
        if label:
            parts.append(f'<text class="g-label" x="{px:.1f}" y="{ly:.1f}" text-anchor="{anchor}">{x(label)}</text>')
            last_x = px
    below = []
    if ul_v is not None:
        uf, um = _band_value(ul, "female", b), _band_value(ul, "male", b)
        lab = f"Upper limit {fmt(ul_v)} {unit}" if (uf == um or um is None or uf is None) else f"Upper limit {fmt(uf)} / {fmt(um)} {unit}"
        below.append((ul_v, lab, "g-tick-ul"))
    if lim_v is not None:
        lim = r["limit"]
        lab = (f"Limit {fmt(round(lim_v))} {unit} at 2,000 kcal" if lim.get("kind") == "pct_energy" else f"Limit {fmt(lim_v)} {unit}")
        below.append((lim_v, lab, "g-tick-ul"))
    for v, label, cls in below:
        px = sx(v)
        anchor = "start" if px < X0 + 80 else ("end" if px > X1 - 80 else "middle")
        parts.append(f'<line class="g-tick {cls}" x1="{px:.1f}" y1="{TY - 4:.1f}" x2="{px:.1f}" y2="{TY + TH + 16:.1f}"/>')
        parts.append(f'<text class="g-label g-label-ul" x="{px:.1f}" y="{TY + TH + 36:.1f}" text-anchor="{anchor}">{x(label)}</text>')
    parts.append(f'<text class="g-axis" x="{X0}" y="{TY + TH + 66:.1f}">0</text>')
    parts.append(f'<text class="g-axis" x="{X1}" y="{TY + TH + 66:.1f}" text-anchor="end">Adults {x(blabel)}, per day</text>')
    H = TY + TH + 74
    desc = "; ".join(l for _, l, _ in ticks + below if l)
    aria = f"{name} reference amounts for adults aged {blabel}: {desc}"
    legend = []
    if any(c == "g-low" for *_, c in zones):
        legend.append('<span class="gl gl-low">Below the recommended amount</span>')
    if any(c == "g-ok" for *_, c in zones):
        legend.append('<span class="gl gl-ok">' + ("Recommended range" if (recs and (ul_v or lim_v)) else ("Meets the reference" if recs else "Within the limit")) + "</span>")
    if any(c == "g-high" for *_, c in zones):
        legend.append('<span class="gl gl-high">' + ("Above the upper limit" if ul_v is not None else "Above the limit") + "</span>")
    return (f'<figure class="gauge"><svg viewBox="0 0 {W} {H:.0f}" role="img" aria-labelledby="{sid}"><title id="{sid}">{x(aria)}</title>'
            f'{"".join(parts)}</svg><figcaption class="gauge-legend">{"".join(legend)}</figcaption></figure>')


# ---------------------------------------------------------------------------
# page sections
# ---------------------------------------------------------------------------
DIET_LABEL = {"veg": "Vegetarian", "nonveg": "Non-vegetarian"}


def _photo(slug: str, f: dict, eager: bool) -> str:
    p = f["photo"]
    load = 'fetchpriority="high" decoding="async"' if eager else 'loading="lazy" decoding="async"'
    return (f'<figure class="nt-photo"><img src="{asset(f"/assets/nutrients/{slug}.webp")}" alt="{x(p["alt"])}" '
            f'width="{p["out_w"]}" height="{p["out_h"]}" {load}>'
            f'<figcaption>Photo: <a href="{x(p["page_url"])}" rel="noopener">{x(p["photographer"])}</a> on {x(p["site"])} '
            f'(<a href="{x(p["licence_url"])}" rel="noopener">{x(p["licence"])}</a>)</figcaption></figure>')


def _hero(slug: str, f: dict, r: dict, n_src: int) -> str:
    style = {"target": "Daily target", "limit": "Daily limit", "info": "For information"}.get(r.get("style"), "")
    unit_pill = f"Tracked in {r.get('unit', '')}" if r.get("unit") else ""
    meta = [CATEGORIES[f["category"]]["label"], unit_pill, style,
            f"{n_src} sources · checked {CHECKED}"]
    pills = "".join(f"<span>{x(m)}</span>" for m in meta if m)
    return (
        f'<header class="hero compact nt-hero"><div class="container">{crumbs_html([("Nutrients", "/nutrients")], x(f["name"]))}'
        f'<div class="hero-split"><div class="hero-text">{eyebrow(x(f["kicker"]))}'
        f'<h1 class="hero-title">{f["h1"]}</h1><p class="hero-lede">{t(f["summary"])}</p>'
        f'<div class="nt-meta">{pills}</div>'
        f'<p class="cta-note"><a class="link-arrow" href="#how-much">How much you need</a></p></div>'
        f'<div class="hero-visual">{_photo(slug, f, eager=True)}</div></div></div></header>'
    )


def _does(f: dict, num: str) -> str:
    cards = "".join(
        f'<div class="nt-card"><span class="nt-ico">{glyph(d["icon"])}</span><h3>{t(d["title"])}</h3><p>{t(d["text"])}</p></div>'
        for d in f["does"]
    )
    return band("paper", section_head("What it does", f"What {x(lname(f))} does <em>for you</em>.", num=num)
                + f'<div class="nt-cards">{cards}</div>')


def _flow(slug: str, f: dict, num: str) -> str:
    fl = f["flow"]
    return band("paper alt", section_head("How your body uses it", x(plain(fl["title"])) + ".", num=num)
                + f'<div class="flow-wrap">{flow_svg(fl, f["name"], "flow-" + slug)}</div>'
                + f'<p class="nt-caption">{t(fl["caption"])}</p>', "flow")


def _food_table(rows: list[dict], heading: str, amount_head: str = "Amount") -> str:
    body = "".join(f'<tr><th scope="row">{t(rw["food"])}</th><td class="num">{t(rw.get("amount") or "—")}</td></tr>' for rw in rows)
    return (f'<div class="nt-food"><h3>{heading}</h3><div class="table-wrap"><table class="data-table food-table">'
            f'<thead><tr><th scope="col">Food and serving</th><th scope="col" class="num">{amount_head}</th></tr></thead>'
            f"<tbody>{body}</tbody></table></div></div>")


def _sources_section(slug: str, f: dict, num: str) -> str:
    fd = f["foods"]
    rows = fd["rows"]
    veg = [r for r in rows if r.get("diet") == "veg"]
    non = [r for r in rows if r.get("diet") == "nonveg"]
    if veg and non:
        tables = f'<div class="nt-food-grid">{_food_table(veg, glyph("leaf") + "Vegetarian")}{_food_table(non, glyph("fish") + "Non-vegetarian")}</div>'
    else:
        tables = _food_table(rows, "Foods", fd.get("amount_head", "Amount"))
    src_n = fd.get("source")
    lead = "Amounts per serving from" if fd.get("amount_head", "Amount") == "Amount" else "Figures from"
    src_note = f' {lead}{_refs(f" [{src_n}]")}.' if src_n else ""
    chips = "".join(f'<span class="nt-chip">{x(c)}</span>' for c in f.get("chips", []))
    other = ""
    if f.get("other_sources"):
        cells = "".join(
            f'<div class="nt-card nt-card-row"><span class="nt-ico">{glyph(o.get("kind", "other"))}</span><div><h3>{t(o["label"])}</h3><p>{t(o["text"])}</p></div></div>'
            for o in f["other_sources"])
        other = f'<div class="nt-cards nt-cards-3">{cells}</div>'
    return band("paper", section_head("Where to get it", "Foods and <em>other sources</em>.", num=num)
                + (f'<div class="nt-chips" aria-label="Foods that provide {x(f["name"])}">{chips}</div>' if chips else "")
                + tables + f'<p class="nt-caption">{t(fd["note"])}{src_note}</p>' + other, "sources-food")


UNIT_LABEL = {"mcg": "mcg", "mg": "mg", "g": "g"}
LIMIT_BASIS = {"CDRR": "Chronic Disease Risk Reduction Intake (CDRR)"}


def _how_much(slug: str, f: dict, r: dict, cite: str, num: str) -> str:
    name = f["name"]
    unit = UNIT_LABEL.get(r.get("unit", ""), r.get("unit", ""))
    rec, ul, lim = r.get("recommended"), r.get("upper_limit"), r.get("limit")
    has_ul = bool(ul and (ul.get("male") or ul.get("female")))
    iu = iu_factor(r)
    left: list[str] = []

    def cell(v):
        out = f"{fmt(v)} {unit}" if v is not None else "—"
        if iu and v is not None:
            out += f" <small>({fmt(v * iu)} IU)</small>"
        return out

    if rec:
        kind = rec.get("kind", "RDA")
        head = '<tr><th scope="col">Age</th><th scope="col" class="num">Women</th><th scope="col" class="num">Men</th>'
        head += '<th scope="col" class="num">Upper limit</th></tr>' if has_ul else "</tr>"
        body = []
        for bid, blabel in bands():
            fv, mv = _band_value(rec, "female", bid), _band_value(rec, "male", bid)
            hl = ' class="hl"' if bid == default_band() else ""
            row = f'<tr{hl}><th scope="row">{x(blabel)}</th><td class="num">{cell(fv)}</td><td class="num">{cell(mv)}</td>'
            if has_ul:
                uf, um = _band_value(ul, "female", bid), _band_value(ul, "male", bid)
                row += f'<td class="num">{cell(uf) if (uf == um or um is None) else cell(uf) + " / " + cell(um)}</td>'
            body.append(row + "</tr>")
        kind_txt = ("<strong>RDA</strong> (Recommended Dietary Allowance) is the daily amount that meets the needs of nearly all healthy people."
                    if kind == "RDA" else
                    "<strong>AI</strong> (Adequate Intake) is used when there is not enough evidence for an RDA: an amount assumed to be enough.")
        left.append(
            f'<div class="table-wrap"><table class="data-table amount-table"><caption class="sr-only">{x(name)}: daily {kind} by age and sex</caption>'
            f"<thead>{head}</thead><tbody>{''.join(body)}</tbody></table></div>"
            f'<p class="nt-caption">{kind_txt} Per day, for adults{cite}.</p>')
    if has_ul:
        scope = x(ul_scope_text(ul))
        note = t(ref_note(ul["note"])) if ul.get("note") else f"It applies {scope}."
        left.append(f'<p class="nt-caption"><strong>Upper limit (UL)</strong> is the most per day that is unlikely to cause harm. {note}</p>')
    if lim and lim.get("kind") == "fixed":
        basis = LIMIT_BASIS.get(lim.get("basis", ""), "")
        left.append(f'<div class="nt-limit"><span class="nt-limit-v">{fmt(lim["value"])} {x(unit)}</span>'
                    f'<span>Reference limit per day for adults{(": " + x(basis)) if basis else ""}{cite}</span></div>')
    elif lim and lim.get("kind") == "pct_energy":
        cells = "".join(
            f'<tr><th scope="row">{fmt(k)} kcal</th><td class="num">{fmt(round(k * lim["pct"] / 100 / lim["kcal_per_unit"]))} {x(unit)}</td></tr>'
            for k in (1600, 2000, 2400, 2800))
        left.append(
            f'<div class="nt-limit"><span class="nt-limit-v">Under {fmt(lim["pct"])}% of calories</span>'
            f"<span>Reference limit for adults{cite}. In grams it depends on how much you eat ({fmt(lim['kcal_per_unit'])} kcal per gram):</span></div>"
            f'<div class="table-wrap narrow"><table class="data-table amount-table"><caption class="sr-only">{x(name)} limit in grams by daily calories</caption>'
            f'<thead><tr><th scope="col">Daily calories</th><th scope="col" class="num">Less than</th></tr></thead><tbody>{cells}</tbody></table></div>')
    if not rec and not lim and not has_ul:
        left.append(f'<div class="nt-limit nt-limit-info"><span class="nt-limit-v">No daily number</span>'
                    f"<span>No recommended amount or upper limit is set for {x(lname(f))}{cite}, so Ayuvo charts your intake without a reference line.</span></div>")
    notes = [ref_note(n) for n in r.get("notes") or []]
    if notes:
        left.append('<ul class="nt-notes">' + "".join(f"<li>{t(n)}</li>" for n in notes) + "</ul>")
    gauge = gauge_svg(r, name, f"gauge-{slug}")
    right = gauge
    if gauge and has_ul and not ul_all_sources(r):
        right += (f'<p class="nt-caption">The upper limit for {x(lname(f))} covers only part of your intake ({x(ul_scope_text(ul))}), '
                  "so it is not drawn on this bar.</p>")
    inner = f'<div class="nt-howmuch"><div>{"".join(left)}</div>' + (f"<div>{right}</div>" if right else "") + "</div>"
    if not right:
        inner = f'<div class="nt-howmuch single"><div>{"".join(left)}</div></div>'
    lede = ("The same reference table draws the reference lines on your charts in Ayuvo. "
            "Needs differ in pregnancy and breastfeeding, which are not covered here.")
    return band("ink raised", section_head("How much you need", "Your daily <em>reference amounts</em>.", lede, num=num) + inner, "how-much")


def _too(f: dict, num: str) -> str:
    cols = []
    for key, cls, ico, title in (("too_little", "low", "down", "Too little"), ("too_much", "high", "up", "Too much")):
        d = f.get(key)
        if not d:
            continue
        chips = "".join(f"<li>{t(i)}</li>" for i in d["items"])
        cols.append(f'<div class="nt-too nt-too-{cls}"><div class="nt-too-head">{glyph(ico)}<h3>{title}</h3></div>'
                    f'<p>{t(d["intro"])}</p><ul class="nt-too-list">{chips}</ul></div>')
    if not cols:
        return ""
    grid = "nt-too-grid" if len(cols) == 2 else "nt-too-grid one"
    return band("paper alt", section_head("Balance", "Too little, <em>too much</em>.",
                                          "Signs like these have many possible causes. They are a reason to talk to a clinician, not to self-treat.", num=num)
                + f'<div class="{grid}">{"".join(cols)}</div>')


def _marker(f: dict, num: str) -> str:
    m = f.get("marker")
    if not m:
        return ""
    tbl = ""
    if m.get("table"):
        head = m.get("table_head") or ["Result", "What it may mean"]
        rows = "".join(f'<tr><th scope="row" class="num">{t(a)}</th><td>{t(b_)}</td></tr>' for a, b_ in m["table"])
        tbl = (f'<div class="table-wrap marker-wrap"><table class="data-table marker-table"><thead><tr><th scope="col">{t(head[0])}</th>'
               f'<th scope="col">{t(head[1])}</th></tr></thead><tbody>{rows}</tbody></table></div>')
    return band("paper", f'<div class="nt-marker"><div class="nt-marker-ico">{glyph("lab")}</div><div>'
                + section_head(m.get("section", "Blood test"), x(plain(m["name"])) + ".", num=num)
                + f'<p class="nt-lede">{t(m["text"])}</p>{tbl}'
                + '<p class="nt-caption">Labs use different units and ranges. Your clinician reads a result alongside your history and other tests.</p></div></div>')


def _supplements(f: dict, num: str) -> str:
    s = f.get("supplements")
    if not s:
        return ""
    cards = "".join(
        f'<div class="nt-card nt-card-row"><span class="nt-ico">{glyph("supplement")}</span><div><h3>{t(fm["name"])}</h3><p>{t(fm["text"])}</p></div></div>'
        for fm in s["forms"])
    return band("paper alt", section_head("Supplements", "Supplement <em>forms</em>.", num=num)
                + f'<p class="nt-lede">{t(s["intro"])}</p><div class="nt-cards nt-cards-2">{cards}</div>'
                + '<p class="nt-caption">Supplements can interact with medicines. Talk to a clinician or pharmacist before you start one, especially at high doses.</p>')


def _remember(f: dict, num: str) -> str:
    items = "".join(f"<li><span>{t(i)}</span></li>" for i in f["remember"])
    return band("ink", section_head("Key points", "Remember <em>these five</em>.", num=num) + f'<ol class="nt-remember">{items}</ol>')


def _sources(all_src: list[dict], f: dict) -> str:
    items = "".join(
        f'<li id="src-{i}"><a href="{x(s["url"])}" rel="noopener">{x(plain(s["title"]))}</a>'
        f'{(" · " + x(s["publisher"])) if s.get("publisher") else ""} <span class="tag plain">checked {x(s.get("checked") or CHECKED)}</span></li>'
        for i, s in enumerate(all_src, 1))
    disc = (
        '<div class="disclaimer"><p><strong>General information, not medical advice.</strong> This guide is for healthy adults and '
        "does not cover pregnancy, breastfeeding, children or medical conditions. It cannot diagnose or treat anything. "
        "Talk to a clinician before starting high-dose supplements, or if you think you may have too little or too much of a nutrient. "
        f'Spotted an error? <a href="mailto:{EMAIL}?subject=Nutrient%20guide%20correction">Email us</a>.</p></div>'
    )
    return band("paper", f'<div class="prose wide"><h2>Sources</h2><p>Facts on this page come from these sources, checked on the dates shown. '
                         f"Intake numbers come from Ayuvo's shared reference table, which cites the sources marked for them.</p>"
                         f'<ol class="src-list">{items}</ol>{disc}</div>', "sources")


def _related(slug: str, f: dict) -> str:
    same = [s for s in ORDER if s != slug and FACTS[s]["category"] == f["category"]][:6]
    others = [s for s in ORDER if s != slug and s not in same][: max(0, 8 - len(same))]
    chips = "".join(f'<a class="chip" href="/nutrients/{s}"><span>{x(FACTS[s]["name"])}<small>{x(FACTS[s]["hub_line"])}</small></span></a>'
                    for s in same + others[:2])
    return band("paper alt", section_head("More guides", "Keep <em>reading</em>.") + f'<div class="works-with">{chips}</div>'
                + '<p class="cta-note"><a class="link-arrow" href="/nutrients">All nutrient guides</a></p>')


def page_sources(slug: str) -> tuple[list[dict], str]:
    """Fact sources first (their [n] markers stay valid), then the reference sources for the numbers."""
    f = FACTS[slug]
    r = ref_for(slug)
    src = [dict(s, checked=CHECKED) for s in f["sources"]]
    urls = [s["url"].rstrip("/") for s in src]
    ref_src = load_reference().get("sources", {})
    marks = []
    for sid in r.get("source_ids", []):
        s = ref_src.get(sid)
        if not s:
            continue
        u = s["url"].rstrip("/")
        if u in urls:
            marks.append(urls.index(u) + 1)
        else:
            src.append({"title": s["title"], "publisher": s.get("publisher", ""), "url": s["url"], "checked": s.get("checked", CHECKED)})
            urls.append(u)
            marks.append(len(src))
    cite = "".join(_refs(f" [{m}]") for m in sorted(set(marks)))
    return src, cite


def build_nutrient(slug: str) -> Page:
    f = FACTS[slug]
    r = ref_for(slug)
    src, cite = page_sources(slug)
    nums = iter(f"{i:02d}" for i in range(1, 20))
    body = glyph_sprite() + _hero(slug, f, r, len(src))
    body += _does(f, next(nums))
    body += _flow(slug, f, next(nums))
    body += _sources_section(slug, f, next(nums))
    body += _how_much(slug, f, r, cite, next(nums))
    too = _too(f, "")
    if too:
        body += _too(f, next(nums))
    if f.get("marker"):
        body += _marker(f, next(nums))
    if f.get("supplements"):
        body += _supplements(f, next(nums))
    body += _remember(f, next(nums))
    faqs = [(q["q"], q["a"]) for q in f["faqs"]]
    body += faq_section(faqs, heading=f"Questions <em>about {x(lname(f))}</em>.")
    if app_tracked(slug):
        body += cta_section(
            f"Track {x(lname(f))} <em>in Ayuvo</em>.",
            f"Ayuvo charts {x(lname(f))} by day, week, month and year, with the reference lines from this page, personalised by age and sex where they differ. "
            "Supplements you log in Medications count too. Free, with no account.")
    else:
        body += cta_section(
            f"Track {x(lname(f))} <em>in Ayuvo</em>.",
            f"Food logging does not estimate {x(lname(f))}, so Ayuvo tracks it from supplements you log in Medications, and from Apple Health "
            "on iPhone or Health Connect on Android when another app saves it there. Charts by day, week, month and year use the reference "
            "lines from this page, personalised by age and sex where they differ. Free, with no account.")
    body += _sources(src, f)
    body += _related(slug, f)
    url = f"{BASE}/nutrients/{slug}"
    img = BASE + f"/assets/nutrients/{slug}.webp"
    ld = [{
        "@context": "https://schema.org", "@type": "Article", "@id": f"{url}#article",
        "headline": plain(f["title"]).replace(" | Ayuvo", ""), "description": f["description"],
        "image": [img], "datePublished": PUBLISHED, "dateModified": PUBLISHED, "inLanguage": "en",
        "author": {"@id": f"{BASE}/#organization"}, "publisher": {"@id": f"{BASE}/#organization"},
        "mainEntityOfPage": {"@id": f"{url}#webpage"}, "isPartOf": {"@id": f"{BASE}/nutrients#list"},
        "about": {"@type": "Thing", "name": f["name"]},
        "citation": [s["url"] for s in src], "isAccessibleForFree": True,
    }]
    return Page(path=f"/nutrients/{slug}", title=f["title"], description=f["description"], body=body,
                crumbs=[("Nutrients", "/nutrients")], crumb=f["name"], og_headline=plain(f["h1"]),
                og_alt=f"{f['name']}: what it does, food sources and how much you need", og_photo=f"/assets/nutrients/{slug}.webp",
                lcp=f"/assets/nutrients/{slug}.webp", faqs=faqs, ld=ld, main_entity=f"{url}#article", modified=PUBLISHED)


# ---------------------------------------------------------------------------
# hub
# ---------------------------------------------------------------------------
HUB_FAQ = [
    ("Where do the numbers on these pages come from?",
     "Recommended amounts and upper limits come from one shared reference table built from the NIH Office of Dietary Supplements fact sheets, "
     "the National Academies' Dietary Reference Intakes and the Dietary Guidelines for Americans. The Ayuvo app draws its chart lines from the same table."),
    ("Are these guides medical advice?",
     "No. They are general information for healthy adults. They do not cover pregnancy, breastfeeding, children or medical conditions. "
     "Talk to a clinician before starting high-dose supplements."),
    ("Does Ayuvo count supplements?",
     "Yes, for every nutrient on these pages. When you add a supplement in Medications with the nutrients it contains, every dose you mark "
     "as taken is added to that day's totals, next to anything the food log counts."),
    ("Which nutrients does the food log not estimate?",
     f"The Ayuvo food log estimates {{tracked}} nutrients. {{health_word}} others, such as iodine, selenium and most B vitamins, are not estimated from food. "
     "Ayuvo tracks them from supplements you log in Medications, and from Apple Health on iPhone or Health Connect on Android when another "
     "app has saved them there, with reference lines from the same table."),
]


def hub_faq() -> list[tuple[str, str]]:
    tracked = sum(1 for s in ORDER if app_tracked(s))
    health = len(ORDER) - tracked
    words = {14: "Fourteen", 13: "Thirteen", 15: "Fifteen", 12: "Twelve"}
    return [(q, a.format(tracked=tracked, health_word=words.get(health, str(health)))) for q, a in HUB_FAQ]


def _hub_card(slug: str, eager: bool = False) -> str:
    f = FACTS[slug]
    r = ref_for(slug)
    style = {"target": "Target", "limit": "Limit", "info": "Info"}.get(r.get("style"), "")
    load = 'decoding="async"' if eager else 'loading="lazy" decoding="async"'
    return (f'<a class="nt-hub-card" href="/nutrients/{slug}"><img src="{asset(f"/assets/nutrients/{slug}-thumb.webp")}" alt="" '
            f'width="{f["photo"]["thumb_w"]}" height="{f["photo"]["thumb_h"]}" {load}>'
            f'<div class="nt-hub-body"><h3>{x(f["name"])}</h3><p>{x(f["hub_line"])}</p>'
            f'<span class="nt-style nt-style-{r.get("style", "info")}">{style}</span></div></a>')


def chart_illustration() -> str:
    """A schematic of the app's nutrient chart: bars per day, a dashed Recommended line and an Upper limit line."""
    W, H, M = 560, 260, 34
    days = ["M", "T", "W", "T", "F", "S", "S"]
    heights = [0.55, 0.8, 0.62, None, 0.95, 0.7, 1.5]   # a shape, not data; one empty day stays empty
    base, top = H - 40, 40
    scale = (base - top) / 1.6
    bw = (W - 2 * M - 40) / 7 * 0.56
    parts = [f'<line class="ci-axis" x1="{M}" y1="{base}" x2="{W - M}" y2="{base}"/>']
    for i, (d, v) in enumerate(zip(days, heights)):
        cx = M + 20 + (W - 2 * M - 40) / 7 * (i + 0.5)
        if v is not None:
            parts.append(f'<rect class="ci-bar{" ci-over" if v > 1.35 else ""}" x="{cx - bw / 2:.1f}" y="{base - v * scale:.1f}" width="{bw:.1f}" height="{v * scale:.1f}" rx="6"/>')
        parts.append(f'<text class="ci-day" x="{cx:.1f}" y="{base + 22}" text-anchor="middle">{d}</text>')
    ry, uy = base - 1.0 * scale, base - 1.35 * scale
    parts.append(f'<line class="ci-rec" x1="{M}" y1="{ry:.1f}" x2="{W - M}" y2="{ry:.1f}"/>')
    parts.append(f'<text class="ci-lab ci-lab-rec" x="{M}" y="{ry - 8:.1f}">Recommended</text>')
    parts.append(f'<line class="ci-ul" x1="{M}" y1="{uy:.1f}" x2="{W - M}" y2="{uy:.1f}"/>')
    parts.append(f'<text class="ci-lab ci-lab-ul" x="{M}" y="{uy - 8:.1f}">Upper limit</text>')
    return (f'<figure class="nt-illus"><svg viewBox="0 0 {W} {H}" role="img" aria-labelledby="ci-title">'
            '<title id="ci-title">Illustration of an Ayuvo nutrient chart: daily bars with a dashed Recommended line and an Upper limit line. One day has no entry and is left empty.</title>'
            f'{"".join(parts)}</svg><figcaption>Illustration, not real data. A day with nothing logged stays empty, never zero.</figcaption></figure>')


def build_nutrients_hub() -> Page:
    groups = []
    first = True
    for cat, meta in CATEGORIES.items():
        slugs = [s for s in ORDER if FACTS[s]["category"] == cat]
        if not slugs:
            continue
        cards = "".join(_hub_card(s, eager=first and i < 4) for i, s in enumerate(slugs))
        first = False
        groups.append(f'<div class="nt-hub-group" id="{cat}"><div class="nt-hub-head"><h2>{meta["label"]}</h2><p>{meta["line"]}</p></div>'
                      f'<div class="nt-hub-grid">{cards}</div></div>')
    jump = "".join(f'<a class="nt-chip" href="#{c}">{m["label"]}</a>' for c, m in CATEGORIES.items() if any(FACTS[s]["category"] == c for s in ORDER))
    hero = (
        '<header class="hero compact"><div class="container">' + crumbs_html([], "Nutrients")
        + f'<div class="hero-center">{eyebrow("Nutrient guides")}<h1 class="hero-title">What is in your food, <em>and why it matters.</em></h1>'
        f'<p class="hero-lede">Short, visual guides to the {len(ORDER)} nutrients Ayuvo charts: what each one does, where to find it, how much you need, '
        "and what too little or too much looks like. Every fact links to its source.</p>"
        f'<div class="nt-chips center">{jump}</div></div></div></header>'
    )
    sprite = glyph_sprite()
    how = band("ink raised", '<div class="split"><div class="split-text">' + eyebrow("In the app")
               + "<h2>Every nutrient, <em>charted with its reference lines</em>.</h2>"
               "<p>Open any nutrient from Nutrition Details or Browse to see it by day, week, month, six months or a year. "
               "A Recommended line and an Upper limit line are drawn from the same table these pages use, personalised by your age and sex. "
               "Set your own goal and it replaces the Recommended line.</p>"
               "<ul><li><span>Supplements you add in Medications count toward the totals when you mark a dose taken</span></li>"
               "<li><span>Weekly supplements show an average per logged day, so a weekly dose is not mistaken for a daily one</span></li>"
               "<li><span>Nutrients with no reference, such as total sugar, are shown without a line rather than an invented one</span></li>"
               "<li><span>Vitamins and minerals food logging does not estimate, such as iodine or biotin, are charted from supplements in Medications "
               "and from Apple Health or Health Connect, with the same lines</span></li></ul>"
               '</div><div class="split-media">' + chart_illustration() + "</div></div>")
    disc = band("paper", '<div class="prose wide"><div class="disclaimer"><p><strong>General information, not medical advice.</strong> These guides are for healthy adults '
                "and do not cover pregnancy, breastfeeding, children or medical conditions. They cannot diagnose or treat anything. "
                "Talk to a clinician before starting high-dose supplements.</p></div></div>")
    tracked = sum(1 for s in ORDER if app_tracked(s))
    note = (f'<p class="nt-caption nt-hub-note">The Ayuvo food log estimates {tracked} of these nutrients. For the other {len(ORDER) - tracked}, '
            "such as iodine or biotin, Ayuvo tracks supplements you log in Medications and data other apps save to "
            "Apple Health on iPhone or Health Connect on Android.</p>")
    body = (sprite + hero
            + band("paper", note + "".join(groups), "guides")
            + how
            + faq_section(hub_faq(), heading="About <em>these guides</em>.")
            + cta_section("Know what you eat. <em>See it charted.</em>",
                          "Ayuvo tracks more than 30 nutrients from photos, barcodes, voice or text, and draws each one against its reference lines. Free, with no account.")
            + disc)
    ld = [{
        "@context": "https://schema.org", "@type": "ItemList", "@id": f"{BASE}/nutrients#list", "name": "Ayuvo nutrient guides",
        "itemListOrder": "https://schema.org/ItemListUnordered", "numberOfItems": len(ORDER),
        "itemListElement": [{"@type": "ListItem", "position": i, "name": FACTS[s]["name"], "url": f"{BASE}/nutrients/{s}"}
                            for i, s in enumerate(ORDER, 1)],
    }]
    return Page(path="/nutrients", title="Nutrient Guides: Vitamins, Minerals, Fats & Sugar | Ayuvo",
                description=f"Visual, sourced guides to {len(ORDER)} nutrients: what each does, food sources, how much adults need, and signs of too little or too much.",
                body=body, kind="collection", crumb="Nutrients", og_headline="What is in your food, and why it matters.",
                og_alt="Ayuvo nutrient guides: vitamins, minerals, fats and sugar", og_photo="/assets/nutrients/vitamin-d.webp",
                faqs=hub_faq(), ld=ld, main_entity=f"{BASE}/nutrients#list", modified=PUBLISHED, lcp=None)


def nutrient_pages() -> list[Page]:
    return [build_nutrients_hub()] + [build_nutrient(s) for s in ORDER]
