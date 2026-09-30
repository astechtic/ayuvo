"""Shared helpers for the localization scripts (docs/localization.md).

Reads and writes iOS String Catalogs (.xcstrings) in Xcode's own layout and
Android string resources (values*/). Stdlib only.
"""
import hashlib
import json
import os
import re
import xml.etree.ElementTree as ET
from dataclasses import dataclass, field

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
IOS_DIR = os.path.join(ROOT, "ios")
ANDROID_RES = os.path.join(ROOT, "android", "app", "src", "main", "res")
SHARED_L10N = os.path.join(ROOT, "shared", "l10n")

# Languages, plural categories and product names live in shared/l10n/l10n_config.json,
# which the iOS and Android coverage tests read too.
with open(os.path.join(SHARED_L10N, "l10n_config.json"), encoding="utf-8") as _fh:
    CONFIG = json.load(_fh)
# iOS language code -> Android values- qualifier.
LANGS = {lang["ios"]: lang["android"] for lang in CONFIG["languages"]}
# CLDR cardinal plural categories each language needs.
PLURAL_CATEGORIES = CONFIG["plural_categories"]
# Strings that are only product names never need translating.
DO_NOT_TRANSLATE = set(CONFIG["do_not_translate"])

# Apple and Android format specifiers. The space flag is left out on purpose:
# "50% of goal" is text, not "% o".
PLACEHOLDER = re.compile(
    r"%(?:(\d+)\$)?[-+#0,]*\d*(?:\.\d+)?(?:ll|l|h|q|z|t|j)?[@dDuUxXoOfeEgGcCsSaA]|%#@\w+@"
)
TOKEN = re.compile(r"⟦(\d+)⟧")
# A "%" followed by something Xcode would parse as a format specifier.
SPEC_LIKE = re.compile(r"%(?=[-+#0,.\d]*[@a-zA-Z])")
# App Intents parameter summaries ("Log ${amount} of water") keep their parameters.
INTENT_PARAM = re.compile(r"\$\{\w+\}")


def needs_translation(english):
    """False for empty strings, pure symbols/numbers and product names."""
    if isinstance(english, dict):
        return any(needs_translation(v) for v in english.values())
    text = PLACEHOLDER.sub("", english).replace("%%", "")
    if not any(ch.isalpha() for ch in text):
        return False
    return english.strip() not in DO_NOT_TRANSLATE


BRACE = re.compile(r"\{[a-z][a-z0-9_]*\}")


def _placeholder_matches(text, braces):
    # "%%" is an escaped percent sign; mask it so "80%%-dən" is not read as "%-d".
    masked = text.replace("%%", "\0\0")
    found = [(m.start(), m.end(), m.group(0), m.group(1)) for m in PLACEHOLDER.finditer(masked)]
    if braces:
        found += [(m.start(), m.end(), m.group(0), None) for m in BRACE.finditer(text)]
    return sorted(found)


def placeholders(text, braces=False):
    """Placeholder specs in order, positions stripped (for comparisons)."""
    return [re.sub(r"\d+\$", "", g) for _s, _e, g, _p in _placeholder_matches(text, braces)]


def placeholder_signature(text, braces=False):
    """Order-insensitive when positional, order-sensitive otherwise."""
    found = _placeholder_matches(text, braces)
    if found and all(p for *_x, p in found):
        return sorted((int(p), re.sub(r"\d+\$", "", g)) for _s, _e, g, p in found)
    if braces:
        return sorted(re.sub(r"\d+\$", "", g) for _s, _e, g, _p in found)
    return [(i + 1, re.sub(r"\d+\$", "", g)) for i, (_s, _e, g, _p) in enumerate(found)]


