#!/usr/bin/env python3
"""Ayuvo derived-metrics contract check (Python 3 stdlib only).

    python3 scripts/derived_contract_check.py           # verify; exit 1 on any problem
    python3 scripts/derived_contract_check.py --write   # rewrite vectors' "expected", the platform copies and the
                                                        # generated block of docs/derived-metrics.md

Checks:
  1. shared/derived/derived_config.json lint: envelope, unique metric ids, known categories and functions, every
     metric field produced by its function, native type ids present in shared/health/metric_registry.json,
     requires pointing at known metrics without cycles, a method and a citation on every metric, copy rules.
  2. shared/derived/test-vectors/*.json: exact file set, envelope, unique names, allowed time zones, canonical
     formatting and every "expected" equals scripts/derived_reference.py on its "input"; coverage tags.
  3. Portability lint of the reference (no statistics module, no built-in round, no isoformat).
  4. Platform copies of the config are byte-identical to shared/ (Android assets, iOS bundle resource).
  5. docs/derived-metrics.md generated block is up to date.
  6. A small unittest suite with hand-computed expectations.
"""

import glob
import json
import math
import os
import re
import sys
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
SHARED = os.path.join(ROOT, "shared", "derived")
VECTORS = os.path.join(SHARED, "test-vectors")
CONFIG = os.path.join(SHARED, "derived_config.json")
REGISTRY = os.path.join(ROOT, "shared", "health", "metric_registry.json")
DOC = os.path.join(ROOT, "docs", "derived-metrics.md")
COPY_DIRS = [
    os.path.join(ROOT, "android", "app", "src", "main", "assets", "derived"),
    os.path.join(ROOT, "ios", "calorietracker", "Services", "Derived", "Resources"),
]
BEGIN = "<!-- BEGIN GENERATED DERIVED -->"
END = "<!-- END GENERATED DERIVED -->"
sys.path.insert(0, HERE)
import derived_reference as R  # noqa: E402

FORMAT = "ayuvo-derived-vectors"
EXPECTED_FILES = dict((f + ".json", f) for f in R.FUNCTIONS)
ALLOWED_ZONES = frozenset(["America/New_York", "Europe/London", "Asia/Kolkata", "UTC"])
METRIC_KEYS = {"id", "category", "title", "unit", "decimals", "function", "field", "native", "requires", "chart_kind",
               "aggregation", "icon", "about", "method", "citation", "default_enabled", "value2_field", "value3_field"}
CHART_KINDS = ("line", "bar")
AGGREGATIONS = ("average", "sum", "max", "latest")
# Fields each metric-producing function returns (a metric's field / value2 / value3 must be one of them).
FUNCTION_FIELDS = {
    "heart_day": {"resting_hr", "sleeping_hr", "lowest_hr", "dip_pct", "sedentary_hr", "day_avg", "day_min", "day_max",
                  "observed_max", "wear_minutes", "completeness_pct", "moderate_equivalent", "moderate_min",
                  "vigorous_min", "trimp", "walking_hr"},
    "hr_max": {"hr_max", "hr_reserve"},
    "vo2max_uth": {"vo2max"},
    "rhr_strain": {"deviation"},
    "sleep_night": {"efficiency", "onset_latency_min", "deep_pct", "rem_pct", "wakeups", "waso_min", "bedtime_clock",
                    "wake_clock", "midpoint_clock", "deep_latency_min", "rem_latency_min"},
    "sleep_regularity": {"bedtime_sd", "wake_sd", "sri", "social_jetlag_min", "msfsc_clock", "sleep_debt_min"},
    "activity_day": {"brisk_minutes", "peak30_cadence", "active_hours", "longest_sedentary_min", "step_band_index",
                     "phone_missing_steps"},
    "step_streak": {"current", "best"},
    "stride": {"stride_m", "expected_m"},
    "energy_day": {"tdee", "resting_kcal", "bmr_mifflin", "pal"},
    "gait_week": {"speed_median", "double_support_median", "asymmetry_flag_count"},
    "audio_day": {"dose_pct", "loud_minutes"},
    "body_trend": {"trend_kg", "rate_kg_week", "bmi", "healthy_low_kg", "healthy_high_kg", "eta_weeks"},
    # Computed by the intake contract (scripts/intake_reference.py, docs/intake-metrics.md).
    "nutrition_day": {"protein_g_per_kg", "protein_pct", "carbs_pct", "fat_pct", "saturated_fat_pct", "fiber_per_1000kcal",
                      "na_k_ratio", "eating_window_min", "last_meal_to_bed_min", "iron_absorption_risk", "caffeine_mg"},
    "energy_balance": {"balance_kcal", "adaptive_tdee"},
}
FORBIDDEN_COPY = ("never leaves", "leaves your device", "stays on your device", "f" "ud ai", "f" "udai", "caused by",
                  "causes your", "proves", "diagnos")


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
# Config lint
# ---------------------------------------------------------------------------------------------

