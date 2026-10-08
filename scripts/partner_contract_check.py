#!/usr/bin/env python3
"""Ayuvo Partner Health Sync contract check (Python 3 stdlib only).

    python3 scripts/partner_contract_check.py           # verify; exit 1 on any problem
    python3 scripts/partner_contract_check.py --write   # rewrite every vector's "expected" from the reference

Checks:
  1. shared/partner/test-vectors/*.json: envelope shape, unique case names, stable formatting (sorted keys, 2-space
     indent, UTF-8, trailing newline) and every case's "expected" equals scripts/partner_reference.py run on its
     "input". Every file in EXPECTED_FILES exists and no unknown file is present. noise_*.json are the published
     cacophony vectors (crypto, not computed here): only their presence and shape are checked.
  2. shared/partner/schema.sql splits with the records statements() rule; table and index names equal the
     documented lists; migrations/ matches MIGRATION_FILES.
  3. record_types.json / protocol.json consistency: every type has a category in CATEGORIES (or "health"), health
     allow-lists only name metric_registry ids, intraday/hourly types are in the allow-lists, no cycle/symptom/mental
     type is shareable, package entry names stay inside the allow-list.
  4. Privacy: no map vector output contains a forbidden key (file paths, evidence, notes, photos, tracks) and every
     report_overview output keeps only documented keys.
  5. Merge idempotency: re-applying every accepted merge vector's batch on top of its own result changes nothing.
  6. The doc names every function in EXPECTED_FILES and every error code.
  7. fixtures/partner-sample.ayuvo.zip (made by scripts/partner_fixture.py) passes package_validate with
     signature_ok=True, imports to the counts in partner-sample.json and holds no non-JSON entry (the Ed25519
     signature itself is verified by the app tests; this stdlib-only check cannot).
"""

import glob
import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
SHARED = os.path.join(ROOT, "shared", "partner")
VECTORS = os.path.join(SHARED, "test-vectors")
DOC = os.path.join(ROOT, "docs", "partner-sync.md")
sys.path.insert(0, HERE)
import partner_reference as P  # noqa: E402
from records_contract_check import statements  # noqa: E402

FORMAT = "ayuvo-partner-vectors"
EXPECTED_FILES = {
    "qr.json": "qr", "kdf.json": "kdf", "envelope_validate.json": "envelope_validate",
    "merge_apply.json": "merge_apply", "grants_received.json": "grants_received",
    "retention_prune.json": "retention_prune", "ledger.json": "ledger", "map.json": "map",
    "message_validate.json": "message_validate", "session.json": "session", "package.json": "package",
    "summary_metrics.json": "summary_metrics",
}
NOISE_FILES = ["noise_ikpsk2.json", "noise_kk.json"]
TABLES = ["partner_meta", "partners", "partner_grants_out", "partner_grants_received", "partner_records",
          "partner_sync_state", "outbound_ledger"]
INDEXES = ["idx_pr_owner_type_day", "idx_pr_owner_type_ts", "idx_pr_owner_category", "idx_ol_rev", "idx_ol_type_day"]
NOT_SHAREABLE_PREFIXES = ("symptom_", "menstrual", "ovulation", "cervical", "sexual", "pregnancy", "contraceptive",
                          "lactation", "menopausal", "assessment_", "state_of_mind", "mindfulness")


def dumps(obj):
    return json.dumps(obj, indent=2, sort_keys=True, ensure_ascii=False) + "\n"


def first_diff(a, b, path="$"):
    if isinstance(a, bool) or isinstance(b, bool):
        return None if a is b or a == b and type(a) == type(b) else "%s: %r != %r" % (path, a, b)
    if isinstance(a, (int, float)) and isinstance(b, (int, float)):
        return None if a == b else "%s: %r != %r" % (path, a, b)
    if type(a) != type(b):
        return "%s: %r != %r" % (path, a, b)
    if isinstance(a, dict):
        for k in sorted(set(a) | set(b)):
            if k not in a or k not in b:
                return "%s.%s: missing on one side" % (path, k)
            d = first_diff(a[k], b[k], "%s.%s" % (path, k))
            if d:
                return d
        return None
    if isinstance(a, list):
        if len(a) != len(b):
            return "%s: length %d != %d" % (path, len(a), len(b))
        for i, (x, y) in enumerate(zip(a, b)):
            d = first_diff(x, y, "%s[%d]" % (path, i))
            if d:
                return d
        return None
    return None if a == b else "%s: %r != %r" % (path, a, b)


