#!/usr/bin/env python3
"""Ayuvo Metrics contract check (Python 3 stdlib only).

    python3 scripts/metrics_contract_check.py           # verify; exit 1 on any problem
    python3 scripts/metrics_contract_check.py --write   # rewrite every vector's "expected" from the reference

Checks:
  1. shared/metrics/metric_catalog.json: canonical formatting, schema, enum values, unique keys and orders,
     hex and icon formats, about texts, every registry category mapped, override ids exist in
     shared/health/metric_registry.json, aggregation/chart maps cover the registry, summary rings and prefs.
  2. The Android asset and iOS resource copies are byte-identical to the shared catalog.
  3. shared/metrics/test-vectors/*.json: envelope, unique case names, canonical formatting, allowed zones,
     EXPECTED_FILES exact, and every "expected" equals scripts/metrics_reference.py on its "input"
     (inputs are the source of truth; --write only replaces "expected").
  4. Output shapes: buckets ascending, contiguous and covering the interval; D has 23-25 buckets, W 7,
     Y 12; ring progress within [0, 1]; favourites unique and capped.
  5. Regex portability lint of every pattern owned by the reference (records rule).
  6. A small unittest suite with hand-computed expectations.
"""

import datetime as _dt
import glob
import json
import os
import re
import sys
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
SHARED = os.path.join(ROOT, "shared", "metrics")
VECTORS = os.path.join(SHARED, "test-vectors")
CATALOG = os.path.join(SHARED, "metric_catalog.json")
COPIES = [os.path.join(ROOT, "android", "app", "src", "main", "assets", "metrics", "metric_catalog.json"),
          os.path.join(ROOT, "ios", "calorietracker", "Metrics", "Resources", "metric_catalog.json")]
sys.path.insert(0, HERE)
import metrics_reference as R  # noqa: E402
from records_contract_check import lint_pattern  # noqa: E402

FORMAT = "ayuvo-metrics-vectors"
EXPECTED_FILES = {
    "bucket_bounds.json": "bucket_bounds", "anchor_step.json": "step_anchor", "bucket_series.json": "bucket_series",
    "headline.json": "headline", "sparkline.json": "sparkline_7d", "fasting_days.json": "fasting_hours_per_day",
    "workouts.json": "workout_stats_per_bucket", "rings.json": "ring_progress", "pins.json": "favourite_pins_migrate",
    "catalog_resolve.json": "resolve_metric",
}
ALLOWED_ZONES = frozenset(["America/New_York", "Europe/London", "Asia/Kolkata", "UTC"])
DOCUMENTED = {"FAV_MAX": 12, "DAILY_STEP_GOAL_DEFAULT": 10000, "HOUR_MS": 3_600_000,
              "RANGES": ("D", "W", "M", "6M", "Y"), "AGGREGATIONS": ("sum", "avg", "last", "count", "duration")}
DOMAIN_IDS = ["nutrition", "hydration", "fasting", "body", "activity", "heart", "sleep", "vitals", "respiratory", "cycle",
              "mindfulness", "mobility", "hearing", "symptoms", "medications", "records", "other"]
APP_KEYS = ["app:calories", "app:protein", "app:carbs", "app:fat", "app:fiber", "app:water", "app:fasting", "app:weight",
            "app:body_fat", "app:workouts", "app:workout_minutes", "app:workout_burn"]
DEFAULT_FAVOURITES = ["app:calories", "app:protein", "steps", "app:water", "app:weight", "sleep", "heart_rate",
                      "active_energy"]
HEX_RE = re.compile("^#[0-9A-F]{6}$")
ANDROID_ICON_RE = re.compile("^(AutoMirrored[.])?(Filled|Outlined|Rounded)[.][A-Z][A-Za-z0-9]*$")
IOS_ICON_RE = re.compile("^[a-z0-9]+([.][a-z0-9]+)*$")
TARGET_RE = re.compile("^(screen:[a-z]+|metric:app:[a-z_]+|category:[a-z_]+|tab:records)$")


