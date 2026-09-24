#!/usr/bin/env python3
"""Ayuvo Medications contract check (Python 3 stdlib only).

    python3 scripts/medications_contract_check.py           # verify; exit 1 on any problem
    python3 scripts/medications_contract_check.py --write   # rewrite every vector's "expected" from the reference

Checks:
  1. shared/medications/test-vectors/*.json: envelope shape, unique case names, stable formatting (sorted keys,
     2-space indent, UTF-8 without escapes, trailing newline), and every case's "expected" equals
     scripts/medications_reference.py run on its "input" (inputs are the source of truth; --write only replaces
     "expected"). Every file in EXPECTED_FILES must exist and no unknown file may be present.
  2. Output shapes per function (enum values, ordering, new-N id sequences, integer percent).
  3. shared/medications/schema.sql (+ migrations/*.sql when present) split with the records statements() rule
     (docs/health-records.md §8); table and index names equal the documented lists.
  4. Archive round trip: export -> merge into an empty snapshot -> export again is byte-identical.
  5. Documented constants equal the reference's; DST vectors only use the four allowed zones.
  6. Safety: no function other than an explicit dose action produces a `taken` row (merge only copies rows that
     were `taken` in the archive input).
  7. Regex portability lint of every pattern owned by the reference (same rule as the records contract).
  8. A small unittest suite with hand-computed expectations for the tricky cases.
"""

import glob
import json
import os
import re
import sys
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
SHARED = os.path.join(ROOT, "shared", "medications")
VECTORS = os.path.join(SHARED, "test-vectors")
sys.path.insert(0, HERE)
import medications_reference as M  # noqa: E402
from records_contract_check import statements, lint_pattern  # noqa: E402

FORMAT = "ayuvo-medications-vectors"
EXPECTED_FILES = {
    "occurrences.json": "expand_occurrences", "dose_status.json": "resolve_dose_status",
    "missed.json": "materialize_missed", "timeline.json": "today_timeline", "adherence.json": "adherence",
    "reminders.json": "plan_reminders", "dose_actions.json": "dose_actions", "lifecycle.json": "lifecycle",
    "auto_complete.json": "auto_complete", "frequency_hint.json": "frequency_hint", "archive.json": "archive",
    "validation.json": "validate_draft", "coach_tools_payloads.json": "coach_tools",
}
TABLES = ["medications", "medication_schedules", "dose_logs", "medications_meta"]
INDEXES = ["idx_medications_status", "idx_medications_record", "idx_schedules_medication", "idx_schedules_open",
           "idx_dose_logs_occurrence", "idx_dose_logs_medication", "idx_dose_logs_scheduled", "idx_dose_logs_status"]
ALLOWED_ZONES = frozenset(["America/New_York", "Europe/London", "Asia/Kolkata", "UTC"])
DOCUMENTED = {"GRACE_MS": 7_200_000, "LATE_FIRE_MS": 300_000, "ADHERENCE_WINDOW_MS": 7 * 86_400_000, "IOS_BUDGET": 52,
              "SNOOZE_MINUTES": (10, 30, 60), "SLOTS_1": ["08:00"], "SLOTS_2": ["08:00", "20:00"],
              "SLOTS_3": ["08:00", "14:00", "20:00"], "SLOTS_4": ["08:00", "13:00", "18:00", "22:00"],
              "SLOT_NIGHT": ["22:00"], "INTERVAL_HOURS": (1, 2, 3, 4, 6, 8, 12, 24), "MAX_TIMES": 12}


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
# Output shapes
# ---------------------------------------------------------------------------------------------

def _is_int(v):
    return isinstance(v, int) and not isinstance(v, bool)


def _check_log_row(row, where, problems):
    if set(row) != set(M.DOSE_LOG_COLUMNS):
        problems.append("%s: dose log row keys %s" % (where, sorted(row)))
        return
    if row["status"] not in M.STORED_DOSE_STATUSES:
        problems.append("%s: bad stored status %r" % (where, row["status"]))
    if row["status"] == "taken" and not _is_int(row["taken_at_ms"]):
        problems.append("%s: taken row without taken_at_ms" % where)
    if row["status"] == "snoozed" and not _is_int(row["snoozed_until_ms"]):
        problems.append("%s: snoozed row without snoozed_until_ms" % where)
    if row["schedule_id"] is None and row["status"] != "taken":
        problems.append("%s: PRN row must be taken" % where)


