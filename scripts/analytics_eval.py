#!/usr/bin/env python3
"""Evaluate an Ayuvo analytics output against a reference measurement (docs/health-validation.md).

    python3 scripts/analytics_eval.py pairs.csv [--metric hrv_rmssd] [--json]

pairs.csv columns: prediction, reference, and optionally confidence (number in [0, 1] or a label) and id.
An empty prediction counts as a failure (the algorithm returned no value); an empty reference row is skipped.

Reports n, failure rate, coverage, MAE, RMSE, mean bias, median absolute error, SD of differences, Bland-Altman 95%
limits of agreement, Pearson r and MAE per confidence label. The agreement statistics reuse
scripts/vitals_reference.validation_stats so camera vitals and health analytics are scored identically.

Nothing here makes an accuracy claim on its own: an accuracy statement needs a documented reference device, protocol
and population (see docs/health-validation.md). Keep personal reference data out of the repository.
"""

import argparse
import csv
import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import vitals_reference as V  # noqa: E402


def _num(s):
    s = (s or "").strip()
    if not s:
        return None
    try:
        return float(s)
    except ValueError:
        return None


def evaluate(rows):
    """rows: [{prediction, reference, confidence}] -> statistics dict."""
    pairs, failures, skipped = [], 0, 0
    for r in rows:
        ref = r.get("reference")
        if ref is None:
            skipped += 1
            continue
        if r.get("prediction") is None:
            failures += 1
            continue
        conf = r.get("confidence")
        if not isinstance(conf, float):
            conf = None  # vitals_reference.confidence_label buckets numbers only
        pairs.append({"measured": r["prediction"], "reference": ref, "confidence": conf})
    cfg = V.load_config()
    out = V.validation_stats({"pairs": pairs, "failures": failures}, cfg)
    total = len(pairs) + failures
    out["coverage"] = V.round_to(len(pairs) / total, 3) if total else None
    out["skipped_no_reference"] = skipped
    out["median_ae"] = V.round_to(V.median([abs(p["measured"] - p["reference"]) for p in pairs]), 2) if pairs else None
    return out


def main(argv):
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[1])
    ap.add_argument("csv")
    ap.add_argument("--metric", default="metric")
    ap.add_argument("--json", action="store_true")
    a = ap.parse_args(argv)
    rows = []
    with open(a.csv, newline="", encoding="utf-8") as fh:
        for r in csv.DictReader(fh):
            c = r.get("confidence")
            rows.append({"prediction": _num(r.get("prediction")), "reference": _num(r.get("reference")),
                         "confidence": _num(c) if _num(c) is not None else (c or None)})
    res = evaluate(rows)
    if a.json:
        print(json.dumps({"metric": a.metric, "stats": res}, indent=2, sort_keys=True))
        return 0
    print("%s: n=%s failure_rate=%s coverage=%s" % (a.metric, res["n"], res["failure_rate"], res["coverage"]))
    for k in ("mae", "rmse", "bias", "median_ae", "sd", "loa_low", "loa_high", "r"):
        print("  %-10s %s" % (k, res[k]))
    for label, g in sorted(res["by_confidence"].items()):
        print("  confidence %-8s n=%d mae=%s" % (label, g["n"], g["mae"]))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
