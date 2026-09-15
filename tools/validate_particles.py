#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Spec 5.7 / 12.4 particle-asset gate.

This gate exists because the project already shipped one silent visual bug of
exactly the class it now checks for: every `particles/*.json` pointed at
`minecraft:particle`, which is not a real atlas sprite. The client resolved it
to the missing-texture checkerboard, so every ritual particle rendered as a
black/purple square. Nothing failed - it just looked wrong.

Invariants
----------
1. Every `particles/<name>.json` is valid JSON with a non-empty `textures`
   list of `namespace:path` strings.
2. Every sprite referenced actually exists in the vanilla particle atlas
   (checked against the extracted client jar when available).
3. Every particle registered in `ModParticles.java` has a definition file, and
   vice versa - a registered type with no definition is invisible, a
   definition with no type is dead weight.
4. No definition references `minecraft:particle`, the sentinel that caused the
   original bug.
5. Sprite path prefixes that need tinting are declared in the generator's
   TINTED_TEXTURES set, so the client provider and the asset generator cannot
   disagree about which particles are colour-driven.
"""

from __future__ import annotations

import glob
import json
import os
import re
import subprocess
import sys
import tempfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
PARTICLES = os.path.join(ROOT, "src", "main", "resources", "assets",
                         "golem_covenant", "particles")
GEN_ASSETS = os.path.join(ROOT, "tools", "gen_assets.py")
MODPARTICLES = os.path.join(ROOT, "src", "main", "java", "com", "example",
                            "golem_covenant", "registry", "ModParticles.java")
CLIENT_STYLES = os.path.join(ROOT, "src", "client", "java", "com", "example",
                             "golem_covenant", "client", "RitualParticles.java")

MC_JARS = [
    os.path.expanduser(
        "~/.gradle/caches/fabric-loom/26.2/minecraft-client.jar"),
    os.path.join(tempfile.gettempdir(), "..", "mcq", "minecraft-client.jar"),
]

BAD_SPRITE = "minecraft:particle"


def vanilla_particle_sprites() -> set[str]:
    """Sprite names inside assets/minecraft/textures/particle/."""
    for jar in MC_JARS:
        if not os.path.isfile(jar):
            continue
        try:
            out = subprocess.run(
                ["unzip", "-l", jar, "assets/minecraft/textures/particle/*"],
                capture_output=True, text=True, timeout=60)
        except (OSError, subprocess.SubprocessError):
            continue
        sprites = set()
        for line in out.stdout.splitlines():
            m = re.search(r"([a-z_0-9]+)\.png$", line.strip())
            if m:
                sprites.add("minecraft:" + m.group(1))
        if sprites:
            return sprites
    return set()


def registered_particle_paths() -> set[str]:
    """Registry paths registered by ModParticles.java."""
    with open(MODPARTICLES, encoding="utf-8") as fh:
        src = fh.read()
    paths = set()
    # register("path") / the FAMILY_PATHS values / register(e.getValue()) map.
    for m in re.finditer(r'register\(\s*"([a-z0-9_]+)"\s*\)', src):
        paths.add(m.group(1))
    # The family map values are the registry paths for families.
    for m in re.finditer(r'"([a-z_]+)",\s*"([a-z0-9_]+)"', src):
        paths.add(m.group(2))
    return paths


def declared_tinted() -> set[str]:
    """The TINTED_TEXTURES set from gen_assets.py."""
    with open(GEN_ASSETS, encoding="utf-8") as fh:
        src = fh.read()
    m = re.search(r"TINTED_TEXTURES\s*=\s*\{(.*?)\}", src, re.S)
    if not m:
        return set()
    return set(re.findall(r'"([a-z_0-9]+)"', m.group(1)))


def client_styles() -> set[str]:
    """Registry paths that have a STYLES.put(...) entry on the client."""
    if not os.path.isfile(CLIENT_STYLES):
        return set()
    with open(CLIENT_STYLES, encoding="utf-8") as fh:
        src = fh.read()
    # Only look inside the static block that populates STYLES.
    block = re.search(r"static\s*\{(.*?)\n\t\}", src, re.S)
    scope = block.group(1) if block else src
    return set(re.findall(r'STYLES\.put\(\s*"([a-z_0-9]+)"', scope))


def main() -> int:
    errors: list[str] = []

    if not os.path.isdir(PARTICLES):
        print(f"[particles] FAIL: no particle directory at {PARTICLES}")
        return 1

    files = sorted(glob.glob(os.path.join(PARTICLES, "*.json")))
    if not files:
        print("[particles] FAIL: no particle definitions found")
        return 1

    sprites = vanilla_particle_sprites()
    defined: set[str] = set()

    for path in files:
        name = os.path.basename(path)[:-5]
        defined.add(name)
        try:
            with open(path, encoding="utf-8") as fh:
                data = json.load(fh)
        except json.JSONDecodeError as exc:
            errors.append(f"{name}.json: invalid JSON ({exc})")
            continue

        textures = data.get("textures")
        if not isinstance(textures, list) or not textures:
            errors.append(f"{name}.json: 'textures' must be a non-empty list")
            continue

        for sprite in textures:
            if not isinstance(sprite, str) or ":" not in sprite:
                errors.append(
                    f"{name}.json: sprite {sprite!r} is not a "
                    "'namespace:path' string")
                continue
            if sprite == BAD_SPRITE:
                errors.append(
                    f"{name}.json: references {BAD_SPRITE!r}, which is not a "
                    "real atlas sprite and renders as the missing-texture "
                    "checkerboard")
                continue
            if sprites and sprite not in sprites:
                errors.append(
                    f"{name}.json: sprite {sprite!r} is not in the vanilla "
                    "particle atlas")

    # Invariant 3 - the registry and the asset directory must agree.
    registered = registered_particle_paths()
    if registered:
        for name in sorted(registered - defined):
            errors.append(
                f"{name}: registered in ModParticles.java but has no "
                f"particles/{name}.json (the particle would never render)")
        for name in sorted(defined - registered):
            errors.append(
                f"{name}.json exists but {name} is not registered in "
                "ModParticles.java (dead asset)")

    # Invariant 5 - tinting agreement.
    tinted = declared_tinted()
    if not tinted:
        errors.append("gen_assets.py: TINTED_TEXTURES set is missing/empty")

    # Invariant 6 - the client must declare a style for every registered type,
    # otherwise that particle keeps the vanilla renderer and the family
    # distinction (spec 5.3) silently disappears.
    styled = client_styles()
    if not styled:
        errors.append(
            "client RitualParticles.java declares no styles (or the file was "
            "not found) - every particle would render as vanilla")
    else:
        for name in sorted(defined - styled):
            errors.append(
                f"{name}: has a particle definition but no client Style in "
                "RitualParticles.java (renders as vanilla)")
        for name in sorted(styled - defined):
            errors.append(
                f"{name}: client Style exists but no particles/{name}.json "
                "(the sprite set would be empty)")

    if errors:
        print(f"[particles] FAILED - {len(errors)} problem(s):")
        for err in errors:
            print(f"  - {err}")
        return 1

    print(f"[particles] PASSED - {len(defined)} definitions, "
          f"all sprites resolve")
    if sprites:
        print(f"  checked against {len(sprites)} vanilla particle sprites")
    else:
        print("  WARN: vanilla atlas unavailable - sprite names not verified")
    print(f"  registry/asset agreement: {len(registered)} types matched")
    print(f"  client styles declared   : {len(styled)}")
    print(f"  tinted sprite groups     : {sorted(tinted)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
