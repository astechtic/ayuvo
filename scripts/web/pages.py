"""Page content for the Ayuvo site. Rendering lives in site_lib.py, the build in scripts/web_build.py.

Copy rules (enforced by the lint in web_build.py):
  * never claim data "never leaves the device": say it is kept on this device and leaves only when the
    user acts, to a service they chose;
  * MedGemma is a research model, not a medical device: every page that names it carries the disclaimer;
  * no ratings, reviews, download counts or superlatives about medical models;
  * do not advertise iCloud backup (removed 2026-09-22).
"""
from __future__ import annotations

import json
import re
from pathlib import Path

from compare_pages import compare_pages, compare_strip
from nutrient_pages import flow_svg, nutrient_pages
from site_lib import (
    BASE, EMAIL, FUD_AI, GITHUB, MEDGEMMA_DISCLAIMER, STORES, WEB, Page, catalog_card, catalog_table, cta_section,
    asset, eyebrow, faq_section, gib, icon, medgemma, phone, phones, privacy_band, section_head, store_buttons,
)

MG = medgemma()
MG_SIZE = gib(MG["artifact"]["sizeBytes"])

# The apps' own contracts: the Insights and Actions pages render their numbers and lists from these, so the site
# cannot drift from what the apps compute.
INSIGHTS = json.loads((WEB.parent / "shared" / "insights" / "insights_config.json").read_text(encoding="utf-8"))
ACTIONS = json.loads((WEB.parent / "shared" / "actions" / "action_catalog.json").read_text(encoding="utf-8"))
N_ACTIONS = len(ACTIONS["actions"])
# Derived metrics and GPS workouts: the metric list, methods, citations, sports and thresholds come from the apps' contracts.
DERIVED = json.loads((WEB.parent / "shared" / "derived" / "derived_config.json").read_text(encoding="utf-8"))
WORKOUT = json.loads((WEB.parent / "shared" / "workout" / "workout_config.json").read_text(encoding="utf-8"))
N_DERIVED = len(DERIVED["metrics"])
SCREENS = WEB / "assets" / "screens"


def have(slug: str) -> bool:
    return (SCREENS / f"{slug}.webp").exists()


def shot(slug: str, alt: str, fallback: str) -> str:
    """The phone screenshot once web/assets/screens/<slug>.webp exists, the fallback panel until then."""
    return phone(slug, alt) if have(slug) else fallback


def shots(items: list[tuple[str, str]], fallback: str, cls: str = "single") -> str:
    """A phones group of the screenshots that exist; the fallback when none does."""
    got = [(s, a) for s, a in items if have(s)]
    return phones(got, "pair" if len(got) > 1 else cls) if got else fallback

# ---------------------------------------------------------------------------
# layout helpers
# ---------------------------------------------------------------------------


def crumbs_html(trail: list[tuple[str, str]], here: str) -> str:
    items = "".join(f'<li><a href="{u}">{n}</a></li>' for n, u in [("Home", "/")] + trail)
    return f'<ol class="crumbs">{items}<li aria-current="page">{here}</li></ol>'


def eager_hero(visual: str) -> str:
    """Images in a hero are above the fold: never lazy, and the first one is the high-priority LCP candidate."""
    if "fetchpriority" in visual:
        return visual.replace(' loading="lazy"', "")
    visual = visual.replace('loading="lazy" decoding="async"', 'decoding="async"')
    return visual.replace('decoding="async"', 'fetchpriority="high" decoding="async"', 1)


def hero_split(trail, here, eyebrow_text, h1, lede, visual, ctas=True) -> str:
    cta = store_buttons("hero") if ctas else ""
    visual = eager_hero(visual)
    return (
        f'<header class="hero compact"><div class="container">{crumbs_html(trail, here)}'
        f'<div class="hero-split"><div class="hero-text">{eyebrow(eyebrow_text)}'
        f'<h1 class="hero-title">{h1}</h1><p class="hero-lede">{lede}</p>{cta}</div>'
        f'<div class="hero-visual">{visual}</div></div></div></header>'
    )


def split(eyebrow_text, h2, body, bullets=(), media="", reverse=False, link=None, tag="h2") -> str:
    ul = "<ul>" + "".join(f"<li><span>{b}</span></li>" for b in bullets) + "</ul>" if bullets else ""
    lk = f'<p><a class="link-arrow" href="{link[0]}">{link[1]}</a></p>' if link else ""
    r = " reverse" if reverse else ""
    return (f'<div class="split{r}"><div class="split-text">{eyebrow(eyebrow_text)}<{tag}>{h2}</{tag}>{body}{ul}{lk}</div>'
            f'<div class="split-media">{media}</div></div>')


def band(cls: str, inner: str, sid: str = "") -> str:
    i = f' id="{sid}"' if sid else ""
    return f'<section class="{cls}"{i}><div class="container">{inner}</div></section>'


def sends_panel(title: str, lede: str, rows: list[tuple[str, str]]) -> str:
    cells = "".join(f'<div class="panel"><span class="kicker">{k}</span><p>{v}</p></div>' for k, v in rows)
    return band("ink raised", section_head("Privacy", title, lede) + f'<div class="grid-3">{cells}</div>'
                + '<p class="cta-note"><a class="link-arrow" href="/privacy-first">See every data flow</a></p>')


def related(items: list[tuple[str, str, str]], title="Keep exploring") -> str:
    cells = "".join(f'<a class="panel" href="{u}"><h3>{t}</h3><p>{d}</p><span class="link-arrow">Learn more</span></a>' for u, t, d in items)
    return band("paper alt", section_head("Related", title) + f'<div class="grid-3">{cells}</div>')


def feature_page(path, title, description, crumb, h1, lede, eyebrow_text, hero_visual, blocks, sends, related_items,
                 faqs=(), og_headline="", og_screens=(), og_alt="", lcp_slug=None, med=False, ld=None, compare=False) -> Page:
    body = hero_split([("Features", "/features")], crumb, eyebrow_text, h1, lede, hero_visual)
    body += privacy_band()
    body += band("paper", "".join(blocks))
    body += sends
    if med:
        body += band("paper alt", MEDGEMMA_DISCLAIMER)
    body += related(related_items)
    if compare:
        body += compare_strip()
    if faqs:
        body += faq_section(list(faqs), heading="Questions <em>about this feature</em>.")
    body += cta_section()
    return Page(path=path, title=title, description=description, body=body, crumbs=[("Features", "/features")], crumb=crumb,
                og_headline=og_headline or h1, og_screens=list(og_screens), og_alt=og_alt or title,
                lcp=f"/assets/screens/{lcp_slug}.webp" if lcp_slug else None, faqs=list(faqs), ld=ld or [])


REL = {
    "nutrition": ("/features/nutrition", "Nutrition", "Photo, barcode and voice logging with 30+ nutrients."),
    "workouts": ("/features/workouts", "Workouts", "GPS walks, runs and rides, strength sessions and a 1,300+ exercise library."),
    "health": ("/features/health-data", "Health data", "Apple Health and Health Connect in one local hub."),
    "derived": ("/features/derived-metrics", "Derived metrics", f"{N_DERIVED} estimates from your own data, each with its published method."),
    "insights": ("/features/insights", "Insights & trends", "Recovery, Health Age, a daily review and trends against your own baseline."),
    "shortcuts": ("/features/siri-and-shortcuts", "Siri, Shortcuts & Android", "Ask Siri, build Shortcuts, or use Android shortcuts and links."),
    "records": ("/features/records-and-medications", "Records & medications", "Documents read on your phone, and dose reminders."),
    "coach": ("/features/coach", "AI coach", "Ask about your own data, with consent for each source."),
    "fasting": ("/features/fasting-and-water", "Fasting & water", "Optional timers and goals that stay local."),
    "switch": ("/features/switch-phones", "Switch phones", "Move from iPhone to Android, or back, with one zip."),
    "ondevice": ("/features/on-device-ai", "On-device AI", "Run models on the phone itself, with no key and no server."),
    "privacy": ("/privacy-first", "Private by design", "What is stored, what can leave, and when."),
    "providers": ("/ai-providers", "AI providers", "15 providers on your own key."),
    "source": ("/open-source", "Open source", "MIT-licensed. Read it, build it, improve it."),
}

# ---------------------------------------------------------------------------
# home FAQ (also used for the FAQPage JSON-LD)
# ---------------------------------------------------------------------------
HOME_FAQ = [
    ("Is Ayuvo free?", "Yes. There are no subscriptions, credits or in-app purchases. AI features use an API key you create with a provider (many have free tiers); any usage is billed by that provider, never by Ayuvo. On-device models cost nothing to run after the download."),
    ("Which AI can I use?", 'Google Gemini, OpenAI, Anthropic, xAI, OpenRouter, Together AI, Groq, Hugging Face, Fireworks AI, DeepInfra, Mistral, DeepSeek, Cerebras, Ollama or any OpenAI-compatible endpoint. On supported phones, on-device models such as Gemma 4 E2B and MedGemma 1.5 4B run on the phone after a one-time download; iPhones can also use Apple Intelligence.'),
    ("Where is my data?", "In the app's storage on your phone. Ayuvo has no account and no server. Data leaves the device only when you act: sending a photo or message to your AI provider, scanning a barcode, browsing exercise images, downloading a model, sharing a record, exporting, or turning on Android's Google Drive backup."),
    ("What does the Health data hub do with Apple Health or Health Connect?", "With your permission it keeps a local mirror of the data types you grant so Ayuvo can show charts and history. It writes only what you log or record in Ayuvo: nutrition, body measurements, workouts with their calories, and the route of a GPS workout. Values Ayuvo estimates are never written. You can revoke access any time in Health or Health Connect settings."),
    ("Can Ayuvo estimate vitals my watch or band doesn't record?", f'Yes. From data you already have, such as minute-by-minute heart rate, sleep and steps, Ayuvo estimates up to {N_DERIVED} metrics, including resting heart rate, heart rate zones, sleep regularity and cardio fitness, with published formulas. When Apple Health or Health Connect already has a value for that day, that value is shown instead. Each metric can be switched off. <a href="/features/derived-metrics">See every metric and its method</a>.'),
    ("Does Ayuvo track my location?", 'Only while you record a GPS walk, run, ride or hike. The route is stored on your phone and written to Apple Health or Health Connect with the workout. Ayuvo asks only for location while in use; on iPhone it keeps recording with the screen locked, with the location indicator showing. <a href="/features/workouts">See GPS workouts</a>.'),
    ("Does the coach see my health data?", 'Only if you allow it. Health data, medications and health records each have their own consent, and medications and records are off by default. When a source is on, the coach can request summaries of it and sends them to the AI provider you chose, or to an on-device model that keeps them on the phone.'),
    ("What are Recovery and Ayuvo Health Age?", 'Insights that Ayuvo calculates on your phone from your own synced data. Recovery is a 0 to 100 morning score comparing last night with your personal baselines. Ayuvo Health Age is Ayuvo\'s own estimate from fitness and habit markers, not a clinical or biological age. <a href="/features/insights">See how they are calculated</a>.'),
    ("Does Ayuvo work with Siri, Shortcuts and Android assistants?", f'Yes. On iPhone you can ask Siri, or build Shortcuts from {N_ACTIONS} Ayuvo actions such as Get Nutrient, Log Water and Get Recovery. On Android there are launcher shortcuts and ayuvo:// links, and assistant capabilities are declared for Google Assistant and Gemini. <a href="/features/siri-and-shortcuts">See what works where</a>.'),
    ("Can I move from iPhone to Android, or back?", "Yes. Export All Data on one phone creates a zip that Ayuvo on the other platform imports, during setup (Restore from a backup) or later. Your profile, goals, units, logs, workouts, health data, medications, health records and coach chats come along. API keys, reminder schedules and your AI provider choice deliberately do not."),
    ("What is MedGemma, and is it medical advice?", "MedGemma is Google's open model family for medical text and images. Ayuvo offers MedGemma 1.5 4B as an optional on-device model: after a one-time gated download from Hugging Face it runs on the phone. It is a research model, not a medical device. Its answers can be wrong, and Ayuvo never diagnoses or advises on doses."),
    ("How do backups work?", "Export All Data creates a zip on either platform that you save wherever you choose. Android can also back up to your own Google Drive app folder, off by default. Health data and medications are never in that Drive backup."),
    ("Can I export or delete everything?", "Yes. Export All Data covers your diary, health data, medications, health records and settings, and API keys are never included. Settings, Delete All Data wipes the app's local storage; Health and Health Connect samples are left untouched by design."),
    ("Which languages does Ayuvo support?", "18: English, Arabic, Azerbaijani, Czech, Dutch, French, German, Hindi, Italian, Japanese, Korean, Polish, Portuguese (Brazil), Romanian, Russian, Simplified Chinese, Spanish and Ukrainian."),
    ("Is it medical advice?", "No. Ayuvo is not a medical device. Estimates come from AI and formulas; talk to a clinician before changing diet, training or medication."),
    ("Is Ayuvo open source?", f'Yes. Ayuvo is MIT-licensed and the code is at <a href="{GITHUB}" rel="noopener">github.com/astechtic/ayuvo</a>. Read <a href="/open-source">how to build it and contribute</a>.'),
    ("How do I get support?", f'Email <a href="mailto:{EMAIL}">{EMAIL}</a> or open the <a href="/support">Support page</a>. In the app, Settings, Help &amp; Support opens a pre-filled email.'),
]

# ---------------------------------------------------------------------------
# HOME
# ---------------------------------------------------------------------------


def bento_tile(cls, ico, eyebrow_text, h3, p, href, screen=None, alt="") -> str:
    art = f'<div class="tile-art">{phone(screen, alt)}</div>' if screen else ""
    glyph = "" if screen else f'<svg class="tile-glyph icon" aria-hidden="true"><use href="#i-{ico}"/></svg>'
    text_only = "" if screen else " text-only"
    return (f'<a class="tile{cls}{text_only}" href="{href}">{glyph}{eyebrow(eyebrow_text)}<h3>{h3}</h3><p>{p}</p>{art}</a>')


