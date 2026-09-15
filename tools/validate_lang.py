#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Translation-key completeness gate (spec 11.13 / 11.15.4).

A missing `Component.translatable("...")` key does not throw. The player just
sees the raw key string, e.g. `golem_covenant.cmd.soul_pool`, in chat. That
makes every localisation mistake a silent one, which is why this gate parses
the Java sources for translation keys and checks them against the generated
lang files.

Invariants
----------
1. Every literal `Component.translatable("key")` in the Java sources exists in
   both en_us.json and zh_cn.json.
2. Every key composed at runtime as `"prefix." + expr` has at least one
   sibling key sharing that prefix, so a typo'd prefix is caught even though
   the full key cannot be known statically.
3. en_us.json and zh_cn.json have identical key sets.
4. No en_us value contains CJK text (spec 11.15.4: en_us must be real English).
5. No value is an untranslated copy of its own key.
"""

from __future__ import annotations

import glob
import json
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
LANG_DIR = os.path.join(ROOT, "src", "main", "resources", "assets",
                        "golem_covenant", "lang")
SRC_DIRS = [
    os.path.join(ROOT, "src", "main", "java"),
    os.path.join(ROOT, "src", "client", "java"),
]

CJK = re.compile(r"[\u3400-\u4dbf\u4e00-\u9fff\uf900-\ufaff\uff00-\uffef]")

# Keys the vanilla game already provides. Referencing one of these is correct
# and must not be reported as a missing translation: adding our own copy would
# also override the base game's translations for every other language.
VANILLA_KEYS = {
    "gui.done", "gui.cancel", "gui.back", "gui.yes", "gui.no", "gui.ok",
    "gui.toTitle", "gui.continue", "gui.proceed",
}


def java_sources() -> list[str]:
    files: list[str] = []
    for base in SRC_DIRS:
        files.extend(glob.glob(os.path.join(base, "**", "*.java"),
                               recursive=True))
    return files


def literal_keys() -> dict[str, set[str]]:
    """Explicit translatable("...") keys -> the files that use them.

    A key followed by `+` is a dynamic prefix, not a literal key, so it is
    excluded here and handled by dynamic_prefixes().
    """
    found: dict[str, set[str]] = {}
    pattern = re.compile(r'translatable\(\s*"([^"]+)"(\s*\+)?')
    for path in java_sources():
        with open(path, encoding="utf-8") as fh:
            src = fh.read()
        for match in pattern.finditer(src):
            if match.group(2):
                continue  # dynamic: handled separately
            found.setdefault(match.group(1), set()).add(os.path.basename(path))
    return found


def dynamic_prefixes() -> dict[str, set[str]]:
    """Keys built as `"prefix." + expr` -> files that use them."""
    found: dict[str, set[str]] = {}
    # translatable("a.b." + something)  /  translatable("a.b." + (cond ? ...))
    # The prefix may or may not end in a dot; normalise to no trailing dot.
    pattern = re.compile(r'translatable\(\s*"([a-z0-9_.]*)\.?"\s*\+')
    for path in java_sources():
        with open(path, encoding="utf-8") as fh:
            src = fh.read()
        for match in pattern.finditer(src):
            prefix = match.group(1)
            if prefix:
                found.setdefault(prefix, set()).add(os.path.basename(path))
    return found


def load(name: str) -> dict:
    path = os.path.join(LANG_DIR, name)
    with open(path, encoding="utf-8") as fh:
        return json.load(fh)


def main() -> int:
    errors: list[str] = []

    en = load("en_us.json")
    zh = load("zh_cn.json")

    # Invariant 3 - identical key sets.
    missing_zh = set(en) - set(zh)
    missing_en = set(zh) - set(en)
    for key in sorted(missing_zh):
        errors.append(f"zh_cn.json is missing key {key!r}")
    for key in sorted(missing_en):
        errors.append(f"en_us.json is missing key {key!r}")

    # Invariant 1 - literal keys resolve.
    #
    # Vanilla keys are exempt: `gui.done`, `gui.cancel` etc. are supplied by
    # Minecraft itself, so requiring them in our lang file would be wrong (and
    # would shadow the base game's own translations for other languages).
    literals = literal_keys()
    for key, files in sorted(literals.items()):
        if key in VANILLA_KEYS:
            continue
        if key not in en:
            errors.append(
                f"{key!r} used in {sorted(files)} has no lang entry "
                "(it would render as the raw key in chat)")

    # Invariant 2 - dynamic prefixes have at least one real sibling.
    known = set(en)
    for prefix, files in sorted(dynamic_prefixes().items()):
        if not any(k.startswith(prefix) for k in known):
            errors.append(
                f"prefix {prefix!r} (used in {sorted(files)}) matches no lang "
                "key - the composed key would render raw")

    # Invariant 4 / 5 - en_us quality.
    for key, value in en.items():
        if not isinstance(value, str):
            continue
        if CJK.search(value):
            errors.append(
                f"en_us.json[{key!r}] contains CJK text: {value!r}")
        if value == key:
            errors.append(
                f"en_us.json[{key!r}] is a copy of its own key")

    # Invariant 6 - no two forms collapse to the same display NAME.
    #
    # Only `.name` is checked. The name identifies the form in the tooltip and
    # the /golem output, so two identical names make the output ambiguous.
    #
    # The five ability sub-keys are deliberately NOT checked. Reading the
    # registry shows why:
    #
    #   bActiveId   186 distinct  - one per form (escaped CJK id)
    #   bPassiveId   19 distinct  - shared vocabulary
    #   cSecondId    30 distinct  - shared vocabulary
    #   deathWillId  29 distinct  - shared vocabulary
    #
    # `death_will = "speed"` is genuinely carried by 32 different forms; their
    # tooltips *should* all read "Move Speed", because it is the same death
    # will. Flagging that would force 32 invented synonyms for one mechanic,
    # which is worse for the reader, not better. Uniqueness only matters for
    # the name, where it is the form's identity.
    form_prefix = "golem_covenant.form."
    name_suffix = ".name"
    by_value: dict[str, list[str]] = {}
    for key, value in en.items():
        if not key.startswith(form_prefix) or not key.endswith(name_suffix):
            continue
        if not isinstance(value, str):
            continue
        form_id = key[len(form_prefix):-len(name_suffix)]
        by_value.setdefault(value, []).append(form_id)
    for value, ids in sorted(by_value.items()):
        if len(ids) > 1:
            errors.append(
                f"{len(ids)} forms share the English name {value!r}: "
                f"{sorted(ids)} - add one to DISAMBIGUATE_EN in gen_lang.py")

    if errors:
        print(f"[lang] FAILED - {len(errors)} problem(s):")
        for err in errors:
            print(f"  - {err}")
        return 1

    print(f"[lang] PASSED - {len(en)} keys, en/zh identical")
    print(f"  literal keys referenced in Java : {len(literals)}")
    print(f"  dynamic prefixes verified       : {len(dynamic_prefixes())}")
    print("  en_us free of CJK text          : OK")
    return 0


if __name__ == "__main__":
    sys.exit(main())
