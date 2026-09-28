# Nutrients — cross-platform contract

Plan: `/Users/macbook/.claude/plans/majestic-purring-backus.md`. Shared files live in `shared/nutrients/`. The executable reference is `scripts/nutrients_reference.py` (Python 3 stdlib only); `scripts/nutrients_contract_check.py` lints the reference data, checks the vectors in `shared/nutrients/test-vectors/` (`--write` regenerates every `expected`, copies the data files to both apps and regenerates the table at the end of this file). Where this prose and the reference disagree, the reference wins and the prose is fixed. Change the shared files first, then both platforms in the same change.

## 1. Purpose
Every detailed nutrient gets a chart (D, W, M, 6M, Y) with personalised reference lines, supplement doses add to nutrient totals, and each chart links to a sourced guide page on the website. One file, `shared/nutrients/nutrient_reference.json`, feeds all three: the app's lines and default goals and the website's "How much you need" tables, so the app and the site can never disagree.

## 2. Rules that never bend
1. No invented numbers. A nutrient without an authoritative daily amount (`info` style: total sugar, trans fat, cholesterol, monounsaturated and polyunsaturated fat) gets no line. Trans fat and cholesterol are "as low as possible while eating a nutritionally adequate diet" (NASEM), which is not a number, so there is no line.
2. A missing value shows "—", never 0. `day_totals` and `logged_day_average` return `null` when there is no data.
3. No diagnosis and no medical advice. Reference lines describe population intake references for healthy adults; the About text says they are not personal medical advice. "Diagnose" wording appears only inside disclaimers.
4. Supplement contributions are never written to HealthKit or Health Connect and never add calories.
5. AI output is never saved on its own (§7).
6. Copy rule: never claim data "never leaves the device".

## 3. `nutrient_reference.json`
Envelope `{"format": "ayuvo-nutrient-reference", "version": 1, "checked", "population", "population_note", "age_bands", "default_band", "units", "unit_aliases", "amount_per_unit_max", "sports_supplements", "sources", "nutrients"}`. Sorted keys, 2-space indent, UTF-8, trailing newline. The `nutrients` array order is the display order (carbs, fats, minerals, vitamins, other).

