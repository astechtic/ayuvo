"""Shared building blocks for the Ayuvo static site (see scripts/web_build.py).

Everything here returns plain HTML strings. The site is served under a strict CSP
(no inline script or style, no third-party hosts), so components only emit markup
that the stylesheet at web/styles.css already styles.
"""
from __future__ import annotations

import hashlib
import html
import json
from dataclasses import dataclass, field
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
WEB = ROOT / "web"

BASE = "https://ayuvo-health.web.app"
GITHUB = "https://github.com/astechtic/ayuvo"
FUD_AI = "https://github.com/apoorvdarshan/fud-ai"
EMAIL = "yaaratech@gmail.com"
SITE_NAME = "Ayuvo"

# Both listings answered 404 when the site was upgraded (2026-09-25). While `live` is False the
# official badges are replaced by a plain "coming soon" note: a badge must always link to a real
# listing. Flip the flag (and confirm the URL) the day each app is published.
STORES = {
    "ios": {"url": "https://apps.apple.com/app/id6811947230", "app_id": "6811947230", "live": False},
    "android": {"url": "https://play.google.com/store/apps/details?id=com.ayuvo.health", "live": False},
}

CATALOG = json.loads((ROOT / "local-models" / "catalog.v2.json").read_text(encoding="utf-8"))

