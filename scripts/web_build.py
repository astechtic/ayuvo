#!/usr/bin/env python3
"""Build the Ayuvo website (web/) from scripts/web/pages.py, and lint the copy.

    python3 scripts/web_build.py            # render every page, sitemap, llms.txt, manifest
    python3 scripts/web_build.py --check    # lint only (copy rules, titles, headings); writes nothing

The generated HTML is committed, so `cd web && firebase deploy --only hosting` needs no build step.
Legal bodies (privacy, terms, support) live in web/_src/pages/*.html and are only re-wrapped here.
"""
from __future__ import annotations

import html
import json
import re
import shutil
import sys
from html.parser import HTMLParser
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "scripts" / "web"))

import site_lib  # noqa: E402
from site_lib import BASE, EMAIL, GITHUB, WEB, asset_hash, render  # noqa: E402
import pages as pages_mod  # noqa: E402

# --------------------------------------------------------------------------- lint

BANNED = [
    (r"never leaves? (the|your) (device|phone)", 'claims data "never leaves the device"'),
    (r"nothing (ever )?leaves (the|your) (device|phone)", 'claims "nothing leaves the device"'),
    (r"100\s?% (offline|private|secure)", "absolute privacy claim"),
    (r"military[- ]grade|bank[- ]grade", "unverifiable security claim"),
    (r"\bHIPAA\b", "compliance claim"),
    (r"clinical[- ]grade|medical[- ]grade|\bFDA\b|\bCE[- ]marked", "regulatory claim"),
    (r"(?<!never )(?<!not )\bdiagnos(e|es|ing|is|tic)\b", "diagnosis wording outside a disclaimer"),
    (r"\b(best|top|leading|most accurate)\b[^.<]{0,40}\b(medical|health) (model|AI)\b", "superlative about a medical model"),
    (r"\bicloud (backup|sync)\b", "iCloud backup was removed"),
    (r"\d[\d,.]*\s?[kKmM]\+?\s+(users|downloads|installs)", "made-up traction figure"),
    (r"★|\baggregateRating\b|\btestimonial", "ratings or testimonials"),
]


class _Text(HTMLParser):
    def __init__(self):
        super().__init__()
        self.parts: list[str] = []
        self.skip = 0
        self.h1 = 0

    def handle_starttag(self, tag, attrs):
        if tag in ("script", "style"):
            self.skip += 1
        if tag == "h1":
            self.h1 += 1

    def handle_endtag(self, tag):
        if tag in ("script", "style"):
            self.skip -= 1

    def handle_data(self, data):
        if not self.skip:
            self.parts.append(data)


def lint(page: site_lib.Page, doc: str) -> list[str]:
    problems = []
    if page.in_sitemap or page.path == "/404":
        if len(page.title) > 62:
            problems.append(f"{page.path}: title is {len(page.title)} chars (max 62)")
    if page.in_sitemap and not page.legal and not 70 <= len(page.description) <= 165:
        problems.append(f"{page.path}: description is {len(page.description)} chars (70-165)")
    t = _Text()
    t.feed(doc)
    if t.h1 != 1:
        problems.append(f"{page.path}: expected one <h1>, found {t.h1}")
    if page.legal or page.path == "/404":
        return problems
    body = re.sub(r'<div class="disclaimer">.*?</div>', "", doc, flags=re.S)
    tt = _Text()
    tt.feed(body)
    text = " ".join(tt.parts)
    for pattern, why in BANNED:
        m = re.search(pattern, text, re.I)
        if m:
            problems.append(f"{page.path}: {why}: ...{text[max(0, m.start() - 30):m.end() + 30]}...")
    main = re.search(r"<main.*?</main>", doc, re.S)
    main_html = main.group(0) if main else ""
    if re.search(r"medgemma", main_html, re.I) and "Not a medical device." not in main_html:
        problems.append(f"{page.path}: names MedGemma without the disclaimer")
    if page.path not in ("/features/records-and-medications",) and "1,329" in text:
        problems.append(f"{page.path}: use 1,300+ exercises")
    return problems


# --------------------------------------------------------------------------- outputs

