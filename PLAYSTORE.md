# Play Store Listing

Google Play Console listing copy for **Ayuvo Android 1.0 (versionCode 2)**, package `com.ayuvo.health`. Char counts are tracked because Play Console enforces hard caps. Support: yaaratech@gmail.com · Publisher: Yaara Tech.

**Where to paste each field:** App name / Short description / Full description → Grow → Store presence → Main store listing. What's new → Releases → Production → Create new release → Release notes.

---

## 1. App Name (30 char cap) — 27 chars
```
Ayuvo – AI Health Companion
```

## 2. Short Description (80 char cap) — 78 chars
Play blocks promotion keywords ("free", "best", "#1"); this copy avoids them.
```
Nutrition, workouts, fasting, water and health data — private, on your device.
```

## 3. Full Description (4000 char cap) — 3746 chars; English only, the app itself is localised in 18 languages
```
Ayuvo is a private, all-in-one health companion. Log meals, workouts, medications and health records, mirror your Health Connect data into one hub, and ask an AI coach that can see the whole picture — with no account and no Ayuvo servers.

NUTRITION
Snap up to 10 photos of a meal, scan a barcode (Open Food Facts), speak it, type it, reuse a saved meal or enter it manually. Review calories, macros and 30+ nutrients before saving, adjust servings and preview "What if?". Personalised targets you can override.

WORKOUTS
A workout diary with sets, reps, weight and RPE and a 1,300-exercise library with photos and animations. Record walks, runs, rides and hikes with GPS: route, splits, pace and elevation, saved to Health Connect.

HEALTH DATA HUB
With your permission Ayuvo mirrors the Health Connect data you grant — activity, heart, sleep, vitals, body measurements, cycle tracking, mindfulness, mobility, hearing and symptoms where available, nutrition and hydration — into a local hub with Home tiles, day-to-year charts and Data Sources. Ayuvo writes the nutrition, weight, height, body fat and active calories you log, and the outdoor workouts you record. You can also connect Google Health (read-only). Health data is kept on this device and excluded from Google backup and device transfer.

HEALTH RECORDS & MEDICATIONS
Keep lab reports, prescriptions, consultation notes, discharge summaries, imaging reports and bills in one place. Scan, photograph or share them to Ayuvo; text is read on your phone, test values are found and tracked across reports, and you can share with identifiers blacked out. Add medicines with schedules, get reminders and keep a dose log.

CYCLE TRACKING
Optional period tracker with flow, symptoms, mood and notes, calendar estimates of your next period and fertile window (estimates only, not birth control), discreet reminders and Health Connect sync.

CAMERA MEASUREMENTS
Finger and face scans estimate heart rate, heart rate variability and respiratory rate, with a confidence for every value. General wellness, not a medical measurement.

FASTING & WATER
Optional fasting timer (1–168 h) and water goals with reminders and widgets.

AI COACH — BRING YOUR OWN KEY
Chat that can use your food log, workouts, weight trend and fasts and — only with your explicit toggles — your Health data and records. Use Google Gemini, OpenAI, Anthropic, xAI, OpenRouter, Together AI, Groq, Hugging Face, Fireworks AI, DeepInfra, Mistral, DeepSeek, Cerebras, Ollama or any OpenAI-compatible endpoint with your own key, or run a model fully on-device. Keys are encrypted with the Android Keystore; requests go straight from your phone to the provider.

PARTNER HEALTH SYNC
Pair with someone you trust by QR code and share only the categories you choose, directly between your phones on the same Wi-Fi. No server, no account.

WIDGETS & MORE
Home screen widgets, app shortcuts, sharing into Ayuvo from any app, optional Google Drive backup (never includes Health data, medications or coach chat), 18 languages and 18 accent colours.

PRIVACY
No account, no ads, no analytics, no tracking. Your data is stored on this device. It leaves your phone only when you act: a request to the AI provider you chose, a barcode lookup, exercise images, a model download, Google Health sync, a partner you paired with, your own Google Drive backup, or a file you export or share. Export everything or delete everything from Settings.

Ayuvo is not a medical device and does not give medical advice. Estimates come from AI and formulas; talk to a clinician before changing diet, training or medication.

Terms: https://ayuvo-health.web.app/terms
Privacy: https://ayuvo-health.web.app/privacy
Support: https://ayuvo-health.web.app/support
```

