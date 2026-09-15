#!/usr/bin/env python3
"""Import the exercise catalogue shared by the iOS and Android apps.

Source: https://github.com/hasaneyldrm/exercises-dataset pinned to a commit so
media URLs never move underneath shipped builds. Only English text is kept.

Usage:
    python3 scripts/import_exercises_dataset.py            # download + write
    python3 scripts/import_exercises_dataset.py --check    # validate output
"""

from __future__ import annotations

import argparse
import json
import re
import sys
import urllib.request
from pathlib import Path

REPO = "hasaneyldrm/exercises-dataset"
PINNED_SHA = "7455efae41b330c265e7cd4b78dfa848e7ce5ebd"
RAW_BASE = f"https://raw.githubusercontent.com/{REPO}/{PINNED_SHA}"

ROOT = Path(__file__).resolve().parents[1]
OUTPUT_DIR = ROOT / "shared" / "exercises"
OUTPUT = OUTPUT_DIR / "exercises.json"

MUSCLE_SYNONYMS = {
    "trapezius": "traps",
    "latissimus dorsi": "lats",
    "deltoids": "shoulders",
    "abdominals": "abs",
    "quadriceps": "quads",
}

# Quick-log activities referenced by OutdoorActivitySettings on both platforms.
LOCAL_ACTIVITIES = [
    {
        "id": "Walking_Outdoor",
        "name": "walking (outdoor)",
        "bodyPart": "cardio",
        "target": "cardiovascular system",
        "equipment": "body weight",
        "secondaryMuscles": ["calves", "quads", "hamstrings"],
        "instructions": [
            "Walk outdoors at a steady, comfortable pace.",
            "Keep your posture upright and swing your arms naturally.",
        ],
        "gifUrl": None,
        "imageUrl": None,
    },
    {
        "id": "Running_Outdoor",
        "name": "running (outdoor)",
        "bodyPart": "cardio",
        "target": "cardiovascular system",
        "equipment": "body weight",
        "secondaryMuscles": ["calves", "quads", "hamstrings", "glutes"],
        "instructions": [
            "Run outdoors at a pace you can sustain.",
            "Land softly under your hips and keep your breathing steady.",
        ],
        "gifUrl": None,
        "imageUrl": None,
    },
]

ID_PATTERN = re.compile(r"^(\d{4}|Walking_Outdoor|Running_Outdoor)$")
URL_PATTERN = re.compile(
    rf"^{re.escape(RAW_BASE)}/(images/\d{{4}}-[A-Za-z0-9]+\.jpg|videos/\d{{4}}-[A-Za-z0-9]+\.gif)$"
)


def fetch(path: str) -> bytes:
    with urllib.request.urlopen(f"{RAW_BASE}/{path}", timeout=120) as response:
        return response.read()


def normalize_muscle(value: str) -> str:
    cleaned = " ".join(value.strip().lower().split())
    return MUSCLE_SYNONYMS.get(cleaned, cleaned)


def convert(record: dict) -> dict:
    secondary: list[str] = []
    for muscle in record.get("secondary_muscles") or []:
        normalized = normalize_muscle(muscle)
        if normalized and normalized not in secondary:
            secondary.append(normalized)
    steps = [step.strip() for step in record["instruction_steps"]["en"] if step.strip()]
    return {
        "id": record["id"],
        "name": " ".join(record["name"].strip().split()),
        "bodyPart": record["body_part"].strip().lower(),
        "target": normalize_muscle(record["target"]),
        "equipment": record["equipment"].strip().lower(),
        "secondaryMuscles": secondary,
        "instructions": steps,
        "gifUrl": f"{RAW_BASE}/{record['gif_url']}",
        "imageUrl": f"{RAW_BASE}/{record['image']}",
    }


def disambiguate(records: list[dict]) -> None:
    counts: dict[str, int] = {}
    for record in records:
        counts[record["name"]] = counts.get(record["name"], 0) + 1
    seen: dict[str, int] = {}
    for record in records:
        name = record["name"]
        if counts[name] < 2:
            continue
        seen[name] = seen.get(name, 0) + 1
        if seen[name] > 1:
            record["name"] = f"{name} ({record['equipment']}, {record['id']})"


def validate(records: list[dict]) -> list[str]:
    errors: list[str] = []
    ids: set[str] = set()
    names: set[str] = set()
    for record in records:
        rid = record.get("id", "")
        if not ID_PATTERN.match(rid):
            errors.append(f"bad id: {rid!r}")
        if rid in ids:
            errors.append(f"duplicate id: {rid}")
        ids.add(rid)
        if not record.get("name"):
            errors.append(f"{rid}: empty name")
        if record["name"] in names:
            errors.append(f"{rid}: duplicate name {record['name']!r}")
        names.add(record["name"])
        if not record.get("instructions"):
            errors.append(f"{rid}: no instructions")
        for field in ("bodyPart", "target", "equipment"):
            if not record.get(field):
                errors.append(f"{rid}: empty {field}")
        if rid.isdigit():
            for field in ("gifUrl", "imageUrl"):
                if not URL_PATTERN.match(record.get(field) or ""):
                    errors.append(f"{rid}: bad {field}")
    if len(records) != 1324 + len(LOCAL_ACTIVITIES):
        errors.append(f"expected {1324 + len(LOCAL_ACTIVITIES)} records, found {len(records)}")
    return errors


def write() -> None:
    upstream = json.loads(fetch("data/exercises.json"))
    records = [convert(record) for record in upstream]
    records.sort(key=lambda r: (r["name"], r["id"]))
    disambiguate(records)
    records.extend(LOCAL_ACTIVITIES)
    records.sort(key=lambda r: (r["name"], r["id"]))

    errors = validate(records)
    if errors:
        sys.exit("\n".join(errors))

    OUTPUT_DIR.mkdir(parents=True, exist_ok=True)
    OUTPUT.write_text(
        json.dumps(records, ensure_ascii=False, separators=(",", ":")) + "\n",
        encoding="utf-8",
    )
    (OUTPUT_DIR / "LICENSE").write_bytes(fetch("LICENSE"))
    (OUTPUT_DIR / "NOTICE.md").write_bytes(fetch("NOTICE.md"))
    print(f"wrote {len(records)} exercises ({OUTPUT.stat().st_size:,} bytes) from {REPO}@{PINNED_SHA[:7]}")


def check() -> None:
    records = json.loads(OUTPUT.read_text(encoding="utf-8"))
    errors = validate(records)
    if errors:
        sys.exit("\n".join(errors))
    print(f"ok: {len(records)} exercises")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true", help="validate the generated catalogue")
    args = parser.parse_args()
    check() if args.check else write()


if __name__ == "__main__":
    main()