def dumps(obj):
    return json.dumps(obj, indent=2, sort_keys=True, ensure_ascii=False) + "\n"


def _first_diff(a, b, path="$"):
    if type(a) != type(b) and not (isinstance(a, (int, float)) and isinstance(b, (int, float))):
        return "%s: %r != %r" % (path, a, b)
    if isinstance(a, dict):
        for k in sorted(set(a) | set(b)):
            if k not in a or k not in b:
                return "%s.%s: missing on one side" % (path, k)
            d = _first_diff(a[k], b[k], "%s.%s" % (path, k))
            if d:
                return d
        return None
    if isinstance(a, list):
        if len(a) != len(b):
            return "%s: length %d != %d" % (path, len(a), len(b))
        for i, (x, y) in enumerate(zip(a, b)):
            d = _first_diff(x, y, "%s[%d]" % (path, i))
            if d:
                return d
        return None
    return None if a == b else "%s: %r != %r" % (path, a, b)


# ---------------------------------------------------------------------------------------------
# Catalog
# ---------------------------------------------------------------------------------------------

def check_catalog(problems):
    raw = open(CATALOG, encoding="utf-8").read()
    cat = json.loads(raw)
    if raw != dumps(cat):
        problems.append("metric_catalog.json: not in canonical format")
    for path in COPIES:
        rel = os.path.relpath(path, ROOT)
        if not os.path.exists(path):
            problems.append("%s missing" % rel)
        elif open(path, encoding="utf-8").read() != raw:
            problems.append("%s differs from shared/metrics/metric_catalog.json" % rel)
    if cat.get("catalog_version") != 1 or cat.get("format") != "ayuvo-metric-catalog":
        problems.append("catalog: bad version/format")
    registry = R.REGISTRY
    reg_ids = set(m["id"] for m in registry["metrics"])
    reg_cats = [c["id"] for c in registry["categories"]]

    domains = cat["domains"]
    ids = [d["id"] for d in domains]
    if ids != DOMAIN_IDS:
        problems.append("domains %s != documented %s" % (ids, DOMAIN_IDS))
    orders = [d["browse_order"] for d in domains]
    if orders != list(range(1, len(domains) + 1)):
        problems.append("domain browse_order must be 1..n in list order")
    used = set()
    for d in domains:
        w = "domain %s" % d["id"]
        if set(d) != {"id", "title", "title_res", "colour_hex", "colour_hex_dark", "icon", "browse_order", "target"}:
            problems.append("%s: keys %s" % (w, sorted(d)))
        for k in ("colour_hex", "colour_hex_dark"):
            if not HEX_RE.match(d.get(k, "")):
                problems.append("%s: bad %s %r" % (w, k, d.get(k)))
        _check_icon(d.get("icon", {}), w, problems)
        if d.get("title_res") != "domain_" + d["id"]:
            problems.append("%s: title_res must be domain_<id>" % w)
        tg = d.get("target", "")
        if not TARGET_RE.match(tg):
            problems.append("%s: bad target %r" % (w, tg))
        elif tg.startswith("category:") and tg[9:] not in reg_cats:
            problems.append("%s: target category %r not in the registry" % (w, tg))
        elif tg.startswith("metric:") and tg[7:] not in APP_KEYS:
            problems.append("%s: target metric %r unknown" % (w, tg))
        if tg.startswith(("screen:", "tab:")):
            used.add(d["id"])

    metrics = cat["metrics"]
    keys = [m["key"] for m in metrics]
    if keys != APP_KEYS:
        problems.append("app metrics %s != documented %s" % (keys, APP_KEYS))
    sections = dict((s["id"], s) for s in cat["browse_sections"])
    if len(sections) != len(cat["browse_sections"]):
        problems.append("duplicate browse_section ids")
    fav_orders = []
    sec_orders = {}
    for m in metrics:
        w = "metric %s" % m["key"]
        expect = {"key", "domain", "title", "title_res", "icon", "source", "unit", "aggregation", "chart_kind",
                  "day_bucket", "ranges", "goal_source", "default_favourite", "browse_section", "browse_order", "about"}
        if set(m) != expect:
            problems.append("%s: keys %s" % (w, sorted(m)))
            continue
        if not R.APP_KEY_RE.match(m["key"]):
            problems.append("%s: bad key grammar" % w)
        if m["domain"] not in DOMAIN_IDS:
            problems.append("%s: unknown domain" % w)
        used.add(m["domain"])
        if m["title_res"] != "metric_" + m["key"][4:]:
            problems.append("%s: title_res must be metric_<name>" % w)
        _check_icon(m["icon"], w, problems)
        if m["aggregation"] not in R.AGGREGATIONS or m["chart_kind"] not in R.CHART_KINDS:
            problems.append("%s: bad aggregation/chart" % w)
        if m["day_bucket"] not in R.DAY_BUCKETS:
            problems.append("%s: bad day_bucket" % w)
        want_ranges = ["D", "W", "M", "6M", "Y"] if m["day_bucket"] == "hour" else ["W", "M", "6M", "Y"]
        if m["ranges"] != want_ranges:
            problems.append("%s: ranges must be %s" % (w, want_ranges))
        if m["goal_source"] not in R.GOAL_SOURCES:
            problems.append("%s: bad goal_source" % w)
        if not (0 < len(m["about"]) <= 280):
            problems.append("%s: about must be 1..280 chars" % w)
        if set(m["unit"]) != {"canonical", "decimals", "pref"} or not isinstance(m["unit"]["decimals"], int):
            problems.append("%s: bad unit" % w)
        if set(m["source"]) != {"store", "field"}:
            problems.append("%s: bad source" % w)
        f = m["default_favourite"]
        if f.get("enabled"):
            fav_orders.append(f["order"])
        elif f.get("order") is not None:
            problems.append("%s: disabled favourite with an order" % w)
        s = sections.get(m["browse_section"])
        if s is None or s["domain"] != m["domain"]:
            problems.append("%s: browse_section missing or in another domain" % w)
        so = sec_orders.setdefault(m["browse_section"], [])
        if m["browse_order"] in so:
            problems.append("%s: duplicate browse_order in its section" % w)
        so.append(m["browse_order"])

    h = cat["health"]
    if sorted(h["category_domains"]) != sorted(reg_cats):
        problems.append("health.category_domains must cover exactly the registry categories %s" % reg_cats)
    for c, dmn in h["category_domains"].items():
        if dmn not in DOMAIN_IDS:
            problems.append("health.category_domains.%s: unknown domain %s" % (c, dmn))
        used.add(dmn)
    if sorted(h["aggregation_map"]) != sorted(registry["aggregations"]) or sorted(h["chart_kind_map"]) != sorted(registry["aggregations"]):
        problems.append("health aggregation/chart maps must cover registry aggregations %s" % registry["aggregations"])
    for v in h["aggregation_map"].values():
        if v not in R.AGGREGATIONS:
            problems.append("health.aggregation_map: bad value %s" % v)
    for v in h["chart_kind_map"].values():
        if v not in R.CHART_KINDS:
            problems.append("health.chart_kind_map: bad value %s" % v)
    seen = set()
    for o in h["overrides"]:
        if o["id"] not in reg_ids:
            problems.append("override %s not in the registry" % o["id"])
        if o["id"] in seen:
            problems.append("duplicate override %s" % o["id"])
        seen.add(o["id"])
        if set(o) - {"id", "domain", "goal_source", "default_favourite", "browse_hidden", "icon"}:
            problems.append("override %s: unknown keys" % o["id"])
        if "domain" in o and o["domain"] not in DOMAIN_IDS:
            problems.append("override %s: unknown domain" % o["id"])
        if o.get("goal_source", "none") not in R.GOAL_SOURCES:
            problems.append("override %s: bad goal_source" % o["id"])
        if "icon" in o:
            _check_icon(o["icon"], "override %s" % o["id"], problems)
        f = o.get("default_favourite") or {}
        if f.get("enabled"):
            fav_orders.append(f["order"])
    if sorted(fav_orders) != list(range(1, len(fav_orders) + 1)):
        problems.append("default_favourite orders must be unique 1..n, got %s" % sorted(fav_orders))
    if R.default_favourites() != DEFAULT_FAVOURITES:
        problems.append("default favourites %s != documented %s" % (R.default_favourites(), DEFAULT_FAVOURITES))
    for d in DOMAIN_IDS:
        if d not in used:
            problems.append("domain %s is not used by any metric, category or target" % d)

    rings = cat["summary_rings"]
    if [r["id"] for r in rings] != ["eat", "move", "drink"]:
        problems.append("summary_rings must be eat, move, drink")
    for r in rings:
        if not (r["metric"] in APP_KEYS or r["metric"] in reg_ids):
            problems.append("ring %s: unknown metric" % r["id"])
        if r["goal_source"] not in R.GOAL_SOURCES or r["domain"] not in DOMAIN_IDS:
            problems.append("ring %s: bad goal_source/domain" % r["id"])
    fav = cat["favourites"]
    if (fav.get("max") != DOCUMENTED["FAV_MAX"] or fav.get("pref_key") != "summaryFavourites"
            or fav.get("legacy_pref_key") != "healthHomeTiles"):
        problems.append("favourites block differs from the documented values")
    sg = cat["prefs"]["dailyStepGoal"]
    if (sg["default"], sg["min"], sg["max"], sg["step"]) != (10000, 1000, 50000, 500):
        problems.append("prefs.dailyStepGoal differs from documented 10000/1000/50000/500")
    for k in ("protein", "carbs", "fat", "fiber"):
        if not HEX_RE.match(cat["macro_colours"].get(k, "")):
            problems.append("macro_colours.%s bad" % k)
    return len(domains), len(metrics)


