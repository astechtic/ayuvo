# Insights: Recovery, Ayuvo Health Age, Daily Review and Patterns

## 1. Purpose
Ayuvo already mirrors months of health data (sleep, resting heart rate, HRV, VO2 max, breathing rate, blood oxygen, steps, workouts, weight and body fat) and keeps local logs for food, water, fasting and strength training. Insights turns that history into four read-only features:

| Feature | When | What the person sees |
|---|---|---|
| **Daily Recovery** | each morning, once last night's sleep has synced | a 0–100 score, a label, a training suggestion and the signals behind it |
| **Ayuvo Health Age** | long-term, weekly trend | Ayuvo's own estimate in years next to actual age, each marker's contribution and a pace |
| **Daily Health Review** | end of day (any of the last 7 days) | a Day Score plus "went well", "needs attention", "what to improve" and "consider reducing" |
| **Patterns** | ongoing | associations found in the person's own data, with sample sizes |

Every number comes from deterministic, shared math with test vectors. AI is optional: it only rephrases the result, and only when the person taps "Explain with AI". All copy is associational ("on days when…"), never causal, and never a diagnosis or medical advice. Ayuvo Health Age is labelled as Ayuvo's own estimate: it is not a clinical or biological age and does not reproduce WHOOP or any other company's score.

## 2. Contract
| File | Role |
|---|---|
| `shared/insights/insights_config.json` | single source of truth: metrics, windows, SD floors, weights, bands, reference tables with sources, rules and templates, pattern pairs, disclaimers, AI limits and the methodology text |
| `shared/insights/ai_explain.md` | cloud and compact on-device prompts, placeholder rules and validator summary |
| `scripts/insights_reference.py` | the reference implementation; it wins over prose |
| `scripts/insights_contract_check.py [--write]` | config lint, vector runner, coverage, regex/portability lint, unit tests, platform copies, the generated block below |
| `shared/insights/test-vectors/*.json` | `baseline`, `trend`, `overnight`, `training_load`, `recovery`, `health_age`, `health_age_pace`, `daily_review`, `patterns`, `ai_summary` |

Platform copies (byte-identical, refreshed by `--write`): `android/app/src/main/assets/insights/` and the iOS app target's `Insights/Resources/`, each holding `insights_config.json` and `ai_explain.md`.

### 2.1 Vector encoding
Vector files use `{"format": "ayuvo-insights-vectors", "version": 1, "function": …, "cases": [{name, input, expected, notes?}]}`. Only the four zones America/New_York, Europe/London, Asia/Kolkata and UTC appear. To keep files small, inputs may use two compact encodings that the platform test harness expands before calling the engine (`decode_inputs` in the reference):
- a series `{"start": "YYYY-MM-DD", "values": [v | null, …]}` means `{start + i days: v}` (used for `inputs.series.*`, `water_ml`, `fasting_hours`, `strength_volume`, `recovery_scores`);
- sleep `{"start": "YYYY-MM-DD", "nights": [[asleep_min, start_ms, end_ms] | null, …]}` means `{start + i days (wake day): {asleep_min, start_ms, end_ms}}`.
Plain `{day: value}` maps are also valid wherever a series is expected.

### 2.2 Portability rules
- Rounding is `round_to(x, d) = floor(x·10^d + 0.5) / 10^d` (−0 becomes 0); integers use `floor(x + 0.5)`.
- Mean, sample SD (divides by n − 1), least-squares slope, Welch t and Cohen's d are written out; sums run chronologically.
- Sorting by impact is stable (ties keep component order): use a stable sort on Swift.
- Text is filled by plain `{placeholder}` replacement. Numbers print as grouped integers ("8,432") when integral, otherwise one decimal; signed values use "+" and "−" (U+2212); durations print as "7h 48m" or "45m".
- The AI payload is serialised with the canonical JSON writer described in `ai_explain.md`.

