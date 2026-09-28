#!/usr/bin/env python3
"""Ayuvo Nutrients contract check (Python 3 stdlib only).

    python3 scripts/nutrients_contract_check.py           # verify; exit 1 on any problem
    python3 scripts/nutrients_contract_check.py --write   # rewrite vectors' "expected", the platform copies and the
                                                          # generated block of docs/nutrients.md

Checks:
  1. shared/nutrients/nutrient_reference.json lint: envelope, age bands, the exact key set (app_tracked: true = the
     app's 21 detailed OptionalNutrient keys + monounsaturated_fat + polyunsaturated_fat; app_tracked: false = the
     14 registry nutrition types the food log does not track), health_type ids that exist in
     shared/health/metric_registry.json with category nutrition and the same unit, every registry dietary_* type
     either mapped by exactly one nutrient or listed as a macro (MACRO_HEALTH_TYPES), slug/unit/category/style on every
     nutrient, style consistency (target -> recommended, limit -> limit, info -> no lines), complete sex x band
     tables, upper-limit scopes and notes, IU and mass-form blocks, source ids that exist (and every source used),
     canonical units equal to the app's OptionalNutrient units (iOS model parsed) and the sports supplement list.
  2. shared/nutrients/ai_supplement_label.md: the four fenced blocks load, placeholders appear once, and both key
     lists equal ALL reference keys (app_tracked or not) + sports keys in reference order (R.SUPPLEMENT_KEYS).
  3. shared/nutrients/test-vectors/*.json: exact file set, envelope, unique names, allowed zones, canonical
     formatting and every "expected" equals scripts/nutrients_reference.py on its "input"; coverage of every error
     code, style, label, scope and band.
  4. Portability lint of the reference (no built-in round(), statistics, fsum or isoformat).
  5. Platform copies of the reference and prompt are byte-identical (iOS bundle resource, Android asset).
  6. docs/nutrients.md generated table block is up to date.
  7. A small unittest suite with hand-computed expectations.
"""

import glob
import json
import os
import re
import sys
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
SHARED = os.path.join(ROOT, "shared", "nutrients")
VECTORS = os.path.join(SHARED, "test-vectors")
REFERENCE = os.path.join(SHARED, "nutrient_reference.json")
PROMPT = os.path.join(SHARED, "ai_supplement_label.md")
DOC = os.path.join(ROOT, "docs", "nutrients.md")
IOS_MODEL = os.path.join(ROOT, "ios", "calorietracker", "Models", "OptionalNutrientGoals.swift")
REGISTRY = os.path.join(ROOT, "shared", "health", "metric_registry.json")
COPY_DIRS = [
    os.path.join(ROOT, "ios", "calorietracker", "Nutrients", "Resources"),
    os.path.join(ROOT, "android", "app", "src", "main", "assets", "nutrients"),
]
BEGIN = "<!-- BEGIN GENERATED NUTRIENTS -->"
END = "<!-- END GENERATED NUTRIENTS -->"
sys.path.insert(0, HERE)
import nutrients_reference as R  # noqa: E402

FORMAT = "ayuvo-nutrients-vectors"
EXPECTED_FILES = {"reference_lines.json": "reference_lines", "default_goal.json": "default_goal",
                  "iu_conversion.json": "convert_amount", "supplement_entries.json": "supplement_entries",
                  "day_totals.json": "day_totals", "logged_day_average.json": "logged_day_average",
                  "label_output.json": "parse_label_output"}
ALLOWED_ZONES = frozenset(["America/New_York", "Europe/London", "Asia/Kolkata", "UTC"])
BANDS = ["19-30", "31-50", "51-70", "71+"]
CATEGORIES = ("carbs", "fats", "minerals", "vitamins", "other")
EXTRA_KEYS = ("monounsaturated_fat", "polyunsaturated_fat")
# app_tracked: false nutrients: registry nutrition types the food log does not record. They are still supplement
# nutrients (medication_nutrients, AI label, `nutrient:<key>` chart counting supplements only).
UNTRACKED_KEYS = ["phosphorus", "chloride", "copper", "manganese", "selenium", "chromium", "molybdenum", "iodine",
                  "vitamin_b6", "thiamin", "riboflavin", "niacin", "biotin", "pantothenic_acid"]
# Registry dietary_* types that are not reference nutrients: they resolve to the app's macro goals
# (shared/metrics/metric_catalog.json health overrides -> profile.*).
MACRO_HEALTH_TYPES = ("dietary_energy", "dietary_protein", "dietary_carbohydrates", "dietary_fat_total")
SPORTS_KEYS = ["creatine", "beta_alanine", "l_citrulline", "l_carnitine", "l_arginine", "taurine", "betaine", "hmb"]
NUTRIENT_FIELDS = {"key", "slug", "name", "unit", "category", "style", "summary", "recommended", "upper_limit", "limit",
                   "iu", "mass_forms", "notes", "source_ids", "app_tracked", "health_type"}