def _check_icon(icon, where, problems):
    if set(icon) != {"android", "ios"}:
        problems.append("%s: icon needs android and ios" % where)
        return
    if not ANDROID_ICON_RE.match(icon["android"]):
        problems.append("%s: bad android icon %r" % (where, icon["android"]))
    if not IOS_ICON_RE.match(icon["ios"]):
        problems.append("%s: bad ios icon %r" % (where, icon["ios"]))


# ---------------------------------------------------------------------------------------------
# Output shapes
# ---------------------------------------------------------------------------------------------

def _check_contiguous(buckets, interval, where, problems):
    if not buckets:
        problems.append("%s: no buckets" % where)
        return
    if buckets[0]["start_ms"] != interval[0] or buckets[-1]["end_ms"] != interval[1]:
        problems.append("%s: buckets do not cover the interval" % where)
    for a, b in zip(buckets, buckets[1:]):
        if a["end_ms"] != b["start_ms"]:
            problems.append("%s: buckets not contiguous" % where)
            break
    for b in buckets:
        if b["end_ms"] <= b["start_ms"]:
            problems.append("%s: empty bucket" % where)
            break


def _count_rule(range_, n, where, problems):
    ok = {"D": n in (23, 24, 25), "W": n == 7, "M": 28 <= n <= 31, "6M": 26 <= n <= 28, "Y": n == 12}[range_]
    if not ok:
        problems.append("%s: %s has %d buckets" % (where, range_, n))


