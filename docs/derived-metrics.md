# Derived metrics

## 1. Purpose
Many people's devices record raw signals but not the summary vitals built from them. For example, a Zepp or Mi Band writes minute-by-minute heart rate but no resting heart rate, HRV or VO₂max. Ayuvo derives those vitals on the device from data already in the Health mirror, using published formulas.

Three rules apply to every derived metric:

- **Native data wins.** When HealthKit or Health Connect already has a value for the same metric and day, and Ayuvo did not write it, the app shows that value and names its source. The derived value only fills the gaps.
- **Derived values are never written back** to HealthKit or Health Connect. They stay inside Ayuvo and can always be recomputed.
- **Each metric can be switched off.** Every derived metric is on by default. Settings → Derived metrics has a master switch and one switch per metric, and each metric's detail page has the same switch. A switched-off metric is not computed, its stored values are deleted, and it disappears from Browse, Summary, Coach and the Insights fallback. Metrics that depend on it degrade as declared in `requires`: for example, zones fall back to % of maximum heart rate when resting heart rate is off.

Every number is Ayuvo's estimate. It is not a medical measurement or a diagnosis. Each detail page shows the method, the source, the inputs used and a confidence level.

## 2. Contract
| File | Role |
|---|---|
| `shared/derived/derived_config.json` | Single source of truth: metric catalog (id, category, unit, function, field, native type, requires, chart, icon, about, method, citation), thresholds and labels. |
| `scripts/derived_reference.py` | The reference implementation. Where it and this prose disagree, the reference is right. |
| `scripts/derived_contract_check.py [--write]` | Config lint, vector runner, coverage, portability lint, unit tests, platform copies, and the generated block below. |
| `shared/derived/test-vectors/*.json` | One file per reference function (`heart_day`, `hr_max`, `vo2max_uth`, `rhr_strain`, `sleep_nights`, `sleep_night`, `sleep_regularity`, `activity_day`, `step_streak`, `stride`, `energy_day`, `gait_week`, `audio_day`, `body_trend`, `height_conflict`, `priority`). |

Platform copies are refreshed by `--write` and must stay byte-identical to `shared/`:

- `android/app/src/main/assets/derived/derived_config.json`
- `ios/calorietracker/Services/Derived/Resources/derived_config.json`

### 2.1 Encodings
- **Minute series:** `{"start_ms", "values": [v | null, …]}`. Value *i* belongs to the minute `start_ms + i·60 000`. Heart rate outside 25–250 bpm is dropped.
- **Sleep rows:** `[start_ms, end_ms, code]`, using the codes of the `sleep` entry in `shared/health/metric_registry.json`. `sleep_nights` rows also carry a source id.
- **Clock values:** minutes after 12:00 on the day before the wake day, the same clock as Insights, so 23:00 is 660 and 07:00 is 1140.

### 2.2 Portability
The rules are the same as `docs/insights.md` §2.2:

- Rounding is half-up: `floor(x·10^d + 0.5) / 10^d`.
- Loops are written out; there is no `statistics` module and no built-in `sum`.
- Days are `YYYY-MM-DD` strings and instants are epoch milliseconds. Wall-clock questions use the `time_zone` input.

### 2.3 Sleep nights
`sleep_nights` pins the night grouping for both platforms, following iOS `HealthSleepAnalysis`:

1. Rows are chained into episodes while each next row starts within 3 hours of the episode's latest end.
2. An episode belongs to the local day of its latest-ending row, which is the wake day.
3. For each wake day, the source with the most unioned asleep time wins. Ties go to the source with more rows, then to the smaller source id.
4. The night window runs from the first asleep instant to the last one.

Android's `HealthSleepAnalysis` is aligned to this rule.

