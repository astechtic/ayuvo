#!/usr/bin/env python3
"""Ayuvo Partner Health Sync: executable reference for the shared partner-sync logic.

Contract: docs/partner-sync.md. Where this file and the prose disagree, this file wins and the prose is
fixed. Android (Kotlin, `partner/`) and iOS (Swift, `Partner/`) port it and run
shared/partner/test-vectors/*.json in their unit tests.

Portability rules (same as scripts/medications_reference.py):
  * Python 3 stdlib only. Every function is pure: no clock, randomness or I/O. "Now" is an explicit input.
  * Numbers compare by value in vectors; object key order never matters; array order does.
  * Crypto primitives (X25519, Ed25519, ChaCha20-Poly1305) are NOT in this file: the Noise ports are checked
    against the published cacophony vectors in shared/partner/test-vectors/noise_*.json. Only the stdlib-
    computable derivations (HKDF psk, SAS, fingerprint) live here.
"""

import base64
import math
import hashlib
import hmac
import json
import os
import re

_HERE = os.path.dirname(os.path.abspath(__file__))
_SHARED = os.path.join(os.path.dirname(_HERE), "shared", "partner")
with open(os.path.join(_SHARED, "record_types.json"), encoding="utf-8") as _fh:
    RECORD_TYPES = json.load(_fh)
with open(os.path.join(_SHARED, "protocol.json"), encoding="utf-8") as _fh:
    PROTOCOL = json.load(_fh)

# ---------------------------------------------------------------------------------------------
# §2 Constants
# ---------------------------------------------------------------------------------------------

PROTOCOL_ID = PROTOCOL["protocol"]
PROTOCOL_VERSION = PROTOCOL["version"]
CATEGORIES = tuple(RECORD_TYPES["categories"])
TYPES = dict((t["type"], t) for t in RECORD_TYPES["types"])
HEALTH_TYPE_CATEGORY = {}
for _cat, _ids in RECORD_TYPES["health_types"].items():
    for _tid in _ids:
        HEALTH_TYPE_CATEGORY[_tid] = _cat
INTRADAY_TYPES = frozenset(RECORD_TYPES["intraday_types"])
HOURLY_TYPES = frozenset(RECORD_TYPES["hourly_types"])
ANALYTICS_METRICS = frozenset(RECORD_TYPES["analytics_metrics"])
FORBIDDEN_KEYS = frozenset(RECORD_TYPES["forbidden_keys"])
STRING_MAX = RECORD_TYPES["string_max"]
ARRAY_MAX = RECORD_TYPES["array_max"]
DAY_MS = 86_400_000
INTRADAY_MS = RECORD_TYPES["intraday_days"] * DAY_MS
FUTURE_SKEW_MS = 10 * 60_000          # sender clocks may run ahead this much; beyond it timestamps are clamped

QR_PREFIX = PROTOCOL["qr_prefix"]
QR_TTL_MS = PROTOCOL["qr_ttl_ms"]
NAME_MAX = PROTOCOL["name_max"]
BATCH_MAX = PROTOCOL["batch_records_max"]
PACKAGE = PROTOCOL["package"]
MESSAGES = PROTOCOL["messages"]

SCHEMA_VERSION = 1
MIGRATION_FILES = []

