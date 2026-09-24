#!/usr/bin/env python3
"""Ayuvo Coach contract check (Python 3 stdlib only).

    python3 scripts/coach_contract_check.py           # verify; exit 1 on any problem
    python3 scripts/coach_contract_check.py --write   # rewrite every vector's "expected" from the reference

Checks:
  1. shared/coach/test-vectors/*.json: envelope shape, unique case names, stable formatting (sorted keys,
     2-space indent, UTF-8 without escapes, trailing newline), and every case's "expected" equals
     scripts/coach_reference.py run on its "input" (inputs are the source of truth; --write only replaces
     "expected"). Every file in EXPECTED_FILES must exist and no unknown file may be present.
  2. Output shapes per function (block kinds, chart types and caps, source names, archive envelope).
  3. shared/coach/chart_spec.json and shared/coach/prompt_gallery.json: canonical format, declared types
     equal the reference's, every gallery `requires` names a real source, ids and orders unique.
  4. shared/medications/coach_tools.json: the records coach-tools shape, compact one-line input schemas,
     names equal the reference's MEDICATION_TOOLS, every prompt placeholder known.
  5. shared/coach/schema.sql split with the records statements() rule (docs/health-records.md §8); table
     and index names equal the documented lists.
  6. Archive round trip: export -> merge into an empty snapshot -> export again is byte-identical, and a
     local tombstone survives a merge.
  7. Safety: no vector case carries a health value into a stored message field, and `attachment_excerpt`
     never returns a line the records PII rule would have dropped.
  8. Regex portability lint of every pattern owned by the reference (same rule as the records contract).
  9. A small unittest suite with hand-computed expectations for the tricky cases.
"""

import glob
import hashlib
import json
import os
import re
import sys
import unittest
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
SHARED = os.path.join(ROOT, "shared", "coach")
VECTORS = os.path.join(SHARED, "test-vectors")
CHART_SPEC = os.path.join(SHARED, "chart_spec.json")
GALLERY = os.path.join(SHARED, "prompt_gallery.json")
SCHEMA = os.path.join(SHARED, "schema.sql")
FIXTURE_ARCHIVE = os.path.join(SHARED, "fixtures", "ayuvo-coach-chats-fixture.zip")
#: A small, fixed chat the platform tests read back (§11). Two conversations, one of them pinned,
#: one attachment with a blob, and a tombstone that must not be exported.
FIXTURE_SNAPSHOT = {
    "conversations": [
        {"id": "conv-sleep-0001", "title": "How did I sleep this week?", "created_ms": 1789000000000,
         "updated_ms": 1789000200000, "last_message_ms": 1789000200000, "pinned": 1, "archived": 0,
         "data_sources": {"medications": False}, "selected_record_ids": ["rec-sleep-0007"],
         "provider_override": None, "deleted": 0},
        {"id": "conv-diet-0002", "title": "Diet chart from my health data", "created_ms": 1789100000000,
         "updated_ms": 1789100100000, "last_message_ms": 1789100100000, "pinned": 0, "archived": 0,
         "data_sources": {}, "selected_record_ids": [], "provider_override": None, "deleted": 0},
        {"id": "conv-gone-0003", "title": "Deleted here", "created_ms": 1789200000000,
         "updated_ms": 1789200000000, "last_message_ms": None, "pinned": 0, "archived": 0,
         "data_sources": {}, "selected_record_ids": [], "provider_override": None, "deleted": 1},
    ],
    "messages": [
        {"id": "msg-0001", "conversation_id": "conv-sleep-0001", "seq": 1, "variant_index": 0,
         "role": "user", "content": "How did I sleep this week?", "created_ms": 1789000100000,
         "updated_ms": 1789000100000, "regenerated_from": None, "record_refs": [],
         "attachment_ids": ["att-0001"], "deleted": 0},
        {"id": "msg-0002", "conversation_id": "conv-sleep-0001", "seq": 2, "variant_index": 0,
         "role": "assistant", "content": "You averaged 6 h 40 m.", "created_ms": 1789000200000,
         "updated_ms": 1789000200000, "regenerated_from": None,
         "record_refs": [{"record_id": "rec-sleep-0007", "title": "Sleep study", "date": "2026-08-02"}],
         "attachment_ids": [], "deleted": 0},
        {"id": "msg-0003", "conversation_id": "conv-diet-0002", "seq": 1, "variant_index": 0,
         "role": "user", "content": "Make a diet chart from my health data.",
         "created_ms": 1789100100000, "updated_ms": 1789100100000, "regenerated_from": None,
         "record_refs": [], "attachment_ids": [], "deleted": 0},
        {"id": "msg-0004", "conversation_id": "conv-gone-0003", "seq": 1, "variant_index": 0,
         "role": "user", "content": "Never exported", "created_ms": 1789200000000,
         "updated_ms": 1789200000000, "regenerated_from": None, "record_refs": [],
         "attachment_ids": [], "deleted": 1},
    ],
    "attachments": [
        {"id": "att-0001", "kind": "pdf", "filename": "sleep-study.pdf",
         "mime_type": "application/pdf", "bytes": 5, "sha256": None, "page_count": 1,
         "char_count": 27, "excerpt": "Sleep efficiency 88 percent", "created_ms": 1789000050000,
         "deleted": 0},
        {"id": "att-0002", "kind": "image", "filename": "unused.jpg", "mime_type": "image/jpeg",
         "bytes": 3, "sha256": None, "page_count": None, "char_count": None, "excerpt": None,
         "created_ms": 1789000060000, "deleted": 0},
    ],
}
FIXTURE_BLOBS = {"att-0001": ("sleep-study.pdf", b"%PDF-")}
FIXTURE_MANIFEST = {"app_version": "1.0", "platform": "fixture", "exported_at": 1789300000000,
                    "zone_id": "Asia/Kolkata"}
