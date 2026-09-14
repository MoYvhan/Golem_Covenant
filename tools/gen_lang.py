#!/usr/bin/env python3
"""Generates the addon's lang files from the authoritative registry.

Run after `gen_registry.py`. Also wired into the gradle `check` task so the
lang files can never drift from `forms_registry.json` (spec 11.2.1 "单一权威
来源": the registry is the source of truth, lang is derived).

Writes:
  src/main/resources/assets/golem_covenant/lang/en_us.json
  src/main/resources/assets/golem_covenant/lang/zh_cn.json

The static (hand-authored) key blocks live at the top of this file so the
generated per-form keys can be appended without a separate template.
"""
from __future__ import annotations

import json
import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
REGISTRY = os.path.join(ROOT, "src", "main", "resources", "data",
                        "golem_covenant", "forms_registry.json")
LANG_DIR = os.path.join(ROOT, "src", "main", "resources", "assets",
                        "golem_covenant", "lang")

# --- static blocks -----------------------------------------------------

ITEMS = {
    "golden_covenant": ("Golden Covenant", "黄金契约"),
    "soul_fruit": ("Golem Soul Fruit", "傀儡灵魂果"),
    "soul_covenant": ("Soul Covenant", "灵魂圣契"),
    "capacity_shard": ("Covenant Capacity Shard", "契约容量碎片"),
    "altar_core": ("Soul Altar Core", "灵魂圣坛核心"),
}

ITEM_TOOLTIP = {
    "tooltip.golem_covenant.tier_a": (
        "Borrowing a soul - 10 minutes only.",
        "借魂 - 仅存续 10 分钟。"),
    "tooltip.golem_covenant.tier_b": (
        "Awakened soul - permanent companion.",
        "灵魂觉醒 - 永久伙伴。"),
    "tooltip.golem_covenant.tier_c": (
        "Full covenant - carries a death will.",
        "灵魂圣契 - 携带死亡遗志。"),
    "tooltip.golem_covenant.requires_golemized": (
        "Use on a copperized creature.",
        "对已傀儡化的生物使用。"),
    "tooltip.golem_covenant.shift_for_details": (
        "Hold Shift for the full covenant.",
        "按住 Shift 查看完整契约。"),
}

MESSAGES = {
    "msg.base_mod_missing": (
        "The Golemization mod is not installed - covenant items are inert.",
        "未安装傀儡化模组 - 契约物品无法生效。"),
    "msg.not_golemized": (
        "That creature is not copperized yet.",
        "该生物尚未傀儡化。"),
    "msg.already_bound": (
        "That companion is already bound to someone.",
        "该伙伴已被他人契约。"),
    "msg.not_owner": (
        "That companion belongs to another player.",
        "该伙伴属于其他玩家。"),
    "msg.no_slots": (
        "No free covenant slots. Expand capacity with shards.",
        "契约位不足，请使用碎片扩充容量。"),
    "msg.soul_overload": (
        "Soul capacity exceeded for this tier.",
        "该等级的灵魂容量已超出。"),
    "msg.form_unsupported": (
        "That form is not available for covenant.",
        "该形态暂不可契约。"),
    "msg.bound": (
        "Covenant formed with %s.",
        "已与 %s 缔结契约。"),
    "msg.released": (
        "Covenant released. The soul returns to rest.",
        "契约已解除，灵魂重归沉寂。"),
    "msg.revoked_by_owner": (
        "Your covenant was revoked by its owner.",
        "你的契约已被主人解除。"),
    "msg.slot_expanded": (
        "Covenant capacity expanded.",
        "契约容量已扩充。"),
    "msg.already_has_tier": (
        "This companion already holds that covenant tier.",
        "该伙伴已拥有此等级契约。"),
    "msg.tier_upgraded": (
        "Covenant deepened: %s.",
        "契约加深：%s。"),
    "msg.unknown_form": (
        "No form registered for that creature.",
        "未找到该生物对应的形态。"),
    "msg.covenant_list_header": (
        "Your covenants (%s/%s slots):",
        "你的契约（%s/%s 位）："),
    "msg.covenant_list_empty": (
        "You hold no covenants.",
        "你尚未缔结任何契约。"),
    "msg.bond_stage": (
        "Bond with %s: %s (%s)",
        "与 %s 的契合度：%s（%s）"),
}

