# Medications — cross-platform contract

Plan: `/Users/macbook/.claude/plans/fluffy-popping-wreath.md`. Shared files live in `shared/medications/`; each platform embeds a verbatim copy of the schema and a parity test compares it with the shared file. The executable reference is `scripts/medications_reference.py`; `scripts/medications_contract_check.py` validates the vectors in `shared/medications/test-vectors/` (`--write` regenerates every `expected` from the reference). Where this prose and the reference disagree, the reference wins and the prose is fixed. Change the shared files first, then both platforms in the same change.

## 1. Rules that never bend
1. A dose is marked `taken` only by an explicit user action (`apply_dose_action('taken')`, `log_prn_dose`, or a Taken notification action). No planner, materializer, lifecycle change, auto-completion or archive merge ever creates a `taken` row; the contract check asserts it over every vector.
2. Ayuvo never advises: no dosing suggestions, no "take it now", no double-dose or catch-up copy, no missed-dose notification. `missed` is a status label, nothing more.
3. History is append-only. Editing a schedule never rewrites past logs; `undo` (deleting the user's own taken/skipped/snoozed row) is the only delete, and a `missed` row cannot be undone.
4. Stored terminal statuses (`taken`, `skipped`, `missed`) always beat derived ones: a clock rollback cannot un-miss or un-take a dose.
5. Medication data is stored on the device and leaves it only through the user's own `ayuvo-medications` archive export, the share sheet, or the user's chosen AI provider when they ask the Coach about it. Copy rule: say "stored on this device", never "never leaves your device".
6. Prescription import is a suggestion pipeline: `frequency_hint` pre-fills a form that the user reviews and confirms before any medication exists. AI or OCR output is never turned into a medication automatically.

## 2. Storage locations, backup and deletion
| | Android | iOS |
|---|---|---|
| Database | `context.getDatabasePath("ayuvo_medications.db")` (`SQLiteOpenHelper`) | `Application Support/Ayuvo/Medications/medications.sqlite` (`sqlite3`) |
| Photos | `filesDir/ayuvo-medications/<medication id>/photo.jpg` (+ `thumb.jpg`, max 320 px) | `Application Support/Ayuvo/Medications/photos/<medication id>.jpg` |
| Backup exclusion | `backup_rules.xml` + `data_extraction_rules.xml` exclude `ayuvo_medications.db*` and `ayuvo-medications/` | The `Ayuvo/Medications/` directory is created with `isExcludedFromBackup`; `CloudBackupPolicy` drops every `medication*` preference key |
| Cloud | Never in `ayuvo-backup.zip`, Auto Backup or Drive | Never in iCloud / CloudKit |
| Portable copy | `ayuvo-medications.json` (§14) inside the Export All Data zip; merged by Import All Data (the Meds menu Export/Import items were removed 2026-09-22) | via `fileExporter` / `fileImporter` |

Connection settings on both platforms: `journal_mode=WAL`, `synchronous=NORMAL`, `busy_timeout=5000`, `foreign_keys=ON`. Delete All Data deletes the database (and its `-wal`/`-shm`/`-journal` siblings), the photo root, cancels every pending medication reminder and clears the `medication*` preferences.

Preference keys (device-local, excluded from cloud backup): `medicationRemindersEnabled` (default `true`), `medicationSnoozeMinutes` (`10` | `30` | `60`, default `10`).

## 3. Enumerations (stored as these exact lowercase strings)
- `form`: `tablet`, `capsule`, `syrup`, `injection`, `cream`, `drops`, `inhaler`, `other`.
- `food_relation`: `before`, `with`, `after`, `anytime`.
- `status`: `active`, `paused`, `completed`, `stopped`. Transitions: active → paused → active; active | paused → stopped; active | paused → completed. `stopped` and `completed` are final (the UI offers "Add again", which copies the medication into a new one).
- `frequency_kind`: `daily` (every day at `times`), `weekly` (`days` at `times`), `interval` (`interval_hours` from `anchor_time`). Spec mapping: once / twice / three / four times daily and "specific times" → `daily`; "every X hours" → `interval`; "specific days" → `weekly`; "as needed" → `is_prn = 1` with **no schedule row**.
- Dose status (`DOSE_STATUSES`): `scheduled`, `due`, `taken`, `skipped`, `missed`, `snoozed`. Only `taken`, `skipped`, `missed`, `snoozed` are stored (`STORED_DOSE_STATUSES`); `scheduled` and `due` are derived. `taken`, `skipped`, `missed` are terminal.
- `dose_unit`: `tablet`, `capsule`, `ml`, `mg`, `g`, `mcg`, `drop`, `puff`, `unit`, `sachet`, `application`, `other`. Default per form (`DEFAULT_UNIT_FOR_FORM`): tablet → tablet, capsule → capsule, drops → drop, inhaler → puff, injection → unit, cream → application, syrup → ml, other → unit.
- `interval_hours` ∈ {1, 2, 3, 4, 6, 8, 12, 24} (must divide 24). Snooze minutes ∈ {10, 30, 60}.
- Constants: `GRACE_MS = 7 200 000` (120 min), `LATE_FIRE_MS = 300 000` (5 min), `ADHERENCE_WINDOW_MS = 7 days`, `IOS_BUDGET = 52`, `MAX_TIMES = 12`, `NAME_MAX = 80`, `STRENGTH_MAX = 40`, `INSTRUCTIONS_MAX = 200`, `NOTE_MAX = 200`, `DOSE_QUANTITY_MAX = 1000`.
- Readers map an unknown enum string to `other` (form, dose_unit) or ignore the row (status, frequency_kind, dose status); they never crash.

## 4. Schema and the versioned-schedule invariants
DDL: `shared/medications/schema.sql` (v1, embedded verbatim; split into statements with the docs/health-records.md §8 rule; do not restate it here). Tables `medications`, `medication_schedules`, `dose_logs`, `medications_meta`; indexes `idx_medications_status`, `idx_medications_record`, `idx_schedules_medication`, `idx_schedules_open`, `idx_dose_logs_occurrence` (UNIQUE on `(schedule_id, scheduled_at_ms)`), `idx_dose_logs_medication`, `idx_dose_logs_scheduled`, `idx_dose_logs_status`. `shared/medications/migrations/NNN_name.sql` files are created with the first migration (none at v1); a fresh install runs `schema.sql` then every migration in order, each in one transaction; `medications_meta.schema_version` and `PRAGMA user_version` hold the version.

Column conventions: ids are lowercase UUIDs; `*_ms` epoch milliseconds UTC; `start_date` / `end_date` are `yyyy-MM-dd` device-local days; `times_json` is a JSON array of `HH:mm` strings (unique, ascending); `days_json` a JSON array of ISO weekdays (1 = Monday … 7 = Sunday), empty unless `weekly`; `is_prn` / `reminder_enabled` are 0/1. In the reference and in archives the parsed forms are named `times` and `days`.

`medications_meta` keys: `schema_version`, `missed_materialized_until_ms` (§7), `last_planned_ms` (§10).

Versioned schedules:
- At most ONE row per medication has `active_until_ms IS NULL` (the open row). The reference reports `multiple_open_schedules` when this is violated.
- Rows are never edited after creation except to set `active_until_ms`/`updated_ms` (closing) or to flip `reminder_enabled` (§12 `set_reminder_enabled`). Every other change closes the open row and inserts a new one with `active_from_ms = now`.
- Creation (a store rule, identical on both platforms): a NEW medication's first row has `active_from_ms = now − GRACE_MS`, so a dose that became due within the last two hours still appears today and can be taken, while earlier slots of the day never existed (no phantom `missed` rows); `start_date` still gates the first calendar day. Edits and resumes use `now` (§12).
- Dose logs reference the row that produced them; occurrences only exist inside `[active_from_ms, active_until_ms)`, so history is stable across edits, pauses and resumes.
- The UNIQUE index blocks two rows for the same occurrence; NULL `schedule_id` (PRN) never collides.

Queries both stores use verbatim (the indexes serve them): list `WHERE status = ? [AND (name LIKE ? OR brand_name LIKE ? OR generic_name LIKE ?)] ORDER BY name COLLATE NOCASE`; open rows `WHERE active_until_ms IS NULL`; versions `WHERE medication_id = ? ORDER BY active_from_ms`; day window `WHERE scheduled_at_ms >= ? AND scheduled_at_ms < ?`; history keyset `WHERE [medication_id = ? AND] (scheduled_at_ms < ? OR (scheduled_at_ms = ? AND id < ?)) ORDER BY scheduled_at_ms DESC, id DESC LIMIT 60`; adherence `WHERE [medication_id = ? AND] scheduled_at_ms >= ? AND schedule_id IS NOT NULL`; pending snoozes `WHERE status = 'snoozed' AND snoozed_until_ms >= ?`; dose upsert `UPDATE dose_logs SET … WHERE schedule_id = ? AND scheduled_at_ms = ?` then INSERT when no row changed (SQLite 3.18 has no UPSERT).

## 5. Time resolution
Reference `local_instant(date, hhmm, time_zone)`: the instant of the local wall-clock time `hhmm` on `date` in the IANA zone, with `fold = 0`:
- Inside a spring-forward gap the pre-transition offset is applied, so the dose lands gap-length later on the wall clock (New York 2026-03-08 `02:30` → 03:30 EDT = 07:30Z). The `slot` keeps the configured string (`02:30`).
- Inside a fall-back overlap the EARLIER (daylight) instant is used, once (New York 2026-11-01 `01:30` → 05:30Z).
- Ports: java.time `LocalDateTime.atZone(ZoneId)` and Foundation `Calendar.date(from: DateComponents)` with `calendar.timeZone` set behave the same; do not "correct" them. `local_date_of(ms, zone)` / `local_hhmm_of(ms, zone)` are the inverse projections; `day_window(date, zone)` = `[00:00 of date, 00:00 of the next date)`.
- The device zone at planning time is the only zone. A zone change re-plans future reminders (§10); stored `scheduled_at_ms` instants never move.

## 6. Occurrences
Reference `expand_occurrences(schedule, medication, window_start_ms, window_end_ms, time_zone)` (vectors `occurrences.json`) → `[{medication_id, schedule_id, scheduled_at_ms, local_date, slot}]` ascending by `(scheduled_at_ms, slot)`.
- Slots (`schedule_slots`): `daily` / `weekly` → `times` (unique, sorted); `interval` → `{(anchor + k·interval_hours) mod 24 h}` for `0 ≤ k < 24 / interval_hours`, sorted (q8h anchored 22:00 → 06:00, 14:00, 22:00); an interval that does not divide 24 yields nothing.
- Local dates from `local_date_of(window_start_ms)` to `local_date_of(window_end_ms)` inclusive; a slot is kept when `start_date ≤ date ≤ end_date` (end inclusive, NULL = open-ended), `weekly` requires `isoweekday ∈ days`, `active_from_ms ≤ t`, `t < active_until_ms` when set, and `window_start_ms ≤ t < window_end_ms`.
- PRN medications never have occurrences. `status` is not checked here; callers filter (`expand_all(..., statuses)` does).
- Two slots that collapse onto one instant (spring-forward) keep only the earlier slot.
- `expand_all(medications, schedules, start, end, zone, statuses)` orders by `(scheduled_at_ms, fold_name(name), medication_id, schedule_id, slot)` where `fold_name` = lowercase, single spaces, trimmed.

## 7. Dose status, grace and missed materialization
Reference `resolve_dose_status(occurrence, log, now_ms)` (vectors `dose_status.json`) → `{status, deadline_ms, is_late}`:
- Stored `taken` / `skipped` / `missed` → that status. `is_late` = `taken_at_ms > scheduled_at_ms + GRACE_MS` (display only).
- Stored `snoozed` → `snoozed` while `now < snoozed_until_ms`, `due` while `now < snoozed_until_ms + GRACE_MS`, else `missed`.
- No log → `scheduled` while `now < scheduled_at_ms`, `due` while `now < scheduled_at_ms + GRACE_MS`, else `missed`.
- `deadline_ms = max(scheduled_at_ms, snoozed_until_ms or 0) + GRACE_MS`. Boundaries: `now == scheduled_at` is due, `now == deadline` is missed.

Reference `materialize_missed(occurrences, logs, medication_by_id, now_ms)` (vectors `missed.json`) → `{ops: [{op: insert | update, row}]}`: an occurrence that resolves to `missed` with no log → insert `{id: new-N, status: missed, taken_at_ms: null, snoozed_until_ms: null, dose_quantity / dose_unit copied from the medication, note: null, created_ms = updated_ms = now}`; a stored `snoozed` row → update to `missed` keeping `snoozed_until_ms`; terminal rows are untouched; occurrences of unknown medications are skipped. Platforms run it on every planner run (app start/foreground, alarm or notification delivery, every dose action, the maintenance job) over `[medications_meta.missed_materialized_until_ms (default now − 30 days), now − GRACE_MS]`, in one transaction, then advance the cursor. The UI always derives status with `resolve_dose_status`, so display is right even before materialization.

## 8. Today timeline
Reference `today_timeline(medications, schedules, logs, now_ms, time_zone)` (vectors `timeline.json`) → `{date, summary, groups, prn}` for the local day of `now_ms`:
- Items = occurrences of ACTIVE medications' schedule rows inside the day (every row, so a version closed at noon still contributes its morning dose) ∪ stored logs of the day that match no occurrence (paused/stopped medicines' logs; PRN logs, `kind = prn`). Item = `{medication_id, schedule_id, scheduled_at_ms, status, is_late, log_id, snoozed_until_ms, dose_quantity, dose_unit, kind: scheduled | prn}`; dose fields come from the log when one exists, else from the medication.
- `groups` = `[{slot: 'HH:mm' of scheduled_at_ms in the zone, items}]`, slots ascending; items ordered by `(scheduled_at_ms, fold_name(name), medication_id, schedule_id, log_id)`.
- `summary = {total, taken, upcoming (= scheduled), due, snoozed, missed, skipped}` over scheduled-kind items only.
- `prn = [{medication_id, today_count, last_taken_ms}]` for active PRN medicines ordered by folded name; `today_count` counts PRN `taken` rows inside the day; `last_taken_ms` is the latest PRN `taken_at_ms` among ALL logs passed, so platforms pass the day's logs plus each PRN medicine's most recent taken row.
- Known limitation (v1, deliberate): logs match occurrences by `(schedule_id, scheduled_at_ms)`. When a dose is logged *ahead of its slot* and the schedule is then versioned the same day (pause → resume, or an edit that keeps the slot), the new row regenerates that slot, so the day shows both the early log and a fresh occurrence. Logging on or after the slot, or versioning before logging, never duplicates. A future version may match by `(medication_id, scheduled_at_ms)` across versions; that changes the reference, the vectors and both ports together.

