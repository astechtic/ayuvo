#!/usr/bin/env python3
"""Ayuvo derived-metrics reference implementation (Python 3 stdlib only).

The iOS (ios/.../Services/Derived) and Android (android/.../data/derived) engines must produce exactly what this
module produces for every case in shared/derived/test-vectors. Pure functions over plain inputs: minute series,
sleep rows, day-keyed series, a profile and shared/derived/derived_config.json. No clock, no storage, no platform
APIs (see docs/derived-metrics.md).

Portability rules (same as scripts/insights_reference.py):
  * rounding is round_to(x, d) = floor(x * 10^d + 0.5) / 10^d, never banker's rounding;
  * sums run in list / chronological order; mean, sample SD and median are written out;
  * days are "YYYY-MM-DD"; instants are epoch milliseconds; wall-clock questions use inputs["time_zone"];
  * a missing value is None (null), never 0.

Encodings:
  minute series  {"start_ms": t0, "values": [v | null, ...]}  value i belongs to minute t0 + i * 60000
                 (t0 is a whole minute). Heart rate outside [hr_valid_min, hr_valid_max] is dropped.
  sleep rows     [[start_ms, end_ms, code], ...] codes as shared/health/metric_registry.json "sleep":
                 0 in bed, 1 asleep, 2 awake, 3 light/core, 4 deep, 5 REM, 6 out of bed.
  clock          wall-clock minutes after 12:00 on the day before the wake day (23:00 -> 660, 07:00 -> 1140).
"""

import datetime as _dt
import json
import math
import os
import re
from zoneinfo import ZoneInfo

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
CONFIG_PATH = os.path.join(ROOT, "shared", "derived", "derived_config.json")
MINUTE = 60000
DAY_RE = re.compile("^([0-9][0-9][0-9][0-9])-([0-9][0-9])-([0-9][0-9])$")
ASLEEP = (1, 3, 4, 5)


def load_config(path=CONFIG_PATH):
    with open(path, encoding="utf-8") as fh:
        return json.load(fh)


# ---------------------------------------------------------------------------------------------
# Numbers and time
# ---------------------------------------------------------------------------------------------

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


def sample_sd(values, m):
    total = 0.0
    for v in values:
        total += (v - m) * (v - m)
    return math.sqrt(total / (len(values) - 1))


