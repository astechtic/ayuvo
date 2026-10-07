#!/usr/bin/env python3
"""Make display text in the shared JSON contracts translatable (docs/localization.md).

  python3 scripts/l10n/l10n_contracts.py --write   # regenerate
  python3 scripts/l10n/l10n_contracts.py           # check only; exit 1 when stale

The contracts (shared/derived, shared/workout, shared/metrics, shared/insights)
stay English. Every display field gets a stable key such as
`derived.metric.resting_hr_derived.title`, and this script writes:

  iOS      ios/calorietracker/Contracts.xcstrings (table "Contracts")
  Android  res/values/strings_contracts.xml and the generated key -> R.string
           map com/ayuvo/health/l10n/ContractStrings.kt

Apps look text up by key with the JSON English as fallback (ContractText on
iOS, ContractStrings on Android). When the English of a key changes, its iOS
translations go back to state "new" and the Android review ledger no longer
matches, so l10n_report.py asks for a new translation. Citations and AI
prompts are not listed and stay English.
"""
import argparse
import json
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from l10n_lib import (ANDROID_RES, IOS_DIR, ROOT, android_encode, dump_xcstrings,  # noqa: E402
                      load_xcstrings)

SHARED = os.path.join(ROOT, "shared")
KOTLIN_MAP = os.path.join(ROOT, "android", "app", "src", "main", "java", "com", "ayuvo", "health",
                          "l10n", "ContractStrings.kt")
ANDROID_XML = os.path.join(ANDROID_RES, "values", "strings_contracts.xml")
IOS_APP = os.path.join(IOS_DIR, "calorietracker", "Contracts.xcstrings")

# (key prefix, contract file, path patterns). "*" = every dict key, "[*]" = every
# list item (named by its "id" or "key", else its index).
CONTRACTS = [
    ("derived", "derived/derived_config.json", [
        "disclaimer", "labels.*[*]", "metrics[*].title", "metrics[*].about", "metrics[*].method",
    ]),
    ("workout", "workout/workout_config.json", ["disclaimer", "sports.*.title"]),
    ("vitals", "vitals/vitals_config.json", [
        "disclaimer", "classifications.*.label", "classifications.*.about", "guidance.*", "reasons.*",
        "metrics[*].title", "quality.grades[*].label", "indicator.bands[*].label",
    ]),
    ("cycle", "cycle/cycle_config.json", [
        "disclaimer", "fertility_note", "flow_levels[*].title", "symptoms[*].title", "symptom_groups[*].title",
        "moods[*].title", "pain_locations[*].title", "phases[*].title", "basis[*].title", "basis[*].about",
        "insights[*].template",
    ]),
    ("metric", "metrics/metric_catalog.json", [
        "metrics[*].title", "metrics[*].about", "browse_sections[*].title", "domains[*].title",
    ]),
    ("analytics", "analytics/analytics_config.json", [
        "disclaimer", "classifications.*.label", "classifications.*.about", "metrics.*.label",
        "load.states.*", "recovery.bands[*].label", "recovery.bands[*].recommendation", "recovery.drivers.*.*",
        "recovery.summary.*", "recovery.warnings.*", "recovery.components[*].why", "anomaly.states.*", "anomaly.persistent_note",
        "correlation.template", "correlation.pairs[*].exposure_label", "correlation.pairs[*].outcome_label",
        "correlation.direction_words.*", "correlation.lag_words.*",
    ]),
    ("insights", "insights/insights_config.json", [
        "ai.status_labels.*", "daily_review.areas[*].label", "daily_review.not_logged_template",
        "daily_review.reduce_nutrients[*].label", "daily_review.rules[*].template", "disclaimers.*",
        "health_age.markers[*].label", "methodology.*.title", "methodology.*.sections[*].heading",
        "methodology.*.sections[*].body", "metrics.*.label", "patterns.pairs[*].template",
        "patterns.pairs[*].less_word", "patterns.pairs[*].more_word", "recovery.bands[*].label",
        "recovery.bands[*].recommendation", "recovery.contributors.*", "training_load.labels.*",
    ]),
]
TOKEN = re.compile(r"\*|\[\*\]|[^.\[\]*]+")


def _name(item, index):
    if isinstance(item, dict):
        for field in ("id", "key"):
            if isinstance(item.get(field), str):
                return item[field]
    return str(index)


