# 灵魂锚点系统 — 实现说明（规格第 6 章 + 12.1/12.2）

> 本文说明 `golem_covenant` 附属模组中**灵魂锚点系统**的落实现状。
> 权威需求见 `Golem_Covenant_最终需求规格书.md` 第 6 章。

---

## 1. 规格要求回顾

规格把锚点定义为整个 Addon **最核心的系统**，并且反复强调它**不是**什么：

| 规格原文 | 位置 |
|---|---|
| 「锚点不是：Buff、属性、武器、单个技能」 | 6.1 |
| 「而是一套：**这个灵魂观察世界和参与战斗的规则**」 | 6.1 |
| 「186 个形态必须尽可能拥有**不同的主锚点**」 | 6.2 |
| 「锚点至少改变以下其中 **三项**」（九维列表） | 6.2 |
| 「**不建议**写 `ZombieCompanion.java`、`SpiderCompanion.java`… **应该采用** `Profile + Ability + Anchor + Ritual + DeathWill` 的**数据驱动架构**」 | 12.1 |

九维列表（规格 6.2）：

1. 观察对象　2. 资源来源　3. 触发条件　4. 战斗方式　5. AI 决策
6. 区域规则　7. 玩家互动　8. 死亡遗志　9. 仪式结构

---

## 2. 实现架构

采用规格 12.1 要求的数据驱动架构。**没有任何一个「每种生物一个类」的文件。**

```
tools/gen_registry.py          ← 唯一权威定义（ANCHOR_DEFS: 64 条 × 7 维 + behaviour）
        │
        ▼
data/golem_covenant/forms_registry.json
        │  anchors{}          64 条锚点完整定义
        │  anchorBehaviours{} 62 个运行时行为关键字
        │  forms[]            每行带 anchorId + anchorBehaviour
        ▼
data/AnchorProfile.java       ← 7 维文本 + behaviour 的 record
anchor/Anchors.java           ← 索引：按 anchorId / formId / CovenantData 解析
anchor/AnchorRuntime.java     ← 62 个 behaviour 分支的统一 dispatch 表
```

### 2.1 规模

| 项 | 数量 |
|---|---|
| 锚点总数（非保留） | **64** |
| 运行时行为关键字 | **62** |
| 族群 | 10（`zombie` 10 / `animal` 11 / `nether_end` 10 / `aquatic` 8 / `arthropod` 5 / `mount` 5 / `snow` 5 / `environment` 4 / `special` 4 / `construct` 2） |
| 覆盖的活跃形态 | **159**（另有 27 个保留形态不参与，规格 11.2.3） |
| 每锚点文本维度 | 7（第 8/9 维按 form 存于 `deathWillId` / `ritualTheme`） |

> 64 个锚点只对应 **62** 个行为：`charge_horn`（冲锋号角）与 `steady_hoof`（稳健驮行）
> 被 `animal` 与 `mount` 两个族群各自复用一份——这正是数据驱动的收益。

### 2.2 关键设计决定

**（1）锚点是「读取 + 改写行为」，绝不是数值。**
`AnchorRuntime` 里**没有一处** `getAttribute()` / `addAttributeModifier()` /
`setMaxHealth()`。锚点可用的手段只有：

- `MobEffectInstance` —— 且限于**状态与感知类**效果（缓慢、发光、失明、抗性、
  跳跃抑制、再生、饱食、飘浮、隐身…），即改变「谁能做什么」，而非「谁数值更高」
- `Mob.getTarget()` / `setTarget()` —— 改变索敌决策
- `getNavigation().moveTo()` —— 改变寻路
- `Entity.push(...)` —— 位移与击退（**代替**伤害）
- 粒子、音效、地形改写

例如规格 6.3.4 爆鸣锚明确要求「释放**非破坏性冲击波**」——实现里就是
`Entity.push` + 给主人 `RESISTANCE`（保证玩家不被击倒），**不破坏任何方块**。

**（2）Bond 门控的是「深度」，不是数值**（规格 9.3）。
每个锚点的深层行为都写成 `if (depth >= 1)` / `if (depth >= 2)`，`depth` 来自
`BondEngine.allowedDepth(companion)`（0..4）。规格原文：

> 「低 Bond: 伙伴只使用基础规则 / 高 Bond: 解锁灵魂锚点**深层规则**」

所以高 Bond 的僵尸会**开始**连接亡迹、骷髅会**开始**留下悬浮箭符，
而不是「僵尸攻击力 +20」。

