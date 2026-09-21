# Ayuvo UI structure and metrics contract

This page is the cross-platform contract for the app's information architecture (Summary · Browse · Records · Coach · Settings), the shared domain colours, the metric catalog and the chart bucketing rules. Android (`data/metrics/`, `ui/metrics/`, `ui/summary/`, `ui/browse/`) and iOS (`Models/Metrics/`, `Services/Metrics/`, `Views/Summary/`, `Views/Browse/`) implement it.

Shared files:

| File | Role |
|---|---|
| `shared/metrics/metric_catalog.json` | Domains, app metrics, health mapping, rings, favourites, prefs. Loaded at runtime from byte-identical copies: `android/app/src/main/assets/metrics/metric_catalog.json`, `ios/calorietracker/Metrics/Resources/metric_catalog.json`. |
| `scripts/metrics_reference.py` | Executable reference. **When this page and the reference disagree, the reference wins** and this page is fixed. |
| `shared/metrics/test-vectors/*.json` | 10 files, run by both platforms (`MetricsVectorTests`); a coverage test fails when a file has no runner. |
| `scripts/metrics_contract_check.py` | Validates catalog, copies, vectors and shapes. `--write` regenerates `expected` from the reference. |

Change order: edit the shared file / reference first, run the checker with `--write`, copy the catalog to both platform paths, then update both ports in the same change.

## 1. Rules that never bend

1. **Never fabricate a value.** A missing value is `null` in every function and renders as "—", "No data" or an empty state, never 0. Cards on Summary hide themselves when they have no real data.
2. **Domain colours are fixed** (§3). The user's theme accent (`appThemeColor`) is used only for primary buttons, the selected tab and chart scrub markers.
3. **Logging flows keep their existing screens.** Camera, AI analysis, food result/edit sheets, workout set editors, the exercise library, Records internals and Coach internals are re-homed, not redesigned.
4. **One range vocabulary:** D · W · M · 6M · Y, calendar-aligned (§5), for app metrics and health metrics alike.
5. Metrics add no storage. Everything is derived from the existing stores and the health mirror.

## 2. Screen map

```
Tabs: Summary · Browse · Records · Coach · Settings

Summary
 ├─ Rings card (Eat · Move · Drink) ─ ring tap → Metric detail
 ├─ Today cards: Medications · Fasting (active only) · Workout (only if logged today)
 ├─ Favourites grid ─ tile → Metric detail · "Edit" → Favourites editor
 ├─ Highlights (≤ 3: record highlight · weight trend · latest workout)
 ├─ Get More From Ayuvo checklist
 └─ toolbar "+" → Log sheet (§8)

Browse (search over app metrics + health types)
 ├─ one row per domain (§3), in browse_order; target decides the destination:
 │    screen:nutrition   → Nutrition (the former Food segment + "Trends" section)
 │    metric:app:water   → Metric detail app:water
 │    screen:fasting     → Fasting
 │    screen:body        → Body (app weight, app body fat, Body Measurements, health Body rows)
 │    screen:activity    → Activity (Workouts log, Exercise Library, app workout metrics, health Activity rows)
 │    category:<id>      → health category page (registry category)
 │    screen:medications → Medications home and every medications/* screen
 │    tab:records        → switches to the Records tab
 └─ footer: health sync status → Settings › Data & Privacy › Health Sync

Metric detail → All Data · Data Sources & Access (health only) · Unit
Records, Coach: unchanged internally.
Settings: profile header + groups Health Profile · Tracking · Notifications · Data & Privacy · AI & Speech · Appearance · About.
```

The old Health tab segments map as: Food → Browse › Nutrition; Progress → `app:weight`, `app:body_fat`, `app:calories` … details; Health Data → Browse; Workouts → Browse › Activity › Workouts; Meds → Browse › Medications.

Android routes: `summary`, `browse`, `browse/nutrition`, `browse/nutrition/nutrients`, `browse/fasting`, `browse/body`, `browse/body/measurements`, `browse/activity`, `browse/category/{categoryId}`, `metric/{metricKey}` (URL-encoded), `workouts/log`, `workouts/library`, `medications` + `medications/*`. `health/type/{typeKey}` stays as an alias of `metric/{typeKey}`. iOS: `BrowseRoute`, `MetricRoute`, `HealthRoute` (without `.hub`), `MedicationRoute`, `RecordsRoute`.