- `population`: `"adults 19+, not pregnant or lactating"`. Pregnancy and lactation values are not modelled.
- `age_bands`: `19-30`, `31-50`, `51-70`, `71+` (`max: null`); `default_band`: `31-50`.
- `units`: canonical units `g`, `mg`, `mcg`; `unit_aliases`: `ug`, `µg` (U+00B5) and `μg` (U+03BC) → `mcg`.
- `amount_per_unit_max`: sanity cap for one dose unit of a supplement, in the nutrient's canonical unit: `g` 100, `mg` 10,000, `mcg` 100,000. It catches unit mix-ups (a vitamin D amount typed in IU into a mcg field, mg typed as mcg), not medical limits. A 600,000 IU vitamin D injection (15,000 mcg) and 5 mg of folic acid (8,333 mcg DFE) both fit.
- `sports_supplements`: `creatine, beta_alanine, l_citrulline, l_carnitine, l_arginine, taurine, betaine, hmb`, unit `g`. They have charts and can be supplement nutrients, but no reference lines and no guide page.
- `sources`: `{id: {title, publisher, url, checked, page_updated?, verified_via?, note?}}`.
- `nutrients[]`: `{key, slug, name, unit, category, style, summary, recommended, upper_limit, limit, iu, mass_forms, notes, source_ids, app_tracked, health_type}`.
  - `app_tracked` (boolean, on every entry): `true` for the 23 nutrients the food log tracks. Their `key` is the app's `OptionalNutrient.jsonKey` (plus `monounsaturated_fat`, `polyunsaturated_fat` for the `FoodEntry` fields of those names), and `unit` equals the app's unit (the checker parses the iOS model). `false` for the 14 health nutrition types the food log does not track: phosphorus, chloride, copper, manganese, selenium, chromium, molybdenum, iodine, vitamin B6, thiamin, riboflavin, niacin, biotin and pantothenic acid. Their `unit` equals the registry unit. `app_tracked` means only "the FOOD LOG records it"; it never limits supplements.
  - **Supplement nutrients** (`SUPPLEMENT_KEYS` in the reference): every one of the 37 reference nutrients, `app_tracked` or not and of any style (info-style sugar, trans fat, cholesterol and the two fats stay allowed: they were supplement nutrients before, and a label can print them), plus the 8 sports supplements, in reference order then sports order. All of them can be `medication_nutrients` rows (form, archive import), AI label keys, and have a `nutrient:<key>` chart. `convert_amount` and `parse_label_output` return `unknown_nutrient` only for keys outside that list (for example `grape_seed_extract`). `reference_lines` and `default_goal` work for every entry.
  - **`app_tracked: false` nutrients** (copper, iodine, thiamin, …): the food log never records them, so their food part is always `null` (never 0), their totals are supplements only (§4.6), their logged days are the days with a taken dose of that nutrient (§4.7), and their chart says so (§5). The apps store no goals for them: `custom_goal` is always null and the chart shows the reference lines. `food_tracked(key)` in the reference is `app_tracked` for reference nutrients and `true` for sports supplements (they are in the app's `OptionalNutrient` list).
  - **Ports:** code that walks the reference for the food log, the goals settings or Nutrition Details' always-shown rows must filter `app_tracked == true`. Supplement pickers, the AI key list, archive validation and `nutrient_metrics` use every reference entry plus the sports supplements. Display names for `app_tracked: false` nutrients come from the reference `name` (they are not in `OptionalNutrient`).
  - `health_type`: the `shared/health/metric_registry.json` id of the same nutrient (`vitamin_d` → `dietary_vitamin_d`, `saturated_fat` → `dietary_fat_saturated`, `monounsaturated_fat` → `dietary_fat_monounsaturated`, `fiber` → `dietary_fiber`, `sugar` → `dietary_sugar`), or `null` when the registry has none (`added_sugar`, `trans_fat`, `omega_3`). The checker requires a registry type with category `nutrition`, aggregation `SUM` and the same unit (no unit conversion is modelled, so a differing unit is flagged, not converted), each type used once, and every registry `dietary_*` type mapped by one nutrient except the macro types `dietary_energy`, `dietary_protein`, `dietary_carbohydrates`, `dietary_fat_total`.
  - `category`: `carbs | fats | minerals | vitamins | other`. `style`: `target` (has `recommended`), `limit` (has `limit`), `info` (no lines).
  - `recommended`: `{kind: RDA | AI, male: {band: value}, female: {band: value}}`, every band present.
  - `upper_limit`: `{male, female, scope, note}` with `scope` `all_sources | supplements_only | preformed_only | folic_acid_only`. Magnesium (`supplements_only`) is from supplements and medications only; niacin (`supplements_only`) is nicotinic acid and nicotinamide from supplements and fortified foods; vitamin E (`supplements_only`) is supplemental alpha-tocopherol including fortified foods; vitamin A (`preformed_only`) is retinol and retinyl esters; folate (`folic_acid_only`) is folic acid from supplements and fortified foods, in mcg (not mcg DFE). The app tracks one total per nutrient, so for a non-`all_sources` scope the line is a guide and the chart shows the scope note under it.
  - `limit`: `{kind: fixed, value, basis}` (sodium 2,300 mg CDRR, caffeine 400 mg FDA) or `{kind: pct_energy, pct, kcal_per_unit, basis}` (added sugar 10 % ÷ 4 kcal/g, saturated fat 10 % ÷ 9 kcal/g).
  - `iu`: `{unit, mcg_per_iu}` (vitamin D, 0.025 = 40 IU per mcg) or `{unit, forms: {form: canonical amount per IU}}` (vitamin E natural 0.67 / synthetic 0.45 mg; vitamin A retinol 0.3, supplemental beta-carotene 0.3, dietary beta-carotene 0.05, dietary alpha-carotene or beta-cryptoxanthin 0.025 mcg RAE); `null` = IU is not accepted.
  - `mass_forms`: `{form: mass of the form that equals 1 canonical unit}` — vitamin A carotenoids (2, 12, 24 mcg per mcg RAE) and folate `folic_acid` (0.6 mcg per mcg DFE).

## 4. Reference functions and vector shapes
Envelope of every vector file: `{"format": "ayuvo-nutrients-vectors", "version": 1, "function", "cases": [{"name", "input", "expected", "notes"?}]}`. Zones used: `America/New_York`, `Europe/London`, `Asia/Kolkata`, `UTC` (all four appear). Ports compare numbers by value, object key order is irrelevant, array order is significant. Rounding is `round_to(x, d) = floor(x·10^d + 0.5) / 10^d`, never −0; amounts use 6 decimals, `pct_energy` limits 1 decimal.

### 4.1 `band_for_age(age)`
`null`, non-numbers and ages under 19 → `default_band` (`31-50`); otherwise the band containing `floor(age)`. A user under 19 therefore sees adult values; the About text says the values are for adults.

### 4.2 `reference_lines.json` → `reference_lines(key, profile, custom_goal)`
- input `{key, profile: {age?, sex?: "male" | "female" | null, calorie_goal?}, custom_goal?}`
- output `{key, band, sex, style, unit, recommended, upper_limit, limit, recommended_label, limit_label, upper_limit_scope, reference_recommended, reference_limit, recommended_kind, source_ids, error}`
- Unknown sex (null, missing or any other string): the **higher** of the male and female recommended value and the **lower** of the two upper limits.
- `pct_energy` limit = `round_to(calorie_goal × pct / 100 / kcal_per_unit, 1)`; no calorie goal (or ≤ 0) → `null`.
- A custom goal (a number > 0; anything else is ignored) replaces `recommended` for `target` style and sports supplements, or `limit` for `limit` and `info` style, and that line's label becomes `"Your goal"` (otherwise `"Recommended"` / `"Limit"`). `reference_recommended` / `reference_limit` keep the table values for the About section.
- Sports keys → `style: target`, no reference values, only a custom goal line. Unknown keys → `error: "unknown_nutrient"` and every line `null`.

### 4.3 `default_goal.json` → `default_goal(key, profile)`
- output `{value, value_int}`: `value` = `recommended` (target) or `limit` (limit style) for the profile, `null` for info style, sports supplements and unknown keys.
- `value_int` = `default_goal_int(value)`: round half up to an integer, but at least 1 when the value is > 0 (omega-3 1.1 g → 1, vitamin B12 2.4 mcg → 2, saturated fat for a 30 kcal goal 0.3 g → 1). The apps store integer goals; chart lines always use the exact `value`.
- Personalised defaults (platform rule): the goal the user has not customised follows `default_goal_int` for the current profile. A stored value equal to the old fixed default (`OptionalNutrient.defaultGoal` before this change) counts as "not customised".

### 4.4 `iu_conversion.json` → `convert_amount(value, unit, key, form?)`
- output `{ok, amount, unit, error}` with `amount` in the canonical unit rounded to 6 decimals.
- Errors, in check order: `unknown_nutrient`, `invalid_amount` (not a finite number > 0; booleans and strings are invalid), `unsupported_unit` (anything but g / mg / mcg / its aliases / IU, trimmed and lower-cased), `iu_not_supported`, `form_required`, `unknown_form`.
- Keys: every reference nutrient (`app_tracked` or not) and every sports supplement; anything else → `unknown_nutrient`.
- Mass: `value × mcg_per[unit] / mcg_per[canonical]` (g 1,000,000, mg 1,000, mcg 1), then ÷ the `mass_forms` factor when `form` names one (other forms are ignored for mass units). Vitamin A in a mass unit with form `null` or `"retinol"` (retinol, retinyl acetate or palmitate: "Vitamin A (Retinyl Acetate) 1000 mcg") is taken 1:1 as mcg RAE, because `retinol` is not a mass form (vector `vitamin_a_retinyl_acetate_mcg_form_null`).
- IU: vitamin D `value × 0.025`; vitamin A / E `value × forms[form]` (form required); every other nutrient → `iu_not_supported`.

### 4.5 `supplement_entries.json` → `supplement_entries(medication_nutrients, dose_logs)`
- input `{medication_nutrients: [{medication_id, nutrient_key, amount_per_unit}], dose_logs: [{medication_id, status, taken_at_ms, dose_quantity}]}`
- output `{entries: [{t_ms, nutrient_key, value, medication_id}]}` sorted by `(t_ms, nutrient_key, medication_id)`.
- Only `status = taken` with an integer `taken_at_ms`; `value = round_to(dose_quantity × amount_per_unit, 6)`, `dose_quantity` null → 1. Skipped, missed and snoozed doses add nothing.

### 4.6 `day_totals.json` → `day_totals(food_entries, supplement_entries, day, time_zone)`
- input `{food_entries: [{t_ms, nutrients: {key: value | null}}], supplement_entries: [...4.5 entries], day: "yyyy-MM-dd", time_zone}`
- output `{totals: {key: {food, supplements, total}}}` for the entries whose local day is `day`. Keys = every key named by a food entry of the day (even with only nulls) or by a supplement entry of the day. Each part is the sum of its non-null values in input order, `null` when there are none; `total` = food + supplements with a null part treated as absent, `null` only when both parts are null. Rounded to 6 decimals at the end.
- `app_tracked: false` nutrients: food values for them are ignored and do not name a key (the food log does not record them; a stray value in an entry is dropped). Their row exists only when a supplement entry of the day names them, with `food: null` (never 0), `supplements` = the sum and `total` = `supplements` (`null` when every supplement value is null). Vectors `kolkata_untracked_copper_iodine_supplements_only`, `utc_untracked_food_only_no_key`.

### 4.7 `logged_day_average.json` → `logged_day_average(entries, logged_days, interval, time_zone)`
- input `{entries: [{t_ms, value | null}], logged_days: ["yyyy-MM-dd"], interval: {start_ms, end_ms}, time_zone, key?}` — entries of ONE nutrient (food and supplement entries together); `key` (optional) names that nutrient.
- output `{average, logged_days}`. Logged days = the caller's days whose local midnight is inside `[start_ms, end_ms)`, plus the local day of every non-null entry inside the interval. `average = interval total ÷ logged day count`, rounded to 6 decimals; `null` when no entry in the interval has a value.
- Platforms pass as `logged_days` every local day with any food entry or any taken dose, and pass `key`.
- When `key` is an `app_tracked: false` nutrient the caller's `logged_days` are ignored: the logged days are exactly the days with a taken dose of that nutrient (the days of its supplement entries), so 140 mcg of iodine on 2 of 5 logged days averages 140, not 56 (vectors `utc_untracked_iodine_dose_days_only`, `utc_tracked_zinc_same_days`).

### 4.8 `label_output.json` → `parse_label_output(text)` (§7)
- output `{ok, error, serving_units, items: [{key, amount, unit, form}], rejected: [{index, code}]}`.

## 5. Chart line rules
- Nutrient metrics are `nutrient:<key>` (`shared/metrics/metric_catalog.json` → `nutrient_metrics`, docs/ui-structure.md §4), one per reference nutrient plus the sports supplements. Entries = food entries of that field (null skipped) plus `supplement_entries` of that key, bucketed by the existing `bucket_series` (no new bucketing math). For a `food_tracked: false` entry (`app_tracked: false` nutrient) the entries are the supplement entries only.
- Supplements-only note: when the entry has `food_tracked: false`, the chart shows under the headline "Food isn't recorded for ‹nutrient›: this chart counts supplements only." (‹nutrient› = the reference `name`, e.g. "Copper"). The "Food vs Supplements" section is replaced by that note (there is no food part to compare). With no taken dose in the period the chart shows the normal empty state.
- Lines show on **W, M, 6M and Y** only. Those bars are daily totals (W, M) or means of daily totals (6M, Y) per the bucketing contract, so a daily reference is comparable. On **D** (hourly bars) the lines are hidden and the header shows "Today X of Y" instead.
- Two dashed rules: "Recommended" (or "Your goal") in the domain colour, and "Upper limit" in the warning colour with the scope note when the scope is not `all_sources`. Limit-style nutrients show only "Limit" (or "Your goal"). Info-style nutrients show no rules unless the user set a goal. The y-domain grows to include every visible line.
- The headline badge reads **"Average per logged day"** (§4.7) so a weekly supplement is not shown as a daily intake.
- Sections under the chart: "Food vs Supplements" for the selected period, then About (summary, the recommended value for the user's age band and sex, the scope note, the source titles) and "Learn more about ‹nutrient›" linking to `https://ayuvo-health.web.app/nutrients/<slug>`.

## 5a. Health nutrition types
Browse › Nutrition › "All Apple Health Nutrition" (Health Connect on Android) lists the registry `dietary_*` types and opens them in the generic health metric detail screen. Those screens use the same reference as the `nutrient:` charts:
- `resolve_metric(<health id>)` (docs/ui-structure.md §7.10) returns `goal_source: nutrient.reference`, `nutrient_key`, `learn_slug` and `nutrient_metric` from the catalog's health overrides. The macro types `dietary_energy`, `dietary_protein`, `dietary_carbohydrates` and `dietary_fat_total` draw the profile calorie and macro goals instead (`profile.calories`, `profile.protein`, `profile.carbs`, `profile.fat`).
- Lines: `reference_lines(nutrient_key, profile, custom_goal)` with the same rules as §5: **W, M, 6M and Y** only (the bars are daily totals or means of daily totals), hidden on D. They show "Recommended" in the domain colour and "Upper limit" in the warning colour, with the scope note when the scope is not `all_sources`. Limit style shows "Limit"; info style shows nothing. The y-domain grows to include every line. `custom_goal` is the user's goal for that nutrient when it is `app_tracked` and has one. Otherwise it is null, because the apps store no goals for `app_tracked: false` nutrients.
- Sections: About (summary, the value for the user's band and sex, the scope note, the source titles) and "Learn more about ‹nutrient›" → `https://ayuvo-health.web.app/nutrients/<learn_slug>`.
- Badges on W, M, 6M and Y: Total, **Average per logged day** (the total over days that have a value, the same meaning as on the `nutrient:` charts) and Latest. D keeps the standard health badges.
- **No Food vs Supplements split.** The values come from Apple Health / Health Connect, which do not include supplements logged in Ayuvo Medications (§6: supplement contributions are never written to HealthKit or Health Connect). A note under the chart says so.
- Every health nutrition type with a `nutrient_key` now has a `nutrient_metric` (every reference nutrient has an app chart), so the note always has a row "Open Ayuvo ‹nutrient› chart" (`nutrient:<key>`). Its subtitle follows `food_tracked` from `resolve_metric`: `true` → "Logged in Ayuvo: food and supplements"; `false` → "Supplements logged in Ayuvo Medications" (the food log does not record this nutrient).
- Android: the per-nutrient `dietary_*` types are day rollups of Health Connect `NutritionRecord`s. Browse › Nutrition has an "All Health Connect Nutrition" row that lists the types with data, and their D chart is built by hour from the day's records.
- Limitation: HealthKit and Health Connect store vitamin A and folate as plain mcg without saying whether the value is RAE or DFE. The lines assume mcg RAE and mcg DFE.

## 5b. Nutrition Details rows for `app_tracked: false` nutrients
Nutrition Details (iOS `NutritionDetailView`, Android `NutritionDetailSheet`) keeps its 23 food-log rows and sports rows as today. For the selected local day `D`, an `app_tracked: false` nutrient `k` gets a row exactly when at least one of these holds:
1. a medication with `status = active` has a `medication_nutrients` row with `nutrient_key = k` (whether or not a dose was taken on `D`), or
2. `day_totals(…, D, …)[k].supplements` is not null (a `taken` dose with `taken_at_ms` on `D` contributed `k`, whatever the medication's status now).

Such rows are listed after the food-log rows of the same category (minerals after zinc, vitamins after folate) in reference order, show `total` for the day (`—` when null, never 0), use the reference `name`, unit and `default_goal` (no custom goal), and open `nutrient:<k>`. Paused, completed or deleted medications do not add a row by rule 1. Anything that mirrors the sheet (the "Today's Nutrition" action, docs/actions.md) uses the same row set.

## 6. Supplement contribution rules
- Keys: every reference nutrient (`app_tracked` or not) plus the sports supplements (§3). The label's carbohydrate, fat, protein and energy lines are never supplement nutrients (no macro keys exist in the list).
- Stored in `medication_nutrients` (docs/medications.md §4, schema v2): one row per medication and nutrient, `amount_per_unit` in the nutrient's canonical unit, per ONE dose unit (tablet, capsule, ml, …).
- Only `taken` doses contribute (§4.5), at `taken_at_ms`.
- Totals are recomputed from the rows and the dose history on every read and are never stored. Editing a supplement's nutrients therefore changes its past contributions too; the form's info text says so.
- Supplements are never written to HealthKit / Health Connect nutrition, never add calories, and never change the calorie ring.
- Editing a medication's nutrients bumps `medications.updated_ms` so archive merges carry the change.

## 7. AI supplement label rules
- Prompt: `shared/nutrients/ai_supplement_label.md` (cloud and compact on-device versions; photo → vision role, name and strength → text role; routing through `AIRoleResolver`, no silent fallback).
- Output `{"serving_units": n, "items": [{"key", "amount", "unit", "form"}]}`; only keys from the reference (all 37, `app_tracked` or not) and the 8 sports keys; anything not printed is left out; `{"items": []}` when unsure; no advice.
- Prompt v2 mapping hints: thiamin = B1, riboflavin = B2, niacin = B3 / nicotinamide, pantothenic_acid = B5, vitamin_b6 = pyridoxine, biotin = B7; folic acid printed only as folic acid → form `folic_acid`; a mineral printed as a salt ("Zinc sulphate 17 mg", "Cupric sulphate 1.7 mg", "Potassium iodide 140 mcg") → the printed amount under the element key, and never the salt's other element (no potassium from potassium iodide); vitamin A in mcg (retinol / retinyl esters) → form null; extracts (grape seed) and the macros / energy lines are left out. Vector `multivitamin_all_reference_nutrients` is the user's multivitamin label.
- `parse_label_output` validates every item through `convert_amount`, divides by `serving_units`, applies the sanity cap and rejects the rest with a code. The accepted items open a review sheet where the user edits, removes or confirms each row. Nothing is saved until the user confirms. Rejected items are shown as "Not recognised". Without an AI role the button shows the "Set up AI in Settings" hint.

## 8. IU rules
- IU is accepted only for vitamin D (no form needed; D2 and D3 are both 40 IU per mcg), vitamin A (form required: retinol, supplemental or dietary beta-carotene, dietary alpha-carotene / beta-cryptoxanthin) and vitamin E (form required: natural or synthetic). Every other nutrient rejects IU with `iu_not_supported`, and the unit picker offers IU only where `iu` is not null.
- Values are always stored in the canonical unit. Vitamin D may additionally show the IU equivalent (`amount × 40`).

## 9. Limitations
- Adults only, not pregnant or breastfeeding. Under 19 and without a birthday, the 31–50 adult values are used; without a sex, the higher recommended and the lower upper limit.
- Upper limits with a limited scope (magnesium, vitamin E, vitamin A, folate) are compared with the total the app tracks, which also contains food; the chart says so.
- Added sugar follows the Dietary Guidelines 2020–2025 (under 10 % of calories). The 2025–2030 Guidelines say no amount of added sugars is recommended and no more than 10 g per meal; there is no daily percentage to draw, so the line stays at 10 % and the note says so.
- Folate and vitamin A amounts are totals in DFE and RAE; the app cannot split folic acid from food folate or preformed vitamin A from carotenoids.
- Reference values were read from the NIH ODS fact sheets through Internet Archive captures of September 2026 because ods.od.nih.gov blocks automated requests; each source records its check date. Chloride has no ODS fact sheet in the reference; its AI and UL come from Health Canada's DRI table of elements, and the niacin UL scope wording from Health Canada's vitamins table.
- The 14 `app_tracked: false` nutrients are not recorded by the food log: their `nutrient:<key>` charts count Medications supplements only, and their health nutrition charts show Apple Health / Health Connect data only. Their web guide pages (`/nutrients/<slug>`, content in `scripts/web/nutrient_facts_health.py`) say Ayuvo tracks them from Medications supplements and from Apple Health / Health Connect.
- Minerals printed as a salt are taken as the elemental amount printed; a label that prints the salt's weight instead would overstate the element. Vitamin E printed in mg is taken as mg alpha-tocopherol whatever the form (d- or dl-); only IU amounts use the natural / synthetic factors.

## 10. Platform files
- Data copies (byte-identical, written by the checker): `ios/calorietracker/Nutrients/Resources/{nutrient_reference.json, ai_supplement_label.md}`, `android/app/src/main/assets/nutrients/{nutrient_reference.json, ai_supplement_label.md}`.
- Each platform runs every vector file (`NutrientsVectorTests`) with a coverage test that fails when a vector file has no runner.
- Health nutrition types: the platform metric detail for a health id reads `nutrient_key` / `learn_slug` / `nutrient_metric` / `food_tracked` from its `resolve_metric` port (§5a) and reuses the `nutrient:` chart's reference-line, About and Learn more components.

## 11. Reference table

<!-- BEGIN GENERATED NUTRIENTS -->

_Generated from `shared/nutrients/nutrient_reference.json` by `scripts/nutrients_contract_check.py --write`. Do not edit by hand._

Population: adults 19+, not pregnant or lactating. Bands: 19-30, 31-50, 51-70, 71+ (default 31-50). Values that differ by band are listed as 19–30 / 31–50 / 51–70 / 71+.

App tracked: ✓ = the food log records it; — = the food log does not record it (its `nutrient:<key>` chart counts Medications supplements only). Every row can be a supplement nutrient and an AI label key and has a `nutrient:<key>` chart.

| Nutrient | Unit | App | Health type | Style | Recommended | Upper limit (scope) | Limit | IU | Sources |
|---|---|---|---|---|---|---|---|---|---|
| Fiber (`fiber`) | g | ✓ | `dietary_fiber` | target | AI M 38 / 38 / 30 / 30 · F 25 / 25 / 21 / 21 | — | — | — | `nasem_macronutrients_2005`, `hc_dri_macronutrients` |
| Sugar (`sugar`) | g | ✓ | `dietary_sugar` | info | — | — | — | — | `dga_2020_2025` |
| Added Sugar (`added_sugar`) | g | ✓ | — | limit | — | — | 10% of calories ÷ 4 kcal/g (DGA 2020-2025) | — | `dga_2020_2025`, `dga_2025_2030` |
| Saturated Fat (`saturated_fat`) | g | ✓ | `dietary_fat_saturated` | limit | — | — | 10% of calories ÷ 9 kcal/g (DGA 2020-2025 and 2025-2030) | — | `dga_2020_2025`, `dga_2025_2030` |
| Trans Fat (`trans_fat`) | g | ✓ | — | info | — | — | — | — | `nasem_macronutrients_2005`, `hc_dri_macronutrients` |
| Monounsaturated Fat (`monounsaturated_fat`) | g | ✓ | `dietary_fat_monounsaturated` | info | — | — | — | — | `nasem_macronutrients_2005` |
| Polyunsaturated Fat (`polyunsaturated_fat`) | g | ✓ | `dietary_fat_polyunsaturated` | info | — | — | — | — | `nasem_macronutrients_2005` |
| Omega-3 (`omega_3`) | g | ✓ | — | target | AI M 1.6 · F 1.1 | — | — | — | `ods_omega3_hp`, `nasem_macronutrients_2005` |
| Cholesterol (`cholesterol`) | mg | ✓ | `dietary_cholesterol` | info | — | — | — | — | `nasem_macronutrients_2005`, `hc_dri_macronutrients` |
| Sodium (`sodium`) | mg | ✓ | `dietary_sodium` | limit | — | — | 2300 (CDRR) | — | `nasem_sodium_potassium_2019`, `dga_2025_2030` |
| Potassium (`potassium`) | mg | ✓ | `dietary_potassium` | target | AI M 3400 · F 2600 | — | — | — | `ods_potassium_hp`, `nasem_sodium_potassium_2019` |
| Calcium (`calcium`) | mg | ✓ | `dietary_calcium` | target | RDA M 1000 / 1000 / 1000 / 1200 · F 1000 / 1000 / 1200 / 1200 | 2500 / 2500 / 2000 / 2000 (all_sources) | — | — | `ods_calcium_hp` |
| Iron (`iron`) | mg | ✓ | `dietary_iron` | target | RDA M 8 · F 18 / 18 / 8 / 8 | 45 (all_sources) | — | — | `ods_iron_hp` |
| Magnesium (`magnesium`) | mg | ✓ | `dietary_magnesium` | target | RDA M 400 / 420 / 420 / 420 · F 310 / 320 / 320 / 320 | 350 (supplements_only) | — | — | `ods_magnesium_hp` |
| Zinc (`zinc`) | mg | ✓ | `dietary_zinc` | target | RDA M 11 · F 8 | 40 (all_sources) | — | — | `ods_zinc_hp` |
| Phosphorus (`phosphorus`) | mg | — | `dietary_phosphorus` | target | RDA 700 | 4000 / 4000 / 4000 / 3000 (all_sources) | — | — | `ods_phosphorus_hp` |
| Chloride (`chloride`) | mg | — | `dietary_chloride` | target | AI 2300 / 2300 / 2000 / 1800 | 3600 (all_sources) | — | — | `hc_dri_elements` |
| Copper (`copper`) | mg | — | `dietary_copper` | target | RDA 0.9 | 10 (all_sources) | — | — | `ods_copper_hp` |
| Manganese (`manganese`) | mg | — | `dietary_manganese` | target | AI M 2.3 · F 1.8 | 11 (all_sources) | — | — | `ods_manganese_hp` |
| Selenium (`selenium`) | mcg | — | `dietary_selenium` | target | RDA 55 | 400 (all_sources) | — | — | `ods_selenium_hp` |
| Chromium (`chromium`) | mcg | — | `dietary_chromium` | target | AI M 35 / 35 / 30 / 30 · F 25 / 25 / 20 / 20 | — | — | — | `ods_chromium_hp` |
| Molybdenum (`molybdenum`) | mcg | — | `dietary_molybdenum` | target | RDA 45 | 2000 (all_sources) | — | — | `ods_molybdenum_hp` |
| Iodine (`iodine`) | mcg | — | `dietary_iodine` | target | RDA 150 | 1100 (all_sources) | — | — | `ods_iodine_hp` |
| Vitamin A (`vitamin_a`) | mcg | ✓ | `dietary_vitamin_a` | target | RDA M 900 · F 700 | 3000 (preformed_only) | — | food_alpha_carotene_beta_cryptoxanthin 0.025 mcg; food_beta_carotene 0.05 mcg; retinol 0.3 mcg; supplement_beta_carotene 0.3 mcg | `ods_vitamin_a_hp` |
| Vitamin C (`vitamin_c`) | mg | ✓ | `dietary_vitamin_c` | target | RDA M 90 · F 75 | 2000 (all_sources) | — | — | `ods_vitamin_c_hp` |
| Vitamin D (`vitamin_d`) | mcg | ✓ | `dietary_vitamin_d` | target | RDA 15 / 15 / 15 / 20 | 100 (all_sources) | — | 1 IU = 0.025 mcg | `ods_vitamin_d_hp` |
| Vitamin E (`vitamin_e`) | mg | ✓ | `dietary_vitamin_e` | target | RDA 15 | 1000 (supplements_only) | — | natural 0.67 mg; synthetic 0.45 mg | `ods_vitamin_e_hp` |
| Vitamin K (`vitamin_k`) | mcg | ✓ | `dietary_vitamin_k` | target | AI M 120 · F 90 | — | — | — | `ods_vitamin_k_hp` |
| Vitamin B12 (`vitamin_b12`) | mcg | ✓ | `dietary_vitamin_b12` | target | RDA 2.4 | — | — | — | `ods_vitamin_b12_hp` |
| Folate (`folate`) | mcg | ✓ | `dietary_folate` | target | RDA 400 | 1000 (folic_acid_only) | — | — | `ods_folate_hp` |
| Vitamin B6 (`vitamin_b6`) | mg | — | `dietary_vitamin_b6` | target | RDA M 1.3 / 1.3 / 1.7 / 1.7 · F 1.3 / 1.3 / 1.5 / 1.5 | 100 (all_sources) | — | — | `ods_vitamin_b6_hp` |
| Thiamin (`thiamin`) | mg | — | `dietary_thiamin` | target | RDA M 1.2 · F 1.1 | — | — | — | `ods_thiamin_hp` |
| Riboflavin (`riboflavin`) | mg | — | `dietary_riboflavin` | target | RDA M 1.3 · F 1.1 | — | — | — | `ods_riboflavin_hp` |
| Niacin (`niacin`) | mg | — | `dietary_niacin` | target | RDA M 16 · F 14 | 35 (supplements_only) | — | — | `ods_niacin_hp`, `hc_dri_vitamins` |
| Biotin (`biotin`) | mcg | — | `dietary_biotin` | target | AI 30 | — | — | — | `ods_biotin_hp` |
| Pantothenic Acid (`pantothenic_acid`) | mg | — | `dietary_pantothenic_acid` | target | AI 5 | — | — | — | `ods_pantothenic_acid_hp` |
| Caffeine (`caffeine`) | mg | ✓ | `dietary_caffeine` | limit | — | — | 400 (FDA) | — | `fda_caffeine` |

Mass forms (amount of the form that equals 1 canonical unit): vitamin_a: food_alpha_carotene_beta_cryptoxanthin 24, food_beta_carotene 12, supplement_beta_carotene 2; folate: folic_acid 0.6.

Sports supplements (no reference lines, no guide page): `creatine`, `beta_alanine`, `l_citrulline`, `l_carnitine`, `l_arginine`, `taurine`, `betaine`, `hmb` — unit g.

Sanity cap per dose unit: 100 g, 100,000 mcg, 10,000 mg.

### Notes

- **Fiber.** The AI is based on 14 g of fiber per 1,000 kcal.
- **Sugar.** No authoritative daily amount exists for total sugars; the limit applies to added sugars.
- **Added Sugar.** Less than 10% of daily calories (Dietary Guidelines 2020-2025). The Dietary Guidelines 2025-2030 say no amount of added sugars is recommended and that one meal should contain no more than 10 g.
- **Saturated Fat.** Less than 10% of daily calories (both the 2020-2025 and 2025-2030 Dietary Guidelines).
- **Trans Fat.** Keep as low as possible while eating a nutritionally adequate diet; no numeric limit is set.
- **Monounsaturated Fat.** No recommended daily amount is set for this fat.
- **Polyunsaturated Fat.** No recommended daily amount is set for total polyunsaturated fat.
- **Omega-3.** The AI applies to alpha-linolenic acid (ALA) only; EPA and DHA have no separate recommended amount.
- **Cholesterol.** Keep as low as possible while eating a nutritionally adequate diet; no numeric limit is set.
- **Sodium.** 2,300 mg is the Chronic Disease Risk Reduction Intake (CDRR): cutting intake above it is expected to lower chronic disease risk. The Adequate Intake (AI) for adults is 1,500 mg.
- **Potassium.** No upper limit is set for potassium from food; people with kidney problems should follow their clinician's advice.
- **Calcium.** Applies to food, drinks and supplements combined.
- **Iron.** People who eat a vegetarian diet need about 1.8 times more iron. Applies to food, drinks and supplements combined.
- **Magnesium.** Applies only to magnesium from supplements and medications, not magnesium in food and drinks.
- **Zinc.** Applies to food, drinks and supplements combined.
- **Phosphorus.** Applies to food, drinks and supplements combined.
- **Chloride.** Applies to food, drinks and supplements combined.
- **Copper.** Amounts are in mg; the RDA of 0.9 mg equals 900 mcg. Applies to food, drinks and supplements combined.
- **Manganese.** Applies to food, drinks and supplements combined.
- **Selenium.** In 2023 the European Food Safety Authority set a lower upper limit of 255 mcg a day for adults. Applies to food, drinks and supplements combined.
- **Chromium.** No upper limit is set because no adverse effects have been linked to high intakes from food or supplements.
- **Molybdenum.** Applies to food, drinks and supplements combined.
- **Iodine.** Applies to food, drinks and supplements combined.
- **Vitamin A.** Amounts are in mcg retinol activity equivalents (RAE). Applies to preformed vitamin A (retinol and retinyl esters) only; beta-carotene does not count toward it. Ayuvo tracks total vitamin A in mcg RAE, so the line is a guide.
- **Vitamin C.** People who smoke need 35 mg more vitamin C per day. Applies to food, drinks and supplements combined.
- **Vitamin D.** 1 mcg of vitamin D equals 40 IU. Applies to food, drinks and supplements combined.
- **Vitamin E.** Amounts are in mg alpha-tocopherol. 1 IU natural (d-alpha) = 0.67 mg; 1 IU synthetic (dl-alpha) = 0.45 mg. Applies to supplemental alpha-tocopherol (supplements and fortified foods), not vitamin E naturally in food.
- **Vitamin K.** No upper limit is set. People taking blood thinners such as warfarin should keep vitamin K intake steady and follow their clinician's advice.
- **Vitamin B12.** No upper limit is set because of its low potential for toxicity.
- **Folate.** Amounts are in mcg dietary folate equivalents (DFE); 1 mcg DFE = 0.6 mcg folic acid taken with food. Applies to folic acid from supplements and fortified foods (mcg, not mcg DFE), not folate naturally in food.
- **Vitamin B6.** In 2023 the European Food Safety Authority set a lower upper limit of 12 mg a day for adults. Applies to food, drinks and supplements combined.
- **Thiamin.** No upper limit is set because no adverse effects from high intakes from food or supplements have been reported.
- **Riboflavin.** No upper limit is set because no adverse effects from high intakes from food or supplements have been reported.
- **Niacin.** Amounts are in mg niacin equivalents (NE); 1 mg NE equals 1 mg niacin or 60 mg of the amino acid tryptophan. Applies only to niacin from supplements and fortified foods (nicotinic acid and nicotinamide), not niacin naturally in food.
- **Biotin.** No upper limit is set because there is no evidence that biotin is toxic at high intakes.
- **Pantothenic Acid.** No upper limit is set because no toxicity from high intakes has been reported.
- **Caffeine.** The FDA cites 400 mg a day for most healthy adults as an amount not generally associated with negative effects; sensitivity varies.

### Sources

- `dga_2020_2025`: Dietary Guidelines for Americans, 2020-2025 — U.S. Department of Agriculture and U.S. Department of Health and Human Services. <https://www.dietaryguidelines.gov/sites/default/files/2020-12/Dietary_Guidelines_for_Americans_2020-2025.pdf> (checked 2026-09-27)
- `dga_2025_2030`: Dietary Guidelines for Americans, 2025-2030 — U.S. Department of Agriculture and U.S. Department of Health and Human Services. <https://cdn.realfood.gov/DGA.pdf> (checked 2026-09-27)
- `fda_caffeine`: Spilling the Beans: How Much Caffeine Is Too Much? — U.S. Food and Drug Administration. <https://www.fda.gov/consumers/consumer-updates/spilling-beans-how-much-caffeine-too-much> (checked 2026-09-27, page updated 2024-08-28)
- `hc_dri_elements`: Dietary Reference Intakes tables: Reference values for elements — Health Canada. <https://www.canada.ca/en/health-canada/services/food-nutrition/healthy-eating/dietary-reference-intakes/tables/reference-values-elements.html> (checked 2026-09-27, page updated 2025-11-19)
- `hc_dri_macronutrients`: Dietary Reference Intakes tables: Reference values for macronutrients — Health Canada. <https://www.canada.ca/en/health-canada/services/food-nutrition/healthy-eating/dietary-reference-intakes/tables/reference-values-macronutrients.html> (checked 2026-09-27)
- `hc_dri_vitamins`: Dietary Reference Intakes tables: Reference values for vitamins — Health Canada. <https://www.canada.ca/en/health-canada/services/food-nutrition/healthy-eating/dietary-reference-intakes/tables/reference-values-vitamins.html> (checked 2026-09-27, page updated 2025-11-19)
- `nasem_macronutrients_2005`: Dietary Reference Intakes for Energy, Carbohydrate, Fiber, Fat, Fatty Acids, Cholesterol, Protein, and Amino Acids (2005) — National Academies of Sciences, Engineering, and Medicine. <https://nap.nationalacademies.org/catalog/10490> (checked 2026-09-27)
- `nasem_sodium_potassium_2019`: Dietary Reference Intakes for Sodium and Potassium (2019) — National Academies of Sciences, Engineering, and Medicine. <https://nap.nationalacademies.org/catalog/25353/dietary-reference-intakes-for-sodium-and-potassium> (checked 2026-09-27)
- `ods_biotin_hp`: Biotin - Health Professional Fact Sheet — NIH Office of Dietary Supplements. <https://ods.od.nih.gov/factsheets/Biotin-HealthProfessional/> (checked 2026-09-27, page updated 2022-01-10)
- `ods_calcium_hp`: Calcium - Health Professional Fact Sheet — NIH Office of Dietary Supplements. <https://ods.od.nih.gov/factsheets/Calcium-HealthProfessional/> (checked 2026-09-27, page updated 2026-06-22)
- `ods_chromium_hp`: Chromium - Health Professional Fact Sheet — NIH Office of Dietary Supplements. <https://ods.od.nih.gov/factsheets/Chromium-HealthProfessional/> (checked 2026-09-27, page updated 2022-06-02)
- `ods_copper_hp`: Copper - Health Professional Fact Sheet — NIH Office of Dietary Supplements. <https://ods.od.nih.gov/factsheets/Copper-HealthProfessional/> (checked 2026-09-27, page updated 2022-10-18)
- `ods_folate_hp`: Folate - Health Professional Fact Sheet — NIH Office of Dietary Supplements. <https://ods.od.nih.gov/factsheets/Folate-HealthProfessional/> (checked 2026-09-27, page updated 2022-11-30)
- `ods_iodine_hp`: Iodine - Health Professional Fact Sheet — NIH Office of Dietary Supplements. <https://ods.od.nih.gov/factsheets/Iodine-HealthProfessional/> (checked 2026-09-27, page updated 2024-11-05)
- `ods_iron_hp`: Iron - Health Professional Fact Sheet — NIH Office of Dietary Supplements. <https://ods.od.nih.gov/factsheets/Iron-HealthProfessional/> (checked 2026-09-27, page updated 2025-09-04)
- `ods_magnesium_hp`: Magnesium - Health Professional Fact Sheet — NIH Office of Dietary Supplements. <https://ods.od.nih.gov/factsheets/Magnesium-HealthProfessional/> (checked 2026-09-27, page updated 2026-01-06)
- `ods_manganese_hp`: Manganese - Health Professional Fact Sheet — NIH Office of Dietary Supplements. <https://ods.od.nih.gov/factsheets/Manganese-HealthProfessional/> (checked 2026-09-27, page updated 2021-03-29)
- `ods_molybdenum_hp`: Molybdenum - Health Professional Fact Sheet — NIH Office of Dietary Supplements. <https://ods.od.nih.gov/factsheets/Molybdenum-HealthProfessional/> (checked 2026-09-27, page updated 2021-03-30)
- `ods_niacin_hp`: Niacin - Health Professional Fact Sheet — NIH Office of Dietary Supplements. <https://ods.od.nih.gov/factsheets/Niacin-HealthProfessional/> (checked 2026-09-27, page updated 2022-11-18)
- `ods_omega3_hp`: Omega-3 Fatty Acids - Health Professional Fact Sheet — NIH Office of Dietary Supplements. <https://ods.od.nih.gov/factsheets/Omega3FattyAcids-HealthProfessional/> (checked 2026-09-27, page updated 2025-08-22)
- `ods_pantothenic_acid_hp`: Pantothenic Acid - Health Professional Fact Sheet — NIH Office of Dietary Supplements. <https://ods.od.nih.gov/factsheets/PantothenicAcid-HealthProfessional/> (checked 2026-09-27, page updated 2026-05-01)
- `ods_phosphorus_hp`: Phosphorus - Health Professional Fact Sheet — NIH Office of Dietary Supplements. <https://ods.od.nih.gov/factsheets/Phosphorus-HealthProfessional/> (checked 2026-09-27, page updated 2023-05-04)
- `ods_potassium_hp`: Potassium - Health Professional Fact Sheet — NIH Office of Dietary Supplements. <https://ods.od.nih.gov/factsheets/Potassium-HealthProfessional/> (checked 2026-09-27, page updated 2022-06-02)
- `ods_riboflavin_hp`: Riboflavin - Health Professional Fact Sheet — NIH Office of Dietary Supplements. <https://ods.od.nih.gov/factsheets/Riboflavin-HealthProfessional/> (checked 2026-09-27, page updated 2022-05-11)
- `ods_selenium_hp`: Selenium - Health Professional Fact Sheet — NIH Office of Dietary Supplements. <https://ods.od.nih.gov/factsheets/Selenium-HealthProfessional/> (checked 2026-09-27, page updated 2025-09-04)
- `ods_thiamin_hp`: Thiamin - Health Professional Fact Sheet — NIH Office of Dietary Supplements. <https://ods.od.nih.gov/factsheets/Thiamin-HealthProfessional/> (checked 2026-09-27, page updated 2023-02-09)
- `ods_vitamin_a_hp`: Vitamin A and Carotenoids - Health Professional Fact Sheet — NIH Office of Dietary Supplements. <https://ods.od.nih.gov/factsheets/VitaminA-HealthProfessional/> (checked 2026-09-27, page updated 2025-03-10)
- `ods_vitamin_b12_hp`: Vitamin B12 - Health Professional Fact Sheet — NIH Office of Dietary Supplements. <https://ods.od.nih.gov/factsheets/VitaminB12-HealthProfessional/> (checked 2026-09-27, page updated 2025-07-02)
- `ods_vitamin_b6_hp`: Vitamin B6 - Health Professional Fact Sheet — NIH Office of Dietary Supplements. <https://ods.od.nih.gov/factsheets/VitaminB6-HealthProfessional/> (checked 2026-09-27, page updated 2023-06-16)
- `ods_vitamin_c_hp`: Vitamin C - Health Professional Fact Sheet — NIH Office of Dietary Supplements. <https://ods.od.nih.gov/factsheets/VitaminC-HealthProfessional/> (checked 2026-09-27, page updated 2025-07-31)
- `ods_vitamin_d_hp`: Vitamin D - Health Professional Fact Sheet — NIH Office of Dietary Supplements. <https://ods.od.nih.gov/factsheets/VitaminD-HealthProfessional/> (checked 2026-09-27, page updated 2025-06-27)
- `ods_vitamin_e_hp`: Vitamin E - Health Professional Fact Sheet — NIH Office of Dietary Supplements. <https://ods.od.nih.gov/factsheets/VitaminE-HealthProfessional/> (checked 2026-09-27, page updated 2021-03-26)
- `ods_vitamin_k_hp`: Vitamin K - Health Professional Fact Sheet — NIH Office of Dietary Supplements. <https://ods.od.nih.gov/factsheets/VitaminK-HealthProfessional/> (checked 2026-09-27, page updated 2021-03-29)
- `ods_zinc_hp`: Zinc - Health Professional Fact Sheet — NIH Office of Dietary Supplements. <https://ods.od.nih.gov/factsheets/Zinc-HealthProfessional/> (checked 2026-09-27, page updated 2026-01-06)

<!-- END GENERATED NUTRIENTS -->