## 3. Architecture
```
Health mirror (health_samples, Android health_series_points, daily rollups) + profile + app stores
        │  DerivedInputs: minute heart rate, minute/hourly steps per source, nights, day series
        ▼
 DerivedMetricsEngine (pure; ports of derived_reference.py)
        │  per day and metric: value, value2, value3, quality, algo_version
        ▼
 derived_daily_values (health database; not exported; rebuilt on demand)
        │  DerivedPriority: a native platform reading for the same metric and day wins
        ▼
 Metric detail / Browse / Summary / Insights fallback / Coach
```

**When computation runs:**
- After each sync commit, for the days that sync touched.
- When a switch changes.
- As a background backfill of the last 200 days.
- For every day again whenever `algo_version` changes.

**Storage:** `derived_daily_values(metric_id, day, value, value2, value3, quality, source_kind, algo_version, computed_ms)`, with primary key `(metric_id, day)`, lives next to the mirror tables. It is excluded from the Health data export and from backups because it can always be recomputed.

**Settings:** `derivedMetricsEnabled` (bool, default true) and `derivedMetricsDisabled` (a list of metric ids). Both travel in portable data under `preferences`. Cloud backup picks them up automatically.

## 4. Metrics
### Algorithm 3 (2026-10-07)
- TRIMP uses Banister's sex-specific weighting: 0.64·e^(1.92x) for men and 0.86·e^(1.67x) for women. Before this change it used 0.64 for everyone (`thresholds.trimp_a`).
- `sleep_nights` keeps only the main episode of each wake day as the night. Any other episode on that wake day is reported as `nap_min`/`naps` and never widens the night window.
- `sleep_debt` uses the personal sleep need from `shared/analytics` `sleep_need` when the app supplies it. With no personal value it uses 480 min.
- Stored values are recomputed because `algo_version` changed.

<!-- BEGIN GENERATED DERIVED -->

_Generated from `shared/derived/derived_config.json` by `scripts/derived_contract_check.py --write`. Do not edit by hand._

Algorithm version: 3.

### Heart