FIXTURE_MAX_BYTES = 32 * 1024
MEDS_TOOLS = os.path.join(ROOT, "shared", "medications", "coach_tools.json")
#: Every shared catalog is bundled verbatim on both platforms; a drift here is a parity bug.
COPIES = {
    CHART_SPEC: ["ios/calorietracker/Coach/Resources/chart_spec.json",
                 "android/app/src/main/assets/coach/chart_spec.json"],
    GALLERY: ["ios/calorietracker/Coach/Resources/prompt_gallery.json",
              "android/app/src/main/assets/coach/prompt_gallery.json"],
    # iOS flattens bundle resources, so this copy cannot also be called coach_tools.json
    # (Records already ships one); Android assets are path-scoped and keep the shared name.
    MEDS_TOOLS: ["ios/calorietracker/Medications/Resources/medication_coach_tools.json",
                 "android/app/src/main/assets/medications/coach_tools.json"],
}
sys.path.insert(0, HERE)
import coach_reference as R  # noqa: E402
from records_contract_check import (  # noqa: E402
    FIXTURE_ZIP_DATE, lint_pattern, statements, write_fixture_archive,
)

FORMAT = "ayuvo-coach-vectors"
EXPECTED_FILES = {
    "markdown_blocks.json": "parse_blocks",
    "chart_spec.json": "parse_chart_spec",
    "chart_repair.json": "repair_chart_json",
    "attachment_excerpt.json": "attachment_excerpt",
    "conversation_title.json": "conversation_title",
    "data_sources.json": "resolve_data_sources",
    "prompt_gallery.json": "gallery_for",
    "prompt_chips.json": "chips_for",
    "chat_archive.json": "chat_archive",
    "export.json": "export",
}
TABLES = ["conversations", "messages", "attachments", "messages_fts", "coach_meta"]
INDEXES = ["idx_conversations_recent", "idx_messages_conversation", "idx_messages_regenerated",
           "idx_attachments_sha"]
DOCUMENTED = {
    "MAX_SERIES": 4, "MAX_POINTS": 60, "MAX_LABEL_CHARS": 60, "MAX_TITLE_CHARS": 120,
    "MAX_ATTACHMENT_PAGES": 20, "MAX_ATTACHMENT_CHARS": 20000, "MAX_TURN_CHARS": 40000,
    "MAX_TITLE_LENGTH": 48, "MIN_SENTENCE_LENGTH": 12, "MAX_LIST_DEPTH": 3, "MAX_QUOTE_DEPTH": 3,
    "MAX_HEADING_LEVEL": 4, "TAB_WIDTH": 4, "INDENT_PER_DEPTH": 2,
    "CHART_FENCE": "ayuvo-chart", "ARCHIVE_FORMAT": "ayuvo-coach-chats", "ARCHIVE_VERSION": 1,
    "SOURCES": ("food", "health", "medications", "records"),
}
ALL_TOOLS = set(R.FOOD_TOOLS + R.WORKOUT_TOOLS + R.HEALTH_TOOLS + R.MEDICATION_TOOLS + R.RECORDS_TOOLS)
_RE_PLACEHOLDER = re.compile("\\{([a-z_]+)\\}")


def dumps(obj):
    return json.dumps(obj, indent=2, sort_keys=True, ensure_ascii=False) + "\n"


def _first_diff(a, b, path="$"):
    if type(a) != type(b) and not (isinstance(a, (int, float)) and isinstance(b, (int, float))):
        return "%s: %r != %r" % (path, a, b)
    if isinstance(a, dict):
        for k in sorted(set(a) | set(b)):
            if k not in a or k not in b:
                return "%s.%s: missing on one side" % (path, k)
            d = _first_diff(a[k], b[k], "%s.%s" % (path, k))
            if d:
                return d
        return None
    if isinstance(a, list):
        if len(a) != len(b):
            return "%s: length %d != %d" % (path, len(a), len(b))
        for i, (x, y) in enumerate(zip(a, b)):
            d = _first_diff(x, y, "%s[%d]" % (path, i))
            if d:
                return d
        return None
    return None if a == b else "%s: %r != %r" % (path, a, b)


# ---------------------------------------------------------------------------------------------
# Shared catalogs
# ---------------------------------------------------------------------------------------------

def check_chart_spec(problems):
    raw = open(CHART_SPEC, encoding="utf-8").read()
    doc = json.loads(raw)
    if raw != dumps(doc):
        problems.append("chart_spec.json: not in canonical format (run --write)")
    if doc.get("format") != "ayuvo-coach-chart-spec" or doc.get("version") != 1:
        problems.append("chart_spec.json: bad format/version")
    if doc.get("fence") != R.CHART_FENCE:
        problems.append("chart_spec.json: fence %r != reference %r" % (doc.get("fence"), R.CHART_FENCE))
    if sorted(doc.get("types") or {}) != sorted(R.CHART_TYPES):
        problems.append("chart_spec.json: types %s != reference %s"
                        % (sorted(doc.get("types") or {}), sorted(R.CHART_TYPES)))
    if sorted(doc.get("reasons") or []) != sorted(R.CHART_REASONS):
        problems.append("chart_spec.json: reasons differ from the reference")
    caps = doc.get("caps") or {}
    for key, name in (("max_series", "MAX_SERIES"), ("max_points", "MAX_POINTS"),
                      ("max_label_chars", "MAX_LABEL_CHARS"), ("max_title_chars", "MAX_TITLE_CHARS")):
        if caps.get(key) != getattr(R, name):
            problems.append("chart_spec.json: caps.%s != reference %s" % (key, name))
    prompt = doc.get("prompt") or {}
    for key in ("charts_section", "guardrails"):
        if not isinstance(prompt.get(key), str) or not prompt[key].strip():
            problems.append("chart_spec.json: prompt.%s missing" % key)
    section = prompt.get("charts_section", "")
    if R.CHART_FENCE not in section:
        problems.append("chart_spec.json: prompt.charts_section never names the fence")
    for kind in R.CHART_TYPES:
        if kind not in section:
            problems.append("chart_spec.json: prompt.charts_section does not list type %r" % kind)
    # The "never invent a number" rule is the reason this feature is safe; it must stay in the prompt.
    if "Never estimate" not in section:
        problems.append("chart_spec.json: prompt.charts_section lost the no-invented-values rule")
    return len(R.CHART_TYPES)


