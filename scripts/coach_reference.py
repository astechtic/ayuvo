#!/usr/bin/env python3
"""Ayuvo Coach: executable reference for the shared chat logic.

Contract: docs/coach.md. Where this file and the prose disagree, this file wins and the prose is
fixed. Android (Kotlin, `coach/`) and iOS (Swift, `Coach/`) port it line by line and run
shared/coach/test-vectors/*.json in their unit tests.

Portability rules (same as scripts/metrics_reference.py and scripts/medications_reference.py):
  * Python 3 stdlib only. Every function is pure: no clock, locale or randomness. "Now" is always an
    explicit input. The only I/O is reading the two catalogs once at import
    (shared/coach/chart_spec.json, shared/coach/prompt_gallery.json); ports load the same data from
    their bundled copies.
  * Regexes use only literals, explicit ASCII classes, (?:...), numbered groups, ? * + {n,m},
    alternation and ^/$ on single-line strings. `_pii_line` is imported from the records reference so
    an attachment is redacted by exactly the same rule as `records_get` text.
  * Numbers compare by value in vectors (13 == 13.0); object key order never matters; array order does.
  * Text is measured in Unicode code points (Swift `String.unicodeScalars`/`count` over Characters is
    NOT the same; ports use code points, i.e. Kotlin `codePointCount`, Swift `unicodeScalars.count`).
  * Markdown parsing is block-level only. Inline emphasis, code spans and links stay with the platform
    renderer (`AttributedString(markdown:)` / `buildAnnotatedString`), so this file never has to model
    them.
"""

import json
import math
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
SHARED = os.path.join(ROOT, "shared", "coach")
CHART_SPEC_PATH = os.path.join(SHARED, "chart_spec.json")
GALLERY_PATH = os.path.join(SHARED, "prompt_gallery.json")

sys.path.insert(0, HERE)
from records_reference import _pii_line, fold  # noqa: E402

# ---------------------------------------------------------------------------------------------
# Constants
# ---------------------------------------------------------------------------------------------

BLOCK_KINDS = ("heading", "paragraph", "bullet", "numbered", "task", "quote", "code", "table",
               "rule", "chart")
MAX_HEADING_LEVEL = 4
MAX_LIST_DEPTH = 3
MAX_QUOTE_DEPTH = 3
TAB_WIDTH = 4
#: Spaces of indentation per list depth level.
INDENT_PER_DEPTH = 2

CHART_FENCE = "ayuvo-chart"
CHART_TYPES = ("bar", "grouped_bar", "stacked_bar", "line", "area", "pie", "scatter", "range",
               "progress")
#: Types whose points carry a second value (`y2`).
RANGE_TYPES = ("range",)
#: Types that render exactly one series.
SINGLE_SERIES_TYPES = ("pie", "progress")
MAX_SERIES = 4
MAX_POINTS = 60
MAX_LABEL_CHARS = 60
MAX_TITLE_CHARS = 120
CHART_REASONS = ("invalid_json", "not_an_object", "unknown_type", "no_series", "too_many_series",
                 "no_points", "too_many_points", "bad_point", "bad_number", "label_too_long",
                 "missing_y2", "bad_max", "too_many_series_for_type")

#: Why a chart could not be read, in the four groups the two apps have wording for. Every reason in
#: CHART_REASONS has a group (scripts/coach_contract_check.py asserts it) so neither renderer can be
#: left with a failure it cannot explain.
CHART_REASON_GROUPS = {
    "invalid_json": "malformed",
    "not_an_object": "malformed",
    "bad_point": "malformed",
    "unknown_type": "unsupported",
    "too_many_series": "too_big",
    "too_many_series_for_type": "too_big",
    "too_many_points": "too_big",
    "label_too_long": "too_big",
    "no_series": "no_readings",
    "no_points": "no_readings",
    "bad_number": "no_readings",
    "missing_y2": "no_readings",
    "bad_max": "no_readings",
}
CHART_REASON_GROUP_NAMES = ("malformed", "unsupported", "too_big", "no_readings")


def chart_reason_group(reason):
    """§5. The wording group for a refusal; an unknown reason reads as malformed."""
    return CHART_REASON_GROUPS.get(reason or "", "malformed")


#: Spellings of a type that mean one of CHART_TYPES. A model that writes "column" or "donut" gets the
#: chart it meant; a type that is not in this map is still refused (`unknown_type`).
CHART_TYPE_ALIASES = {
    "column": "bar", "columns": "bar", "bars": "bar", "vertical_bar": "bar",
    "horizontal_bar": "bar", "grouped": "grouped_bar", "group_bar": "grouped_bar",
    "multi_bar": "grouped_bar", "stacked": "stacked_bar", "stacked_column": "stacked_bar",
    "lines": "line", "spline": "line", "donut": "pie", "doughnut": "pie",
    "scatter_plot": "scatter", "scatterplot": "scatter", "bubble": "scatter",
    "band": "range", "gauge": "progress", "progress_bar": "progress",
}
#: Keys a spec may carry its series, its points and its labels under. The first present key wins.
SERIES_KEYS = ("series", "datasets", "data")
POINT_KEYS = ("points", "data", "values", "y")
SERIES_LABEL_KEYS = ("label", "name", "title")
LABELS_KEYS = ("labels", "categories", "x_labels")
LABEL_KEYS = ("x", "label", "name", "t")
VALUE_KEYS = ("y", "value", "v")
HIGH_KEYS = ("y2", "high", "y_high")
TITLE_KEYS = ("title",)
UNIT_KEYS = ("unit", "units")
X_LABEL_KEYS = ("x_label", "xLabel", "x_axis", "xAxis")
Y_LABEL_KEYS = ("y_label", "yLabel", "y_axis", "yAxis")
NOTE_KEYS = ("note", "subtitle", "caption")
MAX_KEYS = ("max", "target")
#: Non-numbers a model writes where a reading is missing. They become "no reading", never zero.
JSON_NON_NUMBERS = ("-Infinity", "+Infinity", "Infinity", "-infinity", "+infinity", "infinity",
                    "NaN", "nan", "NAN", "undefined")

#: Attachment excerpt caps (docs/coach.md §5).
MAX_ATTACHMENT_PAGES = 20
MAX_ATTACHMENT_CHARS = 20000
MAX_TURN_CHARS = 40000
ATTACHMENT_KINDS = ("image", "pdf", "text", "note")

MAX_TITLE_LENGTH = 48
MIN_SENTENCE_LENGTH = 12

SOURCES = ("food", "health", "medications", "records")
FOOD_TOOLS = ("get_data_summary", "get_weight_history", "get_body_fat_history", "get_calorie_totals",
              "get_food_entries", "get_fasting_history")
WORKOUT_TOOLS = ("get_workout_history", "get_workout_plans", "get_workout_preferences",
                 "get_training_summary", "get_exercise_lift_history")
