# AdventureWorldGen 文档体系彻底重构任务

仓库：

`https://github.com/SuperUnom/AdventureWorldGen`

## 0. 任务目标

对 AdventureWorldGen 的整个文档体系进行一次**彻底重构**。

这不是一次“整理现有 Markdown”“移动文件”“补充索引”或“修正文档链接”的任务。

目标是：

> 从“历史演化记录驱动的文档体系”，重建为“当前系统模型驱动的开发文档体系”。

最终文档只回答四类问题：

1. 当前系统是什么；
2. 当前代码在哪里；
3. 修改某部分需要遵守什么约束；
4. 修改后如何验证。

原则上不要在 canonical documentation 中解释：

* 某规则在 r14 / r21 / r28 / r33 中是如何改变的；
* 某次重构做了什么；
* 某个 bug 曾经怎样修复；
* 某个历史版本是什么状态；
* 某次测试曾经是多少项；
* 某个旧实现为什么被替换。

这些信息已经属于 Git 历史。

需要调查设计来源时，应依赖：

```bash
git log
git blame
git show
```

而不是继续维护 Markdown 版历史数据库。

---

# 1. 第一原则：以当前代码为事实基础

重构文档之前，必须完整检查当前仓库。

不要默认现有文档正确。

当前代码、自动化测试、实际配置和持久化格式，是判断“当前实现是什么”的主要依据。

尤其需要检查：

```text
neoforge/src/main/java/io/github/luoyan/adventureworldgen/
neoforge/src/main/resources/
neoforge/src/test/
neoforge/src/testmod/
neoforge/tools/
```

重点检查：

```text
PackageBoundaryTest
PlanVersions
RuntimePlanner
PlanAssembly
GeneratedAdventurePlan
AdventureChunkGenerator
AdventureBiomeSource
RuntimePlanRegistry
PlanV2Codec
AdventureWorldConfig
AdventureWorldConfigParser
default.json
```

以及当前所有 planner / terrain / hydrology / climate / biome / runtime / worldgen 相关实现。

不得因为旧文档写了某件事，就认为当前实现仍然如此。

当文档与代码不一致时：

1. 查代码；
2. 查测试；
3. 必要时查相关 Git 提交；
4. 得出当前事实；
5. 新文档只记录当前事实。

---

# 2. 文档重构的核心原则

## 2.1 Canonical 文档只描述当前状态

例如不要写：

```text
自 r14 起 adventure level 改为软约束。
```

应该写：

```text
Adventure level 是位置软偏好，不是互斥空间区间。
```

不要写：

```text
r29 放宽了 area.min。
```

应该直接说明当前 `area.min` 的实际语义。

不要写：

```text
r21 引入了 Terrain Recipe。
```

应该说明当前 Terrain Recipe 是什么、由谁调用、承担什么职责。

---

## 2.2 一个事实只能有一个 source of truth

避免同一个事实分别存在于：

```text
README
设计原则
代码全景图
实现状态报告
重构实施记录
某个 rNN 变更说明
NeoForge README
```

需要明确 source of truth。

例如：

### 默认 profile

source of truth：

```text
neoforge/src/main/resources/data/adventureworldgen/adventureworldgen/profiles/default.json
```

文档只解释字段语义，不复制大量默认值。

### Plan 版本

source of truth：

```text
plan/PlanVersions.java
```

文档解释版本作用，不在多个文档中复制字符串。

### 架构依赖

source of truth：

```text
PackageBoundaryTest
```

架构文档总结这些约束，但测试负责真正锁定它们。

### 当前测试数量

不要写死在长期文档中。

测试数量会变化。

只记录如何运行测试以及每组测试负责什么。

---

# 3. 删除历史文档体系

以下目录原则上从当前文档体系删除：

```text
docs/变更记录/
docs/历史版本/
docs/重构/
docs/实验/
```

以下文档原则上删除：

```text
docs/实现状态与验收报告.md
docs/代码全景图.md
docs/待讨论问题.md
```

除非其中存在无法从代码、测试或 Git 历史恢复、并且对于当前开发仍然必要的信息。

如果发现这种信息：

不要保留原文档。

应把仍然有效的内容抽取到新的 canonical 文档，然后删除旧文档。

