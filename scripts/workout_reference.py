#!/usr/bin/env python3
"""Ayuvo workout reference implementation (Python 3 stdlib only).

GPS outdoor workouts, heart rate during a workout, cardio-fitness estimates and strength-session windows
(docs/workouts-gps.md). The iOS and Android recorders must produce exactly what this module produces for every
case in shared/workout/test-vectors. Pure functions over plain inputs: no clock, no storage, no platform APIs.

Portability rules (same as scripts/insights_reference.py): half-up round_to, written-out loops (no built-in sum,
no statistics module), instants in epoch milliseconds, None for "no data".

Encodings:
  gps points   [[t_ms, lat, lon, alt_m | null, h_acc_m, speed_mps | null], ...] in time order (alt is the
               barometric/relative altitude when the device has one, otherwise GPS altitude)
  hr samples   [[t_ms, bpm], ...] in time order (per-second from a watch, per-minute from a band)
"""

import json
import math
import os

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
CONFIG_PATH = os.path.join(ROOT, "shared", "workout", "workout_config.json")
EARTH_RADIUS_M = 6371008.8


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


def haversine_m(lat1, lon1, lat2, lon2):
    p1, p2 = math.radians(lat1), math.radians(lat2)
    dp = p2 - p1
    dl = math.radians(lon2 - lon1)
    a = math.sin(dp / 2) * math.sin(dp / 2) + math.cos(p1) * math.cos(p2) * math.sin(dl / 2) * math.sin(dl / 2)
    return 2.0 * EARTH_RADIUS_M * math.atan2(math.sqrt(a), math.sqrt(1.0 - a))


def in_pause(t, pauses):
    for s, e in pauses:
        if s <= t < e:
            return True
    return False


# ---------------------------------------------------------------------------------------------
# GPS track
# ---------------------------------------------------------------------------------------------

def gps_track(inp, cfg):
    """input: sport ("walk"|"run"|"cycle"|"hike"), points, pauses [[start_ms, end_ms]] (manual pauses),
    start_ms, end_ms (the workout window).

    1. A point is dropped when its horizontal accuracy is worse than max_h_accuracy_m, or when reaching it from the
       last kept point needs a speed above the sport's max_speed_mps (GPS jumps).
    2. A segment between consecutive kept points counts when neither end lies in a manual pause and its speed is at
       least the sport's auto_pause_speed_mps (slower segments are auto-paused: no distance, no moving time).
    3. Splits: the elapsed moving time at each whole kilometre, interpolated inside the segment that crosses it.
    4. Elevation: gain/loss with hysteresis — a change counts once the altitude moves elevation_hysteresis_m away
       from the last anchor."""
    sp = cfg["sports"][inp["sport"]]
    th = cfg["thresholds"]
    pauses = inp.get("pauses") or []
    kept, dropped = [], 0
    for p in inp["points"]:
        if p[4] is None or p[4] > th["max_h_accuracy_m"] or p[0] < inp["start_ms"] or p[0] > inp["end_ms"]:
            dropped += 1
            continue
        if kept:
            q = kept[-1]
            dt = (p[0] - q[0]) / 1000.0
            if dt <= 0:
                dropped += 1
                continue
            if haversine_m(q[1], q[2], p[1], p[2]) / dt > sp["max_speed_mps"]:
                dropped += 1
                continue
        kept.append(p)
    distance, moving = 0.0, 0.0
    splits = []
    next_km = 1000.0
    max_speed = 0.0
    for q, p in zip(kept, kept[1:]):
        if in_pause(q[0], pauses) or in_pause(p[0], pauses):
            continue
        dt = (p[0] - q[0]) / 1000.0
        d = haversine_m(q[1], q[2], p[1], p[2])
        v = d / dt
        if v < sp["auto_pause_speed_mps"]:
            continue
        while distance + d >= next_km:
            frac = (next_km - distance) / d
            splits.append(round_to(moving + frac * dt, 1))
            next_km += 1000.0
        distance += d
        moving += dt
        if v > max_speed:
            max_speed = v
    per_km = []
    prev = 0.0
    for i, s in enumerate(splits):
        per_km.append({"km": i + 1, "seconds": round_to(s - prev, 1)})
        prev = s
    gain, loss = 0.0, 0.0
    anchor = None
    for p in kept:
        alt = p[3]
        if alt is None:
            continue
        if anchor is None:
            anchor = alt
        elif alt - anchor >= th["elevation_hysteresis_m"]:
            gain += alt - anchor
            anchor = alt
        elif anchor - alt >= th["elevation_hysteresis_m"]:
            loss += anchor - alt
            anchor = alt
    elapsed = (inp["end_ms"] - inp["start_ms"]) / 1000.0
    avg_speed = distance / moving if moving > 0 else None
    return {"distance_m": round_to(distance, 1), "moving_s": round_to(moving, 1), "elapsed_s": round_to(elapsed, 1),
            "avg_speed_mps": round_to(avg_speed, 2),
            "avg_pace_s_per_km": round_to(1000.0 / avg_speed, 0) if avg_speed else None,
            "max_speed_mps": round_to(max_speed, 2) if moving > 0 else None, "splits": per_km,
            "elevation_gain_m": round_to(gain, 1), "elevation_loss_m": round_to(loss, 1),
            "kept_points": len(kept), "dropped_points": dropped}


