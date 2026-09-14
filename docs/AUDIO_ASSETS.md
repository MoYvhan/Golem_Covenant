# 音频资产清单 / Audio Asset Manifest

规格 11.12 的补齐项。本文件是音频资产的**权威清单**：代码侧已经注册好全部
SoundEvent（见 `registry/ModSounds.java` 与 `assets/golem_covenant/sounds.json`），
本文件描述每个事件期望的 ogg 文件与预算。

## 1. 格式与约束

| 项 | 要求 |
|---|---|
| 格式 | Ogg Vorbis，单声道，44.1 kHz |
| 单文件时长上限 | 4.0 秒（仪式音），2.0 秒（遗志触发音） |
| 响度 | 归一化至 -16 LUFS，峰值不超过 -1 dBTP |
| 自定义资源包 | **支持**。资源包提供同名 ogg 即可覆盖，无需改代码 |
| 缺失行为 | 事件仍注册成功；MC 记录一条 missing-sound 警告，不崩溃 |

## 2. 清单

### 2.1 仪式基础音（3 套，规格 5.3）

| SoundEvent | 文件路径 | 时长上限 | 用途 |
|---|---|---|---|
| `golem_covenant:ritual_a` | `sounds/ritual_a.ogg` | 2.0s | A 级借魂：短促单层金环 |
| `golem_covenant:ritual_b` | `sounds/ritual_b.ogg` | 3.0s | B 级觉醒：双层法阵 |
| `golem_covenant:ritual_c` | `sounds/ritual_c.ogg` | 4.0s | C 级圣契：多层法阵 + 核心 |

### 2.2 死亡遗志触发音（6 套，按主题，规格 8.3 / 11.12.1）

| SoundEvent | 文件路径 | 主题 | 对应遗志示例 |
|---|---|---|---|
| `golem_covenant:death_will_guard` | `sounds/death_will_guard.ogg` | 护卫型 | 伤害减免 / 抗击退 / 紧急位移保护 |
| `golem_covenant:death_will_scout` | `sounds/death_will_scout.ogg` | 侦察型 | 夜视 / 侦测 / 追踪 / 回声钟 |
| `golem_covenant:death_will_heal` | `sounds/death_will_heal.ogg` | 治疗型 | 生命恢复 / 经验辅助 |
| `golem_covenant:death_will_element` | `sounds/death_will_element.ogg` | 元素型 | 火焰抗性 / 寒冷抗性 / 雷抗 / 水下能力 |
| `golem_covenant:death_will_control` | `sounds/death_will_control.ogg` | 控制型 | 最后织命网 / 蜂巢残片 |
| `golem_covenant:death_will_mobility` | `sounds/death_will_mobility.ogg` | 机动型 | 移速 / 跳跃强化 / 空间门 |

### 2.3 锚点专属音（可选扩展，12～20 套）

规格 11.12.1 建议「按锚点分类的专属音效」。当前实现**复用上表 6 个主题音**，
按 `deathWillProfile.effect` 归入主题（映射见 `DeathWillEngine.EFFECTS` 的
`kind` 字段）。若要升级为逐锚点专属音，需：

1. 在 `sounds.json` 增加 `anchor_<anchorId>` 条目；
2. 在 `ModSounds.register()` 增加对应 `reg("anchor_" + id)`；
3. 在 `DeathWillEngine` 触发处按 `anchorId` 选择事件。

在提供实际资产前不建议开启，否则会产生 12～20 个 missing-sound 警告。

## 3. 授权来源

| 方案 | 说明 | 合规风险 |
|---|---|---|
| 自制（推荐） | 用合成器 / 采样生成原创音效 | 无 |
| 开源素材 | 仅限 CC0 / CC-BY（需在 `LICENSE_AUDIO` 署名） | 低，须保留署名 |
| 商用授权 | 需保留授权凭证至仓库 `licenses/` | 中，需存档 |

**禁止**：直接使用 Minecraft 原版音效文件（版权归属 Mojang）。
本模组代码中引用的 `SoundEvents.*` 是对原版事件的**合法调用**，不属于复制资产。

## 4. 当前状态

- [x] 全部 9 个 SoundEvent 已在代码中注册
- [x] `sounds.json` 已生成，事件名与路径映射完整
- [ ] ogg 资产待补齐（缺失时仅告警，不影响运行与其他功能）
