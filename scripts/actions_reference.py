#!/usr/bin/env python3
"""Ayuvo Actions reference implementation (Python 3 stdlib only).

The iOS (ios/calorietracker/Actions) and Android (android/.../actions) action layers must produce exactly what this
module produces for every case in shared/actions/test-vectors. Pure functions only: catalog lookup, request
validation, date-range resolution, deep-link parsing and the small calculations actions return (see docs/actions.md).
"""

import json
import math
import os
import re

import metrics_reference as MR

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
CATALOG_PATH = os.path.join(ROOT, "shared", "actions", "action_catalog.json")

KINDS = ("get", "set", "search", "open")
PARAM_TYPES = ("number", "integer", "string", "enum", "metric", "entity")
CONFIRMATIONS = ("never", "always", "when_ai")
COACH_MODES = ("none", "read", "propose")
SURFACES = ("siri", "shortcuts", "android", "deeplink", "coach")
SOURCES = ("app",) + SURFACES
PERMISSIONS = ("local", "health_read", "records", "medications", "ai_provider")
OUTPUT_KINDS = ("scalar", "record", "list", "entity", "none")
ERROR_CODES = ("unknown_action", "not_allowed", "unknown_param", "missing_param", "bad_type", "bad_enum", "bad_value",
               "too_long", "out_of_range", "requires_one_of")
LINK_ERRORS = ("not_action_link", "bad_link", "duplicate_param")
ENTITY_MAX_LENGTH = 200

NUMBER_RE = re.compile(r"^[+-]?([0-9]+(\.[0-9]*)?|\.[0-9]+)$")
INTEGER_RE = re.compile(r"^[+-]?[0-9]+$")
METRIC_RE = re.compile(r"^(app:)?[a-z][a-z0-9_]*$")
HEX = "0123456789abcdefABCDEF"


def load_catalog(path=CATALOG_PATH):
    with open(path, encoding="utf-8") as fh:
        return json.load(fh)


def find_action(catalog, action_id):
    for a in catalog["actions"]:
        if a["id"] == action_id:
            return a
    return None


# ---------------------------------------------------------------------------------------------
# Rounding (half up, same formula on every platform)
# ---------------------------------------------------------------------------------------------

def round_to(x, decimals):
    scale = 10 ** decimals
    return math.floor(x * scale + 0.5) / scale


def percent(part, whole):
    return int(math.floor(part * 100.0 / whole + 0.5))


# ---------------------------------------------------------------------------------------------
# Units
# ---------------------------------------------------------------------------------------------

def to_canonical(catalog, family, unit, value):
    return value * catalog["units"][family]["factors"][unit]


def convert(catalog, family, from_unit, to_unit, value):
    factors = catalog["units"][family]["factors"]
    return round_to(value * factors[from_unit] / factors[to_unit], 3)


# ---------------------------------------------------------------------------------------------
# Date ranges: half-open [from_ms, to_ms) in the user's time zone
# ---------------------------------------------------------------------------------------------

def resolve_date_range(preset, now_ms, time_zone, week_start):
    d = MR.local_date_of(now_ms, time_zone)
    day = MR._dt.timedelta(days=1)
    if preset == "today":
        a, b = d, d + day
    elif preset == "yesterday":
        a, b = d - day, d
    elif preset in ("this_week", "last_week"):
        ws = MR.week_start_of(d, week_start)
        a, b = (ws, ws + 7 * day) if preset == "this_week" else (ws - 7 * day, ws)
    elif preset == "last_7_days":
        a, b = d - 6 * day, d + day
    elif preset == "last_30_days":
        a, b = d - 29 * day, d + day
    elif preset in ("this_month", "last_month"):
        first = MR._dt.date(d.year, d.month, 1)
        a, b = (first, MR.add_months(first, 1)) if preset == "this_month" else (MR.add_months(first, -1), first)
    else:
        raise ValueError("bad date range %r" % (preset,))
    return {"from_ms": MR.local_midnight(a, time_zone), "to_ms": MR.local_midnight(b, time_zone)}


# ---------------------------------------------------------------------------------------------
# Validation
# ---------------------------------------------------------------------------------------------

def _fail(code, param=None):
    return {"ok": False, "error": {"code": code, "param": param}}


def _is_bool(v):
    return isinstance(v, bool)


