#!/usr/bin/env python3
"""Ayuvo cycle tracking reference implementation (Python 3 stdlib only).

Period tracker calculations (docs/cycle-tracking.md). The iOS and Android engines must produce exactly what this
module produces for every case in shared/cycle/test-vectors. Pure functions over plain inputs: no clock, no storage.

Days are local calendar days 'yyyy-MM-dd' converted to integer day ordinals (days since 1970-01-01, proleptic
Gregorian, Howard Hinnant's civil algorithm) so time zones, DST and leap years cannot move a period.

Portability rules (same as scripts/workout_reference.py): half-up round_to, written-out loops (no built-in sum, no
statistics module), integer day arithmetic, None for "no data", lists sorted with explicit keys.

State shared by most functions:
  {"today": "yyyy-MM-dd",
   "settings": {"cycle_length", "period_length", "luteal_length", "reminders": {...}} (any field may be null),
   "periods": [{"id", "start", "end" (null = ongoing), "source": "app" | "healthkit" | "health_connect"}],
   "logs": [{"day", "flow", "pain", "pain_locations", "symptoms", "moods"}]}
"""

import json
import math
import os

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
CONFIG_PATH = os.path.join(ROOT, "shared", "cycle", "cycle_config.json")


def load_config(path=CONFIG_PATH):
    with open(path, encoding="utf-8") as fh:
        return json.load(fh)


# ---------------------------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------------------------

def round_to(x, decimals):
    if x is None:
        return None
    scale = 10 ** decimals
    v = math.floor(x * scale + 0.5) / scale
    return 0.0 if v == 0 else v


def round_half_up_int(x):
    return int(math.floor(x + 0.5))


def clamp(x, lo, hi):
    return lo if x < lo else (hi if x > hi else x)


def total(values):
    t = 0.0
    for v in values:
        t += v
    return t


def sorted_ints(values):
    out = list(values)
    out.sort()
    return out


