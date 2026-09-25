"""Builders for /compare and /compare/<product> (content in compare_facts.py)."""
from __future__ import annotations

import html
import re

from compare_facts import CHECKED, COMPARE, HUB_ONE_LINERS, LIMITS_COMMON, ORDER
from site_lib import BASE, EMAIL, STORES, Page, cta_section, eyebrow, faq_section, phones, privacy_band, section_head

DISCLAIMER_MARK = "is not affiliated with or endorsed by"


def _refs(text: str) -> str:
    """`[1]` markers become superscript links to the sources list."""
    return re.sub(r"\s?\[(\d)\]", lambda m: f'<sup><a href="#src-{m[1]}" aria-label="Source {m[1]}">{m[1]}</a></sup>', text)


def _band(cls: str, inner: str, sid: str = "") -> str:
    i = f' id="{sid}"' if sid else ""
    return f'<section class="{cls}"{i}><div class="container">{inner}</div></section>'


def _crumbs(trail: list[tuple[str, str]], here: str) -> str:
    items = "".join(f'<li><a href="{u}">{n}</a></li>' for n, u in [("Home", "/")] + trail)
    return f'<ol class="crumbs">{items}<li aria-current="page">{here}</li></ol>'


def disclaimer(name: str) -> str:
    return (
        '<div class="disclaimer"><p><strong>About this comparison.</strong> It is written by the Ayuvo team, so it is not neutral. '
        f"Facts about {name} come from its own public pages, listed above and checked on {CHECKED}. Products change, so check {name} "
        f'for current details, and <a href="mailto:{EMAIL}?subject=Comparison%20correction">email us</a> if something is wrong. '
        f"{name} is a trademark of its owner. Ayuvo {DISCLAIMER_MARK} {name}.</p></div>"
    )


def sources_list(sources: list[tuple[str, str]]) -> str:
    items = "".join(
        f'<li id="src-{i}"><a href="{u}" rel="noopener">{html.escape(t)}</a> <span class="tag plain">checked {CHECKED}</span></li>'
        for i, (t, u) in enumerate(sources, 1)
    )
    return f'<ol class="src-list">{items}</ol>'


def table(name: str, rows: list[tuple[str, str, str]]) -> str:
    body = "".join(
        f'<tr><th scope="row">{label}</th><td data-label="Ayuvo">{ay}</td><td data-label="{name}">{_refs(vendor)}</td></tr>'
        for label, ay, vendor in rows
    )
    return (
        '<div class="table-wrap"><table class="data-table compare-table">'
        f'<caption class="sr-only">Ayuvo compared with {name}</caption>'
        f'<thead><tr><th scope="col">Compared on</th><th scope="col">Ayuvo</th><th scope="col">{name}</th></tr></thead>'
        f"<tbody>{body}</tbody></table></div>"
    )


def _ticks(items: list[str]) -> str:
    return '<ul class="ticks">' + "".join(f"<li><span>{_refs(i)}</span></li>" for i in items) + "</ul>"


def chooser(name: str, f: dict) -> str:
    cols = [(f"Choose {name} if", f["choose_vendor"]), ("Choose Ayuvo if", f["choose_ayuvo"])]
    if f["choose_both"]:
        cols.append(("Use both if", f["choose_both"]))
    cells = "".join(f'<div class="panel"><span class="kicker">{t}</span>{_ticks(items)}</div>' for t, items in cols)
    grid = "grid-3" if len(cols) == 3 else "grid-2"
    return f'<div class="{grid}">{cells}</div>'


def limits_for(f: dict) -> list[str]:
    limits = list(f["limits"]) + LIMITS_COMMON
    if not all(s["live"] for s in STORES.values()):
        limits.append("Ayuvo is not on the App Store or Google Play yet, so it is listed as coming soon. You can build it from source today.")
    return limits


def compare_strip() -> str:
    """A row of links to the comparison pages, for the pages people read before they compare."""
    chips = "".join(
        f'<a class="chip" href="/compare/{s}"><span>{COMPARE[s]["name"]}<small>{HUB_ONE_LINERS[s]}</small></span></a>' for s in ORDER
    )
    return _band(
        "paper alt",
        section_head("Compare", "Weighing up your options?", "Honest side-by-sides with the apps and services people use for records and health data, including where each one is stronger.")
        + f'<div class="works-with">{chips}</div><p class="cta-note"><a class="link-arrow" href="/compare">See all comparisons</a></p>',
    )


