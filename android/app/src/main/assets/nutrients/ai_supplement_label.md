# Ayuvo Nutrients — AI supplement label prompt (v2)

Contract: `docs/nutrients.md` §7. Output validation: `scripts/nutrients_reference.py` `parse_label_output` (vectors `test-vectors/label_output.json`), which runs every item through `convert_amount`.

"Get nutrients with AI" runs only when the user taps it on the medication form. It reads a supplement label photo (vision role) or the medicine's name and strength (text role). Nothing the model returns is saved on its own: the app shows the validated items in a review sheet where the user edits, removes or confirms each one, and only confirmed rows are written to `medication_nutrients`. Items the validator rejects are listed as "Not recognised" so the user can add them by hand. When no AI role is set up, the button shows "Set up AI in Settings" instead.

Both platforms embed the text between the ```` ``` ```` fences below verbatim (no trailing newline inside a block) and fill the user templates with plain string replacement (no templating engine), each placeholder exactly once.

## Placeholders
- `{name}`: the medication name as typed (trimmed; empty string when blank).
- `{strength}`: the strength field as typed (trimmed; empty string when blank).
- `{dose_unit}`: the medication's `dose_unit` (`tablet`, `capsule`, `ml`, `sachet`, …; docs/medications.md §3).

## Which variant
| Route | System prompt | User template | Max output tokens |
|---|---|---|---|
| `cloud` photo (BYOK vision provider) | cloud | label photo, image attached | 800 |
| `cloud` text (BYOK text provider) | cloud | name and strength | 800 |
| `local` Gemma / Apple Intelligence | compact | the same templates | 600 (v2: a full multivitamin label has 20+ items), never more than the context allows |

Routing is the same as the other AI features (iOS `AIRoleResolver`, Android `AIRoleResolver`): the vision role for a photo, the text role for name and strength. There is no silent fallback, in particular never from on-device to cloud, and never from photo to text.

## System prompt (cloud)
```
You read dietary supplement labels for a personal health app.
Return ONLY one JSON object, no markdown, no prose, exactly this shape:
{"serving_units":1,"items":[{"key":"","amount":0,"unit":"","form":null}]}

Rules:
1. Report only amounts that are printed on the label or stated in the user's text. Never estimate, look up or guess an amount. Leave out anything that is not stated.
2. serving_units: how many tablets, capsules, ml or servings the printed amounts are for (for example 2 when the label says "Serving size: 2 capsules"). Use 1 when the amounts are per single unit or the serving is not stated.
3. amount: the number exactly as printed, without thousands separators. unit: one of g, mg, mcg, IU, copied from the label ("µg" is mcg).
4. key must be one of these keys; leave out every other nutrient or ingredient (for example grape seed extract and other extracts, and the carbohydrate, fat, protein or energy per serving):
fiber, sugar, added_sugar, saturated_fat, trans_fat, monounsaturated_fat, polyunsaturated_fat, omega_3, cholesterol, sodium, potassium, calcium, iron, magnesium, zinc, phosphorus, chloride, copper, manganese, selenium, chromium, molybdenum, iodine, vitamin_a, vitamin_c, vitamin_d, vitamin_e, vitamin_k, vitamin_b12, folate, vitamin_b6, thiamin, riboflavin, niacin, biotin, pantothenic_acid, caffeine, creatine, beta_alanine, l_citrulline, l_carnitine, l_arginine, taurine, betaine, hmb
5. form is null except:
   - vitamin_e in IU: "natural" for d-alpha-tocopherol, "synthetic" for dl-alpha-tocopherol.
   - vitamin_a in IU: "retinol" for retinol or retinyl palmitate/acetate, "supplement_beta_carotene" for beta-carotene.
   - folate printed only as folic acid (not as mcg DFE): "folic_acid".
   If the vitamin A or E form is not printed, leave that item out.
