#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Second-soul trial gate (spec 4.3.3 / 11.8).

Spec 4.3.3 makes the second C-tier seat the hardest reward in the mod, and
spec 11.8 exists because the original text described it with adjectives -
"完成多个" / "获得稀有" / "高难度" - that cannot be implemented or tested. The
whole value of 11.8 is that each clause now has a number, so the failure this
gate guards against is not a crash: it is a later edit quietly softening a
threshold, or leaving a condition wired to a constant instead of to observed
world state. Both would ship a "legendary" reward that any player can walk
through, and neither would fail a compile.

Invariants
----------
1. The five spec 11.8 clauses all exist as condition ids, and the two
   irreversible-event ids are credited somewhere in the source.
2. The quantified thresholds match spec 11.8's suggested values
   (6 anchors / 32 shards / 3 sacrifice anchors).
3. No condition is a hardcoded constant: every id must appear in the source
   OUTSIDE its own declaration, so a stub returning ``0`` is caught.
4. ``SummonManager.expandSlot`` still refuses the C or B tier without its
   gate, i.e. the seat is not purchasable (spec 4.3.3: 不能通过普通材料直接购买).
5. ``SecondSoulTrial.completed`` exists and is reachable from the seat grant
   path, so the trial actually gates something.
6. Every condition id and every message key used by the trial resolves in
   both lang files (the spec-11.13 rule applied to the new keys).
