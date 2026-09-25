#!/usr/bin/env python3
"""Scan the non-app parts of the repo for leftovers of the previous brand and removed features.

Covers root docs, docs/, local-models/, scripts/, web/, brand/, marketing/, shared/health,
.github/ and .cursor/ (file names and contents). The Android and iOS trees have their own scans
(`android/scripts/check_brand_residue.py`, `scripts/brand_residue_ios.py`).

Exit 1 on any hit that is not allow-listed.
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

PATTERN = re.compile(
    r"fud[\s_-]?ai|fudai|apoorv|udyam|ace[\s-]?cpt|acefitness|ko-?fi\b|revenuecat|discord|product ?hunt|"
    r"linkedin\.com|x\.com/|twitter\.com|verceltics|star-history|weekly challenge|meal[\s-]?share|hosted[\s-]?ai|"
    r"tip jar|cloudflare|wrangler|openssf|scorecard\.dev|bestpractices\.dev|calorietracker",
    re.IGNORECASE,
)

SCAN = [
    "README.md", "DEVELOPMENT.md", "SECURITY.md", "QUALITY.md", "ASSET_CREDITS.md", "APPSTORE.md",
    "PLAYSTORE.md", "RELEASE_NOTES.md", "LICENSE", ".gitignore",
    "docs", "local-models", "scripts", "web", ".github", ".cursor", "brand", "marketing", "shared/health",
]
SKIP_DIRS = {".git", "node_modules", ".firebase", "__pycache__", "raw", "store", "composites", "proofs"}
# One-shot migration scripts must contain the old tokens they rewrite.
SKIP_FILES = {"scripts/rebrand_xcstrings.py"}
SKIP_SUFFIXES = {".png", ".jpg", ".jpeg", ".ico", ".woff2", ".ttf", ".zip", ".pyc", ".json"}

# (path suffix, regex of allowed lines) — the retained MIT notice and verbatim third-party notices.
ALLOW = [
    ("LICENSE", re.compile(r"Copyright \(c\) 2026 Apoorv Darshan")),
    ("THIRD_PARTY_NOTICES.md", None),
    ("local-models/legal/THIRD_PARTY_NOTICES_LiteRTLM_v0.16.0.txt", None),
    ("web/terms.html", re.compile(r"Copyright \(c\) 2026 Apoorv Darshan")),
    ("scripts/brand_residue.py", None),
    (".cursor/rules/ayuvo-brand.mdc", None),
    ("DEVELOPMENT.md", re.compile(r"calorietracker")),          # Xcode target/scheme names (internal)
    ("README.md", re.compile(r"calorietracker|Fud AI|fud-ai|Apoorv")),
    ("APPSTORE.md", re.compile(r"CURRENT_PROJECT_VERSION|calorietracker")),
    ("web/privacy.html", re.compile(r"\"Fud AI\" open-source code base")),
    ("web/_src/pages/privacy.html", re.compile(r"\"Fud AI\" open-source code base")),
    ("web/_src/pages/terms.html", re.compile(r"Copyright \(c\) 2026 Apoorv Darshan")),
    # The open-source page credits the project Ayuvo is inspired by (and its licence notice travels with the code).
    ("web/open-source.html", re.compile(r"Fud AI|fud-ai|Apoorv")),
    ("scripts/web/pages.py", re.compile(r"Fud AI|FUD_AI|Apoorv")),
    ("scripts/web/site_lib.py", re.compile(r"FUD_AI")),
    (".github/workflows/ios-release.yml", re.compile(r"calorietracker")),
    ("docs/health-data.md", re.compile(r"calorietracker")),
    ("docs/health-data-export.md", re.compile(r"calorietrackerTests")),
    ("scripts/brand/render_icons.py", re.compile(r"ROOT / \"ios/")),
    ("scripts/web_check.py", re.compile(r"FORBIDDEN_HOSTS")),
    ("scripts/brand_residue_ios.py", None),
]


def allowed(rel: str, line: str) -> bool:
    for suffix, pattern in ALLOW:
        if rel.endswith(suffix):
            if pattern is None or pattern.search(line):
                return True
    return False


def main() -> int:
    hits = 0
    for entry in SCAN:
        base = ROOT / entry
        paths = [base] if base.is_file() else sorted(p for p in base.rglob("*") if p.is_file()) if base.exists() else []
        for p in paths:
            if any(part in SKIP_DIRS for part in p.relative_to(ROOT).parts):
                continue
            rel = p.relative_to(ROOT).as_posix()
            if PATTERN.search(p.name):
                print(f"{rel}: file name")
                hits += 1
            if p.relative_to(ROOT).as_posix() in SKIP_FILES:
                continue
            if p.suffix.lower() in SKIP_SUFFIXES:
                continue
            try:
                text = p.read_text(encoding="utf-8")
            except UnicodeDecodeError:
                continue
            for i, line in enumerate(text.splitlines(), 1):
                m = PATTERN.search(line)
                if m and not allowed(rel, line):
                    print(f"{rel}:{i}: {m.group(0)}: {line.strip()[:110]}")
                    hits += 1
    print(f"brand_residue: {'FAIL' if hits else 'OK'} ({hits} hits)")
    return 1 if hits else 0


if __name__ == "__main__":
    sys.exit(main())
