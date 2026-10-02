#!/usr/bin/env python3
"""Offline validation of camera vitals against reference instruments (docs/camera-vitals.md §8).

    python3 scripts/vitals_eval.py ayuvo-vitals-validation.json [--json]

Input: the "Export validation dataset" file from Settings > Camera measurements > Validation:

    {"format": "ayuvo-vitals-validation", "version": 1, "scans": [
       {"id", "mode": "finger_ppg"|"face_rppg", "device_model", "quality", "metrics": {metric_id: envelope},
        "reference": {"heart_rate": 66, "hrv_rmssd": 41, "respiratory_rate": 14, "spo2": 98,
                      "blood_pressure": [118, 76], "device": "Polar H10"}}, ...]}

For every mode x metric with a reference, prints MAE, RMSE, bias, limits of agreement, Pearson r, failure rate (scan
had a reference but no valid value) and MAE per confidence label, using vitals_reference.validation_stats so the
numbers match the in-app validation screen.
"""

import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import vitals_reference as R  # noqa: E402

METRICS = ("heart_rate", "ibi_mean", "hrv_rmssd", "hrv_sdnn", "respiratory_rate", "spo2", "blood_pressure")


def evaluate(doc, cfg):
    if doc.get("format") != "ayuvo-vitals-validation":
        raise ValueError("not an ayuvo-vitals-validation file")
    out = {}
    for mode in ("finger_ppg", "face_rppg"):
        for metric in METRICS:
            pairs, failures = [], 0
            for scan in doc["scans"]:
                ref = (scan.get("reference") or {}).get(metric)
                if scan["mode"] != mode or ref is None:
                    continue
                env = scan["metrics"].get(metric) or {}
                if env.get("status") != "valid" or env.get("value") is None:
                    failures += 1
                    continue
                reference = ref[0] if isinstance(ref, list) else ref
                pairs.append({"measured": env["value"], "reference": reference, "confidence": env.get("confidence")})
            if pairs or failures:
                out["%s/%s" % (mode, metric)] = R.validation_stats({"pairs": pairs, "failures": failures}, cfg)
    return out


def main(argv):
    if not argv:
        print(__doc__)
        return 2
    with open(argv[0], encoding="utf-8") as fh:
        doc = json.load(fh)
    result = evaluate(doc, R.load_config())
    if "--json" in argv:
        print(json.dumps(result, indent=2, sort_keys=True))
        return 0
    print("%-32s %4s %7s %7s %7s %17s %6s %6s" % ("mode/metric", "n", "MAE", "RMSE", "bias", "LoA", "r", "fail"))
    for key in sorted(result):
        s = result[key]
        loa = "" if s.get("loa_low") is None else "%.1f..%.1f" % (s["loa_low"], s["loa_high"])
        fmt = lambda v: "-" if v is None else "%.2f" % v  # noqa: E731
        print("%-32s %4d %7s %7s %7s %17s %6s %6s" % (key, s["n"], fmt(s.get("mae")), fmt(s.get("rmse")),
                                                     fmt(s.get("bias")), loa, fmt(s.get("r")),
                                                     fmt(s.get("failure_rate"))))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
