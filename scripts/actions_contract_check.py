#!/usr/bin/env python3
"""Ayuvo Actions contract check (Python 3 stdlib only).

    python3 scripts/actions_contract_check.py           # verify; exit 1 on any problem
    python3 scripts/actions_contract_check.py --write   # rewrite vectors' "expected", the platform copies and the
                                                        # generated block of docs/actions.md

Checks:
  1. shared/actions/action_catalog.json lint: enums, units, unique ids, param specs, defaults, unit/range links,
     coach tools, surfaces, screens, and the safety rules (no delete actions, medication writes limited to
     medication.dose.mark with confirmation, Coach never proposes medication or goal changes).
  2. shared/actions/test-vectors/*.json: envelope, unique names, canonical formatting and every "expected" equals
     scripts/actions_reference.py on its "input". Coverage: every action has an ok validate case and every error
     code, date range, compute op and link error appears at least once.
  3. Platform copies of the catalog are byte-identical to shared/ (Android assets, iOS bundle resource).
  4. docs/actions.md generated catalog block is up to date.
  5. A small unittest suite with hand-computed expectations.
"""

import glob
import json
import os
import re
import sys
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
SHARED = os.path.join(ROOT, "shared", "actions")
VECTORS = os.path.join(SHARED, "test-vectors")
CATALOG = os.path.join(SHARED, "action_catalog.json")
DOC = os.path.join(ROOT, "docs", "actions.md")
COPIES = [
    os.path.join(ROOT, "android", "app", "src", "main", "assets", "actions", "action_catalog.json"),
    os.path.join(ROOT, "ios", "calorietracker", "Actions", "Resources", "action_catalog.json"),
]
BEGIN = "<!-- BEGIN GENERATED CATALOG (scripts/actions_contract_check.py --write) -->"
END = "<!-- END GENERATED CATALOG -->"
sys.path.insert(0, HERE)
import actions_reference as A  # noqa: E402

FORMAT = "ayuvo-actions-vectors"
EXPECTED_FILES = {"validation.json": "validate", "date_ranges.json": "resolve_date_range",
                  "deeplinks.json": "deeplink", "compute.json": "compute"}
ID_RE = re.compile(r"^[a-z]+(\.[a-z][A-Za-z]*)+$")
TOOL_RE = re.compile(r"^[a-z][a-z_]*$")
ALLOWED_ZONES = frozenset(["America/New_York", "Europe/London", "Asia/Kolkata", "UTC"])
SCREEN_PREFIXES = ("metric:", "screen:", "tab:", "section:", "record:")


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
# Catalog lint
# ---------------------------------------------------------------------------------------------

