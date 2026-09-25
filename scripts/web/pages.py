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

from site_lib import (
    BASE, EMAIL, FUD_AI, GITHUB, MEDGEMMA_DISCLAIMER, STORES, WEB, Page, catalog_card, catalog_table, cta_section,
    asset, eyebrow, faq_section, gib, icon, medgemma, phone, phones, privacy_band, section_head, store_buttons,
)

MG = medgemma()
MG_SIZE = gib(MG["artifact"]["sizeBytes"])

# ---------------------------------------------------------------------------
# layout helpers
# ---------------------------------------------------------------------------


def crumbs_html(trail: list[tuple[str, str]], here: str) -> str:
    items = "".join(f'<li><a href="{u}">{n}</a></li>' for n, u in [("Home", "/")] + trail)
    return f'<ol class="crumbs">{items}<li aria-current="page">{here}</li></ol>'


def hero_split(trail, here, eyebrow_text, h1, lede, visual, ctas=True) -> str:
    cta = store_buttons("hero") if ctas else ""
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
                 faqs=(), og_headline="", og_screens=(), og_alt="", lcp_slug=None, med=False, ld=None) -> Page:
    body = hero_split([("Features", "/features")], crumb, eyebrow_text, h1, lede, hero_visual)
    body += privacy_band()
    body += band("paper", "".join(blocks))
    body += sends
    if med:
        body += band("paper alt", MEDGEMMA_DISCLAIMER)
    body += related(related_items)
    if faqs:
        body += faq_section(list(faqs), heading="Questions <em>about this feature</em>.")
    body += cta_section()
    return Page(path=path, title=title, description=description, body=body, crumbs=[("Features", "/features")], crumb=crumb,
                og_headline=og_headline or h1, og_screens=list(og_screens), og_alt=og_alt or title,
                lcp=f"/assets/screens/{lcp_slug}.webp" if lcp_slug else None, faqs=list(faqs), ld=ld or [])


