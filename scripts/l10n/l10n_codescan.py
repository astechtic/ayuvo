#!/usr/bin/env python3
"""Find user-facing text that bypasses localization (docs/localization.md).

  python3 scripts/l10n/l10n_codescan.py [--platform ios|android] [--by-file] [--all]

iOS: a Swift string literal counts as localized when the compiler extracted it
(it appears in a .stringsdata file at that line, so build first). Everything
else that looks like prose is reported.
Android: Kotlin literals that look like prose outside resources are reported.

Legitimate English (AI prompts, logs, identifiers, parsers of English
documents) is listed in scripts/l10n/allow/*.json, each
{"files": [...], "dirs": [...], "contains": [...]} with repo-relative paths;
"contains" entries allow any line that contains that text.
Exit code 1 when anything unallowed is found.
"""
import argparse
import json
import os
import re
import sys
from collections import defaultdict

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from l10n_lib import ROOT  # noqa: E402
from l10n_sync_ios import TARGET_FOLDERS, newest_derived_data  # noqa: E402

ALLOW_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "allow")
LITERAL = re.compile(r'(?<![\\#])"((?:[^"\\\n]|\\.)*)"')
MULTILINE = re.compile(r'"""')
# Lines that never carry UI text.
SKIP_LINE = re.compile(
    r'(\bprint\(|\bLog\.[dewiv]\(|\blogger\.|os_log|\bLogger\(|debugPrint|fatalError|precondition|'
    r'assert(ionFailure)?\(|require\(|check\(|\berror\(|NSPredicate|\bsystemName:|\bImage\(\s*"|'
    r'UIImage\(named|forKey:|\bdefaults\.|UserDefaults|@AppStorage|@SceneStorage|Preferences?Keys?\b|'
    r'PreferencesKey|stringPreferencesKey|booleanPreferencesKey|intPreferencesKey|'
    r'\bURL\(string|Uri\.parse|https?://|\.header\(|addValue\(|setValue\(.*forHTTPHeaderField|'
    r'@Suppress|@SerialName|@Json|CodingKeys|case \w+ = "|const val|Regex\(|NSRegularExpression|'
    r'\.appendingPathComponent|contentType|mimeType|"SELECT|"INSERT|"UPDATE|"DELETE|"CREATE|'
    r'Identifier\(|identifier:|\.accessibilityIdentifier|testTag|\bkey = "|keyPath|'
    r'dateFormat|DateTimeFormatter\.ofPattern|SimpleDateFormat)'
)


def prose(text):
    """Looks like words a person reads: two letters in a row and either a space
    or a capitalised word; not an identifier, path, key or symbol name."""
    if len(text) < 2 or "\\(" in text and not re.search(r"[A-Za-z]{3}", re.sub(r"\\\(.*?\)", "", text)):
        return False
    plain = re.sub(r"\\\(.*?\)|\$\{.*?\}|\$\w+|%[-#0-9.]*[a-z@]+", "", text)
    if not re.search(r"[A-Za-z]{2}", plain):
        return False
    if re.fullmatch(r"[\w.\-/:]+", plain) and not re.fullmatch(r"[A-Z][a-z]+", plain):
        return False  # identifiers, keys, file names, symbol names
    if re.fullmatch(r"[a-z]+", plain):
        return False
    return " " in plain.strip() or re.fullmatch(r"[A-Z][a-z]+[.!?:]?", plain.strip()) is not None


def load_allow():
    allow = {"files": [], "dirs": [], "contains": []}
    if os.path.isdir(ALLOW_DIR):
        for name in sorted(os.listdir(ALLOW_DIR)):
            if name.endswith(".json"):
                with open(os.path.join(ALLOW_DIR, name), encoding="utf-8") as fh:
                    data = json.load(fh)
                for k in allow:
                    allow[k] += data.get(k, [])
    return allow


def allowed(rel, line, allow):
    if rel in allow["files"] or any(rel.startswith(d.rstrip("/") + "/") for d in allow["dirs"]):
        return True
    return any(line.find(s) >= 0 for s in allow["contains"])


def ios_extracted_lines():
    """{source path: set(line numbers)} the compiler extracted."""
    derived = newest_derived_data()
    lines = defaultdict(set)
    if not derived:
        return lines
    for base, _dirs, files in os.walk(os.path.join(derived, "Build", "Intermediates.noindex")):
        builds = [p[:-6] for p in base.split(os.sep) if p.endswith(".build")]
        if not builds or builds[-1] not in TARGET_FOLDERS:
            continue
        for name in files:
            if name.endswith(".stringsdata"):
                with open(os.path.join(base, name), encoding="utf-8") as fh:
                    data = json.load(fh)
                for items in data.get("tables", {}).values():
                    for item in items:
                        if "location" in item:
                            lines[data["source"]].add(item["location"]["startingLine"])
    return lines


def source_files(platform):
    if platform == "ios":
        roots = [os.path.join(ROOT, "ios", f) for f in TARGET_FOLDERS.values()]
        ext = ".swift"
    else:
        roots = [os.path.join(ROOT, "android", "app", "src", "main", "java")]
        ext = ".kt"
    for root in roots:
        for base, dirs, files in os.walk(root):
            dirs[:] = [d for d in dirs if d not in ("build", "Preview Content")]
            for name in sorted(files):
                if name.endswith(ext):
                    yield os.path.join(base, name)


def scan(platform, allow):
    extracted = ios_extracted_lines() if platform == "ios" else {}
    findings = []
    for path in source_files(platform):
        rel = os.path.relpath(path, ROOT)
        with open(path, encoding="utf-8", errors="ignore") as fh:
            lines = fh.read().split("\n")
        in_block_comment = False
        in_multiline = False
        for no, line in enumerate(lines, 1):
            stripped = line.strip()
            if in_block_comment:
                if "*/" in stripped:
                    in_block_comment = False
                continue
            if stripped.startswith("/*"):
                in_block_comment = "*/" not in stripped
                continue
            if len(MULTILINE.findall(line)) % 2 == 1:
                in_multiline = not in_multiline
                continue
            if in_multiline or stripped.startswith(("//", "*", "#if", "#Preview", "import ")):
                continue
            if SKIP_LINE.search(line) or allowed(rel, line, allow):
                continue
            code = line.split(" //")[0]
            for m in LITERAL.finditer(code):
                text = m.group(1)
                if not prose(text):
                    continue
                if platform == "ios" and no in extracted.get(path, ()):
                    continue
                if platform == "ios" and re.search(r'String\(localized:\s*$|String\(localized:\s*"', code[:m.start() + 1]):
                    continue
                findings.append((rel, no, text, stripped[:140]))
    return findings


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--platform", choices=["ios", "android"], action="append")
    ap.add_argument("--by-file", action="store_true")
    ap.add_argument("--all", action="store_true", help="print every finding")
    args = ap.parse_args()
    allow = load_allow()
    total = 0
    for platform in args.platform or ["ios", "android"]:
        findings = scan(platform, allow)
        total += len(findings)
        print(f"== {platform}: {len(findings)} literals bypass localization")
        if args.by_file:
            per = defaultdict(int)
            for rel, *_ in findings:
                per[rel] += 1
            for rel, n in sorted(per.items(), key=lambda kv: -kv[1]):
                print(f"{n:5}  {rel}")
        if args.all:
            for rel, no, text, line in findings:
                print(f"{rel}:{no}: {text!r}   | {line}")
    sys.exit(1 if total else 0)


if __name__ == "__main__":
    main()