def check_gallery(problems):
    raw = open(GALLERY, encoding="utf-8").read()
    doc = json.loads(raw)
    if raw != dumps(doc):
        problems.append("prompt_gallery.json: not in canonical format (run --write)")
    if doc.get("format") != "ayuvo-coach-prompt-gallery" or doc.get("version") != 1:
        problems.append("prompt_gallery.json: bad format/version")
    categories = doc.get("categories") or []
    if len(set(categories)) != len(categories):
        problems.append("prompt_gallery.json: duplicate category")
    prompts = doc.get("prompts") or []
    seen = set()
    for entry in prompts:
        where = "prompt_gallery.json/%s" % entry.get("id")
        if set(entry) != {"id", "category", "order", "requires", "icon", "title_en", "prompt_en"}:
            problems.append("%s: keys %s" % (where, sorted(entry)))
            continue
        if entry["id"] in seen:
            problems.append("%s: duplicate id" % where)
        seen.add(entry["id"])
        if entry["category"] not in categories:
            problems.append("%s: unknown category %r" % (where, entry["category"]))
        unknown = [s for s in entry["requires"] if s not in R.SOURCES]
        if unknown:
            problems.append("%s: requires unknown source(s) %s" % (where, unknown))
        if not entry["requires"]:
            problems.append("%s: requires must name at least one source" % where)
        if sorted(entry["requires"]) != entry["requires"]:
            problems.append("%s: requires must be sorted" % where)
        for key in ("title_en", "prompt_en"):
            if not entry[key].strip() or entry[key] != R.collapse_ws(entry[key]):
                problems.append("%s: %s must be one collapsed line" % (where, key))
        if R.cp_len(entry["title_en"]) > 48:
            problems.append("%s: title_en longer than 48 code points" % where)
    for category in categories:
        orders = [e["order"] for e in prompts if e.get("category") == category]
        if len(set(orders)) != len(orders):
            problems.append("prompt_gallery.json: duplicate order inside category %r" % category)
        if not orders:
            problems.append("prompt_gallery.json: category %r has no prompts" % category)
    # Every prompt must be reachable: some combination of sources shows it.
    everything = dict((s, True) for s in R.SOURCES)
    shown = set()
    for group in R.gallery_for(everything)["categories"]:
        shown.update(group["ids"])
    for entry in prompts:
        if entry.get("id") not in shown:
            problems.append("prompt_gallery.json/%s: never offered" % entry.get("id"))
    return len(prompts)


def check_medication_tools(problems):
    raw = open(MEDS_TOOLS, encoding="utf-8").read()
    doc = json.loads(raw)
    if doc.get("format") != "ayuvo-medications-coach-tools" or doc.get("version") != 1:
        problems.append("medications/coach_tools.json: bad format/version")
    names = [t.get("name") for t in doc.get("tools") or []]
    if names != list(R.MEDICATION_TOOLS):
        problems.append("medications/coach_tools.json: tools %s != reference %s"
                        % (names, list(R.MEDICATION_TOOLS)))
    for tool in doc.get("tools") or []:
        where = "medications/coach_tools.json/%s" % tool.get("name")
        if set(tool) != {"name", "description", "input_schema"}:
            problems.append("%s: keys %s" % (where, sorted(tool)))
            continue
        if not tool["description"].strip():
            problems.append("%s: empty description" % where)
        schema = tool["input_schema"]
        if not isinstance(schema, dict) or schema.get("type") != "object":
            problems.append("%s: input_schema must be an object schema" % where)
            continue
        compact = json.dumps(schema, ensure_ascii=False, separators=(",", ":"))
        if compact not in raw:
            problems.append("%s: input_schema is not stored compactly on one line" % where)
        for key in schema.get("required") or []:
            if key not in (schema.get("properties") or {}):
                problems.append("%s: required %r is not a property" % (where, key))
    prompt = doc.get("prompt") or {}
    words = prompt.get("mentions_words")
    # Both ports read this list, so "did the user ask about medicines?" cannot drift between them.
    if not isinstance(words, list) or not words:
        problems.append("medications/coach_tools.json: prompt.mentions_words missing")
    else:
        if words != sorted(set(words), key=words.index) or len(set(words)) != len(words):
            problems.append("medications/coach_tools.json: prompt.mentions_words has duplicates")
        for word in words:
            if not isinstance(word, str) or word != word.lower().strip() or " " in word:
                problems.append("medications/coach_tools.json: bad mentions word %r" % (word,))
    for key in ("available_line", "not_available_line", "guardrails"):
        if not isinstance(prompt.get(key), str) or not prompt[key].strip():
            problems.append("medications/coach_tools.json: prompt.%s missing" % key)
    for key, allowed in (("available_line", {"n", "active"}),):
        found = set(_RE_PLACEHOLDER.findall(prompt.get(key, "")))
        if found - allowed:
            problems.append("medications/coach_tools.json: prompt.%s has unknown placeholders %s"
                            % (key, sorted(found - allowed)))
    guardrails = prompt.get("guardrails", "")
    # The dosing rule is the whole reason Coach may read this data at all.
    if "Never tell the user to start, stop, change" not in guardrails:
        problems.append("medications/coach_tools.json: guardrails lost the no-dosing-advice rule")
    errors = doc.get("errors") or {}
    if sorted(errors) != ["bad_date", "date_order", "unavailable", "unknown_medication"]:
        problems.append("medications/coach_tools.json: errors %s" % sorted(errors))
    return len(names)


