#!/usr/bin/env python3
"""Ayuvo Health Records contract check (Python 3 stdlib only).

    python3 scripts/records_contract_check.py           # verify; exit 1 on any problem
    python3 scripts/records_contract_check.py --write   # rewrite every vector's "expected" from the reference

Checks:
  1. shared/records/test-vectors/*.json: envelope shape, unique case names, stable formatting
     (sorted keys, 2-space indent, UTF-8 without escapes, trailing newline), and every case's
     "expected" equals scripts/records_reference.py run on its "input" (inputs are the source of truth;
     --write only replaces "expected").
  2. Output shapes per function (keys and enum values).
  3. record_types.json and units.json: shapes, regexes compile, portable-subset lint, units unique
     after folding. The reference module's own compiled patterns are linted too.
     analytes.json (§20): ids unique snake_case, known panels/categories/kinds, every unit a units.json
     canonical spelling with a positive factor, canonical unit listed with factor 1 / offset 0, decimals on
     numeric analytes, aliases normalized-unique per analyte, shared aliases exactly
     ANALYTE_SHARED_ALIASES and each group separable (unit sets, kind, own panel or the urine rule).
  4. ai_extraction.schema.json parses; vector inputs marked "schema_valid" validate against it;
     ai_extraction.md carries both prompt variants and the placeholders.
  5. schema.sql and migrations/*.sql split into statements with statements(path) (rule in
     docs/health-records.md §8).
  6. shared/records/fixtures/ayuvo-records-fixture.zip (§35): entry order, every entry's bytes against
     archive_manifest/archive_rows, checksums.json, a read + merge round trip, and the 200 KB cap.
     --write rebuilds it from the "fixture" snapshot of archive.json (byte-deterministic).
  7. A small unittest suite for the helpers.
"""

import glob
import hashlib
import json
import os
import re
import sys
import unittest
import zipfile
import zlib

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
SHARED = os.path.join(ROOT, "shared", "records")
VECTORS = os.path.join(SHARED, "test-vectors")
sys.path.insert(0, HERE)
import records_reference as R  # noqa: E402

EXPECTED_FILES = {
    "fold.json": "fold", "classifier.json": "classify", "dates.json": "extract_dates",
    "fields.json": "extract_fields", "lab_rows.json": "parse_lab_rows", "boundaries.json": "detect_boundaries",
    "highlights.json": "build_highlights", "review.json": "review_status", "apply_extraction.json": "apply_extraction",
    "ai_validation.json": "validate_ai", "ai_chunks.json": "ai_chunks", "hashing.json": "hashing",
    "query_parser.json": "parse_query", "analyte_mapping.json": "map_analyte", "unit_conversion.json": "convert_unit",
    "observations.json": "observations", "trends.json": "trends", "entities.json": "entities",
    "relations.json": "suggest_relations",
    "coach_tools_payloads.json": "coach_tools", "coach_context.json": "pack_coach_records",
    "coach_prompt.json": "coach_prompt",
    "share_summary.json": "share_summary", "redaction.json": "redaction", "archive.json": "archive",
}
# Phase 4/5 vector files may carry top-level "fixtures": {"snapshots": {name: snapshot}}; a case input whose
# "snapshot" is a string names one of them (ports resolve it the same way before running the case).
FIXTURE_FILES = frozenset(["coach_tools_payloads.json", "coach_context.json", "coach_prompt.json",
                           "share_summary.json", "redaction.json", "archive.json"])
# The §35 fixture archive: built from the "fixture" snapshot of archive.json plus the file blobs below.
FIXTURE_ARCHIVE = os.path.join(SHARED, "fixtures", "ayuvo-records-fixture.zip")
FIXTURE_SNAPSHOT = "fixture"
FIXTURE_MANIFEST = {"platform": "android", "app_version": "1.4", "created_ms": 1757462400000,
                    "time_zone": "Asia/Kolkata"}
FIXTURE_ZIP_DATE = (2026, 1, 1, 0, 0, 0)
FIXTURE_MAX_BYTES = 200 * 1024


def resolve_input(doc, inp):
    snap = inp.get("snapshot")
    if isinstance(snap, str):
        inp = dict(inp)
        inp["snapshot"] = doc["fixtures"]["snapshots"][snap]
    return inp


def dumps(obj):
    return json.dumps(obj, indent=2, sort_keys=True, ensure_ascii=False) + "\n"


# ---------------------------------------------------------------------------------------------
# SQL statements (docs §8)
# ---------------------------------------------------------------------------------------------

def statements(path):
    """Split a shared .sql file into statements, exactly as both platforms embed them:
    1. decode UTF-8, CRLF/CR -> LF;
    2. on every line delete from the first '--' to the end of the line (the shared files never put '--'
       inside a string literal; check_sql enforces it);
    3. right-trim every line and drop lines that are then empty;
    4. a line ending with ';' closes the statement: its lines are joined with '\\n' and the final ';'
       is removed (leading indentation inside the statement is kept);
    5. non-empty text after the last ';' is an error."""
    with open(path, encoding="utf-8") as fh:
        text = fh.read().replace("\r\n", "\n").replace("\r", "\n")
    out, cur = [], []
    for raw in text.split("\n"):
        k = raw.find("--")
        line = (raw if k < 0 else raw[:k]).rstrip()
        if not line:
            continue
        cur.append(line)
        if line.endswith(";"):
            stmt = "\n".join(cur)[:-1].rstrip()
            if stmt.strip():
                out.append(stmt)
            cur = []
    if cur:
        raise ValueError("%s: text after the last ';': %r" % (path, "\n".join(cur)[:80]))
    return out


def check_sql(problems):
    files = [os.path.join(SHARED, "schema.sql")] + sorted(glob.glob(os.path.join(SHARED, "migrations", "*.sql")))
    counts = {}
    for path in files:
        rel = os.path.relpath(path, ROOT)
        with open(path, encoding="utf-8") as fh:
            for n, raw in enumerate(fh.read().split("\n"), 1):
                k = raw.find("--")
                if k >= 0 and raw[:k].count("'") % 2 == 1:
                    problems.append("%s:%d: '--' inside a string literal" % (rel, n))
        try:
            st = statements(path)
        except ValueError as e:
            problems.append(str(e))
            continue
        if not st:
            problems.append("%s: no statements" % rel)
        for s in st:
            if not re.match("(CREATE|ALTER|DROP|INSERT|UPDATE|DELETE|PRAGMA) ", s.lstrip()):
                problems.append("%s: unexpected statement start %r" % (rel, s[:40]))
            if s.count("(") != s.count(")"):
                problems.append("%s: unbalanced parentheses in %r" % (rel, s[:40]))
        counts[rel] = len(st)
    return counts


# ---------------------------------------------------------------------------------------------
# Regex portability lint
# ---------------------------------------------------------------------------------------------

_LINT = [
    ("(?P<", "named group"), ("(?<=", None), ("(?>", "atomic group"), ("\\p{", "unicode property"),
    ("\\P{", "unicode property"), ("\\A", "\\A anchor"), ("\\Z", "\\Z anchor"), ("\\z", "\\z anchor"),
    ("\\h", "\\h class"), ("\\R", "\\R"), ("\\X", "\\X"), ("\\K", "\\K"), ("\\Q", "\\Q quoting"),
    ("(?#", "inline comment"),
]


