#!/usr/bin/env python3
"""Ayuvo cycle tracking (period tracker) contract check (Python 3 stdlib only).

    python3 scripts/cycle_contract_check.py           # verify; exit 1 on any problem
    python3 scripts/cycle_contract_check.py --write   # rewrite vectors' "expected", the platform copies and the doc block

Checks: config lint (limits, catalogues, copy rules), vectors equal scripts/cycle_reference.py on their input with
coverage tags, portability lint, byte-identical platform copies of shared/cycle/cycle_config.json and
shared/cycle/coach.json, the schema header, and the generated block in docs/cycle-tracking.md.
"""

import glob
import json
import os
import re
import sys
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
SHARED = os.path.join(ROOT, "shared", "cycle")
VECTORS = os.path.join(SHARED, "test-vectors")
CONFIG = os.path.join(SHARED, "cycle_config.json")
COACH = os.path.join(SHARED, "coach.json")
SCHEMA = os.path.join(SHARED, "schema.sql")
DOC = os.path.join(ROOT, "docs", "cycle-tracking.md")
COPY_DIRS = [
    os.path.join(ROOT, "android", "app", "src", "main", "assets", "cycle"),
    os.path.join(ROOT, "ios", "calorietracker", "Cycle", "Resources"),
]
sys.path.insert(0, HERE)
import cycle_reference as R  # noqa: E402
from derived_contract_check import _first_diff, dumps  # noqa: E402

FORMAT = "ayuvo-cycle-vectors"
EXPECTED_FILES = dict((f + ".json", f) for f in R.FUNCTIONS)
PHASES = ("period", "predicted_period", "follicular", "fertile", "ovulation", "luteal", "late", "unknown")
REQUIRED = {
    "normalize": ["future", "end_before_start", "duplicate", "merged", "auto_closed", "ongoing", "platform_only"],
    "snapshot": ["basis_none", "basis_default", "basis_limited", "basis_history", "late", "ongoing", "variability_high",
                 "insight_recent_range", "insight_recent_same", "insight_variable", "insight_late",
                 "insight_out_of_range", "insight_long_period", "insight_severe_pain", "insight_few_cycles",
                 "reminder_period_soon", "reminder_period_end", "reminder_daily_log", "no_ovulation"],
    "day_status": ["phase_" + p for p in PHASES],
    "validate_period": ["ok", "future_start", "future_end", "end_before_start", "too_long", "overlap",
                        "open_not_latest"],
    "apply_period_day": ["insert", "insert_open", "extend_before", "extend_after", "bridge", "delete",
                         "shrink_start", "truncate", "noop", "future"],
    "days": ["leap", "year_boundary"],
    "settings": ["defaults", "clamped"],
    "trends": ["symptoms", "flow_pattern", "empty"],
    "cycles": ["invalid_cycle", "trimmed_range"],
}
BANNED = ("pcos", "endometriosis", "infertil", "contracept", "guarantee", "will ovulate", "definitely",
          "you have", "abnormal", "disorder", "you are pregnant")


