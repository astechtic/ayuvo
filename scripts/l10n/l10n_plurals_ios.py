#!/usr/bin/env python3
"""Give iOS count strings real English plural forms (docs/localization.md).

  python3 scripts/l10n/l10n_plurals_ios.py FILE [FILE ...] [--write]

Each FILE has one line per key: `key<TAB>English one<TAB>English other`, e.g.
`%lld days\t%lld day\t%lld days`. For every catalog that contains the key (run
l10n_sync_ios.py first so new keys exist), the English entry becomes a plural
variation. A translation that is still a single string for a language whose
plural rules need more than "other" is dropped, so l10n_report.py asks for real
plural forms.
"""
import argparse
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from l10n_lib import PLURAL_CATEGORIES, ios_catalogs, load_xcstrings, save_xcstrings  # noqa: E402


def read_keys(paths):
    keys = {}
    for path in paths:
        with open(path, encoding="utf-8") as fh:
            for line in fh:
                parts = line.rstrip("\n").split("\t")
                if len(parts) == 3 and parts[0]:
                    keys[parts[0]] = {"one": parts[1], "other": parts[2]}
    return keys


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("files", nargs="+")
    ap.add_argument("--write", action="store_true")
    args = ap.parse_args()
    keys = read_keys(args.files)
    found = set()
    for path in ios_catalogs():
        catalog = load_xcstrings(path)
        changed = False
        for key, forms in keys.items():
            entry = catalog["strings"].get(key)
            if entry is None:
                continue
            found.add(key)
            locs = entry.setdefault("localizations", {})
            locs["en"] = {"variations": {"plural": {
                cat: {"stringUnit": {"state": "translated", "value": forms[cat]}} for cat in ("one", "other")}}}
            for lang in list(locs):
                if lang != "en" and "stringUnit" in locs[lang] and PLURAL_CATEGORIES.get(lang, ["other"]) != ["other"]:
                    del locs[lang]
            entry["localizations"] = dict(sorted(locs.items()))
            changed = True
        if changed and args.write:
            save_xcstrings(path, catalog)
    missing = sorted(set(keys) - found)
    print(f"{len(found)} keys made plural" + (f"; not in any catalog (build + sync first): {missing}" if missing else ""))


if __name__ == "__main__":
    main()
