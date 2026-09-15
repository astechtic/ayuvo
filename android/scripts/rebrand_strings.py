#!/usr/bin/env python3
"""Phase A4 of the Ayuvo rebrand: string resources.

Works on every `res/values*/strings.xml` (18 locales) plus `health_data.xml`,
`health_nutrition_import.xml` and the debug2 override:

1. deletes the entries for removed features (pricing, socials, Udyam/ACE,
   GitHub, hosted AI, Weekly Challenge leftovers, meal-link import) in every
   locale,
2. drops the locale copies of entries whose English meaning changed (they fall
   back to the rewritten English — `MissingTranslation` lint is disabled),
3. replaces the brand tokens inside element text only (never inside `name=`),
4. prints any remaining brand mention for manual review.

Pure regex over the entry blocks; nothing is re-serialised, so translations,
escapes and comments outside the touched entries stay byte-identical.
Idempotent: the second run reports zero changes.
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

ANDROID = Path(__file__).resolve().parents[1]
RES = ANDROID / "app/src/main/res"
FILES = sorted(RES.glob("values*/strings.xml")) + [
    RES / "values/health_data.xml",
    RES / "values/health_nutrition_import.xml",
    ANDROID / "app/src/debug2/res/values/strings.xml",
]
KOTLIN_ROOTS = [ANDROID / "app/src/main/java", ANDROID / "app/src/debug/java"]

TOKENS: list[tuple[str, str]] = [  # longest first; applied to element text only
    ("https://www.fud-ai.app", "https://ayuvo-health.web.app"),
    ("https://fud-ai.app", "https://ayuvo-health.web.app"),
    ("fud-ai.app", "ayuvo-health.web.app"),
    ("Fud AI Debug 2", "Ayuvo Debug 2"),
    ("Fud AI Debug", "Ayuvo Debug"),
    ("Fud AI’s", "Ayuvo’s"),
    ("Fud AI's", "Ayuvo's"),
    ("Fud AI\\'s", "Ayuvo\\'s"),
    ("Fud AI", "Ayuvo"),
    ("Fud AI", "Ayuvo"),
    ("FudAI", "Ayuvo"),
]

# Removed everywhere (feature gone).
DELETE = {
    "about_open_source", "about_openssf_best_practices", "about_openssf_best_practices_desc",
    "about_openssf_scorecard", "about_openssf_scorecard_desc", "about_udyam", "about_udyam_desc",
    "about_ace", "about_ace_desc", "about_star_github", "about_vote_ph", "about_leave_tip_kofi",
    "about_report_issue", "about_request_feature", "about_join_discord", "about_follow_x",
    "about_follow_linkedin", "about_follow_instagram", "about_category_support",
    "about_category_community", "about_category_help_feedback", "about_made_by", "about_with_care",
    "onboarding_ai_hosted_card_title", "onboarding_ai_hosted_card_subtitle", "onboarding_ai_hosted_badge",
    "about_whats_new", "about_whats_new_version_format", "about_whats_new_android_summary",
    "health_back_home", "health_source_fud_ai",
    "import_note", "import_add_to_log", "import_add_to_log_many_format", "import_add_meals_title",
}
DELETE_PREFIX = ("whats_new_v30_",)
# Removed from locales only: the English copy was rewritten with a new meaning.
DELETE_LOCALE_ONLY = {
    "about_share_message", "onboarding_ai_choice_subtitle", "coach_empty_subtitle", "about_contact",
    "theme_color_fud_pink", "onboarding_welcome_line1", "onboarding_welcome_line2",
    "onboarding_welcome_subtitle", "onboarding_feature_snap", "onboarding_feature_coach",
    "onboarding_feature_library", "onboarding_feature_widgets",
}

ENTRY = re.compile(
    r'[ \t]*<(string|plurals|string-array)\s+name="([^"]+)"[^>]*>.*?</\1>[ \t]*\n', re.S
)
RESIDUE = re.compile(r"Fud\s?AI|FudAI|fud-ai|Apoorv|apoorv|Fud\b", re.I)


def kotlin_references(keys: set[str]) -> dict[str, list[str]]:
    hits: dict[str, list[str]] = {}
    for root in KOTLIN_ROOTS:
        for path in root.rglob("*.kt"):
            text = path.read_text(encoding="utf-8")
            for key in keys:
                if re.search(rf"R\.(string|plurals)\.{re.escape(key)}\b", text):
                    hits.setdefault(key, []).append(str(path.relative_to(ANDROID)))
    return hits


def rewrite(path: Path) -> tuple[int, int, list[str]]:
    is_locale = path.parent.name != "values"
    text = path.read_text(encoding="utf-8")
    deleted = replaced = 0
    out = []
    pos = 0
    for m in ENTRY.finditer(text):
        out.append(text[pos:m.start()])
        pos = m.end()
        name = m.group(2)
        if name in DELETE or name.startswith(DELETE_PREFIX) or (is_locale and name in DELETE_LOCALE_ONLY):
            deleted += 1
            continue
        block = m.group(0)
        head_end = block.index(">") + 1
        head, body = block[:head_end], block[head_end:]
        new_body = body
        for old, new in TOKENS:
            new_body = new_body.replace(old, new)
        if new_body != body:
            replaced += 1
        out.append(head + new_body)
    out.append(text[pos:])
    new_text = "".join(out)
    if new_text != text:
        path.write_text(new_text, encoding="utf-8")
    residue = [
        f"{path.relative_to(ANDROID)}:{n}: {line.strip()[:120]}"
        for n, line in enumerate(new_text.splitlines(), 1)
        if RESIDUE.search(line)
    ]
    return deleted, replaced, residue


def main() -> int:
    refs = kotlin_references(DELETE | {k for k in DELETE_LOCALE_ONLY if k == "theme_color_fud_pink"})
    for prefix in DELETE_PREFIX:
        for root in KOTLIN_ROOTS:
            for path in root.rglob("*.kt"):
                if f"R.string.{prefix}" in path.read_text(encoding="utf-8"):
                    refs.setdefault(prefix + "*", []).append(str(path.relative_to(ANDROID)))
    if refs:
        print("Refusing to delete keys still referenced from Kotlin:")
        for key, paths in sorted(refs.items()):
            print(f"  {key}: {', '.join(paths)}")
        return 1
    total_deleted = total_replaced = 0
    residue: list[str] = []
    for path in FILES:
        if not path.exists():
            continue
        d, r, res = rewrite(path)
        total_deleted += d
        total_replaced += r
        residue += res
    print(f"deleted {total_deleted} entries, rewrote {total_replaced} entries")
    if residue:
        print("Residue for manual review:")
        print("\n".join(residue))
    return 0


if __name__ == "__main__":
    sys.exit(main())