def lint_catalog(cat, problems):
    if cat.get("format") != "ayuvo-actions-catalog" or cat.get("version") != 1:
        problems.append("catalog: bad format/version")
    enums = cat["enums"]
    for name, values in enums.items():
        if not values or len(set(values)) != len(values):
            problems.append("enum %s: empty or duplicate values" % name)
    for fam, u in cat["units"].items():
        if sorted(u["factors"]) != sorted(enums.get(u["enum"], [])):
            problems.append("units.%s: factors do not match enum %s" % (fam, u["enum"]))
        if u["factors"].get(u["canonical"]) != 1:
            problems.append("units.%s: canonical factor must be 1" % fam)
    ids, tools = set(), set()
    for a in cat["actions"]:
        aid = a.get("id", "?")
        w = "action %s" % aid
        if not ID_RE.match(aid) or aid in ids:
            problems.append("%s: bad or duplicate id" % w)
        ids.add(aid)
        if a["kind"] not in A.KINDS or a["confirmation"] not in A.CONFIRMATIONS or a["coach_mode"] not in A.COACH_MODES:
            problems.append("%s: bad kind/confirmation/coach_mode" % w)
        if set(a["surfaces"]) - set(A.SURFACES) or set(a["permissions"]) - set(A.PERMISSIONS):
            problems.append("%s: unknown surface or permission" % w)
        if a["output"]["kind"] not in A.OUTPUT_KINDS:
            problems.append("%s: bad output kind" % w)
        if a["output"].get("entity") and a["output"]["entity"] not in cat["entities"]:
            problems.append("%s: unknown output entity" % w)
        for key in ("title", "summary", "screen", "domain"):
            if not isinstance(a.get(key), str) or not a[key]:
                problems.append("%s: missing %s" % (w, key))
        if not a["screen"].startswith(SCREEN_PREFIXES):
            problems.append("%s: bad screen %r" % (w, a["screen"]))
        if set(a["examples"]) != {"siri", "shortcuts", "android"}:
            problems.append("%s: examples must have siri/shortcuts/android" % w)
        if "siri" in a["surfaces"] and not a["examples"]["siri"]:
            problems.append("%s: siri surface without a Siri example" % w)
        names = [p["name"] for p in a["params"]]
        if len(set(names)) != len(names):
            problems.append("%s: duplicate param names" % w)
        for p in a["params"]:
            pw = "%s.%s" % (w, p["name"])
            if p["type"] not in A.PARAM_TYPES:
                problems.append("%s: bad type" % pw)
            if p["type"] == "enum" and p.get("enum") not in enums:
                problems.append("%s: unknown enum" % pw)
            if p["type"] == "entity" and p.get("entity") not in cat["entities"]:
                problems.append("%s: unknown entity" % pw)
            if "default" in p:
                if p.get("required"):
                    problems.append("%s: required param with a default" % pw)
                _, err = A._coerce(cat, p, p["default"])
                if err:
                    problems.append("%s: default fails validation (%s)" % (pw, err))
            if "default_pref" in p and (p["type"] != "enum" or p["default_pref"] != p["enum"]):
                problems.append("%s: default_pref must name the param's own enum" % pw)
            if "unit_param" in p:
                up = next((q for q in a["params"] if q["name"] == p["unit_param"]), None)
                fam = cat["units"].get(p.get("unit_family"))
                if up is None or fam is None or up.get("enum") != fam["enum"]:
                    problems.append("%s: unit_param/unit_family mismatch" % pw)
            if "range_by" in p:
                by = next((q for q in a["params"] if q["name"] == p["range_by"]["param"]), None)
                if by is None or not by.get("required") or sorted(p["range_by"]["ranges"]) != sorted(enums[by["enum"]]):
                    problems.append("%s: range_by must cover every value of a required enum param" % pw)
        for g in a.get("requires_one_of", []):
            if set(g) - set(names):
                problems.append("%s: requires_one_of names unknown params" % w)
        if set(a.get("ai_unless", [])) - set(names):
            problems.append("%s: ai_unless names unknown params" % w)
        for ph in re.findall(r"\{([a-z_]+)\}", a["screen"]):
            if ph not in names:
                problems.append("%s: screen placeholder {%s} is not a param" % (w, ph))
        # Coach
        if (a["coach_mode"] != "none") != ("coach" in a["surfaces"]):
            problems.append("%s: coach surface must match coach_mode" % w)
        if a["coach_mode"] == "read":
            if a["kind"] not in ("get", "search") or not TOOL_RE.match(a.get("coach_tool", "")):
                problems.append("%s: read coach action needs a get/search kind and a coach_tool" % w)
            elif a["coach_tool"] in tools:
                problems.append("%s: duplicate coach_tool" % w)
            tools.add(a.get("coach_tool"))
        elif "coach_tool" in a:
            problems.append("%s: coach_tool only on read actions" % w)
        if a["coach_mode"] == "propose" and a["kind"] != "set":
            problems.append("%s: only set actions can be proposed" % w)
        if a["kind"] == "open" and not a.get("opens_app"):
            problems.append("%s: open actions must set opens_app" % w)
        # Safety
        if "delete" in aid.lower() or "remove" in aid.lower():
            problems.append("%s: delete actions are not allowed" % w)
        if a["kind"] == "set" and a["domain"] == "medications":
            if aid != "medication.dose.mark" or a["confirmation"] != "always" or a["coach_mode"] != "none":
                problems.append("%s: medication writes are limited to confirmed dose marking" % w)
            if set(names) != {"dose", "action", "snooze_minutes"}:
                problems.append("%s: medication writes may not take dose/strength params" % w)
        if a["kind"] == "set" and a["domain"] == "goals" and (a["confirmation"] != "always" or a["coach_mode"] != "none"):
            problems.append("%s: goal changes must be confirmed and never proposed by Coach" % w)
    return tools


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