## 9. History and adherence
History = `dose_logs` ordered by `scheduled_at_ms DESC, id DESC` (keyset paging, 60 per page), optionally for one medication, grouped by local day; rows show time, medicine, dose snapshot and the stored status.

Reference `adherence(occurrences, logs, now_ms, medication_id=None)` (vectors `adherence.json`) → `{taken, expected, percent, has_data}`. Platforms pass the occurrences of every schedule row (any status) and the logs inside `[now − ADHERENCE_WINDOW_MS, now]`. `expected` = occurrences whose resolved status is terminal (so a dose still due or still snoozed does not count yet) plus stored terminal logs of scheduled doses that match no occurrence (older versions); `taken` = those with status `taken`; PRN rows never count; `percent = floor(taken·100 / expected + 0.5)` (18 / 21 → 86, 5 / 8 → 63); `has_data = expected > 0`. Display: "18 / 21 doses taken · 86%".

## 10. Reminder planning
Reference `plan_reminders(medications, schedules, logs, now_ms, horizon_ms, time_zone, budget)` (vectors `reminders.json`) → `{entries, next_fire_ms, truncated}`, `entry = {medication_id, schedule_id, scheduled_at_ms, fire_at_ms, kind: scheduled | snooze}`.
- Scheduled entries: occurrences of ACTIVE medications' OPEN rows with `reminder_enabled = 1` inside `[now − LATE_FIRE_MS, now + horizon_ms)` that have no stored log; `fire_at_ms = max(scheduled_at_ms, now_ms)` (a dose that came due up to 5 minutes ago fires immediately; older ones are left to §7).
- Snooze entries: stored `snoozed` rows of active medications with `snoozed_until_ms ≥ now − LATE_FIRE_MS`, `fire_at_ms = max(snoozed_until_ms, now_ms)`; a user-requested snooze fires even when the row's reminders are off. Because a snoozed occurrence has a log, it never also gets a scheduled entry.
- Order `(fire_at_ms, fold_name(name), medication_id, scheduled_at_ms, schedule_id)`; keep the first `budget` entries (`truncated = true` when cut; `budget = null` = unlimited); `next_fire_ms` = first entry or null.
- Android (`budget = null`): arms ONE alarm at `next_fire_ms` (`setExactAndAllowWhileIdle` when `canScheduleExactAlarms()`, else `setAndAllowWhileIdle`); when it fires, it posts every entry whose `fire_at_ms ≤ now + LATE_FIRE_MS` and re-plans. iOS (`budget = IOS_BUDGET`, horizon 7 days): schedules one `UNCalendarNotificationTrigger` per entry (identifier `medication.<medication_id>.<scheduled_at_ms>` plus `.snooze` for snooze entries) after removing every pending request whose identifier starts with `medication.`.
- Re-plan triggers on both: app start/foreground, every store write, every notification action, boot / package replaced / time-zone change (Android receivers; iOS `NSSystemTimeZoneDidChange` + significant time change), the periodic maintenance job (Android WorkManager 12 h; iOS `BGAppRefreshTask`, best effort). `medications_meta.last_planned_ms` records the last run. iOS keeps only a rolling window; when the app is neither opened nor interacted with for longer than the horizon, reminders pause until it is — the settings footer says so.
- Reminder identity string on both = `medication_id + ':' + scheduled_at_ms` (Android notification tag/id derive from it).