def _check_new_ids(ids, where, problems):
    new = [i for i in ids if isinstance(i, str) and i.startswith("new-")]
    if new != ["new-%d" % (k + 1) for k in range(len(new))]:
        problems.append("%s: new-N ids not sequential: %s" % (where, new))


def check_shape(function, got, where, problems, inp):
    if function == "expand_occurrences":
        occ = got["occurrences"]
        keys = [(o["scheduled_at_ms"], o["slot"]) for o in occ]
        if keys != sorted(keys) or len(set(o["scheduled_at_ms"] for o in occ)) != len(occ):
            problems.append("%s: occurrences not ascending/unique" % where)
        for o in occ:
            if set(o) != {"medication_id", "schedule_id", "scheduled_at_ms", "local_date", "slot"}:
                problems.append("%s: occurrence keys %s" % (where, sorted(o)))
            if M.parse_hhmm(o["slot"]) is None or M.parse_date(o["local_date"]) is None:
                problems.append("%s: bad slot/date" % where)
    elif function == "resolve_dose_status":
        if set(got) != {"status", "deadline_ms", "is_late"} or got["status"] not in M.DOSE_STATUSES:
            problems.append("%s: bad status result" % where)
        if got["is_late"] and got["status"] != "taken":
            problems.append("%s: is_late on a non-taken status" % where)
    elif function == "materialize_missed":
        for op in got["ops"]:
            if op["op"] not in ("insert", "update") or op["row"]["status"] != "missed":
                problems.append("%s: materialize op must insert/update a missed row" % where)
            _check_log_row(op["row"], where, problems)
        _check_new_ids([op["row"]["id"] for op in got["ops"]], where, problems)
    elif function == "today_timeline":
        if set(got) != {"date", "summary", "groups", "prn"}:
            problems.append("%s: timeline keys %s" % (where, sorted(got)))
        slots = [g["slot"] for g in got["groups"]]
        if slots != sorted(slots) or len(set(slots)) != len(slots):
            problems.append("%s: groups not ascending" % where)
        total = 0
        for g in got["groups"]:
            for it in g["items"]:
                if it["status"] not in M.DOSE_STATUSES or it["kind"] not in ("scheduled", "prn"):
                    problems.append("%s: bad item" % where)
                if it["kind"] == "prn" and it["status"] != "taken":
                    problems.append("%s: PRN item must be taken" % where)
                total += it["kind"] == "scheduled"
        s = got["summary"]
        if set(s) != {"total", "taken", "upcoming", "due", "snoozed", "missed", "skipped"} or s["total"] != total:
            problems.append("%s: summary mismatch" % where)
        if sum(s[k] for k in ("taken", "upcoming", "due", "snoozed", "missed", "skipped")) != total:
            problems.append("%s: summary counts do not add up" % where)
    elif function == "adherence":
        if set(got) != {"taken", "expected", "percent", "has_data"} or not _is_int(got["percent"]):
            problems.append("%s: adherence keys/percent" % where)
        if got["taken"] > got["expected"] or got["has_data"] != (got["expected"] > 0):
            problems.append("%s: adherence inconsistent" % where)
        if got["expected"] and got["percent"] != int((got["taken"] * 100.0 / got["expected"]) + 0.5):
            problems.append("%s: percent rounding" % where)
    elif function == "plan_reminders":
        fires = [e["fire_at_ms"] for e in got["entries"]]
        if fires != sorted(fires):
            problems.append("%s: entries not ascending" % where)
        for e in got["entries"]:
            if e["kind"] not in ("scheduled", "snooze") or e["fire_at_ms"] < inp["now_ms"]:
                problems.append("%s: bad entry" % where)
        if inp.get("budget") is not None and len(got["entries"]) > inp["budget"]:
            problems.append("%s: budget exceeded" % where)
        if (got["next_fire_ms"] is None) != (not got["entries"]):
            problems.append("%s: next_fire_ms mismatch" % where)
        keys = [(e["medication_id"], e["scheduled_at_ms"]) for e in got["entries"]]
        if len(set(keys)) != len(keys):
            problems.append("%s: duplicate reminder identity" % where)
    elif function == "dose_actions":
        if got["ok"]:
            if got["error"] is not None or got["row"] is None:
                problems.append("%s: ok result must carry a row and no error" % where)
            else:
                _check_log_row(got["row"], where, problems)
        elif got["row"] is not None or not isinstance(got["error"], str):
            problems.append("%s: failed result must carry an error code and no row" % where)
    elif function == "lifecycle":
        if got["ok"]:
            open_rows = [r for r in got["schedules"] if r["active_until_ms"] is None]
            if len(open_rows) > 1:
                problems.append("%s: more than one open schedule after lifecycle" % where)
            if got["medication"]["status"] not in M.STATUSES:
                problems.append("%s: bad status" % where)
            _check_new_ids([o.get("id") for o in got["ops"] if o["op"] == "insert_schedule"], where, problems)
    elif function == "auto_complete":
        if got["medication_ids"] != sorted(got["medication_ids"], key=lambda x: [m["id"] for m in inp["medications"]].index(x)):
            problems.append("%s: ids not in input order" % where)
    elif function == "frequency_hint":
        if got["form"] not in M.FORMS or got["dose_unit"] not in M.DOSE_UNITS or got["food_relation"] not in M.FOOD_RELATIONS:
            problems.append("%s: bad enum in hint" % where)
        if got["is_prn"]:
            if got["frequency_kind"] is not None or got["times"] or got["days"]:
                problems.append("%s: PRN hint carries a schedule" % where)
        elif got["frequency_kind"] not in M.FREQUENCY_KINDS:
            problems.append("%s: bad frequency_kind" % where)
        if got["confidence"] not in (0.3, 0.6, 0.9):
            problems.append("%s: confidence must be 0.3/0.6/0.9" % where)
    elif function == "archive":
        if inp["op"] == "export":
            if got["format"] != M.ARCHIVE_FORMAT or got["version"] != M.ARCHIVE_VERSION:
                problems.append("%s: bad archive envelope" % where)
            for key in ("medications", "schedules", "dose_logs"):
                ids = [r["id"] for r in got[key]]
                if ids != sorted(ids):
                    problems.append("%s: %s not ordered by id" % (where, key))
            if any(m["photo_path"] is not None for m in got["medications"]):
                problems.append("%s: photo_path exported" % where)
        else:
            for op in got["ops"]:
                if op["op"] not in ("insert", "update", "skip"):
                    problems.append("%s: bad merge op" % where)
                if op["op"] == "skip" and op.get("reason") not in ("older", "invalid", "orphan", "occurrence_conflict"):
                    problems.append("%s: bad skip reason" % where)
    elif function == "validate_draft":
        for e in got["errors"]:
            if set(e) != {"field", "code"}:
                problems.append("%s: bad error entry" % where)