## 3. Architecture
```
Existing sources (health mirror, food/water/fasting stores, strength workouts, weight/body fat, profile, goals)
        │  InsightsDataSource: builds plain inputs (day-keyed series, nights, workouts, logs, targets)
        ▼
 Baseline engine ──► Recovery ────────┐
        │         ──► Health Age + pace├─► InsightsSummary ─► Summary cards / Insights screens / info sheets
        │         ──► Daily Review ───┘            │                 └─► Coach tools and Actions (read only)
        └────────► Pattern engine                   └─► optional "Explain with AI" (payload → prompt → validator)
```
- Engines are pure functions (see the signatures at the top of `insights_reference.py`). They never touch HealthKit, Health Connect, storage or the clock.
- Nothing is persisted: scores are recomputed from the mirror on demand (at most about 400 days × 12 series), and history such as the 30-day Recovery chart or the 12 weekly Health Age points is recomputed "as of" earlier days. An in-memory cache keyed on the stores' revision counters avoids repeat work. No health values are written to preferences.
- The façade supplies two derived inputs so each engine stays small: `daily_review` receives the day's `recovery()` result and the `patterns()` result, and `patterns` receives `recovery_scores` computed with `recovery()` for each day.

### 3.1 Inputs the adapter must build
- `series.hrv` is SDNN on iOS and RMSSD on Android (`hrv_kind`), never compared across platforms. `blood_oxygen` is 0–100 on both.
- HRV, resting heart rate, blood oxygen and breathing rate use `overnight_value(samples, night, fallback)`: the mean of samples inside last night's sleep window, otherwise the daily rollup with `fallback: true`; list fallback metrics in `overnight_fallback`.
- Steps and active energy come from the de-duplicating statistics path (the same one the Summary rings use), never from summing mirror rows.
- `workouts` holds health workouts and Ayuvo strength sessions together; overlapping ones are merged by the engine. `effort` is the 1–10 effort score when the platform has one, else null.
- A missing value is simply absent. Never fill 0 for "no data".

## 4. Privacy and local-first
- Everything is computed on the device from data already on the device. There is no Ayuvo server.
- "Explain with AI" is optional and only runs on tap. The on-device route keeps the request on the phone. The online route sends the compact payload to the AI provider the person configured with their own key; it contains only derived values (scores, labels, contributor texts, review items, surfaced patterns). It never contains raw samples, timestamps, dates or names. The screen states which route was used ("Explained on this device" or "Explained using online AI · provider").
- Notifications ("Your recovery is ready", "Your daily review is ready") never include values and never appear with values on the lock screen.
- Insights data is covered by the same export and backup behaviour as the underlying stores; Insights itself stores nothing.

## 5. AI flow (optional)
1. The person taps "Explain with AI" on Recovery, Health Age or the Daily Review.
2. `ai_payload(summary)` builds the compact payload; `build_prompt(kind, payload, variant)` fills the prompt from `ai_explain.md`.
3. The text role is resolved like other AI features (iOS `AIRoleResolver` → cloud dispatch, Apple on-device model or local Gemma; Android `AIRoleResolver` → cloud dispatch or local runtime). No silent fallback, in particular never from on-device to cloud.
4. `validate_ai_output(text, payload)` extracts the JSON, checks shape and lengths, rejects any number not present in the payload and any blocked term. On failure the deterministic text is shown with a short note.
5. Without a configured provider the button shows "Set up AI in Settings"; every feature still works.

## 6. Background processing limits
- **iOS:** a `BGAppRefreshTask` (`com.ayuvo.health.insights.refresh`) becomes eligible from 05:00 and an `HKObserverQuery` with hourly background delivery watches sleep analysis. iOS decides when (and whether) either runs: low battery, Low Power Mode, how often the app is used and Focus modes all delay it. The morning score may therefore appear only when the app is opened.
- **Android:** periodic WorkManager work (about hourly, battery-not-low) acts only between 05:00 and 12:00 and only with `READ_HEALTH_DATA_IN_BACKGROUND`. Doze and OEM battery savers can delay it; the Daily Review notification uses the existing inexact alarm.
- The info sheet ("When scores update") says this in plain words; scores are always recomputed when a screen opens.

## 7. UI placement
- **Summary:** an Insights section after the rings with a Recovery card (score ring, label, top two signals), a Health Age card (estimate vs actual age, pace chip) and a Daily Review card (Day Score). While baselines are being learned, one "Learning your baseline (n/14 nights)" card replaces them. Hidden when health sync is off.
- **Browse:** a new "Insights" domain opens the hub with Recovery, Health Age, Daily Review, Trends (per-metric baseline, range, today, % change and trend) and Patterns.
- **Info sheets:** every card and screen has an ⓘ button that opens "How we calculate this": the methodology text below, the weights table and "Your inputs today" (the values used and which components were missing).
- **Disclaimer footer** on every screen: the `disclaimers.general` text; Health Age and Patterns add their own disclaimer.
- Missing data shows "—" or a collecting state, never 0.

