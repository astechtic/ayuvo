# App Store Listing

App Store Connect submission details for **Ayuvo 1.0 (build 1)**. Each field is in a code block for copy-paste. Support: yaaratech@gmail.com · App Store ID 6811947230 · Publisher: Yaara Tech.

## App Name (30 chars max)
```
Ayuvo – AI Health Companion
```

## Subtitle (30 chars max)
```
Nutrition, Workouts, AI Coach
```

## Promotional Text (170 chars max)
```
Nutrition, workouts, fasting, water and your Apple Health data in one private app — with an AI coach that runs on the key you bring. No account, no ads, no subscriptions.
```

## Keywords (100 chars max)
```
calorie,counter,macro,food,scanner,barcode,gym,lifting,fasting,water,weight,bodyfat,diet,log,tracker
```

## Category
```
Primary: Health & Fitness
Secondary: Food & Drink
```

## Description
```
Ayuvo is a private, all-in-one health companion. Log meals by photo, barcode, voice or text, plan and log workouts, mirror your Apple Health data into one hub, track fasting and water, and ask an AI coach that can see the whole picture — all on your iPhone, with no account and no Ayuvo servers.

NUTRITION
Snap up to 10 photos of a meal, scan a barcode (Open Food Facts), speak it, type it, reuse a saved meal or enter it manually. Review calories, macros and 30+ nutrients before anything is saved, adjust servings, and preview "What if?" before you log. Personalised targets from Mifflin-St Jeor or Katch-McArdle that you can override.

WORKOUTS
A day-by-day workout diary with sets, reps, weight and RPE, calculated calorie burn, and a 1,300-exercise library with photos and animations filtered by body part, target muscle and equipment.

HEALTH DATA HUB
With your permission Ayuvo mirrors the Apple Health data you grant — activity, heart, sleep, vitals, body measurements, cycle tracking, mindfulness, mobility, hearing, symptoms, nutrition and hydration — into a local hub with Home tiles, day/week/month/year charts, Show All Data and Data Sources. Ayuvo writes only the nutrition, weight, height, body fat and active calories you log. Health data is kept on this device and never stored in iCloud backups.

HEALTH RECORDS
Keep every lab report, prescription, consultation note, discharge summary, imaging report and bill in one place. Scan, photograph, pick files or send them to Ayuvo from any app — the original is saved instantly. Ayuvo reads the text on your iPhone, finds the doctor, hospital, dates and test values, and highlights results the report itself marks outside its reference range. A timeline, list and grid view, one search that covers documents, values, doctors and notes, and trend graphs that connect the same test across reports — tap any value to open the page it came from. Edit anything, link related records, and share only what you choose, with names and ID numbers blacked out if you want.

FASTING & WATER
Optional fasting with a 1–168 hour goal, a timer that survives restarts and editable history. Optional water goals with quick logging, reminders, widgets and Apple Watch. Both stay local and are never written to Health.

AI COACH — BRING YOUR OWN KEY
Multi-turn chat that can pull your food log, workouts, weight trend, fasts and — only with your explicit toggles — your Health data and your health records (ask about one report, compare two, or explain a trend; you choose which records it may read). Use Google Gemini, OpenAI, Anthropic, xAI, OpenRouter, Together AI, Groq, Hugging Face, Fireworks AI, DeepInfra, Mistral, DeepSeek, Cerebras, Ollama or any OpenAI-compatible endpoint with your own key, or run Gemma 4 E2B fully on-device. Apple Intelligence can handle text food descriptions on supported iPhones. Keys stay in the iOS Keychain; requests go straight from your phone to the provider.

WIDGETS & WATCH
Home Screen and Lock Screen widgets, an Apple Watch app with complications, Siri Shortcuts to log food and weight, and a Share Extension to send a food photo straight into Ayuvo.

PRIVACY
No account, no ads, no analytics, no tracking. Everything you log stays on your device. Data leaves your phone only when you act: sending a request to the AI provider you chose, scanning a barcode, browsing exercise images, downloading a model, sharing a health record, or turning on your own iCloud backup (which never includes Health data, health records or coach chat). Health records are processed on your iPhone by default — you decide during setup whether AI may help, and whether it runs on-device or with your own key. Export everything, delete everything — Settings → Delete All Data.

Ayuvo is not a medical device and does not give medical advice. Estimates come from AI and formulas; talk to a clinician before changing diet, training or medication.

Terms: https://ayuvo-health.web.app/terms
Privacy: https://ayuvo-health.web.app/privacy
Support: https://ayuvo-health.web.app/support
```