def bento() -> str:
    tiles = [
        bento_tile("", "fork", "Nutrition", "Snap it, scan it, say it.", "Photos, barcodes, voice or text, reviewed before anything is saved.", "/features/nutrition", "nutrition", "Ayuvo daily nutrition diary with calories, macros and water"),
        bento_tile("", "dumbbell", "Workouts", "Record the route. Log the set.", "GPS walks, runs, rides and hikes with splits and a map, and strength sessions from a 1,300+ exercise library.", "/features/workouts", "workouts", "Ayuvo workout diary with calculated calorie burn"),
        bento_tile("", "chart", "Health data", "Every chart, one hub.", "Apple Health and Health Connect, mirrored on your phone.", "/features/health-data", "heart-rate", "Ayuvo heart rate chart with day, week, month and year ranges"),
        bento_tile("", "doc", "Records", "Documents that read themselves.", "Scan a report. Values and highlights appear, processed on your phone.", "/features/records-and-medications", "records", "Ayuvo health records with important highlights"),
        bento_tile("", "chat", "AI coach", "Ask about your own day.", "Consent for every data source, on your key or on your phone.", "/features/coach", "coach", "Ayuvo coach chat with suggested prompts"),
        bento_tile("", "timer", "Fasting & water", "Timers that stay optional.", "Off by default. Goals, reminders and widgets when you want them.", "/features/fasting-and-water", "summary", "Ayuvo Summary with Eat, Move and Drink rings"),
        bento_tile(" wide", "heart", "Insights", "Your own baseline, not someone else's.", "Morning Recovery, Ayuvo Health Age, a Daily Review, trends and patterns, calculated on your phone.", "/features/insights"),
        bento_tile(" wide", "chart", "Derived metrics", "Vitals your devices don't record.", f"Resting heart rate, zones, sleep regularity, cardio fitness and {N_DERIVED - 4} more, estimated from your own data. A value from Health always wins.", "/features/derived-metrics"),
        bento_tile(" wide", "timer", "GPS workouts", "Walk, run, ride or hike.", "Route, splits and elevation, with Pause, Lap and End on the Lock Screen, in a notification, on a widget or on Apple Watch.", "/features/workouts"),
        bento_tile(" wide", "sparkle", "Siri &amp; Shortcuts", "Ask Siri. Build a Shortcut.", f"{N_ACTIONS} actions for Siri, the Shortcuts app, Android shortcuts and links. No action can delete anything.", "/features/siri-and-shortcuts"),
        bento_tile(" wide", "swap", "Switch phones", "iPhone to Android, and back.", "Export All Data on one phone, import it on the other. Your history moves with you.", "/features/switch-phones"),
        bento_tile(" wide", "cpu", "On-device AI", "Models that live on the phone.", "Run Gemma, Qwen or a medical model on the phone itself. No key, no server, nothing to send.", "/features/on-device-ai"),
    ]
    return f'<div class="bento">{"".join(tiles)}</div>'


def build_home() -> Page:
    hero = (
        '<header class="hero"><div class="container"><div class="hero-center">'
        '<div class="hero-meta"><span><span class="dot"></span> iOS 17.6+ · Android 8.0+</span>'
        "<span>No account · No ads · No analytics</span><span>Open source</span></div>"
        '<h1 class="hero-title">Your health. Your device. <em>Your call.</em></h1>'
        '<p class="hero-lede">Ayuvo is a free, private health app for iPhone and Android. Nutrition, workouts, health records, medications, insights and your Apple Health or Health Connect data, together and kept on your phone. '
        "Ask an AI coach only if you want to, with a key you bring or a model that runs on the phone itself.</p>"
        f'{store_buttons("hero")}'
        '<p class="cta-note">Free for iPhone and Android. No account. Open source under the MIT licence.</p></div>'
        '<div class="hero-stage">'
        + phones([("records", "Ayuvo health records with important highlights"), ("summary", "Ayuvo Summary with Eat, Move and Drink rings"),
                  ("coach", "Ayuvo coach chat with suggested prompts")], "fan", eager_index=1)
        + "</div></div></header>"
    )

    features = band("paper", section_head("Everything in one place", "One app for the whole picture.",
                                          "The parts of your health that usually live in six apps, kept together on your phone, with an AI coach that can read across them only when you allow it.", "01")
                    + bento(), "features")

    privacy = band("ink", split(
        "Privacy", "Private by design. <em>Not as a setting.</em>",
        "<p>There is no Ayuvo account and no Ayuvo server. Your food, workouts, health data, records and chats are stored in the app on your phone. "
        "Data leaves it only when you take an action, and only to a service you chose.</p>",
        ["Health data, medications and records each need their own consent before the coach can read them",
         "API keys live in the iOS Keychain or Android EncryptedSharedPreferences, never in a backup",
         "No analytics, crash-reporting or advertising SDKs. This website sets no cookies",
         "Open source, so the claims above can be checked"],
        media=('<div class="panel"><span class="kicker">What can leave your phone, and only when you act</span><ul>'
               "<li>Photos, text and coach messages to the AI provider you configured</li>"
               "<li>Audio to a speech provider, only if you pick a cloud one</li>"
               "<li>A barcode number to Open Food Facts, when you scan</li>"
               "<li>Exercise images and model files, as plain requests to GitHub and Hugging Face</li>"
               "<li>Map images of the area shown, when you look at a workout route</li>"
               "<li>A record or export, when you tap Share or Export</li>"
               "<li>Your own Google Drive, if you turn on Android backup</li></ul></div>"),
        link=("/privacy-first", "See every data flow"))
        + '<div class="stats"><div class="stat"><strong>0</strong><span>Ayuvo accounts or servers</span></div>'
          '<div class="stat"><strong>0</strong><span>analytics, crash or ad SDKs</span></div>'
          '<div class="stat"><strong>15</strong><span>AI providers on your own key</span></div>'
          '<div class="stat"><strong>18</strong><span>languages</span></div></div>', "privacy")

    records = band("paper", split(
        "Health records &amp; medications", "Your paperwork, <em>read on your phone</em>.",
        "<p>Add lab reports, prescriptions and visit notes from the camera, a file, or the share sheet. Ayuvo keeps the original, reads the text on your phone and pulls out the doctor, dates and test values. "
        "Results the report itself marks as outside its range are highlighted. Medications get schedules and dose reminders.</p>",
        ["You choose how AI helps: on this device, your own provider, ask each time, or not at all",
         "Every record says whether it was processed on this device, online, or not by AI",
         "Share pages with name, address and ID numbers blacked out",
         "Medication reminders with Taken, Skip and Snooze right from the notification"],
        media=phones([("records", "Ayuvo health records list with highlights"), ("medications", "Ayuvo medications with today's doses")], "pair"),
        link=("/features/records-and-medications", "See records and medications")))

    ondevice = band("ink raised", split(
        "On-device AI", "Run a health model <em>on the phone itself</em>.",
        f"<p>Download Gemma, Qwen or MedGemma once and ask questions with no key, no server and nothing to send. "
        f"MedGemma is Google's open model family for medical text and images. Ayuvo offers MedGemma 1.5 4B, a {MG_SIZE} download for phones with 8 GB of memory or more.</p>",
        ["Gated download: accept Google's terms on Hugging Face, then add a free access token",
         "Text and image understanding, in a 2,048-token context window",
         "A research model, not a medical device. It never replaces your clinician"],
        media=catalog_card(),
        link=("/features/on-device-ai", "See the on-device models"), reverse=True)
        + MEDGEMMA_DISCLAIMER, "on-device")

    worksw = band("paper alt", split(
        "Works with", "Apple Health and <em>Health Connect</em>, mirrored locally.",
        "<p>With your permission Ayuvo keeps a local copy of the Health data you grant, then shows day, week, month, six-month and year charts, sources and units for every metric. "
        "It writes back only what you log or record: nutrition, body measurements, workouts and GPS routes. "
        "Where your devices leave a gap, Ayuvo estimates vitals such as resting heart rate from the data you already have, and a value from Health always wins.</p>"
        f'<div class="works-with"><span class="chip">{icon("heart")}<span>Works with Apple Health<small>iPhone</small></span></span>'
        f'<span class="chip">{icon("heart")}<span>Works with Health Connect<small>Android</small></span></span></div>',
        ["13 categories, from activity and sleep to cycle tracking and vitals",
         "Pin up to 12 favourites to Summary, with 7-day sparklines",
         f"{N_DERIVED} derived metrics, each with its method and source, and each one can be switched off",
         "Your Health data is never in a cloud backup"],
        media=phones([("browse", "Ayuvo Browse list of health categories"), ("heart-rate", "Ayuvo heart rate detail chart")], "pair"),
        link=("/features/health-data", "See the Health data hub")), "health")

    switch = band("paper", split(
        "Switch phones", "Change phones. <em>Or platforms.</em>",
        "<p>Export All Data creates one zip. Import it on an iPhone or an Android phone, or choose Restore from a backup while setting Ayuvo up, and your profile, goals, logs, workouts, medications, records and chats are back.</p>",
        ["Merge, never overwrite: importing twice adds nothing, and nothing is deleted",
         "API keys are never exported. You add them again on the new phone",
         "An open, documented format you can inspect"],
        media=phone("export-data", "Ayuvo Export All Data sheet listing food diary, health data, medications, records and settings"),
        link=("/features/switch-phones", "See what moves and what stays"), reverse=True), "switch")

    oss = band("ink", split(
        "Open source", "Read it. Build it. <em>Make it better.</em>",
        f'<p>Ayuvo is MIT-licensed, with the iPhone app in SwiftUI, the Android app in Jetpack Compose and shared contracts between them. Developers can contribute code, exercises, translations and docs. '
        f'The project is inspired by an earlier open-source app, and its licence notice travels with it.</p>',
        [], media=(f'<div class="repo"><a class="gh-btn" href="{GITHUB}" rel="noopener">{icon("github")}<span>View on GitHub</span></a>'
                   '<code>astechtic/ayuvo</code></div>'),
        link=("/open-source", "How to contribute")), "open-source")

    steps = band("paper", section_head("How it works", "From capture to coaching <em>in seconds</em>.", num="02") + (
        '<div class="steps">'
        '<div class="step"><div class="step-num">Step 01</div><h3>Capture</h3><p>Snap up to 10 photos, scan a barcode, speak, type, or scan a document. Log a set, a glass of water, a dose or start a fast from the same + menu.</p></div>'
        '<div class="step"><div class="step-num">Step 02</div><h3>Review</h3><p>Your chosen provider, or a model on your phone, reads the photo or document. Correct anything before it is saved.</p></div>'
        '<div class="step"><div class="step-num">Step 03</div><h3>Keep</h3><p>Saved on your device. Nutrition, weight and calculated burn go to Apple Health or Health Connect only if you enable it.</p></div>'
        '<div class="step"><div class="step-num">Step 04</div><h3>Ask Ayuvo</h3><p>The coach pulls the slice of your data it needs, from the sources you allowed, and answers in context.</p></div></div>'))

    body = hero + privacy_band() + features + privacy + records + ondevice + worksw + switch + oss + compare_strip() + steps
    body += faq_section(HOME_FAQ, "03") + cta_section()
    return Page(path="/", title="Ayuvo: Free, Private AI Health App for iPhone & Android",
                description="Ayuvo is a free, private health app for iPhone and Android: food, workouts, health records, medications and Apple Health data in one place. No account, no ads.",
                body=body, kind="home", faqs=HOME_FAQ, lcp="/assets/screens/summary.webp", modified="2026-09-25",
                og_headline="Your health. Your device. Your call.", og_screens=["records", "summary", "coach"],
                og_alt="Ayuvo: your health, your device, your call. Private AI health app for iPhone and Android.")


# ---------------------------------------------------------------------------
# FEATURES overview
# ---------------------------------------------------------------------------


def build_features() -> Page:
    body = (
        '<header class="hero compact"><div class="container">' + crumbs_html([], "Features")
        + f'<div class="hero-center">{eyebrow("Features")}<h1 class="hero-title">Everything in one place. <em>Nothing shared by default.</em></h1>'
        '<p class="hero-lede">Nutrition, workouts, health data, insights, records, medications, fasting, water and an AI coach, each built to keep working on your phone with no account.</p></div></div></header>'
        + privacy_band()
        + band("paper", section_head("Core", "The parts of your health.") + bento())
        + band("ink", section_head("Around the app", "On your wrist, your Home Screen and your language.", "The same data, wherever you look for it.")
               + '<div class="grid-3">'
                 f'<div class="panel"><span class="kicker">{icon("watch")} Apple Watch</span><h3>Watch app, workouts and complications</h3><p>Calories, macros and water from your wrist, with water logging on the Watch. Start a walk, run, ride or hike on the Watch, and the iPhone shows it live. <a class="link-arrow" href="/features/workouts">See workouts</a></p></div>'
                 f'<div class="panel"><span class="kicker">{icon("chart")} Widgets</span><h3>Today, My Metrics, Quick Log, Workout</h3><p>Home Screen widgets on iPhone and Android. They never invent data. The Workout widget starts a walk, run, ride, hike or strength session and controls it while it runs. Widget settings stay on the device.</p></div>'
                 f'<div class="panel"><span class="kicker">{icon("chart")} Derived metrics</span><h3>{N_DERIVED} estimates, one switch each</h3><p>Resting heart rate, zones, sleep regularity, cardio fitness and more, estimated from your own data with published formulas. A value from Health always wins. <a class="link-arrow" href="/features/derived-metrics">See the metrics</a></p></div>'
                 f'<div class="panel"><span class="kicker">{icon("sparkle")} Siri &amp; Shortcuts</span><h3>Siri, Shortcuts and Android shortcuts</h3><p>{N_ACTIONS} actions to ask Siri, chain in the Shortcuts app, or run from Android shortcuts and links. The Share Extension sends a food photo or a health document straight into Ayuvo. <a class="link-arrow" href="/features/siri-and-shortcuts">See the actions</a></p></div>'
                 f'<div class="panel"><span class="kicker">{icon("heart")} Insights</span><h3>Recovery, Health Age and trends</h3><p>Scores and trends measured against your own 60-day baseline, calculated on the phone, each with a sheet that shows how. <a class="link-arrow" href="/features/insights">See Insights</a></p></div>'
                 f'<div class="panel"><span class="kicker">{icon("globe")} Languages &amp; appearance</span><h3>18 languages, your units</h3><p>English, Arabic, Azerbaijani, Czech, Dutch, French, German, Hindi, Italian, Japanese, Korean, Polish, Portuguese (Brazil), Romanian, Russian, Simplified Chinese, Spanish and Ukrainian. Dark mode, 18 accent colours, and metric or imperial units.</p></div>'
                 f'<div class="panel"><span class="kicker">{icon("swap")} Your data</span><h3>Export, import, delete</h3><p>Export All Data, import it on either platform, or delete everything from Settings.</p></div>'
                 f'<div class="panel"><span class="kicker">{icon("lock")} Lock Screen</span><h3>Live Activity and live notification</h3><p>A GPS workout keeps its time, distance and pace on the iPhone Lock Screen and Dynamic Island, or in an Android notification, with Pause, Lap and End buttons.</p></div></div>')
        + compare_strip()
        + cta_section()
    )
    return Page(path="/features", title="Ayuvo Features: Nutrition, Workouts, Records & More",
                description="Explore Ayuvo: AI food logging, workouts, an Apple Health and Health Connect hub, records, medications, fasting, water and a coach that respects your consent.",
                body=body, kind="collection", crumb="Features", og_headline="Everything in one place.", og_screens=["nutrition", "workouts", "records"],
                og_alt="Ayuvo features: nutrition, workouts, health data, records, coach")