# ---------------------------------------------------------------------------------------------
# Heart rate during a workout
# ---------------------------------------------------------------------------------------------

def _zone(hr, hr_max, rhr, th):
    if rhr is not None and hr_max > rhr:
        x = (hr - rhr) / (hr_max - rhr)
        cuts = th["hrr_zones"]
    else:
        x = hr / hr_max
        cuts = th["hrmax_zones"]
    z = 0
    for c in cuts:
        if x >= c:
            z += 1
    return z


def hr_workout(inp, cfg):
    """input: samples [[t_ms, bpm]], start_ms, end_ms, hr_max, rhr (or null), sex, age, weight_kg.

    Each sample stands for the time until the next sample, capped at max_sample_gap_s (the last one at its own
    cap), clipped to the window. Zone seconds by % heart-rate reserve (or % HRmax without a resting heart rate),
    Banister TRIMP, and Keytel (2005) energy when coverage ≥ keytel_min_coverage."""
    th = cfg["thresholds"]
    s0, s1 = inp["start_ms"], inp["end_ms"]
    samples = [x for x in inp.get("samples") or [] if s0 <= x[0] < s1 and th["hr_valid_min"] <= x[1] <= th["hr_valid_max"]]
    cap = th["max_sample_gap_s"] * 1000
    covered = 0
    zones = [0.0, 0.0, 0.0, 0.0, 0.0]
    weighted, trimp, kcal = 0.0, 0.0, 0.0
    peak = None
    hr_max, rhr = float(inp["hr_max"]), inp.get("rhr")
    sex = inp.get("sex") or "other"
    k = th["trimp_k"].get(sex, th["trimp_k"]["other"])
    for i, (t, bpm) in enumerate(samples):
        nxt = samples[i + 1][0] if i + 1 < len(samples) else s1
        dur = min(nxt, t + cap, s1) - t
        if dur <= 0:
            continue
        covered += dur
        minutes = dur / 60000.0
        weighted += bpm * dur
        zones[_zone(bpm, hr_max, rhr, th)] += dur / 1000.0
        if rhr is not None and hr_max > rhr:
            x = (bpm - rhr) / (hr_max - rhr)
            if x > 0:
                trimp += minutes * x * 0.64 * math.exp(k * x)
        if inp.get("weight_kg") and inp.get("age") is not None:
            kc = th["keytel"].get(sex, th["keytel"]["other"])
            per_min = (kc[0] + kc[1] * bpm + kc[2] * inp["weight_kg"] + kc[3] * inp["age"]) / 4.184
            kcal += max(0.0, per_min) * minutes
        if peak is None or bpm > peak:
            peak = bpm
    window = s1 - s0
    coverage = covered / window if window > 0 else 0.0
    return {"avg_hr": round_to(weighted / covered, 1) if covered else None, "max_hr": peak,
            "coverage_pct": round_to(coverage * 100.0, 1),
            "zone_seconds": [round_to(z, 0) for z in zones],
            "trimp": round_to(trimp, 1) if rhr is not None and covered else None,
            "kcal": round_to(kcal, 0) if covered and coverage >= th["keytel_min_coverage"] and inp.get("weight_kg") else None,
            "zone_method": "hrr" if rhr is not None and hr_max > rhr else "hrmax"}


def hr_recovery(inp, cfg):
    """input: samples [[t_ms, bpm]], end_ms. HRR1 = highest HR in the last 60 s before end − HR at end + 60 s
    (the sample nearest to end + 60 s within ± recovery_tolerance_s). Confidence is "low" when samples are further
    apart than 10 s (a per-minute band)."""
    th = cfg["thresholds"]
    end = inp["end_ms"]
    samples = inp.get("samples") or []
    before = [b for t, b in samples if end - 60000 <= t <= end]
    tol = th["recovery_tolerance_s"] * 1000
    after = [(abs(t - (end + 60000)), t, b) for t, b in samples if abs(t - (end + 60000)) <= tol]
    if not before or not after:
        return {"hrr1": None, "flag_low": False, "confidence": None}
    after.sort()
    peak = max(before)
    drop = peak - after[0][2]
    gaps = [samples[i + 1][0] - samples[i][0] for i in range(len(samples) - 1)]
    dense = bool(gaps) and max(gaps) <= 10000
    return {"hrr1": round_to(drop, 0), "flag_low": drop < th["hrr1_abnormal_below"],
            "confidence": "medium" if dense else "low"}