REL = {
    "nutrition": ("/features/nutrition", "Nutrition", "Photo, barcode and voice logging with 30+ nutrients."),
    "workouts": ("/features/workouts", "Workouts", "Sets, reps, weight, RPE and a 1,300+ exercise library."),
    "health": ("/features/health-data", "Health data", "Apple Health and Health Connect in one local hub."),
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
    ("What does the Health data hub do with Apple Health or Health Connect?", "With your permission it keeps a local mirror of the data types you grant so Ayuvo can show charts and history. It writes only nutrition, body measurements and calculated workout calories that you log. You can revoke access any time in Health or Health Connect settings."),
    ("Does the coach see my health data?", 'Only if you allow it. Health data, medications and health records each have their own consent, and medications and records are off by default. When a source is on, the coach can request summaries of it and sends them to the AI provider you chose, or to an on-device model that keeps them on the phone.'),
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
        bento_tile("", "dumbbell", "Workouts", "Plan the week, log the set.", "Sets, reps, weight and RPE, with a 1,300+ exercise library.", "/features/workouts", "workouts", "Ayuvo workout diary with calculated calorie burn"),
        bento_tile("", "chart", "Health data", "Every chart, one hub.", "Apple Health and Health Connect, mirrored on your phone.", "/features/health-data", "heart-rate", "Ayuvo heart rate chart with day, week, month and year ranges"),
        bento_tile("", "doc", "Records", "Documents that read themselves.", "Scan a report. Values and highlights appear, processed on your phone.", "/features/records-and-medications", "records", "Ayuvo health records with important highlights"),
        bento_tile("", "chat", "AI coach", "Ask about your own day.", "Consent for every data source, on your key or on your phone.", "/features/coach", "coach", "Ayuvo coach chat with suggested prompts"),
        bento_tile("", "timer", "Fasting & water", "Timers that stay optional.", "Off by default. Goals, reminders and widgets when you want them.", "/features/fasting-and-water", "summary", "Ayuvo Summary with Eat, Move and Drink rings"),
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
        '<p class="hero-lede">Ayuvo brings nutrition, workouts, health records, medications and your Apple Health or Health Connect data together in one private app. '
        "Everything is kept on your phone. Ask an AI coach only if you want to, with a key you bring or a model that runs on the phone itself.</p>"
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
        "It writes back only nutrition, body measurements and calculated workout calories that you log.</p>"
        f'<div class="works-with"><span class="chip">{icon("heart")}<span>Works with Apple Health<small>iPhone</small></span></span>'
        f'<span class="chip">{icon("heart")}<span>Works with Health Connect<small>Android</small></span></span></div>',
        ["13 categories, from activity and sleep to cycle tracking and vitals",
         "Pin up to 12 favourites to Summary, with 7-day sparklines",
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

    body = hero + privacy_band() + features + privacy + records + ondevice + worksw + switch + oss + steps
    body += faq_section(HOME_FAQ, "03") + cta_section()
    return Page(path="/", title="Ayuvo: Private AI Health App for iPhone & Android",
                description="Nutrition, workouts, health records, medications and Apple Health or Health Connect data in one private, open-source app. No account, no ads, no analytics.",
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
        '<p class="hero-lede">Nutrition, workouts, health data, records, medications, fasting, water and an AI coach, each built to keep working on your phone with no account.</p></div></div></header>'
        + privacy_band()
        + band("paper", section_head("Core", "The parts of your health.") + bento())
        + band("ink", section_head("Around the app", "On your wrist, your Home Screen and your language.", "The same data, wherever you look for it.")
               + '<div class="grid-3">'
                 f'<div class="panel"><span class="kicker">{icon("watch")} Apple Watch</span><h3>Watch app and complications</h3><p>Calories, macros and water from your wrist, with water logging on the Watch.</p></div>'
                 f'<div class="panel"><span class="kicker">{icon("chart")} Widgets</span><h3>Today, My Metrics, Quick Log</h3><p>Home Screen widgets on iPhone and Android. They never invent data, and every action opens the app. Widget settings stay on the device.</p></div>'
                 f'<div class="panel"><span class="kicker">{icon("sparkle")} Shortcuts</span><h3>Siri Shortcuts and Share Extension</h3><p>Log food and weight from Siri Shortcuts, and send a food photo or a health document straight into Ayuvo from any app on iPhone.</p></div>'
                 f'<div class="panel"><span class="kicker">{icon("globe")} Languages</span><h3>18 languages</h3><p>English, Arabic, Azerbaijani, Czech, Dutch, French, German, Hindi, Italian, Japanese, Korean, Polish, Portuguese (Brazil), Romanian, Russian, Simplified Chinese, Spanish and Ukrainian.</p></div>'
                 f'<div class="panel"><span class="kicker">{icon("sparkle")} Appearance</span><h3>Dark mode, units and 18 accent colours</h3><p>Metric or imperial, kilograms or pounds, millilitres or fluid ounces, with matching app icons.</p></div>'
                 f'<div class="panel"><span class="kicker">{icon("swap")} Your data</span><h3>Export, import, delete</h3><p>Export All Data, import it on either platform, or delete everything from Settings.</p></div></div>')
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
        [REL["health"], REL["coach"], REL["fasting"]], lcp_slug="nutrition", og_screens=["nutrition", "summary"],
        og_headline="Log a meal in seconds. Keep the data.")


def build_workouts() -> Page:
    blocks = [
        split("Diary", "A workout diary that counts the way you do.",
              "<p>Plan the week and log every set with reps, weight and RPE. Tap Calculate calorie burn to see the totals for the day: sets, workouts, reps and calories burned.</p>",
              ["Day-by-day diary with a week strip you can swipe",
               "Calculated calorie burn, written to Apple Health or Health Connect only if you enable it",
               "Ask the coach about your plans, sets and completed sessions, if you allow it"],
              phone("workouts", "Ayuvo workout diary with sets, reps and calculated burn")),
        split("Library", "1,300+ exercises, filtered your way.",
              "<p>Browse the library by split, target muscle, secondary muscle and equipment, and add your own exercises and activities.</p>",
              ["Exercise photos and animations load from the open exercises-dataset when you browse the library, as plain image requests",
               "Workout plans, sessions and saved exercises move between iPhone and Android in Export All Data"],
              phone("exercises", "Ayuvo exercise library with search and filters"), reverse=True),
    ]
    sends = sends_panel("What the workout diary sends, and to whom", "Your sets and plans are stored on the phone.", [
        ("Exercise media", "Browsing the library requests thumbnails and animations from GitHub (raw.githubusercontent.com). It is a plain image request with no account or identifier."),
        ("Health sync", "Calculated burn is written to Apple Health or Health Connect only when you turn that on."),
        ("Coach", "The coach reads your workouts only when you ask it something, and sends them to the provider you chose or to an on-device model."),
    ])
    return feature_page(
        "/features/workouts", "Workout Tracker with 1,300+ Exercises | Ayuvo",
        "Plan and log workouts with sets, reps, weight and RPE, calculated calorie burn and a 1,300+ exercise library. Your training diary stays on your phone.",
        "Workouts", "Plan the week. <em>Log the set.</em>",
        "A day-by-day workout diary with sets, reps, weight and RPE, calculated calorie burn and a library of more than 1,300 exercises.",
        "Workouts", phones([("workouts", "Ayuvo workout diary")], "single"), blocks, sends,
        [REL["nutrition"], REL["health"], REL["switch"]], lcp_slug="workouts", og_screens=["workouts", "exercises"],
        og_headline="Plan the week. Log the set.")


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
        split("Control", "Read a lot. Write back a little.",
              "<p>Ayuvo writes to Apple Health or Health Connect only the nutrition, weight, height, body fat and active calories you log in Ayuvo. Fasting and water are never written.</p>",
              ["Revoke access at any time in Apple Health or Health Connect settings",
               "The mirror is never included in a cloud backup, and you can clear it from Settings",
               "Export the hub in Export All Data, and import it on iPhone or Android"],
              phone("summary", "Ayuvo Summary with Health favourites")),
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
        [REL["coach"], REL["records"], REL["switch"]], lcp_slug="browse", og_screens=["browse", "heart-rate"],
        faqs=[("Which Android versions can use Health Connect?", "Health Connect is built into Android 14 and later, and is a Play Store app on Android 9 to 13. Ayuvo's Connect button opens the right screen."),
              ("Does Ayuvo change my Health data?", "Only by adding what you log in Ayuvo: nutrition, weight, height, body fat and calculated active calories. Fasting and water are never written."),
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
        [REL["coach"], REL["ondevice"], REL["switch"]], lcp_slug="records", og_screens=["records", "medications"],
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
        [REL["ondevice"], REL["providers"], REL["records"]], lcp_slug="coach", og_screens=["coach"], med=True,
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


def build_privacy_first() -> Page:
    principles = (
        '<div class="grid-2">'
        '<div class="panel"><span class="kicker">01 · Stored here</span><h3>On this device</h3><p>Your profile, diary, workouts, health data, records, medications and coach chats are stored in the app on your phone. Ayuvo has no account and no server, so there is nothing of yours to breach on our side.</p></div>'
        '<div class="panel"><span class="kicker">02 · Your choice</span><h3>You pick every destination</h3><p>Data leaves only when you act, and only to a service you chose: your AI provider, a barcode database, a file host or your own drive. Choose an on-device model and the AI step stays on the phone too.</p></div>'
        '<div class="panel"><span class="kicker">03 · Consent</span><h3>Visible, per source</h3><p>Health data, medications and records each have their own switch before the coach can read them. Medications and records start off. You can turn a source off between messages.</p></div>'
        '<div class="panel"><span class="kicker">04 · Verifiable</span><h3>Open to inspection</h3><p>No analytics, crash-reporting or advertising SDKs, and no first-party endpoints. The code is open source so you can check it. This website sets no cookies and loads nothing from third parties.</p></div></div>')
    rows = [
        ("AI provider (your key)", "Photos, text, coach messages and the context you ask for. Health data, medications and records only with their switches on", "The provider you configured", "When you analyse or ask"),
        ("On-device model", "Nothing", "Stays on the phone", "Always"),
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
    ld = [{"@context": "https://schema.org", "@type": "SoftwareSourceCode", "name": "Ayuvo", "codeRepository": GITHUB,
           "license": "https://opensource.org/licenses/MIT", "programmingLanguage": ["Swift", "Kotlin"],
           "runtimePlatform": ["iOS", "Android"], "author": {"@id": f"{BASE}/#organization"}}]
    return Page(path="/open-source", title="Open Source Health App (MIT) | Ayuvo on GitHub",
                description="Ayuvo is an MIT-licensed health app for iPhone and Android. Read the code, build it, and contribute code, translations, exercise data or docs on GitHub.",
                body=body, kind="source", crumb="Open source", ld=ld, og_headline="Open source, so you can check.", og_screens=["settings"],
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
                        modified=meta["modified"], og_headline=meta["title"].split(" — ")[0], og_screens=["settings"],
                        og_alt=meta["title"], crumb=meta["title"].split(" — ")[0]))
    return out


def build_404() -> Page:
    links = "".join(f'<li><a href="{u}">{t}</a></li>' for u, t in [("/features", "Features"), ("/privacy-first", "Privacy"), ("/features/on-device-ai", "On-device AI"), ("/open-source", "Open source"), ("/support", "Support")])
    body = (f'<div class="notfound"><div><img src="{asset("/assets/brand/ayuvo-mark.svg")}" alt="" width="84" height="84"><h1>Page not found</h1>'
            f'<p>The page you were looking for does not exist. Try one of these instead.</p><ul>{links}</ul>'
            '<p><a class="store-btn" href="/">Return home</a></p></div></div>')
    return Page(path="/404", title="Page not found | Ayuvo", description="This page does not exist. Return to the Ayuvo home page.", body=body,
                robots="noindex", in_sitemap=False, kind="page")


def all_pages() -> list[Page]:
    pages = [build_home(), build_features(), build_nutrition(), build_workouts(), build_health(), build_records(), build_coach(),
             build_fasting(), build_switch(), build_ondevice(), build_privacy_first(), build_providers(), build_open_source(), build_download()]
    pages += legal_pages()
    pages.append(build_404())
    return pages