def check_vectors(cat, write, problems):
    counts = {}
    seen_ok_ids, seen_errors, seen_presets, seen_ops, seen_link = set(), set(), set(), set(), set()
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
                got = json.loads(json.dumps(A.run_case(doc["function"], c["input"], cat)))
            except Exception as e:  # noqa: BLE001
                problems.append("%s: reference raised %s: %s" % (where, type(e).__name__, e))
                continue
            if write:
                if c.get("expected") != got:
                    c["expected"], changed = got, True
            elif c.get("expected") != got:
                problems.append("%s: expected differs at %s" % (where, _first_diff(c.get("expected"), got)))
            f = doc["function"]
            if f == "validate":
                if got["ok"]:
                    seen_ok_ids.add(c["input"]["id"])
                else:
                    seen_errors.add(got["error"]["code"])
            elif f == "resolve_date_range":
                seen_presets.add(c["input"]["preset"])
                if got["from_ms"] >= got["to_ms"]:
                    problems.append("%s: empty range" % where)
            elif f == "compute":
                seen_ops.add(c["input"]["op"])
            elif f == "deeplink":
                if got["parse"]["ok"]:
                    seen_link.add("ok")
                    if got["validate"] and not got["validate"]["ok"]:
                        seen_errors.add(got["validate"]["error"]["code"])
                    action = A.find_action(cat, got["parse"]["request"]["id"])
                    if (got["validate"] and got["validate"]["ok"] and action["kind"] == "set"
                            and not action.get("opens_app") and not got["validate"]["confirm"]):
                        problems.append("%s: a deep-link write must require confirmation" % where)
                else:
                    seen_link.add(got["parse"]["error"]["code"])
        counts[name] = len(doc["cases"])
        if write and (changed or raw != dumps(doc)):
            with open(path, "w", encoding="utf-8") as fh:
                fh.write(dumps(doc))
        elif not write and raw != dumps(doc):
            problems.append("%s: not in canonical format (run --write)" % name)
    for a in cat["actions"]:
        if a["id"] not in seen_ok_ids:
            problems.append("coverage: no ok validate case for %s" % a["id"])
    for code in A.ERROR_CODES:
        if code not in seen_errors:
            problems.append("coverage: no case produces error %s" % code)
    for p in cat["enums"]["date_range"]:
        if p not in seen_presets:
            problems.append("coverage: no date range case for %s" % p)
    for op in A.COMPUTE_OPS:
        if op not in seen_ops:
            problems.append("coverage: no compute case for %s" % op)
    for code in ("ok",) + A.LINK_ERRORS:
        if code not in seen_link:
            problems.append("coverage: no deeplink case for %s" % code)
    return counts


# ---------------------------------------------------------------------------------------------
# Copies and docs
# ---------------------------------------------------------------------------------------------

def check_copies(write, problems):
    source = open(CATALOG, encoding="utf-8").read()
    for path in COPIES:
        rel = os.path.relpath(path, ROOT)
        current = open(path, encoding="utf-8").read() if os.path.exists(path) else None
        if current == source:
            continue
        if write:
            os.makedirs(os.path.dirname(path), exist_ok=True)
            with open(path, "w", encoding="utf-8") as fh:
                fh.write(source)
        else:
            problems.append("%s differs from shared/actions/action_catalog.json (run --write)" % rel)