# ---------------------------------------------------------------------------
# FEATURE PAGES
# ---------------------------------------------------------------------------


def build_nutrition() -> Page:
    blocks = [
        split("Capture", "Six ways to log a meal.",
              "<p>Photograph up to 10 items at once, scan a barcode, speak, type, reuse a saved meal or enter it by hand. Ayuvo estimates calories, macros and more than 30 nutrients, and shows them to you to review before anything is saved.</p>",
              ["Barcode lookups use the public Open Food Facts database and send only the barcode number",
               "Smart serving units, such as slices, cups and millilitres, with grams as the source of truth",
               'Ask "What if?" to preview how a meal changes today\'s targets before you log it'],
              phone("nutrition", "Ayuvo Today nutrition diary with calories, macros and water")),
        split("Targets", "Goals that fit you, and that you can override.",
              "<p>Ayuvo calculates a calorie and macro target from your profile with the Mifflin-St Jeor or Katch-McArdle formula. Change any number, set nutrient goals, and choose your own meal times.</p>",
              ["Eat, Move and Drink rings on Summary show today at a glance",
               "Pin Calories, Protein and other nutrients as Favourites with 7-day sparklines",
               "Water entries live in the same diary, and hydration has its own optional goal"],
              phone("summary", "Ayuvo Summary with Eat, Move and Drink rings"), reverse=True),
        split("Nutrients", "A chart for every nutrient, with your reference lines.",
              "<p>Open any nutrient for day, week, month, six-month and year charts. Dashed lines show the recommended amount for your age and sex and the upper limit, from the NIH and National Academies tables, or your own goal if you set one.</p>",
              ["Default nutrient goals follow the NASEM Dietary Reference Intakes for your sex and age. A goal you set yourself is never overwritten",
               "Supplements count too: add the nutrients in a vitamin or supplement in Medications, by hand or read from a label photo, and every dose you mark taken adds to that day",
               "A weekly supplement, such as 60,000 IU of vitamin D, is spread over the seven days it covers instead of counting as one huge day, and anything above the upper limit is flagged with “follow your prescriber”",
               "Food and supplements are shown separately, and supplements never add calories or get written to Apple Health or Health Connect",
               "Each chart links to a sourced guide explaining what the nutrient does and how much adults need"],
              '<div class="panel"><span class="kicker">Nutrient guides</span><h3>37 sourced guides</h3><p>What each vitamin and mineral does, food sources, how much adults need and signs of too little or too much.</p>'
              '<p><a class="link-arrow" href="/nutrients">Browse the nutrient guides</a></p></div>'),
        split("Quality and timing", "Not just how much. <em>What, and when.</em>",
              "<p>Each entry has an eaten-at time, separate from when you logged it, and you can change it. From your diary Ayuvo works out protein per kilogram, macro split, saturated fat share, fibre per 1,000 kcal, sodium to potassium, your eating window and the gap between your last meal and bedtime.</p>",
              ["Saturated fat against the WHO limit of 10% of energy, fibre against 14 g per 1,000 kcal",
               "A gentle note when tea or coffee came within an hour of an iron-rich meal",
               "Energy balance, and an adaptive energy estimate from what you ate and your weight trend, once you have 14 logged days and 6 weigh-ins"],
              '<div class="panel"><span class="kicker">Derived metrics</span><h3>Every one can be switched off</h3><p>Nutrition metrics follow the same rules as the rest of Ayuvo\'s estimates: shown with their method, and off with one switch.</p>'
              '<p><a class="link-arrow" href="/features/derived-metrics">See the metrics</a></p></div>', reverse=True),
    ]
    sends = sends_panel("What a meal log sends, and to whom", "Logging is local. These are the only times something leaves your phone.", [
        ("Photos and text", "Sent to the AI provider you configured to identify the food. Choose an on-device model instead and the photo stays on the phone."),
        ("Barcode", "The barcode number, and nothing else, goes to Open Food Facts when you scan."),
        ("Voice", "Speech is recognised on the device by default. Audio is sent to a provider only if you choose a cloud speech provider."),
    ])
    return feature_page(
        "/features/nutrition", "Private AI Food Scanner & Calorie Tracker | Ayuvo",
        "Log meals by photo, barcode, voice or text and review 30+ nutrients before saving. Your diary stays on your phone. Use your own AI key or an on-device model.",
        "Nutrition", "Log a meal in seconds. <em>Keep the data.</em>",
        "Photo, barcode, voice, text or manual entry, with calories, macros and 30+ nutrients to review before anything is saved. Your diary stays on your phone.",
        "Nutrition", phones([("nutrition", "Ayuvo Today nutrition diary")], "single"), blocks, sends,
        [REL["health"], REL["derived"], REL["coach"]], lcp_slug="nutrition", og_screens=["nutrition", "summary"],
        og_headline="Log a meal in seconds. Keep the data.")


def _panel(kicker: str, h3: str, body: str) -> str:
    return f'<div class="panel"><span class="kicker">{kicker}</span><h3>{h3}</h3>{body}</div>'


def _ticks(items: list[str]) -> str:
    return '<ul class="ticks">' + "".join(f"<li><span>{i}</span></li>" for i in items) + "</ul>"


def build_workouts() -> Page:
    th = WORKOUT["thresholds"]
    sports = [s["title"] for s in WORKOUT["sports"].values()]
    sport_rows = [[s["title"], f'{s["auto_pause_speed_mps"] * 3.6:g} km/h']
                  for s in sorted(WORKOUT["sports"].values(), key=lambda s: s["auto_pause_speed_mps"])]
    widget_rows = [["Ready", "Walk, Run, Cycle, Hike and Strength buttons", "Each opens the app and starts recording"],
                   ["Recording", "Sport, a ticking timer, distance and pace (speed for cycling)", "Pause · Lap · End"],
                   ["Paused", "A frozen timer", "Resume · End"],
                   ["Strength session", "A ticking timer", "Finish"]]

    gps_fallback = _panel("GPS workouts", f"{len(sports)} outdoor sports",
                          "<p>Live time, distance, pace, heart rate, elevation and the current split while you move, then a map of the route, "
                          "per-kilometre splits, elevation gain and loss, heart rate zones and heart rate along the route.</p>"
                          + _table(["Sport", "Auto-pause below"], sport_rows))
    live_fallback = _panel("While it runs", "Controls where you already look",
                           _ticks(["<strong>iPhone Lock Screen and Dynamic Island.</strong> A Live Activity with the timer, distance and pace, and Pause, Resume, Lap and End buttons",
                                   "<strong>Android.</strong> An ongoing notification with the same four buttons, promoted to a Live Update on Android 16",
                                   "<strong>Apple Watch.</strong> Start on the Watch and the iPhone shows it live. Only one device saves the workout, so it is never counted twice"]))
    widget_fallback = _panel("Workout widget", "Start and control a workout from the Home Screen", _table(["State", "Shows", "Buttons"], widget_rows)
                             + "<p>iPhone: small, medium and Lock Screen. Android: 2×2 and 4×2.</p>")
    fitness_fallback = _panel("Cardio fitness", "Measured on a real route",
                              f"<p>Steady stretches of at least {th['min_segment_s'] // 60} minutes on flat ground (grade under {th['max_grade'] * 100:g}%) with heart rate between "
                              f"{th['min_hrr'] * 100:g}% and {th['max_hrr'] * 100:g}% of your reserve give a VO₂max estimate from the ACSM walking and running equations. "
                              "A guided 12-minute Cooper test is there if you want one.</p>"
                              f"<p>Heart rate recovery one minute after End is shown too. A drop of less than {th['hrr1_abnormal_below']} bpm is flagged as worth mentioning to a doctor.</p>")

    blocks = [
        split("GPS", "Walk, run, ride or hike. <em>Keep the route.</em>",
              "<p>Choose a sport and press Start. Ayuvo records the route with your phone's GPS, and keeps going with the screen locked. Pause, laps and auto-pause are built in, and when you finish you get the map, splits and elevation.</p>",
              ["Real start and end times, and moving time that leaves out every pause",
               "Poor fixes (worse than 20 m) and GPS jumps are dropped before any distance is counted",
               "Elevation from the barometer when the phone has one, otherwise from GPS with a 3 m threshold",
               "Saved to Apple Health or Health Connect as a workout with its route"],
              shots([("gps-live", "Ayuvo GPS run in progress with time, distance, pace and map"),
                     ("gps-summary", "Ayuvo GPS workout summary with route map, splits and elevation")], gps_fallback)),
        split("Lock Screen", "Pause, lap and end <em>without opening the app</em>.",
              "<p>A GPS workout or strength session stays in view while it runs. On iPhone it is a Live Activity on the Lock Screen and in the Dynamic Island. On Android it is an ongoing notification. Both have Pause, Resume, Lap and End.</p>",
              ["Start on Apple Watch and the iPhone shows the Watch's heart rate and distance live",
               "If the app is closed mid-workout, the recording can be resumed or saved when you come back"],
              shots([("gps-live-activity", "Ayuvo workout Live Activity on the iPhone Lock Screen with Pause, Lap and End"),
                     ("gps-notification", "Ayuvo workout notification on Android with Pause, Lap and End")], live_fallback), reverse=True),
        split("Widget", "One tap from the Home Screen.",
              "<p>Add the Workout widget and start a walk, run, ride, hike or strength session straight from the Home Screen. While it runs, the widget shows the ticking timer, distance and pace, and its buttons pause, lap and end the workout.</p>",
              ["The timer ticks on its own, so the widget stays current without draining the battery",
               "It never replaces a running workout: a tap on Start opens the one already running"],
              shots([("widget-workout", "Ayuvo Workout widget with a run in progress"),
                     ("widget-workout-idle", "Ayuvo Workout widget with Walk, Run, Cycle, Hike and Strength buttons")], widget_fallback)),
        split("Cardio fitness", "A fitness estimate from your own runs.",
              "<p>With a heart rate source, each GPS walk or run can give an estimate of your VO₂max, the standard measure of cardio fitness. It replaces the rougher estimate from resting heart rate, and a VO₂max from your watch still takes priority.</p>",
              ["Heart rate zones from your heart rate reserve (ACSM), training load as Banister TRIMP",
               "Calories from heart rate (Keytel 2005) when it covers at least 70% of the workout, otherwise from the activity's MET value"],
              fitness_fallback, reverse=True),
        split("Strength", "A strength session with a real start and finish.",
              "<p>Press Start session before your first set and Finish at the end, and the session gets its real time window. Forgot? When you calculate the day's burn, Ayuvo looks at your heart rate and suggests the window when you were training. You confirm or change the times.</p>",
              ["Plan the week and log every set with reps, weight and RPE",
               "Calculated burn, written to Apple Health or Health Connect as a workout over the real window, only if you enable it",
               "Ask the coach about your plans, sets and completed sessions, if you allow it"],
              phone("workouts", "Ayuvo workout diary with sets, reps and calculated burn")),
        split("Library", "1,300+ exercises, filtered your way.",
              "<p>Browse the library by split, target muscle, secondary muscle and equipment, and add your own exercises and activities.</p>",
              ["Exercise photos and animations load from the open exercises-dataset when you browse the library, as plain image requests",
               "Workout plans, sessions and saved exercises move between iPhone and Android in Export All Data"],
              phone("exercises", "Ayuvo exercise library with search and filters"), reverse=True),
        f'<div class="disclaimer"><p><strong>Not a clinical test.</strong> {WORKOUT["disclaimer"]}</p></div>',
    ]
    sends = sends_panel("What workouts send, and to whom", "Your routes, sets and plans are stored on the phone.", [
        ("Location", "Only while a GPS workout records, and only on the phone. The route goes to Apple Health or Health Connect with the workout."),
        ("Map tiles", "Showing a route map loads map images: from Apple Maps on iPhone, and from OpenStreetMap on Android. Those services see which area is shown, not your route."),
        ("Exercise media and coach", "Browsing the library requests images from GitHub. The coach reads your workouts only when you ask, and sends them to the provider you chose or to an on-device model."),
    ])
    return feature_page(
        "/features/workouts", "GPS Workout Tracker, Strength Log & Exercises | Ayuvo",
        "Record GPS walks, runs, rides and hikes with a route map, splits and elevation, and log strength sessions from a 1,300+ exercise library. Stored on your phone.",
        "Workouts", "Record the route. <em>Log the set.</em>",
        "GPS walks, runs, rides and hikes with a map, splits and elevation, controls on the Lock Screen, a widget and Apple Watch, and a strength diary with a library of more than 1,300 exercises.",
        "Workouts", shots([("gps-summary", "Ayuvo GPS workout summary with route map")], phones([("workouts", "Ayuvo workout diary")], "single")), blocks, sends,
        [REL["derived"], REL["health"], REL["insights"]], lcp_slug="gps-summary" if have("gps-summary") else "workouts",
        og_screens=[s for s in ("gps-summary", "workouts", "exercises") if have(s)][:2],
        faqs=[("Does Ayuvo need location access all the time?", "No. It asks only for location while using the app. A workout started in the app keeps recording with the screen locked, and the location indicator shows while it does."),
              ("Does the Workout widget start recording by itself?", "A tap on a sport opens Ayuvo and starts the recording there, because phones only allow location recording to begin while the app is open. After that, Pause, Lap and End work from the widget."),
              ("Will a Watch workout be counted twice?", "No. When the Apple Watch records the workout, it saves it and the iPhone only shows it live."),
              ("How accurate is the VO₂max estimate?", "It is an estimate from published equations, not a lab test. It needs a heart rate source and steady, flat stretches, and a VO₂max from your watch is shown instead when there is one.")],
        og_headline="Record the route. Log the set.")


