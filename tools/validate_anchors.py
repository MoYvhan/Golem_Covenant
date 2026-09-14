#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Build gate for the 灵魂锚点系统 (spec ch.6).

This is the gate that protects the single most important rule in the whole
specification. Spec 6.1 says an anchor is NOT a buff / attribute / weapon /
single skill - it is "这个灵魂观察世界和参与战斗的规则". Spec 6.2 then states
the iron law:

    186 个形态必须尽可能拥有不同的主锚点。
    并且锚点至少改变以下其中 三项:
      1. 观察对象   2. 资源来源   3. 触发条件
      4. 战斗方式   5. AI 决策    6. 区域规则
      7. 玩家互动   8. 死亡遗志   9. 仪式结构

Dimensions 1-7 are stored per-anchor in the registry's `anchors` block.
Dimensions 8-9 (死亡遗志 / 仪式结构) are stored per-FORM, so this gate reads
them back from the forms that use each anchor. An anchor therefore has to prove
it changes at least 3 of 9 by looking at BOTH tables.

Invariants checked
------------------
1. Coverage      - every active form's anchorId exists in the `anchors` block;
                   every declared anchor is actually used by some active form.
2. Iron law      - every PAIR of anchors inside the same family differs on >= 3
                   of the 9 dimensions (spec 6.2). Cross-family pairs are
                   exempt because spec 6.2 asks for "尽可能" and the families
                   are deliberately far apart already.
3. No stat-talk  - no anchor dimension may describe a numeric stat bonus; an
                   anchor must describe a RULE (spec 6.1, 12.6).
4. Behaviour map - every anchor's runtime behaviour keyword exists in
                   `anchorBehaviours`.
5. Death will / ritual spread - within a family, anchors must not all share the
                   same death will AND ritual, or dimensions 8/9 would be dead.
6. Localisation  - every anchor must have real English text in en_us.json
                   (spec 11.15.4); a Chinese string in the English file is a
                   shipping bug, not a translation gap.
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
EN_LANG = os.path.join(ROOT, "src", "main", "resources", "assets",
                       "golem_covenant", "lang", "en_us.json")

# Dimensions 1-7, as stored on the anchor itself.
TEXT_DIMS = ["watch", "source", "trigger", "combat", "ai", "zone", "interact"]

# Dimensions 8-9, stored per form.
FORM_DIMS = ["deathWillId", "ritualTheme"]

# Spec 6.2 requires a difference on at least this many dimensions.
MIN_DIFFERING_DIMS = 3

# An anchor must never read as a stat buff (spec 6.1 "锚点不是 Buff / 属性").
#
# The patterns below are deliberately narrow. A threshold such as
# "主人生命低于 40%" is a TRIGGER, not a buff - it is exactly the kind of rule
# spec 6.2 dimension 3 asks for - so a bare "N%" must not be flagged. Only
# phrasing that promises a gain is a violation.
STAT_TALK = [
    r"[+＋]\s*\d+",
    r"(攻击力|生命值|护甲值|移动速度|攻击速度|射程)\s*(提升|增加|提高|加成)",
    r"(提升|增加|提高|加成)\s*\d+\s*(点|%|倍)?",
    r"造成\s*\d+\s*(点)?\s*伤害",
    r"伤害\s*(提升|增加|翻倍|乘以)",
    r"(攻击|防御|生命)\s*(力|值)?\s*[×xX*]\s*\d",
]

failures: list[str] = []
notes: list[str] = []


def fail(msg: str) -> None:
    failures.append(msg)


