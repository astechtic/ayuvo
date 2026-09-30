#!/usr/bin/env python3
"""Write the strings that still need translating as batches (docs/localization.md).

  python3 scripts/l10n/l10n_extract.py --work DIR [--lang de --lang fr] [--batch-size 120]

Same English text on iOS and Android is translated once: every batch item is a
unique source string, with placeholders replaced by ⟦1⟧, ⟦2⟧ … tokens. Strings
already translated elsewhere in the same language are reused (autofill.json).

Output per language in DIR/<lang>/:
  batch_001.json …   items to translate: {"id", "en", "forms"?, "context", "short"?}
  autofill.json      {id: translation} reused from existing translations
The translator writes batch_001.out.json = {id: translation} next to each batch.
"""
import argparse
import json
import os
import sys
from collections import defaultdict

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from l10n_lib import (LANGS, PLURAL_CATEGORIES, SHORT_HINT_FILES, all_units,  # noqa: E402
                      check_value, tm_id, tm_key, tokenise_value)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--work", required=True)
    ap.add_argument("--lang", action="append")
    ap.add_argument("--batch-size", type=int, default=120)
    ap.add_argument("--platform", choices=["ios", "android"], action="append")
    args = ap.parse_args()
    langs = args.lang or list(LANGS)
    units = all_units(tuple(args.platform or ("ios", "android")))

    by_key = defaultdict(list)
    for u in units:
        by_key[tm_key(u)].append(u)

    index = {}
    for lang in langs:
        memory = {}
        for key, group in by_key.items():
            for u in group:
                value, state = u.translations[lang]
                if state != "new" and not check_value(u, lang, value):
                    memory[key] = tokenise_value(value, u.braces)[0]
                    break
        pending, autofill = [], {}
        for key, group in by_key.items():
            if all(not check_value(u, lang, u.translations[lang][0]) and u.translations[lang][1] != "new"
                   for u in group):
                continue
            ident = tm_id(key)
            index[ident] = key
            if key in memory:
                autofill[ident] = memory[key]
                continue
            kind, tokenised = json.loads(key)[:2]
            context = []
            for u in group[:3]:
                bits = [u.platform, os.path.basename(u.file)]
                if u.key != (u.english if isinstance(u.english, str) else None):
                    bits.append(f"key={u.key}")
                if u.comment:
                    bits.append(u.comment)
                context.append(" | ".join(bits))
            item = {"id": ident, "en": tokenised, "context": context}
            if kind == "plurals":
                item["forms"] = PLURAL_CATEGORIES[lang]
            if kind == "string-array":
                item["array"] = True
            if any(h in u.file for u in group for h in SHORT_HINT_FILES):
                item["short"] = True
            pending.append(item)

        folder = os.path.join(args.work, lang)
        os.makedirs(folder, exist_ok=True)
        for name in os.listdir(folder):
            if name.startswith("batch_") and not name.endswith(".out.json"):
                os.remove(os.path.join(folder, name))
        for n in range(0, len(pending), args.batch_size):
            path = os.path.join(folder, f"batch_{n // args.batch_size + 1:03d}.json")
            with open(path, "w", encoding="utf-8") as fh:
                json.dump({"lang": lang, "items": pending[n:n + args.batch_size]}, fh, ensure_ascii=False, indent=1)
        with open(os.path.join(folder, "autofill.json"), "w", encoding="utf-8") as fh:
            json.dump(autofill, fh, ensure_ascii=False, indent=1)
        print(f"{lang}: {len(pending)} to translate in {-(-len(pending) // args.batch_size)} batches, "
              f"{len(autofill)} reused")

    index_path = os.path.join(args.work, "index.json")
    if os.path.exists(index_path):
        with open(index_path, encoding="utf-8") as fh:
            index = {**json.load(fh), **index}
    with open(index_path, "w", encoding="utf-8") as fh:
        json.dump(index, fh, ensure_ascii=False)


if __name__ == "__main__":
    main()