def check_copies(problems):
    """Each platform ships a byte-identical copy of every shared catalog."""
    copied = 0
    for source, targets in COPIES.items():
        raw = open(source, encoding="utf-8").read()
        for relative in targets:
            path = os.path.join(ROOT, relative)
            if not os.path.exists(path):
                problems.append("%s missing (copy of %s)" % (relative, os.path.relpath(source, ROOT)))
            elif open(path, encoding="utf-8").read() != raw:
                problems.append("%s differs from %s" % (relative, os.path.relpath(source, ROOT)))
            else:
                copied += 1
    return copied


def check_schema(problems):
    if not os.path.exists(SCHEMA):
        problems.append("shared/coach/schema.sql missing")
        return 0, 0
    stmts = statements(SCHEMA)
    tables = [re.sub("^CREATE (?:VIRTUAL )?TABLE ([a-z_]+).*$", "\\1", s, flags=re.S)
              for s in stmts if s.startswith("CREATE TABLE") or s.startswith("CREATE VIRTUAL TABLE")]
    indexes = [re.sub("^CREATE INDEX ([a-z_]+).*$", "\\1", s, flags=re.S)
               for s in stmts if s.startswith("CREATE INDEX")]
    if tables != TABLES:
        problems.append("schema.sql: tables %s != documented %s" % (tables, TABLES))
    if indexes != INDEXES:
        problems.append("schema.sql: indexes %s != documented %s" % (indexes, INDEXES))
    joined = "\n".join(stmts)
    for column in ("deleted", "updated_ms", "created_ms"):
        if column not in joined:
            problems.append("schema.sql: missing the %r column convention" % column)
    return len(tables), len(indexes)


# ---------------------------------------------------------------------------------------------
# Output shapes
# ---------------------------------------------------------------------------------------------

def check_shape(function, got, where, problems, inp):
    if function == "parse_blocks":
        for block in got["blocks"]:
            kind = block.get("kind")
            if kind not in R.BLOCK_KINDS:
                problems.append("%s: unknown block kind %r" % (where, kind))
                continue
            if kind == "heading" and not 1 <= block["level"] <= R.MAX_HEADING_LEVEL:
                problems.append("%s: heading level %r" % (where, block["level"]))
            if kind in ("bullet", "numbered", "task") and not 0 <= block["depth"] <= R.MAX_LIST_DEPTH:
                problems.append("%s: list depth %r" % (where, block["depth"]))
            if kind == "quote" and not 1 <= block["depth"] <= R.MAX_QUOTE_DEPTH:
                problems.append("%s: quote depth %r" % (where, block["depth"]))
            if kind == "table":
                if len(block["aligns"]) != len(block["headers"]):
                    problems.append("%s: table aligns/headers length" % where)
                for row in block["rows"]:
                    if len(row) != len(block["headers"]):
                        problems.append("%s: ragged table row" % where)
            if kind == "chart":
                if block["ok"] and "spec" not in block:
                    problems.append("%s: chart ok without a spec" % where)
                if not block["ok"] and block.get("reason") not in R.CHART_REASONS:
                    problems.append("%s: unknown chart reason %r" % (where, block.get("reason")))
    elif function == "parse_chart_spec":
        if got["ok"]:
            spec = got["spec"]
            if set(spec) != {"type", "title", "unit", "x_label", "y_label", "note", "max", "series",
                             "dropped"}:
                problems.append("%s: spec keys %s" % (where, sorted(spec)))
            if not isinstance(spec["dropped"], int) or spec["dropped"] < 0:
                problems.append("%s: dropped %r" % (where, spec["dropped"]))
            if spec["type"] not in R.CHART_TYPES:
                problems.append("%s: type %r" % (where, spec["type"]))
            if not 1 <= len(spec["series"]) <= R.MAX_SERIES:
                problems.append("%s: series count %d" % (where, len(spec["series"])))
            for entry in spec["series"]:
                if not 1 <= len(entry["points"]) <= R.MAX_POINTS:
                    problems.append("%s: point count %d" % (where, len(entry["points"])))
                for point in entry["points"]:
                    if not R._is_finite_number(point["y"]):
                        problems.append("%s: non-finite y" % where)
                    if R.cp_len(point["x"]) > R.MAX_LABEL_CHARS:
                        problems.append("%s: label longer than the cap" % where)
                    if spec["type"] in R.RANGE_TYPES and "y2" not in point:
                        problems.append("%s: range point without y2" % where)
        elif got.get("reason") not in R.CHART_REASONS:
            problems.append("%s: unknown reason %r" % (where, got.get("reason")))
    elif function == "repair_chart_json":
        if not isinstance(got.get("text"), str):
            problems.append("%s: repair must return text" % where)
        elif R.repair_chart_json(got["text"]) != got["text"]:
            problems.append("%s: repair is not idempotent" % where)
    elif function == "attachment_excerpt":
        text = got["text"] or ""
        if got["chars"] != R.cp_len(text):
            problems.append("%s: chars != len(text)" % where)
        if got["chars"] > inp.get("max_chars", R.MAX_ATTACHMENT_CHARS):
            problems.append("%s: over the character cap" % where)
        if got["pages_used"] > got["pages_total"]:
            problems.append("%s: pages_used > pages_total" % where)
        for line in text.split("\n"):
            if line.startswith("--- page "):
                continue
            if R._pii_line(R.fold(line), []):
                problems.append("%s: a redacted line survived: %r" % (where, line[:40]))
    elif function == "conversation_title":
        title = got["title"]
        if not isinstance(title, str):
            problems.append("%s: title is not a string" % where)
        elif R.cp_len(title) > R.MAX_TITLE_LENGTH + 1:
            problems.append("%s: title longer than the cap" % where)
        elif title != title.strip(" "):
            problems.append("%s: title is not trimmed" % where)
    elif function == "resolve_data_sources":
        if sorted(got["sources"]) != sorted(R.SOURCES):
            problems.append("%s: sources %s" % (where, sorted(got["sources"])))
        if len(set(got["tools"])) != len(got["tools"]):
            problems.append("%s: duplicate tool" % where)
        for name in got["tools"]:
            if name not in ALL_TOOLS:
                problems.append("%s: unknown tool %r" % (where, name))
        for entry in got["blocked"]:
            if entry["reason"] not in ("unavailable", "not_consented", "switched_off"):
                problems.append("%s: unknown block reason %r" % (where, entry["reason"]))
    elif function == "gallery_for":
        ids = [i for group in got["categories"] for i in group["ids"]]
        if len(set(ids)) != len(ids):
            problems.append("%s: duplicate gallery id" % where)
        if got["count"] != len(ids):
            problems.append("%s: count != ids" % where)
    elif function == "export":
        if "text" in got:
            if not got["filename"].endswith(".md"):
                problems.append("%s: markdown filename %r" % (where, got["filename"]))
            if not got["text"].endswith("\n"):
                problems.append("%s: transcript must end with a newline" % where)
            # An attachment's contents never go into a transcript; only its name (§10).
            for attachment in inp.get("attachments") or []:
                excerpt = attachment.get("excerpt")
                if excerpt and excerpt in got["text"]:
                    problems.append("%s: an attachment excerpt leaked into the transcript" % where)
        elif "archive" in got:
            if not got["filename"].endswith(".json"):
                problems.append("%s: json filename %r" % (where, got["filename"]))
            ids = set(c["id"] for c in got["archive"]["conversations"])
            for message in got["archive"]["messages"]:
                if message["conversation_id"] not in ids:
                    problems.append("%s: a foreign conversation's message was exported" % where)
        elif "slug" in got:
            if not got["slug"] or R.cp_len(got["slug"]) > R.MAX_SLUG_CHARS:
                problems.append("%s: bad slug %r" % (where, got["slug"]))
        elif got.get("ok"):
            if got["variant_index"] < 1:
                problems.append("%s: a regenerated reply must not reuse variant 0" % where)
            if got["prompt_seq"] >= got["seq"]:
                problems.append("%s: the prompt must come before the reply" % where)
        elif got.get("reason") not in ("not_a_reply", "no_prompt"):
            problems.append("%s: unknown regenerate reason %r" % (where, got.get("reason")))
    elif function == "chat_archive":
        if "snapshot" in got:
            if got["error"] not in (None, "bad_format", "newer_version"):
                problems.append("%s: unknown merge error %r" % (where, got["error"]))
            for key, value in got["counts"].items():
                if value < 0:
                    problems.append("%s: negative count %s" % (where, key))
        else:
            if got["format"] != R.ARCHIVE_FORMAT or got["format_version"] != R.ARCHIVE_VERSION:
                problems.append("%s: bad archive envelope" % where)
            for row in got["conversations"] + got["messages"] + got["attachments"]:
                if "deleted" in row:
                    problems.append("%s: a tombstone reached the archive" % where)
            known = set(c["id"] for c in got["conversations"])
            for message in got["messages"]:
                if message["conversation_id"] not in known:
                    problems.append("%s: orphan message in the archive" % where)
                if message["role"] not in R.MESSAGE_ROLES:
                    problems.append("%s: unknown role %r" % (where, message["role"]))


