#!/usr/bin/env python3
"""Generates particle definitions, sounds.json and block/item models.

Both are pure derivations of the Java registries (ModParticles / ModSounds),
so they are generated rather than hand-maintained. A missing particle
definition makes Minecraft log "Redundant texture list" / fail to draw the
particle at all; a missing sounds.json entry makes the sound silent.

The block models matter for the same reason: a registered block with no
blockstate JSON reports "Missing blockstate definition" and renders as the
black-and-magenta missing model, and an item with no model shows as a
missing-texture cube in the inventory.
"""
from __future__ import annotations

import json
import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ASSETS = os.path.join(ROOT, "src", "main", "resources", "assets",
                      "golem_covenant")
PARTICLES = os.path.join(ASSETS, "particles")
BLOCKSTATES = os.path.join(ASSETS, "blockstates")
MODELS_BLOCK = os.path.join(ASSETS, "models", "block")
MODELS_ITEM = os.path.join(ASSETS, "models", "item")
MOD_ID = "golem_covenant"

# Mirrors ModParticles.FAMILY_PATHS (family -> registry path)
FAMILY_PATHS = {
    "zombie": "soul_ash",
    "arthropod": "silk_strand",
    "animal": "beast_ember",
    "mount": "hoof_dust",
    "snow": "frost_mote",
    "environment": "biome_spore",
    "aquatic": "tide_drop",
    "construct": "iron_spark",
    "nether_end": "void_shard",
    "special": "detonation_glyph",
}
EXTRA_PARTICLES = ["core_spark", "revive_burst"]

# Mirrors ModSounds.WILL_THEMES + the three ritual cues.
WILL_THEMES = ["guard", "scout", "heal", "element", "control", "mobility"]
RITUALS = ["ritual_a", "ritual_b", "ritual_c"]

# Particle definition textures.
#
# The earlier build pointed every definition at "minecraft:particle", which is
# not a real atlas sprite: the client resolves an unknown sprite to the
# missing-texture (black/purple) checkerboard, and single-frame "generic"
# sprites look like floating squares. Each particle now maps onto a real
# vanilla particle-atlas sprite set, so shapes read correctly out of the box,
# and a resource pack can still override any of them (spec 11.15: 粒子必须
# 可用且可替换).
PARTICLE_TEXTURES = {
    # family particles
    "soul_ash": ["minecraft:sculk_soul_{}".format(i) for i in range(11)],
    "silk_strand": ["minecraft:generic_{}".format(i) for i in range(8)],
    "beast_ember": ["minecraft:flame"],
    "hoof_dust": ["minecraft:generic_{}".format(i) for i in range(8)],
    "frost_mote": ["minecraft:nautilus", "minecraft:glint",
                   "minecraft:bubble_white"],
    "biome_spore": ["minecraft:generic_{}".format(i) for i in range(8)],
    "tide_drop": ["minecraft:bubble", "minecraft:splash_0",
                  "minecraft:splash_1", "minecraft:splash_2",
                  "minecraft:splash_3"],
    "iron_spark": ["minecraft:critical_hit"],
    "void_shard": ["minecraft:spell_{}".format(i) for i in range(8)],
    "detonation_glyph": ["minecraft:flash"],
    # shared structural particles
    "core_spark": ["minecraft:glitter_{}".format(i) for i in range(8)],
    "revive_burst": ["minecraft:flash", "minecraft:glint"],
}

# Sprite sets that are pure white/grey in the atlas, so the client provider
# must tint them; the rest keep their vanilla colours.
TINTED_TEXTURES = {
    "soul_ash", "silk_strand", "hoof_dust", "biome_spore", "generic"}

# Colour tints per family so the ten families are visually distinct on day one.
FAMILY_TINT = {
    "soul_ash": "#8FA88C",
    "silk_strand": "#C9D6C4",
    "beast_ember": "#B4763C",
    "hoof_dust": "#A08B6A",
    "frost_mote": "#BFE4F2",
    "biome_spore": "#7FA65C",
    "tide_drop": "#4E9BC4",
    "iron_spark": "#C9A227",
    "void_shard": "#7A5CA8",
    "detonation_glyph": "#D9483B",
    "core_spark": "#E8D48B",
    "revive_burst": "#9FE6B0",
}