def lint_config(cfg, problems):
    if cfg.get("format") != "ayuvo-derived-config" or cfg.get("config_version") != 1 or cfg.get("algo_version", 0) < 1:
        problems.append("config: bad format/config_version/algo_version")
    registry = dict((m["id"], m) for m in json.load(open(REGISTRY, encoding="utf-8"))["metrics"])
    ids = [m["id"] for m in cfg["metrics"]]
    if len(set(ids)) != len(ids):
        problems.append("metrics: duplicate ids")
    known = set(ids)
    for m in cfg["metrics"]:
        w = "metrics.%s" % m["id"]
        missing = {"id", "category", "title", "unit", "decimals", "function", "field", "native", "requires",
                   "chart_kind", "aggregation", "icon", "about", "method", "citation", "default_enabled"} - set(m)
        if missing or set(m) - METRIC_KEYS:
            problems.append("%s: keys missing %s / unknown %s" % (w, sorted(missing), sorted(set(m) - METRIC_KEYS)))
            continue
        if not re.match("^[a-z][a-z0-9_]*$", m["id"]) or m["id"] in registry:
            problems.append("%s: id must be snake_case and must not collide with a Health registry type" % w)
        if m["category"] not in cfg["categories"]:
            problems.append("%s: unknown category" % w)
        fields = FUNCTION_FIELDS.get(m["function"])
        if fields is None:
            problems.append("%s: unknown function %s" % (w, m["function"]))
        else:
            for key in ("field", "value2_field", "value3_field"):
                if key in m and m[key] not in fields:
                    problems.append("%s: %s %s is not produced by %s" % (w, key, m[key], m["function"]))
        if m["native"] is not None:
            if set(m["native"]) != {"ios", "android"} or not (m["native"]["ios"] or m["native"]["android"]):
                problems.append("%s: native needs ios and android keys (one may be null)" % w)
            for p in ("ios", "android"):
                t = m["native"].get(p)
                if t is not None and t not in registry:
                    problems.append("%s: native %s type %s not in the Health registry" % (w, p, t))
        for r in m["requires"]:
            if r not in known or r == m["id"]:
                problems.append("%s: requires unknown metric %s" % (w, r))
        if m["chart_kind"] not in CHART_KINDS or m["aggregation"] not in AGGREGATIONS:
            problems.append("%s: bad chart_kind/aggregation" % w)
        if not isinstance(m["decimals"], int) or not 0 <= m["decimals"] <= 2:
            problems.append("%s: decimals must be 0-2" % w)
        if set(m["icon"]) != {"ios", "android"} or not all(m["icon"].values()):
            problems.append("%s: icon needs ios and android" % w)
        if len(m["about"]) < 20 or len(m["method"]) < 15 or len(m["citation"]) < 10:
            problems.append("%s: about/method/citation too short" % w)
        if m["default_enabled"] is not True:
            problems.append("%s: every derived metric is on by default" % w)
    # requires must be acyclic
    graph = dict((m["id"], m["requires"]) for m in cfg["metrics"])
    state = {}

    def visit(n):
        if state.get(n) == 1:
            return True
        if state.get(n) == 2:
            return False
        state[n] = 1
        cyc = any(visit(x) for x in graph.get(n, []))
        state[n] = 2
        return cyc
    if any(visit(n) for n in graph):
        problems.append("metrics: requires has a cycle")
    th = cfg["thresholds"]
    if th["hrr_zones"] != sorted(th["hrr_zones"]) or th["hrmax_zones"] != sorted(th["hrmax_zones"]) \
            or len(th["hrr_zones"]) != 4 or len(th["hrmax_zones"]) != 4:
        problems.append("thresholds: zone cut-offs must be 4 ascending values")
    if th["strain_min_days"] < 2 or th["rhr_min_sleep_min"] < th["rhr_window_min"]:
        problems.append("thresholds: strain_min_days must be >= 2 (sample SD) and the RHR window must fit a night")
    if th["step_bands"] != sorted(th["step_bands"]) or len(cfg["labels"]["step_band"]) != len(th["step_bands"]) + 1:
        problems.append("thresholds: step bands and labels do not line up")
    if len(cfg["labels"]["pal_band"]) != len(th["pal_bands"]) + 1:
        problems.append("labels.pal_band must have one more entry than pal_bands")
    for scheme in ("who", "asian"):
        if len(cfg["labels"]["bmi_category"]) != len(th["bmi"][scheme]) + 1:
            problems.append("labels.bmi_category does not line up with bmi.%s" % scheme)
    strings = [m[k] for m in cfg["metrics"] for k in ("title", "about", "method")] + [cfg["disclaimer"]]
    for s in strings:
        low = s.lower()
        for bad in FORBIDDEN_COPY:
            if re.search("(?<![a-z])" + re.escape(bad), low) and not (bad == "diagnos" and "not a medical measurement or diagnosis" in low):
                problems.append("copy: forbidden phrase %r in %r" % (bad, s[:60]))


