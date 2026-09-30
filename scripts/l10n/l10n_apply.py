#!/usr/bin/env python3
"""Write translated batches back into both apps (docs/localization.md).

  python3 scripts/l10n/l10n_apply.py --work DIR [--lang de]

Reads DIR/<lang>/autofill.json and every batch_*.out.json, turns ⟦n⟧ tokens
back into each platform's placeholders, validates, and writes:
  iOS      the unit's .xcstrings, state "needs_review"
  Android  values-<q>/<same file as English>.xml, plus shared/l10n/review/<lang>.json
Rejected items are listed in DIR/<lang>/rejects.json for re-translation.
"""
import argparse
import json
import os
import sys
from collections import defaultdict

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from l10n_lib import (LANGS, ROOT, SEPARATE, SHARED_L10N, normalize_android, all_units, check_value, detokenise,  # noqa: E402
                      load_xcstrings, save_xcstrings, set_ios_translation, source_hash, tm_id,
                      tm_key, write_android_values)


def load_outputs(folder):
    values = {}
    path = os.path.join(folder, "autofill.json")
    if os.path.exists(path):
        with open(path, encoding="utf-8") as fh:
            values.update(json.load(fh))
    for name in sorted(os.listdir(folder)):
        if name.endswith(".out.json"):
            with open(os.path.join(folder, name), encoding="utf-8") as fh:
                values.update(json.load(fh))
    return values


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--work", required=True)
    ap.add_argument("--lang", action="append")
    ap.add_argument("--refresh", action="store_true",
                    help="also rewrite translations still in needs_review (e.g. after a batch was revised)")
    args = ap.parse_args()
    langs = [l for l in (args.lang or list(LANGS)) if os.path.isdir(os.path.join(args.work, l))]
    units = all_units()

    catalogs = {}
    for lang in langs:
        folder = os.path.join(args.work, lang)
        values = load_outputs(folder)
        android = defaultdict(list)
        rejects = {}
        written = 0
        ledger_path = os.path.join(SHARED_L10N, "review", f"{lang}.json")
        ledger = {}
        if os.path.exists(ledger_path):
            with open(ledger_path, encoding="utf-8") as fh:
                ledger = json.load(fh)
        for u in units:
            current, state = u.translations[lang]
            if not check_value(u, lang, current) and state != "new" \
                    and not (args.refresh and state == "needs_review"):
                continue
            ident = tm_id(tm_key(u))
            if ident not in values:
                continue
            try:
                value = detokenise(u, lang, values[ident])
            except (ValueError, TypeError, AttributeError) as err:
                rejects[ident] = f"{u.platform}:{u.key}: {err}"
                continue
            problems = check_value(u, lang, value)
            if problems:
                rejects[ident] = f"{u.platform}:{u.key}: {', '.join(problems)}"
                continue
            if u.platform == "ios":
                path = os.path.join(ROOT, u.file)
                if path not in catalogs:
                    catalogs[path] = load_xcstrings(path)
                set_ios_translation(catalogs[path]["strings"][u.key], lang, value)
            else:
                android[u.file].append((u.key, u.kind, value))
                ledger[u.key] = {"source": source_hash(u.english), "state": "needs_review"}
                if f"android:{u.key}" in SEPARATE:
                    ledger[u.key]["separate"] = True
            written += 1
        for file_name, entries in android.items():
            write_android_values(f"values-{LANGS[lang]}", file_name, entries)
        if android:
            os.makedirs(os.path.dirname(ledger_path), exist_ok=True)
            with open(ledger_path, "w", encoding="utf-8") as fh:
                json.dump(dict(sorted(ledger.items())), fh, ensure_ascii=False, indent=1)
                fh.write("\n")
        with open(os.path.join(folder, "rejects.json"), "w", encoding="utf-8") as fh:
            json.dump(rejects, fh, ensure_ascii=False, indent=1)
        print(f"{lang}: wrote {written}, rejected {len(rejects)}")
    for path, catalog in catalogs.items():
        save_xcstrings(path, catalog)
    print(f"android lint hygiene: {normalize_android()} files updated")


if __name__ == "__main__":
    main()
