#!/usr/bin/env python3
"""Ayuvo Health Records: executable reference for the Phase 2 deterministic logic.

Contract: docs/health-records.md §8.1, §9.3, §10-§17. Where this file and the prose disagree,
this file wins and the prose is fixed. Android (Kotlin) and iOS (Swift) port it line by line and
run shared/records/test-vectors/*.json in their unit tests.

Portability rules followed everywhere below (and required of the ports):
  * Python 3 stdlib only. Every function is pure (no clock, locale, randomness or I/O except loading
    the two shared JSON files once).
  * Regexes use only: literals, character classes with explicit ASCII ranges ([a-z], [0-9], [ ]),
    (?:...), capture groups (numbered, never named), backreferences (\\2), ?, *, +, {n,m}, lazy
    quantifiers are not used, alternation, ^ and $ on single-line strings, and FIXED-LENGTH
    lookbehind/lookahead of one character class ((?<![a-z]), (?![0-9])). No flags: case-insensitivity
    comes from matching FOLDED text. We never use \\d \\w \\s \\b in patterns owned by this file
    (record_types.json does; see §10 for the semantics ports must use).
  * Python `re.match(p, s)` == Java `Matcher.lookingAt()` == NSRegularExpression anchored match;
    `re.search` == `find()`; `re.fullmatch` == `matches()`. Matching always runs on whole strings or
    explicit substrings (never on Java regions), so lookbehind at a substring start sees nothing.
  * Strings are sequences of Unicode code points. Lengths and offsets in this file are code point
    offsets. Kotlin/Swift ports may use UTF-16/Character offsets internally as long as the same
    substrings result (all vector text is BMP).
  * Iteration orders are explicit (lists, or sorted keys). Floats: confidences are literals or
    produced by round2(); numbers are compared by value in vectors (13 == 13.0).
"""

import calendar
import datetime as _dt
import json
import math
import os
import re
import unicodedata

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SHARED = os.path.join(ROOT, "shared", "records")

RECORD_TYPES = ["lab_report", "prescription", "consultation_note", "discharge_summary", "imaging_report",
                "diagnostic_report", "medication_list", "vaccination_record", "bill", "insurance",
                "personal_note", "other"]
DEFAULT_CATEGORY = {
    "lab_report": "lab_reports", "diagnostic_report": "lab_reports", "prescription": "prescriptions",
    "consultation_note": "doctor_visits", "discharge_summary": "hospitalization", "imaging_report": "imaging",
    "medication_list": "medication", "vaccination_record": "vaccination", "bill": "insurance_bills",
    "insurance": "insurance_bills", "personal_note": "personal_notes", "other": "other",
}
# English type labels used in STORED titles ("<Type label> — <facility>"). Same strings as the UI's
# English RecordType titles; stored titles are never localized.
TYPE_LABEL = {
    "lab_report": "Lab Report", "prescription": "Prescription", "consultation_note": "Doctor Note",
    "discharge_summary": "Discharge Summary", "imaging_report": "Imaging Report",
    "diagnostic_report": "Diagnostic Report", "medication_list": "Medication List",
    "vaccination_record": "Vaccination Record", "bill": "Bill", "insurance": "Insurance",
    "personal_note": "Note", "other": "Record",
}
DATE_KEYS = ["report_date", "collection_date", "prescription_date", "discharge_date", "visit_date",
             "admission_date", "follow_up_date"]
# document_date derivation order (§8.1).
DOCUMENT_DATE_ORDER = ["report_date", "collection_date", "prescription_date", "discharge_date", "visit_date"]
MULTI_VALUED_KEYS = frozenset(["test_result", "diagnosis", "symptom", "medication", "procedure", "recommendation"])
FIELD_KEYS = ["doctor_name", "doctor_specialty", "facility", "department", "patient_name", "patient_age",
              "patient_sex", "report_name", "test_result", "diagnosis", "symptom", "medication", "procedure",
              "recommendation", "follow_up_date", "visit_date", "collection_date", "report_date",
              "prescription_date", "admission_date", "discharge_date", "document_time", "location"]
FLAGS = ["low", "high", "critical_low", "critical_high", "normal", "abnormal", "unknown"]


# ---------------------------------------------------------------------------------------------
# Shared data files
# ---------------------------------------------------------------------------------------------

_CACHE = {}


def record_types():
    """record_types.json with each rule's pattern compiled (Python default str semantics)."""
    if "rt" not in _CACHE:
        with open(os.path.join(SHARED, "record_types.json"), encoding="utf-8") as fh:
            data = json.load(fh)
        for t in data["types"]:
            for r in t["rules"]:
                r["_re"] = re.compile(r["pattern"])
        _CACHE["rt"] = data
    return _CACHE["rt"]


def units():
    """[(folded_variant, canonical)] sorted by folded length desc, then folded string asc."""
    if "units" not in _CACHE:
        with open(os.path.join(SHARED, "units.json"), encoding="utf-8") as fh:
            data = json.load(fh)
        seen = {}
        for u in data["units"]:
            for v in u["variants"] + [u["canonical"]]:
                fv = fold(v)
                if fv and fv not in seen:
                    seen[fv] = u["canonical"]
        _CACHE["units"] = sorted(seen.items(), key=lambda kv: (-len(kv[0]), kv[0]))
    return _CACHE["units"]


# ---------------------------------------------------------------------------------------------
# §10 Folding
# ---------------------------------------------------------------------------------------------

# Deleted before anything else: soft hyphen, zero-width space/non-joiner/joiner, word joiner, BOM.
_ZERO_WIDTH = frozenset("­​‌‍⁠﻿")


def _pre(s):
    """Step 1 of fold: CRLF/CR -> LF, NFKD, drop combining marks (Mn, Mc, Me) and zero-width chars.
    Spacing is untouched (lab rows need the original 2+ space / tab cell separators)."""
    s = s.replace("\r\n", "\n").replace("\r", "\n")
    s = unicodedata.normalize("NFKD", s)
    return "".join(ch for ch in s if ch not in _ZERO_WIDTH and unicodedata.category(ch) not in ("Mn", "Mc", "Me"))


def _collapse(line):
    """Runs of ' ' and '\\t' -> one ' ', then trim ' ' at both ends. (NFKD already turned NBSP,
    U+2000-U+200A, U+202F, U+205F and U+3000 into U+0020.)"""
    out = []
    prev_space = False
    for ch in line:
        if ch == " " or ch == "\t":
            if not prev_space:
                out.append(" ")
            prev_space = True
        else:
            out.append(ch)
            prev_space = False
    return "".join(out).strip(" ")


def _lower_cp(ch):
    """Per-code-point lowercase, no context (Σ -> σ, never ς). If the full mapping is longer than
    one code point (only U+0130, already decomposed by NFKD) the code point is kept. Equivalent to
    Java Character.toLowerCase(int) / Swift single-scalar lowercaseMapping."""
    lo = ch.lower()
    return lo if len(lo) == 1 else ch


def pfold(s):
    """'Printed fold': fold() without lowercasing. Same length as fold(s) code point for code point,
    so offsets found in folded text slice the printed (case-preserving) text. Values stored 'as
    printed' are pfold slices: diacritics are removed, case is kept."""
    return "\n".join(_collapse(line) for line in _pre(s).split("\n"))


def fold(s):
    """§10 fold: NFKD, remove combining marks and zero-width characters, CRLF/CR -> LF, collapse
    spaces/tabs per line, trim each line, lowercase per code point. Line count is preserved."""
    return "".join(_lower_cp(ch) for ch in pfold(s))


_CELL_SPLIT = re.compile("[ ]*\t[ \t]*|[ ]{2,}")


class Line(object):
    """One line of a page. p = printed fold, f = folded (same length), cells = [(start, end)] spans
    of p separated in the source by a tab or 2+ spaces (§9.1 joins OCR row cells with two spaces)."""
    __slots__ = ("page", "index", "p", "f", "cells")

    def __init__(self, page, index, p, cells):
        self.page = page
        self.index = index
        self.p = p
        self.f = "".join(_lower_cp(ch) for ch in p)
        self.cells = cells

    def cell_end(self, pos):
        for s, e in self.cells:
            if s <= pos < e:
                return e
        return len(self.p)


def page_lines(text, page):
    out = []
    for i, raw in enumerate(_pre(text or "").split("\n")):
        parts = [c.strip(" \t") for c in _CELL_SPLIT.split(raw)]
        parts = [c for c in parts if c]
        cells = []
        pos = 0
        for c in parts:
            cells.append((pos, pos + len(c)))
            pos += len(c) + 1
        out.append(Line(page, i, " ".join(parts), cells))
    return out


def _nonempty(lines):
    return [ln for ln in lines if ln.p]


def _head(lines):
    """§10 head: first 25 non-empty lines."""
    return _nonempty(lines)[:25]


def round2(x):
    """Round half up to 2 decimals: floor(x*100 + 0.5 + 1e-9) / 100."""
    return math.floor(x * 100 + 0.5 + 1e-9) / 100.0


def _is_alnum_cp(ch):
    """Alphanumeric for normalize_value / tokens: ASCII [a-z0-9] (after fold) or, above U+007F, a
    code point whose General_Category is L* or Nd."""
    o = ord(ch)
    if o < 128:
        return ("a" <= ch <= "z") or ("0" <= ch <= "9") or ("A" <= ch <= "Z")
    cat = unicodedata.category(ch)
    return cat[0] == "L" or cat == "Nd"


def words(folded):
    """Maximal runs of alphanumeric code points."""
    out, cur = [], []
    for ch in folded:
        if _is_alnum_cp(ch):
            cur.append(ch)
        elif cur:
            out.append("".join(cur))
            cur = []
    if cur:
        out.append("".join(cur))
    return out


def norm_text(s):
    """§8.1 normalized text: fold, runs of non-alphanumerics -> one space, trim."""
    return " ".join(words(fold(s or "")))


_NORM_DOCTOR_TITLES = frozenset(["dr", "doctor", "prof"])
_NORM_PATIENT_TITLES = frozenset(["mr", "mrs", "ms", "miss", "master", "mstr", "baby", "smt", "shri", "sri", "kumari",
                                  "kum", "mx"])


def normalize_value(key, value_text, value_json=None):
    """§8.1 normalized value used for de-duplication in applyExtraction.
    Date keys and document_time: the ISO string unchanged. test_result: norm(name) + '|' +
    norm(value). Everything else (medication included: the drug name): norm(value_text)."""
    if key in DATE_KEYS or key == "document_time":
        return (value_text or "").strip()
    if key in ("doctor_name", "patient_name"):
        # Honorifics are not part of the identity: "Dr Neha-Gupta" == "Neha Gupta", "Mrs. Rao" == "Rao".
        ws = norm_text(value_text).split(" ")
        drop = _NORM_DOCTOR_TITLES if key == "doctor_name" else _NORM_PATIENT_TITLES
        while len(ws) > 1 and ws[0] in drop:
            ws = ws[1:]
        return " ".join(ws)
    if key == "test_result":
        vj = value_json or {}
        name = vj.get("name") if vj.get("name") is not None else value_text
        return norm_text(name) + "|" + norm_text(vj.get("value") or "")
    return norm_text(value_text)


# ---------------------------------------------------------------------------------------------
# §10 Classifier
# ---------------------------------------------------------------------------------------------

def _classify_lines(lines_by_page):
    rt = record_types()
    head = [ln.f for ln in (_head(lines_by_page[0]) if lines_by_page else [])]
    body = [ln.f for pg in lines_by_page for ln in pg if ln.f]
    scores = []
    for t in rt["types"]:
        s = 0
        for r in t["rules"]:
            scope = head if r["scope"] == "head" else body
            # Rules match line by line (a pattern never spans a line break).
            for text in scope:
                if r["_re"].search(text):
                    s += r["weight"]
                    break
        scores.append((t["id"], t["category"], s))
    return scores


def classify(pages_text):
    """§10. pages_text: list of page strings of the analysed range."""
    rt = record_types()
    scores = _classify_lines([page_lines(t, i) for i, t in enumerate(pages_text)])
    best_i = 0
    for i, (_, _, s) in enumerate(scores):
        if s > scores[best_i][2]:
            best_i = i  # strict '>' keeps the earlier type on ties
    best = scores[best_i][2]
    second = max([s for i, (_, _, s) in enumerate(scores) if i != best_i] or [0])
    out_scores = dict((tid, s) for tid, _, s in scores)
    if best >= rt["threshold"] and best - second >= rt["margin"]:
        # Confidence grows with the lead beyond the margin (capped at 3) and with the absolute score
        # (capped at 2 x threshold). A lead exactly at the margin gives at most 0.65 (< the 0.7 review bar);
        # any lead of margin + 1 gives at least 0.70. Range 0.60 ... 0.95.
        excess = min(best - second - rt["margin"], 3)
        strength = min(best / float(rt["threshold"]), 2.0)
        conf = round2(min(0.95, 0.55 + 0.10 * excess + 0.05 * strength))
        return {"record_type": scores[best_i][0], "category": scores[best_i][1], "confidence": conf,
                "scores": out_scores}
    return {"record_type": "other", "category": "other", "confidence": 0, "scores": out_scores}


def _page_best_type(lines):
    """Boundary helper: best type of a page by score alone (margin ignored), None below threshold."""
    rt = record_types()
    scores = _classify_lines([lines])
    best_i = 0
    for i, (_, _, s) in enumerate(scores):
        if s > scores[best_i][2]:
            best_i = i
    if scores[best_i][2] >= rt["threshold"]:
        return scores[best_i][0]
    return None


# ---------------------------------------------------------------------------------------------
# §11 Dates
# ---------------------------------------------------------------------------------------------

_MONTH_ALT = ("january|february|march|april|june|july|august|september|october|november|december|"
              "jan|feb|mar|apr|may|jun|jul|aug|sept|sep|oct|nov|dec")
_MONTH_NUM = {"january": 1, "february": 2, "march": 3, "april": 4, "may": 5, "june": 6, "july": 7,
              "august": 8, "september": 9, "october": 10, "november": 11, "december": 12, "jan": 1,
              "feb": 2, "mar": 3, "apr": 4, "jun": 6, "jul": 7, "aug": 8, "sept": 9, "sep": 9, "oct": 10,
              "nov": 11, "dec": 12}

# Priority order matters only for equal (start, length) candidates.
_RE_ISO = re.compile("(?<![0-9])([0-9]{4})([-/.])([0-9]{1,2})\\2([0-9]{1,2})(?![0-9])")
_RE_NUM = re.compile("(?<![0-9])([0-9]{1,2})([-/.])([0-9]{1,2})\\2([0-9]{4}|[0-9]{2})(?![0-9])")
_RE_DMY_TEXT = re.compile("(?<![0-9a-z])([0-9]{1,2})(?:st|nd|rd|th)?[ ./-]?(" + _MONTH_ALT +
                          ")\\.?(?![a-z])[ ,./'-]*([0-9]{4}|[0-9]{2})(?![0-9])")
_RE_MDY_TEXT = re.compile("(?<![a-z])(" + _MONTH_ALT + ")\\.?[ ./-]?([0-9]{1,2})(?:st|nd|rd|th)?(?![0-9a-z])"
                          "[ ,./-]*([0-9]{4})(?![0-9])")
_RE_MY_TEXT = re.compile("(?<![a-z])(" + _MONTH_ALT + ")\\.?(?![a-z])[ ,'./-]*([0-9]{4})(?![0-9])")


def _two_digit_year(yy, today):
    return 2000 + yy if yy <= (today.year + 1) % 100 else 1900 + yy


def _valid(y, m, d):
    try:
        return _dt.date(y, m, d)
    except ValueError:
        return None


def _date_candidates(f, today, date_order, both_orders=False):
    """All date mentions in one folded line: [{start, end, dates:[date], precision}], non-overlapping,
    leftmost-longest (ties: pattern priority ISO, NUM, DMY_TEXT, MDY_TEXT, MY_TEXT).
    `dates` has one element except for ambiguous numeric dates when both_orders is True (validator)."""
    raw = []
    for prio, rx in enumerate([_RE_ISO, _RE_NUM, _RE_DMY_TEXT, _RE_MDY_TEXT, _RE_MY_TEXT]):
        pos = 0
        while True:
            m = rx.search(f, pos)
            if not m:
                break
            raw.append((m.start(), -(m.end() - m.start()), prio, m))
            pos = m.start() + 1
    raw.sort(key=lambda t: (t[0], t[1], t[2]))
    out = []
    last_end = 0
    for start, neglen, prio, m in raw:
        if start < last_end:
            continue
        dates, precision = [], "day"
        if prio == 0:
            d = _valid(int(m.group(1)), int(m.group(3)), int(m.group(4)))
            dates = [d] if d else []
        elif prio == 1:
            a, b, ytxt, sep = int(m.group(1)), int(m.group(3)), m.group(4), m.group(2)
            if len(ytxt) == 2 and sep == ".":
                continue  # d.m.yy is too often a version/decimal; dotted dates need a 4-digit year
            y = int(ytxt) if len(ytxt) == 4 else _two_digit_year(int(ytxt), today)
            if a > 12 and b > 12:
                continue
            if a > 12:
                orders = ["dmy"]
            elif b > 12:
                orders = ["mdy"]
            elif both_orders:
                orders = [date_order, "mdy" if date_order == "dmy" else "dmy"]
            else:
                orders = [date_order]
            for o in orders:
                d = _valid(y, b, a) if o == "dmy" else _valid(y, a, b)
                if d and d not in dates:
                    dates.append(d)
        elif prio == 2:
            ytxt = m.group(3)
            y = int(ytxt) if len(ytxt) == 4 else _two_digit_year(int(ytxt), today)
            d = _valid(y, _MONTH_NUM[m.group(2)], int(m.group(1)))
            dates = [d] if d else []
        elif prio == 3:
            d = _valid(int(m.group(3)), _MONTH_NUM[m.group(1)], int(m.group(2)))
            dates = [d] if d else []
        else:
            d = _valid(int(m.group(2)), _MONTH_NUM[m.group(1)], 1)
            dates = [d] if d else []
            precision = "month"
        if not dates:
            continue  # an invalid calendar date consumes nothing
        out.append({"start": m.start(), "end": m.end(), "dates": dates, "precision": precision})
        last_end = m.end()
    return out


def _add_years(d, n):
    try:
        return d.replace(year=d.year + n)
    except ValueError:
        return d.replace(year=d.year + n, day=28)


def _add_months(d, n):
    total = d.year * 12 + (d.month - 1) + n
    y, m = divmod(total, 12)
    return _dt.date(y, m + 1, min(d.day, calendar.monthrange(y, m + 1)[1]))


def _plausible(d, key, today):
    if d < _dt.date(1900, 1, 1):
        return False
    if key == "follow_up_date":
        return d <= _add_years(today, 2)
    return d <= today + _dt.timedelta(days=1)


def _lbl(alt):
    return re.compile("(?<![a-z])(?:" + alt + ")(?![a-z])")


# (kind, key, pattern). kind: key | typed (0.9, type default key) | bare (0.6, head only) | dob | ignore
_DATE_LABELS = [
    ("dob", None, _lbl("dob|d\\.o\\.b\\.?|date of birth|birth ?date|born on|born")),
    ("ignore", None, _lbl("printed(?: on)?|print date|generated(?: on)?|registered(?: on)?|registration(?: date)?|"
                          "reg\\.? date|received(?: on)?|valid (?:till|upto|up to|until)|expiry(?: date)?|"
                          "exp\\.?(?: date)?|mfg\\.?(?: date)?|manufactur[a-z]*|lmp|edd")),
    ("key", "collection_date", _lbl("collected(?: on| at)?|collection(?: date)?|date of collection|sample date|"
                                    "sample collected(?: on)?|sample collection(?: date)?|specimen collected|"
                                    "drawn(?: on)?|coll\\.? date|date of sample")),
    ("key", "report_date", _lbl("reported(?: on)?|report date|date of report|reporting date|released(?: on)?|"
                                "authenticated(?: on)?|authori[sz]ed(?: on)?|result date|approved on|verified on")),
    ("key", "visit_date", _lbl("visit date|date of visit|consultation date|date of consultation|opd date|"
                               "visited on|seen on|appointment date|encounter date")),
    ("key", "prescription_date", _lbl("rx date|prescription date|date of prescription")),
    ("key", "admission_date", _lbl("date of admission|doa|admitted on|admission date|admitted")),
    ("key", "discharge_date", _lbl("date of discharge|dod|discharged on|discharge date|discharged")),
    ("key", "follow_up_date", _lbl("follow[ -]?up(?: on| date| visit)?|review on|review date|next review|"
                                   "next visit(?: on)?|revisit(?: on)?|f/u|come (?:back|again) on|next due(?: on| date)?|due on")),
    ("typed", None, _lbl("invoice date|bill date|receipt date|date of issue|issue date|certificate date|"
                         "date of vaccination|vaccination date|vaccinated on|date of dose|dose date|"
                         "date administered|study date|date of study|exam date|date of examination|"
                         "examination date|scan date|test date|date of test|date of procedure|procedure date|"
                         "date of surgery|claim date|date of service|service date")),
    ("bare", None, _lbl("date|dated|dt")),
]
_GAP_WORDS = frozenset(["on", "at", "dt", "date", "dated", "time", "and", "of", "is"])


def _type_default_key(record_type):
    return {"lab_report": "report_date", "imaging_report": "report_date", "diagnostic_report": "report_date",
            "consultation_note": "visit_date", "prescription": "prescription_date"}.get(record_type, "report_date")


def _label_matches(f):
    """All label matches in a folded string: [(start, end, order, kind, key)]."""
    out = []
    for order, (kind, key, rx) in enumerate(_DATE_LABELS):
        pos = 0
        while True:
            m = rx.search(f, pos)
            if not m:
                break
            out.append((m.start(), m.end(), order, kind, key))
            pos = m.start() + 1
    return out