def _param_line(cat, p):
    t = p["type"]
    if t == "enum":
        t = "%s (%s)" % (p["enum"], ", ".join(cat["enums"][p["enum"]]))
    elif t == "entity":
        t = "entity: %s" % p["entity"]
    bits = [t, "required" if p.get("required") else "optional"]
    if "default" in p:
        bits.append("default %s" % json.dumps(p["default"]))
    if "default_pref" in p:
        bits.append("default: your %s preference" % p["default_pref"].replace("_", " "))
    if "min" in p or "max" in p:
        bits.append("range %s–%s%s" % (p.get("min", ""), p.get("max", ""),
                                       " " + cat["units"][p["unit_family"]]["canonical"] if "unit_family" in p else ""))
    if "allowed" in p:
        bits.append("one of %s" % ", ".join(str(v) for v in p["allowed"]))
    if "range_by" in p:
        bits.append("range by %s: %s" % (p["range_by"]["param"], ", ".join(
            "%s %s–%s" % (k, v[0], v[1]) for k, v in sorted(p["range_by"]["ranges"].items()))))
    return "| `%s` | %s | %s |" % (p["name"], "; ".join(bits), p["summary"])


def render_catalog(cat):
    out = [BEGIN, "", "### Index", "", "| Action | Kind | Title | Confirmation | Coach |", "|---|---|---|---|---|"]
    for a in cat["actions"]:
        coach = a["coach_mode"] if a["coach_mode"] != "read" else "read (`%s`)" % a["coach_tool"]
        out.append("| [`%s`](#%s) | %s | %s | %s | %s |" % (a["id"], a["id"].replace(".", "").lower(), a["kind"].upper(),
                                                        a["title"], a["confirmation"], coach))
    for domain in sorted({a["domain"] for a in cat["actions"]}):
        out += ["", "### Domain: %s" % domain]
        for a in (x for x in cat["actions"] if x["domain"] == domain):
            out += ["", "#### `%s`" % a["id"], "", "**%s** · %s. %s" % (a["title"], a["kind"].upper(), a["summary"]), ""]
            if a["params"]:
                out += ["| Parameter | Type | Meaning |", "|---|---|---|"]
                out += [_param_line(cat, p) for p in a["params"]]
                out.append("")
            if a.get("requires_one_of"):
                out.append("- Needs one of: %s" % " or ".join("(%s)" % ", ".join(g) for g in a["requires_one_of"]))
            out.append("- Output: %s%s — %s" % (a["output"]["kind"],
                                              " of `%s`" % a["output"]["entity"] if a["output"].get("entity") else "",
                                              ", ".join("`%s`" % f for f in a["output"]["fields"]) or "none"))
            out.append("- Permissions: %s" % (", ".join(a["permissions"]) or "none"))
            conf = {"never": "none", "always": "always asks first",
                    "when_ai": "asks first when the food is estimated by your AI provider"}[a["confirmation"]]
            out.append("- Confirmation: %s%s" % (conf, "; deep links and Coach proposals always ask first" if a["kind"] == "set" and not a.get("opens_app") else ""))
            out.append("- Requires unlocked device: %s · Opens the app: %s" % ("yes" if a["requires_unlock"] else "no",
                                                                           "yes" if a.get("opens_app") else "no"))
            out.append("- Surfaces: %s · Screen: `%s`" % (", ".join(a["surfaces"]), a["screen"]))
            for key, label in (("siri", "Siri"), ("shortcuts", "Shortcuts"), ("android", "Android")):
                for ex in a["examples"][key]:
                    out.append("- %s: %s" % (label, ex if key != "android" or not ex.startswith("ayuvo://") else "`%s`" % ex))
    out += ["", END]
    return "\n".join(out)