def lint_pattern(p, owned):
    """Constructs outside the portable subset. owned=True additionally forbids \\d \\w \\s \\b and lazy
    quantifiers (the reference's own patterns use explicit ASCII classes)."""
    issues = []
    for tok, what in _LINT:
        if tok in p and what:
            issues.append(what)
    if re.search("\\(\\?[aiLmsux-]+[):]", p):
        issues.append("inline flags")
    if re.search("\\(\\?<[A-Za-z]", p):
        issues.append("named group")
    if re.search("[*+?}][+]", p.replace("\\+", "").replace("\\*", "").replace("\\?", "").replace("\\}", "")):
        issues.append("possessive quantifier")
    for m in re.finditer("\\(\\?<[=!]", p):
        depth, i, body = 1, m.end(), []
        while i < len(p) and depth:
            if p[i] == "\\":
                body.append(p[i:i + 2])
                i += 2
                continue
            if p[i] == "(":
                depth += 1
            elif p[i] == ")":
                depth -= 1
                if depth == 0:
                    break
            body.append(p[i])
            i += 1
        b = "".join(body)
        if re.search("(?<!\\\\)[*+?|]|\\{[0-9]*,", b):
            issues.append("variable-length lookbehind")
    if owned:
        if re.search("\\\\[dwsbDWSB]", p):
            issues.append("\\d/\\w/\\s/\\b in a reference-owned pattern")
        if re.search("[*+?}]\\?", p.replace("\\*", "").replace("\\+", "").replace("\\?", "")):
            issues.append("lazy quantifier")
    return issues


def check_data_files(problems):
    rt = json.load(open(os.path.join(SHARED, "record_types.json"), encoding="utf-8"))
    if rt.get("format") != "ayuvo-record-types" or not isinstance(rt.get("types"), list):
        problems.append("record_types.json: bad envelope")
    ids = [t["id"] for t in rt["types"]]
    for t in rt["types"]:
        if t["id"] not in R.RECORD_TYPES or R.DEFAULT_CATEGORY[t["id"]] != t["category"]:
            problems.append("record_types.json: %s category/type mismatch with §3" % t["id"])
        for r in t["rules"]:
            if r["scope"] not in ("head", "body") or not isinstance(r["weight"], int):
                problems.append("record_types.json: bad rule in %s" % t["id"])
            try:
                re.compile(r["pattern"])
            except re.error as e:
                problems.append("record_types.json: %s pattern does not compile: %s" % (t["id"], e))
            for issue in lint_pattern(r["pattern"], owned=False):
                problems.append("record_types.json: %s %r: %s" % (t["id"], r["pattern"], issue))
    if len(set(ids)) != len(ids):
        problems.append("record_types.json: duplicate type ids")

    un = json.load(open(os.path.join(SHARED, "units.json"), encoding="utf-8"))
    if un.get("format") != "ayuvo-record-units" or un.get("version") != 1:
        problems.append("units.json: bad envelope")
    owner = {}
    for u in un["units"]:
        if not u.get("canonical") or not u.get("variants"):
            problems.append("units.json: empty unit entry")
        for v in u["variants"] + [u["canonical"]]:
            fv = R.fold(v)
            if fv != fv.strip() or "  " in fv or "\n" in fv:
                problems.append("units.json: variant %r folds with bad spacing" % v)
            re.compile(re.escape(fv))
            if fv in owner and owner[fv] != u["canonical"]:
                problems.append("units.json: %r folds to %r, claimed by %s and %s" % (v, fv, owner[fv], u["canonical"]))
            owner[fv] = u["canonical"]
    check_analytes(problems, set(u["canonical"] for u in un["units"]))
    for name in sorted(dir(R)):
        obj = getattr(R, name)
        if isinstance(obj, re.Pattern):
            for issue in lint_pattern(obj.pattern, owned=True):
                problems.append("records_reference.%s: %s" % (name, issue))


_ANALYTE_KEYS = {"id", "display_name", "category", "panels", "kind", "canonical_unit", "decimals", "units", "aliases",
                 "loinc", "metric_registry_id"}


def _separable(a, b):
    """Two analytes sharing an alias can be told apart (§20 _disambiguate)."""
    ua, ub = R._analyte_units(a), R._analyte_units(b)
    if ua and ub and not (ua & ub):
        return True
    if a["kind"] != b["kind"]:
        return True
    if (a["category"] == "urine") != (b["category"] == "urine"):
        return True
    return bool(set(a["panels"]) - set(b["panels"])) and bool(set(b["panels"]) - set(a["panels"]))


def check_analytes(problems, unit_canonicals):
    path = os.path.join(SHARED, "analytes.json")
    try:
        doc = json.load(open(path, encoding="utf-8"))
    except (OSError, ValueError) as e:
        problems.append("analytes.json: %s" % e)
        return
    if set(doc) != {"format", "version", "analytes"} or doc.get("format") != "ayuvo-analytes" or doc.get("version") != 1:
        problems.append("analytes.json: bad envelope")
        return
    ids = set()
    index = {}
    for e in doc["analytes"]:
        where = "analytes.json: %s" % e.get("id")
        if set(e) - _ANALYTE_KEYS or not {"id", "display_name", "category", "panels", "kind", "canonical_unit",
                                            "units", "aliases"} <= set(e):
            problems.append("%s: bad keys %s" % (where, sorted(e)))
            continue
        if not re.fullmatch("[a-z][a-z0-9_]*", e["id"]) or e["id"] in ids:
            problems.append("%s: id not unique snake_case" % where)
        ids.add(e["id"])
        if not e["display_name"].strip():
            problems.append("%s: empty display_name" % where)
        if e["category"] not in R.ANALYTE_CATEGORIES:
            problems.append("%s: unknown category" % where)
        if e["kind"] not in ("numeric", "qualitative"):
            problems.append("%s: bad kind" % where)
        if any(p not in R.PANEL_IDS for p in e["panels"]) or len(set(e["panels"])) != len(e["panels"]):
            problems.append("%s: bad panels" % where)
        if e["kind"] == "numeric" and not (isinstance(e.get("decimals"), int) and 0 <= e["decimals"] <= 4):
            problems.append("%s: numeric analyte needs decimals 0..4" % where)
        seen_units = set()
        for u in e["units"]:
            if set(u) != {"unit", "factor", "offset"} or u["unit"] not in unit_canonicals:
                problems.append("%s: unit %r is not a units.json canonical spelling" % (where, u.get("unit")))
                continue
            if isinstance(u["factor"], bool) or not isinstance(u["factor"], (int, float)) or not u["factor"] > 0:
                problems.append("%s: factor of %s must be > 0" % (where, u["unit"]))
            if isinstance(u["offset"], bool) or not isinstance(u["offset"], (int, float)):
                problems.append("%s: offset of %s must be a number" % (where, u["unit"]))
            if u["unit"] in seen_units:
                problems.append("%s: unit %s listed twice" % (where, u["unit"]))
            seen_units.add(u["unit"])
        cu = e["canonical_unit"]
        if cu is None:
            if e["units"]:
                problems.append("%s: units listed without a canonical unit" % where)
        elif not any(u.get("unit") == cu and u.get("factor") == 1 and u.get("offset") == 0 for u in e["units"]):
            problems.append("%s: canonical unit %s must be listed with factor 1, offset 0" % (where, cu))
        if e.get("metric_registry_id") == "blood_glucose" and cu != "mmol/L":
            problems.append("%s: blood_glucose analytes use mmol/L" % where)
        keys = [" ".join(R.analyte_words(a)) for a in e["aliases"]]
        if not keys or any(not k for k in keys) or len(set(keys)) != len(keys):
            problems.append("%s: aliases empty or not unique after normalization" % where)
        for k in keys:
            lst = index.setdefault(k, [])
            if e["id"] not in lst:
                lst.append(e["id"])
    shared = dict((k, v) for k, v in index.items() if len(v) > 1)
    if shared != R.ANALYTE_SHARED_ALIASES:
        diff = sorted(k for k in set(shared) | set(R.ANALYTE_SHARED_ALIASES)
                      if shared.get(k) != R.ANALYTE_SHARED_ALIASES.get(k))
        problems.append("analytes.json: shared aliases differ from ANALYTE_SHARED_ALIASES: %s" % diff)
    by_id = dict((e["id"], e) for e in doc["analytes"])
    for k, group in sorted(shared.items()):
        for i in range(len(group)):
            for j in range(i + 1, len(group)):
                if group[i] in by_id and group[j] in by_id and not _separable(by_id[group[i]], by_id[group[j]]):
                    problems.append("analytes.json: shared alias %r cannot disambiguate %s / %s" % (k, group[i], group[j]))
    for panel, phrases in R._PANEL_KEYWORDS:
        if panel not in R.PANEL_IDS or any(" ".join(R.analyte_words(p)) != p for p in phrases):
            problems.append("_PANEL_KEYWORDS: %s phrases must be in analyte_words form" % panel)


