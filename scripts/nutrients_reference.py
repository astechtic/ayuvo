#!/usr/bin/env python3
"""Ayuvo Nutrients: executable reference for nutrient reference lines, default goals, unit conversion and
supplement contributions (Python 3 stdlib only).

Contract: docs/nutrients.md. Data: shared/nutrients/nutrient_reference.json. Where this file and the prose
disagree, this file wins and the prose is fixed. iOS (`Nutrients/`) and Android (`nutrients/`) port it line by
line and run shared/nutrients/test-vectors/*.json in their unit tests.

Portability rules (same as scripts/insights_reference.py):
  * rounding is round_to(x, d) = floor(x * 10^d + 0.5) / 10^d, never banker's rounding, and never -0;
  * sums run in input order (food entries, then supplement entries), rounding happens once, at the output;
  * amounts are rounded to AMOUNT_DECIMALS (6) decimals, pct_energy limits to 1 decimal;
  * instants are epoch milliseconds, days are "yyyy-MM-dd" in the IANA zone given as `time_zone`;
  * sorts are stable and use the documented keys only.
"""

import datetime as _dt
import json
import math
import os
import re
from zoneinfo import ZoneInfo

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
REFERENCE_PATH = os.path.join(ROOT, "shared", "nutrients", "nutrient_reference.json")
PROMPT_PATH = os.path.join(ROOT, "shared", "nutrients", "ai_supplement_label.md")

AMOUNT_DECIMALS = 6
LIMIT_DECIMALS = 1
SEXES = ("male", "female")
STYLES = ("target", "limit", "info")
UL_SCOPES = ("all_sources", "supplements_only", "preformed_only", "folic_acid_only")
MASS_FACTORS = {"g": 1000000.0, "mg": 1000.0, "mcg": 1.0}     # mcg per unit
CONVERT_ERRORS = ("unknown_nutrient", "invalid_amount", "unsupported_unit", "iu_not_supported", "form_required",
                  "unknown_form")
LABEL_ERRORS = ("parse_error", "bad_shape")
LABEL_ITEM_ERRORS = ("bad_item", "unknown_nutrient", "duplicate_nutrient", "amount_too_large", "bad_serving") + CONVERT_ERRORS
DAY_RE = re.compile("^([0-9][0-9][0-9][0-9])-([0-9][0-9])-([0-9][0-9])$")
LABEL_RECOMMENDED = "Recommended"
LABEL_LIMIT = "Limit"
LABEL_GOAL = "Your goal"


def load_reference(path=REFERENCE_PATH):
    with open(path, encoding="utf-8") as fh:
        return json.load(fh)


REF = load_reference()
BY_KEY = dict((n["key"], n) for n in REF["nutrients"])
# Nutrients the app's FOOD LOG records (app_tracked: true). The others (app_tracked: false: copper, iodine, thiamin, ...)
# are never recorded by the food log; their food part is always null (day_totals) and their `nutrient:<key>` chart
# counts supplements only. app_tracked never limits supplements: see SUPPLEMENT_KEYS.
TRACKED = dict((n["key"], n) for n in REF["nutrients"] if n["app_tracked"])
# Registry health type id ("dietary_vitamin_d") -> reference nutrient key ("vitamin_d").
BY_HEALTH_TYPE = dict((n["health_type"], n["key"]) for n in REF["nutrients"] if n["health_type"] is not None)
SPORTS = dict((s["key"], s) for s in REF["sports_supplements"])
# Supplement nutrients (medication_nutrients, archive import, AI label items, `nutrient:<key>` charts): EVERY reference
# nutrient (all styles, app_tracked or not) plus the sports supplements, in reference order then sports order.
SUPPLEMENT_KEYS = [n["key"] for n in REF["nutrients"]] + [s["key"] for s in REF["sports_supplements"]]


def nutrient_unit(key):
    """Canonical unit of a supplement nutrient (any reference nutrient or a sports supplement); None for unknown
    keys."""
    if key in BY_KEY:
        return BY_KEY[key]["unit"]
    if key in SPORTS:
        return SPORTS[key]["unit"]
    return None


