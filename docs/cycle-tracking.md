# Cycle tracking (Period Tracker)

A local-first menstrual cycle tracker on iOS and Android: period logging, a calendar, calendar-based estimates of the
next period, fertile window and ovulation, daily logs (flow, pain, symptoms, mood, notes), history, trends, reminders
and an opt-in Coach summary.

Contract: `shared/cycle/` (`cycle_config.json`, `coach.json`, `schema.sql`, `test-vectors/`),
`scripts/cycle_reference.py` (the calculations), `scripts/cycle_contract_check.py` (vectors, copies, copy lint, the
generated block below). Change the reference first, run `python3 scripts/cycle_contract_check.py --write`, then port.

## 1. Rules

- **Estimates, never facts.** Every prediction is labelled an estimate ("Estimated period", "Likely fertile window",
  "Estimated ovulation"). Copy never says a period *will* start or that the user *will* ovulate.
- **No diagnosis.** Insights describe the user's own data in neutral words and, for persistent changes, suggest a
  healthcare professional. No condition is ever named as a cause. The contract check bans "PCOS", "endometriosis",
  "infertil…", "contracept…", "guarantee", "will ovulate", "definitely", "abnormal", "disorder", "you have".
- **Not birth control.** The disclaimer (shown on the dashboard, the insights screen and the fertility explainer) says
  estimates are not a form of birth control and cannot plan or prevent pregnancy.
- **Privacy.** Data lives in a separate local SQLite database excluded from iCloud / Android Auto Backup and device
  transfer. No analytics, no logging of cycle values, symptoms or notes (Android `Log` lines carry only the exception
  class), nothing in crash reports. It leaves the device only through (a) Export All Data, (b) the user's own Apple
  Health / Health Connect when sync is on, (c) the Coach when the user turned on Coach access.
- **Opt-in for everyone.** The feature is listed in Browse regardless of profile sex. Nothing is tracked or reminded
  until setup is finished. Settings → Cycle tracking → "Show cycle tracking" off hides every entry point (Browse item,
  Summary "+" entry, Summary card) without deleting data.

## 2. Data model (`shared/cycle/schema.sql`, v1)

| Table | Holds |
|---|---|
| `cycle_periods` | App-logged periods: `id` (`local:<uuid>`), `start_day`, `end_day` (NULL = ongoing), `platform_ids_json`, `sync_state`, `created_ms`, `updated_ms`, `deleted` |
| `cycle_day_logs` | One row per day: `flow`, `pain` 0–10, `pain_locations_json`, `symptoms_json`, `moods_json`, `note`, `platform_ids_json`, `sync_state`, `updated_ms`, `deleted` |
| `cycle_settings` | Single row (`id = 1`): `setup_done`, `cycle_length`, `period_length`, `luteal_length` (NULL = default), `settings_json` (reminders, fertility display, sync, lock-screen detail) |
| `cycle_meta` | `schema_version` |

- Files: Android `ayuvo_cycle.db`; iOS `Application Support/Ayuvo/Cycle/cycle.sqlite` (directory marked
  `isExcludedFromBackup`). Both embed the statements verbatim; parity tests compare them with `schema.sql`.
