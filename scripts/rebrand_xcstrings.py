#!/usr/bin/env python3
"""Rebrand the iOS String Catalogs (plan §3.8). Idempotent: a second run changes nothing.

* Localizable.xcstrings — brand tokens are replaced in the KEY and in every localized value
  (plural variations included) so existing translations stay attached; keys of removed
  features (hosted AI, tips, Weekly Challenge, community/legal rows…) are deleted when no
  Swift source still references them; stale comments naming removed features are dropped;
  new English-only keys are added (other locales fall back to English until translated).
* InfoPlist.xcstrings / LocalModels.xcstrings — values only (keys are plist/lookup keys).

Swift literals must be updated with the same table so `Text("…")` keys keep matching; the
iOS rebrand did that in the same change (see scripts/brand_residue_ios.py for the check).
"""
from __future__ import annotations

import json
import re
from collections import OrderedDict
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
APP = ROOT / "ios" / "calorietracker"

TABLE = [
    ("Fud AI’s", "Ayuvo’s"), ("Fud AI's", "Ayuvo's"), ("Fud AI", "Ayuvo"),
    ("fudai-health-data", "ayuvo-health-data"), ("fudai-cloud-backup", "ayuvo-cloud-backup"),
    ("Fud Pink", "Rose"), ("Róż Fud", "Róż"),
]

DELETE_KEY = re.compile(
    r"hosted|Plus|\bPro\b|credit|\bTip|tip jar|challenge|Product Hunt|GitHub|OpenSSF|Report an Issue|"
    r"Request a Feature|Contact Us|Discord|Follow on|Udyam|UDYAM|ACE Certified|NCCA|Support Fud AI|"
    r"Support Ayuvo|Help & Feedback|^Community$|Made by Apoorv|with care, for everyone|add it to their|"
    r"Share any meal as a link|Choose how you want to power AI|processed through|Bring Your Own Key|"
    r"Free forever|^Recommended$|^Convenient$|Subscribe|Restore Purchases|Leave a Tip|4\.4$|"
    r"AI Calorie Tracker|completely free|Read Community Rules|Refresh Usage|Switch to BYOK|"
    r"Use BYOK instead|Active plan|View Plans|Passing · project|Security health score|"
    r"Open Source \(MIT\)|^Open source$|Star on|Vote on|Premium|fud-ai\.app",
    re.I,
)
STALE_COMMENT = re.compile(r"Weekly ?Challenge|Premium")

NEW_ENGLISH_KEYS = [
    "Health", "Health Data", "Progress view", "Help & Support", "Contact Support", "Send Feedback", "Licenses",
    "Base project — MIT", "Base project MIT licence", "Third-party notices",
    "Exercise library, Open Food Facts, muscle glyphs, provider logos", "Ayuvo support request", "Ayuvo feedback",
    "Share this meal as text, with its photo when there is one.",
    "I've been tracking my health with Ayuvo — meals, workouts, sleep and more in one place.\n\nDownload: https://ayuvo-health.web.app",
    "Ask Ayuvo", "Message Ayuvo…",
    "Ayuvo can see your nutrition, goals, workouts and — if you allow it — your Health data. Ask about food, sleep, activity, recovery or your plan.",
    "Send with your message", "Am I moving enough?",
    "Sent to your AI provider only when Coach answers a question.",
    "Sent to your AI provider only when Coach answers a question. Never stored in iCloud backup.",
    "Rose", "No mail app found", "The support address %@ was copied to your clipboard.",
    "Your message opens in your mail app with the app version, iOS version, device model and locale prefilled so we can help faster. Nothing is sent until you tap Send.",
    "Ayuvo is built on an MIT-licensed open-source project. The original notice is retained as required by the licence.",
    "Start Fast", "Whisper Base · MIT",
]


def sub(value: str) -> str:
    for old, new in TABLE:
        value = value.replace(old, new)
    return value


def swift_corpus() -> str:
    return "\n".join(p.read_text() for p in (ROOT / "ios").rglob("*.swift") if "Legal" not in p.parts)


def swift_literal(key: str) -> str:
    return key.replace("\\", "\\\\").replace("\n", "\\n").replace('"', '\\"')


def rewrite_values(entry: dict) -> None:
    for loc in entry.get("localizations", {}).values():
        if "stringUnit" in loc:
            loc["stringUnit"]["value"] = sub(loc["stringUnit"]["value"])
        for variant in loc.get("variations", {}).get("plural", {}).values():
            if "stringUnit" in variant:
                variant["stringUnit"]["value"] = sub(variant["stringUnit"]["value"])


def dump(path: Path, data: dict) -> bool:
    text = json.dumps(data, ensure_ascii=False, indent=2, separators=(",", " : ")) + "\n"
    if path.read_text() == text:
        return False
    path.write_text(text)
    return True


def localizable() -> None:
    path = APP / "Localizable.xcstrings"
    data = json.loads(path.read_text(), object_pairs_hook=OrderedDict)
    corpus = swift_corpus()
    out: OrderedDict = OrderedDict()
    deleted = renamed = 0
    for key, entry in data["strings"].items():
        if DELETE_KEY.search(key) and swift_literal(key) not in corpus:
            deleted += 1
            continue
        new_key = sub(key)
        renamed += new_key != key
        rewrite_values(entry)
        comment = entry.get("comment")
        if comment:
            comment = sub(comment)
            if STALE_COMMENT.search(comment):
                entry.pop("comment", None)
                entry.pop("isCommentAutoGenerated", None)
            else:
                entry["comment"] = comment
        out[new_key] = entry
    added = 0
    for key in NEW_ENGLISH_KEYS:
        if key not in out:
            out[key] = OrderedDict([("extractionState", "manual")])
            added += 1
    data["strings"] = out
    changed = dump(path, data)
    print(f"Localizable.xcstrings: renamed {renamed}, deleted {deleted}, added {added}, written={changed}")


def values_only(name: str) -> None:
    path = APP / name
    data = json.loads(path.read_text(), object_pairs_hook=OrderedDict)
    for key, entry in data["strings"].items():
        rewrite_values(entry)
    if name == "InfoPlist.xcstrings":
        for loc in data["strings"].get("CFBundleName", {}).get("localizations", {}).values():
            if loc.get("stringUnit", {}).get("value") == "calorietracker":
                loc["stringUnit"]["value"] = "Ayuvo"
    print(f"{name}: written={dump(path, data)}")


if __name__ == "__main__":
    localizable()
    values_only("InfoPlist.xcstrings")
    values_only("LocalModels.xcstrings")
