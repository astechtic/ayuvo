#!/usr/bin/env python3
"""Fill the Ayuvo App Store Connect record: localized listings, age rating, review notes, screenshots, price.

    python3 marketing/store-listing/asc_publish.py [--step listings|age|review|rights|screens|price|all]

Credentials: ASC_KEY_ID, ASC_ISSUER_ID and ASC_KEY_FILE (path to the .p8). The key is only read to sign
short-lived JWTs; it is never printed or written anywhere. Requires: pyjwt, cryptography, requests.
Idempotent: existing localizations are updated, existing screenshots are left alone.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import sys
import time
from pathlib import Path

import jwt
import requests

sys.path.insert(0, str(Path(__file__).parent))
import listings  # noqa: E402

ROOT = Path(__file__).resolve().parents[2]
API = "https://api.appstoreconnect.apple.com"
APP_ID = "6811947230"
PRIVACY = "https://ayuvo-health.web.app/privacy"
SUPPORT = "https://ayuvo-health.web.app/support"
MARKETING = "https://ayuvo-health.web.app/"

_tok = {"t": None, "exp": 0}


def token() -> str:
    if _tok["t"] and time.time() < _tok["exp"] - 60:
        return _tok["t"]
    key = Path(os.environ["ASC_KEY_FILE"]).read_text()
    exp = int(time.time()) + 15 * 60
    _tok["t"] = jwt.encode({"iss": os.environ["ASC_ISSUER_ID"], "exp": exp, "aud": "appstoreconnect-v1"}, key,
                           algorithm="ES256", headers={"kid": os.environ["ASC_KEY_ID"], "typ": "JWT"})
    _tok["exp"] = exp
    return _tok["t"]


def call(method: str, path: str, body: dict | None = None, ok=(200, 201, 204)) -> dict:
    r = requests.request(method, API + path, json=body, timeout=60,
                         headers={"Authorization": f"Bearer {token()}", "Content-Type": "application/json"})
    if r.status_code not in ok:
        errs = r.json().get("errors", []) if r.content else []
        msg = "; ".join(f"{e.get('code')}: {e.get('detail')}" for e in errs) or r.text[:300]
        raise RuntimeError(f"{method} {path} -> {r.status_code} {msg}")
    return r.json() if r.content else {}


def ios_version_id() -> str:
    d = call("GET", f"/v1/apps/{APP_ID}/appStoreVersions?filter[platform]=IOS&filter[appStoreState]=PREPARE_FOR_SUBMISSION")
    return d["data"][0]["id"]


def app_info_id() -> str:
    return call("GET", f"/v1/apps/{APP_ID}/appInfos")["data"][0]["id"]


def step_listings() -> None:
    info = app_info_id()
    ver = ios_version_id()
    have_info = {x["attributes"]["locale"]: x["id"] for x in call("GET", f"/v1/appInfos/{info}/appInfoLocalizations?limit=50")["data"]}
    have_ver = {x["attributes"]["locale"]: x["id"] for x in call("GET", f"/v1/appStoreVersions/{ver}/appStoreVersionLocalizations?limit=50")["data"]}
    for lang, loc in listings.ASC_LOCALE.items():
        d = listings.L[lang]
        ia = {"name": d["name"], "subtitle": d["subtitle"], "privacyPolicyUrl": PRIVACY}
        if loc in have_info:
            call("PATCH", f"/v1/appInfoLocalizations/{have_info[loc]}", {"data": {"type": "appInfoLocalizations", "id": have_info[loc], "attributes": ia}})
        else:
            call("POST", "/v1/appInfoLocalizations", {"data": {"type": "appInfoLocalizations", "attributes": {"locale": loc, **ia},
                 "relationships": {"appInfo": {"data": {"type": "appInfos", "id": info}}}}})
        if loc not in have_ver:  # creating the app-info locale also creates the version locale
            have_ver = {x["attributes"]["locale"]: x["id"] for x in call("GET", f"/v1/appStoreVersions/{ver}/appStoreVersionLocalizations?limit=50")["data"]}
        va = {"description": listings.description(lang, "ios"), "keywords": d["keywords"], "promotionalText": d["promo"],
              "supportUrl": SUPPORT, "marketingUrl": MARKETING}
        if loc in have_ver:
            call("PATCH", f"/v1/appStoreVersionLocalizations/{have_ver[loc]}", {"data": {"type": "appStoreVersionLocalizations", "id": have_ver[loc], "attributes": va}})
        else:
            call("POST", "/v1/appStoreVersionLocalizations", {"data": {"type": "appStoreVersionLocalizations", "attributes": {"locale": loc, **va},
                 "relationships": {"appStoreVersion": {"data": {"type": "appStoreVersions", "id": ver}}}}})
        print(f"listing {loc}: ok")


def step_age() -> None:
    decl = call("GET", f"/v1/appInfos/{app_info_id()}/ageRatingDeclaration")["data"]["id"]
    none = "NONE"
    attrs = {
        "alcoholTobaccoOrDrugUseOrReferences": none, "contests": none, "gamblingSimulated": none,
        "gunsOrOtherWeapons": none, "horrorOrFearThemes": none, "matureOrSuggestiveThemes": none,
        "medicalOrTreatmentInformation": "INFREQUENT_OR_MILD", "profanityOrCrudeHumor": none,
        "sexualContentGraphicAndNudity": none, "sexualContentOrNudity": none, "violenceCartoonOrFantasy": none,
        "violenceRealistic": none, "violenceRealisticProlongedGraphicOrSadistic": none,
        "gambling": False, "lootBox": False, "unrestrictedWebAccess": False, "advertising": False,
        "healthOrWellnessTopics": True, "messagingAndChat": False, "parentalControls": False,
        "ageAssurance": False, "userGeneratedContent": False, "socialMedia": False,
    }
    for attempt in range(6):
        try:
            call("PATCH", f"/v1/ageRatingDeclarations/{decl}", {"data": {"type": "ageRatingDeclarations", "id": decl, "attributes": attrs}})
            print("age rating: ok", "(" + ", ".join(f"{k}={v}" for k, v in attrs.items() if v not in (none, False)) + ")")
            return
        except RuntimeError as e:  # drop attributes this API version does not know and retry
            msg = str(e)
            if "ATTRIBUTE.REQUIRED" in msg:  # a newly added question: answer it as "No" / "None"
                req = msg.split("attribute '")[1].split("'")[0]
                attrs[req] = False
                continue
            bad = [k for k in list(attrs) if f"'{k}'" in msg or f"/{k}" in msg or f" {k} " in msg]
            if "INFREQUENT_OR_MILD" in msg and attrs["medicalOrTreatmentInformation"] == "INFREQUENT_OR_MILD":
                attrs["medicalOrTreatmentInformation"] = "INFREQUENT"
                continue
            if not bad:
                raise
            for k in bad:
                attrs.pop(k)
    raise RuntimeError("age rating: could not converge")


def step_review() -> None:
    ver = ios_version_id()
    notes = Path(ROOT / "APPSTORE.md").read_text()
    i = notes.index("## Reviewer Notes")
    a = notes.index("```\n", i) + 4
    notes = notes[a:notes.index("```", a)].strip()
    attrs = {"contactEmail": "yaaratech@gmail.com", "notes": notes, "demoAccountRequired": False}
    d = call("GET", f"/v1/appStoreVersions/{ver}/appStoreReviewDetail", ok=(200, 404))
    if d.get("data"):
        rid = d["data"]["id"]
        call("PATCH", f"/v1/appStoreReviewDetails/{rid}", {"data": {"type": "appStoreReviewDetails", "id": rid, "attributes": attrs}})
    else:
        call("POST", "/v1/appStoreReviewDetails", {"data": {"type": "appStoreReviewDetails", "attributes": attrs,
             "relationships": {"appStoreVersion": {"data": {"type": "appStoreVersions", "id": ver}}}}})
    print(f"review details: ok (notes {len(notes)} chars; contact name/phone left for the owner)")


def step_rights() -> None:
    # Open Food Facts data and the exercise media (Gym visual) are third-party content used under their licences.
    call("PATCH", f"/v1/apps/{APP_ID}", {"data": {"type": "apps", "id": APP_ID, "attributes": {"contentRightsDeclaration": "USES_THIRD_PARTY_CONTENT"}}})
    print("content rights: USES_THIRD_PARTY_CONTENT")


def upload_set(loc_id: str, display: str, files: list[Path]) -> None:
    sets = call("GET", f"/v1/appStoreVersionLocalizations/{loc_id}/appScreenshotSets?limit=50")["data"]
    sset = next((s for s in sets if s["attributes"]["screenshotDisplayType"] == display), None)
    if not sset:
        sset = call("POST", "/v1/appScreenshotSets", {"data": {"type": "appScreenshotSets", "attributes": {"screenshotDisplayType": display},
                    "relationships": {"appStoreVersionLocalization": {"data": {"type": "appStoreVersionLocalizations", "id": loc_id}}}}})["data"]
    existing = call("GET", f"/v1/appScreenshotSets/{sset['id']}/appScreenshots?limit=20")["data"]
    if existing:
        print(f"screenshots {display}: {len(existing)} already uploaded, skipped")
        return
    for f in files:
        data = f.read_bytes()
        shot = call("POST", "/v1/appScreenshots", {"data": {"type": "appScreenshots", "attributes": {"fileName": f.name, "fileSize": len(data)},
                    "relationships": {"appScreenshotSet": {"data": {"type": "appScreenshotSets", "id": sset["id"]}}}}})["data"]
        for op in shot["attributes"]["uploadOperations"]:
            chunk = data[op["offset"]:op["offset"] + op["length"]]
            hdrs = {h["name"]: h["value"] for h in op.get("requestHeaders", [])}
            r = requests.request(op["method"], op["url"], data=chunk, headers=hdrs, timeout=120)
            r.raise_for_status()
        call("PATCH", f"/v1/appScreenshots/{shot['id']}", {"data": {"type": "appScreenshots", "id": shot["id"],
             "attributes": {"uploaded": True, "sourceFileChecksum": hashlib.md5(data).hexdigest()}}})
        print(f"screenshots {display}: uploaded {f.name}")


def step_screens() -> None:
    ver = ios_version_id()
    locs = {x["attributes"]["locale"]: x["id"] for x in call("GET", f"/v1/appStoreVersions/{ver}/appStoreVersionLocalizations?limit=50")["data"]}
    en = locs["en-US"]
    upload_set(en, "APP_IPHONE_67", sorted((ROOT / "marketing/store/ios/6.9").glob("*.png")))
    upload_set(en, "APP_WATCH_ULTRA", sorted((ROOT / "marketing/appstore/watch").glob("*.png")))
    # Real iPad captures (iPad Pro 13-inch simulator) in the 3D tablet frame; the app supports iPad.
    upload_set(en, "APP_IPAD_PRO_3GEN_129", sorted((ROOT / "marketing/store/ios/ipad-13").glob("*.png")))


def step_price() -> None:
    d = call("GET", f"/v1/apps/{APP_ID}/appPriceSchedule", ok=(200, 404))
    if d.get("data") and call("GET", f"/v1/appPriceSchedules/{d['data']['id']}/manualPrices?limit=1")["data"]:
        print("price: schedule already exists, left unchanged")
    else:
        pts = call("GET", f"/v1/apps/{APP_ID}/appPricePoints?filter[territory]=USA&limit=200")["data"]
        free = next(p for p in pts if float(p["attributes"]["customerPrice"]) == 0.0)
        call("POST", "/v1/appPriceSchedules", {
            "data": {"type": "appPriceSchedules", "relationships": {
                "app": {"data": {"type": "apps", "id": APP_ID}},
                "baseTerritory": {"data": {"type": "territories", "id": "USA"}},
                "manualPrices": {"data": [{"type": "appPrices", "id": "${free}"}]}}},
            "included": [{"type": "appPrices", "id": "${free}", "attributes": {"startDate": None},
                          "relationships": {"appPricePoint": {"data": {"type": "appPricePoints", "id": free["id"]}}}}]})
        print("price: Free (base territory USA)")
    a = call("GET", f"/v1/apps/{APP_ID}/appAvailabilityV2", ok=(200, 404))
    if a.get("data"):
        print("availability: already set, left unchanged")
    else:
        terrs = []
        url = "/v1/territories?limit=200"
        while url:
            page = call("GET", url)
            terrs += [t["id"] for t in page["data"]]
            nxt = page.get("links", {}).get("next")
            url = nxt.replace(API, "") if nxt else None
        inc = [{"type": "territoryAvailabilities", "id": f"${{{t}}}", "attributes": {"available": True},
                "relationships": {"territory": {"data": {"type": "territories", "id": t}}}} for t in terrs]
        call("POST", "/v2/appAvailabilities", {
            "data": {"type": "appAvailabilities", "attributes": {"availableInNewTerritories": True},
                     "relationships": {"app": {"data": {"type": "apps", "id": APP_ID}},
                                       "territoryAvailabilities": {"data": [{"type": "territoryAvailabilities", "id": i["id"]} for i in inc]}}},
            "included": inc})
        print(f"availability: all {len(terrs)} territories + new territories")


STEPS = {"listings": step_listings, "age": step_age, "review": step_review, "rights": step_rights,
         "screens": step_screens, "price": step_price}

if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--step", default="all", choices=[*STEPS, "all"])
    a = ap.parse_args()
    if listings.check():
        sys.exit(f"listing limits: {listings.check()}")
    for name, fn in STEPS.items():
        if a.step in ("all", name):
            try:
                fn()
            except Exception as e:  # report and continue with the other steps
                print(f"{name}: FAILED {e}")
