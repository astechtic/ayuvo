#!/usr/bin/env python3
"""Ayuvo intake reference implementation (Python 3 stdlib only): nutrition, nutrient coverage against DRIs,
medication adherence, supplement averaging, strength training volume, energy balance and labs links
(docs/intake-metrics.md). The iOS and Android ports must produce exactly what this module produces for every case in
shared/intake/test-vectors. Pure functions over plain inputs; no clock, no storage, no platform APIs.

Portability rules are those of scripts/insights_reference.py: half-up round_to, written-out loops (no built-in sum, no
statistics module), days as "YYYY-MM-DD", instants in epoch milliseconds, None for "no data".
"""

import datetime as _dt
import json
import math
import os
from zoneinfo import ZoneInfo

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
CONFIG_PATH = os.path.join(ROOT, "shared", "intake", "intake_config.json")


def load_config(path=CONFIG_PATH):
    with open(path, encoding="utf-8") as fh:
        return json.load(fh)


def round_to(x, decimals):
    if x is None:
        return None
    scale = 10 ** decimals
    v = math.floor(x * scale + 0.5) / scale
    return 0.0 if v == 0 else v


def mean(values):
    total = 0.0
    for v in values:
        total += v
    return total / len(values)


def median(values):
    s = sorted(values)
    n = len(s)
    return s[n // 2] if n % 2 == 1 else (s[n // 2 - 1] + s[n // 2]) / 2.0


def add_days(day, n):
    d = _dt.date(int(day[0:4]), int(day[5:7]), int(day[8:10])) + _dt.timedelta(days=n)
    return "%04d-%02d-%02d" % (d.year, d.month, d.day)


def local(ms, tz):
    return _dt.datetime.fromtimestamp(ms / 1000.0, ZoneInfo(tz))


def minute_of_day(ms, tz):
    t = local(ms, tz)
    return t.hour * 60 + t.minute


# ---------------------------------------------------------------------------------------------
# Nutrition (one day)
# ---------------------------------------------------------------------------------------------

def nutrition_day(inp, cfg):
    """input: time_zone, weight_kg (or null), items [{eaten_ms, meal, calories, protein_g, carbs_g, fat_g,
    saturated_fat_g, fiber_g, sodium_mg, potassium_mg, iron_mg, caffeine_mg, is_tea_or_coffee}], bedtime_ms (or null).

    Macro shares use 4/4/9 kcal per gram of the logged grams (Atwater), not the logged calories, so they add up to 100.
    Protein per meal groups items by `meal`. The iron-absorption risk counts tea/coffee items within
    iron_absorption_window_min of an item with at least iron_rich_mg iron (tannins and polyphenols inhibit non-heme
    iron absorption). The eating window runs from the first to the last eaten time; the gap to bed from the last item."""
    th = cfg["thresholds"]
    tz = inp["time_zone"]
    items = sorted(inp.get("items") or [], key=lambda i: i["eaten_ms"])
    out = {"items": len(items), "calories": None, "protein_pct": None, "carbs_pct": None, "fat_pct": None,
           "protein_g_per_kg": None, "saturated_fat_pct": None, "fiber_per_1000kcal": None, "na_k_ratio": None,
           "caffeine_mg": None, "last_caffeine_min": None, "eating_window_min": None, "last_meal_to_bed_min": None,
           "meals_with_protein_target": None, "meals": 0, "iron_absorption_risk": 0}
    if not items:
        return out

    def total(key):
        t = 0.0
        for i in items:
            t += i.get(key) or 0.0
        return t
    kcal = total("calories")
    p, c, f = total("protein_g"), total("carbs_g"), total("fat_g")
    energy = 4.0 * p + 4.0 * c + 9.0 * f
    out["calories"] = round_to(kcal, 0)
    if energy > 0:
        out["protein_pct"] = round_to(400.0 * p / energy, 1)
        out["carbs_pct"] = round_to(400.0 * c / energy, 1)
        out["fat_pct"] = round_to(900.0 * f / energy, 1)
        out["saturated_fat_pct"] = round_to(900.0 * total("saturated_fat_g") / energy, 1)
    w = inp.get("weight_kg")
    if w:
        out["protein_g_per_kg"] = round_to(p / w, 2)
    if kcal > 0:
        out["fiber_per_1000kcal"] = round_to(total("fiber_g") * 1000.0 / kcal, 1)
    k = total("potassium_mg")
    if k > 0:
        # molar ratio: sodium 22.99 g/mol, potassium 39.10 g/mol
        out["na_k_ratio"] = round_to((total("sodium_mg") / 22.99) / (k / 39.10), 2)
    caf = [i for i in items if (i.get("caffeine_mg") or 0) > 0]
    out["caffeine_mg"] = round_to(total("caffeine_mg"), 0)
    if caf:
        out["last_caffeine_min"] = minute_of_day(caf[-1]["eaten_ms"], tz)
    out["eating_window_min"] = round_to((items[-1]["eaten_ms"] - items[0]["eaten_ms"]) / 60000.0, 0)
    if inp.get("bedtime_ms") is not None:
        out["last_meal_to_bed_min"] = round_to((inp["bedtime_ms"] - items[-1]["eaten_ms"]) / 60000.0, 0)
    meals = {}
    for i in items:
        meals.setdefault(i.get("meal") or "other", []).append(i)
    out["meals"] = len(meals)
    if w:
        target = th["protein_per_meal_g_per_kg"] * w
        hit = 0
        for name in sorted(meals):
            mp = 0.0
            for i in meals[name]:
                mp += i.get("protein_g") or 0.0
            if mp >= target:
                hit += 1
        out["meals_with_protein_target"] = hit
    risk = 0
    for t in items:
        if not t.get("is_tea_or_coffee"):
            continue
        for i in items:
            if (i.get("iron_mg") or 0) >= th["iron_rich_mg"] and abs(i["eaten_ms"] - t["eaten_ms"]) <= th["iron_absorption_window_min"] * 60000 and i is not t:
                risk += 1
                break
    out["iron_absorption_risk"] = risk
    return out


# ---------------------------------------------------------------------------------------------
# DRIs and nutrient coverage
# ---------------------------------------------------------------------------------------------

def dri_goals(inp, cfg):
    """input: sex ("male"|"female"|other), age. The NIH/NASEM Dietary Reference Intakes for the person's life stage:
    RDA (or AI where no RDA exists) as `goals`, Tolerable Upper Intake Levels as `upper`, sodium's CDRR as its limit.
    `other` sex uses the higher of the male and female values. Returns the band label and the values."""
    dri = cfg["dri"]
    age = inp.get("age")
    band = None
    for b in dri["age_bands"]:
        if age is not None and b["min"] <= age <= b["max"]:
            band = b["id"]
    if band is None:
        band = dri["age_bands"][0]["id"] if age is not None and age < dri["age_bands"][0]["min"] else dri["age_bands"][-1]["id"]
    sex = inp.get("sex")
    goals = {}
    for key, row in sorted(dri["goals"].items()):
        vals = row[band]
        goals[key] = vals[0] if sex == "male" else (vals[1] if sex == "female" else max(vals[0], vals[1]))
    upper = {}
    for key, v in sorted(dri["upper"].items()):
        upper[key] = v
    return {"band": band, "goals": goals, "upper": upper}


def nutrient_coverage(inp, cfg):
    """input: days [{day, totals {nutrient: amount}}] (logged days only), goals {nutrient: goal}, limits {nutrient:
    upper limit} (for nutrients to keep below). Per nutrient: mean % of goal over logged days (supplements included by
    the caller). A shortfall is a mean below shortfall_pct on at least shortfall_min_days logged days; an excess is a
    mean above 100% of a limit."""
    th = cfg["thresholds"]
    days = inp.get("days") or []
    out = {"days": len(days), "coverage": {}, "shortfalls": [], "excesses": []}
    if len(days) < th["shortfall_min_days"]:
        return out
    for key in sorted(inp.get("goals") or {}):
        g = inp["goals"][key]
        if not g:
            continue
        vals = []
        for d in days:
            vals.append((d["totals"].get(key) or 0.0) * 100.0 / g)
        pct = mean(vals)
        out["coverage"][key] = round_to(pct, 0)
        if pct < th["shortfall_pct"]:
            out["shortfalls"].append(key)
    for key in sorted(inp.get("limits") or {}):
        lim = inp["limits"][key]
        vals = []
        for d in days:
            vals.append(d["totals"].get(key) or 0.0)
        if lim and mean(vals) > lim:
            out["excesses"].append(key)
    return out


def supplement_daily(inp, cfg):
    """input: amount_per_dose, doses [{taken_ms}] in the window, window_days, upper (daily UL or null).
    Averages a supplement over the window (a weekly 60,000 IU vitamin D dose counts 1/7 per day) and flags a daily
    average above the UL (prescribed short courses can exceed it; the copy says to follow the prescriber)."""
    n = len(inp.get("doses") or [])
    avg = inp["amount_per_dose"] * n / inp["window_days"] if inp["window_days"] > 0 else None
    up = inp.get("upper")
    return {"daily_average": round_to(avg, 1), "doses": n, "above_upper": avg is not None and up is not None and avg > up}


# ---------------------------------------------------------------------------------------------
# Medications
# ---------------------------------------------------------------------------------------------

def meds_adherence(inp, cfg):
    """input: time_zone, logs [{scheduled_ms, taken_ms (or null), status "taken"|"missed"|"skipped"}] for one
    medication in the window (PRN doses excluded by the caller).

    Adherence = taken ÷ (taken + missed) doses (skipped doses the person chose to skip are excluded); ≥ 80% is
    "adherent" (the PQA proportion-of-days-covered threshold, applied per dose). On time = taken within on_time_min of
    the schedule. Delay = median minutes late of taken doses. A new reminder time is suggested (median taken clock,
    rounded to 15 minutes) when at least suggest_min_taken doses were taken and the median delay exceeds
    suggest_delay_min. Missed doses are counted per ISO weekday. Streak = consecutive taken doses up to the last one."""
    th = cfg["thresholds"]
    tz = inp["time_zone"]
    logs = sorted(inp.get("logs") or [], key=lambda l: l["scheduled_ms"])
    taken = [l for l in logs if l["status"] == "taken" and l.get("taken_ms") is not None]
    missed = [l for l in logs if l["status"] == "missed"]
    denom = len(taken) + len(missed)
    out = {"scheduled": denom, "taken": len(taken), "adherence_pct": None, "adherent": None, "on_time_pct": None,
           "median_delay_min": None, "suggested_clock_min": None, "missed_by_weekday": [0, 0, 0, 0, 0, 0, 0],
           "streak": 0}
    if denom == 0:
        return out
    pct = len(taken) * 100.0 / denom
    out["adherence_pct"] = round_to(pct, 1)
    out["adherent"] = pct >= th["adherent_pct"]
    if taken:
        delays = [(l["taken_ms"] - l["scheduled_ms"]) / 60000.0 for l in taken]
        on = len([d for d in delays if abs(d) <= th["on_time_min"]])
        out["on_time_pct"] = round_to(on * 100.0 / len(taken), 1)
        md = median(delays)
        out["median_delay_min"] = round_to(md, 0)
        if len(taken) >= th["suggest_min_taken"] and md > th["suggest_delay_min"]:
            clocks = [minute_of_day(l["taken_ms"], tz) for l in taken]
            c = median(clocks)
            out["suggested_clock_min"] = int(math.floor(c / 15.0 + 0.5)) * 15 % 1440
    for l in missed:
        out["missed_by_weekday"][local(l["scheduled_ms"], tz).isoweekday() - 1] += 1
    streak = 0
    for l in reversed(logs):
        if l["status"] == "taken":
            streak += 1
        elif l["status"] == "missed":
            break
    out["streak"] = streak
    return out


# ---------------------------------------------------------------------------------------------
# Strength training
# ---------------------------------------------------------------------------------------------

def epley(weight, reps):
    return weight * (1.0 + reps / 30.0)


def strength_week(inp, cfg):
    """input: sessions [{day, exercises [{name, primary_muscles [..], sets [{reps, weight_kg}]}]}] of one week,
    planned_sessions (or null).

    Hard sets per muscle count every set with reps ≥ 1 for each primary muscle (10–20 per muscle per week is the usual
    hypertrophy range, Schoenfeld 2017). Volume = reps × kg. Push/pull/legs shares come from the muscle groups in
    config. Estimated 1RM (Epley) per exercise uses the best set with 1–12 reps."""
    th = cfg["thresholds"]
    groups = cfg["muscle_groups"]
    sets_by, vol_by = {}, {}
    pattern = {"push": 0.0, "pull": 0.0, "legs": 0.0}
    best = {}
    for s in inp.get("sessions") or []:
        for e in s["exercises"]:
            muscles = e.get("primary_muscles") or []
            for st in e["sets"]:
                reps = st.get("reps") or 0
                w = st.get("weight_kg") or 0.0
                if reps < 1:
                    continue
                for m in muscles:
                    sets_by[m] = sets_by.get(m, 0) + 1
                    vol_by[m] = vol_by.get(m, 0.0) + reps * w
                    for g in ("push", "pull", "legs"):
                        if m in groups[g]:
                            pattern[g] += 1
                if 1 <= reps <= th["e1rm_max_reps"] and w > 0:
                    v = epley(w, reps)
                    if e["name"] not in best or v > best[e["name"]]:
                        best[e["name"]] = v
    total_pattern = pattern["push"] + pattern["pull"] + pattern["legs"]
    below = sorted([m for m in sets_by if sets_by[m] < th["weekly_sets_min"]])
    return {"sessions": len(inp.get("sessions") or []),
            "planned": inp.get("planned_sessions"),
            "sets_by_muscle": dict((m, sets_by[m]) for m in sorted(sets_by)),
            "volume_by_muscle": dict((m, round_to(vol_by[m], 0)) for m in sorted(vol_by)),
            "below_range": below,
            "pattern_pct": dict((g, round_to(pattern[g] * 100.0 / total_pattern, 1) if total_pattern else None)
                                for g in ("push", "pull", "legs")),
            "e1rm": dict((n, round_to(best[n], 1)) for n in sorted(best))}


# ---------------------------------------------------------------------------------------------
# Energy balance
# ---------------------------------------------------------------------------------------------

def energy_balance(inp, cfg):
    """input: day, intake {day: kcal} (logged days only), tdee {day: kcal} (measured), weights {day: kg}.

    Daily balance = intake − measured TDEE. Adaptive TDEE over the last adaptive_window_days: mean logged intake −
    (trend change × energy_per_kg) ÷ days, where the trend is the exponentially smoothed weight (α = ewma_alpha). It
    needs adaptive_min_intake_days logged days and adaptive_min_weighins weigh-ins in the window."""
    th = cfg["thresholds"]
    day = inp["day"]
    intake, tdee, w = inp.get("intake") or {}, inp.get("tdee") or {}, inp.get("weights") or {}
    bal = None
    if day in intake and day in tdee:
        bal = round_to(intake[day] - tdee[day], 0)
    n = th["adaptive_window_days"]
    start = add_days(day, -(n - 1))
    logged = [intake[add_days(start, i)] for i in range(n) if add_days(start, i) in intake]
    weighins = [d for d in w if start <= d <= day]
    out = {"balance_kcal": bal, "adaptive_tdee": None, "trend_change_kg": None, "status": "insufficient"}
    if len(logged) < th["adaptive_min_intake_days"] or len(weighins) < th["adaptive_min_weighins"]:
        return out
    first = min(w)
    trend, cur = {}, None
    d = first
    while d <= day:
        if d in w:
            cur = float(w[d]) if cur is None else cur + th["ewma_alpha"] * (w[d] - cur)
        trend[d] = cur
        d = add_days(d, 1)
    before = trend.get(add_days(start, -1))
    if before is None:
        before = trend[min(weighins)]
    change = trend[day] - before
    out["trend_change_kg"] = round_to(change, 2)
    out["adaptive_tdee"] = round_to(mean(logged) - change * th["energy_per_kg"] / n, 0)
    out["status"] = "ok"
    return out


# ---------------------------------------------------------------------------------------------
# Paired comparison (gym-day sleep cost, sleep → next-day resting heart rate, protein → strength)
# ---------------------------------------------------------------------------------------------

def paired_difference(inp, cfg):
    """input: exposure {day: bool}, outcome {day: number}, lag_days. Compares the outcome lag_days after exposed vs
    unexposed days: mean difference, the group sizes and Cohen's d. Associational only; needs pair_min_group days in
    each group."""
    th = cfg["thresholds"]
    ex, un = [], []
    for d in sorted(inp.get("exposure") or {}):
        o = (inp.get("outcome") or {}).get(add_days(d, inp.get("lag_days") or 0))
        if o is None:
            continue
        (ex if inp["exposure"][d] else un).append(o)
    out = {"n_exposed": len(ex), "n_unexposed": len(un), "difference": None, "cohens_d": None, "status": "insufficient"}
    if len(ex) < th["pair_min_group"] or len(un) < th["pair_min_group"]:
        return out
    me, mu = mean(ex), mean(un)

    def var(v, m):
        t = 0.0
        for x in v:
            t += (x - m) * (x - m)
        return t / (len(v) - 1)
    pooled = math.sqrt(((len(ex) - 1) * var(ex, me) + (len(un) - 1) * var(un, mu)) / (len(ex) + len(un) - 2))
    out["difference"] = round_to(me - mu, 2)
    out["cohens_d"] = round_to((me - mu) / pooled, 2) if pooled > 0 else None
    out["status"] = "ok"
    return out


# ---------------------------------------------------------------------------------------------
# Labs link
# ---------------------------------------------------------------------------------------------

def lab_nutrient_links(inp, cfg):
    """input: labs [{analyte, value, ref_low, ref_high}] (latest per analyte), intake_avg {nutrient: amount per day
    incl. supplements}, goals {nutrient: goal}, supplement_nutrients [nutrient keys any active supplement provides].

    For each configured rule (e.g. low hemoglobin or MCV ↔ iron): when a linked analyte is below its report's
    reference range, report the nutrient's average intake as % of goal and whether a supplement provides it. The
    card only suggests talking to a doctor; it never names a condition."""
    rules = cfg["lab_links"]
    labs = dict((l["analyte"], l) for l in inp.get("labs") or [])
    out = []
    for r in rules:
        low = [a for a in r["analytes"] if a in labs and labs[a]["ref_low"] is not None and labs[a]["value"] < labs[a]["ref_low"]]
        if not low:
            continue
        n = r["nutrient"]
        g = (inp.get("goals") or {}).get(n)
        intake = (inp.get("intake_avg") or {}).get(n)
        out.append({"id": r["id"], "analytes_low": low, "nutrient": n,
                    "intake_pct": round_to(intake * 100.0 / g, 0) if g and intake is not None else None,
                    "supplement_provides": n in (inp.get("supplement_nutrients") or [])})
    return {"links": out}


FUNCTIONS = {"nutrition_day": nutrition_day, "dri_goals": dri_goals, "nutrient_coverage": nutrient_coverage,
             "supplement_daily": supplement_daily, "meds_adherence": meds_adherence, "strength_week": strength_week,
             "energy_balance": energy_balance, "paired_difference": paired_difference,
             "lab_nutrient_links": lab_nutrient_links}


def run_case(function, inp, cfg):
    return FUNCTIONS[function](inp, cfg)
