"""Facts and copy for the /compare pages (rendered by compare_pages.py, linted by scripts/web_build.py).

Every statement about another product must come from that product's own public page and carry a
`[n]` marker that points at an entry in SOURCES. Statements about Ayuvo come from the docs and pages in
this repo. Re-fetch the sources and update CHECKED whenever this file changes.

Copy rules for these pages (enforced by the lint): no "better than", "beats", "superior" or "best"
claims; no ratings or prices; nothing about a competitor's privacy failures; a competitor's own
compliance wording (for example HIPAA) is allowed only inside <span class="vclaim">states ...</span>.
Vendor marketing is attributed ("says", "states", "its page cites"), never asserted as fact. Every page
must list where Ayuvo is limited. The MedGemma name is not used here, so no medical-model disclaimer is needed.
"""
from __future__ import annotations

CHECKED = "2026-09-25"

# Ayuvo-side cells reused by several pages.
AY_INTAKE = "Camera, scan, file, paste or the share sheet. Read on your phone, with values and highlights."
AY_STORE = "On your phone. No Ayuvo account and no Ayuvo server."
AY_AI = "Optional. Your own key (15 providers) or a model that runs on the phone."
AY_TRACK = "Meals (photo, barcode, voice), workouts, water, fasting and medications."
AY_HEALTH = "Reads and mirrors Apple Health on iPhone and Health Connect on Android, with your permission."
AY_PLAT = "iPhone (iOS 17.6+) and Android (8.0+)."
AY_COST = "Free. No subscription, credits or in-app purchases. AI on your own key is billed by that provider."

# Limits that apply to every comparison; compare_pages.py adds the store note while a listing is not live.
LIMITS_COMMON = [
    "Your data lives on your phone. Records and Health data are not in cloud backups, so keep an Export All Data file if you would miss them.",
    "AI on your own key is billed by the provider you chose, and on-device models need a one-time download and a phone with enough memory.",
    "Ayuvo is a young project with no independent reviews yet. Its code is open, so you can check what it does.",
]

