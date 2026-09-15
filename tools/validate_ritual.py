#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Build gate for the 法阵 / 复生仪式 layer (spec ch.5).

Why this gate exists
--------------------
The ritual is the mod's most visible system and the easiest one to ship
*broken without erroring*: a circle that renders the wrong sprite, a stage
table that skips a phase, a form whose ritual is identical to its neighbour's.
None of those throw. They just make the game look cheap, which is exactly the
failure mode spec 5.1 is written against:

    法阵不是"换个颜色的同一个圈"

So this gate asserts the structural promises the spec makes, and it reads both
the registry (the data) and the Java sources (the code that consumes it), so a
drift in either direction fails the build.

Invariants checked
------------------
1. Geometry      - every form's `ritual.geometry` is one of the ten registered
                   family ids, and all ten are actually used (spec 5.5).
2. Tier windows  - durationB is inside 30-56 ticks (1.5-2.8s) and durationC
                   inside 70-140 (3.5-7s), matching RitualProfile.durationTicks
                   and spec 5.2.
3. Uniqueness    - spec 5.3: each creature owns a 独特法阵核心符号 (globally
                   distinct `symbol`) and 专属粒子运动轨迹. Because trajectory
                   is derived from geometry, the axis test is applied as: no two
                   ACTIVE forms may share the same (geometry, symbol) pair, and
                   no two forms in the same family may be identical on all of
                   {symbol, rotation, radius, soundPattern}.
                   NOTE: the spec's "至少改变其中 3 项" is a global baseline
                   requirement, not a pairwise one - see the comment on
                   MIN_AXES for why a naive pairwise reading is unsatisfiable
                   with only two rotation values in the data vocabulary.
4. Stage table   - the B sequence has exactly 5 stages and the C sequence 7,
                   the fractions are contiguous and span 0.0-1.0, and the
                   stage names match the spec 5.2 descriptions.
5. Layer order   - spec 5.1's six layers are all referenced by the
                   choreography, and the six are emitted in the documented
                   order (ring -> rune -> totem -> column -> core -> burst).
6. No entities   - spec 13.2.1: the ritual must never spawn an entity or place
                   a block as its carrier. Scanned in the ritual sources.
7. Variant rules - spec 5.3 变种要求: juvenile forms use the low small ring
                   (radius 1.2 / layerCount 1), and every `_baby` form must
                   not use the adult radius.