# ---------------------------------------------------------------------------------------------
# Vectors
# ---------------------------------------------------------------------------------------------

def _zones(obj, out):
    if isinstance(obj, dict):
        for k, v in obj.items():
            if k == "time_zone" and isinstance(v, str):
                out.add(v)
            _zones(v, out)
    elif isinstance(obj, list):
        for v in obj:
            _zones(v, out)


def _cover(seen, got, f):
    def add(tag):
        seen.add("%s:%s" % (f, tag))
    if f == "heart_day":
        add(got["rhr_status"])
        add(got["zone_method"])
        if got["dip_pct"] is not None:
            add("dip")
        if got["walking_hr"] is not None:
            add("walking")
        if got["sedentary_hr"] is not None:
            add("sedentary")
    elif f == "hr_max":
        add(got["method"])
    elif f == "vo2max_uth":
        add(got["confidence"] or "none")
    elif f == "rhr_strain":
        add(got["status"])
        add("flag" if got["flag"] else "no_flag")
    elif f == "sleep_night":
        add("staged" if got["staged"] else ("unstaged" if got["asleep_min"] is not None else "empty"))
    elif f == "sleep_nights":
        add("nights" if got else "empty")
    elif f == "sleep_regularity":
        add("sri" if got["sri"] is not None else "no_sri")
        if got["social_jetlag_min"] is not None:
            add("social_jetlag")
    elif f == "energy_day":
        add(got["status"])
    elif f == "body_trend":
        add("eta" if got["eta_weeks"] is not None else "no_eta")
        if got["trend_kg"] is None:
            add("no_weights")
    elif f == "priority":
        add(got["source_kind"] or "none")


REQUIRED = {"heart_day": ["ok", "short_night", "low_coverage", "no_night", "hrr", "hrmax", "dip", "walking",
                          "sedentary"],
            "hr_max": ["tanaka", "observed"], "vo2max_uth": ["low", "medium", "none"],
            "rhr_strain": ["ok", "insufficient", "flag", "no_flag"],
            "sleep_night": ["staged", "unstaged", "empty"], "sleep_nights": ["nights", "empty"],
            "sleep_regularity": ["sri", "no_sri", "social_jetlag"],
            "energy_day": ["ok", "partial_day", "no_resting"], "body_trend": ["eta", "no_eta", "no_weights"],
            "priority": ["native", "derived", "none"]}


