#!/usr/bin/env python3
"""Ayuvo Metrics: executable reference for the shared chart / Summary logic.

Contract: docs/ui-structure.md. Where this file and the prose disagree, this file wins and the prose
is fixed. Android (Kotlin, `data/metrics/`) and iOS (Swift, `Services/Metrics/`) port it line by line
and run shared/metrics/test-vectors/*.json in their unit tests.

Portability rules (same as scripts/medications_reference.py):
  * Python 3 stdlib only. Every function is pure: no clock, locale or randomness. "Now" and the time
    zone are always explicit inputs (`now_ms`, `time_zone` = IANA id). The only I/O is reading the two
    catalogs once at import (shared/metrics/metric_catalog.json, shared/health/metric_registry.json);
    ports load the same data from their bundled copies.
  * Regexes use only literals, explicit ASCII classes, (?:...), numbered groups, ? * + {n,m},
    alternation and ^/$ on single-line strings.
  * Numbers compare by value in vectors (13 == 13.0); object key order never matters; array order does.
  * Fractional results are rounded with `round3` (half away from zero, 3 decimals). Ports use the same
    rule, never the platform's banker's rounding.
  * Local midnight is `local_instant(date, 0, 0)` with fold=0 (java.time `LocalDate.atStartOfDay(zone)`,
    Foundation `Calendar.startOfDay(for:)` with the zone set). The four vector zones have no DST change
    at midnight, so the rule only matters for "D" hour buckets, which step in real hours (23 or 25 on
    DST days) instead of wall-clock hours.
"""

import datetime as _dt
import json
import math
import os
import re
from zoneinfo import ZoneInfo

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
CATALOG_PATH = os.path.join(ROOT, "shared", "metrics", "metric_catalog.json")
REGISTRY_PATH = os.path.join(ROOT, "shared", "health", "metric_registry.json")

# ---------------------------------------------------------------------------------------------
# Constants
# ---------------------------------------------------------------------------------------------

RANGES = ("D", "W", "M", "6M", "Y")
WEEK_STARTS = ("monday", "sunday")
AGGREGATIONS = ("sum", "avg", "last", "count", "duration")
CHART_KINDS = ("bar", "line", "range")
DAY_BUCKETS = ("hour", "none")
HEADLINE_KINDS = ("TOTAL", "AVERAGE", "LATEST")
RING_STATES = ("value", "no_goal", "no_data")
PIN_SOURCES = ("new", "migrated", "default")
GOAL_SOURCES = ("profile.calories", "profile.protein", "profile.carbs", "profile.fat", "prefs.waterDailyGoalMl",
                "prefs.dailyStepGoal", "profile.goalWeight", "profile.goalBodyFat", "none")
FAV_MAX = 12
DAILY_STEP_GOAL_DEFAULT = 10000
HOUR_MS = 3_600_000
DAY_MS = 86_400_000
SUMMED = ("sum", "duration", "count")

APP_KEY_RE = re.compile("^app:[a-z][a-z_]*$")
HEALTH_ID_RE = re.compile("^[A-Za-z][A-Za-z0-9_.]*$")


def _load(path):
    with open(path, encoding="utf-8") as fh:
        return json.load(fh)


CATALOG = _load(CATALOG_PATH)
REGISTRY = _load(REGISTRY_PATH)
REGISTRY_BY_ID = dict((m["id"], m) for m in REGISTRY["metrics"])
APP_METRICS = dict((m["key"], m) for m in CATALOG["metrics"])
DOMAINS = dict((d["id"], d) for d in CATALOG["domains"])
OVERRIDES = dict((o["id"], o) for o in CATALOG["health"]["overrides"])

# ---------------------------------------------------------------------------------------------
# Time helpers
# ---------------------------------------------------------------------------------------------


def round3(x):
    """Half away from zero, 3 decimals. None passes through."""
    if x is None:
        return None
    sign = -1 if x < 0 else 1
    v = math.floor(abs(x) * 1000 + 0.5) / 1000
    v = sign * v
    if v == int(v):
        return int(v)
    return v


def parse_date(s):
    m = re.match("^([0-9]{4})-([0-9]{2})-([0-9]{2})$", s or "")
    if not m:
        return None
    try:
        return _dt.date(int(m.group(1)), int(m.group(2)), int(m.group(3)))
    except ValueError:
        return None