def main() -> int:
    with open(REGISTRY, encoding="utf-8") as fh:
        reg = json.load(fh)

    anchors = reg.get("anchors") or {}
    behaviours = reg.get("anchorBehaviours") or {}
    forms = reg.get("forms") or []
    active = [f for f in forms if f.get("status") == "active"]

    if not anchors:
        fail("[1] registry has no `anchors` block (spec ch.6 not implemented)")
        return report()

    # ------------------------------------------------------------------
    # 1. coverage
    # ------------------------------------------------------------------
    # Spec 11.2.3: the reserved band (161..186) is parsed but never registered.
    # It has no real anchor and is excluded from every anchor check.
    used: dict[str, list[dict]] = collections.defaultdict(list)
    for f in active:
        aid = f.get("anchorId")
        if str(aid).startswith("reserved"):
            continue
        if aid not in anchors:
            fail(f"[1] form {f['formId']} uses undeclared anchor {aid!r}")
            continue
        used[aid].append(f)

    orphans = sorted(set(anchors) - set(used))
    if orphans:
        fail(f"[1] {len(orphans)} declared anchor(s) never used by an active "
             f"form: {orphans[:6]}{' ...' if len(orphans) > 6 else ''}")
    else:
        notes.append(f"all {len(anchors)} anchors are used by active forms OK")

    # ------------------------------------------------------------------
    # 2. the iron law: every intra-family anchor pair differs on >= 3 of 9
    # ------------------------------------------------------------------
    by_family: dict[str, list[str]] = collections.defaultdict(list)
    for aid in anchors:
        by_family[anchors[aid].get("familyId", "")].append(aid)

    pairs_checked = 0
    worst_pair = None
    worst_diff = 99

    for fam, aids in sorted(by_family.items()):
        aids = sorted(aids)
        for i in range(len(aids)):
            for j in range(i + 1, len(aids)):
                a, b = aids[i], aids[j]
                da, db = anchors[a], anchors[b]
                diffs = []
                for dim in TEXT_DIMS:
                    if da.get(dim, "") != db.get(dim, ""):
                        diffs.append(dim)
                # dimensions 8 & 9 come from the forms, and only count when
                # the two anchors actually have distinct form-level values.
                fa = used.get(a, [])
                fb = used.get(b, [])
                if fa and fb:
                    wills_a = {f.get("deathWillId") for f in fa}
                    wills_b = {f.get("deathWillId") for f in fb}
                    rituals_a = {f.get("ritualTheme") for f in fa}
                    rituals_b = {f.get("ritualTheme") for f in fb}
                    # a dimension counts as "changed" when the two anchors do
                    # not draw from an identical value set
                    if wills_a != wills_b or not (wills_a & wills_b):
                        diffs.append("deathWillId")
                    if rituals_a != rituals_b:
                        diffs.append("ritualTheme")

                pairs_checked += 1
                if len(diffs) < worst_diff:
                    worst_diff = len(diffs)
                    worst_pair = (a, b, diffs)
                if len(diffs) < MIN_DIFFERING_DIMS:
                    fail(f"[2] spec 6.2 iron law: anchors {a!r} and {b!r} "
                         f"(family {fam}) differ on only {len(diffs)} "
                         f"dimension(s): {diffs}")

    if worst_pair and worst_diff >= MIN_DIFFERING_DIMS:
        notes.append(f"iron law holds: {pairs_checked} intra-family anchor "
                     f"pairs, worst case = {worst_diff}/9 dims "
                     f"({worst_pair[0]} vs {worst_pair[1]}) OK")
    elif worst_pair:
        notes.append(f"iron law WORST: {worst_pair[0]} vs {worst_pair[1]} "
                     f"-> {worst_diff} dims {worst_pair[2]}")

    # ------------------------------------------------------------------
    # 3. an anchor must describe a rule, never a stat
    # ------------------------------------------------------------------
    offenders = []
    for aid, d in anchors.items():
        for dim in TEXT_DIMS:
            text = d.get(dim, "")
            for pat in STAT_TALK:
                if re.search(pat, text):
                    offenders.append((aid, dim, text))
                    break
    if offenders:
        for aid, dim, text in offenders[:10]:
            fail(f"[3] spec 6.1: anchor {aid!r} dimension {dim!r} reads as a "
                 f"stat bonus, not a rule: {text!r}")
        if len(offenders) > 10:
            fail(f"[3] ... and {len(offenders) - 10} more stat-like anchors")
    else:
        notes.append("no anchor describes a raw stat bonus (spec 6.1) OK")

    # ------------------------------------------------------------------
    # 4. behaviour keyword resolution
    # ------------------------------------------------------------------
    bad_behaviour = []
    for aid, d in anchors.items():
        bh = d.get("behaviour")
        if bh not in behaviours:
            bad_behaviour.append((aid, bh))
    if bad_behaviour:
        for aid, bh in bad_behaviour[:10]:
            fail(f"[4] anchor {aid!r} declares unknown behaviour {bh!r} "
                 f"(not in anchorBehaviours)")
    else:
        notes.append(f"all {len(anchors)} anchors resolve to a runtime "
                     f"behaviour ({len(behaviours)} in vocabulary) OK")

    # every form's anchorBehaviour field must match its anchor's definition
    mismatch = 0
    for f in active:
        aid = f.get("anchorId")
        if aid in anchors and f.get("anchorBehaviour") != anchors[aid].get("behaviour"):
            mismatch += 1
            if mismatch <= 5:
                fail(f"[4] form {f['formId']} anchorBehaviour "
                     f"{f.get('anchorBehaviour')!r} != anchor {aid} behaviour "
                     f"{anchors[aid].get('behaviour')!r}")
    if not mismatch:
        notes.append("form.anchorBehaviour agrees with the anchor table OK")

    # ------------------------------------------------------------------
    # 5. dimensions 8 & 9 must actually vary inside a family
    # ------------------------------------------------------------------
    degenerate = []
    for fam, aids in sorted(by_family.items()):
        if len(aids) < 2:
            continue
        fam_forms = [f for aid in aids for f in used.get(aid, [])]
        if not fam_forms:
            continue
        wills = collections.Counter(f.get("deathWillId") for f in fam_forms)
        rituals = collections.Counter(f.get("ritualTheme") for f in fam_forms)
        if len(wills) < 2:
            degenerate.append(f"{fam}: every form shares deathWillId "
                              f"{next(iter(wills))!r}")
        if len(rituals) < 2:
            degenerate.append(f"{fam}: every form shares ritualTheme "
                              f"{next(iter(rituals))!r}")
    if degenerate:
        for d in degenerate:
            fail(f"[5] spec 6.2 dims 8/9 degenerate - {d}")
    else:
        notes.append("death-will and ritual dimensions vary in every "
                     "multi-anchor family OK")

    # ------------------------------------------------------------------
    # 6. localisation (spec 11.15.4)
    # ------------------------------------------------------------------
    untranslated = check_localisation(anchors)
    if untranslated:
        for k in untranslated[:10]:
            fail(f"[6] spec 11.15.4: {k} has no English text in en_us.json")
        if len(untranslated) > 10:
            fail(f"[6] ... and {len(untranslated) - 10} more untranslated keys")
    else:
        notes.append(f"all {len(anchors)} anchors have English text "
                     f"(spec 11.15.4) OK")

    # ------------------------------------------------------------------
    # summary stats
    # ------------------------------------------------------------------
    print("=" * 68)
    print("anchor system validation (spec ch.6)")
    print("=" * 68)
    print(f"  registry      : {os.path.relpath(REGISTRY, ROOT)}")
    print(f"  anchors       : {len(anchors)} declared, {len(used)} used")
    print(f"  families      : {len(by_family)}")
    print(f"  behaviours    : {len(behaviours)} in vocabulary, "
          f"{len({a.get('behaviour') for a in anchors.values()})} used")
    print(f"  active forms  : {len(active)}")
    bh_use = collections.Counter(f.get("anchorBehaviour") for f in active)
    top = ", ".join(f"{k}={v}" for k, v in bh_use.most_common(5))
    print(f"  busiest       : {top}")
    print("-" * 68)
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