HEALTH_TOOLS = ("get_health_data_types", "get_health_summary", "get_health_samples",
                "get_sleep_history")
MEDICATION_TOOLS = ("get_medications", "get_dose_history", "get_medication_adherence")
RECORDS_TOOLS = ("records_search", "records_get", "records_observation_series")

MESSAGE_ROLES = ("user", "assistant")
ARCHIVE_FORMAT = "ayuvo-coach-chats"
ARCHIVE_VERSION = 1

_RE_RULE = re.compile("^(?:-{3,}|\\*{3,}|_{3,})$")
_RE_DELIM_CELL = re.compile("^:?-{1,}:?$")
_RE_NUMBERED = re.compile("^([0-9]{1,9})[.)] (.*)$")
_RE_TASK = re.compile("^\\[([ xX])\\] (.*)$")
_RE_MD_LINK = re.compile("\\[([^\\]]*)\\]\\([^)]*\\)")
_RE_MD_MARKS = re.compile("[*_`~]")
_RE_SENTENCE_END = re.compile("[.!?]")
_RE_WS_RUN = re.compile("[ \\t\\r\\n]+")


def _read_json(path):
    with open(path, "r", encoding="utf-8") as handle:
        return json.load(handle)


CHART_SPEC_DOC = _read_json(CHART_SPEC_PATH) if os.path.exists(CHART_SPEC_PATH) else {}
GALLERY_DOC = _read_json(GALLERY_PATH) if os.path.exists(GALLERY_PATH) else {}


# ---------------------------------------------------------------------------------------------
# Small helpers
# ---------------------------------------------------------------------------------------------

def collapse_ws(text):
    """Runs of space/tab/CR/LF collapse to one space; result trimmed."""
    return _RE_WS_RUN.sub(" ", text or "").strip(" ")


def cp_len(text):
    """Length in Unicode code points."""
    return len(text or "")


def cp_cut(text, limit):
    """First `limit` code points."""
    return (text or "")[:limit]


def _expand_tabs(line):
    """Tabs become TAB_WIDTH spaces so indentation is comparable across editors."""
    out = []
    column = 0
    for ch in line:
        if ch == "\t":
            width = TAB_WIDTH - (column % TAB_WIDTH)
            out.append(" " * width)
            column += width
        else:
            out.append(ch)
            column += 1
    return "".join(out)


def _indent_of(line):
    """Leading spaces of a tab-expanded line."""
    count = 0
    for ch in line:
        if ch == " ":
            count += 1
        else:
            break
    return count