# ---------------------------------------------------------------------------
# icon sprite
# ---------------------------------------------------------------------------
SPRITE = """<svg width="0" height="0" class="sr-only" aria-hidden="true" focusable="false">
  <symbol id="i-apple" viewBox="0 0 24 24"><path d="M12.152 6.896c-.948 0-2.415-1.078-3.96-1.04-2.04.027-3.91 1.183-4.961 3.014-2.117 3.675-.546 9.103 1.519 12.09 1.013 1.454 2.208 3.09 3.792 3.039 1.52-.065 2.09-.987 3.935-.987 1.831 0 2.35.987 3.96.948 1.637-.026 2.676-1.48 3.676-2.948 1.156-1.688 1.636-3.325 1.662-3.415-.039-.013-3.182-1.221-3.22-4.857-.026-3.04 2.48-4.494 2.597-4.559-1.429-2.09-3.623-2.324-4.39-2.376-2-.156-3.675 1.09-4.61 1.09zM15.53 3.83c.843-1.012 1.4-2.427 1.245-3.83-1.207.052-2.662.805-3.532 1.818-.78.896-1.454 2.338-1.273 3.714 1.338.104 2.715-.688 3.559-1.701"/></symbol>
  <symbol id="i-play" viewBox="0 0 24 24"><path d="M22.018 13.298l-3.919 2.218-3.515-3.493 3.543-3.521 3.891 2.202a1.49 1.49 0 0 1 0 2.594zM1.337.924a1.486 1.486 0 0 0-.112.568v21.017c0 .217.045.419.124.6l11.155-11.087L1.337.924zm12.207 10.065l3.258-3.238L3.45.195a1.466 1.466 0 0 0-.946-.179l11.04 10.973zm0 2.067l-11 10.933c.298.036.612-.016.906-.183l13.324-7.54-3.23-3.21z"/></symbol>
  <symbol id="i-github" viewBox="0 0 24 24"><path d="M12 .5C5.65.5.5 5.65.5 12c0 5.08 3.29 9.39 7.86 10.91.58.1.79-.25.79-.56v-2c-3.2.7-3.87-1.37-3.87-1.37-.52-1.33-1.28-1.68-1.28-1.68-1.05-.72.08-.7.08-.7 1.15.08 1.76 1.19 1.76 1.19 1.03 1.76 2.7 1.25 3.36.96.1-.75.4-1.25.73-1.54-2.55-.29-5.24-1.28-5.24-5.68 0-1.25.45-2.28 1.19-3.08-.12-.29-.52-1.46.11-3.04 0 0 .97-.31 3.17 1.18a11 11 0 0 1 5.77 0c2.2-1.49 3.17-1.18 3.17-1.18.63 1.58.23 2.75.11 3.04.74.8 1.19 1.83 1.19 3.08 0 4.41-2.69 5.38-5.25 5.67.41.36.78 1.06.78 2.14v3.17c0 .31.21.67.8.56A11.5 11.5 0 0 0 23.5 12C23.5 5.65 18.35.5 12 .5z"/></symbol>
  <symbol id="i-mail" viewBox="0 0 24 24"><path d="M20 4H4a2 2 0 0 0-2 2v12a2 2 0 0 0 2 2h16a2 2 0 0 0 2-2V6a2 2 0 0 0-2-2zm0 4-8 5-8-5V6l8 5 8-5z"/></symbol>
  <symbol id="i-check" viewBox="0 0 24 24"><path d="M9 16.2 4.8 12l-1.4 1.4L9 19 21 7l-1.4-1.4z"/></symbol>
  <symbol id="i-shield" viewBox="0 0 24 24"><path d="M12 2 4 5v6c0 5.5 3.4 10.7 8 12 4.6-1.3 8-6.5 8-12V5z"/></symbol>
  <symbol id="i-sparkle" viewBox="0 0 24 24"><path d="M12 2l2.2 6.3L20.5 10l-6.3 2.2L12 18.5l-2.2-6.3L3.5 10l6.3-1.7z"/></symbol>
  <symbol id="i-lock" viewBox="0 0 24 24"><path d="M17 8h-1V6a4 4 0 0 0-8 0v2H7a2 2 0 0 0-2 2v9a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2v-9a2 2 0 0 0-2-2zm-7-2a2 2 0 0 1 4 0v2h-4z"/></symbol>
  <symbol id="i-heart" viewBox="0 0 24 24"><path d="M12 21s-7.5-4.6-9.5-9.2C1.2 8.6 3.4 5 7 5c2 0 3.4 1.1 5 3 1.6-1.9 3-3 5-3 3.6 0 5.8 3.6 4.5 6.8C19.5 16.4 12 21 12 21z"/></symbol>
  <symbol id="i-doc" viewBox="0 0 24 24"><path d="M6 2h9l5 5v13a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2zm8 1.5V8h4.5L14 3.5zM8 12h8v1.6H8zm0 3.4h8V17H8z"/></symbol>
  <symbol id="i-pill" viewBox="0 0 24 24"><path d="M8.6 3.6a5 5 0 0 1 7.1 0l4.7 4.7a5 5 0 0 1 0 7.1l-5 5a5 5 0 0 1-7.1 0l-4.7-4.7a5 5 0 0 1 0-7.1zm.5 5.6L5.6 12.8a3 3 0 0 0 0 4.2l1.4 1.4a3 3 0 0 0 4.2 0l3.6-3.6z"/></symbol>
  <symbol id="i-dumbbell" viewBox="0 0 24 24"><path d="M3 9h2V7h2v10H5v-2H3zm18 0h-2V7h-2v10h2v-2h2zM8 11h8v2H8z"/></symbol>
  <symbol id="i-fork" viewBox="0 0 24 24"><path d="M7 2v7a2.5 2.5 0 0 0 2 2.4V22h2V11.4A2.5 2.5 0 0 0 13 9V2h-1.5v6h-1V2H9v6H8V2zm9.5 0C14.6 3.4 14 6 14 8.5c0 1.8.9 3 2.5 3.4V22h2V2z"/></symbol>
  <symbol id="i-chart" viewBox="0 0 24 24"><path d="M4 20V11h4v9zm6-16h4v16h-4zm6 9h4v7h-4z"/></symbol>
  <symbol id="i-drop" viewBox="0 0 24 24"><path d="M12 2.5S5.5 9.3 5.5 14a6.5 6.5 0 0 0 13 0C18.5 9.3 12 2.5 12 2.5z"/></symbol>
  <symbol id="i-timer" viewBox="0 0 24 24"><path d="M9 2h6v2H9zm3 4a8 8 0 1 0 0 16 8 8 0 0 0 0-16zm-.8 3h1.6v4.4l3 1.8-.8 1.3-3.8-2.3z"/></symbol>
  <symbol id="i-chat" viewBox="0 0 24 24"><path d="M4 4h16a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2H9l-5 4v-4H4a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2z"/></symbol>
  <symbol id="i-swap" viewBox="0 0 24 24"><path d="M7 7h11l-3-3 1.4-1.4L22 8l-5.6 5.4L15 12l3-3H7zm10 10H6l3 3-1.4 1.4L2 16l5.6-5.4L9 12l-3 3h11z"/></symbol>
  <symbol id="i-cpu" viewBox="0 0 24 24"><path fill-rule="evenodd" d="M9 2h2v3h2V2h2v3h1a2 2 0 0 1 2 2v1h3v2h-3v2h3v2h-3v1a2 2 0 0 1-2 2h-1v3h-2v-3h-2v3H9v-3H8a2 2 0 0 1-2-2v-1H3v-2h3v-2H3V8h3V7a2 2 0 0 1 2-2h1zM8 7v10h8V7z"/></symbol>
  <symbol id="i-watch" viewBox="0 0 24 24"><path d="M8 2h8l1 4H7zm-1 16h10l-1 4H8zM6 8h12a2 2 0 0 1 2 2v4a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2v-4a2 2 0 0 1 2-2z"/></symbol>
  <symbol id="i-globe" viewBox="0 0 24 24"><path d="M12 2a10 10 0 1 0 0 20 10 10 0 0 0 0-20zm6.9 9h-3a15 15 0 0 0-1.2-5.2A8 8 0 0 1 18.9 11zM12 4c.9 1.2 1.7 3.3 1.9 7h-3.8C10.3 7.3 11.1 5.2 12 4zM4.1 13h3a15 15 0 0 0 1.2 5.2A8 8 0 0 1 4.1 13zm3-2h-3a8 8 0 0 1 4.2-5.2A15 15 0 0 0 7.1 11zm4.9 9c-.9-1.2-1.7-3.3-1.9-7h3.8c-.2 3.7-1 5.8-1.9 7zm3.7-1.8A15 15 0 0 0 16.9 13h3a8 8 0 0 1-4.2 5.2z"/></symbol>
</svg>"""