# ---------------------------------------------------------------------------------------------
# Vectors
# ---------------------------------------------------------------------------------------------

def check_vectors(write, problems):
    counts = {}
    present = sorted(os.path.basename(p) for p in glob.glob(os.path.join(VECTORS, "*.json")))
    for name in sorted(EXPECTED_FILES):
        if name not in present:
            problems.append("test-vectors/%s missing" % name)
    for name in present:
        if name not in EXPECTED_FILES:
            problems.append("test-vectors/%s: unknown vector file (add it to EXPECTED_FILES)" % name)
            continue
        path = os.path.join(VECTORS, name)
        raw = open(path, encoding="utf-8").read()
        try:
            doc = json.loads(raw)
        except ValueError as e:
            problems.append("%s: invalid JSON: %s" % (name, e))
            continue
        if (doc.get("format") != FORMAT or doc.get("version") != 1
                or set(doc) != {"format", "version", "function", "cases"}
                or doc.get("function") != EXPECTED_FILES[name] or not isinstance(doc.get("cases"), list)):
            problems.append("%s: bad envelope" % name)
            continue
        seen = set()
        changed = False
        for c in doc["cases"]:
            if set(c) - {"name", "input", "expected", "notes"} or not isinstance(c.get("input"), dict) or not c.get("name"):
                problems.append("%s: bad case keys %s" % (name, sorted(c)))
                continue
            if c["name"] in seen:
                problems.append("%s: duplicate case %s" % (name, c["name"]))
            seen.add(c["name"])
            where = "%s/%s" % (name, c["name"])
            try:
                got = json.loads(json.dumps(R.run_case(doc["function"], c["input"]), ensure_ascii=False))
            except Exception as e:  # noqa: BLE001 - report, don't crash the whole check
                problems.append("%s: reference raised %s: %s" % (where, type(e).__name__, e))
                continue
            if write:
                if c.get("expected") != got:
                    c["expected"] = got
                    changed = True
            elif c.get("expected") != got:
                problems.append("%s: expected differs from the reference at %s"
                                % (where, _first_diff(c.get("expected"), got)))
            check_shape(doc["function"], got, where, problems, c["input"])
        counts[name] = len(doc["cases"])
        if write and (changed or raw != dumps(doc)):
            with open(path, "w", encoding="utf-8") as fh:
                fh.write(dumps(doc))
        elif not write and raw != dumps(doc):
            problems.append("%s: not in canonical format (run --write)" % name)
    return counts