DERIVED_CATS = {"heart": "Heart", "sleep": "Sleep", "activity": "Activity", "energy": "Energy", "mobility": "Walking",
                "hearing": "Hearing", "body": "Body", "nutrition": "Nutrition"}


def build_derived() -> Page:
    ms = DERIVED["metrics"]
    by_cat: dict[str, list[dict]] = {}
    for m in ms:
        by_cat.setdefault(m["category"], []).append(m)
    counts = ", ".join(f"{DERIVED_CATS.get(c, c.title()).lower()} {len(v)}" for c, v in by_cat.items())
    native = [m for m in ms if m.get("native") and any(m["native"].values())]

    def row(m: dict) -> list[str]:
        tag = ' <span class="tag">Health wins</span>' if m in native else ""
        return [f"<strong>{m['title']}</strong>{tag}", m["method"], f"<small>{m['citation']}</small>"]

    catalogue = "".join(f'<h3>{DERIVED_CATS.get(c, c.title())}</h3>' + _table(["Metric", "How it is calculated", "Reference"], [row(m) for m in v])
                        for c, v in by_cat.items())

    flow = {"title": "How a derived metric is chosen", "steps": [
        {"label": "Your synced data", "sub": "Minute-by-minute heart rate, sleep, steps, weight and your logs"},
        {"label": "Quality check", "sub": "Enough coverage that day, one source per window, never two devices averaged"},
        {"label": "Published formula", "sub": "The same maths and test vectors on iPhone and Android"},
        {"label": "On your screen", "sub": "Badged Estimated by Ayuvo, with the method and reference"}],
        "side": {"at": 3, "label": "Health value", "sub": "A value Apple Health or Health Connect has for that day", "verb": "replaces"}}
    diagram = f'<div class="flow-wrap">{flow_svg(flow, "Derived", "der-flow")}</div>'

    examples = [
        ["Resting heart rate", "Lowest 5-minute average inside last night's sleep", "Your watch's resting heart rate"],
        ["Cardio fitness (VO₂max)", "From resting and maximum heart rate, then from GPS runs", "Your watch's VO₂max"],
        ["BMI", "Weight and height, WHO or Asian cut-offs", "A BMI in Health"],
        ["Sleep Regularity Index", "How alike each pair of days is, minute by minute", "Nothing: Ayuvo's own"],
    ]
    hero_card = _panel("Priority, per metric, per day", "A value from Health always wins",
                       _table(["Metric", "Ayuvo estimates", "Shown instead when present"], examples)
                       + "<p>Estimates are never written to Apple Health or Health Connect.</p>")

    blocks = [
        f'<div class="prose wide">{section_head("How it works", "Filling the gaps your devices leave.", f"Many bands and watches record heart rate, sleep and steps but never turn them into resting heart rate, zones or sleep regularity. Ayuvo does, on your phone, for {N_DERIVED} metrics ({counts}).")}{diagram}</div>',
        split("Priority", "Your device first. <em>Ayuvo second.</em>",
              "<p>For each metric and each day, if Apple Health or Health Connect already holds a value, Ayuvo shows that value with its source. Only on days without one does it show its own estimate, badged Estimated by Ayuvo.</p>",
              ["Estimates are never written back, so they can never feed into their own inputs",
               "Every estimate carries its coverage and confidence, and is hidden when there is too little data",
               "Each metric's page shows the method, the published reference and the inputs used"],
              shots([("derived-detail", "Ayuvo derived resting heart rate with the Estimated by Ayuvo badge and method")], hero_card)),
        split("Your switches", "Turn off any metric. <em>Or all of them.</em>",
              "<p>Settings, Derived Metrics has a master switch and one switch per metric, and each metric's page has its own. A metric you turn off is no longer calculated, its stored values are deleted, and it disappears from Browse, Summary and Coach.</p>",
              ["Metrics that depend on it say so: turning off resting heart rate moves zones to percentages of maximum heart rate",
               "Choose WHO or Asian BMI categories (overweight from 23, obese from 27.5)",
               "Your switches move with you between iPhone and Android in Export All Data"],
              shots([("derived-settings", "Ayuvo Derived Metrics settings with a switch for each metric")],
                    _panel("Settings", "Derived Metrics", _ticks(["Master switch: on by default", "One switch per metric, grouped by category", "“Also affects” lists the metrics that depend on each one", "BMI categories: WHO or Asian"]))),
              reverse=True),
        split("Heart", "Resting heart rate, zones and load.",
              "<p>From minute-by-minute heart rate: resting and sleeping heart rate, the night-time dip, your daytime range, maximum heart rate (Tanaka, replaced by your observed maximum after enough workouts), heart rate reserve, cardio minutes in ACSM zones, training load and a resting heart rate compared with your usual.</p>",
              ["Weekly moderate and vigorous minutes against the WHO 150 to 300 minute guideline",
               "Wear time and valid days (10 hours or more), so a day with the band off is not judged"],
              _panel("Sleep", "Regularity, timing and debt",
                     "<p>Efficiency, time to fall asleep, deep and REM share, wake-ups, bedtime and wake time, the Sleep Regularity Index, social jet lag, chronotype and a 14-day sleep debt against the 7 hours or more that the AASM and SRS recommend for adults.</p>")),
        split("Every day", "Activity, energy, walking and body.",
              "<p>Brisk walking minutes (100 steps a minute or more), peak cadence, active hours, the longest sitting spell, your activity level, total energy burned and physical activity level, walking speed and steadiness trends, weekly headphone sound dose against the WHO-ITU safe listening limit, and a smoothed weight trend with a healthy range and weeks to goal.</p>",
              ["Steps are counted once, even when your phone and watch both recorded them",
               "Nutrition metrics use the time you ate, not the time you logged it"],
              shots([("derived-browse", "Ayuvo Browse with derived metrics badged Estimated by Ayuvo")],
                    _panel("Nutrition and balance", "From your food diary",
                           "<p>Protein per kilogram, macro split, saturated fat share, fibre per 1,000 kcal, sodium to potassium, eating window, last meal to bedtime, tea or coffee with an iron-rich meal, energy balance and an adaptive energy estimate from intake and your weight trend.</p>")),
              reverse=True),
        f'<div class="prose wide">{section_head("Catalogue", f"All {N_DERIVED} metrics, with their methods.", "Rendered from the same file the apps read, so this list cannot drift from what they calculate. “Health wins” marks the metrics where a value from Apple Health or Health Connect is shown instead when there is one.")}{catalogue}</div>',
        f'<div class="disclaimer"><p><strong>Not medical advice.</strong> {DERIVED["disclaimer"]}</p></div>',
    ]
    sends = sends_panel("What derived metrics send, and to whom", "They are calculated from data already on the phone.", [
        ("Nothing, to calculate", "Every estimate is calculated on the phone from your synced data and logs. Nothing is uploaded."),
        ("Nothing to Health", "Estimates are never written to Apple Health or Health Connect, and they are not in an export: they are recalculated."),
        ("Coach, with its toggle", "The coach sees the derived metrics you have on, marked as estimates, only while its health data consent is on."),
    ])
    return feature_page(
        "/features/derived-metrics", "Resting Heart Rate, Zones & Sleep Estimates | Ayuvo",
        f"Ayuvo estimates {N_DERIVED} health metrics your devices don't record, from resting heart rate to sleep regularity, with published methods. Health values always win.",
        "Derived metrics", "Vitals your devices <em>don't record.</em>",
        f"Resting heart rate, zones, sleep regularity, cardio fitness and {N_DERIVED - 4} more, estimated on your phone from the data you already have. A value from Apple Health or Health Connect always wins, and every metric can be switched off.",
        "Derived metrics", hero_card, blocks, sends,
        [REL["insights"], REL["health"], REL["workouts"]], og_screens=[s for s in ("derived-detail", "heart-rate", "browse") if have(s)][:2],
        faqs=[("Why does a metric say Estimated by Ayuvo?", "Your devices did not record that metric for that day, so Ayuvo calculated it from other data you have, such as minute-by-minute heart rate. When Health has a real value, that is shown with its source instead."),
              ("Does Ayuvo write its estimates to Apple Health or Health Connect?", "No. Only what you log or record, such as food, weight and workouts, is written. Estimates stay in Ayuvo."),
              ("How do I turn a metric off?", "Settings, Derived Metrics, or the switch on the metric's own page. Its stored values are deleted, and metrics that depend on it switch to their fallback."),
              ("Are these numbers medical advice?", "No. They are estimates from published formulas, shown with their confidence. Talk to your doctor about anything that concerns you.")],
        og_headline="Vitals your devices don't record.")


def build_health() -> Page:
    works = (f'<div class="works-with"><span class="chip">{icon("heart")}<span>Works with Apple Health<small>iPhone, iOS 17.6+</small></span></span>'
             f'<span class="chip">{icon("heart")}<span>Works with Health Connect<small>Android</small></span></span></div>')
    blocks = [
        split("Hub", "Every Health chart, mirrored on your phone.",
              "<p>With your permission Ayuvo reads the Apple Health or Health Connect data types you grant into a local database. Browse them by category and open any metric for charts, sources and units.</p>" + works,
              ["13 categories: activity, body, cycle tracking, hearing, heart, mental wellbeing, mobility, nutrition, respiratory, sleep, symptoms, vitals and more",
               "Day, week, month, six-month and year charts. Missing data is left empty, never smoothed or drawn as zero",
               "Sleep nights as a hypnogram, and blood pressure as paired ranges"],
              phone("browse", "Ayuvo Browse list of health categories")),
        split("Detail", "Know where each number came from.",
              "<p>Every metric has a detail screen with a headline value, range picker, Show All Data and Data Sources &amp; Access, so you can see which apps and devices supplied the readings.</p>",
              ["Add up to 12 favourites to Summary, each with a 7-day sparkline",
               "Units in kilograms or pounds, centimetres or feet and inches, millilitres or fluid ounces, mmol/L or mg/dL",
               "A goal line appears on charts when you have set a goal"],
              phone("heart-rate", "Ayuvo heart rate detail with day, week, month, six month and year ranges"), reverse=True),
        split("Derived", "What your devices don't record, estimated.",
              f"<p>Many bands record heart rate every minute but never a resting heart rate. Ayuvo fills gaps like that for {N_DERIVED} metrics, from heart rate zones and sleep regularity to cardio fitness, using published formulas. When Health has a value for that day, that value is shown instead.</p>",
              ["Badged Estimated by Ayuvo, with the method and reference on every metric",
               "One switch per metric in Settings, Derived Metrics",
               "Never written to Apple Health or Health Connect"],
              shot("derived-detail", "Ayuvo derived resting heart rate with the Estimated by Ayuvo badge",
                   '<div class="panel"><span class="kicker">Derived metrics</span><h3>Your device first, Ayuvo second</h3><p>A value from Apple Health or Health Connect wins, per metric and per day.</p>'
                   '<p><a class="link-arrow" href="/features/derived-metrics">See every metric</a></p></div>'),
              link=("/features/derived-metrics", "See how each metric is calculated")),
        split("Control", "Read a lot. Write back a little.",
              "<p>Ayuvo writes to Apple Health or Health Connect only what you log or record in Ayuvo: nutrition, weight, height, body fat, workouts with their calories and the route of a GPS workout. Fasting, water and Ayuvo's own estimates are never written.</p>",
              ["Revoke access at any time in Apple Health or Health Connect settings",
               "The mirror is never included in a cloud backup, and you can clear it from Settings",
               "Export the hub in Export All Data, and import it on iPhone or Android"],
              phone("summary", "Ayuvo Summary with Health favourites"), reverse=True),
    ]
    sends = sends_panel("What the Health hub sends, and to whom", "The mirror lives in a local database and is excluded from cloud backups.", [
        ("Nothing by default", "Reading Health data sends nothing anywhere. The database is stored on this device only."),
        ("Coach, with a toggle", "The coach can read your granted health types only while Let Coach use my health data is on. Then it sends what it requests to your provider or on-device model."),
        ("Export, when you tap it", "Health data leaves as a file only when you export it, to wherever you choose to save it."),
    ])
    return feature_page(
        "/features/health-data", "Apple Health & Health Connect Data Hub | Ayuvo",
        "Mirror Apple Health or Health Connect into a private local hub with day-to-year charts, sources and units. Health data stays on your phone and out of cloud backups.",
        "Health data", "Every Health chart. <em>One private hub.</em>",
        "Mirror the Apple Health or Health Connect data you grant into a local hub, with charts, sources and history for every metric.",
        "Health data", phones([("browse", "Ayuvo Browse health categories"), ("heart-rate", "Ayuvo heart rate chart")], "pair"), blocks, sends,
        [REL["derived"], REL["insights"], REL["coach"]], lcp_slug="browse", og_screens=["browse", "heart-rate"], compare=True,
        faqs=[("Which Android versions can use Health Connect?", "Health Connect is built into Android 14 and later, and is a Play Store app on Android 9 to 13. Ayuvo's Connect button opens the right screen."),
              ("Does Ayuvo change my Health data?", "Only by adding what you log or record in Ayuvo: nutrition, weight, height, body fat, workouts with their calories and GPS routes. Fasting, water and Ayuvo's estimates are never written."),
              ("Will Ayuvo see my old history?", "It reads the history you grant. On Android, without the special history permission, history starts 30 days before you first grant access, and Ayuvo says so on screen.")],
        og_headline="Every Health chart. One private hub.")