**（3）战场读数不持久化**（规格 11.14）。
亡迹、猎痕、巢线、震荡能量这些「资源」存在 `AnchorRuntime.STATE`（内存
`Map<UUID, AnchorState>`），`SERVER_STOPPED` 时清空。它们是本场战斗的读数，
不是存档数据。

**（4）单个锚点异常绝不拖垮 tick**（规格 11.1.3）。
`tickOne()` 对 `dispatch()` 整体 try/catch，只记 warn 日志后继续。

**（5）性能上限**（规格 13.2.3）。
每 10 tick 评估一次；距离主人超过 `ACTIVE_RANGE`（48 格）的伙伴直接跳过。

---

## 3. 构建门禁

规格 6.2 的「至少三项」是**最容易随时间腐化**的规则——很容易在后续迭代里
悄悄退化成「+20 攻击」。因此它被做成了构建硬门禁。

### `tools/validate_anchors.py`（挂在 `check` 上，7 条不变量）

| # | 不变量 | 依据 |
|---|---|---|
| 1 | 每个活跃 form 的 anchorId 必须在 `anchors` 块中；每个声明锚点必须被使用 | 11.2.3 |
| 2 | **⭐ 同族群内任一对锚点必须在九维中至少差 3 维** | **6.2 铁律** |
| 3 | 七维文本不得读作数值加成 | 6.1 |
| 4 | 每个 behaviour 必须在词表中；`form.anchorBehaviour` 必须与锚点表一致 | 12.1 |
| 5 | 多锚点族群内死亡遗志与仪式主题必须有变化（第 8/9 维非退化） | 6.2 |
| 6 | 每个锚点名称 + 七维在 `en_us.json` 中必须是**真英文**（CJK 检测） | 11.15.4 |

**当前实测结果**：**191 个族群内锚点对全部通过**，最差的一对是
8/9 维（`animal_jungle_stalk` vs `animal_wool`）——远超「至少 3 维」的要求。

### 门禁实际抓到的问题

1. **误报**：`animal_nine_lives.trigger = "主人生命低于 40%"` 被判为数值加成。
   复核后确认这其实是**阈值触发条件**（正是九维的第 3 维该有的内容），
   因此收紧了 STAT_TALK 正则——裸 `\d+%` 不再算违规。
2. **真漏译**：首版 `zombie_death.source` 的英文残留 `亡迹` 中文。
   新加的 CJK 检测器立刻抓出，已修。

---

## 4. 规格 6.3 各锚点落地对照

| 规格章节 | 锚点 | behaviour | 实现要点 |
|---|---|---|---|
| 6.3.1 | 死亡锚 | `death_harvest` | `AFTER_DEATH` 记录亡迹节点；伙伴**走向亡迹而非敌人**；深度≥2 亡潮标记猎物 |
| 6.3.1 | 墓穴锚 | `corpse_anchor` | 守墓站桩，超出墓钉半径即放弃追击 |
| 6.3.1 | 枯竭锚 | `desiccation_field` | 读取敌人的**增益**并逐层剥离 |
| 6.3.1 | 亡潮锚 | `tide_pursuit` | 读取**击杀间隔**；潮位高时不回撤 |
| 6.3.3 | 骨矢锚 | `arrow_forecast` | 位置 + 速度×飞行时间 → 预测落点；深度≥2 悬浮箭符标记 |
| 6.3.3 | 霜轨锚 | `frost_trajectory` | 重复路线 → 结成冰面（冻结而非伤害） |
| 6.3.1 | 凋刃锚 | `blade_reap` | 负面状态层数达阈值 → **阈值斩杀**（非倍率） |
| 6.3.11 | 循环锚 | `village_cycle` | 读取作物生长 → 范围再生 |
| 6.3.11 | 金契锚 | `gold_contract` | 读取**敌人装备**并掠夺（而非击杀） |
| 6.3.1 | 金焰锚 | `gold_flame` | 读取**着火数量**，火势在敌人间传递 |
| 6.3.2 | 织命锚 | `web_of_fate` | 记录移动路径，重复生成记忆丝，交叉成命运蛛网 |
| 6.3.2 | 毒巢锚 | `venom_nest` | 毒层聚合成一次范围毒爆 |
| 6.3.2 | 影螨锚 | `shadow_mite` | 只攻击玩家 **120° 盲区**内的目标，始终绕后 |
| 6.3.2 | 潜地锚 | `burrow_swarm` | 读取**软质地表**，地下突袭造成击飞 |
| 6.3.6 | 蜂巢锚 | `hive` | 标记形成六边形网络；**自动在护主/治疗/控制间切换** |
| 6.3.5 | 狩猎锚 | `hunt` | 读取**玩家与伙伴的共有目标**；猎痕满召唤幽影狼群 |
| 6.3.5 | 冲锋锚 | `charge_horn` | 直线冲锋**强制位移**而非伤害 |
| 6.3.5 | 獠牙锚 | `tusk` | 读取目标朝向 → 只从破防点突刺，忽略格挡 |
| 6.3.5 | 绒毛锚 | `wool` | 读取**受到伤害**并转化为缓冲层 |
| 6.3.5 | 羽舞锚 | `plume_dance` | 颜色伪装 → 敌人**丢失锁定** |
| 6.3.5 | 回援锚 | `nine_lives` | 读取**主人生命比例**，低血立即回援并范围击退 |
| 6.3.7 | 空间锚 | `space` | 记录玩家安全位置；深度≥2 危及时有限换位 |
| 6.3.8 | 回声锚 | `deep_echo` | 读取**声音事件**并可视化为声纹 |
| 6.3.9 | 群系锚 | `biome_orb` | **读群系切机制**：温带缠绕 / 寒冷冻结 / 暖热蒸发 |
| 6.3.9 | 环境锚系 | `warm_spring` 等 | 温带/寒冷/暖热三支各自独立机制 |
| 6.3.10 | 弹性锚 | `elastic` | 读取碰撞方向并沿路推开 |
| 6.3.10 | 熔核锚 | `magma_core` | 吸收热量 → **非破坏性**热浪 |
| 6.3.10 | 核心锚 | `sulfur_core` | 读取**全部战场事件**，由吸收内容决定战斗风格 |
| 6.3.4 | 爆鸣锚 | `implosion` | 记录冲击/击退/碰撞，释放**非破坏性**冲击波 |
| 6.3.11 | 职业锚 | `profession` | 职业即锚点，转化为辅助与补给 |

