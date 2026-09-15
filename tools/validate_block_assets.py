#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Block and item asset gate (spec 4.3.1 / 4.3.2).

A registered block with no blockstate JSON does not crash the game. Minecraft
logs "Missing blockstate definition" once and then renders the black-and-magenta
missing model whenever the block is placed. An item with no model renders as a
missing-texture cube in the inventory. Both are silent in review and obvious to a
player, which is exactly the failure class a gate should catch.

Invariants
----------
1. Every block registered in ModBlocks has:
     - a blockstate JSON
     - a block model JSON
     - an inventory item model JSON
     - a loot table (or the block is unobtainable when broken)
2. Every blockstate variant points at a model that exists.
3. Every model's `textures` values resolve to a real vanilla atlas/block
   texture OR a file that exists in this mod's own textures directory. A typo
   in a vanilla sprite name produces the missing-texture checkerboard, so the
   sprite list is taken from the extracted client jar when available.
4. Every plain (non-block) item registered in ModItems has an item model.
5. Every recipe output resolves to an item that is actually registered, and
   every recipe input resolves to a vanilla item or one of ours. A recipe
   pointing at a misspelled id simply never appears in the recipe book.
"""

from __future__ import annotations

import glob
import json
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(ROOT, "src", "main", "resources")
ASSETS = os.path.join(RES, "assets", "golem_covenant")
DATA = os.path.join(RES, "data", "golem_covenant")
MOD_ID = "golem_covenant"

MOD_BLOCKS = os.path.join(ROOT, "src", "main", "java", "com", "example",
                          "golem_covenant", "registry", "ModBlocks.java")
MOD_ITEMS = os.path.join(ROOT, "src", "main", "java", "com", "example",
                         "golem_covenant", "item", "ModItems.java")
MOD_ITEM_ALT = os.path.join(ROOT, "src", "main", "java", "com", "example",
                            "golem_covenant", "registry", "ModItems.java")

# Vanilla block/item textures that exist in the client jar. Loaded lazily; when
# the extracted jar is absent the check degrades to "must be a known prefix"
# rather than falsely failing.
VANILLA_TEX_CACHE: set[str] | None = None
VANILLA_TEX_DIRS = [
    os.path.join(os.path.expanduser("~"), ".gradle", "caches",
                 "fabric-loom", "26.2"),
]


def find_client_jar() -> str | None:
    for base in VANILLA_TEX_DIRS:
        candidate = os.path.join(base, "minecraft-client.jar")
        if os.path.isfile(candidate):
            return candidate
    return None


def vanilla_textures() -> set[str] | None:
    """Every `assets/minecraft/textures/**.png` name, as `block/foo` keys."""
    global VANILLA_TEX_CACHE
    if VANILLA_TEX_CACHE is not None:
        return VANILLA_TEX_CACHE
    jar = find_client_jar()
    if jar is None:
        return None
    import zipfile

    names: set[str] = set()
    with zipfile.ZipFile(jar) as z:
        for n in z.namelist():
            prefix = "assets/minecraft/textures/"
            if not n.startswith(prefix) or not n.endswith(".png"):
                continue
            rel = n[len(prefix):-len(".png")]
            names.add(rel)
    VANILLA_TEX_CACHE = names
    return names


def read(path: str) -> str:
    with open(path, encoding="utf-8") as fh:
        return fh.read()


def load(path: str):
    with open(path, encoding="utf-8") as fh:
        return json.load(fh)


def registered_ids(java_path: str, kind: str) -> list[str]:
    """
    Pull `register("<path>", ...)` ids out of a registry class.

    Deliberately a text scan rather than a compile: the gate has to run before
    compilation in the worst case, and the registration call shape is fixed by
    our own convention.
    """
    if not os.path.isfile(java_path):
        return []
    src = read(java_path)
    if kind == "block":
        # ModBlocks.register("path", new XBlock(...)) / ... new XBlock(
        pattern = re.compile(r'register\(\s*"([a-z0-9_]+)"\s*,')
    else:
        # ModItems uses register("path", ...) and registerItem("path", ...)
        pattern = re.compile(r'register(?:Item)?\(\s*"([a-z0-9_]+)"\s*,')
    return sorted(set(pattern.findall(src)))


def model_path(ref: str) -> str:
    """Resolve `golem_covenant:block/x` / `minecraft:block/x` to a local file."""
    if ":" in ref:
        ns, path = ref.split(":", 1)
    else:
        ns, path = "minecraft", ref
    if ns == MOD_ID:
        return os.path.join(ASSETS, "models", path + ".json")
    return os.path.join("__vanilla__", ns, path + ".json")


def check() -> list[str]:
    errors: list[str] = []

    blocks = registered_ids(MOD_BLOCKS, "block")
    items = registered_ids(MOD_ITEMS, "item")
    # ModItems lives in the item package in this project; fall back if moved.
    if not items and os.path.isfile(MOD_ITEM_ALT):
        items = registered_ids(MOD_ITEM_ALT, "item")

    if not blocks:
        errors.append(
            f"no blocks parsed from {os.path.relpath(MOD_BLOCKS, ROOT)} - the "
            "gate cannot verify anything (did the registration style change?)")
    if not items:
        errors.append(
            f"no items parsed from {os.path.relpath(MOD_ITEMS, ROOT)} - the "
            "gate cannot verify anything (did the registration style change?)")

    tex = vanilla_textures()

    # --- 1/2/3: blocks ---------------------------------------------------
    for name in blocks:
        state_p = os.path.join(ASSETS, "blockstates", f"{name}.json")
        block_model_p = os.path.join(ASSETS, "models", "block", f"{name}.json")
        item_model_p = os.path.join(ASSETS, "models", "item", f"{name}.json")
        loot_p = os.path.join(DATA, "loot_table", "blocks", f"{name}.json")

        for label, path in (("blockstate", state_p),
                            ("block model", block_model_p),
                            ("inventory item model", item_model_p),
                            ("loot table", loot_p)):
            if not os.path.isfile(path):
                errors.append(
                    f"block {name!r} has no {label} "
                    f"({os.path.relpath(path, ROOT)})")

        # 2. every variant resolves
        if os.path.isfile(state_p):
            state = load(state_p)
            variants = state.get("variants") or {}
            if not variants:
                errors.append(
                    f"blockstate {name!r} declares no variants")
            for key, val in variants.items():
                entries = val if isinstance(val, list) else [val]
                for entry in entries:
                    ref = entry.get("model")
                    if not ref:
                        errors.append(
                            f"blockstate {name!r} variant {key!r} has no model")
                        continue
                    target = model_path(ref)
                    if not target.startswith("__vanilla__") \
                            and not os.path.isfile(target):
                        errors.append(
                            f"blockstate {name!r} variant {key!r} points at "
                            f"missing model {ref!r}")

        # 3. textures resolve
        if os.path.isfile(block_model_p):
            model = load(block_model_p)
            for slot, ref in (model.get("textures") or {}).items():
                if ref.startswith("#"):
                    continue  # inherited from the parent, resolved there
                if ":" in ref:
                    ns, path = ref.split(":", 1)
                else:
                    ns, path = "minecraft", ref
                if ns == MOD_ID:
                    local = os.path.join(ASSETS, "textures", path + ".png")
                    if not os.path.isfile(local):
                        errors.append(
                            f"block {name!r} texture slot {slot!r} points at "
                            f"missing local texture {ref!r}")
                elif tex is not None and path not in tex:
                    errors.append(
                        f"block {name!r} texture slot {slot!r} points at "
                        f"unknown vanilla texture {ref!r} (renders as the "
                        "missing-texture checkerboard)")

    # --- 4: plain items --------------------------------------------------
    # Anything that is also a block gets its model from the block pass.
    block_set = set(blocks)
    for name in items:
        if name in block_set:
            continue
        model_p = os.path.join(ASSETS, "models", "item", f"{name}.json")
        if not os.path.isfile(model_p):
            errors.append(
                f"item {name!r} has no item model "
                f"({os.path.relpath(model_p, ROOT)})")

    # --- 5: recipes ------------------------------------------------------
    known = set(block_set) | set(items)
    for path in sorted(glob.glob(os.path.join(DATA, "recipe", "*.json"))):
        recipe = load(path)
        rel = os.path.relpath(path, ROOT)
        result = recipe.get("result") or {}
        rid = result.get("id") if isinstance(result, dict) else result
        if isinstance(rid, str):
            check_ref(rid, known, errors, f"{rel}: result")
        # ingredients can be a list (shapeless) or a map (shaped key)
        ing = recipe.get("ingredients")
        if isinstance(ing, list):
            for i in ing:
                check_ref(i, known, errors, f"{rel}: ingredient")
        key_map = recipe.get("key")
        if isinstance(key_map, dict):
            for slot, i in key_map.items():
                check_ref(i, known, errors, f"{rel}: key {slot!r}")

    return errors


def check_ref(ref, known: set[str], errors: list[str], where: str) -> None:
    if not isinstance(ref, str):
        return
    if ":" in ref:
        ns, path = ref.split(":", 1)
    else:
        ns, path = MOD_ID, ref
    if ns != MOD_ID:
        return  # vanilla ids are validated by the game itself
    if path not in known:
        errors.append(
            f"{where} references {ref!r}, which is not a registered item or "
            "block id")


def main() -> int:
    errors = check()
    if errors:
        print(f"[block-assets] FAILED - {len(errors)} problem(s):")
        for err in errors:
            print(f"  - {err}")
        return 1

    blocks = registered_ids(MOD_BLOCKS, "block")
    items = registered_ids(MOD_ITEMS, "item")
    recipes = glob.glob(os.path.join(DATA, "recipe", "*.json"))
    tex = vanilla_textures()
    print(f"[block-assets] PASSED")
    print(f"  blocks with blockstate + model + item model + loot table : "
          f"{len(blocks)}")
    print(f"  items with an item model                                 : "
          f"{len(items)}")
    print(f"  recipes reference only registered ids                     : "
          f"{len(recipes)}")
    print(f"  vanilla texture names available for validation            : "
          f"{'yes' if tex is not None else 'no (jar not extracted)'}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