---

# 4. 不要保留“代码全景图”这种文档形态

当前 `代码全景图.md` 过于庞大，并且大量复制：

* 类；
* 方法；
* 调用链；
* 文件行号；
* 测试数量；
* 当前实现细节。

这种文档会快速腐烂。

新的架构文档应该解释：

```text
模块职责
主要数据流
重要边界
关键入口
稳定 invariant
```

不要逐文件复述代码。

不要尝试把 132 个 Java 文件全部写进文档。

不要写容易变化的行号。

---

# 5. 建立新的文档结构

最终建议采用：

```text
README.md
AGENTS.md

docs/
├── README.md
│
├── architecture/
│   ├── overview.md
│   ├── pipeline.md
│   └── invariants.md
│
├── systems/
│   ├── terrain.md
│   ├── planning.md
│   └── runtime-worldgen.md
│
├── reference/
│   ├── profile.md
│   ├── plan-v2.md
│   ├── adapters.md
│   └── ftf-provenance.md
│
└── development/
    ├── testing.md
    ├── debugging.md
    └── change-guide.md
```

如果检查代码后认为某个文件应该合并或拆分，可以调整。

但最终 canonical 文档应控制在约：

```text
10～15 份
```

而不是再次扩张成几十份。

---

# 6. README.md

根 README 只承担项目入口职责。

建议控制在约 100～150 行。

需要说明：

## 项目是什么

AdventureWorldGen 是一个面向 Minecraft 整合包作者的冒险世界宏观编排系统。

说明：

* 有限大陆；
* 宏观地形；
* 水文；
* 气候；
* 群系；
* 结构；
* 冒险进度；
* 冻结规划；
* Minecraft worldgen 执行。

不要介绍历史版本。

---

## 当前技术环境

从 Gradle 和工程配置读取：

```text
Minecraft
NeoForge
Java
```

不要凭旧文档猜测。

---

## 快速开发

例如：

```bash
cd neoforge
./gradlew build
./gradlew runClient
```

实际命令以当前工程为准。

---

## 文档入口

链接：

```text
docs/README.md
AGENTS.md
```

---

## 测试入口

只说明：

```text
unit tests
default GameTest
full GameTest
audit tools
```

不要写死“当前有 233 项”。

---

# 7. AGENTS.md

新增仓库根目录：

```text
AGENTS.md
```

这个文件专门服务于：

* Codex；
* ChatGPT；
* Claude Code；
* 其他代码代理；
* 新开发者。

它必须足够短，建议：

```text
100～250 行
```

内容包括：

## 项目模型

一句话解释 AdventureWorldGen。

---

## 包职责

至少解释：

```text
api
config
plan
spatial
noise

climate
biome
hydrology
erosion
terrain

cost
planner

persistence
runtime

compat
worldgen
client
mixin
```

每个包最多几句话。

---

## 架构约束

根据当前 `PackageBoundaryTest` 提炼。

例如当前代码中的重要方向包括：

```text
config 不依赖 Minecraft / NeoForge

planner 不依赖 runtime

plan 不依赖 planner / runtime / config

hydrology / erosion 不决定 Minecraft 方块或群系

terrain 不依赖 planner

terrain 不直接读取作者 config

biome 不依赖 planner 和游戏生命周期

runtime 不依赖 Minecraft / NeoForge

climate 不依赖 planner

persistence 不依赖 runtime
```

具体规则必须以当前测试代码为准。

---

## Source of truth

告诉代理：

```text
配置 schema 去哪里看
默认 profile 去哪里看
plan 版本去哪里看
架构约束去哪里看
测试入口去哪里看
```

---

## 修改代码时的规则

例如：

```text
不要让底层算法层直接依赖 Minecraft。

不要为了方便跨越 package boundary。

不要通过复制状态构造第二个 source of truth。

修改冻结计划格式必须检查 plan identity 和 READY compatibility。

修改地形算法必须检查 deterministic output。

修改 planner 必须保证 hard constraints 不被 scoring 放宽。
```

---

# 8. docs/README.md

这个页面成为整个开发文档的唯一目录。

不要再列：

```text
r6
r7
r8
...
r33
```

按照问题分类：