# ---------------------------------------------------------------------------------------------
# Safety: taken rows only from explicit actions
# ---------------------------------------------------------------------------------------------

def _taken_rows(obj, out):
    if isinstance(obj, dict):
        if obj.get("status") == "taken" and "scheduled_at_ms" in obj and "id" in obj:
            out.append(obj)
        for v in obj.values():
            _taken_rows(v, out)
    elif isinstance(obj, list):
        for v in obj:
            _taken_rows(v, out)


def check_safety(function, inp, got, where, problems):
    if function in ("dose_actions", "today_timeline", "adherence", "resolve_dose_status", "plan_reminders",
                    "expand_occurrences", "validate_draft", "frequency_hint", "auto_complete"):
        return
    produced = []
    _taken_rows(got, produced)
    if function == "archive":
        source = []
        _taken_rows(inp.get("archive"), source)
        _taken_rows(inp.get("snapshot"), source)
        allowed = set((r["id"], r["scheduled_at_ms"]) for r in source)
        for r in produced:
            if (r["id"], r["scheduled_at_ms"]) not in allowed:
                problems.append("%s: merge/export invented a taken row %s" % (where, r["id"]))
        return
    if produced:
        problems.append("%s: %s produced a taken row" % (where, function))


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
                got = json.loads(json.dumps(M.run_case(doc["function"], c["input"]), ensure_ascii=False))
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
            check_safety(doc["function"], c["input"], got, where, problems)
        counts[name] = len(doc["cases"])
        if write and (changed or raw != dumps(doc)):
            with open(path, "w", encoding="utf-8") as fh:
                fh.write(dumps(doc))
        elif not write and raw != dumps(doc):
            problems.append("%s: not in canonical format (run --write)" % name)
    return counts


# ---------------------------------------------------------------------------------------------
# SQL, constants, archive round trip, regex lint
# ---------------------------------------------------------------------------------------------

