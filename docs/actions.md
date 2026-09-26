# Ayuvo Actions (Siri, Shortcuts, Android, Coach)

Ayuvo exposes one catalog of typed actions that Siri, Apple Shortcuts, Android shortcuts / deep links / assistant surfaces and Ayuvo Coach all call. The catalog is `shared/actions/action_catalog.json`; the reference algorithm is `scripts/actions_reference.py`; `python3 scripts/actions_contract_check.py` checks the catalog, the vectors in `shared/actions/test-vectors/`, the platform copies and the generated part of this page (`--write` regenerates them).

## Rules that never bend
- Every surface converts its own input into an `ActionRequest(id, params)` and shows the `ActionResult`. The business logic lives once per platform in the action handlers, which call the same stores and repositories the app uses. No surface has its own logic.
- Local-first. Actions read and write on-device data only. The one exception is **Log Food with a description**, which sends the description to your configured AI provider (the same as typing it in the app); it is marked `ai_provider` and always asks first.
- Health reads never prompt for Apple Health / Health Connect access from an assistant. Without access the action returns `permission_required` and the app explains how to grant it.
- Health values never appear in shortcut titles, phrases, donated shortcuts, dynamic shortcut labels or entity type names. They appear only in the result, after the device is unlocked (`requires_unlock`).
- No delete actions. Medication actions can only mark one of today's scheduled doses taken, skipped or snoozed (10/30/60 min), always with confirmation. Nothing can create or edit a medication, change a dose or strength, or give medical advice.
- Goal changes always ask first. Writes from deep links (any app can send one) and Coach proposals always ask first.
- Coach can **read** with the `coach_mode: read` actions and can **propose** `coach_mode: propose` writes. A proposal shows as a card in the chat and runs only when you tap Confirm. Coach never proposes medication or goal changes.
- No analytics.