- Days are local calendar days `yyyy-MM-dd`. No times are stored: a period is a run of whole days, so time zones,
  DST and leap years cannot move it. The engine converts days to integer ordinals (Hinnant's civil algorithm).
- Periods read from HealthKit / Health Connect are **not** copied here; they stay in `health_samples`
  (`menstruation_period`, `menstrual_flow`) and enter the engine as `source: healthkit | health_connect`.
- Edits update the row and bump `updated_ms`. Deletes set `deleted = 1` (and blank `note`), so the platform samples can
  still be removed and imports can merge. "Clear synced health data" never touches this database; only "Delete all
  cycle data" (Settings) and "Delete all app data" do.

## 3. Calculations (`scripts/cycle_reference.py`)

State: `today`, `settings`, `periods` (app + platform), `logs`. Main entry point `snapshot(state)`; the calendar
calls `day_status(state, from, to)` for one visible grid.

1. **Settings** (`effective_settings`): missing values fall back to defaults (28 / 5 / 14 days); values are clamped to
   the setting limits; period length ≤ cycle length − 1.
2. **Normalize** (`normalize_periods`): drop periods starting after today (`future`) or ending before they start; clamp
   ends after today to today; drop platform periods that overlap or touch an app period (`duplicate`, the app wins);
   merge overlapping or adjacent periods (an open member keeps the merged period open); an open period that has run
   longer than `period_max` days is closed at the configured period length (`auto_closed`, excluded from averages).
3. **Cycles**: cycle length = next start − this start; period length = end − start + 1 (inclusive). A cycle counts
   towards averages only when 15–90 days (a longer gap usually means a missed log); a period only when 1–15 days and
   not ongoing or auto-closed.
4. **Stats** over the last 6 valid cycles: median and mean cycle length, sample SD, typical range (min–max, or the
   second-smallest to second-largest from 5 cycles), median period length, variability `high` when range ≥ 8 days or
   SD > 4 days (from 3 cycles).
5. **Prediction** basis:
   - `none`: no period logged → no estimates.
   - `default`: no complete cycle → configured cycle length, range ±3 days.
   - `limited`: 1–2 cycles → median of the observed lengths plus the configured length (half-up), range widened to at
     least ±3 days.
   - `history`: ≥ 3 cycles → median of the recent cycles (half-up), range = typical range.

   Next start = last start + predicted length. When that day is today or earlier and no period was logged, the
   period is **late** by `today − estimate` days and the estimate moves to tomorrow (range starts tomorrow). Three
   future cycles are projected.
6. **Ovulation** = next start − luteal length (default 14), moved to after the period and to at least two days before
   the next period; none when the cycle is too short to fit one. **Fertile window** = ovulation − 5 to ovulation + 1,
   inside the same limits. Completed past cycles outside 15–90 days get no estimate.
7. **Day status**: cycle day (1 = first period day), phase `period` (logged), `predicted_period`, `follicular`,
   `fertile`, `ovulation`, `luteal`, `late` (from the estimated start to today while late) or `unknown` (before the
   first period or after the projection).
8. **Trends** per logged cycle: cycle/period length, max and mean pain, symptom and mood day counts, flow per period
   day; symptom and mood frequency = number of the last 6 cycles in which each was logged (ties in catalogue order);
   flow pattern = mean flow rank per period day.
9. **Insights** (fixed order, neutral templates in the config): recent range or same length, few cycles, high
   variability, late ≥ 7 days, ≥ 2 of the last 3 cycles outside 21–35 days, last period ≥ 8 days, max pain ≥ 7 in the
   last two cycles with pain logged. The last four suggest a healthcare professional.
10. **Reminders** (`snapshot.reminders`): `period_soon` N days (1–3, default 2) before the estimate when not late or
    ongoing; `period_end` when a period is ongoing, on start + period length − 1 + 3 days (or today); `daily_log` when
    enabled.
11. **Editing** helpers: `validate_period` (future start/end, end before start, longer than 15 days, overlap or
    adjacency with another app period → offers the merged range, an open period that is not the latest);
    `apply_period_day` (the "Period day" toggle: insert, extend, bridge two periods, shrink, truncate, delete; marking
    today opens the period).

## 4. Apple Health / Health Connect (read and write)

- **Read:** reuse what the health sync already stores in `health_samples`: `menstruation_period` (Health Connect) and
  runs of `menstrual_flow` days (HealthKit: consecutive flow days, a new run at `HKMetadataKeyMenstrualCycleStart` or
  after a gap of ≥ 2 days). Samples Ayuvo wrote itself are skipped: HealthKit source bundle id = the app's bundle id;
  Health Connect `clientRecordId` starting `ayuvo:cycle:` / `ayuvo:flow:` or data origin = the app package.
  Ovulation tests and basal body temperature are shown read-only on the day sheet when present.
- **Write (when "Sync with Apple Health / Health Connect" is on, default on after permission):**
  - iOS: one `menstrualFlow` sample per period day (value from the day's flow, `unspecified` if none,
    `HKMetadataKeyMenstrualCycleStart = true` on the first day); `intermenstrualBleeding` for spotting; one category
    sample per mapped symptom per day (`HKCategoryValueSeverity.unspecified`). Share types are added to the HealthKit
    authorization request only when the user turns sync on.
  - Android: one `MenstruationPeriodRecord` per period (`clientRecordId = ayuvo:cycle:<id>`) and one
    `MenstruationFlowRecord` per day with flow (`ayuvo:flow:<yyyy-MM-dd>`), written with `clientRecordVersion =
    updated_ms` so writes are idempotent upserts. Needs `WRITE_MENSTRUATION` in the manifest and the permission
    request. Health Connect has no symptom records; symptoms, mood, pain and notes stay local.
- Writes are idempotent. iOS: delete Ayuvo's own samples of the written types for the affected days
  (`HKQuery.predicateForObjects(from: HKSource.default())` + date range), then save the new ones. Android: upsert by
  `clientRecordId`; removed days are deleted by `clientRecordId`. So a restore or re-sync never duplicates samples.
- An ongoing period is written once it ends (its flow days are written as they are logged), so Health never holds a
  half-finished period.
- Writes run after the local save, off the main thread. Written ids go to `platform_ids_json`; an edit deletes or
  upserts them; a delete removes them. Failures set `sync_state = 'failed'` and the item shows "Not synced"; the next
  app start retries. Local data is never lost because of a platform error.
- **Store compliance (owner task):** Google Play requires a health-permissions declaration for Health Connect
  menstruation write; the App Store review notes should mention HealthKit cycle writes.

## 5. Screens and entry points

- **Browse → Period tracker** (title "Period tracker", because the health-data domain row is already "Cycle Tracking"; Browse feature id `cycleTracking`, settings pane id `cycleTracking`, log entry tag `period`; iOS `BrowseRoute.cycle`, Android route `cycle/home`), **Summary "+" → Period** (Body
  section; opens the period sheet, or setup when not set up), **Summary card** "Cycle day N · estimated period in X
  days" (only after setup), and the `menstruation_period` metric detail links to the tracker.
- **Setup:** last period start (or "I don't remember"), typical cycle and period length (skippable), reminders, Health
  sync. Privacy note: stored on this device, excluded from cloud backups, never used for analytics.
- **Dashboard:** cycle ring (period / fertile / ovulation / estimated-period segments, a today marker and the cycle day
  and phase in text), status line with the basis badge, one-tap "Period started" / "Period ended", quick actions
  (Log today, Calendar, History, Insights), variability note, disclaimer.
- **Calendar** (`MonthCalendarView` / `MonthCalendar`, reusable): logged period = filled circle, estimated period =
  dashed circle, fertile = tinted background, ovulation = ring with a dot, today = bold outline, days with a log = small
  dot. Every day has an accessibility label ("12 October, period day 2, heavy flow"); a legend explains the shapes.
  Fertility marks are hidden when "Show fertility estimates" is off.
- **Day sheet:** period-day toggle, flow chips, pain 0–10 and locations, symptoms (grouped), moods, note, read-only
  Health data. Choosing a flow from Light upwards on a non-period day marks it a period day; Spotting does not.
- **History:** cycles newest first (date range, cycle length, period length, flow strip); cycle detail with its day logs,
  edit and delete.
- **Insights:** average cycle, typical range, average period; charts for cycle length (bars with the median line),
  period length, pain per cycle, top symptoms and flow by period day; each chart has an accessibility summary.
- **Settings → Cycle tracking:** show cycle tracking, default lengths, show fertility estimates, Health sync,
  reminders (period soon + days before, period end, daily log + time, show details on lock screen), Coach access,
  delete all cycle data.

## 6. Reminders

- iOS: `UNUserNotificationCenter` requests with identifiers `cycle.*` only (at most 3 pending), re-planned after every
  change, on app open and from the existing background refresh. Android: inexact `setAndAllowWhileIdle` alarms
  (request codes 3001–3003) plus a boot / time-change receiver, channel `cycle` with `VISIBILITY_PRIVATE` and a public
  version.
- Text is discreet by default: title "Ayuvo", body "Time to check your tracker." With "Show details on lock screen"
  on: "Your period may start in about N days." / "Is your period still going? Update your tracker." / "How are you
  feeling today?".

## 7. Export, import and backup

- Export All Data section **`cycle`** (manifest `format` `ayuvo-cycle`, `format_version` 1, counts `periods`,
  `day_logs`): entries `cycle/periods.ndjson`, `cycle/day_logs.ndjson`, `cycle/settings.json`.
  - Rows use the column names; the `*_json` list columns are embedded arrays named `pain_locations`, `symptoms`,
    `moods`, and `settings_json` is the object `settings`. `platform_ids_json` and `sync_state` are device-specific and
    are **not** exported. Deleted rows are exported as tombstones (`deleted: 1`, note blank) so a delete propagates.
  - Import merges by period `id` / log `day`: insert when missing, replace when the incoming `updated_ms` is newer,
    otherwise skip. Settings replace the local row when newer. Imported rows get `sync_state = 'pending'` and are
    written to Health on the next sync when sync is on (writes are idempotent, see §4).
  - Fixture: `shared/cycle/fixtures/cycle-sample/` (4 periods, 6 day logs incl. one tombstone, settings). Both
    platforms import it, check the counts and the snapshot in `manifest.json` → `expected`, and round-trip
    export → import into an empty store.
- Portable preferences: `cycle_enabled` (both), `cycle_show_fertility` (Android writes and reads it; iOS reads the
  value from the `cycle` section's settings instead), `coach_cycle_enabled` (exported, but an imported `true` is
  ignored on both platforms: consent never travels). Settings that shape estimates, reminders and Health sync live in
  `cycle_settings` and travel in the section.
- Excluded from iCloud backup / Auto Backup / device transfer like the health and medications databases.

## 8. Coach

Off by default (`coachCycleEnabled`). Turning it on shows a consent sheet. On iOS the block is also sent only while the
Coach's Health data source is on. When on, the prompt gets the
`shared/cycle/coach.json` header, up to 9 summary lines (recent cycle lengths, median, range, variability, typical
period, cycle day and phase, next estimate with range, late days, top symptoms and moods, max pain per cycle) and the
guardrails. Notes and individual day logs are never sent. When off and the user's message mentions a
`mentions_words` term, the `not_available_line` is added instead.

## 9. Reference feature checklist

| Capability | In Ayuvo |
|---|---|
| Calendar with period, estimates, fertile window, ovulation | Yes |
| Period start/end, per-day flow | Yes |
| Prediction from history with variability | Yes |
| Symptoms, mood, pain, notes | Yes |
| History and statistics charts | Yes |
| Reminders (period soon, period end, daily) | Yes |
| Settings, privacy explanation, discreet notifications | Yes |
| Pill reminders | No: use Medications |
| Pregnancy mode, weight / temperature charts | No (temperature already read from Health shows on the day sheet) |
| App passcode | No (app-wide concern) |

## 10. Generated reference

<!-- BEGIN GENERATED CYCLE -->

| Setting | Value |
|---|---|
| Default cycle / period / luteal length | 28 / 5 / 14 days |
| Valid cycle (counts towards averages) | 15–90 days |
| Valid period | 1–15 days |
| History window | last 6 valid cycles |
| History-based estimate from | 3 valid cycles |
| Trimmed typical range from | 5 valid cycles |
| Fertile window | ovulation −5 to +1 days |
| High variability | range ≥ 8 days or SD > 4.0 days (from 3 cycles) |

| Flow | HealthKit | Health Connect |
|---|---|---|
| Spotting | intermenstrual_bleeding | — |
| Light | light | light |
| Medium | medium | medium |
| Heavy | heavy | heavy |
| Very heavy | heavy | heavy |

| Symptom | Group | HealthKit type |
|---|---|---|
| Cramps | physical | abdominalCramps |
| Headache | physical | headache |
| Backache | physical | lowerBackPain |
| Bloating | physical | bloating |
| Breast tenderness | physical | breastPain |
| Acne | physical | acne |
| Fatigue | physical | fatigue |
| Nausea | physical | nausea |
| Constipation | physical | constipation |
| Diarrhea | physical | diarrhea |
| Pelvic pain | physical | pelvicPain |
| Body ache | physical | generalizedBodyAche |
| Dizziness | other | dizziness |
| Appetite changes | other | appetiteChanges |
| Food cravings | other | — (stays local) |
| Sleep changes | other | sleepChanges |

| Insight | Text |
|---|---|
| `recent_range` | Your last {count} cycles were {low}–{high} days long. |
| `recent_same` | Your last {count} cycles were each {low} days long. |
| `variable` | Your cycles have varied more than usual recently, so estimates may be less precise. |
| `late` | Your period is {days} days later than estimated. Cycles can shift for many reasons; consider talking with a healthcare professional if this keeps happening or worries you. |
| `out_of_range` | {count} of your last {total} cycles were shorter than {low} or longer than {high} days. There are many possible reasons; consider discussing this pattern with a healthcare professional if it continues. |
| `long_period` | Your last period lasted {days} days. If long periods continue or concern you, consider discussing them with a healthcare professional. |
| `severe_pain` | You logged severe pain in recent cycles. Consider discussing it with a healthcare professional, especially if it affects daily life. |
| `few_cycles` | Log a few more periods to personalize your estimates. |

<!-- END GENERATED CYCLE -->