def _non_overlapping_labels(f):
    ms = sorted(_label_matches(f), key=lambda t: (t[0], -(t[1] - t[0]), t[2]))
    out, last = [], 0
    for t in ms:
        if t[0] >= last:
            out.append(t)
            last = t[1]
    return out


def _gap_ok(gap):
    """Text between a label and its date: <= 24 chars of punctuation/spaces and connector words."""
    if len(gap) > 24:
        return False
    return all(w in _GAP_WORDS for w in re.findall("[a-z0-9]+", gap))


_RE_TIME = re.compile("(?<![0-9:])([01]?[0-9]|2[0-3]):([0-5][0-9])(?::[0-5][0-9])?[ ]?"
                      "(am|pm|a\\.m\\.?|p\\.m\\.?)?(?![0-9a-z])")
# "Review after 5 days", "Review in medicine OPD after 1 week": up to 4 words between the verb and after/in.
_RE_REL_FOLLOW = re.compile("(?<![a-z])(?:follow[ -]?up|review|revisit|come back|next visit|see me)"
                            "(?: [a-z]+){0,4} (?:after|in) ([0-9]{1,3}) ?"
                            "(days?|weeks?|wks?|months?|mths?)(?![a-z])")


def _date_items(lines_by_page, record_type, today, date_order):
    """Internal: date items with positions. Returns (items, has_follow_candidates)."""
    labelled, unlabeled, relative = [], [], []
    default_key = _type_default_key(record_type)
    head_ids = set()
    if lines_by_page:
        head_ids = set((ln.page, ln.index) for ln in _head(lines_by_page[0]))
    for lines in lines_by_page:
        prev = None  # previous non-empty line on this page
        for ln in lines:
            if not ln.f:
                continue
            cands = _date_candidates(ln.f, today, date_order)
            in_head = (ln.page, ln.index) in head_ids
            line_has_label = bool(_label_matches(ln.f))
            prev_pairs = {}
            if cands and not line_has_label and prev is not None and not _date_candidates(prev.f, today, date_order):
                # Header row labels are matched per cell so "Dose  Date Given" is not read as "dose date".
                labels = []
                for cs, ce in prev.cells:
                    labels += [(a + cs, b + cs, o, k, y) for a, b, o, k, y in _non_overlapping_labels(prev.f[cs:ce])]
                if labels:
                    rest = prev.f
                    for s, e, _, _, _ in reversed(labels):
                        rest = rest[:s] + " " + rest[e:]
                    leftover_ok = _gap_ok(rest.strip()) or len(labels) == len(cands)
                    if len(labels) == len(cands) and leftover_ok:
                        for i, c in enumerate(cands):
                            prev_pairs[i] = labels[i]
                    elif _gap_ok(prev.f[labels[-1][1]:]):
                        prev_pairs[0] = labels[-1]
            seg_start = 0
            for ci, c in enumerate(cands):
                seg = ln.f[seg_start:c["start"]]
                seg_start = c["end"]
                d = c["dates"][0]
                pos = (ln.page, ln.index, c["start"])
                lab = None
                conf = None
                ms = _label_matches(seg)
                if ms:
                    ms.sort(key=lambda t: (-t[1], t[0], t[2]))  # nearest end, then longest, then list order
                    best = ms[0]
                    gap = seg[best[1]:]
                    # Follow-up labels may carry a short phrase before the date ("Next due: MMR Dose 2 on").
                    if _gap_ok(gap) or (best[4] == "follow_up_date" and len(gap) <= 32):
                        lab, conf = best, 0.9
                elif ci in prev_pairs:
                    lab, conf = prev_pairs[ci], 0.8
                if lab is not None:
                    kind, key = lab[3], lab[4]
                    if kind in ("dob", "ignore"):
                        continue
                    if kind == "typed":
                        key = default_key
                    if kind == "bare":
                        if not in_head:
                            continue
                        # Prescriptions and consultation notes print one date: a bare "Date:" is theirs (0.8).
                        # Lab/imaging pages print several dates, so a bare one stays uncertain (0.6).
                        key = default_key
                        conf = 0.8 if record_type in ("prescription", "consultation_note") else 0.6
                    if not _plausible(d, key, today):
                        continue
                    labelled.append({"key": key, "date": d, "precision": c["precision"], "confidence": conf,
                                     "pos": pos, "end": c["end"], "line": ln, "next_start": None})
                else:
                    if in_head:  # head lines belong to the first page by construction
                        if _plausible(d, default_key, today):
                            unlabeled.append({"key": default_key, "date": d, "precision": c["precision"],
                                              "confidence": 0.6, "pos": pos, "end": c["end"], "line": ln})
            if not any(it["key"] == "follow_up_date" and it["line"] is ln for it in labelled):
                m = _RE_REL_FOLLOW.search(ln.f)
                if m:
                    relative.append((m, ln))
            prev = ln
    # First unlabeled head date only, and only when it adds a new key and a new date value.
    if unlabeled:
        u = unlabeled[0]
        if not any(it["key"] == u["key"] for it in labelled) and not any(it["date"] == u["date"] for it in labelled):
            labelled.append(u)
    # Relative follow-ups resolve against the best other date (§8.1 document_date order).
    base = _best_document_date(labelled)
    for m, ln in relative:
        if base is None:
            continue
        n, unit = int(m.group(1)), m.group(2)
        if unit.startswith("d"):
            d = base["date"] + _dt.timedelta(days=n)
        elif unit.startswith("w"):
            d = base["date"] + _dt.timedelta(days=7 * n)
        else:
            d = _add_months(base["date"], n)
        if _plausible(d, "follow_up_date", today):
            labelled.append({"key": "follow_up_date", "date": d, "precision": "day", "confidence": 0.7,
                             "pos": (ln.page, ln.index, m.start()), "end": m.end(), "line": ln})
    return labelled


def _best_document_date(items):
    for key in DOCUMENT_DATE_ORDER:
        c = [it for it in items if it["key"] == key]
        if c:
            c.sort(key=lambda it: (-it["confidence"], it["pos"]))
            return c[0]
    return None


def _dedup(items, keyfn):
    """Keep, per (key, normalized value), the highest confidence item (earliest on ties); output in
    position order of the kept items."""
    best = {}
    order = []
    for it in sorted(items, key=lambda it: it["pos"]):
        k = keyfn(it)
        if k not in best:
            best[k] = it
            order.append(k)
        elif it["confidence"] > best[k]["confidence"]:
            best[k] = it
    kept = [best[k] for k in order]
    kept.sort(key=lambda it: (it["pos"], FIELD_KEYS.index(it["key"])))
    return kept


def extract_dates(pages, record_type, today, date_order):
    """§11. pages: list of page texts; today: 'yyyy-MM-dd'; date_order: 'dmy' | 'mdy'.
    Returns field items {key, value_text, value_json, confidence, source_page, evidence}."""
    today_d = _dt.date.fromisoformat(today)
    lines_by_page = [page_lines(t, i) for i, t in enumerate(pages)]
    items = _date_items(lines_by_page, record_type, today_d, date_order)
    items = _dedup(items, lambda it: (it["key"], it["date"]))
    out = []
    for it in items:
        out.append({"key": it["key"], "value_text": it["date"].isoformat(),
                    "value_json": {"precision": it["precision"]}, "confidence": it["confidence"],
                    "source_page": it["pos"][0], "evidence": it["line"].p})
    primary = _best_document_date(items)
    if primary is not None:
        ln = primary["line"]
        tail = ln.f[primary["end"]:]
        nxt = _date_candidates(tail, today_d, date_order)
        if nxt:
            tail = tail[:nxt[0]["start"]]
        m = _RE_TIME.search(tail)
        if m:
            h, mi, ap = int(m.group(1)), int(m.group(2)), m.group(3)
            ok = True
            if ap:
                if h < 1 or h > 12:
                    ok = False
                elif ap.startswith("a"):
                    h = 0 if h == 12 else h
                else:
                    h = 12 if h == 12 else h + 12
            if ok:
                out.append({"key": "document_time", "value_text": "%02d:%02d" % (h, mi), "value_json": None,
                            "confidence": 0.7, "source_page": ln.page, "evidence": ln.p})
    return out


# ---------------------------------------------------------------------------------------------
# §13 Units and lab rows
# ---------------------------------------------------------------------------------------------

_UNIT_END = frozenset(" )]},;")


def match_unit(t, pos):
    """Longest folded unit variant at t[pos:] followed by end/space/)]},; -> (canonical, end) or None."""
    for fv, canon in units():
        if t.startswith(fv, pos):
            e = pos + len(fv)
            if e == len(t) or t[e] in _UNIT_END:
                return canon, e
    return None


_NUM = "(?:[0-9]{1,3}(?:,[0-9]{2,3})+(?:\\.[0-9]+)?|[0-9]+(?:\\.[0-9]+)?)"
_RE_VALUE_RANGE = re.compile("([0-9]{1,3})-([0-9]{1,3})")
_RE_VALUE_NUM = re.compile("(?:(<=|>=|<|>|≤|≥)[ ]?)?(" + _NUM + ")")
_RE_VALUE_QUAL = re.compile("(non[ -]?reactive|not detected|positive|negative|reactive|detected|nil|absent|"
                            "present|trace|normal|abnormal)(?![a-z])")
_RE_FLAG = re.compile("(critical high|critical low|critical|\\(high\\)|\\(low\\)|\\(h\\)|\\(l\\)|\\[h\\]|\\[l\\]|"
                      "high|low|hh|ll|h|l|\\*|↑↑|↓↓|↑|↓)(?![^ ])")
_RE_FLAG_ATTACHED = re.compile("(hh|ll|h|l|\\*|↑|↓)(?![^ ])")
_FLAG_MAP = {"critical high": "critical_high", "hh": "critical_high", "↑↑": "critical_high",
             "critical low": "critical_low", "ll": "critical_low", "↓↓": "critical_low",
             "high": "high", "h": "high", "↑": "high", "(h)": "high", "[h]": "high", "(high)": "high",
             "low": "low", "l": "low", "↓": "low", "(l)": "low", "[l]": "low", "(low)": "low",
             "*": "abnormal", "critical": "critical"}
_RE_REF_RANGE = re.compile("(" + _NUM + ")[ ]?(?:-|–|—|to)[ ]?(" + _NUM + ")")
_RE_REF_HIGH = re.compile("(?:<=|≤|<|up ?to|less than|below)[ ]?(" + _NUM + ")")
_RE_REF_LOW = re.compile("(?:>=|≥|>|more than|greater than|above)[ ]?(" + _NUM + ")")
_HEADER_WORDS = frozenset(["test", "tests", "parameter", "parameters", "investigation", "investigations", "result",
                           "results", "unit", "units", "reference", "ref", "range", "interval", "value", "values",
                           "flag", "method", "observed", "biological", "normal", "description", "name"])
_META_FIRST = frozenset(["age", "sex", "gender", "name", "patient", "pt", "uhid", "mrn", "reg", "regn",
                         "registration", "lab", "sample", "specimen", "ref", "referred", "bill", "invoice",
                         "receipt", "date", "time", "page", "phone", "mobile", "mob", "tel", "pin", "pincode",
                         "report", "collected", "received", "reported", "printed", "dr", "doctor", "consultant",
                         "visit", "bed", "ward", "ip", "op", "opd", "ipd", "policy", "claim", "member", "batch",
                         "lot", "dose", "id", "sr", "sl", "no", "plot", "sector", "gst", "gstin", "amount",
                         "qty", "rate", "barcode", "accession", "order", "client", "location"])


def _num_value(txt):
    return float(txt.replace(",", ""))


def _json_num(x):
    """Integral floats are emitted as JSON integers (13 not 13.0); ports compare numerically."""
    if x is None:
        return None
    return int(x) if float(x).is_integer() else x


def _is_header_line(f):
    if re.search("[0-9]", f):
        return False
    ws = set(re.findall("[a-z]+", f))
    return len(ws & _HEADER_WORDS) >= 2


def _parse_tail(t, tp):
    """Parse 'value [flag] [unit] [flag] [reference] [unit] [flag] [leftover]' from folded tail t
    (printed tail tp has identical offsets). Returns dict or None."""
    n = len(t)
    res = {"value": None, "value_num": None, "qualitative": False, "comparator": None, "range_value": False,
           "flag_raw": None, "unit": None, "ref_text": None, "ref_low": None, "ref_high": None}

    def boundary_ok(e):
        return e == n or t[e] == " "

    pos = 0
    m = _RE_VALUE_RANGE.match(t)
    if m and (m.end() == n or t[m.end()] == " ") and not t.startswith(".", m.end()):
        res["value"], res["range_value"] = tp[:m.end()], True
        pos = m.end()
    else:
        m = _RE_VALUE_NUM.match(t)
        if m:
            # The number must end at: end, space, '(' or '[' (reference), a unit ("13.2g/dL"), or an
            # attached flag followed by space/end ("11.2L", "13.2*").
            e = m.end()
            res["value"], res["comparator"] = tp[:e], m.group(1)
            res["value_num"] = _num_value(m.group(2))
            pos = e
            if not (e == n or t[e] in " ([" or match_unit(t, e)):
                am = _RE_FLAG_ATTACHED.match(t[e:])
                if not am:
                    return None
                res["flag_raw"] = am.group(1)
                pos = e + am.end()
        else:
            m = _RE_VALUE_QUAL.match(t)
            if not m:
                return None
            res["value"], res["qualitative"] = tp[:m.end()], True
            pos = m.end()

    def skip(p):
        while p < n and t[p] == " ":
            p += 1
        return p

    def try_flag(p):
        if res["flag_raw"] is None and p < n:
            fm = _RE_FLAG.match(t[p:])
            if fm:
                res["flag_raw"] = fm.group(1)
                return p + fm.end()
        return p

    def try_unit(p):
        if res["unit"] is None and p < n:
            u = match_unit(t, p)
            if u:
                res["unit"] = u[0]
                return u[1]
        return p

    def try_ref(p):
        if res["ref_text"] is not None or p >= n:
            return p
        if t[p] in "([":
            close = ")" if t[p] == "(" else "]"
            q = t.find(close, p + 1)
            if q < 0:
                return p
            inner = t[p + 1:q].strip(" ")
            if not re.search("[0-9]", inner):
                return p
            res["ref_text"] = tp[p:q + 1]
            for rx, kind in ((_RE_REF_RANGE, "range"), (_RE_REF_HIGH, "high"), (_RE_REF_LOW, "low")):
                fm = rx.fullmatch(inner)
                if fm:
                    _set_ref(res, fm, kind)
                    break
            return q + 1
        for rx, kind in ((_RE_REF_RANGE, "range"), (_RE_REF_HIGH, "high"), (_RE_REF_LOW, "low")):
            fm = rx.match(t[p:])
            if fm and boundary_ok(p + fm.end()):
                res["ref_text"] = tp[p:p + fm.end()]
                _set_ref(res, fm, kind)
                return p + fm.end()
        return p

    pos = try_flag(skip(pos))
    pos = try_unit(skip(pos))
    pos = try_flag(skip(pos))
    pos = try_ref(skip(pos))
    pos = try_unit(skip(pos))
    pos = try_flag(skip(pos))
    lp = skip(pos)
    leftover = t[lp:]
    if leftover:
        if re.search("[0-9]", leftover) or len(leftover.split(" ")) > 4:
            return None
        if res["qualitative"] and res["ref_text"] is None and len(leftover.split(" ")) <= 3:
            # Qualitative rows print a qualitative reference: "Protein  Trace  Nil".
            res["ref_text"] = tp[lp:]
        elif res["unit"] is None and res["ref_text"] is None:
            return None
        # else: trailing method/remark words ("Photometry") on a row that has a unit or a range.
    return res


def _set_ref(res, fm, kind):
    if kind == "range":
        res["ref_low"], res["ref_high"] = _num_value(fm.group(1)), _num_value(fm.group(2))
    elif kind == "high":
        res["ref_high"] = _num_value(fm.group(1))
    else:
        res["ref_low"] = _num_value(fm.group(1))


def _valid_name(fname):
    if not fname or len(fname) > 60 or not ("a" <= fname[0] <= "z"):
        return False
    if len(re.findall("[a-z]", fname)) < 2:
        return False
    toks = fname.split(" ")
    if any(re.fullmatch("[0-9.,:;/-]+", tk) for tk in toks):
        return False
    if re.search("[<>=≤≥]", fname):
        return False  # "Non-diabetic < 5.7": interpretation tables, not results
    first = re.findall("[a-z]+", fname)
    if first and first[0] in _META_FIRST:
        return False
    if _date_candidates(fname, _dt.date(2100, 1, 1), "dmy"):
        return False
    return True


def _resolve_flag(res):
    raw = res["flag_raw"]
    lo, hi, v = res["ref_low"], res["ref_high"], res["value_num"]
    computable = v is not None and res["comparator"] is None and not res["range_value"]
    if raw is not None:
        f = _FLAG_MAP[raw]
        if f == "abnormal" and computable and lo is not None and v < lo:
            return "low"  # '*' marks out-of-range; the printed range gives the direction
        if f == "abnormal" and computable and hi is not None and v > hi:
            return "high"
        if f != "critical":
            return f
        if computable and lo is not None and v < lo:
            return "critical_low"
        if computable and hi is not None and v > hi:
            return "critical_high"
        return "abnormal"
    if res["qualitative"]:
        vf = _qual_family(fold(res["value"]))
        rf = _qual_family(fold(res["ref_text"] or "").strip(" ()[]"))
        if fold(res["value"]) == "abnormal":
            return "abnormal"
        if vf is not None and rf is not None:
            return "normal" if vf == rf else "abnormal"
        return "unknown"
    if res["range_value"] and (lo is not None or hi is not None):
        a, b = [float(x) for x in fold(res["value"]).split("-")]
        if hi is not None and a > hi:
            return "high"
        if lo is not None and b < lo:
            return "low"
        if (lo is None or a >= lo) and (hi is None or b <= hi):
            return "normal"
        return "unknown"
    if computable and (lo is not None or hi is not None):
        if lo is not None and v < lo:
            return "low"
        if hi is not None and v > hi:
            return "high"
        return "normal"
    return "unknown"


_QUAL_NEGATIVE = frozenset(["negative", "nil", "absent", "non-reactive", "nonreactive", "non reactive",
                            "not detected", "normal"])
_QUAL_POSITIVE = frozenset(["positive", "reactive", "present", "detected", "trace", "abnormal"])


def _qual_family(f):
    """'neg' / 'pos' for a single qualitative word (folded), else None."""
    if f in _QUAL_NEGATIVE:
        return "neg"
    if f in _QUAL_POSITIVE:
        return "pos"
    return None


def parse_lab_line(ln):
    """One Line -> test_result item dict or None (§13)."""
    f, p = ln.f, ln.p
    if not f or _is_header_line(f):
        return None
    # Strip a leading bullet / index ("1.", "-", "•").
    lead = re.match("(?:[0-9]{1,2}[.)] |[-•*·>] ?)", f)
    off = lead.end() if lead else 0
    candidates = []
    # Cell mode: the first cell is the name (§9.1 separates cells with two spaces).
    if len(ln.cells) >= 2:
        candidates.append(ln.cells[1][0])
    # Token mode: every token start after the first token, left to right.
    for i in range(off + 1, len(f)):
        if f[i - 1] == " " and f[i] != " ":
            if i not in candidates:
                candidates.append(i)
    for i in candidates:
        name_f = f[off:i].rstrip(" :.=-–_")
        name_p = p[off:off + len(name_f)]
        if not _valid_name(name_f):
            continue
        res = _parse_tail(f[i:], p[i:])
        if res is None:
            continue
        if res["range_value"] and res["unit"] is None:
            continue  # "Borderline 200-239" in interpretation tables
        if res["comparator"] and res["unit"] is None:
            continue  # "Desirable < 200"
        if (not res["qualitative"] and res["unit"] is None and res["ref_text"] is None
                and res["flag_raw"] is None and len(name_f.split(" ")) > 4):
            continue
        parts = 2 + (res["unit"] is not None) + (res["ref_text"] is not None)
        if parts == 4:
            conf = 0.9
        elif parts == 3:
            conf = 0.8
        else:
            conf = 0.7 if res["qualitative"] else 0.6
        flag = _resolve_flag(res)
        vj = {"name": name_p, "value": res["value"], "value_num": _json_num(res["value_num"]),
              "unit": res["unit"], "ref_text": res["ref_text"], "ref_low": _json_num(res["ref_low"]),
              "ref_high": _json_num(res["ref_high"]), "flag": flag}
        return {"key": "test_result", "value_text": name_p, "value_json": vj, "confidence": conf,
                "source_page": ln.page, "evidence": p, "pos": (ln.page, ln.index, 0)}
    return None


_RE_NUMBER_UNIT = re.compile("(?<![0-9a-z.])" + _NUM + "[ ]?")


def count_unit_lines(lines_by_page):
    """Lines containing a number immediately followed (optional space) by a unit variant."""
    n = 0
    for lines in lines_by_page:
        for ln in lines:
            pos = 0
            while ln.f:
                m = _RE_NUMBER_UNIT.search(ln.f, pos)
                if not m:
                    break
                if match_unit(ln.f, m.end()):
                    n += 1
                    break
                pos = m.start() + 1
    return n


