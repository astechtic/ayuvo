# Health analytics engine: baselines, HRV, sleep, load, Recovery v2, signals, patterns and forecasts

## 1. Purpose
Ayuvo mirrors health data from Apple Health / HealthKit, Health Connect and Google Health. It derives daily metrics on the device (docs/derived-metrics.md) and scores Recovery, Health Age and the Daily Review (docs/insights.md). The analytics layer adds what those layers lacked:

- **Robust personal baselines.** Median and MAD over 7/14/28/60/90 days, with robust z-scores.
- **Trends.** Theil–Sen slope, EWMA and CUSUM change points.
- **HRV.** RMSSD computed from Apple Watch beat-to-beat series, plus stability checks on provider HRV.
- **Sleep.** Personal sleep need, sleep debt and variability.
- **Training load.** Exponentially weighted (EWMA) acute and chronic training load, with an interpretive state.
- **Heart rate recovery.** HRR1 and HRR2.
- **Recovery Indicator v2.**
- **Signal deviations.** A multi-signal deviation state.
- **Patterns.** Lagged correlations with false-discovery-rate control, plus personal response estimates.
- **Energy and fitness.** A double-count-free energy expenditure, VO2 max trends per source, and MET intensity.
- **Forecasts.** An optional per-user ridge forecast.

Every result carries a **status**, a **classification**, a **confidence** and an **algorithm id@version**, so the UI and the Coach can always say how a number was produced and how much it can be trusted.

The engine is deterministic. The Coach (LLM) receives these finished results as structured evidence and explains them; it never computes physiological values itself (§8). Nothing here is a diagnosis: copy describes deviations from the person's own history and never names a condition. `scripts/analytics_contract_check.py` enforces this by rejecting words such as "infection", "disease", "diagnos…", "caused" and "injury risk" in any copy.

```
HealthKit / Health Connect / Google Health / Ayuvo logs / camera scans
        │  health_samples (+ source_policy.json: one source per metric per day, Google Health duplicates dropped)
        ▼
rollups · derived_daily_values (docs/derived-metrics.md) · Insights inputs (overnight window, fallbacks)
        ▼
shared/analytics  ── robust baselines → HRV / sleep / load / HRR → Recovery v2 · signals · trends
        │              correlations → personal response · energy · VO2 max · MET · ridge forecast (flag)
        ▼
analytics_results (status, classification, confidence, provenance, input hash)  ── never exported
        ▼
Insights UI · Coach evidence (get_health_evidence / insights.evidence.get)
```

## 2. Contract
| File | Role |
|---|---|
| `shared/analytics/analytics_config.json` | Single source of truth. Holds the classifications and statuses, the confidence model, metric directions and spread floors, the algorithm registry (§4) and verified references, plus the Recovery v2 weights with a "why" for each, the signal list, correlation pairs and the copy, the MET table and the forecast settings. |
| `shared/health/source_policy.json` | Per-metric source handling for discrete rollups (§3). |
| `scripts/analytics_reference.py` | The reference implementation; where it and this prose disagree, the reference wins. |
| `scripts/analytics_contract_check.py [--write]` | Config and policy lint, vector runner and coverage tags, portability lint, platform copies, the generated block below, and unit tests. |
| `scripts/analytics_eval.py` | Agreement statistics against reference measurements (docs/health-validation.md). |
| `shared/analytics/test-vectors/*.json` | One file per reference function: 23 files and 118 cases, including edge cases. |

Platform copies, kept byte-identical and refreshed by `--write`, live in `android/app/src/main/assets/analytics/` and `ios/calorietracker/Services/Analytics/Resources/`. Each folder holds `analytics_config.json` and `source_policy.json`.

**Engines:**
- **Android:** `com.ayuvo.health.data.analytics.engine`, tested by `AnalyticsVectorTests`.
- **iOS:** `ios/calorietracker/Services/Analytics/Engine`, tested by `AnalyticsVectorTests`.

Both run every vector file. Numbers must match the reference within `tolerance` (1e-9, absolute or relative); strings, booleans and nulls must match exactly.

**Portability rules** (the same as the other contracts):
- Round half-up with `round_to`.
- Sum in the documented order.
- Write out mean, SD, median, percentiles and MAD by hand.
- Write atanh, tanh and the normal CDF (Abramowitz & Stegun 7.1.26) as explicit formulas.
- Never call a transcendental function inside an iteration whose length depends on its result (the lesson from FastICA in shared/vitals).

## 3. Phase 0 data fixes the engine relies on
| Problem (audit 2026-10-07) | Fix | Where |
|---|---|---|
| Copies of the same reading from Google Health (origin 3) and Health Connect were averaged into one daily value. Different devices were blended for resting HR, HRV, SpO2 and respiratory rate. | `source_policy.json`: `single_best_source_per_day` for discrete vitals. A wearable device type wins, then the most samples, then the source id. Before choosing, a Google Health row is dropped when a row from another origin with the same source arrives within 60 s and has the same value (±0.5 or 1%). | `HealthRollupMath` on both platforms. Rollups rebuild once (rule version bump). Function `source_select` in the reference. |
| TDEE, PAL and energy balance summed every source's active and resting energy on iOS. On Android, `own_sum` (Ayuvo's own energy) included Google Health write-backs and could make active energy negative. | iOS reads de-duplicated HealthKit statistics. Android's `own_sum` counts only Ayuvo workout burns, and the result is clamped at ≥ 0. Analytics `energy` adds Ayuvo burns only for sessions that no provider workout overlaps, and treats negative input as `INVALID_INPUT`. | `DerivedMetricsService`, `EnergyDerivation`; reference `energy`. |
| Naps merged into the night: the overnight window for HRV and resting HR stretched into the afternoon. iOS sleep rollups were split at midnight. The Coach sleep query lost the part of the night before midnight. | Derived `sleep_nights` algorithm 3: only the main episode of a wake day is the night, and other episodes are reported as `nap_min`. `HealthSleepAnalysis` uses the same grouping. Rollups are built per night, and the Coach query reads one extra day. | derived contract v3; `HealthSleepAnalysis` on both platforms. |
| Logged RPE never reached training load. | The workout's effort (CR-10) is passed to Insights and analytics. | `InsightsDataSource` on both platforms. |
| TRIMP used 0.64·e^(1.67x) for women. Banister (1991) gives 0.86·e^(1.67x). | `thresholds.trimp_a` = {male 0.64, female 0.86, other 0.75}. | derived contract v3, workout contract v2. |
| Body fat is a 0–1 fraction in the app model and portable export, and 0–100 in the mirror. | Checked, not changed: every analytics and Insights consumer converts through `bodyFatPercent`. Changing the model would change the export format. | — |

Canonical units inside the engine are kg, m, kcal, s, mL, °C, % (0–100), bpm, ms and mL/kg/min. Conversions happen at the boundaries; `unit_convert` vectors pin the factors (lb, ft, in, mi, kJ, °F, min, h, fraction). Days are local `yyyy-MM-dd` keys, so a 23- or 25-hour DST day is still one day. Sleep belongs to its wake day. The vectors cover America/New_York across the spring-forward change (2026-03-08) for nights, workouts and MET minutes.

## 4. Statuses, classifications, confidence and provenance

### 4.1 Statuses
A result is never made up. Each one carries one of these statuses:

| Status | Meaning |
|---|---|
| `VALID` | Value shown normally. |
| `LOW_CONFIDENCE` | Value shown with a note that the result is less certain. |
| `INSUFFICIENT_HISTORY` | Not enough past days for a baseline. |
| `INSUFFICIENT_DATA` | Not enough points or beats today. |
| `NO_DATA` | Nothing recorded. |
| `INVALID_INPUT` | For example negative energy, or too many RR artifacts. |
| `EXPERIMENTAL` / `RESEARCH_ONLY` | Kept separate from scores. |
| `PROVIDER_REPORTED` | A provider value shown as is. |

A composite takes the worst status of its required inputs (`status_rank`). A missing optional input only lowers confidence. Missing data stays `null` and is never turned into 0. The single documented exception is training load: a day without a session is a true zero while workout tracking is on.

