#!/usr/bin/env python3
"""Static validation for the Ayuvo website (web/) — stdlib + Pillow only.

Checks
  * required files exist; firebase.json parses with public == "."
  * every relative href/src/srcset/url() resolves to a file (clean-URL aware)
  * page resources (stylesheets, scripts, images, fonts, icons) are same-origin —
    the site must make zero third-party requests; anchors may link out over https
  * no Google Fonts / gstatic / cdnjs / unpkg references anywhere
  * no inline style="" attributes, no <style> blocks, no inline <script> except ld+json
    (CSP: style-src 'self'; script-src 'self')
  * every application/ld+json block parses; FAQPage questions == visible FAQ headings
  * sitemap <loc> entries resolve; <img> tags carry alt/width/height
  * image dimensions match marketing/MANIFEST.json and the screenshot storyboard
  * --fail-on-placeholders: any LEGAL_/SUPPORT_EMAIL/FIREBASE_/AYUVO_/APP_STORE_URL/... token fails
  * --store-docs: provider list and Health read/write lists agree across APPSTORE.md,
    PLAYSTORE.md and privacy.html

Exit code 1 on any problem.
"""

from __future__ import annotations

import argparse
import json
import re
import sys
from html.parser import HTMLParser
from pathlib import Path
from urllib.parse import urlsplit

from PIL import Image

ROOT = Path(__file__).resolve().parents[1]
WEB = ROOT / "web"

REQUIRED = [
    "index.html", "privacy.html", "terms.html", "support.html", "404.html",
    "robots.txt", "sitemap.xml", "styles.css", "site.js", "firebase.json", ".firebaserc",
    "assets/opengraph.jpg", "assets/brand/ayuvo-mark.svg", "assets/brand/favicon.svg",
    "assets/brand/favicon.ico", "assets/brand/apple-touch-icon.png",
]
PAGES = ["index.html", "privacy.html", "terms.html", "support.html", "404.html"]
FORBIDDEN_HOSTS = ["fonts.googleapis.com", "fonts.gstatic.com", "cdnjs.cloudflare.com", "unpkg.com",
                   "cdn.jsdelivr.net", "googletagmanager.com", "google-analytics.com"]
PLACEHOLDER = re.compile(r"\b(LEGAL_ENTITY_NAME|LEGAL_ADDRESS|SUPPORT_EMAIL|GOVERNING_LAW|EFFECTIVE_DATE|"
                         r"APP_STORE_URL|PLAY_STORE_URL|AYUVO_APPSTORE_ID|FIREBASE_PROJECT_ID)\b")
RESOURCE_ATTRS = {("link", "href"), ("script", "src"), ("img", "src"), ("img", "srcset"), ("source", "src"),
                  ("source", "srcset"), ("video", "src"), ("audio", "src"), ("iframe", "src"), ("use", "href")}

PROVIDERS = ["Google Gemini", "OpenAI", "Anthropic", "xAI", "OpenRouter", "Together AI", "Groq", "Hugging Face",
             "Fireworks AI", "DeepInfra", "Mistral", "DeepSeek", "Cerebras", "Ollama"]
HEALTH_READ = ["activity", "body measurements", "heart", "sleep", "vitals", "mobility", "hearing",
               "cycle tracking", "mindfulness", "symptoms", "nutrition", "hydration"]
HEALTH_WRITE = ["nutrition", "weight", "height", "body fat", "active calories"]


class Page(HTMLParser):
    def __init__(self):
        super().__init__()
        self.links: list[tuple[str, str, str]] = []   # (tag, attr, value)
        self.inline_styles = 0
        self.style_blocks = 0
        self.inline_scripts = 0
        self.ldjson: list[str] = []
        self.imgs: list[dict] = []
        self.faq_h3: list[str] = []
        self._in_script = None
        self._in_faq_h3 = False
        self._stack: list[str] = []
        self._buf = ""

    def handle_starttag(self, tag, attrs):
        a = dict(attrs)
        self._stack.append(a.get("class", ""))
        if "style" in a:
            self.inline_styles += 1
        if tag == "style":
            self.style_blocks += 1
        if tag == "script":
            t = a.get("type", "")
            if "src" in a:
                self.links.append((tag, "src", a["src"]))
            elif t == "application/ld+json":
                self._in_script = "ld"
                self._buf = ""
            else:
                self.inline_scripts += 1
        for attr in ("href", "src", "srcset"):
            if attr in a and tag != "script":
                self.links.append((tag, attr, a[attr]))
        if tag == "img":
            self.imgs.append(a)
        if tag == "h3" and any("faq-item" in c for c in self._stack[-2:-1]):
            self._in_faq_h3 = True
            self._buf = ""

    def handle_endtag(self, tag):
        if tag == "script" and self._in_script == "ld":
            self.ldjson.append(self._buf)
            self._in_script = None
        if tag == "h3" and self._in_faq_h3:
            self.faq_h3.append(re.sub(r"\s+", " ", self._buf).strip())
            self._in_faq_h3 = False
        if self._stack:
            self._stack.pop()

    def handle_data(self, data):
        if self._in_script == "ld" or self._in_faq_h3:
            self._buf += data

    def handle_entityref(self, name):
        if self._in_faq_h3:
            self._buf += {"amp": "&", "lt": "<", "gt": ">", "quot": '"'}.get(name, "")


