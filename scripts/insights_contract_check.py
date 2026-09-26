#!/usr/bin/env python3
"""Ayuvo Insights contract check (Python 3 stdlib only).

    python3 scripts/insights_contract_check.py           # verify; exit 1 on any problem
    python3 scripts/insights_contract_check.py --write   # rewrite vectors' "expected", the platform copies and the
                                                         # generated block of docs/insights.md

Checks:
  1. shared/insights/insights_config.json lint: envelope, metric keys, weights summing to 100, bands, monotonic
     reference tables, a source on every marker and model, rule and pair templates whose placeholders are declared
     params, every rule implemented by the reference, methodology blocks, AI settings and copy rules.
  2. shared/insights/ai_explain.md has the three fenced prompts the reference loads.
  3. shared/insights/test-vectors/*.json: exact file set, envelope, unique names, allowed time zones, canonical
     formatting and every "expected" equals scripts/insights_reference.py on its "input". Coverage: every status,
     label, direction, load category, review rule, pattern outcome and AI validator error appears at least once.
  4. Regex portability lint of every pattern owned by the reference (records rule) and no platform-divergent calls.
  5. Platform copies of the config and prompt are byte-identical to shared/ (Android assets, iOS bundle resource).
  6. docs/insights.md generated methodology + weights block is up to date.
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
SHARED = os.path.join(ROOT, "shared", "insights")
VECTORS = os.path.join(SHARED, "test-vectors")
CONFIG = os.path.join(SHARED, "insights_config.json")
PROMPT = os.path.join(SHARED, "ai_explain.md")
DOC = os.path.join(ROOT, "docs", "insights.md")
COPY_DIRS = [
    os.path.join(ROOT, "android", "app", "src", "main", "assets", "insights"),
    os.path.join(ROOT, "ios", "calorietracker", "Insights", "Resources"),
]
BEGIN = "<!-- BEGIN GENERATED INSIGHTS -->"
END = "<!-- END GENERATED INSIGHTS -->"
sys.path.insert(0, HERE)
import insights_reference as R  # noqa: E402
from records_contract_check import lint_pattern  # noqa: E402

FORMAT = "ayuvo-insights-vectors"
EXPECTED_FILES = {"baseline.json": "baseline", "trend.json": "trend", "overnight.json": "overnight_value",
                  "training_load.json": "training_load", "recovery.json": "recovery", "health_age.json": "health_age",
                  "health_age_pace.json": "health_age_pace", "daily_review.json": "daily_review",
                  "patterns.json": "patterns", "ai_summary.json": "ai_summary"}
ALLOWED_ZONES = frozenset(["America/New_York", "Europe/London", "Asia/Kolkata", "UTC"])
METRICS = ("sleep", "resting_heart_rate", "hrv", "vo2_max", "respiratory_rate", "blood_oxygen", "steps",
           "active_energy", "workout", "weight", "body_fat", "bmi")
METRIC_KEYS = ("label", "health_type", "direction", "unit", "window_days", "min_points", "sd_floor", "recent",
               "high_confidence_coverage", "high_confidence_min_n", "trend_days", "trend_min_points",
               "trend_stable_pct_per_week")
MARKER_METHODS = ("age_norm", "dose_response", "sleep", "workouts", "body_composition")
METHODOLOGY = ("baselines", "recovery", "health_age", "daily_review", "patterns", "background")
CONTRIBUTOR_PARAMS = {"pct", "delta0", "delta1", "value1", "duration"}
PAIR_PARAMS = {"abs_diff", "unit", "direction_word", "n_exposed", "n_unexposed"}
EXPOSURES = ("late_intense_workout", "high_load", "water_goal_met", "protein_target_met", "short_sleep")
OUTCOMES = ("sleep_minutes", "recovery_score", "strength_volume", "steps")
AI_ERRORS = ("parse_error", "bad_shape", "headline_length", "bullet_count", "bullet_length", "unknown_number",
             "blocked_term")
# Copy rules: no local-only claims, no causal wording, no brand residue.
FORBIDDEN_COPY = ("never leaves", "leaves your device", "stays on your device", "f" "ud ai", "f" "udai", "caused by",
                  "causes your", "proves")


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


def _placeholders(template):
    return set(R.PLACEHOLDER_RE.findall(template))


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
# Config lint
# ---------------------------------------------------------------------------------------------

def _monotonic(table):
    ages = [p[0] for p in table]
    vals = [p[1] for p in table]
    inc = all(b > a for a, b in zip(vals, vals[1:]))
    dec = all(b < a for a, b in zip(vals, vals[1:]))
    return ages == sorted(set(ages)) and len(ages) >= 2 and (inc or dec)


def _ascending(points):
    xs = [p[0] for p in points]
    return len(xs) >= 2 and all(b > a for a, b in zip(xs, xs[1:]))


def lint_config(cfg, problems):
    if cfg.get("format") != "ayuvo-insights-config" or cfg.get("config_version") != 1:
        problems.append("config: bad format/config_version")
    # metrics
    if set(cfg["metrics"]) != set(METRICS):
        problems.append("metrics: keys %s != %s" % (sorted(cfg["metrics"]), sorted(METRICS)))
    for mid, m in cfg["metrics"].items():
        w = "metrics.%s" % mid
        for k in METRIC_KEYS:
            if k not in m:
                problems.append("%s: missing %s" % (w, k))
        if m.get("direction") not in ("higher_better", "lower_better", "band"):
            problems.append("%s: bad direction" % w)
        if m.get("recent") not in ("day", "mean7"):
            problems.append("%s: bad recent" % w)
        if set(m.get("health_type", {})) != {"ios", "android"} or not m["health_type"]["ios"]:
            problems.append("%s: health_type needs ios and android (android may be null)" % w)
        if m.get("min_points", 0) < 2 or m.get("sd_floor", 0) <= 0 or m.get("window_days", 0) < m.get("min_points", 0):
            problems.append("%s: bad window/min_points/sd_floor" % w)
    if cfg["metrics"]["hrv"]["health_type"] != {"ios": "hrv_sdnn", "android": "hrv_rmssd"}:
        problems.append("metrics.hrv: must be hrv_sdnn on iOS and hrv_rmssd on Android")
    # training load
    t = cfg["training_load"]
    if not (0 < t["light_below"] <= t["high_above"]) or t["intensity_min"] > t["default_intensity"] > t["intensity_max"]:
        problems.append("training_load: bad thresholds")
    if set(t["labels"]) != {"none", "light", "moderate", "high"}:
        problems.append("training_load: labels must cover none/light/moderate/high")
    # recovery
    rc = cfg["recovery"]
    ids = [c["id"] for c in rc["components"]]
    if sum(c["weight"] for c in rc["components"]) != 100:
        problems.append("recovery: component weights must sum to 100")
    if len(set(ids)) != len(ids) or "sleep" not in ids or set(rc["heart_components"]) - set(ids):
        problems.append("recovery: bad component ids")
    for c in rc["components"]:
        if c["metric"] not in cfg["metrics"] or c["mode"] not in ("higher", "lower", "band", "drop_only", "sleep"):
            problems.append("recovery.%s: bad metric/mode" % c["id"])
        if c["mode"] == "sleep" and c["duration_share"] + c["consistency_share"] != 1:
            problems.append("recovery.sleep: shares must sum to 1")
    mins = [b["min"] for b in rc["bands"]]
    if mins != sorted(mins, reverse=True) or mins[-1] != 0 or len({b["id"] for b in rc["bands"]}) != len(mins):
        problems.append("recovery: bands must be descending and end at 0")
    for key, tpl in rc["contributors"].items():
        allowed = {"category"} if key == "training_load" else CONTRIBUTOR_PARAMS
        if key != "training_load" and key not in ids:
            problems.append("recovery.contributors.%s: unknown component" % key)
        if _placeholders(tpl) - allowed:
            problems.append("recovery.contributors.%s: unknown placeholder" % key)
    for i in ids + ["training_load"]:
        if i not in rc["contributors"]:
            problems.append("recovery.contributors: missing %s" % i)
    # health age
    ha = cfg["health_age"]
    if sum(m["weight"] for m in ha["markers"]) != 100:
        problems.append("health_age: marker weights must sum to 100")
    mids = [m["id"] for m in ha["markers"]]
    if len(set(mids)) != len(mids) or set(ha["core_markers"]) - set(mids):
        problems.append("health_age: bad marker ids")
    for m in ha["markers"]:
        w = "health_age.%s" % m["id"]
        if m["method"] not in MARKER_METHODS:
            problems.append("%s: bad method" % w)
        if not m.get("source") or len(m["source"]) < 40:
            problems.append("%s: missing source" % w)
        if m["cap_years"] <= 0 or m["cap_years"] > 6 or m["min_days"] < 1:
            problems.append("%s: cap_years must be in (0, 6] and min_days >= 1" % w)
        if m["method"] == "age_norm":
            groups = list(m["tables"].values()) if m["tables_by_kind"] else [m["tables"]]
            if m["tables_by_kind"] and set(m["tables"]) != {"sdnn", "rmssd"}:
                problems.append("%s: HRV tables must be sdnn and rmssd" % w)
            for g in groups:
                if set(g) != {"male", "female"} or [p[0] for p in g["male"]] != [p[0] for p in g["female"]]:
                    problems.append("%s: tables need male and female with the same ages" % w)
                for sex in ("male", "female"):
                    if not _monotonic(g.get(sex, [])):
                        problems.append("%s: %s table must be strictly monotonic with ascending ages" % (w, sex))
                    if any(p[0] < ha["age_min"] or p[0] > ha["age_max"] for p in g.get(sex, [])):
                        problems.append("%s: table ages outside age_min..age_max" % w)
        for key in ("points", "duration_points", "regularity_points", "minutes_points", "active_share_points", "bmi_points"):
            if key in m and not _ascending(m[key]):
                problems.append("%s.%s: x must be strictly ascending" % (w, key))
        if "body_fat_points" in m:
            for sex, pts in m["body_fat_points"].items():
                if sex not in ("male", "female") or not _ascending(pts):
                    problems.append("%s.body_fat_points.%s: bad" % (w, sex))
    p = ha["pace"]
    if not p["improving_below"] < p["declining_above"] or p["min_points"] > p["weeks"] + 1:
        problems.append("health_age.pace: bad thresholds")
    if "WHOOP" not in ha["source"] or "not" not in ha["source"]:
        problems.append("health_age.source must say it does not reproduce WHOOP")
    # daily review
    rv = cfg["daily_review"]
    aids = [a["id"] for a in rv["areas"]]
    if aids != ["nutrition", "hydration", "activity", "training", "sleep", "recovery", "fasting"]:
        problems.append("daily_review: areas must be nutrition, hydration, activity, training, sleep, recovery, fasting")
    if any(a["weight"] <= 0 for a in rv["areas"]):
        problems.append("daily_review: area weights must be positive")
    if _placeholders(rv["not_logged_template"]) - {"area", "area_label"}:
        problems.append("daily_review.not_logged_template: unknown placeholder")
    rids = [r["id"] for r in rv["rules"]]
    if len(set(rids)) != len(rids):
        problems.append("daily_review: duplicate rule ids")
    if set(rids) != set(R.RULES):
        problems.append("daily_review: rules %s not implemented / %s not configured" % (
            sorted(set(rids) - set(R.RULES)), sorted(set(R.RULES) - set(rids))))
    for r in rv["rules"]:
        if r["category"] not in R.REVIEW_CATEGORIES:
            problems.append("rule %s: bad category" % r["id"])
        if _placeholders(r["template"]) != set(r["params"]):
            problems.append("rule %s: template placeholders %s != params %s" % (
                r["id"], sorted(_placeholders(r["template"])), sorted(r["params"])))
    for n in rv["reduce_nutrients"]:
        if not n["goal_key"].endswith("_max_g") and not n["goal_key"].endswith("_max_mg"):
            problems.append("reduce_nutrients.%s: goal_key must be an upper limit" % n["id"])
    # patterns
    pc = cfg["patterns"]
    if pc["min_group"] < 8 or pc["min_abs_t"] < 2 or pc["min_abs_d"] < 0.3 or pc["window_days"] != 120:
        problems.append("patterns: thresholds weaker than the contract (8, 2, 0.3, 120 days)")
    pids = [q["id"] for q in pc["pairs"]]
    if len(set(pids)) != len(pids):
        problems.append("patterns: duplicate pair ids")
    for q in pc["pairs"]:
        if q["exposure"] not in EXPOSURES or q["outcome"] not in OUTCOMES:
            problems.append("pair %s: unknown exposure/outcome" % q["id"])
        if _placeholders(q["template"]) - PAIR_PARAMS or "{n_exposed}" not in q["template"]:
            problems.append("pair %s: template placeholders must come from %s and include the sample sizes" % (
                q["id"], sorted(PAIR_PARAMS)))
        if q.get("review_category") not in (None, "reduce"):
            problems.append("pair %s: review_category must be null or reduce" % q["id"])
    # sources on models
    for key in ("training_load", "recovery", "patterns", "health_age"):
        if not cfg[key].get("source"):
            problems.append("%s: missing source" % key)
    # methodology, disclaimers, AI
    if set(cfg["methodology"]) != set(METHODOLOGY):
        problems.append("methodology: blocks must be %s" % ", ".join(METHODOLOGY))
    for k, block in cfg["methodology"].items():
        if not block.get("title") or not block.get("sections"):
            problems.append("methodology.%s: needs title and sections" % k)
        for s in block.get("sections", []):
            if set(s) != {"heading", "body"} or not s["heading"] or not s["body"]:
                problems.append("methodology.%s: bad section" % k)
    for k in ("general", "health_age", "patterns", "ai", "background"):
        if not cfg["disclaimers"].get(k):
            problems.append("disclaimers.%s missing" % k)
    a = cfg["ai"]
    if set(a["tasks"]) != set(R.AI_KINDS) or a["bullets_max"] > 5 or set(a["status_labels"]) != {"local", "cloud"}:
        problems.append("ai: tasks must cover %s, at most 5 bullets, local/cloud status labels" % ", ".join(R.AI_KINDS))
    if _placeholders(a["status_labels"]["cloud"]) != {"provider"}:
        problems.append("ai.status_labels.cloud must contain {provider}")
    # copy rules
    strings = []
    _strings(cfg, strings)
    strings.append(open(PROMPT, encoding="utf-8").read())
    for s in strings:
        low = s.lower()
        for bad in FORBIDDEN_COPY:
            if bad in low:
                problems.append("copy: forbidden phrase %r in %r" % (bad, s[:60]))
        if "whoop" in low and "not" not in low:
            problems.append("copy: WHOOP mentioned without saying Ayuvo's score is not WHOOP's: %r" % s[:60])


def check_prompts(problems):
    try:
        p = R.load_prompts()
    except Exception as e:  # noqa: BLE001
        problems.append("ai_explain.md: cannot load prompts (%s)" % e)
        return
    if p["user"].count("{task}") != 1 or p["user"].count("{payload}") != 1:
        problems.append("ai_explain.md: user template needs {task} and {payload} exactly once")
    for k in ("cloud", "local"):
        if '{"headline":"","bullets":[]}' not in p[k]:
            problems.append("ai_explain.md: %s prompt must show the output shape" % k)


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


def _cover(seen, got, f, cfg, where, problems):
    def add(tag):
        seen.add("%s:%s" % (f, tag))
    if f == "baseline":
        add(got["status"])
        add("confidence_" + got["confidence"])
        if got["sd_floored"]:
            add("sd_floored")
    elif f == "trend":
        add(got["direction"] or got["status"])
    elif f == "overnight_value":
        add("fallback" if got["fallback"] else "night")
    elif f == "training_load":
        add(got["category"])
    elif f == "recovery":
        add(got["status"])
        if got["label"]:
            add(got["label"])
            add("score_%d" % got["score"])
        if got["load"] and got["load"]["modifier"]:
            add("modifier_%d" % got["load"]["modifier"])
    elif f == "health_age":
        add(got["status"])
    elif f == "health_age_pace":
        add(got["direction"] or got["status"])
    elif f == "daily_review":
        add("scored" if got["day_score"] is not None else "no_score")
        rules = dict((r["id"], r) for r in cfg["daily_review"]["rules"])
        for c in R.REVIEW_CATEGORIES:
            for i in got[c]:
                add("rule_" + i["rule_id"])
                if set(i["params"]) != set(rules[i["rule_id"]]["params"]):
                    problems.append("%s: rule %s params %s != declared" % (where, i["rule_id"], sorted(i["params"])))
        if got["not_logged"]:
            add("not_logged")
    elif f == "patterns":
        for p in got:
            add(p["id"] + "_" + p["status"])
            if p["surfaced"]:
                add("surfaced")
            elif p["status"] == "ok":
                add("not_surfaced")
    elif f == "ai_summary":
        for v in got["validations"]:
            add("ok" if v["ok"] else "")
            for e in v["errors"]:
                add(e)
        if "patterns" in got["prompt"]["user"]:
            add("patterns_in_prompt")


def required_coverage(cfg):
    req = {"baseline": ["ok", "insufficient", "confidence_high", "confidence_medium", "sd_floored"],
           "trend": ["improving", "stable", "declining", "changing", "insufficient"],
           "overnight_value": ["night", "fallback"],
           "training_load": ["none", "light", "moderate", "high"],
           "recovery": ["ok", "collecting", "no_sleep", "no_heart_data", "good", "moderate", "low", "score_67",
                        "score_66", "score_34", "score_33", "modifier_-3", "modifier_-6"],
           "health_age": ["ok", "no_birthday", "unsupported_age", "collecting"],
           "health_age_pace": ["improving", "stable", "declining", "insufficient"],
           "daily_review": ["scored", "no_score", "not_logged"] + ["rule_" + r["id"] for r in cfg["daily_review"]["rules"]],
           "patterns": ["surfaced", "not_surfaced"] + ["%s_ok" % q["id"] for q in cfg["patterns"]["pairs"]]
                       + ["%s_insufficient" % q["id"] for q in cfg["patterns"]["pairs"]],
           "ai_summary": ["ok", "patterns_in_prompt"] + list(AI_ERRORS)}
    return set("%s:%s" % (f, tag) for f, tags in req.items() for tag in tags)


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
            _cover(seen, got, doc["function"], cfg, where, problems)
        counts[name] = len(doc["cases"])
        if write and (changed or raw != dumps(doc)):
            with open(path, "w", encoding="utf-8") as fh:
                fh.write(dumps(doc))
        elif not write and raw != dumps(doc):
            problems.append("%s: not in canonical format (run --write)" % name)
    for tag in sorted(required_coverage(cfg) - seen):
        problems.append("coverage: no vector covers %s" % tag)
    total = sum(os.path.getsize(os.path.join(VECTORS, n)) for n in present)
    if total > 1_600_000:
        problems.append("test-vectors: %d bytes, keep under ~1.5 MB" % total)
    return counts


# ---------------------------------------------------------------------------------------------
# Portability lint
# ---------------------------------------------------------------------------------------------

def check_portability(problems):
    for name in sorted(dir(R)):
        obj = getattr(R, name)
        if isinstance(obj, re.Pattern):
            for issue in lint_pattern(obj.pattern, owned=True):
                problems.append("insights_reference.%s: %s" % (name, issue))
    src = open(os.path.join(HERE, "insights_reference.py"), encoding="utf-8").read()
    for pattern, what in (("import statistics", "statistics module"), ("from statistics", "statistics module"),
                          ("(?<![A-Za-z_.])round[(]", "built-in round() (banker's rounding)"),
                          ("math[.]fsum", "math.fsum (different summation)"), ("[.]isoformat[(]", "isoformat")):
        if re.search(pattern, src):
            problems.append("insights_reference.py uses %s" % what)


# ---------------------------------------------------------------------------------------------
# Copies and docs
# ---------------------------------------------------------------------------------------------

def check_copies(write, problems):
    for src in (CONFIG, PROMPT):
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
                problems.append("%s differs from shared/insights/%s (run --write)" % (rel, os.path.basename(src)))


def _pts(points):
    return ", ".join("%s → %+g" % (x, y) for x, y in points)


def render_doc(cfg):
    out = [BEGIN, "", "_Generated from `shared/insights/insights_config.json` by `scripts/insights_contract_check.py --write`. "
           "Do not edit by hand._", ""]
    out += ["### Methodology text (shown in the in-app \"How we calculate this\" sheets)", ""]
    for key in METHODOLOGY:
        block = cfg["methodology"][key]
        out += ["#### %s (`%s`)" % (block["title"], key), ""]
        for s in block["sections"]:
            out.append("- **%s.** %s" % (s["heading"], s["body"]))
        out.append("")
    out += ["### Metrics", "", "| Metric | iOS type | Android type | Direction | Unit | Window | Min points | SD floor | Recent |",
            "|---|---|---|---|---|---|---|---|---|"]
    for mid in METRICS:
        m = cfg["metrics"][mid]
        out.append("| `%s` | `%s` | %s | %s | %s | %d d | %d | %g | %s |" % (
            mid, m["health_type"]["ios"], "`%s`" % m["health_type"]["android"] if m["health_type"]["android"] else "—",
            m["direction"], m["unit"], m["window_days"], m["min_points"], m["sd_floor"], m["recent"]))
    rc = cfg["recovery"]
    out += ["", "### Recovery", "", "| Component | Weight | Scoring |", "|---|---|---|"]
    for c in rc["components"]:
        mode = {"higher": "50 + 20·z", "lower": "50 − 20·z", "sleep": "%g × (50 + 20·z) + %g × consistency" % (
            c.get("duration_share", 0), c.get("consistency_share", 0)),
            "band": "50 − 20·max(0, abs(z) − %g)" % c.get("tolerance_z", 0),
            "drop_only": "50 − 20·max(0, −z − %g)" % c.get("tolerance_z", 0)}[c["mode"]]
        out.append("| %s | %g | %s, clamped 0–100 |" % (c["id"], c["weight"], mode))
    lm = rc["load_modifier"]
    out += ["", "Load modifier: %d when yesterday's load category is high, %d when its ratio is above %g. "
            "Bands: %s." % (lm["high"], lm["very_high"], lm["very_high_ratio"], "; ".join(
                "≥ %d %s (%s)" % (b["min"], b["label"], b["recommendation"]) for b in rc["bands"])),
            "", "Source: %s" % rc["source"]]
    t = cfg["training_load"]
    out += ["", "### Training load", "", "Intensity = clamp(effort ÷ %g, %g, %g), %g without an effort. Category vs the "
            "%d-day mean: light < %g ≤ moderate ≤ %g < high; no load = none; a load after a zero mean = high." % (
                t["effort_divisor"], t["intensity_min"], t["intensity_max"], t["default_intensity"],
                t["mean_window_days"], t["light_below"], t["high_above"]), "", "Source: %s" % t["source"]]
    ha = cfg["health_age"]
    out += ["", "### Ayuvo Health Age markers", "", "| Marker | Weight | Method | Min days | Cap (years) |", "|---|---|---|---|---|"]
    for m in ha["markers"]:
        out.append("| %s | %d | %s | %d | ±%g |" % (m["label"], m["weight"], m["method"], m["min_days"], m["cap_years"]))
    out += ["", "Window %d days; equivalent ages clamped to %d–%d; total difference capped at ±%g years; pace over %d weeks "
            "(needs %d points): < %g improving, > %g declining." % (
                ha["window_days"], ha["age_min"], ha["age_max"], ha["total_cap_years"], ha["pace"]["weeks"],
                ha["pace"]["min_points"], ha["pace"]["improving_below"], ha["pace"]["declining_above"]), ""]
    out += ["#### Reference tables", ""]
    for m in ha["markers"]:
        out.append("**%s** (%s)" % (m["label"], m["unit"]))
        out.append("")
        if m["method"] == "age_norm":
            groups = m["tables"].items() if m["tables_by_kind"] else [("", m["tables"])]
            for kind, g in groups:
                for sex in ("male", "female"):
                    out.append("- %s%s: %s" % ("%s " % kind.upper() if kind else "", sex,
                                               ", ".join("age %s → %g" % (a, v) for a, v in g[sex])))
        for key, label in (("points", "value → years"), ("duration_points", "hours asleep → years"),
                           ("regularity_points", "midpoint SD (min) → years"), ("minutes_points", "min/week → years"),
                           ("active_share_points", "active-week share → years"), ("bmi_points", "BMI → years")):
            if key in m:
                out.append("- %s: %s" % (label, _pts(m[key])))
        if "body_fat_points" in m:
            for sex in ("male", "female"):
                out.append("- body fat %% (%s) → years: %s" % (sex, _pts(m["body_fat_points"][sex])))
        out += ["- Source: %s" % m["source"], ""]
    rv = cfg["daily_review"]
    out += ["### Daily Review", "", "| Area | Weight |", "|---|---|"]
    out += ["| %s | %d |" % (a["label"], a["weight"]) for a in rv["areas"]]
    out += ["", "| Rule | Category | Template |", "|---|---|---|"]
    out += ["| `%s` | %s | %s |" % (r["id"], r["category"], r["template"]) for r in rv["rules"]]
    pc = cfg["patterns"]
    out += ["", "### Patterns", "", "Window %d days, each group ≥ %d days, surfaced when |t| ≥ %g and |d| ≥ %g." % (
        pc["window_days"], pc["min_group"], pc["min_abs_t"], pc["min_abs_d"]), "",
        "| Pair | Exposure | Outcome | Lag (days) |", "|---|---|---|---|"]
    out += ["| `%s` | %s | %s | %d |" % (q["id"], q["exposure"], q["outcome"], q["lag_days"]) for q in pc["pairs"]]
    out += ["", "Source: %s" % pc["source"], "", "### Disclaimers", ""]
    out += ["- `%s`: %s" % (k, v) for k, v in sorted(cfg["disclaimers"].items())]
    out += ["", END]
    return "\n".join(out)


def check_doc(cfg, write, problems):
    if not os.path.exists(DOC):
        problems.append("docs/insights.md missing")
        return
    text = open(DOC, encoding="utf-8").read()
    i, j = text.find(BEGIN), text.find(END)
    if i < 0 or j < 0:
        problems.append("docs/insights.md: generated block markers missing")
        return
    new = text[:i] + render_doc(cfg) + text[j + len(END):]
    if new != text:
        if write:
            with open(DOC, "w", encoding="utf-8") as fh:
                fh.write(new)
        else:
            problems.append("docs/insights.md generated block is stale (run --write)")


# ---------------------------------------------------------------------------------------------
# Hand-computed unit tests
# ---------------------------------------------------------------------------------------------

class ReferenceTests(unittest.TestCase):
    cfg = R.load_config()

    def test_round_half_up_not_bankers(self):
        self.assertEqual((R.round_to(2.5, 0), R.round_to(0.125, 2), R.round_to(-2.5, 0)), (3.0, 0.13, -2.0))
        self.assertEqual(R.round_to(-0.004, 2), 0.0)

    def test_sample_sd_uses_n_minus_1(self):
        vals = [2.0, 4.0, 4.0, 4.0, 5.0, 5.0, 7.0, 9.0]
        self.assertAlmostEqual(R.sample_sd(vals, R.mean(vals)), 2.13809, places=5)

    def test_baseline_sd_floor(self):
        s = dict((R.add_days("2026-01-01", i), 55.0) for i in range(20))
        s["2026-01-21"] = 57.0
        b = R.baseline(s, "2026-01-21", self.cfg["metrics"]["resting_heart_rate"])
        self.assertEqual((b["status"], b["sd"], b["z"], b["sd_floored"]), ("ok", 0.0, 2.0, True))

    def test_inverse_age_vo2_male(self):
        table = self.cfg["health_age"]["markers"][0]["tables"]["male"]
        self.assertAlmostEqual(R.inverse_age(table, 40.0, 20, 80), 35 + 2.4 * 10 / 4.6, places=9)
        self.assertEqual(R.inverse_age(table, 70.0, 20, 80), 20.0)
        self.assertEqual(R.inverse_age(table, 5.0, 20, 80), 80.0)

    def test_interp_flat_beyond_ends(self):
        pts = [[0, 3.0], [150, 0.0]]
        self.assertEqual((R.interp(pts, -5), R.interp(pts, 75), R.interp(pts, 999)), (3.0, 1.5, 0.0))

    def test_overlap_merge(self):
        w = [{"start_ms": 0, "end_ms": 3600000, "effort": 10}, {"start_ms": 600000, "end_ms": 1800000, "effort": None}]
        s = R.sessions(w, "UTC", self.cfg)
        self.assertEqual((len(s), s[0]["minutes"], s[0]["intensity"], s[0]["load"]), (1, 60.0, 2.0, 120.0))

    def test_welch_t_and_cohens_d(self):
        # exposed [1,2,3], unexposed [4,5,6]: diff -3, var 1 each, se sqrt(2/3), t -3.674, d -3
        ex, un = [1.0, 2.0, 3.0], [4.0, 5.0, 6.0]
        se = (R.sample_sd(ex, 2.0) ** 2 / 3 + R.sample_sd(un, 5.0) ** 2 / 3) ** 0.5
        self.assertAlmostEqual(-3.0 / se, -3.6742346, places=6)

    def test_formatting(self):
        self.assertEqual((R.fmt_signed(-3.2, 0), R.fmt_signed(0.04, 1), R.fmt_signed(8.46, 0)), ("−3", "0", "+8"))
        self.assertEqual((R.fmt_duration(468), R.fmt_duration(45), R.fmt_number(8432.0), R.fmt_number(7.25)),
                         ("7h 48m", "45m", "8,432", "7.3"))

    def test_canonical_json(self):
        self.assertEqual(R.canonical_json({"b": 7.0, "a": [0.1, 2.345, None, "x\"\n"]}), '{"a":[0.1,2.35,null,"x\\"\\n"],"b":7}')

    def test_json_extraction(self):
        self.assertEqual(R.extract_json_object('```json\n{"a": "}"}\n```'), '{"a": "}"}')
        self.assertIsNone(R.extract_json_object("no braces"))

    def test_validator_numbers(self):
        payload = {"recovery": {"score": 72, "signals": [{"text": "Sleep 7h 48m", "impact": 3.14}]}}
        ok = R.validate_ai_output('{"headline":"Recovery 72 of 100","bullets":["You slept 7h 48m (3.1)."]}', payload, self.cfg)
        bad = R.validate_ai_output('{"headline":"Recovery 73","bullets":["x"]}', payload, self.cfg)
        self.assertEqual((ok["ok"], bad["errors"]), (True, ["unknown_number"]))

    def test_recovery_weights_and_health_age_weights(self):
        self.assertEqual(sum(c["weight"] for c in self.cfg["recovery"]["components"]), 100)
        self.assertEqual(sum(m["weight"] for m in self.cfg["health_age"]["markers"]), 100)


def main(argv):
    write = "--write" in argv
    problems = []
    cfg = R.load_config()
    lint_config(cfg, problems)
    check_prompts(problems)
    counts = check_vectors(cfg, write, problems)
    check_portability(problems)
    check_copies(write, problems)
    check_doc(cfg, write, problems)
    suite = unittest.defaultTestLoader.loadTestsFromTestCase(ReferenceTests)
    result = unittest.TextTestRunner(stream=open(os.devnull, "w"), verbosity=0).run(suite)
    for failed, trace in result.failures + result.errors:
        problems.append("unittest %s failed:\n%s" % (failed.id(), trace.strip().splitlines()[-1]))
    print("%d metrics, %d recovery components, %d Health Age markers, %d review rules, %d pattern pairs" % (
        len(cfg["metrics"]), len(cfg["recovery"]["components"]), len(cfg["health_age"]["markers"]),
        len(cfg["daily_review"]["rules"]), len(cfg["patterns"]["pairs"])))
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