def median(values):
    s = sorted_ints(values)
    n = len(s)
    if n % 2 == 1:
        return float(s[n // 2])
    return (s[n // 2 - 1] + s[n // 2]) / 2.0


def sample_sd(values):
    n = len(values)
    if n < 2:
        return None
    m = total(values) / n
    acc = 0.0
    for v in values:
        acc += (v - m) * (v - m)
    return math.sqrt(acc / (n - 1))


def day_ordinal(text):
    """'yyyy-MM-dd' -> days since 1970-01-01 (Hinnant days_from_civil)."""
    y = int(text[0:4])
    m = int(text[5:7])
    d = int(text[8:10])
    if m <= 2:
        y -= 1
    era = (y if y >= 0 else y - 399) // 400
    yoe = y - era * 400
    mp = m - 3 if m > 2 else m + 9
    doy = (153 * mp + 2) // 5 + d - 1
    doe = yoe * 365 + yoe // 4 - yoe // 100 + doy
    return era * 146097 + doe - 719468


def day_string(n):
    """days since 1970-01-01 -> 'yyyy-MM-dd' (Hinnant civil_from_days)."""
    z = n + 719468
    era = (z if z >= 0 else z - 146096) // 146097
    doe = z - era * 146097
    yoe = (doe - doe // 1460 + doe // 36524 - doe // 146096) // 365
    y = yoe + era * 400
    doy = doe - (365 * yoe + yoe // 4 - yoe // 100)
    mp = (5 * doy + 2) // 153
    d = doy - (153 * mp + 2) // 5 + 1
    m = mp + 3 if mp < 10 else mp - 9
    if m <= 2:
        y += 1
    return "%04d-%02d-%02d" % (y, m, d)


def opt_day(n):
    return None if n is None else day_string(n)


# ---------------------------------------------------------------------------------------------
# Settings
# ---------------------------------------------------------------------------------------------

def effective_settings(settings, cfg):
    """User settings with defaults filled in and every number clamped to the configured limits."""
    settings = settings or {}
    d, lim = cfg["defaults"], cfg["limits"]

    def pick(key, default, lo, hi):
        v = settings.get(key)
        return clamp(int(v) if v is not None else default, lo, hi)

    cycle = pick("cycle_length", d["cycle_length"], lim["setting_cycle_min"], lim["setting_cycle_max"])
    period = pick("period_length", d["period_length"], lim["setting_period_min"], lim["setting_period_max"])
    if period > cycle - 1:
        period = cycle - 1
    luteal = pick("luteal_length", d["luteal_length"], lim["luteal_min"], lim["luteal_max"])
    r = settings.get("reminders") or {}
    days_before = r.get("days_before")
    reminders = {
        "period_soon": bool(r.get("period_soon", True)),
        "days_before": clamp(int(days_before) if days_before is not None else d["reminder_days_before"], 1, 3),
        "period_end": bool(r.get("period_end", True)),
        "daily": bool(r.get("daily", False)),
    }
    return {"cycle_length": cycle, "period_length": period, "luteal_length": luteal, "reminders": reminders}


# ---------------------------------------------------------------------------------------------
# Periods and cycles
# ---------------------------------------------------------------------------------------------

def _eff_end(p, today):
    return today if p["end"] is None else p["end"]


def normalize_periods(periods, today, settings, cfg):
    """Clean the raw period list: drop future/invalid entries, let app periods win over overlapping platform periods,
    merge overlapping or adjacent periods, and close an open period that has run past the period limit."""
    lim = cfg["limits"]
    items, dropped = [], []
    for p in periods:
        s = day_ordinal(p["start"])
        e = None if p.get("end") is None else day_ordinal(p["end"])
        if s > today:
            dropped.append({"id": p["id"], "reason": "future"})
            continue
        if e is not None and e < s:
            dropped.append({"id": p["id"], "reason": "end_before_start"})
            continue
        if e is not None and e > today:
            e = today
        items.append({"id": p["id"], "start": s, "end": e, "source": p.get("source") or "app"})
    apps = []
    for it in items:
        if it["source"] == "app":
            apps.append(it)
    kept = []
    for it in items:
        if it["source"] != "app":
            hit = False
            for a in apps:
                if it["start"] <= _eff_end(a, today) + 1 and a["start"] <= _eff_end(it, today) + 1:
                    hit = True
                    break
            if hit:
                dropped.append({"id": it["id"], "reason": "duplicate"})
                continue
        kept.append(it)
    kept.sort(key=lambda p: (p["start"], 0 if p["source"] == "app" else 1, p["id"]))
    merged = []
    for it in kept:
        if merged and it["start"] <= _eff_end(merged[-1], today) + 1:
            m = merged[-1]
            if m["end"] is None or it["end"] is None:
                m["end"] = None
            elif it["end"] > m["end"]:
                m["end"] = it["end"]
            m["members"].append(it["id"])
            if it["source"] == "app":
                m["source"] = "app"
            continue
        merged.append({"id": it["id"], "start": it["start"], "end": it["end"], "source": it["source"],
                       "members": [it["id"]]})
    out = []
    for m in merged:
        ongoing, auto_closed = False, False
        end = m["end"]
        if end is None:
            if today - m["start"] + 1 > lim["period_max"]:
                end = m["start"] + settings["period_length"] - 1
                auto_closed = True
            else:
                ongoing = True
        length = None if ongoing else end - m["start"] + 1
        out.append({"id": m["id"], "start": m["start"], "end": end, "ongoing": ongoing, "auto_closed": auto_closed,
                    "source": m["source"], "members": m["members"], "length": length,
                    "valid": length is not None and not auto_closed and lim["period_min"] <= length <= lim["period_max"]})
    return out, dropped


def build_cycles(norm, cfg):
    lim = cfg["limits"]
    out = []
    for i, p in enumerate(norm):
        length = norm[i + 1]["start"] - p["start"] if i + 1 < len(norm) else None
        out.append({"start": p["start"], "cycle_length": length, "period_length": p["length"],
                    "cycle_valid": length is not None and lim["cycle_min"] <= length <= lim["cycle_max"],
                    "period_valid": p["valid"]})
    return out


def cycle_stats(cycles, cfg):
    window = cfg["prediction"]["history_window"]
    lengths = []
    for c in cycles:
        if c["cycle_valid"]:
            lengths.append(c["cycle_length"])
    lengths = lengths[-window:]
    periods = []
    for c in cycles:
        if c["period_valid"]:
            periods.append(c["period_length"])
    periods = periods[-window:]
    n = len(lengths)
    out = {"cycle_count": n, "cycle_lengths": lengths, "cycle_median": None, "cycle_mean": None, "cycle_sd": None,
           "cycle_range": None, "variability": "insufficient", "period_count": len(periods), "period_median": None}
    if n > 0:
        s = sorted_ints(lengths)
        out["cycle_median"] = round_to(median(lengths), 1)
        out["cycle_mean"] = round_to(total(lengths) / n, 1)
        out["cycle_sd"] = round_to(sample_sd(lengths), 2)
        if n >= cfg["prediction"]["trimmed_range_min_cycles"]:
            out["cycle_range"] = [s[1], s[-2]]
        else:
            out["cycle_range"] = [s[0], s[-1]]
        var = cfg["variability"]
        if n >= var["min_cycles"]:
            sd = sample_sd(lengths)
            high = s[-1] - s[0] >= var["high_range_days"] or (sd is not None and sd > var["high_sd_days"])
            out["variability"] = "high" if high else "regular"
    if periods:
        out["period_median"] = round_to(median(periods), 1)
    return out


# ---------------------------------------------------------------------------------------------
# Prediction and per-day status
# ---------------------------------------------------------------------------------------------

def _model(state, cfg):
    """Everything the snapshot and the calendar need, computed once."""
    today = day_ordinal(state["today"])
    settings = effective_settings(state.get("settings"), cfg)
    norm, dropped = normalize_periods(state.get("periods") or [], today, settings, cfg)
    cycles = build_cycles(norm, cfg)
    stats = cycle_stats(cycles, cfg)
    model = {"today": today, "settings": settings, "norm": norm, "dropped": dropped, "cycles": cycles,
             "stats": stats, "frames": [], "basis": "none"}
    if not norm:
        return model
    pc = cfg["prediction"]
    span = pc["default_range_days"]
    n = stats["cycle_count"]
    if n == 0:
        basis = "default"
        length = settings["cycle_length"]
        lo, hi = length - span, length + span
    elif n < pc["history_min_cycles"]:
        basis = "limited"
        vals = list(stats["cycle_lengths"]) + [settings["cycle_length"]]
        length = round_half_up_int(median(vals))
        s = sorted_ints(vals)
        lo, hi = min(s[0], length - span), max(s[-1], length + span)
    else:
        basis = "history"
        length = round_half_up_int(stats["cycle_median"])
        lo, hi = min(stats["cycle_range"][0], length), max(stats["cycle_range"][1], length)
    period = round_half_up_int(stats["period_median"]) if stats["period_count"] > 0 else settings["period_length"]
    if period > length - 1:
        period = length - 1
    last = norm[-1]
    raw_next = last["start"] + length
    if raw_next > today:
        eff, late = raw_next, 0
    else:
        eff, late = today + 1, today - raw_next
    range_lo = eff + (lo - length)
    if range_lo < today + 1:
        range_lo = today + 1
    range_hi = eff + (hi - length)
    if range_hi < range_lo:
        range_hi = range_lo
    lim = cfg["limits"]
    frames = []
    for i, p in enumerate(norm):
        is_last = i + 1 == len(norm)
        boundary = eff if is_last else norm[i + 1]["start"]
        raw_boundary = raw_next if is_last else boundary
        if p["ongoing"]:
            logged_end = today
            period_end = p["start"] + period - 1
            if period_end < today:
                period_end = today
        else:
            logged_end = p["end"]
            period_end = p["end"]
        estimate = is_last or lim["cycle_min"] <= boundary - p["start"] <= lim["cycle_max"]
        frames.append({"start": p["start"], "boundary": boundary, "raw_boundary": raw_boundary, "logged_end": logged_end,
                       "period_end": period_end, "predicted": False, "ongoing": p["ongoing"], "is_last": is_last,
                       "estimate": estimate})
    for k in range(pc["project_cycles"]):
        s = eff + k * length
        frames.append({"start": s, "boundary": s + length, "raw_boundary": s + length, "logged_end": None,
                       "period_end": s + period - 1, "predicted": True, "ongoing": False, "is_last": False,
                       "estimate": True})
    for f in frames:
        f["ovulation"], f["fertile"] = _ovulation(f, settings["luteal_length"], cfg) if f["estimate"] else (None, None)
    model.update({"basis": basis, "length": length, "range": [lo, hi], "period": period, "raw_next": raw_next,
                  "eff": eff, "late": late, "next_range": [range_lo, range_hi], "frames": frames})
    return model


def _ovulation(frame, luteal, cfg):
    rb = frame["raw_boundary"]
    pend = frame["period_end"]
    ov = rb - luteal
    if ov < pend + 1:
        ov = pend + 1
    if ov > rb - 2:
        ov = rb - 2
    if ov <= pend:
        return None, None
    fc = cfg["fertile"]
    lo = ov - fc["days_before_ovulation"]
    if lo < pend + 1:
        lo = pend + 1
    hi = ov + fc["days_after_ovulation"]
    if hi > rb - 1:
        hi = rb - 1
    return ov, [lo, hi]


def _status(model, day):
    frames = model["frames"]
    if not frames or day < frames[0]["start"] or day >= frames[-1]["boundary"]:
        return {"day": day_string(day), "cycle_day": None, "phase": "unknown", "period_day": None, "estimated": False}
    f = None
    for fr in frames:
        if fr["start"] <= day < fr["boundary"]:
            f = fr
            break
    cycle_day = day - f["start"] + 1
    phase, period_day, estimated = None, None, True
    if not f["predicted"] and day <= f["logged_end"]:
        phase, period_day, estimated = "period", cycle_day, False
    elif day <= f["period_end"]:
        phase, period_day = "predicted_period", cycle_day
    elif f["is_last"] and not f["ongoing"] and model["raw_next"] <= day <= model["today"]:
        phase = "late"
    elif f["ovulation"] is None:
        phase = "unknown"
    elif day == f["ovulation"]:
        phase = "ovulation"
    elif f["fertile"][0] <= day <= f["fertile"][1]:
        phase = "fertile"
    elif day < f["fertile"][0]:
        phase = "follicular"
    else:
        phase = "luteal"
    return {"day": day_string(day), "cycle_day": cycle_day, "phase": phase, "period_day": period_day,
            "estimated": estimated}


def _prediction(model):
    if model["basis"] == "none":
        return {"basis": "none", "cycle_length": None, "cycle_range": None, "period_length": None,
                "luteal_length": model["settings"]["luteal_length"], "next_start": None, "next_range": None,
                "raw_next_start": None, "late_days": 0, "ongoing": False, "expected_end": None,
                "current_cycle_day": None, "windows": []}
    last = model["norm"][-1]
    windows = []
    for f in model["frames"]:
        if not f["is_last"] and not f["predicted"]:
            continue
        windows.append({"cycle_start": day_string(f["start"]), "predicted_start": f["predicted"],
                        "ovulation": opt_day(f["ovulation"]),
                        "fertile": None if f["fertile"] is None else [day_string(f["fertile"][0]), day_string(f["fertile"][1])],
                        "period_end": day_string(f["period_end"])})
    return {"basis": model["basis"], "cycle_length": model["length"], "cycle_range": model["range"],
            "period_length": model["period"], "luteal_length": model["settings"]["luteal_length"],
            "next_start": day_string(model["eff"]),
            "next_range": [day_string(model["next_range"][0]), day_string(model["next_range"][1])],
            "raw_next_start": day_string(model["raw_next"]), "late_days": model["late"], "ongoing": last["ongoing"],
            "expected_end": day_string(last["start"] + model["period"] - 1) if last["ongoing"] else None,
            "current_cycle_day": model["today"] - last["start"] + 1, "windows": windows}


# ---------------------------------------------------------------------------------------------
# Trends, insights, reminders
# ---------------------------------------------------------------------------------------------

def _logs_by_day(logs):
    out = {}
    for lg in logs or []:
        out[day_ordinal(lg["day"])] = lg
    return out


def _trends(model, logs, cfg):
    by_day = _logs_by_day(logs)
    days = sorted_ints(by_day.keys())
    ranks = {}
    for fl in cfg["flow_levels"]:
        if fl["period"]:
            ranks[fl["key"]] = fl["rank"]
    window = cfg["prediction"]["history_window"]
    cycles_out = []
    logged_frames = []
    for f in model["frames"]:
        if not f["predicted"]:
            logged_frames.append(f)
    for f in logged_frames:
        end = model["today"] + 1 if f["is_last"] else f["boundary"]
        pains, symptoms, moods = [], {}, {}
        for d in days:
            if d < f["start"] or d >= end:
                continue
            lg = by_day[d]
            if lg.get("pain") is not None:
                pains.append(lg["pain"])
            for s in lg.get("symptoms") or []:
                symptoms[s] = symptoms.get(s, 0) + 1
            for m in lg.get("moods") or []:
                moods[m] = moods.get(m, 0) + 1
        flow = []
        plen = f["logged_end"] - f["start"] + 1
        for k in range(min(plen, 10)):
            lg = by_day.get(f["start"] + k)
            fk = lg.get("flow") if lg is not None else None
            flow.append(fk if fk in ranks else None)
        pain_max = None
        for v in pains:
            if pain_max is None or v > pain_max:
                pain_max = v
        cycles_out.append({"start": day_string(f["start"]),
                           "cycle_length": None if f["is_last"] else f["boundary"] - f["start"],
                           "period_length": None if f["ongoing"] else plen,
                           "pain_max": pain_max,
                           "pain_mean": round_to(total(pains) / len(pains), 1) if pains else None,
                           "symptom_days": symptoms, "mood_days": moods, "flow": flow})
    recent = cycles_out[-window:]

    def frequency(catalog, field):
        res = []
        for index, item in enumerate(catalog):
            n = 0
            for c in recent:
                if c[field].get(item["key"], 0) > 0:
                    n += 1
            if n > 0:
                res.append((-n, index, {"key": item["key"], "cycles": n}))
        res.sort(key=lambda r: (r[0], r[1]))
        out = []
        for r in res:
            out.append(r[2])
        return out

    flow_pattern = []
    for k in range(10):
        vals = []
        for c in recent:
            if k < len(c["flow"]) and c["flow"][k] is not None:
                vals.append(ranks[c["flow"][k]])
        if not vals:
            break
        flow_pattern.append(round_to(total(vals) / len(vals), 2))
    return {"cycles": cycles_out, "symptom_frequency": frequency(cfg["symptoms"], "symptom_days"),
            "mood_frequency": frequency(cfg["moods"], "mood_days"), "flow_pattern": flow_pattern,
            "window_cycles": len(recent)}


def _insights(model, trends, cfg):
    rules = cfg["insight_rules"]
    out = []
    if model["basis"] == "none":
        return out
    stats = model["stats"]
    lengths = stats["cycle_lengths"]
    k = rules["recent_cycles"]
    if len(lengths) >= k:
        recent = sorted_ints(lengths[-k:])
        if recent[0] == recent[-1]:
            out.append({"key": "recent_same", "params": {"count": k, "low": recent[0]}})
        else:
            out.append({"key": "recent_range", "params": {"count": k, "low": recent[0], "high": recent[-1]}})
    else:
        out.append({"key": "few_cycles", "params": {}})
    if stats["variability"] == "high":
        out.append({"key": "variable", "params": {}})
    if model["late"] >= rules["late_days"]:
        out.append({"key": "late", "params": {"days": model["late"]}})
    last_k = lengths[-k:]
    if len(last_k) >= rules["out_of_range_min_count"]:
        n_out = 0
        for v in last_k:
            if v < rules["typical_cycle_low"] or v > rules["typical_cycle_high"]:
                n_out += 1
        if n_out >= rules["out_of_range_min_count"]:
            out.append({"key": "out_of_range", "params": {"count": n_out, "total": len(last_k),
                                                          "low": rules["typical_cycle_low"],
                                                          "high": rules["typical_cycle_high"]}})
    last_period = None
    for p in model["norm"]:
        if not p["ongoing"] and not p["auto_closed"]:
            last_period = p
    if last_period is not None and last_period["length"] >= rules["long_period_days"]:
        out.append({"key": "long_period", "params": {"days": last_period["length"]}})
    with_pain = []
    for c in trends["cycles"]:
        if c["pain_max"] is not None:
            with_pain.append(c["pain_max"])
    if len(with_pain) >= 2 and with_pain[-1] >= rules["severe_pain"] and with_pain[-2] >= rules["severe_pain"]:
        out.append({"key": "severe_pain", "params": {}})
    return out


def _reminders(model, cfg):
    r = model["settings"]["reminders"]
    out = []
    if r["daily"]:
        out.append({"kind": "daily_log", "day": None})
    if model["basis"] == "none":
        return out
    last = model["norm"][-1]
    if r["period_soon"] and not last["ongoing"] and model["late"] == 0 and model["raw_next"] > model["today"]:
        day = model["eff"] - r["days_before"]
        if day >= model["today"]:
            out.append({"kind": "period_soon", "day": day_string(day)})
    if r["period_end"] and last["ongoing"]:
        day = last["start"] + model["period"] - 1 + cfg["prediction"]["open_period_extra_days"]
        if day < model["today"]:
            day = model["today"]
        out.append({"kind": "period_end", "day": day_string(day)})
    return out


# ---------------------------------------------------------------------------------------------
# Public functions (one vector file each)
# ---------------------------------------------------------------------------------------------

def _norm_out(norm, dropped):
    periods = []
    for p in norm:
        periods.append({"id": p["id"], "start": day_string(p["start"]), "end": opt_day(p["end"]),
                        "ongoing": p["ongoing"], "auto_closed": p["auto_closed"], "source": p["source"],
                        "members": p["members"], "length": p["length"], "valid": p["valid"]})
    return {"periods": periods, "dropped": dropped}


def v_normalize(inp, cfg):
    today = day_ordinal(inp["today"])
    settings = effective_settings(inp.get("settings"), cfg)
    norm, dropped = normalize_periods(inp["periods"], today, settings, cfg)
    return _norm_out(norm, dropped)


def v_settings(inp, cfg):
    return effective_settings(inp.get("settings"), cfg)


def v_cycles(inp, cfg):
    model = _model(inp, cfg)
    cycles = []
    for c in model["cycles"]:
        cycles.append({"start": day_string(c["start"]), "cycle_length": c["cycle_length"],
                       "period_length": c["period_length"], "cycle_valid": c["cycle_valid"],
                       "period_valid": c["period_valid"]})
    return {"cycles": cycles, "stats": model["stats"]}


def snapshot(inp, cfg):
    """Everything the dashboard needs: normalized periods, stats, prediction, today's status, insights, reminders."""
    model = _model(inp, cfg)
    trends = _trends(model, inp.get("logs"), cfg)
    return {"periods": _norm_out(model["norm"], model["dropped"])["periods"], "stats": model["stats"],
            "prediction": _prediction(model), "today": _status(model, model["today"]),
            "insights": _insights(model, trends, cfg), "reminders": _reminders(model, cfg)}


def v_day_status(inp, cfg):
    """Per-day status for a range of days (the calendar asks for one month grid at a time)."""
    model = _model(inp, cfg)
    first, last = day_ordinal(inp["from"]), day_ordinal(inp["to"])
    out = []
    d = first
    while d <= last:
        out.append(_status(model, d))
        d += 1
    return out


def v_trends(inp, cfg):
    return _trends(_model(inp, cfg), inp.get("logs"), cfg)


def validate_period(inp, cfg):
    """Check a period the user is about to save against today and their other app periods."""
    today = day_ordinal(inp["today"])
    cand = inp["candidate"]
    s = day_ordinal(cand["start"])
    e = None if cand.get("end") is None else day_ordinal(cand["end"])
    errors = []
    if s > today:
        errors.append("future_start")
    if e is not None and e > today:
        errors.append("future_end")
    if e is not None and e < s:
        errors.append("end_before_start")
    duration = None if e is None or e < s else e - s + 1
    if duration is not None and duration > cfg["limits"]["period_max"]:
        errors.append("too_long")
    overlaps = []
    merged = None
    if not errors and e is None:
        for p in inp.get("periods") or []:
            if p.get("id") != cand.get("id") and (p.get("source") or "app") == "app" and day_ordinal(p["start"]) > s:
                errors.append("open_not_latest")
                break
    if not errors:
        lo, hi = s, (today if e is None else e)
        m_lo, m_hi, open_end = s, hi, e is None
        for p in inp.get("periods") or []:
            if p.get("id") == cand.get("id") or (p.get("source") or "app") != "app":
                continue
            ps = day_ordinal(p["start"])
            pe = today if p.get("end") is None else day_ordinal(p["end"])
            if ps <= hi + 1 and lo <= pe + 1:
                overlaps.append(p["id"])
                if ps < m_lo:
                    m_lo = ps
                if pe > m_hi:
                    m_hi = pe
                if p.get("end") is None:
                    open_end = True
        if overlaps:
            errors.append("overlap")
            merged = [day_string(m_lo), None if open_end else day_string(m_hi)]
    overlaps.sort()
    return {"ok": not errors, "errors": errors, "overlaps": overlaps, "merged": merged, "duration": duration}


def apply_period_day(inp, cfg):
    """Turn "this is a period day" on or off for one day; returns the operations on the user's app periods."""
    today = day_ordinal(inp["today"])
    day = day_ordinal(inp["day"])
    on = inp["on"]
    if day > today:
        return {"ops": [], "error": "future"}
    apps = []
    for p in inp.get("periods") or []:
        if (p.get("source") or "app") == "app":
            apps.append({"id": p["id"], "start": day_ordinal(p["start"]),
                         "end": None if p.get("end") is None else day_ordinal(p["end"])})
    apps.sort(key=lambda p: (p["start"], p["id"]))
    containing = None
    for p in apps:
        if p["start"] <= day <= _eff_end(p, today):
            containing = p
            break
    ops = []
    if on:
        if containing is not None:
            return {"ops": [], "error": None}
        before, after = None, None
        for p in apps:
            if p["end"] is not None and p["end"] + 1 == day:
                before = p
            if p["start"] - 1 == day:
                after = p
        if before is not None and after is not None:
            ops.append({"op": "update", "id": before["id"], "start": day_string(before["start"]),
                        "end": opt_day(after["end"])})
            ops.append({"op": "delete", "id": after["id"]})
        elif before is not None:
            ops.append({"op": "update", "id": before["id"], "start": day_string(before["start"]),
                        "end": None if day == today else day_string(day)})
        elif after is not None:
            ops.append({"op": "update", "id": after["id"], "start": day_string(day), "end": opt_day(after["end"])})
        else:
            ops.append({"op": "insert", "start": day_string(day), "end": None if day == today else day_string(day)})
        return {"ops": ops, "error": None}
    if containing is None:
        return {"ops": [], "error": None}
    p = containing
    end = _eff_end(p, today)
    if p["start"] == day and end == day:
        ops.append({"op": "delete", "id": p["id"]})
    elif p["start"] == day:
        ops.append({"op": "update", "id": p["id"], "start": day_string(day + 1), "end": opt_day(p["end"])})
    else:
        ops.append({"op": "update", "id": p["id"], "start": day_string(p["start"]), "end": day_string(day - 1)})
    return {"ops": ops, "error": None}


def v_days(inp, cfg):
    """Day ordinal round trip (leap years, century rules, month/year boundaries)."""
    out = []
    for text in inp["days"]:
        n = day_ordinal(text)
        out.append({"day": text, "ordinal": n, "back": day_string(n), "next": day_string(n + 1),
                    "prev": day_string(n - 1)})
    return out


FUNCTIONS = {
    "apply_period_day": apply_period_day,
    "cycles": v_cycles,
    "day_status": v_day_status,
    "days": v_days,
    "normalize": v_normalize,
    "settings": v_settings,
    "snapshot": snapshot,
    "trends": v_trends,
    "validate_period": validate_period,
}


def run_case(function, inp, cfg):
    return FUNCTIONS[function](inp, cfg)


if __name__ == "__main__":
    import sys
    cfg = load_config()
    print(json.dumps(snapshot(json.loads(sys.stdin.read()), cfg), indent=2))
