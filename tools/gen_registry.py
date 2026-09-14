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

# ---------------------------------------------------------------------------
# 第六章 灵魂锚点系统 (spec ch.6)
#
# 6.1: an anchor is NOT a buff / attribute / weapon / single skill. It is the
#      RULE by which a soul observes the world and joins a fight.
# 6.2 铁律 ("iron law"): every anchor must change at least THREE of the nine
#      dimensions below - a mere stat tweak is a spec violation.
# 12.1: forms must be `Profile + Ability + Anchor + Ritual + DeathWill`; there
#      must never be a ZombieCompanion.java / SpiderCompanion.java.
#
# Layout: (watch, source, trigger, combat, ai, zone, interact, behaviour)
#   watch   1. 观察对象     - what the anchor reads from the world
#   source  2. 资源来源     - what resource the reading accumulates into
#   trigger 3. 触发条件     - what makes the mechanism fire
#   combat  4. 战斗方式     - how it fights (never a damage number)
#   ai      5. AI 决策      - how targeting / pathing changes
#   zone    6. 区域规则     - what spatial rule it imposes
#   interact 7. 玩家互动    - how the player participates
#   behaviour - runtime dispatch keyword, consumed by AnchorRuntime
#
# 8. 死亡遗志 and 9. 仪式结构 are the OTHER two dimensions: they are already
# carried per-form by `deathWillId` / `ritual`, and validate_anchors.py reads
# those two back out of the form rows to complete the nine-dimension test.
# ---------------------------------------------------------------------------
ANCHOR_DIMENSIONS = [
    "watch", "source", "trigger", "combat", "ai", "zone", "interact",
    "behaviour",
]

# behaviour -> (displayName, spec reference) - the finite runtime vocabulary.
# 64 anchors map onto 26 behaviours, which keeps the implementation data-driven
# instead of one class per creature (spec 12.1 / 12.6).
ANCHOR_BEHAVIOURS = {
    "death_harvest": ("死亡收割", "6.3.1"),
    "corpse_anchor": ("尸锚", "6.3.1"),
    "desiccation_field": ("枯竭场", "6.3.1"),
    "tide_pursuit": ("亡潮追猎", "6.3.1"),
    "arrow_forecast": ("落点预测", "6.3.3"),
    "frost_trajectory": ("冰轨预判", "6.3.3"),
    "blade_reap": ("凋零收割", "6.3.1"),
    "village_cycle": ("循环再生", "6.3.11"),
    "gold_contract": ("金契掠夺", "6.3.11"),
    "gold_flame": ("金焰灼印", "6.3.1"),
    "web_of_fate": ("命运织网", "6.3.2"),
    "venom_nest": ("毒巢", "6.3.2"),
    "shadow_mite": ("影螨撕咬", "6.3.2"),
    "burrow_swarm": ("潜地虫群", "6.3.2"),
    "hive": ("蜂巢网络", "6.3.6"),
    "charge_horn": ("冲锋号角", "6.3.5"),
    "spore": ("孢子扩散", "6.3.9"),
    "tusk": ("獠牙突刺", "6.3.5"),
    "wool": ("绒毛缓冲", "6.3.5"),
    "plume_dance": ("羽舞扰乱", "6.3.5"),
    "moon_leap": ("月跃", "6.3.9"),
    "night_prowl": ("夜行潜猎", "6.3.5"),
    "nine_lives": ("九命回援", "6.3.5"),
    "jungle_stalk": ("丛影尾随", "6.3.5"),
    "hunt": ("狩猎锚", "6.3.5"),
    "steady_hoof": ("稳健驮行", "6.3.5"),
    "burden": ("负重领域", "6.3.5"),
    "sand_march": ("沙行长驱", "6.3.9"),
    "sky_dash": ("疾空冲刺", "6.3.5"),
    "rock_roll": ("滚石领域", "6.3.9"),
    "bamboo": ("竹阵", "6.3.9"),
    "arctic_fang": ("极地獠牙", "6.3.9"),
    "mountain_horn": ("山峦回响", "6.3.9"),
    "snow_prowl": ("雪原潜行", "6.3.9"),
    "biome_orb": ("群系领域", "6.3.9"),
    "warm_spring": ("暖泉蒸腾", "6.3.9"),
    "grass_echo": ("草木回声", "6.3.9"),
    "frost_leap": ("寒跃冻结", "6.3.9"),
    "tide_shell": ("潮汐甲壳", "6.3.9"),
    "echo_sense": ("回响感知", "6.3.8"),
    "deep_ink": ("深墨障目", "6.3.9"),
    "current_dash": ("洋流突进", "6.3.5"),
    "spine_guard": ("棘刺防卫", "6.3.5"),
    "school_charge": ("群游冲阵", "6.3.5"),
    "coral_scale": ("珊瑚鳞护", "6.3.9"),
    "regeneration_gill": ("再生鳃息", "6.3.9"),
    "city_wall": ("城墙壁垒", "6.3.10"),
    "guardian_watch": ("守望铁卫", "6.3.10"),
    "elastic": ("弹性锚", "6.3.10"),
    "magma_core": ("熔核锚", "6.3.10"),
    "sun_flare": ("日耀喷发", "6.3.10"),
    "wail": ("哀鸣声场", "6.3.10"),
    "lava_stride": ("熔岩踏行", "6.3.10"),
    "crimson_tusk": ("绯红獠牙", "6.3.10"),
    "space": ("空间锚", "6.3.7"),
    "shell": ("甲壳锚", "6.3.10"),
    "prism": ("棱镜折射", "6.3.10"),
    "deep_echo": ("回声锚", "6.3.8"),
    "implosion": ("爆鸣锚", "6.3.4"),
    "sulfur_core": ("核心锚", "6.3.10"),
    "profession": ("职业锚", "6.3.11"),
    "plume_echo": ("羽声回响", "6.3.8"),
}