def fmt_date(d):
    return "%04d-%02d-%02d" % (d.year, d.month, d.day)


def local_instant(d, hour, minute, time_zone):
    """Epoch ms of wall-clock hour:minute on date `d` (fold=0)."""
    naive = _dt.datetime(d.year, d.month, d.day, hour, minute, 0, 0, tzinfo=ZoneInfo(time_zone), fold=0)
    return int(round(naive.timestamp() * 1000))


def local_midnight(d, time_zone):
    return local_instant(d, 0, 0, time_zone)


def local_date_of(ms, time_zone):
    return _dt.datetime.fromtimestamp(ms / 1000.0, ZoneInfo(time_zone)).date()


def local_hour_of(ms, time_zone):
    return _dt.datetime.fromtimestamp(ms / 1000.0, ZoneInfo(time_zone)).hour


def add_months(d, n):
    """Calendar month step with day-of-month clamped to the target month's length."""
    total = d.year * 12 + (d.month - 1) + n
    y, m = divmod(total, 12)
    m += 1
    if m == 12:
        last = 31
    else:
        last = (_dt.date(y, m + 1, 1) - _dt.timedelta(days=1)).day
    return _dt.date(y, m, min(d.day, last))


def week_start_of(d, week_start):
    """First day of the week containing d. week_start is "monday" or "sunday"."""
    if week_start == "sunday":
        offset = d.isoweekday() % 7      # Sunday -> 0, Monday -> 1 ... Saturday -> 6
    else:
        offset = d.weekday()             # Monday -> 0 ... Sunday -> 6
    return d - _dt.timedelta(days=offset)


# ---------------------------------------------------------------------------------------------
# bucket_bounds / step_anchor
# ---------------------------------------------------------------------------------------------


def _interval_dates(range_, anchor, week_start):
    """(first local date, first local date after the interval)."""
    if range_ == "D":
        return anchor, anchor + _dt.timedelta(days=1)
    if range_ == "W":
        s = week_start_of(anchor, week_start)
        return s, s + _dt.timedelta(days=7)
    if range_ == "M":
        s = _dt.date(anchor.year, anchor.month, 1)
        return s, add_months(s, 1)
    if range_ == "6M":
        first = _dt.date(anchor.year, anchor.month, 1)
        return add_months(first, -5), add_months(first, 1)
    if range_ == "Y":
        return _dt.date(anchor.year, 1, 1), _dt.date(anchor.year + 1, 1, 1)
    raise ValueError("bad range %r" % (range_,))


def bucket_bounds(range_, anchor_ms, time_zone, week_start, now_ms):
    """Calendar-aligned chart interval and its buckets.

    D  : local midnight .. next local midnight, one bucket per REAL hour (23 / 24 / 25 buckets);
         label = local wall-clock "HH" of the bucket start.
    W  : the week containing the anchor, first day from `week_start`; 7 day buckets.
    M  : the calendar month; one bucket per day.
    6M : first day of (anchor month - 5) .. first day of the month after the anchor month; weekly buckets
         aligned to `week_start` and clipped to the interval (first and last bucket may be shorter).
    Y  : the calendar year; 12 month buckets.
    Non-D labels are the local yyyy-MM-dd of the (clipped) bucket start.
    can_go_forward = interval end <= now_ms (the next interval has started).
    """
    if week_start not in WEEK_STARTS:
        raise ValueError("bad week_start %r" % (week_start,))
    anchor = local_date_of(anchor_ms, time_zone)
    first, after = _interval_dates(range_, anchor, week_start)
    start_ms = local_midnight(first, time_zone)
    end_ms = local_midnight(after, time_zone)
    buckets = []
    if range_ == "D":
        t = start_ms
        while t < end_ms:
            buckets.append({"start_ms": t, "end_ms": min(t + HOUR_MS, end_ms), "label": "%02d" % local_hour_of(t, time_zone)})
            t += HOUR_MS
    elif range_ in ("W", "M"):
        d = first
        while d < after:
            n = d + _dt.timedelta(days=1)
            buckets.append({"start_ms": local_midnight(d, time_zone), "end_ms": local_midnight(n, time_zone),
                            "label": fmt_date(d)})
            d = n
    elif range_ == "6M":
        d = first
        while d < after:
            n = min(week_start_of(d, week_start) + _dt.timedelta(days=7), after)
            buckets.append({"start_ms": local_midnight(d, time_zone), "end_ms": local_midnight(n, time_zone),
                            "label": fmt_date(d)})
            d = n
    else:
        for m in range(12):
            d = _dt.date(first.year, m + 1, 1)
            n = add_months(d, 1)
            buckets.append({"start_ms": local_midnight(d, time_zone), "end_ms": local_midnight(n, time_zone),
                            "label": fmt_date(d)})
    return {"interval": {"start_ms": start_ms, "end_ms": end_ms}, "buckets": buckets,
            "can_go_forward": end_ms <= now_ms}