def sitemap(pages: list[site_lib.Page]) -> str:
    rows = []
    for p in pages:
        if not p.in_sitemap:
            continue
        og = p.og_image
        rows.append(
            "  <url>\n"
            f"    <loc>{p.url}</loc>\n"
            f"    <lastmod>{p.modified}</lastmod>\n"
            "    <image:image>\n"
            f"      <image:loc>{BASE}{og}</image:loc>\n"
            f"      <image:title>{html.escape(p.og_alt or p.title)}</image:title>\n"
            "    </image:image>\n"
            "  </url>"
        )
    return ('<?xml version="1.0" encoding="UTF-8"?>\n'
            '<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9" xmlns:image="http://www.google.com/schemas/sitemap-image/1.1">\n'
            + "\n".join(rows) + "\n</urlset>\n")


def llms_txt(pages: list[site_lib.Page]) -> str:
    lines = [
        "# Ayuvo",
        "",
        "> Ayuvo is a private, open-source health companion for iPhone and Android: nutrition, workouts, health records, medications,",
        "> fasting, water and a local mirror of Apple Health or Health Connect, with an AI coach that runs on your own API key or",
        "> on a model that stays on the phone. No account, no ads, no analytics, no Ayuvo servers. MIT licensed.",
        "",
        "Ayuvo is not a medical device and does not give medical advice. Data is stored on the user's device and leaves it only when",
        "the user acts, to a service they chose.",
        "",
        "## Pages",
        "",
    ]
    for p in pages:
        if p.in_sitemap:
            lines.append(f"- [{p.crumb or p.title}]({p.url}): {p.description}")
    lines += ["", "## Source", "", f"- [GitHub repository]({GITHUB}): MIT-licensed source for the iPhone and Android apps", f"- Support: {EMAIL}", ""]
    return "\n".join(lines)


def manifest() -> str:
    return json.dumps({
        "name": "Ayuvo",
        "short_name": "Ayuvo",
        "description": "Your health. Your device. Your call.",
        "start_url": "/",
        "display": "browser",
        "background_color": "#EEF6FD",
        "theme_color": "#EEF6FD",
        "icons": [
            {"src": "/assets/brand/logo-192.png", "sizes": "192x192", "type": "image/png"},
            {"src": "/assets/brand/logo-512.png", "sizes": "512x512", "type": "image/png"},
        ],
    }, indent=2) + "\n"


ROBOTS = f"""# Ayuvo has no accounts, so there is nothing private to hide from crawlers.
User-agent: *
Allow: /

Sitemap: {BASE}/sitemap.xml
"""


def main(argv: list[str]) -> int:
    check_only = "--check" in argv
    pages = pages_mod.all_pages()
    css_v, js_v = asset_hash("styles.css"), asset_hash("site.js")

    problems: list[str] = []
    seen_titles: dict[str, str] = {}
    seen_desc: dict[str, str] = {}
    rendered = {}
    for p in pages:
        doc = render(p, css_v, js_v)
        rendered[p.path] = doc
        problems += lint(p, doc)
        for seen, val, what in ((seen_titles, p.title, "title"), (seen_desc, p.description, "description")):
            if val in seen:
                problems.append(f"{p.path}: duplicate {what} with {seen[val]}")
            seen[val] = p.path
    for prob in problems:
        print("LINT", prob)
    if check_only:
        print(f"web_build --check: {'FAIL' if problems else 'OK'} ({len(problems)} problems, {len(pages)} pages)")
        return 1 if problems else 0
    if problems:
        return 1

    badges = WEB / "assets" / "badges"
    badges.mkdir(parents=True, exist_ok=True)
    for name, dst in (("appstore-black.svg", "app-store-black.svg"), ("googleplay.png", "google-play.png")):
        src = ROOT / "marketing" / "badges" / name
        if src.exists():
            shutil.copyfile(src, badges / dst)

    for p in pages:
        out = p.out_file
        out.parent.mkdir(parents=True, exist_ok=True)
        out.write_text(rendered[p.path], encoding="utf-8")
    (WEB / "sitemap.xml").write_text(sitemap(pages), encoding="utf-8")
    (WEB / "llms.txt").write_text(llms_txt(pages), encoding="utf-8")
    (WEB / "manifest.webmanifest").write_text(manifest(), encoding="utf-8")
    (WEB / "robots.txt").write_text(ROBOTS, encoding="utf-8")
    print(f"built {len(pages)} pages (css v={css_v}, js v={js_v})")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