Shared destinations (metric, workouts, medications) keep the tab that opened them selected.

## 3. Domains and colours

| id | title | light | dark | Android icon | iOS symbol | target |
|---|---|---|---|---|---|---|
| nutrition | Nutrition | #34C759 | #30D158 | Filled.Restaurant | fork.knife | screen:nutrition |
| hydration | Hydration | #007AFF | #0A84FF | Filled.WaterDrop | drop.fill | metric:app:water |
| fasting | Fasting | #00C7BE | #63E6E2 | Filled.Timer | timer | screen:fasting |
| body | Body Measurements | #AF52DE | #BF5AF2 | Filled.Accessibility | figure | screen:body |
| activity | Activity | #FF9500 | #FF9F0A | Filled.LocalFireDepartment | flame.fill | screen:activity |
| heart | Heart | #FF3B30 | #FF453A | Filled.Favorite | heart.fill | category:heart |
| sleep | Sleep | #5E5CE6 | #7D7AFF | Filled.Bedtime | bed.double.fill | category:sleep |
| vitals | Vitals | #FF2D55 | #FF375F | Filled.MonitorHeart | waveform.path.ecg | category:vitals |
| respiratory | Respiratory | #5AC8FA | #64D2FF | Filled.Air | lungs.fill | category:respiratory |
| cycle | Cycle Tracking | #FF2D55 | #FF375F | Filled.Loop | arrow.triangle.2.circlepath | category:cycle_tracking |
| mindfulness | Mental Wellbeing | #30B0C7 | #40CBE0 | Filled.SelfImprovement | brain.head.profile | category:mental_wellbeing |
| mobility | Mobility | #FF9500 | #FF9F0A | AutoMirrored.Filled.DirectionsWalk | figure.walk.motion | category:mobility |
| hearing | Hearing | #007AFF | #0A84FF | Filled.Hearing | ear.fill | category:hearing |
| symptoms | Symptoms | #AF52DE | #BF5AF2 | Filled.HealthAndSafety | stethoscope | category:symptoms |
| medications | Medications | #32ADE6 | #64D2FF | Filled.Medication | pills.fill | screen:medications |
| records | Health Records | #5856D6 | #5E5CE6 | Filled.Description | doc.text.fill | tab:records |
| other | Other Data | #8E8E93 | #98989D | Filled.MoreHoriz | ellipsis.circle | category:other |

Macro colours (`macro_colours`): protein #007AFF, carbs #FF9F0A, fat #BF5AF2, fiber #30B0C7. Rings: Eat = nutrition, Move = activity, Drink = hydration.

Domain icons are the fallback for health metrics that have no curated icon of their own (§4). Android icon strings are `material-icons-extended` paths: `Filled.X` → `Icons.Filled.X`, `AutoMirrored.Filled.X` → `Icons.AutoMirrored.Filled.X`. Android resolves `title_res` (`domain_<id>`, `metric_<name>`) to string resources; iOS uses `title` as the localization key. Android hides the hearing and symptoms rows when no data exists (it cannot read those types); iOS shows every row and dims empty ones.

## 4. Catalog rules

Top-level keys: `catalog_version` (1), `format` (`ayuvo-metric-catalog`), `domains`, `metrics`, `browse_sections`, `health`, `macro_colours`, `summary_rings`, `favourites`, `prefs`.

**Metric key grammar.** `app:<snake_case>` for app metrics; any other key is a health registry id (`shared/health/metric_registry.json`, unprefixed, e.g. `steps`). Unknown keys resolve to the Other domain (§7.10).

**App metrics** (`metrics[]`, fields `key, domain, title, title_res, icon, source{store, field}, unit{canonical, decimals, pref}, aggregation, chart_kind, day_bucket, ranges, goal_source, default_favourite{enabled, order}, browse_section, browse_order, about`):

