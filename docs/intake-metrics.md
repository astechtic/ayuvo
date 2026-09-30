# Intake metrics: nutrition, nutrient coverage, medications, strength, energy balance, labs

## 1. Purpose
This contract turns the food diary, medication dose logs, strength sessions, weights and lab records into derived data points. Every function is pure, shared between the platforms and backed by test vectors. The results appear in four places:

- **Derived metrics:** daily values listed in `shared/derived/derived_config.json` under the `nutrition` category, plus `energy_balance` and `adaptive_tdee`. They follow the same switches and "native wins" rule as every derived metric.
- **Medications screen:** per-medication adherence, lateness and a suggested reminder time.
- **Workouts screen:** weekly sets per muscle, push/pull/legs balance and estimated 1RM.
- **Health Records:** a card linking lab results to nutrition. It only ever suggests talking to a doctor.

Copy is non-diagnostic and associational.

## 2. Contract
| File | Role |
|---|---|
| `shared/intake/intake_config.json` | Thresholds, the Dietary Reference Intake tables by sex and age band, upper intake limits, muscle groups, lab-to-nutrient links and sources. |
| `scripts/intake_reference.py` | The reference implementation: `nutrition_day`, `dri_goals`, `nutrient_coverage`, `supplement_daily`, `meds_adherence`, `strength_week`, `energy_balance`, `paired_difference`, `lab_nutrient_links`. |
| `scripts/intake_contract_check.py [--write]` | Lint, vector runner with coverage, portability lint, unit tests and platform copies. |
| `shared/intake/test-vectors/*.json` | Test vectors, format `ayuvo-intake-vectors`. |

Platform copies:

- `android/app/src/main/assets/intake/intake_config.json`
- `ios/calorietracker/Services/Intake/Resources/intake_config.json`

## 3. Rules worth knowing
- **Eaten-at time.** Food entries now have an eaten-at time, separate from when the entry was logged. It defaults to the log time and can be edited. The eating window, the gap between the last meal and bedtime, caffeine timing and the iron-absorption rule all use it.
- **Default nutrient goals** come from `dri_goals`, using NASEM DRIs for the person's sex and age band. Goals the person set themselves are never overwritten.
  - This fixes the old default of 18 mg iron for everyone; the adult male value is 8 mg.
  - "Other" sex takes the higher of the male and female values.
  - Under-19s use the 19–30 band for now.
- **Supplements** are averaged over their dosing interval. A weekly 60,000 IU vitamin D dose counts as 214 mcg per day. Anything above the upper limit is flagged with "follow your prescriber", never an instruction to stop.
- **Adherence** is doses taken ÷ (taken + missed). Doses the person chose to skip are left out. 80% or more counts as adherent, using the PQA threshold applied per dose. A new reminder time is suggested only after 5 or more taken doses and a median delay above 60 minutes.
- **Adaptive energy estimate.** It uses 28 days of logs and needs at least 14 logged days and 6 weigh-ins. The weight trend is smoothed (EWMA 0.1) and each kilogram of change counts as 7,700 kcal. It is shown next to the adaptive goals feature and does not replace it.
- **Paired comparisons.** Examples: gym day vs the next night's sleep, sleep vs the next morning's resting heart rate. Each group needs at least 8 days. They report a difference and Cohen's d and never claim a cause.
- **Labs link.** It fires only when a linked analyte is below the reference range printed on the report. For example, low haemoglobin or MCV links to iron. The card shows the average intake as % of goal and whether any active supplement provides the nutrient. It never names a condition.

Not included: ICMR-NIN (India) reference values. They are planned as an optional table once the values have been checked against the published 2020 report.
