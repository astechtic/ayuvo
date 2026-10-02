#!/usr/bin/env python3
"""Ayuvo camera vitals (finger PPG / face rPPG) contract check (Python 3 stdlib only).

    python3 scripts/vitals_contract_check.py           # verify; exit 1 on any problem
    python3 scripts/vitals_contract_check.py --write   # rewrite vectors' "expected", the platform copies and the doc block

Checks: config lint (bands, classifications, text keys, sources), vectors equal scripts/vitals_reference.py on their
input with coverage tags, vector folder size, portability lint, byte-identical platform copies of
shared/vitals/vitals_config.json, and the generated block in docs/camera-vitals.md.
"""

import glob
import json
import os
import re
import sys
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
SHARED = os.path.join(ROOT, "shared", "vitals")
VECTORS = os.path.join(SHARED, "test-vectors")
CONFIG = os.path.join(SHARED, "vitals_config.json")
DOC = os.path.join(ROOT, "docs", "camera-vitals.md")
COPY_DIRS = [
    os.path.join(ROOT, "android", "app", "src", "main", "assets", "vitals"),
    os.path.join(ROOT, "ios", "calorietracker", "Services", "Vitals", "Resources"),
]
sys.path.insert(0, HERE)
import vitals_reference as R  # noqa: E402
from derived_contract_check import _first_diff, dumps  # noqa: E402

FORMAT = "ayuvo-vitals-vectors"
EXPECTED_FILES = dict((f + ".json", f) for f in R.FUNCTIONS)
CLASSES = ("measured", "calculated", "estimated", "experimental", "research")
MAX_VECTOR_BYTES = 1024 * 1024
REQUIRED = {
    "analyze_finger": ["valid_hr", "valid_hrv", "valid_resp", "freq_domain", "reject_no_finger", "reject_motion",
                       "reject_duration_short", "reject_pressure", "reject_low_quality", "masked", "spo2_valid", "spo2_needs_calibration",
                       "bp_valid", "experimental_off"],
    "analyze_face": ["valid_hr", "valid_hrv", "reject_motion", "reject_no_face", "reject_lighting", "reject_duration_short",
                     "low_quality", "masked", "subset_rois"],
    "spo2_estimate": ["valid", "needs_calibration", "outside_calibration", "low_quality"],
    "bp_research": ["valid", "needs_calibration", "outside_calibration", "few_beats"],
    "scan_control": ["continue", "extend", "finish"],
    "compare": ["consistent", "inconsistent", "incomplete"],
    "scan_indicator": ["low", "typical", "high", "short_history", "few_beats"],
    "pulses": ["hrv", "rejected_ibi", "no_peaks"],
    "resp_rate": ["value", "disagree"],
    "hrv_freq": ["value", "none"],
    "baselines": ["window", "empty"],
    "validation_stats": ["stats", "insufficient"],
    "effective_config": ["override", "none"],
    "finger_detect": ["ok", "no_finger", "pressure"],
    "insights_fallback": ["filled", "empty"],
}


def lint_config(cfg, problems):
    if cfg.get("format") != "ayuvo-vitals-config" or cfg.get("config_version") != 1:
        problems.append("config: bad format/config_version")
    if set(cfg["classifications"]) != set(CLASSES):
        problems.append("classifications must be %s" % ", ".join(CLASSES))
    lo, hi = cfg["signal"]["hr_band_hz"]
    if not 0.5 <= lo < hi <= 4.0:
        problems.append("signal.hr_band_hz outside 30-240 bpm")
    rlo, rhi = cfg["respiration"]["band_hz"]
    if not 0.05 <= rlo < rhi <= 1.0:
        problems.append("respiration.band_hz implausible")
    sc = cfg["scan"]
    if not sc["min_s"] <= sc["target_s"] < sc["max_s"]:
        problems.append("scan: need min_s <= target_s < max_s")
    w = 0.0
    for v in cfg["quality"]["weights"].values():
        w += v
    if abs(w - 1.0) > 1e-9:
        problems.append("quality.weights must sum to 1")
    ids = set()
    for m in cfg["metrics"]:
        ids.add(m["id"])
        if m["classification"] not in CLASSES:
            problems.append("metrics.%s: unknown classification" % m["id"])
        if not m.get("title") or not m.get("unit"):
            problems.append("metrics.%s: needs title and unit" % m["id"])
    for needed, cls in (("heart_rate", "measured"), ("spo2", "experimental"), ("blood_pressure", "research")):
        if needed not in ids:
            problems.append("metrics: missing %s" % needed)
        elif R.metric_class(cfg, needed)["classification"] != cls:
            problems.append("metrics.%s must be classified %s (PPG.md §31)" % (needed, cls))
    for name in cfg["face"]["methods"]:
        if name not in ("green", "normalized", "chrom", "pos", "pca", "ica"):
            problems.append("face.methods: unknown %s" % name)
    if cfg["face"]["methods"][0] != "green":
        problems.append("face.methods: green must come first (sign reference)")
    for k in ("chrom", "pos", "pca", "ica", "elgendi", "hrv", "lomb", "resp", "spo2", "sqi"):
        if len(cfg["sources"].get(k, "")) < 20:
            problems.append("sources.%s missing" % k)
    banned = ("never leaves", "stays on your device", "caused by", "proves", "you are stressed")
    texts = [cfg["disclaimer"]] + list(cfg["guidance"].values()) + list(cfg["reasons"].values())
    for t in texts:
        low = t.lower()
        for b in banned:
            if b in low:
                problems.append("copy uses banned phrase %r: %s" % (b, t))
        if "diagnos" in low and "not a medical measurement or diagnosis" not in low:
            problems.append("copy mentions diagnosis outside the disclaimer: %s" % t)
    reasons_used = ("duration_short", "experimental_off", "face_not_supported", "few_beats", "lighting", "low_quality",
                    "motion", "needs_calibration", "no_face", "no_finger", "outside_calibration", "research_off",
                    "resp_disagree", "short_history", "pressure")
    for r in reasons_used:
        if r not in cfg["reasons"]:
            problems.append("reasons.%s missing" % r)


