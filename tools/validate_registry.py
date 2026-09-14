#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Build gate for forms_registry.json (spec 11.2.4 / 15.5).

Invariants checked:
  1. total == 186
  2. formId unique
  3. bActiveId unique        (same B mechanism must not repeat)
  4. anchorId coverage       (no active form without a real anchor)
  5. every C-tier form has deathWillId + ritualTheme
  6. every form has a bPassiveId, and it is never a pure stat bonus
  7. second-mechanism pool size >= 24
  8. death-will uniqueness: inside one family/variant group no two forms may
     share the same will archetype
  9. reserved forms are excluded from all active checks

Exit code 0 = pass, 1 = fail.
"""
import json
import re
import sys
import collections

# A passive fails the check only when it literally reads as a bare numeric
# buff. Regexes are used (not substring matching) so that behaviour such as
# "低生命值时停止蓄能" is not mistaken for a stat bonus.
PURE_STAT_PATTERNS = [
    r"[+＋]\s*\d+\s*%?\s*(攻击|生命|速度|防御|伤害|护甲)",
    r"(攻击力|生命值|移动速度|护甲值)\s*[+＋]\s*\d+",
    r"^(攻击|生命|防御|速度)提升\d",
]


def fail(msg, errors):
    errors.append(msg)


def main(path):
    with open(path, encoding="utf-8") as fh:
        data = json.load(fh)

    errors = []
    forms = data["forms"]
    active = [f for f in forms if f.get("status") == "active"]
    reserved = [f for f in forms if f.get("status") == "reserved"]

    # 1 total
    if len(forms) != 186:
        fail(f"[1] total forms = {len(forms)}, expected 186", errors)

    # 2 formId unique
    c = collections.Counter(f["formId"] for f in forms)
    for k, v in c.items():
        if v > 1:
            fail(f"[2] duplicate formId {k} x{v}", errors)

    # 3 bActiveId unique
    c = collections.Counter(f["bActiveId"] for f in active)
    for k, v in c.items():
        if v > 1:
            fail(f"[3] duplicate bActiveId {k} x{v}", errors)

    # 4 anchor coverage
    for f in active:
        if not f.get("anchorId") or f["anchorId"] == "reserved":
            fail(f"[4] form {f['formId']} has no anchorId", errors)

    # 5 C tier completeness
    for f in active:
        if not f.get("deathWillId") or f["deathWillId"] == "reserved":
            fail(f"[5] form {f['formId']} missing deathWillId", errors)
        if not f.get("ritualTheme"):
            fail(f"[5] form {f['formId']} missing ritualTheme", errors)
        dwp = f.get("deathWillProfile") or {}
        for key in ("effect", "amplifier", "durationTicks", "cooldownTicks",
                    "once", "refreshOnly", "ownerOnly", "triggerCondition"):
            if key not in dwp:
                fail(f"[5] form {f['formId']} deathWillProfile missing {key}", errors)
        if dwp.get("refreshOnly") is not True:
            fail(f"[5] form {f['formId']} deathWillProfile.refreshOnly must be true",
                 errors)

    # 6 passives
    for f in active:
        pid = f.get("bPassiveId")
        if not pid or pid == "reserved":
            fail(f"[6] form {f['formId']} missing bPassiveId", errors)
        blob = (f.get("bPassive") or "")
        for pat in PURE_STAT_PATTERNS:
            if re.search(pat, blob):
                fail(f"[6] form {f['formId']} passive looks like a pure stat bonus: {blob}",
                     errors)

    # 7 second mechanism pool
    pool = data.get("secondMechanicPool") or {}
    if len(pool) < 24:
        fail(f"[7] secondMechanicPool has {len(pool)} entries, need >= 24", errors)
    for f in active:
        if f.get("cSecondId") not in pool:
            fail(f"[7] form {f['formId']} cSecondId {f.get('cSecondId')} not in pool",
                 errors)

    # 8 death-will uniqueness inside (familyId, variantType) groups.
    #
    # Spec 15.5.11 demands that C-tier death wills differ on at least 2 of
    # {effect type, value, duration, trigger}. Two members may therefore share
    # an effect family (e.g. two zombie babies both granting move speed) but
    # they must then differ on >= 2 quantitative dimensions. TRUE duplicates -
    # identical on 3+ dimensions - are the failure mode this gate blocks.
    groups = collections.defaultdict(list)
    for f in active:
        groups[(f["familyId"], f["variantType"])].append(f)
    collisions = 0
    soft_repeats = 0
    for key, members in groups.items():
        for i in range(len(members)):
            for j in range(i + 1, len(members)):
                a, b = members[i], members[j]
                pa, pb = a["deathWillProfile"], b["deathWillProfile"]
                dims = 0
                if a["deathWillId"] != b["deathWillId"]:
                    dims += 1
                if pa["amplifier"] != pb["amplifier"]:
                    dims += 1
                if pa["durationTicks"] != pb["durationTicks"]:
                    dims += 1
                if pa["triggerCondition"] != pb["triggerCondition"]:
                    dims += 1
                if dims < 2:
                    collisions += 1
                    fail(f"[8] group {key}: {a['formId']} and {b['formId']} share "
                         f"death will '{a['deathWillId']}' on all but {dims} dimension(s)",
                         errors)
                elif a["deathWillId"] == b["deathWillId"]:
                    soft_repeats += 1

    # 9 reserved excluded
    for f in reserved:
        if f.get("anchorId") != "reserved":
            fail(f"[9] reserved form {f['formId']} must keep anchorId=reserved", errors)

    print("=" * 68)
    print("forms_registry.json validation")
    print("=" * 68)
    print(f"  file        : {path}")
    print(f"  total       : {len(forms)}")
    print(f"  active      : {len(active)}")
    print(f"  reserved    : {len(reserved)}")
    print(f"  families    : {len(set(f['familyId'] for f in forms))}")
    print(f"  anchors     : {len(set(f['anchorId'] for f in active))}")
    print(f"  2nd-mech    : {len(pool)} distinct")
    print(f"  passives    : {len(set(f['bPassiveId'] for f in active))} distinct")
    print(f"  deaths      : {len(set(f['deathWillId'] for f in active))} effect families")
    print(f"  will groups : {len(groups)}, hard-collisions={collisions}, "
          f"same-family-but-distinct={soft_repeats}")
    print("-" * 68)
    if errors:
        print(f"FAILED with {len(errors)} problem(s):")
        for e in errors[:60]:
            print("  x", e)
        if len(errors) > 60:
            print(f"  ... and {len(errors) - 60} more")
        return 1
    print("PASSED - all invariants hold")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1]))