## 4. What's New (Release notes)
```
<en-US>
Ayuvo 1.0 — your whole health in one private app.
• Log meals by photo, barcode, voice, text or manual entry with 30+ nutrients
• Workouts from a 1,300-exercise library, plus GPS walks, runs, rides and hikes
• Mirror your Health Connect data into a local hub with charts and history
• Health records, medications with reminders, optional cycle tracking
• AI coach on your own key, or fully on-device
</en-US>
```

## 5. Categorisation
```
App category: Health & Fitness
Tags: Nutrition, Fitness tracker, Health tracker
```

## 6. Contact details
```
Email: yaaratech@gmail.com
Website: https://ayuvo-health.web.app/support
Privacy policy: https://ayuvo-health.web.app/privacy
```

## 7. App content declarations
- **Privacy policy:** https://ayuvo-health.web.app/privacy
- **App access:** All functionality is available without special access. Note for reviewers (Instructions field): AI features require the user's own provider key; a Google Gemini key for testing is provided here: `<paste demo key>` — Settings → AI Providers → Google Gemini → model gemini-3.5-flash-lite → paste → Save.
- **Ads:** No. **Advertising ID:** No (the app does not use it).
- **Content rating (IARC):** Everyone. **Target audience:** 13+ (not designed for children). **News app:** No. **Government app:** No. **Financial features:** No.
- **Health apps → Health Connect declaration:** see below.

## 8. Data safety form

**Recommended answer: "Does your app collect or share any of the required user data types?" → No.** Rationale to keep on file:

- Health Records: documents the user adds are stored on device and excluded from Android backup and device transfer. They leave the phone only on a user action — AI processing with the user's own key (only in Cloud AI / Ask me mode, chosen by the user), Coach after a separate opt-in (patient name, address, phone and ID numbers are never sent), an explicit Share (with optional redaction), an archive the user exports, or the opt-in "Include Health Records in Google Drive backup" which writes to the user's own Drive app folder.
- Google's definitions exempt (a) data processed only on the device, (b) user-initiated transfers where the user reasonably expects the data to go to the specific third party they selected (here: the BYOK AI/speech provider, Open Food Facts on scan, GitHub/Hugging Face file fetches), and (c) transfers to the user's own account storage (Google Drive `appDataFolder`, only after the user signs in at that toggle).
- The developer operates no server and integrates no analytics, crash or ads SDK; nothing is collected by or shared with the developer.
- Still answer the security questions: **Data encrypted in transit: Yes** (HTTPS with modern TLS; a user-typed local-network Ollama address is the only plain-HTTP exception and is disclosed). **Users can request deletion: Yes** — Settings → Delete All Data (on device) and the support email.
- **Conservative alternative** (only if you prefer to over-disclose; then mirror it on App Store Connect): declare Health info, Fitness info, Photos and Files & docs as *collected, optional, for app functionality, not shared, encrypted in transit, deletable*.