def to_tokens(text, braces=False):
    """Replace platform placeholders (and {name} templates when braces=True)
    with ⟦n⟧ tokens and %% with %.

    Returns (tokenised text, [spec for token 1, 2, ...]) where each spec has its
    position stripped.
    """
    specs = []
    out = []
    last = 0
    seq = 0
    for start, end, group, pos in _placeholder_matches(text, braces):
        out.append(text[last:start].replace("%%", "%"))
        seq += 1
        index = int(pos) if pos else seq
        spec = re.sub(r"\d+\$", "", group)
        while len(specs) < index:
            specs.append(None)
        specs[index - 1] = spec
        out.append(f"⟦{index}⟧")
        last = end
    out.append(text[last:].replace("%%", "%"))
    return "".join(out), specs


def from_tokens(text, specs, is_format, force_positional=False):
    """Inverse of to_tokens. Uses positional specifiers when the source was
    positional, or the translation reorders arguments."""
    order = [int(m.group(1)) for m in TOKEN.finditer(text)]
    expected = sorted(i + 1 for i, s in enumerate(specs) if s)
    if sorted(order) != expected:
        raise ValueError(f"placeholder tokens {order} do not match {expected}")
    positional = force_positional or order != list(range(1, len(order) + 1))
    parts = TOKEN.split(text)
    out = []
    for i, part in enumerate(parts):
        if i % 2 == 0:
            out.append(part.replace("%", "%%") if is_format else part)
        else:
            index = int(part)
            spec = specs[index - 1]
            if positional and not spec.startswith(("%#@", "{")):
                spec = f"%{index}${spec[1:]}"
            out.append(spec)
    return "".join(out)


def source_hash(english):
    blob = json.dumps(english, ensure_ascii=False, sort_keys=True)
    return hashlib.sha1(blob.encode("utf-8")).hexdigest()[:12]


# ---------------------------------------------------------------- iOS

def dump_xcstrings(obj, indent=0):
    """Serialise exactly like Xcode: 2-space indent, ' : ' separators."""
    pad = "  " * indent
    if isinstance(obj, dict):
        if not obj:
            return "{\n\n" + pad + "}"
        items = [f"{pad}  {json.dumps(k, ensure_ascii=False)} : {dump_xcstrings(v, indent + 1)}"
                 for k, v in obj.items()]
        return "{\n" + ",\n".join(items) + "\n" + pad + "}"
    if isinstance(obj, list):
        if not obj:
            return "[\n\n" + pad + "]"
        return "[\n" + ",\n".join(pad + "  " + dump_xcstrings(v, indent + 1) for v in obj) + "\n" + pad + "]"
    return json.dumps(obj, ensure_ascii=False)


def ios_catalogs():
    found = []
    for base, dirs, files in os.walk(IOS_DIR):
        dirs[:] = [d for d in dirs if d not in ("build", "DerivedData") and not d.endswith(".xcodeproj")]
        for name in files:
            if name.endswith(".xcstrings"):
                found.append(os.path.join(base, name))
    return sorted(found)


def load_xcstrings(path):
    with open(path, encoding="utf-8") as fh:
        return json.load(fh)


def save_xcstrings(path, catalog):
    with open(path, encoding="utf-8") as fh:
        trailing = fh.read().endswith("\n")
    text = dump_xcstrings(catalog) + ("\n" if trailing else "")
    with open(path, "w", encoding="utf-8") as fh:
        fh.write(text)


def ios_english(key, entry):
    """English text for an entry: a plain string, or {category: text} for plurals."""
    en = entry.get("localizations", {}).get("en")
    if en:
        if "stringUnit" in en:
            return en["stringUnit"].get("value", key)
        plural = en.get("variations", {}).get("plural")
        if plural:
            return {k: v["stringUnit"]["value"] for k, v in plural.items()}
    return key


def ios_translation(entry, lang):
    """(value, state) where value is str, {category: str} or None."""
    loc = entry.get("localizations", {}).get(lang)
    if not loc:
        return None, None
    if "stringUnit" in loc:
        unit = loc["stringUnit"]
        return unit.get("value"), unit.get("state")
    plural = loc.get("variations", {}).get("plural")
    if plural:
        states = {v["stringUnit"].get("state") for v in plural.values()}
        state = "translated" if states == {"translated"} else sorted(states - {"translated"})[0]
        return {k: v["stringUnit"].get("value") for k, v in plural.items()}, state
    return None, None