def check_shape(function, got, where, problems, inp):
    if function == "bucket_bounds":
        iv = (got["interval"]["start_ms"], got["interval"]["end_ms"])
        _check_contiguous(got["buckets"], iv, where, problems)
        _count_rule(inp["range"], len(got["buckets"]), where, problems)
        if got["can_go_forward"] != (iv[1] <= inp["now_ms"]):
            problems.append("%s: can_go_forward inconsistent" % where)
    elif function in ("bucket_series", "workout_stats_per_bucket"):
        b = got["buckets"]
        _check_contiguous(b, (b[0]["start_ms"], b[-1]["end_ms"]) if b else (0, 0), where, problems)
        _count_rule(inp["range"], len(b), where, problems)
        if function == "bucket_series":
            for x in b:
                if (x["value"] is None) != (x["count"] == 0):
                    problems.append("%s: value null must mean count 0" % where)
                    break
    elif function == "step_anchor":
        if R.parse_date(got["anchor_date"]) is None:
            problems.append("%s: bad anchor_date" % where)
    elif function == "headline":
        if got["kind"] not in R.HEADLINE_KINDS:
            problems.append("%s: bad kind" % where)
    elif function == "sparkline_7d":
        if len(got["values"]) != 7:
            problems.append("%s: sparkline needs 7 values" % where)
    elif function == "fasting_hours_per_day":
        days = [d["day"] for d in got["days"]]
        if days != sorted(set(days)) or any(d["seconds"] <= 0 for d in got["days"]):
            problems.append("%s: days not ascending/unique/positive" % where)
    elif function == "ring_progress":
        if got["state"] not in R.RING_STATES:
            problems.append("%s: bad state" % where)
        if got["progress"] is not None and not (0 <= got["progress"] <= 1):
            problems.append("%s: progress outside [0, 1]" % where)
    elif function == "favourite_pins_migrate":
        f = got["favourites"]
        if len(f) != len(set(f)) or len(f) > inp["max"] or got["source"] not in R.PIN_SOURCES:
            problems.append("%s: bad favourites output" % where)
    elif function == "resolve_metric":
        if got["domain"] not in DOMAIN_IDS or got["aggregation"] not in R.AGGREGATIONS or got["chart_kind"] not in R.CHART_KINDS:
            problems.append("%s: bad resolved metric" % where)
        if not ANDROID_ICON_RE.match(got["icon_android"]) or not IOS_ICON_RE.match(got["icon_ios"]):
            problems.append("%s: bad resolved icon %r / %r" % (where, got["icon_android"], got["icon_ios"]))