## 11. Dose actions and PRN logging
Reference `apply_dose_action(action, occurrence, existing_log, medication, now_ms, snooze_minutes, taken_at_ms=None, note=None)` (vectors `dose_actions.json`, `op = apply_dose_action`) → `{ok, error, row, op: insert | update | delete}`:
- `taken`: from any state except stored `taken` (`already_taken`); `taken_at_ms` defaults to now and may not be in the future (`taken_at_in_future`). A late take on a stored `missed` row is an update (the row keeps its id).
- `skipped`: from scheduled / due / snoozed / missed; stored `taken` → `already_taken`, stored `skipped` → `already_resolved`.
- `snoozed`: `snooze_minutes ∈ {10, 30, 60}` (`bad_snooze`); only when the derived status is `due` or `snoozed` (`not_due_yet`, `dose_missed`, `already_resolved`); `snoozed_until_ms = now + minutes·60 000`.
- `undo`: deletes the user's own taken/skipped/snoozed row (`nothing_to_undo`, `cannot_undo_missed`).
- `note` ≤ 200 code points (`note_too_long`); inserted rows snapshot `dose_quantity` / `dose_unit` from the medication, updated rows keep their snapshot and their snooze history.

Reference `log_prn_dose(medication, now_ms, taken_at_ms=None, dose_quantity=None, note=None)` (`op = log_prn_dose`): requires `is_prn = 1` (`not_prn`) and `status = active` (`not_active`); the row is `taken` with `schedule_id = null`, `scheduled_at_ms = taken_at_ms`; errors `taken_at_in_future`, `dose_quantity_invalid`, `note_too_long`.