### 4.2 Classifications
`MEASURED`, `PROVIDER_DERIVED`, `SCIENTIFIC_DERIVED`, `PERSONALIZED_STATISTICAL`, `ML_PREDICTED`, `EXPERIMENTAL`, `RESEARCH_ONLY`. Each classification has a confidence cap (table below).

The camera-vitals classes map onto these:

| Camera-vitals class | Analytics classification |
|---|---|
| measured | `MEASURED` |
| calculated | `SCIENTIFIC_DERIVED` |
| estimated | `EXPERIMENTAL` |
| experimental (SpO2) | `EXPERIMENTAL` |
| research (BP) | `RESEARCH_ONLY` |

Camera SpO2 and BP never enter a score and are labelled as such to the Coach.

### 4.3 Confidence
Confidence is a value in [0, 1]:

`cap(classification) × sqrt(clamp(coverage / 0.7) × clamp(n / 28)) × context weight × (0.8 if the source changed)`

- **Context weights:** wearable, provider and manual 1.0; derived 0.85; camera 0.6.
- **Display:** shown as a percentage. 75% and above is "high", 50% and above "medium". Values below 0.5 make a result `LOW_CONFIDENCE`.
- **Rounding:** values are rounded to the metric's `decimals`, so the UI never shows false precision.

### 4.4 Provenance
Each stored result (`analytics_results.provenance_json`) records:
- the algorithm id@version and the config version;
- the input metric ids and source bundle ids;
- the sample count, window and coverage;
- any fallbacks used (overnight rollup, camera scan);
- the canonical input hash (FNV-1a 64 over `canonical()` JSON, as in vector `input_hash`).

## 5. Algorithms
The registry with inputs, quality gates, fallbacks and references is generated below (§11). This section explains each algorithm.

### 5.1 Personal baseline (`ayuvo.baseline.robust@1`)
**Purpose.** Compare today with the person's own recent range, not with population averages.

**Inputs.** A day-keyed series. Optional `contexts` and `sources` per day.

**Method.** Use the `window` days before today; today itself is excluded.
- Days whose context is `camera` are left out of the baseline (a spot finger scan is a different measurement from an overnight wearable value).
- `median`, `MAD`, `spread = max(1.4826·MAD, spread_floor)`, robust `z = (value − median) / spread`, and the 10th/90th percentiles (type 7).
- `z_dir` is positive when the value is better than usual for higher-better or lower-better metrics. For band metrics it is never positive.

**Windows and minimum points.**

| Window (days) | Minimum points |
|---|---|
| 7 | 5 |
| 14 | 7 |
| 28 | 14 |
| 60 | 21 |
| 90 | 30 |

**Spread floors** stop a very steady history from making tiny changes look dramatic: HRV 3 ms, resting HR 1 bpm, respiratory rate 0.3 br/min, temperature 0.1 °C, steps 500.

**Source changes.** When today's source differs from the most common source in the window, the result is flagged `source_changed` and confidence is reduced.

**Reference.** Leys et al. 2013 (MAD).

**Limitation.** One person's 28 days are a small sample, so z-scores are descriptive, not tests.

### 5.2 Trend (`ayuvo.trend@1`)
**Method.** Over the last 28 days, including today, with at least 10 readings:
- The Theil–Sen slope is expressed as % of the window median per week.
- The 7-day median is compared with the earlier part of the window, and an EWMA with span 7 is reported.
- With at least 28 readings, a two-sided Page CUSUM runs on robust z against the first 14 points (k = 0.5, h = 4) and reports a change-point day.

**Labels.**

| Label | When |
|---|---|
| `UNUSUAL` | Today's \|robust z\| against the rest of the window is ≥ 3. |
| `STABLE` | The weekly slope is below the metric's stable threshold. |
| `IMPROVING` / `DECLINING` | The slope exceeds the threshold, in the metric's better or worse direction. |
| `CHANGING` | Band metrics such as breathing rate or temperature, which have no better direction. |
| `INSUFFICIENT_DATA` | Fewer than 10 readings. |

Every result reports `window_days`, `sample_count`, `coverage` and `confidence`.

### 5.3 HRV
**What the platforms provide.** Apple Health exposes SDNN (`HKQuantityTypeIdentifierHeartRateVariabilitySDNN`). Health Connect exposes RMSSD (`HeartRateVariabilityRmssdRecord`) and has **no beat-to-beat record type**. The two measures are never compared or mixed.

**`ayuvo.hrv.rmssd@1` (iOS only, from `HKHeartbeatSeriesSample`).**
1. Beat times are differenced into RR intervals; never across a beat with `precededByGap`.
2. Quality filter: 300–2000 ms.
3. Artifact rejection: more than 20% away from the median of in-range neighbours within ±2 beats.
4. No interpolation. Successive differences use only consecutive accepted intervals without a gap.
5. A series with more than 5% artifacts is `INVALID_INPUT`. Fewer than 30 accepted beats is `INSUFFICIENT_DATA`.
6. Outputs RMSSD, SDNN, lnRMSSD and mean HR. The day value is the median of valid series, preferring those that start inside last night's sleep.

References: Task Force 1996; Shaffer & Ginsberg 2017; Lipponen & Tarvainen 2019 on artifacts; Apple HKHeartbeatSeriesQuery documentation.

**`ayuvo.hrv.status@1`.** Combines:
- the 28-day robust baseline;
- the trend;
- stability: the coefficient of variation of ln(HRV) over the last 7 days, needing at least 5 days (Plews et al. 2013).

The UI shows the value, the personal baseline, today's robust z, the trend label and the stability. It never labels a number as universally "good" or "bad".

### 5.4 Sleep
- **Night definition.** From derived `sleep_nights` (algorithm 3): the main episode per wake day, with naps reported separately.
- **Per-night values.** From derived `sleep_night`: asleep, in bed, efficiency, onset latency, WASO, bedtime, wake time and midpoint clock. Efficiency is total sleep time ÷ time in bed. This is the widely published convention, which Reed & Sacco 2016 discuss and argue could use the sleep episode as the denominator instead.
- **Sleep Regularity Index.** Unchanged in derived (Phillips 2017).
- **`ayuvo.sleep.need@1`.** The median main-sleep duration over the 60 nights before today (nights of at least 120 min), clamped to 420–540 min. Below 14 nights the default is 480. The 7 h floor follows the AASM/SRS consensus (Watson 2015). The 9 h ceiling is an Ayuvo choice; the statement itself does not cap sleep.
- **`ayuvo.sleep.status@1`.**
  - Sleep debt: the sum of nightly shortfall against need over the last 14 *recorded* nights. Unrecorded nights are not counted as zero sleep.
  - Bedtime and wake-time SD over 14 nights (at least 5).
  - Duration and efficiency compared with a 28-day robust baseline.
  - Nap minutes.
- **What is shown.** Duration, timing, regularity and efficiency are treated as stronger signals than consumer-device stage splits. Stage percentages stay `PROVIDER_DERIVED`, and sleep debt is never presented as a clinical deficit.

### 5.5 Training load (`ayuvo.load.ewma@1`)
**Inputs.** Workouts merged on overlap. Methods are kept separate and never combined into one number:

| Method | How it is computed |
|---|---|
| Session-RPE load | CR-10 effort × minutes (Foster 2001). Only sessions with an effort count. |
| HR TRIMP | Banister 1991 weighting, from `hr_workout` / derived `heart_day`. |
| Minutes | Duration of the merged sessions. |

**EWMA.**
- λ = 2/(N + 1), with N = 7 days for acute load and 28 days for chronic load (Williams 2017).
- Both are seeded with the mean of the first 28 history days, so a steady routine starts steady.
- The ratio is acute ÷ chronic.
- The primary method is TRIMP when it covers at least 70% of training minutes, otherwise session-RPE, otherwise minutes.

**State.** Compared with the person's own previous 90 days. The reference distribution ends before the current acute week. Absolute guards keep an every-other-day routine from flipping between states. States are checked in this order:

| State | Condition |
|---|---|
| `LOAD_SPIKE` | Ratio ≥ 1.3 and above the 95th percentile. |
| `LOAD_HIGH` | Acute load above the 90th percentile and ≥ 1.1 × the median. |
| `LOAD_INCREASING` | Ratio ≥ 1.2 and above the 80th percentile. |
| `LOAD_REDUCED` | Acute load below the 10th percentile and ≤ 0.8 × the median. |
| `LOAD_STABLE` | None of the above. |