def set_ios_translation(entry, lang, value, state="needs_review"):
    locs = entry.setdefault("localizations", {})
    if isinstance(value, dict):
        locs[lang] = {"variations": {"plural": {
            cat: {"stringUnit": {"state": state, "value": value[cat]}}
            for cat in PLURAL_CATEGORIES[lang] if cat in value
        }}}
    else:
        locs[lang] = {"stringUnit": {"state": state, "value": value}}
    # Xcode keeps localizations sorted by language code.
    entry["localizations"] = dict(sorted(locs.items()))


# ---------------------------------------------------------------- Android

@dataclass
class AndroidString:
    name: str
    kind: str  # string | plurals | string-array
    file: str
    translatable: bool
    value: object  # str, {quantity: str} or [str]
    comment: str = ""


def android_decode(raw):
    """Android resource escapes -> plain text."""
    if raw is None:
        return ""
    text = raw.strip()
    if len(text) >= 2 and text.startswith('"') and text.endswith('"'):
        text = text[1:-1]
    out = []
    i = 0
    while i < len(text):
        ch = text[i]
        if ch == "\\" and i + 1 < len(text):
            nxt = text[i + 1]
            if nxt == "n":
                out.append("\n")
            elif nxt == "t":
                out.append("\t")
            elif nxt == "u" and i + 5 < len(text) + 0 and re.match(r"[0-9a-fA-F]{4}", text[i + 2:i + 6]):
                out.append(chr(int(text[i + 2:i + 6], 16)))
                i += 6
                continue
            else:
                out.append(nxt)
            i += 2
            continue
        out.append(ch)
        i += 1
    return "".join(out)


def android_encode(text):
    """Plain text -> Android resource text (XML-escaped)."""
    text = text.replace("\\", "\\\\").replace("\n", "\\n").replace("\t", "\\t")
    text = text.replace("'", "\\'").replace('"', '\\"')
    if text.startswith(("@", "?")):
        text = "\\" + text
    return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")


def _parse_resources(path):
    """Parse with comments kept, so a comment before an element can be read."""
    parser = ET.XMLParser(target=ET.TreeBuilder(insert_comments=True))
    return ET.parse(path, parser).getroot()


def android_value_files(qualifier_dir):
    folder = os.path.join(ANDROID_RES, qualifier_dir)
    if not os.path.isdir(folder):
        return []
    return sorted(os.path.join(folder, f) for f in os.listdir(folder) if f.endswith(".xml"))


def load_android(qualifier_dir="values"):
    """{name: AndroidString} for every string, plurals and string-array."""
    result = {}
    for path in android_value_files(qualifier_dir):
        root = _parse_resources(path)
        last_comment = ""
        for el in root:
            if el.tag is ET.Comment:
                last_comment = (el.text or "").strip()
                continue
            if el.tag not in ("string", "plurals", "string-array"):
                last_comment = ""
                continue
            translatable = el.get("translatable") != "false"
            if el.tag == "string":
                value = android_decode("".join(el.itertext()))
            elif el.tag == "plurals":
                value = {item.get("quantity"): android_decode("".join(item.itertext())) for item in el}
            else:
                value = [android_decode("".join(item.itertext())) for item in el if item.tag == "item"]
            result[el.get("name")] = AndroidString(
                el.get("name"), el.tag, os.path.basename(path), translatable, value, last_comment)
            last_comment = ""
    return result


def android_is_format(value):
    values = value.values() if isinstance(value, dict) else value if isinstance(value, list) else [value]
    return any(PLACEHOLDER.search(v) or "%%" in v for v in values)