def parse_lab_rows(pages, record_type):
    """§13. Applies when record_type is lab_report/diagnostic_report or >= 3 lines carry number+unit."""
    lines_by_page = [page_lines(t, i) for i, t in enumerate(pages)]
    if record_type not in ("lab_report", "diagnostic_report") and count_unit_lines(lines_by_page) < 3:
        return []
    out = []
    for lines in lines_by_page:
        for ln in lines:
            it = parse_lab_line(ln)
            if it:
                out.append(it)
    kept = _dedup(out, lambda it: normalize_value("test_result", it["value_text"], it["value_json"]))
    return [_public(it) for it in kept]


def _public(it):
    return {"key": it["key"], "value_text": it["value_text"], "value_json": it["value_json"],
            "confidence": it["confidence"], "source_page": it["source_page"], "evidence": it["evidence"]}


# ---------------------------------------------------------------------------------------------
# §12 Fields
# ---------------------------------------------------------------------------------------------

_QUALIFICATIONS = frozenset(["mbbs", "md", "ms", "dm", "mch", "dnb", "mrcp", "frcs", "bds", "mds", "dgo", "dch",
                             "facc", "fics", "mrcog", "frcp", "dmrd", "dmrt", "phd", "bams", "bhms", "mph", "do"])
_NAME_CUT = frozenset(["consultant", "reg", "regd", "registration", "mci", "mobile", "ph", "phone", "timings",
                       "sr", "senior", "junior", "head", "hod", "prof", "professor", "associate", "assistant",
                       "department", "dept", "hospital", "clinic", "laboratories", "laboratory", "diagnostics",
                       "labs", "centre", "center", "pathology", "radiology", "signature", "signed", "date",
                       "the", "and", "for", "on", "at", "in", "of", "with"])
_RE_DR_PREFIX = re.compile("(?<![A-Za-z])(?:Dr|DR|dr)(?:\\.[ ]?|[ ])")
_RE_CAP_NAME = re.compile("[A-Z][A-Za-z'.-]*(?: [A-Z][A-Za-z'.-]*){0,5}")
_RE_DOCTOR_LABEL = re.compile("(?<![a-z])(consultant|consulting doctor|treating doctor|attending doctor|"
                              "doctor|physician|surgeon|referred by|referring doctor|ref\\.? ?by|ref\\.? doctor|"
                              "ref\\.? dr\\.?)[ ]?[:\\-][ ]?(?:dr\\.?[ ]?)?")
_RE_SPECIALTY = re.compile("(?<![a-z])(m\\.?b\\.?b\\.?s\\.?|m\\.?d\\.?|m\\.?s\\.?|d\\.?m\\.?|m\\.?ch\\.?|d\\.?n\\.?b\\.?|"
                           "mrcp|frcs|b\\.?d\\.?s\\.?|m\\.?d\\.?s\\.?|dgo|dch|facc|mrcog|frcp|dmrd|"
                           "cardiologist|physician|pediatrician|paediatrician|gyn(?:a)?ecologist|obstetrician|"
                           "orthop(?:a)?edic(?: surgeon)?|dermatologist|neurologist|endocrinologist|pathologist|"
                           "radiologist|general medicine|internal medicine|diabetologist|nephrologist|"
                           "pulmonologist|gastroenterologist|psychiatrist|ophthalmologist|oncologist|urologist|"
                           "surgeon)(?![a-z])")
_RE_FACILITY = re.compile("(?<![a-z])(?:hospitals?|clinics?|medical cent(?:er|re)|health cent(?:er|re)|"
                          "diagnostic cent(?:er|re)|diagnostics|laborator(?:y|ies)|labs|path ?labs|pathology|"
                          "imaging|scans|nursing home|polyclinic|healthcare|health care|institute|"
                          "medical college|pharmacy|chemists?|medicos|medicals)(?![a-z])")
_RE_FACILITY_SKIP = re.compile("(?<![a-z])(?:report|department|dept|referred|ref|consultant|patient|name|test|"
                               "result|reference|range|sample|specimen|collected|date|dr)(?![a-z])")
_RE_DEPT_OF = re.compile("(?<![a-z])(?:department|dept\\.?) of ([a-z][a-z&]*(?: [a-z&]+){0,3})(?![a-z])")
_RE_X_DEPT = re.compile("(?:^|[,;:|(-] ?)([a-z][a-z&]*(?: [a-z&]+){0,3}) department(?![a-z])")
_RE_PATIENT_STRONG = re.compile("(?<![a-z])(?:patient'?s? name|name of (?:the )?patient|pt\\.? name)[ ]?[:\\-][ ]?")
_RE_PATIENT_WEAK = re.compile("(?<![a-z])(?:patient|name)[ ]?[:\\-][ ]?")
_RE_TITLE = re.compile("(?<![a-z])(?:mr|mrs|ms|miss|master|mstr|baby|smt|shri|sri|kumari|kum)\\.?[ ]")
_RE_TITLE_AT = re.compile("(?:mr|mrs|ms|miss|master|mstr|baby|smt|shri|sri|kumari|kum)\\.?[ ]")
_RE_PERSON_WORDS = re.compile("[a-z][a-z.'-]*(?:,? [a-z][a-z.'-]*){0,5}")
_PATIENT_CUT = frozenset(["age", "sex", "gender", "uhid", "mrn", "id", "dob", "ref", "date", "reg", "no", "ip", "op",
                          "lab", "sample", "y", "yr", "yrs", "years", "m", "f", "male", "female", "bed", "ward",
                          "phone", "mobile", "d", "o", "b"])
_RE_PATIENT_CONTEXT = re.compile("(?<![a-z])(?:age|sex|gender|uhid|mrn|yrs?|years?|y/o)(?![a-z])|"
                                 "[0-9]{1,3} ?y(?:rs?)?(?: ?/ ?| )(?:m|f)(?![a-z])|[0-9]{1,3} ?/ ?(?:m|f)(?![a-z])")
_RE_AGE_LABEL = re.compile("(?<![a-z])age(?: ?/ ?(?:sex|gender))?[ ]?[:\\-]?[ ]?([0-9]{1,3})(?:[ ]?"
                           "(years?|yrs?|y|months?|mths?|mos?|days?))?(?![a-z0-9])")
_RE_AGE_SLASH = re.compile("(?<![0-9a-z])([0-9]{1,3})[ ]?(?:y|yrs?|years?)?[ ]?[/,|][ ]?(m|f|male|female)(?![a-z])")
_RE_AGE_YEARS = re.compile("(?<![0-9a-z])([0-9]{1,3})[ ]?(years?|yrs?)(?: old)?(?![a-z])")
_RE_SEX_LABEL = re.compile("(?<![a-z])(?:sex|gender)[ ]?[:\\-]?[ ]?(?:[0-9]{1,3}[ ]?(?:y|yrs?|years?)?[ ]?/[ ]?)?"
                           "(male|female|other|transgender|m|f)(?![a-z])")
_RE_REPORT_WORD = re.compile("(?<![a-z])report(?![a-z])")
_RE_PANEL_WORD = re.compile("(?<![a-z])(?:panel|profile|function tests?)(?![a-z])")
_RE_LEADING_LABEL = re.compile("[a-z][a-z ]{0,24}:[ ]?")

_SECTION_LABELS = [
    ("diagnosis", re.compile("(?:(?:provisional|final|clinical|working|discharge|differential) )?diagnos[ie]s"
                             "(?: on discharge)?|impression|dx")),
    ("symptom", re.compile("(?:(?:chief|presenting) )?complaints?|c/o|presenting symptoms?|symptoms?")),
    ("procedure", re.compile("(?:procedures?|operations?|surgery)(?: (?:done|performed))?")),
    ("recommendation", re.compile("advice(?: on discharge)?|discharge advice|advised|recommendations?|"
                                  "plan(?: of care)?|instructions|suggested|suggestions?|follow[ -]?up advice")),
    (None, re.compile("history(?: of present illness)?|hpi|past (?:medical )?history|examination|on examination|"
                      "o/e|vitals|investigations?|medications?|discharge medications?|treatment(?: given)?|rx|"
                      "course in (?:the )?hospital|hospital course|condition (?:at|on) discharge|findings|"
                      "technique|conclusion|interpretation|clinical (?:history|indication)|allergies|"
                      "comments?|notes?|remarks?|signature|medicines")),
]
_RE_BULLET = re.compile("(?:[0-9]{1,2}[.)][ ]?|[-•*·>][ ]?)")
_RE_GENERIC_LABEL = re.compile("[a-z][a-z0-9 /().&'-]{0,30}[ ]?:")
_RE_SIGNATURE = re.compile("^(?:dr\\.? |\\(dr)|(?<![a-z])(?:signature|signed|radiologist|pathologist|"
                           "end of report|authori[sz]ed signatory)(?![a-z])")
_SECTION_LIMIT = {"diagnosis": 120, "symptom": 80, "procedure": 120, "recommendation": 160}
_SECTION_CONF = {"diagnosis": 0.75, "symptom": 0.7, "procedure": 0.7, "recommendation": 0.7}
_RE_STANDALONE_REC = re.compile("(?:repeat|review|follow[ -]?up|consult) ")

_RE_MED_INDEX = "(?:[0-9]{1,2}[.)][ ]?|[-•*·>][ ]?|rx[ :.]+)?"
_RE_MED_FORM = re.compile("^" + _RE_MED_INDEX + "(tablets?|tabs?|capsules?|caps?|syrup|syp|syr|injection|inj|"
                          "ointment|oint|cream|gel|drops?|inhaler|sachets?|suspension|susp|lotion|spray|powder|"
                          "solution|soln|nebuli[sz]ation|neb|respules?)(?:\\.[ ]?|[ ]+)")
_FORM_CANON = [("tab", "tablet"), ("cap", "capsule"), ("sy", "syrup"), ("inj", "injection"), ("oint", "ointment"),
               ("cream", "cream"), ("gel", "gel"), ("drop", "drops"), ("inhaler", "inhaler"), ("sachet", "sachet"),
               ("susp", "suspension"), ("lotion", "lotion"), ("spray", "spray"), ("powder", "powder"),
               ("sol", "solution"), ("neb", "nebulisation"), ("respule", "respules")]
_RE_MED_FALLBACK = re.compile("^" + _RE_MED_INDEX + "([a-z][a-z0-9'-]*(?: [a-z][a-z0-9'-]*){0,3}) ")
_RE_STRENGTH = re.compile("(?<![a-z0-9.])[0-9]+(?:\\.[0-9]+)?(?:[ ]?[/+][ ]?[0-9]+(?:\\.[0-9]+)?)*[ ]?"
                          "(?:mg|mcg|μg|ug|gm|g|ml|iu|units?|meq|%)(?:[ ]?/[ ]?[0-9]*(?:\\.[0-9]+)?[ ]?(?:ml|g))?"
                          "(?![a-z0-9])")
_RE_FREQ = re.compile("(?<![a-z0-9/⁄])(?:[0-9](?:[/⁄][0-9])?[ ]?-[ ]?[0-9](?:[/⁄][0-9])?[ ]?-[ ]?[0-9](?:[/⁄][0-9])?"
                      "(?:[ ]?-[ ]?[0-9](?:[/⁄][0-9])?)?|[o0]d|bd|bid|tds|tid|qid|qds|hs|qhs|sos|prn|stat|qd|"
                      "once daily|twice daily|thrice daily|once a day|twice a day|thrice a day|three times a day|"
                      "four times a day|once weekly|weekly|daily|at night|every [0-9]{1,2} ?(?:hours|hrs|h)|"
                      "q[0-9]{1,2}h)(?![a-z0-9/⁄])")
_RE_DURATION = re.compile("(?:(?<![a-z])[x×*][ ]?[0-9]{1,3}[ ]?(?:days?|d|weeks?|wks?|w|months?|mths?|m)|"
                          "(?<![a-z0-9])for [0-9]{1,3} ?(?:days?|weeks?|wks?|months?|mths?)|"
                          "(?<![a-z0-9])[0-9]{1,3} ?(?:days|weeks|months))(?![a-z])")
_RE_INSTR = re.compile("(?<![a-z])(?:after food|before food|after meals?|before meals?|with food|with meals?|"
                       "on empty stomach|empty stomach|at bedtime|bedtime|after breakfast|before breakfast|"
                       "after lunch|after dinner|before dinner|with milk|with water|apply locally|"
                       "local application|if needed|when required|as needed|if required)(?![a-z])")
_RE_DOSE = re.compile("(?<![a-z0-9.])(?:[0-9]+(?:\\.[0-9]+)?|1⁄2)[ ]?(?:tabs?|tablets?|caps?|capsules?|puffs?|"
                      "drops?|sachets?|tsp|teaspoons?)(?![a-z])")
_MED_TYPES = frozenset(["prescription", "discharge_summary", "medication_list", "consultation_note"])


def _section_label(f):
    """Label line? -> (section or None, inline_start or None). inline_start is the offset of text after
    the label, None when the label stands alone."""
    b = _RE_BULLET.match(f)
    off = b.end() if b else 0
    for section, rx in _SECTION_LABELS:
        m = rx.match(f[off:])
        if not m:
            continue
        e = off + m.end()
        if e < len(f) and "a" <= f[e] <= "z":
            continue
        rest = f[e:]
        rs = rest.lstrip(" ")
        if rs == "" or rs in (":", "-", "–"):
            return (section, None)
        if rs[0] in ":-–":
            inline = e + (len(rest) - len(rs)) + 1
            while inline < len(f) and f[inline] == " ":
                inline += 1
            return (section, inline if inline < len(f) else None)
        if section == "symptom" and f[off:e] == "c/o" and rest.startswith(" "):
            return (section, e + 1)
    return None


def _is_stop_line(f):
    if not f:
        return True
    if _section_label(f) is not None:
        return True
    if _RE_GENERIC_LABEL.match(f):
        return True
    if _RE_SIGNATURE.search(f):
        return True
    return False


def _split_items(text, section):
    seps = ";" if section in ("diagnosis", "procedure", "recommendation") else ",;"
    out, start = [], 0
    for i, ch in enumerate(text + seps[0]):
        if i == len(text) or ch in seps:
            out.append((start, i))
            start = i + 1
    return out


def _clean_span(p, s, e):
    """Trim spaces, a leading bullet/index and trailing . , ; : - from p[s:e] -> (s, e)."""
    while s < e and p[s] == " ":
        s += 1
    b = _RE_BULLET.match(p[s:e])
    if b:
        s += b.end()
    while s < e and p[e - 1] in " .,;:-–":
        e -= 1
    while s < e and p[s] == " ":
        s += 1
    return s, e


def _field(key, value_text, value_json, conf, ln, start=0):
    return {"key": key, "value_text": value_text, "value_json": value_json, "confidence": conf,
            "source_page": ln.page, "evidence": ln.p, "pos": (ln.page, ln.index, start), "line": ln}


def _sections(lines_by_page, record_type):
    items = []
    for lines in lines_by_page:
        i = 0
        while i < len(lines):
            ln = lines[i]
            lab = _section_label(ln.f) if ln.f else None
            if lab is None or lab[0] is None:
                if (ln.f and record_type not in ("lab_report", "bill", "insurance")
                        and _RE_STANDALONE_REC.match(ln.f) and len(ln.p) <= 160):
                    items.append(_field("recommendation", ln.p.rstrip(" .;,"), None, 0.7, ln))
                i += 1
                continue
            section, inline = lab
            spans = []  # (line, start)
            if inline is not None:
                spans.append((ln, inline))
            j = i + 1
            if inline is None:
                while j < len(lines) and not lines[j].f:
                    j += 1  # blank lines right after a heading are skipped
            while j < len(lines) and len(spans) < 10:
                nl = lines[j]
                if _is_stop_line(nl.f):
                    break
                spans.append((nl, 0))
                j += 1
            for sl, st in spans:
                if section == "recommendation" and _RE_MED_FORM.match(sl.f):
                    continue
                for s, e in _split_items(sl.p[st:], section):
                    s, e = _clean_span(sl.p, st + s, st + e)
                    if e - s < 2 or e - s > _SECTION_LIMIT[section] or not re.search("[a-z]", sl.f[s:e]):
                        continue
                    items.append(_field(section, sl.p[s:e], None, _SECTION_CONF[section], sl, s))
            i = j
    return items


def _cut_name(p_name, cut_words):
    toks = p_name.split(" ")
    kept = []
    for tk in toks:
        bare = re.sub("[^a-z]", "", fold(tk))
        if bare in cut_words or (bare in _QUALIFICATIONS and tk.upper() == tk and len(bare) > 1):
            break
        kept.append(tk)
    while kept and kept[-1].rstrip(".-'") == "":
        kept.pop()
    value = " ".join(kept).rstrip(".-'")
    if not any(len(re.sub("[^a-z]", "", fold(tk))) >= 2 for tk in kept):
        return None
    return value


def _doctors(lines_by_page):
    head_ids = set((ln.page, ln.index) for ln in _head(lines_by_page[0])) if lines_by_page else set()
    cands = []  # (role, name, conf, line, start, end)
    for lines in lines_by_page:
        for ln in lines:
            if not ln.f:
                continue
            used = []
            for m in _RE_DOCTOR_LABEL.finditer(ln.f):
                role = "referrer" if m.group(1).startswith("ref") else None
                s = m.end()
                nm = _RE_CAP_NAME.match(ln.p[s:ln.cell_end(s)])
                if not nm:
                    continue
                if fold(nm.group(0)).startswith("self"):
                    used.append((m.start(), s + nm.end()))
                    continue
                name = _cut_name(nm.group(0), _NAME_CUT)
                used.append((m.start(), s + nm.end()))
                if name:
                    cands.append((role, name, 0.85, ln, s, s + len(name)))
            for m in _RE_DR_PREFIX.finditer(ln.p):
                if any(a <= m.start() < b for a, b in used):
                    continue
                s = m.end()
                nm = _RE_CAP_NAME.match(ln.p[s:ln.cell_end(s)])
                if not nm:
                    continue
                name = _cut_name(nm.group(0), _NAME_CUT)
                if name:
                    conf = 0.85 if (ln.page, ln.index) in head_ids else 0.7
                    cands.append((None, name, conf, ln, s, s + len(name)))
    items = []
    best = {}
    for c in cands:
        k = c[0] or ""
        if k not in best or c[2] > best[k][2]:
            best[k] = c
    ref = best.get("referrer")
    prim = best.get("")
    if ref is not None:
        items.append(_field("doctor_name", ref[1], {"role": "referrer"}, ref[2], ref[3], ref[4]))
    if prim is not None and not (ref is not None and norm_text(ref[1]) == norm_text(prim[1])):
        items.append(_field("doctor_name", prim[1], None, prim[2], prim[3], prim[4]))
        ln = prim[3]
        spec = _specialty_in(ln, prim[5])
        if spec is None:
            lines = [pg for pg in lines_by_page if pg and pg[0].page == ln.page][0]
            nxt = [x for x in lines[ln.index + 1:] if x.p][:1]
            if nxt:
                spec = _specialty_in(nxt[0], 0)
        if spec is not None:
            items.append(_field("doctor_specialty", spec[0], None, 0.7, spec[1], spec[2]))
    return items


def _specialty_in(ln, start):
    ms = []
    for m in _RE_SPECIALTY.finditer(ln.f[start:]):
        s, e = start + m.start(), start + m.end()
        printed = ln.p[s:e]
        letters = re.sub("[^a-z]", "", m.group(1))
        # Short qualifications (ms, md, dm, ...) count only when printed in capitals ("Ms." is a title).
        if len(letters) <= 3 and letters in _QUALIFICATIONS and printed.upper() != printed:
            continue
        ms.append((s, e))
    if not ms:
        return None
    # Value = printed span from the first to the last match on the line ("MBBS, MD").
    s, e = ms[0][0], ms[-1][1]
    if ln.p[s:e].count("(") > ln.p[s:e].count(")") and ln.p[e:e + 1] == ")":
        e += 1  # "MD (Internal Medicine)"
    return (ln.p[s:e].rstrip(" ,"), ln, s)


def _facility(lines_by_page):
    for pi, lines in enumerate(lines_by_page):
        for ln in _head(lines):
            if len(ln.p) > 80 or not _RE_FACILITY.search(ln.f):
                continue
            if _RE_FACILITY_SKIP.search(ln.f) or _RE_DR_PREFIX.match(ln.p):
                continue
            s = 0
            lab = _RE_LEADING_LABEL.match(ln.f)
            if lab:
                s = lab.end()  # "Hospital: City Care Hospital, Nagpur"
            value = ln.p[s:]
            kw = _RE_FACILITY.search(ln.f[s:])
            comma = value.find(",")
            if comma >= 0 and (kw is not None and kw.start() < comma or value.count(",") >= 3):
                value = value[:comma]  # the address after the name is dropped
            value = _title_case(value.strip(" ,.-|"), 2)
            if len(re.findall("[A-Za-z]", value)) < 3:
                continue
            return [_field("facility", value, None, 0.8 if pi == 0 else 0.6, ln, s)]
    return []


def _department(lines_by_page):
    for lines in lines_by_page:
        for ln in lines:
            if not ln.f:
                continue
            m = _RE_DEPT_OF.search(ln.f) or _RE_X_DEPT.search(ln.f)
            if m:
                s, e = m.start(1), m.end(1)
                return [_field("department", ln.p[s:e], None, 0.7, ln, s)]
    return []


def _person_name_at(ln, s):
    end = ln.cell_end(s)
    t = _RE_TITLE_AT.match(ln.f[s:end])
    if t:
        s += t.end()
    m = _RE_PERSON_WORDS.match(ln.f[s:end])
    if not m:
        return None
    toks_f = m.group(0).split(" ")
    kept = 0
    for tk in toks_f:
        if re.sub("[^a-z]", "", tk) in _PATIENT_CUT:
            break
        kept += 1
    if kept == 0:
        return None
    length = len(" ".join(toks_f[:kept]))
    value = ln.p[s:s + length].rstrip(" .,-'")
    if len(re.findall("[A-Za-z]", value)) < 2:
        return None
    return value, s