_ASSET_HASH: dict[str, str] = {}


def asset(url: str) -> str:
    """`/assets/x.svg` -> `/assets/x.svg?v=<content hash>`.

    Static files are served `immutable` for a year, so a changed file (a recoloured logo, a new screenshot) is only
    picked up by browsers that already saw the old one if its URL changes too.
    """
    if url not in _ASSET_HASH:
        f = WEB / url.lstrip("/")
        _ASSET_HASH[url] = hashlib.sha1(f.read_bytes()).hexdigest()[:8] if f.exists() else ""
    return f"{url}?v={_ASSET_HASH[url]}" if _ASSET_HASH[url] else url


def icon(name: str, extra: str = "") -> str:
    cls = f"icon {extra}".strip()
    return f'<svg class="{cls}" aria-hidden="true"><use href="#i-{name}"/></svg>'


# ---------------------------------------------------------------------------
# fact helpers
# ---------------------------------------------------------------------------
def gib(n: int) -> str:
    return f"{n / 2**30:.2f} GiB"


def ram_label(n: int) -> str:
    return f"{round(n / 2**30)} GB"


def license_label(spdx: str) -> str:
    return "Health AI Developer Foundations terms" if spdx.startswith("LicenseRef-HealthAI") else spdx


def catalog_models() -> list[dict]:
    return CATALOG["models"]


def medgemma() -> dict:
    return next(m for m in catalog_models() if m["id"].startswith("medgemma"))


# ---------------------------------------------------------------------------
# components
# ---------------------------------------------------------------------------
def phone(slug: str, alt: str, lazy: bool = True, eager: bool = False) -> str:
    load = 'fetchpriority="high" decoding="async"' if eager else ('loading="lazy" decoding="async"' if lazy else 'decoding="async"')
    return (f'<div class="phone-frame"><div class="phone-screen">'
            f'<img src="{asset(f"/assets/screens/{slug}.webp")}" alt="{html.escape(alt, quote=True)}" width="588" height="1280" {load}>'
            f'</div></div>')


def phones(items: list[tuple[str, str]], cls: str = "", eager_index: int | None = None) -> str:
    inner = "".join(phone(s, a, eager=(i == eager_index)) for i, (s, a) in enumerate(items))
    klass = f"phones {cls}".strip()
    return f'<div class="{klass}">{inner}</div>'