def build_records() -> Page:
    blocks = [
        split("Records", "Scan it once. <em>Find it forever.</em>",
              "<p>Add lab reports, prescriptions, consultation notes, discharge summaries, imaging reports, bills and more from the camera, a file, a scan or the share sheet. Ayuvo saves the original untouched, reads the text on your phone and finds the doctor, facility, dates and test values.</p>",
              ["Twelve document types, a timeline, list and grid, and one search across titles, people, terms, page text, notes and tags",
               "Results the report itself marks as low, high or abnormal show up as highlights, with the reference range the report printed",
               "Trends join the same test across reports, and tapping a value opens the source page"],
              phones([("records", "Ayuvo health records with important highlights")], "single")),
        split("Extraction", "You confirm every value.",
              "<p>Extracted items are suggestions you can confirm, edit or reject. Ayuvo does not interpret your results. It shows what the document says, and labels anything an AI summarised so you can check it against the original.</p>",
              ["Rules run on your phone. AI helps only where they left a gap, and only if you allow it",
               "Choose Local AI, Cloud AI on your own key, Ask me for each record, or Skip AI",
               "Each record states whether it was processed on this device, with online AI, or not by AI"],
              phone("record-extraction", "Ayuvo record detail with extracted information and health data points"), reverse=True),
        split("Sharing", "Share what you mean to, and nothing else.",
              "<p>Choose the records and pages, choose which details go in the summary, and black out name, address, phone number, patient ID and insurance ID. Redacted pages are shared as images so covered text cannot be recovered. You review every file before anything is sent.</p>",
              ["The coach can read records only after a separate consent that is off by default",
               "Patient name, address, phone and ID numbers are never included in what the coach receives",
               "Records are never part of the regular cloud backup. Export them yourself in Export All Data"],
              phone("records", "Ayuvo records screen")),
        split("Medications", "Reminders for the medicines you choose to add.",
              "<p>Add a medicine with its dose, form, food relation and schedule: daily times, weekdays, every few hours, or as needed. Ayuvo reminds you when a dose is due, and you mark it Taken, Skip or Snooze straight from the notification.</p>",
              ["Statuses of Scheduled, Due, Taken, Skipped, Missed and Snoozed, and a rolling 7-day adherence figure",
               "Adherence per medicine, where doses you chose to skip are left out, and a suggested reminder time when you usually take a dose more than an hour late",
               "When a lab report shows low haemoglobin or another linked value, a card sets it beside your intake of the related nutrient and suggests talking to your doctor",
               "Import a prescription from a record, then review every suggested medicine before it is created",
               "Stored on this device, excluded from cloud backups, and included in Export All Data"],
              phone("medications", "Ayuvo medications with taken, upcoming and missed doses"), reverse=True),
    ]
    sends = sends_panel("What records and medications send, and to whom", "Originals and medicines are stored on the phone. Some choices are yours to make.", [
        ("Document reading", "On-device text recognition and rules read each document on the phone. Skip AI and nothing is sent anywhere."),
        ("Cloud AI, if you choose it", "With Cloud AI, text read from a document goes to the provider you chose, with your key. Pages without readable text may be sent as images."),
        ("Sharing and coach", "A record leaves only when you share it, or when you ask the coach about it after turning the records consent on."),
    ])
    med_note = band("paper alt", '<div class="disclaimer"><p><strong>Not medical advice.</strong> Ayuvo helps you track the medicines you choose to add and reminds you when a dose is due. It does not give medical advice. If you miss a dose or are unsure about anything, ask your doctor or pharmacist.</p></div>')
    page = feature_page(
        "/features/records-and-medications", "Private Health Records & Medication Tracker | Ayuvo",
        "Scan lab reports and prescriptions, read on your phone, and get values, highlights and search. Add medication schedules with dose reminders. Stored on-device.",
        "Records & medications", "Your paperwork, <em>read on your phone.</em>",
        "Scan or import lab reports and prescriptions, keep the originals, and add medication schedules with dose reminders, all stored on this device.",
        "Records &amp; medications", phones([("records", "Ayuvo health records"), ("medications", "Ayuvo medications")], "pair"), blocks, sends,
        [REL["coach"], REL["ondevice"], REL["switch"]], lcp_slug="records", og_screens=["records", "medications"], compare=True,
        faqs=[("Does Ayuvo interpret my results?", "No. It shows what the document says and highlights values the report itself marks as outside its reference range. Talk to your clinician about what they mean."),
              ("Can Ayuvo tell me when to take a medicine?", "It reminds you at the times you set. It never suggests a dose, and it never tells you to start, stop, change or skip a medicine."),
              ("Does the coach see my records or medications?", "Only after you turn on the separate consent for each, and both are off by default.")],
        og_headline="Your paperwork, read on your phone.")
    # place the fixed medications disclaimer right after the feature blocks
    marker = '<section class="ink raised">'
    page.body = page.body.replace(marker, med_note + marker, 1)
    return page


def build_coach() -> Page:
    blocks = [
        split("Coach", "A coach that reads only what you allow.",
              "<p>Ask about food, sleep, activity, recovery, labs or your plan. Ayuvo lists the data sources for each chat, and the coach can use only the ones you have connected.</p>",
              ["Food diary, Health data, Medications and Records are separate sources. Health data, medications and records each need consent, and medications and records are off by default",
               "Each conversation can be turned down to fewer sources, never up to more",
               "A prompt gallery for nutrition, sleep, training, labs, medications and planning shows prompts only when the data behind them exists"],
              phone("coach", "Ayuvo coach with suggested prompts and blood report shortcuts")),
        split("Actions", "It can suggest a change. <em>You tap Confirm.</em>",
              "<p>The coach reads through the same actions that Siri and Shortcuts use, such as your goals, water, fasting status, Recovery, Health Age and the Daily Review. When a log would help, such as water or a set, it proposes it as a card in the chat, and nothing changes until you tap Confirm.</p>",
              ["It never proposes medication or goal changes, and no action can delete anything",
               "On-device models get no action tools, so they cannot propose changes",
               "The same rules as Siri and Shortcuts, checked the same way on iPhone and Android"],
              f'<div class="panel"><span class="kicker">One set of actions</span><h3>{N_ACTIONS} actions, every surface</h3><p>Siri, the Shortcuts app, Android shortcuts and the coach all call the same actions. <a class="link-arrow" href="/features/siri-and-shortcuts">See the actions</a></p></div>', reverse=True),
        split("In the chat", "Charts, files and history.",
              "<p>Answers arrive as Markdown with tables and charts. Attach photos, files, a record or a note, and the composer shows the exact text that will be sent before you send it.</p>",
              ["Nine chart types, and every number comes from your data, your message or an attached file. Missing days are left out, never drawn as zero",
               "PDFs and text files are read on the phone, with identity, contact and ID lines removed",
               "Pin, rename, duplicate, search and export chats as Markdown or JSON. Chats are stored on the device"],
              phones([("coach", "Ayuvo coach")], "single"), reverse=True),
        split("Rules", "Guardrails that hold for every model.",
              "<p>Whichever model answers, Coach describes patterns and suggests speaking to a clinician. It never diagnoses from readings, never suggests starting, stopping, changing or skipping a medicine, and never suggests a dose.</p>",
              ["Choose the model for a conversation, from your saved profiles or the models installed on the phone",
               "A chat pinned to a model never quietly falls back to a cloud model",
               "15 providers on your own key, or an on-device model, including MedGemma 1.5 4B, that keeps the chat on the phone"],
              f'<div class="panel"><span class="kicker">Keys and models</span><p>Keys are stored in the iOS Keychain or Android EncryptedSharedPreferences. Requests go from your phone straight to the provider you chose. <a class="link-arrow" href="/ai-providers">See providers</a></p></div>'),
    ]
    sends = sends_panel("What the coach sends, and to whom", "The coach exists to answer with your data, so it is worth being exact.", [
        ("Your provider", "The messages, and the slices of data the coach requests from sources you allowed, go from your phone to the provider you configured. Ayuvo has no server in between."),
        ("Your phone, if you prefer", "With an on-device model or Apple Intelligence, the conversation is processed on the phone and sent nowhere."),
        ("Nothing saved elsewhere", "Tool results are never saved into chat history, and deleting a chat deletes its attachments."),
    ])
    return feature_page(
        "/features/coach", "AI Health Coach on Your Own Data and Key | Ayuvo",
        "Ask an AI coach about your food, workouts, sleep, records and medications. You control each data source, and you can use your own key or a model on your phone.",
        "AI coach", "A coach that knows your day. <em>Only what you allow.</em>",
        "Ask about food, training, sleep or labs. The coach reads your diary and, with separate consent, your health data, medications and records.",
        "AI coach", phones([("coach", "Ayuvo coach chat")], "single"), blocks, sends,
        [REL["insights"], REL["shortcuts"], REL["ondevice"]], lcp_slug="coach", og_screens=["coach"], med=True,
        og_headline="A coach that reads only what you allow.")


def build_fasting() -> Page:
    blocks = [
        split("Fasting", "A timer that survives restarts.",
              "<p>Turn fasting on in Settings, pick a goal from 1 to 168 hours and start a fast. The timer keeps running when the app closes, history is editable, and a goal notification is optional.</p>",
              ["Off by default. Ayuvo never infers a fast from missing meals",
               "Summary shows a Fasting card only while a fast is active",
               "Quick Log widgets offer Start fast and End fast, and food logging pauses while a fast is active"],
              phone("summary", "Ayuvo Summary with Eat, Move and Drink rings")),
        split("Water", "A goal, a tap and a reminder.",
              "<p>Set a daily goal in millilitres or fluid ounces, log a glass in one tap or a custom amount, and add reminders. The Drink ring appears on Summary only when water tracking is on.</p>",
              ["Water progress on the Apple Watch and in widgets",
               "Hydration has its own charts from day to year",
               "Water entries travel inside your food diary export"],
              phone("nutrition", "Ayuvo nutrition diary with water"), reverse=True),
    ]
    sends = sends_panel("What fasting and water send, and to whom", "Both features are local.", [
        ("Nothing", "Timers, goals and history are stored on the phone and are never written to Apple Health or Health Connect."),
        ("Reminders", "Reminders are scheduled locally on the device."),
        ("Switching phones", "Water and fasting settings and fasting sessions move between iPhone and Android in Export All Data."),
    ])
    return feature_page(
        "/features/fasting-and-water", "Fasting Timer & Water Tracker | Ayuvo",
        "An optional fasting timer with goals from 1 to 168 hours and a water tracker with reminders and widgets. Both stay on your phone and are off until you turn them on.",
        "Fasting & water", "Timers that respect <em>your day.</em>",
        "An optional fasting timer and water tracker with goals, reminders and widgets. Off until you turn them on, and never written to Health.",
        "Fasting &amp; water", phones([("summary", "Ayuvo Summary")], "single"), blocks, sends,
        [REL["nutrition"], REL["health"], REL["switch"]], lcp_slug="summary", og_screens=["summary"], og_headline="Timers that respect your day.")


def build_switch() -> Page:
    steps = ('<div class="steps three">'
             '<div class="step"><div class="step-num">Step 01</div><h3>Export</h3><p>On the old phone, open Settings, Data &amp; Privacy, Backup &amp; Export and choose Export All Data. Ayuvo builds one zip.</p></div>'
             '<div class="step"><div class="step-num">Step 02</div><h3>Move the file</h3><p>Send the zip to the new phone any way you like: AirDrop, a cable, a cloud drive you already use, or a message to yourself.</p></div>'
             '<div class="step"><div class="step-num">Step 03</div><h3>Restore</h3><p>On the new phone choose Restore from a backup while setting Ayuvo up, or use Import All Data later. Then add your AI key and grant Health access.</p></div></div>')
    table = ('<div class="table-wrap"><table class="data-table"><thead><tr><th>Comes along</th><th>Stays behind on purpose</th></tr></thead><tbody>'
             "<tr><td>Profile, goals and calorie and macro targets</td><td>API keys</td></tr>"
             "<tr><td>Units and shared preferences</td><td>Notification switches and reminder times</td></tr>"
             "<tr><td>Weights, body fat, measurements and fasting sessions</td><td>Your AI provider and model choice</td></tr>"
             "<tr><td>Workouts, plans and saved exercises</td><td>Add-menu and quick-action layouts, and widgets</td></tr>"
             "<tr><td>Food diary and water</td><td>Meal and exercise photos</td></tr>"
             "<tr><td>Health data, medications, health records and coach chats</td><td>Onboarding state and Health sync switches</td></tr></tbody></table></div>")
    blocks = [
        split("Export", "One zip. Either platform.",
              "<p>Export All Data bundles your diary, health data, medications, health records, coach chats, settings and logs. Ayuvo on the other platform reads it, because the cross-platform part of the zip is written in one normalised format that both apps produce and read.</p>",
              ["Sections with nothing in them are left out",
               "Health Records files can be included or left out, since the zip can get large",
               "Merge only: importing twice adds nothing, and an import never deletes anything"],
              phone("export-data", "Ayuvo Export All Data sheet listing food diary, health data, medications, records and settings")),
        f'<div class="prose wide">{section_head("Steps", "Switch in three steps.")}{steps}</div>',
        f'<div class="prose wide"><h2>What moves, and what stays behind</h2><p>Some things should not travel. Keys, schedules and layouts differ between phones, so they are left out and you set them again in a minute.</p>{table}</div>',
    ]
    sends = sends_panel("What switching phones sends, and to whom", "There is no Ayuvo cloud in the middle. The file goes where you put it.", [
        ("A file you control", "Export All Data writes a zip to the place you choose. It leaves the phone only when you move it."),
        ("No keys inside", "API keys are never included, so the zip does not carry your provider access."),
        ("Google Drive is separate", "Android's optional Drive backup is a same-platform backup. Use the zip to change platforms."),
    ])
    return feature_page(
        "/features/switch-phones", "Move Health Data from iPhone to Android and Back | Ayuvo",
        "Export All Data on one phone, import it on iPhone or Android. Your profile, logs, workouts, health data, medications and records move with you. Keys stay behind.",
        "Switch phones", "Change phones. <em>Or platforms.</em>",
        "Export All Data on the old phone, import it on the new one, iPhone to Android or Android to iPhone. Your history comes with you.",
        "Switch phones", phones([("export-data", "Ayuvo Export All Data")], "single"), blocks, sends,
        [REL["privacy"], REL["health"], REL["source"]], lcp_slug="export-data", og_screens=["export-data"],
        faqs=[("Can I move from iPhone to Android?", "Yes. Export All Data on the iPhone, then use Restore from a backup during setup or Import All Data on the Android phone. It works the other way too."),
              ("What happens if I import twice?", "Nothing is duplicated. Existing entries are matched, new ones are added, and nothing is deleted."),
              ("Do I need to enter my API key again?", "Yes. Keys are never exported. Add your key on the new phone, or download an on-device model.")],
        og_headline="Change phones. Or platforms.")