# ---------------------------------------------------------------------------------------------
# Minimal JSON Schema subset validator (type, enum, const, required, properties,
# additionalProperties: false, items, minimum, maximum, maxLength, minLength, pattern, anyOf)
# ---------------------------------------------------------------------------------------------

def _type_ok(v, t):
    return {"object": isinstance(v, dict), "array": isinstance(v, list), "string": isinstance(v, str),
            "integer": isinstance(v, int) and not isinstance(v, bool),
            "number": isinstance(v, (int, float)) and not isinstance(v, bool), "boolean": isinstance(v, bool),
            "null": v is None}[t]


def schema_errors(v, s, path="$"):
    errs = []
    if "anyOf" in s:
        if not any(not schema_errors(v, sub, path) for sub in s["anyOf"]):
            errs.append("%s: matches no anyOf branch" % path)
        return errs
    if "type" in s:
        types = s["type"] if isinstance(s["type"], list) else [s["type"]]
        if not any(_type_ok(v, t) for t in types):
            return ["%s: expected %s" % (path, "/".join(types))]
    if "enum" in s and v not in s["enum"]:
        errs.append("%s: %r not in enum" % (path, v))
    if "const" in s and v != s["const"]:
        errs.append("%s: expected const" % path)
    if isinstance(v, dict):
        for k in s.get("required", []):
            if k not in v:
                errs.append("%s: missing %s" % (path, k))
        props = s.get("properties", {})
        for k, sub in props.items():
            if k in v:
                errs += schema_errors(v[k], sub, path + "." + k)
        if s.get("additionalProperties") is False:
            for k in v:
                if k not in props:
                    errs.append("%s: unexpected property %s" % (path, k))
    if isinstance(v, list) and "items" in s:
        for i, x in enumerate(v):
            errs += schema_errors(x, s["items"], "%s[%d]" % (path, i))
    if isinstance(v, (int, float)) and not isinstance(v, bool):
        if "minimum" in s and v < s["minimum"]:
            errs.append("%s: below minimum" % path)
        if "maximum" in s and v > s["maximum"]:
            errs.append("%s: above maximum" % path)
    if isinstance(v, str):
        if "maxLength" in s and len(v) > s["maxLength"]:
            errs.append("%s: longer than %d" % (path, s["maxLength"]))
        if "minLength" in s and len(v) < s["minLength"]:
            errs.append("%s: shorter than %d" % (path, s["minLength"]))
        if "pattern" in s and not re.search(s["pattern"], v):
            errs.append("%s: does not match pattern" % path)
    return errs


_COACH_TOOL_PROPS = {"records_search": ["query", "from", "to", "record_type", "limit"],
                     "records_get": ["record_id", "include_text"],
                     "records_observation_series": ["analyte", "from", "to"]}
_COACH_PLACEHOLDERS = {
    ("prompt", "available_line"): {"n", "latest_date"}, ("prompt", "selected_header"): set(),
    ("prompt", "selected_line"): {"record_id", "title", "date", "type_label"}, ("prompt", "not_available_line"): set(),
    ("prompt", "guardrails"): set(), ("errors", "unknown_record"): {"id"}, ("errors", "not_selected"): {"id"},
    ("errors", "unknown_analyte"): {"analyte"}, ("errors", "bad_date"): {"value"}, ("errors", "date_order"): set(),
    ("errors", "unavailable"): set(),
}


def compact_schema(schema):
    """Canonical tool input_schema string (§26): the schema object serialized with keys in file order, no
    whitespace (separators ',' and ':'), non-ASCII unescaped, JSON string escapes as json.dumps writes them."""
    return json.dumps(schema, separators=(",", ":"), ensure_ascii=False)


def check_coach_tools(problems):
    """shared/records/coach_tools.json (§26): envelope, unique tool names, schema subset, required ⊆
    properties, the file embeds every input_schema in its compact form, placeholders per string."""
    path = os.path.join(SHARED, "coach_tools.json")
    try:
        raw = open(path, encoding="utf-8").read()
        doc = json.loads(raw)
    except (OSError, ValueError) as e:
        problems.append("coach_tools.json: %s" % e)
        return []
    if set(doc) != {"format", "version", "notes", "tools", "prompt", "errors"} or \
            doc.get("format") != "ayuvo-records-coach-tools" or doc.get("version") != 1:
        problems.append("coach_tools.json: bad envelope")
        return []
    names = [t.get("name") for t in doc["tools"]]
    if len(set(names)) != len(names) or sorted(names) != sorted(_COACH_TOOL_PROPS):
        problems.append("coach_tools.json: tool names must be unique and exactly %s" % sorted(_COACH_TOOL_PROPS))
    out = []
    for t in doc["tools"]:
        where = "coach_tools.json: %s" % t.get("name")
        if set(t) != {"name", "description", "input_schema"} or not isinstance(t.get("description"), str) \
                or not t["description"].strip():
            problems.append("%s: bad tool keys or empty description" % where)
            continue
        s = t["input_schema"]
        if not isinstance(s, dict) or set(s) - {"type", "properties", "required"} or s.get("type") != "object" \
                or not isinstance(s.get("properties"), dict) or not s["properties"]:
            problems.append("%s: input_schema must be an object schema with properties" % where)
            continue
        for pname, p in s["properties"].items():
            if not isinstance(p, dict) or set(p) - {"type", "description", "enum", "minimum", "maximum"} \
                    or p.get("type") not in ("string", "integer", "number", "boolean") \
                    or not isinstance(p.get("description"), str) or not p["description"].strip():
                problems.append("%s: bad property %s" % (where, pname))
        req = s.get("required", [])
        if not isinstance(req, list) or len(set(req)) != len(req) or any(r not in s["properties"] for r in req):
            problems.append("%s: required must be unique property names" % where)
        if t["name"] in _COACH_TOOL_PROPS and list(s["properties"]) != _COACH_TOOL_PROPS[t["name"]]:
            problems.append("%s: properties differ from the reference arguments %s" % (where, _COACH_TOOL_PROPS[t["name"]]))
        compact = compact_schema(s)
        if ('"input_schema": ' + compact) not in raw:
            problems.append("%s: input_schema is not written in its compact canonical form" % where)
        out.append((t["name"], compact))
    for (section, key), wanted in sorted(_COACH_PLACEHOLDERS.items()):
        value = doc.get(section, {}).get(key)
        if not isinstance(value, str):
            problems.append("coach_tools.json: missing %s.%s" % (section, key))
            continue
        found = set(re.findall("\\{([a-z_]+)\\}", value))
        if found != wanted or value.count("{") != len(re.findall("\\{[a-z_]+\\}", value)):
            problems.append("coach_tools.json: %s.%s placeholders %s, expected %s" % (section, key, sorted(found), sorted(wanted)))
    for section in ("prompt", "errors"):
        extra = set(doc[section]) - set(k for s, k in _COACH_PLACEHOLDERS if s == section)
        if extra:
            problems.append("coach_tools.json: unexpected %s keys %s" % (section, sorted(extra)))
    return out


