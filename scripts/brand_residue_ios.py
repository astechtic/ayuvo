#!/usr/bin/env python3
"""Scan ios/ for leftovers of the previous brand, removed features and stale identifiers.

Internal Xcode target / scheme / folder names ("calorietracker", "FudAIWidgets", ...) are kept on
purpose (plan §3.1, Option B), so `calorietracker` is only flagged inside string literals of
.swift / .xcstrings / .plist files (the one place it would leak to users: CFBundleName).

Exit 1 on any hit that is not allow-listed.
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
IOS = ROOT / "ios"

TOKENS = [
    "Fud AI", "FudAI", "fudai", "fud-ai", "ai.fud", "apoorv", "RevenueCat", "Purchases.", "HostedAI", "hostedAI",
    "WeeklyChallenge", "weeklyChallenge", "producthunt", "Product Hunt", "discord", "ko-fi", "openssf", "Udyam",
    "ACECPT", "add-meal", "unsplash", "fudPink", "Fud Pink", "23RV7FYH36", "6758935726", "HTTP-Referer",
    "_PLACEHOLDER",
]
# Folder / target names that stay (Option B) — never flagged as tokens by themselves.
INTERNAL_NAMES = re.compile(r"FudAIWidgets|FudAIWatchApp|FudAIWatchWidgets|FudAIWidgetsExtension|FudAIWatchWidgetsExtension")
STRING_LITERAL = re.compile(r'"[^"\n]*calorietracker[^"\n]*"')

ALLOW_FILES = {
    "ios/calorietracker/Legal/BASE_PROJECT_LICENSE.txt",       # retained MIT notice
    "ios/calorietracker/Legal/THIRD_PARTY_NOTICES.txt",
}
SKIP_SUFFIXES = {".png", ".jpg", ".jpeg", ".zip", ".pdf", ".ico", ".xcframework", ".a", ".bin", ".litertlm", ".mlmodelc"}
SKIP_PARTS = {"xcuserdata", ".swiftpm", "build", "DerivedData", "build-sim"}


def main() -> int:
    hits = 0
    for p in sorted(IOS.rglob("*")):
        if not p.is_file() or p.suffix.lower() in SKIP_SUFFIXES or any(part in SKIP_PARTS for part in p.parts):
            continue
        rel = p.relative_to(ROOT).as_posix()
        if rel in ALLOW_FILES:
            continue
        try:
            text = p.read_text(encoding="utf-8")
        except UnicodeDecodeError:
            continue
        for i, line in enumerate(text.splitlines(), 1):
            probe = INTERNAL_NAMES.sub("", line)
            for t in TOKENS:
                if t in probe:
                    print(f"{rel}:{i}: {t}: {line.strip()[:120]}")
                    hits += 1
                    break
            if p.suffix in (".swift", ".xcstrings", ".plist") and STRING_LITERAL.search(line):
                # user-visible leak of the internal module name (e.g. CFBundleName = calorietracker)
                if "@testable" not in line and "Bundle(for" not in line and "calorietrackerTests" not in line and "calorietrackerUITests" not in line:
                    print(f"{rel}:{i}: calorietracker (string literal): {line.strip()[:120]}")
                    hits += 1
    print(f"brand_residue_ios: {'FAIL' if hits else 'OK'} ({hits} hits)")
    return 1 if hits else 0


if __name__ == "__main__":
    sys.exit(main())