def food_tracked(key):
    """True when the food log records the key: an app_tracked reference nutrient or a sports supplement (the app's
    OptionalNutrient list includes them). False for app_tracked: false reference nutrients and unknown keys."""
    if key in BY_KEY:
        return bool(BY_KEY[key]["app_tracked"])
    return key in SPORTS


# ---------------------------------------------------------------------------------------------
# Numbers and days
# ---------------------------------------------------------------------------------------------

def round_to(x, decimals):
    if x is None:
        return None
    scale = 10 ** decimals
    v = math.floor(x * scale + 0.5) / scale
    return 0.0 if v == 0 else v


def round_half_up_int(x):
    return int(math.floor(x + 0.5))


def _is_number(v):
    return isinstance(v, (int, float)) and not isinstance(v, bool) and math.isfinite(v)


def local_day_of(ms, time_zone):
    d = _dt.datetime.fromtimestamp(ms / 1000.0, ZoneInfo(time_zone)).date()
    return "%04d-%02d-%02d" % (d.year, d.month, d.day)


def parse_day(s):
    m = DAY_RE.match(s or "")
    if not m:
        raise ValueError("bad day %r" % (s,))
    return _dt.date(int(m.group(1)), int(m.group(2)), int(m.group(3)))


def local_midnight_ms(day, time_zone):
    """Instant of 00:00 (fold 0) of `day` in the zone."""
    d = parse_day(day)
    t = _dt.datetime(d.year, d.month, d.day, 0, 0, tzinfo=ZoneInfo(time_zone))
    return int(t.timestamp() * 1000)


# ---------------------------------------------------------------------------------------------
# Profile -> band, reference lines, default goals
# ---------------------------------------------------------------------------------------------

def band_for_age(age):
    """Age band id. No age (None) or under 19 -> default_band (31-50): the reference models adults only, so a
    younger user sees adult values and the About text says so. Fractional ages are floored."""
    if not _is_number(age):
        return REF["default_band"]
    a = int(math.floor(age))
    if a < 19:
        return REF["default_band"]
    for b in REF["age_bands"]:
        if a >= b["min"] and (b["max"] is None or a <= b["max"]):
            return b["id"]
    return REF["default_band"]


def _sex(profile):
    s = (profile or {}).get("sex")
    return s if s in SEXES else None


def _by_sex(table, band, sex, pick_high):
    """Value of a {male: {band: v}, female: {band: v}} table. Unknown sex: the higher (recommended) or the
    lower (upper limit) of the two sexes."""
    if sex is not None:
        return float(table[sex][band])
    m, f = float(table["male"][band]), float(table["female"][band])
    if pick_high:
        return m if m >= f else f
    return m if m <= f else f


def _limit_value(limit, calorie_goal):
    if limit is None:
        return None
    if limit["kind"] == "fixed":
        return float(limit["value"])
    if limit["kind"] == "pct_energy":
        if not _is_number(calorie_goal) or calorie_goal <= 0:
            return None
        return round_to(calorie_goal * limit["pct"] / 100.0 / limit["kcal_per_unit"], LIMIT_DECIMALS)
    raise ValueError("bad limit kind %r" % (limit["kind"],))