## 8. Extension points
- **New baseline metric:** add it to `metrics` (both platform type ids, direction, unit, window, min points, SD floor, recent mode) and a vector.
- **New Recovery component:** add it to `recovery.components` (weights must still sum to 100) with a mode and a contributor template.
- **New Health Age marker:** add a marker with a `method`, tables or points, `min_days`, `cap_years` and a `source`; keep weights summing to 100. New methods need reference code first.
- **New review rule:** add a config rule (id, category, params, template) and implement it in `RULES`; the checker fails until both exist and a vector covers it.
- **New pattern pair:** add a pair using an existing exposure and outcome, or add the exposure/outcome to the reference first.
- **Coach and Actions:** read-only actions (`insights.recovery.get`, `insights.health_age.get`, `insights.daily_review.get`) return the same summaries; they are added to `shared/actions/action_catalog.json` in a later step.

## 9. Known limitations
- Reference tables come from specific populations and lab methods (see sources below). Watch VO2 max is an estimate; HRV norms come from 5-minute resting ECG recordings, not wearables, so the HRV marker is capped at ±3 years. The year offsets for dose-response markers are Ayuvo design choices, marked approximate in their sources.
- The ±10-year total cap is a safety bound; with per-marker caps of 6 years the weighted average cannot currently exceed it.
- Patterns are associations in one person's data; other things that happen on the same days can explain them.

## 10. Methodology, weights and sources (generated)
<!-- BEGIN GENERATED INSIGHTS -->

_Generated from `shared/insights/insights_config.json` by `scripts/insights_contract_check.py --write`. Do not edit by hand._

### Methodology text (shown in the in-app "How we calculate this" sheets)

#### Your personal baselines (`baselines`)

- **What a baseline is.** Ayuvo compares each reading with your own recent history, not with other people. Your baseline for a metric is the average of the previous 60 days, not counting today.
- **How much data is needed.** A baseline needs at least 14 days with a reading. Until then you will see "Learning your baseline" with a count such as 9/14. Confidence is high when at least 70% of the 60 days have data and there are 30 or more readings, and medium otherwise.
- **Your normal range.** Your normal range is your average plus or minus one standard deviation, the usual spread of your readings. A minimum spread is used for each metric, so a very steady history does not make tiny changes look dramatic.
- **Today compared with usual.** Ayuvo shows the difference from your average, the percentage change and how many spreads away today is. Trends use the slope of the last 28 days, shown as a percentage of your baseline per week.
- **Overnight readings.** HRV, resting heart rate, blood oxygen and breathing rate use readings taken during last night's sleep. When no reading falls inside your sleep window, the daily value is used instead and marked as such.
- **Limitations.** Baselines depend on how often you wear your device and on its sensors. A missing reading is shown as missing, never as zero.

#### How Recovery is calculated (`recovery`)

- **What it shows.** Recovery is a 0-100 morning score that compares last night's body signals with your own baselines. It is ready once last night's sleep has synced.
- **Inputs and weights.** Heart rate variability counts for 35%, resting heart rate 25%, sleep 25%, breathing rate 7.5% and blood oxygen 7.5%. Sleep is 70% duration compared with your usual and 30% how close your sleep midpoint was to the previous 14 nights. Missing inputs are left out and the remaining weights are scaled up.
- **How each input is scored.** Each input starts at 50 for a typical night and moves 20 points for each spread above or below your usual, in the healthy direction: higher HRV scores higher, a higher resting heart rate scores lower. Breathing rate only lowers the score when it is well outside your usual range, and blood oxygen only when it is well below your usual.
- **Training load.** When yesterday's training load was high compared with your 28-day average, 3 points are taken off, or 6 when it was more than double the average.
- **Labels.** 67 and above is Good Recovery (normal training), 34 to 66 is Moderate (consider lighter training) and below 34 is Low (a recovery-focused day).
- **When there is not enough data.** Recovery needs a sleep baseline and an HRV or resting heart rate baseline, each with at least 14 nights. Nights with less than 2 hours of recorded sleep are treated as incomplete.
- **Limitations.** Illness, alcohol, travel, stress and device fit can all move these signals. Recovery is a wellness estimate from your own data, not a medical diagnosis or advice.

#### How Ayuvo Health Age is estimated (`health_age`)

