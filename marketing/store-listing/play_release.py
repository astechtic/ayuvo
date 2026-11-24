#!/usr/bin/env python3
"""Upload the release AAB to Google Play and roll it out on a testing track (default: internal).

    PLAY_SA_JSON=~/Desktop/play_store_analytics/libgen-d4497-60d33fa9c5c5.json \
      python3 marketing/store-listing/play_release.py [--track internal] [--aab android/app/build/outputs/bundle/release/app-release.aab]
"""
from __future__ import annotations

import argparse
import sys
from pathlib import Path

import requests

sys.path.insert(0, str(Path(__file__).parent))
import play_publish as p  # noqa: E402

ROOT = Path(__file__).resolve().parents[2]


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--track", default="internal")
    ap.add_argument("--aab", default=str(ROOT / "android/app/build/outputs/bundle/release/app-release.aab"))
    ap.add_argument("--notes", default="Report AI responses from the Coach; stability fixes.")
    a = ap.parse_args()
    h = {"Authorization": f"Bearer {p.access_token()}"}
    eid = requests.post(f"{p.API}/edits", headers=h, timeout=30).json()["id"]
    with open(a.aab, "rb") as fh:
        r = requests.post(f"{p.UPLOAD}/edits/{eid}/bundles?uploadType=media", data=fh, timeout=1800,
                          headers={**h, "Content-Type": "application/octet-stream"})
    r.raise_for_status()
    vc = r.json()["versionCode"]
    print("uploaded bundle versionCode", vc)
    rel = {"track": a.track, "releases": [{"name": f"{vc} (1.0)", "versionCodes": [str(vc)], "status": "completed",
                                            "releaseNotes": [{"language": "en-GB", "text": a.notes}]}]}
    r = requests.put(f"{p.API}/edits/{eid}/tracks/{a.track}", headers=h, json=rel, timeout=60)
    r.raise_for_status()
    c = requests.post(f"{p.API}/edits/{eid}:commit", headers=h, timeout=120)
    if c.status_code != 200 and "changesNotSentForReview" in c.text:
        c = requests.post(f"{p.API}/edits/{eid}:commit?changesNotSentForReview=true", headers=h, timeout=120)
    c.raise_for_status()
    print(f"track {a.track}: release {vc} (1.0) completed and committed")


if __name__ == "__main__":
    main()