def _coerce(catalog, spec, raw):
    """(value, error_code). raw is never None or an empty string here."""
    t = spec["type"]
    if t == "number":
        if _is_bool(raw):
            return None, "bad_type"
        if isinstance(raw, (int, float)):
            v = float(raw)
        elif isinstance(raw, str) and NUMBER_RE.match(raw):
            v = float(raw)
        else:
            return None, "bad_type"
        if math.isnan(v) or math.isinf(v):
            return None, "bad_type"
    elif t == "integer":
        if _is_bool(raw):
            return None, "bad_type"
        if isinstance(raw, int):
            v = raw
        elif isinstance(raw, float) and raw.is_integer():
            v = int(raw)
        elif isinstance(raw, str) and INTEGER_RE.match(raw):
            v = int(raw)
        else:
            return None, "bad_type"
    elif t == "enum":
        if not isinstance(raw, str):
            return None, "bad_type"
        if raw not in catalog["enums"][spec["enum"]]:
            return None, "bad_enum"
        v = raw
    elif t == "metric":
        if not isinstance(raw, str):
            return None, "bad_type"
        if not METRIC_RE.match(raw):
            return None, "bad_value"
        v = raw
    elif t in ("string", "entity"):
        if not isinstance(raw, str):
            return None, "bad_type"
        limit = spec.get("max_length", ENTITY_MAX_LENGTH) if t == "string" else ENTITY_MAX_LENGTH
        if len(raw) > limit:
            return None, "too_long"
        v = raw
    else:
        raise ValueError("bad param type %r" % (t,))
    if "allowed" in spec and v not in spec["allowed"]:
        return None, "bad_enum"
    return v, None


def validate(catalog, action_id, raw_params, context):
    """Validate and normalise a request. Deterministic check order (see docs/actions.md)."""
    action = find_action(catalog, action_id)
    if action is None:
        return _fail("unknown_action")
    source = context.get("source", "app")
    if source != "app" and source not in action["surfaces"]:
        return _fail("not_allowed")
    specs = action["params"]
    names = [p["name"] for p in specs]
    raw_params = raw_params or {}
    for key in sorted(raw_params):
        if key not in names:
            return _fail("unknown_param", key)
    prefs = context.get("prefs") or {}
    params = {}
    for spec in specs:
        name = spec["name"]
        raw = raw_params.get(name)
        if isinstance(raw, str):
            raw = raw.strip()
            if raw == "":
                raw = None
        if raw is None:
            if "default" in spec:
                params[name] = spec["default"]
                continue
            pref = spec.get("default_pref")
            if pref and prefs.get(pref) in catalog["enums"].get(spec.get("enum"), ()):
                params[name] = prefs[pref]
                continue
            if spec.get("required"):
                return _fail("missing_param", name)
            continue
        value, err = _coerce(catalog, spec, raw)
        if err:
            return _fail(err, name)
        params[name] = value
    for spec in specs:
        name = spec["name"]
        if name not in params:
            continue
        value = params[name]
        lo, hi = spec.get("min"), spec.get("max")
        if "range_by" in spec:
            lo, hi = spec["range_by"]["ranges"][params[spec["range_by"]["param"]]]
        if lo is None and hi is None:
            continue
        if "unit_param" in spec:
            family = spec["unit_family"]
            unit = params.get(spec["unit_param"], catalog["units"][family]["canonical"])
            value = to_canonical(catalog, family, unit, value)
        if (lo is not None and value < lo) or (hi is not None and value > hi):
            return _fail("out_of_range", name)
    groups = action.get("requires_one_of")
    if groups and not any(all(p in params for p in g) for g in groups):
        return _fail("requires_one_of")
    ai = action["confirmation"] == "when_ai" and not all(p in params for p in action.get("ai_unless", []))
    confirm = action["kind"] == "set" and not action.get("opens_app", False) and (
        action["confirmation"] == "always" or ai or source in ("deeplink", "coach"))
    return {"ok": True, "params": params, "confirm": confirm, "ai": ai}


# ---------------------------------------------------------------------------------------------
# Deep links: ayuvo://action/<id>?k=v  and  ayuvo://open/<section>
# ---------------------------------------------------------------------------------------------

def percent_decode(s):
    """Percent-decode with '+' as space; None when malformed or not UTF-8."""
    out = bytearray()
    i = 0
    while i < len(s):
        c = s[i]
        if c == "%":
            if i + 2 >= len(s) or s[i + 1] not in HEX or s[i + 2] not in HEX:
                return None
            out.append(int(s[i + 1:i + 3], 16))
            i += 3
            continue
        if c == "+":
            out.append(0x20)
        else:
            out.extend(c.encode("utf-8"))
        i += 1
    try:
        return out.decode("utf-8")
    except UnicodeDecodeError:
        return None


def parse_deeplink(url):
    prefix = "ayuvo://"
    if not url.startswith(prefix):
        return {"ok": False, "error": {"code": "not_action_link"}}
    rest = url[len(prefix):]
    rest = rest.split("#", 1)[0]
    path, _, query = rest.partition("?")
    host, _, tail = path.partition("/")
    if host not in ("action", "open"):
        return {"ok": False, "error": {"code": "not_action_link"}}
    if tail.endswith("/"):
        tail = tail[:-1]
    if tail == "" or "/" in tail:
        return {"ok": False, "error": {"code": "bad_link"}}
    segment = percent_decode(tail)
    if not segment:
        return {"ok": False, "error": {"code": "bad_link"}}
    params = {}
    for piece in query.split("&") if query else []:
        if piece == "":
            continue
        k, _, v = piece.partition("=")
        key, value = percent_decode(k), percent_decode(v)
        if not key or value is None:
            return {"ok": False, "error": {"code": "bad_link"}}
        if key in params:
            return {"ok": False, "error": {"code": "duplicate_param"}}
        params[key] = value
    if host == "open":
        return {"ok": True, "request": {"id": "open.section", "params": {"section": segment}}}
    return {"ok": True, "request": {"id": segment, "params": params}}