def reference_lines(key, profile, custom_goal=None):
    """Chart lines for one nutrient.
    profile = {age?, sex? "male"|"female"|null, calorie_goal?}. custom_goal = the user's own goal or None; a goal
    that is not a number > 0 is ignored. The custom goal replaces the Recommended line (target style and sports
    supplements) or the Limit line (limit and info styles) and that line's label becomes "Your goal"; the table
    values stay available as reference_recommended / reference_limit for the About section."""
    profile = profile or {}
    band = band_for_age(profile.get("age"))
    sex = _sex(profile)
    goal = float(custom_goal) if _is_number(custom_goal) and custom_goal > 0 else None
    base = {"key": key, "band": band, "sex": sex, "style": None, "unit": None, "recommended": None, "upper_limit": None,
            "limit": None, "recommended_label": LABEL_RECOMMENDED, "limit_label": LABEL_LIMIT, "upper_limit_scope": None,
            "reference_recommended": None, "reference_limit": None, "recommended_kind": None, "source_ids": [],
            "error": None}
    if key in SPORTS:
        base.update({"style": "target", "unit": SPORTS[key]["unit"]})
        if goal is not None:
            base.update({"recommended": goal, "recommended_label": LABEL_GOAL})
        return base
    n = BY_KEY.get(key)
    if n is None:
        base["error"] = "unknown_nutrient"
        return base
    rec = _by_sex(n["recommended"], band, sex, True) if n["recommended"] else None
    ul = _by_sex(n["upper_limit"], band, sex, False) if n["upper_limit"] else None
    lim = _limit_value(n["limit"], profile.get("calorie_goal"))
    base.update({"style": n["style"], "unit": n["unit"], "recommended": rec, "upper_limit": ul, "limit": lim,
                 "upper_limit_scope": n["upper_limit"]["scope"] if n["upper_limit"] else None,
                 "reference_recommended": rec, "reference_limit": lim,
                 "recommended_kind": n["recommended"]["kind"] if n["recommended"] else None,
                 "source_ids": list(n["source_ids"])})
    if goal is not None:
        if n["style"] == "target":
            base.update({"recommended": goal, "recommended_label": LABEL_GOAL})
        else:
            base.update({"limit": goal, "limit_label": LABEL_GOAL})
    return base


def default_goal(key, profile):
    """The app's default goal: the Recommended value for target style, the Limit for limit style, None for info
    style, sports supplements and unknown keys."""
    n = BY_KEY.get(key)
    if n is None or n["style"] == "info":
        return None
    r = reference_lines(key, profile)
    return r["recommended"] if n["style"] == "target" else r["limit"]


def default_goal_int(value):
    """Apps store integer goals: round half up, but never below 1 when the exact value is > 0 (omega-3 AI 1.1 g
    -> 1, vitamin B12 2.4 mcg -> 2). None stays None. Chart lines always use the exact value."""
    if value is None:
        return None
    i = round_half_up_int(value)
    return 1 if value > 0 and i < 1 else i


# ---------------------------------------------------------------------------------------------
# Unit conversion
# ---------------------------------------------------------------------------------------------

def normalize_unit(unit):
    """'g' | 'mg' | 'mcg' | 'iu' | None. Trimmed and lower-cased; ug / µg (U+00B5) / μg (U+03BC) -> mcg."""
    if not isinstance(unit, str):
        return None
    u = unit.strip().lower()
    u = REF["unit_aliases"].get(u, u)
    if u in MASS_FACTORS or u == "iu":
        return u
    return None


def _fail(code):
    return {"ok": False, "amount": None, "unit": None, "error": code}


def convert_amount(value, unit, key, form=None):
    """Amount in the nutrient's canonical unit: {ok, amount, unit, error}.
    Mass units: value * mcg_per[unit] / mcg_per[canonical]. A `form` listed in the nutrient's `mass_forms`
    (vitamin A carotenoids, folic acid) then divides by that factor: 2 mcg supplemental beta-carotene = 1 mcg RAE,
    0.6 mcg folic acid = 1 mcg DFE. Other forms are ignored for mass units.
    IU: vitamin D value * mcg_per_iu; vitamin A / E need a form from iu.forms (form_required, unknown_form);
    any other nutrient -> iu_not_supported. Result rounded to 6 decimals. Every reference nutrient (app_tracked or
    not: copper, iodine, thiamin, ...) and every sports supplement converts; other keys -> unknown_nutrient.
    Vitamin A in a mass unit with form null or "retinol" (retinol, retinyl acetate / palmitate) is 1:1 mcg RAE."""
    unit_c = nutrient_unit(key)
    if unit_c is None:
        return _fail("unknown_nutrient")
    if not _is_number(value) or value <= 0:
        return _fail("invalid_amount")
    u = normalize_unit(unit)
    if u is None:
        return _fail("unsupported_unit")
    n = BY_KEY.get(key)
    if u == "iu":
        iu = n["iu"] if n else None
        if iu is None:
            return _fail("iu_not_supported")
        if "forms" in iu:
            if form is None or form == "":
                return _fail("form_required")
            if form not in iu["forms"]:
                return _fail("unknown_form")
            amount = value * iu["forms"][form]
        else:
            amount = value * iu["mcg_per_iu"]
        amount = amount * MASS_FACTORS[iu["unit"]] / MASS_FACTORS[unit_c]
    else:
        amount = value * MASS_FACTORS[u] / MASS_FACTORS[unit_c]
        mf = (n or {}).get("mass_forms") or {}
        if form in mf:
            amount = amount / mf[form]
    return {"ok": True, "amount": round_to(amount, AMOUNT_DECIMALS), "unit": unit_c, "error": None}


