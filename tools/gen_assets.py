#!/usr/bin/env python3
"""Generates particle definitions and sounds.json for the addon.

Both are pure derivations of the Java registries (ModParticles / ModSounds),
so they are generated rather than hand-maintained. A missing particle
definition makes Minecraft log "Redundant texture list" / fail to draw the
particle at all; a missing sounds.json entry makes the sound silent.
"""
from __future__ import annotations

import json
import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ASSETS = os.path.join(ROOT, "src", "main", "resources", "assets",
                      "golem_covenant")
PARTICLES = os.path.join(ASSETS, "particles")
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

# Per-particle texture is a vanilla texture reused as a stand-in until a
# resource pack supplies real art (spec 11.15:粒子必须可用且可替换).
PARTICLE_TEXTURE = "minecraft:particle"

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
        # `simple` needs only a texture list; the tint is applied by the
        # client-side particle provider registered in the client sourceset.
        body = {
            "textures": [PARTICLE_TEXTURE],
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


def main() -> int:
    p = write_particles()
    s = write_sounds()
    print("asset generation")
    print("=" * 68)
    print(f"  particles/*.json : {p}")
    print(f"  sounds.json      : {s} cues")
    print("=" * 68)
    print("PASSED")
    return 0


if __name__ == "__main__":
    sys.exit(main())