def store_buttons(where: str = "hero") -> str:
    out = []
    ios, android = STORES["ios"], STORES["android"]
    if ios["live"]:
        out.append(f'<a class="badge" href="{ios["url"]}"><img src="/assets/badges/app-store-black.svg" alt="Download on the App Store" width="120" height="40"></a>')
    else:
        out.append(f'<span class="soon">{icon("apple")}<span>Coming soon to the App Store</span></span>')
    if android["live"]:
        out.append(f'<a class="badge play" href="{android["url"]}"><img src="/assets/badges/google-play.png" alt="Get it on Google Play" width="646" height="250"></a>')
    else:
        out.append(f'<span class="soon">{icon("play")}<span>Coming soon to Google Play</span></span>')
    return f'<div class="cta-group">{"".join(out)}</div>'


def privacy_band() -> str:
    items = [
        ("lock", "Stays on your device", "No Ayuvo account, no Ayuvo server."),
        ("cpu", "You choose the AI", "Bring your own key, or run a model on the phone."),
        ("shield", "No ads, no analytics", "Nothing is tracked, sold or profiled."),
        ("github", "Open source", "MIT-licensed. Read every line."),
    ]
    cells = "".join(f'<div class="pband-item">{icon(i)}<div>{t}<small>{s}</small></div></div>' for i, t, s in items)
    return f'<div class="pband" aria-label="Privacy at a glance"><div class="container">{cells}</div></div>'


def eyebrow(text: str, num: str | None = None) -> str:
    n = f'<span class="num">{num}</span> ' if num else ""
    return f'<span class="eyebrow">{n}{text}</span>'


def section_head(eyebrow_text: str, h2: str, lede: str = "", num: str | None = None, center: bool = False) -> str:
    c = " center" if center else ""
    p = f"<p>{lede}</p>" if lede else ""
    return f'<header class="section-head{c}">{eyebrow(eyebrow_text, num)}<h2>{h2}</h2>{p}</header>'


MEDGEMMA_DISCLAIMER = (
    '<div class="disclaimer"><p><strong>Not a medical device.</strong> MedGemma is a research model released under '
    "Google's Health AI Developer Foundations terms. Its output is preliminary, can be inaccurate and needs independent "
    "verification: it is not intended to inform clinical diagnosis, patient management decisions or treatment "
    "recommendations. Coach keeps its own rules whichever model answers. It describes, never diagnoses, never suggests "
    "starting, stopping or changing a dose, and defers to your prescriber.</p>"
    "<p>MedGemma and Gemma are trademarks of Google. Ayuvo is an independent app and is not affiliated with or endorsed "
    "by Google.</p></div>"
)


def catalog_card(spotlight: bool = True) -> str:
    rows = []
    for m in catalog_models():
        spot = " spot" if m["id"].startswith("medgemma") and spotlight else ""
        caps = "text + images" if "image" in m["capabilities"] else "text"
        meta = f'{caps} · {m["contextTokens"]:,}-token context · {license_label(m["license"]["spdx"])}'
        rows.append(
            f'<div class="catalog-row{spot}"><strong>{m["displayName"]}</strong>'
            f'<span>{gib(m["artifact"]["sizeBytes"])} · {ram_label(m["memoryPolicy"]["minimumPhysicalMemoryBytes"])} RAM</span>'
            f'<span class="meta">{meta}</span></div>'
        )
    head = '<div class="catalog-head"><span>On-device models</span><span>Size · memory</span></div>'
    return f'<div class="catalog" role="group" aria-label="On-device model catalogue">{head}{"".join(rows)}</div>'


def catalog_table() -> str:
    body = []
    for m in catalog_models():
        hl = ' class="hl"' if m["id"].startswith("medgemma") else ""
        caps = "Text + images" if "image" in m["capabilities"] else "Text"
        gated = ' <span class="tag">gated</span>' if m["artifact"]["access"]["gated"] else ""
        body.append(
            f'<tr{hl}><td><strong>{m["displayName"]}</strong>{gated}</td><td>{caps}</td>'
            f'<td class="num">{gib(m["artifact"]["sizeBytes"])}</td>'
            f'<td class="num">{ram_label(m["memoryPolicy"]["minimumPhysicalMemoryBytes"])}</td>'
            f'<td class="num">{m["contextTokens"]:,}</td><td>{license_label(m["license"]["spdx"])}</td></tr>'
        )
    return (
        '<div class="table-wrap"><table class="data-table"><thead><tr><th>Model</th><th>Reads</th><th>Download</th>'
        "<th>Memory</th><th>Context</th><th>Licence</th></tr></thead><tbody>" + "".join(body) + "</tbody></table></div>"
    )


