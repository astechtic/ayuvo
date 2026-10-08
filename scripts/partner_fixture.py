#!/usr/bin/env python3
"""Regenerates shared/partner/fixtures/partner-sample.ayuvo.zip and partner-sample.json (docs/partner-sync.md §13).

Dev tool only: needs the `cryptography` package for Ed25519 / X25519 (the contract check does not). Keys come from
fixed seeds so the output is byte-identical on every run. Both apps' tests open the zip, run package_validate with
the sender key from partner-sample.json, verify the Ed25519 signature over the stored manifest.json bytes, import
it with package_import and compare the counts in partner-sample.json["expected"]; a second import must be all
duplicates. Tests also flip one byte of a health file and expect hash_mismatch, and swap the signature key and
expect bad_signature.

    python3 scripts/partner_fixture.py
"""

import hashlib
import io
import json
import os
import sys
import zipfile

from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey
from cryptography.hazmat.primitives.asymmetric.x25519 import X25519PrivateKey

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
OUT = os.path.join(ROOT, "shared", "partner", "fixtures")
sys.path.insert(0, HERE)
import partner_reference as P  # noqa: E402

NOW = 1791374400000
SENDER = "0b8f3c2a-5d1e-4c7a-9f00-1a2b3c4d5e6f"
RECIPIENT = "7e6d5c4b-3a29-4180-8f7e-6d5c4b3a2918"
RAW = serialization.Encoding.Raw
PUB = serialization.PublicFormat.Raw


def seed(label):
    return hashlib.sha256(label.encode()).digest()


def envelopes():
    def env(t, rid, cat, rev, data, day=None):
        return {"type": t, "id": rid, "category": cat, "rev": rev, "deleted": False, "updated_ms": NOW - 60000,
                "day": day, "data": data}
    vitals = [
        env("metric_day", "resting_heart_rate:2026-10-07", "vitals", 1,
            {"type_id": "resting_heart_rate", "day": "2026-10-07", "unit": "bpm", "avg": 58, "min": 55, "max": 61,
             "count": 3}, "2026-10-07"),
        env("metric_day", "steps:2026-10-07", "vitals", 4,
            {"type_id": "steps", "day": "2026-10-07", "unit": "count", "sum": 8421, "count": 120}, "2026-10-07"),
        env("analytics_day", "recovery_indicator:2026-10-07", "vitals", 6,
            {"metric_id": "recovery_indicator", "day": "2026-10-07", "status": "ok", "classification": "good",
             "value": 81.6, "unit": "score"}, "2026-10-07"),
        env("sample", "hk-1", "vitals", 8,
            {"type_id": "heart_rate", "start_ms": NOW - 60000, "end_ms": NOW - 60000, "unit": "bpm", "value": 72},
            "2026-10-07"),
    ]
    sleep = [env("sleep_night", "2026-10-07", "sleep", 2,
                 {"day": "2026-10-07", "start_ms": NOW - 36000000, "end_ms": NOW - 7200000, "asleep_min": 444,
                  "deep_min": 70, "rem_min": 95, "light_min": 250, "awake_min": 29}, "2026-10-07")]
    nutrition = [env("food_entry", "f0e1d2c3-0000-4000-8000-000000000001", "nutrition", 3,
                     {"name": "Oats with milk", "logged_ms": NOW - 14400000, "calories": 320, "meal": "breakfast",
                      "protein_g": 12.5, "carbs_g": 54, "fat_g": 6}, "2026-10-07"),
                 env("water_day", "2026-10-07", "nutrition", 7, {"day": "2026-10-07", "total_ml": 1750}, "2026-10-07")]
    medicines = [env("medication", "med-1", "medicines", 5,
                     {"name": "Vitamin D3", "form": "capsule", "dose_quantity": 1, "dose_unit": "capsule",
                      "start_date": "2026-09-01", "status": "active", "is_prn": False}),
                 env("dose_log", "log-1", "medicines", 9,
                     {"medication_id": "med-1", "scheduled_at_ms": NOW - 3600000, "status": "taken",
                      "taken_at_ms": NOW - 3500000, "dose_quantity": 1, "dose_unit": "capsule"}, "2026-10-07")]
    reports = [env("report_overview", "rec-1", "report_overviews", 10,
                   {"title": "Complete Blood Count", "record_type": "lab_report", "category": "blood",
                    "report_date": "2026-10-01", "doctor": "Dr. Sharma", "facility": "XYZ Hospital",
                    "summary": "Mild anaemia; other counts normal.", "highlights": ["Hemoglobin: 11.2 g/dL (low)"],
                    "results": [{"name": "Hemoglobin", "analyte_id": "hemoglobin", "value": "11.2", "value_num": 11.2,
                                 "unit": "g/dL", "ref_low": 12, "ref_high": 15, "ref_text": "12-15", "flag": "low"},
                                {"name": "Vitamin B12", "analyte_id": "vitamin_b12", "value": "320", "value_num": 320,
                                 "unit": "pg/mL", "ref_low": 200, "ref_high": 900, "flag": "normal"}],
                    "abnormal": [{"name": "Hemoglobin", "analyte_id": "hemoglobin", "value": "11.2", "value_num": 11.2,
                                  "unit": "g/dL", "ref_low": 12, "ref_high": 15, "ref_text": "12-15", "flag": "low"}]},
                   "2026-10-01")]
    # workouts intentionally absent: a granted-but-empty category has no file.
    return {"vitals": vitals, "sleep": sleep, "nutrition": nutrition, "medicines": medicines,
            "report_overviews": reports}