def check_sql(problems):
    files = [os.path.join(SHARED, "schema.sql")] + sorted(glob.glob(os.path.join(SHARED, "migrations", "*.sql")))
    tables, indexes = [], []
    counts = {}
    for path in files:
        rel = os.path.relpath(path, ROOT)
        with open(path, encoding="utf-8") as fh:
            for n, raw in enumerate(fh.read().split("\n"), 1):
                k = raw.find("--")
                if k >= 0 and raw[:k].count("'") % 2 == 1:
                    problems.append("%s:%d: '--' inside a string literal" % (rel, n))
        try:
            st = statements(path)
        except ValueError as e:
            problems.append(str(e))
            continue
        if not st:
            problems.append("%s: no statements" % rel)
        for s in st:
            head = s.lstrip()
            if not re.match("(CREATE|ALTER|DROP|INSERT|UPDATE|DELETE|PRAGMA) ", head):
                problems.append("%s: unexpected statement start %r" % (rel, s[:40]))
            if s.count("(") != s.count(")"):
                problems.append("%s: unbalanced parentheses in %r" % (rel, s[:40]))
            m = re.match("CREATE TABLE ([a-z_]+)", head)
            if m:
                tables.append(m.group(1))
            m = re.match("CREATE (?:UNIQUE )?INDEX ([a-z_]+)", head)
            if m:
                indexes.append(m.group(1))
        counts[rel] = len(st)
    if tables != TABLES:
        problems.append("schema tables %s != documented %s" % (tables, TABLES))
    if indexes != INDEXES:
        problems.append("schema indexes %s != documented %s" % (indexes, INDEXES))
    return counts


def check_constants(problems):
    for name, value in DOCUMENTED.items():
        if getattr(M, name, None) != value:
            problems.append("reference %s = %r, documented %r" % (name, getattr(M, name, None), value))
    for name in sorted(dir(M)):
        obj = getattr(M, name)
        if isinstance(obj, re.Pattern):
            for issue in lint_pattern(obj.pattern, owned=True):
                problems.append("medications_reference.%s: %s" % (name, issue))


def check_archive_round_trip(problems):
    path = os.path.join(VECTORS, "archive.json")
    if not os.path.exists(path):
        return
    doc = json.load(open(path, encoding="utf-8"))
    for c in doc["cases"]:
        inp = c["input"]
        if inp.get("op") != "export":
            continue
        first = M.export_archive(inp["snapshot"], inp["exported_ms"], inp["time_zone"], inp["platform"], inp["app_version"])
        merged = M.merge_archive({"medications": [], "schedules": [], "dose_logs": []}, first, inp["exported_ms"])
        snap = {"medications": [], "schedules": [], "dose_logs": []}
        table_key = {"medications": "medications", "medication_schedules": "schedules", "dose_logs": "dose_logs"}
        for op in merged["ops"]:
            if op["op"] != "insert":
                problems.append("archive.json/%s: round trip merge produced %s" % (c["name"], op["op"]))
                continue
            snap[table_key[op["table"]]].append(op["row"])
        second = M.export_archive(snap, inp["exported_ms"], inp["time_zone"], inp["platform"], inp["app_version"])
        if dumps(first) != dumps(second):
            problems.append("archive.json/%s: export -> merge -> export is not byte-identical at %s"
                            % (c["name"], _first_diff(first, second)))


# ---------------------------------------------------------------------------------------------
# Hand-computed unit tests
# ---------------------------------------------------------------------------------------------