ANCHOR_DEFS = {
    # --- zombie family: 死亡锚 branch (6.3.1) -----------------------------
    "zombie_death": dict(
        name="死亡锚", behaviour="death_harvest",
        watch="敌人的死亡", source="死亡产生「亡迹」",
        trigger="任一敌人在自身 16 格内死亡",
        combat="把敌死位置当作攻击节点，而不是追着敌人打",
        ai="优先移动到最近的亡迹，而不是最近的敌人",
        zone="多个亡迹可连成一条通路，路径上移动加快",
        interact="玩家踩在亡迹上时，下一次攻击附带亡迹标记"),
    "zombie_grave": dict(
        name="墓穴锚", behaviour="corpse_anchor",
        watch="尸体留下的地点与朝向", source="墓碑状「墓钉」",
        trigger="伙伴经过任意尸体位置",
        combat="守墓式站桩：在墓钉范围内攻击力不变但攻击范围外扩",
        ai="不追击离开墓钉范围的目标，转为守住墓钉",
        zone="每个墓钉为 4 格半径的己方地形",
        interact="玩家可以在墓钉上休息，缩短伙伴下一次机制冷却"),
    "zombie_desiccation": dict(
        name="枯竭锚", behaviour="desiccation_field",
        watch="敌人的剩余生命与增益状态", source="「干涸值」",
        trigger="目标身上存在任何正面状态效果",
        combat="不追加伤害，而是让目标的增益逐层失效",
        ai="优先锁定携带增益的目标，无视距离更近的普通敌人",
        zone="自身周围形成枯竭场，场内增益持续时间加速流失",
        interact="玩家在枯竭场内喝下的药水持续时间减半但强度提升"),
    "zombie_tide": dict(
        name="亡潮锚", behaviour="tide_pursuit",
        watch="连续击杀的时间间隔", source="「潮位」随连续击杀上涨",
        trigger="两次击杀间隔小于 5 秒",
        combat="潮位越高，伙伴的追击速度越快（追击而非伤害）",
        ai="潮位高时不再回撤，持续追踪下一个目标",
        zone="潮位满时在身周形成追猎路线，路径上的敌人被强制暴露",
        interact="玩家沿追猎路线前进时获得同向加速"),
    "zombie_bone_arrow": dict(
        name="骨矢锚", behaviour="arrow_forecast",
        watch="目标的移动向量", source="「预测落点」",
        trigger="目标连续移动超过 1 秒",
        combat="不射击当前位置，而是射击预判位置",
        ai="优先选择直线移动的目标（更容易预测）",
        zone="预测落点处生成悬浮箭符，进入该点的敌人被标记",
        interact="玩家瞄准预测落点射箭时，箭矢会轻微自动校正"),
    "zombie_frost_arrow": dict(
        name="霜轨锚", behaviour="frost_trajectory",
        watch="目标移动时留下的轨迹", source="「寒轨」",
        trigger="目标在 3 秒内经过同一路段两次",
        combat="沿寒轨发射的投射物附带冻结而非额外伤害",
        ai="优先封锁敌人习惯走的通道",
        zone="寒轨结成可滑行的冰面，己方在冰面上移动更快",
        interact="玩家在冰面上滑行时伙伴同步提速"),
    "zombie_wither_blade": dict(
        name="凋刃锚", behaviour="blade_reap",
        watch="敌人身上的负面状态层数", source="「凋零层数」",
        trigger="目标身上叠有 3 层以上负面状态",
        combat="对高负面层数目标改为处决式斩杀（阈值斩杀，非倍率）",
        ai="只锁定已被削弱的目标，放弃满血敌人",
        zone="斩杀成功后在原地留下凋刃领域",
        interact="玩家攻击凋零领域内的敌人时触发一次额外凋零"),
    "zombie_village_cycle": dict(
        name="循环锚", behaviour="village_cycle",
        watch="周围的植物生长与作物成熟", source="「循环值」",
        trigger="附近有作物完成一次生长",
        combat="把循环值转化为一次范围再生，而不是攻击",
        ai="优先停留在农田 / 植被密集区域",
        zone="循环值满时催熟周围作物",
        interact="玩家收割作物会立刻补充伙伴的循环值"),
    "zombie_gold_contract": dict(
        name="金契锚", behaviour="gold_contract",
        watch="敌人携带的掉落物与装备", source="「契约重量」",
        trigger="目标身上存在任何装备或掉落物",
        combat="优先掠夺而不是击杀：命中时夺取目标装备",
        ai="锁定装备最好的敌人，而不是血量最低的",
        zone="被夺走装备的敌人进入无甲状态",
        interact="玩家拾取被夺取的装备时获得短时增益"),
    "zombie_gold_flame": dict(
        name="金焰锚", behaviour="gold_flame",
        watch="被点燃的敌人数量", source="「灼印」",
        trigger="任意敌人处于着火状态",
        combat="火势在敌人之间传递，伙伴本身不输出额外伤害",
        ai="优先攻击尚未着火的敌人以扩大火场",
        zone="火场内的敌人无法隐去身形",
        interact="玩家用火把点燃敌人时，灼印直接叠加两层"),

    # --- arthropod family: 织命锚 branch (6.3.2) + 蜂巢 (6.3.6) ----------
    "arthropod_hive": dict(
        name="蜂巢锚", behaviour="hive",
        watch="被标记的敌人数量与位置", source="「巢线」",
        trigger="两个以上敌人被标记",
        combat="在护主 / 治疗 / 控制之间自动切换，而不是固定输出",
        ai="巢线连接成网络后，按网络中心决定站位",
        zone="标记形成六边形巢网，网内的己方单位受到保护",
        interact="玩家攻击某一目标时，该目标加入巢线网络"),
    "arthropod_web_of_fate": dict(
        name="织命锚", behaviour="web_of_fate",
        watch="敌人的移动路径", source="「记忆丝」",
        trigger="敌人重复走同一条路线",
        combat="在预测路径上织网，而不是直接攻击",
        ai="跟踪敌人在区域内的历史走位而非当前位置",
        zone="记忆丝互相连接形成命运蛛网，网内敌人移动受限",
        interact="玩家站上蛛网节点时扩大网的覆盖范围"),
    "arthropod_venom_nest": dict(
        name="毒巢锚", behaviour="venom_nest",
        watch="中毒目标的位置", source="「巢毒」",
        trigger="范围内中毒敌人达 3 个",
        combat="把毒层聚合成一次范围毒爆，而非叠加伤害",
        ai="优先向毒层最厚的位置移动",
        zone="毒巢覆盖处的地面持续为踩踏者叠加毒层",
        interact="玩家在巢上使用任意药水会转化为毒雾"),
    "arthropod_shadow_mite": dict(
        name="影螨锚", behaviour="shadow_mite",
        watch="玩家背后与视野死角的空间", source="「影隙」",
        trigger="有敌人进入玩家 120 度盲区",
        combat="只攻击盲区内的目标，正面敌人完全放过",
        ai="始终绕到目标的背面",
        zone="自身周围影隙内的敌人被持续标记",
        interact="玩家背对敌人时伙伴自动补位"),
    "arthropod_burrow_swarm": dict(
        name="潜地锚", behaviour="burrow_swarm",
        watch="地面材质与可穿透方块", source="「潜地计数」",
        trigger="自身站在软质方块（土 / 沙 / 泥）上",
        combat="从地下突袭，命中不计伤害而是造成击飞",
        ai="追击时始终选择软质地形的路径",
        zone="可潜地的方块被转化为己方通道",
        interact="玩家在软地质上潜行时伙伴同步潜行"),

    # --- animal family: 狩猎锚 branch (6.3.5) ----------------------------
    "animal_hunt": dict(
        name="狩猎锚", behaviour="hunt",
        watch="玩家与自身共同锁定的目标", source="「猎痕」",
        trigger="玩家与伙伴攻击同一目标",
        combat="猎痕累积到阈值召唤短暂幽影狼群，而不是提升攻击",
        ai="始终与玩家锁定同一目标，绝不自行换目标",
        zone="猎痕满时形成狩猎领域，领域内目标被公开标记",
        interact="玩家持续攻击同一目标会加速猎痕累积"),
    "animal_charge_horn": dict(
        name="冲锋锚", behaviour="charge_horn",
        watch="敌人与自身之间的连线", source="「冲势」",
        trigger="与目标距离超过 8 格且视线通畅",
        combat="直线冲锋撞击，造成强制位移而非伤害",
        ai="优先选择成排站立的敌人，以获得更长的冲锋距离",
        zone="冲锋路径被短暂开辟为无地形阻碍的通道",
        interact="玩家沿冲锋同向前进时获得推力"),
    "animal_spore": dict(
        name="孢子锚", behaviour="spore",
        watch="周围生物群系与湿度", source="「孢粉」",
        trigger="附近存在植被或潮湿方块",
        combat="释放孢子云雾，命中者被减速并被标记",
        ai="优先在植被密集处交战",
        zone="孢子云扩散为持续存在的领域，己方在云内回血",
        interact="玩家破坏植被会一次性补充大量孢粉"),
    "animal_tusk": dict(
        name="獠牙锚", behaviour="tusk",
        watch="目标的朝向与防御方向", source="「破防点」",
        trigger="目标背对伙伴或正在攻击他人",
        combat="只从破防点突刺，命中时忽略目标的格挡",
        ai="绕后优先于正面迎击",
        zone="破防点被刺穿后留下短暂缺口，己方可自由进出",
        interact="玩家从同侧攻击时共享破防判定"),
    "animal_wool": dict(
        name="绒毛锚", behaviour="wool",
        watch="己方单位受到的伤害类型", source="「绒层」",
        trigger="任意己方单位受到伤害",
        combat="不还手，转而把伤害转化为一层缓冲绒",
        ai="始终站在玩家与最近的敌人之间",
        zone="绒层覆盖范围内的己方单位受到伤害时按绒层数减伤",
        interact="玩家受到攻击后绒层自动转移到玩家身上"),
    "animal_plume_dance": dict(
        name="羽舞锚", behaviour="plume_dance",
        watch="自身羽毛颜色与周围地形颜色", source="「羽色伪装」",
        trigger="自身颜色与环境色接近",
        combat="在伪装状态下敌人无法锁定，伙伴可自由攻击",
        ai="主动向颜色相近的地形移动",
        zone="伪装区域内敌人失去目标",
        interact="玩家穿着相近颜色护甲时同样获得伪装"),
    "animal_moon_leap": dict(
        name="月跃锚", behaviour="moon_leap",
        watch="光照等级与月相", source="「月能」",
        trigger="夜色或低光照环境",
        combat="以跳跃位移代替追击，落点造成击退",
        ai="夜间改为主动进攻，白天转为跟随",
        zone="月能满时在落点形成短时跃迁点",
        interact="玩家在夜间跟随伙伴跳跃会获得跳跃加成"),
    "animal_night_prowl": dict(
        name="夜猎锚", behaviour="night_prowl",
        watch="敌人的视野方向与警戒状态", source="「潜行值」",
        trigger="目标尚未发现自己",
        combat="首次攻击从潜行状态发起，命中后强制解除目标警戒",
        ai="始终维持潜行姿态直到进入攻击距离",
        zone="潜行值覆盖范围随移动扩大",
        interact="玩家潜行时共享潜行值"),
    "animal_nine_lives": dict(
        name="回援锚", behaviour="nine_lives",
        watch="主人的生命比例", source="「命数」",
        trigger="主人生命低于 40%",
        combat="立即放弃当前目标回援，落地造成一次范围击退",
        ai="任何情况下优先回到主人身边",
        zone="回援落地位置形成短暂安全圈",
        interact="玩家低血时伙伴自动标记该位置"),
    "animal_jungle_stalk": dict(
        name="丛影锚", behaviour="jungle_stalk",
        watch="丛林植被的遮挡关系", source="「影迹」",
        trigger="目标与自身之间存在植被遮挡",
        combat="隔着植被发起攻击，命中不解除遮挡",
        ai="永远选择有遮挡的路线接近目标",
        zone="植被密集区视为己方隐蔽区",
        interact="玩家藏在植被中时伙伴同步隐蔽"),
    "animal_steady_hoof": dict(
        name="稳健锚", behaviour="steady_hoof",
        watch="脚下地形的高低差", source="「稳度」",
        trigger="自身在非平坦地形上移动",
        combat="不攻击，而是把稳度转化为一次地形踏平",
        ai="主动走最平坦的路线护送主人",
        zone="踏平后的路面为所有己方单位提供移动加速",
        interact="玩家在踏平路面上骑行速度提升"),

    # --- mount family: 骑乘锚 branch (6.3.5) -----------------------------
    "mount_charge_horn": dict(
        name="冲角锚", behaviour="charge_horn",
        watch="冲锋路径上的敌人排布", source="「角势」",
        trigger="路径上存在 3 个以上敌人",
        combat="一次性贯穿冲撞，沿途敌人全部被推开",
        ai="优先选择敌人最密集的方向",
        zone="冲撞后路径变成己方跑道",
        interact="玩家在跑道上骑行时不再被减速"),
    "mount_steady_hoof": dict(
        name="稳蹄锚", behaviour="steady_hoof",
        watch="载具与主人的同步状态", source="「同步值」",
        trigger="玩家骑乘中",
        combat="骑乘状态下不主动攻击，改为稳定驾驶",
        ai="完全服从玩家转向输入",
        zone="同步值满时免疫击落",
        interact="玩家骑乘时受到击退减半"),
    "mount_burden": dict(
        name="负重锚", behaviour="burden",
        watch="自身与主人的背包重量", source="「负重值」",
        trigger="背包接近满载",
        combat="以负重换取撞击强度：越重撞得越远",
        ai="满载时避开狭窄地形",
        zone="负重值转化为一次性的重量领域，压低范围内所有跳跃",
        interact="玩家可以主动向伙伴装载物品来提升负重值"),
    "mount_sand_march": dict(
        name="沙行锚", behaviour="sand_march",
        watch="地面材质（沙 / 砾石）", source="「沙程」",
        trigger="在沙质地形上长时间移动",
        combat="扬沙致盲，命中者短时间内失去目标",
        ai="主动沿沙地前进以积累沙程",
        zone="沙程覆盖处形成沙墙，隔断敌人的远程视线",
        interact="玩家在沙墙后远程攻击不会被反击"),
    "mount_sky_dash": dict(
        name="空冲锚", behaviour="sky_dash",
        watch="自身高度与空中位置", source="「升力」",
        trigger="自身处于离地状态",
        combat="空中冲刺撞击，落地造成范围震动",
        ai="优先选择有高度差的路线",
        zone="落地震动范围视为己方控制区",
        interact="玩家与伙伴同时起跳时升力共享"),

    # --- snow / alpine family (6.3.9) ------------------------------------
    "snow_snow_prowl": dict(
        name="雪潜锚", behaviour="snow_prowl",
        watch="雪地上的足迹", source="「雪迹」",
        trigger="自身在雪层上移动",
        combat="从足迹覆盖处发动突袭",
        ai="始终沿自己留下的足迹返回",
        zone="足迹区域为己方视野区",
        interact="玩家沿足迹前进不会被敌人伏击"),
    "snow_rock_roll": dict(
        name="滚石锚", behaviour="rock_roll",
        watch="斜坡方向与坡度", source="「滚动势能」",
        trigger="自身位于下坡",
        combat="沿坡滚落撞击，伤害与坡度无关而与滚动距离相关",
        ai="主动寻找下坡路线发起攻击",
        zone="滚落路径被压平为通道",
        interact="玩家沿滚落路径滑行加速"),
    "snow_bamboo": dict(
        name="竹阵锚", behaviour="bamboo",
        watch="竹类方块的分布", source="「竹节」",
        trigger="周围存在竹子",
        combat="在竹阵中高速穿行，命中不计伤害而是缠绕目标",
        ai="只在竹阵范围内交战，离开则撤回",
        zone="竹阵内己方单位获得隐蔽",
        interact="玩家种植竹子会扩大竹阵"),
    "snow_arctic_fang": dict(
        name="极牙锚", behaviour="arctic_fang",
        watch="目标的体温状态", source="「极寒层」",
        trigger="目标处于寒冷生物群系",
        combat="咬合时叠加极寒层，层满则冻结而非追加伤害",
        ai="把目标推离热源",
        zone="极寒层扩散为冻结区",
        interact="玩家用雪球命中目标可叠加一层极寒"),
    "snow_mountain_horn": dict(
        name="山峦锚", behaviour="mountain_horn",
        watch="高度与山体遮挡", source="「回响值」",
        trigger="自身处于高海拔",
        combat="以吼叫造成范围击退并揭露隐形目标",
        ai="优先占据高地",
        zone="山峦回响覆盖的山顶为己方据点",
        interact="玩家站在据点内攻击力不变但视野扩大"),

    # --- environment family: 青蛙环境锚 (6.3.9) --------------------------
    "environment_warm_spring": dict(
        name="暖泉锚", behaviour="warm_spring",
        watch="环境温度与水源", source="「热汽」",
        trigger="附近存在水源或高温方块",
        combat="蒸腾汽雾遮蔽视线，命中者被推离",
        ai="始终在水边交战",
        zone="暖泉区域内的己方单位持续恢复",
        interact="玩家在水边战斗时恢复效果加倍"),
    "environment_grass_echo": dict(
        name="草回锚", behaviour="grass_echo",
        watch="脚下的植被与草地覆盖", source="「草回声」",
        trigger="自身在草地 / 苔藓上移动",
        combat="把草回声转化为范围缠绕，命中者无法冲刺",
        ai="沿植被最密的路线移动",
        zone="草回声覆盖区为己方隐蔽区",
        interact="玩家在草地上潜行时伙伴同步隐蔽"),
    "environment_frost_leap": dict(
        name="寒跃锚", behaviour="frost_leap",
        watch="可冻结的液体表面", source="「冻点」",
        trigger="跳跃落点附近存在水或岩浆",
        combat="落点冻结液体，把敌人困住而非造成伤害",
        ai="优先跳到液体附近",
        zone="冻结后的表面成为己方可通行地形",
        interact="玩家踩上冻结表面不会滑倒"),
    "environment_biome_orb": dict(
        name="群系锚", behaviour="biome_orb",
        watch="当前生物群系的类型", source="「群系球」随群系切换形态",
        trigger="玩家进入新的生物群系",
        combat="群系球的形态决定战斗方式（温带缠绕 / 寒冷冻结 / 暖热蒸发）",
        ai="跟随玩家所在群系改变行为",
        zone="群系球覆盖处地形被短暂改写为当前群系",
        interact="玩家在不同群系与伙伴交互会得到不同回应"),

    # --- aquatic family (6.3.9) ------------------------------------------
    "aquatic_echo_sense": dict(
        name="回响锚", behaviour="echo_sense",
        watch="水中的声音与声呐反射", source="「可视声纹」",
        trigger="任何生物在水中移动或受到攻击",
        combat="不直接攻击，而是把声音来源可视化供队友打击",
        ai="优先向声纹最密集处移动",
        zone="回声领域内所有重要声音暴露来源",
        interact="玩家受到偷袭时释放一次定位波"),
    "aquatic_tide_shell": dict(
        name="潮甲锚", behaviour="tide_shell",
        watch="水域的潮位与深度", source="「潮层」",
        trigger="自身处于水中",
        combat="把潮层转化为一次性甲壳，抵挡而非反击",
        ai="始终待在水域内",
        zone="潮层覆盖处水压增加，敌人进入后被减速",
        interact="玩家在水中与伙伴同游时获得水下呼吸的补充"),
    "aquatic_deep_ink": dict(
        name="深墨锚", behaviour="deep_ink",
        watch="敌人的视线方向", source="「墨量」",
        trigger="敌人正在锁定己方单位",
        combat="喷墨致盲，被致盲者失去目标",
        ai="优先向锁定主人的敌人喷射",
        zone="墨云覆盖范围为己方绝对隐蔽区",
        interact="玩家在墨云内不会被远程锁定"),
    "aquatic_current_dash": dict(
        name="洋流锚", behaviour="current_dash",
        watch="水流方向", source="「洋流值」",
        trigger="自身处于流动的水中",
        combat="顺流冲刺撞击，逆流时改为驻守",
        ai="永远顺着水流方向选择交战路线",
        zone="洋流路径为己方快速通道",
        interact="玩家顺流移动时伙伴提速同步"),
    "aquatic_spine_guard": dict(
        name="棘刺锚", behaviour="spine_guard",
        watch="接近己方的敌人数量", source="「棘层」",
        trigger="3 个以上敌人进入 6 格范围",
        combat="展开棘刺，敌人攻击时反噬（反射而非伤害加成）",
        ai="只在被包围时展开，平时保持跟随",
        zone="棘刺覆盖范围为拒止区，敌人进入被持续推开",
        interact="玩家站在拒止区内受到的反伤减半"),
    "aquatic_school_charge": dict(
        name="群游锚", behaviour="school_charge",
        watch="同族单位的数量", source="「群势」",
        trigger="附近存在同类伙伴",
        combat="群势越高，冲阵造成的击退越远（不提升伤害）",
        ai="始终与同族单位保持队形",
        zone="群游队形覆盖处为己方阵型区",
        interact="玩家站在阵型中心时获得队形保护"),
    "aquatic_coral_scale": dict(
        name="珊瑚锚", behaviour="coral_scale",
        watch="周围珊瑚与暖水方块", source="「珊瑚鳞」",
        trigger="附近存在珊瑚",
        combat="鳞片按珊瑚颜色变化，抵挡对应元素的伤害",
        ai="优先靠近与自己鳞色一致的珊瑚",
        zone="珊瑚区域视为己方疗养区",
        interact="玩家在珊瑚区采集不会惊动伙伴"),
    "aquatic_regeneration_gill": dict(
        name="再生锚", behaviour="regeneration_gill",
        watch="己方单位缺失的生命", source="「鳃息」",
        trigger="任意己方单位生命低于一半",
        combat="不攻击，持续把鳃息转化为范围再生",
        ai="始终贴近血量最低的己方单位",
        zone="鳃息范围内持续恢复且不会被水流冲散",
        interact="玩家在鳃息范围内复活虚弱的伙伴更快"),

    # --- construct family (6.3.10) ---------------------------------------
    "construct_guardian_watch": dict(
        name="守望锚", behaviour="guardian_watch",
        watch="被保护目标的移动范围", source="「守望半径」",
        trigger="被保护者离开既定区域",
        combat="拦截一切进入守望半径的敌人（拦截而非输出）",
        ai="自身几乎不移动，只在守望半径内巡逻",
        zone="守望半径为不可侵犯区",
        interact="玩家可以在守望半径内指定新的保护点"),
    "construct_city_wall": dict(
        name="城墙锚", behaviour="city_wall",
        watch="可建造的方块与地形", source="「墙体结构」",
        trigger="周围存在可放置方块",
        combat="不攻击，把地形改造为墙体阻隔敌人",
        ai="沿墙巡逻，遇敌绕墙而不是穿过",
        zone="墙体为完全隔断，敌人必须绕行",
        interact="玩家可以拆除墙体回收材料"),

    # --- nether / end family (6.3.4 / 6.3.7 / 6.3.8 / 6.3.10) -----------
    "nether_end_space": dict(
        name="空间锚", behaviour="space",
        watch="玩家和战场的空间位置", source="「空间节点」",
        trigger="玩家记录了一个安全位置",
        combat="建立三点空间网络，进行有限度安全换位而非瞬移攻击",
        ai="持续维护三个节点的连接关系",
        zone="节点覆盖范围为可换位区",
        interact="玩家可以在节点之间有限度换位"),
    "nether_end_deep_echo": dict(
        name="回声锚", behaviour="deep_echo",
        watch="脚步 / 碰撞 / 投掷 / 生物移动 / 环境声音", source="「可视声纹」",
        trigger="任何声音事件发生",
        combat="不攻击，把所有声音来源可视化并共享给队友",
        ai="优先向最近的声音事件移动",
        zone="回声领域内所有重要声音暴露来源",
        interact="玩家受到偷袭时释放一次定位波"),
    "nether_end_elastic": dict(
        name="弹性锚", behaviour="elastic",
        watch="碰撞的方向与速度", source="「弹性能」",
        trigger="自身发生碰撞",
        combat="按碰撞方向反弹，反弹路径上的敌人被推开",
        ai="主动撞向方块以积累弹性能",
        zone="反弹路径形成弹跳通道",
        interact="玩家与伙伴碰撞时同样获得弹力"),
    "nether_end_magma_core": dict(
        name="熔核锚", behaviour="magma_core",
        watch="周围的热量与火焰", source="「熔核温度」",
        trigger="附近存在火源或高温方块",
        combat="吸收热量后释放非破坏性热浪",
        ai="优先靠近热源",
        zone="熔核覆盖处地面持续高温，敌对单位被烫伤",
        interact="玩家在熔核内不会被岩浆伤害"),
    "nether_end_sun_flare": dict(
        name="日耀锚", behaviour="sun_flare",
        watch="光照强度与天空暴露度", source="「耀斑值」",
        trigger="头顶无遮挡且为白天",
        combat="释放致盲耀斑，被致盲者短暂失控",
        ai="优先占据天空开阔处",
        zone="耀斑范围内敌人无法瞄准",
        interact="玩家站在耀斑中心时远程攻击必中"),
    "nether_end_wail": dict(
        name="哀鸣锚", behaviour="wail",
        watch="敌人的听觉反应", source="「声场」",
        trigger="敌人靠近己方据点",
        combat="以哀鸣声场驱散敌人（驱散而非伤害）",
        ai="用声音把敌人赶向指定方向",
        zone="声场覆盖处敌人被迫远离",
        interact="玩家可以在声场内指定驱散方向"),
    "nether_end_lava_stride": dict(
        name="熔行锚", behaviour="lava_stride",
        watch="岩浆与热液方块", source="「踏行点」",
        trigger="自身踩在岩浆上",
        combat="在岩浆面上高速移动并撞击敌人",
        ai="把岩浆面当作主要移动通道",
        zone="岩浆面被转化为己方通路",
        interact="玩家跟随伙伴走过岩浆面时获得短时防火"),
    "nether_end_crimson_tusk": dict(
        name="绯牙锚", behaviour="crimson_tusk",
        watch="目标的击退抗性", source="「绯红势能」",
        trigger="目标被击退",
        combat="撞击造成连续击退（位移控制而非伤害）",
        ai="优先攻击能被击退的目标，跳过抗性目标",
        zone="被击退路径之上形成绯红通道",
        interact="玩家沿同一方向推挤时效果叠加"),
    "nether_end_shell": dict(
        name="甲壳锚", behaviour="shell",
        watch="背上的骑乘者状态", source="「甲层」",
        trigger="身上载有单位",
        combat="载人状态下防御提升但不再主动攻击",
        ai="始终朝向敌人以保护背上的单位",
        zone="甲壳覆盖处为安全落脚点",
        interact="玩家站在甲壳上不会被击退"),
    "nether_end_prism": dict(
        name="棱镜锚", behaviour="prism",
        watch="照射到自身的光线角度", source="「折射值」",
        trigger="自身被任意光源照射",
        combat="把光线折射出去攻击路径上的敌人（借用环境而非自伤）",
        ai="主动移动到光源与敌人之间的位置",
        zone="折射路径被点亮，敌人无法隐蔽",
        interact="玩家用光源照射伙伴可指定折射方向"),

    # --- special family (6.3.4 / 6.3.10 / 6.3.11) ------------------------
    "special_implosion": dict(
        name="爆鸣锚", behaviour="implosion",
        watch="冲击 / 击退 / 碰撞 / 爆炸震动", source="「震荡能量」",
        trigger="任一上述事件发生",
        combat="释放非破坏性冲击波，只位移不破坏地形",
        ai="主动制造碰撞事件以蓄能",
        zone="冲击波覆盖范围为击退区",
        interact="玩家在冲击波内不会被击倒"),
    "special_sulfur_core": dict(
        name="核心锚", behaviour="sulfur_core",
        watch="战场上发生的所有事件", source="「核心吸收」",
        trigger="任意事件在附近发生",
        combat="按吸收到的事件类型自动切换战斗方式",
        ai="不做固定决策，完全由吸收内容驱动",
        zone="核心覆盖处地形随吸收内容改变",
        interact="玩家主动向核心投喂方块会改变其形态"),
    "special_profession": dict(
        name="职业锚", behaviour="profession",
        watch="职业对应的生产流程", source="「职业资源」",
        trigger="玩家完成一次对应职业行为",
        combat="不直接战斗，把职业资源转化为辅助效果",
        ai="驻留在对应的功能方块附近",
        zone="职业区域为己方补给点",
        interact="玩家在补给点内获得对应职业的增益"),
    "special_plume_echo": dict(
        name="羽声锚", behaviour="plume_echo",
        watch="周围声音的音高与节奏", source="「回响羽片」",
        trigger="环境音量超过阈值",
        combat="把声音转化为羽片弹幕，命中造成失谐",
        ai="跟随声音来源移动并对齐节奏",
        zone="羽声覆盖处声音被放大，敌人无法潜行接近",
        interact="玩家在羽声范围内喊话会强化伙伴的下一次机制"),
}