def check_constants(problems):
    for name, value in DOCUMENTED.items():
        if getattr(R, name, None) != value:
            problems.append("reference %s = %r, documented %r" % (name, getattr(R, name, None), value))


def check_regexes(problems):
    for name in dir(R):
        if not name.startswith("_RE_"):
            continue
        pattern = getattr(R, name)
        if not hasattr(pattern, "pattern"):
            continue
        for issue in lint_pattern(pattern.pattern, True):
            problems.append("reference %s: %s" % (name, issue))


def check_round_trip(problems):
    """Export -> merge into an empty store -> export again must be identical, and a local tombstone
    must survive the merge (a deleted conversation is never resurrected)."""
    snapshot = {
        "conversations": [
            {"id": "c1", "title": "Sleep", "created_ms": 10, "updated_ms": 30, "last_message_ms": 30,
             "pinned": 0, "archived": 0, "data_sources": {"health": True}, "selected_record_ids": [],
             "provider_override": None, "deleted": 0},
            {"id": "c2", "title": "Gone", "created_ms": 5, "updated_ms": 6, "last_message_ms": 6,
             "pinned": 0, "archived": 0, "data_sources": {}, "selected_record_ids": [],
             "provider_override": None, "deleted": 1},
        ],
        "messages": [
            {"id": "m1", "conversation_id": "c1", "seq": 1, "role": "user", "content": "how did I sleep?",
             "created_ms": 20, "updated_ms": 20, "regenerated_from": None, "variant_index": 0,
             "record_refs": [], "attachment_ids": ["a1"], "deleted": 0},
            {"id": "m2", "conversation_id": "c1", "seq": 2, "role": "assistant", "content": "Seven hours.",
             "created_ms": 30, "updated_ms": 30, "regenerated_from": None, "variant_index": 0,
             "record_refs": [], "attachment_ids": [], "deleted": 0},
        ],
        "attachments": [
            {"id": "a1", "kind": "pdf", "filename": "report.pdf", "mime_type": "application/pdf",
             "bytes": 1024, "sha256": "ab", "page_count": 2, "char_count": 40, "excerpt": "x",
             "created_ms": 15, "deleted": 0},
            {"id": "a9", "kind": "image", "filename": "orphan.jpg", "mime_type": "image/jpeg",
             "bytes": 10, "sha256": "cd", "page_count": None, "char_count": None, "excerpt": None,
             "created_ms": 1, "deleted": 0},
        ],
    }
    archive = R.chat_archive(snapshot)
    if [c["id"] for c in archive["conversations"]] != ["c1"]:
        problems.append("round trip: a tombstoned conversation was exported")
    if [a["id"] for a in archive["attachments"]] != ["a1"]:
        problems.append("round trip: an unreferenced attachment was exported")

    empty = {"conversations": [], "messages": [], "attachments": []}
    merged = R.merge_chat_archive(empty, archive)
    if merged["error"] is not None:
        problems.append("round trip: merge into an empty store failed: %s" % merged["error"])
    again = R.chat_archive(merged["snapshot"])
    if json.dumps(again, sort_keys=True) != json.dumps(archive, sort_keys=True):
        problems.append("round trip: re-export differs at %s" % _first_diff(archive, again))

    # A local tombstone beats the incoming row.
    local = {"conversations": [dict(snapshot["conversations"][0], deleted=1, updated_ms=1)],
             "messages": [], "attachments": []}
    second = R.merge_chat_archive(local, archive)
    if second["counts"]["conversations_skipped_tombstoned"] != 1:
        problems.append("round trip: a local tombstone did not win")
    if any(not c.get("deleted") for c in second["snapshot"]["conversations"]):
        problems.append("round trip: a deleted conversation was resurrected")

    # A second import of the same archive changes nothing.
    third = R.merge_chat_archive(merged["snapshot"], archive)
    if third["counts"]["conversations_inserted"] or third["counts"]["messages_inserted"]:
        problems.append("round trip: re-importing the same archive duplicated rows")


# ---------------------------------------------------------------------------------------------
# Hand-computed expectations
# ---------------------------------------------------------------------------------------------