| Metric | Id | Unit | Native type wins (iOS / Android) | Requires | Method | Source |
|---|---|---|---|---|---|---|
| Resting Heart Rate (estimated) | `resting_hr_derived` | bpm | `resting_heart_rate` / `resting_heart_rate` | — | Lowest average of 5 consecutive minutes inside last night's main sleep. Needs at least 3 hours of sleep with heart rate on at least 70% of those minutes. | Nocturnal minimum heart rate as a resting-heart-rate estimate, as used by consumer wearables; adult resting range 60-100 bpm per American Heart Association, lower in trained people. |
| Sleeping Heart Rate | `sleeping_hr` | bpm | — | — | Mean of every heart-rate minute inside the main sleep window. | Descriptive statistic of nocturnal heart rate. |
| Lowest Heart Rate at Night | `lowest_night_hr` | bpm | — | — | Minimum heart-rate minute inside the main sleep window. | Descriptive statistic of nocturnal heart rate. |
| Night-time Heart Rate Dip | `night_hr_dip` | % | — | — | 1 − (sleeping average ÷ awake average over the 14 hours after waking) × 100. Needs at least 4 hours of awake heart rate. | Nocturnal heart-rate dipping (≥10%) by analogy with blood-pressure dipping; Ben-Dov IZ et al., Hypertension 2007; Eguchi K et al., Am J Hypertens 2009. |
| Sedentary Daytime Heart Rate | `sedentary_hr` | bpm | — | — | Mean of daytime heart-rate minutes where the current and previous 10 minutes had no steps. Needs 30 such minutes. | Resting-while-awake heart rate; adult resting range 60-100 bpm (American Heart Association). |
| Daytime Heart Rate | `daytime_hr` | bpm | — | — | Mean, minimum and maximum of heart-rate minutes outside the main sleep window. Needs 60 minutes. | Descriptive statistic. |
| Highest Heart Rate | `observed_max_hr` | bpm | — | — | Maximum of 2-minute rolling averages, which ignores single-reading spikes. | Artefact rejection by short rolling average. |
| Maximum Heart Rate | `hr_max_estimate` | bpm | — | — | Tanaka formula 208 − 0.7 × age, raised to the highest 2-minute heart rate seen in the last 180 days when that is higher. | Tanaka H, Monahan KD, Seals DR. Age-predicted maximal heart rate revisited. J Am Coll Cardiol 2001;37:153-156. |
| Heart Rate Reserve | `hr_reserve` | bpm | — | `resting_hr_derived` | Maximum heart rate − resting heart rate (Karvonen). | American College of Sports Medicine. ACSM's Guidelines for Exercise Testing and Prescription, 11th ed. (2021), intensity classification by % heart-rate reserve (Karvonen) and % HRmax. |
| Cardio Minutes | `cardio_minutes` | min | — | — | Each heart-rate minute is classed by % heart-rate reserve (moderate 40-59%, vigorous 60-89%, near-maximal ≥90%). Without a resting heart rate % of maximum is used (moderate 64-76%, vigorous 77-95%, near-maximal ≥96%). Moderate + 2 × (vigorous + near-maximal). | American College of Sports Medicine. ACSM's Guidelines for Exercise Testing and Prescription, 11th ed. (2021), intensity classification by % heart-rate reserve (Karvonen) and % HRmax. World Health Organization. WHO guidelines on physical activity and sedentary behaviour (2020): 150-300 min/week moderate or 75-150 min/week vigorous; vigorous minutes count double. |
| Training Impulse (TRIMP) | `training_impulse` | — | — | `resting_hr_derived` | Banister TRIMP over minutes at ≥30% heart-rate reserve: Σ minutes × HRr × a × e^(k × HRr), with a = 0.64, k = 1.92 for men and a = 0.86, k = 1.67 for women (the midpoint for other). | Banister EW. Modeling elite athletic performance. In: MacDougall JD, Wenger HA, Green HJ, eds. Physiological Testing of the High-Performance Athlete. 2nd ed. Human Kinetics; 1991:403-424. Morton RH et al., J Appl Physiol 1990;69(3):1171-1177. |
| Cardio Fitness (estimated) | `vo2max_estimate` | mL/kg/min | `vo2_max` / `vo2_max` | `resting_hr_derived`, `hr_max_estimate` | Uth-Sørensen: 15.3 × maximum heart rate ÷ resting heart rate. Low confidence when the maximum comes from the age formula. | Uth N, Sørensen H, Overgaard K, Pedersen PK. Estimation of VO2max from the ratio between HRmax and HRrest. Eur J Appl Physiol 2004;91:111-115. |
| Resting Heart Rate vs Usual | `rhr_deviation` | bpm | — | `resting_hr_derived` | Today − mean of the previous 30 days (needs 14 days). Flag when today and yesterday are both at least 5 bpm above. | Resting-heart-rate elevation as a strain and illness signal: Radin JM et al., Lancet Digital Health 2020; Buchheit M, Front Physiol 2014. |
| Wear Time | `wear_minutes` | min | — | — | Count of minutes with a heart-rate reading in the local day. | Valid-day rule of ≥10 h wear from accelerometry research: Troiano RP et al., Med Sci Sports Exerc 2008. |
| Walking Heart Rate | `walking_hr` | bpm | `walking_heart_rate_average` / — | — | Mean heart rate in minutes with 60-130 steps, in runs of 3 or more such minutes (faster minutes count as running). Needs 10 minutes. | Walking heart rate average as a fitness marker (Apple Heart and Movement Study methodology). |

### Sleep

