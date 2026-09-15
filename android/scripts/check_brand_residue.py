#!/usr/bin/env python3
"""Fails when any old-brand token (or an unfilled release placeholder) remains under android/.

Usage: python3 android/scripts/check_brand_residue.py [--allow-placeholders]

`--allow-placeholders` lets development builds pass while SUPPORT_EMAIL_PLACEHOLDER is still
unfilled; release CI runs without it so nothing ships with a placeholder address.
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

ANDROID = Path(__file__).resolve().parents[1]
TOKENS = [
    "Fud AI", "FudAI", "Fudai", "fudai", "fud-ai", "fud_ai", "apoorv", "calorietracker",
    "ko-fi", "kofi", "producthunt", "discord", "weekly challenge", "weeklychallenge",
    "udyam", "ace_cpt", "openssf", "hosted relay", "revenuecat",
]
PLACEHOLDER = "_PLACEHOLDER"
PATTERN = re.compile("|".join(re.escape(t) for t in TOKENS), re.I)
SKIP_DIRS = {"build", ".gradle", ".kotlin", "release", ".idea", "scripts"}
SKIP_SUFFIXES = {".png", ".jpg", ".jpeg", ".webp", ".gif", ".zip", ".jar", ".jks", ".aab", ".apk", ".bin", ".so", ".ttf", ".otf"}
# The MIT licence of the base project must keep its author's name.
ALLOW = {
    "app/src/main/assets/legal/LICENSE_BASE_PROJECT.txt": {"apoorv"},
}


def main() -> int:
    allow_placeholders = "--allow-placeholders" in sys.argv[1:]
    hits: list[str] = []
    placeholders: list[str] = []
    for path in sorted(ANDROID.rglob("*")):
        if path.is_dir() or path.suffix.lower() in SKIP_SUFFIXES:
            continue
        rel = path.relative_to(ANDROID)
        if any(part in SKIP_DIRS for part in rel.parts):
            continue
        try:
            text = path.read_text(encoding="utf-8")
        except (UnicodeDecodeError, OSError):
            continue
        allowed = ALLOW.get(rel.as_posix(), set())
        for number, line in enumerate(text.splitlines(), 1):
            for match in PATTERN.finditer(line):
                token = match.group(0)
                if token.lower() in allowed:
                    continue
                # The iOS project keeps its internal `calorietracker` target/folder name; comments
                # pointing at `ios/calorietracker/...` source paths are accurate, not residue.
                if token.lower() == "calorietracker" and "ios/calorietracker" in line:
                    continue
                hits.append(f"{rel}:{number}: {token}: {line.strip()[:110]}")
            if PLACEHOLDER in line:
                placeholders.append(f"{rel}:{number}: {line.strip()[:110]}")
    if hits:
        print("Old-brand residue:")
        print("\n".join(hits))
    if placeholders:
        print("Unfilled release placeholders:" if not allow_placeholders else "Placeholders still to fill before release:")
        print("\n".join(placeholders))
    failed = bool(hits) or (bool(placeholders) and not allow_placeholders)
    if not failed:
        print("brand residue check passed" + (" (placeholders allowed)" if allow_placeholders and placeholders else ""))
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