class ReferenceTests(unittest.TestCase):
    def test_table_with_a_short_row_is_padded(self):
        blocks = R.parse_blocks("| a | b |\n| --- | ---: |\n| 1 |\n")["blocks"]
        self.assertEqual(blocks[0]["aligns"], ["left", "right"])
        self.assertEqual(blocks[0]["rows"], [["1", ""]])

    def test_nested_bullets_take_a_depth(self):
        blocks = R.parse_blocks("- one\n  - two\n    - three\n")["blocks"]
        self.assertEqual([b["depth"] for b in blocks], [0, 1, 2])

    def test_paragraph_lines_join_with_one_space(self):
        blocks = R.parse_blocks("hello\nthere\n\nnext")["blocks"]
        self.assertEqual([b["text"] for b in blocks], ["hello there", "next"])

    def test_unterminated_fence_runs_to_the_end(self):
        blocks = R.parse_blocks("```swift\nlet a = 1\n")["blocks"]
        self.assertEqual(blocks, [{"kind": "code", "lang": "swift", "text": "let a = 1"}])

    def test_task_items(self):
        blocks = R.parse_blocks("- [x] done\n- [ ] todo")["blocks"]
        self.assertEqual([(b["kind"], b["checked"]) for b in blocks], [("task", True), ("task", False)])

    def test_chart_block_parses_inside_markdown(self):
        text = "Here it is.\n\n```ayuvo-chart\n{\"type\":\"bar\",\"series\":[{\"points\":[[\"Mon\",6]]}]}\n```\n"
        blocks = R.parse_blocks(text)["blocks"]
        self.assertEqual(blocks[1]["kind"], "chart")
        self.assertTrue(blocks[1]["ok"])
        self.assertEqual(blocks[1]["spec"]["series"][0]["points"], [{"x": "Mon", "y": 6}])

    def test_every_chart_reason_has_a_wording_group(self):
        for reason in R.CHART_REASONS:
            self.assertIn(R.chart_reason_group(reason), R.CHART_REASON_GROUP_NAMES, reason)
        self.assertEqual(sorted(R.CHART_REASON_GROUPS), sorted(R.CHART_REASONS))

    def test_nan_is_a_missing_reading(self):
        # NaN means "no reading": the point is left out, and with nothing left there is no chart.
        self.assertEqual(R.parse_chart_spec('{"type":"bar","series":[{"points":[["a",NaN]]}]}'),
                         {"ok": False, "reason": "no_points"})
        got = R.parse_chart_spec('{"type":"bar","series":[{"points":[["a",NaN],["b",2]]}]}')
        self.assertEqual(got["spec"]["series"][0]["points"], [{"x": "b", "y": 2}])

    def test_repairs_do_not_reach_inside_a_string(self):
        raw = '{"type":"bar","title":"a // b, NaN","series":[{"points":[["a",1]]},]}'
        self.assertEqual(R.parse_chart_spec(raw)["spec"]["title"], "a // b, NaN")

    def test_an_ambiguous_spec_is_still_refused(self):
        for raw in ("{'type':'bar','series':[{'points':[['a',1]]}]}",
                    '{type:"bar","series":[{"points":[["a",1]]}]}'):
            self.assertEqual(R.parse_chart_spec(raw), {"ok": False, "reason": "invalid_json"})

    def test_a_number_too_wide_for_a_port_is_not_a_reading(self):
        # Swift's Int and Kotlin's Long both stop at 64 bits; the reference stops there too.
        self.assertEqual(R.strict_json("9223372036854775808"), (False, None))
        self.assertEqual(R.strict_json("9223372036854775807"), (True, 9223372036854775807))
        self.assertEqual(R.repair_chart_json('{"y":9223372036854775808}'), '{"y":null}')

    def test_an_unreadable_number_drops_its_point_and_is_counted(self):
        raw = ('{"type":"bar","series":[{"points":[["Mon",5.3],["Tue",7.1.0],["Wed",5.4]]}]}')
        spec = R.parse_chart_spec(raw)["spec"]
        self.assertEqual([p["x"] for p in spec["series"][0]["points"]], ["Mon", "Wed"])
        self.assertEqual([p["y"] for p in spec["series"][0]["points"]], [5.3, 5.4])
        self.assertEqual(spec["dropped"], 1)

    def test_a_truncated_body_is_completed_but_never_filled_in(self):
        got = R.parse_chart_spec('{"type":"bar","series":[{"points":[["Mon",6.2]')
        self.assertEqual(got["spec"]["series"][0]["points"], [{"x": "Mon", "y": 6.2}])
        # Cut inside a string: completing it would invent the label.
        self.assertEqual(R.parse_chart_spec('{"type":"bar","title":"Sleep last'),
                         {"ok": False, "reason": "invalid_json"})

    def test_too_many_points(self):
        points = json.dumps([[str(i), i] for i in range(61)])
        got = R.parse_chart_spec('{"type":"bar","series":[{"points":%s}]}' % points)
        self.assertEqual(got, {"ok": False, "reason": "too_many_points"})

    def test_range_needs_two_values(self):
        self.assertEqual(R.parse_chart_spec('{"type":"range","series":[{"points":[["a",1]]}]}'),
                         {"ok": False, "reason": "missing_y2"})

    def test_pie_takes_one_series(self):
        raw = '{"type":"pie","series":[{"points":[["a",1]]},{"points":[["b",2]]}]}'
        self.assertEqual(R.parse_chart_spec(raw), {"ok": False, "reason": "too_many_series_for_type"})

    def test_excerpt_drops_identity_lines(self):
        got = R.attachment_excerpt(["Patient Name: Ravi Kumar\nPhone: 98765 43210\nHemoglobin 11.2 g/dL"])
        self.assertEqual(got["text"], "Hemoglobin 11.2 g/dL")
        self.assertEqual(got["redacted_lines"], 2)

    def test_excerpt_page_markers_only_when_several_pages(self):
        one = R.attachment_excerpt(["only"])
        self.assertEqual(one["text"], "only")
        two = R.attachment_excerpt(["first", "second"])
        self.assertEqual(two["text"], "--- page 1 ---\nfirst\n\n--- page 2 ---\nsecond")

    def test_excerpt_page_cap(self):
        got = R.attachment_excerpt(["p%d" % i for i in range(25)], max_pages=20)
        self.assertEqual((got["pages_total"], got["pages_used"], got["pages_skipped"]), (25, 20, 5))
        self.assertTrue(got["truncated"])

    def test_title_takes_the_first_sentence(self):
        self.assertEqual(R.conversation_title("How did I sleep this week? Also my steps.")["title"],
                         "How did I sleep this week")

    def test_title_strips_markdown_and_caps(self):
        got = R.conversation_title("## **Compare** my last two blood reports and explain every value")
        self.assertEqual(got["title"], "Compare my last two blood reports and explain…")
        self.assertLessEqual(R.cp_len(got["title"]), R.MAX_TITLE_LENGTH + 1)

    def test_empty_title(self):
        self.assertEqual(R.conversation_title("   \n\t ")["title"], "")

    def test_switch_cannot_grant_consent(self):
        got = R.resolve_data_sources({"health": True}, {"health": False}, {"health": True})
        self.assertFalse(got["sources"]["health"])
        self.assertEqual([b for b in got["blocked"] if b["source"] == "health"],
                         [{"source": "health", "reason": "not_consented"}])

    def test_food_carries_workout_tools_only_when_available(self):
        without = R.resolve_data_sources({"food": True}, {}, {}, False)["tools"]
        with_them = R.resolve_data_sources({"food": True}, {}, {}, True)["tools"]
        self.assertEqual(without, list(R.FOOD_TOOLS))
        self.assertEqual(with_them, list(R.FOOD_TOOLS) + list(R.WORKOUT_TOOLS))

    def test_gallery_hides_what_is_not_connected(self):
        only_food = R.gallery_for({"food": True})
        ids = [i for g in only_food["categories"] for i in g["ids"]]
        self.assertIn("dinner_tonight", ids)
        self.assertNotIn("explain_latest_report", ids)
        self.assertNotIn("improve_sleep_cycle", ids)


