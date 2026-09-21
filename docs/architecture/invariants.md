# 跨系统约束

这里回答跨模块修改必须保持哪些性质，以及哪些性质由当前测试直接约束。
下列要求是开发边界，不能据此声称所有输入都已获得运行时证明。

<a id="determinism"></a>
## 确定性

同一完整生成输入必须得到同一规划结果：随机选择使用稳定键，遍历与并行归并保持稳定顺序，
搜索用操作预算而非墙钟时限终止。输入身份实际包含什么、哪些变化不会自动入 hash，
统一见 [计划身份](../reference/plan-v2.md#identity)。

缓存只允许复用可重算结果；命中、淘汰、线程或区块访问顺序不能成为新随机输入。
验证时同时检查冷缓存、热缓存、恢复和负坐标，不能仅重复相同查询顺序。
随机调用来源见 [RandomDomains](../../neoforge/src/main/java/io/github/luoyan/adventureworldgen/plan/RandomDomains.java)，
源码清单测试用于发现新增 domain；计划 payload 不重复保存一份无法校验的 domain 清单。
当前气候缓存的验证缺口见 [环境查询边界](../systems/terrain.md#query-limits)。

<a id="frozen"></a>
## 冻结计划

发布前必须完成规划和验证；运行时查询不得重新分配群系、移动结构或补抽实例。
恢复可以重新组装确定性函数及索引，不得重新求解已冻结布局。
同一结构实例跨多个区块仍只计一次。

发布后的缓存可变不等于计划内容可变；缓存写入不得改变冻结归属和几何。
磁盘身份不匹配时的重新规划属于启动过程，不能称为已有区块迁移。

<a id="dependency"></a>
## 依赖边界

下表总结 [PackageBoundaryTest](../../neoforge/src/test/java/io/github/luoyan/adventureworldgen/PackageBoundaryTest.java)
实际检查的禁用引用前缀。测试扫描剔除注释后的源码，包括全限定引用；这是源码依赖护栏。

| 包 | 禁止引用 |
|---|---|
| `config` | Minecraft、NeoForge、`planner`、`runtime` |
| `plan` | Minecraft、NeoForge、`planner`、`runtime`、`config`、`hydrology` |
| `spatial` | 其他本项目包 |
| `noise` | `planner`、`runtime`、`terrain`、`config` |
| `api` | `planner`、`runtime`、`hydrology`、`terrain`、`surface`、`worldgen` |
| `planner` | Minecraft、NeoForge、`runtime`、`worldgen` |
| `terrain` | `planner`、`runtime`、`config` |
| `biome` | `planner`、`runtime` |
| `climate` | `planner`、`runtime` |
| `hydrology`、`erosion` | Minecraft、NeoForge、`biome` |
| `compat` | `runtime`、`planner`、`worldgen`、`terrain`、`hydrology`、`config` |
| `runtime` | Minecraft、NeoForge |
| `persistence` | `runtime` |
| `surface`（出现时生效） | `hydrology`、`planner`、`runtime`、`worldgen` |

测试要求已声明的生产包存在且非空；`surface` 是唯一允许缺席的预留包。
它还检查 planner 不引用结构执行模型，并禁止 `worldgen.structure` 读取 planner、runtime、config、persistence 或 plan。
没有列出的方向不等于鼓励添加依赖，仍应审查职责与环路。

<a id="ownership"></a>
## 地形和材料所有权

| 职责 | 所有者 | 不应越界做的事 |
|---|---|---|
| 区域配方、岸线、山脉与海床 | `terrain` | 读取整份作者配置并求解需求 |
| 需求驱动的地形容量承诺 | `planner` | 在最终生成列上按群系限制截平高度 |
| 侵蚀增量与高度滤波 | `erosion` | 查询时重新模拟水滴或选择材料 |
| 河网、水位、湖湿地、切削和封岸量化 | `hydrology` | 决定河床方块与 Minecraft 群系 |
| 气候准入与归属过渡 | `biome` | 重画地形或持有游戏生命周期 |
| 道路路网、坡度与施工列 | `planner` / `plan` | 在区块期重新寻路或移动已冻结端点 |
| 最终方块和 surface rules | `worldgen` 与原版执行管线 | 重新规划地块或改变冻结锚点 |

连续采样使用同一组冻结输入；网格是用途相关的采样与归属表示，不是另一套低精度地形公式。
原生结构地基是区块期显式适配，不能混同为已反馈到成本图的宏观高度。

<a id="constraints"></a>
## 约束层级

硬条件先决定合法域，软偏好只排序合法候选，优化不得破坏已有硬条件。
Adventure level 是位置软偏好，不是互斥空间区间。
允许的温湿度类型与类型权重是两件事：集合控制准入，权重控制合法候选评分。
面积最低值、目标、上限及不同阶段的失败边界见 [规划系统](../systems/planning.md#constraints)。

不能把有界搜索失败报告成全空间无解，也不能用评分放宽结构数量、间距或环境准入。
当前实现的能力限制必须在对应系统文档中保留，并用 [测试与诊断](../development/testing.md) 验证。

<a id="structure-boundary"></a>
## 结构规划边界

- `StructurePlanningInfo` 是结构类型向 planner 提供固有规划事实的唯一通道。
- `StructureDemand` 是作者需求，不是结构自身属性。
- `PlannedStructurePlacement` 是宏观锚点，不是 Minecraft 结构起点或最终原点。
- planner 只产生纯规划数据，不产生 Minecraft 对象，也不得按具体 structure ID 写特例。
- worldgen 在 READY 前按有界契约验证候选、导出纯实例范围；区块桥接层消费冻结锚点，三类执行器重建原生起点。planner 不调用生成实现。
- 同 ID 同起点区块冲突必须失败；生成失败不得跳过或移动实例。
- 原生起点是 pieces 和执行地基描述的存档权威，不在全局计划保存第二套执行几何。
- 地基过渡带也必须建立区块引用；结构及适配范围必须通过原生引用半径校验。
- 已完成 FEATURES 的区块不在加载时重放；Java 结构不做通用平移。
- 当前 carrier 只表达群系 ownership；结构范围是道路避让输入，不自动改变 carrier 或承诺平整度。
- 结构禁入范围与是否连接道路独立；资源预检与原生实例验证只向规划层提供纯水平范围；启动临时构造的 pieces 不进入全局计划。
- 如果 `StructurePlanningInfo` 新增会影响 planner 输出的字段，这些字段必须进入计划输入身份；同时审查算法版本、
  冻结格式和 READY 失效条件，不能让旧 READY 在结构规划输入已变化时继续命中。

## 道路覆盖

道路在发布前冻结完整施工列；恢复只重建索引，区块执行不重新寻路。自然地形保留为规划输入，
道路覆盖在区块列、基础高度和基础列查询中保持一致；桥下水体不能被路基填平。
结构接入元信息经 `StructurePlanningInfo` 传入，并进入输入摘要；具体边界见 [道路系统](../systems/roads.md)。