def build_ondevice() -> Page:
    steps = ('<ol class="ticks steps-list">'
             "<li><span>Create a free Hugging Face account.</span></li>"
             "<li><span>Open the MedGemma page and accept Google's Health AI Developer Foundations terms.</span></li>"
             "<li><span>Create an access token with read access.</span></li>"
             "<li><span>Paste the token into Ayuvo's on-device models settings. It is sent only to Hugging Face, and only for gated downloads.</span></li>"
             f"<li><span>Tap Download. The file is about {MG_SIZE}, checked with a checksum, and can be deleted from the same screen.</span></li></ol>")
    blocks = [
        '<div class="prose wide"><h2>Six models. Zero servers.</h2>'
        "<p>Ayuvo can download a model once and run it on the phone itself. Pick one for Coach, Health Records or food analysis, and nothing you ask leaves the phone. "
        "A model appears in the pickers only when it is verified and your phone can run it. Smaller phones still see it listed, with the reason it is blocked.</p>"
        + catalog_table()
        + "<ul><li>Speech-to-text can run on the phone as well, with the Whisper Base model (about 150 MB) or the native recogniser.</li>"
        "<li>Apple Intelligence works as an on-device option on supported iPhones. It handles text, not images.</li></ul></div>",
        split("MedGemma", "MedGemma 1.5 4B, on your phone.",
              "<p>MedGemma is Google's open model family for medical text and images. It is built to understand medical text, high-dimensional imaging, medical documents and FHIR health records, and Google positions it as a privacy-preserving building block for health AI. "
              f"Ayuvo runs MedGemma 1.5 4B, a {MG_SIZE} LiteRT-LM build, fully on the device, with text and image input.</p>",
              ["Ask Coach about your own diary and records with the conversation processed on the phone",
               "Needs 8 GB of memory or more, and a 2,048-token context window, shorter than the 4,096 tokens of the other models",
               "Licensed under Google's Health AI Developer Foundations terms, not Apache-2.0 like Gemma 4 and Qwen3"],
              catalog_card(), reverse=True),
        f'<div class="prose wide"><h2>Getting MedGemma</h2><p>MedGemma is a gated download, so Google asks you to accept its terms first. It takes about five minutes.</p>{steps}</div>',
    ]
    sends = sends_panel("What on-device AI sends, and to whom", "Running a model is local. Getting one is a download.", [
        ("Nothing, when it answers", "A prompt to an on-device model is processed on the phone. There is no provider, key or server involved."),
        ("A download, once", "Tapping Download fetches the model file from Hugging Face as a plain request. Hugging Face sees your IP address. For MedGemma your access token goes with it, to Hugging Face only."),
        ("Your call, per chat", "A conversation pinned to an on-device model never quietly falls back to a cloud model."),
    ])
    return feature_page(
        "/features/on-device-ai", "On-Device AI for Health: MedGemma, Gemma, Qwen | Ayuvo",
        f"Run Gemma 4, Qwen3 or MedGemma 1.5 4B on your phone with Ayuvo. No key, no server. See sizes, memory needs and licences, and how the gated MedGemma download works.",
        "On-device AI", "Health AI that <em>stays on the phone.</em>",
        f"Download Gemma, Qwen or MedGemma once and ask questions with nothing to send. Ayuvo offers MedGemma 1.5 4B, Google's open medical model, as a {MG_SIZE} on-device download.",
        "On-device AI", catalog_card(), blocks, sends,
        [REL["coach"], REL["records"], REL["providers"]], med=True, og_screens=["coach"],
        faqs=[("What is MedGemma?", "MedGemma is Google's open model family for health AI, released in 4B and 27B sizes and built to understand medical text and images. Ayuvo runs MedGemma 1.5 4B on the device."),
              ("Which phones can run it?", "MedGemma 1.5 4B needs 8 GB of memory or more. Phones with less still list it, together with the reason it is blocked."),
              ("Why does MedGemma need a Hugging Face token?", "Google gates the download behind its Health AI Developer Foundations terms. Accept them on Hugging Face, add a free access token in Ayuvo, and the download works. The token is sent only to Hugging Face."),
              ("Is MedGemma a medical device?", "No. It is a research model whose output is preliminary and needs independent verification. Ayuvo never diagnoses or advises on doses.")],
        og_headline="Health AI that stays on the phone.")


def _fmt_num(v) -> str:
    return f"{v:g}"


def _table(head: list[str], rows: list[list[str]]) -> str:
    th = "".join(f"<th>{h}</th>" for h in head)
    tr = "".join("<tr>" + "".join(f"<td>{c}</td>" for c in r) + "</tr>" for r in rows)
    return f'<div class="table-wrap"><table class="data-table"><thead><tr>{th}</tr></thead><tbody>{tr}</tbody></table></div>'


def _methodology(key: str) -> str:
    """The in-app "How we calculate this" text, verbatim; its Limitations paragraph is rendered as a disclaimer."""
    m = INSIGHTS["methodology"][key]
    out = []
    for s in m["sections"]:
        if s["heading"] == "Limitations":
            out.append(f'<div class="disclaimer"><p><strong>Limitations.</strong> {s["body"]}</p></div>')
        else:
            out.append(f'<p><strong>{s["heading"]}.</strong> {s["body"]}</p>')
    return "".join(out)


RECOVERY_LABELS = {"hrv": "Heart rate variability", "resting_heart_rate": "Resting heart rate", "sleep": "Sleep",
                   "respiratory_rate": "Breathing rate", "blood_oxygen": "Blood oxygen"}


def build_insights() -> Page:
    rec, ha, dr = INSIGHTS["recovery"], INSIGHTS["health_age"], INSIGHTS["daily_review"]
    rec_rows = [[RECOVERY_LABELS.get(c["id"], c["id"]), f'{_fmt_num(c["weight"])}%'] for c in rec["components"]]
    # bands run high to low: 67–100, 34–66, 0–33
    mins = [b["min"] for b in rec["bands"]]
    bands = [[(f"{b['min']}–100" if i == 0 else f"{b['min']}–{mins[i - 1] - 1}"), b["label"], b["recommendation"]] for i, b in enumerate(rec["bands"])]
    ha_rows = [[m["label"], f'{_fmt_num(m["weight"])}%', f'±{_fmt_num(m["cap_years"])} years'] for m in ha["markers"]]
    area_rows = [[a["label"], f'{_fmt_num(a["weight"])}%'] for a in dr["areas"]]
    pat = INSIGHTS["patterns"]

    flow = {"title": "How Insights works", "steps": [
        {"label": "Your synced data", "sub": "Apple Health or Health Connect, plus the food, water, fasting and workouts you log"},
        {"label": "Your baselines", "sub": "A 60-day average and normal range for each metric"},
        {"label": "Scores and trends", "sub": "Recovery, Health Age, Daily Review, Trends and Patterns"},
        {"label": "On your screen", "sub": "Each with a How we calculate this sheet"}],
        "side": {"at": 2, "label": "Explain with AI", "sub": "Optional, only when you tap", "verb": "rephrases"}}
    diagram = f'<div class="flow-wrap">{flow_svg(flow, "Insights", "ins-flow")}</div>'

    hero_card = ('<div class="panel"><span class="kicker">Recovery bands</span><h3>A 0 to 100 morning score</h3>'
                 + _table(["Score", "Label", "Suggestion"], bands)
                 + f'<p>Weights: {", ".join(f"{r[0].lower()} {r[1]}" for r in rec_rows)}.</p></div>')

    blocks = [
        f'<div class="prose wide">{section_head("How it works", "Compared with you, not with other people.", "Every score is calculated on your phone from data already on it, with the same shared maths and test vectors on iPhone and Android. AI never computes a number.")}{diagram}</div>',
        split("Recovery", "A morning score from last night's signals.",
              "<p>Once last night's sleep has synced, Ayuvo compares your overnight heart rate variability, resting heart rate, sleep, breathing rate and blood oxygen with your own baselines and gives a score from 0 to 100, a label and a training suggestion.</p>"
              + _methodology("recovery"),
              [], '<div class="panel"><span class="kicker">What counts</span>' + _table(["Signal", "Weight"], rec_rows) + "</div>"),
        split("Ayuvo Health Age", "A direction to watch, <em>not a verdict</em>.",
              _methodology("health_age"),
              [], '<div class="panel"><span class="kicker">Markers</span>' + _table(["Marker", "Weight", "Limit"], ha_rows)
              + f'<p>Total difference limited to ±{_fmt_num(ha["total_cap_years"])} years. Every table cites its published source in the app.</p></div>', reverse=True),
        split("Daily Review", "A look back at your day.",
              _methodology("daily_review"),
              [], '<div class="panel"><span class="kicker">Day Score areas</span>' + _table(["Area", "Weight"], area_rows)
              + "<p>Only areas you track and logged count. An area you did not log never lowers the score.</p></div>"),
        split("Trends", "Is it moving, and which way?",
              _methodology("baselines"),
              ["Trends for sleep, resting heart rate, HRV, VO2 max, breathing rate, blood oxygen, steps, active energy, workouts, weight and body fat",
               "Each shows your baseline, normal range, today, the percentage change and the 28-day direction",
               "Lab values in Health Records have their own trends, joining the same test across reports"],
              '<div class="panel"><span class="kicker">Trend maths</span><h3>Plain and checkable</h3><p>Baseline: the average of the previous 60 days. Normal range: that average plus or minus one standard deviation. Trend: the least-squares slope of the last 28 days, as a percentage of your baseline per week.</p></div>',
              reverse=True),
        split("Patterns", "Associations in your own data.",
              _methodology("patterns"),
              [], f'<div class="panel"><span class="kicker">The test</span><h3>Two conditions, both required</h3><p>At least {pat["min_group"]} days in each group over {pat["window_days"]} days, a Welch t of {_fmt_num(pat["min_abs_t"])} or more and an effect size of {_fmt_num(pat["min_abs_d"])} or more.</p></div>'),
        split("Explain with AI", "Plain words, if you want them.",
              "<p>Tap Explain with AI on Recovery, Health Age or the Daily Review and your chosen model rephrases the result. It receives only the derived values, such as scores, labels and review items, never raw readings, dates or names. Every number in its answer is checked against those values, and anything that fails is replaced by the standard text.</p>",
              ["Runs only when you tap. There is no silent fallback from an on-device model to a cloud one",
               "The screen says which was used: on this device, or online with the provider you named",
               "Without AI set up, every score still works"],
              '<div class="panel"><span class="kicker">When scores update</span>' + _methodology("background") + "</div>", reverse=True),
        f'<div class="disclaimer"><p><strong>Not medical advice.</strong> {INSIGHTS["disclaimers"]["general"]} {INSIGHTS["disclaimers"]["health_age"]} Patterns: {INSIGHTS["disclaimers"]["patterns"]}</p></div>',
    ]
    sends = sends_panel("What Insights sends, and to whom", "The scores are local. Only one optional step can leave the phone.", [
        ("Nothing, to calculate", "Scores, trends and patterns are recalculated on the phone each time you open them. Nothing is stored or uploaded."),
        ("Explain with AI, on tap", "Derived values only, to the provider you configured, or to an on-device model that keeps them on the phone."),
        ("Notifications without values", "The optional Recovery and Daily Review notifications only say something is ready, and never show your numbers."),
    ])
    return feature_page(
        "/features/insights", "Recovery Score, Health Age & Health Trends | Ayuvo",
        "Morning Recovery, Ayuvo Health Age, a Daily Review, trends and patterns, calculated on your phone against your own 60-day baseline, with every method shown.",
        "Insights", "Your own baseline. <em>Not someone else's.</em>",
        "Recovery, Ayuvo Health Age, a Daily Review, trends and patterns, calculated on your phone from the data you already have, with the method behind every number.",
        "Insights", hero_card, blocks, sends,
        [REL["health"], REL["coach"], REL["shortcuts"]], og_screens=["summary", "heart-rate"],
        faqs=[("Is Ayuvo Health Age my biological age?", INSIGHTS["disclaimers"]["health_age"] + " Treat it as a direction to watch."),
              ("Why does it say Learning your baseline?", "A baseline needs at least 14 days with a reading, and Recovery needs 14 nights. Until then Ayuvo shows a count such as 9/14 instead of a score."),
              ("Why don't my iPhone and Android HRV match?", "Apple Health records HRV as SDNN and Health Connect as RMSSD. They are different measures, so Ayuvo keeps each with its own baseline and never compares them."),
              ("Does Ayuvo upload my health data to calculate scores?", "No. Scores are calculated on the phone. Only Explain with AI, when you tap it, sends derived values to the provider you chose."),
              ("Can I get my Recovery from Siri or Shortcuts?", 'Yes. Get Recovery, Get Health Age and Get Daily Review are actions in the Shortcuts app and on Android. <a href="/features/siri-and-shortcuts">See the actions</a>.')],
        og_headline="Your own baseline. Not someone else's.", ld=None)


SIRI_PHRASES = [
    ("Today's Nutrition", "Calories today in Ayuvo"),
    ("One Nutrient", "How much protein today in Ayuvo"),
    ("Log Food", "Log food in Ayuvo"),
    ("Log Water", "Log water in Ayuvo"),
    ("Log Weight", "Log my weight in Ayuvo"),
    ("Start Fast", "Start a fast in Ayuvo"),
    ("Open Ayuvo", "Open Insights in Ayuvo"),
    ("Quick Actions 1 to 3", "Quick action one in Ayuvo"),
]  # the App Shortcuts registered in ios/calorietracker/AppIntents/AyuvoSiriIntents.swift (Apple allows 10)

DOMAIN_LABELS = {"health": "Health data", "nutrition": "Nutrition", "water": "Water", "fasting": "Fasting", "body": "Body",
                 "workouts": "Workouts", "records": "Health records", "medications": "Medications", "goals": "Goals &amp; profile",
                 "search": "Search", "navigation": "Open", "insights": "Insights"}

SHORTCUT_RECIPES = [
    ("Protein check", "Get Nutrient (Protein, Today) → If Remaining &gt; 0 → Ask Ayuvo Coach"),
    ("Hydration nudge", "Get Water → If Remaining &gt; 500 → Show Notification"),
    ("Sleep", "Get Last Night's Sleep → If Hours &lt; 7 → Show Notification"),
    ("Recovery", "Get Recovery → If Score &lt; 34 → Show Notification"),
    ("Doses", "Get Next Dose → Mark Dose (Taken). Ayuvo asks you to confirm"),
    ("Averages", "Get Health Samples (Weight, Last 30 Days) → Calculate Statistics (Average)"),
]