def _patient(lines_by_page):
    if not lines_by_page:
        return []
    head = _head(lines_by_page[0])
    items = []

    def context(i):
        for k in (i - 1, i, i + 1):
            if 0 <= k < len(head) and _RE_PATIENT_CONTEXT.search(head[k].f):
                return True
        return False

    for i, ln in enumerate(head):
        found = None
        m = _RE_PATIENT_STRONG.search(ln.f)
        if m:
            found = _person_name_at(ln, m.end())
        if found is None and context(i):
            m = _RE_PATIENT_WEAK.search(ln.f)
            if m and not _RE_PATIENT_STRONG.search(ln.f):
                found = _person_name_at(ln, m.end())
            if found is None:
                m = _RE_TITLE.search(ln.f)
                if m:
                    found = _person_name_at(ln, m.start())
        if found:
            items.append(_field("patient_name", found[0], None, 0.75, ln, found[1]))
            break
    for ln in head:
        m = _RE_AGE_LABEL.search(ln.f) or _RE_AGE_SLASH.search(ln.f)
        if m is None and _RE_PATIENT_CONTEXT.search(ln.f):
            m = _RE_AGE_YEARS.search(ln.f)
        if m:
            n = int(m.group(1))
            unit = m.group(2) if m.re is not _RE_AGE_SLASH else None
            if n <= 120:
                if unit and unit[0] == "m":
                    txt = "%d months" % n
                elif unit and unit[0] == "d":
                    txt = "%d days" % n
                else:
                    txt = "%d years" % n
                items.append(_field("patient_age", txt, None, 0.75, ln, m.start()))
                break
    for ln in head:
        m = _RE_SEX_LABEL.search(ln.f)
        sex = m.group(1) if m else None
        if m is None:
            m2 = _RE_AGE_SLASH.search(ln.f)
            if m2:
                m, sex = m2, m2.group(2)
        if m:
            v = {"m": "male", "male": "male", "f": "female", "female": "female"}.get(sex, "other")
            items.append(_field("patient_sex", v, None, 0.75, ln, m.start()))
            break
    return items


def _title_case(p, min_letters=4):
    """Tokens made of [A-Z'.-] with >= min_letters letters become
    'Xxxx' (first letter kept, rest lowercased); and/of/the/for/with/in/on/to after the first token become
    lowercase only when the token was all caps; everything else (CBC, (CBC), T3, HbA1c, &) is kept.
    Report names use 4, facilities 2 ("ST." -> "St.")."""
    small = frozenset(["and", "of", "the", "for", "with", "in", "on", "to"])
    out = []
    for i, tk in enumerate(p.split(" ")):
        lo = tk.lower()
        if i > 0 and lo in small and tk.upper() == tk:
            out.append(lo)
        elif re.fullmatch("[A-Z'.-]+", tk) and len(re.findall("[A-Z]", tk)) >= min_letters:
            out.append(tk[0] + tk[1:].lower())
        else:
            out.append(tk)
    return " ".join(out)


def _report_name(lines_by_page, facility_line):
    if not lines_by_page:
        return []
    rt = record_types()
    panel = [r["_re"] for t in rt["types"] if t["id"] in ("lab_report", "imaging_report", "diagnostic_report")
             for r in t["rules"] if r["weight"] >= 3]
    head = _head(lines_by_page[0])
    fallback = None
    for ln in head:
        if _section_label(ln.f) is not None:
            break  # the report name precedes the first section (Plan:, Impression:, Interpretation: ...)
        if not ("a" <= ln.f[0] <= "z"):
            continue
        if ln is facility_line or _is_header_line(ln.f) or len(ln.p.split(" ")) > 8:
            continue
        if parse_lab_line(ln) is not None or _section_label(ln.f) is not None or _RE_GENERIC_LABEL.match(ln.f):
            continue
        if ":" in ln.f or _date_candidates(ln.f, _dt.date(2100, 1, 1), "dmy"):
            continue
        if any(rx.search(ln.f) for rx in panel) or _RE_PANEL_WORD.search(ln.f):
            return [_field("report_name", _title_case(ln.p.strip(" .:-")), None, 0.8, ln)]
        if (fallback is None and _RE_REPORT_WORD.search(ln.f) and len(ln.p.split(" ")) <= 6
                and not re.search("[0-9]", ln.f)):
            fallback = ln
    if fallback is not None:
        return [_field("report_name", _title_case(fallback.p.strip(" .:-")), None, 0.7, fallback)]
    return []


def _medication_line(ln):
    f, p = ln.f, ln.p
    m = _RE_MED_FORM.match(f)
    form = None
    if m:
        form_word = m.group(1)
        for prefix, canon in _FORM_CANON:
            if form_word.startswith(prefix):
                form = canon
                break
        rest_start = m.end()
    else:
        fb = _RE_MED_FALLBACK.match(f)
        if not fb:
            return None
        rest_start = fb.start(1)
    rest = f[rest_start:]
    found = {}
    for name, rx in (("strength", _RE_STRENGTH), ("frequency", _RE_FREQ), ("duration", _RE_DURATION),
                     ("instructions", _RE_INSTR), ("dose", _RE_DOSE)):
        mm = rx.search(rest)
        if mm:
            found[name] = (rest_start + mm.start(), rest_start + mm.end())
    if form is None:
        # Fallback lines need a strength directly after the name AND a frequency/duration/instruction.
        s = found.get("strength")
        if s is None or not any(k in found for k in ("frequency", "duration", "instructions")):
            return None
        if s[0] != rest_start + len(fb.group(1)) + 1 or f[s[0]:s[1]].endswith("%"):
            return None
    # Overlaps: a dose inside a strength span, frequency inside a duration, etc. are dropped.
    spans = sorted(found.items(), key=lambda kv: kv[1][0])
    clean = {}
    last = -1
    for k, (s, e) in spans:
        if s >= last:
            clean[k] = (s, e)
            last = e
    stop = min([s for s, _ in clean.values()] + [len(f)])
    for sep in (" - ", " (", " — ", " – "):
        q = f.find(sep, rest_start)
        if 0 <= q < stop:
            stop = q
    name = p[rest_start:stop].strip(" -–:,.(")
    if len(re.findall("[A-Za-z]", name)) < 2 or len(name) > 60 or len(name.split(" ")) > 6:
        return None
    vals = dict((k, p[s:e]) for k, (s, e) in clean.items())
    if form in ("syrup", "suspension", "drops", "solution") and "strength" in vals and "dose" not in vals:
        if fold(vals["strength"]).replace(" ", "").endswith("ml") and "/" not in vals["strength"]:
            vals["dose"] = vals.pop("strength")
    vj = {"name": name, "strength": vals.get("strength"), "form": form, "dose": vals.get("dose"),
          "frequency": vals.get("frequency"), "duration": vals.get("duration"),
          "instructions": vals.get("instructions")}
    conf = 0.8 if (vj["strength"] or vj["frequency"]) else 0.6
    return _field("medication", name, vj, conf, ln)


def _medications(lines_by_page, record_type):
    if record_type not in _MED_TYPES:
        return []
    out = []
    for lines in lines_by_page:
        for ln in lines:
            if ln.f:
                it = _medication_line(ln)
                if it:
                    out.append(it)
    return out


def _fields_items(lines_by_page, record_type):
    items = []
    items += _doctors(lines_by_page)
    fac = _facility(lines_by_page)
    items += fac
    items += _department(lines_by_page)
    items += _patient(lines_by_page)
    fac_line = fac[0]["line"] if fac else None
    if record_type in ("lab_report", "imaging_report", "diagnostic_report", "other"):
        items += _report_name(lines_by_page, fac_line)
    items += _sections(lines_by_page, record_type)
    items += _medications(lines_by_page, record_type)
    return _dedup(items, lambda it: (it["key"], (it["value_json"] or {}).get("role"),
                                     normalize_value(it["key"], it["value_text"], it["value_json"])))


def extract_fields(pages, record_type):
    """§12. Non-date, non-lab-row fields. Returns items ordered by position."""
    lines_by_page = [page_lines(t, i) for i, t in enumerate(pages)]
    return [_public(it) for it in _fields_items(lines_by_page, record_type)]


# ---------------------------------------------------------------------------------------------
# §14 Boundaries
# ---------------------------------------------------------------------------------------------

_RE_PAGE_OF = re.compile("(?<![a-z0-9])(?:page|pg\\.?)[ ]?([0-9]{1,3})[ ]?(?:of|/)[ ]?([0-9]{1,3})(?![0-9])")
_RE_BARE_OF = re.compile("([0-9]{1,3}) ?(?:of|/) ?([0-9]{1,3})")
_RE_CONTINUED = re.compile("(?:continued|contd|cont'd|cont\\.)(?![a-z])")


def _page_numbering(lines):
    """(n, m) from 'page n of m' in the first or last 3 non-empty lines (or such a line that is only
    'n of m'); None when absent."""
    ne = _nonempty(lines)
    zone = ne[:3] + [ln for ln in ne[-3:] if ln not in ne[:3]]
    for ln in zone:
        m = _RE_PAGE_OF.search(ln.f)
        if m:
            return int(m.group(1)), int(m.group(2))
        m = _RE_BARE_OF.fullmatch(ln.f)
        if m:
            return int(m.group(1)), int(m.group(2))
    return None


def _letters(lines):
    return sum(len(re.findall("[a-z]", ln.f)) for ln in lines)


def _head_words(lines):
    ws = set()
    for ln in _nonempty(lines)[:5]:
        ws.update(words(ln.f))
    return ws


def _jaccard(a, b):
    if not a and not b:
        return 1.0
    return len(a & b) / float(len(a | b))


def detect_boundaries(pages, today, date_order):
    """§14. Returns {page_scores:[{page, score, signals}], segments:[...], propose: bool}."""
    today_d = _dt.date.fromisoformat(today)
    lps = [page_lines(t, i) for i, t in enumerate(pages)]
    n = len(pages)
    info = []
    for lines in lps:
        head_ids = set(ln.index for ln in _head(lines))
        ptype = _page_best_type(lines)
        fields = _fields_items([lines], ptype or "other")
        names = set(norm_text(it["value_text"]) for it in fields if it["key"] == "patient_name")
        rname = [norm_text(it["value_text"]) for it in fields if it["key"] == "report_name"]
        # Follow-up dates are excluded: a continuation page often carries only the follow-up date.
        dates = set(it["date"] for it in _date_items([lines], ptype or "other", today_d, date_order)
                    if it["line"].index in head_ids and it["key"] != "follow_up_date")
        info.append({"type": ptype, "names": names, "report": rname[0] if rname else None, "dates": dates,
                     "numbering": _page_numbering(lines), "letters": _letters(lines),
                     "head": _head_words(lines), "first": (_nonempty(lines)[0].f if _nonempty(lines) else "")})
    scores = []
    starts = [0]
    if n >= 3:
        seg = {"type": info[0]["type"], "names": set(info[0]["names"]), "dates": set(info[0]["dates"]),
               "report": info[0]["report"]}
        for i in range(1, n):
            cur, prev = info[i], info[i - 1]
            score, signals = 0, []
            if cur["numbering"] and cur["numbering"][0] == 1:
                score += 3
                signals.append("page_one_of")
            if prev["numbering"] and prev["numbering"][0] == prev["numbering"][1] and prev["numbering"][0] >= 1:
                score += 2
                signals.append("previous_last_page")
            if cur["type"] and seg["type"] and cur["type"] != seg["type"]:
                score += 3
                signals.append("type_change")
            if _jaccard(cur["head"], prev["head"]) < 0.3:
                score += 2
                signals.append("head_change")
            if ((cur["names"] and seg["names"] and not (cur["names"] & seg["names"]))
                    or (cur["dates"] and seg["dates"] and not (cur["dates"] & seg["dates"]))):
                score += 2
                signals.append("patient_or_date_change")
            if cur["report"] and seg["report"] and cur["report"] != seg["report"]:
                score += 2
                signals.append("report_name_change")
            if prev["letters"] < 20:
                score += 1
                signals.append("previous_blank")
            if (cur["numbering"] and cur["numbering"][0] > 1) or _RE_CONTINUED.match(cur["first"]):
                score -= 3
                signals.append("continuation")
            scores.append({"page": i, "score": score, "signals": signals})
            if score >= 5:
                starts.append(i)
                seg = {"type": cur["type"], "names": set(cur["names"]), "dates": set(cur["dates"]),
                       "report": cur["report"]}
            else:
                if seg["type"] is None:
                    seg["type"] = cur["type"]  # the segment keeps its first confident type
                seg["names"] |= cur["names"]
                seg["dates"] |= cur["dates"]
                if seg["report"] is None:
                    seg["report"] = cur["report"]
    segments = []
    break_scores = dict((s["page"], s["score"]) for s in scores)
    for k, s in enumerate(starts):
        e = (starts[k + 1] - 1) if k + 1 < len(starts) else n - 1
        cls = classify(pages[s:e + 1])
        fl = extract_fields(pages[s:e + 1], cls["record_type"])
        rn = [it["value_text"] for it in fl if it["key"] == "report_name"]
        fac = [it["value_text"] for it in fl if it["key"] == "facility"]
        label = TYPE_LABEL[cls["record_type"]]
        title = rn[0] if rn else (label + " — " + fac[0] if fac else label)
        if s in break_scores:
            sc = break_scores[s]
        elif len(starts) > 1:
            sc = break_scores[starts[1]]
        else:
            sc = 0
        segments.append({"page_start": s, "page_end": e, "record_type": cls["record_type"], "title": title,
                         "confidence": round2(min(0.95, 0.5 + 0.05 * sc))})
    return {"page_scores": scores, "segments": segments, "propose": len(segments) >= 2}


# ---------------------------------------------------------------------------------------------
# §15 Highlights and review
# ---------------------------------------------------------------------------------------------

_HL_SUFFIX = {"low": "below the report reference range", "high": "above the report reference range",
              "critical_low": "marked critical on the report", "critical_high": "marked critical on the report",
              "abnormal": "marked abnormal on the report"}


def _distance(vj):
    v, lo, hi, f = vj.get("value_num"), vj.get("ref_low"), vj.get("ref_high"), vj.get("flag")
    if v is None:
        return 0.0
    if f in ("low", "critical_low") and lo is not None:
        return (lo - v) / abs(lo) if lo != 0 else (lo - v)
    if f in ("high", "critical_high") and hi is not None:
        return (v - hi) / abs(hi) if hi != 0 else (v - hi)
    return 0.0


def build_highlights(fields, summary=None):
    """§15. fields: rows/items {key, value_text, value_json, confidence, source_page, state?}. Rejected
    rows are ignored. summary: validated AI summary {text, source_pages} or None."""
    important, meds, recs = [], [], []
    for idx, fd in enumerate(fields):
        if fd.get("state") == "rejected":
            continue
        vj = fd.get("value_json") or {}
        if fd["key"] == "test_result" and vj.get("flag") in _HL_SUFFIX:
            text ="%s: %s" % (vj.get("name") or fd["value_text"], vj.get("value"))
            if vj.get("unit"):
                text += " " + vj["unit"]
            text += " — " + _HL_SUFFIX[vj["flag"]]
            rank = 0 if vj["flag"].startswith("critical") else (1 if vj["flag"] in ("low", "high") else 2)
            important.append((rank, -_distance(vj), idx, text, fd))
        elif fd["key"] == "medication":
            text = vj.get("name") or fd["value_text"]
            if vj.get("strength"):
                text += " " + vj["strength"]
            for k in ("frequency", "duration"):
                if vj.get(k):
                    text += " · " + vj[k]
            meds.append((idx, text, fd))
        elif fd["key"] == "recommendation":
            recs.append((idx, fd["value_text"], fd))
    important.sort(key=lambda t: (t[0], t[1], t[2]))
    out = []

    def emit(section, rows, limit):
        seen = set()
        pos = 0
        for idx, text, fd in rows:
            if text in seen or pos >= limit:
                continue
            seen.add(text)
            out.append({"section": section, "text": text, "position": pos, "field_index": idx,
                        "source_page": fd.get("source_page"), "confidence": fd.get("confidence")})
            pos += 1

    emit("important", [(t[2], t[3], t[4]) for t in important], 8)
    emit("medications", meds, 10)
    emit("recommendations", recs, 5)
    if summary and summary.get("text"):
        text = summary["text"]
        if len(text) > 400:
            text = text[:399] + "…"
        pages = summary.get("source_pages") or []
        out.append({"section": "summary", "text": text, "position": 0, "field_index": None,
                    "source_page": pages[0] if pages else None, "confidence": None})
    return out


_KEY_FIELDS = frozenset(["report_name", "doctor_name", "facility"] + DATE_KEYS)


def conflicts(rows):
    """Single-valued keys with >= 2 distinct normalized values among non-rejected rows. doctor_name
    rows with value_json.role = 'referrer' form their own slot. Returns sorted slot names."""
    vals = {}
    for r in rows:
        if r.get("state") == "rejected" or r["field_key"] in MULTI_VALUED_KEYS:
            continue
        role = (r.get("value_json") or {}).get("role") if isinstance(r.get("value_json"), dict) else None
        slot = r["field_key"] + (":" + role if role else "")
        vals.setdefault(slot, set()).add(normalize_value(r["field_key"], r["value_text"], r.get("value_json")))
    return sorted(k for k, v in vals.items() if len(v) >= 2)


def review_status(record, rows, pending_split=False, pending_duplicate=False):
    """§15 review. record: {record_type, type_confidence, type_method, document_date, processing_error,
    review_status}. rows: record_fields rows {field_key, value_text, value_json, confidence, state,
    from_image?}. Returns {status, reasons}."""
    reasons = []
    reviewed = record.get("review_status") == "reviewed"
    if not reviewed:
        if record.get("type_method") != "user" and (record.get("record_type") == "other"
                                                     or (record.get("type_confidence") or 0) < 0.7):
            reasons.append("type_uncertain")
        if not record.get("document_date"):
            reasons.append("no_date")
    for slot in conflicts(rows):
        key = slot.split(":")[0]
        states = [r["state"] for r in rows if r["field_key"] == key and r["state"] != "rejected"]
        if "suggested" in states:
            reasons.append("conflict:" + slot)
    low = sorted(set(r["field_key"] for r in rows if r["field_key"] in _KEY_FIELDS and r["state"] == "suggested"
                     and r["confidence"] < 0.8))
    reasons += ["low_confidence:" + k for k in low]
    if pending_split:
        reasons.append("split_pending")
    if pending_duplicate:
        reasons.append("duplicate_pending")
    if not reviewed and record.get("processing_error") in ("text_unavailable", "ocr_failed"):
        reasons.append("text_failed")
    # Mean OCR line confidence of the record's OCR pages (NULL when no page was OCR'd or the engine gives none).
    if not reviewed and record.get("ocr_confidence") is not None and record["ocr_confidence"] < 0.6:
        reasons.append("ocr_low_confidence")
    if any(r.get("from_image") and r["state"] == "suggested" for r in rows):
        reasons.append("ai_image_item")
    if reasons:
        return {"status": "needs_review", "reasons": reasons}
    return {"status": "reviewed" if reviewed else "none", "reasons": []}


# ---------------------------------------------------------------------------------------------
# §8.1 applyExtraction
# ---------------------------------------------------------------------------------------------

def _best_row(rows, key):
    cands = [(i, r) for i, r in enumerate(rows) if r["field_key"] == key and r["state"] != "rejected"
             and (r["state"] in ("user", "confirmed") or r["confidence"] >= 0.6)]
    rank = {"user": 0, "confirmed": 1, "suggested": 2}
    cands.sort(key=lambda t: (rank[t[1]["state"]], -t[1]["confidence"], t[0]))
    return cands[0][1] if cands else None


def apply_extraction(existing_rows, new_items, record=None, classification=None):
    """§8.1 write rule. new_items: {key, value_text, value_json, method, confidence, source_page,
    evidence, source_bbox?}. Returns {rows, actions, conflicts, record}. Inserted ids are 'new-1', 'new-2', ...
    (ports substitute generated UUIDs in insertion order)."""
    rows = [dict(r) for r in existing_rows]
    actions = []
    inserted = 0
    for it in new_items:
        nv = normalize_value(it["key"], it["value_text"], it.get("value_json"))
        match = [r for r in rows if r["field_key"] == it["key"]
                 and normalize_value(r["field_key"], r["value_text"], r.get("value_json")) == nv]
        locked = [r for r in match if r["state"] in ("rejected", "confirmed", "user")]
        if locked:
            actions.append({"action": "skip_" + locked[0]["state"], "id": locked[0]["id"]})
            continue
        if match:
            r = match[0]
            if it["confidence"] > r["confidence"]:
                for k_new, k_row in (("value_text", "value_text"), ("value_json", "value_json"), ("method", "method"),
                                     ("evidence", "evidence"), ("source_page", "source_page"),
                                     ("source_bbox", "source_bbox"), ("confidence", "confidence")):
                    r[k_row] = it.get(k_new)
                actions.append({"action": "update", "id": r["id"]})
            else:
                actions.append({"action": "keep", "id": r["id"]})
            continue
        inserted += 1
        row = {"id": "new-%d" % inserted, "field_key": it["key"], "value_text": it["value_text"],
               "value_json": it.get("value_json"), "method": it["method"], "confidence": it["confidence"],
               "state": "suggested", "source_page": it.get("source_page"), "source_bbox": it.get("source_bbox"),
               "evidence": it.get("evidence")}
        rows.append(row)
        actions.append({"action": "insert", "id": row["id"]})
    result = {"rows": rows, "actions": actions, "conflicts": conflicts(rows), "record": None}
    if record is not None:
        result["record"] = derive_record(record, rows, classification)
    return result