def faq_section(faqs: list[tuple[str, str]], num: str | None = None, heading: str = "Ayuvo, <em>plainly answered</em>.") -> str:
    items = "".join(f'<article class="faq-item"><h3>{q}</h3><p>{a}</p></article>' for q, a in faqs)
    return (f'<section class="paper" id="faq"><div class="container">{section_head("FAQ", heading, num=num)}'
            f'<div class="faq-grid">{items}</div></div></section>')


def cta_section(h2: str = "Start with <em>the whole picture</em>.", lede: str | None = None) -> str:
    lede = lede or "Free, private, on your device. Bring your own AI key. No account, no subscription, no ads."
    return (f'<section class="cta-section ink"><div class="container"><div class="cta-content">{eyebrow("Get started")}'
            f"<h2>{h2}</h2><p>{lede}</p>{store_buttons('cta')}"
            f'<p class="cta-note">Or read the code: <a class="link-arrow" href="/open-source">Ayuvo is open source</a></p></div></div></section>')


# ---------------------------------------------------------------------------
# navigation / footer
# ---------------------------------------------------------------------------
FEATURE_LINKS = [
    ("/features/nutrition", "Nutrition", "Photo, barcode, voice"),
    ("/features/workouts", "Workouts", "Sets, reps, 1,300+ exercises"),
    ("/features/health-data", "Health data", "Apple Health and Health Connect"),
    ("/features/records-and-medications", "Records & medications", "Documents and reminders"),
    ("/features/coach", "AI coach", "Ask about your own data"),
    ("/features/fasting-and-water", "Fasting & water", "Optional timers and goals"),
    ("/features/switch-phones", "Switch phones", "iPhone ⇄ Android"),
    ("/features/on-device-ai", "On-device AI", "Run models on the phone"),
]


def nav(current: str) -> str:
    MARK = asset("/assets/brand/ayuvo-mark.svg")
    def cur(p: str) -> str:
        return ' aria-current="page"' if current == p else ""

    menu = "".join(f'<a href="{u}"{cur(u)}>{t}<small>{s}</small></a>' for u, t, s in FEATURE_LINKS)
    return (
        '<nav class="nav" id="nav" aria-label="Main"><div class="container">'
        f'<a href="/" class="nav-brand"><img src="{MARK}" alt="" width="30" height="30"><span>Ayuvo</span></a>'
        '<button class="nav-toggle" id="nav-toggle" type="button" aria-expanded="false" aria-controls="nav-links" aria-label="Menu">'
        "<span></span><span></span><span></span></button>"
        '<div class="nav-links" id="nav-links">'
        f'<div class="has-menu"><a href="/features"{cur("/features")}>Features</a><div class="menu">{menu}</div></div>'
        f'<a href="/privacy-first"{cur("/privacy-first")}>Privacy</a>'
        f'<a href="/features/on-device-ai"{cur("/features/on-device-ai")}>On-device AI</a>'
        f'<a href="/open-source"{cur("/open-source")}>Open source</a>'
        f'<a href="/support"{cur("/support")}>Support</a>'
        f'<a class="nav-cta" href="/download"{cur("/download")}>Download</a>'
        "</div></div></nav>"
    )