def check_localisation(anchors: dict) -> list[str]:
    """Every anchor name and its seven dimensions need English text.

    A CJK string in en_us.json is treated as missing: spec 11.15.4 requires the
    English file to be genuinely English, so a copy of the Chinese source is a
    shipping bug rather than an acceptable fallback.
    """
    if not os.path.exists(EN_LANG):
        fail(f"[6] missing {os.path.relpath(EN_LANG, ROOT)} - run generateLang")
        return []
    with open(EN_LANG, encoding="utf-8") as fh:
        en = json.load(fh)
    dims = ["watch", "source", "trigger", "combat", "ai", "zone", "interact"]
    missing: list[str] = []
    for aid in anchors:
        keys = [f"golem_covenant.anchor.{aid}"]
        keys += [f"golem_covenant.anchor.{aid}.{d}" for d in dims]
        for k in keys:
            v = en.get(k)
            if not v or _has_cjk(v):
                missing.append(k)
    return missing


def _has_cjk(text: str) -> bool:
    return any("\u4e00" <= ch <= "\u9fff" for ch in text)


def report() -> int:
    for n in notes:
        print(f"  [ok]   {n}")
    for f in failures:
        print(f"  [FAIL] {f}")
    print(f"FAILED - {len(failures)} violation(s)")
    return 1


if __name__ == "__main__":
    sys.exit(main())