def resolve(target: str, base: Path) -> Path | None:
    """Map a relative URL to a file under web/, honouring Firebase clean URLs."""
    target = target.split("#", 1)[0].split("?", 1)[0]
    if not target:
        return base
    if target.startswith("/"):
        candidate = WEB / target.lstrip("/")
    else:
        candidate = (base.parent / target).resolve()
    if candidate.is_dir():
        candidate = candidate / "index.html"
    if candidate.exists():
        return candidate
    html = candidate.with_suffix(".html") if candidate.suffix == "" else None
    if html and html.exists():
        return html
    return None


def check_pages(problems: list[str]) -> dict[str, Page]:
    parsed = {}
    for name in PAGES:
        path = WEB / name
        text = path.read_text(encoding="utf-8")
        page = Page()
        page.feed(text)
        parsed[name] = page
        for host in FORBIDDEN_HOSTS:
            if host in text:
                problems.append(f"{name}: references forbidden host {host}")
        if page.inline_styles:
            problems.append(f"{name}: {page.inline_styles} inline style attributes (CSP style-src 'self')")
        if page.style_blocks:
            problems.append(f"{name}: {page.style_blocks} <style> blocks")
        if page.inline_scripts:
            problems.append(f"{name}: {page.inline_scripts} inline <script> blocks (only ld+json allowed)")
        for tag, attr, value in page.links:
            for v in ([value] if attr != "srcset" else [s.strip().split(" ")[0] for s in value.split(",")]):
                if not v or v.startswith("#") or v.startswith("mailto:") or v.startswith("tel:") or v.startswith("data:"):
                    continue
                if PLACEHOLDER.fullmatch(v):
                    continue
                parts = urlsplit(v)
                if parts.scheme in ("http", "https"):
                    if (tag, attr) in RESOURCE_ATTRS or tag in ("link", "script"):
                        if parts.netloc != "ayuvo-health.web.app":
                            problems.append(f"{name}: external resource {v}")
                    elif parts.scheme != "https":
                        problems.append(f"{name}: non-https link {v}")
                    continue
                if resolve(v, path) is None:
                    problems.append(f"{name}: broken link {v}")
        for img in page.imgs:
            if "alt" not in img:
                problems.append(f"{name}: <img src={img.get('src')}> missing alt")
            if "width" not in img or "height" not in img:
                problems.append(f"{name}: <img src={img.get('src')}> missing width/height")
        for block in page.ldjson:
            try:
                json.loads(block)
            except json.JSONDecodeError as exc:
                problems.append(f"{name}: invalid JSON-LD ({exc})")
    # FAQ parity
    index = parsed["index.html"]
    faq_names = []
    for block in index.ldjson:
        data = json.loads(block)
        if data.get("@type") == "FAQPage":
            faq_names = [q["name"] for q in data["mainEntity"]]
    if faq_names != index.faq_h3:
        problems.append(f"index.html: FAQPage JSON-LD questions differ from visible FAQ headings\n  ld: {faq_names}\n  html: {index.faq_h3}")
    return parsed


def check_css(problems: list[str]):
    css = (WEB / "styles.css").read_text(encoding="utf-8")
    for host in FORBIDDEN_HOSTS:
        if host in css:
            problems.append(f"styles.css: references {host}")
    if "@import" in css:
        problems.append("styles.css: @import is not allowed (self-host instead)")
    for m in re.finditer(r"url\((['\"]?)([^'\")]+)\1\)", css):
        target = m.group(2)
        if target.startswith("data:"):
            continue
        if urlsplit(target).scheme:
            problems.append(f"styles.css: external url() {target}")
        elif resolve(target, WEB / "styles.css") is None:
            problems.append(f"styles.css: missing url() target {target}")


def check_sitemap(problems: list[str]):
    text = (WEB / "sitemap.xml").read_text(encoding="utf-8")
    for loc in re.findall(r"<loc>([^<]+)</loc>", text):
        parts = urlsplit(loc)
        if parts.netloc != "ayuvo-health.web.app":
            problems.append(f"sitemap.xml: foreign loc {loc}")
            continue
        if resolve(parts.path or "/", WEB / "index.html") is None:
            problems.append(f"sitemap.xml: loc does not resolve {loc}")
    for loc in re.findall(r"<image:loc>([^<]+)</image:loc>", text):
        if resolve(urlsplit(loc).path, WEB / "index.html") is None:
            problems.append(f"sitemap.xml: image loc does not resolve {loc}")


