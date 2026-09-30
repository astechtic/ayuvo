#!/usr/bin/env python3
"""Localization coverage for both apps (docs/localization.md).

  python3 scripts/l10n/l10n_report.py            # summary table
  python3 scripts/l10n/l10n_report.py --detail   # plus every problem
  python3 scripts/l10n/l10n_report.py --json     # machine-readable
  python3 scripts/l10n/l10n_report.py --strict   # exit 1 unless 100%
"""
import argparse
import json
import os
import sys
from collections import defaultdict

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from l10n_lib import LANGS, all_units, check_value  # noqa: E402


def collect(platforms):
    rows = defaultdict(lambda: {"total": 0, "translated": 0, "needs_review": 0, "problems": []})
    for unit in all_units(platforms):
        for lang in LANGS:
            row = rows[(unit.platform, unit.file, lang)]
            row["total"] += 1
            value, state = unit.translations[lang]
            problems = check_value(unit, lang, value)
            if state == "new":
                problems.append("state new")
            if problems:
                row["problems"].append((unit.key, problems))
                continue
            row["translated"] += 1
            if state == "needs_review":
                row["needs_review"] += 1
    return rows


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--platform", choices=["ios", "android"], action="append")
    ap.add_argument("--json", action="store_true")
    ap.add_argument("--detail", action="store_true")
    ap.add_argument("--strict", action="store_true")
    args = ap.parse_args()
    rows = collect(tuple(args.platform or ("ios", "android")))

    if args.json:
        print(json.dumps([{"platform": p, "file": f, "lang": l, **{k: v for k, v in r.items() if k != "problems"},
                           "problems": len(r["problems"])} for (p, f, l), r in sorted(rows.items())], indent=1))
    else:
        by_file = defaultdict(dict)
        for (p, f, l), r in rows.items():
            by_file[(p, f)][l] = r
        langs = list(LANGS)
        print(f"{'platform/file':58} " + " ".join(f"{l[:5]:>5}" for l in langs))
        for (p, f), per in sorted(by_file.items()):
            total = next(iter(per.values()))["total"]
            cells = []
            for l in langs:
                r = per.get(l)
                pct = 100 * r["translated"] / r["total"] if r and r["total"] else 100
                cells.append(f"{pct:5.0f}")
            print(f"{(p + ' ' + os.path.basename(f) + f' ({total})')[:58]:58} " + " ".join(cells))
        grand = sum(r["total"] for r in rows.values())
        done = sum(r["translated"] for r in rows.values())
        review = sum(r["needs_review"] for r in rows.values())
        print(f"\nTOTAL {done}/{grand} = {100 * done / max(grand, 1):.2f}%  (needs_review: {review})")
        if args.detail:
            for (p, f, l), r in sorted(rows.items()):
                for key, problems in r["problems"]:
                    if problems != ["missing"]:
                        print(f"{p} {os.path.basename(f)} {l} {key!r}: {', '.join(problems)}")

    if args.strict and any(r["problems"] for r in rows.values()):
        sys.exit(1)


if __name__ == "__main__":
    main()