def canonical(value):
    """Compact JSON with sorted keys — the archive's entry format (§11), matched by both ports."""
    return json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=False)


def build_fixture_archive():
    """The fixture chat archive as an ordered {entry name: bytes}, exactly as §11 orders it."""
    archive = R.chat_archive(FIXTURE_SNAPSHOT)
    counts = dict((table, len(archive[table])) for table in ("conversations", "messages", "attachments"))
    manifest = dict(FIXTURE_MANIFEST)
    manifest.update({"format": R.ARCHIVE_FORMAT, "format_version": R.ARCHIVE_VERSION, "app": "Ayuvo",
                     "counts": counts})
    entries = {}
    entries["manifest.json"] = (canonical(manifest) + "\n").encode("utf-8")
    for name, table in (("conversations.ndjson", "conversations"), ("messages.ndjson", "messages"),
                        ("attachments.ndjson", "attachments")):
        entries[name] = "".join(canonical(row) + "\n" for row in archive[table]).encode("utf-8")
    exported = set(row["id"] for row in archive["attachments"])
    for attachment_id, (filename, data) in sorted(FIXTURE_BLOBS.items()):
        if attachment_id in exported:
            entries["attachments/%s/%s" % (attachment_id, filename)] = data
    entries["checksums.json"] = (canonical(
        dict((name, hashlib.sha256(data).hexdigest()) for name, data in entries.items())
    ) + "\n").encode("utf-8")
    return entries


def check_fixture_archive(write, problems):
    """Validate (or rebuild with --write) the fixture both platforms read back (§11): entry order,
    entry bytes, checksums, and that merging it into an empty store gives the documented counts."""
    wanted = build_fixture_archive()
    if write:
        write_fixture_archive(wanted, FIXTURE_ARCHIVE)
    if not os.path.exists(FIXTURE_ARCHIVE):
        problems.append("fixtures/ayuvo-coach-chats-fixture.zip is missing (run --write)")
        return 0
    size = os.path.getsize(FIXTURE_ARCHIVE)
    if size > FIXTURE_MAX_BYTES:
        problems.append("fixture archive is %d bytes (cap %d)" % (size, FIXTURE_MAX_BYTES))
    with zipfile.ZipFile(FIXTURE_ARCHIVE) as zf:
        names = zf.namelist()
        got = dict((name, zf.read(name)) for name in names)
    if names != list(wanted):
        problems.append("fixture archive: entry order %s, expected %s" % (names, list(wanted)))
        return len(names)
    for name in names:
        if got[name] != wanted[name]:
            problems.append("fixture archive: %s differs (run --write)" % name)

    # The tombstone and the attachment nothing references never leave the device.
    text = got["conversations.ndjson"].decode("utf-8")
    if "conv-gone-0003" in text:
        problems.append("fixture archive: a tombstoned conversation was exported")
    if "att-0002" in got["attachments.ndjson"].decode("utf-8"):
        problems.append("fixture archive: an unreferenced attachment was exported")

    archive = {"format": R.ARCHIVE_FORMAT, "format_version": R.ARCHIVE_VERSION}
    for name, table in (("conversations.ndjson", "conversations"), ("messages.ndjson", "messages"),
                        ("attachments.ndjson", "attachments")):
        archive[table] = [json.loads(line) for line in got[name].decode("utf-8").splitlines() if line]
    merged = R.merge_chat_archive({"conversations": [], "messages": [], "attachments": []}, archive)
    if merged["error"] is not None:
        problems.append("fixture archive: merging it reported %r" % merged["error"])
    expected = {"conversations_inserted": 2, "messages_inserted": 3, "attachments_inserted": 1}
    for key, value in expected.items():
        if merged["counts"].get(key) != value:
            problems.append("fixture archive: %s is %r, expected %r" % (key, merged["counts"].get(key), value))
    return len(names)


def main(argv):
    write = "--write" in argv
    problems = []
    n_types = check_chart_spec(problems)
    n_prompts = check_gallery(problems)
    n_tools = check_medication_tools(problems)
    n_tables, n_indexes = check_schema(problems)
    n_copies = check_copies(problems)
    check_constants(problems)
    check_regexes(problems)
    check_round_trip(problems)
    counts = check_vectors(write, problems)
    n_fixture = check_fixture_archive(write, problems)
    suite = unittest.defaultTestLoader.loadTestsFromTestCase(ReferenceTests)
    result = unittest.TextTestRunner(stream=open(os.devnull, "w"), verbosity=0).run(suite)
    for failed, trace in result.failures + result.errors:
        problems.append("unittest %s failed:\n%s" % (failed.id(), trace.strip().splitlines()[-1]))
    print("%-45s %3d chart types" % ("chart_spec.json", n_types))
    print("%-45s %3d prompts" % ("prompt_gallery.json", n_prompts))
    print("%-45s %3d tools" % ("medications/coach_tools.json", n_tools))
    print("%-45s %3d tables, %d indexes" % ("schema.sql", n_tables, n_indexes))
    print("%-45s %3d bundled copies verified" % ("platform catalogs", n_copies))
    for name, n in sorted(counts.items()):
        print("%-45s %3d cases" % ("test-vectors/" + name, n))
    print("%-45s %3d entries" % ("fixtures/ayuvo-coach-chats-fixture.zip", n_fixture))
    print("%d unit tests" % result.testsRun)
    if problems:
        print("\n%d problem(s):" % len(problems))
        for p in problems:
            print(" - " + p)
        return 1
    print("OK")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