def write_android_values(qualifier_dir, file_name, entries):
    """Insert or replace entries in values-<q>/<file_name>.

    entries: list of (name, kind, value) with plain-text values. Existing
    elements with the same name in any file of that folder are replaced in place.
    """
    folder = os.path.join(ANDROID_RES, qualifier_dir)
    os.makedirs(folder, exist_ok=True)
    pending = {name: (kind, value) for name, kind, value in entries}

    def render(name, kind, value):
        if kind == "string":
            return f'    <string name="{name}">{android_encode(value)}</string>'
        if kind == "plurals":
            items = "\n".join(f'        <item quantity="{q}">{android_encode(v)}</item>' for q, v in value.items())
            return f'    <plurals name="{name}">\n{items}\n    </plurals>'
        items = "\n".join(f"        <item>{android_encode(v)}</item>" for v in value)
        return f'    <string-array name="{name}">\n{items}\n    </string-array>'

    element = re.compile(r'( *)<(string|plurals|string-array)\s+name="([^"]+)"[^>]*?(?:/>|>.*?</\2>)', re.S)
    for path in android_value_files(qualifier_dir):
        with open(path, encoding="utf-8") as fh:
            text = fh.read()

        def replace(m):
            name = m.group(3)
            if name in pending:
                kind, value = pending.pop(name)
                out = render(name, kind, value)
                if 'formatted="false"' in m.group(0).split(">", 1)[0]:
                    out = out.replace(f'name="{name}">', f'name="{name}" formatted="false">', 1)
                return out
            return m.group(0)

        new = element.sub(replace, text)
        if new != text:
            with open(path, "w", encoding="utf-8") as fh:
                fh.write(new)

    if not pending:
        return
    target = os.path.join(folder, file_name)
    if os.path.exists(target):
        with open(target, encoding="utf-8") as fh:
            text = fh.read()
    else:
        text = '<?xml version="1.0" encoding="utf-8"?>\n<resources>\n</resources>\n'
    block = "\n".join(render(n, k, v) for n, (k, v) in pending.items())
    idx = text.rindex("</resources>")
    head = text[:idx].rstrip("\n") + "\n"
    text = head + block + "\n" + text[idx:]
    with open(target, "w", encoding="utf-8") as fh:
        fh.write(text)


# ---------------------------------------------------------------- units

@dataclass
class Unit:
    platform: str  # ios | android
    file: str  # catalog path relative to ROOT, or res file name
    key: str
    english: object  # str | {category: str} | [str]
    kind: str = "string"
    comment: str = ""
    is_format: bool = False
    translations: dict = field(default_factory=dict)  # lang -> (value, state)
    braces: bool = False  # {name} templates count as placeholders (contract text)


def ios_units():
    units = []
    for path in ios_catalogs():
        catalog = load_xcstrings(path)
        rel = os.path.relpath(path, ROOT)
        for key, entry in catalog["strings"].items():
            if entry.get("shouldTranslate") is False:
                continue
            english = ios_english(key, entry)
            if not needs_translation(english):
                continue
            kind = "plurals" if isinstance(english, dict) else "string"
            # Keys with arguments are format strings; "%%" in a plain key is text.
            fmt = bool(PLACEHOLDER.search(key)) or (isinstance(english, dict))
            unit = Unit("ios", rel, key, english, kind, entry.get("comment", ""), fmt,
                        braces=os.path.basename(path) in CONTRACT_FILES)
            for lang in LANGS:
                unit.translations[lang] = ios_translation(entry, lang)
            units.append(unit)
    return units


def android_units():
    base = load_android("values")
    per_lang = {lang: load_android(f"values-{q}") for lang, q in LANGS.items()}
    ledgers = {lang: load_ledger(lang) for lang in LANGS}
    units = []
    for name, s in base.items():
        if not s.translatable or not needs_translation(s.value if not isinstance(s.value, list) else " ".join(s.value)):
            continue
        unit = Unit("android", s.file, name, s.value, s.kind, s.comment, android_is_format(s.value),
                    braces=s.file in CONTRACT_FILES)
        for lang in LANGS:
            other = per_lang[lang].get(name)
            state = "translated"
            review = ledgers[lang].get(name)
            if f"android:{name}" in SEPARATE and not (review and review.get("separate")):
                state = "new"  # was filled from the shared translation; needs its own
            elif review:
                # The English changed after this translation was written: translate again.
                state = "new" if review["source"] != source_hash(s.value) else review["state"]
            unit.translations[lang] = (other.value, state) if other else (None, None)
        units.append(unit)
    return units