def build_compare(slug: str) -> Page:
    f = COMPARE[slug]
    name = f["name"]
    visual = phones(f["screens"], "pair", eager_index=0)
    hero = (
        f'<header class="hero compact"><div class="container">{_crumbs([("Compare", "/compare")], f["crumb"])}'
        f'<div class="hero-split"><div class="hero-text">{eyebrow("Compare")}<h1 class="hero-title">{f["h1"]}</h1>'
        f'<p class="hero-lede verdict">{f["verdict"]}</p>'
        f'<p class="cta-note">Facts about {name} checked {CHECKED} · <a href="#sources">See the sources</a></p>'
        f'</div><div class="hero-visual">{visual}</div></div></div></header>'
    )
    glance = _band("paper", section_head("At a glance", f"Ayuvo and {name}, side by side.",
                                          f"What each one does on the points people ask about most. Every statement about {name} links to its own page.", "01")
                   + table(name, f["rows"]))
    stronger = _band("paper alt", f'<div class="prose wide"><h2>Where {name} is stronger</h2>'
                                  "<p>Start here if these matter most to you.</p>" + _ticks(f["stronger"]) + "</div>")
    different = _band("paper", f'<div class="prose wide"><h2>Where Ayuvo is different</h2>' + _ticks(f["different"])
                      + '<p><a class="link-arrow" href="/privacy-first">See every data flow</a></p></div>')
    limits = _band("paper alt", f'<div class="prose wide"><h2>Where Ayuvo is limited</h2>'
                                "<p>What Ayuvo does not do, so you can decide with the full picture.</p>" + _ticks(limits_for(f)) + "</div>")
    choose = _band("ink raised", section_head("Which fits", "Choosing between them.", num="02") + chooser(name, f))
    src = _band("paper", f'<div class="prose wide"><h2>Sources</h2><p>Facts about {name} were taken from these pages.</p>'
                         + sources_list(f["sources"]) + disclaimer(name) + "</div>", "sources")
    others = "".join(_card(s) for s in ORDER if s != slug)
    more = _band("paper alt", section_head("Related", "More comparisons") + f'<div class="grid-2">{others}</div>'
                 + '<p class="cta-note"><a class="link-arrow" href="/compare">See all comparisons</a></p>')
    body = hero + privacy_band() + glance + stronger + different + limits + choose + src + more
    body += faq_section(f["faqs"], heading=f"Questions <em>about Ayuvo and {name}</em>.")
    body += cta_section()
    # the product discussed, by name and the page we cite; no claims in the markup
    mentions = [{"@type": "Thing", "name": name, "url": f["sources"][0][1]}]
    return Page(path=f"/compare/{slug}", title=f["title"], description=f["description"], body=body,
                crumbs=[("Compare", "/compare")], crumb=f["crumb"], og_headline=f["og_headline"],
                og_screens=[s for s, _ in f["screens"]], og_alt=f["title"], lcp=f"/assets/screens/{f['lcp']}.webp",
                faqs=f["faqs"], mentions=mentions)


def _card(slug: str) -> str:
    f = COMPARE[slug]
    return f'<a class="panel" href="/compare/{slug}"><h3>{f["name"]}</h3><p>{HUB_ONE_LINERS[slug]}.</p><span class="link-arrow">Compare with Ayuvo</span></a>'


HUB_FAQ = [
    ("How does Ayuvo compare with other health record apps?", "Ayuvo keeps records, medications, meals, workouts and Health data on your phone with no account. Services such as Guava Health and PicnicHealth connect to or gather records from your providers, which Ayuvo does not do. Each comparison page lists the differences and where the other product is stronger."),
    ("Are these comparisons independent?", "No. They are written by the Ayuvo team. Each fact about another product links to that product's own public page and shows the date we checked it, and every page says where the other product is stronger."),
    ("How do I report a mistake?", f'Email <a href="mailto:{EMAIL}?subject=Comparison%20correction">{EMAIL}</a> with the page and the correction. Products change, so we recheck the sources when we update these pages.'),
]