def check_ai_files(problems):
    schema_path = os.path.join(SHARED, "ai_extraction.schema.json")
    md_path = os.path.join(SHARED, "ai_extraction.md")
    try:
        schema = json.load(open(schema_path, encoding="utf-8"))
    except (OSError, ValueError) as e:
        problems.append("ai_extraction.schema.json: %s" % e)
        return None
    try:
        md = open(md_path, encoding="utf-8").read()
    except OSError as e:
        problems.append("ai_extraction.md: %s" % e)
        md = ""
    for needle in ("{record_type_hint}", "{pages}", "=== Page N ===", "## System prompt (full)",
                   "## User template", "## System prompt (compact, on-device)"):
        if needle not in md:
            problems.append("ai_extraction.md: missing %r" % needle)
    enum_types = schema["properties"]["record_type"]["enum"]
    if enum_types != R.RECORD_TYPES:
        problems.append("ai_extraction.schema.json: record_type enum differs from §3")
    date_keys = schema["properties"]["dates"]["items"]["properties"]["key"]["enum"]
    if sorted(date_keys) != sorted(R.DATE_KEYS):
        problems.append("ai_extraction.schema.json: date keys differ from the reference")
    field_keys = schema["properties"]["fields"]["items"]["properties"]["key"]["enum"]
    if sorted(field_keys) != sorted(R._AI_FIELD_KEYS):
        problems.append("ai_extraction.schema.json: field keys differ from the reference")
    return schema


# ---------------------------------------------------------------------------------------------
# Output shapes
# ---------------------------------------------------------------------------------------------

_ITEM_KEYS = {"key", "value_text", "value_json", "confidence", "source_page", "evidence"}


def _shape_items(items, where, problems):
    for it in items:
        if set(it) != _ITEM_KEYS:
            problems.append("%s: item keys %s" % (where, sorted(it)))
            continue
        if it["key"] not in R.FIELD_KEYS:
            problems.append("%s: unknown field key %s" % (where, it["key"]))
        if not (0 <= it["confidence"] <= 1):
            problems.append("%s: confidence out of range" % where)
        if it["key"] == "test_result" and it["value_json"]["flag"] not in R.FLAGS:
            problems.append("%s: bad flag" % where)


_SEARCH_KEYS = {"query", "count", "records", "values"}
_SEARCH_RECORD_KEYS = {"record_id", "title", "date", "record_type", "facility", "doctor", "review_status", "highlights"}
_SEARCH_VALUE_KEYS = {"record_id", "analyte", "name", "value", "unit", "flag", "date"}
_GET_KEYS = {"record_id", "title", "date", "record_type", "category", "facility", "doctor", "referrer", "patient_sex",
             "patient_age", "review_status", "ai_mode_used", "page_count", "fields", "test_results", "highlights", "text"}
_GET_TEST_KEYS = {"name", "analyte", "value", "unit", "ref_text", "ref_low", "ref_high", "flag", "state", "source_page"}
_SERIES_KEYS = {"analyte", "display_name", "unit", "count", "points"}
_SERIES_POINT_KEYS = {"date", "value", "unit", "canonical_value", "flag", "ref_low", "ref_high", "record_id",
                      "record_title", "state"}


def _is_filled_error(message):
    for tpl in R.coach_tools_doc()["errors"].values():
        pieces = re.split("(\\{[a-z_]+\\})", tpl)
        pat = "".join(".*" if p.startswith("{") and p.endswith("}") else re.escape(p) for p in pieces)
        if re.fullmatch(pat, message, re.S):
            return True
    return False


def _check_coach_tools_shape(exp, where, problems, inp):
    if "error" in exp:
        if set(exp) != {"error"} or not _is_filled_error(exp["error"]):
            problems.append("%s: bad error payload" % where)
        return
    snap = inp["snapshot"]
    selected = set(inp.get("selected_ids") or [])
    names = [R.norm_text(f["value_text"]) for f in snap.get("fields") or [] if f["field_key"] == "patient_name"]
    blob = " " + R.norm_text(json.dumps(exp, ensure_ascii=False)) + " "
    if any(n and (" " + n + " ") in blob for n in names):
        problems.append("%s: patient name leaked into a tool payload" % where)
    ids = []
    if inp["tool"] == "records_search":
        if set(exp) != _SEARCH_KEYS or exp["count"] != len(exp["records"]) or len(exp["records"]) > 20 \
                or len(exp["values"]) > 20:
            problems.append("%s: bad records_search payload" % where)
            return
        for r in exp["records"]:
            if set(r) != _SEARCH_RECORD_KEYS or len(r["highlights"]) > 3:
                problems.append("%s: bad search record" % where)
        for v in exp["values"]:
            if set(v) != _SEARCH_VALUE_KEYS or v["flag"] not in R.FLAGS:
                problems.append("%s: bad search value" % where)
        ids = [r["record_id"] for r in exp["records"]] + [v["record_id"] for v in exp["values"]]
    elif inp["tool"] == "records_get":
        if set(exp) != _GET_KEYS or (exp["text"] is not None and len(exp["text"]) > 2000):
            problems.append("%s: bad records_get payload" % where)
            return
        if any(f["key"] in ("test_result", "patient_name", "location") for f in exp["fields"]):
            problems.append("%s: excluded field key in records_get" % where)
        if any(set(t) != _GET_TEST_KEYS or t["flag"] not in R.FLAGS for t in exp["test_results"]):
            problems.append("%s: bad test_results entry" % where)
        ids = [exp["record_id"]]
    else:
        if set(exp) != _SERIES_KEYS or exp["count"] != len(exp["points"]) or len(exp["points"]) > 100 \
                or not exp["points"]:
            problems.append("%s: bad series payload" % where)
            return
        if any(set(p) != _SERIES_POINT_KEYS for p in exp["points"]) or \
                [p["date"] for p in exp["points"]] != sorted(p["date"] for p in exp["points"]):
            problems.append("%s: bad series points" % where)
        ids = [p["record_id"] for p in exp["points"]]
    if selected and any(i not in selected for i in ids):
        problems.append("%s: payload escapes the selected records" % where)


_ARCHIVE_WARNING_CODES = frozenset(R.ARCHIVE_READ_WARNINGS)
_SHARE_WARNING_CODES = frozenset(R.SHARE_WARNINGS)


def _check_share_summary_shape(exp, where, problems, inp):
    if set(exp) != {"text", "record_ids"} or (exp["text"] is None) != (not exp["record_ids"]):
        problems.append("%s: bad share summary payload" % where)
        return
    if exp["text"] is None:
        return
    if not exp["text"].endswith(R.SHARE_FOOTER) or exp["text"].count(R.SHARE_FOOTER) != 1:
        problems.append("%s: the summary must end with the share footer exactly once" % where)
    if exp["text"].count(R.SHARE_SEPARATOR) != len(exp["record_ids"]) - 1:
        problems.append("%s: one '---' separator per extra record" % where)
    if "patient_name" not in set((inp["plan"].get("summary_fields") or [])):
        snap = inp["snapshot"]
        blob = " " + R.norm_text(exp["text"]) + " "
        names = [R.norm_text(f["value_text"]) for f in snap.get("fields") or []
                 if f["field_key"] == "patient_name" and f["record_id"] in exp["record_ids"]]
        if any(n and (" " + n + " ") in blob for n in names):
            problems.append("%s: patient name in a summary that did not select it" % where)