- **What it is.** Ayuvo Health Age is Ayuvo's own estimate of how your recent habits and fitness markers compare with published population values. It is not a clinical or biological age, and it is not WHOOP's or any other company's score.
- **Markers and weights.** VO2 max 25%, resting heart rate 15%, heart rate variability 15%, daily steps 15%, sleep 15%, workout consistency 10% and body composition 5%. Each marker uses your average over the last 90 days.
- **How a marker becomes years.** VO2 max and HRV are compared with population averages by age and sex: the age at which the average equals your value, minus your actual age. Resting heart rate, steps, sleep, workouts and body composition use tables that turn each range into a number of years. Each marker is limited to 6 years either way (3 for HRV), and the total to 10.
- **What is needed.** Your birthday in your profile, and at least 3 markers including VO2 max, resting heart rate or HRV. Most markers need 30 days of data in the last 90; VO2 max needs 3 readings and body composition 1.
- **Pace.** Health Age is recalculated for each of the last 12 weeks. Pace is how fast it has been changing per calendar year: below 0.9 is improving, 0.9 to 1.1 is steady and above 1.1 is rising faster than time. Pace needs at least 8 weekly points.
- **Limitations.** Population tables come from specific groups and lab methods, and watch readings are estimates. HRV norms come from resting ECG recordings, so that marker is kept small. Treat the number as a direction to watch, not a verdict.

#### How the Daily Review works (`daily_review`)

- **What it shows.** A look back at one day: a Day Score from 0 to 100, what went well, what needs attention, what to try tomorrow and what to consider reducing.
- **Areas and weights.** Nutrition 30%, activity 20%, sleep 20%, hydration 10%, training 10%, recovery 10% and fasting 10%. Only areas you track and have data for count, and the weights are scaled to what is included. An area you did not log shows as not logged and never lowers your score.
- **How areas are scored.** Nutrition: calories within 10% of your target, protein at 90% or more of your target, fiber at your goal and carbs and fat within 20% of target. Hydration and steps: progress toward your goals. Training: a workout, or a rest day after a hard day. Sleep: last night compared with 7 hours. Recovery: this morning's score.
- **Consider reducing.** Nutrients with an upper limit you set, such as sugar, sodium, saturated fat or caffeine, are compared with that limit and your 28-day average. Patterns from your own data can appear here too.
- **Limitations.** The review can only see what was logged or synced. It describes your day; it is not medical advice.

#### How patterns are found (`patterns`)

- **What a pattern is.** Ayuvo compares days with and without something, such as a late intense workout, and looks at what happened next, such as that night's sleep.
- **The test.** Over the last 120 days, each group needs at least 8 days. Ayuvo compares the two averages with Welch's t test and an effect size (Cohen's d). A pattern is shown only when the difference is both consistent (t of 2 or more) and large enough to matter (d of 0.3 or more).
- **Pairs checked.** Late intense workouts and that night's sleep; high training load and next-day recovery; meeting your water goal and next-day recovery; meeting your protein target and next-day strength volume; a short night and that day's steps.
- **Limitations.** An association in your data, not proof of cause. Other things that happen on the same days can explain a difference.

#### When scores update (`background`)

- **Recomputed on demand.** Scores are calculated from your synced health data each time you open them, so there is no separate score history to keep in sync.
- **Morning refresh.** Ayuvo asks your phone to refresh Recovery in the morning after sleep syncs. iOS and Android decide when background work actually runs, so it may appear later, or only when you open the app.
- **Notifications.** The optional Recovery and Daily Review notifications only say that something is ready. They never show your values.

### Metrics