其余锚点（`nether_end` 10 个含熔行/日耀/哀鸣/绯牙/甲壳/棱镜，
`snow` 5 个含雪潜/滚石/竹阵/极牙/山峦，`aquatic` 8 个含潮甲/深墨/洋流/棘刺/群游/珊瑚/再生
等）同样按 6.3 与 6.3.9/6.3.10 的规则实现，逐条可在
`src/main/java/com/example/golem_covenant/anchor/AnchorRuntime.java` 中按
`case "<behaviour>" ->` 定位。

---

## 5. 本地化

规格 11.15.4 要求 `en_us` 是真英文。锚点部分共补 **64 × (1 名称 + 7 维) ≈ 512** 条英译，
在 `tools/gen_lang.py` 的 `ANCHOR_EN` 表中维护。lang 总键数：

| 文件 | 键数 |
|---|---|
| `en_us.json` | **899** |
| `zh_cn.json` | **899**（键集与英文完全一致） |
| 锚点相关未翻译键 | **0** |

生成的键：

- `golem_covenant.anchor.<anchorId>` —— 锚点名（「死亡锚」/「Death Anchor」）
- `golem_covenant.anchor.<anchorId>.<dim>` —— 七维正文（dim ∈ watch/source/trigger/combat/ai/zone/interact）
- `golem_covenant.anchor.dim.<dim>` —— 九维标签（「观察对象」/「Watches」）
- `golem_covenant.behaviour.<behaviour>` —— 行为名

---

## 6. 验证

```
./gradlew clean build --offline
```

（需 `JAVA_HOME=/d/jdk/jdk-25_windows-x64_bin/jdk-25.0.4`，`PYTHON=D:/python/python.exe`）

结果：**BUILD SUCCESSFUL**，三道门禁全绿：

```
validateAnchors        PASSED - 7 invariants hold
validateFormsRegistry  PASSED - all invariants hold
validateSystems        PASSED - 8 invariants hold
```

---

## 7. 已知边界 / 后续

- 锚点的**视觉与音效表现**目前使用各族群粒子（`ModParticles`）+ 环状/连线轨迹，
  尚未为每个锚点定制专属粒子形状（规格 5.3 的「法阵语言」细化仍在第 5 章范围）。
- 客户端**自定义粒子 provider 注册**（`ParticleProvider`）尚未接入，
  当前依赖服务端 `sendParticles` 发送原版渲染类型。
- 规格 6.3 中部分锚点的深层规则（如空间锚的「三点空间网络」完整交互、
  职业锚与村民职业数据表的联动）目前实现到 B/C 级行为，尚未与第 7 章变种轴联动。
- 命令 `/golem anchor` 诊断入口尚未接线（`AnchorRuntime.trackedCompanions()` 已就绪）。