def _zones(obj, out):
    if isinstance(obj, dict):
        for k, v in obj.items():
            if k == "time_zone" and isinstance(v, str):
                out.add(v)
            _zones(v, out)
    elif isinstance(obj, list):
        for v in obj:
            _zones(v, out)


# ---------------------------------------------------------------------------------------------
# Vectors
# ---------------------------------------------------------------------------------------------

def check_vectors(write, problems):
    counts = {}
    present = sorted(os.path.basename(p) for p in glob.glob(os.path.join(VECTORS, "*.json")))
    for name in sorted(EXPECTED_FILES):
        if name not in present:
            problems.append("test-vectors/%s missing" % name)
    for name in present:
        if name not in EXPECTED_FILES:
            problems.append("test-vectors/%s: unknown vector file (add it to EXPECTED_FILES)" % name)
            continue
        path = os.path.join(VECTORS, name)
        raw = open(path, encoding="utf-8").read()
        try:
            doc = json.loads(raw)
        except ValueError as e:
            problems.append("%s: invalid JSON: %s" % (name, e))
            continue
        if (doc.get("format") != FORMAT or doc.get("version") != 1 or set(doc) != {"format", "version", "function", "cases"}
                or doc.get("function") != EXPECTED_FILES[name] or not isinstance(doc.get("cases"), list)):
            problems.append("%s: bad envelope" % name)
            continue
        seen = set()
        changed = False
        for c in doc["cases"]:
            if set(c) - {"name", "input", "expected", "notes"} or not isinstance(c.get("input"), dict) or not c.get("name"):
                problems.append("%s: bad case keys %s" % (name, sorted(c)))
                continue
            if c["name"] in seen:
                problems.append("%s: duplicate case %s" % (name, c["name"]))
            seen.add(c["name"])
            where = "%s/%s" % (name, c["name"])
            zones = set()
            _zones(c["input"], zones)
            if zones - ALLOWED_ZONES:
                problems.append("%s: zone outside the allowed set: %s" % (where, sorted(zones - ALLOWED_ZONES)))
            try:
                got = json.loads(json.dumps(R.run_case(doc["function"], c["input"]), ensure_ascii=False))
            except Exception as e:  # noqa: BLE001 - report, don't crash the whole check
                problems.append("%s: reference raised %s: %s" % (where, type(e).__name__, e))
                continue
            if write:
                if c.get("expected") != got:
                    c["expected"] = got
                    changed = True
            elif c.get("expected") != got:
                problems.append("%s: expected differs from the reference at %s" % (where, _first_diff(c.get("expected"), got)))
            check_shape(doc["function"], got, where, problems, c["input"])
        counts[name] = len(doc["cases"])
        if write and (changed or raw != dumps(doc)):
            with open(path, "w", encoding="utf-8") as fh:
                fh.write(dumps(doc))
        elif not write and raw != dumps(doc):
            problems.append("%s: not in canonical format (run --write)" % name)
    return counts