def ndjson(rows):
    return "".join(json.dumps(r, sort_keys=True, separators=(",", ":"), ensure_ascii=False) + "\n"
                   for r in rows).encode("utf-8")


def main():
    ed = Ed25519PrivateKey.from_private_bytes(seed("ayuvo-partner-fixture-ed25519"))
    x = X25519PrivateKey.from_private_bytes(seed("ayuvo-partner-fixture-x25519"))
    ed_pub = P.b64url_encode(ed.public_key().public_bytes(RAW, PUB))
    x_pub = P.b64url_encode(x.public_key().public_bytes(RAW, PUB))
    files = envelopes()
    categories = ["vitals", "sleep", "nutrition", "workouts", "medicines", "report_overviews"]
    blobs = {
        "profile/partner.json": (json.dumps({"device_id": SENDER, "name": "Ananya", "platform": "android",
                                             "fingerprint": P.fingerprint(x_pub, ed_pub)},
                                            indent=2, sort_keys=True) + "\n").encode(),
        "sync/revision.json": (json.dumps({"from_rev": 0, "to_rev": 12}, indent=2, sort_keys=True) + "\n").encode(),
    }
    for cat in P.CATEGORIES:
        if cat in files:
            blobs["health/%s.ndjson" % cat] = ndjson(files[cat])
    order = ["profile/partner.json", "sync/revision.json"] + ["health/%s.ndjson" % c for c in P.CATEGORIES if c in files]
    manifest = {"format": "ayuvo-partner-sync", "version": 1, "export_id": "3c3c3c3c-1111-4222-8333-444455556666",
                "device_id": SENDER, "recipient_device_id": RECIPIENT, "created_ms": NOW, "from_rev": 0,
                "to_rev": 12, "categories": categories,
                "files": [{"name": n, "sha256": hashlib.sha256(blobs[n]).hexdigest(), "bytes": len(blobs[n]),
                           "count": len(files[n[7:-7]]) if n.startswith("health/") else 1} for n in order]}
    manifest_bytes = (json.dumps(manifest, indent=2, sort_keys=True) + "\n").encode()
    signature = (json.dumps({"alg": "Ed25519", "key": ed_pub,
                             "sig": P.b64url_encode(ed.sign(manifest_bytes))}, indent=2, sort_keys=True) + "\n").encode()

    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w", zipfile.ZIP_DEFLATED) as z:
        for name, data in [("manifest.json", manifest_bytes), ("signature.json", signature)] + [(n, blobs[n]) for n in order]:
            info = zipfile.ZipInfo(name, date_time=(2026, 10, 7, 12, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = 0o644 << 16
            z.writestr(info, data)
    os.makedirs(OUT, exist_ok=True)
    with open(os.path.join(OUT, "partner-sample.ayuvo.zip"), "wb") as fh:
        fh.write(buf.getvalue())

    entries = [{"name": "manifest.json", "sha256": hashlib.sha256(manifest_bytes).hexdigest()},
               {"name": "signature.json", "sha256": hashlib.sha256(signature).hexdigest()}] + \
              [{"name": n, "sha256": hashlib.sha256(blobs[n]).hexdigest()} for n in order]
    validation = P.package_validate(manifest, entries, True, [SENDER], RECIPIENT, NOW)
    first = P.package_import([], 0, manifest, files, NOW)
    existing = [{"type": u["type"], "record_id": u["record_id"], "rev": u["rev"]} for u in first["upserts"]]
    second = P.package_import(existing, first["cursor"], manifest, files, NOW)
    meta = {"format": "ayuvo-partner-fixture", "version": 1, "now_ms": NOW, "sender_device_id": SENDER,
            "recipient_device_id": RECIPIENT, "sender_ed25519": ed_pub, "sender_x25519": x_pub,
            "sender_fingerprint": P.fingerprint(x_pub, ed_pub),
            "expected": {"validate": validation, "summary": P.package_summary(manifest["files"], categories),
                         "first_import": {"cursor": first["cursor"], "counts": first["counts"]},
                         "second_import": {"cursor": second["cursor"], "counts": second["counts"]}}}
    with open(os.path.join(OUT, "partner-sample.json"), "w", encoding="utf-8") as fh:
        fh.write(json.dumps(meta, indent=2, sort_keys=True, ensure_ascii=False) + "\n")
    print("wrote fixtures:", meta["expected"])


if __name__ == "__main__":
    main()