# family -> ordered anchor suffix list. Ordered so the round-robin in
# build_forms() spreads anchors evenly; each suffix must exist in
# ANCHOR_DEFS as f"{family}_{suffix}".
ANCHORS = {
    "zombie": [k[len("zombie_"):] for k in ANCHOR_DEFS if k.startswith("zombie_")],
    "arthropod": [k[len("arthropod_"):] for k in ANCHOR_DEFS if k.startswith("arthropod_")],
    "animal": [k[len("animal_"):] for k in ANCHOR_DEFS if k.startswith("animal_")],
    "mount": [k[len("mount_"):] for k in ANCHOR_DEFS if k.startswith("mount_")],
    "snow": [k[len("snow_"):] for k in ANCHOR_DEFS if k.startswith("snow_")],
    "environment": [k[len("environment_"):] for k in ANCHOR_DEFS if k.startswith("environment_")],
    "aquatic": [k[len("aquatic_"):] for k in ANCHOR_DEFS if k.startswith("aquatic_")],
    "construct": [k[len("construct_"):] for k in ANCHOR_DEFS if k.startswith("construct_")],
    "nether_end": [k[len("nether_end_"):] for k in ANCHOR_DEFS if k.startswith("nether_end_")],
    "special": [k[len("special_"):] for k in ANCHOR_DEFS if k.startswith("special_")],
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
        anchor_def = ANCHOR_DEFS.get(anchor)
        if anchor_def is None:
            # reserved band (11.2.3): parsed but never registered, so it needs
            # no anchor definition.
            anchor_def = {"behaviour": "reserved"}

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
            "anchorBehaviour": anchor_def["behaviour"],
            "anchorRef": ANCHOR_BEHAVIOURS.get(
                anchor_def["behaviour"], ("", ""))[1],
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
        # 第六章: the anchor table. Each entry carries the seven textual
        # dimensions from spec 6.2 plus the runtime behaviour keyword; the
        # remaining two dimensions (死亡遗志 / 仪式结构) live on the form rows.
        "anchors": {
            aid: {
                "name": d["name"],
                "familyId": aid.rsplit("_", 1)[0]
                if aid.rsplit("_", 1)[0] in ANCHORS else aid.split("_")[0],
                "behaviour": d["behaviour"],
                "specRef": ANCHOR_BEHAVIOURS[d["behaviour"]][1],
                "watch": d["watch"],
                "source": d["source"],
                "trigger": d["trigger"],
                "combat": d["combat"],
                "ai": d["ai"],
                "zone": d["zone"],
                "interact": d["interact"],
            }
            for aid, d in ANCHOR_DEFS.items()
        },
        "anchorBehaviours": {
            k: {"name": v[0], "specRef": v[1]}
            for k, v in ANCHOR_BEHAVIOURS.items()
        },
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