| Metric | Id | Unit | Native type wins (iOS / Android) | Requires | Method | Source |
|---|---|---|---|---|---|---|
| Sleep Efficiency | `sleep_efficiency` | % | — | — | Time asleep ÷ time in bed × 100. | Ohayon M et al. National Sleep Foundation's sleep quality recommendations. Sleep Health 2017;3:6-19 (efficiency ≥85%). |
| Time to Fall Asleep | `sleep_onset_latency` | min | — | — | First asleep stage − start of time in bed. Needs an In Bed record. | Ohayon M et al., Sleep Health 2017 (latency ≤30 min). |
| Deep Sleep | `deep_sleep_pct` | % | — | — | Deep minutes ÷ time asleep × 100. Only nights with sleep stages. | Ohayon MM et al. Meta-analysis of quantitative sleep parameters. Sleep 2004;27:1255-1273. |
| REM Sleep | `rem_sleep_pct` | % | — | — | REM minutes ÷ time asleep × 100. Only nights with sleep stages. | Ohayon MM et al., Sleep 2004. |
| Wake-ups | `sleep_wakeups` | — | — | — | Gaps of ≥5 minutes between asleep stages, from first sleep to final waking. Minutes awake are shown as wake after sleep onset. | Wake after sleep onset (WASO), Ohayon M et al., Sleep Health 2017. |
| Bedtime | `bedtime` | clock | — | — | Start of the first sleep record, as a clock time. | Descriptive. |
| Wake Time | `wake_time` | clock | — | — | End of the last asleep stage, as a clock time. | Descriptive. |
| Sleep Midpoint | `sleep_midpoint` | clock | — | — | Sleep onset + half the time to final waking. | Roenneberg T et al. Life between clocks. J Biol Rhythms 2003. |
| Time to First Deep Sleep | `deep_latency` | min | — | — | First deep stage − sleep onset. | Descriptive (sleep architecture). |
| Time to First REM | `rem_latency` | min | — | — | First REM stage − sleep onset. | Carskadon MA, Dement WC. Normal human sleep: an overview. Principles and Practice of Sleep Medicine. |
| Bedtime Variability | `sleep_timing_variability` | min | — | — | Sample standard deviation of bedtime (and wake time) over the last 14 nights. Needs 5 nights. | Sleep timing regularity; Phillips AJK et al., Sci Rep 2017. |
| Sleep Regularity Index | `sleep_regularity_index` | — | — | — | Minute-by-minute comparison of each noon-to-noon day with the next over the last 14 days: 200 × agreement − 100. Needs 5 day pairs. | Phillips AJK et al. Irregular sleep/wake patterns are associated with poorer academic performance and delayed circadian and sleep/wake timing. Sci Rep 2017;7:3216. |
| Social Jet Lag | `social_jetlag` | min | — | — | \|Mean sleep midpoint on free nights (waking Saturday or Sunday) − on work nights\|. Needs 2 free and 3 work nights in 14 days. | Wittmann M, Dinich J, Merrow M, Roenneberg T. Social jetlag. Chronobiol Int 2006;23:497-509. |
| Chronotype (MSFsc) | `chronotype` | clock | — | — | MSFsc = MSF − (SDf − SDweek) ÷ 2 when free-day sleep is longer than weekday sleep, where SDweek = (5 × SDw + 2 × SDf) ÷ 7. | Roenneberg T et al. Epidemiology of the human circadian clock. Sleep Med Rev 2007;11:429-438. |
| Sleep Debt | `sleep_debt` | min | — | — | Σ max(0, need − time asleep) over recorded nights in the last 14 days. Need defaults to 8 hours. | Watson NF et al. Recommended amount of sleep for a healthy adult (AASM/SRS). Sleep 2015;38:843-844. |

### Activity