At least 42 history days are needed, plus 28 reference days. These states describe load relative to the person's history. **They are not injury probabilities** (Impellizzeri et al. 2020 explain why the acute:chronic ratio must not be read that way).

### 5.6 Heart rate recovery (`ayuvo.hrr@1`)
- **HRR1:** the peak HR in the last 60 s of exercise minus the HR 60 s after the end (Cole 1999). This is the same rule as workout `hr_recovery`.
- **HRR2:** the same peak minus the HR at 120 s (Shetler 2001).
- **Matching samples:** "at" means the nearest sample within ±30 s.
- **Confidence:** 0.8 when samples are at most 10 s apart (a dense watch series); otherwise 0.4.
- **Apple's own value:** HealthKit `heart_rate_recovery_one_minute` is kept separately as `PROVIDER_DERIVED`.
- **Over time:** both values get a personal baseline and trend.
- **Not a diagnosis:** HRR is never turned into a diagnosis.

### 5.7 Recovery Indicator v2 (`ayuvo.recovery@2`, weights version 1)
Recovery v2 replaces Insights Recovery v1 in the UI. The v1 code and vectors remain in shared/insights.

**Components.** Each is compared with the 60 days before today (at least 7 points):

| Component | Weight | Rule |
|---|---|---|
| HRV | 35 | Higher is better. |
| Resting HR | 25 | Lower is better. |
| Sleep | 25 | 0.5 duration against personal need (0 at 180 min short), 0.25 efficiency z, 0.25 midpoint regularity against the median of 14 nights (0 at 120 min off). |
| Respiratory rate | 5 | Band, tolerance 1 z. |
| Sleeping temperature | 5 | Band, tolerance 1 z. |
| Blood oxygen | 5 | Drop only. |

**Scoring.**
- Each subscore is clamp(50 + 20·z_dir, 0, 100).
- The score is the weighted mean over the available components (weights renormalized), plus the load modifier from yesterday's load state: −6 for a spike, −3 for high.
- **Confidence** is Σ weight × component confidence divided by the total of *all* weights, so missing inputs lower it.

**Statuses and warnings.**
- `INSUFFICIENT_HISTORY` until there are 7 nights and an HRV or resting-HR baseline.
- `NO_DATA` with reason `no_sleep` or `no_heart_data`.
- Warnings: `overnight_fallback`, `camera`, `source_changed`, `missing`.

**Explanation.** The summary sentence is assembled only from the drivers' templates.

**What it is not.** The name is "Recovery Indicator", and it does not measure a biological recovery percentage. Each weight has a "why" in the config; none is presented as a universal constant.

### 5.8 Multi-signal deviation (`ayuvo.anomaly@1`)
**Signals.** Each signal's robust z against its own 28-day baseline (at least 14 points) is taken in its concerning direction:

| Signal | Concerning direction |
|---|---|
| HRV | Low |
| Resting HR | High |
| Respiratory rate | High |
| Temperature | Either way |
| Sleep | Low |
| Steps | Low |
| Weight | Either way |
| Training minutes | High (training days only) |

**States.**

| State | Condition |
|---|---|
| `NORMAL` | No signal at \|z\| ≥ 2. |
| `MILD_DEVIATION` | Any signal ≥ 2. |
| `SIGNIFICANT_DEVIATION` | Any signal ≥ 3. |
| `MULTI_SIGNAL_DEVIATION` | At least 3 signals ≥ 2. |

**Persistence.** When the last 3 days are all significant or multi-signal, the result is `persistent` and the copy adds the healthcare-professional note.

**Copy.** For example: "Several health signals are outside your recent normal range." It never names a condition.

### 5.9 Correlation (`ayuvo.correlation@1`) and personal response (`ayuvo.response@1`)
**Pairs.** Sleep → HRV and resting HR, load → HRV, resting HR and HRR, bedtime → sleep duration, and energy intake → weight. Each pair is tested at lags 0–3 days over 120 days.

**Correlation.**
- At least 21 paired days, otherwise the pair and lag are not tested.
- Pearson and Spearman correlation, with average ranks for ties.
- Fisher-z 95% CI and p-value for ρ, with SE = √(1.06/(n−3)) (Fieller 1957).
- Benjamini–Hochberg q across every tested pair and lag.
- A pattern is surfaced when q ≤ 0.1 and |ρ| ≥ 0.3.
- Copy: "Higher training-load days were associated with lower next-day HRV in your recent data (n days)." It is never causal.

**Personal response.**
- At least 28 pairs.
- Theil–Sen effect (outcome units per exposure unit) with Sen's rank-based 95% interval. There is no tie correction, which is documented.

### 5.10 Energy (`ayuvo.energy@1`)
The outputs are kept separate:
- `predicted_resting_kcal` (Mifflin–St Jeor);
- `provider_basal_kcal`;
- `provider_active_kcal` (platform de-duplicated, Ayuvo burns excluded);
- `provider_workout_kcal` (informational, already inside active);
- `ayuvo_extra_kcal` (Ayuvo sessions that no provider workout overlaps).

**Resting energy.** Provider basal when it is at least 0.7 × Mifflin (a full day); otherwise Mifflin.

**Estimate.** `estimated_daily_expenditure = (resting + active) / (1 − 0.10)`, where the 10% is the thermic effect of food. It is never BMR × activity multiplier plus active energy.

**Confidence.** 0.8 × 0.95 with provider basal, 0.6 × 0.95 with predicted resting energy. Wearable energy is an estimate, not ground truth.

**Adaptive energy.** The existing derived `adaptive_tdee` (intake and weight trend) remains the adaptive layer, classified `PERSONALIZED_STATISTICAL`. Hall et al. 2011 explain why the energy density of weight change is not a constant.

### 5.11 Fitness (`ayuvo.fitness.vo2max_trend@1`) and MET intensity (`ayuvo.met.intensity@1`)
**VO2 max.** Readings are kept per kind and never mixed:

| Kind | Classification / status |
|---|---|
| Provider | `PROVIDER_REPORTED` |
| GPS ACSM estimate | `SCIENTIFIC_DERIVED` |
| Uth heart-rate ratio | `EXPERIMENTAL` |

Each kind reports its latest value, the change against readings at least 60 days older, and a Theil–Sen slope per 30 days (at least 4 readings).

**MET intensity.**
- One representative 2024 Adult Compendium entry per activity type (table below, with codes). Unknown activity types get no MET.
- Overlapping activities keep the higher MET.
- Weekly light, moderate (3.0–5.9 MET) and vigorous (≥ 6) minutes, plus moderate-equivalent minutes (moderate + 2 × vigorous) compared with the WHO 2020 target of 150.

### 5.12 Forecast (`ayuvo.forecast`, model version 1, feature schema 1, `ML_PREDICTED`)
**Setup.** A per-user ridge regression predicting next-day HRV and resting HR from today's features (schema in config `forecast.features`). Missing features are imputed with the training median and get a missing-indicator column.

