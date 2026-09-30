#!/usr/bin/env python3
"""Ayuvo intake (nutrition, medications, strength, energy balance, labs) contract check (Python 3 stdlib only).

    python3 scripts/intake_contract_check.py           # verify; exit 1 on any problem
    python3 scripts/intake_contract_check.py --write   # rewrite vectors' "expected" and the platform copies

Checks: config lint (DRI tables, thresholds, muscle groups, lab links, sources), vectors equal scripts/intake_reference.py on their input with
coverage tags, portability lint, and byte-identical platform copies of shared/intake/intake_config.json.
"""

import glob
import json
import os
import re
import sys
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
SHARED = os.path.join(ROOT, "shared", "intake")
VECTORS = os.path.join(SHARED, "test-vectors")
CONFIG = os.path.join(SHARED, "intake_config.json")
COPY_DIRS = [
    os.path.join(ROOT, "android", "app", "src", "main", "assets", "intake"),
    os.path.join(ROOT, "ios", "calorietracker", "Services", "Intake", "Resources"),
]
sys.path.insert(0, HERE)
import intake_reference as R  # noqa: E402
from derived_contract_check import _first_diff, dumps  # noqa: E402

FORMAT = "ayuvo-intake-vectors"
EXPECTED_FILES = dict((f + ".json", f) for f in R.FUNCTIONS)
REQUIRED = {"nutrition_day": ["full", "empty", "iron_risk"], "dri_goals": ["19-30", "51-70", "71+"],
            "nutrient_coverage": ["shortfall", "excess", "too_few_days"], "supplement_daily": ["above", "within"],
            "meds_adherence": ["adherent", "not_adherent", "suggested", "none"], "strength_week": ["e1rm", "below_range"],
            "energy_balance": ["ok", "insufficient"], "paired_difference": ["ok", "insufficient"],
            "lab_nutrient_links": ["link", "no_link"]}
BANDS = ("19-30", "31-50", "51-70", "71+")


def lint_config(cfg, problems):
    if cfg.get("format") != "ayuvo-intake-config" or cfg.get("config_version") != 1:
        problems.append("config: bad format/config_version")
    dri = cfg["dri"]
    if [b["id"] for b in dri["age_bands"]] != list(BANDS):
        problems.append("dri.age_bands must be %s" % ", ".join(BANDS))
    for key, row in dri["goals"].items():
        if set(row) != set(BANDS) or any(len(v) != 2 or min(v) <= 0 for v in row.values()):
            problems.append("dri.goals.%s: needs [male, female] > 0 for every band" % key)
    if dri["goals"]["iron_mg"]["19-30"] != [8, 18] or dri["goals"]["vitamin_d_mcg"]["71+"] != [20, 20]:
        problems.append("dri: iron 8/18 mg (19-50) and vitamin D 20 mcg (71+) per NIH ODS")
    for key in dri["upper"]:
        if key in dri["goals"] and dri["upper"][key] <= max(max(v) for v in dri["goals"][key].values()):
            problems.append("dri.upper.%s must exceed every goal" % key)
    th = cfg["thresholds"]
    if th["adherent_pct"] != 80 or th["pair_min_group"] < 8 or th["energy_per_kg"] != 7700:
        problems.append("thresholds: adherence 80% (PQA), pair groups >= 8, 7700 kcal/kg")
    for g in ("push", "pull", "legs"):
        if not cfg["muscle_groups"].get(g):
            problems.append("muscle_groups.%s missing" % g)
    for r in cfg["lab_links"]:
        if r["nutrient"] not in dri["goals"]:
            problems.append("lab_links.%s: nutrient has no DRI goal" % r["id"])
    for k in ("macros", "protein", "iron", "adherence", "strength", "energy", "labs"):
        if len(cfg["sources"].get(k, "")) < 20:
            problems.append("sources.%s missing" % k)


def _cover(seen, f, got):
    def add(tag):
        seen.add("%s:%s" % (f, tag))
    if f == "nutrition_day":
        add("full" if got["items"] else "empty")
        if got["iron_absorption_risk"]:
            add("iron_risk")
    elif f == "dri_goals":
        add(got["band"])
    elif f == "nutrient_coverage":
        if got["shortfalls"]:
            add("shortfall")
        if got["excesses"]:
            add("excess")
        if not got["coverage"]:
            add("too_few_days")
    elif f == "supplement_daily":
        add("above" if got["above_upper"] else "within")
    elif f == "meds_adherence":
        add("none" if got["adherent"] is None else ("adherent" if got["adherent"] else "not_adherent"))
        if got["suggested_clock_min"] is not None:
            add("suggested")
    elif f == "strength_week":
        if got["e1rm"]:
            add("e1rm")
        if got["below_range"]:
            add("below_range")
    elif f in ("energy_balance", "paired_difference"):
        add(got["status"])
    elif f == "lab_nutrient_links":
        add("link" if got["links"] else "no_link")


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
    src = open(os.path.join(HERE, "intake_reference.py"), encoding="utf-8").read()
    for pattern, what in (("import statistics", "statistics module"), ("(?<![A-Za-z_.])round[(]", "built-in round()"),
                          ("(?<![A-Za-z_.])sum[(]", "built-in sum()"), ("math[.]fsum", "math.fsum")):
        if re.search(pattern, src):
            problems.append("intake_reference.py uses %s" % what)


def check_copies(write, problems):
    body = open(CONFIG, encoding="utf-8").read()
    for folder in COPY_DIRS:
        path = os.path.join(folder, "intake_config.json")
        current = open(path, encoding="utf-8").read() if os.path.exists(path) else None
        if current == body:
            continue
        if write:
            os.makedirs(folder, exist_ok=True)
            open(path, "w", encoding="utf-8").write(body)
        else:
            problems.append("%s differs from shared/intake/intake_config.json (run --write)" % os.path.relpath(path, ROOT))


class ReferenceTests(unittest.TestCase):
    cfg = R.load_config()

    def test_epley(self):
        self.assertAlmostEqual(R.epley(100.0, 10), 133.3333, places=3)

    def test_dri_male_iron(self):
        self.assertEqual(R.dri_goals({"sex": "male", "age": 24}, self.cfg)["goals"]["iron_mg"], 8)

    def test_na_k_molar(self):
        got = R.nutrition_day({"time_zone": "UTC", "weight_kg": None, "bedtime_ms": None, "items": [
            {"eaten_ms": 0, "meal": "lunch", "calories": 100, "sodium_mg": 2299.0, "potassium_mg": 3910.0}]}, self.cfg)
        self.assertEqual(got["na_k_ratio"], 1.0)


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