## Request lifecycle
1. The surface builds the request. Deep links: `ayuvo://action/<id>?k=v&…` and `ayuvo://open/<section>`; query values are percent-decoded with `+` as a space; a duplicate key is rejected. Links with any other host (`ayuvo://log/…`, `ayuvo://metric/…`) keep going to the widget router.
2. **Validate** (same order on every platform): unknown action → surface not allowed for this action (`app` is always allowed) → unknown parameter → per parameter in catalog order: missing (defaults, then the user's unit preference for `default_pref`), type, enum/allowed value, length → ranges (checked in the canonical unit: ml, kg, cm; `goals.update` ranges depend on the goal) → `requires_one_of`.
3. **Confirm** when the action is a write that does not just open the app and: its confirmation is `always`; or it is `when_ai` and the AI path is used; or the source is a deep link or Coach.
4. **Execute** in the handler, then return the structured result (fields listed per action below) plus a short spoken/visible summary.

Error codes: `unknown_action`, `not_allowed`, `unknown_param`, `missing_param`, `bad_type`, `bad_enum`, `bad_value`, `too_long`, `out_of_range`, `requires_one_of` (validation) and `permission_required`, `not_found`, `conflict` (e.g. food while a fast is running, a second fast), `unavailable` (feature off) from execution.

Date ranges are half-open `[from, to)` local-midnight intervals in the device time zone; weeks start on the user's week-start setting. `last_7_days` and `last_30_days` include today.

## Composing actions in Apple Shortcuts
Every GET returns a typed value (a number or measurement, an entity, or a list of entities) so Shortcuts can pass it on:
- **Weight → BMI:** Get Body Composition → Get BMI → Show Result. Or Get Weight → Get Profile (height) → Calculate.
- **Protein check:** Get Nutrient (Protein, Today) → If Remaining > 0 → Ask Ayuvo Coach ("How can I get more protein today?").
- **Hydration nudge:** Get Water → If Remaining > 500 → Show Notification.
- **Sleep:** Get Last Night's Sleep → If Hours < 7 → Show Notification.
- **Labs:** Search Health Records ("lipid") → Choose from List → Open Health Record; or Get Lab Value (HbA1c) → If Value > 6.5 → Ask Ayuvo Coach.
- **Doses:** Get Next Dose → Mark Dose (Taken). Ayuvo asks you to confirm before marking.
- **Filter and calculate:** Get Health Samples (Weight, Last 30 Days) → Calculate Statistics (Average).

## Platform support
### iOS
App Intents in the app process (the data is in the app container, so there is no intents extension): one intent per action with `siri` or `shortcuts` in its surfaces, app entities with queries for pickers and search, and enums for every catalog enum, so no parameter is typed as raw text. At most 10 App Shortcuts (Siri phrases) are registered, which is Apple's limit. Every other action is available in the Shortcuts app and to Siri through "Run <shortcut>".

"Today's Nutrition" (`nutrition.summary.get`) returns every row of Nutrition Details on iOS. That means calories, protein, carbs and fat with their targets, water when tracked, and each detailed nutrient with its goal. Each detailed nutrient is a field named `<key>_<unit>` (e.g. `sodium_mg`, `vitamin_d_mcg`), and the result also has a `nutrients` list and a `details_text` summary. Siri reads the macros and water, plus only the detailed nutrients that were logged.

### Android
- `ayuvo://action/…` and `ayuvo://open/…` deep links and the `com.ayuvo.health.ACTION` intent (extras: `action_id` + string parameters), handled by MainActivity before the Records share handler.
- Static shortcuts in `res/xml/shortcuts.xml` (Log water, Start fast, Today's summary, Log weight) on every launcher alias, and dynamic shortcuts for recently used actions. Labels never contain values. `targetPackage` cannot be templated, so the debug build types have their own copy; a test checks they differ only in the package.
- App Actions capabilities (`OPEN_APP_FEATURE` and the health and fitness built-in intents) arrive as `ayuvo://assistant/<bii>` and are mapped onto catalog actions.
- Every write that comes from outside the app (shortcut, assistant, deep link) shows a confirm sheet. This is stricter than the catalog rule because any app can send these intents. A write with no value (e.g. "Log water" without an amount) opens the in-app logger instead of failing.
- GET actions open the catalog `screen` and show the result in a snackbar.
- AppFunctions is not used yet. Its latest release (1.0.0-alpha12, September 2026) is still alpha and needs KSP and API 34+, and the Gemini integration is a private preview.

### Coach
Read tools: every `coach_mode: read` action, named by its `coach_tool`. Writes: one `propose_action` tool for `coach_mode: propose` actions. The on-device model gets no tools.

## Platform limitations
- Apple allows 10 App Shortcuts per app. Actions outside those 10 have no built-in Siri phrase, but they still work in the Shortcuts app, and a shortcut you build can be run by voice.
- Siri cannot read Apple Health data or Ayuvo's health values while the iPhone is locked. Ayuvo asks you to unlock first.
- Android has no equivalent of Apple Shortcuts composition. Results are shown in the app and cannot be piped into another action.
- Google began replacing Assistant with Gemini on phones in September 2026. The App Actions capabilities are declared and follow Google's current docs, but Gemini may not invoke them. Voice invocation has not been tested.
- Dynamic shortcuts are not pushed to Assistant. That needs `core-google-shortcuts`, which pulls in Google Play services.

## Catalog
<!-- BEGIN GENERATED CATALOG (scripts/actions_contract_check.py --write) -->

### Index

| Action | Kind | Title | Confirmation | Coach |
|---|---|---|---|---|
| [`health.metric.get`](#healthmetricget) | GET | Get Health Data | never | none |
| [`health.metric.latest`](#healthmetriclatest) | GET | Get Latest Value | never | none |
| [`health.metric.samples`](#healthmetricsamples) | GET | Get Health Samples | never | none |
| [`health.sleep.lastNight`](#healthsleeplastnight) | GET | Get Last Night's Sleep | never | none |
| [`nutrition.summary.get`](#nutritionsummaryget) | GET | Get Nutrition | never | none |
| [`nutrition.nutrient.get`](#nutritionnutrientget) | GET | Get Nutrient | never | none |
| [`nutrition.meals.list`](#nutritionmealslist) | GET | Get Logged Foods | never | none |
| [`nutrition.targets.get`](#nutritiontargetsget) | GET | Get Nutrition Targets | never | read (`get_nutrition_targets`) |
| [`water.get`](#waterget) | GET | Get Water | never | read (`get_water_intake`) |
| [`fasting.status.get`](#fastingstatusget) | GET | Get Fasting Status | never | read (`get_fasting_status`) |
| [`fasting.history.list`](#fastinghistorylist) | GET | Get Fasting History | never | none |
| [`weight.get`](#weightget) | GET | Get Weight | never | none |
| [`weight.history`](#weighthistory) | GET | Get Weight History | never | none |
| [`body.composition.get`](#bodycompositionget) | GET | Get Body Composition | never | read (`get_body_composition`) |
| [`workout.today.get`](#workouttodayget) | GET | Get Today's Workout | never | none |
| [`workout.history.list`](#workouthistorylist) | GET | Get Workout History | never | none |
| [`workout.exercise.stats`](#workoutexercisestats) | GET | Get Exercise Stats | never | read (`get_exercise_stats`) |
| [`records.search`](#recordssearch) | SEARCH | Search Health Records | never | none |
| [`records.latest`](#recordslatest) | GET | Get Latest Health Record | never | none |
| [`records.labValue.get`](#recordslabvalueget) | GET | Get Lab Value | never | none |
| [`medications.today.list`](#medicationstodaylist) | GET | Get Today's Doses | never | none |
| [`medications.next.get`](#medicationsnextget) | GET | Get Next Dose | never | none |
| [`medications.history.list`](#medicationshistorylist) | GET | Get Dose History | never | none |
| [`medications.adherence.get`](#medicationsadherenceget) | GET | Get Adherence | never | none |
| [`goals.get`](#goalsget) | GET | Get Goals | never | read (`get_goals`) |
| [`profile.summary.get`](#profilesummaryget) | GET | Get Profile | never | none |
| [`search.universal`](#searchuniversal) | SEARCH | Search Ayuvo | never | read (`search_ayuvo`) |
| [`nutrition.food.log`](#nutritionfoodlog) | SET | Log Food | when_ai | propose |
| [`nutrition.food.logSaved`](#nutritionfoodlogsaved) | SET | Log Saved Food | never | propose |
| [`water.log`](#waterlog) | SET | Log Water | never | propose |
| [`weight.log`](#weightlog) | SET | Log Weight | never | propose |
| [`body.fat.log`](#bodyfatlog) | SET | Log Body Fat | never | propose |
| [`body.measurement.log`](#bodymeasurementlog) | SET | Log Body Measurement | never | propose |
| [`fasting.start`](#fastingstart) | SET | Start Fast | never | propose |
| [`fasting.stop`](#fastingstop) | SET | End Fast | never | propose |
| [`workout.start`](#workoutstart) | SET | Start Workout | never | none |
| [`workout.finish`](#workoutfinish) | SET | Finish Workout | never | none |
| [`workout.set.log`](#workoutsetlog) | SET | Log Workout Set | never | propose |
| [`medication.dose.mark`](#medicationdosemark) | SET | Mark Dose | always | none |
| [`goals.update`](#goalsupdate) | SET | Update Goal | always | none |
| [`open.section`](#opensection) | OPEN | Open Ayuvo | never | none |
| [`open.metric`](#openmetric) | OPEN | Open Metric | never | none |
| [`open.record`](#openrecord) | OPEN | Open Health Record | never | none |
| [`open.coach`](#opencoach) | OPEN | Ask Ayuvo Coach | never | none |
| [`insights.recovery.get`](#insightsrecoveryget) | GET | Get Recovery | never | read (`get_recovery`) |
| [`insights.healthAge.get`](#insightshealthageget) | GET | Get Health Age | never | read (`get_health_age`) |
| [`insights.dailyReview.get`](#insightsdailyreviewget) | GET | Get Daily Review | never | read (`get_daily_review`) |

### Domain: body

#### `weight.get`

**Get Weight** · GET. Your latest logged weight.

- Output: scalar — `value`, `unit`, `t_ms`
- Permissions: local
- Confirmation: none
- Requires unlocked device: yes · Opens the app: no
- Surfaces: siri, shortcuts, android, deeplink · Screen: `metric:app:weight`
- Siri: What is my current weight in Ayuvo?
- Shortcuts: Get Weight → Get Body Composition → Show BMI
- Android: Hey Google, show my weight in Ayuvo
- Android: `ayuvo://action/weight.get`

#### `weight.history`

**Get Weight History** · GET. Weights in a date range with the change from first to last.

| Parameter | Type | Meaning |
|---|---|---|
| `range` | date_range (today, yesterday, this_week, last_week, last_7_days, last_30_days, this_month, last_month); optional; default "last_30_days" | Date range. |

- Output: record — `first_kg`, `last_kg`, `change_kg`, `count`, `entries`
- Permissions: local
- Confirmation: none
- Requires unlocked device: yes · Opens the app: no
- Surfaces: siri, shortcuts, android, deeplink · Screen: `metric:app:weight`
- Siri: Show my weight for the last 30 days in Ayuvo
- Siri: What was my weight trend this month in Ayuvo?
- Shortcuts: Get Weight History (Last 30 Days) → Get Change
- Android: `ayuvo://action/weight.history?range=this_month`

#### `body.composition.get`

**Get Body Composition** · GET. Latest weight, body fat, height and the BMI calculated from them.

- Output: record — `weight_kg`, `body_fat_percent`, `height_cm`, `bmi`
- Permissions: local
- Confirmation: none
- Requires unlocked device: yes · Opens the app: no
- Surfaces: siri, shortcuts, android, deeplink, coach · Screen: `screen:body`
- Siri: What is my BMI in Ayuvo?
- Shortcuts: Get Body Composition → Get BMI → Show Result
- Android: `ayuvo://action/body.composition.get`

#### `weight.log`

**Log Weight** · SET. Log your weight now.

| Parameter | Type | Meaning |
|---|---|---|
| `value` | number; required; range 20–400 kg | Weight (range checked in kg). |
| `unit` | mass_unit (kg, lb); optional; default: your mass unit preference | Unit. Defaults to your weight unit. |

- Output: scalar — `value`, `unit`, `t_ms`
- Permissions: local
- Confirmation: none; deep links and Coach proposals always ask first
- Requires unlocked device: yes · Opens the app: no
- Surfaces: siri, shortcuts, android, deeplink, coach · Screen: `metric:app:weight`
- Siri: Log my weight in Ayuvo
- Siri: Log 70 kg as my weight in Ayuvo
- Shortcuts: Ask for Number → Log Weight (Provided Input, kg)
- Android: `ayuvo://action/weight.log?value=70&unit=kg`

#### `body.fat.log`

**Log Body Fat** · SET. Log your body fat percentage now.

| Parameter | Type | Meaning |
|---|---|---|
| `percent` | number; required; range 2–75 | Body fat (%). |

- Output: scalar — `value`, `unit`, `t_ms`
- Permissions: local
- Confirmation: none; deep links and Coach proposals always ask first
- Requires unlocked device: yes · Opens the app: no
- Surfaces: siri, shortcuts, android, deeplink, coach · Screen: `metric:app:body_fat`
- Siri: Log body fat in Ayuvo
- Shortcuts: Log Body Fat (18.5)
- Android: `ayuvo://action/body.fat.log?percent=18.5`

#### `body.measurement.log`

**Log Body Measurement** · SET. Log a tape measurement such as waist or hips.

| Parameter | Type | Meaning |
|---|---|---|
| `site` | body_site (neck, waist, hips, chest, upper_arm, thigh, calf, wrist); required | Where you measured. |
| `value` | number; required; range 10–300 cm | Measurement (range checked in cm). |
| `unit` | length_unit (cm, in); optional; default: your length unit preference | Unit. |

- Output: scalar — `value`, `unit`, `t_ms`
- Permissions: local
- Confirmation: none; deep links and Coach proposals always ask first
- Requires unlocked device: yes · Opens the app: no
- Surfaces: siri, shortcuts, android, deeplink, coach · Screen: `screen:body`
- Siri: Log my waist in Ayuvo
- Shortcuts: Log Body Measurement (Waist, 82 cm)
- Android: `ayuvo://action/body.measurement.log?site=waist&value=82&unit=cm`

### Domain: fasting

#### `fasting.status.get`

**Get Fasting Status** · GET. Whether a fast is running, how long it has run and time left to your goal.

- Output: record — `active`, `started_ms`, `elapsed_s`, `goal_s`, `remaining_s`, `percent`, `reached`
- Permissions: local
- Confirmation: none
- Requires unlocked device: yes · Opens the app: no
- Surfaces: siri, shortcuts, android, deeplink, coach · Screen: `screen:fasting`
- Siri: How long have I been fasting in Ayuvo?
- Shortcuts: Get Fasting Status → If Reached → Show Notification
- Android: `ayuvo://action/fasting.status.get`

#### `fasting.history.list`

**Get Fasting History** · GET. Completed fasts in a date range.

| Parameter | Type | Meaning |
|---|---|---|
| `range` | date_range (today, yesterday, this_week, last_week, last_7_days, last_30_days, this_month, last_month); optional; default "last_30_days" | Date range. |
| `limit` | integer; optional; default 50; range 1–200 | Maximum fasts. |

- Output: list of `fasting_session` — `id`, `started_ms`, `ended_ms`, `duration_s`, `goal_s`, `reached`
- Permissions: local
- Confirmation: none
- Requires unlocked device: yes · Opens the app: no
- Surfaces: shortcuts, android, deeplink · Screen: `screen:fasting`
- Shortcuts: Get Fasting History (This Month) → Count
- Android: `ayuvo://action/fasting.history.list?range=this_month`

#### `fasting.start`

**Start Fast** · SET. Start a fast with a goal length.

| Parameter | Type | Meaning |
|---|---|---|
| `goal_hours` | number; optional; default 16; range 1–72 | Goal (hours). |

- Output: record — `active`, `started_ms`, `elapsed_s`, `goal_s`, `remaining_s`, `percent`, `reached`
- Permissions: local
- Confirmation: none; deep links and Coach proposals always ask first
- Requires unlocked device: yes · Opens the app: no
- Surfaces: siri, shortcuts, android, deeplink, coach · Screen: `screen:fasting`
- Siri: Start a fast in Ayuvo
- Siri: Start a 16 hour fast in Ayuvo
- Shortcuts: Start Fast (18 hours)
- Android: Hey Google, start a 16 hour fast in Ayuvo
- Android: `ayuvo://action/fasting.start?goal_hours=16`

#### `fasting.stop`

**End Fast** · SET. End the fast that is running.

- Output: entity of `fasting_session` — `id`, `started_ms`, `ended_ms`, `duration_s`, `goal_s`, `reached`
- Permissions: local
- Confirmation: none; deep links and Coach proposals always ask first
- Requires unlocked device: yes · Opens the app: no
- Surfaces: siri, shortcuts, android, deeplink, coach · Screen: `screen:fasting`
- Siri: End my fast in Ayuvo
- Shortcuts: End Fast → Get Duration
- Android: `ayuvo://action/fasting.stop`

### Domain: goals

#### `goals.get`

**Get Goals** · GET. Your daily goals and today's progress towards each.

- Output: record — `calories`, `protein_g`, `carbs_g`, `fat_g`, `water_ml`, `steps`, `goal_weight_kg`, `progress`
- Permissions: local
- Confirmation: none
- Requires unlocked device: yes · Opens the app: no
- Surfaces: siri, shortcuts, android, deeplink, coach · Screen: `tab:summary`
- Siri: How am I doing on my goals today in Ayuvo?
- Shortcuts: Get Goals → Get Water
- Android: `ayuvo://action/goals.get`

#### `profile.summary.get`

**Get Profile** · GET. Age, height, current weight and preferred units. No name or contact details.

- Output: record — `age`, `height_cm`, `weight_kg`, `mass_unit`, `volume_unit`
- Permissions: local
- Confirmation: none
- Requires unlocked device: yes · Opens the app: no
- Surfaces: shortcuts, android · Screen: `tab:settings`
- Shortcuts: Get Profile → Get Height
- Android: `ayuvo://action/profile.summary.get`

#### `goals.update`

**Update Goal** · SET. Change a daily goal: calories, protein, carbs, fat, water (ml) or steps.

| Parameter | Type | Meaning |
|---|---|---|
| `goal` | goal_kind (calories, protein, carbs, fat, water, steps); required | Which goal. |
| `value` | number; required; range by goal: calories 800–10000, carbs 10–1000, fat 10–500, protein 10–500, steps 500–100000, water 250–10000 | New daily goal. |

- Output: record — `goal`, `value`, `previous`
- Permissions: local
- Confirmation: always asks first; deep links and Coach proposals always ask first
- Requires unlocked device: yes · Opens the app: no
- Surfaces: siri, shortcuts, android, deeplink · Screen: `tab:settings`
- Siri: Change my water goal in Ayuvo
- Shortcuts: Update Goal (Water, 3000)
- Android: `ayuvo://action/goals.update?goal=water&value=3000`

### Domain: health

#### `health.metric.get`

**Get Health Data** · GET. A single value for any metric over a date range: total steps this week, average heart rate, latest weight.

| Parameter | Type | Meaning |
|---|---|---|
| `metric` | metric; required | Metric key, e.g. steps, heart_rate, app:water. |
| `range` | date_range (today, yesterday, this_week, last_week, last_7_days, last_30_days, this_month, last_month); optional; default "today" | Date range. |
| `aggregation` | aggregation (latest, sum, average, min, max, count); optional | How to combine samples. Defaults to the metric's natural aggregation. |

- Output: scalar — `value`, `unit`, `aggregation`, `sample_count`, `from_ms`, `to_ms`
- Permissions: health_read
- Confirmation: none
- Requires unlocked device: yes · Opens the app: no
- Surfaces: siri, shortcuts, android, deeplink · Screen: `metric:{metric}`
- Siri: What was my average heart rate this week in Ayuvo?
- Siri: How many steps today in Ayuvo?
- Shortcuts: Get Health Data (Heart Rate, This Week, Average) → If < 60 → Show Notification
- Android: `ayuvo://action/health.metric.get?metric=steps&range=today`

#### `health.metric.latest`

**Get Latest Value** · GET. The most recent sample of a metric and when it was recorded.

| Parameter | Type | Meaning |
|---|---|---|
| `metric` | metric; required | Metric key. |

- Output: scalar — `value`, `unit`, `t_ms`
- Permissions: health_read
- Confirmation: none
- Requires unlocked device: yes · Opens the app: no
- Surfaces: siri, shortcuts, android, deeplink · Screen: `metric:{metric}`
- Siri: What is my latest blood pressure in Ayuvo?
- Shortcuts: Get Latest Value (Blood Glucose) → Show Result
- Android: `ayuvo://action/health.metric.latest?metric=blood_glucose`

#### `health.metric.samples`

**Get Health Samples** · GET. The samples of a metric in a date range, newest first, for filtering and calculating in Shortcuts.

| Parameter | Type | Meaning |
|---|---|---|
| `metric` | metric; required | Metric key. |
| `range` | date_range (today, yesterday, this_week, last_week, last_7_days, last_30_days, this_month, last_month); optional; default "last_7_days" | Date range. |
| `limit` | integer; optional; default 100; range 1–500 | Maximum samples. |

- Output: list — `t_ms`, `value`, `unit`
- Permissions: health_read
- Confirmation: none
- Requires unlocked device: yes · Opens the app: no
- Surfaces: shortcuts, android · Screen: `metric:{metric}`
- Shortcuts: Get Health Samples (Weight, Last 30 Days) → Calculate Statistics (Average)
- Android: `ayuvo://action/health.metric.samples?metric=weight&range=last_30_days`

#### `health.sleep.lastNight`

**Get Last Night's Sleep** · GET. Time asleep last night from Apple Health or Health Connect.

- Output: record — `asleep_s`, `start_ms`, `end_ms`
- Permissions: health_read
- Confirmation: none
- Requires unlocked device: yes · Opens the app: no
- Surfaces: siri, shortcuts, android, deeplink · Screen: `metric:sleep`
- Siri: How long did I sleep last night in Ayuvo?
- Shortcuts: Get Last Night's Sleep → If Hours < 7 → Show Notification
- Android: `ayuvo://action/health.sleep.lastNight`

### Domain: insights

#### `insights.recovery.get`

**Get Recovery** · GET. This morning's Recovery score (0–100) with its main positive and negative signals, compared with your own baseline. A wellness estimate, not a diagnosis.

- Output: record — `status`, `score`, `label`, `recommendation`, `confidence`, `positives`, `negatives`, `training_load`
- Permissions: local, health_read
- Confirmation: none
- Requires unlocked device: yes · Opens the app: no
- Surfaces: siri, shortcuts, android, deeplink, coach · Screen: `screen:insights.recovery`
- Siri: What's my recovery in Ayuvo?
- Shortcuts: Get Recovery → If Score < 34 → Show Notification
- Android: `ayuvo://action/insights.recovery.get`

#### `insights.healthAge.get`

**Get Health Age** · GET. Your Ayuvo Health Age estimate next to your actual age, with its pace and the markers behind it. Ayuvo's own estimate, not a clinical age.

- Output: record — `status`, `actual_age`, `health_age`, `difference`, `confidence`, `pace`, `direction`, `markers`
- Permissions: local, health_read
- Confirmation: none
- Requires unlocked device: yes · Opens the app: no
- Surfaces: siri, shortcuts, android, deeplink, coach · Screen: `screen:insights.health_age`
- Siri: What's my health age in Ayuvo?
- Shortcuts: Get Health Age → Get Difference → Show Result
- Android: `ayuvo://action/insights.healthAge.get`

#### `insights.dailyReview.get`

**Get Daily Review** · GET. The Daily Health Review for a day: Day Score, what went well, what needs attention, what to improve and what to consider reducing.

| Parameter | Type | Meaning |
|---|---|---|
| `day` | review_day (today, yesterday); optional; default "today" | Day to review. |

- Output: record — `status`, `day`, `day_score`, `went_well`, `needs_attention`, `improve`, `reduce`, `not_logged`
- Permissions: local, health_read
- Confirmation: none
- Requires unlocked device: yes · Opens the app: no
- Surfaces: siri, shortcuts, android, deeplink, coach · Screen: `screen:insights.review`
- Siri: How did my day go in Ayuvo?
- Shortcuts: Get Daily Review → Get Day Score → Show Result
- Android: `ayuvo://action/insights.dailyReview.get`

### Domain: medications

#### `medications.today.list`

**Get Today's Doses** · GET. Today's scheduled doses and their status.

- Output: list of `dose` — `id`, `medication`, `scheduled_ms`, `status`
- Permissions: medications
- Confirmation: none
- Requires unlocked device: yes · Opens the app: no
- Surfaces: siri, shortcuts, android, deeplink · Screen: `screen:medications`
- Siri: What medicines do I have today in Ayuvo?
- Shortcuts: Get Today's Doses → Filter Status is due
- Android: `ayuvo://action/medications.today.list`

#### `medications.next.get`

**Get Next Dose** · GET. The next dose that is due or upcoming today.

- Output: entity of `dose` — `id`, `medication`, `scheduled_ms`, `status`
- Permissions: medications
- Confirmation: none
- Requires unlocked device: yes · Opens the app: no
- Surfaces: siri, shortcuts, android, deeplink · Screen: `screen:medications`
- Siri: When is my next medicine in Ayuvo?
- Shortcuts: Get Next Dose → Mark Dose (Taken)
- Android: `ayuvo://action/medications.next.get`

#### `medications.history.list`

**Get Dose History** · GET. Taken, skipped and missed doses in a date range.

| Parameter | Type | Meaning |
|---|---|---|
| `range` | date_range (today, yesterday, this_week, last_week, last_7_days, last_30_days, this_month, last_month); optional; default "last_7_days" | Date range. |
| `medication` | entity: medication; optional | Only this medication. |
| `limit` | integer; optional; default 100; range 1–500 | Maximum doses. |

- Output: list — `medication`, `scheduled_ms`, `status`, `taken_ms`
- Permissions: medications
- Confirmation: none
- Requires unlocked device: yes · Opens the app: no
- Surfaces: shortcuts, android, deeplink · Screen: `screen:medications`
- Shortcuts: Get Dose History (Last 7 Days) → Filter Status is missed → Count
- Android: `ayuvo://action/medications.history.list`

#### `medications.adherence.get`

**Get Adherence** · GET. Share of scheduled doses taken in a date range.

| Parameter | Type | Meaning |
|---|---|---|
| `range` | date_range (today, yesterday, this_week, last_week, last_7_days, last_30_days, this_month, last_month); optional; default "last_7_days" | Date range. |
| `medication` | entity: medication; optional | Only this medication. |

- Output: record — `taken`, `expected`, `percent`
- Permissions: medications
- Confirmation: none
- Requires unlocked device: yes · Opens the app: no
- Surfaces: siri, shortcuts, android, deeplink · Screen: `screen:medications`
- Siri: How well did I take my medicines this week in Ayuvo?
- Shortcuts: Get Adherence (This Week) → Get Percent
- Android: `ayuvo://action/medications.adherence.get?range=this_week`

#### `medication.dose.mark`

**Mark Dose** · SET. Mark one of today's scheduled doses as taken, skipped or snoozed. Never changes the medication or its dose.

| Parameter | Type | Meaning |
|---|---|---|
| `dose` | entity: dose; required | Today's dose. |
| `action` | dose_action (taken, skipped, snoozed); required | Taken, skipped or snoozed. |
| `snooze_minutes` | integer; optional; default 10; one of 10, 30, 60 | Snooze length. |

- Output: entity of `dose` — `id`, `medication`, `scheduled_ms`, `status`
- Permissions: medications
- Confirmation: always asks first; deep links and Coach proposals always ask first
- Requires unlocked device: yes · Opens the app: no
- Surfaces: siri, shortcuts, android · Screen: `screen:medications`
- Siri: Mark my morning medicine as taken in Ayuvo
- Shortcuts: Get Next Dose → Mark Dose (Taken)
- Android: com.ayuvo.health.ACTION extras: action_id=medication.dose.mark, dose=<id>, action=taken

### Domain: navigation

#### `open.section`

**Open Ayuvo** · OPEN. Open a section of Ayuvo.

| Parameter | Type | Meaning |
|---|---|---|
| `section` | section (summary, browse, health, nutrition, water, fasting, body, activity, workouts, insights, records, medications, coach, settings); required | Section. |

- Output: none — none
- Permissions: none
- Confirmation: none
- Requires unlocked device: no · Opens the app: yes
- Surfaces: siri, shortcuts, android, deeplink · Screen: `section:{section}`
- Siri: Open my health records in Ayuvo
- Siri: Open Coach in Ayuvo
- Shortcuts: Open Ayuvo (Medications)
- Android: Hey Google, open fasting in Ayuvo
- Android: `ayuvo://open/records`

#### `open.metric`

**Open Metric** · OPEN. Open the chart for a metric.

| Parameter | Type | Meaning |
|---|---|---|
| `metric` | metric; required | Metric key. |

- Output: none — none
- Permissions: none
- Confirmation: none
- Requires unlocked device: no · Opens the app: yes
- Surfaces: shortcuts, android, deeplink · Screen: `metric:{metric}`
- Shortcuts: Open Metric (Resting Heart Rate)
- Android: `ayuvo://action/open.metric?metric=resting_heart_rate`

#### `open.record`

**Open Health Record** · OPEN. Open a document in Health Records.

| Parameter | Type | Meaning |
|---|---|---|
| `record` | entity: health_record; required | Record. |

- Output: none — none
- Permissions: records
- Confirmation: none
- Requires unlocked device: yes · Opens the app: yes
- Surfaces: shortcuts, android · Screen: `record:{record}`
- Shortcuts: Search Health Records → Choose from List → Open Health Record
- Android: `ayuvo://action/open.record?record=<id>`

#### `open.coach`

**Ask Ayuvo Coach** · OPEN. Open Coach, optionally with a question typed in for you to send.

| Parameter | Type | Meaning |
|---|---|---|
| `prompt` | string; optional | Question to prefill. Not sent until you tap Send. |

- Output: none — none
- Permissions: none
- Confirmation: none
- Requires unlocked device: no · Opens the app: yes
- Surfaces: siri, shortcuts, android, deeplink · Screen: `tab:coach`
- Siri: Open Ayuvo Coach
- Shortcuts: Get Nutrient (Protein) → If < Target → Ask Ayuvo Coach ("How do I get more protein today?")
- Android: `ayuvo://action/open.coach?prompt=Plan%20my%20dinner`

### Domain: nutrition

#### `nutrition.summary.get`

**Get Nutrition** · GET. Calories, protein, carbs, fat and fiber for a date range, with your targets and what's left today.

| Parameter | Type | Meaning |
|---|---|---|
| `range` | date_range (today, yesterday, this_week, last_week, last_7_days, last_30_days, this_month, last_month); optional; default "today" | Date range. |

- Output: record — `calories`, `protein_g`, `carbs_g`, `fat_g`, `fiber_g`, `calorie_target`, `protein_target_g`, `calories_remaining`, `entry_count`
- Permissions: local
- Confirmation: none
- Requires unlocked device: yes · Opens the app: no
- Surfaces: siri, shortcuts, android, deeplink · Screen: `screen:nutrition`
- Siri: How many calories have I eaten today in Ayuvo?
- Siri: Show my health summary for today in Ayuvo
- Shortcuts: Get Nutrition (Today) → Get Calories Remaining → Show Result
- Android: Hey Google, show today's calories in Ayuvo
- Android: `ayuvo://action/nutrition.summary.get?range=today`

#### `nutrition.nutrient.get`

**Get Nutrient** · GET. One nutrient (calories, protein, carbs, fat or fiber) over a date range, with its daily target.

| Parameter | Type | Meaning |
|---|---|---|
| `nutrient` | nutrient (calories, protein, carbs, fat, fiber); required | Nutrient. |
| `range` | date_range (today, yesterday, this_week, last_week, last_7_days, last_30_days, this_month, last_month); optional; default "today" | Date range. |
| `aggregation` | aggregation (latest, sum, average, min, max, count); optional; default "sum" | sum = total, average = per logged day. |

- Output: scalar — `value`, `unit`, `target`, `remaining`, `percent`
- Permissions: local
- Confirmation: none
- Requires unlocked device: yes · Opens the app: no
- Surfaces: siri, shortcuts, android, deeplink · Screen: `screen:nutrition`
- Siri: How much protein did I eat yesterday in Ayuvo?
- Siri: How much protein today in Ayuvo?
- Shortcuts: Get Nutrient (Protein, Today) → If < Target → Open Ayuvo Coach
- Android: `ayuvo://action/nutrition.nutrient.get?nutrient=protein&range=yesterday`

#### `nutrition.meals.list`

**Get Logged Foods** · GET. Foods you logged in a date range, optionally one meal.

| Parameter | Type | Meaning |
|---|---|---|
| `range` | date_range (today, yesterday, this_week, last_week, last_7_days, last_30_days, this_month, last_month); optional; default "today" | Date range. |
| `meal` | meal_type (breakfast, lunch, dinner, snack, other); optional | Only this meal. |
| `limit` | integer; optional; default 50; range 1–200 | Maximum foods. |

- Output: list of `food_entry` — `id`, `name`, `calories`, `protein_g`, `carbs_g`, `fat_g`, `meal`, `t_ms`
- Permissions: local
- Confirmation: none
- Requires unlocked device: yes · Opens the app: no
- Surfaces: siri, shortcuts, android, deeplink · Screen: `screen:nutrition`
- Siri: What did I eat for breakfast in Ayuvo?
- Shortcuts: Get Logged Foods (Today, Breakfast) → Repeat with Each → Get Calories
- Android: `ayuvo://action/nutrition.meals.list?meal=breakfast`

#### `nutrition.targets.get`

**Get Nutrition Targets** · GET. Your daily calorie and macro targets.

- Output: record — `calories`, `protein_g`, `carbs_g`, `fat_g`
- Permissions: local
- Confirmation: none
- Requires unlocked device: yes · Opens the app: no
- Surfaces: siri, shortcuts, android, coach · Screen: `screen:nutrition`
- Siri: What is my protein target in Ayuvo?
- Shortcuts: Get Nutrition Targets → Get Protein
- Android: `ayuvo://action/nutrition.targets.get`

#### `nutrition.food.log`

**Log Food** · SET. Log food by describing it (estimated by your AI provider) or with exact calories and macros.

| Parameter | Type | Meaning |
|---|---|---|
| `description` | string; optional | What you ate, e.g. two eggs and toast. Sent to your AI provider for an estimate. |
| `name` | string; optional | Food name when giving exact values. |
| `calories` | number; optional; range 0–10000 | Calories (kcal). |
| `protein` | number; optional; range 0–1000 | Protein (g). |
| `carbs` | number; optional; range 0–1000 | Carbohydrates (g). |
| `fat` | number; optional; range 0–1000 | Fat (g). |
| `meal` | meal_type (breakfast, lunch, dinner, snack, other); optional | Meal. Defaults to the current meal. |

- Needs one of: (description) or (name, calories)
- Output: entity of `food_entry` — `id`, `name`, `calories`, `protein_g`, `carbs_g`, `fat_g`, `meal`, `t_ms`
- Permissions: local, ai_provider
- Confirmation: asks first when the food is estimated by your AI provider; deep links and Coach proposals always ask first
- Requires unlocked device: yes · Opens the app: no
- Surfaces: siri, shortcuts, android, deeplink, coach · Screen: `screen:nutrition`
- Siri: Log food in Ayuvo
- Siri: Log two eggs and toast in Ayuvo
- Shortcuts: Log Food (Name: Protein shake, Calories: 180, Protein: 30)
- Android: Hey Google, log a banana in Ayuvo
- Android: `ayuvo://action/nutrition.food.log?name=Banana&calories=105`

#### `nutrition.food.logSaved`

**Log Saved Food** · SET. Log a favourite or recent food again.

| Parameter | Type | Meaning |
|---|---|---|
| `food` | entity: food; required | Saved food. |
| `servings` | number; optional; default 1; range 0.1–20 | Servings. |
| `meal` | meal_type (breakfast, lunch, dinner, snack, other); optional | Meal. Defaults to the current meal. |

- Output: entity of `food_entry` — `id`, `name`, `calories`, `protein_g`, `carbs_g`, `fat_g`, `meal`, `t_ms`
- Permissions: local
- Confirmation: none; deep links and Coach proposals always ask first
- Requires unlocked device: yes · Opens the app: no
- Surfaces: siri, shortcuts, android, coach · Screen: `screen:nutrition`
- Siri: Log my usual breakfast in Ayuvo
- Shortcuts: Log Saved Food (Oatmeal, 1 serving, Breakfast)
- Android: `ayuvo://action/nutrition.food.logSaved?food=<id>`

### Domain: records

#### `records.search`

**Search Health Records** · SEARCH. Find documents in Health Records by text.

| Parameter | Type | Meaning |
|---|---|---|
| `query` | string; required | Search text. |
| `limit` | integer; optional; default 20; range 1–100 | Maximum results. |

- Output: list of `health_record` — `id`, `title`, `kind`, `date`
- Permissions: records
- Confirmation: none
- Requires unlocked device: yes · Opens the app: no
- Surfaces: siri, shortcuts, android, deeplink · Screen: `tab:records`
- Siri: Search my health records in Ayuvo
- Shortcuts: Search Health Records ("lipid") → Choose from List → Open Record
- Android: `ayuvo://action/records.search?query=lipid`

#### `records.latest`

**Get Latest Health Record** · GET. Your most recent document in Health Records.

- Output: entity of `health_record` — `id`, `title`, `kind`, `date`
- Permissions: records
- Confirmation: none
- Requires unlocked device: yes · Opens the app: no
- Surfaces: siri, shortcuts, android, deeplink · Screen: `tab:records`
- Siri: Show my latest blood test in Ayuvo
- Shortcuts: Get Latest Health Record → Open Record
- Android: `ayuvo://action/records.latest`

#### `records.labValue.get`

**Get Lab Value** · GET. The latest result for a lab test (e.g. HbA1c) and its earlier results.

| Parameter | Type | Meaning |
|---|---|---|
| `analyte` | entity: lab_value; required | Lab test. |
| `limit` | integer; optional; default 10; range 1–100 | How many past results. |

- Output: record — `value`, `unit`, `date`, `flag`, `history`
- Permissions: records
- Confirmation: none
- Requires unlocked device: yes · Opens the app: no
- Surfaces: siri, shortcuts, android · Screen: `tab:records`
- Siri: What was my last HbA1c in Ayuvo?
- Shortcuts: Get Lab Value (HbA1c) → Get Value → If > 6.5 → Open Ayuvo Coach
- Android: `ayuvo://action/records.labValue.get?analyte=hba1c`

### Domain: search

#### `search.universal`

**Search Ayuvo** · SEARCH. Search foods, exercises, metrics, health records and medications at once.

| Parameter | Type | Meaning |
|---|---|---|
| `query` | string; required | Search text. |
| `domain` | search_domain (all, food, exercises, metrics, records, medications); optional; default "all" | Where to search. |
| `limit` | integer; optional; default 20; range 1–100 | Maximum results. |

- Output: list of `search_result` — `domain`, `id`, `title`, `subtitle`
- Permissions: local
- Confirmation: none
- Requires unlocked device: yes · Opens the app: no
- Surfaces: siri, shortcuts, android, deeplink, coach · Screen: `tab:browse`
- Siri: Search Ayuvo for cholesterol
- Shortcuts: Search Ayuvo ("vitamin d", All) → Choose from List
- Android: `ayuvo://action/search.universal?query=vitamin%20d`

### Domain: water

#### `water.get`

**Get Water** · GET. Water logged, your daily goal and how much is left.

| Parameter | Type | Meaning |
|---|---|---|
| `range` | date_range (today, yesterday, this_week, last_week, last_7_days, last_30_days, this_month, last_month); optional; default "today" | Date range. |

- Output: record — `intake_ml`, `goal_ml`, `remaining_ml`, `percent`
- Permissions: local
- Confirmation: none
- Requires unlocked device: yes · Opens the app: no
- Surfaces: siri, shortcuts, android, deeplink, coach · Screen: `metric:app:water`
- Siri: How much water have I had today in Ayuvo?
- Shortcuts: Get Water → Get Remaining → If > 0 → Show Notification
- Android: `ayuvo://action/water.get`

#### `water.log`

**Log Water** · SET. Add water to today's intake.

| Parameter | Type | Meaning |
|---|---|---|
| `amount` | number; required; range 1–5000 ml | Amount (range checked in ml). |
| `unit` | volume_unit (ml, l, floz, cup); optional; default: your volume unit preference | Unit. Defaults to your water unit. |

- Output: record — `added_ml`, `intake_ml`, `goal_ml`, `remaining_ml`, `percent`
- Permissions: local
- Confirmation: none; deep links and Coach proposals always ask first
- Requires unlocked device: yes · Opens the app: no
- Surfaces: siri, shortcuts, android, deeplink, coach · Screen: `metric:app:water`
- Siri: Log water in Ayuvo
- Siri: Log 500 ml of water in Ayuvo
- Shortcuts: Log Water (250 ml) → Get Remaining → Show Result
- Android: Hey Google, log 750 ml water in Ayuvo
- Android: `ayuvo://action/water.log?amount=500&unit=ml`

### Domain: workouts

#### `workout.today.get`

**Get Today's Workout** · GET. Today's planned exercises, sets done and total volume.

- Output: record — `exercise_count`, `sets_done`, `reps_done`, `volume_kg`, `completed`
- Permissions: local
- Confirmation: none
- Requires unlocked device: yes · Opens the app: no
- Surfaces: siri, shortcuts, android, deeplink · Screen: `screen:workouts`
- Siri: What's my workout today in Ayuvo?
- Shortcuts: Get Today's Workout → Get Volume
- Android: `ayuvo://action/workout.today.get`

#### `workout.history.list`

**Get Workout History** · GET. Completed workouts in a date range.

| Parameter | Type | Meaning |
|---|---|---|
| `range` | date_range (today, yesterday, this_week, last_week, last_7_days, last_30_days, this_month, last_month); optional; default "last_30_days" | Date range. |
| `limit` | integer; optional; default 50; range 1–200 | Maximum workouts. |

- Output: list of `workout` — `id`, `date`, `exercise_count`, `sets`, `volume_kg`, `duration_s`
- Permissions: local
- Confirmation: none
- Requires unlocked device: yes · Opens the app: no
- Surfaces: shortcuts, android, deeplink · Screen: `screen:workouts`
- Shortcuts: Get Workout History (This Week) → Count
- Android: `ayuvo://action/workout.history.list?range=this_week`

#### `workout.exercise.stats`

**Get Exercise Stats** · GET. Sessions, sets, reps, volume and best weight for one exercise.

| Parameter | Type | Meaning |
|---|---|---|
| `exercise` | entity: exercise; required | Exercise. |
| `range` | date_range (today, yesterday, this_week, last_week, last_7_days, last_30_days, this_month, last_month); optional; default "last_30_days" | Date range. |

- Output: record — `sessions`, `sets`, `reps`, `volume_kg`, `best_weight_kg`
- Permissions: local
- Confirmation: none
- Requires unlocked device: yes · Opens the app: no
- Surfaces: siri, shortcuts, android, coach · Screen: `screen:workouts`
- Siri: How much did I bench this month in Ayuvo?
- Shortcuts: Get Exercise Stats (Bench Press, This Month) → Get Best Weight
- Android: `ayuvo://action/workout.exercise.stats?exercise=bench_press`

#### `workout.start`

**Start Workout** · SET. Open today's workout log, ready to add sets.

- Output: none — none
- Permissions: local
- Confirmation: none
- Requires unlocked device: no · Opens the app: yes
- Surfaces: siri, shortcuts, android, deeplink · Screen: `screen:workout_log`
- Siri: Start my workout in Ayuvo
- Shortcuts: Start Workout
- Android: Hey Google, start my workout in Ayuvo
- Android: `ayuvo://action/workout.start`

#### `workout.finish`

**Finish Workout** · SET. Mark today's logged sets as done and calculate the calories burned.

- Output: record — `exercise_count`, `sets_done`, `reps_done`, `volume_kg`, `completed`
- Permissions: local
- Confirmation: none; deep links and Coach proposals always ask first
- Requires unlocked device: yes · Opens the app: no
- Surfaces: siri, shortcuts, android, deeplink · Screen: `screen:workouts`
- Siri: Finish my workout in Ayuvo
- Shortcuts: Finish Workout → Get Volume
- Android: `ayuvo://action/workout.finish`

#### `workout.set.log`

**Log Workout Set** · SET. Add a set to today's workout: exercise, weight, reps and optional RPE.

| Parameter | Type | Meaning |
|---|---|---|
| `exercise` | entity: exercise; required | Exercise. |
| `reps` | integer; required; range 1–200 | Reps. |
| `weight` | number; optional; default 0; range 0–1000 kg | Weight (range checked in kg). 0 for bodyweight. |
| `unit` | mass_unit (kg, lb); optional; default: your mass unit preference | Unit. |
| `rpe` | number; optional; range 1–10 | Effort (RPE 1-10). |

- Output: record — `exercise`, `set_number`, `reps`, `weight_kg`, `sets_today`, `volume_kg`
- Permissions: local
- Confirmation: none; deep links and Coach proposals always ask first
- Requires unlocked device: yes · Opens the app: no
- Surfaces: siri, shortcuts, android, deeplink, coach · Screen: `screen:workouts`
- Siri: Add a workout set in Ayuvo
- Shortcuts: Log Workout Set (Bench Press, 10 reps, 60 kg)
- Android: `ayuvo://action/workout.set.log?exercise=bench_press&weight=60&unit=kg&reps=10`

<!-- END GENERATED CATALOG -->