def step_anchor(range_, anchor_ms, direction, time_zone, now_ms):
    """Move the anchor one range back (direction -1) or forward (+1).

    D +-1 day, W +-7 days, M +-1 month, 6M +-6 months, Y +-1 year; month steps clamp the day of month
    (Jan 31 + 1 month = Feb 28/29). The result never passes today's local date (it is clamped to today).
    anchor_ms of the result is the local midnight of anchor_date.
    """
    if direction not in (-1, 1):
        raise ValueError("bad direction %r" % (direction,))
    d = local_date_of(anchor_ms, time_zone)
    if range_ == "D":
        r = d + _dt.timedelta(days=direction)
    elif range_ == "W":
        r = d + _dt.timedelta(days=7 * direction)
    elif range_ == "M":
        r = add_months(d, direction)
    elif range_ == "6M":
        r = add_months(d, 6 * direction)
    elif range_ == "Y":
        r = add_months(d, 12 * direction)
    else:
        raise ValueError("bad range %r" % (range_,))
    today = local_date_of(now_ms, time_zone)
    if r > today:
        r = today
    return {"anchor_date": fmt_date(r), "anchor_ms": local_midnight(r, time_zone)}


# ---------------------------------------------------------------------------------------------
# bucket_series / headline / sparkline
# ---------------------------------------------------------------------------------------------


def _clean(entries):
    """Entries with a numeric value, in input order (index kept for `last` ties)."""
    out = []
    for i, e in enumerate(entries):
        v = e.get("value")
        if v is None:
            continue
        out.append((e["t_ms"], i, float(v)))
    return out


def _unit_value(aggregation, v):
    return 1.0 if aggregation == "count" else v


def _latest(items):
    """items: (t_ms, index, value). Latest t_ms wins; ties -> later input index."""
    best = None
    for it in items:
        if best is None or (it[0], it[1]) > (best[0], best[1]):
            best = it
    return best


def _aggregate(items, aggregation, daily_mean, time_zone):
    if not items:
        return None
    if aggregation == "avg":
        return sum(v for _, _, v in items) / len(items)
    if aggregation == "last":
        return _latest(items)[2]
    if daily_mean:
        days = {}
        for t, _, v in items:
            k = local_date_of(t, time_zone)
            days[k] = days.get(k, 0.0) + _unit_value(aggregation, v)
        return sum(days.values()) / len(days)
    return sum(_unit_value(aggregation, v) for _, _, v in items)


def bucket_series(entries, range_, anchor_ms, time_zone, week_start, aggregation):
    """One output row per bucket of bucket_bounds (every bucket; empty -> value null, count 0).

    entries: [{t_ms, value}] - value may be null (ignored). An entry belongs to the bucket with
    start_ms <= t_ms < end_ms; entries outside the interval are ignored.
    sum / duration : bucket total. On 6M and Y: mean of the per-local-day totals over days with data.
    count          : like sum with every entry counting 1 (6M/Y: mean daily count).
    avg            : mean of the entry values in the bucket.
    last           : value of the latest entry (ties: later input index).
    min / max      : min / max of raw entry values for avg and last; null for summed aggregations.
    All values rounded with round3.
    """
    if aggregation not in AGGREGATIONS:
        raise ValueError("bad aggregation %r" % (aggregation,))
    bounds = bucket_bounds(range_, anchor_ms, time_zone, week_start, anchor_ms)
    items = _clean(entries)
    daily_mean = range_ in ("6M", "Y") and aggregation in SUMMED
    out = []
    for b in bounds["buckets"]:
        inside = [it for it in items if b["start_ms"] <= it[0] < b["end_ms"]]
        value = _aggregate(inside, aggregation, daily_mean, time_zone)
        if inside and aggregation in ("avg", "last"):
            lo, hi = min(v for _, _, v in inside), max(v for _, _, v in inside)
        else:
            lo = hi = None
        out.append({"start_ms": b["start_ms"], "end_ms": b["end_ms"], "value": round3(value),
                    "count": len(inside), "min": round3(lo), "max": round3(hi)})
    return out


