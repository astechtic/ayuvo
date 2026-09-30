#!/usr/bin/env python3
"""Ayuvo workout (GPS / heart-rate) contract check (Python 3 stdlib only).

    python3 scripts/workout_contract_check.py           # verify; exit 1 on any problem
    python3 scripts/workout_contract_check.py --write   # rewrite vectors' "expected" and the platform copies

Checks: config lint (sports, thresholds, sources), vectors equal scripts/workout_reference.py on their input with
coverage tags, portability lint, and byte-identical platform copies of shared/workout/workout_config.json.
"""

import glob
import json
import os
import re
import sys
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
SHARED = os.path.join(ROOT, "shared", "workout")
VECTORS = os.path.join(SHARED, "test-vectors")
CONFIG = os.path.join(SHARED, "workout_config.json")
COPY_DIRS = [
    os.path.join(ROOT, "android", "app", "src", "main", "assets", "workout"),
    os.path.join(ROOT, "ios", "calorietracker", "Services", "Workout", "Resources"),
]
sys.path.insert(0, HERE)
import workout_reference as R  # noqa: E402
from derived_contract_check import _first_diff, dumps  # noqa: E402

FORMAT = "ayuvo-workout-vectors"
EXPECTED_FILES = dict((f + ".json", f) for f in R.FUNCTIONS)
SPORTS = ("walk", "run", "cycle", "hike")
REQUIRED = {"gps_track": ["distance", "dropped", "splits", "empty"], "hr_workout": ["hrr", "hrmax", "kcal", "no_kcal"],
            "hr_recovery": ["medium", "low", "none", "flag"], "vo2max_gps": ["ok", "no_steady_segment",
                                                                           "no_heart_rate_reserve"],
            "cooper": ["value", "none"], "workout_windows": ["windows", "level_100", "level_hrr"]}


def lint_config(cfg, problems):
    if cfg.get("format") != "ayuvo-workout-config" or cfg.get("config_version") != 1:
        problems.append("config: bad format/config_version")
    if set(cfg["sports"]) != set(SPORTS):
        problems.append("sports must be %s" % ", ".join(SPORTS))
    for sid, s in cfg["sports"].items():
        if not 0 < s["auto_pause_speed_mps"] < s["max_speed_mps"]:
            problems.append("sports.%s: auto-pause speed must be below the max speed" % sid)
        for k in ("title", "hk_activity", "hc_exercise", "met_item_id"):
            if not s.get(k):
                problems.append("sports.%s: missing %s" % (sid, k))
    th = cfg["thresholds"]
    if not 0 < th["min_hrr"] < th["max_hrr"] <= 1 or th["hrr1_abnormal_below"] != 12:
        problems.append("thresholds: bad %HRR window or HRR1 cut-off (Cole 1999: 12 bpm)")
    for sex in ("male", "female", "other"):
        if len(th["keytel"].get(sex, [])) != 4:
            problems.append("thresholds.keytel.%s needs 4 coefficients" % sex)
    for k in ("gps", "zones", "trimp", "keytel", "acsm", "cooper", "hrr1", "windows"):
        if len(cfg["sources"].get(k, "")) < 20:
            problems.append("sources.%s missing" % k)


def _cover(seen, f, got):
    def add(tag):
        seen.add("%s:%s" % (f, tag))
    if f == "gps_track":
        add("distance" if got["distance_m"] else "empty")
        if got["dropped_points"]:
            add("dropped")
        if got["splits"]:
            add("splits")
    elif f == "hr_workout":
        add(got["zone_method"])
        add("kcal" if got["kcal"] is not None else "no_kcal")
    elif f == "hr_recovery":
        add(got["confidence"] or "none")
        if got["flag_low"]:
            add("flag")
    elif f == "vo2max_gps":
        add(got["status"])
    elif f == "cooper":
        add("value" if got["vo2max"] is not None else "none")
    elif f == "workout_windows":
        if got["windows"]:
            add("windows")
        add("level_100" if got["level_bpm"] == 100.0 else "level_hrr")


