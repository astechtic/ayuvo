#!/usr/bin/env python3
"""Sync iOS String Catalogs with the strings the compiler extracted.

  python3 scripts/l10n/l10n_sync_ios.py [--derived-data PATH] [--write]

Xcode only updates catalogs when a build runs in the IDE, and targets without a
catalog are never localized. This reads the .stringsdata files that every build
writes (SWIFT_EMIT_LOC_STRINGS = YES), then for each target and table:
  * creates <target folder>/<Table>.xcstrings when missing,
  * adds keys that are used but not in the catalog (with their comment),
  * reports keys in the catalog that no build uses any more (Xcode marks them
    stale on its next IDE build; some are looked up by computed keys, so they
    are never removed here).
Only the newest build of each source file counts, and deleted sources are ignored.
Without --write it only reports.
"""
import argparse
import glob
import json
import os
import sys
from collections import defaultdict

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from l10n_lib import IOS_DIR, PLACEHOLDER, load_xcstrings, save_xcstrings  # noqa: E402

TARGET_FOLDERS = {
    "calorietracker": "calorietracker",
    "FudAIWidgetsExtension": "FudAIWidgets",
    "FudAIWatchApp": "FudAIWatchApp",
    "FudAIWatchWidgetsExtension": "FudAIWatchWidgets",
    "calorietrackerShare": "calorietrackerShare",
}


def newest_derived_data():
    base = os.path.expanduser("~/Library/Developer/Xcode/DerivedData")
    found = [p for p in glob.glob(os.path.join(base, "calorietracker-*")) if os.path.isdir(p)]
    return max(found, key=os.path.getmtime) if found else None


def extracted(derived):
    """{(target, table): {key: comment}} from the newest .stringsdata per source."""
    newest = {}
    for base, _dirs, files in os.walk(os.path.join(derived, "Build", "Intermediates.noindex")):
        builds = [p[:-6] for p in base.split(os.sep) if p.endswith(".build")]
        if not builds or builds[-1] not in TARGET_FOLDERS:
            continue
        for name in files:
            if not name.endswith(".stringsdata"):
                continue
            path = os.path.join(base, name)
            with open(path, encoding="utf-8") as fh:
                data = json.load(fh)
            source = data.get("source", "")
            if not os.path.exists(source):
                continue
            key = (builds[-1], source)
            mtime = os.path.getmtime(path)
            if key not in newest or mtime > newest[key][0]:
                newest[key] = (mtime, data)
    result = defaultdict(dict)
    for (target, _source), (_mtime, data) in newest.items():
        for table, items in data.get("tables", {}).items():
            for item in items:
                prev = result[(target, table)].get(item["key"], ("", None))
                result[(target, table)][item["key"]] = (item.get("comment") or prev[0], item.get("value") or prev[1])
    return result


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--derived-data")
    ap.add_argument("--write", action="store_true")
    args = ap.parse_args()
    derived = args.derived_data or newest_derived_data()
    if not derived:
        sys.exit("No DerivedData found: build the app first.")
    used = extracted(derived)
    for (target, table), keys in sorted(used.items()):
        if table == "InfoPlist":
            continue  # Info.plist keys are not in .stringsdata reliably; managed by hand.
        path = os.path.join(IOS_DIR, TARGET_FOLDERS[target], f"{table}.xcstrings")
        catalog = load_xcstrings(path) if os.path.exists(path) else {
            "sourceLanguage": "en", "strings": {}, "version": "1.0"}
        strings = catalog["strings"]
        added = [k for k in keys if k not in strings]
        unused = [k for k, v in strings.items()
                  if k not in keys and v.get("extractionState") not in ("manual", "stale")]
        for key in added:
            entry = {}
            if keys[key][0]:
                entry["comment"] = keys[key][0]
            strings[key] = entry
        # String(localized: "some.key", defaultValue: "Text"): English is the default value.
        filled = 0
        for key, (_comment, value) in keys.items():
            if value and value != key:
                en = strings[key].setdefault("localizations", {}).get("en")
                # Only fill a missing English entry: the compiler reports interpolated
                # defaults as bare specifiers ("%@%@"), so an existing value wins.
                if not en and not PLACEHOLDER.fullmatch(value) and "%" not in value:
                    strings[key]["localizations"]["en"] = {"stringUnit": {"state": "translated", "value": value}}
                    strings[key]["localizations"] = dict(sorted(strings[key]["localizations"].items()))
                    filled += 1
                    added.append(key) if key not in added else None
        print(f"{target}/{table}: {len(keys)} used, +{len(added) - filled} added, {filled} English defaults, "
              f"{len(unused)} newly unused"
              + ("" if os.path.exists(path) else " (new catalog)"))
        if args.write and (added or not os.path.exists(path)):
            if not os.path.exists(path):
                catalog["strings"] = dict(sorted(strings.items(), key=lambda kv: kv[0].lower()))
                with open(path, "w", encoding="utf-8") as fh:
                    fh.write("{}")
            save_xcstrings(path, catalog)


if __name__ == "__main__":
    main()