### 第一次了解项目

```text
architecture/overview.md
architecture/pipeline.md
```

### 修改地形

```text
systems/terrain.md
```

### 修改规划

```text
systems/planning.md
```

### 修改 Minecraft 接入

```text
systems/runtime-worldgen.md
```

### 修改配置

```text
reference/profile.md
```

### 修改 plan 格式

```text
reference/plan-v2.md
```

### 接入模组内容

```text
reference/adapters.md
```

### 测试

```text
development/testing.md
```

---

# 9. architecture/overview.md

目标：

> 让开发者在 5～10 分钟内理解系统分层。

重点描述：

```text
author configuration
        ↓
planning domain
        ↓
frozen plan
        ↓
runtime query
        ↓
Minecraft worldgen
```

给出当前 package 层级。

可以使用 Mermaid。

例如逻辑关系：

```text
config
   ↓
planner
   ↓
plan
   ↓
runtime
   ↓
worldgen
```

但必须根据真实代码依赖画完整图。

同时解释：

```text
climate
biome
terrain
hydrology
erosion
cost
```

分别属于什么层。

不要解释历史重构。

---

# 10. architecture/pipeline.md

描述一次完整世界生命周期。

至少覆盖：

## Profile reload

JSON 如何：

```text
parse
validate
resolve content
```

---

## Initial planning

说明：

```text
RuntimePlanner
```

如何构造最终 plan。

根据当前代码总结真实阶段。

不要沿用旧文档的步骤编号，除非当前代码本身存在稳定的 PlanningStage。

---

## Freeze / publish

说明：

```text
GeneratedAdventurePlan
PlanAssembly
PlanSnapshot
AtomicPlanRepository
PlanV2Codec
```

之间的关系。

---

## READY reload

解释：

已有 plan 如何恢复，哪些东西不会重新计算。

---

## Chunk generation

说明：

```text
AdventureChunkGenerator
AdventureBiomeSource
Structure handling
Surface rules
```

如何消费冻结 plan。

---

# 11. architecture/invariants.md

这里只记录真正稳定的跨系统约束。

例如：

### Determinism

相同的：

```text
world seed
canonical profile
planner/profile version
algorithm versions
```

必须产生相同规划结果。

具体身份组成需要读取当前实现确认。

---

### Frozen-plan invariant

计划发布后：

运行时只能读取冻结结果，不得偷偷重新规划。

---

### Dependency invariant

总结 `PackageBoundaryTest`。

---

### Terrain ownership

明确：

```text
hydrology 负责什么
erosion 负责什么
terrain 负责什么
surface rule 负责什么
```

防止以后职责重新混杂。

---

### Planning constraint hierarchy

明确：

```text
hard constraints
soft preference
optimization
```

之间的区别。

尤其避免把 adventure level 等软偏好重新实现成 hard gate。

---

# 12. systems/terrain.md

整合原来的：

```text
海岸轮廓生成算法
粗细地形生成算法
生成器参数与执行约定 中的 terrain 内容
历次 terrain change note 中仍然有效的最终结论
```

但最终文档不能出现历史叙事。

建议结构：

```text
Terrain pipeline

Coast
Region terrain
Terrain recipes
Mountains
Hydrology
Erosion
Ocean bathymetry
Climate interaction
Final terrain query
```

每部分说明：

```text
职责
输入
输出
冻结时机
依赖
重要 invariant
主要实现类
主要测试
```

不要逐方法解释。

---

# 13. systems/planning.md

整合：

```text
cost
adventure level
required biome
terrain capacity
biome allocation
structure placement
joint planning
filler layout
```

重点解释当前 planner 的数据模型。

建议覆盖：

```text
RequirementExpander
CostPlanner
AdventurePreference
TerrainCapacitySolver
BiomeAllocationPlanner
JointPlanner
FillerLayout
```

根据当前代码调整。

明确区分：

```text
hard constraint
soft positional preference
optimization score
fallback / relaxation
failure
```

这一点非常重要。

---

# 14. systems/runtime-worldgen.md

负责：

```text
Frozen plan
RuntimePlanRegistry
AdventureBiomeSource
AdventureChunkGenerator
structure restore/injection
surface generation
spawn
Minecraft lifecycle
```

