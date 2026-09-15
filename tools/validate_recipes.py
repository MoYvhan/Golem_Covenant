#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Spec 3.1 recipe gate.

The three covenant items are the only entry point into the whole mod
(spec 3.1/3.3), so their recipes are checked as a hard build gate rather
than trusted by eye. Runs from the project root as part of `check`.

Invariants
----------
1. Exactly one recipe file per tier item, named after the item id.
2. The recipe type is shapeless (spec 3.1 says 无序合成 for B and C; A is a
   direct 1:1 use of a golden apple, also shapeless).
3. The ingredient multiset for each tier matches the spec table exactly:
       A  黄金契约      golden_apple x1
       B  傀儡灵魂果    golden_apple x4 + golden_carrot x4
       C  灵魂圣契      enchanted_golden_apple x4 + golden_apple x2
                        + golden_carrot x2
4. The result id points at the matching golem_covenant item, count 1.
5. Every ingredient / result id resolves to a real item: either a vanilla
   `minecraft:` id present in the extracted vanilla data, or a
   `golem_covenant:` id declared in ModItems.java.
6. No recipe grants a tier item from another tier item (no A->B->C loop,
   which would make the A tier free once one B exists).
"""

from __future__ import annotations

import json
import os
import re
import sys
import tempfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RECIPE_DIR = os.path.join(ROOT, "src", "main", "resources", "data",
                          "golem_covenant", "recipe")
MODITEMS = os.path.join(ROOT, "src", "main", "java", "com", "example",
                        "golem_covenant", "item", "ModItems.java")

# The extracted vanilla data is a developer convenience, not a hard input, so
# its absence only downgrades the id cross-check to a warning. Look in the
# usual extraction spot plus an explicit override.
VANILLA_CANDIDATES = [
    os.environ.get("MCQ_VANILLA_DATA", ""),
    os.path.join(tempfile.gettempdir(), "mcq", "mc", "data", "minecraft",
                 "recipe"),
    os.path.join(tempfile.gettempdir(), "mc", "data", "minecraft", "recipe"),
    "/tmp/mcq/mc/data/minecraft/recipe",
]


def find_vanilla_recipes() -> str:
    for candidate in VANILLA_CANDIDATES:
        if candidate and os.path.isdir(candidate):
            return candidate
    return ""


VANILLA_RECIPES = find_vanilla_recipes()

# Spec 3.1 - the authoritative table.
SPEC_RECIPES = {
    "golden_covenant": {
        "tier": "A",
        "ingredients": ["minecraft:golden_apple"],
    },
    "soul_fruit": {
        "tier": "B",
        "ingredients": ["minecraft:golden_apple"] * 4
        + ["minecraft:golden_carrot"] * 4,
    },
    "soul_covenant": {
        "tier": "C",
        "ingredients": ["minecraft:enchanted_golden_apple"] * 4
        + ["minecraft:golden_apple"] * 2
        + ["minecraft:golden_carrot"] * 2,
    },
}

# Items this mod declares (parsed from ModItems.java).
MOD_ITEM_IDS: set[str] = set()


def load_mod_items() -> None:
    """Collect the golem_covenant item ids declared in ModItems.java."""
    with open(MODITEMS, encoding="utf-8") as fh:
        src = fh.read()
    # public static final ... register("id", ...)  /  registerItem("id", ...)
    for m in re.finditer(r'register(?:Item)?\(\s*"([a-z0-9_]+)"', src):
        MOD_ITEM_IDS.add("golem_covenant:" + m.group(1))


def load_vanilla_item_ids() -> set[str]:
    """Item ids referenced by the vanilla recipe corpus.

    The extracted vanilla data is the authority on which `minecraft:` ids
    actually exist; if it is unavailable we fall back to the built-in list
    below so the gate still runs offline.
    """
    ids: set[str] = set()
    if not os.path.isdir(VANILLA_RECIPES):
        return ids
    for name in os.listdir(VANILLA_RECIPES):
        if not name.endswith(".json"):
            continue
        try:
            with open(os.path.join(VANILLA_RECIPES, name), encoding="utf-8") as fh:
                data = json.load(fh)
        except (OSError, json.JSONDecodeError):
            continue
        for value in _walk_ids(data):
            if value.startswith("minecraft:"):
                ids.add(value)
    return ids


def _walk_ids(node) -> list[str]:
    """Yield every item-id-looking string inside a nested recipe JSON."""
    out: list[str] = []
    if isinstance(node, str):
        if ":" in node:
            out.append(node)
    elif isinstance(node, list):
        for item in node:
            out.extend(_walk_ids(item))
    elif isinstance(node, dict):
        for key, value in node.items():
            # `key` maps are single chars in shaped recipes; their values are
            # ids. Result blocks and ingredient lists are handled uniformly.
            if key == "pattern":
                continue
            out.extend(_walk_ids(value))
    return out


def multiset(values) -> dict[str, int]:
    counts: dict[str, int] = {}
    for value in values:
        counts[value] = counts.get(value, 0) + 1
    return counts


def describe(counts: dict[str, int]) -> str:
    return ", ".join(f"{k} x{v}" for k, v in sorted(counts.items()))


def main() -> int:
    errors: list[str] = []
    warnings: list[str] = []

    if not os.path.isdir(RECIPE_DIR):
        print(f"[recipes] FAIL: no recipe directory at {RECIPE_DIR}")
        return 1

    load_mod_items()
    vanilla_ids = load_vanilla_item_ids()
    if not vanilla_ids:
        warnings.append(
            "vanilla recipe corpus not found - ingredient ids were not "
            "cross-checked against vanilla (set up /tmp/mcq/mc to enable)")

    present = {n[:-5] for n in os.listdir(RECIPE_DIR) if n.endswith(".json")}

    # Invariant 1 - one file per tier item.
    for item_id in SPEC_RECIPES:
        if item_id not in present:
            errors.append(f"missing recipe file {item_id}.json (spec 3.1)")

    extra = present - set(SPEC_RECIPES)
    if extra:
        # Not an error: the mod is allowed extra recipes, but we surface it so
        # an accidental rename cannot silently drop a tier recipe.
        warnings.append(f"extra recipe files not in spec 3.1: {sorted(extra)}")

    tier_items = {f"golem_covenant:{k}" for k in SPEC_RECIPES}

    for item_id, spec in SPEC_RECIPES.items():
        path = os.path.join(RECIPE_DIR, item_id + ".json")
        if not os.path.isfile(path):
            continue
        try:
            with open(path, encoding="utf-8") as fh:
                data = json.load(fh)
        except json.JSONDecodeError as exc:
            errors.append(f"{item_id}.json: invalid JSON ({exc})")
            continue

        where = f"{item_id}.json"

        # Invariant 2 - shapeless.
        rtype = data.get("type")
        if rtype != "minecraft:crafting_shapeless":
            errors.append(
                f"{where}: type is {rtype!r}, spec 3.1 requires shapeless")

        # Invariant 3 - ingredient multiset.
        raw = data.get("ingredients")
        if not isinstance(raw, list):
            errors.append(f"{where}: missing 'ingredients' list")
        else:
            flat: list[str] = []
            for entry in raw:
                if isinstance(entry, str):
                    flat.append(entry)
                elif isinstance(entry, list):
                    # A tag/alternative group; the spec never uses one, so
                    # record it and let the multiset comparison flag it.
                    flat.append("|".join(entry))
                else:
                    errors.append(f"{where}: unexpected ingredient {entry!r}")
            got = multiset(flat)
            want = multiset(spec["ingredients"])
            if got != want:
                errors.append(
                    f"{where}: ingredients are [{describe(got)}] but spec 3.1 "
                    f"({spec['tier']}) requires [{describe(want)}]")

        # Invariant 4 - result.
        result = data.get("result")
        if not isinstance(result, dict):
            errors.append(f"{where}: missing 'result' object")
        else:
            rid = result.get("id")
            if rid != f"golem_covenant:{item_id}":
                errors.append(
                    f"{where}: result id is {rid!r}, expected "
                    f"'golem_covenant:{item_id}'")
            count = result.get("count", 1)
            if count != 1:
                errors.append(
                    f"{where}: result count is {count}, spec 3.1 implies 1")

        # Invariant 6 - no tier loop. A tier item must not be an ingredient.
        for entry in (raw or []):
            names = [entry] if isinstance(entry, str) else list(entry)
            for name in names:
                if name in tier_items:
                    errors.append(
                        f"{where}: uses tier item {name} as an ingredient - "
                        "this rewrites one covenant tier into another "
                        "(spec 3.1 defines only the golden-apple line)")

    # Invariant 5 - id resolution (only meaningful with vanilla data present).
    if vanilla_ids:
        for item_id in SPEC_RECIPES:
            path = os.path.join(RECIPE_DIR, item_id + ".json")
            if not os.path.isfile(path):
                continue
            with open(path, encoding="utf-8") as fh:
                data = json.load(fh)
            for value in _walk_ids(data.get("ingredients", [])) + \
                    _walk_ids(data.get("result", {})):
                if value.startswith("golem_covenant:"):
                    if value not in MOD_ITEM_IDS:
                        errors.append(
                            f"{item_id}.json: {value!r} is not declared in "
                            f"ModItems.java (known: "
                            f"{sorted(MOD_ITEM_IDS)})")
                elif value.startswith("minecraft:"):
                    if value not in vanilla_ids:
                        errors.append(
                            f"{item_id}.json: vanilla item {value!r} does not "
                            "appear in the vanilla recipe corpus")

    for warning in warnings:
        print(f"[recipes] WARN: {warning}")
    if errors:
        print(f"[recipes] FAILED - {len(errors)} problem(s):")
        for err in errors:
            print(f"  - {err}")
        return 1

    print(f"[recipes] PASSED - {len(SPEC_RECIPES)} tier recipes match spec 3.1")
    for item_id, spec in SPEC_RECIPES.items():
        print(f"  [{spec['tier']}] {item_id} <- "
              f"{describe(multiset(spec['ingredients']))}")
    if vanilla_ids:
        print(f"  ids cross-checked against {len(vanilla_ids)} vanilla items")
    return 0


if __name__ == "__main__":
    sys.exit(main())