def check_firebase(problems: list[str]):
    try:
        cfg = json.loads((WEB / "firebase.json").read_text())
    except json.JSONDecodeError as exc:
        problems.append(f"firebase.json: {exc}")
        return
    hosting = cfg.get("hosting", {})
    if hosting.get("public") != ".":
        problems.append("firebase.json: hosting.public must be '.'")
    if hosting.get("cleanUrls") is not True:
        problems.append("firebase.json: cleanUrls must be true (apps link to /privacy, /terms, /support)")
    csp = ""
    for h in hosting.get("headers", []):
        for kv in h.get("headers", []):
            if kv["key"].lower() == "content-security-policy":
                csp = kv["value"]
    if "script-src 'self'" not in csp or "style-src 'self'" not in csp:
        problems.append("firebase.json: CSP must pin script-src and style-src to 'self'")
    try:
        rc = json.loads((WEB / ".firebaserc").read_text())
        rc["projects"]["default"]
    except Exception as exc:  # noqa: BLE001
        problems.append(f".firebaserc: {exc}")


def check_images(problems: list[str]):
    manifest = ROOT / "marketing/MANIFEST.json"
    if manifest.exists():
        for rel, (w, h, mode) in json.loads(manifest.read_text()).items():
            if not rel.startswith("web/"):
                continue
            p = ROOT / rel
            if not p.exists():
                problems.append(f"{rel}: missing (listed in MANIFEST.json)")
                continue
            with Image.open(p) as im:
                if (im.width, im.height) != (w, h):
                    problems.append(f"{rel}: {im.size} != {(w, h)}")
    storyboard = ROOT / "marketing/storyboard.json"
    if storyboard.exists():
        sb = json.loads(storyboard.read_text())
        size = tuple(sb["web"]["size"])
        for screen in sb["screens"]:
            p = ROOT / sb["web"]["dir"] / f"{screen['id']}.png"
            if not p.exists():
                problems.append(f"screenshot missing: {p.relative_to(ROOT)}")
                continue
            with Image.open(p) as im:
                if im.size != size:
                    problems.append(f"{p.relative_to(ROOT)}: {im.size} != {size}")


def check_placeholders(problems: list[str]):
    hits = []
    for p in sorted(WEB.rglob("*")):
        if p.is_file() and p.suffix in (".html", ".xml", ".txt", ".json", ".js", ".css") and ".firebase" not in p.parts:
            for i, line in enumerate(p.read_text(encoding="utf-8", errors="ignore").splitlines(), 1):
                for m in PLACEHOLDER.finditer(line):
                    hits.append(f"{p.relative_to(ROOT)}:{i}: {m.group(1)}")
    for h in hits:
        problems.append(f"placeholder: {h}")
    return hits


def check_store_docs(problems: list[str]):
    docs = {
        "APPSTORE.md": (ROOT / "APPSTORE.md").read_text(encoding="utf-8"),
        "PLAYSTORE.md": (ROOT / "PLAYSTORE.md").read_text(encoding="utf-8"),
        "web/privacy.html": (WEB / "privacy.html").read_text(encoding="utf-8"),
    }
    for name, text in docs.items():
        low = text.lower()
        for provider in PROVIDERS:
            if provider.lower() not in low:
                problems.append(f"{name}: provider '{provider}' missing")
        for t in HEALTH_READ:
            if t not in low:
                problems.append(f"{name}: Health read type '{t}' missing")
        for t in HEALTH_WRITE:
            if t not in low:
                problems.append(f"{name}: Health write type '{t}' missing")


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--fail-on-placeholders", action="store_true")
    ap.add_argument("--store-docs", action="store_true")
    args = ap.parse_args(argv)

    problems: list[str] = []
    for rel in REQUIRED:
        if not (WEB / rel).exists():
            problems.append(f"missing required file web/{rel}")
    if problems:
        for p in problems:
            print(p)
        return 1
    check_firebase(problems)
    check_pages(problems)
    check_css(problems)
    check_sitemap(problems)
    check_images(problems)
    hits = []
    if args.fail_on_placeholders:
        hits = check_placeholders(problems)
    else:
        hits = check_placeholders([])
    if args.store_docs:
        check_store_docs(problems)

    for p in problems:
        print(p)
    if not args.fail_on_placeholders and hits:
        print(f"note: {len(hits)} placeholder tokens remain (run with --fail-on-placeholders before release)")
    print(f"web_check: {'FAIL' if problems else 'OK'} ({len(problems)} problems)")
    return 1 if problems else 0


if __name__ == "__main__":
    raise SystemExit(main())