| Metric | Id | Unit | Native type wins (iOS / Android) | Requires | Method | Source |
|---|---|---|---|---|---|---|
| Brisk Walking Minutes | `brisk_minutes` | min | — | — | Minutes with ≥100 steps from your wearable's minute data. | Tudor-Locke C et al. Walking cadence (steps/min) and intensity in 21-40 year olds: CADENCE-adults. Int J Behav Nutr Phys Act 2019; Tudor-Locke C et al., Br J Sports Med 2018. |
| Peak 30-min Cadence | `peak_30_cadence` | steps/min | — | — | Mean of the 30 highest minute step counts in the day. | Tudor-Locke C et al. Peak stepping cadence in free-living adults. J Phys Act Health 2011. |
| Active Hours | `active_hours` | h | — | — | Count of hours 07:00-21:59 whose de-duplicated steps are ≥250. | WHO 2020 guideline: limit sedentary time and replace it with activity of any intensity. |
| Longest Sitting Spell | `longest_sedentary` | min | — | — | Longest run of worn minutes (heart rate present) with no steps, 07:00-21:59. | WHO 2020 guideline on sedentary behaviour. |
| Activity Level | `step_band` | — | — | — | Band of the day's de-duplicated step total. | Tudor-Locke C et al. How many steps/day are enough? for adults. Int J Behav Nutr Phys Act 2011;8:79. |
| Phone-free Steps | `phone_left_behind` | steps | — | — | Flagged when the wearable counts ≥1,000 steps and the phone less than 10% of that. | Data-quality rule. |
| Step Goal Streak | `step_streak` | days | — | — | Days in a row meeting the goal, counting today once it is met. | Descriptive. |
| Stride Length | `stride_length` | m | — | — | Distance ÷ steps from the same source (needs 1,000 steps). Typical = 0.415 × height for men and 0.413 × height for women. | Hatano Y. Use of the pedometer for promoting daily walking exercise. ICHPER 1993. |

### Energy

| Metric | Id | Unit | Native type wins (iOS / Android) | Requires | Method | Source |
|---|---|---|---|---|---|---|
| Total Energy Burned | `tdee` | kcal | — | — | (Resting + active energy) ÷ 0.9, adding about 10% for digestion. Needs a full day of resting energy (at least 70% of your predicted BMR). | Westerterp KR. Diet induced thermogenesis. Nutr Metab 2004;1:5. |
| Resting Metabolic Rate | `measured_bmr` | kcal | — / `basal_metabolic_rate` | — | Daily resting energy; prediction 10 × kg + 6.25 × cm − 5 × age + 5 (men) or − 161 (women). | Mifflin MD et al. A new predictive equation for resting energy expenditure. Am J Clin Nutr 1990;51:241-247. |
| Physical Activity Level | `physical_activity_level` | — | — | — | TDEE ÷ resting energy. | FAO/WHO/UNU. Human energy requirements. Food and Nutrition Technical Report Series 1, 2004. |

### Mobility

| Metric | Id | Unit | Native type wins (iOS / Android) | Requires | Method | Source |
|---|---|---|---|---|---|---|
| Walking Speed (weekly) | `walking_speed_weekly` | m/s | — | — | Median of walking-speed readings in the last 7 days. | Studenski S et al. Gait speed and survival in older adults. JAMA 2011;305:50-58. |
| Double Support Time (weekly) | `double_support_weekly` | % | — | — | Median of double-support readings in the last 7 days. | Apple Inc. Measuring Walking Quality Through iPhone Mobility Metrics (2022). |
| Uneven Walking Readings | `asymmetry_flags` | — | — | — | Count of asymmetry readings >10% (readings of 100% are treated as sensor errors). Flagged at 3 or more. | Apple Inc. Measuring Walking Quality Through iPhone Mobility Metrics (2022). |

### Hearing

| Metric | Id | Unit | Native type wins (iOS / Android) | Requires | Method | Source |
|---|---|---|---|---|---|---|
| Headphone Sound Dose | `sound_dose` | % | — | — | Σ hours × 2^((dB − 80) ÷ 3) ÷ 40 × 100 (3 dB exchange rate). | WHO-ITU H.870 Guidelines for safe listening devices/systems (2019). |

### Body