def derive_record(record, rows, classification=None):
    """§8.1 record columns. record: {title, title_is_derived, record_type, category, type_confidence,
    type_method, document_date, document_date_precision, document_date_method, sort_date}.
    classification: list of {record_type, confidence, method} (rules classifier and/or AI)."""
    rec = dict(record)
    if classification and rec.get("type_method") != "user":
        best = None
        for c in classification:
            if best is None or c["confidence"] > best["confidence"]:
                best = c
        rec["record_type"] = best["record_type"]
        rec["category"] = DEFAULT_CATEGORY[best["record_type"]]
        rec["type_confidence"] = best["confidence"]
        rec["type_method"] = best["method"]
    if rec.get("document_date_method") in (None, "import_time", "file_metadata"):
        for key in DOCUMENT_DATE_ORDER:
            r = _best_row(rows, key)
            if r is not None:
                rec["document_date"] = r["value_text"]
                vj = r.get("value_json") or {}
                rec["document_date_precision"] = vj.get("precision") or "day"
                rec["document_date_method"] = r["method"]
                rec["sort_date"] = r["value_text"]
                break
    if rec.get("title_is_derived"):
        rn = _best_row(rows, "report_name")
        fac = _best_row(rows, "facility")
        if rn is not None:
            rec["title"] = rn["value_text"]
        elif fac is not None:
            rec["title"] = TYPE_LABEL[rec.get("record_type") or "other"] + " — " + fac["value_text"]
    return rec


# ---------------------------------------------------------------------------------------------
# §9.3 AI chunking and validation
# ---------------------------------------------------------------------------------------------

AI_CHUNK_CHARS = {"cloud": 12000, "local": 2500}


def ai_chunks(pages, mode):
    """Pages rendered as '=== Page N ===\\n<text>' (N 1-based, text = pfold of the page, blank lines
    kept) joined by '\\n\\n', packed greedily into chunks of <= limit code points. A page longer than the
    limit is split at line breaks into consecutive blocks that repeat its header; a single line longer
    than the limit is hard-cut. Empty pages are skipped. Returns [{pages:[N...], text}]."""
    limit = AI_CHUNK_CHARS[mode]
    blocks = []
    for i, t in enumerate(pages):
        body = pfold(t or "").strip("\n")
        if not body.strip():
            continue
        header = "=== Page %d ===\n" % (i + 1)
        room = limit - len(header)
        cur = ""
        for line in body.split("\n"):
            while len(line) > room:
                if cur:
                    blocks.append((i + 1, header + cur))
                    cur = ""
                blocks.append((i + 1, header + line[:room]))
                line = line[room:]
            cand = line if not cur else cur + "\n" + line
            if len(cand) > room:
                blocks.append((i + 1, header + cur))
                cur = line
            else:
                cur = cand
        if cur:
            blocks.append((i + 1, header + cur))
    chunks = []
    for pno, text in blocks:
        if chunks and len(chunks[-1]["text"]) + 2 + len(text) <= limit:
            chunks[-1]["text"] += "\n\n" + text
            if chunks[-1]["pages"][-1] != pno:
                chunks[-1]["pages"].append(pno)
        else:
            chunks.append({"pages": [pno], "text": text})
    return chunks


def lenient_json(raw):
    """Existing lenient extractor semantics: if raw is already an object use it; else strip everything
    before the first '{' and after the last '}' and parse. Returns dict or None."""
    if isinstance(raw, dict):
        return raw
    if not isinstance(raw, str):
        return None
    a, b = raw.find("{"), raw.rfind("}")
    if a < 0 or b <= a:
        return None
    try:
        v = json.loads(raw[a:b + 1])
    except ValueError:
        return None
    return v if isinstance(v, dict) else None


_WS = re.compile("[ \t\n]+")
_RE_NUMS = re.compile("[0-9]+(?:[.,][0-9]+)*")
_AI_FIELD_KEYS = frozenset(["doctor_name", "doctor_specialty", "facility", "department", "patient_name",
                            "patient_age", "patient_sex", "diagnosis", "symptom", "procedure", "recommendation",
                            "document_time"])
_AI_DATE_KEYS = frozenset(DATE_KEYS)


def _flat(s):
    return _WS.sub(" ", fold(s or "")).strip(" ")


def _numbers(s):
    """Number tokens of folded text with thousands commas removed: '1,50,000' -> '150000'."""
    return [x.replace(",", "") for x in _RE_NUMS.findall(fold(s or ""))]


def _nums_in(value, evidence):
    ev = set(_numbers(evidence))
    return all(n in ev for n in _numbers(value))


def _conf(x):
    try:
        v = float(x)
    except (TypeError, ValueError):
        return 0.5
    if v != v:
        return 0.5
    return max(0.0, min(0.9, v))


def validate_ai(pages, ai_json, mode, today, date_order, image_pages=None):
    """§9.3 ExtractionValidator. pages: page texts (OCR text for image pages, '' when none);
    image_pages: 1-based page numbers that were sent as images. Returns
    {record_type, report_name, items, summary, dropped, error}. Items use applyExtraction's shape with
    method ai_local/ai_cloud, 0-based source_page and from_image."""
    method = "ai_local" if mode == "local" else "ai_cloud"
    image_pages = set(image_pages or [])
    today_d = _dt.date.fromisoformat(today)
    data = lenient_json(ai_json)
    res = {"record_type": None, "report_name": None, "items": [], "summary": None, "dropped": [], "error": None}
    if data is None:
        res["error"] = "parse_error"
        return res
    flat_pages = [_flat(p) for p in pages]

    def drop(kind, i, reason):
        res["dropped"].append({"kind": kind, "index": i, "reason": reason})

    def page_of(kind, i, item):
        sp = item.get("source_page")
        if sp is None or isinstance(sp, bool):
            drop(kind, i, "missing_source_page")
            return None
        if not isinstance(sp, int) or sp < 1 or sp > len(pages):
            drop(kind, i, "bad_source_page")
            return None
        return sp

    def evidence_ok(kind, i, item, sp):
        ev = item.get("evidence")
        if not isinstance(ev, str) or not _flat(ev):
            drop(kind, i, "evidence_not_found")
            return None
        text = flat_pages[sp - 1]
        if not text and sp in image_pages:
            return "image_only"
        if _flat(ev) not in text:
            drop(kind, i, "evidence_not_found")
            return None
        return "ok"

    def contains(value, ev):
        nv = norm_text(value)
        return bool(nv) and (" " + nv + " ") in (" " + norm_text(ev) + " ")

    def finish(item, sp, status, conf):
        from_image = sp in image_pages
        if status == "image_only":
            conf = min(conf, 0.5)
        item.update({"method": method, "confidence": conf, "source_page": sp - 1, "from_image": from_image})
        res["items"].append(item)

    rt = data.get("record_type")
    if isinstance(rt, str) and rt in RECORD_TYPES:
        res["record_type"] = {"record_type": rt, "confidence": _conf(data.get("record_type_confidence")),
                              "method": method}
    rn = data.get("report_name")
    if isinstance(rn, str) and norm_text(rn):
        for pi, p in enumerate(pages):
            if contains(rn, p):
                res["report_name"] = {"key": "report_name", "value_text": _WS.sub(" ", rn).strip(), "value_json": None,
                                      "method": method, "confidence": 0.7, "source_page": pi, "evidence": None,
                                      "from_image": (pi + 1) in image_pages}
                break

    for i, d in enumerate(data.get("dates") or []):
        if not isinstance(d, dict):
            drop("dates", i, "bad_item")
            continue
        if d.get("key") not in _AI_DATE_KEYS:
            drop("dates", i, "unknown_key")
            continue
        sp = page_of("dates", i, d)
        if sp is None:
            continue
        st = evidence_ok("dates", i, d, sp)
        if st is None:
            continue
        v = d.get("value")
        m = re.fullmatch("([0-9]{4})-([0-9]{2})(?:-([0-9]{2}))?", v) if isinstance(v, str) else None
        dv = _valid(int(m.group(1)), int(m.group(2)), int(m.group(3) or 1)) if m else None
        if dv is None or not _plausible(dv, d["key"], today_d):
            drop("dates", i, "bad_date")
            continue
        precision = "day" if m.group(3) else "month"
        found = False
        for c in _date_candidates(fold(d["evidence"]), today_d, date_order, both_orders=True):
            if dv in c["dates"] and (c["precision"] == precision):
                found = True
        if not found:
            drop("dates", i, "date_not_in_evidence")
            continue
        finish({"key": d["key"], "value_text": dv.isoformat(), "value_json": {"precision": precision},
                "evidence": _WS.sub(" ", d["evidence"]).strip()}, sp, st, _conf(d.get("confidence")))

    for i, fd in enumerate(data.get("fields") or []):
        if not isinstance(fd, dict):
            drop("fields", i, "bad_item")
            continue
        if fd.get("key") not in _AI_FIELD_KEYS:
            drop("fields", i, "unknown_key")
            continue
        sp = page_of("fields", i, fd)
        if sp is None:
            continue
        st = evidence_ok("fields", i, fd, sp)
        if st is None:
            continue
        v = fd.get("value")
        if not isinstance(v, str) or not norm_text(v):
            drop("fields", i, "empty_value")
            continue
        if not _nums_in(v, fd["evidence"]):
            drop("fields", i, "number_not_in_evidence")
            continue
        if fd["key"] != "patient_sex" and not contains(v, fd["evidence"]):
            drop("fields", i, "value_not_in_evidence")
            continue
        finish({"key": fd["key"], "value_text": _WS.sub(" ", v).strip(), "value_json": None,
                "evidence": _WS.sub(" ", fd["evidence"]).strip()}, sp, st, _conf(fd.get("confidence")))

    for i, tr in enumerate(data.get("test_results") or []):
        if not isinstance(tr, dict):
            drop("test_results", i, "bad_item")
            continue
        sp = page_of("test_results", i, tr)
        if sp is None:
            continue
        st = evidence_ok("test_results", i, tr, sp)
        if st is None:
            continue
        name, value, ev = tr.get("name"), tr.get("value"), tr.get("evidence")
        if not isinstance(name, str) or not norm_text(name) or not isinstance(value, str) or not value.strip():
            drop("test_results", i, "empty_value")
            continue
        if not contains(name, ev):
            drop("test_results", i, "value_not_in_evidence")
            continue
        if not _numbers(value) and not contains(value, ev):
            drop("test_results", i, "value_not_in_evidence")
            continue
        if not _nums_in(value, ev):
            drop("test_results", i, "number_not_in_evidence")
            continue
        vm = _RE_VALUE_NUM.fullmatch(fold(value).strip())
        ref_text = tr.get("ref_text") if isinstance(tr.get("ref_text"), str) and tr.get("ref_text").strip() else None
        if ref_text is not None and not _nums_in(ref_text, ev):
            ref_text = None
        lo = hi = None
        if ref_text is not None:
            rf = fold(ref_text).strip(" ()[]")
            for rx, kind in ((_RE_REF_RANGE, "range"), (_RE_REF_HIGH, "high"), (_RE_REF_LOW, "low")):
                fm = rx.fullmatch(rf)
                if fm:
                    tmp = {"ref_low": None, "ref_high": None}
                    _set_ref(tmp, fm, kind)
                    lo, hi = tmp["ref_low"], tmp["ref_high"]
                    break
        unit = None
        if isinstance(tr.get("unit"), str) and tr["unit"].strip():
            u = match_unit(fold(tr["unit"]).strip(), 0)
            if u and u[1] == len(fold(tr["unit"]).strip()):
                unit = u[0]
            elif norm_text(tr["unit"]) and fold(tr["unit"]).strip() in fold(ev):
                unit = _WS.sub(" ", tr["unit"]).strip()
        # Flag: never the model's opinion. Explicit marker printed after the value in the evidence, else
        # computed from the printed range, else unknown.
        tail = fold(ev)
        vf = fold(value).strip()
        # The value occurrence after the printed name whose neighbours are not digits or '.'.
        kn = tail.find(_WS.sub(" ", fold(name)).strip())
        k = tail.find(vf, kn + len(fold(name).strip()) if kn >= 0 else 0)
        while k >= 0 and ((k > 0 and tail[k - 1] in "0123456789.") or
                          (k + len(vf) < len(tail) and tail[k + len(vf)] in "0123456789.")):
            k = tail.find(vf, k + 1)
        flag_raw = None
        if k >= 0:
            after = tail[k + len(vf):].lstrip(" ")
            fmm = _RE_FLAG.match(after) or (_RE_FLAG_ATTACHED.match(tail[k + len(vf):]))
            if fmm:
                flag_raw = fmm.group(1)
        tmp = {"flag_raw": flag_raw, "ref_low": lo, "ref_high": hi,
               "value_num": _num_value(vm.group(2)) if vm else None, "comparator": vm.group(1) if vm else None,
               "range_value": False, "qualitative": vm is None, "value": value, "ref_text": ref_text}
        flag = _resolve_flag(tmp)
        vj = {"name": _WS.sub(" ", name).strip(), "value": value.strip(), "value_num": _json_num(tmp["value_num"]),
              "unit": unit, "ref_text": ref_text, "ref_low": _json_num(lo), "ref_high": _json_num(hi), "flag": flag}
        finish({"key": "test_result", "value_text": vj["name"], "value_json": vj,
                "evidence": _WS.sub(" ", ev).strip()}, sp, st, _conf(tr.get("confidence")))

    for i, md in enumerate(data.get("medications") or []):
        if not isinstance(md, dict):
            drop("medications", i, "bad_item")
            continue
        sp = page_of("medications", i, md)
        if sp is None:
            continue
        st = evidence_ok("medications", i, md, sp)
        if st is None:
            continue
        name, ev = md.get("name"), md.get("evidence")
        if not isinstance(name, str) or not norm_text(name):
            drop("medications", i, "empty_value")
            continue
        if not contains(name, ev) or not _nums_in(name, ev):
            drop("medications", i, "value_not_in_evidence")
            continue
        vj = {"name": _WS.sub(" ", name).strip()}
        for k in ("strength", "form", "dose", "frequency", "duration", "instructions"):
            v = md.get(k)
            if isinstance(v, str) and v.strip() and _nums_in(v, ev) and (k == "form" or contains(v, ev)):
                vj[k] = _WS.sub(" ", v).strip()
            else:
                vj[k] = None
        finish({"key": "medication", "value_text": vj["name"], "value_json": vj,
                "evidence": _WS.sub(" ", ev).strip()}, sp, st, _conf(md.get("confidence")))

    sm = data.get("summary")
    if isinstance(sm, dict) and isinstance(sm.get("text"), str) and sm["text"].strip():
        all_nums = set(n for p in pages for n in _numbers(p))
        if all(n in all_nums for n in _numbers(sm["text"])):
            sps = [p - 1 for p in (sm.get("source_pages") or []) if isinstance(p, int) and not isinstance(p, bool)
                   and 1 <= p <= len(pages)]
            text = _WS.sub(" ", sm["text"]).strip()
            if len(text) > 400:
                text = text[:399] + "…"
            res["summary"] = {"text": text, "source_pages": sps}
        else:
            drop("summary", 0, "number_not_in_pages")
    return res


# ---------------------------------------------------------------------------------------------
# §15 Near duplicates: dHash and MinHash
# ---------------------------------------------------------------------------------------------

FNV_OFFSET = 0xcbf29ce484222325
FNV_PRIME = 0x100000001b3
MASK64 = 0xFFFFFFFFFFFFFFFF


def fnv1a64(data, h=FNV_OFFSET):
    for b in data:
        h ^= b
        h = (h * FNV_PRIME) & MASK64
    return h


def _le64(x):
    return bytes((x >> (8 * i)) & 0xFF for i in range(8))


def dhash_hex(gray):
    """gray: 8 rows x 9 columns of 0..255 luminance. Bit for (row r, col c), c in 0..7, is 1 when
    gray[r][c] > gray[r][c+1]. Bits are taken row-major, MSB first (row 0 col 0 = bit 63), and printed
    as 16 lowercase hex digits."""
    if len(gray) != 8 or any(len(row) != 9 for row in gray):
        raise ValueError("dhash needs 8 rows of 9 values")
    v = 0
    for r in range(8):
        for c in range(8):
            v = (v << 1) | (1 if gray[r][c] > gray[r][c + 1] else 0)
    return "%016x" % v


def hamming_hex(a, b):
    return bin(int(a, 16) ^ int(b, 16)).count("1")


def shingles(text):
    """Distinct 5-word shingles of words(fold(text)), each words joined by one space. 1-4 words -> one
    shingle of all words; 0 words -> none. Returned sorted (order does not affect the signature)."""
    ws = words(fold(text or ""))
    if not ws:
        return []
    if len(ws) < 5:
        return [" ".join(ws)]
    return sorted(set(" ".join(ws[i:i + 5]) for i in range(len(ws) - 4)))


def minhash_signature(text):
    """64 slots; slot s (1..64) = min over shingles of fnv1a64(le64(base), h=fnv1a64(le64(s))) where
    base = fnv1a64(utf8(shingle)). Unsigned 64-bit compare. Slots as 16 lowercase hex digits joined
    by ','. No shingles -> ''."""
    sh = shingles(text)
    if not sh:
        return ""
    seeds = [fnv1a64(_le64(s)) for s in range(1, 65)]
    mins = [MASK64] * 64
    for x in sh:
        base = _le64(fnv1a64(x.encode("utf-8")))
        for k in range(64):
            v = fnv1a64(base, seeds[k])
            if v < mins[k]:
                mins[k] = v
    return ",".join("%016x" % v for v in mins)


def signature_similarity(a, b):
    """Equal-slot fraction; 0.0 when either signature is empty."""
    if not a or not b:
        return 0.0
    xa, xb = a.split(","), b.split(",")
    if len(xa) != 64 or len(xb) != 64:
        return 0.0
    return sum(1 for i in range(64) if xa[i] == xb[i]) / 64.0


def near_duplicate(phash_a, phash_b, sig_a, sig_b):
    """§15 candidate rule -> {candidate, reason, score}."""
    sim = signature_similarity(sig_a, sig_b)
    if phash_a and phash_b:
        hd = hamming_hex(phash_a, phash_b)
        if hd <= 6 and (sim >= 0.9 or (not sig_a and not sig_b)):
            return {"candidate": True, "reason": "phash", "score": round2(sim if sig_a else 1 - hd / 64.0)}
    if sim >= 0.95:
        return {"candidate": True, "reason": "content", "score": round2(sim)}
    return {"candidate": False, "reason": None, "score": round2(sim)}


# ---------------------------------------------------------------------------------------------
# §17 Query parser
# ---------------------------------------------------------------------------------------------

_Q_TYPES = [
    (("blood", "tests"), ["lab_report"]), (("blood", "test"), ["lab_report"]),
    (("blood", "reports"), ["lab_report"]), (("blood", "report"), ["lab_report"]),
    (("test", "reports"), ["lab_report"]), (("test", "report"), ["lab_report"]),
    (("lab", "reports"), ["lab_report"]), (("lab", "report"), ["lab_report"]),
    (("doctor", "notes"), ["consultation_note"]), (("doctor", "note"), ["consultation_note"]),
    (("discharge", "summary"), ["discharge_summary"]), (("discharge", "summaries"), ["discharge_summary"]),
    (("x", "ray"), ["imaging_report"]), (("x", "rays"), ["imaging_report"]),
    (("ct", "scan"), ["imaging_report"]), (("ct", "scans"), ["imaging_report"]),
    (("mri", "scan"), ["imaging_report"]),
    (("reports",), ["lab_report", "diagnostic_report", "imaging_report"]),
    (("report",), ["lab_report", "diagnostic_report", "imaging_report"]),
    (("lab",), ["lab_report"]), (("labs",), ["lab_report"]),
    (("prescription",), ["prescription"]), (("prescriptions",), ["prescription"]), (("rx",), ["prescription"]),
    (("medicine",), ["prescription"]), (("medicines",), ["prescription"]),
    (("scan",), ["imaging_report"]), (("scans",), ["imaging_report"]), (("imaging",), ["imaging_report"]),
    (("xray",), ["imaging_report"]), (("xrays",), ["imaging_report"]), (("mri",), ["imaging_report"]),
    (("ct",), ["imaging_report"]), (("ultrasound",), ["imaging_report"]), (("usg",), ["imaging_report"]),
    (("discharge",), ["discharge_summary"]),
    (("consultation",), ["consultation_note"]), (("consultations",), ["consultation_note"]),
    (("visit",), ["consultation_note"]), (("visits",), ["consultation_note"]),
    (("bill",), ["bill"]), (("bills",), ["bill"]), (("invoice",), ["bill"]), (("invoices",), ["bill"]),
    (("receipt",), ["bill"]), (("receipts",), ["bill"]),
    (("insurance",), ["insurance"]), (("claim",), ["insurance"]), (("claims",), ["insurance"]),
    (("vaccine",), ["vaccination_record"]), (("vaccines",), ["vaccination_record"]),
    (("vaccination",), ["vaccination_record"]), (("vaccinations",), ["vaccination_record"]),
    (("note",), ["personal_note"]), (("notes",), ["personal_note"]),
]
# Modality words stay searchable terms as well as types (an MRI search should rank MRI text first).
_Q_TYPE_AND_TERM = frozenset(["mri", "ct", "xray", "ultrasound", "usg"])
_Q_FLAGS = [(("out", "of", "range"), "abnormal"), (("abnormal",), "abnormal"), (("abnormalities",), "abnormal"),
            (("abnormality",), "abnormal"), (("low",), "low"), (("high",), "high"), (("elevated",), "high"),
            (("raised",), "high"), (("critical",), "critical")]