def lint_config(cfg, problems):
    if cfg.get("format") != "ayuvo-cycle-config" or cfg.get("config_version") != 1:
        problems.append("config: bad format/config_version")
    lim, d = cfg["limits"], cfg["defaults"]
    if not lim["setting_cycle_min"] <= d["cycle_length"] <= lim["setting_cycle_max"]:
        problems.append("defaults.cycle_length outside setting limits")
    if not lim["setting_period_min"] <= d["period_length"] <= lim["setting_period_max"]:
        problems.append("defaults.period_length outside setting limits")
    if not lim["luteal_min"] <= d["luteal_length"] <= lim["luteal_max"]:
        problems.append("defaults.luteal_length outside limits")
    if lim["cycle_min"] > lim["setting_cycle_min"] or lim["period_max"] >= lim["cycle_min"] + 1:
        problems.append("limits: a period must always fit inside the shortest valid cycle")
    if [p["key"] for p in cfg["phases"]] != list(PHASES):
        problems.append("phases must be %s" % ", ".join(PHASES))
    if [b["key"] for b in cfg["basis"]] != ["none", "default", "limited", "history"]:
        problems.append("basis keys must be none, default, limited, history")
    groups = set(g["key"] for g in cfg["symptom_groups"])
    for catalog in ("flow_levels", "symptoms", "moods", "pain_locations"):
        seen = set()
        for item in cfg[catalog]:
            if not re.match(r"^[a-z][a-z_]*$", item["key"]) or item["key"] in seen:
                problems.append("%s: bad or duplicate key %s" % (catalog, item["key"]))
            seen.add(item["key"])
            if not item.get("title"):
                problems.append("%s.%s: needs a title" % (catalog, item["key"]))
            if catalog == "symptoms" and item["group"] not in groups:
                problems.append("symptoms.%s: unknown group" % item["key"])
    ranks = [f["rank"] for f in cfg["flow_levels"]]
    if ranks != sorted(ranks):
        problems.append("flow_levels must be ordered by rank")
    keys = set(i["key"] for i in cfg["insights"])
    for k in ("recent_range", "recent_same", "variable", "late", "out_of_range", "long_period", "severe_pain",
              "few_cycles"):
        if k not in keys:
            problems.append("insights.%s missing" % k)
    texts = [cfg["disclaimer"], cfg["fertility_note"]]
    for i in cfg["insights"]:
        texts.append(i["template"])
        if i["professional"] and "healthcare professional" not in i["template"]:
            problems.append("insights.%s: professional insight must suggest a healthcare professional" % i["key"])
    for b in cfg["basis"]:
        texts += [b["title"], b["about"]]
    for p in cfg["phases"]:
        texts.append(p["title"])
    for t in texts:
        low = t.lower()
        for b in BANNED:
            if b in low:
                problems.append("copy uses banned phrase %r: %s" % (b, t))
        if "diagnos" in low and "not a medical measurement or diagnosis" not in low:
            problems.append("copy mentions diagnosis outside the disclaimer: %s" % t)
    if "not a form of birth control" not in cfg["disclaimer"]:
        problems.append("disclaimer must say estimates are not a form of birth control")


def lint_coach(problems):
    coach = json.load(open(COACH, encoding="utf-8"))
    if coach.get("format") != "ayuvo-cycle-coach" or not coach.get("prompt"):
        problems.append("coach.json: bad format or missing prompt")
        return
    prompt = " ".join(coach["prompt"]).lower()
    for needed in ("never diagnose", "estimate", "healthcare professional", "birth control"):
        if needed not in prompt:
            problems.append("coach.json prompt must mention %r" % needed)
    if "note" not in " ".join(coach.get("never_shared", [])):
        problems.append("coach.json: never_shared must list notes")


def _cover(seen, f, inp, got):
    def add(tag):
        seen.add("%s:%s" % (f, tag))
    if f == "normalize":
        for d in got["dropped"]:
            add(d["reason"])
        for p in got["periods"]:
            if len(p["members"]) > 1:
                add("merged")
            if p["auto_closed"]:
                add("auto_closed")
            if p["ongoing"]:
                add("ongoing")
            if p["source"] != "app":
                add("platform_only")
    elif f == "snapshot":
        pr = got["prediction"]
        add("basis_" + pr["basis"])
        if pr["late_days"] > 0:
            add("late")
        if pr["ongoing"]:
            add("ongoing")
        if got["stats"]["variability"] == "high":
            add("variability_high")
        for i in got["insights"]:
            add("insight_" + i["key"])
        for r in got["reminders"]:
            add("reminder_" + r["kind"])
        for w in pr["windows"]:
            if w["ovulation"] is None:
                add("no_ovulation")
    elif f == "day_status":
        for d in got:
            add("phase_" + d["phase"])
    elif f == "validate_period":
        if got["ok"]:
            add("ok")
        for e in got["errors"]:
            add(e)
    elif f == "apply_period_day":
        if got["error"]:
            add(got["error"])
        elif not got["ops"]:
            add("noop")
        else:
            kinds = [o["op"] for o in got["ops"]]
            if kinds == ["insert"]:
                add("insert_open" if got["ops"][0]["end"] is None else "insert")
            elif kinds == ["update", "delete"]:
                add("bridge")
            elif kinds == ["delete"]:
                add("delete")
            else:
                o = got["ops"][0]
                before = None
                for p in inp["periods"]:
                    if p["id"] == o["id"]:
                        before = p
                if inp["on"]:
                    add("extend_after" if o["start"] != before["start"] else "extend_before")
                else:
                    add("shrink_start" if o["start"] != before["start"] else "truncate")
    elif f == "days":
        for d in got:
            if d["day"][5:] in ("02-29", "03-01"):
                add("leap")
            if d["day"][5:] in ("12-31", "01-01"):
                add("year_boundary")
    elif f == "settings":
        add("clamped" if inp.get("settings") else "defaults")
    elif f == "trends":
        if got["symptom_frequency"]:
            add("symptoms")
        if got["flow_pattern"]:
            add("flow_pattern")
        if not got["cycles"]:
            add("empty")
    elif f == "cycles":
        for c in got["cycles"]:
            if c["cycle_length"] is not None and not c["cycle_valid"]:
                add("invalid_cycle")
        if got["stats"]["cycle_count"] >= 5:
            add("trimmed_range")


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
            _cover(seen, doc["function"], c["input"], got)
        counts[name] = len(doc["cases"])
        body = dumps(doc)
        if write and (changed or raw != body):
            open(path, "w", encoding="utf-8").write(body)
        elif not write and raw != body:
            problems.append("%s: not in canonical format (run --write)" % name)
    for f, tags in sorted(REQUIRED.items()):
        for t in tags:
            if "%s:%s" % (f, t) not in seen:
                problems.append("coverage: no vector covers %s:%s" % (f, t))
    return counts


