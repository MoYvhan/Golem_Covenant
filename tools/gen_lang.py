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
import re
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

# spec 4.3.1 / 4.3.2: the two expansion blocks. Blocks need their own
# `block.golem_covenant.*` namespace - the game does not fall back to the
# item key, so without these the block item shows its raw id in the inventory.
BLOCKS = {
    "covenant_stele": ("Covenant Stele", "契约石碑"),
    "soul_altar": ("Soul Altar", "灵魂圣坛"),
}

# Item tooltips (spec 11.13 namespace: golem_covenant.tooltip.*).
#
# These used to live under `tooltip.golem_covenant.*` while CovenantItem.java
# asked for `golem_covenant.tooltip.*`, so every covenant item showed raw keys.
# validate_lang.py now makes that class of mismatch a build failure.
ITEM_TOOLTIP = {
    "golem_covenant.tooltip.tier_a": (
        "Borrowing a soul - 10 minutes only.",
        "借魂 - 仅存续 10 分钟。"),
    "golem_covenant.tooltip.tier_b": (
        "Awakened soul - permanent companion.",
        "灵魂觉醒 - 永久伙伴。"),
    "golem_covenant.tooltip.tier_c": (
        "Full covenant - carries a death will.",
        "灵魂圣契 - 携带死亡遗志。"),
    # spec 11.9.3: the tooltip must state the soul load and the slot range,
    # so a player can plan a team from the item alone.
    "golem_covenant.tooltip.soul_load": (
        "Soul load: %s", "占用灵魂：%s"),
    "golem_covenant.tooltip.slots": (
        "Slots: %s (up to %s)", "契约位：%s（上限 %s）"),
    "golem_covenant.tooltip.has_will": (
        "Carries a death will.", "携带有死亡遗志。"),
    "golem_covenant.tooltip.requires_golemized": (
        "Use on a copperized creature.",
        "对已傀儡化的生物使用。"),
    # 26.2 gives the tooltip no key state (TooltipFlag carries only advanced /
    # creative), so the expand trigger is the F3+H detail toggle rather than
    # the older "hold Shift" convention. The key must match the code or the
    # hint sends players looking for a key that does nothing.
    "golem_covenant.tooltip.shift_for_details": (
        "F3+H shows the full covenant.",
        "按 F3+H 查看完整契约。"),
    # spec 11.13: the attuned form's ritual circle and its four abilities.
    "golem_covenant.tooltip.ritual": (
        "Ritual: %s", "法阵：%s"),
    "golem_covenant.tooltip.ability": (
        "%s: %s", "%s：%s"),
    "golem_covenant.tooltip.ability.b_active": (
        "Active", "主动"),
    "golem_covenant.tooltip.ability.b_passive": (
        "Passive", "被动"),
    "golem_covenant.tooltip.ability.c_second": (
        "Second Mechanism", "第二机制"),
    "golem_covenant.tooltip.ability.death_will": (
        "Death Will", "死亡遗志"),
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
    # spec 4.3.1 / 4.3.2 block interactions. Kept as messages rather than a
    # GUI because a slot grant is a one-line outcome, and a screen for it would
    # be more ceremony than the feature needs.
    "msg.stele_rite_started": (
        "The stele's rite has begun. Hold the circle.",
        "石碑仪式已启动，请守住法阵。"),
    "msg.stele_rite_running": (
        "This stele is already running a rite.",
        "该石碑的仪式尚未结束。"),
    "msg.stele_at_max": (
        "A-tier slots are already at the cap (%s).",
        "A 级契约位已达上限（%s）。"),
    "msg.stele_rite_done": (
        "The rite completes: +1 A-tier slot.",
        "仪式完成：A 级契约位 +1。"),
    "msg.altar_no_materials": (
        "Seats come from partner tasks, not from offered materials.",
        "灵魂席位来自伙伴任务，而非献上的材料。"),
    "msg.altar_seat_granted": (
        "A soul seat opens: B-tier slots now %s.",
        "灵魂席位开启：B 级契约位现为 %s。"),
    # spec 11.8 clause 2/3: the altar's offering bowl. Deposits are reported
    # on every click because the shard count is the trial's only slow-moving
    # counter, and a silent deposit reads as a lost item.
    "msg.altar_shard_deposited": (
        "Offering accepted: %s / %s capacity shards.",
        "祭品已接受：契约容量碎片 %s / %s。"),
    "msg.altar_offering_full": (
        "The offering is already complete (%s shards).",
        "祭品已集齐（%s 个碎片）。"),
    "msg.altar_activated": (
        "The Soul Altar awakens. The second soul rite may begin.",
        "灵魂圣坛已苏醒，第二灵魂仪式可以开始了。"),
    # spec 11.8 clause 5: the grand rite. Each outcome is a separate key
    # because the three failure modes need different player actions.
    "msg.grand_rite_started": (
        "The grand rite begins: %s companions of different anchors are offered.",
        "大型仪式开始：%s 种不同锚点的伙伴正被献上。"),
    "msg.grand_rite_need_more": (
        "The rite needs %s nearby B-tier companions of different anchors.",
        "仪式需要 %s 个不同锚点、且位于附近的 B 级伙伴。"),
    "msg.grand_rite_running": (
        "A grand rite is already running here.",
        "此处的大型仪式尚未结束。"),
    "msg.grand_rite_done": (
        "The grand rite has already been completed.",
        "大型仪式已经完成过了。"),
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

# spec ch.9 Bond growth + ch.10 anchor resonance
BOND_MSG = {
    "bond.advanced": (
        "%s deepened its pact: %s (%s)",
        "%s 的契约加深：%s（%s）"),
}

RESONANCE = {
    "resonance.formed": (
        "Anchor resonance formed: %s",
        "锚点共鸣形成：%s"),
    "resonance.broken": (
        "Anchor resonance broke: %s",
        "锚点共鸣中断：%s"),
    "resonance.close_far": (
        "Close-Far Coordination",
        "远近协同"),
    "resonance.space_web": (
        "Space Web",
        "空间蛛网"),
    "resonance.echo_snipe": (
        "Echo Snipe",
        "声纹狙击"),
    "resonance.generic": (
        "Shared Mark",
        "协同标记"),
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

# The /golem command tree (spec 11.9.3 / 11.10.4 / 13.1).
#
# Keys are `cmd.*` to match GolemCommand.java. The earlier `command.golem.*`
# block described a command tree that was never registered, so it was dead
# text; it is replaced here by the keys the command actually sends.
COMMANDS = {
    # --- shared / failure ------------------------------------------------
    "cmd.players_only": ("Only a player can use this command.",
                         "只有玩家可以使用此命令。"),
    "cmd.bad_value": ("'%s' is not valid here; expected %s",
                      "「%s」不是有效取值；应为 %s"),
    "cmd.set_ok": ("%s -> %s", "%s -> %s"),
    # --- headers ---------------------------------------------------------
    "cmd.header": ("=== Golem Covenant ===", "=== 傀儡契约 ==="),
    "cmd.capacity_header": ("=== Soul capacity ===", "=== 灵魂容量 ==="),
    "cmd.anchor_header": ("=== Companion anchors ===", "=== 伙伴灵魂锚点 ==="),
    "cmd.anchor_list_header": ("=== Anchor codex ===", "=== 锚点图鉴 ==="),
    "cmd.bond_header": ("=== Bond ===", "=== 契合度 ==="),
    "cmd.resonance_header": ("=== Anchor resonance ===", "=== 锚点共鸣 ==="),
    "cmd.settings_header": ("=== Ritual settings ===", "=== 法阵设置 ==="),
    # --- covenant / capacity --------------------------------------------
    "cmd.slots": ("  %s slots: %s", "  %s 契约位：%s"),
    "cmd.soul_pool": ("  Soul capacity: %s", "  灵魂容量：%s"),
    "cmd.capacity_row": ("  %s [%s] - %s soul",
                         "  %s [%s] - 占用 %s"),
    "cmd.no_companions": ("  (no companions)", "  （暂无伙伴）"),
    # --- anchors ---------------------------------------------------------
    "cmd.anchor_row": ("  %s -> %s (depth %s)",
                       "  %s -> %s（深度 %s）"),
    "cmd.anchor_family": ("  %s: %s anchors", "  %s：%s 个锚点"),
    "cmd.anchor_total": ("  total %s anchors, %s tracked companions",
                         "  共 %s 个锚点，正在追踪 %s 个伙伴"),
    "cmd.anchor_list_row": ("  %s - %s", "  %s - %s"),
    "cmd.unknown_family": ("Unknown family '%s'.",
                           "未知族群「%s」。"),
    # --- bond / resonance -----------------------------------------------
    "cmd.bond_row": ("  %s bond %s, depth %s",
                     "  %s 契合 %s，深度 %s"),
    "cmd.resonance_none": ("  (no resonance active)", "  （无共鸣生效）"),
    "cmd.resonance_row": ("  %s", "  %s"),
    # --- soul altar task board (spec 4.3.2) ------------------------------
    "cmd.altar_header": ("=== Soul altar ===", "=== 灵魂圣坛 ==="),
    "cmd.altar_row": ("  Tasks complete: %s/%s (B slots %s/%s)",
                      "  已完成任务：%s/%s（B 级契约位 %s/%s）"),
    "cmd.altar_next": ("  Next task: %s", "  下一项任务：%s"),
    "cmd.altar_offering": ("  Offering: %s/%s shards (%s)",
                           "  祭品：%s/%s 碎片（%s）"),
    "cmd.altar_state_active": ("awakened", "已苏醒"),
    "cmd.altar_state_dormant": ("dormant", "沉睡"),
    "cmd.task.protect_villagers": ("Protect villagers", "保护村民"),
    "cmd.task.dangerous_delve": ("Complete a dangerous delve", "完成一次危险探索"),
    "cmd.task.defeat_enemy_type": ("Defeat a specific enemy type", "击败特定类型敌人"),
    "cmd.task.assisted_kills": ("Accumulate assisted kills", "累计协助战斗"),
    "cmd.task.soul_anchor_trial": ("Clear a soul-anchor trial", "完成一次灵魂锚点试炼"),
    # --- 第二灵魂 trial (spec 4.3.3 / 11.8) ------------------------------
    # One key per condition so the readout tracks the spec's own five clauses
    # instead of an implementation-shaped summary.
    "trial.header": ("=== Second Soul trial (second C seat) ===",
                     "=== 第二灵魂试炼（第二圣契位）==="),
    "trial.distinct_anchors": (
        "Awaken B-tier companions of distinct anchors",
        "觉醒不同锚点的 B 级伙伴"),
    "trial.altar_activated": (
        "Build and awaken the Soul Altar",
        "建造并唤醒灵魂圣坛"),
    "trial.capacity_shards": (
        "Offer capacity shards at the altar",
        "在圣坛献上契约容量碎片"),
    "trial.boss_or_delve": (
        "Defeat a Warden or Elder Guardian, or delve the Deep Dark",
        "击败监守者或远古守卫者，或完成一次深暗之城探索"),
    "trial.grand_sacrifice": (
        "Complete a grand rite with three different anchors",
        "以三种不同锚点完成一次大型灵魂仪式"),
    "trial.row": ("  %s: %s", "  %s：%s"),
    "trial.ready": (
        "Every clause is satisfied. The second covenant seat awaits.",
        "所有条件均已满足，第二圣契位在等待你。"),
    "trial.not_ready": (
        "The trial is not yet complete.",
        "试炼尚未完成。"),
    "trial.boss_defeated": ("Trial clause cleared: %s",
                            "试炼条件达成：%s"),
    "trial.sacrifice_done": (
        "The grand rite is done - %s souls were offered.",
        "大型仪式完成 - 已有 %s 个灵魂被献上。"),
    "trial.boss.warden": ("the Warden", "监守者"),
    "trial.boss.elder_guardian": ("the Elder Guardian", "远古守卫者"),
    "trial.boss.deep_dark": ("a Deep Dark delve", "一次深暗之城探索"),
    # --- settings --------------------------------------------------------
    "cmd.setting_particles": ("Particle quality", "粒子质量"),
    "cmd.setting_effects": ("Ritual effects", "仪式特效"),
    "cmd.setting_shake": ("Screen shake", "屏幕震动"),
    "cmd.setting_active": ("  Active ceremonies: %s", "  进行中的法阵：%s"),
    "cmd.quality.low": ("low", "低"),
    "cmd.quality.medium": ("medium", "中"),
    "cmd.quality.high": ("high", "高"),
    "cmd.toggle.on": ("on", "开"),
    "cmd.toggle.off": ("off", "关"),
    "cmd.shake.off": ("off", "关"),
    "cmd.shake.weak": ("weak", "弱"),
    "cmd.shake.normal": ("normal", "普通"),
    "cmd.shake.strong": ("strong", "强"),
}

# ---------------------------------------------------------------------------
# The ritual settings screen (spec 13.1) and its keybind.
#
# Spec 13.1 lists the same three settings the commands expose, but a command
# is not discoverable, so the screen is the player-facing form of that table.
# The value keys are shared with `cmd.quality.*` / `cmd.shake.*` in spirit, but
# kept separate because the screen's wording is title-case ("Low") where the
# command's is inline ("low") - the spec asks for a settings UI, not a log.
# ---------------------------------------------------------------------------
UI = {
    "key.golem_covenant.ritual_settings": (
        "Open Ritual Settings", "打开法阵设置"),
    "screen.ritual.title": ("Ritual Effects", "仪式特效设置"),
    "setting.rituals": ("Ritual Effects", "仪式特效"),
    "setting.quality": ("Particle Quality", "粒子质量"),
    "setting.shake": ("Screen Shake", "屏幕震动"),
    "setting.hint.low": (
        "Low keeps only the circle outline and the core.",
        "低质量仅保留法阵轮廓与核心粒子。"),
    "setting.hint.scale": (
        "Density drops automatically when many companions are nearby.",
        "同屏伙伴较多时会自动降低粒子密度。"),
    "value.on": ("On", "开"),
    "value.off": ("Off", "关"),
    "value.quality.low": ("Low", "低"),
    "value.quality.medium": ("Medium", "中"),
    "value.quality.high": ("High", "高"),
    "value.shake.off": ("Off", "关"),
    "value.shake.weak": ("Weak", "弱"),
    "value.shake.normal": ("Normal", "普通"),
    "value.shake.strong": ("Strong", "强"),
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
    # blocks (spec 4.3.1 / 4.3.2) - a separate namespace from the item key
    for k, v in BLOCKS.items():
        out[f"block.golem_covenant.{k}"] = v[0] if en else v[1]
    merge(out, ITEM_TOOLTIP, en)
    for k, v in MESSAGES.items():
        out[f"golem_covenant.{k}"] = v[0] if en else v[1]
    for k, v in DEATH_WILL_MSG.items():
        out[f"golem_covenant.{k}"] = v[0] if en else v[1]
    # these blocks already carry their full sub-path, so prefix once
    for k, v in BOND_MSG.items():
        out[f"golem_covenant.{k}"] = v[0] if en else v[1]
    for k, v in RESONANCE.items():
        out[f"golem_covenant.{k}"] = v[0] if en else v[1]
    merge(out, KEYS, en)
    merge(out, HUD, en)
    # COMMANDS keys are bare `cmd.*`, so they need the namespace prefix here;
    # every other block either already carries its full path or is merged by
    # its own helper. Getting this wrong ships raw keys to chat, which is
    # exactly what validate_lang.py checks for.
    for k, v in COMMANDS.items():
        out[f"golem_covenant.{k}"] = v[0] if en else v[1]

    # Screen / keybind keys (spec 13.1). `key.*` is the one exception: MC reads
    # keybind labels from the full `key.<namespace>.<name>` path, so those are
    # already fully qualified and must NOT be prefixed a second time.
    for k, v in UI.items():
        if k.startswith("key."):
            out[k] = v[0] if en else v[1]
        else:
            out[f"golem_covenant.{k}"] = v[0] if en else v[1]

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

    # spec ch.6: anchor display names. Derived from the registry so a new
    # anchor can never ship without a name (spec 11.15.4).
    for aid, a in (reg.get("anchors") or {}).items():
        tr = ANCHOR_EN.get(aid) or {}
        # spec 11.15.4: en_us must be real English; the Chinese source text is
        # only a fallback for an anchor that has not been translated yet.
        name = (tr.get("name") or a.get("name")) if en else a.get("name")
        out[f"golem_covenant.anchor.{aid}"] = name or _humanize(aid)
        # spec 11.15.5: each of the seven textual dimensions is shown in the
        # form tooltip, so each needs a key of its own.
        for dim in ("watch", "source", "trigger", "combat", "ai", "zone",
                    "interact"):
            text = a.get(dim)
            if not text:
                continue
            if en:
                text = tr.get(dim) or text
            out[f"golem_covenant.anchor.{aid}.{dim}"] = text
    # the anchor-dimension labels themselves (spec 6.2's numbered list)
    for dim, label in ANCHOR_DIMENSION_LABELS.items():
        out[f"golem_covenant.anchor.dim.{dim}"] = label[0] if en else label[1]
    for bh, v in (reg.get("anchorBehaviours") or {}).items():
        if en:
            out[f"golem_covenant.behaviour.{bh}"] = (
                BEHAVIOUR_EN.get(bh) or _humanize(bh))
        else:
            out[f"golem_covenant.behaviour.{bh}"] = v.get("name") or _humanize(bh)
    # The reserved band (spec 11.2.3) has no anchor of its own, so /golem
    # anchor and /golem bond need a name to fall back to instead of a raw key.
    out["golem_covenant.anchor.reserved"] = (
        "Unimplemented" if en else "未实装")
    return out


# Spec 6.2's nine dimensions, localised for the tooltip (spec 11.15.5).
ANCHOR_DIMENSION_LABELS = {
    "watch": ("Watches", "观察对象"),
    "source": ("Resource", "资源来源"),
    "trigger": ("Trigger", "触发条件"),
    "combat": ("Combat", "战斗方式"),
    "ai": ("AI Decision", "AI 决策"),
    "zone": ("Zone Rule", "区域规则"),
    "interact": ("Player Interaction", "玩家互动"),
    "deathWill": ("Death Will", "死亡遗志"),
    "ritual": ("Ritual", "仪式结构"),
    # Not one of the nine: the Bond-gated depth a companion has unlocked,
    # shown by /golem anchor and /golem bond (spec 9.4).
    "depth": ("Depth", "深度"),
}

# ---------------------------------------------------------------------------
# Anchor English translations (spec 11.15.4: en_us must be real English, not
# a copy of the Chinese source). Keyed by anchorId, then by the seven textual
# dimensions of spec 6.2. Anything missing falls back to the Chinese text so a
# newly added anchor degrades gracefully instead of producing a blank tooltip.
# ---------------------------------------------------------------------------
ANCHOR_EN: dict[str, dict[str, str]] = {
    "zombie_death": dict(
        name="Death Anchor",
        watch="the deaths of enemies",
        source="a death leaves a death-trail node",
        trigger="any enemy dies within 16 blocks",
        combat="treats the spot where an enemy died as an attack node, "
               "instead of chasing the enemy",
        ai="moves to the nearest death-trail rather than the nearest enemy",
        zone="several death-trails link into a route that speeds up travel",
        interact="standing on a death-trail adds its mark to your next attack"),
    "zombie_grave": dict(
        name="Grave Anchor",
        watch="where and which way corpses fell",
        source="grave-nail markers",
        trigger="the companion passes any corpse site",
        combat="holds the grave: same damage, but a wider reach",
        ai="never pursues past the grave radius; falls back to guarding it",
        zone="each grave-nail claims a 4-block radius as friendly ground",
        interact="resting on a grave-nail shortens the companion's next "
                 "mechanism cooldown"),
    "zombie_desiccation": dict(
        name="Desiccation Anchor",
        watch="enemy health and their positive effects",
        source="a dryness value",
        trigger="the target carries any beneficial effect",
        combat="strips buffs layer by layer instead of adding damage",
        ai="ignores the closer enemy and locks the buffed one",
        zone="a desiccation field around itself drains buffs faster",
        interact="potions drunk inside the field last half as long but hit "
                 "harder"),
    "zombie_tide": dict(
        name="Tide Anchor",
        watch="the time between consecutive kills",
        source="a tide level that rises with each chain kill",
        trigger="two kills land less than 5 seconds apart",
        combat="a higher tide means faster pursuit speed, never more damage",
        ai="stops withdrawing while the tide is high and keeps tracking",
        zone="a full tide lays down a hunt route that exposes enemies on it",
        interact="following the hunt route grants you matching speed"),
    "zombie_bone_arrow": dict(
        name="Bone Arrow Anchor",
        watch="the target's movement vector",
        source="a predicted impact point",
        trigger="the target has moved continuously for over a second",
        combat="shoots the predicted point instead of the current position",
        ai="prefers targets travelling in a straight line (easier to predict)",
        zone="a hovering arrow-rune marks anything entering the predicted point",
        interact="your own arrows gently correct toward the predicted point"),
    "zombie_frost_arrow": dict(
        name="Frost Trail Anchor",
        watch="the trail a target leaves while moving",
        source="a cold-trail reading",
        trigger="the target crosses the same stretch twice in 3 seconds",
        combat="projectiles on the cold trail freeze rather than add damage",
        ai="seals off the corridors enemies habitually use",
        zone="the cold trail becomes slippery ice that speeds up allies",
        interact="you accelerate in step with your companion on the ice"),
    "zombie_wither_blade": dict(
        name="Wither Blade Anchor",
        watch="how many negative effects an enemy carries",
        source="wither stacks",
        trigger="the target carries 3 or more negative effects",
        combat="executes above a health threshold rather than scaling damage",
        ai="only locks weakened targets and ignores full-health ones",
        zone="a successful execution leaves a wither-blade field behind",
        interact="your attacks inside the field add one extra wither tick"),
    "zombie_village_cycle": dict(
        name="Cycle Anchor",
        watch="plant growth and crop ripening around it",
        source="a cycle value",
        trigger="a nearby crop completes one growth stage",
        combat="converts the cycle value into an area regeneration, not damage",
        ai="prefers to linger on farmland and dense vegetation",
        zone="a full cycle value ripens the surrounding crops",
        interact="harvesting a crop instantly refills the companion's cycle"),
    "zombie_gold_contract": dict(
        name="Golden Contract Anchor",
        watch="the loot and gear an enemy carries",
        source="a contract weight",
        trigger="the target wears or carries anything",
        combat="robs rather than kills: hits strip the target's equipment",
        ai="locks the best-equipped enemy instead of the weakest",
        zone="a robbed enemy is left unarmoured",
        interact="picking up the taken gear grants you a short boon"),
    "zombie_gold_flame": dict(
        name="Golden Flame Anchor",
        watch="how many enemies are burning",
        source="searing marks",
        trigger="any enemy is on fire",
        combat="fire spreads between enemies; the companion adds no damage",
        ai="attacks the ones not yet burning to widen the field",
        zone="enemies inside the fire field cannot hide",
        interact="lighting an enemy with a torch adds two searing marks at once"),

    "arthropod_hive": dict(
        name="Hive Anchor",
        watch="how many enemies are marked and where",
        source="hive threads",
        trigger="two or more enemies are marked",
        combat="switches between guarding, healing and controlling instead of "
               "dealing fixed damage",
        ai="holds formation around the centre of the hive network",
        zone="marks form a hexagonal hive-net that shields allies inside it",
        interact="attacking a target adds it to the hive network"),
    "arthropod_web_of_fate": dict(
        name="Fate Weave Anchor",
        watch="the paths enemies walk",
        source="memory strands",
        trigger="an enemy repeats the same route",
        combat="weaves across the predicted path instead of striking directly",
        ai="tracks a target's historical route, not its current position",
        zone="strands cross into a fate-web that restricts movement inside it",
        interact="standing on a web node expands the web's coverage"),
    "arthropod_venom_nest": dict(
        name="Venom Nest Anchor",
        watch="where poisoned targets are",
        source="nest venom",
        trigger="3 poisoned enemies are inside range",
        combat="fuses the poison into one area burst rather than stacking damage",
        ai="moves toward the thickest concentration of poison",
        zone="ground under the nest keeps poisoning anyone who steps on it",
        interact="using any potion on the nest turns it into poison mist"),
    "arthropod_shadow_mite": dict(
        name="Shadow Mite Anchor",
        watch="the space behind you and outside your view",
        source="a shadow gap",
        trigger="an enemy enters your 120-degree blind spot",
        combat="only ever strikes targets inside the blind spot",
        ai="always circles around to a target's back",
        zone="enemies inside the shadow gap stay marked",
        interact="the companion fills in automatically when you turn away"),
    "arthropod_burrow_swarm": dict(
        name="Burrow Anchor",
        watch="ground material and which blocks can be tunnelled",
        source="a burrow counter",
        trigger="the companion stands on soft blocks (dirt, sand, mud)",
        combat="ambushes from below: hits knock the target away, not down",
        ai="always routes its pursuit over soft ground",
        zone="tunnel-able blocks become friendly passages",
        interact="sneaking on soft ground makes the companion sneak too"),

    "animal_hunt": dict(
        name="Hunt Anchor",
        watch="the target you and the companion are both attacking",
        source="hunt marks",
        trigger="you and your companion strike the same target",
        combat="enough hunt marks summon a brief shadow pack; damage never rises",
        ai="always locks your target and never switches on its own",
        zone="a full hunt mark opens a hunting domain that publicly marks its prey",
        interact="sustained attacks on one target accelerate the hunt marks"),
    "animal_charge_horn": dict(
        name="Charge Anchor",
        watch="the line between the companion and an enemy",
        source="charge momentum",
        trigger="the target is over 8 blocks away with a clear line",
        combat="charges in a straight line and forces displacement, not damage",
        ai="prefers enemies standing in a row for a longer run-up",
        zone="the charge briefly clears its path of obstructing terrain",
        interact="running the same direction as the charge gives you a push"),
    "animal_spore": dict(
        name="Spore Anchor",
        watch="the surrounding biome and humidity",
        source="spore dust",
        trigger="vegetation or damp blocks are nearby",
        combat="releases a spore cloud that slows and marks whoever it touches",
        ai="prefers to fight where vegetation is dense",
        zone="the cloud persists as a field where allies regenerate",
        interact="breaking vegetation refills a large amount of spore dust"),
    "animal_tusk": dict(
        name="Tusk Anchor",
        watch="a target's facing and guard direction",
        source="a break point",
        trigger="the target turns away or is busy attacking someone else",
        combat="thrusts only at the break point, ignoring the target's guard",
        ai="flanking comes before trading blows head-on",
        zone="a pierced break point leaves a gap allies can pass through",
        interact="attacking from the same side shares the break-point read"),
    "animal_wool": dict(
        name="Wool Anchor",
        watch="the damage types friendly units take",
        source="wool layers",
        trigger="any friendly unit takes damage",
        combat="does not retaliate; converts the damage into a buffer layer",
        ai="always stands between you and the nearest enemy",
        zone="allies inside the wool radius take reduced damage per layer",
        interact="wool layers transfer to you when you are hit"),
    "animal_plume_dance": dict(
        name="Plume Dance Anchor",
        watch="its own feather colour against the surrounding terrain",
        source="plumage camouflage",
        trigger="its colour closely matches the environment",
        combat="enemies cannot lock onto it while camouflaged, so it strikes free",
        ai="actively moves toward terrain of a matching colour",
        zone="enemies lose their targets inside the camouflage area",
        interact="wearing matching armour gives you the same camouflage"),
    "animal_moon_leap": dict(
        name="Moon Leap Anchor",
        watch="light level and moon phase",
        source="moon energy",
        trigger="night or a low-light environment",
        combat="leaps to reposition instead of running, knocking targets back",
        ai="turns aggressive at night and switches to escorting by day",
        zone="a full moon energy leaves a short-lived leap point where it lands",
        interact="following the companion's leaps at night boosts your jumps"),
    "animal_night_prowl": dict(
        name="Night Prowl Anchor",
        watch="an enemy's facing and alert state",
        source="a stealth value",
        trigger="the target has not noticed it yet",
        combat="opens from stealth and forces the target's alertness off on hit",
        ai="stays stealthed until inside striking distance",
        zone="the stealth field widens as it moves",
        interact="sneaking shares the stealth value with you"),
    "animal_nine_lives": dict(
        name="Return Anchor",
        watch="your health ratio",
        source="remaining lives",
        trigger="your health drops below 40%",
        combat="drops its target immediately to return, landing with a knockback",
        ai="comes back to you before anything else, always",
        zone="the landing point becomes a brief safe circle",
        interact="it automatically marks that spot while you are low"),
    "animal_jungle_stalk": dict(
        name="Jungle Stalk Anchor",
        watch="how vegetation blocks sightlines",
        source="shadow traces",
        trigger="vegetation breaks the line between it and the target",
        combat="strikes through foliage without giving up the concealment",
        ai="always approaches along obstructed routes",
        zone="dense vegetation counts as friendly cover",
        interact="hiding in vegetation conceals you alongside it"),
    "animal_steady_hoof": dict(
        name="Steady Anchor",
        watch="the height differences underfoot",
        source="a steadiness value",
        trigger="it moves across uneven ground",
        combat="does not attack; flattens the terrain with its steadiness",
        ai="escorts you along the flattest available route",
        zone="flattened ground speeds up every friendly unit",
        interact="riding on flattened ground is faster for you"),

    "mount_charge_horn": dict(
        name="Charge Horn Anchor",
        watch="how enemies are arranged along a charge path",
        source="horn momentum",
        trigger="3 or more enemies sit on the path",
        combat="a single pass-through that shoves every enemy it meets",
        ai="picks the direction with the densest enemies",
        zone="the charge lane becomes a friendly runway",
        interact="riding on the runway removes your slowdown"),
    "mount_steady_hoof": dict(
        name="Steady Hoof Anchor",
        watch="sync between mount and rider",
        source="a sync value",
        trigger="you are riding",
        combat="does not attack while ridden; it drives steadily instead",
        ai="follows your steering input completely",
        zone="a full sync value makes it immune to being dismounted",
        interact="you take half knockback while riding"),
    "mount_burden": dict(
        name="Burden Anchor",
        watch="how loaded you and it are",
        source="a burden value",
        trigger="your inventory approaches full",
        combat="trades weight for impact: the heavier it is, the further it shoves",
        ai="avoids narrow terrain when fully loaded",
        zone="the burden becomes a weight field that suppresses all jumping",
        interact="you can load items onto it to raise the burden value"),
    "mount_sand_march": dict(
        name="Sand March Anchor",
        watch="the ground material (sand, gravel)",
        source="a sand march",
        trigger="it travels over sandy terrain for a while",
        combat="kicks up dust and blinds whoever it hits",
        ai="deliberately follows sand to build up the march",
        zone="the march raises a sand wall that cuts enemy sightlines",
        interact="you can shoot from behind the wall without being retaliated on"),
    "mount_sky_dash": dict(
        name="Sky Dash Anchor",
        watch="its own altitude and airtime",
        source="lift",
        trigger="it is off the ground",
        combat="dashes through the air and quakes the area on landing",
        ai="prefers routes with elevation changes",
        zone="the landing quake counts as friendly ground",
        interact="jumping together shares the lift with you"),

    "snow_snow_prowl": dict(
        name="Snow Prowl Anchor",
        watch="footprints left in the snow",
        source="snow traces",
        trigger="it moves across a snow layer",
        combat="ambushes from wherever the snow traces are thickest",
        ai="always retraces its own trail when returning",
        zone="the traced area becomes a friendly vision zone",
        interact="following the trail prevents you being ambushed"),
    "snow_rock_roll": dict(
        name="Rolling Stone Anchor",
        watch="slope direction and steepness",
        source="rolling momentum",
        trigger="it is on a downhill slope",
        combat="rolls down and hits; the impact scales with distance, not slope",
        ai="actively seeks downhill routes to attack along",
        zone="the rolling path is flattened into a corridor",
        interact="you accelerate sliding down the same path"),
    "snow_bamboo": dict(
        name="Bamboo Grove Anchor",
        watch="the distribution of bamboo blocks",
        source="bamboo nodes",
        trigger="bamboo is nearby",
        combat="moves fast through the grove and entangles rather than damages",
        ai="only fights inside the grove and withdraws when it leaves",
        zone="allies inside the grove gain concealment",
        interact="planting bamboo expands the grove"),
    "snow_arctic_fang": dict(
        name="Arctic Fang Anchor",
        watch="a target's body temperature",
        source="frost layers",
        trigger="the target is in a cold biome",
        combat="bites to stack frost; a full stack freezes instead of hurting",
        ai="pushes the target away from heat sources",
        zone="the frost layer spreads into a freezing zone",
        interact="hitting a target with a snowball adds one frost layer"),
    "snow_mountain_horn": dict(
        name="Mountain Anchor",
        watch="altitude and how the mountain blocks sound",
        source="an echo value",
        trigger="it is at high altitude",
        combat="shouts to knock targets back and reveal anything invisible",
        ai="takes the high ground whenever it can",
        zone="the echoing summit counts as a friendly strongpoint",
        interact="standing in the strongpoint widens your view without changing "
                 "your damage"),

    "environment_warm_spring": dict(
        name="Warm Spring Anchor",
        watch="ambient temperature and water",
        source="rising vapour",
        trigger="water or a hot block is nearby",
        combat="steam clouds the view and pushes away whoever it touches",
        ai="always fights near water",
        zone="allies inside the spring keep recovering",
        interact="fighting near water doubles your recovery"),
    "environment_grass_echo": dict(
        name="Grass Echo Anchor",
        watch="the vegetation and grass cover underfoot",
        source="a grass echo",
        trigger="it moves over grass or moss",
        combat="turns the echo into entanglement; targets lose their sprint",
        ai="travels along the densest vegetation routes",
        zone="the echoed area is friendly cover",
        interact="sneaking on grass conceals you alongside it"),
    "environment_frost_leap": dict(
        name="Frost Leap Anchor",
        watch="which liquid surfaces can be frozen",
        source="a freezing point",
        trigger="liquid sits near the landing point",
        combat="freezes the landing area and traps enemies instead of damaging",
        ai="prefers to land near liquids",
        zone="frozen surfaces become friendly walkable ground",
        interact="you never slip on the frozen surface"),
    "environment_biome_orb": dict(
        name="Biome Anchor",
        watch="the current biome type",
        source="a biome orb that reshapes per biome",
        trigger="you enter a new biome",
        combat="the orb's form decides the fighting style: temperate entangles, "
               "cold freezes, hot evaporates",
        ai="changes behaviour to follow the biome you are in",
        zone="the orb briefly rewrites the local terrain into the current biome",
        interact="interacting in different biomes yields different responses"),

    "aquatic_echo_sense": dict(
        name="Echo Anchor",
        watch="sound and sonar reflections in the water",
        source="visible sound-prints",
        trigger="any creature moves or is struck in water",
        combat="does not strike; it reveals sound sources for allies to hit",
        ai="moves toward the densest sound-prints",
        zone="every important sound inside the echo domain has its source exposed",
        interact="being ambushed releases one locating pulse"),
    "aquatic_tide_shell": dict(
        name="Tide Shell Anchor",
        watch="water level and depth",
        source="tide layers",
        trigger="it is in water",
        combat="turns tide layers into a shell that absorbs rather than counters",
        ai="stays inside water",
        zone="pressure rises inside the tide layer and slows anything entering",
        interact="swimming with it tops up your water breathing"),
    "aquatic_deep_ink": dict(
        name="Deep Ink Anchor",
        watch="which way enemies are looking",
        source="an ink reserve",
        trigger="an enemy is locking onto a friendly unit",
        combat="sprays ink to blind; a blinded enemy loses its target",
        ai="prioritises enemies that are locking onto you",
        zone="the ink cloud is an absolute friendly cover area",
        interact="you cannot be ranged-locked inside the ink cloud"),
    "aquatic_current_dash": dict(
        name="Current Anchor",
        watch="the direction water flows",
        source="a current value",
        trigger="it is in flowing water",
        combat="dashes with the current; against it, it garrisons instead",
        ai="always fights along the flow direction",
        zone="the current path is a friendly fast lane",
        interact="travelling downstream speeds you up in sync with it"),
    "aquatic_spine_guard": dict(
        name="Spine Anchor",
        watch="how many enemies are closing in",
        source="spine layers",
        trigger="3 or more enemies enter a 6-block radius",
        combat="extends spines so attackers suffer backlash: reflection, not bonus",
        ai="only extends them when surrounded; otherwise it follows",
        zone="the spine radius is a denial zone that keeps pushing enemies out",
        interact="you take half the reflected damage inside the denial zone"),
    "aquatic_school_charge": dict(
        name="School Anchor",
        watch="how many of the same species are nearby",
        source="school strength",
        trigger="same-species allies are nearby",
        combat="a stronger school pushes further, never hits harder",
        ai="always keeps formation with same-species allies",
        zone="the school's formation is a friendly phalanx area",
        interact="standing in the centre grants formation protection"),
    "aquatic_coral_scale": dict(
        name="Coral Anchor",
        watch="surrounding coral and warm-water blocks",
        source="coral scales",
        trigger="coral is nearby",
        combat="scales shift with the coral colour and resist the matching element",
        ai="moves toward coral matching its own scale colour",
        zone="coral counts as a friendly recovery area",
        interact="harvesting coral there does not startle it"),
    "aquatic_regeneration_gill": dict(
        name="Regeneration Anchor",
        watch="how much health friendly units are missing",
        source="gill breath",
        trigger="any friendly unit drops below half health",
        combat="does not attack; it keeps converting breath into area regen",
        ai="stays glued to the lowest-health friendly unit",
        zone="recovery inside the gill radius cannot be broken up by currents",
        interact="reviving a weakened companion inside it is faster"),

    "construct_guardian_watch": dict(
        name="Watch Anchor",
        watch="the movement range of whoever it protects",
        source="a watch radius",
        trigger="the protected unit leaves the assigned area",
        combat="intercepts everything entering the watch radius rather than "
               "dealing damage",
        ai="barely moves; it patrols only its radius",
        zone="the watch radius is inviolable",
        interact="you can designate a new protection point inside the radius"),
    "construct_city_wall": dict(
        name="City Wall Anchor",
        watch="buildable blocks and terrain",
        source="wall structure",
        trigger="placeable blocks are nearby",
        combat="does not attack; it reshapes terrain into a wall to block enemies",
        ai="patrols along the wall and routes around it, never through it",
        zone="the wall is a hard seal enemies have to walk around",
        interact="you can tear the wall down to recover materials"),

    "nether_end_space": dict(
        name="Space Anchor",
        watch="the spatial positions of you and the battlefield",
        source="space nodes",
        trigger="you record a safe position",
        combat="links three nodes to allow limited safe transposition, not "
               "teleport attacks",
        ai="continuously maintains the links between its three nodes",
        zone="the node coverage is the transposition area",
        interact="you can swap between nodes to a limited degree"),
    "nether_end_deep_echo": dict(
        name="Deep Echo Anchor",
        watch="footsteps, collisions, throws, creature movement, ambient sound",
        source="visible sound-prints",
        trigger="any sound event occurs",
        combat="does not attack; it visualises every sound source for the team",
        ai="moves toward the most recent sound event",
        zone="every important sound inside the domain has its source exposed",
        interact="being ambushed releases one locating pulse"),
    "nether_end_elastic": dict(
        name="Elastic Anchor",
        watch="collision direction and speed",
        source="elastic energy",
        trigger="it collides with something",
        combat="bounces along the collision direction, shoving enemies in its path",
        ai="deliberately collides with blocks to build up elastic energy",
        zone="the bounce path becomes a trampoline corridor",
        interact="colliding with your companion gives you the same bounce"),
    "nether_end_magma_core": dict(
        name="Magma Core Anchor",
        watch="surrounding heat and fire",
        source="core temperature",
        trigger="a fire source or hot block is nearby",
        combat="absorbs heat, then releases a non-destructive heat wave",
        ai="moves toward heat sources",
        zone="the ground under the core stays hot and burns hostile units",
        interact="you take no lava damage inside the core"),
    "nether_end_sun_flare": dict(
        name="Sun Flare Anchor",
        watch="light intensity and how exposed to the sky it is",
        source="a flare value",
        trigger="the sky is open overhead and it is daytime",
        combat="releases a blinding flare; blinded units briefly lose control",
        ai="takes open-sky positions whenever possible",
        zone="enemies inside the flare cannot aim",
        interact="ranged attacks from the flare centre cannot miss"),
    "nether_end_wail": dict(
        name="Wail Anchor",
        watch="how enemies react to sound",
        source="a sound field",
        trigger="enemies approach a friendly position",
        combat="drives enemies off with a wail rather than damaging them",
        ai="herds enemies in a chosen direction using sound",
        zone="enemies are forced out of the sound field",
        interact="you can pick the herding direction from inside the field"),
    "nether_end_lava_stride": dict(
        name="Lava Stride Anchor",
        watch="lava and hot liquid blocks",
        source="stride points",
        trigger="it steps on lava",
        combat="moves fast across lava and rams enemies on the surface",
        ai="treats lava surfaces as its primary travel lanes",
        zone="lava surfaces become friendly passages",
        interact="crossing the lava with it grants you brief fire immunity"),
    "nether_end_crimson_tusk": dict(
        name="Crimson Tusk Anchor",
        watch="a target's knockback resistance",
        source="crimson momentum",
        trigger="the target is knocked back",
        combat="chains knockbacks: displacement control, never damage",
        ai="prefers pushable targets and skips resistant ones",
        zone="the knockback path becomes a crimson corridor",
        interact="pushing in the same direction stacks with its effect"),
    "nether_end_shell": dict(
        name="Shell Anchor",
        watch="the state of whoever rides on its back",
        source="shell layers",
        trigger="a unit is riding it",
        combat="defends better while carrying and stops attacking",
        ai="always faces the enemy to shield its rider",
        zone="the shell is a safe foothold",
        interact="you cannot be knocked back while standing on the shell"),
    "nether_end_prism": dict(
        name="Prism Anchor",
        watch="the angle of light hitting it",
        source="a refraction value",
        trigger="any light source illuminates it",
        combat="refracts light outward at enemies along the path, borrowing the "
               "environment rather than burning itself",
        ai="moves to sit between a light source and an enemy",
        zone="the refraction path is lit, so enemies cannot hide",
        interact="shining a light on it lets you choose the refraction direction"),

    "special_implosion": dict(
        name="Blast Anchor",
        watch="impacts, knockback, collisions and blast shocks",
        source="stored shock energy",
        trigger="any of those events occurs",
        combat="releases a non-destructive shock wave that only displaces",
        ai="deliberately creates collisions to charge up",
        zone="the shock wave radius is a knockback zone",
        interact="you cannot be knocked down inside the shock wave"),
    "special_sulfur_core": dict(
        name="Core Anchor",
        watch="every event that happens on the battlefield",
        source="core absorption",
        trigger="any event occurs nearby",
        combat="picks its fighting style from whatever it has absorbed",
        ai="makes no fixed decision; the absorbed content drives it entirely",
        zone="the terrain under the core shifts with what it absorbed",
        interact="feeding it blocks changes its form"),
    "special_profession": dict(
        name="Profession Anchor",
        watch="the production flow of its trade",
        source="profession resources",
        trigger="you complete an action matching its trade",
        combat="does not fight directly; converts trade resources into support",
        ai="lingers near the matching functional block",
        zone="the trade area is a friendly resupply point",
        interact="you gain the matching trade boon inside the resupply point"),
    "special_plume_echo": dict(
        name="Plume Echo Anchor",
        watch="the pitch and rhythm of surrounding sound",
        source="echo feathers",
        trigger="ambient volume crosses a threshold",
        combat="converts sound into a feather barrage that causes disharmony",
        ai="follows sound sources and aligns to their rhythm",
        zone="sound is amplified here, so enemies cannot sneak in",
        interact="calling out inside the field strengthens its next mechanism"),
}


# ---------------------------------------------------------------------------
# Anchor behaviour display names (spec 11.13 / 11.15.4).
#
# The registry's anchorBehaviours block stores Chinese names only, so en_us is
# translated here. A behaviour added to the registry without an entry falls
# back to a humanised id, and validate_lang.py flags the missing CJK-free
# value - so this table cannot silently fall behind the vocabulary.
# ---------------------------------------------------------------------------
BEHAVIOUR_EN = {
    "arctic_fang": "Arctic Fang",
    "arrow_forecast": "Impact Forecast",
    "bamboo": "Bamboo Grove",
    "biome_orb": "Biome Domain",
    "blade_reap": "Withering Reap",
    "burden": "Burden Field",
    "burrow_swarm": "Burrow Swarm",
    "charge_horn": "Charge Horn",
    "city_wall": "City Bulwark",
    "coral_scale": "Coral Scale",
    "corpse_anchor": "Corpse Anchor",
    "crimson_tusk": "Crimson Tusk",
    "current_dash": "Current Dash",
    "death_harvest": "Death Harvest",
    "deep_echo": "Deep Echo",
    "deep_ink": "Deep Ink Veil",
    "desiccation_field": "Desiccation Field",
    "echo_sense": "Echo Sense",
    "elastic": "Elastic Anchor",
    "frost_leap": "Frost Leap",
    "frost_trajectory": "Frost Trajectory",
    "gold_contract": "Golden Contract",
    "gold_flame": "Golden Flame",
    "grass_echo": "Verdant Echo",
    "guardian_watch": "Guardian Watch",
    "hive": "Hive Network",
    "hunt": "Hunt Anchor",
    "implosion": "Implosion Anchor",
    "jungle_stalk": "Jungle Stalk",
    "lava_stride": "Lava Stride",
    "magma_core": "Magma Core",
    "moon_leap": "Moon Leap",
    "mountain_horn": "Mountain Echo",
    "night_prowl": "Night Prowl",
    "nine_lives": "Nine Lives Rescue",
    "plume_dance": "Plume Dance",
    "plume_echo": "Plume Echo",
    "prism": "Prism Refraction",
    "profession": "Profession Anchor",
    "regeneration_gill": "Regenerating Gill",
    "rock_roll": "Rolling Stone Field",
    "sand_march": "Sand March",
    "school_charge": "School Charge",
    "shadow_mite": "Shadow Mite",
    "shell": "Carapace Anchor",
    "sky_dash": "Sky Dash",
    "snow_prowl": "Snowfield Prowl",
    "space": "Space Anchor",
    "spine_guard": "Spine Guard",
    "spore": "Spore Bloom",
    "steady_hoof": "Steady Hoof",
    "sulfur_core": "Core Anchor",
    "sun_flare": "Sun Flare",
    "tide_pursuit": "Tide Pursuit",
    "tide_shell": "Tidal Shell",
    "tusk": "Tusk Thrust",
    "venom_nest": "Venom Nest",
    "village_cycle": "Village Cycle",
    "wail": "Wailing Field",
    "warm_spring": "Warm Spring",
    "web_of_fate": "Web of Fate",
    "wool": "Wool Cushion",
}


def _humanize(sid: str) -> str:
    if not sid or sid == "reserved":
        return ""
    return sid.replace("_", " ").title()


def _ritual_en(form: dict) -> str:
    """
    English name for a form's magic-circle theme (spec 5.3 / 11.13).

    The ritual symbol is Chinese (`断墓骨环`). Its geometry id is English and
    already distinguishes the circle's shape, which is what the English reader
    needs from this line, so the geometry is humanized.

    The reserved band (spec 11.2.3) has no geometry - it exists so that a
    future form can drop into a known slot - so its circle is named by that
    slot, mirroring how `_form_name_en` names the reserved forms themselves.
    """
    geometry = (form.get("ritual") or {}).get("geometry")
    # `reserved` is a placeholder value, not a real circle, so it must not be
    # humanized to the literal word "Reserved" and then read as a circle name.
    if geometry and geometry != "reserved":
        return _humanize(geometry)
    if form.get("status") == "reserved":
        number = form.get("sourceId") or form.get("number") or ""
        return f"Reserved Circle {number}".strip()
    return ""


def _ability_en(form: dict, id_key: str, fallback: str = "") -> str:
    """
    English name for one of a form's abilities (spec 11.13 sub-keys).

    Careful: `bActiveId` is NOT usable for this. It was built by escaping each
    CJK codepoint to `u<hex>` to keep it a unique identifier, so humanizing it
    yields `Zombie U4Ea1U9Ab8U58C1U5792` - an internal id leaking into a
    tooltip. It is used only as a *presence* test here.

    The other ids (`chain`, `gravity_well`, `damage_reduction`) are genuine
    English snake_case and humanize cleanly, so they are the ones that carry
    the name. Where a form has only an escaped id, `fallback` is used, which is
    composed from language-neutral fields instead.
    """
    raw = form.get(id_key, "")
    if not raw or raw == "reserved":
        return fallback or _generic_ability_en(form)
    # Reject escaped-CJK ids: they are identifiers, not names.
    if re.search(r"u[0-9a-f]{4}", raw):
        return fallback or _generic_ability_en(form)
    return _humanize(raw) or fallback or _generic_ability_en(form)


def _generic_ability_en(form: dict) -> str:
    """
    Last-resort English label, composed from fields that are always English.

    Reached by forms whose ability ids are all escaped or absent, which is the
    whole reserved band plus a handful of active forms. The composition walks a
    list of candidates in decreasing specificity and returns the first that
    yields something:

      family + variant   e.g. `Beast Color`   (active, id was escaped)
      entity name        e.g. `Wolf`
      "Reserved <id>"    guaranteed non-empty (the reserved band)

    The final fallback matters: `validate_lang.py` compares en and zh key *sets*
    and fails on asymmetry, so a form must never produce an empty English value
    while its Chinese counterpart is non-empty. Reserved forms would otherwise
    hit it - `familyId` and `variantType` are both the literal `"reserved"` and
    `_humanize` maps that to `""`.
    """
    family = _humanize(form.get("familyId", ""))
    variant = _humanize(form.get("variantType", ""))
    parts = [p for p in (family, variant) if p]
    if parts:
        return " ".join(parts)
    # Active forms keep their entity name available even when the family is a
    # placeholder; reserved forms have no entity, so name them by their slot.
    entity = form.get("entityId") or ""
    base = ENTITY_EN.get(entity.split(":")[-1])
    if base:
        return base
    number = form.get("sourceId") or form.get("number") or form.get("formId")
    return f"Reserved {number}".strip()


# ---------------------------------------------------------------------------
# Form display names (spec 11.13: golem_covenant.form.<formId>).
#
# The source table's `nameEn` column is empty for 155 of the 186 forms - it
# just repeats the Chinese `displayName`. Rather than hand-maintaining 186
# translations (which would rot the moment a form is added), the English name
# is COMPOSED from the entity id plus the variant tokens, both of which are
# small closed vocabularies. Anything unrecognised falls back to the entity
# name alone, so a new form still yields a sane name instead of Chinese text.
# ---------------------------------------------------------------------------

# entityId -> English entity name.
ENTITY_EN = {
    "axolotl": "Axolotl", "bat": "Bat", "bee": "Bee", "blaze": "Blaze",
    "camel": "Camel", "cat": "Cat", "cave_spider": "Cave Spider",
    "chicken": "Chicken", "cod": "Cod", "cow": "Cow",
    "creeper": "Creeper", "dolphin": "Dolphin", "donkey": "Donkey",
    "drowned": "Drowned", "elder_guardian": "Elder Guardian",
    "enderman": "Enderman", "endermite": "Endermite", "fox": "Fox",
    "frog": "Frog", "ghast": "Ghast", "glow_squid": "Glow Squid",
    "goat": "Goat", "guardian": "Guardian", "hoglin": "Hoglin",
    "horse": "Horse", "husk": "Husk", "iron_golem": "Iron Golem",
    "magma_cube": "Magma Cube", "mooshroom": "Mooshroom", "mule": "Mule",
    "ocelot": "Ocelot", "panda": "Panda", "parrot": "Parrot", "pig": "Pig",
    "piglin": "Piglin", "polar_bear": "Polar Bear", "pufferfish": "Pufferfish",
    "rabbit": "Rabbit", "salmon": "Salmon", "sheep": "Sheep",
    "shulker": "Shulker", "silverfish": "Silverfish", "skeleton": "Skeleton",
    "slime": "Slime", "snow_golem": "Snow Golem", "spider": "Spider",
    "squid": "Squid", "stray": "Stray", "strider": "Strider",
    "sulfur_cube": "Sulfur Cube", "tadpole": "Tadpole",
    "tropical_fish": "Tropical Fish", "turtle": "Turtle",
    "villager": "Villager", "warden": "Warden",
    "wither_skeleton": "Wither Skeleton", "wolf": "Wolf", "zombie": "Zombie",
    "zombie_villager": "Zombie Villager",
    "zombified_piglin": "Zombified Piglin",
}

# Chinese variant token -> English. Covers every token present in the table.
VARIANT_EN = {
    "普通": "", "成年": "Adult", "幼年": "Baby",
    # professions
    "农民": "Farmer", "渔夫": "Fisherman", "牧师": "Cleric",
    "牧羊人": "Shepherd", "制箭师": "Fletcher", "图书管理员": "Librarian",
    "制图师": "Cartographer", "武器匠": "Weaponsmith", "工具匠": "Toolsmith",
    "盔甲匠": "Armorsmith", "屠夫": "Butcher", "石匠": "Mason",
    "无业": "Unemployed",
    # colours
    "红色": "Red", "橘": "Orange", "蓝": "Blue", "绿": "Green",
    "灰": "Gray", "黑": "Black", "白": "White", "棕": "Brown",
    "棕色": "Brown", "红蓝": "Red & Blue", "青绿": "Cyan",
    "三花": "Calico", "暹罗": "Siamese", "布偶": "Ragdoll",
    "虎斑": "Tabby", "黑猫": "Black", "其他花色": "Other Coat",
    "不同毛色": "Assorted Coat", "不同颜色": "Assorted Colour",
    "五色/花色": "Five-Colour", "普通颜色": "Common Colour",
    "金色/特殊": "Golden / Special", "特殊羊毛色": "Special Wool",
    "彩色羊毛：红": "Red Wool", "彩色羊毛：绿": "Green Wool",
    "彩色羊毛：蓝": "Blue Wool",
    # sizes
    "大": "Large", "中": "Medium", "小": "Small",
    # biomes
    "森林": "Forest", "雪原": "Snowy", "黑森林": "Dark Forest",
    "沙地": "Desert", "沼泽": "Swamp", "丛林": "Jungle",
    "沙漠": "Desert", "雪狐": "Arctic", "夜雪变种": "Night Snow",
    "黑夜变种": "Night", "雪原变种": "Snowy", "沙漠变种": "Desert",
    "丛林变种": "Jungle", "温暖": "Warm", "温带": "Temperate",
    "寒冷": "Cold",
    # variants
    "高速变种": "Swift", "高跳变种": "Leaping", "高生命变种": "Vigorous",
    "高负载变种": "Burdened", "性格变种": "Personality", "稀有变种": "Rare",
    "尖叫": "Screaming", "杀手兔": "Killer", "闪电变种": "Charged",
    "特殊/毒态": "Venomous", "特殊Boss": "Boss", "多样花纹": "Patterned",
    "吸收冰块状态": "Ice-Absorbing", "吸收熔岩/热状态": "Lava-Absorbing",
    "吸收TNT：未点燃": "TNT-Absorbing",
    "吸收TNT：已点燃": "TNT-Primed",
    # already-English archetypes from the source table
    "Regular": "Regular", "Slow Bouncy": "Slow Bouncy", "Hot": "Hot",
    "Explosive": "Explosive",
}

# "幼年：森林" style composites, plus the "+" composites.
VARIANT_COMPOSITE_EN = {
    "幼年：森林": "Baby Forest", "幼年：雪原": "Baby Snowy",
    "温暖+幼年": "Warm Baby", "温带+幼年": "Temperate Baby",
    "寒冷+幼年": "Cold Baby",
}


def _form_name_en(form: dict) -> str:
    """Compose an English form name from entity id + variant tokens."""
    display = form.get("displayName") or ""

    # The reserved band (spec 11.2.3) has no entity and no variant: its only
    # identity is its slot in the 186 list, which lives in `sourceId`.
    if form.get("status") == "reserved":
        number = form.get("sourceId") or form.get("number") or ""
        return f"Reserved Form {number}".strip()

    entity = form.get("entityId", "")
    base = ENTITY_EN.get(entity.split(":")[-1])
    if base is None:
        # Unknown entity: derive from the id so the name is still English.
        base = _humanize(entity.split(":")[-1]) or form["formId"]

    if "·" not in display:
        return base

    parts = []
    for token in display.split("·")[1:]:
        english = VARIANT_COMPOSITE_EN.get(token)
        if english is None:
            english = VARIANT_EN.get(token, "")
        if english:
            parts.append(english)
    # A bare "普通" (common) yields no parts, which is the intent: the entity
    # name alone already means the common variant.
    name = " ".join([base] + parts)

    # A few forms are the same creature as another form but distinguished only
    # by an id suffix (cat_black vs cat_black_cat, axolotl_rare vs rare_2).
    # Their displayName is identical, so the name needs the id tail to stay
    # unambiguous in the tooltip and the /golem output.
    return _disambiguate(form, name)


def _disambiguate(form: dict, name: str) -> str:
    """Suffix a name when another form would produce the same string."""
    suffix = DISAMBIGUATE_EN.get(form.get("formId", ""))
    return f"{name} ({suffix})" if suffix else name


# formId -> suffix, for forms whose composed name would collide with a sibling.
# Detected by tools/validate_lang.py, which fails on duplicate en_us values.
DISAMBIGUATE_EN = {
    "animal_cat_black_cat": "Black Coat",
    "aquatic_axolotl_rare_2": "Golden",
    "zombie_zombie_villager_baby_2": "Variant",
}


def main() -> int:
    with open(REGISTRY, encoding="utf-8") as fh:
        reg = json.load(fh)

    os.makedirs(LANG_DIR, exist_ok=True)
    counts = {}
    for en, name in ((True, "en_us.json"), (False, "zh_cn.json")):
        data = build(en)
        # Per-form display names and the five ability sub-keys (spec 11.13).
        # The key shape is fixed by the spec:
        #   golem_covenant.form.<formId>.name / .b_active / .b_passive
        #                              / .c_second / .death_will / .ritual
        # zh_cn uses the source table's Chinese verbatim; en_us is COMPOSED,
        # because the table's nameEn column is not real English for 155 of 186
        # forms and no English exists at all for the ability names.
        for f in reg["forms"]:
            if en:
                # A form's B-active id is a slugged Chinese name, so it cannot
                # supply an English label. The anchor behaviour is a real
                # English id and describes what the form actually does, so it
                # stands in for the active ability's name.
                b_active_fallback = _humanize(f.get("anchorBehaviour", ""))
                values = {
                    "name": _form_name_en(f),
                    "b_active": _ability_en(f, "bActiveId", b_active_fallback),
                    "b_passive": _ability_en(f, "bPassiveId"),
                    "c_second": _ability_en(f, "cSecondId"),
                    "death_will": _ability_en(f, "deathWillId"),
                    "ritual": _ritual_en(f),
                }
            else:
                values = {
                    "name": f.get("displayName") or f["formId"],
                    "b_active": f.get("bActive") or "",
                    "b_passive": f.get("bPassiveName") or f.get("bPassive") or "",
                    "c_second": f.get("cSecond") or "",
                    "death_will": f.get("deathWill") or "",
                    "ritual": (f.get("ritual") or {}).get("symbol") or "",
                }
            for suffix, text in values.items():
                key = f"golem_covenant.form.{f['formId']}.{suffix}"
                # An empty value would render as a blank line in the tooltip and
                # would also trip validate_lang; omit the key instead so the
                # caller can detect it as absent.
                if text:
                    data[key] = text
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