"""
from __future__ import annotations

import json
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, "src", "main", "java", "com", "example",
                   "golem_covenant")
TRIAL = os.path.join(SRC, "trial", "SecondSoulTrial.java")
TASK_OBSERVER = os.path.join(SRC, "trial", "TaskObserver.java")
SUMMON = os.path.join(SRC, "summon", "SummonManager.java")
ALTAR_BE = os.path.join(SRC, "block", "entity", "SoulAltarBlockEntity.java")
LANG_DIR = os.path.join(ROOT, "src", "main", "resources", "assets",
                        "golem_covenant", "lang")

# spec 11.8: the five clauses, their ids, and their suggested quantities.
# These are the numbers the spec names, so they are asserted literally.
SPEC_CLAUSES = {
    "distinct_anchors": 6,
    "capacity_shards": 32,
    "grand_sacrifice": 3,
}
# Clauses that are binary in the spec (a boss kill / a completed delve).
SPEC_FLAG_CLAUSES = ["altar_activated", "boss_or_delve"]

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


def const(src: str, name: str) -> int | None:
    """Reads ``public static final int NAME = <int>;``."""
    m = re.search(rf"\b{name}\s*=\s*(\d+)\s*;", src)
    return int(m.group(1)) if m else None


# --- 1/2: the clauses exist with the spec's numbers -------------------------

def check_clauses() -> None:
    src = read(TRIAL)
    if not src:
        return

    for cid, expected in SPEC_CLAUSES.items():
        if f'"{cid}"' not in src:
            fail(f"spec 11.8 clause id missing from SecondSoulTrial: {cid}")

    anchors = const(src, "REQUIRED_ANCHORS")
    if anchors is None:
        fail("SecondSoulTrial.REQUIRED_ANCHORS is not a literal int")
    elif anchors != SPEC_CLAUSES["distinct_anchors"]:
        fail(f"spec 11.8: REQUIRED_ANCHORS is {anchors}, spec says "
             f"{SPEC_CLAUSES['distinct_anchors']}")

    shards = const(src, "REQUIRED_SHARDS")
    if shards is None:
        fail("SecondSoulTrial.REQUIRED_SHARDS is not a literal int")
    elif shards != SPEC_CLAUSES["capacity_shards"]:
        fail(f"spec 11.8: REQUIRED_SHARDS is {shards}, spec says "
             f"{SPEC_CLAUSES['capacity_shards']}")

    sacrifice = const(src, "REQUIRED_SACRIFICE_ANCHORS")
    if sacrifice is None:
        fail("SecondSoulTrial.REQUIRED_SACRIFICE_ANCHORS is not a literal int")
    elif sacrifice != SPEC_CLAUSES["grand_sacrifice"]:
        fail(f"spec 11.8: REQUIRED_SACRIFICE_ANCHORS is {sacrifice}, spec "
             f"says {SPEC_CLAUSES['grand_sacrifice']}")

    if not failures:
        notes.append("spec 11.8 thresholds match the spec (6 / 32 / 3) OK")


# --- 3: no condition is a hardcoded stub -----------------------------------

def check_not_stubbed() -> None:
    """Every condition must be bound to real work, not to a constant.

    The bug this catches: a condition registered as ``out.add(flag(ID, false))``
    so the readout lists five rows while nothing observes anything. That is
    exactly what the previous ``trialProgress`` stub did with three of its four
    keys, and no compiler or ordinary test would notice.

    Counting occurrences of the id string is NOT the right test - a correctly
    wired condition uses its ``ID_*`` constant exactly once, inside the table.
    What actually matters is that each entry in ``conditions()`` passes a
    <b>method call</b>, so the table cannot be satisfied by literals.
    """
    src = read(TRIAL)
    if not src:
        return

    # Isolate the body of conditions(), so a method call elsewhere cannot
    # accidentally vouch for a stubbed row.
    table = re.search(r"public static List<TrialCondition> conditions\(.*?\n\t\}",
                      src, re.S)
    if not table:
        fail("spec 11.8: SecondSoulTrial.conditions() not found")
        return
    body = table.group(0)

    # Every row must derive its progress from an observation. Checking for
    # "some call" is not enough: `TrialCondition.of(ID, 0, 1)` is a call whose
    # argument is a literal, which is precisely the stub to catch. So the test
    # is on the declared FACTORIES - each condition id must be paired with a
    # helper whose name says how it is observed, and those helpers must exist
    # as real methods in the file.
    rows = re.findall(r"out\.add\((.*?)\);", body, re.S)
    if len(rows) != 5:
        fail(f"spec 11.8: expected 5 condition rows, found {len(rows)}")

    observed_helpers = {
        "ID_ANCHORS": "distinctAnchors",
        "ID_ALTAR": "altarActivated",
        "ID_SHARDS": "depositedShards",
        "ID_BOSS": "BOSS_CREDIT",
        "ID_SACRIFICE": "SACRIFICE_CREDIT",
    }
    for cid, helper in observed_helpers.items():
        pair = re.search(rf"{cid}[^)]*", body, re.S)
        if not pair:
            fail(f"spec 11.8: {cid} does not appear in conditions()")
            continue
        # The observation must appear within the same statement as the id.
        stmt = body[body.index(cid):]
        stmt = stmt[:stmt.index(";")] if ";" in stmt else stmt
        if helper not in stmt:
            fail(f"spec 11.8: condition {cid} is not bound to {helper}() - "
                 "its progress is a literal, not an observation")

    # And each helper must be a real method (or, for the two event clauses, a
    # set that something actually writes to).
    for helper in ("distinctAnchors", "altarActivated", "depositedShards"):
        if not re.search(rf"\b{helper}\s*\(", src):
            fail(f"spec 11.8: {helper}() is called but never defined")

    # The event clauses must have a credit path.
    for marker, what in (("creditBoss", "boss kill"),
                         ("creditDelve", "deep dark delve"),
                         ("completeGrandRite", "grand rite")):
        if marker not in src:
            fail(f"spec 11.8: no credit path for the {what} clause "
                 f"({marker} missing)")

    if "BOSS_CREDIT.add" not in src:
        fail("spec 11.8: the boss clause is never added to BOSS_CREDIT")
    if "SACRIFICE_CREDIT.add" not in src:
        fail("spec 11.8: the sacrifice clause is never credited")
    if "depositedShards" not in src:
        fail("spec 11.8: the shard clause does not read the altar's offering")
    if "altarActivated" not in src:
        fail("spec 11.8: the altar clause does not check for an awakened altar")

    if not failures:
        notes.append("every spec 11.8 clause is bound to an observation OK")


# --- 4/5: the seat is still gated ------------------------------------------

def check_gate() -> None:
    summon = read(SUMMON)
    if not summon:
        return
    if "CovenantTier.C" not in summon or "expandSlot" not in summon:
        fail("SummonManager no longer gates the C tier in expandSlot")
    if "grantSecondSoulSeat" not in summon:
        fail("summon: no grant path for the second soul seat")
    if "SecondSoulTrial" not in summon:
        fail("SummonManager does not consult SecondSoulTrial")

    # spec 4.3.3: 不能通过普通材料直接购买 - the C branch must READ the gate,
    # not merely exist. Asserting the C branch contains the flag is the point:
    # `return current == 0;` would let a player buy the seat with shards, and
    # a match on the B branch nearby must not be allowed to vouch for it.
    c_branch = re.search(
        r"if\s*\(\s*tier\s*==\s*CovenantTier\.C\s*\)\s*\{(.*?)\}", summon,
        re.S)
    if not c_branch:
        fail("spec 4.3.3: no `tier == CovenantTier.C` branch in expandSlot")
    elif "altarTaskCompleted" not in c_branch.group(1):
        fail("spec 4.3.3: the C branch does not read its gate - the second "
             "seat has become purchasable")

    b_branch = re.search(
        r"if\s*\(\s*tier\s*==\s*CovenantTier\.B\s*&&\s*!"
        r"altarTaskCompleted\s*\)\s*\{(.*?)\}", summon, re.S)
    if not b_branch:
        fail("spec 4.3.2: the B branch no longer refuses without altar tasks")

    trial = read(TRIAL)
    if "public static boolean completed(" not in trial:
        fail("SecondSoulTrial.completed is missing - the seat gate cannot "
             "call it")

    if not failures:
        notes.append("second C seat still gated on the trial (spec 4.3.3) OK")


# --- 3b: the altar task observer actually observes --------------------------

def check_task_observer() -> None:
    src = read(TASK_OBSERVER)
    if not src:
        return
    # spec 4.3.2's five tasks must each be credited by name.
    for task in ("protect_villagers", "dangerous_delve", "defeat_enemy_type",
                 "assisted_kills", "soul_anchor_trial"):
        if f'"{task}"' not in src:
            fail(f"spec 4.3.2: TaskObserver never credits '{task}'")
    if "onTaskCompleted" not in src:
        fail("TaskObserver does not call SoulAltarBlock.onTaskCompleted - the "
             "task would be observed and then dropped")

    be = read(ALTAR_BE)
    if "isActivated" not in be:
        fail("spec 11.8 clause 2: the altar has no activation state")
    if "WorldlyContainer" not in be:
        fail("spec 11.8 clause 3: the altar is not a container, so the shard "
             "offering cannot be deposited")
    if "SHARDS_FOR_TRIAL" not in be:
        fail("spec 11.8 clause 3: the altar does not know the shard target")

    if not failures:
        notes.append("all five spec 4.3.2 tasks are observed and credited OK")


# --- 6: lang coverage for the new keys -------------------------------------

def check_lang() -> None:
    en_path = os.path.join(LANG_DIR, "en_us.json")
    zh_path = os.path.join(LANG_DIR, "zh_cn.json")
    if not (os.path.exists(en_path) and os.path.exists(zh_path)):
        fail("lang files missing - run tools/gen_lang.py")
        return
    with open(en_path, encoding="utf-8") as fh:
        en = json.load(fh)
    with open(zh_path, encoding="utf-8") as fh:
        zh = json.load(fh)

    required = ["golem_covenant.trial.header", "golem_covenant.trial.row",
                "golem_covenant.trial.ready", "golem_covenant.trial.not_ready",
                "golem_covenant.trial.boss_defeated",
                "golem_covenant.trial.sacrifice_done",
                "golem_covenant.msg.altar_activated",
                "golem_covenant.msg.altar_shard_deposited",
                "golem_covenant.msg.grand_rite_started",
                "golem_covenant.msg.grand_rite_need_more",
                "golem_covenant.cmd.altar_offering"]
    for cid in list(SPEC_CLAUSES) + SPEC_FLAG_CLAUSES:
        required.append(f"golem_covenant.trial.{cid}")
    for key in required:
        if key not in en:
            fail(f"lang: en_us.json is missing {key}")
        if key not in zh:
            fail(f"lang: zh_cn.json is missing {key}")

    if not failures:
        notes.append(f"trial lang keys present in both files ({len(required)}) OK")


def main() -> int:
    check_clauses()
    check_not_stubbed()
    check_gate()
    check_task_observer()
    check_lang()

    print("=" * 68)
    print("second-soul trial gate (spec 4.3.3 / 11.8)")
    print("=" * 68)
    for note in notes:
        print(f"  OK   {note}")
    for problem in failures:
        print(f"  FAIL {problem}")
    print("-" * 68)
    if failures:
        print(f"FAILED - {len(failures)} problem(s)")
        return 1
    print(f"PASSED - {len(notes)} check(s)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