def check_vectors(cfg, write, problems):
    counts, seen = {}, set()
    present = sorted(os.path.basename(p) for p in glob.glob(os.path.join(VECTORS, "*.json")))
    for name in sorted(EXPECTED_FILES):
        if name not in present:
            problems.append("test-vectors/%s missing" % name)
    for name in present:
        if name not in EXPECTED_FILES:
            problems.append("test-vectors/%s: unknown vector file" % name)
            continue
        path = os.path.join(VECTORS, name)
        raw = open(path, encoding="utf-8").read()
        try:
            doc = json.loads(raw)
        except ValueError as e:
            problems.append("%s: invalid JSON: %s" % (name, e))
            continue
        if (doc.get("format") != FORMAT or doc.get("version") != 1 or set(doc) != {"format", "version", "function", "cases"}
                or doc.get("function") != EXPECTED_FILES[name]):
            problems.append("%s: bad envelope" % name)
            continue
        names, changed = set(), False
        for c in doc["cases"]:
            if set(c) - {"name", "input", "expected", "notes"} or not c.get("name"):
                problems.append("%s: bad case keys" % name)
                continue
            if c["name"] in names:
                problems.append("%s: duplicate case %s" % (name, c["name"]))
            names.add(c["name"])
            where = "%s/%s" % (name, c["name"])
            zones = set()
            _zones(c["input"], zones)
            if zones - ALLOWED_ZONES:
                problems.append("%s: zone outside the allowed set" % where)
            try:
                got = json.loads(json.dumps(R.run_case(doc["function"], c["input"], cfg)))
            except Exception as e:  # noqa: BLE001
                problems.append("%s: reference raised %s: %s" % (where, type(e).__name__, e))
                continue
            if write:
                if c.get("expected") != got:
                    c["expected"], changed = got, True
            elif c.get("expected") != got:
                problems.append("%s: expected differs at %s" % (where, _first_diff(c.get("expected"), got)))
            _cover(seen, got, doc["function"])
        counts[name] = len(doc["cases"])
        if write and (changed or raw != dumps(doc)):
            with open(path, "w", encoding="utf-8") as fh:
                fh.write(dumps(doc))
        elif not write and raw != dumps(doc):
            problems.append("%s: not in canonical format (run --write)" % name)
    for f, tags in sorted(REQUIRED.items()):
        for t in tags:
            if "%s:%s" % (f, t) not in seen:
                problems.append("coverage: no vector covers %s:%s" % (f, t))
    total = sum(os.path.getsize(os.path.join(VECTORS, n)) for n in present)
    if total > 1_000_000:
        problems.append("test-vectors: %d bytes, keep under ~1 MB" % total)
    return counts


# ---------------------------------------------------------------------------------------------
# Portability, copies and docs
# ---------------------------------------------------------------------------------------------

def check_portability(problems):
    src = open(os.path.join(HERE, "derived_reference.py"), encoding="utf-8").read()
    for pattern, what in (("import statistics", "statistics module"), ("from statistics", "statistics module"),
                          ("(?<![A-Za-z_.])round[(]", "built-in round() (banker's rounding)"),
                          ("(?<![A-Za-z_.])sum[(]", "built-in sum() (write the loop)"),
                          ("math[.]fsum", "math.fsum"), ("[.]isoformat[(]", "isoformat")):
        if re.search(pattern, src):
            problems.append("derived_reference.py uses %s" % what)


def check_copies(write, problems):
    body = open(CONFIG, encoding="utf-8").read()
    for folder in COPY_DIRS:
        path = os.path.join(folder, os.path.basename(CONFIG))
        rel = os.path.relpath(path, ROOT)
        current = open(path, encoding="utf-8").read() if os.path.exists(path) else None
        if current == body:
            continue
        if write:
            os.makedirs(folder, exist_ok=True)
            with open(path, "w", encoding="utf-8") as fh:
                fh.write(body)
        else:
            problems.append("%s differs from shared/derived/derived_config.json (run --write)" % rel)


def render_doc(cfg):
    out = [BEGIN, "", "_Generated from `shared/derived/derived_config.json` by `scripts/derived_contract_check.py "
           "--write`. Do not edit by hand._", "", "Algorithm version: %d." % cfg["algo_version"], ""]
    for cat in cfg["categories"]:
        ms = [m for m in cfg["metrics"] if m["category"] == cat]
        out += ["### %s" % cat.capitalize(), "", "| Metric | Id | Unit | Native type wins (iOS / Android) | Requires | "
                "Method | Source |", "|---|---|---|---|---|---|---|"]
        for m in ms:
            n = m["native"]
            native = "—" if n is None else "%s / %s" % (("`%s`" % n["ios"]) if n["ios"] else "—",
                                                         ("`%s`" % n["android"]) if n["android"] else "—")
            out.append("| %s | `%s` | %s | %s | %s | %s | %s |" % (
                m["title"], m["id"], m["unit"] or "—", native, ", ".join("`%s`" % r for r in m["requires"]) or "—",
                m["method"].replace("|", "\\|"), m["citation"].replace("|", "\\|")))
        out.append("")
    out += ["### Thresholds", "", "```json", json.dumps(cfg["thresholds"], indent=2, sort_keys=True), "```", "",
            "Disclaimer shown on every derived metric: %s" % cfg["disclaimer"], "", END]
    return "\n".join(out)