**Training and evaluation.**
- **Temporal split:** 70/15/15, never random.
- **λ:** chosen on validation MAE from {0.1, 1, 10, 100}.
- **Scoring:** refit on train + validation, then scored on the newest 15% against persistence (today's value) and the 28-day median. Reported metrics: MAE, RMSE, R², bias and empirical coverage of the ±80th-percentile interval.
- **Deployment gate:** a model is **deployed only when its test MAE is at least 5% better than both baselines** and there are at least 90 rows, 14 of them in the test block.

**Governance.**
- The final model is refit on all rows.
- Every retrain is stored in `ml_models` with its periods, λ, coefficients, normalization and metrics.

**Use and runtime.**
- The forecast sits behind `analyticsForecastEnabled`, which is off by default.
- It is never fed into Recovery v2.
- It runs as plain linear algebra; Core ML or TFLite would add nothing for a dot product.
- Ayuvo has no labelled multi-user dataset, so nothing transfers between people, and usefulness is proven per person by the gate or the model is not shown.

## 6. Storage and incremental processing
Schema v5 (`shared/health/schema.sql`) adds three tables:
- `analytics_results`, keyed by (metric, period start, algorithm version). It holds the status, classification, value(s), confidence, coverage, the full result JSON, provenance and input hash.
- `analytics_state`, the last processed day per metric and version.
- `ml_models`.

When an algorithm version changes, new rows are written next to the old ones instead of overwriting them, and only the two newest versions are kept. Like `derived_daily_values`, these tables are recomputable and **not part of portable export**.

The platform service runs after the debounced derived refresh. It hashes each day's canonical inputs and recomputes only days whose hash, algorithm version or config version changed.

## 7. Privacy
Everything is computed on the device from the local mirror. No server is involved and no health data is uploaded to compute anything. The Coach evidence object (§8) contains derived values only, and it is sent to a cloud model only when the person uses a cloud Coach provider with health-data access switched on (the existing `coachHealthDataEnabled` consent).

## 8. Coach evidence
`insights.evidence.get` (Coach tool `get_health_evidence`) returns `evidence()` from the reference. The items are:
- `recovery_indicator`: score, label, drivers with value, baseline, z and unit, and warnings;
- `multi_signal_deviation`;
- `hrv`: kind, value, baseline, z and trend;
- `sleep`: need, debt, efficiency and variability;
- `training_load`: state, method, acute, chronic and ratio.

Each item carries its status, classification, confidence and `algorithm@version`.

The Coach prompt rules:
- Explain these values; never calculate HRV, RMSSD, sleep efficiency, recovery, load, VO2 max, blood pressure or SpO2.
- Mention confidence when it is below 75%.
- Use association language.
- Never diagnose.
- Suggest discussing persistent changes with a qualified professional.

Camera SpO2 and BP are labelled `RESEARCH_ONLY` in the Coach's camera summary.

## 9. What is not claimed
No accuracy is claimed for any analytics output, because no reference-device validation has been done yet (docs/health-validation.md). Recovery v2 weights, sleep-need clamps, load guards and signal thresholds are Ayuvo design choices, documented as such.

## 10. Tests
- `python3 scripts/analytics_contract_check.py` (23 vector files, coverage tags for every status and state, unit tests).
- `AnalyticsVectorTests` on Android and iOS.
- The existing derived, workout, insights and actions contract checks and their platform vector tests.

## 11. Generated reference
<!-- BEGIN GENERATED ANALYTICS -->

_Generated from `shared/analytics/analytics_config.json` by `scripts/analytics_contract_check.py --write`. Do not edit by hand._

Config version: 1. Numerical tolerance between the reference and the apps: 1e-09.

### Algorithm registry

| Algorithm | Version | Classification | Inputs | Quality gate | Fallback | References |
|---|---|---|---|---|---|---|
| `ayuvo.source.select` | 1 | PROVIDER_DERIVED | discrete health_samples rows of one metric and day | min_n 1 | average_all for metrics without a policy | Ayuvo design |
| `ayuvo.baseline.robust` | 1 | PERSONALIZED_STATISTICAL | day-keyed series of one metric | min_points per window, see baseline.min_points | INSUFFICIENT_HISTORY below min_points; NO_DATA without today's value | [leys_2013](#ref-leys_2013) |
| `ayuvo.trend` | 1 | PERSONALIZED_STATISTICAL | day-keyed series of one metric | min_points 10 | INSUFFICIENT_DATA below 10 readings | [theil_1950](#ref-theil_1950), [sen_1968](#ref-sen_1968), [page_1954](#ref-page_1954) |
| `ayuvo.hrv.rmssd` | 1 | SCIENTIFIC_DERIVED | HKHeartbeatSeriesSample beat times (iOS only) | max_artifact_fraction 0.05, min_beats 30 | INVALID_INPUT above 5% artifacts; INSUFFICIENT_DATA below 30 accepted beats | [task_force_1996](#ref-task_force_1996), [shaffer_2017](#ref-shaffer_2017), [lipponen_2019](#ref-lipponen_2019), [apple_heartbeat](#ref-apple_heartbeat) |
| `ayuvo.hrv.status` | 1 | PERSONALIZED_STATISTICAL | provider HRV (SDNN on iOS, RMSSD on Android) or Ayuvo RMSSD | baseline_min_points 14, stability_min_days 5 | per sub-result status | [plews_2013](#ref-plews_2013), [buchheit_2014](#ref-buchheit_2014), [apple_hrv_sdnn](#ref-apple_hrv_sdnn), [hc_hrv](#ref-hc_hrv) |
| `ayuvo.sleep.need` | 1 | PERSONALIZED_STATISTICAL | main-sleep asleep minutes per wake day | min_nights 14 | 480 min below 14 nights | [watson_2015](#ref-watson_2015) |
| `ayuvo.sleep.status` | 1 | SCIENTIFIC_DERIVED | per-night summaries from shared/derived sleep_night | min_night_minutes 120 | NO_DATA without last night | [reed_sacco_2016](#ref-reed_sacco_2016), [phillips_2017](#ref-phillips_2017), [watson_2015](#ref-watson_2015) |
| `ayuvo.load.ewma` | 1 | SCIENTIFIC_DERIVED | workouts with effort (CR-10) and TRIMP when available | min_history_days 42, state_min_days 28 | INSUFFICIENT_HISTORY below 42 days | [foster_2001](#ref-foster_2001), [banister_1991](#ref-banister_1991), [williams_2017](#ref-williams_2017), [impellizzeri_2020](#ref-impellizzeri_2020) |
| `ayuvo.hrr` | 1 | SCIENTIFIC_DERIVED | heart-rate samples around the end of a workout | tolerance_s 30 | INSUFFICIENT_DATA without a peak and a 60 s sample | [cole_1999](#ref-cole_1999), [shetler_2001](#ref-shetler_2001) |
| `ayuvo.recovery` | 2 | PERSONALIZED_STATISTICAL | hrv; resting_heart_rate; sleep nights; respiratory_rate; wrist_temperature; blood_oxygen; workouts | min_points 7 | INSUFFICIENT_HISTORY / NO_DATA, never a made-up score | [plews_2013](#ref-plews_2013), [buchheit_2014](#ref-buchheit_2014), [williams_2017](#ref-williams_2017) |
| `ayuvo.anomaly` | 1 | PERSONALIZED_STATISTICAL | hrv; resting_heart_rate; respiratory_rate; wrist_temperature; sleep; steps; weight; training minutes | min_points 14, min_signals 2 | INSUFFICIENT_DATA below 2 evaluable signals | [leys_2013](#ref-leys_2013) |
| `ayuvo.correlation` | 1 | PERSONALIZED_STATISTICAL | paired day series | min_n 21 | INSUFFICIENT_DATA below 21 pairs | [fieller_1957](#ref-fieller_1957), [bh_1995](#ref-bh_1995), [abramowitz_stegun](#ref-abramowitz_stegun) |
| `ayuvo.response` | 1 | PERSONALIZED_STATISTICAL | paired day series | min_n 28 | INSUFFICIENT_DATA below 28 pairs | [sen_1968](#ref-sen_1968), [theil_1950](#ref-theil_1950) |
| `ayuvo.energy` | 1 | SCIENTIFIC_DERIVED | provider basal and active energy; Ayuvo workout burns; profile | bmr_min_share 0.7 | Mifflin-St Jeor when basal is missing or partial | [mifflin_1990](#ref-mifflin_1990), [hall_2011](#ref-hall_2011) |
| `ayuvo.fitness.vo2max_trend` | 1 | PROVIDER_DERIVED | provider VO2 max; GPS ACSM estimate; Uth ratio estimate | slope_min_points 4 | NO_DATA per kind without readings; change and slope stay null until enough readings | [uth_2004](#ref-uth_2004), [tanaka_2001](#ref-tanaka_2001) |
| `ayuvo.met.intensity` | 1 | SCIENTIFIC_DERIVED | workouts with an activity key | — | unknown activities get no MET | [herrmann_2024](#ref-herrmann_2024), [who_2020](#ref-who_2020) |
| `ayuvo.forecast` | 1 | ML_PREDICTED | feature schema v1 | min_rows 90, min_test_rows 14 | hidden unless it beats persistence and the 28-day median by 5% | [hoerl_1970](#ref-hoerl_1970) |

### Classifications

| Classification | Shown as | Confidence cap | Meaning |
|---|---|---|---|
| `MEASURED` | Measured | 1 | A reading recorded directly by a sensor or entered by you. |
| `PROVIDER_DERIVED` | From your device | 0.95 | Calculated by Apple Health, Health Connect or your wearable's own software. Ayuvo shows it as reported. |
| `SCIENTIFIC_DERIVED` | Calculated | 0.95 | Calculated by Ayuvo with a published, documented method. |
| `PERSONALIZED_STATISTICAL` | Compared with your history | 0.9 | A statistical comparison with your own recent history. Weights and thresholds are Ayuvo design choices. |
| `ML_PREDICTED` | Forecast | 0.7 | A prediction from a small model trained only on your own data. Forecasts can be wrong. |
| `EXPERIMENTAL` | Experimental | 0.5 | An estimate whose accuracy has not been established. |
| `RESEARCH_ONLY` | Research only | 0.3 | A research feature. It is not a measurement and is never used in scores. |

### Recovery Indicator v2 weights (weights version 1)

| Component | Weight | Mode | Why |
|---|---|---|---|
| hrv | 35 | higher | HRV is the most responsive overnight marker of how your body is coping, when compared with your own baseline (Plews 2013, Buchheit 2014). |
| resting_heart_rate | 25 | lower | A resting heart rate above your usual level often accompanies short sleep, heavy training or feeling unwell, so it gets the second-largest weight. |
| sleep | 25 | sleep | Sleep duration, efficiency and timing are the best-measured sleep signals on consumer devices. |
| respiratory_rate | 5 | band | Overnight breathing rate is very stable for most people, so only a clear change in either direction counts. |
| wrist_temperature | 5 | band | Sleeping temperature only counts when it moves clearly outside your usual range. |
| blood_oxygen | 5 | drop_only | Only a drop below your usual overnight level counts; higher readings never add points. |

### MET table (2024 Adult Compendium)

| Activity key | Code | MET | Compendium description |
|---|---|---|---|
| `badminton` | 15030 | 5.5 | Badminton, social singles and doubles, general |
| `basketball` | 15055 | 7.5 | Basketball, general |
| `boxing` | 15110 | 5.8 | Boxing, punching bag |
| `climbing` | 15533 | 8 | Rock or mountain climbing (Taylor Code 060), (formerly code 17120) |
| `cross_country_skiing` | 19090 | 8.5 | Skiing, cross country, 4.0-4.9 mph, moderate speed and effort |
| `cycling` | 01014 | 7 | Bicycling, general |
| `dancing` | 03025 | 4.5 | Ethnic or cultural dancing (e.g. Greek, Middle Eastern, hula, salsa, merengue, bamba y plena, flamenco, belly, and swing) |
| `elliptical` | 02048 | 5 | Elliptical trainer, moderate effort |
| `golf` | 15265 | 4.3 | Golf, walking, carrying clubs |
| `hiit` | 02040 | 7.5 | Circuit training, including kettlebells, some aerobic movement with minimal rest, general, vigorous intensity |
| `hiking` | 17080 | 6 | Hiking, cross country (Taylor Code 040) |
| `jump_rope` | 02068 | 11 | Rope skipping exercise, general |
| `martial_arts` | 15430 | 10.3 | Martial Arts, different types, moderate pace (e.g., judo, jujitsu, karate, kick boxing, tae kwon do, tai-bo, Muay Thai boxing) |
| `mind_body` | 02101 | 2.3 | Stretching, mild |
| `pilates` | 02105 | 2.8 | Pilates, general |
| `rowing_machine` | 02071 | 5 | Rowing, stationary ergometer, general, <100 watts, moderate effort |
| `running` | 12145 | 10.5 | Running, self-selected pace |
| `skating` | 19030 | 7 | Skating, ice, general (Taylor Code 360) |
| `skiing_downhill` | 19160 | 6.3 | Skiing, downhill, alpine or snowboarding, moderate effort |
| `soccer` | 15610 | 7 | Soccer, casual, general (Taylor Code 540) |
| `stair_climbing` | 02065 | 9.3 | Stair treadmill ergometer, general |
| `stationary_cycling` | 01200 | 6.8 | Bicycling, stationary, general |
| `strength_training` | 02054 | 3.5 | Resistance (weight) training, multiple exercises, 8-15 reps at varied resistance |
| `swimming` | 18310 | 6 | Swimming, leisurely, not lap swimming, general |
| `table_tennis` | 15660 | 4 | Table tennis, ping pong (Taylor Code 410) |
| `tennis` | 15675 | 6.8 | Tennis, general, moderate effort |
| `volleyball` | 15720 | 3 | Volleyball, non-competitive, 6 – 9 member team, general |
| `walking` | 17190 | 3.8 | Walking, 2.8 to 3.4 mph, level, moderate pace, firm surface |
| `yoga` | 02150 | 2.3 | Yoga, Hatha |

### References

- <a id="ref-abramowitz_stegun"></a>**abramowitz_stegun** — Abramowitz M, Stegun IA, eds. Handbook of Mathematical Functions with Formulas, Graphs, and Mathematical Tables. National Bureau of Standards Applied Mathematics Series 55. Washington, DC: US Government Printing Office; 1964 (Dover reprint 1965). Formula 7.1.26, p. 299. https://dlmf.nist.gov/7  
  _Supports:_ Formula 7.1.26 gives erf(x) ~ 1 - (a1 t + a2 t^2 + a3 t^3 + a4 t^4 + a5 t^5) e^(-x^2), t = 1/(1+px), p = 0.3275911, a1..a5 = 0.254829592, -0.284496736, 1.421413741, -1.453152027, 1.061405429, with |error| <= 1.5e-7 for x >= 0 (due to Hastings 1955).  
  _Limitations:_ Valid for x >= 0; use erf(-x) = -erf(x) for negative arguments; normal CDF Phi(z) = 0.5(1 + erf(z/sqrt2)). Absolute (not relative) error, so tail probabilities far from 0 lose relative precision. No DOI; NIST DLMF (https://dlmf.nist.gov/) is the official successor. Page number from standard citation.
- <a id="ref-apple_heartbeat"></a>**apple_heartbeat** — Apple Inc. HKHeartbeatSeriesSample and HKHeartbeatSeriesQuery (init(heartbeatSeries:dataHandler:)). Apple Developer Documentation, HealthKit. https://developer.apple.com/documentation/healthkit/hkheartbeatseriesquery  
  _Supports:_ HKHeartbeatSeriesSample is 'a sample that represents a series of heartbeats', read via HKHeartbeatSeriesQuery. The query's dataHandler is called once per heartbeat with timeSinceSeriesStart (time of the heartbeat measured from the series start date; positive TimeInterval in seconds), precededByGap (Bool: heartbeat immediately preceded by a gap; one or more beats may be missing), done, and error. Fields confirmed. URLs: https://developer.apple.com/documentation/healthkit/hkheartbeatseriessample ; https://developer.apple.com/documentation/healthkit/hkheartbeatseriesquery ; https://developer.apple.com/documentation/healthkit/hkheartbeatseriesquery/init(heartbeatseries:datahandler:)  
  _Limitations:_ Apple gives beat timestamps, not RR intervals: RR must be computed as successive differences and must not span a beat with precededByGap = true. Series are recorded mainly during Apple Watch Breathe/background HRV readings (~1 min, PPG-derived), so availability is sparse. A HKHeartbeatSeriesQueryDescriptor async API also exists.
- <a id="ref-apple_hrv_sdnn"></a>**apple_hrv_sdnn** — Apple Inc. HKQuantityTypeIdentifier.heartRateVariabilitySDNN. Apple Developer Documentation, HealthKit. https://developer.apple.com/documentation/healthkit/hkquantitytypeidentifier/heartratevariabilitysdnn  
  _Supports:_ Apple states: 'A quantity sample type that measures the standard deviation of heartbeat intervals... While there are multiple ways of computing HRV, HealthKit uses SDNN heart rate variability, which uses the standard deviation of the inter-beat (RR) intervals between normal heartbeats (typically measured in milliseconds). The system automatically records samples on Apple Watch.'  
  _Limitations:_ Apple Watch SDNN comes from short (~1 min) PPG recordings, so it is not comparable to 24-h or 5-min ECG SDNN norms (Task Force) nor to RMSSD from Health Connect.
- <a id="ref-banister_1991"></a>**banister_1991** — Banister EW. Modeling elite athletic performance. In: MacDougall JD, Wenger HA, Green HJ, eds. Physiological Testing of the High-Performance Athlete. 2nd ed. Champaign, IL: Human Kinetics; 1991:403-424.  
  _Supports:_ Book chapter presenting the impulse-response fitness/fatigue model and the HR-reserve-weighted TRIMP = duration x dHR x y, with y = 0.64e^(1.92 dHR) for men and 0.86e^(1.67 dHR) for women.  
  _Limitations:_ IMPORTANT: the female weighting is 0.86e^(1.67x), not 0.64e^(1.67x) - correct the app/docs if they use 0.64 for women. Chapter not indexed (no DOI/PMID); bibliographic details and coefficients confirmed via secondary sources, not the book itself. Weighting derived from lactate-HR relationships in small samples; assumes valid HRrest/HRmax.
- <a id="ref-bh_1995"></a>**bh_1995** — Benjamini Y, Hochberg Y. Controlling the false discovery rate: a practical and powerful approach to multiple testing. J R Stat Soc Series B Stat Methodol. 1995;57(1):289-300. doi:[10.1111/j.2517-6161.1995.tb02031.x](https://doi.org/10.1111/j.2517-6161.1995.tb02031.x)  
  _Supports:_ Defines the false discovery rate and the step-up procedure (sort p-values; reject p(i) <= (i/m)q) that controls FDR for independent tests.  
  _Limitations:_ FDR control proven for independent (later: positively dependent) tests; correlated health metrics may need Benjamini-Yekutieli; p-values must be valid in the first place. Full title includes the subtitle.
- <a id="ref-bland_altman_1986"></a>**bland_altman_1986** — Bland JM, Altman DG. Statistical methods for assessing agreement between two methods of clinical measurement. Lancet. 1986;1(8476):307-310. doi:[10.1016/S0140-6736(86)90837-8](https://doi.org/10.1016/S0140-6736(86)90837-8) PMID 2868172.  
  _Supports:_ Argues correlation is inappropriate for method agreement and introduces the difference-vs-mean plot with 95% limits of agreement (mean difference +/- 1.96 SD of differences).  
  _Limitations:_ Assumes differences are roughly normal and do not vary with magnitude (otherwise log-transform or regression-based LoA); single measurement per subject; Lancet volume 327 in Crossref = 1986 vol 1 issue 8476.
- <a id="ref-buchheit_2014"></a>**buchheit_2014** — Buchheit M. Monitoring training status with HR measures: do all roads lead to Rome? Front Physiol. 2014;5:73. doi:[10.3389/fphys.2014.00073](https://doi.org/10.3389/fphys.2014.00073) PMID 24578692.  
  _Supports:_ Review arguing that resting vagal HRV indices (from short, near-daily recordings), submaximal exercise HR and HR recovery are useful monitoring tools, and that individual changes should be interpreted against the measure's typical error and smallest worthwhile/important change in the training context.  
  _Limitations:_ Narrative review focused on athletes; SWC values cited are from ECG/chest-strap protocols with standardized conditions; HR measures do not capture all aspects of fatigue.
- <a id="ref-cole_1999"></a>**cole_1999** — Cole CR, Blackstone EH, Pashkow FJ, Snader CE, Lauer MS. Heart-rate recovery immediately after exercise as a predictor of mortality. N Engl J Med. 1999;341(18):1351-1357. doi:[10.1056/NEJM199910283411804](https://doi.org/10.1056/NEJM199910283411804) PMID 10536127.  
  _Supports:_ In 2,428 adults referred for symptom-limited exercise testing, HR recovery defined as peak HR minus HR 1 min after exercise cessation (abnormal <=12 bpm with a cool-down walk) independently predicted 6-year mortality.  
  _Limitations:_ Clinical treadmill protocol with standardized cool-down; the 12-bpm cutoff is protocol-specific (passive recovery uses different cutoffs); referral population; wearable workout HR data are not equivalent to a graded max test.
- <a id="ref-fieller_1957"></a>**fieller_1957** — Fieller EC, Hartley HO, Pearson ES. Tests for rank correlation coefficients. I. Biometrika. 1957;44(3-4):470-481. doi:[10.1093/biomet/44.3-4.470](https://doi.org/10.1093/biomet/44.3-4.470)  
  _Supports:_ Simulation study of Fisher z-transformed rank correlations, proposing approximate variance 1.060/(n-3) for z of Spearman's rho (and 0.437/(n-4) for Kendall's tau).  
  _Limitations:_ Approximation derived for bivariate normal samples of moderate size (n roughly >=10); less accurate for small n or very high correlations; ties and autocorrelation not addressed.
- <a id="ref-foster_2001"></a>**foster_2001** — Foster C, Florhaug JA, Franklin J, Gottschall L, Hrovatin LA, Parker S, Doleshal P, Dodge C. A new approach to monitoring exercise training. J Strength Cond Res. 2001;15(1):109-115. doi:[10.1519/00124278-200102000-00019](https://doi.org/10.1519/00124278-200102000-00019) PMID 11708692.  
  _Supports:_ Validates the session-RPE method (CR-10 session RPE multiplied by session duration in minutes) against an HR-based summated-zone method during cycling and basketball.  
  _Limitations:_ Small samples; session-RPE produced systematically higher absolute scores than the HR method; RPE should be collected ~30 min post-session. DOI is registered in Crossref but not listed in PubMed.
- <a id="ref-hall_2011"></a>**hall_2011** — Hall KD, Sacks G, Chandramohan D, Chow CC, Wang YC, Gortmaker SL, Swinburn BA. Quantification of the effect of energy imbalance on bodyweight. Lancet. 2011;378(9793):826-837. doi:[10.1016/S0140-6736(11)60812-X](https://doi.org/10.1016/S0140-6736(11)60812-X) PMID 21872751.  
  _Supports:_ Presents a validated dynamic model showing that the energy content of weight change depends on body composition and changes over time, and that the static ~3500 kcal/lb (~7700 kcal/kg) rule overestimates long-term weight change because energy expenditure adapts; offers the approximation of ~10 kcal/day per lb (~22 kcal/day per kg) at steady state with half the change reached in ~1 year.  
  _Limitations:_ Model calibrated on adult population data; individual variation (adherence, water shifts, adaptive thermogenesis) remains; short-term scale changes are dominated by water/glycogen.
- <a id="ref-hc_hrv"></a>**hc_hrv** — Android Developers. HeartRateVariabilityRmssdRecord; Health Connect data types. Jetpack Health Connect client documentation. https://developer.android.com/health-and-fitness/guides/health-connect/plan/data-types  
  _Supports:_ Health Connect provides HeartRateVariabilityRmssdRecord (instantaneous RMSSD in milliseconds at a time point). The Health Connect data-types list for vitals contains HeartRateRecord (BPM series), HeartRateVariabilityRmssdRecord and RestingHeartRateRecord, with no RR/inter-beat-interval or heartbeat-series record type. URLs: https://developer.android.com/reference/kotlin/androidx/health/connect/client/records/HeartRateVariabilityRmssdRecord ; https://developer.android.com/health-and-fitness/guides/health-connect/plan/data-types  
  _Limitations:_ Absence of an RR record confirmed from the data-types guide (current as of fetch, Oct 2026); the record class page itself could not be fully read via fetch. RMSSD values are computed by the writing app/device with undisclosed methods and windows, so they are not comparable to Apple SDNN. Future Health Connect versions could add types.
- <a id="ref-herrmann_2024"></a>**herrmann_2024** — Herrmann SD, Willis EA, Ainsworth BE, Barreira TV, Hastert M, Kracht CL, et al. 2024 Adult Compendium of Physical Activities: a third update of the energy costs of human activities. J Sport Health Sci. 2024;13(1):6-12. doi:[10.1016/j.jshs.2023.10.010](https://doi.org/10.1016/j.jshs.2023.10.010) PMID 38242596.  
  _Supports:_ Updated compendium of MET values for 1,114 activities with an accompanying online database; METs are standardized (1 MET = 3.5 mL O2/kg/min) estimates of energy cost.  
  _Limitations:_ Supports MET values per activity. The intensity bands (light <3, moderate 3-5.9, vigorous >=6) are conventional and are better cited to WHO 2020/US guidelines than to this paper. MET values are population averages, not individual energy cost; 1 MET convention overestimates RMR for many adults.
- <a id="ref-hoerl_1970"></a>**hoerl_1970** — Hoerl AE, Kennard RW. Ridge regression: biased estimation for nonorthogonal problems. Technometrics. 1970;12(1):55-67. doi:[10.1080/00401706.1970.10488634](https://doi.org/10.1080/00401706.1970.10488634)  
  _Supports:_ Introduces ridge regression, adding k*I to X'X to obtain biased but lower-MSE coefficient estimates under multicollinearity, with the ridge trace for choosing k.  
  _Limitations:_ Choice of penalty k is not prescribed (ridge trace is heuristic; cross-validation is modern practice); predictors should be standardized; coefficients are biased so p-values/CIs from OLS do not apply.
- <a id="ref-impellizzeri_2020"></a>**impellizzeri_2020** — Impellizzeri FM, Tenan MS, Kempton T, Novak A, Coutts AJ. Acute:chronic workload ratio: conceptual issues and fundamental pitfalls. Int J Sports Physiol Perform. 2020;15(6):907-913. doi:[10.1123/ijspp.2019-0864](https://doi.org/10.1123/ijspp.2019-0864) PMID 32502973.  
  _Supports:_ Concludes there is no evidence supporting ACWR use in training-load management or injury-risk reduction; the ratio is statistically flawed, adds noise and creates artifacts, and its causal link to injury is unestablished.  
  _Limitations:_ Critical commentary (conceptual/statistical), not new injury data; directly supports presenting ACWR as descriptive only, never as an injury predictor.
- <a id="ref-leys_2013"></a>**leys_2013** — Leys C, Ley C, Klein O, Bernard P, Licata L. Detecting outliers: do not use standard deviation around the mean, use absolute deviation around the median. J Exp Soc Psychol. 2013;49(4):764-766. doi:[10.1016/j.jesp.2013.03.013](https://doi.org/10.1016/j.jesp.2013.03.013)  
  _Supports:_ Recommends median absolute deviation (MAD) scaled by b = 1.4826 (consistency constant under normality) for outlier detection, with a suggested threshold of 2.5 (moderately conservative).  
  _Limitations:_ Not indexed in PubMed. The 1.4826 constant assumes underlying normality; MAD = 0 when >50% of values are identical (needs a fallback); the paper is a brief methods note.
- <a id="ref-lipponen_2019"></a>**lipponen_2019** — Lipponen JA, Tarvainen MP. A robust algorithm for heart rate variability time series artefact correction using novel beat classification. J Med Eng Technol. 2019;43(3):173-181. doi:[10.1080/03091902.2019.1640306](https://doi.org/10.1080/03091902.2019.1640306) PMID 31314618.  
  _Supports:_ Presents an algorithm (used in Kubios) that detects ectopic, missed, extra and long/short beats in RR series using successive-difference (dRR) features with time-varying thresholds and corrects them.  
  _Limitations:_ Validated mainly on ECG-derived RR series with simulated artifacts; performance on PPG/wearable inter-beat intervals not established; threshold parameters are algorithm-specific.
- <a id="ref-mifflin_1990"></a>**mifflin_1990** — Mifflin MD, St Jeor ST, Hill LA, Scott BJ, Daugherty SA, Koh YO. A new predictive equation for resting energy expenditure in healthy individuals. Am J Clin Nutr. 1990;51(2):241-247. doi:[10.1093/ajcn/51.2.241](https://doi.org/10.1093/ajcn/51.2.241) PMID 2305711.  
  _Supports:_ Derives the REE equation 10*weight(kg) + 6.25*height(cm) - 5*age(y) + 5 (men) or -161 (women) from indirect calorimetry in 498 healthy adults.  
  _Limitations:_ Healthy, mostly white US adults aged 19-78; individual error commonly +/-10%; less accurate at extremes of BMI, in athletes and in some ethnic groups.
- <a id="ref-morton_1990"></a>**morton_1990** — Morton RH, Fitz-Clarke JR, Banister EW. Modeling human performance in running. J Appl Physiol (1985). 1990;69(3):1171-1177. doi:[10.1152/jappl.1990.69.3.1171](https://doi.org/10.1152/jappl.1990.69.3.1171) PMID 2246166.  
  _Supports:_ Describes the two-component (fitness/fatigue) impulse-response model in which a quantified training impulse (TRIMP based on duration and fractional HR reserve, exponentially weighted) predicts performance.  
  _Limitations:_ Abstract does not state the TRIMP coefficients; the exponential weighting is in the full text/Banister 1991 (cite Banister 1991 for the sex-specific coefficients). Small samples; model parameters are individual-specific.
- <a id="ref-page_1954"></a>**page_1954** — Page ES. Continuous inspection schemes. Biometrika. 1954;41(1-2):100-115. doi:[10.1093/biomet/41.1-2.100](https://doi.org/10.1093/biomet/41.1-2.100)  
  _Supports:_ Introduces cumulative-sum (CUSUM) schemes for detecting a shift in the mean of a process.  
  _Limitations:_ Classic CUSUM assumes independent observations and known in-control mean/variance; reference value k and threshold h must be chosen; autocorrelation and missing days raise false alarms.
- <a id="ref-phillips_2017"></a>**phillips_2017** — Phillips AJK, Clerx WM, O'Brien CS, Sano A, Barger LK, Picard RW, Lockley SW, Klerman EB, Czeisler CA. Irregular sleep/wake patterns are associated with poorer academic performance and delayed circadian and sleep/wake timing. Sci Rep. 2017;7(1):3216. doi:[10.1038/s41598-017-03171-4](https://doi.org/10.1038/s41598-017-03171-4) PMID 28607474.  
  _Supports:_ Introduces the Sleep Regularity Index: the probability that an individual is in the same state (asleep vs awake) at any two time points 24 h apart, rescaled so 100 = perfectly regular and 0 = random (theoretical range -100..100); links lower SRI to later DLMO and poorer grades in 61 undergraduates.  
  _Limitations:_ Small sample of 61 students, sleep diaries; SRI needs multiple consecutive days at fine (e.g., minute/epoch) resolution and is sensitive to missing data; associations are observational.
- <a id="ref-plews_2013"></a>**plews_2013** — Plews DJ, Laursen PB, Stanley J, Kilding AE, Buchheit M. Training adaptation and heart rate variability in elite endurance athletes: opening the door to effective monitoring. Sports Med. 2013;43(9):773-781. doi:[10.1007/s40279-013-0071-8](https://doi.org/10.1007/s40279-013-0071-8) PMID 23852425.  
  _Supports:_ Review proposing practical HRV monitoring with log-transformed RMSSD (lnRMSSD), rolling (e.g., 7-day) averages and the coefficient of variation of lnRMSSD to interpret individual day-to-day changes; illustrated with elite athlete case data.  
  _Limitations:_ Elite endurance athletes and case examples only; the CV/rolling-average approach is proposed, not validated against outcomes in general populations; HRV 'saturation' issues noted.
- <a id="ref-reed_sacco_2016"></a>**reed_sacco_2016** — Reed DL, Sacco WP. Measuring sleep efficiency: what should the denominator be? J Clin Sleep Med. 2016;12(2):263-266. doi:[10.5664/jcsm.5498](https://doi.org/10.5664/jcsm.5498) PMID 26194727.  
  _Supports:_ Notes that sleep efficiency is commonly defined as TST/TIB x 100, but argues TIB is a flawed denominator and proposes SE = TST / duration of sleep episode (SOL + TST + WASO + time attempting to sleep after final awakening).  
  _Limitations:_ Supports TST/TIB only as the widely published convention; the paper actually recommends a different denominator (DSE). Diary-oriented commentary, no empirical validation. Docs should note that TIB includes non-sleep time in bed.
- <a id="ref-sen_1968"></a>**sen_1968** — Sen PK. Estimates of the regression coefficient based on Kendall's tau. J Am Stat Assoc. 1968;63(324):1379-1389. doi:[10.1080/01621459.1968.10480934](https://doi.org/10.1080/01621459.1968.10480934)  
  _Supports:_ Extends Theil's estimator to handle ties in x, defining the slope as the median of pairwise slopes and deriving a distribution-free confidence interval from the null distribution of Kendall's tau (rank-based order statistics of the pairwise slopes).  
  _Limitations:_ Assumes independent observations; autocorrelated daily health series inflate confidence; CI is non-parametric and requires enough pairs.
- <a id="ref-shaffer_2017"></a>**shaffer_2017** — Shaffer F, Ginsberg JP. An overview of heart rate variability metrics and norms. Front Public Health. 2017;5:258. doi:[10.3389/fpubh.2017.00258](https://doi.org/10.3389/fpubh.2017.00258) PMID 29034226.  
  _Supports:_ Narrative review defining time-, frequency- and nonlinear HRV metrics (incl. SDNN, RMSSD), recording lengths (24 h, short-term ~5 min, ultra-short <5 min) and published norms.  
  _Limitations:_ Narrative review, not primary data; norms are from heterogeneous ECG studies; article number 258 (no page range).
- <a id="ref-shetler_2001"></a>**shetler_2001** — Shetler K, Marcus R, Froelicher VF, Vora S, Kalisetti D, Prakash M, Do D, Myers J. Heart rate recovery: validation and methodologic issues. J Am Coll Cardiol. 2001;38(7):1980-1987. doi:[10.1016/S0735-1097(01)01652-7](https://doi.org/10.1016/S0735-1097(01)01652-7) PMID 11738304.  
  _Supports:_ In 2,193 male veterans, HR recovery at 2 min after exercise outperformed other time points for predicting death (drop <22 bpm, HR 2.6); concludes HR at 1 or 2 min of recovery is a validated prognostic measure.  
  _Limitations:_ DOI CORRECTION: the correct DOI is 10.1016/S0735-1097(01)01652-7 (10.1016/S0735-1097(01)01671-0 belongs to an unrelated cannabinoid paper). Male veterans with chest pain only; supine recovery protocol; no diagnostic value for angiographic disease.
- <a id="ref-tanaka_2001"></a>**tanaka_2001** — Tanaka H, Monahan KD, Seals DR. Age-predicted maximal heart rate revisited. J Am Coll Cardiol. 2001;37(1):153-156. doi:[10.1016/S0735-1097(00)01054-8](https://doi.org/10.1016/S0735-1097(00)01054-8) PMID 11153730.  
  _Supports:_ Meta-analysis (351 studies, 18,712 subjects) plus laboratory cross-validation (n=514) yielding HRmax = 208 - 0.7 x age in healthy adults, independent of sex and activity status.  
  _Limitations:_ Group-level equation; individual SD around ~10 bpm; healthy, non-medicated adults (beta-blockers etc. invalidate it).
- <a id="ref-task_force_1996"></a>**task_force_1996** — Task Force of the European Society of Cardiology and the North American Society of Pacing and Electrophysiology. Heart rate variability: standards of measurement, physiological interpretation and clinical use. Circulation. 1996;93(5):1043-1065. doi:[10.1161/01.CIR.93.5.1043](https://doi.org/10.1161/01.CIR.93.5.1043) PMID 8598068.  
  _Supports:_ Standards document defining time-domain HRV measures (SDNN, RMSSD, pNN50 etc.) computed from normal-to-normal (NN) intervals, recommending 5-min short-term and 24-h recordings, and requiring editing of ectopic beats/artifacts before analysis.  
  _Limitations:_ Written for ECG recordings; SDNN depends strongly on recording length so values from different durations are not comparable; no norms for wearable/PPG data. Crossref lists the corporate author only; PubMed has no DOI field but the DOI resolves.
- <a id="ref-theil_1950"></a>**theil_1950** — Theil H. A rank-invariant method of linear and polynomial regression analysis. I, II, III. Proc Kon Ned Akad Wetensch (Indagationes Mathematicae). 1950;53:386-392, 521-525, 1397-1412. Reprinted in: Raj B, Koerts J, eds. Henri Theil's Contributions to Economics and Econometrics. Dordrecht: Springer; 1992:345-381. doi:[10.1007/978-94-011-2546-8_20](https://doi.org/10.1007/978-94-011-2546-8_20)  
  _Supports:_ Original proposal of a rank-based (median of pairwise slopes) estimator for the linear regression slope, robust to outliers.  
  _Limitations:_ Original 1950 KNAW papers have no DOI; the DOI is for the 1992 Springer reprint (confirmed in Crossref, pp. 345-381). Part I page range from standard citations, not checked against the original volume. Original form assumes distinct x values (Sen 1968 handles ties).
- <a id="ref-uth_2004"></a>**uth_2004** — Uth N, Sorensen H, Overgaard K, Pedersen PK. Estimation of VO2max from the ratio between HRmax and HRrest - the Heart Rate Ratio Method. Eur J Appl Physiol. 2004;91(1):111-115. doi:[10.1007/s00421-003-0988-y](https://doi.org/10.1007/s00421-003-0988-y) PMID 14624296.  
  _Supports:_ Derives VO2max (mL/kg/min) ~ 15.3 x HRmax/HRrest in 46 well-trained men (SEE ~2.7 mL/kg/min with measured HRmax; ~4.7 with age-predicted HRmax).  
  _Limitations:_ Only well-trained men aged 21-51; authors state applicability to other groups awaits validation; error roughly doubles with age-predicted HRmax; an erratum was published (Eur J Appl Physiol 2005;93(4):508-509).
- <a id="ref-watson_2015"></a>**watson_2015** — Watson NF, Badr MS, Belenky G, Bliwise DL, Buxton OM, Buysse D, et al. Recommended amount of sleep for a healthy adult: a joint consensus statement of the American Academy of Sleep Medicine and Sleep Research Society. Sleep. 2015;38(6):843-844. Also published in J Clin Sleep Med. 2015;11(6):591-592 (doi:10.5664/jcsm.4758; PMID 25979105). doi:[10.5665/sleep.4716](https://doi.org/10.5665/sleep.4716) PMID 26039963.  
  _Supports:_ Consensus that adults should sleep 7 or more hours per night regularly; sleeping more than 9 h may be appropriate for young adults, those recovering from sleep debt, or illness; uncertainty about whether >9 h is associated with risk.  
  _Limitations:_ Recommends >=7 h for adults 18-60; it does NOT set an upper limit of 9 h as a target - the 7-9 h clamp is the app's design choice (the 9 h figure is mentioned as a point of uncertainty). Population consensus, not individual sleep need. Crossref lacks volume/pages for the Sleep version; PubMed confirms 38(6):843-844.
- <a id="ref-who_2020"></a>**who_2020** — Bull FC, Al-Ansari SS, Biddle S, Borodulin K, Buman MP, Cardon G, et al. World Health Organization 2020 guidelines on physical activity and sedentary behaviour. Br J Sports Med. 2020;54(24):1451-1462. doi:[10.1136/bjsports-2020-102955](https://doi.org/10.1136/bjsports-2020-102955) PMID 33239350.  
  _Supports:_ Recommends adults (18-64) do at least 150-300 min/week moderate-intensity or 75-150 min/week vigorous-intensity aerobic activity (or an equivalent combination), plus muscle-strengthening on >=2 days, and to limit sedentary time.  
  _Limitations:_ Population public-health guidance; equivalence assumes 1 vigorous min = 2 moderate min; separate recommendations apply to older adults, pregnancy, chronic conditions and children.
- <a id="ref-williams_2017"></a>**williams_2017** — Williams S, West S, Cross MJ, Stokes KA. Better way to determine the acute:chronic workload ratio? Br J Sports Med. 2017;51(3):209-210. doi:[10.1136/bjsports-2016-096589](https://doi.org/10.1136/bjsports-2016-096589) PMID 27650255.  
  _Supports:_ Short letter proposing exponentially weighted moving averages for acute and chronic load, with decay lambda_a = 2/(N+1) (N = time-decay constant in days, e.g., 7 and 28), instead of rolling averages.  
  _Limitations:_ Letter/editorial with no empirical validation; choice of N is arbitrary; does not address the ratio's statistical problems (see Impellizzeri 2020).

<!-- END GENERATED ANALYTICS -->