def footer() -> str:
    MARK = asset("/assets/brand/ayuvo-mark.svg")
    feat = "".join(f'<li><a href="{u}">{t}</a></li>' for u, t, _ in FEATURE_LINKS[:6])
    return f"""<footer class="footer"><div class="container">
<div class="footer-grid">
<div class="footer-brand"><a href="/" class="footer-brand-mark"><img src="{MARK}" alt="" width="30" height="30"><span>Ayuvo</span></a>
<p>Your whole health in one private app for iPhone and Android. Open source under the MIT licence.</p></div>
<div class="footer-col"><h4>Features</h4><ul>{feat}</ul></div>
<div class="footer-col"><h4>Privacy</h4><ul><li><a href="/privacy-first">Private by design</a></li><li><a href="/features/on-device-ai">On-device AI</a></li><li><a href="/ai-providers">AI providers</a></li><li><a href="/features/switch-phones">Switch phones</a></li><li><a href="/privacy">Privacy Policy</a></li></ul></div>
<div class="footer-col"><h4>Open source</h4><ul><li><a href="{GITHUB}" rel="noopener">GitHub</a></li><li><a href="/open-source">Contribute</a></li><li><a href="{GITHUB}/blob/main/LICENSE" rel="noopener">MIT licence</a></li><li><a href="{GITHUB}/blob/main/SECURITY.md" rel="noopener">Security policy</a></li></ul></div>
<div class="footer-col"><h4>Help</h4><ul><li><a href="/download">Download</a></li><li><a href="/support">Support</a></li><li><a href="/terms">Terms of Service</a></li><li><a href="/sitemap.xml">Sitemap</a></li></ul></div>
</div>
<p class="footer-legal">Apple, the Apple logo, iPhone and Apple Watch are trademarks of Apple Inc., registered in the U.S. and other countries. App Store is a service mark of Apple Inc. Google Play, Android and Health Connect are trademarks of Google LLC. Gemma and MedGemma are trademarks of Google. Ayuvo is an independent app and is not affiliated with or endorsed by Apple or Google. Ayuvo is not a medical device and does not give medical advice.</p>
<div class="footer-bottom"><span>© 2026 Yaara Tech · MIT licensed</span><span>Set in Fraunces &amp; Manrope · No cookies, no trackers</span></div>
</div></footer>"""


# ---------------------------------------------------------------------------
# pages
# ---------------------------------------------------------------------------
@dataclass
class Page:
    path: str
    title: str
    description: str
    body: str = ""
    crumbs: list[tuple[str, str]] = field(default_factory=list)
    modified: str = "2026-09-25"
    og_headline: str = ""
    og_screens: list[str] = field(default_factory=list)
    og_alt: str = ""
    lcp: str | None = None
    kind: str = "page"          # home | page | collection | source | legal
    legal: bool = False
    ld: list[dict] = field(default_factory=list)
    faqs: list[tuple[str, str]] = field(default_factory=list)
    in_sitemap: bool = True
    robots: str = "index,follow,max-image-preview:large,max-snippet:-1,max-video-preview:-1"
    crumb: str = ""

    @property
    def slug(self) -> str:
        return "home" if self.path == "/" else self.path.strip("/").replace("/", "-")

    @property
    def url(self) -> str:
        return BASE + ("/" if self.path == "/" else self.path)

    @property
    def out_file(self) -> Path:
        if self.path == "/":
            return WEB / "index.html"
        if self.path == "/features":
            return WEB / "features" / "index.html"
        return WEB / (self.path.strip("/") + ".html")

    @property
    def og_image(self) -> str:
        return f"/assets/og/{self.slug}.jpg" if (WEB / "assets" / "og" / f"{self.slug}.jpg").exists() else "/assets/opengraph.jpg"


def asset_hash(name: str) -> str:
    return hashlib.sha1((WEB / name).read_bytes()).hexdigest()[:8]


def jsonld(obj: dict) -> str:
    return '<script type="application/ld+json">\n' + json.dumps(obj, indent=2, ensure_ascii=False) + "\n</script>"


def organization_node() -> dict:
    return {
        "@type": "Organization",
        "@id": f"{BASE}/#organization",
        "name": "Yaara Tech",
        "url": BASE + "/",
        "logo": {"@type": "ImageObject", "url": f"{BASE}/assets/brand/logo-512.png", "width": 512, "height": 512},
        "sameAs": [GITHUB],
        "contactPoint": {"@type": "ContactPoint", "contactType": "customer support", "email": EMAIL, "url": f"{BASE}/support"},
    }


def website_node() -> dict:
    return {
        "@type": "WebSite",
        "@id": f"{BASE}/#website",
        "name": SITE_NAME,
        "url": BASE + "/",
        "inLanguage": "en",
        "publisher": {"@id": f"{BASE}/#organization"},
    }


