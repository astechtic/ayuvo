# App Store Listing

App Store Connect submission details for **Ayuvo 1.0 (build 3)**. Each field is in a code block for copy-paste. Support: yaaratech@gmail.com · App Store ID 6811947230 · Publisher: Yaara Tech.

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
Ayuvo is a private, all-in-one health companion. Log meals, workouts, medications and health records, mirror your Apple Health data into one hub, and ask an AI coach that can see the whole picture — with no account and no Ayuvo servers.

NUTRITION
Snap up to 10 photos of a meal, scan a barcode (Open Food Facts), speak it, type it, reuse a saved meal or enter it manually. Review calories, macros and 30+ nutrients before saving, adjust servings and preview "What if?". Personalised targets you can override.

WORKOUTS
A workout diary with sets, reps, weight and RPE and a 1,300-exercise library with photos and animations. Record walks, runs, rides and hikes with GPS: route, splits, pace and elevation, saved to Apple Health.

HEALTH DATA HUB
With your permission Ayuvo mirrors the Apple Health data you grant — activity, heart, sleep, vitals, body measurements, cycle tracking, mindfulness, mobility, hearing and symptoms, nutrition and hydration — into a local hub with Home tiles, day-to-year charts and Data Sources. Ayuvo writes the nutrition, weight, height, body fat and active calories you log, and the outdoor workouts you record. You can also connect Google Health (read-only). Health data is kept on this device and excluded from iCloud backups.

HEALTH RECORDS & MEDICATIONS
Keep lab reports, prescriptions, consultation notes, discharge summaries, imaging reports and bills in one place. Scan, photograph or share them to Ayuvo; text is read on your iPhone, test values are found and tracked across reports, and you can share with identifiers blacked out. Add medicines with schedules, get reminders and keep a dose log.

CYCLE TRACKING
Optional period tracker with flow, symptoms, mood and notes, calendar estimates of your next period and fertile window (estimates only, not birth control), discreet reminders and Apple Health sync.

CAMERA MEASUREMENTS
Finger and face scans estimate heart rate, heart rate variability and respiratory rate, with a confidence for every value. General wellness, not a medical measurement.

FASTING & WATER
Optional fasting timer (1–168 h) and water goals with reminders, widgets and Apple Watch.

AI COACH — BRING YOUR OWN KEY
Chat that can use your food log, workouts, weight trend and fasts and — only with your explicit toggles — your Health data and records. Use Google Gemini, OpenAI, Anthropic, xAI, OpenRouter, Together AI, Groq, Hugging Face, Fireworks AI, DeepInfra, Mistral, DeepSeek, Cerebras, Ollama or any OpenAI-compatible endpoint with your own key, or run a model fully on-device. Keys stay in the iOS Keychain; requests go straight from your iPhone to the provider.

PARTNER HEALTH SYNC
Pair with someone you trust by QR code and share only the categories you choose, directly between your phones on the same Wi-Fi. No server, no account.

WIDGETS & WATCH
Home Screen and Lock Screen widgets, an Apple Watch app with complications, Siri Shortcuts and a Share Extension. 18 languages and 18 accent colours.

PRIVACY
No account, no ads, no analytics, no tracking. Your data is stored on this device. It leaves your iPhone only when you act: a request to the AI provider you chose, a barcode lookup, exercise images, a model download, Google Health sync, a partner you paired with, or a file you export or share. Export everything or delete everything from Settings.

Ayuvo is not a medical device and does not give medical advice. Estimates come from AI and formulas; talk to a clinician before changing diet, training or medication.

Terms: https://ayuvo-health.web.app/terms
Privacy: https://ayuvo-health.web.app/privacy
Support: https://ayuvo-health.web.app/support
```

## What's New (1.0)
```
Ayuvo 1.0 — your whole health in one private app.