FORBIDDEN_COPY = ("never leaves", "leaves your device", "stays on your device", "cure", "prevents", "guarantee")
DATE_RE = re.compile("^[0-9]{4}-[0-9]{2}-[0-9]{2}$")
KEY_RE = re.compile("^[a-z][a-z0-9_]*$")


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


def _strings(obj, out):
    if isinstance(obj, str):
        out.append(obj)
    elif isinstance(obj, dict):
        for v in obj.values():
            _strings(v, out)
    elif isinstance(obj, list):
        for v in obj:
            _strings(v, out)


# ---------------------------------------------------------------------------------------------
# App model (iOS OptionalNutrient) parsing
# ---------------------------------------------------------------------------------------------

def app_units():
    """{jsonKey: unit} parsed from OptionalNutrientGoals.swift (jsonKey switch + unit switch, default g)."""
    src = open(IOS_MODEL, encoding="utf-8").read()
    json_block = src[src.index("var jsonKey: String"):src.index("init?(jsonKey: String)")]
    case_to_key = dict(re.findall("case [.]([A-Za-z0-9]+): \"([a-z0-9_]+)\"", json_block))
    unit_block = src[src.index("var unit: String"):src.index("var defaultGoal: Int")]
    units = {}
    for cases, unit in re.findall("case ([.A-Za-z0-9, ]+): \"([a-z]+)\"", unit_block):
        for c in cases.split(","):
            units[c.strip()[1:]] = unit
    default = re.search("default: \"([a-z]+)\"", unit_block).group(1)
    return dict((k, units.get(c, default)) for c, k in case_to_key.items())


# ---------------------------------------------------------------------------------------------
# Reference lint
# ---------------------------------------------------------------------------------------------

def _check_table(table, where, problems):
    if not isinstance(table, dict) or set(k for k in table if k in ("male", "female")) != {"male", "female"}:
        problems.append("%s: needs male and female" % where)
        return
    for sex in ("male", "female"):
        t = table[sex]
        if not isinstance(t, dict) or list(sorted(t)) != sorted(BANDS):
            problems.append("%s.%s: bands must be exactly %s" % (where, sex, BANDS))
            continue
        for b in BANDS:
            v = t[b]
            if isinstance(v, bool) or not isinstance(v, (int, float)) or v <= 0:
                problems.append("%s.%s.%s: must be a number > 0" % (where, sex, b))