def software_node(screens: list[str]) -> dict:
    node = {
        "@type": ["SoftwareApplication", "MobileApplication"],
        "@id": f"{BASE}/#software",
        "name": "Ayuvo",
        "alternateName": ["Ayuvo health app", "Ayuvo AI health companion"],
        "operatingSystem": "iOS 17.6+, Android 8.0+",
        "applicationCategory": "HealthApplication",
        "applicationSubCategory": "Nutrition, workouts, health records and medications",
        "offers": {"@type": "Offer", "price": "0", "priceCurrency": "USD"},
        "isAccessibleForFree": True,
        "description": (
            "Ayuvo is a private, open-source health companion for iPhone and Android: nutrition, workouts, fasting, "
            "water, medications, health records and a local mirror of Apple Health or Health Connect, with an AI coach "
            "that runs on the API key you bring or on a model that stays on your phone. No account, no ads, no analytics, no Ayuvo servers."
        ),
        "featureList": [
            "Photo, barcode, voice, text and manual meal logging with 30+ nutrients",
            "Workout diary with sets, reps, weight, RPE and a 1,300+ exercise library",
            "Local health data hub mirroring Apple Health and Health Connect with charts and history",
            "Health records: scan or import documents, read on-device, extract values and highlights",
            "Medication schedules with dose reminders",
            "AI coach on your own key (15 providers) or on-device models including MedGemma 1.5 4B",
            "Export All Data and import it on iPhone or Android to switch phones",
            "Optional fasting timer and water tracking",
            "Apple Watch app, widgets, Siri Shortcuts and iOS Share Extension",
            "18 languages, no account, no ads, no analytics",
        ],
        "publisher": {"@id": f"{BASE}/#organization"},
        "license": "https://opensource.org/licenses/MIT",
        "sameAs": [GITHUB],
        "url": BASE + "/",
        "image": f"{BASE}/assets/opengraph.jpg",
        "screenshot": [f"{BASE}/assets/screens/{s}.webp" for s in screens],
    }
    installs = [s["url"] for s in STORES.values() if s["live"]]
    if installs:
        node["installUrl"] = installs if len(installs) > 1 else installs[0]
    return node