def check_portability(problems):
    src = open(os.path.join(HERE, "cycle_reference.py"), encoding="utf-8").read()
    for pattern, what in (("import statistics", "statistics module"), ("(?<![A-Za-z_.])round[(]", "built-in round()"),
                          ("(?<![A-Za-z_.])sum[(]", "built-in sum()"), ("math[.]fsum", "math.fsum"),
                          ("import datetime", "datetime"), ("[.]isoformat", "isoformat")):
        if re.search(pattern, src):
            problems.append("cycle_reference.py uses %s" % what)


def check_copies(write, problems):
    for source in (CONFIG, COACH):
        body = open(source, encoding="utf-8").read()
        for folder in COPY_DIRS:
            path = os.path.join(folder, os.path.basename(source))
            current = open(path, encoding="utf-8").read() if os.path.exists(path) else None
            if current == body:
                continue
            if write:
                os.makedirs(folder, exist_ok=True)
                open(path, "w", encoding="utf-8").write(body)
            else:
                problems.append("%s differs from shared/cycle/%s (run --write)" % (os.path.relpath(path, ROOT),
                                                                                  os.path.basename(source)))


def schema_statements(path=SCHEMA):
    """schema.sql -> list of statements with comments stripped (the docs/health-records.md §8 rule)."""
    lines = []
    for line in open(path, encoding="utf-8").read().splitlines():
        if line.strip().startswith("--"):
            continue
        lines.append(line)
    out = []
    for part in "\n".join(lines).split(";"):
        if part.strip():
            out.append(part.strip() + ";")
    return out


def check_schema(problems):
    stmts = schema_statements()
    tables = set()
    for s in stmts:
        m = re.match(r"CREATE TABLE (\w+)", s)
        if m:
            tables.add(m.group(1))
    for t in ("cycle_periods", "cycle_day_logs", "cycle_settings", "cycle_meta"):
        if t not in tables:
            problems.append("schema.sql: table %s missing" % t)


FIXTURE = os.path.join(SHARED, "fixtures", "cycle-sample")


def check_fixture(cfg, problems):
    man = json.load(open(os.path.join(FIXTURE, "manifest.json"), encoding="utf-8"))
    periods, logs = [], []
    for line in open(os.path.join(FIXTURE, "periods.ndjson"), encoding="utf-8"):
        periods.append(json.loads(line))
    for line in open(os.path.join(FIXTURE, "day_logs.ndjson"), encoding="utf-8"):
        logs.append(json.loads(line))
    settings = json.load(open(os.path.join(FIXTURE, "settings.json"), encoding="utf-8"))
    if man["counts"] != {"periods": len(periods), "day_logs": len(logs)}:
        problems.append("fixture: manifest counts do not match the ndjson files")
    state = {"today": man["expected"]["today"],
             "settings": {"cycle_length": settings["cycle_length"], "period_length": settings["period_length"],
                          "luteal_length": settings["luteal_length"]},
             "periods": [{"id": p["id"], "start": p["start_day"], "end": p["end_day"]} for p in periods if not p["deleted"]],
             "logs": [lg for lg in logs if not lg["deleted"]]}
    snap = R.snapshot(state, cfg)
    exp = man["expected"]
    got = {"today": exp["today"], "next_start": snap["prediction"]["next_start"], "basis": snap["prediction"]["basis"],
           "cycle_length": snap["prediction"]["cycle_length"], "today_phase": snap["today"]["phase"],
           "live_day_logs": len(state["logs"])}
    if got != exp:
        problems.append("fixture: manifest expected %s, reference gives %s" % (exp, got))


BEGIN, END = "<!-- BEGIN GENERATED CYCLE -->", "<!-- END GENERATED CYCLE -->"


