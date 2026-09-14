#!/usr/bin/env python3
"""Static invariant checks for the Bond (ch.9) and Resonance (ch.10) systems.

These checks are deliberately *static*: they assert that the source code
adheres to the spec's design rules, because those rules are the whole point of
the two chapters and are easy to violate by accident later.

Checked invariants
------------------
1. spec 9.3 "Bond 不应该增加纯数值" - no Bond code path may write a raw stat
   (attack damage / max health / armour / movement speed attribute modifiers).
2. spec 9.4 thresholds - the BondStage ranges in CovenantData must tile
   0..100 with no gaps and no overlaps.
3. spec 9.4 anti-farm - BondEngine must expose a daily cap.
4. spec 10.1 named pairs - all three names from the spec must exist as
   resonances, with the anchor-family pairs they are built from.
5. spec 10.2 "构筑而非数值" - resonance effects must not apply attribute
   modifiers; they must only touch AI/targeting.
6. registry anchors - every anchor family used by the resonance table must
   actually exist in forms_registry.json.
"""
from __future__ import annotations

import json
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, "src", "main", "java", "com", "example",
                   "golem_covenant")
BOND = os.path.join(SRC, "bond", "BondEngine.java")
RESONANCE = os.path.join(SRC, "team", "ResonanceEngine.java")
COVENANT_DATA = os.path.join(SRC, "data", "CovenantData.java")
REGISTRY = os.path.join(ROOT, "src", "main", "resources", "data",
                        "golem_covenant", "forms_registry.json")

failures: list[str] = []
notes: list[str] = []


def fail(msg: str) -> None:
    failures.append(msg)


def read(path: str) -> str:
    if not os.path.exists(path):
        fail(f"missing file: {os.path.relpath(path, ROOT)}")
        return ""
    with open(path, encoding="utf-8") as fh:
        return fh.read()


# --- 1 & 3: bond must not grant raw stats, must cap farm --------------------

def check_bond() -> None:
    src = read(BOND)
    if not src:
        return

    stat_writes = re.findall(
        r"(getAttribute|AttributeModifier|ADD_ATTRIBUTE|setMaxHealth|"
        r"ATTACK_DAMAGE|MAX_HEALTH|ARMOR|MOVEMENT_SPEED|ATTACK_SPEED)", src)
    if stat_writes:
        fail("spec 9.3 violated: BondEngine writes raw stats "
             f"({sorted(set(stat_writes))})")
    else:
        notes.append("bond grants no raw stats (spec 9.3) OK")

    if "PASSIVE_DAILY_CAP" not in src:
        fail("spec 9.4: BondEngine has no daily passive cap (anti-farm)")
    else:
        notes.append("bond passive daily cap present (spec 9.4) OK")

    for token in ("MAX_BOND", "ABSENT_DISTANCE", "allowedDepth", "hasDepth"):
        if token not in src:
            fail(f"BondEngine missing expected member: {token}")

    # spec 9.4 award table must be present
    for token in ("AWARD_FOLLOW", "AWARD_SHARED_KILL", "AWARD_RESCUE",
                  "AWARD_ANCHOR_EVENT", "PENALTY_ABSENT"):
        if token not in src:
            fail(f"BondEngine missing award constant: {token} (spec 9.4 table)")


# --- 2: bond stage thresholds must tile 0..100 ------------------------------

def check_stages() -> None:
    src = read(COVENANT_DATA)
    if not src:
        return
    block = re.search(r"enum BondStage\s*\{(.*?);", src, re.S)
    if not block:
        fail("CovenantData.BondStage enum not found")
        return
    entries = re.findall(r"(\w+)\s*\(\s*\"[^\"]*\"\s*,\s*(\d+)\s*,\s*(\d+)\s*\)",
                         block.group(1))
    if not entries:
        fail("could not parse BondStage ranges")
        return
    spans = sorted((int(a), int(b), n) for n, a, b in entries)
    # must start at 0 and end at 100
    if spans[0][0] != 0:
        fail(f"BondStage starts at {spans[0][0]}, expected 0")
    if spans[-1][1] != 100:
        fail(f"BondStage ends at {spans[-1][1]}, expected 100")
    for i in range(1, len(spans)):
        prev_max = spans[i - 1][1]
        cur_min = spans[i][0]
        if cur_min != prev_max + 1:
            fail(f"BondStage gap/overlap between {spans[i-1][2]} "
                 f"(max {prev_max}) and {spans[i][2]} (min {cur_min})")
    if not failures:
        notes.append(f"bond stages tile 0..100 across {len(spans)} stages OK")


# --- 4 & 5 & 6: resonance ---------------------------------------------------