| key | store.field | unit (pref) | aggregation | chart | ranges | goal_source | fav |
|---|---|---|---|---|---|---|---|
| app:calories | food.calories | kcal | sum | bar | D–Y | profile.calories | 1 |
| app:protein | food.protein | g | sum | bar | D–Y | profile.protein | 2 |
| app:carbs | food.carbs | g | sum | bar | D–Y | profile.carbs | – |
| app:fat | food.fat | g | sum | bar | D–Y | profile.fat | – |
| app:fiber | food.fiber (null skipped) | g | sum | bar | D–Y | none | – |
| app:water | water.milliliters | mL (`waterUnit`) | sum | bar | D–Y | prefs.waterDailyGoalMl | 4 |
| app:fasting | fasting.duration | s | duration | bar | W–Y | none | – |
| app:weight | weight.weightKg | kg (`weightUnit`) | last | line | W–Y | profile.goalWeight | 5 |
| app:body_fat | bodyFat.bodyFatFraction | % | last | line | W–Y | profile.goalBodyFat | – |
| app:workouts | workouts.sessions | count | count | bar | W–Y | none | – |
| app:workout_minutes | workouts.durationSeconds | s | duration | bar | W–Y | none | – |
| app:workout_burn | workouts.caloriesBurned (null skipped) | kcal | sum | bar | W–Y | none | – |

`day_bucket = hour` metrics offer D (hourly bars); `none` metrics start at W. Values are always stored and computed in the canonical unit; display converts only when `unit.pref` names a preference. Body fat is `bodyFatFraction × 100` (percent 0–100, the registry convention); the platform adapter does the conversion before calling the shared functions.

**Entry adapters** (platform code, not in the reference): each app metric becomes `entries = [{t_ms, value}]`:
- food metrics: one entry per food entry at its timestamp; the field value (null → skipped).
- water: one entry per water entry.
- fasting: `fasting_hours_per_day` (§7.6), then one entry per day at that day's local midnight with `value = seconds`.
- weight / body fat: one entry per reading.
- workouts: one entry per completed session at the local midnight of `workout_day` (§7.7); value 1 (`app:workouts`), `durationSeconds` (`app:workout_minutes`) or `caloriesBurned` (`app:workout_burn`, null → skipped).

**Health metrics** (`health`): `category_domains` maps every registry category to a domain; `aggregation_map` (`SUM→sum, DURATION→duration, AVERAGE→avg, MIN_MAX→avg, LATEST→last, COUNT→count`) and `chart_kind_map` (`SUM/DURATION/COUNT→bar, AVERAGE/LATEST→line, MIN_MAX→range`) apply to the registry aggregation. Sleep, blood pressure and period types keep their existing special charts. `overrides[]` (`id` + optional `domain`, `goal_source`, `default_favourite`, `browse_hidden`, `icon`): steps (goal `prefs.dailyStepGoal`, favourite 3), sleep (6), heart_rate (7), active_energy (8), hydration (domain hydration), weight and body_fat (`browse_hidden`: not listed on the Body page because the app metrics already include imported readings, but still found by search).

**Icons.** App metrics carry their own `icon`. A health metric uses its override `icon` when present, otherwise its domain's icon, so the curated list below keeps tiles and rows from all sharing one category glyph. Unknown keys use the Other domain icon. `resolve_metric` returns the resolved pair as `icon_android` / `icon_ios`; platforms never re-derive it.

| id | Android | iOS | | id | Android | iOS |
|---|---|---|---|---|---|---|
| steps | AutoMirrored.Filled.DirectionsWalk | figure.walk | | sleep | Filled.Bedtime | bed.double.fill |
| distance | Filled.Straighten | ruler.fill | | mindfulness_session | Filled.SelfImprovement | brain.head.profile |
| floors_climbed | Filled.Stairs | figure.stairs | | blood_glucose | Filled.Bloodtype | drop.fill |
| active_energy | Filled.LocalFireDepartment | flame.fill | | body_temperature | Filled.Thermostat | thermometer |
| total_energy | Filled.Bolt | bolt.fill | | vo2_max | Filled.Speed | speedometer |
| exercise_minutes | Filled.Timer | timer | | height | Filled.Height | figure.stand |
| heart_rate | Filled.Favorite | heart.fill | | weight | Filled.MonitorWeight | scalemass.fill |
| resting_heart_rate | Filled.MonitorHeart | heart.circle.fill | | body_fat | Filled.Percent | percent |
| hrv_rmssd | Filled.MonitorHeart | waveform.path.ecg | | bmi | Filled.Calculate | function |
| blood_pressure | Filled.MonitorHeart | heart.text.square.fill | | blood_oxygen | Filled.Air | lungs.fill |
| respiratory_rate | Filled.Air | wind | | | | |