def _depth_for(indent):
    return min(MAX_LIST_DEPTH, indent // INDENT_PER_DEPTH)


def _is_finite_number(value):
    """True for a JSON number that is neither bool, NaN nor +/-Infinity."""
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        return False
    return not (math.isnan(value) or math.isinf(value))


def num_text(value):
    """Deterministic number -> string used for coerced chart x labels: integers lose the '.0'."""
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        return ""
    if float(value) == int(value) and abs(float(value)) < 1e15:
        return str(int(value))
    return repr(float(value))


# ---------------------------------------------------------------------------------------------
# §4 parse_blocks
# ---------------------------------------------------------------------------------------------

def _heading_level(trimmed):
    hashes = 0
    for ch in trimmed:
        if ch == "#":
            hashes += 1
        else:
            break
    if hashes < 1 or hashes > MAX_HEADING_LEVEL:
        return None
    rest = trimmed[hashes:]
    if not rest.startswith(" "):
        return None
    return hashes


def _quote_depth(trimmed):
    """Leading '>' markers (each optionally followed by one space). 0 when the line is not a quote."""
    depth = 0
    index = 0
    while index < len(trimmed) and trimmed[index] == ">" and depth < MAX_QUOTE_DEPTH:
        depth += 1
        index += 1
        if index < len(trimmed) and trimmed[index] == " ":
            index += 1
    return depth, trimmed[index:] if depth else trimmed


def _bullet_text(body):
    """Body after a '-', '*' or '+' marker, or None."""
    if len(body) >= 2 and body[0] in "-*+" and body[1] == " ":
        return body[2:].strip(" ")
    return None


def _split_row(line):
    """Table row cells. A leading and a trailing pipe are decoration and are dropped."""
    text = line.strip(" ")
    if text.startswith("|"):
        text = text[1:]
    if text.endswith("|") and not text.endswith("\\|"):
        text = text[:-1]
    return [cell.strip(" ") for cell in text.split("|")]


def _alignments(cells):
    """Delimiter row -> alignments, or None when the row is not a delimiter row."""
    if not cells:
        return None
    aligns = []
    for cell in cells:
        if not _RE_DELIM_CELL.match(cell):
            return None
        left = cell.startswith(":")
        right = cell.endswith(":")
        if left and right:
            aligns.append("center")
        elif right:
            aligns.append("right")
        else:
            aligns.append("left")
    return aligns


def parse_blocks(markdown):
    """§4. Block-level markdown for one assistant message.

    CRLF/CR become LF. Recognised, in this precedence: fenced code (``` — info string `ayuvo-chart`
    makes a chart block), thematic break, ATX heading (1-4 '#'), table (a pipe row followed by a
    delimiter row), block quote ('>' up to depth 3), task item ('- [ ] '), bullet, numbered ('1.' or
    '1)'), otherwise paragraph. Consecutive paragraph lines join with one space; consecutive quote
    lines of the same depth join with one space. A blank line closes the open paragraph or quote.
    List items never merge. An unterminated fence runs to the end of the text. A single trailing
    newline at the end of the message is the document terminator and is not a blank line of content.
    """
    raw = (markdown or "").replace("\r\n", "\n").replace("\r", "\n")
    lines = [_expand_tabs(line) for line in raw.split("\n")]
    if lines and lines[-1] == "":
        lines.pop()  # the document's final newline, not a blank line of content
    blocks = []
    paragraph = []
    quote = []
    quote_depth = 0
    index = 0

    def flush_paragraph():
        if paragraph:
            blocks.append({"kind": "paragraph", "text": " ".join(paragraph)})
            del paragraph[:]

    def flush_quote():
        if quote:
            blocks.append({"kind": "quote", "depth": quote_depth, "text": " ".join(quote)})
            del quote[:]

    def flush_all():
        flush_paragraph()
        flush_quote()

    while index < len(lines):
        line = lines[index]
        trimmed = line.strip(" ")

        if trimmed.startswith("```"):
            flush_all()
            info = trimmed[3:].strip(" ").lower()
            body = []
            index += 1
            while index < len(lines) and not lines[index].strip(" ").startswith("```"):
                body.append(lines[index])
                index += 1
            index += 1  # skip the closing fence (or run past the end)
            text = "\n".join(body)
            if info == CHART_FENCE:
                blocks.append(_chart_block(text))
            else:
                blocks.append({"kind": "code", "lang": info or None, "text": text})
            continue

        if not trimmed:
            flush_all()
            index += 1
            continue

        if _RE_RULE.match(trimmed):
            flush_all()
            blocks.append({"kind": "rule"})
            index += 1
            continue

        level = _heading_level(trimmed)
        if level is not None:
            flush_all()
            blocks.append({"kind": "heading", "level": level, "text": trimmed[level:].strip(" ")})
            index += 1
            continue

        if "|" in trimmed and index + 1 < len(lines):
            aligns = _alignments(_split_row(lines[index + 1]))
            if aligns is not None:
                headers = _split_row(line)
                if len(aligns) == len(headers):
                    flush_all()
                    rows = []
                    index += 2
                    while index < len(lines):
                        row_line = lines[index].strip(" ")
                        if not row_line or "|" not in row_line:
                            break
                        cells = _split_row(lines[index])
                        if len(cells) < len(headers):
                            cells = cells + [""] * (len(headers) - len(cells))
                        rows.append(cells[:len(headers)])
                        index += 1
                    blocks.append({"kind": "table", "headers": headers, "aligns": aligns,
                                   "rows": rows})
                    continue

        depth, body = _quote_depth(trimmed)
        if depth:
            flush_paragraph()
            if quote and depth != quote_depth:
                flush_quote()
            quote_depth = depth
            quote.append(body.strip(" "))
            index += 1
            continue
        flush_quote()

        indent = _indent_of(line)
        bullet = _bullet_text(trimmed)
        if bullet is not None:
            flush_paragraph()
            task = _RE_TASK.match(bullet)
            if task:
                blocks.append({"kind": "task", "depth": _depth_for(indent),
                               "checked": task.group(1) != " ", "text": task.group(2).strip(" ")})
            else:
                blocks.append({"kind": "bullet", "depth": _depth_for(indent), "text": bullet})
            index += 1
            continue

        numbered = _RE_NUMBERED.match(trimmed)
        if numbered:
            flush_paragraph()
            blocks.append({"kind": "numbered", "depth": _depth_for(indent),
                           "marker": numbered.group(1), "text": numbered.group(2).strip(" ")})
            index += 1
            continue

        paragraph.append(trimmed)
        index += 1

    flush_all()
    return {"blocks": blocks}


# ---------------------------------------------------------------------------------------------
# §5 parse_chart_spec
# ---------------------------------------------------------------------------------------------

# ---------------------------------------------------------------------------------------------
# Strict JSON (docs/coach.md §5)
# ---------------------------------------------------------------------------------------------
#
# Platform JSON parsers disagree about what they accept, so a chart spec is never handed to one.
# Apple's JSONSerialization accepts trailing commas; Android's org.json additionally accepts single
# quotes, unquoted keys and comments; Python's json accepts NaN and Infinity. A spec that renders on
# one phone and not another is a parity bug, so all three ports run this scanner instead: RFC 8259
# with no extensions, no NaN/Infinity, no trailing commas, no leading zeros and no raw control
# characters inside strings.

MAX_JSON_CHARS = 20000
MAX_JSON_DEPTH = 32
_JSON_WS = " \t\n\r"
_JSON_ESCAPES = {'"': '"', "\\": "\\", "/": "/", "b": "\b", "f": "\f", "n": "\n", "r": "\r", "t": "\t"}


class _JSONFail(Exception):
    pass


def _json_ws(text, i):
    while i < len(text) and text[i] in _JSON_WS:
        i += 1
    return i


def _json_string(text, i):
    if i >= len(text) or text[i] != '"':
        raise _JSONFail()
    i += 1
    out = []
    while True:
        if i >= len(text):
            raise _JSONFail()
        ch = text[i]
        if ch == '"':
            return "".join(out), i + 1
        if ch == "\\":
            i += 1
            if i >= len(text):
                raise _JSONFail()
            esc = text[i]
            if esc == "u":
                if i + 4 >= len(text):
                    raise _JSONFail()
                digits = text[i + 1:i + 5]
                for d in digits:
                    if d not in "0123456789abcdefABCDEF":
                        raise _JSONFail()
                out.append(chr(int(digits, 16)))
                i += 5
                continue
            if esc not in _JSON_ESCAPES:
                raise _JSONFail()
            out.append(_JSON_ESCAPES[esc])
            i += 1
            continue
        if ord(ch) < 0x20:
            raise _JSONFail()
        out.append(ch)
        i += 1


def _json_number(text, i):
    start = i
    if i < len(text) and text[i] == "-":
        i += 1
    if i >= len(text) or text[i] not in "0123456789":
        raise _JSONFail()
    if text[i] == "0":
        i += 1
    else:
        while i < len(text) and text[i] in "0123456789":
            i += 1
    is_float = False
    if i < len(text) and text[i] == ".":
        is_float = True
        i += 1
        if i >= len(text) or text[i] not in "0123456789":
            raise _JSONFail()
        while i < len(text) and text[i] in "0123456789":
            i += 1
    if i < len(text) and text[i] in "eE":
        is_float = True
        i += 1
        if i < len(text) and text[i] in "+-":
            i += 1
        if i >= len(text) or text[i] not in "0123456789":
            raise _JSONFail()
        while i < len(text) and text[i] in "0123456789":
            i += 1
    raw = text[start:i]
    value = float(raw) if is_float else int(raw)
    if is_float and (math.isnan(value) or math.isinf(value)):
        raise _JSONFail()
    # An integer wider than 64 bits has no portable representation (Swift's Int and Kotlin's Long
    # both refuse it), so all three ports refuse it rather than one of them silently rounding.
    if not is_float and not (-(2 ** 63) <= value <= 2 ** 63 - 1):
        raise _JSONFail()
    return value, i


def _json_value(text, i, depth):
    if depth > MAX_JSON_DEPTH:
        raise _JSONFail()
    if i >= len(text):
        raise _JSONFail()
    ch = text[i]
    if ch == "{":
        out = {}
        i = _json_ws(text, i + 1)
        if i < len(text) and text[i] == "}":
            return out, i + 1
        while True:
            key, i = _json_string(text, i)
            i = _json_ws(text, i)
            if i >= len(text) or text[i] != ":":
                raise _JSONFail()
            value, i = _json_value(text, _json_ws(text, i + 1), depth + 1)
            out[key] = value          # a repeated key keeps the last value, as every JSON reader does
            i = _json_ws(text, i)
            if i < len(text) and text[i] == ",":
                i = _json_ws(text, i + 1)
                continue              # a ',' must be followed by another member, never by '}'
            if i < len(text) and text[i] == "}":
                return out, i + 1
            raise _JSONFail()
    if ch == "[":
        out = []
        i = _json_ws(text, i + 1)
        if i < len(text) and text[i] == "]":
            return out, i + 1
        while True:
            value, i = _json_value(text, i, depth + 1)
            out.append(value)
            i = _json_ws(text, i)
            if i < len(text) and text[i] == ",":
                i = _json_ws(text, i + 1)
                continue
            if i < len(text) and text[i] == "]":
                return out, i + 1
            raise _JSONFail()
    if ch == '"':
        return _json_string(text, i)
    if text.startswith("true", i):
        return True, i + 4
    if text.startswith("false", i):
        return False, i + 5
    if text.startswith("null", i):
        return None, i + 4
    return _json_number(text, i)


def strict_json(text):
    """RFC 8259 with no extensions -> (ok, value). Used for chart specs so all three ports accept
    exactly the same bytes; see the note above."""
    text = text or ""
    if cp_len(text) > MAX_JSON_CHARS:
        return False, None
    try:
        value, i = _json_value(text, _json_ws(text, 0), 0)
    except (_JSONFail, RecursionError, ValueError):
        return False, None
    if _json_ws(text, i) != len(text):
        return False, None
    return True, value


def _skip_ws_comments(text, i):
    """Past whitespace and // or /* */ comments, which a model sometimes leaves in a spec."""
    while i < len(text):
        if text[i] in _JSON_WS:
            i += 1
            continue
        if text.startswith("//", i):
            while i < len(text) and text[i] != "\n":
                i += 1
            continue
        if text.startswith("/*", i):
            i += 2
            while i + 1 < len(text) and not (text[i] == "*" and text[i + 1] == "/"):
                i += 1
            i = min(len(text), i + 2)
            continue
        break
    return i


def _word_boundary(text, i, length):
    """True when text[i:i+length] is not part of a longer bare word."""
    before = text[i - 1] if i > 0 else ""
    after = text[i + length] if i + length < len(text) else ""
    return not (before.isalnum() or before == "_") and not (after.isalnum() or after == "_")


_NUMBER_CHARS = "0123456789+-.eE"


def _number_run(text, i):
    """The maximal run of number characters starting at i (a number token as written)."""
    end = i
    while end < len(text) and text[end] in _NUMBER_CHARS:
        end += 1
    return text[i:end]


def _complete_or_cut(text, start):
    """From the `{` at `start`: the root value alone (trailing prose dropped), or, when the body was
    cut short, the same text with the containers it left open closed.

    Completing a truncated body adds structure, never a value: a body cut off inside a string is left
    as it is, because finishing a label would be putting words in the model's mouth.
    """
    stack = []
    in_string = False
    i = start
    while i < len(text):
        ch = text[i]
        if in_string:
            if ch == "\\":
                i += 2
                continue
            if ch == '"':
                in_string = False
            i += 1
            continue
        if ch == '"':
            in_string = True
            i += 1
            continue
        if ch in "{[":
            stack.append("}" if ch == "{" else "]")
            i += 1
            continue
        if ch in "}]":
            if stack:
                stack.pop()
            i += 1
            if not stack:
                return text[start:i]          # the root value ended here; anything after it is prose
            continue
        i += 1
    if in_string or not stack:
        return text[start:]
    body = text[start:].rstrip(" \t\n")
    while body.endswith(","):                  # a comma the cut left dangling
        body = body[:-1].rstrip(" \t\n")
    return body + "".join(reversed(stack))


def repair_chart_json(raw):
    """§5. The small, deterministic repairs a fence body gets before it is parsed.

    Strictness is about parity, not about punishing the model: a spec is refused when it cannot be
    understood, not when it is punctuated badly. In one left-to-right pass that never touches the
    inside of a string:

    1. `//` and `/* */` comments are dropped.
    2. A comma directly before `}` or `]` is dropped.
    3. `NaN`, `Infinity`, `-Infinity` and `undefined` become `null` -- a reading the model did not
       have. `null` points are left out of the chart (§5), never drawn as zero.
    4. A number token that is not a number (`7.1.0`, `01`, `1.2.3`) becomes `null` for the same
       reason: it cannot be read, and reading it *nearly* -- 7.1 out of "7.1.0" -- would be choosing
       a value for the user. The point is left out and counted in `dropped` instead.

    Then the root object is bounded: prose after it is dropped, and a body that was cut short has the
    containers it left open closed (structure only, never a value).

    Nothing here invents, rounds or moves a number, and nothing rescues a genuinely ambiguous spec:
    single quotes and unquoted keys are still refused, because guessing what they meant could change
    a value.
    """
    text = (raw or "").replace("\r\n", "\n").replace("\r", "\n")
    if cp_len(text) > MAX_JSON_CHARS:
        return text                       # too big to be a spec; strict_json refuses it as it stands
    out = []
    i = 0
    length = len(text)
    while i < length:
        ch = text[i]
        if ch == '"':                     # a string is copied verbatim, escapes and all
            out.append(ch)
            i += 1
            while i < length:
                out.append(text[i])
                if text[i] == "\\" and i + 1 < length:
                    out.append(text[i + 1])
                    i += 2
                    continue
                if text[i] == '"':
                    i += 1
                    break
                i += 1
            continue
        if text.startswith("//", i) or text.startswith("/*", i):
            i = _skip_ws_comments(text, i)
            continue
        if ch == ",":
            nxt = _skip_ws_comments(text, i + 1)
            if nxt < length and text[nxt] in "}]":
                i += 1
                continue
            out.append(ch)
            i += 1
            continue
        word = None
        for candidate in JSON_NON_NUMBERS:
            if text.startswith(candidate, i) and _word_boundary(text, i, len(candidate)):
                word = candidate
                break
        if word is not None:
            out.append("null")
            i += len(word)
            continue
        if ch in "0123456789+-":
            token = _number_run(text, i)
            ok, value = strict_json(token)
            out.append(token if (ok and _is_finite_number(value)) else "null")
            i += len(token)
            # A quote glued to the end of a number (`5.38"`, a model writing the unit as a mark) can
            # never start a string in JSON. Dropping it matters more than it looks: left in, it opens
            # a string that swallows the rest of the line, and every reading after it is lost.
            if i < len(text) and text[i] == '"':
                i += 1
            continue
        out.append(ch)
        i += 1
    repaired = "".join(out)
    start = repaired.find("{")
    if start != -1:
        repaired = _complete_or_cut(repaired, start)
    return repaired.strip(" \t\n")


def _first_key(doc, keys):
    """The value of the first of `keys` the object carries, or None."""
    for key in keys:
        if key in doc:
            return doc[key]
    return None


def _chart_type(value):
    """A written type -> one of CHART_TYPES, or None. Case, spaces, dashes and a "_chart" suffix are
    noise; a spelling that is not a known alias is still refused."""
    if not isinstance(value, str):
        return None
    text = value.strip(" \t\n\r").lower().replace(" ", "_").replace("-", "_")
    if text.endswith("_chart"):
        text = text[:-len("_chart")]
    if text in CHART_TYPES:
        return text
    return CHART_TYPE_ALIASES.get(text)


def _number(value):
    """A finite JSON number, or a string holding exactly one -> the number; otherwise None.

    A model that quotes its numbers ("6.2") still means the reading it took; reading it back is not
    inventing it. A string with a unit in it ("6.2 h") is not a number and is refused.
    """
    if _is_finite_number(value):
        return value
    if isinstance(value, str):
        ok, parsed = strict_json(value.strip(" \t\n\r"))
        if ok and _is_finite_number(parsed):
            return parsed
    return None


def _missing(value):
    """True when a point carries no reading at all, as opposed to an unreadable one."""
    return value is None or (isinstance(value, str) and not value.strip(" \t\n\r"))


def _labels_of(doc):
    """Shared x labels, for the `{"labels": [...], "series": [{"values": [...]}]}` shape."""
    raw = _first_key(doc, LABELS_KEYS)
    if not isinstance(raw, list):
        return []
    out = []
    for item in raw:
        if isinstance(item, str):
            out.append(collapse_ws(item))
        elif _is_finite_number(item):
            out.append(num_text(item))
        else:
            out.append("")
    return out


def _positional_label(labels, index):
    """The shared label for this position, else its 1-based position."""
    if 0 <= index < len(labels) and labels[index]:
        return labels[index]
    return str(index + 1)


def _chart_block(raw):
    parsed = parse_chart_spec(raw)
    if parsed["ok"]:
        return {"kind": "chart", "ok": True, "spec": parsed["spec"]}
    return {"kind": "chart", "ok": False, "reason": parsed["reason"], "text": raw}


def _bad(reason):
    return {"ok": False, "reason": reason}


def _text_or_none(value, limit):
    if not isinstance(value, str):
        return None
    text = collapse_ws(value)
    if not text:
        return None
    return cp_cut(text, limit)


def _point(raw, index, needs_y2, labels):
    """One point -> {x, y, y2}, None when it carries no reading, or a reason string."""
    x_value = None
    y_value = None
    y2_value = None
    if isinstance(raw, list):
        if not raw:
            return "bad_point"
        if len(raw) == 1:
            y_value = raw[0]
        else:
            x_value, y_value = raw[0], raw[1]
            if len(raw) > 2:
                y2_value = raw[2]
    elif isinstance(raw, dict):
        x_value = _first_key(raw, LABEL_KEYS)
        y_value = _first_key(raw, VALUE_KEYS)
        y2_value = _first_key(raw, HIGH_KEYS)
    elif raw is None or isinstance(raw, str) or _is_finite_number(raw):
        y_value = raw
    else:
        return "bad_point"

    if isinstance(x_value, str) and x_value.strip(" \t\n\r"):
        label = collapse_ws(x_value)
    elif _is_finite_number(x_value):
        label = num_text(x_value)
    elif _missing(x_value):
        label = _positional_label(labels, index)
    else:
        return "bad_point"
    if cp_len(label) > MAX_LABEL_CHARS:
        return "label_too_long"

    y_number = _number(y_value)
    if y_number is None:
        if _missing(y_value):
            return None       # no reading for this point: it is left out, never drawn as zero
        return "bad_number"
    point = {"x": label, "y": y_number}
    if needs_y2:
        y2_number = _number(y2_value)
        if y2_number is None:
            return "missing_y2"
        point["y2"] = y2_number
    elif not _missing(y2_value):
        y2_number = _number(y2_value)
        if y2_number is None:
            return "bad_number"
        point["y2"] = y2_number
    return point


def parse_chart_spec(raw):
    """§5. The body of an ```ayuvo-chart fence -> {"ok": true, "spec": ...} or {"ok": false, "reason"}.

    The body is repaired first (`repair_chart_json`), then read strictly. What survives is normalised:
    a known alias for `type`, the first recognised key for the series, the points and the labels, and
    a quoted number read back as a number. What cannot be understood still fails closed -- an unknown
    type, an unreadable value, a malformed point or an over-long label all return a reason and the
    renderer falls back to a plain code block. A point with no reading is left out of the chart; a
    chart is never drawn from a partially understood spec, and a gap is never drawn as zero.
    """
    ok, doc = strict_json(repair_chart_json(raw))
    if not ok:
        return _bad("invalid_json")
    if not isinstance(doc, dict):
        return _bad("not_an_object")

    kind = _chart_type(doc.get("type"))
    if kind is None:
        return _bad("unknown_type")

    raw_series = _first_key(doc, SERIES_KEYS)
    if isinstance(raw_series, dict):
        raw_series = [raw_series]
    if not isinstance(raw_series, list) or not raw_series:
        return _bad("no_series")
    if not any(isinstance(entry, dict) and isinstance(_first_key(entry, POINT_KEYS), list)
               for entry in raw_series):
        # Nothing in the list carries points, so the list is not a list of series: it is either a list
        # of point lists (one series each) or one series' points written bare.
        nested = all(isinstance(entry, list) and entry
                     and all(isinstance(item, (list, dict)) for item in entry)
                     for entry in raw_series)
        raw_series = [{"points": entry} for entry in raw_series] if nested else [{"points": raw_series}]
    if len(raw_series) > MAX_SERIES:
        return _bad("too_many_series")
    if kind in SINGLE_SERIES_TYPES and len(raw_series) > 1:
        return _bad("too_many_series_for_type")

    labels = _labels_of(doc)
    needs_y2 = kind in RANGE_TYPES
    series = []
    dropped = 0
    for position, entry in enumerate(raw_series):
        if not isinstance(entry, dict):
            return _bad("no_series")
        raw_points = _first_key(entry, POINT_KEYS)
        if not isinstance(raw_points, list) or not raw_points:
            return _bad("no_points")
        if len(raw_points) > MAX_POINTS:
            return _bad("too_many_points")
        points = []
        for index, raw_point in enumerate(raw_points):
            point = _point(raw_point, index, needs_y2, labels)
            if isinstance(point, str):
                return _bad(point)
            if point is None:
                dropped += 1  # a reading the model did not have, or wrote unreadably
                continue
            points.append(point)
        if not points:
            continue          # every reading was missing: the series is left out entirely
        label = _text_or_none(_first_key(entry, SERIES_LABEL_KEYS), MAX_LABEL_CHARS)
        series.append({"label": label if label is not None else str(position + 1), "points": points})
    if not series:
        return _bad("no_points")

    raw_max = _first_key(doc, MAX_KEYS)
    maximum = None
    if not _missing(raw_max):
        maximum = _number(raw_max)
        if maximum is None:
            return _bad("bad_max")
    if kind == "progress" and maximum is not None and maximum <= 0:
        return _bad("bad_max")

    spec = {
        "type": kind,
        "title": _text_or_none(_first_key(doc, TITLE_KEYS), MAX_TITLE_CHARS),
        "unit": _text_or_none(_first_key(doc, UNIT_KEYS), MAX_LABEL_CHARS),
        "x_label": _text_or_none(_first_key(doc, X_LABEL_KEYS), MAX_LABEL_CHARS),
        "y_label": _text_or_none(_first_key(doc, Y_LABEL_KEYS), MAX_LABEL_CHARS),
        "note": _text_or_none(_first_key(doc, NOTE_KEYS), MAX_TITLE_CHARS),
        "max": maximum,
        "series": series,
        "dropped": dropped,
    }
    return {"ok": True, "spec": spec}


def chart_accessibility_text(spec):
    """One sentence describing a parsed chart, for VoiceOver / TalkBack."""
    kinds = {"bar": "Bar chart", "grouped_bar": "Grouped bar chart", "stacked_bar": "Stacked bar chart",
             "line": "Line chart", "area": "Area chart", "pie": "Pie chart", "scatter": "Scatter chart",
             "range": "Range chart", "progress": "Progress chart"}
    parts = [kinds.get(spec["type"], "Chart")]
    if spec.get("title"):
        parts.append(spec["title"])
    values = []
    for entry in spec["series"]:
        for point in entry["points"]:
            values.append(point["y"])
            if "y2" in point:
                values.append(point["y2"])
    if values:
        unit = (" " + spec["unit"]) if spec.get("unit") else ""
        parts.append("%s to %s%s" % (num_text(min(values)), num_text(max(values)), unit))
    return ", ".join(parts)


# ---------------------------------------------------------------------------------------------
# §6 attachment_excerpt
# ---------------------------------------------------------------------------------------------

def attachment_excerpt(pages, max_pages=MAX_ATTACHMENT_PAGES, max_chars=MAX_ATTACHMENT_CHARS):
    """§6. Page texts of one attachment -> the text actually sent to the provider.

    CRLF/CR become LF. Every line whose folded form carries an identity/contact/ID label, a run of
    >= 10 digits, is dropped by the records rule (`_pii_line`, docs/health-records.md §28), so a lab
    PDF attached to chat is redacted exactly like a record read through `records_get`. Empty pages are
    skipped but still count toward `pages_total`. Pages are joined with a blank line and, when the
    attachment has more than one page, each is introduced by `--- page N ---`. The result is cut to
    `max_chars` code points.
    """
    all_pages = list(pages or [])
    total = len(all_pages)
    kept = []
    redacted = 0
    for number, page in enumerate(all_pages[:max_pages], start=1):
        text = (page or "").replace("\r\n", "\n").replace("\r", "\n")
        lines = []
        for line in text.split("\n"):
            if _pii_line(fold(line), []):
                redacted += 1
            else:
                lines.append(line)
        body = "\n".join(lines).strip("\n")
        if not body.strip(" \t\n"):
            continue
        kept.append((number, body))

    if total > 1:
        parts = ["--- page %d ---\n%s" % (number, body) for number, body in kept]
    else:
        parts = [body for _, body in kept]
    text = "\n\n".join(parts)
    truncated = cp_len(text) > max_chars or total > max_pages
    text = cp_cut(text, max_chars)
    return {
        "text": text if text else None,
        "pages_total": total,
        "pages_used": len(kept),
        "pages_skipped": max(0, total - max_pages),
        "chars": cp_len(text),
        "redacted_lines": redacted,
        "truncated": truncated,
    }


def turn_excerpts(attachments, max_turn_chars=MAX_TURN_CHARS):
    """§6. Excerpts of every document attached to one turn, cut to the per-turn budget in order.
    Each entry is {id, filename, chars, truncated}; a document that no longer fits gets chars 0."""
    used = 0
    out = []
    for item in attachments or []:
        text = item.get("text") or ""
        room = max(0, max_turn_chars - used)
        cut = cp_cut(text, room)
        used += cp_len(cut)
        out.append({"id": item.get("id"), "filename": item.get("filename"),
                    "chars": cp_len(cut), "truncated": cp_len(cut) < cp_len(text)})
    return {"attachments": out, "chars": used}


# ---------------------------------------------------------------------------------------------
# §7 conversation_title
# ---------------------------------------------------------------------------------------------

def conversation_title(text):
    """§7. Title derived from the first user message: markdown stripped, whitespace collapsed, the
    first sentence when it is at least MIN_SENTENCE_LENGTH code points, cut to MAX_TITLE_LENGTH on a
    word boundary with an ellipsis. Empty input -> "" (the platform shows its localized "New chat")."""
    stripped = _RE_MD_LINK.sub("\\1", text or "")
    stripped = _RE_MD_MARKS.sub("", stripped)
    stripped = collapse_ws(stripped)
    while stripped.startswith("#"):
        stripped = stripped[1:]
    while stripped.startswith(">"):
        stripped = stripped[1:]
    stripped = stripped.strip(" ")
    if not stripped:
        return {"title": ""}

    match = _RE_SENTENCE_END.search(stripped)
    if match and match.start() + 1 >= MIN_SENTENCE_LENGTH:
        stripped = stripped[:match.start()].strip(" ")
    if cp_len(stripped) <= MAX_TITLE_LENGTH:
        return {"title": stripped}

    cut = cp_cut(stripped, MAX_TITLE_LENGTH)
    space = cut.rfind(" ")
    if space >= MIN_SENTENCE_LENGTH:
        cut = cut[:space]
    return {"title": cut.strip(" ") + "…"}


# ---------------------------------------------------------------------------------------------
# §8 resolve_data_sources
# ---------------------------------------------------------------------------------------------

def resolve_data_sources(available, consents, switches, workouts_available=False):
    """§8. The effective data sources for one conversation and the tool names they advertise.

    A source is effective only when it is available (the user has that data / connected it), its own
    consent flag is true, and the conversation's switch is not off. A switch can only ever narrow what
    consent already permits: turning one on never grants consent. Omitted switches default to on.
    Workout tools ride with the `food` source and additionally require `workouts_available`.
    """
    available = available or {}
    consents = consents or {}
    switches = switches or {}
    sources = {}
    blocked = []
    for name in SOURCES:
        is_available = bool(available.get(name))
        consented = True if name == "food" else bool(consents.get(name))
        switched_on = switches.get(name, True) is not False
        sources[name] = is_available and consented and switched_on
        if not is_available:
            blocked.append({"source": name, "reason": "unavailable"})
        elif not consented:
            blocked.append({"source": name, "reason": "not_consented"})
        elif not switched_on:
            blocked.append({"source": name, "reason": "switched_off"})

    tools = []
    if sources["food"]:
        tools.extend(FOOD_TOOLS)
        if workouts_available:
            tools.extend(WORKOUT_TOOLS)
    if sources["health"]:
        tools.extend(HEALTH_TOOLS)
    if sources["medications"]:
        tools.extend(MEDICATION_TOOLS)
    if sources["records"]:
        tools.extend(RECORDS_TOOLS)
    return {"sources": sources, "tools": tools, "blocked": blocked}


# ---------------------------------------------------------------------------------------------
# §9 prompt gallery
# ---------------------------------------------------------------------------------------------

def gallery_for(sources, catalog=None):
    """§9. The gallery entries offered for a set of effective sources, grouped by category in catalog
    order. An entry whose `requires` is not fully satisfied is absent — never shown greyed out."""
    doc = catalog if catalog is not None else GALLERY_DOC
    entries = list(doc.get("prompts") or [])
    categories = list(doc.get("categories") or [])
    active = set(name for name in SOURCES if (sources or {}).get(name))
    chosen = [e for e in sorted(entries, key=lambda e: (e.get("order", 0), e.get("id", "")))
              if set(e.get("requires") or []).issubset(active)]
    grouped = []
    for category in categories:
        ids = [e["id"] for e in chosen if e.get("category") == category]
        if ids:
            grouped.append({"category": category, "ids": ids})
    return {"categories": grouped, "count": len(chosen)}


#: The chips above the composer, by weight goal. They are gallery ids, not their own strings, so a
#: chip and the gallery card that says the same thing are one entry with one translation.
CHIP_GOALS = {
    "lose": ("reach_my_goal", "week_review", "dinner_tonight", "one_thing_to_change"),
    "gain": ("reach_my_goal", "protein_sources", "week_review", "one_thing_to_change"),
    "maintain": ("month_over_month", "week_review", "macro_balance", "whole_picture"),
}
#: No goal set yet: nothing that assumes a direction.
CHIP_DEFAULT = ("week_review", "reach_my_goal", "one_thing_to_change")
MAX_CHIPS = 6


def chips_for(goal, has_workouts, has_sleep, sources, catalog=None):
    """§9. The chips offered above the composer: the goal's own prompts, with a training and a sleep
    prompt in front when there is something to read.

    Gated by `gallery_for`, so a chip can never promise data the user has not connected — the same
    rule the gallery cards follow, from the same catalog.
    """
    offered = set()
    for group in gallery_for(sources, catalog)["categories"]:
        offered.update(group["ids"])
    wanted = []
    if has_sleep:
        wanted.append("sleep_week")
    if has_workouts:
        wanted.append("training_review")
    wanted.extend(CHIP_GOALS.get(goal or "", CHIP_DEFAULT))
    ids = []
    for entry in wanted:
        if entry in offered and entry not in ids:
            ids.append(entry)
    return {"ids": ids[:MAX_CHIPS]}


# ---------------------------------------------------------------------------------------------
# §10 archive
# ---------------------------------------------------------------------------------------------

CONVERSATION_COLUMNS = ("id", "title", "created_ms", "updated_ms", "last_message_ms", "pinned",
                        "archived", "data_sources", "selected_record_ids", "provider_override")
MESSAGE_COLUMNS = ("id", "conversation_id", "seq", "role", "content", "created_ms", "updated_ms",
                   "regenerated_from", "variant_index", "record_refs", "attachment_ids")
ATTACHMENT_COLUMNS = ("id", "kind", "filename", "mime_type", "bytes", "sha256", "page_count",
                      "char_count", "excerpt", "created_ms")


def _rows(snapshot, table):
    return list((snapshot or {}).get(table) or [])


def _live(rows):
    return [r for r in rows if not r.get("deleted")]


def _pick(row, columns):
    return dict((key, row.get(key)) for key in columns)


def chat_archive(snapshot):
    """§10. A store snapshot -> the `ayuvo-coach-chats` document. Deleted rows (tombstones) are local
    only and never exported. Conversations sort by (created_ms, id), messages by (conversation_id,
    seq, id), attachments by (created_ms, id), so a re-export is byte-identical."""
    conversations = sorted(_live(_rows(snapshot, "conversations")),
                           key=lambda r: (r.get("created_ms", 0), r.get("id", "")))
    known = set(r.get("id") for r in conversations)
    messages = sorted([r for r in _live(_rows(snapshot, "messages")) if r.get("conversation_id") in known],
                      key=lambda r: (r.get("conversation_id", ""), r.get("seq", 0), r.get("id", "")))
    used = set()
    for message in messages:
        for attachment_id in message.get("attachment_ids") or []:
            used.add(attachment_id)
    attachments = sorted([r for r in _live(_rows(snapshot, "attachments")) if r.get("id") in used],
                         key=lambda r: (r.get("created_ms", 0), r.get("id", "")))
    return {
        "format": ARCHIVE_FORMAT,
        "format_version": ARCHIVE_VERSION,
        "conversations": [_pick(r, CONVERSATION_COLUMNS) for r in conversations],
        "messages": [_pick(r, MESSAGE_COLUMNS) for r in messages],
        "attachments": [_pick(r, ATTACHMENT_COLUMNS) for r in attachments],
    }


def _merge_rows(local, incoming, key_columns, counts, prefix):
    by_id = dict((row.get("id"), row) for row in local)
    for row in incoming:
        row_id = row.get("id")
        if not isinstance(row_id, str) or not row_id:
            counts[prefix + "_rejected"] += 1
            continue
        existing = by_id.get(row_id)
        if existing is None:
            merged = _pick(row, key_columns)
            merged["deleted"] = 0
            by_id[row_id] = merged
            local.append(merged)
            counts[prefix + "_inserted"] += 1
            continue
        if existing.get("deleted"):
            counts[prefix + "_skipped_tombstoned"] += 1
            continue
        if (row.get("updated_ms") or 0) > (existing.get("updated_ms") or 0):
            existing.update(_pick(row, key_columns))
            counts[prefix + "_updated"] += 1
        else:
            counts[prefix + "_skipped_older"] += 1
    return local


def merge_chat_archive(snapshot, archive):
    """§10. Merge an archive into a snapshot. Nothing is ever deleted: rows upsert by id, a newer
    `updated_ms` wins, and a local tombstone always wins (a deleted conversation is never
    resurrected). Messages whose conversation is absent from both the archive and the store are
    skipped and counted."""
    result = {
        "conversations": [dict(r) for r in _rows(snapshot, "conversations")],
        "messages": [dict(r) for r in _rows(snapshot, "messages")],
        "attachments": [dict(r) for r in _rows(snapshot, "attachments")],
    }
    counts = {}
    for prefix in ("conversations", "messages", "attachments"):
        for suffix in ("inserted", "updated", "skipped_older", "skipped_tombstoned", "rejected"):
            counts[prefix + "_" + suffix] = 0
    counts["messages_skipped_orphan"] = 0

    if (archive or {}).get("format") != ARCHIVE_FORMAT:
        return {"snapshot": result, "counts": counts, "error": "bad_format"}
    if (archive.get("format_version") or 0) > ARCHIVE_VERSION:
        return {"snapshot": result, "counts": counts, "error": "newer_version"}

    _merge_rows(result["conversations"], archive.get("conversations") or [],
                CONVERSATION_COLUMNS, counts, "conversations")
    live_ids = set(r.get("id") for r in result["conversations"] if not r.get("deleted"))
    messages = []
    for row in archive.get("messages") or []:
        if row.get("conversation_id") in live_ids:
            messages.append(row)
        else:
            counts["messages_skipped_orphan"] += 1
    _merge_rows(result["messages"], messages, MESSAGE_COLUMNS, counts, "messages")
    _merge_rows(result["attachments"], archive.get("attachments") or [],
                ATTACHMENT_COLUMNS, counts, "attachments")
    return {"snapshot": result, "counts": counts, "error": None}


# ---------------------------------------------------------------------------------------------
# §10 Export
# ---------------------------------------------------------------------------------------------

MAX_SLUG_CHARS = 40
_RE_SLUG_DROP = re.compile("[^a-z0-9]+")


def export_slug(title):
    """A filename stem from a conversation title: lowercase, runs of anything else become one '-',
    trimmed, capped. An empty title gives "chat", so a file is never named by an accident."""
    slug = _RE_SLUG_DROP.sub("-", (title or "").lower()).strip("-")
    slug = cp_cut(slug, MAX_SLUG_CHARS).strip("-")
    return slug or "chat"


def conversation_markdown(conversation, messages, attachments, local_day, provider=None):
    """§10 "Export as Markdown". A readable transcript: the title and date, then each turn as
    **You:** / **Coach:**, with the attachments a turn carried and the records a reply relied on.
    Attachment *contents* are never inlined — only their names, because the excerpt was a redacted
    copy of a file the user still has."""
    conversation = conversation or {}
    by_id = dict((a.get("id"), a) for a in attachments or [])
    rows = sorted(messages or [], key=lambda m: (m.get("seq", 0), m.get("variant_index", 0)))
    shown = _latest_variants(rows)

    title = conversation.get("title") or "New chat"
    header = [local_day, "%d messages" % len(shown)]
    if provider:
        header.append(provider)
    lines = ["# " + collapse_ws(title), "", " · ".join(header), "", "---"]

    for message in shown:
        lines.append("")
        speaker = "Coach" if message.get("role") == "assistant" else "You"
        lines.append("**%s:** %s" % (speaker, message.get("content") or ""))
        names = [by_id.get(i, {}).get("filename") or i for i in message.get("attachment_ids") or []]
        if names:
            lines.append("")
            lines.append("Attached: " + ", ".join(names))
        refs = message.get("record_refs") or []
        if refs:
            lines.append("")
            lines.append("Used records: " + "; ".join(
                "%s — %s" % (r.get("title") or r.get("record_id"), r.get("date") or "") for r in refs
            ))
    return {
        "text": "\n".join(lines) + "\n",
        "filename": "%s-%s.md" % (export_slug(title), local_day),
    }


def conversation_json(conversation, messages, attachments, local_day):
    """§10 "Export as JSON": one conversation in the §11 archive shape, so a file exported from one
    chat imports through exactly the same reader as a full backup."""
    conversation = conversation or {}
    snapshot = {
        "conversations": [conversation],
        "messages": [m for m in messages or [] if m.get("conversation_id") == conversation.get("id")],
        "attachments": attachments or [],
    }
    archive = chat_archive(snapshot)
    return {
        "archive": archive,
        "filename": "%s-%s.json" % (export_slug(conversation.get("title") or "New chat"), local_day),
    }


def regenerate_plan(messages, seq):
    """§10 "Regenerate": which user turn to re-send, and what the new reply row looks like.

    Nothing is deleted — the new reply keeps the seq it replaces and takes the next variant_index, so
    the stepper can walk the versions. `regenerated_from` always points at the FIRST variant, so a
    chain of regenerations stays a flat set rather than a linked list.
    """
    rows = sorted(messages or [], key=lambda m: (m.get("seq", 0), m.get("variant_index", 0)))
    variants = [m for m in rows if m.get("seq") == seq and m.get("role") == "assistant"]
    if not variants:
        return {"ok": False, "reason": "not_a_reply"}
    prompt = None
    for message in rows:
        if message.get("seq", 0) < seq and message.get("role") == "user":
            prompt = message
    if prompt is None:
        return {"ok": False, "reason": "no_prompt"}
    first = variants[0]
    return {
        "ok": True,
        "prompt_id": prompt.get("id"),
        "prompt_seq": prompt.get("seq"),
        "seq": seq,
        "variant_index": max(m.get("variant_index", 0) for m in variants) + 1,
        "regenerated_from": first.get("regenerated_from") or first.get("id"),
        "attachment_ids": list(prompt.get("attachment_ids") or []),
    }


def _latest_variants(rows):
    """Only the newest version of each reply is shown; the stepper reaches the rest."""
    best = {}
    order = []
    for row in rows:
        seq = row.get("seq", 0)
        if seq not in best:
            order.append(seq)
        if seq in best and best[seq].get("variant_index", 0) >= row.get("variant_index", 0):
            continue
        best[seq] = row
    return [best[s] for s in order]


# ---------------------------------------------------------------------------------------------
# Vector dispatch
# ---------------------------------------------------------------------------------------------

def run_case(function, inp):
    if function == "parse_blocks":
        return parse_blocks(inp["markdown"])
    if function == "parse_chart_spec":
        return parse_chart_spec(inp["raw"])
    if function == "repair_chart_json":
        return {"text": repair_chart_json(inp["raw"])}
    if function == "attachment_excerpt":
        return attachment_excerpt(inp["pages"], inp.get("max_pages", MAX_ATTACHMENT_PAGES),
                                  inp.get("max_chars", MAX_ATTACHMENT_CHARS))
    if function == "conversation_title":
        return conversation_title(inp["text"])
    if function == "resolve_data_sources":
        return resolve_data_sources(inp["available"], inp["consents"], inp["switches"],
                                    inp.get("workouts_available", False))
    if function == "chips_for":
        return chips_for(inp.get("goal"), inp.get("has_workouts", False), inp.get("has_sleep", False),
                         inp.get("sources"), inp.get("catalog"))
    if function == "gallery_for":
        return gallery_for(inp["sources"], inp.get("catalog"))
    if function == "chat_archive":
        if "archive" in inp:
            return merge_chat_archive(inp["snapshot"], inp["archive"])
        return chat_archive(inp["snapshot"])
    if function == "export":
        op = inp["op"]
        if op == "markdown":
            return conversation_markdown(inp["conversation"], inp["messages"], inp.get("attachments"),
                                         inp["local_day"], inp.get("provider"))
        if op == "json":
            return conversation_json(inp["conversation"], inp["messages"], inp.get("attachments"),
                                     inp["local_day"])
        if op == "regenerate":
            return regenerate_plan(inp["messages"], inp["seq"])
        if op == "slug":
            return {"slug": export_slug(inp["title"])}
        raise ValueError(op)
    raise ValueError("unknown function %r" % (function,))