# ---------------------------------------------------------------------------------------------
# Supplement contributions and day totals
# ---------------------------------------------------------------------------------------------

def supplement_entries(medication_nutrients, dose_logs):
    """[{t_ms, nutrient_key, value, medication_id}] for every TAKEN dose with a taken_at_ms: value =
    dose_quantity (null -> 1) x amount_per_unit, one entry per nutrient of the medication. Skipped, missed and
    snoozed doses add nothing. Sorted by (t_ms, nutrient_key, medication_id), stable."""
    per_med = {}
    for row in medication_nutrients or []:
        per_med.setdefault(row["medication_id"], []).append(row)
    out = []
    for log in dose_logs or []:
        if log.get("status") != "taken" or not isinstance(log.get("taken_at_ms"), int) or isinstance(log.get("taken_at_ms"), bool):
            continue
        q = log.get("dose_quantity")
        q = 1.0 if q is None else float(q)
        for row in per_med.get(log["medication_id"], []):
            out.append({"t_ms": log["taken_at_ms"], "nutrient_key": row["nutrient_key"],
                        "value": round_to(q * row["amount_per_unit"], AMOUNT_DECIMALS),
                        "medication_id": log["medication_id"]})
    out.sort(key=lambda e: (e["t_ms"], e["nutrient_key"], e["medication_id"]))
    return out


DAY_MS = 86400000


def supplement_interval_days(schedule):
    """Whole days one dose of a schedule covers (docs/intake-metrics.md §3): weekly on n distinct days -> 7 / n
    (half-up, at least 1), an interval of >= 48 hours -> hours / 24 (half-up), anything else (daily, shorter
    intervals, no schedule) -> 1. `schedule`: {frequency_kind: "daily"|"weekly"|"interval", days, interval_hours}."""
    if not schedule:
        return 1
    kind = schedule.get("frequency_kind")
    if kind == "weekly":
        n = len(set(schedule.get("days") or []))
        return 1 if n <= 0 else max(1, int(math.floor(7.0 / n + 0.5)))
    if kind == "interval":
        h = schedule.get("interval_hours") or 0
        return int(math.floor(h / 24.0 + 0.5)) if h >= 48 else 1
    return 1


def spread_supplement_entries(entries, intervals):
    """`supplement_entries` averaged over each medication's dosing interval: an entry of a medication whose
    interval n > 1 becomes n entries of value / n at t_ms + k days (k = 0..n-1), so a weekly 60,000 IU vitamin D dose
    counts 1/7 on each day it covers and the total is kept. Sorted by (t_ms, nutrient_key, medication_id), stable."""
    out = []
    for e in entries or []:
        n = (intervals or {}).get(e["medication_id"], 1)
        if n <= 1:
            out.append(dict(e))
            continue
        for k in range(n):
            out.append({"t_ms": e["t_ms"] + k * DAY_MS, "nutrient_key": e["nutrient_key"], "value": e["value"] / n,
                        "medication_id": e["medication_id"]})
    out.sort(key=lambda x: (x["t_ms"], x["nutrient_key"], x["medication_id"]))
    return out