**Default favourites**, in order: `app:calories, app:protein, steps, app:water, app:weight, sleep, heart_rate, active_energy`.

**Rings** (`summary_rings`): `eat` = app:calories / profile.calories, always shown; `move` = steps / prefs.dailyStepGoal, always shown, `no_source_state: connect` (shows "Connect Health" when health sync is off or steps cannot be read); `drink` = app:water / prefs.waterDailyGoalMl, shown only when `prefs.waterTrackingEnabled`.

**Favourites** (`favourites`): stored under `summaryFavourites` as comma-separated keys (Android string; iOS stores `[String]` and joins with "," before calling the shared rule), max 12, migrated once from `healthHomeTiles` (§7.9).

**Prefs** (`prefs`): `dailyStepGoal` int, default 10000, range 1000–50000, step 500, cloud-backed. `summaryChecklistDismissed` bool, default false, cloud-backed. `summaryFavourites` is cloud-backed on both platforms.

## 5. Bucketing semantics

All instants are epoch milliseconds (UTC); days are local `yyyy-MM-dd` in the given IANA zone. Local midnight is the fold=0 wall-clock 00:00 (`LocalDate.atStartOfDay(zone)` / `Calendar.startOfDay(for:)`).

| Range | Interval | Buckets | Label |
|---|---|---|---|
| D | anchor day's local midnight → next local midnight | one per **real** hour from the start: 23 on spring-forward, 25 on fall-back | local `HH` of the bucket start (a fall-back day shows `01` twice; a spring-forward day skips `02`) |
| W | week containing the anchor; first day from `week_start` (`monday` / `sunday`, the app's `weekStartsOnMonday` pref, never the locale) | 7 days | `yyyy-MM-dd` |
| M | calendar month | 28–31 days | `yyyy-MM-dd` |
| 6M | first day of (anchor month − 5) → first day of the month after the anchor month | weekly, aligned to `week_start`, clipped to the interval (first/last bucket may be shorter) | `yyyy-MM-dd` of the clipped start |
| Y | calendar year | 12 months | `yyyy-MM-dd` (first of month) |

A bucket contains entries with `start_ms <= t_ms < end_ms`. `can_go_forward` is `interval.end_ms <= now_ms`.

Anchor stepping (‹ ›): D ±1 day, W ±7 days, M ±1 month, 6M ±6 months, Y ±1 year. Month steps clamp the day (Jan 31 + 1 month = Feb 28, or 29 in a leap year). A step forward never passes today (it clamps to today's date). The new anchor is that date's local midnight.

Aggregation per bucket (`bucket_series`):

| aggregation | D / W / M | 6M / Y | min / max |
|---|---|---|---|
| sum, duration | total of the bucket | mean of the **per-local-day totals** over days with data | null |
| count | number of entries | mean of daily counts over days with data | null |
| avg | mean of the entries | mean of the entries | raw min / max |
| last | value of the latest entry (ties: later input position) | same | raw min / max |

Every bucket is returned. An empty bucket has `value: null, count: 0` (never 0). Entries with `value: null` are ignored everywhere (they do not count).

**Rounding.** Every fractional output uses `round3(x) = sign(x) · floor(|x| · 1000 + 0.5) / 1000`, computed in IEEE double in exactly that order. Integral results are emitted as integers in vectors; ports compare numerically.

## 6. Metric detail anatomy

Top to bottom: title bar (metric title) · range picker D/W/M/6M/Y (only the metric's `ranges`) · headline (§7.4: kind label, value + unit, date span) · ‹ interval title › (forward disabled when `can_go_forward` is false) · chart card (bars/line/range in the domain colour; dashed goal rule when `goal_source` resolves to a value; scrub marker in the theme accent) · Options (Add to Favourites; Show All Data; Data Sources & Access — health only; Unit — weight, water, glucose and health unit types; Log — app metrics) · About (`about` text, or the registry description for health types). A chart whose buckets are all null shows an empty state ("No data in this range").

## 7. Reference functions and vector shapes

Envelope of every vector file: `{"format": "ayuvo-metrics-vectors", "version": 1, "function": <name>, "cases": [{"name", "input", "expected", "notes"?}]}`, sorted keys, 2-space indent, UTF-8 without escapes, trailing newline. Zones used: `America/New_York`, `Europe/London`, `Asia/Kolkata`, `UTC`.

### 7.1 `bucket_bounds.json` → `bucket_bounds`
- input `{range, anchor_ms, time_zone, week_start, now_ms}`
- output `{interval: {start_ms, end_ms}, buckets: [{start_ms, end_ms, label}], can_go_forward}`

### 7.2 `anchor_step.json` → `step_anchor`
- input `{range, anchor_ms, direction (-1 | 1), time_zone, now_ms}`
- output `{anchor_date: "yyyy-MM-dd", anchor_ms}` (local midnight of anchor_date)

### 7.3 `bucket_series.json` → `bucket_series`
- input `{entries: [{t_ms, value|null}], range, anchor_ms, time_zone, week_start, aggregation}`
- output `{buckets: [{start_ms, end_ms, value|null, count, min|null, max|null}]}` — one per bucket of `bucket_bounds` (its `now_ms` is irrelevant here).

### 7.4 `headline.json` → `headline`
- input: same as 7.3
- output `{kind: TOTAL|AVERAGE|LATEST, value|null, from_ms, to_ms, days_with_data}`
- sum/duration/count on D → `TOTAL` (interval total); on W/M/6M/Y → `AVERAGE` = interval total ÷ `days_with_data` (count uses 1 per entry). avg → `AVERAGE` of all entries in the interval. last → `LATEST` = latest entry (ties: later input), `from_ms = to_ms =` its `t_ms`. Otherwise `from_ms/to_ms` are the interval bounds. `days_with_data` = distinct local dates with a non-null entry inside the interval. No entries → `value: null` (for last also `days_with_data: 0` and interval bounds).

### 7.5 `sparkline.json` → `sparkline_7d`
- input `{entries, now_ms, time_zone, aggregation}`
- output `{values: [7 × number|null], min|null, max|null, has_data}` — today − 6 … today (oldest first); daily value by the D/W/M rule of §5 (summed → day total, count → entries, avg → mean, last → latest).

### 7.6 `fasting_days.json` → `fasting_hours_per_day`
- input `{sessions: [{started_at_ms, ended_at_ms|null}], now_ms, time_zone}`
- output `{days: [{day, seconds}]}` ascending, only days with seconds > 0.
- An active session (`ended_at_ms` null) ends at `now_ms`. Sessions with `end <= start` are ignored. Each session is split across local midnights; each day's piece contributes `floor(overlap_ms / 1000)` seconds, summed per day. The fasting history list keeps its existing end-day attribution; only charts use this split.

### 7.7 `workouts.json` → `workout_stats_per_bucket`
- input `{sessions: [{diary_date|null, started_at_ms, duration_s, calories|null}], range, anchor_ms, time_zone, week_start}`
- output `{buckets: [{start_ms, end_ms, count, duration_s, burn_kcal|null, burn_count}]}`
- `workout_day` = `diary_date` when it parses as `yyyy-MM-dd`, else the local date of `started_at_ms`. The session sits at that day's local midnight. Raw totals per bucket (no daily averaging). `burn_kcal` = sum of non-null calories, null when `burn_count` is 0. Used by workout summaries; charts use `bucket_series` with the §4 adapters.

### 7.8 `rings.json` → `ring_progress`
- input `{value|null, goal|null}`
- output `{state: value|no_goal|no_data, progress|null, percent|null, over}`
- goal null or ≤ 0 → `no_goal` (checked first); value null → `no_data`; else `progress = round3(clamp(value / goal, 0, 1))`, `percent = floor(value · 100 / goal + 0.5)` (may exceed 100 or be negative), `over = value > goal`. Non-value states have `over: false`.

### 7.9 `pins.json` → `favourite_pins_migrate`
- input `{new_raw: string|null, legacy_raw: string|null, known_health_ids: [string], max}`
- output `{favourites: [key], source: new|migrated|default}`
- Parsing: split on ",", trim, drop blanks, drop unknown keys (an `app:` key not in the catalog, or a health id not in `known_health_ids`), keep the first occurrence, cap at `max`.
  1. `new_raw` not null → parse it; `""` → `[]`. Source `new`.
  2. else `legacy_raw` not null and blank after trimming → `[]` (the user had switched the tiles off). Source `migrated`.
  3. else `legacy_raw` not null → parsed legacy ids, then the `app:` keys of the default favourites (in default order) not yet present, until `max`. Source `migrated`.
  4. else → default favourites minus health ids not in `known_health_ids`, capped. Source `default`.
- Platforms run this on first launch after the update, write the result to `summaryFavourites`, and from then on read and write only `summaryFavourites`. `healthHomeTiles` is left in place (older builds still read it).

### 7.10 `catalog_resolve.json` → `resolve_metric`
- input `{key}`
- output `{source: app|health|unknown, domain, colour_hex, colour_hex_dark, aggregation, chart_kind, unit, goal_source, default_favourite_order|null, browse_hidden, icon_android, icon_ios}`
- app key in the catalog → its fields (`unit` = `unit.canonical`, icon = the metric's own). Registry id → domain from the override or `category_domains`, aggregation/chart from the maps, unit from the registry, goal_source from the override (else `none`), `browse_hidden` from the override (else false), icon from the override when it has one, else the domain icon. Anything else (unknown native id, unknown `app:` key, empty string) → `unknown`, domain `other`, aggregation `last`, chart `line`, unit `none`, Other domain icon.
- Icon fallback order, in one line: **metric icon → override icon → domain icon → Other domain icon.**

## 8. Summary composition

Order and visibility:

1. Date line under the large title.
2. **Rings card** — always shown. Each ring uses `ring_progress` on today's value: Eat = today's `app:calories` total vs the profile calorie goal; Move = today's steps vs `dailyStepGoal` (`connect` state when health sync is off or unreadable); Drink = today's `app:water` total vs `waterDailyGoalMl` (ring omitted when water tracking is off). Legend lines show value / goal and never invent a 0 for `no_data`.
3. **Today** — Medications card (only when an active or paused medication exists); Fasting card (only while a fast is active); Workout card (only when a workout is logged today).
4. **Favourites** — tiles in `summaryFavourites` order: icon + title in the domain colour, today's value (summed) or latest value (avg/last), unit, 7-day sparkline, relative time. Water and fasting tiles are hidden while their tracker is off. No data → "—". Empty list → an "Add Favourites" card.
5. **Highlights** — at most 3, each only with real data: newest important record highlight; weight trend (only when the weight analysis has enough data); latest workout.
6. **Get More From Ayuvo** — rows: Connect Health (done when health sync is on), Turn on reminders (notifications on and permitted), Add a record (any record exists), Add medications (any medication exists). Done rows disappear; the card hides when all are done or when `summaryChecklistDismissed` is true.

**"+" log sheet**, in order: Food (the user's `+ Menu` food methods, in their order) · Water (only when water tracking is on) · Fasting · Weight · Body Fat · Workout · Medication dose · Health Record. Each entry opens the existing flow; accessibility id `log.entry.<food|water|fasting|weight|bodyFat|workout|medication|record>`.

## 9. Accessibility identifiers

Same strings on both platforms (`Modifier.testTag` with `testTagsAsResourceId`, `.accessibilityIdentifier`). Raw metric keys are used as-is.

| Surface | Identifier |
|---|---|
| Tabs | `tab.summary`, `tab.browse`, `tab.records`, `tab.coach`, `tab.settings` |
| Summary rings | `summary.ring.eat`, `summary.ring.move`, `summary.ring.drink` |
| Summary favourites | `summary.favourite.<key>`, `summary.favourites.edit` |
| Summary cards | `summary.card.medications`, `summary.card.fasting`, `summary.card.workouts`, `summary.card.records` |
| Summary add button | `summary.add` |
| Log sheet | `log.sheet`, `log.entry.<id>` |
| Browse | `browse.search`, `browse.row.<domain>`, `browse.metric.<key>`, `browse.link.foodDiary`, `browse.link.workouts`, `browse.link.exerciseLibrary`, `browse.link.bodyMeasurements` |
| Metric detail | `metric.headline`, `metric.range.<D|W|M|6M|Y>`, `metric.prev`, `metric.next`, `metric.chart`, `metric.favourite`, `metric.unit`, `metric.allData`, `metric.sources`, `metric.log`, `metric.about` |
| Settings | `settings.profile`, `settings.group.<group>`, `settings.row.<row>` |
| Settings rows (new) | `settings.row.dailyStepGoal`, `settings.row.heightUnit`, `settings.row.weightUnit`, `settings.row.waterUnit`, `settings.row.glucoseUnit`, `settings.row.weekStart`, `settings.row.openMedications`, `settings.row.medicationReminders` |
| Settings panes | `settings.category.<rawValue>`; raw values: `personalInfo`, `goalsNutrition`, `units`, `nutritionTracking`, `hydration`, `fasting`, `activity`, `medications`, `notifications`, `healthData` (Health Sync), `healthRecords`, `dataManagement` (Backup & Export), `deleteData`, `aiProviders`, `speechToText`, `customInstructions`, `appearance`, `appUpdates`, `helpSupport`, `legal` |

Kept for existing UI tests: `home.add` (Nutrition add button), `home.medicationsCard` (second id on the Summary medications card until tests move), `medications.*`, `workouts.search`, `workouts.search.clear`, `records.*`, `settings.category.<rawValue>`, `settings.speech.provider`. The `health.home.*`, `health.detail.*` and `settings.health.homeTile` ids in `docs/health-data.md` §9 are replaced by the rows above.

## 10. Deep links and landings

| Entry point | Android | iOS | Lands on |
|---|---|---|---|
| Home-screen widget tap | opens `MainActivity` | `LogFoodWidget` → `ayuvo://log-food?method=` | Summary; a log method opens Browse › Nutrition with that flow |
| App shortcut / quick action | `QuickActionShortcutManager` → quick-action request | `QuickActionCoordinator` | Browse › Nutrition with the quick-action flow |
| Medication notification tap | `MedicationIntents` content intent | `MedicationCoordinator` | Browse › Medications (› detail when an id is given) |
| `ayuvo://medications[?id=]` | – (no URL scheme on Android) | `onOpenURL` | same as above |
| Share / Open in → records | SEND / VIEW intent filters | share extension → `ayuvo://records-inbox` | Records tab |
| Shared food image | – | `ayuvo://import-share-image` | Browse › Nutrition |
| Siri (iOS only) | – | App Intents | Summary / Nutrition as today |

## 11. Parity checklist

- [ ] Tab bar: labels, order, icons, `tab.*` ids.
- [ ] Summary: ring colours and states (`no_goal`, `no_data`, `connect`; Drink hidden when water tracking is off); default favourites; editor; cap 12; Today/Highlights/checklist visibility; log sheet entries and order.
- [ ] Browse: 17 rows in order with tint and icon; targets as §2; search; dimmed empty rows; status footer; Records row switches tab.
- [ ] Domain pages: app metrics first, then health metrics with data, then a collapsed "No data" group.
- [ ] Metric detail for `app:calories` (D, W, 6M), `app:water`, `app:fasting`, `app:weight` (goal line, log, unit), `app:workouts`, `steps`, `heart_rate`, `sleep`, `blood_pressure`: headline kind, ranges, forward limit, chart kind, favourite toggle, all data, sources.
- [ ] Settings rows in the same group, screen and section (platform-only rows only where the plan lists them).
- [ ] Deep links in §10 land as listed.
- [ ] Both platforms load byte-identical catalog copies and pass every vector file.
