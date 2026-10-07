#!/usr/bin/env python3
"""Ayuvo health analytics reference implementation (Python 3 stdlib only).

The iOS (ios/.../Services/Analytics) and Android (android/.../data/analytics) engines must produce what this module
produces for every case in shared/analytics/test-vectors, within config["tolerance"] (docs/health-analytics.md).
Pure functions over plain inputs and shared/analytics/analytics_config.json: no clock, no storage, no platform APIs.

This layer sits on top of the raw mirror (shared/health), the derived metrics (shared/derived) and Insights
(shared/insights). It adds robust personal baselines, trends, HRV from beat-to-beat intervals, sleep need and debt,
EWMA training load, heart rate recovery, Recovery v2, a multi-signal anomaly state, correlations with FDR control,
personal response estimates, energy expenditure, VO2 max trends, MET intensity and a small per-user ridge forecast.
Every result carries a status (config["statuses"]), a classification (config["classifications"]) and a confidence
in [0, 1]. A missing value is None (null), never 0, unless the text says the zero is a true zero.

Portability rules (same as scripts/insights_reference.py):
  * rounding is round_to(x, d) = floor(x * 10^d + 0.5) / 10^d, never banker's rounding;
  * sums run in list / chronological order; mean, sample SD, median, percentiles and MAD are written out;
  * transcendental functions (exp, log, sqrt) are only used in closed-form expressions, never inside an iteration
    whose length depends on the result (lesson from shared/vitals FastICA);
  * days are "YYYY-MM-DD"; instants are epoch milliseconds; wall-clock questions use inputs["time_zone"].

Common encodings:
  series   {day: value}; vectors may use {"start": day, "values": [v | null, ...]} (decode_series)
  contexts {day: "wearable" | "provider" | "manual" | "derived" | "camera"} (missing day -> "wearable")
  sources  {day: source id} (used only to notice a source change)
  nights   {wake_day: {asleep_min, in_bed_min, efficiency, waso_min, bedtime_clock, wake_clock, midpoint_clock,
            nap_min}} main sleep episode per wake day (clock = minutes after 12:00 on the day before the wake day)
  workouts [{start_ms, end_ms, effort (1-10 or null), trimp (or null), activity (key or null)}]
"""

import datetime as _dt
import json
import math
import os
import re
from zoneinfo import ZoneInfo

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
CONFIG_PATH = os.path.join(ROOT, "shared", "analytics", "analytics_config.json")
POLICY_PATH = os.path.join(ROOT, "shared", "health", "source_policy.json")
DAY_RE = re.compile("^([0-9][0-9][0-9][0-9])-([0-9][0-9])-([0-9][0-9])$")
MINUTE = 60000


def load_config(path=CONFIG_PATH):
    with open(path, encoding="utf-8") as fh:
        return json.load(fh)


def load_policy(path=POLICY_PATH):
    with open(path, encoding="utf-8") as fh:
        return json.load(fh)


# ---------------------------------------------------------------------------------------------
# Numbers and days
# ---------------------------------------------------------------------------------------------

def round_to(x, decimals):
    if x is None:
        return None
    scale = 10 ** decimals
    v = math.floor(x * scale + 0.5) / scale
    return 0.0 if v == 0 else v


def round_int(x):
    return None if x is None else int(math.floor(x + 0.5))


def clamp(x, lo, hi):
    return lo if x < lo else (hi if x > hi else x)


def total(values):
    t = 0.0
    for v in values:
        t += v
    return t


def mean(values):
    return total(values) / len(values)


def sample_sd(values, m):
    """Sample standard deviation (n - 1). Needs n >= 2."""
    t = 0.0
    for v in values:
        t += (v - m) * (v - m)
    return math.sqrt(t / (len(values) - 1))