必须解释：

```text
planning-time
runtime
chunk-time
```

三者之间的边界。

---

# 15. reference/profile.md

从：

```text
AdventureWorldConfig
AdventureWorldConfigParser
default.json
```

重建配置文档。

文档重点是：

```text
字段语义
类型
required / optional
default 行为
validation
interaction
```

不要把当前 default profile 整份复制进去。

可以给一个最小示例配置。

真正默认值如果容易变化，应指向 `default.json`。

---

# 16. reference/plan-v2.md

这是非常重要的开发契约。

必须明确：

```text
plan-v2 是什么
为什么需要冻结
包含哪些逻辑数据
READY 状态是什么
PlanIdentity 如何工作
PlanVersions 的作用
哪些修改会 invalidate plan
codec 在哪里
原子写入如何工作
```

不要复制大量 binary/text 格式实现细节，除非这是稳定公开协议。

如果 plan-v2 只是内部格式，要明确说明。

---

# 17. reference/adapters.md

重构现有：

```text
数据包与适配器开发.md
```

围绕当前公开 API：

```text
BiomeAdapter
StructureAdapter
AdapterRegistry
FrozenPieceSupport
```

以及真实可扩展点来写。

必须区分：

```text
public extension contract
vanilla compatibility implementation
internal worldgen implementation
```

不要让第三方适配器依赖内部 planner 实现。

---

# 18. reference/ftf-provenance.md

不要保留一份 100 KB 以上的 FreeTerraForged 手册作为主要开发文档。

只保留：

```text
upstream project
固定参考 commit
license
哪些思想/算法被采用
哪些地方被 AdventureWorldGen 修改
哪些算法没有移植
对应代码模块
```

如果需要保留详细研究资料，应与 canonical docs 分离，例如：

```text
research/
```

并且 `docs/README.md` 默认不把它列为开发必读资料。

---

# 19. development/testing.md

整合所有测试入口。

按照测试目的，而不是历史阶段组织。

至少区分：

```text
JUnit
GameTest default group
GameTest full suite
READY reload tests
performance/capacity/planning tests
audit / preview tools
```

给出实际命令。

不要写死长期会变化的数量。

说明：

```text
什么情况下必须跑什么
```

例如：

修改纯算法：

```text
./gradlew test
```

修改 worldgen：

```text
unit + GameTest
```

修改 structure：

运行 structure / READY 对应测试。

修改 planner：

运行 planner unit tests + planning GameTest。

---

# 20. development/debugging.md

建立开发故障入口。

重点是：

```text
profile parse failure
UNSUPPORTED_CONTENT
planning failure
RESOURCE_LIMIT
READY load failure
plan identity mismatch
structure restore failure
terrain visual debugging
```

说明去哪里看：

```text
日志
PlanDiagnostics
audit tools
preview tools
```

---

# 21. development/change-guide.md

这是后续开发效率最高的一份文档。

用任务导向的表格。

例如：

| 我要修改            | 阅读               | 主要代码                    | 验证                    |
| --------------- | ---------------- | ----------------------- | --------------------- |
| 海岸              | terrain          | `terrain/Coast*`        | coast + terrain tests |
| 山脉              | terrain          | `terrain/Mountain*`     | terrain preview/tests |
| 河流              | terrain          | `hydrology/`            | hydrology audit       |
| 侵蚀              | terrain          | `erosion/`              | terrain regression    |
| 温度              | terrain/planning | `climate/`              | climate tests         |
| 群系选择            | planning         | `biome/`, `planner/`    | biome/planning tests  |
| 到达成本            | planning         | `cost/`                 | cost tests            |
| 结构规划            | planning         | `planner/`              | planning GameTest     |
| plan 格式         | plan-v2          | `plan/`, `persistence/` | codec/READY tests     |
| Minecraft chunk | runtime-worldgen | `worldgen/`             | full GameTest         |
| adapter         | adapters         | `api/`, `compat/`       | integration tests     |

实际名称必须以当前源码为准。

---

# 22. Roadmap 不属于开发规范

现有 `待讨论问题.md` 中真正属于未来需求的内容，例如：

```text
道路
其他外围环境
其他 placement mode
更复杂海岸
垂直群系
```