def check_vectors(cfg, write, problems):
    counts, seen = {}, set()
    present = sorted(os.path.basename(p) for p in glob.glob(os.path.join(VECTORS, "*.json")))
    for name in sorted(set(EXPECTED_FILES) - set(present)):
        problems.append("test-vectors/%s missing" % name)
    for name in present:
        if name not in EXPECTED_FILES:
            problems.append("test-vectors/%s: unknown vector file" % name)
            continue
        path = os.path.join(VECTORS, name)
        raw = open(path, encoding="utf-8").read()
        doc = json.loads(raw)
        if doc.get("format") != FORMAT or doc.get("version") != 1 or doc.get("function") != EXPECTED_FILES[name]:
            problems.append("%s: bad envelope" % name)
            continue
        changed = False
        for c in doc["cases"]:
            got = json.loads(json.dumps(R.run_case(doc["function"], c["input"], cfg)))
            if write:
                if c.get("expected") != got:
                    c["expected"], changed = got, True
            elif c.get("expected") != got:
                problems.append("%s/%s: expected differs at %s" % (name, c["name"], _first_diff(c.get("expected"), got)))
            _cover(seen, doc["function"], got)
        counts[name] = len(doc["cases"])
        if write and (changed or raw != dumps(doc)):
            open(path, "w", encoding="utf-8").write(dumps(doc))
        elif not write and raw != dumps(doc):
            problems.append("%s: not in canonical format (run --write)" % name)
    for f, tags in sorted(REQUIRED.items()):
        for t in tags:
            if "%s:%s" % (f, t) not in seen:
                problems.append("coverage: no vector covers %s:%s" % (f, t))
    return counts


def check_portability(problems):
    src = open(os.path.join(HERE, "workout_reference.py"), encoding="utf-8").read()
    for pattern, what in (("import statistics", "statistics module"), ("(?<![A-Za-z_.])round[(]", "built-in round()"),
                          ("(?<![A-Za-z_.])sum[(]", "built-in sum()"), ("math[.]fsum", "math.fsum")):
        if re.search(pattern, src):
            problems.append("workout_reference.py uses %s" % what)


def check_copies(write, problems):
    body = open(CONFIG, encoding="utf-8").read()
    for folder in COPY_DIRS:
        path = os.path.join(folder, "workout_config.json")
        current = open(path, encoding="utf-8").read() if os.path.exists(path) else None
        if current == body:
            continue
        if write:
            os.makedirs(folder, exist_ok=True)
            open(path, "w", encoding="utf-8").write(body)
        else:
            problems.append("%s differs from shared/workout/workout_config.json (run --write)" % os.path.relpath(path, ROOT))


class ReferenceTests(unittest.TestCase):
    cfg = R.load_config()

    def test_haversine_one_degree_latitude(self):
        self.assertAlmostEqual(R.haversine_m(0.0, 0.0, 1.0, 0.0), 111195.1, places=0)

    def test_acsm_running(self):
        # 200 m/min flat: 0.2*200 + 3.5 = 43.5
        self.assertAlmostEqual(R.acsm_vo2(200.0 / 60.0, 0.0, "run"), 43.5)

    def test_cooper(self):
        self.assertEqual(R.cooper({"distance_m": 2400.0}, self.cfg)["vo2max"], R.round_to((2400 - 504.9) / 44.73, 1))

    def test_keytel_male(self):
        k = self.cfg["thresholds"]["keytel"]["male"]
        per_min = (k[0] + k[1] * 150 + k[2] * 70 + k[3] * 30) / 4.184
        self.assertAlmostEqual(per_min, 14.22, places=2)


def main(argv):
    write = "--write" in argv
    problems = []
    cfg = R.load_config()
    lint_config(cfg, problems)
    counts = check_vectors(cfg, write, problems)
    check_portability(problems)
    check_copies(write, problems)
    suite = unittest.defaultTestLoader.loadTestsFromTestCase(ReferenceTests)
    result = unittest.TextTestRunner(stream=open(os.devnull, "w"), verbosity=0).run(suite)
    for failed, trace in result.failures + result.errors:
        problems.append("unittest %s failed:\n%s" % (failed.id(), trace.strip().splitlines()[-1]))
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