def lint_reference(ref, problems):
    if ref.get("format") != "ayuvo-nutrient-reference" or ref.get("version") != 1:
        problems.append("reference: bad format/version")
    if ref.get("population") != "adults 19+, not pregnant or lactating" or not ref.get("population_note"):
        problems.append("reference: population must say adults 19+, not pregnant or lactating")
    if [b["id"] for b in ref["age_bands"]] != BANDS or ref["default_band"] not in BANDS:
        problems.append("reference: age_bands must be %s" % BANDS)
    prev = 18
    for b in ref["age_bands"]:
        if b["min"] != prev + 1 or (b["max"] is not None and b["max"] < b["min"]):
            problems.append("reference: age band %s not contiguous" % b["id"])
        prev = b["max"] if b["max"] is not None else prev
    if ref["age_bands"][-1]["max"] is not None:
        problems.append("reference: last band must be open-ended")
    if ref["units"] != ["g", "mg", "mcg"] or sorted(ref["amount_per_unit_max"]) != sorted(ref["units"]):
        problems.append("reference: units must be g, mg, mcg with a max for each")
    for alias, target in ref["unit_aliases"].items():
        if target not in ref["units"] or alias != alias.lower():
            problems.append("reference: bad unit alias %r" % alias)
    if [s["key"] for s in ref["sports_supplements"]] != SPORTS_KEYS:
        problems.append("reference: sports_supplements must be %s" % SPORTS_KEYS)
    sources = ref["sources"]
    for sid, s in sources.items():
        if not KEY_RE.match(sid) or not s.get("title") or not s.get("publisher") or not s.get("url", "").startswith("https://"):
            problems.append("source %s: needs id, title, publisher and https url" % sid)
        if not DATE_RE.match(s.get("checked", "")):
            problems.append("source %s: checked must be yyyy-MM-dd" % sid)
        if "page_updated" in s and not DATE_RE.match(s["page_updated"]):
            problems.append("source %s: page_updated must be yyyy-MM-dd" % sid)
    used = set()
    units = app_units()
    want = [k for k in units if k not in SPORTS_KEYS] + list(EXTRA_KEYS)
    keys = [n.get("key") for n in ref["nutrients"]]
    tracked = [n.get("key") for n in ref["nutrients"] if n.get("app_tracked") is True]
    untracked = [n.get("key") for n in ref["nutrients"] if n.get("app_tracked") is False]
    if len(set(keys)) != len(keys) or len(tracked) + len(untracked) != len(keys):
        problems.append("reference: keys must be unique and every nutrient needs app_tracked true or false")
    if sorted(tracked) != sorted(want):
        problems.append("reference: app_tracked keys %s != app keys + mono/poly %s" % (sorted(tracked), sorted(want)))
    if sorted(untracked) != sorted(UNTRACKED_KEYS):
        problems.append("reference: app_tracked: false keys %s != %s" % (sorted(untracked), sorted(UNTRACKED_KEYS)))
    lint_health_types(ref, problems)
    for sk in SPORTS_KEYS:
        if units.get(sk) != "g" or dict((s["key"], s["unit"]) for s in ref["sports_supplements"]).get(sk) != units.get(sk):
            problems.append("sports %s: unit must match the app (g)" % sk)
    slugs = set()
    for n in ref["nutrients"]:
        w = "nutrient %s" % n.get("key")
        if set(n) != NUTRIENT_FIELDS:
            problems.append("%s: fields %s" % (w, sorted(set(n) ^ NUTRIENT_FIELDS)))
            continue
        if n["slug"] != n["key"].replace("_", "-") or n["slug"] in slugs:
            problems.append("%s: slug must be the key with '-' and unique" % w)
        slugs.add(n["slug"])
        expect_unit = units.get(n["key"], "g") if n["app_tracked"] else registry_units().get(n["health_type"])
        if n["unit"] not in ref["units"] or n["unit"] != expect_unit:
            problems.append("%s: unit %s != %s unit %s" % (w, n["unit"], "app" if n["app_tracked"] else "registry", expect_unit))
        if n["category"] not in CATEGORIES or n["style"] not in R.STYLES:
            problems.append("%s: bad category/style" % w)
        if not n["name"] or not (10 <= len(n["summary"]) <= 160) or not n["summary"].endswith("."):
            problems.append("%s: needs a name and a one-sentence summary (10-160 chars, ends with '.')" % w)
        if not isinstance(n["notes"], list) or any(not isinstance(x, str) or not x.endswith(".") for x in n["notes"]):
            problems.append("%s: notes must be sentences" % w)
        st = n["style"]
        if st == "target" and (n["recommended"] is None or n["limit"] is not None):
            problems.append("%s: target style needs recommended and no limit" % w)
        if st == "limit" and (n["limit"] is None or n["recommended"] is not None):
            problems.append("%s: limit style needs a limit and no recommended" % w)
        if st == "info" and (n["recommended"] is not None or n["limit"] is not None or n["upper_limit"] is not None):
            problems.append("%s: info style has no lines" % w)
        if n["recommended"] is not None:
            if n["recommended"].get("kind") not in ("RDA", "AI") or set(n["recommended"]) != {"kind", "male", "female"}:
                problems.append("%s: recommended needs kind RDA|AI, male, female" % w)
            _check_table(n["recommended"], w + ".recommended", problems)
        u = n["upper_limit"]
        if u is not None:
            if set(u) != {"male", "female", "scope", "note"} or u["scope"] not in R.UL_SCOPES or not u["note"]:
                problems.append("%s: upper_limit needs male, female, scope and note" % w)
            _check_table(u, w + ".upper_limit", problems)
            if n["recommended"] is not None and u.get("scope") == "all_sources":
                for sex in ("male", "female"):
                    for b in BANDS:
                        if u[sex][b] < n["recommended"][sex][b]:
                            problems.append("%s: all-sources UL below recommended (%s %s)" % (w, sex, b))
        lim = n["limit"]
        if lim is not None:
            if lim.get("kind") == "fixed":
                ok = set(lim) == {"kind", "value", "basis"} and lim["value"] > 0
            elif lim.get("kind") == "pct_energy":
                ok = set(lim) == {"kind", "pct", "kcal_per_unit", "basis"} and 0 < lim["pct"] < 100 and lim["kcal_per_unit"] > 0
            else:
                ok = False
            if not ok or not lim.get("basis"):
                problems.append("%s: bad limit %r" % (w, lim))
        iu = n["iu"]
        if iu is not None:
            if iu.get("unit") != n["unit"] or (("mcg_per_iu" in iu) == ("forms" in iu)):
                problems.append("%s: iu needs unit = canonical and exactly one of mcg_per_iu / forms" % w)
            vals = list(iu.get("forms", {}).values()) + ([iu["mcg_per_iu"]] if "mcg_per_iu" in iu else [])
            if any(not isinstance(v, (int, float)) or v <= 0 for v in vals):
                problems.append("%s: iu factors must be > 0" % w)
        mf = n["mass_forms"]
        if mf is not None and (not mf or any(not KEY_RE.match(k) or v <= 0 for k, v in mf.items())):
            problems.append("%s: bad mass_forms" % w)
        if not n["source_ids"]:
            problems.append("%s: no sources" % w)
        for sid in n["source_ids"]:
            if sid not in sources:
                problems.append("%s: unknown source %s" % (w, sid))
            used.add(sid)
    for sid in sources:
        if sid not in used:
            problems.append("source %s is not used by any nutrient" % sid)
    strings = []
    _strings(ref, strings)
    strings.append(open(PROMPT, encoding="utf-8").read())
    for s in strings:
        low = s.lower()
        for bad in FORBIDDEN_COPY:
            if bad in low:
                problems.append("copy: forbidden phrase %r in %r" % (bad, s[:60]))