def day_totals(food_entries, supplements, day, time_zone):
    """{key: {food, supplements, total}} for the local `day`. Keys = every key named by a food entry of the day
    (even with a null value) or by a supplement entry of the day, sorted. food = sum of the non-null food values,
    null when there are none; supplements likewise; total = food + supplements treating a null part as absent,
    null only when both parts are null. Sums in input order, rounded to 6 decimals at the end.
    Food values of app_tracked: false reference nutrients (copper, iodine, ...) are ignored and do not name a key:
    the food log does not record them, so their food part is always null (never 0) and total = supplements."""
    acc = {}
    for e in food_entries or []:
        if local_day_of(e["t_ms"], time_zone) != day:
            continue
        for k, v in (e.get("nutrients") or {}).items():
            if k in BY_KEY and not BY_KEY[k]["app_tracked"]:
                continue
            a = acc.setdefault(k, [None, None])
            if _is_number(v):
                a[0] = v if a[0] is None else a[0] + v
    for s in supplements or []:
        if local_day_of(s["t_ms"], time_zone) != day:
            continue
        a = acc.setdefault(s["nutrient_key"], [None, None])
        if _is_number(s.get("value")):
            a[1] = s["value"] if a[1] is None else a[1] + s["value"]
    out = {}
    for k in sorted(acc):
        f, p = acc[k]
        total = None if f is None and p is None else (0.0 if f is None else f) + (0.0 if p is None else p)
        out[k] = {"food": round_to(f, AMOUNT_DECIMALS), "supplements": round_to(p, AMOUNT_DECIMALS),
                  "total": round_to(total, AMOUNT_DECIMALS)}
    return out


def logged_day_average(entries, logged_days, interval, time_zone, key=None):
    """Average per logged day over [start_ms, end_ms): {average, logged_days}.
    entries = [{t_ms, value|null}] for ONE nutrient (food and supplement entries together); logged_days = local
    days with any food entry or any taken dose (from the caller). A day counts when its local midnight lies in
    the interval; days of non-null entries inside the interval are added to the caller's list. average =
    interval total / logged day count, null when no entry in the interval has a value. Rounded to 6 decimals.
    `key` (optional) names the nutrient: for an app_tracked: false reference nutrient the caller's logged_days are
    ignored, so the logged days are exactly the days with a taken dose of that nutrient (its supplement entries)."""
    start, end = interval["start_ms"], interval["end_ms"]
    if key in BY_KEY and not BY_KEY[key]["app_tracked"]:
        logged_days = []
    days = set()
    for d in logged_days or []:
        m = local_midnight_ms(d, time_zone)
        if start <= m < end:
            days.add(d)
    total, seen = 0.0, False
    for e in entries or []:
        if not (start <= e["t_ms"] < end) or not _is_number(e.get("value")):
            continue
        total += e["value"]
        seen = True
        days.add(local_day_of(e["t_ms"], time_zone))
    n = len(days)
    if not seen or n == 0:
        return {"average": None, "logged_days": n}
    return {"average": round_to(total / n, AMOUNT_DECIMALS), "logged_days": n}


# ---------------------------------------------------------------------------------------------
# AI supplement label output
# ---------------------------------------------------------------------------------------------

def extract_json_object(text):
    """First balanced {...} of the text (string and escape aware) after stripping a ``` fence; None if absent."""
    if not isinstance(text, str):
        return None
    t = text.strip()
    if t.startswith("```"):
        nl = t.find("\n")
        t = t[nl + 1:] if nl >= 0 else ""
        if t.rstrip().endswith("```"):
            t = t.rstrip()[:-3]
    start = t.find("{")
    if start < 0:
        return None
    depth, in_str, esc = 0, False, False
    for i in range(start, len(t)):
        c = t[i]
        if in_str:
            if esc:
                esc = False
            elif c == "\\":
                esc = True
            elif c == "\"":
                in_str = False
        elif c == "\"":
            in_str = True
        elif c == "{":
            depth += 1
        elif c == "}":
            depth -= 1
            if depth == 0:
                return t[start:i + 1]
    return None