def _check_redaction_shape(exp, where, problems, inp):
    if inp.get("op", "targets") == "plan_warnings":
        if set(exp) != {"warnings", "pages", "records"} or \
                any(w["code"] not in _SHARE_WARNING_CODES or "{" in w["text"] for w in exp["warnings"]):
            problems.append("%s: bad share plan warnings" % where)
        return
    if set(exp) != {"record_id", "classes", "pages", "excluded_pages", "warnings"} or \
            any(c not in R.REDACTION_CLASSES for c in exp["classes"]):
        problems.append("%s: bad redaction payload" % where)
        return
    if set(p["page_index"] for p in exp["pages"]) & set(exp["excluded_pages"]):
        problems.append("%s: a page is both redactable and excluded" % where)
    if len(exp["warnings"]) != len(exp["excluded_pages"]):
        problems.append("%s: one warning per excluded page" % where)
    for page in exp["pages"]:
        idx = [l["index"] for l in page["lines"]]
        if idx != sorted(set(idx)):
            problems.append("%s: line indexes must be unique and ascending" % where)
        for line in page["lines"]:
            if not line["classes"] or any(c not in exp["classes"] for c in line["classes"]):
                problems.append("%s: bad line classes" % where)
            x, y, w, h = line["box"]
            if not (0 <= x <= 1 and 0 <= y <= 1 and 0 <= w <= 1 and 0 <= h <= 1 and x + w <= 1.0001
                    and y + h <= 1.0001):
                problems.append("%s: box outside the page" % where)


def _check_archive_shape(exp, where, problems, inp):
    op = inp["op"]
    if op == "manifest":
        if list(exp) != R.ARCHIVE_MANIFEST_KEYS or exp["format"] != R.ARCHIVE_FORMAT or \
                exp["format_version"] != 1 or exp["schema_version"] != 4:
            problems.append("%s: bad manifest" % where)
    elif op == "rows":
        if [e["name"] for e in exp["entries"]] != list(R.ARCHIVE_DATA_ENTRIES):
            problems.append("%s: entries must follow the §35 order" % where)
            return
        for entry in exp["entries"]:
            columns = R.ARCHIVE_ENTRY_COLUMNS[entry["name"]]
            for row in entry["rows"]:
                extra = set(row) - set(columns) - {"text_truncated", "blocks_truncated"}
                if extra or any(row[c] is None for c in row):
                    problems.append("%s: %s row has %s or a null" % (where, entry["name"], sorted(extra)))
                if [c for c in row if c in columns] != [c for c in columns if c in row]:
                    problems.append("%s: %s row columns are out of schema order" % (where, entry["name"]))
    elif op == "entries":
        if exp["entries"][0] != "manifest.json" or exp["entries"][-1] != "checksums.json":
            problems.append("%s: manifest.json first, checksums.json last" % where)
    elif op == "truncate":
        if exp["line_bytes"] > R.ARCHIVE_LINE_CAP:
            problems.append("%s: the encoded line is over the 256 KB cap" % where)
        if exp["text_truncated"] != (exp["text_length"] < exp["input_length"]):
            problems.append("%s: text_truncated must mean the text was cut" % where)
    elif op == "read":
        if any(w["code"] not in _ARCHIVE_WARNING_CODES or "{" in w["text"] for w in exp["warnings"]):
            problems.append("%s: bad archive warning" % where)
        if exp["ok"] == (exp["error"] is not None):
            problems.append("%s: ok and error disagree" % where)
        if not exp["ok"] and exp["rows"]:
            problems.append("%s: a rejected archive imports nothing" % where)
    elif op == "merge":
        if exp.get("error"):
            return
        if set(exp["imported"]) & set(s["id"] for s in exp["skipped"] if s["reason"] != "duplicate_in_archive"):
            problems.append("%s: a record is both imported and skipped" % where)
        if [r["id"] for r in exp["records"]] != exp["imported"]:
            problems.append("%s: records must mirror imported" % where)
        if exp["mode"] == "merge" and exp["deleted"]:
            problems.append("%s: merge never deletes" % where)


def check_shape(function, exp, where, problems, inp=None):
    if function == "share_summary":
        _check_share_summary_shape(exp, where, problems, inp)
    elif function == "redaction":
        _check_redaction_shape(exp, where, problems, inp)
    elif function == "archive":
        _check_archive_shape(exp, where, problems, inp)
    elif function == "coach_tools":
        _check_coach_tools_shape(exp, where, problems, inp)
    elif function == "pack_coach_records":
        if set(exp) != {"text", "record_ids", "dropped_results"} or len(exp["record_ids"]) > R.COACH_CONTEXT_MAX_RECORDS \
                or (exp["text"] is None) != (not exp["record_ids"]):
            problems.append("%s: bad packed context" % where)
        elif exp["text"] is not None and len(exp["text"]) > R.COACH_CONTEXT_MAX_CHARS and len(exp["record_ids"]) > 1:
            problems.append("%s: packed context over the limit" % where)
    elif function == "coach_prompt" and inp["op"] == "prompt_lines":
        if set(exp) != {"advertise_tools", "available_line", "guardrails", "selected_lines", "not_available_line"} or \
                any("{" in line for line in ([exp["available_line"] or ""] + exp["selected_lines"][:1])):
            problems.append("%s: bad prompt lines" % where)
    elif function == "classify":
        if exp["record_type"] not in R.RECORD_TYPES or exp["category"] != R.DEFAULT_CATEGORY[exp["record_type"]]:
            problems.append("%s: bad type/category" % where)
    elif function in ("extract_dates", "extract_fields", "parse_lab_rows"):
        _shape_items(exp["items"], where, problems)
    elif function == "detect_boundaries":
        segs = exp["segments"]
        if segs and (segs[0]["page_start"] != 0 or any(segs[i]["page_start"] != segs[i - 1]["page_end"] + 1
                                                         for i in range(1, len(segs)))):
            problems.append("%s: segments are not contiguous" % where)
        if exp["propose"] != (len(segs) >= 2):
            problems.append("%s: propose flag" % where)
    elif function == "build_highlights" and inp is not None and inp.get("op") == "important":
        if set(exp) != {"since", "highlights"} or len(exp["highlights"]) > inp["limit"] or \
                any(set(h) != {"id", "record_id", "section", "text", "position"} or h["section"] != "important"
                    for h in exp["highlights"]):
            problems.append("%s: bad important highlights" % where)
        elif (exp["since"] is None) != (not inp.get("today")):
            problems.append("%s: since must follow today" % where)
    elif function == "build_highlights":
        for h in exp["highlights"]:
            if h["section"] not in ("important", "medications", "recommendations", "summary"):
                problems.append("%s: bad section" % where)
    elif function == "review_status":
        if exp["status"] not in ("none", "needs_review", "reviewed"):
            problems.append("%s: bad status" % where)
    elif function == "apply_extraction":
        for r in exp["rows"]:
            if r["state"] not in ("suggested", "confirmed", "rejected", "user"):
                problems.append("%s: bad state" % where)
    elif function == "validate_ai":
        for it in exp["items"]:
            if it["method"] not in ("ai_local", "ai_cloud") or not (0 <= it["confidence"] <= 0.9):
                problems.append("%s: bad AI item" % where)
    elif function == "parse_query":
        for t in exp["record_types"]:
            if t not in R.RECORD_TYPES:
                problems.append("%s: bad record type" % where)
        for f in exp["flags"]:
            if f not in ("abnormal", "low", "high", "critical"):
                problems.append("%s: bad flag" % where)
        cat = R.analyte_catalog()["by_id"]
        for c in exp["analyte_conditions"]:
            if c["analyte_id"] not in cat or (c["flag"] is None) == (c["op"] is None):
                problems.append("%s: bad analyte condition" % where)
            if c["flag"] is not None and c["flag"] not in ("abnormal", "low", "high", "critical", "normal"):
                problems.append("%s: bad condition flag" % where)
            if c["op"] is not None and c["op"] not in (">", ">=", "<", "<="):
                problems.append("%s: bad condition op" % where)
        if any(a not in cat for a in exp["analytes"]):
            problems.append("%s: unknown analyte" % where)
    elif function == "map_analyte" and "analyte_id" in exp:
        if exp["analyte_id"] is not None and exp["analyte_id"] not in R.analyte_catalog()["by_id"]:
            problems.append("%s: unknown analyte" % where)
        if (exp["analyte_id"] is None) != (exp["method"] is None) or (exp["candidates"] and exp["analyte_id"]):
            problems.append("%s: inconsistent mapping" % where)
    elif function == "convert_unit":
        if exp["status"] not in ("converted", "unmapped", "no_value", "unit_missing", "unit_unknown") or \
                ((exp["canonical_value"] is not None) != (exp["status"] == "converted")):
            problems.append("%s: bad conversion status" % where)
    elif function == "observations" and "observations" in exp:
        for o in exp["observations"]:
            if set(o) != set(R.OBSERVATION_KEYS):
                problems.append("%s: observation keys %s" % (where, sorted(set(o) ^ set(R.OBSERVATION_KEYS))))
            elif (o["state"] not in ("suggested", "confirmed", "rejected", "user") or o["flag"] not in R.FLAGS
                  or o["analyte_method"] not in R.ANALYTE_METHODS + [None]):
                problems.append("%s: bad observation enums" % where)
    elif function == "observations" and exp.get("observation") is not None:
        o = exp["observation"]
        if set(o) != set(R.OBSERVATION_KEYS) or o["flag"] not in R.FLAGS:
            problems.append("%s: bad edited observation" % where)
    elif function == "entities" and "entities" in exp:
        for e in exp["entities"]:
            if e["kind"] not in ("doctor", "facility"):
                problems.append("%s: bad entity kind" % where)
        for l in exp["record_entities"]:
            if (l["kind"], l["role"]) not in (("doctor", "doctor"), ("doctor", "referrer"), ("facility", "facility")):
                problems.append("%s: bad entity role" % where)
    elif function == "suggest_relations":
        if len(exp["links"]) > R.MAX_SUGGESTIONS:
            problems.append("%s: more than %d suggestions" % (where, R.MAX_SUGGESTIONS))
        for l in exp["links"]:
            if not l["a_id"] < l["b_id"] or l["kind"] not in R.LINK_KINDS or l["score"] < 0.6:
                problems.append("%s: bad link" % where)