DEATH_WILL_MSG = {
    "death_will.armed": (
        "%s is armed. It will answer the next time you are struck.",
        "%s已就绪，将在你下次受击时回应。"),
    "death_will.triggered": (
        "%s has answered.",
        "%s已回应。"),
    "death_will.applied": (
        "%s - it helped you one last time.",
        "%s - 它最后帮了你一次。"),
    "death_will.pending": (
        "%s is waiting for your next fight.",
        "%s在等你下一场战斗。"),
    "death_will.expired": (
        "%s faded away.",
        "%s已消散。"),
}

KEYS = {
    "key.categories.golem_covenant": ("Golem Covenant", "傀儡契约"),
    "key.golem_covenant.ability": ("Trigger companion ability", "触发伙伴能力"),
    "key.golem_covenant.panel": ("Open covenant panel", "打开契约面板"),
}

HUD = {
    "hud.bond": ("Bond", "契合"),
    "hud.slots": ("Slots", "契约位"),
    "hud.will_armed": ("Will armed", "遗志就绪"),
}

COMMANDS = {
    "command.golem.covenant.list": ("List your covenants", "列出你的契约"),
    "command.golem.covenant.info": ("Inspect a companion", "查看伙伴详情"),
    "command.golem.covenant.release": ("Release a covenant", "解除契约"),
    "command.golem.covenant.survey": ("Show the compat survey", "显示兼容性勘察"),
    "command.golem.covenant.none": ("No covenants found.", "未找到契约。"),
    "command.golem.covenant.listed": ("Covenant list sent.", "契约列表已发送。"),
}

# effectId -> (en, zh). Effect display names come from the will theme table.
EFFECT_NAMES = {
    "resistance": ("Damage Reduction", "伤害减免"),
    "speed": ("Movement Speed", "移速"),
    "knockback_resist": ("Knockback Resistance", "抗击退"),
    "regeneration": ("Regeneration Pulse", "生命恢复"),
    "fire_resistance": ("Fire Resistance", "火焰抗性"),
    "cold_resist": ("Cold Resistance", "寒冷抗性"),
    "slow_resist": ("Slow Resistance", "减速抗性"),
    "water_breathing": ("Water Breathing", "水下呼吸"),
    "water_power": ("Water Power", "水下能力"),
    "night_vision": ("Night Vision", "夜视"),
    "strength": ("Attack Boost", "攻击强化"),
    "ranged_boost": ("Ranged Boost", "远程强化"),
    "poison_resist": ("Poison Resistance", "毒素抗性"),
    "glowing_enemies": ("Enemy Detection", "敌人侦测"),
    "jump_boost": ("Jump Boost", "跳跃强化"),
    "haste": ("Agility", "灵活"),
    "mining_boost": ("Gathering Aid", "采集辅助"),
    "absorb": ("Burst Shield", "爆发防护"),
    "exp_boost": ("Experience Aid", "经验辅助"),
    "negative_resist": ("Debuff Resistance", "负面抗性"),
    "melee_boost": ("Melee Boost", "近战强化"),
    "tool_boost": ("Tool Efficiency", "工具效率"),
    "lightning_resist": ("Lightning Resistance", "雷抗"),
    "tracking": ("Tracking", "追踪"),
    "random_boon": ("Random Boon", "随机增益"),
    "blink_shield": ("Emergency Blink", "紧急位移保护"),
    "last_shot": ("Final Pursuit", "追击"),
    "space_door": ("Space Door", "空间门"),
    "echo_ping": ("Echo Bell", "回声钟"),
    "hive_shard": ("Hive Shard", "蜂巢残片"),
    "web_burst": ("Last Weave", "最后织命网"),
}