# ---------------------------------------------------------------------------------------------
# Calculations shared by action outputs
# ---------------------------------------------------------------------------------------------

def aggregate(entries, aggregation):
    vals = [(e["t_ms"], e["value"]) for e in entries if e.get("value") is not None]
    n = len(vals)
    if aggregation == "count":
        return {"value": n, "count": n}
    if n == 0:
        return {"value": None, "count": 0}
    if aggregation == "latest":
        best = vals[0]
        for t, v in vals[1:]:
            if t >= best[0]:
                best = (t, v)
        value = best[1]
    elif aggregation == "sum":
        value = 0.0
        for _, v in vals:
            value += v
    elif aggregation == "average":
        total = 0.0
        for _, v in vals:
            total += v
        value = round_to(total / n, 2)
    elif aggregation == "min":
        value = min(v for _, v in vals)
    elif aggregation == "max":
        value = max(v for _, v in vals)
    else:
        raise ValueError("bad aggregation %r" % (aggregation,))
    return {"value": float(value), "count": n}


def water_status(intake_ml, goal_ml):
    has_goal = goal_ml is not None and goal_ml > 0
    return {"intake_ml": intake_ml, "goal_ml": goal_ml if has_goal else None,
            "remaining_ml": max(0, goal_ml - intake_ml) if has_goal else None,
            "percent": percent(intake_ml, goal_ml) if has_goal else None}


def progress(value, target):
    has = target is not None and target > 0
    return {"value": round_to(value, 1), "target": round_to(target, 1) if has else None,
            "remaining": round_to(max(0.0, target - value), 1) if has else None,
            "percent": percent(value, target) if has else None}


def bmi(weight_kg, height_cm):
    if not weight_kg or not height_cm or weight_kg <= 0 or height_cm <= 0:
        return {"bmi": None}
    m = height_cm / 100.0
    return {"bmi": round_to(weight_kg / (m * m), 1)}


def weight_change(entries):
    ordered = sorted(entries, key=lambda e: e["t_ms"])
    if not ordered:
        return {"first_kg": None, "last_kg": None, "change_kg": None, "count": 0}
    first, last = ordered[0]["kg"], ordered[-1]["kg"]
    return {"first_kg": round_to(first, 1), "last_kg": round_to(last, 1), "change_kg": round_to(last - first, 1),
            "count": len(ordered)}


def set_volume(sets):
    reps = 0
    volume = 0.0
    for s in sets:
        reps += s["reps"]
        volume += s["weight_kg"] * s["reps"]
    return {"sets": len(sets), "reps": reps, "volume_kg": round_to(volume, 1)}


def fasting_progress(started_ms, goal_minutes, now_ms):
    elapsed = max(0, (now_ms - started_ms) // 1000)
    goal = goal_minutes * 60
    return {"elapsed_s": elapsed, "goal_s": goal, "remaining_s": max(0, goal - elapsed),
            "percent": percent(elapsed, goal) if goal > 0 else None, "reached": goal > 0 and elapsed >= goal}


def compute(catalog, op, args):
    if op == "convert":
        return {"value": convert(catalog, args["family"], args["from"], args["to"], args["value"])}
    if op == "aggregate":
        return aggregate(args["entries"], args["aggregation"])
    if op == "water_status":
        return water_status(args["intake_ml"], args["goal_ml"])
    if op == "progress":
        return progress(args["value"], args["target"])
    if op == "bmi":
        return bmi(args["weight_kg"], args["height_cm"])
    if op == "weight_change":
        return weight_change(args["entries"])
    if op == "set_volume":
        return set_volume(args["sets"])
    if op == "fasting_progress":
        return fasting_progress(args["started_ms"], args["goal_minutes"], args["now_ms"])
    raise ValueError("bad op %r" % (op,))


COMPUTE_OPS = ("convert", "aggregate", "water_status", "progress", "bmi", "weight_change", "set_volume",
               "fasting_progress")


# ---------------------------------------------------------------------------------------------
# Vector dispatch
# ---------------------------------------------------------------------------------------------

def run_case(function, inp, catalog=None):
    catalog = catalog or load_catalog()
    if function == "validate":
        return validate(catalog, inp["id"], inp.get("params"), inp.get("context") or {})
    if function == "resolve_date_range":
        return resolve_date_range(inp["preset"], inp["now_ms"], inp["time_zone"], inp["week_start"])
    if function == "deeplink":
        parsed = parse_deeplink(inp["url"])
        checked = None
        if parsed["ok"]:
            ctx = {"source": "deeplink", "prefs": inp.get("prefs") or {}}
            checked = validate(catalog, parsed["request"]["id"], parsed["request"]["params"], ctx)
        return {"parse": parsed, "validate": checked}
    if function == "compute":
        return compute(catalog, inp["op"], inp["args"])
    raise ValueError("unknown function %r" % (function,))