def registry_units():
    """{health type id: unit} for the registry's nutrition category."""
    reg = json.load(open(REGISTRY, encoding="utf-8"))
    return dict((m["id"], m["unit"]) for m in reg["metrics"] if m["category"] == "nutrition")


def lint_health_types(ref, problems):
    """health_type: a registry id with category nutrition and the nutrient's unit (no unit conversion is modelled: a
    differing unit is flagged, never converted), used once; every registry dietary_* type is mapped by one nutrient
    or is a macro type."""
    reg = json.load(open(REGISTRY, encoding="utf-8"))
    by_id = dict((m["id"], m) for m in reg["metrics"])
    seen = {}
    for n in ref["nutrients"]:
        ht = n.get("health_type")
        if ht is None:
            continue
        m = by_id.get(ht)
        if m is None:
            problems.append("nutrient %s: health_type %s is not in metric_registry.json" % (n["key"], ht))
            continue
        if m["category"] != "nutrition" or m["aggregation"] != "SUM":
            problems.append("nutrient %s: health_type %s must be a nutrition SUM type" % (n["key"], ht))
        if m["unit"] != n["unit"]:
            problems.append("nutrient %s: health_type %s unit %s != %s (conversion is not modelled; fix the data)" % (
                n["key"], ht, m["unit"], n["unit"]))
        if ht in seen:
            problems.append("health_type %s used by %s and %s" % (ht, seen[ht], n["key"]))
        seen[ht] = n["key"]
    for m in reg["metrics"]:
        if m["id"].startswith("dietary_") and m["id"] not in seen and m["id"] not in MACRO_HEALTH_TYPES:
            problems.append("registry %s: no nutrient maps it (add health_type or list it in MACRO_HEALTH_TYPES)" % m["id"])
    for ht in MACRO_HEALTH_TYPES:
        if ht not in by_id or ht in seen:
            problems.append("macro health type %s must exist in the registry and map to no nutrient" % ht)


def check_prompt(ref, problems):
    try:
        p = R.load_prompts()
    except Exception as e:  # noqa: BLE001
        problems.append("ai_supplement_label.md: cannot load prompts (%s)" % e)
        return
    keys = ", ".join([n["key"] for n in ref["nutrients"]] + SPORTS_KEYS)
    if keys != ", ".join(R.SUPPLEMENT_KEYS):
        problems.append("nutrients_reference.SUPPLEMENT_KEYS must be every reference key + sports keys in order")
    if ("\n" + keys + "\n") not in p["cloud"] or ("Keys: " + keys + "\n") not in p["local"]:
        problems.append("ai_supplement_label.md: key lists must equal every reference key + sports keys in "
                        "order:\n%s" % keys)
    if not re.search("prompt [(]v[0-9]+[)]", open(PROMPT, encoding="utf-8").read().split("\n")[0]):
        problems.append("ai_supplement_label.md: the title must carry the prompt version (vN)")
    for k in ("cloud", "local"):
        if '{"serving_units":1,"items":[{"key":"","amount":0,"unit":"","form":null}]}' not in p[k]:
            problems.append("ai_supplement_label.md: %s prompt must show the output shape" % k)
    for k in ("user_photo", "user_text"):
        for ph in ("{name}", "{strength}", "{dose_unit}"):
            if p[k].count(ph) != 1:
                problems.append("ai_supplement_label.md: %s needs %s exactly once" % (k, ph))


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