BOND_STAGES = {
    "first_pact": ("First Pact", "初契"),
    "acknowledged": ("Acknowledged", "认主"),
    "resonance": ("Resonance", "共鸣"),
    "deep_pact": ("Deep Pact", "深契"),
    "full_covenant": ("Full Covenant", "完全契约"),
}

TIER_NAMES = {
    "a": ("Borrow", "借魂"),
    "b": ("Awaken", "灵魂觉醒"),
    "c": ("Covenant", "灵魂圣契"),
}

FAMILY_NAMES = {
    "zombie": ("Undead", "亡灵"),
    "arthropod": ("Arthropod", "节肢"),
    "animal": ("Beast", "走兽"),
    "mount": ("Mount", "坐骑"),
    "snow": ("Frost", "霜寒"),
    "environment": ("Wild", "自然"),
    "aquatic": ("Aquatic", "水生"),
    "construct": ("Construct", "构装"),
    "nether_end": ("Nether & End", "下界与末地"),
    "special": ("Special", "特异"),
    "reserved": ("Reserved", "未开放"),
}


def merge(target: dict, block: dict, en: bool) -> None:
    for k, v in block.items():
        target[k] = v[0] if en else v[1]


def build(en: bool) -> dict:
    out: dict[str, str] = {}
    # items
    for k, v in ITEMS.items():
        out[f"item.golem_covenant.{k}"] = v[0] if en else v[1]
    merge(out, ITEM_TOOLTIP, en)
    for k, v in MESSAGES.items():
        out[f"golem_covenant.{k}"] = v[0] if en else v[1]
    for k, v in DEATH_WILL_MSG.items():
        out[f"golem_covenant.{k}"] = v[0] if en else v[1]
    merge(out, KEYS, en)
    merge(out, HUD, en)
    merge(out, COMMANDS, en)

    # will display names
    for eid, v in EFFECT_NAMES.items():
        out[f"golem_covenant.will.{eid}"] = v[0] if en else v[1]
    # bond stages
    for bid, v in BOND_STAGES.items():
        out[f"golem_covenant.bond.{bid}"] = v[0] if en else v[1]
    # tiers
    for tid, v in TIER_NAMES.items():
        out[f"golem_covenant.tier.{tid}"] = v[0] if en else v[1]
    # families
    for fid, v in FAMILY_NAMES.items():
        out[f"golem_covenant.family.{fid}"] = v[0] if en else v[1]

    # second mechanics (derived from the registry so it stays complete)
    with open(REGISTRY, encoding="utf-8") as fh:
        reg = json.load(fh)
    for f in reg["forms"]:
        human = _humanize(f.get("cSecondId") or "")
        if human:
            out.setdefault(f"golem_covenant.second.{f['cSecondId']}", human)
    return out


def _humanize(sid: str) -> str:
    if not sid or sid == "reserved":
        return ""
    return sid.replace("_", " ").title()


def main() -> int:
    with open(REGISTRY, encoding="utf-8") as fh:
        reg = json.load(fh)

    os.makedirs(LANG_DIR, exist_ok=True)
    counts = {}
    for en, name in ((True, "en_us.json"), (False, "zh_cn.json")):
        data = build(en)
        # per-form display names, keyed by formId (spec 11.2.1 derived names)
        for f in reg["forms"]:
            key = f"golem_covenant.form.{f['formId']}"
            label = f.get("nameEn") if en else f.get("displayName")
            data[key] = label or f["formId"]
        path = os.path.join(LANG_DIR, name)
        with open(path, "w", encoding="utf-8") as fh:
            json.dump(data, fh, ensure_ascii=False, indent=2, sort_keys=True)
            fh.write("\n")
        counts[name] = len(data)

    print("lang generation")
    print("=" * 68)
    print(f"  registry forms : {reg['total']}")
    for n, c in counts.items():
        print(f"  {n:<14} : {c} keys")
    print("=" * 68)
    print("PASSED")
    return 0


if __name__ == "__main__":
    sys.exit(main())