Notification actions map 1:1: Taken → `taken`, Skip → `skipped`, Snooze → `snoozed` with the `medicationSnoozeMinutes` preference; a failed action (e.g. `dose_missed`) leaves the row untouched and the app shows nothing.

## 12. Lifecycle and auto-completion
Reference `lifecycle(action, medication, schedules, now_ms, new_schedule=None)` (vectors `lifecycle.json`) → `{ok, error, medication, schedules, ops}` with `ops ∈ {set_status(status), close_schedule(id), insert_schedule(id), update_schedule(id)}`; `schedules` is every row of the medication after the change, ordered by `active_from_ms`.
- `pause` (active only): close the open row, status `paused`.
- `resume` (paused only): copy the latest generation (the rows whose `active_until_ms` equals the maximum) as `new-N` rows with `active_from_ms = now`, `active_until_ms = null`; status `active`. Without rows only the status changes.
- `stop` / `complete` (active | paused): close the open row, status `stopped` / `completed`.
- `edit_schedule` (active, non-PRN; `prn_has_schedule`, `schedule_required`, `invalid_schedule` + `errors`): validate with §15, close the open row, insert the new row from now. Editing while paused is `invalid_transition` (resume first).
- `set_reminder_enabled` (`new_schedule = {reminder_enabled}`): updates the open row in place (`no_open_schedule` otherwise) — toggling reminders does not version the row.
- Invalid transitions → `invalid_transition`; more than one open row → `multiple_open_schedules`; unknown action → `bad_action`. Every change sets `updated_ms = now`.