_Q_STATES = [(("shared", "with", "me"), "source"), (("needs", "review"), "needs_review"),
             (("to", "review"), "needs_review"), (("favorites",), "favorites"), (("favorite",), "favorites"),
             (("favourites",), "favorites"), (("favourite",), "favorites"), (("starred",), "favorites"),
             (("received",), "source"), (("archived",), "archived")]
_Q_STOP = frozenset(["a", "an", "the", "my", "of", "with", "where", "was", "were", "is", "are", "from", "for", "in",
                     "on", "show", "find", "all", "me", "and", "or", "to", "by", "at", "any", "which", "that",
                     "had", "has", "have", "please", "get", "list", "records", "record", "documents", "document",
                     "files", "file", "last", "latest", "recent", "this", "since", "before", "after", "between",
                     "doctor", "dr", "hospital"])
_Q_ORDER_FLAGS = ["abnormal", "low", "high", "critical"]
_Q_MONTH_FULL = frozenset(["january", "february", "march", "april", "june", "july", "august", "september",
                           "october", "november", "december"])
_Q_PERIODS = {"day": "day", "days": "day", "week": "week", "weeks": "week", "month": "month", "months": "month",
              "year": "year", "years": "year"}


def _q_text(text):
    return " ".join(fold(text or "").split("\n"))


def _is_ascii_digit(ch):
    return "0" <= ch <= "9"


def _q_plain_tokens(f, start, end, toks):
    """Tokens of f[start:end]: maximal alphanumeric runs ('w'); a run of ASCII digits followed by '.' and
    ASCII digits and then a non-alphanumeric (or the end) is ONE 'w' token ('1.2', Phase 3); '>=', '<=',
    '>', '<', '≥', '≤' are 'op' tokens (value '>=', '<=', '>', '<'). Tokens are (kind, value, text, start, end)."""
    i = start
    while i < end:
        ch = f[i]
        if _is_alnum_cp(ch):
            j = i
            while j < end and _is_alnum_cp(f[j]):
                j += 1
            if all(_is_ascii_digit(x) for x in f[i:j]) and j + 1 < end and f[j] == "." and _is_ascii_digit(f[j + 1]):
                k = j + 1
                while k < end and _is_ascii_digit(f[k]):
                    k += 1
                if k == end or not _is_alnum_cp(f[k]):
                    j = k
            toks.append(("w", f[i:j], f[i:j], i, j))
            i = j
        elif ch in "<>≤≥":
            if ch in "<>" and i + 1 < end and f[i + 1] == "=":
                toks.append(("op", ch + "=", f[i:i + 2], i, i + 2))
                i += 2
            else:
                op = {"≤": "<=", "≥": ">="}.get(ch, ch)
                toks.append(("op", op, ch, i, i + 1))
                i += 1
        else:
            i += 1


def _q_tokens(text, today, date_order):
    """Folded query -> tokens [(kind, value, text, start, end)]: kind 'date' for full dates found by §11
    (value = date), 'op' comparison symbols, else 'w' words (see _q_plain_tokens). Offsets index _q_text."""
    f = _q_text(text)
    toks = []
    pos = 0
    for c in _date_candidates(f, today, date_order):
        if c["precision"] != "day":
            continue
        _q_plain_tokens(f, pos, c["start"], toks)
        toks.append(("date", c["dates"][0], f[c["start"]:c["end"]], c["start"], c["end"]))
        pos = c["end"]
    _q_plain_tokens(f, pos, len(f), toks)
    return toks


def _period_range(unit, start, today):
    if unit == "day":
        return start, start
    if unit == "week":
        s = start - _dt.timedelta(days=start.weekday())
        return s, s + _dt.timedelta(days=6)
    if unit == "month":
        s = start.replace(day=1)
        return s, _add_months(s, 1) - _dt.timedelta(days=1)
    s = start.replace(month=1, day=1)
    return s, s.replace(month=12, day=31)


def _q_datespec(toks, i, today, allow_bare_abbrev):
    """Date spec at i -> (from, to, consumed) or None. Specs: full date; month yyyy; month; yyyy; today;
    yesterday; this|last|past|previous week|month|year; last|past N days|weeks|months|years."""
    if i >= len(toks):
        return None
    kind, v = toks[i][0], toks[i][1]
    if kind == "date":
        return v, v, 1
    if v == "today":
        return today, today, 1
    if v == "yesterday":
        d = today - _dt.timedelta(days=1)
        return d, d, 1
    nxt = toks[i + 1][1] if i + 1 < len(toks) and toks[i + 1][0] == "w" else None
    nxt2 = toks[i + 2][1] if i + 2 < len(toks) and toks[i + 2][0] == "w" else None
    if v in ("this", "current") and nxt in ("week", "month", "year"):
        s, e = _period_range(nxt, today, today)
        return s, e, 2
    if v in ("last", "past", "previous") and nxt in ("week", "month", "year"):
        if nxt == "week":
            ref = today - _dt.timedelta(days=7)
        elif nxt == "month":
            ref = _add_months(today.replace(day=1), -1)
        else:
            ref = today.replace(year=today.year - 1, month=1, day=1)
        s, e = _period_range(nxt, ref, today)
        return s, e, 2
    if v in ("last", "past", "previous") and nxt is not None and re.fullmatch("[0-9]{1,3}", nxt) and nxt2 in _Q_PERIODS:
        n, unit = int(nxt), _Q_PERIODS[nxt2]
        if unit == "day":
            s = today - _dt.timedelta(days=n)
        elif unit == "week":
            s = today - _dt.timedelta(days=7 * n)
        elif unit == "month":
            s = _add_months(today, -n)
        else:
            s = _add_years(today, -n)
        return s, today, 3
    if v in _MONTH_NUM:
        mo = _MONTH_NUM[v]
        if nxt is not None and re.fullmatch("[0-9]{4}", nxt) and 1900 <= int(nxt) <= 2100:
            s = _dt.date(int(nxt), mo, 1)
            return s, _add_months(s, 1) - _dt.timedelta(days=1), 2
        if (v in _Q_MONTH_FULL) or allow_bare_abbrev:
            y = today.year if mo <= today.month else today.year - 1
            s = _dt.date(y, mo, 1)
            return s, _add_months(s, 1) - _dt.timedelta(days=1), 1
        return None
    if re.fullmatch("[0-9]{4}", v) and 1900 <= int(v) <= 2100:
        return _dt.date(int(v), 1, 1), _dt.date(int(v), 12, 31), 1
    return None


def _match_phrase(toks, i, table):
    for phrase, val in table:
        n = len(phrase)
        if i + n <= len(toks) and all(toks[i + k][0] == "w" and toks[i + k][1] == phrase[k] for k in range(n)):
            return phrase, val
    return None


_Q_COND_FLAGS = _Q_FLAGS + [(("normal",), "normal")]
_Q_CONNECTORS = frozenset(["was", "is", "were", "are"])
_Q_CMP_WORDS = [(("greater", "than"), ">"), (("more", "than"), ">"), (("less", "than"), "<"), (("at", "least"), ">="),
                (("at", "most"), "<="), (("above",), ">"), (("over",), ">"), (("below",), "<"), (("under",), "<")]
_RE_Q_NUMBER = re.compile("[0-9]+(?:\\.[0-9]+)?")


def _q_alias_table():
    """§23 query aliases: every catalog alias as §8.1 words and as analyte_words (both spellings index the
    same analyte). Skipped: keys of <= 1 character and aliases made only of reserved query words (types,
    flags, states, stop words) or method words. Returns ({words tuple: [ids]}, longest length)."""
    if "q_alias" not in _CACHE:
        reserved = (set(w for p, _ in _Q_TYPES for w in p) | set(w for p, _ in _Q_FLAGS for w in p)
                    | set(w for p, _ in _Q_STATES for w in p) | _Q_STOP | _METHOD_WORDS)
        table = {}
        for e in analyte_catalog()["list"]:
            for a in e["aliases"]:
                for ws in (tuple(words(fold(a))), tuple(analyte_words(a))):
                    if len(" ".join(ws)) <= 1 or all(w in reserved for w in ws):
                        continue
                    ids = table.setdefault(ws, [])
                    if e["id"] not in ids:
                        ids.append(e["id"])
        _CACHE["q_alias"] = (table, max(len(k) for k in table))
    return _CACHE["q_alias"]


def _q_match_alias(toks, i):
    table, longest = _q_alias_table()
    for n in range(min(longest, len(toks) - i), 0, -1):
        if all(toks[i + k][0] == "w" for k in range(n)):
            ids = table.get(tuple(toks[i + k][1] for k in range(n)))
            if ids:
                return n, ids
    return None


def _q_resolve(ids, unit):
    """Query alias -> one analyte or None: keep analytes listing the unit (when given and any does), then
    non-urine analytes, then numeric analytes; each filter applies only if it keeps at least one."""
    cat = analyte_catalog()["by_id"]
    c = list(ids)
    for keep in ((lambda i: unit is not None and unit in _analyte_units(cat[i])),
                 (lambda i: cat[i]["category"] != "urine"), (lambda i: cat[i]["kind"] == "numeric")):
        if len(c) > 1:
            k = [i for i in c if keep(i)]
            if k:
                c = k
    return c[0] if len(c) == 1 else None


def _q_condition(toks, j, f):
    """After an analyte alias ending at token j: optional was/is/were/are, then a flag phrase (low, high,
    elevated, raised, abnormal, out of range, critical, normal) or a comparison (op token or comparison
    words) + number [+ unit matched by match_unit on the folded query]. -> dict or None."""
    k = j
    if k < len(toks) and toks[k][0] == "w" and toks[k][1] in _Q_CONNECTORS:
        k += 1
    fl = _match_phrase(toks, k, _Q_COND_FLAGS)
    if fl:
        end = k + len(fl[0])
        return {"flag": fl[1], "op": None, "value": None, "unit": None, "end": end, "end_pos": toks[end - 1][4]}
    op, k2 = None, None
    if k < len(toks) and toks[k][0] == "op":
        op, k2 = toks[k][1], k + 1
    else:
        cm = _match_phrase(toks, k, _Q_CMP_WORDS)
        if cm:
            op, k2 = cm[1], k + len(cm[0])
    if op is None or k2 >= len(toks) or toks[k2][0] != "w" or not _RE_Q_NUMBER.fullmatch(toks[k2][1]):
        return None
    end, end_pos = k2 + 1, toks[k2][4]
    pos = end_pos
    while pos < len(f) and f[pos] == " ":
        pos += 1
    unit = None
    um = match_unit(f, pos) if pos < len(f) else None
    if um:
        unit, end_pos = um[0], um[1]
        while end < len(toks) and toks[end][3] < um[1]:
            end += 1
    return {"flag": None, "op": op, "value": float(toks[k2][1]), "unit": unit, "end": end, "end_pos": end_pos}


def _q_condition_out(aid, cond):
    if cond["flag"] is not None:
        return {"analyte_id": aid, "flag": cond["flag"], "op": None, "value": None, "unit": None,
                "canonical_value": None, "canonical_unit": None}
    if cond["unit"] is None:
        cv, cu = _json_num(round4(cond["value"])), analyte_catalog()["by_id"][aid]["canonical_unit"]
    else:
        conv = convert_unit(aid, cond["value"], cond["unit"])
        cv, cu = conv["canonical_value"], conv["canonical_unit"]
    return {"analyte_id": aid, "flag": None, "op": cond["op"], "value": _json_num(cond["value"]),
            "unit": cond["unit"], "canonical_value": cv, "canonical_unit": cu}


def parse_query(text, today, date_order):
    """§17 RecordQueryParser + §23 analyte conditions. Returns the RecordQuery dict (dates ISO strings)."""
    today_d = _dt.date.fromisoformat(today)
    toks = _q_tokens(text, today_d, date_order)
    f = _q_text(text)
    q = {"terms": [], "date_from": None, "date_to": None, "record_types": [], "flags": [], "doctor": None,
         "facility": None, "favorites": False, "needs_review": False, "archived": False, "source": None,
         "analyte_conditions": [], "analytes": [], "chips": [], "match": None}
    types, flags = set(), set()
    ranges = []
    i = 0

    def chip(kind, a, b):
        q["chips"].append({"kind": kind, "text": " ".join(t[2] for t in toks[a:b])})

    reserved_names = set(w for p, _ in _Q_TYPES for w in p) | set(w for p, _ in _Q_FLAGS for w in p) | \
        set(w for p, _ in _Q_STATES for w in p) | _Q_STOP
    while i < len(toks):
        kind, v = toks[i][0], toks[i][1]
        # 1. Date phrases with a preposition.
        if kind == "w" and v in ("from", "between") :
            a = _q_datespec(toks, i + 1, today_d, True)
            if a:
                j = i + 1 + a[2]
                if j < len(toks) and toks[j][1] in ("to", "and", "till", "until"):
                    b = _q_datespec(toks, j + 1, today_d, True)
                    if b:
                        ranges.append((a[0], b[1]))
                        chip("date", i, j + 1 + b[2])
                        i = j + 1 + b[2]
                        continue
                ranges.append((a[0], a[1]))
                chip("date", i, j)
                i = j
                continue
        if kind == "w" and v in ("since", "after", "before", "in", "during"):
            a = _q_datespec(toks, i + 1, today_d, True)
            if a:
                if v == "since":
                    ranges.append((a[0], None))
                elif v == "after":
                    ranges.append((a[1] + _dt.timedelta(days=1), None))
                elif v == "before":
                    ranges.append((None, a[0] - _dt.timedelta(days=1)))
                else:
                    ranges.append((a[0], a[1]))
                chip("date", i, i + 1 + a[2])
                i += 1 + a[2]
                continue
        a = _q_datespec(toks, i, today_d, False)
        if a:
            ranges.append((a[0], a[1]))
            chip("date", i, i + a[2])
            i += a[2]
            continue
        if kind != "w":
            i += 1
            continue
        # 2. States, types, flags (longest phrase first within each table).
        st = _match_phrase(toks, i, _Q_STATES)
        if st:
            phrase, what = st
            if what == "favorites":
                q["favorites"] = True
            elif what == "needs_review":
                q["needs_review"] = True
            elif what == "archived":
                q["archived"] = True
            else:
                q["source"] = "received"
            chip(what, i, i + len(phrase))
            i += len(phrase)
            continue
        ty = _match_phrase(toks, i, _Q_TYPES)
        if ty:
            phrase, vals = ty
            types.update(vals)
            chip("type", i, i + len(phrase))
            if len(phrase) == 1 and phrase[0] in _Q_TYPE_AND_TERM and phrase[0] not in q["terms"]:
                q["terms"].append(phrase[0])
            i += len(phrase)
            continue
        # 2b. Analyte alias (§23): with a condition -> analyte_conditions; bare -> analytes + terms.
        al = _q_match_alias(toks, i)
        if al:
            n, ids = al
            cond = _q_condition(toks, i + n, f)
            aid = _q_resolve(ids, cond["unit"] if cond else None)
            if aid is not None and cond is not None:
                q["analyte_conditions"].append(_q_condition_out(aid, cond))
                q["chips"].append({"kind": "analyte", "text": f[toks[i][3]:cond["end_pos"]]})
                i = cond["end"]
                continue
            if aid is not None:
                if aid not in q["analytes"]:
                    q["analytes"].append(aid)
                for t in toks[i:i + n]:
                    if t[1] not in _Q_STOP and t[1] not in q["terms"]:
                        q["terms"].append(t[1])
                i += n
                continue
        # 2c. Flag + analyte alias ("high cholesterol") -> analyte condition.
        cf = _match_phrase(toks, i, _Q_COND_FLAGS)
        if cf:
            al = _q_match_alias(toks, i + len(cf[0]))
            aid = _q_resolve(al[1], None) if al else None
            if aid is not None:
                end = i + len(cf[0]) + al[0]
                q["analyte_conditions"].append(_q_condition_out(aid, {"flag": cf[1]}))
                q["chips"].append({"kind": "analyte", "text": f[toks[i][3]:toks[end - 1][4]]})
                i = end
                continue
        fl = _match_phrase(toks, i, _Q_FLAGS)
        if fl:
            phrase, val = fl
            flags.add(val)
            chip("flag", i, i + len(phrase))
            i += len(phrase)
            continue
        # 3. doctor/dr <name>, at/hospital <name>.
        if v in ("doctor", "dr", "at", "hospital"):
            j = i + 1
            name = []
            while j < len(toks) and toks[j][0] == "w" and len(name) < 3 and toks[j][1] not in reserved_names \
                    and not _q_datespec(toks, j, today_d, False):
                name.append(toks[j][1])
                j += 1
            if name:
                if v in ("doctor", "dr"):
                    q["doctor"] = " ".join(name)
                    chip("doctor", i, j)
                else:
                    q["facility"] = " ".join(name)
                    chip("facility", i, j)
                i = j
                continue
        # 4. Stop words, else a term (a '1.2' token becomes the terms '1' and '2', as in Phase 2).
        for w in words(v):
            if w not in _Q_STOP and w not in q["terms"]:
                q["terms"].append(w)
        i += 1
    if ranges:
        lo = [r[0] for r in ranges if r[0] is not None]
        hi = [r[1] for r in ranges if r[1] is not None]
        q["date_from"] = max(lo).isoformat() if lo else None
        q["date_to"] = min(hi).isoformat() if hi else None
    q["record_types"] = [t for t in RECORD_TYPES if t in types]
    q["flags"] = [f for f in _Q_ORDER_FLAGS if f in flags]
    q["match"] = " ".join(t + "*" for t in q["terms"]) if q["terms"] else None
    return q


# =============================================================================================
# Phase 3: health knowledge base (docs §19-§24)
# =============================================================================================

# ---------------------------------------------------------------------------------------------
# §20 Analyte catalog and test-name mapping
# ---------------------------------------------------------------------------------------------

PANEL_IDS = ["cbc", "iron_studies", "vitamins", "diabetes", "lipid", "lft", "kft", "electrolytes", "thyroid",
             "cardiac", "coagulation", "hormones", "urine_routine", "urine_albumin", "serology", "pancreas"]
ANALYTE_CATEGORIES = ["hematology", "iron", "vitamins", "diabetes", "lipids", "liver", "kidney", "electrolytes",
                      "thyroid", "cardiac", "inflammation", "coagulation", "hormones", "tumor_markers", "urine",
                      "serology", "pancreas", "other"]
URINE_PANELS = frozenset(["urine_routine", "urine_albumin"])
ANALYTE_METHODS = ["catalog", "user_alias", "user"]

# Method words (§20): removed from a test name, as whole words, when building the "m" keys. Specimens,
# derivations, assay methods and filler. Never: total, free, direct, indirect, fasting, random, absolute,
# count, ratio (they distinguish analytes).
_METHOD_WORDS = frozenset([
    "serum", "plasma", "blood", "whole", "venous", "capillary", "edta", "fluoride", "heparin",
    "calculated", "calc", "derived", "measured", "method", "automated", "auto", "analyzer", "analyser",
    "hplc", "ifcc", "ngsp", "dcct", "clia", "eclia", "cmia", "cia", "elisa", "elfa", "ria", "ise",
    "turbidimetric", "turbidimetry", "immunoturbidimetric", "immunoturbidimetry", "nephelometric", "nephelometry",
    "photometric", "photometry", "colorimetric", "colorimetry", "spectrophotometric", "spectrophotometry",
    "enzymatic", "kinetic", "jaffe", "jaffes", "westergren", "wintrobe", "ckd", "epi", "mdrd",
    "level", "levels", "conc", "concentration"])
# Serum abbreviations, removed only as the FIRST word ("S. Creatinine", "Sr. Uric Acid").
_METHOD_LEADING = frozenset(["s", "sr", "se", "ser"])

# Panel headings (§20 detect_panels): phrases in analyte_words form, matched as whole-word runs.
_PANEL_KEYWORDS = [
    ("cbc", ["complete blood count", "cbc", "hemogram", "haemogram", "full blood count", "fbc", "blood count"]),
    ("iron_studies", ["iron studies", "iron profile", "iron panel", "anemia profile", "anaemia profile"]),
    ("vitamins", ["vitamin profile", "vitamin panel", "vitamin b12", "vitamin d"]),
    ("diabetes", ["diabetes profile", "diabetic profile", "diabetes panel", "glucose tolerance", "blood sugar",
                  "hba1c", "glycated", "glycosylated"]),
    ("lipid", ["lipid", "lipids", "lipid profile", "lipid panel"]),
    ("lft", ["liver function", "lft", "hepatic function", "liver panel", "liver profile"]),
    ("kft", ["kidney function", "renal function", "kft", "rft", "renal profile", "kidney profile", "renal panel"]),
    ("electrolytes", ["electrolyte", "electrolytes"]),
    ("thyroid", ["thyroid", "tft"]),
    ("cardiac", ["cardiac", "troponin"]),
    ("coagulation", ["coagulation", "prothrombin", "pt inr"]),
    ("hormones", ["hormone", "hormones", "hormonal", "fertility", "pcos"]),
    ("urine_routine", ["urine", "urinalysis", "cue"]),
    ("urine_albumin", ["microalbumin", "albumin creatinine ratio", "acr", "urine albumin"]),
    ("serology", ["serology", "dengue", "widal", "hiv", "hbsag", "viral markers"]),
    ("pancreas", ["amylase", "lipase", "pancreatic"]),
]
_PANEL_MIN_ANALYTES = 3