def headline(entries, range_, anchor_ms, time_zone, week_start, aggregation):
    """The big number above a chart.

    summed (sum/duration/count) on D -> TOTAL of the interval.
    summed on W/M/6M/Y              -> AVERAGE = interval total / days_with_data.
    avg                             -> AVERAGE of all entries in the interval.
    last                            -> LATEST entry; from_ms = to_ms = its t_ms.
    Otherwise from_ms/to_ms are the interval bounds. days_with_data = distinct local dates with an
    entry in the interval. No entries -> value null (kind unchanged).
    """
    bounds = bucket_bounds(range_, anchor_ms, time_zone, week_start, anchor_ms)
    s, e = bounds["interval"]["start_ms"], bounds["interval"]["end_ms"]
    items = [it for it in _clean(entries) if s <= it[0] < e]
    days = len(set(local_date_of(t, time_zone) for t, _, _ in items))
    if aggregation in SUMMED:
        kind = "TOTAL" if range_ == "D" else "AVERAGE"
        total = sum(_unit_value(aggregation, v) for _, _, v in items)
        value = None if not items else (total if kind == "TOTAL" else total / days)
        return {"kind": kind, "value": round3(value), "from_ms": s, "to_ms": e, "days_with_data": days}
    if aggregation == "avg":
        value = None if not items else sum(v for _, _, v in items) / len(items)
        return {"kind": "AVERAGE", "value": round3(value), "from_ms": s, "to_ms": e, "days_with_data": days}
    if aggregation == "last":
        best = _latest(items)
        if best is None:
            return {"kind": "LATEST", "value": None, "from_ms": s, "to_ms": e, "days_with_data": 0}
        return {"kind": "LATEST", "value": round3(best[2]), "from_ms": best[0], "to_ms": best[0],
                "days_with_data": days}
    raise ValueError("bad aggregation %r" % (aggregation,))


def sparkline_7d(entries, now_ms, time_zone, aggregation):
    """Seven daily values, oldest first, ending with today's local date. Missing days -> null.
    Daily value: summed -> day total (count: number of entries); avg -> day mean; last -> latest."""
    today = local_date_of(now_ms, time_zone)
    items = _clean(entries)
    values = []
    for k in range(6, -1, -1):
        d = today - _dt.timedelta(days=k)
        s, e = local_midnight(d, time_zone), local_midnight(d + _dt.timedelta(days=1), time_zone)
        inside = [it for it in items if s <= it[0] < e]
        values.append(round3(_aggregate(inside, aggregation, False, time_zone)))
    present = [v for v in values if v is not None]
    return {"values": values, "min": min(present) if present else None, "max": max(present) if present else None,
            "has_data": bool(present)}


# ---------------------------------------------------------------------------------------------
# Fasting and workouts
# ---------------------------------------------------------------------------------------------


def fasting_hours_per_day(sessions, now_ms, time_zone):
    """Seconds fasted on each local day. sessions: [{started_at_ms, ended_at_ms|null}].
    An active session (ended_at_ms null) runs until now_ms. A session is split across local midnights by
    overlap. Sessions ending at or before their start are ignored. Output ascending by day, only days
    with seconds > 0; seconds = floor(overlap ms / 1000), summed per day."""
    per_day = {}
    for s in sessions:
        start = s["started_at_ms"]
        end = s.get("ended_at_ms")
        if end is None:
            end = now_ms
        if end <= start:
            continue
        d = local_date_of(start, time_zone)
        while True:
            ds = local_midnight(d, time_zone)
            de = local_midnight(d + _dt.timedelta(days=1), time_zone)
            if ds >= end:
                break
            overlap = min(end, de) - max(start, ds)
            if overlap > 0:
                per_day[d] = per_day.get(d, 0) + overlap // 1000
            d = d + _dt.timedelta(days=1)
    return {"days": [{"day": fmt_date(d), "seconds": per_day[d]} for d in sorted(per_day) if per_day[d] > 0]}