| Metric | Id | Unit | Native type wins (iOS / Android) | Requires | Method | Source |
|---|---|---|---|---|---|---|
| Weight Trend | `weight_trend` | kg | — | — | Exponentially smoothed average: trend += 0.1 × (weigh-in − trend) on each day with a weigh-in. | Walker J. The Hacker's Diet (exponentially smoothed moving average, α = 0.1). |
| Weekly Weight Change | `weight_rate` | kg/wk | — | `weight_trend` | (Trend today − trend 14 days ago) ÷ 2. Needs 4 weigh-ins in 14 days. | Derived from the smoothed trend. |
| BMI | `bmi_derived` | kg/m² | `bmi` / `bmi` | `weight_trend` | Trend weight ÷ height². WHO categories, or the Asian cut-offs (overweight ≥23, obese ≥27.5) if chosen in Settings. | WHO Expert Consultation. Appropriate body-mass index for Asian populations. Lancet 2004;363:157-163. |
| Healthy Weight Range | `healthy_weight_range` | kg | — | — | 18.5 × height² to 24.9 × height² (22.9 with Asian cut-offs). | WHO BMI classification. |
| Weeks to Goal Weight | `goal_eta` | wk | — | `weight_rate` | (Goal − trend) ÷ weekly rate, when moving towards the goal at ≥0.05 kg a week. | Derived from the smoothed trend. |

### Nutrition

| Metric | Id | Unit | Native type wins (iOS / Android) | Requires | Method | Source |
|---|---|---|---|---|---|---|
| Protein per kg | `protein_per_kg` | g/kg | — | — | Logged protein ÷ latest weight. | International Society of Sports Nutrition position stand: protein and exercise (Jäger R et al. 2017): 1.4-2.0 g/kg/day, about 0.25 g/kg per meal. |
| Macro Split | `macro_split` | % | — | — | 4 kcal/g protein and carbohydrate, 9 kcal/g fat; each share of their total. | Atwater factors 4/4/9 kcal/g; WHO: saturated fat below 10% of energy; Dietary Guidelines for Americans: fiber 14 g per 1,000 kcal; WHO sodium/potassium molar ratio below 1. |
| Saturated Fat | `saturated_fat_share` | % kcal | — | — | 9 × saturated fat g ÷ energy from macros × 100. | Atwater factors 4/4/9 kcal/g; WHO: saturated fat below 10% of energy; Dietary Guidelines for Americans: fiber 14 g per 1,000 kcal; WHO sodium/potassium molar ratio below 1. |
| Fibre per 1,000 kcal | `fiber_density` | g | — | — | Fibre g × 1,000 ÷ logged kcal. | Atwater factors 4/4/9 kcal/g; WHO: saturated fat below 10% of energy; Dietary Guidelines for Americans: fiber 14 g per 1,000 kcal; WHO sodium/potassium molar ratio below 1. |
| Sodium to Potassium | `sodium_potassium_ratio` | ratio | — | — | (sodium mg ÷ 22.99) ÷ (potassium mg ÷ 39.10). | Atwater factors 4/4/9 kcal/g; WHO: saturated fat below 10% of energy; Dietary Guidelines for Americans: fiber 14 g per 1,000 kcal; WHO sodium/potassium molar ratio below 1. |
| Eating Window | `eating_window` | min | — | — | Last eaten time − first eaten time. | Descriptive; uses each item's eaten-at time, not when it was logged. |
| Last Meal to Bedtime | `last_meal_to_bed` | min | — | — | Bedtime − last eaten time. | Descriptive; uses the eaten-at time and the night's first sleep record. |
| Tea or Coffee with Iron-rich Food | `iron_absorption_risk` | — | — | — | Count of tea/coffee items within 60 minutes of an item with ≥3 mg iron. | Tea and coffee polyphenols inhibit non-heme iron absorption (Hurrell RF et al. Br J Nutr 1999). |
| Energy Balance | `energy_balance` | kcal | — | `tdee` | Logged kcal − total energy burned. | Adaptive energy expenditure from intake and weight trend, about 7,700 kcal per kg of body-mass change (Hall KD, Int J Obes 2008 discusses its limits). |
| Adaptive Energy Estimate | `adaptive_tdee` | kcal | — | `weight_trend` | Mean logged kcal − (trend change × 7,700 kcal/kg) ÷ 28 days. Needs 14 logged days and 6 weigh-ins. | Adaptive energy expenditure from intake and weight trend, about 7,700 kcal per kg of body-mass change (Hall KD, Int J Obes 2008 discusses its limits). |