def _cover(seen, f, inp, got):
    def add(tag):
        seen.add("%s:%s" % (f, tag))
    if f == "reference_lines":
        add("style_%s" % got["style"])
        if inp["key"] in R.BY_KEY and not R.BY_KEY[inp["key"]]["app_tracked"]:
            add("untracked")
        add("band_%s" % got["band"])
        if got["error"]:
            add(got["error"])
        if got["upper_limit_scope"]:
            add("scope_" + got["upper_limit_scope"])
        if R.LABEL_GOAL in (got["recommended_label"], got["limit_label"]):
            add("your_goal")
        if got["sex"] is None and got["style"] == "target" and got["upper_limit"] is not None:
            add("unknown_sex")
        lim = (R.BY_KEY.get(inp["key"]) or {}).get("limit")
        if lim and lim["kind"] == "pct_energy":
            add("pct_energy_" + ("value" if got["reference_limit"] is not None else "null"))
    elif f == "default_goal":
        add("null" if got["value"] is None else "value")
        if inp["key"] in R.BY_KEY and not R.BY_KEY[inp["key"]]["app_tracked"]:
            add("untracked")
        if got["value"] is not None and 0 < got["value"] < 0.5:
            add("min_1")
    elif f == "convert_amount":
        add(got["error"] or "ok")
        if inp["key"] in R.BY_KEY and not R.BY_KEY[inp["key"]]["app_tracked"]:
            add("untracked_" + (got["error"] or "ok"))
        if inp.get("unit") and R.normalize_unit(inp["unit"]) == "iu" and got["ok"]:
            add("iu_ok")
        if inp["key"] == "vitamin_a" and got["ok"] and R.normalize_unit(inp.get("unit")) != "iu" and got["amount"] == R.convert_amount(inp["value"], inp["unit"], "vitamin_a")["amount"] and inp.get("form") in (None, "retinol"):
            add("vitamin_a_retinol_mass")
    elif f == "supplement_entries":
        for log in inp.get("dose_logs") or []:
            add("status_" + log["status"])
    elif f == "day_totals":
        for v in got["totals"].values():
            if v["total"] is None:
                add("all_null")
            if v["food"] is None and v["supplements"] is not None:
                add("supplements_only")
            if v["food"] is not None and v["supplements"] is not None:
                add("both")
        for k, v in got["totals"].items():
            if k in R.BY_KEY and not R.BY_KEY[k]["app_tracked"]:
                if v["food"] is not None:
                    add("untracked_food_not_null")      # never allowed: required_coverage cannot contain it
                elif v["supplements"] is not None and v["total"] == v["supplements"]:
                    add("untracked_supplements_only")
        if any(k in R.BY_KEY and not R.BY_KEY[k]["app_tracked"] for e in inp.get("food_entries") or []
               for k in (e.get("nutrients") or {})):
            add("untracked_food_value_ignored")
    elif f == "logged_day_average":
        add("null" if got["average"] is None else "value")
        if inp.get("key") in R.BY_KEY and not R.BY_KEY[inp["key"]]["app_tracked"] and inp.get("logged_days"):
            add("untracked_dose_days_only")
    elif f == "parse_label_output":
        add(got["error"] or "ok")
        for r in got["rejected"]:
            add(r["code"])
        if any(it["key"] in UNTRACKED_KEYS for it in got["items"]):
            add("untracked_accepted")
        if len(set(it["key"] for it in got["items"]) & set(UNTRACKED_KEYS)) >= 8:
            add("multivitamin_label")


def required_coverage():
    req = {"reference_lines": ["style_target", "style_limit", "style_info", "band_19-30", "band_31-50", "band_51-70",
                               "band_71+", "unknown_nutrient", "your_goal", "unknown_sex", "pct_energy_value",
                               "pct_energy_null", "untracked"] + ["scope_" + s for s in R.UL_SCOPES],
           "default_goal": ["null", "value", "min_1", "untracked"],
           "convert_amount": ["ok", "iu_ok", "untracked_ok", "untracked_iu_not_supported", "vitamin_a_retinol_mass"]
           + list(R.CONVERT_ERRORS),
           "supplement_entries": ["status_taken", "status_skipped", "status_missed", "status_snoozed"],
           "day_totals": ["all_null", "supplements_only", "both", "untracked_supplements_only",
                          "untracked_food_value_ignored"],
           "logged_day_average": ["null", "value", "untracked_dose_days_only"],
           "parse_label_output": ["ok", "parse_error", "bad_shape", "bad_item", "unknown_nutrient", "duplicate_nutrient",
                                  "amount_too_large", "bad_serving", "form_required", "iu_not_supported",
                                  "unsupported_unit", "invalid_amount", "untracked_accepted", "multivitamin_label"]}
    return set("%s:%s" % (f, t) for f, tags in req.items() for t in tags)


def check_vectors(write, problems):
    counts, seen, zones_seen = {}, set(), set()
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
            if set(c) - {"name", "input", "expected", "notes"} or not c.get("name") or not isinstance(c.get("input"), dict):
                problems.append("%s: bad case keys" % name)
                continue
            if c["name"] in names:
                problems.append("%s: duplicate case %s" % (name, c["name"]))
            names.add(c["name"])
            where = "%s/%s" % (name, c["name"])
            zones = set()
            _zones(c["input"], zones)
            zones_seen |= zones
            if zones - ALLOWED_ZONES:
                problems.append("%s: zone outside the allowed set" % where)
            try:
                got = json.loads(json.dumps(R.run_case(doc["function"], c["input"]), ensure_ascii=False))
            except Exception as e:  # noqa: BLE001
                problems.append("%s: reference raised %s: %s" % (where, type(e).__name__, e))
                continue
            if write:
                if c.get("expected") != got:
                    c["expected"], changed = got, True
            elif c.get("expected") != got:
                problems.append("%s: expected differs at %s" % (where, _first_diff(c.get("expected"), got)))
            _cover(seen, doc["function"], c["input"], got)
        counts[name] = len(doc["cases"])
        if write and (changed or raw != dumps(doc)):
            with open(path, "w", encoding="utf-8") as fh:
                fh.write(dumps(doc))
        elif not write and raw != dumps(doc):
            problems.append("%s: not in canonical format (run --write)" % name)
    for tag in sorted(required_coverage() - seen):
        problems.append("coverage: no vector covers %s" % tag)
    if "day_totals:untracked_food_not_null" in seen:
        problems.append("day_totals: an app_tracked: false nutrient must have food null")
    if zones_seen != ALLOWED_ZONES:
        problems.append("coverage: vectors must use all four zones, missing %s" % sorted(ALLOWED_ZONES - zones_seen))
    return counts