def generated_block(cfg):
    d, lim, pc = cfg["defaults"], cfg["limits"], cfg["prediction"]
    lines = [BEGIN, "",
             "| Setting | Value |", "|---|---|",
             "| Default cycle / period / luteal length | %d / %d / %d days |" % (d["cycle_length"], d["period_length"],
                                                                             d["luteal_length"]),
             "| Valid cycle (counts towards averages) | %d–%d days |" % (lim["cycle_min"], lim["cycle_max"]),
             "| Valid period | %d–%d days |" % (lim["period_min"], lim["period_max"]),
             "| History window | last %d valid cycles |" % pc["history_window"],
             "| History-based estimate from | %d valid cycles |" % pc["history_min_cycles"],
             "| Trimmed typical range from | %d valid cycles |" % pc["trimmed_range_min_cycles"],
             "| Fertile window | ovulation −%d to +%d days |" % (cfg["fertile"]["days_before_ovulation"],
                                                             cfg["fertile"]["days_after_ovulation"]),
             "| High variability | range ≥ %d days or SD > %s days (from %d cycles) |" % (
                 cfg["variability"]["high_range_days"], cfg["variability"]["high_sd_days"],
                 cfg["variability"]["min_cycles"]),
             "", "| Flow | HealthKit | Health Connect |", "|---|---|---|"]
    for f in cfg["flow_levels"]:
        lines.append("| %s | %s | %s |" % (f["title"], f["healthkit"] or "—", f["health_connect"] or "—"))
    lines += ["", "| Symptom | Group | HealthKit type |", "|---|---|---|"]
    for s in cfg["symptoms"]:
        lines.append("| %s | %s | %s |" % (s["title"], s["group"], s["healthkit"] or "— (stays local)"))
    lines += ["", "| Insight | Text |", "|---|---|"]
    for i in cfg["insights"]:
        lines.append("| `%s` | %s |" % (i["key"], i["template"]))
    lines += ["", END]
    return "\n".join(lines)


def check_doc(cfg, write, problems):
    if not os.path.exists(DOC):
        problems.append("docs/cycle-tracking.md missing")
        return
    text = open(DOC, encoding="utf-8").read()
    if BEGIN not in text or END not in text:
        problems.append("docs/cycle-tracking.md: generated markers missing")
        return
    start, end = text.index(BEGIN), text.index(END) + len(END)
    new = text[:start] + generated_block(cfg) + text[end:]
    if new != text:
        if write:
            open(DOC, "w", encoding="utf-8").write(new)
        else:
            problems.append("docs/cycle-tracking.md generated block is stale (run --write)")


class ReferenceTests(unittest.TestCase):
    cfg = R.load_config()

    def test_day_ordinals(self):
        self.assertEqual(R.day_ordinal("1970-01-01"), 0)
        self.assertEqual(R.day_ordinal("2000-03-01") - R.day_ordinal("2000-02-28"), 2)
        self.assertEqual(R.day_ordinal("2100-03-01") - R.day_ordinal("2100-02-28"), 1)
        self.assertEqual(R.day_string(R.day_ordinal("2028-02-29")), "2028-02-29")

    def test_period_duration_inclusive(self):
        got = R.validate_period({"today": "2026-09-30", "candidate": {"start": "2026-09-12", "end": "2026-09-16"},
                                 "periods": []}, self.cfg)
        self.assertEqual(got["duration"], 5)

    def test_average_example(self):
        periods = []
        starts = ["2026-05-01", "2026-05-30", "2026-06-27", "2026-07-27", "2026-08-25"]
        for i, s in enumerate(starts):
            periods.append({"id": "p%d" % i, "start": s, "end": R.day_string(R.day_ordinal(s) + 4)})
        snap = R.snapshot({"today": "2026-09-01", "periods": periods}, self.cfg)
        self.assertEqual(snap["stats"]["cycle_lengths"], [29, 28, 30, 29])
        self.assertEqual(snap["prediction"]["cycle_length"], 29)
        self.assertEqual(snap["prediction"]["next_start"], "2026-09-23")

    def test_round_half_up(self):
        self.assertEqual(R.round_half_up_int(28.5), 29)
        self.assertEqual(R.round_to(0.585, 2), 0.59)


def main(argv):
    write = "--write" in argv
    problems = []
    cfg = R.load_config()
    lint_config(cfg, problems)
    lint_coach(problems)
    check_schema(problems)
    check_fixture(cfg, problems)
    counts = check_vectors(cfg, write, problems)
    check_portability(problems)
    check_copies(write, problems)
    check_doc(cfg, write, problems)
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