| Metric | iOS type | Android type | Direction | Unit | Window | Min points | SD floor | Recent |
|---|---|---|---|---|---|---|---|---|
| `sleep` | `sleep` | `sleep` | higher_better | min | 60 d | 14 | 20 | day |
| `resting_heart_rate` | `resting_heart_rate` | `resting_heart_rate` | lower_better | bpm | 60 d | 14 | 1 | day |
| `hrv` | `hrv_sdnn` | `hrv_rmssd` | higher_better | ms | 60 d | 14 | 3 | day |
| `vo2_max` | `vo2_max` | `vo2_max` | higher_better | mL/kg/min | 60 d | 14 | 0.5 | mean7 |
| `respiratory_rate` | `respiratory_rate` | `respiratory_rate` | band | br/min | 60 d | 14 | 0.3 | day |
| `blood_oxygen` | `blood_oxygen` | `blood_oxygen` | higher_better | % | 60 d | 14 | 0.5 | day |
| `steps` | `steps` | `steps` | higher_better | steps | 60 d | 14 | 500 | mean7 |
| `active_energy` | `active_energy` | `active_energy` | higher_better | kcal | 60 d | 14 | 50 | mean7 |
| `workout` | `workout` | `workout` | higher_better | min | 60 d | 14 | 10 | mean7 |
| `weight` | `weight` | `weight` | band | kg | 60 d | 14 | 0.3 | mean7 |
| `body_fat` | `body_fat` | `body_fat` | band | % | 60 d | 14 | 0.3 | mean7 |
| `bmi` | `bmi` | — | band | kg/m2 | 60 d | 14 | 0.1 | mean7 |

### Recovery

| Component | Weight | Scoring |
|---|---|---|
| hrv | 35 | 50 + 20·z, clamped 0–100 |
| resting_heart_rate | 25 | 50 − 20·z, clamped 0–100 |
| sleep | 25 | 0.7 × (50 + 20·z) + 0.3 × consistency, clamped 0–100 |
| respiratory_rate | 7.5 | 50 − 20·max(0, abs(z) − 1), clamped 0–100 |
| blood_oxygen | 7.5 | 50 − 20·max(0, −z − 1), clamped 0–100 |

Load modifier: -3 when yesterday's load category is high, -6 when its ratio is above 2. Bands: ≥ 67 Good Recovery (Normal training); ≥ 34 Moderate (Consider lighter training); ≥ 0 Low (Recovery-focused day).

Source: Ayuvo design: personal-baseline z-scores (the approach used by consumer readiness scores; see Plews et al. 2013 Sports Med 43(9):773-781 on individual HRV baselines). Weights and bands are Ayuvo choices.

### Training load

Intensity = clamp(effort ÷ 5, 0.5, 2), 1 without an effort. Category vs the 28-day mean: light < 0.8 ≤ moderate ≤ 1.3 < high; no load = none; a load after a zero mean = high.

Source: Ayuvo design: session-minutes x intensity load (in the spirit of session-RPE load, Foster et al. 2001 J Strength Cond Res 15(1):109-115) compared with the 28-day average; thresholds are Ayuvo choices.

### Ayuvo Health Age markers

| Marker | Weight | Method | Min days | Cap (years) |
|---|---|---|---|---|
| VO2 max | 25 | age_norm | 3 | ±6 |
| Resting heart rate | 15 | dose_response | 30 | ±6 |
| Heart rate variability | 15 | age_norm | 30 | ±3 |
| Daily steps | 15 | dose_response | 30 | ±6 |
| Sleep | 15 | sleep | 30 | ±6 |
| Workout consistency | 10 | workouts | 30 | ±6 |
| Body composition | 5 | body_composition | 1 | ±6 |

Window 90 days; equivalent ages clamped to 20–80; total difference capped at ±10 years; pace over 12 weeks (needs 8 points): < 0.9 improving, > 1.1 declining.

#### Reference tables

**VO2 max** (mL/kg/min)

- male: age 25 → 48, age 35 → 42.4, age 45 → 37.8, age 55 → 32.6, age 65 → 28.2, age 75 → 24.4
- female: age 25 → 37.6, age 35 → 30.2, age 45 → 26.7, age 55 → 23.4, age 65 → 20, age 75 → 18.3
- Source: 50th percentiles of treadmill VO2max by decade (20-29 ... 70-79, plotted at midpoints 25-75), FRIEND registry: Kaminsky LA, Arena R, Myers J. Reference standards for cardiorespiratory fitness measured with cardiopulmonary exercise testing. Mayo Clin Proc 2015;90(11):1515-1523. Watch values are estimates, so a few readings (3 days) are enough.

**Resting heart rate** (bpm)

- value → years: 45 → -2, 55 → -1, 65 → +0, 75 → +1, 85 → +2.5, 95 → +4
- Source: Ayuvo mapping (approximate) informed by Zhang D, Shen X, Qi X. Resting heart rate and all-cause and cardiovascular mortality in the general population: a meta-analysis. CMAJ 2016;188(3):E53-E63 (RR 1.09 per 10 bpm; RR 1.45 above 80 bpm). About +1 year per 10 bpm (a 9% risk step is roughly one year of age at an ~8-year mortality doubling time), steeper above 80 bpm. Resting heart rate changes little with age, so a dose-response table is used instead of an age curve.

