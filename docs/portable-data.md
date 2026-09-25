# Portable data section (`ayuvo-portable-data`), the cross-platform part of Export All Data

Export All Data (`ayuvo-all-data`, docs/cloud-backup.md) already moves the food diary, health data, medications, Health Records and Coach chats between iPhone and Android. The **app backup** part (`app-backup/ayuvo-backup.zip`) restores profile, settings and logs, but only on the platform that made it: preference keys, date encodings and workout enums differ. The portable section closes that gap: **one JSON file both platforms write and read**, carrying what a person expects to survive a phone change across platforms.

This file is the contract. `shared/portable/fixtures/portable-sample.json` is the canonical sample; both platforms have a unit test that imports it and asserts the resulting native values, and a round-trip test (own export → own import).

## Where it lives in the zip

| Item | Value |
|------|-------|
| Section id | `portable_data` |
| Manifest `format` | `ayuvo-portable-data` |
| Entry name | `portable-data/ayuvo-portable-data.json` |
| Manifest counts | `weights`, `body_fat`, `body_measurements`, `fasting_sessions`, `workout_sessions`, `user_exercises`, `settings` (a count of 0 is left out) |
| Import order | `app_backup` → **`portable_data`** → `food_diary` → `health_data` → `medications` → `health_records` → `coach_chats` |