def build_compare_hub() -> Page:
    need_rows = [
        ("I want someone to gather my records from my providers", "Guava Health, PicnicHealth"),
        ("I want to keep my own records on my phone with no account", "Ayuvo"),
        ("I want to book care or message my doctor", "MyChart, or your provider's own portal"),
        ("I want the built-in store for my Apple devices", "Apple Health"),
        ("I want the shared health data store on Android", "Health Connect"),
        ("I want meals, workouts, records and medications in one app on iPhone and Android", "Ayuvo"),
    ]
    need = ('<div class="table-wrap"><table class="data-table"><caption class="sr-only">Which option fits which need</caption>'
            '<thead><tr><th scope="col">If you want to</th><th scope="col">Look at</th></tr></thead><tbody>'
            + "".join(f'<tr><th scope="row">{a}</th><td>{b}</td></tr>' for a, b in need_rows) + "</tbody></table></div>")
    cards = "".join(_card(s) for s in ORDER)
    hero = (
        '<header class="hero compact"><div class="container">' + _crumbs([], "Compare")
        + f'<div class="hero-center">{eyebrow("Compare")}<h1 class="hero-title">Ayuvo alongside <em>the apps you know.</em></h1>'
        '<p class="hero-lede">Honest comparisons with Guava Health, PicnicHealth, Apple Health, MyChart and Health Connect: what each does well, where Ayuvo is different, and when to use both.</p></div></div></header>'
    )
    body = (
        hero + privacy_band()
        + _band("paper", section_head("Comparisons", "Pick a product.", "Each page has a side-by-side table, where the other product is stronger, and links to its own pages as sources.", "01")
                + f'<div class="grid-3">{cards}</div>')
        + _band("ink raised", section_head("Which fits", "Start from what you need.", num="02") + need)
        + _band("paper", f'<div class="prose wide"><h2>How we compare</h2>'
                         '<ul class="ticks"><li><span>We only state facts about another product that its own public page supports, and we link to the page.</span></li>'
                         f"<li><span>Every comparison shows the date it was checked ({CHECKED}), and says where the other product is stronger.</span></li>"
                         "<li><span>We do not use ratings, review scores or price claims, and we do not use other companies' logos.</span></li>"
                         f'<li><span>Spotted something out of date? <a href="mailto:{EMAIL}?subject=Comparison%20correction">Email us</a>.</span></li></ul>'
                         f'<div class="disclaimer"><p><strong>About these comparisons.</strong> They are written by the Ayuvo team. Product names belong to their owners, and Ayuvo {DISCLAIMER_MARK} any of them.</p></div></div>')
    )
    body += faq_section(HUB_FAQ, heading="About <em>these comparisons</em>.") + cta_section()
    ld = [{
        "@context": "https://schema.org", "@type": "ItemList", "@id": f"{BASE}/compare#list", "name": "Ayuvo comparisons",
        "itemListOrder": "https://schema.org/ItemListUnordered", "numberOfItems": len(ORDER),
        "itemListElement": [{"@type": "ListItem", "position": i, "name": COMPARE[s]["crumb"], "url": f"{BASE}/compare/{s}"}
                            for i, s in enumerate(ORDER, 1)],
    }]
    return Page(path="/compare", title="Ayuvo Alternatives: Compare Health Record & Tracker Apps",
                description="Compare Ayuvo with Guava Health, PicnicHealth, Apple Health, MyChart and Health Connect. Honest differences on records, AI and privacy, with sources.",
                body=body, kind="collection", crumb="Compare", og_headline="Ayuvo alongside the apps you know.", og_screens=["records", "browse"],
                og_alt="Compare Ayuvo with Guava Health, PicnicHealth, Apple Health, MyChart and Health Connect", faqs=HUB_FAQ, ld=ld,
                main_entity=f"{BASE}/compare#list")


def compare_pages() -> list[Page]:
    return [build_compare_hub()] + [build_compare(s) for s in ORDER]