COMPARE = {
    "guava-health": {
        "name": "Guava Health",
        "crumb": "Ayuvo vs Guava Health",
        "title": "Guava Health Alternative: Ayuvo, Private and No Account",
        "description": "Guava syncs with your patient portals; Ayuvo doesn't. Compare records, medications, AI and privacy, and see who each app suits. Ayuvo needs no account.",
        "h1": "Ayuvo vs <em>Guava Health</em>",
        "og_headline": "Ayuvo vs Guava Health",
        "verdict": "Guava Health and Ayuvo both keep medical records, medications and health data in one app. The main difference is where the work happens: Guava says it brings records in from your providers' portals, while Ayuvo has no account and no server, keeps everything on your phone, and you add documents yourself.",
        "screens": [("records", "Ayuvo health records with important highlights"), ("medications", "Ayuvo medications with today's doses")],
        "lcp": "records",
        "sources": [("Guava Health homepage", "https://www.guavahealth.com")],
        "rows": [
            ("Getting records in", AY_INTAKE, "Says it extracts lab results from PDFs, photos and health portals [1]"),
            ("Provider connections", "None. You add documents yourself.", "Says it connects to patient portals, and its page cites 100,000+ providers, including Epic, Cerner and Quest Diagnostics [1]"),
            ("Account", "None.", "Not stated on its page. See Guava's site [1]"),
            ("Where your data lives", AY_STORE, 'Guava <span class="vclaim">states it follows HIPAA and GDPR and does not sell your data</span> [1]. Read its privacy policy for storage details'),
            ("AI", AY_AI, "Says a Health Insights feature can find symptom triggers and evaluate treatments [1]"),
            ("What you can track", AY_TRACK, "Its page lists symptoms, medications, mood, sleep, food, blood pressure, cycle and pregnancy [1]"),
            ("Apple Health and Health Connect", AY_HEALTH, "Lists Apple Health, Fitbit, Garmin and Dexcom among its integrations [1]"),
            ("Platforms", AY_PLAT, "iOS, Android and web [1]"),
            ("Cost", AY_COST, "Not on the page we checked. See Guava's plans page [1]"),
        ],
        "stronger": [
            "It says it can bring records in for you from patient portals, so you do not have to download and add each file [1].",
            "It has a web version, and its page describes a printable summary to share with your doctor and a wallet-sized emergency card with a QR code [1].",
            "It says it logs symptoms and mood and can look for patterns in them [1].",
        ],
        "different": [
            "No account and no server: records, medications and your diary are stored on your phone, and there is nothing to sign in to.",
            "Records are read on your phone, and AI is optional: your own key, a model on the phone, or none at all.",
            "Open source under the MIT licence, so anyone can read how your data is handled.",
            "Meals, workouts and a coach sit alongside your records, and Export All Data moves everything between iPhone and Android.",
        ],
        "limits": [
            "No connection to patient portals or providers. You add every document yourself.",
            "No web version. Ayuvo runs on iPhone and Android only.",
        ],
        "choose_vendor": ["You want records brought in for you from patient portals", "You want a web version, visit summaries and an emergency card", "You want your records collected and kept up to date without adding files yourself"],
        "choose_ayuvo": ["You would rather keep records on your own phone, with no account", "You are happy to add documents yourself: camera, scan, file or share sheet", "You want meals, workouts and medications in the same app", "You want the code open to inspection"],
        "choose_both": [],
        "faqs": [
            ("Is Ayuvo an alternative to Guava Health?", "For keeping records, medications and health data in one place, yes. The main difference is that Guava says it connects to your providers, while Ayuvo does not. You add documents yourself and they stay on your phone."),
            ("Does Ayuvo connect to my hospital or patient portal?", "No. Ayuvo does not sign in to patient portals or hospital systems. Download or photograph the document, then add it with the camera, scan, file picker or share sheet."),
            ("Do both apps work with Apple Health?", "Guava lists Apple Health among its integrations. Ayuvo reads Apple Health on iPhone and Health Connect on Android, with your permission, and keeps a local mirror."),
            ("What does Ayuvo not do that Guava does?", "Ayuvo has no portal connections, no web version and no clinician-facing features. The Where Ayuvo is limited section above lists the rest."),
        ],
    },
    "picnichealth": {
        "name": "PicnicHealth",
        "crumb": "Ayuvo vs PicnicHealth",
        "title": "PicnicHealth Alternative: A Private App, Not a Service",
        "description": "PicnicHealth gathers your records for you. With Ayuvo you add them yourself and they stay on your phone, with no account. See which approach fits you.",
        "h1": "Ayuvo vs <em>PicnicHealth</em>",
        "og_headline": "Ayuvo vs PicnicHealth",
        "verdict": "PicnicHealth says it gathers your records from your providers and has a clinical team review them. Ayuvo is a free app you run yourself: you add your documents, they are read on your phone, and nothing is collected on your behalf or stored on an Ayuvo server.",
        "screens": [("records", "Ayuvo health records with important highlights"), ("record-extraction", "Ayuvo record detail with extracted information")],
        "lcp": "records",
        "sources": [("PicnicHealth homepage", "https://picnichealth.com")],
        "rows": [
            ("Getting records in", AY_INTAKE, "Says it gathers your records from every point of care into one organised view [1]"),
            ("Who does the work", "You. Ayuvo is a self-serve app.", "Says a clinical team reviews your records and develops a personalised care plan [1]"),
            ("Where your data lives", AY_STORE + " Nothing is shared for research.", "Says you own your health data, with lifetime digital access. It describes research participation, sharing anonymised data with pharmaceutical and academic partners, as your choice [1]"),
            ("AI and guidance", AY_AI + " Coach describes patterns and defers to your clinician.", "Describes health assistance to help you understand your health story and know the right questions to ask [1]"),
            ("What you can track", AY_TRACK, "Not described on its homepage [1]"),
            ("Apple Health and Health Connect", AY_HEALTH, "Not described on its homepage [1]"),
            ("Platforms", AY_PLAT, "iOS and Android [1]"),
            ("Cost", AY_COST, "Has a pricing page, and its page refers to subscriptions [1]"),
        ],
        "stronger": [
            "It says it does the collecting: it gathers your records from your providers, so you do not chase each one yourself [1].",
            "People are part of it. It says a clinical team reviews your records and develops a personalised care plan, which no app can do on its own [1].",
            "It describes an optional way to take part in research, if that is something you want [1].",
        ],
        "different": [
            "A free app you run yourself: there is no service to sign up to and nobody to wait for.",
            "Nothing is collected on your behalf and nothing is shared for research. Ayuvo has no account, no server and no analytics.",
            "Records are read on your phone, and AI is optional: your own key, a model on the phone, or none at all.",
            "Open source under the MIT licence, with meals, workouts and a coach alongside your records.",
        ],
        "limits": [
            "Nobody collects your records for you and no clinician reviews them. Ayuvo shows what a document says and does not interpret it.",
            "No care plan or human support beyond email.",
        ],
        "choose_vendor": ["You want someone else to collect your records from every provider", "You want a clinical team to review your history", "You want lifetime digital access to your records and are open to optional research participation"],
        "choose_ayuvo": ["You want to keep your own records on your phone, with no account", "You are happy to add documents yourself", "You want food, workouts, medications and health data alongside them", "You prefer a tool with no research programme, where data leaves your phone only when you act"],
        "choose_both": [],
        "faqs": [
            ("Is Ayuvo a PicnicHealth alternative?", "They cover different parts of the job. PicnicHealth says it gathers records and has clinicians review them. Ayuvo is a free app for keeping your own records and daily health data on your phone. If you want the collecting and review done for you, PicnicHealth fits. If you want to keep it yourself with no account, Ayuvo does."),
            ("Can Ayuvo get my records from my doctors?", "No. Ayuvo does not contact providers. You add documents yourself with the camera, a scan, a file or the share sheet."),
            ("Does Ayuvo share data for research?", "No. Ayuvo has no account, no server and no analytics. Data leaves your phone only when you act, and only to a service you chose."),
        ],
    },
    "apple-health": {
        "name": "Apple Health",
        "crumb": "Apple Health and Ayuvo",
        "title": "Apple Health Alternative? Ayuvo Adds Records and Meds",
        "description": "Ayuvo reads Apple Health, then adds records, medications, meals and a coach, all stored on your phone. See where each fits, and how to use both.",
        "h1": "Apple Health and Ayuvo: <em>use both.</em>",
        "og_headline": "Apple Health and Ayuvo: use both.",
        "verdict": "Apple Health is built into iPhone and holds your health data. Ayuvo is not a replacement for it: with your permission Ayuvo reads Apple Health, keeps a local mirror with charts, and adds meal logging, workouts, record scanning, medication reminders and a coach, plus an Android version.",
        "screens": [("browse", "Ayuvo Browse list of health categories"), ("heart-rate", "Ayuvo heart rate detail chart")],
        "lcp": "browse",
        "sources": [("Apple Health on apple.com", "https://www.apple.com/ios/health/"),
                    ("Get health records from your providers, iPhone User Guide", "https://support.apple.com/guide/iphone/get-your-health-records-iph5a1d4e1c1/ios")],
        "rows": [
            ("What it is", "An app for iPhone and Android for meals, workouts, records, medications and your Health data.", "The Health app, built in to iPhone, iPad, Apple Watch and Apple Vision Pro [1]"),
            ("Data it holds", "Mirrors the Apple Health data you allow, and adds its own diary, records and medications.", "Says it organises medications, sleep, activity and more in one place [1]"),
            ("Records", AY_INTAKE, "Store a vision prescription by taking a photo [1]. Its guide describes downloading health records from your providers [2]"),
            ("Where your data lives", AY_STORE + " Not in cloud backups.", "States your health data is encrypted on your device. iCloud data is encrypted in transit and at rest, and end to end with two-factor authentication [1]"),
            ("Trends", "Day, week, month, six-month and year charts, with favourites on Summary.", "Trends that show how a metric changes over time and what may influence it [1]"),
            ("Meal photo scanning and workout diary", "Photo, barcode and voice meal logging, and a workout diary with a 1,300+ exercise library.", "Not described on Apple's Health page [1]"),
            ("Coach", AY_AI, "Says the redesigned Health app promises more ways to turn your data into insights [1]"),
            ("Android", "Yes, using Health Connect.", "No. It runs on Apple devices [1]"),
        ],
        "stronger": [
            "It is built in. There is nothing to install, and it sits alongside your iPhone, iPad and Apple Watch [1].",
            "It has Health Sharing, and can create a PDF of an ECG reading to share with your care team [1].",
            "Its guide describes downloading health records from your providers, which Ayuvo does not do [2].",
            "It states your data can be end-to-end encrypted in iCloud when two-factor authentication is on [1].",
        ],
        "different": [
            "Ayuvo reads Apple Health rather than replacing it, so nothing you already have is lost, and you can revoke access at any time in Health settings.",
            "It works on Android too, and Export All Data moves your history between iPhone and Android.",
            "It adds photo and barcode meal logging, a workout diary, record scanning with highlights, medication reminders and a coach.",
            "AI is optional and yours: your own key, or a model on the phone.",
        ],
        "limits": [
            "Ayuvo depends on Apple Health for your Health data. It mirrors only the types you grant and writes back only a few.",
            "It does not download health records from your providers.",
        ],
        "choose_vendor": ["You only need what the Health app already shows", "You use Apple devices only", "You want to download records from providers where your region supports it"],
        "choose_ayuvo": ["You want meals, workouts, records and medications in one app", "You use, or may switch to, Android", "You want a coach that reads only the sources you allow"],
        "choose_both": ["Keep Apple Health as the store on your iPhone, and let Ayuvo mirror it", "Ayuvo writes back only nutrition, weight, height, body fat and calculated active calories that you log"],
        "faqs": [
            ("Is Ayuvo an Apple Health alternative?", "It is better described as a companion. Ayuvo reads the Apple Health data you allow and adds meals, workouts, records, medications and a coach. It also runs on Android, where it uses Health Connect."),
            ("Can I use Ayuvo without Apple Health?", "Yes. Health access is optional. Without it Ayuvo works as a private diary for meals, workouts, records and medications."),
            ("Does Ayuvo work on Android too?", "Yes. On Android it uses Health Connect instead of Apple Health, and Export All Data moves your history between the two platforms."),
        ],
    },
    "mychart": {
        "name": "MyChart",
        "crumb": "MyChart and Ayuvo",
        "title": "MyChart Alternative? Keep a Private Copy of Your Records",
        "description": "Ayuvo can't connect to MyChart. Download your documents, add them by scan, file or share sheet, and keep them on your phone with no account.",
        "h1": "MyChart and Ayuvo: <em>different jobs.</em>",
        "og_headline": "MyChart and Ayuvo: different jobs.",
        "verdict": "MyChart is a patient portal licensed from Epic and provided by your health system. Ayuvo is not a portal and cannot sign in to one. It is a private place on your phone to keep your own copies of reports, track medications, and log food and workouts next to them.",
        "screens": [("records", "Ayuvo health records with important highlights"), ("medications", "Ayuvo medications with today's doses")],
        "lcp": "records",
        "sources": [("MyChart homepage", "https://www.mychart.org")],
        "rows": [
            ("What it is", "An app you run yourself for your own records and daily health data.", "Describes itself as a patient portal licensed from Epic Systems [1]"),
            ("Who gives you access", "Anyone can install it. There is nothing to sign up for.", "Says health systems and healthcare organisations provide access to their patients [1]"),
            ("Records", "Documents you add: camera, scan, file, paste or the share sheet.", "Says you can view medications, test results, bills and more from your provider [1]"),
            ("Appointments and messages", "None. Ayuvo cannot sign in to a portal or book care.", "Says you can find and schedule care, and connect with your doctor virtually [1]"),
            ("Where your data lives", AY_STORE, "Links to Epic's privacy policies, and your health system's apply. See its site [1]"),
            ("What you can track", AY_TRACK, "Not described on its homepage [1]"),
            ("Platforms", AY_PLAT, "Web, iOS and Android [1]"),
            ("Cost", AY_COST, "Not stated on its homepage [1]"),
        ],
        "stronger": [
            "It is connected to your care. It says you can find and schedule care, and connect with your doctor virtually [1].",
            "It shows your provider's own records, test results, medications and bills [1].",
            "It says you can manage care for you and your family, with one login for all your care [1].",
        ],
        "different": [
            "Your own copy, on your own phone. Save reports from any portal or provider and keep them in one searchable timeline.",
            "Ayuvo holds whatever you add, whichever provider it came from, with values and highlights read on the phone.",
            "Medication reminders, meals, workouts and your Apple Health or Health Connect data sit next to your records.",
            "No account, no ads and no analytics, and the code is open source.",
        ],
        "limits": [
            "Ayuvo cannot sign in to MyChart or any portal, book care or message a clinician.",
            "It only holds what you add, so it is not your provider's official record.",
        ],
        "choose_vendor": ["You want to book care, message your doctor or see your provider's records", "Your health system offers it and you want everything in one portal"],
        "choose_ayuvo": ["You want your own private copy of your paperwork", "You want reminders, food and workouts next to your records", "You want it all on your phone with no account"],
        "choose_both": ["Save or photograph a report from any portal, then add it to Ayuvo with a file, a photo or the share sheet", "Keep the portal for appointments and messages, and Ayuvo for your own timeline"],
        "faqs": [
            ("Is Ayuvo a MyChart alternative?", "Not a replacement. MyChart connects you to your provider. Ayuvo is a private place to keep your own copies of records and track daily health. Many people can use both."),
            ("Can Ayuvo connect to MyChart?", "No. Ayuvo does not sign in to MyChart or any other patient portal. Save or photograph a document and add it yourself."),
            ("Can I use Ayuvo and MyChart together?", "Yes. Keep MyChart for appointments and messages with your provider, and save or photograph a report from it to add to Ayuvo."),
        ],
    },
    "health-connect": {
        "name": "Health Connect",
        "crumb": "Health Connect and Ayuvo",
        "title": "Health Connect Companion App: What Ayuvo Adds on Android",
        "description": "Health Connect is Android's shared health data layer. Ayuvo reads it and adds charts, records, medications and meals, kept on your phone.",
        "h1": "Health Connect and Ayuvo: <em>use both.</em>",
        "og_headline": "Health Connect and Ayuvo: use both.",
        "verdict": "Health Connect is Android's shared store for health and fitness data, kept on your device. Ayuvo is an app that sits on top of it: with your permission it reads Health Connect, mirrors the data locally with charts, and adds records, medications, nutrition, workouts and a coach.",
        "screens": [("browse", "Ayuvo Browse list of health categories"), ("heart-rate", "Ayuvo heart rate detail chart")],
        "lcp": "browse",
        "sources": [("Health Connect overview, Android Developers", "https://developer.android.com/health-and-fitness/health-connect"),
                    ("Health Connect medical records, Android Developers", "https://developer.android.com/health-and-fitness/health-connect/medical-records")],
        "rows": [
            ("What it is", "An app that reads Health Connect and adds its own charts, diary, records and coach.", "A health and fitness data platform on Android that lets apps share data with the user's permission [1]"),
            ("Where data is stored", "A local mirror of the types you grant. Never in a cloud backup.", "Stores and structures data on the user's Android device [1]"),
            ("Permissions", "Asks only for the types it needs. You can revoke access in Health Connect settings.", "Requires user permission before an app reads data, with controls to manage it [1]"),
            ("Browsing and charts", "Day, week, month, six-month and year charts, sources and units for every metric.", "A screen for browsing data and managing permissions [1]"),
            ("Medical records", AY_INTAKE, "Supports medical records in FHIR format with its own permissions screen. The APIs are marked experimental and still changing [2]"),
            ("Meals, workouts and coach", AY_TRACK + " " + AY_AI, "It is a platform: apps read and write the data [1]"),
            ("Android versions", "Android 8.0 and later.", "Android SDK 28 (Android 9) and higher [1]"),
            ("iPhone", "Yes, using Apple Health.", "No. It is part of Android [1]"),
        ],
        "stronger": [
            "It is the shared layer several apps can write to, so data from different apps can meet in one place under your permissions [1].",
            "It has its own permissions screen, so you decide which app can read or write what [1].",
            "It supports medical records in the open FHIR format, with a separate permissions screen, though the APIs are still marked experimental [2].",
        ],
        "different": [
            "Ayuvo turns the stored data into charts by day, week, month, six months and year, with sources and units.",
            "It adds photo, barcode and voice meal logging, a workout diary, record scanning, medication reminders and a coach.",
            "It also runs on iPhone with Apple Health, and Export All Data moves your history between platforms.",
            "AI is optional and yours: your own key, or a model on the phone.",
        ],
        "limits": [
            "Ayuvo depends on Health Connect for your Health data. Without it the Health data hub has nothing to show.",
            "It mirrors only the types you grant, and without the history permission Android limits how far back it can read.",
        ],
        "choose_vendor": ["You want the system store that several apps can share", "You build or use other apps that read and write Health Connect"],
        "choose_ayuvo": ["You want charts, meals, workouts, records and medications in one app", "You may switch between Android and iPhone"],
        "choose_both": ["Keep Health Connect as the store and let Ayuvo read it", "Ayuvo writes back only nutrition, weight, height, body fat and calculated active calories that you log"],
        "faqs": [
            ("Is Ayuvo a Health Connect alternative?", "Not really. Health Connect is Android's data layer, and Ayuvo is an app that uses it. You would normally use both."),
            ("Do I need Health Connect to use Ayuvo?", "Only for the Health data hub. Without it Ayuvo works as a private diary for meals, workouts, records and medications."),
            ("Can I use Ayuvo on iPhone as well?", "Yes. On iPhone it uses Apple Health. Export All Data on one phone and import it on the other to move your history."),
        ],
    },
}

ORDER = ["guava-health", "picnichealth", "apple-health", "mychart", "health-connect"]

# Hub: what each product is, in the fewest words that are safe without a citation.
HUB_ONE_LINERS = {
    "guava-health": "Records and health tracking app",
    "picnichealth": "Records collection service",
    "apple-health": "Built-in Apple Health app",
    "mychart": "Patient portal from Epic",
    "health-connect": "Android health data platform",
}