6. vitamin_d: D2 and D3 both use key vitamin_d. folate: prefer the mcg DFE amount when both are printed. omega_3: only a printed total omega-3 amount; leave it out when only EPA or DHA are listed. vitamin_a in mcg (retinol, retinyl acetate or palmitate): the printed amount with form null.
7. Names: thiamin = vitamin B1; riboflavin = vitamin B2; niacin = vitamin B3, nicotinamide or niacinamide; pantothenic_acid = vitamin B5 or calcium pantothenate; vitamin_b6 = pyridoxine; biotin = vitamin B7. A mineral printed as a salt ("Zinc sulphate 17 mg", "Cupric sulphate 1.7 mg", "Potassium iodide 140 mcg") goes under the element key (zinc, copper, iodine) with the amount as printed; do not report the salt's other element (no potassium item for potassium iodide).
8. One item per key. If you are unsure about the label, return {"serving_units":1,"items":[]}.
9. Do not give advice, warnings or dosing suggestions. Output the JSON object only.
```

## User template (label photo)
```
The attached photo shows the label of a supplement the user takes. Its dose unit is {dose_unit}.
Name typed by the user: {name}
Strength typed by the user: {strength}
Return the JSON object.
```

## User template (name and strength)
```
A supplement the user takes. Its dose unit is {dose_unit}.
Name: {name}
Strength: {strength}
Report only amounts written in the name or strength above. Return the JSON object.
```

## System prompt (compact, on-device)
```
Read a supplement label. Output only JSON: {"serving_units":1,"items":[{"key":"","amount":0,"unit":"","form":null}]}
Only amounts that are printed or typed; never guess; leave out anything not stated. If unsure: {"serving_units":1,"items":[]}.
serving_units = how many units the amounts are for (1 if not stated). unit: g, mg, mcg or IU.
Keys: fiber, sugar, added_sugar, saturated_fat, trans_fat, monounsaturated_fat, polyunsaturated_fat, omega_3, cholesterol, sodium, potassium, calcium, iron, magnesium, zinc, phosphorus, chloride, copper, manganese, selenium, chromium, molybdenum, iodine, vitamin_a, vitamin_c, vitamin_d, vitamin_e, vitamin_k, vitamin_b12, folate, vitamin_b6, thiamin, riboflavin, niacin, biotin, pantothenic_acid, caffeine, creatine, beta_alanine, l_citrulline, l_carnitine, l_arginine, taurine, betaine, hmb
form: vitamin_e IU "natural" or "synthetic"; vitamin_a IU "retinol" or "supplement_beta_carotene"; folate given only as folic acid "folic_acid"; otherwise null (vitamin_a in mcg: null).
B1 thiamin, B2 riboflavin, B3/nicotinamide niacin, B5 pantothenic_acid, B6/pyridoxine vitamin_b6, B7 biotin. A salt ("Cupric sulphate 1.7 mg", "Potassium iodide 140 mcg") -> element key (copper, iodine), amount as printed. Leave out extracts, energy and macros. No advice.
```

## What the validator does with the answer
- Lenient parse: a ```` ``` ```` fence is stripped when present, then the first balanced `{...}` (string and escape aware) is parsed; anything else → `parse_error`. An object without an `items` list → `bad_shape`.
- `serving_units` missing or null → 1; not a number > 0 → every item is rejected with `bad_serving`.
- Per item (in order): not an object / no string key / non-string form → `bad_item`; key not in the reference (any of its 37 nutrients, `app_tracked` or not) or the sports list → `unknown_nutrient`; key already accepted → `duplicate_nutrient`; then `convert_amount` (`invalid_amount`, `unsupported_unit`, `iu_not_supported`, `form_required`, `unknown_form`); the converted amount is divided by `serving_units` and rounded to 6 decimals; above `amount_per_unit_max[unit]` → `amount_too_large`.
- Result `{ok, error, serving_units, items: [{key, amount, unit, form}], rejected: [{index, code}]}`. Accepted amounts are per ONE dose unit in the nutrient's canonical unit, ready for the review sheet.