# ---------------------------------------------------------------------------------------------
# Vectors
# ---------------------------------------------------------------------------------------------

def _first_diff(a, b, path="$"):
    if type(a) != type(b) and not (isinstance(a, (int, float)) and isinstance(b, (int, float))):
        return path
    if isinstance(a, dict):
        for k in sorted(set(a) | set(b)):
            if k not in a or k not in b:
                return path + "." + k
            d = _first_diff(a[k], b[k], path + "." + k)
            if d:
                return d
        return None
    if isinstance(a, list):
        if len(a) != len(b):
            return path + " (length %d vs %d)" % (len(a), len(b))
        for i in range(len(a)):
            d = _first_diff(a[i], b[i], "%s[%d]" % (path, i))
            if d:
                return d
        return None
    return None if a == b else path


def check_vectors(write, schema, problems):
    counts = {}
    present = sorted(os.path.basename(p) for p in glob.glob(os.path.join(VECTORS, "*.json")))
    for name in sorted(EXPECTED_FILES):
        if name not in present:
            problems.append("test-vectors/%s missing" % name)
    for name in present:
        path = os.path.join(VECTORS, name)
        raw = open(path, encoding="utf-8").read()
        try:
            doc = json.loads(raw)
        except ValueError as e:
            problems.append("%s: invalid JSON: %s" % (name, e))
            continue
        allowed = {"format", "version", "function", "cases"} | ({"fixtures"} if name in FIXTURE_FILES else set())
        if (doc.get("format") != "ayuvo-records-vectors" or doc.get("version") != 1 or set(doc) - allowed
                or doc.get("function") != EXPECTED_FILES.get(name) or not isinstance(doc.get("cases"), list)):
            problems.append("%s: bad envelope" % name)
            continue
        snaps = (doc.get("fixtures") or {}).get("snapshots") or {}
        if any(isinstance(c.get("input", {}).get("snapshot"), str) and c["input"]["snapshot"] not in snaps
               for c in doc["cases"] if isinstance(c.get("input"), dict)):
            problems.append("%s: a case names an unknown snapshot fixture" % name)
            continue
        seen = set()
        changed = False
        for c in doc["cases"]:
            if set(c) - {"name", "input", "expected", "notes"} or not isinstance(c.get("input"), dict):
                problems.append("%s: bad case keys %s" % (name, sorted(c)))
                continue
            if c["name"] in seen:
                problems.append("%s: duplicate case %s" % (name, c["name"]))
            seen.add(c["name"])
            got = json.loads(json.dumps(R.run_case(doc["function"], resolve_input(doc, c["input"])), ensure_ascii=False))
            where = "%s/%s" % (name, c["name"])
            if write:
                if c.get("expected") != got:
                    c["expected"] = got
                    changed = True
            elif c.get("expected") != got:
                problems.append("%s: expected differs from the reference at %s" % (where, _first_diff(c.get("expected"), got)))
            check_shape(doc["function"], got, where, problems, resolve_input(doc, c["input"]))
            if schema is not None and c["input"].get("schema_valid"):
                ai = R.lenient_json(c["input"]["ai_json"])
                for err in schema_errors(ai, schema):
                    problems.append("%s: AI input violates ai_extraction.schema.json: %s" % (where, err))
        counts[name] = len(doc["cases"])
        if write and (changed or raw != dumps(doc)):
            with open(path, "w", encoding="utf-8") as fh:
                fh.write(dumps(doc))
        elif not write and raw != dumps(doc):
            problems.append("%s: not in canonical format (run --write)" % name)
    return counts


# ---------------------------------------------------------------------------------------------
# §35 fixture archive (shared/records/fixtures/ayuvo-records-fixture.zip)
# ---------------------------------------------------------------------------------------------
#
# Everything here is byte-deterministic: the PDF is assembled with computed xref offsets, the PNG uses
# stored (uncompressed) deflate blocks, the JPEG carries one-code Huffman tables, and the zip is written
# with a fixed timestamp and fixed attributes. Rebuild with --write after changing the fixture snapshot.

def fixture_pdf(lines):
    """A tiny, valid single-page PDF (Helvetica 12pt, US Letter) containing `lines`."""
    content = "BT\n/F1 12 Tf\n72 720 Td\n14 TL\n"
    for i, line in enumerate(lines):
        if i:
            content += "T*\n"
        content += "(%s) Tj\n" % line.replace("\\", r"\\").replace("(", r"\(").replace(")", r"\)")
    content += "ET\n"
    body = content.encode("ascii")
    objects = [
        b"<< /Type /Catalog /Pages 2 0 R >>",
        b"<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
        b"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << /Font << /F1 5 0 R >> >>"
        b" /Contents 4 0 R >>",
        b"<< /Length " + str(len(body)).encode("ascii") + b" >>\nstream\n" + body + b"endstream",
        b"<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>",
    ]
    out = b"%PDF-1.4\n%\xe2\xe3\xcf\xd3\n"
    offsets = []
    for i, obj in enumerate(objects, 1):
        offsets.append(len(out))
        out += b"%d 0 obj\n" % i + obj + b"\nendobj\n"
    start = len(out)
    out += b"xref\n0 %d\n" % (len(objects) + 1) + b"0000000000 65535 f \n"
    for off in offsets:
        out += b"%010d 00000 n \n" % off
    out += b"trailer\n<< /Size %d /Root 1 0 R >>\nstartxref\n%d\n%%%%EOF\n" % (len(objects) + 1, start)
    return out


def _deflate_stored(raw):
    """A zlib stream using only stored (BTYPE 00) deflate blocks — identical bytes everywhere."""
    out = b"\x78\x01"
    i = 0
    while True:
        chunk = raw[i:i + 65535]
        i += len(chunk)
        final = 1 if i >= len(raw) else 0
        out += bytes([final]) + len(chunk).to_bytes(2, "little") + (0xFFFF - len(chunk)).to_bytes(2, "little")
        out += chunk
        if final:
            break
    return out + zlib.adler32(raw).to_bytes(4, "big")