def check_constants(problems):
    for name, value in DOCUMENTED.items():
        if getattr(R, name, None) != value:
            problems.append("reference %s = %r, documented %r" % (name, getattr(R, name, None), value))
    for name in sorted(dir(R)):
        obj = getattr(R, name)
        if isinstance(obj, re.Pattern):
            for issue in lint_pattern(obj.pattern, owned=True):
                problems.append("metrics_reference.%s: %s" % (name, issue))


# ---------------------------------------------------------------------------------------------
# Hand-computed unit tests
# ---------------------------------------------------------------------------------------------

class ReferenceTests(unittest.TestCase):
    NY = "America/New_York"
    IN = "Asia/Kolkata"

    def test_ny_spring_forward_day_has_23_hour_buckets(self):
        anchor = R.local_instant(_dt.date(2026, 3, 8), 12, 0, self.NY)
        b = R.bucket_bounds("D", anchor, self.NY, "monday", anchor)
        self.assertEqual(len(b["buckets"]), 23)
        self.assertEqual(b["interval"]["end_ms"] - b["interval"]["start_ms"], 23 * 3_600_000)
        self.assertNotIn("02", [x["label"] for x in b["buckets"]])

    def test_ny_fall_back_day_has_25_hour_buckets(self):
        anchor = R.local_instant(_dt.date(2026, 11, 1), 12, 0, self.NY)
        labels = [x["label"] for x in R.bucket_bounds("D", anchor, self.NY, "monday", anchor)["buckets"]]
        self.assertEqual(len(labels), 25)
        self.assertEqual(labels[:3], ["00", "01", "01"])

    def test_fast_22_to_14_splits(self):
        s = R.local_instant(_dt.date(2026, 9, 15), 22, 0, self.IN)
        e = R.local_instant(_dt.date(2026, 9, 16), 14, 0, self.IN)
        got = R.fasting_hours_per_day([{"started_at_ms": s, "ended_at_ms": e}], e, self.IN)
        self.assertEqual(got["days"], [{"day": "2026-09-15", "seconds": 7200}, {"day": "2026-09-16", "seconds": 50400}])

    def test_legacy_blank_pins_mean_none(self):
        self.assertEqual(R.favourite_pins_migrate(None, "", ["steps"], 12), {"favourites": [], "source": "migrated"})

    def test_6m_monday_clipping(self):
        anchor = R.local_instant(_dt.date(2026, 9, 16), 12, 0, "UTC")
        b = R.bucket_bounds("6M", anchor, "UTC", "monday", anchor)["buckets"]
        self.assertEqual(b[0]["label"], "2026-04-01")      # Wednesday: first bucket clipped
        self.assertEqual(b[1]["label"], "2026-04-06")      # next Monday
        self.assertEqual(b[-1]["end_ms"], R.local_instant(_dt.date(2026, 10, 1), 0, 0, "UTC"))

    def test_6m_sum_is_daily_mean(self):
        z = "UTC"
        anchor = R.local_instant(_dt.date(2026, 9, 16), 12, 0, z)
        e = [{"t_ms": R.local_instant(_dt.date(2026, 9, 14), 8, 0, z), "value": 2000},
             {"t_ms": R.local_instant(_dt.date(2026, 9, 14), 20, 0, z), "value": 500},
             {"t_ms": R.local_instant(_dt.date(2026, 9, 15), 8, 0, z), "value": 1500}]
        rows = R.bucket_series(e, "6M", anchor, z, "monday", "sum")
        week = [r for r in rows if r["count"]][0]
        self.assertEqual(week["value"], 2000)

    def test_jan31_plus_month(self):
        a = R.local_instant(_dt.date(2026, 1, 31), 12, 0, "UTC")
        now = R.local_instant(_dt.date(2026, 9, 1), 0, 0, "UTC")
        self.assertEqual(R.step_anchor("M", a, 1, "UTC", now)["anchor_date"], "2026-02-28")

    def test_ring_states(self):
        self.assertEqual(R.ring_progress(2600, 2000), {"state": "value", "progress": 1, "percent": 130, "over": True})
        self.assertEqual(R.ring_progress(5, 0)["state"], "no_goal")
        self.assertEqual(R.ring_progress(None, 10)["state"], "no_data")

    def test_icon_fallback_order(self):
        self.assertEqual(R.resolve_metric("steps")["icon_ios"], "figure.walk")          # override icon
        self.assertEqual(R.resolve_metric("app:calories")["icon_ios"], "flame.fill")    # app metric icon
        domain_icon = R.DOMAINS[R.resolve_metric("vo2_max")["domain"]]["icon"]["ios"]
        self.assertEqual(R.resolve_metric("vo2_max")["icon_ios"], "speedometer")
        self.assertNotEqual(R.resolve_metric("vo2_max")["icon_ios"], domain_icon)
        no_override = R.resolve_metric("basal_metabolic_rate")                          # no curated icon
        self.assertEqual(no_override["icon_ios"], R.DOMAINS[no_override["domain"]]["icon"]["ios"])
        self.assertEqual(R.resolve_metric("HKQuantityTypeIdentifierMadeUp")["icon_ios"],
                         R.DOMAINS["other"]["icon"]["ios"])

    def test_round3(self):
        self.assertEqual(R.round3(1.0005), 1.001)
        self.assertEqual(R.round3(-0.0005), -0.001)
        self.assertEqual(R.round3(2.0), 2)


def main(argv):
    write = "--write" in argv
    problems = []
    n_domains, n_metrics = check_catalog(problems)
    check_constants(problems)
    counts = check_vectors(write, problems)
    suite = unittest.defaultTestLoader.loadTestsFromTestCase(ReferenceTests)
    result = unittest.TextTestRunner(stream=open(os.devnull, "w"), verbosity=0).run(suite)
    for failed, trace in result.failures + result.errors:
        problems.append("unittest %s failed:\n%s" % (failed.id(), trace.strip().splitlines()[-1]))
    print("%-45s %3d domains, %d app metrics" % ("metric_catalog.json", n_domains, n_metrics))
    for name, n in sorted(counts.items()):
        print("%-45s %3d cases" % ("test-vectors/" + name, n))
    print("%d unit tests" % result.testsRun)
    if problems:
        print("\n%d problem(s):" % len(problems))
        for p in problems:
            print(" - " + p)
        return 1
    print("OK")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