不要继续维护一个 Markdown backlog。

如果仓库启用了 GitHub Issues：

应该迁移为 issue。

如果当前不创建 issue，则可以完全删除，不必为了“以后可能有用”继续保留。

---

# 23. Git 历史保留

这次清理的是：

```text
working tree 中的历史文档
```

不要重写 Git commit 历史。

不要：

```text
filter-branch
filter-repo
force push
```

Git 历史应继续保存设计演化记录。

---

# 24. 文档书写规范

所有文档使用中文。

代码标识保持英文。

每份文档开头直接说明该文档回答的问题。

避免：

```text
本文将……
首先……
接下来……
在之前版本中……
经过 rXX……
本轮……
历史上……
```

优先使用：

```text
X 的职责是……
输入为……
输出为……
该层不得……
source of truth 是……
```

---

# 25. 控制重复

重构后检查所有 Markdown。

相同事实不能在不同文件完整重复。

允许：

```text
一句摘要 + 链接 canonical definition
```

不允许：

```text
复制整段规则
```

---

# 26. 自动检查文档

重构完成后至少检查：

```text
所有相对 Markdown 链接
所有锚点
所有引用文件路径
所有提到的 Java 类是否存在
所有 Gradle / shell 命令是否存在对应任务或脚本
```

搜索并清理 canonical docs 中的历史残留：

```text
r6
r7
...
r33
P0
P1
P2
P3
P4
P5
本轮
重构阶段
历史版本
```

如果这些词仍存在，逐项确认是否真的必要。

---

# 27. 不允许的做法

不要：

### 只移动旧文件

这不是目录整理任务。

### 给所有历史文档加“已过时”标签

应该删除。

### 创建新的巨大“总设计文档”

任何单一文档都不应该再次发展为 100 KB 级别。

### 手工复制源码结构

代码自己就是源码结构。

### 保留逐 commit 实施记录

Git 已经承担该职责。

### 为了“不丢信息”留下所有东西

本次目标本身就是降低信息噪声。

---

# 28. 执行流程

请按照以下顺序执行。

## Phase 1：审计

阅读：

```text
当前代码
测试
resource
现有 docs
最近关键 Git history
```

建立：

```text
current-system model
```

不要修改代码。

---

## Phase 2：内容抽取

从旧文档提取仍然有效的信息。

分类进入：

```text
architecture
systems
reference
development
```

任何纯历史信息直接舍弃。

---

## Phase 3：建立新文档

创建新的目录和文档。

先完成：

```text
AGENTS.md
README.md
docs/README.md
architecture/*
```

然后再写：

```text
systems/*
reference/*
development/*
```

---

## Phase 4：删除旧体系

删除已经被新文档覆盖的旧文件和历史目录。

不要同时保留新旧两个体系。

---

## Phase 5：交叉验证

对照当前代码检查：

```text
package
class
version
pipeline
test command
config
plan
```

---

## Phase 6：最终报告

最终给出：

### 删除了什么

按类别说明即可，不需要逐段解释历史。

### 新建了什么

说明每份 canonical 文档的职责。

### 合并了什么

例如：

```text
海岸 + 粗细地形 + terrain 参数
→ systems/terrain.md
```

### Source of truth

列出：

```text
配置
版本
架构边界
测试
持久化
```

各自的真实来源。

### 仍然存在的文档风险

如果发现代码本身职责不清、文档无法准确描述，也应指出。

不要为了让文档看起来整洁而掩盖架构问题。

---

# 29. 最终验收标准

重构完成后，一个第一次接触项目的开发者应该能够：

### 10 分钟内

知道 AdventureWorldGen 的总体架构。

### 20 分钟内

找到：

```text
terrain
planner
runtime
worldgen
config
plan
```

分别在哪里。

### 修改某模块前

能快速知道：

```text
应该读哪个文档
不能违反哪些 invariant
需要运行哪些测试
```

### 不需要阅读

```text
r6～r33
P0～P5
旧计划格式
历史验收报告
逐 commit 实施记录
```

就可以继续当前开发。

最终文档体系应体现一个基本原则：

> Git 解释项目是如何走到今天的；文档解释项目今天是什么。