def _png_chunk(kind, data):
    return (len(data).to_bytes(4, "big") + kind + data +
            (zlib.crc32(kind + data) & 0xFFFFFFFF).to_bytes(4, "big"))


def fixture_png(width, height, rgb):
    """A tiny, valid 8-bit RGB PNG filled with one colour."""
    raw = b"".join(b"\x00" + bytes(rgb) * width for _ in range(height))
    ihdr = width.to_bytes(4, "big") + height.to_bytes(4, "big") + bytes([8, 2, 0, 0, 0])
    return (b"\x89PNG\r\n\x1a\n" + _png_chunk(b"IHDR", ihdr) +
            _png_chunk(b"IDAT", _deflate_stored(raw)) + _png_chunk(b"IEND", b""))


def fixture_jpeg(quant):
    """A tiny, valid 8x8 greyscale baseline JPEG: one quantization table, two one-code Huffman tables and
    a single MCU whose DC difference is 0 (code '0') followed by EOB (code '0')."""
    def seg(marker, payload):
        return bytes([0xFF, marker]) + (len(payload) + 2).to_bytes(2, "big") + payload
    dqt = seg(0xDB, bytes([0x00]) + bytes([quant]) * 64)
    sof = seg(0xC0, bytes([8]) + (8).to_bytes(2, "big") + (8).to_bytes(2, "big") + bytes([1, 1, 0x11, 0]))
    bits = bytes([1] + [0] * 15)
    dht_dc = seg(0xC4, bytes([0x00]) + bits + bytes([0x00]))
    dht_ac = seg(0xC4, bytes([0x10]) + bits + bytes([0x00]))
    sos = seg(0xDA, bytes([1, 1, 0x00, 0, 63, 0]))
    return b"\xff\xd8" + dqt + sof + dht_dc + dht_ac + sos + b"\x3f" + b"\xff\xd9"


def fixture_blobs():
    """{files/ entry name: bytes} for the fixture archive, in export order."""
    return {
        "files/rec-cbc-0001/original.pdf": fixture_pdf([
            "Sunrise Diagnostics and Research Centre",
            "Complete Blood Count",
            "Patient Name: Rohan Mehta        Age/Sex: 34 Y / M",
            "UHID: SD2026004417               Mobile: +91 98765 43210",
            "Collected On: 12-Sep-2026        Reported On: 13-Sep-2026",
            "Hemoglobin            7.6 g/dL        13.0 - 17.0   L",
            "Total WBC Count       8.2 10^3/uL     4.0 - 10.0",
            "Platelet Count        96 10^3/uL      150 - 410     L",
            "Dr. A. K. Sharma, MD (Internal Medicine)",
        ]),
        "files/rec-cbc-0001/thumb.jpg": fixture_jpeg(16),
        "files/rec-lipid-0004/original.pdf": fixture_pdf([
            "Sunrise Diagnostics and Research Centre",
            "Lipid Profile",
            "Patient Name: Rohan Mehta",
            "Reported On: 20-Nov-2025",
            "Total Cholesterol     214 mg/dL       < 200         H",
            "HDL Cholesterol       38 mg/dL        > 40          L",
            "LDL Cholesterol       142 mg/dL       < 100         H",
            "Triglycerides         168 mg/dL       < 150         H",
            "Dr. A. K. Sharma, MD (Internal Medicine)",
        ]),
        "files/rec-lipid-0004/thumb.jpg": fixture_jpeg(20),
        "files/rec-note-0003/original.txt":
            ("Felt dizzy again after climbing stairs on 14 Sep.\n"
             "Started Ferrous ascorbate the same evening.\n"
             "Ask Dr Iyer whether the dose should change.\n").encode("utf-8"),
        "files/rec-rx-0002/original.png": fixture_png(8, 8, (240, 244, 248)),
        "files/rec-rx-0002/thumb.jpg": fixture_jpeg(24),
    }


def fixture_snapshot(problems):
    path = os.path.join(VECTORS, "archive.json")
    try:
        doc = json.load(open(path, encoding="utf-8"))
        return doc["fixtures"]["snapshots"][FIXTURE_SNAPSHOT]
    except (OSError, ValueError, KeyError) as e:
        problems.append("archive.json: no %r snapshot fixture (%s)" % (FIXTURE_SNAPSHOT, e))
        return None


def build_fixture_archive(snapshot):
    """The fixture archive as an ordered {entry name: bytes}, exactly as §35 orders it."""
    blobs = fixture_blobs()
    rows = R.archive_rows(snapshot)
    manifest = R.archive_manifest(snapshot, **FIXTURE_MANIFEST)
    entries = {}
    for name in R.archive_entry_names(snapshot):
        if name == "manifest.json":
            data = R.archive_entry_text(name, manifest).encode("utf-8")
        elif name == "checksums.json":
            data = R.archive_entry_text(name, dict((k, hashlib.sha256(v).hexdigest())
                                                   for k, v in entries.items())).encode("utf-8")
        elif name.startswith("files/"):
            data = blobs[name]
        else:
            data = R.archive_entry_text(name, rows[name]).encode("utf-8")
        entries[name] = data
    return entries


def write_fixture_archive(entries, path):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with zipfile.ZipFile(path, "w") as zf:
        for name, data in entries.items():
            info = zipfile.ZipInfo(name, date_time=FIXTURE_ZIP_DATE)
            info.compress_type = zipfile.ZIP_DEFLATED
            info.create_system = 3
            info.external_attr = 0o644 << 16
            zf.writestr(info, data)


def check_fixture_archive(write, problems):
    """Validate (or rebuild with --write) shared/records/fixtures/ayuvo-records-fixture.zip against §35:
    entry order, entry bytes from archive_rows/archive_manifest, checksums.json, and a read + merge
    round-trip. -> a short description for the report."""
    snapshot = fixture_snapshot(problems)
    if snapshot is None:
        return None
    wanted = build_fixture_archive(snapshot)
    if write:
        write_fixture_archive(wanted, FIXTURE_ARCHIVE)
    if not os.path.exists(FIXTURE_ARCHIVE):
        problems.append("fixtures/ayuvo-records-fixture.zip is missing (run --write)")
        return None
    size = os.path.getsize(FIXTURE_ARCHIVE)
    if size > FIXTURE_MAX_BYTES:
        problems.append("fixtures/ayuvo-records-fixture.zip is %d bytes (cap %d)" % (size, FIXTURE_MAX_BYTES))
    with zipfile.ZipFile(FIXTURE_ARCHIVE) as zf:
        names = zf.namelist()
        got = dict((n, zf.read(n)) for n in names)
        if any(zf.getinfo(n).compress_type != zipfile.ZIP_DEFLATED for n in names):
            problems.append("fixture archive: every entry must be deflated")
    if names != list(wanted):
        problems.append("fixture archive: entry order %s, expected %s" % (names, list(wanted)))
        return None
    for name in names:
        if got[name] != wanted[name]:
            problems.append("fixture archive: %s differs from the reference output" % name)
    checksums = json.loads(got["checksums.json"].decode("utf-8"))
    for name in names:
        if name == "checksums.json":
            continue
        if checksums.get(name) != hashlib.sha256(got[name]).hexdigest():
            problems.append("fixture archive: bad checksum for %s" % name)
    if set(checksums) != set(names) - {"checksums.json"}:
        problems.append("fixture archive: checksums.json must cover every other entry")
    entries = []
    for name in names:
        entry = {"name": name, "sha256": hashlib.sha256(got[name]).hexdigest()}
        if name.endswith(".ndjson"):
            entry["rows"] = [json.loads(line) for line in got[name].decode("utf-8").split("\n") if line]
        elif not name.startswith("files/"):
            value = json.loads(got[name].decode("utf-8"))
            entry["json" if name in ("manifest.json", "checksums.json") else "rows"] = value
        entries.append(entry)
    read = R.read_archive_rows(entries)
    if not read["ok"] or read["warnings"]:
        problems.append("fixture archive: read_archive_rows reports %s / %s"
                        % (read["error"], [w["code"] for w in read["warnings"]]))
    plan = R.merge_plan({"records": []}, read, "merge")
    expected_records = len(snapshot.get("records") or [])
    if len(plan["imported"]) != expected_records or plan["skipped"] or plan["file_missing"]:
        problems.append("fixture archive: a fresh merge must import every record without warnings")
    round_trip = build_fixture_archive({"records": read["rows"]["records"], "pages": read["rows"]["pages"],
                                        "fields": read["rows"]["fields"],
                                        "observations": read["rows"]["observations"],
                                        "highlights": read["rows"]["highlights"], "links": read["rows"]["links"],
                                        "entities": read["rows"]["entities"],
                                        "record_entities": read["rows"]["record_entities"],
                                        "tags": [{"id": t["id"], "name": t["name"]} for t in read["rows"]["tags"]],
                                        "record_tags": [{"record_id": rid, "tag_id": t["id"]}
                                                        for t in read["rows"]["tags"]
                                                        for rid in t.get("record_ids") or []],
                                        "analyte_user_aliases": read["rows"]["analyte_user_aliases"]})
    # manifest.json and checksums.json depend on the exporting device (file sizes on disk), so only the
    # data entries must survive an import + re-export. §38: the comparison is structural, never
    # byte-for-byte (a SQLite REAL column cannot preserve 96 vs 96.0).
    def parsed(data, name):
        text = data.decode("utf-8")
        if name.endswith(".ndjson"):
            return [json.loads(line) for line in text.split("\n") if line]
        return json.loads(text)
    for name in R.ARCHIVE_DATA_ENTRIES:
        if name not in round_trip:
            problems.append("fixture archive: re-exporting the imported rows drops %s" % name)
            continue
        diff = _first_diff(parsed(round_trip[name], name), parsed(wanted[name], name))
        if diff:
            problems.append("fixture archive: re-exporting the imported rows changes %s at %s" % (name, diff))
    return "%d entries, %d bytes, %d records" % (len(names), size, expected_records)