def workout_day(session, time_zone):
    d = parse_date(session.get("diary_date") or "")
    if d is None:
        d = local_date_of(session["started_at_ms"], time_zone)
    return d


def workout_stats_per_bucket(sessions, range_, anchor_ms, time_zone, week_start):
    """Raw workout totals per bucket (no daily averaging). A session belongs to the local midnight of its
    day = diary_date (yyyy-MM-dd) or, when missing/invalid, the local date of started_at_ms.
    burn_kcal = sum of non-null calories, null when no session in the bucket has calories."""
    bounds = bucket_bounds(range_, anchor_ms, time_zone, week_start, anchor_ms)
    placed = [(local_midnight(workout_day(s, time_zone), time_zone), s) for s in sessions]
    out = []
    for b in bounds["buckets"]:
        inside = [s for t, s in placed if b["start_ms"] <= t < b["end_ms"]]
        burns = [s["calories"] for s in inside if s.get("calories") is not None]
        out.append({"start_ms": b["start_ms"], "end_ms": b["end_ms"], "count": len(inside),
                    "duration_s": sum(int(s.get("duration_s") or 0) for s in inside),
                    "burn_kcal": sum(burns) if burns else None, "burn_count": len(burns)})
    return out


# ---------------------------------------------------------------------------------------------
# Rings and favourites
# ---------------------------------------------------------------------------------------------


def ring_progress(value, goal):
    """goal null or <= 0 -> no_goal; value null -> no_data; else progress clamp(value/goal, 0, 1)
    (round3), percent floor(value*100/goal + 0.5) (may exceed 100), over = value > goal."""
    if goal is None or goal <= 0:
        return {"state": "no_goal", "progress": None, "percent": None, "over": False}
    if value is None:
        return {"state": "no_data", "progress": None, "percent": None, "over": False}
    p = max(0.0, min(1.0, float(value) / goal))
    return {"state": "value", "progress": round3(p), "percent": int(math.floor(value * 100.0 / goal + 0.5)),
            "over": value > goal}


def default_favourites():
    rows = []
    for m in CATALOG["metrics"]:
        f = m.get("default_favourite") or {}
        if f.get("enabled"):
            rows.append((f["order"], m["key"]))
    for o in CATALOG["health"]["overrides"]:
        f = o.get("default_favourite") or {}
        if f.get("enabled"):
            rows.append((f["order"], o["id"]))
    return [k for _, k in sorted(rows)]


def _valid_pin(key, known_health_ids):
    if key.startswith("app:"):
        return key in APP_METRICS
    return key in known_health_ids


def _parse_pins(raw, known_health_ids, max_):
    out = []
    for part in raw.split(","):
        k = part.strip()
        if not k or k in out or not _valid_pin(k, known_health_ids):
            continue
        out.append(k)
    return out[:max_]


def favourite_pins_migrate(new_raw, legacy_raw, known_health_ids, max_):
    """Favourites list for the Summary. Raw values are comma-separated strings (iOS joins its array).
    1. new_raw not null  -> parse it (trim, drop blanks/unknown keys, dedupe, cap). "" -> [].   source new
    2. legacy_raw not null and blank -> [] (the user had switched the tiles off).        source migrated
    3. legacy_raw not null -> its valid ids, then app: defaults in catalog order until the cap.  migrated
    4. otherwise -> catalog defaults (health ids not in known_health_ids dropped).         source default
    Unknown = an app: key not in the catalog or a health id not in known_health_ids."""
    known = set(known_health_ids)
    if new_raw is not None:
        return {"favourites": _parse_pins(new_raw, known, max_), "source": "new"}
    if legacy_raw is not None:
        if legacy_raw.strip() == "":
            return {"favourites": [], "source": "migrated"}
        pins = _parse_pins(legacy_raw, known, max_)
        for k in default_favourites():
            if len(pins) >= max_:
                break
            if k.startswith("app:") and k not in pins:
                pins.append(k)
        return {"favourites": pins, "source": "migrated"}
    pins = [k for k in default_favourites() if _valid_pin(k, known)][:max_]
    return {"favourites": pins, "source": "default"}


# ---------------------------------------------------------------------------------------------
# resolve_metric
# ---------------------------------------------------------------------------------------------