def check_vectors(write, problems):
    present = sorted(os.path.basename(p) for p in glob.glob(os.path.join(VECTORS, "*.json")))
    for name in present:
        if name not in EXPECTED_FILES and name not in NOISE_FILES:
            problems.append("unknown vector file %s" % name)
    for name in NOISE_FILES:
        path = os.path.join(VECTORS, name)
        if not os.path.exists(path):
            problems.append("missing %s (cacophony vectors)" % name)
            continue
        with open(path, encoding="utf-8") as fh:
            doc = json.load(fh)
        if doc.get("format") != "ayuvo-partner-noise-vectors" or not doc.get("vectors"):
            problems.append("%s: bad envelope" % name)
    for name, function in sorted(EXPECTED_FILES.items()):
        path = os.path.join(VECTORS, name)
        if not os.path.exists(path):
            problems.append("missing vector file %s" % name)
            continue
        with open(path, encoding="utf-8") as fh:
            text = fh.read()
        doc = json.loads(text)
        if doc.get("format") != FORMAT or doc.get("version") != 1 or doc.get("function") != function:
            problems.append("%s: bad envelope" % name)
        names = [c["name"] for c in doc["cases"]]
        if len(set(names)) != len(names):
            problems.append("%s: duplicate case names" % name)
        changed = False
        for case in doc["cases"]:
            got = P.run_case(function, case["input"])
            got = json.loads(json.dumps(got))
            if write:
                if case.get("expected") != got:
                    case["expected"] = got
                    changed = True
                continue
            d = first_diff(case.get("expected"), got)
            if d:
                problems.append("%s/%s: %s" % (name, case["name"], d))
        if write and (changed or dumps(doc) != text):
            with open(path, "w", encoding="utf-8") as fh:
                fh.write(dumps(doc))
        elif not write and dumps(doc) != text:
            problems.append("%s: not canonically formatted (run --write)" % name)


def check_sql(problems):
    stmts = statements(os.path.join(SHARED, "schema.sql"))
    tables, indexes = [], []
    for s in stmts:
        head = s.split("(")[0].split()
        if head[:2] == ["CREATE", "TABLE"]:
            tables.append(head[2])
        elif head[:2] == ["CREATE", "INDEX"]:
            indexes.append(head[2])
        else:
            problems.append("schema.sql: unexpected statement %r" % s[:40])
        if "ON CONFLICT" in s.upper() or "UPSERT" in s.upper():
            problems.append("schema.sql: SQLite 3.18 has no upsert")
    if tables != TABLES:
        problems.append("schema.sql tables %s != %s" % (tables, TABLES))
    if indexes != INDEXES:
        problems.append("schema.sql indexes %s != %s" % (indexes, INDEXES))
    migrations = sorted(os.path.basename(p) for p in glob.glob(os.path.join(SHARED, "migrations", "*.sql")))
    if migrations != P.MIGRATION_FILES or P.SCHEMA_VERSION != 1 + len(migrations):
        problems.append("migrations %s vs reference %s / v%d" % (migrations, P.MIGRATION_FILES, P.SCHEMA_VERSION))


def check_catalogs(problems):
    with open(os.path.join(ROOT, "shared", "health", "metric_registry.json"), encoding="utf-8") as fh:
        registry = set(m["id"] for m in json.load(fh)["metrics"])
    for t in P.RECORD_TYPES["types"]:
        if t["category"] not in P.CATEGORIES and t["category"] != "health":
            problems.append("record type %s: unknown category %s" % (t["type"], t["category"]))
        if t["ts"] is not None and t["ts"] not in t["required"] + t["optional"]:
            problems.append("record type %s: ts field %s not declared" % (t["type"], t["ts"]))
        for f in t["required"] + t["optional"]:
            if f in P.FORBIDDEN_KEYS:
                problems.append("record type %s declares forbidden field %s" % (t["type"], f))
    for cat, ids in P.RECORD_TYPES["health_types"].items():
        if cat not in P.CATEGORIES:
            problems.append("health_types: unknown category %s" % cat)
        for tid in ids:
            if tid not in registry:
                problems.append("health_types.%s: %s not in metric_registry" % (cat, tid))
            if tid.startswith(NOT_SHAREABLE_PREFIXES):
                problems.append("health_types.%s: %s must not be shareable" % (cat, tid))
    for tid in list(P.INTRADAY_TYPES) + list(P.HOURLY_TYPES):
        if tid not in P.HEALTH_TYPE_CATEGORY:
            problems.append("intraday/hourly type %s is not in health_types" % tid)
    for name in P.package_entry_names(P.CATEGORIES):
        if not (name.endswith(".json") or name.endswith(".ndjson")):
            problems.append("package entry %s is not json/ndjson" % name)
    if P.PROTOCOL["version"] != 1 or P.PACKAGE["version"] != 1:
        problems.append("protocol/package version changed without a migration note")


REPORT_KEYS = set(next(t for t in P.RECORD_TYPES["types"] if t["type"] == "report_overview")["required"]) | \
    set(next(t for t in P.RECORD_TYPES["types"] if t["type"] == "report_overview")["optional"])


def _keys_deep(value, out):
    if isinstance(value, dict):
        for k, v in value.items():
            out.add(k)
            _keys_deep(v, out)
    elif isinstance(value, list):
        for v in value:
            _keys_deep(v, out)
    return out