def check_portability(problems):
    src = open(os.path.join(HERE, "nutrients_reference.py"), encoding="utf-8").read()
    for pattern, what in (("import statistics", "statistics module"), ("from statistics", "statistics module"),
                          ("(?<![A-Za-z_.])round[(]", "built-in round() (banker's rounding)"),
                          ("math[.]fsum", "math.fsum (different summation)"), ("[.]isoformat[(]", "isoformat")):
        if re.search(pattern, src):
            problems.append("nutrients_reference.py uses %s" % what)


# ---------------------------------------------------------------------------------------------
# Copies and docs
# ---------------------------------------------------------------------------------------------

def check_copies(write, problems):
    for src in (REFERENCE, PROMPT):
        body = open(src, encoding="utf-8").read()
        for folder in COPY_DIRS:
            path = os.path.join(folder, os.path.basename(src))
            rel = os.path.relpath(path, ROOT)
            current = open(path, encoding="utf-8").read() if os.path.exists(path) else None
            if current == body:
                continue
            if write:
                os.makedirs(folder, exist_ok=True)
                with open(path, "w", encoding="utf-8") as fh:
                    fh.write(body)
            else:
                problems.append("%s differs from shared/nutrients/%s (run --write)" % (rel, os.path.basename(src)))


def _num(v):
    if v is None:
        return "—"
    if v == int(v):
        s = str(int(v))
        return s if len(s) < 5 else "{:,}".format(int(v))
    return "%g" % v


def _row_values(table):
    """'M 400 / 420 / 420 / 420 · F 310 / 320 / 320 / 320', collapsed when a sex has one value for every band
    and when both sexes are equal."""
    parts = {}
    for sex in ("male", "female"):
        vals = [table[sex][b] for b in BANDS]
        parts[sex] = _num(vals[0]) if len(set(vals)) == 1 else " / ".join(_num(v) for v in vals)
    if parts["male"] == parts["female"]:
        return parts["male"]
    return "M %s · F %s" % (parts["male"], parts["female"])


def render_doc(ref):
    out = [BEGIN, "", "_Generated from `shared/nutrients/nutrient_reference.json` by `scripts/nutrients_contract_check.py "
           "--write`. Do not edit by hand._", "",
           "Population: %s. Bands: %s (default %s). Values that differ by band are listed as 19–30 / 31–50 / 51–70 / 71+." % (
               ref["population"], ", ".join(b["id"] for b in ref["age_bands"]), ref["default_band"]), "",
           "App tracked: ✓ = the food log records it; — = the food log does not record it (its `nutrient:<key>` chart "
           "counts Medications supplements only). Every row can be a supplement nutrient and an AI label key and has "
           "a `nutrient:<key>` chart.", "",
           "| Nutrient | Unit | App | Health type | Style | Recommended | Upper limit (scope) | Limit | IU | Sources |",
           "|---|---|---|---|---|---|---|---|---|---|"]
    for n in ref["nutrients"]:
        rec = "%s %s" % (n["recommended"]["kind"], _row_values(n["recommended"])) if n["recommended"] else "—"
        ul = "%s (%s)" % (_row_values(n["upper_limit"]), n["upper_limit"]["scope"]) if n["upper_limit"] else "—"
        lim = n["limit"]
        if lim is None:
            lim_s = "—"
        elif lim["kind"] == "fixed":
            lim_s = "%s (%s)" % (_num(lim["value"]), lim["basis"])
        else:
            lim_s = "%g%% of calories ÷ %g kcal/%s (%s)" % (lim["pct"], lim["kcal_per_unit"], n["unit"], lim["basis"])
        iu = n["iu"]
        if iu is None:
            iu_s = "—"
        elif "mcg_per_iu" in iu:
            iu_s = "1 IU = %g %s" % (iu["mcg_per_iu"], iu["unit"])
        else:
            iu_s = "; ".join("%s %g %s" % (k, v, iu["unit"]) for k, v in sorted(iu["forms"].items()))
        out.append("| %s (`%s`) | %s | %s | %s | %s | %s | %s | %s | %s | %s |" % (
            n["name"], n["key"], n["unit"], "✓" if n["app_tracked"] else "—",
            "`%s`" % n["health_type"] if n["health_type"] else "—", n["style"], rec, ul, lim_s, iu_s,
            ", ".join("`%s`" % s for s in n["source_ids"])))
    out += ["", "Mass forms (amount of the form that equals 1 canonical unit): " + "; ".join(
        "%s: %s" % (n["key"], ", ".join("%s %g" % (k, v) for k, v in sorted(n["mass_forms"].items())))
        for n in ref["nutrients"] if n["mass_forms"]) + ".", "",
        "Sports supplements (no reference lines, no guide page): %s — unit %s." % (
            ", ".join("`%s`" % s["key"] for s in ref["sports_supplements"]), ref["sports_supplements"][0]["unit"]), "",
        "Sanity cap per dose unit: %s." % ", ".join("%s %s" % (_num(v), u) for u, v in sorted(ref["amount_per_unit_max"].items())),
        "", "### Notes", ""]
    for n in ref["nutrients"]:
        notes = list(n["notes"]) + ([n["upper_limit"]["note"]] if n["upper_limit"] else [])
        if notes:
            out.append("- **%s.** %s" % (n["name"], " ".join(notes)))
    out += ["", "### Sources", ""]
    for sid in sorted(ref["sources"]):
        s = ref["sources"][sid]
        out.append("- `%s`: %s — %s. <%s> (checked %s%s)" % (sid, s["title"], s["publisher"], s["url"], s["checked"],
                                                            ", page updated %s" % s["page_updated"] if s.get("page_updated") else ""))
    out += ["", END]
    return "\n".join(out)