_RE_DAY = re.compile("^[0-9]{4}-[0-9]{2}-[0-9]{2}$")
_RE_UUID = re.compile("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
_RE_B64URL = re.compile("^[A-Za-z0-9_-]+$")
_RE_HOST = re.compile("^[0-9a-fA-F.:%a-z]+:[0-9]{1,5}$")
_RE_SHA = re.compile("^[0-9a-f]{64}$")


def _full(rx, text):
    """Whole-string match. Python's `$` also matches before a final newline; ports use whole-string matching, so the
    reference does too."""
    return isinstance(text, str) and rx.fullmatch(text) is not None


def _is_int(v):
    return isinstance(v, int) and not isinstance(v, bool)


def _is_num(v):
    return isinstance(v, (int, float)) and not isinstance(v, bool)


# ---------------------------------------------------------------------------------------------
# §3 Encodings and key derivations
# ---------------------------------------------------------------------------------------------

def b64url_encode(raw):
    return base64.urlsafe_b64encode(raw).decode("ascii").rstrip("=")


def b64url_decode(text):
    """Strict: alphabet [A-Za-z0-9_-], no padding. Returns bytes or None."""
    if not isinstance(text, str) or not text or not _full(_RE_B64URL, text) or len(text) % 4 == 1:
        return None
    try:
        return base64.urlsafe_b64decode(text + "=" * (-len(text) % 4))
    except Exception:  # noqa: BLE001
        return None


def hkdf_sha256(ikm, salt, info, length):
    """RFC 5869."""
    prk = hmac.new(salt, ikm, hashlib.sha256).digest()
    out, block, counter = b"", b"", 1
    while len(out) < length:
        block = hmac.new(prk, block + info + bytes([counter]), hashlib.sha256).digest()
        out += block
        counter += 1
    return out[:length]


def pairing_psk(token_b64):
    """32-byte Noise psk from the QR token: HKDF-SHA256(ikm=token, salt, info)."""
    token = b64url_decode(token_b64)
    kdf = PROTOCOL["kdf"]
    return hkdf_sha256(token, kdf["psk_salt"].encode(), kdf["psk_info"].encode(), 32).hex()


def sas_code(handshake_hash_hex):
    """6-digit short authentication string both screens show after the pairing handshake."""
    h = bytes.fromhex(handshake_hash_hex)
    d = hashlib.sha256(PROTOCOL["kdf"]["sas_label"].encode() + h).digest()
    n = int.from_bytes(d[:4], "big") % 1_000_000
    return "%06d" % n


def fingerprint(x25519_b64, ed25519_b64):
    """Hex of the first 16 bytes of SHA-256(x25519_pub || ed25519_pub)."""
    raw = b64url_decode(x25519_b64) + b64url_decode(ed25519_b64)
    return hashlib.sha256(raw).digest()[:PROTOCOL["kdf"]["fingerprint_bytes"]].hex()


def format_fingerprint(fp_hex):
    """Display form: 8 groups of 4 upper-case hex digits."""
    up = fp_hex.upper()
    return " ".join(up[i:i + 4] for i in range(0, len(up), 4))


# ---------------------------------------------------------------------------------------------
# §4 QR pairing payload
# ---------------------------------------------------------------------------------------------

QR_FIELDS = ("protocol", "v", "device_id", "name", "x25519", "ed25519", "token", "exp_ms", "hosts")


_ASCII_WS = " \t\n\r\x0b\x0c"


def clean_name(name):
    """Trim, collapse runs of ASCII whitespace (space, \\t \\n \\r \\v \\f) to one space, cap at NAME_MAX code
    points; empty -> 'Ayuvo'. Other Unicode spaces are kept as they are (ports must not use their platform's
    wider whitespace set)."""
    if not isinstance(name, str):
        return "Ayuvo"
    s = " ".join(p for p in re.split("[ \t\n\r\x0b\x0c]+", name) if p)
    s = s[:NAME_MAX].rstrip(_ASCII_WS)
    return s or "Ayuvo"


def qr_encode(payload):
    """Canonical QR text: prefix + base64url(compact JSON with sorted keys)."""
    body = dict((k, payload[k]) for k in QR_FIELDS if k in payload)
    body["name"] = clean_name(body.get("name"))
    text = json.dumps(body, sort_keys=True, separators=(",", ":"), ensure_ascii=False)
    return QR_PREFIX + b64url_encode(text.encode("utf-8"))


def qr_parse(text, now_ms, self_device_id):
    """-> {"ok": True, "payload": {...}} | {"ok": False, "error": code}.
    Codes: not_ayuvo, unsupported_version, malformed, expired, self, bad_key."""
    if not isinstance(text, str):
        return {"ok": False, "error": "not_ayuvo"}
    text = text.strip(_ASCII_WS)
    if not text.startswith("ayuvo-partner:"):
        return {"ok": False, "error": "not_ayuvo"}
    if not text.startswith(QR_PREFIX):
        return {"ok": False, "error": "unsupported_version"}
    raw = b64url_decode(text[len(QR_PREFIX):])
    if raw is None:
        return {"ok": False, "error": "malformed"}
    try:
        obj = json.loads(raw.decode("utf-8"))
    except Exception:  # noqa: BLE001
        return {"ok": False, "error": "malformed"}
    if not isinstance(obj, dict) or obj.get("protocol") != PROTOCOL_ID:
        return {"ok": False, "error": "malformed"}
    if not _is_int(obj.get("v")) or obj.get("v") != PROTOCOL_VERSION:
        return {"ok": False, "error": "unsupported_version"}
    dev = obj.get("device_id")
    if not isinstance(dev, str) or not _full(_RE_UUID, dev):
        return {"ok": False, "error": "malformed"}
    exp = obj.get("exp_ms")
    if not _is_int(exp):
        return {"ok": False, "error": "malformed"}
    hosts = obj.get("hosts", [])
    if not isinstance(hosts, list) or len(hosts) > 8 or any(not isinstance(h, str) or not _full(_RE_HOST, h) for h in hosts):
        return {"ok": False, "error": "malformed"}
    for key in ("x25519", "ed25519", "token"):
        b = b64url_decode(obj.get(key))
        if b is None or len(b) != 32:
            return {"ok": False, "error": "bad_key"}
    if dev == self_device_id:
        return {"ok": False, "error": "self"}
    if now_ms > exp or exp - now_ms > QR_TTL_MS:
        return {"ok": False, "error": "expired"}
    payload = {"device_id": dev, "name": clean_name(obj.get("name")), "x25519": obj["x25519"],
               "ed25519": obj["ed25519"], "token": obj["token"], "exp_ms": exp, "hosts": hosts,
               "fingerprint": fingerprint(obj["x25519"], obj["ed25519"])}
    return {"ok": True, "payload": payload}


# ---------------------------------------------------------------------------------------------
# §7 Record envelopes
# ---------------------------------------------------------------------------------------------

def category_of(rtype, data):
    """Category of a record. Health-derived types take it from the type_id allow-list (None = not shareable)."""
    spec = TYPES.get(rtype)
    if spec is None:
        return None
    if spec["category"] != "health":
        return spec["category"]
    tid = (data or {}).get("type_id")
    cat = HEALTH_TYPE_CATEGORY.get(tid)
    if cat is None:
        return None
    if rtype == "sample" and tid not in INTRADAY_TYPES:
        return None
    if rtype == "metric_hour" and tid not in HOURLY_TYPES:
        return None
    return cat


def _forbidden_paths(value, path):
    out = []
    if isinstance(value, dict):
        for k in sorted(value):
            if k in FORBIDDEN_KEYS:
                out.append("%s.%s" % (path, k))
            out.extend(_forbidden_paths(value[k], "%s.%s" % (path, k)))
    elif isinstance(value, list):
        for i, v in enumerate(value):
            out.extend(_forbidden_paths(v, "%s[%d]" % (path, i)))
    return out


def _too_big(value):
    if isinstance(value, str):
        return len(value) > STRING_MAX
    if isinstance(value, list):
        return len(value) > ARRAY_MAX or any(_too_big(v) for v in value)
    if isinstance(value, dict):
        return any(_too_big(v) for v in value.values())
    return False


def envelope_validate(env, now_ms):
    """-> {"ok": bool, "error": code|None, "category": str|None}.
    Codes: malformed, unknown_type, not_shareable, category_mismatch, missing_field, bad_day, forbidden_field,
    too_large."""
    if not isinstance(env, dict):
        return {"ok": False, "error": "malformed", "category": None}
    rtype, rid, rev = env.get("type"), env.get("id"), env.get("rev")
    if not isinstance(rtype, str) or not isinstance(rid, str) or not rid or len(rid) > 200 or not _is_int(rev) or rev < 1:
        return {"ok": False, "error": "malformed", "category": None}
    if not isinstance(env.get("deleted"), bool) or not _is_int(env.get("updated_ms")):
        return {"ok": False, "error": "malformed", "category": None}
    spec = TYPES.get(rtype)
    if spec is None:
        return {"ok": False, "error": "unknown_type", "category": None}
    claimed = env.get("category")
    if env["deleted"]:
        # Tombstones carry no data; the category is taken as claimed but must be a known one.
        if claimed not in CATEGORIES or (spec["category"] != "health" and spec["category"] != claimed):
            return {"ok": False, "error": "category_mismatch", "category": None}
        return {"ok": True, "error": None, "category": claimed}
    data = env.get("data")
    if not isinstance(data, dict):
        return {"ok": False, "error": "malformed", "category": None}
    cat = category_of(rtype, data)
    if cat is None:
        return {"ok": False, "error": "not_shareable", "category": None}
    if claimed != cat:
        return {"ok": False, "error": "category_mismatch", "category": None}
    for f in spec["required"]:
        if data.get(f) is None:
            return {"ok": False, "error": "missing_field", "category": None}
    if _forbidden_paths(data, "$"):
        return {"ok": False, "error": "forbidden_field", "category": None}
    if _too_big(data):
        return {"ok": False, "error": "too_large", "category": None}
    day = env.get("day")
    if spec["day"]:
        if not isinstance(day, str) or not _full(_RE_DAY, day):
            return {"ok": False, "error": "bad_day", "category": None}
    elif day is not None:
        return {"ok": False, "error": "bad_day", "category": None}
    return {"ok": True, "error": None, "category": cat}


def envelope_ts(env):
    spec = TYPES.get(env.get("type")) or {}
    field = spec.get("ts")
    if not field or env.get("deleted"):
        return None
    v = (env.get("data") or {}).get(field)
    return v if _is_int(v) else None


# ---------------------------------------------------------------------------------------------
# §8 Merge engine (receiver)
# ---------------------------------------------------------------------------------------------

def merge_apply(existing, batch, cursor, granted, now_ms):
    """Apply one CHANGES batch from one owner.

    existing: [{type, record_id, rev, ...}] rows already stored for this owner (only rev matters here).
    batch: {from_rev, to_rev, records:[envelope]}.
    cursor: last committed rev of this owner. granted: categories the owner currently shares with me.

    -> {"accepted": bool, "error": code|None, "cursor": new cursor,
        "upserts": [row], "deletes": [{type, record_id}], "counts": {...}, "rejected": [{type, id, reason}]}
    A batch whose from_rev is ahead of the cursor would leave a hole and is refused as a whole (cursor_gap).
    Per record, in batch order: the highest rev wins; an equal rev is a duplicate; a record with no stored row and
    rev <= cursor was committed before and removed since, so it is stale (no resurrection on replay).
    upserts/deletes are sorted by (type, record_id); within a batch a later tombstone cancels an earlier insert.
    Rows: {type, record_id, category, rev, day, ts_ms, updated_ms, data} with updated_ms clamped to
    now_ms + FUTURE_SKEW_MS."""
    counts = {"inserted": 0, "updated": 0, "deleted": 0, "duplicate": 0, "stale": 0, "rejected": 0}
    from_rev, to_rev = batch.get("from_rev"), batch.get("to_rev")
    records = batch.get("records")
    if not _is_int(from_rev) or not _is_int(to_rev) or to_rev < from_rev or not isinstance(records, list):
        return {"accepted": False, "error": "malformed", "cursor": cursor, "upserts": [], "deletes": [],
                "counts": counts, "rejected": []}
    if from_rev > cursor:
        return {"accepted": False, "error": "cursor_gap", "cursor": cursor, "upserts": [], "deletes": [],
                "counts": counts, "rejected": []}
    state = dict(((r["type"], r["record_id"]), r["rev"]) for r in existing)   # key -> rev of the stored row
    seen = {}                                                                  # key -> highest rev applied in this batch
    upserts, deletes, rejected = {}, {}, []
    granted = set(granted)
    for env in records:
        v = envelope_validate(env, now_ms)
        rtype = env.get("type") if isinstance(env, dict) else None
        rid = env.get("id") if isinstance(env, dict) else None
        if not v["ok"]:
            counts["rejected"] += 1
            rejected.append({"type": rtype, "id": rid, "reason": v["error"]})
            continue
        if v["category"] not in granted:
            counts["rejected"] += 1
            rejected.append({"type": rtype, "id": rid, "reason": "not_granted"})
            continue
        if env["rev"] > to_rev:
            counts["rejected"] += 1
            rejected.append({"type": rtype, "id": rid, "reason": "rev_out_of_range"})
            continue
        key = (rtype, rid)
        stored = state.get(key)
        applied = seen.get(key)
        rev = env["rev"]
        if applied is not None:
            if rev <= applied:
                counts["stale" if rev < applied else "duplicate"] += 1
                continue
        elif stored is not None:
            if rev == stored:
                counts["duplicate"] += 1
                continue
            if rev < stored:
                counts["stale"] += 1
                continue
        elif rev <= cursor:
            # Already committed once and gone since (deleted, pruned or purged): never resurrect it.
            counts["stale"] += 1
            continue
        seen[key] = rev
        exists = (key in upserts) or (stored is not None and key not in deletes)
        if env["deleted"]:
            upserts.pop(key, None)
            if exists:
                if stored is not None:
                    deletes[key] = {"type": rtype, "record_id": rid}
                counts["deleted"] += 1
            else:
                counts["duplicate"] += 1
            continue
        deletes.pop(key, None)
        upserts[key] = {"type": rtype, "record_id": rid, "category": v["category"], "rev": rev, "day": env.get("day"),
                        "ts_ms": envelope_ts(env), "updated_ms": min(env["updated_ms"], now_ms + FUTURE_SKEW_MS),
                        "data": env["data"]}
        counts["updated" if exists else "inserted"] += 1
    upserts = [upserts[k] for k in sorted(upserts)]
    deletes = [deletes[k] for k in sorted(deletes)]
    return {"accepted": True, "error": None, "cursor": max(cursor, to_rev), "upserts": upserts, "deletes": deletes,
            "counts": counts, "rejected": rejected}


def grants_received_update(previous, granted_now, now_ms):
    """previous: [{category, granted, revoked_ms}] ; granted_now: categories in HELLO/manifest.
    -> rows for every category in CATEGORIES order. A category that was granted and is no longer gets
    revoked_ms = now_ms (data is kept, docs §9); re-granting clears revoked_ms."""
    prev = dict((p["category"], p) for p in previous)
    now = set(granted_now)
    out = []
    for cat in CATEGORIES:
        p = prev.get(cat) or {"granted": False, "revoked_ms": None}
        if cat in now:
            out.append({"category": cat, "granted": True, "revoked_ms": None})
        elif p.get("granted"):
            out.append({"category": cat, "granted": False, "revoked_ms": now_ms})
        else:
            out.append({"category": cat, "granted": False, "revoked_ms": p.get("revoked_ms")})
    return out


def retention_prune(rows, now_ms):
    """-> keys of intraday rows (types with retention_days) older than the window, by ts_ms."""
    out = []
    for r in rows:
        spec = TYPES.get(r["type"]) or {}
        days = spec.get("retention_days")
        if days and _is_int(r.get("ts_ms")) and r["ts_ms"] < now_ms - days * DAY_MS:
            out.append({"type": r["type"], "record_id": r["record_id"]})
    return out


# ---------------------------------------------------------------------------------------------
# §10 Outbound ledger (sender)
# ---------------------------------------------------------------------------------------------

def ledger_refresh(ledger, rev, current, scopes):
    """Bring the ledger in line with the current shareable records.

    ledger: [{type, record_id, category, day, content_hash, rev, deleted}]
    rev: the device's outbound revision counter (last assigned rev).
    current: [{type, record_id, category, day, content_hash}] rendered from the sources for the scopes.
    scopes: [{type, day_from?}] — records of `type` that the refresh looked at. Without day_from the scope is
      the whole type; with day_from only ledger rows whose day >= day_from (or no day) can become tombstones.
    -> {"rev": new counter, "ledger": full ledger sorted by (type, record_id), "changed": n, "tombstoned": n}
    New revs are assigned in (type, record_id) order: changes first, then tombstones."""
    by_key = dict(((r["type"], r["record_id"]), dict(r)) for r in ledger)
    scope_of = dict((s["type"], s.get("day_from")) for s in scopes)
    seen = set()
    changed = []
    for c in sorted(current, key=lambda c: (c["type"], c["record_id"])):
        key = (c["type"], c["record_id"])
        seen.add(key)
        old = by_key.get(key)
        if old is None or old["deleted"] or old["content_hash"] != c["content_hash"] or old["category"] != c["category"] \
                or old.get("day") != c.get("day"):
            changed.append(c)
    tomb = []
    for key in sorted(by_key):
        r = by_key[key]
        if r["deleted"] or key in seen or r["type"] not in scope_of:
            continue
        day_from = scope_of[r["type"]]
        if day_from is not None and r.get("day") is not None and r["day"] < day_from:
            continue
        tomb.append(key)
    for c in changed:
        rev += 1
        by_key[(c["type"], c["record_id"])] = {"type": c["type"], "record_id": c["record_id"], "category": c["category"],
                                               "day": c.get("day"), "content_hash": c["content_hash"], "rev": rev,
                                               "deleted": False}
    for key in tomb:
        rev += 1
        r = by_key[key]
        r["rev"] = rev
        r["deleted"] = True
    out = [by_key[k] for k in sorted(by_key)]
    return {"rev": rev, "ledger": out, "changed": len(changed), "tombstoned": len(tomb)}


def ledger_prune(ledger, intraday_day_from):
    """Local cleanup: drop intraday-type ledger rows whose day is before the window. No rev is assigned:
    receivers prune the same rows themselves (retention_prune)."""
    out = []
    for r in ledger:
        spec = TYPES.get(r["type"]) or {}
        if spec.get("retention_days") and r.get("day") is not None and r["day"] < intraday_day_from:
            continue
        out.append(r)
    return out


def ledger_regrant(ledger, rev, category):
    """Re-granting a category to any partner: every ledger row of the category gets a fresh rev so partners
    whose cursor moved past it while it was revoked receive it again (merge dedupes the rest)."""
    out = []
    for r in sorted(ledger, key=lambda r: (r["type"], r["record_id"])):
        r = dict(r)
        if r["category"] == category:
            rev += 1
            r["rev"] = rev
        out.append(r)
    return {"rev": rev, "ledger": out}


def ledger_delta(ledger, rev, cursor, grants, limit):
    """One CHANGES page for a partner: rows with rev > cursor in granted categories, ascending rev.
    -> {"from_rev": cursor, "to_rev", "has_more", "keys": [{type, record_id, rev, deleted}]}
    to_rev is the last row's rev while has_more, else the device counter (so ungranted revs are skipped)."""
    if cursor > rev:
        # The partner remembers more than this device ever issued (reinstall with kept keys): start over.
        cursor = 0
    granted = set(grants)
    rows = sorted([r for r in ledger if r["rev"] > cursor and r["category"] in granted], key=lambda r: r["rev"])
    page = rows[:limit]
    has_more = len(rows) > limit
    to_rev = page[-1]["rev"] if has_more else rev
    return {"from_rev": cursor, "to_rev": to_rev, "has_more": has_more,
            "keys": [{"type": r["type"], "record_id": r["record_id"], "rev": r["rev"], "deleted": r["deleted"]}
                     for r in page]}


# ---------------------------------------------------------------------------------------------
# §11 Source mappings (shared-schema rows -> envelope data)
# ---------------------------------------------------------------------------------------------

def _drop_none(d):
    return dict((k, v) for k, v in d.items() if v is not None)


def map_rollup(row):
    """health_daily_rollups row -> metric_day envelope parts, or None when the type is not shareable."""
    data = _drop_none({"type_id": row["type_id"], "day": row["day"], "unit": row.get("unit"),
                       "sum": row.get("sum"), "avg": row.get("avg"), "min": row.get("min"), "max": row.get("max"),
                       "count": row.get("count"), "last_value": row.get("last_value"),
                       "last_at_ms": row.get("last_at_ms"), "v2_avg": row.get("v2_avg"), "v2_min": row.get("v2_min"),
                       "v2_max": row.get("v2_max"), "duration_s": row.get("duration_s")})
    cat = category_of("metric_day", data)
    if cat is None or data.get("unit") is None:
        return None
    return {"type": "metric_day", "id": "%s:%s" % (row["type_id"], row["day"]), "category": cat, "day": row["day"],
            "data": data}


def map_hourly(row):
    data = _drop_none({"type_id": row["type_id"], "day": row["day"], "hour": row["hour"],
                       "hour_start_ms": row["hour_start_ms"], "unit": row.get("unit"), "sum": row.get("sum"),
                       "avg": row.get("avg"), "min": row.get("min"), "max": row.get("max"), "count": row.get("count")})
    cat = category_of("metric_hour", data)
    if cat is None or data.get("unit") is None:
        return None
    return {"type": "metric_hour", "id": "%s:%s:%d" % (row["type_id"], row["day"], row["hour"]), "category": cat,
            "day": row["day"], "data": data}


def map_sample(row, now_ms):
    """health_samples row -> sample (intraday window only; tombstoned rows are not mapped)."""
    if row.get("deleted"):
        return None
    if row["start_ms"] < now_ms - INTRADAY_MS:
        return None
    data = _drop_none({"type_id": row["type_id"], "start_ms": row["start_ms"], "end_ms": row["end_ms"],
                       "unit": row["unit"], "value": row.get("value"), "value2": row.get("value2"),
                       "value3": row.get("value3"), "category_value": row.get("category_value"),
                       "source_name": row.get("source_name")})
    cat = category_of("sample", data)
    if cat is None:
        return None
    return {"type": "sample", "id": row["id"], "category": cat, "day": row["local_day"], "data": data}


def map_analytics(row):
    """analytics_results row -> analytics_day for single-day results of allow-listed metrics."""
    if row["metric_id"] not in ANALYTICS_METRICS or row["period_start"] != row["period_end"]:
        return None
    data = _drop_none({"metric_id": row["metric_id"], "day": row["period_start"], "status": row["status"],
                       "classification": row["classification"], "value": row.get("value"),
                       "value2": row.get("value2"), "value3": row.get("value3"), "unit": row.get("unit"),
                       "confidence": row.get("confidence"), "coverage": row.get("coverage")})
    return {"type": "analytics_day", "id": "%s:%s" % (row["metric_id"], row["period_start"]), "category": "vitals",
            "day": row["period_start"], "data": data}


def map_medication(row):
    data = _drop_none({"name": row["name"], "generic_name": row.get("generic_name"),
                       "brand_name": row.get("brand_name"), "strength": row.get("strength"), "form": row["form"],
                       "dose_quantity": row["dose_quantity"], "dose_unit": row["dose_unit"],
                       "food_relation": row.get("food_relation"), "instructions": row.get("instructions"),
                       "start_date": row["start_date"], "end_date": row.get("end_date"), "status": row["status"],
                       "is_prn": bool(row.get("is_prn"))})
    return {"type": "medication", "id": row["id"], "category": "medicines", "day": None, "data": data}


def map_schedule(row):
    data = _drop_none({"medication_id": row["medication_id"], "frequency_kind": row["frequency_kind"],
                       "times": json.loads(row.get("times_json") or "[]"), "days": json.loads(row.get("days_json") or "[]"),
                       "interval_hours": row.get("interval_hours"), "anchor_time": row.get("anchor_time"),
                       "active_from_ms": row["active_from_ms"], "active_until_ms": row.get("active_until_ms")})
    return {"type": "medication_schedule", "id": row["id"], "category": "medicines", "day": None, "data": data}


def map_dose_log(row, local_day):
    data = _drop_none({"medication_id": row["medication_id"], "schedule_id": row.get("schedule_id"),
                       "scheduled_at_ms": row["scheduled_at_ms"], "status": row["status"],
                       "taken_at_ms": row.get("taken_at_ms"), "dose_quantity": row["dose_quantity"],
                       "dose_unit": row["dose_unit"], "note": row.get("note")})
    return {"type": "dose_log", "id": row["id"], "category": "medicines", "day": local_day, "data": data}


_ABNORMAL = ("low", "high", "critical_low", "critical_high", "abnormal")
_FIELD_ORDER = {"user": 0, "confirmed": 1, "suggested": 2}


def _best_field(fields, key):
    cands = [f for f in fields if f["field_key"] == key and f.get("state") != "rejected"]
    cands.sort(key=lambda f: (_FIELD_ORDER.get(f.get("state"), 9), -(f.get("confidence") or 0), f["id"]))
    return cands[0] if cands else None


def _field_values(fields, key):
    out = []
    for f in sorted([f for f in fields if f["field_key"] == key and f.get("state") != "rejected"],
                    key=lambda f: f["id"]):
        if f["value_text"] not in out:
            out.append(f["value_text"])
    return out


def map_report_overview(record, fields, highlights, observations):
    """records row + children -> report_overview. Reads only named structured columns: never the file, page text,
    evidence, bounding boxes or notes (docs §7.6). Archived records are not shared."""
    if record.get("archived"):
        return None
    doctor = _best_field(fields, "doctor_name")
    specialty = _best_field(fields, "doctor_specialty")
    facility = _best_field(fields, "facility")
    report_date = record.get("document_date")
    if report_date is None:
        rd = _best_field(fields, "report_date")
        report_date = rd["value_text"] if rd and _full(_RE_DAY, rd["value_text"] or "") else None
    live = [h for h in highlights if not h.get("dismissed")]
    live.sort(key=lambda h: (h.get("position") or 0, h["id"]))
    summary = next((h["text"] for h in live if h["section"] == "summary"), None)
    important = [h["text"] for h in live if h["section"] == "important"]
    recs = [h["text"] for h in live if h["section"] == "recommendations"]
    results = []
    for o in sorted([o for o in observations if o.get("state") != "rejected"],
                    key=lambda o: (o.get("raw_name") or "", o["id"])):
        results.append(_drop_none({"name": o["raw_name"], "analyte_id": o.get("analyte_id"),
                                   "value": o["value_text"], "value_num": o.get("value_num"), "unit": o.get("unit"),
                                   "ref_low": o.get("ref_low"), "ref_high": o.get("ref_high"),
                                   "ref_text": o.get("ref_text"), "flag": o.get("flag") or "unknown",
                                   "observed_date": o.get("observed_date")}))
    abnormal = [r for r in results if r["flag"] in _ABNORMAL]
    meds = []
    for f in sorted([f for f in fields if f["field_key"] == "medication" and f.get("state") != "rejected"],
                    key=lambda f: f["id"]):
        vj = json.loads(f["value_json"]) if f.get("value_json") else {}
        meds.append(_drop_none({"name": vj.get("name") or f["value_text"], "strength": vj.get("strength"),
                                "dose": vj.get("dose"), "frequency": vj.get("frequency"),
                                "duration": vj.get("duration")}))
    data = _drop_none({"title": record["title"], "record_type": record["record_type"], "category": record["category"],
                       "report_date": report_date,
                       "doctor": doctor["value_text"] if doctor else None,
                       "doctor_specialty": specialty["value_text"] if specialty else None,
                       "facility": facility["value_text"] if facility else None,
                       "summary": summary, "highlights": important or None, "results": results or None,
                       "abnormal": abnormal or None, "diagnoses": _field_values(fields, "diagnosis") or None,
                       "medications": meds or None, "recommendations": recs or None})
    return {"type": "report_overview", "id": record["id"], "category": "report_overviews",
            "day": report_date or record["sort_date"][:10], "data": data}


# ---------------------------------------------------------------------------------------------
# §12 Protocol messages
# ---------------------------------------------------------------------------------------------

def message_validate(msg):
    """-> {"ok": bool, "error": code|None}. Codes: malformed, unknown_message, unsupported_version."""
    if not isinstance(msg, dict) or not isinstance(msg.get("t"), str):
        return {"ok": False, "error": "malformed"}
    spec = MESSAGES.get(msg["t"])
    if spec is None:
        return {"ok": False, "error": "unknown_message"}
    for f in spec["required"]:
        if f not in msg:
            return {"ok": False, "error": "malformed"}
    if msg["t"] == "HELLO":
        if msg.get("protocol") != PROTOCOL_ID:
            return {"ok": False, "error": "malformed"}
        if not _is_int(msg.get("v")) or msg.get("v") != PROTOCOL_VERSION:
            return {"ok": False, "error": "unsupported_version"}
        if not isinstance(msg.get("grants"), list) or any(g not in CATEGORIES for g in msg["grants"]):
            return {"ok": False, "error": "malformed"}
    if msg["t"] == "SYNC_REQ" and (not _is_int(msg["cursor"]) or msg["cursor"] < 0):
        return {"ok": False, "error": "malformed"}
    if msg["t"] == "CHANGES":
        if not _is_int(msg["from_rev"]) or not _is_int(msg["to_rev"]) or msg["to_rev"] < msg["from_rev"] \
                or not isinstance(msg["has_more"], bool) or not isinstance(msg["records"], list) \
                or len(msg["records"]) > BATCH_MAX:
            return {"ok": False, "error": "malformed"}
    if msg["t"] == "ACK" and not _is_int(msg["committed_rev"]):
        return {"ok": False, "error": "malformed"}
    if msg["t"] == "PAIR_CONFIRM":
        if not isinstance(msg["accepted"], bool):
            return {"ok": False, "error": "malformed"}
        if msg["accepted"]:
            # The initiator's identity is only known from here (its X25519 key came in the IK handshake).
            key = b64url_decode(msg.get("ed25519"))
            if not isinstance(msg.get("device_id"), str) or not _full(_RE_UUID, msg["device_id"]) \
                    or key is None or len(key) != 32 or not isinstance(msg.get("name"), str) \
                    or not isinstance(msg.get("grants"), list) or any(g not in CATEGORIES for g in msg["grants"]):
                return {"ok": False, "error": "malformed"}
    return {"ok": True, "error": None}


def session_script(inbound):
    """Replays the inbound message types of one session (docs §12.3) and says whether the sequence is legal.
    Rules: HELLO first; at most one SYNC_REQ; ACK only after the peer's SYNC_REQ; CHANGES only until a page with
    has_more=false; DONE only after that last page; nothing after DONE; ERROR ends the session.
    -> {"ok": bool, "error": code|None, "steps": accepted inbound messages}. ok also requires a complete pull and
    the peer's DONE (an interrupted session is "incomplete": committed pages stay, the next session resumes)."""
    hello = sync_req = done = False
    pulling = True
    steps = 0
    for m in inbound:
        t = m.get("t")
        if t == "ERROR":
            return {"ok": False, "error": m.get("code") or "internal", "steps": steps}
        legal = (t == "HELLO" and not hello) or (hello and not done and (
            (t == "SYNC_REQ" and not sync_req) or (t == "ACK" and sync_req) or (t == "CHANGES" and pulling)
            or (t == "DONE" and not pulling)))
        if not legal:
            return {"ok": False, "error": "malformed", "steps": steps}
        if t == "HELLO":
            hello = True
        elif t == "SYNC_REQ":
            sync_req = True
        elif t == "CHANGES" and not m.get("has_more"):
            pulling = False
        elif t == "DONE":
            done = True
        steps += 1
    ok = hello and not pulling and done
    return {"ok": ok, "error": None if ok else "incomplete", "steps": steps}


# ---------------------------------------------------------------------------------------------
# §13 Package (.ayuvo.zip)
# ---------------------------------------------------------------------------------------------

def package_entry_names(categories):
    return list(PACKAGE["entries_fixed"]) + ["health/%s.ndjson" % c for c in CATEGORIES if c in categories]


def package_filename(name, day):
    """<Name>_Health_YYYY-MM-DD.ayuvo.zip with the name reduced to ASCII letters/digits joined by '_'."""
    parts, cur = [], ""
    for ch in clean_name(name):
        if ("a" <= ch <= "z") or ("A" <= ch <= "Z") or ("0" <= ch <= "9"):
            cur += ch
        elif cur:
            parts.append(cur)
            cur = ""
    if cur:
        parts.append(cur)
    base = "_".join(parts)[:32].strip("_") or "Partner"
    return "%s_Health_%s%s" % (base, day, PACKAGE["extension"])


def package_validate(manifest, entries, signature_ok, partners, me, now_ms):
    """Checks before any record is read.

    manifest: parsed manifest.json; entries: [{name, sha256}] as found in the zip (sha256 computed by the reader);
    signature_ok: Ed25519 verification of manifest.json bytes with the claimed sender's stored key (False when the
    sender is unknown); partners: trusted owner ids (unpaired excluded); me: my device_id.
    -> {"ok": bool, "error": code|None}
    Codes in check order: not_package, unsupported_version, malformed, unsafe_entry, unknown_sender,
    wrong_recipient, bad_signature, missing_entry, unexpected_entry, hash_mismatch."""
    if not isinstance(manifest, dict) or manifest.get("format") != PACKAGE["format"]:
        return {"ok": False, "error": "not_package"}
    if not _is_int(manifest.get("version")) or manifest.get("version") != PACKAGE["version"]:
        return {"ok": False, "error": "unsupported_version"}
    for f in ("export_id", "device_id", "recipient_device_id", "created_ms", "from_rev", "to_rev", "categories", "files"):
        if f not in manifest:
            return {"ok": False, "error": "malformed"}
    cats = manifest["categories"]
    if not isinstance(cats, list) or any(c not in CATEGORIES for c in cats) or len(set(cats)) != len(cats):
        return {"ok": False, "error": "malformed"}
    if not _is_int(manifest["from_rev"]) or not _is_int(manifest["to_rev"]) or manifest["to_rev"] < manifest["from_rev"]:
        return {"ok": False, "error": "malformed"}
    files = manifest["files"]
    if not isinstance(files, list) or any(not isinstance(f, dict) or not isinstance(f.get("name"), str)
                                          or not isinstance(f.get("sha256"), str) or not _full(_RE_SHA, f["sha256"])
                                          for f in files):
        return {"ok": False, "error": "malformed"}
    for e in entries:
        n = e["name"]
        if not n or n.startswith("/") or "\\" in n or "\x00" in n or ".." in n.split("/"):
            return {"ok": False, "error": "unsafe_entry"}
    if manifest["device_id"] not in partners:
        return {"ok": False, "error": "unknown_sender"}
    if manifest["recipient_device_id"] != me:
        return {"ok": False, "error": "wrong_recipient"}
    if not signature_ok:
        return {"ok": False, "error": "bad_signature"}
    allowed = set(package_entry_names(cats))
    present = dict((e["name"], e.get("sha256")) for e in entries if not e["name"].endswith("/"))
    listed = dict((f["name"], f["sha256"]) for f in files)
    for required in ("manifest.json", "signature.json"):
        if required not in present:
            return {"ok": False, "error": "missing_entry"}
    for name in present:
        if name not in allowed:
            return {"ok": False, "error": "unexpected_entry"}
    for name in listed:
        if name not in allowed or name in ("manifest.json", "signature.json"):
            return {"ok": False, "error": "unexpected_entry"}
        if name not in present:
            return {"ok": False, "error": "missing_entry"}
    for name in present:
        if name in ("manifest.json", "signature.json"):
            continue
        if name not in listed:
            return {"ok": False, "error": "unexpected_entry"}
        if present[name] != listed[name]:
            return {"ok": False, "error": "hash_mismatch"}
    return {"ok": True, "error": None}


def package_summary(manifest_files, categories):
    """Counts for the import confirmation sheet: total records and per category (files[].count)."""
    per = {}
    for f in manifest_files:
        name = f["name"]
        if name.startswith("health/") and name.endswith(".ndjson"):
            cat = name[len("health/"):-len(".ndjson")]
            if cat in categories:
                per[cat] = per.get(cat, 0) + int(f.get("count") or 0)
    return {"total": sum(per.values()), "per_category": [{"category": c, "count": per[c]} for c in CATEGORIES if c in per]}


def package_import(existing, cursor, manifest, files, now_ms, batch_size=BATCH_MAX):
    """Merge a validated package. files: {category: [envelope, ...]} in file order (rev order per file).
    Category files interleave revs, so every batch is merged against the PRE-IMPORT cursor with the manifest's
    from_rev/to_rev, and the cursor is committed only with the last batch (docs §13). Platforms persist each
    batch's upserts/deletes in its own transaction; an interrupted import re-runs as duplicates.
    -> {"accepted", "error", "cursor", "counts", "rejected", "upserts", "deletes"} (upserts/deletes = final state
    changes across all batches, sorted by (type, record_id))."""
    counts = {"inserted": 0, "updated": 0, "deleted": 0, "duplicate": 0, "stale": 0, "rejected": 0}
    granted = manifest["categories"]
    stored = dict(((r["type"], r["record_id"]), r["rev"]) for r in existing)
    final_up, final_del, rejected = {}, {}, []
    records = []
    for cat in CATEGORIES:
        if cat in files:
            records.extend(files[cat])
    batches = [records[i:i + batch_size] for i in range(0, len(records), batch_size)] or [[]]
    for chunk in batches:
        current = [{"type": k[0], "record_id": k[1], "rev": v} for k, v in sorted(stored.items())]
        res = merge_apply(current, {"from_rev": manifest["from_rev"], "to_rev": manifest["to_rev"], "records": chunk},
                          cursor, granted, now_ms)
        if not res["accepted"]:
            return {"accepted": False, "error": res["error"], "cursor": cursor, "counts": counts, "rejected": [],
                    "upserts": [], "deletes": []}
        for k in counts:
            counts[k] += res["counts"][k]
        rejected.extend(res["rejected"])
        for d in res["deletes"]:
            key = (d["type"], d["record_id"])
            stored.pop(key, None)
            final_up.pop(key, None)
            final_del[key] = d
        for u in res["upserts"]:
            key = (u["type"], u["record_id"])
            stored[key] = u["rev"]
            final_del.pop(key, None)
            final_up[key] = u
    return {"accepted": True, "error": None, "cursor": max(cursor, manifest["to_rev"]), "counts": counts,
            "rejected": rejected, "upserts": [final_up[k] for k in sorted(final_up)],
            "deletes": [final_del[k] for k in sorted(final_del)]}


# ---------------------------------------------------------------------------------------------
# §15 Summary card metrics
# ---------------------------------------------------------------------------------------------

def round_half_up(v):
    """Display rounding shared by both ports (Kotlin Math.round / Swift .rounded(.down) of v + 0.5)."""
    return int(math.floor(v + 0.5))


def _rows_of(rows, rtype):
    return [r for r in rows if r["type"] == rtype]


def summary_metrics(rows, today, yesterday, grants_received, limit=3):
    """Pick up to `limit` headline metrics for one partner from stored rows (docs §15). Never fabricates:
    a metric appears only when a row exists. Priority: recovery, sleep, resting_hr, activity, medicines.
    rows: [{type, record_id, day, ts_ms, data}] ; grants_received: categories currently shared (others still show
    but flagged `stale_category`)."""
    out = []
    granted = set(grants_received)

    def add(key, category, value, unit, day):
        out.append({"key": key, "category": category, "value": value, "unit": unit, "day": day,
                    "shared": category in granted})

    rec = [r for r in _rows_of(rows, "analytics_day") if r["data"].get("metric_id") == "recovery_indicator"
           and r["day"] in (today, yesterday) and _is_num(r["data"].get("value"))]
    rec.sort(key=lambda r: r["day"], reverse=True)
    if rec:
        add("recovery", "vitals", round_half_up(rec[0]["data"]["value"]), None, rec[0]["day"])
    sleep = [r for r in _rows_of(rows, "sleep_night") if r["day"] in (today, yesterday)]
    sleep.sort(key=lambda r: r["day"], reverse=True)
    if sleep:
        add("sleep", "sleep", int(sleep[0]["data"]["asleep_min"]), "min", sleep[0]["day"])
    rhr = [r for r in _rows_of(rows, "metric_day") if r["data"].get("type_id") == "resting_heart_rate"
           and r["day"] in (today, yesterday) and _is_num(r["data"].get("avg"))]
    rhr.sort(key=lambda r: r["day"], reverse=True)
    if rhr:
        add("resting_hr", "vitals", round_half_up(rhr[0]["data"]["avg"]), "bpm", rhr[0]["day"])
    ex = [r for r in _rows_of(rows, "metric_day") if r["data"].get("type_id") == "exercise_minutes" and r["day"] == today
          and _is_num(r["data"].get("sum"))]
    steps = [r for r in _rows_of(rows, "metric_day") if r["data"].get("type_id") == "steps" and r["day"] == today
             and _is_num(r["data"].get("sum"))]
    if ex and ex[0]["data"]["sum"] > 0:
        add("activity", "vitals", round_half_up(ex[0]["data"]["sum"]), "min", today)
    elif steps:
        add("steps", "vitals", round_half_up(steps[0]["data"]["sum"]), "count", today)
    doses = [r for r in _rows_of(rows, "dose_log") if r["day"] == today]
    if doses:
        taken = len([d for d in doses if d["data"].get("status") == "taken"])
        add("medicines", "medicines", taken, "of:%d" % len(doses), today)
    return out[:limit]


# ---------------------------------------------------------------------------------------------
# Vector dispatcher
# ---------------------------------------------------------------------------------------------

def run_case(function, inp):
    if function == "qr":
        op = inp["op"]
        if op == "encode":
            return {"text": qr_encode(inp["payload"])}
        if op == "parse":
            return qr_parse(inp["text"], inp["now_ms"], inp["self_device_id"])
        raise ValueError(op)
    if function == "kdf":
        op = inp["op"]
        if op == "psk":
            return {"psk": pairing_psk(inp["token"])}
        if op == "sas":
            return {"sas": sas_code(inp["handshake_hash"])}
        if op == "fingerprint":
            fp = fingerprint(inp["x25519"], inp["ed25519"])
            return {"fingerprint": fp, "display": format_fingerprint(fp)}
        raise ValueError(op)
    if function == "envelope_validate":
        return envelope_validate(inp["envelope"], inp["now_ms"])
    if function == "merge_apply":
        return merge_apply(inp.get("existing") or [], inp["batch"], inp["cursor"], inp["granted"], inp["now_ms"])
    if function == "grants_received":
        return {"grants": grants_received_update(inp.get("previous") or [], inp["granted_now"], inp["now_ms"])}
    if function == "retention_prune":
        return {"delete": retention_prune(inp["rows"], inp["now_ms"])}
    if function == "ledger":
        op = inp["op"]
        if op == "refresh":
            return ledger_refresh(inp.get("ledger") or [], inp["rev"], inp.get("current") or [], inp["scopes"])
        if op == "prune":
            return {"ledger": ledger_prune(inp["ledger"], inp["day_from"])}
        if op == "regrant":
            return ledger_regrant(inp["ledger"], inp["rev"], inp["category"])
        if op == "delta":
            return ledger_delta(inp["ledger"], inp["rev"], inp["cursor"], inp["grants"], inp.get("limit", BATCH_MAX))
        raise ValueError(op)
    if function == "map":
        src = inp["source"]
        if src == "rollup":
            return {"envelope": map_rollup(inp["row"])}
        if src == "hourly":
            return {"envelope": map_hourly(inp["row"])}
        if src == "sample":
            return {"envelope": map_sample(inp["row"], inp["now_ms"])}
        if src == "analytics":
            return {"envelope": map_analytics(inp["row"])}
        if src == "medication":
            return {"envelope": map_medication(inp["row"])}
        if src == "schedule":
            return {"envelope": map_schedule(inp["row"])}
        if src == "dose_log":
            return {"envelope": map_dose_log(inp["row"], inp["local_day"])}
        if src == "report":
            return {"envelope": map_report_overview(inp["record"], inp.get("fields") or [], inp.get("highlights") or [],
                                                    inp.get("observations") or [])}
        raise ValueError(src)
    if function == "message_validate":
        return message_validate(inp["message"])
    if function == "session":
        return session_script(inp["inbound"])
    if function == "package":
        op = inp["op"]
        if op == "validate":
            return package_validate(inp["manifest"], inp["entries"], inp["signature_ok"], inp["partners"], inp["me"],
                                    inp["now_ms"])
        if op == "entries":
            return {"entries": package_entry_names(inp["categories"])}
        if op == "filename":
            return {"filename": package_filename(inp["name"], inp["day"])}
        if op == "summary":
            return package_summary(inp["files"], inp["categories"])
        if op == "import":
            return package_import(inp.get("existing") or [], inp["cursor"], inp["manifest"], inp["files"], inp["now_ms"],
                                  inp.get("batch_size", BATCH_MAX))
        raise ValueError(op)
    if function == "summary_metrics":
        return {"metrics": summary_metrics(inp["rows"], inp["today"], inp["yesterday"], inp["grants"],
                                           inp.get("limit", 3))}
    raise ValueError(function)