def check_doc(cfg, write, problems):
    if not os.path.exists(DOC):
        problems.append("docs/derived-metrics.md missing")
        return
    text = open(DOC, encoding="utf-8").read()
    i, j = text.find(BEGIN), text.find(END)
    if i < 0 or j < 0:
        problems.append("docs/derived-metrics.md: generated block markers missing")
        return
    new = text[:i] + render_doc(cfg) + text[j + len(END):]
    if new != text:
        if write:
            with open(DOC, "w", encoding="utf-8") as fh:
                fh.write(new)
        else:
            problems.append("docs/derived-metrics.md generated block is stale (run --write)")


# ---------------------------------------------------------------------------------------------
# Hand-computed unit tests
# ---------------------------------------------------------------------------------------------

class ReferenceTests(unittest.TestCase):
    cfg = R.load_config()

    def test_round_half_up(self):
        self.assertEqual((R.round_to(2.5, 0), R.round_to(0.125, 2), R.round_to(-0.004, 2)), (3.0, 0.13, 0.0))

    def test_median(self):
        self.assertEqual((R.median([3, 1, 2]), R.median([4, 1, 2, 3])), (2, 2.5))

    def test_union(self):
        self.assertEqual(R.union([(5, 9), (0, 3), (2, 4), (9, 10)]), [(0, 4), (5, 10)])

    def test_tanaka_and_uth(self):
        self.assertAlmostEqual(R.hr_max({"age": 24, "observed": [], "rhr": None}, self.cfg)["hr_max"], 191.2)
        self.assertEqual(R.vo2max_uth({"hr_max": 191.2, "hr_max_method": "tanaka", "rhr": 56.7}, self.cfg)["vo2max"],
                         R.round_to(15.3 * 191.2 / 56.7, 1))

    def test_zone_edges(self):
        th = self.cfg["thresholds"]
        # rhr 60, max 180: 40% HRR = 108 -> moderate, 107.9 -> light
        self.assertEqual((R.hr_zone(108.0, 180.0, 60.0, th), R.hr_zone(107.9, 180.0, 60.0, th)), (2, 1))
        # %HRmax fallback: 64% of 200 = 128 -> moderate
        self.assertEqual(R.hr_zone(128.0, 200.0, None, th), 2)

    def test_mifflin(self):
        th = self.cfg["thresholds"]
        self.assertAlmostEqual(R.mifflin(54.7, 175.0, 24, "male", th), 547.0 + 1093.75 - 120.0 + 5.0)

    def test_sound_dose(self):
        # 40 h at 80 dB is 100%; +3 dB halves the allowed time
        t = 0
        self.assertEqual(R.audio_day({"samples": [[t, t + 40 * 3600000, 80.0]]}, self.cfg)["dose_pct"], 100.0)
        self.assertEqual(R.audio_day({"samples": [[t, t + 20 * 3600000, 83.0]]}, self.cfg)["dose_pct"], 100.0)

    def test_sri_identical_days_is_100(self):
        tz = "UTC"
        nights = {}
        for i in range(8):
            d = R.add_days("2026-03-15", -i)
            s = R.day_start_ms(R.add_days(d, -1), tz) + 23 * 3600000
            nights[d] = {"rows": [[s, s + 8 * 3600000, 1]]}
        got = R.sleep_regularity({"time_zone": tz, "day": "2026-03-15", "need_min": None, "nights": nights}, self.cfg)
        self.assertEqual((got["sri"], got["bedtime_sd"]), (100.0, 0.0))

    def test_priority_native_first(self):
        got = R.priority({"enabled": True, "native": {"value": 60.0, "source": "x"}, "derived": 55.0}, self.cfg)
        self.assertEqual(got["source_kind"], "native")

    def test_trimp_formula(self):
        self.assertAlmostEqual(0.5 * 0.64 * math.exp(1.92 * 0.5), 0.8358, places=3)


def main(argv):
    write = "--write" in argv
    problems = []
    cfg = R.load_config()
    lint_config(cfg, problems)
    counts = check_vectors(cfg, write, problems)
    check_portability(problems)
    check_copies(write, problems)
    check_doc(cfg, write, problems)
    suite = unittest.defaultTestLoader.loadTestsFromTestCase(ReferenceTests)
    result = unittest.TextTestRunner(stream=open(os.devnull, "w"), verbosity=0).run(suite)
    for failed, trace in result.failures + result.errors:
        problems.append("unittest %s failed:\n%s" % (failed.id(), trace.strip().splitlines()[-1]))
    print("%d derived metrics in %d categories" % (len(cfg["metrics"]), len(cfg["categories"])))
    for name, n in sorted(counts.items()):
        print("%-40s %3d cases" % ("test-vectors/" + name, n))
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