def expand(node, parts, key):
    if not parts:
        if isinstance(node, str) and node.strip():
            yield key, node
        return
    head, rest = parts[0], parts[1:]
    if head == "*":
        if isinstance(node, dict):
            for k, v in node.items():
                yield from expand(v, rest, f"{key}.{k}")
    elif head == "[*]":
        if isinstance(node, list):
            for i, v in enumerate(node):
                yield from expand(v, rest, f"{key}.{_name(v, i)}")
    elif isinstance(node, dict) and head in node:
        yield from expand(node[head], rest, f"{key}.{head}")


def collect():
    texts = {}
    for prefix, rel, patterns in CONTRACTS:
        with open(os.path.join(SHARED, rel), encoding="utf-8") as fh:
            data = json.load(fh)
        for pattern in patterns:
            for key, text in expand(data, TOKEN.findall(pattern), prefix):
                texts[key] = text
    return texts


def resource_name(key):
    return "c_" + re.sub(r"[^a-z0-9]+", "_", key.lower()).strip("_")


def ios_catalog(path, texts):
    catalog = load_xcstrings(path) if os.path.exists(path) else {
        "sourceLanguage": "en", "strings": {}, "version": "1.0"}
    old = catalog["strings"]
    strings = {}
    for key in sorted(texts, key=str.lower):
        entry = old.get(key, {})
        locs = dict(entry.get("localizations", {}))
        previous = locs.get("en", {}).get("stringUnit", {}).get("value")
        if previous is not None and previous != texts[key]:
            for lang, loc in locs.items():
                if lang != "en" and "stringUnit" in loc:
                    loc["stringUnit"]["state"] = "new"
        locs["en"] = {"stringUnit": {"state": "translated", "value": texts[key]}}
        strings[key] = {"extractionState": "manual", "localizations": dict(sorted(locs.items()))}
    catalog["strings"] = strings
    return dump_xcstrings(catalog) + "\n"


def android_xml(texts):
    lines = ['<?xml version="1.0" encoding="utf-8"?>',
             "<!-- Generated by scripts/l10n/l10n_contracts.py from shared/*. Do not edit. -->",
             "<resources>"]
    for key, text in texts.items():
        # Contract text is never a format string; a "%" in it is a percent sign.
        attrs = ' formatted="false"' if "%" in text else ""
        lines.append(f'    <string name="{resource_name(key)}"{attrs}>{android_encode(text)}</string>')
    lines.append("</resources>")
    return "\n".join(lines) + "\n"


def kotlin_map(texts):
    entries = ",\n".join(f'        "{key}" to R.string.{resource_name(key)}' for key in texts)
    return f'''// Generated by scripts/l10n/l10n_contracts.py. Do not edit.
package com.ayuvo.health.l10n

import android.content.Context
import com.ayuvo.health.R

/**
 * Translated display text for the shared JSON contracts (docs/localization.md).
 * Keys are stable paths such as "derived.metric.<id>.title"; unknown keys and
 * missing contexts fall back to the English from the contract.
 */
object ContractStrings {{
    private val ids: Map<String, Int> = mapOf(
{entries}
    )

    fun text(context: Context?, key: String, fallback: String): String {{
        val id = ids[key] ?: return fallback
        return context?.getString(id) ?: fallback
    }}

    fun has(key: String): Boolean = key in ids
}}
'''


def outputs():
    texts = collect()
    return {
        IOS_APP: ios_catalog(IOS_APP, texts),
        ANDROID_XML: android_xml(texts),
        KOTLIN_MAP: kotlin_map(texts),
    }, texts


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--write", action="store_true")
    args = ap.parse_args()
    files, texts = outputs()
    stale = []
    for path, content in files.items():
        current = open(path, encoding="utf-8").read() if os.path.exists(path) else None
        if current != content:
            stale.append(os.path.relpath(path, ROOT))
            if args.write:
                os.makedirs(os.path.dirname(path), exist_ok=True)
                with open(path, "w", encoding="utf-8") as fh:
                    fh.write(content)
    print(f"{len(texts)} contract strings; " + (
        ("updated: " if args.write else "stale: ") + ", ".join(stale) if stale else "all generated files up to date"))
    if stale and not args.write:
        sys.exit(1)


if __name__ == "__main__":
    main()