# Aliases shared by several analytes after normalization (§20). The contract check recomputes this map
# from analytes.json and fails when it differs, and requires every group to be separable by unit, kind,
# panel or the urine rule.
ANALYTE_SHARED_ALIASES = {
    "albumin": ["albumin", "urine_protein"],
    "basophil": ["basophils_pct", "basophils_abs"],
    "basophils": ["basophils_pct", "basophils_abs"],
    "baso": ["basophils_pct", "basophils_abs"],
    "bilirubin": ["bilirubin_total", "urine_bilirubin"],
    "creatinine": ["creatinine", "urine_creatinine"],
    "eos": ["eosinophils_pct", "eosinophils_abs"],
    "eosinophil": ["eosinophils_pct", "eosinophils_abs"],
    "eosinophils": ["eosinophils_pct", "eosinophils_abs"],
    "glucose": ["glucose", "urine_glucose"],
    "leucocytes": ["wbc_count", "urine_leukocyte_esterase"],
    "leukocytes": ["wbc_count", "urine_leukocyte_esterase"],
    "lymph": ["lymphocytes_pct", "lymphocytes_abs"],
    "lymphocyte": ["lymphocytes_pct", "lymphocytes_abs"],
    "lymphocytes": ["lymphocytes_pct", "lymphocytes_abs"],
    "mono": ["monocytes_pct", "monocytes_abs"],
    "monocyte": ["monocytes_pct", "monocytes_abs"],
    "monocytes": ["monocytes_pct", "monocytes_abs"],
    "neut": ["neutrophils_pct", "neutrophils_abs"],
    "neutrophil": ["neutrophils_pct", "neutrophils_abs"],
    "neutrophils": ["neutrophils_pct", "neutrophils_abs"],
    "protein": ["total_protein", "urine_protein"],
    "rbc": ["rbc_count", "urine_rbc"],
    "rdw": ["rdw_cv", "rdw_sd"],
    "red blood cells": ["rbc_count", "urine_rbc"],
    "red cell distribution width": ["rdw_cv", "rdw_sd"],
    "sugar": ["glucose", "urine_glucose"],
    "tc": ["wbc_count", "cholesterol_total"],
    "wbc": ["wbc_count", "urine_pus_cells"],
}


def analyte_catalog():
    """shared/records/analytes.json -> {list, by_id, index} where index maps each alias key
    (' '.join(analyte_words(alias))) to the analyte ids listing it, in file order."""
    if "analytes" not in _CACHE:
        with open(os.path.join(SHARED, "analytes.json"), encoding="utf-8") as fh:
            data = json.load(fh)
        by_id, index = {}, {}
        for e in data["analytes"]:
            by_id[e["id"]] = e
            for a in e["aliases"]:
                ids = index.setdefault(" ".join(analyte_words(a)), [])
                if e["id"] not in ids:
                    ids.append(e["id"])
        _CACHE["analytes"] = {"list": data["analytes"], "by_id": by_id, "index": index}
    return _CACHE["analytes"]


def analyte_words(s):
    """§20 words of a test name: §8.1 words of fold(s), then every run of >= 2 consecutive one-letter
    ASCII words is joined into one word ('S.G.O.T.' -> 'sgot', 'A/G Ratio' -> 'ag ratio'; a single
    one-letter word stays: 'Vitamin D' -> 'vitamin d')."""
    out, run = [], []
    for w in words(fold(s or "")):
        if len(w) == 1 and "a" <= w <= "z":
            run.append(w)
            continue
        if len(run) >= 2:
            out.append("".join(run))
        else:
            out.extend(run)
        run = []
        out.append(w)
    if len(run) >= 2:
        out.append("".join(run))
    else:
        out.extend(run)
    return out


def _strip_method_words(ws):
    """Remove method words (and a leading serum abbreviation); at least the original words remain."""
    out = [w for i, w in enumerate(ws) if not (w in _METHOD_WORDS or (i == 0 and w in _METHOD_LEADING))]
    return out if out else list(ws)


def _strip_brackets(f):
    """Delete (...) and [...] segments (nesting of the same bracket counted; an unclosed bracket runs to the
    end). Each deleted segment becomes one space."""
    out, close, opener, depth = [], None, None, 0
    for ch in f:
        if close is None:
            if ch == "(" or ch == "[":
                opener, close, depth = ch, (")" if ch == "(" else "]"), 1
                out.append(" ")
            else:
                out.append(ch)
        elif ch == opener:
            depth += 1
        elif ch == close:
            depth -= 1
            if depth == 0:
                close = None
    return "".join(out)


def test_name_keys(name):
    """Lookup keys of a printed test name, most specific first (duplicates removed):
    k1 = all words; k2 = words after deleting bracketed text; k1m = k1 without method words; k2m = k2 without
    method words. (Brackets go before method words: 'Sr. Creatinine (Jaffe)' keeps 'sr creatinine'.)"""
    f = fold(name or "")
    k1 = analyte_words(f)
    k2 = analyte_words(_strip_brackets(f))
    keys = []
    for ws in (k1, k2, _strip_method_words(k1), _strip_method_words(k2)):
        k = " ".join(ws)
        if k and k not in keys:
            keys.append(k)
    return keys


def normalize_test_name(name):
    """§20 normalized test name (the analyte_user_aliases key): k1m of test_name_keys."""
    return " ".join(_strip_method_words(analyte_words(name)))


def canonical_unit_spelling(unit):
    """A unit string -> the units.json canonical spelling when the WHOLE folded string is a variant, else
    the printed-fold text with whitespace collapsed; blank/None -> None."""
    if unit is None:
        return None
    p = _WS.sub(" ", pfold(unit)).strip(" ")
    if not p:
        return None
    m = match_unit(fold(p), 0)
    if m and m[1] == len(p):
        return m[0]
    return p


def _analyte_units(e):
    """Units accepted for an analyte. Dimensionless numeric analytes (canonical_unit null) accept 'ratio'."""
    us = set(u["unit"] for u in e["units"])
    if e["canonical_unit"] is None and e["kind"] == "numeric":
        us.add("ratio")
    return us


def _disambiguate(ids, unit, qualitative, panels):
    """Shared alias -> one analyte id or None. Steps, each applied only while >= 2 candidates remain; a step
    that leaves exactly one candidate decides, one that leaves none is skipped:
    1. unit (canonical spelling, or '%' printed in the name) listed for the analyte;
    2. kind: qualitative when the value has no number, numeric otherwise;
    3. panels: the analyte lists a detected panel;
    4. urine rule: without a detected urine panel, urine-category analytes drop out."""
    cat = analyte_catalog()["by_id"]
    c = list(ids)
    steps = []
    if unit is not None:
        steps.append(lambda i: unit in _analyte_units(cat[i]))
    if qualitative is not None:
        steps.append(lambda i: cat[i]["kind"] == ("qualitative" if qualitative else "numeric"))
    steps.append(lambda i: bool(set(cat[i]["panels"]) & panels))
    if not (panels & URINE_PANELS):
        steps.append(lambda i: cat[i]["category"] != "urine")
    for keep in steps:
        k = [i for i in c if keep(i)]
        if len(k) == 1:
            return k[0]
        if len(k) >= 2:
            c = k
    return None


def map_analyte(name, panels=None, user_aliases=None, unit=None, qualitative=None):
    """§20 mapping. Order: user alias on any key (k1, k2, k1m, k2m) -> catalog alias on the first key that has
    one (unique alias -> that analyte; shared alias -> _disambiguate; unresolved -> unmapped with candidates).
    Never fuzzy. Returns {analyte_id, method, key, normalized_name, candidates}."""
    cat = analyte_catalog()
    keys = test_name_keys(name)
    res = {"analyte_id": None, "method": None, "key": None, "normalized_name": normalize_test_name(name),
           "candidates": []}
    ua = user_aliases or {}
    for k in keys:
        aid = ua.get(k)
        if aid is not None and aid in cat["by_id"]:
            res.update({"analyte_id": aid, "method": "user_alias", "key": k})
            return res
    u = canonical_unit_spelling(unit)
    if u is None and "%" in (name or ""):
        u = "%"
    pset = set(panels or [])
    for k in keys:
        ids = cat["index"].get(k)
        if not ids:
            continue
        res["key"] = k
        aid = ids[0] if len(ids) == 1 else _disambiguate(ids, u, qualitative, pset)
        if aid is None:
            res["candidates"] = list(ids)
        else:
            res.update({"analyte_id": aid, "method": "catalog"})
        return res
    return res


def detect_panels(texts, test_names):
    """§20 panels of a record: a heading phrase in any of `texts` (report names), or >= 3 distinct analytes
    mapped (catalog, no context) from `test_names` that list the panel. Output in PANEL_IDS order."""
    found = set()
    for t in texts or []:
        s = " " + " ".join(analyte_words(t)) + " "
        for panel, phrases in _PANEL_KEYWORDS:
            if any((" " + ph + " ") in s for ph in phrases):
                found.add(panel)
    by_id = analyte_catalog()["by_id"]
    counts, seen = {}, set()
    for n in test_names or []:
        m = map_analyte(n)
        if m["method"] == "catalog" and m["analyte_id"] not in seen:
            seen.add(m["analyte_id"])
            for p in by_id[m["analyte_id"]]["panels"]:
                counts[p] = counts.get(p, 0) + 1
    for p, c in counts.items():
        if c >= _PANEL_MIN_ANALYTES:
            found.add(p)
    return [p for p in PANEL_IDS if p in found]


def round4(x):
    """Half-up to 4 decimals: floor(x * 10000 + 0.5 + 1e-9) / 10000 (canonical values)."""
    return math.floor(x * 10000 + 0.5 + 1e-9) / 10000.0


def convert_unit(analyte_id, value, unit):
    """§20 conversion -> {unit, canonical_value, canonical_unit, status}. canonical = round4(value x factor +
    offset). status: converted | unmapped | no_value | unit_missing | unit_unknown. Dimensionless numeric
    analytes convert a value with no unit or unit 'ratio'. A missing unit is never assumed."""
    u = canonical_unit_spelling(unit)
    out = {"unit": u, "canonical_value": None, "canonical_unit": None, "status": None}
    e = analyte_catalog()["by_id"].get(analyte_id) if analyte_id else None
    if e is None:
        out["status"] = "unmapped"
        return out
    if value is None:
        out["status"] = "no_value"
        return out
    factor = offset = None
    if e["canonical_unit"] is None and e["kind"] == "numeric":
        if u is None or u == "ratio":
            factor, offset = 1, 0
    elif u is None:
        out["status"] = "unit_missing"
        return out
    else:
        for x in e["units"]:
            if x["unit"] == u:
                factor, offset = x["factor"], x["offset"]
                break
    if factor is None:
        out["status"] = "unit_unknown"
        return out
    out.update({"canonical_value": _json_num(round4(value * factor + offset)), "canonical_unit": e["canonical_unit"],
                "status": "converted"})
    return out


# ---------------------------------------------------------------------------------------------
# §19 Observations: observed date, promotion, edits, user aliases
# ---------------------------------------------------------------------------------------------

OBSERVATION_KEYS = ["id", "record_id", "field_id", "analyte_id", "analyte_method", "raw_name", "value_num",
                    "value_text", "unit", "canonical_value", "canonical_unit", "ref_low", "ref_high", "ref_text",
                    "flag", "observed_date", "observed_date_method", "method", "confidence", "state", "source_page",
                    "source_bbox", "evidence", "excluded_from_trends", "created_ms", "updated_ms"]
# Columns a still-suggested observation copies from its field on every promotion.
_OBS_SYNC_KEYS = ["analyte_id", "analyte_method", "raw_name", "value_num", "value_text", "unit", "canonical_value",
                  "canonical_unit", "ref_low", "ref_high", "ref_text", "flag", "method", "confidence", "state",
                  "source_page", "source_bbox", "evidence"]


def observed_date(record, fields):
    """§19: best non-rejected collection_date row -> best report_date row (§8.1 best row) ->
    record.document_date -> record.sort_date. Returns {date, method}; method names the source."""
    for key in ("collection_date", "report_date"):
        r = _best_row(fields, key)
        if r is not None:
            return {"date": r["value_text"], "method": key}
    for key in ("document_date", "sort_date"):
        if record.get(key):
            return {"date": record[key], "method": key}
    return {"date": None, "method": None}


def _observation_from_field(fd, record, panels, user_aliases, od):
    vj = fd.get("value_json") or {}
    raw = vj.get("name") if vj.get("name") else fd["value_text"]
    vn = vj.get("value_num")
    m = map_analyte(raw, panels, user_aliases, vj.get("unit"), vn is None)
    conv = convert_unit(m["analyte_id"], vn, vj.get("unit"))
    return {"id": None, "record_id": record["id"], "field_id": fd["id"], "analyte_id": m["analyte_id"],
            "analyte_method": m["method"], "raw_name": raw, "value_num": vn,
            "value_text": vj.get("value") if vj.get("value") is not None else "", "unit": conv["unit"],
            "canonical_value": conv["canonical_value"], "canonical_unit": conv["canonical_unit"],
            "ref_low": vj.get("ref_low"), "ref_high": vj.get("ref_high"), "ref_text": vj.get("ref_text"),
            "flag": vj.get("flag") or "unknown", "observed_date": od["date"], "observed_date_method": od["method"],
            "method": fd.get("method"), "confidence": fd.get("confidence"),
            "state": "confirmed" if fd["state"] in ("confirmed", "user") else "suggested",
            "source_page": fd.get("source_page"), "source_bbox": fd.get("source_bbox"), "evidence": fd.get("evidence"),
            "excluded_from_trends": 0, "created_ms": None, "updated_ms": None}


def record_panels(fields):
    """detect_panels over a record's non-rejected report_name values and test_result names."""
    live = [r for r in fields if r.get("state") != "rejected"]
    texts = [r["value_text"] for r in live if r["field_key"] == "report_name"]
    names = [((r.get("value_json") or {}).get("name") or r["value_text"]) for r in live if r["field_key"] == "test_result"]
    return detect_panels(texts, names)


def promote_observations(fields, existing_observations, record, user_aliases=None, now_ms=0):
    """§19 promotion (runs in the transaction that changes a record's fields: extraction or review).
    For each test_result field in row order:
      * no observation with that field_id: insert one unless the field is rejected (ids obs-1, obs-2, ...;
        state confirmed when the field is confirmed/user, else suggested);
      * field rejected: a suggested/confirmed observation becomes rejected; user/rejected ones are kept;
      * observation still suggested: every _OBS_SYNC_KEYS column is recomputed from the field;
      * any non-rejected observation whose observed_date_method is not 'user' follows observed_date().
    Returns {observations, actions, panels}; existing rows keep their order, inserts are appended."""
    panels = record_panels(fields)
    od = observed_date(record, fields)
    obs = [dict(o) for o in existing_observations]
    by_field = {}
    for o in obs:
        if o.get("field_id") is not None and o["field_id"] not in by_field:
            by_field[o["field_id"]] = o
    actions = []
    inserted = 0
    for fd in fields:
        if fd["field_key"] != "test_result":
            continue
        o = by_field.get(fd["id"])
        if o is None:
            if fd["state"] == "rejected":
                continue
            inserted += 1
            new = _observation_from_field(fd, record, panels, user_aliases, od)
            new.update({"id": "obs-%d" % inserted, "created_ms": now_ms, "updated_ms": now_ms})
            obs.append(new)
            by_field[fd["id"]] = new
            actions.append({"action": "insert", "id": new["id"], "field_id": fd["id"]})
            continue
        if fd["state"] == "rejected":
            if o["state"] in ("suggested", "confirmed"):
                o["state"] = "rejected"
                o["updated_ms"] = now_ms
                actions.append({"action": "reject", "id": o["id"], "field_id": fd["id"]})
            else:
                actions.append({"action": "keep", "id": o["id"], "field_id": fd["id"]})
            continue
        changed = False
        if o["state"] == "suggested":
            target = _observation_from_field(fd, record, panels, user_aliases, od)
            for k in _OBS_SYNC_KEYS:
                if o.get(k) != target[k]:
                    o[k] = target[k]
                    changed = True
        if o["state"] != "rejected" and o.get("observed_date_method") != "user":
            if o.get("observed_date") != od["date"] or o.get("observed_date_method") != od["method"]:
                o["observed_date"], o["observed_date_method"] = od["date"], od["method"]
                changed = True
        if changed:
            o["updated_ms"] = now_ms
        actions.append({"action": "update" if changed else "keep", "id": o["id"], "field_id": fd["id"]})
    return {"observations": obs, "actions": actions, "panels": panels}


def _parse_ref_text(ref_text):
    """§13 reference forms on a whole reference text (brackets stripped) -> (low, high) floats or None."""
    if ref_text is None:
        return None, None
    rf = fold(ref_text).strip(" ()[]")
    for rx, kind in ((_RE_REF_RANGE, "range"), (_RE_REF_HIGH, "high"), (_RE_REF_LOW, "low")):
        fm = rx.fullmatch(rf)
        if fm:
            tmp = {"ref_low": None, "ref_high": None}
            _set_ref(tmp, fm, kind)
            return tmp["ref_low"], tmp["ref_high"]
    return None, None


_RE_EDIT_RANGE = re.compile("([0-9]{1,3})[ ]?-[ ]?([0-9]{1,3})")
_RE_ISO_DATE = re.compile("([0-9]{4})-([0-9]{2})-([0-9]{2})")


def _value_parts(value_text):
    """Edited value -> (value_num, comparator, range_value, qualitative) with §13 value forms."""
    vf = fold(value_text or "").strip(" ")
    rm = _RE_EDIT_RANGE.fullmatch(vf)
    if rm:
        return None, None, True, False
    vm = _RE_VALUE_NUM.fullmatch(vf)
    if vm:
        return _num_value(vm.group(2)), vm.group(1), False, False
    return None, None, False, True


def recompute_flag(value_text, ref_text, ref_low, ref_high):
    """§24 flag of an edited observation: §13 flag resolution without a printed marker."""
    vn, comp, rng, qual = _value_parts(value_text)
    value = fold(value_text or "").strip(" ")
    if rng:
        value = value.replace(" ", "")
    res = {"flag_raw": None, "ref_low": ref_low, "ref_high": ref_high, "value_num": vn, "comparator": comp,
           "range_value": rng, "qualitative": qual, "value": value, "ref_text": ref_text}
    return _resolve_flag(res)


def _convert_between(analyte_id, value, from_unit, to_unit):
    """A value in from_unit -> to_unit through the analyte's factors (both units listed), round4; else None."""
    e = analyte_catalog()["by_id"].get(analyte_id) if analyte_id else None
    if value is None or e is None or from_unit is None or to_unit is None:
        return None
    f = dict((u["unit"], u) for u in e["units"])
    if from_unit not in f or to_unit not in f:
        return None
    canonical = value * f[from_unit]["factor"] + f[from_unit]["offset"]
    return _json_num(round4((canonical - f[to_unit]["offset"]) / f[to_unit]["factor"]))


def edit_observation(obs, patch):
    """§24 user edit. patch keys (each optional): value, unit, ref_text, analyte_id (null = unmapped),
    observed_date (yyyy-MM-dd or null), excluded_from_trends (bool), remove (true), now_ms.
    Every edit sets state 'user' (remove sets 'rejected') and updated_ms; evidence/source_* are kept.
    canonical_value/canonical_unit are always recomputed. A unit change without a ref_text in the patch converts
    ref_low/ref_high from the old unit to the new one through the analyte's factors (ref_text stays as printed);
    when either unit is not convertible the bounds become null and flag 'unknown'. flag is recomputed when value,
    ref_text or the unit changed.
    Returns {observation, error}; on error the observation is returned unchanged."""
    cat = analyte_catalog()["by_id"]
    o = dict(obs)
    if patch.get("analyte_id") is not None and patch["analyte_id"] not in cat:
        return {"observation": dict(obs), "error": "unknown_analyte"}
    if patch.get("observed_date") is not None:
        m = _RE_ISO_DATE.fullmatch(patch["observed_date"]) if isinstance(patch["observed_date"], str) else None
        if not m or _valid(int(m.group(1)), int(m.group(2)), int(m.group(3))) is None:
            return {"observation": dict(obs), "error": "bad_date"}
    if "value" in patch and not _WS.sub(" ", patch["value"] or "").strip(" "):
        return {"observation": dict(obs), "error": "empty_value"}
    now = patch.get("now_ms")
    if patch.get("remove"):
        o["state"] = "rejected"
        o["updated_ms"] = now
        return {"observation": o, "error": None}
    if "value" in patch:
        o["value_text"] = _WS.sub(" ", patch["value"]).strip(" ")
        vn = _value_parts(o["value_text"])[0]
        o["value_num"] = _json_num(vn)
    unit_changed = False
    if "unit" in patch:
        new_unit = canonical_unit_spelling(patch["unit"])
        unit_changed = new_unit != o.get("unit")
        old_unit = o.get("unit")
        o["unit"] = new_unit
    if "ref_text" in patch:
        rt = _WS.sub(" ", patch["ref_text"] or "").strip(" ") or None
        lo, hi = _parse_ref_text(rt)
        o["ref_text"], o["ref_low"], o["ref_high"] = rt, _json_num(lo), _json_num(hi)
    if "analyte_id" in patch:
        o["analyte_id"] = patch["analyte_id"]
        o["analyte_method"] = "user" if patch["analyte_id"] is not None else None
    if "observed_date" in patch:
        o["observed_date"] = patch["observed_date"]
        o["observed_date_method"] = "user"
    if "excluded_from_trends" in patch:
        o["excluded_from_trends"] = 1 if patch["excluded_from_trends"] else 0
    ranges_ok = True
    if unit_changed and "ref_text" not in patch and (o.get("ref_low") is not None or o.get("ref_high") is not None):
        # The printed range is in the original unit: convert the bounds, or drop them (ref_text stays as printed).
        lo = _convert_between(o.get("analyte_id"), o.get("ref_low"), old_unit, o["unit"])
        hi = _convert_between(o.get("analyte_id"), o.get("ref_high"), old_unit, o["unit"])
        if (o.get("ref_low") is not None and lo is None) or (o.get("ref_high") is not None and hi is None):
            o["ref_low"], o["ref_high"] = None, None
            ranges_ok = False
        else:
            o["ref_low"], o["ref_high"] = lo, hi
    if not ranges_ok:
        o["flag"] = "unknown"
    elif "value" in patch or "ref_text" in patch or unit_changed:
        o["flag"] = recompute_flag(o["value_text"], o.get("ref_text"), o.get("ref_low"), o.get("ref_high"))
    conv = convert_unit(o.get("analyte_id"), o.get("value_num"), o.get("unit"))
    o["canonical_value"], o["canonical_unit"] = conv["canonical_value"], conv["canonical_unit"]
    o["state"] = "user"
    o["updated_ms"] = now
    return {"observation": o, "error": None}


