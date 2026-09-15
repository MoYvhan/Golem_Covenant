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
- [x] 9 个 ogg 资产已生成（自制合成，无第三方素材，无版权风险）
- [x] 资产生成与校验已纳入构建门禁（`generateSounds` / `validateSounds`）

### 4.1 资产来源与生成方式

9 个 ogg 全部由 `tools/gen_sounds.py` **程序化合成**（`tools/vorbis_encoder.py`
为项目自带的纯 Python Ogg Vorbis 编码器），不引用任何外部素材，因此
`LICENSE_AUDIO` 无需署名条目，也不存在 Mojang 原版音效的版权问题。

| 文件 | 时长 | 说明 |
|---|---|---|
| `ritual_a.ogg` | 1.90s | A 级单次上升铃音 |
| `ritual_b.ogg` | 2.74s | B 级五阶段（主环 → 核心 → 微光 → 撞击爆发） |
| `ritual_c.ogg` | 3.72s | C 级七阶段（三重环 + 符文渐强 + 6 次锻造击打 + 外爆/内锁） |
| `death_will_guard.ogg` | 1.63s | 守护型遗志 |
| `death_will_scout.ogg` | 1.44s | 侦察型遗志 |
| `death_will_heal.ogg` | 1.72s | 治疗型遗志 |
| `death_will_element.ogg` | 1.53s | 元素型遗志 |
| `death_will_control.ogg` | 1.63s | 控制型遗志 |
| `death_will_mobility.ogg` | 1.11s | 机动型遗志 |

规格 5.3 要求六个形态之间至少 3 项不同，音效节奏是其中一项，因此三套
仪式音在时长与节奏结构上刻意拉开差异（1.90 / 2.74 / 3.72 秒）。

### 4.2 门禁

`validateSounds` 是一个**独立解码器**：它不复用编码器的任何代码，而是
按 RFC 3533 与 Vorbis I 规范重新解析磁盘上的字节。这样编码器的 bug
无法"自我认证"。它检查：

1. Ogg 页结构、**重算 CRC**、BOS/EOS 标记、页序连续性；
2. 跨页包重组（含 continued 标志）；
3. identification header（版本 / 声道数 / 采样率 / 块大小 / framing bit）；
4. comment header（vendor、注释列表、framing bit）；
5. setup header 完整遍历（codebook / time / floor1 / residue / mapping / modes
   的每一个字段，含带 `-1` 偏移的计数字段）；
6. 音频包中的 floor 曲线**是否真的有信号**（全零即静音，属于结构合法但
   不可用的失败模式）；
7. `sounds.json` 与磁盘文件的一一对应。

> 注意：`validateSounds` 依赖 `generateSounds` 的输出目录，因此
> `build.gradle` 中已声明 `dependsOn`，否则会校验到陈旧或缺失的目录。