Both platforms write it on **every** export (next to the platform's own app backup), skipping it only when there is nothing to carry (no profile, no logs, no non-default settings). Old builds ignore unknown section ids, so a file with this section still imports there.

### Import dispositions

| Situation | Disposition |
|-----------|-------------|
| Zip made on the other platform (`manifest.platform` differs) | **Import** (app backup is skipped as `otherPlatform`; the food diary JSON is merged as before) |
| Same platform and the `app_backup` part was restored | **Skip**, covered by the app backup (Android `SkipReason.IN_APP_BACKUP`, iOS `.coveredByAppBackup`); imported after all if the app backup failed (iOS; Android re-plans the same way) |
| Same platform, no `app_backup` part in the file | Import |

## Encoding rules

- UTF-8 JSON object, keys snake_case, unknown keys ignored, **every top-level section optional** (missing = leave the device as it is).
- Timestamps: ISO-8601 UTC with milliseconds, `2026-09-25T10:30:00.000Z`. Readers also accept an offset (`+05:30`) and no fraction. Never epoch numbers, never iOS reference-date doubles.
- Local calendar days: `yyyy-MM-dd` strings.
- UUIDs: lowercase hyphenated strings; compare case-insensitively.
- Enums: the values in the tables below. Profile enums are the same strings both platforms already store (`male`, `veryActive`); workout enums are lower snake case (Android stores them upper case, iOS in its own camelCase, so both convert).
- Numbers are plain JSON numbers. Fractions (`body_fat_fraction`) are 0…1, never percentages.
- Optional values are omitted or `null`.

## Top level

```json
{
  "app": "Ayuvo",
  "format": "ayuvo-portable-data",
  "format_version": 1,
  "created_at": "2026-09-25T10:30:00.000Z",
  "platform": "ios",
  "app_version": "1.0",
  "profile": { },
  "units": { },
  "preferences": { },
  "water": { "settings": { } },
  "fasting": { "settings": { }, "sessions": [ ] },
  "weights": [ ],
  "body_fat": [ ],
  "body_measurements": [ ],
  "workouts": { }
}
```

`format` must equal `ayuvo-portable-data` and `format_version` must be ≤ 1, otherwise the section is refused (`newerVersion` / `UNSUPPORTED`).

### `profile`

| Key | Type | Notes |
|-----|------|-------|
| `name` | string? | |
| `gender` | `male` \| `female` \| `other` | |
| `birthday` | `yyyy-MM-dd` | The local calendar day; readers build start-of-day in their own zone. Exporters emit the local day of the stored instant. |
| `height_cm`, `weight_kg` | number | |
| `activity_level` | `sedentary` \| `light` \| `moderate` \| `active` \| `veryActive` \| `extraActive` | |
| `goal` | `lose` \| `maintain` \| `gain` | |
| `body_fat_fraction`, `goal_body_fat_fraction` | number? | 0…1 |
| `weekly_change_kg`, `goal_weight_kg` | number? | |
| `custom_calories`, `custom_protein_g`, `custom_fat_g`, `custom_carbs_g` | int? | The calorie and macro targets |
| `auto_balance_macro` | `protein` \| `carbs` \| `fat`? | |
| `allergens` | string[] | Free-text names |
| `calories_locked` | bool | default false |
| `locked_macros` | (`protein`\|`carbs`\|`fat`)[] | at most 2 |

Import **replaces** the device profile when `profile` is present. Not carried: the legacy `useBodyFatInBMR`, goal signatures and adaptive-goal bookkeeping (`adaptiveGoalsPreviousTargets`, last-check days).

### `units`

`height_unit` (`cm` \| `ftin`), `weight_unit` (`kg` \| `lbs`), `water_unit` (`ml` \| `floz`), `glucose_unit` (`mmol/L` \| `mg/dL`, omit when unset). Present keys are written to the same-named preferences (`heightUnit`, `weightUnit`, `waterUnit`, `healthGlucoseUnit`).

### `preferences` (only keys with a counterpart on both platforms)

| Key | Type | Native key |
|-----|------|-----------|
| `week_starts_on_monday` | bool | `weekStartsOnMonday` |
| `daily_step_goal` | int 1000…50000 | `dailyStepGoal` |
| `appearance_mode` | `system` \| `light` \| `dark` | `appearanceMode` |
| `app_theme_color` | one of the 18 theme keys (`rose`, `red`, … `lime`) | `appThemeColor` |
| `adaptive_goals_enabled` | bool | `adaptiveGoalsEnabled` |
| `food_measurement_prefer_grams` | bool | `foodMeasurementPreferGramsByDefault` |
| `meal_start_minutes` | `{breakfast, lunch, dinner, snack}` ints (strictly increasing, < 1440, else ignored) | `meal{Breakfast,Lunch,Dinner,Snack}StartMinutes` |
| `summary_favourites` | string[] (max 12) | `summaryFavourites` (iOS: array, Android: comma-separated) |
| `optional_nutrient_goals` | `{ "<camelCase nutrient>": int 0…999999 }`, supplements (`creatine`, `betaAlanine`, `lCitrulline`, `lCarnitine`, `lArginine`, `taurine`, `betaine`, `hmb`) in the same flat object | `optionalNutrientGoals` (iOS `{values:{…}}` blob, Android object + `supplementalNutrients` map) |

Out of range or unknown values are ignored one by one. **Never carried:** notification and reminder switches and times (they differ per platform and need an OS grant), health-sync flags, AI provider/model/keys, speech provider, quick actions and Add-menu layout (different method sets), Records/medication/Coach preferences, onboarding state, widgets.

### `water.settings` / `fasting.settings`

`water.settings`: `tracking_enabled`, `daily_goal_ml`. `fasting.settings`: `tracking_enabled`, `default_goal_minutes` (60…10080), `goal_notification_enabled`. Water **entries** are not here: they travel in the food diary JSON.

### Logs (merge by id; nothing is deleted)

- `weights[]`: `{ id, date, weight_kg }`
- `body_fat[]`: `{ id, date, fraction }`
- `body_measurements[]`: `{ id, date, neck_cm?, waist_cm?, hips_cm?, chest_cm?, upper_arm_cm?, thigh_cm?, calf_cm?, wrist_cm? }`
- `fasting.sessions[]`: `{ id, started_at, ended_at?, goal_minutes }`. `ended_at` missing = active. Import never creates a second active session or an overlap: such a row is dropped.

Merge rule: skip a row whose `id` already exists (case-insensitive); for `weights` and `body_fat` also skip a row with the same timestamp (to the second) and the same value, because Health Connect / HealthKit ids differ per platform. Rows are then sorted the way the platform's store sorts. After the logs are applied, `profile` is applied **last**, so a replaced profile weight/body fat is not overwritten by the "sync profile to latest entry" side effect (Android `WeightRepository.replaceAll`). Imports write through the stores' own persistence but **do not** call the Health write callbacks (HealthKit / Health Connect): the samples came from a Health app and re-writing them would duplicate.

### `workouts`

Everything the workout diary keeps that both platforms model the same way. Normalized because the two apps store different enum spellings.

```json
{
  "preferences": {
    "target_muscles": ["chest"], "issues": ["lower_back"], "additional_issues": "",
    "frequency_days": 3, "duration_minutes": 60, "split": "full_body", "custom_split": "",
    "equipment": ["barbell"], "rpe_scale": "strength",
    "strength": { "bench_press_kg": 80, "squat_kg": 100, "deadlift_kg": 120, "overhead_press_kg": 50 }
  },
  "saved_exercise_ids": ["0001", "user_exercise_…"],
  "user_exercises": [ Exercise ],
  "custom_activities": [ Exercise ],
  "day_plans": { "2026-09-25": [ Exercise ] },
  "sessions": [ Session ]
}
```

- `split`: `push_pull_legs` (iOS `pushPullLegs`), `upper_lower` (`upperLower`), `body_part` (`broSplit`), `arnold`, `push_pull` (`pushPull`), `antagonist` (`antagonistSplit`), `hybrid` (`hybridSplit`), `full_body` (`fullBody`), `custom`. Android has no `custom` (it coerces to `full_body`).
- `issues[]`: `shoulder`, `elbow`, `wrist`, `lower_back`, `hip`, `knee`, `ankle`, `other`.
- `rpe_scale`: `strength` \| `cr10` \| `borg`. `weight_unit`: `kg` \| `lbs`. `intensity`: `light` \| `moderate` \| `vigorous`.
- **Exercise** (a template or a planned exercise): `{ id, item_id, name, body_part, equipment, primary_muscles[], secondary_muscles[], instructions[], image_url?, gif_url?, sets: [ { id, weight, weight_unit?, reps, rpe, rpe_scale? } ] }`. `item_id` is the shared catalogue id (`shared/exercises/exercises.json`: `"0001"`, `"Walking_Outdoor"`, `"Running_Outdoor"`) or `user_exercise_<uuid>` / `custom_activity_<uuid>`. `weight`, `reps`, `rpe` stay **strings** (`"7.5"`) exactly as both apps store them. User-photo filenames (`image_paths`), timers and running timers are **not** carried.
- **Session**: `{ id, diary_date_key, started_at, completed_at, duration_seconds, calories_burned?, exercises: [ { id, item_id, name, target_muscles[], equipment, duration_seconds?, intensity?, sets: [ { id, set_number, weight, weight_unit, reps, rpe, rpe_scale? } ] } ] }`. A session with `calories_burned` is the calculated daily burn snapshot (at most one per day): if a local snapshot for the same `diary_date_key` exists, the incoming one is skipped. Health-sync bookkeeping (`healthSyncVersion`, tombstones, pending ids) is never carried and imported sessions get none.
- Merge: `sessions` and `user_exercises`/`custom_activities` by `id`, `saved_exercise_ids` as a set union, `day_plans` per date (a date already planned on the device is kept), `preferences` replace. The written workout state keeps the platform's own version (**2** on both; any other version is discarded by the store).
- Third-party Health workouts (HKWorkout / Health Connect sessions) are not here; they travel in `health_data`.

## Deliberately outside this section

Food entries and water entries (`food_diary`, lossy but already cross-platform: it keeps ids and merges), meal photos and exercise photos, Health data, medications, Health Records, Coach chats, API keys, Drive backup state.

## Onboarding

"Restore from a backup" on the Welcome step (docs/cloud-backup.md) treats a restored `portable_data` **with a `profile`** exactly like a restored app backup: the profile steps are skipped and onboarding continues with notifications, Health and AI setup. That is what makes an iPhone zip restore a profile on Android and an Android zip restore it on iPhone.

## Test obligations (both platforms)

1. Import `shared/portable/fixtures/portable-sample.json` into an empty store and assert profile, units, preferences, logs and workouts (enum mapping, `birthday` local day, ids lowercased or uppercased per platform, string weights).
2. Round-trip: build native data → export → import into an empty store → equal (by the fields above).
3. Merge: importing twice adds nothing; a same-id or same-second-same-value weight is not duplicated; a second active fast is dropped; a same-day calorie snapshot is not doubled.
4. Refusals: wrong `format`, `format_version` 2, or non-JSON leaves every store untouched.
5. Cross-platform: the plan marks `portable_data` as import when the zip's platform differs and as covered when the same-platform app backup is applied (build the manifest in the test; the fixture's own `platform` value is irrelevant to the importer).