SPEC10_PAIRS = {
    "close_far": ("animal", "zombie"),
    "space_web": ("nether_end", "arthropod"),
    "echo_snipe": ("nether_end", "zombie"),
}


def check_resonance() -> None:
    src = read(RESONANCE)
    if not src:
        return

    for rid, (fa, fb) in SPEC10_PAIRS.items():
        if f'"{rid}"' not in src:
            fail(f"spec 10.1: resonance '{rid}' missing from ResonanceEngine")
            continue
        if f'"{fa}"' not in src or f'"{fb}"' not in src:
            fail(f"spec 10.1: resonance '{rid}' anchor families "
                 f"({fa}, {fb}) not referenced")
    if not failures:
        notes.append("all 3 spec-10.1 named resonances present OK")

    stat_writes = re.findall(
        r"(getAttribute|AttributeModifier|setMaxHealth|ATTACK_DAMAGE|"
        r"MAX_HEALTH|MOVEMENT_SPEED|ATTACK_SPEED)", src)
    if stat_writes:
        fail("spec 10.2 violated: ResonanceEngine writes raw stats "
             f"({sorted(set(stat_writes))})")
    else:
        notes.append("resonance changes behaviour only, no stats (spec 10.2) OK")

    # resonance must be gated behind bond (spec 9.4 <- 10.1 dependency)
    if "MIN_BOND" not in src:
        fail("spec 10.1: resonance is not gated on Bond")
    else:
        notes.append("resonance gated on bond OK")

    if "onResonanceEvent" not in src:
        fail("ResonanceEngine does not feed BondEngine (spec 9.4 锚点事件)")

    # familyOf must exclude the reserved band
    if "reserved" not in src:
        fail("ResonanceEngine.familyOf does not exclude 'reserved' anchors")


def check_anchor_families() -> None:
    """Anchor-family derivation must agree between the validator and the Java
    code, and every family used by the resonance table must exist.

    Note the prefix trap: the registry's *familyId* is ``nether_end`` and so is
    the anchor prefix, but a naive ``split("_")[0]`` yields ``nether``. The
    Java side therefore matches against an explicit longest-first prefix list;
    this check asserts that list is complete and correct.
    """
    with open(REGISTRY, encoding="utf-8") as fh:
        reg = json.load(fh)

    # the prefix list declared in ResonanceEngine
    src = read(RESONANCE)
    m = re.search(r"FAMILY_PREFIXES\s*=\s*List\.of\((.*?)\);", src, re.S)
    if not m:
        fail("ResonanceEngine.FAMILY_PREFIXES not found")
        return
    declared = re.findall(r'"([^"]+)"', m.group(1))

    # every non-reserved anchor must start with exactly one declared prefix
    actual_anchors = {f["anchorId"] for f in reg["forms"]
                      if f.get("anchorId")
                      and not f["anchorId"].startswith("reserved")}
    unmatched = []
    used_fams = set()
    for aid in actual_anchors:
        hit = [p for p in declared if aid.startswith(p + "_")]
        if not hit:
            unmatched.append(aid)
            continue
        # longest match wins, and there must be no ambiguity
        used_fams.add(max(hit, key=len))
    if unmatched:
        fail(f"anchors not covered by FAMILY_PREFIXES: {sorted(unmatched)[:5]}"
             f"{' ...' if len(unmatched) > 5 else ''}")

    # A prefix must not be shadowed by an earlier, shorter prefix: if
    # prefix B starts with prefix A + "_", then A must not precede B.
    for i, a in enumerate(declared):
        for b in declared[i + 1:]:
            if b.startswith(a + "_"):
                fail(f"FAMILY_PREFIXES shadowing: '{a}' precedes longer "
                     f"'{b}' which extends it")
                break

    used = {fam for pair in SPEC10_PAIRS.values() for fam in pair}
    missing = used - used_fams
    if missing:
        fail(f"resonance references anchor families absent from registry: "
             f"{sorted(missing)}")
    else:
        notes.append(f"resonance anchor families all present "
                     f"({len(used)} checked) OK")
    notes.append(f"family prefixes cover all {len(actual_anchors)} anchors "
                 f"across {len(used_fams)} families OK")


def main() -> int:
    check_bond()
    check_stages()
    check_resonance()
    check_anchor_families()

    print("=" * 68)
    print("bond / resonance invariant check")
    print("=" * 68)
    for n in notes:
        print(f"  [ok]   {n}")
    for f in failures:
        print(f"  [FAIL] {f}")
    print("-" * 68)
    if failures:
        print(f"FAILED - {len(failures)} violation(s)")
        return 1
    print(f"PASSED - {len(notes)} invariants hold")
    return 0


if __name__ == "__main__":
    sys.exit(main())