def apply_user_alias(observations, raw_name, analyte_id, now_ms=0):
    """§19 "This is <analyte>": alias key = normalize_test_name(raw_name). On confirmation every non-rejected
    UNMAPPED observation for which that key is one of test_name_keys(raw_name) gets analyte_id,
    analyte_method 'user_alias' and a recomputed canonical value (state unchanged).
    Returns {alias, observations, updated_ids, error}."""
    key = normalize_test_name(raw_name)
    if analyte_id not in analyte_catalog()["by_id"]:
        return {"alias": None, "observations": [dict(o) for o in observations], "updated_ids": [],
                "error": "unknown_analyte"}
    out, updated = [], []
    for o in observations:
        o = dict(o)
        if o["state"] != "rejected" and o.get("analyte_id") is None and key in test_name_keys(o["raw_name"]):
            conv = convert_unit(analyte_id, o.get("value_num"), o.get("unit"))
            o.update({"analyte_id": analyte_id, "analyte_method": "user_alias",
                      "canonical_value": conv["canonical_value"], "canonical_unit": conv["canonical_unit"],
                      "updated_ms": now_ms})
            updated.append(o["id"])
        out.append(o)
    return {"alias": {"normalized_name": key, "analyte_id": analyte_id}, "observations": out, "updated_ids": updated,
            "error": None}


# ---------------------------------------------------------------------------------------------
# §21 Trends
# ---------------------------------------------------------------------------------------------

def trend_series(observations, analyte_id):
    """§21. Points = observations of the analyte with state != rejected, excluded_from_trends = 0 and an
    observed_date, ordered by (observed_date, created_ms, id). A point with canonical_value joins the
    canonical series; else one with value_num joins the series of its unit (null = no unit); else it is
    skipped. Within a series, observations with the same date and value collapse into one point (first one
    gives flag/range/unit; record_ids and observation_ids list every member). Canonical series first, then
    unit series, each by first appearance. Canonical points convert ref_low/ref_high with the observation's
    unit. band = the range of the most recent point that has a bound, else null."""
    e = analyte_catalog()["by_id"].get(analyte_id)
    rows = [o for o in observations if o.get("analyte_id") == analyte_id and o.get("state") != "rejected"
            and not o.get("excluded_from_trends") and o.get("observed_date")]
    rows.sort(key=lambda o: (o["observed_date"], o.get("created_ms") or 0, o["id"]))
    series, skipped = [], []
    for o in rows:
        if o.get("canonical_value") is not None:
            key, value = ("c", o.get("canonical_unit")), o["canonical_value"]
        elif o.get("value_num") is not None:
            key, value = ("u", o.get("unit")), o["value_num"]
        else:
            skipped.append(o["id"])
            continue
        s = next((x for x in series if x["_key"] == key), None)
        if s is None:
            s = {"_key": key, "unit": key[1], "convertible": key[0] == "c", "points": [], "band": None}
            series.append(s)
        lo, hi = o.get("ref_low"), o.get("ref_high")
        if key[0] == "c":
            lo = convert_unit(analyte_id, lo, o.get("unit"))["canonical_value"] if lo is not None else None
            hi = convert_unit(analyte_id, hi, o.get("unit"))["canonical_value"] if hi is not None else None
        p = next((x for x in s["points"] if x["date"] == o["observed_date"] and x["value"] == value), None)
        if p is None:
            s["points"].append({"date": o["observed_date"], "value": value, "value_text": o.get("value_text"),
                                "unit": o.get("unit"), "flag": o.get("flag"), "ref_low": lo, "ref_high": hi,
                                "record_ids": [o["record_id"]], "observation_ids": [o["id"]]})
        else:
            if o["record_id"] not in p["record_ids"]:
                p["record_ids"].append(o["record_id"])
            p["observation_ids"].append(o["id"])
    for s in series:
        for p in reversed(s["points"]):
            if p["ref_low"] is not None or p["ref_high"] is not None:
                s["band"] = {"low": p["ref_low"], "high": p["ref_high"]}
                break
    ordered = [s for s in series if s["convertible"]] + [s for s in series if not s["convertible"]]
    for s in ordered:
        del s["_key"]
    return {"analyte_id": analyte_id, "display_name": e["display_name"] if e else None, "series": ordered,
            "skipped_ids": skipped}


def _round_dec(x, d):
    """Half-up on the magnitude to d decimals (sign kept)."""
    r = math.floor(abs(x) * (10.0 ** d) + 0.5 + 1e-9) / (10.0 ** d)
    return -r if x < 0 and r != 0 else r


def format_value(x, d):
    """Fixed d decimals of _round_dec(x, d), '-' for negatives."""
    r = _round_dec(x, d)
    return ("-" if r < 0 else "") + ("%." + str(d) + "f") % abs(r)


def mini_trend(trend, observation_id):
    """§21 detail mini trend for one observation. Uses the series point that contains the observation and the
    points before it (a report shows its trend as of that report). Shown when that is >= 2 points.
    text = the last <= 5 values (older first) joined by ' → ' with the analyte's decimals (default 2);
    change_text = '<+|−><|delta|>[ <unit>] since <previous point date>' (U+2212 minus; no sign when the
    rounded delta is 0)."""
    none = {"show": False, "values": [], "text": None, "unit": None, "change": None, "change_text": None}
    e = analyte_catalog()["by_id"].get(trend["analyte_id"])
    d = e.get("decimals", 2) if e else 2
    for s in trend["series"]:
        for idx, p in enumerate(s["points"]):
            if observation_id not in p["observation_ids"]:
                continue
            pts = s["points"][:idx + 1]
            if len(pts) < 2:
                return none
            vals = [format_value(x["value"], d) for x in pts[-5:]]
            prev = pts[-2]
            delta = _round_dec(p["value"] - prev["value"], d)
            sign = "+" if delta > 0 else ("−" if delta < 0 else "")
            text = sign + ("%." + str(d) + "f") % abs(delta)
            if s["unit"]:
                text += " " + s["unit"]
            return {"show": True, "values": vals, "text": " → ".join(vals), "unit": s["unit"],
                    "change": {"delta": _json_num(delta), "unit": s["unit"], "since_date": prev["date"]},
                    "change_text": text + " since " + prev["date"]}
    return none


# ---------------------------------------------------------------------------------------------
# §19 Entities (doctors, facilities)
# ---------------------------------------------------------------------------------------------

_DOCTOR_TITLES = frozenset(["dr", "doctor", "prof", "professor"])
_ENTITY_QUALIFICATIONS = _QUALIFICATIONS | frozenset(["mrcgp", "fcps", "facp", "frcpath", "dpm", "dortho", "dlo",
                                                      "dvd", "dnbe", "fnb", "fracs", "mams"])
_FACILITY_WORD = {"hospitals": "hospital", "clinics": "clinic", "laboratories": "lab", "laboratory": "lab",
                  "labs": "lab", "diagnostics": "diagnostic", "centre": "center", "centres": "center",
                  "centers": "center", "pathlabs": "pathlab", "speciality": "specialty", "specialities": "specialty",
                  "specialties": "specialty", "pvt": None, "private": None, "ltd": None, "limited": None,
                  "llp": None, "inc": None, "and": None}
_FACILITY_JOIN = [("health", "care", "healthcare"), ("path", "lab", "pathlab"), ("multi", "specialty", "multispecialty"),
                  ("super", "specialty", "superspecialty"), ("poly", "clinic", "polyclinic")]


def _letters_of(tok):
    return "".join(ch for ch in fold(tok) if "a" <= ch <= "z")


def doctor_display_name(value):
    """Doctor name for display: printed fold, whitespace collapsed, cut before the first ',' or '(', a glued
    'Dr.Name' split, leading titles (dr, doctor, prof, professor) and trailing qualification tokens
    (letters of the token in the qualification list, 'M.B.B.S.' included) removed; one token always stays."""
    p = _WS.sub(" ", pfold(value or "")).strip(" ")
    cut = [i for i in (p.find(","), p.find("(")) if i >= 0]
    if cut:
        p = p[:min(cut)]
    toks = [t for t in p.strip(" .-").split(" ") if t]
    if toks:
        t0 = fold(toks[0])
        for pre in ("dr.", "prof."):
            if t0.startswith(pre) and len(t0) > len(pre):
                toks = [toks[0][:len(pre)], toks[0][len(pre):]] + toks[1:]
                break
    while len(toks) > 1 and _letters_of(toks[0]) in _DOCTOR_TITLES:
        toks = toks[1:]
    while len(toks) > 1 and _letters_of(toks[-1]) in _ENTITY_QUALIFICATIONS:
        toks = toks[:-1]
    return " ".join(toks).strip(" .-'")


def normalize_doctor_name(value):
    """entities.normalized_name for doctors: analyte_words of the display name ('A.K. Sharma' == 'AK Sharma')."""
    return " ".join(analyte_words(doctor_display_name(value)))


def facility_display_name(value):
    return _WS.sub(" ", pfold(value or "")).strip(" ,.-|")


def normalize_facility_name(value):
    """entities.normalized_name for facilities: analyte_words; per-word suffix normalization (_FACILITY_WORD;
    None = dropped); adjacent pairs joined (_FACILITY_JOIN, left to right); a leading 'the' dropped; if nothing
    is left the plain words are kept."""
    ws = analyte_words(facility_display_name(value))
    out = []
    for w in ws:
        m = _FACILITY_WORD.get(w, w)
        if m is not None:
            out.append(m)
    joined = []
    for w in out:
        if joined:
            pair = next((j for a, b, j in _FACILITY_JOIN if joined[-1] == a and w == b), None)
            if pair is not None:
                joined[-1] = pair
                continue
        joined.append(w)
    if joined and joined[0] == "the" and len(joined) > 1:
        joined = joined[1:]
    return " ".join(joined) if joined else " ".join(ws)


def _best_of(rows):
    """§8.1 best row among already filtered rows."""
    rank = {"user": 0, "confirmed": 1, "suggested": 2}
    c = [(i, r) for i, r in enumerate(rows) if r["state"] != "rejected"
         and (r["state"] in ("user", "confirmed") or r["confidence"] >= 0.6)]
    c.sort(key=lambda t: (rank[t[1]["state"]], -t[1]["confidence"], t[0]))
    return c[0][1] if c else None


def rebuild_entities(fields):
    """§19 entities of one record from its field rows: best primary doctor_name (role doctor, specialty =
    best doctor_specialty), best referrer doctor_name (role referrer), best facility (role facility).
    Returns {entities:[{kind, display_name, normalized_name, specialty}], record_entities:[{kind,
    normalized_name, role}]}. The store upserts entities by (kind, normalized_name), keeping an existing
    display_name and filling a NULL specialty."""
    def role(r):
        vj = r.get("value_json")
        return vj.get("role") if isinstance(vj, dict) else None
    docs = [r for r in fields if r["field_key"] == "doctor_name"]
    prim = _best_of([r for r in docs if role(r) != "referrer"])
    ref = _best_of([r for r in docs if role(r) == "referrer"])
    spec = _best_row(fields, "doctor_specialty")
    fac = _best_row(fields, "facility")
    entities, links = [], []

    def add(kind, display, norm, specialty, rl):
        if not norm:
            return
        ent = next((x for x in entities if x["kind"] == kind and x["normalized_name"] == norm), None)
        if ent is None:
            ent = {"kind": kind, "display_name": display, "normalized_name": norm, "specialty": None}
            entities.append(ent)
        if specialty and not ent["specialty"]:
            ent["specialty"] = specialty
        link = {"kind": kind, "normalized_name": norm, "role": rl}
        if link not in links:
            links.append(link)

    if prim is not None:
        add("doctor", doctor_display_name(prim["value_text"]), normalize_doctor_name(prim["value_text"]),
            spec["value_text"] if spec is not None else None, "doctor")
    if ref is not None:
        add("doctor", doctor_display_name(ref["value_text"]), normalize_doctor_name(ref["value_text"]), None, "referrer")
    if fac is not None:
        add("facility", facility_display_name(fac["value_text"]), normalize_facility_name(fac["value_text"]), None,
            "facility")
    return {"entities": entities, "record_entities": links}


# ---------------------------------------------------------------------------------------------
# §22 Related-record suggestions
# ---------------------------------------------------------------------------------------------

LINK_KINDS = ["follow_up", "prescription_for", "same_episode", "previous_report", "related", "split_from"]
_LINK_PRIORITY = ["follow_up", "prescription_for", "previous_report", "same_episode"]
_LAB_LIKE = frozenset(["lab_report", "imaging_report", "diagnostic_report"])
MAX_SUGGESTIONS = 5
RELATION_WINDOW_DAYS = 180


def _days_between(a, b):
    return (_dt.date.fromisoformat(b) - _dt.date.fromisoformat(a)).days


def suggest_relations(record, candidates, today, existing_links=None):
    """§22. record/candidates: {id, record_type, sort_date, archived, split_parent, panels, report_name,
    analytes, doctors (normalized doctor+referrer entity names), facilities, follow_up_dates}.
    existing_links: record_links rows {a_id, b_id, kind, origin, status}. `today` is part of the port signature
    and is not used by the v1 rules. Returns {links:[{a_id, b_id, kind, origin, status, score, reasons}]}."""
    links = existing_links or []
    if record.get("archived") and record.get("split_parent"):
        return {"links": []}
    pairs = set((l["a_id"], l["b_id"]) for l in links)
    pending = sum(1 for l in links if l["status"] == "suggested" and record["id"] in (l["a_id"], l["b_id"]))
    slots = max(0, MAX_SUGGESTIONS - pending)
    found = []
    for c in candidates:
        if c["id"] == record["id"] or (c.get("archived") and c.get("split_parent")):
            continue
        gap = _days_between(record["sort_date"], c["sort_date"])
        if abs(gap) > RELATION_WINDOW_DAYS:
            continue
        a, b = sorted([record["id"], c["id"]])
        if (a, b) in pairs:
            continue
        kinds, reasons = {}, []
        if record["record_type"] in _LAB_LIKE and c["record_type"] in _LAB_LIKE:
            same_panel = bool(set(record.get("panels") or []) & set(c.get("panels") or []))
            rn = norm_text(record.get("report_name") or "")
            same_name = bool(rn) and rn == norm_text(c.get("report_name") or "")
            if same_panel or same_name:
                s = 0.5
                reasons += (["same_panel"] if same_panel else []) + (["same_report_name"] if same_name else [])
                if len(set(record.get("analytes") or []) & set(c.get("analytes") or [])) >= 3:
                    s += 0.2
                    reasons.append("shared_analytes")
                kinds["previous_report"] = s
        near = abs(gap) <= 30
        if near and set(record.get("doctors") or []) & set(c.get("doctors") or []):
            kinds["same_episode"] = kinds.get("same_episode", 0) + 0.4
            reasons.append("same_doctor")
        if near and set(record.get("facilities") or []) & set(c.get("facilities") or []):
            kinds["same_episode"] = kinds.get("same_episode", 0) + 0.2
            reasons.append("same_facility")
        for p, v in ((record, c), (c, record)):
            if (p["record_type"] == "prescription" and v["record_type"] in ("consultation_note", "discharge_summary")
                    and set(p.get("doctors") or []) & set(v.get("doctors") or [])
                    and 0 <= _days_between(v["sort_date"], p["sort_date"]) <= 14):
                kinds["prescription_for"] = 0.6
                reasons.append("prescription_after_visit")
                break
        for x, y in ((record, c), (c, record)):
            if any(abs(_days_between(f, y["sort_date"])) <= 7 for f in (x.get("follow_up_dates") or [])):
                kinds["follow_up"] = 0.6
                reasons.append("follow_up_date")
                break
        if not kinds:
            continue
        total = round2(min(1.0, sum(kinds[k] for k in _LINK_PRIORITY if k in kinds)))
        if total < 0.6:
            continue
        kind = sorted(kinds, key=lambda k: (-round2(kinds[k]), _LINK_PRIORITY.index(k)))[0]
        found.append((-total, abs(gap), c["id"], {"a_id": a, "b_id": b, "kind": kind, "origin": "suggested",
                                                  "status": "suggested", "score": total, "reasons": reasons}))
    found.sort(key=lambda t: (t[0], t[1], t[2]))
    return {"links": [t[3] for t in found[:slots]]}


# ---------------------------------------------------------------------------------------------
# Vector dispatch (used by scripts/records_contract_check.py)
# ---------------------------------------------------------------------------------------------

def run_case(function, inp):
    if function == "fold":
        return {"fold": fold(inp["text"]), "pfold": pfold(inp["text"]), "normalized": norm_text(inp["text"]),
                "words": words(fold(inp["text"]))}
    if function == "classify":
        return classify(inp["pages"])
    if function == "extract_dates":
        return {"items": extract_dates(inp["pages"], inp["record_type"], inp["today"], inp["date_order"])}
    if function == "extract_fields":
        return {"items": extract_fields(inp["pages"], inp["record_type"])}
    if function == "parse_lab_rows":
        return {"items": parse_lab_rows(inp["pages"], inp["record_type"])}
    if function == "detect_boundaries":
        return detect_boundaries(inp["pages"], inp["today"], inp["date_order"])
    if function == "build_highlights":
        return {"highlights": build_highlights(inp["fields"], inp.get("summary"))}
    if function == "review_status":
        return review_status(inp["record"], inp["rows"], inp.get("pending_split", False),
                             inp.get("pending_duplicate", False))
    if function == "apply_extraction":
        return apply_extraction(inp["existing_rows"], inp["new_items"], inp.get("record"), inp.get("classification"))
    if function == "validate_ai":
        return validate_ai(inp["pages"], inp["ai_json"], inp["mode"], inp["today"], inp["date_order"],
                           inp.get("image_pages"))
    if function == "ai_chunks":
        return {"chunks": ai_chunks(inp["pages"], inp["mode"])}
    if function == "hashing":
        op = inp["op"]
        if op == "dhash":
            return {"hex": dhash_hex(inp["gray"])}
        if op == "hamming":
            return {"distance": hamming_hex(inp["a"], inp["b"])}
        if op == "fnv1a64":
            return {"hex": "%016x" % fnv1a64(inp["text"].encode("utf-8"))}
        if op == "shingles":
            return {"shingles": shingles(inp["text"])}
        if op == "minhash":
            return {"signature": minhash_signature(inp["text"])}
        if op == "similarity":
            sa, sb = minhash_signature(inp["a"]), minhash_signature(inp["b"])
            return {"similarity": signature_similarity(sa, sb)}
        if op == "near_duplicate":
            sa = minhash_signature(inp["text_a"]) if inp.get("text_a") is not None else ""
            sb = minhash_signature(inp["text_b"]) if inp.get("text_b") is not None else ""
            return near_duplicate(inp.get("phash_a"), inp.get("phash_b"), sa, sb)
        raise ValueError(op)
    if function == "parse_query":
        return parse_query(inp["text"], inp["today"], inp["date_order"])
    if function == "map_analyte":
        op = inp.get("op", "map")
        if op == "map":
            return map_analyte(inp["name"], inp.get("panels"), inp.get("user_aliases"), inp.get("unit"),
                               inp.get("qualitative"))
        if op == "keys":
            return {"keys": test_name_keys(inp["name"]), "normalized_name": normalize_test_name(inp["name"])}
        if op == "detect_panels":
            return {"panels": detect_panels(inp.get("texts"), inp.get("test_names"))}
        raise ValueError(op)
    if function == "convert_unit":
        return convert_unit(inp["analyte_id"], inp["value"], inp.get("unit"))
    if function == "observations":
        op = inp["op"]
        if op == "promote":
            return promote_observations(inp["fields"], inp.get("existing_observations") or [], inp["record"],
                                        inp.get("user_aliases"), inp.get("now_ms", 0))
        if op == "observed_date":
            return observed_date(inp["record"], inp["fields"])
        if op == "edit":
            return edit_observation(inp["observation"], inp["patch"])
        if op == "user_alias":
            return apply_user_alias(inp["observations"], inp["raw_name"], inp["analyte_id"], inp.get("now_ms", 0))
        raise ValueError(op)
    if function == "trends":
        tr = trend_series(inp["observations"], inp["analyte_id"])
        if inp["op"] == "series":
            return tr
        if inp["op"] == "mini":
            return mini_trend(tr, inp["observation_id"])
        raise ValueError(inp["op"])
    if function == "entities":
        op = inp.get("op", "rebuild")
        if op == "rebuild":
            return rebuild_entities(inp["fields"])
        if op == "normalize":
            return {"doctor_display": doctor_display_name(inp["name"]), "doctor": normalize_doctor_name(inp["name"]),
                    "facility_display": facility_display_name(inp["name"]),
                    "facility": normalize_facility_name(inp["name"])}
        raise ValueError(op)
    if function == "suggest_relations":
        return suggest_relations(inp["record"], inp["candidates"], inp["today"], inp.get("existing_links"))
    raise ValueError("unknown function " + function)