def build_shortcuts() -> Page:
    acts = ACTIONS["actions"]
    by_domain: dict[str, list[dict]] = {}
    for a in acts:
        by_domain.setdefault(a["domain"], []).append(a)
    cells = "".join(
        f'<div class="panel"><span class="kicker">{DOMAIN_LABELS.get(d, d.title())}</span>'
        f'<p>{", ".join(a["title"] for a in items)}</p></div>'
        for d, items in by_domain.items())
    phrases = "".join(f"<li><span><strong>{t}.</strong> “{p}”</span></li>" for t, p in SIRI_PHRASES)
    recipes = "".join(f"<li><span><strong>{t}.</strong> {r}</span></li>" for t, r in SHORTCUT_RECIPES)

    flow = {"title": "One set of actions for every surface", "steps": [
        {"label": "You ask", "sub": "Siri, the Shortcuts app, an Android shortcut, an ayuvo:// link or the coach"},
        {"label": "One catalogue", "sub": f"{N_ACTIONS} typed actions, the same on iPhone and Android"},
        {"label": "Checked", "sub": "Parameters and ranges validated the same way everywhere"},
        {"label": "Runs on the phone", "sub": "Using the same data and screens as the app"}],
        "side": {"at": 2, "label": "Confirm", "sub": "Medication, goal, link and coach changes ask first", "verb": "asks"}}
    diagram = f'<div class="flow-wrap">{flow_svg(flow, "Actions", "act-flow")}</div>'

    hero_card = (f'<div class="panel"><span class="kicker">Say it to Siri</span><h3>Built-in phrases</h3><ul class="ticks">{phrases}</ul>'
                 f'<p>The other actions are in the Shortcuts app, and any shortcut you build can be run by its name.</p></div>')

    blocks = [
        f'<div class="prose wide">{section_head("How it works", "Ask anywhere. The same rules everywhere.", "Siri, Shortcuts, Android and the coach all call one catalogue of actions. The logic lives once in each app, so an answer from Siri matches what the app shows.")}{diagram}</div>',
        split("iPhone", "Siri and the Shortcuts app.",
              f"<p>Ayuvo registers App Shortcuts, so phrases such as “Log water in Ayuvo” work without any setup. All {N_ACTIONS} actions appear in the Shortcuts app with typed results, so one action's answer can feed the next.</p>",
              ["Health values are shown only after your iPhone is unlocked, and never in phrases or shortcut titles",
               "Ayuvo never asks for Apple Health access from Siri. If access is missing, it tells you how to grant it in the app",
               "A Focus filter can mute meal reminders while a Focus is on"],
              f'<div class="panel"><span class="kicker">Shortcut ideas</span><h3>Chain them together</h3><ul class="ticks">{recipes}</ul></div>'),
        split("Android", "Shortcuts, links and assistants.",
              "<p>Long-press the Ayuvo icon for Log water, Start fast, Today's summary and Log weight, plus shortcuts for actions you used recently. Every action can also be run from an app intent, and most from an ayuvo:// link, for automation apps and your own tools.</p>",
              ["Any change that comes from outside the app shows a confirm sheet first, because any app can send these links",
               "A change with no value, such as Log water without an amount, opens the logger in the app instead",
               "Answers open the matching screen, with the result shown at the bottom"],
              '<div class="panel"><span class="kicker">Google Assistant and Gemini</span><h3>Declared, not yet proven by voice</h3>'
              "<p>Ayuvo declares Android App Actions for opening features and for health, food and exercise requests. Google is replacing Assistant with Gemini on phones, and Gemini may not use these yet. We have not tested them by voice, so treat assistant voice commands as experimental. Launcher shortcuts and links work regardless.</p></div>",
              reverse=True),
        split("Coach", "The coach uses the same actions.",
              "<p>Ayuvo Coach reads through the same actions, and when a log would help it proposes one as a card in the chat. Nothing changes until you tap Confirm.</p>",
              ["It never proposes medication or goal changes", "On-device models get no action tools"],
              '<div class="panel"><span class="kicker">Safety rules</span><ul class="ticks">'
              "<li><span>No action can delete anything</span></li>"
              "<li><span>Medication actions can only mark one of today's doses taken, skipped or snoozed, and always ask first</span></li>"
              "<li><span>Nothing can create or edit a medication, change a dose or give medical advice</span></li>"
              "<li><span>Goal changes always ask first</span></li></ul></div>"),
        f'<div class="prose wide">{section_head("Catalogue", f"All {N_ACTIONS} actions.", "Get answers, log entries, search and open screens. Each is documented in the open-source repository.")}<div class="grid-3">{cells}</div></div>',
    ]
    sends = sends_panel("What actions send, and to whom", "Actions read and write the data on your phone.", [
        ("Nothing, from Ayuvo", "Actions work with the data stored on this device. There is no Ayuvo server."),
        ("One exception, after you confirm", "Log Food with a description sends that description to your AI provider, the same as typing it in the app."),
        ("Your assistant's own rules", "Siri and Android assistants handle your voice under Apple's or Google's settings, before Ayuvo sees the request."),
    ])
    return feature_page(
        "/features/siri-and-shortcuts", "Siri, Shortcuts & Android Assistant Actions | Ayuvo",
        f"Ask Siri, chain {N_ACTIONS} Ayuvo actions in the Shortcuts app, or use Android shortcuts and links. No action deletes, and medication changes always ask first.",
        "Siri & Shortcuts", "Ask Siri. <em>Build a Shortcut.</em>",
        f"Check today's protein, log water or get your Recovery with your voice, the Shortcuts app, an Android shortcut or a link: {N_ACTIONS} actions, one set of rules.",
        "Siri &amp; Shortcuts", hero_card, blocks, sends,
        [REL["insights"], REL["coach"], REL["nutrition"]], og_screens=["summary", "coach"],
        faqs=[("Does Ayuvo work with Gemini?", "Ayuvo declares Android App Actions, which Google Assistant uses. Google is moving phones from Assistant to Gemini, and Gemini may not use them yet. Voice commands have not been tested, so launcher shortcuts and ayuvo:// links are the reliable way on Android today."),
              ("Can Siri read my health data on a locked iPhone?", "No. Ayuvo shows health values only after the iPhone is unlocked."),
              ("Can a shortcut delete my data or change my medication?", "No. There are no delete actions, and medication actions can only mark one of today's doses, after you confirm."),
              ("Why are only some actions spoken without setup?", "Apple allows 10 App Shortcuts per app. Every other action is in the Shortcuts app, and a shortcut you build can be run by saying its name.")],
        og_headline="Ask Siri. Build a Shortcut.")


def build_privacy_first() -> Page:
    principles = (
        '<div class="grid-2">'
        '<div class="panel"><span class="kicker">01 · Stored here</span><h3>On this device</h3><p>Your profile, diary, workouts, health data, records, medications and coach chats are stored in the app on your phone. Ayuvo has no account and no server, so there is nothing of yours to breach on our side.</p></div>'
        '<div class="panel"><span class="kicker">02 · Your choice</span><h3>You pick every destination</h3><p>Data leaves only when you act, and only to a service you chose: your AI provider, a barcode database, a file host or your own drive. Choose an on-device model and the AI step stays on the phone too.</p></div>'
        '<div class="panel"><span class="kicker">03 · Consent</span><h3>Visible, per source</h3><p>Health data, medications and records each have their own switch before the coach can read them. Medications and records start off. You can turn a source off between messages.</p></div>'
        '<div class="panel"><span class="kicker">04 · Verifiable</span><h3>Open to inspection</h3><p>No analytics, crash-reporting or advertising SDKs, and no first-party endpoints. The code is open source so you can check it. This website sets no cookies and loads nothing from third parties.</p></div></div>')
    rows = [
        ("AI provider (your key)", "Photos, text, coach messages and the context you ask for. Health data, medications and records only with their switches on", "The provider you configured", "When you analyse or ask"),
        ("Explain with AI (Insights)", "Derived scores, labels and review items only: no raw readings, dates or names", "The provider you configured, or an on-device model", "When you tap Explain with AI"),
        ("On-device model", "Nothing", "Stays on the phone", "Always"),
        ("Derived metrics", "Nothing. Estimates are calculated on the phone and never written to Health", "Stays on the phone", "Always"),
        ("GPS workout", "The workout and its route", "Apple Health or Health Connect, on the phone", "When you finish a GPS workout"),
        ("Route map", "Requests for map images of the area shown, not your route", "Apple Maps (iPhone) or OpenStreetMap (Android)", "When a route map is on screen"),
        ("Siri, Shortcuts, Android shortcuts", "Ayuvo adds nothing, except Log Food with a description, which goes to your AI provider after you confirm. Voice requests are handled by Siri or your Android assistant under their own settings", "The provider you configured", "When you run that action"),
        ("Speech to text", "An audio clip, only for a cloud provider", "The provider you chose", "Voice input, if you picked a cloud provider"),
        ("Barcode", "The barcode number", "Open Food Facts", "When you scan"),
        ("Exercise images", "A plain image request", "GitHub (raw.githubusercontent.com)", "When you browse the library"),
        ("Model download", "A plain file request, plus your access token for gated models", "Hugging Face", "When you tap Download"),
        ("Sharing a record", "The pages and details you choose", "The app you pick", "When you tap Share"),
        ("Export All Data", "A zip file", "Where you save it", "When you tap Export"),
        ("Google Drive backup (Android)", "The app backup archive, never health data or medications", "Your own Drive app folder", "If you turn it on"),
    ]
    tr = "".join(f"<tr><td><strong>{a}</strong></td><td>{b}</td><td>{c}</td><td>{d}</td></tr>" for a, b, c, d in rows)
    table = ('<div class="table-wrap"><table class="data-table"><thead><tr><th>Flow</th><th>What is sent</th><th>To</th><th>When</th></tr></thead>'
             f"<tbody>{tr}</tbody></table></div>")
    body = (
        '<header class="hero compact"><div class="container">' + crumbs_html([], "Private by design")
        + f'<div class="hero-center">{eyebrow("Privacy")}<h1 class="hero-title">Your health. Your device. <em>Your call.</em></h1>'
        '<p class="hero-lede">Ayuvo is built so your health data stays where you keep it: on your phone. No account, no server, no ads, no analytics. Here is exactly what is stored, what can leave, and when.</p>'
        + store_buttons("hero") + "</div></div></header>"
        + privacy_band()
        + band("paper", section_head("Principles", "Four promises, each one checkable.", num="01") + principles)
        + band("ink raised", section_head("Data flows", "What can leave your phone, and when.",
                                           "Ayuvo makes no network request on its own behalf. Each flow below happens because you triggered it, and it goes from your phone to the service named.", "02") + table
               + '<p class="cta-note">This is a summary. The <a class="link-arrow" href="/privacy">full Privacy Policy</a> is the authority.</p>')
        + band("paper", split(
            "Choose how private", "Two ways to keep even the AI step yours.",
            "<p>Bring your own key and requests go straight from your phone to the provider you trust, with no Ayuvo server in between. Or run a model on the phone, including MedGemma 1.5 4B, and the conversation never goes anywhere.</p>",
            ["15 providers, keys kept in the iOS Keychain or Android EncryptedSharedPreferences", "Gemma 4, Qwen3 and MedGemma on the device, and Apple Intelligence on supported iPhones"],
            media=catalog_card(), link=("/features/on-device-ai", "See on-device AI"), reverse=True) + MEDGEMMA_DISCLAIMER)
        + band("ink", split(
            "Your data, portable", "Export it, move it, delete it.",
            "<p>Export All Data gives you one zip you can read, keep and import on iPhone or Android. Delete All Data wipes the app's storage on the device. API keys are never in an export.</p>",
            ["Revoke Apple Health or Health Connect access at any time", "Health data, medications and records are excluded from cloud backups"],
            media=phone("export-data", "Ayuvo Export All Data sheet"), link=("/features/switch-phones", "See how switching works")))
        + cta_section("Private, <em>on purpose.</em>")
    )
    return Page(path="/privacy-first", title="Private by Design: How Ayuvo Protects Your Health Data",
                description="No account, no server, no ads, no analytics. See what Ayuvo stores on your phone, exactly what can leave it and when, and how to run health AI on-device.",
                body=body, crumb="Private by design", og_headline="Your health. Your device. Your call.", og_screens=["settings", "records"],
                og_alt="Ayuvo is private by design: your health data stays on your phone.")


def build_providers() -> Page:
    provs = [("Google Gemini", "https://policies.google.com/privacy"), ("OpenAI", "https://openai.com/policies/privacy-policy"),
             ("Anthropic", "https://www.anthropic.com/legal/privacy"), ("xAI", "https://x.ai/legal/privacy-policy"),
             ("OpenRouter", "https://openrouter.ai/privacy"), ("Together AI", "https://www.together.ai/privacy"),
             ("Groq", "https://groq.com/privacy-policy/"), ("Hugging Face", "https://huggingface.co/privacy"),
             ("Fireworks AI", "https://fireworks.ai/privacy-policy"), ("DeepInfra", "https://deepinfra.com/privacy"),
             ("Mistral", "https://mistral.ai/terms/#privacy-policy"), ("DeepSeek", "https://www.deepseek.com/"),
             ("Cerebras", "https://www.cerebras.ai/privacy-policy"), ("Ollama", None)]
    cells = "".join(
        f'<div class="panel"><h3>{n}</h3><p>{"<a class=" + chr(34) + "link-arrow" + chr(34) + " href=" + chr(34) + u + chr(34) + " rel=" + chr(34) + "noopener" + chr(34) + ">Privacy policy</a>" if u else "Runs on your own computer or network."}</p></div>'
        for n, u in provs)
    cells += '<div class="panel"><h3>Any OpenAI-compatible endpoint</h3><p>Point Ayuvo at a server you run or trust.</p></div>'
    body = (
        '<header class="hero compact"><div class="container">' + crumbs_html([], "AI providers")
        + f'<div class="hero-center">{eyebrow("AI providers")}<h1 class="hero-title">Bring your own key. <em>Keep your choice.</em></h1>'
        '<p class="hero-lede">Ayuvo has no AI of its own to sell you. Use the provider you already trust, with your own key, or run a model on the phone. Requests go from your phone straight to the provider.</p></div></div></header>'
        + privacy_band()
        + band("paper", section_head("Providers", "15 ways to power the coach and the scanner.", "Each provider's own privacy policy governs what it does with a request.", "01") + f'<div class="grid-3">{cells}</div>')
        + band("ink raised", split(
            "How it works", "Your key stays in the phone's secure storage.",
            "<p>Add a key once. It is kept in the iOS Keychain or Android EncryptedSharedPreferences, is never included in a backup or export, and is sent only to the provider that issued it, as part of your request.</p>",
            ["Set separate models for photo analysis, text questions and fallbacks",
             "Speech to text can be native, on-device Whisper Base, or a cloud provider such as Deepgram, AssemblyAI, Gemini, OpenAI, Groq or Mistral",
             "Ollama on your own network is supported, with plain HTTP allowed only for local addresses"],
            media=f'<div class="panel"><span class="kicker">Prefer no provider at all?</span><h3>Run it on the phone</h3><p>Gemma 4, Qwen3 and other models run on-device after a one-time download.</p><p><a class="link-arrow" href="/features/on-device-ai">See on-device AI</a></p></div>'))
        + cta_section()
    )
    return Page(path="/ai-providers", title="Bring Your Own AI Key: 15 Providers Supported | Ayuvo",
                description="Use Google Gemini, OpenAI, Anthropic, xAI, Mistral, Groq and more with your own key, or run a model on-device. Ayuvo has no AI servers of its own.",
                body=body, crumb="AI providers", og_headline="Bring your own key. Keep your choice.", og_screens=["coach"],
                og_alt="Ayuvo works with 15 AI providers on your own key")