def load_ledger(lang):
    """Android review ledger: {resource name: {"source": hash, "state": ...}}."""
    path = os.path.join(SHARED_L10N, "review", f"{lang}.json")
    if not os.path.exists(path):
        return {}
    with open(path, encoding="utf-8") as fh:
        return json.load(fh)


def all_units(platforms=("ios", "android")):
    units = []
    if "ios" in platforms:
        units += ios_units()
    if "android" in platforms:
        units += android_units()
    return units


def check_value(unit, lang, value):
    """List of problems with a translation (empty = OK)."""
    problems = []
    if value is None:
        return ["missing"]
    eng = unit.english
    if isinstance(value, dict) and isinstance(eng, str):
        # A key without an English plural entry: every form must keep its arguments.
        eng = {"other": eng}
    if isinstance(eng, dict):
        if not isinstance(value, dict):
            return ["expected plural forms"]
        need = PLURAL_CATEGORIES[lang]
        missing = [c for c in need if not value.get(c)]
        if missing:
            problems.append(f"missing plural forms {missing}")
        ref = placeholder_signature(eng.get("other", next(iter(eng.values()))), unit.braces)
        for cat, text in value.items():
            if text and sorted(placeholder_signature(text, unit.braces)) != sorted(ref) \
                    and cat not in ("one", "zero", "two"):
                problems.append(f"placeholders differ in '{cat}'")
        return problems
    if isinstance(eng, list):
        if not isinstance(value, list) or len(value) != len(eng):
            return ["array length differs"]
        return problems
    if not isinstance(value, str) or not value.strip():
        return ["empty"]
    if sorted(INTENT_PARAM.findall(value)) != sorted(INTENT_PARAM.findall(eng)):
        problems.append("App Intents ${…} parameters differ")
    if not unit.is_format:
        # Plain text: a "%" is just a percent sign; only {name} templates must match.
        if unit.braces and sorted(BRACE.findall(value)) != sorted(BRACE.findall(eng)):
            problems.append("placeholders differ")
        return problems
    if sorted(placeholder_signature(value, unit.braces)) != sorted(placeholder_signature(eng, unit.braces)):
        problems.append("placeholders differ")
    return problems


# ---------------------------------------------------------------- translation memory

def tokenise_value(value, braces=False):
    """(tokenised value, specs). Plural dicts and arrays are tokenised per entry;
    specs come from the richest form."""
    if isinstance(value, dict):
        out, specs = {}, []
        for cat, text in value.items():
            out[cat], s = to_tokens(text, braces)
            if len([x for x in s if x]) > len([x for x in specs if x]):
                specs = s
        return out, specs
    if isinstance(value, list):
        out = [to_tokens(t, braces)[0] for t in value]
        return out, []
    return to_tokens(value, braces)


SEPARATE = set(CONFIG.get("translate_separately", []))


def tm_key(unit):
    tokenised, _ = tokenise_value(unit.english, unit.braces)
    parts = [unit.kind if unit.kind != "string" else "s", tokenised]
    if f"{unit.platform}:{unit.key}" in SEPARATE:
        parts.append(f"{unit.platform}:{unit.key}")  # same English, different meaning
    return json.dumps(parts, ensure_ascii=False, sort_keys=True)


def tm_id(key):
    return hashlib.sha1(key.encode("utf-8")).hexdigest()[:10]