def check_privacy(problems):
    with open(os.path.join(VECTORS, "map.json"), encoding="utf-8") as fh:
        doc = json.load(fh)
    for case in doc["cases"]:
        env = case["expected"]["envelope"]
        if env is None:
            continue
        bad = _keys_deep(env["data"], set()) & P.FORBIDDEN_KEYS
        if bad:
            problems.append("map/%s: forbidden keys %s" % (case["name"], sorted(bad)))
        if env["type"] == "report_overview" and not set(env["data"]) <= REPORT_KEYS:
            problems.append("map/%s: undocumented report keys %s" % (case["name"], sorted(set(env["data"]) - REPORT_KEYS)))
        v = P.envelope_validate(dict(env, rev=1, deleted=False, updated_ms=0), 0)
        if not v["ok"]:
            problems.append("map/%s: mapped envelope fails validation (%s)" % (case["name"], v["error"]))


def check_idempotency(problems):
    with open(os.path.join(VECTORS, "merge_apply.json"), encoding="utf-8") as fh:
        doc = json.load(fh)
    for case in doc["cases"]:
        inp, exp = case["input"], case["expected"]
        if not exp["accepted"]:
            continue
        stored = dict(((r["type"], r["record_id"]), r["rev"]) for r in inp.get("existing") or [])
        for d in exp["deletes"]:
            stored.pop((d["type"], d["record_id"]), None)
        for u in exp["upserts"]:
            stored[(u["type"], u["record_id"])] = u["rev"]
        existing = [{"type": k[0], "record_id": k[1], "rev": v} for k, v in sorted(stored.items())]
        again = P.merge_apply(existing, inp["batch"], exp["cursor"], inp["granted"], inp["now_ms"])
        if again["upserts"] or again["deletes"] or again["cursor"] != exp["cursor"]:
            problems.append("merge/%s: not idempotent on replay" % case["name"])


def check_doc(problems):
    if not os.path.exists(DOC):
        problems.append("docs/partner-sync.md missing")
        return
    with open(DOC, encoding="utf-8") as fh:
        text = fh.read()
    for function in sorted(set(EXPECTED_FILES.values())):
        if "`%s`" % function not in text:
            problems.append("doc does not name function `%s`" % function)
    for code in P.PROTOCOL["error_codes"] + P.PROTOCOL["statuses"]:
        if "`%s`" % code not in text:
            problems.append("doc does not name `%s`" % code)
    for code in ("not_package", "unknown_sender", "wrong_recipient", "bad_signature", "unexpected_entry",
                 "hash_mismatch", "unsafe_entry", "missing_entry"):
        if "`%s`" % code not in text:
            problems.append("doc does not name package error `%s`" % code)


def check_fixture(problems):
    import hashlib
    import zipfile
    zpath = os.path.join(SHARED, "fixtures", "partner-sample.ayuvo.zip")
    mpath = os.path.join(SHARED, "fixtures", "partner-sample.json")
    if not os.path.exists(zpath) or not os.path.exists(mpath):
        problems.append("fixtures/partner-sample.* missing (python3 scripts/partner_fixture.py)")
        return
    with open(mpath, encoding="utf-8") as fh:
        meta = json.load(fh)
    with zipfile.ZipFile(zpath) as z:
        names = z.namelist()
        if names[0] != "manifest.json":
            problems.append("fixture: manifest.json is not the first entry")
        blobs = dict((n, z.read(n)) for n in names)
    manifest = json.loads(blobs["manifest.json"].decode("utf-8"))
    entries = [{"name": n, "sha256": hashlib.sha256(b).hexdigest()} for n, b in blobs.items()]
    v = P.package_validate(manifest, entries, True, [meta["sender_device_id"]], meta["recipient_device_id"],
                           meta["now_ms"])
    if v != meta["expected"]["validate"] or not v["ok"]:
        problems.append("fixture: package_validate %s" % v)
    files = {}
    for n, b in blobs.items():
        if n.startswith("health/"):
            files[n[len("health/"):-len(".ndjson")]] = [json.loads(line) for line in b.decode("utf-8").splitlines()]
    first = P.package_import([], 0, manifest, files, meta["now_ms"])
    if {"cursor": first["cursor"], "counts": first["counts"]} != meta["expected"]["first_import"]:
        problems.append("fixture: first import differs from partner-sample.json")
    sig = json.loads(blobs["signature.json"].decode("utf-8"))
    if sig.get("alg") != "Ed25519" or sig.get("key") != meta["sender_ed25519"]:
        problems.append("fixture: signature.json key/alg")


def main():
    write = "--write" in sys.argv
    problems = []
    check_vectors(write, problems)
    check_sql(problems)
    check_catalogs(problems)
    if not write:
        check_privacy(problems)
        check_idempotency(problems)
    check_doc(problems)
    check_fixture(problems)
    for p in problems:
        print("FAIL", p)
    if problems:
        sys.exit(1)
    print("partner contract OK (%d vector files)" % len(EXPECTED_FILES))


if __name__ == "__main__":
    main()