## 9. Health Connect declaration (App content → Health apps)
- **Use case:** fitness & wellness — nutrition tracking, workout logging (including GPS outdoor workouts), cycle tracking and displaying the user's own health data in the Health Data hub.
- **Read permissions requested (each optional, granted per category by the user):** activity (steps, distance, active/total calories, floors, elevation gained, exercise sessions, planned exercise, speed, power, wheelchair pushes), body measurements (weight, height, body fat, lean body mass, bone mass, body water mass, basal metabolic rate), heart (heart rate, resting heart rate, heart rate variability, blood pressure, VO2 max), sleep, vitals (blood glucose, body temperature, oxygen saturation, respiratory rate, skin temperature), cycle tracking (menstruation, intermenstrual bleeding, ovulation test, cervical mucus, sexual activity, basal body temperature), mindfulness, nutrition, hydration.
- **`READ_HEALTH_DATA_HISTORY`:** "Users can view and chart their full history in the Health Data hub (day to year ranges, Show All Data), not only the last 30 days."
- **`READ_HEALTH_DATA_IN_BACKGROUND`:** "Keeps the on-device Health Data hub and daily insights (recovery, sleep) current when the app is not open, via a periodic WorkManager job; data stays on the device."
- **Write permissions:**
  - What the user logs in Ayuvo: nutrition, weight, height, body fat, active calories burned (water and fasting logged in Ayuvo are never written).
  - Outdoor workouts the user records: exercise session, exercise route, distance, elevation gained, total calories burned, heart rate during the session.
  - Cycle tracking (when its Health Connect sync is on): menstruation.
  - Optional Google Health connection (read-only from Google), mirrored into Health Connect only when the user leaves "Write to Health Connect" on: steps, floors, heart rate, resting heart rate, heart rate variability, oxygen saturation, blood glucose, body temperature, VO2 max, respiratory rate, sleep, hydration.
- **Justification:** "The user views, charts, searches and exports their own data in the Health Data hub and lets an optional AI coach summarise it only after a visible consent toggle. Data is stored in an on-device database that is excluded from backup and device transfer; nothing is sent to the developer."
- **Privacy policy link reachable in-app:** the Health Connect rationale activity and Settings → Legal open https://ayuvo-health.web.app/privacy, whose section 5 covers Health Connect purpose, read/write types, storage, sharing and revocation.

## 10. Other permission declarations (App content)
- **Foreground service — location (`FOREGROUND_SERVICE_LOCATION`):** "Records the route, distance and pace of an outdoor walk, run, ride or hike that the user starts. The service runs only while that workout is recording, shows an ongoing notification with Pause/Resume, Lap and End, and stops when the user ends it." Play asks for a short video: start a GPS workout from Browse → Activity → Workouts, lock the screen, show the notification, finish the workout.
- **Location:** fine and coarse location only while recording a workout; no `ACCESS_BACKGROUND_LOCATION`.
- **Exact alarms (`SCHEDULE_EXACT_ALARM`):** medication reminders must fire at the time the user scheduled. The Medications screen asks the user to allow it (system "Alarms & reminders" page) and falls back to inexact `setAndAllowWhileIdle` reminders if denied (the permission is not pre-granted on Android 14+).
- **Camera:** meals, barcodes, health record scans, medication packs, partner pairing QR codes, and finger/face camera measurements (heart rate, HRV, respiratory rate; general wellness, frames are never stored). Release builds do not estimate blood pressure or blood oxygen.
- **Microphone:** voice meal entry. **Notifications:** reminders and the live workout notification.
- **AI-generated content:** the Coach (and meal analysis) shows answers generated by the user's own AI provider. Every Coach answer has an in-app **Report** action (flag icon): the user picks a reason (offensive, harmful, incorrect, other) without leaving the app, the answer is marked reported, and the user can email it to yaaratech@gmail.com. The Coach's system prompt grounds answers in the user's own data, tells it to avoid medical advice and never diagnose, and to suggest a clinician when readings look concerning.
- **Local network (Partner Health Sync):** paired phones exchange user-chosen summaries directly over the same Wi-Fi; no server. This is a user-initiated transfer to a device the user chose, so it is not "sharing" on the Data safety form.

Copy rule for every listing text: never claim data "never leaves the device"; say "kept on this device, never stored in Drive backup, shared with your AI provider only through Coach".

## 11. Graphics
- Icon 512×512 (no alpha): `marketing/play/icon-512.png`
- Feature graphic 1024×500: `marketing/play/feature-graphic-1024x500.png` (3D scene, `python3 scripts/brand/store3d/render.py --platform feature`)
- Phone screenshots 1080×1920, in this order: `marketing/store/android/phone/01-nutrition.png` … `07-export-data.png` (real emulator captures in `marketing/raw/android/`, composed by `python3 scripts/brand/store3d/render.py --platform android`)
- Developer page header 4096×2304: `marketing/play/developer-header-4096x2304.jpg`
