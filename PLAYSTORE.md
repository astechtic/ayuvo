# Play Store Listing

Google Play Console listing copy for **Ayuvo Android 1.0 (versionCode 1)**, package `com.ayuvo.health`. Char counts are tracked because Play Console enforces hard caps. Support: yaaratech@gmail.com · Publisher: Yaara Tech.

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

## 3. Full Description (4000 char cap; English only, the app itself is localised in 18 languages)
```
Ayuvo is a private, all-in-one health companion. Log meals by photo, barcode, voice or text, plan and log workouts, mirror your Health Connect data into one hub, track fasting and water, and ask an AI coach that can see the whole picture — all on your phone, with no account and no Ayuvo servers.

NUTRITION
• Camera or Photos — up to 10 images with an optional note
• Barcode — Open Food Facts lookup
• Voice, text, saved meals, copy from another day, manual entry
• Review calories, macros and 30+ nutrients before saving; adjust servings; preview "What if?"
• Personalised targets (Mifflin-St Jeor / Katch-McArdle) you can override

WORKOUTS
• Day-by-day diary with sets, reps, weight and RPE
• Calculated calorie burn with history
• 1,300-exercise library with photos and animations, filtered by body part, target muscle and equipment

HEALTH DATA HUB
With your permission Ayuvo mirrors the Health Connect data you grant — activity, heart, sleep, vitals, body measurements, cycle tracking, mindfulness, nutrition and hydration (mobility, hearing and symptoms where available) — into a local hub with Home tiles, day/week/month/year charts, Show All Data and Data Sources. Ayuvo writes only the nutrition, weight, height, body fat and active calories you log. Health data is kept on this device and excluded from Google backup and device transfer.

FASTING & WATER
• Optional fasting with a 1–168 hour goal, a persistent timer and editable history
• Optional water goals with quick logging, reminders and Glance widgets
• Both stay local and are never written to Health Connect

AI COACH — BRING YOUR OWN KEY
Multi-turn chat that can pull your food log, workouts, weight trend, fasts and — only with your explicit toggle — your Health data. Use Google Gemini, OpenAI, Anthropic, xAI, OpenRouter, Together AI, Groq, Hugging Face, Fireworks AI, DeepInfra, Mistral, DeepSeek, Cerebras, Ollama or any OpenAI-compatible endpoint with your own key, or run Gemma 4 E2B fully on-device. Keys stay in EncryptedSharedPreferences backed by the Android Keystore; requests go straight from your phone to the provider.

WIDGETS & MORE
Calorie, Protein, Today and Water widgets, app shortcuts, 18 languages, dark mode, metric and imperial units, 18 accent colours with matching icons.

PRIVACY
No account, no ads, no analytics, no tracking. Everything you log stays on your device. Data leaves your phone only when you act: sending a request to the AI provider you chose, scanning a barcode, browsing exercise images, downloading a model, or turning on your own Google Drive backup (which never includes Health data or coach chat). Export everything, delete everything — Settings → Delete All Data.

Ayuvo is not a medical device and does not give medical advice. Estimates come from AI and formulas; talk to a clinician before changing diet, training or medication.

Terms: https://ayuvo-health.web.app/terms
Privacy: https://ayuvo-health.web.app/privacy
Support: https://ayuvo-health.web.app/support
```

## 4. What's New (Release notes)
```
<en-US>
Ayuvo 1.0 — nutrition, workouts, the Health data hub, fasting, water and an AI coach in one private app.
• Log meals by photo, barcode, voice, text or manual entry with 30+ nutrients
• Plan and log workouts from a 1,300-exercise library
• Mirror your Health Connect data into a local hub with charts and history
• Optional fasting timer and water tracking
• AI coach on your own key, or fully on-device
• Widgets, app shortcuts, 18 languages
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

- Google's definitions exempt (a) data processed only on the device, (b) user-initiated transfers where the user reasonably expects the data to go to the specific third party they selected (here: the BYOK AI/speech provider, Open Food Facts on scan, GitHub/Hugging Face file fetches), and (c) transfers to the user's own account storage (Google Drive `appDataFolder`, only after the user signs in at that toggle).
- The developer operates no server and integrates no analytics, crash or ads SDK; nothing is collected by or shared with the developer.
- Still answer the security questions: **Data encrypted in transit: Yes** (HTTPS with modern TLS; a user-typed local-network Ollama address is the only plain-HTTP exception and is disclosed). **Users can request deletion: Yes** — Settings → Delete All Data (on device) and the support email.
- **Conservative alternative** (only if you prefer to over-disclose; then mirror it on App Store Connect): declare Health info, Fitness info and Photos as *collected, optional, for app functionality, not shared, encrypted in transit, deletable*.

## 9. Health Connect declaration (App content → Health apps)
- **Use case:** fitness & wellness — nutrition tracking, workout logging and displaying the user's own health data in the Health Data hub.
- **Read permissions requested (each optional, granted per category by the user):** activity (steps, distance, active/total calories, floors, exercise sessions, speed, power, cadence), body measurements (weight, height, body fat, lean body mass, bone mass, body water mass, basal metabolic rate), heart (heart rate, resting heart rate, heart rate variability, blood pressure, VO2 max), sleep, vitals (blood glucose, body temperature, oxygen saturation, respiratory rate, skin temperature), cycle tracking (menstruation, intermenstrual bleeding, ovulation test, cervical mucus, sexual activity, basal body temperature), mindfulness, nutrition, hydration, wheelchair pushes, elevation gained, plus health data history; mobility, hearing and symptoms categories appear only where Health Connect exposes them.
- **Write permissions:** nutrition, weight, height, body fat, active calories burned.
- **Justification:** "The user views, charts, searches and exports their own data in the Health Data hub and lets an optional AI coach summarise it only after a visible consent toggle. Data is stored in an on-device database that is excluded from backup and device transfer; nothing is sent to the developer."
- **Privacy policy link reachable in-app:** the Health Connect rationale activity and Settings → Legal open https://ayuvo-health.web.app/privacy, whose section 5 covers Health Connect purpose, read/write types, storage, sharing and revocation.

Copy rule for every listing text: never claim data "never leaves the device"; say "kept on this device, never stored in Drive backup, shared with your AI provider only through Coach".

## 10. Graphics
- Icon 512×512 (no alpha): `marketing/play/icon-512.png`
- Feature graphic 1024×500: `marketing/play/feature-graphic-1024x500.png`
- Phone screenshots 1080×2400: `marketing/store/android/phone/01..08.png` (see `marketing/storyboard.json`)
- Developer page header 4096×2304: `marketing/play/developer-header-4096x2304.jpg`
