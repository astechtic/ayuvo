#!/usr/bin/env python3
"""Ayuvo Insights reference implementation (Python 3 stdlib only).

The iOS (ios/.../Insights) and Android (android/.../insights) engines must produce exactly what this
module produces for every case in shared/insights/test-vectors. Pure functions over plain inputs only: day-keyed
series, sleep nights, workouts, logs, a profile, targets and shared/insights/insights_config.json. No clock, no
storage, no platform APIs (see docs/insights.md).

Portability rules (every function below follows them so Swift and Kotlin doubles give identical results):
  * rounding is round_to(x, d) = floor(x * 10^d + 0.5) / 10^d, never banker's rounding;
  * sums run in the documented order (chronological for series, list order otherwise);
  * mean / sample SD (n - 1) / least squares / Welch t are written out explicitly, no statistics library;
  * days are "YYYY-MM-DD" strings; instants are epoch milliseconds; wall-clock questions (which local day a
    workout belongs to, sleep midpoint) use the IANA time zone in inputs["time_zone"];
  * text is built by plain placeholder replacement from templates in the config.

The common `inputs` object (every key optional; a missing key means "no data", never zero):
  time_zone        IANA zone of the user
  hrv_kind         "sdnn" (iOS) or "rmssd" (Android): picks the Health Age HRV table
  series           {metric_id: {day: value}} for hrv, resting_heart_rate, respiratory_rate, blood_oxygen (0-100),
                   vo2_max, steps, active_energy, weight, body_fat, bmi (overnight values already resolved)
  sleep            {wake_day: {asleep_min, start_ms, end_ms}} one main night per wake day
  workouts         [{start_ms, end_ms, effort}] effort 1-10 or null (health workouts and Ayuvo strength sessions)
  overnight_fallback [metric_id] metrics whose value today came from the daily rollup, not the night window
  tracking         {nutrition, water, workouts, fasting} booleans
  targets          {calories, protein_g, carbs_g, fat_g, fiber_g, water_ml, steps, fasting_hours,
                    sugar_max_g, added_sugar_max_g, sodium_max_mg, saturated_fat_max_g, caffeine_max_mg}
  nutrition        {day: {calories, protein_g, carbs_g, fat_g, fiber_g, sugar_g, added_sugar_g, sodium_mg,
                    saturated_fat_g, caffeine_mg}}
  water_ml, fasting_hours, strength_volume, recovery_scores   {day: value}
  recovery         a recovery() result for the review day (daily_review only)
  patterns         a patterns() result (daily_review only)
"""

import datetime as _dt
import json
import math
import os
import re
from zoneinfo import ZoneInfo

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
CONFIG_PATH = os.path.join(ROOT, "shared", "insights", "insights_config.json")
PROMPT_PATH = os.path.join(ROOT, "shared", "insights", "ai_explain.md")

MINUS = "−"
DAY_RE = re.compile("^([0-9][0-9][0-9][0-9])-([0-9][0-9])-([0-9][0-9])$")
PLACEHOLDER_RE = re.compile("[{]([a-z_][a-z0-9_]*)[}]")
NUMBER_RE = re.compile("[0-9]+(,[0-9][0-9][0-9])*([.][0-9]+)?")
AI_KINDS = ("recovery", "health_age", "daily_review")
AI_VARIANTS = ("cloud", "local")
REVIEW_CATEGORIES = ("went_well", "needs_attention", "improve", "reduce")


def load_config(path=CONFIG_PATH):
    with open(path, encoding="utf-8") as fh:
        return json.load(fh)


# ---------------------------------------------------------------------------------------------
# Numbers, days and text (same formulas on every platform)
# ---------------------------------------------------------------------------------------------

def round_to(x, decimals):
    if x is None:
        return None
    scale = 10 ** decimals
    v = math.floor(x * scale + 0.5) / scale
    return 0.0 if v == 0 else v          # never emit -0.0


def round_int(x):
    return None if x is None else int(math.floor(x + 0.5))


def clamp(x, lo, hi):
    return lo if x < lo else (hi if x > hi else x)


def mean(values):
    total = 0.0
    for v in values:
        total += v
    return total / len(values)


def sample_sd(values, m):
    """Sample standard deviation (divides by n - 1). Needs n >= 2."""
    total = 0.0
    for v in values:
        total += (v - m) * (v - m)
    return math.sqrt(total / (len(values) - 1))


def ols_slope(points):
    """Least-squares slope of [(x, y)]; None when x has no spread."""
    mx = mean([p[0] for p in points])
    my = mean([p[1] for p in points])
    sxx, sxy = 0.0, 0.0
    for x, y in points:
        sxx += (x - mx) * (x - mx)
        sxy += (x - mx) * (y - my)
    return None if sxx == 0 else sxy / sxx


def parse_day(s):
    m = DAY_RE.match(s or "")
    if not m:
        raise ValueError("bad day %r" % (s,))
    return _dt.date(int(m.group(1)), int(m.group(2)), int(m.group(3)))


def fmt_day(d):
    return "%04d-%02d-%02d" % (d.year, d.month, d.day)


def add_days(day, n):
    return fmt_day(parse_day(day) + _dt.timedelta(days=n))


def days_between(a, b):
    """b - a in whole days."""
    return (parse_day(b) - parse_day(a)).days


def local_day_of(ms, time_zone):
    return fmt_day(_dt.datetime.fromtimestamp(ms / 1000.0, ZoneInfo(time_zone)).date())


def local_minutes_of(ms, time_zone):
    t = _dt.datetime.fromtimestamp(ms / 1000.0, ZoneInfo(time_zone))
    return t.hour * 60 + t.minute


def sleep_midpoint_min(night, wake_day, time_zone):
    """Wall-clock minutes from 12:00 on the day before wake_day to the night's midpoint (integer division of the
    instant): a 23:00-07:00 night -> 900. Same clock as metrics sleep_clock_offset."""
    mid = night["start_ms"] + (night["end_ms"] - night["start_ms"]) // 2
    local = _dt.datetime.fromtimestamp(mid / 1000.0, ZoneInfo(time_zone))
    days = (local.date() - (parse_day(wake_day) - _dt.timedelta(days=1))).days
    return days * 1440 + local.hour * 60 + local.minute - 720


def group_int(n):
    s = str(abs(n))
    out = []
    while len(s) > 3:
        out.insert(0, s[-3:])
        s = s[:-3]
    out.insert(0, s)
    return ("-" if n < 0 else "") + ",".join(out)


def fmt_number(x):
    """Template number: integral -> grouped integer ("8,432"), otherwise one decimal ("7.5")."""
    if x == math.floor(x):
        return group_int(int(x))
    return "%.1f" % round_to(x, 1)


def fmt_signed(x, decimals):
    r = round_to(x, decimals)
    if r == 0:
        return "0"
    body = group_int(int(abs(r))) if decimals == 0 else "%.*f" % (decimals, abs(r))
    return ("+" if r > 0 else MINUS) + body


