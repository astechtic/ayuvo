#!/usr/bin/env python3
"""Ayuvo health analytics contract check (Python 3 stdlib only).

    python3 scripts/analytics_contract_check.py           # verify; exit 1 on any problem
    python3 scripts/analytics_contract_check.py --write   # rewrite vectors' "expected", the platform copies and the
                                                          # generated block of docs/health-analytics.md

Checks:
  1. shared/analytics/analytics_config.json lint: envelope, classifications and statuses, every algorithm registered
     with id@version, classification, function, inputs, output, quality, assumptions, fallback and references that
     exist and were verified; recovery weights and bands; metrics directions; copy rules (no diagnosis, no causal
     wording, no injury-risk claims).
  2. shared/health/source_policy.json lint against shared/health/metric_registry.json.
  3. shared/analytics/test-vectors/*.json: exact file set, envelope, unique names, allowed zones, canonical format,
     every "expected" equals scripts/analytics_reference.py; coverage tags (statuses, states, labels).
  4. Portability lint of the reference (no statistics module, no built-in round()/sum(), no fsum, no isoformat).
  5. Platform copies of the config and the source policy are byte-identical to shared/.
  6. docs/health-analytics.md generated block is up to date.
  7. Hand-computed unit tests.
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
SHARED = os.path.join(ROOT, "shared", "analytics")
VECTORS = os.path.join(SHARED, "test-vectors")
CONFIG = os.path.join(SHARED, "analytics_config.json")
POLICY = os.path.join(ROOT, "shared", "health", "source_policy.json")
REGISTRY = os.path.join(ROOT, "shared", "health", "metric_registry.json")
DERIVED = os.path.join(ROOT, "shared", "derived", "derived_config.json")
DOC = os.path.join(ROOT, "docs", "health-analytics.md")
COPY_DIRS = [
    os.path.join(ROOT, "android", "app", "src", "main", "assets", "analytics"),
    os.path.join(ROOT, "ios", "calorietracker", "Services", "Analytics", "Resources"),
]
BEGIN = "<!-- BEGIN GENERATED ANALYTICS -->"
END = "<!-- END GENERATED ANALYTICS -->"
sys.path.insert(0, HERE)
import analytics_reference as R  # noqa: E402

FORMAT = "ayuvo-analytics-vectors"
EXPECTED_FILES = dict((f + ".json", f) for f in R.FUNCTIONS)
ALLOWED_ZONES = frozenset(["America/New_York", "Europe/London", "Asia/Kolkata", "UTC"])
CLASSIFICATIONS = ("MEASURED", "PROVIDER_DERIVED", "SCIENTIFIC_DERIVED", "PERSONALIZED_STATISTICAL", "ML_PREDICTED",
                   "EXPERIMENTAL", "RESEARCH_ONLY")
STATUSES = ("VALID", "LOW_CONFIDENCE", "NO_DATA", "INSUFFICIENT_HISTORY", "INSUFFICIENT_DATA", "INVALID_INPUT",
            "EXPERIMENTAL", "RESEARCH_ONLY", "PROVIDER_REPORTED")
ALGO_KEYS = {"id", "version", "classification", "function", "inputs", "output", "quality", "assumptions", "fallback",
             "references"}
# Copy must describe, never diagnose, never claim causation or injury probability.
FORBIDDEN_COPY = ("diagnos", "infection", "illness", "disease", "covid", "you are sick", "caused", "causes your",
                  "because of your", "proves", "injury risk", "injury probability", "risk of injury",
                  "scientifically authorized", "clinically validated", "medical-grade")
ALLOWED_PHRASES = ("not a medical measurement or diagnosis",)


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


def _strings(node, out, path=""):
    if isinstance(node, str):
        out.append((path, node))
    elif isinstance(node, dict):
        for k, v in node.items():
            if k in ("references", "description"):
                continue
            _strings(v, out, path + "." + k)
    elif isinstance(node, list):
        for i, v in enumerate(node):
            _strings(v, out, "%s[%d]" % (path, i))


# ---------------------------------------------------------------------------------------------
# Config lint
# ---------------------------------------------------------------------------------------------

def lint_config(cfg, problems):
    if cfg.get("format") != "ayuvo-analytics-config" or cfg.get("config_version") != 1:
        problems.append("config: bad format/config_version")
    if tuple(sorted(cfg["classifications"])) != tuple(sorted(CLASSIFICATIONS)):
        problems.append("config: classifications must be exactly %s" % (CLASSIFICATIONS,))
    for k, v in cfg["classifications"].items():
        if not 0 < v["validity_cap"] <= 1:
            problems.append("classifications.%s: validity_cap must be in (0, 1]" % k)
    caps = [cfg["classifications"][c]["validity_cap"] for c in ("MEASURED", "PROVIDER_DERIVED", "ML_PREDICTED",
                                                                 "EXPERIMENTAL", "RESEARCH_ONLY")]
    if caps != sorted(caps, reverse=True):
        problems.append("classifications: validity caps must fall from MEASURED to RESEARCH_ONLY")
    if tuple(cfg["statuses"]) != STATUSES or set(cfg["status_rank"]) != set(STATUSES):
        problems.append("config: statuses / status_rank must list exactly %s" % (STATUSES,))
    if not 0 < cfg["tolerance"] <= 1e-6:
        problems.append("config: tolerance must be a small positive number")
    refs = cfg["references"]
    for rid, r in refs.items():
        if not r.get("citation") or r.get("verified") is not True:
            problems.append("references.%s: needs a citation and verified: true" % rid)
    ids, seen_fn = set(), set()
    for a in cfg["algorithms"]:
        w = "algorithms.%s" % a.get("id")
        if set(a) != ALGO_KEYS:
            problems.append("%s: keys must be %s" % (w, sorted(ALGO_KEYS)))
            continue
        if not re.match("^ayuvo[.][a-z0-9_.]+$", a["id"]) or a["id"] in ids:
            problems.append("%s: id must be unique and look like ayuvo.<area>" % w)
        ids.add(a["id"])
        if not isinstance(a["version"], int) or a["version"] < 1:
            problems.append("%s: version must be a positive integer" % w)
        if a["classification"] not in CLASSIFICATIONS:
            problems.append("%s: unknown classification" % w)
        if a["function"] not in R.FUNCTIONS:
            problems.append("%s: function %s not in the reference" % (w, a["function"]))
        seen_fn.add(a["function"])
        if not a["inputs"] or len(a["assumptions"]) < 20 or len(a["fallback"]) < 10:
            problems.append("%s: inputs / assumptions / fallback missing" % w)
        for r in a["references"]:
            if r not in refs:
                problems.append("%s: unknown reference %s" % (w, r))
    for needed in ("recovery", "anomaly", "baseline", "trend", "hrv_rr", "load", "hrr", "correlation", "response",
                   "energy", "forecast", "sleep_status", "sleep_need", "met_intensity", "vo2max_trend"):
        if needed not in seen_fn:
            problems.append("algorithms: %s is not registered" % needed)
    rc = cfg["recovery"]
    if rc["algorithm_version"] < 2 or any(a["id"] == rc["algorithm_id"] and a["version"] != rc["algorithm_version"]
                                          for a in cfg["algorithms"]):
        problems.append("recovery: algorithm_version must match the registry entry and be >= 2 (v1 is Insights)")
    for c in rc["components"]:
        if c["weight"] <= 0 or len(c.get("why", "")) < 30:
            problems.append("recovery.%s: positive weight and a 'why' sentence required" % c["id"])
        if c["mode"] != "sleep" and c["metric"] not in cfg["metrics"]:
            problems.append("recovery.%s: metric not in metrics" % c["id"])
        if c["id"] not in rc["drivers"]:
            problems.append("recovery.%s: no driver copy" % c["id"])
    parts = [c for c in rc["components"] if c["mode"] == "sleep"][0]["parts"]
    if abs(R.total(parts.values()) - 1.0) > 1e-12:
        problems.append("recovery.sleep.parts must sum to 1")
    if [b["min"] for b in rc["bands"]] != sorted([b["min"] for b in rc["bands"]], reverse=True) or rc["bands"][-1]["min"] != 0:
        problems.append("recovery.bands must descend to 0")
    for m_id, m in cfg["metrics"].items():
        if m["direction"] not in ("higher_better", "lower_better", "band") or m["classification"] not in CLASSIFICATIONS \
                or m["spread_floor"] <= 0:
            problems.append("metrics.%s: bad direction / classification / spread_floor" % m_id)
    for s in cfg["anomaly"]["signals"]:
        if s["metric"] not in cfg["metrics"] or s["bad"] not in ("low", "high", "band"):
            problems.append("anomaly.%s: bad signal" % s["id"])
    for w in cfg["baseline"]["windows"]:
        if str(w) not in cfg["baseline"]["min_points"] or cfg["baseline"]["min_points"][str(w)] > w:
            problems.append("baseline.min_points.%s missing or larger than the window" % w)
    dth = json.load(open(DERIVED, encoding="utf-8"))["thresholds"]
    if dth["tef_share"] != cfg["energy"]["tef_share"] or dth["mifflin"] != cfg["energy"]["mifflin"] \
            or dth["bmr_min_share"] != cfg["energy"]["bmr_min_share"]:
        problems.append("energy: tef_share / mifflin / bmr_min_share must equal shared/derived thresholds")
    for k, e in cfg["met"]["table"].items():
        if not re.match("^[0-9]{5}$", e["code"]) or not 1.0 <= e["met"] <= 25.0:
            problems.append("met.table.%s: needs a 5-digit Compendium code and a MET in [1, 25]" % k)
    f = cfg["forecast"]
    if abs(R.total(f["split"]) - 1.0) > 1e-12 or f["min_rows"] < 60 or not 0 < f["min_improvement"] < 1:
        problems.append("forecast: split must sum to 1, min_rows >= 60, min_improvement in (0, 1)")
    strings = []
    _strings(dict((k, v) for k, v in cfg.items() if k not in ("references", "algorithms", "met")), strings)
    for a in cfg["algorithms"]:
        _strings({"assumptions": a["assumptions"], "fallback": a["fallback"]}, strings, "algorithms." + a["id"])
    for path, s in strings:
        low = s.lower()
        if any(p in low for p in ALLOWED_PHRASES):
            low = low.replace(ALLOWED_PHRASES[0], "")
        for bad in FORBIDDEN_COPY:
            if re.search("(?<![a-z])" + re.escape(bad), low):
                problems.append("copy: forbidden phrase %r at %s" % (bad, path))


def lint_policy(problems):
    pol = json.load(open(POLICY, encoding="utf-8"))
    registry = dict((m["id"], m) for m in json.load(open(REGISTRY, encoding="utf-8"))["metrics"])
    if pol.get("format") != "ayuvo-source-policy" or pol.get("version") != 1:
        problems.append("source_policy: bad envelope")
    for m, strategy in pol["metrics"].items():
        if m not in registry:
            problems.append("source_policy: %s not in the Health registry" % m)
        elif registry[m]["kind"] not in ("discrete", "series"):
            problems.append("source_policy: %s is %s; only discrete types take a per-source policy" % (m, registry[m]["kind"]))
        if strategy not in ("single_best_source_per_day", "average_all"):
            problems.append("source_policy: unknown strategy %s" % strategy)


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
    if isinstance(got, dict):
        for k in ("status", "state", "label", "strategy", "reason"):
            if got.get(k) is not None:
                add(got[k])
        if f == "source_select" and got["dropped_duplicates"]:
            add("dedup")
        if f == "anomaly" and got["persistent"]:
            add("persistent")
        if f == "forecast" and got["deployed"]:
            add("deployed")
        if f == "correlation":
            for r in got["results"]:
                add(r["status"])
                if r["surfaced"]:
                    add("surfaced")
        if f == "recovery":
            for w in got["warnings"]:
                add("warning_" + w["code"])
            if got["load"] and got["load"]["modifier"] < 0:
                add("load_modifier")
            for d in got["drivers"]:
                if d["text_key"].endswith("_short"):
                    add("sleep_short_note")
        if f == "unit_convert" and not got["ok"]:
            add("unknown_pair")


REQUIRED = {
    "source_select": ["average_all", "single_best_source_per_day", "dedup"],
    "baseline": ["VALID", "LOW_CONFIDENCE", "INSUFFICIENT_HISTORY", "NO_DATA"],
    "trend": ["INSUFFICIENT_DATA", "STABLE", "IMPROVING", "DECLINING", "UNUSUAL", "CHANGING"],
    "hrv_rr": ["VALID", "NO_DATA", "INVALID_INPUT", "INSUFFICIENT_DATA"],
    "hrv_rr_day": ["VALID", "NO_DATA", "INSUFFICIENT_DATA"],
    "sleep_status": ["VALID", "LOW_CONFIDENCE", "NO_DATA"],
    "load": ["NO_DATA", "INSUFFICIENT_HISTORY", "LOAD_STABLE", "LOAD_SPIKE", "LOAD_HIGH", "LOAD_REDUCED",
             "LOAD_INCREASING"],
    "hrr": ["VALID", "LOW_CONFIDENCE", "NO_DATA", "INSUFFICIENT_DATA"],
    "recovery": ["VALID", "INSUFFICIENT_HISTORY", "no_sleep", "no_heart_data", "warning_camera",
                 "warning_overnight_fallback", "warning_missing", "warning_source_changed", "load_modifier",
                 "sleep_short_note"],
    "anomaly": ["NORMAL", "MILD_DEVIATION", "SIGNIFICANT_DEVIATION", "MULTI_SIGNAL_DEVIATION", "INSUFFICIENT_DATA",
                "persistent"],
    "correlation": ["VALID", "INSUFFICIENT_DATA", "surfaced"],
    "response": ["VALID", "INSUFFICIENT_DATA"],
    "energy": ["VALID", "INVALID_INPUT", "INSUFFICIENT_DATA", "NO_DATA"],
    "forecast": ["INSUFFICIENT_HISTORY", "deployed", "LOW_CONFIDENCE"],
    "unit_convert": ["unknown_pair"],
}


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
    total = 0
    for n in present:
        total += os.path.getsize(os.path.join(VECTORS, n))
    if total > 3_000_000:
        problems.append("test-vectors: %d bytes, keep under ~3 MB" % total)
    return counts


# ---------------------------------------------------------------------------------------------
# Portability, copies and docs
# ---------------------------------------------------------------------------------------------

def check_portability(problems):
    src = open(os.path.join(HERE, "analytics_reference.py"), encoding="utf-8").read()
    for pattern, what in (("import statistics", "statistics module"), ("from statistics", "statistics module"),
                          ("(?<![A-Za-z_.])round[(]", "built-in round() (banker's rounding)"),
                          ("(?<![A-Za-z_.])sum[(]", "built-in sum() (write the loop)"),
                          ("math[.]fsum", "math.fsum"), ("[.]isoformat[(]", "isoformat"),
                          ("math[.](atanh|tanh|erf)[(]", "math.atanh/tanh/erf (use the written-out forms)")):
        if re.search(pattern, src):
            problems.append("analytics_reference.py uses %s" % what)


def check_copies(write, problems):
    for src in (CONFIG, POLICY):
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
                problems.append("%s differs from %s (run --write)" % (rel, os.path.relpath(src, ROOT)))


def render_doc(cfg):
    refs = cfg["references"]
    out = [BEGIN, "", "_Generated from `shared/analytics/analytics_config.json` by `scripts/analytics_contract_check.py "
           "--write`. Do not edit by hand._", "", "Config version: %d. Numerical tolerance between the reference and the "
           "apps: %g." % (cfg["config_version"], cfg["tolerance"]), "", "### Algorithm registry", "",
           "| Algorithm | Version | Classification | Inputs | Quality gate | Fallback | References |", "|---|---|---|---|---|---|---|"]
    for a in cfg["algorithms"]:
        out.append("| `%s` | %d | %s | %s | %s | %s | %s |" % (
            a["id"], a["version"], a["classification"], "; ".join(a["inputs"]).replace("|", "\\|"),
            ", ".join("%s %s" % (k, v) for k, v in sorted(a["quality"].items())) or "—", a["fallback"].replace("|", "\\|"),
            ", ".join("[%s](#ref-%s)" % (r, r) for r in a["references"]) or "Ayuvo design"))
    out += ["", "### Classifications", "", "| Classification | Shown as | Confidence cap | Meaning |", "|---|---|---|---|"]
    for k in CLASSIFICATIONS:
        c = cfg["classifications"][k]
        out.append("| `%s` | %s | %g | %s |" % (k, c["label"], c["validity_cap"], c["about"]))
    out += ["", "### Recovery Indicator v%d weights (weights version %d)" % (cfg["recovery"]["algorithm_version"],
                                                                             cfg["recovery"]["weights_version"]), "",
            "| Component | Weight | Mode | Why |", "|---|---|---|---|"]
    for c in cfg["recovery"]["components"]:
        out.append("| %s | %g | %s | %s |" % (c["id"], c["weight"], c["mode"], c["why"]))
    out += ["", "### MET table (2024 Adult Compendium)", "", "| Activity key | Code | MET | Compendium description |",
            "|---|---|---|---|"]
    for k in sorted(cfg["met"]["table"]):
        e = cfg["met"]["table"][k]
        out.append("| `%s` | %s | %g | %s |" % (k, e["code"], e["met"], e["description"].replace("|", "\\|")))
    out += ["", "### References", ""]
    for rid in sorted(refs):
        r = refs[rid]
        link = (" doi:[%s](https://doi.org/%s)" % (r["doi"], r["doi"])) if r.get("doi") else (
            " %s" % r["url"] if r.get("url") else "")
        pm = (" PMID %s." % r["pmid"]) if r.get("pmid") else ""
        out.append("- <a id=\"ref-%s\"></a>**%s** — %s%s%s  \n  _Supports:_ %s  \n  _Limitations:_ %s" % (
            rid, rid, r["citation"], link, pm, r.get("supports") or "—", r.get("limitations") or "—"))
    out += ["", END]
    return "\n".join(out)


def check_doc(cfg, write, problems):
    if not os.path.exists(DOC):
        problems.append("docs/health-analytics.md missing")
        return
    text = open(DOC, encoding="utf-8").read()
    i, j = text.find(BEGIN), text.find(END)
    if i < 0 or j < 0:
        problems.append("docs/health-analytics.md: generated block markers missing")
        return
    new = text[:i] + render_doc(cfg) + text[j + len(END):]
    if new != text:
        if write:
            with open(DOC, "w", encoding="utf-8") as fh:
                fh.write(new)
        else:
            problems.append("docs/health-analytics.md generated block is stale (run --write)")


# ---------------------------------------------------------------------------------------------
# Hand-computed unit tests
# ---------------------------------------------------------------------------------------------

class ReferenceTests(unittest.TestCase):
    cfg = R.load_config()

    def test_round_half_up(self):
        self.assertEqual((R.round_to(2.5, 0), R.round_to(0.125, 2), R.round_to(-0.004, 2)), (3.0, 0.13, 0.0))

    def test_median_mad_percentile(self):
        self.assertEqual((R.median([3, 1, 2]), R.mad([1, 2, 3, 4, 100])), (2, 1))
        self.assertAlmostEqual(R.percentile([1, 2, 3, 4], 50), 2.5)
        self.assertAlmostEqual(R.percentile([10, 20], 90), 19.0)

    def test_robust_z_ignores_outlier(self):
        s = dict((R.add_days("2026-03-01", i), 60.0 + (i % 3)) for i in range(28))
        s["2026-03-10"] = 200.0
        s["2026-03-29"] = 61.0
        b = R.baseline({"series": s, "day": "2026-03-29", "metric": "hrv", "window": 28}, self.cfg)
        self.assertEqual(b["median"], 61.0)
        self.assertEqual(b["z"], 0.0)

    def test_rmssd_by_hand(self):
        vals = [1000.0, 1010.0, 990.0, 1000.0] * 10
        ib = [[v, False] for v in vals]
        r = R.hrv_rr({"ibis": ib}, self.cfg)
        # 39 diffs: nine cycles of 10, -20, 10, 0 plus 10, -20, 10 -> sum of squares 6000
        self.assertAlmostEqual(r["rmssd"], R.round_to(math.sqrt(6000.0 / 39.0), 2))

    def test_theil_sen(self):
        b, a, _ = R.theil_sen([(0, 0), (1, 2), (2, 4), (3, 6), (4, 100)])
        self.assertEqual(b, 2.0)

    def test_spearman_ties(self):
        self.assertEqual(R.ranks([5, 1, 5, 3]), [3.5, 1.0, 3.5, 2.0])

    def test_bh(self):
        q = R.bh_qvalues([0.01, 0.04, 0.03, 0.2])
        self.assertAlmostEqual(q[0], 0.04)
        self.assertAlmostEqual(q[1], 0.0533333333, places=8)

    def test_normal_cdf(self):
        self.assertAlmostEqual(R.normal_cdf(1.959964), 0.975, places=6)
        self.assertAlmostEqual(R.normal_cdf(0.0), 0.5, places=7)

    def test_cholesky(self):
        x = R.cholesky_solve([[4.0, 2.0], [2.0, 3.0]], [2.0, 1.0])
        self.assertAlmostEqual(x[0], 0.5)
        self.assertAlmostEqual(x[1], 0.0)

    def test_fnv(self):
        self.assertEqual(R.fnv1a64(""), "cbf29ce484222325")
        self.assertEqual(R.fnv1a64("a"), "af63dc4c8601ec8c")

    def test_ewma_lambda(self):
        self.assertAlmostEqual(2.0 / (7 + 1.0), 0.25)

    def test_missing_is_not_zero(self):
        b = R.baseline({"series": {}, "day": "2026-03-15", "metric": "steps", "window": 7}, self.cfg)
        self.assertEqual((b["status"], b["value"], b["median"]), ("INSUFFICIENT_HISTORY", None, None))

    def test_energy_no_double_count(self):
        r = R.energy({"provider_basal_kcal": 1700.0, "provider_active_kcal": 500.0,
                      "ayuvo_sessions": [{"start_ms": 0, "end_ms": 10, "kcal": 300.0}],
                      "provider_workouts": [{"start_ms": 5, "end_ms": 20}], "profile": {}}, self.cfg)
        self.assertEqual(r["active_kcal"], 500.0)


def main(argv):
    write = "--write" in argv
    problems = []
    cfg = R.load_config()
    lint_config(cfg, problems)
    lint_policy(problems)
    counts = check_vectors(cfg, write, problems)
    check_portability(problems)
    check_copies(write, problems)
    check_doc(cfg, write, problems)
    suite = unittest.defaultTestLoader.loadTestsFromTestCase(ReferenceTests)
    result = unittest.TextTestRunner(stream=open(os.devnull, "w"), verbosity=0).run(suite)
    for failed, trace in result.failures + result.errors:
        problems.append("unittest %s failed:\n%s" % (failed.id(), trace.strip().splitlines()[-1]))
    print("%d analytics algorithms, %d references" % (len(cfg["algorithms"]), len(cfg["references"])))
    n = 0
    for name, c in sorted(counts.items()):
        n += c
        print("%-40s %3d cases" % ("test-vectors/" + name, c))
    print("%d cases, %d unit tests" % (n, result.testsRun))
    if problems:
        print("\n%d problem(s):" % len(problems))
        for p in problems:
            print(" - " + p)
        return 1
    print("OK")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