**Heart rate variability** (ms)

- RMSSD male: age 30 → 39.7, age 40 → 32, age 50 → 23, age 60 → 19.9, age 70 → 19.1
- RMSSD female: age 30 → 42.9, age 40 → 35.4, age 50 → 26.3, age 60 → 21.4, age 70 → 19.1
- SDNN male: age 30 → 50, age 40 → 44.6, age 50 → 36.8, age 60 → 32.8, age 70 → 29.6
- SDNN female: age 30 → 48.7, age 40 → 45.4, age 50 → 36.9, age 60 → 30.6, age 70 → 27.8
- Source: Mean SDNN and RMSSD by age decade and sex from Voss A, Schroeder R, Heitmann A, Peters A, Perz S. Short-term heart rate variability: influence of gender and age in healthy subjects. PLoS One 2015;10(3):e0118308, Tables 5 and 7 (5-minute supine ECG, KORA S4; decade means plotted at decade midpoints 30-70). Resting-ECG norms, not wearable norms: kept as a small, capped marker.

**Daily steps** (steps)

- value → years: 3000 → +4, 4500 → +2.5, 6000 → +1, 7500 → +0, 9000 → -1, 11000 → -1.5, 13000 → -2
- Source: Ayuvo mapping (approximate, deliberately conservative) informed by Paluch AE et al. Daily steps and all-cause mortality: a meta-analysis of 15 international cohorts. Lancet Public Health 2022;7(3):e219-e228 (quartile medians about 3,500 / 5,800 / 7,800 / 10,900 steps; 40-53% lower risk above the lowest quartile; benefit levels off around 6,000-8,000 steps at 60+ and 8,000-10,000 below 60).

**Sleep** (h)

- hours asleep → years: 5.0 → +2.5, 6.0 → +1, 7.0 → +0, 9.0 → +0, 10.0 → +1, 11.0 → +2
- midpoint SD (min) → years: 30 → -0.5, 60 → +0, 90 → +0.5, 120 → +1
- Source: Duration: 7-9 h recommended for adults (Watson NF et al. Recommended amount of sleep for a healthy adult: AASM and SRS consensus statement. Sleep 2015;38(6):843-844; Hirshkowitz M et al. National Sleep Foundation recommendations. Sleep Health 2015;1(1):40-43). Regularity (SD of sleep midpoint): Ayuvo mapping (approximate) informed by Windred DP et al. Sleep regularity is a stronger predictor of mortality risk than sleep duration. Sleep 2024;47(1):zsad253.

**Workout consistency** (min/week)

- min/week → years: 0 → +3, 75 → +1, 150 → +0, 300 → -1.5, 450 → -2
- active-week share → years: 0.0 → +1, 0.5 → +0.5, 0.75 → +0, 1.0 → -0.5
- Source: 150 min/week target from the WHO 2020 guidelines on physical activity and sedentary behaviour (Bull FC et al. Br J Sports Med 2020;54:1451-1462). Ayuvo mapping (approximate) informed by Arem H et al. Leisure time physical activity and mortality: a detailed pooled analysis of the dose-response relationship. JAMA Intern Med 2015;175(6):959-967 (most benefit by 1-3x the minimum, plateau at 3-5x). Needs 30 days of step data in the window as evidence the device was worn.

**Body composition** (% or kg/m2)

- BMI → years: 17.0 → +2, 18.5 → +0, 24.9 → +0, 30.0 → +1.5, 35.0 → +3, 40.0 → +4
- body fat % (male) → years: 5 → +1, 8 → +0, 19 → +0, 25 → +1.5, 30 → +3
- body fat % (female) → years: 15 → +1, 21 → +0, 33 → +0, 39 → +1.5, 45 → +3
- Source: Body fat ranges (approximate, ages 20-39): Gallagher D et al. Healthy percentage body fat ranges: an approach for developing guidelines based on body mass index. Am J Clin Nutr 2000;72(3):694-701 (as used by ACSM). BMI 18.5-24.9 healthy range: WHO. Obesity: preventing and managing the global epidemic. WHO Technical Report Series 894, 2000. Year offsets are an Ayuvo mapping. Body fat is used when sex is known; otherwise BMI.

### Daily Review