def fmt_duration(minutes):
    m = round_int(minutes)
    return "%dm" % m if m < 60 else "%dh %dm" % (m // 60, m % 60)


def fill(template, params):
    """Replace each {name} with its param; numbers via fmt_number, strings as is."""
    def rep(match):
        v = params[match.group(1)]
        return v if isinstance(v, str) else fmt_number(v)
    return PLACEHOLDER_RE.sub(rep, template)


def interp(points, x):
    """Piecewise-linear y(x) over [[x, y]] with x ascending; flat beyond the ends."""
    if x <= points[0][0]:
        return float(points[0][1])
    for (x0, y0), (x1, y1) in zip(points, points[1:]):
        if x <= x1:
            return y0 + (y1 - y0) * (x - x0) / (x1 - x0)
    return float(points[-1][1])


def inverse_age(table, value, age_min, age_max):
    """Age at which a monotonic population curve [[age, value]] equals `value`: inverse linear interpolation,
    linear extrapolation from the end segments, then clamped to [age_min, age_max]."""
    n = len(table)
    seg = n - 2
    for i in range(n - 1):
        lo, hi = sorted((table[i][1], table[i + 1][1]))
        if lo <= value <= hi:
            seg = i
            break
    else:
        increasing = table[-1][1] > table[0][1]
        below_first = value < table[0][1] if increasing else value > table[0][1]
        seg = 0 if below_first else n - 2
    (a0, v0), (a1, v1) = table[seg], table[seg + 1]
    age = a0 + (value - v0) * (a1 - a0) / (v1 - v0)
    return clamp(age, float(age_min), float(age_max))


def _values(series, day, first_offset, last_offset):
    """[(offset, value)] for days day+first_offset .. day+last_offset (inclusive, chronological), non-null only."""
    out = []
    d = parse_day(day)
    for k in range(first_offset, last_offset + 1):
        v = series.get(fmt_day(d + _dt.timedelta(days=k)))
        if v is not None:
            out.append((k, float(v)))
    return out


# ---------------------------------------------------------------------------------------------
# Baseline engine
# ---------------------------------------------------------------------------------------------

def _baseline_raw(series, day, m):
    window = m["window_days"]
    vals = [v for _, v in _values(series, day, -window, -1)]
    n = len(vals)
    if m["recent"] == "mean7":
        last7 = [v for _, v in _values(series, day, -6, 0)]
        recent = mean(last7) if last7 else None
    else:
        v = series.get(day)
        recent = None if v is None else float(v)
    b = {"n": n, "needed": m["min_points"], "coverage": n / float(window), "recent": recent, "mean": None,
         "sd": None, "sd_used": None, "low": None, "high": None, "delta": None, "pct": None, "z": None}
    if n < m["min_points"]:
        b.update(status="insufficient", confidence="low")
        return b
    mu = mean(vals)
    sd = sample_sd(vals, mu)
    used = max(sd, m["sd_floor"])
    b.update(status="ok", mean=mu, sd=sd, sd_used=used, low=mu - used, high=mu + used,
             confidence="high" if b["coverage"] >= m["high_confidence_coverage"] and n >= m["high_confidence_min_n"]
             else "medium")
    if recent is not None:
        b["delta"] = recent - mu
        b["pct"] = None if mu == 0 else (recent - mu) / mu * 100.0
        b["z"] = (recent - mu) / used
    return b


def baseline(series, day, metric_cfg):
    """Personal baseline of one metric on `day`: prior window_days (day excluded), mean +/- max(SD, sd_floor)."""
    b = _baseline_raw(series, day, metric_cfg)
    return {"status": b["status"], "n": b["n"], "needed": b["needed"], "coverage": round_to(b["coverage"], 2),
            "mean": round_to(b["mean"], 2), "sd": round_to(b["sd"], 2), "sd_floored": b["sd"] is not None and b["sd"] < metric_cfg["sd_floor"],
            "low": round_to(b["low"], 2), "high": round_to(b["high"], 2), "recent": round_to(b["recent"], 2),
            "delta": round_to(b["delta"], 2), "pct": round_to(b["pct"], 1), "z": round_to(b["z"], 2),
            "confidence": b["confidence"]}


def trend(series, day, metric_cfg):
    """Least-squares slope over the last trend_days (day included) as % of the baseline mean per week."""
    m = metric_cfg
    b = _baseline_raw(series, day, m)
    pts = _values(series, day, -(m["trend_days"] - 1), 0)
    out = {"status": "insufficient", "n": len(pts), "needed": m["trend_min_points"], "slope_pct_per_week": None,
           "direction": None}
    if b["status"] != "ok" or len(pts) < m["trend_min_points"] or b["mean"] == 0:
        return out
    slope = ols_slope([(float(x), y) for x, y in pts])
    if slope is None:
        return out
    pct = slope * 7.0 / b["mean"] * 100.0
    if abs(pct) < m["trend_stable_pct_per_week"]:
        direction = "stable"
    elif m["direction"] == "band":
        direction = "changing"
    elif (pct > 0) == (m["direction"] == "higher_better"):
        direction = "improving"
    else:
        direction = "declining"
    out.update(status="ok", slope_pct_per_week=round_to(pct, 2), direction=direction)
    return out


def overnight_value(samples, night, fallback_value=None):
    """Mean of samples [{t_ms, value}] with night.start_ms <= t_ms <= night.end_ms. No night or no sample inside
    it -> the daily-rollup fallback_value (may be null) with fallback true."""
    if night is not None:
        inside = [float(s["value"]) for s in samples if night["start_ms"] <= s["t_ms"] <= night["end_ms"]]
        if inside:
            return {"value": round_to(mean(inside), 2), "n": len(inside), "fallback": False}
    return {"value": None if fallback_value is None else round_to(float(fallback_value), 2), "n": 0, "fallback": True}


# ---------------------------------------------------------------------------------------------
# Sleep helpers
# ---------------------------------------------------------------------------------------------

def valid_nights(inputs, cfg):
    """Nights long enough to count (shorter ones are treated as incomplete recordings)."""
    floor_min = cfg["sleep"]["min_night_minutes"]
    return dict((d, n) for d, n in (inputs.get("sleep") or {}).items()
                if n is not None and n.get("asleep_min") is not None and n["asleep_min"] >= floor_min)


def sleep_series(inputs, cfg):
    return dict((d, float(n["asleep_min"])) for d, n in valid_nights(inputs, cfg).items())


# ---------------------------------------------------------------------------------------------
# Training load
# ---------------------------------------------------------------------------------------------

def sessions(workouts, time_zone, cfg):
    """Overlapping workouts merged into sessions (sorted by start, then end). A session spans the union of its
    members; intensity = max member intensity, where intensity = clamp(effort / effort_divisor, min, max) or
    default_intensity without an effort. Day = local day of the session start."""
    t = cfg["training_load"]
    ws = sorted([w for w in workouts if w["end_ms"] > w["start_ms"]], key=lambda w: (w["start_ms"], w["end_ms"]))
    out = []
    for w in ws:
        has_effort = w.get("effort") is not None
        inten = clamp(w["effort"] / t["effort_divisor"], t["intensity_min"], t["intensity_max"]) if has_effort \
            else t["default_intensity"]
        if out and w["start_ms"] < out[-1]["end_ms"]:
            s = out[-1]
            s["end_ms"] = max(s["end_ms"], w["end_ms"])
            s["intensity"] = max(s["intensity"], inten)
            s["has_effort"] = s["has_effort"] or has_effort
            s["members"] += 1
        else:
            out.append({"start_ms": w["start_ms"], "end_ms": w["end_ms"], "intensity": inten,
                        "has_effort": has_effort, "members": 1})
    for s in out:
        s["day"] = local_day_of(s["start_ms"], time_zone)
        s["minutes"] = (s["end_ms"] - s["start_ms"]) / 60000.0
        s["load"] = s["minutes"] * s["intensity"]
    return out


def daily_loads(workouts, time_zone, cfg):
    days = {}
    for s in sessions(workouts, time_zone, cfg):
        d = days.setdefault(s["day"], {"load": 0.0, "minutes": 0.0, "sessions": 0})
        d["load"] += s["load"]
        d["minutes"] += s["minutes"]
        d["sessions"] += 1
    return days


def _load_raw(loads, day, cfg):
    t = cfg["training_load"]
    today = loads.get(day, {"load": 0.0, "minutes": 0.0, "sessions": 0})
    total = 0.0
    for k in range(t["mean_window_days"], 0, -1):
        total += loads.get(add_days(day, -k), {"load": 0.0})["load"]
    avg = total / t["mean_window_days"]
    ratio = today["load"] / avg if avg > 0 else None
    if today["load"] == 0:
        cat = "none"
    elif ratio is None or ratio > t["high_above"]:
        cat = "high"
    elif ratio < t["light_below"]:
        cat = "light"
    else:
        cat = "moderate"
    return {"day": day, "load": today["load"], "minutes": today["minutes"], "sessions": today["sessions"],
            "mean_28d": avg, "ratio": ratio, "category": cat}


def training_load(workouts, day, time_zone, cfg):
    """Load of `day` (sum of session minutes x intensity) vs the mean daily load of the 28 days before it."""
    r = _load_raw(daily_loads(workouts, time_zone, cfg), day, cfg)
    return {"day": r["day"], "load": round_to(r["load"], 1), "minutes": round_to(r["minutes"], 1),
            "sessions": r["sessions"], "mean_28d": round_to(r["mean_28d"], 1), "ratio": round_to(r["ratio"], 2),
            "category": r["category"], "label": cfg["training_load"]["labels"][r["category"]]}


# ---------------------------------------------------------------------------------------------
# Recovery
# ---------------------------------------------------------------------------------------------

def _subscore(z_dir, rc):
    return clamp(rc["subscore_center"] + rc["subscore_slope"] * z_dir, 0.0, 100.0)


def _consistency(nights, day, time_zone, rc):
    """(deviation minutes, sub-score) of last night's midpoint vs the mean midpoint of the prior 14 nights."""
    prior = []
    for k in range(rc["consistency_window_days"], 0, -1):
        d = add_days(day, -k)
        if d in nights:
            prior.append(float(sleep_midpoint_min(nights[d], d, time_zone)))
    if len(prior) < rc["consistency_min_nights"]:
        return None, None
    dev = abs(sleep_midpoint_min(nights[day], day, time_zone) - mean(prior))
    return dev, clamp(100.0 * (1.0 - dev / rc["consistency_zero_at_min"]), 0.0, 100.0)


def recovery(inputs, day, cfg):
    """Morning readiness 0-100 for `day` (the wake day of last night's sleep). See docs/insights.md."""
    rc = cfg["recovery"]
    tz = inputs.get("time_zone", "UTC")
    series = inputs.get("series") or {}
    nights = valid_nights(inputs, cfg)
    fallback = set(inputs.get("overnight_fallback") or [])
    comps, raw = [], {}
    for c in rc["components"]:
        m = cfg["metrics"][c["metric"]]
        s = sleep_series(inputs, cfg) if c["id"] == "sleep" else dict(
            (d, v) for d, v in (series.get(c["metric"]) or {}).items() if v is not None)
        b = _baseline_raw(s, day, m)
        item = {"id": c["id"], "weight": c["weight"], "available": False, "value": b["recent"], "baseline": b["mean"],
                "delta": b["delta"], "pct": b["pct"], "z": b["z"], "subscore": None, "impact": None,
                "baseline_n": b["n"], "baseline_confidence": b["confidence"], "fallback": c["metric"] in fallback,
                "consistency_deviation_min": None, "consistency_subscore": None}
        if b["status"] == "ok" and b["recent"] is not None:
            z = b["z"]
            if c["mode"] == "higher":
                sub = _subscore(z, rc)
            elif c["mode"] == "lower":
                sub = _subscore(-z, rc)
            elif c["mode"] == "band":
                sub = _subscore(-max(0.0, abs(z) - c["tolerance_z"]), rc)
            elif c["mode"] == "drop_only":
                sub = _subscore(-max(0.0, -z - c["tolerance_z"]), rc)
            else:  # sleep: duration vs personal baseline, blended with bedtime consistency when available
                sub = _subscore(z, rc)
                dev, cons = _consistency(nights, day, tz, rc)
                if cons is not None:
                    item["consistency_deviation_min"], item["consistency_subscore"] = dev, cons
                    sub = c["duration_share"] * sub + c["consistency_share"] * cons
            item.update(available=True, subscore=sub)
        comps.append(item)
        raw[c["id"]] = (item, b)
    sleep_item, sleep_b = raw["sleep"]
    heart = [raw[i] for i in rc["heart_components"]]
    need = cfg["metrics"]["sleep"]["min_points"]
    have = min(sleep_b["n"], max(b["n"] for _, b in heart))
    out = {"day": day, "status": "ok", "score": None, "label": None, "label_text": None, "recommendation": None,
           "confidence": None, "collecting": None, "components": comps, "positives": [], "negatives": [], "load": None}
    if sleep_b["status"] != "ok" or all(b["status"] != "ok" for _, b in heart):
        out.update(status="collecting", collecting={"have": min(have, need), "need": need})
    elif not sleep_item["available"]:
        out["status"] = "no_sleep"
    elif not any(i["available"] for i, _ in heart):
        out["status"] = "no_heart_data"
    if out["status"] != "ok":
        return _round_recovery(out)
    avail = [i for i in comps if i["available"]]
    wsum = 0.0
    for i in avail:
        wsum += i["weight"]
    score = 0.0
    for i in avail:
        score += i["weight"] * i["subscore"]
        i["impact"] = (i["subscore"] - rc["subscore_center"]) * i["weight"] / wsum
    score /= wsum
    load = _load_raw(daily_loads(inputs.get("workouts") or [], tz, cfg), add_days(day, -1), cfg)
    mod = 0
    if load["category"] == "high":
        lm = rc["load_modifier"]
        mod = lm["very_high"] if load["ratio"] is not None and load["ratio"] > lm["very_high_ratio"] else lm["high"]
    final = int(clamp(round_int(score + mod), 0, 100))
    band = next(b for b in rc["bands"] if final >= b["min"])
    all_high = all(i["baseline_confidence"] == "high" for i in avail)
    conf = "high" if len(avail) >= 4 and all_high else ("medium" if len(avail) >= 3 or all_high else "low")
    tpl = rc["contributors"]
    signals = []
    for i in avail:
        signals.append((i["impact"], i["id"], fill(tpl[i["id"]], _contributor_params(i))))
    pos = sorted([s for s in signals if s[0] > 0], key=lambda s: -s[0])
    neg = sorted([s for s in signals if s[0] < 0], key=lambda s: s[0])
    if mod < 0:
        neg.append((float(mod), "training_load", fill(tpl["training_load"],
                                                       {"category": cfg["training_load"]["labels"][load["category"]]})))
        neg = sorted(neg, key=lambda s: s[0])
    out.update(score=final, label=band["id"], label_text=band["label"], recommendation=band["recommendation"],
               confidence=conf,
               positives=[{"id": s[1], "impact": s[0], "text": s[2]} for s in pos],
               negatives=[{"id": s[1], "impact": s[0], "text": s[2]} for s in neg],
               load={"day": load["day"], "load": load["load"], "mean_28d": load["mean_28d"], "ratio": load["ratio"],
                     "category": load["category"], "label": cfg["training_load"]["labels"][load["category"]],
                     "modifier": mod})
    return _round_recovery(out)


def _contributor_params(i):
    return {"pct": fmt_signed(i["pct"] if i["pct"] is not None else 0.0, 0), "delta0": fmt_signed(i["delta"], 0),
            "delta1": fmt_signed(i["delta"], 1), "value1": "%.1f" % round_to(i["value"], 1),
            "duration": fmt_duration(i["value"])}


def _round_recovery(out):
    for i in out["components"]:
        for k in ("value", "baseline", "delta", "z"):
            i[k] = round_to(i[k], 2)
        for k in ("pct", "subscore", "impact", "consistency_deviation_min", "consistency_subscore"):
            i[k] = round_to(i[k], 1)
    for lst in (out["positives"], out["negatives"]):
        for s in lst:
            s["impact"] = round_to(s["impact"], 1)
    if out["load"]:
        for k, d in (("load", 1), ("mean_28d", 1), ("ratio", 2)):
            out["load"][k] = round_to(out["load"][k], d)
    return out


# ---------------------------------------------------------------------------------------------
# Health Age
# ---------------------------------------------------------------------------------------------

def _sex_table(tables, sex):
    """Sex-specific table; any other or missing sex -> the element-wise mean of the male and female tables."""
    if sex in ("male", "female"):
        return tables[sex]
    return [[a, (m + f) / 2.0] for (a, m), (_, f) in zip(tables["male"], tables["female"])]


def _window(series, as_of, days):
    return [v for _, v in _values(series, as_of, -(days - 1), 0)]


def _marker(mk, inputs, as_of, profile, actual, cfg):
    """(value, secondary, days, equivalent_age, offset or None when unavailable)."""
    ha = cfg["health_age"]
    win = ha["window_days"]
    series = inputs.get("series") or {}
    tz = inputs.get("time_zone", "UTC")
    sex = profile.get("sex")
    kind = mk["method"]
    if kind in ("age_norm", "dose_response"):
        vals = _window(series.get(mk["series"]) or {}, as_of, win)
        if len(vals) < mk["min_days"]:
            return None, None, len(vals), None, None
        v = mean(vals)
        if kind == "dose_response":
            return v, None, len(vals), None, interp(mk["points"], v)
        tables = mk["tables"][inputs.get("hrv_kind", "sdnn")] if "tables_by_kind" in mk and mk["tables_by_kind"] \
            else mk["tables"]
        eq = inverse_age(_sex_table(tables, sex), v, ha["age_min"], ha["age_max"])
        return v, None, len(vals), eq, eq - actual
    if kind == "sleep":
        nights = valid_nights(inputs, cfg)
        days = [add_days(as_of, -k) for k in range(win - 1, -1, -1)]
        use = [d for d in days if d in nights]
        if len(use) < mk["min_days"]:
            return None, None, len(use), None, None
        hours = mean([nights[d]["asleep_min"] / 60.0 for d in use])
        mids = [float(sleep_midpoint_min(nights[d], d, tz)) for d in use]
        sd = sample_sd(mids, mean(mids))
        return hours, sd, len(use), None, interp(mk["duration_points"], hours) + interp(mk["regularity_points"], sd)
    if kind == "workouts":
        wear = len(_window(series.get("steps") or {}, as_of, win))
        if not (inputs.get("tracking") or {}).get("workouts") or wear < mk["min_days"]:
            return None, None, wear, None, None
        loads = daily_loads(inputs.get("workouts") or [], tz, cfg)
        weeks = mk["weeks"]
        total, active = 0.0, 0
        for w in range(weeks - 1, -1, -1):
            block = 0.0
            for k in range(6, -1, -1):
                block += loads.get(add_days(as_of, -(7 * w + k)), {"minutes": 0.0})["minutes"]
            total += block
            if block > 0:
                active += 1
        per_week = total / weeks
        share = active / float(weeks)
        return per_week, share, wear, None, interp(mk["minutes_points"], per_week) + interp(mk["active_share_points"], share)
    if kind == "body_composition":
        fat = _window(series.get("body_fat") or {}, as_of, win)
        if sex in ("male", "female") and len(fat) >= mk["min_days"]:
            v = mean(fat)
            return v, None, len(fat), None, interp(mk["body_fat_points"][sex], v)
        bmis = _window(series.get("bmi") or {}, as_of, win)
        if not bmis and profile.get("height_cm"):
            h = profile["height_cm"] / 100.0
            bmis = [w / (h * h) for w in _window(series.get("weight") or {}, as_of, win)]
        if len(bmis) < mk["min_days"]:
            return None, None, len(bmis), None, None
        v = mean(bmis)
        return v, "bmi", len(bmis), None, interp(mk["bmi_points"], v)
    raise ValueError("unknown marker method %r" % (kind,))


def _health_age_raw(inputs, as_of, profile, cfg):
    ha = cfg["health_age"]
    out = {"as_of": as_of, "status": "ok", "actual_age": None, "health_age": None, "difference": None,
           "confidence": None, "markers": [], "collecting": None, "markers_available": 0,
           "markers_needed": ha["min_markers"]}
    bday = (profile or {}).get("birthday")
    if not bday:
        out["status"] = "no_birthday"
        return out
    actual = days_between(bday, as_of) / ha["days_per_year"]
    out["actual_age"] = actual
    if actual < ha["min_actual_age"]:
        out["status"] = "unsupported_age"
        return out
    wsum, acc = 0.0, 0.0
    for mk in ha["markers"]:
        value, secondary, days, eq, off = _marker(mk, inputs, as_of, profile, actual, cfg)
        item = {"id": mk["id"], "method": mk["method"], "available": off is not None,
                "value": value, "secondary_value": None if isinstance(secondary, str) else secondary,
                "basis": secondary if isinstance(secondary, str) else None,
                "days": days, "needed_days": mk["min_days"], "equivalent_age": eq, "offset_years": None,
                "weight": mk["weight"], "contribution_years": None}
        if mk["method"] == "body_composition" and off is not None and item["basis"] is None:
            item["basis"] = "body_fat"
        if off is not None:
            item["offset_years"] = clamp(off, -mk["cap_years"], mk["cap_years"])
            wsum += mk["weight"]
        out["markers"].append(item)
    avail = [i for i in out["markers"] if i["available"]]
    out["markers_available"] = len(avail)
    core = set(ha["core_markers"])
    if len(avail) < ha["min_markers"] or not any(i["id"] in core for i in avail):
        need = ha["collecting_days"]
        best = max([i["days"] for i in out["markers"] if i["id"] in core] + [0])
        out.update(status="collecting", collecting={"have": min(best, need), "need": need})
        return out
    for i in avail:
        i["contribution_years"] = i["weight"] * i["offset_years"] / wsum
        acc += i["contribution_years"]
    diff = clamp(acc, -ha["total_cap_years"], ha["total_cap_years"])
    ids = set(i["id"] for i in avail)
    c = ha["confidence"]
    if len(avail) >= c["high_min_markers"] and c["high_requires"] in ids:
        conf = "high"
    elif len(avail) >= c["medium_min_markers"]:
        conf = "medium"
    else:
        conf = "low"
    out.update(health_age=actual + diff, difference=diff, confidence=conf)
    return out


def health_age(inputs, as_of_day, profile, cfg):
    """Ayuvo Health Age as of `as_of_day` (inclusive 90-day window)."""
    r = _health_age_raw(inputs, as_of_day, profile, cfg)
    r["actual_age"] = round_to(r["actual_age"], 1)
    r["health_age"] = round_to(r["health_age"], 1)
    r["difference"] = round_to(r["difference"], 1)
    for i in r["markers"]:
        for k, d in (("value", 2), ("secondary_value", 2), ("equivalent_age", 1), ("offset_years", 2),
                     ("contribution_years", 2)):
            i[k] = round_to(i[k], d)
    return r


def health_age_pace(inputs, as_of_day, profile, cfg):
    """Health Age recomputed as of each of the last `weeks` week-ends (as_of, as_of-7 ... as_of-7*weeks);
    pace = least-squares slope in years of Health Age per calendar year (days_per_year)."""
    p = cfg["health_age"]["pace"]
    pts = []
    for k in range(p["weeks"], -1, -1):
        d = add_days(as_of_day, -7 * k)
        r = _health_age_raw(inputs, d, profile, cfg)
        if r["status"] == "ok":
            pts.append((-7 * k, d, r))
    out = {"status": "insufficient", "have": len(pts), "needed": p["min_points"], "pace": None, "direction": None,
           "points": [{"day": d, "health_age": round_to(r["health_age"], 1), "difference": round_to(r["difference"], 1)}
                      for _, d, r in pts]}
    if len(pts) < p["min_points"]:
        return out
    slope = ols_slope([(float(x), r["health_age"]) for x, _, r in pts])
    pace = slope * cfg["health_age"]["days_per_year"]
    direction = "improving" if pace < p["improving_below"] else ("declining" if pace > p["declining_above"] else "stable")
    out.update(status="ok", pace=round_to(pace, 2), direction=direction)
    return out


# ---------------------------------------------------------------------------------------------
# Daily Health Review
# ---------------------------------------------------------------------------------------------

def _pct_dev(value, target):
    return (value - target) / target * 100.0


def _linear_score(dev_abs, full_within, zero_at):
    if dev_abs <= full_within:
        return 100.0
    return clamp(100.0 * (zero_at - dev_abs) / (zero_at - full_within), 0.0, 100.0)


def _pos(x):
    return x is not None and x > 0


def daily_review(inputs, day, cfg):
    """End-of-day review of `day`: area scores, Day Score and rule items. See docs/insights.md."""
    rv = cfg["daily_review"]
    tz = inputs.get("time_zone", "UTC")
    tracking = inputs.get("tracking") or {}
    targets = inputs.get("targets") or {}
    series = inputs.get("series") or {}
    food = (inputs.get("nutrition") or {}).get(day)
    logged = food is not None and _pos(food.get("calories"))
    water = (inputs.get("water_ml") or {}).get(day)
    fast = (inputs.get("fasting_hours") or {}).get(day)
    steps = series.get("steps", {}).get(day)
    nights = valid_nights(inputs, cfg)
    night = nights.get(day)
    sleep_b = _baseline_raw(sleep_series(inputs, cfg), day, cfg["metrics"]["sleep"])
    rec = inputs.get("recovery")
    rec_ok = rec is not None and rec.get("status") == "ok"
    loads = daily_loads(inputs.get("workouts") or [], tz, cfg)
    today_load = loads.get(day, {"minutes": 0.0, "sessions": 0})
    prev = _load_raw(loads, add_days(day, -1), cfg)
    nut = rv["nutrition"]

    # --- area scores ---
    scores, details = {}, {}
    if tracking.get("nutrition", True) and logged:
        checks, det = [], {"calories": food.get("calories")}
        if _pos(targets.get("calories")):
            dev = _pct_dev(food["calories"], targets["calories"])
            checks.append(_linear_score(abs(dev), nut["calorie_tolerance_pct"], nut["calorie_zero_at_pct"]))
            det["calorie_dev_pct"] = round_to(dev, 1)
        if _pos(targets.get("protein_g")) and food.get("protein_g") is not None:
            checks.append(min(100.0, 100.0 * food["protein_g"] / (nut["protein_min_share"] * targets["protein_g"])))
            det["protein_g"] = food["protein_g"]
        if _pos(targets.get("fiber_g")) and food.get("fiber_g") is not None:
            checks.append(min(100.0, 100.0 * food["fiber_g"] / targets["fiber_g"]))
            det["fiber_g"] = food["fiber_g"]
        for key in ("carbs_g", "fat_g"):
            if _pos(targets.get(key)) and food.get(key) is not None:
                checks.append(_linear_score(abs(_pct_dev(food[key], targets[key])), nut["macro_tolerance_pct"],
                                            nut["macro_zero_at_pct"]))
                det[key] = food[key]
        if checks:
            scores["nutrition"], details["nutrition"] = mean(checks), det
    if tracking.get("water") and _pos(water) and _pos(targets.get("water_ml")):
        scores["hydration"] = min(100.0, 100.0 * water / targets["water_ml"])
        details["hydration"] = {"water_ml": water, "goal_ml": targets["water_ml"]}
    if steps is not None and _pos(targets.get("steps")):
        scores["activity"] = min(100.0, 100.0 * steps / targets["steps"])
        details["activity"] = {"steps": steps, "goal": targets["steps"]}
    if tracking.get("workouts"):
        if today_load["minutes"] > 0:
            s = 100.0
        elif prev["category"] == "high":
            s = 100.0
        else:
            s = rv["training_rest_score"]
        scores["training"] = s
        details["training"] = {"minutes": round_to(today_load["minutes"], 1), "sessions": today_load["sessions"],
                               "previous_day_category": prev["category"]}
    if night is not None:
        scores["sleep"] = min(100.0, 100.0 * night["asleep_min"] / rv["sleep_target_min"])
        details["sleep"] = {"asleep_min": night["asleep_min"],
                            "baseline_min": round_to(sleep_b["mean"], 1) if sleep_b["status"] == "ok" else None}
    if rec_ok:
        scores["recovery"] = float(rec["score"])
        details["recovery"] = {"score": rec["score"], "label": rec["label"]}
    if tracking.get("fasting") and _pos(fast) and _pos(targets.get("fasting_hours")):
        scores["fasting"] = min(100.0, 100.0 * fast / targets["fasting_hours"])
        details["fasting"] = {"hours": fast, "goal_hours": targets["fasting_hours"]}

    areas, wsum, acc, not_logged = [], 0.0, 0.0, []
    tracked = {"nutrition": tracking.get("nutrition", True), "hydration": bool(tracking.get("water")),
               "activity": True, "training": bool(tracking.get("workouts")), "sleep": True, "recovery": True,
               "fasting": bool(tracking.get("fasting"))}
    for a in rv["areas"]:
        inc = a["id"] in scores
        areas.append({"id": a["id"], "included": inc, "score": round_int(scores[a["id"]]) if inc else None,
                      "weight": a["weight"], "detail": details.get(a["id"])})
        if inc:
            wsum += a["weight"]
            acc += a["weight"] * scores[a["id"]]
        elif tracked[a["id"]]:
            params = {"area": a["id"], "area_label": a["label"]}
            not_logged.append({"rule_id": "not_logged", "params": params, "text": fill(rv["not_logged_template"], params)})
    day_score = round_int(acc / wsum) if wsum > 0 else None

    # --- rules ---
    ctx = {"food": food if logged and "nutrition" in scores else None, "targets": targets, "water": water,
           "hydration": "hydration" in scores, "steps": steps, "activity": "activity" in scores,
           "training": "training" in scores, "minutes": today_load["minutes"], "prev": prev, "night": night,
           "sleep_b": sleep_b, "rec": rec if rec_ok else None, "fast": fast, "fasting": "fasting" in scores,
           "cfg": cfg, "inputs": inputs, "day": day}
    items = dict((c, []) for c in REVIEW_CATEGORIES)
    for rule in rv["rules"]:
        for params in RULES[rule["id"]](ctx, rule):
            items[rule["category"]].append({"rule_id": rule["id"], "params": params, "text": fill(rule["template"], params)})
    cap = rv["max_items_per_category"]
    out = {"day": day, "day_score": day_score, "areas": areas, "not_logged": not_logged}
    for c in REVIEW_CATEGORIES:
        out[c] = items[c][:cap]
    return out


def _food(ctx, key):
    f = ctx["food"]
    return None if f is None else f.get(key)


def _target(ctx, key):
    t = ctx["targets"].get(key)
    return t if _pos(t) else None


def _cal_dev(ctx):
    c, t = _food(ctx, "calories"), _target(ctx, "calories")
    return None if c is None or t is None else _pct_dev(c, t)


def _r_calories_on_target(ctx, rule):
    d = _cal_dev(ctx)
    tol = ctx["cfg"]["daily_review"]["nutrition"]["calorie_tolerance_pct"]
    return [] if d is None or abs(d) > tol else [{"calories": round_int(_food(ctx, "calories")),
                                                   "target": round_int(_target(ctx, "calories"))}]


def _r_calories_over(ctx, rule):
    d = _cal_dev(ctx)
    tol = ctx["cfg"]["daily_review"]["nutrition"]["calorie_tolerance_pct"]
    return [] if d is None or d <= tol else [{"calories": round_int(_food(ctx, "calories")),
                                              "target": round_int(_target(ctx, "calories")), "over_pct": round_int(d)}]


def _r_calories_under(ctx, rule):
    d = _cal_dev(ctx)
    tol = ctx["cfg"]["daily_review"]["nutrition"]["calorie_tolerance_pct"]
    return [] if d is None or d >= -tol else [{"calories": round_int(_food(ctx, "calories")),
                                               "target": round_int(_target(ctx, "calories")), "under_pct": round_int(-d)}]


def _protein(ctx):
    p, t = _food(ctx, "protein_g"), _target(ctx, "protein_g")
    return (None, None) if p is None or t is None else (p, t)


def _r_protein_met(ctx, rule):
    p, t = _protein(ctx)
    return [] if p is None or p < rule["min_share"] * t else [{"protein_g": round_int(p), "pct": round_int(100.0 * p / t)}]


def _r_protein_low(ctx, rule):
    p, t = _protein(ctx)
    return [] if p is None or p >= rule["below_share"] * t else [{"protein_g": round_int(p), "pct": round_int(100.0 * p / t)}]


def _r_improve_protein(ctx, rule):
    p, t = _protein(ctx)
    return [] if p is None or p >= rule["below_share"] * t else [{"gap_g": round_int(t - p)}]


def _fiber(ctx):
    f, g = _food(ctx, "fiber_g"), _target(ctx, "fiber_g")
    return (None, None) if f is None or g is None else (f, g)


def _r_fiber_met(ctx, rule):
    f, g = _fiber(ctx)
    return [] if f is None or f < g else [{"fiber_g": round_int(f), "goal_g": round_int(g)}]


def _r_improve_fiber(ctx, rule):
    f, g = _fiber(ctx)
    return [] if f is None or f >= g else [{"gap_g": round_int(g - f)}]


def _r_water_met(ctx, rule):
    if not ctx["hydration"] or ctx["water"] < ctx["targets"]["water_ml"]:
        return []
    return [{"water_ml": round_int(ctx["water"]), "goal_ml": round_int(ctx["targets"]["water_ml"])}]


def _r_water_low(ctx, rule):
    if not ctx["hydration"] or ctx["water"] >= rule["below_share"] * ctx["targets"]["water_ml"]:
        return []
    return [{"water_ml": round_int(ctx["water"]), "goal_ml": round_int(ctx["targets"]["water_ml"])}]


def _r_improve_water(ctx, rule):
    if not ctx["hydration"] or ctx["water"] >= ctx["targets"]["water_ml"]:
        return []
    return [{"gap_ml": round_int(ctx["targets"]["water_ml"] - ctx["water"])}]


def _r_steps_met(ctx, rule):
    if not ctx["activity"] or ctx["steps"] < ctx["targets"]["steps"]:
        return []
    return [{"steps": round_int(ctx["steps"]), "goal": round_int(ctx["targets"]["steps"])}]


def _r_steps_low(ctx, rule):
    if not ctx["activity"] or ctx["steps"] >= rule["below_share"] * ctx["targets"]["steps"]:
        return []
    return [{"steps": round_int(ctx["steps"]), "goal": round_int(ctx["targets"]["steps"])}]


def _r_improve_steps(ctx, rule):
    if not ctx["activity"] or ctx["steps"] >= ctx["targets"]["steps"]:
        return []
    gap = ctx["targets"]["steps"] - ctx["steps"]
    step = rule["walk_round_min"]
    walk = max(step, int(math.ceil(gap / rule["steps_per_minute"] / step)) * step)
    return [{"gap": round_int(gap), "walk_min": walk}]


def _r_workout_done(ctx, rule):
    return [{"minutes": round_int(ctx["minutes"])}] if ctx["training"] and ctx["minutes"] > 0 else []


def _r_planned_rest(ctx, rule):
    return [{}] if ctx["training"] and ctx["minutes"] == 0 and ctx["prev"]["category"] == "high" else []


def _r_lighter_tomorrow(ctx, rule):
    if not ctx["training"] or ctx["minutes"] == 0:
        return []
    today = _load_raw(daily_loads(ctx["inputs"].get("workouts") or [], ctx["inputs"].get("time_zone", "UTC"),
                                  ctx["cfg"]), ctx["day"], ctx["cfg"])
    return [{}] if today["category"] == "high" else []


def _r_sleep_good(ctx, rule):
    n = ctx["night"]
    return [] if n is None or n["asleep_min"] < rule["min_minutes"] else [{"duration": fmt_duration(n["asleep_min"])}]


def _r_sleep_short(ctx, rule):
    n = ctx["night"]
    return [] if n is None or n["asleep_min"] >= rule["below_minutes"] else [{"duration": fmt_duration(n["asleep_min"])}]


def _r_sleep_below_usual(ctx, rule):
    n, b = ctx["night"], ctx["sleep_b"]
    if n is None or b["status"] != "ok" or n["asleep_min"] >= b["mean"] - rule["margin_minutes"]:
        return []
    return [{"duration": fmt_duration(n["asleep_min"]), "diff_min": round_int(b["mean"] - n["asleep_min"])}]


def _r_improve_sleep(ctx, rule):
    n = ctx["night"]
    target = ctx["cfg"]["daily_review"]["sleep_target_min"]
    if n is None or n["asleep_min"] >= target:
        return []
    step = rule["round_min"]
    mins = min(rule["max_minutes"], int(math.ceil((target - n["asleep_min"]) / step)) * step)
    return [{"minutes": mins}]


def _r_recovery_good(ctx, rule):
    r = ctx["rec"]
    return [{"score": r["score"]}] if r is not None and r["label"] == "good" else []


def _r_recovery_low(ctx, rule):
    r = ctx["rec"]
    return [{"score": r["score"]}] if r is not None and r["label"] == "low" else []


def _r_recovery_day(ctx, rule):
    r = ctx["rec"]
    return [{}] if r is not None and r["label"] == "low" else []


def _r_fasting_met(ctx, rule):
    if not ctx["fasting"] or ctx["fast"] < ctx["targets"]["fasting_hours"]:
        return []
    return [{"hours": round_to(ctx["fast"], 1), "goal_hours": round_to(ctx["targets"]["fasting_hours"], 1)}]


def _nutrient_average(ctx, key):
    rv = ctx["cfg"]["daily_review"]
    vals = []
    days = ctx["inputs"].get("nutrition") or {}
    for k in range(rv["average_window_days"], 0, -1):
        f = days.get(add_days(ctx["day"], -k))
        if f is not None and _pos(f.get("calories")) and f.get(key) is not None:
            vals.append(float(f[key]))
    return mean(vals) if len(vals) >= rv["average_min_days"] else None


def _reduce(ctx, with_average):
    out = []
    for n in ctx["cfg"]["daily_review"]["reduce_nutrients"]:
        v, g = _food(ctx, n["id"]), _target(ctx, n["goal_key"])
        if v is None or g is None or v <= g:
            continue
        avg = _nutrient_average(ctx, n["id"])
        above_avg = avg is not None and v > avg
        if above_avg != with_average:
            continue
        p = {"nutrient": n["label"], "value": round_int(v), "goal": round_int(g), "unit": n["unit"]}
        if with_average:
            p["average"] = round_int(avg)
        out.append(p)
    return out


def _r_reduce_avg(ctx, rule):
    return _reduce(ctx, True)


def _r_reduce_goal(ctx, rule):
    return _reduce(ctx, False)


def _r_reduce_pattern(ctx, rule):
    ids = set(p["id"] for p in ctx["cfg"]["patterns"]["pairs"] if p.get("review_category") == "reduce")
    return [{"pattern_text": p["text"]} for p in (ctx["inputs"].get("patterns") or [])
            if p.get("surfaced") and p["id"] in ids]


RULES = {
    "calories_on_target": _r_calories_on_target, "protein_met": _r_protein_met, "fiber_met": _r_fiber_met,
    "water_goal_met": _r_water_met, "steps_goal_met": _r_steps_met, "workout_done": _r_workout_done,
    "planned_rest": _r_planned_rest, "sleep_good": _r_sleep_good, "recovery_good": _r_recovery_good,
    "fasting_goal_met": _r_fasting_met, "calories_over": _r_calories_over, "calories_under": _r_calories_under,
    "protein_low": _r_protein_low, "sleep_short": _r_sleep_short, "sleep_below_usual": _r_sleep_below_usual,
    "recovery_low": _r_recovery_low, "steps_low": _r_steps_low, "water_low": _r_water_low,
    "improve_protein": _r_improve_protein, "improve_fiber": _r_improve_fiber, "improve_steps": _r_improve_steps,
    "improve_water": _r_improve_water, "improve_sleep": _r_improve_sleep, "recovery_focus": _r_recovery_day,
    "lighter_tomorrow": _r_lighter_tomorrow, "reduce_nutrient_vs_average": _r_reduce_avg,
    "reduce_nutrient": _r_reduce_goal, "reduce_pattern": _r_reduce_pattern,
}


# ---------------------------------------------------------------------------------------------
# Pattern engine
# ---------------------------------------------------------------------------------------------

def _exposure_fn(name, inputs, cfg, as_of):
    """day -> True/False (exposed/unexposed) or None (not measurable that day)."""
    pc = cfg["patterns"]
    tz = inputs.get("time_zone", "UTC")
    targets = inputs.get("targets") or {}
    if name == "late_intense_workout":
        t = cfg["training_load"]
        late = set()
        for s in sessions(inputs.get("workouts") or [], tz, cfg):
            end_late = local_day_of(s["end_ms"], tz) != s["day"] or local_minutes_of(s["end_ms"], tz) >= t["late_hour"] * 60
            intense = s["intensity"] >= t["intense_min_intensity"] or (
                not s["has_effort"] and s["minutes"] >= t["intense_min_minutes_without_effort"])
            if end_late and intense:
                late.add(s["day"])
        return lambda d: d in late
    if name == "high_load":
        loads = daily_loads(inputs.get("workouts") or [], tz, cfg)
        return lambda d: _load_raw(loads, d, cfg)["category"] == "high"
    if name == "water_goal_met":
        water, goal = inputs.get("water_ml") or {}, targets.get("water_ml")
        return lambda d: None if not _pos(goal) or not _pos(water.get(d)) else water[d] >= goal
    if name == "protein_target_met":
        food, goal = inputs.get("nutrition") or {}, targets.get("protein_g")

        def f(d):
            x = food.get(d)
            if not _pos(goal) or x is None or not _pos(x.get("calories")) or x.get("protein_g") is None:
                return None
            return x["protein_g"] >= goal
        return f
    if name == "short_sleep":
        nights = sleep_series(inputs, cfg)
        start = add_days(as_of, -pc["window_days"])
        vals = [v for _, v in _values(nights, start, 0, pc["window_days"] - 1)]
        if len(vals) < cfg["metrics"]["sleep"]["min_points"]:
            return lambda d: None
        cut = mean(vals) - pc["short_sleep_margin_min"]
        return lambda d: None if d not in nights else nights[d] < cut
    raise ValueError("unknown exposure %r" % (name,))


def _outcome_series(name, inputs, cfg):
    if name == "sleep_minutes":
        return sleep_series(inputs, cfg)
    if name == "recovery_score":
        return inputs.get("recovery_scores") or {}
    if name == "strength_volume":
        return dict((d, v) for d, v in (inputs.get("strength_volume") or {}).items() if _pos(v))
    if name == "steps":
        return (inputs.get("series") or {}).get("steps") or {}
    raise ValueError("unknown outcome %r" % (name,))


def patterns(inputs, as_of_day, cfg):
    """Exposure/outcome pairs over exposure days as_of-window .. as_of-1: Welch t and Cohen's d."""
    pc = cfg["patterns"]
    out = []
    for pair in pc["pairs"]:
        expo = _exposure_fn(pair["exposure"], inputs, cfg, as_of_day)
        outc = _outcome_series(pair["outcome"], inputs, cfg)
        partial = pair["outcome"] in pc["partial_today_outcomes"]
        ex, un = [], []
        for k in range(pc["window_days"], 0, -1):
            d = add_days(as_of_day, -k)
            o = add_days(d, pair["lag_days"])
            if days_between(o, as_of_day) < (1 if partial else 0):
                continue
            e = expo(d)
            y = outc.get(o)
            if e is None or y is None:
                continue
            (ex if e else un).append(float(y))
        item = {"id": pair["id"], "status": "insufficient", "n_exposed": len(ex), "n_unexposed": len(un),
                "needed": pc["min_group"], "mean_exposed": None, "mean_unexposed": None, "diff": None, "t": None,
                "d": None, "surfaced": False, "text": None, "review_category": pair.get("review_category")}
        if len(ex) >= pc["min_group"] and len(un) >= pc["min_group"]:
            me, mu = mean(ex), mean(un)
            ve, vu = sample_sd(ex, me) ** 2, sample_sd(un, mu) ** 2
            diff = me - mu
            se = math.sqrt(ve / len(ex) + vu / len(un))
            t = diff / se if se > 0 else None
            pooled = math.sqrt(((len(ex) - 1) * ve + (len(un) - 1) * vu) / (len(ex) + len(un) - 2))
            d = diff / pooled if pooled > 0 else None
            surfaced = t is not None and d is not None and abs(t) >= pc["min_abs_t"] and abs(d) >= pc["min_abs_d"]
            params = {"abs_diff": round_to(abs(diff), pair["decimals"]), "unit": pair["unit"],
                      "direction_word": pair["more_word"] if diff > 0 else pair["less_word"],
                      "n_exposed": len(ex), "n_unexposed": len(un)}
            item.update(status="ok", mean_exposed=round_to(me, 1), mean_unexposed=round_to(mu, 1),
                        diff=round_to(diff, 1), t=round_to(t, 2), d=round_to(d, 2), surfaced=surfaced,
                        text=fill(pair["template"], params))
        out.append(item)
    return out


# ---------------------------------------------------------------------------------------------
# AI: payload, prompt, validation
# ---------------------------------------------------------------------------------------------

def canonical_json(obj):
    """Compact JSON with sorted keys, used for the prompt payload so both platforms send identical bytes.
    Numbers: integral -> integer digits; otherwise at most 2 decimals with trailing zeros removed."""
    if obj is None:
        return "null"
    if obj is True:
        return "true"
    if obj is False:
        return "false"
    if isinstance(obj, (int, float)):
        x = round_to(float(obj), 2)
        if x == math.floor(x):
            return str(int(x))
        s = "%.2f" % x
        return s.rstrip("0").rstrip(".")
    if isinstance(obj, str):
        out = ['"']
        for ch in obj:
            c = ord(ch)
            if ch == '"':
                out.append('\\"')
            elif ch == "\\":
                out.append("\\\\")
            elif ch == "\n":
                out.append("\\n")
            elif ch == "\r":
                out.append("\\r")
            elif ch == "\t":
                out.append("\\t")
            elif c < 0x20:
                out.append("\\u%04x" % c)
            else:
                out.append(ch)
        out.append('"')
        return "".join(out)
    if isinstance(obj, list):
        return "[" + ",".join(canonical_json(v) for v in obj) + "]"
    if isinstance(obj, dict):
        return "{" + ",".join(canonical_json(k) + ":" + canonical_json(obj[k]) for k in sorted(obj)) + "}"
    raise TypeError("not JSON: %r" % (obj,))


def ai_payload(summary):
    """Only derived values: no raw samples, timestamps, dates or names."""
    out = {}
    r = summary.get("recovery")
    if r is not None:
        if r["status"] == "ok":
            out["recovery"] = {"status": "ok", "score": r["score"], "label": r["label_text"],
                               "confidence": r["confidence"], "training_load": r["load"]["label"],
                               "signals": [{"text": s["text"], "impact": s["impact"]} for s in r["positives"] + r["negatives"]]}
        else:
            out["recovery"] = {"status": r["status"], "collecting": r["collecting"]}
    h = summary.get("health_age")
    if h is not None:
        if h["status"] == "ok":
            sec = {"status": "ok", "actual_age": h["actual_age"], "health_age": h["health_age"],
                   "difference": h["difference"], "confidence": h["confidence"],
                   "markers": [{"id": m["id"], "offset_years": m["offset_years"],
                                "contribution_years": m["contribution_years"]} for m in h["markers"] if m["available"]]}
            p = summary.get("health_age_pace")
            if p is not None and p["status"] == "ok":
                sec["pace"], sec["pace_direction"] = p["pace"], p["direction"]
            out["health_age"] = sec
        else:
            out["health_age"] = {"status": h["status"], "collecting": h["collecting"]}
    v = summary.get("daily_review")
    if v is not None:
        out["daily_review"] = {"day_score": v["day_score"],
                               "areas": [{"id": a["id"], "score": a["score"]} for a in v["areas"] if a["included"]],
                               "not_logged": [i["params"]["area"] for i in v["not_logged"]]}
        for c in REVIEW_CATEGORIES:
            out["daily_review"][c] = [i["text"] for i in v[c]]
    out["patterns"] = [{"id": p["id"], "text": p["text"], "n_exposed": p["n_exposed"], "n_unexposed": p["n_unexposed"]}
                       for p in (summary.get("patterns") or []) if p.get("surfaced")]
    return out


def load_prompts(path=PROMPT_PATH):
    """Fenced blocks of ai_explain.md under their '## ' headings."""
    text = open(path, encoding="utf-8").read()
    blocks, heading, i = {}, None, 0
    lines = text.split("\n")
    while i < len(lines):
        line = lines[i]
        if line.startswith("## "):
            heading = line[3:].strip()
        elif line.startswith("```") and heading is not None:
            j = i + 1
            while not lines[j].startswith("```"):
                j += 1
            blocks[heading] = "\n".join(lines[i + 1:j])
            i = j
        i += 1
    return {"cloud": blocks["System prompt (cloud)"], "local": blocks["System prompt (compact, on-device)"],
            "user": blocks["User template"]}


def build_prompt(kind, payload, variant, cfg, prompts=None):
    """{system, user}. The user template gets {task} then {payload} by plain string replacement."""
    if kind not in AI_KINDS or variant not in AI_VARIANTS:
        raise ValueError("bad kind/variant")
    prompts = prompts or load_prompts()
    section = {kind: payload.get(kind)}
    if kind == "daily_review":
        section["patterns"] = payload.get("patterns") or []
    user = prompts["user"].replace("{task}", cfg["ai"]["tasks"][kind]).replace("{payload}", canonical_json(section))
    return {"system": prompts[variant], "user": user}


def utf16_len(s):
    return len(s.encode("utf-16-le")) // 2


def extract_json_object(text):
    """Strip a ``` fence when present, then the first balanced {...} (string- and escape-aware)."""
    t = text
    f = t.find("```")
    if f >= 0:
        nl = t.find("\n", f)
        end = t.find("```", nl + 1) if nl >= 0 else -1
        if nl >= 0 and end >= 0:
            t = t[nl + 1:end]
    start = t.find("{")
    if start < 0:
        return None
    depth, in_str, esc = 0, False, False
    for i in range(start, len(t)):
        ch = t[i]
        if in_str:
            if esc:
                esc = False
            elif ch == "\\":
                esc = True
            elif ch == '"':
                in_str = False
        elif ch == '"':
            in_str = True
        elif ch == "{":
            depth += 1
        elif ch == "}":
            depth -= 1
            if depth == 0:
                return t[start:i + 1]
    return None


def _norm_number(token):
    return canonical_json(round_to(float(token.replace(",", "")), 2))


def _payload_numbers(obj, out):
    if isinstance(obj, bool) or obj is None:
        return
    if isinstance(obj, (int, float)):
        for d in (2, 1, 0):
            out.add(canonical_json(round_to(abs(float(obj)), d)))
    elif isinstance(obj, str):
        for m in NUMBER_RE.finditer(obj):
            out.add(_norm_number(m.group(0)))
    elif isinstance(obj, list):
        for v in obj:
            _payload_numbers(v, out)
    elif isinstance(obj, dict):
        for v in obj.values():
            _payload_numbers(v, out)


def validate_ai_output(text, payload, cfg):
    """{ok, errors, output}. The explanation is shown only when ok; otherwise the deterministic text is used."""
    a = cfg["ai"]
    raw = extract_json_object(text or "")
    try:
        obj = json.loads(raw) if raw is not None else None
    except ValueError:
        obj = None
    if not isinstance(obj, dict):
        return {"ok": False, "errors": ["parse_error"], "output": None}
    head, bullets = obj.get("headline"), obj.get("bullets")
    if not isinstance(head, str) or not isinstance(bullets, list) or not all(isinstance(b, str) for b in bullets):
        return {"ok": False, "errors": ["bad_shape"], "output": None}
    head = head.strip()
    bullets = [b.strip() for b in bullets]
    errors = []
    if not 1 <= utf16_len(head) <= a["headline_max"]:
        errors.append("headline_length")
    if not 1 <= len(bullets) <= a["bullets_max"]:
        errors.append("bullet_count")
    if any(not 1 <= utf16_len(b) <= a["bullet_max"] for b in bullets):
        errors.append("bullet_length")
    allowed = set(canonical_json(float(x)) for x in a["allowed_numbers"])
    _payload_numbers(payload, allowed)
    for s in [head] + bullets:
        if any(_norm_number(m.group(0)) not in allowed for m in NUMBER_RE.finditer(s)):
            errors.append("unknown_number")
            break
    lower = " ".join([head] + bullets).lower()
    if any(term in lower for term in a["blocked_terms"]):
        errors.append("blocked_term")
    ok = not errors
    return {"ok": ok, "errors": errors, "output": {"headline": head, "bullets": bullets} if ok else None}


# ---------------------------------------------------------------------------------------------
# Vector runner (compact encodings used only by the test vectors)
# ---------------------------------------------------------------------------------------------

def decode_series(x):
    """{"start": day, "values": [v|null, ...]} -> {day: v}; any other dict passes through."""
    if isinstance(x, dict) and set(x) == {"start", "values"}:
        return dict((add_days(x["start"], i), v) for i, v in enumerate(x["values"]) if v is not None)
    return x


def decode_nights(x):
    """{"start": day, "nights": [[asleep_min, start_ms, end_ms]|null, ...]} -> {wake_day: {...}}."""
    if isinstance(x, dict) and set(x) == {"start", "nights"}:
        return dict((add_days(x["start"], i), {"asleep_min": n[0], "start_ms": n[1], "end_ms": n[2]})
                    for i, n in enumerate(x["nights"]) if n is not None)
    return x


def decode_inputs(inp):
    out = dict(inp)
    out["series"] = dict((k, decode_series(v)) for k, v in (inp.get("series") or {}).items())
    if "sleep" in inp:
        out["sleep"] = decode_nights(inp["sleep"])
    for k in ("water_ml", "fasting_hours", "strength_volume", "recovery_scores"):
        if k in inp:
            out[k] = decode_series(inp[k])
    return out


def run_case(function, inp, cfg):
    if function == "baseline":
        return baseline(decode_series(inp["series"]), inp["day"], cfg["metrics"][inp["metric"]])
    if function == "trend":
        return trend(decode_series(inp["series"]), inp["day"], cfg["metrics"][inp["metric"]])
    if function == "overnight_value":
        return overnight_value(inp["samples"], inp["night"], inp.get("fallback_value"))
    if function == "training_load":
        return training_load(inp["workouts"], inp["day"], inp["time_zone"], cfg)
    if function == "recovery":
        return recovery(decode_inputs(inp["inputs"]), inp["day"], cfg)
    if function == "health_age":
        return health_age(decode_inputs(inp["inputs"]), inp["as_of"], inp["profile"], cfg)
    if function == "health_age_pace":
        return health_age_pace(decode_inputs(inp["inputs"]), inp["as_of"], inp["profile"], cfg)
    if function == "daily_review":
        return daily_review(decode_inputs(inp["inputs"]), inp["day"], cfg)
    if function == "patterns":
        return patterns(decode_inputs(inp["inputs"]), inp["as_of"], cfg)
    if function == "ai_summary":
        payload = ai_payload(inp["summary"])
        return {"payload": payload, "prompt": build_prompt(inp["kind"], payload, inp["variant"], cfg),
                "validations": [validate_ai_output(t, payload, cfg) for t in inp["outputs"]]}
    raise ValueError("unknown function %r" % (function,))