def detokenise(unit, lang, value):
    """Translator output (tokenised) -> platform value for this unit."""
    _, specs = tokenise_value(unit.english, unit.braces)
    sources = unit.english.values() if isinstance(unit.english, dict) else [unit.english] \
        if isinstance(unit.english, str) else []
    # Android rejects several non-positional arguments, so it always gets positions.
    positional = any(m.group(1) for s in sources for m in PLACEHOLDER.finditer(s)) or \
        (unit.platform == "android" and len([s for s in specs if s]) > 1)
    if isinstance(unit.english, dict) or isinstance(value, dict):
        if not isinstance(value, dict):
            raise ValueError("expected plural forms")
        out = {}
        for cat in PLURAL_CATEGORIES[lang]:
            text = value.get(cat) or value.get("other")
            if text is None:
                raise ValueError(f"missing plural form {cat}")
            # "one" may drop the number ("a day"), so tokens are checked leniently there.
            try:
                out[cat] = from_tokens(text, specs, True, positional)
            except ValueError:
                if cat not in ("one", "zero", "two"):
                    raise
                # These forms may drop the count ("a day", Arabic dual); keep the
                # remaining arguments at their positions.
                present = {int(i) for i in TOKEN.findall(text)}
                if not present:
                    out[cat] = text.replace("%", "%%")
                else:
                    masked = [s if (i + 1) in present else None for i, s in enumerate(specs)]
                    out[cat] = from_tokens(text, masked, True, True)
        return out
    if isinstance(unit.english, list):
        if not isinstance(value, list) or len(value) != len(unit.english):
            raise ValueError("array length differs")
        return [from_tokens(v, [], False) for v in value]
    text = from_tokens(value, specs, unit.is_format, positional)
    if unit.platform == "ios" and not unit.is_format:
        # Xcode reads "10%-dən" in a catalog as a "%-d" specifier and fails the build;
        # an invisible word joiner after the "%" keeps it plain text.
        text = SPEC_LIKE.sub("%\u2060", text)
    return text


CONTRACT_FILES = ("Contracts.xcstrings", "strings_contracts.xml")
SHORT_HINT_FILES = ("widget", "Widgets", "workout_widget", "WatchWidgets")


# ---------------------------------------------------------------- Android lint hygiene

_STRING_EL = re.compile(r'<string\s+name="([^"]+)"([^>]*)>(.*?)</string>', re.S)


def normalize_android():
    """Keep Android lint's translation checks green (docs/localization.md).

    * A plain-text string with a "%" (no format arguments) gets formatted="false"
      in every locale, so lint does not read "80% of" as a broken format.
    * Strings that need no translation (symbols, formats, product names) are
      copied into every locale that lacks them, so MissingTranslation stays an error.
    Returns the number of files changed.
    """
    base = load_android("values")
    plain_percent = {n for n, s in base.items()
                     if s.kind == "string" and "%" in s.value and not android_is_format(s.value)}
    changed = 0
    for folder in ["values"] + [f"values-{q}" for q in LANGS.values()]:
        for path in android_value_files(folder):
            with open(path, encoding="utf-8") as fh:
                text = fh.read()

            def fix(m):
                name, attrs, body = m.groups()
                if name in plain_percent and "formatted=" not in attrs:
                    return f'<string name="{name}"{attrs} formatted="false">{body}</string>'
                return m.group(0)

            new = _STRING_EL.sub(fix, text)
            if new != text:
                with open(path, "w", encoding="utf-8") as fh:
                    fh.write(new)
                changed += 1
    for q in LANGS.values():
        have = load_android(f"values-{q}")
        missing = [s for n, s in base.items()
                   if n not in have and s.translatable and s.kind == "string"
                   and not needs_translation(s.value)]
        by_file = {}
        for s in missing:
            by_file.setdefault(s.file, []).append((s.name, s.kind, s.value))
        for file_name, entries in by_file.items():
            write_android_values(f"values-{q}", file_name, entries)
            changed += 1
    if changed:
        # New copies of plain-percent strings need the attribute too.
        normalize_android_attrs_only(plain_percent)
    return changed


def normalize_android_attrs_only(plain_percent):
    for folder in [f"values-{q}" for q in LANGS.values()]:
        for path in android_value_files(folder):
            with open(path, encoding="utf-8") as fh:
                text = fh.read()
            new = _STRING_EL.sub(
                lambda m: (f'<string name="{m.group(1)}"{m.group(2)} formatted="false">{m.group(3)}</string>'
                           if m.group(1) in plain_percent and "formatted=" not in m.group(2) else m.group(0)),
                text)
            if new != text:
                with open(path, "w", encoding="utf-8") as fh:
                    fh.write(new)
