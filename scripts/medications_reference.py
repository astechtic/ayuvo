#!/usr/bin/env python3
"""Ayuvo Medications: executable reference for the shared medication logic.

Contract: docs/medications.md. Where this file and the prose disagree, this file wins and the prose
is fixed. Android (Kotlin, `medications/logic/`) and iOS (Swift, `Medications/Logic/`) port it line
by line and run shared/medications/test-vectors/*.json in their unit tests.

Portability rules (same as scripts/records_reference.py):
  * Python 3 stdlib only. Every function is pure: no clock, locale, randomness or I/O. "Now" and the
    time zone are always explicit inputs (`now_ms`, `time_zone` = IANA id).
  * Regexes use only literals, explicit ASCII classes, (?:...), numbered groups, ? * + {n,m},
    alternation and ^/$ on single-line strings. No flags, no \\d \\w \\s \\b, no lazy quantifiers:
    text is lowercased and space-collapsed before matching.
  * Ids created here are "new-1", "new-2", ... in creation order; ports substitute their own UUIDs
    in the same order.
  * Numbers compare by value in vectors (13 == 13.0); object key order never matters; array order does.
  * Local wall-clock times resolve with `local_instant` (fold=0). A time inside a spring-forward gap
    lands gap-length later on the wall clock (02:30 -> 03:30); a time inside a fall-back overlap is the
    EARLIER instant, once. This equals java.time `LocalDateTime.atZone(zone)` and Foundation
    `Calendar.date(from:)` with the zone set, so ports must not "fix" it.
"""

import datetime as _dt
import json
import math
import re
from zoneinfo import ZoneInfo

# ---------------------------------------------------------------------------------------------
# §3 Enumerations and constants
# ---------------------------------------------------------------------------------------------

FORMS = ("tablet", "capsule", "syrup", "injection", "cream", "drops", "inhaler", "other")
FOOD_RELATIONS = ("before", "with", "after", "anytime")
STATUSES = ("active", "paused", "completed", "stopped")
FREQUENCY_KINDS = ("daily", "weekly", "interval")
DOSE_STATUSES = ("scheduled", "due", "taken", "skipped", "missed", "snoozed")
STORED_DOSE_STATUSES = ("taken", "skipped", "missed", "snoozed")
TERMINAL_DOSE_STATUSES = ("taken", "skipped", "missed")
DOSE_UNITS = ("tablet", "capsule", "ml", "mg", "g", "mcg", "drop", "puff", "unit", "sachet", "application", "other")
DEFAULT_UNIT_FOR_FORM = {
    "tablet": "tablet", "capsule": "capsule", "drops": "drop", "inhaler": "puff", "injection": "unit",
    "cream": "application", "syrup": "ml", "other": "unit",
}
INTERVAL_HOURS = (1, 2, 3, 4, 6, 8, 12, 24)
SNOOZE_MINUTES = (10, 30, 60)

GRACE_MS = 7_200_000          # 120 min: a dose is missed this long after its (snoozed) time
LATE_FIRE_MS = 300_000        # 5 min: a dose that came due this recently still fires "now"
ADHERENCE_WINDOW_MS = 7 * 86_400_000
IOS_BUDGET = 52               # 64 pending notifications - 12 reserved for the rest of the app
MAX_TIMES = 12
NAME_MAX = 80
STRENGTH_MAX = 40
INSTRUCTIONS_MAX = 200
NOTE_MAX = 200
DOSE_QUANTITY_MAX = 1000

# Meal-anchored default slots (docs §13). 1-0-1 = morning / afternoon / night positions of SLOTS_3.
SLOTS_1 = ["08:00"]
SLOTS_2 = ["08:00", "20:00"]
SLOTS_3 = ["08:00", "14:00", "20:00"]
SLOTS_4 = ["08:00", "13:00", "18:00", "22:00"]
SLOT_NIGHT = ["22:00"]
SLOT_TABLES = {1: SLOTS_1, 2: SLOTS_2, 3: SLOTS_3, 4: SLOTS_4}
INTERVAL_ANCHOR = "08:00"
WEEKLY_DEFAULT_DAYS = [1]

ARCHIVE_FORMAT = "ayuvo-medications"
ARCHIVE_VERSION = 1

MEDICATION_COLUMNS = ["id", "name", "generic_name", "brand_name", "strength", "form", "dose_quantity", "dose_unit",
                      "food_relation", "instructions", "start_date", "end_date", "status", "is_prn", "photo_path",
                      "related_record_id", "created_ms", "updated_ms"]
# `times`/`days` are the parsed forms of the times_json / days_json columns (archives carry arrays).
SCHEDULE_COLUMNS = ["id", "medication_id", "frequency_kind", "times", "days", "interval_hours", "anchor_time",
                    "reminder_enabled", "active_from_ms", "active_until_ms", "created_ms", "updated_ms"]
DOSE_LOG_COLUMNS = ["id", "medication_id", "schedule_id", "scheduled_at_ms", "status", "taken_at_ms",
                    "snoozed_until_ms", "dose_quantity", "dose_unit", "note", "created_ms", "updated_ms"]


# ---------------------------------------------------------------------------------------------
# Small helpers
# ---------------------------------------------------------------------------------------------

_RE_HHMM = re.compile("^([01][0-9]|2[0-3]):([0-5][0-9])$")
_RE_DATE = re.compile("^([0-9]{4})-([0-9]{2})-([0-9]{2})$")


class _Ids(object):
    """Deterministic id generator: new-1, new-2, ... per top-level call."""

    def __init__(self):
        self.n = 0

    def next(self):
        self.n += 1
        return "new-%d" % self.n


def _is_int(v):
    return isinstance(v, int) and not isinstance(v, bool)


def _is_number(v):
    return isinstance(v, (int, float)) and not isinstance(v, bool)


def _truthy(v):
    """SQLite integer flags arrive as 0/1 (or true/false from JSON)."""
    return bool(v) and v is not None


def parse_hhmm(s):
    """'HH:mm' -> (hour, minute) or None."""
    if not isinstance(s, str):
        return None
    m = _RE_HHMM.match(s)
    if not m:
        return None
    return int(m.group(1)), int(m.group(2))


def parse_date(s):
    """'yyyy-MM-dd' -> datetime.date or None (calendar-checked)."""
    if not isinstance(s, str):
        return None
    m = _RE_DATE.match(s)
    if not m:
        return None
    try:
        return _dt.date(int(m.group(1)), int(m.group(2)), int(m.group(3)))
    except ValueError:
        return None


def _fmt_date(d):
    return "%04d-%02d-%02d" % (d.year, d.month, d.day)


def _fmt_hhmm(h, m):
    return "%02d:%02d" % (h, m)


def local_instant(date, hhmm, time_zone):
    """Epoch ms of the local wall-clock time `hhmm` on `date` (yyyy-MM-dd) in `time_zone`.

    fold=0 semantics: inside a spring-forward gap the instant is the pre-transition offset applied to
    the wall-clock time, which lands gap-length later on the local clock (02:30 -> 03:30 EDT); inside
    a fall-back overlap the EARLIER (daylight) instant is used. Ports: java.time
    `LocalDateTime.atZone(zone)`, Foundation `Calendar.date(from: components)` with `timeZone` set.
    """
    d = parse_date(date) if isinstance(date, str) else date
    hm = parse_hhmm(hhmm) if isinstance(hhmm, str) else hhmm
    if d is None or hm is None:
        raise ValueError("bad date/time %r %r" % (date, hhmm))
    naive = _dt.datetime(d.year, d.month, d.day, hm[0], hm[1], 0, 0, tzinfo=ZoneInfo(time_zone), fold=0)
    return int(round(naive.timestamp() * 1000))


