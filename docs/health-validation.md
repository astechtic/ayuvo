# Validation of health analytics

This page has two halves:

- **Shipped and verified.** The reproducibility checks: the reference implementation, the vectors and cross-platform agreement.
- **Not done yet.** Accuracy validation against reference measurements.

Ayuvo does not claim accuracy for any analytics output until a reference dataset exists for it.

## 1. Reproducibility (shipped, verified)
| Layer | Check | Tolerance |
|---|---|---|
| Reference | `scripts/analytics_contract_check.py`: 23 vector files and 118 cases, with a coverage tag for every status, state and label, plus 13 hand-computed unit tests | exact (the reference produces the expected values) |
| Android | `AnalyticsVectorTests` runs every vector file and requires a runner per file. It also checks that the asset copies are byte-identical | numbers within 1e-9 (absolute or relative); strings, booleans and nulls exact |
| iOS | `AnalyticsVectorTests` (Swift Testing), same rules | same |
| Derived / workout / insights | existing vector suites, rerun after the Phase 0 changes (derived v3, workout v2) | exact, as before |

**Edge cases in the vectors:**
- Inputs: empty input, a single sample, insufficient history, today missing (`NO_DATA`), outliers, and a MAD of 0 (the spread floor applies).
- Contexts: camera-context days excluded from the baseline, and a source change.
- Heart rate: invalid RR (more than 5% artifacts), gaps between beats, too few beats.
- Duplicates: Google Health duplicates within 60 s, distinct readings kept, null values.
- Time: the America/New_York DST change for nights, workouts and MET minutes; naps on the wake day; late-evening workouts that cross midnight in local time.
- Energy: negative active energy (the old Android bug) and an Ayuvo burn that overlaps a watch workout.
- Correlation: tied ranks and too few pairs.
- Forecast: constant exposure; a forecast that passes the gate and one that fails it.

Cross-platform consistency follows one path: shared definition → Python reference → Kotlin and Swift ports → the same vectors. Any intentional difference must appear in the vector notes. None exist today.

## 2. Accuracy framework (tooling shipped, no dataset)
`scripts/analytics_eval.py pairs.csv` compares an Ayuvo output with a reference measurement:

| Input column | Meaning |
|---|---|
| `prediction` | Ayuvo's value. An empty prediction counts as a failure. |
| `reference` | The reference measurement. Rows without one are skipped. |
| `confidence` | Optional. |

It reports n, failure rate, coverage, MAE, RMSE, mean bias, median absolute error, SD of differences, Bland–Altman 95% limits of agreement, Pearson r and MAE by confidence band. It reuses `vitals_reference.validation_stats`, so camera vitals and analytics are scored the same way. Keep personal reference data out of the repository.

**What each metric needs before an accuracy statement:**

| Output | Reference needed | Protocol | Status |
|---|---|---|---|
| Ayuvo RMSSD from Apple Watch beat series | ECG chest strap (for example Polar H10) RR export, same time window | Seated rest, 1–5 min, simultaneous recording; overnight comparison separately | not started |
| HRR1 and HRR2 | Chest-strap HR during a standardized cool-down | Same end-of-exercise marker; standing versus walking recovery documented | not started |
| Sleep duration and efficiency | Research actigraphy or PSG | Multiple nights per person | not started (provider values used as is) |
| Estimated daily expenditure | Doubly labelled water, or a long intake and weight balance study | Weeks | not feasible in-house; documented as an estimate |
| Recovery v2, deviation state, load state | No ground truth exists | Evaluate stability and plausibility; compare with self-reported readiness if collected with consent | design only |
| Forecast | The person's own next-day value | Built in: temporal test block against persistence and the 28-day median | per-user gate at runtime |
| Camera vitals | See docs/camera-vitals.md | Reference devices listed there | experimental / research only |

## 3. Known gaps
- **No beat-to-beat data on Android.** Health Connect has no RR record type, so Android HRV stays provider RMSSD, and Ayuvo RMSSD is iOS only.
- **Provider-limited stage data.** Sleep stages and wearable energy are the provider's estimates. Ayuvo labels them and never treats them as ground truth.
- **Forecast usefulness is unproven.** It is shown per person only when it beats both baselines. It is off by default.
- **Design choices without validation.** Recovery v2 weights, deviation thresholds and load guards are design choices with no external validation.
- **Correlation assumptions.** The correlation p-values assume independent days, but daily health data are autocorrelated. The FDR control and the |ρ| ≥ 0.3 minimum reduce false positives but do not remove them.