def parse_label_output(text):
    """Validate a model answer for the supplement-label prompt.
    -> {ok, error, serving_units, items: [{key, amount, unit, form}], rejected: [{index, code}]}.
    `serving_units` (optional, default 1, must be a number > 0) is how many tablets / capsules / ml / servings the
    printed amounts cover; each amount is divided by it after conversion. Each item needs a known key (any reference
    nutrient, app_tracked or not, or a sports supplement; anything else is unknown_nutrient), a number amount > 0 and
    a unit convert_amount accepts; a repeated key keeps the first. Converted
    amounts above amount_per_unit_max[unit] are rejected (amount_too_large). Nothing here is saved: the app shows
    the items for the user to edit and confirm."""
    raw = extract_json_object(text)
    if raw is None:
        return {"ok": False, "error": "parse_error", "serving_units": None, "items": [], "rejected": []}
    try:
        doc = json.loads(raw)
    except ValueError:
        return {"ok": False, "error": "parse_error", "serving_units": None, "items": [], "rejected": []}
    if not isinstance(doc, dict) or not isinstance(doc.get("items"), list):
        return {"ok": False, "error": "bad_shape", "serving_units": None, "items": [], "rejected": []}
    serving = doc.get("serving_units", 1)
    if serving is None:
        serving = 1
    if not _is_number(serving) or serving <= 0:
        return {"ok": True, "error": None, "serving_units": None, "items": [],
                "rejected": [{"index": i, "code": "bad_serving"} for i in range(len(doc["items"]))]}
    items, rejected, seen = [], [], set()
    for i, it in enumerate(doc["items"]):
        if not isinstance(it, dict) or not isinstance(it.get("key"), str):
            rejected.append({"index": i, "code": "bad_item"})
            continue
        form = it.get("form")
        if form is not None and not isinstance(form, str):
            rejected.append({"index": i, "code": "bad_item"})
            continue
        key = it["key"].strip()
        if nutrient_unit(key) is None:
            rejected.append({"index": i, "code": "unknown_nutrient"})
            continue
        if key in seen:
            rejected.append({"index": i, "code": "duplicate_nutrient"})
            continue
        c = convert_amount(it.get("amount"), it.get("unit"), key, form)
        if not c["ok"]:
            rejected.append({"index": i, "code": c["error"]})
            continue
        amount = round_to(c["amount"] / serving, AMOUNT_DECIMALS)
        if amount <= 0:
            rejected.append({"index": i, "code": "invalid_amount"})
            continue
        if amount > REF["amount_per_unit_max"][c["unit"]]:
            rejected.append({"index": i, "code": "amount_too_large"})
            continue
        seen.add(key)
        items.append({"key": key, "amount": amount, "unit": c["unit"], "form": form})
    return {"ok": True, "error": None, "serving_units": serving, "items": items, "rejected": rejected}


def load_prompts(path=PROMPT_PATH):
    """{cloud, local, user_photo, user_text} from the fenced blocks under the matching headings."""
    text = open(path, encoding="utf-8").read()
    out = {}
    for name, heading in (("cloud", "## System prompt (cloud)"), ("user_photo", "## User template (label photo)"),
                          ("user_text", "## User template (name and strength)"),
                          ("local", "## System prompt (compact, on-device)")):
        i = text.index(heading)
        a = text.index("```\n", i) + 4
        b = text.index("\n```", a)
        out[name] = text[a:b]
    return out


# ---------------------------------------------------------------------------------------------
# Vector dispatch
# ---------------------------------------------------------------------------------------------

def run_case(function, inp):
    if function == "reference_lines":
        return reference_lines(inp["key"], inp.get("profile"), inp.get("custom_goal"))
    if function == "default_goal":
        v = default_goal(inp["key"], inp.get("profile"))
        return {"value": v, "value_int": default_goal_int(v)}
    if function == "convert_amount":
        return convert_amount(inp.get("value"), inp.get("unit"), inp["key"], inp.get("form"))
    if function == "supplement_entries":
        return {"entries": supplement_entries(inp.get("medication_nutrients"), inp.get("dose_logs"))}
    if function == "interval_days":
        return {"days": supplement_interval_days(inp.get("schedule"))}
    if function == "spread_supplements":
        return {"entries": spread_supplement_entries(inp.get("entries"), inp.get("intervals"))}
    if function == "day_totals":
        return {"totals": day_totals(inp.get("food_entries"), inp.get("supplement_entries"), inp["day"],
                                     inp["time_zone"])}
    if function == "logged_day_average":
        return logged_day_average(inp.get("entries"), inp.get("logged_days"), inp["interval"], inp["time_zone"],
                                  inp.get("key"))
    if function == "parse_label_output":
        return parse_label_output(inp["text"])
    raise ValueError("unknown function %r" % (function,))


if __name__ == "__main__":
    import sys
    doc = json.load(sys.stdin)
    print(json.dumps(run_case(doc["function"], doc["input"]), indent=2, sort_keys=True, ensure_ascii=False))