# ---------------------------------------------------------------------------------------------
# Cardio fitness
# ---------------------------------------------------------------------------------------------

def acsm_vo2(speed_mps, grade, mode):
    s = speed_mps * 60.0  # m/min
    if mode == "run":
        return 0.2 * s + 0.9 * s * grade + 3.5
    return 0.1 * s + 1.8 * s * grade + 3.5


def vo2max_gps(inp, cfg):
    """input: segments [{speed_mps, grade, hr, duration_s}] steady segments of a GPS walk or run, rhr, hr_max, sport.

    ACSM metabolic equations give the oxygen cost of each segment (walking below walk_run_split_mps, running above).
    Heart-rate reserve fraction ≈ VO2-reserve fraction (Swain & Leutholtz 1997), so each qualifying segment gives
    VO2max = 3.5 + (VO2 − 3.5) ÷ %HRR. Segments count when they last ≥ min_segment_s, are flatter than max_grade and
    have %HRR within [min_hrr, max_hrr]; the estimate is their duration-weighted mean."""
    th = cfg["thresholds"]
    rhr, hr_max = inp.get("rhr"), inp.get("hr_max")
    if rhr is None or hr_max is None or hr_max <= rhr:
        return {"vo2max": None, "segments_used": 0, "status": "no_heart_rate_reserve"}
    total_w, total = 0.0, 0.0
    used = 0
    for s in inp.get("segments") or []:
        if s["duration_s"] < th["min_segment_s"] or abs(s["grade"]) > th["max_grade"]:
            continue
        hrr = (s["hr"] - rhr) / (hr_max - rhr)
        if hrr < th["min_hrr"] or hrr > th["max_hrr"]:
            continue
        mode = "run" if s["speed_mps"] >= th["walk_run_split_mps"] else "walk"
        v = 3.5 + (acsm_vo2(s["speed_mps"], s["grade"], mode) - 3.5) / hrr
        total += v * s["duration_s"]
        total_w += s["duration_s"]
        used += 1
    if used == 0:
        return {"vo2max": None, "segments_used": 0, "status": "no_steady_segment"}
    return {"vo2max": round_to(total / total_w, 1), "segments_used": used, "status": "ok"}


def cooper(inp, cfg):
    """input: distance_m covered in a 12-minute all-out test. VO2max = (distance − 504.9) ÷ 44.73 (Cooper 1968)."""
    d = inp.get("distance_m")
    if d is None or d <= 504.9:
        return {"vo2max": None}
    return {"vo2max": round_to((d - 504.9) / 44.73, 1)}


# ---------------------------------------------------------------------------------------------
# Strength-session windows from heart rate
# ---------------------------------------------------------------------------------------------

def workout_windows(inp, cfg):
    """input: hr minute series {start_ms, values}, rhr (or null), hr_max.

    A minute is "active" when HR ≥ min(detect_bpm, rhr + detect_hrr × (hr_max − rhr)) (detect_bpm alone without a
    resting heart rate). Active minutes separated by at most merge_gap_min idle minutes join one window; windows of
    at least min_window_min minutes are returned with their average and peak heart rate."""
    th = cfg["thresholds"]
    rhr = inp.get("rhr")
    level = th["detect_bpm"]
    if rhr is not None and inp["hr_max"] > rhr:
        level = min(level, rhr + th["detect_hrr"] * (inp["hr_max"] - rhr))
    series = inp["hr"]
    t0 = series["start_ms"]
    active = []
    for i, v in enumerate(series["values"]):
        if v is not None and v >= level:
            active.append(t0 + i * 60000)
    windows = []
    for t in active:
        if windows and t - windows[-1][1] <= (th["merge_gap_min"] + 1) * 60000:
            windows[-1][1] = t
        else:
            windows.append([t, t])
    out = []
    for s, e in windows:
        minutes = (e - s) // 60000 + 1
        if minutes < th["min_window_min"]:
            continue
        vals = [series["values"][(t - t0) // 60000] for t in range(s, e + 60000, 60000)]
        vals = [v for v in vals if v is not None]
        out.append({"start_ms": s, "end_ms": e + 60000, "minutes": minutes, "avg_hr": round_to(mean(vals), 1),
                    "max_hr": max(vals)})
    return {"level_bpm": round_to(level, 1), "windows": out}


FUNCTIONS = {"gps_track": gps_track, "hr_workout": hr_workout, "hr_recovery": hr_recovery, "vo2max_gps": vo2max_gps,
             "cooper": cooper, "workout_windows": workout_windows}


def run_case(function, inp, cfg):
    return FUNCTIONS[function](inp, cfg)
