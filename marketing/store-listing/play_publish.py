#!/usr/bin/env python3
"""Upload the Ayuvo Google Play store listing (18 languages) and graphics through the Android Publisher API.

    PLAY_SA_JSON=~/Desktop/play_store_analytics/libgen-d4497-60d33fa9c5c5.json python3 marketing/store-listing/play_publish.py [--dry-run]

Prerequisites (one-time, done by the account owner):
  1. The app com.ayuvo.health exists in Play Console and has had one bundle uploaded by hand (any track).
  2. Google Cloud: enable "Google Play Android Developer API", create a service account, download its JSON key.
  3. Play Console → Users and permissions → invite the service-account email → app access to Ayuvo with
     "Manage store presence" (and "Release to testing tracks" if releases should be uploaded too).

Text comes from marketing/store-listing/play/<locale>/{title,short_description,full_description}.txt.
Graphics (default language only; other languages fall back to them): icon, feature graphic, phone screenshots.
The service-account key is read only to sign a short-lived token; it is never printed. Requires: pyjwt, cryptography, requests.
"""
from __future__ import annotations

import argparse
import json
import os
import time
from pathlib import Path

import jwt
import requests

ROOT = Path(__file__).resolve().parents[2]
TEXT = Path(__file__).with_name("play")
PKG = "com.ayuvo.health"
API = f"https://androidpublisher.googleapis.com/androidpublisher/v3/applications/{PKG}"
UPLOAD = f"https://androidpublisher.googleapis.com/upload/androidpublisher/v3/applications/{PKG}"
DEFAULT = "en-GB"  # the Play listing's default language (graphics live here; other languages fall back)


def access_token() -> str:
    sa = json.loads(Path(os.environ["PLAY_SA_JSON"]).read_text())
    now = int(time.time())
    assertion = jwt.encode({"iss": sa["client_email"], "scope": "https://www.googleapis.com/auth/androidpublisher",
                            "aud": "https://oauth2.googleapis.com/token", "iat": now, "exp": now + 3600},
                           sa["private_key"], algorithm="RS256")
    r = requests.post("https://oauth2.googleapis.com/token", timeout=30,
                      data={"grant_type": "urn:ietf:params:oauth:grant-type:jwt-bearer", "assertion": assertion})
    r.raise_for_status()
    return r.json()["access_token"]


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--dry-run", action="store_true", help="validate files only")
    a = ap.parse_args()
    locales = sorted(p.name for p in TEXT.iterdir() if p.is_dir())
    listings = {}
    for loc in locales:
        t, s, f = ((TEXT / loc / n).read_text().strip() for n in ("title.txt", "short_description.txt", "full_description.txt"))
        assert len(t) <= 30 and len(s) <= 80 and len(f) <= 4000, loc
        listings[loc] = {"language": loc, "title": t, "shortDescription": s, "fullDescription": f}
    shots = sorted((ROOT / "marketing/store/android/phone").glob("*.png"))
    images = {"icon": [ROOT / "marketing/play/icon-512.png"],
              "featureGraphic": [ROOT / "marketing/play/feature-graphic-1024x500.png"],
              "phoneScreenshots": shots}
    print(f"{len(listings)} listings, {len(shots)} phone screenshots")
    if a.dry_run:
        return
    h = {"Authorization": f"Bearer {access_token()}"}
    edit = requests.post(f"{API}/edits", headers=h, timeout=30)
    edit.raise_for_status()
    eid = edit.json()["id"]
    for loc, body in listings.items():
        r = requests.put(f"{API}/edits/{eid}/listings/{loc}", headers=h, json=body, timeout=30)
        r.raise_for_status()
        print(f"listing {loc}: ok")
    for kind, files in images.items():
        requests.delete(f"{API}/edits/{eid}/listings/{DEFAULT}/{kind}", headers=h, timeout=30)
        for f in files:
            r = requests.post(f"{UPLOAD}/edits/{eid}/listings/{DEFAULT}/{kind}?uploadType=media", timeout=120,
                              headers={**h, "Content-Type": "image/png"}, data=f.read_bytes())
            r.raise_for_status()
        print(f"{kind}: {len(files)} uploaded")
    r = requests.post(f"{API}/edits/{eid}:commit", headers=h, timeout=60)
    if r.status_code != 200 and "changesNotSentForReview" in r.text:
        r = requests.post(f"{API}/edits/{eid}:commit?changesNotSentForReview=true", headers=h, timeout=60)
    if r.status_code != 200:
        print("commit failed:", r.text[:500])
        r.raise_for_status()
    print("edit committed")


if __name__ == "__main__":
    main()
