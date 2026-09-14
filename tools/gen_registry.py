#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Generates the authoritative forms registry for the Golem Covenant addon.

This is the "data driven production pipeline" required by spec 12.6:
    raw 186 matrix  ->  forms_registry.json  ->  Profile / Ability / Ritual / DeathWill

It implements the gap-fill requirements of chapter 11:
  11.2  single authoritative registry file, reserved tail (160..186 in the source
        numbering become status="reserved")
  11.3  canonical ID bands, family prefixes, duplicate merging
  11.4  quantified DeathWillProfile + uniqueness remediation (no two forms in the
        same group may share the same will on 2+ dimensions)
  11.5  24+ second-mechanism pool replacing the 5-item template
  11.6  bPassive column for every form (never a pure stat bonus)

Run:  python tools/gen_registry.py
"""
import json
import os
import re
import collections

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
SRC = os.path.join(ROOT, "..", "Golem_Covenant_186形态数据表.json")
OUT = os.path.join(ROOT, "src", "main", "resources", "data",
                   "golem_covenant", "forms_registry.json")

# ---------------------------------------------------------------------------
# 11.3 canonical ID bands. Every family maps to a band; forms inside a family
# are ordered (adult -> baby -> colour -> profession -> element -> size).
# ---------------------------------------------------------------------------
FAMILY_BAND = {
    "zombie": (1, 19),          # 亡灵系
    "arthropod": (20, 39),      # 节肢系
    "animal": (40, 69),         # 主世界动物
    "mount": (70, 89),          # 骑乘与驮运
    "snow": (90, 109),          # 雪原山地与稀有变种
    "environment": (110, 129),  # 环境生物
    "aquatic": (130, 149),      # 水生
    "construct": (150, 159),    # 构筑体
    "nether_end": (160, 179),   # 下界与末地深海
    "special": (180, 199),      # 爆炸系 / 村民职业 / 鹦鹉
    "reserved": (200, 299),     # 预留扩展（原 160~186 占位符）
}

# entityId -> (familyId, variantType) mapping for every real form.
# Derived from spec 11.3 + the 186 matrix, with duplicates merged.
ENTITY_MAP = {
    "僵尸": ("zombie", "adult"),
    "幼年僵尸": ("zombie", "baby"),
    "尸壳": ("zombie", "adult"),
    "幼年尸壳": ("zombie", "baby"),
    "溺尸": ("zombie", "adult"),
    "幼年溺尸": ("zombie", "baby"),
    "骷髅": ("zombie", "adult"),
    "流浪者": ("zombie", "adult"),
    "凋零骷髅": ("zombie", "adult"),
    "僵尸村民": ("zombie", "adult"),
    "幼年僵尸村民": ("zombie", "baby"),
    "猪灵": ("zombie", "adult"),
    "幼年猪灵": ("zombie", "baby"),
    "僵尸猪灵": ("zombie", "adult"),
    "幼年僵尸猪灵": ("zombie", "baby"),
    "蜘蛛": ("arthropod", "adult"),
    "洞穴蜘蛛": ("arthropod", "adult"),
    "幼年洞穴蜘蛛": ("arthropod", "baby"),
    "末影螨": ("arthropod", "adult"),
    "蠹虫": ("arthropod", "adult"),
    "蜜蜂": ("arthropod", "adult"),
    "毒态蜜蜂": ("arthropod", "element"),
    "牛": ("animal", "adult"),
    "红色哞菇": ("animal", "color"),
    "棕色哞菇": ("animal", "color"),
    "猪": ("animal", "adult"),
    "幼年猪": ("animal", "baby"),
    "羊": ("animal", "adult"),
    "幼年羊": ("animal", "baby"),
    "彩色羊": ("animal", "color"),
    "鸡": ("animal", "adult"),
    "幼年鸡": ("animal", "baby"),
    "兔子": ("animal", "adult"),
    "杀手兔": ("animal", "element"),
    "狐狸": ("animal", "color"),
    "雪狐": ("animal", "color"),
    "黑猫": ("animal", "color"),
    "花猫": ("animal", "color"),
    "豹猫": ("animal", "adult"),
    "狼": ("animal", "adult"),
    "毛色狼": ("animal", "color"),
    "幼年狼": ("animal", "baby"),
    "马": ("mount", "adult"),
    "彩色马": ("mount", "color"),
    "幼年马": ("mount", "baby"),
    "驴": ("mount", "adult"),
    "骡": ("mount", "adult"),
    "骆驼": ("mount", "adult"),
    "幼年骆驼": ("mount", "baby"),
    "熊猫": ("snow", "adult"),
    "性格熊猫": ("snow", "element"),
    "幼年熊猫": ("snow", "baby"),
    "北极熊": ("snow", "adult"),
    "幼年北极熊": ("snow", "baby"),
    "山羊": ("snow", "adult"),
    "尖叫山羊": ("snow", "element"),
    "青蛙": ("environment", "adult"),
    "幼年青蛙": ("environment", "baby"),
    "海龟": ("aquatic", "adult"),
    "幼年海龟": ("aquatic", "baby"),
    "蝌蚪": ("aquatic", "adult"),
    "蝙蝠": ("aquatic", "adult"),
    "鱿鱼": ("aquatic", "adult"),
    "发光鱿鱼": ("aquatic", "adult"),
    "海豚": ("aquatic", "adult"),
    "河豚": ("aquatic", "adult"),
    "鲑鱼": ("aquatic", "adult"),
    "鳕鱼": ("aquatic", "adult"),
    "热带鱼": ("aquatic", "color"),
    "美西螈": ("aquatic", "color"),
    "铁傀儡": ("construct", "adult"),
    "雪傀儡": ("construct", "adult"),
    "史莱姆": ("nether_end", "size"),
    "岩浆怪": ("nether_end", "size"),
    "烈焰人": ("nether_end", "adult"),
    "恶魂": ("nether_end", "adult"),
    "炽足兽": ("nether_end", "adult"),
    "幼年炽足兽": ("nether_end", "baby"),
    "疣猪兽": ("nether_end", "adult"),
    "幼年疣猪兽": ("nether_end", "baby"),
    "末影人": ("nether_end", "adult"),
    "潜影贝": ("nether_end", "adult"),
    "守卫者": ("nether_end", "adult"),
    "远古守卫者": ("nether_end", "adult"),
    "监守者": ("nether_end", "element"),
    "苦力怕": ("special", "adult"),
    "闪电苦力怕": ("special", "element"),
    "硫磺史莱姆": ("special", "archetype"),
    "村民": ("special", "profession"),
    "幼年村民": ("special", "baby"),
    "鹦鹉": ("special", "color"),
}

# 11.5 extended second-mechanism pool (24 entries). The original document only
# rotated 5 templates; each of these answers "how does it change the player's
# way of solving the fight?".
SECOND_MECHANICS = {
    "range_domain": ("范围领域", "在主人周围建立持续作用区域，区域内规则改写"),
    "emergency_return": ("紧急回援", "主人陷入危险时无视距离瞬间回防"),
    "sync_mark": ("协同标记", "与主人共享目标标记，双方对标记目标增伤"),
    "counter_shield": ("反击护盾", "承受攻击后转化为一层反击护盾"),
    "env_adapt": ("环境适应", "按所处生物群系/维度改写自身能力参数"),
    "chain": ("连锁", "同一目标连续命中后效果向邻近目标扩散"),
    "reflect": ("反射", "把受到的投射物伤害按比例反弹"),
    "projectile_deflect": ("投射物偏转", "改写来袭投射物的飞行方向"),
    "gravity_well": ("重力场", "在区域中心制造径向牵引，改变走位"),
    "time_dilation": ("时间减速", "区域内敌对目标的时间流速下降"),
    "energy_siphon": ("能量汲取", "从被标记目标的行动中抽取资源"),
    "terrain_rewrite": ("地形改造", "把地面临时改写为自身主题地形"),
    "summon_reinforce": ("召唤增援", "消耗累积资源召唤主题衍生物"),
    "state_transfer": ("状态转移", "把主人身上的负面状态转移到敌人身上"),
    "damage_share": ("伤害分摊", "把主人承受的伤害分摊到自身"),
    "aggro_shift": ("仇恨转移", "强制附近敌对目标把仇恨转向自身"),
    "vision_share": ("视野共享", "与主人共享侦测范围与实体高亮"),
    "resource_recycle": ("资源回收", "击杀后回收消耗品或耐久"),
    "position_swap": ("位移交换", "与主人或标记目标进行有限度换位"),
    "shield_break": ("护盾破解", "对带护盾目标造成额外穿透"),
    "element_infuse": ("元素附着", "攻击附加主题元素层数，叠满触发"),
    "stack_detonate": ("叠加引爆", "累积层数达到阈值后一次性释放"),
    "path_block": ("路径封锁", "在敌人路径上生成不可通行区域"),
    "area_disguise": ("区域伪装", "在区域内隐藏主人与自身的行踪"),
    "phantom_clone": ("幻影分身", "生成不造成伤害的诱饵分身吸引仇恨"),
    "life_link": ("生命链接", "自身与主人生命上限互相补充"),
    "ammo_boost": ("弹药强化", "强化主人与自身的投射物"),
    "pressure_build": ("压力积累", "持续承受压力后转化为爆发资源"),
    "threshold_burst": ("阈值爆发", "资源达到阈值时自动释放一次强效"),
}

# 11.4 quantified death-will profiles. Each entry is
#   (displayName, effectId, amplifier, durationTicks, cooldownTicks, triggerNote)
# durationTicks stay inside the spec's per-theme windows (120..600).
WILL_ARCHETYPES = {
    "damage_reduction": ("伤害减免", "resistance", 1, 200, 6000, "伙伴死亡后立即生效"),
    "move_speed": ("移速", "speed", 1, 240, 6000, "伙伴死亡后立即生效"),
    "knockback_resist": ("抗击退", "knockback_resist", 1, 300, 6000, "伙伴死亡后立即生效"),
    "regeneration": ("生命恢复", "regeneration", 1, 160, 6000, "伙伴死亡后立即生效"),
    "fire_resist": ("火焰抗性", "fire_resistance", 0, 600, 6000, "伙伴死亡后立即生效"),
    "cold_resist": ("寒冷抗性", "cold_resist", 0, 600, 6000, "伙伴死亡后立即生效"),
    "slow_resist": ("减速抗性", "slow_resist", 0, 400, 6000, "伙伴死亡后立即生效"),
    "water_breathing": ("水下呼吸", "water_breathing", 0, 600, 6000, "伙伴死亡后立即生效"),
    "water_power": ("水下能力", "water_power", 0, 500, 6000, "伙伴死亡后立即生效"),
    "night_vision": ("夜视", "night_vision", 0, 600, 6000, "伙伴死亡后立即生效"),
    "strength": ("攻击强化", "strength", 1, 260, 6000, "伙伴死亡后立即生效"),
    "ranged_boost": ("远程强化", "ranged_boost", 1, 280, 6000, "伙伴死亡后立即生效"),
    "defense": ("防御", "resistance", 1, 240, 6000, "伙伴死亡后立即生效"),
    "poison_resist": ("毒素抗性", "poison_resist", 0, 500, 6000, "伙伴死亡后立即生效"),
    "detection": ("侦测", "glowing_enemies", 0, 500, 6000, "伙伴死亡后立即生效"),
    "jump_boost": ("跳跃强化", "jump_boost", 1, 300, 6000, "伙伴死亡后立即生效"),
    "haste": ("灵活", "haste", 1, 300, 6000, "伙伴死亡后立即生效"),
    "mining_boost": ("采集辅助", "mining_boost", 0, 600, 6000, "伙伴死亡后立即生效"),
    "burst_shield": ("爆发防护", "absorb", 2, 120, 6000, "伙伴死亡后立即生效"),
    "exp_boost": ("经验辅助", "exp_boost", 0, 600, 6000, "伙伴死亡后立即生效"),
    "negative_resist": ("负面抗性", "negative_resist", 0, 500, 6000, "伙伴死亡后立即生效"),
    "melee_boost": ("近战强化", "melee_boost", 1, 260, 6000, "伙伴死亡后立即生效"),
    "tool_boost": ("工具效率", "tool_boost", 1, 400, 6000, "伙伴死亡后立即生效"),
    "true_immunity": ("高防御", "resistance", 2, 200, 6000, "伙伴死亡后立即生效"),
    "lightning_resist": ("雷抗", "lightning_resist", 0, 500, 6000, "伙伴死亡后立即生效"),
    "random_boon": ("随机增益", "random_boon", 0, 300, 6000, "伙伴死亡后立即生效"),
    "tracking": ("追踪", "tracking", 0, 400, 6000, "伙伴死亡后立即生效"),
    "emergency_blink": ("紧急位移保护", "blink_shield", 0, 1, 6000, "下一次致命伤害时触发"),
    "last_shot": ("追击", "last_shot", 0, 1, 6000, "下一次远程命中时触发"),
    "space_door": ("空间门", "space_door", 0, 1, 6000, "危险时返回最后安全位置"),
    "echo_bell": ("回声钟", "echo_ping", 0, 1, 6000, "受到偷袭时释放定位波"),
    "hive_shard": ("蜂巢残片", "hive_shard", 0, 1, 6000, "受到重击时释放保护花粉"),
    "last_mark": ("护主标记", "absorb", 1, 1, 6000, "下一次受击时替主人承受"),
    "last_web": ("最后织命网", "web_burst", 0, 1, 6000, "被包围时自动展开"),
}

# raw death-will text -> will archetype key (11.4 uniqueness remediation).
WILL_TEXT_MAP = {
    "主人获得短时减伤": "damage_reduction",
    "主人短时减伤": "damage_reduction",
    "主人获得短时移速": "move_speed",
    "主人短时移速": "move_speed",
    "主人获得短时抗击退": "knockback_resist",
    "主人短时抗击退": "knockback_resist",
    "主人短时恢复": "regeneration",
    "主人短时生命恢复": "regeneration",
    "主人获得短时恢复": "regeneration",
    "主人短时火抗": "fire_resist",
    "主人短时抗火": "fire_resist",
    "主人短时寒冷抗性": "cold_resist",
    "主人短时寒抗": "cold_resist",
    "主人短时减速抗性": "slow_resist",
    "主人短时水下呼吸": "water_breathing",
    "主人获得短时水下呼吸": "water_breathing",
    "主人短时水下能力": "water_power",
    "主人获得短时水下能力": "water_power",
    "主人短时水下防护": "water_power",
    "主人短时水下抗性": "water_power",
    "主人短时水速": "water_power",
    "主人短时夜视": "night_vision",
    "主人短时攻击强化": "strength",
    "主人短时攻击": "strength",
    "主人短时远程强化": "ranged_boost",
    "主人短时防御": "defense",
    "主人短时高防御": "true_immunity",
    "主人短时毒抗": "poison_resist",
    "主人短时侦测": "detection",
    "主人短时跳跃强化": "jump_boost",
    "主人短时灵活": "haste",
    "主人短时采集辅助": "mining_boost",
    "主人短时爆发防护": "burst_shield",
    "主人短时经验辅助": "exp_boost",
    "主人短时负面抗性": "negative_resist",
    "主人短时近战强化": "melee_boost",
    "主人短时工具效率": "tool_boost",
    "主人短时抗性": "negative_resist",
    "主人获得一次紧急位移保护": "emergency_blink",
    "主人随机小型辅助": "random_boon",
    "主人短时随机小增益": "random_boon",
    "主人短时雷抗/攻击强化": "lightning_resist",
    "主人短时侦察": "tracking",
    "主人短时追踪": "tracking",
    "对应主题短时抗性": "negative_resist",
    "对应主题短时增益": "random_boon",
    "对应性格短时增益": "random_boon",
    "对应环境短时增益": "random_boon",
}

# ---------------------------------------------------------------------------
# 11.6 B-passive pool. Explicitly NOT pure stat bonuses: every entry is either
# conditional, environment-interactive or resource-based.
# ---------------------------------------------------------------------------
PASSIVE_BY_KEYWORD = [
    (("幼",), "b_passive_baby", "幼生感知", "主人远离超过 8 格时自动切换为跟随优先，不再主动追敌"),
    (("末影", "空间", "影"), "b_passive_space", "空间残响", "每记录一次主人安全位置，下一次传送冷却缩短"),
    (("蜘蛛", "蛛", "织命"), "b_passive_web", "丝线积累", "雨后或潮湿环境下丝线生成速度提升"),
    (("骷髅", "骨", "弦", "矢"), "b_passive_bone", "弹道校准", "连续命中同一目标后，下一箭自动修正落点"),
    (("苦力怕", "爆", "硫"), "b_passive_boom", "压力缓冲", "生命值降低后停止蓄能，改为只释放冲击"),
    (("侦察", "狐狸", "狐", "夜"), "b_passive_scout", "夜行直觉", "夜间侦测范围扩大，并标记视野内敌对目标"),
    (("蜂", "巢"), "b_passive_hive", "花粉循环", "在花丛附近停留后恢复自身能力冷却"),
    (("监守", "回声", "回响"), "b_passive_echo", "声纹记忆", "记录近期声音事件来源并在主 HUD 上残留标记"),
    (("狼", "猎", "狩"), "b_passive_hunt", "猎痕延续", "与主人共同攻击同一目标后，追击期间移速提升"),
    (("守", "城", "铁傀儡"), "b_passive_guard", "守望姿态", "原地静止时逐步扩大警戒范围"),
    (("村民", "收割", "职业"), "b_passive_trade", "职业本能", "与主人同处村庄时能力冷却加快"),
    (("海", "水", "潮", "鱼", "龟"), "b_passive_tide", "潮汐呼吸", "水下停留时不再消耗能力冷却"),
    (("青蛙", "蝌蚪", "群系"), "b_passive_biome", "群系共鸣", "所处生物群系与自身主题一致时机制强化"),
    (("熊猫", "北极熊", "山羊", "雪"), "b_passive_alpine", "高地适应", "非平坦地形上跟随更稳定，不中断能力"),
    (("马", "驴", "骡", "骆驼"), "b_passive_mount", "负重节奏", "长时间移动后为下一次冲刺储能"),
    (("猪", "牛", "羊", "鸡", "兔"), "b_passive_herd", "群体本能", "附近存在同类伙伴时触发专属机制"),
    (("鹦鹉", "羽"), "b_passive_plume", "羽色伪装", "在对应颜色环境中降低被敌对目标锁定概率"),
]

DEFAULT_PASSIVE = ("b_passive_default", "灵魂余韵",
                   "主人受到攻击后自身下一次机制触发时间缩短")

# ritual geometry by family (5.7 RitualProfile)
GEOMETRY = {
    "zombie": "tomb_ring",
    "arthropod": "web_hex",
    "animal": "beast_ring",
    "mount": "hoof_star",
    "snow": "alpine_arc",
    "environment": "biome_orb",
    "aquatic": "tide_triple",
    "construct": "pillar_square",
    "nether_end": "void_spiral",
    "special": "implosion_core",
}

PARTICLES = {
    "zombie": "golem_covenant:soul_ash",
    "arthropod": "golem_covenant:silk_strand",
    "animal": "golem_covenant:beast_ember",
    "mount": "golem_covenant:hoof_dust",
    "snow": "golem_covenant:frost_mote",
    "environment": "golem_covenant:biome_spore",
    "aquatic": "golem_covenant:tide_drop",
    "construct": "golem_covenant:iron_spark",
    "nether_end": "golem_covenant:void_shard",
    "special": "golem_covenant:detonation_glyph",
}

COLORS = {
    "zombie": "#3E6B4A",
    "arthropod": "#8C8C8C",
    "animal": "#C49A6C",
    "mount": "#A97C50",
    "snow": "#BFE3F5",
    "environment": "#6FA96F",
    "aquatic": "#3F7FD1",
    "construct": "#B8B8B8",
    "nether_end": "#7A3FB5",
    "special": "#C2542A",
}

ANCHORS = {
    "zombie": ["death", "grave", "desiccation", "tide", "bone_arrow", "frost_arrow",
               "wither_blade", "village_cycle", "gold_contract", "gold_flame"],
    "arthropod": ["web_of_fate", "venom_nest", "shadow_mite", "burrow_swarm", "hive"],
    "animal": ["charge_horn", "spore", "tusk", "wool", "plume_dance", "moon_leap",
               "night_prowl", "nine_lives", "jungle_stalk", "hunt", "steady_hoof"],
    "mount": ["charge_horn", "steady_hoof", "burden", "sand_march", "sky_dash"],
    "snow": ["rock_roll", "bamboo", "arctic_fang", "mountain_horn", "snow_prowl"],
    "environment": ["biome_orb", "warm_spring", "grass_echo", "frost_leap"],
    "aquatic": ["tide_shell", "echo_sense", "deep_ink", "current_dash", "spine_guard",
                "school_charge", "coral_scale", "regeneration_gill"],
    "construct": ["city_wall", "snowball", "guardian_watch"],
    "nether_end": ["elastic", "magma_core", "sun_flare", "wail", "lava_stride",
                   "crimson_tusk", "space", "shell", "prism", "deep_echo"],
    "special": ["implosion", "sulfur_core", "profession", "plume_echo"],
}

# ---------------------------------------------------------------------------
# Builders
# ---------------------------------------------------------------------------


# 11.4 step 2 - uniqueness remediation.
# The source matrix reuses 24 death-will strings across 138 of 186 rows. The
# spec demands that inside a group no two forms may share the same will on 2+
# dimensions. The dimension we vary deterministically is (amplifier,
# durationTicks, trigger phrasing); the effect family is preserved so the will
# still reads as belonging to that creature.
WILL_VARIANTS = {}


def resolve_will(will_key, group_counter, used_signatures, group_signatures,
                 group_all=None):
    """Pick a will variant whose signature is unique across the whole registry.

    Spec 15.5.11 requires that, inside a group, two forms differ on >= 2 of
    {effect family, amplifier, duration, trigger}.

    Collisions can be cross-family too (a night-vision will and a cold-resist
    will that happen to share amplifier+duration+trigger only differ on ONE
    dimension). `group_all` therefore carries every (will, amp, dur, trig)
    already handed out in this group, regardless of family, and is used for the
    >= 2 dimension test.
    """
    variants = WILL_VARIANTS.get(will_key) or []
    if not variants:
        variants = [(WILL_ARCHETYPES[will_key][2], WILL_ARCHETYPES[will_key][3],
                     WILL_ARCHETYPES[will_key][5])]
        WILL_VARIANTS[will_key] = variants

    siblings = (group_all if group_all is not None
                else group_signatures.get(will_key, []))

    def score(v):
        """Dimensions differing from the closest previously used signature.

        Layout: {effect family, amplifier, duration, trigger}. A candidate v is
        compared against every signature already used in the group; the
        worst-case (minimum) difference is what matters.
        """
        if not siblings:
            return 4
        worst = 4
        for s in siblings:
            s_will, s_amp, s_dur, s_trig = s
            d = 1 if s_will != will_key else 0
            d += 1 if s_amp != v[0] else 0
            d += 1 if s_dur != v[1] else 0
            d += 1 if s_trig != v[2] else 0
            if d < worst:
                worst = d
            if worst <= 1:
                break
        return worst

    candidates = [v for v in variants
                  if (will_key, v[0], v[1], v[2]) not in used_signatures]
    if not candidates:
        return None
    candidates.sort(key=score, reverse=True)
    best = candidates[0]
    if score(best) < 2:
        return None

    sig = (will_key, best[0], best[1], best[2])
    used_signatures.add(sig)
    if group_all is not None:
        group_all.append(sig)
    else:
        group_signatures.setdefault(will_key, []).append(best)
    name, effect, _a, _d, cd, _t = WILL_ARCHETYPES[will_key]
    return name, effect, best[0], best[1], cd, best[2]


# Extra trigger phrasings used to synthesise additional variants for will
# families whose source data is very repetitive (e.g. "主人短时移速" appears 32
# times). Combined with the amplitude/duration ladder below this yields enough
# distinct profiles that no family group can exhaust the pool.
EXTRA_TRIGGERS = [
    "伙伴死亡后立即生效",
    "伙伴死亡后脱战才生效",
    "伙伴死亡后首次受击时触发",
    "伙伴死亡后首次命中时触发",
    "伙伴死亡后生命低于一半时触发",
    "伙伴死亡后击杀目标时触发",
    "伙伴死亡后进入危险地形时触发",
    "伙伴死亡后持续站立时触发",
    "伙伴死亡后移动超过 10 格后生效",
    "伙伴死亡后受到重击时触发",
]
DURATION_LADDER = [120, 160, 200, 240, 280, 320, 400, 480, 600]


def ensure_variant_capacity():
    """Guarantee every will archetype has a generous variant pool.

    A family group may legitimately contain ~20 forms sharing one thematic will
    ("主人短时移速" appears 32 times in the source matrix), so each pool is
    filled in product order over (amplifier x duration x trigger) until it
    holds at least 40 distinct signatures. Because the allocator also enforces
    global uniqueness, this guarantees termination for the real data set.
    """
    for key, base in list(WILL_ARCHETYPES.items()):
        variants = WILL_VARIANTS.setdefault(key, [])
        amp0, dur0, trig0 = base[2], base[3], base[5]
        if not variants:
            variants.append((amp0, dur0, trig0))
        seen = set(variants)
        # Build the pool so that consecutive entries differ on BOTH the
        # duration and the trigger phrasing, which lets the allocator satisfy
        # the ">= 2 dimensions" rule for arbitrarily long runs in one group.
        guard = 0
        ti = 0
        di = 0
        while len(variants) < 80 and guard < 50000:
            guard += 1
            trig = EXTRA_TRIGGERS[ti % len(EXTRA_TRIGGERS)]
            dur = DURATION_LADDER[di % len(DURATION_LADDER)]
            prev = variants[-1]
            if trig == prev[2] or dur == prev[1]:
                # advance both cursors until duration AND trigger both change
                ti += 1
                di += 1
                continue
            amp = (len(variants) * 3) % 4
            cand = (amp, dur, trig)
            if cand in seen:
                ti += 1
                di += 1
                continue
            seen.add(cand)
            variants.append(cand)
            ti += 1
            di += 1


def family_of(entity_name, variant):
    """Resolve familyId + variantType from the human readable entity name."""
    key = entity_name
    if variant in ("幼年",) and not key.startswith("幼"):
        key = "幼年" + key

    # explicit special-cases first
    specials = {
        ("僵尸村民", "农民"): ("zombie", "profession"),
        ("僵尸村民", "渔夫"): ("zombie", "profession"),
        ("僵尸村民", "牧师"): ("zombie", "profession"),
        ("僵尸村民", "幼年"): ("zombie", "baby"),
        ("羊", "彩色羊毛：红"): ("animal", "color"),
        ("羊", "彩色羊毛：蓝"): ("animal", "color"),
        ("羊", "彩色羊毛：绿"): ("animal", "color"),
        ("狼", "幼年：森林"): ("animal", "baby"),
        ("狼", "幼年：雪原"): ("animal", "baby"),
        ("狼", "森林"): ("animal", "color"),
        ("狼", "雪原"): ("animal", "color"),
        ("狼", "黑森林"): ("animal", "color"),
        ("狼", "沙地"): ("animal", "color"),
        ("狼", "沼泽"): ("animal", "color"),
        ("美西螈", "金色/特殊"): ("aquatic", "element"),
        ("金色美西螈", "稀有变种"): ("aquatic", "element"),
        ("蓝色美西螈", "稀有变种"): ("aquatic", "element"),
        ("硫磺史莱姆", "Regular"): ("special", "archetype"),
        ("硫磺史莱姆", "Slow Bouncy"): ("special", "archetype"),
        ("硫磺史莱姆", "Hot"): ("special", "archetype"),
        ("硫磺史莱姆", "Explosive"): ("special", "archetype"),
        ("硫磺史莱姆", "吸收冰块状态"): ("special", "element"),
        ("硫磺史莱姆", "吸收熔岩/热状态"): ("special", "element"),
        ("硫磺史莱姆", "吸收TNT：未点燃"): ("special", "element"),
        ("硫磺史莱姆", "吸收TNT：已点燃"): ("special", "element"),
        ("雪狐", "夜雪变种"): ("snow", "color"),
        ("黑猫", "黑夜变种"): ("snow", "color"),
        ("白兔", "雪原变种"): ("snow", "color"),
        ("沙漠兔", "沙漠变种"): ("snow", "color"),
        ("丛林兔", "丛林变种"): ("snow", "color"),
        ("青蛙", "温暖+幼年"): ("environment", "baby"),
        ("青蛙", "温带+幼年"): ("environment", "baby"),
        ("青蛙", "寒冷+幼年"): ("environment", "baby"),
        ("马", "高跳变种"): ("mount", "element"),
        ("马", "高生命变种"): ("mount", "element"),
        ("驴", "高负载变种"): ("mount", "element"),
        ("骡", "高速变种"): ("mount", "element"),
        ("骆驼", "高速变种"): ("mount", "element"),
        ("猫", "黑"): ("animal", "color"),
        ("猫", "白"): ("animal", "color"),
        ("猫", "橘"): ("animal", "color"),
        ("猫", "暹罗"): ("animal", "color"),
        ("猫", "虎斑"): ("animal", "color"),
        ("猫", "布偶"): ("animal", "color"),
        ("猫", "三花"): ("animal", "color"),
        ("猫", "黑猫"): ("animal", "color"),
        ("猫", "其他花色"): ("animal", "color"),
        ("兔子", "普通"): ("animal", "adult"),
        ("兔子", "杀手兔"): ("animal", "element"),
        ("熊猫", "性格变种"): ("snow", "element"),
        ("山羊", "尖叫"): ("snow", "element"),
        ("狼", "不同毛色"): ("animal", "color"),
        ("马", "不同颜色"): ("mount", "color"),
        ("鹦鹉", "五色/花色"): ("special", "color"),
    }
    if (entity_name, variant) in specials:
        return specials[(entity_name, variant)]

    base = ENTITY_MAP.get(entity_name)
    if base is None:
        # infant naming used by the matrix
        for k, v in ENTITY_MAP.items():
            if k in entity_name or entity_name in k:
                base = v
                break
    if base is None:
        base = ("reserved", "reserved")
    fam, vtype = base
    if variant == "幼年" or entity_name.startswith("幼年"):
        vtype = "baby"
    return fam, vtype


# Chinese variant label -> short ASCII key used inside formIds.
VARIANT_KEYS = {
    "成年": "adult", "幼年": "baby", "普通": "base", "幼年：森林": "baby_forest",
    "幼年：雪原": "baby_snow", "不同毛色": "coat", "不同颜色": "coat",
    "彩色羊毛：红": "wool_red", "彩色羊毛：蓝": "wool_blue",
    "彩色羊毛：绿": "wool_green",
    "森林": "forest", "雪原": "snow", "黑森林": "dark_forest",
    "沙地": "desert", "沼泽": "swamp",
    "红色": "red", "棕色": "brown", "雪狐": "snow", "夜雪变种": "night_snow",
    "黑夜变种": "night", "雪原变种": "snow", "沙漠变种": "desert",
    "丛林变种": "jungle", "杀手兔": "killer",
    "性格变种": "personality", "尖叫": "screaming",
    "高跳变种": "jump", "高生命变种": "health", "高负载变种": "load",
    "高速变种": "speed", "金色/特殊": "golden", "稀有变种": "rare",
    "毒态": "venom", "温暖": "warm", "温带": "temperate", "寒冷": "cold",
    "温暖+幼年": "baby_warm", "温带+幼年": "baby_temperate",
    "寒冷+幼年": "baby_cold",
    "大": "large", "中": "medium", "小": "small",
    "黑猫": "black_cat", "其他花色": "patterned",
    "黑": "black", "白": "white", "橘": "orange", "暹罗": "siamese",
    "虎斑": "tabby", "布偶": "ragdoll", "三花": "calico",
    "Regular": "regular", "Slow Bouncy": "slow_bouncy", "Hot": "hot",
    "Explosive": "explosive",
    "吸收冰块状态": "ice", "吸收熔岩/热状态": "magma",
    "吸收TNT：未点燃": "tnt", "吸收TNT：已点燃": "tnt_lit",
    "五色/花色": "multi", "红蓝": "red_blue", "青绿": "teal",
    "蓝": "blue", "灰": "gray", "绿": "green",
    "农民": "farmer", "渔夫": "fisherman", "牧羊人": "shepherd",
    "制箭师": "fletcher", "图书管理员": "librarian", "制图师": "cartographer",
    "牧师": "cleric", "武器匠": "weaponsmith", "工具匠": "toolsmith",
    "盔甲匠": "armorer", "屠夫": "butcher", "石匠": "mason", "无业": "unemployed",
}


def slug(text):
    """ASCII slug for a Chinese display name (deterministic + readable).

    Each CJK codepoint becomes ``u<hex>`` so the result is stable across runs
    and always a legal identifier segment. ASCII alphanumerics pass through.
    """
    table = []
    for ch in text:
        if ch.isascii() and ch.isalnum():
            table.append(ch.lower())
        elif ch.isascii():
            table.append("_")
        else:
            table.append("u%04x" % ord(ch))
    s = re.sub(r"_+", "_", "".join(table)).strip("_")
    return s or "unknown"


# Chinese entity name -> vanilla entity registry path. Used to emit a real
# ``minecraft:`` id so GolemizationCompat can map a form onto a live entity
# (spec 11.1.1.4: the base mod reuses vanilla entity types).
ENTITY_IDS = {
    "僵尸": "zombie", "尸壳": "husk", "溺尸": "drowned", "骷髅": "skeleton",
    "流浪者": "stray", "凋零骷髅": "wither_skeleton", "僵尸村民": "zombie_villager",
    "猪灵": "piglin", "僵尸猪灵": "zombified_piglin",
    "蜘蛛": "spider", "洞穴蜘蛛": "cave_spider", "末影螨": "endermite",
    "蠹虫": "silverfish", "蜜蜂": "bee",
    "牛": "cow", "哞菇": "mooshroom", "猪": "pig", "羊": "sheep", "鸡": "chicken",
    "兔子": "rabbit", "狐狸": "fox", "雪狐": "fox", "猫": "cat", "黑猫": "cat",
    "豹猫": "ocelot", "狼": "wolf",
    "马": "horse", "驴": "donkey", "骡": "mule", "骆驼": "camel",
    "熊猫": "panda", "北极熊": "polar_bear", "山羊": "goat",
    "白兔": "rabbit", "沙漠兔": "rabbit", "丛林兔": "rabbit",
    "青蛙": "frog", "蝌蚪": "tadpole",
    "海龟": "turtle", "蝙蝠": "bat", "鱿鱼": "squid",
    "发光鱿鱼": "glow_squid", "海豚": "dolphin", "河豚": "pufferfish",
    "鲑鱼": "salmon", "鳕鱼": "cod", "热带鱼": "tropical_fish",
    "美西螈": "axolotl", "金色美西螈": "axolotl", "蓝色美西螈": "axolotl",
    "铁傀儡": "iron_golem", "雪傀儡": "snow_golem",
    "史莱姆": "slime", "岩浆怪": "magma_cube", "烈焰人": "blaze",
    "恶魂": "ghast", "炽足兽": "strider", "疣猪兽": "hoglin",
    "末影人": "enderman", "潜影贝": "shulker", "守卫者": "guardian",
    "远古守卫者": "elder_guardian", "监守者": "warden",
    "苦力怕": "creeper", "闪电苦力怕": "creeper", "硫磺史莱姆": "sulfur_cube",
    "村民": "villager", "鹦鹉": "parrot",
}


def passive_for(entity_name, variant):
    blob = entity_name + variant
    for keys, pid, name, desc in PASSIVE_BY_KEYWORD:
        for k in keys:
            if k in blob:
                return pid, name, desc
    return DEFAULT_PASSIVE


def build():
    ensure_variant_capacity()
    with open(SRC, encoding="utf-8") as fh:
        raw = json.load(fh)["forms"]

    # counter per family band
    cursor = {k: v[0] for k, v in FAMILY_BAND.items()}
    used_ids = set()
    forms = []
    reserved_index = 0
    # 11.4 per (family,variantType) will-variant allocator
    will_group_counter = collections.defaultdict(dict)
    group_members = collections.defaultdict(list)
    used_signatures = set()
    # per-group, per-will-family list of already-allocated signatures
    group_signatures = collections.defaultdict(dict)

    for row in raw:
        src_id, entity, variant, a_desc, b_active, c_blob, will_text, ritual = row
        if entity.startswith("扩展形态"):
            # 11.2.3 placeholder tail -> reserved, not part of phase one
            reserved_index += 1
            rid = f"reserved_{reserved_index:03d}"
            forms.append({
                "formId": rid,
                "sourceId": src_id,
                "entityId": "golem_covenant:reserved",
                "familyId": "reserved",
                "variantType": "reserved",
                "displayName": entity,
                "nameEn": f"Reserved Form {reserved_index}",
                "status": "reserved",
                "aDesc": a_desc,
                "bActive": b_active,
                "bActiveId": f"reserved_active_{reserved_index}",
                "bPassive": "reserved",
                "bPassiveId": "reserved",
                "anchorId": "reserved",
                "cUpgrade": c_blob,
                "cSecond": "reserved",
                "cSecondId": "reserved",
                "deathWill": will_text,
                "deathWillId": "reserved",
                "ritualTheme": ritual,
                "ritual": {
                    "geometry": "reserved", "particleType": "minecraft:end_rod",
                    "particleColor": "#FFFFFF", "rotation": "cw", "radius": 1.0,
                    "duration": 0, "coreShape": "none", "symbol": ritual,
                    "soundPattern": "none", "layerCount": 0,
                },
                "tier": "reserved",
                "soulLoad": 0,
                "registryIndex": int(src_id),
            })
            continue

        fam, vtype = family_of(entity, variant)
        lo, hi = FAMILY_BAND[fam]
        # advance cursor until we find a free slot
        nid = cursor[fam]
        while nid in used_ids and nid <= hi:
            nid += 1
        if nid > hi:
            fam = "reserved"
            lo, hi = FAMILY_BAND["reserved"]
            nid = cursor["reserved"]
            while nid in used_ids:
                nid += 1
        cursor[fam] = nid + 1
        used_ids.add(nid)
        # spec 11.2.1 asked for readable ids such as ``formzombie_adult``; we
        # use ``<family>_<entityKey>_<variantKey>`` which is the same idea and
        # keeps familyId shareable across code paths (spec 11.3.2).
        entity_key = ENTITY_IDS.get(entity, slug(entity))
        variant_key = VARIANT_KEYS.get(variant, slug(variant))
        form_id = f"{fam}_{entity_key}_{variant_key}"
        # guarantee unique formIds even for identical entity+variant pairs
        base_fid = form_id
        suffix = 2
        while any(x["formId"] == form_id for x in forms):
            form_id = f"{base_fid}_{suffix}"
            suffix += 1

        # 11.5 second mechanism: pick from the pool, stable hash by formId
        pool = sorted(SECOND_MECHANICS.keys())
        idx = (nid * 7 + len(fam) * 3) % len(pool)
        second_key = pool[idx]
        second_name, second_desc = SECOND_MECHANICS[second_key]

        # 11.4 death will
        will_key = WILL_TEXT_MAP.get(will_text)
        if will_key is None:
            # derive from text keywords
            for t, k in WILL_TEXT_MAP.items():
                if t in will_text or will_text in t:
                    will_key = k
                    break
        if will_key not in WILL_ARCHETYPES:
            will_key = "random_boon"
        # 11.4 / 15.5.11 - allocate a globally unique will signature. Prefer the
        # thematically correct will; if its pool is exhausted, fall back to any
        # other archetype that still has a free signature.
        gkey = f"{fam}|{vtype}"
        candidates = [will_key] + [k for k in sorted(WILL_ARCHETYPES)
                                   if k != will_key]
        resolved = None
        # live list: mutated by resolve_will's caller below as signatures are
        # handed out, so every candidate is tested against the current group
        all_sigs = group_signatures.setdefault(gkey, [])
        for cand in candidates:
            resolved = resolve_will(cand, will_group_counter[gkey],
                                    used_signatures, None, all_sigs)
            if resolved is not None:
                will_key = cand
                break
        if resolved is None:
            fail_hard = f"will pool exhausted for {gkey}"
            raise RuntimeError(fail_hard)
        wname, effect, amp, dur, cd, trig = resolved
        group_members[gkey].append((will_key, amp, dur, trig))

        pid, pname, pdesc = passive_for(entity, variant)
        anchor_pool = ANCHORS.get(fam, ["reserved"])
        anchor = f"{fam}_{anchor_pool[(nid - 1) % len(anchor_pool)]}"

        geometry = GEOMETRY.get(fam, "beast_ring")
        particle = PARTICLES.get(fam, "golem_covenant:soul_ash")
        color = COLORS.get(fam, "#FFFFFF")

        # ritual durations from spec 5.2 (authoritative v3.0 ranges)
        b_dur = 30 + (nid % 26)          # 1.5s .. 2.8s
        c_dur = 70 + (nid % 71)          # 3.5s .. 7.0s
        layer = 2 if vtype != "baby" else 1

        # 11.5 / 15.5.10 - the source document reuses a few B-mechanism names
        # across forms (e.g. 爆核圣契 appears on both creeper and explosive
        # sulfur cube; 黄金再生 on axolotl and golden axolotl). Disambiguate
        # by appending the variant so bActiveId stays unique while the display
        # name keeps the thematic wording.
        b_active_id = f"{fam}_{slug(b_active)}"
        if sum(1 for x in forms
               if x.get("bActiveId") == b_active_id) > 0:
            b_active_id = f"{b_active_id}_{slug(variant)}"
        suffix_b = 2
        while any(x.get("bActiveId") == b_active_id for x in forms):
            b_active_id = f"{fam}_{slug(b_active)}_{slug(variant)}_{suffix_b}"
            suffix_b += 1

        forms.append({
            "formId": form_id,
            "sourceId": src_id,
            "entityId": f"minecraft:{ENTITY_IDS.get(entity, slug(entity))}",
            "familyId": fam,
            "variantType": vtype,
            "displayName": f"{entity}·{variant}",
            "nameEn": f"{slug(entity)} {variant}",
            "status": "active",
            "aDesc": a_desc,
            "bActive": b_active,
            "bActiveId": b_active_id,
            "bPassive": pdesc,
            "bPassiveId": pid,
            "bPassiveName": pname,
            "anchorId": anchor,
            "cUpgrade": c_blob,
            "cSecond": second_name,
            "cSecondId": second_key,
            "cSecondDesc": second_desc,
            "deathWill": wname,
            "deathWillRaw": will_text,
            "deathWillId": will_key,
            "deathWillProfile": {
                "effect": effect,
                "amplifier": amp,
                "durationTicks": dur,
                "cooldownTicks": cd,
                "once": True,
                "refreshOnly": True,
                "ownerOnly": True,
                "triggerCondition": trig,
            },
            "ritualTheme": ritual,
            "ritual": {
                "geometry": geometry,
                "particleType": particle,
                "particleColor": color,
                "rotation": "cw" if nid % 2 == 0 else "ccw",
                "radius": 2.0 if vtype != "baby" else 1.2,
                "durationB": b_dur,
                "durationC": c_dur,
                "coreShape": "hexagram" if fam == "arthropod" else "core_orb",
                "symbol": ritual,
                "soundPattern": f"{fam}_ritual_{nid % 4}",
                "layerCount": layer,
            },
            "tier": {"a": "borrow", "b": "awaken", "c": "covenant"},
            "soulLoad": {"a": 1, "b": 3, "c": 8},
            "registryIndex": int(src_id),
            "number": nid,
        })

    forms.sort(key=lambda x: (x["familyId"] == "reserved", x.get("number", 9999)))
    out = {
        "registryVersion": 1,
        "dataVersion": 1,
        "generatedFrom": "Golem_Covenant_186形态数据表.json + 最终需求规格书 第11章补齐",
        "total": len(forms),
        "active": sum(1 for f in forms if f["status"] == "active"),
        "reserved": sum(1 for f in forms if f["status"] == "reserved"),
        "secondMechanicPool": {k: {"name": v[0], "desc": v[1]}
                               for k, v in SECOND_MECHANICS.items()},
        "forms": forms,
    }
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with open(OUT, "w", encoding="utf-8") as fh:
        json.dump(out, fh, ensure_ascii=False, indent=1)
    print(f"wrote {OUT}")
    print(f"  total={out['total']} active={out['active']} reserved={out['reserved']}")
    return out


def report(out):
    active = [f for f in out["forms"] if f["status"] == "active"]
    print("\n--- id bands ---")
    band = collections.Counter(f["familyId"] for f in active)
    for k, v in band.most_common():
        print(f"  {k:12s} {v}")
    dup_will = collections.Counter(f["deathWillId"] for f in active)
    print("\n--- death will usage ---")
    for k, v in dup_will.most_common(12):
        print(f"  {v:3d}  {k}")
    print("\n--- second mechanism usage ---")
    sm = collections.Counter(f["cSecondId"] for f in active)
    print(f"  distinct={len(sm)}")
    for k, v in sm.most_common(10):
        print(f"  {v:3d}  {k}")
    print("\n--- passive usage ---")
    ps = collections.Counter(f["bPassiveId"] for f in active)
    print(f"  distinct={len(ps)}")
    fam_anchor = collections.defaultdict(set)
    for f in active:
        fam_anchor[f["familyId"]].add(f["anchorId"])
    print("\n--- distinct anchors per family ---")
    for k, v in fam_anchor.items():
        print(f"  {k:12s} {len(v)}")


if __name__ == "__main__":
    o = build()
    report(o)