def _cover(seen, f, got):
    def add(tag):
        seen.add("%s:%s" % (f, tag))
    if f in ("analyze_finger", "analyze_face"):
        m = got["metrics"]
        if got["reject_reason"] is not None:
            rr = got["reject_reason"]
            add("reject_" + ("no_face" if rr == "no_face" else rr))
            return
        if m["heart_rate"]["status"] == "valid":
            add("valid_hr")
        elif f == "analyze_face":
            add("low_quality")
        else:
            add("reject_low_quality")
        if m["hrv_rmssd"]["status"] == "valid":
            add("valid_hrv")
        if m["respiratory_rate"]["status"] == "valid":
            add("valid_resp")
        if m["hrv_lf_hf"]["status"] == "valid":
            add("freq_domain")
        if got["quality"].get("masked_fraction", 0) > 0:
            add("masked")
        if f == "analyze_finger":
            sp = m["spo2"]
            if sp["status"] == "valid":
                add("spo2_valid")
            elif sp["reason"] == "needs_calibration":
                add("spo2_needs_calibration")
            elif sp["reason"] == "experimental_off":
                add("experimental_off")
            if m["blood_pressure"]["status"] == "valid":
                add("bp_valid")
        else:
            if len(got["quality"]["rois"]) < len(got["quality"]["method_snr_db"]):
                add("subset_rois")
    elif f == "spo2_estimate":
        add("valid" if got["value"] is not None else got["reason"])
    elif f == "bp_research":
        add("valid" if got["sbp"] is not None else got["reason"])
    elif f == "scan_control":
        add(got["action"])
    elif f == "compare":
        add(got["status"])
    elif f == "scan_indicator":
        add(got["band"] if got["band"] is not None else got["reason"])
    elif f == "pulses":
        if got["hrv"] is not None:
            add("hrv")
        if 0.0 in got["ibi_quality"]:
            add("rejected_ibi")
        if not got["peaks_ms"]:
            add("no_peaks")
    elif f == "resp_rate":
        add("value" if got["value"] is not None else "disagree")
    elif f == "hrv_freq":
        add("value" if got is not None else "none")
    elif f == "baselines":
        for v in got.values():
            add("window" if v is not None else "empty")
    elif f == "validation_stats":
        add("stats" if got["mae"] is not None else "insufficient")
    elif f == "effective_config":
        add("override" if got["overridden"] else "none")
    elif f == "insights_fallback":
        for days in got.values():
            add("filled" if days else "empty")
    elif f == "finger_detect":
        for s in got:
            add(s)


def check_vectors(cfg, write, problems):
    counts, seen = {}, set()
    present = sorted(os.path.basename(p) for p in glob.glob(os.path.join(VECTORS, "*.json")))
    for name in sorted(set(EXPECTED_FILES) - set(present)):
        problems.append("test-vectors/%s missing" % name)
    size = 0
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
        body = dumps(doc)
        size += len(body.encode("utf-8"))
        if write and (changed or raw != body):
            open(path, "w", encoding="utf-8").write(body)
        elif not write and raw != body:
            problems.append("%s: not in canonical format (run --write)" % name)
    if size > MAX_VECTOR_BYTES:
        problems.append("test-vectors total %d bytes > %d" % (size, MAX_VECTOR_BYTES))
    for f, tags in sorted(REQUIRED.items()):
        for t in tags:
            if "%s:%s" % (f, t) not in seen:
                problems.append("coverage: no vector covers %s:%s" % (f, t))
    return counts