def resolve_metric(key):
    """Presentation facts for any metric key.
    app:*        -> the catalog metric.
    registry id  -> domain from overrides or category_domains, aggregation/chart from the maps, unit from
                    the registry, goal_source from overrides (else "none").
    anything else-> source "unknown", domain "other", aggregation "last", chart "line", unit "none".
    default_favourite_order is the order when enabled, else null.
    Icons (icon_android / icon_ios) fall back in this order: the metric's own icon (app metrics), else the
    override icon (health ids), else the domain icon; unknown keys get the Other domain icon."""
    if key.startswith("app:"):
        m = APP_METRICS.get(key)
        if m is None:
            return _unknown()
        d = DOMAINS[m["domain"]]
        fav = m.get("default_favourite") or {}
        return {"source": "app", "domain": m["domain"], "colour_hex": d["colour_hex"],
                "colour_hex_dark": d["colour_hex_dark"], "aggregation": m["aggregation"],
                "chart_kind": m["chart_kind"], "unit": m["unit"]["canonical"], "goal_source": m["goal_source"],
                "default_favourite_order": fav.get("order") if fav.get("enabled") else None,
                "browse_hidden": False, "icon_android": m["icon"]["android"], "icon_ios": m["icon"]["ios"]}
    r = REGISTRY_BY_ID.get(key)
    if r is None:
        return _unknown()
    o = OVERRIDES.get(key, {})
    h = CATALOG["health"]
    domain = o.get("domain") or h["category_domains"][r["category"]]
    d = DOMAINS[domain]
    fav = o.get("default_favourite") or {}
    icon = o.get("icon") or d["icon"]
    return {"source": "health", "domain": domain, "colour_hex": d["colour_hex"], "colour_hex_dark": d["colour_hex_dark"],
            "aggregation": h["aggregation_map"][r["aggregation"]], "chart_kind": h["chart_kind_map"][r["aggregation"]],
            "unit": r["unit"], "goal_source": o.get("goal_source", "none"),
            "default_favourite_order": fav.get("order") if fav.get("enabled") else None,
            "browse_hidden": bool(o.get("browse_hidden", False)),
            "icon_android": icon["android"], "icon_ios": icon["ios"]}


def _unknown():
    d = DOMAINS["other"]
    return {"source": "unknown", "domain": "other", "colour_hex": d["colour_hex"], "colour_hex_dark": d["colour_hex_dark"],
            "aggregation": "last", "chart_kind": "line", "unit": "none", "goal_source": "none",
            "default_favourite_order": None, "browse_hidden": False,
            "icon_android": d["icon"]["android"], "icon_ios": d["icon"]["ios"]}


# ---------------------------------------------------------------------------------------------
# Vector dispatch
# ---------------------------------------------------------------------------------------------


def run_case(function, inp):
    if function == "bucket_bounds":
        return bucket_bounds(inp["range"], inp["anchor_ms"], inp["time_zone"], inp["week_start"], inp["now_ms"])
    if function == "step_anchor":
        return step_anchor(inp["range"], inp["anchor_ms"], inp["direction"], inp["time_zone"], inp["now_ms"])
    if function == "bucket_series":
        return {"buckets": bucket_series(inp["entries"], inp["range"], inp["anchor_ms"], inp["time_zone"],
                                         inp["week_start"], inp["aggregation"])}
    if function == "headline":
        return headline(inp["entries"], inp["range"], inp["anchor_ms"], inp["time_zone"], inp["week_start"],
                        inp["aggregation"])
    if function == "sparkline_7d":
        return sparkline_7d(inp["entries"], inp["now_ms"], inp["time_zone"], inp["aggregation"])
    if function == "fasting_hours_per_day":
        return fasting_hours_per_day(inp["sessions"], inp["now_ms"], inp["time_zone"])
    if function == "workout_stats_per_bucket":
        return {"buckets": workout_stats_per_bucket(inp["sessions"], inp["range"], inp["anchor_ms"], inp["time_zone"],
                                                    inp["week_start"])}
    if function == "ring_progress":
        return ring_progress(inp["value"], inp["goal"])
    if function == "favourite_pins_migrate":
        return favourite_pins_migrate(inp["new_raw"], inp["legacy_raw"], inp["known_health_ids"], inp["max"])
    if function == "resolve_metric":
        return resolve_metric(inp["key"])
    raise ValueError("unknown function %r" % (function,))