def median(values):
    s = sorted(values)
    n = len(s)
    if n % 2 == 1:
        return s[n // 2]
    return (s[n // 2 - 1] + s[n // 2]) / 2.0


def percentile(values, p):
    """Linear interpolation between closest ranks (Hyndman & Fan type 7, the spreadsheet default)."""
    s = sorted(values)
    n = len(s)
    if n == 1:
        return float(s[0])
    h = (n - 1) * p / 100.0
    lo = int(math.floor(h))
    hi = lo + 1 if lo + 1 < n else n - 1
    return s[lo] + (h - lo) * (s[hi] - s[lo])


def mad(values):
    """Median absolute deviation (unscaled)."""
    m = median(values)
    return median([abs(v - m) for v in values])


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


def window_days(day, first_offset, last_offset):
    """Days day+first_offset .. day+last_offset inclusive, oldest first."""
    return [add_days(day, k) for k in range(first_offset, last_offset + 1)]


def ln(x):
    return math.log(x)


def atanh(r):
    return 0.5 * math.log((1.0 + r) / (1.0 - r))


def tanh(z):
    e = math.exp(2.0 * z)
    return (e - 1.0) / (e + 1.0)


def normal_cdf(x):
    """Standard normal CDF via Abramowitz & Stegun 7.1.26 (|error of erf| <= 1.5e-7)."""
    z = abs(x) / math.sqrt(2.0)
    t = 1.0 / (1.0 + 0.3275911 * z)
    poly = t * (0.254829592 + t * (-0.284496736 + t * (1.421413741 + t * (-1.453152027 + t * 1.061405429))))
    erf = 1.0 - poly * math.exp(-z * z)
    return 0.5 * (1.0 + erf) if x >= 0 else 0.5 * (1.0 - erf)


# ---------------------------------------------------------------------------------------------
# Status, confidence, provenance
# ---------------------------------------------------------------------------------------------

def worst_status(statuses, cfg):
    rank = cfg["status_rank"]
    best = "VALID"
    for s in statuses:
        if rank[s] > rank[best]:
            best = s
    return best


def confidence(classification, coverage, n, target_n, context, source_changed, cfg):
    """cap(classification) x sqrt(coverage factor x sample factor) x context weight x source-change factor."""
    c = cfg["confidence"]
    f_cov = clamp(coverage / c["target_coverage"], 0.0, 1.0)
    f_n = clamp(n / float(target_n), 0.0, 1.0)
    f_ctx = c["context_weight"].get(context or "wearable", 1.0)
    f_src = c["source_change_factor"] if source_changed else 1.0
    cap = cfg["classifications"][classification]["validity_cap"]
    return cap * math.sqrt(f_cov * f_n) * f_ctx * f_src


def confidence_band(conf, cfg):
    for b in cfg["confidence"]["bands"]:
        if conf >= b["min"]:
            return b["id"]
    return "low"


def status_for(conf, cfg):
    return "VALID" if conf >= cfg["confidence"]["low_confidence_below"] else "LOW_CONFIDENCE"


def canonical(obj):
    """Compact JSON with sorted keys. Integral numbers -> digits; others -> %.6f with trailing zeros removed."""
    if obj is None:
        return "null"
    if obj is True:
        return "true"
    if obj is False:
        return "false"
    if isinstance(obj, (int, float)):
        x = float(obj)
        if x == math.floor(x) and abs(x) < 1e15:
            return "%d" % int(x)
        s = "%.6f" % round_to(x, 6)
        s = s.rstrip("0").rstrip(".")
        return "0" if s in ("-0", "") else s
    if isinstance(obj, str):
        out = ['"']
        for ch in obj:
            c = ord(ch)
            if ch == '"':
                out.append('\\"')
            elif ch == "\\":
                out.append("\\\\")
            elif c < 0x20:
                out.append("\\u%04x" % c)
            else:
                out.append(ch)
        out.append('"')
        return "".join(out)
    if isinstance(obj, list):
        return "[" + ",".join(canonical(v) for v in obj) + "]"
    if isinstance(obj, dict):
        return "{" + ",".join(canonical(k) + ":" + canonical(obj[k]) for k in sorted(obj)) + "}"
    raise TypeError("not JSON: %r" % (obj,))


def fnv1a64(text):
    h = 0xcbf29ce484222325
    for b in text.encode("utf-8"):
        h ^= b
        h = (h * 0x100000001b3) & 0xFFFFFFFFFFFFFFFF
    return "%016x" % h


def input_hash(inp, cfg):
    """Stable hash of a canonical input object: lets a platform skip days whose inputs did not change."""
    return {"canonical": canonical(inp["value"]), "hash": fnv1a64(canonical(inp["value"]))}


# ---------------------------------------------------------------------------------------------
# Source policy (per metric, per day) — Phase 0 data fix used by both rollup engines
# ---------------------------------------------------------------------------------------------

def _strip_gh(source):
    return source[len("google_health:"):] if source.startswith("google_health:") else source


def source_select(inp, cfg):
    """One day of discrete readings for one metric -> the value the rollup should use.

    input: metric, policy (shared/health/source_policy.json), rows [{id, source, origin, device_type, t_ms, value,
    count}] (deleted rows removed). Strategies:
      average_all                  count-weighted mean of every row (the old rule; metrics not listed in the policy)
      single_best_source_per_day   after cross-origin de-duplication, pick one source: wearable device types first,
                                   then most samples (sum of count), then the smaller source id; mean of its rows
    Cross-origin de-duplication drops a Google Health row (origin 3, source "google_health:<pkg>") when a row from
    another origin with source <pkg> lies within dedup_window_s and differs by at most dedup_value_tolerance
    (absolute or relative, whichever is larger): the same reading arrived twice."""
    pol = inp["policy"]
    strategy = pol["metrics"].get(inp["metric"], pol["default_strategy"])
    rows = sorted(inp.get("rows") or [], key=lambda r: (r["t_ms"], r["id"]))
    rows = [r for r in rows if r.get("value") is not None]
    out = {"strategy": strategy, "value": None, "min": None, "max": None, "count": 0, "source": None,
           "dropped_duplicates": 0, "sources": sorted(set(r["source"] for r in rows))}
    if not rows:
        return out
    win = pol["dedup_window_s"] * 1000
    tol_abs, tol_rel = pol["dedup_value_tolerance"]
    kept, dropped = [], 0
    for r in rows:
        if r["origin"] == 3:
            dup = False
            for o in rows:
                if o["origin"] != 3 and o["source"] == _strip_gh(r["source"]) and abs(o["t_ms"] - r["t_ms"]) <= win:
                    if abs(o["value"] - r["value"]) <= max(tol_abs, tol_rel * abs(o["value"])):
                        dup = True
                        break
            if dup:
                dropped += 1
                continue
        kept.append(r)
    out["dropped_duplicates"] = dropped
    chosen = kept
    if strategy == "single_best_source_per_day":
        by = {}
        for r in kept:
            by.setdefault(r["source"], []).append(r)
        wearable = set(pol["wearable_device_types"])

        def key(src):
            rs = by[src]
            is_wear = any(r.get("device_type") in wearable for r in rs)
            n = 0
            for r in rs:
                n += max(1, r.get("count") or 1)
            return (0 if is_wear else 1, -n, src)
        src = sorted(by, key=key)[0]
        chosen = by[src]
        out["source"] = src
    ws, w = 0.0, 0
    lo, hi = None, None
    for r in chosen:
        n = max(1, r.get("count") or 1)
        ws += r["value"] * n
        w += n
        lo = r["value"] if lo is None or r["value"] < lo else lo
        hi = r["value"] if hi is None or r["value"] > hi else hi
    out.update(value=ws / w, min=lo, max=hi, count=w)
    out["value"] = round_to(out["value"], 6)
    return out


# ---------------------------------------------------------------------------------------------
# Unit normalization (canonical units inside, conversions at the boundaries)
# ---------------------------------------------------------------------------------------------

UNIT_FACTORS = {
    # (from, to): factor; value_to = value_from * factor (temperature is affine, handled below)
    ("lb", "kg"): 0.45359237, ("kg", "lb"): 1.0 / 0.45359237,
    ("ft", "m"): 0.3048, ("m", "ft"): 1.0 / 0.3048, ("in", "m"): 0.0254, ("m", "in"): 1.0 / 0.0254,
    ("cm", "m"): 0.01, ("m", "cm"): 100.0, ("km", "m"): 1000.0, ("m", "km"): 0.001,
    ("mi", "m"): 1609.344, ("m", "mi"): 1.0 / 1609.344,
    ("kJ", "kcal"): 1.0 / 4.184, ("kcal", "kJ"): 4.184,
    ("min", "s"): 60.0, ("s", "min"): 1.0 / 60.0, ("h", "s"): 3600.0, ("s", "h"): 1.0 / 3600.0,
    ("h", "min"): 60.0, ("min", "h"): 1.0 / 60.0, ("ms", "s"): 0.001, ("s", "ms"): 1000.0,
    ("L", "mL"): 1000.0, ("mL", "L"): 0.001, ("g", "mg"): 1000.0, ("mg", "g"): 0.001,
    ("fraction", "%"): 100.0, ("%", "fraction"): 0.01,
}


def unit_convert(inp, cfg):
    """input: value, from, to. Canonical units: kg, m, kcal, s, mL, degC, % (0-100), bpm, ms, mL/kg/min."""
    v, a, b = inp["value"], inp["from"], inp["to"]
    if v is None:
        return {"value": None, "ok": True}
    if a == b:
        return {"value": round_to(float(v), 9), "ok": True}
    if (a, b) == ("degF", "degC"):
        return {"value": round_to((v - 32.0) * 5.0 / 9.0, 9), "ok": True}
    if (a, b) == ("degC", "degF"):
        return {"value": round_to(v * 9.0 / 5.0 + 32.0, 9), "ok": True}
    f = UNIT_FACTORS.get((a, b))
    if f is None:
        return {"value": None, "ok": False}
    return {"value": round_to(v * f, 9), "ok": True}


# ---------------------------------------------------------------------------------------------
# Personal baseline engine (robust: median / MAD)
# ---------------------------------------------------------------------------------------------

def _robust(series, day, window, min_points, spread_floor, cfg, contexts=None, allowed=None, sources=None,
            recent="day"):
    """Raw (unrounded) baseline of `series` over the `window` days before `day` (day excluded).

    Only days whose context is in `allowed` (default: everything except "camera") enter the baseline; today's value
    is used whatever its context, and its context lowers the confidence. Missing days are skipped, never zero."""
    contexts = contexts or {}
    allowed = allowed or ("wearable", "provider", "manual", "derived")
    vals, srcs = [], []
    for d in window_days(day, -window, -1):
        v = series.get(d)
        if v is None or contexts.get(d, "wearable") not in allowed:
            continue
        vals.append(float(v))
        if sources and sources.get(d) is not None:
            srcs.append(sources[d])
    if recent == "median7":
        last = [float(series[d]) for d in window_days(day, -6, 0) if series.get(d) is not None]
        today = median(last) if last else None
    else:
        today = None if series.get(day) is None else float(series[day])
    n = len(vals)
    b = {"window_days": window, "n": n, "needed": min_points, "coverage": n / float(window), "value": today,
         "context": contexts.get(day, "wearable") if today is not None else None, "median": None, "mad": None,
         "spread": None, "p10": None, "p90": None, "deviation": None, "pct": None, "z": None,
         "source_changed": False}
    if n < min_points:
        return b
    med = median(vals)
    m = mad(vals)
    p_lo, p_hi = cfg["baseline"]["percentiles"]
    b.update(median=med, mad=m, spread=max(cfg["baseline"]["mad_scale"] * m, spread_floor),
             p10=percentile(vals, p_lo), p90=percentile(vals, p_hi))
    if sources and srcs and sources.get(day) is not None:
        counts = {}
        for s in srcs:
            counts[s] = counts.get(s, 0) + 1
        top = sorted(counts, key=lambda s: (-counts[s], s))[0]
        b["source_changed"] = sources[day] != top
    if today is not None:
        b["deviation"] = today - med
        b["pct"] = None if med == 0 else (today - med) / abs(med) * 100.0
        b["z"] = (today - med) / b["spread"]
    return b


def _z_dir(z, direction):
    if direction == "higher_better":
        return z
    if direction == "lower_better":
        return -z
    return -abs(z)


def baseline(inp, cfg):
    """input: series, day, metric, window (optional, one of config baseline.windows), contexts, sources.

    Output: status (VALID / LOW_CONFIDENCE / INSUFFICIENT_HISTORY / NO_DATA), n, coverage, median, mad, spread,
    p10, p90, value, deviation, pct, z (robust: (value - median) / max(1.4826 MAD, spread_floor)), z_dir (positive =
    better for higher/lower-better metrics, never positive for band metrics), confidence, classification."""
    m = cfg["metrics"][inp["metric"]]
    window = inp.get("window") or 28
    if window not in cfg["baseline"]["windows"]:
        raise ValueError("window %r not in config baseline.windows" % (window,))
    b = _robust(inp["series"], inp["day"], window, cfg["baseline"]["min_points"][str(window)], m["spread_floor"], cfg,
                inp.get("contexts"), None, inp.get("sources"), inp.get("recent", "day"))
    return _baseline_out(b, m, cfg)


def _baseline_out(b, m, cfg):
    if b["median"] is None:
        status, conf = "INSUFFICIENT_HISTORY", 0.0
    elif b["value"] is None:
        status, conf = "NO_DATA", 0.0
    else:
        conf = confidence(m["classification"], b["coverage"], b["n"], cfg["confidence"]["target_n"], b["context"],
                          b["source_changed"], cfg)
        status = status_for(conf, cfg)
    d = m["decimals"] + 1
    return {"status": status, "window_days": b["window_days"], "n": b["n"], "needed": b["needed"],
            "coverage": round_to(b["coverage"], 2), "median": round_to(b["median"], d), "mad": round_to(b["mad"], d),
            "spread": round_to(b["spread"], d), "p10": round_to(b["p10"], d), "p90": round_to(b["p90"], d),
            "value": round_to(b["value"], d), "context": b["context"], "deviation": round_to(b["deviation"], d),
            "pct": round_to(b["pct"], 1), "z": round_to(b["z"], 2),
            "z_dir": round_to(_z_dir(b["z"], m["direction"]), 2) if b["z"] is not None else None,
            "source_changed": b["source_changed"], "confidence": round_to(conf, 2),
            "classification": "PERSONALIZED_STATISTICAL"}


def baselines(inp, cfg):
    """Every configured window at once: {"7": baseline, "14": ..., "90": ...}."""
    out = {}
    for w in cfg["baseline"]["windows"]:
        q = dict(inp)
        q["window"] = w
        out[str(w)] = baseline(q, cfg)
    return out


# ---------------------------------------------------------------------------------------------
# Trend engine
# ---------------------------------------------------------------------------------------------

def theil_sen(points):
    """Median of pairwise slopes over points [(x, y)] with distinct x; intercept = median(y - slope x)."""
    slopes = []
    for i in range(len(points)):
        for j in range(i + 1, len(points)):
            dx = points[j][0] - points[i][0]
            if dx != 0:
                slopes.append((points[j][1] - points[i][1]) / dx)
    if not slopes:
        return None, None, []
    b = median(slopes)
    a = median([y - b * x for x, y in points])
    return b, a, slopes


def ewma(values, span):
    """Exponentially weighted mean of values (oldest first), alpha = 2 / (span + 1), seeded with the first value."""
    alpha = 2.0 / (span + 1.0)
    e = None
    for v in values:
        e = v if e is None else alpha * v + (1.0 - alpha) * e
    return e


def cusum(z_values, k, h):
    """Two-sided Page CUSUM on standardized values: first index where S+ or S- exceeds h, and its direction."""
    sp, sn = 0.0, 0.0
    for i, z in enumerate(z_values):
        sp = max(0.0, sp + z - k)
        sn = max(0.0, sn - z - k)
        if sp > h:
            return i, "up"
        if sn > h:
            return i, "down"
    return None, None


def trend(inp, cfg):
    """input: series, day, metric, window (optional). Theil-Sen slope over the window (day included) as % of the
    window median per week; 7-day median vs the rest of the window; EWMA; CUSUM change point (n >= 28)."""
    t = cfg["trend"]
    m = cfg["metrics"][inp["metric"]]
    window = inp.get("window") or t["window_days"]
    series = inp["series"]
    days = window_days(inp["day"], -(window - 1), 0)
    pts = [(float(i), float(series[d])) for i, d in enumerate(days) if series.get(d) is not None]
    out = {"status": "INSUFFICIENT_DATA", "label": "INSUFFICIENT_DATA", "window_days": window, "sample_count": len(pts),
           "coverage": round_to(len(pts) / float(window), 2), "slope_per_day": None, "pct_per_week": None,
           "recent_median": None, "prior_median": None, "pct_change": None, "ewma": None, "latest_z": None,
           "change_point_day": None, "change_direction": None, "confidence": 0.0}
    if len(pts) < t["min_points"]:
        return out
    ys = [p[1] for p in pts]
    med = median(ys)
    slope, _, _ = theil_sen(pts)
    pct_week = None if med == 0 else slope * 7.0 / abs(med) * 100.0
    recent = [p[1] for p in pts if p[0] >= window - t["recent_days"]]
    prior = [p[1] for p in pts if p[0] < window - t["recent_days"]]
    rm = median(recent) if recent else None
    pm = median(prior) if prior else None
    spread = max(cfg["baseline"]["mad_scale"] * mad(ys), m["spread_floor"])
    latest_z = None
    if series.get(inp["day"]) is not None and len(prior) >= 1:
        others = [p[1] for p in pts if p[0] != window - 1]
        o_spread = max(cfg["baseline"]["mad_scale"] * mad(others), m["spread_floor"])
        latest_z = (float(series[inp["day"]]) - median(others)) / o_spread
    cp_day, cp_dir = None, None
    c = t["cusum"]
    if len(pts) >= c["min_points"]:
        ref = [p[1] for p in pts[:c["reference_points"]]]
        r_med = median(ref)
        r_spread = max(cfg["baseline"]["mad_scale"] * mad(ref), m["spread_floor"])
        idx, cp_dir = cusum([(p[1] - r_med) / r_spread for p in pts[c["reference_points"]:]], c["k"], c["h"])
        if idx is not None:
            cp_day = days[int(pts[c["reference_points"] + idx][0])]
    if latest_z is not None and abs(latest_z) >= t["unusual_abs_z"]:
        label = "UNUSUAL"
    elif pct_week is None or abs(pct_week) < m["trend_stable_pct_per_week"]:
        label = "STABLE"
    elif m["direction"] == "band":
        label = "CHANGING"
    elif (pct_week > 0) == (m["direction"] == "higher_better"):
        label = "IMPROVING"
    else:
        label = "DECLINING"
    conf = confidence(m["classification"], len(pts) / float(window), len(pts), window, "wearable", False, cfg)
    d = m["decimals"] + 1
    out.update(status=status_for(conf, cfg), label=label, slope_per_day=round_to(slope, 4),
               pct_per_week=round_to(pct_week, 2), recent_median=round_to(rm, d), prior_median=round_to(pm, d),
               pct_change=round_to(None if rm is None or pm is None or pm == 0 else (rm - pm) / abs(pm) * 100.0, 1),
               ewma=round_to(ewma(ys, t["ewma_span_days"]), d), latest_z=round_to(latest_z, 2),
               change_point_day=cp_day, change_direction=cp_dir, confidence=round_to(conf, 2))
    out["median"] = round_to(med, d)
    out["spread"] = round_to(spread, d)
    return out


# ---------------------------------------------------------------------------------------------
# HRV
# ---------------------------------------------------------------------------------------------

def hrv_rr(inp, cfg):
    """Time-domain HRV from one beat-to-beat series (Apple HKHeartbeatSeriesSample).

    input: ibis [[ibi_ms, preceded_by_gap]]. Quality filter (300-2000 ms), artifact rejection against the median of
    the in-range intervals within +/- 2 beats (> 20% deviation rejected), no interpolation. Successive differences
    only between two accepted, consecutive intervals with no gap between them. More than 5% artifacts ->
    INVALID_INPUT; fewer than min_beats accepted intervals -> INSUFFICIENT_DATA."""
    rr = cfg["hrv"]["rr"]
    ibis = inp.get("ibis") or []
    out = {"status": "NO_DATA", "rmssd": None, "sdnn": None, "ln_rmssd": None, "mean_hr": None, "n_beats": len(ibis),
           "n_accepted": 0, "n_diffs": 0, "artifact_fraction": None, "classification": "SCIENTIFIC_DERIVED"}
    if not ibis:
        return out
    raw = [float(x[0]) for x in ibis]
    gap = [bool(x[1]) for x in ibis]
    in_range = [rr["min_ms"] <= v <= rr["max_ms"] for v in raw]
    h = rr["median_window"] // 2
    accepted = []
    for k in range(len(raw)):
        ok = in_range[k]
        if ok:
            local = [raw[j] for j in range(k - h, k + h + 1) if 0 <= j < len(raw) and in_range[j]]
            med = median(local)
            if abs(raw[k] - med) / med > rr["max_rel_deviation"]:
                ok = False
        accepted.append(ok)
    acc = [raw[k] for k in range(len(raw)) if accepted[k]]
    diffs = [raw[k] - raw[k - 1] for k in range(1, len(raw)) if accepted[k] and accepted[k - 1] and not gap[k]]
    rejected = len(raw) - len(acc)
    out.update(n_accepted=len(acc), n_diffs=len(diffs), artifact_fraction=round_to(rejected / float(len(raw)), 3))
    if rejected / float(len(raw)) > rr["max_artifact_fraction"]:
        out["status"] = "INVALID_INPUT"
        return out
    if len(acc) < rr["min_beats"] or len(diffs) < 2:
        out["status"] = "INSUFFICIENT_DATA"
        return out
    sq = 0.0
    for d in diffs:
        sq += d * d
    rmssd = math.sqrt(sq / len(diffs))
    m = mean(acc)
    out.update(status="VALID", rmssd=round_to(rmssd, 2), sdnn=round_to(sample_sd(acc, m), 2),
               ln_rmssd=round_to(ln(rmssd), 3) if rmssd > 0 else None, mean_hr=round_to(60000.0 / m, 1))
    return out


def hrv_rr_day(inp, cfg):
    """input: series [{start_ms, ibis}] of one day, night ({start_ms, end_ms} of last night or null).
    Day value = median RMSSD / SDNN of the VALID series, using only series that start inside the night when any
    does (context "overnight"), otherwise all VALID series (context "daytime")."""
    night = inp.get("night")
    results = []
    for s in sorted(inp.get("series") or [], key=lambda s: s["start_ms"]):
        r = hrv_rr({"ibis": s["ibis"]}, cfg)
        if r["status"] == "VALID":
            inside = night is not None and night["start_ms"] <= s["start_ms"] <= night["end_ms"]
            results.append((inside, r))
    out = {"status": "NO_DATA", "rmssd": None, "sdnn": None, "ln_rmssd": None, "n_series": 0, "context": None,
           "classification": "SCIENTIFIC_DERIVED"}
    if not inp.get("series"):
        return out
    if not results:
        out["status"] = "INSUFFICIENT_DATA"
        return out
    overnight = [r for inside, r in results if inside]
    use = overnight if overnight else [r for _, r in results]
    rm = median([r["rmssd"] for r in use])
    out.update(status="VALID", rmssd=round_to(rm, 2), sdnn=round_to(median([r["sdnn"] for r in use]), 2),
               ln_rmssd=round_to(ln(rm), 3) if rm > 0 else None, n_series=len(use),
               context="overnight" if overnight else "daytime")
    return out


def hrv_status(inp, cfg):
    """input: series (one HRV kind: SDNN on iOS, RMSSD on Android or Ayuvo RMSSD), day, kind, contexts, sources.
    Baseline (28 days), trend, and stability = coefficient of variation of ln(HRV) over the last 7 days (Plews 2013)."""
    hc = cfg["hrv"]
    series = inp["series"]
    b = baseline({"series": series, "day": inp["day"], "metric": "hrv", "window": hc["baseline_window_days"],
                  "contexts": inp.get("contexts"), "sources": inp.get("sources")}, cfg)
    t = trend({"series": series, "day": inp["day"], "metric": "hrv"}, cfg)
    lnv = [ln(float(series[d])) for d in window_days(inp["day"], -(hc["stability_days"] - 1), 0)
           if series.get(d) is not None and series[d] > 0]
    cv, ln_mean = None, None
    if len(lnv) >= hc["stability_min_days"]:
        ln_mean = mean(lnv)
        cv = sample_sd(lnv, ln_mean) / ln_mean * 100.0
    return {"kind": inp.get("kind"), "baseline": b, "trend": t, "ln_mean_7d": round_to(ln_mean, 3),
            "cv_ln_7d": round_to(cv, 2), "stability_days": len(lnv),
            "classification": "PROVIDER_DERIVED" if inp.get("kind") in ("sdnn", "rmssd") else "SCIENTIFIC_DERIVED"}


# ---------------------------------------------------------------------------------------------
# Sleep
# ---------------------------------------------------------------------------------------------

def _valid_nights(nights, cfg):
    floor_min = cfg["sleep"]["min_night_minutes"]
    return dict((d, n) for d, n in (nights or {}).items()
                if n is not None and n.get("asleep_min") is not None and n["asleep_min"] >= floor_min)


def sleep_need(inp, cfg):
    """input: nights (or asleep {day: minutes}), day. Personal sleep need = median main-sleep duration over the
    need_window_days nights before `day`, clamped to need_clamp_min (7-9 h, AASM/SRS adult recommendation);
    the default need below need_min_nights nights. A statistical estimate, not a measured requirement."""
    s = cfg["sleep"]
    if "asleep" in inp:
        asleep = dict((d, v) for d, v in inp["asleep"].items() if v is not None and v >= s["min_night_minutes"])
    else:
        asleep = dict((d, n["asleep_min"]) for d, n in _valid_nights(inp.get("nights"), cfg).items())
    vals = [float(asleep[d]) for d in window_days(inp["day"], -s["need_window_days"], -1) if d in asleep]
    if len(vals) < s["need_min_nights"]:
        return {"need_min": float(s["need_default_min"]), "source": "default", "n": len(vals),
                "classification": "PERSONALIZED_STATISTICAL"}
    lo, hi = s["need_clamp_min"]
    return {"need_min": round_to(clamp(median(vals), float(lo), float(hi)), 1), "source": "personal", "n": len(vals),
            "classification": "PERSONALIZED_STATISTICAL"}


def sleep_status(inp, cfg):
    """input: nights, day. Last night, personal need, 14-day debt (sum of nightly shortfall vs need over recorded
    nights; unrecorded nights are not counted), bedtime / wake-time variability (sample SD, minutes), duration and
    efficiency vs a 28-day robust baseline, naps."""
    s = cfg["sleep"]
    nights = _valid_nights(inp.get("nights"), cfg)
    day = inp["day"]
    need = sleep_need({"nights": inp.get("nights"), "day": day}, cfg)
    last = nights.get(day)
    out = {"status": "NO_DATA", "day": day, "asleep_min": None, "in_bed_min": None, "efficiency": None,
           "waso_min": None, "nap_min": None, "need_min": need["need_min"], "need_source": need["source"],
           "debt_min": None, "debt_nights": 0, "bedtime_sd": None, "wake_sd": None, "variability_nights": 0,
           "duration": None, "efficiency_baseline": None, "confidence": 0.0}
    debt_days = [d for d in window_days(day, -(s["debt_window_days"] - 1), 0) if d in nights]
    if debt_days:
        debt = 0.0
        for d in debt_days:
            debt += max(0.0, need["need_min"] - nights[d]["asleep_min"])
        out["debt_min"] = round_to(debt, 0)
        out["debt_nights"] = len(debt_days)
    var_days = [d for d in window_days(day, -(s["variability_window_days"] - 1), 0) if d in nights
                and nights[d].get("bedtime_clock") is not None and nights[d].get("wake_clock") is not None]
    out["variability_nights"] = len(var_days)
    if len(var_days) >= s["variability_min_nights"]:
        beds = [float(nights[d]["bedtime_clock"]) for d in var_days]
        wakes = [float(nights[d]["wake_clock"]) for d in var_days]
        out["bedtime_sd"] = round_to(sample_sd(beds, mean(beds)), 1)
        out["wake_sd"] = round_to(sample_sd(wakes, mean(wakes)), 1)
    dur = dict((d, n["asleep_min"]) for d, n in nights.items())
    eff = dict((d, n["efficiency"]) for d, n in nights.items() if n.get("efficiency") is not None)
    w = s["baseline_window_days"]
    out["duration"] = baseline({"series": dur, "day": day, "metric": "sleep_duration", "window": w}, cfg)
    out["efficiency_baseline"] = baseline({"series": eff, "day": day, "metric": "sleep_efficiency", "window": w}, cfg)
    if last is not None:
        raw = (inp.get("nights") or {}).get(day) or {}
        out.update(asleep_min=round_to(last["asleep_min"], 0), in_bed_min=round_to(last.get("in_bed_min"), 0),
                   efficiency=round_to(last.get("efficiency"), 1), waso_min=round_to(last.get("waso_min"), 0),
                   nap_min=round_to(raw.get("nap_min"), 0))
        n_hist = len([d for d in window_days(day, -w, -1) if d in nights])
        conf = confidence("PROVIDER_DERIVED", n_hist / float(w), n_hist, cfg["confidence"]["target_n"], "wearable",
                          False, cfg)
        out["confidence"] = round_to(conf, 2)
        out["status"] = status_for(conf, cfg)
    return out


# ---------------------------------------------------------------------------------------------
# Training load
# ---------------------------------------------------------------------------------------------

def sessions(workouts, time_zone):
    """Overlapping workouts merged into sessions (sorted by start, then end): span = union, effort = max known
    effort, trimp = sum of known trimps (None when no member has one), minutes = span length.
    Day = local day of the session start."""
    ws = sorted([w for w in workouts if w["end_ms"] > w["start_ms"]], key=lambda w: (w["start_ms"], w["end_ms"]))
    out = []
    for w in ws:
        if out and w["start_ms"] < out[-1]["end_ms"]:
            s = out[-1]
            s["end_ms"] = max(s["end_ms"], w["end_ms"])
            if w.get("effort") is not None:
                s["effort"] = w["effort"] if s["effort"] is None else max(s["effort"], w["effort"])
            if w.get("trimp") is not None:
                s["trimp"] = w["trimp"] if s["trimp"] is None else s["trimp"] + w["trimp"]
        else:
            out.append({"start_ms": w["start_ms"], "end_ms": w["end_ms"], "effort": w.get("effort"),
                        "trimp": w.get("trimp")})
    for s in out:
        s["day"] = local_day_of(s["start_ms"], time_zone)
        s["minutes"] = (s["end_ms"] - s["start_ms"]) / 60000.0
    return out


def load_days(workouts, time_zone):
    """{day: {minutes, rpe_load, rpe_minutes, trimp, trimp_minutes}}. session-RPE load = effort (CR-10) x minutes
    (Foster 2001); sessions without an effort add to minutes only, never to the RPE load."""
    days = {}
    for s in sessions(workouts, time_zone):
        d = days.setdefault(s["day"], {"minutes": 0.0, "rpe_load": 0.0, "rpe_minutes": 0.0, "trimp": 0.0,
                                       "trimp_minutes": 0.0})
        d["minutes"] += s["minutes"]
        if s["effort"] is not None:
            d["rpe_load"] += s["effort"] * s["minutes"]
            d["rpe_minutes"] += s["minutes"]
        if s["trimp"] is not None:
            d["trimp"] += s["trimp"]
            d["trimp_minutes"] += s["minutes"]
    return days


METHODS = ("trimp", "rpe_load", "minutes")


def _load_series(days, start, end):
    """Daily load per method from start to end inclusive. A day without a session is a true zero (workout tracking
    is on), so it enters the EWMA as 0."""
    out = dict((m, []) for m in METHODS)
    d = start
    while d <= end:
        x = days.get(d)
        for m in METHODS:
            out[m].append(x[m] if x is not None else 0.0)
        d = add_days(d, 1)
    return out


def load(inp, cfg):
    """input: workouts, day, time_zone, tracking (bool). EWMA acute (7 d) and chronic (28 d) load per method,
    lambda = 2 / (N + 1) (Williams 2017), both seeded with the mean daily load of the first 28 history days (so a
    steady routine starts steady instead of ramping up from 0); ratio = acute / chronic. The primary
    method is TRIMP when TRIMP covers >= 70% of training minutes in the state window, else session-RPE, else minutes.
    State from the user's own distribution of acute load and ratio over the previous 90 days, with absolute guards so a
    regular routine does not flip between states (config load.guards). Never an injury risk."""
    lc = cfg["load"]
    day = inp["day"]
    out = {"status": "NO_DATA", "day": day, "state": None, "state_label": None, "primary_method": None,
           "history_days": 0, "methods": {}, "today": None, "classification": "SCIENTIFIC_DERIVED"}
    if not inp.get("tracking", True):
        return out
    days = load_days(inp.get("workouts") or [], inp.get("time_zone", "UTC"))
    known = sorted(d for d in days if d <= day)
    if not known:
        out["status"] = "INSUFFICIENT_HISTORY"
        return out
    start = max(known[0], add_days(day, -lc["max_history_days"]))
    series = _load_series(days, start, day)
    n = len(series["minutes"])
    out["history_days"] = n
    la = 2.0 / (lc["acute_days"] + 1.0)
    lch = 2.0 / (lc["chronic_days"] + 1.0)
    acute = dict((m, [0.0] * n) for m in METHODS)
    chronic = dict((m, [0.0] * n) for m in METHODS)
    for m in METHODS:
        seed = series[m][:lc["chronic_days"]]
        a = c = mean(seed)
        for i, x in enumerate(series[m]):
            a = la * x + (1.0 - la) * a
            c = lch * x + (1.0 - lch) * c
            acute[m][i] = a
            chronic[m][i] = c
    lo = max(0, n - 1 - lc["state_window_days"])
    mins, tmins, rmins = 0.0, 0.0, 0.0
    for d in window_days(day, -lc["state_window_days"], 0):
        x = days.get(d)
        if x is not None:
            mins += x["minutes"]
            tmins += x["trimp_minutes"]
            rmins += x["rpe_minutes"]
    cov = {"minutes": 1.0 if mins > 0 else 0.0, "trimp": tmins / mins if mins > 0 else 0.0,
           "rpe_load": rmins / mins if mins > 0 else 0.0}
    for m in METHODS:
        ratio = acute[m][-1] / chronic[m][-1] if chronic[m][-1] > 0 else None
        out["methods"][m] = {"acute": round_to(acute[m][-1], 1), "chronic": round_to(chronic[m][-1], 1),
                             "ratio": round_to(ratio, 2), "coverage": round_to(cov[m], 2),
                             "today": round_to(series[m][-1], 1)}
    if cov["trimp"] >= lc["method_min_coverage"]:
        primary = "trimp"
    elif cov["rpe_load"] >= lc["method_min_coverage"]:
        primary = "rpe_load"
    else:
        primary = "minutes"
    out["primary_method"] = primary
    out["today"] = out["methods"][primary]["today"]
    if n < lc["min_history_days"]:
        out["status"] = "INSUFFICIENT_HISTORY"
        return out
    hist_a, hist_r = [], []
    # the reference distribution ends before the current acute week, so a spike is compared with what came before it
    for i in range(max(lo, lc["min_history_days"] - 1), n - lc["acute_days"]):
        hist_a.append(acute[primary][i])
        if chronic[primary][i] > 0:
            hist_r.append(acute[primary][i] / chronic[primary][i])
    if len(hist_a) < lc["state_min_days"] or len(hist_r) < lc["state_min_days"]:
        out["status"] = "INSUFFICIENT_HISTORY"
        return out
    p = lc["percentiles"]
    gd = lc["guards"]
    a_now = acute[primary][-1]
    r_now = a_now / chronic[primary][-1] if chronic[primary][-1] > 0 else None
    a_med = median(hist_a)
    if r_now is not None and r_now >= gd["spike_min_ratio"] and r_now > percentile(hist_r, p["spike_ratio_above"]):
        state = "LOAD_SPIKE"
    elif a_now > percentile(hist_a, p["high_above"]) and a_now >= gd["high_min_vs_median"] * a_med:
        state = "LOAD_HIGH"
    elif r_now is not None and r_now >= gd["increasing_min_ratio"] and r_now > percentile(hist_r, p["increasing_ratio_above"]):
        state = "LOAD_INCREASING"
    elif a_now < percentile(hist_a, p["reduced_below"]) and a_now <= gd["reduced_max_vs_median"] * a_med:
        state = "LOAD_REDUCED"
    else:
        state = "LOAD_STABLE"
    conf = confidence("SCIENTIFIC_DERIVED", cov[primary], len(hist_a), lc["state_window_days"], "wearable", False, cfg)
    out.update(status=status_for(conf, cfg), state=state, state_label=lc["states"][state], confidence=round_to(conf, 2))
    return out


# ---------------------------------------------------------------------------------------------
# Heart rate recovery
# ---------------------------------------------------------------------------------------------

def hrr(inp, cfg):
    """input: samples [[t_ms, bpm]], end_ms. HRR1 = highest HR in the last 60 s before the end - HR at end + 60 s
    (Cole 1999); HRR2 = the same peak - HR at end + 120 s (Shetler 2001). "At" = the sample nearest the target within
    +/- tolerance_s. Identical HRR1 rule to shared/workout hr_recovery."""
    hc = cfg["hrr"]
    end = inp["end_ms"]
    samples = sorted(inp.get("samples") or [], key=lambda s: s[0])
    tol = hc["tolerance_s"] * 1000
    before = [b for t, b in samples if end - 60000 <= t <= end]
    out = {"status": "NO_DATA", "peak": None, "hrr1": None, "hrr2": None, "confidence": 0.0,
           "classification": "SCIENTIFIC_DERIVED"}
    if not samples:
        return out

    def at(offset_ms):
        c = sorted([(abs(t - (end + offset_ms)), t, b) for t, b in samples if abs(t - (end + offset_ms)) <= tol])
        return c[0][2] if c else None
    h1, h2 = at(60000), at(120000)
    if not before or h1 is None:
        out["status"] = "INSUFFICIENT_DATA"
        return out
    peak = max(before)
    gaps = [samples[i + 1][0] - samples[i][0] for i in range(len(samples) - 1)]
    dense = bool(gaps) and max(gaps) <= hc["dense_max_gap_s"] * 1000
    conf = hc["confidence_dense"] if dense else hc["confidence_sparse"]
    out.update(status=status_for(conf, cfg), peak=round_to(peak, 0), hrr1=round_to(peak - h1, 0),
               hrr2=round_to(peak - h2, 0) if h2 is not None else None, confidence=conf)
    return out


# ---------------------------------------------------------------------------------------------
# Recovery Indicator v2
# ---------------------------------------------------------------------------------------------

def _sub(z_dir, rc):
    return clamp(rc["subscore_center"] + rc["subscore_slope"] * z_dir, 0.0, 100.0)


def _sleep_component(inputs, day, rc, cfg):
    """(subscore or None, parts, confidence, raw values). Duration vs personal need, efficiency vs own baseline,
    regularity = last night's midpoint vs the median midpoint of the prior 14 nights."""
    sc = rc["sleep"]
    parts_w = next(c for c in rc["components"] if c["id"] == "sleep")["parts"]
    nights = _valid_nights(inputs.get("nights"), cfg)
    last = nights.get(day)
    if last is None:
        return None, {}, 0.0, None
    need = sleep_need({"nights": inputs.get("nights"), "day": day}, cfg)
    parts = {}
    deficit = max(0.0, need["need_min"] - last["asleep_min"])
    parts["duration"] = clamp(100.0 * (1.0 - deficit / sc["duration_zero_deficit_min"]), 0.0, 100.0)
    eff = dict((d, n["efficiency"]) for d, n in nights.items() if n.get("efficiency") is not None)
    if last.get("efficiency") is not None:
        b = _robust(eff, day, rc["baseline_window_days"], rc["min_points"], cfg["metrics"]["sleep_efficiency"]["spread_floor"], cfg)
        if b["z"] is not None:
            parts["efficiency"] = _sub(b["z"], rc)
    if last.get("midpoint_clock") is not None:
        prior = [float(nights[d]["midpoint_clock"]) for d in window_days(day, -sc["regularity_window_days"], -1)
                 if d in nights and nights[d].get("midpoint_clock") is not None]
        if len(prior) >= sc["regularity_min_nights"]:
            dev = abs(last["midpoint_clock"] - median(prior))
            parts["regularity"] = clamp(100.0 * (1.0 - dev / sc["regularity_zero_at_min"]), 0.0, 100.0)
    ws, acc = 0.0, 0.0
    for k in ("duration", "efficiency", "regularity"):
        if k in parts:
            ws += parts_w[k]
            acc += parts_w[k] * parts[k]
    hist = len([d for d in window_days(day, -rc["baseline_window_days"], -1) if d in nights])
    conf = cfg["classifications"]["PROVIDER_DERIVED"]["validity_cap"] * math.sqrt(
        clamp(hist / float(sc["history_target_nights"]), 0.0, 1.0)) * (1.0 if need["source"] == "personal" else 0.8)
    return acc / ws, parts, conf, {"asleep_min": last["asleep_min"], "need_min": need["need_min"]}


def recovery(inp, cfg):
    """Recovery Indicator v2 for `day` (wake day of last night). input: inputs {time_zone, series, contexts,
    sources, nights, workouts, tracking, overnight_fallback, scan_fallback}, day.

    Heart / breathing / temperature / oxygen components: robust z vs the 60 days before `day` (min 7 points),
    subscore = clamp(50 + 20 z_dir). Sleep: duration vs personal need, efficiency vs own baseline, timing regularity.
    Score = weighted mean over available components (weights renormalized), plus the load modifier from yesterday's
    load state. Confidence = sum of weight x component confidence over ALL configured weights, so a missing input
    lowers it. Not a biological recovery percentage."""
    rc = cfg["recovery"]
    inputs = inp["inputs"]
    day = inp["day"]
    series = inputs.get("series") or {}
    contexts = inputs.get("contexts") or {}
    sources = inputs.get("sources") or {}
    fallback = set(inputs.get("overnight_fallback") or [])
    scans = set(inputs.get("scan_fallback") or [])
    comps, warnings = [], []
    total_w = 0.0
    for c in rc["components"]:
        total_w += c["weight"]
    heart_hist = 0
    for c in rc["components"]:
        item = {"id": c["id"], "weight": c["weight"], "available": False, "value": None, "baseline": None, "z": None,
                "subscore": None, "impact": None, "confidence": 0.0, "parts": None, "unit": None, "n": 0,
                "context": None}
        if c["mode"] == "sleep":
            sub, parts, conf, raw = _sleep_component(inputs, day, rc, cfg)
            item["unit"] = "min"
            if sub is not None:
                item.update(available=True, subscore=sub, confidence=conf, value=raw["asleep_min"],
                            baseline=raw["need_min"], parts=dict((k, round_to(v, 1)) for k, v in parts.items()))
            comps.append(item)
            continue
        m = cfg["metrics"][c["metric"]]
        item["unit"] = m["unit"]
        b = _robust(dict((d, v) for d, v in (series.get(c["metric"]) or {}).items() if v is not None), day,
                    rc["baseline_window_days"], rc["min_points"], m["spread_floor"], cfg, contexts.get(c["metric"]),
                    None, sources.get(c["metric"]))
        item.update(value=b["value"], baseline=b["median"], z=b["z"], n=b["n"], context=b["context"])
        if c["id"] in rc["heart_components"] and b["median"] is not None:
            heart_hist += 1
        if b["z"] is not None:
            z = b["z"]
            if c["mode"] == "higher":
                sub = _sub(z, rc)
            elif c["mode"] == "lower":
                sub = _sub(-z, rc)
            elif c["mode"] == "band":
                sub = _sub(-max(0.0, abs(z) - c["tolerance_z"]), rc)
            else:
                sub = _sub(-max(0.0, -z - c["tolerance_z"]), rc)
            conf = confidence(m["classification"], b["coverage"], b["n"], cfg["confidence"]["target_n"], b["context"],
                              b["source_changed"], cfg)
            item.update(available=True, subscore=sub, confidence=conf)
            if b["source_changed"]:
                warnings.append({"code": "source_changed", "metric": c["id"]})
            if c["metric"] in fallback:
                warnings.append({"code": "overnight_fallback", "metric": c["id"]})
            if c["metric"] in scans or b["context"] == "camera":
                warnings.append({"code": "camera", "metric": c["id"]})
        comps.append(item)
    by = dict((i["id"], i) for i in comps)
    nights = _valid_nights(inputs.get("nights"), cfg)
    sleep_hist = len([d for d in window_days(day, -rc["baseline_window_days"], -1) if d in nights])
    out = {"algorithm_id": rc["algorithm_id"], "algorithm_version": rc["algorithm_version"],
           "weights_version": rc["weights_version"], "config_version": cfg["config_version"], "day": day,
           "status": "VALID", "reason": None, "score": None, "label": None, "label_text": None,
           "recommendation": None, "confidence": 0.0, "confidence_band": "low", "coverage": None,
           "collecting": None, "components": comps, "drivers": [], "positives": [], "negatives": [], "load": None,
           "warnings": [], "summary": None, "classification": "PERSONALIZED_STATISTICAL"}
    if heart_hist == 0 or sleep_hist < rc["min_points"]:
        have = min(sleep_hist, max([by[i]["n"] for i in rc["heart_components"]] + [0]))
        out.update(status="INSUFFICIENT_HISTORY", reason="collecting",
                   collecting={"have": min(have, rc["min_points"]), "need": rc["min_points"]})
        return _round_recovery(out)
    if not by["sleep"]["available"]:
        out.update(status="NO_DATA", reason="no_sleep")
        return _round_recovery(out)
    if not any(by[i]["available"] for i in rc["heart_components"]):
        out.update(status="NO_DATA", reason="no_heart_data")
        return _round_recovery(out)
    avail = [i for i in comps if i["available"]]
    wsum = 0.0
    for i in avail:
        wsum += i["weight"]
    score = 0.0
    conf = 0.0
    for i in avail:
        score += i["weight"] * i["subscore"]
        conf += i["weight"] * i["confidence"]
        i["impact"] = (i["subscore"] - rc["subscore_center"]) * i["weight"] / wsum
    score /= wsum
    conf /= total_w
    for i in comps:
        if not i["available"]:
            warnings.append({"code": "missing", "metric": i["id"]})
    ld = load({"workouts": inputs.get("workouts") or [], "day": add_days(day, -1),
               "time_zone": inputs.get("time_zone", "UTC"), "tracking": (inputs.get("tracking") or {}).get("workouts", True)}, cfg)
    mod = rc["load_modifier"].get(ld["state"], 0) if ld["status"] in ("VALID", "LOW_CONFIDENCE") else 0
    final = int(clamp(round_int(score + mod), 0, 100))
    band = next(b for b in rc["bands"] if final >= b["min"])
    tpl = rc["drivers"]
    drivers = []
    for i in avail:
        direction = "positive" if i["impact"] > rc["impact_neutral"] else (
            "negative" if i["impact"] < -rc["impact_neutral"] else "neutral")
        key = direction
        if i["id"] == "sleep" and direction != "negative" and \
                i["baseline"] - i["value"] >= rc["sleep"]["short_note_deficit_min"]:
            key = direction + "_short"  # never call a short night "adequate" because timing and efficiency were good
        drivers.append({"id": i["id"], "direction": direction, "impact": i["impact"], "value": i["value"],
                        "baseline": i["baseline"], "z": i["z"], "unit": i["unit"], "text": tpl[i["id"]][key],
                        "text_key": i["id"] + "." + key})
    if mod < 0:
        drivers.append({"id": "training_load", "direction": "negative", "impact": float(mod), "value": ld["today"],
                        "baseline": None, "z": None, "unit": "AU", "text": tpl["training_load"]["negative"],
                        "text_key": "training_load.negative"})
    drivers.sort(key=lambda d: (-abs(d["impact"]), d["id"]))
    neg = [d for d in drivers if d["direction"] == "negative"]
    pos = [d for d in drivers if d["direction"] == "positive"]
    st = rc["summary"]
    parts = []
    if neg:
        parts.append(st["lead_negative"].replace("{items}", "; ".join(d["text"] for d in neg)))
    if pos:
        parts.append(st["lead_positive"].replace("{items}", "; ".join(d["text"] for d in pos)))
    seen, uniq = set(), []
    for w in warnings:
        k = (w["code"], w["metric"])
        if k not in seen:
            seen.add(k)
            uniq.append(w)
    out.update(status=status_for(conf, cfg), score=final, label=band["id"], label_text=band["label"],
               recommendation=band["recommendation"], confidence=conf, confidence_band=confidence_band(conf, cfg),
               coverage=wsum / total_w, drivers=drivers, positives=[d["id"] for d in pos],
               negatives=[d["id"] for d in neg], warnings=uniq,
               summary=" ".join(parts) if parts else st["none"],
               load={"day": ld["day"], "state": ld["state"], "status": ld["status"], "method": ld["primary_method"],
                     "modifier": mod,
                     "acute": ld["methods"][ld["primary_method"]]["acute"] if ld["primary_method"] else None,
                     "chronic": ld["methods"][ld["primary_method"]]["chronic"] if ld["primary_method"] else None,
                     "ratio": ld["methods"][ld["primary_method"]]["ratio"] if ld["primary_method"] else None})
    return _round_recovery(out)


def _round_recovery(out):
    for i in out["components"]:
        for k in ("value", "baseline", "z"):
            i[k] = round_to(i[k], 2)
        for k in ("subscore", "impact"):
            i[k] = round_to(i[k], 1)
        i["confidence"] = round_to(i["confidence"], 2)
    for d in out["drivers"]:
        d["impact"] = round_to(d["impact"], 1)
        for k in ("value", "baseline", "z"):
            d[k] = round_to(d[k], 2)
    out["confidence"] = round_to(out["confidence"], 2)
    out["coverage"] = round_to(out["coverage"], 2)
    return out


# ---------------------------------------------------------------------------------------------
# Multi-signal anomaly state
# ---------------------------------------------------------------------------------------------

def _signal_series(inputs, metric, cfg):
    if metric == "sleep_duration":
        return dict((d, n["asleep_min"]) for d, n in _valid_nights(inputs.get("nights"), cfg).items())
    if metric == "training_load":
        days = load_days(inputs.get("workouts") or [], inputs.get("time_zone", "UTC"))
        return dict((d, x["minutes"]) for d, x in days.items())
    return dict((d, v) for d, v in ((inputs.get("series") or {}).get(metric) or {}).items() if v is not None)


def _anomaly_day(inputs, day, ac, cfg):
    sigs = []
    for s in ac["signals"]:
        m = cfg["metrics"][s["metric"]]
        series = _signal_series(inputs, s["metric"], cfg)
        if s["metric"] == "training_load":
            # training days only: a rest day is not a deviation
            if series.get(day) is None:
                continue
        b = _robust(series, day, ac["baseline_window_days"], ac["min_points"], m["spread_floor"], cfg,
                    (inputs.get("contexts") or {}).get(s["metric"]))
        if b["z"] is None:
            continue
        z = b["z"]
        zb = -z if s["bad"] == "low" else (z if s["bad"] == "high" else abs(z))
        sigs.append({"id": s["id"], "value": b["value"], "median": b["median"], "z": z, "z_bad": zb,
                     "flagged": zb >= ac["mild_z"]})
    flagged = [x for x in sigs if x["flagged"]]
    if len(sigs) < ac["min_signals"]:
        state = None
    elif len(flagged) >= ac["multi_signal_count"]:
        state = "MULTI_SIGNAL_DEVIATION"
    elif any(x["z_bad"] >= ac["significant_z"] for x in sigs):
        state = "SIGNIFICANT_DEVIATION"
    elif flagged:
        state = "MILD_DEVIATION"
    else:
        state = "NORMAL"
    return state, sigs


def anomaly(inp, cfg):
    """input: inputs (series, nights, workouts, contexts, time_zone), day. Robust z of each signal vs its own
    28-day baseline, in the signal's concerning direction. NORMAL / MILD (any >= 2) / SIGNIFICANT (any >= 3) /
    MULTI_SIGNAL (>= 3 signals >= 2). Persistent when the last 3 days are all SIGNIFICANT or MULTI_SIGNAL.
    Describes deviations from the user's own range; never names a condition."""
    ac = cfg["anomaly"]
    inputs = inp["inputs"]
    day = inp["day"]
    state, sigs = _anomaly_day(inputs, day, ac, cfg)
    out = {"algorithm_id": ac["algorithm_id"], "algorithm_version": ac["algorithm_version"], "day": day,
           "status": "INSUFFICIENT_DATA", "state": None, "message": None, "persistent": False, "persistent_note": None,
           "signals": [], "flagged_count": 0, "classification": "PERSONALIZED_STATISTICAL", "confidence": 0.0}
    out["signals"] = [{"id": s["id"], "value": round_to(s["value"], 2), "median": round_to(s["median"], 2),
                       "z": round_to(s["z"], 2), "z_bad": round_to(s["z_bad"], 2), "flagged": s["flagged"]}
                      for s in sigs]
    if state is None:
        return out
    serious = ("SIGNIFICANT_DEVIATION", "MULTI_SIGNAL_DEVIATION")
    persistent = state in serious
    for k in range(1, ac["persistent_days"]):
        if not persistent:
            break
        s2, _ = _anomaly_day(inputs, add_days(day, -k), ac, cfg)
        persistent = s2 in serious
    conf = clamp(len(sigs) / float(len(ac["signals"])), 0.0, 1.0) * \
        cfg["classifications"]["PERSONALIZED_STATISTICAL"]["validity_cap"]
    out.update(status=status_for(conf, cfg), state=state, message=ac["states"][state], persistent=persistent,
               persistent_note=ac["persistent_note"] if persistent else None,
               flagged_count=len([s for s in sigs if s["flagged"]]), confidence=round_to(conf, 2))
    return out


# ---------------------------------------------------------------------------------------------
# Correlation and personal response
# ---------------------------------------------------------------------------------------------

def ranks(values):
    """1-based ranks; ties share the average rank."""
    order = sorted(range(len(values)), key=lambda i: (values[i], i))
    r = [0.0] * len(values)
    i = 0
    while i < len(order):
        j = i
        while j + 1 < len(order) and values[order[j + 1]] == values[order[i]]:
            j += 1
        avg = (i + j) / 2.0 + 1.0
        for k in range(i, j + 1):
            r[order[k]] = avg
        i = j + 1
    return r


def pearson(xs, ys):
    mx, my = mean(xs), mean(ys)
    sxx, syy, sxy = 0.0, 0.0, 0.0
    for x, y in zip(xs, ys):
        sxx += (x - mx) * (x - mx)
        syy += (y - my) * (y - my)
        sxy += (x - mx) * (y - my)
    if sxx == 0 or syy == 0:
        return None
    return sxy / math.sqrt(sxx * syy)


def paired(exposure, outcome, lag, as_of, window):
    """[(x_d, y_{d+lag})] for exposure days as_of-window .. as_of-lag, oldest first, both present."""
    out = []
    for d in window_days(as_of, -window, -lag):
        x = exposure.get(d)
        y = outcome.get(add_days(d, lag))
        if x is not None and y is not None:
            out.append((float(x), float(y)))
    return out


def bh_qvalues(pvalues):
    """Benjamini-Hochberg adjusted p-values (q), same order as the input."""
    m = len(pvalues)
    order = sorted(range(m), key=lambda i: (pvalues[i], i))
    q = [0.0] * m
    running = 1.0
    for rank in range(m, 0, -1):
        i = order[rank - 1]
        running = min(running, pvalues[i] * m / rank)
        q[i] = running
    return q


def correlation(inp, cfg):
    """input: as_of, pairs [{id, exposure {day: v}, outcome {day: v}}] (ids from config correlation.pairs).
    For each pair and lag (0-3 days): Pearson r and Spearman rho over paired days; Fisher-z 95% CI and two-sided p
    for rho with SE = sqrt(1.06 / (n - 3)) (Fieller 1957); Benjamini-Hochberg q over every tested pair and lag.
    Surfaced when q <= 0.1 and |rho| >= 0.3. Association language only."""
    cc = cfg["correlation"]
    meta = dict((p["id"], p) for p in cc["pairs"])
    results, tested = [], []
    for pr in inp["pairs"]:
        for lag in cc["lags"]:
            pts = paired(pr["exposure"], pr["outcome"], lag, inp["as_of"], cc["window_days"])
            r = {"id": pr["id"], "lag": lag, "n": len(pts), "status": "INSUFFICIENT_DATA", "pearson_r": None,
                 "spearman_rho": None, "ci_low": None, "ci_high": None, "p": None, "q": None, "surfaced": False,
                 "text": None}
            if len(pts) >= cc["min_n"]:
                xs = [p[0] for p in pts]
                ys = [p[1] for p in pts]
                pr_r = pearson(xs, ys)
                rho = pearson(ranks(xs), ranks(ys))
                if pr_r is not None and rho is not None:
                    rc_ = clamp(rho, -0.9999, 0.9999)
                    z = atanh(rc_)
                    se = math.sqrt(cc["spearman_se_factor"] / (len(pts) - 3))
                    p = 2.0 * (1.0 - normal_cdf(abs(z) / se))
                    r.update(status="VALID", pearson_r=pr_r, spearman_rho=rho, ci_low=tanh(z - cc["z_975"] * se),
                             ci_high=tanh(z + cc["z_975"] * se), p=p)
                    tested.append(r)
            results.append(r)
    qs = bh_qvalues([r["p"] for r in tested])
    for r, q in zip(tested, qs):
        r["q"] = q
        r["surfaced"] = q <= cc["q_level"] and abs(r["spearman_rho"]) >= cc["min_abs_rho"]
        if r["surfaced"]:
            m = meta[r["id"]]
            r["text"] = cc["template"].replace("{exposure}", m["exposure_label"]).replace(
                "{direction}", cc["direction_words"]["higher" if r["spearman_rho"] > 0 else "lower"]).replace(
                "{outcome}", m["outcome_label"]).replace("{lag}", cc["lag_words"][str(r["lag"])]).replace(
                "{n}", "%d" % r["n"])
    for r in results:
        for k, d in (("pearson_r", 3), ("spearman_rho", 3), ("ci_low", 3), ("ci_high", 3), ("p", 4), ("q", 4)):
            r[k] = round_to(r[k], d)
    return {"algorithm_id": cc["algorithm_id"], "algorithm_version": cc["algorithm_version"], "as_of": inp["as_of"],
            "tested": len(tested), "results": results, "classification": "PERSONALIZED_STATISTICAL"}


def response(inp, cfg):
    """input: exposure, outcome, lag, as_of, window. Theil-Sen effect (outcome units per exposure unit) with the
    Sen (1968) rank-based 95% interval: C = z * sqrt(n(n-1)(2n+5)/18) (no tie correction), lower = sorted slope at
    index floor((N - C) / 2), upper at index ceil((N + C) / 2) clamped (0-based, N = number of pairwise slopes)."""
    rc = cfg["response"]
    pts = paired(inp["exposure"], inp["outcome"], inp["lag"], inp["as_of"], inp.get("window") or
                 cfg["correlation"]["window_days"])
    out = {"algorithm_id": rc["algorithm_id"], "algorithm_version": rc["algorithm_version"], "status":
           "INSUFFICIENT_DATA", "n": len(pts), "lag": inp["lag"], "effect": None, "intercept": None, "ci_low": None,
           "ci_high": None, "excludes_zero": False, "confidence": 0.0, "classification": "PERSONALIZED_STATISTICAL"}
    if len(pts) < rc["min_n"]:
        return out
    b, a, slopes = theil_sen(pts)
    if b is None:
        return out
    n = len(pts)
    s = sorted(slopes)
    big_n = len(s)
    c = rc["z_975"] * math.sqrt(n * (n - 1) * (2 * n + 5) / 18.0)
    lo = int(clamp(math.floor((big_n - c) / 2.0), 0, big_n - 1))
    hi = int(clamp(math.ceil((big_n + c) / 2.0), 0, big_n - 1))
    conf = clamp(n / 60.0, 0.0, 1.0) * cfg["classifications"]["PERSONALIZED_STATISTICAL"]["validity_cap"]
    out.update(status=status_for(conf, cfg), effect=round_to(b, 4), intercept=round_to(a, 3),
               ci_low=round_to(s[lo], 4), ci_high=round_to(s[hi], 4), excludes_zero=s[lo] > 0 or s[hi] < 0,
               confidence=round_to(conf, 2))
    return out


# ---------------------------------------------------------------------------------------------
# Energy
# ---------------------------------------------------------------------------------------------

def mifflin(weight_kg, height_cm, age, sex, ec):
    return 10.0 * weight_kg + 6.25 * height_cm - 5.0 * age + ec["mifflin"].get(sex or "other", ec["mifflin"]["other"])


def energy(inp, cfg):
    """One day. input: provider_basal_kcal, provider_active_kcal (platform de-duplicated, Ayuvo's own burns
    excluded), provider_workout_kcal (energy inside provider workouts, already part of active), ayuvo_sessions
    [{start_ms, end_ms, kcal}], provider_workouts [{start_ms, end_ms}], profile {weight_kg, height_cm, age, sex}.

    resting = provider basal when it is at least bmr_min_share x Mifflin (a full day), else Mifflin;
    active = provider active + Ayuvo burns of sessions that no provider workout overlaps (no double count);
    estimated_daily_expenditure = (resting + active) / (1 - tef_share). Negative inputs are invalid."""
    ec = cfg["energy"]
    p = inp.get("profile") or {}
    pred = None
    if p.get("weight_kg") and p.get("height_cm") and p.get("age") is not None:
        pred = mifflin(p["weight_kg"], p["height_cm"], p["age"], p.get("sex"), ec)
    out = {"algorithm_id": ec["algorithm_id"], "algorithm_version": ec["algorithm_version"], "status": "NO_DATA",
           "predicted_resting_kcal": round_to(pred, 0), "provider_basal_kcal": None, "provider_active_kcal": None,
           "provider_workout_kcal": None, "ayuvo_extra_kcal": 0.0, "resting_kcal": None, "resting_source": None,
           "active_kcal": None, "estimated_daily_expenditure": None, "confidence": 0.0,
           "classification": "SCIENTIFIC_DERIVED"}
    basal, active = inp.get("provider_basal_kcal"), inp.get("provider_active_kcal")
    for v in (basal, active, inp.get("provider_workout_kcal")):
        if v is not None and v < 0:
            out["status"] = "INVALID_INPUT"
            return out
    out["provider_basal_kcal"] = round_to(basal, 0)
    out["provider_active_kcal"] = round_to(active, 0)
    out["provider_workout_kcal"] = round_to(inp.get("provider_workout_kcal"), 0)
    extra = 0.0
    for s in inp.get("ayuvo_sessions") or []:
        covered = any(w["start_ms"] < s["end_ms"] and s["start_ms"] < w["end_ms"] for w in inp.get("provider_workouts") or [])
        if not covered and s.get("kcal") is not None and s["kcal"] > 0:
            extra += s["kcal"]
    out["ayuvo_extra_kcal"] = round_to(extra, 0)
    if basal is not None and basal > 0 and (pred is None or basal >= ec["bmr_min_share"] * pred):
        rest, src, c_rest = basal, "provider", ec["confidence"]["provider_resting"]
    elif pred is not None:
        rest, src, c_rest = pred, "predicted", ec["confidence"]["predicted_resting"]
    else:
        return out
    out["resting_kcal"] = round_to(rest, 0)
    out["resting_source"] = src
    if active is None:
        out["status"] = "INSUFFICIENT_DATA"
        return out
    act = active + extra
    tdee = (rest + act) / (1.0 - ec["tef_share"])
    conf = c_rest * ec["confidence"]["provider_active"] * cfg["classifications"]["SCIENTIFIC_DERIVED"]["validity_cap"]
    out.update(status=status_for(conf, cfg), active_kcal=round_to(act, 0), estimated_daily_expenditure=round_to(tdee, 0),
               confidence=round_to(conf, 2))
    return out


# ---------------------------------------------------------------------------------------------
# Fitness: VO2 max trend per source kind (never mixed)
# ---------------------------------------------------------------------------------------------

def vo2max_trend(inp, cfg):
    """input: readings {kind: {day: value}} with kind in provider / gps / uth, day. Per kind: latest, change vs the
    median of readings at least 60 days older than the latest (within the 365-day window), Theil-Sen slope per 30
    days (>= 4 readings). Kinds are never combined."""
    fc = cfg["fitness"]
    out = {"algorithm_id": fc["algorithm_id"], "algorithm_version": fc["algorithm_version"], "day": inp["day"],
           "kinds": {}, "primary": None}
    for kind in fc["kinds"]:
        s = (inp.get("readings") or {}).get(kind) or {}
        days = [d for d in window_days(inp["day"], -(fc["window_days"] - 1), 0) if s.get(d) is not None]
        r = {"n": len(days), "latest": None, "latest_day": None, "change": None, "slope_per_30d": None,
             "classification": fc["kind_classification"][kind], "status": "NO_DATA"}
        if days:
            last = days[-1]
            r.update(latest=round_to(float(s[last]), 1), latest_day=last, status="PROVIDER_REPORTED"
                     if kind == "provider" else "VALID")
            old = [float(s[d]) for d in days if days_between(d, last) >= fc["change_min_gap_days"]]
            if old:
                r["change"] = round_to(float(s[last]) - median(old), 1)
            if len(days) >= fc["slope_min_points"]:
                b, _, _ = theil_sen([(float(days_between(days[0], d)), float(s[d])) for d in days])
                r["slope_per_30d"] = round_to(b * 30.0, 2) if b is not None else None
            if kind == "uth":
                r["status"] = "EXPERIMENTAL"
            if out["primary"] is None:
                out["primary"] = kind
        out["kinds"][kind] = r
    return out


# ---------------------------------------------------------------------------------------------
# MET intensity
# ---------------------------------------------------------------------------------------------

def met_intensity(inp, cfg):
    """input: activities [{start_ms, end_ms, key}], day, time_zone. MET per activity key from the 2024 Adult
    Compendium table in the config; unknown keys get no MET and are reported as unknown minutes. Overlapping
    activities are merged keeping the higher MET. Weekly (7 days ending `day`) light / moderate / vigorous minutes,
    MET-minutes and moderate-equivalent minutes (moderate + 2 x vigorous, WHO 2020)."""
    mc = cfg["met"]
    tz = inp.get("time_zone", "UTC")
    acts = sorted([a for a in inp.get("activities") or [] if a["end_ms"] > a["start_ms"]],
                  key=lambda a: (a["start_ms"], a["end_ms"]))
    merged = []
    for a in acts:
        e = mc["table"].get(a.get("key") or "")
        met = e["met"] if e is not None else None
        if merged and a["start_ms"] < merged[-1]["end_ms"]:
            m = merged[-1]
            m["end_ms"] = max(m["end_ms"], a["end_ms"])
            if met is not None and (m["met"] is None or met > m["met"]):
                m["met"], m["key"] = met, a["key"]
        else:
            merged.append({"start_ms": a["start_ms"], "end_ms": a["end_ms"], "met": met, "key": a.get("key")})
    first = add_days(inp["day"], -(mc["window_days"] - 1))
    out = {"algorithm_id": mc["algorithm_id"], "algorithm_version": mc["algorithm_version"], "day": inp["day"],
           "light_min": 0.0, "moderate_min": 0.0, "vigorous_min": 0.0, "unknown_min": 0.0, "met_minutes": 0.0,
           "moderate_equivalent_min": 0.0, "meets_who": False, "sessions": 0, "classification": "SCIENTIFIC_DERIVED"}
    for m in merged:
        d = local_day_of(m["start_ms"], tz)
        if d < first or d > inp["day"]:
            continue
        mins = (m["end_ms"] - m["start_ms"]) / 60000.0
        out["sessions"] += 1
        if m["met"] is None:
            out["unknown_min"] += mins
            continue
        out["met_minutes"] += m["met"] * mins
        if m["met"] >= mc["bands"]["vigorous_min"]:
            out["vigorous_min"] += mins
        elif m["met"] >= mc["bands"]["moderate_min"]:
            out["moderate_min"] += mins
        else:
            out["light_min"] += mins
    out["moderate_equivalent_min"] = out["moderate_min"] + 2.0 * out["vigorous_min"]
    out["meets_who"] = out["moderate_equivalent_min"] >= mc["who_weekly_moderate_equivalent_min"]
    for k in ("light_min", "moderate_min", "vigorous_min", "unknown_min", "met_minutes", "moderate_equivalent_min"):
        out[k] = round_to(out[k], 1)
    return out


# ---------------------------------------------------------------------------------------------
# Forecast: per-user ridge regression with a temporal split and a deployment gate
# ---------------------------------------------------------------------------------------------

def cholesky_solve(a, b):
    """Solve A x = b for symmetric positive-definite A (lists), Cholesky-Banachiewicz, then two triangular solves."""
    n = len(a)
    L = [[0.0] * n for _ in range(n)]
    for i in range(n):
        for j in range(i + 1):
            s = a[i][j]
            for k in range(j):
                s -= L[i][k] * L[j][k]
            if i == j:
                if s <= 0:
                    return None
                L[i][j] = math.sqrt(s)
            else:
                L[i][j] = s / L[j][j]
    y = [0.0] * n
    for i in range(n):
        s = b[i]
        for k in range(i):
            s -= L[i][k] * y[k]
        y[i] = s / L[i][i]
    x = [0.0] * n
    for i in range(n - 1, -1, -1):
        s = y[i]
        for k in range(i + 1, n):
            s -= L[k][i] * x[k]
        x[i] = s / L[i][i]
    return x


def _design(rows, names, fit_rows):
    """Normalization from fit_rows: per feature median (imputation), mean and SD of the imputed values, and a
    missing indicator when any fit row lacks it. Features with no values or zero SD are dropped."""
    norm = {}
    for f in names:
        vals = [r["x"][f] for r in fit_rows if r["x"].get(f) is not None]
        if not vals:
            continue
        med = median(vals)
        imputed = [r["x"][f] if r["x"].get(f) is not None else med for r in fit_rows]
        mu = mean(imputed)
        sd = sample_sd(imputed, mu) if len(imputed) >= 2 else 0.0
        if sd == 0:
            continue
        norm[f] = {"median": med, "mean": mu, "sd": sd, "indicator": len(vals) < len(fit_rows)}
    cols = []
    for f in names:
        if f in norm:
            cols.append((f, "value"))
            if norm[f]["indicator"]:
                cols.append((f, "missing"))
    return norm, cols


def _vector(x, norm, cols):
    v = []
    for f, kind in cols:
        raw = x.get(f)
        if kind == "missing":
            v.append(1.0 if raw is None else 0.0)
        else:
            val = raw if raw is not None else norm[f]["median"]
            v.append((val - norm[f]["mean"]) / norm[f]["sd"])
    return v


def _fit(rows, names, lam):
    norm, cols = _design(rows, names, rows)
    xs = [_vector(r["x"], norm, cols) for r in rows]
    y_mean = mean([r["y"] for r in rows])
    p = len(cols)
    a = [[0.0] * p for _ in range(p)]
    b = [0.0] * p
    for x, r in zip(xs, rows):
        yc = r["y"] - y_mean
        for i in range(p):
            b[i] += x[i] * yc
            for j in range(p):
                a[i][j] += x[i] * x[j]
    for i in range(p):
        a[i][i] += lam
    beta = cholesky_solve(a, b) if p > 0 else []
    if beta is None:
        return None
    return {"norm": norm, "cols": cols, "beta": beta, "intercept": y_mean, "lambda": lam}


def _predict(model, x):
    v = _vector(x, model["norm"], model["cols"])
    s = model["intercept"]
    for bi, xi in zip(model["beta"], v):
        s += bi * xi
    return s


def _metrics(preds, ys):
    n = len(ys)
    ae, se, bias = 0.0, 0.0, 0.0
    for p, y in zip(preds, ys):
        ae += abs(p - y)
        se += (p - y) * (p - y)
        bias += p - y
    my = mean(ys)
    sst = 0.0
    for y in ys:
        sst += (y - my) * (y - my)
    return {"mae": ae / n, "rmse": math.sqrt(se / n), "bias": bias / n, "r2": 1.0 - se / sst if sst > 0 else None}


def forecast(inp, cfg):
    """input: target id, features {day: {name: v | null}}, target_series {day: v}, as_of.

    Rows: features of day d -> target on d + 1 (d + 1 <= as_of), oldest first. Temporal split 70/15/15 (never
    random). Lambda chosen on validation MAE (ties -> larger lambda); refit on train + validation and score on the
    test block against persistence (today's value) and the 28-day median. Deployed only when test MAE is at least
    min_improvement better than both baselines. Final model refit on all rows; prediction for as_of + 1 with an
    interval of +/- the 80th percentile of absolute validation residuals."""
    fc = cfg["forecast"]
    names = fc["features"]
    feats = inp["features"]
    ts = inp["target_series"]
    rows = []
    for d in sorted(feats):
        nxt = add_days(d, 1)
        if nxt <= inp["as_of"] and ts.get(nxt) is not None:
            rows.append({"day": d, "x": feats[d], "y": float(ts[nxt])})
    out = {"model_id": fc["algorithm_id"] + "." + inp["target"], "model_version": fc["model_version"],
           "feature_schema_version": fc["feature_schema_version"], "target": inp["target"], "status":
           "INSUFFICIENT_HISTORY", "n_rows": len(rows), "n_train": 0, "n_val": 0, "n_test": 0, "lambda": None,
           "train_start": None, "train_end": None, "val_start": None, "val_end": None, "test_start": None,
           "test_end": None, "features_used": [], "coefficients": {}, "intercept": None, "normalization": {},
           "metrics": None, "baselines": None, "deployed": False, "prediction": None, "interval_low": None,
           "interval_high": None, "classification": "ML_PREDICTED"}
    n = len(rows)
    if n < fc["min_rows"]:
        return out
    n_tr = int(math.floor(fc["split"][0] * n))
    n_va = int(math.floor(fc["split"][1] * n))
    n_te = n - n_tr - n_va
    if n_te < fc["min_test_rows"]:
        return out
    tr, va, te = rows[:n_tr], rows[n_tr:n_tr + n_va], rows[n_tr + n_va:]
    best, best_mae = None, None
    for lam in fc["lambdas"]:
        m = _fit(tr, names, lam)
        if m is None:
            continue
        mae = _metrics([_predict(m, r["x"]) for r in va], [r["y"] for r in va])["mae"]
        if best_mae is None or mae <= best_mae:
            best, best_mae = m, mae
    if best is None:
        out["status"] = "INVALID_INPUT"
        return out
    lam = best["lambda"]
    resid = sorted([abs(_predict(best, r["x"]) - r["y"]) for r in va])
    half = percentile(resid, fc["interval_percentile"])
    m2 = _fit(tr + va, names, lam)
    preds = [_predict(m2, r["x"]) for r in te]
    ys = [r["y"] for r in te]
    met = _metrics(preds, ys)
    covered = 0
    for p_, y in zip(preds, ys):
        if abs(p_ - y) <= half:
            covered += 1
    met["coverage80"] = covered / float(len(ys))
    persist, med28 = [], []
    for r in te:
        prev = [float(ts[d]) for d in window_days(r["day"], -(fc["baseline_median_days"] - 1), 0) if ts.get(d) is not None]
        last = None
        for d in window_days(r["day"], -(fc["baseline_median_days"] - 1), 0):
            if ts.get(d) is not None:
                last = float(ts[d])
        persist.append(last)
        med28.append(median(prev) if prev else None)
    def base_mae(ps):
        pairs = [(p_, y) for p_, y in zip(ps, ys) if p_ is not None]
        return _metrics([p_ for p_, _ in pairs], [y for _, y in pairs])["mae"] if pairs else None
    b_p, b_m = base_mae(persist), base_mae(med28)
    refs = [x for x in (b_p, b_m) if x is not None]
    deployed = bool(refs) and met["mae"] <= (1.0 - fc["min_improvement"]) * min(refs)
    final = _fit(rows, names, lam)
    pred = _predict(final, feats.get(inp["as_of"], {})) if inp["as_of"] in feats else None
    out.update(status="VALID" if deployed else "LOW_CONFIDENCE", n_train=n_tr, n_val=n_va, n_test=n_te, lambda_=None)
    out.pop("lambda_")
    out.update({"lambda": lam, "train_start": tr[0]["day"], "train_end": tr[-1]["day"], "val_start": va[0]["day"],
                "val_end": va[-1]["day"], "test_start": te[0]["day"], "test_end": te[-1]["day"],
                "features_used": [f for f in names if f in final["norm"]],
                "coefficients": dict(("%s:%s" % c, round_to(b, 6)) for c, b in zip(final["cols"], final["beta"])),
                "intercept": round_to(final["intercept"], 6),
                "normalization": dict((f, {"median": round_to(v["median"], 6), "mean": round_to(v["mean"], 6),
                                           "sd": round_to(v["sd"], 6), "indicator": v["indicator"]})
                                      for f, v in final["norm"].items()),
                "metrics": dict((k, round_to(v, 4)) for k, v in met.items()),
                "baselines": {"persistence_mae": round_to(b_p, 4), "median28_mae": round_to(b_m, 4)},
                "deployed": deployed, "prediction": round_to(pred, 2),
                "interval_low": round_to(pred - half, 2) if pred is not None else None,
                "interval_high": round_to(pred + half, 2) if pred is not None else None})
    return out


def forecast_features(inp, cfg):
    """input: inputs (series, nights, workouts, time_zone, nutrition {day: {calories, protein_g}}), from, to.
    Feature rows (schema v1) for every day from..to: raw values, robust z of HRV and resting HR vs their 28 days
    before, sleep duration / efficiency / midpoint, load EWMA (primary method), steps, active energy, workout minutes,
    energy and protein intake, respiratory rate, sleeping temperature, weight. Missing stays null."""
    inputs = inp["inputs"]
    series = inputs.get("series") or {}
    nights = _valid_nights(inputs.get("nights"), cfg)
    nutrition = inputs.get("nutrition") or {}
    days_load = load_days(inputs.get("workouts") or [], inputs.get("time_zone", "UTC"))
    out = {}
    d = inp["from"]
    while d <= inp["to"]:
        row = {}
        for f in ("hrv", "resting_heart_rate", "steps", "active_energy", "respiratory_rate", "wrist_temperature",
                  "weight"):
            v = (series.get(f) or {}).get(d)
            row[f] = None if v is None else float(v)
        for f in ("hrv", "resting_heart_rate"):
            m = cfg["metrics"][f]
            b = _robust(dict((k, v) for k, v in (series.get(f) or {}).items() if v is not None), d, 28,
                        cfg["baseline"]["min_points"]["28"], m["spread_floor"], cfg)
            row[f + "_z"] = None if b["z"] is None else round_to(b["z"], 6)
        n = nights.get(d)
        row["sleep_duration"] = None if n is None else float(n["asleep_min"])
        row["sleep_efficiency"] = None if n is None or n.get("efficiency") is None else float(n["efficiency"])
        row["sleep_midpoint"] = None if n is None or n.get("midpoint_clock") is None else float(n["midpoint_clock"])
        ld = load({"workouts": inputs.get("workouts") or [], "day": d, "time_zone": inputs.get("time_zone", "UTC"),
                   "tracking": True}, cfg)
        pm = ld["primary_method"]
        row["load_acute"] = ld["methods"][pm]["acute"] if pm else None
        row["load_chronic"] = ld["methods"][pm]["chronic"] if pm else None
        row["load_ratio"] = ld["methods"][pm]["ratio"] if pm else None
        row["workout_minutes"] = days_load[d]["minutes"] if d in days_load else (0.0 if pm else None)
        f = nutrition.get(d)
        row["energy_intake"] = None if f is None or f.get("calories") is None else float(f["calories"])
        row["protein_g"] = None if f is None or f.get("protein_g") is None else float(f["protein_g"])
        out[d] = row
        d = add_days(d, 1)
    return out


# ---------------------------------------------------------------------------------------------
# Structured evidence for the Coach (the LLM explains these numbers; it never computes them)
# ---------------------------------------------------------------------------------------------

def evidence(inp, cfg):
    """input: recovery, anomaly, hrv (hrv_status), sleep (sleep_status), load results (any may be null).
    Returns the compact evidence object both platforms send to the Coach."""
    out = {"evidence_version": 1, "items": []}
    r = inp.get("recovery")
    if r is not None:
        item = {"metric": "recovery_indicator", "status": r["status"], "algorithm": "%s@%d" % (r["algorithm_id"],
                r["algorithm_version"]), "classification": r["classification"], "confidence": r["confidence"]}
        if r["score"] is not None:
            item.update(score=r["score"], label=r["label"], drivers=[
                {"metric": d["id"], "direction": d["direction"], "value": d["value"], "baseline": d["baseline"],
                 "z": d["z"], "unit": d["unit"]} for d in r["drivers"]],
                warnings=[w["code"] + ":" + w["metric"] for w in r["warnings"]])
        out["items"].append(item)
    a = inp.get("anomaly")
    if a is not None:
        out["items"].append({"metric": "multi_signal_deviation", "status": a["status"], "state": a["state"],
                             "algorithm": "%s@%d" % (a["algorithm_id"], a["algorithm_version"]),
                             "classification": a["classification"], "confidence": a["confidence"],
                             "persistent": a["persistent"],
                             "signals": [{"metric": s["id"], "z": s["z"], "flagged": s["flagged"]} for s in a["signals"]]})
    h = inp.get("hrv")
    if h is not None:
        b = h["baseline"]
        out["items"].append({"metric": "hrv", "kind": h["kind"], "status": b["status"], "value": b["value"],
                             "baseline": b["median"], "z": b["z"], "trend": h["trend"]["label"],
                             "classification": h["classification"], "confidence": b["confidence"], "unit": "ms"})
    s = inp.get("sleep")
    if s is not None:
        out["items"].append({"metric": "sleep", "status": s["status"], "asleep_min": s["asleep_min"],
                             "need_min": s["need_min"], "need_source": s["need_source"], "debt_min": s["debt_min"],
                             "efficiency": s["efficiency"], "bedtime_sd": s["bedtime_sd"],
                             "confidence": s["confidence"]})
    ld = inp.get("load")
    if ld is not None:
        pm = ld["primary_method"]
        out["items"].append({"metric": "training_load", "status": ld["status"], "state": ld["state"], "method": pm,
                             "acute": ld["methods"][pm]["acute"] if pm else None,
                             "chronic": ld["methods"][pm]["chronic"] if pm else None,
                             "ratio": ld["methods"][pm]["ratio"] if pm else None,
                             "classification": ld["classification"]})
    return out


# ---------------------------------------------------------------------------------------------
# Vector runner
# ---------------------------------------------------------------------------------------------

def decode_series(x):
    if isinstance(x, dict) and set(x) == {"start", "values"}:
        return dict((add_days(x["start"], i), v) for i, v in enumerate(x["values"]) if v is not None)
    return x


def decode_nights(x):
    """{"start": day, "nights": [[asleep_min, in_bed_min, efficiency, midpoint_clock, bedtime_clock, wake_clock]
    | null]} -> {wake_day: {...}}; any other dict passes through."""
    if isinstance(x, dict) and set(x) == {"start", "nights"}:
        out = {}
        for i, n in enumerate(x["nights"]):
            if n is not None:
                out[add_days(x["start"], i)] = {"asleep_min": n[0], "in_bed_min": n[1], "efficiency": n[2],
                                                "midpoint_clock": n[3], "bedtime_clock": n[4], "wake_clock": n[5],
                                                "waso_min": None, "nap_min": None}
        return out
    return x


def decode_inputs(inp):
    out = dict(inp)
    out["series"] = dict((k, decode_series(v)) for k, v in (inp.get("series") or {}).items())
    if "contexts" in inp:
        out["contexts"] = dict((k, decode_series(v)) for k, v in inp["contexts"].items())
    if "sources" in inp:
        out["sources"] = dict((k, decode_series(v)) for k, v in inp["sources"].items())
    if "nights" in inp:
        out["nights"] = decode_nights(inp["nights"])
    if "nutrition" in inp:
        out["nutrition"] = inp["nutrition"]
    return out


def run_case(function, inp, cfg):
    if function == "source_select":
        q = dict(inp)
        q["policy"] = load_policy()
        return source_select(q, cfg)
    if function == "unit_convert":
        return unit_convert(inp, cfg)
    if function == "input_hash":
        return input_hash(inp, cfg)
    if function == "robust_stats":
        v = inp["values"]
        return {"median": round_to(median(v), 6), "mad": round_to(mad(v), 6),
                "p10": round_to(percentile(v, 10), 6), "p90": round_to(percentile(v, 90), 6),
                "theil_sen": [round_to(x, 6) for x in theil_sen(inp["points"])[:2]] if inp.get("points") else None,
                "ranks": ranks(v), "normal_cdf": [round_to(normal_cdf(x), 9) for x in inp.get("z", [])],
                "bh": [round_to(q, 9) for q in bh_qvalues(inp.get("p", []))]}
    if function in ("baseline", "baselines", "trend"):
        q = dict(inp)
        q["series"] = decode_series(inp["series"])
        if "contexts" in inp:
            q["contexts"] = decode_series(inp["contexts"])
        if "sources" in inp:
            q["sources"] = decode_series(inp["sources"])
        return {"baseline": baseline, "baselines": baselines, "trend": trend}[function](q, cfg)
    if function == "hrv_rr":
        return hrv_rr(inp, cfg)
    if function == "hrv_rr_day":
        return hrv_rr_day(inp, cfg)
    if function == "hrv_status":
        q = dict(inp)
        q["series"] = decode_series(inp["series"])
        return hrv_status(q, cfg)
    if function in ("sleep_need", "sleep_status"):
        q = dict(inp)
        if "nights" in inp:
            q["nights"] = decode_nights(inp["nights"])
        if "asleep" in inp:
            q["asleep"] = decode_series(inp["asleep"])
        return {"sleep_need": sleep_need, "sleep_status": sleep_status}[function](q, cfg)
    if function == "load":
        return load(inp, cfg)
    if function == "hrr":
        return hrr(inp, cfg)
    if function in ("recovery", "anomaly"):
        return {"recovery": recovery, "anomaly": anomaly}[function]({"inputs": decode_inputs(inp["inputs"]),
                                                                    "day": inp["day"]}, cfg)
    if function == "correlation":
        return correlation({"as_of": inp["as_of"], "pairs": [{"id": p["id"], "exposure": decode_series(p["exposure"]),
                                                              "outcome": decode_series(p["outcome"])}
                                                             for p in inp["pairs"]]}, cfg)
    if function == "response":
        q = dict(inp)
        q["exposure"] = decode_series(inp["exposure"])
        q["outcome"] = decode_series(inp["outcome"])
        return response(q, cfg)
    if function == "energy":
        return energy(inp, cfg)
    if function == "vo2max_trend":
        return vo2max_trend({"day": inp["day"], "readings": dict((k, decode_series(v)) for k, v in
                                                                 inp["readings"].items())}, cfg)
    if function == "met_intensity":
        return met_intensity(inp, cfg)
    if function == "forecast":
        feats = forecast_features({"inputs": decode_inputs(inp["inputs"]), "from": inp["from"], "to": inp["as_of"]}, cfg)
        target = decode_series(inp["inputs"]["series"][inp["target"]])
        res = forecast({"target": inp["target"], "features": feats, "target_series": target, "as_of": inp["as_of"]}, cfg)
        if inp.get("include_features"):
            res["features_sample"] = dict((d, feats[d]) for d in sorted(feats)[-2:])
        return res
    if function == "evidence":
        return evidence(inp, cfg)
    raise ValueError("unknown function %r" % (function,))


FUNCTIONS = ("source_select", "unit_convert", "input_hash", "robust_stats", "baseline", "baselines", "trend",
             "hrv_rr", "hrv_rr_day", "hrv_status", "sleep_need", "sleep_status", "load", "hrr", "recovery", "anomaly",
             "correlation", "response", "energy", "vo2max_trend", "met_intensity", "forecast", "evidence")