def check_doc(ref, write, problems):
    if not os.path.exists(DOC):
        problems.append("docs/nutrients.md missing")
        return
    text = open(DOC, encoding="utf-8").read()
    i, j = text.find(BEGIN), text.find(END)
    if i < 0 or j < 0:
        problems.append("docs/nutrients.md: generated block markers missing")
        return
    new = text[:i] + render_doc(ref) + text[j + len(END):]
    if new != text:
        if write:
            with open(DOC, "w", encoding="utf-8") as fh:
                fh.write(new)
        else:
            problems.append("docs/nutrients.md generated block is stale (run --write)")


# ---------------------------------------------------------------------------------------------
# Hand-computed unit tests
# ---------------------------------------------------------------------------------------------

class ReferenceTests(unittest.TestCase):
    def test_round_half_up(self):
        self.assertEqual((R.round_to(2.5, 0), R.round_to(0.125, 2), R.round_to(-0.0000001, 6)), (3.0, 0.13, 0.0))
        self.assertEqual((R.round_half_up_int(1.5), R.round_half_up_int(2.4)), (2, 2))

    def test_bands(self):
        self.assertEqual([R.band_for_age(a) for a in (None, 12, 18.99, 19, 30.9, 31, 50, 51, 70, 71, 104)],
                         ["31-50", "31-50", "31-50", "19-30", "19-30", "31-50", "31-50", "51-70", "51-70", "71+", "71+"])

    def test_weekly_vitamin_d(self):
        self.assertEqual(R.convert_amount(60000, "IU", "vitamin_d")["amount"], 1500)
        self.assertEqual(R.convert_amount(400, "IU", "vitamin_e", "natural")["amount"], 268)
        self.assertEqual(R.convert_amount(400, "IU", "vitamin_e", "synthetic")["amount"], 180)
        self.assertEqual(R.convert_amount(5000, "IU", "vitamin_a", "retinol")["amount"], 1500)
        self.assertEqual(R.convert_amount(500, "IU", "zinc")["error"], "iu_not_supported")

    def test_unknown_sex_picks(self):
        r = R.reference_lines("iron", {"age": 30})
        self.assertEqual((r["recommended"], r["upper_limit"]), (18.0, 45.0))
        r = R.reference_lines("calcium", {"age": 60})
        self.assertEqual((r["recommended"], r["upper_limit"]), (1200.0, 2000.0))

    def test_pct_energy(self):
        self.assertEqual(R.reference_lines("saturated_fat", {"calorie_goal": 2000})["limit"], 22.2)
        self.assertEqual(R.reference_lines("added_sugar", {"calorie_goal": 2000})["limit"], 50.0)
        self.assertIsNone(R.reference_lines("added_sugar", {})["limit"])

    def test_custom_goal_label(self):
        r = R.reference_lines("vitamin_d", {}, 50)
        self.assertEqual((r["recommended"], r["recommended_label"], r["reference_recommended"]), (50.0, "Your goal", 15.0))
        r = R.reference_lines("sodium", {}, 1500)
        self.assertEqual((r["limit"], r["limit_label"]), (1500.0, "Your goal"))

    def test_default_goal_int(self):
        self.assertEqual([R.default_goal_int(v) for v in (None, 0.3, 1.1, 1.6, 2.4, 22.2, 0.0)], [None, 1, 1, 2, 2, 22, 0])

    def test_supplements_only_taken(self):
        mn = [{"medication_id": "m", "nutrient_key": "vitamin_d", "amount_per_unit": 1500}]
        logs = [{"medication_id": "m", "status": s, "taken_at_ms": 1 if s == "taken" else None, "dose_quantity": 1}
                for s in ("taken", "skipped", "missed", "snoozed")]
        self.assertEqual([e["value"] for e in R.supplement_entries(mn, logs)], [1500])

    def test_day_totals_null_is_not_zero(self):
        t = R.day_totals([{"t_ms": 0, "nutrients": {"iron": None}}], [], "1970-01-01", "UTC")
        self.assertEqual(t["iron"], {"food": None, "supplements": None, "total": None})

    def test_logged_day_average(self):
        day = 86_400_000
        got = R.logged_day_average([{"t_ms": day, "value": 1500}], ["1970-01-01", "1970-01-02", "1970-01-03"],
                                   {"start_ms": 0, "end_ms": 7 * day}, "UTC")
        self.assertEqual(got, {"average": 500.0, "logged_days": 3})

    def test_label_serving_division(self):
        out = R.parse_label_output('{"serving_units":2,"items":[{"key":"zinc","amount":20,"unit":"mg"}]}')
        self.assertEqual(out["items"], [{"key": "zinc", "amount": 10.0, "unit": "mg", "form": None}])

    def test_untracked_nutrients(self):
        r = R.reference_lines("copper", {"sex": "female", "age": 40})
        self.assertEqual((r["recommended"], r["upper_limit"], r["upper_limit_scope"]), (0.9, 10.0, "all_sources"))
        r = R.reference_lines("vitamin_b6", {"sex": "male", "age": 60})
        self.assertEqual((r["recommended"], r["upper_limit"]), (1.7, 100.0))
        self.assertEqual(R.reference_lines("chromium", {"age": 75})["recommended"], 30.0)
        self.assertIsNone(R.reference_lines("chromium", {})["upper_limit"])
        self.assertEqual(R.reference_lines("niacin", {})["upper_limit_scope"], "supplements_only")
        self.assertEqual(R.convert_amount(1.7, "mg", "copper")["amount"], 1.7)
        self.assertEqual(R.convert_amount(140, "mcg", "iodine")["amount"], 140)
        self.assertEqual(R.convert_amount(1, "IU", "copper")["error"], "iu_not_supported")
        self.assertEqual(R.convert_amount(50, "mg", "grape_seed_extract")["error"], "unknown_nutrient")
        self.assertEqual(R.convert_amount(1000, "mcg", "vitamin_a", "retinol")["amount"], 1000)
        self.assertEqual(R.convert_amount(300, "mcg", "folate", "folic_acid")["amount"], 500)
        t = R.day_totals([{"t_ms": 0, "nutrients": {"copper": 2, "zinc": 3}}],
                         [{"t_ms": 1, "nutrient_key": "copper", "value": 1.7, "medication_id": "m"}], "1970-01-01", "UTC")
        self.assertEqual(t["copper"], {"food": None, "supplements": 1.7, "total": 1.7})
        self.assertEqual(t["zinc"], {"food": 3.0, "supplements": None, "total": 3.0})
        self.assertFalse(R.food_tracked("copper"))
        self.assertTrue(R.food_tracked("creatine"))
        self.assertEqual(R.BY_HEALTH_TYPE["dietary_vitamin_d"], "vitamin_d")
        self.assertEqual(R.BY_HEALTH_TYPE["dietary_fat_saturated"], "saturated_fat")

    def test_app_units_parsed(self):
        u = app_units()
        self.assertEqual((u["vitamin_d"], u["sodium"], u["omega_3"], u["creatine"]), ("mcg", "mg", "g", "g"))


def main(argv):
    write = "--write" in argv
    problems = []
    ref = R.load_reference()
    lint_reference(ref, problems)
    check_prompt(ref, problems)
    counts = check_vectors(write, problems)
    check_portability(problems)
    check_copies(write, problems)
    check_doc(ref, write, problems)
    suite = unittest.defaultTestLoader.loadTestsFromTestCase(ReferenceTests)
    result = unittest.TextTestRunner(stream=open(os.devnull, "w"), verbosity=0).run(suite)
    for failed, trace in result.failures + result.errors:
        problems.append("unittest %s failed:\n%s" % (failed.id(), trace.strip().splitlines()[-1]))
    print("%d nutrients (%d app tracked; %d target, %d limit, %d info), %d sports supplements, %d sources" % (
        len(ref["nutrients"]), sum(n["app_tracked"] for n in ref["nutrients"]), sum(n["style"] == "target" for n in ref["nutrients"]),
        sum(n["style"] == "limit" for n in ref["nutrients"]), sum(n["style"] == "info" for n in ref["nutrients"]),
        len(ref["sports_supplements"]), len(ref["sources"])))
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