• Log meals by photo, barcode, voice, text or manual entry with 30+ nutrients
• Workouts from a 1,300-exercise library, plus GPS walks, runs, rides and hikes
• Mirror your Apple Health data into a local hub with charts and history
• Health records, medications with reminders, and optional cycle tracking
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
- Open Food Facts (barcode number), GitHub (image GETs), Hugging Face (model GETs) and Apple's iTunes lookup (public metadata) transmit no personal data types. The iPhone app has no cloud backup; Export All Data writes a file the user saves where they choose.
- Google Health (optional, read-only) downloads the user's own data from Google to the phone after the user signs in; nothing is uploaded and the developer has no access.
- Partner Health Sync sends user-chosen summaries directly to a phone the user paired with by QR code, over the local network or as a file the user shares. No server is involved and the developer has no access.
- GPS routes from outdoor workouts stay on the device unless the user writes the workout to Apple Health.
- **Conservative alternative** (use only if you prefer to over-disclose, and then mirror it on Play's Data safety form): declare Health & Fitness → Health, Health & Fitness → Fitness, Photos or Videos, User Content → Other User Content as "Data Not Linked to You", purpose App Functionality, not used for tracking, with "optional / user-initiated" in the notes. Do not mix the two answers.
- Tracking: **No**. Advertising: **No**.

## Age Rating
```
4+ (no objectionable content). Store availability: 13+ recommended (matches Play); the app is not directed at children.
```

## Reviewer Notes
```
Ayuvo has no account, no sign-in and no in-app purchases. Everything is stored on the device.

DEMO VIDEO (6 min, every feature): https://drive.google.com/file/d/1cJof_7Z0BwZuqI7BYm4Woc_SyWikWfAK/view?usp=sharing

AI KEY FOR REVIEW
AI features are "bring your own key". Use this Google Gemini API key: [GEMINI_REVIEW_KEY]
Onboarding "Set Up Your AI" step (or Settings > AI & Speech > AI Providers): choose Google Gemini, model gemini-3.5-flash-lite, paste the key, tap Accept & Continue. Photo meal analysis, text/voice logging, "What if?" and the Coach then work. Without a key the app still works for manual logging, barcode lookup, workouts, fasting, water, medications, records and the Health data hub.

WHERE THINGS ARE (tabs: Summary, Browse, Records, Coach, Settings; on iPad the tabs are at the top)
- Apple Health: Browse > Grant access, or Settings > Data & Privacy > Health Sync. The "Let Coach use my health data" toggle is shown before access is requested; only while it is on can the Coach send Health summaries to the user's own AI provider.
- Health Records: Records tab > +. Text is read on the device. The user chooses how AI may process records (Local AI / Cloud AI / Ask me / Skip AI). "Let Coach read your health records" is off by default.
- Medications: Browse > Medications. Reminders are local notifications; the app never gives dosing advice.
- Camera measurements: Browse > Camera measurements, or Summary > + > Measure. Heart rate, heart rate variability and respiratory rate from a finger or face scan, for general wellness, each with a confidence. Not written to Apple Health. The app does not measure blood pressure or blood oxygen.
- Partner Health Sync: Settings > Data & Privacy > Partner Health. Two paired phones exchange chosen summaries directly over the same Wi-Fi; no server.

AI RESPONSE REPORTING
Every Coach answer has a Report button (flag icon, next to Copy / Regenerate / Share): pick a reason, the answer is marked reported on the device, and the user can email it to us.

PERMISSIONS AND BACKGROUND MODES
- Location ("When In Use" only) is requested when the user starts a GPS walk, run, ride or hike: Browse > Activity > Workouts > orange + button > Start GPS Workout. The "location" background mode keeps that recording going while the screen is locked.
- "fetch" refreshes medication reminders and on-device insights. HealthKit background delivery keeps the local Health hub current.
- Local Network is used only for Partner Health Sync or an Ollama server address the user enters.
- Optional model downloads (on-device AI ~2.6 GB, Whisper) start only when the user taps Download.

Ayuvo supports iPhone and iPad. Settings > Data & Privacy > Delete All Data wipes everything on the device. Ayuvo is not a medical device and gives no medical advice.
```

## Screenshots
- iPhone 6.9" (1290×2796), in this order: `marketing/store/ios/6.9/01-summary.png` … `09-export-data.png` (`python3 scripts/brand/store3d/render.py --platform ios`, from the redacted captures in `marketing/raw/ios-real/`). Apple scales them down for smaller iPhones.
- iPad 13" (2064×2752): `marketing/store/ios/ipad-13/01-summary.png` … `07-coach.png`, real captures from the iPad Pro 13-inch simulator (`marketing/raw/ipad/`, `AppReviewDemoUITests.testC_storeScreens`) in the 3D tablet frame (`render.py --platform ipad`). The app supports iPhone and iPad (`TARGETED_DEVICE_FAMILY = "1,2"`); iPad support can't be removed after release.
- Apple Watch (422×514, Ultra 3): `marketing/appstore/watch/01-nutrition-422x514.png`.
- App icon 1024×1024 comes from the build.

## Version / Build
```
MARKETING_VERSION: 1.0
CURRENT_PROJECT_VERSION: 3
Bundle ID: com.ayuvo.health (widgets .widgets, share .share, watch .watch, watch widgets .watch.widgets)
App Store ID: 6811947230
```

## Screenshots
See `marketing/storyboard.json` and `marketing/seed/README.md`. Store sets: `marketing/store/ios/6.9/01..08.png` (1290×2796); Apple Watch `marketing/appstore/watch/*.png`; icon `marketing/appstore/icon-1024.png`.