# ---------------------------------------------------------------------------------------------
# Self-tests
# ---------------------------------------------------------------------------------------------

class ReferenceSelfTest(unittest.TestCase):
    def test_fold_is_idempotent_and_length_preserving(self):
        for s in ["Hémoglobine  µg/dL\r\n\tİ ²", "ÅNGSTRÖM ﬁ", "Σ plain"]:
            self.assertEqual(R.fold(R.fold(s)), R.fold(s))
            self.assertEqual(len(R.fold(s)), len(R.pfold(s)))

    def test_round2_half_up(self):
        self.assertEqual(R.round2(0.625), 0.63)
        self.assertEqual(R.round2(0.8125), 0.81)
        self.assertEqual(R.round2(0.955), 0.96)

    def test_statements_rule(self):
        import tempfile
        with tempfile.NamedTemporaryFile("w", suffix=".sql", delete=False, encoding="utf-8") as fh:
            fh.write("-- header\r\nCREATE TABLE a (\n  x TEXT, -- note; not an end\n  y INTEGER);\n\nCREATE INDEX i ON a(x);  \n")
        try:
            self.assertEqual(statements(fh.name), ["CREATE TABLE a (\n  x TEXT,\n  y INTEGER)", "CREATE INDEX i ON a(x)"])
        finally:
            os.unlink(fh.name)

    def test_fnv_known_values(self):
        self.assertEqual(R.fnv1a64(b""), 0xcbf29ce484222325)
        self.assertEqual(R.fnv1a64(b"a"), 0xaf63dc4c8601ec8c)

    def test_lint_flags_non_portable(self):
        self.assertIn("named group", lint_pattern("(?P<x>a)", False))
        self.assertIn("variable-length lookbehind", lint_pattern("(?<!a+)b", False))
        self.assertIn("possessive quantifier", lint_pattern("a++", False))
        self.assertEqual(lint_pattern("(?<![a-z])ab(?:c|d)?[0-9]{1,2}", True), [])

    def test_analyte_words_and_keys(self):
        self.assertEqual(R.analyte_words("S.G.O.T. (AST)"), ["sgot", "ast"])
        self.assertEqual(R.analyte_words("Vitamin D"), ["vitamin", "d"])
        self.assertEqual(R.test_name_keys("Sr. Creatinine (Jaffe)"), ["sr creatinine jaffe", "sr creatinine", "creatinine"])

    def test_round4_and_format(self):
        self.assertEqual(R.round4(95 * 0.0555), 5.2725)
        self.assertEqual(R.format_value(6.105, 1), "6.1")
        self.assertEqual(R.format_value(-0.04, 1), "0.0")

    def test_coach_helpers(self):
        self.assertEqual(R.fill_placeholders("{a} {b} {c}", {"a": "{b}", "b": 2}), "{b} 2 {c}")
        self.assertEqual(R.fts_tokens("hb: 9.1 g/dl — low μl"), ["hb", "9", "1", "g", "dl", "—", "low", "μl"])
        self.assertEqual(compact_schema({"type": "object", "properties": {"q": {"type": "string", "description": "a \"b\""}}}),
                         '{"type":"object","properties":{"q":{"type":"string","description":"a \\"b\\""}}}')
        self.assertEqual(R.collapse_ws(" a \r\n\tb  "), "a b")

    def test_highlight_window_clamps_month_end(self):
        self.assertEqual(R.highlights_since("2026-08-31"), "2026-02-28")
        self.assertEqual(R.highlights_since("2028-08-31"), "2028-02-29")
        self.assertEqual(R.highlights_since("2026-01-15"), "2025-07-15")
        self.assertEqual(R.add_months_clamped("2026-03-31", -1), "2026-02-28")

    def test_apply_extraction_never_overwrites_confirmed(self):
        rows = [{"id": "r", "field_key": "facility", "value_text": "Metro Labs", "value_json": None, "method": "user",
                 "confidence": 0.1, "state": "confirmed", "source_page": 0, "source_bbox": None, "evidence": None}]
        out = R.apply_extraction(rows, [{"key": "facility", "value_text": "METRO LABS", "value_json": None,
                                         "method": "ai_cloud", "confidence": 0.9}])
        self.assertEqual(out["rows"], rows)


def main(argv):
    write = "--write" in argv
    problems = []
    check_data_files(problems)
    schema = check_ai_files(problems)
    sql_counts = check_sql(problems)
    coach_schemas = check_coach_tools(problems)
    counts = check_vectors(write, schema, problems)
    fixture = check_fixture_archive(write, problems)
    suite = unittest.defaultTestLoader.loadTestsFromTestCase(ReferenceSelfTest)
    result = unittest.TextTestRunner(stream=open(os.devnull, "w"), verbosity=0).run(suite)
    for f, tb in result.failures + result.errors:
        problems.append("self-test %s failed:\n%s" % (f.id(), tb))
    for rel in sorted(sql_counts):
        print("sql  %-45s %d statements" % (rel, sql_counts[rel]))
    for name in sorted(counts):
        print("vec  %-45s %d cases" % (name, counts[name]))
    for name, compact in coach_schemas:
        print("tool %-45s schema %d chars" % (name, len(compact)))
    if fixture:
        print("zip  %-45s %s" % ("fixtures/ayuvo-records-fixture.zip", fixture))
    if "--print-schemas" in argv:
        for name, compact in coach_schemas:
            print("%s %s" % (name, compact))
    print("self-tests: %d run" % result.testsRun)
    if problems:
        print("\n%d problem(s):" % len(problems))
        for p in problems:
            print("  - " + p)
        return 1
    print("records contract OK" + (" (vectors rewritten)" if write else ""))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