def check_doc(cat, write, problems):
    if not os.path.exists(DOC):
        problems.append("docs/actions.md missing")
        return
    text = open(DOC, encoding="utf-8").read()
    i, j = text.find(BEGIN), text.find(END)
    if i < 0 or j < 0:
        problems.append("docs/actions.md: generated block markers missing")
        return
    new = text[:i] + render_catalog(cat) + text[j + len(END):]
    if new != text:
        if write:
            with open(DOC, "w", encoding="utf-8") as fh:
                fh.write(new)
        else:
            problems.append("docs/actions.md generated block is stale (run --write)")


# ---------------------------------------------------------------------------------------------
# Hand-computed unit tests
# ---------------------------------------------------------------------------------------------

class ReferenceTests(unittest.TestCase):
    cat = A.load_catalog()

    def test_water_in_floz_range_checked_in_ml(self):
        ok = A.validate(self.cat, "water.log", {"amount": "169", "unit": "floz"}, {"source": "siri"})
        self.assertTrue(ok["ok"])  # 169 fl oz = 4997.9 ml
        bad = A.validate(self.cat, "water.log", {"amount": "170", "unit": "floz"}, {"source": "siri"})
        self.assertEqual(bad["error"], {"code": "out_of_range", "param": "amount"})

    def test_unit_preference_default(self):
        r = A.validate(self.cat, "weight.log", {"value": 165}, {"source": "siri", "prefs": {"mass_unit": "lb"}})
        self.assertEqual(r["params"], {"value": 165.0, "unit": "lb"})

    def test_food_log_confirms_only_for_ai(self):
        ai = A.validate(self.cat, "nutrition.food.log", {"description": "two eggs"}, {"source": "siri"})
        exact = A.validate(self.cat, "nutrition.food.log", {"name": "Egg", "calories": 78}, {"source": "siri"})
        self.assertEqual((ai["confirm"], ai["ai"], exact["confirm"], exact["ai"]), (True, True, False, False))

    def test_medication_marks_always_confirm_and_coach_cannot(self):
        r = A.validate(self.cat, "medication.dose.mark", {"dose": "d1", "action": "taken"}, {"source": "shortcuts"})
        self.assertTrue(r["confirm"])
        self.assertEqual(A.validate(self.cat, "medication.dose.mark", {"dose": "d1", "action": "taken"},
                                    {"source": "coach"})["error"]["code"], "not_allowed")

    def test_week_ranges_monday_vs_sunday(self):
        now = 1789574400000  # 2026-09-16T16:00Z, a Wednesday
        mon = A.resolve_date_range("this_week", now, "UTC", "monday")
        sun = A.resolve_date_range("this_week", now, "UTC", "sunday")
        self.assertEqual(mon["from_ms"] - sun["from_ms"], 86_400_000)

    def test_bmi(self):
        self.assertEqual(A.bmi(70, 175)["bmi"], 22.9)

    def test_deeplink_plus_and_percent(self):
        r = A.parse_deeplink("ayuvo://action/search.universal?query=vitamin+d%20k2")
        self.assertEqual(r["request"]["params"], {"query": "vitamin d k2"})
        self.assertEqual(A.parse_deeplink("ayuvo://log/water")["error"]["code"], "not_action_link")


def main(argv):
    write = "--write" in argv
    problems = []
    cat = A.load_catalog()
    tools = lint_catalog(cat, problems)
    counts = check_vectors(cat, write, problems)
    check_copies(write, problems)
    check_doc(cat, write, problems)
    suite = unittest.defaultTestLoader.loadTestsFromTestCase(ReferenceTests)
    result = unittest.TextTestRunner(stream=open(os.devnull, "w"), verbosity=0).run(suite)
    for failed, trace in result.failures + result.errors:
        problems.append("unittest %s failed:\n%s" % (failed.id(), trace.strip().splitlines()[-1]))
    print("%d actions, %d coach tools" % (len(cat["actions"]), len(tools)))
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