class ReferenceTests(unittest.TestCase):
    NY = "America/New_York"
    IN = "Asia/Kolkata"

    def test_spring_forward_gap_shifts_forward(self):
        t = M.local_instant("2026-03-08", "02:30", self.NY)
        self.assertEqual(t, 1772955000000)  # 2026-03-08T07:30:00Z = 03:30 EDT
        self.assertEqual(M.local_hhmm_of(t, self.NY), "03:30")

    def test_fall_back_uses_earlier_instant_once(self):
        t = M.local_instant("2026-11-01", "01:30", self.NY)
        self.assertEqual(t, 1793511000000)  # 2026-11-01T05:30:00Z = 01:30 EDT (first occurrence)
        med = {"id": "m", "start_date": "2026-10-01", "end_date": None, "is_prn": 0}
        sch = {"id": "s", "frequency_kind": "daily", "times": ["01:30"], "days": [], "active_from_ms": 0,
               "active_until_ms": None}
        occ = M.expand_occurrences(sch, med, M.local_instant("2026-11-01", "00:00", self.NY),
                                   M.local_instant("2026-11-02", "00:00", self.NY), self.NY)
        self.assertEqual([o["scheduled_at_ms"] for o in occ], [t])

    def test_q8h_anchored_22(self):
        self.assertEqual(M.schedule_slots({"frequency_kind": "interval", "interval_hours": 8, "anchor_time": "22:00"}),
                         ["06:00", "14:00", "22:00"])
        self.assertEqual(M.schedule_slots({"frequency_kind": "interval", "interval_hours": 5, "anchor_time": "08:00"}), [])

    def test_adherence_18_of_21(self):
        occ = [{"medication_id": "m", "schedule_id": "s", "scheduled_at_ms": i * 1000} for i in range(21)]
        logs = [{"id": "l%d" % i, "medication_id": "m", "schedule_id": "s", "scheduled_at_ms": i * 1000,
                 "status": "taken", "taken_at_ms": i * 1000} for i in range(18)]
        self.assertEqual(M.adherence(occ, logs, 10 ** 9), {"taken": 18, "expected": 21, "percent": 86, "has_data": True})
        self.assertEqual(M.adherence([], [], 0)["has_data"], False)

    def test_snooze_precedence_and_budget(self):
        t = M.local_instant("2026-09-10", "08:00", self.IN)
        now = t - 120_000
        med = {"id": "m", "name": "Metformin", "status": "active", "is_prn": 0, "start_date": "2026-09-01",
               "end_date": None}
        sch = {"id": "s", "medication_id": "m", "frequency_kind": "daily", "times": ["08:00"], "days": [],
               "reminder_enabled": 1, "active_from_ms": 0, "active_until_ms": None}
        log = {"id": "l", "medication_id": "m", "schedule_id": "s", "scheduled_at_ms": t, "status": "snoozed",
               "snoozed_until_ms": t + 600_000}
        plan = M.plan_reminders([med], [sch], [log], now, 86_400_000, self.IN, 52)
        self.assertEqual([e["kind"] for e in plan["entries"]], ["snooze"])
        self.assertEqual(plan["next_fire_ms"], t + 600_000)
        many = dict(sch, times=["%02d:00" % h for h in range(12)])
        plan = M.plan_reminders([med], [many], [], now, 7 * 86_400_000, self.IN, 52)
        self.assertEqual(len(plan["entries"]), 52)
        self.assertTrue(plan["truncated"])

    def test_frequency_hint_1_0_1(self):
        h = M.frequency_hint({"name": "Dolo 650", "strength": "650 mg", "form": "tablet", "dose": None,
                              "frequency": "1-0-1", "duration": "x 5 days", "instructions": "after food"})
        self.assertEqual(h["times"], ["08:00", "20:00"])
        self.assertEqual((h["frequency_kind"], h["dose_quantity"], h["dose_unit"], h["duration_days"],
                          h["food_relation"], h["confidence"]), ("daily", 1, "tablet", 5, "after", 0.9))

    def test_missed_boundary(self):
        t = 1_000_000_000
        occ = {"medication_id": "m", "schedule_id": "s", "scheduled_at_ms": t}
        self.assertEqual(M.resolve_dose_status(occ, None, t + M.GRACE_MS - 1)["status"], "due")
        self.assertEqual(M.resolve_dose_status(occ, None, t + M.GRACE_MS)["status"], "missed")
        rows = M.materialize_missed([occ], [], {"m": {"dose_quantity": 1, "dose_unit": "tablet"}}, t + M.GRACE_MS)["ops"]
        self.assertEqual([r["row"]["status"] for r in rows], ["missed"])

    def test_never_taken_without_action(self):
        med = {"id": "m", "status": "active", "is_prn": 0, "dose_quantity": 1, "dose_unit": "tablet"}
        occ = {"medication_id": "m", "schedule_id": "s", "scheduled_at_ms": 0}
        out = M.materialize_missed([occ], [], {"m": med}, 10 ** 9)
        self.assertTrue(all(o["row"]["status"] != "taken" for o in out["ops"]))
        r = M.apply_dose_action("taken", occ, None, med, 10, 10)
        self.assertEqual(r["row"]["status"], "taken")


def main(argv):
    write = "--write" in argv
    problems = []
    sql_counts = check_sql(problems)
    check_constants(problems)
    counts = check_vectors(write, problems)
    check_archive_round_trip(problems)
    suite = unittest.defaultTestLoader.loadTestsFromTestCase(ReferenceTests)
    result = unittest.TextTestRunner(stream=open(os.devnull, "w"), verbosity=0).run(suite)
    for failed, trace in result.failures + result.errors:
        problems.append("unittest %s failed:\n%s" % (failed.id(), trace.strip().splitlines()[-1]))
    for rel, n in sorted(sql_counts.items()):
        print("%-45s %3d statements" % (rel, n))
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
