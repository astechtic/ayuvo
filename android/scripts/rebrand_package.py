#!/usr/bin/env python3
"""One-shot package move: com.apoorvdarshan.calorietracker -> com.ayuvo.health.

Moves the package directory in every source set that has one, then rewrites
every `package`/`import`/fully-qualified reference and string literal in Kotlin
sources plus the non-Kotlin files that embed the package (Gradle, ProGuard,
OAuth example, docs). Idempotent: a second run reports zero changes.
"""
from __future__ import annotations

import re
import shutil
import sys
from pathlib import Path

ANDROID = Path(__file__).resolve().parents[1]
OLD = "com.apoorvdarshan.calorietracker"
NEW = "com.ayuvo.health"
OLD_DIR = Path(*OLD.split("."))
NEW_DIR = Path(*NEW.split("."))
SOURCE_SETS = ["main", "test", "androidTest", "debug", "debug2"]
# Not preceded by a word char or '.', so `iCloud.com.apoorvdarshan...` (iOS docs)
# is skipped; may be followed by `.debug`, `.R`, `.ui...` because '.' is not \w.
TOKEN = re.compile(r"(?<![\w.])" + re.escape(OLD) + r"(?!\w)")
EXTRA_FILES = [
    ANDROID / "app/build.gradle.kts",
    ANDROID / "app/proguard-rules.pro",
    ANDROID / "oauth.properties.example",
    ANDROID / "docs/health-data-hub.md",
    ANDROID / "docs/health-nutrition-import.md",
]


def move_package_dirs() -> int:
    moved = 0
    for source_set in SOURCE_SETS:
        java_root = ANDROID / "app/src" / source_set / "java"
        src = java_root / OLD_DIR
        if not src.is_dir():
            continue
        dst = java_root / NEW_DIR
        dst.parent.mkdir(parents=True, exist_ok=True)
        if dst.exists():
            # Merge (only happens on a partial re-run).
            for item in src.iterdir():
                shutil.move(str(item), str(dst / item.name))
            src.rmdir()
        else:
            shutil.move(str(src), str(dst))
        moved += 1
        # Prune the now-empty com/apoorvdarshan chain.
        parent = src.parent
        while parent != java_root and parent.is_dir() and not any(parent.iterdir()):
            parent.rmdir()
            parent = parent.parent
    return moved


def rewrite_files() -> int:
    files = [
        p for p in (ANDROID / "app/src").rglob("*.kt") if "build" not in p.parts
    ] + [p for p in EXTRA_FILES if p.exists()]
    changed = 0
    for path in files:
        text = path.read_text(encoding="utf-8")
        new = TOKEN.sub(NEW, text)
        if new != text:
            path.write_text(new, encoding="utf-8")
            changed += 1
    return changed


def leftovers() -> list[str]:
    hits: list[str] = []
    skip_dirs = {"build", ".gradle", ".kotlin", "release", "scripts"}
    skip_ext = {".png", ".jpg", ".jpeg", ".webp", ".zip", ".jar", ".jks", ".aab", ".apk", ".bin"}
    for path in ANDROID.rglob("*"):
        if path.is_dir() or path.suffix in skip_ext:
            continue
        if any(part in skip_dirs for part in path.relative_to(ANDROID).parts):
            continue
        try:
            text = path.read_text(encoding="utf-8")
        except (UnicodeDecodeError, OSError):
            continue
        for number, line in enumerate(text.splitlines(), 1):
            if OLD in line:
                hits.append(f"{path.relative_to(ANDROID)}:{number}: {line.strip()[:120]}")
    return hits


def main() -> int:
    moved = move_package_dirs()
    changed = rewrite_files()
    print(f"moved {moved} package dirs, rewrote {changed} files")
    remaining = leftovers()
    if remaining:
        print("LEFTOVERS under android/:")
        print("\n".join(remaining))
        return 1
    print("no leftovers under android/")
    return 0


if __name__ == "__main__":
    sys.exit(main())