def build_open_source() -> Page:
    tree = ('<pre class="tree"><b>ayuvo/</b>\n'
            "├── <b>ios/</b>          SwiftUI app, widgets, watch app, share extension, tests\n"
            "├── <b>android/</b>      Kotlin + Jetpack Compose app, tests\n"
            "├── <b>shared/</b>       exercise dataset, health registry and schema, contracts\n"
            "├── <b>docs/</b>         health data, records, medications, portable data, AI models\n"
            "├── <b>local-models/</b> on-device model catalogue and notices\n"
            "├── <b>web/</b>          this website (static, Firebase Hosting)\n"
            "├── <b>brand/</b>        mark, tints, fonts\n"
            "└── <b>scripts/</b>      brand and website tooling</pre>")
    body = (
        '<header class="hero compact"><div class="container">' + crumbs_html([], "Open source")
        + f'<div class="hero-center">{eyebrow("Open source")}<h1 class="hero-title">Open source, <em>so you can check.</em></h1>'
        '<p class="hero-lede">Ayuvo is MIT-licensed. Read how it stores your data, build it yourself, and help make it better on iPhone, Android or the web.</p>'
        f'<div class="cta-group"><a class="gh-btn" href="{GITHUB}" rel="noopener">{icon("github")}<span>View on GitHub</span></a></div>'
        '<p class="cta-note">github.com/astechtic/ayuvo · MIT licence</p></div></div></header>'
        + privacy_band()
        + band("paper", split("What is inside", "Two native apps and shared contracts.",
                              "<p>The iPhone app is SwiftUI and the Android app is Jetpack Compose. Behaviour that must match, such as the health metric registry, export formats and AI routing, lives in shared contracts with test vectors that both apps run, so an iPhone export imports on Android and back.</p>",
                              ["Docs explain the formats: health data, records, medications, portable data, AI models",
                               "An on-device model catalogue pins every download to a checksum",
                               "Everything runs without an Ayuvo server, because there isn't one"], tree))
        + band("ink raised", section_head("Contribute", "Ways to help.", num="01") + (
            '<div class="grid-3">'
            '<div class="panel"><span class="kicker">Build</span><h3>Run the apps</h3><p>iOS needs Xcode 16 or later. Android needs Android Studio and JDK 17. The README has the build commands for both.</p></div>'
            '<div class="panel"><span class="kicker">Improve</span><h3>Fix, translate, document</h3><p>Add a language, improve exercise data, extend the health metric registry, tighten docs or polish this website.</p></div>'
            '<div class="panel"><span class="kicker">Method</span><h3>Contract first</h3><p>Change the shared contract and its test vectors first, then both platforms in the same pull request.</p></div>'
            '<div class="panel"><span class="kicker">Discuss</span><h3>Open an issue</h3><p>Bugs, ideas and questions are welcome as GitHub issues. See CONTRIBUTING.md for the checklist.</p></div>'
            '<div class="panel"><span class="kicker">Security</span><h3>Report privately</h3>'
            f'<p>Please do not open a public issue for a vulnerability. Email <a class="link-arrow" href="mailto:{EMAIL}?subject=Security">{EMAIL}</a> with the subject Security. See SECURITY.md.</p></div>'
            '<div class="panel"><span class="kicker">Licence</span><h3>MIT</h3><p>Use it, fork it and ship your own, keeping the licence notice. Third-party credits are in THIRD_PARTY_NOTICES.md.</p></div></div>'))
        + band("paper", split("Credits", "Inspired by Fud AI.",
                              f'<p>Ayuvo is inspired by <a href="{FUD_AI}" rel="noopener">Fud AI</a> by Apoorv Darshan, an open-source calorie tracker, and builds on its MIT-licensed code. The original copyright notice is retained in the repository and shown in the app under Settings, Legal, Licenses.</p>'
                              "<p>Exercise data comes from the open exercises-dataset, and product data from Open Food Facts under the Open Database Licence.</p>",
                              [], f'<div class="repo"><a class="gh-btn" href="{FUD_AI}" rel="noopener">{icon("github")}<span>Fud AI on GitHub</span></a></div>', reverse=True))
        + cta_section("Read it. <em>Then trust it.</em>")
    )
    ld = [{"@context": "https://schema.org", "@type": "SoftwareSourceCode", "@id": f"{BASE}/open-source#code", "name": "Ayuvo source code",
           "url": GITHUB, "codeRepository": GITHUB, "description": "Source code of the Ayuvo iPhone and Android apps, under the MIT licence.",
           "license": "https://opensource.org/licenses/MIT", "programmingLanguage": ["Swift", "Kotlin"],
           "runtimePlatform": ["iOS", "Android"], "author": {"@id": f"{BASE}/#organization"}}]
    return Page(path="/open-source", title="Open Source Health App (MIT) | Ayuvo on GitHub",
                description="Ayuvo is an MIT-licensed health app for iPhone and Android. Read the code, build it, and contribute code, translations, exercise data or docs on GitHub.",
                body=body, kind="source", crumb="Open source", ld=ld, main_entity=f"{BASE}/open-source#code", og_headline="Open source, so you can check.", og_screens=["settings"],
                og_alt="Ayuvo is open source under the MIT licence on GitHub")


def build_download() -> Page:
    from site_lib import catalog_models, ram_label
    ms = catalog_models()
    min_ram = ram_label(min(m["memoryPolicy"]["minimumPhysicalMemoryBytes"] for m in ms))
    max_ram = ram_label(max(m["memoryPolicy"]["minimumPhysicalMemoryBytes"] for m in ms))
    min_size = gib(min(m["artifact"]["sizeBytes"] for m in ms))
    max_size = gib(max(m["artifact"]["sizeBytes"] for m in ms))
    body = (
        '<header class="hero compact"><div class="container">' + crumbs_html([], "Download")
        + f'<div class="hero-center">{eyebrow("Download")}<h1 class="hero-title">Get Ayuvo for <em>iPhone and Android.</em></h1>'
        '<p class="hero-lede">Free, with no account. Bring an AI key or run a model on the phone, or skip AI entirely and use Ayuvo as a private diary.</p>'
        + store_buttons("hero") + '<p class="cta-note">Not on the stores yet? You can build it from source today.</p></div></div></header>'
        + privacy_band()
        + band("paper", section_head("Requirements", "What you need.", num="01") + (
            '<div class="grid-3">'
            f'<div class="panel"><span class="kicker">{icon("apple")} iPhone</span><h3>iOS 17.6 or later</h3><p>Apple Watch is optional. Apple Health works on iPhone. Apple Intelligence needs a supported iPhone.</p></div>'
            f'<div class="panel"><span class="kicker">{icon("play")} Android</span><h3>Android 8.0 or later</h3><p>Health Connect is built into Android 14 and later, and a Play Store app on Android 9 to 13.</p></div>'
            f'<div class="panel"><span class="kicker">{icon("cpu")} On-device AI</span><h3>Memory and space</h3><p>On-device models need {min_ram} to {max_ram} of memory and {min_size} to {max_size} of storage, depending on the model. See the <a href="/features/on-device-ai">full list</a>.</p></div></div>'
            f'<div class="works-with"><span class="chip">{icon("heart")}<span>Works with Apple Health<small>iPhone</small></span></span><span class="chip">{icon("heart")}<span>Works with Health Connect<small>Android</small></span></span></div>'))
        + band("ink raised", split("From source", "Build it yourself.",
                                   f"<p>Ayuvo is open source. Clone the repository, follow the README to build the iPhone or Android app, and run it on your own device.</p>",
                                   ["No account needed to build or run it", "Read exactly what is stored and sent"],
                                   f'<div class="repo"><a class="gh-btn" href="{GITHUB}" rel="noopener">{icon("github")}<span>Get the source</span></a><code>git clone {GITHUB}.git</code></div>',
                                   link=("/open-source", "Contribution guide")))
        + band("paper", split("Switching?", "Bring your history with you.",
                              "<p>Already using Ayuvo on another phone? Export All Data there and choose Restore from a backup when you set it up here.</p>",
                              [], phone("export-data", "Ayuvo Export All Data sheet"), link=("/features/switch-phones", "See how switching works"), reverse=True))
    )
    return Page(path="/download", title="Download Ayuvo for iPhone and Android",
                description="Get Ayuvo, the free private health app for iPhone (iOS 17.6+) and Android (8.0+). No account. Or build it from source on GitHub.",
                body=body, crumb="Download", og_headline="Get Ayuvo for iPhone and Android.", og_screens=["summary", "coach"],
                og_alt="Download Ayuvo for iPhone and Android")


def build_about() -> Page:
    body = (
        '<header class="hero compact"><div class="container">' + crumbs_html([], "About")
        + f'<div class="hero-center">{eyebrow("About")}<h1 class="hero-title">Private by design, <em>and open to check.</em></h1>'
        '<p class="hero-lede">Ayuvo is a free health app for iPhone and Android, published by Yaara Tech and released as open source. This page says who is behind it, how it is built and how to reach us.</p></div></div></header>'
        + privacy_band()
        + band("paper", split(
            "Who", "Published by <em>Yaara Tech</em>.",
            f'<p>Yaara Tech publishes Ayuvo. The source code is public at <a href="{GITHUB}" rel="noopener">github.com/astechtic/ayuvo</a> under the MIT licence, so what the app stores and sends can be read rather than taken on trust.</p>'
            f'<p>Questions, corrections and security reports go to <a href="mailto:{EMAIL}">{EMAIL}</a>. There is no account, and there are no ads or analytics, so there is no profile of you to look at either.</p>',
            ["Free, with no subscription, credits or in-app purchases", "Built natively: SwiftUI on iPhone, Jetpack Compose on Android", "Built on earlier open-source work, credited on the open-source page with its licence notice kept"],
            f'<div class="repo"><a class="gh-btn" href="{GITHUB}" rel="noopener">{icon("github")}<span>View on GitHub</span></a><code>MIT licence</code></div>',
            link=("/open-source", "How to contribute")))
        + band("ink raised", section_head("Principles", "How Ayuvo is built.", num="01") + (
            '<div class="grid-3">'
            '<div class="panel"><span class="kicker">Local first</span><h3>Your data stays with you</h3><p>Your diary, records, medications and chats are stored on your phone. Data leaves it only when you act, and only to a service you chose. <a class="link-arrow" href="/privacy-first">See every data flow</a></p></div>'
            '<div class="panel"><span class="kicker">Honest claims</span><h3>We say what is not covered</h3><p>Ayuvo is not a medical device and does not give medical advice. We are precise about what can leave the phone and when, and our comparison pages say where other products are stronger.</p></div>'
            '<div class="panel"><span class="kicker">Checkable</span><h3>Sources on every comparison</h3><p>Facts about other products come from their own public pages, with the date we checked them. <a class="link-arrow" href="/compare">See the comparisons</a></p></div></div>'))
        + band("paper", '<div class="prose wide"><h2>Trademarks and independence</h2>'
               "<p>Apple, iPhone, Apple Health and Apple Watch are trademarks of Apple Inc. Google, Android and Health Connect are trademarks of Google. "
               "Other product names on this site belong to their owners. Ayuvo is an independent app and is not affiliated with or endorsed by any of them.</p></div>")
        + cta_section()
    )
    return Page(path="/about", title="About Ayuvo and Yaara Tech: Open-Source Health App",
                description="Ayuvo is a free, private, open-source health app for iPhone and Android, published by Yaara Tech. Who we are, how it is built and how to contact us.",
                body=body, kind="about", crumb="About", og_headline="Private by design, and open to check.", og_screens=["summary", "settings"],
                og_alt="About Ayuvo: a private, open-source health app for iPhone and Android")


# ---------------------------------------------------------------------------
# legal + 404
# ---------------------------------------------------------------------------


def legal_pages() -> list[Page]:
    out = []
    for f in sorted((WEB / "_src" / "pages").glob("*.html")):
        text = f.read_text(encoding="utf-8")
        m = re.match(r"<!--meta\s*(\{.*?\})\s*-->\s*", text, re.S)
        meta = json.loads(m.group(1))
        body = text[m.end():]
        out.append(Page(path=meta["path"], title=meta["title"], description=meta["description"], body=body, kind="legal", legal=True,
                        modified=meta["modified"], og_headline=meta.get("crumb") or meta["title"].split(" — ")[0], og_screens=["settings"],
                        og_alt=meta["title"], crumb=meta.get("crumb") or meta["title"].split(" — ")[0]))
    return out


def build_404() -> Page:
    links = "".join(f'<li><a href="{u}">{t}</a></li>' for u, t in [("/features", "Features"), ("/privacy-first", "Privacy"), ("/features/on-device-ai", "On-device AI"), ("/open-source", "Open source"), ("/support", "Support")])
    body = (f'<div class="notfound"><div><img src="{asset("/assets/brand/ayuvo-mark.svg")}" alt="" width="84" height="84"><h1>Page not found</h1>'
            f'<p>The page you were looking for does not exist. Try one of these instead.</p><ul>{links}</ul>'
            '<p><a class="store-btn" href="/">Return home</a></p></div></div>')
    return Page(path="/404", title="Page not found | Ayuvo", description="This page does not exist. Return to the Ayuvo home page.", body=body,
                robots="noindex", in_sitemap=False, kind="page")


def all_pages() -> list[Page]:
    new = [build_insights(), build_shortcuts()]
    for p in new:
        p.modified = "2026-09-28"  # first published; later edits are dated by lastmod.json
    derived = build_derived()
    derived.modified = "2026-09-30"
    pages = [build_home(), build_features(), build_nutrition(), build_workouts(), build_health(), derived, *new, build_records(), build_coach(),
             build_fasting(), build_switch(), build_ondevice(), build_privacy_first(), build_providers(), build_open_source(), build_download()]
    pages += compare_pages()
    pages += nutrient_pages()
    pages.append(build_about())
    pages += legal_pages()
    pages.append(build_404())
    return pages