def render(page: Page, css_v: str, js_v: str) -> str:
    esc = lambda s: html.escape(s, quote=True)  # noqa: E731
    url = page.url
    home = page.path == "/"
    og = page.og_image
    og_url = BASE + asset(og)
    og_alt = page.og_alt or page.title
    head = [
        '<meta charset="UTF-8">',
        '<meta name="viewport" content="width=device-width, initial-scale=1">',
        f"<title>{esc(page.title)}</title>",
        f'<meta name="description" content="{esc(page.description)}">',
        f'<meta name="robots" content="{page.robots}">',
        f'<link rel="canonical" href="{url}">',
        f'<link rel="alternate" href="{url}" hreflang="en">',
        f'<link rel="alternate" href="{url}" hreflang="x-default">',
        '<meta name="theme-color" content="#EEF6FD">',
        '<meta name="color-scheme" content="light">',
    ]
    if STORES["ios"]["live"]:
        head.append(f'<meta name="apple-itunes-app" content="app-id={STORES["ios"]["app_id"]}">')
    if home:
        head.append('<meta name="google-site-verification" content="Bw5_BS8q9GGMSX_JCouSwaPHmNi-zUr1rnStY9cnjMA" />')
    head += [
        f'<meta property="og:type" content="{"website" if home or page.kind != "page" else "article"}">',
        '<meta property="og:site_name" content="Ayuvo">',
        '<meta property="og:locale" content="en_US">',
        f'<meta property="og:url" content="{url}">',
        f'<meta property="og:title" content="{esc(page.title)}">',
        f'<meta property="og:description" content="{esc(page.description)}">',
        f'<meta property="og:image" content="{og_url}">',
        f'<meta property="og:image:secure_url" content="{og_url}">',
        '<meta property="og:image:type" content="image/jpeg">',
        '<meta property="og:image:width" content="1200">',
        '<meta property="og:image:height" content="630">',
        f'<meta property="og:image:alt" content="{esc(og_alt)}">',
        '<meta name="twitter:card" content="summary_large_image">',
        f'<meta name="twitter:title" content="{esc(page.title)}">',
        f'<meta name="twitter:description" content="{esc(page.description)}">',
        f'<meta name="twitter:image" content="{og_url}">',
        f'<meta name="twitter:image:alt" content="{esc(og_alt)}">',
        f'<link rel="icon" href="{asset("/assets/brand/favicon.svg")}" type="image/svg+xml">',
        f'<link rel="icon" href="{asset("/assets/brand/favicon.ico")}" sizes="16x16 32x32 48x48">',
        f'<link rel="apple-touch-icon" href="{asset("/assets/brand/apple-touch-icon.png")}">',
        '<link rel="manifest" href="/manifest.webmanifest">',
        '<link rel="preload" as="font" type="font/woff2" href="/assets/fonts/manrope-latin.woff2" crossorigin>',
        '<link rel="preload" as="font" type="font/woff2" href="/assets/fonts/fraunces-latin.woff2" crossorigin>',
    ]
    if page.lcp:
        head.append(f'<link rel="preload" as="image" type="image/webp" href="{asset(page.lcp)}" fetchpriority="high">')
    head += [f'<link rel="stylesheet" href="/styles.css?v={css_v}">', f'<script defer src="/site.js?v={js_v}"></script>']

    # structured data
    blocks: list[dict] = []
    if home:
        blocks.append({"@context": "https://schema.org", "@graph": [organization_node(), website_node()]})
        blocks.append({"@context": "https://schema.org", **software_node(["summary", "records", "coach", "medications"])})
    else:
        crumbs = [("Home", "/")] + page.crumbs + [(page.crumb or page.title.split(" — ")[0].split(" | ")[0], page.path)]
        blocks.append({
            "@context": "https://schema.org",
            "@type": "BreadcrumbList",
            "itemListElement": [
                {"@type": "ListItem", "position": i, "name": n, "item": BASE + ("/" if p == "/" else p)}
                for i, (n, p) in enumerate(crumbs, 1)
            ],
        })
        wp_type = {"collection": "CollectionPage", "legal": "WebPage", "source": "WebPage"}.get(page.kind, "WebPage")
        wp = {
            "@context": "https://schema.org",
            "@type": wp_type,
            "@id": f"{url}#webpage",
            "url": url,
            "name": page.title,
            "description": page.description,
            "inLanguage": "en",
            "dateModified": page.modified,
            "isPartOf": {"@id": f"{BASE}/#website"},
            "primaryImageOfPage": {"@type": "ImageObject", "url": og_url, "width": 1200, "height": 630},
        }
        if not page.legal:
            wp["about"] = {"@id": f"{BASE}/#software"}
        blocks.append(wp)
    blocks += [{"@context": "https://schema.org", **b} if "@context" not in b else b for b in page.ld]
    if page.faqs:
        blocks.append({
            "@context": "https://schema.org",
            "@type": "FAQPage",
            "mainEntity": [
                {"@type": "Question", "name": html.unescape(_strip_tags(q)),
                 "acceptedAnswer": {"@type": "Answer", "text": html.unescape(_strip_tags(a))}}
                for q, a in page.faqs
            ],
        })
    head += [jsonld(b) for b in blocks]

    if page.legal:
        main = (f'<main id="main" class="page"><div class="container"><div class="page-content">{page.body}'
                "</div></div></main>")
    else:
        main = f'<main id="main">{page.body}</main>'
    return (
        '<!DOCTYPE html>\n<html lang="en">\n<head>\n' + "\n".join(head) + "\n</head>\n<body>\n"
        '<a class="skip" href="#main">Skip to content</a>\n' + SPRITE + "\n" + nav(page.path) + "\n" + main + "\n" + footer() + "\n</body>\n</html>\n"
    )


def _strip_tags(s: str) -> str:
    out, depth = [], 0
    for ch in s:
        if ch == "<":
            depth += 1
        elif ch == ">":
            depth = max(0, depth - 1)
        elif depth == 0:
            out.append(ch)
    return " ".join("".join(out).split())