def write_particles() -> int:
    os.makedirs(PARTICLES, exist_ok=True)
    names = list(FAMILY_PATHS.values()) + EXTRA_PARTICLES
    for n in names:
        textures = PARTICLE_TEXTURES.get(n)
        if textures is None:
            # Never emit a definition pointing at a non-existent sprite: that
            # is exactly the bug this generator used to ship.
            raise SystemExit(
                f"gen_assets: no texture set declared for particle {n!r}")
        body = {
            "textures": textures,
        }
        with open(os.path.join(PARTICLES, f"{n}.json"), "w",
                  encoding="utf-8") as fh:
            json.dump(body, fh, indent=2)
            fh.write("\n")
    return len(names)


def write_sounds() -> int:
    cues = {}
    for t in WILL_THEMES:
        cues[f"death_will_{t}"] = {"sounds": [f"{MOD_ID}:death_will_{t}"]}
    for r in RITUALS:
        cues[r] = {"sounds": [f"{MOD_ID}:{r}"]}
    path = os.path.join(ASSETS, "sounds.json")
    with open(path, "w", encoding="utf-8") as fh:
        json.dump(cues, fh, ensure_ascii=False, indent=2, sort_keys=True)
        fh.write("\n")
    return len(cues)


# ---------------------------------------------------------------------------
# Blocks (spec 4.3.1 / 4.3.2).
#
# Each entry is a full model parent + texture map. Vanilla textures are used
# deliberately: the addon ships no block art, so pointing at real vanilla
# sprites means the blocks look correct on day one and a resource pack can
# still replace them (spec 11.15: assets must be usable and replaceable).
# The bottom/top/side split is what makes them read as crafted furniture
# rather than recoloured stone.
# ---------------------------------------------------------------------------
BLOCKS = {
    "covenant_stele": {
        # A carved pedestal: polished blackstone body, deepslate cap.
        "parent": "minecraft:block/cube_bottom_top",
        "textures": {
            "bottom": "minecraft:block/polished_blackstone",
            "top": "minecraft:block/chiseled_polished_blackstone",
            "side": "minecraft:block/polished_blackstone_bricks",
        },
    },
    "soul_altar": {
        # A crying-obsidian plinth with a soul-soil top, echoing the soul
        # theme without needing a bespoke texture.
        "parent": "minecraft:block/cube_bottom_top",
        "textures": {
            "bottom": "minecraft:block/obsidian",
            "top": "minecraft:block/soul_soil",
            "side": "minecraft:block/crying_obsidian",
        },
    },
}

# Item models for the plain (non-block) items. The three covenant items and
# the two expansion resources all need one, or they render as a missing cube.
# Parents point at vanilla item models so the sprites resolve immediately.
ITEMS = {
    "golden_covenant": "minecraft:item/golden_apple",
    "soul_fruit": "minecraft:item/golden_apple",
    "soul_covenant": "minecraft:item/golden_apple",
    "capacity_shard": "minecraft:item/amethyst_shard",
    "altar_core": "minecraft:item/echo_shard",
}


def write_json(path: str, body: dict) -> None:
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8") as fh:
        json.dump(body, fh, indent=2)
        fh.write("\n")


def write_blocks() -> int:
    """Emit a blockstate + block model + item model per block."""
    for name, spec in BLOCKS.items():
        # A single-variant blockstate. "variants" with one "" key is the
        # correct shape for a block with no state properties.
        write_json(os.path.join(BLOCKSTATES, f"{name}.json"), {
            "variants": {
                "": {"model": f"{MOD_ID}:block/{name}"}
            }
        })
        write_json(os.path.join(MODELS_BLOCK, f"{name}.json"), {
            "parent": spec["parent"],
            "textures": spec["textures"],
        })
        # The inventory model: parented to the block model so the item form
        # shows the same face the placed block does.
        write_json(os.path.join(MODELS_ITEM, f"{name}.json"), {
            "parent": f"{MOD_ID}:block/{name}"
        })
    return len(BLOCKS)


def write_items() -> int:
    """Emit an item model per plain item, parented at a vanilla sprite."""
    for name, parent in ITEMS.items():
        write_json(os.path.join(MODELS_ITEM, f"{name}.json"), {
            "parent": parent
        })
    return len(ITEMS)


def main() -> int:
    p = write_particles()
    s = write_sounds()
    b = write_blocks()
    i = write_items()
    print("asset generation")
    print("=" * 68)
    print(f"  particles/*.json : {p}")
    print(f"  sounds.json      : {s} cues")
    print(f"  blocks           : {b} (blockstate + block model + item model)")
    print(f"  items            : {i} item models")
    print("=" * 68)
    print("PASSED")
    return 0


if __name__ == "__main__":
    sys.exit(main())