## What's New (1.0)
```
Ayuvo 1.0 — nutrition, workouts, the Health data hub, fasting, water and an AI coach in one private app.

• Log meals by photo, barcode, voice, text or manual entry with 30+ nutrients
• Plan and log workouts from a 1,300-exercise library
• Mirror your Apple Health data into a local hub with charts and history
• Optional fasting timer and water tracking
• AI coach on your own key, or fully on-device
• Widgets, Apple Watch, Siri Shortcuts, 18 languages
```

## URLs
```
Privacy Policy URL: https://ayuvo-health.web.app/privacy
Terms of Use (EULA) URL: https://ayuvo-health.web.app/terms
Support URL: https://ayuvo-health.web.app/support
Marketing URL: https://ayuvo-health.web.app/
```

## App Privacy (App Store Connect → App Privacy)

**Recommended answer: "Data Not Collected."** Rationale to keep on file:

- Apple defines *collected* data as data transmitted off the device in a way that the developer and/or third-party partners can access beyond what is needed to service the request in real time. *Third-party partners* are analytics or advertising tools, SDKs or vendors whose code the developer integrates. Ayuvo integrates no such SDK and runs no server.
- Photos, text and (only with the user's consent toggle) Health summaries go over HTTPS to a provider the user selected and authenticated with their own key. The developer has no access to those requests and no relationship with the provider; it is a user-initiated transfer to a service of the user's choosing, like a mail client sending an email.
- Health & Fitness data stays in the on-device hub, is excluded from iCloud and device backups, and is shared only through the coach toggle to the user's chosen provider.
- Health Records (documents the user adds) are stored on device and excluded from iCloud and device backups. They leave the phone only on a user action: AI processing with the user's own key (only in Cloud AI / Ask me mode, which the user picks), Coach after a separate opt-in (patient name, address, phone and ID numbers are never sent), an explicit Share (with optional redaction), or an archive the user exports and saves where they choose. There is no iCloud counterpart for records, per guideline 5.1.3(ii).
- Open Food Facts (barcode number), GitHub (image GETs), Hugging Face (model GETs) and Apple's iTunes lookup (public metadata) transmit no personal data types. CloudKit private-database backups are user-owned and inaccessible to the developer.
- **Conservative alternative** (use only if you prefer to over-disclose, and then mirror it on Play's Data safety form): declare Health & Fitness → Health, Health & Fitness → Fitness, Photos or Videos, User Content → Other User Content as "Data Not Linked to You", purpose App Functionality, not used for tracking, with "optional / user-initiated" in the notes. Do not mix the two answers.
- Tracking: **No**. Advertising: **No**.

## Age Rating
```
4+ (no objectionable content). Store availability: 13+ recommended (matches Play); the app is not directed at children.
```

## Reviewer Notes
```
Ayuvo has no account, no sign-in and no purchases of any kind.

AI features are "bring your own key". A working Google Gemini API key for review is in the App Review Information sign-in fields (username: reviewer, password: <the key>). To enable AI: during onboarding on the "Set Up Your AI" step (or later in Settings → AI Providers) choose Google Gemini, model gemini-3.5-flash-lite, paste the key, tap Accept & Continue. Photo analysis, text/voice logging, "What if?" and the Coach then work. Without a key the app still works for manual logging, barcode lookup, workouts, fasting, water and the Health data hub.

Health data hub: Settings → Health & Data → Connect Apple Health (or the Health tab → Connect). Grant categories; the Health tab shows "Progress | Health Data". The "Let Coach use my health data" toggle is shown on the connect screen and in Settings; only while it is on can the coach send Health summaries to the user's own AI provider.

Health Records: the Records tab → + → Choose files (or share a PDF to Ayuvo). The original saves immediately; text reading and rule-based extraction run on device. During onboarding the user picks how AI may process records (Local AI / Cloud AI / Ask me / Skip AI); with Skip AI nothing is sent anywhere. "Let Coach read your health records" is off by default and lives in Settings → Health Records. Sharing shows every item before sending and can black out identifiers.

Optional large downloads (Gemma 4 E2B ~2.6 GB, Whisper Base) are user-initiated and can be skipped. Local Network permission is requested only if the user enters an Ollama server address. iCloud Backup (Settings → Data Management) is off by default and uses the user's private CloudKit database; it never includes Health data. Settings → Delete All Data wipes the device copy.
```

## Version / Build
```
MARKETING_VERSION: 1.0
CURRENT_PROJECT_VERSION: 1
Bundle ID: com.ayuvo.health (widgets .widgets, share .share, watch .watch, watch widgets .watch.widgets)
App Store ID: 6811947230
```

## Screenshots
See `marketing/storyboard.json` and `marketing/seed/README.md`. Store sets: `marketing/store/ios/6.9/01..08.png` (1290×2796); Apple Watch `marketing/appstore/watch/*.png`; icon `marketing/appstore/icon-1024.png`.
