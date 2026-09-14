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
    # these blocks already carry their full sub-path, so prefix once
    for k, v in BOND_MSG.items():
        out[f"golem_covenant.{k}"] = v[0] if en else v[1]
    for k, v in RESONANCE.items():
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
        out[f"golem_covenant.behaviour.{bh}"] = v.get("name") or _humanize(bh)
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