def check_portability(problems):
    src = open(os.path.join(HERE, "vitals_reference.py"), encoding="utf-8").read()
    for pattern, what in (("import statistics", "statistics module"), ("(?<![A-Za-z_.])round[(]", "built-in round()"),
                          ("(?<![A-Za-z_.])sum[(]", "built-in sum()"), ("math[.]fsum", "math.fsum"),
                          ("import numpy", "numpy"), ("[.]isoformat", "isoformat")):
        if re.search(pattern, src):
            problems.append("vitals_reference.py uses %s" % what)


def check_copies(write, problems):
    body = open(CONFIG, encoding="utf-8").read()
    for folder in COPY_DIRS:
        path = os.path.join(folder, "vitals_config.json")
        current = open(path, encoding="utf-8").read() if os.path.exists(path) else None
        if current == body:
            continue
        if write:
            os.makedirs(folder, exist_ok=True)
            open(path, "w", encoding="utf-8").write(body)
        else:
            problems.append("%s differs from shared/vitals/vitals_config.json (run --write)" % os.path.relpath(path, ROOT))


BEGIN, END = "<!-- BEGIN GENERATED VITALS -->", "<!-- END GENERATED VITALS -->"


def generated_block(cfg):
    lines = [BEGIN, "", "| Metric | Unit | Classification | Finger | Face |", "|---|---|---|---|---|"]
    for m in cfg["metrics"]:
        face = "no" if m["id"] in ("spo2", "blood_pressure") else "yes"
        lines.append("| %s | %s | %s | yes | %s |" % (m["title"], m["unit"], cfg["classifications"][m["classification"]]["label"], face))
    lines += ["", "| Reason key | Text |", "|---|---|"]
    for k in sorted(cfg["reasons"]):
        lines.append("| `%s` | %s |" % (k, cfg["reasons"][k]))
    lines += ["", "Sources:", ""]
    for k in sorted(cfg["sources"]):
        lines.append("- **%s**: %s" % (k, cfg["sources"][k]))
    lines += ["", END]
    return "\n".join(lines)


def check_doc(cfg, write, problems):
    if not os.path.exists(DOC):
        problems.append("docs/camera-vitals.md missing")
        return
    text = open(DOC, encoding="utf-8").read()
    if BEGIN not in text or END not in text:
        problems.append("docs/camera-vitals.md: generated markers missing")
        return
    start, end = text.index(BEGIN), text.index(END) + len(END)
    new = text[:start] + generated_block(cfg) + text[end:]
    if new != text:
        if write:
            open(DOC, "w", encoding="utf-8").write(new)
        else:
            problems.append("docs/camera-vitals.md generated block is stale (run --write)")


class ReferenceTests(unittest.TestCase):
    cfg = R.load_config()

    def test_round_half_up(self):
        self.assertEqual(R.round_to(2.5, 0), 3.0)
        self.assertEqual(R.round_to(-0.0001, 2), 0.0)

    def test_moving_average_edges(self):
        self.assertEqual(R.moving_average([1.0, 2.0, 3.0, 4.0], 3), [1.5, 2.0, 3.0, 3.5])

    def test_lcg_first_value(self):
        self.assertEqual(R.Lcg(1).uniform(), ((1103515245 + 12345) % 2147483648) / 2147483648.0)

    def test_rmssd_by_hand(self):
        ibi = {"ibi_ms": [800.0, 820.0, 790.0, 810.0], "accepted": [True, True, True, True]}
        h = R.hrv_time(ibi)
        self.assertAlmostEqual(h["rmssd"], ((400 + 900 + 400) / 3.0) ** 0.5)

    def test_bandpass_keeps_pulse_band(self):
        import math
        fs = 30
        x = [math.sin(2 * math.pi * 1.2 * k / fs) for k in range(600)]
        y = R.bandpass(x, fs, 0.7, 3.5)
        # 2nd-order HP at 0.7 Hz and LP at 3.5 Hz, applied twice: |H(1.2 Hz)|^2 = 0.885
        self.assertAlmostEqual(R.std(y[100:500]) / R.std(x[100:500]), 0.885, places=2)

    def test_jacobi_reconstructs(self):
        vals, vecs = R.jacobi_eigen([[2.0, 1.0, 0.0], [1.0, 2.0, 0.0], [0.0, 0.0, 1.0]])
        self.assertAlmostEqual(vals[0], 3.0)
        self.assertAlmostEqual(vals[2], 1.0)


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