def local_date_of(ms, time_zone):
    """yyyy-MM-dd of an instant in `time_zone`."""
    dt = _dt.datetime.fromtimestamp(ms / 1000.0, ZoneInfo(time_zone))
    return _fmt_date(dt.date())


def local_hhmm_of(ms, time_zone):
    dt = _dt.datetime.fromtimestamp(ms / 1000.0, ZoneInfo(time_zone))
    return _fmt_hhmm(dt.hour, dt.minute)


def _next_date(date_str):
    return _fmt_date(parse_date(date_str) + _dt.timedelta(days=1))


def day_window(date, time_zone):
    """[start_ms, end_ms) of a local calendar day."""
    return local_instant(date, "00:00", time_zone), local_instant(_next_date(date), "00:00", time_zone)


def fold_name(s):
    """Sort key for medication names: lowercase, single spaces, trimmed."""
    s = (s or "").lower()
    return " ".join(s.split())


def _sorted_unique_times(times):
    out = []
    for t in times or []:
        if parse_hhmm(t) is not None and t not in out:
            out.append(t)
    return sorted(out)


def schedule_slots(schedule):
    """Effective 'HH:mm' slots of a schedule row (docs §6). Interval schedules derive theirs from
    anchor_time + k * interval_hours mod 24 h; an interval that does not divide 24 yields nothing."""
    kind = schedule.get("frequency_kind")
    if kind in ("daily", "weekly"):
        return _sorted_unique_times(schedule.get("times"))
    if kind == "interval":
        n = schedule.get("interval_hours")
        anchor = parse_hhmm(schedule.get("anchor_time"))
        if not _is_int(n) or n not in INTERVAL_HOURS or anchor is None:
            return []
        slots = []
        for k in range(24 // n):
            total = (anchor[0] + k * n) * 60 + anchor[1]
            total %= 24 * 60
            slots.append(_fmt_hhmm(total // 60, total % 60))
        return sorted(set(slots))
    return []


def _log_key(schedule_id, scheduled_at_ms):
    return (schedule_id, scheduled_at_ms)


def _index_logs(logs):
    """{(schedule_id, scheduled_at_ms): log} for logs of scheduled doses (schedule_id not null)."""
    out = {}
    for log in logs or []:
        if log.get("schedule_id") is None:
            continue
        key = _log_key(log["schedule_id"], log["scheduled_at_ms"])
        if key not in out:
            out[key] = log
    return out


def _row(columns, src, **overrides):
    row = {}
    for c in columns:
        row[c] = src.get(c) if c in src else None
    row.update(overrides)
    return row


# ---------------------------------------------------------------------------------------------
# §6 Occurrences
# ---------------------------------------------------------------------------------------------

def expand_occurrences(schedule, medication, window_start_ms, window_end_ms, time_zone):
    """Occurrences of one schedule row inside [window_start_ms, window_end_ms).

    Iterates the local dates from the window start's local date to the window end's local date
    (inclusive), keeps a slot when medication.start_date <= date <= end_date (when set), the weekday
    is in `days` for weekly rows, active_from_ms <= t < active_until_ms (when set) and the window
    contains t. PRN medications never have occurrences. Status is NOT checked (callers filter).
    Two slots that collapse onto one instant (spring-forward gap) keep only the earlier slot.
    Output ascending by (scheduled_at_ms, slot)."""
    if _truthy(medication.get("is_prn")):
        return []
    if not _is_int(window_start_ms) or not _is_int(window_end_ms) or window_end_ms <= window_start_ms:
        return []
    slots = schedule_slots(schedule)
    if not slots:
        return []
    start_date = parse_date(medication.get("start_date"))
    end_date = parse_date(medication.get("end_date")) if medication.get("end_date") else None
    if start_date is None:
        return []
    days = schedule.get("days") or []
    kind = schedule.get("frequency_kind")
    active_from = schedule.get("active_from_ms")
    active_until = schedule.get("active_until_ms")
    first = parse_date(local_date_of(window_start_ms, time_zone))
    last = parse_date(local_date_of(window_end_ms, time_zone))
    out = []
    seen = set()
    d = first
    while d <= last:
        if d >= start_date and (end_date is None or d <= end_date):
            if kind != "weekly" or d.isoweekday() in days:
                date_str = _fmt_date(d)
                for slot in slots:
                    t = local_instant(date_str, slot, time_zone)
                    if _is_int(active_from) and t < active_from:
                        continue
                    if _is_int(active_until) and t >= active_until:
                        continue
                    if t < window_start_ms or t >= window_end_ms:
                        continue
                    if t in seen:
                        continue
                    seen.add(t)
                    out.append({"medication_id": medication.get("id"), "schedule_id": schedule.get("id"),
                                "scheduled_at_ms": t, "local_date": date_str, "slot": slot})
        d = d + _dt.timedelta(days=1)
    out.sort(key=lambda o: (o["scheduled_at_ms"], o["slot"]))
    return out


def expand_all(medications, schedules, window_start_ms, window_end_ms, time_zone, statuses=None):
    """Occurrences of every schedule row whose medication has one of `statuses` (None = any),
    sorted by (scheduled_at_ms, folded name, medication_id, schedule_id, slot)."""
    by_id = dict((m["id"], m) for m in medications)
    out = []
    for s in schedules:
        m = by_id.get(s.get("medication_id"))
        if m is None:
            continue
        if statuses is not None and m.get("status") not in statuses:
            continue
        out.extend(expand_occurrences(s, m, window_start_ms, window_end_ms, time_zone))
    out.sort(key=lambda o: (o["scheduled_at_ms"], fold_name(by_id[o["medication_id"]].get("name")),
                            o["medication_id"], o["schedule_id"], o["slot"]))
    return out


# ---------------------------------------------------------------------------------------------
# §7 Dose status
# ---------------------------------------------------------------------------------------------

def resolve_dose_status(occurrence, log, now_ms):
    """Derived status of one occurrence given its stored log (or None) at `now_ms`.
    Stored terminal statuses always win (a clock rollback cannot un-miss or un-take a dose)."""
    scheduled_at = occurrence["scheduled_at_ms"]
    if log is not None and log.get("status") in TERMINAL_DOSE_STATUSES:
        snoozed = log.get("snoozed_until_ms")
        deadline = max(scheduled_at, snoozed if _is_int(snoozed) else 0) + GRACE_MS
        is_late = False
        if log["status"] == "taken":
            taken_at = log.get("taken_at_ms")
            is_late = _is_int(taken_at) and taken_at > scheduled_at + GRACE_MS
        return {"status": log["status"], "deadline_ms": deadline, "is_late": bool(is_late)}
    if log is not None and log.get("status") == "snoozed":
        until = log.get("snoozed_until_ms")
        if not _is_int(until):
            until = scheduled_at
        deadline = max(scheduled_at, until) + GRACE_MS
        if now_ms < until:
            status = "snoozed"
        elif now_ms < deadline:
            status = "due"
        else:
            status = "missed"
        return {"status": status, "deadline_ms": deadline, "is_late": False}
    deadline = scheduled_at + GRACE_MS
    if now_ms < scheduled_at:
        status = "scheduled"
    elif now_ms < deadline:
        status = "due"
    else:
        status = "missed"
    return {"status": status, "deadline_ms": deadline, "is_late": False}


def materialize_missed(occurrences, logs, medication_by_id, now_ms):
    """Rows to write so that every occurrence that resolves to `missed` has a stored `missed` log.
    Untouched occurrence -> insert; stored `snoozed` -> update (keeps snoozed_until_ms); stored
    taken/skipped/missed -> nothing. Never produces a `taken` row. Occurrences whose medication is
    not in `medication_by_id` are skipped (no dose snapshot available)."""
    ids = _Ids()
    index = _index_logs(logs)
    ops = []
    for occ in occurrences:
        log = index.get(_log_key(occ["schedule_id"], occ["scheduled_at_ms"]))
        if resolve_dose_status(occ, log, now_ms)["status"] != "missed":
            continue
        if log is None:
            med = medication_by_id.get(occ["medication_id"])
            if med is None:
                continue
            ops.append({"op": "insert", "row": {
                "id": ids.next(), "medication_id": occ["medication_id"], "schedule_id": occ["schedule_id"],
                "scheduled_at_ms": occ["scheduled_at_ms"], "status": "missed", "taken_at_ms": None,
                "snoozed_until_ms": None, "dose_quantity": med.get("dose_quantity"),
                "dose_unit": med.get("dose_unit"), "note": None, "created_ms": now_ms, "updated_ms": now_ms}})
        elif log.get("status") == "snoozed":
            row = _row(DOSE_LOG_COLUMNS, log, status="missed", updated_ms=now_ms)
            ops.append({"op": "update", "row": row})
    return {"ops": ops}


# ---------------------------------------------------------------------------------------------
# §8 Today timeline
# ---------------------------------------------------------------------------------------------

def _item_from_log(log, med, occ_scheduled_at, kind, now_ms):
    occ = {"scheduled_at_ms": occ_scheduled_at}
    r = resolve_dose_status(occ, log, now_ms)
    return {"medication_id": log["medication_id"], "schedule_id": log.get("schedule_id"),
            "scheduled_at_ms": occ_scheduled_at, "status": r["status"], "is_late": r["is_late"],
            "log_id": log.get("id"), "snoozed_until_ms": log.get("snoozed_until_ms"),
            "dose_quantity": log.get("dose_quantity"), "dose_unit": log.get("dose_unit"), "kind": kind}


def today_timeline(medications, schedules, logs, now_ms, time_zone):
    """The Today screen model: the local day of `now_ms`.

    items = occurrences of ACTIVE medications' schedule rows inside the day (every row, so closed
    versions still contribute their earlier doses) plus stored logs of the day that match no
    occurrence (paused/stopped medicines' logs, PRN logs = kind 'prn'). Grouped by the 'HH:mm' of
    scheduled_at_ms in `time_zone`, groups ascending, items by folded name then ids.
    summary counts scheduled-kind items only. `prn` lists active PRN medicines (today_count = PRN
    doses inside the day; last_taken_ms = latest PRN taken_at among ALL logs passed, so platforms
    pass the day's logs plus each PRN medicine's most recent taken log)."""
    by_id = dict((m["id"], m) for m in medications)
    date = local_date_of(now_ms, time_zone)
    start, end = day_window(date, time_zone)
    occurrences = expand_all(medications, schedules, start, end, time_zone, statuses=("active",))
    index = _index_logs(logs)
    items = []
    matched = set()
    for occ in occurrences:
        med = by_id[occ["medication_id"]]
        log = index.get(_log_key(occ["schedule_id"], occ["scheduled_at_ms"]))
        r = resolve_dose_status(occ, log, now_ms)
        if log is not None:
            matched.add(id(log))
        items.append({"medication_id": occ["medication_id"], "schedule_id": occ["schedule_id"],
                      "scheduled_at_ms": occ["scheduled_at_ms"], "status": r["status"], "is_late": r["is_late"],
                      "log_id": log.get("id") if log else None,
                      "snoozed_until_ms": log.get("snoozed_until_ms") if log else None,
                      "dose_quantity": log.get("dose_quantity") if log else med.get("dose_quantity"),
                      "dose_unit": log.get("dose_unit") if log else med.get("dose_unit"), "kind": "scheduled"})
    for log in logs or []:
        if id(log) in matched:
            continue
        t = log.get("scheduled_at_ms")
        if not _is_int(t) or t < start or t >= end:
            continue
        if log.get("medication_id") not in by_id:
            continue
        if log.get("status") not in STORED_DOSE_STATUSES:
            continue
        kind = "prn" if log.get("schedule_id") is None else "scheduled"
        items.append(_item_from_log(log, by_id[log["medication_id"]], t, kind, now_ms))
    groups = {}
    for it in items:
        slot = local_hhmm_of(it["scheduled_at_ms"], time_zone)
        groups.setdefault(slot, []).append(it)
    ordered = []
    for slot in sorted(groups):
        lst = groups[slot]
        lst.sort(key=lambda it: (it["scheduled_at_ms"], fold_name(by_id[it["medication_id"]].get("name")),
                                 it["medication_id"], it["schedule_id"] or "", it["log_id"] or ""))
        ordered.append({"slot": slot, "items": lst})
    scheduled_items = [it for it in items if it["kind"] == "scheduled"]
    summary = {"total": len(scheduled_items)}
    for name, status in (("taken", "taken"), ("upcoming", "scheduled"), ("due", "due"), ("snoozed", "snoozed"),
                         ("missed", "missed"), ("skipped", "skipped")):
        summary[name] = sum(1 for it in scheduled_items if it["status"] == status)
    prn = []
    prn_meds = [m for m in medications if _truthy(m.get("is_prn")) and m.get("status") == "active"]
    prn_meds.sort(key=lambda m: (fold_name(m.get("name")), m["id"]))
    for m in prn_meds:
        count = 0
        last = None
        for log in logs or []:
            if log.get("medication_id") != m["id"] or log.get("schedule_id") is not None:
                continue
            if log.get("status") != "taken":
                continue
            taken_at = log.get("taken_at_ms")
            t = log.get("scheduled_at_ms")
            if _is_int(t) and start <= t < end:
                count += 1
            if _is_int(taken_at) and (last is None or taken_at > last):
                last = taken_at
        prn.append({"medication_id": m["id"], "today_count": count, "last_taken_ms": last})
    return {"date": date, "summary": summary, "groups": ordered, "prn": prn}


# ---------------------------------------------------------------------------------------------
# §9 Adherence
# ---------------------------------------------------------------------------------------------

def adherence(occurrences, logs, now_ms, medication_id=None):
    """taken / expected over the occurrences and logs passed (already restricted to the window).
    expected = occurrences whose resolved status is terminal (taken, skipped, missed) plus stored
    terminal logs of scheduled doses that match no occurrence. PRN logs never count.
    percent = floor(taken * 100 / expected + 0.5); has_data = expected > 0."""
    index = _index_logs(logs)
    expected = 0
    taken = 0
    matched = set()
    for occ in occurrences:
        if medication_id is not None and occ.get("medication_id") != medication_id:
            continue
        log = index.get(_log_key(occ["schedule_id"], occ["scheduled_at_ms"]))
        status = resolve_dose_status(occ, log, now_ms)["status"]
        if log is not None:
            matched.add(id(log))
        if status in TERMINAL_DOSE_STATUSES:
            expected += 1
            if status == "taken":
                taken += 1
    for log in logs or []:
        if id(log) in matched or log.get("schedule_id") is None:
            continue
        if medication_id is not None and log.get("medication_id") != medication_id:
            continue
        if log.get("status") in TERMINAL_DOSE_STATUSES:
            expected += 1
            if log["status"] == "taken":
                taken += 1
    percent = int(math.floor(taken * 100.0 / expected + 0.5)) if expected else 0
    return {"taken": taken, "expected": expected, "percent": percent, "has_data": expected > 0}


# ---------------------------------------------------------------------------------------------
# §10 Reminder planning
# ---------------------------------------------------------------------------------------------

def plan_reminders(medications, schedules, logs, now_ms, horizon_ms, time_zone, budget):
    """Reminder entries ascending by fire time, trimmed to `budget` (None = unlimited).
    Scheduled entries: occurrences of ACTIVE medications' OPEN schedule rows with reminder_enabled,
    inside [now - LATE_FIRE_MS, now + horizon), that have no stored log; fire_at = max(t, now).
    Snooze entries: stored `snoozed` logs of active medications with snoozed_until >= now -
    LATE_FIRE_MS; fire_at = max(snoozed_until, now); they replace the scheduled entry of the same
    occurrence (which has a log and is therefore already excluded). Android arms one alarm at
    next_fire_ms; iOS schedules every entry. Identity = medication_id + ':' + scheduled_at_ms."""
    by_id = dict((m["id"], m) for m in medications)
    active = dict((k, v) for k, v in by_id.items() if v.get("status") == "active")
    open_rows = [s for s in schedules if s.get("active_until_ms") is None and _truthy(s.get("reminder_enabled"))
                 and s.get("medication_id") in active]
    index = _index_logs(logs)
    entries = []
    if _is_int(horizon_ms) and horizon_ms > 0:
        occurrences = expand_all(list(active.values()), open_rows, now_ms - LATE_FIRE_MS, now_ms + horizon_ms,
                                 time_zone, statuses=("active",))
        for occ in occurrences:
            if _log_key(occ["schedule_id"], occ["scheduled_at_ms"]) in index:
                continue
            entries.append({"medication_id": occ["medication_id"], "schedule_id": occ["schedule_id"],
                            "scheduled_at_ms": occ["scheduled_at_ms"],
                            "fire_at_ms": max(occ["scheduled_at_ms"], now_ms), "kind": "scheduled"})
    for log in logs or []:
        if log.get("status") != "snoozed" or log.get("medication_id") not in active:
            continue
        until = log.get("snoozed_until_ms")
        if not _is_int(until) or until < now_ms - LATE_FIRE_MS:
            continue
        entries.append({"medication_id": log["medication_id"], "schedule_id": log.get("schedule_id"),
                        "scheduled_at_ms": log["scheduled_at_ms"], "fire_at_ms": max(until, now_ms),
                        "kind": "snooze"})
    entries.sort(key=lambda e: (e["fire_at_ms"], fold_name(by_id[e["medication_id"]].get("name")),
                                e["medication_id"], e["scheduled_at_ms"], e["schedule_id"] or ""))
    truncated = False
    if _is_int(budget) and budget >= 0 and len(entries) > budget:
        entries = entries[:budget]
        truncated = True
    return {"entries": entries, "next_fire_ms": entries[0]["fire_at_ms"] if entries else None,
            "truncated": truncated}


# ---------------------------------------------------------------------------------------------
# §11 Dose actions and PRN logging
# ---------------------------------------------------------------------------------------------

def _fail(error):
    return {"ok": False, "error": error, "row": None, "op": None}


def apply_dose_action(action, occurrence, existing_log, medication, now_ms, snooze_minutes, taken_at_ms=None,
                      note=None):
    """The user's explicit action on one scheduled occurrence. Returns {ok, error, row, op}.
    `taken` is the ONLY way (with log_prn_dose) a `taken` row comes into existence."""
    ids = _Ids()
    if action not in ("taken", "skipped", "snoozed", "undo"):
        return _fail("bad_action")
    if note is not None and (not isinstance(note, str) or len(note) > NOTE_MAX):
        return _fail("note_too_long")
    stored = existing_log.get("status") if existing_log else None
    derived = resolve_dose_status(occurrence, existing_log, now_ms)["status"]

    def base_row(**over):
        if existing_log is not None:
            row = _row(DOSE_LOG_COLUMNS, existing_log, updated_ms=now_ms)
            if note is not None:
                row["note"] = note
        else:
            row = {"id": ids.next(), "medication_id": occurrence["medication_id"],
                   "schedule_id": occurrence["schedule_id"], "scheduled_at_ms": occurrence["scheduled_at_ms"],
                   "status": None, "taken_at_ms": None, "snoozed_until_ms": None,
                   "dose_quantity": medication.get("dose_quantity"), "dose_unit": medication.get("dose_unit"),
                   "note": note, "created_ms": now_ms, "updated_ms": now_ms}
        row.update(over)
        return row

    op = "update" if existing_log is not None else "insert"
    if action == "taken":
        if stored == "taken":
            return _fail("already_taken")
        taken_at = taken_at_ms if _is_int(taken_at_ms) else now_ms
        if taken_at > now_ms:
            return _fail("taken_at_in_future")
        return {"ok": True, "error": None, "row": base_row(status="taken", taken_at_ms=taken_at), "op": op}
    if action == "skipped":
        if stored == "taken":
            return _fail("already_taken")
        if stored == "skipped":
            return _fail("already_resolved")
        return {"ok": True, "error": None, "row": base_row(status="skipped", taken_at_ms=None), "op": op}
    if action == "snoozed":
        if snooze_minutes not in SNOOZE_MINUTES:
            return _fail("bad_snooze")
        if derived == "scheduled":
            return _fail("not_due_yet")
        if derived == "missed":
            return _fail("dose_missed")
        if derived in ("taken", "skipped"):
            return _fail("already_resolved")
        until = now_ms + snooze_minutes * 60_000
        return {"ok": True, "error": None, "row": base_row(status="snoozed", snoozed_until_ms=until), "op": op}
    # undo
    if existing_log is None:
        return _fail("nothing_to_undo")
    if stored == "missed":
        return _fail("cannot_undo_missed")
    return {"ok": True, "error": None, "row": _row(DOSE_LOG_COLUMNS, existing_log), "op": "delete"}


def log_prn_dose(medication, now_ms, taken_at_ms=None, dose_quantity=None, note=None):
    """An as-needed dose: a `taken` row with schedule_id NULL and scheduled_at_ms = taken_at_ms."""
    ids = _Ids()
    if not _truthy(medication.get("is_prn")):
        return {"ok": False, "error": "not_prn", "row": None}
    if medication.get("status") != "active":
        return {"ok": False, "error": "not_active", "row": None}
    taken_at = taken_at_ms if _is_int(taken_at_ms) else now_ms
    if taken_at > now_ms:
        return {"ok": False, "error": "taken_at_in_future", "row": None}
    if dose_quantity is not None and (not _is_number(dose_quantity) or dose_quantity <= 0
                                      or dose_quantity > DOSE_QUANTITY_MAX):
        return {"ok": False, "error": "dose_quantity_invalid", "row": None}
    if note is not None and (not isinstance(note, str) or len(note) > NOTE_MAX):
        return {"ok": False, "error": "note_too_long", "row": None}
    row = {"id": ids.next(), "medication_id": medication["id"], "schedule_id": None, "scheduled_at_ms": taken_at,
           "status": "taken", "taken_at_ms": taken_at, "snoozed_until_ms": None,
           "dose_quantity": dose_quantity if dose_quantity is not None else medication.get("dose_quantity"),
           "dose_unit": medication.get("dose_unit"), "note": note, "created_ms": now_ms, "updated_ms": now_ms}
    return {"ok": True, "error": None, "row": row}


# ---------------------------------------------------------------------------------------------
# §12 Lifecycle
# ---------------------------------------------------------------------------------------------

def _validate_schedule(s, prefix=""):
    """Schedule-row validation shared by validate_draft and lifecycle. Returns [{field, code}]."""
    errs = []
    kind = s.get("frequency_kind")
    times = s.get("times")
    days = s.get("days")
    if kind not in FREQUENCY_KINDS:
        errs.append({"field": prefix + "frequency_kind", "code": "frequency_required"})
        return errs
    if kind in ("daily", "weekly"):
        if not isinstance(times, list) or not times:
            errs.append({"field": prefix + "times", "code": "times_required"})
        else:
            ok = all(parse_hhmm(t) is not None for t in times)
            if not ok or len(times) > MAX_TIMES or len(set(times)) != len(times) or sorted(times) != list(times):
                errs.append({"field": prefix + "times", "code": "times_invalid"})
    if kind == "weekly":
        if not isinstance(days, list) or not days:
            errs.append({"field": prefix + "days", "code": "days_required"})
        elif (not all(_is_int(d) and 1 <= d <= 7 for d in days) or len(set(days)) != len(days)
              or sorted(days) != list(days)):
            errs.append({"field": prefix + "days", "code": "days_invalid"})
    elif days:
        errs.append({"field": prefix + "days", "code": "days_not_allowed"})
    if kind == "interval":
        n = s.get("interval_hours")
        if not _is_int(n) or n not in INTERVAL_HOURS:
            errs.append({"field": prefix + "interval_hours", "code": "interval_invalid"})
        anchor = s.get("anchor_time")
        if anchor is None or anchor == "":
            errs.append({"field": prefix + "anchor_time", "code": "anchor_required"})
        elif parse_hhmm(anchor) is None:
            errs.append({"field": prefix + "anchor_time", "code": "anchor_invalid"})
    return errs


def lifecycle(action, medication, schedules, now_ms, new_schedule=None):
    """Status transitions and schedule versioning. Returns {ok, error, medication, schedules, ops}.
    schedules = every row of this medication after the change; ops describe the writes:
    {op: set_status, status} | {op: close_schedule, id} | {op: insert_schedule, id} | {op: update_schedule, id}."""
    ids = _Ids()
    rows = [_row(SCHEDULE_COLUMNS, s) for s in schedules if s.get("medication_id") == medication.get("id")]
    med = _row(MEDICATION_COLUMNS, medication)
    status = med.get("status")
    open_rows = [r for r in rows if r.get("active_until_ms") is None]

    def fail(error):
        return {"ok": False, "error": error, "medication": med, "schedules": rows, "ops": []}

    if len(open_rows) > 1:
        return fail("multiple_open_schedules")
    ops = []

    def close_open():
        for r in open_rows:
            r["active_until_ms"] = now_ms
            r["updated_ms"] = now_ms
            ops.append({"op": "close_schedule", "id": r["id"]})

    def set_status(new):
        med["status"] = new
        med["updated_ms"] = now_ms
        ops.append({"op": "set_status", "status": new})

    if action == "pause":
        if status != "active":
            return fail("invalid_transition")
        close_open()
        set_status("paused")
    elif action == "resume":
        if status != "paused":
            return fail("invalid_transition")
        closed = [r for r in rows if _is_int(r.get("active_until_ms"))]
        if closed:
            latest = max(r["active_until_ms"] for r in closed)
            for r in [r for r in closed if r["active_until_ms"] == latest]:
                copy = _row(SCHEDULE_COLUMNS, r, id=ids.next(), active_from_ms=now_ms, active_until_ms=None,
                            created_ms=now_ms, updated_ms=now_ms)
                rows.append(copy)
                ops.append({"op": "insert_schedule", "id": copy["id"]})
        set_status("active")
    elif action in ("stop", "complete"):
        if status not in ("active", "paused"):
            return fail("invalid_transition")
        close_open()
        set_status("stopped" if action == "stop" else "completed")
    elif action == "edit_schedule":
        if status != "active":
            return fail("invalid_transition")
        if _truthy(med.get("is_prn")):
            return fail("prn_has_schedule")
        if not isinstance(new_schedule, dict):
            return fail("schedule_required")
        errs = _validate_schedule(new_schedule)
        if errs:
            out = fail("invalid_schedule")
            out["errors"] = errs
            return out
        close_open()
        row = {"id": ids.next(), "medication_id": med["id"], "frequency_kind": new_schedule["frequency_kind"],
               "times": list(new_schedule.get("times") or []) if new_schedule["frequency_kind"] != "interval" else [],
               "days": list(new_schedule.get("days") or []) if new_schedule["frequency_kind"] == "weekly" else [],
               "interval_hours": new_schedule.get("interval_hours") if new_schedule["frequency_kind"] == "interval" else None,
               "anchor_time": new_schedule.get("anchor_time") if new_schedule["frequency_kind"] == "interval" else None,
               "reminder_enabled": 1 if _truthy(new_schedule.get("reminder_enabled", 1)) else 0,
               "active_from_ms": now_ms, "active_until_ms": None, "created_ms": now_ms, "updated_ms": now_ms}
        rows.append(row)
        ops.append({"op": "insert_schedule", "id": row["id"]})
        med["updated_ms"] = now_ms
    elif action == "set_reminder_enabled":
        if not isinstance(new_schedule, dict) or "reminder_enabled" not in new_schedule:
            return fail("schedule_required")
        if not open_rows:
            return fail("no_open_schedule")
        r = open_rows[0]
        r["reminder_enabled"] = 1 if _truthy(new_schedule["reminder_enabled"]) else 0
        r["updated_ms"] = now_ms
        ops.append({"op": "update_schedule", "id": r["id"]})
    else:
        return fail("bad_action")
    rows.sort(key=lambda r: (r.get("active_from_ms") if _is_int(r.get("active_from_ms")) else 0, r["id"]))
    return {"ok": True, "error": None, "medication": med, "schedules": rows, "ops": ops}


def auto_complete(medications, today_local_date):
    """Ids of active/paused medications whose end_date is before today (their end date's own doses
    still run; completion happens on the first planner run of the next local day)."""
    today = parse_date(today_local_date)
    out = []
    for m in medications:
        if m.get("status") not in ("active", "paused"):
            continue
        end = parse_date(m.get("end_date")) if m.get("end_date") else None
        if end is not None and today is not None and end < today:
            out.append(m["id"])
    return out


# ---------------------------------------------------------------------------------------------
# §13 Frequency hint (from the Records pipeline's medication field value_json)
# ---------------------------------------------------------------------------------------------

_FORM_MAP = [
    ("tablet", "tablet"), ("tab", "tablet"), ("capsule", "capsule"), ("cap", "capsule"),
    ("syrup", "syrup"), ("syp", "syrup"), ("syr", "syrup"), ("suspension", "syrup"), ("susp", "syrup"),
    ("solution", "syrup"), ("soln", "syrup"), ("injection", "injection"), ("inj", "injection"),
    ("ointment", "cream"), ("oint", "cream"), ("cream", "cream"), ("gel", "cream"), ("lotion", "cream"),
    ("drops", "drops"), ("drop", "drops"), ("inhaler", "inhaler"), ("nebulisation", "inhaler"),
    ("nebulization", "inhaler"), ("neb", "inhaler"), ("respules", "inhaler"), ("respule", "inhaler"),
    ("sachet", "other"), ("powder", "other"), ("spray", "other"),
]
_RE_TRIPLET = re.compile("^([0-9]+(?:/[0-9]+)?|[0-9]+\\.[0-9]+)-([0-9]+(?:/[0-9]+)?|[0-9]+\\.[0-9]+)-"
                         "([0-9]+(?:/[0-9]+)?|[0-9]+\\.[0-9]+)(?:-([0-9]+(?:/[0-9]+)?|[0-9]+\\.[0-9]+))?$")
_RE_EVERY_H = re.compile("(?:^|[^a-z0-9])(?:every|each) ([0-9]{1,2}) ?(?:hours|hour|hrs|hr|hourly|h)(?:$|[^a-z0-9])")
_RE_QNH = re.compile("(?:^|[^a-z0-9])q([0-9]{1,2}) ?h(?:$|[^a-z0-9])")
_RE_N_HOURLY = re.compile("(?:^|[^a-z0-9])([0-9]{1,2}) ?hourly(?:$|[^a-z0-9])")
_RE_DURATION = re.compile("(?:^|[^a-z0-9])(?:x|for)? ?([0-9]{1,3}) ?(days|day|d|weeks|week|wks|wk|w|months|month|mths|mth|m)"
                          "(?:$|[^a-z0-9])")
_RE_DOSE = re.compile("(?:^|[^a-z0-9])([0-9]+(?:\\.[0-9]+)?|1/2) ?(tablets|tablet|tabs|tab|capsules|capsule|caps|cap|"
                      "puffs|puff|drops|drop|sachets|sachet|teaspoons|teaspoon|tsp|ml)(?:$|[^a-z0-9])")
_ONCE = ("once daily", "once a day", "one time a day", "every day", "everyday", "daily", "od", "0d", "qd", "1 time a day")
_TWICE = ("twice daily", "twice a day", "two times a day", "two times daily", "2 times a day", "bd", "bid")
_THRICE = ("thrice daily", "thrice a day", "three times a day", "three times daily", "3 times a day", "tds", "tid")
_FOUR = ("four times a day", "four times daily", "4 times a day", "qid", "qds")
_WEEKLY = ("once weekly", "once a week", "weekly", "every week")
_PRN = ("sos", "prn", "if needed", "if required", "when required", "when needed", "as needed", "as required",
        "as directed when needed")
_NIGHT = ("hs", "qhs", "at night", "at bedtime", "nocte", "bedtime", "nightly")
_STAT = ("stat",)


def _norm(s):
    if not isinstance(s, str):
        return ""
    s = s.replace("×", "x").replace("½", "1/2").lower()
    return " ".join(s.replace("\t", " ").split())


def _has_phrase(text, phrase):
    """Whole-word phrase match on normalized text (word chars = [a-z0-9/])."""
    start = 0
    while True:
        i = text.find(phrase, start)
        if i < 0:
            return False
        before = text[i - 1] if i > 0 else " "
        after = text[i + len(phrase)] if i + len(phrase) < len(text) else " "
        if before not in "abcdefghijklmnopqrstuvwxyz0123456789/" and after not in "abcdefghijklmnopqrstuvwxyz0123456789/":
            return True
        start = i + 1


def _any_phrase(text, phrases):
    return any(_has_phrase(text, p) for p in phrases)


def _num(tok):
    if "/" in tok:
        a, b = tok.split("/", 1)
        try:
            return float(a) / float(b) if float(b) else 0.0
        except ValueError:
            return 0.0
    try:
        return float(tok)
    except ValueError:
        return 0.0


def _clean_number(x):
    """1.0 -> 1 for JSON friendliness; keeps 0.5."""
    if x is None:
        return None
    return int(x) if float(x).is_integer() else x


def _map_form(form_text):
    f = _norm(form_text).rstrip(".")
    if not f:
        return None, False
    for word, canon in _FORM_MAP:
        if f == word or f.startswith(word):
            return canon, canon != word
    return "other", True


def frequency_hint(value_json):
    """Prefilled Add-medication draft from a Records `medication` field value_json
    {name, strength, form, dose, frequency, duration, instructions}. Every default is listed in
    `notes`; the draft is a suggestion the user reviews before anything is created."""
    v = value_json or {}
    notes = []
    name = (v.get("name") or "").strip()
    strength = (v.get("strength") or "").strip() or None
    instructions_raw = (v.get("instructions") or "").strip() or None
    form, mapped = _map_form(v.get("form"))
    if form is None:
        form = "other"
        notes.append("form_defaulted")
    elif mapped:
        notes.append("form_mapped")
    freq = _norm(v.get("frequency"))
    instr = _norm(v.get("instructions"))
    dur = _norm(v.get("duration"))
    dose_text = _norm(v.get("dose"))

    is_prn = False
    kind = None
    times = []
    days = []
    interval = None
    anchor = None
    quantity = None
    explicit = False
    combined = (freq + " " + instr).strip()

    m = _RE_TRIPLET.match(freq)
    if m:
        parts = [g for g in m.groups() if g is not None]
        nums = [_num(p) for p in parts]
        table = SLOTS_4 if len(nums) == 4 else SLOTS_3
        times = [table[i] for i, n in enumerate(nums) if n > 0]
        nonzero = [n for n in nums if n > 0]
        if nonzero:
            explicit = True
            kind = "daily"
            if len(set(nonzero)) == 1:
                quantity = nonzero[0]
            else:
                quantity = nonzero[0]
                notes.append("uneven_doses")
    if not explicit:
        if _any_phrase(combined, _PRN):
            is_prn = True
            explicit = True
        elif _any_phrase(freq, _FOUR):
            kind, times, explicit = "daily", list(SLOTS_4), True
        elif _any_phrase(freq, _THRICE):
            kind, times, explicit = "daily", list(SLOTS_3), True
        elif _any_phrase(freq, _TWICE):
            kind, times, explicit = "daily", list(SLOTS_2), True
        elif _RE_EVERY_H.search(freq) or _RE_QNH.search(freq) or _RE_N_HOURLY.search(freq):
            mm = _RE_EVERY_H.search(freq) or _RE_QNH.search(freq) or _RE_N_HOURLY.search(freq)
            n = int(mm.group(1))
            explicit = True
            if n in INTERVAL_HOURS:
                kind, interval, anchor = "interval", n, INTERVAL_ANCHOR
            else:
                kind, times = "daily", list(SLOTS_3)
                notes.append("interval_rounded")
        elif _any_phrase(freq, _WEEKLY):
            kind, days, times, explicit = "weekly", list(WEEKLY_DEFAULT_DAYS), list(SLOTS_1), True
            notes.append("weekday_defaulted")
        elif _any_phrase(freq, _NIGHT):
            kind, times, explicit = "daily", list(SLOT_NIGHT), True
        elif _any_phrase(freq, _ONCE):
            kind, times, explicit = "daily", list(SLOTS_1), True
        elif _any_phrase(freq, _STAT):
            kind, times, explicit = "daily", list(SLOTS_1), True
    duration_days = None
    if _any_phrase(freq, _STAT) and not is_prn:
        duration_days = 1
    if not explicit:
        kind, times = "daily", list(SLOTS_1)
        notes.append("frequency_defaulted")
    if kind == "daily" and len(times) == 1 and not _any_phrase(freq, _NIGHT) and _any_phrase(instr, _NIGHT):
        times = list(SLOT_NIGHT)
    if not is_prn and kind != "interval":
        notes.append("time_defaulted")

    md = _RE_DURATION.search(dur)
    if md and duration_days is None:
        n = int(md.group(1))
        unit = md.group(2)
        if unit[0] == "d":
            duration_days = n
        elif unit[0] == "w":
            duration_days = n * 7
        else:
            duration_days = n * 30

    dose_unit = None
    mdose = _RE_DOSE.search(dose_text) or (_RE_DOSE.search(_norm(v.get("strength"))) if form == "syrup" else None)
    if mdose:
        quantity = _num(mdose.group(1))
        u = mdose.group(2)
        if u.startswith("tab"):
            dose_unit = "tablet"
        elif u.startswith("cap"):
            dose_unit = "capsule"
        elif u.startswith("puff"):
            dose_unit = "puff"
        elif u.startswith("drop"):
            dose_unit = "drop"
        elif u.startswith("sachet"):
            dose_unit = "sachet"
        elif u in ("tsp", "teaspoon", "teaspoons"):
            dose_unit = "ml"
            quantity = quantity * 5
        else:
            dose_unit = "ml"
    if dose_unit is None:
        dose_unit = DEFAULT_UNIT_FOR_FORM.get(form, "unit")
    if quantity is None:
        if form == "syrup":
            notes.append("dose_required")
        else:
            quantity = 1
            notes.append("dose_defaulted")

    food = "anytime"
    if _has_phrase(instr, "empty stomach") or re.search("before (?:food|meals|meal|breakfast|lunch|dinner|eating)", instr):
        food = "before"
    elif re.search("after (?:food|meals|meal|breakfast|lunch|dinner|eating)", instr):
        food = "after"
    elif re.search("with (?:food|meals|meal|milk|water)", instr):
        food = "with"

    if is_prn:
        confidence = 0.9
    elif not explicit:
        confidence = 0.3
    elif any(n in notes for n in ("interval_rounded", "weekday_defaulted", "uneven_doses")):
        confidence = 0.6
    else:
        confidence = 0.9
    return {"name": name, "strength": strength, "form": form, "dose_quantity": _clean_number(quantity),
            "dose_unit": dose_unit, "is_prn": is_prn, "frequency_kind": None if is_prn else kind,
            "times": [] if is_prn else times, "days": [] if is_prn else days,
            "interval_hours": None if is_prn else interval, "anchor_time": None if is_prn else anchor,
            "duration_days": duration_days, "food_relation": food, "instructions": instructions_raw,
            "confidence": confidence, "notes": notes}


# ---------------------------------------------------------------------------------------------
# §14 Archive (ayuvo-medications v1)
# ---------------------------------------------------------------------------------------------

def export_archive(snapshot, exported_ms, time_zone, platform, app_version):
    """The portable archive: every row of the three tables, arrays ordered by id, photo_path null."""
    meds = sorted((_row(MEDICATION_COLUMNS, m, photo_path=None) for m in snapshot.get("medications") or []),
                  key=lambda r: r["id"])
    scheds = sorted((_row(SCHEDULE_COLUMNS, s) for s in snapshot.get("schedules") or []), key=lambda r: r["id"])
    logs = sorted((_row(DOSE_LOG_COLUMNS, l) for l in snapshot.get("dose_logs") or []), key=lambda r: r["id"])
    for s in scheds:
        s["times"] = list(s.get("times") or [])
        s["days"] = list(s.get("days") or [])
    return {"format": ARCHIVE_FORMAT, "version": ARCHIVE_VERSION, "exported_ms": exported_ms,
            "time_zone": time_zone, "app": {"platform": platform, "version": app_version},
            "medications": meds, "schedules": scheds, "dose_logs": logs}


def _valid_medication_row(m):
    if not isinstance(m, dict) or not isinstance(m.get("id"), str) or not m["id"]:
        return False
    if not isinstance(m.get("name"), str) or not m["name"].strip():
        return False
    if m.get("form") not in FORMS or m.get("food_relation") not in FOOD_RELATIONS or m.get("status") not in STATUSES:
        return False
    if not _is_number(m.get("dose_quantity")) or m.get("dose_unit") not in DOSE_UNITS:
        return False
    if parse_date(m.get("start_date")) is None:
        return False
    if m.get("end_date") is not None and parse_date(m.get("end_date")) is None:
        return False
    if not _is_int(m.get("created_ms")) or not _is_int(m.get("updated_ms")):
        return False
    return True


def _valid_schedule_row(s):
    if not isinstance(s, dict) or not isinstance(s.get("id"), str) or not s["id"]:
        return False
    if not isinstance(s.get("medication_id"), str) or not _is_int(s.get("active_from_ms")):
        return False
    if s.get("active_until_ms") is not None and not _is_int(s.get("active_until_ms")):
        return False
    if not _is_int(s.get("created_ms")) or not _is_int(s.get("updated_ms")):
        return False
    return not _validate_schedule(s)


def _valid_log_row(l):
    if not isinstance(l, dict) or not isinstance(l.get("id"), str) or not l["id"]:
        return False
    if not isinstance(l.get("medication_id"), str) or not _is_int(l.get("scheduled_at_ms")):
        return False
    if l.get("status") not in STORED_DOSE_STATUSES:
        return False
    if l.get("schedule_id") is not None and not isinstance(l.get("schedule_id"), str):
        return False
    if not _is_number(l.get("dose_quantity")) or not isinstance(l.get("dose_unit"), str):
        return False
    if not _is_int(l.get("created_ms")) or not _is_int(l.get("updated_ms")):
        return False
    return True


def merge_archive(snapshot, archive, now_ms):
    """Merge an archive into the local snapshot: per row by id, absent -> insert, newer updated_ms ->
    update, else skip:older. Nothing is ever deleted. An archive updated_ms in the future is clamped
    to now_ms so a bad clock can never make a row permanently newer than later edits. The caller
    runs the ops in one transaction, medications first."""
    if not isinstance(archive, dict) or archive.get("format") != ARCHIVE_FORMAT:
        return {"ok": False, "error": "bad_format", "ops": []}
    if archive.get("version") != ARCHIVE_VERSION:
        return {"ok": False, "error": "unsupported_version", "ops": []}
    ops = []
    local_meds = dict((m["id"], m) for m in snapshot.get("medications") or [])
    local_scheds = dict((s["id"], s) for s in snapshot.get("schedules") or [])
    local_logs = dict((l["id"], l) for l in snapshot.get("dose_logs") or [])
    occupied = {}
    for l in local_logs.values():
        if l.get("schedule_id") is not None:
            occupied[_log_key(l["schedule_id"], l["scheduled_at_ms"])] = l["id"]
    known_meds = set(local_meds)
    known_scheds = set(local_scheds)

    def decide(table, row, local, columns):
        row = _row(columns, row)
        if _is_int(row.get("updated_ms")) and row["updated_ms"] > now_ms:
            row["updated_ms"] = now_ms
        if local is None:
            ops.append({"table": table, "op": "insert", "id": row["id"], "row": row})
            return True
        if row["updated_ms"] > (local.get("updated_ms") or 0):
            ops.append({"table": table, "op": "update", "id": row["id"], "row": row})
            return True
        ops.append({"table": table, "op": "skip", "id": row["id"], "reason": "older"})
        return False

    for m in archive.get("medications") or []:
        if not _valid_medication_row(m):
            ops.append({"table": "medications", "op": "skip", "id": m.get("id") if isinstance(m, dict) else None,
                        "reason": "invalid"})
            continue
        decide("medications", dict(m, photo_path=None if m["id"] not in local_meds
                                   else local_meds[m["id"]].get("photo_path")), local_meds.get(m["id"]),
               MEDICATION_COLUMNS)
        known_meds.add(m["id"])
    for s in archive.get("schedules") or []:
        if not _valid_schedule_row(s):
            ops.append({"table": "medication_schedules", "op": "skip",
                        "id": s.get("id") if isinstance(s, dict) else None, "reason": "invalid"})
            continue
        if s["medication_id"] not in known_meds:
            ops.append({"table": "medication_schedules", "op": "skip", "id": s["id"], "reason": "orphan"})
            continue
        decide("medication_schedules", s, local_scheds.get(s["id"]), SCHEDULE_COLUMNS)
        known_scheds.add(s["id"])
    for l in archive.get("dose_logs") or []:
        if not _valid_log_row(l):
            ops.append({"table": "dose_logs", "op": "skip", "id": l.get("id") if isinstance(l, dict) else None,
                        "reason": "invalid"})
            continue
        if l["medication_id"] not in known_meds or (l.get("schedule_id") is not None
                                                     and l["schedule_id"] not in known_scheds):
            ops.append({"table": "dose_logs", "op": "skip", "id": l["id"], "reason": "orphan"})
            continue
        if l.get("schedule_id") is not None:
            key = _log_key(l["schedule_id"], l["scheduled_at_ms"])
            holder = occupied.get(key)
            if holder is not None and holder != l["id"]:
                ops.append({"table": "dose_logs", "op": "skip", "id": l["id"], "reason": "occurrence_conflict"})
                continue
        if decide("dose_logs", l, local_logs.get(l["id"]), DOSE_LOG_COLUMNS) and l.get("schedule_id") is not None:
            occupied[_log_key(l["schedule_id"], l["scheduled_at_ms"])] = l["id"]
    return {"ok": True, "error": None, "ops": ops}


# ---------------------------------------------------------------------------------------------
# §15 Draft validation
# ---------------------------------------------------------------------------------------------

def validate_draft(draft):
    """Errors of an Add/Edit form draft as [{field, code}] in field order; [] = valid."""
    errs = []
    d = draft or {}
    name = d.get("name")
    if not isinstance(name, str) or not name.strip() or len(name.strip()) > NAME_MAX:
        errs.append({"field": "name", "code": "name_required"})
    strength = d.get("strength")
    if strength is not None and (not isinstance(strength, str) or len(strength) > STRENGTH_MAX):
        errs.append({"field": "strength", "code": "strength_too_long"})
    if d.get("form") not in FORMS:
        errs.append({"field": "form", "code": "form_invalid"})
    q = d.get("dose_quantity")
    if not _is_number(q) or q <= 0 or q > DOSE_QUANTITY_MAX:
        errs.append({"field": "dose_quantity", "code": "dose_quantity_invalid"})
    if d.get("dose_unit") not in DOSE_UNITS:
        errs.append({"field": "dose_unit", "code": "dose_unit_invalid"})
    if d.get("food_relation") not in FOOD_RELATIONS:
        errs.append({"field": "food_relation", "code": "food_relation_invalid"})
    start = parse_date(d.get("start_date"))
    if start is None:
        errs.append({"field": "start_date", "code": "start_date_invalid"})
    end = None
    if d.get("end_date") is not None:
        end = parse_date(d.get("end_date"))
        if end is None:
            errs.append({"field": "end_date", "code": "end_date_invalid"})
        elif start is not None and end < start:
            errs.append({"field": "end_date", "code": "end_date_before_start"})
    is_prn = _truthy(d.get("is_prn"))
    has_schedule = d.get("frequency_kind") is not None or bool(d.get("times")) or bool(d.get("days")) \
        or d.get("interval_hours") is not None
    if is_prn:
        if has_schedule:
            errs.append({"field": "frequency_kind", "code": "prn_has_schedule"})
    else:
        errs.extend(_validate_schedule(d))
    instructions = d.get("instructions")
    if instructions is not None and (not isinstance(instructions, str) or len(instructions) > INSTRUCTIONS_MAX):
        errs.append({"field": "instructions", "code": "instructions_too_long"})
    note = d.get("note")
    if note is not None and (not isinstance(note, str) or len(note) > NOTE_MAX):
        errs.append({"field": "note", "code": "note_too_long"})
    return errs


# ---------------------------------------------------------------------------------------------
# Vector dispatch
# ---------------------------------------------------------------------------------------------

def run_case(function, inp):
    if function == "expand_occurrences":
        return {"occurrences": expand_occurrences(inp["schedule"], inp["medication"], inp["window_start_ms"],
                                                  inp["window_end_ms"], inp["time_zone"])}
    if function == "resolve_dose_status":
        return resolve_dose_status(inp["occurrence"], inp.get("log"), inp["now_ms"])
    if function == "materialize_missed":
        by_id = dict((m["id"], m) for m in inp["medications"])
        return materialize_missed(inp["occurrences"], inp.get("logs") or [], by_id, inp["now_ms"])
    if function == "today_timeline":
        return today_timeline(inp["medications"], inp.get("schedules") or [], inp.get("logs") or [], inp["now_ms"],
                              inp["time_zone"])
    if function == "adherence":
        return adherence(inp["occurrences"], inp.get("logs") or [], inp["now_ms"], inp.get("medication_id"))
    if function == "plan_reminders":
        return plan_reminders(inp["medications"], inp.get("schedules") or [], inp.get("logs") or [], inp["now_ms"],
                              inp["horizon_ms"], inp["time_zone"], inp.get("budget"))
    if function == "dose_actions":
        op = inp["op"]
        if op == "apply_dose_action":
            return apply_dose_action(inp["action"], inp["occurrence"], inp.get("existing_log"), inp["medication"],
                                     inp["now_ms"], inp.get("snooze_minutes"), inp.get("taken_at_ms"), inp.get("note"))
        if op == "log_prn_dose":
            return log_prn_dose(inp["medication"], inp["now_ms"], inp.get("taken_at_ms"), inp.get("dose_quantity"),
                                inp.get("note"))
        raise ValueError(op)
    if function == "lifecycle":
        return lifecycle(inp["action"], inp["medication"], inp.get("schedules") or [], inp["now_ms"],
                         inp.get("new_schedule"))
    if function == "auto_complete":
        return {"medication_ids": auto_complete(inp["medications"], inp["today"])}
    if function == "frequency_hint":
        return frequency_hint(inp["value_json"])
    if function == "archive":
        op = inp["op"]
        if op == "export":
            return export_archive(inp["snapshot"], inp["exported_ms"], inp["time_zone"], inp["platform"],
                                  inp["app_version"])
        if op == "merge":
            return merge_archive(inp["snapshot"], inp["archive"], inp["now_ms"])
        raise ValueError(op)
    if function == "validate_draft":
        return {"errors": validate_draft(inp["draft"])}
    raise ValueError("unknown function %s" % function)


if __name__ == "__main__":
    import sys
    doc = json.load(sys.stdin)
    print(json.dumps(run_case(doc["function"], doc["input"]), indent=2, sort_keys=True, ensure_ascii=False))