### Thresholds

```json
{
  "active_hour_first": 7,
  "active_hour_last": 21,
  "active_hour_steps": 250,
  "asymmetry_flag_count": 3,
  "asymmetry_pct": 10,
  "bmi": {
    "asian": [
      18.5,
      23.0,
      27.5
    ],
    "who": [
      18.5,
      25.0,
      30.0
    ]
  },
  "bmr_min_share": 0.7,
  "brisk_cadence": 100,
  "daytime_min_minutes": 60,
  "dip_min_wake_min": 240,
  "episode_gap_hours": 3,
  "eta_min_rate": 0.05,
  "ewma_alpha": 0.1,
  "free_wake_weekdays": [
    6,
    7
  ],
  "healthy_bmi": {
    "asian": [
      18.5,
      22.9
    ],
    "who": [
      18.5,
      24.9
    ]
  },
  "height_conflict_m": 0.01,
  "hr_max_lookback_days": 180,
  "hr_valid_max": 250,
  "hr_valid_min": 25,
  "hrmax_zones": [
    0.57,
    0.64,
    0.77,
    0.96
  ],
  "hrr_zones": [
    0.3,
    0.4,
    0.6,
    0.9
  ],
  "mifflin": {
    "female": -161,
    "male": 5,
    "other": -78
  },
  "pal_bands": [
    1.4,
    1.7,
    2.0,
    2.4
  ],
  "peak_minutes": 30,
  "phone_min_wearable_steps": 1000,
  "phone_ratio": 0.1,
  "rate_min_weighins": 4,
  "rate_window_days": 14,
  "regularity_min_nights": 5,
  "regularity_window_days": 14,
  "rhr_min_coverage": 0.7,
  "rhr_min_sleep_min": 180,
  "rhr_window_min": 5,
  "sedentary_end_hour": 21,
  "sedentary_min_minutes": 30,
  "sedentary_start_hour": 9,
  "sedentary_still_min": 10,
  "sleep_need_min": 480,
  "slow_gait_mps": 0.8,
  "social_min_free": 2,
  "social_min_work": 3,
  "sound_exchange_db": 3,
  "sound_ref_db": 80,
  "sound_weekly_hours": 40,
  "sri_min_pairs": 5,
  "step_bands": [
    5000,
    7500,
    10000,
    12500
  ],
  "strain_delta_bpm": 5,
  "strain_min_days": 14,
  "strain_sd_floor": 1.0,
  "strain_window_days": 30,
  "stride_factor": {
    "female": 0.413,
    "male": 0.415,
    "other": 0.414
  },
  "stride_min_steps": 1000,
  "tanaka": [
    208,
    0.7
  ],
  "tef_share": 0.1,
  "trimp_a": {
    "female": 0.86,
    "male": 0.64,
    "other": 0.75
  },
  "trimp_k": {
    "female": 1.67,
    "male": 1.92,
    "other": 1.795
  },
  "trimp_min_hrr": 0.3,
  "uth_factor": 15.3,
  "valid_wear_min": 600,
  "wake_window_hours": 14,
  "wakeup_min_gap_min": 5,
  "walking_max_steps": 130,
  "walking_min_minutes": 10,
  "walking_min_steps": 60,
  "walking_run_min": 3
}
```

Disclaimer shown on every derived metric: Estimated by Ayuvo from your own data using published formulas. It is not a medical measurement or diagnosis; talk to your doctor about anything that concerns you.

<!-- END GENERATED DERIVED -->