Reference `auto_complete(medications, today_local_date)` (vectors `auto_complete.json`) → ids of active | paused medications with `end_date < today` (the end date's own doses still run; the platform then runs `lifecycle('complete')` for each, on the first planner run of the next local day).

## 13. Health Records linking and prescription import
- `medications.related_record_id` holds a Health Records id (no foreign key across databases). The medication detail shows the record row through `RecordsStore`; a deleted record shows "Record no longer available".
- Import: the user picks a record of type `prescription` / `medication_list` (or taps "Add to Medications" on a record's Medications card). Candidates = `record_fields` rows with `field_key = 'medication'` and `state != 'rejected'`; each `value_json` `{name, strength, form, dose, frequency, duration, instructions}` (docs/health-records.md §12/§15) goes through `frequency_hint` (vectors `frequency_hint.json`) into a draft `{name, strength, form, dose_quantity, dose_unit, is_prn, frequency_kind, times, days, interval_hours, anchor_time, duration_days, food_relation, instructions, confidence, notes}`. The user reviews every candidate (checkbox, all fields editable), then confirms; each created medication gets `related_record_id`. A record still processing shows "Ayuvo is still reading this record"; no candidates → "No medicines were detected in this record" + "Add manually".
- `frequency_hint` rules (text lowercased, spaces collapsed, `×` → `x`, `½` → `1/2`): slot tables 1× `08:00`; 2× `08:00, 20:00`; 3× `08:00, 14:00, 20:00`; 4× `08:00, 13:00, 18:00, 22:00`; night-only `22:00`. `a-b-c` / `a-b-c-d` → the non-zero positions of the 3× / 4× table, a uniform non-zero value → `dose_quantity` (`1/2` → 0.5), mixed values → first non-zero + `uneven_doses`. Words: `od|0d|qd|daily|once daily|once a day|every day` → 1×; `bd|bid|twice…` → 2×; `tds|tid|thrice…|three times…` → 3×; `qid|qds|four times…` → 4×; `every N hours|qNh|N hourly` → `interval N` anchored `08:00` when N ∈ INTERVAL_HOURS, else 3× + `interval_rounded`; `once weekly|weekly|once a week` → `weekly`, `days [1]`, 1× + `weekday_defaulted`; `sos|prn|if needed|if required|when required|as needed|as required` (frequency or instructions) → `is_prn`; `hs|qhs|at night|at bedtime|nocte|nightly` → `22:00`; `stat` → 1× with `duration_days 1`. A single daily slot moves to `22:00` when the instructions say bedtime/night. Duration `x|for N days|weeks|months` (weeks·7, months·30). Dose `N tab|cap|puff|drop|sachet|ml|tsp` (`tsp` = 5 ml, `1/2` = 0.5); missing dose → 1 + `dose_defaulted`, except syrup → `dose_quantity null` + `dose_required`. Form words map to §3 forms (ointment/gel/lotion → cream; suspension/solution → syrup; nebulisation/respules → inhaler; sachet/powder/spray → other; unknown → other) with `form_mapped`, missing → other + `form_defaulted`. Food relation from instructions: after food/meals/breakfast/lunch/dinner → `after`; before … / empty stomach → `before`; with food/meals/milk/water → `with`; else `anytime`. `confidence` 0.9 explicit, 0.6 when `interval_rounded` / `weekday_defaulted` / `uneven_doses` applied, 0.3 when no frequency parsed (`frequency_defaulted`); `time_defaulted` is always noted for slot-table times. The records contract is not changed by this feature.

## 14. Archive `ayuvo-medications` v1
Reference `export_archive(snapshot, exported_ms, time_zone, platform, app_version)` / `merge_archive(snapshot, archive, now_ms)` (vectors `archive.json`, `op = export | merge`).
```json
{"format": "ayuvo-medications", "version": 1, "exported_ms": 0, "time_zone": "Asia/Kolkata",
 "app": {"platform": "android", "version": "1.0"},
 "medications": [ …table columns, photo_path always null… ],
 "schedules":   [ …table columns with times / days as arrays… ],
 "dose_logs":   [ …table columns… ]}
```
Arrays are ordered by `id`; files are written with sorted keys, 2-space indent, UTF-8, trailing newline; the file name is `ayuvo-medications.json`. Photos are not exported in v1.

Merge (`{ok, error, ops: [{table, op: insert | update | skip, id, row?, reason?}]}`): `bad_format` / `unsupported_version` reject the whole file. Per row by `id`: absent locally → `insert`; `archive.updated_ms > local.updated_ms` → `update`; else `skip:older`. Invalid rows (bad enum, time, date, status, missing columns) → `skip:invalid`; schedules and logs whose medication (or schedule) is unknown after the earlier passes → `skip:orphan`; a scheduled log whose `(schedule_id, scheduled_at_ms)` is already held by a different local id → `skip:occurrence_conflict`. Unknown keys are dropped; an `updated_ms` in the future is clamped to `now_ms`; a medication update keeps the local `photo_path`. Nothing is ever deleted. The platform runs the ops in one transaction, medications first, then bumps the revision and re-plans.

## 15. Draft validation
Reference `validate_draft(draft)` (vectors `validation.json`) → `[{field, code}]` in field order; `[]` = valid. Codes: `name_required` (trimmed, 1–80), `strength_too_long` (40), `form_invalid`, `dose_quantity_invalid` (> 0, ≤ 1000), `dose_unit_invalid`, `food_relation_invalid`, `start_date_invalid`, `end_date_invalid`, `end_date_before_start`, `prn_has_schedule`, `frequency_required`, `times_required`, `times_invalid` (1–12 valid `HH:mm`, unique, ascending), `days_required`, `days_invalid` (1–7, unique, ascending), `days_not_allowed` (non-weekly), `interval_invalid`, `anchor_required`, `anchor_invalid`, `instructions_too_long` (200), `note_too_long` (200). The same schedule rules gate `lifecycle('edit_schedule')` and archive rows.

## 16. Notification content
Title = medication name plus strength when present ("Metformin 500 mg"). Body = dose + time + food relation, localized per platform ("Take 1 tablet · 8:00 PM · with food"). Exactly three actions, in this order: Taken, Skip, Snooze (Android `NotificationCompat.Action`s; iOS category `medication.dose` with actions `medication.taken`, `medication.skip`, `medication.snooze`). Tapping the notification body opens the Meds segment. Channel / category: Android `medication_reminders` (IMPORTANCE_HIGH); iOS `interruptionLevel = .timeSensitive`, `threadIdentifier = "medications"`, no Focus filter criteria. Never a recommendation, never a missed-dose notification. Medicine names appear in notifications by necessity; lock-screen visibility follows the system setting.

## 17. Safety copy
Fixed disclaimer (English source string, localized on both platforms), shown in the Medications home footer, the medication detail and the history screen:

> Ayuvo helps you track the medicines you choose to add and reminds you when a dose is due. It does not give medical advice. If you miss a dose or are unsure about anything, ask your doctor or pharmacist.

Status labels are single words (Scheduled, Due, Taken, Skipped, Missed, Snoozed) rendered as icon + text, never colour alone. The prescription-import screen carries "Check each medicine against the prescription before saving."

## 18. Vectors and the contract check
`shared/medications/test-vectors/*.json`, envelope `{"format": "ayuvo-medications-vectors", "version": 1, "function": …, "cases": [{"name", "input", "expected", "notes"?}]}`. Files and functions: `occurrences.json` → `expand_occurrences`; `dose_status.json` → `resolve_dose_status`; `missed.json` → `materialize_missed`; `timeline.json` → `today_timeline`; `adherence.json` → `adherence`; `reminders.json` → `plan_reminders`; `dose_actions.json` → `dose_actions` (input `op` = `apply_dose_action` | `log_prn_dose`); `lifecycle.json` → `lifecycle`; `auto_complete.json` → `auto_complete` (output `{medication_ids}`); `frequency_hint.json` → `frequency_hint`; `archive.json` → `archive` (input `op` = `export` | `merge`); `validation.json` → `validate_draft` (output `{errors}`); `coach_tools_payloads.json` → `coach_tools` (input `tool` = `get_medications` | `get_dose_history` | `get_medication_adherence` | `prompt_lines`, §20). `input` is the source of truth; `python3 scripts/medications_contract_check.py --write` regenerates `expected` and without `--write` fails on any drift. Comparison rules for ports: numbers by value, object key order irrelevant, array order significant, `new-N` ids substituted in order, time zones limited to `America/New_York`, `Europe/London`, `Asia/Kolkata`, `UTC`. Each platform runs every file (`MedicationsVectorTests`) with a coverage test that fails when a vector file has no runner, and a schema parity test (`MedicationsSchemaContractTest` / `MedicationsDatabaseTests`) that compares the embedded statements with `schema.sql`.

## 19. Accessibility identifiers
`medications.home`, `medications.summary`, `medications.today.<medication id>.<scheduled_at_ms>` (dose row), `medications.action.taken`, `medications.action.skip`, `medications.action.snooze`, `medications.prn.<medication id>`, `medications.list.<medication id>`, `medications.add`, `medications.menu`, `medications.filter.<active|paused|completed|stopped|all>`, `medications.detail.<section>`, `medications.history.<log id>`, `medications.import.<record id>`, `records.detail.medications.add`, `medications.settings.reminders`, `medications.settings.snooze`, `medications.settings.exact` (Android only). Every icon-only control carries a content description / accessibility label; timeline rows are combined elements.

## 20. Coach tools
Coach reads medications through three tools whose names, descriptions and input schemas live in `shared/medications/coach_tools.json` (`format: "ayuvo-medications-coach-tools"`, version 1) and are advertised **byte-identically** on both platforms (Android `medications/coach/MedicationsCoachTools.kt`, iOS `Medications/Coach/MedicationsCoachTools.swift`); a parity test byte-compares each embedded copy with the shared file, exactly as Health Records does (`docs/health-records.md` §26).

- `get_medications(include_inactive?)` → the medicines, their dose, schedule summary, dates and status. Called first, to learn the `medication_id` values.
- `get_dose_history(medication_id?, from, to, limit?)` → individual doses, newest first, cap 200: the local `date`, `scheduled_at` and `taken_at` as `HH:mm` plus `scheduled_at_ms`, and the resolved status `taken | taken_late | skipped | missed`, or `scheduled | due | snoozed` while the dose is still open. PRN doses are included with `is_prn: true`. A `limit` that is not a whole number falls back to the cap.
- `get_medication_adherence(medication_id?, from, to)` → §9 adherence overall and per medicine: `scheduled` (terminal doses), `taken`, `taken_late`, `skipped`, `missed`, `still_open`, `percent`, `has_data` and `most_missed_time` (the slot with the most missed or skipped doses; ties go to the earlier time, `null` when none). `percent` is the §9 `adherence` value, so the tool and the adherence screen can never disagree. A medicine with no terminal and no open dose in the window is left out. PRN doses never count.

`prompt.mentions_words` is the shared word list that decides whether the user asked about medicines
at all; the "not available" line is only emitted when one of those words appears in the folded
message, so a question about steps never spends tokens explaining that medicines are off.

Gating, consent and the on-device (no-tool) block are `docs/coach.md` §3: the tools are advertised only when at least one non-deleted medicine exists **and** `coachMedicationsEnabled` is true, which is set only by an affirmative act. Payload shapes are built by `scripts/medications_reference.py` and asserted by `shared/medications/test-vectors/coach_tools_payloads.json`. Errors use the `errors.*` strings with placeholders filled in one pass. The guardrails in `prompt.guardrails` are not advisory: Coach never tells the user to start, stop, change, skip or combine a medicine, and never suggests a dose.