def median(values):
    s = sorted(values)
    n = len(s)
    if n % 2 == 1:
        return s[n // 2]
    return (s[n // 2 - 1] + s[n // 2]) / 2.0


def parse_day(s):
    m = DAY_RE.match(s or "")
    if not m:
        raise ValueError("bad day %r" % (s,))
    return _dt.date(int(m.group(1)), int(m.group(2)), int(m.group(3)))


def fmt_day(d):
    return "%04d-%02d-%02d" % (d.year, d.month, d.day)


def add_days(day, n):
    return fmt_day(parse_day(day) + _dt.timedelta(days=n))


def day_start_ms(day, time_zone):
    d = parse_day(day)
    t = _dt.datetime(d.year, d.month, d.day, tzinfo=ZoneInfo(time_zone))
    return int(t.timestamp()) * 1000


def local_time(ms, time_zone):
    return _dt.datetime.fromtimestamp(ms / 1000.0, ZoneInfo(time_zone))


def local_day_of(ms, time_zone):
    return fmt_day(local_time(ms, time_zone).date())


def iso_weekday(day):
    return parse_day(day).isoweekday()


def clock_min(ms, wake_day, time_zone):
    """Wall-clock minutes after 12:00 of the day before wake_day (same clock as insights sleep_midpoint_min)."""
    t = local_time(ms, time_zone)
    days = (t.date() - (parse_day(wake_day) - _dt.timedelta(days=1))).days
    return days * 1440 + t.hour * 60 + t.minute - 720


def minute_map(series, lo=None, hi=None):
    """{minute_ms: value} from a minute series; values outside [lo, hi] and nulls are dropped."""
    out = {}
    if not series:
        return out
    t0 = series["start_ms"]
    for i, v in enumerate(series["values"]):
        if v is None:
            continue
        if lo is not None and (v < lo or v > hi):
            continue
        out[t0 + i * MINUTE] = float(v)
    return out


def union(intervals):
    """Merged [start, end) intervals, sorted; drops empty ones."""
    s = sorted([(a, b) for a, b in intervals if b > a])
    out = []
    for a, b in s:
        if out and a <= out[-1][1]:
            if b > out[-1][1]:
                out[-1] = (out[-1][0], b)
        else:
            out.append((a, b))
    return out


def total_ms(intervals):
    t = 0
    for a, b in intervals:
        t += b - a
    return t


# ---------------------------------------------------------------------------------------------
# Heart
# ---------------------------------------------------------------------------------------------

def hr_zone(hr, hr_max, rhr, th):
    """0 below light, 1 light, 2 moderate, 3 vigorous, 4 near-maximal. %HRR when rhr is known, else %HRmax."""
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


def heart_day(inp, cfg):
    """Per-day heart metrics.

    input: time_zone, day, sex, hr (minute series), steps (minute series or null), night ({start_ms, end_ms} of
    the main night whose wake day is `day`, or null), hr_max (number), rhr_ref (number or null: the resting heart
    rate used for zones and TRIMP)."""
    th = cfg["thresholds"]
    tz = inp["time_zone"]
    hr = minute_map(inp.get("hr"), th["hr_valid_min"], th["hr_valid_max"])
    steps = minute_map(inp.get("steps")) if inp.get("steps") is not None else None
    d0 = day_start_ms(inp["day"], tz)
    d1 = day_start_ms(add_days(inp["day"], 1), tz)
    night = inp.get("night")
    out = {"resting_hr": None, "sleeping_hr": None, "lowest_hr": None, "dip_pct": None, "sedentary_hr": None,
           "day_avg": None, "day_min": None, "day_max": None, "observed_max": None, "wear_minutes": 0,
           "completeness_pct": 0.0, "valid_day": False, "zone_method": None, "light_min": 0, "moderate_min": 0,
           "vigorous_min": 0, "max_min": 0, "moderate_equivalent": 0, "trimp": None, "walking_hr": None,
           "rhr_status": "no_night"}

    def in_night(t):
        return night is not None and night["start_ms"] <= t < night["end_ms"]

    # night
    if night is not None:
        window = [t for t in sorted(hr) if night["start_ms"] <= t < night["end_ms"]]
        span_min = (night["end_ms"] - night["start_ms"]) // MINUTE
        coverage = len(window) / span_min if span_min > 0 else 0.0
        if span_min < th["rhr_min_sleep_min"]:
            out["rhr_status"] = "short_night"
        elif coverage < th["rhr_min_coverage"]:
            out["rhr_status"] = "low_coverage"
        else:
            n = th["rhr_window_min"]
            best = None
            for t in window:
                vals = []
                for k in range(n):
                    v = hr.get(t + k * MINUTE)
                    if v is None or t + k * MINUTE >= night["end_ms"]:
                        vals = None
                        break
                    vals.append(v)
                if vals is not None:
                    m = mean(vals)
                    if best is None or m < best:
                        best = m
            out["resting_hr"] = round_to(best, 1)
            out["rhr_status"] = "ok" if best is not None else "low_coverage"
        if window:
            vals = [hr[t] for t in window]
            out["sleeping_hr"] = round_to(mean(vals), 1)
            out["lowest_hr"] = round_to(min(vals), 1)
            wake_end = night["end_ms"] + th["wake_window_hours"] * 3600000
            wake = [hr[t] for t in sorted(hr) if night["end_ms"] <= t < wake_end]
            if len(wake) >= th["dip_min_wake_min"] and out["rhr_status"] == "ok":
                out["dip_pct"] = round_to((1.0 - mean(vals) / mean(wake)) * 100.0, 1)

    day_minutes = [t for t in sorted(hr) if d0 <= t < d1]
    out["wear_minutes"] = len(day_minutes)
    out["completeness_pct"] = round_to(len(day_minutes) * 100.0 / ((d1 - d0) // MINUTE), 1)
    out["valid_day"] = len(day_minutes) >= th["valid_wear_min"]

    # daytime
    awake = [hr[t] for t in day_minutes if not in_night(t)]
    if len(awake) >= th["daytime_min_minutes"]:
        out["day_avg"] = round_to(mean(awake), 1)
        out["day_min"] = round_to(min(awake), 1)
        out["day_max"] = round_to(max(awake), 1)

    # observed max: highest 2-minute mean
    best2 = None
    for t in day_minutes:
        v2 = hr.get(t + MINUTE)
        if v2 is not None:
            m = (hr[t] + v2) / 2.0
            if best2 is None or m > best2:
                best2 = m
    out["observed_max"] = round_to(best2, 1)

    # sedentary daytime
    if steps is not None:
        sed = []
        for t in day_minutes:
            lt = local_time(t, tz)
            if not (th["sedentary_start_hour"] <= lt.hour < th["sedentary_end_hour"]) or in_night(t):
                continue
            still = True
            for k in range(th["sedentary_still_min"] + 1):
                if steps.get(t - k * MINUTE, 0.0) > 0:
                    still = False
                    break
            if still:
                sed.append(hr[t])
        if len(sed) >= th["sedentary_min_minutes"]:
            out["sedentary_hr"] = round_to(mean(sed), 1)

        # walking heart rate: runs of >= walking_run_min minutes with >= walking_min_steps
        walk = []
        run = []
        t = d0
        while t < d1:
            if th["walking_min_steps"] <= steps.get(t, 0.0) <= th["walking_max_steps"]:
                run.append(t)
            else:
                if len(run) >= th["walking_run_min"]:
                    walk.extend([hr[x] for x in run if x in hr])
                run = []
            t += MINUTE
        if len(run) >= th["walking_run_min"]:
            walk.extend([hr[x] for x in run if x in hr])
        if len(walk) >= th["walking_min_minutes"]:
            out["walking_hr"] = round_to(mean(walk), 1)

    # zones and TRIMP
    hr_max = float(inp["hr_max"])
    rhr = inp.get("rhr_ref")
    out["zone_method"] = "hrr" if rhr is not None and hr_max > rhr else "hrmax"
    counts = [0, 0, 0, 0, 0]
    trimp = 0.0
    k = th["trimp_k"].get(inp.get("sex") or "other", th["trimp_k"]["other"])
    for t in day_minutes:
        v = hr[t]
        counts[hr_zone(v, hr_max, rhr, th)] += 1
        if out["zone_method"] == "hrr":
            x = (v - rhr) / (hr_max - rhr)
            if x >= th["trimp_min_hrr"]:
                trimp += x * 0.64 * math.exp(k * x)
    out["light_min"], out["moderate_min"], out["vigorous_min"], out["max_min"] = counts[1], counts[2], counts[3], counts[4]
    out["moderate_equivalent"] = counts[2] + 2 * (counts[3] + counts[4])
    out["trimp"] = round_to(trimp, 1) if out["zone_method"] == "hrr" else None
    return out


def hr_max(inp, cfg):
    """input: age, observed ([number] daily observed maxima from the lookback window), rhr (number or null)."""
    th = cfg["thresholds"]
    tanaka = th["tanaka"][0] - th["tanaka"][1] * inp["age"]
    obs = [v for v in (inp.get("observed") or []) if v is not None]
    top = max(obs) if obs else None
    if top is not None and top > tanaka:
        value, method = top, "observed"
    else:
        value, method = tanaka, "tanaka"
    rhr = inp.get("rhr")
    return {"hr_max": round_to(value, 1), "method": method,
            "hr_reserve": round_to(value - rhr, 1) if rhr is not None else None}


def vo2max_uth(inp, cfg):
    """input: hr_max, hr_max_method, rhr."""
    rhr = inp.get("rhr")
    if rhr is None or rhr <= 0 or inp.get("hr_max") is None:
        return {"vo2max": None, "confidence": None}
    v = cfg["thresholds"]["uth_factor"] * inp["hr_max"] / rhr
    return {"vo2max": round_to(v, 1), "confidence": "medium" if inp.get("hr_max_method") == "observed" else "low"}


def rhr_strain(inp, cfg):
    """input: series {day: resting hr}, day."""
    th = cfg["thresholds"]
    s = inp["series"]
    day = inp["day"]

    def base(as_of):
        vals = [s[add_days(as_of, -i)] for i in range(th["strain_window_days"], 0, -1)
                if s.get(add_days(as_of, -i)) is not None]
        if len(vals) < th["strain_min_days"]:
            return None
        m = mean(vals)
        return m, max(sample_sd(vals, m), th["strain_sd_floor"])

    today = s.get(day)
    b = base(day)
    if today is None or b is None:
        return {"status": "insufficient", "baseline": None, "deviation": None, "z": None, "flag": False}
    m, sd = b
    dev = today - m
    y = s.get(add_days(day, -1))
    yb = base(add_days(day, -1))
    flag = dev >= th["strain_delta_bpm"] and y is not None and yb is not None and y - yb[0] >= th["strain_delta_bpm"]
    return {"status": "ok", "baseline": round_to(m, 1), "deviation": round_to(dev, 1), "z": round_to(dev / sd, 2),
            "flag": flag}


# ---------------------------------------------------------------------------------------------
# Sleep
# ---------------------------------------------------------------------------------------------

def sleep_nights(inp, cfg):
    """Night grouping shared by both platforms (docs/health-data.md §1.3, iOS HealthSleepAnalysis).

    input: time_zone, rows [[start_ms, end_ms, code, source_id]] (every sleep row, any source, deleted rows removed).
    Rows sorted by (start, then input order) are chained into episodes while each next row starts at most
    episode_gap_hours after the episode's latest end; an episode belongs to the local day of its latest-ending row
    (the wake day). Per wake day the source with the most unioned asleep time wins (ties: more rows, then the
    smaller source id); out-of-bed rows are ignored. Returns {wake_day: {source, rows, window: {start_ms, end_ms}}}
    where window spans first to last asleep instant (null when the source has no asleep rows)."""
    tz = inp["time_zone"]
    gap = cfg["thresholds"]["episode_gap_hours"] * 3600000
    rows = [r for r in inp["rows"] if r[1] >= r[0]]
    order = sorted(range(len(rows)), key=lambda i: (rows[i][0], i))
    groups = {}
    episode, end = [], None

    def flush():
        if not episode:
            return
        last = max(episode, key=lambda r: r[1])
        groups.setdefault(local_day_of(last[1], tz), []).extend(episode)

    for i in order:
        r = rows[i]
        if episode and r[0] > end + gap:
            flush()
            episode = []
        if not episode:
            end = r[1]
        episode.append(r)
        end = max(end, r[1])
    flush()
    out = {}
    for day in sorted(groups):
        cand = [r for r in groups[day] if r[2] != 6]
        by = {}
        for r in cand:
            by.setdefault(r[3], []).append(r)
        if not by:
            continue
        ranked = sorted(by, key=lambda s: (-total_ms(union([(r[0], r[1]) for r in by[s] if r[2] in ASLEEP])),
                                           -len(by[s]), s))
        src = ranked[0]
        chosen = sorted(by[src], key=lambda r: (r[0], r[1], r[2]))
        asleep = union([(r[0], r[1]) for r in chosen if r[2] in ASLEEP])
        out[day] = {"source": src, "rows": [[r[0], r[1], r[2]] for r in chosen],
                    "window": {"start_ms": asleep[0][0], "end_ms": asleep[-1][1]} if asleep else None}
    return out


def sleep_night(inp, cfg):
    """input: time_zone, wake_day, rows (the chosen source's rows for the night)."""
    th = cfg["thresholds"]
    tz = inp["time_zone"]
    wd = inp["wake_day"]
    rows = [r for r in inp["rows"] if r[2] != 6 and r[1] > r[0]]
    empty = {"asleep_min": None, "in_bed_min": None, "efficiency": None, "onset_latency_min": None, "deep_pct": None,
             "rem_pct": None, "light_pct": None, "wakeups": None, "waso_min": None, "bedtime_clock": None,
             "wake_clock": None, "midpoint_clock": None, "deep_latency_min": None, "rem_latency_min": None,
             "staged": False}
    asleep = union([(r[0], r[1]) for r in rows if r[2] in ASLEEP])
    if not asleep:
        return empty
    out = dict(empty)
    asleep_ms = total_ms(asleep)
    bed_rows = [(r[0], r[1]) for r in rows if r[2] == 0]
    first_row = min(r[0] for r in rows)
    in_bed = total_ms(union(bed_rows)) if bed_rows else total_ms(union([(r[0], r[1]) for r in rows]))
    if in_bed < asleep_ms:
        in_bed = max(r[1] for r in rows) - first_row
    onset, final = asleep[0][0], asleep[-1][1]
    out["asleep_min"] = round_to(asleep_ms / 60000.0, 1)
    out["in_bed_min"] = round_to(in_bed / 60000.0, 1)
    out["efficiency"] = round_to(min(100.0, asleep_ms * 100.0 / in_bed), 1)
    if bed_rows:
        bed_start = min(a for a, _ in bed_rows)
        out["onset_latency_min"] = round_to(max(0, onset - bed_start) / 60000.0, 1)
    staged = [r for r in rows if r[2] in (3, 4, 5)]
    if staged:
        out["staged"] = True
        deep = total_ms(union([(r[0], r[1]) for r in rows if r[2] == 4]))
        rem = total_ms(union([(r[0], r[1]) for r in rows if r[2] == 5]))
        light = total_ms(union([(r[0], r[1]) for r in rows if r[2] == 3]))
        out["deep_pct"] = round_to(deep * 100.0 / asleep_ms, 1)
        out["rem_pct"] = round_to(rem * 100.0 / asleep_ms, 1)
        out["light_pct"] = round_to(light * 100.0 / asleep_ms, 1)
        firsts = {}
        for r in sorted(staged):
            if r[2] not in firsts:
                firsts[r[2]] = r[0]
        if 4 in firsts:
            out["deep_latency_min"] = round_to((firsts[4] - onset) / 60000.0, 1)
        if 5 in firsts:
            out["rem_latency_min"] = round_to((firsts[5] - onset) / 60000.0, 1)
    wakeups, waso = 0, 0
    for (a, b), (c, _) in zip(asleep, asleep[1:]):
        gap = c - b
        waso += gap
        if gap >= th["wakeup_min_gap_min"] * MINUTE:
            wakeups += 1
    out["wakeups"] = wakeups
    out["waso_min"] = round_to(waso / 60000.0, 1)
    out["bedtime_clock"] = clock_min(first_row, wd, tz)
    out["wake_clock"] = clock_min(final, wd, tz)
    out["midpoint_clock"] = clock_min(onset + (final - onset) // 2, wd, tz)
    return out


def sleep_regularity(inp, cfg):
    """input: time_zone, day, need_min (or null for the default), nights {wake_day: {rows}} for the window."""
    th = cfg["thresholds"]
    tz = inp["time_zone"]
    day = inp["day"]
    need = inp.get("need_min") or th["sleep_need_min"]
    win = [add_days(day, -i) for i in range(th["regularity_window_days"] - 1, -1, -1)]
    nights = {}
    for d in win:
        n = (inp.get("nights") or {}).get(d)
        if not n:
            continue
        rows = [r for r in n["rows"] if r[2] != 6 and r[1] > r[0]]
        asleep = union([(r[0], r[1]) for r in rows if r[2] in ASLEEP])
        if not asleep:
            continue
        first = min(r[0] for r in rows)
        onset, final = asleep[0][0], asleep[-1][1]
        nights[d] = {"asleep": asleep, "asleep_min": total_ms(asleep) / 60000.0,
                     "bed": clock_min(first, d, tz), "wake": clock_min(final, d, tz),
                     "mid": clock_min(onset + (final - onset) // 2, d, tz)}
    out = {"nights": len(nights), "bedtime_sd": None, "wake_sd": None, "sri": None, "sri_pairs": 0,
           "social_jetlag_min": None, "msfsc_clock": None, "sleep_debt_min": None}
    days = [d for d in win if d in nights]
    if len(days) >= th["regularity_min_nights"]:
        beds = [nights[d]["bed"] for d in days]
        wakes = [nights[d]["wake"] for d in days]
        out["bedtime_sd"] = round_to(sample_sd(beds, mean(beds)), 1)
        out["wake_sd"] = round_to(sample_sd(wakes, mean(wakes)), 1)
    # SRI over consecutive noon-to-noon windows
    agree, total, pairs = 0, 0, 0
    for a, b in zip(win, win[1:]):
        if a not in nights or b not in nights:
            continue
        pairs += 1
        sa = day_start_ms(add_days(a, -1), tz) + 12 * 3600000
        sb = day_start_ms(add_days(b, -1), tz) + 12 * 3600000
        for i in range(1440):
            ta, tb = sa + i * MINUTE, sb + i * MINUTE
            xa = any(s <= ta < e for s, e in nights[a]["asleep"])
            xb = any(s <= tb < e for s, e in nights[b]["asleep"])
            agree += 1 if xa == xb else 0
            total += 1
    out["sri_pairs"] = pairs
    if pairs >= th["sri_min_pairs"]:
        out["sri"] = round_to(200.0 * agree / total - 100.0, 1)
    free = [d for d in days if iso_weekday(d) in th["free_wake_weekdays"]]
    work = [d for d in days if iso_weekday(d) not in th["free_wake_weekdays"]]
    if len(free) >= th["social_min_free"] and len(work) >= th["social_min_work"]:
        mf = mean([nights[d]["mid"] for d in free])
        mw = mean([nights[d]["mid"] for d in work])
        out["social_jetlag_min"] = round_to(abs(mf - mw), 1)
        sdf = mean([nights[d]["asleep_min"] for d in free])
        sdw = mean([nights[d]["asleep_min"] for d in work])
        msf = mf
        if sdf > sdw:
            sdweek = (5.0 * sdw + 2.0 * sdf) / 7.0
            msf = mf - (sdf - sdweek) / 2.0
        out["msfsc_clock"] = round_to(msf, 1)
    if days:
        debt = 0.0
        for d in days:
            debt += max(0.0, need - nights[d]["asleep_min"])
        out["sleep_debt_min"] = round_to(debt, 1)
    return out


# ---------------------------------------------------------------------------------------------
# Activity
# ---------------------------------------------------------------------------------------------

def activity_day(inp, cfg):
    """input: time_zone, day, sources {name: {kind: "phone"|"wearable", hourly: [24 numbers|null]}},
    minute_steps (minute series from one wearable, or null), wear (minute series of heart rate, or null),
    steps_total (platform de-duplicated total, or null)."""
    th = cfg["thresholds"]
    tz = inp["time_zone"]
    srcs = inp.get("sources") or {}
    names = sorted(srcs)
    hourly = []
    for h in range(24):
        vals = [srcs[n]["hourly"][h] for n in names if srcs[n]["hourly"][h] is not None]
        hourly.append(max(vals) if vals else 0.0)
    dedup = 0.0
    for v in hourly:
        dedup += v
    total = inp.get("steps_total")
    if total is None:
        total = dedup
    band = 0
    for c in th["step_bands"]:
        if total >= c:
            band += 1
    active = 0
    for h in range(th["active_hour_first"], th["active_hour_last"] + 1):
        if hourly[h] >= th["active_hour_steps"]:
            active += 1
    out = {"dedup_steps": round_to(dedup, 0), "step_band_index": band, "active_hours": active,
           "brisk_minutes": None, "peak30_cadence": None, "longest_sedentary_min": None, "phone_missing_steps": 0}
    phone = [n for n in names if srcs[n]["kind"] == "phone"]
    wear = [n for n in names if srcs[n]["kind"] == "wearable"]
    if phone and wear:
        pt = sum_hours(srcs[phone[0]]["hourly"])
        wt = sum_hours(srcs[wear[0]]["hourly"])
        if wt >= th["phone_min_wearable_steps"] and pt < th["phone_ratio"] * wt:
            out["phone_missing_steps"] = round_to(wt - pt, 0)
    ms = inp.get("minute_steps")
    if ms is not None:
        steps = minute_map(ms)
        d0 = day_start_ms(inp["day"], tz)
        d1 = day_start_ms(add_days(inp["day"], 1), tz)
        mins = [steps[t] for t in sorted(steps) if d0 <= t < d1]
        out["brisk_minutes"] = len([v for v in mins if v >= th["brisk_cadence"]])
        top = sorted(mins, reverse=True)[:th["peak_minutes"]]
        while len(top) < th["peak_minutes"]:
            top.append(0.0)
        out["peak30_cadence"] = round_to(mean(top), 1)
        if inp.get("wear") is not None:
            worn = minute_map(inp["wear"], th["hr_valid_min"], th["hr_valid_max"])
            best, run = 0, 0
            t = d0
            while t < d1:
                h = local_time(t, tz).hour
                if th["active_hour_first"] <= h <= th["active_hour_last"] and t in worn and steps.get(t, 0.0) == 0:
                    run += 1
                    best = max(best, run)
                else:
                    run = 0
                t += MINUTE
            out["longest_sedentary_min"] = best
    return out


def sum_hours(values):
    t = 0.0
    for v in values:
        if v is not None:
            t += v
    return t


def step_streak(inp, cfg):
    """input: series {day: steps}, day, goal, window_days."""
    s, day, goal = inp["series"], inp["day"], inp["goal"]
    if goal <= 0:
        return {"current": 0, "best": 0}
    cur = 0
    d = day if (s.get(day) or 0) >= goal else add_days(day, -1)
    while (s.get(d) or 0) >= goal:
        cur += 1
        d = add_days(d, -1)
    best, run = 0, 0
    for i in range(inp["window_days"] - 1, -1, -1):
        if (s.get(add_days(day, -i)) or 0) >= goal:
            run += 1
            best = max(best, run)
        else:
            run = 0
    return {"current": cur, "best": max(best, cur)}


def stride(inp, cfg):
    """input: distance_m, steps (same phone source), height_cm, sex."""
    th = cfg["thresholds"]
    f = th["stride_factor"].get(inp.get("sex") or "other", th["stride_factor"]["other"])
    expected = round_to(f * inp["height_cm"] / 100.0, 2) if inp.get("height_cm") else None
    if not inp.get("steps") or inp["steps"] < th["stride_min_steps"] or inp.get("distance_m") is None:
        return {"stride_m": None, "expected_m": expected, "ratio_pct": None}
    s = inp["distance_m"] / inp["steps"]
    return {"stride_m": round_to(s, 2), "expected_m": expected,
            "ratio_pct": round_to(s * 100.0 / (f * inp["height_cm"] / 100.0), 1) if expected else None}


# ---------------------------------------------------------------------------------------------
# Energy
# ---------------------------------------------------------------------------------------------

def mifflin(weight_kg, height_cm, age, sex, th):
    return 10.0 * weight_kg + 6.25 * height_cm - 5.0 * age + th["mifflin"].get(sex or "other", th["mifflin"]["other"])


def energy_day(inp, cfg):
    """input: resting_kcal, active_kcal (Ayuvo's own workout burns excluded), weight_kg, height_cm, age, sex."""
    th = cfg["thresholds"]
    bmr = None
    if inp.get("weight_kg") and inp.get("height_cm") and inp.get("age") is not None:
        bmr = mifflin(inp["weight_kg"], inp["height_cm"], inp["age"], inp.get("sex"), th)
    out = {"bmr_mifflin": round_to(bmr, 0), "resting_kcal": None, "tdee": None, "pal": None, "pal_band_index": None,
           "status": "no_resting"}
    rest = inp.get("resting_kcal")
    if rest is None or rest <= 0:
        return out
    if bmr is not None and rest < th["bmr_min_share"] * bmr:
        out["status"] = "partial_day"
        return out
    active = inp.get("active_kcal") or 0.0
    tdee = (rest + active) / (1.0 - th["tef_share"])
    pal = tdee / rest
    band = 0
    for c in th["pal_bands"]:
        if pal >= c:
            band += 1
    out.update({"resting_kcal": round_to(rest, 0), "tdee": round_to(tdee, 0), "pal": round_to(pal, 2),
                "pal_band_index": band, "status": "ok"})
    return out


# ---------------------------------------------------------------------------------------------
# Mobility and hearing
# ---------------------------------------------------------------------------------------------

def gait_week(inp, cfg):
    """input: walking_speed, double_support, asymmetry: lists of readings from the last 7 days."""
    th = cfg["thresholds"]
    sp = [v for v in inp.get("walking_speed") or [] if v is not None and v > 0]
    ds = [v for v in inp.get("double_support") or [] if v is not None]
    asy = [v for v in inp.get("asymmetry") or [] if v is not None and v < 100]
    speed = median(sp) if sp else None
    count = len([v for v in asy if v > th["asymmetry_pct"]])
    return {"speed_median": round_to(speed, 2), "slow_gait": speed is not None and speed < th["slow_gait_mps"],
            "double_support_median": round_to(median(ds), 1) if ds else None,
            "asymmetry_flag_count": count, "asymmetry_flag": count >= th["asymmetry_flag_count"]}


def audio_day(inp, cfg):
    """input: samples [[start_ms, end_ms, dB]] for the day (headphone exposure)."""
    th = cfg["thresholds"]
    dose, loud = 0.0, 0.0
    for s, e, db in inp.get("samples") or []:
        hours = max(0, e - s) / 3600000.0
        dose += hours * math.pow(2.0, (db - th["sound_ref_db"]) / th["sound_exchange_db"])
        if db >= th["sound_ref_db"]:
            loud += max(0, e - s) / 60000.0
    return {"dose_pct": round_to(dose * 100.0 / th["sound_weekly_hours"], 2), "loud_minutes": round_to(loud, 1)}


# ---------------------------------------------------------------------------------------------
# Body
# ---------------------------------------------------------------------------------------------

def body_trend(inp, cfg):
    """input: weights {day: kg} (last weigh-in per day), day, height_m, goal_kg (or null), scheme "who"|"asian"."""
    th = cfg["thresholds"]
    w = inp.get("weights") or {}
    days = sorted(d for d in w if d <= inp["day"])
    out = {"trend_kg": None, "rate_kg_week": None, "bmi": None, "bmi_category_index": None, "healthy_low_kg": None,
           "healthy_high_kg": None, "eta_weeks": None}
    scheme = inp.get("scheme") or "who"
    h = inp.get("height_m")
    if h:
        lo, hi = th["healthy_bmi"][scheme]
        out["healthy_low_kg"] = round_to(lo * h * h, 1)
        out["healthy_high_kg"] = round_to(hi * h * h, 1)
    if not days:
        return out
    trend = {}
    cur = None
    d = days[0]
    while d <= inp["day"]:
        if d in w:
            cur = float(w[d]) if cur is None else cur + th["ewma_alpha"] * (w[d] - cur)
        trend[d] = cur
        d = add_days(d, 1)
    t = trend[inp["day"]]
    out["trend_kg"] = round_to(t, 2)
    start = add_days(inp["day"], -th["rate_window_days"])
    recent = [x for x in days if x > start]
    if start in trend and len(recent) >= th["rate_min_weighins"]:
        rate = (t - trend[start]) * 7.0 / th["rate_window_days"]
        out["rate_kg_week"] = round_to(rate, 2)
        g = inp.get("goal_kg")
        if g is not None and abs(rate) >= th["eta_min_rate"] and (g - t) * rate > 0:
            out["eta_weeks"] = round_to((g - t) / rate, 1)
    if h:
        bmi = t / (h * h)
        cat = 0
        for c in th["bmi"][scheme]:
            if bmi >= c:
                cat += 1
        out["bmi"] = round_to(bmi, 1)
        out["bmi_category_index"] = cat
    return out


def height_conflict(inp, cfg):
    """input: heights [{value_m, source}] (latest per source)."""
    th = cfg["thresholds"]
    vals = sorted(set(round_to(h["value_m"], 2) for h in inp.get("heights") or []))
    return {"conflict": len(vals) >= 2 and vals[-1] - vals[0] > th["height_conflict_m"], "values": vals}


# ---------------------------------------------------------------------------------------------
# Priority: native platform data wins
# ---------------------------------------------------------------------------------------------

def priority(inp, cfg):
    """input: enabled (bool), native ({value, source} or null: a platform reading for that metric and day that
    Ayuvo did not write), derived (number or null)."""
    n = inp.get("native")
    if n is not None and n.get("value") is not None:
        return {"value": n["value"], "source_kind": "native", "source": n.get("source")}
    if inp.get("enabled") and inp.get("derived") is not None:
        return {"value": inp["derived"], "source_kind": "derived", "source": None}
    return {"value": None, "source_kind": None, "source": None}


FUNCTIONS = {"heart_day": heart_day, "hr_max": hr_max, "vo2max_uth": vo2max_uth, "rhr_strain": rhr_strain,
             "sleep_nights": sleep_nights, "sleep_night": sleep_night, "sleep_regularity": sleep_regularity, "activity_day": activity_day,
             "step_streak": step_streak, "stride": stride, "energy_day": energy_day, "gait_week": gait_week,
             "audio_day": audio_day, "body_trend": body_trend, "height_conflict": height_conflict,
             "priority": priority}


def run_case(function, inp, cfg):
    return FUNCTIONS[function](inp, cfg)