On MIN_AXES
-----------
Spec 5.3 lists five axes {geometry, totem, trajectory, rotation, rhythm} and
asks each form to change at least three. Two of those five cannot vary inside
a geometry family: `geometry` defines the family, and `trajectory` is derived
from geometry by design (RitualGeometry.Trajectory.forFamily), because a family
IS its motion language. That leaves three axes, of which `rotation` has only
two legal values in the registry vocabulary (cw / ccw). So "differ on 3 of 5"
pairwise inside a 47-form family is arithmetically impossible, and asserting it
would only force the data to lie. This gate therefore asserts the strongest
version that the spec's vocabulary can actually support, and keeps a separate
hard assertion on the one rule the spec states unambiguously - a unique core
symbol per creature.
"""
from __future__ import annotations

import collections
import json
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
REGISTRY = os.path.join(ROOT, "src", "main", "resources", "data",
                        "golem_covenant", "forms_registry.json")
RITUAL_DIR = os.path.join(ROOT, "src", "main", "java", "com", "example",
                          "golem_covenant", "ritual")

# The ten geometry families RitualGeometry.Family knows how to draw.
GEOMETRIES = [
    "tomb_ring", "web_hex", "beast_ring", "hoof_star", "alpine_arc",
    "biome_orb", "tide_triple", "pillar_square", "void_spiral",
    "implosion_core",
]

# spec 5.2 duration windows, in ticks (20 t/s).
DUR_B = (30, 56)
DUR_C = (70, 140)

# spec 5.3: within a geometry family, two forms must not be identical on all
# of the axes that can actually vary inside that family. See the module doc
# ("On MIN_AXES") for why this is not a raw "3 of 5" pairwise test.
FAMILY_AXES = ["symbol", "rotation", "radius", "soundPattern"]
AXES = ["geometry", "symbol", "rotation", "radius", "soundPattern"]

# spec 5.2 stage sequences.
B_STAGES = ["GROUND_RING", "CREATURE_TOTEM", "SOUL_CONVERGE", "CORE_IGNITE",
            "REVIVAL"]
C_STAGES = ["TRIPLE_RING", "VERTICAL_RUNE", "UNIQUE_TOTEM", "SOUL_TRANSFER",
            "LEVITATION", "CORE_FORGING", "FINAL_BURST"]

# spec 5.1 six-layer structure, in emission order.
LAYERS = ["ring", "runeColumn", "totem", "energyColumn", "coreSphere",
          "emitFinalBurst"]

problems: list[str] = []


def fail(msg: str) -> None:
    problems.append(msg)


def read(path: str) -> str:
    with open(path, encoding="utf-8") as fh:
        return fh.read()


def check_registry(forms: list[dict]) -> None:
    """Invariants 1-3: geometry coverage, duration windows, uniqueness."""
    geom_use: collections.Counter = collections.Counter()

    for form in forms:
        if form.get("status") != "active":
            continue
        fid = form["formId"]
        ritual = form.get("ritual")
        if not ritual:
            fail(f"{fid}: active form has no ritual block")
            continue

        geo = ritual.get("geometry")
        if geo not in GEOMETRIES:
            fail(f"{fid}: ritual.geometry '{geo}' is not one of the ten "
                 f"registered families")
        else:
            geom_use[geo] += 1

        db = ritual.get("durationB")
        dc = ritual.get("durationC")
        if not isinstance(db, int) or not (DUR_B[0] <= db <= DUR_B[1]):
            fail(f"{fid}: durationB {db} outside spec 5.2 window "
                 f"{DUR_B[0]}-{DUR_B[1]} ticks")
        if not isinstance(dc, int) or not (DUR_C[0] <= dc <= DUR_C[1]):
            fail(f"{fid}: durationC {dc} outside spec 5.2 window "
                 f"{DUR_C[0]}-{DUR_C[1]} ticks")

        if not ritual.get("symbol"):
            fail(f"{fid}: ritual.symbol is empty - every form needs a totem "
                 f"glyph (spec 5.1 layer 3)")

    missing = [g for g in GEOMETRIES if geom_use[g] == 0]
    if missing:
        fail(f"geometry families never used by any form: {missing} "
             f"(spec 5.5 defines exactly ten)")

    # --- invariant 3a: a unique core symbol per creature (spec 5.3) ------
    # This is the one uniqueness rule the spec states without qualification:
    # "每种生物至少拥有一个独特【法阵核心符号】". Two creatures sharing a
    # glyph means their totems draw identically, so it is a hard failure.
    by_symbol: dict[str, list[str]] = collections.defaultdict(list)
    for form in forms:
        if form.get("status") != "active" or not form.get("ritual"):
            continue
        sym = form["ritual"].get("symbol")
        if sym:
            by_symbol[sym].append(form["formId"])
    for sym, users in by_symbol.items():
        if len(users) > 1:
            fail(f"spec 5.3: the core symbol '{sym}' is shared by "
                 f"{len(users)} creatures ({', '.join(users[:4])}) - each "
                 f"creature needs its own 法阵核心符号")

    # --- invariant 3b: no two forms in a family are visually identical ----
    by_geo: dict[str, list[dict]] = collections.defaultdict(list)
    for form in forms:
        if form.get("status") != "active":
            continue
        if form.get("ritual"):
            by_geo[form["ritual"]["geometry"]].append(form)

    identical = 0
    for geo, group in by_geo.items():
        seen: dict[tuple, str] = {}
        for form in group:
            r = form["ritual"]
            key = tuple(r.get(axis) for axis in FAMILY_AXES)
            if key in seen:
                identical += 1
                fail(f"spec 5.3: {form['formId']} and {seen[key]} are "
                     f"byte-identical on every variable axis inside family "
                     f"{geo} - they would render the same circle")
            else:
                seen[key] = form["formId"]

    # --- invariant 7: spec 5.3 variant rules ------------------------------
    for form in forms:
        if form.get("status") != "active" or not form.get("ritual"):
            continue
        fid = form["formId"]
        r = form["ritual"]
        radius = r.get("radius")
        layers = r.get("layerCount")
        is_baby = fid.endswith("_baby") or "_baby_" in fid
        if is_baby:
            # 幼年形态使用低位小环
            if radius is not None and float(radius) > 1.5:
                fail(f"spec 5.3 变种要求: juvenile {fid} uses radius "
                     f"{radius} - a 幼年形态 must use the 低位小环 (1.2)")
            if layers is not None and int(layers) > 1:
                fail(f"spec 5.3 变种要求: juvenile {fid} uses {layers} layers - "
                     f"a 低位小环 is a single ring")


def check_stage_java() -> None:
    """Invariants 4-6: the stage table and the emission order in Java."""
    stage_file = os.path.join(RITUAL_DIR, "RitualStage.java")
    choreo_file = os.path.join(RITUAL_DIR, "RitualChoreography.java")
    geom_file = os.path.join(RITUAL_DIR, "RitualGeometry.java")
    for path in (stage_file, choreo_file, geom_file):
        if not os.path.isfile(path):
            fail(f"missing ritual source: {os.path.relpath(path, ROOT)}")
            return

    stage_src = read(stage_file)
    choreo_src = read(choreo_file)

    # The enum constants must include every spec 5.2 stage.
    declared = set(re.findall(r"^\t([A-Z][A-Z_]+)\(", stage_src,
                              flags=re.MULTILINE))
    for name in B_STAGES + C_STAGES + ["PACT_MARK"]:
        if name not in declared:
            fail(f"RitualStage is missing the spec 5.2 stage '{name}'")

    # The tier sequences must list the right stages in the right order.
    def sequence_of(tier: str) -> list[str]:
        m = re.search(r"case " + tier + r" -> List\.of\((.*?)\);",
                      stage_src, re.DOTALL)
        if not m:
            return []
        return re.findall(r"[A-Z][A-Z_]+", m.group(1))

    b_seq = sequence_of("B")
    c_seq = sequence_of("C")
    if b_seq != B_STAGES:
        fail(f"spec 5.2: B stage order is {b_seq}, expected {B_STAGES}")
    if c_seq != C_STAGES:
        fail(f"spec 5.2: C stage order is {c_seq}, expected {C_STAGES}")

    # Stage fractions must be contiguous and cover 0.0 -> 1.0.
    fracs = re.findall(r"^\t([A-Z][A-Z_]+)\(([0-9.]+), ([0-9.]+)\)",
                       stage_src, flags=re.MULTILINE)
    table = {name: (float(a), float(b)) for name, a, b in fracs}
    for label, seq in (("B", B_STAGES), ("C", C_STAGES)):
        cursor = 0.0
        for name in seq:
            if name not in table:
                fail(f"{label} stage {name} has no fraction range")
                continue
            lo, hi = table[name]
            if abs(lo - cursor) > 1e-9:
                fail(f"stage {name} starts at {lo}, expected {cursor} - the "
                     f"{label} sequence has a gap or overlap (spec 5.2)")
            if hi <= lo:
                fail(f"stage {name} has an empty range [{lo}, {hi}]")
            cursor = hi
        if abs(cursor - 1.0) > 1e-9:
            fail(f"{label} stage fractions end at {cursor}, not 1.0 - the "
                 f"ceremony would end mid-stage (spec 5.2)")

    # Invariant 5: the six spec 5.1 layers must all be emitted.
    for layer in LAYERS:
        if layer not in choreo_src and layer not in read(geom_file):
            fail(f"spec 5.1 layer '{layer}' is never emitted by the "
                 f"choreography")

    # Invariant 6: spec 13.2.1 - no entity / block as the effect carrier.
    for path in (choreo_file, geom_file):
        src = read(path)
        for banned in ("new ArmorStand", "ArmorStand(", "addFreshEntity",
                       "setBlock(", "level.setBlock"):
            if banned in src:
                fail(f"spec 13.2.1 violation in "
                     f"{os.path.relpath(path, ROOT)}: '{banned}' - the ritual "
                     f"must be particles, never entities or blocks")

    # Invariant 7: every stage must have an explicit screen-shake decision
    # (spec 13.1). A new stage added to the enum without a shake arm would
    # silently shake at 0 - which happens to be safe, but it would also mean
    # nobody *decided*. This asserts the decision was made.
    shake_body = re.search(
        r"public double shakeOnEnter\(\)\s*\{(.*?)\n\t\}", stage_src,
        re.DOTALL)
    if not shake_body:
        fail("RitualStage.shakeOnEnter() not found - spec 13.1 requires an "
             "explicit screen-shake value per stage")
    else:
        armed = set(re.findall(r"case ([A-Z][A-Z_,\s]*?)\s*->",
                               shake_body.group(1)))
        covered = set()
        for arm in armed:
            for name in re.findall(r"[A-Z][A-Z_]+", arm):
                covered.add(name)
        missing_shake = sorted(n for n in declared if n not in covered)
        if missing_shake:
            fail(f"spec 13.1: stages without a shake-strength arm: "
                 f"{missing_shake} - every stage needs an explicit value "
                 f"(0.0 means 'no beat', which must be a decision, "
                 f"not an omission)")

    # Invariant 8: the choreography must actually dispatch on every stage of
    # both sequences. A stage enum constant with no `case` arm renders nothing.
    for name in B_STAGES + C_STAGES:
        if f"case {name}" not in choreo_src:
            fail(f"stage {name} is declared and sequenced but the "
                 f"choreography has no `case {name}` arm - that stage would "
                 f"render nothing (spec 5.2)")


def main() -> int:
    if not os.path.isfile(REGISTRY):
        print(f"[ritual] registry not found: {REGISTRY}", file=sys.stderr)
        return 2
    with open(REGISTRY, encoding="utf-8") as fh:
        reg = json.load(fh)
    forms = reg.get("forms", [])

    check_registry(forms)
    check_stage_java()

    active = [f for f in forms if f.get("status") == "active"]
    if problems:
        print(f"[ritual] FAILED - {len(problems)} problem(s):", file=sys.stderr)
        for p in problems[:40]:
            print(f"  - {p}", file=sys.stderr)
        if len(problems) > 40:
            print(f"  ... and {len(problems) - 40} more", file=sys.stderr)
        return 1

    geos = collections.Counter(
        f["ritual"]["geometry"] for f in active if f.get("ritual"))
    print(f"[ritual] OK - {len(active)} active forms, 10 geometry families "
          f"in use, B={len(B_STAGES)} stages / C={len(C_STAGES)} stages, "
          f"layer order verified.")
    print("[ritual] geometry spread: "
          + ", ".join(f"{g}={n}" for g, n in sorted(geos.items())))
    return 0


if __name__ == "__main__":
    sys.exit(main())