| Area | Weight |
|---|---|
| Nutrition | 30 |
| Hydration | 10 |
| Activity | 20 |
| Training | 10 |
| Sleep | 20 |
| Recovery | 10 |
| Fasting | 10 |

| Rule | Category | Template |
|---|---|---|
| `calories_on_target` | went_well | Calories were on target: {calories} of {target} kcal. |
| `protein_met` | went_well | You reached {protein_g} g of protein, {pct}% of your target. |
| `fiber_met` | went_well | You had {fiber_g} g of fiber, meeting your {goal_g} g goal. |
| `water_goal_met` | went_well | You drank {water_ml} ml of water, meeting your {goal_ml} ml goal. |
| `steps_goal_met` | went_well | You walked {steps} steps, above your {goal} step goal. |
| `workout_done` | went_well | You trained for {minutes} min. |
| `planned_rest` | went_well | You took a rest day after a hard training day. |
| `sleep_good` | went_well | You slept {duration} last night. |
| `recovery_good` | went_well | Your recovery was good ({score}). |
| `fasting_goal_met` | went_well | You fasted {hours} h, reaching your {goal_hours} h goal. |
| `calories_over` | needs_attention | Calories were {over_pct}% above your target ({calories} of {target} kcal). |
| `calories_under` | needs_attention | Calories were {under_pct}% below your target ({calories} of {target} kcal). |
| `protein_low` | needs_attention | Protein was {protein_g} g, {pct}% of your target. |
| `sleep_short` | needs_attention | You slept {duration}, under 6 hours. |
| `sleep_below_usual` | needs_attention | You slept {duration}, {diff_min} min less than your usual. |
| `recovery_low` | needs_attention | Your recovery was low ({score}). |
| `steps_low` | needs_attention | You walked {steps} steps, under half of your {goal} step goal. |
| `water_low` | needs_attention | You drank {water_ml} ml, under half of your {goal_ml} ml goal. |
| `improve_protein` | improve | Add a protein-rich food to tomorrow's meals to close the {gap_g} g gap. |
| `improve_fiber` | improve | Add vegetables, fruit, beans or whole grains tomorrow; you were {gap_g} g short on fiber. |
| `improve_steps` | improve | Aim for {gap} more steps tomorrow, about a {walk_min}-minute walk. |
| `improve_water` | improve | Keep water nearby tomorrow; you were {gap_ml} ml short of your goal. |
| `improve_sleep` | improve | Try winding down {minutes} min earlier tonight. |
| `recovery_focus` | improve | Tomorrow could be a lighter, recovery-focused day. |
| `lighter_tomorrow` | improve | Today's training load was high; consider a lighter session tomorrow. |
| `reduce_nutrient_vs_average` | reduce | {nutrient} was {value} {unit}, above your {goal} {unit} limit and your 28-day average of {average} {unit}. |
| `reduce_nutrient` | reduce | {nutrient} was {value} {unit}, above your {goal} {unit} limit. |
| `reduce_pattern` | reduce | {pattern_text} |

### Patterns

Window 120 days, each group ≥ 8 days, surfaced when |t| ≥ 2 and |d| ≥ 0.3.

| Pair | Exposure | Outcome | Lag (days) |
|---|---|---|---|
| `late_workout_sleep` | late_intense_workout | sleep_minutes | 1 |
| `high_load_recovery` | high_load | recovery_score | 1 |
| `water_goal_recovery` | water_goal_met | recovery_score | 1 |
| `protein_strength_volume` | protein_target_met | strength_volume | 1 |
| `short_sleep_steps` | short_sleep | steps | 0 |

Source: Welch's unequal-variance t statistic and Cohen's d with pooled SD; thresholds |t| >= 2 and |d| >= 0.3 are Ayuvo choices (d 0.2/0.5 are Cohen's small/medium conventions: Cohen J. Statistical Power Analysis for the Behavioral Sciences, 2nd ed. 1988).

### Disclaimers

- `ai`: AI explanations describe these numbers. They are not medical advice.
- `background`: Your phone decides when background refresh runs, so a morning score can appear later than you expect.
- `general`: Wellness estimates from your own data, not a medical diagnosis or advice.
- `health_age`: Ayuvo Health Age is Ayuvo's own estimate from your data. It is not a clinical or biological age, and it is not WHOOP's or any other company's score.
- `patterns`: An association in your data, not proof of cause.

<!-- END GENERATED INSIGHTS -->
